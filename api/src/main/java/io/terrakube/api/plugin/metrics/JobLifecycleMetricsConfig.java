package io.terrakube.api.plugin.metrics;

import java.util.function.Supplier;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.JobStatus;

@Configuration
class JobLifecycleMetricsConfig {

    /**
     * Supplies the {@code terrakube.workspace.active} gauge value. {@code count()} is a cheap
     * {@code SELECT count(*)} and is only invoked when a scrape reads the gauge.
     */
    @Bean
    Supplier<Number> activeWorkspaceCount(WorkspaceRepository workspaceRepository) {
        return workspaceRepository::count;
    }

    /**
     * Supplies the {@code terrakube.run.awaiting.approval} gauge value - runs currently sitting in
     * {@code waitingApproval}. Evaluated only on scrape.
     */
    @Bean
    Supplier<Number> jobsAwaitingApprovalCount(JobRepository jobRepository) {
        return () -> jobRepository.countByStatusAndDeletedFalse(JobStatus.waitingApproval);
    }
}
