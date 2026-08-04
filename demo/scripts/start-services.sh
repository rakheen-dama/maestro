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
# It also does not return until the sample's two domain-topic Kafka listeners
# have their partitions assigned, so the caller can publish immediately without
# the first request being silently dropped on a cold broker — see the note
# above DOMAIN_LISTENER_GROUPS.
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

# Every environment variable and JVM option below is shared with
# restart-loan-app.sh, so both read them from one file. Each setting's rationale
# lives there, next to the setting.
# shellcheck source=lib/jvm-env.sh
. "$SCRIPT_DIR/lib/jvm-env.sh"

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

# A TCP probe on the broker port is NOT enough to say Kafka is ready for us.
# The broker binds 29093 as soon as it starts; `kafka-init` creates the eleven
# topics AFTER that, as a separate container. In the window between the two,
# every TCP check here passes and the JVMs start against a broker with no
# topics — and because Maestro never auto-creates topics, they fail with
# UNKNOWN_TOPIC_OR_PARTITION. That window is exactly the first five minutes of
# `docker compose up -d && start-services.sh` on an unfamiliar machine, where
# it looks like the demo itself is broken.
#
# So gate on the topics existing, not on the port answering.
#
# The required list is DERIVED from docker-compose.yml's kafka-init command
# rather than duplicated here: a topic added there can never silently go
# ungated.
required_topics() {
    grep -oE -- '--topic +[A-Za-z0-9._-]+' "$DEMO_DIR/docker-compose.yml" \
        | awk '{print $2}' | sort -u
}

# Asks the BROKER what exists (via the kafka container's own CLI — the demo
# already requires docker compose, and this needs no host-side Kafka tooling).
existing_topics() {
    docker compose -f "$DEMO_DIR/docker-compose.yml" exec -T kafka \
        /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list \
        2>/dev/null | tr -d '\r' | sort -u
}

require_kafka_topics() {
    local timeout=120 deadline=$((SECONDS + 120)) want missing
    want="$(required_topics)"
    if [[ -z "$want" ]]; then
        err "Could not parse any --topic entries out of $DEMO_DIR/docker-compose.yml"
        return 1
    fi
    log "Waiting for $(printf '%s\n' "$want" | wc -l | tr -d ' ') Kafka topics to exist..."
    while (( SECONDS < deadline )); do
        missing="$(comm -23 <(printf '%s\n' "$want") <(existing_topics))"
        if [[ -z "$missing" ]]; then
            log "All Kafka topics present"
            return 0
        fi
        sleep 2
    done
    err "Kafka topics still missing after ${timeout}s:"
    printf '%s\n' "$missing" | while read -r t; do [[ -n "$t" ]] && err "    $t"; done
    err "kafka-init may have failed. Check:  docker compose -f demo/docker-compose.yml logs kafka-init"
    return 1
}

# ── Kafka consumer-group readiness (post-start) ──────────────────────────
#
# `/actuator/health` returning 200 does NOT mean this service can receive
# anything. The two sample services each own a plain `@KafkaListener` on a
# DOMAIN topic:
#
#   verification-gateway  loans.verification.requests   (VerificationRequestListener)
#   underwriting          loans.underwriting.requests   (UnderwritingRequestListener)
#
# Those beans use Spring Boot's default `auto.offset.reset=latest`. On a COLD
# Kafka — i.e. straight after `docker compose down -v`, with no committed
# offsets for the group — "latest" means: everything published before the
# partitions are assigned is skipped and never delivered.
#
# Measured on two independent cold starts, the assignment landed 2.21 s and
# 2.13 s AFTER preflight's throwaway loan had already published its
# verification requests, so the loan parked forever and preflight failed at
# step 7/8, deterministically, both times
# (demo/.evidence/task-6-F1-reproduction-cold-run-2.log).
#
# It never bit on a warm stack because a group with committed offsets never
# consults auto.offset.reset at all — which is exactly why only a presenter
# doing a genuine cold T-30 setup would ever have seen it.
#
# Maestro's own consumers are NOT affected: KafkaMessagingAutoConfiguration
# sets `auto.offset.reset=earliest` explicitly, so the `maestro-*` groups
# cannot lose a record this way. Only these two sample-owned groups can.
#
# The loan sample's own e2e harness gates on the same two groups for the same
# reason (maestro-samples/sample-loan-origination/e2e/run-e2e.sh); this is that
# gate, brought to the demo's start path so preflight, reset.sh and a manual
# start all get it.
DOMAIN_LISTENER_GROUPS=(verification-gateway underwriting)

# consumer_group_members <group> — the group's member count while it is
# Stable; 0 when the group does not exist yet or is mid-rebalance. The
# `--state` output's last column is #MEMBERS.
consumer_group_members() {
    local group="$1" line
    line="$(docker compose -f "$DEMO_DIR/docker-compose.yml" exec -T kafka \
              /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
              --describe --group "$group" --state 2>/dev/null \
            | tr -d '\r' | grep Stable || true)"
    if [[ -n "$line" ]]; then awk '{print $NF}' <<<"$line"; else echo 0; fi
}

# wait_for_consumer_group <group> <timeout> — block until the group is Stable
# with at least one member, i.e. until its partitions are assigned and anything
# published from now on is guaranteed to be delivered.
wait_for_consumer_group() {
    local group="$1" timeout="$2" deadline=$((SECONDS + $2)) members=0
    while (( SECONDS < deadline )); do
        members="$(consumer_group_members "$group")"
        if [[ "$members" =~ ^[0-9]+$ ]] && (( members >= 1 )); then
            log "Kafka consumer group '$group' is stable ($members member(s)) — its partitions are assigned"
            return 0
        fi
        sleep 2
    done
    err "Kafka consumer group '$group' did not become Stable within ${timeout}s (last member count: ${members:-0})."
    err "Until it is, that listener silently skips anything published to its topic"
    err "(the sample's @KafkaListener beans run auto.offset.reset=latest)."
    err "Check the owning service's log under $RUN_DIR/ and:"
    err "    docker compose -f $DEMO_DIR/docker-compose.yml exec -T kafka \\"
    err "      /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group $group --state"
    return 1
}

wait_for_domain_listeners() {
    local g
    for g in "${DOMAIN_LISTENER_GROUPS[@]}"; do
        wait_for_consumer_group "$g" 90 || return 1
    done
}

preflight() {
    require_tcp Postgres "$POSTGRES_HOST" "$POSTGRES_PORT" || return 1
    require_tcp Valkey   "$VALKEY_HOST"   "$VALKEY_PORT"   || return 1
    require_tcp Kafka    "${KAFKA_BOOTSTRAP%%:*}" "${KAFKA_BOOTSTRAP##*:}" || return 1
    require_tcp Jaeger-OTLP localhost 4318 || return 1
    require_kafka_topics || return 1
    assert_all_ports_free || return 1
}

assert_port_free() {
    local name="$1" port="$2"
    if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
        err "Port $port is already in use — cannot start $name."
        err "Run demo/scripts/stop-services.sh first."
        return 1
    fi
}

# Check EVERY port up front. This used to be checked inside start_jvm, one JVM
# at a time: a conflict on 8093 then aborted the script under `set -e` with
# 8091 and 8092 already running and their pid files written, leaving orphan
# JVMs that the next run would trip over too. Fail before anything is started.
assert_all_ports_free() {
    local ok=0 name port
    for name in loan-application-service:8091 verification-gateway-service:8092 \
                underwriting-service:8093 maestro-admin:8080; do
        assert_port_free "${name%%:*}" "${name##*:}" || ok=1
    done
    if [[ "$TWO_NODE" == "1" ]]; then
        assert_port_free loan-application-service-b 8094 || ok=1
    fi
    (( ok == 0 )) || return 1
    log "All service ports are free"
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
        java "${MAESTRO_JVM_OPTS[@]}" -jar "$jar" >>"$RUN_DIR/$iname.log" 2>&1 &
    echo $! >"$RUN_DIR/$iname.pid"
    STARTED_PIDS+=("$!")
    STARTED_NAMES+=("$iname")
}

# Everything this run started, so a later failure can undo it.
STARTED_PIDS=()
STARTED_NAMES=()
STARTUP_COMPLETE=0

# Belt and braces alongside assert_all_ports_free: that stops us starting into a
# conflict, this stops a failure LATER (a service that never turns healthy) from
# leaving the JVMs it did start running, with stale pid files the next run would
# trip over. Only ever touches pids started by this invocation.
cleanup_on_failure() {
    local status=$? i
    (( status == 0 )) && return 0
    (( STARTUP_COMPLETE == 1 )) && return 0
    (( ${#STARTED_PIDS[@]} == 0 )) && return 0
    err "Startup failed — stopping the ${#STARTED_PIDS[@]} JVM(s) this run started"
    for i in "${!STARTED_PIDS[@]}"; do
        if kill -0 "${STARTED_PIDS[$i]}" 2>/dev/null; then
            err "    stopping ${STARTED_NAMES[$i]} (pid ${STARTED_PIDS[$i]})"
            kill "${STARTED_PIDS[$i]}" 2>/dev/null || true
        fi
        rm -f "$RUN_DIR/${STARTED_NAMES[$i]}.pid"
    done
}
trap cleanup_on_failure EXIT

# wait_for_http <name> <url> <timeout-seconds>
#
# Only 2xx counts as up. This used to accept anything in 1xx–4xx, which meant a
# 404 from an actuator endpoint that was never exposed read as "healthy" and the
# script announced a service that could not answer the very check it was being
# asked for. 503 is the case that makes the narrow window right rather than
# merely stricter: Spring Boot's health endpoint answers 503 while components are
# still coming up, so requiring 2xx keeps waiting through startup instead of
# declaring victory on the first response of any kind.
wait_for_http() {
    local name="$1" url="$2" deadline=$((SECONDS + $3)) code
    while (( SECONDS < deadline )); do
        code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "$url" 2>/dev/null)" || code=000
        if [[ "$code" =~ ^2[0-9][0-9]$ ]]; then
            log "$name is up (HTTP $code from $url)"
            return 0
        fi
        sleep 1
    done
    err "$name did not respond 2xx at $url within $3s (last HTTP $code) — see $RUN_DIR/$name.log"
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

    # HTTP health is not consumer-group membership — see the long note above
    # DOMAIN_LISTENER_GROUPS. Nothing may publish to a domain topic until the
    # listener that owns it has its partitions.
    wait_for_domain_listeners || return 1

    STARTUP_COMPLETE=1
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
