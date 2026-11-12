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
  dc run --rm --no-deps "${args[@]}" k6 run --quiet "/scripts/${script}" \
    </dev/null >"${RESULTS}/k6-${phase}.log" 2>&1 || rc=$?
  log "k6 ${phase}: exit ${rc}"
  grep '^k6\[' "${RESULTS}/k6-${phase}.log" >&2 || true
  local summary="${RESULTS}/k6-${phase}.json"
  if [[ -s "$summary" ]]; then
    jq -c --arg phase "$phase" --arg script "$script" --argjson rc "$rc" '
      def v($m; $k): (.metrics[$m].values[$k] // null);
      {phase: $phase, script: $script, exitCode: $rc,
       iterations: v("iterations"; "count"),
       droppedIterations: (v("dropped_iterations"; "count") // 0),
       httpReqs: v("http_reqs"; "count"),
       httpReqFailedRate: v("http_req_failed"; "rate"),
       checksPassed: v("checks"; "passes"),
       checksFailed: v("checks"; "fails"),
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
