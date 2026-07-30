# Issues 13–15 Plan

Source spec: `docs/open-issues.md` §Issue 13, §Issue 14, §Issue 15 (added by the
release-readiness pass; read each section in full — they are the requirements).
Branch: `worktree-issues-13-15` off `main` @ the PR #28 merge.

Issues 13 and 15 require coordinator-approved designs before implementation;
the architect agents write them to the SDD workspace as `issue13-design.md`
and `issue15-design.md`. Issue 14 needs no design.

## Global Constraints

- `maestro-core` must NEVER import Spring. All Spring integration lives in
  `maestro-spring-boot-starter`.
- Jackson 3 (`tools.jackson`), never `com.fasterxml.jackson`. `jakarta.*`,
  never `javax.*`. No Lombok. JSpecify `@Nullable` on public APIs. Exceptions
  extend `MaestroException` (the one documented exception:
  `ExecutorShutdownException extends Error`). Javadoc + thread-safety notes on
  public classes.
- Kafka topics never auto-created. Never break
  `(workflow_instance_id, sequence_number)` uniqueness.
- TDD: every behaviour change starts with a failing test; RED evidence in the
  report. Awaitility, never `Thread.sleep` as synchronisation.
- Never use `git stash` (shared stack). Use `git show HEAD:<path>` for
  temporary-revert RED captures. Commit incrementally — never >30 min of work
  uncommitted.
- Real-backend suites: 3 consecutive `--rerun-tasks` green runs.
- Library-bug protocol: reproduce engine defects as failing tests in the
  owning module first; never work around them in tests.
- Do not change public behaviour of anything not named by your task.

## Task 1: Issue 14 — SagaManager replay-skip guard

**Kind:** Library defect (currently harmless; latent re-execution bug).

`SagaManager.compensate()` has no replay-skip guard: on recovery it re-appends
`COMPENSATION_STARTED`/`COMPENSATION_COMPLETED` (swallowed via
`DuplicateEventException`) and — the real hazard — a manually-registered
compensation action (`wf.addCompensation(Runnable)`) that COMPLETED before an
interruption is re-invoked on replay, because unlike `@Compensate` activities
it is not memoized.

**Fix (per the issue's own sketch):** give `compensate()` the same replay-skip
check every other event-emitting path has: before appending
`COMPENSATION_STARTED`/`COMPENSATION_COMPLETED` (and before re-invoking a
compensation action), check whether an event already exists at that sequence
number and skip the already-completed work. Cover both sequential and parallel
compensation loops.

**Test (failing first, mirrors how activities are proven not to re-execute):**
drive a workflow through a partially-completed compensation — at least two
LIFO compensation actions, the FIRST of which completes durably and the SECOND
of which is interrupted (use the existing shutdown-mid-compensation fixtures
as precedent, but ensure the completed action is NOT first in LIFO order this
time — the issue notes current fixtures never exercise that) — then recover on
a second harness and assert the completed compensation action is NOT
re-invoked (invocation counter) while compensation still completes.

**Done when:** the test passes; no duplicate-event append occurs on replay
(assert the log, not just absence of exceptions); sequential and parallel
loops both covered; existing saga/shutdown suites stay green.

## Task 2: Issue 13 — cancelling a parked timer must not strand the workflow

**Kind:** Library defect + design decision. Follow the coordinator-approved
design in the SDD workspace file `issue13-design.md` (the dispatch prompt
carries its path). The design settles: what SHOULD cancelling a timer that a
workflow is currently parked on do (unpark with a distinguished catchable
"cancelled" outcome vs. reject/no-op cancellation of awaited timers), covering
both the live case (workflow parked right now) and the replay case
(`DefaultWorkflowOperations.sleep` finds `TIMER_SCHEDULED`, no `TIMER_FIRED`,
row says `CANCELLED`).

**Reproduce first** (both cases, failing tests): (a) live: cancel via
`TimerManager.cancelTimer` while a workflow is parked on the timer; today
nothing unparks it. (b) replay: workflow parked on a timer, timer row
`CANCELLED`, process crashes, recovery re-parks forever.

**Done when:** both repros pass with the designed semantics; the semantics are
documented (Javadoc on `cancelTimer` + the timer docs section); the admin
cancel-timer path (the trigger named by the issue) behaves observably; the
Issue 2 heal path is untouched for `FIRED`.

## Task 3: Issue 15 — engine-side consumer for $maestro:retry / $maestro:terminate

**Kind:** Library gap (admin buttons silently do nothing). Follow the
coordinator-approved design in the SDD workspace file `issue15-design.md`.
The design settles: where the `$maestro:` command dispatcher lives (starter,
alongside the existing signal-routing machinery), how it distinguishes command
signals from application signals before `awaitSignal` consumption, what
`$maestro:retry` does (re-drive a FAILED workflow's retry path — the state
machine already allows FAILED → RUNNING manual retry) and what
`$maestro:terminate` does (any active → TERMINATED), idempotency/validation
(what happens on retry of a non-FAILED workflow, terminate of a terminal one),
and security posture (documented — these arrive on the service's signal
topic).

**Done when:** an end-to-end test proves the dashboard's Retry measurably
re-drives a FAILED workflow and Terminate moves an active workflow to
TERMINATED on a real transport (Kafka integration test at minimum; the
maestro-admin suite's controller tests stay controller-scoped); command
signals are never delivered to `awaitSignal`; docs updated (`docs/admin.md`
known-limitation callout removed, semantics documented).

## Task 4: Docs — close out issues 13–15

After Tasks 1–3: update `docs/open-issues.md` (Resolved callouts with commit
refs and pinning tests for 13, 14, 15, matching the house style of 1–10);
update `docs/release-notes.md` (new behaviour: timer-cancel semantics, admin
retry/terminate now functional — with any new config or breaking changes
called out); remove/adjust the `docs/admin.md` known-limitation callout;
verify `docs/test-plan.md`'s pre-existing note about retry/terminate is
reconciled. Verify every claim against the actual commits/reports. Docs only.

## Task 5: QA cycle

Read `tasks/lessons.md` first and follow the E2E process-identity lesson to
the letter. Gates:
1. `./gradlew build` clean (includes integration tests).
2. `./gradlew :maestro-integration-tests:test --rerun-tasks` — 3 consecutive
   green runs with per-run counts.
3. Loan-origination E2E (`maestro-samples/sample-loan-origination/e2e/run-e2e.sh`)
   — all scenarios, process-identity proof, jars contain this branch's classes.
   This also closes the release-readiness caveat that the E2E was last run
   before the `GatedWorkflowMessaging` fix.
4. Admin dashboard live check: events ingested (HTTP evidence) AND — new — the
   Retry/Terminate buttons' end-to-end effect against a live service, per
   Task 3's feature. Verify a FAILED workflow retried from the dashboard
   actually re-runs, and Terminate actually terminates, via the dashboard's
   own view or the service's store.
5. Failures reopen the owning task (library-bug protocol); QA never patches.

**Done when:** all gates pass with pasted evidence.
