#!/usr/bin/env bash
# Planned node stops and starts for perf/scenarios/*.sh (WP12). Sourced by
# perf/run.sh after lib/common.sh, never run.
#
# A scenario that stops or starts a node on purpose goes through these, so
# every event lands in ${RESULTS}/topology.jsonl, one JSON line each, in the
# order it happened:
#   {at: <host epoch s>, dbNow: <database now(), RFC 3339>, action: "stop" |
#    "start", container: "maestro-perf-node-<i>", jvm: <the new JVM's
#    /perf/info + container on a start, null on a stop>, infoAt: <host epoch s
#    of that answer, start only>}
# The scenario copies topology_records into measurements.topology, and
# perf/eval/common.jq judges the run by it: validity.identity holds every
# planned JVM to the stamped commit, validity.noRestart expects each
# container's LAST recorded JVM at the end (so an unplanned restart is still
# INVALID), validity.sweeps gives every JVM its own lifetime and allows each
# planned start's startup sweep. A scenario must end with every replica up,
# so run.sh's post-run collect_nodes reaches them all.
#
# Containers are addressed by their exact name (maestro-perf-node-<i>), never
# by a pattern: only this compose project's nodes are ever stopped or started.
# shellcheck shell=bash
# SC2016: the single-quoted '$x' strings are jq filters, expanded by jq.
# shellcheck disable=SC2016

node_container() {
  printf '%s-node-%s' "${COMPOSE_PROJECT}" "$1"
}

# Host epoch seconds with milliseconds (perl's Time::HiRes; macOS date has no
# %N), or whole seconds when perl is missing.
epoch_now() {
  perl -MTime::HiRes=time -e 'printf "%.3f\n", time' 2>/dev/null || date +%s
}

# The database clock as JSON (a quoted RFC 3339 string), or null.
db_now_json() {
  dc exec -T postgres psql -U postgres -d maestro -At -c 'SELECT to_json(now())' </dev/null 2>>"${RESULTS}/harness.log" \
    | jq -c . 2>/dev/null || printf 'null'
}

# Appends one record to topology.jsonl: topology_append <action> <container> <at> <dbNow json> [<jvm json> <infoAt>]
topology_append() {
  jq -nc --arg action "$1" --arg c "$2" --argjson at "$3" --argjson db "$4" \
    --argjson jvm "${5:-null}" --argjson infoAt "${6:-null}" \
    '{at: $at, dbNow: $db, action: $action, container: $c, jvm: $jvm}
     + (if $action == "start" then {infoAt: $infoAt} else {} end)' >>"${RESULTS}/topology.jsonl"
}

# Stops node <i> gracefully (docker stop -t 40: SIGTERM, then 40 s for the
# engine's shutdown before SIGKILL) and records it once the container has
# exited.
# node_stop <i>
node_stop() {
  local c at db
  c=$(node_container "$1")
  log "topology: stop ${c}"
  docker stop -t 40 "$c" >>"${RESULTS}/harness.log" 2>&1 || die "docker stop ${c} failed"
  at=$(epoch_now)
  db=$(db_now_json)
  topology_append stop "$c" "$at" "$db"
}

# Starts node <i>, waits up to 180 s for its /perf/info and prints the new
# JVM's record (/perf/info + container). The record's at / dbNow are taken
# just BEFORE `docker start`, so a window opened before the call contains the
# new JVM's whole start.
# node_start <i>
node_start() {
  local i=$1 c at db deadline info="" info_at jvm
  c=$(node_container "$i")
  at=$(epoch_now)
  db=$(db_now_json)
  log "topology: start ${c}"
  docker start "$c" >>"${RESULTS}/harness.log" 2>&1 || die "docker start ${c} failed"
  deadline=$(($(date +%s) + 180))
  while :; do
    if info=$(http_get "$(node_url "$i")/perf/info" 2>/dev/null) && jq -e '.jvmStartMillis' <<<"$info" >/dev/null 2>&1; then
      break
    fi
    (($(date +%s) < deadline)) || die "${c} did not answer /perf/info within 180 s of docker start"
    sleep 1
  done
  info_at=$(epoch_now)
  jvm=$(jq -c --arg c "$c" '{container: $c} + .' <<<"$info")
  topology_append start "$c" "$at" "$db" "$jvm" "$info_at"
  log "topology: ${c} answered after $(jq -rn --argjson a "$at" --argjson b "$info_at" '$b - $a | . * 10 | round / 10') s (pid $(jq -r .pid <<<"$jvm"), jvmStartMillis $(jq -r .jvmStartMillis <<<"$jvm"))"
  printf '%s\n' "$jvm"
}

# Prints the host epoch (s, millisecond resolution) at which node <i>'s
# /actuator/health/readiness first answered UP, polling every second for at
# most <timeout> s; prints nothing and returns 1 on timeout.
# node_wait_ready <i> <timeout>
node_wait_ready() {
  local i=$1 timeout=$2 deadline
  deadline=$(($(date +%s) + timeout))
  while :; do
    if http_get "$(node_url "$i")/actuator/health/readiness" 2>/dev/null | grep -q '"status":"UP"'; then
      epoch_now
      return 0
    fi
    (($(date +%s) < deadline)) || return 1
    sleep 1
  done
}

# This run's topology log as a JSON array ([] when nothing was stopped or
# started).
topology_records() {
  if [[ -s "${RESULTS}/topology.jsonl" ]]; then
    jq -s -c '.' "${RESULTS}/topology.jsonl"
  else
    printf '[]'
  fi
}
