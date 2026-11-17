#!/usr/bin/env bash
# Scenario 2 (s2-recovery-drain, WP12; finding 3.1 / Issue 30, fixed by WP6):
# how a node's recovery takes over a backlog of orphaned runs. One node
# drains N = 5000 orphaned RUNNING runs (phase A); then the same backlog is
# drained by a ROLLING start of two nodes (phase B: node 2 starts 5 s after
# node 1 reads ready, as a Kubernetes rolling deploy). Sourced by
# perf/run.sh, which provides RUN_ID, RESULTS, REPLICAS (exactly 2), PROFILE,
# lib/common.sh and lib/topology.sh. Writes ${RESULTS}/measurements.json;
# perf/eval/s2-recovery-drain.jq judges it.
#
# Workload: perf-chain, 2 activities of d = 2000 ms (both profiles, MD-11).
# C = the nodes' workerDefaultConcurrency (/perf/info, 20 by default).
#
#   profile  N     d        probes   phases                               drain timeout
#   local    5000  2000 ms  0.2/s    A, B                                 1800 s per phase
#   full     5000  2000 ms  0.2/s    A, B, C (600 rows; 15/s fresh, 120 s) 1800 s per phase
#
# Seeding (MD-4): a consistent mid-run snapshot, cloned by SQL. One template
# run ${RUN_ID}-tmpl-0 is started on node 1; perf/sql/s2-template.sql polls
# it (every ~200 ms plus the psql call) and, in one repeatable-read
# transaction, copies its instance row and events once it is RUNNING with
# exactly one ACTIVITY_COMPLETED (step 0 done, step 1 not). The template then
# finishes. With every node stopped, perf/sql/s2-seed.sql inserts N clones
# per phase (${RUN_ID}-s2a-<i>, -s2b-<i>, -s2c-<i>): each is exactly a run
# whose node died after step 0 completed and before step 1's outcome, so
# recovery replays step 0 from its memo (args_hash included) and runs step 1
# live (d).
#
# Fidelity limits: no instance-lock rows (the crashed node's locks have
# expired: a real backlog reaches that 30 s after the crash); one history
# shape, every run identical; no signal or timer rows; the clones never went
# through startWorkflow (no orphaned-signal adoption).
#
# Order:
#  1. Setup: warm-up on both nodes (outside every window), the idle window
#     (background), the template and its snapshot; both nodes stopped
#     (docker stop -t 40); then an EMPTY-DATABASE start of node 1 and node 2
#     in turn (readiness timed, the comparison for advisory.readiness), both
#     stopped again.
#  2. Phase A (one node): seed s2a, its seeded read (validity.seeded), pgss
#     reset, t0 = database now(), start node 1. From node 1's first answer,
#     probes (chain 1 x d, ${RUN_ID}-p2a-<i>) go to node 1 only (K6_NODES) at
#     0.2/s, in k6 runs of 30 s, until the drain ends; readiness is polled
#     every second. Wait until every s2a row is terminal (<= 1800 s), stop
#     the probes, wait for them, close the window (pgss-window +
#     s3-components + s2-recovery), read each node's own counters, stop
#     node 1.
#  3. Phase B (rolling): seed s2b, the same with node 1; when node 1 reads
#     ready, wait 5 s, start node 2. Probes go round-robin over the nodes
#     that are up. Drain, close, as A. Both nodes stay up.
#  4. Phase C (full only, C2): stop both, seed 600 s2c rows, start node 1
#     and offer fresh chain(1 x d) starts at 15/s for 120 s during the drain;
#     recovered completions per minute. Ends with node 2 started.
# Every run ends with both replicas up (run.sh's post-run collect_nodes).
#
# Per phase it records: drain time on the database clock (the phase rows'
# max(completed_at) - t0), each node's JVM, readiness-UP time and delay
# (readiness UP - docker start, host clock), its adoptions and completions
# (its own /actuator/prometheus at the phase's end: every phase runs fresh
# JVMs, so the counters are the phase's), the hoard every 5 s per node (from
# its own scrape: maestro_workflows_running - maestro_workflows_parked -
# maestro_taskqueue_slots_used{kind="workflow"}), probe start-to-complete
# p50/p95 (instance rows), and the recovery statements (lockAcquire,
# recoveryPage, heldKeys: s3-components.sql; adoptCas: s2-recovery.sql).
#
# Test-only: PERF_S2_STOP_AFTER=seed writes the measurements after phase A's
# seeded read, deletes the seeded rows and starts both nodes again (so the
# run ends normally, without a drain).
# shellcheck shell=bash
# SC2016: the single-quoted '$x' strings are jq filters, expanded by jq.
# shellcheck disable=SC2016

S2_N=5000
S2_D=2000
S2_TIMEOUT=1800
S2_PROBE_SECS=30   # one probe k6 run: 1 start per 5 s for 30 s = 6 starts
S2_PROBES_PER_RUN=6
S2_ROLLING_DELAY=5
S2_HOARD_SECS=5

# The base URLs of the nodes that are up, for the probes (a file, so the
# background probe loop sees phase B's second node).
s2_up_nodes_file() { printf '%s/.s2-up-nodes' "$RESULTS"; }

# Reads the gauges and counters S2 needs from node <i>'s own scrape, as one
# JSON object (null fields when absent, e.g. on an engine without a meter).
s2_node_metrics() {
  local i=$1 scrape
  scrape=$(http_get "$(node_url "$i")/actuator/prometheus" 2>/dev/null) || { printf 'null'; return 0; }
  awk -v c="$(node_container "$i")" -v at="$(epoch_now)" '
    function val(line) { n = split(line, f, " "); return f[n] }
    /^maestro_workflows_running[ {]/ { running = val($0) }
    /^maestro_workflows_parked[ {]/ { parked = val($0) }
    /^maestro_taskqueue_slots_used\{/ && /kind="workflow"/ { slots += val($0); hasSlots = 1 }
    /^maestro_recovery_adopted_total[ {]/ { adopted = val($0) }
    /^maestro_workflow_completed_total\{/ && /workflow="perf-chain"/ { completed += val($0); hasCompleted = 1 }
    END {
      printf "{\"at\":%s,\"container\":\"%s\",\"running\":%s,\"parked\":%s,\"slotsWorkflow\":%s,\"adopted\":%s,\"completed\":%s}\n",
        at, c, (running == "" ? "null" : running), (parked == "" ? "null" : parked),
        (hasSlots ? slots : "null"), (adopted == "" ? "null" : adopted), (hasCompleted ? completed : "null")
    }' <<<"$scrape"
}

# Background loop: every S2_HOARD_SECS, every up node's metrics to
# hoard-<phase>.jsonl, until <stop-file> exists, the harness is gone or the
# phase's deadline (<deadline epoch>) has passed (bg_should_continue,
# lib/common.sh); its pid is registered, so run.sh's on_exit kills it.
s2_hoard_loop() {
  local phase=$1 stop=$2 deadline=$3 url i
  while [[ ! -e "$stop" ]] && bg_should_continue "$deadline"; do
    while IFS= read -r url || [[ -n "$url" ]]; do
      [[ -n "$url" ]] || continue
      i=${url##*-node-}
      i=${i%%:*}
      s2_node_metrics "$i" >>"${RESULTS}/hoard-${phase}.jsonl"
    done < <(tr ',' '\n' <"$(s2_up_nodes_file)")
    sleep "$S2_HOARD_SECS"
  done
}

# Background loop: probe k6 runs (30 s each, 6 starts of chain(1 x d) at
# 0.2/s) to the nodes listed in the up-nodes file, until <stop-file> exists,
# the harness is gone or <deadline epoch> has passed (as the hoard loop).
s2_probe_loop() {
  local phase=$1 stop=$2 prefix=$3 deadline=$4 k=0
  local body
  body=$(jq -nc --argjson d "$S2_D" '{type: "chain", activities: 1, activityMillis: $d}')
  while [[ ! -e "$stop" ]] && bg_should_continue "$deadline"; do
    K6_NODES=$(cat "$(s2_up_nodes_file)") k6_run "probe${phase}-${k}" start-rate.js \
      "PERF_ID_PREFIX=${prefix}" "PERF_ID_OFFSET=$((k * S2_PROBES_PER_RUN))" "PERF_COUNT=${S2_PROBES_PER_RUN}" \
      "PERF_RATE=1" "PERF_TIME_UNIT=5" "PERF_DURATION=${S2_PROBE_SECS}" "PERF_BODY=${body}"
    k=$((k + 1))
  done
  printf '%s\n' "$((k * S2_PROBES_PER_RUN))" >"${RESULTS}/.s2-probes-${phase}"
}

# No node container may be running while a phase is seeded (the SQL refuses
# a connected engine too). Read-only.
s2_assert_nodes_down() {
  local up
  up=$(docker ps -q --filter "label=com.docker.compose.project=${COMPOSE_PROJECT}" \
    --filter "label=com.docker.compose.service=node" --filter status=running | wc -l | tr -d ' ')
  ((up == 0)) || die "s2: ${up} node container(s) still running before seeding"
}

# One phase's start record for node <i>: {node, container, startIssuedAt,
# dbStartIssuedAt, jvmStartMillis, infoAt, readyAt, readinessDelaySecs}.
s2_start_record() { # <i> <jvm json> <readyAt or empty>
  local rec
  rec=$(jq -c --arg c "$(node_container "$1")" '[.[] | select(.action == "start" and .container == $c)] | last' \
    <<<"$(topology_records)")
  jq -nc --argjson i "$1" --argjson jvm "$2" --argjson rec "$rec" --arg ready "${3:-}" '
    ($ready | if . == "" then null else tonumber end) as $r
    | {node: $i, container: $jvm.container, startIssuedAt: $rec.at, dbStartIssuedAt: $rec.dbNow,
       jvmStartMillis: $jvm.jvmStartMillis, pid: $jvm.pid, infoAt: $rec.infoAt, readyAt: $r,
       readinessDelaySecs: (if $r == null then null else ($r - $rec.at) * 1000 | round / 1000 end)}'
}

# Runs one drain phase. Prints its record.
# s2_phase <name A|B|C> <rows> <rolling 0|1> [<fresh-start rate per s> <fresh secs>]
s2_phase() {
  local name=$1 rows=$2 rolling=$3 fresh_rate=${4:-} fresh_secs=${5:-}
  local lc
  lc=$(tr '[:upper:]' '[:lower:]' <<<"$name")
  local prefix="${RUN_ID}-s2${lc}-" probe_prefix="${RUN_ID}-p2${lc}-" fresh_prefix="${RUN_ID}-f2${lc}-"
  local seed seeded reset t0 jvm1 ready1="" jvm2="" ready2="" n2rec="null" drain stop_probes stop_hoard
  local probe_pid hoard_pid deadline window components recovery status probes probe_status nodes_end i
  local fresh_k6="null" per_minute="null"

  s2_assert_nodes_down
  seed=$(psql_json s2-seed -v "prefix=${prefix}" -v "n=${rows}")
  seeded=$(psql_json s2-status -v "prefix=${prefix}")
  log "s2 ${name}: seeded $(jq -c '{total, byStatus, events, lockRows}' <<<"$seeded")"

  if [[ "${PERF_S2_STOP_AFTER:-}" == seed ]]; then
    jq -nc --arg n "$name" --arg p "$prefix" --argjson seed "$seed" --argjson seeded "$seeded" \
      '{name: $n, prefix: $p, rows: ($seed.seeded), seed: $seed, seeded: $seeded, stoppedAfter: "seed"}'
    return 0
  fi

  reset=$(psql_json pgss-reset)
  t0=$(db_now_json)
  printf '%s' "$(node_url 1)" >"$(s2_up_nodes_file)"
  stop_probes="${RESULTS}/.s2-stop-probes-${lc}"
  stop_hoard="${RESULTS}/.s2-stop-hoard-${lc}"
  rm -f "$stop_probes" "$stop_hoard"
  jvm1=$(node_start 1)
  # Both loops end at the latest a minute past the phase's whole budget
  # (drain timeout, plus readiness and the probes' own wait).
  deadline=$(($(date +%s) + 2 * S2_TIMEOUT + 300))
  s2_hoard_loop "$lc" "$stop_hoard" "$deadline" &
  hoard_pid=$!
  bg_register "$hoard_pid"
  s2_probe_loop "$lc" "$stop_probes" "$probe_prefix" "$deadline" &
  probe_pid=$!
  bg_register "$probe_pid"
  if [[ -n "$fresh_rate" ]]; then
    K6_NODES=$(node_url 1) k6_run "fresh${lc}" start-rate.js \
      "PERF_ID_PREFIX=${fresh_prefix}" "PERF_ID_OFFSET=0" "PERF_RATE=${fresh_rate}" \
      "PERF_DURATION=${fresh_secs}" "PERF_K6_VUS=50" "PERF_K6_MAX_VUS=200" \
      "PERF_BODY=$(jq -nc --argjson d "$S2_D" '{type: "chain", activities: 1, activityMillis: $d}')" &
    fresh_k6=$!
    bg_register "$fresh_k6"
  fi
  ready1=$(node_wait_ready 1 "$S2_TIMEOUT") || ready1=""
  log "s2 ${name}: node 1 ready at ${ready1:-never}"
  if ((rolling)); then
    [[ -n "$ready1" ]] || die "s2 ${name}: node 1 never read ready; no rolling start"
    sleep "$S2_ROLLING_DELAY"
    jvm2=$(node_start 2)
    printf '%s,%s' "$(node_url 1)" "$(node_url 2)" >"$(s2_up_nodes_file)"
    ready2=$(node_wait_ready 2 "$S2_TIMEOUT") || ready2=""
    log "s2 ${name}: node 2 ready at ${ready2:-never}"
  fi

  drain=$(wait_terminal "$prefix" "$rows" "$S2_TIMEOUT" 5)
  log "s2 ${name}: drain $(jq -c '{byStatus: .status.byStatus, waitedSecs, timedOut}' <<<"$drain")"
  touch "$stop_probes"
  wait "$probe_pid" || true
  if [[ "$fresh_k6" != null ]]; then
    wait "$fresh_k6" || true
  fi
  probes=$(cat "${RESULTS}/.s2-probes-${lc}" 2>/dev/null || echo 0)
  probe_status=$(wait_terminal "$probe_prefix" "$probes" 120 2)
  if [[ -n "$fresh_rate" ]]; then
    wait_terminal "$fresh_prefix" 1 600 5 >/dev/null
  fi
  touch "$stop_hoard"
  wait "$hoard_pid" || true

  window=$(psql_json pgss-window)
  components=$(psql_json s3-components)
  recovery=$(psql_json s2-recovery)
  status=$(psql_json s2-status -v "prefix=${prefix}")
  nodes_end="[]"
  for i in 1 2; do
    if [[ "$i" == 1 || "$rolling" == 1 ]]; then
      nodes_end=$(jq -c --argjson m "$(s2_node_metrics "$i")" '. + [$m]' <<<"$nodes_end")
    fi
  done
  if [[ -n "$fresh_rate" ]]; then
    per_minute=$(psql_json s2-per-minute -v "prefix=${prefix}" -v "t0=$(jq -r . <<<"$t0")")
  fi
  log "s2 ${name}: window $(jq -c '{windowSecs, maestroCalls, sweepCalls}' <<<"$window"), recovery $(jq -c . <<<"$recovery")"

  if ((rolling)); then
    n2rec=$(s2_start_record 2 "$jvm2" "$ready2")
  fi
  jq -nc --arg n "$name" --arg p "$prefix" --arg pp "$probe_prefix" --argjson rows "$rows" --argjson rolling "$rolling" \
    --argjson seed "$seed" --argjson seeded "$seeded" --argjson reset "$reset" --argjson t0 "$t0" \
    --argjson n1 "$(s2_start_record 1 "$jvm1" "$ready1")" --argjson n2 "$n2rec" \
    --argjson drain "$drain" --argjson window "$window" --argjson components "$components" \
    --argjson recovery "$recovery" --argjson status "$status" --argjson nodesEnd "$nodes_end" \
    --argjson hoard "$(jq -s -c . "${RESULTS}/hoard-${lc}.jsonl" 2>/dev/null || echo '[]')" \
    --argjson probes "$probes" --argjson probeStatus "$probe_status" \
    --argjson probeLatency "$(psql_json latency -v "prefix=${probe_prefix}")" \
    --argjson perMinute "$per_minute" \
    '{name: $n, prefix: $p, rows: $rows, rolling: ($rolling == 1), seed: $seed, seeded: $seeded,
      pgssReset: $reset, t0: $t0,
      starts: ([$n1, $n2] | map(select(. != null))),
      drain: {waitedSecs: $drain.waitedSecs, timedOut: $drain.timedOut},
      status: $status, window: $window, components: $components, recovery: $recovery,
      nodesEnd: $nodesEnd, hoardSamples: $hoard,
      probes: {prefix: $pp, started: $probes, status: $probeStatus.status, timedOut: $probeStatus.timedOut,
               latency: $probeLatency},
      perMinute: $perMinute}'
}

# Drain time on the database clock: max(completed_at) - t0, both RFC 3339.
s2_drain_secs() { # <phase json>
  jq -n -L "${PERF_DIR}/eval" --argjson p "$1" -r 'include "common";
    ($p.status.lastCompletedAt | ts_secs) as $e | ($p.t0 | ts_secs) as $s
    | if $e == null or $s == null then null else ($e - $s) * 1000 | round / 1000 end'
}

scenario_run() {
  local warmup background tmpl_body tmpl="" tmpl_status final deadline
  local empty1 empty2 jvm ready phases="[]" phase c
  local warm_body='{"type":"chain","activities":2,"activityMillis":10}'
  c=$(jq '.nodes[0].workerDefaultConcurrency' "${RESULTS}/identity.json")

  log "s2: warm-up, $((20 * REPLICAS)) runs"
  warmup=$(warm_up "$((20 * REPLICAS))" "$((10 * REPLICAS))" "$warm_body")
  sleep 2
  background=$(measure_background)

  # The template and its mid-run snapshot.
  tmpl_body=$(jq -nc --arg id "${RUN_ID}-tmpl-0" --argjson d "$S2_D" \
    '{workflowId: $id, type: "chain", activities: 2, activityMillis: $d}')
  http_get "$(node_url 1)/perf/workflows" -X POST -H 'Content-Type: application/json' -d "$tmpl_body" >/dev/null \
    || die "s2: the template start was refused"
  deadline=$(($(date +%s) + 30))
  while :; do
    tmpl=$(psql_json s2-template -v "wf=${RUN_ID}-tmpl-0")
    [[ "$(jq -r .captured <<<"$tmpl")" != true ]] || break
    (($(date +%s) < deadline)) || die "s2: no mid-run snapshot of the template within 30 s ($(jq -c . <<<"$tmpl"))"
    sleep 0.2
  done
  log "s2: template captured $(jq -c '{status, events, activityCompleted, eventTypes}' <<<"$tmpl")"
  final=$(wait_terminal "${RUN_ID}-tmpl-" 1 60 1)
  tmpl_status=$(jq -c '.status.byStatus' <<<"$final")

  node_stop 1
  node_stop 2
  # Empty-database starts: the readiness baseline.
  jvm=$(node_start 1)
  ready=$(node_wait_ready 1 300) || ready=""
  empty1=$(s2_start_record 1 "$jvm" "$ready")
  jvm=$(node_start 2)
  ready=$(node_wait_ready 2 300) || ready=""
  empty2=$(s2_start_record 2 "$jvm" "$ready")
  log "s2: empty-database readiness delays $(jq -c .readinessDelaySecs <<<"$empty1") / $(jq -c .readinessDelaySecs <<<"$empty2") s"
  node_stop 1
  node_stop 2

  phase=$(s2_phase A "$S2_N" 0)
  phases=$(jq -c --argjson p "$phase" '. + [$p]' <<<"$phases")
  if [[ "${PERF_S2_STOP_AFTER:-}" == seed ]]; then
    log "s2: PERF_S2_STOP_AFTER=seed: deleting the seeded rows and restarting both nodes"
    dc exec -T postgres psql -U postgres -d maestro -q -v ON_ERROR_STOP=1 -c \
      "DELETE FROM maestro_workflow_event WHERE workflow_instance_id IN (SELECT id FROM maestro_workflow_instance WHERE workflow_id LIKE '${RUN_ID}-s2a-%'); DELETE FROM maestro_workflow_instance WHERE workflow_id LIKE '${RUN_ID}-s2a-%';" \
      </dev/null >>"${RESULTS}/harness.log" 2>&1
    node_start 1 >/dev/null
    node_start 2 >/dev/null
  else
    node_stop 1
    phase=$(s2_phase B "$S2_N" 1)
    phases=$(jq -c --argjson p "$phase" '. + [$p]' <<<"$phases")
    if [[ "$PROFILE" == full ]]; then
      node_stop 1
      node_stop 2
      phase=$(s2_phase C 600 0 $((15 * c / 20)) 120)
      phases=$(jq -c --argjson p "$phase" '. + [$p]' <<<"$phases")
      node_start 2 >/dev/null
    fi
  fi

  # Drain seconds on the database clock, per phase.
  local with_drain="[]" p
  while IFS= read -r p; do
    with_drain=$(jq -c --argjson p "$p" --argjson d "$(s2_drain_secs "$p")" '. + [$p + {drainSecs: $d}]' <<<"$with_drain")
  done < <(jq -c '.[]' <<<"$phases")

  jq -n \
    --argjson n "$S2_N" --argjson d "$S2_D" --argjson c "$c" --argjson timeout "$S2_TIMEOUT" \
    --argjson warmup "$warmup" --argjson background "$background" --argjson k6 "$(k6_records)" \
    --argjson topology "$(topology_records)" --argjson tmpl "$tmpl" --argjson tmplStatus "$tmpl_status" \
    --argjson empty "[${empty1},${empty2}]" --argjson phases "$with_drain" \
    --arg stopAfter "${PERF_S2_STOP_AFTER:-}" \
    '{plan: {n: $n, activityMillis: $d, concurrency: $c, drainTimeoutSecs: $timeout,
             probeRatePerSec: 0.2, rollingDelaySecs: 5, phases: ($phases | map(.name))},
      warmup: $warmup, background: $background, k6: $k6, topology: $topology,
      template: ($tmpl + {finalByStatus: $tmplStatus}),
      emptyStarts: $empty,
      phases: $phases,
      windows: [$phases[] | .window | select(. != null)],
      phaseTable: [$phases[] | {phase: .name, rows: .rows, drainSecs, nodes: (.starts // [] | length),
                                readinessDelaySecs: (.starts // [] | map(.readinessDelaySecs)),
                                adopted: (.nodesEnd // [] | map(.adopted)),
                                hoardMax: ([.hoardSamples // [] | group_by(.container)[]
                                            | map(select(.running != null and .slotsWorkflow != null)
                                                  | .running - (.parked // 0) - .slotsWorkflow) | max]),
                                probeP95Ms: .probes.latency.p95Ms,
                                adoptCas: .recovery.adoptCas.calls}]}
     + (if $stopAfter == "" then {} else {stoppedAfter: $stopAfter} end)' \
    >"${RESULTS}/measurements.json"
}
