import { axiosGraphQL } from "@/config/axiosConfig";
import queueStatusService from "../queueStatusService";

jest.mock("@/config/axiosConfig", () => ({
  axiosGraphQL: { post: jest.fn() },
}));

const mockPost = axiosGraphQL.post as jest.Mock;

describe("queueStatusService.getQueueStatusCounts", () => {
  it("reads pending, waitingApproval, and currentQueueWaitSeconds from the response", async () => {
    mockPost.mockResolvedValue({
      data: {
        data: {
          pending: { edges: [{ node: { runCount: 2, currentQueueWaitSeconds: 45.5 } }] },
          waitingApproval: { edges: [{ node: { runCount: 1 } }] },
        },
      },
    });

    const result = await queueStatusService.getQueueStatusCounts("org-1");

    expect(result).toEqual({ pending: 2, waitingApproval: 1, currentQueueWaitSeconds: 45.5 });
  });

  it("defaults to 0/null when a query returns no rows", async () => {
    mockPost.mockResolvedValue({
      data: { data: { pending: { edges: [] }, waitingApproval: { edges: [] } } },
    });

    const result = await queueStatusService.getQueueStatusCounts("org-1");

    expect(result).toEqual({ pending: 0, waitingApproval: 0, currentQueueWaitSeconds: null });
  });

  it("queries jobMetric (job itself isn't root-level queryable) filtered by organizationId and status", async () => {
    mockPost.mockResolvedValue({
      data: { data: { pending: { edges: [] }, waitingApproval: { edges: [] } } },
    });

    await queueStatusService.getQueueStatusCounts("org-1");

    const body = mockPost.mock.calls[0][1];
    expect(body.query).toContain("jobMetric");
    expect(body.query).toContain('organizationId==\\"org-1\\"');
    expect(body.query).toContain('status==\\"queue\\"');
    expect(body.query).toContain('status==\\"waitingApproval\\"');
    expect(body.query).toContain("currentQueueWaitSeconds");
  });
});
