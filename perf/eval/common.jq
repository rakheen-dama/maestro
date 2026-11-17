# Shared evaluation for every scenario (included by perf/eval/<scenario>.jq).
#
# result.json, schema 1 (pinned for WP12):
#   {schema: 1, identity, scenario, profile, measurements,
#    checks: [{id, class: validity|correctness|gate|advisory,
#              status: PASS|FAIL|INVALID|NO_BASELINE|WARN|ERROR, value, limit, detail}],
#    verdict: PASS|FAIL|INVALID|ERROR}
#
# The verdict is decided by allow-list, never by enumerating bad statuses.
# Precedence (first match wins; exit code as perf/run.sh returns it):
#   a. any check has status ERROR, or an unknown class or status,
#      or an ADVISORY check has any status other than PASS or
#      WARN, or the check list is empty (or not a list)
#      (evaluator defect, misconfigured check, invalid allowance,
#      a scenario that misuses an advisory)                        -> ERROR    exit 3
#   b. any VALIDITY check is not PASS
#      (the measurement itself cannot be trusted)                  -> INVALID  exit 2
#   c. any CORRECTNESS check is not PASS or NO_BASELINE: FAIL, and
#      also INVALID or WARN (a correctness check that could not
#      establish correctness counts as failed)                     -> FAIL     exit 1
#   d. any GATE check is not PASS, NO_BASELINE or INVALID: FAIL,
#      and also WARN                                               -> FAIL     exit 1
#   e. any GATE check is INVALID (could not be judged, e.g. the
#      statement gate's 25 % tolerance cap)                        -> INVALID  exit 2
#   f. otherwise (NO_BASELINE gates count as pass)                 -> PASS     exit 0
# So only validity outranks FAIL: a correctness FAIL is never hidden behind
# a gate that could not be judged. Advisory checks (PASS or WARN) never
# change the verdict, and a failure INSIDE an advisory cannot raise ERROR:
# anything that goes wrong while evaluating one is reported as that
# advisory's WARN with the reason (advisory_guarded). An advisory that
# REPORTS FAIL, INVALID, NO_BASELINE or ERROR is a programming error in the
# scenario (a judgement given the wrong class) and makes the run ERROR (a)
# rather than letting the run PASS silently past it. An empty check list is
# ERROR too: a run that judged nothing has not passed.
#
# Statement counts. pg_stat_statements counts every call the engine's role
# makes in a window, and an engine is never idle: its timer poller (each
# node's leader-election upsert plus the leader's due-timer query, every
# timer-poll tick), its recovery poller (each node, every recovery-poll tick)
# and, when due, the lock cleaner, signal janitor and retention sweeper all
# issue statements. So a load window's maestroCalls is GROSS: workflow work
# plus background. The NET figure subtracts the background rate measured on
# the idle stack times the load window's length. Background is periodic, so
# the subtraction is exact only up to one tick per poller; netToleranceCalls
# (distinct background statements x replicas) bounds that.
# The idle window counts its timer-poll ticks from what the database
# recorded, not from its length: see "Timer-poll ticks" below.
#
# Gates on statements: a BASELINE-RELATIVE statement gate (S1, S3) must go
# through statements_gate below: symmetric, PASS only when
# |net - expected| <= band, band = tolerance + relativeAllowance x expected
# (tolerance = netTolerancePerWorkflow, the same on both sides;
# relativeAllowance a fraction in [0, 0.5] the scenario declares and
# justifies with measurements in its own header, 0 by default; anything else
# is ERROR). INVALID when the tolerance alone exceeds 25 % of the expected
# value (too few workflows completed for the figure to mean anything). FAIL
# for a null, zero or negative net, and a drop below the band FAILs with a
# message to re-record the baseline if the improvement is intended
# (perf/run.sh --record-baseline, which judges the run without the old
# baseline: lib/baseline.sh). The smoke has no expected value and gates only a wide absolute
# range ([5, 500] net statements per workflow) that proves the measurement
# works; it does not call statements_gate, so neither the allowance nor the
# 25 % cap applies to it.
# Known limits of this model: see "Known limits" in perf/run.sh's header.

def check($id; $class; $status; $value; $limit; $detail):
  {id: $id, class: $class, status: $status, value: $value, limit: $limit, detail: $detail};

def known_classes: ["validity", "correctness", "gate", "advisory"];
def known_statuses: ["PASS", "FAIL", "INVALID", "NO_BASELINE", "WARN", "ERROR"];

def verdict:
  def has($cls; $statuses): any(.[]; .class == $cls and (.status as $s | $statuses | index([$s])));
  def lacks($cls; $statuses): any(.[]; .class == $cls and (.status as $s | $statuses | index([$s]) | not));
  if type != "array" or length == 0 then "ERROR"                       # a
  elif any(.[]; (.class as $c | known_classes | index([$c]) | not)
              or (.status as $s | known_statuses | index([$s]) | not)
              or .status == "ERROR"
              or (.class == "advisory" and .status != "PASS" and .status != "WARN")) then "ERROR" # a
  elif lacks("validity"; ["PASS"]) then "INVALID"                       # b
  elif lacks("correctness"; ["PASS", "NO_BASELINE"]) then "FAIL"        # c
  elif lacks("gate"; ["PASS", "NO_BASELINE", "INVALID"]) then "FAIL"    # d
  elif has("gate"; ["INVALID"]) then "INVALID"                          # e
  else "PASS" end;                                                      # f

def round2: if . == null then null else (. * 100 | round) / 100 end;
def round4: if . == null then null else (. * 10000 | round) / 10000 end;

def reasons: map(select(. != null)) | join("; ");

def isnum: type == "number";

# ── Timer-poll ticks ────────────────────────────────────────────────────────
# The idle window's ticks are COUNTED from the statements the database
# recorded in it (its pg_stat_statements list, .window.top), never assumed
# from its length: a stalled poller, or a database that recorded nothing,
# must not pass.
#
# The tick statement is the timer poller's due-timer query (called only by
# TimerPoller.pollDueTimers). Since 3.7 the Postgres store compares on the
# database clock (PostgresWorkflowStore.getDueTimersByStoreClockSql), which
# pg_stat_statements records as (r20261116t221546z and every later run)
#   SELECT id, workflow_instance_id, workflow_id, timer_id, fire_at, status,
#   created_at FROM maestro_workflow_timer WHERE fire_at <= now() AND
#   status = $2 ORDER BY fire_at ASC LIMIT $1 FOR UPDATE SKIP LOCKED
# ('PENDING' normalised to $2; whitespace collapsed and the text cut at 200
# characters by pgss-window.sql). An older engine binds the leader's clock:
#   ... FROM maestro_workflow_timer WHERE fire_at <= $1 AND status = $3
#   ORDER BY fire_at ASC LIMIT $2 FOR UPDATE SKIP LOCKED
# Both forms are matched (self-tested), top-level entries only, by the regex
# tick_query_re, which needs only the stable head of the WHERE clause
# (fire_at <= $n or now(), then status =): it survives the normalisation
# and the cut, and no other engine
# statement (and nothing of the harness, which runs as postgres and is not in
# the engine's list) reads maestro_workflow_timer by fire_at.
#
# What one tick looks like (idle windows of 60.3 s at a 5 s poll, smoke runs
# that PASS this evaluator: r20261116t221546z at 1 replica, r20261116t231624z
# at 2; the earlier r20261112t170809z / r20261112t165400z (1 replica) and
# r20261112t153828z / r20261112t154022z (2) recorded the same counts but are
# INVALID here, validity.sweeps: their nodes predate the sweep intervals in
# /perf/info):
#   1 replica:  1 leader-election upsert + 1 due-timer query  (12 + 12);
#   2 replicas: 2 leader-election upserts (every node tries every tick)
#               + 1 due-timer query (only the leader polls) (24 + 12).
# So observedTicks = the due-timer query's calls, at any replica count. The
# leader-election upsert is shared with the retention sweeper's election, so
# it is no tick counter; it only checks from below that every node's poller
# ran: leaderElectionCalls >= replicas x (observedTicks - 1). That bound is
# loose (Known limit 4 in perf/run.sh): it allows one missed tick per node,
# and a retention election in the window inflates the count; it proves each
# node's poller ran at least about observedTicks - 1 times, and it does catch
# a node whose poller is fully dead (2 replicas, 12 ticks: about 12 upserts
# against a floor of 22).
def tick_query_re: "FROM maestro_workflow_timer WHERE fire_at <= (\\$[0-9]+|now\\(\\)) AND status = ";
def leader_election_query_re: "^INSERT INTO maestro_leader_election ";

# Calls of the engine's top-level statements matching $re in a pgss window's
# statement list, and how many entries matched.
def statement_calls($w; $re):
  ($w.top | if type == "array" then . else [] end
   | map(select(type == "object" and .toplevel == true and (.query | type) == "string"
                and (.calls | isnum) and (.query | test($re))))) as $hits
  | {entries: ($hits | length), calls: ($hits | map(.calls) | add // 0)};

# What the idle window shows about the timer poller (null fields when the
# record is too malformed to say).
def background_ticks($bg; $replicas):
  statement_calls($bg.window; tick_query_re) as $tick
  | statement_calls($bg.window; leader_election_query_re) as $el
  | {expectedTicks: ($bg.window.windowSecs / $bg.timerPollSecs | floor),
     observedTicks: $tick.calls, tickStatementEntries: $tick.entries,
     leaderElectionCalls: $el.calls, replicas: $replicas};

# Why measure_background's output cannot be used, or null if it can.
def background_problem($bg; $replicas):
  if $bg == null then "no background (idle) window was measured"
  elif ($bg | type) != "object" then "background is a \($bg | type), not an object"
  elif ($bg.window | type) != "object" then "background has no pg_stat_statements window"
  elif ([$bg.window.windowSecs, $bg.window.maestroCalls, $bg.window.maestroCallsTopLevel,
         $bg.window.maestroStatements] | all(isnum) | not)
    then "background window lacks windowSecs/maestroCalls/maestroCallsTopLevel/maestroStatements"
  elif $bg.window.windowSecs <= 0 then "background window has no length"
  elif ($bg.timerPollSecs | isnum | not) or $bg.timerPollSecs <= 0
    then "background record has no timer-poll interval"
  elif ($replicas | isnum | not) or $replicas < 1 then "the run records no replica count"
  else
    background_ticks($bg; $replicas) as $t
    | if $t.expectedTicks < 2
        then "background window of \($bg.window.windowSecs) s spans \($t.expectedTicks) timer-poll tick(s) of \($bg.timerPollSecs) s, fewer than 2: the background rate is not trustworthy"
      elif $t.tickStatementEntries == 0
        then "the timer poller's due-timer query (FROM maestro_workflow_timer WHERE fire_at <= …) is absent from the idle window: the poller did not run there, or the database recorded nothing; the background rate is not trustworthy"
      elif $t.observedTicks < 2
        then "the idle window recorded \($t.observedTicks) timer-poll tick(s), fewer than 2: the background rate is not trustworthy"
      elif ($t.observedTicks - $t.expectedTicks | fabs) > 1
        then "the idle window recorded \($t.observedTicks) timer-poll ticks where its \($bg.window.windowSecs) s at the node's \($bg.timerPollSecs) s interval hold \($t.expectedTicks) (+/- 1): the poller is not running at the interval the node reports, so the background model is wrong"
      elif $t.leaderElectionCalls < $replicas * ($t.observedTicks - 1)
        then "the idle window recorded \($t.leaderElectionCalls) leader-election upserts for \($t.observedTicks) ticks at \($replicas) replica(s), fewer than \($replicas) x (\($t.observedTicks) - 1): not every node's timer poller ran each tick"
      elif $bg.window.maestroCalls < $t.observedTicks + $t.leaderElectionCalls
        then "the idle window's maestroCalls (\($bg.window.maestroCalls)) is below the calls of its own poller statements (\($t.observedTicks + $t.leaderElectionCalls)): the record contradicts itself"
      else null end
  end;

# validity.background: the idle window the net figures rest on was measured,
# and the timer poller demonstrably ran in it at the interval the node reports.
def background_check($bg; $replicas):
  background_problem($bg; $replicas) as $p
  | (try ({windowSecs: $bg.window.windowSecs, maestroCalls: $bg.window.maestroCalls,
           timerPollSecs: $bg.timerPollSecs, plannedTicks: $bg.ticks}
          + background_ticks($bg; $replicas)) catch null) as $v
  | check("validity.background"; "validity"; if $p == null then "PASS" else "INVALID" end;
      $v;
      {windowSecs: "> 0", expectedTicks: ">= 2 (floor(windowSecs / timerPollSecs))",
       observedTicks: ">= 2 and within expectedTicks +/- 1 (calls of the due-timer query)",
       tickStatement: tick_query_re,
       leaderElectionCalls: ">= replicas x (observedTicks - 1)"};
      if $p == null then "background rate measured over \($bg.window.windowSecs) s: \($v.observedTicks) timer-poll ticks recorded (due-timer query calls), \($v.expectedTicks) expected at \($bg.timerPollSecs) s; \($v.leaderElectionCalls) leader-election upserts at \($replicas) replica(s)"
      else $p end);

# Every pg_stat_statements window a scenario measured: the background
# (idle) window, .window, .windows[] and .auxWindows[] (windows a scenario
# measures besides its judged ones, e.g. Scenario 3's seed and release
# windows: they are held to the same completeness and sweep rules).
# Malformed entries are skipped here; background_check reports them.
def pgss_windows($m):
  ([($m.background | if type == "object" then .window else null end), $m.window]
   + ($m.windows // []) + ($m.auxWindows // []))
  | map(select(type == "object"));

# Gross and net engine statements per completed workflow for one load window.
# $bg is measure_background's output; $w a pgss-window; $completed the
# divisor (this window's own workflows only, never warm-up rows). With an
# unusable background every figure that depends on it is null (and
# validity.background is INVALID).
# Lock-cleaner calls a window recorded (pgss-window.sql sweepCalls), 0 when
# it has no classification (validity.sweeps is then INVALID), and how many
# distinct statements they are.
def sweep_calls_field($w; $k):
  ($w | if type == "object" then .sweepCalls else null end
      | if type == "object" then .[$k] else null end) // 0 | if isnum then . else 0 end;
def lock_cleaner_calls($w): sweep_calls_field($w; "lockCleaner");
def lock_cleaner_statements($w): sweep_calls_field($w; "lockCleanerStatements");
# Why a load window's record cannot carry statement figures, or [] if it can.
def load_window_problems($w):
  if $w == null then ["the load window (measurements.window) is missing"]
  elif ($w | type) != "object" then ["the load window (measurements.window) is a \($w | type), not an object"]
  else [
    (if ($w.windowSecs | isnum) and $w.windowSecs > 0 then null
     else "the load window's windowSecs is \($w.windowSecs | tojson), not a positive number" end),
    (if ($w.maestroCalls | isnum) then null else "the load window's maestroCalls is \($w.maestroCalls | tojson), not a number" end),
    (if ($w.maestroCallsTopLevel | isnum) then null
     else "the load window's maestroCallsTopLevel is \($w.maestroCallsTopLevel | tojson), not a number" end)
  ] | map(select(. != null)) end;
# A load window whose record is unusable (load_window_problems) gives null
# figures here, never an error.
def statement_figures($bg; $w0; $completed; $replicas):
  (background_problem($bg; $replicas) == null) as $bgOk
  | ((load_window_problems($w0) | length) == 0) as $wOk
  | (if $wOk then $w0 else {} end) as $w
  | (if $bgOk then $bg.window else {} end) as $b
  | (if $bgOk then lock_cleaner_calls($b) else 0 end) as $bSweep
  | lock_cleaner_calls($w) as $wSweep
  | (if $bgOk then ($b.maestroCalls - $bSweep) / $b.windowSecs else null end) as $rate
  | (if $bgOk then ($b.maestroCallsTopLevel - $bSweep) / $b.windowSecs else null end) as $rateTop
  | (if $bgOk and $wOk then $rate * $w.windowSecs else null end) as $bgInLoad
  | (if $bgOk and $wOk then $rateTop * $w.windowSecs else null end) as $bgInLoadTop
  | (if $bgOk then ($b.maestroStatements - lock_cleaner_statements($b)) * $replicas else null end) as $tol
  | ($completed > 0 and $wOk) as $any
  | {
      note: "gross = every engine (role maestro) call in the load window / completed workflows, including background engine statements (timer-poller leader election and due-timer poll, recovery poll; lock-cleaner passes when due); net = (calls - lockCleanerCalls - backgroundRatePerSec x loadWindowSecs) / completed, the background rate itself measured without lock-cleaner calls. Lock-cleaner calls are identified by statement text and subtracted exactly; signal-janitor and retention statements must be absent from every window (validity.sweeps). TopLevel variants exclude statements nested in others (track=all, e.g. foreign-key trigger queries).",
      lockCleanerCallsSubtracted: {background: (if $bgOk then $bSweep else null end), load: $wSweep},
      loadWindowSecs: $w.windowSecs,
      completed: $completed,
      backgroundCalls: $b.maestroCalls,
      backgroundCallsTopLevel: $b.maestroCallsTopLevel,
      backgroundStatements: $b.maestroStatements,
      backgroundWindowSecs: $b.windowSecs,
      backgroundTicks: (if $bgOk then $bg.ticks else null end),
      backgroundTicksObserved: (if $bgOk then background_ticks($bg; $replicas).observedTicks else null end),
      backgroundTicksExpected: (if $bgOk then background_ticks($bg; $replicas).expectedTicks else null end),
      backgroundRatePerSec: ($rate | round4),
      backgroundRateTopLevelPerSec: ($rateTop | round4),
      backgroundCallsExpectedInLoadWindow: ($bgInLoad | round2),
      maestroCalls: $w.maestroCalls,
      maestroCallsTopLevel: $w.maestroCallsTopLevel,
      statementsPerWorkflowGross: (if $any then $w.maestroCalls / $completed | round2 else null end),
      statementsPerWorkflowNet:
        (if $any and $bgOk then ($w.maestroCalls - $wSweep - $bgInLoad) / $completed | round2 else null end),
      statementsPerWorkflowGrossTopLevel:
        (if $any then $w.maestroCallsTopLevel / $completed | round2 else null end),
      statementsPerWorkflowNetTopLevel:
        (if $any and $bgOk then ($w.maestroCallsTopLevel - $wSweep - $bgInLoadTop) / $completed | round2 else null end),
      netToleranceCalls: $tol,
      netTolerancePerWorkflow: (if $any and $bgOk then $tol / $completed | round2 else null end)
    };

# THE baseline-relative statement gate (for S1, S3 and any later scenario).
# $s is statement_figures' output; $expected the baseline's net statements
# per workflow (null: no usable baseline); $relativeAllowance a fraction of
# $expected (0 in the 3-argument form). The gate is SYMMETRIC: a drop is as
# suspicious as a rise (work silently not done, runs not really completing, a
# mis-measured window), and an intended improvement must show up as a
# deliberate, reviewed baseline change, not as a silent pass. In order:
#   ERROR       $relativeAllowance is not a number in [0, max_relative_allowance]
#               (0.5), or $expected is present but not a positive number: the
#               gate is misconfigured, so it judges nothing;
#   FAIL        net is null, not a number, zero or negative (regardless of
#               $expected): nothing true was measured;
#   NO_BASELINE no expected value;
#   FAIL        no net tolerance could be computed;
#   INVALID     tolerance > max_tolerance_fraction (25 %) x expected: too few
#               workflows completed for the per-workflow figure to mean
#               anything, so the gate cannot be judged either way;
#   PASS        |net - expected| <= band;
#   FAIL        otherwise; below the band the detail says how to re-record
#               the baseline (--record-baseline on a clean tree, judged
#               without the old baseline: lib/baseline.sh) if the
#               improvement is intended.
# band = tolerance + relativeAllowance x expected, the same on both sides.
# tolerance = netTolerancePerWorkflow: one tick per background poller.
# relativeAllowance: variance the background model does not explain (CAS
# retries, lock contention under load). A scenario that passes a non-zero
# one must state the fraction AND the measurements that justify it in its own
# header. The check records tolerance, relativeAllowance, the allowance in
# statements and the band, each on its own.
def max_relative_allowance: 0.5;
def max_tolerance_fraction: 0.25;
def statements_gate($id; $s; $expected; $relativeAllowance):
  ($s.statementsPerWorkflowNet) as $net
  | ($s.netTolerancePerWorkflow) as $tol
  | {statementsPerWorkflowNet: $net, netTolerancePerWorkflow: $tol} as $value
  | if ($relativeAllowance | isnum | not) or $relativeAllowance < 0
       or $relativeAllowance > max_relative_allowance then
      check($id; "gate"; "ERROR"; $value;
            {relativeAllowance: $relativeAllowance, allowed: [0, max_relative_allowance]};
            "relative allowance \($relativeAllowance | tojson) is not a number in [0, \(max_relative_allowance)]: the scenario's statement gate is misconfigured and judges nothing")
    elif $expected != null and (($expected | isnum | not) or $expected <= 0) then
      check($id; "gate"; "ERROR"; $value; {expected: $expected};
            "the baseline's expected value \($expected | tojson) is not a positive number: the baseline is malformed")
    elif ($net | isnum | not) or $net <= 0 then
      check($id; "gate"; "FAIL"; $value; {expected: $expected};
            "net statements per workflow is \($net | tojson): nothing true was measured")
    elif $expected == null then
      check($id; "gate"; "NO_BASELINE"; $value; {relativeAllowance: $relativeAllowance}; "no usable baseline")
    elif ($tol | isnum | not) then
      check($id; "gate"; "FAIL"; $value; {expected: $expected}; "no net tolerance could be computed")
    elif $tol > max_tolerance_fraction * $expected then
      check($id; "gate"; "INVALID"; $value;
            {expected: $expected, tolerance: $tol,
             maxTolerance: (max_tolerance_fraction * $expected | round2),
             maxToleranceFraction: max_tolerance_fraction};
            "tolerance \($tol) per workflow exceeds \(max_tolerance_fraction * 100) % of expected \($expected): too few workflows completed for the statement figure to mean anything; the gate cannot be judged")
    else
      ($relativeAllowance * $expected) as $allowance
      | ($tol + $allowance) as $band
      | ($net - $expected) as $delta
      | check($id; "gate";
          if ($delta | fabs) <= $band then "PASS" else "FAIL" end;
          $value + {delta: ($delta | round2)};
          {expected: $expected, tolerance: $tol, relativeAllowance: $relativeAllowance,
           allowance: ($allowance | round4), band: ($band | round4),
           min: ($expected - $band | round4), max: ($expected + $band | round4)};
          "band = tolerance \($tol) + relativeAllowance \($relativeAllowance) x expected \($expected) = \($band | round4); "
          + if ($delta | fabs) <= $band then
            "net \($net) within expected \($expected) +/- \($band | round4)"
          elif $delta > 0 then
            "net \($net) is above expected \($expected) + \($band | round4): more statements per workflow than the baseline"
          else
            "net \($net) is below expected \($expected) - \($band | round4): fewer statements per workflow than the baseline. If this improvement is intended, re-record the baseline: perf/run.sh <scenario> --profile <this run's> --replicas <this run's> --record-baseline on a clean tree (it judges the run without the old baseline, so validity and correctness must PASS, prints old and new side by side and rewrites perf/baselines/<scenario>.json), then review and commit the diff; otherwise work is missing or the window is mis-measured"
          end)
    end;
def statements_gate($id; $s; $expected): statements_gate($id; $s; $expected; 0);

# ── Baselines ───────────────────────────────────────────────────────────────
# perf/baselines/<scenario>.json as lib/evaluate.sh read it: $bs = its JSON
# documents (slurped: [] when the file is absent), $err = why the file could
# not be read (empty, not JSON) or null. Returns {b, status, detail}:
#   status null         one object of schema 1 for this scenario: the
#                       scenario matches its settings (b) itself;
#   status NO_BASELINE  no file;
#   status ERROR        the file is empty, not JSON, holds more than one
#                       document or something other than an object;
#   status INVALID      its schema is not 1 or its scenario is not this one.
# A key that appears twice in the file takes its LAST value (jq's parser).
def baseline_record($bs; $err; $scenario):
  "perf/baselines/\($scenario).json" as $path
  | if $err != null then {b: null, status: "ERROR", detail: "\($path) \($err): fix or delete it"}
    elif ($bs | type) != "array" or ($bs | length) == 0 then {b: null, status: "NO_BASELINE", detail: "\($path) is absent"}
    elif ($bs | length) > 1 then {b: null, status: "ERROR", detail: "\($path) holds \($bs | length) JSON documents, not one"}
    elif ($bs[0] | type) != "object" then {b: null, status: "ERROR", detail: "\($path) holds a JSON \($bs[0] | type), not an object"}
    elif $bs[0].schema != 1 then {b: null, status: "INVALID", detail: "\($path) is baseline schema \($bs[0].schema | tojson), not 1: this evaluator cannot read it"}
    elif $bs[0].scenario != $scenario then {b: null, status: "INVALID", detail: "\($path) is a baseline for scenario \($bs[0].scenario | tojson), not \($scenario | tojson)"}
    else {b: $bs[0], status: null, detail: null} end;

# Applies the baseline's state to a baseline gate's check (statements_gate's
# output, judged against $expected = null unless $br is usable and matched):
# a broken file makes the gate ERROR, a baseline of another schema or
# scenario makes it INVALID (unless the gate already FAILed on its own), an
# unmatched one leaves NO_BASELINE with the reason ($noBase), and a used one
# names its source run and engine commit in the detail.
def baseline_gate_detail($br; $noBase):
  if $br.status == "ERROR" then
    .status = "ERROR" | .detail = "the baseline cannot be read: \($br.detail)"
  elif $br.status == "INVALID" then
    (if .status == "NO_BASELINE" then .status = "INVALID" | .detail = "the baseline cannot be used: \($br.detail)"
     else .detail += "; the baseline cannot be used: \($br.detail)" end)
  elif .status == "NO_BASELINE" then .detail = "no usable baseline: \($noBase)"
  elif $br.b != null and $noBase == null then
    .detail += "; baseline \($br.b.runId // "with no runId") (engine \($br.b.engineCommit // "unknown" | tostring | .[0:12]), recorded \($br.b.recordedAtUtc // "at an unknown time"))"
  else . end;

# validity.warmup: the warm-up batch finished, every run of it COMPLETED,
# before the idle window opened. Otherwise warm-up work leaks into the
# background window, the background rate comes out high and the net figure
# low; a FAILED warm-up run means the stack is not healthy.
def warmup_check($m):
  $m.warmup as $w
  | if ($w | type) != "object" then
      check("validity.warmup"; "validity"; "INVALID"; null; {timedOut: false, allCompleted: true};
            "no warm-up record")
    else
      ($w.runStatus.byStatus // {}) as $by
      | ($w.runStatus.total // 0) as $total
      | ($by.COMPLETED // 0) as $done
      | ($w.count | isnum) as $hasCount
      | ($hasCount and $w.drain.timedOut == false and $total == $w.count and $done == $w.count) as $ok
      | check("validity.warmup"; "validity"; if $ok then "PASS" else "INVALID" end;
          {count: $w.count, total: $total, byStatus: $by, timedOut: $w.drain.timedOut,
           waitedSecs: $w.drain.waitedSecs};
          {timedOut: false, completed: $w.count};
          if $ok then "\($w.count)/\($w.count) warm-up runs COMPLETED in \($w.drain.waitedSecs) s, before the idle window"
          else [
            (if $hasCount | not then "warm-up record has no count" else null end),
            (if $w.drain.timedOut != false then "warm-up drain timed out after \($w.drain.waitedSecs) s" else null end),
            (if $hasCount and $done != $w.count then "\($done)/\($w.count) COMPLETED, by status \($by | tojson)" else null end)
          ] | reasons end)
    end;

# validity.adminEvents: requested == every node's bound value == (topic end offset > 0).
def admin_events_check($id):
  $id.config.adminEventsRequested as $req
  | ($id.effective.adminEventsEffectiveByNode // []) as $eff
  | ($id.effective.adminTopicEndOffset // null) as $off
  | ($off != null and ($eff | length) > 0 and ($eff | all(. == $req)) and (($off > 0) == $req)) as $ok
  | check("validity.adminEvents"; "validity"; if $ok then "PASS" else "INVALID" end;
      {requested: $req, effectiveByNode: $eff, adminTopicEndOffset: $off};
      "requested == /perf/info.adminEventsEnabled == (maestro.admin.events end offset > 0)";
      if $ok then "admin events \(if $req then "on" else "off" end) as requested"
      elif $off == null then "the admin topic end offset could not be read"
      else "requested \($req), nodes bound \($eff), topic end offset \($off)" end);

# validity.pgssComplete: no pg_stat_statements entry was evicted in any window.
def pgss_complete_check($m):
  pgss_windows($m) as $ws
  | (($ws | length) > 0 and ($ws | all(.dealloc == 0))) as $ok
  | check("validity.pgssComplete"; "validity"; if $ok then "PASS" else "INVALID" end;
      {windows: ($ws | length), dealloc: ($ws | map(.dealloc))}; {dealloc: 0};
      if ($ws | length) == 0 then "no pg_stat_statements window was measured"
      elif $ok then "no statement entry evicted"
      else "pg_stat_statements evicted entries: statement counts are incomplete" end);

# validity.k6: every k6 phase wrote its summary and exited 0, or 99 (advisory
# thresholds crossed: recorded, not failed).
def k6_check($m):
  ($m.k6 // []) as $runs
  | ($runs | map(select(.summaryMissing != false))) as $nosum
  | ($runs | map(select(.exitCode != 0 and .exitCode != 99))) as $bad
  | (($runs | length) > 0 and ($nosum | length) == 0 and ($bad | length) == 0) as $ok
  | check("validity.k6"; "validity"; if $ok then "PASS" else "INVALID" end;
      ($runs | map({phase, exitCode, summary: (.summaryMissing == false)}));
      {exitCode: [0, 99], summary: true};
      if ($runs | length) == 0 then "no k6 phase ran"
      elif $ok then
        ([$runs[] | select(.exitCode == 99) | .phase]) as $t
        | if ($t | length) > 0 then "advisory k6 thresholds crossed in: \($t | join(", "))" else "every k6 phase exited 0 with a summary" end
      else [
        (if ($nosum | length) > 0 then "no k6 summary for: \($nosum | map(.phase) | join(", "))" else null end),
        (if ($bad | length) > 0 then "k6 failed in: \($bad | map("\(.phase) (exit \(.exitCode))") | join(", "))" else null end)
      ] | reasons end);

# validity.droppedIterations: k6 could not start every iteration it was asked
# for because its virtual users were all busy. That happens when the system
# under test answers slowly (each VU waits on its request) or when the
# generator itself is starved of CPU; the run cannot tell which, so it
# measures neither, and the detail blames neither. INVALID when any phase
# dropped iterations or has no droppedIterations value at all (no summary).
def dropped_iterations_check($m):
  ($m.k6 // []) as $runs
  | ($runs | map(select((.droppedIterations | type) != "number"))) as $unknown
  | ($runs | map(select((.droppedIterations | type) == "number" and .droppedIterations > 0))) as $dropped
  | (($runs | length) > 0 and ($unknown | length) == 0 and ($dropped | length) == 0) as $ok
  | check("validity.droppedIterations"; "validity"; if $ok then "PASS" else "INVALID" end;
      ($runs | map({phase, droppedIterations}));
      {droppedIterations: 0};
      if ($runs | length) == 0 then "no k6 phase ran"
      elif $ok then "no k6 phase dropped an iteration"
      else [
        (if ($unknown | length) > 0 then "droppedIterations unknown (no k6 summary) for: \($unknown | map(.phase) | join(", "))" else null end),
        (if ($dropped | length) > 0 then "virtual users exhausted: the system under test was slow or the generator was starved; the run measures neither: \($dropped | map("\(.phase) dropped \(.droppedIterations)") | join(", "))" else null end)
      ] | reasons end);

# Every advisory is built through advisory_guarded: whatever goes wrong
# inside it (a malformed record, a jq error) becomes that advisory's WARN with
# the reason, so an advisory can never turn a run into ERROR.
def advisory_guarded($id; f):
  try f
  catch check($id; "advisory"; "WARN"; null; null;
              "this advisory could not be evaluated: \(. | tostring)");

# Seconds since the epoch (fractional) of an RFC 3339 timestamp, as Postgres
# writes timestamptz into JSON ("2026-11-12T17:08:49.054477+00:00"): date,
# T or space, time, optional fractional seconds, and an offset of Z, +HH,
# +HHMM or +HH:MM (either sign). null for anything else, never an error.
def ts_secs:
  if type != "string" then null
  else
    ([capture("^(?<d>[0-9]{4}-[0-9]{2}-[0-9]{2})[Tt ](?<t>[0-9]{2}:[0-9]{2}:[0-9]{2})(?<f>[.][0-9]+)?(?<z>[Zz]|[+-][0-9]{2}(:?[0-9]{2})?)$")] | first) as $c
    | if $c == null then null
      else
        (try ("\($c.d)T\($c.t)Z" | fromdateiso8601) catch null) as $base
        | ($c.z | if . == "Z" or . == "z" then 0
                  else (.[1:] | gsub(":"; "")) as $hm
                    | (($hm[0:2] | tonumber) * 3600
                       + (if ($hm | length) == 4 then ($hm[2:4] | tonumber) * 60 else 0 end))
                      * (if startswith("-") then -1 else 1 end)
                  end) as $offset
        | if $base == null then null else $base + ("0\($c.f // "")" | tonumber) - $offset end
      end
  end;

# Seconds in an ISO-8601 duration as /perf/info reports it (Java
# Duration.toString(): PT5S, PT10M, PT1H, PT1M30S, PT0.5S). null for anything
# else, never an error.
def duration_secs:
  if type != "string" then null
  else
    ([capture("^PT((?<h>[0-9.]+)H)?((?<m>[0-9.]+)M)?((?<s>[0-9.]+)S)?$")] | first) as $c
    | if $c == null or . == "PT" then null
      else (try (($c.h // "0" | tonumber) * 3600 + ($c.m // "0" | tonumber) * 60
                 + ($c.s // "0" | tonumber)) catch null)
      end
  end;

# ── Planned topology (WP12) ─────────────────────────────────────────────────
# A scenario that stops and starts nodes on purpose (perf/lib/topology.sh)
# records each event in measurements.topology, in the order they happened:
#   {at: <host epoch s>, dbNow: <database now(), RFC 3339>, action: "stop" |
#    "start", container: "maestro-perf-node-<i>", jvm: <the new JVM's
#    /perf/info + container on a start; null on a stop>}
# validity.identity holds every planned JVM to the stamped commit too;
# validity.noRestart expects, per container, the LAST JVM the log recorded
# (the initial one when it recorded none), so a planned restart passes and an
# unplanned one, before or after it, does not; validity.sweeps gives every JVM
# its own lifetime and allows each planned start's startup sweep. A run
# without measurements.topology is judged by exactly the rules it was before.
def topology_records($m):
  ($m.topology // []) | if type == "array" then map(select(type == "object")) else [] end;
# The time of a topology record: the database clock when it parses, else the
# host epoch the harness wrote.
def topology_secs: (.dbNow | ts_secs) // (.at | if type == "number" then . else null end);
# Every JVM of the run, per container in log order: the initial ones
# ($id.nodes) and each planned start's, each with its lifetime end (the
# container's next stop in the log; null while it was still up at the end).
def run_jvms($id; $m):
  (topology_records($m) | to_entries) as $te
  | ([($id.nodes // [])[] | {container, jvm: ., planned: false, from: -1}]
     + [$te[] | select(.value.action == "start")
        | {container: .value.container, jvm: .value.jvm, planned: true, from: .key}])
  | map(. as $j
        | ([$te[] | select(.key > $j.from and .value.container == $j.container and .value.action == "stop")]
           | first) as $stop
        | . + {end: (if $stop == null then null else ($stop.value | topology_secs) end)});

# validity.identity: every node runs the jar this run built and stamped, and
# there are exactly --replicas distinct JVM hosts. Every JVM a planned start
# recorded must run the stamped jar too, on one of those containers.
def identity_check($id; $m):
  $id.build.expectedCommit as $exp
  | ($id.nodes // []) as $nodes
  | $id.config.replicas as $r
  | ($nodes | map(.hostname) | unique | length) as $distinct
  | ($nodes | map(.buildCommit)) as $commits
  | ($nodes | map(.container)) as $containers
  | ([run_jvms($id; $m)[] | select(.planned)]) as $planned
  | ([$planned[] | select((.jvm | type) != "object" or .jvm.buildCommit != $exp
                          or (.container as $c | $containers | index([$c]) | not))]) as $badPlanned
  | ($exp != null and ($nodes | length) == $r and $distinct == $r and ($commits | all(. == $exp))
     and ($badPlanned | length) == 0) as $ok
  | check("validity.identity"; "validity"; if $ok then "PASS" else "INVALID" end;
      {buildCommits: $commits, nodes: ($nodes | length), distinctHostnames: $distinct,
       hostnames: ($nodes | map(.hostname))}
      + (if ($planned | length) > 0
         then {plannedJvms: ($planned | map({container, buildCommit: (.jvm | if type == "object" then .buildCommit else null end)}))}
         else {} end);
      {expectedCommit: $exp, replicas: $r};
      if $ok then "every node runs the stamped jar on a distinct host"
                  + (if ($planned | length) > 0 then "; so does the JVM of each of the \($planned | length) planned start(s)" else "" end)
      else [
        (if ($nodes | length) != $r then "\($nodes | length) node(s) answered, \($r) expected" else null end),
        (if $distinct != ($nodes | length) then "hostnames are not distinct" else null end),
        ($nodes[] | select(.buildCommit != $exp) | "\(.container) runs \(.buildCommit), expected \($exp)"),
        ($badPlanned[]
         | if (.jvm | type) != "object" then "a planned start of \(.container) recorded no JVM"
           elif (.container as $c | $containers | index([$c]) | not) then "a planned start names \(.container), not one of this run's nodes"
           else "the planned start of \(.container) runs \(.jvm.buildCommit), expected \($exp)" end)
      ] | reasons end);

# validity.noRestart: the same JVMs answered before and after the scenario;
# with a planned topology, each container's final JVM is the last one the
# log recorded for it, and a container whose last record is a stop must not
# have answered at the end.
def no_restart_check($id; $m):
  def jvms: map({hostname, jvmStartMillis, pid}) | sort_by(.hostname);
  topology_records($m) as $topo
  | ($id.nodes // []) as $nodes
  | ($nodes | jvms) as $before
  | [$nodes[] | . as $n | ([$topo[] | select(.container == $n.container)] | last) as $l
     | if $l == null then {container: $n.container, jvm: $n}
       elif $l.action == "start" and ($l.jvm | type) == "object" then {container: $n.container, jvm: $l.jvm}
       else {container: $n.container, jvm: null} end] as $expected
  | ($expected | map(select(.jvm == null) | .container)) as $stopped
  | ($expected | map(select(.jvm != null) | .jvm) | jvms) as $exp
  | ($topo | length > 0) as $hasTopo
  | (if $hasTopo then {expectedAfter: $exp} else {} end) as $planned
  | ($id.nodesAfter // null) as $after
  | if $after == null then
      check("validity.noRestart"; "validity"; "INVALID"; {before: $before} + $planned + {after: null}; "same JVMs";
            "no post-run /perf/info was collected")
    else
      ($after | jvms) as $a
      | (($before | length) > 0 and ($stopped | length) == 0 and $exp == $a) as $ok
      | check("validity.noRestart"; "validity"; if $ok then "PASS" else "INVALID" end;
              {before: $before} + $planned + {after: $a};
              if $hasTopo then "per container, the last JVM the topology log recorded" else "same hostnames and jvmStartMillis" end;
              if $ok then
                (if $hasTopo then "every node's final JVM is the last one the topology log recorded (\($topo | map(select(.action == "start")) | length) planned start(s)): no unplanned restart"
                 else "no node restarted during the run" end)
              elif ($stopped | length) > 0 then "the topology log's last record for \($stopped | join(", ")) is a stop, yet the run ended with it answering"
              elif $hasTopo then "a node's final JVM is not the last one the topology log recorded: an unplanned restart or replacement"
              else "a node restarted or was replaced during the run" end)
    end;

# ── Periodic sweeps (validity.sweeps) ───────────────────────────────────────
# The background model (idle-window rate, one-tick tolerance) covers only the
# pollers. The engine's periodic sweeps are accounted for EXPLICITLY, in every
# measured pg_stat_statements window (background, .window, .windows[]), from
# the intervals each node reports in /perf/info (the bound
# maestro.lock.cleanup-interval, maestro.signal.janitor-interval,
# maestro.retention.enabled / .interval) and from what the window recorded
# (pgss-window.sql's sweepCalls, classified by statement text over every
# engine entry):
#
#  Lock cleaner (PostgresLockCleanerScheduler: sleep one interval, then two
#  DELETEs, repeat; every node). Its scheduler starts inside the node's
#  context refresh, so between jvmStartMillis and reportedAtMillis (the
#  node's first /perf/info answer); pass k (k >= 1) therefore falls in
#  [jvmStart + k x I, reportedAt + k x (I + sweep_drift_secs)] (each pass
#  may lag by its own run time; 5 s per pass is generous). Per window the
#  passes that CERTAINLY and that POSSIBLY fall in it are counted over every
#  node; the observed calls must lie in [2 x certain, 2 x possible]. Those
#  calls are then SUBTRACTED exactly (statement_figures), so a window that
#  contains a pass is as trustworthy as one that does not.
#
#  Signal janitor and retention sweeper (sweep at startup, then every
#  interval). Not subtractable: the retention sweeper's leader election is
#  the timer poller's upsert. So a measured window must contain NONE of their
#  statements (observed calls 0, which also proves the startup sweeps ran
#  before the first window) and no predicted pass k >= 1. Either makes the
#  window untrustworthy.
#
# Every window: background, .window, .windows[] and .auxWindows[].
# INVALID on any of those, and when it cannot tell: no node, a node that
# does not report an interval or its start/report time, a window without
# sweepCalls or with a timestamp it cannot parse.
def sweep_drift_secs: 5;
def lock_cleaner_calls_per_pass: 2;
# Passes k >= 1 of a schedule whose pass k falls in [lo0 + k x I, hi0 + k x
# (I + drift)], counted as {certain, possible} for the window [$a, $b].
def sweep_passes($lo0; $hi0; $i; $a; $b):
  ($i + sweep_drift_secs) as $j
  | {possible: ([0, ((($b - $lo0) / $i | floor)
                     -([1, (($a - $hi0) / $j | ceil)] | max) + 1)] | max),
     certain: ([0, ((($b - $hi0) / $j | floor)
                    - ([1, (($a - $lo0) / $i | ceil)] | max) + 1)] | max)};
def named_windows($m):
  [{name: "background", w: ($m.background | if type == "object" then .window else null end)},
   {name: "window", w: $m.window}]
  + ($m.windows // [] | to_entries | map({name: "windows[\(.key)]", w: .value}))
  + ($m.auxWindows // [] | to_entries | map({name: "auxWindows[\(.key)]", w: .value}))
  | map(select(.w | type == "object"));
# Every JVM of the run (run_jvms: the initial nodes and each planned start's
# JVM) predicts passes only inside its own lifetime: from its start to the
# container's next recorded stop (the window is clipped to [a, min(b, end)]).
# A planned start whose start-up span [jvmStart, reportedAt + drift] meets a
# window, and whose JVM was not already stopped when the window opened, is
# counted in that window's plannedStarts, and each allows its
# startup sweep there: planned_start_janitor_calls signal-janitor calls and
# planned_start_retention_calls retention calls (an empty-backlog sweep: one
# candidates select per terminal status). A window flagged sweepWindow: true
# (a scenario that measures a sweep on purpose) is exempt from the
# signal-janitor and retention bounds, observed and predicted; the
# lock-cleaner rule holds in every window.
def planned_start_janitor_calls: 1;
def planned_start_retention_calls: 3;
def sweeps_problems_and_value($id; $m):
  (run_jvms($id; $m) | map(.jvm as $n | {container, planned, end,
                           start: ($n | if type == "object" then .jvmStartMillis else null end | if type == "number" then . / 1000 else null end),
                           reported: ($n | if type == "object" then .reportedAtMillis else null end | if type == "number" then . / 1000 else null end),
                           lock: ($n | if type == "object" then .lockCleanupInterval else null end | duration_secs),
                           janitor: ($n | if type == "object" then .signalJanitorInterval else null end | duration_secs),
                           retentionEnabled: ($n | if type == "object" then .retentionEnabled else null end),
                           retention: ($n | if type == "object" then .retentionInterval else null end | duration_secs)})) as $nodes
  | ($nodes | map(select((.start | isnum | not) or (.reported | isnum | not) or .reported < .start
                         or (.lock | isnum | not) or .lock <= 0 or (.janitor | isnum | not) or .janitor <= 0
                         or (.retentionEnabled | type) != "boolean"
                         or (.retention | isnum | not) or .retention <= 0
                         or (.end != null and (.end | isnum | not))))) as $badNodes
  | (named_windows($m) | map({name, a: (.w.resetAt | ts_secs), b: (.w.snapshotAt | ts_secs),
                              sweeps: .w.sweepCalls, sweepWindow: (.w.sweepWindow == true)})) as $ws
  | ($ws | map(select(.a == null or .b == null or .b < .a))) as $unparsed
  | ($ws | map(select((.sweeps | type) != "object"
                      or ([.sweeps.lockCleaner, .sweeps.signalJanitor, .sweeps.retention] | all(isnum) | not)))) as $unclassified
  | (($nodes | length) > 0 and ($badNodes | length) == 0) as $nodesOk
  | (if $nodesOk then
       [$ws[] | select(.a != null and .b != null and .b >= .a) | . as $w
        # each JVM sees the window clipped to its lifetime
        | ([$nodes[] | ([$w.b, (.end // $w.b)] | min) as $b
            | if $b < $w.a then {lp: {certain: 0, possible: 0}, jp: 0, rp: 0}
              else {lp: sweep_passes(.start; .reported; .lock; $w.a; $b),
                    jp: sweep_passes(.start; .reported; .janitor; $w.a; $b).possible,
                    rp: (if .retentionEnabled then sweep_passes(.start; .reported; .retention; $w.a; $b).possible else 0 end)}
              end]) as $per
        # a planned JVM already stopped when the window opened allows nothing in it
        | ([$nodes[] | select(.planned and .start <= $w.b and .reported + sweep_drift_secs >= $w.a
                              and (.end == null or .end >= $w.a))] | length) as $ps
        | {name: $w.name,
           sinceEarliestNodeStartSecs: [($w.a - ($nodes | map(.start) | min) | round),
                                        ($w.b - ($nodes | map(.start) | min) | round)],
           plannedStarts: $ps,
           sweepWindow: $w.sweepWindow,
           lockCleaner: {observedCalls: ($w.sweeps.lockCleaner // null),
                         predictedPasses: {certain: ($per | map(.lp.certain) | add), possible: ($per | map(.lp.possible) | add)}},
           signalJanitor: {observedCalls: ($w.sweeps.signalJanitor // null), predictedPasses: ($per | map(.jp) | add),
                           allowedForPlannedStarts: ($ps * planned_start_janitor_calls)},
           retention: {observedCalls: ($w.sweeps.retention // null), predictedPasses: ($per | map(.rp) | add),
                       allowedForPlannedStarts: ($ps * planned_start_retention_calls)}}]
     else [] end) as $per
  | {value: {windows: $per,
             nodes: ($nodes | map({container, lockCleanupSecs: .lock, signalJanitorSecs: .janitor,
                                   retentionEnabled, retentionSecs: .retention,
                                   schedulerStartBoundSecs: (if (.start | isnum) and (.reported | isnum) then (.reported - .start | round2) else null end)}
                                  + (if .planned or .end != null then {planned, lifetimeEndSecs: (if .end != null and (.start | isnum) then (.end - .start | round2) else null end)} else {} end)))},
     problems: [
       (if ($nodes | length) == 0 then "no node recorded" else null end),
       (if ($badNodes | length) > 0
        then "node(s) \($badNodes | map(.container) | join(", ")) do not report a usable jvmStartMillis / reportedAtMillis / lockCleanupInterval / signalJanitorInterval / retentionEnabled / retentionInterval in /perf/info (or a planned stop without a time): sweeps cannot be accounted for"
        else null end),
       (if ($ws | length) == 0 then "no measured window" else null end),
       (if ($unparsed | length) > 0
        then "window(s) \($unparsed | map(.name) | join(", ")) have a resetAt/snapshotAt that is not an RFC 3339 timestamp (or end before they start)"
        else null end),
       (if ($unclassified | length) > 0
        then "window(s) \($unclassified | map(.name) | join(", ")) carry no sweepCalls classification (pgss-window.sql)"
        else null end),
       ($per[] | . as $p
        | (if ($p.lockCleaner.observedCalls | isnum)
              and ($p.lockCleaner.observedCalls < lock_cleaner_calls_per_pass * $p.lockCleaner.predictedPasses.certain
                   or $p.lockCleaner.observedCalls > lock_cleaner_calls_per_pass * $p.lockCleaner.predictedPasses.possible)
           then "\($p.name): \($p.lockCleaner.observedCalls) lock-cleaner call(s) where the nodes' reported interval predicts \($p.lockCleaner.predictedPasses.certain)..\($p.lockCleaner.predictedPasses.possible) pass(es) of \(lock_cleaner_calls_per_pass) calls: the cleaner does not run as the nodes report"
           else null end),
          (if $p.sweepWindow then empty
           else
             (if ($p.signalJanitor.observedCalls | isnum) and $p.signalJanitor.observedCalls > $p.signalJanitor.allowedForPlannedStarts
              then "\($p.name): \($p.signalJanitor.observedCalls) signal-janitor call(s) inside the window (not modelled\(if $p.plannedStarts > 0 then "; its \($p.plannedStarts) planned start(s) allow \($p.signalJanitor.allowedForPlannedStarts)" else "" end))" else null end),
             (if $p.signalJanitor.predictedPasses > 0
              then "\($p.name): a signal-janitor pass may fall inside the window (not modelled)" else null end),
             (if ($p.retention.observedCalls | isnum) and $p.retention.observedCalls > $p.retention.allowedForPlannedStarts
              then "\($p.name): \($p.retention.observedCalls) retention-sweep call(s) inside the window (not modelled: its leader election is the timer poller's upsert\(if $p.plannedStarts > 0 then "; its \($p.plannedStarts) planned start(s) allow \($p.retention.allowedForPlannedStarts)" else "" end))" else null end),
             (if $p.retention.predictedPasses > 0
              then "\($p.name): a retention-sweep pass may fall inside the window (not modelled)" else null end)
           end))
     ] | map(select(. != null))};
def sweeps_check($id; $m):
  (try sweeps_problems_and_value($id; $m)
   catch {value: null, problems: ["the sweep record could not be evaluated: \(. | tostring)"]}) as $r
  | ($r.problems | length == 0) as $ok
  | check("validity.sweeps"; "validity"; if $ok then "PASS" else "INVALID" end;
      $r.value;
      {lockCleanerCalls: "within \(lock_cleaner_calls_per_pass) x [certain, possible] passes predicted from each node's reported interval, jvmStartMillis and reportedAtMillis (+\(sweep_drift_secs) s drift per pass); subtracted exactly from the window's calls",
       signalJanitorCalls: 0, retentionCalls: 0, signalJanitorOrRetentionPassesPredicted: 0};
      if $ok then
        ([$r.value.windows[] | select(.lockCleaner.observedCalls > 0) | "\(.name) \(.lockCleaner.observedCalls)"]) as $lc
        | "every measured window's sweeps are accounted for: no signal-janitor or retention statement in any window, none predicted; lock-cleaner calls as predicted from the reported intervals"
          + (if ($lc | length) > 0 then " (subtracted exactly: \($lc | join(", ")))" else " (none in any window)" end)
      else $r.problems | join("; ") end);

# Contention thresholds shared by the scenarios that judge contention (S1,
# S3): a node CPU average at or above cpu_saturation (process_cpu_usage or
# system_cpu_usage; on JDK 25 both are container-scoped), and the host's
# 1-minute load average per CPU above host_load_per_cpu_max. Why these
# values: perf/eval/s1-throughput.jq, "Contention".
def cpu_saturation: 0.9;
def host_load_per_cpu_max: 2.0;
# The host's 1-minute load per CPU of each well-formed host_load record.
def load_per_cpu($records):
  [$records[]? | select(type == "object" and (.load1 | isnum) and (.hostCpus | isnum) and .hostCpus > 0)
   | .load1 / .hostCpus | round2];

def dirty_tree_check($id):
  advisory_guarded("advisory.dirtyTree";
    $id.repo.dirty as $d
    | check("advisory.dirtyTree"; "advisory"; if $d == false then "PASS" else "WARN" end;
            $d; false;
            if $d == false then "clean tree"
            elif $d == true then "uncommitted changes: the result is not reproducible from a commit"
            else "the run did not record whether the tree was clean (\($d | tojson))" end));

def common_checks($id; $m):
  [identity_check($id; $m), no_restart_check($id; $m), admin_events_check($id),
   pgss_complete_check($m), k6_check($m), dropped_iterations_check($m), warmup_check($m),
   dirty_tree_check($id), sweeps_check($id; $m)];

# The result document: common checks first, then the scenario's.
def result($id; $m; $checks):
  (common_checks($id; $m) + $checks) as $all
  | {schema: 1, identity: $id, scenario: $id.scenario, profile: $id.profile,
     measurements: $m, checks: $all, verdict: ($all | verdict)};
