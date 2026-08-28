package io.terrakube.api.plugin.metrics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;

/**
 * Defence in depth for metric cardinality. Workspace identity must never become a time-series
 * label; this filter drops any meter that carries a {@code workspace} tag, and bounds the
 * {@code organization} tag on the queue-wait timer so a pathological org count cannot explode
 * storage. Consumers who still find {@code organization} too wide can add
 * {@code MeterFilter.ignoreTags("organization")} of their own.
 */
@Configuration
public class MetricsCardinalityConfig {

    @Bean
    MeterFilter cardinalityMeterFilter(
            @Value("${io.terrakube.metrics.max-organization-tags:200}") int maxOrganizationTags) {
        MeterFilter organizationCap = MeterFilter.maximumAllowableTags(
                "terrakube.job.queue.wait", "organization", maxOrganizationTags, MeterFilter.deny());

        return new MeterFilter() {
            @Override
            public MeterFilterReply accept(Meter.Id id) {
                if (id.getTag("workspace") != null) {
                    return MeterFilterReply.DENY;
                }
                return organizationCap.accept(id);
            }

            @Override
            public Meter.Id map(Meter.Id id) {
                return organizationCap.map(id);
            }
        };
    }
}
