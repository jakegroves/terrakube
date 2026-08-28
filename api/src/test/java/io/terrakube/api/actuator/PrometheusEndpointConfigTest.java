package io.terrakube.api.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/**
 * Guards the api Prometheus scrape config. api already declared {@code prometheus} in the
 * exposure list, but with {@code management.endpoints.enabled-by-default=false} the endpoint also
 * has to be enabled explicitly - without that line {@code /actuator/prometheus} returns 404
 * despite being "exposed". Booting the full api context here would be disproportionate for a
 * config assertion; the live endpoint is exercised by the telemetry-compose integration check.
 */
class PrometheusEndpointConfigTest {

    @Test
    void prometheusRegistryIsOnTheClasspathAndScrapes() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registry.counter("api.prometheus.probe").increment();

        assertThat(registry.scrape()).contains("api_prometheus_probe");
    }

    @Test
    void applicationPropertiesExposesAndEnablesThePrometheusEndpoint() throws IOException {
        String properties = Files.readString(Path.of("src/main/resources/application.properties"));

        assertThat(properties).contains("management.endpoints.web.exposure.include=health,prometheus,info");
        assertThat(properties).contains("management.endpoint.prometheus.enabled=true");
    }
}
