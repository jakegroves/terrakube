import { axiosGraphQL } from "@/config/axiosConfig";
import { JobMetricNode, QueryJobMetricsParams } from "./types";

function buildFilter({ organizationId, workspaceId, from, to }: QueryJobMetricsParams): string {
  // These clauses are embedded inside the outer query template's own "${filter}"
  // quotes (see queryJobMetrics below), so each RSQL value's quotes must be escaped
  // as literal \" — a bare " here would prematurely close the outer GraphQL string.
  const clauses = [`organizationId==\\"${organizationId}\\"`, `day=ge=\\"${from}\\"`, `day=le=\\"${to}\\"`];
  if (workspaceId) {
    clauses.push(`workspaceId==\\"${workspaceId}\\"`);
  }
  return clauses.join(";");
}

async function queryJobMetrics(params: QueryJobMetricsParams): Promise<JobMetricNode[]> {
  const fields = [...params.dimensions, ...params.metrics].join("\n            ");
  const filter = buildFilter(params);

  const body = {
    query: `{
      jobMetric(filter: "${filter}") {
        edges {
          node {
            ${fields}
          }
        }
      }
    }`,
  };

  const response = await axiosGraphQL.post("", body, {
    headers: { "Content-Type": "application/json" },
  });

  if (response.data?.errors?.length) {
    throw new Error(response.data.errors[0].message || "Failed to load metrics");
  }

  const edges = response.data?.data?.jobMetric?.edges ?? [];
  return edges.map((edge: { node: JobMetricNode }) => edge.node);
}

const methods = {
  queryJobMetrics,
};

export default methods;
