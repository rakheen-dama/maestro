# Plan 03 — Activity Semantics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the engine's live-activity path honest and stoppable: an explicit, tested contract for catching activity failures in workflow code (F2); enforced `startToCloseTimeout` and truthful retry-durability docs (C11); terminate that stops live work and abandons a retry backoff (C6); a dual-execution loser that stands down at its first collision or at any terminal fresh read (C5); and a compensation loop that re-checks for terminate before every entry (C7).

**Architecture:** One new per-run in-memory object, `RunCancellation`, is reachable from every `WorkflowContext` (including `parallel()` and parallel-compensation branch contexts) and from the executor's `RunningWorkflow` record; `terminateWorkflow` and `shutdown()` set it on the owning node, and `RetryExecutor` consults it before each attempt and during each backoff wait. Timeout enforcement lives in `ActivityInvocationHandler`, which runs each attempt on its own virtual thread and bounds it with `Future.get(startToCloseTimeout)`; a timed-out attempt becomes an ordinary retryable failure (`ActivityTimeoutException`). A new `StaleRunException extends MaestroException` carries "another runner finalised this workflow" from `InstanceStatusWriter` and `SagaManager` to the executor's existing `STALE_RUN` stand-down arm, which already handles `DuplicateEventException`; the activity live path stops adopting a winner's payload and rethrows the duplicate instead.

**Tech Stack:** Java 25 (virtual threads, `FutureTask`, `CountDownLatch`), JUnit 5, Awaitility, Testcontainers 2 (Postgres 16) via `maestro-integration-tests`, `maestro-test`'s `TestWorkflowEnvironment` for the sample test.

**Spec:** [`00-spec-independent-assessment.md`](00-spec-independent-assessment.md) §3a rows C5, C6, C7, C11; §4 row F2. Plan index and shared rules: [`README.md`](README.md).

## Global Constraints

See [`README.md` § Global Constraints](README.md#global-constraints). Two restated because this plan leans on them: the sealed `MaestroControlFlowError` `permits` clause stays exactly three types (this plan adds **no** control-flow `Error`; `StaleRunException` and `ActivityTimeoutException` are ordinary `MaestroException`s and are added to **its** sealed `permits` list at `maestro-core/src/main/java/io/b2mash/maestro/core/exception/MaestroException.java:30-45`); and every fix below ships a unit pin in `maestro-core` **and** an `@Tag("integration")` pin against real Postgres in `maestro-integration-tests`.

**Ordering dependency:** Plan 02 must have landed first. Task 1 references Plan 02's replay-reconstruction type `ReconstructedActivityFailure` (verify with the grep in Task 1 Step 1 before writing code). Plan 01 renumbers parts of `SagaManager`; all `file:line` citations here are as of `main` @ 4eb28e9 — re-read the cited region before editing.

## Review Focus

Inputs the spec implies but no existing test exercises; each line's pin is added to the owning task in that task's step style.

1. **Terminate while a live activity is mid-retry backoff** (README Review Focus 3) — the backoff wait is abandoned within seconds, not slept through, and no further attempt runs. → Task 2.
2. **Terminate while a `parallel()` branch's activity is in retry backoff** — branch contexts are *new* `WorkflowContext`s (`DefaultWorkflowOperations.java:591-601`); they must share the parent run's `RunCancellation`, or a terminate stops the trunk and leaves two branches retrying. → Task 2.
3. **A timed-out attempt whose thread ignores interruption** (blocking JDBC, a `catch (InterruptedException) { continue; }` loop) later returns a value — that late value must never be memoized or returned to workflow code once the timeout was recorded. → Task 3.
4. **The dual-execution loser *fails* its own attempt** after the winner persisted `ACTIVITY_COMPLETED` at the same sequence — the loser's `ACTIVITY_FAILED` append must stand down, never be reported into workflow code as a failure that contradicts the winner. → Task 4.
5. **A cross-node (non-owning) terminate during a sequential compensation of manually registered `Runnable`s** — no proxy is involved, so Task 2's in-memory flag never sees it; only the per-entry store read can. → Task 5.

---

## Decisions already made (recorded, not re-opened)

| # | Decision | Where it lands |
|---|---|---|
| F2 | **Keep the `ActivityExecutionException` wrapper.** Temporal wraps too, and replay reconstruction (`ActivityInvocationHandler.reconstructFailure`, `:306-324`) depends on the wrapper to carry a cause it may not be able to instantiate. Make the contract explicit: `ActivityExecutionException.causedBy(Class)`, correct the sample, PRD and concepts, and add the sample test that would have caught this. | Task 1 |
| C11 | **Do not persist attempt counts.** Enforce `startToCloseTimeout` per attempt on a virtual thread with `Future.get(timeout)`; timeout → `ActivityTimeoutException`, retryable under the policy like any other exception. Docs say honestly: the attempt counter is in-memory; the durable guarantee is that a *completed* attempt is never re-executed. | Task 3 (enforcement + docs), Task 2 (backoff interrupt source) |
| C6 | **In-memory per-run flag**, named `RunCancellation`, held by `WorkflowContext` (so `ActivityInvocationHandler` can reach it via `WorkflowContext.current()`) and by `WorkflowExecutor.RunningWorkflow` (so `terminateWorkflow` can set it). Checked before every live attempt and during every backoff wait. Cross-node terminate is already covered by the status-write CAS and Task 5's per-entry read; **no store read per live activity is added.** | Task 2 |
| C5 | `InstanceStatusWriter` treats **any** terminal fresh read as "another runner owns this; stand down" (`STALE_RUN`). The activity live path **stops adopting** a winner's payload on `DuplicateEventException`: the loser accepts the winner's *record* as truth (writes nothing that contradicts it) and stands down; it never feeds the adopted *value* into workflow code, because the winner is alive and ahead — every later live activity would collide again or, worse, run a side effect the winner also runs. | Task 4 |
| C7 | `executeSequential` re-reads the instance before **each** live entry (one store read per compensation entry) via the same guard `transitionToCompensating` uses; `TERMINATED` → `WorkflowTerminatedException`, other terminal → `StaleRunException`. Fix the Javadoc that admits the race and the release-notes claim. | Task 5 |

**Admin terminate routing (verified).** The dashboard's Terminate button publishes a `$maestro:terminate` message on the service's signals topic. On whichever node the consumer group delivers it, `SignalSubscriptionRunner.handleSignal` (`maestro-spring-boot-starter/.../config/SignalSubscriptionRunner.java:94-101`) diverts `$maestro:*` to `AdminCommandDispatcher.dispatch` (`.../config/AdminCommandDispatcher.java:101-115`), whose `dispatchTerminate` (`:149-157`) calls `WorkflowExecutor.terminateWorkflow(workflowId, reason)` (`maestro-core/.../engine/WorkflowExecutor.java:730-780`). That method writes `TERMINATED` by CAS first, then — only if `runningWorkflows.containsKey(workflowId)` (`:774-776`) — evicts the local run via `parkingLot.abandonWorkflow`. So the command is **not** routed to the owner; it lands on an arbitrary node, and the owner converges via guards. Task 2's flag is therefore set only when the receiving node *is* the owner; the non-owner case is Task 4/5's job.

**How `ExecutorShutdownException` reaches a parked workflow (verified, mirrored in Task 2).** `WorkflowExecutor.shutdown()` (`:1099-1152`) never interrupts workflow threads; it calls `parkingLot.shutdown()`, which completes every registered `CompletableFuture` exceptionally with the `Error` and sets a sticky `shuttingDown` flag so late parks fail fast (`ParkingLot.java:160-182`). Terminate is the per-workflow analogue: `abandonWorkflow` (`:324-335`) with a sticky `terminatedWorkflows` entry. Neither path uses `Thread.interrupt()`. Task 2 mirrors this with a latch, not an interrupt: the backoff wait is `RunCancellation.awaitRequested(backoff)`, which returns early when either `terminateWorkflow` or `shutdown()` requests, and the `InterruptedException` branch (which the engine never triggers) re-checks the flag before falling back to the old "activity failure" outcome.

---

## File structure

| Path | Responsibility | Tasks |
|---|---|---|
| `maestro-core/src/main/java/io/b2mash/maestro/core/exception/ActivityExecutionException.java` | + `causedBy(Class)` | 1 |
| `maestro-core/src/main/java/io/b2mash/maestro/core/exception/ActivityTimeoutException.java` | **new** — per-attempt timeout failure | 3 |
| `maestro-core/src/main/java/io/b2mash/maestro/core/exception/StaleRunException.java` | **new** — "another runner finalised this workflow" | 4 |
| `maestro-core/src/main/java/io/b2mash/maestro/core/exception/MaestroException.java` | sealed `permits` += the two above | 3, 4 |
| `maestro-core/src/main/java/io/b2mash/maestro/core/context/RunCancellation.java` | **new** — per-run terminate/shutdown latch | 2 |
| `maestro-core/src/main/java/io/b2mash/maestro/core/context/WorkflowContext.java` | holds a `RunCancellation`; shared into branch contexts | 2 |
| `maestro-core/src/main/java/io/b2mash/maestro/core/retry/RetryExecutor.java` | consults `RunCancellation` before attempts and in backoff | 2 |
| `maestro-core/src/main/java/io/b2mash/maestro/core/engine/ActivityInvocationHandler.java` | passes cancellation; bounds each attempt; stops adopting winners | 2, 3, 4 |
| `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java` | sets cancellation on terminate/shutdown; stale-run arms widened | 2, 4, 5 (Javadoc) |
| `maestro-core/src/main/java/io/b2mash/maestro/core/engine/DefaultWorkflowOperations.java` | branch contexts share cancellation | 2 |
| `maestro-core/src/main/java/io/b2mash/maestro/core/engine/InstanceStatusWriter.java` | terminal fresh read → `StaleRunException` | 4 |
| `maestro-core/src/main/java/io/b2mash/maestro/core/saga/SagaManager.java` | branch contexts share cancellation; stale-run arms; per-entry guard | 2, 4, 5 |
| `maestro-core/src/main/java/io/b2mash/maestro/core/annotation/ActivityStub.java` | `startToCloseTimeout` Javadoc | 3 |
| `maestro-samples/sample-payment-gateway/**` | correct catch pattern; `nonRetryableExceptions`; first test | 1 |
| `docs/concepts.md`, `docs/maestro-prd.md`, `docs/maestro-architecture.md`, `README.md`, `maestro-samples/README.md`, `docs/admin.md`, `docs/release-notes.md`, `docs/open-issues.md` | contract + honesty fixes | 1, 2, 3, 5 |
| `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/` | `EnginePostgresTerminateIT`, `EnginePostgresActivityTimeoutIT`, `EnginePostgresDualExecutionIT` (**new**); `EnginePostgresSagaIT` (+1 test) | 2, 3, 4, 5 |
| `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/workflows/{TestWorkflows,CountingActivities}.java` | new fixtures | 2, 3 |

---

### Task 1: F2 — explicit activity-failure contract, correct sample, first sample test

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/exception/ActivityExecutionException.java` (whole file, 36 lines)
- Create: `maestro-core/src/test/java/io/b2mash/maestro/core/exception/ActivityExecutionExceptionTest.java`
- Modify: `maestro-samples/sample-payment-gateway/src/main/java/io/b2mash/maestro/samples/payment/workflow/PaymentProcessingWorkflow.java:14-20` (class Javadoc), `:25-33` (provider stub), `:49-67` (`process`)
- Modify: `maestro-samples/sample-payment-gateway/build.gradle.kts:7-21` (add test deps)
- Create: `maestro-samples/sample-payment-gateway/src/test/java/io/b2mash/maestro/samples/payment/workflow/PaymentProcessingWorkflowTest.java`
- Modify: `docs/maestro-prd.md:236-256`, `docs/concepts.md` (insert after the Retry policy table, `:123-138`), `maestro-samples/README.md:118-123`, `docs/release-notes.md` (`## Unreleased`)

**Interfaces:**
- Consumes: Plan 02's `io.b2mash.maestro.core.exception.ReconstructedActivityFailure` with `String originalClassName()` (the cause installed on replay when the original exception type cannot be instantiated). If Plan 02 shipped a different accessor name, use it; the test in Step 2 fixes the *behaviour*, not the accessor.
- Produces: `public boolean ActivityExecutionException.causedBy(Class<? extends Throwable> type)` — `true` when `type.isInstance(getCause())`, or when the cause is a `ReconstructedActivityFailure` whose `originalClassName()` names `type` or a subtype of it (resolved with `Class.forName(name, false, Thread.currentThread().getContextClassLoader())`; if the class cannot be loaded, exact name equality). Task 3's `ActivityTimeoutException` relies on this working on replay without a `(String)` constructor.

- [ ] **Step 1: Confirm Plan 02 has landed**

Run: `grep -rln "class ReconstructedActivityFailure" maestro-core/src/main/java`
Expected: exactly one file. If none, stop: execute Plan 02 first.

- [ ] **Step 2: Write the failing unit tests for `causedBy`**

`maestro-core/src/test/java/io/b2mash/maestro/core/exception/ActivityExecutionExceptionTest.java`:

```java
class ActivityExecutionExceptionTest {

    static class DomainException extends RuntimeException {
        DomainException(String m) { super(m); }
    }
    static class SpecificDomainException extends DomainException {
        SpecificDomainException(String m) { super(m); }
    }

    @Test
    @DisplayName("causedBy matches the live cause's exact type")
    void causedBy_matchesExactCauseType() {
        var e = new ActivityExecutionException("wf", "pay.charge", new DomainException("declined"));
        assertTrue(e.causedBy(DomainException.class));
    }

    @Test
    @DisplayName("causedBy matches a supertype of the cause")
    void causedBy_matchesSupertypeOfCause() {
        var e = new ActivityExecutionException("wf", "pay.charge", new SpecificDomainException("declined"));
        assertTrue(e.causedBy(DomainException.class));
        assertTrue(e.causedBy(RuntimeException.class));
    }

    @Test
    @DisplayName("causedBy is false for an unrelated type")
    void causedBy_falseForUnrelatedType() {
        var e = new ActivityExecutionException("wf", "pay.charge", new IllegalStateException("x"));
        assertFalse(e.causedBy(DomainException.class));
    }

    @Test
    @DisplayName("causedBy matches a replay-reconstructed failure by its original class name")
    void causedBy_matchesReconstructedFailureByOriginalClassName() {
        // Plan 02: the cause installed when replay cannot instantiate the original type.
        var reconstructed = new ReconstructedActivityFailure(
                SpecificDomainException.class.getName(), "declined");   // adapt ctor to Plan 02's
        var e = new ActivityExecutionException("wf", "pay.charge", reconstructed);
        assertTrue(e.causedBy(SpecificDomainException.class));
        assertTrue(e.causedBy(DomainException.class), "subtype relation is resolved when the class loads");
        assertFalse(e.causedBy(IllegalStateException.class));
    }
}
```

- [ ] **Step 3: Run to verify they fail**

Run: `./gradlew :maestro-core:test --tests '*ActivityExecutionExceptionTest' --console=plain`
Expected: compilation error `cannot find symbol: method causedBy`.

- [ ] **Step 4: Implement `causedBy` in `ActivityExecutionException.java`**

Signature: `public boolean causedBy(Class<? extends Throwable> type)`. Javadoc must state the contract from Interfaces and show the canonical pattern:

```java
try { provider.charge(...); }
catch (ActivityExecutionException e) {
    if (e.causedBy(PaymentDeclinedException.class)) { ... } else { throw e; }
}
```

Also update the class Javadoc (`:3-7`) to say the activity's own exception is *never* delivered unwrapped to workflow code, live or on replay, and why (the replay cause may be a reconstruction).

- [ ] **Step 5: Run to verify they pass**

Run: `./gradlew :maestro-core:test --tests '*ActivityExecutionExceptionTest' --console=plain`
Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 6: Add test dependencies to the payment sample**

In `maestro-samples/sample-payment-gateway/build.gradle.kts` `dependencies {}` add, mirroring `maestro-samples/sample-loan-origination/underwriting-service/build.gradle.kts:42-43`:

```kotlin
testImplementation(project(":maestro-test"))
testImplementation(libs.spring.boot.starter.test)
```

- [ ] **Step 7: Write the failing sample test**

`maestro-samples/sample-payment-gateway/src/test/java/io/b2mash/maestro/samples/payment/workflow/PaymentProcessingWorkflowTest.java` — pattern after `UnderwritingWorkflowTest` (`TestWorkflowEnvironment.create()`, `registerActivities`, `startWorkflow`, `getResult(type, timeout)`, `env.shutdown()` in `@AfterEach`). Fixtures inside the test class: `CountingProvider implements PaymentProviderActivities` (counts calls; `"DECLINE_ME"` → `throw new PaymentDeclinedException("Card permanently declined")`; otherwise returns `new PaymentConfirmation("txn-1")`), `RecordingMessaging implements PaymentMessagingActivities` (collects `PaymentResult`s).

```java
@Test
void declinedCard_yieldsDeclinedResultAfterExactlyOneProviderAttempt() throws TimeoutException {
    var request = new PaymentRequest("order-1", "DECLINE_ME", new BigDecimal("19.99"));
    var handle = env.startWorkflow("payment-order-1", PaymentProcessingWorkflow.class, request);

    var result = handle.getResult(PaymentProcessingResult.class, Duration.ofSeconds(5));

    assertFalse(result.success());
    assertEquals("Card permanently declined", result.reason());
    assertNull(result.transactionId());
    assertEquals(1, provider.calls.get(), "a declined card is non-retryable: exactly one provider attempt");
    assertEquals(List.of(PaymentResult.failed("Card permanently declined")), messaging.published);
    assertEquals(WorkflowStatus.COMPLETED, handle.getStatus(),
            "a decline is a business outcome, not a workflow failure");
}

@Test
void approvedCard_yieldsSuccessAndPublishesTheTransaction() throws TimeoutException {
    var request = new PaymentRequest("order-2", "VISA-OK", new BigDecimal("5.00"));
    var handle = env.startWorkflow("payment-order-2", PaymentProcessingWorkflow.class, request);

    var result = handle.getResult(PaymentProcessingResult.class, Duration.ofSeconds(5));

    assertTrue(result.success());
    assertEquals("txn-1", result.transactionId());
    assertEquals(1, provider.calls.get());
    assertEquals(List.of(PaymentResult.success("txn-1")), messaging.published);
}
```

- [ ] **Step 8: Run to verify the decline test fails for the right reason**

Run: `./gradlew :maestro-samples:sample-payment-gateway:test --console=plain`
Expected: `declinedCard_…` fails with `java.util.concurrent.TimeoutException` from `getResult` (the wrapped `PaymentDeclinedException` is not caught; the workflow is in its first 5 s retry backoff), and `provider.calls` would read `1` only because the backoff is longer than the wait. `approvedCard_…` passes. Note: the `@AfterEach` `env.shutdown()` may take up to the test environment's shutdown timeout on the RED run because the run is mid-backoff; Task 2 shortens this. The RED run is allowed to take ~35 s.

- [ ] **Step 9: Fix `PaymentProcessingWorkflow.java`**

- `:25-33` provider stub: add `nonRetryableExceptions = PaymentDeclinedException.class` to its `@RetryPolicy`.
- `:49-67` `process`: `catch (ActivityExecutionException e)`; if `e.causedBy(PaymentDeclinedException.class)`, publish `PaymentResult.failed(e.getCause().getMessage())` and return `PaymentProcessingResult.declined(e.getCause().getMessage())`; otherwise `throw e`. Import `io.b2mash.maestro.core.exception.ActivityExecutionException`.
- `:14-20` class Javadoc: replace the sentence about "caught" with the `causedBy` pattern and state that the decline is listed as non-retryable so it costs exactly one provider call.

- [ ] **Step 10: Run to verify both sample tests pass**

Run: `./gradlew :maestro-samples:sample-payment-gateway:test --console=plain`
Expected: `BUILD SUCCESSFUL`, 2 tests passed, wall time under 10 s.

- [ ] **Step 11: Fix the documentation**

- `docs/maestro-prd.md:236-256`: replace `catch (PaymentDeclinedException e)` with the `catch (ActivityExecutionException e) { if (e.causedBy(PaymentDeclinedException.class)) … else throw e; }` form, and add `nonRetryableExceptions = PaymentDeclinedException.class` to the `provider` stub shown above it. Keep the comments' meaning.
- `docs/concepts.md`: after the Retry policy table and backoff formula (`:138`), insert `### Handling activity failures in workflow code` containing: (1) the rule — an activity's exception always reaches the workflow method wrapped in `ActivityExecutionException`, live and on replay, with the original available as `getCause()` and tested with `causedBy(Class)`; (2) the canonical `try/catch` pattern above; (3) why wrapping is kept (replay may have to *reconstruct* the cause from its recorded class name; `causedBy` handles that case); (4) the retry interaction — an exception you intend to catch as a business outcome belongs in `nonRetryableExceptions`, otherwise the policy retries it to exhaustion first (30 attempts with up-to-10-minute backoff in the payment sample's case). Add `ActivityExecutionException` to the exceptions column of the `WorkflowContext` API reference (`:492-510`) is **not** needed; it is not a `WorkflowContext` method.
- `maestro-samples/README.md:118-123`: after "Payment gateway immediately declines", add "(`PaymentDeclinedException` is declared non-retryable on the provider stub, so no retry happens; the workflow catches it as `ActivityExecutionException` and checks `causedBy`)".
- `docs/release-notes.md` `## Unreleased`: add `### Fixed — Activity failures: explicit catch contract, payment sample no longer retries a declined card 30×` describing `causedBy`, the sample fix and the new sample test; cite spec row F2.

- [ ] **Step 12: Commit**

```bash
git add maestro-core/src/main/java/io/b2mash/maestro/core/exception/ActivityExecutionException.java \
        maestro-core/src/test/java/io/b2mash/maestro/core/exception/ActivityExecutionExceptionTest.java \
        maestro-samples/sample-payment-gateway docs/maestro-prd.md docs/concepts.md maestro-samples/README.md docs/release-notes.md
git commit -m "fix(core,samples): ActivityExecutionException.causedBy; payment sample catches the wrapper and marks declines non-retryable (F2)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: C6 + C11(c) — `RunCancellation`: terminate stops live work and abandons a retry backoff; shutdown abandons a backoff

**Files:**
- Create: `maestro-core/src/main/java/io/b2mash/maestro/core/context/RunCancellation.java`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/context/WorkflowContext.java:74-82` (field), `:130-149` (constructor), new accessor
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/retry/RetryExecutor.java:41-97` (loop), `:154-167` (`sleep`)
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/ActivityInvocationHandler.java:392-397` (pass cancellation)
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java:1361-1371` (ctx), `:1392` (`RunningWorkflow`), `:2007-2008` (record), `:774-776` (terminate), `:1121` (shutdown), Javadoc `:686-701`, `:1095-1097`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/DefaultWorkflowOperations.java:591-601`; `maestro-core/src/main/java/io/b2mash/maestro/core/saga/SagaManager.java:410-420` (branch contexts)
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/exception/WorkflowTerminatedException.java:14-22`
- Test: `maestro-core/src/test/java/io/b2mash/maestro/core/retry/RetryExecutorTest.java`, `.../engine/WorkflowExecutorTerminateTest.java`, `.../engine/WorkflowExecutorShutdownTest.java`
- Create: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/EnginePostgresTerminateIT.java`; add fixture `TestWorkflows.SlowRetryWorkflow`
- Modify: `docs/admin.md:238-242`, `docs/release-notes.md`

**Interfaces:**
- Produces:
  ```java
  package io.b2mash.maestro.core.context;
  public final class RunCancellation {                       // thread-safe; one per run, shared by all branch contexts
      public enum Cause { TERMINATED, SHUTDOWN }
      public RunCancellation();
      public boolean requestTermination(@Nullable String reason);   // first request wins; returns true iff this call set it
      public boolean requestShutdown();
      public boolean isRequested();
      public @Nullable Cause cause();
      public @Nullable String reason();
      public void throwIfTerminated(String workflowId);     // throws WorkflowTerminatedException iff cause == TERMINATED
      public void throwIfRequested(String workflowId);      // WorkflowTerminatedException | ExecutorShutdownException
      public boolean awaitRequested(Duration timeout) throws InterruptedException;  // true iff requested before timeout
  }
  ```
  `WorkflowContext.cancellation()` returns the run's instance; new constructor `WorkflowContext(UUID, String, UUID, String, String, String, int, boolean, @Nullable WorkflowOperations, RunCancellation)`; the existing 9-arg constructor delegates with `new RunCancellation()` (tests and `maestro-test` keep compiling).
  `RetryExecutor.executeWithRetry(RetryPolicy, Callable<T>, String activityName, String workflowId, RunCancellation cancellation)`; the existing 4-arg overload delegates with `new RunCancellation()`.
  `WorkflowExecutor.RunningWorkflow(Thread thread, WorkflowInstance instance, String workflowType, Object workflowImpl, RunCancellation cancellation)`.
- Semantics (pin these, do not soften): `throwIfTerminated` runs **before every attempt including the first** — terminate means stop now. Shutdown is **not** checked before an attempt (shutdown's contract, `WorkflowExecutor.java:1095-1097`, lets in-flight work drain). Both are honoured by the backoff wait: it returns as soon as either is requested, and `throwIfRequested` raises the matching control-flow `Error`. On `InterruptedException` during the wait: restore the interrupt flag, `throwIfRequested(workflowId)`, and only if nothing was requested fall back to today's outcome (`ActivityExecutionException` wrapping a `RuntimeException("Retry sleep interrupted", e)`) — the engine never interrupts a workflow thread, so that branch means a foreign interrupter.

- [ ] **Step 1: Write the failing `RetryExecutorTest` tests**

Add to `maestro-core/src/test/java/io/b2mash/maestro/core/retry/RetryExecutorTest.java` (imports: `RunCancellation`, `CountDownLatch`, `AtomicReference`, `TimeUnit`):

```java
@Test
@DisplayName("a termination requested before an attempt throws WorkflowTerminatedException without calling the task")
void terminationBeforeAttempt_isNotAttempted() {
    var calls = new AtomicInteger();
    var cancellation = new RunCancellation();
    cancellation.requestTermination("operator");
    var thrown = assertThrows(WorkflowTerminatedException.class, () -> executor.executeWithRetry(
            fastPolicy(3), () -> calls.incrementAndGet(), "a", "wf-t1", cancellation));
    assertEquals(0, calls.get());
    assertEquals("wf-t1", thrown.workflowId());
    assertEquals("operator", thrown.reason());
}

@Test
@DisplayName("a termination requested during the backoff wait abandons it; no further attempt runs")
void terminationDuringBackoff_abandonsTheWait() throws Exception {
    var calls = new AtomicInteger();
    var firstAttemptFailed = new CountDownLatch(1);
    var cancellation = new RunCancellation();
    var policy = RetryPolicy.builder().maxAttempts(5)
            .initialInterval(Duration.ofSeconds(30)).maxInterval(Duration.ofSeconds(30)).build();
    Callable<String> task = () -> { calls.incrementAndGet(); firstAttemptFailed.countDown();
                                    throw new IllegalStateException("boom"); };
    var outcome = new AtomicReference<Throwable>();
    var worker = Thread.ofVirtual().start(() -> {
        try { executor.executeWithRetry(policy, task, "a", "wf-t2", cancellation); }
        catch (Throwable t) { outcome.set(t); }
    });
    assertTrue(firstAttemptFailed.await(5, TimeUnit.SECONDS));

    cancellation.requestTermination(null);

    assertTrue(worker.join(Duration.ofSeconds(5)), "the 30s backoff must be abandoned promptly");
    assertInstanceOf(WorkflowTerminatedException.class, outcome.get());
    assertEquals(1, calls.get(), "no attempt may run after the terminate");
}

@Test
@DisplayName("a shutdown requested during the backoff wait abandons it with ExecutorShutdownException")
void shutdownDuringBackoff_abandonsTheWaitAsShutdown() throws Exception {
    // same shape as above with cancellation.requestShutdown();
    // assertInstanceOf(ExecutorShutdownException.class, outcome.get()); assertEquals(1, calls.get());
}

@Test
@DisplayName("shutdown is NOT checked before the first attempt — in-flight work drains")
void shutdownBeforeFirstAttempt_stillAttemptsOnce() throws Exception {
    var calls = new AtomicInteger();
    var cancellation = new RunCancellation();
    cancellation.requestShutdown();
    assertEquals("ok", executor.executeWithRetry(fastPolicy(3), () -> { calls.incrementAndGet(); return "ok"; },
            "a", "wf-t4", cancellation));
    assertEquals(1, calls.get());
}

@Test
@DisplayName("a foreign interrupt during backoff, with nothing requested, is still an activity failure")
void foreignInterruptDuringBackoff_isAnActivityFailure() throws Exception {
    // worker thread as above with a fresh RunCancellation that is never requested; after the first
    // attempt fails call worker.interrupt();
    // assertTrue(worker.join(Duration.ofSeconds(5)));
    // var aee = assertInstanceOf(ActivityExecutionException.class, outcome.get());
    // assertInstanceOf(InterruptedException.class, aee.getCause().getCause());
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :maestro-core:test --tests '*RetryExecutorTest' --console=plain`
Expected: compilation error `cannot find symbol: class RunCancellation`.

- [ ] **Step 3: Implement `RunCancellation`, the `WorkflowContext` plumbing and the `RetryExecutor` change**

- `RunCancellation`: `CountDownLatch(1)` + `AtomicReference<Cause>` (compare-and-set null→cause gives first-wins) + `volatile @Nullable String reason`. `awaitRequested` = `latch.await(timeout.toMillis(), MILLISECONDS)`. `throwIfTerminated` builds `new WorkflowTerminatedException(workflowId, reason)`; `throwIfRequested` for `SHUTDOWN` builds `new ExecutorShutdownException("Executor is shutting down — abandoning the retry backoff of workflow '%s'; the workflow keeps its durable state and is recoverable".formatted(workflowId))` (mirrors `ParkingLot.shutdownSignal`, `ParkingLot.java:365-369`). Full Javadoc including the mirror-of-`ParkingLot` rationale above and a thread-safety line.
- `WorkflowContext`: field `private final RunCancellation cancellation;`, accessor `public RunCancellation cancellation()`, the 10-arg constructor; 9-arg delegates with `new RunCancellation()`. Javadoc on the accessor: "engine-internal; workflow code has no reason to call it".
- `RetryExecutor.executeWithRetry` 5-arg: `cancellation.throwIfTerminated(workflowId)` as the first statement of each loop iteration (`:49-50`); replace `sleep(backoff, …)` (`:88`, `:158-167`) with `awaitBackoff(backoff, cancellation, activityName, workflowId)` implementing the semantics in Interfaces. Update the class Javadoc (`:12-20`) — "stateless" still holds; the cancellation is caller-owned state.

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew :maestro-core:test --tests '*RetryExecutor*' --console=plain`
Expected: `BUILD SUCCESSFUL`; all `RetryExecutorTest` and `RetryExecutorControlFlowTest` tests pass (the 4-arg overload still works).

- [ ] **Step 5: Write the failing executor-level pins**

Add to `WorkflowExecutorTerminateTest` (fixture: `@Activity interface FailingActivity { String fail(); }` with `FailingActivityImpl(AtomicInteger invocations)` that always throws `IllegalStateException`; `@Activity interface GatedActivity { String first(); String second(); }` with latches `entered`/`release` on `first` and a counter on `second`; workflows `BackoffWorkflow(FailingActivity proxy)` calling `proxy.fail()`, `TwoStepWorkflow(GatedActivity proxy)` calling `first()` then `second()`, `ParallelBackoffWorkflow(FailingActivity proxy)` calling `WorkflowContext.workflow().parallel(List.of(proxy::fail, proxy::fail))`; proxies built with `new ActivityProxyFactory().createProxy(...)` exactly as `:248-253` does, policy `maxAttempts(5).initialInterval(Duration.ofSeconds(30)).maxInterval(Duration.ofSeconds(30))`):

```java
@Test
@DisplayName("terminate while a live activity is in retry backoff abandons the backoff; no further attempt runs")
void terminateDuringRetryBackoff_abandonsBackoffAndRunsNoFurtherAttempt() throws Exception {
    var invocations = new AtomicInteger();
    var workflow = new BackoffWorkflow(failingProxy(invocations));
    executor.startWorkflow("term-backoff", "BackoffWorkflow", "default", null, workflow,
            BackoffWorkflow.class.getMethod("run"));
    await().atMost(Duration.ofSeconds(5)).until(() -> invocations.get() == 1);   // attempt 1 failed; in backoff

    var terminatedAt = Instant.now();
    assertEquals(WorkflowExecutor.TerminateOutcome.TERMINATED,
            executor.terminateWorkflow("term-backoff", "mid-backoff"));
    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertFalse(executor.isRunning("term-backoff")));

    assertTrue(Duration.between(terminatedAt, Instant.now()).compareTo(Duration.ofSeconds(10)) < 0,
            "the 30s backoff must be abandoned, not slept through");
    assertEquals(1, invocations.get(), "no further attempt may run after terminate");
    var instance = store.getInstance("term-backoff").orElseThrow();
    assertEquals(WorkflowStatus.TERMINATED, instance.status());
    assertTrue(events(instance.id(), EventType.ACTIVITY_FAILED).isEmpty(),
            "an abandoned backoff is not an exhausted-retries failure");
    assertTrue(events(instance.id(), EventType.WORKFLOW_FAILED).isEmpty());
}

@Test
@DisplayName("terminate while one activity is in flight stops the next live activity from starting")
void terminateDuringLiveActivity_runsNoFurtherLiveActivity() throws Exception {
    var gated = new GatedActivityImpl();
    var workflow = new TwoStepWorkflow(gatedProxy(gated));
    executor.startWorkflow("term-next", "TwoStepWorkflow", "default", null, workflow,
            TwoStepWorkflow.class.getMethod("run"));
    assertTrue(gated.entered.await(5, TimeUnit.SECONDS));

    executor.terminateWorkflow("term-next", "stop");
    gated.release.countDown();                      // the in-flight attempt is NOT interrupted; it finishes

    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertFalse(executor.isRunning("term-next")));
    assertEquals(0, gated.secondCalls.get(), "the second live activity must never start");
    var instance = store.getInstance("term-next").orElseThrow();
    assertEquals(WorkflowStatus.TERMINATED, instance.status());
    assertEquals(1, events(instance.id(), EventType.ACTIVITY_COMPLETED).size(),
            "the finished first attempt is still memoized (harmless, as before)");
}

@Test
@DisplayName("terminate while parallel() branches are in retry backoff abandons every branch")
void terminateDuringParallelBranchBackoff_abandonsEveryBranch() throws Exception {
    var invocations = new AtomicInteger();
    var workflow = new ParallelBackoffWorkflow(failingProxy(invocations));
    executor.startWorkflow("term-par", "ParallelBackoffWorkflow", "default", null, workflow,
            ParallelBackoffWorkflow.class.getMethod("run"));
    await().atMost(Duration.ofSeconds(5)).until(() -> invocations.get() == 2);

    executor.terminateWorkflow("term-par", "stop");

    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertFalse(executor.isRunning("term-par")));
    assertEquals(2, invocations.get(), "neither branch may attempt again — branch contexts share the run's cancellation");
    assertEquals(WorkflowStatus.TERMINATED, store.getInstance("term-par").orElseThrow().status());
}
```

Add to `WorkflowExecutorShutdownTest` (reuse `BackoffWorkflow`/`FailingActivity` by making them package-visible static classes in `WorkflowExecutorTerminateTest`, the way `WorkflowExecutorShutdownTest.ParkingActivityImpl` is reused today):

```java
@Test
@DisplayName("Shutdown while a retried activity is in backoff abandons the wait and leaves the workflow RUNNING and recoverable")
void shutdown_duringRetryBackoff_abandonsBackoffAndLeavesItRecoverable() throws Exception {
    var invocations = new AtomicInteger();
    var workflow = new WorkflowExecutorTerminateTest.BackoffWorkflow(failingProxy(invocations));
    executor.startWorkflow("shutdown-backoff", "BackoffWorkflow", "default", null, workflow,
            WorkflowExecutorTerminateTest.BackoffWorkflow.class.getMethod("run"));
    await().atMost(Duration.ofSeconds(5)).until(() -> invocations.get() == 1);

    var start = Instant.now();
    executor.shutdown();

    assertTrue(Duration.between(start, Instant.now()).compareTo(Duration.ofSeconds(10)) < 0,
            "shutdown must not wait out a 30s backoff");
    assertEquals(1, invocations.get());
    var instance = store.getInstance("shutdown-backoff").orElseThrow();
    assertEquals(WorkflowStatus.RUNNING, instance.status(), "nothing is written — the run is recoverable");
    assertTrue(events(instance.id(), EventType.ACTIVITY_FAILED).isEmpty());
    assertTrue(store.getRecoverableInstances().stream().anyMatch(i -> i.workflowId().equals("shutdown-backoff")));
}
```

- [ ] **Step 6: Run to verify they fail**

Run: `./gradlew :maestro-core:test --tests '*WorkflowExecutorTerminateTest' --tests '*WorkflowExecutorShutdownTest' --console=plain`
Expected: the four new tests FAIL — `terminateDuringRetryBackoff_…` and the shutdown test on the elapsed-time assertion or `invocations` (`expected: <1> but was: <2>` once the 30 s wait is exceeded by Awaitility's 5 s bound → `ConditionTimeoutException`); `terminateDuringLiveActivity_…` on `secondCalls expected: <0> but was: <1>`; `terminateDuringParallelBranchBackoff_…` on the Awaitility bound. Existing tests still pass.

- [ ] **Step 7: Wire the executor**

- `WorkflowExecutor.java:1361-1371`: construct `var cancellation = new RunCancellation();` and pass it as the 10th constructor argument. `:1392`: `new RunningWorkflow(thread, instance, instance.workflowType(), workflowImpl, cancellation)`. `:2007-2008`: add the record component.
- `:774-776` (`terminateWorkflow`): replace the `containsKey` check with `var running = runningWorkflows.get(workflowId); if (running != null) { running.cancellation().requestTermination(reason); parkingLot.abandonWorkflow(workflowId, reason); }` — flag first, then parks.
- `:1121` (`shutdown()`), immediately after `parkingLot.shutdown()`: `runningWorkflows.values().forEach(r -> r.cancellation().requestShutdown());`.
- `ActivityInvocationHandler.java:392-397`: call the 5-arg `executeWithRetry(..., ctx.cancellation())`.
- `DefaultWorkflowOperations.java:591-601` and `SagaManager.java:410-420`: branch contexts use the 10-arg constructor with `ctx.cancellation()`.
- Javadoc: `WorkflowExecutor.java:698-701` "No interruption of an in-flight activity" → "No interruption of an in-flight activity **attempt**. The attempt that is executing finishes and memoizes its result (harmless); but no further attempt starts, a retry backoff in progress is abandoned, and no later activity goes live — the run stands down at its next live activity, retry, park or status write (`RunCancellation`)." `:1095-1097` (shutdown): add "A retry backoff in progress is abandoned too, with `ExecutorShutdownException`, leaving the instance `RUNNING` and recoverable; the attempt counter restarts on the next node." `WorkflowTerminatedException.java:14-22`: add the bullet "at its next live activity attempt or retry backoff, when the terminating node is the owner". `terminateMidActivity_letsTheActivityFinishThenStandsDown`'s comment (`WorkflowExecutorTerminateTest.java:229-230`) stays true; update its wording from "exactly as it does under shutdown" to name the attempt.

- [ ] **Step 8: Run the whole core suite**

Run: `./gradlew :maestro-core:test --console=plain`
Expected: `BUILD SUCCESSFUL`. If `WorkflowExecutorShutdownTest.shutdown_withInFlightWork_waitsForItToDrain` (`:463`) fails, shutdown was wrongly checked before an attempt — see Interfaces.

- [ ] **Step 9: Write the failing integration pin**

Add fixture to `TestWorkflows.java`:

```java
/** RetryWorkflow with a backoff longer than any test bound — for terminate/shutdown-during-backoff pins. */
@DurableWorkflow(name = "SlowRetryWorkflow")
public static class SlowRetryWorkflow {
    @ActivityStub(retryPolicy = @RetryPolicy(maxAttempts = 5, initialInterval = "PT30S", maxInterval = "PT30S"))
    FlakyActivities activities;
    @WorkflowMethod public String run(String input) { return activities.attempt(input); }
}
```

Create `EnginePostgresTerminateIT extends PostgresIntegrationSupport` (harness pattern from `EnginePostgresSagaIT.java:168-173`, `.lock(newLock())`, `.observer(new RecordingObserver())`):

```java
@Test
@DisplayName("terminate while a live activity is mid-retry backoff abandons the backoff; no further attempt runs")
void terminateDuringRetryBackoff_runsNoFurtherAttempt() {
    harness = newHarness();
    harness.registerActivities(FlakyActivities.class, new CountingActivities.FailNTimesActivities(recorder, Integer.MAX_VALUE));
    harness.registerWorkflow(new TestWorkflows.SlowRetryWorkflow());
    var workflowId = MaestroEngineHarness.uniqueWorkflowId("term-backoff");
    var handle = harness.start(workflowId, TestWorkflows.SlowRetryWorkflow.class, "x");
    await().atMost(BOUND).until(() -> recorder.count("attempt") == 1);

    assertEquals(WorkflowExecutor.TerminateOutcome.TERMINATED, harness.executor().terminateWorkflow(workflowId, "ops"));

    await().atMost(Duration.ofSeconds(10)).until(() -> !handle.isRunningLocally());   // << the 30 s backoff
    assertEquals(1, recorder.count("attempt"));
    assertEquals(WorkflowStatus.TERMINATED, handle.status());
    assertTrue(handle.events().stream().noneMatch(e -> e.eventType() == EventType.ACTIVITY_FAILED),
            "no ACTIVITY_FAILED may be persisted for an abandoned backoff");
    assertTrue(handle.events().stream().noneMatch(e -> e.eventType() == EventType.WORKFLOW_FAILED));
}
```

- [ ] **Step 10: Run the integration pin**

Run: `./gradlew :maestro-integration-tests:test --tests '*EnginePostgresTerminateIT' --console=plain` (Docker required)
Expected: PASS (the fix is already in). To see it fail, temporarily revert `RetryExecutor.awaitBackoff` to `Thread.sleep`: the Awaitility 10 s bound trips. Restore before committing.

- [ ] **Step 11: Docs and release notes**

- `docs/admin.md:238-242`: replace "without interrupting an in-flight activity (… stands down at its next checkpoint …)" with the attempt-level wording from Step 7.
- `docs/release-notes.md` `## Unreleased`: `### Fixed — Terminate now stops live work: no further activity attempt, retry backoff abandoned; shutdown abandons a retry backoff` — describe `RunCancellation`, which node sets it (owner only; non-owners converge via guards), what still is not interrupted (the executing attempt), and the shutdown behaviour change (a run in backoff is left `RUNNING`/recoverable instead of being waited for). Cite spec rows C6, C11.

- [ ] **Step 12: Commit**

```bash
git add maestro-core maestro-integration-tests docs/admin.md docs/release-notes.md
git commit -m "fix(core): RunCancellation — terminate stops live activities and abandons retry backoff; shutdown abandons backoff (C6, C11)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: C11(a)(b) — enforce `startToCloseTimeout` per attempt; make the retry-durability docs honest

**Files:**
- Create: `maestro-core/src/main/java/io/b2mash/maestro/core/exception/ActivityTimeoutException.java`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/exception/MaestroException.java:30-45` (`permits`)
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/ActivityInvocationHandler.java:386-399` (call site), `:432-452` (`invokeActivity`), constructor Javadoc `@param startToCloseTimeout` at `:91`, `:126`, `:157`, class Javadoc `:33-66`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/annotation/ActivityStub.java:43-52`
- Test: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/ActivityInvocationHandlerTest.java`
- Create: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/EnginePostgresActivityTimeoutIT.java`; fixtures `TestWorkflows.TimeoutRetryWorkflow`, `TestWorkflows.TimeoutNoRetryWorkflow`, `CountingActivities.SlowFirstAttemptActivities`
- Modify: `README.md:36`, `docs/concepts.md` (Retry policy section), `docs/maestro-architecture.md:656,658`, `docs/release-notes.md`

**Interfaces:**
- Produces: `public final class ActivityTimeoutException extends MaestroException` with constructor `(String workflowId, String activityName, Duration timeout)`, accessors `workflowId()`, `activityName()`, `timeout()`, message `"Activity '%s' in workflow '%s' exceeded its startToCloseTimeout of %s"`. Retryability: it is **not** special-cased — `RetryExecutor.isRetryable` treats it like any other exception, so `nonRetryableExceptions = ActivityTimeoutException.class` makes a timeout terminal, and the default (both lists empty) retries it. Replay: it is persisted as `ErrorDetail.exceptionType = io.b2mash.maestro.core.exception.ActivityTimeoutException`; Task 1's `causedBy(ActivityTimeoutException.class)` matches on replay via Plan 02's reconstruction (no `(String)` constructor is added).
- `ActivityInvocationHandler` private `invokeActivityBounded(Method, Object[], WorkflowContext, String stepName)`: runs `invokeActivity(method, args)` in a `FutureTask` on `Thread.ofVirtual().name("maestro-activity-" + ctx.workflowId() + "-" + stepName)` with the caller's `MDC.getCopyOfContextMap()` installed on the attempt thread; `get(startToCloseTimeout)`; `TimeoutException` → `cancel(true)` then throw `ActivityTimeoutException`; `ExecutionException` → unwrap: `Error` rethrown first, then `Exception`, else `RuntimeException(cause)` (same ordering as today's `invokeActivity`, `:439-451`). The `Callable` handed to `RetryExecutor` becomes `() -> invokeActivityBounded(...)`; `RetryExecutor` is **unchanged** by this task.

- [ ] **Step 1: Write the failing handler tests**

Add to `ActivityInvocationHandlerTest` (fixtures: `@Activity interface SlowActivities { String slow(); }`; `SlowActivitiesImpl(Duration sleep, boolean ignoreInterrupt)` — with `ignoreInterrupt` it loops `while (System.nanoTime() < deadline) { try { Thread.sleep(10); } catch (InterruptedException ignored) { } }` and then returns `"late"`; a `createProxy(...)` variant taking the policy and timeout, mirroring the existing helper):

```java
@Test
@DisplayName("Timeout: an attempt exceeding startToCloseTimeout is cancelled and recorded as ActivityTimeoutException")
void liveAttemptExceedingTimeout_isRecordedAsTimeout() {
    withContext(() -> {
        var proxy = createProxy(SlowActivities.class, new SlowActivitiesImpl(Duration.ofSeconds(2), false),
                RetryPolicy.noRetry(), Duration.ofMillis(100));
        var start = System.nanoTime();
        var thrown = assertThrows(ActivityExecutionException.class, proxy::slow);
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(1)) < 0,
                "must give up at the timeout, not wait for the attempt");
        assertTrue(thrown.causedBy(ActivityTimeoutException.class));
        assertEquals(1, store.appendedEvents.size());
        assertEquals(EventType.ACTIVITY_FAILED, store.appendedEvents.get(0).eventType());
        assertEquals(ActivityTimeoutException.class.getName(),
                store.appendedEvents.get(0).payload().get("exceptionType").stringValue());
    });
}

@Test
@DisplayName("Timeout: a timed-out attempt is retried under the policy, and a fast second attempt succeeds")
void timedOutAttempt_isRetriedLikeAnyFailure() {
    var calls = new AtomicInteger();
    SlowActivities impl = () -> calls.incrementAndGet() == 1 ? sleepThenReturn(Duration.ofSeconds(2)) : "fast";
    withContext(() -> {
        var proxy = createProxy(SlowActivities.class, impl,
                RetryPolicy.builder().maxAttempts(2).initialInterval(Duration.ofMillis(1)).build(),
                Duration.ofMillis(100));
        assertEquals("fast", proxy.slow());
        assertEquals(2, calls.get());
        assertEquals(1, store.appendedEvents.size());
        assertEquals(EventType.ACTIVITY_COMPLETED, store.appendedEvents.get(0).eventType());
    });
}

@Test
@DisplayName("Timeout: nonRetryableExceptions = ActivityTimeoutException makes a timeout terminal after one attempt")
void timeoutListedNonRetryable_failsAfterOneAttempt() {
    var calls = new AtomicInteger();
    withContext(() -> {
        var proxy = createProxy(SlowActivities.class, () -> { calls.incrementAndGet(); return sleepThenReturn(Duration.ofSeconds(2)); },
                RetryPolicy.builder().maxAttempts(5).initialInterval(Duration.ofMillis(1))
                        .nonRetryableExceptions(List.of(ActivityTimeoutException.class)).build(),
                Duration.ofMillis(100));
        assertThrows(ActivityExecutionException.class, proxy::slow);
        assertEquals(1, calls.get());
    });
}

@Test
@DisplayName("Timeout: a late value from an attempt that ignored interruption is never memoized")   // Review Focus 3
void lateValueAfterTimeout_isNeverMemoized() throws Exception {
    withContext(() -> {
        var proxy = createProxy(SlowActivities.class, new SlowActivitiesImpl(Duration.ofMillis(400), true),
                RetryPolicy.noRetry(), Duration.ofMillis(50));
        assertThrows(ActivityExecutionException.class, proxy::slow);
    });
    Thread.sleep(700);   // let the uninterruptible attempt finish and try to "return"
    assertEquals(1, store.appendedEvents.size(), "exactly one event at this sequence");
    assertEquals(EventType.ACTIVITY_FAILED, store.appendedEvents.get(0).eventType(),
            "the late 'late' value must not be persisted over the recorded timeout");
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :maestro-core:test --tests '*ActivityInvocationHandlerTest' --console=plain`
Expected: compilation error `cannot find symbol: class ActivityTimeoutException`.

- [ ] **Step 3: Implement**

- `ActivityTimeoutException` per Interfaces; add to `MaestroException`'s `permits` (`:30-45`). Javadoc: what the timeout bounds (one attempt, wall clock, from invocation to return), what happens to the attempt (`cancel(true)` → interrupt; an attempt that ignores interruption keeps running but its result is discarded), and that retryability follows the policy.
- `ActivityInvocationHandler`: `invokeActivityBounded` per Interfaces; the `RetryExecutor` callable at `:393-397` becomes `() -> invokeActivityBounded(method, args, ctx, stepName)`.
- Javadoc: the three `@param startToCloseTimeout timeout used as lock TTL hint` → "wall-clock bound for each live attempt (enforced); also the base of the per-activity lock TTL". Class Javadoc `:33-66`: add an `<h2>Timeouts</h2>` paragraph. `ActivityStub.java:47-48`: delete "Hard enforcement of this timeout is a future enhancement"; state the enforcement and `ActivityTimeoutException`.

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew :maestro-core:test --console=plain`
Expected: `BUILD SUCCESSFUL`. Existing `ActivityInvocationHandlerTest` tests must still pass with every attempt now on its own virtual thread. The test's `InMemoryWorkflowStore.appendedEvents` (`:565`) and `RecordingMessaging.publishedEvents` (`:720`) are plain `ArrayList`s; the handler itself still appends from the workflow thread (only `invokeActivity` moves), but the Review Focus 3 test reads `appendedEvents` after a late attempt thread may have touched the store — change both to `CopyOnWriteArrayList` before relying on them.

- [ ] **Step 5: Write the failing integration pin**

Fixtures: `CountingActivities.SlowFirstAttemptActivities implements FlakyActivities` (`attempt`: records `"attempt"`; on the first call sleeps `slow`, later calls return `input + "-ok-after-" + n`); `TestWorkflows.TimeoutRetryWorkflow` with `@ActivityStub(startToCloseTimeout = "PT0.3S", retryPolicy = @RetryPolicy(maxAttempts = 2, initialInterval = "PT0.1S", maxInterval = "PT0.1S"))`; `TestWorkflows.TimeoutNoRetryWorkflow` with `@ActivityStub(startToCloseTimeout = "PT0.3S", retryPolicy = @RetryPolicy(maxAttempts = 1))`. Both call `activities.attempt(input)`.

`EnginePostgresActivityTimeoutIT extends PostgresIntegrationSupport`:

```java
@Test
@DisplayName("an attempt exceeding startToCloseTimeout is retried; the second attempt completes the workflow")
void timedOutFirstAttempt_isRetriedAndCompletes() {
    harness = newHarness();
    harness.registerActivities(FlakyActivities.class, new CountingActivities.SlowFirstAttemptActivities(recorder, Duration.ofSeconds(5)));
    harness.registerWorkflow(new TestWorkflows.TimeoutRetryWorkflow());
    var handle = harness.start(MaestroEngineHarness.uniqueWorkflowId("timeout-retry"), TestWorkflows.TimeoutRetryWorkflow.class, "x");

    assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(Duration.ofSeconds(10)));   // << 5 s sleep + retry
    assertEquals("x-ok-after-2", handle.result(String.class));
    assertEquals(2, recorder.count("attempt"));
    assertEquals(1, handle.events().stream().filter(e -> e.eventType() == EventType.ACTIVITY_COMPLETED).count());
    assertTrue(handle.events().stream().noneMatch(e -> e.eventType() == EventType.ACTIVITY_FAILED));
}

@Test
@DisplayName("with maxAttempts = 1 a timeout is persisted as ACTIVITY_FAILED with the timeout's type and the workflow FAILS")
void timedOutOnlyAttempt_persistsTimeoutFailure() {
    harness = newHarness();
    harness.registerActivities(FlakyActivities.class, new CountingActivities.SlowFirstAttemptActivities(recorder, Duration.ofSeconds(5)));
    harness.registerWorkflow(new TestWorkflows.TimeoutNoRetryWorkflow());
    var handle = harness.start(MaestroEngineHarness.uniqueWorkflowId("timeout-fail"), TestWorkflows.TimeoutNoRetryWorkflow.class, "x");

    assertEquals(WorkflowStatus.FAILED, handle.awaitTerminal(Duration.ofSeconds(10)));
    var failed = handle.events().stream().filter(e -> e.eventType() == EventType.ACTIVITY_FAILED).toList();
    assertEquals(1, failed.size());
    assertEquals(ActivityTimeoutException.class.getName(), failed.get(0).payload().get("exceptionType").stringValue());
    assertEquals(1, recorder.count("attempt"));
}
```

- [ ] **Step 6: Run the integration pin**

Run: `./gradlew :maestro-integration-tests:test --tests '*EnginePostgresActivityTimeoutIT' --console=plain`
Expected: both PASS within ~3 s each. (Pre-fix they would wait the full 5 s sleep and record `IllegalStateException`/success instead.)

- [ ] **Step 7: Make the docs honest**

- `README.md:36`: `- **Durable Retries** -- Activities retry with exponential backoff, each attempt bounded by \`startToCloseTimeout\`. A completed attempt is never re-executed after a crash; the attempt counter itself is in-memory, so a crash mid-retry restarts the schedule at attempt 1.`
- `docs/concepts.md` Retry policy section (`:123-138`): after the backoff formula add two short paragraphs — **Timeouts** (`startToCloseTimeout` bounds each attempt; timed-out attempt is interrupted; `ActivityTimeoutException` is retryable under the policy and can be listed in `nonRetryableExceptions`) and **What survives a crash** (the exact sentence from the decision: "the attempt counter is in-memory; after a crash the activity restarts at attempt 1 with the full backoff schedule; the durable guarantee is that a *completed* attempt is never re-executed").
- `docs/maestro-architecture.md:656`: `| **Activity timeout** | Each attempt is bounded by \`startToCloseTimeout\` on its own virtual thread; a timed-out attempt is interrupted and counts as a retryable failure (\`ActivityTimeoutException\`). Exhausted → saga or fail. |` and `:658`: `| **External API down** | Activity retries with backoff (configurable: attempts, backoff, max interval). The attempt counter is in-memory: after a restart the activity restarts at attempt 1 with the full schedule; a completed attempt is never re-executed. |`
- `docs/release-notes.md` `## Unreleased`: `### Changed — startToCloseTimeout is now enforced per attempt (ActivityTimeoutException); retry-durability docs corrected`. **Behaviour change:** an activity whose attempt used to run past its declared timeout now fails that attempt. Cite spec row C11.

- [ ] **Step 8: Commit**

```bash
git add maestro-core maestro-integration-tests README.md docs/concepts.md docs/maestro-architecture.md docs/release-notes.md
git commit -m "feat(core): enforce startToCloseTimeout per attempt with ActivityTimeoutException; honest retry-durability docs (C11)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: C5 — a dual-execution loser stands down at its first collision or terminal fresh read

**Files:**
- Create: `maestro-core/src/main/java/io/b2mash/maestro/core/exception/StaleRunException.java`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/exception/MaestroException.java:30-45` (`permits`)
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/InstanceStatusWriter.java:41-43` (Javadoc), `:144-149`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/ActivityInvocationHandler.java:48-52` (Javadoc), `:400-425` (adoption branch), `:456-500` (`appendEventSafe`), `:505-530` (`persistFailure`)
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java:1480`, `:1521` (catch arms), `:1626-1655` (`handleStaleRunStandDown`)
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/saga/SagaManager.java:304-318` (sequential arm), `:470-473` (parallel arm), `:601-607` (`transitionToCompensating` other-terminal)
- Test: `.../engine/ActivityInvocationHandlerTest.java:206-224`, `.../engine/ForeignPayloadStandDownTest.java:136`, `.../engine/WorkflowTerminalStateGuardTest.java:149-165`, `.../engine/WorkflowExecutorDuplicateEventStandDownTest.java`; create `.../engine/InstanceStatusWriterTest.java`
- Create: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/EnginePostgresDualExecutionIT.java`
- Modify: `docs/release-notes.md`, `docs/open-issues.md` Issue 18 callout (`:1492+`)

**Interfaces:**
- Produces: `public final class StaleRunException extends MaestroException` with constructor `(String workflowId, WorkflowStatus observedStatus, String detail)`, accessors `workflowId()`, `observedStatus()`, `detail()`, message `"Workflow '%s' is already %s — another runner finalised it; this run is stale (%s)"`. Meaning: "a fresh read shows a terminal status this run did not write; write nothing, compensate nothing, stand down". It is an ordinary `MaestroException` by design (same class as `DuplicateEventException`, whose executor arm it joins); Task 5 reuses it.
- `WorkflowExecutor.handleStaleRunStandDown(WorkflowContext ctx, MaestroException e)` — accepts both `DuplicateEventException` (detail `"append collision at sequence N"`) and `StaleRunException` (detail `e.detail()`); emits `observer.standDown(StandDownReason.STALE_RUN, …)` as today.
- `ActivityInvocationHandler.appendEventSafe` becomes `void`: on `DuplicateEventException` it re-reads, runs `UnknownHistoryGuard.requireKnown` (so an unknown-typed winner still stands down as `UNKNOWN_EVENT_TYPE`, RULING 9), logs at WARN, and **rethrows** the `DuplicateEventException`. `persistFailure` becomes `void` and `executeLive` loses the adoption branch (`:404-422`): no payload is ever adopted; the observer still gets `activityCompleted(info, elapsed, false)` for the attempt this node genuinely executed, then the duplicate propagates. Same for the failure path: `persistFailure` → duplicate → propagate (Review Focus 4: the loser never reports a failure that contradicts the winner).

- [ ] **Step 1: Write the failing tests**

`InstanceStatusWriterTest` (package `io.b2mash.maestro.core.engine`, uses `VersionedInMemoryStore` from the test tree; seed an instance via `store.createInstance(...)` then force its status with the store's `forceStatus(workflowId, status)` as `WorkflowTerminalStateGuardTest.java:157` does):

```java
@Test
@DisplayName("a fresh read of COMPLETED throws StaleRunException — another runner finalised it")
void freshReadCompleted_throwsStaleRun() {
    seed("w1", WorkflowStatus.COMPLETED);
    var thrown = assertThrows(StaleRunException.class,
            () -> InstanceStatusWriter.write(store, "w1", WorkflowStatus.RUNNING));
    assertEquals(WorkflowStatus.COMPLETED, thrown.observedStatus());
    assertEquals(WorkflowStatus.COMPLETED, store.getInstance("w1").orElseThrow().status(), "nothing written");
}

@Test
@DisplayName("a fresh read of FAILED throws StaleRunException")
void freshReadFailed_throwsStaleRun() { /* as above with FAILED */ }

@Test
@DisplayName("a fresh read of TERMINATED still throws WorkflowTerminatedException")
void freshReadTerminated_throwsWorkflowTerminated() {
    seed("w3", WorkflowStatus.TERMINATED);
    assertThrows(WorkflowTerminatedException.class, () -> InstanceStatusWriter.write(store, "w3", WorkflowStatus.WAITING_SIGNAL));
}
```

Rewrite `ActivityInvocationHandlerTest.duplicateEventIdempotency` (`:206-224`) as:

```java
@Test
@DisplayName("Stale run: losing the append race rethrows DuplicateEventException instead of adopting the winner's value")
void duplicateEventOnAppend_standsDownInsteadOfAdopting() {
    store.preloadForDuplication(1, new WorkflowEvent(UUID.randomUUID(), INSTANCE_ID, 1,
            EventType.ACTIVITY_COMPLETED, "GreetingActivities.greet", MAPPER.valueToTree(new Greeting("winner")), Instant.now()));
    withContext(() -> {
        var proxy = createProxy(GreetingActivities.class, new GreetingActivitiesImpl());
        var thrown = assertThrows(DuplicateEventException.class, () -> proxy.greet("World"));
        assertEquals(1, thrown.sequenceNumber());
    });
    assertTrue(messaging.publishedEvents.stream().noneMatch(e -> e.eventType() == LifecycleEventType.ACTIVITY_COMPLETED),
            "the loser must not announce a completion it did not persist");
}

@Test
@DisplayName("Stale run: a loser whose own attempt FAILED after the winner completed does not persist or surface that failure")  // Review Focus 4
void duplicateEventOnFailureAppend_standsDown() {
    store.preloadForDuplication(1, /* winner's ACTIVITY_COMPLETED at seq 1 as above */);
    withContext(() -> {
        var proxy = createProxy(GreetingActivities.class, new FailingGreetingActivitiesImpl(), RetryPolicy.noRetry());
        assertThrows(DuplicateEventException.class, () -> proxy.greet("World"),
                "must not surface ActivityExecutionException — the winner's COMPLETED is the truth");
    });
    assertTrue(messaging.publishedEvents.stream().noneMatch(e -> e.eventType() == LifecycleEventType.ACTIVITY_FAILED));
}
```

(`messaging` is the test's existing `RecordingMessaging` at `ActivityInvocationHandlerTest.java:718-720`; its list is `publishedEvents`.)

```java
```

`ForeignPayloadStandDownTest.java:136` ("adopting a winner whose payload this build cannot read stands down"): the payload is now never read; change the expected reason to `StandDownReason.STALE_RUN` and rename to `losingTheAppendRace_standsDownAsStaleRunWithoutReadingTheWinnerPayload`. Keep `:100` (unknown-typed winner → `UNKNOWN_EVENT_TYPE`) unchanged — `requireKnown` still runs first.

`WorkflowTerminalStateGuardTest.completedElsewhere_isNotOverwrittenByAWakingRun` (`:149-165`): construct the executor with a recording observer (the 11-arg constructor at `WorkflowExecutor.java:327`; `maestro-core`'s test tree has no shared recording observer — add a small `EngineObserver` implementation to the test class that collects `standDown(reason, workflowId, detail)` calls into a `CopyOnWriteArrayList`, with a `List<StandDownReason> standDownReasons(String workflowId)` accessor) and add `assertEquals(List.of(StandDownReason.STALE_RUN), observer.standDownReasons("resurrect-completed"))`. `WorkflowExecutorDuplicateEventStandDownTest` below reuses the same observer class (make it package-visible).

`WorkflowExecutorDuplicateEventStandDownTest`: add `duplicateOnNextActivityAppend(JsonNode winnerPayload)` to `ConflictingStore` (`:215-290`; it already has `duplicateOnNextWorkflowAppend`/`duplicateOnNextCompensationAppend` toggles) and:

```java
@Test
@DisplayName("an activity append collision stands the loser down before it runs the next activity")
void activityAppendCollision_standsDownWithoutRunningTheNextActivity() {
    var secondActivityCalls = new AtomicInteger();
    store.duplicateOnNextActivityAppend(serializer.serialize("winner-value"));
    var workflowId = startTwoActivityWorkflow("standdown-activity", secondActivityCalls);   // fixture: activity A then B via proxies

    await().atMost(Duration.ofSeconds(5)).until(() -> !executor.isRunning(workflowId));

    assertEquals(0, secondActivityCalls.get(), "the loser must not execute activity B — the winner is alive and ahead");
    assertNotEquals(WorkflowStatus.FAILED, store.getInstance(workflowId).orElseThrow().status());
    assertEquals(0, store.eventsOfType(EventType.WORKFLOW_FAILED));
    assertEquals(List.of(StandDownReason.STALE_RUN), observer.standDownReasons(workflowId));
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :maestro-core:test --tests '*InstanceStatusWriterTest' --tests '*ActivityInvocationHandlerTest' --tests '*WorkflowExecutorDuplicateEventStandDownTest' --tests '*WorkflowTerminalStateGuardTest' --tests '*ForeignPayloadStandDownTest' --console=plain`
Expected: compilation error `cannot find symbol: class StaleRunException`; after stubbing the class only, `duplicateEventOnAppend_…` fails with "Expected DuplicateEventException to be thrown, but nothing was thrown", `activityAppendCollision_…` with `secondActivityCalls expected: <0> but was: <1>`.

- [ ] **Step 3: Implement**

- `StaleRunException` per Interfaces; `MaestroException` `permits` += it.
- `InstanceStatusWriter.java:144-149`: replace `return` with `throw new StaleRunException(workflowId, current.status(), "status write of " + newStatus)`; Javadoc `:41-43` and the `@throws` list on `write` updated.
- `ActivityInvocationHandler`: per Interfaces. Rewrite the `<h2>Idempotency</h2>` Javadoc (`:48-52`) to the stand-down contract and the one-paragraph "why not adopt" from the Decisions table.
- `WorkflowExecutor.java:1480`, `:1521`: `catch (DuplicateEventException | StaleRunException staleRun)`; `handleStaleRunStandDown(ctx, MaestroException)` with a `switch` on the type for the detail string; rewrite its Javadoc `:1636-1641` (the sentence "Inside `ActivityInvocationHandler` the same exception keeps its adopt-the-stored-result semantics" is now false).
- `SagaManager.java:304-318`: `catch (DuplicateEventException | StaleRunException staleRun) { throw staleRun; }`; `:470-473`: also rethrow `StaleRunException`; `:601-607`: throw `StaleRunException(ctx.workflowId(), latest.status(), "COMPENSATING write")` instead of `InvalidStateTransitionException` (which today escapes every arm in `executeWorkflow` and kills the thread uncaught).

- [ ] **Step 4: Run the core suite**

Run: `./gradlew :maestro-core:test --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Write the failing integration pin**

`EnginePostgresDualExecutionIT extends PostgresIntegrationSupport`. Two harnesses on the shared `store`, **no lock backend** (`.lock(null)`, the Issue 11 lost-lock window made permanent), same `serviceName("dual-node")`, each with its own `RecordingObserver`. Fixture `GatedChainActivities implements ChainActivities` wrapping `RecordingChainActivities`: `stepOne` blocks on `release` for the **first** caller only (`AtomicBoolean firstCaller`) after counting down `entered`; `stepTwo`/`stepThree` delegate.

```java
@Test
@DisplayName("with the lock lost, the loser stands down at its first append collision and runs no further activity")
void lostLock_loserStandsDownAtFirstCollision() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var gated = new GatedChainActivities(new CountingActivities.RecordingChainActivities(recorder), entered, release);
    nodeA = newNode(observerA).registerActivities(ChainActivities.class, gated).registerWorkflow(new TestWorkflows.ChainWorkflow());
    nodeB = newNode(observerB).registerActivities(ChainActivities.class, gated).registerWorkflow(new TestWorkflows.ChainWorkflow());

    var workflowId = MaestroEngineHarness.uniqueWorkflowId("dual");
    var handle = nodeA.start(workflowId, TestWorkflows.ChainWorkflow.class, "x");
    assertTrue(entered.await(30, TimeUnit.SECONDS), "node A must be inside stepOne");

    assertEquals(1, nodeB.recover(), "node B adopts the RUNNING instance — no lock to stop it");
    awaitStatus(workflowId, WorkflowStatus.COMPLETED, BOUND);          // B runs all three steps
    release.countDown();                                              // A's stepOne returns; its append at seq 1 collides
    await().atMost(BOUND).until(() -> !nodeA.executor().isRunning(workflowId));

    assertEquals(2, recorder.count("stepOne"), "both nodes executed stepOne — the double execution the lock normally prevents");
    assertEquals(1, recorder.count("stepTwo"), "the loser must not go on to stepTwo");
    assertEquals(1, recorder.count("stepThree"));
    assertEquals("x-one-two-three", handle.result(String.class));
    assertEquals(3, handle.events().stream().filter(e -> e.eventType() == EventType.ACTIVITY_COMPLETED).count(),
            "exactly one durable result per sequence");
    assertEquals(List.of(StandDownReason.STALE_RUN), observerA.standDowns().stream()
            .filter(s -> s.workflowId().equals(workflowId)).map(RecordingObserver.StandDownCall::reason).toList());
    assertTrue(observerB.standDowns().isEmpty());
}
```

- [ ] **Step 6: Run the integration pin**

Run: `./gradlew :maestro-integration-tests:test --tests '*EnginePostgresDualExecutionIT' --console=plain`
Expected: PASS. (Pre-fix: `recorder.count("stepTwo")` is 2 — the loser adopted "x-one" and kept going — and no stand-down is observed.)

- [ ] **Step 7: Docs**

- `docs/release-notes.md` `## Unreleased`: `### Fixed — A dual-execution loser now stands down at its first collision and at any terminal fresh read` — explain `StaleRunException`, the end of payload adoption and why (winner alive and ahead), and the `InstanceStatusWriter` change (`COMPLETED`/`FAILED` previously logged and let the run continue). Cite spec row C5.
- `docs/open-issues.md` Issue 18 callout: append one sentence noting the activity live path no longer adopts the winner's value (it stands down instead) and that `StaleRunException` carries the status-write variant of the same signal.

- [ ] **Step 8: Commit**

```bash
git add maestro-core maestro-integration-tests docs/release-notes.md docs/open-issues.md
git commit -m "fix(core): stale run stands down at first collision or terminal fresh read — StaleRunException, no winner-payload adoption (C5)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: C7 — re-check for terminate before every sequential compensation entry

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/saga/SagaManager.java:233-253` (Javadoc), `:255-289` (loop), `:570-645` (`transitionToCompensating`)
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java:64-69`, `:686-697` (Javadoc "one open race")
- Test: `maestro-core/src/test/java/io/b2mash/maestro/core/saga/SagaManagerTerminateRaceTest.java`; `maestro-integration-tests/.../engine/EnginePostgresSagaIT.java`
- Modify: `docs/release-notes.md`, `docs/open-issues.md:1791-1815` (Issue 22 callout)

**Interfaces:**
- Consumes: `StaleRunException` (Task 4).
- Produces: `SagaManager` private `Optional<WorkflowInstance> requireNotFinalised(WorkflowContext ctx, String action)` — one `store.getInstance` read; `TERMINATED` → `throw new WorkflowTerminatedException(ctx.workflowId(), null)` (log INFO as `:593-595` does today); other terminal → `throw new StaleRunException(ctx.workflowId(), status, action)`; empty → `Optional.empty()`; else `Optional.of(latest)`. `transitionToCompensating` calls it once per CAS attempt (replacing `:591-607`); `executeSequential` calls it **before each live entry** (after the replay-skip `continue`, before `ctx.setSequence(stepBaseSeq)`), with `action = "compensation entry " + i + " '" + entry.stepName() + "'"`. Replay-skipped entries do not read (they run nothing). Parallel compensation is unchanged: its branches all start in the same instant, and every `@Compensate` entry already goes through `ActivityInvocationHandler`, where Task 2's flag stops a local terminate; only manually registered `Runnable`s in parallel mode remain uncovered, which the Javadoc states.

- [ ] **Step 1: Write the failing unit pins**

Add to `SagaManagerTerminateRaceTest` (reusing its `RaceableStore`, `newInstance`, `newCtx`, `RecordingMessaging`; helper `forceStatus(RaceableStore, String workflowId, WorkflowStatus)` that writes `latest.toBuilder().status(s).version(latest.version() + 1).build()` directly — "a remote node's write"):

```java
@Test
@DisplayName("a terminate landing while entry 0 runs stops the loop before entry 1")
void terminateDuringEntryZero_stopsBeforeEntryOne() {
    var store = new RaceableStore();
    var sagaManager = new SagaManager(store, new RecordingMessaging(), new PayloadSerializer(new ObjectMapper()), "test-service");
    var instance = newInstance(store, "per-entry");
    var ctx = newCtx(instance);
    var runs = new CopyOnWriteArrayList<String>();
    var stack = new CompensationStack();
    stack.push("step-C", () -> runs.add("C"));
    stack.push("step-B", () -> runs.add("B"));
    stack.push("step-A", () -> { runs.add("A"); forceStatus(store, "per-entry", WorkflowStatus.TERMINATED); }); // LIFO: A runs first

    assertThrows(WorkflowTerminatedException.class, () -> sagaManager.compensate(ctx, instance, stack, false));

    assertEquals(List.of("A"), runs, "entries 1..n must not run once the instance reads TERMINATED");
    var events = store.getEvents(instance.id());
    assertEquals(1, events.stream().filter(e -> e.eventType() == EventType.COMPENSATION_STEP_COMPLETED).count(),
            "entry 0 ran to completion and its outcome stays durable");
    assertTrue(events.stream().noneMatch(e -> e.eventType() == EventType.COMPENSATION_COMPLETED));
    assertEquals(WorkflowStatus.TERMINATED, store.getInstance("per-entry").orElseThrow().status());
}

@Test
@DisplayName("another runner finalising the workflow mid-compensation stands this run down as stale")
void otherRunnerFinalisedDuringEntryZero_standsDownAsStale() {
    // same shape; entry A forces FAILED; assertThrows(StaleRunException.class, ...); assertEquals(List.of("A"), runs);
}

@Test
@DisplayName("exactly one instance read per live entry; replay-skipped entries read nothing")
void perEntryGuard_costsOneReadPerLiveEntry() {
    // RaceableStore (or a counting decorator) counts getInstance calls; 3 live entries;
    // reads during compensate() == 1 (transitionToCompensating) + 3 (one per entry).
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :maestro-core:test --tests '*SagaManagerTerminateRaceTest' --console=plain`
Expected: `terminateDuringEntryZero_…` fails: `expected: <[A]> but was: <[A, B, C]>` (no exception thrown — `assertThrows` fails first with "Expected WorkflowTerminatedException to be thrown, but nothing was thrown").

- [ ] **Step 3: Implement**

`requireNotFinalised` per Interfaces; refactor `transitionToCompensating` to use it; insert the per-entry call in `executeSequential`. Update `executeSequential`'s Javadoc (`:233-253`) with the per-entry guard and its cost, and the `catch (Exception e)` comment (`:290-302`) — a terminate is now also caught *between* entries, not only when an action parks. Fix the Javadoc that admits the race: `WorkflowExecutor.java:64-69` (class) and `:686-697` (terminate) — delete the "One open exception / one open race" text; state: "`SagaManager` re-checks the instance before entering compensation and before each sequential entry, so a terminate observed at any of those points stops the unwind; the only compensation work that can still complete after a terminate is the entry (or parallel branch set) already executing".

- [ ] **Step 4: Run the core suite**

Run: `./gradlew :maestro-core:test --console=plain`
Expected: `BUILD SUCCESSFUL`. `SagaManagerTest` (`:87-200`) still passes — the extra reads are invisible to it.

- [ ] **Step 5: Write the failing integration pin** (Review Focus 5)

Add to `EnginePostgresSagaIT` a second harness `remote = MaestroEngineHarness.builder(store, objectMapper).serviceName("saga-node").lock(newLock()).build()` (closed in `@AfterEach`). The terminate must come from the **non-owning** node so Task 2's in-memory flag plays no part; use the `GatedSagaActivities` fixture (`:69-74`) which holds inside `refund`, the first compensation:

```java
@Test
@DisplayName("a terminate from another node during the first compensation stops the second from running")
void remoteTerminateDuringFirstCompensation_stopsBeforeTheSecond() throws Exception {
    var compensating = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    harness = newHarness();
    harness.registerActivities(SagaActivities.class, new GatedSagaActivities(
            new CountingActivities.RecordingSagaActivities(recorder, "ship"), compensating, release));
    harness.registerWorkflow(new TestWorkflows.SagaWorkflow());
    var workflowId = MaestroEngineHarness.uniqueWorkflowId("saga-remote-term");
    var handle = harness.start(workflowId, TestWorkflows.SagaWorkflow.class, "widget");
    assertTrue(compensating.await(30, TimeUnit.SECONDS), "must be held inside refund");

    assertEquals(WorkflowExecutor.TerminateOutcome.TERMINATED, remote.executor().terminateWorkflow(workflowId, "remote"));
    release.countDown();

    await().atMost(BOUND).until(() -> !handle.isRunningLocally());
    assertEquals(1, recorder.count("refund"), "the entry already executing finishes");
    assertEquals(0, recorder.count("releaseReservation"), "the next entry must not start on a TERMINATED workflow");
    assertEquals(WorkflowStatus.TERMINATED, handle.status());
    var events = handle.events();
    assertTrue(eventsOfType(events, EventType.COMPENSATION_COMPLETED).isEmpty());
    assertTrue(eventsOfType(events, EventType.WORKFLOW_FAILED).isEmpty());
}
```

- [ ] **Step 6: Run the integration pin**

Run: `./gradlew :maestro-integration-tests:test --tests '*EnginePostgresSagaIT' --console=plain`
Expected: all `EnginePostgresSagaIT` tests PASS. (Pre-fix: `releaseReservation` count is 1 and `COMPENSATION_COMPLETED` is recorded on a TERMINATED workflow.)

- [ ] **Step 7: Docs**

- `docs/open-issues.md:1791-1815` (Issue 22 callout): add a paragraph "Residual closed (this cycle)": the entry gap was closed earlier; the per-entry gap (a terminate landing during entry *i* let entries *i+1…n* run) is now closed by `requireNotFinalised` before each sequential entry; name the two pins above. Keep the status **Resolved**.
- `docs/release-notes.md` `## Unreleased`: `### Fixed — Terminate during saga compensation now stops before the next sequential entry` with the exact residual and the one-read-per-entry cost. Cite spec row C7.

- [ ] **Step 8: Commit**

```bash
git add maestro-core maestro-integration-tests docs/release-notes.md docs/open-issues.md
git commit -m "fix(saga): re-check instance status before every sequential compensation entry; close the Issue 22 residual (C7)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: Whole-plan gate

**Files:** none modified unless a step fails.

- [ ] **Step 1: Core has no Spring**

Run: `./gradlew :maestro-core:dependencies --configuration compileClasspath --console=plain | grep -c springframework`
Expected: `0`.

- [ ] **Step 2: Sealed clauses are as intended**

Run: `grep -A3 "permits ExecutorShutdownException" maestro-core/src/main/java/io/b2mash/maestro/core/exception/MaestroControlFlowError.java && grep -c "StaleRunException\|ActivityTimeoutException" maestro-core/src/main/java/io/b2mash/maestro/core/exception/MaestroException.java`
Expected: the `permits` of `MaestroControlFlowError` lists exactly `ExecutorShutdownException, WorkflowTerminatedException, UnknownWorkflowHistoryException`; the second count is `2`.

- [ ] **Step 3: Full build**

Run: `./gradlew build --console=plain`
Expected: `BUILD SUCCESSFUL`; `maestro-samples:sample-payment-gateway:test` reports 2 tests.

- [ ] **Step 4: Real-backend suites**

Run: `./gradlew :maestro-integration-tests:test --console=plain`
Expected: `BUILD SUCCESSFUL`, including `EnginePostgresTerminateIT`, `EnginePostgresActivityTimeoutIT`, `EnginePostgresDualExecutionIT` and the two new `EnginePostgresSagaIT` tests. No test in this plan depends on wall-clock margins tighter than 5 s against a 30 s backoff or a 5 s sleep.

- [ ] **Step 5: Release notes read as one story**

Open `docs/release-notes.md` `## Unreleased` and confirm the five entries from Tasks 1–5 are present, each naming its spec row (F2, C6/C11, C11, C5, C7), and that the Task 2 entry's shutdown behaviour change and the Task 3 entry's timeout behaviour change are both flagged **Behaviour change**.

---

## Self-review (done while writing)

- **Spec coverage:** C5 → Task 4 (both halves: status writer and adoption); C6 → Task 2; C7 → Task 5; C11 → Task 3 (timeout, docs) and Task 2 (interrupt source, backoff abandonment); F2 → Task 1 (all four sub-items a–d). README Review Focus 3 → Task 2 Step 5 first test and Step 9.
- **Step scan:** every code step names the file, the signature and the pinned values; bodies appear only for `RunCancellation`'s latch/CAS shape and the `FutureTask` unwrap ordering, which the tests do not determine. Two test bodies are elided to a comment where they are the same shape as the test above them.
- **Type consistency:** `RunCancellation` (Task 2) is used by `WorkflowContext`, `RetryExecutor`, `RunningWorkflow`; `StaleRunException` (Task 4) is used by `InstanceStatusWriter`, `SagaManager` (Tasks 4 and 5) and the executor arms; `causedBy` (Task 1) is used by the payment sample and Task 3's tests; `ActivityTimeoutException` (Task 3) is referenced by Task 3's docs only. `executeWithRetry`'s 5-arg form is introduced once (Task 2) and not changed by Task 3, which bounds the attempt inside the `Callable` instead.
- **Review Focus:** five lines, each pinned in the named task (2: Step 5 tests 1 and 3; 3: Step 1 test 4; 4: Step 1 second handler test; 5: Step 5).
- **Proportion:** the plan is long relative to the diff because each fix carries two pins and documentation; implementation steps stay at signature level.
