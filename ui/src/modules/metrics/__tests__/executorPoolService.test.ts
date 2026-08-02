import { axiosRegistry } from "@/config/axiosConfig";
import executorPoolService from "../executorPoolService";

jest.mock("@/config/axiosConfig", () => ({
  axiosRegistry: { get: jest.fn() },
}));

const mockGet = axiosRegistry.get as jest.Mock;

describe("executorPoolService.getStatus", () => {
  afterEach(() => {
    mockGet.mockReset();
  });

  it("reads busy and poolSize from the response", async () => {
    mockGet.mockResolvedValue({ data: { busy: 2, poolSize: 5 } });

    const result = await executorPoolService.getStatus();

    expect(result).toEqual({ busy: 2, poolSize: 5 });
    expect(mockGet).toHaveBeenCalledWith("/executor-pool/status");
  });

  it("defaults to 0 when fields are missing", async () => {
    mockGet.mockResolvedValue({ data: {} });

    const result = await executorPoolService.getStatus();

    expect(result).toEqual({ busy: 0, poolSize: 0 });
  });
});
