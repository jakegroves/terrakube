import type { Tracer } from "@opentelemetry/api";

const recordError = (tracer: Tracer, err: unknown, fallbackMessage: string): void => {
  const span = tracer.startSpan("browser.error");
  const error = err instanceof Error ? err : new Error(typeof err === "string" ? err : fallbackMessage);
  span.setAttribute("exception.type", error.name);
  span.setAttribute("exception.message", error.message);
  if (error.stack) {
    span.setAttribute("exception.stacktrace", error.stack);
  }
  span.setAttribute("page.route", window.location.pathname);
  span.end();
};

export function installErrorHandlers(tracer: Tracer): () => void {
  const onError = (e: ErrorEvent): void => recordError(tracer, e.error ?? e.message, "window.onerror");
  const onRejection = (e: PromiseRejectionEvent): void => recordError(tracer, e.reason, "unhandledrejection");

  window.addEventListener("error", onError);
  window.addEventListener("unhandledrejection", onRejection);

  return () => {
    window.removeEventListener("error", onError);
    window.removeEventListener("unhandledrejection", onRejection);
  };
}
