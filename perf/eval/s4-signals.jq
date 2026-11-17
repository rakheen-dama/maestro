# Scenario 4 (s4-signals, WP12; finding 3.2, fixed by WP2): signal delivery
# across nodes, and whether a delivery that wakes nobody here leaves a wake
# permit behind. Inputs: $identity, $measurements (slurped). Workload and
# phases: perf/scenarios/s4-signals.sh. Permits are read exactly from every
# node's own scrape (maestro_wake_permits) at P0 .. P3.
#
#   validity.background      the idle window (common.jq background_check)
#   validity.replicas        >= 2 distinct nodes answered
#   validity.permitGauge     maestro_wake_permits present on every node at
#                            P0..P3: absent is INVALID, never 0
#   correctness.allSignalsConsumed  per signal phase: exactly one signal row
#                            per accepted signal, all consumed, every
#                            (run, name) key exactly one row, every run
#                            COMPLETED with output M; every sleep run
#                            COMPLETED "slept"
#   validity.phases          both signal phases were set up as planned before
#                            signalling: every run parked (no timeout on the
#                            wait, W runs WAITING_SIGNAL), the local phase
#                            routed "own", the cross phase routed "next"
#                            with crossNode true (review r5)
#   gate.noStrayPermits (3.2)  every node's P1-P0, P2-P1 and P3-P2: each 0.
#                            Per node, never a sum over nodes, which a +k on
#                            one node and -k on another would cancel
#                            (review r6)
#   gate.crossNodeStatementParity  net engine statements per consumed signal:
#                            |cross - local| <= tolerance + 0.05 x local (the
#                            larger phase tolerance: one tick per background
#                            poller per consumed signal). A guard that the
#                            cross-node wake adds no per-signal store work
#   advisory.permitArithmetic  the deltas next to WP2's prediction (cross =
#                            accepted cross signals, timer = W_s x (N-1)/N)
#   advisory.consumeLatency  p95 of SIGNAL_RECEIVED created_at - signal
#                            received_at, cross / local <= 1.5
#   advisory.throughput      consumed signals per second of the signal phase
#                            window, next to the offered rate
include "common";

def s4_parity_fraction: 0.05;
def s4_latency_ratio: 1.5;
def phase($m; $n): ($m.phases // [] | if type == "array" then map(select(type == "object" and .name == $n)) | first else null end);
def permit_sum($p): ($p.nodes // [] | map(.value)) as $v | if ($v | length) > 0 and ($v | all(isnum)) then $v | add else null end;

$identity[0] as $id
| $measurements[0] as $m
| $id.config.replicas as $r
| phase($m; "local") as $loc
| phase($m; "cross") as $cr
| ($m.plan.m // null) as $mm
| ([permit_sum($m.permits.P0), permit_sum($m.permits.P1), permit_sum($m.permits.P2), permit_sum($m.permits.P3)]) as $P
| (try statement_figures($m.background; $loc.window; ($loc.status.signals.consumed // 0); $r) catch null) as $sl
| (try statement_figures($m.background; $cr.window; ($cr.status.signals.consumed // 0); $r) catch null) as $sx
| [
    background_check($m.background; $r),
    (($id.nodes // [] | map(.hostname) | unique | length) as $d
     | check("validity.replicas"; "validity"; if ($r | isnum) and $r >= 2 and $d >= 2 then "PASS" else "INVALID" end;
         {replicas: $r, distinctNodes: $d}; {distinctNodes: ">= 2"};
         if $d >= 2 then "\($d) distinct nodes answered" else "fewer than 2 nodes: no cross-node phase is possible" end)),
    ([["P0", $m.permits.P0], ["P1", $m.permits.P1], ["P2", $m.permits.P2], ["P3", $m.permits.P3]]
     | map({read: .[0], values: (.[1].nodes // [] | map(.value))})) as $v
    | (($v | all((.values | length) >= 2 and (.values | length) == $r and (.values | all(isnum))))) as $ok
    | check("validity.permitGauge"; "validity"; if $ok then "PASS" else "INVALID" end; $v;
        {every: "node at P0..P3 reports maestro_wake_permits"};
        if $ok then "every node reported its wake-permit count at P0..P3"
        else "a node did not report maestro_wake_permits at some read: the permit count is not measured (absent is never 0)" end),
    ([$loc, $cr] | map(select(. != null) | {phase: .name, route, planRoute: .plan.route, crossNode: .plan.crossNode,
        parkedTimedOut: .parked.timedOut, parked: (.parked.byStatus.WAITING_SIGNAL // 0), w})) as $v
    | (($v | length) == 2
       and ($v | all(.parkedTimedOut == false and .parked == .w))
       and ($v[0].phase == "local" and $v[0].route == "own" and $v[0].planRoute == "own")
       and ($v[1].phase == "cross" and $v[1].route == "next" and $v[1].planRoute == "next" and $v[1].crossNode == true)) as $ok
    | check("validity.phases"; "validity"; if $ok then "PASS" else "INVALID" end; $v;
        {parked: "every run WAITING_SIGNAL before signalling", local: "route own", cross: "route next, crossNode true"};
        if $ok then "both phases parked every run before signalling; local routed to the owner, cross to another node"
        else "a signal phase was not set up as planned (runs not all parked, or the routing not as named): its figures do not mean what the phase name says" end),
    ([$loc, $cr] | map(select(. != null) | {phase: .name, w, accepted, rows: .status.signals.rows,
        consumed: .status.signals.consumed, unconsumed: .status.signals.unconsumed,
        keysNotExactlyOneRow: .status.signals.keysNotExactlyOneRow,
        completed: (.status.byStatus.COMPLETED // 0), outputM: (.status.outputs[($mm | tostring)] // 0)})) as $v
    | ($m.timer.status // {}) as $t
    | ($m.plan.sleepRuns // null) as $ws
    | (($v | length) == 2
       and ($v | all(.rows == .accepted and .consumed == .rows and .unconsumed == 0 and .keysNotExactlyOneRow == 0
                     and .completed == .w and .outputM == .w))
       and ($t.byStatus.COMPLETED // 0) == $ws and ($t.results.slept // 0) == $ws) as $ok
    | check("correctness.allSignalsConsumed"; "correctness"; if $ok then "PASS" else "FAIL" end;
        {phases: $v, sleepRuns: {planned: $ws, byStatus: $t.byStatus, results: $t.results}};
        {rows: "= accepted", unconsumed: 0, keysNotExactlyOneRow: 0, completed: "every run, output M"};
        if $ok then "every accepted signal has exactly one row, consumed; every run COMPLETED with output \($mm); every sleep run slept"
        else "a signal was lost, duplicated or left unconsumed, or a run did not complete as planned" end),
    ([["P0", $m.permits.P0], ["P1", $m.permits.P1], ["P2", $m.permits.P2], ["P3", $m.permits.P3]]
     | map(.[1].nodes // [] | map({key: .container, value: .value}) | from_entries)) as $pn
    | ([$pn[0] | keys[]] | map(. as $c
        | {container: $c,
           p1p0: (if ($pn[1][$c] | isnum) and ($pn[0][$c] | isnum) then $pn[1][$c] - $pn[0][$c] else null end),
           p2p1: (if ($pn[2][$c] | isnum) and ($pn[1][$c] | isnum) then $pn[2][$c] - $pn[1][$c] else null end),
           p3p2: (if ($pn[3][$c] | isnum) and ($pn[2][$c] | isnum) then $pn[3][$c] - $pn[2][$c] else null end)})) as $per
    | ({p1p0: ($per | map(.p1p0) | if all(isnum) then add else null end),
        p2p1: ($per | map(.p2p1) | if all(isnum) then add else null end),
        p3p2: ($per | map(.p3p2) | if all(isnum) then add else null end)}) as $d
    | ([$per[] | .p1p0, .p2p1, .p3p2]) as $all
    | check("gate.noStrayPermits"; "gate";
        if ($per | length) < 2 or ($all | any(isnum | not)) then "INVALID"
        elif ($all | all(. == 0)) then "PASS" else "FAIL" end;
        $d + {perNode: $per, sums: $P}; {perNode: "every delta 0"};
        if ($per | length) < 2 or ($all | any(isnum | not)) then "a permit read is missing"
        elif ($all | all(. == 0)) then "no delivery left a wake permit behind on any node"
        else "wake permits moved: local \($d.p1p0), cross \($d.p2p1), timer \($d.p3p2) in total; per node \($per | map("\(.container): \(.p1p0) / \(.p2p1) / \(.p3p2)") | join(", ")) (3.2: a delivery that wakes nobody on the node stores a permit for good)" end),
    ($sl.statementsPerWorkflowNet // null) as $nl
    | ($sx.statementsPerWorkflowNet // null) as $nx
    | ([$sl.netTolerancePerWorkflow, $sx.netTolerancePerWorkflow] | map(select(isnum)) | max) as $tol
    | (if ($nl | isnum) and ($nx | isnum) and ($tol | isnum) then $tol + s4_parity_fraction * $nl else null end) as $band
    | check("gate.crossNodeStatementParity"; "gate";
        if $band == null or ($nl | isnum | not) or $nl <= 0 then "INVALID"
        elif ($nx - $nl | fabs) <= $band then "PASS" else "FAIL" end;
        {localNetPerSignal: $nl, crossNetPerSignal: $nx, delta: (if ($nl | isnum) and ($nx | isnum) then ($nx - $nl | round4) else null end),
         tolerancePerSignal: $tol};
        {band: ($band | round4), rule: "|cross - local| <= tolerance + \(s4_parity_fraction) x local"};
        if $band == null then "a phase's net statements per signal could not be computed"
        else "net engine statements per consumed signal: local \($nl), cross \($nx)" end),
    advisory_guarded("advisory.permitArithmetic";
      (($id.nodes | length) as $n
       | {local: {observed: ($P[1] - $P[0]), predictedBefore: 0},
          cross: {observed: ($P[2] - $P[1]), predictedBefore: $cr.accepted},
          timer: {observed: ($P[3] - $P[2]), predictedBefore: (($m.plan.sleepRuns // 0) * ($n - 1) / $n)},
          predictedAfter: 0}) as $v
      | check("advisory.permitArithmetic"; "advisory"; "PASS"; $v; null;
          "permit deltas next to WP2's arithmetic: before the fix a cross delivery and a non-owner timer fire each leave one permit; after it, none")),
    advisory_guarded("advisory.consumeLatency";
      ($loc.status.latencyMs.p95) as $a | ($cr.status.latencyMs.p95) as $b
      | (if ($a | isnum) and $a > 0 and ($b | isnum) then $b / $a else null end) as $q
      | check("advisory.consumeLatency"; "advisory"; if $q != null and $q <= s4_latency_ratio then "PASS" else "WARN" end;
          {localP95Ms: $a, crossP95Ms: $b, localP50Ms: $loc.status.latencyMs.p50, crossP50Ms: $cr.status.latencyMs.p50,
           ratio: ($q | round4)};
          {ratio: "<= \(s4_latency_ratio)"};
          if $q == null then "a phase's consume latency was not recorded"
          else "signal received -> consumed p95: local \($a) ms, cross \($b) ms" end)),
    advisory_guarded("advisory.throughput";
      [$loc, $cr] | map({phase: .name, offered: .offered, levels: $m.plan.levels,
                        consumedPerSec: (.status.signals.consumed / .window.windowSecs | round2)}) as $v
      | check("advisory.throughput"; "advisory"; "PASS"; $v; null;
          "consumed signals per second of each signal window (recorded, never a verdict)"))
  ] as $checks
| result($id; $m + {statementsPerSignal: {local: $sl, cross: $sx}}; $checks)
