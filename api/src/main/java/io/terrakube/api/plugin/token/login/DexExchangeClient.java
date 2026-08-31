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
            if (idToken == null) {
                throw new BrokerUpstreamException("Dex response missing id_token");
            }
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
