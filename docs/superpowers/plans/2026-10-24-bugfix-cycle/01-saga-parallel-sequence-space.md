# Saga / Parallel Sequence-Space Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `parallel()` and saga compensation share the ×1000 sequence partition *correctly*: a failed fork no longer lets compensation read sibling-branch events (C1), a crash mid-parallel-compensation no longer strands the instance in `COMPENSATING` forever (C2), the 999-step-per-branch promise is enforced with a hard error (C3), and every piece of block arithmetic goes through one overflow-guarded helper (C4).

**Architecture:** Four surgical changes in `maestro-core`, no SPI or schema change. `DefaultWorkflowOperations.parallel()` advances the parent counter in a `finally`; `SagaManager.executeParallel()` replay-guards its fork marker exactly as `compensate()` guards `COMPENSATION_STARTED`; `WorkflowContext` gains a bounded-branch factory whose `nextSequence()` throws `BranchSequenceExhaustedException` on the 1000th direct step; a new public `SequenceSpace` utility owns `BRANCH_MULTIPLIER`, `branchBase`, and the overflow-checked `nextParentSeq`, used by both `DefaultWorkflowOperations` and `SagaManager`, with `WorkflowExecutor.handleWorkflowFailure` learning that a `SequenceSpaceExhaustedException` from `compensate()` means "nothing could be compensated — still finish as FAILED, loudly".

**Tech Stack:** Java 25, JUnit 5, Awaitility, Testcontainers 2 (Postgres 16) via `maestro-integration-tests`' `PostgresIntegrationSupport` + `MaestroEngineHarness`.

**Spec:** [`00-spec-independent-assessment.md`](00-spec-independent-assessment.md) §3a rows C1–C4; [`README.md`](README.md) § Review Focus item 1. Line numbers below are as of `main` @ 4eb28e9 — a locator, not a contract; re-read the cited region before editing.

## Global Constraints

See [README.md § Global Constraints](README.md#global-constraints). Two that bite here: every new `MaestroException` subtype must be added to the sealed `permits` clause at `maestro-core/src/main/java/io/b2mash/maestro/core/exception/MaestroException.java:30-46` (it will not compile otherwise), and neither new exception may join `MaestroControlFlowError` — they *are* workflow failures and must be catchable, compensating ones.

## Decisions already made (do not re-open)

1. **C1:** `parallel()` advances the parent counter to `nextParentSeq` in a `finally`, so the counter is past the branch space whether branches succeed or fail. `SagaManager`'s anchoring (`anchor = ctx.nextSequence()` after the fork space) is unchanged.
2. **C2:** `executeParallel` replay-guards the `$maestro:parallel-compensation` marker with `UnknownHistoryGuard.requireKnown(store.getEventBySequence(...))` — present means replay, skip the append — mirroring `compensate()`'s `COMPENSATION_STARTED` guard at `SagaManager.java:172-195`.
3. **C3:** `WorkflowContext` gains a static factory `bounded(parent, branchIndex, baseSeq, limitExclusive, operations)`; its `nextSequence()` throws `BranchSequenceExhaustedException extends MaestroException` (fields `workflowId`, `branchIndex`, `baseSeq`, `limit`) when the branch's direct steps would leave `[baseSeq, limitExclusive)`. Ordinary `MaestroException`, not a control-flow `Error`: the branch fails, the workflow fails and compensates — that is the documented consequence.
4. **C4:** One shared, public `io.b2mash.maestro.core.engine.SequenceSpace` utility (`BRANCH_MULTIPLIER`, `branchBase`, `nextParentSeq`) throwing `SequenceSpaceExhaustedException extends MaestroException` on `int` overflow, used from both `DefaultWorkflowOperations` and `SagaManager`. Public, not package-private, because `SagaManager` lives in `io.b2mash.maestro.core.saga` (same reason `UnknownHistoryGuard` is public).
5. Every fix gets a unit pin in `maestro-core` **and** an `@Tag("integration")` pin against real Postgres in `maestro-integration-tests`.

**Two refinements made while reading the code (not reversals):**

- *C3 bound semantics.* A nested `parallel()` inside a branch legitimately rebases the branch counter outside its block (`setSequence(nextParentSeq)`, where `nextParentSeq ≥ 2,001,000` for any branch base ≥ 2000). The bound therefore fires on the **1000th direct step only**: `nextSequence()` throws iff the counter currently stands at `limitExclusive - 1`. A counter already rebased beyond the block by a nested join is never equal to `limitExclusive - 1`, so nesting keeps working, and the nested fork's own `SequenceSpace` guard bounds *its* space.
- *C4 in `SagaManager`.* The overflow check runs **once, in `compensate()`, before `transitionToCompensating`** and before any sequence is consumed, using the peeked anchor (`ctx.currentSequence() + 1`, plus one more for the parallel fork marker). On overflow nothing is written, no action runs, and `SequenceSpaceExhaustedException` propagates to `WorkflowExecutor.handleWorkflowFailure`, which gains a catch arm (beside `CompensationException` at `WorkflowExecutor.java:1716`) that logs at `ERROR` that **no compensation ran** and proceeds to `FAILED`. Without that arm the exception escapes `executeWorkflow`'s `catch (Exception e)` block and the instance becomes a `RUNNING` zombie — the exact C8 shape Plan 2 is closing elsewhere.

## Review Focus

Inputs the spec implies but no existing test exercises; each has its pin in the task named.

1. **Parallel inside compensation** (README item 1): a compensation registered with `addCompensation` that itself calls `parallel()` must land in the compensation entry's own block, run exactly once across a replay, and never touch a forward-path slot — Task 5.
2. **Replaying a failed fork on a recovering node**: after C1 the counter advance happens under replay too, so a second run must anchor `COMPENSATION_STARTED` at the same sequence the first run did and skip every already-durable entry — Task 1 (unit, second half).
3. **Fork-marker read meets unknown history**: the new C2 guard must stand down (`UnknownWorkflowHistoryException`), not fall through to a live append — Task 2.
4. **One branch exhausts its block while a sibling is still live**: `parallel()` joins all branches before rethrowing, so the sibling's events must still be persisted at their own slots — Task 4.
5. **The overflow pre-check must consume no sequence and append nothing**, so a recovering node sees identical history — Task 3.

---

### Task 1: `parallel()` advances the parent counter even when a branch fails (C1)

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/DefaultWorkflowOperations.java:551-645`
- Create: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/ParallelSagaTestSupport.java` (shared harness for Tasks 1, 3, 4, 5)
- Create: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/ParallelFailureCompensationSequenceTest.java`
- Create: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/EnginePostgresParallelSagaIT.java`
- Modify: `docs/release-notes.md:3-5` (insert a `### Fixed —` section at the top of `## Unreleased`)

**Interfaces:**
- Consumes: `DefaultWorkflowOperations(store, null, null, serializer, parkingLot, compensationStack, signalManager)` (`:110-118`), `SagaManager(store, null, serializer, serviceName)` (`:103-109`), `ActivityProxyFactory.createProxy(iface, impl, store, null, null, RetryPolicy.noRetry(), Duration, serializer, new RetryExecutor())` (`ActivityProxyFactory.java:65`), test store `VersionedInMemoryStore` (package-private, `maestro-core/src/test/java/io/b2mash/maestro/core/engine/VersionedInMemoryStore.java` — enforces `(instance, seq)` uniqueness).
- Produces: the invariant *"after `parallel()` returns or throws, `ctx.currentSequence() == parentSeq * 1000 + (branchCount + 1) * 1000`"*. Task 5 and the ITs below hard-code sequence numbers that depend on it. `EnginePostgresParallelSagaIT` is the IT class Tasks 2, 3 and 5 extend. The package-private test base class:
  ```java
  abstract class ParallelSagaTestSupport {            // @BeforeEach builds: store (VersionedInMemoryStore), serializer,
      VersionedInMemoryStore store; PayloadSerializer serializer; WorkflowInstance instance;   // instance (eventSequence 0, RUNNING),
      CompensationStack stack; DefaultWorkflowOperations ops; SagaManager sagaManager; WorkflowContext ctx;  // ops wired with stack, ctx = newContext(0, true, ops)
      WorkflowContext newContext(int initialSequence, boolean replaying, WorkflowOperations ops);   // 9-arg ctor on `instance`'s identity
      void inScope(Runnable body);                                   // ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(body)
      List<String> eventLogSortedBySequence();                       // "seq:TYPE:stepName" sorted by sequenceNumber
      long count(EventType type); long count(EventType type, String stepName);
  }
  ```
  Wiring as in `WorkflowVersionParallelBranchTest.java:38-121`, plus `sagaManager = new SagaManager(store, null, serializer, "test-service")`.

- [ ] **Step 1: Write the failing unit test**

Create `ParallelSagaTestSupport` as specified in Interfaces. The test class extends it and adds fields `released` (`AtomicInteger`), `impl`, and `proxy` (built in its own `@BeforeEach`). Activity fixture, test-local:

```java
@Activity(name = "pay")
public interface PayActivities {
    @Compensate("releaseReservation") String reserve(String item);
    void releaseReservation(String reservation);
    String charge(String item);
}
// impl: reserve → "reserved:" + item; releaseReservation → released.incrementAndGet();
//       charge → throw new IllegalStateException("declined")
```

```java
@Test
@DisplayName("a branch failure leaves the parent counter past the branch space, so compensation never reads a sibling branch's slot")
void failedBranch_compensationRunsInItsOwnBlock() {
    // proxy = new ActivityProxyFactory().createProxy(PayActivities.class, impl, store, null, null,
    //         RetryPolicy.noRetry(), Duration.ofSeconds(5), serializer, new RetryExecutor());   — in @BeforeEach
    assertThrows(ActivityExecutionException.class, () -> inScope(() ->
            ops.parallel(List.of(() -> proxy.reserve("widget"), () -> proxy.charge("widget")))));

    // The fix: counter is past both branch blocks, exactly as on the success path.
    assertEquals(4000, ctx.currentSequence(), "p=1, 2 branches → 1*1000 + 3*1000");

    inScope(() -> sagaManager.compensate(ctx, instance, stack, false));   // must not throw

    assertEquals(1, released.get(), "releaseReservation must actually execute, not replay saga.charge's ACTIVITY_FAILED");
    assertEquals(List.of(
            "1:SIDE_EFFECT:$maestro:parallel",
            "2001:ACTIVITY_COMPLETED:pay.reserve",
            "3001:ACTIVITY_FAILED:pay.charge",
            "4001:COMPENSATION_STARTED:$maestro:compensation",
            "4002000:COMPENSATION_STEP_COMPLETED:pay.releaseReservation",
            "4002001:ACTIVITY_COMPLETED:pay.releaseReservation",
            "4003001:COMPENSATION_COMPLETED:$maestro:compensation"),
            eventLogSortedBySequence());
    assertEquals(0, count(EventType.COMPENSATION_STEP_FAILED));
}

@Test
@DisplayName("a recovering node replays the failed fork, anchors compensation at the same sequence, and re-invokes nothing")
void failedBranch_replayAnchorsIdentically() {
    failedBranch_compensationRunsInItsOwnBlock();           // first run, live
    var eventsAfterFirstRun = eventLogSortedBySequence();

    ctx = newContext(/* initialSequence */ 0, /* replaying */ true, ops);
    assertThrows(ActivityExecutionException.class, () -> inScope(() ->
            ops.parallel(List.of(() -> proxy.reserve("widget"), () -> proxy.charge("widget")))));
    assertEquals(4000, ctx.currentSequence());
    inScope(() -> sagaManager.compensate(ctx, instance, stack, false));

    assertEquals(1, released.get(), "the durable compensation outcome must be replayed, not re-run");
    assertEquals(eventsAfterFirstRun, eventLogSortedBySequence(), "replay appends nothing");
}
```

Note: `@Compensate` registration goes through `ctx.addCompensation` (`ActivityInvocationHandler.java:657`), which in a branch delegates to the shared `DefaultWorkflowOperations` and therefore the shared `stack` — no extra wiring; one proxy serves both runs because `released` is shared.

- [ ] **Step 2: Run the unit test, verify it fails for the right reason**

Run: `./gradlew :maestro-core:test --tests 'io.b2mash.maestro.core.engine.ParallelFailureCompensationSequenceTest' --console=plain`
Expected: FAIL on `assertEquals(4000, ctx.currentSequence())` with actual `1`. (If you temporarily remove that assertion to see the downstream symptom: `released.get()` is `0`, and the log contains `3000:COMPENSATION_STEP_FAILED` — the compensation "replayed" `pay.charge`'s failure.)

- [ ] **Step 3: Implement the `finally` in `parallel()`**

In `DefaultWorkflowOperations.parallel()` (`:551-645`): compute `nextParentSeq` immediately after `var parentSeq = ctx.nextSequence();` (`:551`) with the existing expression from `:644`, then wrap everything from the replay check (`:558`) through the error-rethrow loop (`:633-639`) in `try { ... } finally { ctx.setSequence(nextParentSeq); }`. Delete the now-duplicate advance at `:644-645`. Result collection stays after the `try`. Update the method's Javadoc/comment: *"The advance runs in `finally` so a branch failure leaves the counter past the branch space — `SagaManager.compensate()` anchors from `ctx.nextSequence()`, and anchoring at `p+1` would make entry *i*'s block coincide with branch *i+1*'s base (C1)."*

- [ ] **Step 4: Run the unit test and the full core suite**

Run: `./gradlew :maestro-core:test --tests 'io.b2mash.maestro.core.engine.ParallelFailureCompensationSequenceTest' --console=plain`
Expected: `BUILD SUCCESSFUL`, 2 tests passed.
Run: `./gradlew :maestro-core:test --console=plain`
Expected: `BUILD SUCCESSFUL` (412 tests on `main`; count grows by 2).

- [ ] **Step 5: Write the failing Postgres IT**

New class `EnginePostgresParallelSagaIT extends PostgresIntegrationSupport`, `@Tag("integration")`. Follow `EnginePostgresSagaIT.java:43-62` for fields/`@AfterEach` and `EnginePostgresParallelIT.java:429-455` for a `node(...)` helper and `describe(...)`. Test-local workflow:

```java
@DurableWorkflow(name = "ParallelSagaWorkflow")
public static class ParallelSagaWorkflow {
    @ActivityStub SagaActivities activities;
    @Saga @WorkflowMethod
    public String run(String item) {
        WorkflowContext.current().parallel(List.<Callable<String>>of(
                () -> activities.reserve(item), () -> activities.charge(item)));
        return activities.ship(item);
    }
}
```

```java
@Test
@DisplayName("reserve ∥ charge, charge fails: releaseReservation runs once and lands past the branch space")
void failedBranch_compensatesInItsOwnBlock() {
    var recorder = new CountingActivities.Recorder();
    harness = node("parallel-saga", new CountingActivities.RecordingSagaActivities(recorder, "charge"));
    var handle = harness.start(MaestroEngineHarness.uniqueWorkflowId("parallel-saga"),
            ParallelSagaWorkflow.class, "widget");

    assertEquals(WorkflowStatus.FAILED, handle.awaitTerminal(Duration.ofSeconds(30)));
    assertEquals(1, recorder.count("reserve"));
    assertEquals(3, recorder.count("charge"), "the fixture's @ActivityStub default is three attempts");
    assertEquals(0, recorder.count("ship"), "the fork failed, so ship never ran");
    assertEquals(1, recorder.count("releaseReservation"), "the compensation must execute, not replay saga.charge's failure");
    assertEquals(0, recorder.count("refund"), "charge never succeeded, so nothing to refund");
    assertEquals(List.of(
            "1:SIDE_EFFECT:$maestro:parallel",
            "2001:ACTIVITY_COMPLETED:saga.reserve",
            "3001:ACTIVITY_FAILED:saga.charge",
            "4001:COMPENSATION_STARTED:$maestro:compensation",
            "4002000:COMPENSATION_STEP_COMPLETED:saga.releaseReservation",
            "4002001:ACTIVITY_COMPLETED:saga.releaseReservation",
            "4003001:COMPENSATION_COMPLETED:$maestro:compensation",
            "4003002:WORKFLOW_FAILED:null"),
            describe(handle));
}
```

`node(serviceName, SagaActivities impl)` builds `MaestroEngineHarness.builder(store, objectMapper).serviceName(..).lock(newLock()).instanceLockTtl(Duration.ofSeconds(2)).build()`, registers `SagaActivities.class` and `new ParallelSagaWorkflow()`.

- [ ] **Step 6: Run the IT against `main`'s behaviour to confirm it pins the bug**

Temporarily shelve only the production change — `git stash push maestro-core/src/main/java/io/b2mash/maestro/core/engine/DefaultWorkflowOperations.java` — then:
Run: `./gradlew :maestro-integration-tests:test --tests 'io.b2mash.maestro.integration.engine.EnginePostgresParallelSagaIT' --console=plain`
Expected: FAIL — `releaseReservation` count `0`, log contains `3000:COMPENSATION_STEP_FAILED:saga.releaseReservation`. Then `git stash pop`.

- [ ] **Step 7: Run the IT with the fix**

Run: `./gradlew :maestro-integration-tests:test --tests 'io.b2mash.maestro.integration.engine.EnginePostgresParallelSagaIT' --console=plain`
Expected: `BUILD SUCCESSFUL`, 1 test passed.

- [ ] **Step 8: Release notes**

Insert under `## Unreleased` in `docs/release-notes.md` (above the current first `### Added` at `:5`):

`### Fixed — Compensation after a failed `parallel()` no longer reads sibling-branch events` — three sentences: the counter stayed at the fork's own sequence when a branch threw, so `SagaManager` anchored entry *i*'s block at branch *i+1*'s base and a `@Compensate` action replayed the sibling's `ACTIVITY_COMPLETED`/`ACTIVITY_FAILED` instead of running; `parallel()` now advances the counter in a `finally`; **behaviour change:** compensation events of a saga that fails inside `parallel()` now carry sequence numbers ≥ `(p*1000+(n+1)*1000+1)*1000` — larger than before, still within `int` for one forward fork (see Task 3's note on depth).

- [ ] **Step 9: Commit**

```bash
git add maestro-core/src/main/java/io/b2mash/maestro/core/engine/DefaultWorkflowOperations.java \
        maestro-core/src/test/java/io/b2mash/maestro/core/engine/ParallelSagaTestSupport.java \
        maestro-core/src/test/java/io/b2mash/maestro/core/engine/ParallelFailureCompensationSequenceTest.java \
        maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/EnginePostgresParallelSagaIT.java \
        docs/release-notes.md
git commit -m "fix(core): advance the parent sequence past the branch space even when a parallel() branch fails (C1)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: Replay-guard the parallel-compensation fork marker (C2)

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/saga/SagaManager.java:371-374`
- Modify: `maestro-core/src/test/java/io/b2mash/maestro/core/saga/SagaManagerTest.java:332-370, 444-460, 533`
- Modify: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/EnginePostgresParallelSagaIT.java` (add one test + fixtures)
- Modify: `docs/release-notes.md` (`## Unreleased`)

**Interfaces:**
- Consumes: `UnknownHistoryGuard.requireKnown(Optional<WorkflowEvent>, String workflowId)` (`UnknownHistoryGuard.java:73`); `MaestroEngineHarness.Builder.observer(EngineObserver)` (`:403`) with `RecordingObserver` (`maestro-integration-tests/.../support/RecordingObserver.java`, `standDowns()` at `:60`); `PostgresIntegrationSupport.awaitTerminal(String workflowId, Duration)` (`:137`).
- Produces: the `$maestro:parallel-compensation` `SIDE_EFFECT` is appended **at most once** per instance; a recovery run of a parallel compensation reaches `FAILED` with no `standDown` callback.

- [ ] **Step 1: Harden the test fake and expose the existing pin as false-green**

In `SagaManagerTest.InMemoryStore.appendEvent` (`:533`), reject a second event at the same `(workflowInstanceId, sequenceNumber)` with `throw new DuplicateEventException(event.workflowInstanceId(), event.sequenceNumber())` — the real store's unique index (`V2__create_indexes.sql`). Add to `parallelReplayDoesNotReinvokeAnAlreadyCompletedCompensation` (`:332-370`), after the second `compensate`:

```java
assertEquals(1, store.getEvents(instance.id()).stream()
        .filter(e -> e.eventType() == EventType.SIDE_EFFECT
                && "$maestro:parallel-compensation".equals(e.stepName())).count(),
        "the fork marker must be appended once, never re-appended on replay");
```

Add the Review-Focus-3 pin beside `parallelBranchReadStandsDown` (`:444-460`):

```java
@Test
@DisplayName("Stand-down: the parallel-compensation fork marker's replay read is guarded")
void parallelForkMarkerReadStandsDown() {
    var invocations = new AtomicInteger();
    var stack = new CompensationStack();
    stack.push("branch-A", invocations::incrementAndGet);
    plantFutureEvent(7);   // ctx seeded at 5: COMPENSATION_STARTED consumes 6, the fork marker 7

    assertThrows(UnknownWorkflowHistoryException.class,
            () -> sagaManager.compensate(ctx, instance, stack, true));

    assertEquals(0, invocations.get());
    assertEquals(0, countEvents(EventType.SIDE_EFFECT), "no live append may follow an unreadable read");
    assertEquals(0, countEvents(EventType.COMPENSATION_STEP_FAILED));
    assertEquals(0, countEvents(EventType.COMPENSATION_COMPLETED));
}
```

- [ ] **Step 2: Run the class, verify exactly the expected failures**

Run: `./gradlew :maestro-core:test --tests 'io.b2mash.maestro.core.saga.SagaManagerTest' --console=plain`
Expected: exactly two failures — `parallelReplayDoesNotReinvokeAnAlreadyCompletedCompensation` with `DuplicateEventException` at sequence `7` thrown from the second `compensate`, and `parallelForkMarkerReadStandsDown` with `DuplicateEventException` where `UnknownWorkflowHistoryException` was expected. Any *other* failure is a second instance of the same defect: report it in the task summary, do not weaken the fake.

- [ ] **Step 3: Guard the fork marker**

Replace `SagaManager.java:372-374` with: `var parentSeq = ctx.nextSequence();` then `if (UnknownHistoryGuard.requireKnown(store.getEventBySequence(ctx.workflowInstanceId(), parentSeq), ctx.workflowId()).isEmpty()) { appendEvent(...same args...); } else { logger.debug("Replaying parallel-compensation fork at seq {} for workflow '{}' — already recorded", parentSeq, ctx.workflowId()); }`. Update the method Javadoc (`:343-356`) with one sentence: the fork point is replay-guarded like `COMPENSATION_STARTED`, because `appendEvent` rethrows `DuplicateEventException` and an unguarded re-append on recovery stood the run down forever (C2).

- [ ] **Step 4: Run the class and the core suite**

Run: `./gradlew :maestro-core:test --tests 'io.b2mash.maestro.core.saga.SagaManagerTest' --console=plain`
Expected: `BUILD SUCCESSFUL`, all tests in the class pass (count on `main` + 1).
Run: `./gradlew :maestro-core:test --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Write the failing Postgres IT (crash mid-parallel-compensation)**

Add to `EnginePostgresParallelSagaIT`. Fixtures: `@Saga(parallelCompensation = true)` workflow with the linear `SagaWorkflow` body (`TestWorkflows.java:228-233`), test-local as `ParallelCompensationSagaWorkflow`; two test fields `CountDownLatch reachedRefund = new CountDownLatch(1)`, `CountDownLatch wedge = new CountDownLatch(1)`, and a field `MaestroEngineHarness recoveringNode`; a `WedgingRefund implements SagaActivities` decorator (shape of `GatedSagaActivities`, `EnginePostgresSagaIT.java:233-287`) whose `refund` counts down `reachedRefund` then blocks on `wedge`. `@AfterEach` does `wedge.countDown()` first, then closes `recoveringNode` and `harness` (`EnginePostgresParallelIT.java:66-74`). Node A: no lock (`.lock(null)`), `instanceLockTtl 2s`. Node B: `newLock()`, `.observer(observerB)` where `observerB = new RecordingObserver()`.

```java
@Test
@DisplayName("a node that dies mid-parallel-compensation is finished by a recovering node — no stand-down, no stuck COMPENSATING")
void crashMidParallelCompensation_recoversToFailed() throws Exception {
    var recorderA = new CountingActivities.Recorder();
    harness = nodeFor(ParallelCompensationSagaWorkflow.class, "node-a",
            new WedgingRefund(new CountingActivities.RecordingSagaActivities(recorderA, "ship")), /*lock*/ false, null);
    var workflowId = MaestroEngineHarness.uniqueWorkflowId("parallel-comp-crash");
    var handle = harness.start(workflowId, ParallelCompensationSagaWorkflow.class, "widget");

    assertTrue(reachedRefund.await(30, TimeUnit.SECONDS), "node A never reached the parallel compensation phase");
    await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(50))
            .until(() -> store.getEventBySequence(handle.instanceId(), 7000).isPresent());
    assertEquals(WorkflowStatus.COMPENSATING, handle.status());
    assertTrue(store.getEventBySequence(handle.instanceId(), 6000).isEmpty(), "the wedged refund branch must have recorded nothing");
    assertEquals(1, recorderA.count("releaseReservation"));

    var recorderB = new CountingActivities.Recorder();
    var observerB = new RecordingObserver();
    recoveringNode = nodeFor(ParallelCompensationSagaWorkflow.class, "node-b",
            new CountingActivities.RecordingSagaActivities(recorderB, "ship"), /*lock*/ true, observerB);
    assertEquals(1, recoveringNode.recover());

    var instance = awaitTerminal(workflowId, Duration.ofSeconds(15));
    assertEquals(WorkflowStatus.FAILED, instance.status(), "the compensated saga must finish FAILED, not sit in COMPENSATING");
    assertEquals(List.of(), observerB.standDowns(), "re-appending the fork marker used to stand the recovering run down");
    assertEquals(0, recorderB.count("ship"), "ship's ACTIVITY_FAILED replays — no live retries");
    assertEquals(1, recorderB.count("refund"), "the interrupted branch runs exactly once on the recovering node");
    assertEquals(0, recorderB.count("releaseReservation"), "the durable branch replays");
    assertEquals(List.of(
            "1:ACTIVITY_COMPLETED:saga.reserve",
            "2:ACTIVITY_COMPLETED:saga.charge",
            "3:ACTIVITY_FAILED:saga.ship",
            "4:COMPENSATION_STARTED:$maestro:compensation",
            "5:SIDE_EFFECT:$maestro:parallel-compensation",
            "6000:COMPENSATION_STEP_COMPLETED:saga.refund",
            "6001:ACTIVITY_COMPLETED:saga.refund",
            "7000:COMPENSATION_STEP_COMPLETED:saga.releaseReservation",
            "7001:ACTIVITY_COMPLETED:saga.releaseReservation",
            "8001:COMPENSATION_COMPLETED:$maestro:compensation",
            "8002:WORKFLOW_FAILED:null"),
            describe(instance.id()));
}
```

Sequence derivation (so the implementer can re-check): forward 1,2,3; `COMPENSATION_STARTED` 4; fork marker 5; LIFO entries `[refund, releaseReservation]` → bases `5*1000+1000 = 6000`, `7000`; after join counter `5*1000+3*1000 = 8000`. `nodeFor(workflowClass, serviceName, SagaActivities, boolean withLock, @Nullable RecordingObserver)` generalises Task 1's `node(...)`; refactor Task 1's call site to use it.

- [ ] **Step 6: Confirm the IT pins the bug against `main`'s `SagaManager`**

`git stash` only `SagaManager.java` (`git stash push maestro-core/src/main/java/io/b2mash/maestro/core/saga/SagaManager.java`), then:
Run: `./gradlew :maestro-integration-tests:test --tests 'io.b2mash.maestro.integration.engine.EnginePostgresParallelSagaIT.crashMidParallelCompensation_recoversToFailed' --console=plain`
Expected: FAIL — `awaitTerminal` times out with the row still `COMPENSATING`, and `observerB.standDowns()` contains one `STALE_RUN` entry. Then `git stash pop`.

- [ ] **Step 7: Run the IT with the fix**

Run: `./gradlew :maestro-integration-tests:test --tests 'io.b2mash.maestro.integration.engine.EnginePostgresParallelSagaIT' --console=plain`
Expected: `BUILD SUCCESSFUL`, 2 tests passed.

- [ ] **Step 8: Release notes**

Under `## Unreleased`: `### Fixed — A crash during parallel saga compensation no longer strands the workflow in COMPENSATING` — the `$maestro:parallel-compensation` fork marker was appended unconditionally; on recovery the duplicate was rethrown as a stale-run stand-down, so the instance was re-adopted every recovery poll forever; the marker is now replay-guarded like `COMPENSATION_STARTED`; the pre-existing unit pin passed only because the test's in-memory store lacked the unique index — it now has one.

- [ ] **Step 9: Commit**

```bash
git add maestro-core/src/main/java/io/b2mash/maestro/core/saga/SagaManager.java \
        maestro-core/src/test/java/io/b2mash/maestro/core/saga/SagaManagerTest.java \
        maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/EnginePostgresParallelSagaIT.java \
        docs/release-notes.md
git commit -m "fix(saga): replay-guard the parallel-compensation fork marker so recovery does not stand down forever (C2)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: One overflow-guarded `SequenceSpace` for every block computation (C4)

**Files:**
- Create: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/SequenceSpace.java`
- Create: `maestro-core/src/main/java/io/b2mash/maestro/core/exception/SequenceSpaceExhaustedException.java`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/exception/MaestroException.java:30-46` (permits)
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/DefaultWorkflowOperations.java:65-73, 540-551, 582` (+ the `nextParentSeq` line Task 1 added)
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/saga/SagaManager.java:157-165, 257, 329, 336-341, 381, 464, 509`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java:1713-1722`
- Create: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/SequenceSpaceTest.java`
- Create: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/SequenceSpaceExhaustionTest.java`
- Modify: `maestro-core/src/test/java/io/b2mash/maestro/core/saga/SagaManagerTest.java` (one test)
- Modify: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/EnginePostgresParallelSagaIT.java` (one test)
- Modify: `docs/concepts.md:416` (depth paragraph), `docs/release-notes.md`

**Interfaces:**
- Produces:
  ```java
  package io.b2mash.maestro.core.engine;
  public final class SequenceSpace {                       // static-only, private ctor, Javadoc'd "internal engine API"
      public static final int BRANCH_MULTIPLIER = 1000;
      public static int branchBase(int parentSeq, int branchIndex);          // parentSeq*1000 + (branchIndex+1)*1000, no check
      public static int nextParentSeq(String workflowId, int parentSeq, int branchCount)
              throws SequenceSpaceExhaustedException;                        // (long)parentSeq*1000 + (long)(branchCount+1)*1000 > Integer.MAX_VALUE → throw
  }
  ```
  ```java
  package io.b2mash.maestro.core.exception;
  public final class SequenceSpaceExhaustedException extends MaestroException {
      public SequenceSpaceExhaustedException(String workflowId, int parentSeq, int branchCount);
      public String workflowId(); public int parentSeq(); public int branchCount();
  }
  // message: "Forking %d branch(es) at sequence %d of workflow '%s' would exceed the 32-bit sequence space; "
  //          + "reduce parallel() nesting/sequential forks or the number of compensation entries"
  ```
  Task 4 consumes `SequenceSpace.BRANCH_MULTIPLIER`; Task 5 relies on the pre-check living in `compensate()`.

- [ ] **Step 1: Write the failing pure-arithmetic test**

```java
class SequenceSpaceTest {
    @Test void branchBase_matchesTheDocumentedFormula() {
        assertEquals(2000, SequenceSpace.branchBase(1, 0));
        assertEquals(8000, SequenceSpace.branchBase(5, 2));
    }
    @Test void nextParentSeq_isOnePastTheLastBranchBlock() {
        assertEquals(5000, SequenceSpace.nextParentSeq("wf", 1, 3));
        assertEquals(5_005_000, SequenceSpace.nextParentSeq("wf", 5001, 3));
        assertEquals(2_147_483_000, SequenceSpace.nextParentSeq("wf", 2_147_479, 3), "largest 3-branch fork that fits");
    }
    @Test void nextParentSeq_throwsInsteadOfWrapping() {
        var e = assertThrows(SequenceSpaceExhaustedException.class, () -> SequenceSpace.nextParentSeq("wf", 2_147_480, 3));
        assertAll(() -> assertEquals("wf", e.workflowId()),
                  () -> assertEquals(2_147_480, e.parentSeq()),
                  () -> assertEquals(3, e.branchCount()));
        assertThrows(SequenceSpaceExhaustedException.class, () -> SequenceSpace.nextParentSeq("wf", 5_005_001, 3), "the third sequential fork of a workflow");
    }
}
```

- [ ] **Step 2: Run it, verify compilation failure**

Run: `./gradlew :maestro-core:test --tests 'io.b2mash.maestro.core.engine.SequenceSpaceTest' --console=plain`
Expected: FAIL — `cannot find symbol: class SequenceSpace`.

- [ ] **Step 3: Create `SequenceSpace` and `SequenceSpaceExhaustedException`; extend `permits`**

Signatures as in Interfaces. Add `SequenceSpaceExhaustedException` to the `permits` list at `MaestroException.java:30-46`. Javadoc on the utility: the partition formula, who calls it (`DefaultWorkflowOperations.parallel()`, `SagaManager.compensate()`/`executeSequential`/`executeParallel`), and that it is public only because `SagaManager` is in another package.

- [ ] **Step 4: Run the arithmetic test**

Run: `./gradlew :maestro-core:test --tests 'io.b2mash.maestro.core.engine.SequenceSpaceTest' --console=plain`
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 5: Write the failing engine-level tests**

`SequenceSpaceExhaustionTest extends ParallelSagaTestSupport` (Task 1). `twoBranches()` returns `List.<Callable<String>>of(() -> "a", () -> "b")`. `TripleForkWorkflow` is a test-local `public static class` with an `AtomicInteger` constructor argument and `public String run(String input)` that calls `WorkflowContext.current().addCompensation("undo", undone::incrementAndGet)` and then `parallel(twoBranches())` three times — no annotations are needed at the executor level (`handleWorkflowFailure` compensates whenever the stack is non-empty). `TestTerminalWait.awaitTerminal(store, workflowId, Duration)` is at `maestro-core/src/test/java/io/b2mash/maestro/core/TestTerminalWait.java:103`.

```java
@Test
@DisplayName("the third sequential parallel() fails with SequenceSpaceExhaustedException, consuming no sequence and appending nothing")
void thirdSequentialFork_failsBeforeConsumingASequence() {
    inScope(() -> { ops.parallel(twoBranches()); ops.parallel(twoBranches()); });
    assertEquals(4_004_000, ctx.currentSequence(), "1 → 4000 → 4001*1000+3000");

    var e = assertThrows(SequenceSpaceExhaustedException.class, () -> inScope(() -> ops.parallel(twoBranches())));
    assertAll(() -> assertEquals(4_004_001, e.parentSeq()), () -> assertEquals(2, e.branchCount()));
    assertEquals(4_004_000, ctx.currentSequence(), "the pre-check must not consume the fork's sequence");
    assertTrue(store.getEventBySequence(instance.id(), 4_004_001).isEmpty(), "no fork marker for a fork that never happened");
}

@Test
@DisplayName("when compensation block arithmetic would overflow, the workflow still finishes FAILED — no zombie, no compensation run at a wrapped sequence")
void compensationOverflow_finishesFailedWithoutRunningCompensations() throws Exception {
    var undone = new AtomicInteger();
    var workflow = new TripleForkWorkflow(undone);
    var executor = new WorkflowExecutor(store, null, null, null, serializer, "node-a");
    try {
        executor.startWorkflow("triple-fork", "TripleForkWorkflow", "default", "in", workflow,
                TripleForkWorkflow.class.getMethod("run", String.class));
        TestTerminalWait.awaitTerminal(store, "triple-fork", Duration.ofSeconds(5));
        var instance = store.getInstance("triple-fork").orElseThrow();
        assertEquals(WorkflowStatus.FAILED, instance.status());
        assertEquals(SequenceSpaceExhaustedException.class.getName(), instance.output().get("exceptionType").asText());
        assertEquals(0, undone.get(), "no compensation may run at an int-wrapped sequence");
        assertEquals(2, count(EventType.SIDE_EFFECT, "$maestro:parallel"));
        assertEquals(0, count(EventType.COMPENSATION_STARTED));
        assertEquals(1, count(EventType.WORKFLOW_FAILED));
    } finally { executor.shutdown(); }
}
```

`SagaManagerTest` — add:

```java
@Test
@DisplayName("Compensation whose block arithmetic would overflow throws before transitioning or running anything")
void compensationBlockOverflow_failsBeforeAnyActionRuns() {
    var invocations = new AtomicInteger();
    var stack = new CompensationStack();
    stack.push("step-A", invocations::incrementAndGet);
    stack.push("step-B", invocations::incrementAndGet);
    var highCtx = new WorkflowContext(instance.id(), "test-workflow", instance.runId(),
            "TestWorkflow", "default", "test-service", 2_147_480, false);  // anchor 2_147_481; (anchor*1000 + 3*1000) > MAX

    var e = assertThrows(SequenceSpaceExhaustedException.class,
            () -> sagaManager.compensate(highCtx, instance, stack, false));
    assertEquals(2_147_481, e.parentSeq());
    assertEquals(0, invocations.get());
    assertEquals(WorkflowStatus.RUNNING, store.getInstance("test-workflow").orElseThrow().status(), "pre-check runs before transitionToCompensating");
    assertEquals(0, countEvents(EventType.COMPENSATION_STARTED));
}
```

- [ ] **Step 6: Run the new tests, verify they fail for the right reasons**

Run: `./gradlew :maestro-core:test --tests 'io.b2mash.maestro.core.engine.SequenceSpaceExhaustionTest' --tests 'io.b2mash.maestro.core.saga.SagaManagerTest.compensationBlockOverflow_failsBeforeAnyActionRuns' --console=plain`
Expected: `thirdSequentialFork_*` FAILS with `IllegalStateException` where `SequenceSpaceExhaustedException` was expected; `compensationOverflow_*` FAILS on `exceptionType` (`java.lang.IllegalStateException`) and `undone == 1` (the action ran at a wrapped, negative sequence); `compensationBlockOverflow_*` FAILS because `compensate` returns normally (int wraparound) with `invocations == 2`.

- [ ] **Step 7: Route every block computation through `SequenceSpace`**

- `DefaultWorkflowOperations`: delete the local `BRANCH_MULTIPLIER` (`:65-73`); replace the guard at `:540-549` with `SequenceSpace.nextParentSeq(ctx.workflowId(), ctx.currentSequence() + 1, tasks.size());` (result discarded — pre-check before any sequence is consumed); after `var parentSeq = ctx.nextSequence();` compute `var nextParentSeq = SequenceSpace.nextParentSeq(ctx.workflowId(), parentSeq, branchCount);` (replacing Task 1's expression); `:582` becomes `SequenceSpace.branchBase(parentSeq, branchIndex)`.
- `SagaManager`: delete the private constant (`:336-341`). In `compensate()`, after the `entries.isEmpty()` return (`:157-160`) and **before** `transitionToCompensating` (`:165`), add the pre-check: `var anchor = ctx.currentSequence() + 1; SequenceSpace.nextParentSeq(ctx.workflowId(), parallelCompensation ? anchor + 1 : anchor, entries.size());`. Replace `:257`, `:381`, `:509` with `SequenceSpace.branchBase(...)` and `:329`, `:464` with `SequenceSpace.nextParentSeq(ctx.workflowId(), ..., entries.size())`. Javadoc the pre-check: why before the transition (nothing durable may change if compensation cannot be sequenced), and that the executor turns it into a loud `FAILED`.
- `WorkflowExecutor.handleWorkflowFailure` (`:1713-1722`): add `catch (SequenceSpaceExhaustedException noRoom)` after the `CompensationException` arm: `logger.error("Workflow '{}' failed and its registered compensations could NOT run — the sequence space is exhausted ({}). Finishing as FAILED; compensate manually.", ctx.workflowId(), noRoom.getMessage(), noRoom)`. No count in the message (`compensate()` has already drained the stack). Then fall through to the existing `FAILED` transition.

- [ ] **Step 8: Run the new tests and the whole core suite**

Run: `./gradlew :maestro-core:test --console=plain`
Expected: `BUILD SUCCESSFUL`; `SequenceSpaceExhaustionTest` 2 passed, `SagaManagerTest` all passed, no regressions in `EnginePostgresParallelIT`-style sequence pins (`WorkflowVersionParallelBranchTest` still asserts `2001`/`3001`).

- [ ] **Step 9: Write and run the Postgres IT**

Add to `EnginePostgresParallelSagaIT` a test-local `TripleForkWorkflow` (`@DurableWorkflow(name = "TripleForkWorkflow")`, `@Saga @WorkflowMethod public String run(String input)`; `addCompensation("undo", () -> undone.set(true))`; three `parallel(List.of(() -> "a", () -> "b"))`), `undone` an `AtomicBoolean` field on the workflow object (pattern: `ParallelParkingWorkflow.compensated`, `EnginePostgresParallelIT.java:300-320`):

```java
@Test
@DisplayName("a workflow that exhausts the sequence space finishes FAILED with no compensation and no stand-down")
void sequenceSpaceExhaustion_isALoudFailureNotAZombie() {
    var observer = new RecordingObserver();
    var workflow = new TripleForkWorkflow();
    harness = MaestroEngineHarness.builder(store, objectMapper).serviceName("triple-fork").lock(newLock())
            .observer(observer).build();
    harness.registerWorkflow(workflow);
    var handle = harness.start(MaestroEngineHarness.uniqueWorkflowId("triple-fork"), TripleForkWorkflow.class, "in");

    assertEquals(WorkflowStatus.FAILED, handle.awaitTerminal(Duration.ofSeconds(15)));
    assertEquals(SequenceSpaceExhaustedException.class.getName(), handle.instance().output().get("exceptionType").asText());
    assertFalse(workflow.undone.get(), "no compensation may run at a wrapped sequence");
    assertEquals(List.of(), observer.standDowns());
    assertEquals(List.of("1:SIDE_EFFECT:$maestro:parallel", "4001:SIDE_EFFECT:$maestro:parallel", "4004001:WORKFLOW_FAILED:null"),
            describe(handle));
}
```

Run: `./gradlew :maestro-integration-tests:test --tests 'io.b2mash.maestro.integration.engine.EnginePostgresParallelSagaIT' --console=plain`
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 10: Document the depth limit and the release note**

`docs/concepts.md:416` — after the existing sentence, add one paragraph: every fork level multiplies the sequence by 1000, and the sequence is a 32-bit `int`, so from a parent sequence below ~2,000 a workflow has room for **two** fork levels (two sequential `parallel()` calls, or one `parallel()` and the compensation phase that follows a failure, or one `parallel()` nested inside another); a third level fails the workflow with `SequenceSpaceExhaustedException` *before* any sequence is consumed. State the compensation consequence explicitly: **a saga that has already made two sequential `parallel()` calls cannot compensate at all** — its compensation anchor is ≈ 4,004,001–7,005,001 depending on branch counts, and the anchor × 1000 block arithmetic overflows `int` — so the workflow is still finished `FAILED`, but **no compensation runs**; the engine logs at `ERROR` and the operator must compensate manually. Link to `docs/open-issues.md#issue-28` (Step 11). Mirror the "two fork levels" sentence at `docs/maestro-architecture.md:567`.

`docs/release-notes.md` under `## Unreleased`: `### Changed — Sequence-space overflow is now a typed failure; compensation arithmetic is guarded too` — `IllegalStateException` from `parallel()` becomes `SequenceSpaceExhaustedException`; `SagaManager`'s block math previously wrapped `int` silently and ran compensations at garbage sequences; now it pre-checks in `compensate()` and the executor finishes `FAILED` with an `ERROR` log that no compensation ran. Link the `concepts.md` paragraph and `open-issues.md#issue-28`.

- [ ] **Step 11: File Issue 28 in the ledger (open by design, structural)**

In `docs/open-issues.md`: add a row to the §4 index table after Issue 27 (`:282`) — `| [28](#issue-28) | ×1000 sequence partitioning bounds sequential/nested `parallel()` and compensation depth | Structural limitation | Medium | **Open by design** |` — and append a new section after Issue 27 (`:2341-2408`, before `## 6. Suggested order` at `:2409`), in the ledger's existing format (`:2341-2360` for the blockquote/Kind/Severity shape; `:816-840` for the open-by-design voice):

`### Issue 28 — ×1000 sequence partitioning bounds sequential/nested `parallel()` and compensation depth {#issue-28}`

> **Open by design (structural).** Filed by the 2026-10-24 bug-fix cycle while closing C1–C4 (`docs/superpowers/plans/2026-10-24-bugfix-cycle/`). The cycle deliberately chose a loud failure (`SequenceSpaceExhaustedException`, `FAILED`, `ERROR` log) over the previous behaviour of silently wrapping `int` and running compensations at garbage sequence numbers.

**Kind:** Structural limitation — sequence-space design. **Severity:** Medium (deterministic and loud, but it makes a whole class of workflow shape unusable with sagas).

**What's wrong.** Every fork level multiplies the parent sequence by 1000 and the sequence is a 32-bit `int`, so depth compounds. Concrete limits once C1 lands (the counter now sits *past* the branch space after any `parallel()`): one `parallel()` plus compensation fits; **two sequential `parallel()` calls at a low parent sequence put the compensation anchor at ≈ 7,004,001** (e.g. two plain steps, a 3-branch fork at seq 3 → counter 7000, a 2-branch fork at 7001 → counter 7,004,000, anchor 7,004,001; with 2-branch forks from seq 1 it is 4,004,001) — the anchor × 1000 block arithmetic overflows `int`, so **compensation cannot run**: the workflow finishes `FAILED` with an `ERROR` log saying so, and the operator compensates by hand. A third sequential or nested `parallel()` fails the same way before any sequence is consumed. Enforced by `SequenceSpace.nextParentSeq` (Task 3) and pinned by `SequenceSpaceExhaustionTest` and `EnginePostgresParallelSagaIT.sequenceSpaceExhaustion_isALoudFailureNotAZombie`.

**How to tackle it.** Two candidate redesigns for a future cycle:
(a) **Widen `sequence_number` to `BIGINT`/`long`** across the SPI (`WorkflowStore.getEventBySequence`), model (`WorkflowEvent`, `WorkflowInstance.eventSequence`), both stores (new Flyway migrations in the store band), `maestro-test` fakes and the admin dashboard — mechanical, no change to ordering or display, but only raises the ceiling to roughly three sequential `parallel()` calls plus compensation; the compounding stays.
(b) **Replace the single integer line with a structured event identity** — a path such as `"7.1.3"` (fork seq, branch, step), or a reserved non-colliding compensation space (e.g. negative sequence numbers for the compensation phase) — which removes compounding entirely, but changes event ordering, the unique index, and how the dashboard displays and sorts history; a schema and SPI change, not a patch.

**Done when.** A workflow with three sequential 3-branch `parallel()` calls followed by a failing step compensates fully on real Postgres, and `SequenceSpaceExhaustedException` is either unreachable for realistic depth or removed.

Check: `grep -n "issue-28" docs/open-issues.md docs/concepts.md docs/release-notes.md` → three hits (index row + section anchor, the `concepts.md` link, the release-note link).

- [ ] **Step 12: Commit**

```bash
git add maestro-core/src/main/java/io/b2mash/maestro/core/engine/SequenceSpace.java \
        maestro-core/src/main/java/io/b2mash/maestro/core/exception/SequenceSpaceExhaustedException.java \
        maestro-core/src/main/java/io/b2mash/maestro/core/exception/MaestroException.java \
        maestro-core/src/main/java/io/b2mash/maestro/core/engine/DefaultWorkflowOperations.java \
        maestro-core/src/main/java/io/b2mash/maestro/core/saga/SagaManager.java \
        maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java \
        maestro-core/src/test/java/io/b2mash/maestro/core/engine/SequenceSpaceTest.java \
        maestro-core/src/test/java/io/b2mash/maestro/core/engine/SequenceSpaceExhaustionTest.java \
        maestro-core/src/test/java/io/b2mash/maestro/core/saga/SagaManagerTest.java \
        maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/EnginePostgresParallelSagaIT.java \
        docs/concepts.md docs/maestro-architecture.md docs/release-notes.md docs/open-issues.md
git commit -m "fix(core): one overflow-guarded SequenceSpace for parallel() and saga block arithmetic (C4)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: Enforce the 999-step branch limit with `BranchSequenceExhaustedException` (C3)

**Files:**
- Create: `maestro-core/src/main/java/io/b2mash/maestro/core/exception/BranchSequenceExhaustedException.java`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/exception/MaestroException.java:30-46` (permits)
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/context/WorkflowContext.java:80, 130-150, 185-193, 264-273`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/DefaultWorkflowOperations.java:591-600`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/saga/SagaManager.java:393-403`
- Create: `maestro-core/src/test/java/io/b2mash/maestro/core/context/WorkflowContextBoundTest.java`
- Create: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/ParallelBranchExhaustionTest.java`
- Modify: `maestro-core/src/test/java/io/b2mash/maestro/core/saga/SagaManagerTest.java` (one test)
- Modify: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/EnginePostgresParallelIT.java` (one test + fixture)
- Modify: `docs/concepts.md:416`, `docs/maestro-architecture.md:567`, `CLAUDE.md:27`, `docs/release-notes.md`

**Interfaces:**
- Consumes: `SequenceSpace.BRANCH_MULTIPLIER` (Task 3).
- Produces:
  ```java
  // WorkflowContext
  public static WorkflowContext bounded(WorkflowContext parent, int branchIndex, int baseSeq,
                                        int limitExclusive, @Nullable WorkflowOperations operations);
  // identity fields and isReplaying() copied from parent; counter starts at baseSeq.
  // nextSequence(): if bounded and currentSequence() == limitExclusive - 1 → throw
  //   BranchSequenceExhaustedException(workflowId, branchIndex, baseSeq, limitExclusive); else incrementAndGet().
  // setSequence() unchanged (engine-internal; a nested parallel() join rebases past the block on purpose).
  ```
  ```java
  public final class BranchSequenceExhaustedException extends MaestroException {
      public BranchSequenceExhaustedException(String workflowId, int branchIndex, int baseSeq, int limit);
      public String workflowId(); public int branchIndex(); public int baseSeq(); public int limit();
  }
  // message: "Parallel branch %d of workflow '%s' exhausted its sequence block [%d, %d): a branch may take at most %d memoized steps"
  ```

- [ ] **Step 1: Write the failing context-level test**

```java
class WorkflowContextBoundTest {
    // parent = new WorkflowContext(UUID, "wf", UUID, "T", "q", "svc", 1, false); branch = WorkflowContext.bounded(parent, 0, 2000, 3000, null)
    @Test void boundedBranch_hands_out_exactly_999_slots() {
        for (int expected = 2001; expected <= 2999; expected++) assertEquals(expected, branch.nextSequence());
        var e = assertThrows(BranchSequenceExhaustedException.class, branch::nextSequence);
        assertAll(() -> assertEquals("wf", e.workflowId()), () -> assertEquals(0, e.branchIndex()),
                  () -> assertEquals(2000, e.baseSeq()), () -> assertEquals(3000, e.limit()));
        assertEquals(2999, branch.currentSequence(), "a refused step must not move the counter");
    }
    @Test void boundedBranch_copiesIdentityAndReplayFlag() {
        assertAll(() -> assertEquals(parent.workflowInstanceId(), branch.workflowInstanceId()),
                  () -> assertEquals(parent.runId(), branch.runId()), () -> assertEquals(parent.isReplaying(), branch.isReplaying()),
                  () -> assertEquals(2000, branch.currentSequence()));
    }
    @Test void counterRebasedByANestedJoin_isNotBounded() {
        branch.setSequence(2_005_000);                     // what a nested parallel() join does
        assertEquals(2_005_001, branch.nextSequence());
    }
    @Test void unboundedContext_neverThrows() {
        parent.setSequence(2999); assertEquals(3000, parent.nextSequence());
    }
}
```

- [ ] **Step 2: Run it, verify compilation failure**

Run: `./gradlew :maestro-core:test --tests 'io.b2mash.maestro.core.context.WorkflowContextBoundTest' --console=plain`
Expected: FAIL — `cannot find symbol: method bounded(...)` / `class BranchSequenceExhaustedException`.

- [ ] **Step 3: Implement the exception, the factory and the bounded `nextSequence()`**

Create `BranchSequenceExhaustedException` (signature above); add to `permits` at `MaestroException.java:30-46`. In `WorkflowContext`: a private nested `record BranchBound(int branchIndex, int baseSeq, int limitExclusive)` and a `private final @Nullable BranchBound bound` field (`null` for the two public constructors at `:100-150`, which delegate to a new private all-args constructor); the `bounded(...)` factory; `nextSequence()` (`:191-193`) per the Interfaces rule. Javadoc on the factory and on `nextSequence()`: the rule is "the 1000th direct step throws", why `setSequence` is not bounded (nested join), thread-safety note that the compare-then-increment is safe because a branch context is used by exactly one virtual thread.

- [ ] **Step 4: Run the context test**

Run: `./gradlew :maestro-core:test --tests 'io.b2mash.maestro.core.context.WorkflowContextBoundTest' --console=plain`
Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 5: Write the failing engine-level tests**

`ParallelBranchExhaustionTest extends ParallelSagaTestSupport` (Task 1):

```java
@Test
@DisplayName("the 1000th step of a branch fails the fork with BranchSequenceExhaustedException; the sibling's slots are untouched and fully persisted")
void branchExceedingItsBlock_failsWithoutTouchingSiblings() {
    var e = assertThrows(BranchSequenceExhaustedException.class, () -> inScope(() -> ops.parallel(List.of(
            () -> { for (int i = 0; i < 1000; i++) WorkflowContext.current().randomUUID(); return "never"; },
            () -> WorkflowContext.current().randomUUID()))));
    assertAll(() -> assertEquals(0, e.branchIndex()), () -> assertEquals(2000, e.baseSeq()), () -> assertEquals(3000, e.limit()));
    assertTrue(store.getEventBySequence(instance.id(), 2999).isPresent(), "999 steps fit");
    assertTrue(store.getEventBySequence(instance.id(), 3000).isEmpty(), "the sibling's base is never written by branch 0");
    assertEquals(EventType.SIDE_EFFECT, store.getEventBySequence(instance.id(), 3001).orElseThrow().eventType(),
            "parallel() joins every branch before rethrowing, so branch 1's own step is durable");
    assertEquals(1000, count(EventType.SIDE_EFFECT, "$maestro:randomUUID"), "999 from branch 0 + 1 from branch 1");
    assertEquals(4000, ctx.currentSequence(), "Task 1's finally still advances the parent");
}

@Test
@DisplayName("999 steps in a branch succeed")
void branchWith999Steps_succeeds() {
    inScope(() -> ops.parallel(List.of(
            () -> { for (int i = 0; i < 999; i++) WorkflowContext.current().randomUUID(); return "ok"; },
            () -> "ok")));
    assertEquals(999, count(EventType.SIDE_EFFECT, "$maestro:randomUUID"));
    assertTrue(store.getEventBySequence(instance.id(), 2999).isPresent());
    assertTrue(store.getEventBySequence(instance.id(), 3000).isEmpty());
}
```

`SagaManagerTest` — a parallel-compensation branch is bounded the same way (its base here is `8000`: ctx 5 → started 6 → fork 7 → `7*1000+1000`):

```java
@Test
@DisplayName("Parallel compensation: a branch that exhausts its block is recorded COMPENSATION_STEP_FAILED with the typed cause")
void parallelCompensationBranchExhaustingItsBlock_isRecordedFailed() {
    var stack = new CompensationStack();
    stack.push("greedy", () -> { for (int i = 0; i < 1000; i++) WorkflowContext.current().nextSequence(); });

    assertThrows(CompensationException.class, () -> sagaManager.compensate(ctx, instance, stack, true));

    var failed = store.getEventBySequence(instance.id(), 8000).orElseThrow();
    assertEquals(EventType.COMPENSATION_STEP_FAILED, failed.eventType());
    assertEquals(BranchSequenceExhaustedException.class.getName(), failed.payload().get("exceptionType").asText());
}
```

- [ ] **Step 6: Run them, verify the right failures**

Run: `./gradlew :maestro-core:test --tests 'io.b2mash.maestro.core.engine.ParallelBranchExhaustionTest' --tests 'io.b2mash.maestro.core.saga.SagaManagerTest.parallelCompensationBranchExhaustingItsBlock_isRecordedFailed' --console=plain`
Expected: `branchExceedingItsBlock_*` FAILS — no exception thrown, and (if you inspect) an event exists at `3000`; `branchWith999Steps_succeeds` PASSES already (boundary sanity); the `SagaManagerTest` addition FAILS because `compensate` returns normally.

- [ ] **Step 7: Use the bounded factory at both branch-spawn sites**

- `DefaultWorkflowOperations.java:591-600`: replace the 9-arg `new WorkflowContext(...)` with `WorkflowContext.bounded(ctx, branchIndex, branchBaseSeq, branchBaseSeq + SequenceSpace.BRANCH_MULTIPLIER, DefaultWorkflowOperations.this)`.
- `SagaManager.java:393-403`: `WorkflowContext.bounded(ctx, index, branchBaseSeq, branchBaseSeq + SequenceSpace.BRANCH_MULTIPLIER, null)` — keep the existing comment about `null` operations.

- [ ] **Step 8: Run the core suite**

Run: `./gradlew :maestro-core:test --console=plain`
Expected: `BUILD SUCCESSFUL`; `ParallelBranchExhaustionTest` 2 passed; `WorkflowVersionParallelBranchTest`, `UnknownHistoryDetectionSitesTest.parallelStandsDown`, `WorkflowExecutorShutdownTest` (its `ParkingParallelBranchWorkflow`) unchanged.

- [ ] **Step 9: Write and run the Postgres IT**

Add to `EnginePostgresParallelIT` a test-local `OverlongBranchWorkflow` (`@ActivityStub ChainActivities activities`; `parallel([branch 0: 1000 × WorkflowContext.current().randomUUID(), branch 1: activities.stepTwo(input)])`), built like `twoParkingBranches_doNotRaceTheInstanceRow` (`:170-180`) with `newLock()` and a `RecordingChainActivities(recorder)` registration:

```java
@Test
@DisplayName("a branch that takes a 1000th step fails the workflow; its sibling's block and the parent counter are intact")
void branchExceedingItsBlock_failsTheWorkflowAndLeavesSiblingsIntact() {
    assertEquals(WorkflowStatus.FAILED, handle.awaitTerminal(Duration.ofSeconds(30)));
    assertEquals(BranchSequenceExhaustedException.class.getName(), handle.instance().output().get("exceptionType").asText());
    assertEquals(1, recorder.count("stepTwo"), "the sibling branch ran to completion before the fork rethrew");
    assertTrue(store.getEventBySequence(handle.instanceId(), 2999).isPresent());
    assertTrue(store.getEventBySequence(handle.instanceId(), 3000).isEmpty(), "branch 1's base is never written by branch 0");
    assertEquals("3001:ACTIVITY_COMPLETED:chain.stepTwo", describe(handle).get(1000), "events: fork(1), 999 uuids, then branch 1");
    assertEquals("4001:WORKFLOW_FAILED:null", describe(handle).getLast(), "parent counter advanced to 1*1000 + 3*1000 before the terminal event");
    assertEquals(0, handle.events().stream().filter(e -> e.eventType() == EventType.COMPENSATION_STARTED).count(), "nothing was registered to compensate");
}
```

Run: `./gradlew :maestro-integration-tests:test --tests 'io.b2mash.maestro.integration.engine.EnginePostgresParallelIT' --console=plain`
Expected: `BUILD SUCCESSFUL`, 5 tests passed.

- [ ] **Step 10: State the consequence in the docs and release notes**

- `docs/concepts.md:416`: change "with up to 999 steps per branch" to "with up to 999 memoized steps per branch — **the 1000th step throws `BranchSequenceExhaustedException`, failing the branch and therefore the workflow (its registered compensations run)**; the counter never spills into the sibling's block".
- `docs/maestro-architecture.md:567` and `CLAUDE.md:27`: append "(the 1000th step fails the branch with `BranchSequenceExhaustedException`)".
- `docs/release-notes.md` under `## Unreleased`: `### Changed — The 999-step-per-branch limit is enforced` — previously step 1000 silently read/wrote the next branch's slots; now a bounded branch context throws; applies to `parallel()` branches and parallel-compensation branches alike.

- [ ] **Step 11: Commit**

```bash
git add maestro-core/src/main/java/io/b2mash/maestro/core/exception/BranchSequenceExhaustedException.java \
        maestro-core/src/main/java/io/b2mash/maestro/core/exception/MaestroException.java \
        maestro-core/src/main/java/io/b2mash/maestro/core/context/WorkflowContext.java \
        maestro-core/src/main/java/io/b2mash/maestro/core/engine/DefaultWorkflowOperations.java \
        maestro-core/src/main/java/io/b2mash/maestro/core/saga/SagaManager.java \
        maestro-core/src/test/java/io/b2mash/maestro/core/context/WorkflowContextBoundTest.java \
        maestro-core/src/test/java/io/b2mash/maestro/core/engine/ParallelBranchExhaustionTest.java \
        maestro-core/src/test/java/io/b2mash/maestro/core/saga/SagaManagerTest.java \
        maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/EnginePostgresParallelIT.java \
        docs/concepts.md docs/maestro-architecture.md CLAUDE.md docs/release-notes.md
git commit -m "fix(core): enforce the 999-step branch limit with BranchSequenceExhaustedException (C3)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Pin "parallel() inside a compensation" (Review Focus 1) and run the whole gate

**Files:**
- Create: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/CompensationWithParallelTest.java`
- Modify: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/EnginePostgresParallelSagaIT.java` (one test + fixture)

**Interfaces:**
- Consumes: Task 1's counter invariant, Task 3's `compensate()` pre-check, Task 4's bounded branches. No production code is expected to change; if a step below fails, the sequence arithmetic in Tasks 1–4 is wrong — stop, report, and fix there, not here.

The compensation is registered with `addCompensation(String, Runnable)` rather than `@Compensate` because a compensation `Runnable` is the only path on which workflow-author code legitimately runs on the workflow thread and may call `parallel()`; a `@Compensate` method body is activity code (I/O), where `parallel()` would be a determinism violation.

- [ ] **Step 1: Write the unit pin**

`CompensationWithParallelTest extends ParallelSagaTestSupport` (Task 1). `ctx` has `ops`, so `WorkflowContext.current().parallel(...)` inside the compensation runs on the main context — which is what sequential compensation uses (`SagaManager.java:281`). The forward body is a private helper so the replay half re-registers the identical compensation:

```java
private void forwardPath(AtomicInteger fanOuts) {          // called inside inScope(...)
    ops.currentTime();                                      // forward step at seq 1
    ops.addCompensation("undoFanOut", () -> {               // pushes onto the DWO-shared `stack`
        fanOuts.incrementAndGet();
        WorkflowContext.current().parallel(List.of(
                () -> WorkflowContext.current().randomUUID(),
                () -> WorkflowContext.current().randomUUID()));
    });
}

@Test
@DisplayName("a compensation that fans out with parallel() lands in the entry's own block, never in a forward slot, and runs once across a replay")
void compensationThatForks_runsOnceInItsOwnBlock() {
    var fanOuts = new AtomicInteger();
    inScope(() -> forwardPath(fanOuts));
    inScope(() -> sagaManager.compensate(ctx, instance, stack, false));   // compensate() drains `stack`

    assertEquals(1, fanOuts.get());
    assertEquals(List.of(
            "1:SIDE_EFFECT:$maestro:currentTime",
            "2:COMPENSATION_STARTED:$maestro:compensation",
            "3000:COMPENSATION_STEP_COMPLETED:undoFanOut",
            "3001:SIDE_EFFECT:$maestro:parallel",
            "3002001:SIDE_EFFECT:$maestro:randomUUID",
            "3003001:SIDE_EFFECT:$maestro:randomUUID",
            "4001:COMPENSATION_COMPLETED:$maestro:compensation"),
            eventLogSortedBySequence());

    // Replay on a fresh context: same body re-registers the same entry; nothing re-invoked, nothing appended.
    var before = eventLogSortedBySequence();
    ctx = newContext(0, true, ops);
    inScope(() -> forwardPath(fanOuts));                    // currentTime() replays seq 1; addCompensation re-populates `stack`
    inScope(() -> sagaManager.compensate(ctx, instance, stack, false));
    assertEquals(1, fanOuts.get(), "the durable COMPENSATION_STEP_COMPLETED at 3000 skips the entry");
    assertEquals(before, eventLogSortedBySequence());
}
```

Sequence derivation: anchor `2` → entry block `2*1000+1000 = 3000`; inside the action the fork consumes `3001`; branch bases `3001*1000 + 1000 = 3_002_000` and `3_003_000`; after the join the counter is `3001*1000 + 3000 = 3_004_000`; `executeSequential` then rebases to `2*1000 + 2000 = 4000`; `COMPENSATION_COMPLETED` at `4001`. Nothing is written at or below `1` other than the forward step itself.

- [ ] **Step 2: Run it**

Run: `./gradlew :maestro-core:test --tests 'io.b2mash.maestro.core.engine.CompensationWithParallelTest' --console=plain`
Expected: `BUILD SUCCESSFUL`, 1 passed. If the event list differs, the mismatch names which task's arithmetic is wrong (a `3000`-prefixed slot missing → Task 3's `branchBase`; an extra `SIDE_EFFECT` on replay → Task 1's `finally` under replay).

- [ ] **Step 3: Write and run the Postgres IT**

Add to `EnginePostgresParallelSagaIT` a test-local `FanOutCompensationWorkflow` (`@ActivityStub ChainActivities chain; @Saga run(input) { chain.stepOne(input); ctx.addCompensation("undoFanOut", () -> WorkflowContext.current().parallel(List.of(() -> chain.stepTwo(input), () -> chain.stepThree(input)))); throw new IllegalStateException("boom"); }`), registered with `RecordingChainActivities(recorder)`:

```java
@Test
@DisplayName("a compensation that fans out runs each branch once, in the entry's own block, past every forward slot")
void compensationThatForks_runsOnceInItsOwnBlock() {
    assertEquals(WorkflowStatus.FAILED, handle.awaitTerminal(Duration.ofSeconds(30)));
    assertEquals(1, recorder.count("stepOne")); assertEquals(1, recorder.count("stepTwo")); assertEquals(1, recorder.count("stepThree"));
    assertEquals(List.of(
            "1:ACTIVITY_COMPLETED:chain.stepOne",
            "2:COMPENSATION_STARTED:$maestro:compensation",
            "3000:COMPENSATION_STEP_COMPLETED:undoFanOut",
            "3001:SIDE_EFFECT:$maestro:parallel",
            "3002001:ACTIVITY_COMPLETED:chain.stepTwo",
            "3003001:ACTIVITY_COMPLETED:chain.stepThree",
            "4001:COMPENSATION_COMPLETED:$maestro:compensation",
            "4002:WORKFLOW_FAILED:null"),
            describe(handle));
    assertEquals(List.of(), observer.standDowns());
}
```

Run: `./gradlew :maestro-integration-tests:test --tests 'io.b2mash.maestro.integration.engine.EnginePostgresParallelSagaIT' --console=plain`
Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 4: Whole-plan gate**

Run: `./gradlew build --console=plain`
Expected: `BUILD SUCCESSFUL` (unit + starter + integration; Docker required).
Run: `./gradlew :maestro-core:dependencies --configuration compileClasspath | grep -c springframework`
Expected: `0`.

- [ ] **Step 5: Commit**

```bash
git add maestro-core/src/test/java/io/b2mash/maestro/core/engine/CompensationWithParallelTest.java \
        maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/EnginePostgresParallelSagaIT.java
git commit -m "test(core): pin parallel() inside a saga compensation against fakes and real Postgres (Review Focus 1)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```
