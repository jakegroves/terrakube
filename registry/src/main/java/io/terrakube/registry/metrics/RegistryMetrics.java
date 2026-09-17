package io.terrakube.registry.metrics;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** Terraform registry traffic metrics. All tag values are bounded enumerations. */
@Component
public class RegistryMetrics {

    private final MeterRegistry registry;
    @Value("${io.terrakube.observability.metrics.enabled:false}")
    private boolean metricsEnabled = true;

    public RegistryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordDownload(String type, String organization) {
        if (!metricsEnabled) {
            return;
        }
        Counter.builder("terrakube.registry.download")
                .tag("type", type)
                .tag("organization", organization)
                .description("Module/provider artifact downloads served")
                .register(registry)
                .increment();
    }

    public Timer.Sample startResolve() {
        return metricsEnabled ? Timer.start(registry) : null;
    }

    public void stopResolve(Timer.Sample sample, String type, String organization) {
        if (!metricsEnabled || sample == null) {
            return;
        }
        sample.stop(Timer.builder("terrakube.registry.resolve")
                .tag("type", type)
                .tag("organization", organization)
                .description("Version-resolution latency")
                .register(registry));
    }

    public void recordAuthFailure(String reason) {
        if (!metricsEnabled) {
            return;
        }
        Counter.builder("terrakube.registry.auth.failure")
                .tag("reason", reason)
                .description("Rejected registry requests")
                .register(registry)
                .increment();
    }
}
