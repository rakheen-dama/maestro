#!/usr/bin/env bash
# Stops every host JVM started by demo/scripts/start-services.sh, using the pid
# files in demo/.run/.
#
# Graceful by default: SIGTERM, then wait, then SIGKILL only if a JVM is still
# alive after the grace period. That ordering matters for this demo — Maestro's
# graceful shutdown path leaves parked workflows in WAITING_SIGNAL rather than
# failing them, and a demo that SIGKILLs on the way out would show the opposite.
#
# Usage:
#   demo/scripts/stop-services.sh                      # stop all
#   demo/scripts/stop-services.sh loan-application-service   # stop just one
#   GRACE=5 demo/scripts/stop-services.sh              # shorter SIGTERM grace
#
# Killing ONE service the hard way (the crash-recovery demo) is deliberately
# NOT this script's job — the runbook does it directly:
#   kill -9 "$(cat demo/.run/loan-application-service.pid)"

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEMO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RUN_DIR="$DEMO_DIR/.run"
TARGETS_DIR="$DEMO_DIR/targets"

# Maestro's default shutdown timeout (maestro.shutdown.timeout) is 30s; give
# the JVM a little more than that before escalating.
GRACE="${GRACE:-35}"

log() { printf '%s [demo] %s\n' "$(date +%H:%M:%S)" "$*"; }

stop_one() {
    local iname="$1" pidfile="$RUN_DIR/$1.pid" pid
    if [[ ! -f "$pidfile" ]]; then
        log "$iname: no pid file, nothing to stop"
        return 0
    fi
    pid="$(cat "$pidfile")"
    if [[ -z "$pid" ]] || ! kill -0 "$pid" 2>/dev/null; then
        log "$iname: pid $pid is not running (stale pid file) — removing"
        rm -f "$pidfile"
        return 0
    fi

    log "$iname: SIGTERM to pid $pid (up to ${GRACE}s grace)"
    kill -TERM "$pid" 2>/dev/null

    local waited=0
    while (( waited < GRACE )) && kill -0 "$pid" 2>/dev/null; do
        sleep 1
        waited=$((waited + 1))
    done

    if kill -0 "$pid" 2>/dev/null; then
        log "$iname: still alive after ${GRACE}s — SIGKILL to pid $pid"
        kill -9 "$pid" 2>/dev/null
        sleep 1
    else
        log "$iname: exited cleanly after ${waited}s"
    fi
    rm -f "$pidfile"
}

main() {
    if [[ ! -d "$RUN_DIR" ]]; then
        log "No $RUN_DIR directory — nothing to stop"
        return 0
    fi

    if [[ $# -gt 0 ]]; then
        local name
        for name in "$@"; do
            stop_one "$name"
        done
        return 0
    fi

    local pidfile found=0
    # Stop the admin dashboard first (it only reads), then the workflow
    # services, so nothing is mid-request against a dashboard that has gone.
    if [[ -f "$RUN_DIR/maestro-admin.pid" ]]; then
        found=1
        stop_one maestro-admin
    fi
    for pidfile in "$RUN_DIR"/*.pid; do
        [[ -f "$pidfile" ]] || continue
        found=1
        stop_one "$(basename "$pidfile" .pid)"
    done
    (( found == 0 )) && log "No pid files in $RUN_DIR — nothing to stop"

    # Drop the optional node-B Prometheus target so a later single-node run
    # does not show a DOWN target for a JVM that is no longer there.
    rm -f "$TARGETS_DIR/loan-application-b.json"

    log "Done. Infrastructure containers are untouched — tear them down with:"
    log "  docker compose -f demo/docker-compose.yml down -v"
}

main "$@"
