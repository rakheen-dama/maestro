#!/usr/bin/env bash
# Run-lock self-test: perf/lib/lock-selftest.sh [iterations] [contenders]
#
# Races <contenders> shells (default 8), released at the same instant, for
# take_lock (lib/common.sh) on a scratch lock directory, <iterations> times
# (default 30) for each starting state: a free lock, a stale lock naming a dead
# pid, and a stale pid-less lock older than a minute. Every race must have
# exactly one winner, and the lock must name that winner's pid. No Docker.
# Exit 0 when every race holds, 1 otherwise.
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

for state in free stale-pid stale-no-pid; do
  bad=0
  for ((it = 1; it <= iterations; it++)); do
    lock="${work}/lock"
    rm -rf "$lock" "${lock}.takeover" "${work}/go" "${work}/release"
    : >"${work}/wins"
    : >"${work}/decided"
    case "$state" in
      stale-pid) mkdir "$lock" && printf '%s\n' "$dead" >"${lock}/pid" ;;
      stale-no-pid) mkdir "$lock" && touch -t 200001010000 "$lock" ;;
    esac
    pids=()
    for ((c = 1; c <= contenders; c++)); do
      # A winner keeps running (holding the lock) until every contender has
      # decided, as a real run holds it for its whole life; a loser exits.
      bash -c '
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

if ((failures > 0)); then
  echo "lock-selftest: ${failures} starting state(s) failed"
  exit 1
fi
echo "lock-selftest: all races hold"
