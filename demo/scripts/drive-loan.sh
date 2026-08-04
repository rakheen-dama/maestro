#!/usr/bin/env bash
# drive-loan.sh — drive one demo scenario end to end so the presenter types one
# short command instead of a curl by hand.
#
#   demo/scripts/drive-loan.sh happy      [id]   fully automatic loan -> FUNDED
#   demo/scripts/drive-loan.sh conditions [id]   underwriter asks for a condition, round 2 approves
#   demo/scripts/drive-loan.sh withdraw   [id]   withdraw after rate lock -> LIFO compensation
#   demo/scripts/drive-loan.sh crash      [id]   park a loan mid-flight, then stop and hand over
#   demo/scripts/drive-loan.sh finish     <id>   resume the parked loan after the restart
#   demo/scripts/drive-loan.sh events     <id>   print the durable event log for a loan
#
# Loan parameters are chosen deliberately (see RUNBOOK.md):
#   DTI = amount/income. < 3.0 auto-approves, 3.0..6.0 goes to the human queue,
#   > 6.0 auto-rejects. `happy` uses DTI 2.0 so nobody has to click; the other
#   scenarios use DTI 3.5-4.5 so the human-decision beat actually happens.
set -euo pipefail

LOAN_URL="${LOAN_URL:-http://localhost:8091}"
VG_URL="${VG_URL:-http://localhost:8092}"
UW_URL="${UW_URL:-http://localhost:8093}"
PG_CONTAINER="${PG_CONTAINER:-maestro-demo-postgres-1}"
PG_DB="${PG_DB:-loan_application}"
DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$DEMO_DIR/.run"
LOAN_LOG="$RUN_DIR/loan-application-service.log"

usage() {
  sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

say()  { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
info() { printf '    %s\n' "$*"; }
die()  { printf '\n\033[31mFAILED: %s\033[0m\n' "$*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "missing required tool: $1"; }
need curl; need jq

psql_q() { docker exec "$PG_CONTAINER" psql -U maestro -d "$PG_DB" -tAc "$1"; }

# ---------------------------------------------------------------- REST helpers

create_app() { # id borrowersJson amount income propertyValue docsJson
  curl -sS -X POST "$LOAN_URL/applications" -H 'Content-Type: application/json' \
    -d "{\"applicationId\":\"$1\",\"borrowerIds\":$2,\"amount\":$3,\"income\":$4,\"propertyValue\":$5,\"requiredDocs\":$6}" \
    -w ' [http %{http_code}]\n'
}

upload_doc() { # id docType uploadedBy
  local code
  code=$(curl -sS -o /dev/null -w '%{http_code}' \
    -X POST "$LOAN_URL/applications/$1/documents" -H 'Content-Type: application/json' \
    -d "{\"docType\":\"$2\",\"uploadedBy\":\"$3\"}")
  printf '    document.uploaded %s by %s [http %s]\n' "$2" "$3" "$code"
  [ "$code" = "202" ] || die "document upload for $1 returned $code"
}

sign_app() { # id signerId
  curl -sS -o /dev/null -w "    package.signed by $2 [http %{http_code}]\n" \
    -X POST "$LOAN_URL/applications/$1/sign" -H 'Content-Type: application/json' \
    -d "{\"signerId\":\"$2\"}"
}

withdraw_app() { # id reason
  curl -sS -o /dev/null -w "    application.withdrawn [http %{http_code}]\n" \
    -X POST "$LOAN_URL/applications/$1/withdraw" -H 'Content-Type: application/json' \
    -d "{\"reason\":\"$2\"}"
}

post_decision() { # id round verdict conditionsJson
  curl -sS -X POST "$UW_URL/underwriting/$1/rounds/$2/decision" -H 'Content-Type: application/json' \
    -d "{\"verdict\":\"$3\",\"conditions\":$4}" | jq -c .
}

app_status() { curl -sS "$LOAN_URL/applications/$1" | jq -r '.status // "ABSENT"'; }
app_json()   { curl -sS "$LOAN_URL/applications/$1" | jq -c .; }

# ---------------------------------------------------------------- wait helpers

count_events() { # workflowId stepLike
  psql_q "select count(*) from maestro_workflow_event e
            join maestro_workflow_instance i on i.id = e.workflow_instance_id
           where i.workflow_id = 'loan-$1' and e.step_name like '$2'" | tr -d '[:space:]'
}

wait_verifications() { # id [timeout]
  local id="$1" timeout="${2:-90}" n=0 waited=0
  say "waiting for all 3 verification results (credit 2s, employment 5s, appraisal 8s simulated)"
  info "SAY: \"nothing here is mocked out — three providers answer on their own schedule, in any order.\""
  while [ "$waited" -lt "$timeout" ]; do
    n=$(count_events "$id" '%awaitSignal:verification.result')
    [ "$n" -ge 3 ] && { info "3/3 verification.result signals recorded after ${waited}s"; return 0; }
    sleep 2; waited=$((waited + 2))
  done
  die "only $n/3 verification results after ${timeout}s. Fallback: see RUNBOOK.md scenario 1."
}

wait_pending_review() { # id round [timeout]
  local id="$1" round="$2" timeout="${3:-60}" waited=0
  say "waiting for the underwriting desk to queue round $round"
  while [ "$waited" -lt "$timeout" ]; do
    if curl -sS "$UW_URL/underwriting/pending" \
         | jq -e --arg l "$id" --argjson r "$round" 'any(.[]; .loanId==$l and .round==$r)' >/dev/null; then
      curl -sS "$UW_URL/underwriting/pending" | jq -c --arg l "$id" '[.[] | select(.loanId==$l)]'
      return 0
    fi
    sleep 2; waited=$((waited + 2))
  done
  die "round $round never appeared on GET $UW_URL/underwriting/pending after ${timeout}s. Fallback: RUNBOOK.md scenario 4."
}

wait_status() { # id expectedStatus [timeout]
  local id="$1" want="$2" timeout="${3:-180}" waited=0 s=""
  say "waiting for $id to reach $want"
  while [ "$waited" -lt "$timeout" ]; do
    s=$(app_status "$id")
    [ "$s" = "$want" ] && { info "$id is $want after ${waited}s"; return 0; }
    case "$s" in FAILED|TERMINATED) [ "$s" = "$want" ] || die "$id went $s, expected $want. $(app_json "$id")";; esac
    sleep 2; waited=$((waited + 2))
  done
  die "$id is still $s after ${timeout}s, expected $want."
}

wait_step_event() { # id stepLike label [timeout]
  local id="$1" like="$2" label="$3" timeout="${4:-120}" waited=0
  say "waiting for $label"
  while [ "$waited" -lt "$timeout" ]; do
    [ "$(count_events "$id" "$like")" -ge 1 ] && { info "$label recorded after ${waited}s"; return 0; }
    sleep 1; waited=$((waited + 1))
  done
  die "$label never recorded after ${timeout}s."
}

event_rows() { # id — the event log with no decoration, for diffing
  psql_q "select lpad(e.sequence_number::text,5,' ') || '  ' || rpad(e.event_type,28,' ') || '  ' || coalesce(e.step_name,'')
            from maestro_workflow_event e
            join maestro_workflow_instance i on i.id = e.workflow_instance_id
           where i.workflow_id = 'loan-$1' order by e.sequence_number"
}

print_events() { # id
  say "durable event log for loan-$1  (this is the whole of the workflow's memory)"
  event_rows "$1"
}

# ------------------------------------------------------------------ scenarios

scenario_happy() { # DTI 2.0 -> AUTO_APPROVE, no human touches it
  local id="${1:-happy-$(date +%s)}"
  say "SCENARIO 1 (happy) — application $id, \$200,000 on \$100,000 income, DTI 2.0"
  info "DTI 2.0 is below the 3.0 auto-approve line, so no underwriter is involved."
  create_app "$id" '["carol"]' 200000 100000 500000 '["tax-return"]'
  wait_verifications "$id"
  upload_doc "$id" tax-return carol
  wait_step_event "$id" '%reserveRateLock' "the rate lock (auto-approved, then the funding saga starts)"
  sign_app "$id" carol
  wait_status "$id" COMPLETED
  app_json "$id"
  print_events "$id"
  say "DONE — $id FUNDED. Point at http://localhost:8080/admin/workflows/loan-$id"
}

scenario_conditions() { # DTI 4.5 -> HUMAN_REVIEW, round 1 CONDITIONS, round 2 APPROVED
  local id="${1:-conditions-$(date +%s)}"
  say "SCENARIO (conditions) — application $id, \$450,000 on \$100,000 income, DTI 4.5"
  info "DTI 4.5 sits in the 3.0-6.0 human band, so this one really does queue for an underwriter."
  create_app "$id" '["alice","bob"]' 450000 100000 650000 '["pay-stub","bank-statement"]'
  wait_verifications "$id"
  upload_doc "$id" pay-stub alice
  upload_doc "$id" bank-statement bob
  wait_pending_review "$id" 1
  say "round 1: underwriter asks for a condition rather than approving"
  post_decision "$id" 1 CONDITIONS '["proof-of-insurance"]'
  say "the workflow now waits for exactly one more document — the condition it was given"
  sleep 3
  upload_doc "$id" proof-of-insurance alice
  wait_pending_review "$id" 2
  say "round 2: same workflow, same instance, round 2 of the loop"
  post_decision "$id" 2 APPROVED '[]'
  wait_step_event "$id" '%reserveRateLock' "the rate lock"
  sign_app "$id" alice
  sleep 1
  sign_app "$id" bob
  wait_status "$id" COMPLETED
  app_json "$id"
  print_events "$id"
  say "DONE — $id FUNDED after two underwriting rounds."
}

scenario_withdraw() { # DTI 4.0 -> approve -> rate lock -> withdraw -> LIFO compensation
  local id="${1:-withdraw-$(date +%s)}"
  say "SCENARIO 4 (withdraw) — application $id, \$400,000 on \$100,000 income, DTI 4.0"
  create_app "$id" '["alice","bob"]' 400000 100000 650000 '["pay-stub","bank-statement"]'
  wait_verifications "$id"
  upload_doc "$id" pay-stub alice
  upload_doc "$id" bank-statement bob
  wait_pending_review "$id" 1
  post_decision "$id" 1 APPROVED '[]'
  wait_step_event "$id" '%reserveRateLock' "the rate lock to be reserved (money is now committed)"
  # Scope to THIS loan's id. restart-loan-app.sh appends to a log that
  # start-services.sh truncates, so the file can carry lines from before a
  # scenario-2 restart; an unscoped `| tail -1` could surface one of those.
  grep -a "Reserved rate lock .* for loan $id " "$LOAN_LOG" | tail -1 || true
  say "borrower withdraws AFTER the rate lock — this is the expensive case"
  withdraw_app "$id" "found a better rate"
  info "both borrowers still sign, so the workflow reaches withdrawal gate 2 and finds the signal already waiting"
  sign_app "$id" alice
  sleep 1
  sign_app "$id" bob
  wait_status "$id" FAILED
  app_json "$id"
  say "compensation the engine ran, LIFO, without the workflow author writing a rollback path"
  grep -a "COMPENSATION.* for loan $id\b" "$LOAN_LOG" | tail -5 \
      || info "(no COMPENSATION lines for $id yet — give it 2s and re-grep $LOAN_LOG)"
  print_events "$id"
  say "DONE — $id FAILED with the rate lock released. Point at http://localhost:8080/admin/failed"
}

scenario_crash() { # park a loan mid-flight and hand the kill -9 to the presenter
  local id="${1:-crash-$(date +%s)}"
  local pidfile="$RUN_DIR/loan-application-service.pid"
  say "SCENARIO 2 (crash), phase 1 — application $id, \$350,000 on \$100,000 income, DTI 3.5"
  create_app "$id" '["alice","bob"]' 350000 100000 500000 '["pay-stub","bank-statement"]'
  wait_verifications "$id"
  upload_doc "$id" pay-stub alice
  upload_doc "$id" bank-statement bob
  wait_pending_review "$id" 1
  mkdir -p "$RUN_DIR"
  print_events "$id"
  event_rows "$id" > "$RUN_DIR/crash-$id-before-rows.txt"
  echo "$id" > "$RUN_DIR/crash-last-id"
  local before
  before=$(wc -l < "$RUN_DIR/crash-$id-before-rows.txt" | tr -d ' ')
  say "PARKED. $before durable events recorded. The workflow is holding nothing in memory that matters."
  info "snapshot of those $before rows: $RUN_DIR/crash-$id-before-rows.txt"
  cat <<EOF

  Now, on stage, in this order:

    1.  kill -9 \$(cat $pidfile)              <- no graceful shutdown, no drain
    2.  demo/scripts/restart-loan-app.sh      <- restarts ONLY the process you killed
    3.  demo/scripts/drive-loan.sh finish $id

  (Step 2 is not start-services.sh: that starts all four JVMs and refuses to
  run while 8092/8093/8080 are still healthy, which they are.)

  Expected after step 3: the loan completes, and the $before rows above are
  byte-identical afterwards — they replayed from Postgres, they did not run
  again.

EOF
}

scenario_finish() { # resume the parked loan after the restart
  local id="${1:-}"
  [ -n "$id" ] || id="$(cat "$RUN_DIR/crash-last-id" 2>/dev/null || true)"
  [ -n "$id" ] || die "usage: drive-loan.sh finish <applicationId>"
  local beforefile="$RUN_DIR/crash-$id-before-rows.txt" before=0
  [ -f "$beforefile" ] || die "no pre-crash snapshot for $id — run 'drive-loan.sh crash' first"
  before=$(wc -l < "$beforefile" | tr -d ' ')
  say "SCENARIO 2 (crash), phase 2 — resuming $id after the restart"
  info "pre-crash snapshot: $before durable events"
  wait_pending_review "$id" 1 120
  post_decision "$id" 1 APPROVED '[]'
  wait_step_event "$id" '%reserveRateLock' "the rate lock"
  sign_app "$id" alice
  sleep 1
  sign_app "$id" bob
  wait_status "$id" COMPLETED
  app_json "$id"
  print_events "$id"
  say "PROOF 1 — the $before rows recorded before the kill -9 are byte-identical now"
  if event_rows "$id" | head -n "$before" | diff -u "$beforefile" - > "$RUN_DIR/crash-$id-diff.txt"; then
    info "diff is empty. Nothing was rewritten, renumbered, or re-executed."
  else
    cat "$RUN_DIR/crash-$id-diff.txt"
    die "the pre-crash event rows changed — that would be an engine bug, not a demo bug."
  fi

  local dupes
  dupes=$(psql_q "select count(*) from (
                    select e.sequence_number from maestro_workflow_event e
                      join maestro_workflow_instance i on i.id = e.workflow_instance_id
                     where i.workflow_id = 'loan-$id'
                     group by e.sequence_number having count(*) > 1) d" | tr -d '[:space:]')
  say "PROOF 2 — duplicate sequence numbers after the crash: $dupes  (must be 0)"
  [ "$dupes" = "0" ] || die "duplicate sequence numbers found — that would be an engine bug, not a demo bug."
  say "DONE — $id FUNDED across a kill -9. Nothing re-executed."
}

# ----------------------------------------------------------------------- main

case "${1:-}" in
  happy)      shift; scenario_happy "${1:-}" ;;
  conditions) shift; scenario_conditions "${1:-}" ;;
  withdraw)   shift; scenario_withdraw "${1:-}" ;;
  crash)      shift; scenario_crash "${1:-}" ;;
  finish)     shift; scenario_finish "${1:-}" ;;
  events)     shift; [ -n "${1:-}" ] || usage; print_events "$1" ;;
  *)          usage ;;
esac
