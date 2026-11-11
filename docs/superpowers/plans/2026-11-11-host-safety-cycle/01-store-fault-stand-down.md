# Store-Fault Stand-Down Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A database fault on an active workflow abandons the local run and leaves the instance recoverable, instead of recording `FAILED` and compensating.

**Architecture:** The JDBC store classifies each `SQLException` and throws a new core exception, `StoreUnavailableException`, for anything the workflow's data did not cause. `WorkflowExecutor` wraps its store once in `StoreFaultGuard`, a reflective proxy that retries two idempotent operations briefly and then latches the run's `RunCancellation` with a new cause, so the abandon is sticky whatever workflow code does with the exception. The executor's catch arms route the latch to a new `AbandonReason.STORE_UNAVAILABLE`.

**Tech Stack:** Java 25, `maestro-core` (no Spring), `maestro-store-jdbc`, JUnit 5, Testcontainers Postgres for the one integration test.

**Spec:** `docs/superpowers/plans/2026-11-11-host-safety-cycle/00-spec.md` (R1–R5, R10; D3, D4)

## Global Constraints

- `maestro-core` must never import Spring.
- Jackson 3 (`tools.jackson`) only; JSpecify `@Nullable` on public APIs; no Lombok; Javadoc and a thread-safety note on every public class.
- `MaestroException` is sealed: a new subtype must be added to its `permits` clause.
- The three `MaestroControlFlowError`s must pass through every new catch and every reflective unwrap untouched: unwrap `InvocationTargetException`, check `instanceof Error` first, rethrow.
- No migration. Rolling-deploy safe against a27a275.
- Every new test must be run against the unfixed code and seen to fail on a positive assertion (a collected value), not on a timeout (`tasks/lessons.md`, 2026-08-03).
- Work in a worktree on branch `fix/store-fault-stand-down`; never edit the main checkout. Commit at every green step.

## Review Focus

1. Workflow code wraps an activity call in `try { … } catch (Exception e) { fallback(); }` and the memo lookup faults: the fallback must not run. (Task 3, test 3.)
2. The fault lands inside a `parallel()` branch, whose collector catches `Throwable`: the run is abandoned, the sibling's outcome is not recorded as a failure. (Task 4, test 2.)
3. The fault lands on a compensation step's own append while compensating a genuine failure: no `COMPENSATION_STEP_FAILED`, the instance stays `COMPENSATING`, recovery finishes it with each compensation recorded once. (Task 4, test 1.)
4. The append commits but the connection dies before the driver sees the acknowledgement, so the retry hits the unique index: the run stands down as a stale run and recovery replays the memo; the activity is not executed again. (Task 2, test 3.)
5. A payload Postgres rejects (SQLSTATE 22xxx) on a healthy database: the workflow still fails; it must not loop through recovery forever. (Task 1, test 2; Task 3, test 5.)

---

### Task 1: `StoreUnavailableException` and JDBC classification

**Files:**
- Create: `maestro-core/src/main/java/io/b2mash/maestro/core/exception/StoreUnavailableException.java`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/exception/MaestroException.java` (permits)
- Modify: `maestro-store-jdbc/src/main/java/io/b2mash/maestro/store/jdbc/AbstractJdbcWorkflowStore.java` (all 22 `catch (SQLException` sites; `UncheckedSqlException` at :1709 stays)
- Test: `maestro-store-jdbc/src/test/java/io/b2mash/maestro/store/jdbc/AbstractJdbcWorkflowStoreUnitTest.java`

**Interfaces:**
- Produces: `public final class StoreUnavailableException extends MaestroException` with constructor `(String message, Throwable cause)`.
- Produces: `static RuntimeException AbstractJdbcWorkflowStore.storeFault(String message, SQLException e)` — returns `UncheckedSqlException` when `e.getSQLState()` starts with `"22"` or `"23"`, otherwise `StoreUnavailableException`. A `null` SQLSTATE is a store fault.

- [ ] **Step 1: Write the failing tests** in a new `@Nested class FaultClassification`:

```java
@ParameterizedTest
@CsvSource(value = {"08006", "57P01", "40001", "40P01", "53300", "42703", "NULL"}, nullValues = "NULL")
void infrastructureFaultsAreStoreUnavailable(String sqlState) {
    var ex = AbstractJdbcWorkflowStore.storeFault("q", new SQLException("boom", sqlState));
    assertInstanceOf(StoreUnavailableException.class, ex);
    assertEquals(sqlState, ((SQLException) ex.getCause()).getSQLState());
}

@ParameterizedTest
@ValueSource(strings = {"22001", "22P02", "23502"})
void dataErrorsStayWorkflowFailures(String sqlState) {
    var ex = AbstractJdbcWorkflowStore.storeFault("q", new SQLException("boom", sqlState));
    assertInstanceOf(AbstractJdbcWorkflowStore.UncheckedSqlException.class, ex);
}

@Test
void poolTimeoutFromGetConnectionIsStoreUnavailable() {
    // dataSource.getConnection() throws SQLTransientConnectionException (no SQLSTATE)
    assertThrows(StoreUnavailableException.class, () -> store.getInstance("wf-1"));
}
```

- [ ] **Step 2: Run** `./gradlew :maestro-store-jdbc:test --tests '*AbstractJdbcWorkflowStoreUnitTest*'` — expect compile failure (`StoreUnavailableException` undefined).
- [ ] **Step 3: Create the exception**, add it to `permits`, add `storeFault`, and replace every `throw new UncheckedSqlException(msg, e)` with `throw storeFault(msg, e)`. Leave any site that first maps a unique violation to `DuplicateEventException`/`WorkflowAlreadyExistsException` mapping it first.
- [ ] **Step 4: Update the four existing assertions** in the same test file (`:149, :170, :185, :198`) that expect `UncheckedSqlException` from a connection-level failure to expect `StoreUnavailableException`; keep any that inject a class-22/23 state.
- [ ] **Step 5: Run** `./gradlew :maestro-store-jdbc:test :maestro-core:compileJava` — expect PASS.
- [ ] **Step 6: Commit** `feat(store): classify infrastructure SQL faults as StoreUnavailableException`

### Task 2: `StoreFaultGuard` and the sticky latch

**Files:**
- Create: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/StoreFaultGuard.java`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/context/RunCancellation.java`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/ParkProbe.java:92` (wrap the read in `StoreFaultGuard.advisory`)
- Test: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/StoreFaultGuardTest.java`
- Test: `maestro-core/src/test/java/io/b2mash/maestro/core/context/RunCancellationTest.java` (create if absent)

**Interfaces:**
- Consumes: `StoreUnavailableException` (Task 1).
- Produces in `RunCancellation`: `Cause.STORE_UNAVAILABLE` with rank 2 (same as `STALE`: first of the two wins, `TERMINATED` replaces either, either replaces `SHUTDOWN`); `boolean requestStoreUnavailable(String detail)`; `void throwIfStoreUnavailable(String workflowId)` throwing `StoreUnavailableException`; `throwIfRequested` gains the matching `case`.
- Produces in `StoreFaultGuard` (package-private, final, stateless):
  - `static WorkflowStore wrap(WorkflowStore delegate)` — a `java.lang.reflect.Proxy`. Wrapping an already wrapped store returns it unchanged.
  - `static <T> T advisory(Supplier<T> read)` — runs `read` with a `ScopedValue<Boolean>` set, under which the guard neither retries nor latches.
  - Behaviour of the proxy on `StoreUnavailableException` from the delegate: if the method name is `appendEvent` or `getEventBySequence`, retry up to 3 attempts total, sleeping 200 ms then 1 s (D3); an interrupt during the sleep restores the flag and ends the retries. When attempts are exhausted (or for any other method): if `WorkflowContext` is bound on this thread and the call is not advisory, call `ctx.cancellation().requestStoreUnavailable("<method>: <cause.toString()>")`; rethrow the last exception. Any other throwable passes through unwrapped; `Error` first.

- [ ] **Step 1: Write the failing tests.** `StoreFaultGuardTest` uses a `FaultyStore extends VersionedInMemoryStore` (same package, new, modelled on `ParkedProbeStoreOutageTest.OutageStore`) with `failNext(String method, int times)` throwing `StoreUnavailableException`:

```java
@Test void appendEventIsRetriedAndSucceedsOnThirdAttempt()   // failNext("appendEvent", 2): no throw, event stored once, latch cause() == null
@Test void appendEventExhaustsThenLatches()                  // failNext("appendEvent", 3): throws StoreUnavailableException, cause() == STORE_UNAVAILABLE
@Test void ambiguousCommitSurfacesAsDuplicate()              // delegate stores the event THEN throws once: second attempt throws DuplicateEventException, cause() == null
@Test void updateInstanceIsNotRetried()                      // failNext("updateInstance", 1): throws, delegate saw exactly 1 call, latched
@Test void advisoryReadNeitherRetriesNorLatches()            // inside advisory(): 1 call, cause() == null
@Test void noBoundContextMeansNoLatchAndNoNpe()              // called off a workflow thread: throws, nothing else
@Test void controlFlowErrorPassesThroughUnwrapped()          // delegate throws ExecutorShutdownException: assertThrows(ExecutorShutdownException.class, …), 1 call
@Test void wrapIsIdempotent()                                // assertSame(g, StoreFaultGuard.wrap(g))
```

`RunCancellationTest`: `requestStoreUnavailable` after `requestStale` returns `false` and `reason()` is still the stale detail; `requestTermination` after it returns `true`; `throwIfStoreUnavailable` throws only for that cause; `requestStoreUnavailable` replaces `SHUTDOWN`.

- [ ] **Step 2: Run** `./gradlew :maestro-core:test --tests '*StoreFaultGuardTest*' --tests '*RunCancellationTest*'` — expect compile failure.
- [ ] **Step 3: Implement** `RunCancellation` changes, then `StoreFaultGuard`. Bind the test context with `ScopedValue.where(WorkflowContext.scopedValue(), ctx)`.
- [ ] **Step 4: Change `ParkProbe.read`** to call `StoreFaultGuard.advisory(read)` in place of `read.get()`.
- [ ] **Step 5: Run** the two test classes plus `*ParkedProbeStoreOutageTest*` — expect PASS.
- [ ] **Step 6: Commit** `feat(core): StoreFaultGuard retries outcome appends and latches the run`

### Task 3: Executor routes the latch to an abandon

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/observe/AbandonReason.java`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java` (full constructor; `:2221`; catch chain `:2255-2350`; `failAndCompensate` `:2623-2670`; new `handleStoreUnavailable` beside `handleNodeError` `:2461`)
- Modify: the six `throwIfStale` call sites — `ActivityInvocationHandler.java:638`, `DefaultWorkflowOperations.java:312`, `SignalManager.java:620`, `WorkflowExecutor.java:2221, 2695, 2731`
- Test: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/StoreFaultStandDownTest.java`
- Test: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/ParkedProbeStoreOutageTest.java`

**Interfaces:**
- Consumes: `StoreFaultGuard.wrap`, `RunCancellation.Cause.STORE_UNAVAILABLE`, `throwIfStoreUnavailable` (Task 2); `FaultyStore` (Task 2 test fixture).
- Produces: `AbandonReason.STORE_UNAVAILABLE` (Javadoc: "the store was unreachable while this run was active; nothing recorded, the instance stays recoverable; expect it during a database outage, alert if it persists").
- Produces: `private void handleStoreUnavailable(WorkflowContext ctx, @Nullable Throwable cause)` — one WARN naming the workflow and `ctx.cancellation().reason()`, then `emitRunAbandoned(ctx, AbandonReason.STORE_UNAVAILABLE)`.

Executor changes, exactly:
1. The full constructor assigns `this.store = StoreFaultGuard.wrap(store)` before any collaborator is built from it. Verify every collaborator (`ActivityProxyFactory`, `SignalManager`, `DefaultWorkflowOperations`, `InstanceStatusWriter`, `SagaManager`, `TimerManager`) receives the wrapped reference; `store()` accessors return the wrapped one.
2. At each of the six `throwIfStale` sites add `throwIfStoreUnavailable(workflowId)` on the next line.
3. In the run's catch chain add `catch (StoreUnavailableException e) { handleStoreUnavailable(ctx, e); }` ahead of `catch (Exception e)`; in `catch (Exception e)` and in the non-control-flow, non-node branch of `catch (Error e)`, test `ctx.cancellation().cause() == Cause.STORE_UNAVAILABLE` first and call `handleStoreUnavailable` instead of `failAndCompensate`.
4. In `failAndCompensate` add the same arm beside the `DuplicateEventException | StaleRunException | FencedWriteException` arm (`:2651`).

- [ ] **Step 1: Write the failing tests** (`StoreFaultStandDownTest`; a recording `EngineObserver` collects `runAbandoned` reasons; assert on collected values, never on `await(condition)` alone):

```java
@Test void outcomeAppendFaultAbandonsInsteadOfFailing()
// 2-step saga workflow; FaultyStore fails every appendEvent of ACTIVITY_COMPLETED for step 2.
// assert: abandoned == [STORE_UNAVAILABLE]; instance status RUNNING; events contain no WORKFLOW_FAILED and
// no COMPENSATION_*; compensation activity invocations == 0.
// then heal + executor.recoverWorkflows(regs): status COMPLETED; step-1 executions == 1; step-2 executions == 2.

@Test void singleBlipOnOutcomeAppendIsAbsorbed()
// failNext("appendEvent", 1): status COMPLETED; step executions == 1; abandoned == [].

@Test void swallowedFaultDoesNotRunTheFallback()
// workflow: try { a.primary(); } catch (Exception e) { a.fallback(); }
// fail every getEventBySequence at seq 1. assert: fallback executions == 0; abandoned == [STORE_UNAVAILABLE];
// status RUNNING; after heal + recover: primary executions == 1, fallback == 0, COMPLETED.

@Test void statusWriteFaultWhileParkingAbandons()
// workflow awaits a signal; fail updateInstance once at the WAITING_SIGNAL transition.
// assert: abandoned == [STORE_UNAVAILABLE]; no WORKFLOW_FAILED event.

@Test void dataErrorStillFailsTheWorkflow()
// store throws a plain RuntimeException("22001") on appendEvent: status FAILED; abandoned == [].

@Test void workflowThatSwallowsAndReturnsIsNotCompleted()
// workflow catches Exception around its only activity and returns "ok"; persistent append fault.
// assert: status RUNNING (not COMPLETED); abandoned == [STORE_UNAVAILABLE].
```

Add to `ParkedProbeStoreOutageTest`: make `OutageStore` throw `StoreUnavailableException`, and assert in each existing case that the recorded abandon list is empty (R4).

- [ ] **Step 2: Run** `./gradlew :maestro-core:test --tests '*StoreFaultStandDownTest*'` — expect FAIL: the first test reports `expected: <[STORE_UNAVAILABLE]> but was: <[]>` and status `FAILED`.
- [ ] **Step 3: Implement** the four executor changes above.
- [ ] **Step 4: Run** `./gradlew :maestro-core:test` — expect PASS, whole module.
- [ ] **Step 5: Commit** `fix(core): a store fault abandons the run instead of failing the workflow`

### Task 4: Compensation and parallel branches

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/saga/SagaManager.java:468` and every other multi-catch found by the grep below
- Test: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/StoreFaultStandDownTest.java` (new `@Nested class SagaAndParallel`)

**Interfaces:**
- Consumes: everything from Tasks 1–3.

- [ ] **Step 1: List the sites.** Run `grep -rn "StaleRunException | FencedWriteException\|catch (Throwable" maestro-core/src/main/java`. Every site that rethrows the stale trio so a run can stand down, and every `catch (Throwable)` that collects branch outcomes, must also rethrow `StoreUnavailableException` (for a collector: rethrow when `ctx.cancellation().cause() == Cause.STORE_UNAVAILABLE` or the throwable is a `StoreUnavailableException`, beside its existing `MaestroControlFlowError` check). Record the site list in the commit message.
- [ ] **Step 2: Write the failing tests:**

```java
@Test void faultOnCompensationAppendLeavesCompensatingAndRecovers()
// 3-step saga, step 3 throws a business exception; fail the COMPENSATION_STEP_COMPLETED append of the first
// compensation persistently. assert: no COMPENSATION_STEP_FAILED event; status COMPENSATING;
// abandoned == [STORE_UNAVAILABLE]. heal + recover: status FAILED; exactly one COMPENSATION_STEP_COMPLETED
// per compensable step; each compensation activity recorded once.

@Test void faultInsideParallelBranchAbandonsTheRun()
// parallel(branchA, branchB); branchA's outcome append faults persistently.
// assert: abandoned == [STORE_UNAVAILABLE]; status RUNNING; no WORKFLOW_FAILED; no compensation ran.
// heal + recover: COMPLETED.
```

- [ ] **Step 3: Run** them — expect FAIL (`COMPENSATION_STEP_FAILED` present; status `FAILED`).
- [ ] **Step 4: Implement** the rethrows at each listed site.
- [ ] **Step 5: Run** `./gradlew :maestro-core:test` — expect PASS.
- [ ] **Step 6: Commit** `fix(core): store faults stand down compensation and parallel branches`

### Task 5: Real-Postgres proof, observer wiring and docs

**Files:**
- Create: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/StoreOutageMidStepIT.java` (read `maestro-integration-tests/SPEC.md` and `support/` for fixtures first)
- Modify: `maestro-spring-boot-starter/.../observe/MicrometerEngineObserver.java` only if it enumerates `AbandonReason` values by hand
- Modify: `docs/failure-scenarios.md` (S2.3: expected now matches), `docs/operations.md` (new subsection "Store outages": what you see, the `STORE_UNAVAILABLE` abandon, recovery within one `maestro.recovery.poll-interval`, the at-least-once consequence for the in-flight activity), `docs/observability.md` (reason added to the abandon meter's tag values), `docs/open-issues.md` (file as Issue 34, resolved, linking Issue 20), `docs/release-notes.md` (Unreleased), `CLAUDE.md` (Exceptions bullet: one sentence on `StoreUnavailableException` and the latch)

- [ ] **Step 1: Write the failing integration test.** A `DataSource` decorator whose `getConnection()` throws `SQLTransientConnectionException` while a flag is set. Workflow: `reserve()` (compensable) then `charge()`; `charge()`'s body sets the flag, a scheduler clears it after 3 s.

```java
@Test void poolOutageAfterActivityBodyNeverFailsTheWorkflow()
// assert: final status COMPLETED within 2 poll intervals; zero WORKFLOW_FAILED events;
// releaseReservation invocations == 0; charge invocations in {1, 2}; observer saw STORE_UNAVAILABLE
// exactly when charge invocations == 2.
```

- [ ] **Step 2: Prove it can fail:** `git stash` Tasks 3–4's production changes (or revert on a scratch copy), run `./gradlew :maestro-integration-tests:test --tests '*StoreOutageMidStepIT*'`, expect `FAILED` status and one release; restore.
- [ ] **Step 3: Run it 3 times** with `--rerun-tasks` — expect 3 PASS.
- [ ] **Step 4: Write the docs.**
- [ ] **Step 5: Run** `./gradlew build` — expect `BUILD SUCCESSFUL`.
- [ ] **Step 6: Commit** `test,docs: store outage mid-step proof on real Postgres; Issue 34`

## Gate before the PR

`./gradlew build` green; loan-origination E2E scenarios 1–10 and the chaos PR-gate ×3 green on a dispatched `e2e-nightly.yml` run on the branch (requires PR #47 merged first); `code-reviewer` agent run on the branch diff.
