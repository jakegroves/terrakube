export interface TelemetryConfig {
  enabled: boolean;
  otlpEndpoint: string;
  apiOrigin: string;
  sampleRate: number;
  serviceName: string;
}

const clamp01 = (n: number): number => Math.min(1, Math.max(0, n));

const deriveApiOrigin = (): string => {
  try {
    return new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin;
  } catch {
    return "";
  }
};

export function getTelemetryConfig(): TelemetryConfig {
  const env = window._env_;
  const otlpEndpoint = (env.REACT_APP_OTEL_EXPORTER_OTLP_ENDPOINT ?? "").trim();
  const flagOn = (env.REACT_APP_OTEL_ENABLED ?? "").trim().toLowerCase() === "true";

  const parsedRate = Number.parseFloat(env.REACT_APP_OTEL_TRACES_SAMPLE_RATE ?? "");
  const sampleRate = Number.isFinite(parsedRate) ? clamp01(parsedRate) : 0.1;

  return {
    enabled: flagOn && otlpEndpoint.length > 0,
    otlpEndpoint,
    apiOrigin: deriveApiOrigin(),
    sampleRate,
    serviceName: (env.REACT_APP_OTEL_SERVICE_NAME ?? "").trim() || "terrakube-ui",
  };
}
