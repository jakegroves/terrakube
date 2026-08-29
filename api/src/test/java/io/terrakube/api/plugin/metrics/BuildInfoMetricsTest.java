package io.terrakube.api.plugin.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class BuildInfoMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = Mockito.mock(ObjectProvider.class);
        Mockito.when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    @Test
    void registersBuildInfoGaugeWithVersionAndCommit() {
        Properties bp = new Properties();
        bp.setProperty("version", "1.2.3");
        bp.setProperty("artifact", "svc");
        Properties gp = new Properties();
        gp.setProperty("commit.id.abbrev", "abc1234");

        new BuildInfoMetrics(registry, provider(new BuildProperties(bp)), provider(new GitProperties(gp)));

        assertThat(registry.get("terrakube.build.info")
                .tags("service", "terrakube-api", "version", "1.2.3", "commit", "abc1234")
                .gauge().value()).isEqualTo(1.0);
    }

    @Test
    void fallsBackToUnknownWhenNoBuildOrGitProperties() {
        new BuildInfoMetrics(registry, provider(null), provider(null));

        assertThat(registry.get("terrakube.build.info")
                .tags("service", "terrakube-api", "version", "unknown", "commit", "unknown")
                .gauge().value()).isEqualTo(1.0);
    }
}
