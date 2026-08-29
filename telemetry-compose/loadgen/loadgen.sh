#!/usr/bin/env bash
# Terrakube load / demo generator. Seeds orgs+workspaces and drives runs so the
# observability dashboards populate and can be watched under load.
#
#   ./loadgen.sh seed --orgs 10 --workspaces 5
#   ./loadgen.sh run --rate 20/min --duration 10m --mix plan=60,apply=30,reject=5,fail=5
#   ./loadgen.sh ramp --steps 1,10,50,100 --hold 10m
#   ./loadgen.sh status
#   ./loadgen.sh teardown
#
# Local stack by default. For a remote Terrakube:
#   ./loadgen.sh --api-url https://terrakube-api.example.com \
#                --source https://github.com/you/tf-noop.git --pat-secret <secret> seed ...
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

API_URL="http://localhost:8080"
GRAFANA_URL="http://localhost:3001"
PAT_SECRET=""
SOURCE=""
TF_VERSION="1.5.7"
LOADGEN_SRC="${LOADGEN_SRC:-/tmp/tk-loadgen-src}"
STATE="$PWD/.state.json"
DRY_RUN=0
VERBOSE=0

# --- arg parsing: global flags may appear before OR after the subcommand ---
ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --api-url) API_URL="$2"; shift 2;;
    --grafana-url) GRAFANA_URL="$2"; shift 2;;
    --pat-secret) PAT_SECRET="$2"; shift 2;;
    --source) SOURCE="$2"; shift 2;;
    --tf-version) TF_VERSION="$2"; shift 2;;
    --dry-run) DRY_RUN=1; shift;;
    -v|--verbose) VERBOSE=1; shift;;
    *) ARGS+=("$1"); shift;;
  esac
done
set -- "${ARGS[@]:-}"
SUBCOMMAND="${1:-}"; [ $# -gt 0 ] && shift || true

log()  { echo "[loadgen] $*" >&2; }
vlog() { [ "$VERBOSE" = 1 ] && echo "[loadgen] $*" >&2 || true; }
die()  { echo "[loadgen] ERROR: $*" >&2; exit 1; }

resolve_secret() {
  if [ -n "$PAT_SECRET" ]; then return; fi
  local envfile="../../.envApi"
  [ -f "$envfile" ] || die "no --pat-secret and $envfile not found"
  PAT_SECRET="$(grep -E '^PatSecret=' "$envfile" | head -1 | cut -d= -f2-)"
  [ -n "$PAT_SECRET" ] || die "PatSecret not found in $envfile"
}

mint() { python3 mint-token.py --secret "$PAT_SECRET"; }

# api METHOD PATH [JSON_BODY]  -> prints response body; honours --dry-run
api() {
  local method="$1" path="$2" body="${3:-}"
  if [ "$DRY_RUN" = 1 ]; then
    echo "DRY-RUN $method $API_URL$path ${body:+-d '$body'}" >&2
    echo '{"data":{"id":"dry-run","attributes":{"status":"completed"}}}'
    return
  fi
  local -a c=(curl -sS -X "$method" "$API_URL$path"
             -H "Authorization: Bearer $TOKEN"
             -H "Content-Type: application/vnd.api+json"
             -H "Accept: application/vnd.api+json")
  [ -n "$body" ] && c+=(-d "$body")
  "${c[@]}"
}

# jval JSON PY_EXPR  (expr sees `d` = parsed json); prints result or empty on error
jval() { python3 -c 'import sys,json
try:
    d=json.loads(sys.argv[1]); print(eval(sys.argv[2]))
except Exception: pass' "$1" "$2"; }

ensure_src_repo() {
  [ -n "$SOURCE" ] && return                       # caller supplied a remote source
  SOURCE="file://$LOADGEN_SRC"
  if [ ! -d "$LOADGEN_SRC/.git" ]; then
    log "creating no-op source repo at $LOADGEN_SRC"
    rm -rf "$LOADGEN_SRC"; mkdir -p "$LOADGEN_SRC"
    cp noop/main.tf "$LOADGEN_SRC/main.tf"
    ( cd "$LOADGEN_SRC"
      git init -q
      git checkout -q -b main
      git add -A
      git -c commit.gpgsign=false -c user.email=loadgen@terrakube -c user.name=loadgen commit -qm noop )
  fi
}

state_init() { [ -f "$STATE" ] || echo '{"orgs":[],"templates":{},"workspaces":[]}' > "$STATE"; }
# state_put PY_STMT : mutate `d` in place then write. e.g. state_put 'd["orgs"].append({"id":"x"})'
state_put() {
  python3 -c 'import sys,json
d=json.load(open(sys.argv[1])); exec(sys.argv[2]); json.dump(d,open(sys.argv[1],"w"),indent=2)' "$STATE" "$1"
}
state_get() { python3 -c 'import sys,json; d=json.load(open(sys.argv[1])); print(eval(sys.argv[2]))' "$STATE" "$1"; }

cmd_seed()     { die "seed: not implemented"; }
cmd_run()      { die "run: not implemented"; }
cmd_ramp()     { die "ramp: not implemented"; }
cmd_teardown() { die "teardown: not implemented"; }

cmd_status() {
  state_init
  echo "state: $STATE"
  echo "  orgs:       $(state_get 'len(d["orgs"])')"
  echo "  workspaces: $(state_get 'len(d["workspaces"])')"
  local runs
  runs=$(curl -s "$API_URL/actuator/prometheus" 2>/dev/null | grep -c '^terrakube_run_finished_total' || true)
  echo "  terrakube_run_finished_total series at $API_URL: ${runs:-0}"
}

case "$SUBCOMMAND" in
  status)   cmd_status ;;
  seed)     resolve_secret; TOKEN="$(mint)"; ensure_src_repo; cmd_seed "$@" ;;
  run)      resolve_secret; TOKEN="$(mint)"; cmd_run "$@" ;;
  ramp)     resolve_secret; TOKEN="$(mint)"; cmd_ramp "$@" ;;
  teardown) resolve_secret; TOKEN="$(mint)"; cmd_teardown "$@" ;;
  ""|-h|--help|help) sed -n '2,13p' "$0"; exit 0 ;;
  *) die "unknown subcommand: $SUBCOMMAND" ;;
esac
