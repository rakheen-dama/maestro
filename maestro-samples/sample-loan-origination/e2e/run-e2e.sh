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
#   7. Owner-kill adoption — kill -9 the OWNER node mid-underwriting wait and
#                          never restart it; the peer node alone adopts and
#                          completes the workflow (recovery poller + instance
#                          lock TTL). Unlike scenario 5, the owner never
#                          comes back. Runs second-to-last (see main()).
#   8. Rolling restart    — graceful SIGTERM (not kill -9) of node A with
#                          THREE workflows parked in distinct WAITING_SIGNAL
#                          states (awaiting docs / awaiting underwriting
#                          decision / awaiting signatures); asserts node A's
#                          own log shows the graceful-shutdown path leaving
#                          them WAITING_SIGNAL (never FAILED); node B serves a
#                          status read and ingests a signal while node A is
#                          down; node A restarts and all three reach
#                          COMPLETED with zero FAILED/compensation. Runs LAST
#                          (see main()) for the same reason as scenario 7.
#   9. Timer-poller leader failover — kill -9 the ELECTED timer-poller
#                          leader among two verification-gateway nodes while
#                          a workflow.sleep()-backed verification is mid-sleep
#                          on the OTHER (surviving, non-leader) node; asserts
#                          the durable timer still fires and the verification
#                          -> loan progresses within leader TTL + poll
#                          interval + wake-recheck. Leader identity proven via
#                          a DEBUG-log follower count BEFORE the kill.
#  10. Cross-node admin retry/terminate — drives a loan to FAILED without
#                          compensation (uncaught SignalTimeoutException at
#                          the underwriting-decision await) while owned by
#                          node A; kills node A; issues $maestro:retry via a
#                          raw Kafka publish (mirrors AdminCommandService)
#                          with only node B alive, delivers the decision
#                          promptly, and asserts completion + a
#                          WORKFLOW_RETRIED lifecycle event on the admin
#                          events topic. Also terminates a second, still-
#                          active node-A-owned workflow the same way and
#                          asserts WORKFLOW_TERMINATED. See task-4-report.md
#                          for the admin-transport decision (no maestro-admin
#                          in this compose; raw Kafka publish instead).
#
# Usage:
#   ./e2e/run-e2e.sh                    # full run: infra up, services up, scenarios, teardown
#   E2E_SKIP_BUILD=1 ./e2e/run-e2e.sh   # reuse previously built boot jars
#   E2E_NO_TEARDOWN=1 ./e2e/run-e2e.sh  # leave infra + services running afterwards
#   E2E_REUSE=1 ./e2e/run-e2e.sh        # assume infra + services are already up
#   E2E_ONLY="7" ./e2e/run-e2e.sh       # run only the listed scenario number(s)
#                                        # (space-separated); unset = all.
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
# E2E_ONLY: space-separated scenario numbers to run (e.g. E2E_ONLY="7", or
# E2E_ONLY="1 2 3"). Unset/empty = run all scenarios (default behaviour
# unchanged). Skipped scenarios do not appear in the results table.
E2E_ONLY="${E2E_ONLY:-}"

# Verification fan-in takes ~8s real time (appraisal latency); allow slack.
WAIT_PENDING_SECS=90       # create -> underwriting human queue
WAIT_TERMINAL_SECS=90      # decision/signatures -> terminal status
WAIT_RECOVERY_SECS=150     # crash-recovery scenario end-to-end after restart

# Owner-kill peer-adoption bound: default maestro.lock.ttl=30s (instance lock
# expiry) + default maestro.recovery.poll-interval=60s (worst case the peer's
# poller just missed a cycle) + slack for query/JVM overhead. Overridable so
# the "prove it can fail" run can pass an absurdly short bound without
# touching the kill/adoption logic itself.
WAIT_ADOPTION_SECS="${WAIT_ADOPTION_SECS:-150}"

# Scenario 8 (rolling restart): dedicated SIGTERM grace period for
# stop_service_graceful — default maestro.shutdown.timeout=30s (drains
# in-flight workflows) + slack for JVM/Spring-context-close overhead
# (Kafka listener containers, DataSource pool, etc.). Deliberately NOT the
# shared stop_service()'s 15s teardown wait: 15s is fine for teardown (which
# falls back to KILL and doesn't care), but scenario 8 exists to PROVE a real
# graceful shutdown drained parked workflows without failing them, so it must
# never silently escalate to KILL — see stop_service_graceful.
WAIT_SHUTDOWN_GRACE_SECS="${WAIT_SHUTDOWN_GRACE_SECS:-40}"
# Scenario 8's "zero FAILED among the deploy-window workflows" assertion,
# expressed as an expected count so the prove-it-can-fail run can demand a
# wrong count (e.g. 1) without touching the scenario's kill/restart logic.
EXPECT_FAILED_COUNT="${EXPECT_FAILED_COUNT:-0}"

# Scenario 9 (timer-poller leader failover): TimerPoller.LEADER_TTL (15s,
# hardcoded in maestro-core) + maestro.timer.poll-interval (5s default,
# unchanged by this sample) + this sample's maestro.signal.wake-recheck-
# interval (5s — sleep() rechecks the durable timer row on that cadence,
# Issue 17) + slack for JVM/election overhead. Overridable so the "prove it
# can fail" run can demand an absurdly short bound without touching the
# kill/election logic itself.
WAIT_TIMER_FAILOVER_SECS="${WAIT_TIMER_FAILOVER_SECS:-60}"
# How many fresh loan applications scenario 9 will try before giving up on
# finding one whose verification workflows land on the NON-leader vg node.
# "loans.verification.requests" (3 partitions) is keyed by loanId
# (KafkaLoanMessagingActivities), so a given loan's ownership is deterministic
# but which vg node that is is not something this harness controls directly.
# A 2-consumer range assignment typically splits 2:1, so the "wrong" 2/3 of
# partitions has an ~(2/3)^n chance of being hit n times running (n=8 -> 4%,
# observed once in development) - 20 pushes that below 0.04%.
OWNER_SEARCH_MAX_ATTEMPTS="${OWNER_SEARCH_MAX_ATTEMPTS:-20}"

# Scenario 10 (cross-node admin retry/terminate): short decision-timeout
# override applied ONLY to node A, so the retry-target workflow reaches
# FAILED (an uncaught SignalTimeoutException at the underwriting-decision
# await — LoanApplicationWorkflow.awaitDecisionForRound, never caught by the
# workflow) within seconds instead of the SPEC default 10 minutes. Node B
# keeps the SPEC default (10m) so the retried run has ample time to receive
# the decision this harness delivers promptly after issuing $maestro:retry.
RETRY_DECISION_TIMEOUT="${RETRY_DECISION_TIMEOUT:-PT8S}"
WAIT_FAILURE_SECS="${WAIT_FAILURE_SECS:-30}"
# Observed empirically: a $maestro:* command published to a Kafka partition
# that belonged to the now-dead node A is NOT delivered to node B until the
# "maestro-loan-application" consumer group's coordinator evicts node A's
# session and reassigns its partitions — bounded by the Kafka client's
# session.timeout.ms (default 45s, unconfigured by this sample), not by
# anything in Maestro. First-command latency is therefore up to ~45s worst
# case (a message on a partition B did not already own); every command AFTER
# that rebalance has already happened is near-instant. Generous slack on top.
WAIT_ADMIN_COMMAND_SECS="${WAIT_ADMIN_COMMAND_SECS:-90}"

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
# Every curl is bounded (--max-time 10): without it, one hung connection
# blows straight through the wait_for_* deadlines (observed: a 90s-bounded
# wait ran 1003s during a host freeze). wait_for_http already bounds at 2s.

# api_post <url> <json-body>  -> body on 2xx, nonzero + message on error
api_post() {
    local url="$1" body="$2" code tmp
    tmp="$(mktemp)"
    code="$(curl -sS --max-time 10 -o "$tmp" -w '%{http_code}' \
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
    code="$(curl -sS --max-time 10 -o "$tmp" -w '%{http_code}' "$url" 2>/dev/null)" || {
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
# app_status_json_via <baseUrl> <id> - same as app_status_json but against an
# explicit node. Needed whenever node A (the $LOAN_URL default) may be dead,
# e.g. scenario 7 after the kill.
app_status_json_via() { api_get "$1/applications/$2"; }

# wait_for_engine_status <appId> <expectedStatus> <timeout>
# Fails fast when a DIFFERENT terminal status is reached.
wait_for_engine_status() {
    wait_for_engine_status_via "$LOAN_URL" "$1" "$2" "$3"
}

# wait_for_engine_status_via <baseUrl> <appId> <expectedStatus> <timeout>
# Same as wait_for_engine_status, polling an explicit node instead of the
# hardcoded node-A default - required once node A may be dead (scenario 7).
wait_for_engine_status_via() {
    local url="$1" id="$2" expected="$3" timeout="$4" deadline=$((SECONDS + $4)) body status=""
    while (( SECONDS < deadline )); do
        body="$(app_status_json_via "$url" "$id" || echo "")"
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
#
# The pending queue (GET /underwriting/pending) is served from
# PendingReviewRegistry — a documented best-effort NODE-LOCAL in-memory view,
# populated only on the node whose Kafka consumer happened to receive the
# review request. In cluster mode the review can therefore surface on either
# underwriting instance, so BOTH are polled. The decision endpoints do NOT
# need the same routing: POST .../decision only signals the
# underwriting-{loanId}-round{n} workflow via MaestroClient (store-backed,
# node-agnostic), so it works from any node.
wait_for_pending_review() {
    local id="$1" round="$2" timeout="$3" deadline=$((SECONDS + $3)) body url
    local urls=("$UW_URL")
    [[ "$E2E_CLUSTER" == 1 ]] && urls+=("$UW_URL_B")
    while (( SECONDS < deadline )); do
        for url in "${urls[@]}"; do
            body="$(api_get "$url/underwriting/pending" || echo "")"
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
        done
        sleep 1
    done
    err "Loan $id round $round never appeared in any underwriting pending queue within ${timeout}s (polled: ${urls[*]})"
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

# psql_loan <sql> - runs SQL against loan-application-service's own Postgres
# database (compose service "postgres", database "loan_application" - see
# loan-application-service/src/main/resources/application.yml) via the
# compose Postgres container, tuples-only/unaligned so callers can parse
# pipe-separated columns directly.
psql_loan() {
    compose exec -T postgres psql -U maestro -d loan_application -tAc "$1"
}

# psql_verify <sql> - same as psql_loan but against verification-gateway's
# own database ("verification_gateway" - see verification-gateway-service's
# application.yml). Used by scenario 9 to inspect maestro_workflow_timer
# directly.
psql_verify() {
    compose exec -T postgres psql -U maestro -d verification_gateway -tAc "$1"
}

# assert_event_log_integrity <workflowId> <expected-missing-csv> - queries
# Postgres DIRECTLY (not through the engine) for the instance's event log
# and asserts:
#   1. no duplicate (workflow_instance_id, sequence_number) pairs
#      (COUNT(*) == COUNT(DISTINCT sequence_number));
#   2. the SET of missing sequence numbers below the terminal event is
#      EXACTLY <expected-missing-csv> (ascending CSV, e.g. "9,16"; "" =
#      none). Set equality, not just gap COUNT: a regression that dropped
#      one designed gap while introducing an unrelated real gap would net
#      the same cardinality and slip past a count-only check.
#   3. the event at MAX(sequence_number) is WORKFLOW_COMPLETED (so "below
#      the terminal event" genuinely covers the whole log).
#
# Why gaps are expected at all: SignalManager.awaitSignal allocates its
# sequence number at entry (ctx.nextSequence(), SignalManager.java:228) and,
# when the await TIMES OUT, throws SignalTimeoutException (line 279) WITHOUT
# appending any event at that sequence - a timed-out await deliberately
# consumes a sequence and leaves a hole. LoanApplicationWorkflow's two
# withdrawal gates (checkWithdrawalGate: awaitSignal("loan.withdrawn"),
# maestro.sample.gate-timeout=1s) both time out on every FUNDED path, so a
# funded loan has exactly those two holes by design - at deterministic
# positions, because sequence layout is a pure function of the workflow's
# deterministic step order. Verified against baseline: every COMPLETED loan
# instance from scenarios 1/2/3/5/6 (no owner kill involved) shows the same
# designed-gap pattern with this exact query.
# (The workflow has no parallel branches, so no per-branch sequence bands.)
#
# Optional 3rd arg <expected-terminal-type> (default WORKFLOW_COMPLETED):
# lets a non-completed instance (e.g. scenario 10's TERMINATED-via-admin-
# command workflow, which appends NO event of its own — see
# WorkflowExecutor#terminateWorkflow's Javadoc, "No memoization-log event")
# pass an empty string to skip the terminal-event-type check entirely and
# get just the duplicate/gap checks.
assert_event_log_integrity() {
    local wfid="$1" expected_missing="$2" expected_terminal="${3-WORKFLOW_COMPLETED}"
    local instance_id status event_seq_col counts
    local total distinct_seq min_seq max_seq missing terminal_type

    instance_id="$(psql_loan "SELECT id FROM maestro_workflow_instance WHERE workflow_id = '$wfid';" | tr -d '[:space:]')"
    if [[ -z "$instance_id" ]]; then
        err "SQL: no maestro_workflow_instance row found for workflow_id='$wfid'"
        return 1
    fi
    status="$(psql_loan "SELECT status FROM maestro_workflow_instance WHERE id = '$instance_id';" | tr -d '[:space:]')"
    event_seq_col="$(psql_loan "SELECT event_sequence FROM maestro_workflow_instance WHERE id = '$instance_id';" | tr -d '[:space:]')"
    log "SQL: maestro_workflow_instance id=$instance_id workflow_id=$wfid status=$status event_sequence=$event_seq_col"

    log "SQL> SELECT COUNT(*), COUNT(DISTINCT sequence_number), MIN(sequence_number), MAX(sequence_number) FROM maestro_workflow_event WHERE workflow_instance_id = '$instance_id';"
    counts="$(psql_loan "SELECT COUNT(*), COUNT(DISTINCT sequence_number), MIN(sequence_number), MAX(sequence_number) FROM maestro_workflow_event WHERE workflow_instance_id = '$instance_id';")"
    total="$(awk -F'|' '{print $1}' <<<"$counts" | tr -d '[:space:]')"
    distinct_seq="$(awk -F'|' '{print $2}' <<<"$counts" | tr -d '[:space:]')"
    min_seq="$(awk -F'|' '{print $3}' <<<"$counts" | tr -d '[:space:]')"
    max_seq="$(awk -F'|' '{print $4}' <<<"$counts" | tr -d '[:space:]')"
    log "SQL: result -> total=$total distinct_sequences=$distinct_seq min_seq=$min_seq max_seq=$max_seq"

    assert_eq "no duplicate (workflow_instance_id, sequence_number)" "$total" "$distinct_seq" || return 1

    # Assert the exact SET of missing sequences (ascending CSV), not just
    # how many there are: the holes must sit precisely at the timed-out
    # gate awaits, and a real lost event elsewhere must fail even if a
    # count would coincidentally still match.
    log "SQL> SELECT string_agg(s::text, ',' ORDER BY s) FROM generate_series($min_seq, $max_seq) s WHERE NOT EXISTS (SELECT 1 FROM maestro_workflow_event WHERE workflow_instance_id = '$instance_id' AND sequence_number = s);"
    missing="$(psql_loan "SELECT string_agg(s::text, ',' ORDER BY s) FROM generate_series($min_seq, $max_seq) s WHERE NOT EXISTS (SELECT 1 FROM maestro_workflow_event WHERE workflow_instance_id = '$instance_id' AND sequence_number = s);" | tr -d '[:space:]')"
    log "SQL: missing sequence numbers in $min_seq..$max_seq -> ${missing:-<none>}"
    assert_eq "missing-sequence set below terminal event (timed-out gate awaits only)" "$expected_missing" "$missing" || return 1

    if [[ -n "$expected_terminal" ]]; then
        terminal_type="$(psql_loan "SELECT event_type FROM maestro_workflow_event WHERE workflow_instance_id = '$instance_id' AND sequence_number = $max_seq;" | tr -d '[:space:]')"
        assert_eq "terminal event at max sequence $max_seq" "$expected_terminal" "$terminal_type" || return 1
    else
        log "  (skipping terminal-event-type check - expected_terminal='' - e.g. a TERMINATED instance appends no event of its own)"
    fi

    log "  assert OK: event log for $wfid is duplicate-free with exactly the designed timed-out-await gap(s) {${missing:-}} ($total events, sequence $min_seq..$max_seq)"
}

# wait_for_signal_received_count <workflowId> <signalName> <count> <timeout>
#
# Polls Postgres directly for the number of SIGNAL_RECEIVED events recorded
# for <signalName> on this instance (step_name = "$maestro:awaitSignal:
# <signalName>" — SignalManager.awaitSignal.java:229; collectSignals just
# calls awaitSignal in a loop, so the same step_name applies). Needed because
# the engine's WAITING_SIGNAL status is identical across every await in the
# workflow (verification.result, document.uploaded, underwriting.decision,
# package.signed) and does NOT by itself say which one a parked instance is
# on — e.g. LoanApplicationWorkflow parks on verification.result immediately
# after creation, then (once fan-in completes) on document.uploaded, both as
# plain WAITING_SIGNAL. Waiting for "3 verification.result signals consumed"
# is how scenario 8 confirms a workflow with no documents uploaded has moved
# PAST verification fan-in and is now parked specifically on document.uploaded
# (rather than still mid-fan-in).
wait_for_signal_received_count() {
    local wfid="$1" signal="$2" want="$3" timeout="$4" deadline=$((SECONDS + $4))
    local instance_id="" count=""
    while (( SECONDS < deadline )); do
        [[ -z "$instance_id" ]] && instance_id="$(psql_loan "SELECT id FROM maestro_workflow_instance WHERE workflow_id = '$wfid';" | tr -d '[:space:]')"
        if [[ -n "$instance_id" ]]; then
            count="$(psql_loan "SELECT COUNT(*) FROM maestro_workflow_event WHERE workflow_instance_id = '$instance_id' AND event_type = 'SIGNAL_RECEIVED' AND step_name = '\$maestro:awaitSignal:$signal';" | tr -d '[:space:]')"
            [[ "$count" =~ ^[0-9]+$ ]] && (( count >= want )) && return 0
        fi
        sleep 1
    done
    err "wfid=$wfid: SIGNAL_RECEIVED count for '$signal' never reached $want within ${timeout}s (last: ${count:-<no instance row yet>})"
    return 1
}

# stop_service_graceful <name> <grace-secs> - SIGTERM only, no KILL fallback.
#
# stop_service() (teardown's workhorse) escalates to KILL after its wait, by
# design - teardown doesn't care HOW a process dies. Scenario 8 exists
# specifically to prove that a REAL graceful Spring shutdown (not a disguised
# kill) leaves parked workflows WAITING_* instead of FAILED, so silently
# falling back to KILL here would defeat the scenario's own claim: if the
# grace period is too short, this function fails loudly instead of masking
# a slow/hung shutdown as a clean one.
stop_service_graceful() {
    local name="$1" grace="$2" pid i
    [[ -f "$PID_DIR/$name.pid" ]] || { err "No pid file for $name - cannot SIGTERM"; return 1; }
    pid="$(cat "$PID_DIR/$name.pid")"
    kill -0 "$pid" 2>/dev/null || { err "$name (pid $pid) is not running - cannot SIGTERM"; return 1; }
    kill -TERM "$pid" || { err "SIGTERM to $name (pid $pid) failed"; return 1; }
    for (( i = 0; i < grace; i++ )); do
        if ! kill -0 "$pid" 2>/dev/null; then
            rm -f "$PID_DIR/$name.pid"
            log "$name (pid $pid) exited gracefully ${i}s after SIGTERM (grace budget ${grace}s) - no KILL fallback used"
            return 0
        fi
        sleep 1
    done
    err "$name (pid $pid) did NOT exit within ${grace}s of SIGTERM - real Spring shutdown exceeded the grace period (maestro.shutdown.timeout default 30s); refusing to KILL so this is visible instead of disguised as a clean shutdown"
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

# start_service_instance <service> <port> <instance-name> [KEY=VALUE ...]
# Generic single-process launcher: every service and every second ("-b")
# node routes through this. <service> selects the boot jar (the source
# directory / SERVICES entry); <instance-name> selects the pid/log file
# names, so a second node of the same service gets its own pid/log without
# touching the primary's. Any trailing KEY=VALUE args are exported as extra
# environment overrides for JUST this process (Spring Boot relaxed binding,
# e.g. MAESTRO_SAMPLE_DECISION_TIMEOUT=PT8S or
# LOGGING_LEVEL_IO_B2MASH_MAESTRO_CORE_ENGINE=DEBUG) - used by scenarios 9
# and 10 to reconfigure one node without touching the others.
start_service_instance() {
    local svc="$1" port="$2" iname="$3"
    shift 3
    local extra_env=("$@") jar
    jar="$(service_jar "$svc")"
    assert_port_free "$iname" "$port" || return 1
    : >"$LOG_DIR/$iname.log"
    if [[ "${#extra_env[@]}" -gt 0 ]]; then
        log "Starting $iname (service=$svc) on port $port, extra env: ${extra_env[*]}..."
    else
        log "Starting $iname (service=$svc) on port $port..."
    fi
    # NOTE: dynamic "${extra_env[@]}" words are NOT recognized as env-var-
    # assignment prefixes by bash (that recognition is lexical/literal, not
    # value-based) - `env` is required to apply them to the child process.
    SERVER_PORT="$port" env "${extra_env[@]}" java -jar "$jar" >>"$LOG_DIR/$iname.log" 2>&1 &
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

# scenario_selected <num> - true when E2E_ONLY is empty (all scenarios) or
# lists this scenario number.
scenario_selected() {
    [[ -z "$E2E_ONLY" ]] && return 0
    local n
    for n in $E2E_ONLY; do [[ "$n" == "$1" ]] && return 0; done
    return 1
}

run_scenario() { # <name> <function>   (name must start "<num>. ...")
    local name="$1" fn="$2" start=$SECONDS rc=0
    local num="${name%%.*}"
    if ! scenario_selected "$num"; then
        log "E2E_ONLY='$E2E_ONLY' - skipping scenario $num."
        return 0
    fi
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

# start_verify_node_b - mirrors start_loan_node_b for the verification-gateway
# service. Used by scenario 9, which needs TWO vg nodes for timer-poller
# leader election to be meaningful (a lone node is always its own leader).
start_verify_node_b() {
    if [[ -f "$PID_DIR/$VERIFY_NODE_B.pid" ]] && kill -0 "$(cat "$PID_DIR/$VERIFY_NODE_B.pid")" 2>/dev/null; then
        log "$VERIFY_NODE_B already running (cluster mode) - reusing for scenario 9."
    else
        start_service_instance verification-gateway-service "$VERIFY_PORT_B" "$VERIFY_NODE_B" || return 1
    fi
    wait_for_http "$VERIFY_NODE_B" "$VERIFY_URL_B/webhooks/credit/__probe__" 120
}

# determine_timer_leader <log_a> <log_b> <settle_secs> <observe_secs> -
# prints "A" or "B" to stdout: the vg node whose TimerPoller log shows ZERO
# "Another instance holds timer poller leadership" DEBUG lines in a POST-
# SETTLE observation window, while the other shows >=1. REQUIRES both nodes
# started with LOGGING_LEVEL_IO_B2MASH_MAESTRO_CORE_ENGINE=DEBUG (TimerPoller
# logs that line at DEBUG on every poll cycle a non-leader loses the
# trySetLeader race - TimerPoller.java isLeader()).
#
# Two-phase, not "watch from t=0": a fresh restart of both nodes races for
# the SAME election key, and if either process reused a service that just
# held that key (as this scenario's own vg-node-A restart does — see the
# caller), the OLD candidate's entry can still be live with up to the full
# 15s TTL remaining, during which BOTH new candidates lose every attempt —
# observed empirically (both sides logged nonzero follower lines for the
# first ~15s, one settled to zero once it won, the other one never did).
# <settle_secs> (>= LEADER_TTL, 15s) is slept UNCOUNTED first so any stale
# entry fully expires and a winner is decided; only the FOLLOWING
# <observe_secs> (>= 2 poll cycles, 5s each) counts toward the verdict, via a
# "new lines since the settle mark" diff so leftover pre-settle noise on the
# eventual winner cannot cause a false "both nonzero" verdict.
#
# CAUTION for callers: this function is invoked as `leader="$(determine_
# timer_leader ...)"` — a command substitution — so every diagnostic line
# it emits MUST go to stderr (log()/warn() default to stdout and would
# otherwise silently splice themselves into $leader, corrupting every later
# `[[ "$leader" == "A" ]]` string comparison; caught in development by
# noticing $leader always fell through to the "else" branch).
determine_timer_leader() {
    local log_a="$1" log_b="$2" settle_secs="$3" observe_secs="$4"
    local pattern="Another instance holds timer poller leadership for service 'verification-gateway'"
    log "Waiting ${settle_secs}s for the timer-poller election to settle (any stale leader-key entry to expire)..." >&2
    sleep "$settle_secs"
    local mark_a mark_b
    mark_a="$(wc -l < "$log_a" | tr -d '[:space:]')"
    mark_b="$(wc -l < "$log_b" | tr -d '[:space:]')"
    log "Observing ${observe_secs}s post-settle (marks: A@line$mark_a, B@line$mark_b)..." >&2
    sleep "$observe_secs"
    local count_a count_b
    count_a="$(tail -n "+$((mark_a + 1))" "$log_a" 2>/dev/null | grep -cF "$pattern" || true)"
    count_b="$(tail -n "+$((mark_b + 1))" "$log_b" 2>/dev/null | grep -cF "$pattern" || true)"
    count_a="${count_a:-0}"; count_b="${count_b:-0}"
    log "Post-settle follower-line counts: A=$count_a B=$count_b" >&2
    if (( count_a > 0 && count_b == 0 )); then echo "B"; return 0; fi
    if (( count_b > 0 && count_a == 0 )); then echo "A"; return 0; fi
    err "Could not determine a single timer-poller leader post-settle (follower-line counts: A=$count_a B=$count_b) - both zero means neither lost an election in the window (increase observe_secs), both nonzero means leadership flapped (increase settle_secs)"
    return 1
}

# kafka_produce_signal <topic> <workflowId> <json-value> - publishes a single
# record keyed by workflowId via the Kafka container's console producer.
# This is EXACTLY the wire format AdminCommandService.sendSignal produces
# (KafkaTemplate<String, byte[]>.send(topic, workflowId, jacksonBytes) of a
# SignalMessage) minus running the maestro-admin service itself, which this
# sample's compose does not include - see task-4-report.md for why. Key
# separator '|' is safe here: workflow ids in this sample never contain '|',
# and kafka-console-producer's LineMessageReader splits on the FIRST
# occurrence only, so a JSON value containing '|' would still be fine.
kafka_produce_signal() {
    local topic="$1" wfid="$2" json="$3" kafka_cid
    kafka_cid="$(compose ps -q kafka)"
    printf '%s|%s\n' "$wfid" "$json" | docker exec -i "$kafka_cid" \
        /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
        --topic "$topic" --property parse.key=true --property key.separator='|'
}

# kafka_read_admin_events <timeout-ms> - bounded read of the admin events
# topic from the beginning, printed to stdout. Safe to call repeatedly across
# runs: every workflow id in this suite is RUN_ID-scoped, so grepping the
# output for a specific id after a --from-beginning read is unambiguous even
# though the topic accumulates events across runs (E2E_REUSE/E2E_NO_TEARDOWN).
kafka_read_admin_events() {
    local timeout_ms="$1" kafka_cid
    kafka_cid="$(compose ps -q kafka)"
    docker exec "$kafka_cid" /opt/kafka/bin/kafka-console-consumer.sh \
        --bootstrap-server localhost:9092 --topic maestro.admin.events \
        --from-beginning --timeout-ms "$timeout_ms" 2>/dev/null || true
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

# ── Scenario 7: owner-kill -> peer adoption ─────────────────────────────
# The claim scenario 5 does NOT make: scenario 5 kill -9's the owner and then
# RESTARTS THE SAME NODE. Here node A (the owner) is kill -9'd mid-underwriting
# wait and NEVER comes back for the rest of this scenario; node B alone must
# adopt the orphaned instance (recovery poller + instance-lock TTL) and drive
# it to FUNDED. Runs LAST in main() - see that function for why.
scenario_owner_kill_adoption() {
    local id="e2e-${RUN_ID}-s7"
    local wfid="loan-$id"
    local loan_log="$LOG_DIR/loan-application-service.log"
    local loan_log_b="$LOG_DIR/$LOAN_NODE_B.log"

    # Pre-flight repair: a PREVIOUS failed scenario-7 run exits before its
    # cleanup step, leaving node A dead (killed, pid file removed). Restore
    # node A first so consecutive runs (E2E_ONLY=7 E2E_REUSE=1 loops) don't
    # inherit a poisoned topology. Within THIS run node A still stays dead
    # from its kill until the assertions pass.
    if ! port_in_use 8091; then
        warn "Node A (port 8091) is not up at scenario start - restoring it (leftover from a previous failed run?)"
        start_service "loan-application-service" || return 1
        wait_for_http "loan-application-service" "$LOAN_URL/applications/__probe__" 120 || return 1
    fi

    # Node B must already be up (same service name -> same consumer group,
    # store, and lock namespace) before the kill, or "adoption" would really
    # just be "cold start of the only surviving node".
    local started_node_b=1
    if [[ -f "$PID_DIR/$LOAN_NODE_B.pid" ]] && kill -0 "$(cat "$PID_DIR/$LOAN_NODE_B.pid")" 2>/dev/null; then
        started_node_b=0
    fi
    start_loan_node_b || return 1

    local pid_a pid_b
    pid_a="$(cat "$PID_DIR/loan-application-service.pid")"
    pid_b="$(cat "$PID_DIR/$LOAN_NODE_B.pid")"
    [[ "$pid_a" != "$pid_b" ]] || { err "Both nodes report the same PID $pid_a"; return 1; }
    log "Node A (owner-to-be) pid=$pid_a, node B (peer) pid=$pid_b"

    # DTI = 400000/100000 = 4.0 -> HUMAN_REVIEW (same shape as scenarios 1/5).
    # Deliberately create via LOAN_URL (node A's port) so node A is the one
    # that starts and parks the instance.
    create_app "$id" '["grace"]' 400000 100000 650000 '["pay-stub"]' || return 1
    upload_doc "$id" "pay-stub" "grace" || return 1
    log "Created $id on node A (port 8091) - node A is the owner-to-be"

    wait_for_pending_review "$id" 1 "$WAIT_PENDING_SECS" || return 1
    wait_for_log_line "$loan_log" "Requested underwriting round 1 for loan $id" 30 || return 1

    # ── Ownership proof BEFORE the kill ─────────────────────────────────
    if grep -qE "Requested underwriting round 1 for loan $id" "$loan_log_b" 2>/dev/null; then
        err "Node B's log ALSO shows 'Requested underwriting round 1' for $id - ownership is ambiguous"
        return 1
    fi
    log "OWNERSHIP PROOF (log): only node A's log ($loan_log) shows 'Requested underwriting round 1 for loan $id'"

    # Extra-credit ownership proof: inspect the Valkey instance-lock key
    # directly. Best-effort - the compose service name / valkey-cli
    # availability aren't asserted elsewhere, so a miss here is a warning,
    # not a scenario failure (the log-based proof above is authoritative).
    local lock_key="maestro:lock:workflow:$wfid" token_before=""
    token_before="$(compose exec -T valkey valkey-cli GET "$lock_key" 2>/dev/null | tr -d '\r')"
    if [[ -n "$token_before" ]]; then
        log "OWNERSHIP PROOF (lock, extra credit): $lock_key = '$token_before' (held pre-kill)"
    else
        warn "Lock inspection: could not read $lock_key via valkey-cli - proceeding on the log-based proof alone"
    fi

    # ── kill -9 node A; do NOT restart it for the rest of this scenario ──
    local kill_epoch
    kill_epoch=$(date +%s)
    log "kill -9 loan-application-service (pid $pid_a, OWNER of $wfid) - will NOT be restarted until after assertions pass"
    kill -KILL "$pid_a"
    sleep 1
    if kill -0 "$pid_a" 2>/dev/null; then err "Process $pid_a survived kill -9"; return 1; fi
    rm -f "$PID_DIR/loan-application-service.pid"

    # Deliver the rest of the workflow's inputs with node A dead. The
    # decision goes through underwriting (never killed, node-agnostic
    # store-backed signal); the signature is routed to node B explicitly
    # per the task's requirement that node B alone drives completion.
    post_decision "$id" 1 "APPROVED" '[]' || return 1
    sign_app_via "$LOAN_URL_B" "$id" "grace" || return 1
    log "Decision + signature delivered with node A dead (signature routed via node B, port $LOAN_PORT_B)"

    log "Waiting for node B to adopt $wfid (bound ${WAIT_ADOPTION_SECS}s = lock TTL 30s + recovery poll-interval 60s + slack)..."
    wait_for_log_line "$loan_log_b" "Resuming workflow '$wfid' \(type=" "$WAIT_ADOPTION_SECS" || return 1
    local adopted_epoch adoption_latency
    adopted_epoch=$(date +%s)
    adoption_latency=$(( adopted_epoch - kill_epoch ))
    log "ADOPTION LATENCY: ${adoption_latency}s (kill -> node B's 'Resuming workflow' log line)"

    # Node A is dead - MUST poll node B (wait_for_engine_status defaults to
    # the node-A-hardcoded $LOAN_URL, which would just fail here).
    wait_for_engine_status_via "$LOAN_URL_B" "$id" COMPLETED "$WAIT_RECOVERY_SECS" || return 1
    local body
    body="$(api_get "$LOAN_URL_B/applications/$id")"
    assert_eq "engine status (via node B)" "COMPLETED" "$(json_get "$body" status)" || return 1
    assert_eq "loan result (via node B)"   "FUNDED"    "$(json_get "$body" output.status)" || return 1

    # Node A must have stayed dead throughout.
    if kill -0 "$pid_a" 2>/dev/null; then
        err "Node A ($pid_a) is alive - this scenario requires it stay dead until after assertions pass"
        return 1
    fi
    log "Node A confirmed dead throughout (never restarted before this line)"

    # Extra-credit lock proof: the lock was reacquired (token changed) by
    # whichever node adopted the instance.
    local token_after
    token_after="$(compose exec -T valkey valkey-cli GET "$lock_key" 2>/dev/null | tr -d '\r')"
    if [[ -n "$token_before" && -n "$token_after" ]]; then
        if [[ "$token_before" != "$token_after" ]]; then
            log "OWNERSHIP PROOF (lock, extra credit): $lock_key token changed '$token_before' -> '$token_after' (reacquired)"
        else
            warn "Lock token unchanged ('$token_after') - unexpected but non-fatal (log-based proof already established adoption)"
        fi
    fi

    # Side-effect count: grep the disbursement line for THIS loan id across
    # BOTH loan-node logs. Case-insensitive substring match on "Disbursed"
    # (SimulatedFundingActivities logs "Disbursed {} for loan {} ..." with a
    # capital D - a lowercase-only "disburs" pattern, as scenario 6 uses,
    # never matches it; that is scenario 6's known gap, not reproduced here).
    local disbursed_a disbursed_b total
    disbursed_a="$(grep -ciE "Disbursed .* for loan $id " "$loan_log" 2>/dev/null || true)"
    disbursed_b="$(grep -ciE "Disbursed .* for loan $id " "$loan_log_b" 2>/dev/null || true)"
    total=$(( ${disbursed_a:-0} + ${disbursed_b:-0} ))
    log "SIDE-EFFECT COUNT: loan $id disbursed A=$disbursed_a B=$disbursed_b total=$total (Issue 11: >1 tolerated only if documented duplication - recorded as observed, not asserted away)"
    if (( total < 1 )); then
        err "Loan $id was never disbursed on either node"
        return 1
    fi

    # Event-log assertions: query Postgres directly. Expected missing set =
    # {9,16}: the two withdrawal-gate awaitSignal("loan.withdrawn") calls
    # time out on every FUNDED path and each consumes a sequence number
    # without appending an event; this scenario's fixed shape (1 borrower,
    # 1 doc, 3 verifications, round-1 approval, 1 signature) puts gate 1 at
    # seq 9 and gate 2 at seq 16 deterministically (see
    # assert_event_log_integrity's header comment).
    assert_event_log_integrity "$wfid" "9,16" || return 1

    # ── Cleanup: restart node A now that the scenario's assertions have
    # passed (the "do not restart" constraint applies only until then), and
    # stop node B if THIS scenario started it - mirrors scenario 6's
    # cluster-mode-aware cleanup so a subsequent run (or E2E_NO_TEARDOWN=1)
    # finds the topology it expects.
    log "Assertions passed - restarting node A (post-scenario cleanup, not required for the scenario's own claim)"
    start_service "loan-application-service"
    wait_for_http "loan-application-service" "$LOAN_URL/applications/__probe__" 120 || return 1

    if [[ "$started_node_b" == 1 ]]; then
        stop_service "$LOAN_NODE_B"
    else
        log "$LOAN_NODE_B was started by cluster mode - leaving it running for the rest of the run."
    fi
}

# ── Scenario 8: rolling restart (graceful SIGTERM mid-flight) ──────────
#
# Task 3 (spec Phase 1 Task C). Unlike scenario 7 (kill -9, owner never
# returns), this scenario SIGTERMs node A - a real Spring/Maestro graceful
# shutdown - with THREE workflows parked in distinct WAITING_SIGNAL states,
# asserts node A's own log proves the graceful path (not a failure), has
# node B read status + ingest a signal while node A is down, then restarts
# node A and drives every workflow to COMPLETED.
#
# Parked-state coverage (see task-3-report.md for the full writeup):
#   - awaiting document.uploaded  (after verification fan-in, before docs)
#   - awaiting underwriting.decision (after doc collection + gate 1)
#   - awaiting package.signed     (after decision + rate-lock reservation)
# All three are WAITING_SIGNAL - the spec's third bucket (parked on a durable
# TIMER) is NOT claimed here: verified in source, SignalManager.awaitSignal's
# timeout (which backs the withdrawal gates AND every await's timeout
# parameter) is a plain in-memory parkingLot.parkWithTimeout with periodic
# store recheck - it never calls WorkflowStore.saveTimer, so no
# maestro_workflow_timer row backs it. Only DefaultWorkflowOperations.sleep()
# creates durable timer rows, and the only sleep() in this sample
# (VerificationWorkflow's simulated provider latency) runs on
# verification-gateway-service, a node this scenario does not touch. Runs
# LAST (see main()) - same reason as scenario 7: it kills node A, the
# primary every earlier scenario addresses via $LOAN_URL.
scenario_rolling_restart() {
    local base="e2e-${RUN_ID}-s8"
    local id_docs="${base}-docs" id_decision="${base}-decision" id_sign="${base}-sign"
    local wf_docs="loan-$id_docs" wf_decision="loan-$id_decision" wf_sign="loan-$id_sign"
    local loan_log="$LOG_DIR/loan-application-service.log"
    local loan_log_b="$LOG_DIR/$LOAN_NODE_B.log"

    # Pre-flight repair: mirrors scenario 7 - a previous failed scenario-8
    # run can exit before restarting node A, leaving it dead for the next run.
    if ! port_in_use 8091; then
        warn "Node A (port 8091) is not up at scenario start - restoring it (leftover from a previous failed run?)"
        start_service "loan-application-service" || return 1
        wait_for_http "loan-application-service" "$LOAN_URL/applications/__probe__" 120 || return 1
    fi

    # Node B must be up BEFORE anything parks on node A, so its status
    # read / signal ingestion during the downtime window is a genuine peer,
    # not a cold start racing the workflow's own creation.
    local started_node_b=1
    if [[ -f "$PID_DIR/$LOAN_NODE_B.pid" ]] && kill -0 "$(cat "$PID_DIR/$LOAN_NODE_B.pid")" 2>/dev/null; then
        started_node_b=0
    fi
    start_loan_node_b || return 1

    local pid_a pid_b
    pid_a="$(cat "$PID_DIR/loan-application-service.pid")"
    pid_b="$(cat "$PID_DIR/$LOAN_NODE_B.pid")"
    [[ "$pid_a" != "$pid_b" ]] || { err "Both nodes report the same PID $pid_a"; return 1; }
    log "Node A (to be SIGTERM'd) pid=$pid_a, node B (peer) pid=$pid_b"

    # ── Bring up 3 workflows on node A, each headed for a distinct
    # WAITING_SIGNAL state. Same shape (1 borrower, 1 required doc "pay-stub",
    # amount/income -> DTI 4.0 -> HUMAN_REVIEW) as scenarios 1/5/7 so the
    # event-log gap assertion below reuses that proven baseline ("9,16").
    # Creates/uploads fired back-to-back so the ~8s verification-fan-in
    # latency overlaps across all three instead of serializing 3x.
    create_app "$id_docs" '["s8a"]' 400000 100000 650000 '["pay-stub"]' || return 1
    log "Created $id_docs (document withheld - will park awaiting document.uploaded)"

    create_app "$id_decision" '["s8b"]' 400000 100000 650000 '["pay-stub"]' || return 1
    upload_doc "$id_decision" "pay-stub" "s8b" || return 1
    log "Created $id_decision (doc uploaded - will park awaiting underwriting.decision)"

    create_app "$id_sign" '["s8c"]' 400000 100000 650000 '["pay-stub"]' || return 1
    upload_doc "$id_sign" "pay-stub" "s8c" || return 1
    log "Created $id_sign (doc uploaded - decision will be approved immediately, then parks awaiting package.signed)"

    wait_for_pending_review "$id_decision" 1 "$WAIT_PENDING_SECS" || return 1
    wait_for_log_line "$loan_log" "Requested underwriting round 1 for loan $id_decision" 30 || return 1
    log "$id_decision parked awaiting underwriting.decision"

    wait_for_pending_review "$id_sign" 1 "$WAIT_PENDING_SECS" || return 1
    post_decision "$id_sign" 1 "APPROVED" '[]' || return 1
    wait_for_log_line "$loan_log" "Reserved rate lock .* for loan $id_sign " 30 || return 1
    log "$id_sign parked awaiting package.signed"

    # Confirms verification fan-in has actually completed (3/3 verification.
    # result signals consumed) - only then is $id_docs genuinely parked on
    # document.uploaded rather than still mid-fan-in (both are WAITING_SIGNAL).
    wait_for_signal_received_count "$wf_docs" "verification.result" 3 "$WAIT_PENDING_SECS" || return 1
    log "$id_docs parked awaiting document.uploaded (verification fan-in confirmed complete: 3/3)"

    local body id
    for id in "$id_docs" "$id_decision" "$id_sign"; do
        body="$(app_status_json "$id")"
        assert_eq "pre-SIGTERM engine status ($id)" "WAITING_SIGNAL" "$(json_get "$body" status)" || return 1
    done

    # ── Graceful SIGTERM of node A, mid-flight, with all 3 parked ───────
    log "SIGTERM node A (pid $pid_a) - 3 workflows parked, grace ${WAIT_SHUTDOWN_GRACE_SECS}s (maestro.shutdown.timeout default 30s + slack)"
    stop_service_graceful "loan-application-service" "$WAIT_SHUTDOWN_GRACE_SECS" || return 1

    # ── Graceful-shutdown evidence, read from node A's OWN log ──────────
    wait_for_log_line "$loan_log" "Maestro graceful shutdown initiated" 5 || return 1
    for id in "$wf_docs" "$wf_decision" "$wf_sign"; do
        wait_for_log_line "$loan_log" "Workflow '$id' suspended by shutdown while WAITING_SIGNAL — left recoverable" 5 || return 1
    done
    wait_for_log_line "$loan_log" "Maestro graceful shutdown complete" 5 || return 1
    log "GRACEFUL-SHUTDOWN EVIDENCE: all 3 parked workflows suspended WAITING_SIGNAL (not FAILED) - node A's log confirms the graceful path, not a kill"

    # ── While node A is down: node B serves a status read AND ingests a
    # signal for an in-flight (node-A-owned) workflow ──────────────────
    body="$(app_status_json_via "$LOAN_URL_B" "$id_decision")"
    local read_status; read_status="$(json_get "$body" status)"
    log "NODE-B STATUS READ (node A down): $id_decision -> $read_status"
    case "$read_status" in
        COMPLETED|FAILED|TERMINATED|null)
            err "Unexpected status '$read_status' for $id_decision read via node B while node A is down"; return 1 ;;
    esac

    upload_doc_via "$LOAN_URL_B" "$id_docs" "pay-stub" "s8a" || return 1
    log "NODE-B SIGNAL INGESTED (node A down): document.uploaded for $id_docs"

    kill -0 "$pid_a" 2>/dev/null && { err "Node A ($pid_a) is alive during the 'node A down' window - it must stay down until the restart step"; return 1; }
    log "Node A confirmed dead throughout the downtime window"

    # ── Restart node A ───────────────────────────────────────────────
    log "Restarting node A..."
    start_service "loan-application-service" || return 1
    wait_for_http "loan-application-service" "$LOAN_URL/applications/__probe__" 120 || return 1

    # ── Drive all three workflows to COMPLETED ──────────────────────
    wait_for_pending_review "$id_docs" 1 "$WAIT_RECOVERY_SECS" || return 1
    post_decision "$id_docs" 1 "APPROVED" '[]' || return 1
    sign_app "$id_docs" "s8a" || return 1

    post_decision "$id_decision" 1 "APPROVED" '[]' || return 1
    sign_app "$id_decision" "s8b" || return 1

    sign_app "$id_sign" "s8c" || return 1

    for id in "$id_docs" "$id_decision" "$id_sign"; do
        wait_for_engine_status "$id" COMPLETED "$WAIT_RECOVERY_SECS" || return 1
        body="$(app_status_json "$id")"
        assert_eq "engine status ($id)" "COMPLETED" "$(json_get "$body" status)" || return 1
        assert_eq "loan result ($id)"   "FUNDED"    "$(json_get "$body" output.status)" || return 1
    done
    log "All 3 deploy-window workflows reached COMPLETED/FUNDED after node A's restart"

    # ── Zero-FAILED assertion (SQL, independent of the wait loop above) ──
    local failed_count
    failed_count="$(psql_loan "SELECT COUNT(*) FROM maestro_workflow_instance WHERE workflow_id IN ('$wf_docs','$wf_decision','$wf_sign') AND status = 'FAILED';" | tr -d '[:space:]')"
    log "SQL: FAILED count among deploy-window workflows ($wf_docs, $wf_decision, $wf_sign) = $failed_count"
    assert_eq "FAILED count among deploy-window workflows" "$EXPECT_FAILED_COUNT" "$failed_count" || return 1

    # ── Zero-compensation assertion, scoped to THESE 3 ids (scenario 4's
    # own legitimate compensation lines for a DIFFERENT loan id must not
    # false-positive this check when the full suite runs before scenario 8) ──
    local comp_hits=0
    for id in "$id_docs" "$id_decision" "$id_sign"; do
        if grep -qE "COMPENSATION: .* for loan $id( |$)" "$loan_log" "$loan_log_b" 2>/dev/null; then
            comp_hits=$((comp_hits + 1))
            err "Found a COMPENSATION log line for deploy-window workflow $id"
        fi
    done
    assert_eq "compensation log lines attributable to the deploy-window workflows" "0" "$comp_hits" || return 1

    # ── Status/version sanity via SQL (optimistic-locking version advanced,
    # proving the row was genuinely updated across the deploy, not just
    # written once at creation) ─────────────────────────────────────────
    local row st ver
    for id in "$wf_docs" "$wf_decision" "$wf_sign"; do
        row="$(psql_loan "SELECT status, version FROM maestro_workflow_instance WHERE workflow_id = '$id';")"
        st="$(awk -F'|' '{print $1}' <<<"$row" | tr -d '[:space:]')"
        ver="$(awk -F'|' '{print $2}' <<<"$row" | tr -d '[:space:]')"
        log "SQL: $id -> status=$st version=$ver"
        assert_eq "SQL status ($id)" "COMPLETED" "$st" || return 1
        if [[ ! "$ver" =~ ^[0-9]+$ ]] || (( ver < 1 )); then
            err "Suspicious version ($ver) for $id - expected optimistic-locking updates across the deploy window"
            return 1
        fi
    done

    # ── Event-log integrity per instance (same shape as scenarios 1/5/7 ->
    # same designed gap set: the two withdrawal-gate awaitSignal timeouts) ──
    assert_event_log_integrity "$wf_docs"     "9,16" || return 1
    assert_event_log_integrity "$wf_decision" "9,16" || return 1
    assert_event_log_integrity "$wf_sign"     "9,16" || return 1

    if [[ "$started_node_b" == 1 ]]; then
        stop_service "$LOAN_NODE_B"
    else
        log "$LOAN_NODE_B was started by cluster mode - leaving it running for the rest of the run."
    fi
}

# ── Scenario 9: timer-poller leader failover (verification-gateway) ─────
#
# Task 4 (spec Phase 1 Task D), sub-scenario 1. Durable timers in this sample
# only come from workflow.sleep() — VerificationWorkflow's simulated provider
# latency (credit 2s / employment 5s / appraisal 8s, VerificationGateway
# Properties defaults). TimerPoller elects ONE leader per service via
# DistributedLock.trySetLeader(key="maestro:leader:timer-poller:verification
# -gateway", ttl=15s) and only the leader scans+fires due timers. This
# scenario kills the ELECTED LEADER — not necessarily the node that started
# the workflow — while a verification is mid-sleep, and asserts the sleep
# still completes via the surviving node's poller (Issue 17's fix: sleep()
# rechecks the durable timer row every wake-recheck-interval, 5s here, so a
# workflow parked on a DIFFERENT node than the timer-poller leader still
# wakes up without needing recovery).
#
# Ownership vs. leadership are deliberately decoupled: "loans.verification.
# requests" is keyed by loanId (KafkaLoanMessagingActivities), so all 3 of
# one loan's verification workflows land on whichever vg node's Kafka
# consumer owns that partition — not something this harness controls
# directly. The scenario proves BOTH identities BEFORE the kill (leader via
# a DEBUG-log follower-line count, owner via the "Started verification
# workflow" log line) and only proceeds once they differ, retrying with a
# fresh loan id otherwise: if leader==owner, killing the leader would ALSO
# kill the workflow's own thread, and recovery would be bounded by the much
# slower recovery poller (scenario 7's mechanism) instead of the timer-
# poller leader TTL — conflating the two instead of isolating this one.
scenario_timer_leader_failover() {
    # Pre-flight repair, same as scenarios 7/8/10: a previous failed run of
    # THIS scenario can exit before its cleanup step, leaving vg node A dead
    # (killed as the elected leader) or the leader-election DEBUG env still
    # applied. Restore node A to a known-good default state first.
    if ! port_in_use "$(default_port_for verification-gateway-service)"; then
        warn "vg node A (port $(default_port_for verification-gateway-service)) is not up at scenario start - restoring it (leftover from a previous failed run?)"
        start_service "verification-gateway-service" || return 1
        wait_for_http "verification-gateway-service" "$VERIFY_URL/webhooks/credit/__probe__" 120 || return 1
    fi

    local started_verify_b=1
    if [[ -f "$PID_DIR/$VERIFY_NODE_B.pid" ]] && kill -0 "$(cat "$PID_DIR/$VERIFY_NODE_B.pid")" 2>/dev/null; then
        started_verify_b=0
    fi
    start_verify_node_b || return 1

    # Package-level, not class-level: Spring Boot's env-var relaxed binding
    # lowercases map keys sourced from SystemEnvironmentPropertySource, so a
    # mixed-case class name like ...ENGINE_TIMERPOLLER never matches the
    # logger named "...engine.TimerPoller" (verified empirically - silently
    # inert, zero DEBUG lines emitted). The package name is already all
    # lowercase, so it maps cleanly; this enables DEBUG for the whole
    # io.b2mash.maestro.core.engine package (chattier, but scoped to this
    # scenario's two short-lived processes).
    local debug_env="LOGGING_LEVEL_IO_B2MASH_MAESTRO_CORE_ENGINE=DEBUG"
    local log_a="$LOG_DIR/verification-gateway-service.log" log_b="$LOG_DIR/$VERIFY_NODE_B.log"
    log "Restarting both verification-gateway nodes with DEBUG timer-poller logging (leader-election evidence)..."
    stop_service verification-gateway-service
    stop_service "$VERIFY_NODE_B"
    start_service_instance verification-gateway-service "$(default_port_for verification-gateway-service)" \
        verification-gateway-service "$debug_env" || return 1
    start_service_instance verification-gateway-service "$VERIFY_PORT_B" "$VERIFY_NODE_B" "$debug_env" || return 1
    wait_for_http "verification-gateway-service" "$VERIFY_URL/webhooks/credit/__probe__" 120 || return 1
    wait_for_http "$VERIFY_NODE_B" "$VERIFY_URL_B/webhooks/credit/__probe__" 120 || return 1
    wait_for_consumer_group "verification-gateway" 90 2 || return 1

    # ── Leader identity proof BEFORE any kill ───────────────────────────
    local leader
    leader="$(determine_timer_leader "$log_a" "$log_b" 17 12)" || return 1
    log "LEADER PROOF (log): vg node $leader shows ZERO 'Another instance holds timer poller leadership' DEBUG lines - it is the elected timer-poller leader for service 'verification-gateway'"

    local leader_key="maestro:leader:timer-poller:verification-gateway" leader_token_1 leader_token_2
    leader_token_1="$(compose exec -T valkey valkey-cli GET "$leader_key" 2>/dev/null | tr -d '\r')"
    sleep 3
    leader_token_2="$(compose exec -T valkey valkey-cli GET "$leader_key" 2>/dev/null | tr -d '\r')"
    if [[ -n "$leader_token_1" && "$leader_token_1" == "$leader_token_2" ]]; then
        log "LEADER PROOF (lock, extra credit): $leader_key stable at '$leader_token_1' across a 3s window - single consistent leader"
    else
        warn "Lock inspection inconclusive ('$leader_token_1' -> '$leader_token_2') - proceeding on the log-based proof alone"
    fi

    local pid_a pid_b
    pid_a="$(cat "$PID_DIR/verification-gateway-service.pid")"
    pid_b="$(cat "$PID_DIR/$VERIFY_NODE_B.pid")"
    [[ "$pid_a" != "$pid_b" ]] || { err "Both vg nodes report the same PID $pid_a"; return 1; }

    local leader_iname
    if [[ "$leader" == "A" ]]; then
        leader_iname="verification-gateway-service"
    else
        leader_iname="$VERIFY_NODE_B"
    fi
    local leader_pid; leader_pid="$(cat "$PID_DIR/$leader_iname.pid")"

    # ── Owner identity proof BEFORE the kill: find a loan whose verification
    # workflows land on the NON-leader node (retry with a fresh id otherwise;
    # a leader==owner loan is simply abandoned - see scenario header comment) ──
    local id_target="" owner_log="" attempt=0 found=0 matched
    while (( attempt < OWNER_SEARCH_MAX_ATTEMPTS )); do
        attempt=$((attempt + 1))
        # $RANDOM suffix (not just the attempt counter) so a run of near-
        # identical sequential ids can't structurally hash to the same
        # partition every time - see OWNER_SEARCH_MAX_ATTEMPTS above.
        local id="e2e-${RUN_ID}-s9-${attempt}-${RANDOM}"
        create_app "$id" '["s9"]' 400000 100000 650000 '["pay-stub"]' || return 1

        matched=""
        local deadline=$((SECONDS + 5))
        while (( SECONDS < deadline )); do
            if grep -qF "Started verification workflow 'verification-$id-appraisal'" "$log_a" 2>/dev/null; then matched="A"; break; fi
            if grep -qF "Started verification workflow 'verification-$id-appraisal'" "$log_b" 2>/dev/null; then matched="B"; break; fi
            sleep 1
        done
        if [[ -z "$matched" ]]; then
            err "verification-$id-appraisal never started on either vg node within 5s"
            return 1
        fi
        if [[ "$matched" != "$leader" ]]; then
            found=1; id_target="$id"
            [[ "$matched" == "A" ]] && owner_log="$log_a" || owner_log="$log_b"
            log "OWNERSHIP PROOF (log): verification-$id_target-appraisal started on node $matched, the NON-leader node (leader=$leader) - attempt $attempt/$OWNER_SEARCH_MAX_ATTEMPTS"
            break
        fi
        warn "Attempt $attempt: verification-$id-appraisal landed on the LEADER node ($matched) - abandoning $id (left running; harmless test debris, never asserted on) and trying a fresh loan id"
    done
    if (( found == 0 )); then
        err "Could not find a loan whose verification workflows land on the non-leader vg node within $OWNER_SEARCH_MAX_ATTEMPTS attempts"
        return 1
    fi

    # ── kill -9 the LEADER; the owner of id_target's verifications is the
    # OTHER node and stays alive throughout ─────────────────────────────
    local kill_epoch
    kill_epoch=$(date +%s)
    log "kill -9 vg node $leader (pid $leader_pid, TIMER-POLLER LEADER) - $id_target's verification workflows are owned by the surviving node and stay alive throughout"
    kill -KILL "$leader_pid"
    sleep 1
    if kill -0 "$leader_pid" 2>/dev/null; then err "Process $leader_pid survived kill -9"; return 1; fi
    rm -f "$PID_DIR/$leader_iname.pid"

    wait_for_log_line "$owner_log" "Published appraisal verification result for loan $id_target" "$WAIT_TIMER_FAILOVER_SECS" || return 1
    local fired_epoch elapsed
    fired_epoch=$(date +%s)
    elapsed=$((fired_epoch - kill_epoch))
    log "TIMER FAILOVER LATENCY: ${elapsed}s (leader kill -> appraisal verification result published by the surviving owner node), bound ${WAIT_TIMER_FAILOVER_SECS}s (TTL 15s + poll 5s + wake-recheck 5s + slack)"

    wait_for_log_line "$owner_log" "Published credit verification result for loan $id_target" 30 || return 1
    wait_for_log_line "$owner_log" "Published employment verification result for loan $id_target" 30 || return 1

    # ── SQL proof: the durable timer row actually fired (not some other path) ──
    local vfid="verification-$id_target-appraisal" v_instance_id v_timer_status v_wf_status
    v_instance_id="$(psql_verify "SELECT id FROM maestro_workflow_instance WHERE workflow_id = '$vfid';" | tr -d '[:space:]')"
    [[ -n "$v_instance_id" ]] || { err "No maestro_workflow_instance row for $vfid"; return 1; }
    v_timer_status="$(psql_verify "SELECT status FROM maestro_workflow_timer WHERE workflow_instance_id = '$v_instance_id';" | tr -d '[:space:]')"
    v_wf_status="$(psql_verify "SELECT status FROM maestro_workflow_instance WHERE id = '$v_instance_id';" | tr -d '[:space:]')"
    log "SQL: $vfid timer status=$v_timer_status, workflow status=$v_wf_status"
    assert_eq "verification appraisal durable timer status" "FIRED" "$v_timer_status" || return 1
    assert_eq "verification appraisal workflow status" "COMPLETED" "$v_wf_status" || return 1

    # ── The owning LOAN workflow progresses to completion ───────────────
    upload_doc "$id_target" "pay-stub" "s9" || return 1
    wait_for_pending_review "$id_target" 1 "$WAIT_PENDING_SECS" || return 1
    post_decision "$id_target" 1 "APPROVED" '[]' || return 1
    sign_app "$id_target" "s9" || return 1
    wait_for_engine_status "$id_target" COMPLETED "$WAIT_TERMINAL_SECS" || return 1
    local body; body="$(app_status_json "$id_target")"
    assert_eq "engine status" "COMPLETED" "$(json_get "$body" status)" || return 1
    assert_eq "loan result"   "FUNDED"    "$(json_get "$body" output.status)" || return 1
    log "Loan $id_target progressed to FUNDED after the timer-poller leader failover"

    # Same shape as scenarios 1/5/7/8 (1 borrower, 1 doc, round-1 approval,
    # 1 signature) -> same designed gap set (the two withdrawal-gate timeouts).
    assert_event_log_integrity "loan-$id_target" "9,16" || return 1

    # ── Cleanup: restore both vg nodes to default (non-DEBUG) config ────
    log "Assertions passed - restoring vg node A to default (non-DEBUG) config"
    stop_service verification-gateway-service
    start_service "verification-gateway-service" || return 1
    wait_for_http "verification-gateway-service" "$VERIFY_URL/webhooks/credit/__probe__" 120 || return 1

    stop_service "$VERIFY_NODE_B"
    if [[ "$started_verify_b" == 1 ]]; then
        log "$VERIFY_NODE_B was started by this scenario - left stopped."
    else
        start_verify_node_b || return 1
        log "$VERIFY_NODE_B was started by cluster mode - restarted with default (non-DEBUG) config for the rest of the run."
    fi
    wait_for_consumer_group "verification-gateway" 90 || return 1
}

# ── Scenario 10: cross-node admin retry/terminate ───────────────────────
#
# Task 4 (spec Phase 1 Task D), sub-scenario 2. The admin dashboard's Retry
# and Terminate buttons publish $maestro:retry / $maestro:terminate signals
# (AdminCommandService) to the target workflow's service signal topic; any
# node's AdminCommandDispatcher consumes and executes them (node-agnostic —
# WorkflowExecutor.retryWorkflow/terminateWorkflow are pure store CAS
# operations keyed by workflowId, not by which node happens to own the
# instance). This sample's compose does not run maestro-admin (see
# task-4-report.md for why this harness publishes the raw signal directly
# via kafka_produce_signal instead of standing up the admin service).
#
# Retry target: a loan driven to FAILED WITHOUT saga compensation — an
# uncaught SignalTimeoutException at the underwriting-decision await
# (LoanApplicationWorkflow.awaitDecisionForRound has no timeout catch, unlike
# the withdrawal gates). This happens BEFORE reserveRateLock (the only
# @Compensate-registered step), so compensationStack is empty and no
# COMPENSATION_STARTED event is ever written — verified via SQL below, and
# required per Issue 16 (WorkflowExecutor.RetryOutcome.COMPENSATED_NOT_
# RETRYABLE guards off retrying a saga that already compensated). Node A runs
# with a short maestro.sample.decision-timeout override so this happens in
# seconds instead of the SPEC default 10 minutes; node B keeps the SPEC
# default so the retried run has time to receive the decision this harness
# delivers promptly after the retry.
#
# Terminate target: a second, still-ACTIVE (WAITING_SIGNAL) node-A-owned
# workflow, created and parked just before the kill so it has no chance to
# also hit the short decision-timeout.
scenario_cross_node_admin() {
    # Pre-flight repair, same as scenarios 7/8: restore node A if a previous
    # failed run left it dead.
    if ! port_in_use 8091; then
        warn "Node A (port 8091) is not up at scenario start - restoring it (leftover from a previous failed run?)"
        start_service "loan-application-service" || return 1
        wait_for_http "loan-application-service" "$LOAN_URL/applications/__probe__" 120 || return 1
    fi

    local started_node_b=1
    if [[ -f "$PID_DIR/$LOAN_NODE_B.pid" ]] && kill -0 "$(cat "$PID_DIR/$LOAN_NODE_B.pid")" 2>/dev/null; then
        started_node_b=0
    fi

    log "Restarting node A with a short decision-timeout ($RETRY_DECISION_TIMEOUT) and node B with admin events enabled..."
    stop_service loan-application-service
    stop_service "$LOAN_NODE_B"
    start_service_instance loan-application-service "$(default_port_for loan-application-service)" \
        loan-application-service "MAESTRO_SAMPLE_DECISION_TIMEOUT=$RETRY_DECISION_TIMEOUT" || return 1
    start_service_instance loan-application-service "$LOAN_PORT_B" "$LOAN_NODE_B" \
        "MAESTRO_ADMIN_EVENTS_ENABLED=true" || return 1
    wait_for_http "loan-application-service" "$LOAN_URL/applications/__probe__" 120 || return 1
    wait_for_http "$LOAN_NODE_B" "$LOAN_URL_B/applications/__probe__" 120 || return 1
    wait_for_consumer_group "maestro-loan-application" 90 2 || return 1

    local pid_a pid_b
    pid_a="$(cat "$PID_DIR/loan-application-service.pid")"
    pid_b="$(cat "$PID_DIR/$LOAN_NODE_B.pid")"
    [[ "$pid_a" != "$pid_b" ]] || { err "Both loan nodes report the same PID $pid_a"; return 1; }
    log "Node A (short decision-timeout, to be killed) pid=$pid_a; node B (admin events, survivor) pid=$pid_b"

    local loan_log="$LOG_DIR/loan-application-service.log"
    local loan_log_b="$LOG_DIR/$LOAN_NODE_B.log"

    # ── Retry-target: create on node A, never decide -> FAILED (no comp) ──
    local id_retry="e2e-${RUN_ID}-s10-retry" wfid_retry
    wfid_retry="loan-$id_retry"
    create_app "$id_retry" '["s10r"]' 400000 100000 650000 '["pay-stub"]' || return 1
    upload_doc "$id_retry" "pay-stub" "s10r" || return 1
    wait_for_pending_review "$id_retry" 1 "$WAIT_PENDING_SECS" || return 1
    wait_for_log_line "$loan_log" "Requested underwriting round 1 for loan $id_retry" 30 || return 1
    log "OWNERSHIP PROOF (log): node A's log shows 'Requested underwriting round 1 for loan $id_retry' - node A owns $wfid_retry"

    # Deliberately never post the decision - node A's short decision-timeout
    # ($RETRY_DECISION_TIMEOUT) fires an uncaught SignalTimeoutException.
    log "Withholding the underwriting decision for $id_retry - waiting up to ${WAIT_FAILURE_SECS}s for node A's short decision-timeout to FAIL it"
    local deadline=$((SECONDS + WAIT_FAILURE_SECS)) retry_status=""
    while (( SECONDS < deadline )); do
        retry_status="$(psql_loan "SELECT status FROM maestro_workflow_instance WHERE workflow_id = '$wfid_retry';" | tr -d '[:space:]')"
        [[ "$retry_status" == "FAILED" ]] && break
        sleep 1
    done
    assert_eq "SQL status ($wfid_retry) after decision-timeout" "FAILED" "$retry_status" || return 1

    # ── SQL proof (Issue 16 precondition): no COMPENSATION_STARTED event ──
    local retry_instance_id comp_count
    retry_instance_id="$(psql_loan "SELECT id FROM maestro_workflow_instance WHERE workflow_id = '$wfid_retry';" | tr -d '[:space:]')"
    comp_count="$(psql_loan "SELECT COUNT(*) FROM maestro_workflow_event WHERE workflow_instance_id = '$retry_instance_id' AND event_type = 'COMPENSATION_STARTED';" | tr -d '[:space:]')"
    log "SQL: $wfid_retry COMPENSATION_STARTED count = $comp_count (must be 0 - Issue 16 precondition for a retryable FAILED)"
    assert_eq "COMPENSATION_STARTED events for $wfid_retry" "0" "$comp_count" || return 1

    # ── Terminate-target: create LAST, right before the kill, so it never
    # gets close to node A's short decision-timeout ─────────────────────
    local id_term="e2e-${RUN_ID}-s10-term" wfid_term
    wfid_term="loan-$id_term"
    create_app "$id_term" '["s10t"]' 400000 100000 650000 '["pay-stub"]' || return 1
    upload_doc "$id_term" "pay-stub" "s10t" || return 1
    wait_for_pending_review "$id_term" 1 "$WAIT_PENDING_SECS" || return 1
    wait_for_log_line "$loan_log" "Requested underwriting round 1 for loan $id_term" 30 || return 1
    log "OWNERSHIP PROOF (log): node A's log shows 'Requested underwriting round 1 for loan $id_term' - node A owns $wfid_term"
    local body_term; body_term="$(app_status_json "$id_term")"
    assert_eq "pre-kill engine status ($id_term)" "WAITING_SIGNAL" "$(json_get "$body_term" status)" || return 1

    # ── kill -9 node A; only node B is alive for both admin commands ────
    local kill_epoch
    kill_epoch=$(date +%s)
    log "kill -9 loan-application-service (pid $pid_a, OWNER of $wfid_retry and $wfid_term) - will NOT be restarted until after assertions pass"
    kill -KILL "$pid_a"
    sleep 1
    if kill -0 "$pid_a" 2>/dev/null; then err "Process $pid_a survived kill -9"; return 1; fi
    rm -f "$PID_DIR/loan-application-service.pid"

    # ── $maestro:retry, published raw (see scenario header) ─────────────
    kafka_produce_signal "maestro.signals.loan-application" "$wfid_retry" \
        "{\"workflowId\":\"$wfid_retry\",\"signalName\":\"\$maestro:retry\",\"payload\":null}" || return 1
    log "Published \$maestro:retry for $wfid_retry to maestro.signals.loan-application (node A dead, only node B can consume it)"

    wait_for_log_line "$loan_log_b" "Retried workflow '$wfid_retry'" "$WAIT_ADMIN_COMMAND_SECS" || return 1
    log "Node B's log confirms it executed the retry for $wfid_retry"

    # Deliver the decision promptly - the retried run resumes node B's
    # default (10m) decision-timeout, but there is no reason to wait.
    post_decision "$id_retry" 1 "APPROVED" '[]' || return 1
    sign_app_via "$LOAN_URL_B" "$id_retry" "s10r" || return 1

    wait_for_engine_status_via "$LOAN_URL_B" "$id_retry" COMPLETED "$WAIT_RECOVERY_SECS" || return 1
    local body_retry; body_retry="$(api_get "$LOAN_URL_B/applications/$id_retry")"
    assert_eq "engine status (via node B, post-retry)" "COMPLETED" "$(json_get "$body_retry" status)" || return 1
    assert_eq "loan result (via node B, post-retry)"   "FUNDED"    "$(json_get "$body_retry" output.status)" || return 1
    log "$wfid_retry retried by node B alone and completed FUNDED"

    # ── $maestro:terminate, published raw ────────────────────────────────
    kafka_produce_signal "maestro.signals.loan-application" "$wfid_term" \
        "{\"workflowId\":\"$wfid_term\",\"signalName\":\"\$maestro:terminate\",\"payload\":{\"reason\":\"e2e admin terminate test\"}}" || return 1
    log "Published \$maestro:terminate for $wfid_term to maestro.signals.loan-application (node A dead, only node B can consume it)"

    wait_for_log_line "$loan_log_b" "Terminated workflow '$wfid_term'" "$WAIT_ADMIN_COMMAND_SECS" || return 1
    wait_for_engine_status_via "$LOAN_URL_B" "$id_term" TERMINATED "30" || return 1
    local body_term_after; body_term_after="$(api_get "$LOAN_URL_B/applications/$id_term")"
    assert_eq "engine status (via node B, post-terminate)" "TERMINATED" "$(json_get "$body_term_after" status)" || return 1
    log "$wfid_term terminated by node B alone"

    if kill -0 "$pid_a" 2>/dev/null; then
        err "Node A ($pid_a) is alive - this scenario requires it stay dead until after assertions pass"
        return 1
    fi
    log "Node A confirmed dead throughout (never restarted before this line)"

    # ── Admin-events topic: WORKFLOW_RETRIED / WORKFLOW_TERMINATED ──────
    local admin_events
    admin_events="$(kafka_read_admin_events 15000)"
    if ! grep -qF "\"workflowId\":\"$wfid_retry\"" <<<"$admin_events" || ! grep -qF '"eventType":"WORKFLOW_RETRIED"' <<<"$(grep -F "\"workflowId\":\"$wfid_retry\"" <<<"$admin_events")"; then
        err "admin.events topic does not contain a WORKFLOW_RETRIED event for $wfid_retry"
        printf '%s\n' "$admin_events" | grep -F "$wfid_retry" || true
        return 1
    fi
    log "ADMIN-EVENTS PROOF: WORKFLOW_RETRIED for $wfid_retry found on maestro.admin.events"

    if ! grep -qF "\"workflowId\":\"$wfid_term\"" <<<"$admin_events" || ! grep -qF '"eventType":"WORKFLOW_TERMINATED"' <<<"$(grep -F "\"workflowId\":\"$wfid_term\"" <<<"$admin_events")"; then
        err "admin.events topic does not contain a WORKFLOW_TERMINATED event for $wfid_term"
        printf '%s\n' "$admin_events" | grep -F "$wfid_term" || true
        return 1
    fi
    log "ADMIN-EVENTS PROOF: WORKFLOW_TERMINATED for $wfid_term found on maestro.admin.events"

    # ── Event-log integrity ──────────────────────────────────────────────
    # $wfid_retry: retried past the decision-timeout gap and completed
    # normally - the gap the timed-out await left behind gets filled in by
    # the retry's live (successful) re-execution at the SAME deterministic
    # sequence number, so the final shape matches the scenario 1/5/7/8
    # baseline exactly (only the two withdrawal-gate timeout gaps remain).
    assert_event_log_integrity "$wfid_retry" "9,16" || return 1
    # $wfid_term: terminated mid-flight, WAITING_SIGNAL on the underwriting
    # decision (never resolved) - terminateWorkflow appends NO event of its
    # own (see its Javadoc), so there is no WORKFLOW_COMPLETED to check for;
    # only gate 1's designed timeout gap (seq 9) has been reached.
    assert_event_log_integrity "$wfid_term" "9" "" || return 1

    # ── Cleanup: restart node A with default config, node B with default
    # config, matching pre-scenario topology ────────────────────────────
    log "Assertions passed - restarting node A (default config) and node B (default config)"
    start_service "loan-application-service" || return 1
    wait_for_http "loan-application-service" "$LOAN_URL/applications/__probe__" 120 || return 1

    stop_service "$LOAN_NODE_B"
    if [[ "$started_node_b" == 1 ]]; then
        log "$LOAN_NODE_B was started by this scenario - left stopped."
    else
        start_loan_node_b || return 1
        log "$LOAN_NODE_B was started by cluster mode - restarted with default config for the rest of the run."
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
    # Scenarios 7 and 8 both take node A (the primary loan-application-service
    # used by every scenario above via $LOAN_URL) down mid-run. They MUST run
    # last: any earlier position would leave node A dead (or racing its own
    # restart) while scenarios 1-6 still expect it to be the live, addressable
    # primary at $LOAN_URL. Scenario 8 runs after 7 for the same reason.
    run_scenario "7. Owner-kill -> peer adoption (multi-node)" scenario_owner_kill_adoption
    run_scenario "8. Rolling restart (graceful SIGTERM mid-flight)" scenario_rolling_restart
    # Scenarios 9 and 10 restart the verification-gateway and loan-application
    # nodes (respectively) with per-node config overrides. They run last, same
    # reasoning as 7/8: they leave node A / vg node A briefly reconfigured or
    # dead mid-scenario, which earlier scenarios must not observe.
    run_scenario "9. Timer-poller leader failover (verification-gateway)" scenario_timer_leader_failover
    run_scenario "10. Cross-node admin retry/terminate" scenario_cross_node_admin

    sweep_logs

    printf '\n%s=== RESULTS ===%s\n' "$BOLD" "$NC"
    if [[ "${#RESULT_NAMES[@]}" == 0 ]]; then
        err "No scenarios ran (E2E_ONLY='$E2E_ONLY' matched nothing)."
        exit 1
    fi
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
