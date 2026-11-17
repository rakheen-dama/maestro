# Scenario 2 (s2-recovery-drain, WP12; finding 3.1 / Issue 30, fixed by WP6):
# a 5,000-run recovery drain by one node (phase A) against a rolling start of
# two nodes (phase B). Inputs: $identity, $measurements (slurped). See
# perf/scenarios/s2-recovery-drain.sh for the workload and the phases.
#
# Every PASS/FAIL rests on counts or on ratios of two figures of this run
# (D4): no absolute latency is gated.
#
#   validity.template            the snapshot was RUNNING with exactly one
#                                ACTIVITY_COMPLETED and k >= 1 events; the
#                                template run ended COMPLETED
#   validity.seeded              per phase, right before its node started:
#                                exactly N rows, all RUNNING, N x k events,
#                                0 instance-lock rows
#   validity.rollingStart        phase B: node 2 started after node 1 read
#                                ready (both recorded) and within 15 s of it
#   validity.recoveryClassifier  recovery statements >= 1.5 per seeded run in
#                                every phase: below that the statement texts
#                                did not match (INVALID, never a vacuous PASS)
#   validity.hoardSeries         running, parked and workflow-slot gauges for
#                                every node of every drain (>= 3 samples each)
#   correctness.allRecovered     every phase row COMPLETED with output 2, none
#                                FAILED, none open, no drain timed out; every
#                                probe COMPLETED
#   gate.twoNodeDrain     (3.1)  drainB / drainA <= 0.65
#   gate.noHoard          (3.1)  max hoard per node, phases A and B, <= C + 2
#                                (hoard = running - parked - workflow slots
#                                used; 2 = sampling slack between the gauges)
#   gate.freshStartsNotStarved (3.1)  phase A probe p95 <= 0.25 x drainA
#   gate.recoveryStatements      (lockAcquire - probe starts + adoptCas +
#                                recoveryPage + heldKeys) / N: phase A (one
#                                node) <= 2.5, phase B (two nodes) <= 3.0.
#                                Why two limits (coordinator's ruling,
#                                2026-11-17): on two nodes the excess over
#                                one node is lock attempts lost to the peer
#                                between its heldKeys read and its lock call,
#                                inherent while the store has no claim
#                                protocol; measured 2.41 (preview
#                                r20261117t122039z) and 2.45 (after of record
#                                r20261117t162103z: 677 lockAcquire calls
#                                beyond one per run and per probe, about 670
#                                pages and 670 heldKeys reads), so 2.5 was a
#                                coin flip on a loaded host. Hoarding (one
#                                node adopting everything and re-polling) or
#                                a re-poll storm would still exceed 3.0.
#   advisory.readiness           readiness delay over the empty-database
#                                start, per phase start (WARN above 5 s)
#   advisory.adoptionShare       per-node adoptions in phase B (WARN when one
#                                node adopted more than 80 %)
#   advisory.decomposition       component calls per seeded run
#   advisory.c2Stream            full: recovered completions in every minute of
#                                phase C > 0; local: not measured
#
# Drain = the phase rows' max(completed_at) - t0, both on the database clock
# (t0: now() right before node 1's docker start).
include "common";

def s2_max_ratio: 0.65;
def s2_hoard_slack: 2;
def s2_probe_fraction: 0.25;
def s2_max_statements: 2.5;
def s2_max_statements_two_nodes: 3.0;
def s2_statement_limit($phase): if $phase == "B" then s2_max_statements_two_nodes else s2_max_statements end;
def s2_min_statements: 1.5;
def s2_rolling_max_secs: 15;
def s2_min_hoard_samples: 3;

def phase($m; $name): ($m.phases // [] | if type == "array" then map(select(type == "object" and .name == $name)) | first else null end);
def drain_secs: if type != "object" then null
  else ((.status.lastCompletedAt | ts_secs) as $e | (.t0 | ts_secs) as $s
        | if $e == null or $s == null then null else $e - $s end) end;
def comp($p; $k): ($p.components.components[$k].calls // 0) | if isnum then . else 0 end;
# Recovery statements of a phase per seeded run, and their parts.
def recovery_stmts($p):
  (try ({lockAcquire: comp($p; "lockAcquire"), probeStarts: ($p.probes.started // 0),
         adoptCas: ($p.recovery.adoptCas.calls // 0), recoveryPage: comp($p; "recoveryPage"),
         heldKeys: comp($p; "heldKeys"), rows: $p.rows}
        | . + {total: (.lockAcquire - .probeStarts + .adoptCas + .recoveryPage + .heldKeys)}
        | . + {perRun: (if (.rows | isnum) and .rows > 0 then .total / .rows | round4 else null end)})
   catch {perRun: null});
# Per-node hoard series of a phase: {container: {samples, max}}.
def hoard_by_node($p):
  ($p.hoardSamples // [] | if type == "array" then . else [] end
   | map(select(type == "object" and (.running | isnum) and (.parked | isnum) and (.slotsWorkflow | isnum)))
   | group_by(.container)
   | map({key: .[0].container, value: {samples: length, max: (map(.running - .parked - .slotsWorkflow) | max)}})
   | from_entries);
def phase_containers($p): ($p.starts // [] | map(.container));

$identity[0] as $id
| $measurements[0] as $m
| ($m.plan.concurrency // $id.effective.workerDefaultConcurrency) as $c
| phase($m; "A") as $a
| phase($m; "B") as $b
| ([$a, $b] | map(select(. != null))) as $ab
| ($m.template // {}) as $t
| ($t.events // null) as $k
| [
    # ── validity ──
    ((($t.captured == true) and $t.status == "RUNNING" and $t.activityCompleted == 1 and ($k | isnum) and $k >= 1
      and ($t.finalByStatus.COMPLETED // 0) == 1) as $ok
     | check("validity.template"; "validity"; if $ok then "PASS" else "INVALID" end;
         {status: $t.status, activityCompleted: $t.activityCompleted, events: $k, eventTypes: $t.eventTypes,
          finalByStatus: $t.finalByStatus};
         {status: "RUNNING", activityCompleted: 1, events: ">= 1", final: "COMPLETED"};
         if $ok then "snapshot RUNNING with one ACTIVITY_COMPLETED of \($k) event(s); the template run COMPLETED"
         else "the template snapshot is not a mid-run state of one completed step, or the template did not complete" end)),
    (($m.phases // [] | if type == "array" then map(select(type == "object")) else [] end) as $ps
     | ($ps | map({name, rows, total: .seeded.total, running: .seeded.byStatus.RUNNING, events: .seeded.events,
                   lockRows: .seeded.lockRows})) as $v
     | ($v | map(select(.rows != .total or .running != .rows or ($k | isnum | not) or .events != .rows * $k or .lockRows != 0))) as $bad
     | (($ps | length) >= 2 and ($a != null) and ($b != null) and ($bad | length) == 0) as $ok
     | check("validity.seeded"; "validity"; if $ok then "PASS" else "INVALID" end; $v;
         {total: "N", running: "N", events: "N x k", lockRows: 0};
         if $ok then "every phase seeded exactly its rows, all RUNNING, \($k) events each, no lock row"
         elif $a == null or $b == null then "phases A and B are not both recorded"
         else "phase(s) \($bad | map(.name) | join(", ")) not seeded as planned" end)),
    (($b.starts // []) as $s
     | ($s[0].readyAt // null) as $r1 | ($s[1].startIssuedAt // null) as $s2
     | (($r1 | isnum) and ($s2 | isnum) and $s2 > $r1 and ($s2 - $r1) <= s2_rolling_max_secs) as $ok
     | check("validity.rollingStart"; "validity"; if $ok then "PASS" else "INVALID" end;
         {node1ReadyAt: $r1, node2StartIssuedAt: $s2,
          gapSecs: (if ($r1 | isnum) and ($s2 | isnum) then ($s2 - $r1 | round2) else null end)};
         {gapSecs: "in (0, \(s2_rolling_max_secs)]"};
         if $ok then "node 2 started \($s2 - $r1 | round2) s after node 1 read ready"
         elif ($r1 | isnum | not) or ($s2 | isnum | not) then "phase B did not record node 1's readiness and node 2's start"
         else "node 2 started \($s2 - $r1 | round2) s after node 1 read ready: not a rolling start" end)),
    ([$ab[] | {phase: .name} + recovery_stmts(.)] as $v
     | (($v | length) == 2 and ($v | all((.perRun | isnum) and .perRun >= s2_min_statements))) as $ok
     | check("validity.recoveryClassifier"; "validity"; if $ok then "PASS" else "INVALID" end; $v;
         {perRun: ">= \(s2_min_statements)"};
         if $ok then "the recovery statements matched their texts in every phase"
         else "recovery statements below \(s2_min_statements) per seeded run: the statement texts did not match this engine's SQL, so the statement gate would be vacuous" end)),
    ([$ab[] | . as $p | hoard_by_node($p) as $h
      | phase_containers($p)[] | {phase: $p.name, container: ., samples: ($h[.].samples // 0)}] as $v
     | (($v | length) >= 3 and ($v | all(.samples >= s2_min_hoard_samples))) as $ok
     | check("validity.hoardSeries"; "validity"; if $ok then "PASS" else "INVALID" end; $v;
         {samples: ">= \(s2_min_hoard_samples) per node per drain, with running, parked and workflow slots"};
         if $ok then "running, parked and workflow-slot gauges sampled for every node of every drain"
         else "a node of a drain has no complete gauge series: the hoard cannot be judged" end)),
    # ── correctness ──
    ([$m.phases // [] | .[]? | select(type == "object")
      | {phase: .name, rows, byStatus: .status.byStatus, completed2: (.status.outputs["2"] // 0),
         timedOut: .drain.timedOut, probes: .probes.started, probesCompleted: (.probes.status.byStatus.COMPLETED // 0)}] as $v
     | ($v | map(select(.byStatus.COMPLETED != .rows or .completed2 != .rows or .timedOut != false
                        or (.probes // 0) != .probesCompleted))) as $bad
     | (($v | length) >= 2 and ($bad | length) == 0) as $ok
     | check("correctness.allRecovered"; "correctness"; if $ok then "PASS" else "FAIL" end; $v;
         {completed: "every row, output 2", failed: 0, probes: "every one COMPLETED"};
         if $ok then "every seeded run of every phase COMPLETED with output 2; every probe COMPLETED"
         else "phase(s) \($bad | map(.phase) | join(", ")) did not recover every run (or a probe) to COMPLETED" end)),
    # ── gates ──
    (($a | drain_secs) as $da | ($b | drain_secs) as $db
     | (if ($da | isnum) and $da > 0 and ($db | isnum) then $db / $da else null end) as $r
     | check("gate.twoNodeDrain"; "gate";
         if $r == null then "INVALID" elif $r <= s2_max_ratio then "PASS" else "FAIL" end;
         {drainASecs: ($da | round2), drainBSecs: ($db | round2), ratio: ($r | round4)};
         {ratio: "<= \(s2_max_ratio)"};
         if $r == null then "a drain time is missing"
         else "a rolling start of two nodes drained in \($r | round4) of one node's time (3.1: the first node adopts the whole backlog before the second starts)" end)),
    ([$ab[] | . as $p | hoard_by_node($p) | to_entries[] | {phase: $p.name, container: .key, max: .value.max}] as $v
     | ($v | map(.max) | max) as $mx
     | check("gate.noHoard"; "gate";
         if ($v | length) == 0 or ($c | isnum | not) then "INVALID" elif $mx <= $c + s2_hoard_slack then "PASS" else "FAIL" end;
         $v; {maxHoard: "<= C + \(s2_hoard_slack) = \(if $c | isnum then $c + s2_hoard_slack else null end)"};
         if ($v | length) == 0 then "no hoard series"
         else "max hoard \($mx) (running - parked - workflow slots used) against C = \($c)" end)),
    (($a | drain_secs) as $da | ($a.probes.latency.p95Ms // null) as $p95
     | (if ($da | isnum) and $da > 0 and ($p95 | isnum) then $p95 / 1000 / $da else null end) as $f
     | check("gate.freshStartsNotStarved"; "gate";
         if $f == null then "INVALID" elif $f <= s2_probe_fraction then "PASS" else "FAIL" end;
         {probeP95Ms: $p95, drainASecs: ($da | round2), fraction: ($f | round4)};
         {fraction: "<= \(s2_probe_fraction)"};
         if $f == null then "phase A has no probe p95 or no drain time"
         else "a fresh start during phase A took \($f | round4) of the drain at p95" end)),
    ([$ab[] | {phase: .name} + recovery_stmts(.) + {limit: s2_statement_limit(.name)}] as $v
     | ($v | map(select((.perRun | isnum) and .perRun > .limit))) as $over
     | check("gate.recoveryStatements"; "gate";
         if ($v | length) < 2 or ($v | any(.perRun | isnum | not)) then "INVALID"
         elif ($over | length) == 0 then "PASS" else "FAIL" end;
         $v; {perRunA: "<= \(s2_max_statements) (one node)", perRunB: "<= \(s2_max_statements_two_nodes) (two nodes: lock attempts lost to the peer are expected)"};
         "recovery statements per seeded run (lockAcquire - probe starts + adoptCas + recoveryPage + heldKeys): \($v | map("\(.phase) \(.perRun) (<= \(.limit))") | join(", "))"
         + (if ($over | length) > 0 then "; over the limit: \($over | map(.phase) | join(", "))" else "" end))),
    # ── advisories ──
    advisory_guarded("advisory.readiness";
      ($m.emptyStarts | map({key: (.node | tostring), value: .readinessDelaySecs}) | from_entries) as $e
      | [$ab[] | .name as $n | .starts[] | {phase: $n, node, readinessDelaySecs,
                                            overEmptySecs: (if (.readinessDelaySecs | isnum) and ($e[.node | tostring] | isnum)
                                                            then (.readinessDelaySecs - $e[.node | tostring] | round2) else null end)}] as $v
      | ($v | all(.overEmptySecs | isnum)) as $all
      | ($v | map(.overEmptySecs) | max) as $mx
      | check("advisory.readiness"; "advisory"; if $all and $mx <= 5 then "PASS" else "WARN" end;
          {emptyStart: $e, starts: $v}; {overEmptySecs: "<= 5"};
          if $all | not then "a readiness delay was not recorded"
          else "readiness delay over the empty-database start: max \($mx) s" end)),
    advisory_guarded("advisory.adoptionShare";
      ($b.nodesEnd | map({container, adopted})) as $v
      | ($v | map(.adopted) | add) as $tot
      | ($v | map(.adopted) | max) as $mx
      | (if ($tot | isnum) and $tot > 0 then $mx / $tot else null end) as $share
      | check("advisory.adoptionShare"; "advisory"; if $share != null and $share <= 0.8 then "PASS" else "WARN" end;
          {nodes: $v, maxShare: ($share | round4)}; {maxShare: "<= 0.8"};
          if $share == null then "phase B's adoptions per node were not recorded"
          else "phase B adoptions per node: \($v | map("\(.container) \(.adopted)") | join(", "))" end)),
    advisory_guarded("advisory.decomposition";
      [$ab[] | . as $p | {phase: .name, rows,
        perRun: ($p.components.components | to_entries | map({key, value: (.value.calls / $p.rows | round4)}) | from_entries),
        adoptCasPerRun: ($p.recovery.adoptCas.calls / $p.rows | round4)}] as $v
      | check("advisory.decomposition"; "advisory"; "PASS"; $v; null;
          "component calls per seeded run (s3-components.sql, s2-recovery.sql); recorded, never a verdict")),
    advisory_guarded("advisory.c2Stream";
      if $id.profile != "full" then
        check("advisory.c2Stream"; "advisory"; "PASS"; null; null; "not measured (local profile: phase C runs on the full profile only)")
      else
        phase($m; "C") as $cph
        | ($cph.perMinute // null) as $pm
        | (($pm | type) == "array" and ($pm | length) > 0 and ($pm | all(.completed > 0))) as $ok
        | check("advisory.c2Stream"; "advisory"; if $ok then "PASS" else "WARN" end; $pm;
            {completedPerMinute: "> 0 in every minute"};
            if $cph == null then "the full profile ran no phase C"
            elif $ok then "recovered runs completed in every minute while fresh starts streamed in"
            else "a minute of phase C recovered nothing while fresh starts streamed in" end)
      end)
  ] as $checks
| result($id; $m; $checks)
