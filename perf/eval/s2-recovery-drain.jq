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
#                                ready (both recorded) and within 15 s of it,
#                                and node 2 read ready too
#   validity.recoveryClassifier  every statement the gates count was matched
#                                in every phase (lockAcquire, adoptCas,
#                                recoveryPage, heldKeys each >= 1; the lock
#                                statement's rows recorded), adoptCas == the
#                                nodes' own adoption counters == N, and
#                                lockAcquire calls >= N: a changed statement
#                                text would otherwise zero a term silently
#                                (review r3). INVALID, never a vacuous PASS
#   validity.statusRecorded      every phase recorded its rows' byStatus and
#                                outputs, its drain's timedOut and its probes'
#                                byStatus: without them correctness.allRecovered
#                                would read a missing record as a failure
#   validity.hoardSeries         running, parked and workflow-slot gauges for
#                                every node of every drain (>= 3 samples each)
#   correctness.allRecovered     every phase row COMPLETED with output 2, none
#                                FAILED, none open, no drain timed out; every
#                                probe COMPLETED
#   gate.twoNodeDrain     (3.1)  drainB / drainA <= 0.65
#   gate.noHoard          (3.1)  max hoard per node, phases A and B, <= 2C
#                                (hoard = running - parked - workflow slots
#                                used: runs queued for a slot). The bound the
#                                engine documents (operations.md, the recovery
#                                pass row): at most 2 x concurrency runs per
#                                queue running or queued, so at most 2C queued
#                                at any instant, sampling skew between the
#                                three gauges included. The WP6 runs measured
#                                21; the before 4,883-4,941 (review r4: C + 2
#                                left a one-run margin)
#   gate.freshStartsNotStarved (3.1)  phase A probe p95 <= 0.25 x drainA
#   Recovery statements, three gates (review r2, replacing one combined
#   figure that mixed per-run cost, lost lock races and per-node-second pass
#   cost, so it moved with drain time and N; it stays as
#   advisory.recoveryStatements). Lock rows/calls come from the window's
#   pg_stat_statements entry for INSERT INTO maestro_distributed_lock (the
#   acquire is an upsert that returns a row only when it takes the lock).
#   gate.lockAcquisitionsPerAdoption  (successful acquisitions - probe starts
#                                + adoptCas) / adoptions <= 2.02. Read
#                                honestly: validity.recoveryClassifier
#                                already holds adoptCas == adoptions == N, so
#                                this is 1 + (lock rows - probe starts) / N:
#                                every adoption took exactly one lock, and no
#                                run was locked twice (re-adopted). Measured
#                                2.0000 in every run
#   gate.lostLockRaces           (lock calls - lock rows) / N <= 0.5: attempts
#                                lost to the peer between its heldKeys read
#                                and its lock call, inherent on two nodes
#                                while the store has no claim protocol, and
#                                growing with statement latency (the window
#                                between the read and the call). Measured, one
#                                host: B 0 before (node 2 adopted nothing),
#                                622 = 0.124 preview, 923 = 0.185 after of
#                                record; A 0. 0.5 (coordinator's ruling, final
#                                review f3) leaves room for a slower host,
#                                while the regression the gate exists for — a
#                                peer chasing every row, as if heldKeys were
#                                not read — loses about one race per run (>= 1)
#   gate.recoveryPassRate        (recoveryPage + heldKeys) / designed <= 1.25.
#                                designed is a FLOOR, not a ceiling: R x
#                                node-seconds + one full pass per node start,
#                                R = 2 / 1 s (capacity passes at most once per
#                                second per node, RecoveryPoller.java:201,
#                                counted at their least, one page + one
#                                heldKeys read; a pass whose page fills goes
#                                on to the next page while headroom remains,
#                                operations.md: ceil(rows read / page) page
#                                reads, so a pass can read more) + 2 x
#                                ceil(N / 200) / pollInterval (a full pass
#                                every recovery poll interval reads every page
#                                and its heldKeys; 200 = the page,
#                                WorkflowExecutor RECOVERY_PAGE_SIZE); a full
#                                pass = 2 x ceil(N / 200); node-seconds: each
#                                node's start (dbStartIssuedAt) to the
#                                component snapshot (componentsAt, read with
#                                the page and heldKeys counts, after the
#                                window's snapshotAt), both on the database
#                                clock, the interval the reads are counted
#                                over (it closes after the post-drain waits
#                                for the probes and the sampler, and recovery
#                                keeps polling in them); either time missing
#                                is INVALID. The limit 1.25 rests on the
#                                measurements, not the arithmetic: on that
#                                interval 0.56-0.71 on one node, 0.78-0.82 on
#                                two after WP6 (worst 0.824, the preview's
#                                phase B; 0.896 when node-seconds ended at the
#                                last completion), so 1.25 is 52 % above the
#                                worst run seen; a re-poll
#                                storm (passes faster than the spacing, or
#                                re-reading pages it has adopted) exceeds it
#   advisory.readiness           readiness delay over the empty-database
#                                start, per phase start (WARN above 5 s)
#   advisory.adoptionShare       per-node adoptions in phase B (WARN when one
#                                node adopted more than 80 %)
#   advisory.decomposition       component calls per seeded run
#   advisory.recoveryStatements  the former combined figure, (lockAcquire -
#                                probe starts + adoptCas + recoveryPage +
#                                heldKeys) / N, recorded, never a verdict
#   advisory.c2Stream            full: recovered completions in every minute of
#                                phase C > 0; local: not measured
#
# Drain = the phase rows' max(completed_at) - t0, both on the database clock
# (t0: now() right before node 1's docker start).
include "common";

def s2_max_ratio: 0.65;
def s2_probe_fraction: 0.25;
def s2_max_acquisitions_per_adoption: 2.02;
def s2_max_lost_per_run: 0.5;
def s2_pass_rate_slack: 1.25;
def s2_page_rows: 200;
def s2_capacity_spacing_secs: 1;
def s2_rolling_max_secs: 15;
def s2_min_hoard_samples: 3;

def phase($m; $name): ($m.phases // [] | if type == "array" then map(select(type == "object" and .name == $name)) | first else null end);
def drain_secs: if type != "object" then null
  else ((.status.lastCompletedAt | ts_secs) as $e | (.t0 | ts_secs) as $s
        | if $e == null or $s == null then null else $e - $s end) end;
def comp($p; $k): ((try $p.components.components[$k].calls catch null) // 0) | if isnum then . else 0 end;
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
# The lock-acquire statement's calls and rows in a phase's window (null when
# the window's list has no such entry).
def lock_stat($p):
  ([$p.window.top // [] | .[]? | select(type == "object" and (.query | type) == "string"
                                       and (.query | test("^INSERT INTO maestro_distributed_lock ")) and .toplevel != false)]) as $e
  | if ($e | length) == 0 then {calls: null, rows: null}
    else {calls: ($e | map(.calls) | add), rows: ($e | map(.rows) | add)} end;
def adopted_by_nodes($p): ($p.nodesEnd // [] | map(.adopted) | if length > 0 and all(isnum) then add else null end);
# The three statement figures of a phase (null parts when unmeasured).
def stmt_figures($p; $pollSecs):
  (try (
    lock_stat($p) as $l
    | ($p.probes.started // 0) as $pr
    | ($p.recovery.adoptCas.calls // null) as $cas
    | adopted_by_nodes($p) as $ad
    | ($p.rows) as $n
    # node-seconds end at the component snapshot (componentsAt: the page and
    # heldKeys counts are read with it), from each start, both on the
    # database clock; a missing time leaves the figure null (INVALID)
    | ($p.components.componentsAt | ts_secs) as $end
    | ([$p.starts[]? | .dbStartIssuedAt | ts_secs]) as $st
    | (if ($end | isnum) and ($st | length) > 0 and ($st | all(isnum)) then $st | map($end - .) else null end) as $alive
    | (if ($n | isnum) then ($n / s2_page_rows | ceil) * 2 else null end) as $fullPass
    | (2 / s2_capacity_spacing_secs + (if ($fullPass | isnum) and ($pollSecs | isnum) and $pollSecs > 0 then $fullPass / $pollSecs else 0 end)) as $rate
    | (if $alive == null then null else $alive | add end) as $nodeSecs
    | (if ($nodeSecs | isnum) and ($fullPass | isnum) then $rate * $nodeSecs + $fullPass * ($alive | length) else null end) as $designed
    | (comp($p; "recoveryPage") + comp($p; "heldKeys")) as $reads
    | {phase: $p.name, rows: $n, adoptions: $ad, adoptCas: $cas, probeStarts: $pr,
       lockCalls: $l.calls, lockRows: $l.rows,
       acquisitionsPerAdoption: (if ($l.rows | isnum) and ($cas | isnum) and ($ad | isnum) and $ad > 0
                                 then ($l.rows - $pr + $cas) / $ad | round4 else null end),
       lost: (if ($l.calls | isnum) and ($l.rows | isnum) then $l.calls - $l.rows else null end),
       lostPerRun: (if ($l.calls | isnum) and ($l.rows | isnum) and ($n | isnum) and $n > 0
                    then ($l.calls - $l.rows) / $n | round4 else null end),
       pageAndHeldKeysReads: $reads, nodeSeconds: ($nodeSecs | round2), designedReads: ($designed | round2),
       ratio: (if ($designed | isnum) and $designed > 0 then $reads / $designed | round4 else null end)})
   catch {phase: $p.name});

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
     | ($s[0].readyAt // null) as $r1 | ($s[1].startIssuedAt // null) as $s2 | ($s[1].readyAt // null) as $r2
     | (($r1 | isnum) and ($s2 | isnum) and ($r2 | isnum) and $s2 > $r1 and ($s2 - $r1) <= s2_rolling_max_secs) as $ok
     | check("validity.rollingStart"; "validity"; if $ok then "PASS" else "INVALID" end;
         {node1ReadyAt: $r1, node2StartIssuedAt: $s2, node2ReadyAt: $r2,
          gapSecs: (if ($r1 | isnum) and ($s2 | isnum) then ($s2 - $r1 | round2) else null end)};
         {gapSecs: "in (0, \(s2_rolling_max_secs)]", node2ReadyAt: "a time"};
         if $ok then "node 2 started \($s2 - $r1 | round2) s after node 1 read ready, and read ready itself"
         elif ($r1 | isnum | not) or ($s2 | isnum | not) then "phase B did not record node 1's readiness and node 2's start"
         elif ($r2 | isnum | not) then "node 2 never read ready in phase B"
         else "node 2 started \($s2 - $r1 | round2) s after node 1 read ready: not a rolling start" end)),
    ([$ab[] | . as $p | lock_stat($p) as $l
      | {phase: .name, rows: .rows, lockAcquire: comp($p; "lockAcquire"), lockRows: $l.rows,
         adoptCas: (.recovery.adoptCas.calls // 0), recoveryPage: comp($p; "recoveryPage"), heldKeys: comp($p; "heldKeys"),
         nodesAdopted: adopted_by_nodes($p)}] as $v
     | ($v | map(select(.lockAcquire < 1 or .adoptCas < 1 or .recoveryPage < 1 or .heldKeys < 1
                        or (.lockRows | isnum | not) or .adoptCas != .nodesAdopted or .adoptCas != .rows
                        or .lockAcquire < .rows))) as $bad
     | (($v | length) == 2 and ($bad | length) == 0) as $ok
     | check("validity.recoveryClassifier"; "validity"; if $ok then "PASS" else "INVALID" end; $v;
         {each: ">= 1", adoptCas: "= nodes' adoptions = N", lockAcquire: ">= N"};
         if $ok then "every recovery statement matched its text in every phase, and the counts agree"
         else "phase(s) \($bad | map(.phase) | join(", ")): a recovery statement was not matched (a changed SQL text?) or the counts disagree, so the statement gates would judge nothing" end)),
    ([$ab[] | . as $p | hoard_by_node($p) as $h
      | phase_containers($p)[] | {phase: $p.name, container: ., samples: ($h[.].samples // 0)}] as $v
     | (($v | length) >= 3 and ($v | all(.samples >= s2_min_hoard_samples))) as $ok
     | check("validity.hoardSeries"; "validity"; if $ok then "PASS" else "INVALID" end; $v;
         {samples: ">= \(s2_min_hoard_samples) per node per drain, with running, parked and workflow slots"};
         if $ok then "running, parked and workflow-slot gauges sampled for every node of every drain"
         else "a node of a drain has no complete gauge series: the hoard cannot be judged" end)),
    ([$m.phases // [] | .[]? | select(type == "object")
      | {phase: .name, byStatus: (.status.byStatus | type), outputs: (.status.outputs | type),
         timedOut: (.drain.timedOut | type), probeStatus: (.probes.status.byStatus | type)}] as $v
     | ($v | map(select(.byStatus != "object" or .outputs != "object" or .timedOut != "boolean"
                        or .probeStatus != "object"))) as $bad
     | (($v | length) >= 2 and ($bad | length) == 0) as $ok
     | check("validity.statusRecorded"; "validity"; if $ok then "PASS" else "INVALID" end; $v;
         {byStatus: "object", outputs: "object", timedOut: "boolean", probeStatus: "object"};
         if $ok then "every phase recorded its rows' statuses and outputs, its drain's outcome and its probes' statuses"
         else "phase(s) \($bad | map(.phase) | join(", ")) did not record their status: correctness cannot be judged (INVALID, not a failure)" end)),
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
         if ($v | length) == 0 or ($c | isnum | not) then "INVALID" elif $mx <= 2 * $c then "PASS" else "FAIL" end;
         $v; {maxHoard: "<= 2C = \(if $c | isnum then 2 * $c else null end) (the documented running-or-queued bound)"};
         if ($v | length) == 0 then "no hoard series"
         else "max hoard \($mx) (running - parked - workflow slots used) against 2C = \(2 * $c) (C = \($c))" end)),
    (($a | drain_secs) as $da | ($a.probes.latency.p95Ms // null) as $p95
     | (if ($da | isnum) and $da > 0 and ($p95 | isnum) then $p95 / 1000 / $da else null end) as $f
     | check("gate.freshStartsNotStarved"; "gate";
         if $f == null then "INVALID" elif $f <= s2_probe_fraction then "PASS" else "FAIL" end;
         {probeP95Ms: $p95, drainASecs: ($da | round2), fraction: ($f | round4)};
         {fraction: "<= \(s2_probe_fraction)"};
         if $f == null then "phase A has no probe p95 or no drain time"
         else "a fresh start during phase A took \($f | round4) of the drain at p95" end)),
    ([$ab[] | stmt_figures(.; ($id.nodes[0].recoveryPollInterval | duration_secs))] as $f
     | ($f | map(.acquisitionsPerAdoption)) as $x
     | check("gate.lockAcquisitionsPerAdoption"; "gate";
         if ($f | length) < 2 or ($x | any(isnum | not)) then "INVALID"
         elif ($x | all(. <= s2_max_acquisitions_per_adoption)) then "PASS" else "FAIL" end;
         ($f | map({phase, adoptions, lockRows, probeStarts, adoptCas, acquisitionsPerAdoption}));
         {acquisitionsPerAdoption: "<= \(s2_max_acquisitions_per_adoption)"};
         "(successful lock acquisitions - probe starts + adoption CAS) per adopted run: \($f | map("\(.phase) \(.acquisitionsPerAdoption)") | join(", "))")
     ,
     ($f | map(.lostPerRun)) as $y
     | check("gate.lostLockRaces"; "gate";
         if ($f | length) < 2 or ($y | any(isnum | not)) then "INVALID"
         elif ($y | all(. <= s2_max_lost_per_run)) then "PASS" else "FAIL" end;
         ($f | map({phase, lockCalls, lockRows, lost, lostPerRun}));
         {lostPerRun: "<= \(s2_max_lost_per_run)"};
         "lock attempts lost to a peer per seeded run: \($f | map("\(.phase) \(.lost) (\(.lostPerRun))") | join(", "))")
     ,
     ($f | map(.ratio)) as $z
     | check("gate.recoveryPassRate"; "gate";
         if ($f | length) < 2 or ($z | any(isnum | not)) then "INVALID"
         elif ($z | all(. <= s2_pass_rate_slack)) then "PASS" else "FAIL" end;
         ($f | map({phase, pageAndHeldKeysReads, nodeSeconds, designedReads, ratio}));
         {ratio: "<= \(s2_pass_rate_slack) x the designed page + heldKeys reads"};
         "recovery page + heldKeys reads against the engine's designed pass rate: \($f | map("\(.phase) \(.ratio)") | join(", "))")),
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
    advisory_guarded("advisory.recoveryStatements";
      [$ab[] | {phase: .name} + recovery_stmts(.)] as $v
      | check("advisory.recoveryStatements"; "advisory"; if ($v | all(.perRun | isnum)) then "PASS" else "WARN" end;
          $v; null;
          "the former combined figure (lockAcquire - probe starts + adoptCas + recoveryPage + heldKeys) / N, recorded only: \($v | map("\(.phase) \(.perRun)") | join(", "))")),
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
