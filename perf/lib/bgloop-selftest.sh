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

# A harness shaped like run.sh: EXIT trap -> bg_kill_all, INT/TERM -> exit
# 130, and a phase run as `x=$(phase)` (S2's s2_phase) that starts a guarded
# loop and then dies (mode die) or blocks (mode block). <starter> is how the
# phase starts its loop: "bg_start" (the helper) or "plain" (a bare `&`, whose
# stdout is the command substitution's pipe).
phase_harness() { # <starter> <mode> <dir> <loop deadline offset s>
  exec bash -c '
    set -Eeuo pipefail
    RESULTS=$2; COMPOSE_PROJECT=none; PERF_MAIN_PID=$$
    : >"$RESULTS/harness.log"
    source "$1/lib/common.sh"
    trap "bg_kill_all" EXIT
    trap "exit 130" INT TERM
    loop_body() { while bg_should_continue "$1"; do sleep 1 || true; done; }
    phase() {
      local deadline=$(($(date +%s) + $5))
      if [[ $3 == bg_start ]]; then
        bg_start loop_body "$deadline"
        printf "%s\n" "$BG_LAST_PID" >"$RESULTS/loop.pid"
      else
        loop_body "$deadline" &
        bg_register "$!"
        printf "%s\n" "$!" >"$RESULTS/loop.pid"
      fi
      if [[ $4 == die ]]; then die "phase failed"; fi
      while :; do sleep 1; done
    }
    x=$(phase "$@")
  ' _ "$PERF_DIR" "$3" "$1" "$2" "$4"
}
elapsed_of() { # <start epoch> : seconds since
  echo $(($(date +%s) - $1))
}

# 4. The hazard (control): a bare `&` inside $(...) keeps the substitution's
#    pipe open, so after a die the harness waits for the loop's own end.
mkdir "$work/t4"
t=$(date +%s)
(phase_harness plain die "$work/t4" 4) 2>/dev/null || true
e=$(elapsed_of "$t")
expect "control: a bare & inside \$(...) then die: the harness waited for the loop (>= 3 s)" yes "$( ((e >= 3)) && echo yes || echo "no (${e} s)")"

# 5. bg_start inside $(...) then die: the harness exits at once, the loop is killed.
mkdir "$work/t5"
t=$(date +%s)
(phase_harness bg_start die "$work/t5" 60) 2>/dev/null || true
e=$(elapsed_of "$t")
expect "bg_start inside \$(...) then die: the loop had started" yes "$([[ -s "$work/t5/loop.pid" ]] && echo yes || echo no)"
expect "bg_start inside \$(...) then die: the harness exits within 3 s" yes "$( ((e <= 3)) && echo yes || echo "no (${e} s)")"
expect "bg_start inside \$(...) then die: the loop is gone" gone "$(gone_within "$(cat "$work/t5/loop.pid")" 3)"

# 6. An interrupt (SIGINT to the whole process group, as a terminal's Ctrl-C)
#    while the phase blocks: the harness exits within a few seconds.
mkdir "$work/t6"
set -m
phase_harness bg_start block "$work/t6" 60 2>/dev/null &
h=$!
set +m
for _ in $(seq 1 50); do [[ -s "$work/t6/loop.pid" ]] && break; sleep 0.1; done
t=$(date +%s)
expect "interrupt during a phase: the loop had started" yes "$([[ -s "$work/t6/loop.pid" ]] && echo yes || echo no)"
kill -INT -- "-$h" 2>/dev/null || true
expect "interrupt during a phase: the harness exits within 3 s" gone "$(gone_within "$h" 3)"
wait "$h" 2>/dev/null || true
expect "interrupt during a phase: the loop is gone" gone "$(gone_within "$(cat "$work/t6/loop.pid")" 3)"

# 7. A pid waited for leaves the registry; a registry entry whose process no
#    longer has the recorded start time (a reused pid) is never killed.
mkdir "$work/t7"
out=$(bash -c '
  set -Eeuo pipefail
  RESULTS=$2; COMPOSE_PROJECT=none; PERF_MAIN_PID=$$
  source "$1/lib/common.sh"
  sleep 0.2 & p=$!
  bg_register "$p"
  bg_wait "$p"
  if grep -q "^$p|" "$RESULTS/.bg-pids" 2>/dev/null; then echo kept; else echo forgotten; fi
' _ "$PERF_DIR" "$work/t7" 2>&1 || true)
expect "a pid waited for with bg_wait leaves the registry" forgotten "$out"
# bg_wait <pid> <var> hands the waited process's exit status back (S2
# records its collectors' statuses: a loop that stopped early is INVALID).
mkdir "$work/t7b"
out=$(bash -c '
  set -Eeuo pipefail
  RESULTS=$2; COMPOSE_PROJECT=none; PERF_MAIN_PID=$$
  source "$1/lib/common.sh"
  ( sleep 0.2; exit 3 ) & p=$!
  bg_register "$p"
  bg_wait "$p" rc
  sleep 0.2 & q=$!
  bg_register "$q"
  bg_wait "$q" rc2
  printf "%s,%s\n" "${rc:-unset}" "${rc2:-unset}"
' _ "$PERF_DIR" "$work/t7b" 2>&1 || true)
expect "bg_wait <pid> <var>: the exit status of a failed and a clean process" "3,0" "$out"
mkdir "$work/t8"
sleep 30 &
stranger=$!
bash -c '
  set -Eeuo pipefail
  RESULTS=$2; COMPOSE_PROJECT=none; PERF_MAIN_PID=$$
  source "$1/lib/common.sh"
  bg_register "$3"
  # as if the registered process had ended and its pid been reused: the
  # recorded start time no longer matches the live process
  sed -i.bak "s/^\($3\)|[^|]*|/\1|Thu_Jan__1_00:00:00_1970|/" "$RESULTS/.bg-pids"
  bg_kill_all
' _ "$PERF_DIR" "$work/t8" "$stranger" 2>/dev/null || true
expect "a registered pid whose start time does not match is not killed" alive "$(alive "$stranger")"
kill "$stranger" 2>/dev/null || true
wait "$stranger" 2>/dev/null || true

# 8. Cleanup must not let a loop replace the child it just lost: a loop
#    that starts one child after another (an S2 probe loop runs one k6 after
#    another) and logs each child's pid. When the harness exits through its
#    trap, no child the loop ever started may survive — including one it
#    started after its first child was killed.
mkdir "$work/t9"
bash -c '
  set -Eeuo pipefail
  RESULTS=$2; COMPOSE_PROJECT=none; PERF_MAIN_PID=$$
  : >"$RESULTS/harness.log"
  source "$1/lib/common.sh"
  trap "bg_kill_all" EXIT
  spawner() {
    # It outlives its own SIGTERM, which makes the race deterministic: a
    # cleanup that only kills the current child and then the loop would let
    # it start another child; the stop flag (bg_should_continue) does not.
    trap ":" TERM
    local deadline=$(($(date +%s) + 600))
    while bg_should_continue "$deadline"; do
      sleep 30 &
      printf "%s\n" "$!" >>"$RESULTS/children.log"
      wait "$!" || true
    done
  }
  bg_start spawner
  for _ in $(seq 1 50); do [[ -s "$RESULTS/children.log" ]] && break; sleep 0.1; done
  sleep 0.5
' _ "$PERF_DIR" "$work/t9" 2>/dev/null || true
sleep 1.5
left=0
while IFS= read -r c; do
  if kill -0 "$c" 2>/dev/null; then left=$((left + 1)); kill "$c" 2>/dev/null || true; fi
done <"$work/t9/children.log"
expect "cleanup: no child the loop started survives, a replacement included" 0 "$left"
expect "cleanup: the loop started no replacement child" 1 "$(wc -l <"$work/t9/children.log" | tr -d ' ')"

if ((failures > 0)); then
  echo "bgloop-selftest: ${failures} case(s) failed"
  exit 1
fi
echo "bgloop-selftest: all cases hold"
