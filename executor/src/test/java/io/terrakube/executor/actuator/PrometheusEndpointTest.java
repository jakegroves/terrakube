package io.terrakube.executor.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/**
 * Guards that the executor can serve a Prometheus scrape. The executor module has no
 * {@code @SpringBootTest} precedent (see AdmissionControlIntegrationTest) and no web-slice test
 * infrastructure, so rather than boot a context this pairs two cheap checks that together fail if
 * either half of the "expose /actuator/prometheus" change is reverted:
 * <ul>
 *   <li>the {@code micrometer-registry-prometheus} dependency is on the classpath and scrapes,</li>
 *   <li>{@code application.properties} opts the {@code prometheus} endpoint into web exposure.</li>
 * </ul>
 * The live endpoint is exercised end-to-end by the telemetry-compose integration check.
 */
class PrometheusEndpointTest {

    @Test
    void prometheusRegistryIsOnTheClasspathAndScrapes() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registry.counter("executor.prometheus.probe").increment();

        assertThat(registry.scrape()).contains("executor_prometheus_probe");
    }

    @Test
    void applicationPropertiesExposesAndEnablesThePrometheusEndpoint() throws IOException {
        String properties = Files.readString(Path.of("src/main/resources/application.properties"));

        // Both are required: exposure puts it on the web, and - because enabled-by-default is
        // false - the endpoint must also be individually enabled or it 404s.
        assertThat(properties).contains("management.endpoints.web.exposure.include=health,prometheus,info");
        assertThat(properties).contains("management.endpoint.prometheus.enabled=true");
    }
}
