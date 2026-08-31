package io.terrakube.api.plugin.metrics;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

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
 * <p>The bound is on the number of {@code organization} values seen <em>recently</em> (within
 * {@link #ORGANIZATION_TAG_RETENTION}) per meter name, not for the lifetime of the process: an
 * org that stops appearing - deleted, renamed, or a one-off junk value from a bot probing the
 * registry - ages out and frees its slot, so a burst of distinct values early on can no longer
 * permanently lock real organizations out until a restart.
 */
@Configuration
public class MetricsCardinalityConfig {

    static final Duration ORGANIZATION_TAG_RETENTION = Duration.ofHours(2);

    @Bean
    MeterFilter cardinalityMeterFilter(
            @Value("${io.terrakube.metrics.max-organization-tags:200}") int maxOrganizationTags) {
        return new OrganizationTagCardinalityFilter(maxOrganizationTags, ORGANIZATION_TAG_RETENTION, System::nanoTime);
    }

    /**
     * Package-private and separately constructable so a test can drive it with a fake nano clock.
     */
    static final class OrganizationTagCardinalityFilter implements MeterFilter {

        private final int maxOrganizationTags;
        private final long retentionNanos;
        private final LongSupplier nanoTime;
        // meter name -> (organization tag value -> last-seen nano time)
        private final Map<String, Map<String, Long>> seenPerMeter = new ConcurrentHashMap<>();

        OrganizationTagCardinalityFilter(int maxOrganizationTags, Duration retention, LongSupplier nanoTime) {
            this.maxOrganizationTags = maxOrganizationTags;
            this.retentionNanos = retention.toNanos();
            this.nanoTime = nanoTime;
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

            Map<String, Long> lastSeen = seenPerMeter.computeIfAbsent(id.getName(), k -> new ConcurrentHashMap<>());
            long now = nanoTime.getAsLong();

            if (lastSeen.replace(org, now) != null) {
                return MeterFilterReply.NEUTRAL;
            }

            // New value for this meter: drop entries not seen within the retention window, then
            // enforce the cap. Two threads racing a new value here can overshoot the cap by the
            // number of concurrent registrations - acceptable for a defence-in-depth bound.
            for (Iterator<Long> it = lastSeen.values().iterator(); it.hasNext();) {
                if (now - it.next() > retentionNanos) {
                    it.remove();
                }
            }
            if (lastSeen.size() >= maxOrganizationTags) {
                return MeterFilterReply.DENY;
            }
            lastSeen.put(org, now);
            return MeterFilterReply.NEUTRAL;
        }
    }
}
