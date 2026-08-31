# Terraform / Tofu Login — Configurable Token Duration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let `terraform login` / `tofu login` against Terrakube issue a revocable Terrakube PAT whose lifetime the user picks at login time, bounded by an admin cap.

**Architecture:** Add a minimal, feature-flagged OAuth2 authorization broker to the Terrakube **API** (`/oauth/authorize`, `/oauth/callback`, `/oauth/consent`, `/oauth/token`). The CLI talks to Terrakube; Terrakube runs the real auth-code+PKCE flow upstream against Dex; after a server-rendered consent page where the user picks a duration, Terrakube mints a normal `iss: "Terrakube"` PAT via the existing `PatService` and returns it to the CLI. Token *validation* is unchanged everywhere. When the flag is off, `.well-known` and the flow are byte-for-byte what they are today.

**Tech Stack:** Java 21, Spring Boot 3 (`spring-boot-starter-web`, Spring Security OAuth2 resource server), Spring Data JPA, Liquibase, `io.jsonwebtoken` (jjwt), Spring `WebClient`, JUnit 5, RestAssured, WireMock (`wiremock-spring-boot`), Mockito; UI is React 19 + Ant Design + Vite + Vitest.

**Spec:** [docs/superpowers/specs/2026-08-31-terraform-login-token-duration-design.md](../specs/2026-08-31-terraform-login-token-duration-design.md)

## Global Constraints

- New Java code lives under `api/src/main/java/io/terrakube/api/plugin/token/login/` (broker logic) and `api/src/main/java/io/terrakube/api/rs/token/login/` (entity), except where a task names a different existing file.
- Feature flag property: `io.terrakube.token.login.enabled` (default `false`). When `false`: `/oauth/**` returns 404 and `.well-known/terraform.json` is unchanged.
- Fixed public client id advertised to the CLI: `terraform-cli`.
- Loopback redirect port range: `10000`–`10010` inclusive. The same range is advertised in `.well-known` `ports` in **both** flag states.
- PKCE: `S256` only. Reject `code_challenge_method=plain`.
- The minted bearer JWT is **never** persisted — only the `pat` metadata row is.
- Token duration bounds: `1 <= days <= io.terrakube.token.login.max-days`, and `max-days` is itself clamped to `<= 365`.
- Dates in JPA entities use `java.util.Date` (match `GenericAuditFields`).
- Liquibase changeSet ids in a new file use the kebab form `2-34-0-<name>`, `author="jake"`, matched by `(id, author, filename)`.
- Commit after every task with a Conventional Commits message (`feat:`, `test:`, `refactor:`, `chore:`). Do not add `Co-Authored-By` trailers.
- Run API tests with `./mvnw -pl api test -Dtest=<ClassName>` from the repo root (Maven wrapper is `./mvnw`). Registry: `-pl registry`. UI: `cd ui && npx vitest run <path>`.

---

### Task 1: `TerraformLoginProperties` + configuration wiring

**Files:**
- Create: `api/src/main/java/io/terrakube/api/plugin/token/login/TerraformLoginProperties.java`
- Create: `api/src/test/java/io/terrakube/api/plugin/token/login/TerraformLoginPropertiesTest.java`
- Modify: `api/src/main/resources/application.properties` (after line 248, the `io.terrakube.token.*` block)
- Modify: `api/src/test/resources/application-test.properties` (near the `io.terrakube.token.*` block, ~line 88)
- Modify: `docker-compose/docker-compose.yml` (the `x-api-environment`/anchor block that defines `PatSecret`, ~line 30 and ~line 84)
- Modify: `docker-compose/config-ldap.yaml` (comment only, in `staticClients[0].redirectURIs`)

**Interfaces:**
- Produces: `io.terrakube.api.plugin.token.login.TerraformLoginProperties` — a `@ConfigurationProperties(prefix = "io.terrakube.token.login")` `@Component` with:
  - `boolean isEnabled()` (default `false`)
  - `int getDefaultDays()` (default `30`)
  - `int getMaxDays()` (default `90`, clamped to `[1, 365]` in a `@PostConstruct`)
  - `String getApiUrl()` (no default; when `enabled` and blank → throw on startup)
  - `int getCleanupIntervalMs()` (default `300000`)
  - constant `String CLIENT_ID = "terraform-cli"`, `int PORT_LOW = 10000`, `int PORT_HIGH = 10010`

- [ ] **Step 1: Write the failing test**

```java
package io.terrakube.api.plugin.token.login;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerraformLoginPropertiesTest {

    @Test
    void defaultsAreSafe() {
        TerraformLoginProperties p = new TerraformLoginProperties();
        assertFalse(p.isEnabled());
        assertEquals(30, p.getDefaultDays());
        assertEquals(90, p.getMaxDays());
        assertEquals(300000, p.getCleanupIntervalMs());
    }

    @Test
    void maxDaysIsClampedToOneYear() {
        TerraformLoginProperties p = new TerraformLoginProperties();
        p.setMaxDays(100000);
        p.normalize();
        assertEquals(365, p.getMaxDays());
    }

    @Test
    void maxDaysBelowOneIsClampedUp() {
        TerraformLoginProperties p = new TerraformLoginProperties();
        p.setMaxDays(0);
        p.normalize();
        assertEquals(1, p.getMaxDays());
    }

    @Test
    void enabledWithoutApiUrlFailsFast() {
        TerraformLoginProperties p = new TerraformLoginProperties();
        p.setEnabled(true);
        p.setApiUrl("  ");
        assertThrows(IllegalStateException.class, p::normalize);
    }

    @Test
    void disabledWithoutApiUrlIsFine() {
        TerraformLoginProperties p = new TerraformLoginProperties();
        p.setEnabled(false);
        assertDoesNotThrow(p::normalize);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl api test -Dtest=TerraformLoginPropertiesTest`
Expected: FAIL — `TerraformLoginProperties` does not exist / does not compile.

- [ ] **Step 3: Write minimal implementation**

```java
package io.terrakube.api.plugin.token.login;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "io.terrakube.token.login")
public class TerraformLoginProperties {

    public static final String CLIENT_ID = "terraform-cli";
    public static final int PORT_LOW = 10000;
    public static final int PORT_HIGH = 10010;

    private boolean enabled = false;
    private int defaultDays = 30;
    private int maxDays = 90;
    private String apiUrl;
    private int cleanupIntervalMs = 300000;

    @PostConstruct
    public void normalize() {
        if (maxDays > 365) maxDays = 365;
        if (maxDays < 1) maxDays = 1;
        if (defaultDays < 1) defaultDays = 1;
        if (defaultDays > maxDays) defaultDays = maxDays;
        if (enabled && (apiUrl == null || apiUrl.isBlank())) {
            throw new IllegalStateException(
                "io.terrakube.token.login.enabled=true requires io.terrakube.token.login.api-url");
        }
        if (apiUrl != null && apiUrl.endsWith("/")) {
            apiUrl = apiUrl.substring(0, apiUrl.length() - 1);
        }
    }

    public String getCallbackUrl() {
        return apiUrl + "/oauth/callback";
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl api test -Dtest=TerraformLoginPropertiesTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Add config keys**

In `api/src/main/resources/application.properties`, after the `io.terrakube.token.client-id` line:

```properties
io.terrakube.token.login.enabled=${TerraformLoginEnabled:false}
io.terrakube.token.login.default-days=${TerraformLoginDefaultDays:30}
io.terrakube.token.login.max-days=${TerraformLoginMaxDays:90}
io.terrakube.token.login.api-url=${TerraformLoginApiUrl:}
```

In `api/src/test/resources/application-test.properties`, near the other `io.terrakube.token.*` keys:

```properties
io.terrakube.token.login.enabled=false
io.terrakube.token.login.api-url=http://localhost:8080
```

In `docker-compose/docker-compose.yml`, in the shared api environment anchor (same block that sets `PatSecret`), add:

```yaml
  TerraformLoginEnabled: ${TERRAFORM_LOGIN_ENABLED:-false}
  TerraformLoginApiUrl: https://terrakube-api.${DOMAIN}
  TerraformLoginMaxDays: ${TERRAFORM_LOGIN_MAX_DAYS:-90}
  TerraformLoginDefaultDays: ${TERRAFORM_LOGIN_DEFAULT_DAYS:-30}
```

In `docker-compose/config-ldap.yaml`, add a comment line inside `staticClients[0].redirectURIs`:

```yaml
      # When io.terrakube.token.login.enabled=true the CLI never redirects here;
      # only the API callback below is used:
      # - "https://terrakube-api.platform.local/oauth/callback"
```

- [ ] **Step 6: Verify the app context still loads**

Run: `./mvnw -pl api test -Dtest=IndexTests`
Expected: PASS (context loads with the new properties bean).

- [ ] **Step 7: Commit**

```bash
git add api/src/main/java/io/terrakube/api/plugin/token/login/TerraformLoginProperties.java \
        api/src/test/java/io/terrakube/api/plugin/token/login/TerraformLoginPropertiesTest.java \
        api/src/main/resources/application.properties \
        api/src/test/resources/application-test.properties \
        docker-compose/docker-compose.yml docker-compose/config-ldap.yaml
git commit -m "feat: add TerraformLoginProperties and config wiring for the login broker"
```

---

### Task 2: Conditional `login.v1` in `.well-known/terraform.json` (API + Registry)

**Files:**
- Modify: `api/src/main/java/io/terrakube/api/plugin/state/WellKnownWebServiceImpl.java`
- Create: `api/src/test/java/io/terrakube/api/WellKnownLoginBrokerTests.java`
- Modify: `registry/src/main/java/io/terrakube/registry/controller/WellKnownWebServiceImpl.java`
- Modify: `registry/src/main/java/io/terrakube/registry/configuration/OpenRegistryProperties.java`
- Modify: `registry/src/main/resources/application.properties`
- Create/Modify: `registry/src/test/java/io/terrakube/registry/WellKnownTests.java` (add cases; file exists)

**Interfaces:**
- Consumes: `TerraformLoginProperties` (Task 1).
- Produces: `.well-known/terraform.json` whose `login.v1` block matches the spec table depending on `io.terrakube.token.login.enabled`. `ports` is `[10000, 10010]` in both states.

- [ ] **Step 1: Write the failing test (API)**

```java
package io.terrakube.api;

import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class WellKnownLoginBrokerTests extends ServerApplicationTests {

    @Test
    void brokerDisabled_pointsAtDex() {
        given().when().get("/.well-known/terraform.json")
            .then().statusCode(200)
            .body("'login.v1'.token", containsString("/dev"))       // io.terrakube.token.issuer-uri in test props
            .body("'login.v1'.ports", hasItems(10000, 10010));
    }
}
```

Add a second nested test class for the enabled case (separate context, different properties):

```java
@org.springframework.boot.test.context.SpringBootTest(
    webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.ActiveProfiles("test")
@TestPropertySource(properties = {
    "io.terrakube.token.login.enabled=true",
    "io.terrakube.token.login.api-url=https://api.example.test"
})
class WellKnownLoginBrokerEnabledTests extends ServerApplicationTests {

    @Test
    void brokerEnabled_pointsAtTerrakube() {
        given().when().get("/.well-known/terraform.json")
            .then().statusCode(200)
            .body("'login.v1'.client", equalTo("terraform-cli"))
            .body("'login.v1'.authz", equalTo("https://api.example.test/oauth/authorize"))
            .body("'login.v1'.token", equalTo("https://api.example.test/oauth/token"))
            .body("'login.v1'.grant_types", hasItem("authz_code"))
            .body("'login.v1'.ports", hasItems(10000, 10010));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -pl api test -Dtest='WellKnownLoginBrokerTests,WellKnownLoginBrokerEnabledTests'`
Expected: FAIL — enabled case still renders the Dex URL; `ports` still `[10000, 10001]`.

- [ ] **Step 3: Implement (API)**

Replace the body of `WellKnownWebServiceImpl` so it builds the JSON from `TerraformLoginProperties`:

```java
package io.terrakube.api.plugin.state;

import io.terrakube.api.plugin.token.login.TerraformLoginProperties;
import lombok.AllArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/.well-known/terraform.json")
@AllArgsConstructor
public class WellKnownWebServiceImpl {

    @Value("${io.terrakube.token.client-id}")
    String dexClientId;

    @Value("${io.terrakube.token.issuer-uri}")
    String dexIssuerUri;

    private final TerraformLoginProperties loginProperties;

    @GetMapping(produces = "application/json")
    public ResponseEntity<String> terraformJson() {
        String loginBlock = loginProperties.isEnabled()
            ? String.format("""
                {
                    "client": "%s",
                    "grant_types": ["authz_code"],
                    "authz": "%s/oauth/authorize",
                    "token": "%s/oauth/token",
                    "ports": [10000, 10010]
                }""", TerraformLoginProperties.CLIENT_ID, loginProperties.getApiUrl(), loginProperties.getApiUrl())
            : String.format("""
                {
                    "client": "%s",
                    "grant_types": ["authz_code", "openid", "profile", "email", "offline_access", "groups"],
                    "authz": "%s/auth?scope=openid+profile+email+offline_access+groups",
                    "token": "%s/token",
                    "ports": [10000, 10010]
                }""", dexClientId, dexIssuerUri, dexIssuerUri);

        String body = String.format("""
            {
              "login.v1": %s,
              "state.v2": "/remote/state/v2/",
              "tfe.v2": "/remote/tfe/v2/",
              "tfe.v2.1": "/remote/tfe/v2/"
            }""", loginBlock);

        return ResponseEntity.ok(body);
    }
}
```

(Note: the previous string had malformed comma placement — `"\n,"` — this rewrite fixes it while keeping the same keys.)

- [ ] **Step 4: Implement (Registry)**

In `OpenRegistryProperties` add:

```java
    private boolean loginBrokerEnabled = false;
    private String loginApiUrl;
```

In `registry/src/main/resources/application.properties` add:

```properties
io.terrakube.registry.login-broker-enabled=${TerraformLoginEnabled:false}
io.terrakube.registry.login-api-url=${TerraformLoginApiUrl:}
```

In the registry `WellKnownWebServiceImpl`, branch the `login.v1` block the same way: when `openRegistryProperties.isLoginBrokerEnabled()`, emit
`"client":"terraform-cli"`, `"grant_types":["authz_code"]`,
`"authz":"<loginApiUrl>/oauth/authorize"`, `"token":"<loginApiUrl>/oauth/token"`,
`"ports":[10000,10010]`; otherwise keep today's Dex block but with `"ports":[10000,10010]`. Keep the existing `modules.v1` / `providers.v1` keys unchanged.

- [ ] **Step 5: Add the registry test case**

In `registry/src/test/java/io/terrakube/registry/WellKnownTests.java`, add a test asserting the disabled output still contains the Dex `authz`, and (with `@TestPropertySource` `io.terrakube.registry.login-broker-enabled=true`, `io.terrakube.registry.login-api-url=https://api.example.test`) a test asserting `authz` = `https://api.example.test/oauth/authorize`.

- [ ] **Step 6: Run all affected tests**

Run: `./mvnw -pl api test -Dtest='WellKnownLoginBrokerTests,WellKnownLoginBrokerEnabledTests'`
Run: `./mvnw -pl registry test -Dtest=WellKnownTests`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add api/src/main/java/io/terrakube/api/plugin/state/WellKnownWebServiceImpl.java \
        api/src/test/java/io/terrakube/api/WellKnownLoginBrokerTests.java \
        registry/src/main/java/io/terrakube/registry/controller/WellKnownWebServiceImpl.java \
        registry/src/main/java/io/terrakube/registry/configuration/OpenRegistryProperties.java \
        registry/src/main/resources/application.properties \
        registry/src/test/java/io/terrakube/registry/WellKnownTests.java
git commit -m "feat: render terraform login.v1 discovery through the broker when enabled"
```

---

### Task 3: Broker primitives — constants, PKCE, redirect-URI validation

**Files:**
- Create: `api/src/main/java/io/terrakube/api/plugin/token/login/PkceUtil.java`
- Create: `api/src/main/java/io/terrakube/api/plugin/token/login/LoopbackRedirectUriValidator.java`
- Create: `api/src/main/java/io/terrakube/api/plugin/token/login/BrokerBadRequestException.java`
- Create: `api/src/test/java/io/terrakube/api/plugin/token/login/PkceUtilTest.java`
- Create: `api/src/test/java/io/terrakube/api/plugin/token/login/LoopbackRedirectUriValidatorTest.java`

**Interfaces:**
- Produces:
  - `PkceUtil.generateCodeVerifier()` → `String` (43–128 char base64url, ~256 bits entropy)
  - `PkceUtil.codeChallengeS256(String verifier)` → `String` (base64url, no padding, of `SHA-256(verifier ASCII bytes)`)
  - `PkceUtil.verifyS256(String verifier, String expectedChallenge)` → `boolean` (constant-time compare)
  - `LoopbackRedirectUriValidator.validate(String redirectUri)` → `void`, throws `BrokerBadRequestException` on any failure
  - `BrokerBadRequestException extends RuntimeException` (message is user-safe)

- [ ] **Step 1: Write the failing tests**

```java
// PkceUtilTest.java
package io.terrakube.api.plugin.token.login;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PkceUtilTest {

    @Test
    void knownVectorFromRfc7636() {
        // RFC 7636 Appendix B
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            PkceUtil.codeChallengeS256(verifier));
    }

    @Test
    void verifyRoundTrip() {
        String v = PkceUtil.generateCodeVerifier();
        assertTrue(v.length() >= 43 && v.length() <= 128);
        assertTrue(PkceUtil.verifyS256(v, PkceUtil.codeChallengeS256(v)));
        assertFalse(PkceUtil.verifyS256(v, PkceUtil.codeChallengeS256(v + "x")));
    }
}
```

```java
// LoopbackRedirectUriValidatorTest.java
package io.terrakube.api.plugin.token.login;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoopbackRedirectUriValidatorTest {

    private final LoopbackRedirectUriValidator v = new LoopbackRedirectUriValidator();

    @Test
    void acceptsLoopbackInRange() {
        v.validate("http://localhost:10000/login");
        v.validate("http://127.0.0.1:10010/login");
        v.validate("http://[::1]:10005/login");
    }

    @Test
    void rejectsNonLoopbackHost() {
        assertThrows(BrokerBadRequestException.class,
            () -> v.validate("http://evil.example.com:10000/login"));
    }

    @Test
    void rejectsPortOutsideRange() {
        assertThrows(BrokerBadRequestException.class,
            () -> v.validate("http://localhost:9999/login"));
        assertThrows(BrokerBadRequestException.class,
            () -> v.validate("http://localhost:10011/login"));
    }

    @Test
    void rejectsWrongPathSchemeOrExtras() {
        assertThrows(BrokerBadRequestException.class, () -> v.validate("http://localhost:10000/callback"));
        assertThrows(BrokerBadRequestException.class, () -> v.validate("https://localhost:10000/login"));
        assertThrows(BrokerBadRequestException.class, () -> v.validate("http://localhost:10000/login?x=1"));
        assertThrows(BrokerBadRequestException.class, () -> v.validate("http://localhost:10000/login#frag"));
        assertThrows(BrokerBadRequestException.class, () -> v.validate("http://localhost/login"));
        assertThrows(BrokerBadRequestException.class, () -> v.validate("not a uri"));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -pl api test -Dtest='PkceUtilTest,LoopbackRedirectUriValidatorTest'`
Expected: FAIL — classes missing.

- [ ] **Step 3: Implement**

```java
// BrokerBadRequestException.java
package io.terrakube.api.plugin.token.login;

public class BrokerBadRequestException extends RuntimeException {
    public BrokerBadRequestException(String message) { super(message); }
}
```

```java
// PkceUtil.java
package io.terrakube.api.plugin.token.login;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class PkceUtil {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private PkceUtil() {}

    public static String generateCodeVerifier() {
        byte[] bytes = new byte[48]; // 64 base64url chars
        RANDOM.nextBytes(bytes);
        return B64.encodeToString(bytes);
    }

    public static String codeChallengeS256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return B64.encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean verifyS256(String verifier, String expectedChallenge) {
        if (verifier == null || expectedChallenge == null) return false;
        return MessageDigest.isEqual(
            codeChallengeS256(verifier).getBytes(StandardCharsets.US_ASCII),
            expectedChallenge.getBytes(StandardCharsets.US_ASCII));
    }
}
```

```java
// LoopbackRedirectUriValidator.java
package io.terrakube.api.plugin.token.login;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Set;

@Component
public class LoopbackRedirectUriValidator {

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    public void validate(String redirectUri) {
        URI uri;
        try {
            uri = new URI(redirectUri);
        } catch (Exception e) {
            throw new BrokerBadRequestException("redirect_uri is not a valid URI");
        }
        if (!"http".equals(uri.getScheme())) {
            throw new BrokerBadRequestException("redirect_uri must use http on a loopback address");
        }
        String host = uri.getHost();
        if (host == null) throw new BrokerBadRequestException("redirect_uri has no host");
        if (!LOOPBACK_HOSTS.contains(host)) {
            throw new BrokerBadRequestException("redirect_uri must target a loopback address");
        }
        int port = uri.getPort();
        if (port < TerraformLoginProperties.PORT_LOW || port > TerraformLoginProperties.PORT_HIGH) {
            throw new BrokerBadRequestException("redirect_uri port is outside the allowed range");
        }
        if (!"/login".equals(uri.getPath())) {
            throw new BrokerBadRequestException("redirect_uri path must be /login");
        }
        if (uri.getQuery() != null || uri.getFragment() != null || uri.getUserInfo() != null) {
            throw new BrokerBadRequestException("redirect_uri must not contain a query, fragment, or userinfo");
        }
    }
}
```

Note on `::1`: `new URI("http://[::1]:10005/login").getHost()` returns `[::1]` on some JDKs and `::1` on others — the `LOOPBACK_HOSTS` set includes both forms.

- [ ] **Step 4: Run to verify pass**

Run: `./mvnw -pl api test -Dtest='PkceUtilTest,LoopbackRedirectUriValidatorTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add api/src/main/java/io/terrakube/api/plugin/token/login/PkceUtil.java \
        api/src/main/java/io/terrakube/api/plugin/token/login/LoopbackRedirectUriValidator.java \
        api/src/main/java/io/terrakube/api/plugin/token/login/BrokerBadRequestException.java \
        api/src/test/java/io/terrakube/api/plugin/token/login/PkceUtilTest.java \
        api/src/test/java/io/terrakube/api/plugin/token/login/LoopbackRedirectUriValidatorTest.java
git commit -m "feat: add PKCE and loopback redirect-uri validation helpers for the login broker"
```

---

### Task 4: `CliLoginCookie` — signed session cookie

**Files:**
- Create: `api/src/main/java/io/terrakube/api/plugin/token/login/CliLoginCookie.java`
- Create: `api/src/test/java/io/terrakube/api/plugin/token/login/CliLoginCookieTest.java`

**Interfaces:**
- Consumes: `${io.terrakube.token.pat}` (existing base64url HMAC secret) — injected via constructor `@Value`.
- Produces:
  - `String COOKIE_NAME = "tk_cli_login"`
  - `String sign(String sessionId)` → `"<sessionId>.<base64url(HMAC-SHA256(hkdfKey, sessionId))>"`
  - `Optional<String> verify(String cookieValue)` → the session id if the MAC matches (constant-time), else empty
  - `ResponseCookie build(String sessionId)` → `ResponseCookie` with `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/oauth`, `Max-Age=600`
  - `ResponseCookie clear()` → an expired cookie with the same attributes

- [ ] **Step 1: Write the failing test**

```java
package io.terrakube.api.plugin.token.login;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class CliLoginCookieTest {

    // any base64url string works as the HMAC seed
    private final CliLoginCookie cookie =
        new CliLoginCookie("ejZRSFgheUBOZXAyUURUITUzdmdINDNeUGpSWHlDM1g=");

    @Test
    void signAndVerifyRoundTrip() {
        String sid = "11111111-1111-1111-1111-111111111111";
        String signed = cookie.sign(sid);
        assertTrue(signed.startsWith(sid + "."));
        assertEquals(Optional.of(sid), cookie.verify(signed));
    }

    @Test
    void tamperedValueIsRejected() {
        String sid = "11111111-1111-1111-1111-111111111111";
        String signed = cookie.sign(sid);
        assertEquals(Optional.empty(), cookie.verify(signed + "x"));
        assertEquals(Optional.empty(),
            cookie.verify("22222222-2222-2222-2222-222222222222." + signed.split("\\.")[1]));
        assertEquals(Optional.empty(), cookie.verify("garbage"));
        assertEquals(Optional.empty(), cookie.verify(null));
    }

    @Test
    void builtCookieHasHardenedAttributes() {
        ResponseCookie rc = cookie.build("11111111-1111-1111-1111-111111111111");
        assertEquals("tk_cli_login", rc.getName());
        assertTrue(rc.isHttpOnly());
        assertTrue(rc.isSecure());
        assertEquals("Lax", rc.getSameSite());
        assertEquals("/oauth", rc.getPath());
        assertEquals(600, rc.getMaxAge().getSeconds());
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -pl api test -Dtest=CliLoginCookieTest`
Expected: FAIL — class missing.

- [ ] **Step 3: Implement**

```java
package io.terrakube.api.plugin.token.login;

import io.jsonwebtoken.io.Decoders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

@Component
public class CliLoginCookie {

    public static final String COOKIE_NAME = "tk_cli_login";
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final byte[] HKDF_INFO = "terrakube-cli-login-cookie-v1".getBytes(StandardCharsets.UTF_8);

    private final byte[] key;

    public CliLoginCookie(@Value("${io.terrakube.token.pat}") String patSecretBase64) {
        byte[] ikm = Decoders.BASE64URL.decode(patSecretBase64);
        this.key = hkdfSha256(ikm, HKDF_INFO, 32);
    }

    public String sign(String sessionId) {
        return sessionId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(sessionId));
    }

    public Optional<String> verify(String cookieValue) {
        if (cookieValue == null) return Optional.empty();
        int dot = cookieValue.lastIndexOf('.');
        if (dot <= 0 || dot == cookieValue.length() - 1) return Optional.empty();
        String sid = cookieValue.substring(0, dot);
        String sig = cookieValue.substring(dot + 1);
        byte[] expected = Base64.getUrlEncoder().withoutPadding().encodeToString(mac(sid))
            .getBytes(StandardCharsets.US_ASCII);
        if (java.security.MessageDigest.isEqual(expected, sig.getBytes(StandardCharsets.US_ASCII))) {
            return Optional.of(sid);
        }
        return Optional.empty();
    }

    public ResponseCookie build(String sessionId) {
        return baseBuilder(sign(sessionId)).maxAge(TTL).build();
    }

    public ResponseCookie clear() {
        return baseBuilder("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder baseBuilder(String value) {
        return ResponseCookie.from(COOKIE_NAME, value)
            .httpOnly(true).secure(true).sameSite("Lax").path("/oauth");
    }

    private byte[] mac(String data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // RFC 5869 HKDF-SHA256 (extract + expand), single-block output (len <= 32)
    private static byte[] hkdfSha256(byte[] ikm, byte[] info, int len) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(new byte[32], "HmacSHA256")); // zero salt
            byte[] prk = m.doFinal(ikm);
            m.init(new SecretKeySpec(prk, "HmacSHA256"));
            m.update(info);
            m.update((byte) 0x01);
            byte[] t = m.doFinal();
            byte[] out = new byte[len];
            System.arraycopy(t, 0, out, 0, len);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./mvnw -pl api test -Dtest=CliLoginCookieTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add api/src/main/java/io/terrakube/api/plugin/token/login/CliLoginCookie.java \
        api/src/test/java/io/terrakube/api/plugin/token/login/CliLoginCookieTest.java
git commit -m "feat: add signed tk_cli_login session cookie for the login broker consent step"
```

---

### Task 5: Persistence — `pat` columns, `CliAuthSession` entity, repository, changeset

**Files:**
- Create: `api/src/main/resources/db/changelog/local/changelog-2.34.0-cli-auth-session.xml`
- Modify: `api/src/main/resources/db/changelog/changelog.xml` (add `<include>` at the end of the local includes)
- Modify: `api/src/main/java/io/terrakube/api/rs/token/pat/Pat.java`
- Create: `api/src/main/java/io/terrakube/api/rs/token/login/CliAuthSession.java`
- Create: `api/src/main/java/io/terrakube/api/rs/token/login/CliAuthSessionStatus.java`
- Create: `api/src/main/java/io/terrakube/api/repository/CliAuthSessionRepository.java`
- Create: `api/src/test/java/io/terrakube/api/CliAuthSessionRepositoryTests.java`

**Interfaces:**
- Produces:
  - `CliAuthSessionStatus` enum: `PENDING_IDP, PENDING_CONSENT, CODE_ISSUED, EXCHANGED, DENIED, FAILED`
  - `CliAuthSession` entity (table `cli_auth_session`) with getters/setters for: `UUID id`, `CliAuthSessionStatus status`, `String cliRedirectUri`, `String cliCodeChallenge`, `String cliState`, `String dexCodeVerifier`, `String identityEmail`, `String identityName`, `String identityGroups` (JSON text), `Integer chosenDays`, `String authCodeHash`, `Date codeExpiresAt`, `Date expiresAt`, plus `GenericAuditFields`
  - `CliAuthSessionRepository extends JpaRepository<CliAuthSession, UUID>` with:
    - `Optional<CliAuthSession> findByAuthCodeHash(String authCodeHash)`
    - `long deleteByExpiresAtBefore(Date cutoff)`
  - `Pat` gains `String source` (default `"API"`) and `Date lastUsedAt`

- [ ] **Step 1: Write the failing test**

```java
package io.terrakube.api;

import io.terrakube.api.repository.CliAuthSessionRepository;
import io.terrakube.api.rs.token.login.CliAuthSession;
import io.terrakube.api.rs.token.login.CliAuthSessionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CliAuthSessionRepositoryTests extends ServerApplicationTests {

    @Autowired
    CliAuthSessionRepository repository;

    @Test
    void persistsAndFindsByAuthCodeHash() {
        CliAuthSession s = new CliAuthSession();
        s.setStatus(CliAuthSessionStatus.CODE_ISSUED);
        s.setCliRedirectUri("http://localhost:10000/login");
        s.setCliCodeChallenge("abc");
        s.setCliState("cli-state");
        s.setAuthCodeHash("hash-1");
        s.setChosenDays(30);
        s.setCodeExpiresAt(new Date(System.currentTimeMillis() + 60000));
        s.setExpiresAt(new Date(System.currentTimeMillis() + 600000));
        repository.save(s);

        assertTrue(repository.findByAuthCodeHash("hash-1").isPresent());
        assertEquals(CliAuthSessionStatus.CODE_ISSUED,
            repository.findByAuthCodeHash("hash-1").get().getStatus());
    }

    @Test
    void deletesExpiredRows() {
        CliAuthSession s = new CliAuthSession();
        s.setStatus(CliAuthSessionStatus.PENDING_IDP);
        s.setCliRedirectUri("http://localhost:10000/login");
        s.setCliCodeChallenge("abc");
        s.setCliState(UUID.randomUUID().toString());
        s.setExpiresAt(new Date(System.currentTimeMillis() - 1000));
        repository.save(s);

        long deleted = repository.deleteByExpiresAtBefore(new Date());
        assertTrue(deleted >= 1);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -pl api test -Dtest=CliAuthSessionRepositoryTests`
Expected: FAIL — entity/repository missing.

- [ ] **Step 3: Write the Liquibase changeset**

`changelog-2.34.0-cli-auth-session.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
            http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.3.xsd">

    <changeSet id="2-34-0-cli-auth-session" author="jake">
        <createTable tableName="cli_auth_session">
            <column name="id" type="varchar(36)">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="status" type="varchar(20)">
                <constraints nullable="false"/>
            </column>
            <column name="cli_redirect_uri" type="varchar(255)">
                <constraints nullable="false"/>
            </column>
            <column name="cli_code_challenge" type="varchar(128)">
                <constraints nullable="false"/>
            </column>
            <column name="cli_state" type="varchar(255)">
                <constraints nullable="false"/>
            </column>
            <column name="dex_code_verifier" type="varchar(128)"/>
            <column name="identity_email" type="varchar(255)"/>
            <column name="identity_name" type="varchar(255)"/>
            <column name="identity_groups" type="text"/>
            <column name="chosen_days" type="int"/>
            <column name="auth_code_hash" type="varchar(64)"/>
            <column name="code_expires_at" type="datetime"/>
            <column name="expires_at" type="datetime">
                <constraints nullable="false"/>
            </column>
            <column name="created_date" type="datetime"/>
            <column name="updated_date" type="datetime"/>
            <column name="created_by" type="varchar(128)"/>
            <column name="updated_by" type="varchar(128)"/>
        </createTable>
        <createIndex tableName="cli_auth_session" indexName="idx_cli_auth_session_auth_code_hash">
            <column name="auth_code_hash"/>
        </createIndex>
        <createIndex tableName="cli_auth_session" indexName="idx_cli_auth_session_expires_at">
            <column name="expires_at"/>
        </createIndex>
    </changeSet>

    <changeSet id="2-34-0-pat-source" author="jake">
        <addColumn tableName="pat">
            <column name="source" type="varchar(20)" defaultValue="API">
                <constraints nullable="false"/>
            </column>
            <column name="last_used_at" type="datetime"/>
        </addColumn>
    </changeSet>

</databaseChangeLog>
```

Add to `changelog.xml`, after the last existing local `<include>`:

```xml
    <include file="/db/changelog/local/changelog-2.34.0-cli-auth-session.xml"/>
```

- [ ] **Step 4: Write the entities**

```java
// CliAuthSessionStatus.java
package io.terrakube.api.rs.token.login;

public enum CliAuthSessionStatus {
    PENDING_IDP, PENDING_CONSENT, CODE_ISSUED, EXCHANGED, DENIED, FAILED
}
```

```java
// CliAuthSession.java
package io.terrakube.api.rs.token.login;

import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.util.Date;
import java.util.UUID;

@NoArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "cli_auth_session")
public class CliAuthSession extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CliAuthSessionStatus status;

    @Column(name = "cli_redirect_uri", nullable = false)
    private String cliRedirectUri;

    @Column(name = "cli_code_challenge", nullable = false)
    private String cliCodeChallenge;

    @Column(name = "cli_state", nullable = false)
    private String cliState;

    @Column(name = "dex_code_verifier")
    private String dexCodeVerifier;

    @Column(name = "identity_email")
    private String identityEmail;

    @Column(name = "identity_name")
    private String identityName;

    @Column(name = "identity_groups", columnDefinition = "text")
    private String identityGroups;

    @Column(name = "chosen_days")
    private Integer chosenDays;

    @Column(name = "auth_code_hash")
    private String authCodeHash;

    @Column(name = "code_expires_at")
    private Date codeExpiresAt;

    @Column(name = "expires_at", nullable = false)
    private Date expiresAt;
}
```

Add to `Pat.java` (below `description`):

```java
    @Column(nullable = false)
    private String source = "API";

    @Column(name = "last_used_at")
    private java.util.Date lastUsedAt;
```

```java
// CliAuthSessionRepository.java
package io.terrakube.api.repository;

import io.terrakube.api.rs.token.login.CliAuthSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Date;
import java.util.Optional;
import java.util.UUID;

public interface CliAuthSessionRepository extends JpaRepository<CliAuthSession, UUID> {
    Optional<CliAuthSession> findByAuthCodeHash(String authCodeHash);
    long deleteByExpiresAtBefore(Date cutoff);
}
```

- [ ] **Step 5: Run to verify pass**

Run: `./mvnw -pl api test -Dtest=CliAuthSessionRepositoryTests`
Expected: PASS. `deleteByExpiresAtBefore` needs `@Transactional` on the test methods or a `@Modifying` derived-delete — Spring Data supports derived `deleteBy…` returning `long`; the test class extends `ServerApplicationTests` which runs in a transaction per test. If the delete fails for lack of a transaction, annotate the test method with `@org.springframework.transaction.annotation.Transactional`.

- [ ] **Step 6: Run the wider token suite to check nothing regressed**

Run: `./mvnw -pl api test -Dtest='TokenTests,AccessTests'`
Expected: PASS (the new non-null `source` column has a default, existing inserts unaffected).

- [ ] **Step 7: Commit**

```bash
git add api/src/main/resources/db/changelog/local/changelog-2.34.0-cli-auth-session.xml \
        api/src/main/resources/db/changelog/changelog.xml \
        api/src/main/java/io/terrakube/api/rs/token/pat/Pat.java \
        api/src/main/java/io/terrakube/api/rs/token/login/ \
        api/src/main/java/io/terrakube/api/repository/CliAuthSessionRepository.java \
        api/src/test/java/io/terrakube/api/CliAuthSessionRepositoryTests.java
git commit -m "feat: add cli_auth_session table and pat source/last_used_at columns"
```

---

### Task 6: `PatService` — `source` overload and `touchLastUsed`

**Files:**
- Modify: `api/src/main/java/io/terrakube/api/plugin/token/pat/PatService.java`
- Modify: `api/src/test/java/io/terrakube/api/TokenTests.java` (add one assertion) or create `PatServiceSourceTests.java`

**Interfaces:**
- Consumes: `PatRepository`, `CliAuthSessionRepository` not needed here.
- Produces:
  - New overload `String createToken(int days, String description, Object name, Object email, Object groups, String source)` — same behaviour as the existing 5-arg method but stores `pat.setSource(source)`.
  - Existing `createToken(int, String, Object, Object, Object)` now delegates to the 6-arg version with `source = "API"` (keeps all current callers working).
  - `void touchLastUsed(UUID patId)` — sets `last_used_at = now` for that row if it exists; used by Task 13.

- [ ] **Step 1: Write the failing test**

```java
// PatServiceSourceTests.java
package io.terrakube.api;

import io.terrakube.api.plugin.token.pat.PatService;
import io.terrakube.api.repository.PatRepository;
import io.terrakube.api.rs.token.pat.Pat;
import net.minidev.json.JSONArray;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PatServiceSourceTests extends ServerApplicationTests {

    @Autowired PatService patService;
    @Autowired PatRepository patRepository;

    @Test
    void sixArgOverloadStoresSource() {
        JSONArray groups = new JSONArray();
        groups.appendElement("TERRAKUBE_DEVELOPERS");
        String jws = patService.createToken(7, "cli", "N", "e@e.io", groups, "CLI_LOGIN");
        String jti = new String(Base64.getUrlDecoder().decode(jws.split("\\.")[1]))
            .replaceAll(".*\"jti\":\"([^\"]+)\".*", "$1");
        Pat pat = patRepository.findById(java.util.UUID.fromString(jti)).orElseThrow();
        assertEquals("CLI_LOGIN", pat.getSource());
    }

    @Test
    void legacyOverloadDefaultsToApiSource() {
        JSONArray groups = new JSONArray();
        String jws = patService.createToken(7, "legacy", "N", "e@e.io", groups);
        String jti = new String(Base64.getUrlDecoder().decode(jws.split("\\.")[1]))
            .replaceAll(".*\"jti\":\"([^\"]+)\".*", "$1");
        assertEquals("API", patRepository.findById(java.util.UUID.fromString(jti)).orElseThrow().getSource());
    }

    @Test
    void touchLastUsedSetsTimestamp() {
        JSONArray groups = new JSONArray();
        String jws = patService.createToken(7, "t", "N", "e@e.io", groups, "CLI_LOGIN");
        java.util.UUID id = java.util.UUID.fromString(
            new String(Base64.getUrlDecoder().decode(jws.split("\\.")[1]))
                .replaceAll(".*\"jti\":\"([^\"]+)\".*", "$1"));
        assertNull(patRepository.findById(id).orElseThrow().getLastUsedAt());
        patService.touchLastUsed(id);
        assertNotNull(patRepository.findById(id).orElseThrow().getLastUsedAt());
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -pl api test -Dtest=PatServiceSourceTests`
Expected: FAIL — 6-arg overload and `touchLastUsed` missing.

- [ ] **Step 3: Implement**

In `PatService`, refactor: extract the existing body of `createToken(int, String, Object, Object, Object)` into a private `buildJws(Pat pat, int days, Object name, Object email, Object groups)` and add:

```java
public String createToken(int days, String description, Object name, Object email, Object groups) {
    return createToken(days, description, name, email, groups, "API");
}

public String createToken(int days, String description, Object name, Object email, Object groups, String source) {
    Pat pat = new Pat();
    pat.setDays(days);
    pat.setDeleted(false);
    pat.setDescription(description);
    pat.setSource(source);
    pat = patRepository.save(pat);
    try {
        String jws = buildJws(pat, days, name, email, groups);
        log.info("Generated Pat {} (source {})", pat.getId(), source);
        return jws;
    } catch (Exception e) {
        log.error("Error generating token", e);
        patRepository.delete(pat);
        return "";
    }
}

public void touchLastUsed(java.util.UUID patId) {
    patRepository.findById(patId).ifPresent(pat -> {
        pat.setLastUsedAt(new java.util.Date());
        patRepository.save(pat);
    });
}
```

`buildJws` contains the existing `if (days > 0) { … } else { … }` jjwt builder logic verbatim, returning the compact string.

- [ ] **Step 4: Run to verify pass**

Run: `./mvnw -pl api test -Dtest='PatServiceSourceTests,TokenTests'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add api/src/main/java/io/terrakube/api/plugin/token/pat/PatService.java \
        api/src/test/java/io/terrakube/api/PatServiceSourceTests.java
git commit -m "feat: PatService source overload and touchLastUsed"
```

---

### Task 7: `DexExchangeClient` — upstream calls to Dex

**Files:**
- Create: `api/src/main/java/io/terrakube/api/plugin/token/login/DexExchangeClient.java`
- Create: `api/src/main/java/io/terrakube/api/plugin/token/login/DexIdentity.java`
- Create: `api/src/main/java/io/terrakube/api/plugin/token/login/BrokerUpstreamException.java`
- Create: `api/src/test/java/io/terrakube/api/plugin/token/login/DexExchangeClientTest.java`

**Interfaces:**
- Consumes: `TerraformLoginProperties`, `${io.terrakube.token.issuer-uri}`, `${io.terrakube.token.client-id}`.
- Produces:
  - `record DexIdentity(String email, String name, java.util.List<String> groups)`
  - `String buildAuthorizeRedirect(String state, String codeChallenge)` → the full Dex `/auth` URL (`<issuer>/auth?response_type=code&client_id=…&redirect_uri=<callbackUrl>&scope=openid%20profile%20email%20groups&code_challenge=…&code_challenge_method=S256&state=…`)
  - `DexIdentity exchange(String code, String codeVerifier)` → POST `<issuer>/token`, parse `id_token`, decode its payload (no signature verification needed here — the code came straight from Dex over the back-channel TLS call; still assert `iss` == issuer and `exp` in the future), return identity. Throws `BrokerUpstreamException` on non-2xx or missing `id_token`.
  - `String issuerUri()` accessor (used for the `iss` mix-up check in `CliLoginService`)
  - `BrokerUpstreamException extends RuntimeException`

- [ ] **Step 1: Write the failing test**

```java
package io.terrakube.api.plugin.token.login;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.*;

class DexExchangeClientTest {

    WireMockServer wm;
    DexExchangeClient client;
    String issuer;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        issuer = "http://localhost:" + wm.port() + "/dex";
        TerraformLoginProperties props = new TerraformLoginProperties();
        props.setEnabled(true);
        props.setApiUrl("http://api.local");
        props.normalize();
        client = new DexExchangeClient(props, issuer, "terrakube-app");
    }

    @AfterEach
    void tearDown() { wm.stop(); }

    @Test
    void buildAuthorizeRedirectContainsAllParams() {
        String url = client.buildAuthorizeRedirect("state-123", "challenge-abc");
        assertTrue(url.startsWith(issuer + "/auth?"));
        assertTrue(url.contains("response_type=code"));
        assertTrue(url.contains("client_id=terrakube-app"));
        assertTrue(url.contains("redirect_uri=http%3A%2F%2Fapi.local%2Foauth%2Fcallback"));
        assertTrue(url.contains("code_challenge=challenge-abc"));
        assertTrue(url.contains("code_challenge_method=S256"));
        assertTrue(url.contains("state=state-123"));
    }

    @Test
    void exchangeParsesIdentityFromIdToken() {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64URL.decode(
            "ejZRSFgheUBOZXAyUURUITUzdmdINDNeUGpSWHlDM1g="));
        String idToken = Jwts.builder().issuer(issuer).subject("u")
            .claim("email", "alice@example.io").claim("name", "Alice")
            .claim("groups", List.of("DEV", "OPS"))
            .issuedAt(new Date())
            .expiration(Date.from(Instant.now().plus(5, ChronoUnit.MINUTES)))
            .signWith(key).compact();
        wm.stubFor(post(urlEqualTo("/dex/token")).willReturn(okJson(
            "{\"access_token\":\"x\",\"id_token\":\"" + idToken + "\",\"token_type\":\"bearer\"}")));

        DexIdentity id = client.exchange("the-code", "the-verifier");
        assertEquals("alice@example.io", id.email());
        assertEquals("Alice", id.name());
        assertEquals(List.of("DEV", "OPS"), id.groups());
    }

    @Test
    void exchangeThrowsOnUpstreamError() {
        wm.stubFor(post(urlEqualTo("/dex/token")).willReturn(aResponse().withStatus(401)));
        assertThrows(BrokerUpstreamException.class, () -> client.exchange("bad", "v"));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -pl api test -Dtest=DexExchangeClientTest`
Expected: FAIL — classes missing.

- [ ] **Step 3: Implement**

```java
// DexIdentity.java
package io.terrakube.api.plugin.token.login;

import java.util.List;

public record DexIdentity(String email, String name, List<String> groups) {}
```

```java
// BrokerUpstreamException.java
package io.terrakube.api.plugin.token.login;

public class BrokerUpstreamException extends RuntimeException {
    public BrokerUpstreamException(String message) { super(message); }
    public BrokerUpstreamException(String message, Throwable cause) { super(message, cause); }
}
```

```java
// DexExchangeClient.java
package io.terrakube.api.plugin.token.login;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Component
public class DexExchangeClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TerraformLoginProperties loginProperties;
    private final String issuerUri;
    private final String dexClientId;
    private final WebClient webClient = WebClient.builder().build();

    public DexExchangeClient(TerraformLoginProperties loginProperties,
                             @Value("${io.terrakube.token.issuer-uri}") String issuerUri,
                             @Value("${io.terrakube.token.client-id}") String dexClientId) {
        this.loginProperties = loginProperties;
        this.issuerUri = issuerUri.endsWith("/") ? issuerUri.substring(0, issuerUri.length() - 1) : issuerUri;
        this.dexClientId = dexClientId;
    }

    public String issuerUri() {
        return issuerUri;
    }

    public String buildAuthorizeRedirect(String state, String codeChallenge) {
        String q = "response_type=code"
            + "&client_id=" + enc(dexClientId)
            + "&redirect_uri=" + enc(loginProperties.getCallbackUrl())
            + "&scope=" + enc("openid profile email groups")
            + "&code_challenge=" + enc(codeChallenge)
            + "&code_challenge_method=S256"
            + "&state=" + enc(state);
        return issuerUri + "/auth?" + q;
    }

    public DexIdentity exchange(String code, String codeVerifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", loginProperties.getCallbackUrl());
        form.add("client_id", dexClientId);
        form.add("code_verifier", codeVerifier);

        String body;
        try {
            body = webClient.post().uri(issuerUri + "/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(form)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        } catch (Exception e) {
            throw new BrokerUpstreamException("Dex token exchange failed", e);
        }

        try {
            JsonNode json = MAPPER.readTree(body);
            String idToken = json.path("id_token").asText(null);
            if (idToken == null) throw new BrokerUpstreamException("Dex response missing id_token");
            JsonNode claims = MAPPER.readTree(
                new String(Base64.getUrlDecoder().decode(idToken.split("\\.")[1]), StandardCharsets.UTF_8));

            if (!issuerUri.equals(claims.path("iss").asText())) {
                throw new BrokerUpstreamException("id_token issuer mismatch");
            }
            if (claims.path("exp").asLong(0) * 1000L < System.currentTimeMillis()) {
                throw new BrokerUpstreamException("id_token already expired");
            }

            List<String> groups = new ArrayList<>();
            claims.path("groups").forEach(n -> groups.add(n.asText()));
            return new DexIdentity(
                claims.path("email").asText(null),
                claims.path("name").asText(null),
                groups);
        } catch (BrokerUpstreamException e) {
            throw e;
        } catch (Exception e) {
            throw new BrokerUpstreamException("Could not parse Dex id_token", e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./mvnw -pl api test -Dtest=DexExchangeClientTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add api/src/main/java/io/terrakube/api/plugin/token/login/DexExchangeClient.java \
        api/src/main/java/io/terrakube/api/plugin/token/login/DexIdentity.java \
        api/src/main/java/io/terrakube/api/plugin/token/login/BrokerUpstreamException.java \
        api/src/test/java/io/terrakube/api/plugin/token/login/DexExchangeClientTest.java
git commit -m "feat: add DexExchangeClient for the login broker upstream flow"
```

---

### Task 8: `ConsentPageRenderer` — server-rendered HTML

**Files:**
- Create: `api/src/main/java/io/terrakube/api/plugin/token/login/ConsentPageRenderer.java`
- Create: `api/src/test/java/io/terrakube/api/plugin/token/login/ConsentPageRendererTest.java`

**Interfaces:**
- Produces:
  - `String renderConsent(String email, int defaultDays, int maxDays, String errorMessage)` — a full self-contained HTML document with a `<form method="post" action="/oauth/consent">` containing a `<select name="days">` (options from `{1,7,14,30,60,90}` filtered to `<= maxDays`, `defaultDays` marked `selected`) and two submit buttons `name="decision"` value `authorize` / `deny`. `errorMessage` (nullable) renders in a `<p class="error">`. HTML-escape `email` and `errorMessage`.
  - `String renderError(String message)` — a full HTML document showing `message` and "You can close this window."
  - `String renderSuccess()` — "Authorized. Return to your terminal — you can close this window."

- [ ] **Step 1: Write the failing test**

```java
package io.terrakube.api.plugin.token.login;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConsentPageRendererTest {

    private final ConsentPageRenderer r = new ConsentPageRenderer();

    @Test
    void consentPageHasFormAndFilteredOptions() {
        String html = r.renderConsent("alice@example.io", 30, 60, null);
        assertTrue(html.contains("<form method=\"post\" action=\"/oauth/consent\""));
        assertTrue(html.contains("alice@example.io"));
        assertTrue(html.contains("value=\"30\" selected"));
        assertTrue(html.contains("value=\"60\""));
        assertFalse(html.contains("value=\"90\""));   // filtered out by maxDays=60
        assertTrue(html.contains("name=\"decision\" value=\"authorize\""));
        assertTrue(html.contains("name=\"decision\" value=\"deny\""));
    }

    @Test
    void escapesEmailAndError() {
        String html = r.renderConsent("<script>x</script>@e.io", 30, 90, "bad & wrong");
        assertFalse(html.contains("<script>x</script>"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("bad &amp; wrong"));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -pl api test -Dtest=ConsentPageRendererTest`
Expected: FAIL — class missing.

- [ ] **Step 3: Implement**

```java
package io.terrakube.api.plugin.token.login;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ConsentPageRenderer {

    private static final List<Integer> CHOICES = List.of(1, 7, 14, 30, 60, 90);

    public String renderConsent(String email, int defaultDays, int maxDays, String errorMessage) {
        StringBuilder options = new StringBuilder();
        for (int d : CHOICES) {
            if (d > maxDays) continue;
            options.append("<option value=\"").append(d).append("\"")
                   .append(d == defaultDays ? " selected" : "")
                   .append(">").append(d).append(d == 1 ? " day" : " days").append("</option>");
        }
        String error = errorMessage == null ? ""
            : "<p class=\"error\">" + escape(errorMessage) + "</p>";
        return page("Authorize Terrakube CLI login", """
            <h1>Terrakube CLI login</h1>
            <p>A Terraform / OpenTofu CLI is requesting access to Terrakube as
               <strong>%s</strong>.</p>
            %s
            <form method="post" action="/oauth/consent">
              <label for="days">Token duration</label>
              <select id="days" name="days">%s</select>
              <div class="actions">
                <button type="submit" name="decision" value="authorize">Authorize</button>
                <button type="submit" name="decision" value="deny" class="secondary">Deny</button>
              </div>
            </form>
            """.formatted(escape(email), error, options));
    }

    public String renderError(String message) {
        return page("Terrakube CLI login", """
            <h1>Login failed</h1>
            <p>%s</p>
            <p>You can close this window and try <code>terraform login</code> again.</p>
            """.formatted(escape(message)));
    }

    public String renderSuccess() {
        return page("Terrakube CLI login", """
            <h1>Authorized</h1>
            <p>Return to your terminal — you can close this window.</p>
            """);
    }

    private String page(String title, String body) {
        return """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>%s</title>
            <style>
              body{font-family:system-ui,sans-serif;max-width:34rem;margin:4rem auto;padding:0 1rem;color:#1f2430}
              h1{font-size:1.35rem}
              select,button{font-size:1rem;padding:.5rem .75rem;border-radius:.4rem}
              .actions{margin-top:1.25rem;display:flex;gap:.75rem}
              button{background:#5c4ee5;color:#fff;border:0;cursor:pointer}
              button.secondary{background:#e6e6ef;color:#1f2430}
              .error{color:#b3261e;font-weight:600}
              label{display:block;margin-bottom:.35rem;font-weight:600}
            </style></head><body>%s</body></html>
            """.formatted(escape(title), body);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./mvnw -pl api test -Dtest=ConsentPageRendererTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add api/src/main/java/io/terrakube/api/plugin/token/login/ConsentPageRenderer.java \
        api/src/test/java/io/terrakube/api/plugin/token/login/ConsentPageRendererTest.java
git commit -m "feat: add server-rendered consent page for the login broker"
```

---

### Task 9: `CliLoginService` — session lifecycle orchestration

**Files:**
- Create: `api/src/main/java/io/terrakube/api/plugin/token/login/CliLoginService.java`
- Create: `api/src/main/java/io/terrakube/api/plugin/token/login/dto/` — `AuthorizeRequest`, `TokenRequest`, `TokenResponse` records
- Create: `api/src/test/java/io/terrakube/api/CliLoginServiceTests.java`

**Interfaces:**
- Consumes: `CliAuthSessionRepository`, `DexExchangeClient`, `PatService`, `LoopbackRedirectUriValidator`, `TerraformLoginProperties`, `ObjectMapper`.
- Produces (all on `CliLoginService`):
  - `record AuthorizeRequest(String clientId, String redirectUri, String responseType, String codeChallenge, String codeChallengeMethod, String state)`
  - `record TokenRequest(String grantType, String code, String codeVerifier, String redirectUri, String clientId)`
  - `record TokenResponse(String accessToken, String tokenType, long expiresIn)`
  - `String startAuthorization(AuthorizeRequest req)` → validates params + redirect_uri, creates `PENDING_IDP` session, returns the Dex `/auth` redirect URL. Throws `BrokerBadRequestException`.
  - `String handleCallback(String code, String state, String iss)` → loads `PENDING_IDP` session by id=`state`, checks not expired, checks `iss` (if present) equals `dexExchangeClient.issuerUri()`, exchanges code, stores identity, sets `PENDING_CONSENT`, clears `dexCodeVerifier`, returns the **session id** (controller turns it into the cookie + redirect to `/oauth/consent`). Throws `BrokerBadRequestException` / `BrokerUpstreamException`.
  - `CliAuthSession requireConsentSession(String sessionId)` → returns the session iff `PENDING_CONSENT` and not expired, else `BrokerBadRequestException`.
  - `String authorize(String sessionId, int days)` → validates `1 <= days <= maxDays`, generates auth code, stores `sha256(code)` + `chosenDays`, sets `CODE_ISSUED` + `codeExpiresAt = now+60s`, returns `cliRedirectUri + "?code=" + code + "&state=" + urlEncode(cliState)`.
  - `String deny(String sessionId)` → sets `DENIED`, returns `cliRedirectUri + "?error=access_denied&state=" + urlEncode(cliState)`.
  - `TokenResponse exchangeToken(TokenRequest req)` → looks up by `sha256(req.code)`, checks `CODE_ISSUED` + not past `codeExpiresAt` + `redirectUri` and `clientId` match + `PkceUtil.verifyS256(req.codeVerifier, session.cliCodeChallenge)`; mints PAT via `patService.createToken(chosenDays, description, name, email, groupsList, "CLI_LOGIN")`; sets `EXCHANGED`; returns `TokenResponse(jws, "Bearer", chosenDays * 86400L)`. Any failed precondition → `BrokerBadRequestException("invalid_grant")`.
  - Static helper `sha256Hex(String)` for auth-code hashing.

- [ ] **Step 1: Write the failing test**

```java
package io.terrakube.api;

import io.terrakube.api.plugin.token.login.*;
import io.terrakube.api.repository.CliAuthSessionRepository;
import io.terrakube.api.rs.token.login.CliAuthSession;
import io.terrakube.api.rs.token.login.CliAuthSessionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class CliLoginServiceTests extends ServerApplicationTests {

    @Autowired CliLoginService service;
    @Autowired CliAuthSessionRepository repository;
    @MockitoBean DexExchangeClient dexExchangeClient;

    private CliLoginService.AuthorizeRequest validAuthorize(String challenge) {
        return new CliLoginService.AuthorizeRequest(
            "terraform-cli", "http://localhost:10000/login", "code", challenge, "S256", "cli-state-1");
    }

    @Test
    void startAuthorizationRejectsBadClientId() {
        var req = new CliLoginService.AuthorizeRequest(
            "wrong", "http://localhost:10000/login", "code", "c", "S256", "s");
        assertThrows(BrokerBadRequestException.class, () -> service.startAuthorization(req));
    }

    @Test
    void startAuthorizationRejectsPlainPkce() {
        var req = new CliLoginService.AuthorizeRequest(
            "terraform-cli", "http://localhost:10000/login", "code", "c", "plain", "s");
        assertThrows(BrokerBadRequestException.class, () -> service.startAuthorization(req));
    }

    @Test
    void fullHappyPath() {
        when(dexExchangeClient.issuerUri()).thenReturn("http://localhost/dex");
        when(dexExchangeClient.buildAuthorizeRedirect(anyString(), anyString()))
            .thenReturn("http://localhost/dex/auth?state=x");
        when(dexExchangeClient.exchange(anyString(), anyString()))
            .thenReturn(new DexIdentity("alice@example.io", "Alice", List.of("TERRAKUBE_DEVELOPERS")));

        String verifier = PkceUtil.generateCodeVerifier();
        String challenge = PkceUtil.codeChallengeS256(verifier);

        String redirect = service.startAuthorization(validAuthorize(challenge));
        assertTrue(redirect.contains("/dex/auth"));

        CliAuthSession session = repository.findAll().stream()
            .filter(s -> "cli-state-1".equals(s.getCliState())).findFirst().orElseThrow();
        assertEquals(CliAuthSessionStatus.PENDING_IDP, session.getStatus());

        String sessionId = service.handleCallback("dex-code", session.getId().toString(), "http://localhost/dex");
        assertEquals(session.getId().toString(), sessionId);
        assertEquals(CliAuthSessionStatus.PENDING_CONSENT,
            repository.findById(session.getId()).orElseThrow().getStatus());

        String cliRedirect = service.authorize(sessionId, 30);
        assertTrue(cliRedirect.startsWith("http://localhost:10000/login?code="));
        assertTrue(cliRedirect.contains("state=cli-state-1"));

        String code = cliRedirect.replaceAll(".*code=([^&]+).*", "$1");
        var tokenResponse = service.exchangeToken(new CliLoginService.TokenRequest(
            "authorization_code", code, verifier, "http://localhost:10000/login", "terraform-cli"));
        assertEquals("Bearer", tokenResponse.tokenType());
        assertEquals(30L * 86400, tokenResponse.expiresIn());
        assertTrue(tokenResponse.accessToken().split("\\.").length == 3);
        assertEquals(CliAuthSessionStatus.EXCHANGED,
            repository.findById(session.getId()).orElseThrow().getStatus());
    }

    @Test
    void exchangeRejectsWrongVerifier() {
        when(dexExchangeClient.issuerUri()).thenReturn("http://localhost/dex");
        when(dexExchangeClient.buildAuthorizeRedirect(anyString(), anyString())).thenReturn("http://x/dex/auth");
        when(dexExchangeClient.exchange(anyString(), anyString()))
            .thenReturn(new DexIdentity("a@e.io", "A", List.of()));
        String verifier = PkceUtil.generateCodeVerifier();
        String challenge = PkceUtil.codeChallengeS256(verifier);
        String redirect = service.startAuthorization(validAuthorize(challenge));
        CliAuthSession s = repository.findAll().stream()
            .filter(x -> "cli-state-1".equals(x.getCliState())).findFirst().orElseThrow();
        service.handleCallback("c", s.getId().toString(), null);
        String cliRedirect = service.authorize(s.getId().toString(), 30);
        String code = cliRedirect.replaceAll(".*code=([^&]+).*", "$1");
        assertThrows(BrokerBadRequestException.class, () -> service.exchangeToken(
            new CliLoginService.TokenRequest("authorization_code", code, "WRONG",
                "http://localhost:10000/login", "terraform-cli")));
    }

    @Test
    void exchangeRejectsReusedCode() {
        when(dexExchangeClient.issuerUri()).thenReturn("http://localhost/dex");
        when(dexExchangeClient.buildAuthorizeRedirect(anyString(), anyString())).thenReturn("http://x/dex/auth");
        when(dexExchangeClient.exchange(anyString(), anyString()))
            .thenReturn(new DexIdentity("a@e.io", "A", List.of()));
        String verifier = PkceUtil.generateCodeVerifier();
        String redirect = service.startAuthorization(validAuthorize(PkceUtil.codeChallengeS256(verifier)));
        CliAuthSession s = repository.findAll().stream()
            .filter(x -> "cli-state-1".equals(x.getCliState())).findFirst().orElseThrow();
        service.handleCallback("c", s.getId().toString(), null);
        String code = service.authorize(s.getId().toString(), 30).replaceAll(".*code=([^&]+).*", "$1");
        var ok = new CliLoginService.TokenRequest("authorization_code", code, verifier,
            "http://localhost:10000/login", "terraform-cli");
        service.exchangeToken(ok);
        assertThrows(BrokerBadRequestException.class, () -> service.exchangeToken(ok));
    }

    @Test
    void authorizeRejectsDaysOverCap() {
        when(dexExchangeClient.issuerUri()).thenReturn("http://localhost/dex");
        when(dexExchangeClient.buildAuthorizeRedirect(anyString(), anyString())).thenReturn("http://x/dex/auth");
        when(dexExchangeClient.exchange(anyString(), anyString()))
            .thenReturn(new DexIdentity("a@e.io", "A", List.of()));
        String verifier = PkceUtil.generateCodeVerifier();
        service.startAuthorization(validAuthorize(PkceUtil.codeChallengeS256(verifier)));
        CliAuthSession s = repository.findAll().stream()
            .filter(x -> "cli-state-1".equals(x.getCliState())).findFirst().orElseThrow();
        service.handleCallback("c", s.getId().toString(), null);
        assertThrows(BrokerBadRequestException.class, () -> service.authorize(s.getId().toString(), 9999));
    }
}
```

Note: the default `application-test.properties` `max-days` is unset → `90`. `days=30` is valid, `9999` is not.

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -pl api test -Dtest=CliLoginServiceTests`
Expected: FAIL — `CliLoginService` missing.

- [ ] **Step 3: Implement**

```java
package io.terrakube.api.plugin.token.login;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.token.pat.PatService;
import io.terrakube.api.repository.CliAuthSessionRepository;
import io.terrakube.api.rs.token.login.CliAuthSession;
import io.terrakube.api.rs.token.login.CliAuthSessionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CliLoginService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long SESSION_TTL_MS = 10 * 60 * 1000L;
    private static final long CODE_TTL_MS = 60 * 1000L;

    private final CliAuthSessionRepository repository;
    private final DexExchangeClient dexExchangeClient;
    private final PatService patService;
    private final LoopbackRedirectUriValidator redirectUriValidator;
    private final TerraformLoginProperties loginProperties;
    private final ObjectMapper objectMapper;

    public record AuthorizeRequest(String clientId, String redirectUri, String responseType,
                                   String codeChallenge, String codeChallengeMethod, String state) {}
    public record TokenRequest(String grantType, String code, String codeVerifier,
                               String redirectUri, String clientId) {}
    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {}

    @Transactional
    public String startAuthorization(AuthorizeRequest req) {
        if (!TerraformLoginProperties.CLIENT_ID.equals(req.clientId()))
            throw new BrokerBadRequestException("unknown client_id");
        if (!"code".equals(req.responseType()))
            throw new BrokerBadRequestException("response_type must be code");
        if (!"S256".equals(req.codeChallengeMethod()))
            throw new BrokerBadRequestException("code_challenge_method must be S256");
        if (req.codeChallenge() == null || req.codeChallenge().isBlank())
            throw new BrokerBadRequestException("code_challenge is required");
        if (req.state() == null || req.state().isBlank())
            throw new BrokerBadRequestException("state is required");
        redirectUriValidator.validate(req.redirectUri());

        CliAuthSession session = new CliAuthSession();
        session.setStatus(CliAuthSessionStatus.PENDING_IDP);
        session.setCliRedirectUri(req.redirectUri());
        session.setCliCodeChallenge(req.codeChallenge());
        session.setCliState(req.state());
        session.setDexCodeVerifier(PkceUtil.generateCodeVerifier());
        session.setExpiresAt(new Date(System.currentTimeMillis() + SESSION_TTL_MS));
        session = repository.save(session);

        return dexExchangeClient.buildAuthorizeRedirect(
            session.getId().toString(),
            PkceUtil.codeChallengeS256(session.getDexCodeVerifier()));
    }

    @Transactional
    public String handleCallback(String code, String state, String iss) {
        CliAuthSession session = loadActive(parseId(state));
        if (session.getStatus() != CliAuthSessionStatus.PENDING_IDP)
            throw new BrokerBadRequestException("session is not awaiting identity provider");
        if (iss != null && !iss.isBlank() && !dexExchangeClient.issuerUri().equals(iss)) {
            fail(session);
            throw new BrokerBadRequestException("issuer mismatch");
        }
        DexIdentity identity;
        try {
            identity = dexExchangeClient.exchange(code, session.getDexCodeVerifier());
        } catch (RuntimeException e) {
            fail(session);
            throw e;
        }
        session.setIdentityEmail(identity.email());
        session.setIdentityName(identity.name());
        try {
            session.setIdentityGroups(objectMapper.writeValueAsString(
                identity.groups() == null ? List.of() : identity.groups()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        session.setDexCodeVerifier(null);
        session.setStatus(CliAuthSessionStatus.PENDING_CONSENT);
        repository.save(session);
        return session.getId().toString();
    }

    @Transactional(readOnly = true)
    public CliAuthSession requireConsentSession(String sessionId) {
        CliAuthSession session = loadActive(parseId(sessionId));
        if (session.getStatus() != CliAuthSessionStatus.PENDING_CONSENT)
            throw new BrokerBadRequestException("session is not awaiting consent");
        return session;
    }

    @Transactional
    public String authorize(String sessionId, int days) {
        CliAuthSession session = requireConsentSession(sessionId);
        if (days < 1 || days > loginProperties.getMaxDays())
            throw new BrokerBadRequestException("days must be between 1 and " + loginProperties.getMaxDays());
        String code = randomToken();
        session.setAuthCodeHash(sha256Hex(code));
        session.setChosenDays(days);
        session.setStatus(CliAuthSessionStatus.CODE_ISSUED);
        session.setCodeExpiresAt(new Date(System.currentTimeMillis() + CODE_TTL_MS));
        repository.save(session);
        return session.getCliRedirectUri() + "?code=" + urlEnc(code) + "&state=" + urlEnc(session.getCliState());
    }

    @Transactional
    public String deny(String sessionId) {
        CliAuthSession session = requireConsentSession(sessionId);
        session.setStatus(CliAuthSessionStatus.DENIED);
        repository.save(session);
        return session.getCliRedirectUri() + "?error=access_denied&state=" + urlEnc(session.getCliState());
    }

    @Transactional
    public TokenResponse exchangeToken(TokenRequest req) {
        if (!"authorization_code".equals(req.grantType()))
            throw new BrokerBadRequestException("invalid_grant");
        CliAuthSession session = repository.findByAuthCodeHash(sha256Hex(req.code()))
            .orElseThrow(() -> new BrokerBadRequestException("invalid_grant"));
        boolean valid = session.getStatus() == CliAuthSessionStatus.CODE_ISSUED
            && session.getCodeExpiresAt() != null
            && session.getCodeExpiresAt().after(new Date())
            && session.getCliRedirectUri().equals(req.redirectUri())
            && TerraformLoginProperties.CLIENT_ID.equals(req.clientId())
            && PkceUtil.verifyS256(req.codeVerifier(), session.getCliCodeChallenge());
        if (!valid) throw new BrokerBadRequestException("invalid_grant");

        List<String> groups;
        try {
            groups = session.getIdentityGroups() == null ? List.of()
                : objectMapper.readValue(session.getIdentityGroups(), new TypeReference<List<String>>() {});
        } catch (Exception e) {
            groups = List.of();
        }
        String description = "terraform login " + DateTimeFormatter.ISO_LOCAL_DATE
            .withZone(ZoneOffset.UTC).format(Instant.now());
        String jws = patService.createToken(session.getChosenDays(), description,
            session.getIdentityName(), session.getIdentityEmail(), groups, "CLI_LOGIN");
        if (jws == null || jws.isBlank())
            throw new BrokerUpstreamException("token generation failed");

        session.setStatus(CliAuthSessionStatus.EXCHANGED);
        repository.save(session);
        return new TokenResponse(jws, "Bearer", session.getChosenDays() * 86400L);
    }

    private CliAuthSession loadActive(UUID id) {
        CliAuthSession session = repository.findById(id)
            .orElseThrow(() -> new BrokerBadRequestException("session not found"));
        if (session.getExpiresAt().before(new Date()))
            throw new BrokerBadRequestException("session expired");
        return session;
    }

    private void fail(CliAuthSession session) {
        session.setStatus(CliAuthSessionStatus.FAILED);
        session.setDexCodeVerifier(null);
        repository.save(session);
    }

    private static UUID parseId(String s) {
        try { return UUID.fromString(s); }
        catch (Exception e) { throw new BrokerBadRequestException("session not found"); }
    }

    private static String randomToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String sha256Hex(String input) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String urlEnc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./mvnw -pl api test -Dtest=CliLoginServiceTests`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add api/src/main/java/io/terrakube/api/plugin/token/login/CliLoginService.java \
        api/src/test/java/io/terrakube/api/CliLoginServiceTests.java
git commit -m "feat: add CliLoginService orchestrating the login broker session lifecycle"
```

---

### Task 10: `OAuthBrokerController` — HTTP endpoints

**Files:**
- Create: `api/src/main/java/io/terrakube/api/plugin/token/login/OAuthBrokerController.java`
- (Test coverage for the controller comes with the security wiring in Task 11 — the controller alone cannot be reached until `DexWebSecurityAdapter` permits `/oauth/**`.)

**Interfaces:**
- Consumes: `CliLoginService`, `CliLoginCookie`, `ConsentPageRenderer`, `TerraformLoginProperties`.
- Produces HTTP endpoints under `/oauth`:
  - `GET /oauth/authorize` (query params) → `302` to the Dex `/auth` URL. On `BrokerBadRequestException` → `400 text/html` error page.
  - `GET /oauth/callback` (`code`, `state`, optional `iss`) → `302` to `/oauth/consent` **with** `Set-Cookie: tk_cli_login`. On broker exceptions → `400 text/html`.
  - `GET /oauth/consent` → `200 text/html` consent page (cookie-gated). No cookie / bad session → `403`/`400 text/html`.
  - `POST /oauth/consent` (form: `decision`, `days`) → `302` to the loopback URL. Bad `days` → `400 text/html` re-rendered page. `Origin`/`Referer` host mismatch → `403`.
  - `POST /oauth/token` (form) → `200 application/json` `{access_token, token_type, expires_in}` or `400 application/json {"error":"invalid_grant"}`.

- [ ] **Step 1: Implement the controller**

```java
package io.terrakube.api.plugin.token.login;

import io.terrakube.api.rs.token.login.CliAuthSession;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/oauth")
@RequiredArgsConstructor
public class OAuthBrokerController {

    private final CliLoginService cliLoginService;
    private final CliLoginCookie cliLoginCookie;
    private final ConsentPageRenderer consentPageRenderer;
    private final TerraformLoginProperties loginProperties;

    @GetMapping("/authorize")
    public ResponseEntity<String> authorize(
            @RequestParam("client_id") String clientId,
            @RequestParam("redirect_uri") String redirectUri,
            @RequestParam("response_type") String responseType,
            @RequestParam("code_challenge") String codeChallenge,
            @RequestParam(value = "code_challenge_method", required = false) String codeChallengeMethod,
            @RequestParam("state") String state) {
        try {
            String location = cliLoginService.startAuthorization(new CliLoginService.AuthorizeRequest(
                clientId, redirectUri, responseType, codeChallenge,
                codeChallengeMethod == null ? "S256" : codeChallengeMethod, state));
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
        } catch (BrokerBadRequestException e) {
            return htmlError(e.getMessage());
        }
    }

    @GetMapping("/callback")
    public ResponseEntity<String> callback(
            @RequestParam("code") String code,
            @RequestParam("state") String state,
            @RequestParam(value = "iss", required = false) String iss) {
        try {
            String sessionId = cliLoginService.handleCallback(code, state, iss);
            return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.SET_COOKIE, cliLoginCookie.build(sessionId).toString())
                .location(URI.create(loginProperties.getApiUrl() + "/oauth/consent"))
                .build();
        } catch (BrokerBadRequestException | BrokerUpstreamException e) {
            return htmlError(e.getMessage());
        }
    }

    @GetMapping(value = "/consent", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> consentPage(
            @CookieValue(value = CliLoginCookie.COOKIE_NAME, required = false) String cookie) {
        Optional<String> sessionId = cliLoginCookie.verify(cookie);
        if (sessionId.isEmpty()) return htmlForbidden("Missing or invalid session.");
        try {
            CliAuthSession session = cliLoginService.requireConsentSession(sessionId.get());
            return html(consentPageRenderer.renderConsent(
                session.getIdentityEmail(), loginProperties.getDefaultDays(),
                loginProperties.getMaxDays(), null));
        } catch (BrokerBadRequestException e) {
            return htmlError(e.getMessage());
        }
    }

    @PostMapping(value = "/consent", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> consentSubmit(
            @CookieValue(value = CliLoginCookie.COOKIE_NAME, required = false) String cookie,
            @RequestParam("decision") String decision,
            @RequestParam(value = "days", required = false, defaultValue = "0") int days,
            HttpServletRequest request) {
        Optional<String> sessionId = cliLoginCookie.verify(cookie);
        if (sessionId.isEmpty()) return htmlForbidden("Missing or invalid session.");
        if (!originMatches(request)) return htmlForbidden("Bad request origin.");
        try {
            String location = "deny".equals(decision)
                ? cliLoginService.deny(sessionId.get())
                : cliLoginService.authorize(sessionId.get(), days);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
        } catch (BrokerBadRequestException e) {
            // re-render the consent page with the message when the session is still consent-able
            try {
                CliAuthSession session = cliLoginService.requireConsentSession(sessionId.get());
                return ResponseEntity.badRequest().contentType(MediaType.TEXT_HTML)
                    .body(consentPageRenderer.renderConsent(session.getIdentityEmail(),
                        loginProperties.getDefaultDays(), loginProperties.getMaxDays(), e.getMessage()));
            } catch (BrokerBadRequestException ignored) {
                return htmlError(e.getMessage());
            }
        }
    }

    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> token(
            @RequestParam("grant_type") String grantType,
            @RequestParam("code") String code,
            @RequestParam("code_verifier") String codeVerifier,
            @RequestParam("redirect_uri") String redirectUri,
            @RequestParam("client_id") String clientId) {
        try {
            CliLoginService.TokenResponse r = cliLoginService.exchangeToken(
                new CliLoginService.TokenRequest(grantType, code, codeVerifier, redirectUri, clientId));
            return ResponseEntity.ok(Map.of(
                "access_token", r.accessToken(),
                "token_type", r.tokenType(),
                "expires_in", r.expiresIn()));
        } catch (BrokerBadRequestException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_grant"));
        } catch (BrokerUpstreamException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "server_error"));
        }
    }

    private boolean originMatches(HttpServletRequest request) {
        String expectedHost = URI.create(loginProperties.getApiUrl()).getHost();
        for (String header : new String[]{"Origin", "Referer"}) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank()) {
                try {
                    return expectedHost.equalsIgnoreCase(URI.create(value).getHost());
                } catch (Exception e) {
                    return false;
                }
            }
        }
        return false; // neither header present → reject
    }

    private static ResponseEntity<String> html(String body) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(body);
    }

    private ResponseEntity<String> htmlError(String message) {
        return ResponseEntity.badRequest().contentType(MediaType.TEXT_HTML)
            .body(consentPageRenderer.renderError(message));
    }

    private ResponseEntity<String> htmlForbidden(String message) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.TEXT_HTML)
            .body(consentPageRenderer.renderError(message));
    }
}
```

- [ ] **Step 2: Compile check**

Run: `./mvnw -pl api test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add api/src/main/java/io/terrakube/api/plugin/token/login/OAuthBrokerController.java
git commit -m "feat: add OAuthBrokerController endpoints for terraform login"
```

---

### Task 11: Security wiring + disabled→404 + full-flow integration test

**Files:**
- Modify: `api/src/main/java/io/terrakube/api/plugin/security/authentication/dex/DexWebSecurityAdapter.java`
- Create: `api/src/test/java/io/terrakube/api/TerraformLoginBrokerIntegrationTests.java`
- Create: `api/src/test/java/io/terrakube/api/TerraformLoginBrokerDisabledTests.java`

**Interfaces:**
- Consumes: `TerraformLoginProperties`.
- Produces: `/oauth/**` is `permitAll` and CSRF-exempt **only when** `io.terrakube.token.login.enabled=true`; otherwise every `/oauth/**` path returns `404`.

- [ ] **Step 1: Write the failing tests**

`TerraformLoginBrokerDisabledTests` (uses the default test properties, broker disabled):

```java
package io.terrakube.api;

import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;

class TerraformLoginBrokerDisabledTests extends ServerApplicationTests {

    @Test
    void oauthEndpointsAreInvisibleWhenDisabled() {
        given().when().get("/oauth/authorize?client_id=terraform-cli&redirect_uri=http://localhost:10000/login"
                + "&response_type=code&code_challenge=x&code_challenge_method=S256&state=s")
            .then().statusCode(404);
        given().contentType("application/x-www-form-urlencoded")
            .formParam("grant_type", "authorization_code").formParam("code", "x")
            .formParam("code_verifier", "y").formParam("redirect_uri", "http://localhost:10000/login")
            .formParam("client_id", "terraform-cli")
            .when().post("/oauth/token").then().statusCode(404);
    }
}
```

`TerraformLoginBrokerIntegrationTests` (broker enabled, Dex mocked):

```java
package io.terrakube.api;

import io.restassured.http.Cookie;
import io.restassured.response.Response;
import io.terrakube.api.plugin.token.login.DexExchangeClient;
import io.terrakube.api.plugin.token.login.DexIdentity;
import io.terrakube.api.plugin.token.login.PkceUtil;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "io.terrakube.token.login.enabled=true",
    "io.terrakube.token.login.api-url=http://localhost:8080",
    "io.terrakube.token.login.max-days=90"
})
class TerraformLoginBrokerIntegrationTests extends ServerApplicationTests {

    @MockitoBean DexExchangeClient dexExchangeClient;

    @Test
    void endToEndIssuesUsableRevocablePat() {
        when(dexExchangeClient.issuerUri()).thenReturn("http://localhost/dex");
        when(dexExchangeClient.buildAuthorizeRedirect(anyString(), anyString()))
            .thenAnswer(inv -> "http://localhost/dex/auth?state=" + inv.getArgument(0));
        when(dexExchangeClient.exchange(anyString(), anyString()))
            .thenReturn(new DexIdentity("alice@terrakube.io", "Alice", List.of("TERRAKUBE_DEVELOPERS")));

        String verifier = PkceUtil.generateCodeVerifier();
        String challenge = PkceUtil.codeChallengeS256(verifier);

        // 1. /authorize -> 302 to Dex
        Response authz = given().redirects().follow(false)
            .queryParam("client_id", "terraform-cli")
            .queryParam("redirect_uri", "http://localhost:10005/login")
            .queryParam("response_type", "code")
            .queryParam("code_challenge", challenge)
            .queryParam("code_challenge_method", "S256")
            .queryParam("state", "cli-xyz")
            .when().get("/oauth/authorize");
        authz.then().statusCode(302).header("Location", containsString("/dex/auth?state="));
        String dexState = authz.getHeader("Location").replaceAll(".*state=", "");

        // 2. /callback -> 302 to /oauth/consent + Set-Cookie
        Response cb = given().redirects().follow(false)
            .queryParam("code", "dex-code").queryParam("state", dexState)
            .when().get("/oauth/callback");
        cb.then().statusCode(302).header("Location", containsString("/oauth/consent"));
        Cookie session = cb.getDetailedCookie("tk_cli_login");

        // 3. GET /oauth/consent -> form
        given().cookie("tk_cli_login", session.getValue())
            .when().get("/oauth/consent")
            .then().statusCode(200).body(containsString("alice@terrakube.io"))
            .body(containsString("name=\"decision\" value=\"authorize\""));

        // 4. POST /oauth/consent -> 302 to loopback with code
        Response consent = given().redirects().follow(false)
            .cookie("tk_cli_login", session.getValue())
            .header("Origin", "http://localhost:8080")
            .contentType("application/x-www-form-urlencoded")
            .formParam("decision", "authorize").formParam("days", "45")
            .when().post("/oauth/consent");
        consent.then().statusCode(302)
            .header("Location", startsWith("http://localhost:10005/login?code="))
            .header("Location", containsString("state=cli-xyz"));
        String code = consent.getHeader("Location").replaceAll(".*code=([^&]+).*", "$1");

        // 5. POST /oauth/token -> access_token
        String token = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("grant_type", "authorization_code").formParam("code", code)
            .formParam("code_verifier", verifier)
            .formParam("redirect_uri", "http://localhost:10005/login")
            .formParam("client_id", "terraform-cli")
            .when().post("/oauth/token")
            .then().statusCode(200)
            .body("token_type", equalTo("Bearer"))
            .body("expires_in", equalTo(45 * 86400))
            .extract().path("access_token");

        // 6. token works against a protected endpoint
        given().header("Authorization", "Bearer " + token)
            .when().get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8")
            .then().statusCode(200);

        // 7. revoke it -> now 401
        String jti = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]))
            .replaceAll(".*\"jti\":\"([^\"]+)\".*", "$1");
        given().header("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
            .when().delete("/pat/v1/" + jti).then().statusCode(anyOf(is(202), is(200)));
        given().header("Authorization", "Bearer " + token)
            .when().get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8")
            .then().statusCode(401);
    }

    @Test
    void reusedAuthCodeIsRejected() {
        when(dexExchangeClient.issuerUri()).thenReturn("http://localhost/dex");
        when(dexExchangeClient.buildAuthorizeRedirect(anyString(), anyString()))
            .thenAnswer(inv -> "http://localhost/dex/auth?state=" + inv.getArgument(0));
        when(dexExchangeClient.exchange(anyString(), anyString()))
            .thenReturn(new DexIdentity("a@terrakube.io", "A", List.of("TERRAKUBE_DEVELOPERS")));
        String verifier = PkceUtil.generateCodeVerifier();
        String challenge = PkceUtil.codeChallengeS256(verifier);
        Response authz = given().redirects().follow(false)
            .queryParam("client_id", "terraform-cli").queryParam("redirect_uri", "http://localhost:10005/login")
            .queryParam("response_type", "code").queryParam("code_challenge", challenge)
            .queryParam("code_challenge_method", "S256").queryParam("state", "s2")
            .when().get("/oauth/authorize");
        String dexState = authz.getHeader("Location").replaceAll(".*state=", "");
        Response cb = given().redirects().follow(false)
            .queryParam("code", "c").queryParam("state", dexState).when().get("/oauth/callback");
        String cookie = cb.getDetailedCookie("tk_cli_login").getValue();
        Response consent = given().redirects().follow(false).cookie("tk_cli_login", cookie)
            .header("Origin", "http://localhost:8080")
            .contentType("application/x-www-form-urlencoded")
            .formParam("decision", "authorize").formParam("days", "30")
            .when().post("/oauth/consent");
        String code = consent.getHeader("Location").replaceAll(".*code=([^&]+).*", "$1");
        var form = given().contentType("application/x-www-form-urlencoded")
            .formParam("grant_type", "authorization_code").formParam("code", code)
            .formParam("code_verifier", verifier).formParam("redirect_uri", "http://localhost:10005/login")
            .formParam("client_id", "terraform-cli");
        form.when().post("/oauth/token").then().statusCode(200);
        given().contentType("application/x-www-form-urlencoded")
            .formParam("grant_type", "authorization_code").formParam("code", code)
            .formParam("code_verifier", verifier).formParam("redirect_uri", "http://localhost:10005/login")
            .formParam("client_id", "terraform-cli")
            .when().post("/oauth/token").then().statusCode(400).body("error", equalTo("invalid_grant"));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -pl api test -Dtest='TerraformLoginBrokerDisabledTests,TerraformLoginBrokerIntegrationTests'`
Expected: FAIL — enabled flow currently 401/403 (endpoints not permitted); disabled flow returns 401 not 404.

- [ ] **Step 3: Implement the security wiring**

In `DexWebSecurityAdapter`, inject `TerraformLoginProperties loginProperties` into the `filterChain` bean (add a parameter — Spring resolves the bean) and:

1. Add a new high-priority chain that only exists to hide `/oauth/**` when disabled:

```java
@Bean
@Order(0)
public SecurityFilterChain filterChainOauthBrokerDisabled(HttpSecurity http,
        TerraformLoginProperties loginProperties) throws Exception {
    http.securityMatcher("/oauth/**");
    if (loginProperties.isEnabled()) {
        // Broker on: let the main chain handle it; this chain must not short-circuit.
        // Returning a chain that matches but denies would block it, so instead we
        // make this chain not match by using a matcher that never matches.
        http.securityMatcher(request -> false);
        return http.authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
    }
    return http
        .authorizeHttpRequests(a -> a.anyRequest().denyAll())
        .exceptionHandling(e -> e.authenticationEntryPoint(
            (req, res, ex) -> res.setStatus(404)))
        .csrf(c -> c.disable())
        .build();
}
```

   Simpler and clearer alternative that avoids the double-`securityMatcher` trick — gate the whole bean with `@ConditionalOnProperty` is not possible for "return 404 when absent", so instead handle it inside the existing `@Order(1)` chain: when disabled, register a matcher that denies `/oauth/**` with a 404 entry point; when enabled, `permitAll` + csrf-ignore. Use this form:

```java
// inside the existing @Order(1) filterChain, in the authorizeHttpRequests block, FIRST:
if (loginProperties.isEnabled()) {
    authz.requestMatchers("/oauth/**").permitAll();
} else {
    authz.requestMatchers("/oauth/**").denyAll();
}
```

   and in the `.csrf(...)` customizer add `/oauth/**` to `ignoringRequestMatchers` only when enabled; add a small `AccessDeniedHandler`/`AuthenticationEntryPoint` that returns `404` for `/oauth/**` when disabled:

```java
.exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
    (req, res, e) -> res.setStatus(HttpServletResponse.SC_NOT_FOUND),
    new AntPathRequestMatcher("/oauth/**")))
```

   Pick the single-chain approach (second form). It is the least surprising and keeps all `/oauth` handling in one place.

- [ ] **Step 4: Run to verify pass**

Run: `./mvnw -pl api test -Dtest='TerraformLoginBrokerDisabledTests,TerraformLoginBrokerIntegrationTests'`
Expected: PASS.

- [ ] **Step 5: Run the security-sensitive neighbours**

Run: `./mvnw -pl api test -Dtest='AccessTests,TokenTests,WellKnownLoginBrokerTests'`
Expected: PASS (no regression to the main chain).

- [ ] **Step 6: Commit**

```bash
git add api/src/main/java/io/terrakube/api/plugin/security/authentication/dex/DexWebSecurityAdapter.java \
        api/src/test/java/io/terrakube/api/TerraformLoginBrokerIntegrationTests.java \
        api/src/test/java/io/terrakube/api/TerraformLoginBrokerDisabledTests.java
git commit -m "feat: wire login broker endpoints into security, hidden when disabled"
```

---

### Task 12: `CliAuthSessionCleanupTask` — expire stale sessions

**Files:**
- Create: `api/src/main/java/io/terrakube/api/plugin/token/login/CliAuthSessionCleanupTask.java`
- Create: `api/src/test/java/io/terrakube/api/CliAuthSessionCleanupTaskTests.java`

**Interfaces:**
- Consumes: `CliAuthSessionRepository`, `TerraformLoginProperties`.
- Produces: `@Scheduled` bean; method `int purgeExpired()` returns the number of rows deleted (called by the scheduler and directly by the test). `@Scheduled(fixedDelayString = "${io.terrakube.token.login.cleanup-interval-ms:300000}")`. `@EnableScheduling` is already on `ServerApplication`.

- [ ] **Step 1: Write the failing test**

```java
package io.terrakube.api;

import io.terrakube.api.plugin.token.login.CliAuthSessionCleanupTask;
import io.terrakube.api.repository.CliAuthSessionRepository;
import io.terrakube.api.rs.token.login.CliAuthSession;
import io.terrakube.api.rs.token.login.CliAuthSessionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class CliAuthSessionCleanupTaskTests extends ServerApplicationTests {

    @Autowired CliAuthSessionCleanupTask task;
    @Autowired CliAuthSessionRepository repository;

    @Test
    @Transactional
    void purgesOnlyExpiredRows() {
        repository.save(newSession("live", new Date(System.currentTimeMillis() + 60000)));
        repository.save(newSession("dead", new Date(System.currentTimeMillis() - 60000)));

        int deleted = task.purgeExpired();

        assertTrue(deleted >= 1);
        assertTrue(repository.findAll().stream().anyMatch(s -> "live".equals(s.getCliState())));
        assertFalse(repository.findAll().stream().anyMatch(s -> "dead".equals(s.getCliState())));
    }

    private CliAuthSession newSession(String state, Date expiresAt) {
        CliAuthSession s = new CliAuthSession();
        s.setStatus(CliAuthSessionStatus.PENDING_IDP);
        s.setCliRedirectUri("http://localhost:10000/login");
        s.setCliCodeChallenge("c");
        s.setCliState(state);
        s.setExpiresAt(expiresAt);
        return s;
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -pl api test -Dtest=CliAuthSessionCleanupTaskTests`
Expected: FAIL — class missing.

- [ ] **Step 3: Implement**

```java
package io.terrakube.api.plugin.token.login;

import io.terrakube.api.repository.CliAuthSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

@Slf4j
@Component
@RequiredArgsConstructor
public class CliAuthSessionCleanupTask {

    private final CliAuthSessionRepository repository;

    @Scheduled(fixedDelayString = "${io.terrakube.token.login.cleanup-interval-ms:300000}")
    @Transactional
    public int purgeExpired() {
        long deleted = repository.deleteByExpiresAtBefore(new Date());
        if (deleted > 0) log.info("Purged {} expired cli_auth_session rows", deleted);
        return (int) deleted;
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./mvnw -pl api test -Dtest=CliAuthSessionCleanupTaskTests`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add api/src/main/java/io/terrakube/api/plugin/token/login/CliAuthSessionCleanupTask.java \
        api/src/test/java/io/terrakube/api/CliAuthSessionCleanupTaskTests.java
git commit -m "feat: scheduled cleanup of expired cli_auth_session rows"
```

---

### Task 13: `last_used_at` throttled write on PAT authentication (API + Registry)

**Files:**
- Modify: `api/src/main/java/io/terrakube/api/plugin/security/authentication/dex/DexAuthenticationManagerResolver.java`
- Modify: `registry/src/main/java/io/terrakube/registry/configuration/authentication/dex/RegistryAuthenticationManagerResolver.java`
- Create: `api/src/test/java/io/terrakube/api/PatLastUsedTests.java`

**Interfaces:**
- Consumes: `PatRepository` (API — already available), `TerrakubeClient` (registry — already available), the authenticated `Jwt` (for `jti` + `iss == "Terrakube"`).
- Produces: after a successful PAT authentication (`iss == "Terrakube"`), `pat.last_used_at` is set to now, but only if the stored value is null or older than 1 hour (throttle). Never blocks or fails the request on write error (log + continue).

- [ ] **Step 1: Write the failing test (API)**

```java
package io.terrakube.api;

import io.terrakube.api.repository.PatRepository;
import io.terrakube.api.rs.token.pat.Pat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

class PatLastUsedTests extends ServerApplicationTests {

    @Autowired PatRepository patRepository;

    @Test
    void firstUseStampsLastUsedAt() {
        String token = generatePAT("TERRAKUBE_DEVELOPERS");
        UUID id = UUID.fromString(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]))
            .replaceAll(".*\"jti\":\"([^\"]+)\".*", "$1"));
        assertNull(patRepository.findById(id).orElseThrow().getLastUsedAt());

        given().header("Authorization", "Bearer " + token)
            .when().get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8")
            .then().statusCode(200);

        Pat pat = patRepository.findById(id).orElseThrow();
        assertNotNull(pat.getLastUsedAt());
    }

    @Test
    void recentUseIsNotRewritten() {
        String token = generatePAT("TERRAKUBE_DEVELOPERS");
        UUID id = UUID.fromString(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]))
            .replaceAll(".*\"jti\":\"([^\"]+)\".*", "$1"));
        Pat pat = patRepository.findById(id).orElseThrow();
        Date pinned = new Date(System.currentTimeMillis() - 5 * 60 * 1000); // 5 min ago
        pat.setLastUsedAt(pinned);
        patRepository.save(pat);

        given().header("Authorization", "Bearer " + token)
            .when().get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8")
            .then().statusCode(200);

        assertEquals(pinned.getTime() / 1000,
            patRepository.findById(id).orElseThrow().getLastUsedAt().getTime() / 1000);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -pl api test -Dtest=PatLastUsedTests`
Expected: FAIL — `last_used_at` stays null.

- [ ] **Step 3: Implement (API)**

In `DexAuthenticationManagerResolver.resolve(...)`, the PAT case builds `new ProviderManager(new JwtAuthenticationProvider(getJwtEncoder(jwtTypePat)))`. Wrap that provider manager so that on a successful authentication it stamps `last_used_at`. The cleanest place is a small success handler: add a private method and use `ProviderManager`'s result. Since `resolve` returns an `AuthenticationManager`, wrap it:

```java
case jwtTypePat: {
    ProviderManager delegate = new ProviderManager(new JwtAuthenticationProvider(getJwtEncoder(jwtTypePat)));
    providerManager = null;
    return authentication -> {
        Authentication result = delegate.authenticate(authentication);
        stampLastUsed(result);
        return result;
    };
}
```

with:

```java
private static final long LAST_USED_THROTTLE_MS = 60 * 60 * 1000L;

private void stampLastUsed(Authentication result) {
    try {
        if (!(result instanceof JwtAuthenticationToken jwt)) return;
        if (!jwtTypePat.equals(jwt.getToken().getClaimAsString("iss"))) return;
        String jti = jwt.getToken().getId();
        if (jti == null) return;
        UUID id = UUID.fromString(jti);
        patRepository.findById(id).ifPresent(pat -> {
            Date last = pat.getLastUsedAt();
            if (last == null || System.currentTimeMillis() - last.getTime() > LAST_USED_THROTTLE_MS) {
                pat.setLastUsedAt(new Date());
                patRepository.save(pat);
            }
        });
    } catch (Exception e) {
        log.debug("Could not stamp PAT last_used_at: {}", e.getMessage());
    }
}
```

(`patRepository` is already a field on the builder-constructed resolver. `import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;` and `java.util.Date` / `java.util.UUID`.)

- [ ] **Step 4: Implement (Registry)**

`RegistryAuthenticationManagerResolver` has no `PatRepository` — it has `TerrakubeClient`. Check whether `TerrakubeClient` exposes a PAT update call; if not, **skip the registry write** and add a `// last_used_at is stamped by the API auth path only` comment. Rationale: the registry is read-mostly for module/provider downloads and the API path already covers the common "token in use" signal. Do not add a new client method in this task. (Recorded as acceptable in the spec's Decisions — update the spec if the reviewer disagrees.)

- [ ] **Step 5: Run to verify pass**

Run: `./mvnw -pl api test -Dtest='PatLastUsedTests,TokenTests,AccessTests'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add api/src/main/java/io/terrakube/api/plugin/security/authentication/dex/DexAuthenticationManagerResolver.java \
        registry/src/main/java/io/terrakube/registry/configuration/authentication/dex/RegistryAuthenticationManagerResolver.java \
        api/src/test/java/io/terrakube/api/PatLastUsedTests.java
git commit -m "feat: stamp pat.last_used_at on successful token auth (throttled)"
```

---

### Task 14: UI — token source badge and Last used column

**Files:**
- Modify: `ui/src/modules/token/types.ts`
- Modify: `ui/src/modules/user/components/PatSection/PatSection.tsx`
- Create: `ui/src/modules/user/components/PatSection/__tests__/PatSection.test.tsx` (if a sibling test dir pattern exists; otherwise co-locate as `PatSection.test.tsx`)

**Interfaces:**
- Consumes: `/pat/v1` GET response — now includes `source: "API" | "CLI_LOGIN"` and `lastUsedAt: string | null` per token (the `Pat` entity is serialized wholesale by `PatController.searchToken`).
- Produces: the personal-token table shows a tag ("CLI login" for `CLI_LOGIN`, otherwise "API") and a "Last used" column rendering a relative date or "Never".

- [ ] **Step 1: Inspect the current component**

Run: `sed -n '1,140p' ui/src/modules/user/components/PatSection/PatSection.tsx` and `cat ui/src/modules/token/types.ts`
Confirm the token row type name and the table `columns` array shape.

- [ ] **Step 2: Write the failing test**

```tsx
import { render, screen } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import PatSection from "../PatSection";

vi.mock("@/modules/user/userService", () => ({
  default: {
    getPersonalAccessTokens: vi.fn().mockResolvedValue({
      data: [
        { id: "1", description: "cli", days: 30, source: "CLI_LOGIN", lastUsedAt: null, createdDate: "2026-08-01T00:00:00Z" },
        { id: "2", description: "manual", days: 0, source: "API", lastUsedAt: "2026-08-30T00:00:00Z", createdDate: "2026-07-01T00:00:00Z" },
      ],
    }),
    createPersonalAccessToken: vi.fn(),
    deletePersonalAccessToken: vi.fn(),
  },
}));

describe("PatSection", () => {
  it("shows the CLI login badge and a Last used column", async () => {
    render(<PatSection />);
    expect(await screen.findByText("CLI login")).toBeInTheDocument();
    expect(screen.getByText("Last used")).toBeInTheDocument();
    expect(screen.getByText("Never")).toBeInTheDocument();
  });
});
```

Adjust the mock's service method/return shape to match what Step 1 revealed (e.g. if the component consumes `useApiRequest` rather than the service directly, mock at that boundary — follow the pattern already used in a neighbouring `__tests__` file such as `ui/src/domain/Settings/__tests__/Notifications.test.tsx`).

- [ ] **Step 3: Run to verify failure**

Run: `cd ui && npx vitest run src/modules/user/components/PatSection`
Expected: FAIL — no "CLI login" / "Last used" text.

- [ ] **Step 4: Implement**

In `ui/src/modules/token/types.ts`, add to the token row type:

```ts
  source?: "API" | "CLI_LOGIN";
  lastUsedAt?: string | null;
```

In `PatSection.tsx`, add a column before "Actions":

```tsx
{
  title: "Source",
  dataIndex: "source",
  key: "source",
  render: (source?: string) =>
    source === "CLI_LOGIN" ? <Tag color="blue">CLI login</Tag> : <Tag>API</Tag>,
},
{
  title: "Last used",
  dataIndex: "lastUsedAt",
  key: "lastUsedAt",
  render: (value?: string | null) =>
    value ? DateTime.fromISO(value).toRelative() : "Never",
},
```

(`Tag` from `antd`, `DateTime` from `luxon` — both already used elsewhere in the module; import if not present in this file.)

- [ ] **Step 5: Run to verify pass**

Run: `cd ui && npx vitest run src/modules/user/components/PatSection`
Expected: PASS.

- [ ] **Step 6: Run the UI typecheck + lint**

Run: `cd ui && npx tsc --noEmit && npx eslint src/modules/user/components/PatSection src/modules/token`
Expected: clean.

- [ ] **Step 7: Commit**

```bash
git add ui/src/modules/token/types.ts ui/src/modules/user/components/PatSection/
git commit -m "feat(ui): show token source badge and last-used column in the PAT list"
```

---

### Task 15: Manual end-to-end verification (terraform + tofu)

**Files:** none (verification only — record results in the PR description).

**Interfaces:** exercises the whole feature against the docker-compose stack.

- [ ] **Step 1: Start the stack with the broker enabled**

```bash
cd docker-compose
TERRAFORM_LOGIN_ENABLED=true docker compose up -d
```

Wait for `terrakube-api` health. Confirm discovery:

```bash
curl -sk https://terrakube-api.platform.local/.well-known/terraform.json | jq '."login.v1"'
```

Expected: `client` = `terraform-cli`, `authz`/`token` on the API host, `ports` `[10000,10010]`.

- [ ] **Step 2: `terraform login`**

```bash
terraform login terrakube-api.platform.local
```

Expected: browser opens → Dex login → Terrakube consent page showing your email + a duration dropdown → choose 90 days → "Authorized" page → terminal prints "Success! Terraform has obtained and saved an API token."

Verify: `jq . ~/.terraform.d/credentials.tfrc.json` shows a token for `terrakube-api.platform.local`; decode its payload and confirm `iss: "Terrakube"` and `exp` ≈ now + 90 days.

- [ ] **Step 3: Use the token**

In a workspace configured for that Terrakube, run `terraform init` (private module + remote state). Expected: both succeed using the stored token.

- [ ] **Step 4: `tofu login`**

```bash
tofu login terrakube-api.platform.local
```

Expected: identical flow and a working token.

- [ ] **Step 5: Revocation**

In the Terrakube UI → user settings → tokens: the new token appears with a "CLI login" badge and a recent "Last used". Delete it. Re-run `terraform init` → expect `401`; re-run `terraform login` to get a fresh one.

- [ ] **Step 6: Flag-off regression**

```bash
docker compose down && docker compose up -d   # TERRAFORM_LOGIN_ENABLED unset
curl -sk https://terrakube-api.platform.local/.well-known/terraform.json | jq '."login.v1".authz'
```

Expected: back to the Dex `/auth` URL; `curl -sk https://terrakube-api.platform.local/oauth/authorize` → `404`. A plain `terraform login` still works via the direct Dex flow.

- [ ] **Step 7: Record results and open the PR**

```bash
git push -u origin claude/terraform-login-token-duration-6dcbcb
gh pr create --title "feat: configurable token duration for terraform/tofu login" --body "$(cat <<'EOF'
Implements a feature-flagged OAuth broker so `terraform login` / `tofu login` issues a revocable Terrakube PAT with a user-chosen, admin-capped lifetime.

Spec: docs/superpowers/specs/2026-08-31-terraform-login-token-duration-design.md

## Manual verification
- [x] terraform login → consent page → 90-day token → init works
- [x] tofu login → same
- [x] token shows in UI with CLI-login badge; revoke → 401
- [x] flag off → discovery + flow unchanged, /oauth/* → 404

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review

**1. Spec coverage**

| Spec section | Task |
|---|---|
| `.well-known` conditional `login.v1`, widened `ports` (API + registry) | 2 |
| `GET /oauth/authorize` (param + redirect_uri validation, session create, Dex redirect) | 3 (validators), 9 (logic), 10 (endpoint) |
| `GET /oauth/callback` (session lookup, iss check, Dex exchange, identity, cookie) | 4 (cookie), 7 (Dex), 9 (logic), 10 (endpoint) |
| `GET /oauth/consent` server-rendered page | 8, 10 |
| `POST /oauth/consent` (days validation, auth code, deny) + Origin check | 9, 10 |
| `POST /oauth/token` (PKCE verify, single-use code, mint PAT, no refresh token) | 9, 10 |
| `cli_auth_session` table + `pat.source` / `pat.last_used_at` | 5 |
| Cleanup task | 12 |
| Config properties + fail-fast + docker-compose wiring | 1 |
| PAT `source` overload; minted JWT never persisted | 6, 9 |
| `last_used_at` throttled write | 13 |
| Security: hidden when disabled, permitAll + CSRF-exempt when enabled | 11 |
| Security: S256-only, loopback-only redirect, hashed single-use codes, iss mix-up, no offline_access | 3, 7, 9 (+ tests in 9, 11) |
| UI source badge + last-used column | 14 |
| Manual terraform + tofu verification | 15 |
| Asymmetric PAT signing | Explicitly deferred (spec Non-goals) — no task, intentional |
| Helm chart values/README | Noted in Task 1 scope as a separate repo; docs-only, no code task here |

No gaps in scope. The Helm chart lives in `terrakube-helm-chart` (separate repo) — call this out in the PR so the chart PR is tracked separately.

**2. Placeholder scan**

No "TBD"/"handle edge cases"/"similar to Task N". Task 11 Step 3 offers two implementation forms and then explicitly says "Pick the single-chain approach (second form)" — that is a definite instruction, not a placeholder. Task 13 Step 4 is a definite "skip and comment" instruction with rationale, not a deferral.

**3. Type consistency**

- `createToken(int, String, Object, Object, Object, String)` — defined Task 6, called Task 9. ✓
- `touchLastUsed` defined Task 6 but Task 13 uses an inline `patRepository.save` in the resolver instead (the resolver has `PatRepository`, not `PatService`) — consistent, `touchLastUsed` is still used by tests in Task 6 and available if a future caller wants it. Acceptable; noted here so the reviewer isn't surprised it's not called from Task 13.
- `CliLoginService.AuthorizeRequest` / `TokenRequest` / `TokenResponse` record shapes — identical between Task 9 definition, Task 9 tests, and Task 10 controller. ✓
- `DexExchangeClient.issuerUri()` / `buildAuthorizeRedirect(String,String)` / `exchange(String,String)` — consistent Task 7 ↔ Task 9 ↔ Task 11. ✓
- `CliLoginCookie.COOKIE_NAME` / `verify` / `build` — consistent Task 4 ↔ Task 10 ↔ Task 11. ✓
- `CliAuthSessionRepository.findByAuthCodeHash` / `deleteByExpiresAtBefore(Date)` — consistent Task 5 ↔ 9 ↔ 12. ✓
- `TerraformLoginProperties.CLIENT_ID` / `PORT_LOW` / `PORT_HIGH` / `getMaxDays` / `getCallbackUrl` — consistent across Tasks 1, 3, 7, 9, 10. ✓

One fix applied during review: Task 13's `stampLastUsed` reads `jwt.getToken().getClaimAsString("iss")` and compares to `jwtTypePat` (`"Terrakube"`) — matches how `DexAuthenticationManagerResolver` already identifies PAT tokens elsewhere in the class. Consistent.
