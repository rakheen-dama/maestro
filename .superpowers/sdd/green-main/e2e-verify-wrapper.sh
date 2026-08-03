#!/usr/bin/env bash
cd /Users/rakheendama/Projects/2026/maestro/.claude/worktrees/green-main
LOG=.superpowers/sdd/green-main/evidence/fix-2/17-e2e-seed17-complete.log
{
  echo "=== ARTIFACT IDENTITY ==="
  echo "pwd: $(pwd)"; echo "toplevel: $(git rev-parse --show-toplevel)"
  echo "HEAD: $(git rev-parse HEAD)"; echo "branch: $(git branch --show-current)"
  echo "started: $(date)"; echo "stage: seed-17 e2e to completion (PARTITION-containing schedule)"
  echo "========================="
} > "$LOG"
./gradlew :maestro-integration-tests:e2eTest --rerun-tasks -Dmaestro.chaos.seed=17 >> "$LOG" 2>&1
echo "E2E_SEED17_EXIT=$?" >> "$LOG"
