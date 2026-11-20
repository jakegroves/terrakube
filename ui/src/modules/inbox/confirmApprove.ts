import { Modal, message } from "antd";
import { approveRuns, findWaitingRuns } from "@/modules/projects/bulkWorkspaceActions";
import { InboxItem } from "./inboxModel";

/** Asks before approving the run waiting on a workspace, then approves it. `onDone` runs either way, to reload. */
export function confirmApprove(item: InboxItem, onDone: () => void) {
  Modal.confirm({
    title: `Approve the run waiting on ${item.name}?`,
    content: `${item.orgName}${item.projectName ? ` / ${item.projectName}` : ""}. Approving lets the run go ahead.`,
    okText: "Approve",
    onOk: async () => {
      try {
        const runs = await findWaitingRuns(item.orgId, [item.workspaceId]);
        if (runs.length === 0) {
          message.info("That run is no longer waiting for approval.");
        } else {
          const outcome = await approveRuns(
            item.orgId,
            runs.map((run) => run.jobId)
          );
          if (outcome.failed.length) message.error(outcome.failed[0].reason);
          else message.success(`Approved the run on ${item.name}`);
        }
      } catch (err: any) {
        message.error(err?.message ?? "The run could not be approved");
      }
      onDone();
    },
  });
}

/**
 * Asks before approving the runs waiting on several workspaces, then approves them, one organization at a time.
 * A run that is no longer waiting is skipped and counted, not treated as a failure. `onDone` runs either way.
 */
export function confirmApproveMany(items: InboxItem[], onDone: () => void) {
  const count = items.length;
  Modal.confirm({
    title: `Approve the runs waiting on ${count} workspace${count === 1 ? "" : "s"}?`,
    content: "Approving lets each run go ahead. Runs that are no longer waiting are left alone.",
    okText: `Approve ${count}`,
    onOk: async () => {
      const byOrganization = new Map<string, string[]>();
      items.forEach((item) =>
        byOrganization.set(item.orgId, [...(byOrganization.get(item.orgId) ?? []), item.workspaceId])
      );
      let approved = 0;
      let skipped = 0;
      const failures: string[] = [];
      for (const [orgId, workspaceIds] of byOrganization) {
        try {
          const runs = await findWaitingRuns(orgId, workspaceIds);
          skipped += workspaceIds.length - new Set(runs.map((run) => run.workspaceId)).size;
          if (runs.length === 0) continue;
          const outcome = await approveRuns(
            orgId,
            runs.map((run) => run.jobId)
          );
          approved += outcome.succeeded.length;
          failures.push(...outcome.failed.map((failure) => failure.reason));
        } catch (err: any) {
          failures.push(err?.message ?? "The runs could not be approved");
        }
      }
      const parts = [`Approved ${approved}`];
      if (skipped > 0) parts.push(`${skipped} no longer waiting`);
      if (failures.length > 0) parts.push(`${failures.length} failed: ${failures[0]}`);
      if (failures.length > 0) message.warning(parts.join(", "));
      else message.success(parts.join(", "));
      onDone();
    },
  });
}
