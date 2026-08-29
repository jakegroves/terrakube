package io.terrakube.registry.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class RegistryMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RegistryMetrics metrics = new RegistryMetrics(registry);

    @Test
    void countsDownloadsByTypeAndOrganization() {
        metrics.recordDownload("module", "acme");
        metrics.recordDownload("provider", "acme");
        metrics.recordDownload("provider", "acme");

        assertThat(registry.get("terrakube.registry.download")
                .tags("type", "module", "organization", "acme").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("terrakube.registry.download")
                .tags("type", "provider", "organization", "acme").counter().count()).isEqualTo(2.0);
    }

    @Test
    void timesResolutionByTypeAndOrganization() {
        Timer.Sample sample = metrics.startResolve();
        metrics.stopResolve(sample, "module", "acme");

        assertThat(registry.get("terrakube.registry.resolve")
                .tags("type", "module", "organization", "acme").timer().count()).isEqualTo(1L);
    }

    @Test
    void countsAuthFailuresByReason() {
        metrics.recordAuthFailure("missing_token");

        assertThat(registry.get("terrakube.registry.auth.failure").tag("reason", "missing_token").counter().count()).isEqualTo(1.0);
    }
}
