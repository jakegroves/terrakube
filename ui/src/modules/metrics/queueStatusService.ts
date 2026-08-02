import { axiosGraphQL } from "@/config/axiosConfig";

// The transactional `job` type isn't queryable at the GraphQL root (Job.java is
// `@Include(rootLevel = false)` — only reachable nested under workspace/organization).
// `jobMetric` (the Elide Aggregation Data Store view) *is* root-level and already
// exposes `status`, so it's used here too, with runCount and no day-range filter —
// this panel is a live snapshot, not scoped to the selected metrics time range.
async function getQueueStatusCounts(
  organizationId: string
): Promise<{ pending: number; waitingApproval: number; currentQueueWaitSeconds: number | null }> {
  const body = {
    query: `{
      pending: jobMetric(filter: "organizationId==\\"${organizationId}\\";status==\\"queue\\"") {
        edges { node { runCount currentQueueWaitSeconds } }
      }
      waitingApproval: jobMetric(filter: "organizationId==\\"${organizationId}\\";status==\\"waitingApproval\\"") {
        edges { node { runCount } }
      }
    }`,
  };

  const response = await axiosGraphQL.post("", body, {
    headers: { "Content-Type": "application/json" },
  });

  if (response.data?.errors?.length) {
    throw new Error(response.data.errors[0].message || "Failed to load queue status");
  }

  const data = response.data?.data;
  const pendingNode = data?.pending?.edges?.[0]?.node;
  return {
    pending: Number(pendingNode?.runCount ?? 0),
    waitingApproval: Number(data?.waitingApproval?.edges?.[0]?.node?.runCount ?? 0),
    currentQueueWaitSeconds: pendingNode?.currentQueueWaitSeconds == null ? null : Number(pendingNode.currentQueueWaitSeconds),
  };
}

const methods = { getQueueStatusCounts };

export default methods;
