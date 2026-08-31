package io.terrakube.executor.service.metrics;

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
 * Defence in depth for metric cardinality (mirror of the {@code api} config). Drops any meter that
 * carries a {@code workspace} tag, and bounds the {@code organization} tag on every meter that
 * carries one.
 *
 * <p>The bound is on the number of {@code organization} values seen <em>recently</em> (within
 * {@link #ORGANIZATION_TAG_RETENTION}) per meter name, not for the lifetime of the process, so a
 * burst of distinct values - deleted orgs, or junk path segments from a bot probing the registry -
 * ages out and frees its slot instead of permanently locking real organizations out until a restart.
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
