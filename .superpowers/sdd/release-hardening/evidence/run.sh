#!/usr/bin/env bash
# Evidence runner: archives a gradle invocation under evidence/ with an identity header.
# Usage: run.sh <log-name> <note> -- <gradle args...>
set -uo pipefail
ROOT="/Users/rakheendama/Projects/2026/maestro/.claude/worktrees/release-hardening"
NAME="$1"; shift
NOTE="$1"; shift
[ "$1" = "--" ] && shift
LOG="$ROOT/.superpowers/sdd/release-hardening/evidence/$NAME.log"
cd "$ROOT" || exit 1
{
  echo "pwd: $(pwd)"
  echo "branch: $(git rev-parse --abbrev-ref HEAD)"
  echo "HEAD: $(git rev-parse HEAD)"
  echo "timestamp: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "command: ./gradlew $* ($NOTE)"
} > "$LOG"
./gradlew "$@" >> "$LOG" 2>&1
RC=$?
echo "exit-code: $RC" >> "$LOG"
echo "archived: $LOG (exit $RC)"
exit $RC
