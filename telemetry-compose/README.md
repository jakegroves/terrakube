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
datasources and four dashboards (Overview, API, Executor, JVM) already loaded.

## Walk a request through the stack

1. Sign in to the UI and start a plan on any workspace.
2. **Grafana → Explore → Tempo**, search `service.name = terrakube-api`. Open the
   trace — you should see spans from `terrakube-ui` (browser), `terrakube-api`
   and `terrakube-executor` sharing one trace id, plus a `terrakube.job` span
   carrying `workspace.id` / `organization.id` / `job.id`.
3. From a span, use **"Logs for this span"** to jump to VictoriaLogs filtered by
   that `trace_id`.
4. **Grafana → Dashboards → Terrakube → Executor**: `terrakube_job_execution`,
   `terrakube_job_concurrent` and the exit-code breakdown update as the job runs.
5. **Terrakube → API**: watch `hikaricp_connections_pending`, the webhook queue
   depth, and job-status throughput.

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
