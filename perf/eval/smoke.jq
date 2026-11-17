# Smoke: 20 short chain workflows must all complete, nothing foreign may be in
# the store, and the engine's statements must be measurable (and the
# harness's own excluded). Inputs: $identity, $measurements, $baseline
# (slurped arrays). Statement figures: see the header of common.jq: the
# gross figure includes background engine statements, the gate uses the net.
# The smoke has no expected value, so gate.statementsMeasured is a wide
# absolute range ([5, 500] net per workflow) that proves the measurement
# works; it does not call statements_gate and applies no tolerance.
include "common";

$identity[0] as $id
| $measurements[0] as $m
| $m.expected as $n
| ($m.runStatus.byStatus // {}) as $by
| ($by.COMPLETED // 0) as $done
| ($by.FAILED // 0) as $failed
| load_window_problems($m.window) as $wProblems
| ($m.window | if type == "object" then . else {} end) as $w
| statement_figures($m.background; $m.window; $done; $id.config.replicas) as $s
| [
    background_check($m.background; $id.config.replicas),
    check("validity.loadWindow"; "validity"; if ($wProblems | length) == 0 then "PASS" else "INVALID" end;
          ($m.window | if type == "object" then {windowSecs, maestroCalls, maestroCallsTopLevel} else . end);
          {windowSecs: "> 0", maestroCalls: "a number", maestroCallsTopLevel: "a number"};
          if ($wProblems | length) == 0 then "load window of \($w.windowSecs) s recorded with its engine calls"
          else "the statement figures cannot be computed: \($wProblems | join("; "))" end),
    check("validity.roleSplit"; "validity";
          if $w.maestroCalls > 0 and $w.harnessCalls > 0 then "PASS" else "INVALID" end;
          {maestroCalls: $w.maestroCalls, harnessCalls: $w.harnessCalls};
          {maestroCalls: "> 0", harnessCalls: "> 0"};
          "engine (role maestro) and harness (role postgres) statements are both seen and counted apart"),
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
          if $m.foreignRows == 0 then "no instance row outside this run's id space \($m.runPrefix)"
          else "\($m.foreignRows) instance row(s) outside \($m.runPrefix): leftover state" end),
    check("gate.statementsMeasured"; "gate";
          if ($s.statementsPerWorkflowNet | isnum) and $s.statementsPerWorkflowNet >= 5
             and $s.statementsPerWorkflowNet <= 500 then "PASS" else "FAIL" end;
          {statementsPerWorkflowNet: $s.statementsPerWorkflowNet,
           statementsPerWorkflowGross: $s.statementsPerWorkflowGross,
           netTolerancePerWorkflow: $s.netTolerancePerWorkflow};
          {statementsPerWorkflowNet: [5, 500]};
          "net engine statements per completed workflow (background subtracted), wide absolute range, no tolerance applied; gross includes background engine statements"),
    advisory_guarded("advisory.latency";
      ($m.latency.p95Ms | isnum) as $has
      | check("advisory.latency"; "advisory"; if $has then "PASS" else "WARN" end;
              $m.latency; null;
              if $has then "end-to-end p95 \($m.latency.p95Ms) ms (recorded, never a verdict)"
              else "no end-to-end latency was recorded" end))
  ] as $checks
| result($id;
         $m + {statements: $s,
               baselineCandidate: {workflowShape: $m.workflowShape,
                                   statementsPerWorkflowNet: $s.statementsPerWorkflowNet,
                                   netTolerancePerWorkflow: $s.netTolerancePerWorkflow}};
         $checks)
