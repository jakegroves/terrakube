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
}
