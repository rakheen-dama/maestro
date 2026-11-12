# Shared evaluation for every scenario (included by perf/eval/<scenario>.jq).
#
# result.json, schema 1 (pinned for WP12):
#   {schema: 1, identity, scenario, profile, measurements,
#    checks: [{id, class: validity|correctness|gate|advisory,
#              status: PASS|FAIL|INVALID|NO_BASELINE|WARN, value, limit, detail}],
#    verdict: PASS|FAIL|INVALID|ERROR}
#
# The verdict is decided by allow-list, never by enumerating bad statuses:
#   ERROR   any check has an unknown class or status (an evaluator bug);
#   INVALID unless every validity check is exactly PASS;
#   FAIL    unless every correctness and gate check is PASS or NO_BASELINE;
#   PASS    otherwise. Advisory checks (PASS or WARN) never change it.
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
# (distinct background statements × replicas) bounds that, and every gate on
# statements uses the net figure with at least that tolerance.

def check($id; $class; $status; $value; $limit; $detail):
  {id: $id, class: $class, status: $status, value: $value, limit: $limit, detail: $detail};

def known_classes: ["validity", "correctness", "gate", "advisory"];
def known_statuses: ["PASS", "FAIL", "INVALID", "NO_BASELINE", "WARN"];

def verdict:
  if any(.[]; (.class as $c | known_classes | index([$c]) | not)
              or (.status as $s | known_statuses | index([$s]) | not)) then "ERROR"
  elif any(.[]; .class == "validity" and .status != "PASS") then "INVALID"
  elif any(.[]; (.class == "correctness" or .class == "gate")
                and .status != "PASS" and .status != "NO_BASELINE") then "FAIL"
  else "PASS" end;

def round2: if . == null then null else (. * 100 | round) / 100 end;
def round4: if . == null then null else (. * 10000 | round) / 10000 end;

def reasons: map(select(. != null)) | join("; ");

# Every pg_stat_statements window a scenario measured: the background
# (idle) window, .window and .windows[].
def pgss_windows($m):
  ([$m.background.window, $m.window] + ($m.windows // [])) | map(select(. != null));

# Gross and net engine statements per completed workflow for one load window.
# $bg is measure_background's output; $w a pgss-window; $completed the
# divisor (this window's own workflows only, never warm-up rows).
def statement_figures($bg; $w; $completed; $replicas):
  $bg.window as $b
  | ($b.maestroCalls / $b.windowSecs) as $rate
  | ($b.maestroCallsTopLevel / $b.windowSecs) as $rateTop
  | ($rate * $w.windowSecs) as $bgInLoad
  | ($rateTop * $w.windowSecs) as $bgInLoadTop
  | ($b.maestroStatements * $replicas) as $tol
  | {
      note: "gross = every engine (role maestro) call in the load window / completed workflows, including background engine statements (timer-poller leader election and due-timer poll, recovery poll; lock cleaner, signal janitor and retention sweeps when due); net = (calls - backgroundRatePerSec x loadWindowSecs) / completed. TopLevel variants exclude statements nested in others (track=all, e.g. foreign-key trigger queries).",
      loadWindowSecs: $w.windowSecs,
      completed: $completed,
      backgroundCalls: $b.maestroCalls,
      backgroundCallsTopLevel: $b.maestroCallsTopLevel,
      backgroundStatements: $b.maestroStatements,
      backgroundWindowSecs: $b.windowSecs,
      backgroundTicks: $bg.ticks,
      backgroundRatePerSec: ($rate | round4),
      backgroundRateTopLevelPerSec: ($rateTop | round4),
      backgroundCallsExpectedInLoadWindow: ($bgInLoad | round2),
      maestroCalls: $w.maestroCalls,
      maestroCallsTopLevel: $w.maestroCallsTopLevel,
      statementsPerWorkflowGross:
        (if $completed > 0 then $w.maestroCalls / $completed | round2 else null end),
      statementsPerWorkflowNet:
        (if $completed > 0 then ($w.maestroCalls - $bgInLoad) / $completed | round2 else null end),
      statementsPerWorkflowGrossTopLevel:
        (if $completed > 0 then $w.maestroCallsTopLevel / $completed | round2 else null end),
      statementsPerWorkflowNetTopLevel:
        (if $completed > 0 then ($w.maestroCallsTopLevel - $bgInLoadTop) / $completed | round2 else null end),
      netToleranceCalls: $tol,
      netTolerancePerWorkflow: (if $completed > 0 then $tol / $completed | round2 else null end)
    };

# validity.identity: every node runs the jar this run built and stamped, and
# there are exactly --replicas distinct JVM hosts.
def identity_check($id):
  $id.build.expectedCommit as $exp
  | ($id.nodes // []) as $nodes
  | $id.config.replicas as $r
  | ($nodes | map(.hostname) | unique | length) as $distinct
  | ($nodes | map(.buildCommit)) as $commits
  | ($exp != null and ($nodes | length) == $r and $distinct == $r and ($commits | all(. == $exp))) as $ok
  | check("validity.identity"; "validity"; if $ok then "PASS" else "INVALID" end;
      {buildCommits: $commits, nodes: ($nodes | length), distinctHostnames: $distinct,
       hostnames: ($nodes | map(.hostname))};
      {expectedCommit: $exp, replicas: $r};
      if $ok then "every node runs the stamped jar on a distinct host"
      else [
        (if ($nodes | length) != $r then "\($nodes | length) node(s) answered, \($r) expected" else null end),
        (if $distinct != ($nodes | length) then "hostnames are not distinct" else null end),
        ($nodes[] | select(.buildCommit != $exp) | "\(.container) runs \(.buildCommit), expected \($exp)")
      ] | reasons end);

# validity.noRestart: the same JVMs answered before and after the scenario.
def no_restart_check($id):
  def jvms: map({hostname, jvmStartMillis, pid}) | sort_by(.hostname);
  ($id.nodes // [] | jvms) as $before
  | ($id.nodesAfter // null) as $after
  | if $after == null then
      check("validity.noRestart"; "validity"; "INVALID"; {before: $before, after: null}; "same JVMs";
            "no post-run /perf/info was collected")
    else
      ($after | jvms) as $a
      | (($before | length) > 0 and $before == $a) as $ok
      | check("validity.noRestart"; "validity"; if $ok then "PASS" else "INVALID" end;
              {before: $before, after: $a}; "same hostnames and jvmStartMillis";
              if $ok then "no node restarted during the run" else "a node restarted or was replaced during the run" end)
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

# validity.droppedIterations: a load generator that could not start every
# iteration it was asked for measured itself, not the system under test.
# INVALID when any phase dropped iterations or has no droppedIterations
# value at all (no summary).
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
        (if ($dropped | length) > 0 then "generator saturated, not the SUT: \($dropped | map("\(.phase) dropped \(.droppedIterations)") | join(", "))" else null end)
      ] | reasons end);

def dirty_tree_check($id):
  check("advisory.dirtyTree"; "advisory"; if $id.repo.dirty then "WARN" else "PASS" end;
        $id.repo.dirty; false;
        if $id.repo.dirty then "uncommitted changes: the result is not reproducible from a commit" else "clean tree" end);

def common_checks($id; $m):
  [identity_check($id), no_restart_check($id), admin_events_check($id),
   pgss_complete_check($m), k6_check($m), dropped_iterations_check($m), dirty_tree_check($id)];

# The result document: common checks first, then the scenario's.
def result($id; $m; $checks):
  (common_checks($id; $m) + $checks) as $all
  | {schema: 1, identity: $id, scenario: $id.scenario, profile: $id.profile,
     measurements: $m, checks: $all, verdict: ($all | verdict)};
