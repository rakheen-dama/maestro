#!/usr/bin/env bash
# Run-lock self-test: perf/lib/lock-selftest.sh [iterations] [contenders]
#
# Races <contenders> shells (default 8), released at the same instant, for
# take_lock (lib/common.sh) on a scratch lock directory, <iterations> times
# (default 30) for each starting state: a free lock, a stale lock naming a dead
# pid, and a stale pid-less lock older than a minute; plus a forced
# interleaving on the pid-less stale lock ("stale-no-pid-interleaved"):
# contender 1 pauses 0.4 s between acquiring the lock and writing its pid
# (PERF_LOCK_TEST_PAUSE_BEFORE_PID) while every other contender, having
# already judged the OLD lock stale, pauses 0.15 s before trying the takeover
# mutex (PERF_LOCK_TEST_PAUSE_BEFORE_TAKEOVER) - so they try it exactly while
# contender 1's new lock has no pid. The hooks act only with
# PERF_LOCK_SELFTEST=1, which this test sets for its contenders. Every race
# must have exactly one winner, and the lock must name that winner's pid.
# Then the hooks themselves: set WITHOUT PERF_LOCK_SELFTEST=1 (non-numeric or
# a 5 s pause) they change nothing; with it, a value that is not a
# non-negative number makes take_lock refuse with nothing touched. Each such
# case runs take_lock under set -Eeuo pipefail, as perf/run.sh does. No Docker.
# Exit 0 when every case holds, 1 otherwise.
# SC2016: the single-quoted bash -c scripts expand their own "$1"…, not ours.
# shellcheck disable=SC2016
set -Eeuo pipefail

PERF_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
iterations=${1:-30}
contenders=${2:-8}
work=$(mktemp -d "${TMPDIR:-/tmp}/perf-lock-selftest.XXXXXX")
trap 'rm -rf "$work"' EXIT
dead=$(sh -c 'echo $$')
if ps -p "$dead" >/dev/null 2>&1; then
  echo "lock-selftest: pid ${dead} is unexpectedly alive" >&2
  exit 1
fi
failures=0

for state in free stale-pid stale-no-pid stale-no-pid-interleaved; do
  bad=0
  for ((it = 1; it <= iterations; it++)); do
    lock="${work}/lock"
    rm -rf "$lock" "${lock}.takeover" "${work}/go" "${work}/release"
    : >"${work}/wins"
    : >"${work}/decided"
    case "$state" in
      stale-pid) mkdir "$lock" && printf '%s\n' "$dead" >"${lock}/pid" ;;
      stale-no-pid | stale-no-pid-interleaved) mkdir "$lock" && touch -t 200001010000 "$lock" ;;
    esac
    pids=()
    for ((c = 1; c <= contenders; c++)); do
      pause_pid="" pause_takeover=""
      if [[ "$state" == stale-no-pid-interleaved ]]; then
        if ((c == 1)); then pause_pid=0.4; else pause_takeover=0.15; fi
      fi
      # A winner keeps running (holding the lock) until every contender has
      # decided, as a real run holds it for its whole life; a loser exits.
      PERF_LOCK_SELFTEST=1 PERF_LOCK_TEST_PAUSE_BEFORE_PID="$pause_pid" PERF_LOCK_TEST_PAUSE_BEFORE_TAKEOVER="$pause_takeover" bash -c '
        source "$1/lib/common.sh"
        while [[ ! -e "$2/go" ]]; do :; done
        if (take_lock "$3" "contender $4" 2>/dev/null); then
          printf "%s\n" "$$" >>"$2/wins"
          printf "win\n" >>"$2/decided"
          while [[ ! -e "$2/release" ]]; do sleep 0.05; done
        else
          printf "lose\n" >>"$2/decided"
        fi' \
        _ "$PERF_DIR" "$work" "$lock" "$c" &
      pids+=("$!")
    done
    sleep 0.3
    touch "${work}/go"
    while (($(wc -l <"${work}/decided") < contenders)); do sleep 0.05; done
    touch "${work}/release"
    for p in "${pids[@]}"; do wait "$p" || true; done
    wins=$(wc -l <"${work}/wins" | tr -d ' ')
    holder=$(cat "${lock}/pid" 2>/dev/null || true)
    if [[ "$wins" != 1 || "$holder" != "$(cat "${work}/wins")" || -e "${lock}.takeover" ]]; then
      bad=$((bad + 1))
      echo "  ${state} race ${it}: ${wins} winner(s) [$(tr '\n' ' ' <"${work}/wins")], lock names '${holder}'$([[ -e "${lock}.takeover" ]] && echo ', takeover dir left')"
    fi
  done
  if ((bad == 0)); then
    printf 'ok    %-14s %d races x %d contenders: exactly one winner each, lock names it\n' "$state" "$iterations" "$contenders"
  else
    printf 'FAIL  %-14s %d of %d races broke\n' "$state" "$bad" "$iterations"
    failures=$((failures + 1))
  fi
done

# hook_case <name> <take|refuse> <free|stale-pid> <max secs> [VAR=value…]
# One take_lock under set -Eeuo pipefail with exactly the given hook
# environment (PERF_LOCK_SELFTEST and both hooks cleared first).
#  take:   exits 0 within <max secs>, the lock names that shell's pid, no
#          takeover directory is left;
#  refuse: exits non-zero and touches nothing (no lock created from free; the
#          stale lock still names the dead pid; no takeover directory).
hook_case() {
  local name=$1 want=$2 state=$3 max=$4
  shift 4
  local lock="${work}/hooklock" rc=0 out start elapsed why=""
  rm -rf "$lock" "${lock}.takeover"
  if [[ "$state" == stale-pid ]]; then mkdir "$lock" && printf '%s\n' "$dead" >"${lock}/pid"; fi
  start=$(date +%s)
  out=$(env -u PERF_LOCK_SELFTEST -u PERF_LOCK_TEST_PAUSE_BEFORE_PID -u PERF_LOCK_TEST_PAUSE_BEFORE_TAKEOVER "$@" \
    bash -c 'set -Eeuo pipefail; source "$1/lib/common.sh"; take_lock "$2" hook-case; printf "%s" "$$"' \
    _ "$PERF_DIR" "$lock" 2>"${work}/hook.err") || rc=$?
  elapsed=$(($(date +%s) - start))
  if [[ "$want" == take ]]; then
    ((rc == 0)) || why+="exit ${rc}; "
    [[ "$(cat "${lock}/pid" 2>/dev/null || true)" == "$out" && -n "$out" ]] || why+="lock names '$(cat "${lock}/pid" 2>/dev/null || true)', not the taker '${out}'; "
    ((elapsed <= max)) || why+="took ${elapsed} s (> ${max} s); "
  else
    ((rc != 0)) || why+="took the lock; "
    if [[ "$state" == free ]]; then
      [[ ! -e "$lock" ]] || why+="left ${lock} behind (pid '$(cat "${lock}/pid" 2>/dev/null || true)'); "
    else
      [[ "$(cat "${lock}/pid" 2>/dev/null || true)" == "$dead" ]] || why+="the stale lock no longer names ${dead}; "
    fi
  fi
  [[ ! -e "${lock}.takeover" ]] || why+="left a takeover directory; "
  if [[ -z "$why" ]]; then
    printf 'ok    %-34s %s\n' "$name" "$want"
  else
    printf 'FAIL  %-34s expected %s: %s(stderr: %s)\n' "$name" "$want" "$why" "$(tr '\n' ' ' <"${work}/hook.err")"
    failures=$((failures + 1))
  fi
  rm -rf "$lock" "${lock}.takeover"
}
hook_case no-flag-nonnumeric-free take free 2 \
  PERF_LOCK_TEST_PAUSE_BEFORE_PID=abc PERF_LOCK_TEST_PAUSE_BEFORE_TAKEOVER=abc
hook_case no-flag-nonnumeric-stale take stale-pid 2 \
  PERF_LOCK_TEST_PAUSE_BEFORE_PID=abc PERF_LOCK_TEST_PAUSE_BEFORE_TAKEOVER=abc
hook_case no-flag-nonnumeric-pid-stale take stale-pid 2 PERF_LOCK_TEST_PAUSE_BEFORE_PID=abc
hook_case no-flag-5s-free take free 2 PERF_LOCK_TEST_PAUSE_BEFORE_PID=5
hook_case no-flag-5s-stale take stale-pid 2 \
  PERF_LOCK_TEST_PAUSE_BEFORE_PID=5 PERF_LOCK_TEST_PAUSE_BEFORE_TAKEOVER=5
hook_case flag-nonnumeric-free refuse free 2 PERF_LOCK_SELFTEST=1 PERF_LOCK_TEST_PAUSE_BEFORE_PID=abc
hook_case flag-nonnumeric-stale refuse stale-pid 2 PERF_LOCK_SELFTEST=1 PERF_LOCK_TEST_PAUSE_BEFORE_TAKEOVER=abc
hook_case flag-negative-free refuse free 2 PERF_LOCK_SELFTEST=1 PERF_LOCK_TEST_PAUSE_BEFORE_PID=-1
hook_case flag-leading-dot-free take free 2 PERF_LOCK_SELFTEST=1 PERF_LOCK_TEST_PAUSE_BEFORE_PID=.5
hook_case flag-trailing-dot-free take free 3 PERF_LOCK_SELFTEST=1 PERF_LOCK_TEST_PAUSE_BEFORE_PID=1.
hook_case flag-lone-dot-free refuse free 2 PERF_LOCK_SELFTEST=1 PERF_LOCK_TEST_PAUSE_BEFORE_PID=.
hook_case flag-exponent-free refuse free 2 PERF_LOCK_SELFTEST=1 PERF_LOCK_TEST_PAUSE_BEFORE_PID=1e3
hook_case flag-valid-free take free 3 PERF_LOCK_SELFTEST=1 PERF_LOCK_TEST_PAUSE_BEFORE_PID=0.5
if grep -q 'lock test hooks active (PERF_LOCK_SELFTEST=1): pause before pid 0.5 s' "${work}/hook.err"; then
  printf 'ok    %-34s %s\n' flag-valid-logs-one-line "$(grep -c 'lock test hooks active' "${work}/hook.err") line"
else
  printf 'FAIL  %-34s no "lock test hooks active" line in stderr\n' flag-valid-logs-one-line
  failures=$((failures + 1))
fi

if ((failures > 0)); then
  echo "lock-selftest: ${failures} case(s) failed"
  exit 1
fi
echo "lock-selftest: all races and hook cases hold"
