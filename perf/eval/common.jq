# Shared evaluation for every scenario (included by perf/eval/<scenario>.jq).
#
# result.json, schema 1 (pinned for WP12):
#   {schema: 1, identity, scenario, profile, measurements,
#    checks: [{id, class: validity|correctness|gate|advisory,
#              status: PASS|FAIL|INVALID|NO_BASELINE|WARN, value, limit, detail}],
#    verdict: PASS|FAIL|INVALID}
# The verdict is INVALID if any validity check is INVALID, else FAIL if any
# correctness or gate check is FAIL, else PASS. Advisory checks never change it.

def check($id; $class; $status; $value; $limit; $detail):
  {id: $id, class: $class, status: $status, value: $value, limit: $limit, detail: $detail};

def verdict:
  if any(.[]; .class == "validity" and .status == "INVALID") then "INVALID"
  elif any(.[]; (.class == "correctness" or .class == "gate") and .status == "FAIL") then "FAIL"
  else "PASS" end;

def round2: if . == null then null else (. * 100 | round) / 100 end;

def reasons: map(select(. != null)) | join("; ");

# Every pg_stat_statements window a scenario measured: .window and/or .windows[].
def pgss_windows($m): ([$m.window] + ($m.windows // [])) | map(select(. != null));

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

# validity.k6: every k6 phase exited 0, or 99 (advisory thresholds crossed: recorded, not failed).
def k6_check($m):
  ($m.k6 // []) as $runs
  | (($runs | length) > 0 and ($runs | all(.exitCode == 0 or .exitCode == 99))) as $ok
  | check("validity.k6"; "validity"; if $ok then "PASS" else "INVALID" end;
      ($runs | map({phase, exitCode})); {exitCode: [0, 99]};
      if ($runs | length) == 0 then "no k6 phase ran"
      elif $ok then
        ([$runs[] | select(.exitCode == 99) | .phase]) as $t
        | if ($t | length) > 0 then "advisory k6 thresholds crossed in: \($t | join(", "))" else "every k6 phase exited 0" end
      else "k6 failed in: \([$runs[] | select(.exitCode != 0 and .exitCode != 99) | "\(.phase) (exit \(.exitCode))"] | join(", "))" end);

def dirty_tree_check($id):
  check("advisory.dirtyTree"; "advisory"; if $id.repo.dirty then "WARN" else "PASS" end;
        $id.repo.dirty; false;
        if $id.repo.dirty then "uncommitted changes: the result is not reproducible from a commit" else "clean tree" end);

def common_checks($id; $m):
  [identity_check($id), no_restart_check($id), admin_events_check($id),
   pgss_complete_check($m), k6_check($m), dirty_tree_check($id)];

# The result document: common checks first, then the scenario's.
def result($id; $m; $checks):
  (common_checks($id; $m) + $checks) as $all
  | {schema: 1, identity: $id, scenario: $id.scenario, profile: $id.profile,
     measurements: $m, checks: $all, verdict: ($all | verdict)};
