package io.terrakube.registry.metrics;

import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Adds a stable {@code service} tag to every meter this process exports.
 *
 * <p>Dashboards and alert rules need one label that identifies the Terrakube service regardless of
 * how the scrape target was discovered - a Prometheus Operator {@code ServiceMonitor} labels the
 * target {@code service=<k8s service name>}, a {@code PodMonitor} / {@code VMPodScrape} adds no
 * such label at all, and {@code prometheus.io/scrape} annotation scrapers add their own. Emitting
 * {@code service} from the application makes the label present and consistent in every case, and
 * matches the {@code service} label the reference {@code telemetry-compose} scrape config injects.
 */
@Configuration
public class MetricsCommonTagsConfig {

    static final String SERVICE_NAME = "terrakube-registry";

    @Bean
    MeterRegistryCustomizer<MeterRegistry> serviceCommonTag() {
        return registry -> registry.config().commonTags("service", SERVICE_NAME);
    }
}
