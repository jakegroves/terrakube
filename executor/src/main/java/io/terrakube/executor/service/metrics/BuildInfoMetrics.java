package io.terrakube.executor.service.metrics;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/** {@code terrakube.build.info} - constant 1 gauge carrying version / commit for the running build. */
@Component
class BuildInfoMetrics {

    BuildInfoMetrics(MeterRegistry registry,
                     ObjectProvider<BuildProperties> build,
                     ObjectProvider<GitProperties> git) {
        BuildProperties b = build.getIfAvailable();
        GitProperties g = git.getIfAvailable();
        Gauge.builder("terrakube.build.info", () -> 1d)
                .tag("service", "terrakube-executor")
                .tag("version", b != null && b.getVersion() != null ? b.getVersion() : "unknown")
                .tag("commit", g != null && g.getShortCommitId() != null ? g.getShortCommitId() : "unknown")
                .description("Build info; value is always 1")
                .register(registry);
    }
}
