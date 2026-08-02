import { Card, Col, Row, Spin, Statistic, Typography } from "antd";
import { Column, Line } from "@ant-design/plots";
import { DateTime } from "luxon";
import { useCallback, useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import metricsService from "@/modules/metrics/metricsService";
import queueStatusService from "@/modules/metrics/queueStatusService";
import executorPoolService from "@/modules/metrics/executorPoolService";
import { MetricsTimeRangeSelect, RangeDays } from "@/modules/metrics/components/MetricsTimeRangeSelect";

const EXECUTOR_POOL_POLL_MS = 10_000;

type Params = {
  orgid: string;
};

export const OrganizationMetrics = () => {
  const { orgid } = useParams<Params>();
  const organizationId = orgid!;
  const [rangeDays, setRangeDays] = useState<RangeDays>(3);
  const [loading, setLoading] = useState(true);
  const [queueStatus, setQueueStatus] = useState<{
    pending: number;
    waitingApproval: number;
    currentQueueWaitSeconds: number | null;
  }>({ pending: 0, waitingApproval: 0, currentQueueWaitSeconds: null });
  const [executorPool, setExecutorPool] = useState<{ busy: number; poolSize: number } | null>(null);
  const [queueLatency, setQueueLatency] = useState<{
    avgQueueWaitSeconds: number | null;
    avgApprovalWaitSeconds: number | null;
  }>({
    avgQueueWaitSeconds: null,
    avgApprovalWaitSeconds: null,
  });
  const [digest, setDigest] = useState<{ total: number; successRate: number; planOnlyRate: number }>({
    total: 0,
    successRate: 0,
    planOnlyRate: 0,
  });
  const [dailyRuns, setDailyRuns] = useState<{ day: string; runCount: number }[]>([]);
  const [planOnlyDuration, setPlanOnlyDuration] = useState<{ day: string; p95DurationSeconds: number }[]>([]);
  const [fullRunDuration, setFullRunDuration] = useState<{ day: string; p95DurationSeconds: number }[]>([]);

  const load = useCallback(async () => {
    setLoading(true);
    const to = DateTime.utc().toISODate();
    const from = DateTime.utc().minus({ days: rangeDays }).toISODate();

    const [status, latencyRows] = await Promise.all([
      queueStatusService.getQueueStatusCounts(organizationId),
      metricsService.queryJobMetrics({
        organizationId,
        from: from!,
        to: to!,
        dimensions: [],
        metrics: ["avgQueueWaitSeconds", "avgApprovalWaitSeconds"],
      }),
    ]);

    setQueueStatus(status);
    const latency = latencyRows[0];
    setQueueLatency({
      avgQueueWaitSeconds: latency?.avgQueueWaitSeconds == null ? null : Number(latency.avgQueueWaitSeconds),
      avgApprovalWaitSeconds: latency?.avgApprovalWaitSeconds == null ? null : Number(latency.avgApprovalWaitSeconds),
    });

    const [statusRows, planOnlyRows, dailyRows, durationRows] = await Promise.all([
      metricsService.queryJobMetrics({
        organizationId,
        from: from!,
        to: to!,
        dimensions: ["status"],
        metrics: ["runCount"],
      }),
      metricsService.queryJobMetrics({
        organizationId,
        from: from!,
        to: to!,
        dimensions: ["planOnly"],
        metrics: ["runCount"],
      }),
      metricsService.queryJobMetrics({
        organizationId,
        from: from!,
        to: to!,
        dimensions: ["day"],
        metrics: ["runCount"],
      }),
      metricsService.queryJobMetrics({
        organizationId,
        from: from!,
        to: to!,
        dimensions: ["day", "planOnly"],
        metrics: ["p95DurationSeconds"],
      }),
    ]);

    const total = statusRows.reduce((sum, row) => sum + Number(row.runCount ?? 0), 0);
    const successCount = statusRows
      .filter((row) => row.status === "completed" || row.status === "noChanges")
      .reduce((sum, row) => sum + Number(row.runCount ?? 0), 0);
    const planOnlyCount = planOnlyRows
      .filter((row) => row.planOnly === true)
      .reduce((sum, row) => sum + Number(row.runCount ?? 0), 0);

    setDigest({
      total,
      successRate: total > 0 ? Math.round((successCount / total) * 100) : 0,
      planOnlyRate: total > 0 ? Math.round((planOnlyCount / total) * 100) : 0,
    });
    setDailyRuns(dailyRows.map((row) => ({ day: String(row.day ?? ""), runCount: Number(row.runCount ?? 0) })));
    setPlanOnlyDuration(
      durationRows
        .filter((row) => row.planOnly === true)
        .map((row) => ({ day: String(row.day ?? ""), p95DurationSeconds: Number(row.p95DurationSeconds ?? 0) }))
    );
    setFullRunDuration(
      durationRows
        .filter((row) => row.planOnly === false)
        .map((row) => ({ day: String(row.day ?? ""), p95DurationSeconds: Number(row.p95DurationSeconds ?? 0) }))
    );

    setLoading(false);
  }, [organizationId, rangeDays]);

  useEffect(() => {
    load();
  }, [load]);

  // Live, unscoped by the selected time range — this is "right now", not a trend.
  useEffect(() => {
    let cancelled = false;
    const poll = () => {
      executorPoolService.getStatus().then((status) => {
        if (!cancelled) {
          setExecutorPool(status);
        }
      });
    };
    poll();
    const interval = setInterval(poll, EXECUTOR_POOL_POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, []);

  return (
    <div style={{ padding: 24 }}>
      <Row justify="end" style={{ marginBottom: 16 }}>
        <MetricsTimeRangeSelect value={rangeDays} onChange={setRangeDays} />
      </Row>
      <Spin spinning={loading}>
        <Row gutter={16}>
          <Col span={8}>
            <Card title="Queue status">
              <Row gutter={16}>
                <Col span={8}>
                  <Statistic title="Pending runs" value={queueStatus.pending} />
                </Col>
                <Col span={8}>
                  {queueStatus.currentQueueWaitSeconds == null ? (
                    <Statistic title="Current wait" valueRender={() => <Typography.Text type="secondary">—</Typography.Text>} />
                  ) : (
                    <Statistic title="Current wait" value={queueStatus.currentQueueWaitSeconds} suffix="s" precision={1} />
                  )}
                </Col>
                <Col span={8}>
                  <Statistic title="Waiting for confirmation" value={queueStatus.waitingApproval} />
                </Col>
              </Row>
            </Card>
          </Col>
          <Col span={8}>
            <Card title="Queue latency">
              <Row gutter={16}>
                <Col span={12}>
                  {queueLatency.avgQueueWaitSeconds == null ? (
                    <Statistic title="Avg queue wait" valueRender={() => <Typography.Text type="secondary">No data yet</Typography.Text>} />
                  ) : (
                    <Statistic title="Avg queue wait" value={queueLatency.avgQueueWaitSeconds} suffix="s" precision={1} />
                  )}
                </Col>
                <Col span={12}>
                  {queueLatency.avgApprovalWaitSeconds == null ? (
                    <Statistic
                      title="Avg approval wait"
                      valueRender={() => <Typography.Text type="secondary">No data yet</Typography.Text>}
                    />
                  ) : (
                    <Statistic title="Avg approval wait" value={queueLatency.avgApprovalWaitSeconds} suffix="s" precision={1} />
                  )}
                </Col>
              </Row>
            </Card>
          </Col>
          <Col span={8}>
            <Card title="Executor pool">
              {executorPool == null ? (
                <Typography.Text type="secondary">Loading...</Typography.Text>
              ) : (
                <Statistic title="Busy" value={executorPool.busy} suffix={`/ ${executorPool.poolSize}`} />
              )}
            </Card>
          </Col>
          <Col span={24} style={{ marginTop: 16 }}>
            <Card title={`Runs digest (total: ${digest.total})`}>
              <Row gutter={16}>
                <Col span={8}>
                  <Statistic title="Success rate" value={digest.successRate} suffix="%" />
                </Col>
                <Col span={8}>
                  <Statistic title="Plan-only rate" value={digest.planOnlyRate} suffix="%" />
                </Col>
              </Row>
            </Card>
          </Col>
          <Col span={24} style={{ marginTop: 16 }}>
            <Card title="Daily runs">
              <Column data={dailyRuns} xField="day" yField="runCount" />
            </Card>
          </Col>
          <Col span={12} style={{ marginTop: 16 }}>
            <Card title="Plan-only duration">
              <Line data={planOnlyDuration} xField="day" yField="p95DurationSeconds" />
            </Card>
          </Col>
          <Col span={12} style={{ marginTop: 16 }}>
            <Card title="Full run duration">
              <Line data={fullRunDuration} xField="day" yField="p95DurationSeconds" />
            </Card>
          </Col>
        </Row>
      </Spin>
    </div>
  );
};
