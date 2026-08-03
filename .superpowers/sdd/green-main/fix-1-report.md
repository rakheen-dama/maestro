# Fix 1 — the terminal-event read race that keeps `main` red

**Worktree:** `/Users/rakheendama/Projects/2026/maestro/.claude/worktrees/green-main`
**Branch:** `worktree-green-main` (based on merged `main` `15b27dc`)
**Java:** Corretto 25.0.1 · **Docker:** 28.5.1 · **Date (UTC):** 2026-08-03

**STATUS: FIXED.** Reproduced deterministically, fixed, swept across every other
site, and verified green — including a pin that goes red the moment the fix is
reverted.

| Commit | What |
|---|---|
| `82a4a65` | `test: fixture that makes the terminal-write gap deterministic` |
| `6296427` | `test: wait for the terminal EVENT, not the instance status` |
| (this file) | report + evidence |

All evidence under `.superpowers/sdd/green-main/evidence/`, each file carrying an
`=== IDENTITY ===` header (pwd, branch, HEAD, timestamp).

---

## 1. The defect, restated in one line

Finalising a run is **two separate, non-transactional writes and the instance row
goes first**, so there is a real committed interval in which `getInstance()`
answers `COMPLETED` while `getEvents()` is still one event short.

`WorkflowExecutor.java:1425-1441` (and the mirror-image failure path at
`:1731-1741`):

```java
if (transitionToTerminal(ctx, instance, WorkflowStatus.COMPLETED, outputPayload)) { // 1. UPDATE instance
    emit("workflowCompleted", ctx.workflowId(), () -> observer.workflowCompleted(observed(instance)));
    appendEvent(ctx, EventType.WORKFLOW_COMPLETED, null, outputPayload);             // 2. INSERT event
    publishLifecycleEvent(instance, LifecycleEventType.WORKFLOW_COMPLETED, null);
}
```

The tests gated on the advisory hint (the status column) and then asserted on the
durable truth (the event log). That is the bug — a test-side await-predicate
defect, not an engine defect.

---

## 2. Reproduced deterministically — BEFORE

A repeat loop was rejected: the window is one database round trip wide, so it
reproduces at a low single-digit percent and proves nothing either way. Instead
`TerminalEventDelayStore` (a `WorkflowStore` decorator, commit `82a4a65`) holds
**only** the `WORKFLOW_COMPLETED`/`WORKFLOW_FAILED` append open for a configured
delay. Everything else passes straight through, so the workflow runs at normal
speed right up to the moment it finishes.

Wiring it into `EnginePostgresParallelIT` with a 3 s delay
(`evidence/01-repro-BEFORE-raw.log`, `evidence/02-repro-BEFORE-assertion.txt`):

```
Parallel branches partition the sequence space in Postgres > replay after a crash re-runs only the branch that never finished FAILED
4 tests completed, 1 failed
BUILD FAILED in 18s
```

```
org.opentest4j.AssertionFailedError: the fork event must not be re-appended on replay
 ==> expected: <[1:SIDE_EFFECT:$maestro:parallel, 2001:ACTIVITY_COMPLETED:chain.stepOne,
                 3001:ACTIVITY_COMPLETED:chain.stepTwo, 4001:ACTIVITY_COMPLETED:chain.stepThree,
                 5001:WORKFLOW_COMPLETED:null]>
      but was: <[1:SIDE_EFFECT:$maestro:parallel, 2001:ACTIVITY_COMPLETED:chain.stepOne,
                 3001:ACTIVITY_COMPLETED:chain.stepTwo, 4001:ACTIVITY_COMPLETED:chain.stepThree]>
```

**Byte-identical to the CI failure in GitHub Actions run 30728290264.** Same
assertion, same label, same single missing trailing `5001:WORKFLOW_COMPLETED`.

A useful control fell out for free: in that same BEFORE run the sibling test
`parallelBranches_persistDistinctSequenceBlocks` **passed**, because it goes
through `WorkflowHandle.awaitTerminal`, which already waited for the event. Same
workflow, same 3 s delay, different predicate, different outcome — the predicate
is the whole difference.

## 3. AFTER — same injected delay, green

With the fix applied and the 3 s delay **still injected**
(`evidence/03-repro-AFTER-raw.log`):

```
Parallel branches partition the sequence space in Postgres > replay after a crash re-runs only the branch that never finished PASSED
BUILD SUCCESSFUL in 17s
```

The temporary injection was then removed from `EnginePostgresParallelIT` and
replaced with a permanent pin (§5).

---

## 4. The fix

One predicate, in one place: `TerminalWait` (`…/integration/support/TerminalWait.java`).

```java
public static boolean isFinalised(WorkflowStore store, WorkflowInstance instance) {
    if (!instance.status().isTerminal())                return false;
    if (instance.status() == WorkflowStatus.TERMINATED) return true;   // appends no event
    return store.getEvents(instance.id()).stream().anyMatch(e ->
            e.eventType() == EventType.WORKFLOW_COMPLETED
                    || e.eventType() == EventType.WORKFLOW_FAILED);
}
```

No sleeps, no lengthened timeouts. The bounds are unchanged; only the condition
changed.

**The one exemption, and why it is the only one.** `terminate()`
(`WorkflowExecutor.java:745,769`) moves the instance row to `TERMINATED` and
publishes a `WORKFLOW_TERMINATED` *lifecycle* event, but there is no
`WORKFLOW_TERMINATED` member of `EventType` — nothing reaches the durable log.
Requiring an event there would hang forever. Every other terminal status must
carry its event.

**A silent fallback was also removed.** `WorkflowHandle.awaitTerminal` already
waited for the terminal event (added in `0ece77d`, "close a terminal-event race
that flaked EnginePostgresSagaIT on CI") but swallowed the timeout and returned
on the status anyway, justified as "a node that lost the finalisation race writes
no event of its own". That justification does not hold: `getEvents` reads the
*shared* log, and the winner of `transitionToTerminal` always appends. So the
fallback could only ever mask the race it was added to close. The event is now
required, and a terminal status that never grows its terminal event fails loudly
— because that would be a genuine engine defect, not a timing artefact.

---

## 5. Every site swept

The shape was copy-pasted. `0ece77d` had already fixed it once, in
`WorkflowHandle` only; the inline copies were never swept. All of them now route
through `TerminalWait`.

| # | File : line (pre-fix) | Gated on | Then asserted | Would have failed? |
|---|---|---|---|---|
| 1 | `engine/EnginePostgresParallelIT.java:147` | `status().isTerminal()` | log must equal `[…, 5001:WORKFLOW_COMPLETED]` | **Yes — this is the CI failure** |
| 2 | `engine/EnginePostgresMemoizationIT.java:241` (helper `awaitTerminal`, 3 callers @ 69/99/137) | `status().isTerminal()` | seq list `[1,2,3,4]` where 4 is `WORKFLOW_COMPLETED`; `getEvents().getLast()` must be `WORKFLOW_FAILED`; before/after log snapshots must be equal | **Yes, all three** |
| 3 | `engine/EnginePostgresRecoveryIT.java:268` (helper `awaitStatus`, 2 callers @ 83/125) | `status() == COMPLETED` | log must equal `[…, 4:WORKFLOW_COMPLETED]` | **Yes (caller 83)** |
| 4 | `multinode/MultiNodeLockContentionIT.java:196` | `status() == COMPLETED` | `assertEquals(4, getEvents(…).size())` | **Yes** |
| 5 | `multinode/MultiNodeNoLockBackendIT.java:163` | `status() == COMPLETED` | seq list `[1,2,3,4]`; exactly one `WORKFLOW_COMPLETED` | **Yes** |
| 6 | `multinode/MultiNodeOwnerDeathIT.java:96` | `status() == COMPLETED` | log must equal `[…, 4:WORKFLOW_COMPLETED]` | **Yes** |
| 7 | `kafka/KafkaSpringIntegrationSupport.java:182` (helper `awaitStatus`, ~20 callers across 6 Kafka suites) | `status() == expected` | its current callers only filter for `SIGNAL_RECEIVED` | No — but hardened anyway; it is the trap, and the next caller to read the whole log would have fallen in |
| 8 | `maestro-test/…/TestWorkflowHandle.java:166` (`getResult`/`awaitCompletion`) | `status().isTerminal()` | `TestWorkflowEnvironmentTest:52-55` asserts `WORKFLOW_COMPLETED` present; `DeterminismCheckerVersionMarkerTest:55` reads the log | **Yes — and this one ships.** Same defect in `maestro-test`'s public API, so every downstream user writing `awaitCompletion(); getEvents()` inherits it |
| 9 | `support/WorkflowHandle.java:114` (~15 callers) | already waited, best-effort | — | Silent status-only fallback removed (§4) |

Audited and found **not** affected: `e2e/chaos/WorkloadDriver.java:472,683,748`
(polls a service's HTTP status, never reads an event log);
`maestro-store-postgres`, `maestro-lock-*`, `maestro-messaging-*` (store/lock/broker
unit and Testcontainers suites — no engine await/assert pairs); the loan-origination
sample tests (`awaitCompletion` via `maestro-test`, now fixed at source by #8).

**New permanent pin:** `engine/TerminalEventRaceIT.java` — four tests over
`TerminalEventDelayStore`. The first, `statusOnlyWait_seesTheGap`, asserts the
window is *actually open* (`assertFalse(hasTerminalEvent(...))` plus
`assertEquals(List.of(1,2,3), …)`), so the suite cannot pass vacuously; the other
three pin `TerminalWait.awaitTerminal`, `WorkflowHandle.awaitTerminal` and the
base-class `awaitStatus`.

**The pin observes the symptom.** Temporarily reverting `isFinalised` to the
pre-fix status-only predicate (`evidence/05-pin-fails-without-fix-raw.log`):

```
4 tests completed, 3 failed
BUILD FAILED in 20s
```

— the three predicate tests go red, and `statusOnlyWait_seesTheGap` stays green
exactly as it should.

---

## 6. Verification

| Evidence | Command | Result |
|---|---|---|
| `04-regression-pin-raw.log` | `:maestro-integration-tests:test --tests '*TerminalEventRaceIT*' --rerun-tasks` | `BUILD SUCCESSFUL`, 4/4 |
| `05-pin-fails-without-fix-raw.log` | same, fix temporarily reverted | `4 tests completed, 3 failed` — the pin works |
| `06-integration-tests-rerun-raw.log` | `./gradlew :maestro-integration-tests:test --rerun-tasks` | `BUILD SUCCESSFUL in 2m 1s` — **107 PASSED, 0 FAILED** |
| `07-full-build-raw.log` | `./gradlew build` | `BUILD SUCCESSFUL`, 134 actionable tasks |
| `08-test-result-totals.txt` | XML report aggregation, all modules | **`TOTAL tests=899 failures=0 errors=0 skipped=0`** |
| `09-stability-loop.txt` | 8× `EnginePostgresParallelIT` + `MemoizationIT` + `MultiNode*` + `TerminalEventRaceIT` | 8/8 `PASSED=25 FAILED=0 BUILD SUCCESSFUL` |

Honest note on `07`: it completed in 11 s because most tasks were `FROM-CACHE` /
`UP-TO-DATE` — `:maestro-integration-tests:test` in particular, having just been
run with `--rerun-tasks`. The tasks that genuinely **executed** are exactly the
ones downstream of the changed `maestro-test` source: `:maestro-test:test`,
`:maestro-spring-boot-starter:test`, and all three
`:maestro-samples:sample-loan-origination:*:test`. The 899-test total in `08` is
aggregated from the on-disk XML reports across every module and is the number to
trust.

The 8× loop is a smoke check, not proof — at a natural ~1-in-hundreds flake rate,
8 green runs would be unremarkable even on broken code. The proof is the
BEFORE/AFTER pair in §2–§3 and the revert test in §5.

---

## 7. Recommendation on engine atomicity — **you rule, I do not**

**Question:** should `WorkflowExecutor` make the status write and the terminal-event
append atomic (or reorder to append-then-status), so no observer can ever see a
terminal workflow without its terminal event?

**Recommendation: yes — eventually, and specifically as a new `WorkflowStore`
SPI method `finaliseInstance(instance, terminalEvent)` with a "both or neither"
contract. Not as a reorder, and not urgently.** Reasoning:

### It is a genuine user-visible defect, not a test artefact

The test was wrong, but it was wrong in the way every integrator will be wrong.
Anyone who polls status and then reads the log hits the identical window:

- **`maestro-admin`** — a dashboard refresh landing in the gap renders a run
  badged `COMPLETED` whose timeline stops at the last activity. Cosmetic and
  self-healing on the next poll, but it looks like data loss to an operator.
- **An operator or API client doing "wait until terminal, then export the audit
  log"** — gets a log missing its closing record. If that snapshot is archived
  or handed to an auditor, it is **permanently** wrong. This is the real harm.
- Notably the **lifecycle-event** path is already safe: `publishLifecycleEvent`
  fires *after* the append, so a Kafka consumer reacting to
  `WORKFLOW_COMPLETED` and then reading the log always sees a complete log. It
  is specifically the status-polling path that is unsafe — and status polling is
  the obvious thing to reach for. Six of our own test sites reached for it.

An embeddable engine should be able to state "a terminal status implies a
terminal event" flatly in its docs. Callers cannot reasonably be expected to know
the status column runs one write ahead of the log, and every integration that
does not know will re-derive this bug independently.

### But do not reorder to append-then-status — that trades a benign gap for a malignant one

`transitionToTerminal` is the CAS that decides *which* runner owns finalisation;
`appendEvent` runs only for the winner. Reordering breaks that twice over:

1. **Two converging runners would both append**, colliding on the
   `(workflow_instance_id, sequence_number)` unique index. The loser's exception
   lands in `catch (Exception)` → `handleWorkflowFailure` — i.e. **compensating a
   workflow that just succeeded**. Recoverable (there is already a
   duplicate-event stand-down path), but it demotes explicit CAS arbitration to
   exception-driven arbitration.
2. Worse, it creates a **new window in which the log carries
   `WORKFLOW_COMPLETED` while the instance row still reads `RUNNING`** — and
   `getRecoverableInstances()` reads that row. The recovery poller would hand
   back an already-finished workflow and re-invoke the workflow method. That is
   strictly more dangerous than what we have today.

### Why not urgent

The current gap is a **read-consistency** defect, not a durability one. It is one
round trip wide, it closes unconditionally, and nothing durable is wrong: no state
is lost, nothing double-executes, no compensation fires. Only a snapshot read
taken inside the window is incomplete, and the very next read is correct.

### The shape I would implement

Add `WorkflowStore.finaliseInstance(WorkflowInstance, WorkflowEvent)`, contract
"both writes commit or neither does", returning the same boolean
`transitionToTerminal` returns today (false = lost the version CAS, wrote
nothing). Postgres/JDBC does it in one transaction; the in-memory store does it
under its existing lock. This keeps the SPI honest — it adds one narrow method
rather than leaking a transaction or unit-of-work abstraction into it — and
`maestro-core` gains no dependency. Apply it on **both** terminal paths
(`:1425` COMPLETED and `:1731` FAILED), and preserve where `emit(...)` sits: the
observer contract deliberately fires *before* the append, and that ordering is
load-bearing (see the comment at `:1428-1434`).

Until then, document the gap explicitly in the `WorkflowStore` Javadoc and in
`docs/`, so integrators gate on the event exactly as these tests now do.

### And regardless of how you rule, this fix stands

An engine change would not have been the right way to fix a test that asserts on
the wrong thing, and the tests should keep gating on the event even afterwards —
a third-party or older `WorkflowStore` implementation may not be atomic, and the
event log is the truth in any case.

**PR #31's marginal widening.** The investigation noted PR #31 inserted an
`emit()` into this exact gap. Confirmed present at `WorkflowExecutor.java:1435`
and `:1735`. With `EngineObserver.NOOP` the cost is negligible, but it is
strictly more work in the window, so the flake could only get more likely. The
predicate fix removes the sensitivity entirely; if the engine is ever made
atomic, the `emit()` placement stops mattering as well.
