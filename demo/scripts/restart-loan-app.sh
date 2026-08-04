#!/usr/bin/env bash
# restart-loan-app.sh — bring loan-application-service back on 8091, alone.
#
#   demo/scripts/restart-loan-app.sh          # the v1 boot jar
#   demo/scripts/restart-loan-app.sh <jar>    # a specific jar
#
# Why this exists: `start-services.sh` starts all four JVMs and refuses to run
# while any of their ports is taken. After scenario 2's `kill -9` — where only
# loan-application died and the other three are still healthy — it aborts on
# 8092/8093/8080. This restarts exactly the one process that died.
#
# Env/JVM options are kept identical to start-services.sh on purpose; if that
# file changes, change this one.
set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$DEMO_DIR/.." && pwd)"
RUN_DIR="$DEMO_DIR/.run"
NAME=loan-application-service
PIDFILE="$RUN_DIR/$NAME.pid"
PORT=8091

log() { printf '%s [demo] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { printf '%s [demo] ERROR: %s\n' "$(date +%H:%M:%S)" "$*" >&2; exit 1; }

JAR="${1:-}"
if [ -z "$JAR" ]; then
  for c in "$REPO_ROOT/maestro-samples/sample-loan-origination/loan-application-service/build/libs/"loan-application-service-*.jar; do
    case "$c" in *-plain.jar) continue;; esac
    [ -f "$c" ] && { JAR="$c"; break; }
  done
fi
[ -n "$JAR" ] && [ -f "$JAR" ] || die "no loan-application jar found — run ./gradlew :maestro-samples:sample-loan-origination:loan-application-service:bootJar"

# Whatever is left of the old process, dead or alive, goes away first.
if [ -f "$PIDFILE" ]; then
  old="$(cat "$PIDFILE")"
  if kill -0 "$old" 2>/dev/null; then
    log "stopping $NAME (pid $old)"
    kill -TERM "$old" 2>/dev/null || true
    for _ in $(seq 1 30); do kill -0 "$old" 2>/dev/null || break; sleep 1; done
    kill -0 "$old" 2>/dev/null && kill -9 "$old" 2>/dev/null || true
  else
    log "pid $old is already gone (this is the kill -9 case)"
  fi
  rm -f "$PIDFILE"
fi
for _ in $(seq 1 30); do
  lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1 || break
  sleep 1
done
lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1 && die "port $PORT still held after 30s"

export POSTGRES_HOST="${POSTGRES_HOST:-localhost}" POSTGRES_PORT="${POSTGRES_PORT:-5433}"
export POSTGRES_USER="${POSTGRES_USER:-maestro}" POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-maestro}"
export VALKEY_HOST="${VALKEY_HOST:-localhost}" VALKEY_PORT="${VALKEY_PORT:-6380}"
export KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:29093}"
export OTEL_EXPORTER_OTLP_ENDPOINT="${OTEL_EXPORTER_OTLP_ENDPOINT:-http://localhost:4318/v1/traces}"
export MAESTRO_ADMIN_EVENTS_ENABLED="${MAESTRO_ADMIN_EVENTS_ENABLED:-true}"
export POSTGRES_DB=loan_application SERVER_PORT="$PORT"

log "starting $NAME from $(basename "$JAR")"
# -Dmaestro.recovery.poll-interval: see the long note in start-services.sh. The
# committed default is 60s; the demo shortens it to 5s so the restarted node
# adopts the parked workflow while the audience is still looking at it. This is
# THE flag that matters on this script — it is the one the crash scenario waits
# on — so it must stay identical to start-services.sh.
java -Xmx256m -XX:+UseSerialGC \
     -Dmanagement.metrics.distribution.percentiles-histogram.maestro.activity.duration=true \
     -Dmaestro.recovery.poll-interval="${DEMO_RECOVERY_POLL_INTERVAL:-5s}" \
     -Dspring.kafka.template.observation-enabled=true \
     -Dspring.kafka.listener.observation-enabled=true \
     -jar "$JAR" >>"$RUN_DIR/$NAME.log" 2>&1 &
pid=$!
echo "$pid" >"$PIDFILE"

for _ in $(seq 1 120); do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://localhost:$PORT/actuator/health" || echo 000)
  [ "$code" = "200" ] && {
    log "$NAME is up — pid $pid, jar $(basename "$JAR"), sha256 $(shasum -a 256 "$JAR" | cut -c1-12)"
    exit 0
  }
  kill -0 "$pid" 2>/dev/null || die "$NAME died during startup — see $RUN_DIR/$NAME.log"
  sleep 1
done
die "$NAME did not turn healthy in 120s — see $RUN_DIR/$NAME.log"
