# Scenario 1 (s1-throughput): the throughput ceiling of one node at
# Maestro's true defaults. Inputs: $identity, $measurements, $baseline
# (slurped arrays). Measurements: perf/scenarios/s1-throughput.sh.
#
# D4: numbers first. The ceiling and every latency figure are REPORTED
# (advisories), never gated. The verdict rests on validity, on correctness
# (every start k6 made was accepted: passed start checks == iterations and
# none failed; every accepted start COMPLETED, nothing failed, nothing left)
# and on the statement gate. A refused start is a correctness FAIL whatever
# refused it: the full run r20261116t234614z (PASS when it ran) lost one start
# of 42973 to a connection the node closed ("Request Failed ... EOF" in its k6
# log), created no row for it, and is FAIL under this rule.
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
# slotHoldMs (per level, DERIVED, coarse) = average workflow slots in use
# (WP11 gauge, 5 s samples, whole level window) / completionsInWindowPerSec.
# 250 ms of any hold is the sample's own activity time (5 x 50 ms
# activityMillis); engineOverheadMs = slotHoldMs - activities x
# activityMillis. These per-level figures are quantised (one gauge sample
# moves them by ms) and the window includes the ramp, so the RUN-LEVEL
# figure uses a robust basis instead:
#   engineOverheadMsPerWorkflow = hold - activity time, hold = mean workflow
#   slots in use / completion rate, both over the ceiling level's HOLD only:
#   [t1, t2] = the first and last cumulative samples inside [ws + ramp, we];
#   rate = completed(t2) - completed(t1) over t2 - t1; mean slots from the
#   run's own prometheus.json series (5 s steps) inside [t1, t2]. At a
#   saturated ceiling the slots are pinned at the limit (every gauge sample
#   20 in all four quiet runs), so the gauge adds no noise.
#   engineOverheadResolutionMs = 2 standard errors of the mean of the
#   per-interval rates, in ms of hold (plus 1 / samples of a slot if the
#   slots were not pinned): about 1.4-4 ms within a run on the quiet runs.
#   Between quiet runs on this shared machine the figure spread more:
#   16.65 / 25.17 / 19.85 / 25.93 ms (r20261112t210341z, r20261112t213818z,
#   r20261112t222511z, r20261112t223114z), so differences under about 10 ms
#   between runs are not meaningful here. Without prometheus.json the old
#   whole-window figure is used and labelled so (resolution not stated).
# The ceiling model: ceiling = busy slots / (activity time + overhead), at
# most 20 / 0.25 s = 80/s (zero overhead): bounded ABOVE, not below. Engine
# overhead lowers it without limit (model values): 20 / (0.25 + 0.020) =
# 74.1/s at 20 ms, 20 / (0.25 + 0.120) = 54.1/s at 20 + 100 ms. On the four
# quiet runs the hold-window rate at the ceiling was 72.5-75.0/s and the
# overhead 16.7-25.9 ms, consistent with the model. So this scenario detects
# an overhead REGRESSION of roughly 10 ms per workflow or more on this
# machine (the between-run spread of both the overhead and the ceiling:
# 2.5/s of ceiling is about 9 ms of hold at 72/s); a quiet dedicated host
# would tighten that toward the within-run resolution. It cannot show an
# IMPROVEMENT beyond 80/s. What it is good for: showing that the 20 workflow
# slots bind before the pool of 10 does (pool pending 0 at the ceiling on a
# quiet host); pinning statements per workflow (the gate); measuring engine
# overhead per workflow. What it is NOT: a measure of engine throughput
# headroom.
# Busy slots at the ceiling level in the four valid quiet runs: 18.67-19.22
# averaged over the whole level window (ramp included), 20.0 in every hold
# sample.
# advisory.littlesLaw compares ceilingPerSec with an UNLOADED model cap,
# slots / the first level's start-to-terminal p50 (rows; nothing queues
# there, so the p50 is an upper-bound proxy for the unloaded hold time), and
# WARNs below 0.9 x that cap: then the slots were not what bound (a
# contended host, or a limit other than the slots).
#
# ── Contention (advisory.contention) ───────────────────────────────────────
# A PASSing run can carry host-contended numbers. Four parts; any one makes
# it WARN "contended", and the detail says which fired:
#   holdTime    slotHoldMs > 1.15 x the reference (the first level's
#               start-to-terminal p50, rows: precise and unloaded) at a
#               JUDGED level averaging >= 4 busy slots. Judged = every level
#               at or below the ceiling level (the level of ceilingPerSec)
#               AND every level offered below the slot model's cap (slots /
#               reference): the second set closes the escape where contention
#               at the top drags the ceiling level down and the hurt level is
#               no longer at or below it. On the recorded runs the union
#               judges the same levels as before, so quiet runs stay quiet
#               (0.83-1.03 x) and r20261112t214359z (L40 1.68 x, L60 1.26 x)
#               and r20261112t204157z (L40 1.60 x) stay contended.
#   nodeCpu     process_cpu_usage or system_cpu_usage averaging >= 0.9 at a
#               judged level (on JDK 25 both are container-scoped, 2 CPUs).
#   hostLoad    the host's 1-minute load average per CPU at the scenario's
#               start or after the drain (measurements.hostLoad) > 2.0. The
#               recorded quiet runs reached 1.21 (r20261112t222511z, start)
#               and 0.81, so 1.0 would flag a quiet run; the starved run 2
#               was at about 8.7 per CPU (uptime during the run, not
#               recorded in it). No run is recorded between 1.21 per CPU (a
#               quiet run) and about 8.7 per CPU (a contended run, observed
#               outside the run directory), so where in that gap the
#               threshold sits is a judgement. It does NOT separate
#               r20261112t214359z (about 0.95 per CPU at its start, also
#               only from uptime): the holdTime part catches that one. Runs
#               without the record report this part as "not recorded".
#   reference   the reference itself is slow: the first level's p50 > the
#               sample's activity time (activities x activityMillis) + 40 ms.
#               The recorded runs' first-level p50s are 275.6-281.5 ms (25.6-
#               31.5 ms over 250), contended ones included, so 290 ms leaves
#               8.5 ms over the slowest. This narrows the UNIFORM-contention
#               blind spot of holdTime (a host slow at every level, the first
#               included, raises the reference with everything else and reads
#               quiet). This part has not fired on any recorded run: both
#               known contended runs had an unremarkable first-level p50
#               (278.9 and 276.3 ms) and were caught by holdTime, so it is a
#               guard that is reasoned, not yet validated by a real case. It
#               also fires on an engine that got about 10 ms slower per
#               workflow: the detail says "a contended host or a slower
#               engine".
# Remaining blind spot: uniform contention mild enough to keep the first
# level's p50 under 290 ms and the load average under 2 per CPU. Deviations
# from the first wording of the rule, forced by data: the first level's
# DERIVED hold time is quantised (one or two busy slots in a 5 s gauge sample
# read 200 or 400 ms against a true ~280 ms), hence the row p50 and the
# 4-slot floor; the starved run was contended AT its ceiling level, hence
# "at or below". Above both the ceiling level and the cap the hold time rises
# with saturation itself and is not judged. When it WARNs "contended",
# advisory.ceiling and advisory.littlesLaw say "measured on a contended host".
# Advisory only. Also recorded per level: node CPU (process/system_cpu_usage,
# system_load_average_1m) and the containers' CPU from `docker stats` (k6,
# node, postgres; percent of one CPU).
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
# relativeAllowance = s1_relative_allowance below: 0.02, from measurements.
# Every run cited here has its result.json committed under
# perf/.evidence/wp11/<runId>.result.json (the pre-merge runs re-evaluated by
# the current evaluator without a baseline; the figures below are derived
# from their measurements.statements: maestroCalls, lock-cleaner calls,
# backgroundCallsExpectedInLoadWindow and completed).
# Today's engine (main 525b287 merged; no per-activity lock, 3.10): cold
# local runs, one node, 6337 workflows each, net statements per completed
# workflow over the whole load window, admin events off unless stated:
#   r20261116t221747z 26.00 (164862 calls)
#   r20261116t222856z 26.00 (164858 calls)
#   r20261116t222331z 26.00 (164860 calls; admin events ON, 85644 records)
#   r20261116t225610z 26.00 (164857 calls; admin events ON, 85644 records)
# a spread of 5 calls over 6337 workflows (0.0008 per workflow), with admin
# events on or off: publishing lifecycle events costs no database
# statement, so the baseline match does not include the setting (the
# baseline records it as adminEventsEnabled). All four ran on a contended
# host (advisory.contention: host load 7.7-14.6 per CPU) and the figure did
# not move with it. The per-activity lock's removal took exactly 10
# statements per workflow (2 per live activity x 5) off the earlier figure:
# Before it (engine at 4c930c4..f6518d0), four cold local runs that PASS
# this evaluator and that advisory.contention judges quiet:
#   r20261112t210341z 36.00 (228229 calls; VUs 50/200, before 4bf45b6;
#                              quiet on slot-hold time ALONE: no node CPU
#                              or host load recorded)
#   r20261112t213818z 36.00 (228232 calls; quiet on slot-hold time ALONE)
#   r20261112t222511z 36.00 (228229 calls; quiet on every part; host load
#                              average 12.1 on 10 CPUs at its start)
#   r20261112t223114z 36.00 (228232 calls; quiet on every part; host load
#                              7.8)
# a spread of 3 calls (0.001 %). The full profile's PASSing run
# r20261112t214359z (contended) gave 36.01 over 42974 workflows. Runs
# r20261112t203520z, r20261112t204157z and r20261112t204918z (36.00 / 36.01
# / 36.00), first cited here, are INVALID under this evaluator (they predate
# the executed-plan record) and are no longer evidence. The started 0.10
# would be a band of +/- 2.6 statements at 26: blind to one added statement
# per workflow (+3.8 %), the smallest real regression. 0.02 is +/- 0.52: it
# catches that and is still far above the observed spread. What could move
# the figure without a code change, and how much: recovery polls page over
# the backlog (at most about 70 calls a minute at the full profile's peak
# backlog, about 0.03 per workflow, consistent with the full run's +0.01),
# CAS retries and lock contention (not observed); lock-cleaner passes are
# subtracted exactly (validity.sweeps); a clock-skew read (perf/run.sh known
# limit 5) is one call in a window.
include "common";

def s1_relative_allowance: 0.02;
def sustain_ratio: 0.95;
def backlog_slack_secs: 1;
# A level's statement interval is "balanced" when the runs started in it are
# within 5 % of the runs completed in it (see "Per-level statements").
def balanced_fraction: 0.05;
def contention_ratio: 1.15;
def contention_min_slots: 4;
def littles_law_floor: 0.9;
def start_slow_ms: 1000;
def reference_margin_ms: 40;

# Engine overhead on the robust basis (see the header): the ceiling level's
# hold window, slots from prometheus.json, rate from the cumulative samples.
def hold_window_overhead($m; $prom; $row; $slotLimit; $activityMs):
  ($m.samples // [] | map(select(type == "object") | {t: (.snapshotAt | ts_secs), done: .completed})
   | map(select((.t | isnum) and (.done | isnum))) | sort_by(.t)) as $smp
  | (($row.windowStartEpoch // null) as $ws | if ($ws | isnum) and ($m.plan.rampSecs | isnum) then $ws + $m.plan.rampSecs else null end) as $a
  | ($row.windowEndEpoch // null) as $b
  | ([$smp[] | select(.t >= $a and .t <= $b)]) as $in
  | ([$prom.queries[]? | select(.query == "maestro_taskqueue_slots_used") | .result[]?
      | select(.metric.kind == "workflow") | .values[]?] | group_by(.[0])
     | map({t: .[0][0], v: (map(.[1] | tonumber) | add)})) as $series
  | if ($a | isnum | not) or ($b | isnum | not) or ($in | length) < 3 then null
    else
      ($in[0]) as $s1 | ($in[-1]) as $s2
      | ([$series[] | select(.t >= $s1.t and .t <= $s2.t) | .v]) as $sv
      | if ($sv | length) == 0 or ($s2.t <= $s1.t) or ($activityMs | isnum | not) then null
        else
          (($s2.done - $s1.done) / ($s2.t - $s1.t)) as $rate
          | ($sv | add / length) as $avg
          | ([range(1; $in | length) as $i | ($in[$i].done - $in[$i-1].done) / ($in[$i].t - $in[$i-1].t)]) as $rs
          | ($rs | add / length) as $mu
          | ((($rs | map((. - $mu) * (. - $mu)) | add) / (($rs | length) - 1)) | sqrt / (($rs | length) | sqrt)) as $se
          | ($avg / $rate * 1000) as $hold
          | (($sv | all(. == $slotLimit)) ) as $pinned
          | {holdMs: ($hold | round2), overheadMs: ($hold - $activityMs | round2),
             resolutionMs: ((2 * $se / $mu * $hold) + (if $pinned then 0 else 1 / ($sv | length) / $rate * 1000 end) | . * 10 | round / 10),
             slotsAvg: ($avg | round2), slotsPinned: $pinned, gaugeSamples: ($sv | length),
             ratePerSec: ($rate | round2), rateIntervals: ($rs | length), windowSecs: ($s2.t - $s1.t | round2),
             basis: "hold window of L\($row.level): \($s2.t - $s1.t | round) s between cumulative samples, \($rs | length) rate intervals, \($sv | length) slot-gauge samples\(if $pinned then " all at the limit" else "" end)"}
        end
    end;

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
| load_window_problems($m.window) as $wProblems
| ($m.window | if type == "object" then . else {} end) as $w
| statement_figures($m.background; $m.window; $done; $replicas) as $s
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
| ((($m.workflowShape.activities // null) as $n | ($m.workflowShape.activityMillis // null) as $ms
    | if ($n | isnum) and ($ms | isnum) then $n * $ms else null end)) as $activityMs
| (if ($slots | isnum) and ($refMs | isnum) and $refMs > 0 then $slots / ($refMs / 1000) else null end) as $modelCap
| (if $ceilingRow == null then null
   else try hold_window_overhead($m; ($prometheus[0] // {});
                                 ([($m.levelRows // [])[] | select(.level == $ceilingRow.level)] | first);
                                 $slots; $activityMs) catch null end) as $hw
| ($m.hostLoad // null) as $hl
| load_per_cpu([($hl.scenarioStart // null), ($hl.afterDrain // null)]) as $loadPerCpu
| ( if $ceilingRow == null or ($refMs | isnum | not) then null
    else
      [$levels[] | select(.level <= $ceilingRow.level or (($modelCap | isnum) and .offeredPerSec < $modelCap))] as $judged
      | ([$judged[] | select((.slotsUsed.workflowAvg | isnum) and .slotsUsed.workflowAvg >= contention_min_slots
                             and (.slotHoldMs | isnum))]) as $eligible
      | ([$judged[] | select(.nodeCpu != null and (((.nodeCpu.processAvg | isnum) and .nodeCpu.processAvg >= cpu_saturation)
                                                   or ((.nodeCpu.systemAvg | isnum) and .nodeCpu.systemAvg >= cpu_saturation)))]) as $cpuHot
      | {referenceMs: $refMs, ceilingLevel: $ceilingRow.level,
         judgedLevels: [$eligible[] | {level, slotHoldMs, ratio: (.slotHoldMs / $refMs | round2)}],
         holdHot: [$eligible[] | select(.slotHoldMs > contention_ratio * $refMs) | .level],
         cpuHot: [$cpuHot[] | .level],
         cpuAvailable: any($judged[]; (.nodeCpu.processAvg | isnum)),
         modelCapPerSec: ($modelCap | round2),
         hostLoadPerCpu: (if $hl == null then "not recorded" else $loadPerCpu end),
         hostLoadHot: (($loadPerCpu | length) > 0 and ($loadPerCpu | max) > host_load_per_cpu_max),
         referenceSlow: (($activityMs | isnum) and $refMs > $activityMs + reference_margin_ms),
         referenceLimitMs: (if ($activityMs | isnum) then $activityMs + reference_margin_ms else null end)}
      | .fired = ([(if (.holdHot | length) > 0 then "holdTime" else null end),
                   (if (.cpuHot | length) > 0 then "nodeCpu" else null end),
                   (if .hostLoadHot then "hostLoad" else null end),
                   (if .referenceSlow then "reference" else null end)] | map(select(. != null)))
      | .contended = ((.fired | length) > 0)
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
   engineOverheadMsPerWorkflow: (if $hw != null then $hw.overheadMs else ($ceilingRow.engineOverheadMs // null) end),
   engineOverheadResolutionMs: (if $hw != null then $hw.resolutionMs else null end),
   engineOverheadBasis: (if $hw != null then $hw.basis
                         elif ($ceilingRow.engineOverheadMs // null) != null then "whole level window, coarse (no prometheus.json slot series or too few samples): resolution not stated"
                         else null end),
   engineOverheadMsWholeWindow: ($ceilingRow.engineOverheadMs // null),
   holdWindow: $hw,
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
    check("validity.loadWindow"; "validity"; if ($wProblems | length) == 0 then "PASS" else "INVALID" end;
          ($m.window | if type == "object" then {windowSecs, maestroCalls, maestroCallsTopLevel} else . end);
          {windowSecs: "> 0", maestroCalls: "a number", maestroCallsTopLevel: "a number"};
          if ($wProblems | length) == 0 then "load window of \($w.windowSecs) s recorded with its engine calls"
          else "the statement figures cannot be computed: \($wProblems | join("; "))" end),
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
    # Every start k6 made must have been accepted: one check ("start 202")
    # per iteration, so passed checks == iterations and none failed. The rows
    # are compared with the accepted starts, so without this a run whose
    # starts were refused would pass on the ones that got through.
    ( ($k6s1.iterations // null) as $its
      | ($k6s1.checksPassed // null) as $cp
      | ($k6s1.checksFailed // null) as $cf
      | (($its | isnum) and ($cp | isnum) and ($cf | isnum) and $cf == 0 and $cp == $its and $accepted == $cp) as $startsOk
      | ($startsOk and $accepted > 0 and $done == $accepted and $failed == 0 and $open == 0
       and ($m.runStatus.total // 0) == $accepted and $m.drain.timedOut == false) as $ok
      | check("correctness.allCompleted"; "correctness"; if $ok then "PASS" else "FAIL" end;
          {accepted: $accepted, iterations: $its, checksPassed: $cp, checksFailed: $cf,
           completed: $done, failed: $failed, nonTerminal: $open, total: $m.runStatus.total,
           byStatus: $by, drainTimedOut: $m.drain.timedOut, drainSecs: $m.drain.waitedSecs};
          {checksFailed: 0, checksPassed: "== k6 iterations (every start accepted)",
           completed: "== accepted starts (k6 checks passed on name:start)", failed: 0, nonTerminal: 0};
          if $ok then "\($accepted)/\($accepted) starts accepted and COMPLETED; drained in \($m.drain.waitedSecs) s"
          else [
            (if ($its | isnum | not) or ($cp | isnum | not) or ($cf | isnum | not)
               then "k6 recorded no start check counts (iterations \($its | tojson), passed \($cp | tojson), failed \($cf | tojson)): whether every start was accepted is unknown"
             elif $cf > 0 then "\($cf) of \($its) starts rejected (k6 check \"start 202\" failed)"
             elif $cp != $its then "\($cp) passed start checks for \($its) k6 iterations"
             else null end),
            (if ($cp | isnum) and $accepted != $cp then "accepted \($accepted) differs from k6's \($cp) passed start checks" else null end),
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
               activityMsPerWorkflow: $actMs, engineOverheadMsPerWorkflow: $ceiling.engineOverheadMsPerWorkflow,
               engineOverheadResolutionMs: $ceiling.engineOverheadResolutionMs, holdWindow: $hw};
              {ceilingPerSec: ">= \(littles_law_floor) x unloadedModelCapPerSec"};
              if ($cap | isnum | not) or ($c | isnum | not) then "the model cap could not be computed (no first-level p50, slot count or ceiling)"
              else
                (if $hw != null then
                   "measured over the hold window of L\($ceiling.ceilingLevel): \($hw.slotsAvg) of \($slots) workflow slots busy\(if $hw.slotsPinned then " (pinned)" else "" end) at \($hw.ratePerSec) completions/s, a hold of \($hw.holdMs) ms per run, of which \($actMs) ms is the sample's own activity time, so engine overhead is \($hw.overheadMs) ms +/- \($hw.resolutionMs) ms per workflow (+/- = 2 standard errors within this run; between runs on a shared host differences under about 10 ms are not meaningful)"
                 else
                   "measured over the whole window of L\($ceiling.ceilingLevel) (coarse; no hold-window basis): \($ceiling.slotsAvgAtCeiling // "n/a") of \($slots) slots busy, derived hold \($ceiling.slotHoldMsAtCeiling // "n/a") ms, engine overhead \($ceiling.engineOverheadMsPerWorkflow // "n/a") ms per workflow (resolution not stated; differences under about 10 ms between runs are not meaningful)"
                 end)
                + "; the ceiling \($c)/s is bounded above by \($slots) / \($actMs) ms = \(if $actMs > 0 then $slots / ($actMs / 1000) | round2 else "n/a" end)/s and lowered by engine overhead. Unloaded model cap \($slots) / \($p50) ms (first-level start-to-terminal p50, a proxy for the unloaded hold) = \($cap | round2)/s"
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
              "contended (\($contention.fired | join(", "))): " + ([
                (if ($contention.holdHot | length) > 0 then "slot-hold time above \(contention_ratio) x the \($contention.referenceMs) ms reference at L\($contention.holdHot | map(tostring) | join(", L"))" else null end),
                (if ($contention.cpuHot | length) > 0 then "node CPU saturated at L\($contention.cpuHot | map(tostring) | join(", L"))" else null end),
                (if $contention.hostLoadHot then "host load average \($contention.hostLoadPerCpu | max) per CPU, above \(host_load_per_cpu_max)" else null end),
                (if $contention.referenceSlow then "the first level's start-to-terminal p50 \($contention.referenceMs) ms exceeds the \($contention.referenceLimitMs) ms limit (activity time + \(reference_margin_ms) ms): uniformly slow, a contended host or a slower engine" else null end)
              ] | reasons) + "; the ceiling and sustained level of this run are not the engine's alone"
            elif ($contention.judgedLevels | length) == 0 and ($contention.cpuAvailable | not) then
              "not available: no level at or below the ceiling has slot-gauge data with >= \(contention_min_slots) busy slots, and node CPU is not recorded"
            else
              "quiet: slot-hold time within \(contention_ratio) x the \($contention.referenceMs) ms reference at every judged level (\([$contention.judgedLevels[] | "L\(.level) \(.ratio)x"] | join(", "))); reference within \($contention.referenceLimitMs) ms"
              + (if $contention.cpuAvailable then ", node CPU below \(cpu_saturation)" else "; node CPU not available" end)
              + (if ($contention.hostLoadPerCpu | type) == "string" then "; host load not recorded"
                 elif ($contention.hostLoadPerCpu | length) == 0 then "; host load not recorded"
                 else ", host load \($contention.hostLoadPerCpu | max) per CPU (<= \(host_load_per_cpu_max))" end)
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
