#!/usr/bin/env bash
# Scenario 4 (s4-signals, WP12; finding 3.2, fixed by WP2): signal throughput
# across nodes, and whether a delivery that wakes nobody leaves a wake permit
# behind. Sourced by perf/run.sh, which provides RUN_ID, RESULTS, REPLICAS
# (>= 2), PROFILE, lib/common.sh and lib/topology.sh. Writes
# ${RESULTS}/measurements.json; perf/eval/s4-signals.jq judges it.
#
# Workload: perf-signals runs (sample-perf type "signals"): one step, then M
# awaits on the distinct names perf.s0 .. perf.s<M-1> (parkSeconds 7200),
# then one step; output M. Run n is started on nodeFor(n) and runs there.
# Sleep runs: perf-park, sleep mode, parkSeconds 20.
#
#   profile  replicas  W     M   levels (signals/s, 30 s each)  W_s
#   local    2         450   10  50, 100                        200
#   full     3         1500  10  50, 100, 200, 400              600
# (W x M = the signals offered per phase: local 4500, full 15000 of the
# 22500 the full levels could offer; k6 stops at W x M.)
#
# The wake-permit count is read EXACTLY, from every node's own
# /actuator/prometheus (maestro_wake_permits, WP2's gauge; absent before
# 0c710a1e), at P0 (after setup), P1 (after the local phase), P2 (after the
# cross phase) and P3 (after the timer phase).
#
# Order: warm-up and the idle background window; P0. Local phase: start W
# runs (start-rate.js, 50/s), wait until all W are WAITING_SIGNAL (<= 180 s),
# pgss reset, signal-rate.js with route "own" (each signal on its run's own
# node) at the profile's levels, wait until every run is terminal (<= 300 s),
# close the window, read the phase's rows (s4-status.sql), P1. Cross phase:
# the same with a fresh prefix and route "next" (each signal on nodeFor(n +
# 1): never the owner), P2. Timer phase: W_s sleep runs round-robin, wait
# until all are terminal (their timers fire on the timer-poller leader; the
# other owners wake by their re-check), P3.
# shellcheck shell=bash
# SC2016: the single-quoted '$x' strings are jq filters, expanded by jq.
# shellcheck disable=SC2016

S4_M=10
S4_HOLD_SECS=30
S4_START_RATE=50

# Every node's maestro_wake_permits as [{container, value}] (value null when
# the node has no such series).
s4_permits() {
  local i scrape v all="[]"
  for ((i = 1; i <= REPLICAS; i++)); do
    scrape=$(http_get "$(node_url "$i")/actuator/prometheus" 2>/dev/null || true)
    v=$(awk '/^maestro_wake_permits[ {]/ { n = split($0, f, " "); print f[n]; exit }' <<<"$scrape")
    all=$(jq -c --arg c "$(node_container "$i")" --arg v "$v" \
      '. + [{container: $c, value: ($v | if . == "" then null else tonumber end)}]' <<<"$all")
  done
  jq -c --argjson at "$(epoch_now)" '{at: $at, nodes: .}' <<<"$all"
}

# Waits until every one of <expected> rows of <prefix> has status <status>
# (or is terminal), at most <timeout> s. Prints {status, waitedSecs, timedOut}.
s4_wait_status() { # <prefix> <expected> <status> <timeout>
  local start status n
  start=$(date +%s)
  while :; do
    status=$(psql_json run-status -v "prefix=$1")
    n=$(jq --arg s "$3" '.byStatus[$s] // 0' <<<"$status")
    if ((n >= $2)); then
      jq -nc --argjson s "$status" --argjson w "$(($(date +%s) - start))" '{status: $s, waitedSecs: $w, timedOut: false}'
      return 0
    fi
    if (($(date +%s) - start >= $4)); then
      jq -nc --argjson s "$status" --argjson w "$(($(date +%s) - start))" '{status: $s, waitedSecs: $w, timedOut: true}'
      return 0
    fi
    sleep 2
  done
}

# One signal phase. Prints its record.
s4_signal_phase() { # <name> <route own|next> <W> <levels>
  local name=$1 route=$2 w=$3 levels=$4
  local prefix="${RUN_ID}-s4${name}-" parked reset drain window status plan
  k6_run "start${name}" start-rate.js "PERF_ID_PREFIX=${prefix}" "PERF_ID_OFFSET=0" "PERF_COUNT=${w}" \
    "PERF_RATE=${S4_START_RATE}" "PERF_DURATION=$(((w + S4_START_RATE - 1) / S4_START_RATE + 1))" \
    "PERF_BODY=$(jq -nc --argjson m "$S4_M" '{type: "signals", signals: $m, parkSeconds: 7200}')"
  parked=$(s4_wait_status "$prefix" "$w" WAITING_SIGNAL 180)
  log "s4 ${name}: parked $(jq -c '{byStatus: .status.byStatus, waitedSecs, timedOut}' <<<"$parked")"
  reset=$(psql_json pgss-reset)
  k6_run "$name" signal-rate.js "PERF_ID_PREFIX=${prefix}" "PERF_W=${w}" "PERF_M=${S4_M}" \
    "PERF_LEVELS=${levels}" "PERF_HOLD_SECS=${S4_HOLD_SECS}" "PERF_ROUTE=${route}"
  drain=$(wait_terminal "$prefix" "$w" 300 2)
  window=$(psql_json pgss-window)
  status=$(psql_json s4-status -v "prefix=${prefix}" -v "m=${S4_M}")
  plan=$(cat "${RESULTS}/k6-${name}-plan.json" 2>/dev/null || echo null)
  log "s4 ${name}: $(jq -c '{byStatus, signals, latencyMs}' <<<"$status")"
  jq -nc --arg n "$name" --arg p "$prefix" --arg route "$route" --argjson w "$w" \
    --argjson parked "$parked" --argjson reset "$reset" --argjson drain "$drain" --argjson window "$window" \
    --argjson status "$status" --argjson plan "$plan" \
    --argjson k6 "$(k6_records | jq -c --arg n "$name" '[.[] | select(.phase == $n)] | first')" \
    '{name: $n, prefix: $p, route: $route, w: $w, m: ($plan.m // null), plan: $plan,
      parked: {byStatus: $parked.status.byStatus, waitedSecs: $parked.waitedSecs, timedOut: $parked.timedOut},
      pgssReset: $reset, drain: {waitedSecs: $drain.waitedSecs, timedOut: $drain.timedOut},
      accepted: ($k6.checksPassed // null), offered: (($plan.w // 0) * ($plan.m // 0)),
      window: $window, status: $status}'
}

scenario_run() {
  local w ws levels warmup background p0 p1 p2 p3 local_phase cross timer_prefix timer_drain timer_status
  if [[ "$PROFILE" == full ]]; then w=1500 ws=600 levels=50,100,200,400; else w=450 ws=200 levels=50,100; fi

  log "s4: warm-up, $((20 * REPLICAS)) runs"
  warmup=$(warm_up "$((20 * REPLICAS))" "$((10 * REPLICAS))" '{"type":"chain","activities":2,"activityMillis":10}')
  sleep 2
  background=$(measure_background)
  p0=$(s4_permits)
  log "s4: P0 $(jq -c . <<<"$p0")"

  local_phase=$(s4_signal_phase local own "$w" "$levels")
  p1=$(s4_permits)
  log "s4: P1 $(jq -c . <<<"$p1")"
  cross=$(s4_signal_phase cross next "$w" "$levels")
  p2=$(s4_permits)
  log "s4: P2 $(jq -c . <<<"$p2")"

  timer_prefix="${RUN_ID}-s4timer-"
  k6_run starttimer start-rate.js "PERF_ID_PREFIX=${timer_prefix}" "PERF_ID_OFFSET=0" "PERF_COUNT=${ws}" \
    "PERF_RATE=${S4_START_RATE}" "PERF_DURATION=$(((ws + S4_START_RATE - 1) / S4_START_RATE + 1))" \
    'PERF_BODY={"type":"park","parkMode":"sleep","parkSeconds":20}'
  timer_drain=$(wait_terminal "$timer_prefix" "$ws" 240 2)
  timer_status=$(psql_json s3-release -v "park=${timer_prefix}" -v signal=perf.release)
  p3=$(s4_permits)
  log "s4: timer $(jq -c '{byStatus, results}' <<<"$timer_status"), P3 $(jq -c . <<<"$p3")"

  jq -n \
    --argjson w "$w" --argjson m "$S4_M" --argjson ws "$ws" --arg levels "$levels" --argjson hold "$S4_HOLD_SECS" \
    --argjson warmup "$warmup" --argjson background "$background" --argjson k6 "$(k6_records)" \
    --argjson p0 "$p0" --argjson p1 "$p1" --argjson p2 "$p2" --argjson p3 "$p3" \
    --argjson local "$local_phase" --argjson cross "$cross" \
    --arg tp "$timer_prefix" --argjson td "$timer_drain" --argjson ts "$timer_status" \
    '{plan: {w: $w, m: $m, sleepRuns: $ws, levels: ($levels | split(",") | map(tonumber)), holdSecs: $hold},
      warmup: $warmup, background: $background, k6: $k6,
      permits: {P0: $p0, P1: $p1, P2: $p2, P3: $p3},
      phases: [$local, $cross],
      timer: {prefix: $tp, drain: {waitedSecs: $td.waitedSecs, timedOut: $td.timedOut}, status: $ts},
      windows: [$local.window, $cross.window],
      phaseTable: ([$local, $cross] | map({phase: .name, route, offered, accepted,
                     completed: (.status.byStatus.COMPLETED // 0), consumed: .status.signals.consumed,
                     latencyP50Ms: .status.latencyMs.p50, latencyP95Ms: .status.latencyMs.p95,
                     windowSecs: .window.windowSecs, maestroCalls: .window.maestroCalls}))
                  + [{phase: "timer", offered: $ws, completed: ($ts.byStatus.COMPLETED // 0)}]
                  + ([["P0", $p0], ["P1", $p1], ["P2", $p2], ["P3", $p3]]
                     | map({phase: .[0], permits: (.[1].nodes | map(.value))}))}' \
    >"${RESULTS}/measurements.json"
}
