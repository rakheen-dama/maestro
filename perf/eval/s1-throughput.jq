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
# above the top level and ceilingPerSec is a lower bound). sustainedLevel is
# NOISY (20 to 60 across runs of identical code on this shared machine);
# ceilingPerSec is the stabler figure and leads advisory.ceiling's detail.
# Two biases, both small: a level's window includes the ramp from the
# previous level, which offers less than L, so its rates are biased down by a
# few percent; and ceilingPerSec takes the MAXIMUM over levels, which adds a
# small upward bias (the luckiest window wins). So each level also reports
# its HOLD-only rates (window trimmed by the plan's ramp: arrivalInHoldPerSec,
# completionsInHoldPerSec) and the ceiling also ceilingHoldPerSec.
#
# ── Slot-hold time, engine overhead, Little's law ─────────────────────────
# slotHoldMs (per level, DERIVED) = average workflow slots in use (WP11
# gauge, 5 s samples) / completionsInWindowPerSec: how long a run holds its
# workflow slot. 250 ms of it is the sample's own activity time (5 x 50 ms
# activityMillis); engineOverheadMs = slotHoldMs - activities x
# activityMillis. Run-level engineOverheadMsPerWorkflow is taken at the
# ceiling level, where the slots are nearly all busy and the gauge average is
# least quantised. Measured on quiet runs: hold 261-269 ms with 19.0-19.8 of
# 20 slots busy, so the ceiling is about 20 / (0.25 s + 13-19 ms) = 72-77/s.
# This scenario therefore mostly measures the slot limit against the
# sample's activity time: an engine change can move the ceiling only within
# about 72-80/s (80 = 20 / 0.25 s, zero overhead). What it is good for: it
# shows the 20 workflow slots bind before the pool of 10 does (pool pending 0
# at the ceiling on a quiet host); it pins statements per workflow (the
# gate); it measures engine overhead per workflow (hold minus activity time).
# What it is NOT: a measure of engine throughput headroom.
# advisory.littlesLaw compares ceilingPerSec with an UNLOADED model cap,
# slots / the first level's start-to-terminal p50 (rows; nothing queues
# there, so the p50 is an upper-bound proxy for the unloaded hold time), and
# WARNs below 0.9 x that cap: then the slots were not what bound (a
# contended host, or a limit other than the slots).
#
# ── Contention (advisory.contention) ───────────────────────────────────────
# A PASSing run can carry host-contended numbers. The reference is the
# first level's start-to-terminal p50 (rows: precise and unloaded). The run
# is "contended" when, at any level AT OR BELOW the ceiling level (the level
# of ceilingPerSec), slotHoldMs exceeds 1.15 x that reference, counting only
# levels averaging >= 4 busy slots, or the node's CPU saturates there
# (process_cpu_usage or system_cpu_usage averaging >= 0.9). Deviations from
# the first wording of the rule, both forced by data: the first level's
# DERIVED hold time is quantised (one or two busy slots in a 5 s gauge sample
# read 200 or 400 ms against a true ~280 ms), hence the row p50 and the
# 4-slot floor; and the starved run r20261112t204157z was contended AT its
# ceiling level (L40: 446 ms, pool pending 4.3), hence "at or below". Above
# the ceiling level the hold time rises with saturation itself and is not
# judged. Measured: quiet runs stay within 0.98-1.06 x; r20261112t214359z
# reads 464 / 349 ms at L40 / L60 (1.68 / 1.26 x). When it WARNs
# "contended", advisory.ceiling and advisory.littlesLaw say "measured on a
# contended host". Advisory only. Also recorded per level: node CPU
# (process/system_cpu_usage, system_load_average_1m), the containers' CPU
# from `docker stats` (k6, node, postgres; percent of one CPU) and the host's
# load average at the scenario's start, at the load's start and after the
# drain.
#
# ── What validity.plan proves ──────────────────────────────────────────────
# start-ramp.js writes the plan it executed from its own profile table, in the
# executing process's environment; validity.plan requires it to equal the plan
# `k6 inspect` resolved, for the run's --profile, AND requires a k6 runtime
# fact: iterations + dropped_iterations from k6's summary within max(2, 0.5 %)
# of the iterations the plan's stage table implies (the integral of the rate:
# (previous + L) / 2 x ramp + L x hold per level; local 6337.5, full 42975).
# It proves k6 started the planned NUMBER of iterations; it does not prove
# their timing per stage (the per-level arrival counts in the database,
# validity.levels and the level table, show that).
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
# relativeAllowance = s1_relative_allowance below: 0.02, PENDING its evidence:
# the three runs first cited predate validity.plan and are INVALID under this
# evaluator; fix round 1 re-measures on this code and cites only runs that
# PASS it.
include "common";

def s1_relative_allowance: 0.02;
def sustain_ratio: 0.95;
def backlog_slack_secs: 1;
# A level's statement interval is "balanced" when the runs started in it are
# within 5 % of the runs completed in it (see "Per-level statements").
def balanced_fraction: 0.05;
def contention_ratio: 1.15;
def contention_min_slots: 4;
def cpu_saturation: 0.9;
def littles_law_floor: 0.9;
def start_slow_ms: 1000;

# Iterations the plan's stage table implies (ramping-arrival-rate, linear
# ramps, timeUnit 1 s).
def expected_iterations($plan):
  reduce ($plan.levels // [])[] as $l ({prev: ($plan.startRate // ($plan.levels[0] // 0)), n: 0};
    .n += (.prev + $l) / 2 * $plan.rampSecs + $l * $plan.holdSecs | .prev = $l) | .n;

# Average of one container's CPU (docker stats, percent of one CPU) over [$a, $b].
def container_cpu_avg($m; $name; $a; $b):
  [($m.containerStats // [])[] | select(type == "object" and (.at | isnum) and .at >= $a and .at <= $b)
   | .cpuPercent[$name] | select(isnum)] as $v
  | if ($v | length) > 0 then {avg: ($v | add / length | round2), max: ($v | max), samples: ($v | length)} else null end;

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
       holdSecs: ($r.holdSecs // null), arrivalInHoldPerSec: ($r.arrivalInHoldPerSec // null),
       completionsInHoldPerSec: ($r.completionsInHoldPerSec // null),
       slotsUsed: ($p.slotsUsed // null), hikari: ($p.hikari // null), durationMeter: ($p.durationMeter // null),
       nodeCpu: ($p.nodeCpu // null),
       containerCpu: {k6: container_cpu_avg($m; "k6"; $r.windowStartEpoch; $r.windowEndEpoch),
                      node: container_cpu_avg($m; "node-1"; $r.windowStartEpoch; $r.windowEndEpoch),
                      postgres: container_cpu_avg($m; "postgres-1"; $r.windowStartEpoch; $r.windowEndEpoch)},
       slotHoldMs: (($p.slotsUsed.workflowAvg // null) as $avg
                    | if ($avg | isnum) and ($r.completionsInWindowPerSec | isnum) and $r.completionsInWindowPerSec > 0
                      then $avg / $r.completionsInWindowPerSec * 1000 | round2 else null end),
       start: ([($m.startByLevel // [])[] | select(.level == $r.level)] | first // null),
       statements: (try level_statements($m; ($r.windowStartEpoch); ($r.windowEndEpoch); $rate)
                    catch {gross: null, net: null, note: "could not be computed: \(. | tostring)"})}]
   | sort_by(.level)
   | ((($m.workflowShape.activities // null) as $n | ($m.workflowShape.activityMillis // null) as $ms
       | if ($n | isnum) and ($ms | isnum) then $n * $ms else null end)) as $activityMs
   | map(. + {engineOverheadMs: (if (.slotHoldMs | isnum) and ($activityMs | isnum) then .slotHoldMs - $activityMs | round2 else null end)})) as $levels
| ([$levels[] | select(.completionsInWindowPerSec | isnum)] | max_by(.completionsInWindowPerSec) // null) as $ceilingRow
| ($levels[0].p50Ms // null) as $refMs
| ( if $ceilingRow == null or ($refMs | isnum | not) then null
    else
      [$levels[] | select(.level <= $ceilingRow.level)] as $judged
      | ([$judged[] | select((.slotsUsed.workflowAvg | isnum) and .slotsUsed.workflowAvg >= contention_min_slots
                             and (.slotHoldMs | isnum))]) as $eligible
      | ([$judged[] | select(.nodeCpu != null and (((.nodeCpu.processAvg | isnum) and .nodeCpu.processAvg >= cpu_saturation)
                                                   or ((.nodeCpu.systemAvg | isnum) and .nodeCpu.systemAvg >= cpu_saturation)))]) as $cpuHot
      | {referenceMs: $refMs, ceilingLevel: $ceilingRow.level,
         judgedLevels: [$eligible[] | {level, slotHoldMs, ratio: (.slotHoldMs / $refMs | round2)}],
         holdHot: [$eligible[] | select(.slotHoldMs > contention_ratio * $refMs) | .level],
         cpuHot: [$cpuHot[] | .level],
         cpuAvailable: any($judged[]; (.nodeCpu.processAvg | isnum)),
         contended: (([$eligible[] | select(.slotHoldMs > contention_ratio * $refMs)] | length) > 0 or ($cpuHot | length) > 0)}
    end ) as $contention
| (if ($contention.contended // false) then "; measured on a contended host (advisory.contention)" else "" end) as $contNote
| (reduce $levels[] as $l ({knee: null, broken: false};
     if .broken | not then (if $l.sustained then .knee = $l.level else .broken = true end) else . end)
   | .knee) as $knee
| {sustainedLevel: $knee,
   sustainedArrivalPerSec: ([$levels[] | select(.level == $knee) | .arrivalPerSec] | first // null),
   ceilingPerSec: ([$levels[].completionsInWindowPerSec | select(isnum)] | max // null),
   ceilingReached: any($levels[]; .sustained | not),
   ceilingLevel: ($ceilingRow.level // null),
   ceilingHoldPerSec: ([$levels[].completionsInHoldPerSec | select(isnum)] | max // null),
   slotHoldMsAtCeiling: ($ceilingRow.slotHoldMs // null),
   slotsAvgAtCeiling: ($ceilingRow.slotsUsed.workflowAvg // null),
   engineOverheadMsPerWorkflow: ($ceilingRow.engineOverheadMs // null),
   contended: (if $contention == null then null else $contention.contended end),
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
    ( ($m.plan // {} | {profile, levels, rampSecs, holdSecs, preAllocatedVUs, maxVUs}) as $resolved
      | ($m.executedPlan // null) as $ran
      | (try expected_iterations($m.plan) catch null) as $expIt
      | (if ($k6s1.iterations | isnum) and ($k6s1.droppedIterations | isnum)
         then $k6s1.iterations + $k6s1.droppedIterations else null end) as $gotIt
      | (($expIt | isnum) and ($gotIt | isnum)
         and (($gotIt - $expIt) | fabs) <= ([2, 0.005 * $expIt] | max)) as $itOk
      | (($ran | type) == "object" and $ran == ($ran | {profile, levels, rampSecs, holdSecs, preAllocatedVUs, maxVUs})
         and $ran == $resolved and $resolved.profile == $id.profile) as $planOk
      | ($planOk and $itOk) as $ok
      | check("validity.plan"; "validity"; if $ok then "PASS" else "INVALID" end;
          {resolved: $resolved, executed: $ran, runProfile: $id.profile,
           expectedIterations: $expIt, iterationsPlusDropped: $gotIt};
          {plan: "the plan k6 executed (k6-s1-plan.json) == the plan k6 inspect resolved, for this run's profile",
           iterations: "k6 iterations + dropped within max(2, 0.5 %) of the stage table's integral"};
          if $ok then "k6 ran the \($ran.profile) plan the harness recorded: levels \($ran.levels), VUs \($ran.preAllocatedVUs)/\($ran.maxVUs); \($gotIt) iterations (incl. dropped) for \($expIt) planned"
          else [
            (if ($ran | type) != "object" then "k6 wrote no executed plan"
             elif $planOk | not then "k6 executed \($ran | tojson) but the harness recorded \($resolved | tojson) for profile \($id.profile)"
             else null end),
            (if ($gotIt | isnum | not) then "k6 recorded no iteration count"
             elif ($expIt | isnum | not) then "the plan's stage table could not be integrated"
             elif $itOk | not then "k6 started \($gotIt) iterations (incl. dropped) where the plan implies \($expIt)"
             else null end)
          ] | reasons end) ),
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
    # The warm-up's concurrency is its configured VUs (shared-iterations
    # starts min(vus, count) VUs at once; k6's vus_max gauge may not even be
    # sampled in a sub-second warm-up). The load's peak is k6's vus_max, or
    # the plan's maxVUs (its upper bound) when k6 did not sample it.
    ( ($m.warmup.vus // null) as $wv
      | ($m.warmup.count // null) as $wc
      | (if ($wv | isnum) and ($wc | isnum) then [$wv, $wc] | min else null end) as $wPeak
      | ($k6s1.vusMax // $m.plan.maxVUs // null) as $lPeak
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
              "the most delivered was \($ceiling.ceilingPerSec) completions/s at L\($ceiling.ceilingLevel) (the stabler figure; hold-only windows: \(if ($ceiling.ceilingHoldPerSec | isnum) then "\($ceiling.ceilingHoldPerSec)/s" else "not recorded" end)); sustained up to \($ceiling.sustainedLevel // "no level") starts/s offered (noisy: 20-60 across runs of identical code); saturated at \($ceiling.saturatedLevels)\($contNote) (numbers, never a verdict)"
            else "every level sustained, up to \($ceiling.sustainedLevel) starts/s: the ceiling lies above it (\($ceiling.ceilingPerSec) completions/s is a lower bound)\($contNote)" end)),
    advisory_guarded("advisory.startLatency";
      ($k6s1.startMs.p95 // null) as $p95
      | ($k6s1.startMs.max // null) as $max
      | ([$levels[] | select(.start != null) | {level, maxMs: (.start.maxMs | round2), p95Ms: (.start.p95Ms | round2), over1s: .start.over1s}]) as $per
      | check("advisory.startLatency"; "advisory"; if ($p95 | isnum) and $p95 < 2000 then "PASS" else "WARN" end;
              {overall: $k6s1.startMs, perLevel: $per}; {p95: "< 2000 ms"};
              if ($p95 | isnum | not) then "no start latency recorded"
              else "start endpoint (202 after the row commits, before a slot) p95 \($p95 | round2) ms, max \($max | round2) ms"
                + (if ($per | length) > 0
                   then "; per level max ms / starts over 1 s: \([$per[] | "L\(.level) \(.maxMs) / \(.over1s // "n/a")"] | join(", ")) (a finding about the system under test: the generator's VUs are pre-allocated for a 5 s stall)"
                   else "; per-level start timing not recorded" end) end)),
    advisory_guarded("advisory.littlesLaw";
      ($levels[0].p50Ms // null) as $p50
      | (if ($slots | isnum) and ($p50 | isnum) and $p50 > 0 then $slots / ($p50 / 1000) else null end) as $cap
      | ($ceiling.ceilingPerSec) as $c
      | (($m.workflowShape.activities // 0) * ($m.workflowShape.activityMillis // 0)) as $actMs
      | (($cap | isnum) and ($c | isnum) and $c >= littles_law_floor * $cap) as $ok
      | check("advisory.littlesLaw"; "advisory"; if $ok then "PASS" else "WARN" end;
              {unloadedModelCapPerSec: ($cap | round2), workflowSlots: $slots, firstLevelP50Ms: $p50,
               ceilingPerSec: $c, ceilingLevel: $ceiling.ceilingLevel,
               slotHoldMsAtCeiling: $ceiling.slotHoldMsAtCeiling, slotsAvgAtCeiling: $ceiling.slotsAvgAtCeiling,
               activityMsPerWorkflow: $actMs, engineOverheadMsPerWorkflow: $ceiling.engineOverheadMsPerWorkflow};
              {ceilingPerSec: ">= \(littles_law_floor) x unloadedModelCapPerSec"};
              if ($cap | isnum | not) or ($c | isnum | not) then "the model cap could not be computed (no first-level p50, slot count or ceiling)"
              else
                "measured: at L\($ceiling.ceilingLevel) the \($slots) workflow slots averaged \($ceiling.slotsAvgAtCeiling // "n/a") busy with a derived hold time of \($ceiling.slotHoldMsAtCeiling // "n/a") ms per run, of which \($actMs) ms is the sample's own activity time, so engine overhead is \($ceiling.engineOverheadMsPerWorkflow // "n/a") ms per workflow; the ceiling \($c)/s is the slot limit against that hold time. Unloaded model cap \($slots) / \($p50) ms (first-level start-to-terminal p50, a proxy for the unloaded hold) = \($cap | round2)/s"
                + (if $ok then "; the measured ceiling is within \(littles_law_floor) x of it: the slots bound"
                   else "; the measured ceiling is BELOW \(littles_law_floor) x of it: the slots did not bind (a contended host, or another limit)" end)
                + $contNote + " (an explanation, never a verdict)"
              end)),
    advisory_guarded("advisory.contention";
      check("advisory.contention"; "advisory";
            if $contention == null then "WARN" elif $contention.contended then "WARN"
            elif ($contention.judgedLevels | length) == 0 and ($contention.cpuAvailable | not) then "WARN" else "PASS" end;
            ($contention // {}) + {levels: [$levels[] | {level, slotHoldMs, slotsAvg: .slotsUsed.workflowAvg,
                                                         hikariPendingAvg: .hikari.pendingAvg, nodeCpu, containerCpu}],
                                   hostLoad: ($m.hostLoad // null)};
            {slotHoldMs: "<= \(contention_ratio) x the first level's start-to-terminal p50, at levels <= the ceiling level averaging >= \(contention_min_slots) busy slots",
             nodeCpu: "process_cpu_usage and system_cpu_usage average < \(cpu_saturation) there"};
            if $contention == null then "not available: no ceiling level or first-level p50"
            elif $contention.contended then
              "contended: " + ([
                (if ($contention.holdHot | length) > 0 then "slot-hold time above \(contention_ratio) x the \($contention.referenceMs) ms reference at L\($contention.holdHot | map(tostring) | join(", L"))" else null end),
                (if ($contention.cpuHot | length) > 0 then "node CPU saturated at L\($contention.cpuHot | map(tostring) | join(", L"))" else null end)
              ] | reasons) + "; the ceiling and sustained level of this run are host artefacts, not the engine's"
            elif ($contention.judgedLevels | length) == 0 and ($contention.cpuAvailable | not) then
              "not available: no level at or below the ceiling has slot-gauge data with >= \(contention_min_slots) busy slots, and node CPU is not recorded"
            else
              "quiet: slot-hold time within \(contention_ratio) x the \($contention.referenceMs) ms reference at every judged level (\([$contention.judgedLevels[] | "L\(.level) \(.ratio)x"] | join(", ")))"
              + (if $contention.cpuAvailable then ", node CPU below \(cpu_saturation)" else "; node CPU not available" end)
              + (if any($levels[]; .containerCpu.k6 != null) then "" else "; k6 container CPU not available" end)
            end)),
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
      | ($m.durationMeterTopBucketSecs // null) as $top
      | def q($v): if ($v | isnum | not) then "n/a"
                   elif ($top | isnum) and $v >= $top then ">= \($top * 1000 | round) ms (clipped)"
                   else "\($v * 1000 | round) ms" end;
      ([$levels[] | select(($top | isnum) and (((.durationMeter.p50Secs | isnum) and .durationMeter.p50Secs >= $top)
                                               or ((.durationMeter.p95Secs | isnum) and .durationMeter.p95Secs >= $top))) | .level]) as $clipped
      | check("advisory.workflowDurationMeter"; "advisory"; if ($have | length) == ($levels | length) and ($levels | length) > 0 then "PASS" else "WARN" end;
              {topFiniteBucketSecs: $top, clippedLevels: $clipped,
               levels: [$levels[] | {level, durationMeter, rowsP50Ms: .p50Ms, rowsP95Ms: .p95Ms}]}; null;
              if ($have | length) == 0 then "not available: maestro_workflow_duration_seconds is not exported (an engine that predates the WP11 duration timer, or no scrape); start-to-terminal comes from the instance rows (levels[].p50Ms/p95Ms)"
              elif ($have | length) < ($levels | length) then "partly available: some levels have no duration-timer samples"
              else "maestro.workflow.duration p50 / p95 per level (histogram, over the level window): \([$levels[] | "L\(.level) \(q(.durationMeter.p50Secs)) / \(q(.durationMeter.p95Secs))"] | join(", "))"
                + (if ($clipped | length) > 0 then "; at L\($clipped | map(tostring) | join(", L")) a quantile reaches the timer's top finite bucket (\($top) s): the true value is at least that, and the row-derived start-to-terminal (levels[].p50Ms/p95Ms) is authoritative"
                   elif ($top | isnum | not) then "; the timer's top finite bucket was not recorded, so a clipped quantile cannot be told apart: the row-derived figures are authoritative"
                   else "; rows: levels[].p50Ms/p95Ms" end) end)),
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
