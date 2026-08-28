package io.terrakube.executor.service.metrics;

import org.springframework.stereotype.Component;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.terrakube.executor.service.mode.TerraformJob;

/**
 * Wraps job execution in an OpenTelemetry span carrying the identifiers deliberately kept OUT of
 * metrics for cardinality reasons - workspace above all. At runtime the OpenTelemetry Java agent
 * supplies the SDK; {@link GlobalOpenTelemetry#get()} resolves to it (or to a no-op when the agent
 * is absent, e.g. local runs without the buildpack).
 */
@Component
public class JobTelemetry {

    private final Tracer tracer;

    public JobTelemetry() {
        this(GlobalOpenTelemetry.get());
    }

    public JobTelemetry(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("io.terrakube.executor");
    }

    public void aroundJob(TerraformJob job, Runnable work) {
        Span span = tracer.spanBuilder("terrakube.job")
                .setAttribute("organization.id", String.valueOf(job.getOrganizationId()))
                .setAttribute("workspace.id", String.valueOf(job.getWorkspaceId()))
                .setAttribute("job.id", String.valueOf(job.getJobId()))
                .setAttribute("job.type", String.valueOf(job.getType()))
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            work.run();
        } catch (RuntimeException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            throw e;
        } finally {
            span.end();
        }
    }
}
