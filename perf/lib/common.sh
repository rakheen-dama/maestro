#!/usr/bin/env bash
# Shared helpers for perf/run.sh and perf/scenarios/*.sh. Sourced, never run.
#
# Expects from the caller: PERF_DIR (absolute perf/ path), RESULTS (absolute
# results directory of this run), REPLICAS, COMPOSE_PROJECT.
# shellcheck shell=bash
# SC2016: the single-quoted '$x' strings are jq filters, expanded by jq.
# shellcheck disable=SC2016

# Progress goes to stderr and the run's harness.log: stdout is reserved for the
# summary and the final VERDICT line.
log() {
  local line
  line="[$(date -u +%H:%M:%S)] $*"
  printf '%s\n' "$line" >&2
  if [[ -n "${RESULTS:-}" && -d "${RESULTS}" ]]; then
    printf '%s\n' "$line" >>"${RESULTS}/harness.log"
  fi
}

die() {
  log "ERROR: $*"
  exit 3
}

# docker compose for this harness's project only.
dc() {
  docker compose -p "${COMPOSE_PROJECT}" -f "${PERF_DIR}/compose.yml" --profile tools "$@"
}

# Rewrites identity.json in place: identity_set '<jq filter>' [jq args…]
identity_set() {
  local filter=$1
  shift
  local tmp="${RESULTS}/identity.json.tmp"
  jq "$@" "$filter" "${RESULTS}/identity.json" >"$tmp"
  mv "$tmp" "${RESULTS}/identity.json"
}

# Appends a WARN/INFO note to identity.notes.
identity_note() {
  identity_set '.notes += [$n]' --arg n "$1"
}

node_url() {
  printf 'http://%s-node-%s:8080' "${COMPOSE_PROJECT}" "$1"
}

# Comma-separated base URLs of every replica, for k6's PERF_NODES.
node_urls() {
  local i urls=""
  for ((i = 1; i <= REPLICAS; i++)); do
    urls+="${urls:+,}$(node_url "$i")"
  done
  printf '%s' "$urls"
}

# In-network HTTP GET through the curl tool container: http_get <url> [curl args…]
http_get() {
  local url=$1
  shift
  # </dev/null: exec forwards stdin, and must not swallow a caller's loop input.
  dc exec -T curl curl -fsS -m 10 "$@" "$url" </dev/null
}

# Runs perf/sql/<name>.sql as the postgres role (never as maestro, so it is
# never counted as engine load) and prints its single JSON value.
# psql_json <name> [psql args, e.g. -v prefix=…]
psql_json() {
  local name=$1
  shift
  dc exec -T postgres psql -U postgres -d maestro -At -v ON_ERROR_STOP=1 "$@" -f - \
    <"${PERF_DIR}/sql/${name}.sql"
}

# Collects GET /perf/info from every replica into a JSON array on stdout.
collect_nodes() {
  local i info all="[]"
  for ((i = 1; i <= REPLICAS; i++)); do
    info=$(http_get "$(node_url "$i")/perf/info") || return 1
    all=$(jq -c --argjson info "$info" --arg c "${COMPOSE_PROJECT}-node-$i" \
      '. + [{container: $c} + $info]' <<<"$all")
  done
  printf '%s' "$all"
}

# Runs one k6 phase and records its exit code and key summary values in
# k6-runs.jsonl. A non-zero exit never aborts the harness: 99 (advisory
# thresholds crossed) is recorded as such, anything else makes validity.k6
# INVALID.
# k6_run <phase> <script> [KEY=VALUE env for the script…]
k6_run() {
  local phase=$1 script=$2
  shift 2
  local args=(-e "PERF_NODES=$(node_urls)" -e "PERF_PHASE=${phase}")
  local kv
  for kv in "$@"; do
    args+=(-e "$kv")
  done
  log "k6 ${phase}: ${script} $*"
  local rc=0
  local summary="${RESULTS}/k6-${phase}.json"
  rm -f "$summary"
  dc run --rm --no-deps "${args[@]}" k6 run --quiet "/scripts/${script}" \
    </dev/null >"${RESULTS}/k6-${phase}.log" 2>&1 || rc=$?
  log "k6 ${phase}: exit ${rc}"
  grep '^k6\[' "${RESULTS}/k6-${phase}.log" >&2 || true
  # droppedIterations is present only when k6 wrote its summary: k6 emits the
  # dropped_iterations metric only once an iteration is dropped, so a summary
  # without it means 0. A record without a summary carries no
  # droppedIterations at all, and validity.k6 / validity.droppedIterations
  # are INVALID for it.
  if [[ -s "$summary" ]]; then
    jq -c --arg phase "$phase" --arg script "$script" --argjson rc "$rc" '
      def v($m; $k): (.metrics[$m].values[$k] // null);
      {phase: $phase, script: $script, exitCode: $rc, summaryMissing: false,
       iterations: v("iterations"; "count"),
       droppedIterations: (v("dropped_iterations"; "count") // 0),
       httpReqs: v("http_reqs"; "count"),
       httpReqFailedRate: v("http_req_failed"; "rate"),
       vusMax: (.metrics.vus_max.values.max // null),
       checksPassed: v("checks"; "passes"),
       checksFailed: v("checks"; "fails"),
       # k6/lib/common.js start_retries: k6 emits the counter only once it is
       # added to, so a summary without it means 0.
       startRetries: (v("start_retries"; "count") // 0),
       startMs: (.metrics["http_req_duration{name:start}"].values // null
                 | if . then {p50: .med, p95: .["p(95)"], p99: .["p(99)"], max: .max} else null end)}' \
      "$summary" >>"${RESULTS}/k6-runs.jsonl"
  else
    jq -nc --arg phase "$phase" --arg script "$script" --argjson rc "$rc" \
      '{phase: $phase, script: $script, exitCode: $rc, summaryMissing: true}' >>"${RESULTS}/k6-runs.jsonl"
  fi
  return 0
}

# The k6 records of this run so far, as a JSON array.
k6_records() {
  if [[ -s "${RESULTS}/k6-runs.jsonl" ]]; then
    jq -s -c '.' "${RESULTS}/k6-runs.jsonl"
  else
    printf '[]'
  fi
}

# Waits until every one of <expected> rows of <prefix> is terminal
# (COMPLETED, FAILED or TERMINATED), polling run-status every <interval> s for
# at most <timeout> s. Prints {"status": <run-status>, "waitedSecs", "timedOut"}.
# wait_terminal <prefix> <expected> <timeout> <interval>
wait_terminal() {
  local prefix=$1 expected=$2 timeout=$3 interval=$4
  local start now status open total
  start=$(date +%s)
  while :; do
    status=$(psql_json run-status -v "prefix=${prefix}")
    total=$(jq '.total' <<<"$status")
    open=$(jq '.total - ([.byStatus.COMPLETED, .byStatus.FAILED, .byStatus.TERMINATED] | map(. // 0) | add)' <<<"$status")
    now=$(date +%s)
    if ((total >= expected && open == 0)); then
      jq -nc --argjson s "$status" --argjson w "$((now - start))" '{status: $s, waitedSecs: $w, timedOut: false}'
      return 0
    fi
    if ((now - start >= timeout)); then
      log "wait_terminal ${prefix}: timed out after ${timeout}s (total ${total}, open ${open})"
      jq -nc --argjson s "$status" --argjson w "$((now - start))" '{status: $s, waitedSecs: $w, timedOut: true}'
      return 0
    fi
    sleep "$interval"
  done
}

# Takes the host-wide run lock <lock-dir>, writing this shell's pid and
# <owner> into it, or dies (exit 3) without touching anything.
#  - mkdir is atomic: of any number of runs racing for a free lock, exactly
#    one gets it, and it writes its pid at once.
#  - A lock is HELD while its pid is running, and while it has no pid file
#    and is less than a minute old (its owner may be between mkdir and writing
#    the pid).
#  - Otherwise it is STALE. Taking a stale lock over is serialised by a second
#    atomic mkdir (<lock-dir>.takeover). Under it the run re-evaluates
#    staleness from scratch (same pid as first seen, that pid not running, and
#    a pid-less lock still over a minute old), removes the lock, re-takes it
#    with the same atomic mkdir as everyone else and writes its pid, all
#    BEFORE releasing <lock-dir>.takeover. So a run that gets the takeover
#    mutex next always finds a live pid (or a young lock) and refuses: runs
#    that find one stale lock at the same moment cannot both start.
#  - Finally the run re-checks that the lock names its own pid.
#  A run killed while holding <lock-dir>.takeover leaves it behind; the
#  refusal message says to remove it.
#  Test-only hooks (perf/lib/lock-selftest.sh forces interleavings with them):
#  PERF_LOCK_TEST_PAUSE_BEFORE_TAKEOVER (s, after judging a lock stale) and
#  PERF_LOCK_TEST_PAUSE_BEFORE_PID (s, between acquiring the lock directory
#  and writing the pid). They are INERT unless PERF_LOCK_SELFTEST=1 is also
#  set (only the lock self-test sets it); with it, each value must be a
#  non-negative decimal number of seconds (5, 0.5, .5 and 1. are accepted;
#  -1, ., abc and 1e3 are not), checked before anything is touched (a bad one makes
#  take_lock refuse, nothing created), and an active hook logs one line.
# take_lock <lock-dir> <owner>
lock_is_young() { [[ -z "$(find "$1" -maxdepth 0 -mmin +1 2>/dev/null)" ]]; }
# Prints the validated pause of test hook <var>, or nothing when the hooks are
# inert or <var> is unset; dies (before take_lock touches anything) on a value
# that is not a non-negative number.
lock_test_pause() {
  local name=$1 value
  [[ "${PERF_LOCK_SELFTEST:-}" == 1 ]] || return 0
  value=${!name:-}
  [[ -n "$value" ]] || return 0
  [[ "$value" =~ ^([0-9]+([.][0-9]*)?|[.][0-9]+)$ ]] \
    || die "lock test hook ${name}='${value}' is not a non-negative number of seconds; refusing to start, nothing touched"
  printf '%s' "$value"
}
take_lock() {
  local lock=$1 owner=$2
  local takeover="${lock}.takeover" holder current took_over="" pause_pid pause_takeover
  pause_pid=$(lock_test_pause PERF_LOCK_TEST_PAUSE_BEFORE_PID) || exit 3
  pause_takeover=$(lock_test_pause PERF_LOCK_TEST_PAUSE_BEFORE_TAKEOVER) || exit 3
  if [[ -n "${pause_pid}${pause_takeover}" ]]; then
    log "lock test hooks active (PERF_LOCK_SELFTEST=1): pause before pid ${pause_pid:-none} s, before takeover ${pause_takeover:-none} s"
  fi
  if mkdir "$lock" 2>/dev/null; then
    [[ -z "$pause_pid" ]] || sleep "$pause_pid"
    printf '%s\n' "$$" >"${lock}/pid"
  else
    holder=$(cat "${lock}/pid" 2>/dev/null || true)
    if [[ -n "$holder" ]] && ps -p "$holder" >/dev/null 2>&1; then
      die "another perf run holds ${lock} (pid ${holder}: $(cat "${lock}/owner" 2>/dev/null || echo '?')); refusing to start, nothing touched"
    fi
    if [[ -z "$holder" ]] && lock_is_young "$lock"; then
      die "another perf run is acquiring ${lock} (no pid yet); refusing to start, nothing touched"
    fi
    [[ -z "$pause_takeover" ]] || sleep "$pause_takeover"
    if ! mkdir "$takeover" 2>/dev/null; then
      die "another perf run is taking over the stale lock ${lock} (if no perf run is active, a dead one left ${takeover}: remove it); refusing to start, nothing touched"
    fi
    current=$(cat "${lock}/pid" 2>/dev/null || true)
    if [[ ! -d "$lock" || "$current" != "$holder" ]] \
      || { [[ -n "$current" ]] && ps -p "$current" >/dev/null 2>&1; } \
      || { [[ -z "$current" ]] && lock_is_young "$lock"; }; then
      rmdir "$takeover"
      die "${lock} changed hands while this run was taking it over (now pid ${current:-none}); refusing to start, nothing touched"
    fi
    rm -rf "$lock"
    if ! mkdir "$lock" 2>/dev/null; then
      rmdir "$takeover"
      die "another perf run took ${lock} first; refusing to start, nothing touched"
    fi
    [[ -z "$pause_pid" ]] || sleep "$pause_pid"
    printf '%s\n' "$$" >"${lock}/pid"
    rmdir "$takeover"
    took_over="pid ${holder:-none}"
  fi
  if [[ "$(cat "${lock}/pid" 2>/dev/null || true)" != "$$" ]]; then
    die "${lock} does not name this run (pid $$) after acquiring it; refusing to start"
  fi
  printf '%s\n' "$owner" >"${lock}/owner"
  [[ -z "$took_over" ]] || log "stale lock ${lock} (${took_over} not running): taken over"
}

# The driver's exit code for a verdict (the table in perf/run.sh's header):
# PASS 0, FAIL 1, INVALID 2, ERROR or anything else 3.
verdict_rc() {
  case "$1" in
    PASS) printf '0' ;;
    FAIL) printf '1' ;;
    INVALID) printf '2' ;;
    *) printf '3' ;;
  esac
}

# Seconds in an ISO-8601 duration as /perf/info reports it (Java
# Duration.toString(): PT5S, PT1M, PT0.5S, PT48H).
iso_secs() {
  jq -rn --arg d "$1" '"^PT((?<h>[0-9.]+)H)?((?<m>[0-9.]+)M)?((?<s>[0-9.]+)S)?$" as $re
    | if ($d | test($re)) and $d != "PT" then $d | capture($re)
        | ((.h // "0" | tonumber) * 3600 + (.m // "0" | tonumber) * 60 + (.s // "0" | tonumber))
      else error("not an ISO-8601 duration: \($d)") end'
}

# Warm-up: <count> workflows of <body> under ${RUN_ID}-warmup-, started by
# <vus> VUs round-robin over every replica and drained, BEFORE any
# pg_stat_statements window opens. Connection warm-up and first-use catalog
# lookups (the driver's pg_type queries) then fall outside every measured
# window. validity.warmup is INVALID unless every warm-up run COMPLETED before
# the drain timed out: an unfinished warm-up would leak workflow statements
# into the idle (background) window. Prints {"prefix", "count", "vus",
# "timeoutSecs", "drain", "runStatus"}.
# Test-only overrides (to force an unfinished warm-up): PERF_WARMUP_TIMEOUT
# (drain timeout, s, default 120) and PERF_WARMUP_ACTIVITY_MILLIS (replaces
# the body's activityMillis).
# warm_up <count> <vus> <body-json>
warm_up() {
  local count=$1 vus=$2 body=$3
  local prefix="${RUN_ID}-warmup-" drain timeout="${PERF_WARMUP_TIMEOUT:-120}"
  if [[ -n "${PERF_WARMUP_ACTIVITY_MILLIS:-}" ]]; then
    body=$(jq -c --argjson ms "$PERF_WARMUP_ACTIVITY_MILLIS" '.activityMillis = $ms' <<<"$body")
    log "warm-up: PERF_WARMUP_ACTIVITY_MILLIS override, body ${body}"
  fi
  k6_run warmup start-batch.js \
    "PERF_ID_PREFIX=${prefix}" "PERF_ID_OFFSET=0" "PERF_COUNT=${count}" \
    "PERF_VUS=${vus}" "PERF_MAX_DURATION=2m" "PERF_BODY=${body}"
  drain=$(wait_terminal "$prefix" "$count" "$timeout" 2)
  log "warm-up: $(jq -c '{byStatus: .status.byStatus, waitedSecs, timedOut}' <<<"$drain")"
  jq -nc --arg p "$prefix" --argjson c "$count" --argjson v "$vus" --argjson t "$timeout" --argjson d "$drain" \
    '{prefix: $p, count: $c, vus: $v, timeoutSecs: $t,
      drain: {waitedSecs: $d.waitedSecs, timedOut: $d.timedOut}, runStatus: $d.status}'
}

# The engine's background statement rate, measured on an idle stack: a
# pg_stat_statements window of a whole number of timer-poll ticks (at least
# 2, and enough to span one recovery-poll tick), both intervals read from the
# node's /perf/info rather than assumed. Nothing of the harness runs as the
# maestro role, so every engine call in it is background work: the timer
# poller (every node's leader-election upsert plus the leader's due-timer
# query, each tick) and the recovery poller (each node, each tick); and, when
# their much longer intervals fall due, the lock cleaner, signal janitor and
# retention sweeper. Prints {"ticks", "timerPollSecs", "recoveryPollSecs",
# "idleSecs", "window": <pgss-window>}.
# With <idle-secs> (Scenario 3: a window that is also a whole number of
# wake-recheck and recovery-poll intervals) the window is that long instead;
# it must be a whole number of timer-poll ticks, at least 2, or the harness
# stops (exit 3) before measuring anything.
# measure_background [<idle-secs>]
measure_background() {
  local node timer recovery ticks idle window
  node=$(jq -c '.nodes[0]' "${RESULTS}/identity.json")
  timer=$(iso_secs "$(jq -r '.timerPollInterval // empty' <<<"$node")") \
    || die "node 1 reports no timerPollInterval"
  recovery=$(iso_secs "$(jq -r '.recoveryPollInterval // empty' <<<"$node")") \
    || die "node 1 reports no recoveryPollInterval"
  if [[ -n "${1:-}" ]]; then
    ticks=$(jq -rn --argjson t "$timer" --argjson i "$1" \
      'if ($i / $t) == (($i / $t) | floor) and ($i / $t) >= 2 then $i / $t else empty end')
    [[ -n "$ticks" ]] || die "background: an idle window of $1 s is not a whole number (>= 2) of ${timer} s timer-poll ticks"
  else
    ticks=$(jq -rn --argjson t "$timer" --argjson r "$recovery" '[2, (($r / $t) | ceil)] | max')
  fi
  idle=$(jq -rn --argjson t "$timer" --argjson n "$ticks" '$t * $n')
  log "background: idle window of ${ticks} timer-poll ticks (${timer} s each) = ${idle} s"
  psql_json pgss-reset >/dev/null
  sleep "$idle"
  window=$(psql_json pgss-window)
  log "background: $(jq -c '{windowSecs, maestroCalls, maestroCallsTopLevel, maestroStatements}' <<<"$window")"
  jq -nc --argjson n "$ticks" --argjson t "$timer" --argjson r "$recovery" --argjson i "$idle" \
    --argjson w "$window" '{ticks: $n, timerPollSecs: $t, recoveryPollSecs: $r, idleSecs: $i, window: $w}'
}

# The host's load averages as the OS reports them (macOS vm.loadavg, Linux
# /proc/loadavg): a contention indicator for the shared machine (S1 records it
# at the scenario's start, at the load's start and after the drain; S3 at
# the start, at every window and after the release).
host_load() {
  local raw
  raw=$(sysctl -n vm.loadavg 2>/dev/null || cat /proc/loadavg 2>/dev/null || true)
  jq -nc --arg raw "$raw" --argjson cpus "$(getconf _NPROCESSORS_ONLN)" --argjson t "$(date +%s)" \
    '($raw | [scan("[0-9]+[.][0-9]+")] | map(tonumber)) as $l
     | {at: $t, raw: $raw, load1: ($l[0] // null), load5: ($l[1] // null), load15: ($l[2] // null), hostCpus: $cpus}'
}

# CPU of this run's containers (this compose project only, read-only) as
# `docker stats --no-stream` reports it: percent of ONE CPU, so up to 100 x
# the CPUs a container may use. One JSON line {at, cpuPercent: {name: %}},
# name = the container name without the project prefix ("node-1",
# "postgres-1"; a `compose run` container's "-run-<id>" is dropped: "k6").
container_stats() {
  local ids
  ids=$(docker ps -q --filter "label=com.docker.compose.project=${COMPOSE_PROJECT}")
  [[ -n "$ids" ]] || return 0
  # shellcheck disable=SC2086 # one argument per container id
  docker stats --no-stream --format '{{.Name}}\t{{.CPUPerc}}' $ids 2>/dev/null \
    | jq -Rsc --argjson t "$(date +%s)" --arg p "${COMPOSE_PROJECT}-" '
        [split("\n")[] | select(length > 0) | split("\t")
         | {key: (.[0] | ltrimstr($p) | sub("-run-[0-9a-f]+$"; "")),
            value: (.[1] | rtrimstr("%") | tonumber? // null)}]
        | {at: $t, cpuPercent: from_entries}'
}

# One Prometheus instant query at <epoch>; prints its single value as a JSON
# number, or null (no series, NaN/Inf, or the query failed).
# prom_instant <query> <epoch>
prom_instant() {
  dc exec -T curl curl -fsS -m 30 -G http://prometheus:9090/api/v1/query \
    --data-urlencode "query=$1" --data-urlencode "time=$2" </dev/null 2>>"${RESULTS}/harness.log" \
    | jq -c 'if .status == "success" and (.data.result | length) > 0
             then (.data.result[0].value[1] | if test("^[-+]?[0-9.]+([eE][-+]?[0-9]+)?$") then tonumber else null end)
             else null end' 2>/dev/null \
    || printf 'null'
}
