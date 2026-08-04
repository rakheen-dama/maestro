#!/usr/bin/env bash
# drive-loan.sh — drive one demo scenario end to end with REST calls.
#
# Usage: demo/scripts/drive-loan.sh <scenario> [applicant-name]
#   happy       TODO
#   conditions  TODO
#   withdraw    TODO
#   crash       TODO
set -euo pipefail

SCENARIO="${1:-}"
APPLICANT="${2:-}"

usage() {
  cat >&2 <<'EOF'
Usage: demo/scripts/drive-loan.sh <happy|conditions|withdraw|crash> [applicant-name]
EOF
  exit 2
}

case "$SCENARIO" in
  happy)      echo "TODO: happy" ;;
  conditions) echo "TODO: conditions" ;;
  withdraw)   echo "TODO: withdraw" ;;
  crash)      echo "TODO: crash" ;;
  *)          usage ;;
esac
