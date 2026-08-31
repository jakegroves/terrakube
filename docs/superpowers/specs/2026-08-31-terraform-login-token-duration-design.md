# Terraform / Tofu Login — Configurable Token Duration

**Status:** Design
**Date:** 2026-08-31
**Branch:** `claude/terraform-login-token-duration-6dcbcb`

## Problem

`terraform login <terrakube-host>` (and `tofu login`) today runs the OAuth2
authorization-code + PKCE flow **directly against Dex**. The
`/.well-known/terraform.json` `login.v1` block points `authz` and `token` at
`${DexIssuerUri}/auth` and `${DexIssuerUri}/token`, so Terrakube is not in the
token path at all.

Consequences:

- The credential Terraform stores is a Dex `id_token`. Its lifetime is whatever
  Dex's `expiry` config says (default ~24h; the shipped `config-ldap.yaml` sets
  nothing, so pure Dex defaults).
- The Terraform CLI **does not implement refresh tokens or token expiration**
  (per the [login protocol spec](https://developer.hashicorp.com/terraform/internals/login-protocol)).
  When the token expires the user must re-run `terraform login`.
- There is no way for a user to choose a longer lifetime, and no admin control
  over it beyond editing Dex config globally.

Terraform Enterprise / Cloud solve this by having the user generate a
long-lived **API token with a chosen duration** in the web portal. Terrakube
already mints its own revocable tokens (PATs, `iss: "Terrakube"`), but they are
not wired into `terraform login`.

## Goals

- `terraform login` / `tofu login` against Terrakube produces a token whose
  lifetime the **user chooses at login time**, bounded by an **admin cap**.
- The resulting token is a normal Terrakube PAT: revocable, listed in the UI,
  audited.
- Backward compatible: off by default; when disabled, the flow is byte-for-byte
  what it is today.
- No change to token *validation* anywhere (API, registry, TFE state backend
  already accept `iss: "Terrakube"` PATs including `jti` revocation checks).

## Non-goals

- Refresh tokens / refresh rotation (the CLI does not support them).
- Adopting Spring Authorization Server or any full OAuth2 AS framework.
- Per-organization or per-team duration policy (single global cap for now).
- Device Authorization Grant (RFC 8628) — not supported by `terraform login`.
- Migrating PAT signing from HMAC/HS256 to asymmetric RS256 + JWKS. This is a
  worthwhile pre-existing hardening (the dynamic-credentials path already does
  it via `JwksController`) but is **out of scope** here; tracked as a follow-up.

## Approach

Terrakube API gains a minimal **OAuth2 authorization broker** used *only* by the
`terraform login` client. The CLI talks to Terrakube; Terrakube performs the
real authentication upstream against Dex; Terrakube then mints the final token
(a PAT) with the user-chosen, admin-capped lifetime and returns it to the CLI.

Rejected alternative — **Spring Authorization Server**: RFC-complete but a large
new dependency and security-config surface for one public client and one grant
type. Not justified.

Rejected alternative — **just expose Dex `expiry` config**: does not give
per-user duration, and because the CLI cannot refresh, short Dex tokens do not
actually reduce re-authentication. Does not meet the goal.

### Why a long-lived bearer token is acceptable here

[RFC 9700](https://www.rfc-editor.org/info/rfc9700/) (OAuth 2.0 Security BCP)
prefers short-lived access tokens plus refresh rotation. That guidance assumes a
client that refreshes; `terraform login` does not. TFE/TFC reach the same
conclusion (user-generated long-lived API tokens). The trade-off is made
defensible by:

- Tokens are **revocable** — `DexAuthenticationManagerResolver` /
  `RegistryAuthenticationManagerResolver` check `jti` against the `pat` table on
  every request.
- An **admin cap** (default 30 days, max configurable, hard ceiling 365).
- Every issuance is **audited**.
- Tokens are **listed in the UI** with a "CLI login" source badge and a
  `last_used_at` timestamp so stale ones are visible and revocable.

## Components

### 1. `.well-known/terraform.json` (API + Registry)

`WellKnownWebServiceImpl` (api) and `WellKnownWebServiceImpl` (registry) render
`login.v1` conditionally on `io.terrakube.token.login.enabled`:

| Field | Broker OFF (today) | Broker ON |
|-------|--------------------|-----------|
| `client` | `${DexClientId}` | `terraform-cli` (fixed constant) |
| `grant_types` | current list | `["authz_code"]` |
| `authz` | `${DexIssuerUri}/auth?scope=...` | `${ApiUrl}/oauth/authorize` |
| `token` | `${DexIssuerUri}/token` | `${ApiUrl}/oauth/token` |
| `ports` | `[10000, 10001]` | `[10000, 10010]` |

Notes:

- The registry has no database or `PatService`; when the broker is enabled its
  `login.v1` points `authz`/`token` at the **API** URL (new registry config
  property `io.terrakube.registry.login.api-url`, only required when the broker
  is enabled).
- `ports` widened to a 10-port range per the protocol spec's recommendation of
  "at least 10 distinct port numbers" (applies in both modes).
- Both `.well-known` endpoints remain `permitAll`.

### 2. Broker endpoints (API — new package `io.terrakube.api.plugin.token.login`)

All endpoints are registered `permitAll` in `DexWebSecurityAdapter` (they
perform their own session / cookie / PKCE checks) and Spring CSRF is disabled
for `/oauth/**` (the CLI `POST /oauth/token` sends no cookie; the browser
`POST /oauth/consent` is protected by the `SameSite=Lax` signed cookie plus an
`Origin`/`Referer` host check). When the broker flag is **off**, an `@Order(0)`
security matcher returns `404` for the whole `/oauth/**` tree so the endpoints
are invisible.

#### `GET /oauth/authorize`

Query params from the CLI: `client_id`, `redirect_uri`, `response_type=code`,
`code_challenge`, `code_challenge_method`, `state`, `scope`.

1. Validate `client_id == "terraform-cli"`, `response_type == "code"`,
   `code_challenge_method == "S256"` (reject `plain`).
2. **Validate `redirect_uri` strictly** (critical control — prevents the broker
   being used as an open code-exfiltration redirector):
   - scheme `http`
   - host ∈ {`localhost`, `127.0.0.1`, `::1`}
   - port is an integer within the advertised `ports` range
   - path is exactly `/login`
   - no query or fragment
   Reject with `400` (rendered error page) on any mismatch.
3. Create a `cli_auth_session` row:
   `id` (UUIDv4), `cli_redirect_uri`, `cli_code_challenge`, `cli_state`,
   `dex_code_verifier` (freshly generated, 43–128 char), `status = PENDING_IDP`,
   `created_at`, `expires_at = now + 10 min`.
4. 302 the browser to Dex `/auth` with:
   `client_id = ${DexClientId}`,
   `redirect_uri = ${ApiUrl}/oauth/callback`,
   `response_type=code`,
   `code_challenge = S256(dex_code_verifier)`, `code_challenge_method=S256`,
   `scope = openid profile email groups` (no `offline_access` — we do not want a
   Dex refresh token),
   `state = <session id>`.

#### `GET /oauth/callback`

Query params from Dex: `code`, `state`, optional `iss`.

1. Look up the session by `state`. Reject if missing, expired, or
   `status != PENDING_IDP`.
2. If Dex returned `iss`, verify it equals `${DexIssuerUri}` (mix-up defense).
3. Exchange `code` at Dex `/token`:
   `grant_type=authorization_code`, `code`, `redirect_uri=${ApiUrl}/oauth/callback`,
   `client_id=${DexClientId}`, `code_verifier=<dex_code_verifier>`.
4. Validate the returned `id_token` against the Dex JWKS. Extract `email`,
   `name`, `groups`.
5. Update the session: `identity_email`, `identity_name`, `identity_groups`
   (JSON), `status = PENDING_CONSENT`. Clear `dex_code_verifier`.
6. Set a signed cookie `tk_cli_login` — value = `<session id>.<HMAC(session id)>`,
   `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/oauth`, `Max-Age=600`. Same
   origin as the consent page (see below), so `Lax` is sufficient and blocks
   cross-site POST.
7. 302 to `GET /oauth/consent` (same API origin).

On any failure: set `status = FAILED`, render an error page. The CLI's local
server eventually times out.

#### `GET /oauth/consent` (server-rendered HTML)

The consent page is rendered by the **API**, not the UI SPA. Reasons: the SPA
(`ui/src/domain/Home/App.tsx`) renders `<Login/>` and never mounts its router
when the visitor is not already authenticated to the SPA, so a SPA route would
be unreachable mid-`terraform login`; and serving the page from the same origin
that set `tk_cli_login` keeps the cookie `SameSite=Lax` and CSRF trivial. It is
a single self-contained HTML string returned from the controller (same technique
as `WellKnownWebServiceImpl`) with inline CSS and no JavaScript — a plain
`<form method="post">`.

1. Require a valid `tk_cli_login` cookie; derive the session id from it.
2. Require `status == PENDING_CONSENT` and not expired → else error page.
3. Render: "A Terraform / OpenTofu CLI is requesting access to Terrakube as
   **<identity_email>**", a `<select name="days">` (options ≤ `max-days`: 1, 7,
   14, 30, 60, 90; `default-days` pre-selected), the computed expiry date, and
   two submit buttons — `name="decision"` `value="authorize"` /
   `value="deny"`. Hidden field `session` = session id (defence in depth; the
   cookie is authoritative).

#### `POST /oauth/consent`

Form body: `decision`, `days`. Auth: valid `tk_cli_login` cookie for a
`PENDING_CONSENT` session. CSRF: Spring CSRF is disabled for `/oauth/**`;
instead require the `Origin` (or `Referer`) header host to equal the API host,
and rely on the `SameSite=Lax` HttpOnly signed cookie.

- `decision == "deny"` → `status = DENIED`, 302 to
  `cli_redirect_uri?error=access_denied&state=<cli_state>`.
- `decision == "authorize"`:
  1. Validate `days`: `1 <= days <= io.terrakube.token.login.max-days` (itself
     hard-capped at 365). Out of range → 400, re-render the page with a message.
  2. Generate `terrakube_auth_code`: 32 random bytes, base64url. Store only
     `sha256(terrakube_auth_code)` on the session, plus `chosen_days`.
     `status = CODE_ISSUED`, `code_expires_at = now + 60s`.
  3. 302 to `cli_redirect_uri?code=<terrakube_auth_code>&state=<cli_state>`.

#### `POST /oauth/token`

Body (form-encoded, from the CLI): `grant_type=authorization_code`, `code`,
`code_verifier`, `redirect_uri`, `client_id`.

1. `grant_type` must be `authorization_code`.
2. Look up session by `sha256(code)`. Require `status == CODE_ISSUED`,
   `now < code_expires_at`, `redirect_uri == cli_redirect_uri`,
   `client_id == "terraform-cli"`.
3. Verify PKCE: `base64url(sha256(code_verifier)) == cli_code_challenge`.
   Failure → `400 {"error":"invalid_grant"}`.
4. **Now** mint the PAT — `PatService.createToken(chosen_days, description,
   identity_name, identity_email, identity_groups)` where description =
   `"terraform login — <cli host> — <yyyy-MM-dd>"`. Tag the new `pat` row with
   `source = "CLI_LOGIN"`.
5. `status = EXCHANGED`. Return
   `200 {"access_token": "<jws>", "token_type": "Bearer", "expires_in": <chosen_days*86400>}`.
6. Any reuse of the same code → session is now `EXCHANGED` → `400 invalid_grant`.

The minted JWT is **never** persisted (only the `pat` metadata row is).

### 3. Data model

New entity `CliAuthSession` → table `cli_auth_session` (Liquibase file
`db/changelog/local/changelog-2.34.0-cli-auth-session.xml` — DB changelog
versioning is its own `2.x` series, independent of the Maven `revision`;
`<changeSet id>` is the next sequential integer after the current highest —
added as an `<include>` to `changelog.xml`):

| column | type | notes |
|--------|------|-------|
| `id` | varchar(36) | PK, UUIDv4 = OAuth `state` |
| `status` | varchar(20) | PENDING_IDP / PENDING_CONSENT / CODE_ISSUED / EXCHANGED / DENIED / FAILED |
| `cli_redirect_uri` | varchar(255) | |
| `cli_code_challenge` | varchar(128) | |
| `cli_state` | varchar(255) | opaque, from CLI |
| `dex_code_verifier` | varchar(128) | nulled after callback |
| `identity_email` | varchar(255) | null until PENDING_CONSENT |
| `identity_name` | varchar(255) | |
| `identity_groups` | text | JSON array |
| `chosen_days` | int | null until CODE_ISSUED |
| `auth_code_hash` | varchar(64) | sha256 hex, null until CODE_ISSUED |
| `code_expires_at` | datetime | |
| `created_date` / `updated_date` | datetime | via `GenericAuditFields` |
| `expires_at` | datetime | session hard expiry (10 min) |

New column on existing `pat` table (same changeset):
`source varchar(20) default 'API'` — values `API` | `CLI_LOGIN`. Backfills to
`API`. Surfaced in the PAT list API + UI badge.

Cleanup: a scheduled task (`@Scheduled`, reuse existing scheduler config)
deletes `cli_auth_session` rows past `expires_at` every 5 minutes.

### 4. Configuration

`api/src/main/resources/application.properties` (env-var backed, wired through
docker-compose `x-` anchors and the Helm chart `values.yaml`):

| property | env | default | meaning |
|----------|-----|---------|---------|
| `io.terrakube.token.login.enabled` | `TerraformLoginEnabled` | `false` | master switch |
| `io.terrakube.token.login.default-days` | `TerraformLoginDefaultDays` | `30` | pre-selected on the consent page |
| `io.terrakube.token.login.max-days` | `TerraformLoginMaxDays` | `90` | hard cap (itself clamped to ≤365) |
| `io.terrakube.token.login.api-url` | `TerraformLoginApiUrl` | `${ApiUrl}` | absolute API base URL advertised in `.well-known` (`authz`/`token`) and used for the Dex callback + consent redirect |

Registry gains `io.terrakube.registry.login.api-url` (env
`TerraformLoginApiUrl`), only read when the broker is enabled — its `.well-known`
`authz`/`token` point at this API URL.

Reuses existing `io.terrakube.token.issuer-uri` (Dex), `io.terrakube.token.client-id`
(Dex), `io.terrakube.token.pat` (PAT signing secret — also used to derive the
`tk_cli_login` cookie HMAC via HKDF with a distinct info string).

**Operator side benefit (document in the Helm chart README):** with the broker
enabled, Dex no longer needs `http://localhost:1000x/login` entries in
`staticClients[].redirectURIs` — only the single fixed `${ApiUrl}/oauth/callback`.

### 5. UI

The consent flow itself is **entirely server-rendered by the API** (see
`GET /oauth/consent`); the SPA gets no new route. The only SPA change is
cosmetic: the personal-token list (`ui/src/modules/user/components/PatSection`)
shows the new `source` badge ("CLI login" vs "API") and a `Last used` column
when `last_used_at` is present. Team-token views are unaffected.

### 6. `last_used_at`

Add `last_used_at datetime` to `pat` (same changeset). Update it (throttled to
at most once/hour per token to avoid write amplification) when a PAT
authenticates successfully. Applies to all PATs, not just CLI ones.

## Error handling

| Condition | Result |
|-----------|--------|
| Broker disabled, `/oauth/*` hit | `404` |
| Bad `redirect_uri` / `client_id` / `code_challenge_method` at `/authorize` | `400` HTML error page, nothing persisted |
| Session not found / expired at `/callback` or consent | `400` HTML error page |
| Dex `iss` mismatch | `400`, `status=FAILED` |
| Dex token exchange / id_token validation fails | `400`, `status=FAILED` |
| Missing/invalid `tk_cli_login` cookie at `/oauth/consent` | `403` HTML error page |
| `Origin`/`Referer` host ≠ API host at `POST /oauth/consent` | `403` |
| `days` out of range | `400`, page re-renders with message |
| Bad `code_verifier` or reused/expired code at `/token` | `400 {"error":"invalid_grant"}` |
| `PatService` mint fails | `500 {"error":"server_error"}`, `status=FAILED`, no `pat` row (existing rollback in `PatService`) |

## Security checklist (RFC 9700 / RFC 8252)

- [x] PKCE required end-to-end; `S256` only, `plain` rejected.
- [x] PKCE challenge verified at the token endpoint (protocol spec recommendation).
- [x] Loopback-only `redirect_uri`, exact host + port-range + path validation.
- [x] `state` round-tripped to the CLI; upstream `state` bound to the session.
- [x] Dex `iss` verified on callback (mix-up defense).
- [x] Auth code: ≥128 bits entropy, hashed at rest, single-use, ≤60s TTL, bound
      to client + redirect_uri + PKCE challenge.
- [x] Minted bearer token never stored server-side.
- [x] Consent page same-origin as its cookie; gated by a signed HttpOnly
      `SameSite=Lax` cookie; POST additionally checks the `Origin`/`Referer` host.
- [x] No `offline_access` requested upstream; no refresh token issued downstream.
- [x] Issuance audited; tokens revocable and listed.
- [ ] Asymmetric PAT signing — deferred (follow-up).

## Testing

**Unit**

- `RedirectUriValidator`: accepts `http://localhost:10005/login`,
  `http://127.0.0.1:10000/login`; rejects other hosts, ports outside range,
  wrong path, `https`, query/fragment present.
- PKCE: `S256(verifier)` match/mismatch; `plain` rejected.
- Auth code: single-use, TTL expiry, hash-only storage.
- `days` clamping incl. the 365 hard ceiling.
- `.well-known` rendering (api + registry) for both flag states.

**Integration** (`@SpringBootTest`, pattern from `AccessTests` / `TokenTests`,
Dex `/auth` + `/token` + JWKS stubbed):

- Full happy path: `/oauth/authorize` → stub Dex → `/oauth/callback` →
  `POST /oauth/consent` (`decision=authorize`, `days=30`) → `POST /oauth/token`
  → assert response shape and `expires_in`; decode the JWS and assert
  `iss=Terrakube`, `exp ≈ now+30d`, matching `pat` row with `source=CLI_LOGIN`.
- Use the returned token against a protected API endpoint → `200`.
- Revoke via `DELETE /pat/v1/{id}` → same token now `401`.
- Deny path returns `error=access_denied` to the loopback URI.
- Reused auth code → `400 invalid_grant`.
- Expired session → `400`.
- Broker disabled → `/oauth/authorize` is `404` and `.well-known` unchanged.

**Manual**

- `terraform login` and `tofu login` against a docker-compose stack with the
  broker enabled; confirm the browser lands on the consent page, a chosen
  90-day token is stored in `~/.terraform.d/credentials.tfrc.json`, and
  `terraform init` against a private module + remote state both work with it.

## Files touched (estimate)

**API**
- `plugin/token/login/TerraformLoginProperties.java` — new `@ConfigurationProperties`
- `plugin/token/login/PkceUtil.java` — new (S256 challenge, verifier gen/verify)
- `plugin/token/login/LoopbackRedirectUriValidator.java` — new
- `plugin/token/login/CliLoginCookie.java` — new (HKDF-derived HMAC sign/verify)
- `plugin/token/login/DexExchangeClient.java` — new (WebClient calls to Dex)
- `plugin/token/login/CliLoginService.java` — new (session lifecycle, mint via `PatService`)
- `plugin/token/login/OAuthBrokerController.java` — new (`/oauth/authorize`, `/callback`, `/consent` GET+POST, `/token`)
- `plugin/token/login/ConsentPageRenderer.java` — new (HTML string builder)
- `plugin/token/login/CliAuthSessionCleanupTask.java` — new (`@Scheduled`)
- `rs/token/login/CliAuthSession.java`, `rs/token/login/CliAuthSessionStatus.java` — new entity + enum
- `repository/CliAuthSessionRepository.java` — new
- `plugin/state/WellKnownWebServiceImpl.java` — conditional `login.v1`
- `rs/token/pat/Pat.java` — add `source`, `lastUsedAt`
- `plugin/token/pat/PatService.java` — `createToken` overload taking `source`
- `plugin/token/pat/PatController.java` — expose `source`, `lastUsedAt` in list response
- `plugin/security/authentication/dex/DexWebSecurityAdapter.java` — permitAll + CSRF-ignore `/oauth/**`; new `@Order(0)` matcher for `/oauth/authorize` gated on the flag
- `plugin/security/authentication/dex/DexAuthenticationManagerResolver.java` — throttled `last_used_at` write on successful PAT auth
- `resources/db/changelog/local/changelog-2.34.0-cli-auth-session.xml` + `changelog.xml` include
- `resources/application.properties` — 4 new properties

**Registry**
- `controller/WellKnownWebServiceImpl.java` — conditional `login.v1`
- `configuration/OpenRegistryProperties.java` — `loginBrokerEnabled`, `loginApiUrl`
- `configuration/authentication/dex/RegistryAuthenticationManagerResolver.java` — throttled `last_used_at` write (via `TerrakubeClient`)

**UI**
- `ui/src/modules/token/types.ts` — add `source`, `lastUsedAt` to the token type
- `ui/src/modules/user/components/PatSection/PatSection.tsx` — `source` badge + `Last used` column

**Infra**
- `docker-compose/docker-compose.yml` + `docker-compose/env-config.js` — new env vars
- `docker-compose/config-ldap.yaml` — comment showing the `/oauth/callback` redirect URI
- Helm chart `values.yaml` + README — new env vars, Dex `redirectURIs` simplification note (chart lives in a separate repo `terrakube-helm-chart`; note only)

## Decisions (raise before implementation if any should change)

1. **Duration granularity: days only** on the CLI consent page (team tokens
   support `days/hours/minutes`; the CLI case does not need sub-day precision).
2. **Description is auto-generated**, not user-editable — the consent page is
   one decision (duration) plus authorize/deny.
3. **Fail fast** on startup if the broker is enabled but the API base URL for
   `.well-known` / callback is unresolvable, rather than silently falling back
   to the direct-Dex flow.
4. **`last_used_at` applies to all PATs**, not only CLI-login ones — it is a
   small, generally useful addition and the write path is shared.
