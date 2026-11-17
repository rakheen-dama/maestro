#!/usr/bin/env bash
# Evaluator self-test on committed evidence only: perf/eval/selftest-evidence.sh
#
# perf/eval/selftest.sh needs three PASSING runs' identity.json and
# measurements.json (and the S1 run's prometheus.json, for the hold-window
# overhead cases). perf/results/ is git-ignored, so in CI (or any fresh
# clone) this rebuilds those three directories from perf/.evidence/wp11/:
# <runId>.result.json (identity, measurements) and, where committed,
# <runId>.prometheus.json. Then perf/eval/selftest-wp12.sh on the WP12
# scenario sources rebuilt the same way from perf/.evidence/wp12/. No Docker.
# Exits non-zero when either self-test has a failing case.
set -Eeuo pipefail

PERF_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
EVIDENCE="${PERF_DIR}/.evidence/wp11"
SMOKE_RUN=r20261116t221546z # smoke, 1 replica, PASS
S1_RUN=r20261112t223114z    # S1 local, quiet, PASS (with prometheus.json)
S3_RUN=r20261116t223427z    # S3 local, 1 replica, signal mode, PASS
EVIDENCE12="${PERF_DIR}/.evidence/wp12"
S2_RUN=r20261117t115610z    # S2 local, the WP6 before (main 5b170009 without WP6)
S5_AFTER_RUN=r20261117t153355z  # S5 local, the WP4 after (main bd583f06), PASS
S5_BEFORE_RUN=r20261117t155013z # S5 local, the WP4 before (bdcc074d), FAIL
S4_AFTER_RUN=r20261117t171420z  # S4 local, 2 replicas, the WP2 after, per-level re-take (main 40aa3c21)
S4_BEFORE_RUN=r20261117t171940z # S4 local, 2 replicas, the WP2 before, per-level re-take (0c710a1e)
S6_BEFORE_RUN=r20261117t165803z # S6 local, the WP8 before, re-taken on the review-r8 harness (main d1600bf1, without WP8)
S6_AFTER_RUN=r20261117t170538z  # S6 local, the WP8 after of record, re-taken on the review-r8 harness (main 40aa3c21)
S2_PREVIEW_RUN=r20261117t122039z # S2 local, the WP6 preview (fix/recovery-capacity 25e84962), PASS
S2_AFTER_RUN=r20261117t162103z   # S2 local, the WP6 after of record (main d1600bf1), PASS
work=$(mktemp -d "${TMPDIR:-/tmp}/perf-selftest-evidence.XXXXXX")
trap 'rm -rf "$work"' EXIT
for run in "$SMOKE_RUN" "$S1_RUN" "$S3_RUN"; do
  mkdir "${work}/${run}"
  jq .identity "${EVIDENCE}/${run}.result.json" >"${work}/${run}/identity.json"
  jq .measurements "${EVIDENCE}/${run}.result.json" >"${work}/${run}/measurements.json"
  if [[ -f "${EVIDENCE}/${run}.prometheus.json" ]]; then
    cp "${EVIDENCE}/${run}.prometheus.json" "${work}/${run}/prometheus.json"
  fi
done
rc=0
bash "${PERF_DIR}/eval/selftest.sh" "${work}/${SMOKE_RUN}" "${work}/${S1_RUN}" "${work}/${S3_RUN}" || rc=1
for run in "$S2_RUN" "$S5_AFTER_RUN" "$S5_BEFORE_RUN" "$S4_AFTER_RUN" "$S4_BEFORE_RUN" "$S6_BEFORE_RUN" "$S6_AFTER_RUN" "$S2_PREVIEW_RUN" "$S2_AFTER_RUN"; do
  mkdir "${work}/${run}"
  jq .identity "${EVIDENCE12}/${run}.result.json" >"${work}/${run}/identity.json"
  jq .measurements "${EVIDENCE12}/${run}.result.json" >"${work}/${run}/measurements.json"
done
bash "${PERF_DIR}/eval/selftest-wp12.sh" "${work}/${S2_RUN}" "${work}/${S5_AFTER_RUN}" "${work}/${S5_BEFORE_RUN}" \
  "${work}/${S4_AFTER_RUN}" "${work}/${S4_BEFORE_RUN}" "${work}/${S6_BEFORE_RUN}" \
  "${work}/${S6_AFTER_RUN}" "${work}/${S2_PREVIEW_RUN}" "${work}/${S2_AFTER_RUN}" || rc=1
# Superseded WP12 runs stay committed as run; their verdict under today's
# evaluators is pinned too, so a change that silently flips one shows up:
# S4's first pair (before the per-level latency) and S6's first pair (before
# the sampler measured its span gap: INVALID, validity.samplerResolution).
superseded=(
  "r20261117t155519z PASS"    # S4 after, first take
  "r20261117t160028z FAIL"    # S4 before, first take
  "r20261117t161125z INVALID" # S6 before, first take
  "r20261117t163846z INVALID" # S6 after, first take
)
for entry in "${superseded[@]}"; do
  run=${entry% *} want=${entry#* }
  dir="${work}/superseded-${run}"
  mkdir "$dir"
  jq .identity "${EVIDENCE12}/${run}.result.json" >"${dir}/identity.json"
  jq .measurements "${EVIDENCE12}/${run}.result.json" >"${dir}/measurements.json"
  got=$(bash "${PERF_DIR}/lib/evaluate.sh" "$dir" "$(jq -r .scenario "${dir}/identity.json")" "" >/dev/null 2>&1 \
    && jq -r .verdict "${dir}/result.json" || echo CRASH)
  if [[ "$got" == "$want" ]]; then
    printf 'ok    %-70s %s\n' "superseded ${run}: verdict" "$got"
  else
    printf 'FAIL  %-70s expected %s, got %s\n' "superseded ${run}: verdict" "$want" "$got"
    rc=1
  fi
done
exit "$rc"
