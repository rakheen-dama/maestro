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

# evaluate_with <case> <jq filter applied to measurements.json>
# Prints the doctored run's verdict and its check statuses.
evaluate_with() {
  local dir="${work}/$1"
  mkdir -p "$dir"
  cp "${src}/identity.json" "$dir/"
  jq "$2" "${src}/measurements.json" >"${dir}/measurements.json"
  bash "${PERF_DIR}/lib/evaluate.sh" "$dir" "$scenario" "" >/dev/null
  jq -c '{verdict, checks: (.checks | map({key: .id, value: .status}) | from_entries)}' "${dir}/result.json"
}

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

# statements_gate, the shared baseline-relative gate: net vs expected x 1.10 + tolerance.
gate_of() { # <net|null> <tolerance> <expected|null>
  jq -n -r -L "${PERF_DIR}/eval" --argjson n "$1" --argjson t "$2" --argjson e "$3" \
    'include "common"; statements_gate("gate.x"; {statementsPerWorkflowNet: $n, netTolerancePerWorkflow: $t}; $e; 1.10; 0.80)
     | "\(.status)\(if .value.belowStaleFloor then "+stale" else "" end)"'
}
expect "gate: net 21 vs expected 20 (limit 22.15)" PASS "$(gate_of 21 0.15 20)"
expect "gate: net 22.15 at the limit (20 x 1.10 + 0.15)" PASS "$(gate_of 22.15 0.15 20)"
expect "gate: net 22.2 just over the limit" FAIL "$(gate_of 22.2 0.15 20)"
expect "gate: tolerance applied (22.1 > 22.0, < 22.15)" PASS "$(gate_of 22.1 0.15 20)"
expect "gate: no expected value" NO_BASELINE "$(gate_of 21 0.15 null)"
expect "gate: net missing" FAIL "$(gate_of null 0.15 20)"
expect "gate: far below baseline" PASS+stale "$(gate_of 15 0.15 20)"

expect "unknown status PASSED" ERROR "$(verdict_of '.[0].status = "PASSED"')"
expect "unknown class" ERROR "$(verdict_of '.[0].class = "validty"')"
expect "validity check WARN" INVALID "$(verdict_of 'map(if .id == "validity.identity" then .status = "WARN" else . end)')"
expect "validity check NO_BASELINE" INVALID "$(verdict_of 'map(if .id == "validity.noRestart" then .status = "NO_BASELINE" else . end)')"
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
