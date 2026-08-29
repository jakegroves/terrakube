# Terrakube load / demo generator

Seeds organizations + workspaces and drives runs so the observability dashboards
(`Terrakube - Run Outcomes`, `Flow Efficiency`, `Resources & Registry`, `Traces`,
`Logs`, `Observability Cost & Scale`) populate — and so you can watch the stack
under load.

Bash + `curl` + `python3` only. No build.

## Local stack (telemetry-compose from-source loop)

```bash
cd telemetry-compose/loadgen
./loadgen.sh seed --orgs 10 --workspaces 5
./loadgen.sh run  --rate 20/min --duration 10m --mix plan=60,apply=30,reject=5,fail=5
./loadgen.sh ramp --steps 1,10,50,100 --hold 10m     # for SIZING.md numbers
./loadgen.sh status
./loadgen.sh teardown
```

`seed` mints a superuser PAT from `../../.envApi`'s `PatSecret`, creates a
`TERRAKUBE_ADMIN` team per org, and points workspaces at a bundled no-op
`terraform_data` config served from a local `file://` git repo.

## Remote Terrakube

```bash
./loadgen.sh \
  --api-url https://terrakube-api.example.com \
  --grafana-url https://grafana.example.com \
  --pat-secret "$TERRAKUBE_PAT_SECRET" \
  --source https://github.com/you/tf-noop.git \
  seed --orgs 5 --workspaces 5
```

`file://` sources only work when the executor runs on the same host (the
from-source loop). For a real deployment pass `--source <git-url>` to a repo with
a trivial config.

## Mix slices

`plan` plan-only · `apply` plan+apply (auto-applies with the demo template) ·
`reject` creates an apply job and rejects it at `waitingApproval` ·
`fail` a job against a deliberately broken source.

## Teardown

`teardown` soft-deletes (`disabled=true`) every org it created and removes
`.state.json` + the local source repo. Nothing else is touched.
