package io.terrakube.api.plugin.metrics;

import java.util.ArrayList;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.terrakube.api.repository.ModuleRepository;
import io.terrakube.api.repository.ProviderRepository;

/**
 * Per-organization registry footprint. Micrometer {@link MultiGauge}s refreshed on a fixed delay
 * from grouped COUNT queries over the small {@code module} / {@code provider} tables. The
 * {@code organization} tag is bounded by {@code MetricsCardinalityConfig}. "Publishes over time"
 * is read on the dashboard as the positive delta of these gauges.
 */
@Component
public class RegistryInventoryMetrics {

    private final ModuleRepository moduleRepository;
    private final ProviderRepository providerRepository;
    private final MultiGauge modules;
    private final MultiGauge providers;

    public RegistryInventoryMetrics(MeterRegistry registry,
                                    ModuleRepository moduleRepository,
                                    ProviderRepository providerRepository) {
        this.moduleRepository = moduleRepository;
        this.providerRepository = providerRepository;
        this.modules = MultiGauge.builder("terrakube.registry.modules")
                .description("Modules registered, per organization").register(registry);
        this.providers = MultiGauge.builder("terrakube.registry.providers")
                .description("Providers registered, per organization").register(registry);
    }

    @Scheduled(fixedDelayString = "${io.terrakube.metrics.registry-inventory-refresh-ms:60000}")
    public void refresh() {
        modules.register(rows(moduleRepository.countByOrganization()), true);
        providers.register(rows(providerRepository.countByOrganization()), true);
    }

    private static List<MultiGauge.Row<?>> rows(List<Object[]> grouped) {
        List<MultiGauge.Row<?>> result = new ArrayList<>();
        for (Object[] r : grouped) {
            if (r.length == 2 && r[0] != null && r[1] instanceof Number n) {
                result.add(MultiGauge.Row.of(Tags.of("organization", r[0].toString()), n.doubleValue()));
            }
        }
        return result;
    }
}
