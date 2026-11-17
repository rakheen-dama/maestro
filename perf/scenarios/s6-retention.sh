#!/usr/bin/env bash
# Scenario 6 (s6-retention, WP12; findings 3.11a and 3.11b, fixed by WP8):
# the retention sweeper's and the signal janitor's startup pass over a large
# terminal backlog, against foreground load. Sourced by perf/run.sh, which
# provides RUN_ID, RESULTS, REPLICAS (exactly 2), PROFILE, lib/common.sh and
# lib/topology.sh. Writes ${RESULTS}/measurements.json;
# perf/eval/s6-retention.jq judges it.
#
# Trigger (MD-8): node 2 is stopped right after setup and started again after
# seeding; the sweep measured is ITS startup sweep (retention and janitor run
# once at node start, at true defaults). The foreground runs on node 1 only,
# which stays warm throughout. Limit, stated: the sweeping node's own pool is
# not under foreground load.
#
# Template: one perf-park run (signal mode, parkSeconds 7200) released by
# perf.release, so COMPLETED with its events, its cancelled await-timeout
# timer row and one consumed signal row; s6-template.sql copies it and
# s6-seed.sql clones it into the sets (sizes below; ages, statuses and
# services in s6-seed.sql's header):
#
#   profile  rc      rt      rf      young  jown    jfailed  jforeign  xforeign
#   local    51000   5000    5000    1000   10000   1000     10000     1000
#   full     50000   50000   50000   1000   50000   1000     50000     1000
#
# Retention budget per status per sweep: maestro.retention.batch-size (500) x
# max-batches-per-sweep (100) = 50,000 instances, oldest first (defaults; the
# sample sets neither). So rc keeps rc - min(rc, 50,000): 1,000 local.
#
# Windows (pg_stat_statements): pre (foreground only, node 2 down), sweep
# (pgss reset, then node 2 started; closed when the sweep is over: rc at its
# remainder, rt and rf empty, jown's unconsumed signals gone, node 2's
# maestro_retention_sweeps_total >= 1; flagged sweepWindow: validity.sweeps
# exempts it from the janitor/retention bounds), post (to the foreground's
# end). The foreground is chain(3 x 20 ms) at 5/s on node 1 for pre +
# sweep allowance + post (local 60 + 180 + 60 s, full 60 + 600 + 60 s);
# each probe is assigned to pre / during / post afterwards by its started_at
# against the sampler's first and last purge sample.
#
# The in-database sampler (label s6) counts engine backends active in a purge
# statement (the candidates SELECT, and the four batched DELETEs anchored on
# "WHERE workflow_instance_id IN (" / "maestro_workflow_instance WHERE id IN
# ("). A janitor DELETE, batched or not, never matches them.
#
# Test-only: PERF_S6_STOP_AFTER=seed writes the measurements after the seeded
# read and starts node 2 again (its sweep then runs unmeasured).
# shellcheck shell=bash
# SC2016: the single-quoted '$x' strings are jq filters, expanded by jq.
# shellcheck disable=SC2016

S6_BUDGET=50000
S6_PRE_SECS=60
S6_POST_SECS=60
S6_FG_RATE=5
# Purge statements, as pg_stat_activity shows the JDBC text.
S6_PURGE_RE='^(SELECT id FROM maestro_workflow_instance WHERE status = \$1 AND completed_at < |DELETE FROM maestro_workflow_(signal|timer|event) WHERE workflow_instance_id IN \(|DELETE FROM maestro_workflow_instance WHERE id IN \()'

# maestro_retention_sweeps_total of node <i> (0 when the series is absent:
# the counter registers on its first increment), and purged per kind.
s6_counters() {
  local scrape
  scrape=$(http_get "$(node_url "$1")/actuator/prometheus" 2>/dev/null) || { printf '{"ok":false}'; return 0; }
  awk -v at="$(epoch_now)" '
    function val(line) { n = split(line, f, " "); return f[n] }
    /^maestro_retention_sweeps_total[ {]/ { s += val($0) }
    /^maestro_retention_purged_total\{/ { k = $0; sub(/.*kind="/, "", k); sub(/".*/, "", k); p[k] += val($0) }
    END {
      printf "{\"ok\":true,\"at\":%s,\"sweeps\":%d,\"purged\":{", at, s
      sep = ""; for (k in p) { printf "%s\"%s\":%d", sep, k, p[k]; sep = "," }
      printf "}}\n"
    }' <<<"$scrape"
}

scenario_run() {
  local sizes allowance warmup background tmpl_id final template seed seeded
  local fg_prefix="${RUN_ID}-fg-" fg_pid sampler_pid pre_window sweep_window post_window sweep_comp
  local wal0 wal1 n1_0 n1_1 n2_1 jvm2 t_start end_status waited remainder sampler fg_lat status_end sweep_reset
  if [[ "$PROFILE" == full ]]; then
    sizes=(50000 50000 50000 1000 50000 1000 50000 1000) allowance=600
  else
    sizes=(51000 5000 5000 1000 10000 1000 10000 1000) allowance=180
  fi
  remainder=$((sizes[0] > S6_BUDGET ? sizes[0] - S6_BUDGET : 0))

  log "s6: warm-up, $((20 * REPLICAS)) runs"
  warmup=$(warm_up "$((20 * REPLICAS))" "$((10 * REPLICAS))" '{"type":"chain","activities":2,"activityMillis":10}')
  sleep 2
  background=$(measure_background)

  # The template: a released park run.
  tmpl_id="${RUN_ID}-tmpl-0"
  http_get "$(node_url 1)/perf/workflows" -X POST -H 'Content-Type: application/json' \
    -d "{\"workflowId\":\"${tmpl_id}\",\"type\":\"park\",\"parkMode\":\"signal\",\"parkSeconds\":7200}" >/dev/null \
    || die "s6: the template start was refused"
  sleep 2
  http_get "$(node_url 1)/perf/workflows/${tmpl_id}/signals/perf.release" -X POST >/dev/null \
    || die "s6: the template release was refused"
  final=$(wait_terminal "${RUN_ID}-tmpl-" 1 60 1)
  template=$(psql_json s6-template -v "wf=${tmpl_id}")
  log "s6: template $(jq -c . <<<"$template")"
  [[ "$(jq -r .captured <<<"$template")" == true ]] || die "s6: the template run did not complete"

  node_stop 2
  log "s6: seeding $(IFS=,; echo "${sizes[*]}")"
  seed=$(psql_json s6-seed -v "prefix=${RUN_ID}-s6-" -v "rc=${sizes[0]}" -v "rt=${sizes[1]}" -v "rf=${sizes[2]}" \
    -v "young=${sizes[3]}" -v "jown=${sizes[4]}" -v "jfailed=${sizes[5]}" -v "jforeign=${sizes[6]}" -v "xforeign=${sizes[7]}")
  seeded=$(psql_json s6-status -v "prefix=${RUN_ID}-s6-")
  log "s6: seeded in $(jq -r .seconds <<<"$seed") s: $(jq -c .sets <<<"$seeded")"

  if [[ "${PERF_S6_STOP_AFTER:-}" == seed ]]; then
    node_start 2 >/dev/null
    jq -n --argjson warmup "$warmup" --argjson background "$background" --argjson k6 "$(k6_records)" \
      --argjson topology "$(topology_records)" --argjson template "$template" \
      --argjson final "$(jq -c '.status.byStatus' <<<"$final")" --argjson seed "$seed" --argjson seeded "$seeded" \
      '{warmup: $warmup, background: $background, k6: $k6, topology: $topology,
        template: ($template + {finalByStatus: $final}), seed: $seed, seeded: $seeded, stoppedAfter: "seed"}' \
      >"${RESULTS}/measurements.json"
    return 0
  fi

  dc exec -T postgres psql -U postgres -d maestro -q -v ON_ERROR_STOP=1 -f - \
    <"${PERF_DIR}/sql/activity-sampler.sql" >>"${RESULTS}/harness.log" 2>&1

  # Foreground on node 1, and the pre window.
  psql_json pgss-reset >/dev/null
  K6_NODES=$(node_url 1) k6_run foreground start-rate.js "PERF_ID_PREFIX=${fg_prefix}" "PERF_ID_OFFSET=0" \
    "PERF_RATE=${S6_FG_RATE}" "PERF_DURATION=$((S6_PRE_SECS + allowance + S6_POST_SECS))" \
    'PERF_BODY={"type":"chain","activities":3,"activityMillis":20}' &
  fg_pid=$!
  sleep "$S6_PRE_SECS"
  pre_window=$(psql_json pgss-window)

  # Trigger: the sampler, the sweep window, node 2.
  n1_0=$(s6_counters 1)
  dc exec -T postgres psql -U postgres -d maestro -q -c \
    "CALL perf_harness.sample_activity($((allowance + 60)), 's6', '${S6_PURGE_RE}')" </dev/null >>"${RESULTS}/harness.log" 2>&1 &
  sampler_pid=$!
  sweep_reset=$(psql_json pgss-reset)
  wal0=$(dc exec -T postgres psql -U postgres -d maestro -At -c 'SELECT wal_bytes FROM pg_stat_wal' </dev/null)
  jvm2=$(node_start 2)
  t_start=$(date +%s)
  log "s6: node 2 started (pid $(jq -r .pid <<<"$jvm2")); waiting for its sweep (<= ${allowance} s)"
  while :; do
    end_status=$(psql_json s6-status -v "prefix=${RUN_ID}-s6-")
    n2_1=$(s6_counters 2)
    if jq -e --argjson r "$remainder" --argjson n2 "$n2_1" '
        (.sets.rc.instances // 0) == $r and (.sets.rt.instances // 0) == 0 and (.sets.rf.instances // 0) == 0
        and (.sets.jown.signalsUnconsumed // 0) == 0 and ($n2.sweeps // 0) >= 1' <<<"$end_status" >/dev/null; then
      break
    fi
    (($(date +%s) - t_start < allowance)) || { log "s6: the sweep did not end within ${allowance} s"; break; }
    sleep 2
  done
  waited=$(($(date +%s) - t_start))
  sweep_window=$(psql_json pgss-window | jq -c '. + {sweepWindow: true}')
  sweep_comp=$(psql_json s6-sweep)
  wal1=$(dc exec -T postgres psql -U postgres -d maestro -At -c 'SELECT wal_bytes FROM pg_stat_wal' </dev/null)
  log "s6: sweep over after ${waited} s: $(jq -c .components <<<"$sweep_comp")"

  # Post window, to the foreground's end.
  psql_json pgss-reset >/dev/null
  wait "$fg_pid" || true
  post_window=$(psql_json pgss-window)
  n1_1=$(s6_counters 1)
  n2_1=$(s6_counters 2)
  dc exec -T postgres psql -U postgres -d maestro -At -c \
    "SELECT count(pg_cancel_backend(pid)) FROM pg_stat_activity WHERE query LIKE 'CALL perf_harness.sample_activity(%' AND pid <> pg_backend_pid()" \
    </dev/null >>"${RESULTS}/harness.log" 2>&1
  wait "$sampler_pid" || true
  sampler=$(psql_json activity-samples -v label=s6)
  wait_terminal "$fg_prefix" 1 120 2 >/dev/null
  fg_lat=$(psql_json s6-foreground -v "prefix=${fg_prefix}" \
    -v "a=$(jq -r '.firstMatchAt // "infinity"' <<<"$sampler")" -v "b=$(jq -r '.lastMatchAt // "infinity"' <<<"$sampler")")
  status_end=$(psql_json s6-status -v "prefix=${RUN_ID}-s6-")
  log "s6: sampler $(jq -c '{samples, matchingSamples, spanSamples, matchingInSpan, maxGapSecs}' <<<"$sampler"); foreground $(jq -c . <<<"$fg_lat")"

  jq -n \
    --argjson sizes "$(printf '%s\n' "${sizes[@]}" | jq -sc .)" --argjson budget "$S6_BUDGET" --argjson remainder "$remainder" \
    --argjson pre "$S6_PRE_SECS" --argjson allowance "$allowance" --argjson post "$S6_POST_SECS" \
    --argjson warmup "$warmup" --argjson background "$background" --argjson k6 "$(k6_records)" \
    --argjson topology "$(topology_records)" --argjson template "$template" \
    --argjson final "$(jq -c '.status.byStatus' <<<"$final")" --argjson seed "$seed" --argjson seeded "$seeded" \
    --argjson preW "$pre_window" --argjson sweepW "$sweep_window" --argjson postW "$post_window" \
    --argjson sweepReset "$sweep_reset" --argjson comp "$sweep_comp" --argjson wal0 "${wal0:-null}" --argjson wal1 "${wal1:-null}" \
    --argjson waited "$waited" --argjson sampler "$sampler" --argjson fg "$fg_lat" --argjson end "$status_end" \
    --argjson n10 "$n1_0" --argjson n11 "$n1_1" --argjson n21 "$n2_1" \
    '{plan: {sets: ({rc: $sizes[0], rt: $sizes[1], rf: $sizes[2], young: $sizes[3], jown: $sizes[4],
                     jfailed: $sizes[5], jforeign: $sizes[6], xforeign: $sizes[7]}),
             retentionBudget: $budget, rcRemainder: $remainder,
             windowSecs: {pre: $pre, sweepAllowance: $allowance, post: $post}, foregroundRatePerSec: 5},
      warmup: $warmup, background: $background, k6: $k6, topology: $topology,
      template: ($template + {finalByStatus: $final}), seed: $seed, seeded: $seeded,
      windows: [$preW + {phase: "pre"}, $sweepW + {phase: "sweep"}, $postW + {phase: "post"}],
      sweep: {waitedSecs: $waited, ended: ($waited < $allowance), pgssReset: $sweepReset, components: $comp.components,
              walBytes: (if ($wal0 | type) == "number" and ($wal1 | type) == "number" then $wal1 - $wal0 else null end)},
      sampler: $sampler, foreground: $fg,
      counters: {node1: {before: $n10, after: $n11}, node2: {after: $n21}},
      final: $end,
      phaseTable: ([$preW, $sweepW, $postW] | to_entries
                   | map({window: (["pre", "sweep", "post"][.key]), windowSecs: .value.windowSecs,
                          maestroCalls: .value.maestroCalls, janitorCalls: .value.sweepCalls.signalJanitor,
                          retentionCalls: .value.sweepCalls.retention,
                          foregroundP95Ms: ($fg[["pre", "during", "post"][.key]].p95Ms // null)}))}' \
    >"${RESULTS}/measurements.json"
}
