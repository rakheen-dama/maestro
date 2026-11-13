#!/usr/bin/env bash
# Evaluator self-test:
#   perf/eval/selftest.sh <a PASSING smoke result dir> [<a PASSING s1-throughput result dir>]
#                         [<a PASSING s3-parked result dir>]
# With the second argument it also runs the Scenario 1 cases (ceiling and
# "sustains", the WP11 meters degrading to "not available", the statement
# gate's baseline matching, warm-up size, levels, correctness); with the
# third the Scenario 3 cases (parked verification, window discipline, plan,
# plateau and release correctness, linearity, baseline matching, advisories).
# Pass "" as the second argument to run the Scenario 3 cases alone.
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
src=${1:?usage: perf/eval/selftest.sh <passing smoke result dir> [<passing s1-throughput result dir>]}
s1src=${2:-}
s3src=${3:-}
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
# Auxiliary windows (.auxWindows[], e.g. Scenario 3's seed and release
# windows) are held to the same completeness and sweep rules as the judged ones.
out=$(evaluate_with aux-clean '.auxWindows = [.window]')
expect "auxWindows: a clean copy of the load window: verdict" PASS "$(jq -r .verdict <<<"$out")"
expect "auxWindows: listed by validity.sweeps" '"auxWindows[0]"' "$(check_field aux-clean validity.sweeps '.value.windows[-1].name')"
out=$(evaluate_with aux-janitor '.auxWindows = [.window | .sweepCalls.signalJanitor = 1]')
expect "auxWindows: signal-janitor call in an auxiliary window: validity.sweeps" INVALID "$(jq -r '.checks["validity.sweeps"]' <<<"$out")"
out=$(evaluate_with aux-dealloc '.auxWindows = [.window | .dealloc = 2]')
expect "auxWindows: evicted entries in an auxiliary window: validity.pgssComplete" INVALID "$(jq -r '.checks["validity.pgssComplete"]' <<<"$out")"
out=$(evaluate_with aux-unclassified '.auxWindows = [.window | del(.sweepCalls)]')
expect "auxWindows: auxiliary window without sweepCalls: validity.sweeps" INVALID "$(jq -r '.checks["validity.sweeps"]' <<<"$out")"
# Shared contention helper: load per CPU of well-formed host_load records only.
expect "load_per_cpu: malformed records skipped" '[0.5,2.5]' \
  "$(jq -nc -L "${PERF_DIR}/eval" 'include "common"; load_per_cpu([{load1: 5, hostCpus: 10}, null, {load1: "x", hostCpus: 2}, {load1: 5, hostCpus: 0}, {load1: 25, hostCpus: 10}])')"

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

# ── Scenario 1 (s1-throughput) ──────────────────────────────────────────────
if [[ -n "$s1src" ]]; then
  [[ "$(jq -r .scenario "${s1src}/identity.json")" == s1-throughput ]] || { echo "selftest: ${s1src} is not an s1-throughput run"; exit 1; }
  # s1_eval <case> <measurements filter> [<identity filter>] [<baseline json>]
  s1_eval() {
    local dir="${work}/$1" base=""
    mkdir -p "$dir"
    jq "${3:-.}" "${s1src}/identity.json" >"${dir}/identity.json"
    jq "$2" "${s1src}/measurements.json" >"${dir}/measurements.json"
    # The run's Prometheus range data (the hold-window overhead basis), unless
    # the case asks for it absent (S1_NO_PROM=1).
    if [[ -s "${s1src}/prometheus.json" && "${S1_NO_PROM:-0}" != 1 ]]; then
      cp "${s1src}/prometheus.json" "${dir}/prometheus.json"
    fi
    if [[ -n "${4:-}" ]]; then
      printf '%s\n' "$4" >"${dir}/baseline.json"
      base="${dir}/baseline.json"
    fi
    if ! bash "${PERF_DIR}/lib/evaluate.sh" "$dir" s1-throughput "$base" >/dev/null 2>"${dir}/stderr"; then
      printf '{"verdict":"CRASH","checks":{}}\n'
      return 0
    fi
    jq -c '{verdict, checks: (.checks | map({key: .id, value: .status}) | from_entries)}' "${dir}/result.json"
  }
  s1_field() { jq -c "$2" "${work}/$1/result.json" 2>/dev/null || echo CRASH; }
  st() { jq -r --arg id "$2" '.checks[$id] // "absent"' <<<"$1"; }

  out=$(s1_eval s1-pass '.')
  expect "s1: unchanged run" PASS "$(jq -r .verdict <<<"$out")"
  expect "s1: every check (of at least 20) is PASS or NO_BASELINE (gate) on the real run" "true []" \
    "$(jq -r '.checks | "\(length >= 20) \(to_entries | map(select(.value != "PASS" and .value != "NO_BASELINE")) | map(.key) | tojson)"' <<<"$out")"
  expect "s1: levels recorded = plan" "$(jq -c .plan.levels "${s1src}/measurements.json")" "$(s1_field s1-pass '[.measurements.levels[].level]')"
  expect "s1: ceilingPerSec is a number" number "$(s1_field s1-pass '.measurements.ceiling.ceilingPerSec | type' | tr -d '"')"

  # WP11 meters missing (the harness on an older engine): "not available"
  # advisories, never INVALID or ERROR, and the verdict does not move.
  degrade='.levelProm |= map(.slotsUsed |= map_values(null) | .durationMeter |= map_values(null)) | .runAbandonedIncrease = null'
  out=$(s1_eval s1-no-wp11 "$degrade")
  expect "s1, WP11 meters absent: verdict unchanged" PASS "$(jq -r .verdict <<<"$out")"
  expect "s1, WP11 meters absent: advisory.slotsUsed" WARN "$(st "$out" advisory.slotsUsed)"
  expect "s1, WP11 meters absent: advisory.workflowDurationMeter" WARN "$(st "$out" advisory.workflowDurationMeter)"
  expect "s1, WP11 meters absent: advisory.runAbandoned" WARN "$(st "$out" advisory.runAbandoned)"
  expect "s1, WP11 meters absent: each says \"not available\"" 3 \
    "$(s1_field s1-no-wp11 '[.checks[] | select(.id == "advisory.slotsUsed" or .id == "advisory.workflowDurationMeter" or .id == "advisory.runAbandoned") | select(.detail | startswith("not available"))] | length')"
  non_advisory='.checks | with_entries(select(.key | startswith("advisory.") | not)) | "\(length) \(tojson)"'
  expect "s1, WP11 meters absent: no validity/correctness/gate check moved" \
    "$(jq -r "$non_advisory" <<<"$(s1_eval s1-pass2 '.')")" "$(jq -r "$non_advisory" <<<"$out")"
  expect "s1, WP11 meters absent: those checks are there to compare (>= 15)" true \
    "$(jq -r '.checks | with_entries(select(.key | startswith("advisory.") | not)) | length >= 15' <<<"$out")"
  out=$(s1_eval s1-no-prom 'del(.levelProm) | .runAbandonedIncrease = null')
  expect "s1, no Prometheus data at all: verdict unchanged" PASS "$(jq -r .verdict <<<"$out")"
  expect "s1, no Prometheus data at all: advisory.hikariPool" WARN "$(st "$out" advisory.hikariPool)"
  out=$(s1_eval s1-abandon-lazy '.runAbandonedIncrease = null')
  expect "s1, abandoned counter absent on a WP11 engine (lazy): advisory.runAbandoned" PASS "$(st "$out" advisory.runAbandoned)"
  out=$(s1_eval s1-abandon-3 '.runAbandonedIncrease = 3')
  expect "s1, 3 runs abandoned: advisory.runAbandoned" WARN "$(st "$out" advisory.runAbandoned)"
  expect "s1, 3 runs abandoned: verdict unchanged" PASS "$(jq -r .verdict <<<"$out")"

  # Validity.
  out=$(s1_eval s1-dropped '(.k6[] | select(.phase == "s1") | .droppedIterations) = 12')
  expect "s1, k6 dropped 12 iterations: validity.droppedIterations" INVALID "$(st "$out" validity.droppedIterations)"
  expect "s1, k6 dropped 12 iterations: verdict" INVALID "$(jq -r .verdict <<<"$out")"
  # The plan k6 executed must be the plan the harness recorded (a profile or
  # VU override that reached `k6 run` but not `k6 inspect` once recorded the
  # local plan for a full run).
  out=$(s1_eval s1-plan-missing 'del(.executedPlan)')
  expect "s1, k6 wrote no executed plan: validity.plan" INVALID "$(st "$out" validity.plan)"
  out=$(s1_eval s1-plan-vus '.executedPlan.maxVUs = 1 | .executedPlan.preAllocatedVUs = 1')
  expect "s1, k6 ran 1/1 VUs, harness recorded the profile's: validity.plan" INVALID "$(st "$out" validity.plan)"
  out=$(s1_eval s1-plan-profile '.executedPlan.profile = "full" | .executedPlan.levels = [5, 10, 20, 40, 60, 80, 120]')
  expect "s1, k6 ran another profile's levels: validity.plan" INVALID "$(st "$out" validity.plan)"
  out=$(s1_eval s1-plan-runprofile '.' '.profile = "full"')
  expect "s1, recorded plan is not the run's --profile: validity.plan" INVALID "$(st "$out" validity.plan)"
  out=$(s1_eval s1-level-missing '.levelRows |= .[:-1]')
  expect "s1, a planned level has no rows: validity.levels" INVALID "$(st "$out" validity.levels)"
  out=$(s1_eval s1-unlabeled '.unlabeledRows = 1')
  expect "s1, an unlabeled s1 row: validity.levels" INVALID "$(st "$out" validity.levels)"
  out=$(s1_eval s1-wu-small '.warmup.vus = 50')
  expect "s1, warm-up VUs below the plan's maxVUs: validity.warmupSize" INVALID "$(st "$out" validity.warmupSize)"
  out=$(s1_eval s1-wu-peak '(.k6[] | select(.phase == "s1") | .vusMax) = 100000')
  expect "s1, load peaked above the warm-up's VUs: validity.warmupSize" INVALID "$(st "$out" validity.warmupSize)"
  out=$(s1_eval s1-wu-nogauge '(.k6[] | select(.phase == "warmup") | .vusMax) = null')
  expect "s1, k6 sampled no vus_max in the warm-up (configured VUs count): validity.warmupSize" PASS "$(st "$out" validity.warmupSize)"
  out=$(s1_eval s1-load-nogauge '(.k6[] | select(.phase == "s1") | .vusMax) = null')
  expect "s1, k6 sampled no vus_max in the load (plan maxVUs bounds it): validity.warmupSize" PASS "$(st "$out" validity.warmupSize)"
  out=$(s1_eval s1-load-nogauge-small '(.k6[] | select(.phase == "s1") | .vusMax) = null | .warmup.vus = (.plan.maxVUs - 1)')
  expect "s1, no load vus_max and warm-up below the plan's maxVUs: validity.warmupSize" INVALID "$(st "$out" validity.warmupSize)"
  out=$(s1_eval s1-wu-count '.warmup.count = 39')
  expect "s1, warm-up below 2 x the workflow slots: validity.warmupSize" INVALID "$(st "$out" validity.warmupSize)"

  # Correctness.
  out=$(s1_eval s1-failed '.runStatus.byStatus.COMPLETED -= 1 | .runStatus.byStatus.FAILED = 1')
  expect "s1, one FAILED run: correctness.allCompleted" FAIL "$(st "$out" correctness.allCompleted)"
  expect "s1, one FAILED run: verdict" FAIL "$(jq -r .verdict <<<"$out")"
  out=$(s1_eval s1-residue '.drain.timedOut = true | .runStatus.byStatus.COMPLETED -= 5 | .runStatus.byStatus.RUNNING = 5')
  expect "s1, drain timed out with 5 RUNNING: correctness.allCompleted" FAIL "$(st "$out" correctness.allCompleted)"
  expect "s1, drain timed out: detail names the residue" yes \
    "$(s1_field s1-residue '.checks[] | select(.id == "correctness.allCompleted") | .detail' | grep -q 'drain timed out.*RUNNING' && echo yes || echo no)"
  out=$(s1_eval s1-accepted '.accepted += 1')
  expect "s1, one accepted start has no row: correctness.allCompleted" FAIL "$(st "$out" correctness.allCompleted)"

  # The statement gate: baseline matched on shape, profile and replicas.
  net=$(s1_field s1-pass '.measurements.statements.statementsPerWorkflowNet')
  prof=$(jq -r .profile "${s1src}/identity.json")
  bl() { printf '{"schema":1,"scenario":"s1-throughput","profile":"%s","replicas":%s,"workflowShape":{"type":"chain","activities":%s,"activityMillis":50},"statementsPerWorkflowNet":%s}' "$1" "$2" "$3" "$4"; }
  out=$(s1_eval s1-gate-match '.' '.' "$(bl "$prof" 1 5 "$net")")
  expect "s1 gate: baseline = this run's net: PASS" PASS "$(st "$out" gate.statementsPerWorkflow)"
  expect "s1 gate: the allowance in force is s1_relative_allowance" \
    "$(sed -n 's/^def s1_relative_allowance: \(.*\);$/\1/p' "${PERF_DIR}/eval/s1-throughput.jq")" \
    "$(s1_field s1-gate-match '.checks[] | select(.id == "gate.statementsPerWorkflow") | .limit.relativeAllowance')"
  out=$(s1_eval s1-gate-high '.' '.' "$(bl "$prof" 1 5 "$(jq -n --argjson n "$net" '$n / 1.5')")")
  expect "s1 gate: net 1.5 x the baseline: FAIL" FAIL "$(st "$out" gate.statementsPerWorkflow)"
  out=$(s1_eval s1-gate-low '.' '.' "$(bl "$prof" 1 5 "$(jq -n --argjson n "$net" '$n * 1.5')")")
  expect "s1 gate: net 2/3 of the baseline (symmetric): FAIL" FAIL "$(st "$out" gate.statementsPerWorkflow)"
  # With the allowance in force (0.02, tolerance about 0 at thousands of
  # workflows) one added statement per workflow must FAIL and 1.9 % must not.
  out=$(s1_eval s1-gate-plus1 '.' '.' "$(bl "$prof" 1 5 "$(jq -n --argjson n "$net" '$n - 1')")")
  expect "s1 gate: one statement per workflow above the baseline: FAIL" FAIL "$(st "$out" gate.statementsPerWorkflow)"
  out=$(s1_eval s1-gate-minus1 '.' '.' "$(bl "$prof" 1 5 "$(jq -n --argjson n "$net" '$n + 1')")")
  expect "s1 gate: one statement per workflow below the baseline: FAIL" FAIL "$(st "$out" gate.statementsPerWorkflow)"
  out=$(s1_eval s1-gate-1p9 '.' '.' "$(bl "$prof" 1 5 "$(jq -n --argjson n "$net" '$n / 1.019')")")
  expect "s1 gate: 1.9 % above the baseline: PASS" PASS "$(st "$out" gate.statementsPerWorkflow)"
  out=$(s1_eval s1-gate-shape '.' '.' "$(bl "$prof" 1 2 "$net")")
  expect "s1 gate: baseline of another workflow shape: NO_BASELINE" NO_BASELINE "$(st "$out" gate.statementsPerWorkflow)"
  out=$(s1_eval s1-gate-profile '.' '.' "$(bl other 1 5 "$net")")
  expect "s1 gate: baseline of another profile: NO_BASELINE" NO_BASELINE "$(st "$out" gate.statementsPerWorkflow)"
  out=$(s1_eval s1-gate-replicas '.' '.' "$(bl "$prof" 2 5 "$net")")
  expect "s1 gate: baseline of another replica count: NO_BASELINE" NO_BASELINE "$(st "$out" gate.statementsPerWorkflow)"

  # Ceiling and "sustains" (doctored level rows; the ceiling never moves the verdict).
  all_ok='.levelRows |= map(.completionsInWindowPerSec = .arrivalPerSec | .backlogAtEnd = 0)'
  out=$(s1_eval s1-all-sustained "$all_ok")
  expect "s1 ceiling: every level sustained: sustainedLevel = top level" "$(jq -c '.plan.levels[-1]' "${s1src}/measurements.json")" \
    "$(s1_field s1-all-sustained '.measurements.ceiling.sustainedLevel')"
  expect "s1 ceiling: every level sustained: ceilingReached" false "$(s1_field s1-all-sustained '.measurements.ceiling.ceilingReached')"
  gap="${all_ok} | .levelRows[1].completionsInWindowPerSec = (.levelRows[1].arrivalPerSec * 0.94)"
  out=$(s1_eval s1-gap "$gap")
  expect "s1 ceiling: level 2 below 0.95 x arrivals: knee is level 1, the rest saturated" \
    "$(jq -c '[.plan.levels[0], .plan.levels[1:]]' "${s1src}/measurements.json")" \
    "$(s1_field s1-gap '[.measurements.ceiling.sustainedLevel, .measurements.ceiling.saturatedLevels]')"
  expect "s1 ceiling: a gap does not move the verdict" PASS "$(jq -r .verdict <<<"$out")"
  edge="${all_ok} | .levelRows[1].completionsInWindowPerSec = (.levelRows[1].arrivalPerSec * 0.95)"
  expect "s1 ceiling: exactly 0.95 x arrivals sustains" "$(jq -c '.plan.levels[-1]' "${s1src}/measurements.json")" \
    "$(s1_eval s1-edge "$edge" >/dev/null; s1_field s1-edge '.measurements.ceiling.sustainedLevel')"
  below="${all_ok} | .levelRows[1].completionsInWindowPerSec = (.levelRows[1].arrivalPerSec * 0.9499)"
  expect "s1 ceiling: 0.9499 x arrivals does not sustain" "$(jq -c '.plan.levels[0]' "${s1src}/measurements.json")" \
    "$(s1_eval s1-below "$below" >/dev/null; s1_field s1-below '.measurements.ceiling.sustainedLevel')"
  bedge="${all_ok} | .levelRows[0].backlogAtEnd = (20 * $(jq .config.replicas "${s1src}/identity.json") + .levelRows[0].arrivalPerSec | floor)"
  expect "s1 ceiling: backlog at the bound (slots + 1 s of arrivals) sustains" "$(jq -c '.plan.levels[-1]' "${s1src}/measurements.json")" \
    "$(s1_eval s1-bedge "$bedge" >/dev/null; s1_field s1-bedge '.measurements.ceiling.sustainedLevel')"
  backlog="${all_ok} | .levelRows[0].backlogAtEnd = 1000"
  out=$(s1_eval s1-backlog "$backlog")
  expect "s1 ceiling: first level's backlog unbounded: no level sustained" null "$(s1_field s1-backlog '.measurements.ceiling.sustainedLevel')"
  expect "s1 ceiling: no level sustained: advisory.ceiling still reports (PASS)" PASS "$(st "$out" advisory.ceiling)"
  # Fix round 1: contention, slot-hold time, Little's law, clipped duration
  # quantiles, per-level start timing, the plan's iteration count, hold windows.
  expect "s1: ceiling records the hold-only maximum" number "$(s1_field s1-pass '.measurements.ceiling.ceilingHoldPerSec | type' | tr -d '"')"
  expect "s1: ceiling records engineOverheadMsPerWorkflow" number "$(s1_field s1-pass '.measurements.ceiling.engineOverheadMsPerWorkflow | type' | tr -d '"')"
  expect "s1: levels record slot-hold time and node CPU" "true" \
    "$(s1_field s1-pass '[.measurements.levels[] | (.slotHoldMs | type == "number") and (.nodeCpu.processAvg | type == "number")] | all')"
  expect "s1: the real (quiet) run: advisory.contention" PASS "$(jq -r '.checks["advisory.contention"]' <<<"$(s1_eval s1-quiet '.')")"
  expect "s1: quiet run: ceiling detail carries no contended-host note" no \
    "$(s1_field s1-quiet '.checks[] | select(.id == "advisory.ceiling") | .detail' | grep -q 'contended host' && echo yes || echo no)"
  ceil_idx=$(s1_field s1-pass '[.measurements.levels[] | .level] | index('"$(s1_field s1-pass '.measurements.ceiling.ceilingLevel')"')')
  # (an evaluator without ceilingLevel: fall back to the top level, so every
  # case below still runs and reports instead of the script stopping here)
  [[ "$ceil_idx" =~ ^[0-9]+$ ]] || ceil_idx=$(($(jq '.plan.levels | length' "${s1src}/measurements.json") - 1))
  below_idx=$((ceil_idx - 1))
  hot=".levelProm[${below_idx}].slotsUsed.workflowAvg = (.levelRows[${below_idx}].completionsInWindowPerSec * .levelRows[0].p50Ms * 1.2 / 1000)"
  out=$(s1_eval s1-contended "$hot")
  expect "s1: hold time 1.2 x the reference below the ceiling level: advisory.contention" WARN "$(st "$out" advisory.contention)"
  expect "s1: contended: verdict unchanged" PASS "$(jq -r .verdict <<<"$out")"
  expect "s1: contended: ceiling detail says so" yes \
    "$(s1_field s1-contended '.checks[] | select(.id == "advisory.ceiling") | .detail' | grep -q 'measured on a contended host' && echo yes || echo no)"
  expect "s1: contended: Little's-law detail says so" yes \
    "$(s1_field s1-contended '.checks[] | select(.id == "advisory.littlesLaw") | .detail' | grep -q 'measured on a contended host' && echo yes || echo no)"
  warm=".levelProm[${below_idx}].slotsUsed.workflowAvg = (.levelRows[${below_idx}].completionsInWindowPerSec * .levelRows[0].p50Ms * 1.14 / 1000)"
  expect "s1: hold time 1.14 x the reference: advisory.contention" PASS "$(jq -r '.checks["advisory.contention"]' <<<"$(s1_eval s1-warm "$warm")")"
  out=$(s1_eval s1-cpu-hot ".levelProm[0].nodeCpu.processAvg = 0.95")
  expect "s1: node process CPU averaging 0.95 at the first level: advisory.contention" WARN "$(st "$out" advisory.contention)"
  out=$(s1_eval s1-syscpu-hot ".levelProm[${below_idx}].nodeCpu.systemAvg = 0.9")
  expect "s1: node system CPU averaging 0.90 below the ceiling: advisory.contention" WARN "$(st "$out" advisory.contention)"
  out=$(s1_eval s1-above-ceiling ".levelRows[-1].completionsInWindowPerSec = 1 | .levelProm[-1].slotsUsed.workflowAvg = 1000")
  expect "s1: hold time only ABOVE the ceiling level is not judged" PASS "$(st "$out" advisory.contention)"
  out=$(s1_eval s1-no-series '.levelProm |= map(.slotsUsed |= map_values(null) | .nodeCpu = null) | del(.containerStats)')
  expect "s1: slot gauge, node CPU and container CPU absent: advisory.contention" WARN "$(st "$out" advisory.contention)"
  expect "s1: series absent: contention says \"not available\"" yes \
    "$(s1_field s1-no-series '.checks[] | select(.id == "advisory.contention") | .detail' | grep -q '^"not available' && echo yes || echo no)"
  expect "s1: series absent: no contended-host note on the ceiling" no \
    "$(s1_field s1-no-series '.checks[] | select(.id == "advisory.ceiling") | .detail' | grep -q 'contended host' && echo yes || echo no)"
  expect "s1: series absent: verdict unchanged" PASS "$(jq -r .verdict <<<"$out")"
  out=$(s1_eval s1-littles-low '.levelRows[0].p50Ms = 100')
  expect "s1: ceiling below 0.9 x the unloaded model cap: advisory.littlesLaw" WARN "$(st "$out" advisory.littlesLaw)"
  expect "s1: Little's-law WARN: verdict unchanged" PASS "$(jq -r .verdict <<<"$out")"
  out=$(s1_eval s1-clipped '.durationMeterTopBucketSecs = 30 | .levelProm[-1].durationMeter = {p50Secs: 30, p95Secs: 30}')
  expect "s1: duration quantile at the top finite bucket is reported as clipped" yes \
    "$(s1_field s1-clipped '.checks[] | select(.id == "advisory.workflowDurationMeter") | .detail' | grep -q '>= 30000 ms (clipped).*row-derived start-to-terminal.*authoritative' && echo yes || echo no)"
  expect "s1: clipped level listed" "[$(jq '.plan.levels[-1]' "${s1src}/measurements.json")]" \
    "$(s1_field s1-clipped '.checks[] | select(.id == "advisory.workflowDurationMeter") | .value.clippedLevels')"
  expect "s1: the real run records the duration timer's top finite bucket" true \
    "$(s1_field s1-pass '.checks[] | select(.id == "advisory.workflowDurationMeter") | .value | (.topFiniteBucketSecs | type == "number")')"
  expect "s1: start latency reports per-level max and starts over 1 s" yes \
    "$(s1_field s1-pass '.checks[] | select(.id == "advisory.startLatency") | .detail' | grep -q 'per level max ms / starts over 1 s: L' && echo yes || echo no)"
  out=$(s1_eval s1-iterations '(.k6[] | select(.phase == "s1") | .iterations) -= 100')
  expect "s1: k6 started 100 fewer iterations than the plan implies: validity.plan" INVALID "$(st "$out" validity.plan)"
  out=$(s1_eval s1-iterations-1 '(.k6[] | select(.phase == "s1") | .iterations) -= 1')
  expect "s1: one iteration short (rounding): validity.plan" PASS "$(st "$out" validity.plan)"
  # Fix round 2: uniform contention (an absolute check on the reference, the
  # host load), the ceiling-level shift, the overhead's robust basis and
  # resolution.
  contention_fired() { s1_field "$1" '.checks[] | select(.id == "advisory.contention") | .value.fired'; }
  out=$(s1_eval s1-uniform-slow '.levelRows[0].p50Ms = 300')
  expect "s1: first level's p50 300 ms (> activity time + 40 ms): advisory.contention" WARN "$(st "$out" advisory.contention)"
  expect "s1: uniformly slow: the reference part fired" '["reference"]' "$(contention_fired s1-uniform-slow)"
  expect "s1: uniformly slow: detail names both readings" yes \
    "$(s1_field s1-uniform-slow '.checks[] | select(.id == "advisory.contention") | .detail' | grep -q 'a contended host or a slower engine' && echo yes || echo no)"
  expect "s1: first level's p50 289 ms: no reference part" '[]' "$(s1_eval s1-ref-edge '.levelRows[0].p50Ms = 289' >/dev/null; contention_fired s1-ref-edge)"
  out=$(s1_eval s1-hostload '.hostLoad.scenarioStart.load1 = 25')
  expect "s1: host load 2.5 per CPU at the start: advisory.contention" WARN "$(st "$out" advisory.contention)"
  expect "s1: host load: the hostLoad part fired" '["hostLoad"]' "$(contention_fired s1-hostload)"
  out=$(s1_eval s1-hostload-edge '.hostLoad.scenarioStart.load1 = 20 | .hostLoad.afterDrain.load1 = 20')
  expect "s1: host load exactly 2.0 per CPU: advisory.contention" PASS "$(st "$out" advisory.contention)"
  out=$(s1_eval s1-hostload-none 'del(.hostLoad)')
  expect "s1: host load not recorded: advisory.contention (no WARN from that part)" PASS "$(st "$out" advisory.contention)"
  expect "s1: host load not recorded: says so" yes \
    "$(s1_field s1-hostload-none '.checks[] | select(.id == "advisory.contention") | .detail' | grep -q 'host load not recorded' && echo yes || echo no)"
  shift_ceiling='.levelRows[-1].completionsInWindowPerSec = 15 | .levelRows[2].completionsInWindowPerSec = 10'
  out=$(s1_eval s1-shift "$shift_ceiling")
  expect "s1: ceiling level dragged down to L$(jq '.plan.levels[1]' "${s1src}/measurements.json"); L$(jq '.plan.levels[2]' "${s1src}/measurements.json") (below the cap, above it) slow: advisory.contention" WARN "$(st "$out" advisory.contention)"
  expect "s1: ceiling shift: the hurt level is judged" "[$(jq '.plan.levels[2]' "${s1src}/measurements.json")]" \
    "$(s1_field s1-shift '.checks[] | select(.id == "advisory.contention") | .value.holdHot')"
  expect "s1: overhead on the hold-window basis, with its resolution" "true" \
    "$(s1_field s1-pass '.measurements.ceiling | (.engineOverheadMsPerWorkflow | type == "number") and (.engineOverheadResolutionMs | type == "number") and (.engineOverheadBasis | startswith("hold window"))')"
  expect "s1: Little's-law detail prints the overhead with +/- and the between-run caveat" yes \
    "$(s1_field s1-pass '.checks[] | select(.id == "advisory.littlesLaw") | .detail' | grep -q 'engine overhead is [-0-9.]* ms +/- [0-9.]* ms per workflow.*differences under about 10 ms are not meaningful' && echo yes || echo no)"
  expect "s1: Little's-law detail: the ceiling is bounded above, not below" yes \
    "$(s1_field s1-pass '.checks[] | select(.id == "advisory.littlesLaw") | .detail' | grep -q 'bounded above by 20 / 250 ms = 80/s and lowered by engine overhead' && echo yes || echo no)"
  out=$(S1_NO_PROM=1 s1_eval s1-no-prom-json '.')
  expect "s1: without prometheus.json: coarse whole-window basis, labelled, no resolution" "true" \
    "$(s1_field s1-no-prom-json '.measurements.ceiling | (.engineOverheadBasis | startswith("whole level window")) and .engineOverheadResolutionMs == null')"
  expect "s1: without prometheus.json: detail says resolution not stated" yes \
    "$(s1_field s1-no-prom-json '.checks[] | select(.id == "advisory.littlesLaw") | .detail' | grep -q 'resolution not stated' && echo yes || echo no)"
  expect "s1: without prometheus.json: verdict unchanged" PASS "$(jq -r .verdict <<<"$out")"
  out=$(s1_eval s1-no-samples '.samples = []')
  expect "s1, no statement samples: advisory.statementsPerLevel" WARN "$(st "$out" advisory.statementsPerLevel)"
  expect "s1, no statement samples: verdict unchanged" PASS "$(jq -r .verdict <<<"$out")"
fi

# ── Scenario 3 (s3-parked) ──────────────────────────────────────────────────
if [[ -n "$s3src" ]]; then
  [[ "$(jq -r .scenario "${s3src}/identity.json")" == s3-parked ]] || { echo "selftest: ${s3src} is not an s3-parked run"; exit 1; }
  # s3_eval <case> <measurements filter> [<identity filter>] [<baseline json>]
  s3_eval() {
    local dir="${work}/$1" base=""
    mkdir -p "$dir"
    jq "${3:-.}" "${s3src}/identity.json" >"${dir}/identity.json"
    jq "$2" "${s3src}/measurements.json" >"${dir}/measurements.json"
    [[ ! -s "${s3src}/prometheus.json" ]] || cp "${s3src}/prometheus.json" "${dir}/prometheus.json"
    if [[ -n "${4:-}" ]]; then
      printf '%s\n' "$4" >"${dir}/baseline.json"
      base="${dir}/baseline.json"
    fi
    if ! bash "${PERF_DIR}/lib/evaluate.sh" "$dir" s3-parked "$base" >/dev/null 2>"${dir}/stderr"; then
      printf '{"verdict":"CRASH","checks":{}}\n'
      return 0
    fi
    jq -c '{verdict, checks: (.checks | map({key: .id, value: .status}) | from_entries)}' "${dir}/result.json"
  }
  s3_field() { jq -c "$2" "${work}/$1/result.json" 2>/dev/null || echo CRASH; }
  s3st() { jq -r --arg id "$2" '.checks[$id] // "absent"' <<<"$1"; }
  # The plateau windows' indices in .windows and the largest plateau.
  pmax=$(jq '[.windows[] | select(.kind == "plateau") | .P] | max' "${s3src}/measurements.json")
  pmin=$(jq '[.windows[] | select(.kind == "plateau") | .P] | min' "${s3src}/measurements.json")
  last_plateau='(.windows | map(.kind == "plateau") | rindex(true))'
  first_plateau='(.windows | map(.kind == "plateau") | index(true))'

  out=$(s3_eval s3-pass '.')
  expect "s3: unchanged run" PASS "$(jq -r .verdict <<<"$out")"
  expect "s3: windows' P (idle first, then the plateaus)" "$(jq -c '[0] + .plan.plateaus' "${s3src}/measurements.json")" "$(s3_field s3-pass '[.measurements.windows[].P]')"
  expect "s3: every plateau's perParkedCallsPer30s > 0" true "$(s3_field s3-pass '[.measurements.windows[] | select(.P > 0) | .perParkedCallsPer30s > 0] | all')"
  expect "s3: raw window fields kept next to the figures (resetAt, sweepCalls)" true \
    "$(s3_field s3-pass '[.measurements.windows[] | (.resetAt | type == "string") and (.sweepCalls | type == "object") and (.cps | type == "number")] | all')"
  expect "s3: baseline gate without a baseline" NO_BASELINE "$(s3st "$out" gate.parkedCostBaseline)"

  # validity.parked
  out=$(s3_eval s3-parked-running "${last_plateau} as \$i | .windows[\$i].parkedBefore.park.byStatus = {WAITING_SIGNAL: (.windows[\$i].P - 1), RUNNING: 1}")
  expect "s3: one run still RUNNING when the window opened: validity.parked" INVALID "$(s3st "$out" validity.parked)"
  expect "s3: one run still RUNNING when the window opened: verdict" INVALID "$(jq -r .verdict <<<"$out")"
  out=$(s3_eval s3-parked-after "${first_plateau} as \$i | .windows[\$i].parkedAfter.park.total = (.windows[\$i].P + 1)")
  expect "s3: an extra park row when the window closed: validity.parked" INVALID "$(s3st "$out" validity.parked)"
  out=$(s3_eval s3-parked-other "${first_plateau} as \$i | .windows[\$i].parkedBefore.others.open = 1")
  expect "s3: a probe row still open when the window opened: validity.parked" INVALID "$(s3st "$out" validity.parked)"
  out=$(s3_eval s3-parked-missing "${first_plateau} as \$i | .windows[\$i] |= del(.parkedBefore)")
  expect "s3: no parked snapshot before the window: validity.parked" INVALID "$(s3st "$out" validity.parked)"

  # validity.windows
  out=$(s3_eval s3-win-short "${last_plateau} as \$i | .windows[\$i].windowSecs = (.plan.holdSecs - 10)")
  expect "s3: a plateau window 10 s short of its hold: validity.windows" INVALID "$(s3st "$out" validity.windows)"
  out=$(s3_eval s3-win-long "${last_plateau} as \$i | .windows[\$i].windowSecs = (.plan.holdSecs + 6)")
  expect "s3: a plateau window 6 s over its hold: validity.windows" INVALID "$(s3st "$out" validity.windows)"
  out=$(s3_eval s3-win-unit '.plan.windowUnitSecs = 45')
  expect "s3: a window unit (45 s) that is not a multiple of the node's intervals: validity.windows" INVALID "$(s3st "$out" validity.windows)"
  out=$(s3_eval s3-win-node '.' '.nodes[0].recoveryPollInterval = "PT50S"')
  expect "s3: node reports a 50 s recovery poll (60 s unit no longer whole): validity.windows" INVALID "$(s3st "$out" validity.windows)"
  out=$(s3_eval s3-win-release '.auxWindows |= map(if .kind == "release" then .windowSecs = (.windowSecs - 1) else . end)')
  expect "s3: release window 1 s short of a whole unit: validity.windows" INVALID "$(s3st "$out" validity.windows)"
  out=$(s3_eval s3-win-idle-first '.windows |= (.[1:] + .[:1])')
  expect "s3: windows[0] is not the idle window: validity.windows" INVALID "$(s3st "$out" validity.windows)"

  # validity.plan
  out=$(s3_eval s3-plan-probe '.probes[0].executedPlan.rate = 3')
  expect "s3: a probe ran at another rate: validity.plan" INVALID "$(s3st "$out" validity.plan)"
  out=$(s3_eval s3-plan-probe-it '.probes[0].phase as $ph | .k6 |= map(if .phase == $ph then .iterations = (.iterations - 5) else . end)')
  expect "s3: a probe started 5 iterations too few: validity.plan" INVALID "$(s3st "$out" validity.plan)"
  out=$(s3_eval s3-plan-seed '.plateaus[0].seed.executedPlan.durationSecs = 1')
  expect "s3: a seed not paced over the recheck interval: validity.plan" INVALID "$(s3st "$out" validity.plan)"
  out=$(s3_eval s3-plan-seed-starts '"seed-p\(.plateaus[0].P)" as $ph | .k6 |= map(if .phase == $ph then .checksPassed = (.checksPassed - 1) else . end)')
  expect "s3: a seed with one start not accepted: validity.plan" INVALID "$(s3st "$out" validity.plan)"
  out=$(s3_eval s3-plan-cross '.release.executedPlan.crossNode = true')
  expect "s3: release recorded crossNode at 1 replica: validity.plan" INVALID "$(s3st "$out" validity.plan)"
  out=$(s3_eval s3-plan-profile '.plan.profile = "full"')
  expect "s3: plan recorded for another profile: validity.plan" INVALID "$(s3st "$out" validity.plan)"

  # correctness
  out=$(s3_eval s3-not-reached '.plateaus[-1].reached = false | .plateaus[-1].statusAtTimeout = {park: {total: 5, byStatus: {RUNNING: 5}}} | .stopped = "plateau not reached within 600 s" | .windows |= .[:-1] | .release = null | .auxWindows |= map(select(.kind != "release"))')
  expect "s3: last plateau not reached (scenario stopped): correctness.plateauReached" FAIL "$(s3st "$out" correctness.plateauReached)"
  expect "s3: last plateau not reached: verdict FAIL (not hidden by an unjudgeable gate)" FAIL "$(jq -r .verdict <<<"$out")"
  expect "s3: last plateau not reached: correctness.releaseWakes" FAIL "$(s3st "$out" correctness.releaseWakes)"
  out=$(s3_eval s3-rel-one-lost '.release.status.released -= 1 | .release.status.results = {released: (.release.count - 1), timeout: 1}')
  expect "s3: one parked run ended otherwise (timeout): correctness.releaseWakes" FAIL "$(s3st "$out" correctness.releaseWakes)"
  expect "s3: one parked run ended otherwise: verdict" FAIL "$(jq -r .verdict <<<"$out")"
  out=$(s3_eval s3-rel-dup '.release.status.signals.workflowsNotExactlyOneRow = 1 | .release.status.signals.unconsumed = 1')
  expect "s3: a duplicated release signal: correctness.releaseWakes" FAIL "$(s3st "$out" correctness.releaseWakes)"
  out=$(s3_eval s3-rel-consumed '.release.status.signals.workflowsNotExactlyOneConsumed = 1')
  expect "s3: a run without exactly one consumed signal: correctness.releaseWakes" FAIL "$(s3st "$out" correctness.releaseWakes)"
  out=$(s3_eval s3-rel-timeout '.release.drain.timedOut = true')
  expect "s3: release drain timed out: correctness.releaseWakes" FAIL "$(s3st "$out" correctness.releaseWakes)"
  out=$(s3_eval s3-rel-missing '.release = null')
  expect "s3: no release in signal mode: correctness.releaseWakes" FAIL "$(s3st "$out" correctness.releaseWakes)"
  out=$(s3_eval s3-rel-signal-lost '.k6 |= map(if .phase == "release" then .checksPassed = (.checksPassed - 1) | .checksFailed = 1 else . end)')
  expect "s3: one release signal not accepted: correctness.releaseWakes" FAIL "$(s3st "$out" correctness.releaseWakes)"
  out=$(s3_eval s3-sleep-mode '.parkMode = "sleep" | .release = null')
  expect "s3: sleep mode (no signal release): correctness.releaseWakes not applicable" NO_BASELINE "$(s3st "$out" correctness.releaseWakes)"
  out=$(s3_eval s3-failed '.runStatus.byStatus.FAILED = 1')
  expect "s3: one FAILED row in the run: correctness.noFailures" FAIL "$(s3st "$out" correctness.noFailures)"
  out=$(s3_eval s3-terminated '.runStatus.byStatus.TERMINATED = 1')
  expect "s3: one TERMINATED row in the run: correctness.noFailures" FAIL "$(s3st "$out" correctness.noFailures)"
  out=$(s3_eval s3-probe-open '.probes[-1].status.byStatus = {COMPLETED: (.probes[-1].accepted - 1), RUNNING: 1} | .probes[-1].drain.timedOut = true')
  expect "s3: a probe never completed: correctness.probesCompleted" FAIL "$(s3st "$out" correctness.probesCompleted)"
  out=$(s3_eval s3-foreign '.foreignRows = 3')
  expect "s3: rows outside the run's id space: correctness.noForeignRows" FAIL "$(s3st "$out" correctness.noForeignRows)"

  # gate.parkedCostLinear: rewrite the largest plateau's calls so its
  # per-parked figure is <ratio> x the smallest's.
  linear_at() { # <ratio>
    printf '(.background.window | (.maestroCalls - .sweepCalls.lockCleaner) / .windowSecs) as $c0
      | %s as $i | %s as $j
      | ((.windows[$i] | (.maestroCalls - .sweepCalls.lockCleaner) / .windowSecs) - $c0) / .windows[$i].P as $k
      | .windows[$j] |= (.maestroCalls = (($c0 + %s * $k * .P) * .windowSecs + .sweepCalls.lockCleaner | round)
                         | .maestroCallsTopLevel = .maestroCalls)' "$first_plateau" "$last_plateau" "$1"
  }
  out=$(s3_eval s3-lin-130 "$(linear_at 1.30)")
  expect "s3: per-parked cost 1.30 x at P=${pmax} vs P=${pmin}: gate.parkedCostLinear" FAIL "$(s3st "$out" gate.parkedCostLinear)"
  expect "s3: superlinear: verdict" FAIL "$(jq -r .verdict <<<"$out")"
  out=$(s3_eval s3-lin-124 "$(linear_at 1.24)")
  expect "s3: per-parked cost 1.24 x: gate.parkedCostLinear" PASS "$(s3st "$out" gate.parkedCostLinear)"
  out=$(s3_eval s3-lin-070 "$(linear_at 0.70)")
  expect "s3: per-parked cost 0.70 x (re-checks falling behind): gate.parkedCostLinear" FAIL "$(s3st "$out" gate.parkedCostLinear)"
  out=$(s3_eval s3-lin-076 "$(linear_at 0.76)")
  expect "s3: per-parked cost 0.76 x: gate.parkedCostLinear" PASS "$(s3st "$out" gate.parkedCostLinear)"
  out=$(s3_eval s3-lin-one "${last_plateau} as \$i | .windows |= del(.[\$i])")
  expect "s3: one plateau window only: gate.parkedCostLinear (cannot be judged)" INVALID "$(s3st "$out" gate.parkedCostLinear)"

  # gate.parkedCostBaseline (statements_gate, symmetric)
  net=$(s3_field s3-pass '.measurements.baselineCandidate.perParkedCallsPerMin')
  mk_base() { # <perParkedCallsPerMin> [<jq edit>]
    jq -c --argjson v "$1" "{schema: 1, scenario: \"s3-parked\", profile: .profile, replicas: .identity.config.replicas}
      + .measurements.baselineCandidate + {perParkedCallsPerMin: \$v} | ${2:-.}" "${work}/s3-pass/result.json"
  }
  out=$(s3_eval s3-base-same '.' '.' "$(mk_base "$net")")
  expect "s3: baseline equal to this run: gate.parkedCostBaseline" PASS "$(s3st "$out" gate.parkedCostBaseline)"
  out=$(s3_eval s3-base-up '.' '.' "$(mk_base "$(jq -n --argjson v "$net" '$v / 1.05')")")
  expect "s3: 5 % more statements per parked run than the baseline: gate.parkedCostBaseline" FAIL "$(s3st "$out" gate.parkedCostBaseline)"
  out=$(s3_eval s3-base-down '.' '.' "$(mk_base "$(jq -n --argjson v "$net" '$v * 1.05')")")
  expect "s3: 5 % fewer than the baseline (symmetric): gate.parkedCostBaseline" FAIL "$(s3st "$out" gate.parkedCostBaseline)"
  out=$(s3_eval s3-base-mode '.' '.' "$(mk_base "$net" '.parkMode = "sleep"')")
  expect "s3: baseline of the other park mode: gate.parkedCostBaseline" NO_BASELINE "$(s3st "$out" gate.parkedCostBaseline)"
  out=$(s3_eval s3-base-recheck '.' '.' "$(mk_base "$net" '.wakeRecheckInterval = "PT10S"')")
  expect "s3: baseline at another wake-recheck interval: gate.parkedCostBaseline" NO_BASELINE "$(s3st "$out" gate.parkedCostBaseline)"
  out=$(s3_eval s3-base-replicas '.' '.' "$(mk_base "$net" '.replicas = 2')")
  expect "s3: baseline at 2 replicas: gate.parkedCostBaseline" NO_BASELINE "$(s3st "$out" gate.parkedCostBaseline)"
  out=$(s3_eval s3-base-bad '.' '.' "$(mk_base '"6"')")
  expect "s3: malformed baseline value: gate.parkedCostBaseline" ERROR "$(s3st "$out" gate.parkedCostBaseline)"

  # validity.roleSplit, validity.background, common windows
  out=$(s3_eval s3-noharness '.windows[1].harnessCalls = 0')
  expect "s3: a window without harness calls: validity.roleSplit" INVALID "$(s3st "$out" validity.roleSplit)"
  out=$(s3_eval s3-nobg 'del(.background)')
  expect "s3: no idle window: validity.background (no crash)" INVALID "$(s3st "$out" validity.background)"
  out=$(s3_eval s3-aux-janitor '.auxWindows[0].sweepCalls.signalJanitor = 1')
  expect "s3: signal-janitor call in a seed window: validity.sweeps" INVALID "$(s3st "$out" validity.sweeps)"
  out=$(s3_eval s3-plateau-dealloc "${last_plateau} as \$i | .windows[\$i].dealloc = 1")
  expect "s3: evicted entries in a plateau window: validity.pgssComplete" INVALID "$(s3st "$out" validity.pgssComplete)"

  # advisories: findings and degraded meters WARN, never change the verdict
  out=$(s3_eval s3-dec-finding "${last_plateau} as \$i | .windows[\$i].components.instanceRead.calls |= (. / 2 | floor)")
  expect "s3: half the instance reads (a re-check component off the documented 3): advisory.decomposition" WARN "$(s3st "$out" advisory.decomposition)"
  expect "s3: decomposition finding: verdict unchanged" PASS "$(jq -r .verdict <<<"$out")"
  out=$(s3_eval s3-dec-held "${last_plateau} as \$i | .windows[\$i].components.heldKeys = {calls: 20, entries: 1}")
  expect "s3: heldKeys reads at 1 replica: advisory.decomposition" WARN "$(s3st "$out" advisory.decomposition)"
  out=$(s3_eval s3-dec-renew "${last_plateau} as \$i | .windows[\$i].components.lockRenewAll.calls *= 500")
  expect "s3: one renewal statement per lock (pre-renewAll behaviour): advisory.decomposition" WARN "$(s3st "$out" advisory.decomposition)"
  out=$(s3_eval s3-dec-old-engine '.windows |= map(del(.components))')
  expect "s3: no component attribution (older harness record): advisory.decomposition" WARN "$(s3st "$out" advisory.decomposition)"
  expect "s3: no component attribution: verdict unchanged" PASS "$(jq -r .verdict <<<"$out")"
  out=$(s3_eval s3-slots-absent '.windowProm |= map(.slotsUsed = {workflowMax: null, workflowAvg: null, activityMax: null})')
  expect "s3: slot gauge absent (older engine): advisory.parkedSlots" WARN "$(s3st "$out" advisory.parkedSlots)"
  expect "s3: slot gauge absent: says not available" yes \
    "$(s3_field s3-slots-absent '.checks[] | select(.id == "advisory.parkedSlots") | .detail' | grep -q 'not available' && echo yes || echo no)"
  expect "s3: slot gauge absent: verdict unchanged" PASS "$(jq -r .verdict <<<"$out")"
  out=$(s3_eval s3-slots-pinned '.windowProm |= map(if .kind == "plateau" then .slotsUsed.workflowMax = 20 else . end)')
  expect "s3: workflow slots pinned while parked: advisory.parkedSlots" WARN "$(s3st "$out" advisory.parkedSlots)"
  out=$(s3_eval s3-heap-absent '.windowProm |= map(.heap.usedAvg = null)')
  expect "s3: heap not exported: advisory.heapPerParked" WARN "$(s3st "$out" advisory.heapPerParked)"
  out=$(s3_eval s3-probe-slow '.probes[-1].latency.p95Ms = (.probes[0].latency.p95Ms * 2.5)')
  expect "s3: probe p95 at Pmax 2.5 x that at P=0: advisory.probeLatency" WARN "$(s3st "$out" advisory.probeLatency)"
  expect "s3: slow probes: verdict unchanged" PASS "$(jq -r .verdict <<<"$out")"
  out=$(s3_eval s3-host-hot '.hostLoad.end = {load1: 100, hostCpus: 10}')
  expect "s3: host load 10 per CPU: advisory.contention" WARN "$(s3st "$out" advisory.contention)"
  out=$(s3_eval s3-host-quiet '.hostLoad = {scenarioStart: {load1: 1, hostCpus: 10}, windows: [], end: {load1: 1, hostCpus: 10}}')
  expect "s3: host load 0.1 per CPU, node CPU low: advisory.contention" PASS "$(s3st "$out" advisory.contention)"
  out=$(s3_eval s3-cpu-hot '.windowProm |= map(if .kind == "plateau" then .nodeCpu.processAvg = 0.95 else . end) | .hostLoad = {scenarioStart: {load1: 1, hostCpus: 10}, windows: [], end: null}')
  expect "s3: node CPU 0.95 in the plateau windows: advisory.contention" WARN "$(s3st "$out" advisory.contention)"
  out=$(s3_eval s3-adv-garbage '.windowProm = "garbage" | .probes[0].latency = "x"')
  expect "s3: garbage Prometheus and latency records: verdict (advisories WARN, never ERROR)" PASS "$(jq -r .verdict <<<"$out")"
  expect "s3: garbage records: advisory.heapPerParked" WARN "$(s3st "$out" advisory.heapPerParked)"
  out=$(s3_eval s3-abandoned '.runAbandonedIncrease = 2')
  expect "s3: two runs abandoned: advisory.runAbandoned" WARN "$(s3st "$out" advisory.runAbandoned)"
fi

if ((failures > 0)); then
  echo "selftest: ${failures} case(s) failed"
  exit 1
fi
echo "selftest: all cases hold"
