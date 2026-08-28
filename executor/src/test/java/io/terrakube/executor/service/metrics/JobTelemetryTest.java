package io.terrakube.executor.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.terrakube.executor.service.mode.TerraformJob;

class JobTelemetryTest {

    @RegisterExtension
    static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    private final JobTelemetry telemetry = new JobTelemetry(otel.getOpenTelemetry());

    private TerraformJob job() {
        TerraformJob job = new TerraformJob();
        job.setOrganizationId("org-1");
        job.setWorkspaceId("ws-9");
        job.setJobId("42");
        job.setType("terraformPlan");
        return job;
    }

    @Test
    void opensSpanWithJobAttributes() {
        telemetry.aroundJob(job(), () -> { });

        assertThat(otel.getSpans()).singleElement().satisfies(span -> {
            assertThat(span.getName()).isEqualTo("terrakube.job");
            String attributes = span.getAttributes().toString();
            assertThat(attributes).contains("org-1").contains("ws-9").contains("42").contains("terraformPlan");
        });
    }

    @Test
    void recordsExceptionAndStillEndsSpan() {
        assertThatThrownBy(() -> telemetry.aroundJob(job(), () -> {
            throw new RuntimeException("boom");
        })).isInstanceOf(RuntimeException.class);

        assertThat(otel.getSpans()).singleElement().satisfies(span ->
                assertThat(span.getEvents()).anySatisfy(event ->
                        assertThat(event.getName()).isEqualTo("exception")));
    }
}
