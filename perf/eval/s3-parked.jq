# Scenario 3 (s3-parked): the steady store load of a parked population.
# Inputs: $identity, $measurements, $baseline, $prometheus (slurped arrays).
# Measurements: perf/scenarios/s3-parked.sh.
#
# D4: numbers first. Pass/fail rests only on statement counts and within-run
# comparisons: the per-parked-run cost at each plateau (gate.parkedCostLinear,
# within the run; gate.parkedCostBaseline, against a recorded baseline), and
# on correctness (every plateau reached, every parked run released with
# result "released" and exactly one consumed signal row, nothing failed).
# Latency, memory, slots and time are advisories.
#
# ── The per-parked figure ──────────────────────────────────────────────────
# For window w (windows[0] = the idle window, P = 0; windows[1..] = one per
# plateau, the hold after settle): cps(w) = (maestroCalls - lockCleanerCalls)
# / windowSecs, every engine call counted (track=all; nothing nested occurs
# in these windows, see components.nestedCalls). cps(0) is the idle rate.
#   perParkedCallsPer30s(P) = (cps(P) - cps(0)) x 30 / P      (the brief's figure)
#   perParkedCallsPerMin(P) = (cps(P) - cps(0)) x 60 / P      (the gated figure)
#   perParkedCallsPerRecheck(P) = (cps(P) - cps(0)) x wakeRecheckSecs / P
# The subtraction leaves every statement the parked population causes: its
# wake re-checks, the lock renewal of its instance locks, the recovery pass's
# extra pages over its rows (the idle pass reads one empty page), and any
# heldKeys read. tolerancePerMin = netToleranceCalls (distinct idle
# statements x replicas: one tick per background poller) / P x 60 /
# windowSecs. Every window is a whole number of the node's wake-recheck,
# recovery-poll and timer-poll intervals (validity.windows), so no partial
# recovery pass or recheck cycle biases the per-minute figure.
#
# ── Gates ──────────────────────────────────────────────────────────────────
# gate.parkedCostLinear (within the run): ratio = perParkedCallsPerMin(Pmax)
#   / perParkedCallsPerMin(Pmin); PASS when |ratio - 1| <= linear_band (0.25,
#   the brief's 1.25 x, made symmetric like the statement gate: a per-parked
#   cost that FALLS at Pmax means re-checks no longer keep their interval, as
#   suspicious as one that rises). INVALID when fewer than two plateaus were
#   measured (could not be judged).
# gate.parkedCostBaseline: statements_gate (common.jq; symmetric band =
#   tolerance + relativeAllowance x expected) on perParkedCallsPerMin(Pmax)
#   against perf/baselines/s3-parked.json's perParkedCallsPerMin, used only
#   when its parkMode, wakeRecheckInterval, recoveryPollInterval, profile and
#   replicas match this run (otherwise NO_BASELINE, with the reason).
#   relativeAllowance = s3_relative_allowance below; its justification is
#   recorded here from the cold local runs that pass this evaluator.
#
# relativeAllowance = 0.02, from measurements: three cold local runs, one
# node, signal mode, clean tree, that PASS this evaluator (r20261113t025114z
# after the crossNode fix, its raw record unchanged; r20261113t030358z;
# r20261113t031512z); perParkedCallsPerMin, unrounded:
#   P = 1000: 6.01703 / 6.01651 / 6.01715
#   P = 2000: 6.01608 / 6.01411 / 6.01528   (the gated figure)
# a spread of 0.0020 at P = 2000 (0.03 %). All three were on a contended
# host (advisory.contention: load average up to 9.24, 3.0 and 10.75 per CPU)
# and the figure did not move with it: the statement count of a parked
# population is set by its intervals, not by host speed. 0.02 is a band of
# +/- 0.12 statements per parked run per minute (+/- 2 %): far above the
# observed spread, and far below the smallest real regression, one more read
# per re-check (+2 per minute, +33 %), or renewal per lock instead of per 500
# (+6 per minute). What could move it without a code change: a re-check
# period stretched by slow reads or a slot wait (2 % = 0.6 s per 30 s cycle;
# not observed: re-check reads per parked run per interval 2.9986-2.9996).
# The 25 % tolerance cap does not bind: tolerancePerMin at P = 2000 is
# 3 x 1 / 2000 x 60 / 120 = 0.00075.
#
# ── Decomposition (advisory.decomposition) ─────────────────────────────────
# Per plateau window the engine's top-level calls are attributed by statement
# text (perf/sql/s3-components.sql) and compared with docs/operations.md §13's
# arithmetic at the node's bound intervals (H = instance locks held per node
# = P / replicas, A = active rows of the service = P):
#   wake re-check   3 reads per parked awaitSignal (instance status, signals,
#                   timer row), 2 per parked sleep (status, timer row), every
#                   wakeRecheckSecs
#   lock renewal    ceil(H / 500) UPDATE ... unnest per node every lockTtl / 3
#                   (the renewer sleeps, then renews: the effective period is
#                   lockTtl / 3 + the pass's own duration, ceil(H / 500) x the
#                   statement's mean time from pg_stat_statements; passes are
#                   counted at that period. Full run r20261113t034332z: 34-37
#                   ms per 500-lock UPDATE, 10.37 s at H = 5000, 10.69 s at
#                   10000, so 29 and 28 passes per 300 s, not 30)
#   recovery pages  floor(A / 200) + 1 page reads per node every recoveryPollSecs
#   heldKeys        at most 1 per non-empty page (<= ceil(A / 200)) per pass
#   lock acquires   0 (no orphans while every node is healthy)
#   timer poll      1 due-timer query per tick (leader) and 1 leader-election
#                   upsert per node per tick (perf/run.sh header)
# Measured per unit = calls / (windowSecs / interval) (per parked for the
# re-check); a component "matches" when its calls are within one pass of the
# documented count (re-checks: within 2 %). Any other statement is "other".
# A mismatch is a FINDING about the documentation or the engine, reported by
# this advisory as WARN; it never changes the verdict. The decomposition
# rests on statement text (an engine with different SQL reports its calls as
# "other"), so it is advisory only.
#
# ── Correctness ────────────────────────────────────────────────────────────
# plateauReached: every planned plateau reached exactly P parked rows within
#   600 s. releaseWakes (signal mode): every parked run COMPLETED with output
#   "released", each with exactly one perf.release signal row, consumed, none
#   unconsumed, every signal accepted (202), within the release timeout
#   (120 s + Pmax / 10 s); crossNode is recorded (true when replicas > 1:
#   signal n went to nodeFor(n + 1), every wake crossed nodes). In sleep mode
#   the runs cannot be released by a signal: NO_BASELINE, "not applicable".
#   noFailures: FAILED and TERMINATED == 0 over the run's whole id space.
#   probesCompleted: every accepted probe start COMPLETED within 120 s.
#
# ── Validity (besides the common checks) ───────────────────────────────────
# validity.parked: before each plateau window opens and after it closes,
#   exactly P rows of the park prefix exist, all in the parked status, and no
#   other row of the run is open: a plateau measured while runs are still
#   starting, or with a probe or warm-up row still running, is INVALID.
# validity.windows: the window unit is a whole multiple of every node's
#   wake-recheck, recovery-poll and timer-poll interval; the idle window lasts
#   idleSecs, every plateau window holdSecs and the release window a whole
#   number of units, each measured within +5 s (the snapshot's own latency).
# validity.plan: each k6 phase ran as asked: the probes' executed plan equals
#   the requested one and started rate x duration iterations (within max(2,
#   0.5 %)); every seed and the release ran exactly their count.
include "common";

def s3_relative_allowance: 0.02;
def linear_band: 0.25;
def probe_latency_ratio_max: 2;
def renew_chunk: 500;
def recovery_page_size: 200;
def window_slack_secs: 5;
def recheck_match_fraction: 0.02;

# The well-formed objects of a list field; anything else reads as empty.
def objs: if type == "array" then map(select(type == "object")) else [] end;
def comp($w; $name): ($w.components[$name].calls // 0 | if isnum then . else 0 end);
def ceil_div($a; $b): (($a / $b) | ceil);

def iterations_ok($got; $want): ($got | isnum) and ($want | isnum) and ((($got - $want) | fabs) <= ([2, 0.005 * $want] | max));

$identity[0] as $id
| $measurements[0] as $m
| ($baseline[0] // null) as $b
| $id.config.replicas as $replicas
| ($m.plan // {}) as $plan
| ($m.parkMode // $plan.parkMode) as $mode
| ($m.parkedStatus // $plan.parkedStatus) as $parkedStatus
| ($id.nodes[0] // {}) as $node0
| ($node0.wakeRecheckInterval | duration_secs) as $recheckSecs
| ($node0.recoveryPollInterval | duration_secs) as $recoverySecs
| ($node0.timerPollInterval | duration_secs) as $timerSecs
| ($node0.lockTtl | duration_secs) as $lockTtlSecs
| (if ($lockTtlSecs | isnum) then $lockTtlSecs / 3 else null end) as $renewSecs
| (background_problem($m.background; $replicas) == null) as $bgOk
| (if $bgOk then ($m.background.window.maestroCalls - lock_cleaner_calls($m.background.window)) / $m.background.window.windowSecs else null end) as $cps0
| (if $bgOk then ($m.background.window.maestroStatements - lock_cleaner_statements($m.background.window)) * $replicas else null end) as $tolCalls
| ($m.windows | objs) as $rawWindows
| ($m.k6 | objs) as $k6
| (if $mode == "sleep" then 2 else 3 end) as $readsPerRecheck
# ── per-window figures ──
| ([$rawWindows[] | . as $w
    | (($w.maestroCalls - lock_cleaner_calls($w)) / $w.windowSecs) as $cps
    | ($w.P // null) as $P
    | (($P | isnum) and $P > 0 and ($cps0 | isnum)) as $hasNet
    | {P: $P, kind: $w.kind, windowSecs: $w.windowSecs, maestroCalls: $w.maestroCalls,
       maestroCallsTopLevel: $w.maestroCallsTopLevel, harnessCalls: $w.harnessCalls,
       lockCleanerCalls: lock_cleaner_calls($w), cps: ($cps | round4),
       cpsExact: $cps,
       perParkedCallsPer30s: (if $hasNet then ($cps - $cps0) * 30 / $P | round4 else null end),
       perParkedCallsPerMin: (if $hasNet then ($cps - $cps0) * 60 / $P | round4 else null end),
       perParkedCallsPerMinExact: (if $hasNet then ($cps - $cps0) * 60 / $P else null end),
       perParkedCallsPerRecheck: (if $hasNet and ($recheckSecs | isnum) then ($cps - $cps0) * $recheckSecs / $P | round4 else null end),
       tolerancePerMin: (if $hasNet and ($tolCalls | isnum) then $tolCalls / $P * 60 / $w.windowSecs | round4 else null end),
       components: ($w.components // null), nestedCalls: ($w.nestedCalls // null), otherTop: ($w.otherTop // null),
       top: $w.top}]) as $windows
| ([$windows[] | select(.kind == "plateau" and (.P | isnum) and .P > 0)] | sort_by(.P)) as $plateauWins
| ($plateauWins | first // null) as $wMin
| ($plateauWins | last // null) as $wMax
# ── decomposition ──
| ([$plateauWins[] | . as $w
    | $w.P as $P
    | ($P / $replicas) as $H
    | $P as $A
    | (if ($recheckSecs | isnum) then $w.windowSecs / $recheckSecs else null end) as $nRecheck
    | (if ($renewSecs | isnum) then $w.windowSecs / $renewSecs else null end) as $nRenew
    | (if ($recoverySecs | isnum) then $w.windowSecs / $recoverySecs else null end) as $nRecovery
    | (if ($timerSecs | isnum) then $w.windowSecs / $timerSecs else null end) as $nTick
    | ($w.components // {}) as $c
    | {inst: comp($w; "instanceRead"), sig: comp($w; "signalsRead"), tmr: comp($w; "timerRowRead"),
       renew: comp($w; "lockRenewAll"), renewOne: comp($w; "lockRenewOne"), page: comp($w; "recoveryPage"),
       held: comp($w; "heldKeys"), acq: comp($w; "lockAcquire"), rel: comp($w; "lockRelease"),
       due: comp($w; "timerDuePoll"), elect: comp($w; "leaderElection"), clean: comp($w; "lockCleaner"),
       other: comp($w; "other")} as $k
    | ($k.inst + $k.sig + $k.tmr) as $recheckCalls
    | (if ($nRecheck | isnum) then $recheckCalls / $P / $nRecheck else null end) as $recheckPer
    | ($replicas * ceil_div($H; renew_chunk)) as $renewDocPerPass
    | ((($A / recovery_page_size) | floor) + 1) as $pagesDocPerPass
    | def row($name; $calls; $passes; $docPerPass; $unit; $slackPasses):
        (if ($passes | isnum) and $passes > 0 then $calls / $passes else null end) as $per
        | (if ($passes | isnum) then $docPerPass * $passes else null end) as $docCalls
        | {component: $name, calls: $calls, per: ($per | round4), perUnit: $unit,
           documentedPer: $docPerPass, documentedCalls: ($docCalls | round2),
           matches: (if ($docCalls | isnum) then (($calls - $docCalls) | fabs) <= $docPerPass * $slackPasses + 0.5 else null end)};
      {P: $P, windowSecs: $w.windowSecs,
       rows: [
         ({component: "wakeRecheck", calls: $recheckCalls,
           split: {instanceStatus: $k.inst, signals: $k.sig, timerRow: $k.tmr},
           per: ($recheckPer | round4), perUnit: "reads per parked run per \($recheckSecs) s re-check",
           splitPer: (if ($nRecheck | isnum) then {instanceStatus: ($k.inst / $P / $nRecheck | round4),
                                                   signals: ($k.sig / $P / $nRecheck | round4),
                                                   timerRow: ($k.tmr / $P / $nRecheck | round4)} else null end),
           documentedPer: $readsPerRecheck,
           documentedCalls: (if ($nRecheck | isnum) then $readsPerRecheck * $P * $nRecheck | round2 else null end),
           matches: (if ($recheckPer | isnum) then (($recheckPer - $readsPerRecheck) | fabs) <= recheck_match_fraction * $readsPerRecheck else null end)}),
         # The renewer sleeps lockTtl / 3 and THEN renews (traced:
         # WorkflowInstanceLockManager.renewLoop), so a pass's own duration
         # lengthens the period: effective period = renewSecs + ceil(H / 500)
         # x the statement's mean execution time (pg_stat_statements
         # totalMs / calls). The match is judged at that effective period;
         # the nominal per-pass figure is kept beside it.
         ((($c.lockRenewAll.totalMs // null) as $ms
           | if ($ms | isnum) and $k.renew > 0 then $ms / $k.renew else null end) as $stmtMs
          | (if ($stmtMs | isnum) and ($renewSecs | isnum) then $renewSecs + ceil_div($H; renew_chunk) * $stmtMs / 1000 else null end) as $effPeriod
          | (if ($effPeriod | isnum) then $w.windowSecs / $effPeriod else $nRenew end) as $effPasses
          | row("lockRenewal"; $k.renew + $k.renewOne; $effPasses; $renewDocPerPass;
                "statements per renewal pass (all \($replicas) node(s); every \($renewSecs) s + the pass's own duration)"; 1)
          + {perNominalPass: (if ($nRenew | isnum) and $nRenew > 0 then ($k.renew + $k.renewOne) / $nRenew | round4 else null end),
             statementMeanMs: ($stmtMs | if isnum then round4 else . end),
             effectivePeriodSecs: ($effPeriod | if isnum then round4 else . end),
             perLockPerMin: (if $w.windowSecs > 0 then ($k.renew + $k.renewOne) / $P / ($w.windowSecs / 60) | round4 else null end),
             singleRenewCalls: $k.renewOne,
             note: "documented ceil(H / \(renew_chunk)) per node per pass, H = \($H) locks per node: NOT linear per run (one statement covers up to \(renew_chunk) locks); passes counted at the effective period (renewSecs + the pass's measured duration)"}),
         (row("recoveryPages"; $k.page; (if ($nRecovery | isnum) then $nRecovery * $replicas else null end); $pagesDocPerPass;
              "page reads per recovery pass per node (every \($recoverySecs) s)"; 1)
          + {note: "documented floor(A / \(recovery_page_size)) + 1 per pass, A = \($A) active rows of the service; every node scans all of them"}),
         (row("heldKeys"; $k.held; (if ($nRecovery | isnum) then $nRecovery * $replicas else null end);
              (if $replicas > 1 then ceil_div($A; recovery_page_size) else 0 end);
              "heldKeys reads per recovery pass per node"; 1)
          + {documentedMax: ceil_div($A; recovery_page_size),
             note: "docs: at most 1 per non-empty page (<= ceil(A / \(recovery_page_size)) = \(ceil_div($A; recovery_page_size))); traced: a page whose rows all run on this node (in runningWorkflows, holding their lock) issues none, so 0 is expected at 1 replica and about one per non-empty page at 2+"}),
         (row("lockAcquire"; $k.acq; (if ($nRecovery | isnum) then $nRecovery * $replicas else null end); 0;
              "lock acquisitions per recovery pass per node"; 0)),
         (row("timerDuePoll"; $k.due; $nTick; 1; "due-timer queries per \($timerSecs) s tick (leader only)"; 1)),
         (row("leaderElection"; $k.elect; $nTick; $replicas; "leader-election upserts per tick (every node)"; 1)),
         {component: "lockCleaner", calls: $k.clean, note: "2 per pass per node (subtracted exactly; validity.sweeps)"},
         {component: "lockRelease", calls: $k.rel},
         {component: "other", calls: $k.other, top: ($w.otherTop // []),
          matches: ($k.other == 0)},
         {component: "nested", calls: ($w.nestedCalls // null)}
       ]}]) as $decomposition
# ── seed and release windows ──
| ($m.auxWindows | objs) as $aux
| ([$aux[] | select(.kind == "seed") | . as $s
    | ([$plateauWins[] | select(.P == $s.Pprev)] | first // null) as $prevWin
    | (if $prevWin == null or ($cps0 | isnum | not) then 0 else (($prevWin.cpsExact - $cps0) / $prevWin.P) end) as $kPrev
    | (($s.maestroCalls - lock_cleaner_calls($s)) / $s.windowSecs) as $cpsS
    | ($s.P - $s.Pprev) as $n
    | ([($m.plateaus | objs)[] | select(.P == $s.P)] | first // {}) as $pl
    | {P: $s.P, Pprev: $s.Pprev, started: $n, windowSecs: $s.windowSecs, maestroCalls: $s.maestroCalls,
       timeToParkSecs: ($pl.seed.timeToParkSecs // $pl.seed.waitedSecs // null), k6Secs: ($pl.seed.k6Secs // null),
       lagSecs: ($pl.seed.lagSecs // null),
       netPerStartedRun: (if ($cps0 | isnum) and $n > 0 then ($s.maestroCalls - lock_cleaner_calls($s) - ($cps0 + $kPrev * $s.Pprev) * $s.windowSecs) / $n | round2 else null end),
       grossPerStartedRun: (if $n > 0 then ($s.maestroCalls - lock_cleaner_calls($s)) / $n | round2 else null end)}]) as $seeds
| ($m.release // null) as $rel
| ([$aux[] | select(.kind == "release")] | first // null) as $relWin
| (if $rel != null and $relWin != null and ($cps0 | isnum) and ($rel.count | isnum) and $rel.count > 0 then
     (($relWin.maestroCalls - lock_cleaner_calls($relWin))) as $calls
     | (if $wMax != null then ($wMax.cpsExact - $cps0) / $wMax.P else null end) as $kMax
     | {count: $rel.count, windowSecs: $relWin.windowSecs, maestroCalls: $relWin.maestroCalls,
        grossPerReleased: ($calls / $rel.count | round2),
        netPerReleased: (($calls - $cps0 * $relWin.windowSecs) / $rel.count | round2),
        recheckResidualBoundPerReleased: (if ($kMax | isnum) and ($rel.k6Secs | isnum) then $kMax * $rel.k6Secs | round2 else null end),
        k6SignalSecs: $rel.k6Secs, waitedAfterSignalsSecs: $rel.drain.waitedSecs,
        drainSecs: (($rel.status.lastCompletedAt | ts_secs) as $last | ($relWin.resetAt | ts_secs) as $r0
                    | if ($last | isnum) and ($r0 | isnum) then $last - $r0 | round2 else null end),
        crossNode: (if ($rel.executedPlan | type) == "object" then $rel.executedPlan.crossNode else null end)}
   else null end) as $releaseFig
# ── Prometheus per window ──
| ($m.windowProm | objs) as $wp
| ([$wp[] | select(.kind == "idle")] | first // null) as $promIdle
| ([$wp[] | select(.kind == "plateau")] | sort_by(.P)) as $promPlateaus
| ($promPlateaus | last // null) as $promMax
# ── contention ──
| ($m.hostLoad // null) as $hl
| load_per_cpu([($hl.scenarioStart // null)] + ($hl.windows // []) + [($hl.end // null)]) as $loadPerCpu
| ([$promPlateaus[] | select((.nodeCpu.processAvg | isnum) and .nodeCpu.processAvg >= cpu_saturation
                             or (.nodeCpu.systemAvg | isnum) and .nodeCpu.systemAvg >= cpu_saturation) | .P]) as $cpuHot
| (($loadPerCpu | length) > 0 and ($loadPerCpu | max) > host_load_per_cpu_max) as $loadHot
# ── baseline ──
| (if $b == null then "perf/baselines/s3-parked.json is absent"
   elif $b.parkMode != $mode then "baseline parkMode \($b.parkMode | tojson) is not this run's \($mode | tojson)"
   elif $b.wakeRecheckInterval != $node0.wakeRecheckInterval then "baseline wakeRecheckInterval \($b.wakeRecheckInterval | tojson) is not this run's \($node0.wakeRecheckInterval | tojson)"
   elif $b.recoveryPollInterval != $node0.recoveryPollInterval then "baseline recoveryPollInterval \($b.recoveryPollInterval | tojson) is not this run's \($node0.recoveryPollInterval | tojson)"
   elif $b.profile != $id.profile then "baseline profile \($b.profile | tojson) is not this run's \($id.profile | tojson)"
   elif $b.replicas != $replicas then "baseline replicas \($b.replicas | tojson) is not this run's \($replicas)"
   else null end) as $noBase
| (if $noBase == null then $b.perParkedCallsPerMin else null end) as $expected
| ($m.plateaus | objs) as $plRecs
| ($plan.plateaus // []) as $planned
| [
    background_check($m.background; $replicas),
    # ── validity.roleSplit ──
    ( ($windows | map(select(.kind != "idle"))) as $ws
      | (($ws | length) > 0 and ($ws | all((.maestroCalls | isnum) and .maestroCalls > 0))
         and ($windows | all((.harnessCalls | isnum) and .harnessCalls > 0))) as $ok
      | check("validity.roleSplit"; "validity"; if $ok then "PASS" else "INVALID" end;
          [$windows[] | {P, maestroCalls, harnessCalls}];
          {maestroCalls: "> 0 in every plateau window", harnessCalls: "> 0 in every window"};
          if $ok then "engine (role maestro) and harness (role postgres) statements are both seen and counted apart in every window"
          else "a window lacks engine calls or harness calls: the role split is not demonstrated" end) ),
    # ── validity.parked ──
    ( ( [$rawWindows[] | select(.kind == "plateau") | . as $w
       | [{edge: "before", s: $w.parkedBefore}, {edge: "after", s: $w.parkedAfter}][]
       | . as $e
       | ($e.s.park.total // null) as $t
       | ($e.s.park.byStatus[$parkedStatus] // 0) as $n
       | ($e.s.others.open // null) as $o
       | {P: $w.P, edge: $e.edge, parkTotal: $t, parked: $n, othersOpen: $o, byStatus: ($e.s.park.byStatus // null),
          ok: ($t == $w.P and $n == $w.P and $o == 0)}] ) as $pk
    | (($pk | length) > 0 and ($pk | all(.ok))) as $ok
    | check("validity.parked"; "validity"; if $ok then "PASS" else "INVALID" end;
        $pk; {parkTotal: "== P", parked: "== P (\($parkedStatus))", othersOpen: 0};
        if ($pk | length) == 0 then "no plateau window was measured"
        elif $ok then "every plateau window opened and closed with exactly P runs \($parkedStatus) and no other row of the run open: \([$pk[] | select(.edge == "before") | "P=\(.P)"] | join(", "))"
        else ([$pk[] | select(.ok | not) | "P=\(.P) \(.edge): \(.parked) of \(.parkTotal) rows \($parkedStatus) (\(.byStatus | tojson)), \(.othersOpen) other row(s) open"] | join("; "))
          + ": the plateau was measured while runs were still starting or other work ran" end) ),
    # ── validity.windows ──
    ( ($id.nodes // []) as $nodes
      | ($plan.windowUnitSecs // null) as $unit
      | ([$nodes[] | {r: (.wakeRecheckInterval | duration_secs), c: (.recoveryPollInterval | duration_secs), t: (.timerPollInterval | duration_secs)}]) as $iv
      | (($unit | isnum) and $unit > 0 and ($iv | length) > 0
         and ($iv | all((.r | isnum) and (.c | isnum) and (.t | isnum) and .r > 0 and .c > 0 and .t > 0
                        and ($unit / .r) == (($unit / .r) | floor) and ($unit / .c) == (($unit / .c) | floor)
                        and ($unit / .t) == (($unit / .t) | floor)))) as $unitOk
      | def fits($secs; $planned): ($secs | isnum) and ($planned | isnum) and $secs >= $planned and $secs <= $planned + window_slack_secs;
      ([$rawWindows[] | {P, kind, windowSecs,
                         planned: (if .kind == "idle" then $plan.idleSecs elif .kind == "plateau" then $plan.holdSecs else null end)}
        | . + {ok: fits(.windowSecs; .planned)}]) as $wl
      | ([$aux[] | select(.kind == "release") | {kind, windowSecs,
            planned: (if ($unit | isnum) and $unit > 0 then ((.windowSecs / $unit) | floor) * $unit else null end)}
          | . + {ok: ((.planned | isnum) and .planned >= $unit and fits(.windowSecs; .planned))}]) as $rl
      | (($plan.idleSecs | isnum) and ($plan.holdSecs | isnum) and ($unit | isnum) and $unit > 0
         and ($plan.idleSecs / $unit) == (($plan.idleSecs / $unit) | floor)
         and ($plan.holdSecs / $unit) == (($plan.holdSecs / $unit) | floor)) as $planOk
      | (($rawWindows | length) > 0 and ($rawWindows[0].kind == "idle") and ($rawWindows[0].P == 0)) as $firstIdle
      | ($unitOk and $planOk and $firstIdle and ($wl | all(.ok)) and ($rl | all(.ok))) as $ok
      | check("validity.windows"; "validity"; if $ok then "PASS" else "INVALID" end;
          {windowUnitSecs: $unit, nodeIntervals: $iv, idleSecs: $plan.idleSecs, holdSecs: $plan.holdSecs,
           holdRequestedSecs: $plan.holdRequestedSecs, windows: $wl, release: $rl};
          {unit: "a whole multiple of every node's wake-recheck, recovery-poll and timer-poll interval",
           windowSecs: "planned length (idle, hold, or a whole number of units for the release) to + \(window_slack_secs) s"};
          if $ok then "window unit \($unit) s (recheck/recovery/timer intervals \($iv[0].r)/\($iv[0].c)/\($iv[0].t) s); idle \($plan.idleSecs) s, hold \($plan.holdSecs) s (requested \($plan.holdRequestedSecs) s)\(if ($rl | length) > 0 then ", release \($rl[0].planned) s" else "" end); every window measured within + \(window_slack_secs) s of its planned length"
          else [
            (if $unitOk | not then "the window unit \($unit | tojson) is not a whole multiple of every node's intervals \($iv | tojson)" else null end),
            (if $planOk | not then "idle \($plan.idleSecs | tojson) s / hold \($plan.holdSecs | tojson) s are not whole units" else null end),
            (if $firstIdle | not then "windows[0] is not the idle window (P = 0)" else null end),
            ($wl[] | select(.ok | not) | "\(.kind) window P=\(.P) lasted \(.windowSecs) s, planned \(.planned)"),
            ($rl[] | select(.ok | not) | "release window lasted \(.windowSecs) s, not a whole number of \($unit) s units")
          ] | reasons end) ),
    # ── validity.plan ──
    ( ([($m.probes | objs)[] | . as $p
        | ([$k6[] | select(.phase == $p.phase)] | last // null) as $r
        | ($p.executedPlan // null) as $ex
        | {executor: "constant-arrival-rate", rate: $p.requested.rate, timeUnit: "1s", durationSecs: $p.requested.durationSecs,
           offset: 0, count: null, preAllocatedVUs: 10, maxVUs: 50, idPrefix: $p.prefix} as $want
        | (if ($r.iterations | isnum) and ($r.droppedIterations | isnum) then $r.iterations + $r.droppedIterations else null end) as $got
        | {phase: $p.phase, executed: $ex, requested: $want, iterationsPlusDropped: $got, expected: ($p.requested.rate * $p.requested.durationSecs),
           ok: ($ex == $want and iterations_ok($got; $p.requested.rate * $p.requested.durationSecs))}]) as $pp
      | ([$plRecs[] | select(.reached == true) | . as $pl
          | ([$k6[] | select(.phase == "seed-p\($pl.P)")] | last // null) as $r
          | {phase: "seed-p\($pl.P)", executed: $pl.seed.executedPlan, requested: $pl.seed.requested,
             startsAccepted: $r.checksPassed, startsFailed: $r.checksFailed, expected: ($pl.P - $pl.Pprev),
             ok: ($r != null and $pl.seed.executedPlan != null and $pl.seed.executedPlan == $pl.seed.requested
                  and $r.checksPassed == ($pl.P - $pl.Pprev) and ($r.checksFailed // 0) == 0)}]) as $sp
      | (if $rel == null then [] else
          ([$k6[] | select(.phase == "release")] | last // null) as $r
          | [{phase: "release", iterations: $r.iterations, expected: $rel.count, executed: $rel.executedPlan,
              ok: ($r != null and ($rel.executedPlan | type) == "object" and $r.iterations == $rel.count
                   and $rel.executedPlan.count == $rel.count
                   and $rel.executedPlan.crossNode == ($replicas > 1)
                   and $rel.executedPlan.nodes == $replicas)}] end) as $rp
      | ($plan.profile == $id.profile) as $profOk
      | ($profOk and ($pp | length) > 0 and (($pp + $sp + $rp) | all(.ok))) as $ok
      | check("validity.plan"; "validity"; if $ok then "PASS" else "INVALID" end;
          {profile: $plan.profile, runProfile: $id.profile, probes: $pp, seeds: $sp, release: $rp};
          {probes: "executed plan == requested; iterations + dropped within max(2, 0.5 %) of rate x duration",
           seeds: "executed plan == requested (P - P_prev starts paced over one wake-recheck interval); starts accepted == P - P_prev", release: "iterations == count; crossNode == (replicas > 1)"};
          if $ok then "k6 ran every phase as asked: \($pp | length) probe(s), \($sp | length) seed(s)\(if ($rp | length) > 0 then ", the release of \($rel.count) (crossNode \($releaseFig.crossNode))" else "" end)"
          else [
            (if $profOk | not then "the scenario recorded profile \($plan.profile | tojson) for a \($id.profile) run" else null end),
            (if ($pp | length) == 0 then "no probe was recorded" else null end),
            (($pp + $sp + $rp)[] | select(.ok | not) | "\(.phase) did not run as asked (\(. | del(.ok) | tojson))")
          ] | reasons end) ),
    # ── correctness ──
    ( (($planned | length) > 0 and ($plRecs | length) == ($planned | length) and ($plRecs | all(.reached == true))
       and ($plRecs | map(.P)) == $planned and $m.stopped == null) as $ok
      | check("correctness.plateauReached"; "correctness"; if $ok then "PASS" else "FAIL" end;
          [$plRecs[] | {P, reached, timeToParkSecs: (.seed.timeToParkSecs // .seed.waitedSecs // null)}];
          {plateaus: $planned, within: "\($plan.parkTimeoutSecs // 600) s each"};
          if $ok then "every plateau reached exactly P \($parkedStatus) rows: \([$plRecs[] | "P=\(.P) in \(.seed.timeToParkSecs) s"] | join(", "))"
          else "plateau(s) not reached\(if $m.stopped != null then " (\($m.stopped))" else "" end): \([$plRecs[] | select(.reached != true) | "P=\(.P): \(.statusAtTimeout.park // null | tojson)"] | join("; "))" end) ),
    ( if $mode == "sleep" then
        check("correctness.releaseWakes"; "correctness"; "NO_BASELINE"; null; null;
              "not applicable: sleep-mode runs are not released by a signal; they stay \($parkedStatus) until their 7200 s timer")
      else
        ($wMax.P // ($planned | last) // null) as $pmax
        | ([$k6[] | select(.phase == "release")] | last // null) as $rk
        | ($rel.status // {}) as $s
        | ($rel != null and $s.total == $pmax and $s.released == $pmax and ($s.byStatus.COMPLETED // 0) == $pmax
           and $rel.drain.timedOut == false and $s.signals.workflowsNotExactlyOneRow == 0
           and $s.signals.workflowsNotExactlyOneConsumed == 0 and $s.signals.unconsumed == 0
           and ($rk.checksPassed // null) == $pmax and ($rk.checksFailed // 0) == 0) as $ok
        | check("correctness.releaseWakes"; "correctness"; if $ok then "PASS" else "FAIL" end;
            {count: $rel.count, total: $s.total, released: $s.released, results: $s.results, byStatus: $s.byStatus,
             signals: $s.signals, signalsAccepted: $rk.checksPassed, drainTimedOut: $rel.drain.timedOut,
             crossNode: $releaseFig.crossNode, drainSecs: $releaseFig.drainSecs};
            {released: "== Pmax (\($pmax)) COMPLETED with result \"released\"", signalRowsPerRun: "exactly 1, consumed",
             within: "\($rel.timeoutSecs // "n/a") s"};
            if $rel == null then "no release was run\(if $m.stopped != null then " (\($m.stopped))" else "" end)"
            elif $ok then "\($s.released)/\($pmax) parked runs released (COMPLETED, result \"released\"), each with exactly one consumed perf.release row; all terminal \($releaseFig.drainSecs) s after the release window opened\(if $releaseFig.crossNode == true then "; every signal was sent to the node that does not own the run (cross-node wake)" else "" end)"
            else [
              (if $s.total != $pmax then "\($s.total) park rows for \($pmax) parked" else null end),
              (if $s.released != $pmax then "\($s.released) of \($pmax) COMPLETED with result \"released\" (results \($s.results | tojson), by status \($s.byStatus | tojson))" else null end),
              (if $rel.drain.timedOut != false then "release drain timed out after \($rel.drain.waitedSecs) s" else null end),
              (if ($s.signals.workflowsNotExactlyOneRow // 1) != 0 then "\($s.signals.workflowsNotExactlyOneRow) run(s) without exactly one perf.release row (lost or duplicated)" else null end),
              (if ($s.signals.workflowsNotExactlyOneConsumed // 1) != 0 then "\($s.signals.workflowsNotExactlyOneConsumed) run(s) without exactly one consumed row" else null end),
              (if ($s.signals.unconsumed // 1) != 0 then "\($s.signals.unconsumed) unconsumed signal row(s)" else null end),
              (if ($rk.checksPassed // null) != $pmax then "\($rk.checksPassed // 0) of \($pmax) signals accepted" else null end)
            ] | reasons end)
      end ),
    ( ($m.runStatus.byStatus // {}) as $by
      | ((($by.FAILED // 0) == 0) and (($by.TERMINATED // 0) == 0) and ($m.runStatus | type) == "object") as $ok
      | check("correctness.noFailures"; "correctness"; if $ok then "PASS" else "FAIL" end;
          {byStatus: $by, total: $m.runStatus.total}; {FAILED: 0, TERMINATED: 0};
          if $ok then "no row of the run (\($m.runPrefix)) FAILED or was TERMINATED: \($by | tojson)"
          else "\($by.FAILED // 0) FAILED, \($by.TERMINATED // 0) TERMINATED over \($m.runPrefix)" end) ),
    ( ([($m.probes | objs)[] | . as $p
        | ([$k6[] | select(.phase == $p.phase)] | last // {}) as $r
        | {P: $p.P, accepted: $p.accepted, completed: ($p.status.byStatus.COMPLETED // 0), total: $p.status.total,
           timedOut: $p.drain.timedOut, checksFailed: ($r.checksFailed // null),
           ok: ($p.accepted > 0 and ($p.status.byStatus.COMPLETED // 0) == $p.accepted and $p.status.total == $p.accepted
                and $p.drain.timedOut == false and ($r.checksFailed // 1) == 0)}]) as $pc
      | (($pc | length) > 0 and ($pc | all(.ok))) as $ok
      | check("correctness.probesCompleted"; "correctness"; if $ok then "PASS" else "FAIL" end;
          $pc; {completed: "== accepted", checksFailed: 0, within: "120 s"};
          if ($pc | length) == 0 then "no probe ran"
          elif $ok then "every probe start was accepted and COMPLETED: \([$pc[] | "P=\(.P) \(.completed)/\(.accepted)"] | join(", "))"
          else ([$pc[] | select(.ok | not) | "P=\(.P): \(.completed) of \(.accepted) COMPLETED (\(.total) rows, timed out \(.timedOut), failed starts \(.checksFailed))"] | join("; ")) end) ),
    check("correctness.noForeignRows"; "correctness";
          if $m.foreignRows == 0 then "PASS" else "FAIL" end;
          $m.foreignRows; 0;
          if $m.foreignRows == 0 then "no instance row outside this run's id space \($m.runPrefix)"
          else "\($m.foreignRows) instance row(s) outside \($m.runPrefix): leftover state" end),
    # ── gates ──
    ( if ($plateauWins | length) < 2 or ($wMin.perParkedCallsPerMinExact | isnum | not) or ($wMax.perParkedCallsPerMinExact | isnum | not)
         or $wMin.perParkedCallsPerMinExact <= 0 then
        check("gate.parkedCostLinear"; "gate"; "INVALID"; {plateaus: [$plateauWins[] | {P, perParkedCallsPerMin}]};
              {band: linear_band}; "fewer than two plateaus with a positive per-parked figure: linearity cannot be judged")
      else
        ($wMax.perParkedCallsPerMinExact / $wMin.perParkedCallsPerMinExact) as $ratio
        | ((($ratio - 1) | fabs) <= linear_band) as $ok
        | check("gate.parkedCostLinear"; "gate"; if $ok then "PASS" else "FAIL" end;
            {ratio: ($ratio | round4), Pmin: $wMin.P, Pmax: $wMax.P,
             perParkedCallsPerMinAtPmin: $wMin.perParkedCallsPerMin, perParkedCallsPerMinAtPmax: $wMax.perParkedCallsPerMin,
             plateaus: [$plateauWins[] | {P, perParkedCallsPerMin, perParkedCallsPer30s}]};
            {min: (1 - linear_band), max: (1 + linear_band)};
            "per-parked cost at P=\($wMax.P) is \($ratio | round4) x that at P=\($wMin.P) (\($wMax.perParkedCallsPerMin) vs \($wMin.perParkedCallsPerMin) statements per parked run per minute)"
            + if $ok then ": linear within +/- \(linear_band * 100) %"
              elif $ratio > 1 then ": SUPERLINEAR, more than \(1 + linear_band) x"
              else ": SUBLINEAR, below \(1 - linear_band) x: re-checks are not keeping their interval at the larger population" end)
      end ),
    ( statements_gate("gate.parkedCostBaseline";
          {statementsPerWorkflowNet: ($wMax.perParkedCallsPerMin // null), netTolerancePerWorkflow: ($wMax.tolerancePerMin // null)};
          $expected; s3_relative_allowance)
      | if .status == "NO_BASELINE" then .detail = "no usable baseline: \($noBase)" else . end
      | .detail = "per parked run per minute at P=\($wMax.P // "n/a"): " + .detail ),
    # ── advisories ──
    advisory_guarded("advisory.decomposition";
      ([$decomposition[] | . as $d | .rows[] | select(.matches == false)
        | "P=\($d.P) \(.component): measured \(.per // .calls) \(.perUnit // "calls"), documented \(.documentedPer // 0)"]) as $diff
      | check("advisory.decomposition"; "advisory";
              if ($decomposition | length) > 0 and ($diff | length) == 0 then "PASS" else "WARN" end;
              $decomposition; {documented: "docs/operations.md §13 arithmetic at the node's bound intervals"};
              if ($decomposition | length) == 0 then "no plateau window to decompose"
              else ([$decomposition[] | . as $d
                     | "P=\($d.P): " + ([.rows[] | select(.component == "wakeRecheck" or .component == "lockRenewal" or .component == "recoveryPages" or .component == "heldKeys")
                                         | "\(.component) \(.per) (doc \(.documentedPer))"] | join(", "))] | join("; "))
                + (if ($diff | length) == 0 then "; every component matches the documented arithmetic"
                   else "; FINDINGS (measured differs from the documented arithmetic): \($diff | join("; "))" end) end)),
    advisory_guarded("advisory.probeLatency";
      ([($m.probes | objs)[] | {P, p50Ms: .latency.p50Ms, p95Ms: .latency.p95Ms, maxMs: .latency.maxMs, completed: .latency.completed}]) as $pl
      | ([$pl[] | select(.P == 0)] | first // null) as $p0
      | ($pl | max_by(.P) // null) as $pM
      | (($p0.p95Ms | isnum) and ($pM.p95Ms | isnum) and $p0.p95Ms > 0) as $have
      | check("advisory.probeLatency"; "advisory";
              if $have and $pM.p95Ms <= probe_latency_ratio_max * $p0.p95Ms then "PASS" else "WARN" end;
              $pl; {p95AtPmax: "<= \(probe_latency_ratio_max) x p95 at P = 0"};
              if $have | not then "probe latency not available at P = 0 and at Pmax"
              else "probe (chain 3 x 20 ms) start-to-terminal p50 / p95: \([$pl[] | "P=\(.P) \(.p50Ms) / \(.p95Ms) ms"] | join(", ")); p95 at P=\($pM.P) is \($pM.p95Ms / $p0.p95Ms | round2) x that at P=0"
                + (if $pM.p95Ms <= probe_latency_ratio_max * $p0.p95Ms then "" else ": parked load slows active work" end) end)),
    advisory_guarded("advisory.heapPerParked";
      (($promMax.heap.usedAvg | isnum) and ($promIdle.heap.usedAvg | isnum) and ($promMax.P | isnum) and $promMax.P > 0) as $have
      | check("advisory.heapPerParked"; "advisory"; if $have then "PASS" else "WARN" end;
              {idle: ($promIdle // null | if . then {heap, threads} else null end),
               plateaus: [$promPlateaus[] | {P, heap, threads, parkedGauge}]};
              null;
              if $have | not then "heap not available (no jvm_memory_used_bytes samples for the idle and Pmax windows)"
              else "heap used (avg over the window, all nodes) idle \($promIdle.heap.usedAvg / 1048576 | round2) MiB -> P=\($promMax.P) \($promMax.heap.usedAvg / 1048576 | round2) MiB: about \(($promMax.heap.usedAvg - $promIdle.heap.usedAvg) / $promMax.P | round) bytes per parked run (rough: young-generation sawtooth included)"
                + (if ($promMax.heap.oldGenMax | isnum) and ($promIdle.heap.oldGenMax | isnum) then "; old generation max \($promIdle.heap.oldGenMax / 1048576 | round2) -> \($promMax.heap.oldGenMax / 1048576 | round2) MiB" else "" end)
                + "; live platform threads \([$promIdle] + $promPlateaus | map("P=\(.P) \(.threads.liveMax // "n/a")") | join(", ")) (jvm_threads_live_threads counts platform threads only: parked runs are virtual threads and do not appear in it) (INFO only)" end)),
    advisory_guarded("advisory.parkedSlots";
      ([$promPlateaus[] | {P, workflowSlotsMax: .slotsUsed.workflowMax, workflowSlotsAvg: (.slotsUsed.workflowAvg | if isnum then round2 else . end),
                           parkedGaugeAvg: (.parkedGauge.avg | if isnum then round2 else . end)}]) as $ps
      | (($id.nodes[0].workerDefaultConcurrency // null) as $c | if ($c | isnum) then $c * $replicas else null end) as $limit
      | (($ps | length) > 0 and ($ps | all(.workflowSlotsMax | isnum))) as $have
      | ($have and ($ps | all(($limit | isnum) and .workflowSlotsMax < $limit))) as $ok
      | check("advisory.parkedSlots"; "advisory"; if $ok then "PASS" else "WARN" end;
              {workflowSlots: $limit, plateaus: $ps}; {workflowSlotsMax: "< workflowSlots while P >> workflowSlots are parked"};
              if ($ps | length) == 0 then "no plateau window"
              elif $have | not then "not available: maestro_taskqueue_slots_used is not exported (an engine that predates the WP11 slot gauge, or no scrape)"
              elif $ok then "parked runs do not hold workflow slots: with \([$ps[] | "P=\(.P)"] | join(", ")) parked the workflow slots in use peaked at \([$ps[] | .workflowSlotsMax] | map(tostring) | join(", ")) of \($limit) (avg \([$ps[] | .workflowSlotsAvg] | map(tostring) | join(", "))); maestro_workflows_parked averaged \([$ps[] | .parkedGaugeAvg] | map(tostring) | join(", "))"
              else "workflow slots reached the limit \($limit) while runs were parked: \($ps | tojson)" end)),
    advisory_guarded("advisory.release";
      check("advisory.release"; "advisory"; if $releaseFig != null then "PASS" else "WARN" end;
            $releaseFig; null;
            if $releaseFig == null then "no release figures (sleep mode, or no release window)"
            else "\($releaseFig.count) runs released: signals sent in \($releaseFig.k6SignalSecs) s (k6, container start included), all terminal \($releaseFig.drainSecs) s after the window opened; statements per released run gross \($releaseFig.grossPerReleased), net of the idle rate \($releaseFig.netPerReleased) (includes at most \($releaseFig.recheckResidualBoundPerReleased) re-check reads per run made before its signal arrived) over a \($releaseFig.windowSecs) s window" end)),
    advisory_guarded("advisory.seedCost";
      check("advisory.seedCost"; "advisory"; if ($seeds | length) > 0 and ($seeds | all(.netPerStartedRun | isnum)) then "PASS" else "WARN" end;
            $seeds; null;
            if ($seeds | length) == 0 then "no seed window"
            else ([$seeds[] | "P=\(.P): \(.started) runs started paced over \(.k6Secs) s (k6, container start included), all parked \(.lagSecs) s after k6 ended (2 s poll), \(.netPerStartedRun) statements per started-and-parked run net of the idle rate and the already-parked population (its own first re-checks included)"] | join("; ")) end)),
    advisory_guarded("advisory.contention";
      ((($loadPerCpu | length) > 0) or any($promPlateaus[]; .nodeCpu.processAvg | isnum)) as $have
      | check("advisory.contention"; "advisory"; if $have and ($loadHot | not) and ($cpuHot | length) == 0 then "PASS" else "WARN" end;
              {hostLoadPerCpu: $loadPerCpu, cpuHotPlateaus: $cpuHot,
               nodeCpu: [$promPlateaus[] | {P, nodeCpu}], hostLoad: $hl};
              {hostLoadPerCpu: "<= \(host_load_per_cpu_max)", nodeCpu: "process/system CPU average < \(cpu_saturation) in every plateau window"};
              if $have | not then "not available: neither host load nor node CPU was recorded"
              elif $loadHot or ($cpuHot | length) > 0 then
                "contended: " + ([(if $loadHot then "host load average up to \($loadPerCpu | max) per CPU, above \(host_load_per_cpu_max)" else null end),
                                  (if ($cpuHot | length) > 0 then "node CPU saturated at P=\($cpuHot | map(tostring) | join(", P="))" else null end)] | reasons)
                + "; the latency advisories of this run are not the engine's alone (statement counts are unaffected unless re-checks fall behind)"
              else "quiet: host load at most \($loadPerCpu | max // "n/a") per CPU (<= \(host_load_per_cpu_max)), node CPU below \(cpu_saturation) in every plateau window (blind spot: contention too mild to cross either)" end)),
    advisory_guarded("advisory.runAbandoned";
      $m.runAbandonedIncrease as $a
      | any($promPlateaus[]; .slotsUsed.workflowMax | isnum) as $wp11
      | check("advisory.runAbandoned"; "advisory";
              if ($a | isnum) then (if $a == 0 then "PASS" else "WARN" end) elif $wp11 then "PASS" else "WARN" end;
              $a; 0;
              if ($a | isnum) then "\($a) run(s) abandoned during the scenario (maestro_run_abandoned_total)"
              elif $wp11 then "no maestro_run_abandoned_total series: the counter registers on its first increment, and this engine exports the other WP11 meters, so none was abandoned"
              else "not available: maestro_run_abandoned_total is not exported and neither are the other WP11 meters (an engine that predates them)" end))
  ] as $checks
| ([$plateauWins[] | . as $w
    | ([$plRecs[] | select(.P == $w.P)] | first // {}) as $pl
    | ([$promPlateaus[] | select(.P == $w.P)] | first // {}) as $pp
    | ([($m.probes | objs)[] | select(.P == $w.P)] | first // {}) as $pr
    | ([$decomposition[] | select(.P == $w.P)] | first // {rows: []}) as $d
    | def r($n): ([$d.rows[] | select(.component == $n)] | first // {});
    {P: $w.P, timeToParkSecs: ($pl.seed.timeToParkSecs // null), windowSecs: $w.windowSecs,
     grossCallsPerSec: $w.cps, idleCallsPerSec: ($cps0 | round4),
     grossPerParkedPerMin: ($w.cpsExact * 60 / $w.P | round4),
     perParkedCallsPerMin: $w.perParkedCallsPerMin, perParkedCallsPer30s: $w.perParkedCallsPer30s,
     recheckReadsPerParkedPerInterval: r("wakeRecheck").per, renewalStatementsPerPass: r("lockRenewal").per,
     recoveryPagesPerPass: r("recoveryPages").per, heldKeysPerPass: r("heldKeys").per, otherCalls: r("other").calls,
     heapUsedAvgMiB: ($pp.heap.usedAvg // null | if isnum then . / 1048576 | round2 else . end),
     liveThreadsMax: ($pp.threads.liveMax // null), workflowSlotsMax: ($pp.slotsUsed.workflowMax // null),
     probeP50Ms: ($pr.latency | if type == "object" then .p50Ms else null end),
     probeP95Ms: ($pr.latency | if type == "object" then .p95Ms else null end)}]) as $table
| result($id;
         $m + {windows: [range(0; $rawWindows | length) as $i
                         | $rawWindows[$i] + ($windows[$i] | {lockCleanerCalls, cps, perParkedCallsPer30s, perParkedCallsPerMin,
                                                               perParkedCallsPerRecheck, tolerancePerMin})],
               idleCallsPerSec: ($cps0 | round4), decomposition: $decomposition, seeds: $seeds,
               releaseFigures: $releaseFig, plateauTable: $table,
               baselineCandidate: {parkMode: $mode, wakeRecheckInterval: $node0.wakeRecheckInterval,
                                   recoveryPollInterval: $node0.recoveryPollInterval, plateaus: $planned,
                                   Pmax: ($wMax.P // null),
                                   perParkedCallsPerMin: ($wMax.perParkedCallsPerMin // null),
                                   perParkedCallsPer30s: ($wMax.perParkedCallsPer30s // null),
                                   perParkedTolerancePerMin: ($wMax.tolerancePerMin // null)}};
         $checks)
