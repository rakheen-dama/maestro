#!/usr/bin/env bash
# The live v1 -> v2 versioned-redeploy move, driven end to end against the
# running demo stack (demo/docker-compose.yml + demo/scripts/start-services.sh).
#
# WHAT THIS PROVES
#
#   1. A loan started under v1 and still IN FLIGHT when the v2 jar replaces the
#      v1 jar completes on the SEQUENTIAL (v1) path. Its history recorded no
#      VERSION_MARKER, so workflow.version() resolves it to DEFAULT_VERSION and
#      `v < PARALLEL_VERIFICATION_VERSION` takes the old branch. Asserted from
#      the EVENT LOG (zero VERSION_MARKER rows, zero branch-band sequence
#      numbers), not from "it completed".
#
#   2. A loan started AFTER the swap fans out: a VERSION_MARKER at the first
#      sequence slot, and events in the parallel branches' partitioned sequence
#      bands (branch i of a fork at parent seq p allocates from
#      p*1000 + (i+1)*1000).
#
#   3. The two parallel branches genuinely park CONCURRENTLY. Read off
#      ParkingLot's DEBUG log, which distinguishes the two cases: unpark()
#      logs "Unparked workflow at key" ONLY when a waiter was actually
#      registered at that key, and "stored wake permit" when there was none.
#      So a successful unpark timestamps a live park. The branches overlap
#      when the document.uploaded key is unparked at a moment the
#      verification.result key still holds its (later-unparked, never-yet-
#      woken) waiter. That overlap is the exact shape of the
#      InstanceStatusWriter optimistic-lock defect fixed earlier this month,
#      so the run also greps for its version-conflict line and for
#      ParkingLot's "already occupied" guard.
#
# TIMING. The verification gateway's simulated latencies are credit 2s,
# employment 5s, appraisal 8s (VerificationGatewayProperties.Latency defaults),
# and the loan's own event log timestamps every signal it consumed. The new
# loan therefore uploads its documents at ~3.5s, early in the verification
# window, so the document branch demonstrably parks and wakes while the
# verification branch is still parked with nothing consumed.
#
# Usage:  demo/scripts/v1-to-v2-move.sh
# Leaves the v2 jar running on 8091 with its pid in demo/.run (so
# demo/scripts/stop-services.sh still stops everything).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEMO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$DEMO_DIR/.." && pwd)"
SAMPLE_DIR="$REPO_ROOT/maestro-samples/sample-loan-origination"
RUN_DIR="$DEMO_DIR/.run"

# The JVMs this script starts must be configured exactly like the ones
# start-services.sh starts — this file swaps a jar underneath a running demo,
# so any divergence shows up as the swap having changed behaviour it did not
# change. One definition, shared with start-services.sh and restart-loan-app.sh.
# shellcheck source=lib/jvm-env.sh
. "$SCRIPT_DIR/lib/jvm-env.sh"

LOAN_URL="http://localhost:8091"
UW_URL="http://localhost:8093"
JAEGER="http://localhost:16686"

V1_JAR="$SAMPLE_DIR/loan-application-service/build/libs/loan-application-service-0.3.0-SNAPSHOT.jar"
V2_JAR="$SAMPLE_DIR/loan-application-service/build/libs/loan-application-v2.jar"
PIDFILE="$RUN_DIR/loan-application-service.pid"
V2_LOG="$RUN_DIR/loan-application-service-v2.log"

STAMP="$(date +%s)"
INFLIGHT_ID="${INFLIGHT_ID:-inflight-$STAMP}"
NEW_ID="${NEW_ID:-newloan-$STAMP}"

log() { printf '\n%s [move] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
die() { printf '\n%s [move] FAILED: %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; exit 1; }

psql_loan() { docker exec maestro-demo-postgres-1 psql -U maestro -d loan_application -tAc "$1"; }

instance_id() { psql_loan "SELECT id FROM maestro_workflow_instance WHERE workflow_id = 'loan-$1';" | tr -d '[:space:]'; }
instance_status() { psql_loan "SELECT status FROM maestro_workflow_instance WHERE workflow_id = 'loan-$1';" | tr -d '[:space:]'; }

api_post() { curl -sS --max-time 10 -X POST -H 'Content-Type: application/json' -d "$2" "$1"; }

create_loan() { # <id> <docsJsonArr>
    api_post "$LOAN_URL/applications" \
        "{\"applicationId\":\"$1\",\"borrowerIds\":[\"b1\",\"b2\"],\"amount\":250000,\"income\":90000,\"propertyValue\":320000,\"requiredDocs\":$2}"
    echo
}
upload_doc() { api_post "$LOAN_URL/applications/$1/documents" "{\"docType\":\"$2\",\"uploadedBy\":\"b1\"}"; echo; }
sign_app()   { api_post "$LOAN_URL/applications/$1/sign" "{\"signerId\":\"$2\"}"; echo; }
decide()     { api_post "$UW_URL/underwriting/$1/rounds/$2/decision" "{\"verdict\":\"APPROVED\",\"conditions\":[]}"; echo; }

# wait_signal_count <appId> <signalName> <want> <timeout>
wait_signal_count() {
    local id="$1" sig="$2" want="$3" deadline=$((SECONDS + $4)) iid count
    while (( SECONDS < deadline )); do
        iid="$(instance_id "$id")"
        if [[ -n "$iid" ]]; then
            count="$(psql_loan "SELECT COUNT(*) FROM maestro_workflow_event WHERE workflow_instance_id = '$iid' AND event_type = 'SIGNAL_RECEIVED' AND step_name = '\$maestro:awaitSignal:$sig';" | tr -d '[:space:]')"
            [[ "$count" =~ ^[0-9]+$ ]] && (( count >= want )) && { echo "  $sig SIGNAL_RECEIVED count = $count (>= $want)"; return 0; }
        fi
        sleep 1
    done
    echo "  FAILED: $id never reached $want '$sig' signals within $4s" >&2
    return 1
}

wait_status() { # <appId> <expected> <timeout>
    local deadline=$((SECONDS + $3)) s
    while (( SECONDS < deadline )); do
        s="$(instance_status "$1")"
        if [[ "$s" == "$2" ]]; then echo "  loan-$1 status = $s"; return 0; fi
        case "$s" in FAILED|TERMINATED) echo "  FAILED: loan-$1 reached $s (wanted $2)" >&2; return 1 ;; esac
        sleep 1
    done
    echo "  FAILED: loan-$1 did not reach $2 within $3s (last: ${s:-<none>})" >&2
    return 1
}

wait_pending_review() { # <appId> <round> <timeout>
    local deadline=$((SECONDS + $3))
    while (( SECONDS < deadline )); do
        if curl -s --max-time 5 "$UW_URL/underwriting/pending" \
             | jq -e --arg id "$1" --argjson r "$2" \
                 'map(select(.loanId == $id and .round == $r)) | length > 0' >/dev/null 2>&1; then
            echo "  underwriting round $2 pending for $1"
            return 0
        fi
        sleep 1
    done
    echo "  FAILED: $1 round $2 never appeared in the underwriting pending queue" >&2
    return 1
}

# approve_and_fund <appId> — carries a loan from "documents collected" to
# FUNDED. Documents are uploaded by the caller, because for the new loan their
# TIMING is the whole point.
#
# The underwriting service auto-assesses on DTI (UnderwritingWorkflow.autoAssess)
# and only borderline cases reach the human queue. These loan parameters
# (250k against 90k income) are a clear-cut AUTO_APPROVE, so no decision is
# posted by hand — hence the wait-then-fall-back shape rather than an
# unconditional POST, which would hang forever on a queue nothing ever enters.
approve_and_fund() { # <appId>
    local id="$1"
    if ! wait_signal_count "$id" underwriting.decision 1 60; then
        echo "  no automatic decision — treating as a borderline case in the human queue"
        wait_pending_review "$id" 1 60
        decide "$id" 1
        wait_signal_count "$id" underwriting.decision 1 60
    fi
    sign_app "$id" b1
    sleep 0.5
    sign_app "$id" b2
    wait_status "$id" COMPLETED 180
}

event_log() { # <appId>
    local iid; iid="$(instance_id "$1")"
    psql_loan "SELECT sequence_number || ' | ' || event_type || ' | ' || COALESCE(step_name,'') || ' | ' || to_char(created_at, 'HH24:MI:SS.MS') FROM maestro_workflow_event WHERE workflow_instance_id = '$iid' ORDER BY sequence_number;"
}

count_version_markers() { # <appId>
    local iid; iid="$(instance_id "$1")"
    psql_loan "SELECT COUNT(*) FROM maestro_workflow_event WHERE workflow_instance_id = '$iid' AND event_type = 'VERSION_MARKER';" | tr -d '[:space:]'
}

count_branch_band() { # <appId> — events at sequence >= 1000 (parallel branch bands)
    local iid; iid="$(instance_id "$1")"
    psql_loan "SELECT COUNT(*) FROM maestro_workflow_event WHERE workflow_instance_id = '$iid' AND sequence_number >= 1000;" | tr -d '[:space:]'
}

trace_for() { # <appId> — prints "traceID spanCount" for the richest trace tagged with this workflow id
    curl -sG "$JAEGER/api/traces" \
        --data-urlencode 'service=sample-loan-application-service' \
        --data-urlencode "tags={\"maestro.workflow.id\":\"loan-$1\"}" \
        --data-urlencode 'lookback=2h' --data-urlencode 'limit=50' \
    | jq -r '[.data[] | {id: .traceID, n: (.spans | length)}] | sort_by(-.n) | .[] | "\(.id) \(.n)"'
}

# start_loan_jvm <jar> <log-file> [EXTRA_ENV=...] — boots one loan-application
# JVM with exactly the environment demo/scripts/start-services.sh gives it (both
# read it from lib/jvm-env.sh, sourced above), and claims the same pid file so
# stop-services.sh keeps working.
start_loan_jvm() {
    local jar="$1" logfile="$2"
    shift 2
    : >"$logfile"
    # Instance-specific; everything else came from lib/jvm-env.sh.
    SERVER_PORT=8091 POSTGRES_DB=loan_application \
    env ${1+"$@"} \
        java "${MAESTRO_JVM_OPTS[@]}" -jar "$jar" >>"$logfile" 2>&1 &
    echo $! >"$PIDFILE"
    echo "  started $(basename "$jar") as pid $(cat "$PIDFILE") (log: $logfile)"
    local i
    for i in $(seq 1 120); do
        [[ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "$LOAN_URL/actuator/health")" == 200 ]] && break
        sleep 1
    done
    [[ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "$LOAN_URL/actuator/health")" == 200 ]] \
        || { echo "  FAILED: $(basename "$jar") did not become healthy on 8091 — see $logfile" >&2; return 1; }
    echo "  healthy on 8091"
}

stop_loan_jvm() { # graceful SIGTERM, no KILL fallback — this is a rolling deploy
    local pid; pid="$(cat "$PIDFILE")"
    kill -TERM "$pid"
    local i
    for i in $(seq 1 45); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    if kill -0 "$pid" 2>/dev/null; then
        echo "  FAILED: pid $pid still alive 45s after SIGTERM" >&2; return 1
    fi
    echo "  pid $pid exited on SIGTERM (a real rolling deploy, not kill -9)"
}

# RESTORE_V1=1 puts the v1 jar back on 8091 and exits — how you reset the demo
# stack between runs of this script without restarting the other three JVMs.
if [[ "${RESTORE_V1:-0}" == 1 ]]; then
    log "RESTORE_V1=1 — putting the v1 jar back on 8091"
    stop_loan_jvm
    start_loan_jvm "$V1_JAR" "$RUN_DIR/loan-application-service.log"
    exit 0
fi

# ─────────────────────────────────────────────────────────────────────────

log "PIN 0 — what is running right now"
[ -f "$PIDFILE" ] || die "no pid file at $PIDFILE — is the demo running? (demo/scripts/start-services.sh)"
_pin0_pid=$(cat "$PIDFILE")
_pin0_jar=$(ps -p "$_pin0_pid" -o args= | tr ' ' '\n' | grep -E '\.jar$' | tail -1) || true
[ -n "$_pin0_jar" ] || die "pid $_pin0_pid is not running a jar — the demo's loan-application JVM is not up"
echo "  pid $_pin0_pid -> $_pin0_jar"
echo "  v1 jar sha256: $(shasum -a 256 "$V1_JAR" | awk '{print $1}')"
echo "  v2 jar sha256: $(shasum -a 256 "$V2_JAR" | awk '{print $1}')"
echo "  packaged workflow class differs between the jars:"
for j in "$V1_JAR" "$V2_JAR"; do
    echo -n "    $(basename "$j"): "
    unzip -p "$j" BOOT-INF/classes/io/b2mash/maestro/samples/loan/application/workflow/LoanApplicationWorkflow.class \
        | shasum -a 256 | awk '{print $1}'
done
echo -n "  occurrences of the change id 'parallel-verification' in v2's packaged class: "
unzip -p "$V2_JAR" BOOT-INF/classes/io/b2mash/maestro/samples/loan/application/workflow/LoanApplicationWorkflow.class \
    | strings | grep -c 'parallel-verification' || true
echo -n "  occurrences of the change id 'parallel-verification' in v1's packaged class: "
unzip -p "$V1_JAR" BOOT-INF/classes/io/b2mash/maestro/samples/loan/application/workflow/LoanApplicationWorkflow.class \
    | strings | grep -c 'parallel-verification' || true

log "PIN 1 — start loan '$INFLIGHT_ID' under v1 and let it reach verification"
create_loan "$INFLIGHT_ID" '["payslip","bank-statement"]'
wait_signal_count "$INFLIGHT_ID" verification.result 1 60
echo "  status now: $(instance_status "$INFLIGHT_ID")"
echo "  event log at the moment of the swap:"
event_log "$INFLIGHT_ID" | sed 's/^/    /'
echo "  VERSION_MARKER rows in its history: $(count_version_markers "$INFLIGHT_ID")"

log "PIN 2 — graceful SIGTERM of the v1 JVM, then start the v2 jar in its place"
stop_loan_jvm
echo "  in-flight loan survived the shutdown as: $(instance_status "$INFLIGHT_ID")"
# DEBUG on the engine package only for the v2 JVM: ParkingLot's unpark lines
# are the concurrent-parking evidence PIN 5 reads back.
start_loan_jvm "$V2_JAR" "$V2_LOG" LOGGING_LEVEL_IO_B2MASH_MAESTRO_CORE_ENGINE=DEBUG

log "PIN 3 — the in-flight loan finishes on the SEQUENTIAL path under v2"
# It was parked mid-verification; the results published while the JVM was down
# are consumed on resume. Documents come next on the v1 path.
wait_signal_count "$INFLIGHT_ID" verification.result 3 180
upload_doc "$INFLIGHT_ID" payslip
upload_doc "$INFLIGHT_ID" bank-statement
approve_and_fund "$INFLIGHT_ID"
echo "  final event log:"
event_log "$INFLIGHT_ID" | sed 's/^/    /'
IF_MARKERS="$(count_version_markers "$INFLIGHT_ID")"
IF_BAND="$(count_branch_band "$INFLIGHT_ID")"
echo "  VERSION_MARKER rows: $IF_MARKERS   (must be 0 — history predates the change)"
echo "  events in parallel branch bands (sequence >= 1000): $IF_BAND   (must be 0 — sequential path)"
echo "  output: $(psql_loan "SELECT output FROM maestro_workflow_instance WHERE workflow_id = 'loan-$INFLIGHT_ID';")"
[[ "$IF_MARKERS" == 0 && "$IF_BAND" == 0 ]] || { echo "  FAILED: in-flight loan did not take the v1 path" >&2; exit 1; }

log "PIN 4 — a NEW loan under v2 fans out"
create_loan "$NEW_ID" '["payslip","bank-statement"]'
# Documents at ~3.5s: after credit's 2s result, before employment's 5s and
# appraisal's 8s. This is what interleaves the unparks.
sleep 3.5
upload_doc "$NEW_ID" payslip
# Half a second apart so the branch has re-parked before the second document
# arrives — back-to-back uploads would hit ParkingLot's wake-permit path and
# log "stored wake permit" instead of a second genuine unpark.
sleep 0.5
upload_doc "$NEW_ID" bank-statement
wait_signal_count "$NEW_ID" verification.result 3 180
approve_and_fund "$NEW_ID"
echo "  final event log:"
event_log "$NEW_ID" | sed 's/^/    /'
NEW_MARKERS="$(count_version_markers "$NEW_ID")"
NEW_BAND="$(count_branch_band "$NEW_ID")"
echo "  VERSION_MARKER rows: $NEW_MARKERS   (must be 1)"
echo "  VERSION_MARKER row: $(psql_loan "SELECT sequence_number || ' | ' || step_name || ' | ' || payload::text FROM maestro_workflow_event WHERE workflow_instance_id = '$(instance_id "$NEW_ID")' AND event_type = 'VERSION_MARKER';")"
echo "  events in parallel branch bands (sequence >= 1000): $NEW_BAND   (must be > 0)"
echo "  output: $(psql_loan "SELECT output FROM maestro_workflow_instance WHERE workflow_id = 'loan-$NEW_ID';")"
[[ "$NEW_MARKERS" == 1 && "$NEW_BAND" -gt 0 ]] || { echo "  FAILED: new loan did not take the v2 path" >&2; exit 1; }

log "PIN 5 — did the two branches park CONCURRENTLY?"
echo "  ParkingLot lines for loan-$NEW_ID's two branch signal keys, in log order:"
grep -E "at key 'loan-$NEW_ID:signal:(verification\.result|document\.uploaded)'" "$V2_LOG" \
    | sed 's/^/    /' || echo "    (none — see $V2_LOG)"
echo
echo -n "  InstanceStatusWriter version-conflict lines (the optimistic-lock shape): "
grep -c "Version conflict writing status" "$V2_LOG" || true
grep "Version conflict writing status" "$V2_LOG" | sed 's/^/    /' || true
echo -n "  status-write give-ups (must be 0): "
grep -c "Could not write status" "$V2_LOG" || true
echo -n "  'Parking key already occupied' errors (must be 0): "
grep -c "already occupied" "$V2_LOG" || true

log "PIN 6 — Jaeger trace ids"
echo "  in-flight loan (loan-$INFLIGHT_ID)  traceID spans:"
trace_for "$INFLIGHT_ID" | sed 's/^/    /'
echo "  new loan (loan-$NEW_ID)  traceID spans:"
trace_for "$NEW_ID" | sed 's/^/    /'

log "DONE. in-flight=$INFLIGHT_ID new=$NEW_ID"
