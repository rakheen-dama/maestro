# Scenario 6 (s6-retention): STUB, replaced by the evaluator once the first
# real run exists (plan Task 6 Steps 4-5).
include "common";

$identity[0] as $id
| $measurements[0] as $m
| result($id; $m; [check("correctness.s6"; "correctness"; "ERROR"; null; null; "evaluator not written")])
