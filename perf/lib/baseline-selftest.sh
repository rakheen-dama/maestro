#!/usr/bin/env bash
# Baseline-record self-test: perf/lib/baseline-selftest.sh
#
# Exercises record_baseline (perf/lib/baseline.sh), the step perf/run.sh
# --record-baseline runs after a scenario, on canned runs: the identity and
# measurements of committed evidence files (perf/.evidence/wp11/), doctored one
# fault at a time. No Docker, no network, no local results needed.
#
# The case it exists for: an intended engine improvement (S1 from 36 to 26
# net statements per workflow) FAILs the gate against the old baseline, so
# recording must judge the run WITHOUT the old baseline (validity and
# correctness must still pass), then replace it and print both side by side.
# Exit 0 when every case holds, 1 otherwise.
# SC2016: the single-quoted '$x' strings are jq filters, expanded by jq.
# shellcheck disable=SC2016
set -Eeuo pipefail

PERF_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
EVIDENCE="${PERF_DIR}/.evidence/wp11"
S1_RUN=r20261116t232948z # S1 local, PASS, 26 net statements per workflow
S3_RUN=r20261116t233507z # S3 local, PASS, 6.0161 per parked run per minute at P = 2000
SMOKE_RUN=r20261116t221546z
work=$(mktemp -d "${TMPDIR:-/tmp}/perf-baseline-selftest.XXXXXX")
trap 'rm -rf "$work"' EXIT
failures=0
export RESULTS="" # log() writes to stderr only
# shellcheck source=lib/common.sh
source "${PERF_DIR}/lib/common.sh"
# shellcheck source=lib/baseline.sh
source "${PERF_DIR}/lib/baseline.sh"

expect() {
  local name=$1 expected=$2 actual=$3
  if [[ "$actual" == "$expected" ]]; then
    printf 'ok    %-66s %s\n' "$name" "$actual"
  else
    printf 'FAIL  %-66s expected %s, got %s\n' "$name" "$expected" "$actual"
    failures=$((failures + 1))
  fi
}

# canned <case> <runId> [<jq filter on measurements>]: a results directory
# holding that run's identity.json and (doctored) measurements.json.
canned() {
  local dir="${work}/$1"
  mkdir -p "$dir"
  jq .identity "${EVIDENCE}/$2.result.json" >"${dir}/identity.json"
  jq ".measurements | ${3:-.}" "${EVIDENCE}/$2.result.json" >"${dir}/measurements.json"
  printf '%s' "$dir"
}
# record <case dir> <scenario> <baseline path>: runs record_baseline in a
# subshell (as run.sh would: set -Eeuo pipefail), prints "<rc>" and keeps its
# stdout+stderr in <case dir>/record.out.
record() {
  local rc=0
  (record_baseline "$1" "$2" "$3") >"$1/record.out" 2>&1 || rc=$?
  printf '%s' "$rc"
}
has() { grep -qE -- "$2" "$1/record.out" && echo yes || echo no; }
sum() { if [[ -f "$1" ]]; then shasum -a 256 "$1" | awk '{print $1}'; else echo absent; fi; }

old36() { jq -c '.statementsPerWorkflowNet = 36 | .runId = "r20261112t223114z" | .engineCommit = "f6518d0000000000000000000000000000000000"' \
  "${PERF_DIR}/baselines/s1-throughput.json"; }

# 1. An intended improvement: the old baseline says 36, the run measured 26.
dir=$(canned improve "$S1_RUN")
base="${work}/improve.baseline.json"
old36 >"$base"
bash "${PERF_DIR}/lib/evaluate.sh" "$dir" s1-throughput "$base" >/dev/null
expect "improvement against the old baseline: the gate FAILs (why recording must not use it)" FAIL \
  "$(jq -r '.checks[] | select(.id == "gate.statementsPerWorkflow") | .status' "${dir}/result.json")"
expect "improvement: record_baseline succeeds" 0 "$(record "$dir" s1-throughput "$base")"
expect "improvement: new baseline figure" 26 "$(jq .statementsPerWorkflowNet "$base")"
expect "improvement: new baseline's source run" "\"${S1_RUN}\"" "$(jq .runId "$base")"
expect "improvement: new baseline keeps schema 1 and the scenario" '[1,"s1-throughput"]' "$(jq -c '[.schema, .scenario]' "$base")"
expect "improvement: old and new figures printed side by side" yes "$(has "$dir" 'statementsPerWorkflowNet +36 +26')"
expect "improvement: old and new run ids printed side by side" yes "$(has "$dir" "runId +r20261112t223114z +${S1_RUN}")"
expect "improvement: the run was judged without the old baseline (gate NO_BASELINE)" NO_BASELINE \
  "$(jq -r '.checks[] | select(.id == "gate.statementsPerWorkflow") | .status' "${dir}/result.json")"
expect "improvement: result.json says the gate was not compared because of the record" yes \
  "$(jq -r '.identity.notes[]' "${dir}/result.json" | grep -q 'evaluated without the existing baseline' && echo yes || echo no)"
expect "improvement: no temporary file left beside the baseline" 0 "$(find "$work" -maxdepth 1 -name 'improve.baseline.json.*' | wc -l | tr -d ' ')"
bash "${PERF_DIR}/lib/evaluate.sh" "$dir" s1-throughput "$base" >/dev/null
expect "improvement: the same run against the new baseline: gate PASS" PASS \
  "$(jq -r '.checks[] | select(.id == "gate.statementsPerWorkflow") | .status' "${dir}/result.json")"

# 2. Validity must still pass: dropped iterations refuse, the old file is untouched.
dir=$(canned dropped "$S1_RUN" '(.k6[] | select(.phase == "s1") | .droppedIterations) = 12')
base="${work}/dropped.baseline.json"
old36 >"$base"
before=$(sum "$base")
expect "INVALID run: record_baseline refuses" 1 "$(record "$dir" s1-throughput "$base")"
expect "INVALID run: old baseline unchanged" "$before" "$(sum "$base")"
expect "INVALID run: says why" yes "$(has "$dir" 'refused: verdict INVALID')"

# 3. Correctness must still pass: refused starts.
dir=$(canned rejected "$S1_RUN" '(.k6[] | select(.phase == "s1")) |= (.checksPassed -= 5 | .checksFailed = 5)
  | .accepted -= 5 | .runStatus.total -= 5 | .runStatus.byStatus.COMPLETED -= 5')
base="${work}/rejected.baseline.json"
old36 >"$base"
before=$(sum "$base")
expect "FAIL run (refused starts): record_baseline refuses" 1 "$(record "$dir" s1-throughput "$base")"
expect "FAIL run: old baseline unchanged" "$before" "$(sum "$base")"
expect "FAIL run: says why" yes "$(has "$dir" 'refused: verdict FAIL')"

# 4. No old baseline: recorded, the old column says absent.
dir=$(canned fresh "$S1_RUN")
base="${work}/fresh.baseline.json"
expect "no old baseline: record_baseline succeeds" 0 "$(record "$dir" s1-throughput "$base")"
expect "no old baseline: figure written" 26 "$(jq .statementsPerWorkflowNet "$base")"
expect "no old baseline: printed as absent" yes "$(has "$dir" 'statementsPerWorkflowNet +\(absent\) +26')"

# 5. An unreadable old baseline is replaced (it is what a re-record repairs).
dir=$(canned garbage "$S1_RUN")
base="${work}/garbage.baseline.json"
printf '{"schema": 1,' >"$base"
expect "unparseable old baseline: record_baseline succeeds" 0 "$(record "$dir" s1-throughput "$base")"
expect "unparseable old baseline: replaced" 26 "$(jq .statementsPerWorkflowNet "$base")"
expect "unparseable old baseline: says so" yes "$(has "$dir" 'old baseline .*not valid JSON')"

# 6. Scenario 3: an old baseline at another figure is replaced, with lockTtl.
dir=$(canned s3 "$S3_RUN")
base="${work}/s3.baseline.json"
jq -c '.perParkedCallsPerMin = 9.1' "${PERF_DIR}/baselines/s3-parked.json" >"$base"
expect "s3 re-record: record_baseline succeeds" 0 "$(record "$dir" s3-parked "$base")"
expect "s3 re-record: figure, lockTtl, plateaus" '[6.0161,"PT30S",[1000,2000]]' "$(jq -c '[.perParkedCallsPerMin, .lockTtl, .plateaus]' "$base")"
expect "s3 re-record: old and new figures side by side" yes "$(has "$dir" 'perParkedCallsPerMin +9.1 +6.0161')"

# 7. A scenario without a baseline figure (the smoke) is refused.
dir=$(canned smoke "$SMOKE_RUN")
base="${work}/smoke.baseline.json"
expect "smoke: record_baseline refuses (no gate uses a baseline)" 1 "$(record "$dir" smoke "$base")"
expect "smoke: nothing written" absent "$(sum "$base")"

# 7b. Re-recording with other settings than the old baseline's (the one the
# weekly CI gates against) is refused unless PERF_BASELINE_ALLOW_SETTINGS_CHANGE=1.
settings_case() { # <case> <scenario> <runId> <old baseline jq edit> <setting name>
  local dir base before rc
  dir=$(canned "$1" "$3")
  base="${work}/$1.baseline.json"
  jq -c "$4" "${PERF_DIR}/baselines/$2.json" >"$base"
  before=$(sum "$base")
  expect "$1: other ${5} than the old baseline: record_baseline refuses" 1 "$(record "$dir" "$2" "$base")"
  expect "$1: old baseline byte-identical" "$before" "$(sum "$base")"
  expect "$1: the refusal names ${5} and the override" yes "$(has "$dir" "${5}.*PERF_BASELINE_ALLOW_SETTINGS_CHANGE=1")"
  expect "$1: no temporary file beside it" 0 "$(find "$work" -maxdepth 1 -name "$1.baseline.json.*" | wc -l | tr -d ' ')"
}
settings_case set-profile s1-throughput "$S1_RUN" '.profile = "full"' profile
settings_case set-replicas s1-throughput "$S1_RUN" '.replicas = 2' replicas
settings_case set-admin s1-throughput "$S1_RUN" '.adminEventsEnabled = true' adminEventsEnabled
settings_case set-shape s1-throughput "$S1_RUN" '.workflowShape.activities = 3' workflowShape
settings_case set-ttl s3-parked "$S3_RUN" '.lockTtl = "PT60S"' lockTtl
settings_case set-plateaus s3-parked "$S3_RUN" '.plateaus = [1000, 5000, 10000] | .Pmax = 10000' plateaus
settings_case set-mode s3-parked "$S3_RUN" '.parkMode = "sleep"' parkMode
dir=$(canned set-override "$S1_RUN")
base="${work}/set-override.baseline.json"
jq -c '.profile = "full"' "${PERF_DIR}/baselines/s1-throughput.json" >"$base"
rc=0
(PERF_BASELINE_ALLOW_SETTINGS_CHANGE=1 record_baseline "$dir" s1-throughput "$base") >"${dir}/record.out" 2>&1 || rc=$?
expect "override PERF_BASELINE_ALLOW_SETTINGS_CHANGE=1: recorded" "0 \"local\"" "${rc} $(jq .profile "$base")"
expect "override: the settings change is printed" yes "$(has "$dir" 'profile +full +local')"
expect "override: the log says the override was used" yes "$(has "$dir" 'PERF_BASELINE_ALLOW_SETTINGS_CHANGE=1: recording with other settings')"
dir=$(canned set-override-bad "$S1_RUN")
base="${work}/set-override-bad.baseline.json"
jq -c '.profile = "full"' "${PERF_DIR}/baselines/s1-throughput.json" >"$base"
rc=0
(PERF_BASELINE_ALLOW_SETTINGS_CHANGE=yes record_baseline "$dir" s1-throughput "$base") >"${dir}/record.out" 2>&1 || rc=$?
expect "override set to something other than 1: still refused" 1 "$rc"

# 8. A jq that fails while writing the new baseline (or the identity note)
# leaves the old baseline byte-identical and no temporary file. run.sh calls
# record_baseline through `||`, which disables set -e inside it, so every
# write must check its own status. A wrapper jq on PATH fails only on the
# filter named in FAIL_ON.
realjq=$(command -v jq)
mkdir -p "${work}/fakebin"
cat >"${work}/fakebin/jq" <<EOF
#!/usr/bin/env bash
for a in "\$@"; do [[ "\$a" != *"\${FAIL_ON}"* ]] || exit 5; done
exec "${realjq}" "\$@"
EOF
chmod +x "${work}/fakebin/jq"
for target in 'harnessCommit: .identity.repo.harnessCommit' '.notes += [$n]'; do
  case $target in harness*) name="write" ;; *) name="note" ;; esac
  dir=$(canned "jqfail-${name}" "$S1_RUN")
  base="${work}/jqfail-${name}.baseline.json"
  old36 >"$base"
  before=$(sum "$base")
  idBefore=$(sum "${dir}/identity.json")
  rc=0
  (PATH="${work}/fakebin:${PATH}" FAIL_ON="$target" record_baseline "$dir" s1-throughput "$base") \
    >"${dir}/record.out" 2>&1 || rc=$?
  expect "jq fails on the ${name}: record_baseline returns non-zero" yes "$( ((rc != 0)) && echo yes || echo no)"
  expect "jq fails on the ${name}: old baseline byte-identical" "$before" "$(sum "$base")"
  expect "jq fails on the ${name}: no temporary file beside it" 0 "$(find "$work" -maxdepth 1 -name "jqfail-${name}.baseline.json.*" | wc -l | tr -d ' ')"
  [[ "$name" != note ]] || expect "jq fails on the note: identity.json byte-identical, no temp" "${idBefore} 0" \
    "$(sum "${dir}/identity.json") $(find "$dir" -name 'identity.json.*' | wc -l | tr -d ' ')"
done

# 9. The committed baselines are untouched by all of the above.
expect "committed baselines untouched" "" "$(git -C "$PERF_DIR" status --porcelain -- baselines)"

if ((failures > 0)); then
  echo "baseline-selftest: ${failures} case(s) failed"
  exit 1
fi
echo "baseline-selftest: all cases hold"
