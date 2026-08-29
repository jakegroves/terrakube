package io.terrakube.api.plugin.metrics;

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
 */
@Configuration
public class MetricsCardinalityConfig {

    @Bean
    MeterFilter cardinalityMeterFilter(
            @Value("${io.terrakube.metrics.max-organization-tags:200}") int maxOrganizationTags) {

        // maximumAllowableTags is per-meter-name, so track the accepted organization values
        // per meter name and deny a meter once its name has seen more than the cap.
        ConcurrentHashMap<String, Set<String>> seenPerMeter = new ConcurrentHashMap<>();

        return new MeterFilter() {
            @Override
            public MeterFilterReply accept(Meter.Id id) {
                if (id.getTag("workspace") != null) {
                    return MeterFilterReply.DENY;
                }
                String org = id.getTag("organization");
                if (org == null) {
                    return MeterFilterReply.NEUTRAL;
                }
                Set<String> seen = seenPerMeter.computeIfAbsent(id.getName(), k -> ConcurrentHashMap.newKeySet());
                if (seen.contains(org)) {
                    return MeterFilterReply.NEUTRAL;
                }
                if (seen.size() >= maxOrganizationTags) {
                    return MeterFilterReply.DENY;
                }
                seen.add(org);
                return MeterFilterReply.NEUTRAL;
            }
        };
    }
}
