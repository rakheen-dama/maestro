#!/usr/bin/env bash
# Scenario 3 (s3-parked): the steady store load of a parked population, and
# whether it is linear in the number of parked runs. Sourced by perf/run.sh,
# which provides RUN_ID, RESULTS, REPLICAS, PROFILE and lib/common.sh. Writes
# ${RESULTS}/measurements.json; perf/eval/s3-parked.jq judges it.
#
# Workload: perf-park runs (one step, park, one step) parked on the signal
# perf.release (PERF_PARK_MODE=signal, the default) or on a durable sleep
# (PERF_PARK_MODE=sleep: measured on the local profile at one and two
# nodes and on the full profile at one node; perf/eval/s3-parked.jq),
# parkSeconds 7200 so no deadline fires during a run.
# Parked ids are ${RUN_ID}-park-<n>, n continuous across plateaus
# (PERF_ID_OFFSET); probes are ${RUN_ID}-probe-p<P>-<i> (chain, 3 activities
# x 20 ms), started at a constant rate while P runs are parked.
#
#   profile  plateaus P          idle   hold   probe         seed VUs
#   local    1000, 2000          60 s   120 s  2/s for 30 s  200 / 200
#   full     1000, 5000, 10000   120 s  300 s  2/s for 60 s  200 / 200
#
# Seeding is PACED: the P - P_prev new runs of a plateau are started at a
# constant rate over exactly one wake-recheck interval (start-rate.js,
# rate = P - P_prev per wakeRecheckSecs, capped at that count), so the
# population's re-check phases are spread evenly over the interval. An
# unpaced batch (start-batch.js at 50 VUs) parks 1000 runs in about 3 s
# (shakedown r20261113t023959z: k6 3 s, all WAITING_SIGNAL within 5 s at a
# 2 s poll) and every one of them then re-checks inside the same few seconds
# of each 30 s cycle: a window edge that falls inside that burst counts a
# partial cycle for a whole share of the population, which is exactly the
# bias the window discipline below exists to prevent. Paced, a window of any
# phase sees each run's re-checks in proportion to its length. timeToPark is
# then the seeding interval plus the lag from the last start to the last
# park (lagSecs, at a 2 s poll), not a capacity figure; the shakedown's
# figure above is the one recorded for an unpaced batch. VUs 200 / 200 (all
# pre-allocated): the full profile's 5000-run seed offers 167 starts/s.
#
# Window discipline. Every measured window (idle, each plateau's hold, the
# release window) is a whole number of the node's wake-recheck interval AND
# of its recovery-poll interval AND of its timer-poll interval, all read from
# /perf/info at run time (defaults 30 s, 60 s, 5 s: unit 60 s), so no partial
# recovery pass or recheck cycle biases a per-minute figure: the requested
# length is rounded UP to that unit. The brief's local hold of 90 s is not a
# whole number of 60 s recovery intervals; it becomes 120 s (2 recovery
# passes, 4 recheck intervals, 24 timer ticks). The full profile's 300 s and
# both idle windows already are whole units. Periodic sweeps inside any
# window (the lock cleaner every 10 min from node start) are accounted for by
# validity.sweeps (common.jq), which reads every window, auxiliary ones
# included.
#
# Order: warm-up (40 chain runs per replica, outside every window) -> idle
# window (P = 0: the background rate, validity.background) -> probe at P = 0
# -> for each plateau P: seed P - P_prev parked runs (start-rate.js, paced
# over one wake-recheck interval; its own auxiliary "seed" window), wait until exactly P rows of the park
# prefix are parked (WAITING_SIGNAL, or WAITING_TIMER for sleep; <= 600 s, or
# correctness.plateauReached FAILs and the scenario stops), sleep settle =
# wakeRecheckInterval + 5 s (every new run past its first recheck), status
# snapshot, pgss reset, hold, window + component attribution, status
# snapshot (validity.parked: both snapshots show exactly P parked rows, no
# other row of the run open), probe -> release (signal mode): reset,
# signal-batch.js sends perf.release to EVERY parked run (run n started on
# nodeFor(n), its signal goes to nodeFor(n + 1): across nodes when
# replicas > 1), wait until every park row is terminal, fill the window to a
# whole unit, window + attribution, release record (s3-release.sql: status,
# result "released", one consumed signal row per run). Prometheus is read per
# window after the release (slots, parked gauge, heap, threads, pool, CPU).
#
# Contention: the host's load average at the start, at every window's open
# and close and at the end, the containers' CPU (docker stats, ~every 7 s;
# this project's containers only) and node CPU per window; see
# advisory.contention in perf/eval/s3-parked.jq.
#
# The statement figures, the decomposition by component, the linearity gate,
# the statement gate's relative allowance and its measured justification are
# described in perf/eval/s3-parked.jq.
# shellcheck shell=bash
# SC2016: the single-quoted '$x' strings are jq filters, expanded by jq.
# shellcheck disable=SC2016

S3_STATS_SECS=7

# pgss-window + component attribution at one instant, as one JSON object.
s3_window() {
  cat "${PERF_DIR}/sql/pgss-window.sql" "${PERF_DIR}/sql/s3-components.sql" \
    | dc exec -T postgres psql -U postgres -d maestro -At -v ON_ERROR_STOP=1 -f - \
    | jq -sc 'if length == 2 then .[0] + .[1] else error("expected 2 JSON rows, got \(length)") end'
}

s3_status() {
  psql_json s3-status -v "park=$1" -v "run=${RUN_ID}-"
}

# The Prometheus view of one window [a, b] (epoch seconds, harness clock).
# A series the engine does not export is null ("not available"). Summed over
# replicas for counts and bytes, max over replicas for CPU.
s3_window_prom() {
  local a=$1 b=$2 range at
  range="$(jq -rn --argjson a "$a" --argjson b "$b" '[($b - $a | floor), 5] | max')s"
  at=$(jq -rn --argjson b "$b" '$b | floor')
  local heap='sum(jvm_memory_used_bytes{area="heap"})' heapC='sum(jvm_memory_committed_bytes{area="heap"})'
  local old='sum(jvm_memory_used_bytes{area="heap",id=~".*(Tenured|Old).*"})'
  local thr='sum(jvm_threads_live_threads)' parked='sum(maestro_workflows_parked)' running='sum(maestro_workflows_running)'
  local slotsW='sum(maestro_taskqueue_slots_used{kind="workflow"})' slotsA='sum(maestro_taskqueue_slots_used{kind="activity"})'
  local act='sum(hikaricp_connections_active)' pend='sum(hikaricp_connections_pending)'
  local pcpu='max(process_cpu_usage)' scpu='max(system_cpu_usage)'
  q() { prom_instant "$1" "$at"; }
  jq -nc --argjson rangeSecs "${range%s}" --argjson at "$at" \
    --argjson heapAvg "$(q "avg_over_time(${heap}[${range}:5s])")" \
    --argjson heapMax "$(q "max_over_time(${heap}[${range}:5s])")" \
    --argjson heapMin "$(q "min_over_time(${heap}[${range}:5s])")" \
    --argjson heapCommittedAvg "$(q "avg_over_time(${heapC}[${range}:5s])")" \
    --argjson oldGenMax "$(q "max_over_time(${old}[${range}:5s])")" \
    --argjson oldGenMin "$(q "min_over_time(${old}[${range}:5s])")" \
    --argjson liveData "$(q 'sum(jvm_gc_live_data_size_bytes)')" \
    --argjson threadsAvg "$(q "avg_over_time(${thr}[${range}:5s])")" \
    --argjson threadsMax "$(q "max_over_time(${thr}[${range}:5s])")" \
    --argjson parkedAvg "$(q "avg_over_time(${parked}[${range}:5s])")" \
    --argjson parkedMin "$(q "min_over_time(${parked}[${range}:5s])")" \
    --argjson runningMax "$(q "max_over_time(${running}[${range}:5s])")" \
    --argjson slotsWMax "$(q "max_over_time(${slotsW}[${range}:5s])")" \
    --argjson slotsWAvg "$(q "avg_over_time(${slotsW}[${range}:5s])")" \
    --argjson slotsAMax "$(q "max_over_time(${slotsA}[${range}:5s])")" \
    --argjson actMax "$(q "max_over_time(${act}[${range}:5s])")" \
    --argjson actAvg "$(q "avg_over_time(${act}[${range}:5s])")" \
    --argjson pendMax "$(q "max_over_time(${pend}[${range}:5s])")" \
    --argjson pcAvg "$(q "avg_over_time(${pcpu}[${range}:5s])")" \
    --argjson pcMax "$(q "max_over_time(${pcpu}[${range}:5s])")" \
    --argjson scAvg "$(q "avg_over_time(${scpu}[${range}:5s])")" \
    '{rangeSecs: $rangeSecs, at: $at,
      heap: {usedAvg: $heapAvg, usedMax: $heapMax, usedMin: $heapMin, committedAvg: $heapCommittedAvg,
             oldGenMax: $oldGenMax, oldGenMin: $oldGenMin, gcLiveDataAtEnd: $liveData},
      threads: {liveAvg: $threadsAvg, liveMax: $threadsMax},
      parkedGauge: {avg: $parkedAvg, min: $parkedMin}, runningGaugeMax: $runningMax,
      slotsUsed: {workflowMax: $slotsWMax, workflowAvg: $slotsWAvg, activityMax: $slotsAMax},
      hikari: {activeMax: $actMax, activeAvg: $actAvg, pendingMax: $pendMax},
      nodeCpu: {processAvg: $pcAvg, processMax: $pcMax, systemAvg: $scAvg}}'
}

# Smallest common multiple of positive whole seconds.
s3_lcm() {
  jq -rn --argjson xs "$(printf '%s\n' "$@" | jq -sc .)" '
    def gcd($a; $b): if $b == 0 then $a else gcd($b; $a % $b) end;
    if ($xs | all(. > 0 and . == floor)) | not then error("intervals must be positive whole seconds: \($xs)")
    else reduce $xs[] as $x (1; . * $x / gcd(.; $x)) end'
}

# Runs one probe at P parked: PERF_RATE starts/s for PERF_DURATION s of
# chain runs under ${RUN_ID}-probe-p<P>-, waits (<= 120 s) until every
# accepted start is terminal, and prints the probe record.
s3_probe() {
  local p=$1 rate=$2 secs=$3 body=$4
  local prefix="${RUN_ID}-probe-p${p}-" phase="probe-p${p}" accepted drain latency t0 t1
  t0=$(date +%s)
  k6_run "$phase" start-rate.js "PERF_ID_PREFIX=${prefix}" "PERF_RATE=${rate}" "PERF_DURATION=${secs}" \
    "PERF_BODY=${body}"
  t1=$(date +%s)
  accepted=$(jq -s --arg ph "$phase" '[.[] | select(.phase == $ph) | .checksPassed // 0] | add // 0' "${RESULTS}/k6-runs.jsonl")
  drain=$(wait_terminal "$prefix" "$accepted" 120 2)
  latency=$(psql_json latency -v "prefix=${prefix}")
  log "s3: probe P=${p}: ${accepted} accepted, $(jq -c '{byStatus: .status.byStatus, waitedSecs, timedOut}' <<<"$drain"), latency $(jq -c . <<<"$latency")"
  local plan=null
  [[ ! -s "${RESULTS}/k6-${phase}-plan.json" ]] || plan=$(jq -c . "${RESULTS}/k6-${phase}-plan.json")
  jq -nc --argjson p "$p" --arg ph "$phase" --arg prefix "$prefix" --argjson rate "$rate" --argjson secs "$secs" \
    --argjson accepted "$accepted" --argjson drain "$drain" --argjson latency "$latency" --argjson plan "$plan" \
    --argjson t0 "$t0" --argjson t1 "$t1" \
    '{P: $p, phase: $ph, prefix: $prefix, requested: {rate: $rate, durationSecs: $secs},
      expected: ($rate * $secs), accepted: $accepted, executedPlan: $plan, startEpoch: $t0, k6EndEpoch: $t1,
      drain: {waitedSecs: $drain.waitedSecs, timedOut: $drain.timedOut}, status: $drain.status, latency: $latency}'
}

scenario_run() {
  local parkPrefix="${RUN_ID}-park-"
  local mode="${PERF_PARK_MODE:-signal}" parkedStatus
  case "$mode" in
    signal) parkedStatus=WAITING_SIGNAL ;;
    sleep) parkedStatus=WAITING_TIMER ;;
    *) die "s3-parked: PERF_PARK_MODE must be signal or sleep, got '${mode}'" ;;
  esac
  local plateaus idleReq holdReq probeRate probeSecs seedVus=200
  case "$PROFILE" in
    local) plateaus='[1000,2000]' idleReq=60 holdReq=90 probeRate=2 probeSecs=30 ;;
    full) plateaus='[1000,5000,10000]' idleReq=120 holdReq=300 probeRate=2 probeSecs=60 ;;
    *) die "s3-parked: unknown profile ${PROFILE}" ;;
  esac
  local parkBody probeBody warmBody
  parkBody=$(jq -nc --arg m "$mode" '{type: "park", parkMode: $m, parkSeconds: 7200}')
  probeBody='{"type":"chain","activities":3,"activityMillis":20}'
  warmBody="$probeBody"

  # The node's intervals, as bound (never assumed).
  local node recheck recovery timer lockTtl unit idle hold settle
  node=$(jq -c '.nodes[0]' "${RESULTS}/identity.json")
  recheck=$(iso_secs "$(jq -r '.wakeRecheckInterval // empty' <<<"$node")") || die "node 1 reports no wakeRecheckInterval"
  recovery=$(iso_secs "$(jq -r '.recoveryPollInterval // empty' <<<"$node")") || die "node 1 reports no recoveryPollInterval"
  timer=$(iso_secs "$(jq -r '.timerPollInterval // empty' <<<"$node")") || die "node 1 reports no timerPollInterval"
  lockTtl=$(iso_secs "$(jq -r '.lockTtl // empty' <<<"$node")" 2>/dev/null || echo null)
  unit=$(s3_lcm "$recheck" "$recovery" "$timer") || die "s3-parked: cannot form a window unit from recheck ${recheck} s, recovery ${recovery} s, timer ${timer} s"
  idle=$(jq -rn --argjson r "$idleReq" --argjson u "$unit" '(($r / $u) | ceil) * $u')
  hold=$(jq -rn --argjson r "$holdReq" --argjson u "$unit" '(($r / $u) | ceil) * $u')
  settle=$(jq -rn --argjson r "$recheck" '$r + 5')
  local pmax releaseTimeout
  pmax=$(jq '.[-1]' <<<"$plateaus")
  releaseTimeout=$(jq -rn --argjson p "$pmax" '120 + ($p / 10 | ceil)')
  local plan
  plan=$(jq -nc --arg profile "$PROFILE" --arg mode "$mode" --arg ps "$parkedStatus" --argjson plateaus "$plateaus" \
    --argjson idleReq "$idleReq" --argjson idle "$idle" --argjson holdReq "$holdReq" --argjson hold "$hold" \
    --argjson unit "$unit" --argjson recheck "$recheck" --argjson recovery "$recovery" --argjson timer "$timer" \
    --argjson lockTtl "$lockTtl" --argjson settle "$settle" --argjson probeRate "$probeRate" \
    --argjson probeSecs "$probeSecs" --argjson seedVus "$seedVus" --argjson releaseTimeout "$releaseTimeout" \
    --argjson parkBody "$parkBody" --argjson probeBody "$probeBody" \
    '{profile: $profile, parkMode: $mode, parkedStatus: $ps, plateaus: $plateaus,
      idleRequestedSecs: $idleReq, idleSecs: $idle, holdRequestedSecs: $holdReq, holdSecs: $hold,
      windowUnitSecs: $unit, wakeRecheckSecs: $recheck, recoveryPollSecs: $recovery, timerPollSecs: $timer,
      lockTtlSecs: $lockTtl, renewIntervalSecs: (if $lockTtl == null then null else $lockTtl / 3 end),
      settleSecs: $settle, parkTimeoutSecs: 600,
      probe: {rate: $probeRate, durationSecs: $probeSecs, body: $probeBody, drainTimeoutSecs: 120},
      seed: {executor: "constant-arrival-rate", paceSecs: $recheck, vus: $seedVus, body: $parkBody},
      release: {signal: "perf.release", vus: 50, timeoutSecs: $releaseTimeout}}')
  log "s3: plan $(jq -c '{parkMode, plateaus, idleSecs, holdSecs, windowUnitSecs, settleSecs, probe: .probe | {rate, durationSecs}}' <<<"$plan")"

  local host_start
  host_start=$(host_load)
  log "s3: host load at start $(jq -c '{load1, load5, load15, hostCpus}' <<<"$host_start")"

  # Container CPU sampler (this project's containers only, read-only); stops
  # on the stop file, when the driver's shell is gone, or at a deadline.
  local cstats="${RESULTS}/s3-container-stats.jsonl" stop="${RESULTS}/.s3-sampler-stop" sampler driver=$$ deadline
  deadline=$(($(date +%s) + 7200))
  : >"$cstats"
  rm -f "$stop"
  (
    set +eE
    trap - ERR EXIT INT TERM
    while [[ ! -e "$stop" ]] && kill -0 "$driver" 2>/dev/null && (($(date +%s) < deadline)); do
      container_stats >>"$cstats" 2>>"${RESULTS}/harness.log"
      sleep "$S3_STATS_SECS"
    done
  ) &
  sampler=$!
  bg_register "$sampler"

  local warmCount=$((40 * REPLICAS)) warmup background
  log "s3: warm-up, ${warmCount} chain runs"
  warmup=$(warm_up "$warmCount" "$((10 * REPLICAS))" "$warmBody")
  sleep 2

  local windows="[]" aux="[]" plateauRecs="[]" probes="[]" hostLoads="[]" stopped=null
  local wa wb
  wa=$(date +%s)
  background=$(measure_background "$idle")
  wb=$(date +%s)
  windows=$(jq -c --argjson bg "$background" --argjson a "$wa" --argjson b "$wb" \
    '. + [$bg.window + {P: 0, kind: "idle", epochs: {start: $a, end: $b}}]' <<<"$windows")
  probes=$(jq -c --argjson r "$(s3_probe 0 "$probeRate" "$probeSecs" "$probeBody")" '. + [$r]' <<<"$probes")

  local p prev=0 delta t0 t1 st reached waited before after w
  for p in $(jq -r '.[]' <<<"$plateaus"); do
    delta=$((p - prev))
    log "s3: plateau P=${p}: seeding ${delta} parked runs (${parkPrefix}${prev}..$((p - 1)))"
    psql_json pgss-reset >/dev/null
    wa=$(date +%s)
    t0=$wa
    k6_run "seed-p${p}" start-rate.js "PERF_ID_PREFIX=${parkPrefix}" "PERF_ID_OFFSET=${prev}" \
      "PERF_COUNT=${delta}" "PERF_RATE=${delta}" "PERF_TIME_UNIT=${recheck}" "PERF_DURATION=${recheck}" \
      "PERF_K6_VUS=${seedVus}" "PERF_K6_MAX_VUS=${seedVus}" "PERF_BODY=${parkBody}"
    t1=$(date +%s)
    reached=false
    while :; do
      st=$(s3_status "$parkPrefix")
      if [[ "$(jq --argjson p "$p" --arg s "$parkedStatus" '.park.total == $p and (.park.byStatus[$s] // 0) == $p' <<<"$st")" == true ]]; then
        reached=true
        break
      fi
      (($(date +%s) - t0 < 600)) || break
      sleep 2
    done
    waited=$(($(date +%s) - t0))
    local seedplan=null
    [[ ! -s "${RESULTS}/k6-seed-p${p}-plan.json" ]] || seedplan=$(jq -c . "${RESULTS}/k6-seed-p${p}-plan.json")
    w=$(s3_window)
    wb=$(date +%s)
    aux=$(jq -c --argjson w "$w" --argjson p "$p" --argjson prev "$prev" --argjson a "$wa" --argjson b "$wb" \
      '. + [$w + {kind: "seed", P: $p, Pprev: $prev, epochs: {start: $a, end: $b}}]' <<<"$aux")
    log "s3: plateau P=${p}: reached=${reached} after ${waited} s (k6 $((t1 - t0)) s); $(jq -c '.park' <<<"$st")"
    if [[ "$reached" != true ]]; then
      plateauRecs=$(jq -c --argjson p "$p" --argjson prev "$prev" --argjson st "$st" --argjson t0 "$t0" \
        --argjson t1 "$t1" --argjson w "$waited" --argjson sp "$seedplan" \
        '. + [{P: $p, Pprev: $prev, reached: false, seed: {startEpoch: $t0, k6EndEpoch: $t1, waitedSecs: $w,
               k6Secs: ($t1 - $t0), executedPlan: $sp}, statusAtTimeout: $st}]' <<<"$plateauRecs")
      stopped="\"plateau ${p} not reached within 600 s\""
      log "s3: plateau P=${p} not reached: scenario stops"
      break
    fi
    log "s3: plateau P=${p}: settle ${settle} s"
    sleep "$settle"
    hostLoads=$(jq -c --argjson h "$(host_load)" --argjson p "$p" '. + [$h + {P: $p, edge: "open"}]' <<<"$hostLoads")
    before=$(s3_status "$parkPrefix")
    psql_json pgss-reset >/dev/null
    wa=$(date +%s)
    log "s3: plateau P=${p}: window open, hold ${hold} s"
    sleep "$hold"
    w=$(s3_window)
    wb=$(date +%s)
    after=$(s3_status "$parkPrefix")
    hostLoads=$(jq -c --argjson h "$(host_load)" --argjson p "$p" '. + [$h + {P: $p, edge: "close"}]' <<<"$hostLoads")
    log "s3: plateau P=${p}: window $(jq -c '{windowSecs, maestroCalls, maestroCallsTopLevel, harnessCalls, sweepCalls, components: (.components | map_values(.calls))}' <<<"$w")"
    windows=$(jq -c --argjson w "$w" --argjson p "$p" --argjson a "$wa" --argjson b "$wb" \
      --argjson before "$before" --argjson after "$after" \
      '. + [$w + {P: $p, kind: "plateau", epochs: {start: $a, end: $b}, parkedBefore: $before, parkedAfter: $after}]' <<<"$windows")
    plateauRecs=$(jq -c --argjson p "$p" --argjson prev "$prev" --argjson t0 "$t0" --argjson t1 "$t1" --argjson w "$waited" \
      --argjson sp "$seedplan" --argjson r "$recheck" --argjson v "$seedVus" --arg pp "$parkPrefix" \
      '. + [{P: $p, Pprev: $prev, reached: true,
             seed: {startEpoch: $t0, k6EndEpoch: $t1, k6Secs: ($t1 - $t0), timeToParkSecs: $w,
                    lagSecs: ($t0 + $w - $t1), executedPlan: $sp,
                    requested: {executor: "constant-arrival-rate", rate: ($p - $prev), timeUnit: "\($r)s",
                                durationSecs: $r, offset: $prev, count: ($p - $prev),
                                preAllocatedVUs: $v, maxVUs: $v, idPrefix: $pp}}}]' <<<"$plateauRecs")
    probes=$(jq -c --argjson r "$(s3_probe "$p" "$probeRate" "$probeSecs" "$probeBody")" '. + [$r]' <<<"$probes")
    prev=$p
  done

  # Release: every parked run gets perf.release (signal mode only: a sleeping
  # run does not take the signal and stays parked until its timer).
  local release=null
  if [[ "$stopped" == null && "$mode" == signal ]]; then
    log "s3: release: perf.release to ${prev} parked runs"
    psql_json pgss-reset >/dev/null
    wa=$(date +%s)
    k6_run release signal-batch.js "PERF_ID_PREFIX=${parkPrefix}" "PERF_ID_OFFSET=0" "PERF_COUNT=${prev}" \
      "PERF_VUS=50" "PERF_MAX_DURATION=10m" "PERF_SIGNAL=perf.release"
    t1=$(date +%s)
    local drain fill rel relplan=null
    drain=$(wait_terminal "$parkPrefix" "$prev" "$releaseTimeout" 2)
    # Fill the release window to a whole number of units, so the rechecks of
    # the runs not yet released and the recovery passes are whole cycles.
    # On the database clock (the one windowSecs is measured on), plus 0.5 s
    # so the window is never shorter than its whole number of units.
    fill=$(jq -rn --argjson e "$(psql_json pgss-elapsed)" --argjson u "$unit" \
      '([(($e + 0.5) / $u | ceil), 1] | max) * $u - $e + 0.5')
    log "s3: release: $(jq -c '{byStatus: .status.byStatus, waitedSecs, timedOut}' <<<"$drain"); filling the window by ${fill} s"
    sleep "$fill"
    w=$(s3_window)
    wb=$(date +%s)
    rel=$(psql_json s3-release -v "park=${parkPrefix}" -v "signal=perf.release")
    [[ ! -s "${RESULTS}/k6-release-plan.json" ]] || relplan=$(jq -c . "${RESULTS}/k6-release-plan.json")
    aux=$(jq -c --argjson w "$w" --argjson p "$prev" --argjson a "$wa" --argjson b "$wb" \
      '. + [$w + {kind: "release", P: $p, epochs: {start: $a, end: $b}}]' <<<"$aux")
    release=$(jq -nc --argjson n "$prev" --argjson a "$wa" --argjson t1 "$t1" --argjson d "$drain" --argjson r "$rel" \
      --argjson plan "$relplan" --argjson timeout "$releaseTimeout" \
      '{count: $n, phase: "release", startEpoch: $a, k6EndEpoch: $t1, k6Secs: ($t1 - $a), timeoutSecs: $timeout,
        executedPlan: $plan, crossNode: (if $plan == null then null else $plan.crossNode end),
        drain: {waitedSecs: $d.waitedSecs, timedOut: $d.timedOut}, status: $r}')
    log "s3: release: $(jq -c '{released, results, byStatus, signals}' <<<"$rel")"
  fi

  touch "$stop"
  bg_wait "$sampler"
  rm -f "$stop"
  local host_end final foreign
  host_end=$(host_load)
  final=$(psql_json run-status -v "prefix=${RUN_ID}-")
  foreign=$(psql_json foreign-rows -v "prefix=${RUN_ID}-")

  # Prometheus per window (one more scrape interval first), and run-wide
  # abandoned runs.
  sleep 6
  local prom="[]" row
  while IFS= read -r row; do
    prom=$(jq -c --argjson r "$row" --argjson v "$(s3_window_prom "$(jq -r .epochs.start <<<"$row")" "$(jq -r .epochs.end <<<"$row")")" \
      '. + [{kind: $r.kind, P: $r.P} + $v]' <<<"$prom")
  done < <(jq -c --argjson a "$aux" '(. + $a)[] | {kind, P, epochs}' <<<"$windows")
  local first_a last_b abandoned
  first_a=$(jq -r '.[0].epochs.start' <<<"$windows")
  last_b=$(date +%s)
  abandoned=$(prom_instant_checked "sum(increase(maestro_run_abandoned_total[$((last_b - first_a))s]))" "$last_b")

  jq -n \
    --arg parkPrefix "$parkPrefix" --arg runPrefix "${RUN_ID}-" --argjson plan "$plan" \
    --argjson warmup "$warmup" --argjson background "$background" --argjson k6 "$(k6_records)" \
    --argjson windows "$windows" --argjson aux "$aux" --argjson plateaus "$plateauRecs" --argjson probes "$probes" \
    --argjson release "$release" --argjson prom "$prom" --argjson abandoned "$abandoned" \
    --argjson hostStart "$host_start" --argjson hostLoads "$hostLoads" --argjson hostEnd "$host_end" \
    --slurpfile containerStats "$cstats" --argjson final "$final" --argjson foreign "$foreign" \
    --argjson stopped "$stopped" \
    '{idPrefix: $parkPrefix, runPrefix: $runPrefix, parkMode: $plan.parkMode, parkedStatus: $plan.parkedStatus,
      workflowShape: $plan.seed.body, probeShape: $plan.probe.body,
      plan: $plan, warmup: $warmup, background: $background, k6: $k6,
      windows: $windows, auxWindows: $aux, plateaus: $plateaus, probes: $probes, release: $release,
      windowProm: $prom, runAbandonedIncrease: $abandoned.value, runAbandonedQueryOk: $abandoned.ok,
      hostLoad: {scenarioStart: $hostStart, windows: $hostLoads, end: $hostEnd},
      containerStats: $containerStats, runStatus: $final, foreignRows: $foreign, stopped: $stopped}' \
    >"${RESULTS}/measurements.json"
}
