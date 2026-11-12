#!/usr/bin/env bash
# Smoke: 20 short chain workflows end to end, with every measurement path of
# the harness exercised once. Sourced by perf/run.sh, which provides RUN_ID,
# RESULTS, REPLICAS and lib/common.sh. Writes ${RESULTS}/measurements.json.
# shellcheck shell=bash

scenario_run() {
  local prefix="${RUN_ID}-smoke-"
  local count=20
  local body='{"type":"chain","activities":2,"activityMillis":10}'
  local reset drain window latency foreign

  log "smoke: pg_stat_statements reset"
  reset=$(psql_json pgss-reset)

  k6_run smoke start-batch.js \
    "PERF_ID_PREFIX=${prefix}" "PERF_ID_OFFSET=0" "PERF_COUNT=${count}" \
    "PERF_VUS=${PERF_K6_VUS:-10}" "PERF_MAX_DURATION=2m" "PERF_BODY=${body}"

  log "smoke: waiting (≤ 120 s) for ${count} runs of ${prefix} to be terminal"
  drain=$(wait_terminal "$prefix" "$count" 120 2)
  log "smoke: $(jq -c '{byStatus: .status.byStatus, waitedSecs, timedOut}' <<<"$drain")"

  window=$(psql_json pgss-window)
  latency=$(psql_json latency -v "prefix=${prefix}")
  foreign=$(psql_json foreign-rows -v "prefix=${prefix}")
  log "smoke: window $(jq -c '{windowSecs, dealloc, maestroCalls, harnessCalls}' <<<"$window"), foreign rows ${foreign}"

  jq -n \
    --arg prefix "$prefix" --argjson expected "$count" --argjson body "$body" \
    --argjson reset "$reset" --argjson drain "$drain" --argjson window "$window" \
    --argjson latency "$latency" --argjson foreign "$foreign" --argjson k6 "$(k6_records)" \
    '{idPrefix: $prefix, expected: $expected,
      workflowShape: ($body | {type, activities, activityMillis}),
      pgssReset: $reset, k6: $k6,
      drain: {waitedSecs: $drain.waitedSecs, timedOut: $drain.timedOut},
      runStatus: $drain.status, window: $window, latency: $latency, foreignRows: $foreign}' \
    >"${RESULTS}/measurements.json"
}
