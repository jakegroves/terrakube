import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { OrganizationMetrics } from "../OrganizationMetrics";
import metricsService from "@/modules/metrics/metricsService";
import queueStatusService from "@/modules/metrics/queueStatusService";
import executorPoolService from "@/modules/metrics/executorPoolService";

jest.mock("@/modules/metrics/metricsService");
jest.mock("@/modules/metrics/queueStatusService");
jest.mock("@/modules/metrics/executorPoolService");

// @ant-design/plots (G2/antv canvas rendering) is genuinely slow to initialize the
// first time it mounts in a Jest run, even with jest-canvas-mock — well past the
// default 5000ms per-test timeout on a cold run.
jest.setTimeout(20000);

const mockQueryJobMetrics = metricsService.queryJobMetrics as jest.Mock;
const mockGetQueueStatusCounts = queueStatusService.getQueueStatusCounts as jest.Mock;
const mockGetExecutorPoolStatus = executorPoolService.getStatus as jest.Mock;

describe("OrganizationMetrics", () => {
  beforeEach(() => {
    mockQueryJobMetrics.mockReset();
    mockGetQueueStatusCounts.mockReset();
    mockGetExecutorPoolStatus.mockReset();
    mockQueryJobMetrics.mockResolvedValue([]);
    mockGetQueueStatusCounts.mockResolvedValue({ pending: 3, waitingApproval: 1 });
    mockGetExecutorPoolStatus.mockResolvedValue({ busy: 2, poolSize: 5 });
  });

  afterEach(() => {
    jest.clearAllTimers();
  });

  it("renders queue status counts once loaded", async () => {
    render(
      <MemoryRouter initialEntries={["/organizations/org-1/metrics"]}>
        <Routes>
          <Route path="/organizations/:orgid/metrics" element={<OrganizationMetrics />} />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => expect(screen.getByText("3")).toBeInTheDocument());
    expect(screen.getByText("1")).toBeInTheDocument();
  });

  it("renders the executor pool busy/poolSize once loaded", async () => {
    render(
      <MemoryRouter initialEntries={["/organizations/org-1/metrics"]}>
        <Routes>
          <Route path="/organizations/:orgid/metrics" element={<OrganizationMetrics />} />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => expect(screen.getByText("2")).toBeInTheDocument());
    expect(screen.getByText("/ 5")).toBeInTheDocument();
  });

  it("shows 'No data yet' for queue latency when there are no rows", async () => {
    render(
      <MemoryRouter initialEntries={["/organizations/org-1/metrics"]}>
        <Routes>
          <Route path="/organizations/:orgid/metrics" element={<OrganizationMetrics />} />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => expect(screen.getAllByText("No data yet")).toHaveLength(2));
  });

  it("re-fetches avg queue wait when the time range changes", async () => {
    mockQueryJobMetrics.mockResolvedValue([{ avgQueueWaitSeconds: 12, avgApprovalWaitSeconds: 5 }]);

    render(
      <MemoryRouter initialEntries={["/organizations/org-1/metrics"]}>
        <Routes>
          <Route path="/organizations/:orgid/metrics" element={<OrganizationMetrics />} />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => expect(mockQueryJobMetrics).toHaveBeenCalled());
    const firstCallCount = mockQueryJobMetrics.mock.calls.length;

    const select = screen.getAllByRole("combobox")[0];
    fireEvent.mouseDown(select);
    const option = await screen.findByText("Last 7 days");
    fireEvent.click(option);

    await waitFor(() => expect(mockQueryJobMetrics.mock.calls.length).toBeGreaterThan(firstCallCount));
  });

  it("renders total run count and success rate computed from status-grouped rows", async () => {
    mockQueryJobMetrics.mockImplementation(({ dimensions }: { dimensions: string[] }) => {
      if (dimensions.includes("status")) {
        return Promise.resolve([
          { status: "completed", runCount: 8 },
          { status: "failed", runCount: 2 },
        ]);
      }
      return Promise.resolve([]);
    });

    render(
      <MemoryRouter initialEntries={["/organizations/org-1/metrics"]}>
        <Routes>
          <Route path="/organizations/:orgid/metrics" element={<OrganizationMetrics />} />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => expect(screen.getByText("Runs digest (total: 10)")).toBeInTheDocument());
    expect(screen.getByText("80")).toBeInTheDocument(); // success rate value (suffix "%" renders in a separate span)
  });

  it("splits duration rows into plan-only and full-run series", async () => {
    mockQueryJobMetrics.mockImplementation(({ dimensions }: { dimensions: string[] }) => {
      if (dimensions.includes("day") && dimensions.includes("planOnly")) {
        return Promise.resolve([
          { day: "2026-07-30", planOnly: true, p95DurationSeconds: 5 },
          { day: "2026-07-30", planOnly: false, p95DurationSeconds: 40 },
        ]);
      }
      return Promise.resolve([]);
    });

    render(
      <MemoryRouter initialEntries={["/organizations/org-1/metrics"]}>
        <Routes>
          <Route path="/organizations/:orgid/metrics" element={<OrganizationMetrics />} />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => expect(screen.getByText("Plan-only duration")).toBeInTheDocument());
    expect(screen.getByText("Full run duration")).toBeInTheDocument();
  });
});
