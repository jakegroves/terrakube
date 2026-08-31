#!/bin/bash
# From-source dev loop WITH the local OpenTelemetry stack.
#
# Runs api / registry / executor as host `mvn spring-boot:run` processes and the
# UI as a host Vite server, all on plain-localhost ports, wired to
# telemetry-compose/backend.yml (OTel Collector + VictoriaMetrics + Tempo +
# VictoriaLogs + Grafana). This is the "Host mvn processes" path - native file
# watching, easy debugger attach, no container/image rebuild to see a change.
#
# It wraps scripts/setupDevelopmentEnvironment.sh -s LOCAL -d H2 -o, which now
# handles host mode directly (localhost URLs, dex issuer, redis hostname,
# credential paths, -o /actuator/prometheus exposure). Only two from-source-loop-
# specific fixups remain, applied by patch_backend_env():
#   - strip -Dcom.sun.management.jmxremote.* for the two spring-boot:run services
#     (JAVA_TOOL_OPTIONS is inherited by both the mvn launcher and the forked app
#     JVM -> a fixed JMX port binds twice)
#   - clear TerrakubeRedisPassword (fromsource-infra.yml valkey is passwordless)
# The UI also needs Node >= 20.12; if nvm is present it switches to
# $TERRAKUBE_UI_NODE (default v24.14.1), else it just checks `node -v`.
#
# Usage:
#   ./.devcontainer/dev-fromsource.sh [up]        # bring everything up (safe to re-run)
#   ./.devcontainer/dev-fromsource.sh down        # stop apps + infra + observability
#   ./.devcontainer/dev-fromsource.sh status
#   ./.devcontainer/dev-fromsource.sh logs <api|registry|executor|ui>
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
REPO_ROOT="$(pwd)"

LOG_DIR="$REPO_ROOT/.devcontainer/logs"
DEX_COMPOSE="$REPO_ROOT/scripts/setup/devcontainer/docker-compose.yaml"
INFRA_COMPOSE="$REPO_ROOT/.devcontainer/fromsource-infra.yml"
BACKEND_COMPOSE="$REPO_ROOT/telemetry-compose/backend.yml"

API_PORT=8080
REGISTRY_PORT=8075
EXECUTOR_PORT=8090
UI_PORT=3000

wait_for() {
  # wait_for <description> <grep-pattern> <logfile>
  local desc="$1" pattern="$2" file="$3" tries=0
  until grep -qE "$pattern" "$file" 2>/dev/null; do
    sleep 3
    tries=$((tries + 1))
    if [ "$tries" -gt 200 ]; then
      echo "Timed out waiting for $desc (see $file)"
      return 1
    fi
  done
}

start_svc() {
  # start_svc <name> <envfile> <maven-module>
  local name="$1" envfile="$2" module="$3"
  local pidfile="$LOG_DIR/$name.pid"
  if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
    echo "    $name already running (pid $(cat "$pidfile"))"
    return
  fi
  echo "    starting $name..."
  setsid nohup bash -c "
    set -a; source '$REPO_ROOT/$envfile'; set +a
    exec mvn -pl $module spring-boot:run
  " > "$LOG_DIR/$name.log" 2>&1 < /dev/null &
  echo $! > "$pidfile"
}

stop_svc() {
  local name="$1" pidfile="$LOG_DIR/$1.pid"
  if [ -f "$pidfile" ]; then
    local pid; pid="$(cat "$pidfile")"
    if kill -0 "$pid" 2>/dev/null; then
      echo "    stopping $name (pgid $pid)..."
      kill -TERM "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
    fi
    rm -f "$pidfile"
  fi
}

patch_backend_env() {
  # Only from-source-loop-specific fixups remain here; host URLs, the dex issuer,
  # the redis hostname, credential paths, JAVA_TOOL_OPTIONS quoting and the
  # -o /actuator/prometheus exposure are now all handled by
  # scripts/setupDevelopmentEnvironment.sh itself.
  #
  # JAVA_TOOL_OPTIONS is inherited by BOTH the `mvn` launcher JVM and the forked
  # spring-boot:run app JVM, so a fixed -Dcom.sun.management.jmxremote.port binds
  # twice and the app JVM dies "Port already in use". Strip those flags for the
  # two forked services (the single-JVM devcontainer keeps them).
  sed -i 's#[[:space:]]*-Dcom\.sun\.management\.jmxremote[^" ]*##g' .envExecutor .envRegistry

  # fromsource-infra.yml runs valkey without a password; the setup script sets the
  # devcontainer redis password. Clear it for the host loop.
  sed -i 's#^TerrakubeRedisPassword=.*#TerrakubeRedisPassword=#' .envApi .envExecutor
}

cmd_up() {
  mkdir -p "$LOG_DIR"

  echo "==> Infra: dex + ldap..."
  docker compose -f "$DEX_COMPOSE" up -d

  if (exec 3<>/dev/tcp/127.0.0.1/6379) 2>/dev/null; then
    exec 3>&- 3<&-
    echo "==> Infra: redis - reusing what's already on localhost:6379"
  else
    echo "==> Infra: redis (valkey)..."
    docker compose -f "$INFRA_COMPOSE" up -d
  fi

  echo "==> Generating env files + OTel wiring + observability backend (setupDevelopmentEnvironment.sh -o)..."
  ./scripts/setupDevelopmentEnvironment.sh -s LOCAL -d H2 -o

  echo "==> Applying from-source-loop fixups (JMX flags, redis password)..."
  patch_backend_env

  echo "==> Restarting dex to load the generated config..."
  docker compose -f "$DEX_COMPOSE" restart dex-service >/dev/null

  echo "==> Ensuring the observability backend is up..."
  docker compose -f "$BACKEND_COMPOSE" up -d

  echo "==> Starting app processes (first run downloads dependencies - can take a few minutes)..."
  start_svc api .envApi api
  start_svc registry .envRegistry registry
  start_svc executor .envExecutor executor

  # The UI's vite/rolldown needs Node >= 20.12 (it imports node:util `styleText`).
  # If nvm is present, switch to a known-good version; otherwise just require that
  # whatever `node` is on PATH is new enough.
  local ui_node="${TERRAKUBE_UI_NODE:-v24.14.1}"
  local nvm_use="export NVM_DIR=\"\$HOME/.nvm\"; [ -s \"\$NVM_DIR/nvm.sh\" ] && . \"\$NVM_DIR/nvm.sh\" && nvm use $ui_node >/dev/null 2>&1; corepack enable >/dev/null 2>&1 || true; node -e 'process.exit((process.versions.node.split(\".\").map(Number)[0]>20)||(process.versions.node.split(\".\").map(Number)[0]==20 && process.versions.node.split(\".\").map(Number)[1]>=12)?0:1)' || { echo \"UI needs Node >= 20.12 (vite/rolldown); found \$(node -v). Install: nvm install $ui_node\" >&2; exit 1; }"

  local pidfile="$LOG_DIR/ui.pid"
  if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
    echo "    ui already running (pid $(cat "$pidfile"))"
  else
    echo "    starting ui (node $ui_node)..."
    bash -c "$nvm_use; cd '$REPO_ROOT/ui' && (yarn install --silent || yarn install)"
    setsid nohup bash -c "$nvm_use; cd '$REPO_ROOT/ui' && exec yarn start --port $UI_PORT --host 0.0.0.0" \
      > "$LOG_DIR/ui.log" 2>&1 < /dev/null &
    echo $! > "$pidfile"
  fi

  echo "==> Waiting for services..."
  wait_for "API"      "Started ServerApplication|BUILD FAILURE|APPLICATION FAILED TO START"       "$LOG_DIR/api.log"
  wait_for "Registry" "Started OpenRegistryApplication|BUILD FAILURE|APPLICATION FAILED TO START" "$LOG_DIR/registry.log"
  wait_for "Executor" "Started ExecutorApplication|BUILD FAILURE|APPLICATION FAILED TO START"     "$LOG_DIR/executor.log"
  wait_for "UI"       "ready in|Local:|error when starting dev server"                            "$LOG_DIR/ui.log"

  cmd_status
}

cmd_down() {
  echo "==> Stopping app processes..."
  stop_svc api; stop_svc registry; stop_svc executor; stop_svc ui
  echo "==> Stopping observability backend..."
  docker compose -f "$BACKEND_COMPOSE" down || true
  echo "==> Stopping infra (redis, dex, ldap)..."
  docker compose -f "$INFRA_COMPOSE" down || true
  docker compose -f "$DEX_COMPOSE" down || true
}

cmd_status() {
  echo
  echo "App processes:"
  for s in api registry executor ui; do
    pf="$LOG_DIR/$s.pid"
    if [ -f "$pf" ] && kill -0 "$(cat "$pf")" 2>/dev/null; then
      echo "  $s: running (pid $(cat "$pf"))"
    else
      echo "  $s: not running"
    fi
  done
  echo
  echo "Containers:"
  docker ps --filter "name=dex-service" --filter "name=ldap" --filter "name=terrakube-fromsource-redis" \
    --filter "name=terrakube-observability" --format "table {{.Names}}\t{{.Status}}"
  echo
  echo "URLs:"
  echo "  UI:              http://localhost:$UI_PORT          (admin@example.com / admin)"
  echo "  API:             http://localhost:$API_PORT"
  echo "  Registry:        http://localhost:$REGISTRY_PORT"
  echo "  Dex:             http://localhost:5556/dex"
  echo "  Grafana:         http://localhost:3001              (anonymous admin)"
  echo "  VictoriaMetrics: http://localhost:8428              (PromQL, /api/v1/targets)"
  echo "  Tempo:           http://localhost:3200"
  echo "  VictoriaLogs:    http://localhost:9428"
  echo
  echo "Logs: $LOG_DIR/{api,registry,executor,ui}.log"
}

cmd_logs() {
  local svc="${1:?usage: dev-fromsource.sh logs <api|registry|executor|ui>}"
  tail -f "$LOG_DIR/$svc.log"
}

case "${1:-up}" in
  up) cmd_up ;;
  down) cmd_down ;;
  status) cmd_status ;;
  logs) shift; cmd_logs "$@" ;;
  *) echo "Usage: $0 [up|down|status|logs <service>]"; exit 1 ;;
esac
