package io.terrakube.api.plugin.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;

/**
 * Job state-machine, queue, and business-run observability.
 *
 * <p>api has no single method that owns job status transitions - they are set on the entity across
 * {@code ScheduleJob}, the reconciliation sweeps and {@code RemoteTfeService}. The one call every
 * real transition on the primary path funnels through is
 * {@code JobNotificationTrigger.notifyStatusChanged(Job)}, which is where {@link #recordStatus} is
 * invoked from.
 *
 * <p>Only the destination status is reliably available there, so the transition counter is keyed by
 * {@code to} alone. The {@code terrakube.run.*} family adds the business view: outcome, trigger
 * source ({@code via}), plan-only vs apply, per-organization. Workspace identity is never a tag -
 * it belongs on a span.
 *
 * <p>Approval wait is tracked in an in-memory map because {@code recordStatus} cannot see when the
 * job first entered {@code waitingApproval}; entries in flight at an api restart are lost, which is
 * acceptable for a latency histogram.
 */
@Component
public class JobLifecycleMetrics {

    private static final int MAX_PENDING_APPROVALS = 10_000;
    private static final Duration STALE_APPROVAL_ENTRY = Duration.ofDays(7);

    private final MeterRegistry registry;
    private final Map<Integer, Instant> waitingApprovalSince = new ConcurrentHashMap<>();

    public JobLifecycleMetrics(MeterRegistry registry,
                               @Qualifier("activeWorkspaceCount") Supplier<Number> activeWorkspaceCount,
                               @Qualifier("jobsAwaitingApprovalCount") Supplier<Number> jobsAwaitingApprovalCount) {
        this.registry = registry;
        Gauge.builder("terrakube.workspace.active", activeWorkspaceCount, s -> s.get().doubleValue())
                .description("Current number of workspaces")
                .register(registry);
        Gauge.builder("terrakube.run.awaiting.approval", jobsAwaitingApprovalCount, s -> s.get().doubleValue())
                .description("Runs currently waiting for approval")
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

        String organization = job.getOrganization() == null
                ? null : String.valueOf(job.getOrganization().getId());
        if (organization == null) {
            return;
        }

        if (status == JobStatus.running) {
            Counter.builder("terrakube.run.started")
                    .tag("via", via(job))
                    .tag("plan_only", planOnly(job))
                    .tag("organization", organization)
                    .description("Runs that have begun executing")
                    .register(registry)
                    .increment();
        }

        if (status == JobStatus.waitingApproval) {
            rememberWaitingApproval(job);
        }
        if (status == JobStatus.approved || status == JobStatus.rejected) {
            recordApprovalWait(job, organization);
        }

        if (status.isTerminal()) {
            Counter.builder("terrakube.run.finished")
                    .tag("outcome", status.name())
                    .tag("via", via(job))
                    .tag("plan_only", planOnly(job))
                    .tag("organization", organization)
                    .description("Runs that have reached a terminal state")
                    .register(registry)
                    .increment();

            Date created = job.getCreatedDate();
            if (created != null) {
                long millis = System.currentTimeMillis() - created.getTime();
                if (millis >= 0) {
                    Timer.builder("terrakube.run.duration")
                            .tag("outcome", status.name())
                            .tag("plan_only", planOnly(job))
                            .tag("organization", organization)
                            .description("Wall-clock time from run creation to terminal state")
                            .register(registry)
                            .record(Duration.ofMillis(millis));
                }
            }
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

    private void rememberWaitingApproval(Job job) {
        if (waitingApprovalSince.size() >= MAX_PENDING_APPROVALS) {
            waitingApprovalSince.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .ifPresent(waitingApprovalSince::remove);
        }
        Instant cutoff = Instant.now().minus(STALE_APPROVAL_ENTRY);
        waitingApprovalSince.values().removeIf(t -> t.isBefore(cutoff));
        waitingApprovalSince.put(job.getId(), Instant.now());
    }

    private void recordApprovalWait(Job job, String organization) {
        Instant since = waitingApprovalSince.remove(job.getId());
        if (since == null) {
            return;
        }
        Duration waited = Duration.between(since, Instant.now());
        if (waited.isNegative()) {
            return;
        }
        Timer.builder("terrakube.run.approval.wait")
                .tag("organization", organization)
                .description("Time a run spent waiting for approval")
                .register(registry)
                .record(waited);
    }

    private static String via(Job job) {
        String via = job.getVia();
        return (via == null || via.isBlank()) ? "unknown" : via;
    }

    private static String planOnly(Job job) {
        return String.valueOf(!job.isPlanChanges());
    }
}
