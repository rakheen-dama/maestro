#!/usr/bin/env bash
# Evaluator self-test: perf/eval/selftest.sh <a PASSING smoke result directory>
#
# Copies the run's identity.json and measurements.json to a scratch directory,
# doctors them (and the resulting check list) one fault at a time, re-runs the
# evaluator and asserts the verdict each fault must produce. No Docker, no
# network: it exercises only perf/lib/evaluate.sh and perf/eval/*.jq.
# Exit 0 when every case holds, 1 otherwise.
# SC2016: the single-quoted '$x' strings are jq filters, expanded by jq.
# shellcheck disable=SC2016
set -Eeuo pipefail

PERF_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
src=${1:?usage: perf/eval/selftest.sh <passing smoke result dir>}
scenario=$(jq -r .scenario "${src}/identity.json")
work=$(mktemp -d "${TMPDIR:-/tmp}/perf-selftest.XXXXXX")
trap 'rm -rf "$work"' EXIT
failures=0

# evaluate_with <case> <jq filter applied to measurements.json> [<jq filter applied to identity.json>]
# Prints the doctored run's verdict and its check statuses; an evaluator that
# crashes (no result.json) prints the verdict CRASH (the driver reports that
# as ERROR).
evaluate_with() {
  local dir="${work}/$1"
  mkdir -p "$dir"
  jq "${3:-.}" "${src}/identity.json" >"${dir}/identity.json"
  jq "$2" "${src}/measurements.json" >"${dir}/measurements.json"
  if ! bash "${PERF_DIR}/lib/evaluate.sh" "$dir" "$scenario" "" >/dev/null 2>"${dir}/stderr"; then
    printf '{"verdict":"CRASH","checks":{}}\n'
    return 0
  fi
  jq -c '{verdict, checks: (.checks | map({key: .id, value: .status}) | from_entries)}' "${dir}/result.json"
}
# check_field <case> <check id> <jq path under the check>: one field of a doctored run's check.
check_field() {
  jq -c --arg id "$2" ".checks[] | select(.id == \$id) | $3" "${work}/$1/result.json" 2>/dev/null || echo CRASH
}
replicas=$(jq .config.replicas "${src}/identity.json")

# verdict_of <jq filter applied to the passing run's checks>
verdict_of() {
  jq -n -r -L "${PERF_DIR}/eval" --slurpfile r "${work}/pass/result.json" \
    "include \"common\"; \$r[0].checks | ($1) | verdict"
}

expect() {
  local name=$1 expected=$2 actual=$3
  if [[ "$actual" == "$expected" ]]; then
    printf 'ok    %-58s %s\n' "$name" "$actual"
  else
    printf 'FAIL  %-58s expected %s, got %s\n' "$name" "$expected" "$actual"
    failures=$((failures + 1))
  fi
}

out=$(evaluate_with pass '.')
expect "unchanged run" PASS "$(jq -r .verdict <<<"$out")"

out=$(evaluate_with dropped '.k6[-1].droppedIterations = 3')
expect "dropped iterations > 0: verdict" INVALID "$(jq -r .verdict <<<"$out")"
expect "dropped iterations > 0: validity.droppedIterations" INVALID "$(jq -r '.checks["validity.droppedIterations"]' <<<"$out")"

out=$(evaluate_with nosummary '.k6[-1] = {phase: .k6[-1].phase, script: .k6[-1].script, exitCode: 0, summaryMissing: true}')
expect "k6 exit 0 without a summary: verdict" INVALID "$(jq -r .verdict <<<"$out")"
expect "k6 exit 0 without a summary: validity.k6" INVALID "$(jq -r '.checks["validity.k6"]' <<<"$out")"
expect "k6 exit 0 without a summary: validity.droppedIterations" INVALID "$(jq -r '.checks["validity.droppedIterations"]' <<<"$out")"

out=$(evaluate_with nofield '.k6[-1] |= del(.droppedIterations, .summaryMissing)')
expect "k6 record without droppedIterations: verdict" INVALID "$(jq -r .verdict <<<"$out")"

out=$(evaluate_with k6exit '.k6[-1].exitCode = 107')
expect "k6 exit 107: validity.k6" INVALID "$(jq -r '.checks["validity.k6"]' <<<"$out")"

out=$(evaluate_with k6threshold '.k6[-1].exitCode = 99')
expect "k6 exit 99 (advisory thresholds): verdict" PASS "$(jq -r .verdict <<<"$out")"

out=$(evaluate_with noharness '.window.harnessCalls = 0')
expect "harnessCalls 0: validity.roleSplit" INVALID "$(jq -r '.checks["validity.roleSplit"]' <<<"$out")"

out=$(evaluate_with dealloc '.background.window.dealloc = 1')
expect "background window evicted entries: validity.pgssComplete" INVALID "$(jq -r '.checks["validity.pgssComplete"]' <<<"$out")"

out=$(evaluate_with incomplete '.runStatus.byStatus = {COMPLETED: 19, FAILED: 1}')
expect "one FAILED run: verdict" FAIL "$(jq -r .verdict <<<"$out")"

out=$(evaluate_with pass2 '.')
expect "warm-up all COMPLETED: validity.warmup" PASS "$(jq -r '.checks["validity.warmup"]' <<<"$out")"

out=$(evaluate_with wu-timeout '.warmup.drain.timedOut = true | .warmup.runStatus = {total: .warmup.count, byStatus: {COMPLETED: (.warmup.count - 15), RUNNING: 15}}')
expect "warm-up timed out, 15 RUNNING: verdict" INVALID "$(jq -r .verdict <<<"$out")"
expect "warm-up timed out, 15 RUNNING: validity.warmup" INVALID "$(jq -r '.checks["validity.warmup"]' <<<"$out")"

out=$(evaluate_with wu-running '.warmup.runStatus = {total: .warmup.count, byStatus: {COMPLETED: (.warmup.count - 1), RUNNING: 1}}')
expect "warm-up not timed out but 1 RUNNING: validity.warmup" INVALID "$(jq -r '.checks["validity.warmup"]' <<<"$out")"

out=$(evaluate_with wu-failed '.warmup.runStatus = {total: .warmup.count, byStatus: {COMPLETED: (.warmup.count - 1), FAILED: 1}}')
expect "warm-up with 1 FAILED: validity.warmup" INVALID "$(jq -r '.checks["validity.warmup"]' <<<"$out")"

out=$(evaluate_with wu-missing 'del(.warmup)')
expect "warm-up record missing: verdict" INVALID "$(jq -r .verdict <<<"$out")"
expect "warm-up record missing: validity.warmup" INVALID "$(jq -r '.checks["validity.warmup"]' <<<"$out")"

out=$(evaluate_with bg-missing 'del(.background)')
expect "background missing: verdict (no jq crash)" INVALID "$(jq -r .verdict <<<"$out")"
expect "background missing: validity.background" INVALID "$(jq -r '.checks["validity.background"]' <<<"$out")"

out=$(evaluate_with bg-string '.background = "garbage"')
expect "background not an object: validity.background" INVALID "$(jq -r '.checks["validity.background"]' <<<"$out")"

out=$(evaluate_with bg-nowindow '.background |= del(.window)')
expect "background without window: validity.background" INVALID "$(jq -r '.checks["validity.background"]' <<<"$out")"

out=$(evaluate_with bg-zero '.background.window.windowSecs = 0')
expect "background window of 0 s: validity.background" INVALID "$(jq -r '.checks["validity.background"]' <<<"$out")"

out=$(evaluate_with bg-one-tick '.background.window.windowSecs = (.background.timerPollSecs * 1.9)')
expect "background spans < 2 timer-poll ticks: validity.background" INVALID "$(jq -r '.checks["validity.background"]' <<<"$out")"

# Timer-poll ticks are COUNTED from the idle window's statements: calls of the
# due-timer query (one per tick, leader only); the leader-election upsert
# (every node, every tick) bounds from below. bg_ticks <due calls> <election
# calls> sets both; expected ticks = floor(windowSecs / timerPollSecs).
due_re='FROM maestro_workflow_timer WHERE fire_at'
el_re='^INSERT INTO maestro_leader_election'
bg_ticks() { # <due-timer calls expr> <election calls expr>, $e = expected ticks
  printf '(.background.window.windowSecs / .background.timerPollSecs | floor) as $e
    | .background.window.top |= map(
        if (.query | test("%s")) then .calls = (%s)
        elif (.query | test("%s")) then .calls = (%s) else . end)
    | .background.window |= (.maestroCalls = ([.top[].calls] | add) | .maestroCallsTopLevel = .maestroCalls)' "$due_re" "$1" "$el_re" "$2"
}
expected_ticks=$(jq '.background.window.windowSecs / .background.timerPollSecs | floor' "${src}/measurements.json")
out=$(evaluate_with bg-ticks-real '.')
expect "real idle window: observedTicks recorded (= expected ${expected_ticks})" "${expected_ticks}" "$(check_field bg-ticks-real validity.background .value.observedTicks)"
expect "real idle window: observed = expected: validity.background" PASS "$(jq -r '.checks["validity.background"]' <<<"$out")"
out=$(evaluate_with bg-zeroed '.background.window |= (.maestroCalls = 0 | .maestroCallsTopLevel = 0 | .maestroStatements = 0 | .top = [])')
expect "zeroed idle window (no calls, no statements): validity.background" INVALID "$(jq -r '.checks["validity.background"]' <<<"$out")"
expect "zeroed idle window: verdict" INVALID "$(jq -r .verdict <<<"$out")"
out=$(evaluate_with bg-no-poller "$(printf '.background.window.top |= map(select(.query | test("%s") | not))' "$due_re")")
expect "due-timer query absent from the idle window: validity.background" INVALID "$(jq -r '.checks["validity.background"]' <<<"$out")"
out=$(evaluate_with bg-ticks-plus1 "$(bg_ticks '$e + 1' "$replicas"' * ($e + 1)')")
expect "observed = expected + 1: validity.background" PASS "$(jq -r '.checks["validity.background"]' <<<"$out")"
out=$(evaluate_with bg-ticks-minus1 "$(bg_ticks '$e - 1' "$replicas"' * ($e - 1)')")
expect "observed = expected - 1: validity.background" PASS "$(jq -r '.checks["validity.background"]' <<<"$out")"
out=$(evaluate_with bg-ticks-minus2 "$(bg_ticks '$e - 2' "$replicas"' * ($e - 2)')")
expect "observed = expected - 2: validity.background" INVALID "$(jq -r '.checks["validity.background"]' <<<"$out")"
out=$(evaluate_with bg-ticks-plus2 "$(bg_ticks '$e + 2' "$replicas"' * ($e + 2)')")
expect "observed = expected + 2: validity.background" INVALID "$(jq -r '.checks["validity.background"]' <<<"$out")"
out=$(evaluate_with bg-two-ticks "$(bg_ticks 2 "$replicas"' * 2') | .background.window.windowSecs = (.background.timerPollSecs * 2)")
expect "window of exactly 2 ticks, 2 recorded: validity.background" PASS "$(jq -r '.checks["validity.background"]' <<<"$out")"
out=$(evaluate_with bg-one-observed "$(bg_ticks 1 "$replicas") | .background.window.windowSecs = (.background.timerPollSecs * 2)")
expect "window of 2 ticks, 1 recorded (< 2): validity.background" INVALID "$(jq -r '.checks["validity.background"]' <<<"$out")"
out=$(evaluate_with bg-election-low "$(bg_ticks '$e' "$replicas"' * ($e - 1) - 1')")
expect "election upserts < replicas x (ticks - 1): validity.background" INVALID "$(jq -r '.checks["validity.background"]' <<<"$out")"
out=$(evaluate_with bg-election-edge "$(bg_ticks '$e' "$replicas"' * ($e - 1)')")
expect "election upserts = replicas x (ticks - 1): validity.background" PASS "$(jq -r '.checks["validity.background"]' <<<"$out")"
out=$(evaluate_with bg-calls-contradict '.background.window.maestroCalls = 3')
expect "maestroCalls below its own poller statements: validity.background" INVALID "$(jq -r '.checks["validity.background"]' <<<"$out")"

# Timestamps: RFC 3339 with Z, numeric offsets and fractional seconds.
ts_of() { jq -n -r -L "${PERF_DIR}/eval" --arg t "$1" 'include "common"; $t | ts_secs'; }
expect "ts_secs: +02:00 equals the same instant in Z" "$(ts_of 2026-11-12T17:08:22Z)" "$(ts_of 2026-11-12T19:08:22+02:00)"
expect "ts_secs: -13:45 equals the same instant in Z" "$(ts_of 2026-11-12T17:08:22Z)" "$(ts_of 2026-11-12T03:23:22-13:45)"
expect "ts_secs: +0200 (no colon)" "$(ts_of 2026-11-12T17:08:22Z)" "$(ts_of 2026-11-12T19:08:22+0200)"
expect "ts_secs: Z (epoch computed independently by Python calendar.timegm)" 1794503302 "$(ts_of 2026-11-12T17:08:22Z)"
expect "ts_secs: fractional seconds kept (.25Z)" 1794503302.25 "$(ts_of 2026-11-12T17:08:22.25Z)"
expect "ts_secs: fractional with +00:00 (Postgres form)" 1794503302.054477 "$(ts_of 2026-11-12T17:08:22.054477+00:00)"
expect "ts_secs: not a timestamp" null "$(ts_of not-a-time)"
shift_ts() { # <seconds offset> <suffix>: rewrite every window timestamp into that offset
  printf 'def sh: . as $t | ((.[0:19] + "Z" | fromdateiso8601) + %s | strftime("%%Y-%%m-%%dT%%H:%%M:%%S"))
      + ($t | if test("[.][0-9]+") then capture("(?<f>[.][0-9]+)").f else "" end) + "%s";
    (.background.window, .window) |= (.resetAt |= sh | .snapshotAt |= sh)' "$1" "$2"
}
# (the real timestamps are +00:00, so their first 19 characters are UTC)
sweep_offsets() { check_field "$1" validity.sweeps '[.value.windows[] | .sinceEarliestNodeStartSecs]'; }
out=$(evaluate_with ts-plus2 "$(shift_ts 7200 +02:00)")
expect "window timestamps in +02:00: verdict (no evaluator ERROR)" PASS "$(jq -r .verdict <<<"$out")"
expect "window timestamps in +02:00: validity.sweeps" PASS "$(jq -r '.checks["validity.sweeps"]' <<<"$out")"
expect "window timestamps in +02:00: same offsets from node start" \
  "$(evaluate_with ts-utc '.' >/dev/null; sweep_offsets ts-utc)" "$(sweep_offsets ts-plus2)"
out=$(evaluate_with ts-minus1345 "$(shift_ts -49500 -13:45)")
expect "window timestamps in -13:45: verdict" PASS "$(jq -r .verdict <<<"$out")"
expect "window timestamps in -13:45: validity.sweeps" PASS "$(jq -r '.checks["validity.sweeps"]' <<<"$out")"
out=$(evaluate_with ts-garbage '.window.resetAt = "not-a-time"')
expect "unparseable window timestamp: validity.sweeps (cannot account)" INVALID "$(jq -r '.checks["validity.sweeps"]' <<<"$out")"
expect "unparseable window timestamp: verdict" INVALID "$(jq -r .verdict <<<"$out")"
# A failure inside an advisory is that advisory's WARN, never an ERROR.
out=$(evaluate_with adv-latency '.latency = "garbage"')
expect "latency record not an object: advisory.latency" WARN "$(jq -r '.checks["advisory.latency"]' <<<"$out")"
expect "latency record not an object: verdict unchanged" PASS "$(jq -r .verdict <<<"$out")"
out=$(evaluate_with adv-dirty-unknown '.' '.repo.dirty = null')
expect "dirtiness not recorded: advisory.dirtyTree" WARN "$(jq -r '.checks["advisory.dirtyTree"]' <<<"$out")"

# validity.sweeps: periodic sweeps accounted for explicitly from the nodes'
# reported intervals (lock cleaner: passes predicted, calls subtracted;
# janitor / retention: none allowed in a window).
expect "real run: validity.sweeps" PASS "$(jq -r '.checks["validity.sweeps"]' <<<"$(evaluate_with sw-real '.')")"
expect "real run: lock-cleanup interval is the node's reported one (600 s)" 600 \
  "$(check_field sw-real validity.sweeps '.value.nodes[0].lockCleanupSecs')"
out=$(evaluate_with sw-jvm-x '.' '(.nodes, .nodesAfter) |= map(.jvmStartMillis = "x")')
expect "jvmStartMillis not a number: validity.sweeps (no evaluator crash)" INVALID "$(jq -r '.checks["validity.sweeps"]' <<<"$out")"
out=$(evaluate_with sw-no-nodes '.' '.nodes = [] | .nodesAfter = []')
expect "no node recorded: validity.sweeps" INVALID "$(jq -r '.checks["validity.sweeps"]' <<<"$out")"
out=$(evaluate_with sw-no-interval '.' '.nodes |= map(del(.lockCleanupInterval))')
expect "node does not report lockCleanupInterval: validity.sweeps" INVALID "$(jq -r '.checks["validity.sweeps"]' <<<"$out")"
out=$(evaluate_with sw-no-reported '.' '.nodes |= map(del(.reportedAtMillis))')
expect "node does not report reportedAtMillis: validity.sweeps" INVALID "$(jq -r '.checks["validity.sweeps"]' <<<"$out")"
out=$(evaluate_with sw-unclassified '.window |= del(.sweepCalls)')
expect "window without sweepCalls: validity.sweeps" INVALID "$(jq -r '.checks["validity.sweeps"]' <<<"$out")"
out=$(evaluate_with sw-janitor '.window.sweepCalls.signalJanitor = 1')
expect "signal-janitor call inside the load window: validity.sweeps" INVALID "$(jq -r '.checks["validity.sweeps"]' <<<"$out")"
out=$(evaluate_with sw-retention '.background.window.sweepCalls.retention = 3')
expect "retention calls inside the idle window: validity.sweeps" INVALID "$(jq -r '.checks["validity.sweeps"]' <<<"$out")"
out=$(evaluate_with sw-janitor-due '.' '.nodes |= map(.signalJanitorInterval = "PT10S")')
expect "reported janitor interval 10 s (a pass predicted in a window): validity.sweeps" INVALID "$(jq -r '.checks["validity.sweeps"]' <<<"$out")"
out=$(evaluate_with sw-retention-off '.' '.nodes |= map(.retentionEnabled = false | .retentionInterval = "PT10S")')
expect "retention disabled (its interval irrelevant): validity.sweeps" PASS "$(jq -r '.checks["validity.sweeps"]' <<<"$out")"
# Lock cleaner around node start + 600 s: the load window moved to straddle
# or contain the first pass. jvm = earliest node start, rep = latest report.
jvm=$(jq '[.nodes[].jvmStartMillis] | min / 1000' "${src}/identity.json")
rep=$(jq '[.nodes[].reportedAtMillis] | max / 1000' "${src}/identity.json")
move_window() { # <start offset from jvm> <end offset from jvm or "rep+N"> <lockCleaner calls>
  printf '.window.resetAt = (%s + %s | todate) | .window.snapshotAt = (%s | todate) | .window.sweepCalls.lockCleaner = %s' \
    "$jvm" "$1" "$2" "$3"
}
out=$(evaluate_with sw-straddle-0 "$(move_window 590 "$jvm + 610" 0)")
expect "window straddles the first lock-cleaner mark, 0 calls (pass possible): validity.sweeps" PASS "$(jq -r '.checks["validity.sweeps"]' <<<"$out")"
out=$(evaluate_with sw-straddle-2 "$(move_window 590 "$jvm + 610" 2)")
expect "window straddles the first mark, 2 calls (one pass): validity.sweeps" PASS "$(jq -r '.checks["validity.sweeps"]' <<<"$out")"
out=$(evaluate_with sw-straddle-4 "$(move_window 590 "$jvm + 610" 4)")
expect "window straddles the first mark, 4 calls (two passes, one possible): validity.sweeps" INVALID "$(jq -r '.checks["validity.sweeps"]' <<<"$out")"
out=$(evaluate_with sw-contains-0 "$(move_window 500 "$rep + 700" 0)")
expect "window certainly contains the first pass, 0 calls: validity.sweeps" INVALID "$(jq -r '.checks["validity.sweeps"]' <<<"$out")"
out=$(evaluate_with sw-contains-2 "$(move_window 500 "$rep + 700" 2)")
expect "window certainly contains the first pass, 2 calls: validity.sweeps" PASS "$(jq -r '.checks["validity.sweeps"]' <<<"$out")"
out=$(evaluate_with sw-early-2 '.window.sweepCalls.lockCleaner = 2')
expect "lock-cleaner calls in a window that ends long before 600 s: validity.sweeps" INVALID "$(jq -r '.checks["validity.sweeps"]' <<<"$out")"
out=$(evaluate_with sw-interval-60 '.window.sweepCalls.lockCleaner = 0' '.nodes |= map(.lockCleanupInterval = "PT20S")')
expect "reported cleanup interval 20 s, 0 calls in a window holding passes: validity.sweeps" INVALID "$(jq -r '.checks["validity.sweeps"]' <<<"$out")"
# Subtraction: two lock-cleaner calls added to the load window change nothing
# when classified, and raise the net figure when they are not.
net_of() { check_field "$1" gate.statementsMeasured '.value.statementsPerWorkflowNet'; }
evaluate_with sw-sub-base "$(move_window 590 "$jvm + 610" 0)" >/dev/null
evaluate_with sw-sub-2 "$(move_window 590 "$jvm + 610" 2) | .window.maestroCalls += 2 | .window.maestroCallsTopLevel += 2" >/dev/null
evaluate_with sw-sub-unclassified "$(move_window 590 "$jvm + 610" 0) | .window.maestroCalls += 2 | .window.maestroCallsTopLevel += 2" >/dev/null
expect "two classified lock-cleaner calls: net statements per workflow unchanged" "$(net_of sw-sub-base)" "$(net_of sw-sub-2)"
expect "the same two calls unclassified: net differs (the subtraction is real)" yes \
  "$([[ "$(net_of sw-sub-base)" != "$(net_of sw-sub-unclassified)" ]] && echo yes || echo no)"
expect "two classified lock-cleaner calls: recorded as subtracted" 2 \
  "$(jq -r '.measurements.statements.lockCleanerCallsSubtracted.load' "${work}/sw-sub-2/result.json")"

# statements_gate, the shared baseline-relative gate: symmetric,
# band = tolerance + relativeAllowance x expected (0.25 / 0.10 here).
gate() { # <net|null> <tolerance> <expected|null> <relativeAllowance json> <jq path>
  jq -n -r -L "${PERF_DIR}/eval" --argjson n "$1" --argjson t "$2" --argjson e "$3" --argjson a "$4" \
    "include \"common\"; statements_gate(\"gate.x\"; {statementsPerWorkflowNet: \$n, netTolerancePerWorkflow: \$t}; \$e; \$a) | $5" 2>/dev/null \
    || echo CRASH
}
gate_of() { # <net|null> <tolerance> <expected|null> [relativeAllowance json, default: the 3-argument form]
  if (($# == 4)); then
    gate "$1" "$2" "$3" "$4" '.status'
  else
    jq -n -r -L "${PERF_DIR}/eval" --argjson n "$1" --argjson t "$2" --argjson e "$3" \
      'include "common"; statements_gate("gate.x"; {statementsPerWorkflowNet: $n, netTolerancePerWorkflow: $t}; $e) | .status'
  fi
}
gate_verdict() { # <net> <tolerance> <expected> <relativeAllowance json>: the verdict of a run whose only check is this gate
  gate "$1" "$2" "$3" "$4" '[.] | verdict'
}
expect "gate: net = expected" PASS "$(gate_of 20 0.25 20)"
expect "gate: upper edge expected + tolerance (20.25)" PASS "$(gate_of 20.25 0.25 20)"
expect "gate: lower edge expected - tolerance (19.75)" PASS "$(gate_of 19.75 0.25 20)"
expect "gate: just above the band (20.26)" FAIL "$(gate_of 20.26 0.25 20)"
expect "gate: just below the band (19.74)" FAIL "$(gate_of 19.74 0.25 20)"
expect "gate: net 5 vs expected 20" FAIL "$(gate_of 5 0.25 20)"
expect "gate: net 0" FAIL "$(gate_of 0 0.25 20)"
expect "gate: net -3" FAIL "$(gate_of -3 0.25 20)"
expect "gate: net missing" FAIL "$(gate_of null 0.25 20)"
expect "gate: net 0 without a baseline (FAIL beats NO_BASELINE)" FAIL "$(gate_of 0 0.25 null)"
expect "gate: no expected value" NO_BASELINE "$(gate_of 21 0.25 null)"
expect "gate: below-band message asks for a deliberate re-record" yes \
  "$(gate 5 0.25 20 0 .detail | grep -q 're-record the baseline deliberately (perf/run.sh <scenario> --record-baseline on a clean tree)' && echo yes || echo no)"
# Relative allowance 0.10 on expected 20, tolerance 0.15: band 0.15 + 2 = 2.15.
expect "gate: allowance 0.10 x 20 + tolerance 0.15 = band 2.15" 2.15 "$(gate 20 0.15 20 0.10 .limit.band)"
expect "gate: tolerance, relativeAllowance, allowance and band recorded apart" '{"tolerance":0.15,"relativeAllowance":0.1,"allowance":2,"band":2.15,"min":17.85,"max":22.15}' \
  "$(gate 20 0.15 20 0.1 '.limit | {tolerance, relativeAllowance, allowance, band, min, max} | tojson')"
expect "gate: allowance 0.10, upper edge 22.15" PASS "$(gate_of 22.15 0.15 20 0.10)"
expect "gate: allowance 0.10, lower edge 17.85" PASS "$(gate_of 17.85 0.15 20 0.10)"
expect "gate: allowance 0.10, just above (22.16)" FAIL "$(gate_of 22.16 0.15 20 0.10)"
expect "gate: allowance 0.10, just below (17.84)" FAIL "$(gate_of 17.84 0.15 20 0.10)"
expect "gate: allowance 0.10, net 500 (far outside)" FAIL "$(gate_of 500 0.15 20 0.10)"
expect "gate: allowance 0.5 (the maximum) accepted" PASS "$(gate_of 29 0.15 20 0.5)"
expect "gate: allowance 1000" ERROR "$(gate_of 500 0.15 20 1000)"
expect "gate: allowance 1000: verdict" ERROR "$(gate_verdict 500 0.15 20 1000)"
expect "gate: allowance 0.51" ERROR "$(gate_of 20 0.15 20 0.51)"
expect "gate: allowance -0.1" ERROR "$(gate_of 20 0.15 20 -0.1)"
expect "gate: allowance -0.1: verdict" ERROR "$(gate_verdict 20 0.15 20 -0.1)"
expect "gate: allowance \"x\"" ERROR "$(gate_of 20 0.15 20 '"x"')"
expect "gate: allowance \"x\": verdict" ERROR "$(gate_verdict 20 0.15 20 '"x"')"
expect "gate: allowance null" ERROR "$(gate_of 20 0.15 20 null)"
expect "gate: expected not a number" ERROR "$(gate_of 20 0.15 '"20"' 0)"
# Tolerance cap: INVALID (not PASS) when tolerance > 25 % of expected.
expect "gate: tolerance 5 = 25 % of 20 (at the cap), net 20" PASS "$(gate_of 20 5 20)"
expect "gate: tolerance 5.01 > 25 % of 20, net 20" INVALID "$(gate_of 20 5.01 20)"
expect "gate: tolerance 50, net 5.5 vs 20 (the reviewer's case)" INVALID "$(gate_of 5.5 50 20)"
expect "gate: tolerance over the cap: verdict" INVALID "$(gate_verdict 20 5.01 20 0)"
expect "gate: tolerance at the cap, net 25.01 (outside)" FAIL "$(gate_of 25.01 5 20)"

# Verdict precedence end to end (the table in common.jq's and run.sh's
# headers). A probe scenario evaluates the real run's common checks plus
# injected scenario checks (.probeChecks) through the real lib/evaluate.sh, so
# result.json and summary.md are written exactly as for a run. Each case
# prints "<verdict> <exit code (verdict_rc)> <listed>", where listed = yes when
# result.json holds every common check plus every injected one and summary.md
# has a row for each of them with its status.
probe_perf="${work}/probe-perf"
mkdir -p "$probe_perf"
cp -R "${PERF_DIR}/eval" "${PERF_DIR}/lib" "$probe_perf/"
printf '%s\n' 'include "common"; $identity[0] as $id | $measurements[0] as $m | result($id; $m; $m.probeChecks)' \
  >"${probe_perf}/eval/verdict-probe.jq"
probe() { # <case> <checks: class:status,…> [<jq filter doctoring measurements>]
  local dir="${work}/$1" checks="" spec cls st nprobe=0 v rc listed=yes n want id row
  mkdir -p "$dir"
  for spec in ${2//,/ }; do
    cls=${spec%%:*} st=${spec#*:} nprobe=$((nprobe + 1))
    checks+="${checks:+, }{id: \"${cls}.probe${nprobe}\", class: \"${cls}\", status: \"${st}\", value: null, limit: null, detail: \"probe\"}"
  done
  cp "${src}/identity.json" "$dir/"
  jq "${3:-.} | .probeChecks = [${checks}]" "${src}/measurements.json" >"${dir}/measurements.json"
  if ! bash "${probe_perf}/lib/evaluate.sh" "$dir" verdict-probe "" >/dev/null 2>"${dir}/stderr"; then
    echo "CRASH - no"
    return 0
  fi
  v=$(jq -r .verdict "${dir}/result.json")
  rc=$( (source "${PERF_DIR}/lib/common.sh" && verdict_rc "$v") 2>/dev/null || echo '?')
  n=$(jq '.checks | length' "${dir}/result.json")
  want=$((common_checks + nprobe))
  ((n == want)) || listed=no
  while IFS=$'\t' read -r id cls st; do
    row="| ${id} | ${cls} | **${st}** |"
    grep -qF -- "$row" "${dir}/summary.md" || listed=no
  done < <(jq -r '.checks[] | [.id, .class, .status] | @tsv' "${dir}/result.json")
  for ((n = 1; n <= nprobe; n++)); do
    jq -e --arg p "probe${n}" 'any(.checks[]; .id | endswith($p))' "${dir}/result.json" >/dev/null || listed=no
  done
  printf '%s %s %s' "$v" "$rc" "$listed"
}
common_checks=$(jq '.checks | length' "${work}/pass/result.json")
common_checks=$((common_checks - $(jq '[.checks[] | select(.id | test("^(validity\\.(background|roleSplit)|correctness\\.|gate\\.|advisory\\.latency)"))] | length' "${work}/pass/result.json")))
expect "precedence: no scenario check (f)" "PASS 0 yes" "$(probe p-none '')"
expect "precedence: correctness FAIL + gate INVALID (c beats e)" "FAIL 1 yes" "$(probe p-cfail-ginv correctness:FAIL,gate:INVALID)"
expect "precedence: gate INVALID alone (e)" "INVALID 2 yes" "$(probe p-ginv gate:INVALID)"
expect "precedence: validity INVALID + correctness FAIL (b beats c)" "INVALID 2 yes" \
  "$(probe p-vinv-cfail correctness:FAIL '.k6[-1].exitCode = 107')"
expect "precedence: gate FAIL + gate INVALID (d beats e)" "FAIL 1 yes" "$(probe p-gfail-ginv gate:FAIL,gate:INVALID)"
expect "precedence: gate WARN + gate INVALID (d beats e)" "FAIL 1 yes" "$(probe p-gwarn-ginv gate:WARN,gate:INVALID)"
expect "precedence: correctness INVALID alone (c: counts as FAIL)" "FAIL 1 yes" "$(probe p-cinv correctness:INVALID)"
expect "precedence: correctness INVALID + gate INVALID (c beats e)" "FAIL 1 yes" "$(probe p-cinv-ginv correctness:INVALID,gate:INVALID)"
expect "precedence: correctness and gate NO_BASELINE (f)" "PASS 0 yes" "$(probe p-nobase correctness:NO_BASELINE,gate:NO_BASELINE)"
expect "precedence: advisory WARN + gate INVALID (e)" "INVALID 2 yes" "$(probe p-awarn-ginv advisory:WARN,gate:INVALID)"
expect "precedence: gate ERROR + correctness FAIL + validity INVALID (a)" "ERROR 3 yes" \
  "$(probe p-err-gate gate:ERROR,correctness:FAIL '.k6[-1].exitCode = 107')"
expect "precedence: correctness ERROR (a)" "ERROR 3 yes" "$(probe p-err-corr correctness:ERROR)"
expect "precedence: advisory ERROR (a)" "ERROR 3 yes" "$(probe p-err-adv advisory:ERROR)"
expect "precedence: validity ERROR + gate INVALID (a)" "ERROR 3 yes" "$(probe p-err-val validity:ERROR,gate:INVALID)"
expect "precedence: unknown status PASSED on a correctness check (a)" "ERROR 3 yes" "$(probe p-unknown correctness:PASSED)"
# An advisory reports only PASS or WARN; any other status is a scenario
# programming error and must not pass silently (a).
expect "precedence: advisory FAIL (a: an advisory never FAILs)" "ERROR 3 yes" "$(probe p-adv-fail advisory:FAIL)"
expect "precedence: advisory INVALID (a)" "ERROR 3 yes" "$(probe p-adv-inv advisory:INVALID)"
expect "precedence: advisory NO_BASELINE (a)" "ERROR 3 yes" "$(probe p-adv-nobase advisory:NO_BASELINE)"
expect "precedence: advisory PASS and WARN (f)" "PASS 0 yes" "$(probe p-adv-ok advisory:PASS,advisory:WARN)"
expect "empty check list" ERROR "$(verdict_of '[]')"
expect "check list not an array" ERROR "$(verdict_of '{}')"

expect "unknown status PASSED" ERROR "$(verdict_of '.[0].status = "PASSED"')"
expect "unknown class" ERROR "$(verdict_of '.[0].class = "validty"')"
expect "validity check WARN" INVALID "$(verdict_of 'map(if .id == "validity.identity" then .status = "WARN" else . end)')"
expect "validity check NO_BASELINE" INVALID "$(verdict_of 'map(if .id == "validity.noRestart" then .status = "NO_BASELINE" else . end)')"
expect "gate INVALID (could not be judged)" INVALID "$(verdict_of 'map(if .class == "gate" then .status = "INVALID" else . end)')"
expect "any check ERROR" ERROR "$(verdict_of 'map(if .class == "gate" then .status = "ERROR" else . end)')"
expect "gate NO_BASELINE" PASS "$(verdict_of 'map(if .class == "gate" then .status = "NO_BASELINE" else . end)')"
expect "gate WARN" FAIL "$(verdict_of 'map(if .class == "gate" then .status = "WARN" else . end)')"
expect "correctness INVALID" FAIL "$(verdict_of 'map(if .id == "correctness.allCompleted" then .status = "INVALID" else . end)')"
expect "advisory WARN" PASS "$(verdict_of 'map(if .class == "advisory" then .status = "WARN" else . end)')"
expect "validity INVALID beats gate FAIL" INVALID "$(verdict_of 'map(if .class == "gate" then .status = "FAIL" elif .id == "validity.k6" then .status = "INVALID" else . end)')"

if ((failures > 0)); then
  echo "selftest: ${failures} case(s) failed"
  exit 1
fi
echo "selftest: all cases hold"
