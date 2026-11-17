# Scenario 5 (s5-pool-health, WP12; finding 3.5a, fixed by WP4): Maestro's
# health component while the host's own database work exhausts the shared
# pool, the database itself healthy. Inputs: $identity, $measurements
# (slurped). Workload and phases: perf/scenarios/s5-pool-health.sh.
# Counters per phase come from k6/health-probe.js (one answer per second
# from /actuator/health/perf and /actuator/health, 5 s client timeout).
#
#   validity.poolExhausted   exhausted phase: >= 80 % of the sampler's samples
#                            show >= hikariMaximumPoolSize - 1 engine backends
#                            active in pg_sleep, and Hikari's pending gauge
#                            reached >= 1 (max_over_time over the window);
#                            otherwise the pool was never exhausted and a
#                            PASS would be vacuous
#   validity.storeReachable  the sampler (role postgres, its own session) saw
#                            no gap > 1 s over every phase: the database
#                            itself stayed reachable. Each phase's samples
#                            must COVER it: first within 1 s of the phase's
#                            from, last within 1 s of its to, no gap > 1 s
#                            between (a sampler that stopped early or began
#                            late leaves the phase unobserved, and its busy
#                            fraction would rest on part of the window;
#                            PR #65 review). The sampler takes 10 samples a
#                            second, so 1 s is ten periods
#   validity.noPoolTimeouts  Hikari's timeout counter did not move: the pool
#                            was busy, not failing
#   validity.healthSamples   every phase answered or timed out >= 80 % of its
#                            planned probes
#   correctness.upWhenIdle   idle and recovered: every Maestro answer UP
#   correctness.holdsCompleted  every hold run COMPLETED, none FAILED
#   correctness.noLoopFault  no DOWN answer explained by a stopped loop or a
#                            failing renewer (maestro_down_other) in any phase
#   gate.notDownWhenBusy (3.5a)  exhausted: 0 DOWN answers (any cause) and 0
#                            unanswered within 5 s
#   advisory.rootHealth      the root aggregate in the exhausted window (MD-7:
#                            Boot's db indicator waits on the same pool); WARN
#                            when it was not UP every time, or not recorded
#   advisory.answerTime      Maestro answer time p50 / max per phase
#   advisory.connectionChurn Hikari connection creations over the run
#                            (Issue 66: an interrupted probe can close one)
include "common";

def s5_min_busy_fraction: 0.8;
def s5_max_gap_secs: 1;
def s5_min_sample_fraction: 0.8;
def counters: ["maestro_up", "maestro_unknown", "maestro_down_store_timed_out", "maestro_down_store_unreachable",
               "maestro_down_other", "maestro_no_answer"];
def phase($m; $n): ($m.phases // [] | if type == "array" then map(select(type == "object" and .name == $n)) | first else null end);
def c($k): (.counters // {})[$k];
def answers: [counters[] as $k | c($k)] | if all(isnum) then add else null end;
def downs: [c("maestro_down_store_timed_out"), c("maestro_down_store_unreachable"), c("maestro_down_other")]
           | if all(isnum) then add else null end;

$identity[0] as $id
| $measurements[0] as $m
| phase($m; "idle") as $idle
| phase($m; "exhausted") as $ex
| phase($m; "recovered") as $rec
| ([$idle, $ex, $rec] | map(select(. != null))) as $ps
| ($m.plan.minBusy // null) as $minBusy
| [
    ($ex.sampler // {}) as $s
    | (if ($s.samples | isnum) and $s.samples > 0 and ($s.matchingSamples | isnum)
       then $s.matchingSamples / $s.samples else null end) as $f
    | ($m.pendingMax.value // null) as $pend
    | (($f | isnum) and $f >= s5_min_busy_fraction and ($pend | isnum) and $pend >= 1) as $ok
    | check("validity.poolExhausted"; "validity"; if $ok then "PASS" else "INVALID" end;
        {busySamples: $s.matchingSamples, samples: $s.samples, fraction: ($f | round4), minBusy: $minBusy,
         maxBusy: $s.maxMatching, hikariPendingMax: $pend};
        {fraction: ">= \(s5_min_busy_fraction)", hikariPendingMax: ">= 1"};
        if $ok then "the pool was exhausted: \($f * 100 | round) % of the exhausted window's samples show >= \($minBusy) engine backends in pg_sleep; Hikari pending reached \($pend)"
        else "the pool was not demonstrably exhausted in the probe window (busy fraction \($f | round4 | tojson), pending max \($pend | tojson)): a healthy answer would prove nothing" end),
    ((try $m.sampler.maxGapSecs catch null) as $g
     | [$ps[] | (.sampler // {}) as $s
        | {phase: .name, maxGapSecs: $s.maxGapSecs,
           leadSecs: (try (($s.firstAt | ts_secs) - (.from | ts_secs) | round4) catch null),
           tailSecs: (try ((.to | ts_secs) - ($s.lastAt | ts_secs) | round4) catch null)}] as $v
     | ($v | map(select([.maxGapSecs, .leadSecs, .tailSecs] | any((isnum | not) or . > s5_max_gap_secs)))) as $bad
     | (($g | isnum) and $g <= s5_max_gap_secs and ($v | length) == 3 and ($bad | length) == 0) as $ok
     | check("validity.storeReachable"; "validity"; if $ok then "PASS" else "INVALID" end;
         {maxGapSecs: $g, samples: $m.sampler.samples, phases: $v};
         {maxGapSecs: "<= \(s5_max_gap_secs)", phases: "lead, tail and gap each <= \(s5_max_gap_secs) s"};
         if $ok then "the database answered the sampler every \($g) s or better, and the samples cover every phase from its start to its end"
         elif ($g | isnum) and $g > s5_max_gap_secs then "the sampler stalled \($g) s: the database itself may have been unreachable"
         else "phase(s) \($bad | map(.phase) | join(", ")) not covered by the sampler (it began late, stopped early or stalled): the phase is unobserved" end)),
    ((try $m.hikari.start.timeouts catch null) as $t0 | (try $m.hikari.end.timeouts catch null) as $t1
     | (($t0 | isnum) and ($t1 | isnum) and $t1 - $t0 == 0) as $ok
     | check("validity.noPoolTimeouts"; "validity"; if $ok then "PASS" else "INVALID" end;
         {start: $t0, end: $t1}; {increase: 0};
         if $ok then "no connection request timed out: the pool was busy, not failing"
         elif ($t0 | isnum) and ($t1 | isnum) then "\($t1 - $t0) connection request(s) timed out: the pool failed, which is not what this scenario judges"
         else "the Hikari timeout counter was not read" end)),
    ([$ps[] | {phase: .name, planned: .plannedSamples, answers: answers}] as $v
     | (($v | length) == 3 and ($v | all((.answers | isnum) and .answers >= s5_min_sample_fraction * .planned))) as $ok
     | check("validity.healthSamples"; "validity"; if $ok then "PASS" else "INVALID" end; $v;
         {answers: ">= \(s5_min_sample_fraction) x planned"};
         if $ok then "every phase has its health samples" else "a phase is missing or has too few health samples" end)),
    ([$idle, $rec] | map(select(. != null) | {phase: .name, up: c("maestro_up"), answers: answers})) as $v
    | (($v | length) == 2 and ($v | all((.answers | isnum) and .answers > 0 and .up == .answers))) as $ok
    | check("correctness.upWhenIdle"; "correctness"; if $ok then "PASS" else "FAIL" end; $v; {up: "every answer"};
        if $ok then "Maestro answered UP every time while the pool was free" else "Maestro was not UP every time while the pool was free" end),
    ((try ($m.holds.runStatus.byStatus // {}) catch {}) as $by | ($m.plan.holdRuns // null) as $h
     | (($h | isnum) and ($by.COMPLETED // 0) == $h and ($by.FAILED // 0) == 0 and $m.holds.drain.timedOut == false) as $ok
     | check("correctness.holdsCompleted"; "correctness"; if $ok then "PASS" else "FAIL" end;
         {byStatus: $by, timedOut: $m.holds.drain.timedOut}; {completed: $h};
         if $ok then "\($h)/\($h) hold runs COMPLETED" else "hold runs did not all COMPLETED: \($by | tojson)" end)),
    ([$ps[] | {phase: .name, downOther: c("maestro_down_other")}] as $v
     | (($v | length) == 3 and ($v | all(.downOther == 0))) as $ok
     | check("correctness.noLoopFault"; "correctness"; if $ok then "PASS" else "FAIL" end; $v; {downOther: 0};
         if $ok then "no DOWN answer named a stopped loop or a failing renewer"
         else "a DOWN answer carried a loop or renewer fault (or no store detail)" end)),
    ($ex | if . == null then {} else {down: downs, noAnswer: c("maestro_no_answer"), unknown: c("maestro_unknown"),
                                     up: c("maestro_up"), timedOut: c("maestro_down_store_timed_out")} end) as $v
    | check("gate.notDownWhenBusy"; "gate";
        if ($v.down | isnum | not) or ($v.noAnswer | isnum | not) then "INVALID"
        elif $v.down == 0 and $v.noAnswer == 0 then "PASS" else "FAIL" end;
        $v; {down: 0, noAnswer: 0};
        if ($v.down | isnum | not) or ($v.noAnswer | isnum | not) then "the exhausted phase's health counters are missing"
        elif $v.down == 0 and $v.noAnswer == 0 then "a busy pool never read DOWN: \($v.unknown) UNKNOWN, \($v.up) UP"
        else "a busy pool read DOWN \($v.down) time(s) (\($v.timedOut) of them store \"timed out\") and went unanswered \($v.noAnswer) time(s) (3.5a)" end),
    advisory_guarded("advisory.rootHealth";
      ($ex.counters // {}) as $c
      | (["root_up", "root_down", "root_other", "root_no_answer"] | map({key: ., value: $c[.]}) | from_entries) as $v
      | ($v | to_entries | all(.value | isnum)) as $all
      | check("advisory.rootHealth"; "advisory"; if $all and $v.root_up > 0 and ($v.root_down + $v.root_other + $v.root_no_answer) == 0 then "PASS" else "WARN" end;
          $v + {answerMs: $c.rootAnswerMs}; {root: "UP every time (MD-7, advisory)"};
          if $all | not then "the root aggregate's counters were not all recorded"
          else "root aggregate while the pool was exhausted: \($v.root_up) UP, \($v.root_down) DOWN, \($v.root_other) other, \($v.root_no_answer) no answer within 5 s (Boot's db indicator waits on the same pool)" end)),
    advisory_guarded("advisory.answerTime";
      [$ps[] | {phase: .name, p50Ms: (.counters.maestroAnswerMs.p50 | round2), maxMs: (.counters.maestroAnswerMs.max | round2)}] as $v
      | check("advisory.answerTime"; "advisory"; if ($v | length) == 3 then "PASS" else "WARN" end; $v; null;
          "Maestro's answer time per phase (recorded, never a verdict)")),
    advisory_guarded("advisory.connectionChurn";
      (($m.hikari.end.creations // null) - ($m.hikari.start.creations // null)) as $d
      | check("advisory.connectionChurn"; "advisory"; if ($d | isnum) and $d == 0 then "PASS" else "WARN" end;
          {creations: $d}; {creations: 0};
          "Hikari connections created during the exhausted and recovered phases: \($d) (Issue 66)"))
  ] as $checks
| result($id; $m; $checks)
