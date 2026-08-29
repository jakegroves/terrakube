package io.terrakube.api.plugin.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.prometheus.metrics.tracer.common.SpanContext;

class OtelExemplarConfigTest {

    private final SpanContext spanContext = new OtelExemplarConfig().prometheusExemplarSpanContext();

    @Test
    void beanIsProvided() {
        assertThat(spanContext).isNotNull();
    }

    @Test
    void returnsNoTraceContextWhenNoActiveSpan() {
        assertThat(spanContext.getCurrentTraceId()).isNull();
        assertThat(spanContext.getCurrentSpanId()).isNull();
        assertThat(spanContext.isCurrentSpanSampled()).isFalse();
    }

    @Test
    void markCurrentSpanAsExemplarDoesNotThrowWithoutASpan() {
        spanContext.markCurrentSpanAsExemplar();
    }
}
