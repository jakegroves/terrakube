package io.terrakube.api.rs.job;

import io.terrakube.api.plugin.scheduler.job.tcl.executor.persistent.PersistentExecutorQueueService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Set;

/**
 * Shared bookkeeping for every place a Job's status changes: timing columns
 * (queued_at/started_at/finished_at/etc.) and releasing a held persistent
 * executor slot. Mutates the job in-memory and releases any held slot — it
 * does not save the job itself, so callers keep their own existing
 * {@code jobRepository.save(job)} call, just preceded by a call here.
 *
 * <p>{@link io.terrakube.api.rs.hooks.job.JobManageHook} only fires for status
 * changes that go through Elide's own GraphQL/REST request pipeline. Most of
 * this codebase's actual job-lifecycle transitions (queue, completed, failed,
 * etc., in {@code ScheduleJob} and {@code RemoteTfeService}) are plain
 * {@code jobRepository.save(job)} calls from internal scheduler/state-backend
 * code, which never trigger Elide's lifecycle hooks at all — a plain JPA
 * repository save is invisible to Elide's own dictionary/transaction
 * machinery. Every status-changing save, wherever it happens, must call
 * {@link #applyBookkeeping(Job)} first, or its timing/slot-release
 * bookkeeping silently never happens.
 */
@Service
@AllArgsConstructor
public class JobStatusTransitionService {

    private static final Set<JobStatus> TERMINAL_STATUSES = Set.of(
            JobStatus.completed, JobStatus.noChanges, JobStatus.notExecuted,
            JobStatus.rejected, JobStatus.cancelled, JobStatus.failed, JobStatus.unknown);

    private final PersistentExecutorQueueService persistentExecutorQueueService;

    public void applyBookkeeping(Job job) {
        updateStatusTimestamp(job);
        // Harmless no-op for jobs that never held a slot (e.g. agent-routed, or a
        // status update that isn't a slot-holding transition) — safe to call
        // unconditionally, mirroring how PersistentExecutorService.send() is the
        // only place that ever acquires one.
        persistentExecutorQueueService.releaseSlot(job);
    }

    private void updateStatusTimestamp(Job job) {
        Date now = new Date(System.currentTimeMillis());
        switch (job.getStatus()) {
            case queue:
                if (job.getQueuedAt() == null) {
                    job.setQueuedAt(now);
                }
                break;
            case waitingApproval:
                if (job.getWaitingApprovalAt() == null) {
                    job.setWaitingApprovalAt(now);
                }
                break;
            case approved:
                if (job.getApprovedAt() == null) {
                    job.setApprovedAt(now);
                }
                break;
            case running:
                if (job.getStartedAt() == null) {
                    job.setStartedAt(now);
                }
                break;
            default:
                if (TERMINAL_STATUSES.contains(job.getStatus()) && job.getFinishedAt() == null) {
                    job.setFinishedAt(now);
                }
                break;
        }
    }
}
