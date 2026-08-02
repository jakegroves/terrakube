import { axiosRegistry } from "@/config/axiosConfig";

// ExecutorPoolController is a plain Spring REST controller at the server root
// (not under Elide's /api/v1 or /graphql/api/v1) — axiosRegistry already points
// at the bare origin with the same auth interceptor, so it's reused here rather
// than adding a near-identical axios instance for one endpoint.
async function getStatus(): Promise<{ busy: number; poolSize: number }> {
  const response = await axiosRegistry.get("/executor-pool/status");
  return {
    busy: Number(response.data?.busy ?? 0),
    poolSize: Number(response.data?.poolSize ?? 0),
  };
}

const methods = { getStatus };

export default methods;
