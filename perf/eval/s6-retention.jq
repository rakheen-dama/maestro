# Scenario 6 (s6-retention, WP12; findings 3.11a, 3.11b, fixed by WP8): the
# startup retention sweep and signal janitor of node 2 over a seeded
# terminal backlog, against foreground load on node 1. Inputs: $identity,
# $measurements (slurped). Workload, sets and windows:
# perf/scenarios/s6-retention.sh and perf/sql/s6-seed.sql.
#
#   validity.seeded          every set seeded exactly, each instance with the
#                            template's events and timers, one consumed
#                            signal, and (jown, jfailed, jforeign) one
#                            unconsumed signal
#   validity.sweepObserved   node 2's maestro_retention_sweeps_total >= 1,
#                            node 1's unchanged, and >= 20 sampler samples
#                            with purge work: otherwise there was no sweep,
#                            or it ran on node 1, and the duty-cycle gate
#                            would be meaningless
#   validity.windows         pre and post windows >= 30 s each; the sweep
#                            ended within its allowance
#   correctness.retentionExact  rc keeps exactly rc - min(rc, budget); rt and
#                            rf are empty; young, jown, jfailed and jforeign
#                            keep every instance; no orphan child row; every
#                            foreground probe COMPLETED. xforeign is not
#                            judged here (advisory.foreignRetention)
#   correctness.janitorExact jown's unconsumed signals deleted; jfailed's
#                            kept; every consumed signal of a kept instance
#                            kept
#   validity.statementsClassified  the statement texts were matched: purge
#                            DELETE calls a whole 4 per batch, and the
#                            candidate selects that number of batches plus 0
#                            to 3 short reads (one per terminal status); and
#                            when the janitor's targets are gone, >= 1
#                            janitor call. An unmatched text (a changed
#                            statement) is INVALID, never a FAIL or a PASS
#                            (review r7)
#   validity.dutyCrossCheck  two independent reads of the sweep's duty cycle
#                            agree within 0.15: the sampler's busy fraction
#                            and pg_stat_statements' purge execution time /
#                            the sampled span (promoted from an advisory,
#                            review r7)
#   validity.samplerResolution  the largest sampler gap inside the sweep span
#                            <= 0.3 s (review r8); a run recorded before the
#                            span gap was measured falls back to the
#                            sampler's largest gap overall
#   gate.retentionPaced (3.11a)  busy fraction, TIME-WEIGHTED (each sample
#                            stands for the time to the next;
#                            activity-samples.sql busyTimeFraction; the sample
#                            count's matchingInSpan / spanSamples for a run
#                            recorded before it existed) <= 0.65. The span
#                            is the sweep's own (first to last purge sample),
#                            so a paced sweep that spans longer than an
#                            unpaced one is judged on the same footing: the
#                            share of its own duration a purge statement was
#                            running
#   gate.janitorServiceScoped (3.11b)  jforeign's unconsumed signals all kept
#   gate.janitorBatched (3.11b)  janitor rows per DELETE call in the sweep
#                            window <= 1,000, the engine's batch size
#                            (SignalJanitor.BATCH_SIZE = 1,000,
#                            maestro-core SignalJanitor.java:64; at most
#                            MAX_BATCHES_PER_SWEEP = 1,000 batches, :70), i.e.
#                            calls >= ceil(rows / 1,000). The figure is an
#                            AVERAGE over the calls (pg_stat_statements keeps
#                            rows and calls, not each call's rows), so one
#                            oversized call among many short ones could hide;
#                            the before's single 20,000-row call cannot. The
#                            after of record ran 11 calls (10 x 1,000 + one
#                            empty): 909 rows per call.
#                            WP8 kept the janitor's leading text ("DELETE
#                            FROM maestro_workflow_signal WHERE consumed = ",
#                            AbstractJdbcWorkflowStore.java:1809-1816) and the
#                            candidate select's (":331-332", scoped form
#                            ":357-358"), so no pattern changed.
#   advisory.foreground      probe p95 during / pre and post / pre <= 1.5
#   advisory.foreignRetention  xforeign (another service's TERMINATED rows
#                            past this service's cutoff) purged: recorded as
#                            an observation, never a gate (the planned
#                            service scoping keeps them; Issue 76)
#   advisory.walBytes        WAL written over the sweep window
include "common";

def s6_max_busy: 0.65;
def s6_min_active_samples: 20;
def s6_min_window_secs: 30;
def s6_fg_ratio: 1.5;
def s6_janitor_batch: 1000;
def s6_duty_tolerance: 0.15;
def s6_max_span_gap: 0.3;
def s6_short_reads_max: 3;
# The sweep span in seconds: the sampler's spanSecs, or its first and last
# purge samples' timestamps.
def span_secs($smp): ($smp.spanSecs // (($smp.lastMatchAt | ts_secs) - ($smp.firstMatchAt | ts_secs)));
def busy_fraction($smp):
  ($smp.busyTimeFraction // (if ($smp.spanSamples // 0) > 0 and ($smp.matchingInSpan | isnum)
                             then $smp.matchingInSpan / $smp.spanSamples else null end));
def setv($s; $k; $f): ($s.sets[$k][$f] // 0);
def jsets: ["jown", "jfailed", "jforeign"];

$identity[0] as $id
| $measurements[0] as $m
| ($m.plan.sets // {}) as $plan
| ($m.template // {}) as $t
| ($m.seeded // {}) as $s0
| ($m.final // {}) as $s1
| ($m.sampler // {}) as $smp
| [
    ([$plan | to_entries[] | .key as $k | .value as $n
      | {set: $k, planned: $n, instances: setv($s0; $k; "instances"), events: setv($s0; $k; "events"),
         timers: setv($s0; $k; "timers"), consumed: setv($s0; $k; "signalsConsumed"),
         unconsumed: setv($s0; $k; "signalsUnconsumed"),
         ok: (setv($s0; $k; "instances") == $n and setv($s0; $k; "events") == $n * ($t.events // -1)
              and setv($s0; $k; "timers") == $n * ($t.timers // -1)
              and setv($s0; $k; "signalsConsumed") == $n * ($t.signalsConsumed // -1)
              and setv($s0; $k; "signalsUnconsumed") == (if (jsets | index([$k])) then $n else 0 end))}]) as $v
    | (($v | length) == 8 and ($v | all(.ok)) and $t.captured == true) as $ok
    | check("validity.seeded"; "validity"; if $ok then "PASS" else "INVALID" end; $v | map(del(.ok));
        {perInstance: {events: $t.events, timers: $t.timers, signalsConsumed: $t.signalsConsumed}};
        if $ok then "every set seeded exactly, with the template's children" else "set(s) \($v | map(select(.ok | not) | .set) | join(", ")) not seeded as planned" end),
    (($m.counters.node2.after.sweeps // null) as $n2 | ($m.counters.node1.before.sweeps // null) as $a
     | ($m.counters.node1.after.sweeps // null) as $b | ($smp.matchingSamples // 0) as $act
     | (($n2 | isnum) and $n2 >= 1 and ($a | isnum) and $a == $b and $act >= s6_min_active_samples) as $ok
     | check("validity.sweepObserved"; "validity"; if $ok then "PASS" else "INVALID" end;
         {node2Sweeps: $n2, node1SweepsBefore: $a, node1SweepsAfter: $b, activeSamples: $act};
         {node2Sweeps: ">= 1", node1: "unchanged", activeSamples: ">= \(s6_min_active_samples)"};
         if $ok then "node 2 swept; node 1 did not; \($act) samples saw purge work"
         else "no measured sweep on node 2 (or one on node 1, or too few active samples): the duty-cycle gate would be meaningless" end)),
    (($m.windows // []) as $w
     | ($w | map(select(.phase == "pre")) | first | .windowSecs // null) as $pre
     | ($w | map(select(.phase == "post")) | first | .windowSecs // null) as $post
     | (($pre | isnum) and $pre >= s6_min_window_secs and ($post | isnum) and $post >= s6_min_window_secs
        and $m.sweep.ended == true) as $ok
     | check("validity.windows"; "validity"; if $ok then "PASS" else "INVALID" end;
         {preSecs: $pre, postSecs: $post, sweepEnded: $m.sweep.ended, sweepSecs: $m.sweep.waitedSecs};
         {preSecs: ">= \(s6_min_window_secs)", postSecs: ">= \(s6_min_window_secs)", sweepEnded: true};
         if $ok then "pre \($pre) s, post \($post) s; the sweep ended after \($m.sweep.waitedSecs) s"
         else "a window is too short or the sweep did not end within its allowance" end)),
    (($m.plan.rcRemainder // null) as $rem
     | ($m.foreground // {}) as $fg
     | {rc: setv($s1; "rc"; "instances"), rt: setv($s1; "rt"; "instances"), rf: setv($s1; "rf"; "instances"),
        kept: ([["young", "jown", "jfailed", "jforeign"][] as $k | {set: $k, before: setv($s0; $k; "instances"), after: setv($s1; $k; "instances")}]),
        orphans: $s1.orphans,
        foreground: {n: ([$fg[]?.n] | add), completed: ([$fg[]?.completed] | add)}} as $v
     | (($rem | isnum) and $v.rc == $rem and $v.rt == 0 and $v.rf == 0
        and ($v.kept | all(.before == .after and .after > 0))
        and ($v.orphans | type) == "object" and ([$v.orphans[]] | all(. == 0))
        and ($v.foreground.n | isnum) and $v.foreground.n > 0 and $v.foreground.n == $v.foreground.completed) as $ok
     | check("correctness.retentionExact"; "correctness"; if $ok then "PASS" else "FAIL" end; $v;
         {rc: $rem, rt: 0, rf: 0, kept: "unchanged", orphans: 0, foreground: "every probe COMPLETED"};
         if $ok then "retention removed exactly what its cutoffs and budget allow, and nothing else; no orphan child row"
         else "retention removed too much or too little, left an orphan, or a foreground probe did not complete" end)),
    ({jownUnconsumed: setv($s1; "jown"; "signalsUnconsumed"),
      jfailedUnconsumed: {before: setv($s0; "jfailed"; "signalsUnconsumed"), after: setv($s1; "jfailed"; "signalsUnconsumed")},
      consumedKept: ([["young", "jown", "jfailed", "jforeign"][] as $k
                      | {set: $k, before: setv($s0; $k; "signalsConsumed"), after: setv($s1; $k; "signalsConsumed")}])} as $v
     | ($v.jownUnconsumed == 0 and $v.jfailedUnconsumed.before == $v.jfailedUnconsumed.after
        and $v.jfailedUnconsumed.after > 0 and ($v.consumedKept | all(.before == .after))) as $ok
     | check("correctness.janitorExact"; "correctness"; if $ok then "PASS" else "FAIL" end; $v;
         {jownUnconsumed: 0, jfailedUnconsumed: "kept", consumed: "kept"};
         if $ok then "the janitor deleted this service's unconsumed signals of COMPLETED runs and nothing else"
         else "the janitor left a target signal or deleted one it must keep" end)),
    ((try $m.sweep.components catch null) // {}) as $c
    | ($c.purgeDeletes.calls // 0) as $del | ($c.purgeCandidates.calls // 0) as $cand
    | ($c.janitor.calls // 0) as $jc
    | ((setv($s0; "jown"; "signalsUnconsumed") - setv($s1; "jown"; "signalsUnconsumed")) > 0) as $janitorWorked
    | ($del > 0 and $del % 4 == 0 and ($cand - $del / 4) >= 0 and ($cand - $del / 4) <= s6_short_reads_max) as $purgeOk
    | (($janitorWorked | not) or $jc >= 1) as $janOk
    | check("validity.statementsClassified"; "validity"; if $purgeOk and $janOk then "PASS" else "INVALID" end;
        {purgeDeleteCalls: $del, batches: ($del / 4), candidateSelects: $cand, janitorCalls: $jc,
         janitorTargetsDeleted: $janitorWorked};
        {purgeDeleteCalls: "4 x batches", candidateSelects: "batches + 0..\(s6_short_reads_max)", janitorCalls: ">= 1 when its targets are gone"};
        if $purgeOk and $janOk then "the purge and janitor statements were matched: \($del / 4) batches, \($jc) janitor call(s)"
        else "a purge or janitor statement text was not matched (a changed statement?): the gates on it would judge nothing" end),
    (busy_fraction($smp) as $f | span_secs($smp) as $span
     | ((($c.purgeCandidates.totalMs // 0) + ($c.purgeDeletes.totalMs // 0)) / 1000) as $exec
     | (if ($span | isnum) and $span > 0 then $exec / $span else null end) as $ef
     | (($f | isnum) and ($ef | isnum) and (($f - $ef) | fabs) <= s6_duty_tolerance) as $ok
     | check("validity.dutyCrossCheck"; "validity"; if $ok then "PASS" else "INVALID" end;
         {samplerFraction: ($f | round4), execFraction: ($ef | round4), purgeExecSecs: ($exec | round2), spanSecs: ($span | round2)};
         {difference: "<= \(s6_duty_tolerance)"};
         if $ok then "the sampler and pg_stat_statements agree on the sweep's duty cycle"
         else "the sampler's busy fraction and the purge statements' execution time disagree: the duty cycle is not measured" end)),
    ((($smp.spanMaxGapSecs // $smp.maxGapSecs) // null) as $g
     | check("validity.samplerResolution"; "validity"; if ($g | isnum) and $g <= s6_max_span_gap then "PASS" else "INVALID" end;
         {spanMaxGapSecs: $smp.spanMaxGapSecs, overallMaxGapSecs: $smp.maxGapSecs};
         {gapSecs: "<= \(s6_max_span_gap)"};
         if ($g | isnum) and $g <= s6_max_span_gap then "the sampler saw the sweep every \($g) s or better"
         elif $smp.spanMaxGapSecs == null then "the run predates the span-gap measurement; its largest sampler gap overall was \($g | tojson) s"
         else "a \($g) s gap in the sampler inside the sweep span: its duty cycle is not resolved" end)),
    (busy_fraction($smp) as $f
     | check("gate.retentionPaced"; "gate"; if $f == null then "INVALID" elif $f <= s6_max_busy then "PASS" else "FAIL" end;
         {busyFraction: ($f | round4), timeWeighted: ($smp.busyTimeFraction != null), activeSamples: $smp.matchingInSpan,
          spanSamples: $smp.spanSamples, spanSecs: (span_secs($smp) | round2), firstAt: $smp.firstMatchAt, lastAt: $smp.lastMatchAt};
         {busyFraction: "<= \(s6_max_busy)"};
         if $f == null then "no sweep span was sampled"
         else "a purge statement was active in \($f * 100 | round) % of the sweep's samples (3.11a: batches back to back hold a connection and the database busy for the whole sweep)" end)),
    ({seeded: setv($s0; "jforeign"; "signalsUnconsumed"), after: setv($s1; "jforeign"; "signalsUnconsumed")} as $v
     | check("gate.janitorServiceScoped"; "gate";
         if $v.seeded <= 0 then "INVALID" elif $v.after == $v.seeded then "PASS" else "FAIL" end;
         $v; {after: "= seeded"};
         if $v.seeded <= 0 then "no foreign unconsumed signal was seeded"
         elif $v.after == $v.seeded then "every unconsumed signal of the other service was kept"
         else "the janitor deleted \($v.seeded - $v.after) unconsumed signal(s) of another service (3.11b: not service-scoped)" end)),
    (($m.sweep.components.janitor // {}) as $j | ($plan.jown // null) as $jown
     | (if ($j.calls | isnum) and $j.calls > 0 then $j.rows / $j.calls else null end) as $rpc
     | check("gate.janitorBatched"; "gate";
         if ($jown | isnum | not) or ($j.calls // 0) == 0 then "INVALID"
         elif $rpc != null and $rpc <= s6_janitor_batch then "PASS" else "FAIL" end;
         {calls: ($j.calls // 0), rows: ($j.rows // 0), rowsPerCall: ($rpc | round2)};
         {rowsPerCall: "<= \(s6_janitor_batch) (the engine's batch; an average over the calls)",
          calls: ">= ceil(rows / \(s6_janitor_batch)) = \((($j.rows // 0) / s6_janitor_batch) | ceil)"};
         "the janitor deleted \($j.rows // 0) row(s) in \($j.calls // 0) DELETE call(s) (3.11b: one unbounded statement)")),
    advisory_guarded("advisory.foreground";
      ($m.foreground) as $fg
      | (if ($fg.pre.p95Ms | isnum) and $fg.pre.p95Ms > 0 then {during: ($fg.during.p95Ms / $fg.pre.p95Ms | round4), post: ($fg.post.p95Ms / $fg.pre.p95Ms | round4)} else null end) as $q
      | check("advisory.foreground"; "advisory";
          if $q != null and $q.during <= s6_fg_ratio and $q.post <= s6_fg_ratio then "PASS" else "WARN" end;
          {pre: $fg.pre, during: $fg.during, post: $fg.post, ratios: $q}; {ratio: "<= \(s6_fg_ratio)"};
          if $q == null then "the foreground's pre-window p95 was not recorded"
          else "foreground p95 during / pre \($q.during), post / pre \($q.post)" end)),
    advisory_guarded("advisory.foreignRetention";
      ($plan.xforeign) as $n | ($s1.sets | type) as $ty
      | if ($n | isnum | not) or $ty != "object" then
          check("advisory.foreignRetention"; "advisory"; "WARN"; null; null; "the xforeign set was not read")
        else
          setv($s1; "xforeign"; "instances") as $left
          | check("advisory.foreignRetention"; "advisory"; "PASS";
              {seeded: $n, purged: ($n - $left), kept: $left};
              {observation: "WP8's scoping keeps them (Issue 76)"};
              "another service's TERMINATED rows past this service's cutoff: \($n - $left) of \($n) purged by this service's sweep")
        end),
    advisory_guarded("advisory.walBytes";
      check("advisory.walBytes"; "advisory"; if ($m.sweep.walBytes | isnum) then "PASS" else "WARN" end;
        {walBytes: $m.sweep.walBytes}; null; "WAL written over the sweep window (recorded, never a verdict)"))
  ] as $checks
| result($id; $m; $checks)
