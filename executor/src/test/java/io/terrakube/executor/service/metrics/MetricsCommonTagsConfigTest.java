package io.terrakube.executor.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MetricsCommonTagsConfigTest {

    @Test
    void tagsEveryMeterWithTheServiceName() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new MetricsCommonTagsConfig().serviceCommonTag().customize(registry);

        Counter.builder("any.meter").register(registry).increment();

        assertThat(registry.get("any.meter").tag("service", "terrakube-executor").counter().count()).isEqualTo(1.0);
    }
}
