package io.terrakube.executor.service.metrics;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.terrakube.executor.service.executor.JobExecutionWatchdog;

/**
 * Executor job execution metrics. {@code organization} would be an acceptable tag but is omitted
 * here to keep the per-pod series count minimal; it is available as a span attribute
 * ({@link JobTelemetry}). Workspace identity is never a tag.
 */
@Component
public class ExecutorJobMetrics {

    private final MeterRegistry registry;

    public ExecutorJobMetrics(MeterRegistry registry, JobExecutionWatchdog watchdog) {
        this.registry = registry;
        Gauge.builder("terrakube.job.concurrent", watchdog, w -> w.isBusy() ? 1.0 : 0.0)
                .description("Jobs executing on this pod right now (0 or 1)")
                .register(registry);
    }

    public Timer.Sample startExecution() {
        return Timer.start(registry);
    }

    public void stopExecution(Timer.Sample sample, String tool, String step, boolean success) {
        sample.stop(Timer.builder("terrakube.job.execution")
                .tag("tool", tool)
                .tag("step", step)
                .tag("result", success ? "success" : "failure")
                .description("Duration of a terraform/tofu job phase")
                .register(registry));
    }

    public void recordExit(String tool, int exitCode) {
        Counter.builder("terrakube.job.exit")
                .tag("tool", tool)
                .tag("exit_code_class", exitCode == 0 ? "ok" : "error")
                .description("Terraform/tofu subprocess exit outcomes")
                .register(registry)
                .increment();
    }

    /**
     * Increments {@code terrakube.resource.changes} once per resource in a structured plan/apply
     * change list, keyed by normalised {@code action}. {@code no-op} / blank actions are skipped.
     * A metrics failure never disturbs job execution.
     *
     * @param phase {@code "plan"} or {@code "apply"}
     */
    public void recordResourceChanges(String phase, String organizationId,
                                      java.util.List<java.util.Map<String, Object>> changes) {
        if (changes == null || changes.isEmpty()) {
            return;
        }
        try {
            for (java.util.Map<String, Object> change : changes) {
                Object actionRaw = change.get("action");
                if (!(actionRaw instanceof String action) || action.isBlank() || "no-op".equals(action)) {
                    continue;
                }
                Counter.builder("terrakube.resource.changes")
                        .tag("phase", phase)
                        .tag("action", action)
                        .tag("organization", String.valueOf(organizationId))
                        .description("Resource changes seen in a plan or apply")
                        .register(registry)
                        .increment();
            }
        } catch (RuntimeException e) {
            // never let a metrics failure disturb job execution
        }
    }

    /**
     * Increments {@code terrakube.plan.result} once per plan step. {@code result} is one of
     * {@code changes}, {@code no_changes}, {@code error}.
     */
    public void recordPlanResult(String organizationId, String result) {
        try {
            Counter.builder("terrakube.plan.result")
                    .tag("result", result)
                    .tag("organization", String.valueOf(organizationId))
                    .description("Outcome of a plan step: changes / no_changes / error")
                    .register(registry)
                    .increment();
        } catch (RuntimeException e) {
            // swallow
        }
    }
}
