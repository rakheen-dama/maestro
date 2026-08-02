#!/usr/bin/env bash
#
# ARCHIVAL RECORD — not reusable tooling (CodeRabbit PR #30 ruling: annotate,
# don't engineer). This wrapper is the exact invocation used during the
# multi-instance verification cycle, committed as a record of how the runs of
# record were launched. The hardcoded worktree path is intentional: single-use,
# session-scoped tooling pinned to the cycle's worktree. Consumers read the
# *_EXIT= marker(s) from the log the wrapper writes; the wrapper process's own
# exit status is deliberately not meaningful.
#
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
