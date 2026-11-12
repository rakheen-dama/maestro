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
out=$(evaluate_with ts-plus2 "$(shift_ts 7200 +02:00)")
expect "window timestamps in +02:00: verdict (no evaluator ERROR)" PASS "$(jq -r .verdict <<<"$out")"
expect "window timestamps in +02:00: advisory.sweepOverlap" PASS "$(jq -r '.checks["advisory.sweepOverlap"]' <<<"$out")"
expect "window timestamps in +02:00: same offsets from node start" \
  "$(evaluate_with ts-utc '.' >/dev/null; check_field ts-utc advisory.sweepOverlap '.value.windows')" \
  "$(check_field ts-plus2 advisory.sweepOverlap '.value.windows')"
out=$(evaluate_with ts-minus1345 "$(shift_ts -49500 -13:45)")
expect "window timestamps in -13:45: verdict" PASS "$(jq -r .verdict <<<"$out")"
expect "window timestamps in -13:45: advisory.sweepOverlap" PASS "$(jq -r '.checks["advisory.sweepOverlap"]' <<<"$out")"
out=$(evaluate_with ts-garbage '.window.resetAt = "not-a-time"')
expect "unparseable window timestamp: advisory.sweepOverlap" WARN "$(jq -r '.checks["advisory.sweepOverlap"]' <<<"$out")"
expect "unparseable window timestamp: verdict unchanged" PASS "$(jq -r .verdict <<<"$out")"
# A failure inside an advisory is that advisory's WARN, never an ERROR.
out=$(evaluate_with adv-throws '.' '(.nodes, .nodesAfter) |= map(.jvmStartMillis = "x")')
expect "advisory that throws (jvmStartMillis \"x\"): advisory.sweepOverlap" WARN "$(jq -r '.checks["advisory.sweepOverlap"]' <<<"$out")"
expect "advisory that throws: verdict unchanged" PASS "$(jq -r .verdict <<<"$out")"
out=$(evaluate_with adv-latency '.latency = "garbage"')
expect "latency record not an object: advisory.latency" WARN "$(jq -r '.checks["advisory.latency"]' <<<"$out")"
expect "latency record not an object: verdict unchanged" PASS "$(jq -r .verdict <<<"$out")"
out=$(evaluate_with adv-dirty-unknown '.' '.repo.dirty = null')
expect "dirtiness not recorded: advisory.dirtyTree" WARN "$(jq -r '.checks["advisory.dirtyTree"]' <<<"$out")"
out=$(evaluate_with adv-no-nodes '.' '.nodes = [] | .nodesAfter = []')
expect "no node start recorded: advisory.sweepOverlap (cannot tell)" WARN "$(jq -r '.checks["advisory.sweepOverlap"]' <<<"$out")"
expect "sweepOverlap detail states the assumed interval" yes \
  "$(check_field pass advisory.sweepOverlap .detail | grep -q 'assumes the default 600 s lock-cleanup interval; the node does not report it' && echo yes || echo no)"

# advisory.sweepOverlap: a window straddling node start + 600 s (lock cleaner).
node_start=$(jq '[.nodes[].jvmStartMillis] | min / 1000 | floor' "${src}/identity.json")
out=$(evaluate_with sweep "$(printf '.window.resetAt = (%s + 590 | todate) | .window.snapshotAt = (%s + 610 | todate)' "$node_start" "$node_start")")
expect "window straddles node start + 600 s: advisory.sweepOverlap" WARN "$(jq -r '.checks["advisory.sweepOverlap"]' <<<"$out")"
expect "window straddles node start + 600 s: verdict unchanged" PASS "$(jq -r .verdict <<<"$out")"
out=$(evaluate_with sweep-late "$(printf '.window.resetAt = (%s + 640 | todate) | .window.snapshotAt = (%s + 700 | todate)' "$node_start" "$node_start")")
expect "window after the +600 s pass and its 30 s slack: advisory.sweepOverlap" PASS "$(jq -r '.checks["advisory.sweepOverlap"]' <<<"$out")"
expect "real run: advisory.sweepOverlap" PASS "$(jq -r '.checks["advisory.sweepOverlap"]' <<<"$(evaluate_with pass3 '.')")"

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
