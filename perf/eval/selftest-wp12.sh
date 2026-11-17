#!/usr/bin/env bash
# Evaluator self-test for the WP12 finding-linked scenarios:
#   perf/eval/selftest-wp12.sh <s2-recovery-drain dir>
#       [<s5-pool-health after dir> <s5-pool-health before dir>]
#       [<s4-signals after dir> <s4-signals before dir>] [<s6-retention before dir>]
#       [<s6-retention after dir>] [<s2 WP6 preview dir> <s2 WP6 after-of-record dir>]
# Each source is a real result directory (identity.json, measurements.json);
# pass "" to skip a scenario. perf/eval/selftest-evidence.sh rebuilds them
# from perf/.evidence/wp12/.
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
s2src=${1:-}
s5after=${2:-}
s5before=${3:-}
s4after=${4:-}
s4before=${5:-}
s6before=${6:-}
s6after=${7:-}
s2preview=${8:-}
s2after=${9:-}
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

source_is() { # <dir> <scenario>
  [[ "$(jq -r .scenario "$1/identity.json")" == "$2" ]] || { echo "selftest-wp12: $1 is not an $2 run"; exit 1; }
}

# ── Scenario 2 (s2-recovery-drain) ──────────────────────────────────────────
if [[ -n "$s2src" ]]; then
source_is "$s2src" s2-recovery-drain
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
for v in gate.lockAcquisitionsPerAdoption gate.lostLockRaces gate.recoveryPassRate; do
  expect "s2: the real before run: ${v}" PASS "$(st "$out" "$v")"
done
expect "s2: the real before run: the combined figure is an advisory" PASS "$(st "$out" advisory.recoveryStatements)"
for v in validity.template validity.seeded validity.rollingStart validity.recoveryClassifier validity.hoardSeries correctness.allRecovered; do
  expect "s2: the real before run: ${v}" PASS "$(st "$out" "$v")"
done

out=$(s2 s2-after "$(ratio_b 0.55) | ${after_shape}")
expect "s2: doctored to the after shape (0.55, hoard 18, probe p95 5 s): verdict" PASS "$(jq -r .verdict <<<"$out")"
for v in gate.twoNodeDrain gate.noHoard gate.freshStartsNotStarved gate.lockAcquisitionsPerAdoption gate.lostLockRaces gate.recoveryPassRate; do
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
out=$(s2 s2-hoard-41 "$(ratio_b 0.55) | ${after_shape} | .phases[1].hoardSamples[-1] |= (.running = (.parked // 0) + .slotsWorkflow + 41)")
expect "s2: one sample with hoard 2C + 1: gate.noHoard" FAIL "$(st "$out" gate.noHoard)"
out=$(s2 s2-hoard-edge "$(ratio_b 0.55) | ${after_shape} | .phases[1].hoardSamples[-1] |= (.running = (.parked // 0) + .slotsWorkflow + 40)")
expect "s2: one sample with hoard 2C (the documented bound): gate.noHoard" PASS "$(st "$out" gate.noHoard)"
out=$(s2 s2-probe-quarter "$(ratio_b 0.55) | ${after_shape} | (.phases[0] | ((.status.lastCompletedAt | fromdate) - (.t0 | fromdate))) as \$a | .phases[0].probes.latency.p95Ms = (0.26 * \$a * 1000)")
expect "s2: phase A probe p95 = 0.26 x drainA: gate.freshStartsNotStarved" FAIL "$(st "$out" gate.freshStartsNotStarved)"

# The three recovery-statement gates (review r2). Phase B of the after shape.
# lock_b <rows> <calls>: the lock-acquire statement's rows and calls in phase B
# (pg_stat_statements' list and the component count kept consistent).
lock_b() { # <rows expr> <calls expr>, over $n (the phase's seeded runs) and $pr (its probe starts)
  printf '.phases[1] |= (.rows as $n | .probes.started as $pr
     | (.window.top |= map(if (.query | test("^INSERT INTO maestro_distributed_lock")) then .rows = (%s) | .calls = (%s) else . end))
     | .components.components.lockAcquire.calls = (%s))' "$1" "$2" "$2"
}
base="$(ratio_b 0.55) | ${after_shape}"
out=$(s2 s2-acq-102 "${base} | $(lock_b '$n + $pr + 102' '$n + $pr + 102')")
expect "s2: lock successes + adoptCas = 2.0204 per adoption: gate.lockAcquisitionsPerAdoption" FAIL "$(st "$out" gate.lockAcquisitionsPerAdoption)"
out=$(s2 s2-acq-100 "${base} | $(lock_b '$n + $pr + 100' '$n + $pr + 100')")
expect "s2: lock successes + adoptCas = 2.02 per adoption (the bound): gate.lockAcquisitionsPerAdoption" PASS "$(st "$out" gate.lockAcquisitionsPerAdoption)"
out=$(s2 s2-lost-2500 "${base} | $(lock_b '$n + $pr' '$n + $pr + 2500')")
expect "s2: lost lock races = 0.5 per seeded run (the bound): gate.lostLockRaces" PASS "$(st "$out" gate.lostLockRaces)"
out=$(s2 s2-lost-2501 "${base} | $(lock_b '$n + $pr' '$n + $pr + 2501')")
expect "s2: lost lock races = 0.5002 per seeded run: gate.lostLockRaces" FAIL "$(st "$out" gate.lostLockRaces)"
out=$(s2 s2-lost-5000 "${base} | $(lock_b '$n + $pr' '$n + $pr + 5000')")
expect "s2: a peer chasing every row (1 lost race per run): gate.lostLockRaces" FAIL "$(st "$out" gate.lostLockRaces)"
# Pass rate: scale phase B's page and heldKeys reads to just inside / outside
# the designed rate x slack, from the evaluator's own ratio on the unscaled run.
out=$(s2 s2-rate-base "${base}")
rate_b=$(field s2-rate-base gate.recoveryPassRate '.value[] | select(.phase == "B") | .ratio')
pass_rate() { # <factor of the limit>
  printf '.phases[1].components.components.recoveryPage.calls |= (. * %s / %s | floor)
    | .phases[1].components.components.heldKeys.calls |= (. * %s / %s | floor)' "$1" "$rate_b" "$1" "$rate_b"
}
out=$(s2 s2-rate-in "${base} | $(pass_rate 1.24)")
expect "s2: recovery reads at 1.24 x the designed pass rate: gate.recoveryPassRate" PASS "$(st "$out" gate.recoveryPassRate)"
out=$(s2 s2-rate-out "${base} | $(pass_rate 1.27)")
expect "s2: recovery reads at 1.27 x the designed pass rate (a re-poll storm): gate.recoveryPassRate" FAIL "$(st "$out" gate.recoveryPassRate)"
# Review r3: every statement component must be matched, and agree.
out=$(s2 s2-heldkeys-lost "${base} | .phases[1].components.components.heldKeys.calls = 0")
expect "s2: heldKeys unmatched in phase B only: validity.recoveryClassifier" INVALID "$(st "$out" validity.recoveryClassifier)"
out=$(s2 s2-adopt-mismatch "${base} | .phases[0].recovery.adoptCas.calls -= 1")
expect "s2: adoptCas != the nodes' adoptions: validity.recoveryClassifier" INVALID "$(st "$out" validity.recoveryClassifier)"
out=$(s2 s2-acquire-short "${base} | .phases[0].components.components.lockAcquire.calls = (.phases[0].rows - 1)")
expect "s2: lockAcquire calls below N: validity.recoveryClassifier" INVALID "$(st "$out" validity.recoveryClassifier)"
# PR #65 review: a missing status record is INVALID, never a FAIL.
for f in 'del(.phases[0].status.byStatus)' 'del(.phases[1].status.outputs)' 'del(.phases[0].drain.timedOut)' 'del(.phases[1].probes.status.byStatus)'; do
  out=$(s2 s2-norecord "${base} | ${f}")
  expect "s2: ${f}: validity.statusRecorded" INVALID "$(st "$out" validity.statusRecorded)"
  expect "s2: ${f}: verdict" INVALID "$(jq -r .verdict <<<"$out")"
  rm -rf "${work}/s2-norecord"
done
# PR #65 review: the pass rate's reads and node-seconds cover one interval
# (to the statement window's end). Phase B's window held open 600 s past the
# last completion, with the reads the engine's designed rate makes in that
# tail on both nodes (2 / s + 2 x 25 pages / 60 s each): still in bounds.
out=$(s2 s2-tail "${base} | .phases[1] |= (.window.snapshotAt = ((.status.lastCompletedAt | fromdate) + 600 | todate)
   | .components.components.recoveryPage.calls += 1700 | .components.components.heldKeys.calls += 1700)")
expect "s2: a 600 s tail after the drain, read at the designed rate: gate.recoveryPassRate" PASS "$(st "$out" gate.recoveryPassRate)"

# Review r9 and r12.
out=$(s2 s2-node2-unready "${base} | .phases[1].starts[1].readyAt = null")
expect "s2: node 2 never read ready in phase B: validity.rollingStart" INVALID "$(st "$out" validity.rollingStart)"
out=$(s2 s2-probe-edge "${base} | (.phases[0] | ((.status.lastCompletedAt | fromdate) - (.t0 | fromdate))) as \$a | .phases[0].probes.latency.p95Ms = (0.25 * \$a * 1000)")
expect "s2: phase A probe p95 = 0.25 x drainA (the bound): gate.freshStartsNotStarved" PASS "$(st "$out" gate.freshStartsNotStarved)"

# Review r11: the WP6 preview and the after of record must stay PASS.
for src in "$s2preview" "$s2after"; do
  [[ -n "$src" ]] || continue
  source_is "$src" s2-recovery-drain
  name=$(jq -r .runId "${src}/identity.json")
  out=$(eval_case "$src" s2-recovery-drain "s2-real-${name}" '.')
  expect "s2: the WP6 run ${name}: verdict" PASS "$(jq -r .verdict <<<"$out")"
  for v in gate.twoNodeDrain gate.noHoard gate.freshStartsNotStarved gate.lockAcquisitionsPerAdoption gate.lostLockRaces gate.recoveryPassRate; do
    expect "s2: the WP6 run ${name}: ${v}" PASS "$(st "$out" "$v")"
  done
done

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
fi

# ── Scenario 5 (s5-pool-health) ─────────────────────────────────────────────
# Sources: the WP4 after (this branch's engine) and the WP4 before (bdcc074d).
# Phases: .phases[0] idle, [1] exhausted, [2] recovered.
if [[ -n "$s5after" ]]; then
  source_is "$s5after" s5-pool-health
  source_is "$s5before" s5-pool-health
  s5() { eval_case "$s5after" s5-pool-health "$@"; }
  out=$(s5 s5-after '.')
  expect "s5: the real after run: verdict" PASS "$(jq -r .verdict <<<"$out")"
  for v in validity.poolExhausted validity.storeReachable validity.noPoolTimeouts validity.healthSamples \
    correctness.upWhenIdle correctness.holdsCompleted correctness.noLoopFault gate.notDownWhenBusy; do
    expect "s5: the real after run: ${v}" PASS "$(st "$out" "$v")"
  done
  out=$(eval_case "$s5before" s5-pool-health s5-before '.')
  expect "s5: the real before run: verdict" FAIL "$(jq -r .verdict <<<"$out")"
  expect "s5: the real before run: gate.notDownWhenBusy" FAIL "$(st "$out" gate.notDownWhenBusy)"
  expect "s5: the real before run: validity.poolExhausted" PASS "$(st "$out" validity.poolExhausted)"
  out=$(s5 s5-half '.phases[1].sampler.matchingSamples = (.phases[1].sampler.samples * 0.5 | floor)')
  expect "s5: exhausted busy samples at 50 %: validity.poolExhausted" INVALID "$(st "$out" validity.poolExhausted)"
  out=$(s5 s5-busy-080 '.phases[1].sampler.samples = 375 | .phases[1].sampler.matchingSamples = 300')
  expect "s5: exhausted busy samples at exactly 80 % (the bound): validity.poolExhausted" PASS "$(st "$out" validity.poolExhausted)"
  out=$(s5 s5-busy-079 '.phases[1].sampler.samples = 375 | .phases[1].sampler.matchingSamples = 299')
  expect "s5: exhausted busy samples at 79.7 %: validity.poolExhausted" INVALID "$(st "$out" validity.poolExhausted)"
  out=$(s5 s5-no-pending '.pendingMax.value = 0')
  expect "s5: Hikari pending never above 0: validity.poolExhausted" INVALID "$(st "$out" validity.poolExhausted)"
  out=$(s5 s5-timeout '.hikari.end.timeouts = (.hikari.start.timeouts + 1)')
  expect "s5: one Hikari timeout: validity.noPoolTimeouts" INVALID "$(st "$out" validity.noPoolTimeouts)"
  out=$(s5 s5-gap '.sampler.maxGapSecs = 2.5')
  expect "s5: sampler gap 2.5 s: validity.storeReachable" INVALID "$(st "$out" validity.storeReachable)"
  out=$(s5 s5-few '.phases[1].counters.maestro_unknown = 0 | .phases[1].counters.maestro_up = 1')
  expect "s5: exhausted phase with too few health samples: validity.healthSamples" INVALID "$(st "$out" validity.healthSamples)"
  out=$(s5 s5-idle-unknown '.phases[0].counters.maestro_up -= 1 | .phases[0].counters.maestro_unknown += 1')
  expect "s5: one idle UNKNOWN: correctness.upWhenIdle" FAIL "$(st "$out" correctness.upWhenIdle)"
  out=$(s5 s5-down-other '.phases[2].counters.maestro_up -= 1 | .phases[2].counters.maestro_down_other += 1')
  expect "s5: one maestro_down_other: correctness.noLoopFault" FAIL "$(st "$out" correctness.noLoopFault)"
  out=$(s5 s5-hold-failed '.holds.runStatus.byStatus = {COMPLETED: (.plan.holdRuns - 1), FAILED: 1}')
  expect "s5: one hold run FAILED: correctness.holdsCompleted" FAIL "$(st "$out" correctness.holdsCompleted)"
  out=$(s5 s5-no-answer '.phases[1].counters.maestro_unknown -= 1 | .phases[1].counters.maestro_no_answer += 1')
  expect "s5: one exhausted-phase no answer: gate.notDownWhenBusy" FAIL "$(st "$out" gate.notDownWhenBusy)"
  out=$(s5 s5-one-down '.phases[1].counters.maestro_unknown -= 1 | .phases[1].counters.maestro_down_store_timed_out += 1')
  expect "s5: one exhausted-phase DOWN (store timed out): gate.notDownWhenBusy" FAIL "$(st "$out" gate.notDownWhenBusy)"
  out=$(s5 s5-no-root 'del(.phases[1].counters.root_up)')
  expect "s5: a root counter missing: advisory.rootHealth" WARN "$(st "$out" advisory.rootHealth)"
  expect "s5: a root counter missing: verdict not ERROR" PASS "$(jq -r .verdict <<<"$out")"
  out=$(s5 s5-garbage '.hikari = "x" | .phases[1].counters.rootAnswerMs = "x"')
  expect "s5: malformed advisory inputs: no evaluator ERROR" yes \
    "$(jq -r '.verdict' <<<"$out" | grep -qv -e ERROR -e CRASH && echo yes || echo no)"
fi

# ── Scenario 4 (s4-signals) ─────────────────────────────────────────────────
# Sources: the WP2 after (this branch's engine) and the WP2 before (0c710a1e).
# Phases: .phases[0] local, [1] cross.
if [[ -n "$s4after" ]]; then
  source_is "$s4after" s4-signals
  source_is "$s4before" s4-signals
  s4() { eval_case "$s4after" s4-signals "$@"; }
  out=$(s4 s4-after '.')
  expect "s4: the real after run: verdict" PASS "$(jq -r .verdict <<<"$out")"
  for v in validity.replicas validity.permitGauge correctness.allSignalsConsumed gate.noStrayPermits gate.crossNodeStatementParity; do
    expect "s4: the real after run: ${v}" PASS "$(st "$out" "$v")"
  done
  out=$(eval_case "$s4before" s4-signals s4-before '.')
  expect "s4: the real before run: verdict" FAIL "$(jq -r .verdict <<<"$out")"
  expect "s4: the real before run: gate.noStrayPermits" FAIL "$(st "$out" gate.noStrayPermits)"
  expect "s4: the real before run: correctness.allSignalsConsumed" PASS "$(st "$out" correctness.allSignalsConsumed)"
  expect "s4: the real before run: gate.crossNodeStatementParity" PASS "$(st "$out" gate.crossNodeStatementParity)"
  out=$(s4 s4-no-gauge '.permits.P2.nodes[1].value = null')
  expect "s4: one node's maestro_wake_permits absent at P2: validity.permitGauge" INVALID "$(st "$out" validity.permitGauge)"
  expect "s4: one node's permit gauge absent: gate.noStrayPermits not PASS" INVALID "$(st "$out" gate.noStrayPermits)"
  out=$(s4 s4-one-replica '.' '.config.replicas = 1 | .nodes |= .[0:1]')
  expect "s4: one replica: validity.replicas" INVALID "$(st "$out" validity.replicas)"
  out=$(s4 s4-unconsumed '.phases[1].status.signals.consumed -= 1 | .phases[1].status.signals.unconsumed = 1')
  expect "s4: one signal row unconsumed: correctness.allSignalsConsumed" FAIL "$(st "$out" correctness.allSignalsConsumed)"
  out=$(s4 s4-lost '.phases[0].status.signals.rows -= 1 | .phases[0].status.signals.consumed -= 1')
  expect "s4: one accepted signal without a row: correctness.allSignalsConsumed" FAIL "$(st "$out" correctness.allSignalsConsumed)"
  out=$(s4 s4-sleep '.timer.status.results.slept -= 1')
  expect "s4: one sleep run not slept: correctness.allSignalsConsumed" FAIL "$(st "$out" correctness.allSignalsConsumed)"
  # Review r6: per-node deltas, never a sum that cancels.
  out=$(s4 s4-cancel '.permits.P2.nodes[0].value += 3 | .permits.P2.nodes[1].value -= 3 | .permits.P3.nodes[0].value += 3 | .permits.P3.nodes[1].value -= 3')
  expect "s4: +3 on one node and -3 on the other in the cross phase: gate.noStrayPermits" FAIL "$(st "$out" gate.noStrayPermits)"
  out=$(s4 s4-local-stray '.permits |= (.P1.nodes[0].value += 1 | .P2.nodes[0].value += 1 | .P3.nodes[0].value += 1)')
  expect "s4: one stray permit in the local phase: gate.noStrayPermits" FAIL "$(st "$out" gate.noStrayPermits)"
  # Review r5: both phases set up as planned before signalling.
  out=$(s4 s4-not-parked '.phases[0].parked.timedOut = true | .phases[0].parked.byStatus = {WAITING_SIGNAL: (.phases[0].w - 1), RUNNING: 1}')
  expect "s4: the local phase signalled before every run parked: validity.phases" INVALID "$(st "$out" validity.phases)"
  out=$(s4 s4-not-cross '.phases[1].plan.crossNode = false')
  expect "s4: the cross phase did not route across nodes: validity.phases" INVALID "$(st "$out" validity.phases)"
  out=$(s4 s4-local-routed '.phases[0].route = "next" | .phases[0].plan.route = "next"')
  expect "s4: the local phase routed to the next node: validity.phases" INVALID "$(st "$out" validity.phases)"
  out=$(s4 s4-stray '.permits.P3.nodes[0].value += 1')
  expect "s4: one stray permit after the timer phase: gate.noStrayPermits" FAIL "$(st "$out" gate.noStrayPermits)"
  # Parity: the cross window made a copy of the local one, plus extra calls
  # worth <x> x local net + tolerance + <eps> per consumed signal.
  parity() { # <fraction of local net> <epsilon per signal>
    printf '(.background.window) as $b
      | ((($b.maestroCalls - ($b.sweepCalls.lockCleaner // 0)) / $b.windowSecs)) as $rate
      | .phases[0] as $l | ($l.status.signals.consumed) as $n
      | ((($l.window.maestroCalls - ($l.window.sweepCalls.lockCleaner // 0) - $rate * $l.window.windowSecs) / $n)) as $net
      | ((($b.maestroStatements - ($b.sweepCalls.lockCleanerStatements // 0)) * 2 / $n)) as $tol
      | .phases[1].window = ($l.window | .maestroCalls += (($n * (%s * $net + $tol + %s)) | ceil))
      | .phases[1].status.signals.consumed = $n' "$1" "$2"
  }
  out=$(s4 s4-parity-fail "$(parity 0.10 0.02)")
  expect "s4: cross net per signal = local x 1.10 + tolerance: gate.crossNodeStatementParity" FAIL "$(st "$out" gate.crossNodeStatementParity)"
  out=$(s4 s4-parity-pass "$(parity 0.05 -0.03)")
  expect "s4: cross net per signal just inside local x 1.05 + tolerance: gate.crossNodeStatementParity" PASS "$(st "$out" gate.crossNodeStatementParity)"
  out=$(s4 s4-dropped '.k6 |= map(if .phase == "cross" then .droppedIterations = 1 else . end)')
  expect "s4: a dropped iteration in the cross phase: verdict" INVALID "$(jq -r .verdict <<<"$out")"
  out=$(s4 s4-slow-cross '.phases[1].status.latencyMs.p95 = (.phases[0].status.latencyMs.p95 * 2)')
  expect "s4: cross p95 = 2 x local: advisory.consumeLatency" WARN "$(st "$out" advisory.consumeLatency)"
  expect "s4: cross p95 = 2 x local: verdict unchanged" PASS "$(jq -r .verdict <<<"$out")"
fi

# ── Scenario 6 (s6-retention) ───────────────────────────────────────────────
# Source: the WP8 before (this branch's engine, no WP8).
if [[ -n "$s6before" ]]; then
  source_is "$s6before" s6-retention
  s6() { eval_case "$s6before" s6-retention "$@"; }
  # busy_at <f>: the sweep's busy fraction set to f, both the time-weighted
  # one and the sample count, with pg_stat_statements' purge execution time
  # set to agree (validity.dutyCrossCheck) and the sampler's span resolution
  # at 0.2 s (validity.samplerResolution).
  busy_at() {
    printf '.sampler.spanSecs = (.sampler.spanSecs // (((.sampler.lastMatchAt[0:19] + "Z") | fromdate) - ((.sampler.firstMatchAt[0:19] + "Z") | fromdate)))
      | .sampler.busyTimeFraction = %s
      | .sampler.matchingInSpan = (.sampler.spanSamples * %s | ceil)
      | .sampler.spanMaxGapSecs = 0.2
      | .sweep.components.purgeDeletes.totalMs = (%s * .sampler.spanSecs * 1000 - (.sweep.components.purgeCandidates.totalMs // 0))' "$1" "$1" "$1"
  }
  s6_after='.final.sets.jforeign.signalsUnconsumed = .seeded.sets.jforeign.signalsUnconsumed
    | .sweep.components.janitor = {calls: 10, rows: .plan.sets.jown, totalMs: 100, entries: 1}'
  out=$(s6 s6-before '.')
  expect "s6: the real before run: verdict" FAIL "$(jq -r .verdict <<<"$out")"
  for v in gate.retentionPaced gate.janitorServiceScoped gate.janitorBatched; do
    expect "s6: the real before run: ${v}" FAIL "$(st "$out" "$v")"
  done
  for v in validity.seeded validity.sweepObserved validity.windows correctness.retentionExact correctness.janitorExact; do
    expect "s6: the real before run: ${v}" PASS "$(st "$out" "$v")"
  done
  out=$(s6 s6-after "$(busy_at 0.5) | ${s6_after}")
  expect "s6: doctored to the after shape (busy 0.5, foreign kept, 10 janitor calls): verdict" PASS "$(jq -r .verdict <<<"$out")"
  out=$(s6 s6-no-sweep '.counters.node2.after.sweeps = 0')
  expect "s6: node 2's sweeps +0: validity.sweepObserved" INVALID "$(st "$out" validity.sweepObserved)"
  out=$(s6 s6-node1-swept '.counters.node1.after.sweeps = (.counters.node1.before.sweeps + 1)')
  expect "s6: node 1 swept too: validity.sweepObserved" INVALID "$(st "$out" validity.sweepObserved)"
  out=$(s6 s6-young '.final.sets.young.instances -= 1')
  expect "s6: young set purged by 1: correctness.retentionExact" FAIL "$(st "$out" correctness.retentionExact)"
  out=$(s6 s6-rc-short '.final.sets.rc.instances -= 1')
  expect "s6: rc purged one past its budget: correctness.retentionExact" FAIL "$(st "$out" correctness.retentionExact)"
  out=$(s6 s6-orphan '.final.orphans.events = 1')
  expect "s6: one orphan event: correctness.retentionExact" FAIL "$(st "$out" correctness.retentionExact)"
  out=$(s6 s6-jfailed '.final.sets.jfailed.signalsUnconsumed -= 1')
  expect "s6: one jfailed unconsumed signal deleted: correctness.janitorExact" FAIL "$(st "$out" correctness.janitorExact)"
  out=$(s6 s6-busy-066 "$(busy_at 0.66) | ${s6_after}")
  expect "s6: busy 0.66: gate.retentionPaced" FAIL "$(st "$out" gate.retentionPaced)"
  out=$(s6 s6-busy-065 "$(busy_at 0.65) | ${s6_after}")
  expect "s6: busy 0.65 (the bound): gate.retentionPaced" PASS "$(st "$out" gate.retentionPaced)"
  out=$(s6 s6-janitor-one-batch "$(busy_at 0.5) | ${s6_after} | .sweep.components.janitor.calls = 1")
  expect "s6: one janitor call: gate.janitorBatched" FAIL "$(st "$out" gate.janitorBatched)"
  out=$(s6 s6-post-20 '.windows |= map(if .phase == "post" then .windowSecs = 20 else . end)')
  expect "s6: post window 20 s: validity.windows" INVALID "$(st "$out" validity.windows)"
  # Review r7: an unmatched statement is INVALID, never a FAIL or a PASS.
  out=$(s6 s6-janitor-unmatched "$(busy_at 0.5) | ${s6_after} | .sweep.components |= del(.janitor)")
  expect "s6: janitor statement unmatched while its signals are gone: validity.statementsClassified" INVALID "$(st "$out" validity.statementsClassified)"
  expect "s6: janitor statement unmatched: gate.janitorBatched not FAIL" INVALID "$(st "$out" gate.janitorBatched)"
  out=$(s6 s6-deletes-off "$(busy_at 0.5) | ${s6_after} | .sweep.components.purgeDeletes.calls -= 1")
  expect "s6: purge DELETE calls not 4 per batch: validity.statementsClassified" INVALID "$(st "$out" validity.statementsClassified)"
  out=$(s6 s6-duty-disagree "$(busy_at 0.5) | ${s6_after} | .sweep.components.purgeDeletes.totalMs = 100")
  expect "s6: sampler 0.5 busy, purge execution time ~0.01 of the span: validity.dutyCrossCheck" INVALID "$(st "$out" validity.dutyCrossCheck)"
  # Review r8: a sampler gap inside the sweep span makes the duty cycle untrustworthy.
  out=$(s6 s6-span-gap "$(busy_at 0.5) | ${s6_after} | .sampler.spanMaxGapSecs = 0.5")
  expect "s6: a 0.5 s sampler gap inside the sweep span: validity.samplerResolution" INVALID "$(st "$out" validity.samplerResolution)"
  out=$(s6 s6-span-gap-edge "$(busy_at 0.5) | ${s6_after} | .sampler.spanMaxGapSecs = 0.3")
  expect "s6: a 0.3 s sampler gap (the bound): validity.samplerResolution" PASS "$(st "$out" validity.samplerResolution)"
  out=$(s6 s6-time-weighted "$(busy_at 0.5) | ${s6_after} | .sampler.matchingInSpan = .sampler.spanSamples")
  expect "s6: the gate reads the time-weighted fraction (0.5), not the sample count (1.0): gate.retentionPaced" PASS "$(st "$out" gate.retentionPaced)"
  # Review r12.
  out=$(s6 s6-no-foreign '.seeded.sets.jforeign.signalsUnconsumed = 0 | .final.sets.jforeign.signalsUnconsumed = 0')
  expect "s6: no foreign unconsumed signal seeded: gate.janitorServiceScoped" INVALID "$(st "$out" gate.janitorServiceScoped)"
  out=$(s6 s6-rpc-edge "$(busy_at 0.5) | ${s6_after} | .sweep.components.janitor = {calls: 10, rows: 10000, totalMs: 100, entries: 1}")
  expect "s6: janitor 1,000 rows per call (the engine's batch, the bound): gate.janitorBatched" PASS "$(st "$out" gate.janitorBatched)"
  out=$(s6 s6-rpc-over "$(busy_at 0.5) | ${s6_after} | .sweep.components.janitor = {calls: 10, rows: 10001, totalMs: 100, entries: 1}")
  expect "s6: janitor 1,000.1 rows per call: gate.janitorBatched" FAIL "$(st "$out" gate.janitorBatched)"
  out=$(s6 s6-rpc-two "$(busy_at 0.5) | ${s6_after} | .sweep.components.janitor = {calls: 2, rows: 10000, totalMs: 100, entries: 1}")
  expect "s6: janitor 2 calls of 5,000 rows (batched, but not by 1,000): gate.janitorBatched" FAIL "$(st "$out" gate.janitorBatched)"
  out=$(s6 s6-not-ended '.sweep.ended = false')
  expect "s6: the sweep did not end within its allowance: validity.windows" INVALID "$(st "$out" validity.windows)"

  # PR #65 review: the template must carry every child kind, the sweep must
  # leave every retained instance's children, and the foreground must have
  # completed during the sweep.
  out=$(s6 s6-tmpl-no-timer '.template.timers = 0 | (.seeded.sets, .final.sets) |= map_values(.timers = 0)')
  expect "s6: a template without its timer (and seeded without timers): validity.seeded" INVALID "$(st "$out" validity.seeded)"
  out=$(s6 s6-tmpl-unconsumed '.template.signalsConsumed = 0 | (.seeded.sets, .final.sets) |= map_values(.signalsConsumed = 0)')
  expect "s6: a template without a consumed signal: validity.seeded" INVALID "$(st "$out" validity.seeded)"
  out=$(s6 s6-young-events '.final.sets.young.events -= 1')
  expect "s6: one event of a retained young instance deleted: correctness.retentionExact" FAIL "$(st "$out" correctness.retentionExact)"
  out=$(s6 s6-jforeign-timers '.final.sets.jforeign.timers -= 1')
  expect "s6: one timer of a retained foreign instance deleted: correctness.retentionExact" FAIL "$(st "$out" correctness.retentionExact)"
  out=$(s6 s6-rc-children '.final.sets.rc.events -= 1')
  expect "s6: one event of the rc remainder deleted: correctness.retentionExact" FAIL "$(st "$out" correctness.retentionExact)"
  out=$(s6 s6-rc-signal '.final.sets.rc.signalsConsumed -= 1')
  expect "s6: one consumed signal of the rc remainder deleted: correctness.retentionExact" FAIL "$(st "$out" correctness.retentionExact)"
  out=$(s6 s6-no-during '.foreground.during = {n: 0, completed: 0, p50Ms: null, p95Ms: null}')
  expect "s6: no foreground probe completed during the sweep: correctness.retentionExact" FAIL "$(st "$out" correctness.retentionExact)"

  out=$(s6 s6-no-xforeign 'del(.plan.sets.xforeign)')
  expect "s6: xforeign read missing: advisory.foreignRetention" WARN "$(st "$out" advisory.foreignRetention)"
  expect "s6: xforeign read missing: no evaluator ERROR" yes \
    "$(jq -r '.verdict' <<<"$out" | grep -qv -e ERROR -e CRASH && echo yes || echo no)"
fi
# The WP8 after of record (main 40aa3c21): a regression pin, unchanged -> PASS.
if [[ -n "$s6after" ]]; then
  source_is "$s6after" s6-retention
  out=$(eval_case "$s6after" s6-retention s6-after-real '.')
  expect "s6: the real after run: verdict" PASS "$(jq -r .verdict <<<"$out")"
  for v in gate.retentionPaced gate.janitorServiceScoped gate.janitorBatched correctness.retentionExact correctness.janitorExact; do
    expect "s6: the real after run: ${v}" PASS "$(st "$out" "$v")"
  done
  expect "s6: the real after run: the foreign retention set kept" '{"seeded":1000,"purged":0,"kept":1000}' \
    "$(field s6-after-real advisory.foreignRetention '.value')"
fi

if ((failures > 0)); then
  echo "selftest-wp12: ${failures} case(s) failed"
  exit 1
fi
echo "selftest-wp12: all cases hold"
