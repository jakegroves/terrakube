package io.terrakube.registry.metrics;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.opentelemetry.api.trace.Span;
import io.prometheus.metrics.tracer.common.SpanContext;

/**
 * Feeds trace exemplars into the Prometheus histograms. Backed by the OpenTelemetry agent's
 * current span via {@code io.opentelemetry.api} - no {@code micrometer-tracing} dependency, no
 * extra spans. When the agent is absent (plain unit tests) {@code Span.current()} is a no-op
 * span and every method degrades to null/false.
 */
@Configuration
class OtelExemplarConfig {

    @Bean
    SpanContext prometheusExemplarSpanContext() {
        return new SpanContext() {
            @Override
            public String getCurrentTraceId() {
                io.opentelemetry.api.trace.SpanContext sc = Span.current().getSpanContext();
                return sc.isValid() ? sc.getTraceId() : null;
            }

            @Override
            public String getCurrentSpanId() {
                io.opentelemetry.api.trace.SpanContext sc = Span.current().getSpanContext();
                return sc.isValid() ? sc.getSpanId() : null;
            }

            @Override
            public boolean isCurrentSpanSampled() {
                return Span.current().getSpanContext().isSampled();
            }

            @Override
            public void markCurrentSpanAsExemplar() {
                Span.current().setAttribute(SpanContext.EXEMPLAR_ATTRIBUTE_NAME,
                        SpanContext.EXEMPLAR_ATTRIBUTE_VALUE);
            }
        };
    }
}
