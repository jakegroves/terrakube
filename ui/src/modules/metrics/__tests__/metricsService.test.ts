import { axiosGraphQL } from "@/config/axiosConfig";
import metricsService from "../metricsService";

jest.mock("@/config/axiosConfig", () => ({
  axiosGraphQL: { post: jest.fn() },
}));

const mockPost = axiosGraphQL.post as jest.Mock;

describe("metricsService.queryJobMetrics", () => {
  afterEach(() => {
    mockPost.mockReset();
  });

  it("builds a filter with organizationId and the date range, and requests the given fields", async () => {
    mockPost.mockResolvedValue({ data: { data: { jobMetric: { edges: [] } } } });

    await metricsService.queryJobMetrics({
      organizationId: "org-1",
      from: "2026-07-01",
      to: "2026-08-01",
      dimensions: ["day"],
      metrics: ["runCount"],
    });

    const body = mockPost.mock.calls[0][1];
    expect(body.query).toContain("jobMetric");
    // The filter clause is embedded inside the outer query template's own quotes
    // (`filter: "${filter}"`), so each RSQL value's quotes must appear here as the
    // literal two-character sequence \" (backslash + quote) — a bare " would
    // prematurely close the GraphQL string literal and produce an unparsable query.
    expect(body.query).toContain('organizationId==\\"org-1\\"');
    expect(body.query).toContain('day=ge=\\"2026-07-01\\"');
    expect(body.query).toContain('day=le=\\"2026-08-01\\"');
    expect(body.query).toContain("day");
    expect(body.query).toContain("runCount");
  });

  it("includes workspaceId in the filter when provided", async () => {
    mockPost.mockResolvedValue({ data: { data: { jobMetric: { edges: [] } } } });

    await metricsService.queryJobMetrics({
      organizationId: "org-1",
      workspaceId: "ws-1",
      from: "2026-07-01",
      to: "2026-08-01",
      dimensions: ["day"],
      metrics: ["runCount"],
    });

    const body = mockPost.mock.calls[0][1];
    expect(body.query).toContain('workspaceId==\\"ws-1\\"');
  });

  it("maps edges/node into a flat array", async () => {
    mockPost.mockResolvedValue({
      data: { data: { jobMetric: { edges: [{ node: { day: "2026-07-31", runCount: 3 } }] } } },
    });

    const result = await metricsService.queryJobMetrics({
      organizationId: "org-1",
      from: "2026-07-01",
      to: "2026-08-01",
      dimensions: ["day"],
      metrics: ["runCount"],
    });

    expect(result).toEqual([{ day: "2026-07-31", runCount: 3 }]);
  });

  it("throws with the GraphQL error message on error", async () => {
    mockPost.mockResolvedValue({ data: { errors: [{ message: "boom" }] } });

    await expect(
      metricsService.queryJobMetrics({
        organizationId: "org-1",
        from: "2026-07-01",
        to: "2026-08-01",
        dimensions: ["day"],
        metrics: ["runCount"],
      })
    ).rejects.toThrow("boom");
  });
});
