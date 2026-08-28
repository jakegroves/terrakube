# Observability

Terrakube emits **vendor-neutral** telemetry:

| Signal | Transport | Source | Point it at |
|---|---|---|---|
| metrics | pull — `GET /actuator/prometheus` on the http port | Micrometer | any Prometheus-compatible scraper |
| traces | push — OTLP | OpenTelemetry Java agent (all 3 services) + the `ui` web SDK | any OTLP endpoint (collector, Grafana Cloud, Datadog, Honeycomb, …) |
| logs | push — OTLP; also ECS-JSON on stdout | agent logback appender / Spring Boot structured logging | an OTLP endpoint, or a stdout log collector |

Nothing here depends on a specific backend. `examples/observability/` in the
[terrakube-helm-chart](https://github.com/terrakube-io/terrakube-helm-chart) repo
is one turnkey backend (OTel Collector + VictoriaMetrics + Tempo + VictoriaLogs +
Grafana); it is optional.

---

## 1. Metrics catalog

Names are Micrometer dot-notation; the Prometheus rendering is in parentheses.
Every service also exposes the standard Micrometer/Spring binders — `jvm_*`,
`process_*`, `system_*`, `http_server_requests_seconds_*`, `hikaricp_*` (api),
`logback_events_total`, `executor_*` (Tomcat/thread pools), `jdbc_*`.

### 1.1 api

| Metric | Type | Unit | Tags | Meaning |
|---|---|---|---|---|
| `terrakube.job.transitions` (`terrakube_job_transitions_total`) | Counter | — | `to` | Count of jobs entering a status. Throughput = `rate(...{to="completed"})`; failure ratio = `...{to="failed"} / total`. Keyed by destination only — api has no single state-machine method. |
| `terrakube.job.queue.wait` (`terrakube_job_queue_wait_seconds`) | Timer | s | `organization` | Time from job creation to dispatch (entering the executor queue). |
| `terrakube.workspace.active` (`terrakube_workspace_active`) | Gauge | — | — | Current workspace count (evaluated on scrape). |
| `webhook.delivery.queue.depth` (`webhook_delivery_queue_depth`) | Gauge | — | — | `repo_webhook_delivery` rows currently `PENDING`. |
| `webhook.delivery.queue.oldest.age.seconds` (`webhook_delivery_queue_oldest_age_seconds`) | Gauge | s | — | Age of the oldest `PENDING` delivery, `0` if none. |
| `webhook.workspace.fanout.queue.depth` (`webhook_workspace_fanout_queue_depth`) | Gauge | — | — | Workspace-fanout tasks queued waiting for a thread. |
| `webhook.http.timeout.count` (`webhook_http_timeout_count_total`) | Counter | — | — | VCS API calls that failed with a connection / response / pool-acquisition timeout. |
| `quartz.jobs.executing` (`quartz_jobs_executing`) | Gauge | — | — | Quartz jobs executing on this scheduler instance right now. |
| `executor.availability.age.seconds` (`executor_availability_age_seconds`) | Gauge | s | — | Seconds since the executor module last signalled spare capacity. |

### 1.2 executor

| Metric | Type | Unit | Tags | Meaning |
|---|---|---|---|---|
| `terrakube.job.execution` (`terrakube_job_execution_seconds`) | Timer | s | `tool`, `step`, `result` | Duration of a job phase. `tool` ∈ {`terraform`,`tofu`}; `step` ∈ {`plan`,`apply`,`destroy`,`script`,`unknown`}; `result` ∈ {`success`,`failure`}. |
| `terrakube.job.exit` (`terrakube_job_exit_total`) | Counter | — | `tool`, `exit_code_class` | Subprocess outcomes. `exit_code_class` ∈ {`ok`,`error`}. |
| `terrakube.job.concurrent` (`terrakube_job_concurrent`) | Gauge | — | — | Jobs executing on this pod right now (`0` or `1`; one job per pod). Sum across pods = cluster-wide active jobs. |

Executor container memory is **not** an app metric — read
`container_memory_working_set_bytes` from the cluster's kubelet/cAdvisor scrape
and overlay it with `sum(terrakube_job_concurrent)`.

### 1.3 registry

| Metric | Type | Unit | Tags | Meaning |
|---|---|---|---|---|
| `terrakube.registry.download` (`terrakube_registry_download_total`) | Counter | — | `type` | Artifact downloads served. `type` ∈ {`module`,`provider`}. |
| `terrakube.registry.resolve` (`terrakube_registry_resolve_seconds`) | Timer | s | `type` | Version-resolution latency. `type` ∈ {`module`,`provider`}. |
| `terrakube.registry.auth.failure` (`terrakube_registry_auth_failure_total`) | Counter | — | `reason` | Rejected registry requests. **Not yet wired** — the meter exists; instrumenting it needs a custom `AuthenticationEntryPoint`. |

### 1.4 Tags to expect

| Tag | Cardinality | Notes |
|---|---|---|
| `to` | bounded | `JobStatus` enum |
| `tool` | 2 | terraform / tofu |
| `step`, `result`, `exit_code_class`, `type` | ≤5 | fixed enumerations |
| `organization` | bounded in practice | capped at `io.terrakube.metrics.max-organization-tags` (default 200) by a `MeterFilter`; drop it entirely with `MeterFilter.ignoreTags("organization")` |
| `workspace` | **never a tag** | unbounded — it is a *span attribute* only, and a `MeterFilter` drops any meter that carries it |

---

## 2. Traces catalog

### 2.1 Custom spans

| Span | Service | Attributes | When |
|---|---|---|---|
| `terrakube.job` | executor | `organization.id`, `workspace.id`, `job.id`, `job.type` | wraps the whole plan/apply/destroy/script execution of one job |
| `web.vital.<LCP\|INP\|CLS\|FCP\|TTFB>` | ui | `web_vital.name`, `web_vital.value`, `web_vital.rating`, `page.route` | each Core Web Vital as the browser reports it |
| `browser.error` | ui | `exception.type`, `exception.message`, `exception.stacktrace`, `page.route` | `window.onerror` / `unhandledrejection` |

### 2.2 Agent auto-instrumentation

The OpenTelemetry Java agent (Paketo `opentelemetry` buildpack, already in the
images) produces spans for: Spring MVC server requests, JDBC / HikariCP, Redis
(Lettuce/Jedis), outbound HTTP clients, and Quartz job execution — no code.

### 2.3 Context propagation

The UI fetch instrumentation sends the W3C `traceparent` header **only** to the
API origin (`REACT_APP_TERRAKUBE_API_URL`), so a browser interaction and the
server work it triggers share one trace id.

---

## 3. Logs

Default profile → one ECS-JSON object per line on stdout
(`logging.structured.format.console=ecs`). The agent injects `trace_id`,
`span_id`, `trace_flags` into MDC, so every line is trace-correlated, and the
agent's logback OTLP appender ships the same records over OTLP.

The `local` Spring profile (`application-local.properties`) reverts to the plain
human-readable pattern.

---

## 4. Metric stability policy

Metric names and tag **keys** are part of the app/chart public API and follow
semver:

- Adding a metric, or a new value for an existing tag → **not** breaking.
- Adding a tag **key** to an existing metric → breaking (it changes series
  identity); treat it as a rename.
- Renaming or removing a metric → deprecation cycle: emit old **and** new for one
  minor release, note it under **Breaking** in the changelog, remove no earlier
  than the next minor.

---

## 5. Wiring to a backend

The app config is identical for every backend — only the endpoint (and auth
header) changes. Set these on each service Deployment (the `terrakube` chart does
it from `<svc>.otel.*`):

### 5.1 OpenTelemetry Collector (recommended)

```
OTEL_EXPORTER_OTLP_ENDPOINT = http://<collector>.<ns>:4318
OTEL_EXPORTER_OTLP_PROTOCOL = http/protobuf
OTEL_TRACES_EXPORTER        = otlp
OTEL_LOGS_EXPORTER          = otlp
OTEL_METRICS_EXPORTER       = none
```
Scrape `/actuator/prometheus` with whatever the collector/cluster provides.

### 5.2 Grafana Cloud

```
OTEL_EXPORTER_OTLP_ENDPOINT = https://otlp-gateway-<region>.grafana.net/otlp
OTEL_EXPORTER_OTLP_HEADERS  = Authorization=Basic <base64 instanceID:token>
OTEL_EXPORTER_OTLP_PROTOCOL = http/protobuf
```
Metrics: scrape `/actuator/prometheus` with Grafana Alloy.

### 5.3 Datadog

Run the Datadog Agent with OTLP ingest enabled and point
`OTEL_EXPORTER_OTLP_ENDPOINT` at it (`http://<dd-agent>:4318`). Metrics via the
Agent's OpenMetrics/Prometheus check on `/actuator/prometheus`.

### 5.4 Honeycomb

```
OTEL_EXPORTER_OTLP_ENDPOINT = https://api.honeycomb.io
OTEL_EXPORTER_OTLP_HEADERS  = x-honeycomb-team=<key>
```

### 5.5 Prometheus + Tempo + Loki

- Prometheus: a `ServiceMonitor` / `PodMonitor` / scrape-annotation on
  `/actuator/prometheus` (chart toggles below).
- Tempo: `OTEL_EXPORTER_OTLP_ENDPOINT` → the Tempo distributor, `OTEL_TRACES_EXPORTER=otlp`.
- Loki: run a collector with the `loki` exporter, or Promtail on the JSON stdout.

---

## 6. Scraping on Kubernetes

The `terrakube` chart renders the scrape object for whichever ecosystem you run
— all off by default:

| Value | Renders | For |
|---|---|---|
| `<svc>.metrics.serviceMonitor.enabled` | `ServiceMonitor` | Prometheus Operator / kube-prometheus-stack |
| `<svc>.metrics.podMonitor.enabled` | `PodMonitor` | Prometheus Operator |
| `<svc>.metrics.vmPodScrape.enabled` | `VMPodScrape` | VictoriaMetrics Operator |
| `<svc>.metrics.annotations.enabled` | `prometheus.io/scrape` pod annotations | annotation-based scrapers |

All four describe the same target: `/actuator/prometheus` on the `http` port.
