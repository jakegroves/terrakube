package io.terrakube.api;

import io.terrakube.api.plugin.token.login.*;
import io.terrakube.api.repository.CliAuthSessionRepository;
import io.terrakube.api.rs.token.login.CliAuthSessionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class CliLoginServiceTests extends ServerApplicationTests {

    @Autowired
    CliLoginService service;
    @Autowired
    CliAuthSessionRepository repository;
    @MockitoBean
    DexExchangeClient dexExchangeClient;

    private CliLoginService.AuthorizeRequest validAuthorize(String challenge, String state) {
        return new CliLoginService.AuthorizeRequest(
            "terraform-cli", "http://localhost:10000/login", "code", challenge, "S256", state);
    }

    private void stubDex(DexIdentity identity) {
        when(dexExchangeClient.issuerUri()).thenReturn("http://localhost/dex");
        when(dexExchangeClient.buildAuthorizeRedirect(anyString(), anyString()))
            .thenAnswer(inv -> "http://localhost/dex/auth?state=" + inv.getArgument(0));
        when(dexExchangeClient.exchange(anyString(), anyString())).thenReturn(identity);
    }

    private String sessionIdFromRedirect(String redirect) {
        return redirect.replaceAll(".*state=", "");
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
        stubDex(new DexIdentity("alice@example.io", "Alice", List.of("TERRAKUBE_DEVELOPERS")));
        String verifier = PkceUtil.generateCodeVerifier();
        String challenge = PkceUtil.codeChallengeS256(verifier);

        String redirect = service.startAuthorization(validAuthorize(challenge, "cli-happy"));
        String sessionId = sessionIdFromRedirect(redirect);
        assertEquals(CliAuthSessionStatus.PENDING_IDP,
            repository.findById(UUID.fromString(sessionId)).orElseThrow().getStatus());

        String returned = service.handleCallback("dex-code", sessionId, "http://localhost/dex");
        assertEquals(sessionId, returned);
        assertEquals(CliAuthSessionStatus.PENDING_CONSENT,
            repository.findById(UUID.fromString(sessionId)).orElseThrow().getStatus());

        String cliRedirect = service.authorize(sessionId, 30);
        assertTrue(cliRedirect.startsWith("http://localhost:10000/login?code="));
        assertTrue(cliRedirect.contains("state=cli-happy"));

        String code = cliRedirect.replaceAll(".*code=([^&]+).*", "$1");
        var tokenResponse = service.exchangeToken(new CliLoginService.TokenRequest(
            "authorization_code", code, verifier, "http://localhost:10000/login", "terraform-cli"));
        assertEquals("Bearer", tokenResponse.tokenType());
        assertEquals(30L * 86400, tokenResponse.expiresIn());
        assertEquals(3, tokenResponse.accessToken().split("\\.").length);
        assertEquals(CliAuthSessionStatus.EXCHANGED,
            repository.findById(UUID.fromString(sessionId)).orElseThrow().getStatus());
    }

    @Test
    void exchangeRejectsWrongVerifier() {
        stubDex(new DexIdentity("a@e.io", "A", List.of()));
        String verifier = PkceUtil.generateCodeVerifier();
        String redirect = service.startAuthorization(
            validAuthorize(PkceUtil.codeChallengeS256(verifier), "cli-wrongv"));
        String sessionId = sessionIdFromRedirect(redirect);
        service.handleCallback("c", sessionId, null);
        String cliRedirect = service.authorize(sessionId, 30);
        String code = cliRedirect.replaceAll(".*code=([^&]+).*", "$1");
        assertThrows(BrokerBadRequestException.class, () -> service.exchangeToken(
            new CliLoginService.TokenRequest("authorization_code", code, "WRONG",
                "http://localhost:10000/login", "terraform-cli")));
    }

    @Test
    void exchangeRejectsReusedCode() {
        stubDex(new DexIdentity("a@e.io", "A", List.of()));
        String verifier = PkceUtil.generateCodeVerifier();
        String redirect = service.startAuthorization(
            validAuthorize(PkceUtil.codeChallengeS256(verifier), "cli-reuse"));
        String sessionId = sessionIdFromRedirect(redirect);
        service.handleCallback("c", sessionId, null);
        String code = service.authorize(sessionId, 30).replaceAll(".*code=([^&]+).*", "$1");
        var ok = new CliLoginService.TokenRequest("authorization_code", code, verifier,
            "http://localhost:10000/login", "terraform-cli");
        service.exchangeToken(ok);
        assertThrows(BrokerBadRequestException.class, () -> service.exchangeToken(ok));
    }

    @Test
    void authorizeRejectsDaysOverCap() {
        stubDex(new DexIdentity("a@e.io", "A", List.of()));
        String verifier = PkceUtil.generateCodeVerifier();
        String redirect = service.startAuthorization(
            validAuthorize(PkceUtil.codeChallengeS256(verifier), "cli-cap"));
        String sessionId = sessionIdFromRedirect(redirect);
        service.handleCallback("c", sessionId, null);
        assertThrows(BrokerBadRequestException.class, () -> service.authorize(sessionId, 9999));
    }
}
