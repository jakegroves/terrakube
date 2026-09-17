package io.terrakube.api.plugin.metrics;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;

/**
 * Defence in depth for metric cardinality. Workspace identity must never become a time-series
 * label; this filter drops any meter that carries a {@code workspace} tag, and bounds the
 * {@code organization} tag on every meter that carries one so a pathological org count cannot
 * explode storage. Consumers who still find {@code organization} too wide can add
 * {@code MeterFilter.ignoreTags("organization")} of their own.
 *
 * <p>The cap is per meter name for the lifetime of the process. Meter filters run at registration,
 * not for each measurement, so retaining admitted values is necessary to put a real upper bound
 * on the exported time series.
 */
@Configuration
public class MetricsCardinalityConfig {

    @Bean
    MeterFilter cardinalityMeterFilter(
            @Value("${io.terrakube.metrics.max-organization-tags:200}") int maxOrganizationTags) {
        return new OrganizationTagCardinalityFilter(maxOrganizationTags);
    }

    /**
     * Package-private and separately constructable so a test can drive it with a fake nano clock.
     */
    static final class OrganizationTagCardinalityFilter implements MeterFilter {

        private final int maxOrganizationTags;
        // Meter filters run only when a meter is registered, not for every measurement. Keep the
        // registered organization values for the process lifetime so the cap is a real bound on
        // exported series rather than a window that can admit unbounded stale series.
        private final Map<String, Set<String>> seenPerMeter = new ConcurrentHashMap<>();

        OrganizationTagCardinalityFilter(int maxOrganizationTags) {
            this.maxOrganizationTags = maxOrganizationTags;
        }

        @Override
        public MeterFilterReply accept(Meter.Id id) {
            if (id.getTag("workspace") != null) {
                return MeterFilterReply.DENY;
            }
            String org = id.getTag("organization");
            if (org == null) {
                return MeterFilterReply.NEUTRAL;
            }

            Set<String> organizations = seenPerMeter.computeIfAbsent(id.getName(), k -> ConcurrentHashMap.newKeySet());
            synchronized (organizations) {
                if (organizations.contains(org)) {
                    return MeterFilterReply.NEUTRAL;
                }
                if (organizations.size() >= maxOrganizationTags) {
                    return MeterFilterReply.DENY;
                }
                organizations.add(org);
            }
            return MeterFilterReply.NEUTRAL;
        }
    }
}
