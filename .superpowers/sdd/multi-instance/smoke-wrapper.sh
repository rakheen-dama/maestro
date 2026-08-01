#!/usr/bin/env bash
cd /Users/rakheendama/Projects/2026/maestro/.claude/worktrees/multi-instance-verification
LOG=.superpowers/sdd/multi-instance/evidence/task7/soak-after-smoke-postdriverfix.log
{
  echo "=== ARTIFACT IDENTITY ==="
  echo "pwd: $(pwd)"
  echo "toplevel: $(git rev-parse --show-toplevel)"
  echo "HEAD: $(git rev-parse HEAD)"
  echo "branch: $(git branch --show-current)"
  echo "started: $(date)"
  echo "========================="
} > "$LOG"
./gradlew :maestro-integration-tests:e2eTest --rerun-tasks -Dmaestro.chaos.soak=true -Dmaestro.chaos.durationMinutes=8 -Dmaestro.chaos.seed=558112 >> "$LOG" 2>&1
echo "SMOKE_EXIT=$?" >> "$LOG"
