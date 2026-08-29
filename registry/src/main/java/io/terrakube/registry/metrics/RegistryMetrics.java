package io.terrakube.registry.metrics;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** Terraform registry traffic metrics. All tag values are bounded enumerations. */
@Component
public class RegistryMetrics {

    private final MeterRegistry registry;

    public RegistryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordDownload(String type, String organization) {
        Counter.builder("terrakube.registry.download")
                .tag("type", type)
                .tag("organization", organization)
                .description("Module/provider artifact downloads served")
                .register(registry)
                .increment();
    }

    public Timer.Sample startResolve() {
        return Timer.start(registry);
    }

    public void stopResolve(Timer.Sample sample, String type, String organization) {
        sample.stop(Timer.builder("terrakube.registry.resolve")
                .tag("type", type)
                .tag("organization", organization)
                .description("Version-resolution latency")
                .register(registry));
    }

    public void recordAuthFailure(String reason) {
        Counter.builder("terrakube.registry.auth.failure")
                .tag("reason", reason)
                .description("Rejected registry requests")
                .register(registry)
                .increment();
    }
}
