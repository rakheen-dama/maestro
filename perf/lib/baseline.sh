#!/usr/bin/env bash
# Recording a baseline (perf/run.sh --record-baseline). Sourced, never run;
# expects PERF_DIR and log() (lib/common.sh). Self-test:
# perf/lib/baseline-selftest.sh (no Docker).
# shellcheck shell=bash
# SC2016: the single-quoted '$x' strings are jq filters, expanded by jq.
# shellcheck disable=SC2016

# record_baseline <resultsDir> <scenario> <baseline.json>
#
# Judges the run WITHOUT the existing baseline, so that an intended change of
# the figure (an engine improvement, which the symmetric gate FAILs against
# the old baseline) can be recorded at all, while validity and correctness
# must still PASS: the verdict without a baseline must be PASS (its baseline
# gates read NO_BASELINE, and every absolute gate still judges). Then writes
# the new baseline (atomically, through a temporary file beside it) from
# result.json's identity and measurements.baselineCandidate, and prints the
# old and new values side by side. The old file may be absent or unreadable:
# re-recording replaces it either way. A scenario that judges no gate
# against a baseline (the smoke) is refused. Returns 1, writing nothing, when
# it refuses. Overwrites result.json and summary.md with the no-baseline
# evaluation, and says so in identity.notes.
record_baseline() {
  local results=$1 scenario=$2 baseline=$3 verdict tmp old
  identity_set_in "$results" '.notes += [$n]' --arg n "record-baseline: evaluated without the existing baseline perf/baselines/${scenario}.json (validity and correctness must PASS to record; baseline gates read NO_BASELINE); the old and new values: baseline-record.txt"
  bash "${PERF_DIR}/lib/evaluate.sh" "$results" "$scenario" "" >/dev/null
  verdict=$(jq -r .verdict "${results}/result.json")
  if [[ "$verdict" != PASS ]]; then
    log "--record-baseline refused: verdict ${verdict} evaluated without the existing baseline (validity and correctness must PASS); ${baseline##*/} unchanged"
    return 1
  fi
  # A scenario uses a baseline when, judged without one, some gate says
  # NO_BASELINE; the smoke has none (its gate is an absolute range).
  if ! jq -e '(.measurements.baselineCandidate | type == "object" and length > 0)
              and any(.checks[]; .class == "gate" and .status == "NO_BASELINE")' "${results}/result.json" >/dev/null; then
    log "--record-baseline refused: scenario ${scenario} judges no gate against a baseline (or has no measurements.baselineCandidate); nothing written"
    return 1
  fi
  tmp=$(mktemp "${baseline}.XXXXXX")
  jq '{schema: 1, scenario, profile, runId: .identity.runId,
       recordedAtUtc: .identity.startedAtUtc,
       engineCommit: .identity.repo.engineCommit, harnessCommit: .identity.repo.harnessCommit,
       replicas: .identity.config.replicas,
       adminEventsEnabled: .identity.config.adminEventsRequested} + .measurements.baselineCandidate' \
    "${results}/result.json" >"$tmp"
  if [[ ! -f "$baseline" ]]; then
    old='{"__state": "absent"}'
  elif old=$(jq -cs 'if length == 1 and (.[0] | type) == "object" then .[0] else error("x") end' "$baseline" 2>/dev/null); then
    :
  else
    old='{"__state": "unreadable: not valid JSON or not one object"}'
  fi
  log "baseline ${baseline##*/}: old and new (key, old, new; * = changed):"
  jq -r -n --argjson old "$old" --slurpfile new "$tmp" '
    def cell: if . == null then "(absent)" elif type == "string" then . else tojson end;
    $new[0] as $n
    | (if $old.__state then "(\($old.__state | split(":")[0]))" else null end) as $whole
    | (if $old.__state and ($old.__state | startswith("unreadable")) then "old baseline \($old.__state)" else empty end),
      (($n | keys_unsorted) as $nk | ($nk + (($old | del(.__state) | keys_unsorted) - $nk))[] as $k
       | ($whole // ($old[$k] | cell)) as $o
       | ($n[$k] | cell) as $v
       | "  \(if $o != $v then "*" else " " end) \($k)  \($o)  \($v)")' | tee -a "${results}/baseline-record.txt" >&2
  mv "$tmp" "$baseline"
  log "baseline recorded: ${baseline##*/} from ${results##*/} (verdict PASS without the old baseline); review the diff and commit it"
}

# identity_set on an explicit results directory (record_baseline is also
# called by its self-test, outside a run).
identity_set_in() {
  local dir=$1 filter=$2
  shift 2
  jq "$@" "$filter" "${dir}/identity.json" >"${dir}/identity.json.tmp"
  mv "${dir}/identity.json.tmp" "${dir}/identity.json"
}
