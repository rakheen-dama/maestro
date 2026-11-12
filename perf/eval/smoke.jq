# Smoke: 20 short chain workflows must all complete, nothing foreign may be in
# the store, and the engine's statements must be measurable (and the
# harness's own excluded). Inputs: $identity, $measurements, $baseline
# (slurped arrays).
include "common";

$identity[0] as $id
| $measurements[0] as $m
| $m.expected as $n
| ($m.runStatus.byStatus // {}) as $by
| ($by.COMPLETED // 0) as $done
| ($by.FAILED // 0) as $failed
| $m.window as $w
| (if $done > 0 then ($w.maestroCalls / $done | round2) else null end) as $spw
| [
    check("correctness.allCompleted"; "correctness";
          if $done == $n and $failed == 0 then "PASS" else "FAIL" end;
          {completed: $done, failed: $failed, byStatus: $by, drainTimedOut: $m.drain.timedOut,
           drainSecs: $m.drain.waitedSecs};
          {completed: $n, failed: 0};
          if $done == $n and $failed == 0 then "\($n)/\($n) COMPLETED"
          else "COMPLETED \($done)/\($n), FAILED \($failed)\(if $m.drain.timedOut then ", drain timed out" else "" end)" end),
    check("correctness.noForeignRows"; "correctness";
          if $m.foreignRows == 0 then "PASS" else "FAIL" end;
          $m.foreignRows; 0;
          if $m.foreignRows == 0 then "no instance row outside this run's prefix"
          else "\($m.foreignRows) instance row(s) outside this run's prefix: leftover state" end),
    check("gate.statementsMeasured"; "gate";
          if $spw != null and $spw >= 5 and $spw <= 500 and $w.harnessCalls > 0 then "PASS" else "FAIL" end;
          {statementsPerWorkflow: $spw, maestroCalls: $w.maestroCalls, harnessCalls: $w.harnessCalls};
          {statementsPerWorkflow: [5, 500], harnessCalls: "> 0"};
          "engine statements (role maestro) per completed workflow; harness statements (role postgres) counted apart"),
    check("advisory.latency"; "advisory"; "PASS";
          $m.latency; null;
          "end-to-end p95 \($m.latency.p95Ms) ms (recorded, never a verdict)")
  ] as $checks
| result($id;
         $m + {derived: {statementsPerWorkflow: $spw},
               baselineCandidate: {workflowShape: $m.workflowShape, statementsPerWorkflow: $spw}};
         $checks)
