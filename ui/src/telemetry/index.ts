import { getTelemetryConfig } from "./config";

let started = false;

/**
 * Initializes browser telemetry. No-op (and imports nothing heavy) unless
 * REACT_APP_OTEL_ENABLED=true and an OTLP endpoint is configured. Idempotent and
 * never rejects - safe to call fire-and-forget from app bootstrap.
 */
export async function initTelemetry(): Promise<void> {
  if (started) return;
  const cfg = getTelemetryConfig();
  if (!cfg.enabled) return;
  started = true;

  try {
    const [
      { WebTracerProvider, BatchSpanProcessor, TraceIdRatioBasedSampler },
      { OTLPTraceExporter },
      { ZoneContextManager },
      { registerInstrumentations },
      { FetchInstrumentation },
      { resourceFromAttributes },
      { ATTR_SERVICE_NAME },
      otelApi,
    ] = await Promise.all([
      import("@opentelemetry/sdk-trace-web"),
      import("@opentelemetry/exporter-trace-otlp-http"),
      import("@opentelemetry/context-zone"),
      import("@opentelemetry/instrumentation"),
      import("@opentelemetry/instrumentation-fetch"),
      import("@opentelemetry/resources"),
      import("@opentelemetry/semantic-conventions"),
      import("@opentelemetry/api"),
    ]);

    const provider = new WebTracerProvider({
      resource: resourceFromAttributes({ [ATTR_SERVICE_NAME]: cfg.serviceName }),
      sampler: new TraceIdRatioBasedSampler(cfg.sampleRate),
      spanProcessors: [new BatchSpanProcessor(new OTLPTraceExporter({ url: cfg.otlpEndpoint }))],
    });
    provider.register({ contextManager: new ZoneContextManager() });

    registerInstrumentations({
      instrumentations: [
        new FetchInstrumentation({
          propagateTraceHeaderCorsUrls: cfg.apiOrigin ? [cfg.apiOrigin] : [],
          clearTimingResources: true,
        }),
      ],
    });

    const tracer = otelApi.trace.getTracer(cfg.serviceName);
    const [{ reportWebVitals }, { installErrorHandlers }] = await Promise.all([
      import("./webVitals"),
      import("./errors"),
    ]);
    reportWebVitals(tracer);
    installErrorHandlers(tracer);
  } catch (err) {
    // Telemetry must never break the app.
    // eslint-disable-next-line no-console
    console.warn("Terrakube telemetry failed to initialize", err);
  }
}
