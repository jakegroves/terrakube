package io.terrakube.api.rs.job.metrics;

import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.datastores.aggregation.annotation.DimensionFormula;
import com.yahoo.elide.datastores.aggregation.annotation.MetricFormula;
import com.yahoo.elide.datastores.aggregation.annotation.Temporal;
import com.yahoo.elide.datastores.aggregation.annotation.TimeGrainDefinition;
import com.yahoo.elide.datastores.aggregation.metadata.enums.TimeGrain;
import com.yahoo.elide.datastores.aggregation.queryengines.sql.annotation.FromTable;
import com.yahoo.elide.datastores.aggregation.timegrains.Time;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

/**
 * Read-only analytic view over the {@code job} table (Elide Aggregation
 * Data Store) — backs organization and workspace metrics. Not a JPA entity;
 * every field's physical column comes from the {@code {{$column}}}
 * reference inside its formula string, independent of the Java field name.
 */
@Include(name = "jobMetric")
@FromTable(name = "job")
@ReadPermission(expression = TeamViewJobMetric.RULE)
@Getter
@Setter
public class JobMetric {

    // Terrakube's aggregation-store dialect is Postgres (application.properties:
    // elide.aggregation-store.default-dialect=Postgres) — truncating a timestamp to
    // its date via a plain CAST is portable Postgres SQL for the DAY grain.
    public static final String DAY_FORMAT = "CAST({{$$column.expr}} AS DATE)";

    @Id
    private String id;

    @DimensionFormula("{{$organization_id}}")
    private String organizationId;

    @DimensionFormula("{{$workspace_id}}")
    private String workspaceId;

    @DimensionFormula("{{$status}}")
    private String status;

    @DimensionFormula("{{$via}}")
    private String via;

    @DimensionFormula("{{$plan_only}}")
    private boolean planOnly;

    @Temporal(grains = { @TimeGrainDefinition(grain = TimeGrain.DAY, expression = DAY_FORMAT) }, timeZone = "UTC")
    @DimensionFormula("{{$created_date}}")
    private Time day;

    @MetricFormula("COUNT(*)")
    private long runCount;

    @MetricFormula("AVG(EXTRACT(EPOCH FROM ({{$started_at}} - {{$queued_at}})))")
    private Double avgQueueWaitSeconds;

    // Historical avgQueueWaitSeconds only covers jobs that already left the queue
    // (started_at is set) — a job still sitting in queue right now contributes
    // nothing to it. This is the live counterpart: how long has each currently-queued
    // job (status='queue') been waiting so far, averaged. Callers scope this by
    // filtering status=="queue" in the GraphQL query, same as the pending-count query.
    @MetricFormula("AVG(EXTRACT(EPOCH FROM (NOW() - {{$queued_at}})))")
    private Double currentQueueWaitSeconds;

    @MetricFormula("AVG(EXTRACT(EPOCH FROM ({{$approved_at}} - {{$waiting_approval_at}})))")
    private Double avgApprovalWaitSeconds;

    @MetricFormula("PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM ({{$finished_at}} - {{$created_date}})))")
    private Double p95DurationSeconds;

    // Elide's in-memory filter/predicate evaluation (e.g. FilterExpressionCheck's
    // default applyPredicateToObject) resolves a field's value by calling a
    // bare-named method matching the field name — not the Lombok-generated
    // JavaBean getX()/isX() the rest of this codebase's JPA-backed models rely on.
    // Discovered via NoSuchMethodException: JobMetric.workspaceId() when
    // TeamViewJobMetric's InPredicate was evaluated against a real request.
    public String id() {
        return id;
    }

    public String organizationId() {
        return organizationId;
    }

    public String workspaceId() {
        return workspaceId;
    }

    public String status() {
        return status;
    }

    public String via() {
        return via;
    }

    public boolean planOnly() {
        return planOnly;
    }

    public Time day() {
        return day;
    }

    public long runCount() {
        return runCount;
    }

    public Double avgQueueWaitSeconds() {
        return avgQueueWaitSeconds;
    }

    public Double currentQueueWaitSeconds() {
        return currentQueueWaitSeconds;
    }

    public Double avgApprovalWaitSeconds() {
        return avgApprovalWaitSeconds;
    }

    public Double p95DurationSeconds() {
        return p95DurationSeconds;
    }
}
