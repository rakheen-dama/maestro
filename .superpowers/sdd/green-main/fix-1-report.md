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

---

# Round 1 — closing the review findings

**HEAD at write time:** `ab30ac8` · **Branch:** `worktree-green-main` · **Java:** Corretto 25.0.1
**Date (UTC):** 2026-08-03

**STATUS: ALL FOUR FINDINGS CLOSED.** `:maestro-core:test --rerun-tasks` green;
full `./gradlew build` green; **911 tests, 0 failures, 0 errors** across every
module. New evidence is `evidence/1*-*` and every number below is reproduced by
`evidence/17-greps.txt`, which carries the same `=== IDENTITY ===` header as the
rest.

| Commit | What |
|---|---|
| `157c43b` | `test: sweep maestro-core's own terminal-event read race, and pin status->event` (F1 + F3) |
| `ab30ac8` | `docs: release note for the maestro-test wait change, and stop teaching the bug` (F2 + F4) |

---

## F1 — `maestro-core`'s own tests. The reviewer found two; there were **seven**.

The two reported sites were real. Auditing the module properly found five more of
the same shape, and the experiment (below) then found two the audit itself had
missed — because they are not event *reads*.

| # | Site (pre-fix line) | Gated on | Then | Reported? |
|---|---|---|---|---|
| 1 | `WorkflowExecutorTest.java:607` (inline) | `== COMPLETED` | `:620 assertEquals(4, events.size())` — and the 4th event *is* `WORKFLOW_COMPLETED` | no — found by audit |
| 2 | `WorkflowExecutorRetryTest.java:117` (helper `:464`) | `== COMPLETED` | `:126 hasEvent(WORKFLOW_COMPLETED)` | no — found by audit |
| 3 | `WorkflowExecutorRetryTest.java:444` (same helper) | `== FAILED` | `:449 deleteFailureEvents(...)`, `:450 assertTrue(count > 0)` | **yes — the seam the reviewer flagged** |
| 4 | `WorkflowExecutorShutdownTest.java:578` (helper `:586`) | `== FAILED` | `:581 hasEvent(WORKFLOW_FAILED)` | **yes** |
| 5 | `WorkflowExecutorShutdownTest.java:233 / :281 / :452` (same helper) | `== FAILED` | `hasEvent(WORKFLOW_FAILED)` at `:238 / :293 / :457` | no — found by audit |
| 6 | `WorkflowExecutorTerminalTransitionTest.java:131` (helper `:153`) | `isTerminal()` | `:136 assertEquals(1, eventsOfType(WORKFLOW_COMPLETED))` | **yes** |
| 7 | `SignalTimeoutReplayDeterminismTest.java:184 / :220` (inline) | `== FAILED` | `retryWorkflow(...)`, which calls `deleteFailureEvents` | **no — found by the experiment, not by reading** |

All of them now route through **`TestTerminalWait`**
(`maestro-core/src/test/java/io/b2mash/maestro/core/TestTerminalWait.java`), the
module-local twin of the integration suite's `TerminalWait`. Same predicate, same
`TERMINATED` exemption, same doctrine: **wait for the terminal EVENT, not the
status.** No sleeps, no lengthened timeouts — every bound is unchanged.

The duplication across modules is deliberate and documented in the class Javadoc:
`maestro-core` has no test-fixtures dependency on `maestro-integration-tests` or
`maestro-test`, and introducing one to share nine lines of predicate would cost
more than it saves.

### The `deleteFailureEvents` seam — the reviewer's question, answered

The reviewer asked whether `WorkflowExecutorRetryTest:444→447` is safe because
`ACTIVITY_FAILED` is pre-terminal, and offered "make it robust **or** comment why
it is safe". **It is not safe, and it is not about `ACTIVITY_FAILED`.**

`deleteFailureEvents` deletes `ACTIVITY_FAILED` **and `WORKFLOW_FAILED`**. It is
not an event *read* but an event *mutation*, and the mutation races the append
the status gate did not wait for:

1. the row flips to `FAILED`; the test's status gate returns;
2. `deleteFailureEvents` runs and removes what is there;
3. the engine's `appendEvent(WORKFLOW_FAILED, …)` lands **after** the delete.

The memo the test believes it deleted is now back in the log. The test then
asserts `firstDeleteCount > 0` and proceeds to model "crashed after delete,
before CAS" against a log that no longer has that shape — the scenario is
silently not the one under test. So: **made robust**, not commented away.

That same seam is what makes site #7 above interesting. `retryWorkflow` calls
`deleteFailureEvents` internally, so the two `SignalTimeoutReplayDeterminismTest`
sites have the identical defect with no visible `getEvents` call anywhere near
them. A read-only audit does not find those; the experiment did.

### Proof: each changed test goes red when the property it pins is broken

A repeat loop would prove nothing — the in-memory window is a thread preemption,
so it reproduces at a low rate either way. Instead the window was made
deterministic, exactly as in §2 of the original fix:

- **Injected** a temporary `Thread.sleep(300)` into `WorkflowExecutor` between
  `transitionToTerminal(...)` and `appendEvent(WORKFLOW_COMPLETED / WORKFLOW_FAILED)`
  on **both** terminal paths (`:1425` and `:1731`). This widens the real gap; it
  does not invent one.
- **Reverted** `TestTerminalWait.isFinalised` to the pre-fix status-only
  predicate.

**BEFORE** (`evidence/11-core-pin-BEFORE-statusonly-raw.log`):

```
398 tests completed, 12 failed
BUILD FAILED in 1m 23s
```

The full roster of 12 is appended to that log. It is exactly the seven sites
above, expanded across their test methods — nothing else in the module reddened,
which is itself the sweep's completeness check.

**AFTER** — fixed predicate, **same 300 ms injection still present**
(`evidence/12-core-pin-AFTER-samegap-raw.log`):

```
BUILD SUCCESSFUL in 1m 16s
```

The engine injection was then removed. `git diff 15b27dc..HEAD -- maestro-core/src/main`
is **empty** — this work does not touch the engine, and `evidence/17-greps.txt`
shows that emptiness.

---

## F3 — mapped status → expected event. **Not** documented-and-left.

**Decision: map.** The reviewer offered either. Mapping wins on every axis I can
see:

- It is **three lines**, in a `switch` that the compiler checks, versus a comment
  asserting a cross-module invariant that nothing enforces.
- The dependency being documented is genuinely load-bearing and genuinely
  distant: `TerminalWait` (a test helper) would be relying on
  `AbstractJdbcWorkflowStore.deleteFailureEvents:356-363` (a shipped store) to
  keep stripping `WORKFLOW_FAILED` before every retry. That is a promise **the
  SPI does not make**. `WorkflowStore` is a public SPI with third-party
  implementations; a store that retained failure memos — or a future retry path
  that stopped deleting them — would be within contract and would silently
  reintroduce the exact race the predicate exists to close, in the one place
  nobody would look.
- A comment documenting a bug's unreachability ages into a comment documenting a
  bug. Mapping deletes the question.
- There is no cost: the predicate already has the status in hand.

Applied to **all three** copies, because the flaw was in all three:
`TerminalWait` (integration), the new `TestTerminalWait` (core), and
`maestro-test`'s **shipped** `TestWorkflowHandle.terminalEventLanded` — which the
review did not flag but which had the identical `anyMatch(COMPLETED || FAILED)`
and is the one downstream users inherit.

**Pinned**, not just asserted: `TestTerminalWaitTest` (5 tests) covers the window
itself, the stale-`WORKFLOW_FAILED` case, both matching events, the `TERMINATED`
exemption, and non-terminal statuses. Reverting only the mapping to the
either-event form reddens exactly one test
(`evidence/13-f3-pin-fails-without-mapping-raw.log`):

```
A run is finalised only when the event matching its status is in the log > a COMPLETED run is not satisfied by a leftover WORKFLOW_FAILED from an earlier attempt FAILED
5 tests completed, 1 failed
```

---

## F2 — release note

New `### Changed — maestro-test waits for the terminal event, not the terminal
status` under **Unreleased** in `docs/release-notes.md`, stating plainly that
`awaitCompletion(...)` / `getResult(...)` can now throw `TimeoutException` where
they previously returned, why (the row-then-event ordering), and that a timeout
there is an engine defect rather than a knob to widen. Verified against
`15b27dc` that **no signature changed** — `TimeoutException` was already declared
on both methods, so this is a behavioural note, not a source-compatibility break.

---

## F4 — docs, and the shipped-code shape that stays as it is

`docs/testing.md` taught the bug twice; both are fixed.

1. The Tips poll (was `:522`) is now `waitForParkedStatus`, explicitly scoped to
   **non-terminal** statuses — where it is correct, because parking is a *single*
   write — and given the missing timeout failure it silently lacked. It is
   followed by an explicit right/wrong pair for the terminal case and the rule
   for anyone polling a `WorkflowStore` directly.
2. "Inspecting the Event Log" now says to read the log only after
   `getResult`/`awaitCompletion` has returned, never after a terminal
   `getStatus()`, with the two-write reason.

### `WorkflowStub.startAndWait` — reported, deliberately unchanged

`maestro-spring-boot-starter/.../client/WorkflowStub.java:102` has the same
`status().isTerminal()` shape in **shipped main code**. It is **harmless as
written**, and I did not touch it:

- It reads `instance.get().output()` off **the same row it just gated on** — a
  single read of a single row. The output column is written by
  `transitionToTerminal` itself, in the same write that set the status, so by
  construction it is present whenever the gate passes.
- `grep -n 'getEvents' WorkflowStub.java` returns **nothing** — the class never
  touches the event log, so there is no second, lagging source to be
  inconsistent with (`evidence/17-greps.txt`).

The shape is only a defect when the gate and the assertion read **different**
stores of truth. Here they read one. Changing it would add a `getEvents` round
trip per poll to a shipped client API for no behavioural gain. Worth knowing,
worth leaving.

---

## Verification

| Evidence | Command | Result |
|---|---|---|
| `11-core-pin-BEFORE-statusonly-raw.log` | `:maestro-core:test --rerun-tasks --continue`, 300 ms gap injected + predicate reverted | `398 tests completed, 12 failed` |
| `12-core-pin-AFTER-samegap-raw.log` | same, fixed predicate, **same injection** | `BUILD SUCCESSFUL` |
| `13-f3-pin-fails-without-mapping-raw.log` | `--tests '*TestTerminalWaitTest*'`, F3 mapping reverted | `5 tests completed, 1 failed` |
| `14-core-test-rerun-raw.log` | `./gradlew :maestro-core:test --rerun-tasks` | `BUILD SUCCESSFUL in 54s` |
| `15-full-build-raw.log` | `./gradlew build` | `BUILD SUCCESSFUL in 2m`, 134 actionable tasks |
| `16-test-result-totals.txt` | XML aggregation, all modules | **`TOTAL tests=911 failures=0 errors=0 skipped=3`** |
| `17-greps.txt` | every number above, re-grepped | — |

**Honest notes.**

- `./gradlew build` reports `11 executed, 123 up-to-date`. The test tasks that
  genuinely **executed** are the ones downstream of the changed sources:
  `:maestro-integration-tests:test` (114 tests — this is what verifies the F3
  change to `TerminalWait`), `:maestro-test:test`,
  `:maestro-spring-boot-starter:test`, and all three
  `:maestro-samples:sample-loan-origination:*:test`. `:maestro-core:test` shows
  up-to-date there only because it had just been run with `--rerun-tasks` (row 4).
  The 911 total in `16-*` is aggregated from the on-disk XML across every module
  and is the number to trust.
- The 3 skipped tests are the opt-in chaos E2E suites
  (`ClusterBootSmokeIT`, `ChaosGoldenRunE2EIT`, `ChaosSoakE2EIT`), named in
  `16-*`. Pre-existing and unrelated.
- Gradle prints no test total on a green run, so `12-*` is attested by
  `BUILD SUCCESSFUL` rather than a count. It ran the identical task with
  `--rerun-tasks` immediately after `11-*`; the suite was 398 tests at that
  commit and is 403 at HEAD (the five new `TestTerminalWaitTest` pins).

## One thing the reviewer's framing understated

The finding was filed as "the sweep missed a module". The more useful lesson is
that **the sweep's search key was wrong**: it looked for `getEvents` after a
status gate. Two of the seven sites have no `getEvents` anywhere near them — they
call `retryWorkflow`, which deletes failure memos internally. The correct key is
"any observation *or mutation* of the event log gated on the instance row", and
finding those needs the experiment, not the grep. That is why the deterministic
injection is the load-bearing part of this round, and the audit was only its
starting point.
