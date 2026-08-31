package io.terrakube.api.plugin.token.login;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        props.setApiUrl("https://api.local");
        props.normalize();
        client = new DexExchangeClient(props, issuer, "terrakube-app");
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    @Test
    void buildAuthorizeRedirectContainsAllParams() {
        String url = client.buildAuthorizeRedirect("state-123", "challenge-abc");
        assertTrue(url.startsWith(issuer + "/auth?"));
        assertTrue(url.contains("response_type=code"));
        assertTrue(url.contains("client_id=terrakube-app"));
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fapi.local%2Foauth%2Fcallback"));
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
