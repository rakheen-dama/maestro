#!/usr/bin/env bash
# End-to-end scenario driver for the loan-origination sample.
# See SPEC.md ("E2E scenario") for the five scenarios this script covers:
#
#   1. Happy path        — human underwriting approval, co-borrower signs FIRST.
#   2. Out-of-order      — document uploaded BEFORE the application exists
#                          (orphan signal adoption).
#   3. Conditions loop   — CONDITIONS in round 1, extra doc, APPROVED in round 2.
#   4. Withdrawal        — withdrawal after rate lock -> FAILED + rate-lock
#                          compensation visible in the service log.
#   5. Crash recovery    — kill -9 loan-application-service mid-underwriting
#                          wait, restart, deliver the decision -> FUNDED.
#
# Usage:
#   ./e2e/run-e2e.sh                    # full run: infra up, services up, scenarios, teardown
#   E2E_SKIP_BUILD=1 ./e2e/run-e2e.sh   # reuse previously built boot jars
#   E2E_NO_TEARDOWN=1 ./e2e/run-e2e.sh  # leave infra + services running afterwards
#   E2E_REUSE=1 ./e2e/run-e2e.sh        # assume infra + services are already up
#   E2E_CLUSTER=1 ./e2e/run-e2e.sh      # "cluster mode": bring up a SECOND instance
#                                        # of every service (6 processes total) for
#                                        # the whole run, not just during scenario 6.
#                                        # Foundation for multi-node failure scenarios;
#                                        # this task only wires up start/stop plumbing.
#
# Requires: bash, curl, docker compose, and jq (falls back to python3).
#
# ── Port allocation ──────────────────────────────────────────────────────
# No service in this sample configures a separate management/actuator port
# (server.port, bound from SERVER_PORT, is the only port Spring binds), so
# HTTP port is the only thing that must differ between two instances of the
# same service. Second instances keep the same maestro.service-name (same
# Kafka consumer group, same Postgres store, same lock namespace) - only the
# port differs.
#
#   service                        node A (always)   node B (E2E_CLUSTER=1
#                                                      or scenario 6)
#   loan-application-service       8091               8094
#   verification-gateway-service   8092               8095
#   underwriting-service           8093               8096

set -euo pipefail

# ── Locations ────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SAMPLE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$SAMPLE_DIR/../.." && pwd)"
LOG_DIR="$SCRIPT_DIR/logs"
PID_DIR="$LOG_DIR/pids"
mkdir -p "$LOG_DIR" "$PID_DIR"

# ── Configuration ────────────────────────────────────────────────────────
LOAN_URL="http://localhost:8091"
VERIFY_URL="http://localhost:8092"
UW_URL="http://localhost:8093"

# Second-node ports/URLs (see port allocation table above). Scenario 6 always
# runs a SECOND loan-application-service instance; E2E_CLUSTER=1 additionally
# runs second instances of the other two services for the whole run. Same
# service name -> same Kafka consumer group and same Postgres store.
LOAN_PORT_B=8094
VERIFY_PORT_B=8095
UW_PORT_B=8096
LOAN_URL_B="http://localhost:$LOAN_PORT_B"
VERIFY_URL_B="http://localhost:$VERIFY_PORT_B"
UW_URL_B="http://localhost:$UW_PORT_B"
LOAN_NODE_B="loan-application-service-b"
VERIFY_NODE_B="verification-gateway-service-b"
UW_NODE_B="underwriting-service-b"

E2E_SKIP_BUILD="${E2E_SKIP_BUILD:-0}"
E2E_NO_TEARDOWN="${E2E_NO_TEARDOWN:-0}"
E2E_REUSE="${E2E_REUSE:-0}"
E2E_CLUSTER="${E2E_CLUSTER:-0}"

# Verification fan-in takes ~8s real time (appraisal latency); allow slack.
WAIT_PENDING_SECS=90       # create -> underwriting human queue
WAIT_TERMINAL_SECS=90      # decision/signatures -> terminal status
WAIT_RECOVERY_SECS=150     # crash-recovery scenario end-to-end after restart

SERVICES=(loan-application-service verification-gateway-service underwriting-service)

RUN_ID="$(date +%H%M%S)"

# ── Colors ───────────────────────────────────────────────────────────────
if [[ -t 1 ]]; then
    RED=$'\033[0;31m'; GREEN=$'\033[0;32m'; YELLOW=$'\033[0;33m'; BOLD=$'\033[1m'; NC=$'\033[0m'
else
    RED=""; GREEN=""; YELLOW=""; BOLD=""; NC=""
fi

log()  { printf '%s [e2e] %s\n' "$(date '+%H:%M:%S')" "$*"; }
warn() { printf '%s [e2e] %sWARN%s %s\n' "$(date '+%H:%M:%S')" "$YELLOW" "$NC" "$*"; }
err()  { printf '%s [e2e] %sERROR%s %s\n' "$(date '+%H:%M:%S')" "$RED" "$NC" "$*" >&2; }

# ── JSON parsing (jq preferred, python3 fallback) ───────────────────────
if command -v jq >/dev/null 2>&1; then
    HAVE_JQ=1
elif command -v python3 >/dev/null 2>&1; then
    HAVE_JQ=0
    warn "jq not found - falling back to python3 for JSON parsing"
else
    err "Neither jq nor python3 is available; cannot parse JSON responses."
    exit 1
fi

# json_get <json> <dot.path>  -> value, or "null" when absent
json_get() {
    local json="$1" path="$2"
    if [[ "$HAVE_JQ" == 1 ]]; then
        jq -r ".${path} // \"null\"" <<<"$json" 2>/dev/null || echo "null"
    else
        python3 -c '
import json, sys
try:
    obj = json.loads(sys.argv[1])
    for part in sys.argv[2].split("."):
        obj = obj.get(part) if isinstance(obj, dict) else None
        if obj is None:
            break
    print("null" if obj is None else obj)
except Exception:
    print("null")
' "$json" "$path"
    fi
}

# ── HTTP helpers ─────────────────────────────────────────────────────────
# api_post <url> <json-body>  -> body on 2xx, nonzero + message on error
api_post() {
    local url="$1" body="$2" code tmp
    tmp="$(mktemp)"
    code="$(curl -sS -o "$tmp" -w '%{http_code}' \
        -X POST -H 'Content-Type: application/json' -d "$body" "$url" 2>&1)" || {
        err "POST $url failed: $code"; rm -f "$tmp"; return 1; }
    if [[ "$code" -ge 300 ]]; then
        err "POST $url -> HTTP $code: $(cat "$tmp")"
        rm -f "$tmp"
        return 1
    fi
    cat "$tmp"; rm -f "$tmp"
}

# api_get <url>  -> body (empty on 404), nonzero on connection error / >=400
api_get() {
    local url="$1" code tmp
    tmp="$(mktemp)"
    code="$(curl -sS -o "$tmp" -w '%{http_code}' "$url" 2>/dev/null)" || {
        rm -f "$tmp"; return 1; }
    if [[ "$code" == 404 ]]; then rm -f "$tmp"; echo ""; return 0; fi
    if [[ "$code" -ge 400 ]]; then rm -f "$tmp"; return 1; fi
    cat "$tmp"; rm -f "$tmp"
}

# port_in_use <port> - success (0) if something is accepting TCP connections
# on 127.0.0.1:<port>, failure (1) otherwise. No lsof/nc dependency.
port_in_use() {
    (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
}

# assert_port_free <label> <port> - fail loudly instead of letting the JVM
# fail to bind with a much less obvious error later.
assert_port_free() {
    if port_in_use "$2"; then
        err "$1: port $2 is already in use - refusing to start (stale process from a previous run?)"
        return 1
    fi
    return 0
}

# wait_for_http <name> <url> <timeout-secs> - any HTTP response (<500) counts
wait_for_http() {
    local name="$1" url="$2" timeout="$3" deadline=$((SECONDS + $3)) code
    while (( SECONDS < deadline )); do
        code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "$url" 2>/dev/null)" || code=000
        if [[ "$code" =~ ^[1-4][0-9][0-9]$ ]]; then
            log "$name is up (HTTP $code from $url)"
            return 0
        fi
        sleep 1
    done
    err "$name did not respond at $url within ${timeout}s"
    return 1
}

# ── Loan-application API wrappers ────────────────────────────────────────
create_app() { # <id> <borrowersJsonArr> <amount> <income> <propertyValue> <docsJsonArr>
    api_post "$LOAN_URL/applications" \
        "{\"applicationId\":\"$1\",\"borrowerIds\":$2,\"amount\":$3,\"income\":$4,\"propertyValue\":$5,\"requiredDocs\":$6}" \
        >/dev/null
}
upload_doc() { # <id> <docType> <uploadedBy>
    api_post "$LOAN_URL/applications/$1/documents" \
        "{\"docType\":\"$2\",\"uploadedBy\":\"$3\"}" >/dev/null
}
sign_app() { # <id> <signerId>
    api_post "$LOAN_URL/applications/$1/sign" "{\"signerId\":\"$2\"}" >/dev/null
}
withdraw_app() { # <id> <reason>
    api_post "$LOAN_URL/applications/$1/withdraw" "{\"reason\":\"$2\"}" >/dev/null
}
post_decision() { # <loanId> <round> <verdict> <conditionsJsonArr> [senior]
    local endpoint="decision"
    [[ "${5:-}" == "senior" ]] && endpoint="senior-decision"
    api_post "$UW_URL/underwriting/$1/rounds/$2/$endpoint" \
        "{\"verdict\":\"$3\",\"conditions\":$4}" >/dev/null
}
webhook_verification() { # <type> <loanId> <approved>
    api_post "$VERIFY_URL/webhooks/$1/$2" "{\"approved\":$3,\"details\":\"e2e webhook\"}" >/dev/null
}

app_status_json() { api_get "$LOAN_URL/applications/$1"; }

# wait_for_engine_status <appId> <expectedStatus> <timeout>
# Fails fast when a DIFFERENT terminal status is reached.
wait_for_engine_status() {
    local id="$1" expected="$2" timeout="$3" deadline=$((SECONDS + $3)) body status=""
    while (( SECONDS < deadline )); do
        body="$(app_status_json "$id" || echo "")"
        status="$(json_get "$body" status)"
        if [[ "$status" == "$expected" ]]; then return 0; fi
        case "$status" in
            COMPLETED|FAILED|TERMINATED)
                err "Application $id reached terminal status $status (expected $expected). Body: $body"
                return 1 ;;
        esac
        sleep 1
    done
    err "Application $id did not reach $expected within ${timeout}s (last: ${status:-<none>})"
    return 1
}

# wait_for_pending_review <loanId> <round> <timeout>
wait_for_pending_review() {
    local id="$1" round="$2" timeout="$3" deadline=$((SECONDS + $3)) body
    while (( SECONDS < deadline )); do
        body="$(api_get "$UW_URL/underwriting/pending" || echo "")"
        if [[ "$HAVE_JQ" == 1 ]]; then
            if jq -e --arg id "$id" --argjson r "$round" \
                'map(select(.loanId == $id and .round == $r)) | length > 0' \
                <<<"$body" >/dev/null 2>&1; then return 0; fi
        else
            if python3 -c '
import json, sys
rows = json.loads(sys.argv[1] or "[]")
sys.exit(0 if any(r.get("loanId")==sys.argv[2] and r.get("round")==int(sys.argv[3]) for r in rows) else 1)
' "$body" "$id" "$round" 2>/dev/null; then return 0; fi
        fi
        sleep 1
    done
    err "Loan $id round $round never appeared in the underwriting pending queue within ${timeout}s"
    return 1
}

# wait_for_log_line <logfile> <pattern(grep -E)> <timeout>
wait_for_log_line() {
    local file="$1" pattern="$2" timeout="$3" deadline=$((SECONDS + $3))
    while (( SECONDS < deadline )); do
        if grep -qE "$pattern" "$file" 2>/dev/null; then return 0; fi
        sleep 1
    done
    err "Pattern '$pattern' not found in $file within ${timeout}s"
    return 1
}

assert_eq() { # <label> <expected> <actual>
    if [[ "$2" == "$3" ]]; then
        log "  assert OK: $1 = '$2'"
        return 0
    fi
    err "  assert FAILED: $1 - expected '$2', got '$3'"
    return 1
}

# ── Infrastructure ───────────────────────────────────────────────────────
compose() { docker compose --project-directory "$SAMPLE_DIR" "$@"; }

start_infra() {
    log "Starting infrastructure (Postgres/Valkey/Kafka) via docker compose..."
    compose up -d --wait postgres valkey kafka
    compose up -d kafka-init

    log "Waiting for kafka-init to finish creating topics..."
    local cid deadline=$((SECONDS + 120)) state exitcode
    cid="$(compose ps -aq kafka-init)"
    while (( SECONDS < deadline )); do
        state="$(docker inspect -f '{{.State.Status}}' "$cid" 2>/dev/null || echo missing)"
        if [[ "$state" == "exited" ]]; then
            exitcode="$(docker inspect -f '{{.State.ExitCode}}' "$cid")"
            if [[ "$exitcode" == 0 ]]; then
                log "kafka-init completed - all topics created."
                return 0
            fi
            err "kafka-init exited with code $exitcode"; docker logs "$cid" | tail -20; return 1
        fi
        sleep 2
    done
    err "kafka-init did not finish within 120s"; return 1
}

# consumer_group_members <group> - prints the group's current member count
# (0 if the group is missing or not Stable). The --state output's last
# column is #MEMBERS.
consumer_group_members() {
    local group="$1" kafka_cid line
    kafka_cid="$(compose ps -q kafka)"
    line="$(docker exec "$kafka_cid" /opt/kafka/bin/kafka-consumer-groups.sh \
            --bootstrap-server localhost:9092 --describe --group "$group" --state 2>/dev/null \
            | grep "Stable" || true)"
    if [[ -n "$line" ]]; then
        awk '{print $NF}' <<<"$line"
    else
        echo 0
    fi
}

# wait_for_consumer_group <group> <timeout> [min-members] - group Stable with
# at least min-members members (default 1), so messages produced afterwards
# are guaranteed to be consumed (the sample's @KafkaListeners use default
# auto.offset.reset=latest). The member floor matters when a SECOND instance
# joins an already-Stable group: without it the check passes trivially
# before the new members have even started their rebalance.
wait_for_consumer_group() {
    local group="$1" timeout="$2" min_members="${3:-1}" deadline=$((SECONDS + $2)) members
    while (( SECONDS < deadline )); do
        members="$(consumer_group_members "$group")"
        if [[ "$members" =~ ^[0-9]+$ ]] && (( members >= min_members )); then
            log "Kafka consumer group '$group' is stable ($members member(s), needed >=$min_members)."
            return 0
        fi
        sleep 2
    done
    err "Kafka consumer group '$group' not stable with >=$min_members member(s) within ${timeout}s (last: ${members:-0})"
    return 1
}

# ── Service lifecycle ────────────────────────────────────────────────────
service_jar() {
    local jar
    for jar in "$SAMPLE_DIR/$1/build/libs/$1-"*.jar; do
        [[ -f "$jar" && "$jar" != *-plain.jar ]] && { echo "$jar"; return 0; }
    done
    return 1
}

build_services() {
    if [[ "$E2E_SKIP_BUILD" == 1 ]]; then
        log "E2E_SKIP_BUILD=1 - reusing existing boot jars."
    else
        log "Building boot jars (gradle bootJar)..."
        (cd "$REPO_ROOT" && ./gradlew -q \
            :maestro-samples:sample-loan-origination:loan-application-service:bootJar \
            :maestro-samples:sample-loan-origination:verification-gateway-service:bootJar \
            :maestro-samples:sample-loan-origination:underwriting-service:bootJar -x test)
    fi
    local svc
    for svc in "${SERVICES[@]}"; do
        [[ -n "$(service_jar "$svc")" ]] || { err "No boot jar found for $svc"; return 1; }
    done
}

# start_service_instance <service> <port> <instance-name>
# Generic single-process launcher: every service and every second ("-b")
# node routes through this. <service> selects the boot jar (the source
# directory / SERVICES entry); <instance-name> selects the pid/log file
# names, so a second node of the same service gets its own pid/log without
# touching the primary's.
start_service_instance() {
    local svc="$1" port="$2" iname="$3" jar
    jar="$(service_jar "$svc")"
    assert_port_free "$iname" "$port" || return 1
    : >"$LOG_DIR/$iname.log"
    log "Starting $iname (service=$svc) on port $port..."
    SERVER_PORT="$port" java -jar "$jar" >>"$LOG_DIR/$iname.log" 2>&1 &
    echo $! >"$PID_DIR/$iname.pid"
}

# default_port_for <service> - the node-A port baked into each service's
# application.yml (server.port: ${SERVER_PORT:<this value>}).
default_port_for() {
    case "$1" in
        loan-application-service) echo 8091 ;;
        verification-gateway-service) echo 8092 ;;
        underwriting-service) echo 8093 ;;
        *) err "No default port known for service '$1'"; return 1 ;;
    esac
}

start_service() { # <name> - node-A instance; instance name == service name
    local name="$1" port
    port="$(default_port_for "$name")" || return 1
    start_service_instance "$name" "$port" "$name"
}

stop_service() { # <name> [signal]
    local name="$1" sig="${2:-TERM}" pid
    [[ -f "$PID_DIR/$name.pid" ]] || return 0
    pid="$(cat "$PID_DIR/$name.pid")"
    if kill -0 "$pid" 2>/dev/null; then
        kill "-$sig" "$pid" 2>/dev/null || true
        if [[ "$sig" == "TERM" ]]; then
            for _ in $(seq 1 15); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
            kill -KILL "$pid" 2>/dev/null || true
        fi
    fi
    rm -f "$PID_DIR/$name.pid"
}

# assert_distinct_pids <instance-name>... - fail if any two pid files in the
# list resolve to the same PID (cluster-mode sanity check).
assert_distinct_pids() {
    local n pid seen pids=()
    for n in "$@"; do
        [[ -f "$PID_DIR/$n.pid" ]] || { err "Missing pid file for $n"; return 1; }
        pid="$(cat "$PID_DIR/$n.pid")"
        for seen in ${pids[@]+"${pids[@]}"}; do
            [[ "$seen" != "$pid" ]] || { err "$n shares PID $pid with another instance"; return 1; }
        done
        pids+=("$pid")
    done
    log "Distinct PIDs confirmed for: $*"
}

start_all_services() {
    local svc
    for svc in "${SERVICES[@]}"; do
        start_service "$svc"
    done
    wait_for_http "loan-application-service" "$LOAN_URL/applications/__probe__" 120
    wait_for_http "verification-gateway-service" "$VERIFY_URL/webhooks/credit/__probe__" 120
    wait_for_http "underwriting-service" "$UW_URL/underwriting/pending" 120
    # The business-topic listeners must have partitions assigned before the
    # first scenario publishes, or the first requests are silently skipped
    # (default auto.offset.reset=latest on the sample's @KafkaListeners).
    wait_for_consumer_group "verification-gateway" 90
    wait_for_consumer_group "underwriting" 90

    if [[ "$E2E_CLUSTER" == 1 ]]; then
        log "E2E_CLUSTER=1 - starting second instance of each service (6 processes total)..."
        # loan-application's @MaestroSignalListener consumers of the business
        # topics loans.verification.results / loans.underwriting.decisions use
        # base group "maestro-<service-name>" (see resolveBaseConsumerGroup in
        # MaestroSignalListenerBeanPostProcessor); loan node B will join it
        # too, so it needs the same rebalance treatment as the @KafkaListener
        # groups. Ensure it is stable before baselining.
        wait_for_consumer_group "maestro-loan-application" 90

        # Baseline single-node membership per group, so the post-join waits
        # below can demand the count DOUBLES. Without a member floor the
        # re-wait would pass trivially: the group still reports Stable in the
        # window before the new node's consumers begin their rebalance.
        local vg_base uw_base loan_base
        vg_base="$(consumer_group_members verification-gateway)"
        uw_base="$(consumer_group_members underwriting)"
        loan_base="$(consumer_group_members maestro-loan-application)"

        start_loan_node_b || return 1
        start_service_instance verification-gateway-service "$VERIFY_PORT_B" "$VERIFY_NODE_B" || return 1
        start_service_instance underwriting-service "$UW_PORT_B" "$UW_NODE_B" || return 1
        wait_for_http "$VERIFY_NODE_B" "$VERIFY_URL_B/webhooks/credit/__probe__" 120
        wait_for_http "$UW_NODE_B" "$UW_URL_B/underwriting/pending" 120

        # Each joining consumer forces a group rebalance; with the sample's
        # default auto.offset.reset=latest a message published mid-rebalance
        # can be silently skipped (same reason the single-node path waits
        # above). Re-wait until every affected group is Stable with both
        # nodes' members (2x the single-node baseline; the instances run the
        # same jar and config, so membership is symmetric) before any
        # scenario publishes.
        wait_for_consumer_group "verification-gateway" 90 $(( vg_base > 0 ? vg_base * 2 : 2 ))
        wait_for_consumer_group "underwriting" 90 $(( uw_base > 0 ? uw_base * 2 : 2 ))
        wait_for_consumer_group "maestro-loan-application" 90 $(( loan_base > 0 ? loan_base * 2 : 2 ))

        assert_distinct_pids loan-application-service "$LOAN_NODE_B" \
            verification-gateway-service "$VERIFY_NODE_B" \
            underwriting-service "$UW_NODE_B"
    fi
}

TEARDOWN_DONE=0
teardown() {
    [[ "$TEARDOWN_DONE" == 1 ]] && return 0
    TEARDOWN_DONE=1
    if [[ "$E2E_NO_TEARDOWN" == 1 ]]; then
        warn "E2E_NO_TEARDOWN=1 - leaving services and infra running."
        return 0
    fi
    log "Tearing down services and infrastructure..."
    local svc
    for svc in "${SERVICES[@]}"; do stop_service "$svc" || true; done
    # Second nodes: no-op (stop_service returns immediately) when they were
    # never started, so this is safe in both default and cluster mode.
    stop_service "$LOAN_NODE_B" || true
    stop_service "$VERIFY_NODE_B" || true
    stop_service "$UW_NODE_B" || true
    if [[ "$E2E_REUSE" != 1 ]]; then
        compose down -v >/dev/null 2>&1 || true
    fi
}
trap teardown EXIT

# ── Scenario framework ───────────────────────────────────────────────────
RESULT_NAMES=(); RESULT_STATUS=(); RESULT_SECS=()
OVERALL_FAIL=0

run_scenario() { # <name> <function>
    local name="$1" fn="$2" start=$SECONDS rc=0
    printf '\n%s=== SCENARIO: %s ===%s\n' "$BOLD" "$name" "$NC"
    if "$fn"; then rc=0; else rc=1; fi
    local elapsed=$((SECONDS - start))
    RESULT_NAMES+=("$name"); RESULT_SECS+=("$elapsed")
    if [[ $rc == 0 ]]; then
        RESULT_STATUS+=("PASS")
        printf '%sPASS%s %s (%ss)\n' "$GREEN" "$NC" "$name" "$elapsed"
    else
        RESULT_STATUS+=("FAIL")
        OVERALL_FAIL=1
        printf '%sFAIL%s %s (%ss)\n' "$RED" "$NC" "$name" "$elapsed"
    fi
}

# ── Scenario 1: happy path (co-borrower signs FIRST) ────────────────────
# ── Second loan-application node (scenario 6) ───────────────────────────
# Same jar, same maestro.service-name; only the HTTP port differs. That makes
# it a genuine second node of the same service: one consumer group, one store,
# one instance-lock namespace.
start_loan_node_b() {
    # In cluster mode the second loan node is already running for the whole
    # run (started by start_all_services); reuse it rather than trying to
    # bind the same port twice. In default mode this is always a fresh start.
    if [[ -f "$PID_DIR/$LOAN_NODE_B.pid" ]] && kill -0 "$(cat "$PID_DIR/$LOAN_NODE_B.pid")" 2>/dev/null; then
        log "$LOAN_NODE_B already running (cluster mode) - reusing for scenario 6."
    else
        start_service_instance loan-application-service "$LOAN_PORT_B" "$LOAN_NODE_B" || return 1
    fi
    wait_for_http "$LOAN_NODE_B" "$LOAN_URL_B/applications/__probe__" 120
}

upload_doc_via() { # <baseUrl> <id> <docType> <uploadedBy>
    api_post "$1/applications/$2/documents" "{\"docType\":\"$3\",\"uploadedBy\":\"$4\"}" >/dev/null
}
sign_app_via() { # <baseUrl> <id> <signerId>
    api_post "$1/applications/$2/sign" "{\"signerId\":\"$3\"}" >/dev/null
}

scenario_happy_path() {
    local id="e2e-${RUN_ID}-s1"
    # DTI = 400000/100000 = 4.0 -> HUMAN_REVIEW (not <3 auto-approve, not >6 auto-reject)
    create_app "$id" '["alice","bob"]' 400000 100000 650000 '["pay-stub","bank-statement"]' || return 1
    log "Created application $id (2 borrowers, DTI 4.0 -> human review)"

    # Docs can arrive any time - signals persist (signal-before-await).
    upload_doc "$id" "pay-stub" "alice" || return 1
    upload_doc "$id" "bank-statement" "bob" || return 1

    # Verifications take ~8s (appraisal); wait for the human review queue.
    wait_for_pending_review "$id" 1 "$WAIT_PENDING_SECS" || return 1
    log "Round 1 queued for human review - underwriter approves"
    post_decision "$id" 1 "APPROVED" '[]' || return 1

    # Co-borrower signs FIRST (per SPEC), then the primary borrower.
    sign_app "$id" "bob" || return 1
    sign_app "$id" "alice" || return 1

    wait_for_engine_status "$id" COMPLETED "$WAIT_TERMINAL_SECS" || return 1
    local body; body="$(app_status_json "$id")"
    assert_eq "engine status" "COMPLETED" "$(json_get "$body" status)" || return 1
    assert_eq "loan result"   "FUNDED"    "$(json_get "$body" output.status)" || return 1
}

# ── Scenario 2: out-of-order (orphan adoption) ──────────────────────────
scenario_orphan_adoption() {
    local id="e2e-${RUN_ID}-s2"

    # Deliver the ONLY required document BEFORE the application exists.
    # The signal is persisted with no owning instance and adopted at start.
    upload_doc "$id" "tax-return" "carol" || return 1
    log "Uploaded document for $id BEFORE creating the application"

    local body; body="$(app_status_json "$id")"
    [[ -z "$body" ]] || warn "Expected 404 for not-yet-created app, got: $body"

    # DTI = 200000/100000 = 2.0 -> AUTO_APPROVE; no doc uploads after create,
    # so the workflow can ONLY complete if the orphan document was adopted.
    create_app "$id" '["carol"]' 200000 100000 500000 '["tax-return"]' || return 1
    log "Created application $id (auto-approve DTI, pre-delivered doc must be adopted)"

    sign_app "$id" "carol" || return 1

    wait_for_engine_status "$id" COMPLETED "$WAIT_TERMINAL_SECS" || return 1
    body="$(app_status_json "$id")"
    assert_eq "engine status" "COMPLETED" "$(json_get "$body" status)" || return 1
    assert_eq "loan result"   "FUNDED"    "$(json_get "$body" output.status)" || return 1
}

# ── Scenario 3: conditions loop -> approve round 2 ──────────────────────
scenario_conditions_loop() {
    local id="e2e-${RUN_ID}-s3"
    create_app "$id" '["dave"]' 450000 100000 700000 '["pay-stub"]' || return 1
    upload_doc "$id" "pay-stub" "dave" || return 1

    wait_for_pending_review "$id" 1 "$WAIT_PENDING_SECS" || return 1
    log "Round 1 queued - underwriter returns CONDITIONS (proof-of-insurance)"
    post_decision "$id" 1 "CONDITIONS" '["proof-of-insurance"]' || return 1

    # Satisfy the condition -> loan workflow requests underwriting round 2.
    upload_doc "$id" "proof-of-insurance" "dave" || return 1

    wait_for_pending_review "$id" 2 "$WAIT_PENDING_SECS" || return 1
    log "Round 2 queued (conditions loop worked) - underwriter approves"
    post_decision "$id" 2 "APPROVED" '[]' || return 1

    sign_app "$id" "dave" || return 1

    wait_for_engine_status "$id" COMPLETED "$WAIT_TERMINAL_SECS" || return 1
    local body; body="$(app_status_json "$id")"
    assert_eq "engine status" "COMPLETED" "$(json_get "$body" status)" || return 1
    assert_eq "loan result"   "FUNDED"    "$(json_get "$body" output.status)" || return 1
}

# ── Scenario 4: withdrawal after rate lock -> compensation ──────────────
scenario_withdrawal_after_rate_lock() {
    local id="e2e-${RUN_ID}-s4"
    local loan_log="$LOG_DIR/loan-application-service.log"

    create_app "$id" '["erin"]' 500000 100000 800000 '["pay-stub"]' || return 1
    upload_doc "$id" "pay-stub" "erin" || return 1

    wait_for_pending_review "$id" 1 "$WAIT_PENDING_SECS" || return 1
    post_decision "$id" 1 "APPROVED" '[]' || return 1

    # Rate lock must be reserved before the withdrawal for the saga to unwind.
    wait_for_log_line "$loan_log" "Reserved rate lock .* for loan $id " 60 || return 1
    log "Rate lock reserved - borrower withdraws BEFORE signing"
    withdraw_app "$id" "found a better rate" || return 1

    # Gate #2 sits after signature collection (SPEC step 7 ordering), so the
    # signature completes the fan-in and the pre-arrived withdrawal is then
    # consumed instantly at the gate -> saga releases the rate lock.
    sign_app "$id" "erin" || return 1

    wait_for_engine_status "$id" FAILED "$WAIT_TERMINAL_SECS" || return 1
    local body; body="$(app_status_json "$id")"
    assert_eq "engine status" "FAILED" "$(json_get "$body" status)" || return 1

    wait_for_log_line "$loan_log" "COMPENSATION: released rate lock .* for loan $id" 30 || return 1
    log "  assert OK: rate-lock compensation logged"
}

# ── Scenario 5: crash recovery (kill -9 mid-underwriting wait) ──────────
scenario_crash_recovery() {
    local id="e2e-${RUN_ID}-s5"
    local loan_log="$LOG_DIR/loan-application-service.log"

    create_app "$id" '["frank"]' 350000 100000 550000 '["pay-stub"]' || return 1
    upload_doc "$id" "pay-stub" "frank" || return 1

    # Wait until the loan workflow has submitted round 1 and is parked on
    # awaitSignal("underwriting.decision") - the pending queue proves it.
    wait_for_pending_review "$id" 1 "$WAIT_PENDING_SECS" || return 1
    wait_for_log_line "$loan_log" "Requested underwriting round 1 for loan $id" 30 || return 1

    local pid; pid="$(cat "$PID_DIR/loan-application-service.pid")"
    log "kill -9 loan-application-service (pid $pid) mid-underwriting wait"
    kill -KILL "$pid"
    sleep 1
    if kill -0 "$pid" 2>/dev/null; then err "Process $pid survived kill -9"; return 1; fi
    rm -f "$PID_DIR/loan-application-service.pid"

    log "Restarting loan-application-service..."
    start_service "loan-application-service"
    wait_for_http "loan-application-service" "$LOAN_URL/applications/__probe__" 120 || return 1

    # After restart the instance must still be live (recovered), not lost.
    local body status
    body="$(app_status_json "$id")" || return 1
    status="$(json_get "$body" status)"
    log "Post-restart status of $id: $status"
    case "$status" in COMPLETED|FAILED|TERMINATED|null)
        err "Unexpected post-restart status '$status'"; return 1 ;; esac

    log "Delivering the underwriting decision after the restart"
    post_decision "$id" 1 "APPROVED" '[]' || return 1
    sign_app "$id" "frank" || return 1

    wait_for_engine_status "$id" COMPLETED "$WAIT_RECOVERY_SECS" || return 1
    body="$(app_status_json "$id")"
    assert_eq "engine status" "COMPLETED" "$(json_get "$body" status)" || return 1
    assert_eq "loan result"   "FUNDED"    "$(json_get "$body" output.status)" || return 1
}

# ── Log sweep: surface stack traces / errors even in passing runs ───────
sweep_logs() {
    printf '\n%s=== LOG SWEEP (ERROR / stack traces) ===%s\n' "$BOLD" "$NC"
    local svc hits
    for svc in "${SERVICES[@]}"; do
        hits="$(grep -nE ' ERROR |^\tat |Exception:' "$LOG_DIR/$svc.log" 2>/dev/null \
                | grep -vE "LoanWithdrawnException|SignalTimeoutException" | head -15 || true)"
        if [[ -n "$hits" ]]; then
            warn "$svc log contains ERROR/stack-trace lines (first 15 shown):"
            printf '%s\n' "$hits"
        else
            log "$svc log: clean"
        fi
    done
}

# ── Main ─────────────────────────────────────────────────────────────────
# ── Scenario 6: two loan-application nodes ──────────────────────────────
# The production topology: more than one instance of the same service. The
# application is created on node A and then driven ENTIRELY through node B, so
# every signal is ingested by a node that may not own the workflow. It can only
# complete if signals reach the owner (or the owner is adopted) across nodes.
scenario_two_node() {
    local id="e2e-${RUN_ID}-s6"

    # Cluster mode already has node B running for the whole run; only stop it
    # at the end of this scenario if THIS scenario is the one that started it
    # (default mode) - never tear down a node the cluster-mode run still needs.
    local started_node_b=1
    if [[ -f "$PID_DIR/$LOAN_NODE_B.pid" ]] && kill -0 "$(cat "$PID_DIR/$LOAN_NODE_B.pid")" 2>/dev/null; then
        started_node_b=0
    fi

    start_loan_node_b || return 1

    local pid_a pid_b
    pid_a="$(cat "$PID_DIR/loan-application-service.pid")"
    pid_b="$(cat "$PID_DIR/$LOAN_NODE_B.pid")"
    [[ "$pid_a" != "$pid_b" ]] || { err "Both nodes report the same PID $pid_a"; return 1; }
    log "Node A pid=$pid_a, node B pid=$pid_b"

    # DTI = 200000/100000 = 2.0 -> auto-approve, so the run needs no
    # underwriting queue and the only variable under test is cross-node
    # signal delivery.
    create_app "$id" '["erin"]' 200000 100000 500000 '["tax-return"]' || return 1
    log "Created $id on node A; every signal below goes to node B"

    upload_doc_via "$LOAN_URL_B" "$id" "tax-return" "erin" || return 1
    sign_app_via   "$LOAN_URL_B" "$id" "erin" || return 1

    wait_for_engine_status "$id" COMPLETED "$WAIT_TERMINAL_SECS" || return 1

    # Both nodes must agree on the outcome — they share one store.
    local body_a body_b
    body_a="$(api_get "$LOAN_URL/applications/$id")"
    body_b="$(api_get "$LOAN_URL_B/applications/$id")"
    assert_eq "node A engine status" "COMPLETED" "$(json_get "$body_a" status)" || return 1
    assert_eq "node A loan result"   "FUNDED"    "$(json_get "$body_a" output.status)" || return 1
    assert_eq "node B engine status" "COMPLETED" "$(json_get "$body_b" status)" || return 1
    assert_eq "node B loan result"   "FUNDED"    "$(json_get "$body_b" output.status)" || return 1

    # Funding must have happened once, not once per node. The disbursement log
    # line is the observable side effect of the funding saga.
    local disbursed_a disbursed_b total
    disbursed_a="$(grep -c "disburs.*$id" "$LOG_DIR/loan-application-service.log" 2>/dev/null || true)"
    disbursed_b="$(grep -c "disburs.*$id" "$LOG_DIR/$LOAN_NODE_B.log" 2>/dev/null || true)"
    total=$(( ${disbursed_a:-0} + ${disbursed_b:-0} ))
    if (( total > 1 )); then
        err "Loan $id was disbursed $total times across the two nodes (A=$disbursed_a B=$disbursed_b)"
        return 1
    fi
    log "Disbursement side effect observed $total time(s) across both nodes"

    # Identity: both nodes must still be the processes we started.
    kill -0 "$pid_a" 2>/dev/null || { err "Node A ($pid_a) died during the scenario"; return 1; }
    kill -0 "$pid_b" 2>/dev/null || { err "Node B ($pid_b) died during the scenario"; return 1; }

    if [[ "$started_node_b" == 1 ]]; then
        stop_service "$LOAN_NODE_B"
    else
        log "$LOAN_NODE_B was started by cluster mode - leaving it running for the rest of the run."
    fi
}

main() {
    log "Loan-origination E2E run $RUN_ID (logs: $LOG_DIR)"
    if [[ "$E2E_CLUSTER" == 1 ]]; then
        log "E2E_CLUSTER=1 - cluster mode: 6 processes (2 per service)."
    fi

    if [[ "$E2E_REUSE" == 1 ]]; then
        log "E2E_REUSE=1 - assuming infra and services are already running."
    else
        build_services
        start_infra
        start_all_services
    fi

    run_scenario "1. Happy path (co-borrower signs first)"    scenario_happy_path
    run_scenario "2. Out-of-order doc (orphan adoption)"      scenario_orphan_adoption
    run_scenario "3. Conditions loop -> round-2 approval"     scenario_conditions_loop
    run_scenario "4. Withdrawal after rate lock (saga)"       scenario_withdrawal_after_rate_lock
    run_scenario "5. Crash recovery (kill -9 + replay)"       scenario_crash_recovery
    run_scenario "6. Two-node loan-application (multi-node)"  scenario_two_node

    sweep_logs

    printf '\n%s=== RESULTS ===%s\n' "$BOLD" "$NC"
    local i
    for i in "${!RESULT_NAMES[@]}"; do
        local color=$GREEN; [[ "${RESULT_STATUS[$i]}" == FAIL ]] && color=$RED
        printf '%s%-4s%s %-45s %ss\n' "$color" "${RESULT_STATUS[$i]}" "$NC" \
            "${RESULT_NAMES[$i]}" "${RESULT_SECS[$i]}"
    done

    if [[ "$OVERALL_FAIL" == 1 ]]; then
        err "One or more scenarios FAILED."
        exit 1
    fi
    log "${GREEN}All scenarios passed.${NC}"
}

main "$@"
