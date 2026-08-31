# Terrakube local OpenTelemetry stack

A laptop-sized mirror of the production observability topology, for developing and
demoing Terrakube's telemetry:

| Signal  | Path                                                             | Backend        |
|---------|-----------------------------------------------------------------|----------------|
| metrics | VictoriaMetrics scrapes `/actuator/prometheus` (pull)           | VictoriaMetrics |
| traces  | OTel Java agent + browser → OTLP → **otel-collector** → push    | Tempo          |
| logs    | OTel Java agent → OTLP → **otel-collector** → push              | VictoriaLogs   |

Everything is visualised in a pre-provisioned Grafana. The plain
[`../docker-compose`](../docker-compose) stack stays telemetry-free — use this one
only when you're working on observability.

Two ways to use it:

| File | Runs | Use when |
|---|---|---|
| `docker-compose.yaml` | the 4 Terrakube services (**prebuilt `azbuilder/*` images**) **plus** the OTel backend | quick demo, no source build |
| `backend.yml` | **only** the OTel backend (collector + stores + Grafana) | you run api/executor/registry/ui **from source** and want your changes traced |

## From-source dev (VS Code)

Pick the **"Terrakube + Observability"** run configuration (or run
`./scripts/setupDevelopmentEnvironment.sh -s LOCAL -d H2 -o`). That:

1. downloads the OpenTelemetry Java agent to `.tools/` (there's no Paketo buildpack when running from source),
2. adds `-javaagent` + the `OTEL_*` / `REACT_APP_OTEL_*` vars to `.envApi` / `.envExecutor` / `.envRegistry` / the UI config,
3. starts `backend.yml` (`docker compose -f telemetry-compose/backend.yml up -d`).

Your services then push traces + logs to `localhost:4318` and VictoriaMetrics
scrapes `/actuator/prometheus` through the docker host gateway. Grafana:
`http://localhost:3001`. Stop it with the `observability-down` task.

Idle footprint of `backend.yml` is ~360 MB (collector 80, VM 70, Tempo 30,
VictoriaLogs 10, Grafana 170).

To drive realistic load against the stack — populate the dashboards, watch
cardinality grow — use [`loadgen/`](loadgen/) (`./loadgen.sh seed && ./loadgen.sh run`).
The cardinality / storage model and a captured reference run are in
`examples/observability/SIZING.md` in the
[terrakube-helm-chart](https://github.com/terrakube-io/terrakube-helm-chart) repo.

## Local DNS entries

Add to `/etc/hosts`:

```
127.0.0.1 terrakube-api
127.0.0.1 terrakube-ui
127.0.0.1 terrakube-executor
127.0.0.1 terrakube-dex
127.0.0.1 terrakube-registry
```

## Run

```bash
cd telemetry-compose
docker compose up -d
```

| Service            | URL                       | Notes                                   |
|--------------------|---------------------------|-----------------------------------------|
| Terrakube UI       | http://terrakube-ui:3000  | `admin@example.com` / `admin`           |
| Grafana            | http://localhost:3001     | anonymous admin, no login               |
| VictoriaMetrics    | http://localhost:8428     | PromQL + `/api/v1/targets`              |
| Tempo              | http://localhost:3200     | queried through Grafana's Tempo source  |
| VictoriaLogs       | http://localhost:9428     | `select/logsql/query`                   |
| OTel Collector     | `localhost:4317/4318`     | OTLP gRPC / HTTP ingest                 |

Grafana ships with the **VictoriaMetrics**, **Tempo** and **VictoriaLogs**
datasources and 12 dashboards already loaded:

- **Metrics** — Overview, API, Executor, JVM, Run Outcomes & Throughput,
  Flow Efficiency, Resources & Registry
- **Cross-signal** — Traces, Logs, UI RUM, Observability Platform Health,
  Observability Cost & Scale

## Walk a request through the stack

1. Sign in to the UI and start a plan on any workspace.
2. **Dashboards → Terrakube - Traces**: the service graph shows
   `terrakube-ui → terrakube-api → terrakube-executor`; the span-latency and
   error-ratio panels fill in. Open a slow / error trace from the table — the
   `terrakube.job` span carries `workspace.id` / `organization.id` / `job.id`.
3. From a span, **"Logs for this span"** jumps to **Terrakube - Logs** filtered
   by that `trace_id`. From a log line, the **TraceID** field jumps back to the
   trace.
4. **Terrakube - Run Outcomes** / **Flow Efficiency** / **Resources & Registry**:
   the run fires `terrakube_run_*` and `terrakube_resource_changes_*`; use the
   **Logs** / **Traces** links in the nav bar to drill in, or click an exemplar
   diamond on a latency panel to open its trace.
5. **Terrakube - Observability Platform Health**: collector spans / log records
   accepted, VictoriaMetrics insert rate, scrape targets up.

## Configuration

Agent wiring lives in `api.env` / `executor.env` / `registry.env`:

```
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318
OTEL_TRACES_EXPORTER=otlp
OTEL_METRICS_EXPORTER=none      # metrics are scraped, not pushed
OTEL_LOGS_EXPORTER=otlp
```

Browser telemetry is toggled in `env-config.js`
(`REACT_APP_OTEL_ENABLED`, `REACT_APP_OTEL_EXPORTER_OTLP_ENDPOINT`).

Collector pipeline: `otel-collector.yaml`. Scrape targets: `victoriametrics-scrape.yaml`.

## Reset

```bash
docker compose down -v
```
