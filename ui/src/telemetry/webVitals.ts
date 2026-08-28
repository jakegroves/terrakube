import type { Tracer } from "@opentelemetry/api";
import { onCLS, onFCP, onINP, onLCP, onTTFB, type Metric } from "web-vitals";

const record =
  (tracer: Tracer) =>
  (metric: Metric): void => {
    const span = tracer.startSpan(`web.vital.${metric.name}`);
    span.setAttribute("web_vital.name", metric.name);
    span.setAttribute("web_vital.value", metric.value);
    span.setAttribute("web_vital.rating", metric.rating);
    span.setAttribute("page.route", window.location.pathname);
    span.end();
  };

export function reportWebVitals(tracer: Tracer): void {
  const emit = record(tracer);
  onCLS(emit);
  onFCP(emit);
  onINP(emit);
  onLCP(emit);
  onTTFB(emit);
}
