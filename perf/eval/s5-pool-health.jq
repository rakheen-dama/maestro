# Scenario 5 (s5-pool-health): STUB, replaced by the evaluator once the
# first real runs exist (plan Task 4 Steps 4-5).
include "common";

$identity[0] as $id
| $measurements[0] as $m
| result($id; $m; [check("correctness.s5"; "correctness"; "ERROR"; null; null; "evaluator not written")])
