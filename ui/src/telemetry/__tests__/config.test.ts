import { getTelemetryConfig } from "../config";

const baseEnv = {
  REACT_APP_TERRAKUBE_API_URL: "https://api.terrakube.example.com/api/v1",
} as Window["_env_"];

beforeEach(() => {
  window._env_ = { ...baseEnv };
});

describe("getTelemetryConfig", () => {
  it("is disabled when the flag is absent", () => {
    expect(getTelemetryConfig().enabled).toBe(false);
  });

  it("is disabled when enabled=true but no endpoint is set", () => {
    window._env_.REACT_APP_OTEL_ENABLED = "true";
    expect(getTelemetryConfig().enabled).toBe(false);
  });

  it("is enabled with flag + endpoint, and derives the api origin", () => {
    window._env_.REACT_APP_OTEL_ENABLED = "true";
    window._env_.REACT_APP_OTEL_EXPORTER_OTLP_ENDPOINT = "https://otel.example.com/v1/traces";
    const cfg = getTelemetryConfig();
    expect(cfg.enabled).toBe(true);
    expect(cfg.apiOrigin).toBe("https://api.terrakube.example.com");
  });

  it("defaults sample rate to 0.1 and clamps out-of-range values", () => {
    window._env_.REACT_APP_OTEL_ENABLED = "true";
    window._env_.REACT_APP_OTEL_EXPORTER_OTLP_ENDPOINT = "https://otel.example.com/v1/traces";
    expect(getTelemetryConfig().sampleRate).toBe(0.1);
    window._env_.REACT_APP_OTEL_TRACES_SAMPLE_RATE = "5";
    expect(getTelemetryConfig().sampleRate).toBe(1);
    window._env_.REACT_APP_OTEL_TRACES_SAMPLE_RATE = "-1";
    expect(getTelemetryConfig().sampleRate).toBe(0);
  });

  it("defaults the service name", () => {
    expect(getTelemetryConfig().serviceName).toBe("terrakube-ui");
  });
});
