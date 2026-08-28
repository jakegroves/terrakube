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
