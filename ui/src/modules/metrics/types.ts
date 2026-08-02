export type MetricDimension = "organizationId" | "workspaceId" | "status" | "via" | "planOnly" | "day";

export type MetricName = "runCount" | "avgQueueWaitSeconds" | "avgApprovalWaitSeconds" | "p95DurationSeconds";

export type JobMetricNode = Partial<Record<MetricDimension | MetricName, string | number | boolean>>;

export type QueryJobMetricsParams = {
  organizationId: string;
  workspaceId?: string;
  from: string;
  to: string;
  dimensions: MetricDimension[];
  metrics: MetricName[];
};
