#!/usr/bin/env bash
# evidence-identity.sh [description...]
#
# Emits the standard evidence-identity block to stdout. Every evidence log
# for the multi-instance verification cycle must START with this block,
# generated at write time (never pasted or reconstructed), so a log can be
# tied to the exact working tree, branch, commit, and moment that produced
# it. Usage:
#
#   ./evidence-identity.sh "mode: cluster (E2E_CLUSTER=1)" > "$EVIDENCE_LOG"
#   ... >> "$EVIDENCE_LOG" 2>&1
#
# Any arguments are echoed verbatim as extra identity lines (one per arg).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && git rev-parse --show-toplevel)"

echo "=== EVIDENCE IDENTITY ==="
echo "pwd: $(pwd)"
echo "git-root: $ROOT"
echo "branch: $(git -C "$ROOT" branch --show-current)"
echo "commit: $(git -C "$ROOT" rev-parse HEAD)"
echo "dirty: $(git -C "$ROOT" status --porcelain | wc -l | tr -d ' ') uncommitted change(s)"
echo "timestamp-utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
for line in "$@"; do
    echo "$line"
done
echo "=========================="
echo
