package io.terrakube.api.plugin.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Date;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;

class JobLifecycleMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final JobLifecycleMetrics metrics = new JobLifecycleMetrics(registry, () -> 7);

    private Job job(JobStatus status) {
        Job job = new Job();
        job.setStatus(status);
        Organization organization = new Organization();
        organization.setId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"));
        job.setOrganization(organization);
        job.setCreatedDate(new Date(System.currentTimeMillis() - 5000));
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
}
