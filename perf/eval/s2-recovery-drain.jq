# Scenario 2 (s2-recovery-drain): STUB, replaced by the evaluator once the
# first real run exists (plan Task 3 Steps 5-6).
include "common";

$identity[0] as $id
| $measurements[0] as $m
| result($id; $m; [check("correctness.s2"; "correctness"; "ERROR"; null; null; "evaluator not written")])
