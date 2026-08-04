#!/usr/bin/env bash
# Starts the four Maestro demo JVMs on the HOST from built boot jars:
#
#   loan-application-service       http://localhost:8091
#   verification-gateway-service   http://localhost:8092
#   underwriting-service           http://localhost:8093
#   maestro-admin                  http://localhost:8080
#
# and, with TWO_NODE=1, a SECOND loan-application-service instance on 8094
# (same maestro.service-name -> same Kafka consumer group, same Postgres store,
# same lock namespace: a genuine peer, not a separate deployment).
#
# WHY HOST JVMS, NOT CONTAINERS: none of these modules ships a Dockerfile, and
# the demo's crash-recovery scenario wants `kill -9 <pid>` against a real host
# process. Under a container runtime "the process died" and "the supervisor
# restarted it" are the same event; on the host they are not. Only
# infrastructure and observability run in containers — see
# demo/docker-compose.yml.
#
# Prerequisite: `docker compose -f demo/docker-compose.yml up -d` first. This
# script blocks on Postgres/Kafka/Valkey/Jaeger being reachable before it
# starts anything, so a forgotten `compose up` fails here rather than as a
# confusing Spring context failure 30 seconds later.
#
# Usage:
#   demo/scripts/start-services.sh              # build jars, start four JVMs
#   DEMO_SKIP_BUILD=1 demo/scripts/start-services.sh   # reuse existing jars
#   TWO_NODE=1 demo/scripts/start-services.sh   # + a second loan-app on 8094
#
# State:
#   demo/.run/<instance>.pid    one pid file per JVM (the runbook's kill target)
#   demo/.run/<instance>.log    that JVM's stdout+stderr
#   demo/targets/loan-application-b.json   Prometheus file-SD entry, written
#                                          only when TWO_NODE=1, removed
#                                          otherwise (keeps a single-node demo
#                                          at zero DOWN targets).
#
# Stop everything with demo/scripts/stop-services.sh.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEMO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$DEMO_DIR/.." && pwd)"
SAMPLE_DIR="$REPO_ROOT/maestro-samples/sample-loan-origination"
RUN_DIR="$DEMO_DIR/.run"
TARGETS_DIR="$DEMO_DIR/targets"
mkdir -p "$RUN_DIR" "$TARGETS_DIR"

DEMO_SKIP_BUILD="${DEMO_SKIP_BUILD:-0}"
TWO_NODE="${TWO_NODE:-0}"

# ── Where the demo stack lives (must match demo/docker-compose.yml) ──────
export POSTGRES_HOST="${POSTGRES_HOST:-localhost}"
export POSTGRES_PORT="${POSTGRES_PORT:-5433}"
export POSTGRES_USER="${POSTGRES_USER:-maestro}"
export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-maestro}"
export VALKEY_HOST="${VALKEY_HOST:-localhost}"
export VALKEY_PORT="${VALKEY_PORT:-6380}"
export KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:29093}"

# Jaeger's OTLP/HTTP receiver. Bound in every service's application.yml to
# Spring Boot 4's `management.opentelemetry.tracing.export.otlp.endpoint`.
# The Boot 3.x property name (`management.otlp.tracing.endpoint`) is read by
# NOTHING on Boot 4 — the exporter bean is never created and no connection is
# ever attempted, which looks exactly like "Jaeger is empty" (task-1-report §3).
export OTEL_EXPORTER_OTLP_ENDPOINT="${OTEL_EXPORTER_OTLP_ENDPOINT:-http://localhost:4318/v1/traces}"

# The loan sample sets maestro.admin.events.enabled=false in application.yml
# (it runs no dashboard). This demo DOES run maestro-admin, so lifecycle
# publishing is turned back on here via relaxed binding rather than by editing
# the sample's committed config.
export MAESTRO_ADMIN_EVENTS_ENABLED="${MAESTRO_ADMIN_EVENTS_ENABLED:-true}"

# `maestro.activity.duration` is a plain Micrometer Timer: by default it
# publishes only _count/_sum/_max, which cannot answer "p95 by activity". This
# asks Micrometer for a percentile HISTOGRAM for that one meter, which is what
# emits the `maestro_activity_duration_seconds_bucket{le=...}` series the
# dashboard's histogram_quantile() panels read. Scoped to the single meter name
# so no other timer's cardinality changes.
ACTIVITY_HISTOGRAM_PROP="-Dmanagement.metrics.distribution.percentiles-histogram.maestro.activity.duration=true"

# Every JVM is capped at 256m of heap: four of these plus seven containers has
# to fit on a laptop.
JVM_OPTS=(-Xmx256m -XX:+UseSerialGC "$ACTIVITY_HISTOGRAM_PROP")

log() { printf '%s [demo] %s\n' "$(date +%H:%M:%S)" "$*"; }
err() { printf '%s [demo] ERROR: %s\n' "$(date +%H:%M:%S)" "$*" >&2; }

# ── Preflight ────────────────────────────────────────────────────────────

require_tcp() {
    local name="$1" host="$2" port="$3"
    if ! nc -z "$host" "$port" >/dev/null 2>&1; then
        err "$name is not reachable at $host:$port."
        err "Bring the demo stack up first:  docker compose -f demo/docker-compose.yml up -d"
        return 1
    fi
    log "$name reachable at $host:$port"
}

preflight() {
    require_tcp Postgres "$POSTGRES_HOST" "$POSTGRES_PORT" || return 1
    require_tcp Valkey   "$VALKEY_HOST"   "$VALKEY_PORT"   || return 1
    require_tcp Kafka    "${KAFKA_BOOTSTRAP%%:*}" "${KAFKA_BOOTSTRAP##*:}" || return 1
    require_tcp Jaeger-OTLP localhost 4318 || return 1
}

assert_port_free() {
    local name="$1" port="$2"
    if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
        err "Port $port is already in use — cannot start $name."
        err "Run demo/scripts/stop-services.sh first."
        return 1
    fi
}

# ── Jars ─────────────────────────────────────────────────────────────────

# jar_for <module-dir> <artifact-prefix>
jar_for() {
    local dir="$1" prefix="$2" jar
    for jar in "$dir/build/libs/$prefix-"*.jar; do
        [[ -f "$jar" && "$jar" != *-plain.jar ]] && { echo "$jar"; return 0; }
    done
    err "No boot jar found under $dir/build/libs (run without DEMO_SKIP_BUILD=1)"
    return 1
}

build_jars() {
    if [[ "$DEMO_SKIP_BUILD" == "1" ]]; then
        log "DEMO_SKIP_BUILD=1 — reusing existing boot jars"
        return 0
    fi
    log "Building boot jars (gradle bootJar)..."
    (cd "$REPO_ROOT" && ./gradlew --quiet \
        :maestro-samples:sample-loan-origination:loan-application-service:bootJar \
        :maestro-samples:sample-loan-origination:verification-gateway-service:bootJar \
        :maestro-samples:sample-loan-origination:underwriting-service:bootJar \
        :maestro-admin:bootJar -x test)
}

# ── Process control ──────────────────────────────────────────────────────

# start_jvm <instance-name> <jar> <port> [KEY=VALUE ...]
start_jvm() {
    local iname="$1" jar="$2" port="$3"
    shift 3
    local extra_env=("$@")
    assert_port_free "$iname" "$port" || return 1
    : >"$RUN_DIR/$iname.log"
    log "Starting $iname on port $port..."
    # Dynamic "${arr[@]}" words are not recognised by bash as env-assignment
    # prefixes (that recognition is lexical), so `env` is required. The
    # ${arr[@]+...} guard is for bash 3.2 (macOS stock /bin/bash), where an
    # empty array's "${arr[@]}" is an unbound-variable error under `set -u`.
    SERVER_PORT="$port" env ${extra_env[@]+"${extra_env[@]}"} \
        java "${JVM_OPTS[@]}" -jar "$jar" >>"$RUN_DIR/$iname.log" 2>&1 &
    echo $! >"$RUN_DIR/$iname.pid"
}

# wait_for_http <name> <url> <timeout-seconds>
wait_for_http() {
    local name="$1" url="$2" deadline=$((SECONDS + $3)) code
    while (( SECONDS < deadline )); do
        code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "$url" 2>/dev/null)" || code=000
        if [[ "$code" =~ ^[1-4][0-9][0-9]$ ]]; then
            log "$name is up (HTTP $code from $url)"
            return 0
        fi
        sleep 1
    done
    err "$name did not respond at $url within $3s — see $RUN_DIR/$name.log"
    return 1
}

# ── Prometheus file-SD for the optional second node ───────────────────────

write_node_b_target() {
    cat >"$TARGETS_DIR/loan-application-b.json" <<'JSON'
[
  {
    "targets": ["host.docker.internal:8094"],
    "labels": { "service": "loan-application-b" }
  }
]
JSON
    log "Wrote Prometheus file-SD target for loan-application node B (8094)"
}

clear_node_b_target() {
    rm -f "$TARGETS_DIR/loan-application-b.json"
}

# ── Main ─────────────────────────────────────────────────────────────────

main() {
    preflight
    build_jars

    local loan_jar verify_jar uw_jar admin_jar
    loan_jar="$(jar_for "$SAMPLE_DIR/loan-application-service" loan-application-service)"
    verify_jar="$(jar_for "$SAMPLE_DIR/verification-gateway-service" verification-gateway-service)"
    uw_jar="$(jar_for "$SAMPLE_DIR/underwriting-service" underwriting-service)"
    admin_jar="$(jar_for "$REPO_ROOT/maestro-admin" maestro-admin)"

    start_jvm loan-application-service "$loan_jar" 8091 "POSTGRES_DB=loan_application"
    start_jvm verification-gateway-service "$verify_jar" 8092 "POSTGRES_DB=verification_gateway"
    start_jvm underwriting-service "$uw_jar" 8093 "POSTGRES_DB=underwriting"
    # maestro-admin reads its own DB name from ADMIN_DB and its Kafka bootstrap
    # from KAFKA_BOOTSTRAP (its application.yml defaults point at the ROOT
    # compose stack's 5432/29092, not this one's).
    start_jvm maestro-admin "$admin_jar" 8080 "ADMIN_DB=maestro_admin"

    if [[ "$TWO_NODE" == "1" ]]; then
        start_jvm loan-application-service-b "$loan_jar" 8094 "POSTGRES_DB=loan_application"
        write_node_b_target
    else
        clear_node_b_target
    fi

    wait_for_http loan-application-service     http://localhost:8091/actuator/health 120
    wait_for_http verification-gateway-service http://localhost:8092/actuator/health 120
    wait_for_http underwriting-service         http://localhost:8093/actuator/health 120
    wait_for_http maestro-admin                http://localhost:8080/actuator/health 120
    if [[ "$TWO_NODE" == "1" ]]; then
        wait_for_http loan-application-service-b http://localhost:8094/actuator/health 120
    fi

    log "All demo services are up. PID files in $RUN_DIR:"
    local p
    for p in "$RUN_DIR"/*.pid; do
        [[ -f "$p" ]] && printf '    %-32s pid %s\n' "$(basename "$p" .pid)" "$(cat "$p")"
    done
    cat <<EOF

  Grafana        http://localhost:3000
  Prometheus     http://localhost:9090
  Jaeger UI      http://localhost:16686
  maestro-admin  http://localhost:8080
  loan-application     http://localhost:8091
  verification-gateway http://localhost:8092
  underwriting         http://localhost:8093
EOF
}

main "$@"
