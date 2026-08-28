import { reportWebVitals } from "../webVitals";

const onCLS = jest.fn();
const onFCP = jest.fn();
const onINP = jest.fn();
const onLCP = jest.fn();
const onTTFB = jest.fn();
jest.mock("web-vitals", () => ({
  onCLS: (cb: unknown) => onCLS(cb),
  onFCP: (cb: unknown) => onFCP(cb),
  onINP: (cb: unknown) => onINP(cb),
  onLCP: (cb: unknown) => onLCP(cb),
  onTTFB: (cb: unknown) => onTTFB(cb),
}));

it("subscribes to all five vitals and records a span per reported metric", () => {
  const span = { setAttribute: jest.fn(), end: jest.fn() };
  const tracer = { startSpan: jest.fn().mockReturnValue(span) } as never;

  reportWebVitals(tracer);
  expect(onLCP).toHaveBeenCalled();
  expect(onCLS).toHaveBeenCalled();
  expect(onFCP).toHaveBeenCalled();
  expect(onINP).toHaveBeenCalled();
  expect(onTTFB).toHaveBeenCalled();

  const cb = onLCP.mock.calls[0][0] as (m: unknown) => void;
  cb({ name: "LCP", value: 1234.5, rating: "good" });

  expect(span.setAttribute).toHaveBeenCalledWith("web_vital.name", "LCP");
  expect(span.setAttribute).toHaveBeenCalledWith("web_vital.value", 1234.5);
  expect(span.setAttribute).toHaveBeenCalledWith("web_vital.rating", "good");
  expect(span.end).toHaveBeenCalled();
});
