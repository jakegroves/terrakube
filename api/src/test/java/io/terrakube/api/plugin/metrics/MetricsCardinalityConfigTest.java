package io.terrakube.api.plugin.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MetricsCardinalityConfigTest {

    private SimpleMeterRegistry registryWithFilter(int maxOrganizationTags) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new MetricsCardinalityConfig().cardinalityMeterFilter(maxOrganizationTags));
        return registry;
    }

    @Test
    void dropsMetersCarryingAWorkspaceTag() {
        SimpleMeterRegistry registry = registryWithFilter(200);

        Counter.builder("some.meter").tag("workspace", "ws-1").register(registry).increment();

        assertThat(registry.find("some.meter").counter()).isNull();
    }

    @Test
    void keepsMetersWithoutAWorkspaceTag() {
        SimpleMeterRegistry registry = registryWithFilter(200);

        Counter.builder("ok.meter").tag("organization", "org-1").register(registry).increment();

        assertThat(registry.get("ok.meter").counter().count()).isEqualTo(1.0);
    }

    @Test
    void capsTheOrganizationTagOnTheQueueWaitTimer() {
        SimpleMeterRegistry registry = registryWithFilter(2);

        io.micrometer.core.instrument.Timer.builder("terrakube.job.queue.wait").tag("organization", "a").register(registry);
        io.micrometer.core.instrument.Timer.builder("terrakube.job.queue.wait").tag("organization", "b").register(registry);
        io.micrometer.core.instrument.Timer.builder("terrakube.job.queue.wait").tag("organization", "c").register(registry);

        assertThat(registry.find("terrakube.job.queue.wait").timers()).hasSize(2);
    }

    @Test
    void capsOrganizationOnAnyMeterNotJustQueueWait() {
        SimpleMeterRegistry registry = registryWithFilter(2);

        for (String org : new String[] {"a", "b", "c"}) {
            Counter.builder("terrakube.run.finished").tag("organization", org).register(registry);
        }

        assertThat(registry.find("terrakube.run.finished").counters()).hasSize(2);
    }

    @Test
    void doesNotCapMetersWithoutAnOrganizationTag() {
        SimpleMeterRegistry registry = registryWithFilter(1);

        Counter.builder("terrakube.plain").tag("k", "v1").register(registry);
        Counter.builder("terrakube.plain").tag("k", "v2").register(registry);

        assertThat(registry.find("terrakube.plain").counters()).hasSize(2);
    }
}
