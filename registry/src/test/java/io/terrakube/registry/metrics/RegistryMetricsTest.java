package io.terrakube.registry.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class RegistryMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RegistryMetrics metrics = new RegistryMetrics(registry);

    @Test
    void countsDownloadsByType() {
        metrics.recordDownload("module");
        metrics.recordDownload("provider");
        metrics.recordDownload("provider");

        assertThat(registry.get("terrakube.registry.download").tag("type", "module").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("terrakube.registry.download").tag("type", "provider").counter().count()).isEqualTo(2.0);
    }

    @Test
    void timesResolutionByType() {
        Timer.Sample sample = metrics.startResolve();
        metrics.stopResolve(sample, "module");

        assertThat(registry.get("terrakube.registry.resolve").tag("type", "module").timer().count()).isEqualTo(1L);
    }

    @Test
    void countsAuthFailuresByReason() {
        metrics.recordAuthFailure("missing_token");

        assertThat(registry.get("terrakube.registry.auth.failure").tag("reason", "missing_token").counter().count()).isEqualTo(1.0);
    }
}
