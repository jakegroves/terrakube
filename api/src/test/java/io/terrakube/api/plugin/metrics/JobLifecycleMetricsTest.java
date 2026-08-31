package io.terrakube.api.plugin.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;

class JobLifecycleMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicInteger awaiting = new AtomicInteger(0);
    private final JobLifecycleMetrics metrics = new JobLifecycleMetrics(registry, () -> 7, awaiting::get);

    private Job job(JobStatus status) {
        Job job = new Job();
        job.setStatus(status);
        Organization organization = new Organization();
        organization.setId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"));
        job.setOrganization(organization);
        job.setCreatedDate(new Date(System.currentTimeMillis() - 5000));
        job.setVia("CLI");
        return job;
    }

    @Test
    void countsJobsEnteringAStatus() {
        metrics.recordStatus(job(JobStatus.running));
        metrics.recordStatus(job(JobStatus.running));
        metrics.recordStatus(job(JobStatus.completed));

        assertThat(registry.get("terrakube.job.transitions").tag("to", "running").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("terrakube.job.transitions").tag("to", "completed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void recordsQueueWaitTimerTaggedByOrganizationWhenEnteringQueue() {
        metrics.recordStatus(job(JobStatus.queue));

        assertThat(registry.get("terrakube.job.queue.wait")
                .tag("organization", "00000000-0000-0000-0000-0000000000a1")
                .timer().count()).isEqualTo(1L);
    }

    @Test
    void doesNotRecordQueueWaitForOtherStatuses() {
        metrics.recordStatus(job(JobStatus.running));

        assertThat(registry.find("terrakube.job.queue.wait").timer()).isNull();
    }

    @Test
    void exposesActiveWorkspaceGauge() {
        assertThat(registry.get("terrakube.workspace.active").gauge().value()).isEqualTo(7.0);
    }

    @Test
    void noMeterCarriesAWorkspaceTag() {
        metrics.recordStatus(job(JobStatus.queue));

        registry.getMeters().forEach(meter ->
                assertThat(meter.getId().getTag("workspace")).as(meter.getId().getName()).isNull());
    }

    @Test
    void toleratesNullStatus() {
        Job job = new Job();
        job.setStatus(null);

        metrics.recordStatus(job);

        assertThat(registry.find("terrakube.job.transitions").counter()).isNull();
    }

    // --- terrakube.run.started / terrakube.run.finished ---

    @Test
    void countsRunStartedWhenEnteringRunning() {
        metrics.recordStatus(job(JobStatus.running));

        assertThat(registry.get("terrakube.run.started")
                .tags("via", "CLI", "organization", "00000000-0000-0000-0000-0000000000a1")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void doesNotCountRunStartedForNonRunningStatuses() {
        metrics.recordStatus(job(JobStatus.queue));
        assertThat(registry.find("terrakube.run.started").counter()).isNull();
    }

    @Test
    void countsRunFinishedForEachTerminalOutcome() {
        metrics.recordStatus(job(JobStatus.completed));
        metrics.recordStatus(job(JobStatus.failed));

        assertThat(registry.get("terrakube.run.finished").tag("outcome", "completed").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("terrakube.run.finished").tag("outcome", "failed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void doesNotCountRunFinishedForNonTerminalStatuses() {
        metrics.recordStatus(job(JobStatus.running));
        assertThat(registry.find("terrakube.run.finished").counter()).isNull();
    }

    @Test
    void countsARunsTerminalOutcomeOnceEvenIfTheTerminalEventRepeats() {
        Job job = job(JobStatus.completed);
        job.setId(5150);

        metrics.recordStatus(job);
        metrics.recordStatus(job);
        metrics.recordStatus(job);

        assertThat(registry.get("terrakube.run.finished").tag("outcome", "completed").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("terrakube.run.duration").timer().count()).isEqualTo(1L);
    }

    @Test
    void countsTerminalOutcomesPerJob() {
        Job first = job(JobStatus.completed);
        first.setId(1);
        Job second = job(JobStatus.completed);
        second.setId(2);

        metrics.recordStatus(first);
        metrics.recordStatus(second);

        assertThat(registry.get("terrakube.run.finished").tag("outcome", "completed").counter().count()).isEqualTo(2.0);
    }

    @Test
    void viaTagFallsBackToUnknownWhenMissing() {
        Job job = job(JobStatus.completed);
        job.setVia(null);
        metrics.recordStatus(job);

        assertThat(registry.get("terrakube.run.finished").tag("via", "unknown").counter().count()).isEqualTo(1.0);
    }

    // --- terrakube.run.duration ---

    @Test
    void recordsRunDurationOnTerminalTransition() {
        metrics.recordStatus(job(JobStatus.completed));

        assertThat(registry.get("terrakube.run.duration")
                .tags("outcome", "completed", "organization", "00000000-0000-0000-0000-0000000000a1")
                .timer().count()).isEqualTo(1L);
        assertThat(registry.get("terrakube.run.duration").timer().totalTime(TimeUnit.SECONDS))
                .isBetween(3.0, 30.0);
    }

    @Test
    void doesNotRecordRunDurationWhenCreatedDateMissing() {
        Job job = job(JobStatus.completed);
        job.setCreatedDate(null);
        metrics.recordStatus(job);

        assertThat(registry.find("terrakube.run.duration").timer()).isNull();
    }

    @Test
    void doesNotRecordRunDurationForNonTerminalStatuses() {
        metrics.recordStatus(job(JobStatus.running));
        assertThat(registry.find("terrakube.run.duration").timer()).isNull();
    }

    // --- terrakube.run.approval.wait / terrakube.run.awaiting.approval ---

    @Test
    void recordsApprovalWaitFromWaitingApprovalToApproved() throws InterruptedException {
        Job job = job(JobStatus.waitingApproval);
        job.setId(4242);
        metrics.recordStatus(job);
        Thread.sleep(10);
        job.setStatus(JobStatus.approved);
        metrics.recordStatus(job);

        assertThat(registry.get("terrakube.run.approval.wait")
                .tag("organization", "00000000-0000-0000-0000-0000000000a1")
                .timer().count()).isEqualTo(1L);
    }

    @Test
    void recordsApprovalWaitOnRejection() {
        Job job = job(JobStatus.waitingApproval);
        job.setId(99);
        metrics.recordStatus(job);
        job.setStatus(JobStatus.rejected);
        metrics.recordStatus(job);

        assertThat(registry.get("terrakube.run.approval.wait").timer().count()).isEqualTo(1L);
    }

    @Test
    void approvalWithNoPriorWaitingApprovalRecordsNothingAndDoesNotThrow() {
        Job job = job(JobStatus.approved);
        job.setId(7);
        metrics.recordStatus(job);

        assertThat(registry.find("terrakube.run.approval.wait").timer()).isNull();
    }

    @Test
    void awaitingApprovalGaugeReadsTheSupplier() {
        awaiting.set(3);
        assertThat(registry.get("terrakube.run.awaiting.approval").gauge().value()).isEqualTo(3.0);
    }
}
