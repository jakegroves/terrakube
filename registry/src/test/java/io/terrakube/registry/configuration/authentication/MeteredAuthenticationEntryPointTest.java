package io.terrakube.registry.configuration.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.terrakube.registry.metrics.RegistryMetrics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class MeteredAuthenticationEntryPointTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RegistryMetrics metrics = new RegistryMetrics(registry);
    private final AuthenticationEntryPoint delegate = mock(AuthenticationEntryPoint.class);
    private final MeteredAuthenticationEntryPoint subject = new MeteredAuthenticationEntryPoint(metrics, delegate);

    private HttpServletRequest requestWithAuth(String header) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Authorization")).thenReturn(header);
        return req;
    }

    @Test
    void countsMissingTokenAndDelegates() throws Exception {
        HttpServletRequest req = requestWithAuth(null);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        InvalidBearerTokenException ex = new InvalidBearerTokenException("no token");

        subject.commence(req, resp, ex);

        assertThat(registry.get("terrakube.registry.auth.failure").tag("reason", "missing_token").counter().count()).isEqualTo(1.0);
        verify(delegate).commence(req, resp, ex);
    }

    @Test
    void classifiesExpiredToken() throws Exception {
        subject.commence(requestWithAuth("Bearer abc"), mock(HttpServletResponse.class),
                new InvalidBearerTokenException("An error occurred while attempting to decode the Jwt: Jwt expired at 2020-01-01"));

        assertThat(registry.get("terrakube.registry.auth.failure").tag("reason", "expired_token").counter().count()).isEqualTo(1.0);
    }

    @Test
    void classifiesInvalidToken() throws Exception {
        subject.commence(requestWithAuth("Bearer abc"), mock(HttpServletResponse.class),
                new InvalidBearerTokenException("Malformed token"));

        assertThat(registry.get("terrakube.registry.auth.failure").tag("reason", "invalid_token").counter().count()).isEqualTo(1.0);
    }
}
