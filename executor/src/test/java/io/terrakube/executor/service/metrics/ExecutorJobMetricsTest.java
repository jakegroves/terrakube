package io.terrakube.executor.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.terrakube.executor.service.executor.JobExecutionWatchdog;

class ExecutorJobMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final JobExecutionWatchdog watchdog = mock(JobExecutionWatchdog.class);
    private final ExecutorJobMetrics metrics = new ExecutorJobMetrics(registry, watchdog);

    @Test
    void recordsExecutionTimerTaggedByToolStepAndResult() {
        Timer.Sample sample = metrics.startExecution();
        metrics.stopExecution(sample, "terraform", "plan", true);

        assertThat(registry.get("terrakube.job.execution")
                .tags("tool", "terraform", "step", "plan", "result", "success")
                .timer().count()).isEqualTo(1L);
    }

    @Test
    void classifiesExitCodes() {
        metrics.recordExit("tofu", 0);
        metrics.recordExit("tofu", 2);

        assertThat(registry.get("terrakube.job.exit")
                .tags("tool", "tofu", "exit_code_class", "ok").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("terrakube.job.exit")
                .tags("tool", "tofu", "exit_code_class", "error").counter().count()).isEqualTo(1.0);
    }

    @Test
    void concurrentGaugeReflectsWatchdogBusyState() {
        when(watchdog.isBusy()).thenReturn(true);
        assertThat(registry.get("terrakube.job.concurrent").gauge().value()).isEqualTo(1.0);

        when(watchdog.isBusy()).thenReturn(false);
        assertThat(registry.get("terrakube.job.concurrent").gauge().value()).isEqualTo(0.0);
    }

    @Test
    void noMeterCarriesAWorkspaceTag() {
        metrics.stopExecution(metrics.startExecution(), "terraform", "apply", false);
        metrics.recordExit("terraform", 1);

        registry.getMeters().forEach(meter ->
                assertThat(meter.getId().getTag("workspace")).as(meter.getId().getName()).isNull());
    }

    @Test
    void countsResourceChangesByPhaseActionAndOrganization() {
        java.util.List<java.util.Map<String, Object>> changes = java.util.List.of(
                java.util.Map.of("action", "create"),
                java.util.Map.of("action", "create"),
                java.util.Map.of("action", "update"),
                java.util.Map.of("action", "no-op"));

        metrics.recordResourceChanges("plan", "org-1", changes);

        assertThat(registry.get("terrakube.resource.changes")
                .tags("phase", "plan", "action", "create", "organization", "org-1")
                .counter().count()).isEqualTo(2.0);
        assertThat(registry.get("terrakube.resource.changes")
                .tags("phase", "plan", "action", "update", "organization", "org-1")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.find("terrakube.resource.changes").tag("action", "no-op").counter()).isNull();
    }

    @Test
    void recordResourceChangesToleratesNullAndEmpty() {
        metrics.recordResourceChanges("apply", "org-1", null);
        metrics.recordResourceChanges("apply", "org-1", java.util.List.of());
        assertThat(registry.find("terrakube.resource.changes").counter()).isNull();
    }

    @Test
    void countsPlanResult() {
        metrics.recordPlanResult("org-1", "no_changes");
        assertThat(registry.get("terrakube.plan.result")
                .tags("result", "no_changes", "organization", "org-1").counter().count()).isEqualTo(1.0);
    }
}
