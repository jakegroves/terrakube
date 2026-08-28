package io.terrakube.api.plugin.metrics;

import java.time.Duration;
import java.util.Date;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;

/**
 * Job state-machine and queue observability.
 *
 * <p>api has no single method that owns job status transitions - they are set on the entity across
 * {@code ScheduleJob}, the reconciliation sweeps and {@code RemoteTfeService}. The one call every
 * real transition on the primary path funnels through is
 * {@code JobNotificationTrigger.notifyStatusChanged(Job)}, which is where {@link #recordStatus} is
 * invoked from.
 *
 * <p>Only the destination status is reliably available there, so the transition counter is keyed by
 * {@code to} alone (throughput is {@code rate(...{to="completed"})}, failure ratio is
 * {@code ...{to="failed"} / total}). Workspace identity is never a tag - it belongs on a span.
 */
@Component
public class JobLifecycleMetrics {

    private final MeterRegistry registry;

    public JobLifecycleMetrics(MeterRegistry registry, Supplier<Number> activeWorkspaceCount) {
        this.registry = registry;
        Gauge.builder("terrakube.workspace.active", activeWorkspaceCount, s -> s.get().doubleValue())
                .description("Current number of workspaces")
                .register(registry);
    }

    /**
     * Records that {@code job} has just entered {@code job.getStatus()}. Safe to call on every
     * notify-status-changed event; a repeated status is still a meaningful "entered" count.
     */
    public void recordStatus(Job job) {
        JobStatus status = job.getStatus();
        if (status == null) {
            return;
        }

        Counter.builder("terrakube.job.transitions")
                .tag("to", status.name())
                .description("Count of jobs entering a given status")
                .register(registry)
                .increment();

        if (status == JobStatus.queue) {
            recordQueueWait(job);
        }
    }

    private void recordQueueWait(Job job) {
        Date created = job.getCreatedDate();
        if (created == null || job.getOrganization() == null) {
            return;
        }
        long millis = System.currentTimeMillis() - created.getTime();
        if (millis < 0) {
            return;
        }
        Timer.builder("terrakube.job.queue.wait")
                .tag("organization", String.valueOf(job.getOrganization().getId()))
                .description("Time from job creation to dispatch (entering the executor queue)")
                .register(registry)
                .record(Duration.ofMillis(millis));
    }
}
