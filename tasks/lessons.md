# Lessons

## E2E verification must assert process identity, not just liveness (2026-07-28)
**What happened:** After fixing library bugs and deleting sample workarounds, an
E2E re-run reported 5/5 PASS — but stale JVMs from an earlier interrupted run
were still holding ports 8091-8093 and served the readiness probes. The "clean"
run had exercised the old, workaround-laden binaries.
**Rule:** Before trusting an E2E result, verify WHICH process served it: check
the PID in the service logs against the run's own `pids` file, confirm ports
were free before start, and confirm the artifact contents (e.g.
`unzip -l app.jar | grep DeletedClass` → 0). A 200 from a readiness endpoint
proves something is listening, not that *your build* is listening.

## Multi-agent builds need a written contract before parallel fan-out (2026-07-28)
Cross-service DTO shapes not pinned in the spec (UnderwritingRequest fields,
verdict type) had to be reconciled after the fact. Pin every wire shape —
field names AND types — in the spec before launching parallel builders.

## Long-running subagents die on transient API errors — design for resume
Agents were killed mid-task twice ("connection closed mid-response") and once
stalled on a watchdog. Their working-tree progress survives. Resume via
SendMessage with a precise state summary; if an agent dies twice on the same
step, finish the step inline instead of resuming a third time.

## Long-running builder agents: mandate incremental commits (2026-07-29)
Three builder agents died mid-task on transient API errors/stalls this session.
The one dispatched WITHOUT "commit at every coherent checkpoint" lost ~all its
in-context work twice; the ones dispatched WITH it lost nothing (5 checkpoint
commits survived a death during final verification). Rule: every implementer
dispatch for >30-min work must include the incremental-commit instruction, and
recovery is: read `git log`/`git status`, send a precise state summary, resume
— or fresh agent over the surviving tree after two deaths.

## Shared scratchpads need artifact identity, not just process identity (2026-07-30)
The coordinator read gate2-run2/3.log as this cycle's passing evidence; both were
stale leftovers from the PREVIOUS cycle's (deleted) worktree — caught by QA
grepping the worktree paths inside the logs. Rule: evidence logs must embed
their own identity (pwd + git rev-parse + timestamp INSIDE the file at write
time), live in a per-cycle subdirectory, and be pruned at cycle start. Never
trust a log by filename + recency alone.

## Worktree tasks: verify the target tree BEFORE the first edit (2026-07-31)
Task 5's first four file edits landed in the MAIN checkout instead of the
assigned worktree: relative Read/Edit paths resolved against the main repo
because early exploration used `cd /Users/.../maestro && ...` compound
commands, and the edits reused those (now-wrong) absolute paths. Caught only
when a smoke run showed unstamped script output; cost a full revert+redo
cycle. Rules: (1) in a worktree task, NEVER `cd` into the main checkout, even
for "just a build" - run everything from the worktree; (2) before the first
Edit, `git -C <worktree> status` AND `git -C <main> status` to prove which
tree is about to change; (3) after each commit checkpoint, re-check the main
checkout is still clean. Also: one bounded FOREGROUND Bash call per e2e step -
a backgrounded run dies silently when the agent turn ends.

## From the multi-instance verification cycle (2026-08-02 close-out)

- **Subagent background shells die when the agent idles.** Any command longer than a few minutes must be launched by the coordinator as a detached `nohup` wrapper script writing to a log with an identity header, then watched with bounded foreground poll loops (~25 min chunks). Two smokes and one report build died silently before this was systematic.
- **Dedicated test invocations must select only their dedicated class.** A system property that *adds* a test class (`soak=true`) while a shared property (`durationMinutes`) reconfigures *all* classes let the 25-min-@Timeout PR-gate test run a 120-min window. Three 2h soak attempts died to this one collision — masked first by a swallowed interrupt (pacer runaway), then misdiagnosed twice (Docker degradation, checker reconnects). Guard the non-dedicated classes with `@DisabledIfSystemProperty` and RED-pin the suite selection.
- **Swallowed interrupts turn one bug into four wrong theories.** `parkNanos` + re-set interrupt flag = every later sleep no-ops; the loud-abort fix identified the true interrupter (JUnit TimeoutExtension) in a single run, stack trace included. Interrupt handling in pacing/sleep loops must abort loudly, always.
- **Run-dir identity stamps record repo state, not binary provenance.** The stamp shells out to git at run start; commits landing between compile and run-start skew it (b2b5c65 binary stamped 7113e06). Log the compile-time HEAD at launch separately and reconcile when citing evidence.
- **One red test in a big parallel build is a datum, not a verdict.** Before reopening anything: check the failing test's blast-radius overlap with the delta, rerun targeted, then module x3, then the full build. (WorkflowExecutorTerminateTest 5s-latch race under full-build load: 1 red, then 4 consecutive greens.)
