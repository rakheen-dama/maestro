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
mkdir "${work}/${S2_RUN}"
jq .identity "${EVIDENCE12}/${S2_RUN}.result.json" >"${work}/${S2_RUN}/identity.json"
jq .measurements "${EVIDENCE12}/${S2_RUN}.result.json" >"${work}/${S2_RUN}/measurements.json"
bash "${PERF_DIR}/eval/selftest-wp12.sh" "${work}/${S2_RUN}" || rc=1
exit "$rc"
