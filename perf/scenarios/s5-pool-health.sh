#!/usr/bin/env bash
# Scenario 5 (s5-pool-health, WP12; finding 3.5a, fixed by WP4): what
# Maestro's health component reports while the HOST exhausts the shared
# connection pool, the database itself healthy. Sourced by perf/run.sh, which
# provides RUN_ID, RESULTS, REPLICAS (1), PROFILE, lib/common.sh and
# lib/topology.sh. Writes ${RESULTS}/measurements.json;
# perf/eval/s5-pool-health.jq judges it.
#
# Injector (MD-6): H perf-hold runs (sample-perf type "hold"), each K
# activities of SELECT pg_sleep(h) through the application's own DataSource
# (Hikari, Boot's default maximum of 10; the wake listener holds one for the
# app's lifetime, so 9 serve everything else).
#
#   profile  H    K    h      health windows (idle / exhausted / recovered)
#   local    10   5    8 s    30 s / 35 s / 30 s    (exhaustion ~ 10x5x8/9 = 44 s)
#   full     10   10   8 s    30 s / 75 s / 30 s    (~ 89 s)
#
# Waits stay below Hikari's 30 s connectionTimeout: 9 connections, each held
# for at most 8 s, about 12 waiters -> the worst wait is about 16 s. So no
# SQLTransientConnectionException, every instance-lock renewal lands within
# its 30 s TTL, and the pool is busy, not failing (validity.noPoolTimeouts).
#
# Order: warm-up and the idle background window (common); the in-database
# activity sampler (perf/sql/activity-sampler.sql, label "s5", pattern
# pg_sleep, 10 Hz) starts and runs through every phase; idle health 30 s
# (k6/health-probe.js at 1/s: /actuator/health/perf and /actuator/health,
# 5 s client timeout each); exhausted: Hikari counters read, pgss reset, H
# hold runs started (start-batch.js), wait (<= 30 s) until the sampler sees
# >= hikariMaximumPoolSize - 1 engine backends in pg_sleep, health probe for
# the exhausted window, wait until every hold run is terminal, close the
# pgss window; recovered health 30 s; Hikari counters read again; the sampler
# is cancelled (pg_cancel_backend of its own session). Each phase's sampler
# figures are read for its [from, to] on the database clock.
# shellcheck shell=bash
# SC2016: the single-quoted '$x' strings are jq filters, expanded by jq.
# shellcheck disable=SC2016

S5_H=10
S5_HOLD_MILLIS=8000
S5_IDLE_SECS=30
S5_RECOVERED_SECS=30
S5_SATURATE_SECS=30

# Hikari's timeout and connection-creation counters from node 1's own scrape.
s5_hikari() {
  local scrape
  scrape=$(http_get "$(node_url 1)/actuator/prometheus" 2>/dev/null) || { printf '{"timeouts":null,"creations":null}'; return 0; }
  awk -v at="$(epoch_now)" '
    function val(line) { n = split(line, f, " "); return f[n] }
    /^hikaricp_connections_timeout_total[ {]/ { t += val($0); ht = 1 }
    /^hikaricp_connections_creation_seconds_count[ {]/ { c += val($0); hc = 1 }
    END { printf "{\"at\":%s,\"timeouts\":%s,\"creations\":%s}\n", at, (ht ? t : "null"), (hc ? c : "null") }' <<<"$scrape"
}

# The k6 health counters and answer times of phase <phase>, from its summary.
s5_counters() {
  local f="${RESULTS}/k6-$1.json"
  if [[ ! -s "$f" ]]; then
    printf 'null'
    return 0
  fi
  jq -c '.metrics as $m
    | def c($k): ($m[$k].values.count // null);
      def t($k): ($m[$k].values // null | if . == null then null else {p50: .med, max: .max, count: .count} end);
      {maestro_up: c("maestro_up"), maestro_unknown: c("maestro_unknown"),
       maestro_down_store_timed_out: c("maestro_down_store_timed_out"),
       maestro_down_store_unreachable: c("maestro_down_store_unreachable"),
       maestro_down_other: c("maestro_down_other"), maestro_no_answer: c("maestro_no_answer"),
       root_up: c("root_up"), root_down: c("root_down"), root_other: c("root_other"),
       root_no_answer: c("root_no_answer"),
       maestroAnswerMs: t("maestro_answer_ms"), rootAnswerMs: t("root_answer_ms"),
       iterations: ($m.iterations.values.count // null)}' "$f"
}

# One health phase: the probe for <secs>, bracketed by database times.
s5_health_phase() { # <name> <secs> <minMatching>
  local from to counters samples
  from=$(db_now_json)
  k6_run "$1" health-probe.js "PERF_DURATION=$2"
  to=$(db_now_json)
  counters=$(s5_counters "$1")
  samples=$(psql_json activity-samples -v label=s5 -v "min=$3" -v "from=$(jq -r . <<<"$from")" -v "to=$(jq -r . <<<"$to")")
  jq -nc --arg n "$1" --argjson secs "$2" --argjson from "$from" --argjson to "$to" \
    --argjson counters "$counters" --argjson samples "$samples" \
    '{name: $n, plannedSamples: $secs, from: $from, to: $to, counters: $counters, sampler: $samples}'
}

scenario_run() {
  local k probe_secs hmax min_busy warmup background sampler_secs sampler_pid
  local idle exhausted recovered hik0 hik1 reset holds_drain window saturate pending sampler_all
  local prefix="${RUN_ID}-hold-" start wait_start busy
  if [[ "$PROFILE" == full ]]; then k=10 probe_secs=75; else k=5 probe_secs=35; fi
  hmax=$(jq '.nodes[0].hikariMaximumPoolSize' "${RESULTS}/identity.json")
  if ! [[ "$hmax" =~ ^[0-9]+$ ]] || ((hmax < 2)); then
    die "s5: node 1 reports hikariMaximumPoolSize '${hmax}'"
  fi
  min_busy=$((hmax - 1))

  log "s5: warm-up"
  warmup=$(warm_up 20 10 '{"type":"chain","activities":2,"activityMillis":10}')
  sleep 2
  background=$(measure_background)

  dc exec -T postgres psql -U postgres -d maestro -q -v ON_ERROR_STOP=1 -f - \
    <"${PERF_DIR}/sql/activity-sampler.sql" >>"${RESULTS}/harness.log" 2>&1
  # Long enough for every phase; cancelled at the end.
  sampler_secs=$((S5_IDLE_SECS + S5_SATURATE_SECS + 2 * S5_H * k * S5_HOLD_MILLIS / 1000 / min_busy + probe_secs + S5_RECOVERED_SECS + 180))
  dc exec -T postgres psql -U postgres -d maestro -q -c \
    "CALL perf_harness.sample_activity(${sampler_secs}, 's5', 'pg_sleep')" </dev/null >>"${RESULTS}/harness.log" 2>&1 &
  sampler_pid=$!
  sleep 2

  log "s5: idle health, ${S5_IDLE_SECS} s"
  idle=$(s5_health_phase idle "$S5_IDLE_SECS" "$min_busy")

  log "s5: exhausting the pool: ${S5_H} hold runs x ${k} x ${S5_HOLD_MILLIS} ms"
  hik0=$(s5_hikari)
  reset=$(psql_json pgss-reset)
  k6_run holds start-batch.js "PERF_ID_PREFIX=${prefix}" "PERF_ID_OFFSET=0" "PERF_COUNT=${S5_H}" \
    "PERF_VUS=${S5_H}" "PERF_MAX_DURATION=1m" \
    "PERF_BODY=$(jq -nc --argjson k "$k" --argjson h "$S5_HOLD_MILLIS" '{type: "hold", activities: $k, holdMillis: $h}')"
  wait_start=$(date +%s)
  saturate=false
  while (($(date +%s) - wait_start < S5_SATURATE_SECS)); do
    busy=$(dc exec -T postgres psql -U postgres -d maestro -At -c \
      "SELECT coalesce(max(matching), 0) FROM perf_harness.activity_sample WHERE label = 's5' AND at > now() - interval '1 second'" </dev/null)
    if ((busy >= min_busy)); then
      saturate=true
      break
    fi
    sleep 1
  done
  saturate=$(jq -nc --argjson ok "$saturate" --argjson w "$(($(date +%s) - wait_start))" --argjson min "$min_busy" \
    '{reached: $ok, waitedSecs: $w, minBusy: $min}')
  log "s5: saturation $(jq -c . <<<"$saturate")"
  exhausted=$(s5_health_phase exhausted "$probe_secs" "$min_busy")
  pending=$(prom_instant_checked "max(max_over_time(hikaricp_connections_pending[${probe_secs}s]))" "$(date +%s)")
  holds_drain=$(wait_terminal "$prefix" "$S5_H" 600 2)
  window=$(psql_json pgss-window)
  log "s5: holds $(jq -c '{byStatus: .status.byStatus, waitedSecs, timedOut}' <<<"$holds_drain")"

  log "s5: recovered health, ${S5_RECOVERED_SECS} s"
  recovered=$(s5_health_phase recovered "$S5_RECOVERED_SECS" "$min_busy")
  hik1=$(s5_hikari)

  # Stop the sampler: cancel its own session (it runs as postgres, by query text).
  dc exec -T postgres psql -U postgres -d maestro -At -c \
    "SELECT count(pg_cancel_backend(pid)) FROM pg_stat_activity WHERE query LIKE 'CALL perf_harness.sample_activity(%' AND pid <> pg_backend_pid()" \
    </dev/null >>"${RESULTS}/harness.log" 2>&1
  wait "$sampler_pid" || true
  start=$(jq -r '.from' <<<"$idle")
  sampler_all=$(psql_json activity-samples -v label=s5 -v "min=${min_busy}" -v "from=${start}")

  jq -n \
    --argjson h "$S5_H" --argjson k "$k" --argjson hold "$S5_HOLD_MILLIS" --argjson hmax "$hmax" \
    --argjson secs "{\"idle\": ${S5_IDLE_SECS}, \"exhausted\": ${probe_secs}, \"recovered\": ${S5_RECOVERED_SECS}}" \
    --argjson warmup "$warmup" --argjson background "$background" --argjson k6 "$(k6_records)" \
    --argjson idle "$idle" --argjson exhausted "$exhausted" --argjson recovered "$recovered" \
    --argjson saturate "$saturate" --argjson pending "$pending" --argjson hik0 "$hik0" --argjson hik1 "$hik1" \
    --argjson reset "$reset" --argjson window "$window" --argjson holds "$holds_drain" --argjson samplerAll "$sampler_all" \
    '{plan: {holdRuns: $h, activities: $k, holdMillis: $hold, hikariMaximumPoolSize: $hmax, minBusy: ($hmax - 1),
             probeSecs: $secs},
      warmup: $warmup, background: $background, k6: $k6,
      phases: [$idle, $exhausted, $recovered],
      saturation: $saturate, pendingMax: $pending,
      hikari: {start: $hik0, end: $hik1}, sampler: $samplerAll,
      pgssReset: $reset, windows: [$window],
      holds: {prefix: "hold-", drain: {waitedSecs: $holds.waitedSecs, timedOut: $holds.timedOut}, runStatus: $holds.status},
      phaseTable: [$idle, $exhausted, $recovered] | map(.name as $n | .counters as $c | {phase: $n,
        up: $c.maestro_up, unknown: $c.maestro_unknown,
        down: (if $c == null then null else ($c.maestro_down_store_timed_out + $c.maestro_down_store_unreachable + $c.maestro_down_other) end),
        noAnswer: $c.maestro_no_answer, rootUp: $c.root_up, rootDown: $c.root_down, rootNoAnswer: $c.root_no_answer,
        maestroP50Ms: $c.maestroAnswerMs.p50, maestroMaxMs: $c.maestroAnswerMs.max,
        busySamples: "\(.sampler.matchingSamples)/\(.sampler.samples)"})}' \
    >"${RESULTS}/measurements.json"
}
