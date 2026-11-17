#!/usr/bin/env bash
# Background-loop safety self-test: perf/lib/bgloop-selftest.sh
#
# A scenario's background loops (S2's probe and hoard loops, samplers, the
# S6 foreground) must never outlive the harness: an orphaned probe loop keeps
# running `docker compose run k6` against a torn-down stack and recreates its
# network. lib/common.sh therefore offers bg_register (record a pid),
# bg_kill_all (kill every recorded pid and its direct children, by pid; run.sh's
# on_exit calls it) and bg_should_continue (a loop's guard: the harness's
# PERF_MAIN_PID alive and the loop's deadline not passed). This checks, with
# no Docker:
#   1. a harness that exits (any path through its EXIT trap) leaves no
#      registered loop, nor the loop's child, running;
#   2. a harness killed with SIGKILL (no trap runs) leaves its loop to end by
#      itself within a few seconds (the parent-alive guard);
#   3. a loop past its deadline ends by itself.
# Exit 0 when every case holds, 1 otherwise.
set -Eeuo pipefail

PERF_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=$(mktemp -d "${TMPDIR:-/tmp}/perf-bgloop-selftest.XXXXXX")
trap 'rm -rf "$work"' EXIT
failures=0
expect() {
  if [[ "$3" == "$2" ]]; then printf 'ok    %-70s %s\n' "$1" "$3"; else
    printf 'FAIL  %-70s expected %s, got %s\n' "$1" "$2" "$3"; failures=$((failures + 1)); fi
}
alive() { if kill -0 "$1" 2>/dev/null; then echo alive; else echo gone; fi; }
# waits up to <secs> for pid <p> to end; prints alive/gone
gone_within() {
  local p=$1 end=$(($(date +%s) + $2))
  while kill -0 "$p" 2>/dev/null && (($(date +%s) < end)); do sleep 0.2; done
  alive "$p"
}

# A fake harness: sources common.sh, starts one guarded loop (whose body runs
# a child `sleep` and survives that child's death, as a real loop's k6 call
# would), registers it, writes the loop's pid, then either exits through its
# trap (mode trap) or blocks until killed (mode block). It exec's bash, so a
# caller that backgrounds it (`harness … &`) holds the harness's own pid.
# Called in the foreground as `(harness …)`.
harness() { # <mode> <dir> <deadline offset s>
  exec bash -c '
    set -Eeuo pipefail
    RESULTS=$2; COMPOSE_PROJECT=none; PERF_MAIN_PID=$$
    source "$1/lib/common.sh"
    trap "bg_kill_all" EXIT
    deadline=$(($(date +%s) + $4))
    ( while bg_should_continue "$deadline"; do sleep 30 || true; done ) &
    loop=$!
    bg_register "$loop"
    printf "%s\n" "$loop" >"$2/loop.pid"
    sleep 1
    printf "%s\n" "$(ps -axo pid=,ppid= | awk -v p="$loop" "\$2 == p {print \$1}" | head -1)" >"$2/child.pid"
    if [[ $3 == block ]]; then while :; do sleep 1; done; fi
  ' _ "$PERF_DIR" "$2" "$1" "$3"
}

# 1. Exit through the trap: the loop and its child are killed by pid.
mkdir "$work/t1"
(harness trap "$work/t1" 600)
expect "harness exits: the registered loop is gone" gone "$(gone_within "$(cat "$work/t1/loop.pid")" 3)"
child=$(cat "$work/t1/child.pid")
expect "harness exits: the loop's child (its sleep) is gone" gone "$([[ -n "$child" ]] && gone_within "$child" 3 || echo no-child)"

# 2. SIGKILL: no trap runs; the loop notices its harness is gone.
mkdir "$work/t2"
harness block "$work/t2" 600 &
h=$!
for _ in $(seq 1 50); do [[ -s "$work/t2/child.pid" ]] && break; sleep 0.1; done
loop=$(cat "$work/t2/loop.pid")
kill -9 "$h" 2>/dev/null || true
wait "$h" 2>/dev/null || true
expect "harness SIGKILLed: the loop was still running a moment before" alive "$(alive "$loop")"
# The loop's guard runs between iterations; kill its sleeping child so the
# next check comes now (a real loop's iteration is bounded: a 30 s probe).
child=$(cat "$work/t2/child.pid")
[[ -z "$child" ]] || kill "$child" 2>/dev/null || true
expect "harness SIGKILLed: the loop ends by itself (parent-alive guard)" gone "$(gone_within "$loop" 5)"

# 3. Deadline passed: the loop ends by itself.
mkdir "$work/t3"
harness block "$work/t3" 1 &
h=$!
for _ in $(seq 1 50); do [[ -s "$work/t3/child.pid" ]] && break; sleep 0.1; done
loop=$(cat "$work/t3/loop.pid")
child=$(cat "$work/t3/child.pid")
[[ -z "$child" ]] || kill "$child" 2>/dev/null || true
expect "loop past its deadline ends by itself" gone "$(gone_within "$loop" 5)"
expect "loop past its deadline: the harness was still alive (not the guard's other arm)" alive "$(alive "$h")"
kill "$h" 2>/dev/null || true
wait "$h" 2>/dev/null || true

if ((failures > 0)); then
  echo "bgloop-selftest: ${failures} case(s) failed"
  exit 1
fi
echo "bgloop-selftest: all cases hold"
