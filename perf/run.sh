#!/usr/bin/env bash
# Maestro k6 load harness driver.
#
# Usage: perf/run.sh <smoke|s1-throughput|s3-parked> [--profile local|full]
#                    [--replicas N] [--record-baseline]
#
# Env:  PERF_ADMIN_EVENTS (true|false, default true)  PERF_KEEP_UP=1 (skip the
#       final down -v)  PERF_ENGINE_REF (free-text label)  PERF_NODE_HEAP /
#       PERF_NODE_CPUS / PERF_NODE_MEM (node container sizing)
#       Test-only overrides: PERF_EXPECT_COMMIT, PERF_K6_VUS, PERF_K6_MAX_VUS.
#
# Every run is cold (down -v first), builds and stamps its own jar, proves
# each node runs that jar, and writes a self-identifying directory
# perf/results/<runId>/ (identity.json, measurements.json, result.json,
# summary.md, prometheus.json, compose.log, …).
#
# One run per host at a time: the compose project (maestro-perf) and the
# Prometheus port (59090) are fixed, so a run takes the host-wide lock
# /tmp/maestro-perf.lock and refuses to start, touching nothing, while the lock
# is held or the project has any container (e.g. one left by PERF_KEEP_UP=1).
#
# Statement counts are reported GROSS (every call of the engine's Postgres
# role in the load window, which includes background engine statements: the
# timer poller's leader election and due-timer query, the recovery poll, and
# the lock cleaner / signal janitor / retention sweeps when due) and NET (the
# background rate, measured on the idle stack before the load, subtracted).
# Gates use the net figure; see perf/eval/common.jq.
#
# Exit: 0 PASS, 1 FAIL, 2 INVALID, 3 harness error (including an evaluator
# ERROR verdict: a check with an unknown class or status).
# The last stdout line is always: VERDICT <PASS|FAIL|INVALID|ERROR> <resultsDir>

# SC2016: the single-quoted '$x' strings are jq filters, expanded by jq.
# shellcheck disable=SC2016
set -Eeuo pipefail

PERF_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "${PERF_DIR}/.." && pwd)
COMPOSE_PROJECT=maestro-perf
# Host-wide, not per worktree or per TMPDIR: the compose project and the
# Prometheus host port it guards are host-wide.
LOCK_DIR=/tmp/maestro-perf.lock
LOCK_HELD=0
RESULTS=""
FINAL_VERDICT=""
FINAL_RC=3
STACK_TOUCHED=0

# shellcheck source=lib/common.sh
source "${PERF_DIR}/lib/common.sh"

usage() {
  sed -n '4,6p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
}

# on_err and on_exit run from traps (SC2317: invoked indirectly).
# shellcheck disable=SC2317
on_err() {
  log "command failed (rc=$1) at line $2: $3"
}

# shellcheck disable=SC2317
on_exit() {
  local rc=$?
  set +e
  trap - EXIT ERR
  if ((STACK_TOUCHED)) && [[ -n "$RESULTS" ]]; then
    dc logs --no-color --timestamps >"${RESULTS}/compose.log" 2>&1
    if [[ "${PERF_KEEP_UP:-0}" == "1" ]]; then
      log "PERF_KEEP_UP=1: stack left up (docker compose -p ${COMPOSE_PROJECT} -f perf/compose.yml down -v)"
    else
      log "teardown: down -v"
      dc down -v --remove-orphans >>"${RESULTS}/harness.log" 2>&1
    fi
  fi
  if ((LOCK_HELD)); then
    rm -rf "$LOCK_DIR"
  fi
  if [[ -z "$FINAL_VERDICT" ]]; then
    log "harness error (exit ${rc})"
    FINAL_VERDICT=ERROR
    FINAL_RC=3
  fi
  printf 'VERDICT %s %s\n' "$FINAL_VERDICT" "${RESULTS:-none}"
  exit "$FINAL_RC"
}

trap 'on_err $? $LINENO "$BASH_COMMAND"' ERR
trap on_exit EXIT
trap 'exit 130' INT TERM

# ── Arguments ──────────────────────────────────────────────────────────────
SCENARIO="${1:-}"
[[ -n "$SCENARIO" && "$SCENARIO" != -* ]] || { usage; die "missing scenario"; }
shift
PROFILE=local
REPLICAS=1
RECORD_BASELINE=0
while (($#)); do
  case "$1" in
    --profile) PROFILE="${2:-}"; shift 2 ;;
    --replicas) REPLICAS="${2:-}"; shift 2 ;;
    --record-baseline) RECORD_BASELINE=1; shift ;;
    *) usage; die "unknown argument: $1" ;;
  esac
done
case "$SCENARIO" in smoke | s1-throughput | s3-parked) ;; *) usage; die "unknown scenario: $SCENARIO" ;; esac
case "$PROFILE" in local | full) ;; *) die "--profile must be local or full, got '$PROFILE'" ;; esac
[[ "$REPLICAS" =~ ^[1-9][0-9]*$ ]] || die "--replicas must be a positive integer, got '$REPLICAS'"
PERF_ADMIN_EVENTS="${PERF_ADMIN_EVENTS:-true}"
case "$PERF_ADMIN_EVENTS" in true | false) ;; *) die "PERF_ADMIN_EVENTS must be true or false, got '$PERF_ADMIN_EVENTS'" ;; esac
command -v jq >/dev/null 2>&1 || die "jq is required (it writes identity.json)"

cd "$ROOT"

# ── 1. Identity, before anything else ──────────────────────────────────────
RUN_ID="r$(date -u +%Y%m%dt%H%M%Sz)"
RESULTS="${PERF_DIR}/results/${RUN_ID}"
mkdir -p "${PERF_DIR}/results"
mkdir "$RESULTS" || { RESULTS=""; die "results directory for ${RUN_ID} already exists"; }

HEAD=$(git rev-parse HEAD)
DIRTY=false
[[ -z "$(git status --porcelain -- . ':!perf/results')" ]] || DIRTY=true
SHALLOW=$(git rev-parse --is-shallow-repository)
ENGINE_COMMIT=$(git log -1 --format=%H -- maestro-core maestro-spring-boot-starter maestro-store-jdbc \
  maestro-store-postgres maestro-messaging-kafka)
HARNESS_COMMIT=$(git log -1 --format=%H -- perf maestro-samples/sample-perf)
if [[ "$(uname -s)" == Darwin ]]; then
  HOST_MEM_MB=$(($(sysctl -n hw.memsize) / 1048576))
else
  HOST_MEM_MB=$(($(awk '/^MemTotal:/ {print $2}' /proc/meminfo) / 1024))
fi
DOCKER_SERVER=$(docker version --format '{{.Server.Version}}' 2>/dev/null || echo unavailable)
DOCKER_COMPOSE=$(docker compose version --short 2>/dev/null || echo unavailable)
DOCKER_MEM_MB=$(($(docker info --format '{{.MemTotal}}' 2>/dev/null || echo 0) / 1048576))
IMAGES=$(awk '$1 == "image:" {print $2}' "${PERF_DIR}/compose.yml" | sort -u | jq -R . | jq -sc .)

jq -n \
  --arg runId "$RUN_ID" --arg scenario "$SCENARIO" --arg profile "$PROFILE" \
  --arg startedAtUtc "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --arg pwd "$PWD" \
  --arg head "$HEAD" --argjson dirty "$DIRTY" --argjson shallow "$SHALLOW" \
  --arg engineCommit "$ENGINE_COMMIT" --arg harnessCommit "$HARNESS_COMMIT" \
  --arg engineRefLabel "${PERF_ENGINE_REF:-}" \
  --arg uname "$(uname -a)" --argjson cpus "$(getconf _NPROCESSORS_ONLN)" --argjson memoryMb "$HOST_MEM_MB" \
  --arg dockerServer "$DOCKER_SERVER" --arg dockerCompose "$DOCKER_COMPOSE" --argjson dockerMemoryMb "$DOCKER_MEM_MB" \
  --argjson images "$IMAGES" \
  --argjson replicas "$REPLICAS" --argjson adminEventsRequested "$PERF_ADMIN_EVENTS" \
  --arg nodeHeap "${PERF_NODE_HEAP:-1g}" --arg nodeCpus "${PERF_NODE_CPUS:-2}" --arg nodeMem "${PERF_NODE_MEM:-1536m}" \
  '{runId: $runId, scenario: $scenario, profile: $profile, startedAtUtc: $startedAtUtc, pwd: $pwd,
    repo: {head: $head, dirty: $dirty, shallow: $shallow, engineCommit: $engineCommit,
           harnessCommit: $harnessCommit, engineRefLabel: $engineRefLabel},
    host: {uname: $uname, cpus: $cpus, memoryMb: $memoryMb, dockerServer: $dockerServer,
           dockerCompose: $dockerCompose, dockerMemoryMb: $dockerMemoryMb},
    images: $images,
    config: {replicas: $replicas, adminEventsRequested: $adminEventsRequested,
             nodeHeap: $nodeHeap, nodeCpus: $nodeCpus, nodeMem: $nodeMem},
    notes: []}' >"${RESULTS}/identity.json"
{
  printf '# perf/run.sh %s\n' "$RUN_ID"
  printf '# pwd=%s head=%s dirty=%s started=%s\n' "$PWD" "$HEAD" "$DIRTY" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf '# args: %s --profile %s --replicas %s record-baseline=%s admin-events=%s\n' \
    "$SCENARIO" "$PROFILE" "$REPLICAS" "$RECORD_BASELINE" "$PERF_ADMIN_EVENTS"
} >"${RESULTS}/harness.log"
log "run ${RUN_ID}: ${SCENARIO} profile=${PROFILE} replicas=${REPLICAS} → ${RESULTS}"

# ── 2. Preflight ───────────────────────────────────────────────────────────
command -v docker >/dev/null 2>&1 || die "docker is required"
[[ "$DOCKER_SERVER" != unavailable ]] || die "the Docker daemon is not reachable"
if ! [[ "$DOCKER_COMPOSE" =~ ^v?([0-9]+)\. ]] || ((BASH_REMATCH[1] < 2)); then
  die "docker compose v2 is required, got '${DOCKER_COMPOSE}'"
fi
command -v unzip >/dev/null 2>&1 || die "unzip is required (jar provenance check)"
[[ -f "${PERF_DIR}/scenarios/${SCENARIO}.sh" && -f "${PERF_DIR}/eval/${SCENARIO}.jq" ]] \
  || die "scenario ${SCENARIO} is not implemented (perf/scenarios/${SCENARIO}.sh, perf/eval/${SCENARIO}.jq)"
if ((DOCKER_MEM_MB < 6144)); then
  identity_note "WARN: Docker has ${DOCKER_MEM_MB} MiB of memory, below the 6 GiB the suite is sized for"
  log "WARN: Docker memory ${DOCKER_MEM_MB} MiB < 6 GiB"
fi
# Dirtiness is known now: refuse before spending a build and a run on it.
# (The "run did not PASS" refusal can only come after the run.)
if ((RECORD_BASELINE)) && [[ "$DIRTY" != false ]]; then
  die "--record-baseline refused: the working tree is dirty (commit first)"
fi

# One run per host. mkdir is atomic: exactly one concurrent run gets the lock.
# A lock whose pid is no longer running is stale and taken over; a lock
# without a pid file is treated as held (its owner may be between mkdir and
# writing the pid).
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  holder=$(cat "${LOCK_DIR}/pid" 2>/dev/null || true)
  if [[ -z "$holder" ]] || ps -p "$holder" >/dev/null 2>&1; then
    die "another perf run holds ${LOCK_DIR} (pid ${holder:-unknown}: $(cat "${LOCK_DIR}/owner" 2>/dev/null || echo '?')); refusing to start, nothing touched"
  fi
  log "stale lock ${LOCK_DIR} (pid ${holder} is not running): taking it over"
  rm -rf "$LOCK_DIR"
  mkdir "$LOCK_DIR" 2>/dev/null || die "could not take ${LOCK_DIR}; refusing to start, nothing touched"
fi
LOCK_HELD=1
printf '%s\n' "$$" >"${LOCK_DIR}/pid"
printf '%s %s\n' "$RUN_ID" "$PWD" >"${LOCK_DIR}/owner"
# A stack the lock does not cover (left up by PERF_KEEP_UP=1, or started by
# hand) belongs to someone else's evidence: never tear it down from here.
existing=$(docker ps -aq --filter "label=com.docker.compose.project=${COMPOSE_PROJECT}" | wc -l | tr -d ' ')
if ((existing > 0)); then
  die "compose project ${COMPOSE_PROJECT} already has ${existing} container(s) (another run, or one left up by PERF_KEEP_UP=1); refusing to start, nothing touched. If it is yours: docker compose -p ${COMPOSE_PROJECT} -f perf/compose.yml --profile tools down -v"
fi
export PERF_RESULTS_DIR="$RESULTS" PERF_ADMIN_EVENTS
PERF_UID=$(id -u)
PERF_GID=$(id -g)
export PERF_UID PERF_GID

# ── 3. Cold ────────────────────────────────────────────────────────────────
STACK_TOUCHED=1
log "cold: down -v"
dc down -v --remove-orphans >>"${RESULTS}/harness.log" 2>&1

# ── 4. Build and stamp the jar ─────────────────────────────────────────────
BAKED_COMMIT="${HEAD}"
[[ "$DIRTY" == false ]] || BAKED_COMMIT="${HEAD}+dirty"
EXPECTED_COMMIT="${PERF_EXPECT_COMMIT:-$BAKED_COMMIT}"
JAR="${ROOT}/maestro-samples/sample-perf/build/libs/sample-perf.jar"
log "build: bootJar -PperfGitCommit=${BAKED_COMMIT} (log: build.log)"
./gradlew --no-watch-fs :maestro-samples:sample-perf:bootJar "-PperfGitCommit=${BAKED_COMMIT}" \
  >"${RESULTS}/build.log" 2>&1 || die "bootJar failed (see build.log)"
# Spring Boot 4's bootJar puts the build info at the jar root (META-INF/).
JAR_COMMIT=$(unzip -p "$JAR" META-INF/build-info.properties | sed -n 's/^build\.gitCommit=//p')
[[ "$JAR_COMMIT" == "$BAKED_COMMIT" ]] \
  || die "jar provenance: build-info carries '${JAR_COMMIT}', expected '${BAKED_COMMIT}'"
if command -v sha256sum >/dev/null 2>&1; then
  JAR_SHA=$(sha256sum "$JAR" | awk '{print $1}')
else
  JAR_SHA=$(shasum -a 256 "$JAR" | awk '{print $1}')
fi
identity_set '.build = {bakedCommit: $baked, expectedCommit: $expected, jarCommit: $jarCommit, jarSha256: $sha}' \
  --arg baked "$BAKED_COMMIT" --arg expected "$EXPECTED_COMMIT" --arg jarCommit "$JAR_COMMIT" --arg sha "$JAR_SHA"
log "build: jar stamped ${JAR_COMMIT} (sha256 ${JAR_SHA:0:12}); expecting nodes to report ${EXPECTED_COMMIT}"

# ── 5. Up, and wait for every replica ──────────────────────────────────────
log "up: ${REPLICAS} node replica(s)"
dc up -d --scale "node=${REPLICAS}" postgres kafka kafka-init node prometheus curl \
  >>"${RESULTS}/harness.log" 2>&1 || die "compose up failed (see harness.log)"
STACK_UP_EPOCH=$(date +%s)
DIGESTS=$(dc config --images | sort -u | while read -r img; do
  docker image inspect --format '{{index .RepoDigests 0}}' "$img" 2>/dev/null || echo "$img@unknown"
done | jq -R . | jq -sc .)
identity_set '.imageDigests = $d' --argjson d "$DIGESTS"
# Each container's CPU and memory limit as Docker applies it ("none" = unlimited).
# shellcheck disable=SC2046 # one argument per container id
CONTAINERS=$(docker inspect $(dc ps -aq) | jq -c 'map({
    name: (.Name | ltrimstr("/")),
    service: .Config.Labels["com.docker.compose.service"],
    image: .Config.Image,
    cpus: (if (.HostConfig.NanoCpus // 0) > 0 then .HostConfig.NanoCpus / 1e9
           elif (.HostConfig.CpuQuota // 0) > 0 then .HostConfig.CpuQuota / .HostConfig.CpuPeriod
           else "none" end),
    memoryBytes: (if (.HostConfig.Memory // 0) > 0 then .HostConfig.Memory else "none" end)})
  | sort_by(.name)')
identity_set '.containers = $c' --argjson c "$CONTAINERS"
running=$(dc ps -q node | wc -l | tr -d ' ')
((running == REPLICAS)) || die "expected ${REPLICAS} node container(s), found ${running}"

deadline=$((STACK_UP_EPOCH + 180))
while :; do
  healthy=0
  for ((i = 1; i <= REPLICAS; i++)); do
    if http_get "$(node_url "$i")/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      healthy=$((healthy + 1))
    fi
  done
  ((healthy == REPLICAS)) && break
  (($(date +%s) < deadline)) || die "only ${healthy}/${REPLICAS} node(s) reported UP within 180 s"
  sleep 3
done
log "up: ${healthy}/${REPLICAS} node(s) UP after $(($(date +%s) - STACK_UP_EPOCH)) s"

# Prometheus must have scraped every replica before the scenario starts, or a
# short run's window holds no samples. Its data is advisory, so a miss is a
# WARN note, not a harness error.
prom_targets_up() {
  http_get http://prometheus:9090/api/v1/query \
    -G --data-urlencode 'query=count(up{job="maestro-perf-node"} == 1)' 2>/dev/null \
    | jq -r '.data.result[0].value[1] // "0"'
}
prom_deadline=$(($(date +%s) + 60))
while [[ "$(prom_targets_up)" != "$REPLICAS" ]]; do
  if (($(date +%s) >= prom_deadline)); then
    identity_note "WARN: Prometheus did not report ${REPLICAS} healthy target(s) within 60 s; prometheus.json may be sparse"
    log "WARN: Prometheus targets not all up after 60 s"
    break
  fi
  sleep 2
done
log "prometheus: $(prom_targets_up)/${REPLICAS} target(s) up"

# ── 6. Which JVMs, running which jar ───────────────────────────────────────
NODES=$(collect_nodes) || die "could not collect /perf/info"
identity_set '.nodes = $n' --argjson n "$NODES"
log "nodes: $(jq -r 'map("\(.container) host=\(.hostname) pid=\(.pid) commit=\(.buildCommit)") | join("; ")' <<<"$NODES")"

# ── 7. The scenario ────────────────────────────────────────────────────────
# shellcheck source=/dev/null
source "${PERF_DIR}/scenarios/${SCENARIO}.sh"
log "scenario ${SCENARIO}: start"
scenario_run
[[ -s "${RESULTS}/measurements.json" ]] || die "scenario ${SCENARIO} wrote no measurements.json"
log "scenario ${SCENARIO}: done"

# ── 8. Post-collection ─────────────────────────────────────────────────────
NODES_AFTER=$(collect_nodes) || die "could not re-collect /perf/info"
identity_set '.nodesAfter = $n' --argjson n "$NODES_AFTER"

admin_topic_end_offset() {
  local out
  if out=$(dc exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 \
    --topic maestro.admin.events </dev/null 2>>"${RESULTS}/harness.log") && grep -q ':' <<<"$out"; then
    :
  elif out=$(dc exec -T kafka /opt/kafka/bin/kafka-run-class.sh org.apache.kafka.tools.GetOffsetShell \
    --bootstrap-server localhost:9092 --topic maestro.admin.events </dev/null 2>>"${RESULTS}/harness.log") \
    && grep -q ':' <<<"$out"; then
    :
  else
    printf 'null'
    return 0
  fi
  log "admin topic offsets: $(tr '\n' ' ' <<<"$out")"
  awk -F: 'NF >= 3 {s += $NF} END {print s + 0}' <<<"$out"
}
ADMIN_OFFSET=$(admin_topic_end_offset)
identity_set '.effective = (.nodes[0] as $n | {
    adminEventsEffective: $n.adminEventsEnabled,
    adminEventsEffectiveByNode: [.nodes[].adminEventsEnabled],
    adminTopicEndOffset: $offset,
    hikariMaximumPoolSize: $n.hikariMaximumPoolSize,
    workerDefaultConcurrency: $n.workerDefaultConcurrency,
    workerDefaultActivityConcurrency: $n.workerDefaultActivityConcurrency,
    wakeRecheckInterval: $n.wakeRecheckInterval,
    recoveryPollInterval: $n.recoveryPollInterval,
    timerPollInterval: ($n.timerPollInterval // null)})' --argjson offset "$ADMIN_OFFSET"
log "effective: $(jq -c '.effective | {adminEventsEffective, adminTopicEndOffset, hikariMaximumPoolSize}' "${RESULTS}/identity.json")"

prom_collect() {
  local start=$1 end=$2 n=0 q dir="${RESULTS}/.prometheus"
  local files=()
  mkdir -p "$dir"
  while IFS= read -r q || [[ -n "$q" ]]; do
    [[ -n "${q// /}" && "$q" != \#* ]] || continue
    n=$((n + 1))
    dc exec -T curl curl -fsS -m 30 -G http://prometheus:9090/api/v1/query_range \
      --data-urlencode "query=${q}" --data-urlencode "start=${start}" --data-urlencode "end=${end}" \
      --data-urlencode step=5 </dev/null >"${dir}/${n}.response" 2>>"${RESULTS}/harness.log" \
      || printf '{"status":"error","error":"query_range request failed"}' >"${dir}/${n}.response"
    jq -c --arg q "$q" '{query: $q, status: .status, error: (.error // null), result: (.data.result // [])}' \
      "${dir}/${n}.response" >"${dir}/${n}.json"
    files+=("${dir}/${n}.json")
  done <"${PERF_DIR}/prometheus/queries.txt"
  jq -s --argjson s "$start" --argjson e "$end" \
    '{window: {start: $s, end: $e, stepSecs: 5}, queries: ., emptyQueries: [.[] | select((.result | length) == 0) | .query]}' \
    "${files[@]}" >"${RESULTS}/prometheus.json"
  rm -rf "$dir"
}
# One more scrape interval (5 s), so the run's final state is in the TSDB.
sleep 6
prom_collect "$STACK_UP_EPOCH" "$(date +%s)"
log "prometheus: $(jq -r '"\(.queries | length) queries, empty: \(.emptyQueries | length)"' "${RESULTS}/prometheus.json")"
http_get "$(node_url 1)/actuator/prometheus" >"${RESULTS}/node-1-scrape.txt" || log "WARN: node-1 scrape failed"
psql_json pg-settings | jq . >"${RESULTS}/pg-settings.json"
identity_set '.postgres = $p' --argjson p "$(jq -c . "${RESULTS}/pg-settings.json")"

# ── 9. Evaluate ────────────────────────────────────────────────────────────
BASELINE="${PERF_DIR}/baselines/${SCENARIO}.json"
bash "${PERF_DIR}/lib/evaluate.sh" "$RESULTS" "$SCENARIO" "$BASELINE"
VERDICT=$(jq -r .verdict "${RESULTS}/result.json")
case "$VERDICT" in
  PASS) rc=0 ;;
  FAIL) rc=1 ;;
  INVALID) rc=2 ;;
  ERROR) die "the evaluator returned ERROR: a check has an unknown class or status (see result.json)" ;;
  *) die "result.json has no verdict" ;;
esac

# ── 10. Baseline (a dirty tree was already refused in preflight) ───────────
if ((RECORD_BASELINE)); then
  if [[ "$VERDICT" != PASS ]]; then
    die "--record-baseline refused: verdict is ${VERDICT}, not PASS"
  fi
  mkdir -p "${PERF_DIR}/baselines"
  jq '{schema: 1, scenario, profile, runId: .identity.runId,
       recordedAtUtc: .identity.startedAtUtc,
       engineCommit: .identity.repo.engineCommit, harnessCommit: .identity.repo.harnessCommit,
       replicas: .identity.config.replicas} + (.measurements.baselineCandidate // {})' \
    "${RESULTS}/result.json" >"$BASELINE"
  log "baseline recorded: ${BASELINE}"
fi

FINAL_VERDICT="$VERDICT"
FINAL_RC=$rc
exit "$rc"
