import { installErrorHandlers } from "../errors";

it("records a span when window fires an error event", () => {
  const span = { setAttribute: jest.fn(), end: jest.fn() };
  const tracer = { startSpan: jest.fn().mockReturnValue(span) } as never;

  const cleanup = installErrorHandlers(tracer);
  window.dispatchEvent(new ErrorEvent("error", { message: "boom", error: new Error("boom") }));

  expect(tracer.startSpan).toHaveBeenCalledWith("browser.error");
  expect(span.setAttribute).toHaveBeenCalledWith("exception.message", "boom");
  expect(span.end).toHaveBeenCalled();
  cleanup();
});

it("records a span on unhandledrejection", () => {
  const span = { setAttribute: jest.fn(), end: jest.fn() };
  const tracer = { startSpan: jest.fn().mockReturnValue(span) } as never;

  const cleanup = installErrorHandlers(tracer);
  const evt = new Event("unhandledrejection") as PromiseRejectionEvent;
  Object.defineProperty(evt, "reason", { value: new Error("rejected") });
  window.dispatchEvent(evt);

  expect(tracer.startSpan).toHaveBeenCalledWith("browser.error");
  expect(span.setAttribute).toHaveBeenCalledWith("exception.message", "rejected");
  cleanup();
});

it("cleanup removes the listeners", () => {
  const span = { setAttribute: jest.fn(), end: jest.fn() };
  const tracer = { startSpan: jest.fn().mockReturnValue(span) } as never;

  const cleanup = installErrorHandlers(tracer);
  cleanup();
  window.dispatchEvent(new ErrorEvent("error", { message: "after cleanup" }));

  expect(tracer.startSpan).not.toHaveBeenCalled();
});
