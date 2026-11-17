#!/usr/bin/env bash
# Evaluator self-test for the WP12 finding-linked scenarios:
#   perf/eval/selftest-wp12.sh <a real s2-recovery-drain result dir>
# (perf/eval/selftest-evidence.sh rebuilds it from perf/.evidence/wp12/.)
#
# Each source is a real run; the cases doctor its measurements.json (and
# identity.json) one fault at a time, re-run perf/lib/evaluate.sh and assert
# the verdict and check statuses each fault must produce. The S2 source is
# the WP6 "before" (the engine without the recovery-capacity fix), so the
# unchanged run must FAIL on the three fix-linked gates; doctored to the
# shape WP6 predicts it must PASS. No Docker, no network.
# Exit 0 when every case holds, 1 otherwise.
# SC2016: the single-quoted '$x' strings are jq filters, expanded by jq.
# shellcheck disable=SC2016
set -Eeuo pipefail

PERF_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
s2src=${1:?usage: perf/eval/selftest-wp12.sh <s2-recovery-drain result dir>}
work=$(mktemp -d "${TMPDIR:-/tmp}/perf-selftest-wp12.XXXXXX")
trap 'rm -rf "$work"' EXIT
failures=0

expect() {
  local name=$1 expected=$2 actual=$3
  if [[ "$actual" == "$expected" ]]; then
    printf 'ok    %-70s %s\n' "$name" "$actual"
  else
    printf 'FAIL  %-70s expected %s, got %s\n' "$name" "$expected" "$actual"
    failures=$((failures + 1))
  fi
}

# eval_case <src dir> <scenario> <case> <measurements filter> [<identity filter>]
# Prints {verdict, checks: {id: status}}; CRASH when the evaluator wrote nothing.
eval_case() {
  local src=$1 scenario=$2 dir="${work}/$3"
  mkdir -p "$dir"
  jq "${5:-.}" "${src}/identity.json" >"${dir}/identity.json"
  jq "$4" "${src}/measurements.json" >"${dir}/measurements.json"
  if ! bash "${PERF_DIR}/lib/evaluate.sh" "$dir" "$scenario" "" >/dev/null 2>"${dir}/stderr"; then
    printf '{"verdict":"CRASH","checks":{}}\n'
    return 0
  fi
  jq -c '{verdict, checks: (.checks | map({key: .id, value: .status}) | from_entries)}' "${dir}/result.json"
}
st() { jq -r --arg id "$2" '.checks[$id] // "absent"' <<<"$1"; }
field() { # <case> <check id> <jq path under the check>
  jq -c --arg id "$2" ".checks[] | select(.id == \$id) | $3" "${work}/$1/result.json" 2>/dev/null || echo CRASH
}

# ── Scenario 2 (s2-recovery-drain) ──────────────────────────────────────────
[[ "$(jq -r .scenario "${s2src}/identity.json")" == s2-recovery-drain ]] \
  || { echo "selftest-wp12: ${s2src} is not an s2-recovery-drain run"; exit 1; }
s2() { eval_case "$s2src" s2-recovery-drain "$@"; }
# Phase helpers over the source's own figures: A = .phases[0], B = .phases[1].
# Drain is measured on the database clock: status.lastCompletedAt - t0.
# ratio_b <r>: both phases' t0 cut to whole seconds, phase A's drain kept
# (whole seconds), phase B's last completion set to t0B + floor(r x drainA).
ratio_b() {
  printf '.phases |= map(.t0 = (.t0[0:19] + "Z"))
    | (.phases[0] | ((.status.lastCompletedAt[0:19] + "Z" | fromdate) - (.t0 | fromdate))) as $a
    | .phases[0].status.lastCompletedAt = ((.phases[0].t0 | fromdate) + $a | todate)
    | .phases[1].status.lastCompletedAt = ((.phases[1].t0 | fromdate) + (%s * $a | floor) | todate)' "$1"
}
# The fix's shape: hoard at most 18 on every node, every probe p95 5 s.
after_shape='.phases |= map(.hoardSamples |= map(if .running != null and .slotsWorkflow != null
                                                 then .running = ((.parked // 0) + .slotsWorkflow + ([18, .running - (.parked // 0) - .slotsWorkflow] | min))
                                                 else . end)
                          | .probes.latency.p95Ms = 5000)'
# Recovery statements per seeded run in every phase set to exactly <x>, by
# moving the difference into adoptCas.
stmts_at() {
  printf '.phases |= map(
      ((.components.components.lockAcquire.calls // 0) - .probes.started
       + (.components.components.recoveryPage.calls // 0) + (.components.components.heldKeys.calls // 0)) as $other
      | .recovery.adoptCas.calls = (%s * .rows - $other | round))' "$1"
}

out=$(s2 s2-before '.')
expect "s2: the real before run: verdict" FAIL "$(jq -r .verdict <<<"$out")"
expect "s2: the real before run: gate.twoNodeDrain" FAIL "$(st "$out" gate.twoNodeDrain)"
expect "s2: the real before run: gate.noHoard" FAIL "$(st "$out" gate.noHoard)"
expect "s2: the real before run: gate.freshStartsNotStarved" FAIL "$(st "$out" gate.freshStartsNotStarved)"
expect "s2: the real before run: gate.recoveryStatements" PASS "$(st "$out" gate.recoveryStatements)"
for v in validity.template validity.seeded validity.rollingStart validity.recoveryClassifier validity.hoardSeries correctness.allRecovered; do
  expect "s2: the real before run: ${v}" PASS "$(st "$out" "$v")"
done

out=$(s2 s2-after "$(ratio_b 0.55) | ${after_shape}")
expect "s2: doctored to the after shape (0.55, hoard 18, probe p95 5 s): verdict" PASS "$(jq -r .verdict <<<"$out")"
for v in gate.twoNodeDrain gate.noHoard gate.freshStartsNotStarved gate.recoveryStatements; do
  expect "s2: after shape: ${v}" PASS "$(st "$out" "$v")"
done

out=$(s2 s2-classifier "$(stmts_at 0) | .phases |= map(.components.components.recoveryPage.calls = 0
  | .components.components.heldKeys.calls = 0 | .recovery.adoptCas.calls = 0
  | .components.components.lockAcquire.calls = .probes.started)")
expect "s2: no recovery statement matched (texts changed): validity.recoveryClassifier" INVALID "$(st "$out" validity.recoveryClassifier)"
expect "s2: no recovery statement matched: verdict (never a vacuous PASS)" INVALID "$(jq -r .verdict <<<"$out")"

out=$(s2 s2-no-slots '.phases |= map(.hoardSamples |= map(.slotsWorkflow = null))')
expect "s2: slot series removed: validity.hoardSeries" INVALID "$(st "$out" validity.hoardSeries)"
expect "s2: slot series removed: verdict" INVALID "$(jq -r .verdict <<<"$out")"
out=$(s2 s2-no-node2 "$(ratio_b 0.55) | ${after_shape} | .phases[1].hoardSamples |= map(select(.container | endswith(\"-2\") | not))")
expect "s2: no hoard sample of node 2 in phase B: validity.hoardSeries" INVALID "$(st "$out" validity.hoardSeries)"

out=$(s2 s2-failed '.phases[0].status.byStatus = {COMPLETED: (.phases[0].rows - 1), FAILED: 1}
  | .phases[0].status.outputs = {"2": (.phases[0].rows - 1)}')
expect "s2: one seeded row FAILED: correctness.allRecovered" FAIL "$(st "$out" correctness.allRecovered)"
out=$(s2 s2-probe-open '.phases[1].probes.status.byStatus = {COMPLETED: (.phases[1].probes.started - 1), RUNNING: 1}')
expect "s2: one probe not COMPLETED: correctness.allRecovered" FAIL "$(st "$out" correctness.allRecovered)"
out=$(s2 s2-timeout '.phases[0].drain.timedOut = true')
expect "s2: phase A drain timed out: correctness.allRecovered" FAIL "$(st "$out" correctness.allRecovered)"

out=$(s2 s2-rolling-early '.phases[1].starts[1].startIssuedAt = (.phases[1].starts[0].readyAt - 1)')
expect "s2: node 2 started before node 1 read ready: validity.rollingStart" INVALID "$(st "$out" validity.rollingStart)"
out=$(s2 s2-rolling-late '.phases[1].starts[1].startIssuedAt = (.phases[1].starts[0].readyAt + 16)')
expect "s2: node 2 started 16 s after node 1 read ready: validity.rollingStart" INVALID "$(st "$out" validity.rollingStart)"
out=$(s2 s2-rolling-unready '.phases[1].starts[0].readyAt = null')
expect "s2: node 1 readiness not recorded in phase B: validity.rollingStart" INVALID "$(st "$out" validity.rollingStart)"

out=$(s2 s2-lockrows '.phases[0].seeded.lockRows = 3')
expect "s2: seeded.lockRows 3: validity.seeded" INVALID "$(st "$out" validity.seeded)"
out=$(s2 s2-seeded-short '.phases[1].seeded.total = (.phases[1].rows - 1)')
expect "s2: phase B seeded one row short: validity.seeded" INVALID "$(st "$out" validity.seeded)"
out=$(s2 s2-seeded-events '.phases[0].seeded.events -= 1')
expect "s2: one event row missing from the seed: validity.seeded" INVALID "$(st "$out" validity.seeded)"
out=$(s2 s2-tmpl '.template.activityCompleted = 2')
expect "s2: template snapshot with 2 ACTIVITY_COMPLETED: validity.template" INVALID "$(st "$out" validity.template)"
out=$(s2 s2-tmpl-final '.template.finalByStatus = {FAILED: 1}')
expect "s2: template run ended FAILED: validity.template" INVALID "$(st "$out" validity.template)"

out=$(s2 s2-ratio-066 "$(ratio_b 0.66) | ${after_shape}")
expect "s2: drainB / drainA = 0.66: gate.twoNodeDrain" FAIL "$(st "$out" gate.twoNodeDrain)"
out=$(s2 s2-ratio-065 "$(ratio_b 0.65) | ${after_shape}")
expect "s2: drainB / drainA = 0.65 (the boundary): gate.twoNodeDrain" PASS "$(st "$out" gate.twoNodeDrain)"
out=$(s2 s2-hoard-23 "$(ratio_b 0.55) | ${after_shape} | .phases[1].hoardSamples[-1] |= (.running = (.parked // 0) + .slotsWorkflow + 23)")
expect "s2: one sample with hoard C + 3: gate.noHoard" FAIL "$(st "$out" gate.noHoard)"
out=$(s2 s2-hoard-edge "$(ratio_b 0.55) | ${after_shape} | .phases[1].hoardSamples[-1] |= (.running = (.parked // 0) + .slotsWorkflow + 22)")
expect "s2: one sample with hoard C + 2 (the bound): gate.noHoard" PASS "$(st "$out" gate.noHoard)"
out=$(s2 s2-probe-quarter "$(ratio_b 0.55) | ${after_shape} | (.phases[0] | ((.status.lastCompletedAt | fromdate) - (.t0 | fromdate))) as \$a | .phases[0].probes.latency.p95Ms = (0.26 * \$a * 1000)")
expect "s2: phase A probe p95 = 0.26 x drainA: gate.freshStartsNotStarved" FAIL "$(st "$out" gate.freshStartsNotStarved)"

out=$(s2 s2-stmts-251 "$(ratio_b 0.55) | ${after_shape} | $(stmts_at 2.51)")
expect "s2: recovery statements 2.51 per run: gate.recoveryStatements" FAIL "$(st "$out" gate.recoveryStatements)"
out=$(s2 s2-stmts-250 "$(ratio_b 0.55) | ${after_shape} | $(stmts_at 2.5)")
expect "s2: recovery statements 2.5 per run (the bound): gate.recoveryStatements" PASS "$(st "$out" gate.recoveryStatements)"

out=$(s2 s2-full-noc '.' '.profile = "full"')
expect "s2: full profile without phase C: advisory.c2Stream" WARN "$(st "$out" advisory.c2Stream)"
out=$(s2 s2-local-c '.')
expect "s2: local profile: advisory.c2Stream" PASS "$(st "$out" advisory.c2Stream)"
expect "s2: local profile: advisory.c2Stream says not measured" yes \
  "$(field s2-local-c advisory.c2Stream .detail | grep -qi 'not measured' && echo yes || echo no)"
out=$(s2 s2-full-c '.phases += [{name: "C", rows: 600, perMinute: [{minute: 0, completed: 0}, {minute: 1, completed: 300}]}]' '.profile = "full"')
expect "s2: full, phase C with a minute of 0 recovered completions: advisory.c2Stream" WARN "$(st "$out" advisory.c2Stream)"
out=$(s2 s2-adv-garbage '.emptyStarts = "x" | .phases[0].components = "x"')
expect "s2: malformed advisory inputs: no evaluator ERROR (advisories WARN)" yes \
  "$(jq -r '.verdict' <<<"$out" | grep -qv -e ERROR -e CRASH && echo yes || echo no)"

if ((failures > 0)); then
  echo "selftest-wp12: ${failures} case(s) failed"
  exit 1
fi
echo "selftest-wp12: all cases hold"
