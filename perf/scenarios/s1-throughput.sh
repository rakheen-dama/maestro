#!/usr/bin/env bash
# Scenario 1 (s1-throughput): the throughput ceiling of ONE node at Maestro's
# true defaults (20 workflow runs / 20 live activities per task queue per
# node, Boot's default Hikari pool of 10). Sourced by perf/run.sh, which
# provides RUN_ID, RESULTS, REPLICAS, PROFILE and lib/common.sh. Writes
# ${RESULTS}/measurements.json; perf/eval/s1-throughput.jq judges it.
#
# Workload: chain workflows of 5 activities x 50 ms, started at a stepped
# open-model arrival rate (perf/k6/start-ramp.js, ramping-arrival-rate; its
# profile table is the single source of levels, ramp/hold and VUs, read back
# here with `k6 inspect`):
#   local  levels 5, 20, 40, 80 starts/s, ramp 5 s / hold 40 s, VUs 400 / 400
#          (pre-allocated / max), drain timeout 300 s (load 180 s; about
#          6 min end to end)
#   full   levels 5, 10, 20, 40, 60, 80, 120 starts/s, ramp 10 s / hold 120 s,
#          VUs 600 / 600, drain timeout 1200 s (load 910 s; about 20 min end
#          to end)
#   (VU sizing, and why all are pre-allocated: perf/k6/start-ramp.js. The
#   budget is a 5 s start-endpoint stall at the top level, only about
#   1.6-1.7 x the longest stalls observed there: 2.88 s and 3.2 s.)
#   PERF_K6_VUS / PERF_K6_MAX_VUS override the VUs (the generator-saturation
#   test runs 1 / 1 and must come out INVALID, validity.droppedIterations).
#
# Order: warm-up (outside every window; as many concurrent starts as the
# load's maxVUs, and at least 2 x the cluster's workflow slots, so the load
# never drives the node at a concurrency it has not seen) -> idle window (the
# background rate) -> pg_stat_statements reset -> k6 ramp, with a sampler
# taking a cumulative pgss-window + s1-progress snapshot every 5 s -> drain
# (every s1 row terminal, or the drain timeout) -> the load window (one
# pgss-window over load + drain: the gate's figure) -> per-level SQL and
# Prometheus. Per-level statement figures come from the samples bracketing
# each level's window; see perf/eval/s1-throughput.jq for how levels are
# judged ("sustains", the ceiling, the ramp and maximum biases), for
# contention, and for the statement gate's relative allowance and its
# measured justification: net statements per workflow 26.00 in four cold
# local runs of today's engine (r20261116t221747z, r20261116t222856z,
# admin events off; r20261116t222331z, r20261116t225610z, on; all on a
# contended host), 36.00 in four quiet runs before the per-activity lock was
# removed (r20261112t210341z, r20261112t213818z, r20261112t222511z,
# r20261112t223114z); allowance 0.02. Every cited run's result.json is
# committed under perf/.evidence/wp11/.
#
# What this scenario measures. The ceiling = busy slots / (activity time +
# engine overhead per workflow), and the activity time is the sample's own
# 250 ms (5 x 50 ms). It is bounded ABOVE: 20 / 0.25 s = 80/s with zero
# overhead. Engine overhead lowers it without limit (model values): 20 ms
# -> 20 / 0.270 s = 74.1/s; 20 + 100 ms -> 20 / 0.370 s = 54.1/s. Measured
# on the four valid quiet runs over the ceiling level's hold window: all 20
# slots busy in every gauge sample (18.67-19.22 averaged over the whole
# level window, ramp included), 72.5-75.0 completions/s, so a hold of
# 267-276 ms and an engine overhead of 16.65 / 25.17 / 19.85 / 25.93 ms
# per workflow, each +/- 1.4-3.9 ms within its run
# (ceiling.engineOverheadMsPerWorkflow, .engineOverheadResolutionMs; +/- =
# 2 standard errors). Between runs on
# this shared machine differences under about 10 ms are not meaningful, and
# the delivered ceiling spread 70.6-73.1/s (2.5/s, about 9 ms of hold at
# 72/s): so the scenario detects an engine-overhead REGRESSION of roughly
# 10 ms per workflow or more here (less on a quiet dedicated host), and
# cannot show an improvement beyond 80/s. It is good for: showing that the
# 20 workflow slots bind before the Hikari pool of 10 does; pinning
# statements per workflow (the gate); and measuring engine overhead per
# workflow. It is NOT a measure of engine throughput headroom.
# Today's engine (no per-activity lock), local, one node, on a contended
# host (advisory.contention: host load 7.7-14.6 per CPU, so not quiet
# figures): ceiling 73.01 / 70.61 completions/s with admin events off
# (r20261116t221747z, r20261116t222856z), 73.16 / 73.48 on
# (r20261116t222331z, r20261116t225610z); hold-window rate 72.26-75.47/s;
# engine overhead 17.44 / 27.26 ms off, 15.41 / 15.77 ms on (+/- 1.4-10.9):
# inside the pre-merge quiet runs' spread, so neither removing the activity
# lock (2 statements of about 0.06 ms each per activity: r20261112t223114z)
# nor publishing admin events moved the ceiling detectably here.
# Past the ceiling, throughput was seen to fall 8-11 % at about 118 offered
# per second (the L120 level), 1.5 x the ceiling level's offered rate (L80)
# and about 1.6-1.7 x the measured ceiling (73.7 -> 68.0/s and 70.1 ->
# 62.3/s), with slots pinned and hold time up 9-13 % (269 -> 294 ms,
# 285 -> 321 ms). That is an observation from ONE INVALID full-profile run
# (r20261112t211806z, dropped iterations) and ONE contended one
# (r20261112t214359z), to be reproduced on a quiet host; its cause is NOT
# established (pool, node CPU and host contention are all candidates) and
# needs node CPU and GC recorded.
#
# Recorded for contention (advisory.contention): node CPU per level from
# Prometheus (process/system_cpu_usage, system_load_average_1m), the
# containers' CPU from `docker stats` every ~7 s (k6, node, postgres; only
# this compose project's containers, read-only) and the host's load average
# at the scenario's start, at the load's start and after the drain. Blind
# spots (see the jq header): uniform contention is caught only when it makes
# the first level's p50 exceed 290 ms or the host load exceed 2 per CPU;
# milder uniform contention reads quiet.
#
# Periodic sweeps: the load window of the full profile crosses the lock
# cleaner's 10-minute marks; validity.sweeps (common.jq) accounts for them
# from the intervals /perf/info reports, and the sampler's snapshots carry the
# same sweepCalls so per-level figures subtract them too.
# shellcheck shell=bash
# SC2016: the single-quoted '$x' strings are jq filters, expanded by jq.
# shellcheck disable=SC2016

S1_SAMPLE_SECS=5

# Cumulative engine statements (pgss-window, statement list dropped) and the
# s1 rows' progress at one instant, as one JSON line. s1_sample <prefix>
s1_sample() {
  cat "${PERF_DIR}/sql/pgss-window.sql" "${PERF_DIR}/sql/s1-progress.sql" \
    | dc exec -T postgres psql -U postgres -d maestro -At -v ON_ERROR_STOP=1 -v "prefix=$1" -f - \
    | jq -sc 'if length == 2 then (.[0] | del(.top)) + .[1] else error("expected 2 JSON rows, got \(length)") end'
}

# The Prometheus view of one level window: slot usage (WP11 gauge), the
# Hikari pool, and the WP11 duration timer's quantiles. A series the engine
# does not export (an engine older than WP11) is null: the evaluator reports
# it as "not available", never as a failure. s1_level_prom <wsEpoch> <weEpoch>
s1_level_prom() {
  local ws=$1 we=$2 range at
  range="$(jq -rn --argjson a "$ws" --argjson b "$we" '[($b - $a | ceil), 5] | max')s"
  at=$(jq -rn --argjson b "$we" '$b | ceil')
  local slotsW='sum(maestro_taskqueue_slots_used{kind="workflow"})'
  local slotsA='sum(maestro_taskqueue_slots_used{kind="activity"})'
  local act='sum(hikaricp_connections_active)' pend='sum(hikaricp_connections_pending)'
  local bucket="sum by (le) (increase(maestro_workflow_duration_seconds_bucket{outcome=\"completed\"}[${range}]))"
  local pcpu='max(process_cpu_usage)' scpu='max(system_cpu_usage)' sload='max(system_load_average_1m)'
  jq -nc \
    --argjson rangeSecs "${range%s}" --argjson at "$at" \
    --argjson swMax "$(prom_instant "max_over_time(${slotsW}[${range}:5s])" "$at")" \
    --argjson swAvg "$(prom_instant "avg_over_time(${slotsW}[${range}:5s])" "$at")" \
    --argjson saMax "$(prom_instant "max_over_time(${slotsA}[${range}:5s])" "$at")" \
    --argjson saAvg "$(prom_instant "avg_over_time(${slotsA}[${range}:5s])" "$at")" \
    --argjson actMax "$(prom_instant "max_over_time(${act}[${range}:5s])" "$at")" \
    --argjson actAvg "$(prom_instant "avg_over_time(${act}[${range}:5s])" "$at")" \
    --argjson pendMax "$(prom_instant "max_over_time(${pend}[${range}:5s])" "$at")" \
    --argjson pendAvg "$(prom_instant "avg_over_time(${pend}[${range}:5s])" "$at")" \
    --argjson d50 "$(prom_instant "histogram_quantile(0.5, ${bucket})" "$at")" \
    --argjson d95 "$(prom_instant "histogram_quantile(0.95, ${bucket})" "$at")" \
    --argjson pcAvg "$(prom_instant "avg_over_time(${pcpu}[${range}:5s])" "$at")" \
    --argjson pcMax "$(prom_instant "max_over_time(${pcpu}[${range}:5s])" "$at")" \
    --argjson scAvg "$(prom_instant "avg_over_time(${scpu}[${range}:5s])" "$at")" \
    --argjson scMax "$(prom_instant "max_over_time(${scpu}[${range}:5s])" "$at")" \
    --argjson slAvg "$(prom_instant "avg_over_time(${sload}[${range}:5s])" "$at")" \
    '{rangeSecs: $rangeSecs, at: $at,
      slotsUsed: {workflowMax: $swMax, workflowAvg: $swAvg, activityMax: $saMax, activityAvg: $saAvg},
      hikari: {activeMax: $actMax, activeAvg: $actAvg, pendingMax: $pendMax, pendingAvg: $pendAvg},
      durationMeter: {p50Secs: $d50, p95Secs: $d95},
      nodeCpu: {processAvg: $pcAvg, processMax: $pcMax, systemAvg: $scAvg, systemMax: $scMax,
                systemLoad1mAvg: $slAvg}}'
}

scenario_run() {
  local prefix="${RUN_ID}-s1-"
  local body='{"type":"chain","activities":5,"activityMillis":50}'
  local drain_timeout
  case "$PROFILE" in
    local) drain_timeout=300 ;;
    full) drain_timeout=1200 ;;
    *) die "s1-throughput: unknown profile ${PROFILE}" ;;
  esac
  local k6env=("PERF_PROFILE=${PROFILE}" "PERF_ID_PREFIX=${prefix}" "PERF_BODY=${body}")
  [[ -z "${PERF_K6_VUS:-}" ]] || k6env+=("PERF_K6_VUS=${PERF_K6_VUS}")
  [[ -z "${PERF_K6_MAX_VUS:-}" ]] || k6env+=("PERF_K6_MAX_VUS=${PERF_K6_MAX_VUS}")

  # The profile as k6 resolves it (levels, ramp/hold, VUs, overrides applied).
  local inspect_args=() kv plan
  for kv in "${k6env[@]}"; do inspect_args+=(-e "$kv"); done
  # k6 inspect reads script env only from its own -e flags (unlike k6 run,
  # it does not take the container's environment).
  plan=$(dc run --rm --no-deps k6 inspect "${inspect_args[@]}" /scripts/start-ramp.js </dev/null 2>>"${RESULTS}/harness.log" \
    | jq -c '
        # k6 prints durations in Go form ("10s", "2m0s", "1h0m5s").
        def gosecs: capture("^((?<h>[0-9.]+)h)?((?<m>[0-9.]+)m)?((?<s>[0-9.]+)s)?$")
                    | (.h // "0" | tonumber) * 3600 + (.m // "0" | tonumber) * 60 + (.s // "0" | tonumber);
        .scenarios.s1 | {executor, timeUnit, startRate, preAllocatedVUs, maxVUs, stages,
              levels: [.stages | to_entries[] | select(.key % 2 == 1) | .value.target],
              rampSecs: (.stages[0].duration | gosecs),
              holdSecs: (.stages[1].duration | gosecs),
              loadSecs: ([.stages[].duration | gosecs] | add)}') \
    || die "s1-throughput: k6 inspect of start-ramp.js failed"
  plan=$(jq -c --argjson d "$drain_timeout" --arg p "$PROFILE" '. + {profile: $p, drainTimeoutSecs: $d}' <<<"$plan")
  log "s1: plan $(jq -c '{levels, rampSecs, holdSecs, preAllocatedVUs, maxVUs, drainTimeoutSecs}' <<<"$plan")"

  local host_start host_load_start host_end
  host_start=$(host_load)
  log "s1: host load at start $(jq -c '{load1, load5, load15, hostCpus}' <<<"$host_start")"
  local slots warm_vus warm_count
  slots=$(jq -r '.nodes[0].workerDefaultConcurrency' "${RESULTS}/identity.json")
  warm_vus=$(jq -r '.maxVUs' <<<"$plan")
  warm_count=$(jq -rn --argjson v "$warm_vus" --argjson s "$slots" --argjson r "$REPLICAS" '[2 * $v, 2 * $s * $r] | max')
  log "s1: warm-up, ${warm_count} runs at ${warm_vus} VUs"
  local warmup background reset drain window latency foreign levels status
  warmup=$(warm_up "$warm_count" "$warm_vus" "$body")
  sleep 2
  background=$(measure_background)

  log "s1: pg_stat_statements reset"
  reset=$(psql_json pgss-reset)

  # Sampler: one cumulative snapshot every S1_SAMPLE_SECS until the stop file
  # appears. It runs as postgres, so it is never engine load.
  # It also stops when the driver's shell is gone (a harness error or an
  # interrupt exits run.sh without reaching the stop file) and after the
  # longest the scenario can legitimately take.
  local samples="${RESULTS}/s1-samples.jsonl" stop="${RESULTS}/.s1-sampler-stop" sampler deadline driver=$$
  deadline=$(($(date +%s) + $(jq '.loadSecs | ceil' <<<"$plan") + drain_timeout + 300))
  : >"$samples"
  rm -f "$stop"
  (
    set +eE
    trap - ERR EXIT INT TERM
    while [[ ! -e "$stop" ]] && kill -0 "$driver" 2>/dev/null && (($(date +%s) < deadline)); do
      s1_sample "$prefix" >>"$samples" 2>>"${RESULTS}/harness.log"
      sleep "$S1_SAMPLE_SECS"
    done
  ) &
  sampler=$!
  local cstats="${RESULTS}/s1-container-stats.jsonl" cstats_sampler
  : >"$cstats"
  (
    set +eE
    trap - ERR EXIT INT TERM
    while [[ ! -e "$stop" ]] && kill -0 "$driver" 2>/dev/null && (($(date +%s) < deadline)); do
      container_stats >>"$cstats" 2>>"${RESULTS}/harness.log"
      sleep "$S1_SAMPLE_SECS"
    done
  ) &
  cstats_sampler=$!
  host_load_start=$(host_load)
  log "s1: host load at load start $(jq -c '{load1, load5, load15}' <<<"$host_load_start")"

  k6_run s1 start-ramp.js "${k6env[@]}"
  local executed=null
  if [[ -s "${RESULTS}/k6-s1-plan.json" ]]; then
    executed=$(jq -c . "${RESULTS}/k6-s1-plan.json")
  fi
  local accepted
  accepted=$(jq -s '[.[] | select(.phase == "s1") | .checksPassed // 0] | add // 0' "${RESULTS}/k6-runs.jsonl")
  log "s1: waiting (<= ${drain_timeout} s) for ${accepted} accepted runs of ${prefix} to be terminal"
  drain=$(wait_terminal "$prefix" "$accepted" "$drain_timeout" 5)
  log "s1: $(jq -c '{byStatus: .status.byStatus, waitedSecs, timedOut}' <<<"$drain")"

  touch "$stop"
  wait "$sampler" || true
  wait "$cstats_sampler" || true
  host_end=$(host_load)
  rm -f "$stop"
  # The closing sample, taken after the drain, so the last level is bracketed.
  s1_sample "$prefix" >>"$samples" 2>>"${RESULTS}/harness.log" || log "WARN: s1 closing sample failed"

  window=$(psql_json pgss-window)
  status=$(psql_json run-status -v "prefix=${prefix}")
  latency=$(psql_json latency -v "prefix=${prefix}")
  levels=$(psql_json s1-levels -v "prefix=${prefix}" -v "ramp=$(jq '.rampSecs' <<<"$plan")")
  foreign=$(psql_json foreign-rows -v "prefix=${RUN_ID}-")
  log "s1: window $(jq -c '{windowSecs, dealloc, maestroCalls, maestroCallsTopLevel, harnessCalls, sweepCalls}' <<<"$window"), foreign rows ${foreign}"

  # Prometheus per level (one more scrape interval first, so the last
  # level's samples are in the TSDB), plus run-wide abandoned runs.
  sleep 6
  local level_prom="[]" row lp first_ws last_we abandoned
  while IFS= read -r row; do
    lp=$(s1_level_prom "$(jq -r .windowStartEpoch <<<"$row")" "$(jq -r .windowEndEpoch <<<"$row")")
    level_prom=$(jq -c --argjson l "$(jq .level <<<"$row")" --argjson p "$lp" '. + [{level: $l} + $p]' <<<"$level_prom")
  done < <(jq -c '.levels[]' <<<"$levels")
  first_ws=$(jq -r '[.levels[].windowStartEpoch] | min // empty' <<<"$levels")
  last_we=$(date +%s)
  abandoned=null
  if [[ -n "$first_ws" ]]; then
    abandoned=$(prom_instant "sum(increase(maestro_run_abandoned_total[$(jq -rn --argjson a "$first_ws" --argjson b "$last_we" '$b - $a | ceil')s]))" "$last_we")
  fi
  # The duration timer's largest finite bucket: a quantile that lands on it
  # is clipped (the true value is at least that), never a measurement.
  local top_bucket
  top_bucket=$(dc exec -T curl curl -fsS -m 30 -G http://prometheus:9090/api/v1/query \
    --data-urlencode 'query=group by (le) (maestro_workflow_duration_seconds_bucket)' </dev/null 2>>"${RESULTS}/harness.log" \
    | jq -c '[.data.result[]?.metric.le | select(. != "+Inf") | tonumber?] | max // null' 2>/dev/null || printf 'null')
  # Per-level start timing from k6's custom metrics (start-ramp.js).
  local start_by_level=null
  if [[ -s "${RESULTS}/k6-s1.json" ]]; then
    start_by_level=$(jq -c '.metrics as $m | [$m | keys[] | select(test("^s1_start_ms_L[0-9]+$"))
        | (ltrimstr("s1_start_ms_L") | tonumber) as $l
        | {level: $l, maxMs: $m[.].values.max, p95Ms: $m[.].values["p(95)"], count: ($m[.].values.count // null),
           over1s: ($m["s1_start_over_1s_L\($l)"].values.count // null)}] | sort_by(.level)' "${RESULTS}/k6-s1.json")
  fi
  log "s1: levels $(jq -c '[.levels[] | {level, arrivals, arrivalPerSec, completionsInWindowPerSec, backlogAtEnd, p95Ms}]' <<<"$levels")"

  jq -n \
    --arg prefix "$prefix" --arg runPrefix "${RUN_ID}-" --argjson body "$body" --argjson plan "$plan" \
    --argjson warmup "$warmup" --argjson background "$background" \
    --argjson reset "$reset" --argjson drain "$drain" --argjson window "$window" \
    --argjson status "$status" --argjson latency "$latency" --argjson levels "$levels" \
    --argjson levelProm "$level_prom" --argjson abandoned "$abandoned" \
    --argjson foreign "$foreign" --argjson k6 "$(k6_records)" \
    --argjson topBucket "$top_bucket" --argjson startByLevel "$start_by_level" \
    --argjson hostStart "$host_start" --argjson hostLoadStart "$host_load_start" --argjson hostEnd "$host_end" \
    --slurpfile containerStats "$cstats" \
    --argjson accepted "$accepted" --argjson executed "$executed" --slurpfile samples "$samples" --argjson sampleSecs "$S1_SAMPLE_SECS" \
    '{idPrefix: $prefix, runPrefix: $runPrefix,
      workflowShape: ($body | {type, activities, activityMillis}),
      plan: $plan, executedPlan: $executed, accepted: $accepted,
      warmup: $warmup, background: $background,
      pgssReset: $reset, k6: $k6,
      drain: {waitedSecs: $drain.waitedSecs, timedOut: $drain.timedOut, timeoutSecs: $plan.drainTimeoutSecs},
      runStatus: $status, window: $window, latency: $latency,
      levelRows: $levels.levels, unlabeledRows: $levels.unlabeled, levelProm: $levelProm,
      runAbandonedIncrease: $abandoned,
      durationMeterTopBucketSecs: $topBucket, startByLevel: $startByLevel,
      hostLoad: {scenarioStart: $hostStart, loadStart: $hostLoadStart, afterDrain: $hostEnd},
      containerStats: $containerStats,
      sampleSecs: $sampleSecs, samples: $samples,
      foreignRows: $foreign}' \
    >"${RESULTS}/measurements.json"
}
