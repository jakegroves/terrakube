package io.terrakube.registry.configuration.authentication;

import java.io.IOException;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;

import io.terrakube.registry.metrics.RegistryMetrics;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Counts registry 401s by reason ({@code terrakube.registry.auth.failure}) then delegates the
 * actual challenge/response to the real entry point - response behaviour is unchanged.
 */
public class MeteredAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final RegistryMetrics metrics;
    private final AuthenticationEntryPoint delegate;

    public MeteredAuthenticationEntryPoint(RegistryMetrics metrics, AuthenticationEntryPoint delegate) {
        this.metrics = metrics;
        this.delegate = delegate;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException, ServletException {
        try {
            metrics.recordAuthFailure(reason(request, authException));
        } catch (RuntimeException ignored) {
            // never let a metrics failure change the auth response
        }
        delegate.commence(request, response, authException);
    }

    private static String reason(HttpServletRequest request, AuthenticationException ex) {
        String header = request.getHeader("Authorization");
        if (header == null || header.isBlank() || !header.toLowerCase().startsWith("bearer ")) {
            return "missing_token";
        }
        String msg = ex != null && ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
        if (msg.contains("expired")) {
            return "expired_token";
        }
        if (ex instanceof InvalidBearerTokenException || ex instanceof OAuth2AuthenticationException) {
            return "invalid_token";
        }
        return "other";
    }
}
