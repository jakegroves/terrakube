package io.terrakube.api.plugin.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MetricsCommonTagsConfigTest {

    private SimpleMeterRegistry customizedRegistry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new MetricsCommonTagsConfig().serviceCommonTag().customize(registry);
        return registry;
    }

    @Test
    void tagsEveryMeterWithTheServiceName() {
        SimpleMeterRegistry registry = customizedRegistry();

        Counter.builder("any.meter").register(registry).increment();

        assertThat(registry.get("any.meter").tag("service", "terrakube-api").counter().count()).isEqualTo(1.0);
    }

    @Test
    void doesNotOverrideAnExplicitServiceTagOnAMeter() {
        SimpleMeterRegistry registry = customizedRegistry();

        Counter.builder("build.info").tag("service", "terrakube-api").register(registry).increment();

        assertThat(registry.get("build.info").tag("service", "terrakube-api").counter().count()).isEqualTo(1.0);
    }
}
