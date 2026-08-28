package io.terrakube.api.plugin.metrics;

import java.util.function.Supplier;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.terrakube.api.repository.WorkspaceRepository;

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
}
