#!/usr/bin/env bash
# Evaluates one run: perf/lib/evaluate.sh <resultsDir> <scenario> [baseline.json]
#
# Runs perf/eval/<scenario>.jq (which includes perf/eval/common.jq) over the
# run's identity.json, measurements.json, prometheus.json (if present) and
# the baseline (if the file exists), writes result.json and summary.md into <resultsDir>, and prints
# the summary. The verdict is result.json's .verdict.
set -Eeuo pipefail

PERF_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
results=${1:?resultsDir}
scenario=${2:?scenario}
baseline=${3:-}

args=(--slurpfile identity "${results}/identity.json" --slurpfile measurements "${results}/measurements.json")
# $baseline is the file's JSON documents (none when it is absent); a file that
# exists but cannot be read as JSON is reported through $baselineError (the
# scenario's baseline gate is then ERROR, never "absent"). The message names
# no path (result.json is committed as evidence). A key that appears twice in
# the file takes its LAST value (jq's rule).
baseline_error=null
if [[ -n "$baseline" && -f "$baseline" ]]; then
  if ! grep -q '[^[:space:]]' "$baseline"; then
    baseline_error='"is empty"'
    args+=(--argjson baseline '[]')
  elif ! jq -s . "$baseline" >/dev/null 2>&1; then
    baseline_error='"is not valid JSON"'
    args+=(--argjson baseline '[]')
  else
    args+=(--slurpfile baseline "$baseline")
  fi
else
  args+=(--argjson baseline '[]')
fi
args+=(--argjson baselineError "$baseline_error")
# The run's Prometheus range data (written by run.sh before evaluation), for
# scenarios that use it (s1-throughput: the hold-window overhead basis).
if [[ -s "${results}/prometheus.json" ]]; then
  args+=(--slurpfile prometheus "${results}/prometheus.json")
else
  args+=(--argjson prometheus '[]')
fi

jq -n -L "${PERF_DIR}/eval" "${args[@]}" -f "${PERF_DIR}/eval/${scenario}.jq" >"${results}/result.json.tmp"
mv "${results}/result.json.tmp" "${results}/result.json"

jq -r '
  def cell: if . == null then "" elif type == "string" then . else tojson end
            | gsub("\\|"; "\\|") | gsub("\n"; " ");
  .identity as $i
  | "# Maestro perf: \(.scenario) (\(.profile)): \(.verdict)",
    "",
    "| | |",
    "|---|---|",
    "| run | `\($i.runId)`, started \($i.startedAtUtc) |",
    "| engine commit | `\($i.repo.engineCommit)` |",
    "| harness commit | `\($i.repo.harnessCommit)` |",
    "| HEAD / dirty | `\($i.repo.head)` / \($i.repo.dirty) |",
    "| engine ref label | \($i.repo.engineRefLabel | cell) |",
    "| jar | stamped `\($i.build.bakedCommit)`, nodes expected to report `\($i.build.expectedCommit)` |",
    "| replicas | requested \($i.config.replicas), answered \($i.nodes // [] | length): \($i.nodes // [] | map(.hostname) | join(", ")) |",
    "| adminEventsRequested | \($i.config.adminEventsRequested) |",
    "| adminEventsEffective | \($i.effective.adminEventsEffective | cell) (topic end offset \($i.effective.adminTopicEndOffset | cell)) |",
    "| Hikari maximum pool size | \($i.effective.hikariMaximumPoolSize | cell) |",
    "| worker default concurrency | \($i.effective.workerDefaultConcurrency | cell) workflows / \($i.effective.workerDefaultActivityConcurrency | cell) activities |",
    "| node container | heap \($i.config.nodeHeap), cpus \($i.config.nodeCpus), mem \($i.config.nodeMem) |",
    "| container limits | \($i.containers // [] | map("\(.service): cpus \(.cpus), mem \(.memoryBytes)") | unique | join("; ")) |",
    "| postgres | \($i.postgres.settings // {} | "fsync \(.fsync), synchronous_commit \(.synchronous_commit), wal_level \(.wal_level), shared_buffers \(.shared_buffers), max_connections \(.max_connections)") |",
    (.measurements.statements // null | if . then
      "| statements / workflow | gross \(.statementsPerWorkflowGross), net \(.statementsPerWorkflowNet) ± \(.netTolerancePerWorkflow) (top-level: gross \(.statementsPerWorkflowGrossTopLevel), net \(.statementsPerWorkflowNetTopLevel)); background \(.backgroundRatePerSec)/s over \(.backgroundWindowSecs) s; load window \(.loadWindowSecs) s. Gross includes background engine statements. |"
      else empty end),
    "| host | \($i.host.cpus) CPUs, \($i.host.memoryMb) MiB; Docker \($i.host.dockerServer) (\($i.host.dockerMemoryMb) MiB), compose \($i.host.dockerCompose) |",
    (if ($i.notes | length) > 0 then "| notes | \($i.notes | join("; ") | cell) |" else empty end),
    (.measurements.levels // null | if type == "array" and length > 0 then
      "",
      "## Levels",
      "",
      "| offered /s | arrivals /s | completions /s (hold only) | sustained | backlog at end | p50 / p95 ms (rows) | workflow slots max / avg | slot hold ms (engine overhead) | pool pending max / active max | node CPU avg (process / system) | net stmts / wf |",
      "|---|---|---|---|---|---|---|---|---|---|---|",
      (.[] | "| \(.offeredPerSec) | \(.arrivalPerSec | cell) | \(.completionsInWindowPerSec | cell) (\(.completionsInHoldPerSec // "n/a" | cell)) | \(.sustained) | \(.backlogAtEnd | cell) | \(.p50Ms | cell) / \(.p95Ms | cell) | \(.slotsUsed.workflowMax // "n/a" | cell) / \(.slotsUsed.workflowAvg // "n/a" | if type == "number" then (. * 10 | round / 10) else . end | cell) | \(.slotHoldMs // "n/a" | cell) (\(.engineOverheadMs // "n/a" | cell)) | \(.hikari.pendingMax // "n/a" | cell) / \(.hikari.activeMax // "n/a" | cell) | \(.nodeCpu.processAvg // "n/a" | if type == "number" then (. * 100 | round | tostring) + " %" else . end) / \(.nodeCpu.systemAvg // "n/a" | if type == "number" then (. * 100 | round | tostring) + " %" else . end) | \(.statements.net | cell) |")
      else empty end),
    (.measurements.plateauTable // null | if type == "array" and length > 0 then
      "",
      "## Plateaus",
      "",
      "| parked P | time to park s | window s | stmts / parked / min (net) | per 30 s (net) | gross / parked / min | recheck reads / parked / interval | renewal stmts / pass | recovery pages / pass | heldKeys / pass | other calls | heap avg MiB | live threads max | workflow slots max | probe p50 / p95 ms |",
      "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|",
      (.[] | "| \(.P) | \(.timeToParkSecs | cell) | \(.windowSecs | cell) | \(.perParkedCallsPerMin | cell) | \(.perParkedCallsPer30s | cell) | \(.grossPerParkedPerMin | cell) | \(.recheckReadsPerParkedPerInterval | cell) | \(.renewalStatementsPerPass | cell) | \(.recoveryPagesPerPass | cell) | \(.heldKeysPerPass | cell) | \(.otherCalls | cell) | \(.heapUsedAvgMiB | cell) | \(.liveThreadsMax | cell) | \(.workflowSlotsMax | cell) | \(.probeP50Ms | cell) / \(.probeP95Ms | cell) |")
      else empty end),
    "",
    "## Checks",
    "",
    "| id | class | status | value | limit | detail |",
    "|---|---|---|---|---|---|",
    (.checks[] | "| \(.id) | \(.class) | **\(.status)** | \(.value | cell) | \(.limit | cell) | \(.detail | cell) |"),
    "",
    "Verdict: **\(.verdict)**"
' "${results}/result.json" >"${results}/summary.md"

cat "${results}/summary.md"
