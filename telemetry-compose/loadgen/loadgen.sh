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
# print "<orgId> <workspaceId>" for a random non-broken seeded workspace
state_random_workspace() { python3 -c 'import sys,json,random
d=json.load(open(sys.argv[1]))
w=random.choice([x for x in d["workspaces"] if not x["broken"]])
print(w["orgId"], w["id"])' "$STATE"; }

cmd_seed() {
  local orgs=5 workspaces=5
  while [ $# -gt 0 ]; do case "$1" in
    --orgs) orgs="$2"; shift 2;;
    --workspaces) workspaces="$2"; shift 2;;
    *) die "seed: bad arg $1";; esac; done
  state_init
  local existing; existing="$(state_get '[o["name"] for o in d["orgs"]]')"

  for i in $(seq 1 "$orgs"); do
    local name="loadgen-org-$i"
    case "$existing" in *"'$name'"*) vlog "$name exists, skip"; continue;; esac

    local oid; oid="$(jval "$(api POST /api/v1/organization \
      "{\"data\":{\"type\":\"organization\",\"attributes\":{\"name\":\"$name\",\"executionMode\":\"remote\"}}}")" \
      'd["data"]["id"]')"
    [ -n "$oid" ] || die "create org $name failed"
    log "org $name -> $oid"
    state_put "d['orgs'].append({'id':'$oid','name':'$name'})"

    # team named after the token's group so 'team manage workspace' resolves
    api POST "/api/v1/organization/$oid/team" \
      '{"data":{"type":"team","attributes":{"name":"TERRAKUBE_ADMIN","manageWorkspace":true,"manageJob":true,"manageState":true,"manageModule":true,"manageProvider":true,"manageVcs":true,"manageTemplate":true,"manageCollection":true,"planJob":true,"approveJob":true}}}' >/dev/null

    # the auto-created templates
    local tpls; tpls="$(api GET "/api/v1/organization/$oid/template")"
    local plan apply
    plan="$(jval "$tpls" 'next(t["id"] for t in d["data"] if t["attributes"]["name"]=="Plan")')"
    apply="$(jval "$tpls" 'next(t["id"] for t in d["data"] if t["attributes"]["name"]=="Plan and apply")')"
    [ -n "$plan" ] && [ -n "$apply" ] || die "templates not found for $name"
    state_put "d['templates']['$oid']={'plan':'$plan','apply':'$apply'}"

    for w in $(seq 1 "$workspaces"); do
      local wname="lg-ws-$i-$w"
      local wid; wid="$(jval "$(api POST "/api/v1/organization/$oid/workspace" \
        "{\"data\":{\"type\":\"workspace\",\"attributes\":{\"name\":\"$wname\",\"source\":\"$SOURCE\",\"branch\":\"main\",\"iacType\":\"terraform\",\"terraformVersion\":\"$TF_VERSION\",\"executionMode\":\"remote\"}}}")" \
        'd["data"]["id"]')"
      [ -n "$wid" ] || die "create workspace $wname failed"
      state_put "d['workspaces'].append({'id':'$wid','name':'$wname','orgId':'$oid','broken':False})"
    done
    log "  + $workspaces workspaces"
  done
  log "seed done: $(state_get 'len(d["orgs"])') orgs / $(state_get 'len(d["workspaces"])') workspaces"
}
# parse "20/min" | "2/sec" -> seconds between launches (float)
_interval() { python3 -c 'import sys
v,unit=sys.argv[1].split("/"); v=float(v)
print(60.0/v if unit.startswith("min") else 1.0/v)' "$1"; }

# parse "10m" | "90s" -> seconds
_seconds() { python3 -c 'import sys
x=sys.argv[1]; print(int(x[:-1])*60 if x.endswith("m") else int(x.rstrip("s")))' "$1"; }

# weighted-random slice name from "plan=60,apply=30,reject=5,fail=5"
_pick_slice() { python3 -c 'import sys,random
pairs=[p.split("=") for p in sys.argv[1].split(",")]
print(random.choices([k for k,_ in pairs],weights=[float(v) for _,v in pairs])[0])' "$1"; }

# a cached one-off workspace with a bad source, for the "fail" slice
_broken_workspace() {
  local oid="$1" wid
  wid="$(state_get "next((w['id'] for w in d['workspaces'] if w['orgId']=='$oid' and w['broken']), '')")"
  if [ -z "$wid" ]; then
    wid="$(jval "$(api POST "/api/v1/organization/$oid/workspace" \
      "{\"data\":{\"type\":\"workspace\",\"attributes\":{\"name\":\"lg-broken-$RANDOM\",\"source\":\"file:///nonexistent-loadgen-path\",\"branch\":\"main\",\"iacType\":\"terraform\",\"terraformVersion\":\"$TF_VERSION\",\"executionMode\":\"remote\"}}}")" \
      'd["data"]["id"]')"
    state_put "d['workspaces'].append({'id':'$wid','name':'lg-broken','orgId':'$oid','broken':True})"
  fi
  echo "$wid"
}

_post_job() {  # ORG_ID WORKSPACE_ID TEMPLATE_ID VIA -> job id
  jval "$(api POST "/api/v1/organization/$1/job" \
    "{\"data\":{\"type\":\"job\",\"attributes\":{\"templateReference\":\"$3\",\"via\":\"$4\"},\"relationships\":{\"workspace\":{\"data\":{\"type\":\"workspace\",\"id\":\"$2\"}}}}}")" \
    'd["data"]["id"]'
}

_job_status() { jval "$(api GET "/api/v1/organization/$1/job/$2")" 'd["data"]["attributes"]["status"]'; }

_reject_when_waiting() {  # ORG_ID JOB_ID : poll, PATCH to rejected once waitingApproval
  local oid="$1" jid="$2" i st
  for i in $(seq 1 60); do
    sleep 3; st="$(_job_status "$oid" "$jid")"
    case "$st" in
      waitingApproval) api PATCH "/api/v1/organization/$oid/job/$jid" \
        "{\"data\":{\"type\":\"job\",\"id\":\"$jid\",\"attributes\":{\"status\":\"rejected\"}}}" >/dev/null; return;;
      completed|noChanges|failed|cancelled|rejected|notExecuted|unknown) return;;
    esac
  done
}

cmd_run() {
  local rate="20/min" duration="5m" mix="plan=60,apply=30,reject=5,fail=5" concurrent=10
  while [ $# -gt 0 ]; do case "$1" in
    --rate) rate="$2"; shift 2;;
    --duration) duration="$2"; shift 2;;
    --mix) mix="$2"; shift 2;;
    --concurrent) concurrent="$2"; shift 2;;
    *) die "run: bad arg $1";; esac; done
  state_init
  ensure_src_repo
  [ "$(state_get 'len(d["workspaces"])')" -gt 0 ] || die "no seeded workspaces - run 'seed' first"

  local interval end_ts via_list=(CLI API UI Github Gitlab) vi=0 n=0
  interval="$(_interval "$rate")"
  end_ts=$(( $(date +%s) + $(_seconds "$duration") ))
  log "run: rate=$rate mix=$mix concurrent=$concurrent for $(_seconds "$duration")s"

  while [ "$(date +%s)" -lt "$end_ts" ]; do
    while [ "$(jobs -rp | wc -l)" -ge "$concurrent" ]; do sleep 0.2; done
    local slice; slice="$(_pick_slice "$mix")"
    local via="${via_list[$((vi % ${#via_list[@]}))]}"; vi=$((vi+1))
    local pick; pick="$(state_random_workspace)"
    local oid="${pick% *}" wid="${pick#* }"
    local tpl_plan tpl_apply
    tpl_plan="$(state_get "d['templates']['$oid']['plan']")"
    tpl_apply="$(state_get "d['templates']['$oid']['apply']")"

    case "$slice" in
      plan)   ( _post_job "$oid" "$wid" "$tpl_plan"  "$via" >/dev/null ) & ;;
      apply)  ( _post_job "$oid" "$wid" "$tpl_apply" "$via" >/dev/null ) & ;;
      reject) ( jid="$(_post_job "$oid" "$wid" "$tpl_apply" "$via")"; _reject_when_waiting "$oid" "$jid" ) & ;;
      fail)   ( bw="$(_broken_workspace "$oid")"; _post_job "$oid" "$bw" "$tpl_plan" "$via" >/dev/null ) & ;;
    esac
    n=$((n+1)); [ $((n % 10)) -eq 0 ] && log "  launched $n jobs"
    sleep "$interval"
  done
  wait
  log "run done: $n jobs launched"
}
_grafana_annotation() {  # best-effort; the demo Grafana is anonymous-admin
  local text="$1"
  curl -s -o /dev/null -X POST "$GRAFANA_URL/api/annotations" \
    -H 'Content-Type: application/json' \
    -d "{\"tags\":[\"loadgen-ramp\"],\"text\":\"$text\",\"time\":$(( $(date +%s) * 1000 ))}" || true
}

cmd_ramp() {
  local steps="1,10,50,100" hold="10m"
  while [ $# -gt 0 ]; do case "$1" in
    --steps) steps="$2"; shift 2;;
    --hold) hold="$2"; shift 2;;
    *) die "ramp: bad arg $1";; esac; done
  state_init
  [ "$(state_get 'len(d["workspaces"])')" -gt 0 ] || die "no seeded workspaces - run 'seed' first"
  local IFS=,
  for s in $steps; do
    log "=== ramp step: concurrency=$s for $hold ==="
    _grafana_annotation "loadgen ramp: concurrency=$s"
    cmd_run --concurrent "$s" --rate "$((s*2))/min" --duration "$hold" --mix "plan=60,apply=35,fail=5"
  done
  _grafana_annotation "loadgen ramp: done"
  log "ramp complete"
}

cmd_teardown() {
  state_init
  while read -r oid; do
    [ -n "$oid" ] || continue
    if api PATCH "/api/v1/organization/$oid" \
        "{\"data\":{\"type\":\"organization\",\"id\":\"$oid\",\"attributes\":{\"disabled\":true}}}" >/dev/null; then
      log "disabled org $oid"
    else
      log "WARN could not disable $oid"
    fi
  done < <(python3 -c 'import json; [print(o["id"]) for o in json.load(open("'"$STATE"'"))["orgs"]]')
  rm -f "$STATE"
  rm -rf "$LOADGEN_SRC"
  log "teardown done"
}

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
