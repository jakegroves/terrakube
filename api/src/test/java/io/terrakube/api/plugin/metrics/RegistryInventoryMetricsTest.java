package io.terrakube.api.plugin.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.terrakube.api.repository.ModuleRepository;
import io.terrakube.api.repository.ProviderRepository;

class RegistryInventoryMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ModuleRepository moduleRepository = mock(ModuleRepository.class);
    private final ProviderRepository providerRepository = mock(ProviderRepository.class);
    private final RegistryInventoryMetrics metrics =
            new RegistryInventoryMetrics(registry, moduleRepository, providerRepository);

    @Test
    void registersOneModuleGaugeSeriesPerOrganization() {
        UUID a = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        UUID b = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
        when(moduleRepository.countByOrganization()).thenReturn(List.<Object[]>of(
                new Object[] {a, 3L}, new Object[] {b, 1L}));
        when(providerRepository.countByOrganization()).thenReturn(List.<Object[]>of(new Object[] {a, 2L}));

        metrics.refresh();

        assertThat(registry.get("terrakube.registry.modules").tag("organization", a.toString()).gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("terrakube.registry.modules").tag("organization", b.toString()).gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("terrakube.registry.providers").tag("organization", a.toString()).gauge().value()).isEqualTo(2.0);
    }

    @Test
    void secondRefreshUpdatesValues() {
        UUID a = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        when(moduleRepository.countByOrganization()).thenReturn(List.<Object[]>of(new Object[] {a, 3L}));
        when(providerRepository.countByOrganization()).thenReturn(List.<Object[]>of());
        metrics.refresh();

        when(moduleRepository.countByOrganization()).thenReturn(List.<Object[]>of(new Object[] {a, 5L}));
        metrics.refresh();

        assertThat(registry.get("terrakube.registry.modules").tag("organization", a.toString()).gauge().value()).isEqualTo(5.0);
    }
}
