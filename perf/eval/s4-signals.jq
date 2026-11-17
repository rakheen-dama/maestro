# Scenario 4 (s4-signals): STUB, replaced by the evaluator once the first
# real runs exist (plan Task 5 Steps 5-6).
include "common";

$identity[0] as $id
| $measurements[0] as $m
| result($id; $m; [check("correctness.s4"; "correctness"; "ERROR"; null; null; "evaluator not written")])
