# Replay Drift and Error Handling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every way a workflow's replay can disagree with its persisted history *visible* — a renamed/reordered step stands down with its own reason instead of returning the wrong payload, an escaped `Error` ends `FAILED` instead of looping silently, an unreadable payload names its cause, and a memoized failure replays as the same type it was thrown as.

**Architecture:** All four fixes stay inside `maestro-core` and reuse the existing stand-down channel (`UnknownWorkflowHistoryException` → `WorkflowExecutor.handleUnknownHistoryStandDown` → `EngineObserver.standDown(reason, …)`). A new `ReplayGuard` helper sits beside `UnknownHistoryGuard` and is called at every replay read *before* any live side effect; a new `Kind`/`StandDownReason` pair carries the drift; `executeWorkflow` gains an `Error` arm that routes to the existing failure path; exception reconstruction gets a deterministic fallback type. The sealed `MaestroControlFlowError` `permits` clause does not change.

**Tech Stack:** Java 25, JUnit 5 + Awaitility (core unit tests against `VersionedInMemoryStore`), Testcontainers Postgres via `PostgresIntegrationSupport` + `MaestroEngineHarness` (integration pins), Micrometer (reason tag), Jackson 3.

**Spec:** [`00-spec-independent-assessment.md`](00-spec-independent-assessment.md) §3a rows **C8, C9, C10, C15** and §4 row **F3**. Plan index and shared rules: [`README.md`](README.md).

## Global Constraints

See [`README.md` § Global Constraints](README.md#global-constraints). Two of them bind hardest here: the `MaestroControlFlowError` `permits` clause stays exactly three types (every new stand-down flavour is a `Kind` of `UnknownWorkflowHistoryException`, never a fourth subtype), and every fix gets a unit pin on the fakes **and** an `@Tag("integration")` pin on real Postgres.

**Execution order within the cycle:** this plan runs after Plan 01. Plan 01 rewrites `DefaultWorkflowOperations.parallel()` and `SagaManager`; the `parallel()` line numbers cited below are a locator as of `main` @ 4eb28e9 — re-read the region and keep Plan 01's fork-marker payload field name if it changed `branchCount`.

**Pre-existing state the spec rows understate (read before Task 4):** `StandDownReason.UNKNOWN_EVENT_PAYLOAD`, `UnknownWorkflowHistoryException.Kind.UNKNOWN_EVENT_PAYLOAD`, the `unknown_event_payload` metric tag and `docs/operations.md` §10.4 ("the alarm that matters … on a homogeneous fleet") **already exist** on `main`. The split the C9 decision asks for has landed under that name. This plan therefore keeps `UNKNOWN_EVENT_PAYLOAD` (renaming a documented metric tag for no behavioural gain is churn) and closes what is actually still missing: the stand-down detail carries no cause, and the operator text names `workflow.version()` as the only remedy.

**Out of scope here (owned elsewhere):** `SagaManager`'s own marker reads (`COMPENSATION_STARTED`/`COMPLETED`, Plan 01 — compensation *steps* go through the activity proxy and get Task 2's guard for free); the activity duplicate-adopt branch `ActivityInvocationHandler.java:483-499` (Plan 03, C5 dual-execution stop); the `ActivityExecutionException` wrapping contract (Plan 03, F2 — Task 5 fixes reconstruction fidelity only); `docs/test-plan.md` beyond the two lines Task 3 touches (Plan 06).

## Review Focus

Inputs the spec implies but which are easy to leave untested. Each line's pin lives in the task named.

1. **`version()` peeks over a non-marker event** — the "history predates the change" classification (`DefaultWorkflowOperations.java:742-756`) must still resolve `DEFAULT_VERSION` with no stand-down; a drift guard applied naively to the peek would break every pre-change instance. → Task 3, `versionPeekOverForeignEvent_isNotDrift`.
2. **A pre-arrived signal under a renamed `awaitSignal`** — the stand-down must happen before `getUnconsumedSignals`, so the signal stays durably unconsumed (never discarded, never consumed at the wrong gate). → Task 3, `awaitSignalRenamed_standsDownWithoutConsuming`.
3. **A control-flow `Error` thrown straight from the workflow body** — the new `catch (Error)` arm must not intercept it; the run must stand down exactly as before. → Task 1, `controlFlowErrorFromBody_stillStandsDown`.
4. **A stored `exceptionType` naming a `MaestroControlFlowError` subtype** — reconstruction must never instantiate an engine control-flow signal from a string in the database. → Task 5, `controlFlowErrorName_isNeverInstantiated`.
5. **Re-adoption churn after a drift stand-down on Postgres** — three recovery passes over a `sleep()` whose slot is occupied must leave the timer-row count unchanged (today each pass inserts one PENDING row, then stands down as `STALE_RUN`). → Task 3, `sleepOnOccupiedSlot_leaksNoTimerRowAcrossRecoveryPasses`.

---

### Task 1: An `Error` escaping the workflow body ends `FAILED` (C8)

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java:1450-1532` (catch arms of `executeWorkflow`), `:1703-1709` (`handleWorkflowFailure` signature)
- Test: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/WorkflowExecutorErrorEscapeTest.java` (create)
- Test: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/ErrorEscapeFailsWorkflowIT.java` (create)
- Modify: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/workflows/TestWorkflows.java` (add `ErrorThrowingWorkflow`)
- Modify: `docs/release-notes.md:3` (`## Unreleased`)

**Interfaces:**
- Consumes: `WorkflowExecutor.startWorkflow(...)`, `RecordingEngineObserver.failed()` / `.standDowns()`, `TestTerminalWait.awaitTerminal(store, workflowId, timeout)` (`maestro-core/src/test/java/io/b2mash/maestro/core/TestTerminalWait.java:103`), `VersionedInMemoryStore` (`maestro-core/src/test/.../engine/VersionedInMemoryStore.java`), `MaestroEngineHarness`, `WorkflowHandle.awaitTerminal(Duration)`.
- Produces: `private void failAndCompensate(WorkflowContext ctx, WorkflowInstance instance, Throwable failure, CompensationStack stack, boolean parallelCompensation)` in `WorkflowExecutor` — the single entry for "record FAILED + compensate" used by both the `Exception` and the `Error` arm. `handleWorkflowFailure`'s third parameter widens from `Exception` to `Throwable`. No public surface changes.

- [ ] **Step 1: Write the failing unit test**

`WorkflowExecutorErrorEscapeTest`, fixture copied from `UnknownHistoryStandDownTest.setUp` (`:72-81`: `VersionedInMemoryStore`, `RecordingEngineObserver`, `PayloadSerializer`, `WorkflowExecutor` with service name `"node"`). Workflow fixture:

```java
@DurableWorkflow(name = "ErrorThrowingWorkflow")
public static class ErrorThrowingWorkflow {
    private final AtomicInteger compensations;
    private final Supplier<? extends Throwable> toThrow;
    @WorkflowMethod
    public String run() throws Throwable {
        var workflow = WorkflowContext.current();
        workflow.addCompensation(compensations::incrementAndGet);
        workflow.currentTime();          // seq 1 — a memoized step before the blow-up
        throw toThrow.get();
    }
}
```

Tests:

```java
@Test void assertionError_endsFailedWithCompensation() {
    var compensations = new AtomicInteger();
    var instanceId = start("error-assertion", compensations, () -> new AssertionError("boom"));
    TestTerminalWait.awaitTerminal(store, "error-assertion", BOUND);

    var instance = store.getInstance("error-assertion").orElseThrow();
    var failedEvents = store.getEvents(instanceId).stream()
            .filter(e -> e.eventType() == EventType.WORKFLOW_FAILED).toList();
    assertAll(
        () -> assertEquals(WorkflowStatus.FAILED, instance.status()),
        () -> assertEquals(1, failedEvents.size()),
        () -> assertEquals("java.lang.AssertionError",
                failedEvents.getFirst().payload().path("exceptionType").asString()),
        () -> assertEquals("boom", failedEvents.getFirst().payload().path("message").asString()),
        () -> assertEquals(1, observer.failed().size()),
        () -> assertEquals("java.lang.AssertionError", observer.failed().getFirst().exceptionType()),
        () -> assertEquals(1, compensations.get(), "a FAILED workflow runs its compensations"),
        () -> assertTrue(observer.standDowns().isEmpty(), "an Error is a failure, not a stand-down"),
        () -> assertFalse(executor.isRunning("error-assertion")),
        () -> assertTrue(store.getRecoverableInstances().stream()
                .noneMatch(i -> i.workflowId().equals("error-assertion")),
                "FAILED is terminal — the recovery poller must never re-adopt it"));
}

@Test void stackOverflowError_endsFailed() {
    // same shape, () -> new StackOverflowError("deep"); asserts FAILED, exceptionType
    // "java.lang.StackOverflowError", observer.failed().size() == 1
}

@Test void controlFlowErrorFromBody_stillStandsDown() {
    // Review Focus 3. () -> new ExecutorShutdownException("deploying")
    await().atMost(BOUND).until(() -> !executor.isRunning("error-controlflow"));
    var instance = store.getInstance("error-controlflow").orElseThrow();
    assertAll(
        () -> assertEquals(WorkflowStatus.RUNNING, instance.status(),
                "a control-flow signal writes nothing — the Error arm must not have caught it"),
        () -> assertTrue(observer.failed().isEmpty()),
        () -> assertEquals(0, countOfType(instanceId, EventType.WORKFLOW_FAILED)),
        () -> assertEquals(0, compensations.get()));
}
```

- [ ] **Step 2: Run the unit test to verify it fails for the right reason**

Run: `./gradlew :maestro-core:test --tests '*WorkflowExecutorErrorEscapeTest*' --console=plain`
Expected: `assertionError_endsFailedWithCompensation` and `stackOverflowError_endsFailed` FAIL on `TestTerminalWait.awaitTerminal` timing out (status stays `RUNNING`, no `WORKFLOW_FAILED`). `controlFlowErrorFromBody_stillStandsDown` PASSES already (it pins behaviour that must survive).

- [ ] **Step 3: Add the `Error` arm and extract `failAndCompensate`**

In `WorkflowExecutor.executeWorkflow` (`:1496-1532`): move the body of the `catch (Exception e)` arm — the `handleWorkflowFailure` call plus its four nested `catch` arms (shutdown / terminated / unknown-history / stale-run during compensation) — into

```java
private void failAndCompensate(WorkflowContext ctx, WorkflowInstance instance, Throwable failure,
                               CompensationStack compensationStack, boolean parallelCompensation)
```

and have `catch (Exception e)` call it. Add, **after** the `catch (DuplicateEventException staleRun)` arm (`:1479`) and **before** `catch (Exception e)` (`:1496`):

```java
} catch (Error e) {
    if (e instanceof MaestroControlFlowError) throw e;   // the three arms above own these; one-line guard per CLAUDE.md
    failAndCompensate(ctx, instance, e, compensationStack, parallelCompensation);
}
```

Widen `handleWorkflowFailure(..., Exception exception, ...)` (`:1703-1709`) to `Throwable failure`; its existing `logger.error("Workflow '{}' failed: {}", …)` (`:1710`) already runs under the thread's `WorkflowMDC` and its `observer.workflowFailed(observed(instance), failure.getClass().getName())` (`:1737`) is the callback the decision requires — no second log line. Add a comment on the arm stating why it sits between the control-flow arms and `Exception`: an `Error` that is not a control-flow signal is a workflow failure (`StackOverflowError`, `AssertionError`, `ExceptionInInitializerError`), and before this arm it unwound through `finally`, released the lock, and left the row `RUNNING` for the poller to re-adopt forever with no log and no callback.

- [ ] **Step 4: Run the unit tests to verify they pass**

Run: `./gradlew :maestro-core:test --tests '*WorkflowExecutorErrorEscapeTest*' --tests '*StandDown*' --console=plain`
Expected: all PASS, including the four existing `*StandDown*` suites (the control-flow arms are untouched).

- [ ] **Step 5: Write the failing Postgres pin**

Add to `TestWorkflows`:

```java
/** Runs one activity, then throws a JVM Error — the fixture for C8. */
@DurableWorkflow(name = "ErrorThrowingWorkflow")
public static class ErrorThrowingWorkflow {
    @ActivityStub ChainActivities activities;
    @WorkflowMethod
    public String run(String input) {
        activities.stepOne(input);
        throw new AssertionError("boom");
    }
}
```

`ErrorEscapeFailsWorkflowIT extends PostgresIntegrationSupport`, `@Tag("integration")`; copy `lockRowHeld` from `UnknownEventStandDownIT.java:281-292`:

```java
@Test void assertionErrorEndsFailedOnPostgres() throws Exception {
    var recorder = new CountingActivities.Recorder();
    var observer = new RecordingObserver();
    var workflowId = MaestroEngineHarness.uniqueWorkflowId("error-escape");
    try (var node = MaestroEngineHarness.builder(store, objectMapper).serviceName("node-a")
            .lock(newLock()).observer(observer).build()) {
        node.registerActivities(ChainActivities.class, new CountingActivities.RecordingChainActivities(recorder));
        node.registerWorkflow(new TestWorkflows.ErrorThrowingWorkflow());
        var handle = node.start(workflowId, TestWorkflows.ErrorThrowingWorkflow.class, "seed");

        assertEquals(WorkflowStatus.FAILED, handle.awaitTerminal(BOUND));
        var failed = store.getEvents(handle.instanceId()).stream()
                .filter(e -> e.eventType() == EventType.WORKFLOW_FAILED).toList();
        assertAll(
            () -> assertEquals(1, failed.size()),
            () -> assertEquals("java.lang.AssertionError", failed.getFirst().payload().path("exceptionType").asString()),
            () -> assertEquals(List.of(workflowId), observer.failed()),
            () -> assertTrue(observer.standDowns().isEmpty()),
            () -> assertEquals(1, recorder.count("stepOne")),
            () -> assertFalse(lockRowHeld(workflowId)),
            () -> assertEquals(0, node.recover(), "FAILED is terminal: nothing to re-adopt"));
    }
}
```

- [ ] **Step 6: Run the integration pin**

Run: `./gradlew :maestro-integration-tests:test --tests '*ErrorEscapeFailsWorkflowIT*' --console=plain` (Docker required)
Expected: PASS. (If run against a checkout *without* Step 3 it fails on `awaitTerminal` — do that once to confirm the pin bites, then move on.)

- [ ] **Step 7: Release note and commit**

Under `## Unreleased` in `docs/release-notes.md` add `### Fixed — A JVM Error escaping a workflow method now ends the workflow FAILED` with two bullets: what happened before (lock released, row left `RUNNING`, re-adopted every recovery pass, no log, no `workflowFailed`), and what happens now (same path as an exception: ERROR log, `workflowFailed`, compensation, `WORKFLOW_FAILED` with the `Error`'s class name; control-flow signals unaffected). Cite spec C8.

```bash
git add maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java \
        maestro-core/src/test/java/io/b2mash/maestro/core/engine/WorkflowExecutorErrorEscapeTest.java \
        maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/ErrorEscapeFailsWorkflowIT.java \
        maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/workflows/TestWorkflows.java \
        docs/release-notes.md
git commit -m "fix(engine): an Error escaping the workflow body ends FAILED instead of looping (C8)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: `ReplayGuard` + the `NONDETERMINISTIC_HISTORY` reason, wired at the activity and side-effect replay sites (C10, part 1)

**Files:**
- Create: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/ReplayGuard.java`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/observe/StandDownReason.java:7-17`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/exception/UnknownWorkflowHistoryException.java:5-12` (class Javadoc), `:56-74` (`Kind`), `:76-111` (fields/ctor/accessors)
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java:1681-1699` (`handleUnknownHistoryStandDown`, `standDownReason`)
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/ActivityInvocationHandler.java:216-229` (replay branch of `invoke`)
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/DefaultWorkflowOperations.java:663-667` (`currentTime`), `:686-690` (`randomUUID`)
- Modify: `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/observe/MicrometerEngineObserver.java:182-188`
- Test: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/ReplayGuardTest.java` (create)
- Test: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/ReplayDriftStandDownTest.java` (create — Task 3 adds more tests to it)
- Test: `maestro-spring-boot-starter/src/test/java/io/b2mash/maestro/spring/observe/MicrometerEngineObserverTest.java:166-178`

**Interfaces:**
- Consumes: `UnknownHistoryGuard.requireKnown(...)` (always called first — `ReplayGuard` assumes a known type), `WorkflowEvent.stepName()` / `.eventType()` / `.payload()`.
- Produces (used by Task 3 and Task 4):
  - `StandDownReason.NONDETERMINISTIC_HISTORY`; Micrometer tag `nondeterministic_history`.
  - `UnknownWorkflowHistoryException.Kind.NONDETERMINISTIC_HISTORY`.
  - `UnknownWorkflowHistoryException(String workflowId, int sequenceNumber, Kind kind, String message, String detail)` — new primary constructor; the existing 4-arg constructor delegates with `detail = "seq=" + sequenceNumber`. New accessor `public String detail()` — the exact string handed to `EngineObserver.standDown`'s third argument.
  - `public static WorkflowEvent ReplayGuard.requireMatch(WorkflowEvent stored, String workflowId, String expectedStepName, EventType... compatibleTypes)` — returns `stored` when `stored.eventType()` is one of `compatibleTypes` **and** `expectedStepName.equals(stored.stepName())`; otherwise throws `UnknownWorkflowHistoryException` with `Kind.NONDETERMINISTIC_HISTORY` and `detail = "seq=%d expected=%s stored=%s type=%s".formatted(seq, expectedStepName, stored.stepName(), stored.eventType())`.
  - `public static WorkflowEvent ReplayGuard.requireBranchCount(WorkflowEvent stored, String workflowId, int expectedBranchCount)` — reads `stored.payload().path("branchCount")`; a non-numeric/missing value throws `UnknownHistoryGuard.payloadStandDown(...)` (an unreadable marker is the payload kind, not drift); a different value throws `Kind.NONDETERMINISTIC_HISTORY` with `detail = "seq=%d expected=%s branches=%d stored=%s branches=%d".formatted(seq, stored.stepName(), expected, stored.stepName(), actual)`.

- [ ] **Step 1: Write the failing `ReplayGuard` unit tests**

`ReplayGuardTest` (pure, no executor):

```java
@Test void matchingTypeAndStep_returnsTheEvent() {
    var e = event(2, EventType.ACTIVITY_COMPLETED, "Acts.a");
    assertSame(e, ReplayGuard.requireMatch(e, "wf", "Acts.a", EventType.ACTIVITY_COMPLETED, EventType.ACTIVITY_FAILED));
}
@Test void differentStepName_standsDownAsNondeterministic() {
    var ex = assertThrows(UnknownWorkflowHistoryException.class, () ->
        ReplayGuard.requireMatch(event(2, EventType.ACTIVITY_COMPLETED, "Acts.old"), "wf", "Acts.new", EventType.ACTIVITY_COMPLETED));
    assertAll(
        () -> assertEquals(UnknownWorkflowHistoryException.Kind.NONDETERMINISTIC_HISTORY, ex.kind()),
        () -> assertEquals(2, ex.sequenceNumber()),
        () -> assertEquals("seq=2 expected=Acts.new stored=Acts.old type=ACTIVITY_COMPLETED", ex.detail()));
}
@Test void incompatibleType_standsDownEvenWhenStepNameMatches() {
    var ex = assertThrows(UnknownWorkflowHistoryException.class, () ->
        ReplayGuard.requireMatch(event(2, EventType.SIDE_EFFECT, "$maestro:sleep"), "wf", "$maestro:sleep", EventType.TIMER_SCHEDULED));
    assertEquals("seq=2 expected=$maestro:sleep stored=$maestro:sleep type=SIDE_EFFECT", ex.detail());
}
@Test void nullStoredStepName_standsDown() {   // a lifecycle event sitting in a replay slot
    var ex = assertThrows(UnknownWorkflowHistoryException.class, () ->
        ReplayGuard.requireMatch(event(2, EventType.WORKFLOW_FAILED, null), "wf", "Acts.a", EventType.ACTIVITY_COMPLETED));
    assertEquals("seq=2 expected=Acts.a stored=null type=WORKFLOW_FAILED", ex.detail());
}
@Test void branchCountMismatch_standsDownAsNondeterministic() {
    var ex = assertThrows(UnknownWorkflowHistoryException.class, () ->
        ReplayGuard.requireBranchCount(event(2, EventType.SIDE_EFFECT, "$maestro:parallel", "{\"branchCount\":3}"), "wf", 2));
    assertAll(
        () -> assertEquals(UnknownWorkflowHistoryException.Kind.NONDETERMINISTIC_HISTORY, ex.kind()),
        () -> assertEquals("seq=2 expected=$maestro:parallel branches=2 stored=$maestro:parallel branches=3", ex.detail()));
}
@Test void branchCountUnreadable_isThePayloadKindNotDrift() {
    var ex = assertThrows(UnknownWorkflowHistoryException.class, () ->
        ReplayGuard.requireBranchCount(event(2, EventType.SIDE_EFFECT, "$maestro:parallel", "{\"reshaped\":true}"), "wf", 2));
    assertEquals(UnknownWorkflowHistoryException.Kind.UNKNOWN_EVENT_PAYLOAD, ex.kind());
}
@Test void fourArgConstructor_keepsTheSeqOnlyDetail() {
    var ex = new UnknownWorkflowHistoryException("wf", 7, UnknownWorkflowHistoryException.Kind.UNKNOWN_EVENT_TYPE, "m");
    assertEquals("seq=7", ex.detail());
}
```

- [ ] **Step 2: Run to verify compile failure**

Run: `./gradlew :maestro-core:compileTestJava --console=plain`
Expected: FAIL — `cannot find symbol: class ReplayGuard`, `NONDETERMINISTIC_HISTORY`, `detail()`.

- [ ] **Step 3: Implement the enum constant, the `Kind`, the exception detail, `ReplayGuard`, and the metric tag**

- `StandDownReason.NONDETERMINISTIC_HISTORY` with Javadoc: *"Replay met a persisted event whose step name, type, or fork shape does not match the operation the current code performs at that sequence — the workflow code changed without a `workflow.version()` gate (C10). A deploy rollback is the fix; the instance is left recoverable."* Place it after `UNKNOWN_EVENT_PAYLOAD`.
- `UnknownWorkflowHistoryException.Kind.NONDETERMINISTIC_HISTORY` with the same Javadoc; rewrite the class Javadoc's first paragraph (`:5-12`) so "Both mean the same thing: a newer node wrote that history" becomes a three-way list: unknown type (newer node), unreadable payload (newer node *or* same-version incompatible change), nondeterministic history (current code disagrees with the recorded path). The common ground that justifies the shared base is unchanged: *none* of them is a workflow failure, and standing down keeps rollback as the remedy.
- The 5-arg constructor and `detail()` as specified in Interfaces. `WorkflowExecutor.handleUnknownHistoryStandDown` (`:1691-1692`) passes `e.detail()` instead of `"seq=" + e.sequenceNumber()`; `standDownReason(Kind)` (`:1695-1699`) becomes an exhaustive `switch` over the three kinds.
- `ReplayGuard` — `public final class`, private constructor, Javadoc modelled on `UnknownHistoryGuard`'s (why it is public: `SagaManager` parity; internal API, no compatibility promise; stateless/thread-safe). The class Javadoc states the ordering contract: *always after `UnknownHistoryGuard.requireKnown`, always before any live side effect at that slot*.
- `MicrometerEngineObserver.reasonTag` (`:182-188`): `case NONDETERMINISTIC_HISTORY -> "nondeterministic_history";` (the switch is exhaustive, so the build forces this). Update the Javadoc meter table at `:40` if it enumerates tag values.

- [ ] **Step 4: Run the guard tests**

Run: `./gradlew :maestro-core:test --tests '*ReplayGuardTest*' --console=plain`
Expected: PASS (7 tests).

- [ ] **Step 5: Write the failing drift pins for the activity and side-effect sites**

`ReplayDriftStandDownTest`, fixture copied from `UnknownHistoryStandDownTest` (`:67-81` setUp, `:258-283` `startAndPlant` with gate, `:308-340` workflow shape). Workflow fixture `DriftWorkflow` runs `workflow.currentTime()` at seq 1 (live), awaits the gate, then runs an injected `Runnable secondOp` at seq 2, and returns `"completed"`. The activity stub:

```java
@Activity(name = "Acts")
public interface Acts { String newName(); }
// proxy exactly as ForeignPayloadStandDownTest.java:240-243:
//   new ActivityProxyFactory().createProxy(Acts.class, impl, store, null, null,
//           RetryPolicy.defaultPolicy(), Duration.ofSeconds(5), serializer, new RetryExecutor())
```

Tests (each ends with `awaitRunEnded(workflowId)` then `await().until(() -> !observer.standDowns().isEmpty())`):

```java
@Test void renamedActivity_standsDownBeforeExecuting() {
    var invocations = new AtomicInteger();
    var run = startAndPlant("drift-renamed", () -> acts.newName(),
            plantAt(2, EventType.ACTIVITY_COMPLETED, "Acts.oldName", json("\"stale\"")));
    ...
    var call = observer.standDowns().getFirst();
    assertAll(
        () -> assertEquals(1, observer.standDowns().size()),
        () -> assertEquals(StandDownReason.NONDETERMINISTIC_HISTORY, call.reason()),
        () -> assertEquals("seq=2 expected=Acts.newName stored=Acts.oldName type=ACTIVITY_COMPLETED", call.detail()),
        () -> assertEquals(0, invocations.get(), "the activity impl must not run — stand down at the READ"),
        () -> assertEquals(1, store.appendAttempts(), "nothing appended after the planted row"),
        () -> assertEquals(WorkflowStatus.RUNNING, store.getInstance("drift-renamed").orElseThrow().status()),
        () -> assertEquals(0, countOfType(run.instanceId(), EventType.WORKFLOW_FAILED)),
        () -> assertTrue(observer.failed().isEmpty()),
        () -> assertTrue(observer.compensating().isEmpty()));
}

@Test void sideEffectOnForkSlot_standsDownOnStepNameAlone() {
    // type matches (SIDE_EFFECT) — only the step name differs
    startAndPlant("drift-sideeffect", () -> WorkflowContext.current().currentTime(),
            plantAt(2, EventType.SIDE_EFFECT, "$maestro:parallel", json("{\"branchCount\":2}")));
    ...
    assertEquals("seq=2 expected=$maestro:currentTime stored=$maestro:parallel type=SIDE_EFFECT",
            observer.standDowns().getFirst().detail());
    assertEquals(1, store.appendAttempts());
}

@Test void matchingHistory_replaysNormally() {
    var invocations = new AtomicInteger();
    var result = new AtomicReference<String>();
    startAndPlant("drift-none", () -> result.set(acts.newName()),
            plantAt(2, EventType.ACTIVITY_COMPLETED, "Acts.newName", json("\"memo\"")));
    TestTerminalWait.awaitTerminal(store, "drift-none", BOUND);
    assertAll(
        () -> assertEquals(WorkflowStatus.COMPLETED, store.getInstance("drift-none").orElseThrow().status()),
        () -> assertEquals("memo", result.get()),
        () -> assertEquals(0, invocations.get()),
        () -> assertTrue(observer.standDowns().isEmpty()));
}
```

Add to `MicrometerEngineObserverTest` (`:166-178`) one more call `observer.standDown(StandDownReason.NONDETERMINISTIC_HISTORY, "wf-5", "seq=2 expected=a stored=b type=ACTIVITY_COMPLETED")` and `assertThat(registry.get("maestro.standdown").tag("reason", "nondeterministic_history").counter().count()).isEqualTo(1.0)`.

- [ ] **Step 6: Run to verify the pins fail for the right reason**

Run: `./gradlew :maestro-core:test --tests '*ReplayDriftStandDownTest*' --console=plain`
Expected: `renamedActivity_standsDownBeforeExecuting` FAILS — today the proxy returns `"stale"` and the run **completes** (the `await` on `standDowns` times out; `matchingHistory_replaysNormally` passes). `sideEffectOnForkSlot_standsDownOnStepNameAlone` FAILS with reason `UNKNOWN_EVENT_PAYLOAD` — today `currentTime()` sees a `SIDE_EFFECT`, tries to read the fork marker `{"branchCount":2}` as an `Instant`, and the resulting `SerializationException` is misclassified as "a newer node's payload" (the C9 shape of the C10 bug). Record both failure messages in the commit body — they are the spec's C10 evidence.

- [ ] **Step 7: Wire `ReplayGuard` at the activity and side-effect sites**

- `ActivityInvocationHandler.invoke` (`:216-229`): immediately after `UnknownHistoryGuard.requireKnown(storedEvent.get(), ctx.workflowId())` add `ReplayGuard.requireMatch(storedEvent.get(), ctx.workflowId(), stepName, EventType.ACTIVITY_COMPLETED, EventType.ACTIVITY_FAILED);`. Leave `handleReplay`'s `default ->` arm (`:276-278`) in place but reword its message to say it is unreachable once `ReplayGuard` has admitted the event.
- `DefaultWorkflowOperations.currentTime` (`:663-667`) and `randomUUID` (`:686-690`): the `if (storedEvent.isPresent() && eventType == SIDE_EFFECT)` becomes `if (storedEvent.isPresent()) { ReplayGuard.requireMatch(storedEvent.get(), ctx.workflowId(), "$maestro:currentTime", EventType.SIDE_EFFECT); return …; }` (resp. `"$maestro:randomUUID"`). Any present event that is not a matching side effect now stands down *before* the live append that used to produce the `STALE_RUN`.

- [ ] **Step 8: Run the pins and the neighbours**

Run: `./gradlew :maestro-core:test --tests '*ReplayDriftStandDownTest*' --tests '*ReplayGuardTest*' --tests '*StandDown*' --tests '*ActivityInvocationHandlerTest*' --console=plain && ./gradlew :maestro-spring-boot-starter:test --tests '*MicrometerEngineObserverTest*' --console=plain`
Expected: all PASS.

- [ ] **Step 9: Commit**

```bash
git add maestro-core/src/main/java/io/b2mash/maestro/core/engine/ReplayGuard.java \
        maestro-core/src/main/java/io/b2mash/maestro/core/observe/StandDownReason.java \
        maestro-core/src/main/java/io/b2mash/maestro/core/exception/UnknownWorkflowHistoryException.java \
        maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java \
        maestro-core/src/main/java/io/b2mash/maestro/core/engine/ActivityInvocationHandler.java \
        maestro-core/src/main/java/io/b2mash/maestro/core/engine/DefaultWorkflowOperations.java \
        maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/observe/MicrometerEngineObserver.java \
        maestro-core/src/test/java/io/b2mash/maestro/core/engine/ReplayGuardTest.java \
        maestro-core/src/test/java/io/b2mash/maestro/core/engine/ReplayDriftStandDownTest.java \
        maestro-spring-boot-starter/src/test/java/io/b2mash/maestro/spring/observe/MicrometerEngineObserverTest.java
git commit -m "fix(engine): detect replay drift at activity and side-effect slots — stand down as NONDETERMINISTIC_HISTORY (C10)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Drift detection at `sleep`, `awaitSignal`, `parallel`; the leaked-timer pin; Postgres pins; docs (C10, part 2; F3)

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/DefaultWorkflowOperations.java:203-207` (`sleep` replay check), `:559-566` (`parallel` fork check — re-read after Plan 01)
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/SignalManager.java:287-310` (`awaitSignal` replay checks)
- Modify: `maestro-test/src/main/java/io/b2mash/maestro/test/DeterminismChecker.java:14-18`
- Modify: `docs/operations.md:457-465` (§10.3 reason table) and append a new `### 10.7 Replay drift: nondeterministic_history` after §10.6; `docs/observability.md:94`, `:104-105` (reason-tag tables); `docs/test-plan.md:91`, `:398`; `docs/release-notes.md:3`
- Test: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/ReplayDriftStandDownTest.java` (extend)
- Test: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/ReplayDriftStandDownIT.java` (create)

**Interfaces:**
- Consumes: `ReplayGuard.requireMatch(...)`, `ReplayGuard.requireBranchCount(...)`, `StandDownReason.NONDETERMINISTIC_HISTORY` (Task 2); `TestWorkflows.SignalWorkflow` / `SleepingWorkflow`, `UnknownEventStandDownIT` helpers (`insertRawEvent :253-267`, `deleteEvent :269-278`, `lockRowHeld :281-292`, `fingerprint :299-303`).
- Produces: no new interfaces. `version()` is deliberately **not** guarded (see Review Focus 1).

- [ ] **Step 1: Write the failing unit pins**

Add to `ReplayDriftStandDownTest`:

```java
@Test void sleepOnOccupiedSlot_standsDownWithoutSavingATimer() {
    startAndPlant("drift-sleep", () -> WorkflowContext.current().sleep(Duration.ofHours(1)),
            plantAt(2, EventType.ACTIVITY_COMPLETED, "Acts.oldName", json("\"stale\"")));
    ...
    assertAll(
        () -> assertEquals(StandDownReason.NONDETERMINISTIC_HISTORY, call.reason()),
        () -> assertEquals("seq=2 expected=$maestro:sleep stored=Acts.oldName type=ACTIVITY_COMPLETED", call.detail()),
        () -> assertTrue(store.getDueTimers(Instant.now().plus(Duration.ofDays(2)), 100).isEmpty(),
                "the pre-fix symptom: a PENDING timer row saved before the duplicate append — one leaked row per recovery pass"),
        () -> assertEquals(1, store.appendAttempts()));
}

@Test void awaitSignalRenamed_standsDownWithoutConsuming() {   // Review Focus 2
    // a signal for the NEW name is already waiting — the live path would consume it
    var run = startAndPlant("drift-signal", () -> WorkflowContext.current().awaitSignal("rejected", String.class, Duration.ofSeconds(30)),
            plantAt(2, EventType.SIGNAL_RECEIVED, "$maestro:awaitSignal:approved", json("\"yes\"")),
            instanceId -> store.saveSignal(new WorkflowSignal(UUID.randomUUID(), instanceId, "drift-signal", "rejected", json("\"no\""), false, Instant.now(), null)));
    ...
    assertAll(
        () -> assertEquals("seq=2 expected=$maestro:awaitSignal:rejected stored=$maestro:awaitSignal:approved type=SIGNAL_RECEIVED", call.detail()),
        () -> assertEquals(1, store.getUnconsumedSignals("drift-signal", "rejected").size(), "never consumed, never discarded"),
        () -> assertTrue(observer.signalConsumed().isEmpty()),
        () -> assertEquals(1, store.appendAttempts()));
}

@Test void parallelBranchCountMismatch_standsDownBeforeForking() {
    var branchRuns = new AtomicInteger();
    Callable<String> task = () -> { branchRuns.incrementAndGet(); return "b"; };
    startAndPlant("drift-parallel", () -> WorkflowContext.current().parallel(List.of(task, task)),
            plantAt(2, EventType.SIDE_EFFECT, "$maestro:parallel", json("{\"branchCount\":3}")));
    ...
    assertAll(
        () -> assertEquals("seq=2 expected=$maestro:parallel branches=2 stored=$maestro:parallel branches=3", call.detail()),
        () -> assertEquals(0, branchRuns.get(), "no branch may start"),
        () -> assertEquals(1, store.appendAttempts()));
}

@Test void versionPeekOverForeignEvent_isNotDrift() {   // Review Focus 1
    var resolved = new AtomicInteger(-1);
    var result = new AtomicReference<String>();
    startAndPlant("drift-version", () -> { resolved.set(WorkflowContext.current().version("c", 0, 1)); result.set(acts.newName()); },
            plantAt(2, EventType.ACTIVITY_COMPLETED, "Acts.newName", json("\"memo\"")));
    TestTerminalWait.awaitTerminal(store, "drift-version", BOUND);
    assertAll(
        () -> assertEquals(WorkflowStatus.COMPLETED, store.getInstance("drift-version").orElseThrow().status()),
        () -> assertEquals(WorkflowContext.DEFAULT_VERSION, resolved.get(), "a non-marker at the peek slot means 'predates the change'"),
        () -> assertEquals("memo", result.get()),
        () -> assertTrue(observer.standDowns().isEmpty()));
}
```

(`startAndPlant` gains an optional second planter for the pre-saved signal; `WorkflowSignal`'s record shape is at `maestro-core/src/main/java/io/b2mash/maestro/core/model/WorkflowSignal.java`.)

- [ ] **Step 2: Run to verify they fail for the right reason**

Run: `./gradlew :maestro-core:test --tests '*ReplayDriftStandDownTest*' --console=plain`
Expected: `sleepOnOccupiedSlot…` FAILS with reason `STALE_RUN` **and** one PENDING timer present (the leaked row, inserted before the duplicate append); `awaitSignalRenamed…` FAILS with **no stand-down at all** — `SignalManager` matches on type alone, so the recorded `"approved"` payload `"yes"` is returned as the answer to awaiting `"rejected"` and the run completes (the silent wrong-payload case; the pre-saved `"rejected"` signal is untouched); `parallelBranchCountMismatch…` FAILS — today the fork proceeds with 2 branches (`branchRuns == 2`), no stand-down at all; `versionPeekOverForeignEvent_isNotDrift` PASSES (it guards a regression Step 3 could introduce).

- [ ] **Step 3: Wire the three sites**

- `DefaultWorkflowOperations.sleep` (`:206-207`): replace `if (storedEvent.get().eventType() == EventType.TIMER_SCHEDULED) {` with an unconditional `ReplayGuard.requireMatch(storedEvent.get(), ctx.workflowId(), stepName, EventType.TIMER_SCHEDULED);` followed by the existing block body — so any present-but-wrong event throws before `store.saveTimer` (`:289`) can run. Leave the `nextSeq` outcome checks (`:216-231`) as they are.
- `SignalManager.awaitSignal` (`:287-310`): after `requireKnown`, if `storedEvent.isPresent()` call `ReplayGuard.requireMatch(storedEvent.get(), ctx.workflowId(), stepName, EventType.SIGNAL_RECEIVED, EventType.SIGNAL_TIMEOUT);` *before* the two type-specific `if`s, so the live path (`:313`, `getUnconsumedSignals`) is unreachable with a foreign event in the slot. `collectSignals` (`DefaultWorkflowOperations.java:504-520`) delegates to `awaitSignal` per signal and needs no change.
- `DefaultWorkflowOperations.parallel` (`:559-566`, re-read after Plan 01): when `storedEvent.isPresent()`, call `ReplayGuard.requireMatch(stored, workflowId, "$maestro:parallel", EventType.SIDE_EFFECT)` then `ReplayGuard.requireBranchCount(stored, workflowId, branchCount)`; only an *empty* slot takes the live append. If Plan 01 renamed the payload field, pass its name through — `requireBranchCount` reads `"branchCount"`; change that literal in one place if needed.
- `version()` (`:740-756`) — **no guard**; add a one-line comment at `:742` saying so and why (a non-marker at the peek is "predates the change" by design; `ReplayDriftStandDownTest.versionPeekOverForeignEvent_isNotDrift` pins it).

- [ ] **Step 4: Run the core suite**

Run: `./gradlew :maestro-core:test --console=plain`
Expected: BUILD SUCCESSFUL, 0 failures (the sleep/signal/parallel/timer suites exercise matching histories throughout; any new failure means a site now compares a step name the live path writes differently — fix the site, not the test).

- [ ] **Step 5: Write the failing Postgres pins**

`ReplayDriftStandDownIT extends PostgresIntegrationSupport`, `@Tag("integration")`, helpers copied from `UnknownEventStandDownIT` plus:

```java
private void setStepName(UUID instanceId, int seq, String stepName)      // UPDATE maestro_workflow_event SET step_name = ? WHERE workflow_instance_id = ? AND sequence_number = ?
private void overwriteAsActivity(UUID instanceId, int seq, String stepName) // UPDATE ... SET event_type = 'ACTIVITY_COMPLETED', step_name = ?, payload = '"x"'::jsonb WHERE ...
private int timerRowCount(UUID instanceId)                                // SELECT COUNT(*) FROM maestro_workflow_timer WHERE workflow_instance_id = ?
```

```java
@Test void renamedStepStandsDownThenCompletesOnceHistoryMatchesAgain() throws Exception {
    // Node A: SignalWorkflow to its park (copy UnknownEventStandDownIT.java:84-99). seq 1 = stepOne ACTIVITY_COMPLETED.
    var original = store.getEventBySequence(instanceId, 1).orElseThrow().stepName();
    setStepName(instanceId, 1, original + "-renamed");       // "the code was refactored under this history"
    var beforeStatus = handle.status(); var logBefore = fingerprint(instanceId);

    // Node B (same build, "new code"): adopts, replays seq 1, stands down
    try (var nodeB = ...observer(observer)...) {
        assertEquals(1, nodeB.recover());
        await().atMost(BOUND).until(() -> !observer.standDowns().isEmpty());
        await().atMost(BOUND).until(() -> !nodeB.executor().isRunning(workflowId));
        var call = observer.standDowns().getFirst();
        assertAll(
            () -> assertEquals(1, observer.standDowns().size()),
            () -> assertEquals(StandDownReason.NONDETERMINISTIC_HISTORY, call.reason()),
            () -> assertEquals("seq=1 expected=" + original + " stored=" + original + "-renamed type=ACTIVITY_COMPLETED", call.detail()),
            () -> assertTrue(observer.failed().isEmpty()),
            () -> assertTrue(observer.compensating().isEmpty()),
            () -> assertEquals(beforeStatus, store.getInstance(workflowId).orElseThrow().status()),
            () -> assertEquals(logBefore, fingerprint(instanceId), "a stand-down writes nothing"),
            () -> assertEquals(1, recorder.count("stepOne"), "the activity was NOT re-executed"),
            () -> assertFalse(lockRowHeld(workflowId)));
    }
    // Rollback: history matches the code again
    setStepName(instanceId, 1, original);
    // Node C: recover, signal, COMPLETED (copy UnknownEventStandDownIT.java:171-193)
}

@Test void sleepOnOccupiedSlot_leaksNoTimerRowAcrossRecoveryPasses() throws Exception {   // Review Focus 5
    // Node A: SleepingWorkflow(Duration.ofHours(1)) → handle.awaitStatus(WAITING_TIMER, BOUND); close A.
    assertEquals(1, timerRowCount(instanceId), "the one PENDING row the live sleep wrote");
    var stepOne = store.getEventBySequence(instanceId, 1).orElseThrow().stepName();
    overwriteAsActivity(instanceId, 2, stepOne);      // history now says "an activity ran at seq 2"; code says sleep
    try (var nodeB = ...) {
        for (int pass = 1; pass <= 3; pass++) {
            var expected = pass;
            assertEquals(1, nodeB.recover());
            await().atMost(BOUND).until(() -> observer.standDowns().size() == expected);
            await().atMost(BOUND).until(() -> !nodeB.executor().isRunning(workflowId));
            assertEquals(1, timerRowCount(instanceId), "pass " + pass + ": no timer row may be inserted before the stand-down");
        }
        assertTrue(observer.standDowns().stream().allMatch(s -> s.reason() == StandDownReason.NONDETERMINISTIC_HISTORY),
                "the pre-fix reason was STALE_RUN — after the leaked insert and the duplicate append");
        assertEquals(WorkflowStatus.WAITING_TIMER, store.getInstance(workflowId).orElseThrow().status());
    }
}
```

- [ ] **Step 6: Run the integration pins**

Run: `./gradlew :maestro-integration-tests:test --tests '*ReplayDriftStandDownIT*' --console=plain`
Expected: PASS. Then run the whole engine folder to check neighbours: `./gradlew :maestro-integration-tests:test --tests 'io.b2mash.maestro.integration.engine.*' --console=plain` → PASS.

- [ ] **Step 7: Documentation that now describes the behaviour**

- `DeterminismChecker.java:14-18`: replace "Nothing detects that today; divergence is silent." with: the engine detects divergence *at replay time* — a step name, event type, or `parallel()` branch count that disagrees with the recorded event stands the run down with `StandDownReason.NONDETERMINISTIC_HISTORY` and leaves the instance recoverable (see `docs/operations.md` §10.7); what it still cannot detect is a nondeterministic *value* that happens to produce the same step sequence, which is what this checker exists to catch before deployment.
- `docs/operations.md`: §10.3 table (`:457-465`) gains a row `nondeterministic_history | The current code performs a different operation at this sequence than the one recorded (step renamed/reordered, parallel() arity changed). Only a code change can cause it.` New `### 10.7 Replay drift: nondeterministic_history` after §10.6 covering: what it means (the workflow was edited without a `workflow.version()` gate, §10.2); why it stands down rather than fails (rollback is the fix; a `FAILED` here would run compensations for work that never failed — the same argument as §10.3); the WARN/detail format `seq=N expected=<step> stored=<step> type=<TYPE>`; the two remedies — redeploy the previous build so the recorded path is runnable again, or gate the change behind `workflow.version()` and redeploy; the alert `rate(maestro.standdown{reason="nondeterministic_history"}) > 0` → "an ungated workflow edit shipped; roll back"; and the note that the churn is one WARN + one counter increment per recovery pass, by design (§10.3).
- `docs/observability.md:94` and `:104-105`: add the `nondeterministic_history` tag value and its one-line meaning.
- `docs/test-plan.md:91`: the "Determinism guardrails" row's gap text becomes "Replay drift (step name/type/fork arity) stands down as `nondeterministic_history`; value-level nondeterminism with an unchanged step sequence is still only caught by `DeterminismChecker`"; `:398` likewise. (Plan 06 owns the rest of that document.)
- `docs/release-notes.md` under `## Unreleased`: `### Changed — Replay drift is detected and stands down as nondeterministic_history` — before (wrong payload returned silently; `sleep()` on an occupied slot leaked one PENDING timer row per recovery pass and stood down as `stale_run`), after (checked at every replay read before any side effect; the new reason/tag; `version()` peek unchanged), spec C10/F3.

- [ ] **Step 8: Commit**

```bash
git add maestro-core/src/main/java/io/b2mash/maestro/core/engine/DefaultWorkflowOperations.java \
        maestro-core/src/main/java/io/b2mash/maestro/core/engine/SignalManager.java \
        maestro-core/src/test/java/io/b2mash/maestro/core/engine/ReplayDriftStandDownTest.java \
        maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/ReplayDriftStandDownIT.java \
        maestro-test/src/main/java/io/b2mash/maestro/test/DeterminismChecker.java \
        docs/operations.md docs/observability.md docs/test-plan.md docs/release-notes.md
git commit -m "fix(engine): drift detection at sleep/awaitSignal/parallel — no leaked timer rows; operator docs for nondeterministic_history (C10, F3)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: Unreadable-payload stand-downs name their cause; operator remedies (C9)

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/UnknownHistoryGuard.java:97-107` (`requireReadablePayload`), `:123-132` (`payloadStandDown`)
- Modify: `docs/operations.md:467-503` (§10.4), `docs/release-notes.md:3`
- Test: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/UnknownHistoryStandDownTest.java:185-213` (update the `"seq=2"` pin), `maestro-core/src/test/java/io/b2mash/maestro/core/engine/UnknownHistoryGuardTest.java` (create)
- Test: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/UnreadablePayloadStandDownIT.java` (create)

**Interfaces:**
- Consumes: the 5-arg `UnknownWorkflowHistoryException` constructor and `detail()` (Task 2); `SerializationException` (`maestro-core/.../exception/SerializationException.java`).
- Produces: detail format for `Kind.UNKNOWN_EVENT_PAYLOAD`: `"seq=%d cause=%s: %s".formatted(seq, root.getClass().getName(), root.getMessage())` where `root` is the `SerializationException`'s cause when present, else the `SerializationException` itself; for `payloadStandDown(workflowId, seq, phrase)` (shape mismatch, no throwable): `"seq=%d cause=%s".formatted(seq, phrase)`. The WARN in `WorkflowExecutor.handleUnknownHistoryStandDown` already prints `e.getMessage()`; `initCause(e)` stays so the stack trace carries the Jackson detail.

- [ ] **Step 1: Write the failing tests**

`UnknownHistoryGuardTest`:

```java
@Test void requireReadablePayload_detailNamesTheRootCause() {
    var serializer = new PayloadSerializer(JsonMapper.builder().build());
    var payload = JsonMapper.builder().build().readTree("{\"reshaped\":true}");
    SerializationException expected = assertThrows(SerializationException.class, () -> serializer.deserialize(payload, Instant.class));
    var root = expected.getCause() != null ? expected.getCause() : expected;

    var ex = assertThrows(UnknownWorkflowHistoryException.class, () ->
        UnknownHistoryGuard.requireReadablePayload("wf", 2, "side effect", () -> serializer.deserialize(payload, Instant.class)));
    assertAll(
        () -> assertEquals(UnknownWorkflowHistoryException.Kind.UNKNOWN_EVENT_PAYLOAD, ex.kind()),
        () -> assertEquals("seq=2 cause=" + root.getClass().getName() + ": " + root.getMessage(), ex.detail()),
        () -> assertInstanceOf(SerializationException.class, ex.getCause()));
}
@Test void payloadStandDown_detailCarriesThePhrase() {
    var ex = UnknownHistoryGuard.payloadStandDown("wf", 5, "a VERSION_MARKER with no payload, so it can be attributed to no change");
    assertEquals("seq=5 cause=a VERSION_MARKER with no payload, so it can be attributed to no change", ex.detail());
}
```

Update `UnknownHistoryStandDownTest.unreadablePayload_standsDownWithThePayloadReason` (`:206`): `assertEquals("seq=2", call.detail())` → `assertTrue(call.detail().startsWith("seq=2 cause="), call.detail())`. Grep `maestro-core/src/test` for other `"seq=` pins on the **payload** kind (`ForeignPayloadStandDownTest`) and apply the same change; type-kind pins (`"seq=2"` for `UNKNOWN_EVENT_TYPE`) stay exact.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :maestro-core:test --tests '*UnknownHistoryGuardTest*' --tests '*UnknownHistoryStandDownTest*' --console=plain`
Expected: the two new tests FAIL on `detail()` equalling `"seq=2"` / `"seq=5"`; the updated payload pin FAILS the `startsWith`.

- [ ] **Step 3: Implement**

`UnknownHistoryGuard.requireReadablePayload` (`:101-105`) and `payloadStandDown` (`:126-131`) construct the exception with the 5-arg constructor and the formats in Interfaces. No other call site changes.

- [ ] **Step 4: Run the core suite**

Run: `./gradlew :maestro-core:test --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Write and run the Postgres pin**

`UnreadablePayloadStandDownIT` (same skeleton as `ReplayDriftStandDownIT.renamedStep…`): park `SignalWorkflow`, then `UPDATE maestro_workflow_event SET payload = '{"reshaped":true}'::jsonb WHERE workflow_instance_id = ? AND sequence_number = 1` (seq 1 is a `String` result); node B recovers:

```java
assertAll(
    () -> assertEquals(1, observer.standDowns().size()),
    () -> assertEquals(StandDownReason.UNKNOWN_EVENT_PAYLOAD, call.reason()),
    () -> assertTrue(call.detail().startsWith("seq=1 cause="), call.detail()),
    () -> assertTrue(call.detail().contains("Exception"), "the root cause's class name must be present: " + call.detail()),
    () -> assertTrue(observer.failed().isEmpty()),
    () -> assertEquals(WorkflowStatus.WAITING_SIGNAL, store.getInstance(workflowId).orElseThrow().status()),
    () -> assertEquals(1, recorder.count("stepOne")),
    () -> assertFalse(lockRowHeld(workflowId)));
// restore: UPDATE ... SET payload = '"<original>"'::jsonb; node C recovers, signal, COMPLETED
```

Run: `./gradlew :maestro-integration-tests:test --tests '*UnreadablePayloadStandDownIT*' --console=plain` → PASS.

- [ ] **Step 6: Operator text and release note**

`docs/operations.md` §10.4 (`:467-503`):
- After the existing "Find the changed activity return type…" paragraph add the two other remedies: **roll back** to the build that wrote the payload (the stand-down loop ends immediately; nothing was written), or, when the instance is not worth finishing, **terminate** it from the admin dashboard — stand-down never terminates on its own because it cannot tell a transient skew from a permanent refactor.
- Add that the WARN and the `standDown` detail now carry the deserializer's root cause (`seq=N cause=<class>: <message>` — for a removed enum constant this is Jackson's "not one of the values accepted for Enum class" message), which is what distinguishes "a newer node wrote this" from "my own refactor broke this" on a fleet you know to be homogeneous.
- Add one row to the "Suggested alerts" table: `unknown_event_payload` **and** `nondeterministic_history` both rising on a homogeneous fleet → "one deploy shipped both an ungated code change and an incompatible payload change; roll it back".

`docs/release-notes.md` under `## Unreleased`: `### Changed — unknown_event_payload stand-downs name the deserialization cause` (two bullets: the new detail/WARN content; the operator remedies now documented). Spec C9.

- [ ] **Step 7: Commit**

```bash
git add maestro-core/src/main/java/io/b2mash/maestro/core/engine/UnknownHistoryGuard.java \
        maestro-core/src/test/java/io/b2mash/maestro/core/engine/UnknownHistoryGuardTest.java \
        maestro-core/src/test/java/io/b2mash/maestro/core/engine/UnknownHistoryStandDownTest.java \
        maestro-core/src/test/java/io/b2mash/maestro/core/engine/ForeignPayloadStandDownTest.java \
        maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/UnreadablePayloadStandDownIT.java \
        docs/operations.md docs/release-notes.md
git commit -m "fix(engine): unreadable-payload stand-downs carry the deserialization cause; document rollback/terminate remedies (C9)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Deterministic failure reconstruction on replay (C15)

**Files:**
- Create: `maestro-core/src/main/java/io/b2mash/maestro/core/exception/ReconstructedActivityFailure.java`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/ActivityInvocationHandler.java:292-351` (`reconstructFailure`, `tryInstantiateException`)
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/exception/ActivityExecutionException.java:3-7` (Javadoc)
- Modify: `docs/release-notes.md:3`
- Test: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/ActivityFailureReconstructionTest.java` (create)
- Test: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/ActivityInvocationHandlerTest.java:115-135` (strengthen `replayFailedActivity`)
- Test: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/ActivityFailureReplayIT.java` (create); `.../workflows/CountingActivities.java` (add `FailingActivities`); `.../workflows/TestWorkflows.java` (add `FailureReplayWorkflow`)

**Interfaces:**
- Consumes: `ActivityInvocationHandlerTest`'s fixture (`:47-90`: `InMemoryWorkflowStore`, `createProxy`, `withContext`); `ErrorDetail {exceptionType, message}` payload written by `persistFailure` (`:505-525`).
- Produces:
  - `public final class ReconstructedActivityFailure extends RuntimeException` in `io.b2mash.maestro.core.exception`; constructor `(String originalClassName, @Nullable String message)`; accessor `public String originalClassName()`. Deliberately **not** a `MaestroException`: it stands in for the *user's* exception, and `catch (MaestroException)` must keep meaning "engine-level problem". Javadoc explains that and lists the three cases that produce it (no public `(String)` constructor; class not on this classpath; stored type names an engine control-flow signal).
  - `private static Throwable ActivityInvocationHandler.reconstructCause(@Nullable String exceptionType, String message)` replacing `tryInstantiateException`: returns `clazz.getConstructor(String.class).newInstance(message)` when `Class.forName(exceptionType)` resolves to a `Throwable` that is **not** assignable to `MaestroControlFlowError` and exposes a **public** `(String)` constructor; otherwise `new ReconstructedActivityFailure(exceptionType != null ? exceptionType : "unknown", message)`. Never a bare `RuntimeException`; the no-arg-constructor fallback (`:337-342`) is removed (it dropped the message).
  - `ActivityExecutionException` Javadoc gains a "Matching the cause" section: on the live path `getCause()` is the exception the activity threw; on replay it is the same type **only if** that type has a public `(String)` constructor, else a `ReconstructedActivityFailure` whose `originalClassName()` is the thrown type's FQCN — so workflow code must catch `ActivityExecutionException` and branch on `getCause()`'s class *or* on `originalClassName()`, never rely on `catch (MyDomainException)` alone. (Plan 03 decides whether the wrapper itself changes.)

- [ ] **Step 1: Write the failing unit tests**

`ActivityFailureReconstructionTest` (fixture as `ActivityInvocationHandlerTest`, but the proxy is built with `RetryPolicy.noRetry()` so the live test below does not back off; helper `replayFailure(String exceptionType, String message)` plants an `ACTIVITY_FAILED` at seq 1 with that `ErrorDetail` payload and invokes the proxy under `withContext`). Fixtures: `DomainException extends RuntimeException { public DomainException(String m) }`, `NoStringCtorException extends RuntimeException { public NoStringCtorException(String m, int code) }`, `PrivateCtorException extends RuntimeException { private PrivateCtorException(String m) }`.

```java
@Test void publicStringCtor_replaysTheOriginalType() {
    var ex = replayFailure(DomainException.class.getName(), "boom");
    assertAll(() -> assertInstanceOf(DomainException.class, ex.getCause()),
              () -> assertEquals("boom", ex.getCause().getMessage()));
}
@Test void noStringCtor_replaysAsReconstructedActivityFailure() {
    var ex = replayFailure(NoStringCtorException.class.getName(), "boom");
    var cause = assertInstanceOf(ReconstructedActivityFailure.class, ex.getCause());
    assertAll(() -> assertEquals(NoStringCtorException.class.getName(), cause.originalClassName()),
              () -> assertEquals("boom", cause.getMessage()));
}
@Test void privateStringCtor_replaysAsReconstructed() {
    assertInstanceOf(ReconstructedActivityFailure.class, replayFailure(PrivateCtorException.class.getName(), "boom").getCause());
}
@Test void unknownClass_replaysAsReconstructed() {
    var cause = assertInstanceOf(ReconstructedActivityFailure.class, replayFailure("com.example.Gone", "boom").getCause());
    assertEquals("com.example.Gone", cause.originalClassName());
}
@Test void missingExceptionType_replaysAsReconstructedUnknown() {   // payload has only "message"
    var cause = assertInstanceOf(ReconstructedActivityFailure.class, replayFailureWithPayload(Map.of("message", "boom")).getCause());
    assertEquals("unknown", cause.originalClassName());
}
@Test void controlFlowErrorName_isNeverInstantiated() {   // Review Focus 4
    var cause = replayFailure(ExecutorShutdownException.class.getName(), "deploying").getCause();
    assertInstanceOf(ReconstructedActivityFailure.class, cause);
    assertFalse(cause instanceof MaestroControlFlowError);
}
@Test void liveAndReplay_agreeOnTheWrapperAndOnOriginalClassName() {
    // live: impl throws new NoStringCtorException("boom", 7); proxy built with RetryPolicy.noRetry()
    var live = assertThrows(ActivityExecutionException.class, () -> withContext(() -> proxy.explode()));
    // replay: a FRESH withContext restarts the sequence at 0, so this call reads the
    // ACTIVITY_FAILED just persisted at seq 1 instead of executing live at seq 2
    var replayed = assertThrows(ActivityExecutionException.class, () -> withContext(() -> proxy.explode()));
    assertAll(
        () -> assertEquals(live.activityName(), replayed.activityName()),
        () -> assertInstanceOf(NoStringCtorException.class, live.getCause()),
        () -> assertEquals(NoStringCtorException.class.getName(),
                assertInstanceOf(ReconstructedActivityFailure.class, replayed.getCause()).originalClassName()),
        () -> assertEquals("boom", replayed.getCause().getMessage()));
}
```

Strengthen `ActivityInvocationHandlerTest.replayFailedActivity` (`:131-134`) with `assertInstanceOf(RuntimeException.class, ex.getCause()); assertEquals(RuntimeException.class, ex.getCause().getClass());`.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :maestro-core:test --tests '*ActivityFailureReconstructionTest*' --console=plain`
Expected: compile FAIL on `ReconstructedActivityFailure`; after stubbing the class only, `noStringCtor…`, `privateStringCtor…`, `unknownClass…`, `missingExceptionType…`, `controlFlowErrorName…` FAIL with cause class `java.lang.RuntimeException` (today's fallback); `controlFlowErrorName…` may instead FAIL by instantiating the `Error` via its public `(String)` constructor (`ExecutorShutdownException.java:61`) — either way it fails.

- [ ] **Step 3: Implement**

Create `ReconstructedActivityFailure`; replace `tryInstantiateException` (`:321-351`) with `reconstructCause` per Interfaces; `reconstructFailure` (`:299-319`) calls it. Keep the `message` defaulting (`:302-311`) as is. Update the `ActivityExecutionException` Javadoc.

- [ ] **Step 4: Run the core suite**

Run: `./gradlew :maestro-core:test --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Write and run the Postgres pin**

Fixtures:

```java
// CountingActivities
@Activity(name = "failing")
public interface FailingActivities { String explode(String input); }
public static final class DomainFailure extends RuntimeException { public DomainFailure(String m) { super(m); } }
public static final class NoCtorFailure extends RuntimeException { public NoCtorFailure(String m, int code) { super(m); } }
public static final class ThrowingFailingActivities implements FailingActivities { /* throws the Supplier<RuntimeException> it is built with */ }

// TestWorkflows
@DurableWorkflow(name = "FailureReplayWorkflow")
public static class FailureReplayWorkflow {
    @ActivityStub(retryPolicy = @RetryPolicy(maxAttempts = 1))   // io.b2mash.maestro.core.annotation.RetryPolicy
    FailingActivities failing;
    private final List<String> observations; private final CountDownLatch parked;
    @WorkflowMethod
    public String run(String input) {
        String seen;
        try { failing.explode(input); seen = "no-failure"; }
        catch (ActivityExecutionException e) {
            seen = e.getCause().getClass().getName() + "|" + e.getCause().getMessage()
                 + (e.getCause() instanceof ReconstructedActivityFailure r ? "|" + r.originalClassName() : "");
        }
        observations.add(seen);
        parked.countDown();
        WorkflowContext.current().awaitSignal("go", String.class, Duration.ofSeconds(30));
        return seen;
    }
}
```

`ActivityFailureReplayIT`: node A runs to the park (observations A = live view), closes; node B (fresh `observations` list) recovers → replay view; `deliverSignal("go")`; `awaitTerminal == COMPLETED`; `handle.result(String.class)` is the replay view.

```java
@Test void domainExceptionWithStringCtor_replaysIdentically() {
    // impl throws new DomainFailure("boom")
    assertEquals(DomainFailure.class.getName() + "|boom", liveObservations.getFirst());
    assertEquals(liveObservations.getFirst(), replayObservations.getFirst());
    assertEquals(liveObservations.getFirst(), handle.result(String.class));
}
@Test void exceptionWithoutStringCtor_replaysAsReconstructedWithOriginalName() {
    // impl throws new NoCtorFailure("boom", 7)
    assertEquals(NoCtorFailure.class.getName() + "|boom", liveObservations.getFirst());
    assertEquals(ReconstructedActivityFailure.class.getName() + "|boom|" + NoCtorFailure.class.getName(), replayObservations.getFirst());
    assertEquals(1, countOfType(instanceId, EventType.ACTIVITY_FAILED), "replay re-executed nothing");
}
```

Run: `./gradlew :maestro-integration-tests:test --tests '*ActivityFailureReplayIT*' --console=plain` → PASS.

- [ ] **Step 6: Release note, whole-build gate, commit**

`docs/release-notes.md` under `## Unreleased`: `### Changed — Replayed activity failures never degrade to a bare RuntimeException` (before: `(String)`/no-arg reflection, else `RuntimeException`, so `catch (MyDomainException)` differed live vs replay; after: original type only via a public `(String)` constructor, else `ReconstructedActivityFailure` with `originalClassName()`; engine control-flow types never reconstructed; the documented matching contract on `ActivityExecutionException`). Spec C15.

Run: `./gradlew build --console=plain` → `BUILD SUCCESSFUL`, and `./gradlew :maestro-core:dependencies --configuration compileClasspath | grep -c springframework` → `0`.

```bash
git add maestro-core/src/main/java/io/b2mash/maestro/core/exception/ReconstructedActivityFailure.java \
        maestro-core/src/main/java/io/b2mash/maestro/core/exception/ActivityExecutionException.java \
        maestro-core/src/main/java/io/b2mash/maestro/core/engine/ActivityInvocationHandler.java \
        maestro-core/src/test/java/io/b2mash/maestro/core/engine/ActivityFailureReconstructionTest.java \
        maestro-core/src/test/java/io/b2mash/maestro/core/engine/ActivityInvocationHandlerTest.java \
        maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/ActivityFailureReplayIT.java \
        maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/workflows/CountingActivities.java \
        maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/workflows/TestWorkflows.java \
        docs/release-notes.md
git commit -m "fix(engine): replayed activity failures reconstruct deterministically — ReconstructedActivityFailure, never bare RuntimeException (C15)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

## Self-review (run against the spec rows before hand-off)

- **Spec coverage:** C8 → Task 1. C10 → Tasks 2–3 (activity, side-effect, sleep, awaitSignal, parallel/branchCount; leaked-timer symptom pinned on both fakes and Postgres). C9 → Task 4 (reason split already existed under `UNKNOWN_EVENT_PAYLOAD`; the missing cause detail and operator remedies are added). C15 → Task 5. F3 → Task 3's docs + `DeterminismChecker` Javadoc + Task 4's §10.4 text. No gaps.
- **Step scan:** every code step names a signature and file; bodies appear only where the signature does not determine them (the `Error` arm's guard line, the detail formats, the fixture workflows). No "handle edge cases" lines.
- **Type consistency:** `ReplayGuard.requireMatch(WorkflowEvent, String, String, EventType...)` / `requireBranchCount(WorkflowEvent, String, int)` and `UnknownWorkflowHistoryException.detail()` are spelled identically in Tasks 2, 3, 4; `StandDownReason.NONDETERMINISTIC_HISTORY` / tag `nondeterministic_history` in Tasks 2, 3; `ReconstructedActivityFailure.originalClassName()` in Task 5 unit + IT; `failAndCompensate(..., Throwable, ...)` only in Task 1.
- **Review Focus:** all five lines have a named test in the owning task (1→T3, 2→T3, 3→T1, 4→T5, 5→T3).
- **Proportion:** the plan is dominated by test assertions and signatures, not implementation bodies; the four production changes are each under ~40 lines.
