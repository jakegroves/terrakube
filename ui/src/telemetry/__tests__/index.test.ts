const baseEnv = {
  REACT_APP_TERRAKUBE_API_URL: "https://api.example.com/api/v1",
} as Window["_env_"];

beforeEach(() => {
  jest.resetModules();
  window._env_ = { ...baseEnv };
});

it("does nothing and resolves when telemetry is disabled", async () => {
  const { initTelemetry } = await import("../index");
  await expect(initTelemetry()).resolves.toBeUndefined();
});

it("is safe to call twice when enabled", async () => {
  window._env_.REACT_APP_OTEL_ENABLED = "true";
  window._env_.REACT_APP_OTEL_EXPORTER_OTLP_ENDPOINT = "http://localhost:4318/v1/traces";
  const { initTelemetry } = await import("../index");
  await initTelemetry();
  await expect(initTelemetry()).resolves.toBeUndefined();
});
