# Scenario 1 (s1-throughput): the throughput ceiling of one node at
# Maestro's true defaults. Inputs: $identity, $measurements, $baseline
# (slurped arrays). Measurements: perf/scenarios/s1-throughput.sh.
#
# D4: numbers first. The ceiling and every latency figure are REPORTED
# (advisories), never gated. The verdict rests on validity, on correctness
# (every accepted start COMPLETED, nothing failed, nothing left) and on the
# statement gate.
#
# ── Levels, "sustains" and the ceiling ─────────────────────────────────────
# Level L's window [ws, we] is the min/max started_at of its rows (ramp into
# L included). arrivalPerSec = its arrivals / windowSecs (the rate actually
# offered, below L during the ramp); completionsInWindowPerSec = COMPLETED
# rows of ANY level with completed_at in [ws, we] / windowSecs (k6's request
# timing cannot show this: the start endpoint answers 202 before a slot is
# taken). backlogAtEnd = rows started by we and not yet terminal at we.
# A level SUSTAINS when both hold:
#   completionsInWindowPerSec >= sustain_ratio (0.95) x arrivalPerSec
#     (completions track arrivals within 5 %), and
#   backlogAtEnd <= workflowSlots x replicas + arrivalPerSec x 1 s
#     (what is in flight at the end of the window fits in the node's slots
#     plus one second of arrivals: the queue is bounded, not growing).
# kneeLevel = the highest level of the unbroken run of sustaining levels from
# the lowest upward (null when the lowest does not sustain); every level above
# it is "saturated". The CEILING is reported as two numbers: sustainedLevel
# (the knee's offered rate, starts/s) and ceilingPerSec = the highest
# completionsInWindowPerSec of any level (the most the node delivered).
# ceilingReached = some level did not sustain (otherwise the ceiling lies
# above the top level and ceilingPerSec is a lower bound).
#
# ── Per-level statements ───────────────────────────────────────────────────
# The scenario samples, every sampleSecs (5 s), the engine's cumulative
# statement count (pgss-window, sweepCalls included) and the s1 rows'
# cumulative completions. A level's figure uses the samples bracketing its
# window (the last at or before ws, the reset when none; the first at or after
# we): gross = delta calls (lock-cleaner calls subtracted) / delta
# completions; net additionally subtracts the idle background rate x delta
# time. Statements and completions are counted over the same interval; the
# residual error is the work of the runs in flight at the two edges (at most
# the slots, about 1 % of a level's completions). Recorded, not gated.
# Caveat: a run's statements are not all paid at completion: its start
# (instance insert and what goes with it) is paid at ARRIVAL. In an interval
# where arrivals outrun completions (a saturated level) those start
# statements of runs that complete later are charged to fewer completions,
# so the figure rises without any run costing more (local run
# r20261112t204157z: L80 45.5 while the whole-run figure was 36.0). Each
# level therefore records started and completed in its interval and
# balanced = |started - completed| <= 5 % of completed; per-level figures are
# comparable across runs only where balanced. The gate never uses them.
#
# ── The statement gate (gate.statementsPerWorkflow) ────────────────────────
# Over the whole load window (reset before k6 -> after the drain, so every
# s1 run's statements are in it): net statements per COMPLETED workflow, via
# statements_gate (common.jq): symmetric band = tolerance + relativeAllowance
# x expected. The expected value comes from perf/baselines/s1-throughput.json,
# used only when its workflowShape {activities, activityMillis}, profile and
# replicas match this run (otherwise NO_BASELINE, with the reason).
#
# relativeAllowance = s1_relative_allowance below: 0.02, from measurements.
# Three cold local runs on clean trees (one node, 6337 workflows each), net
# statements per completed workflow over the whole load window:
#   r20261112t203520z 36.00   r20261112t204157z 36.01   r20261112t204918z 36.00
# a spread of 0.01 (0.03 %), although the second ran on a host with a load
# average of 87 on 10 CPUs, where the delivered ceiling fell from 72.6 to
# 36.9 completions/s and the Hikari pool queued 19 deep. (Per-level figures
# at balanced levels ranged 35.96 to 36.24; they are not gated.) The started
# 0.10 would be a band of +/- 3.6 statements: blind to one added statement per
# workflow (+2.8 %), the smallest real regression. 0.02 is +/- 0.72: it
# catches that, and is still about 70 x the observed spread. What could move
# the figure without a code change, and how much: recovery polls page over the
# backlog (at most about 70 calls a minute at the full profile's peak backlog,
# about 0.03 per workflow), CAS retries and lock contention (not observed);
# lock-cleaner passes are subtracted exactly (validity.sweeps).
include "common";

def s1_relative_allowance: 0.02;
def sustain_ratio: 0.95;
def backlog_slack_secs: 1;
# A level's statement interval is "balanced" when the runs started in it are
# within 5 % of the runs completed in it (see "Per-level statements").
def balanced_fraction: 0.05;

def s1_baseline_match($b; $m; $id):
  if $b == null then "perf/baselines/s1-throughput.json is absent"
  elif ($b.workflowShape | type) != "object"
       or ($b.workflowShape | {activities, activityMillis}) != ($m.workflowShape | {activities, activityMillis})
    then "baseline workflowShape \($b.workflowShape | tojson) is not this run's \($m.workflowShape | {activities, activityMillis} | tojson)"
  elif $b.profile != $id.profile then "baseline profile \($b.profile | tojson) is not this run's \($id.profile | tojson)"
  elif $b.replicas != $id.config.replicas then "baseline replicas \($b.replicas | tojson) is not this run's \($id.config.replicas)"
  else null end;

# The idle background rate (calls/s, lock-cleaner calls excluded), or null.
def bg_rate($bg; $replicas):
  if background_problem($bg; $replicas) == null
  then ($bg.window.maestroCalls - lock_cleaner_calls($bg.window)) / $bg.window.windowSecs
  else null end;

# Per-level statement figures from the cumulative samples.
def level_statements($m; $ws; $we; $rate):
  ([{t: ($m.pgssReset.resetAt | ts_secs), calls: 0, sweep: 0, done: 0, rows: 0, reset: true}]
   + ($m.samples // [] | map(select(type == "object")
       | {t: (.snapshotAt | ts_secs), calls: .maestroCalls, sweep: lock_cleaner_calls(.), done: .completed,
          rows: .rows, reset: false})
       | map(select((.t | isnum) and (.calls | isnum) and (.done | isnum))))
   | sort_by(.t)) as $pts
  | ([$pts[] | select(.t <= $ws)] | last) as $a
  | ([$pts[] | select(.t >= $we)] | first) as $b
  | if $a == null or $b == null or ($a.t | isnum | not) then
      {gross: null, net: null, note: "no sample brackets the window"}
    else
      (($b.calls - $b.sweep) - ($a.calls - $a.sweep)) as $dc
      | ($b.done - $a.done) as $dd
      | (if ($a.rows | isnum) and ($b.rows | isnum) then $b.rows - $a.rows else null end) as $ds
      | ($b.t - $a.t) as $dt
      | {intervalSecs: ($dt | round2), fromReset: $a.reset,
         edgeOffsetsSecs: [($a.t - $ws | round2), ($b.t - $we | round2)],
         calls: $dc, completed: $dd, started: $ds,
         balanced: (($ds | isnum) and $dd > 0 and (($ds - $dd) | fabs) <= balanced_fraction * $dd),
         gross: (if $dd > 0 then $dc / $dd | round2 else null end),
         net: (if $dd > 0 and ($rate | isnum) then ($dc - $rate * $dt) / $dd | round2 else null end)}
    end;

$identity[0] as $id
| $measurements[0] as $m
| ($baseline[0] // null) as $b
| $id.config.replicas as $replicas
| (($id.nodes[0].workerDefaultConcurrency // null) as $c | if ($c | isnum) then $c * $replicas else null end) as $slots
| ($m.runStatus.byStatus // {}) as $by
| ($by.COMPLETED // 0) as $done
| ($by.FAILED // 0) as $failed
| (($m.runStatus.total // 0) - $done - $failed - ($by.TERMINATED // 0)) as $open
| ($m.accepted // 0) as $accepted
| $m.window as $w
| statement_figures($m.background; $w; $done; $replicas) as $s
| bg_rate($m.background; $replicas) as $rate
| ($m.levelProm // [] | map({key: (.level | tostring), value: .}) | from_entries) as $prom
| ([($m.levelRows // [])[] | . as $r
    | ($prom[$r.level | tostring] // {}) as $p
    | (if ($r.arrivalPerSec | isnum) and $r.arrivalPerSec > 0 and ($r.completionsInWindowPerSec | isnum)
       then $r.completionsInWindowPerSec / $r.arrivalPerSec else null end) as $ratio
    | (if ($slots | isnum) and ($r.arrivalPerSec | isnum)
       then $slots + $r.arrivalPerSec * backlog_slack_secs else null end) as $bound
    | {level: $r.level, offeredPerSec: $r.level, arrivals: $r.arrivals, windowSecs: $r.windowSecs,
       windowStart: $r.windowStart, windowEnd: $r.windowEnd,
       arrivalPerSec: $r.arrivalPerSec, completionsInWindowPerSec: $r.completionsInWindowPerSec,
       completionRatio: ($ratio | round4), backlogAtStart: $r.backlogAtStart, backlogAtEnd: $r.backlogAtEnd,
       backlogBound: ($bound | round2),
       sustained: (($ratio | isnum) and $r.completionsInWindowPerSec >= sustain_ratio * $r.arrivalPerSec
                   and ($bound | isnum) and ($r.backlogAtEnd | isnum) and $r.backlogAtEnd <= $bound),
       p50Ms: $r.p50Ms, p95Ms: $r.p95Ms, p99Ms: $r.p99Ms, maxMs: $r.maxMs,
       completed: $r.completed, failed: $r.failed, nonTerminal: $r.nonTerminal,
       slotsUsed: ($p.slotsUsed // null), hikari: ($p.hikari // null), durationMeter: ($p.durationMeter // null),
       statements: (try level_statements($m; ($r.windowStartEpoch); ($r.windowEndEpoch); $rate)
                    catch {gross: null, net: null, note: "could not be computed: \(. | tostring)"})}]
   | sort_by(.level)) as $levels
| (reduce $levels[] as $l ({knee: null, broken: false};
     if .broken | not then (if $l.sustained then .knee = $l.level else .broken = true end) else . end)
   | .knee) as $knee
| {sustainedLevel: $knee,
   sustainedArrivalPerSec: ([$levels[] | select(.level == $knee) | .arrivalPerSec] | first // null),
   ceilingPerSec: ([$levels[].completionsInWindowPerSec | select(isnum)] | max // null),
   ceilingReached: any($levels[]; .sustained | not),
   saturatedLevels: [$levels[] | select($knee == null or .level > $knee) | .level],
   workflowSlots: $slots,
   definition: "a level sustains when completionsInWindowPerSec >= \(sustain_ratio) x arrivalPerSec and backlogAtEnd <= workflowSlots + arrivalPerSec x \(backlog_slack_secs) s; sustainedLevel = highest of the unbroken run of sustaining levels from the lowest; ceilingPerSec = highest completionsInWindowPerSec"} as $ceiling
| ($m.plan.levels // []) as $planned
| ($m.k6 // [] | map(select(.phase == "s1")) | last) as $k6s1
| ($m.k6 // [] | map(select(.phase == "warmup")) | last) as $k6wu
| s1_baseline_match($b; $m; $id) as $noBase
| (if $noBase == null then $b.statementsPerWorkflowNet else null end) as $expected
| [
    background_check($m.background; $replicas),
    check("validity.roleSplit"; "validity";
          if ($w.maestroCalls | isnum) and $w.maestroCalls > 0 and ($w.harnessCalls | isnum) and $w.harnessCalls > 0
          then "PASS" else "INVALID" end;
          {maestroCalls: $w.maestroCalls, harnessCalls: $w.harnessCalls};
          {maestroCalls: "> 0", harnessCalls: "> 0"};
          "engine (role maestro) and harness (role postgres) statements are both seen and counted apart"),
    ( ($levels | map(.level)) as $got
      | ([$levels[] | select((.arrivals | isnum | not) or .arrivals <= 0) | .level]) as $empty
      | (($planned | length) > 0 and $got == $planned and ($empty | length) == 0 and $m.unlabeledRows == 0) as $ok
      | check("validity.levels"; "validity"; if $ok then "PASS" else "INVALID" end;
          {levels: $got, arrivals: ($levels | map(.arrivals)), unlabeledRows: $m.unlabeledRows};
          {levels: $planned, arrivals: "> 0 per level", unlabeledRows: 0};
          if $ok then "every planned level \($planned) received arrivals"
          else [
            (if ($planned | length) == 0 then "no plan recorded" else null end),
            (if $got != $planned then "levels seen \($got) differ from the plan \($planned)" else null end),
            (if ($empty | length) > 0 then "no arrivals at level(s) \($empty)" else null end),
            (if $m.unlabeledRows != 0 then "\($m.unlabeledRows | tojson) s1 row(s) carry no L<level> label" else null end)
          ] | reasons end) ),
    ( ($m.warmup.vus // null) as $wv
      | ($m.warmup.count // null) as $wc
      | ($k6wu.vusMax // null) as $wPeak
      | ($k6s1.vusMax // null) as $lPeak
      | ([$wv, $wc, $wPeak, $lPeak, $slots, $m.plan.maxVUs] | all(isnum)) as $known
      | ($known and $wv >= $m.plan.maxVUs and $wPeak >= $lPeak and $wc >= 2 * $slots) as $ok
      | check("validity.warmupSize"; "validity"; if $ok then "PASS" else "INVALID" end;
          {warmupVus: $wv, warmupPeakVus: $wPeak, warmupCount: $wc, loadPeakVus: $lPeak,
           planMaxVUs: $m.plan.maxVUs, workflowSlots: $slots};
          {warmupVus: ">= plan maxVUs", warmupPeakVus: ">= the load's peak VUs", warmupCount: ">= 2 x workflowSlots"};
          if $ok then "warm-up ran \($wc) starts at \($wPeak) concurrent VUs, at least the load's peak of \($lPeak) VUs and 2 x the \($slots) workflow slots"
          elif ($known | not) then "the warm-up or load VU figures were not recorded"
          else [
            (if $wv < $m.plan.maxVUs then "warm-up VUs \($wv) below the plan's maxVUs \($m.plan.maxVUs)" else null end),
            (if $wPeak < $lPeak then "warm-up peaked at \($wPeak) VUs, the load at \($lPeak)" else null end),
            (if $wc < 2 * $slots then "warm-up of \($wc) runs is below 2 x \($slots) workflow slots" else null end)
          ] | reasons end) ),
    ( ($accepted > 0 and $done == $accepted and $failed == 0 and $open == 0
       and ($m.runStatus.total // 0) == $accepted and $m.drain.timedOut == false) as $ok
      | check("correctness.allCompleted"; "correctness"; if $ok then "PASS" else "FAIL" end;
          {accepted: $accepted, completed: $done, failed: $failed, nonTerminal: $open, total: $m.runStatus.total,
           byStatus: $by, drainTimedOut: $m.drain.timedOut, drainSecs: $m.drain.waitedSecs};
          {completed: "== accepted starts (k6 checks passed on name:start)", failed: 0, nonTerminal: 0};
          if $ok then "\($accepted)/\($accepted) accepted starts COMPLETED; drained in \($m.drain.waitedSecs) s"
          else [
            (if $accepted == 0 then "no start was accepted" else null end),
            (if $done != $accepted then "COMPLETED \($done) of \($accepted) accepted" else null end),
            (if ($m.runStatus.total // 0) != $accepted then "\($m.runStatus.total) rows for \($accepted) accepted starts" else null end),
            (if $failed > 0 then "\($failed) FAILED" else null end),
            (if $open > 0 then "\($open) not terminal" else null end),
            (if $m.drain.timedOut != false then "drain timed out after \($m.drain.waitedSecs) s, residue by status \($by | tojson)" else null end)
          ] | reasons end) ),
    check("correctness.noForeignRows"; "correctness";
          if $m.foreignRows == 0 then "PASS" else "FAIL" end;
          $m.foreignRows; 0;
          if $m.foreignRows == 0 then "no instance row outside this run's id space \($m.runPrefix)"
          else "\($m.foreignRows) instance row(s) outside \($m.runPrefix): leftover state" end),
    ( statements_gate("gate.statementsPerWorkflow"; $s; $expected; s1_relative_allowance)
      | if .status == "NO_BASELINE" then .detail = "no usable baseline: \($noBase)" else . end ),
    advisory_guarded("advisory.ceiling";
      check("advisory.ceiling"; "advisory";
            if ($levels | length) > 0 and ($ceiling.ceilingPerSec | isnum) then "PASS" else "WARN" end;
            $ceiling + {levels: [$levels[] | {level, arrivalPerSec, completionsInWindowPerSec, completionRatio,
                                              backlogAtEnd, backlogBound, sustained}]};
            {sustainRatio: sustain_ratio, backlogBound: "workflowSlots + arrivalPerSec x \(backlog_slack_secs) s"};
            if ($ceiling.ceilingPerSec | isnum | not) then "no level was measured: no ceiling"
            elif $ceiling.ceilingReached then
              "sustained up to \($ceiling.sustainedLevel // "no level") starts/s offered; the most delivered was \($ceiling.ceilingPerSec) completions/s; saturated at \($ceiling.saturatedLevels) (numbers, never a verdict)"
            else "every level sustained, up to \($ceiling.sustainedLevel) starts/s: the ceiling lies above it (\($ceiling.ceilingPerSec) completions/s is a lower bound)" end)),
    advisory_guarded("advisory.startLatency";
      ($k6s1.startMs.p95 // null) as $p95
      | check("advisory.startLatency"; "advisory"; if ($p95 | isnum) and $p95 < 2000 then "PASS" else "WARN" end;
              $k6s1.startMs; {p95: "< 2000 ms"};
              if ($p95 | isnum | not) then "no start latency recorded"
              else "start endpoint (202 after the row commits, before a slot) p95 \($p95 | round2) ms" end)),
    advisory_guarded("advisory.littlesLaw";
      ($levels[0].p50Ms // null) as $p50
      | (if ($slots | isnum) and ($p50 | isnum) and $p50 > 0 then $slots / ($p50 / 1000) else null end) as $cap
      | check("advisory.littlesLaw"; "advisory"; if ($cap | isnum) then "PASS" else "WARN" end;
              {modelCapPerSec: ($cap | round2), workflowSlots: $slots, firstLevelP50Ms: $p50,
               ceilingPerSec: $ceiling.ceilingPerSec};
              null;
              if ($cap | isnum) then
                "Little's law: \($slots) workflow slots / \($p50) ms (unloaded p50, first level) = \($cap | round2) runs/s if the slots were the only limit, next to the measured \($ceiling.ceilingPerSec) completions/s (an explanation, never a verdict)"
              else "the model cap could not be computed (no first-level p50 or slot count)" end)),
    advisory_guarded("advisory.statementsPerLevel";
      ([$levels[] | select((.statements.net | isnum) | not) | .level]) as $missing
      | check("advisory.statementsPerLevel"; "advisory"; if ($missing | length) == 0 and ($levels | length) > 0 then "PASS" else "WARN" end;
              [$levels[] | {level, gross: .statements.gross, net: .statements.net, intervalSecs: .statements.intervalSecs,
                            started: .statements.started, completed: .statements.completed, balanced: .statements.balanced}];
              null;
              if ($missing | length) == 0 then "net statements per completed workflow per level (recorded, not gated; comparable only where balanced, i.e. started within 5 % of completed in the interval): \([$levels[] | "L\(.level) \(.statements.net)\(if .statements.balanced then "" else " (unbalanced)" end)"] | join(", "))"
              else "per-level statement figures not available for level(s) \($missing)" end)),
    advisory_guarded("advisory.slotsUsed";
      ([$levels[] | .slotsUsed.workflowMax] | map(select(isnum))) as $have
      | check("advisory.slotsUsed"; "advisory"; if ($have | length) == ($levels | length) and ($levels | length) > 0 then "PASS" else "WARN" end;
              [$levels[] | {level, slotsUsed}]; {workflowSlots: $slots};
              if ($have | length) == 0 then "not available: maestro_taskqueue_slots_used is not exported (an engine that predates the WP11 slot gauge, or no scrape)"
              elif ($have | length) < ($levels | length) then "partly available: some levels have no slot samples"
              else "workflow slots used per level (max over 5 s samples): \([$levels[] | "L\(.level) \(.slotsUsed.workflowMax)"] | join(", ")) of \($slots)" end)),
    advisory_guarded("advisory.workflowDurationMeter";
      ([$levels[] | .durationMeter.p95Secs] | map(select(isnum))) as $have
      | check("advisory.workflowDurationMeter"; "advisory"; if ($have | length) == ($levels | length) and ($levels | length) > 0 then "PASS" else "WARN" end;
              [$levels[] | {level, durationMeter, rowsP50Ms: .p50Ms, rowsP95Ms: .p95Ms}]; null;
              if ($have | length) == 0 then "not available: maestro_workflow_duration_seconds is not exported (an engine that predates the WP11 duration timer, or no scrape); start-to-terminal comes from the instance rows (levels[].p50Ms/p95Ms)"
              elif ($have | length) < ($levels | length) then "partly available: some levels have no duration-timer samples"
              else "maestro.workflow.duration p95 per level (histogram, over the level window): \([$levels[] | "L\(.level) \(.durationMeter.p95Secs * 1000 | round) ms"] | join(", ")) (rows: levels[].p95Ms)" end)),
    advisory_guarded("advisory.runAbandoned";
      $m.runAbandonedIncrease as $a
      | ([$levels[] | .slotsUsed.workflowMax] | any(isnum)) as $wp11
      | check("advisory.runAbandoned"; "advisory";
              if ($a | isnum) then (if $a == 0 then "PASS" else "WARN" end) elif $wp11 then "PASS" else "WARN" end;
              $a; 0;
              if ($a | isnum) then "\($a) run(s) abandoned during the load (maestro_run_abandoned_total)"
              elif $wp11 then "no maestro_run_abandoned_total series: the counter registers on its first increment, and this engine exports the other WP11 meters, so none was abandoned"
              else "not available: maestro_run_abandoned_total is not exported and neither are the other WP11 meters (an engine that predates them)" end)),
    advisory_guarded("advisory.hikariPool";
      ([$levels[] | .hikari.pendingMax] | map(select(isnum))) as $have
      | check("advisory.hikariPool"; "advisory"; if ($have | length) == ($levels | length) and ($levels | length) > 0 then "PASS" else "WARN" end;
              [$levels[] | {level, hikari}]; {maximumPoolSize: $id.effective.hikariMaximumPoolSize};
              if ($have | length) < ($levels | length) then "Hikari pool metrics not available for every level"
              else "pool of \($id.effective.hikariMaximumPoolSize): pending max per level \([$levels[] | "L\(.level) \(.hikari.pendingMax)"] | join(", ")), active max \([$levels[] | "L\(.level) \(.hikari.activeMax)"] | join(", "))" end))
  ] as $checks
| result($id;
         $m + {statements: $s, levels: $levels, ceiling: $ceiling,
               baselineCandidate: {workflowShape: $m.workflowShape,
                                   statementsPerWorkflowNet: $s.statementsPerWorkflowNet,
                                   netTolerancePerWorkflow: $s.netTolerancePerWorkflow}};
         $checks)
