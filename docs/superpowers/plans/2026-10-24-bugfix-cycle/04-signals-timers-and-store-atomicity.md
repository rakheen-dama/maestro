# Signals, Timers and Store Atomicity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `awaitSignal(name, timeout)` deadlines durable and clock-testable, write a workflow's terminal status and terminal event in one transaction, make the Postgres transport honour its redelivery contract when a status write fails, and stop the signal table growing without bound.

**Architecture:** Four independent correctness fixes, each landing as an SPI/engine change plus a unit pin against the in-memory fakes and a real-Postgres pin. The `awaitSignal` deadline becomes a `WorkflowTimer` row plus a memoized `TIMER_SCHEDULED` event (the same two-slot shape `sleep()` already uses), fired by the timer poller like any timer, so `TestWorkflowEnvironment.advanceTime()` drives it. The terminal write collapses into one new `WorkflowStore.completeInstance` call that is a single JDBC transaction. The Postgres transport counts an attempt at *claim* time and retires stale claims that have no attempt budget left instead of re-invoking the handler. Signal hygiene is a store-assigned `received_at`, a framework-free `SignalJanitor` in `maestro-core`, and a synchronous Valkey subscribe.

**Tech Stack:** Java 25, Gradle 9 Kotlin DSL, JUnit 5, Awaitility, Testcontainers 2 (Postgres 16, Valkey), Flyway 11, Lettuce, Jackson 3 (`tools.jackson`), JSpecify.

**Spec:** [`00-spec-independent-assessment.md`](00-spec-independent-assessment.md) §3a rows C12, C13, C14 and §4 row F4; `docs/open-issues.md` §§ Issue 26 (`:2225`) and Issue 27 (`:2341`). All `file:line` citations are as of `main` @ 4eb28e9 — re-read the cited region before editing.

## Global Constraints

See [`README.md`](README.md) § Global Constraints. Additionally for this plan:

- New Flyway migration for the store band is `V5` (`maestro-store-postgres/src/main/resources/db/migration/`); no migration is needed in the messaging band (Issue 27 reuses the existing `attempts` column).
- Both new `WorkflowStore` methods are **abstract** (no `default` body). Pre-1.0: a non-atomic default would let a third-party store silently keep the Issue 26 bug; a compile error is the honest signal. Every `implements WorkflowStore` in the repo is enumerated in Task 1 Step 3 and Task 5 Step 7 and must be updated in the same commit.
- The `TIMER_SCHEDULED` event type is reused for the `awaitSignal` deadline memo (step name `$maestro:awaitSignal:<signalName>`); no new `EventType` constant.
- The sealed `MaestroControlFlowError` `permits` clause is not touched by this plan.

## Review Focus

Inputs the spec implies but no existing test exercises; each is pinned in the owning task.

1. **Signal persisted in the same instant the `awaitSignal` deadline timer fires** (README Review Focus 2) — exactly one of `SIGNAL_RECEIVED` / `SIGNAL_TIMEOUT` is recorded at the outcome slot, never both, and the losing signal row is never discarded. Pinned on real Postgres in Task 3 (`AwaitSignalTimeoutRaceIT`), in both interleavings.
2. **`awaitSignal` with a zero or negative timeout** — must still consume an already-arrived signal, otherwise time out immediately with a memoized `SIGNAL_TIMEOUT` and **no timer row** (a `fireAt` in the past would be a junk row fired on the next poll). Task 2 `awaitSignalZeroTimeout_timesOutWithoutATimerRow`.
3. **`cancelTimer` on an `await-signal-*` row** — the await ends with `SignalTimeoutException` on the next wake (the row is the arbiter; a non-`PENDING` row means the deadline is spent). Task 2 `cancelledAwaitTimer_timesOutTheAwait`.
4. **Janitor and a `FAILED` instance older than the retention** — its unconsumed signals must survive, because `retryWorkflow` may still consume them (Issue 19). Task 5 `deleteUnconsumedSignals_keepsSignalsOfFailedInstances`.
5. **`completeInstance` when the terminal slot is already occupied** (another runner finalised first) — nothing is written, `DuplicateEventException` propagates, the executor stands down. Task 1 `completeInstance_duplicateTerminalEvent_writesNothing` (in-memory and Postgres).

---

### Task 1: Atomic terminal write — `WorkflowStore.completeInstance` (Issue 26 / C13)

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/spi/WorkflowStore.java:99-115` (add method after `updateInstance`)
- Modify: `maestro-store-jdbc/src/main/java/io/b2mash/maestro/store/jdbc/AbstractJdbcWorkflowStore.java:218-297` (refactor + new method; transaction pattern from `createInstance` `:170-191`)
- Modify: `maestro-test/src/main/java/io/b2mash/maestro/test/InMemoryWorkflowStore.java:84-155`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java:1425-1438`, `:1731-1739`, `:1790-1830`, `:1899-1915`
- Modify (add the new method; delegating or stub implementations): `maestro-core/src/test/java/io/b2mash/maestro/core/TestTerminalWaitTest.java:116`, `maestro-core/src/test/java/io/b2mash/maestro/core/saga/SagaManagerTerminateRaceTest.java:286,417`, `maestro-core/src/test/java/io/b2mash/maestro/core/saga/SagaManagerTest.java:510`, `maestro-core/src/test/java/io/b2mash/maestro/core/engine/ActivityProxyFactoryTest.java:320`, `.../engine/RecoveryPassIsolationTest.java:182`, `.../engine/ActivityInvocationHandlerTest.java:562`, `.../engine/WorkflowExecutorDuplicateEventStandDownTest.java:215`, `.../engine/TimerPollerTest.java:224`, `.../engine/WorkflowExecutorQueryTest.java:370`, `.../engine/SignalManagerTest.java:815`, `.../engine/WorkflowExecutorTest.java:1385`, `.../engine/WorkflowExecutorLifecycleEventPublishingTest.java:427`, `.../engine/TimerManagerTest.java:94`, `.../engine/VersionedInMemoryStore.java`, `.../engine/WorkflowExecutorTerminalTransitionTest.java:183`, `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/EnginePostgresParallelIT.java:268`, `.../engine/EnginePostgresSignalIT.java:414`, `maestro-spring-boot-starter/src/test/java/io/b2mash/maestro/spring/health/DelegatingWorkflowStore.java:20`
- Modify: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/support/TerminalEventDelayStore.java` (delay `completeInstance` instead of `appendEvent`)
- Modify: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/TerminalEventRaceIT.java:64-88`
- Create: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/support/FaultInjectingDataSource.java`
- Create: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/TerminalWriteAtomicityIT.java`
- Test: `maestro-test/src/test/java/io/b2mash/maestro/test/InMemoryWorkflowStoreTest.java`, `maestro-store-postgres/src/test/java/io/b2mash/maestro/store/postgres/PostgresWorkflowStoreTest.java`, `maestro-core/src/test/java/io/b2mash/maestro/core/engine/WorkflowExecutorTerminalTransitionTest.java`
- Docs: `docs/testing.md:195`, `docs/testing.md:539`, `docs/release-notes.md` (`## Unreleased` + the "`maestro-test` waits for the terminal *event*" entry at `:350-372`), `docs/open-issues.md:278` and `:2225`, `CLAUDE.md` "Three SPIs" block

**Interfaces:**
- Produces (SPI): `boolean completeInstance(WorkflowInstance terminal, WorkflowEvent terminalEvent)` on `WorkflowStore`. Javadoc must state: CAS exactly like `updateInstance` (row written iff stored `version == terminal.version() - 1`); the event is appended **in the same transaction**; returns `false` and writes **nothing** when the CAS fails; throws `WorkflowNotFoundException` if no row exists; throws `DuplicateEventException` (nothing written) if an event already occupies `terminalEvent.sequenceNumber()`; throws `IllegalArgumentException` if `!terminal.status().isTerminal()`, if `terminalEvent.eventType()` is not `WORKFLOW_COMPLETED`/`WORKFLOW_FAILED`, or if `terminalEvent.workflowInstanceId()` differs from `terminal.id()`. State the Issue 26 safety argument in one sentence: a reader can never observe a terminal status without its terminal event, nor the event without the status, so `getRecoverableInstances()` can never re-enter a finalised workflow.
- Produces (engine): `private boolean transitionToTerminal(WorkflowContext ctx, WorkflowInstance fallback, WorkflowStatus status, EventType terminalType, @Nullable JsonNode output)` in `WorkflowExecutor` — the only terminal write path.
- Produces (IT support): `FaultInjectingDataSource implements DataSource` wrapping a `PGSimpleDataSource`; `void failEventInsertsInsideTransactions()`, `void disarm()`, `int injectedFailures()`. Returns a `java.lang.reflect.Proxy` over `Connection` whose `prepareStatement(String, ...)` throws `SQLException("injected: event insert")` when armed, the SQL contains `INSERT INTO maestro_workflow_event`, and `delegate.getAutoCommit()` is `false` (only `completeInstance` inserts an event inside an explicit transaction; `appendEvent` goes through the autocommit `update()` helper at `AbstractJdbcWorkflowStore.java:779-787`). Task 7 uses a sibling class in the messaging module.

- [ ] **Step 1: Write the failing in-memory store tests** in `InMemoryWorkflowStoreTest`

```java
@Test
void completeInstance_writesStatusAndEventTogether() {
    var created = store.createInstance(instance("wf-1", WorkflowStatus.RUNNING, 0));
    var terminal = created.toBuilder().status(WorkflowStatus.COMPLETED).version(1).build();
    var event = event(created.id(), 5, EventType.WORKFLOW_COMPLETED);
    assertTrue(store.completeInstance(terminal, event));
    assertEquals(WorkflowStatus.COMPLETED, store.getInstance("wf-1").orElseThrow().status());
    assertEquals(1, store.getInstance("wf-1").orElseThrow().version());
    assertEquals(EventType.WORKFLOW_COMPLETED, store.getEventBySequence(created.id(), 5).orElseThrow().eventType());
}

@Test
void completeInstance_staleVersion_writesNothing() {
    var created = store.createInstance(instance("wf-1", WorkflowStatus.RUNNING, 0));
    var stale = created.toBuilder().status(WorkflowStatus.COMPLETED).version(5).build(); // expects stored 4
    assertFalse(store.completeInstance(stale, event(created.id(), 5, EventType.WORKFLOW_COMPLETED)));
    assertEquals(WorkflowStatus.RUNNING, store.getInstance("wf-1").orElseThrow().status());
    assertTrue(store.getEventBySequence(created.id(), 5).isEmpty());
}

@Test
void completeInstance_duplicateTerminalEvent_writesNothing() {
    var created = store.createInstance(instance("wf-1", WorkflowStatus.RUNNING, 0));
    store.appendEvent(event(created.id(), 5, EventType.WORKFLOW_COMPLETED)); // another runner got there first
    var terminal = created.toBuilder().status(WorkflowStatus.FAILED).version(1).build();
    assertThrows(DuplicateEventException.class,
            () -> store.completeInstance(terminal, event(created.id(), 5, EventType.WORKFLOW_FAILED)));
    assertEquals(WorkflowStatus.RUNNING, store.getInstance("wf-1").orElseThrow().status());
    assertEquals(0, store.getInstance("wf-1").orElseThrow().version());
}

@Test
void completeInstance_rejectsNonTerminalStatus() {
    var created = store.createInstance(instance("wf-1", WorkflowStatus.RUNNING, 0));
    var notTerminal = created.toBuilder().status(WorkflowStatus.WAITING_SIGNAL).version(1).build();
    assertThrows(IllegalArgumentException.class,
            () -> store.completeInstance(notTerminal, event(created.id(), 5, EventType.WORKFLOW_COMPLETED)));
}
```

Use the file's existing instance/event builder helpers (or add `instance(...)`/`event(...)` private helpers mirroring them).

- [ ] **Step 2: Run to verify compile failure**

Run: `./gradlew :maestro-test:test --tests 'io.b2mash.maestro.test.InMemoryWorkflowStoreTest' --console=plain`
Expected: `BUILD FAILED` — `cannot find symbol: method completeInstance`.

- [ ] **Step 3: Add the SPI method and every implementation**

- `WorkflowStore.java`: add `boolean completeInstance(WorkflowInstance terminal, WorkflowEvent terminalEvent);` with the Javadoc from Interfaces. Update the class Javadoc "Implementation Requirements" list (`:24-33`) with one bullet: "`completeInstance` must be atomic".
- `InMemoryWorkflowStore.java`: implement under `instanceLocks.get(workflowId)` (the lock `updateInstance` uses at `:85-108`): validate arguments; version check → `return false`; check `events.get(id).containsKey(seq)` → throw `DuplicateEventException` **before** touching the instance; then `instances.put` and `put(event)`.
- `AbstractJdbcWorkflowStore.java`: extract `private int updateInstanceRow(Connection conn, WorkflowInstance instance) throws SQLException` from `updateInstance` (`:218-257`) and `private void insertEvent(Connection conn, WorkflowEvent event) throws SQLException` from `appendEvent` (`:258-297`), keep both public methods behaving exactly as today, and implement `completeInstance` as one connection with `setAutoCommit(false)`: `updateInstanceRow` → if `0` rows, `rollback`, then `resolveUpdateFailure`'s not-found probe (`:597-607`) decides between `WorkflowNotFoundException` and `return false`; else `insertEvent`, `commit`, `return true`; a `UNIQUE_VIOLATION` from the insert → `rollback` → `DuplicateEventException`; any other `SQLException` → `rollback` → `UncheckedSqlException`.
- Every test store listed under **Files**: delegating stores forward to their delegate; stub stores that already implement `updateInstance` + `appendEvent` compose the two under their existing lock (they are single-threaded fixtures; note it in a one-line comment). `VersionedInMemoryStore` and `WorkflowExecutorTerminalTransitionTest.VersionedStore` must route their existing conflict hooks (`completeOutOfBandOnNextUpdate`, `bumpVersionOutOfBandOnNextUpdate`, `conflictForNextUpdates`, `collideOnType`) through `completeInstance` so the existing terminal-transition tests keep exercising them.

- [ ] **Step 4: Run the in-memory tests and the whole core suite**

Run: `./gradlew :maestro-test:test :maestro-core:test --console=plain`
Expected: `BUILD SUCCESSFUL` (core tests still pass — the executor is unchanged so far).

- [ ] **Step 5: Write the failing Postgres store tests** in `PostgresWorkflowStoreTest` (new `@Nested class CompleteInstanceTests`), same four cases as Step 1 plus:

```java
@Test
void completeInstance_rollsBackTheStatusWhenTheEventInsertFails() {
    // An event already at the terminal seq makes the INSERT violate the unique index inside the txn.
    var created = store.createInstance(newInstance("wf-atomic"));
    store.appendEvent(newEvent(created.id(), 3, EventType.ACTIVITY_COMPLETED));
    var terminal = created.toBuilder().status(WorkflowStatus.COMPLETED).version(1).build();
    assertThrows(DuplicateEventException.class,
            () -> store.completeInstance(terminal, newEvent(created.id(), 3, EventType.WORKFLOW_COMPLETED)));
    var row = store.getInstance("wf-atomic").orElseThrow();
    assertEquals(WorkflowStatus.RUNNING, row.status());
    assertEquals(0, row.version(), "the UPDATE must have been rolled back with the failed INSERT");
}
```

Run: `./gradlew :maestro-store-postgres:test --tests '*PostgresWorkflowStoreTest*CompleteInstance*' --console=plain`
Expected: PASS already if Step 3 is right; if `completeInstance_rollsBackTheStatusWhenTheEventInsertFails` fails with `version == 1`, the transaction is not wrapping both statements — fix before continuing.

- [ ] **Step 6: Write the failing executor unit test** in `WorkflowExecutorTerminalTransitionTest`

```java
@Test
void completeInstanceFailure_leavesTheWorkflowRecoverable_andEmitsRunAbandoned() {
    store.failAllCompleteInstanceCalls(new UncheckedSqlException("store down", new SQLException()));
    var observer = new RecordingObserver();          // the test module's existing recording observer, or a minimal one
    var executor = newExecutor(store, observer);
    executor.startWorkflow("wf-1", TrivialWorkflow.class ...);   // mirror the file's existing start helper
    awaitRunFinished(executor, "wf-1");
    var row = store.getInstance("wf-1").orElseThrow();
    assertEquals(WorkflowStatus.RUNNING, row.status());
    assertTrue(store.getEvents(row.id()).stream().noneMatch(e -> e.eventType() == EventType.WORKFLOW_COMPLETED
            || e.eventType() == EventType.WORKFLOW_FAILED));
    assertEquals(List.of(AbandonReason.TERMINAL_WRITE_FAILED), observer.abandonReasons());
    assertEquals(0, observer.completedCount());
}
```

Add to `VersionedStore`: `void failAllCompleteInstanceCalls(RuntimeException failure)`, `int completeInstanceCalls()`, and `int terminalAppendEventCalls()` (count of `appendEvent` calls whose type is `WORKFLOW_COMPLETED`/`WORKFLOW_FAILED`). Also add to the same test class:

```java
@Test
void terminalEventAndStatus_areWrittenByOneStoreCall() {
    var executor = newExecutor(store, EngineObserver.NOOP);
    executor.startWorkflow("wf-1", TrivialWorkflow.class ...);
    awaitTerminal("wf-1");
    assertEquals(1, store.completeInstanceCalls());
    assertEquals(0, store.terminalAppendEventCalls(), "WORKFLOW_COMPLETED must never go through appendEvent");
}
```

Run: `./gradlew :maestro-core:test --tests '*WorkflowExecutorTerminalTransitionTest*' --console=plain`
Expected: FAIL — `terminalAppendEventCalls() == 1` and, for the first test, the executor records FAILED-via-appendEvent paths or observer counts differ.

- [ ] **Step 7: Collapse the executor's terminal write**

In `WorkflowExecutor.java`: change `transitionToTerminal` (`:1790-1830`) to the signature in Interfaces. Allocate `var seq = ctx.nextSequence();` **once before** the retry loop; each attempt builds `updated` as today plus `.eventSequence(seq)` and a `WorkflowEvent(UUID.randomUUID(), ctx.workflowInstanceId(), seq, terminalType, null, output, now)`; call `store.completeInstance(updated, event)`; `true` → return `true`; `false` → the existing retry/give-up logic (replace the `catch (OptimisticLockException)` with the boolean). Delete the `appendEvent(ctx, EventType.WORKFLOW_COMPLETED, ...)` call at `:1438` and `appendEvent(ctx, EventType.WORKFLOW_FAILED, ...)` at `:1739`; pass `EventType.WORKFLOW_COMPLETED` / `EventType.WORKFLOW_FAILED` into `transitionToTerminal` at `:1425` and `:1731`. Delete the now-unused `appendEvent` helper at `:1899-1915` (its only two callers were those lines). Update the Javadoc at `:1776-1789` to describe the single atomic write. A `DuplicateEventException` from `completeInstance` propagates: on the COMPLETED path it reaches the existing stand-down arm at `:1479`; on the FAILED path it lands in `catch (Exception updateError)` at `:1746` (writes nothing, emits `TERMINAL_WRITE_FAILED`) — acceptable, note it in that catch's comment.

- [ ] **Step 8: Run core**

Run: `./gradlew :maestro-core:test --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Rewrite the Postgres race IT and add the fault-injection IT**

- `TerminalEventDelayStore`: move the delay into `completeInstance` (before delegating), rename the counter accessor to `delayedCompletions()`, delete the terminal-type check in `appendEvent`. Update its Javadoc: it now stretches the atomic write so a status poll that *does* see a terminal status proves the event landed with it.
- `TerminalEventRaceIT`: delete `statusOnlyWait_seesTheGap` (`:64-88`); add

```java
@Test
@DisplayName("a status-only poll never observes a terminal status without its terminal event")
void statusOnlyWait_neverSeesTheGap() {
    var handle = startChain("terminal-atomic");
    await().atMost(BOUND).pollInterval(Duration.ofMillis(20))
            .until(() -> store.getInstance(handle.workflowId()).map(i -> i.status().isTerminal()).orElse(false));
    assertTrue(hasTerminalEvent(handle.instanceId()),
            "status is terminal, so the event must already be durable — Issue 26 is closed at the source");
    assertEquals(1, delayingStore.delayedCompletions());
}
```

  Keep the other three tests unchanged.
- Create `FaultInjectingDataSource` per Interfaces.
- Create `TerminalWriteAtomicityIT extends PostgresIntegrationSupport` (`@Tag("integration")`):

```java
@Test
@DisplayName("Issue 26: a failed terminal write leaves the instance non-terminal and recoverable; recovery then finalises it")
void failedTerminalWrite_leavesInstanceRecoverable_thenRecoveryCompletesIt() throws Exception {
    var faulty = new FaultInjectingDataSource(newDataSource());
    var faultyStore = new PostgresWorkflowStore(faulty, objectMapper);
    faulty.failEventInsertsInsideTransactions();
    try (var harness = MaestroEngineHarness.builder(faultyStore, objectMapper).build()) {
        harness.registerActivities(CountingActivities.ChainActivities.class, new CountingActivities.RecordingChainActivities(recorder));
        harness.registerWorkflow(new TestWorkflows.ChainWorkflow());
        var handle = harness.start(MaestroEngineHarness.uniqueWorkflowId("atomic"), TestWorkflows.ChainWorkflow.class, "seed");

        await().atMost(BOUND).until(() -> faulty.injectedFailures() >= 1);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .until(() -> !store.getInstance(handle.workflowId()).orElseThrow().status().isTerminal());
        assertTrue(store.getEvents(handle.instanceId()).stream()
                .noneMatch(e -> e.eventType() == EventType.WORKFLOW_COMPLETED || e.eventType() == EventType.WORKFLOW_FAILED));
        assertTrue(store.getRecoverableInstances().stream().anyMatch(i -> i.workflowId().equals(handle.workflowId())));

        faulty.disarm();
        await().atMost(BOUND).until(() -> !harness.executor().isRunning(handle.workflowId()));
        assertEquals(1, harness.recover());
        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));
        assertEquals(1, handle.events().stream().filter(e -> e.eventType() == EventType.WORKFLOW_COMPLETED).count());
        assertEquals(3, handle.events().stream().filter(e -> e.eventType() == EventType.ACTIVITY_COMPLETED).count(),
                "the three activity memos were replayed, not re-executed, by the recovering run");
    }
}
```

  (`recorder` is the per-test `CountingActivities` recorder — mirror `EnginePostgresSignalIT.newRecorder()`; additionally assert through it that each chain step executed exactly once across both runs.)

- [ ] **Step 10: Run the integration tests**

Run: `./gradlew :maestro-integration-tests:test --tests '*TerminalEventRaceIT*' --tests '*TerminalWriteAtomicityIT*' --console=plain`
Expected: `BUILD SUCCESSFUL`. Then run them three times with `--rerun-tasks` (open-issues §3 verification standard).

- [ ] **Step 11: Docs**

- `docs/testing.md:195`: replace the paragraph with: the engine writes the terminal status and terminal event in one transaction (`WorkflowStore.completeInstance`), so a terminal status always comes with its event; `getResult`/`awaitCompletion` still wait for the event and remain the recommended wait. `docs/testing.md:539`: rewrite "Finishing is **two** writes" to "Finishing is one atomic write" and keep the recommendation to use the handle's waits.
- `docs/release-notes.md`: under `## Unreleased` add "### Fixed — terminal status and terminal event are one transaction (Issue 26)" naming `WorkflowStore.completeInstance` as a **breaking SPI addition** for custom stores (abstract, no default). Append to the existing "`maestro-test` waits for the terminal *event*" entry (`:350-372`): "The underlying two-write gap is now closed at the source — see Issue 26 above; the event wait remains correct and unchanged."
- `docs/open-issues.md`: row `:278` → `**Resolved**`; section `:2225` → `> **Resolved.** <one paragraph: the SPI method, the atomic JDBC transaction, the pins `TerminalWriteAtomicityIT`, `TerminalEventRaceIT.statusOnlyWait_neverSeesTheGap`, `PostgresWorkflowStoreTest.CompleteInstanceTests`>` plus the commit hash after committing.
- `CLAUDE.md` "Three SPIs" `WorkflowStore` block: add `boolean completeInstance(WorkflowInstance terminal, WorkflowEvent terminalEvent); // atomic terminal status + event; CAS like updateInstance`.

- [ ] **Step 12: Commit**

```bash
git add -A
git commit -m "fix(store): write terminal status and terminal event in one transaction (Issue 26)

Adds WorkflowStore.completeInstance (abstract; CAS + event append in one txn),
collapses WorkflowExecutor's two terminal writes into it, and pins the
atomicity on the in-memory store and real Postgres (fault-injected insert).

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: Durable `awaitSignal` deadline in the engine (C12 / F4)

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/SignalManager.java:282-419` (awaitSignal), `:516-552` (consumeSignal), `:554-559` (add `AwaitSignalDetail` next to `SignalTimeoutDetail`), fields `:66-80`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java:572-583` (fireTimer), `:623-634` (cancelTimer)
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/model/EventType.java:30` (Javadoc of `TIMER_SCHEDULED`), `maestro-core/src/main/java/io/b2mash/maestro/core/spi/WorkflowStore.java:279-302` (findTimer Javadoc: timer-id examples), `:166-180` (deleteFailureEvents: retry note), `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowOperations.java:64-75` (awaitSignal Javadoc)
- Test: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/SignalManagerTest.java`, `maestro-test/src/test/java/io/b2mash/maestro/test/TestWorkflowEnvironmentTest.java`
- Docs: `docs/concepts.md:279`, `docs/testing.md:239-244` (advanceTime section), `docs/release-notes.md`

**Interfaces:**
- Consumes: `WorkflowStore.saveTimer/findTimer/markTimerFired/markTimerCancelled` (unchanged), `ParkingLot.parkWithTimeout/unpark` (unchanged).
- Produces: `record AwaitSignalDetail(String signalName, String timerId, String timeout, String deadline)` (package-private in `SignalManager`; `timeout`/`deadline` are ISO-8601 strings, like `SignalTimeoutDetail`/`TimerDetail`). Payload of the `TIMER_SCHEDULED` memo an await writes.
- Produces: `void onTimerTransition(String workflowId, String timerId)` (package-private on `SignalManager`): if `awaitWakeKeys` (a `ConcurrentHashMap<String, String>`, key `workflowId + ":" + timerId`, value the signal park key) holds an entry, `parkingLot.unpark(parkKey, null)`; otherwise a no-op. `WorkflowExecutor.fireTimer` and `cancelTimer` call it after a successful CAS, in addition to the existing `":timer:"` unpark. Task 3 and Task 4 rely on this bridge; the test environment's `advanceTime()` needs no change because it already goes through `executor.fireTimer`.
- Timer id for an await is `"await-signal-" + seq` where `seq` is the sequence the await allocated.

**Event layout (the decision implementers cannot infer):**

| Path | Slot `seq` | Slot `seq+1` | Timer row |
|---|---|---|---|
| Signal already unconsumed at entry (today's fast path, unchanged) | `SIGNAL_RECEIVED` | — | none |
| `timeout <= 0` and no signal | `SIGNAL_TIMEOUT` | — | none |
| Had to park | `TIMER_SCHEDULED`, step `$maestro:awaitSignal:<name>`, payload `AwaitSignalDetail` | `SIGNAL_RECEIVED` **or** `SIGNAL_TIMEOUT` | `await-signal-<seq>`, `fireAt = deadline` |

**Plan 02 interaction (landed before this plan):** every read of slot `seq` and slot `seq+1` below goes through Plan 02's `ReplayGuard.requireMatch(...)` with the expected step name (`$maestro:awaitSignal:<name>`) and the set of event types legal in that slot (slot `seq`: `SIGNAL_RECEIVED`, `SIGNAL_TIMEOUT`, `TIMER_SCHEDULED`; slot `seq+1`: `SIGNAL_RECEIVED`, `SIGNAL_TIMEOUT`). Plan 02 Task 3 wired the guard into today's single-slot `awaitSignal`; this task moves those calls to the two-slot layout rather than dropping them, and `ReplayDriftStandDownTest`/`ReplayDriftStandDownIT` must stay green.

Replay reads slot `seq` first: `SIGNAL_RECEIVED` → return; `SIGNAL_TIMEOUT` → throw; `TIMER_SCHEDULED` → allocate `seq+1`, read it: `SIGNAL_RECEIVED` → return, `SIGNAL_TIMEOUT` → throw, absent → resume the durable wait with `deadline = Instant.parse(detail.deadline())` and `timerDbId = store.findTimer(instanceId, detail.timerId()).map(WorkflowTimer::id)` — never `Instant.now()`.

**The one rule of the wait loop** (live and resumed share it; this is the algorithm the signature does not determine):

1. Read unconsumed signals (via `ParkProbe`, as today `:398-400`). If any → `consumeSignal` at the outcome slot, best-effort `store.markTimerCancelled(timerDbId)` (result ignored), return.
2. Read the timer row status (via `ParkProbe`). If it is not `PENDING` **or** `!Instant.now().isBefore(deadline)`: if still `PENDING`, `store.markTimerFired(timerDbId)` (self-fire CAS; the result does not matter — the row is non-`PENDING` either way now); re-read unconsumed signals once more; any → consume as in 1; none → append `SIGNAL_TIMEOUT` at the outcome slot, throw `SignalTimeoutException`.
3. Otherwise `standDownIfTerminated`, park on the signal key for `min(deadline - now, wakeRecheckInterval)`, loop.

A visible signal always wins while the outcome slot is open; a non-`PENDING` row with no signal always means timeout. Retry after a timeout failure (Issue 19) keeps this rule: `deleteFailureEvents` removes the `SIGNAL_TIMEOUT` memo, replay finds the row `FIRED`, consumes a since-arrived signal if there is one, otherwise times out again immediately — the memoized deadline governs, exactly as `collectSignals`' memoized `currentTime()` deadline already does. Document that in `WorkflowStore.deleteFailureEvents` Javadoc (`:166-180`, one sentence).

- [ ] **Step 1: Write the failing unit tests** in `SignalManagerTest` (a fresh context from `createContext(instanceId, "order-1", 0, ...)` allocates `seq == 1`, so the memo lands at 1, the outcome at 2, and the timer id is `"await-signal-1"`). Add three private helpers: `Thread awaitInBackground(WorkflowContext ctx, String name, Duration timeout, AtomicReference<Object> outcome)` runs `awaitSignal` under the scoped value on a new thread and stores the result or the thrown exception; `JsonNode awaitDetail(String signalName, String timerId, Instant deadline)` serialises `new SignalManager.AwaitSignalDetail(signalName, timerId, Duration.ofHours(1).toString(), deadline.toString())`; `UUID seedAwaitMemo(UUID instanceId, int seq, String signalName, Instant deadline, TimerStatus rowStatus)` appends `TIMER_SCHEDULED` at `seq` with step `"$maestro:awaitSignal:" + signalName` and payload `awaitDetail(...)`, saves a `WorkflowTimer` row (`"await-signal-" + seq`, `fireAt = deadline`, `rowStatus`) and returns the row id.

```java
@Test
void awaitSignalParks_createsDurableTimerRowAndMemoizesDeadline() {
    var instanceId = UUID.randomUUID(); createInstance("order-1", instanceId);
    var ctx = createContext(instanceId, "order-1", 0, false);
    var before = Instant.now();
    var worker = awaitInBackground(ctx, "payment.result", Duration.ofHours(1), new AtomicReference<>());
    await().atMost(Duration.ofSeconds(5)).until(() -> store.findTimer(instanceId, "await-signal-1").isPresent());
    var row = store.findTimer(instanceId, "await-signal-1").orElseThrow();
    assertEquals(TimerStatus.PENDING, row.status());
    assertFalse(row.fireAt().isBefore(before.plus(Duration.ofHours(1)).minusSeconds(5)));
    var memo = store.getEventBySequence(instanceId, 1).orElseThrow();
    assertEquals(EventType.TIMER_SCHEDULED, memo.eventType());
    assertEquals("$maestro:awaitSignal:payment.result", memo.stepName());
    assertEquals("await-signal-1", memo.payload().get("timerId").stringValue());
    assertEquals(row.fireAt(), Instant.parse(memo.payload().get("deadline").stringValue()));
    assertTrue(store.getEventBySequence(instanceId, 2).isEmpty());
    worker.interrupt();
}

@Test
void awaitSignalTimeout_isDrivenByTheTimerTransition_notTheWallClock() throws Exception {
    var instanceId = UUID.randomUUID(); createInstance("order-1", instanceId);
    var ctx = createContext(instanceId, "order-1", 0, false);
    var outcome = new AtomicReference<Object>();
    var worker = awaitInBackground(ctx, "payment.result", Duration.ofHours(1), outcome);
    await().atMost(Duration.ofSeconds(5)).until(() -> store.findTimer(instanceId, "await-signal-1").isPresent());
    var row = store.findTimer(instanceId, "await-signal-1").orElseThrow();
    assertTrue(store.markTimerFired(row.id()));
    signalManager.onTimerTransition("order-1", "await-signal-1");
    worker.join(5_000);
    assertInstanceOf(SignalTimeoutException.class, outcome.get());
    assertEquals(EventType.SIGNAL_TIMEOUT, store.getEventBySequence(instanceId, 2).orElseThrow().eventType());
}

@Test
void awaitSignalReplay_resumesFromTheMemoizedDeadline_withoutASecondTimerRow() {
    var instanceId = UUID.randomUUID(); createInstance("order-1", instanceId);
    var deadline = Instant.now().plus(Duration.ofHours(1));
    var rowId = seedAwaitMemo(instanceId, 1, "payment.result", deadline, TimerStatus.PENDING); // appends TIMER_SCHEDULED at 1 + saves the row
    var ctx = createContext(instanceId, "order-1", 0, true);
    var worker = awaitInBackground(ctx, "payment.result", Duration.ofHours(1), new AtomicReference<>());
    await().atMost(Duration.ofSeconds(5)).until(() -> parkingLot.isParked("order-1:signal:payment.result"));
    assertEquals(1, store.getAllTimers().size(), "replay must reuse the durable row, never re-arm");
    assertEquals(rowId, store.getAllTimers().getFirst().id());
    worker.interrupt();
}

@Test
void awaitSignalReplay_firedRowWithoutOutcome_healsToTimeout() {
    var instanceId = UUID.randomUUID(); createInstance("order-1", instanceId);
    seedAwaitMemo(instanceId, 1, "payment.result", Instant.now().minusSeconds(1), TimerStatus.FIRED);
    var ctx = createContext(instanceId, "order-1", 0, true);
    ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() ->
            assertThrows(SignalTimeoutException.class,
                    () -> signalManager.awaitSignal(ctx, "payment.result", String.class, Duration.ofHours(1))));
    assertEquals(EventType.SIGNAL_TIMEOUT, store.getEventBySequence(instanceId, 2).orElseThrow().eventType());
}

@Test
void awaitSignalReplay_firedRowWithoutOutcome_visibleSignalWins() {
    var instanceId = UUID.randomUUID(); createInstance("order-1", instanceId);
    seedAwaitMemo(instanceId, 1, "payment.result", Instant.now().minusSeconds(1), TimerStatus.FIRED);
    signalManager.deliverSignal("order-1", "payment.result", "paid-late");
    var ctx = createContext(instanceId, "order-1", 0, true);
    ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() ->
            assertEquals("paid-late", signalManager.awaitSignal(ctx, "payment.result", String.class, Duration.ofHours(1))));
    assertEquals(EventType.SIGNAL_RECEIVED, store.getEventBySequence(instanceId, 2).orElseThrow().eventType());
    assertTrue(store.getAllSignals().getFirst().consumed());
}

@Test
void awaitSignalReplay_memoizedOutcome_needsNoTimerRow() {
    var instanceId = UUID.randomUUID(); createInstance("order-1", instanceId);
    store.appendEvent(new WorkflowEvent(UUID.randomUUID(), instanceId, 1, EventType.TIMER_SCHEDULED,
            "$maestro:awaitSignal:payment.result", awaitDetail("payment.result", "await-signal-1", Instant.EPOCH), Instant.now()));
    store.appendEvent(new WorkflowEvent(UUID.randomUUID(), instanceId, 2, EventType.SIGNAL_TIMEOUT,
            "$maestro:awaitSignal:payment.result", null, Instant.now()));
    var ctx = createContext(instanceId, "order-1", 0, true);
    ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() ->
            assertThrows(SignalTimeoutException.class,
                    () -> signalManager.awaitSignal(ctx, "payment.result", String.class, Duration.ofHours(1))));
    assertEquals(0, store.getAllTimers().size());
    assertEquals(2, ctx.currentSequence());
}

@Test
void awaitSignalZeroTimeout_timesOutWithoutATimerRow() {
    var instanceId = UUID.randomUUID(); createInstance("order-1", instanceId);
    var ctx = createContext(instanceId, "order-1", 0, false);
    ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() ->
            assertThrows(SignalTimeoutException.class,
                    () -> signalManager.awaitSignal(ctx, "payment.result", String.class, Duration.ZERO)));
    assertEquals(0, store.getAllTimers().size());
    assertEquals(EventType.SIGNAL_TIMEOUT, store.getEventBySequence(instanceId, 1).orElseThrow().eventType());
}

@Test
void cancelledAwaitTimer_timesOutTheAwait() throws Exception {
    var instanceId = UUID.randomUUID(); createInstance("order-1", instanceId);
    var ctx = createContext(instanceId, "order-1", 0, false);
    var outcome = new AtomicReference<Object>();
    var worker = awaitInBackground(ctx, "payment.result", Duration.ofHours(1), outcome);
    await().atMost(Duration.ofSeconds(5)).until(() -> store.findTimer(instanceId, "await-signal-1").isPresent());
    assertTrue(store.markTimerCancelled(store.findTimer(instanceId, "await-signal-1").orElseThrow().id()));
    signalManager.onTimerTransition("order-1", "await-signal-1");
    worker.join(5_000);
    assertInstanceOf(SignalTimeoutException.class, outcome.get());
}
```

`SignalManagerTest`'s own `InMemoryWorkflowStore` (`:815`) needs `getAllTimers()`/`getAllSignals()` accessors if it lacks them. Existing tests `awaitSignalTimeout` (100 ms, no poller — now the self-fire path) and `awaitSignalTimeoutWithLateSignal` must keep passing unchanged; `awaitSignalUnsubscribesOnTimeout` likewise.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :maestro-core:test --tests '*SignalManagerTest*' --console=plain`
Expected: `BUILD FAILED` — compile error on `onTimerTransition` / `awaitDetail`; after stubbing those, the new tests fail on `findTimer(...).isPresent()` timing out.

- [ ] **Step 3: Implement in `SignalManager` and bridge from `WorkflowExecutor`**

- Add field `private final ConcurrentHashMap<String, String> awaitWakeKeys`, the `AwaitSignalDetail` record, `onTimerTransition`.
- Restructure `awaitSignal` per the layout table: keep `:283-320` (replay of `SIGNAL_RECEIVED`/`SIGNAL_TIMEOUT` at `seq`, live fast path) and add the `TIMER_SCHEDULED` replay branch; the `timeout <= 0` branch; the schedule step (`saveTimer` **before** `appendEvent`, mirroring `DefaultWorkflowOperations.sleep` `:278-294`); then a private `<T> T awaitOutcome(WorkflowContext ctx, int outcomeSeq, String stepName, String signalName, Class<T> type, String timerId, @Nullable UUID timerDbId, Instant deadline, Duration timeout)` implementing the one rule, with `awaitWakeKeys.put/remove` and `subscribeForWake/unsubscribeForWake` around it (the subscribe/raced-recheck at `:326-336` stays where it is).
- `WorkflowExecutor.fireTimer` (`:572-583`) and `cancelTimer` (`:623-634`): inside the `if (fired)` / `if (cancelled)` branch add `signalManager.onTimerTransition(workflowId, timerId);`. Extend both Javadocs with one sentence: an `await-signal-*` timer is an `awaitSignal` deadline; firing or cancelling it ends the wait with `SignalTimeoutException`.
- `consumeSignal` (`:516-552`) is reused unchanged — it already takes the slot as a parameter.

- [ ] **Step 4: Run core**

Run: `./gradlew :maestro-core:test --console=plain`
Expected: `BUILD SUCCESSFUL`. If `DefaultWorkflowOperationsTest`/`WorkflowExecutorTest` fixtures assert exact sequence numbers after an await that parked, update those expectations (the parked await now occupies two slots) and say so in the commit body.

- [ ] **Step 5: Write the failing controllable-clock test** in `TestWorkflowEnvironmentTest`

```java
@Test
void advanceTimeFiresAwaitSignalTimeout() throws TimeoutException {
    var handle = env.startWorkflow(SevenDayApprovalWorkflow.class, "doc-1");
    waitForStatus(handle, WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(5));
    env.advanceTime(Duration.ofDays(7));
    assertEquals("timed-out", handle.getResult(String.class, Duration.ofSeconds(5)));
    assertEquals(1, handle.getEvents().stream().filter(e -> e.eventType() == EventType.SIGNAL_TIMEOUT).count());
}

@Test
void signalBeforeTheAdvancedDeadlineWins() throws TimeoutException {
    var handle = env.startWorkflow(SevenDayApprovalWorkflow.class, "doc-2");
    waitForStatus(handle, WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(5));
    env.advanceTime(Duration.ofDays(6));
    handle.signal("approval", "approved");
    assertEquals("approved", handle.getResult(String.class, Duration.ofSeconds(5)));
}
```

`SevenDayApprovalWorkflow` (new nested test workflow in that file): `awaitSignal("approval", String.class, Duration.ofDays(7))` inside `try`, `catch (SignalTimeoutException e) { return "timed-out"; }`.

Run: `./gradlew :maestro-test:test --tests '*TestWorkflowEnvironmentTest*' --console=plain`
Expected: PASS — `TestWorkflowEnvironment.fireAllDueTimers` (`:282-296`) already routes through `executor.fireTimer`. If the first test times out, the `onTimerTransition` bridge is not wired.

- [ ] **Step 6: Docs**

- `docs/concepts.md:279`: replace with: `awaitSignal(name, timeout)` creates a durable timer row (`await-signal-<seq>`) when it has to park; the timer poller fires it like any `sleep()` timer, `cancelTimer` on it ends the wait with `SignalTimeoutException`, and `TestWorkflowEnvironment.advanceTime()` fires it.
- `docs/testing.md` after `:244`: one paragraph — `advanceTime` also fires `awaitSignal` deadlines; a 7-day approval wait is a one-line `advanceTime(Duration.ofDays(7))`.
- `WorkflowOperations.awaitSignal` Javadoc (`:64-75`), `EventType.TIMER_SCHEDULED` Javadoc, `WorkflowStore.findTimer` Javadoc (`"sleep-2"` → `"sleep-2"` or `"await-signal-7"`), `WorkflowStore.deleteFailureEvents` retry sentence (see the rule above).
- `docs/release-notes.md` `## Unreleased`: "### Changed — `awaitSignal` deadlines are durable timers (C12/F4)": two-slot event layout for parked awaits, poller-fired, clock-testable; a retried timeout failure times out again unless a signal has arrived.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(core): durable awaitSignal deadlines via timer rows (C12/F4)

A parked awaitSignal now memoizes TIMER_SCHEDULED with its deadline and
persists an await-signal-<seq> timer row; the poller fires it, replay never
re-arms from Instant.now(), and TestWorkflowEnvironment.advanceTime() drives it.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Real-Postgres pins for the durable deadline, including the fire/signal race

**Files:**
- Modify: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/workflows/TestWorkflows.java` (add `PatientSignalWorkflow`)
- Create: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/EnginePostgresSignalTimeoutIT.java`
- Create: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/AwaitSignalTimeoutRaceIT.java`

**Interfaces:**
- Consumes: Task 2's `await-signal-<seq>` timer id and `onTimerTransition` bridge; `MaestroEngineHarness.fireDueTimers(int)`, `.recover()`, `.close()`, `.deliverSignal(...)`; `PostgresIntegrationSupport.newDataSource()`, `store`, `objectMapper`; `WorkflowHandle.awaitStatus/awaitTerminal/result/events/instanceId`.
- Produces: `TestWorkflows.PatientSignalWorkflow` — `public static final String SIGNAL = "approval"`; constructor `(Duration timeout)`; `@WorkflowMethod public String run(String input)` returns the signal payload, or `"timed-out"` on `SignalTimeoutException`. Its await is the first step, so the memo is at seq 1 and the timer id is `"await-signal-1"`.

- [ ] **Step 1: Write the failing restart test** in `EnginePostgresSignalTimeoutIT extends PostgresIntegrationSupport` (`@Tag("integration")`)

```java
@Test
@DisplayName("a parked await's deadline survives an executor restart and is not re-armed from now()")
void deadlineSurvivesRestart_andIsNotReArmed() throws Exception {
    var workflowId = MaestroEngineHarness.uniqueWorkflowId("await-durable");
    var a = newHarness(store); a.registerWorkflow(new TestWorkflows.PatientSignalWorkflow(Duration.ofHours(1)));
    var handle = a.start(workflowId, TestWorkflows.PatientSignalWorkflow.class, "seed");
    handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
    var row = store.findTimer(handle.instanceId(), "await-signal-1").orElseThrow();
    assertEquals(TimerStatus.PENDING, row.status());
    assertTrue(Duration.between(Instant.now(), row.fireAt()).compareTo(Duration.ofMinutes(55)) > 0);
    a.close();                                            // abandons the park; row and memo stay

    var b = newHarness(store); b.registerWorkflow(new TestWorkflows.PatientSignalWorkflow(Duration.ofHours(1)));
    assertEquals(1, b.recover());
    await().atMost(BOUND).until(() -> b.executor().isRunning(workflowId));
    assertEquals(row, store.findTimer(handle.instanceId(), "await-signal-1").orElseThrow(), "same row, same fireAt");
    assertEquals(1, countTimerRows(handle.instanceId()));  // raw SQL helper: SELECT count(*) FROM maestro_workflow_timer WHERE workflow_instance_id = ?

    makeDue(row.id());                                    // raw SQL: UPDATE maestro_workflow_timer SET fire_at = now() WHERE id = ?
    await().atMost(BOUND).pollInterval(Duration.ofMillis(50)).until(() -> b.fireDueTimers(10) == 1);
    assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));
    assertEquals("timed-out", handle.result(String.class));
    assertEquals(List.of(EventType.TIMER_SCHEDULED, EventType.SIGNAL_TIMEOUT, EventType.WORKFLOW_COMPLETED),
            handle.events().stream().map(WorkflowEvent::eventType).toList());
    b.close();
}

@Test
@DisplayName("a signal delivered while no node owned the workflow is consumed on recovery, not timed out")
void signalDuringDowntime_isConsumedOnRecovery() throws Exception { /* start on A, park, A.close(); deliver via a bare SignalManager-free path: new PostgresWorkflowStore(...).saveSignal(...) with the instance id; B.recover(); awaitTerminal COMPLETED; result == payload; events == [TIMER_SCHEDULED, SIGNAL_RECEIVED, WORKFLOW_COMPLETED]; timer row status is CANCELLED or FIRED, never PENDING */ }
```

- [ ] **Step 2: Write the failing race test** `AwaitSignalTimeoutRaceIT` (README Review Focus 2)

```java
enum Order { SIGNAL_THEN_FIRE, FIRE_THEN_SIGNAL }

@ParameterizedTest
@EnumSource(Order.class)
@DisplayName("signal persisted in the same instant the deadline fires: exactly one outcome is recorded, the loser is never discarded")
void signalAndDeadlineInTheSameInstant_recordExactlyOneOutcome(Order order) throws Exception {
    var workflowId = MaestroEngineHarness.uniqueWorkflowId("await-race-" + order);
    var racing = new RacingStore(store, order);           // see below
    var harness = MaestroEngineHarness.builder(racing, objectMapper).build();
    racing.harness(harness);
    harness.registerWorkflow(new TestWorkflows.PatientSignalWorkflow(Duration.ofHours(1)));
    var handle = harness.start(workflowId, TestWorkflows.PatientSignalWorkflow.class, "seed");
    handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
    var row = store.findTimer(handle.instanceId(), "await-signal-1").orElseThrow();
    makeDue(row.id());
    await().atMost(BOUND).pollInterval(Duration.ofMillis(50)).until(() -> harness.fireDueTimers(10) == 1);

    assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));
    var outcomes = handle.events().stream().filter(e -> e.sequenceNumber() == 2).map(WorkflowEvent::eventType).toList();
    assertEquals(1, outcomes.size());
    var signalRow = readSingleSignalRow(workflowId);     // raw SQL on maestro_workflow_signal
    switch (outcomes.getFirst()) {
        case SIGNAL_RECEIVED -> { assertEquals("approved", handle.result(String.class)); assertTrue(signalRow.consumed()); }
        case SIGNAL_TIMEOUT  -> { assertEquals("timed-out", handle.result(String.class)); assertFalse(signalRow.consumed(), "never discard a signal"); }
        default -> fail("unexpected outcome " + outcomes);
    }
    assertEquals(TimerStatus.FIRED, store.findTimer(handle.instanceId(), "await-signal-1").orElseThrow().status());
    assertEquals(1, handle.events().stream().filter(e -> e.eventType() == EventType.SIGNAL_RECEIVED || e.eventType() == EventType.SIGNAL_TIMEOUT).count());
    harness.close();
}
```

`RacingStore implements WorkflowStore` delegates everything to the Postgres store except `markTimerFired(UUID)`: for `SIGNAL_THEN_FIRE` it calls `harness.deliverSignal(workflowId, "approval", "approved")` **then** delegates; for `FIRE_THEN_SIGNAL` it delegates **then** delivers. Both run on the test thread inside `fireDueTimers`, so the signal is durable in the same instant as the CAS. Include `completeInstance` in the delegation (Task 1).

- [ ] **Step 3: Run to verify failure**

Run: `./gradlew :maestro-integration-tests:test --tests '*EnginePostgresSignalTimeoutIT*' --tests '*AwaitSignalTimeoutRaceIT*' --console=plain`
Expected: compile error until `PatientSignalWorkflow` exists; then PASS if Task 2 is correct. Prove the race test can fail: temporarily make `RacingStore.markTimerFired` deliver the signal twice — `outcomes.size()` stays 1 but the second signal row breaks `readSingleSignalRow`; restore.

- [ ] **Step 4: Add `PatientSignalWorkflow`, run three times**

Run: `./gradlew :maestro-integration-tests:test --tests '*EnginePostgresSignalTimeoutIT*' --tests '*AwaitSignalTimeoutRaceIT*' --rerun-tasks --console=plain` × 3
Expected: `BUILD SUCCESSFUL` each time.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "test(integration): pin durable awaitSignal deadlines and the fire/signal race on Postgres

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: Remove the loan sample's static timeout holders; test with `advanceTime()`

**Files:**
- Delete: `maestro-samples/sample-loan-origination/loan-application-service/src/main/java/io/b2mash/maestro/samples/loan/application/workflow/LoanTimeouts.java`
- Create: `.../loan/application/workflow/LoanTimeoutPolicy.java`, `.../loan/application/activity/LoanPolicyActivities.java`, `.../loan/application/activity/impl/ConfiguredLoanPolicyActivities.java`
- Modify: `.../loan/application/config/LoanSampleConfig.java` (drop the static call; keep `@EnableConfigurationProperties(LoanSampleProperties.class)`), `.../loan/application/workflow/LoanApplicationWorkflow.java:93,139,195,208,224,236,261` and the `src/v2/java/...LoanApplicationWorkflow.java:221,246,283,296,312,324,349` twin
- Modify: `maestro-samples/sample-loan-origination/underwriting-service/src/main/java/io/b2mash/maestro/samples/loan/underwriting/workflow/UnderwritingWorkflow.java:52-75,125-135`, `.../underwriting/config/UnderwritingSampleConfiguration.java:23`
- Create: `.../loan/underwriting/workflow/UnderwritingTimeoutPolicy.java`, `.../loan/underwriting/activity/UnderwritingPolicyActivities.java`, `.../loan/underwriting/activity/impl/ConfiguredUnderwritingPolicyActivities.java`
- Test: `.../loan/application/workflow/LoanApplicationWorkflowTest.java:55-75` (+ gate/decision paths), `.../LoanApplicationWorkflowV2Test.java:118-148`, `.../underwriting/workflow/UnderwritingWorkflowTest.java:40-57,125-156`
- Docs: `maestro-samples/sample-loan-origination/*/src/main/resources/application.yml` comments (`loan-application-service:62-67`, `underwriting-service:68-73`), `docs/release-notes.md`

**Interfaces:**
- Consumes: Task 2 (`advanceTime()` fires await deadlines).
- Produces: `public record LoanTimeoutPolicy(Duration docTimeout, Duration signTimeout, Duration decisionTimeout, Duration gateTimeout)` with `public static final LoanTimeoutPolicy SPEC_DEFAULTS = new LoanTimeoutPolicy(PT10M, PT10M, PT10M, PT1S)`; `@Activity public interface LoanPolicyActivities { LoanTimeoutPolicy timeouts(); }` (declaration style of `LoanActivities.java`); `@Component public final class ConfiguredLoanPolicyActivities implements LoanPolicyActivities` constructed from `LoanSampleProperties`. Mirror for underwriting: `UnderwritingTimeoutPolicy(Duration underwriterTimeout, Duration seniorTimeout)` with `SPEC_DEFAULTS` (PT10M, PT10M), `UnderwritingPolicyActivities { UnderwritingTimeoutPolicy timeouts(); }`, `ConfiguredUnderwritingPolicyActivities`.
- The workflow reads the policy **once**, as its first activity call (`@ActivityStub private LoanPolicyActivities policy; ... var timeouts = policy.timeouts();`), so the configured durations are memoized and deterministic on replay — the idiom that replaces static mutable state, and it keeps the E2E script's `MAESTRO_SAMPLE_DECISION_TIMEOUT=PT8S` per-node override (`e2e/run-e2e.sh:197-204`) working. Jackson 3 serialises `java.time.Duration` out of the box.

- [ ] **Step 1: Rewrite the tests first**

`LoanApplicationWorkflowTest`: delete `LoanTimeouts.configure/reset` (`:58-61`, `:74`); register `env.registerActivities(LoanPolicyActivities.class, () -> LoanTimeoutPolicy.SPEC_DEFAULTS)`. Every place a test previously waited out a gate or decision timeout in real time now waits for the park and advances the clock:

```java
// pattern, applied at each gate the happy path crosses (the workflow source names them: gate #1 after documents, gate #2 after signatures)
waitForStatus(handle, WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(5));
env.advanceTime(LoanTimeoutPolicy.SPEC_DEFAULTS.gateTimeout());
```

Add one new test:

```java
@Test
void decisionTimeoutIsDurable_sevenDaysElapseWithoutWallClock() throws TimeoutException {
    var handle = start(...); deliverRequiredDocuments(handle); deliverApprovedVerifications(handle);
    waitUntil(() -> "AWAITING_UNDERWRITING".equals(handle.query("getStatus", null, String.class)), Duration.ofSeconds(5)); // use the sample's real step name
    env.advanceTime(LoanTimeoutPolicy.SPEC_DEFAULTS.decisionTimeout());
    assertEquals(WorkflowStatus.FAILED, handle.awaitCompletion(Duration.ofSeconds(5)));
    assertEquals(SignalTimeoutException.class.getName(), handle.getStore().getInstance(handle.workflowId()).orElseThrow().output().get("exceptionType").stringValue());
}
```

`LoanApplicationWorkflowV2Test` (raw executors, no controllable clock): replace `LoanTimeouts.configure(...)` with `activityImpls.put(LoanPolicyActivities.class, (LoanPolicyActivities) () -> new LoanTimeoutPolicy(ofSeconds(5), ofSeconds(5), ofSeconds(5), ofMillis(200)))` and delete `LoanTimeouts.reset()`; add a one-line comment that this fixture has no test clock, so short memoized timeouts replace the static holder here.

`UnderwritingWorkflowTest`: delete `configureTimeouts/resetTimeouts` (`:48`, `:56`, `:127`, `:144`); register `env.registerActivities(UnderwritingPolicyActivities.class, () -> UnderwritingTimeoutPolicy.SPEC_DEFAULTS)`; in `escalatesOnUnderwriterTimeoutThenAcceptsSeniorDecision` replace the real-time wait with `waitForStatus(handle, WAITING_SIGNAL, ...)` → `env.advanceTime(Duration.ofMinutes(10))` → `waitUntil(registry.isEscalated(...))`; in `rejectsWithNoDecisionWhenBothDesksTimeOut` advance twice (wait for `WAITING_SIGNAL` between).

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :maestro-samples:sample-loan-origination:loan-application-service:test :maestro-samples:sample-loan-origination:underwriting-service:test --console=plain`
Expected: `BUILD FAILED` — `cannot find symbol LoanPolicyActivities` / `UnderwritingPolicyActivities`.

- [ ] **Step 3: Implement the policy activities and delete the holders**

Per Interfaces. In both `LoanApplicationWorkflow` twins the first statement of the workflow method is `var timeouts = policy.timeouts();` and every `LoanTimeouts.xxx()` becomes `timeouts.xxx()`. In `UnderwritingWorkflow` delete `:52-75` (the statics, `configureTimeouts`, `resetTimeouts`), add the stub, read `policy.timeouts()` at the top of `review(...)` and pass the fields to the two awaits (`:125-135`). `UnderwritingSampleConfiguration`: remove the `configureTimeouts` call (`:23`). `LoanSampleConfig`: remove the constructor body. Update the `application.yml` comments to say the `maestro.sample.*` durations are read by `Configured*PolicyActivities` and memoized per run.

- [ ] **Step 4: Run the sample tests and the E2E compile**

Run: `./gradlew :maestro-samples:sample-loan-origination:loan-application-service:test :maestro-samples:sample-loan-origination:underwriting-service:test :maestro-samples:sample-loan-origination:loan-application-service:v2BootJar --console=plain`
Expected: `BUILD SUCCESSFUL`; `grep -rn "LoanTimeouts\|configureTimeouts" maestro-samples/` → no matches.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(samples): replace static timeout holders with memoized policy activities; test timeouts with advanceTime()

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Signal hygiene — store-assigned `received_at` and the `SignalJanitor` (C14 a, b)

**Files:**
- Create: `maestro-store-postgres/src/main/resources/db/migration/V5__signal_received_at_default.sql`
- Modify: `maestro-store-jdbc/src/main/java/io/b2mash/maestro/store/jdbc/AbstractJdbcWorkflowStore.java:408-427` (saveSignal INSERT), add `deleteUnconsumedSignalsForTerminalInstancesBefore`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/spi/WorkflowStore.java:199-213` (saveSignal Javadoc), add the delete method after `adoptOrphanedSignals` (`:254`)
- Modify: `maestro-test/src/main/java/io/b2mash/maestro/test/InMemoryWorkflowStore.java` (new method, under `signalLock`)
- Modify: every `implements WorkflowStore` listed in Task 1 **Files** (one delegating/stub method each) — include `TerminalEventDelayStore`, `RacingStore` (Task 3), `FaultInjecting*` wrappers if any implement the SPI
- Create: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/SignalJanitor.java`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java:127-129` (fields), `:1021-1033` (next to `startTimerPoller`), `:1099-1115` (shutdown), `:1263-1296` (accessors)
- Modify: `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/config/MaestroProperties.java:491-509`, `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/config/StartupRecoveryRunner.java:46-50`
- Test: `maestro-store-postgres/src/test/java/io/b2mash/maestro/store/postgres/PostgresWorkflowStoreTest.java`, `maestro-test/src/test/java/io/b2mash/maestro/test/InMemoryWorkflowStoreTest.java`, create `maestro-core/src/test/java/io/b2mash/maestro/core/engine/SignalJanitorTest.java`, `maestro-spring-boot-starter/src/test/java/io/b2mash/maestro/spring/config/MaestroPropertiesBindingTest.java:88-170`, `maestro-spring-boot-starter/src/test/java/io/b2mash/maestro/spring/config/MaestroAutoConfigurationConfigSeamsTest.java`
- Docs: `docs/configuration.md:380-386` (table), `CLAUDE.md` (SPI block + "Configuration Namespace"), `docs/release-notes.md`

**Interfaces:**
- Produces (SPI): `int deleteUnconsumedSignalsForTerminalInstancesBefore(Instant cutoff)` — deletes signal rows with `consumed = false` whose `workflow_instance_id` references an instance with `status IN ('COMPLETED', 'TERMINATED')` and `completed_at < cutoff`; returns the count. Javadoc must say explicitly: **`FAILED` is excluded** because `retryWorkflow` may still consume those signals (Issue 19); orphans (`workflow_instance_id IS NULL`) are never touched (they belong to workflows that have not started); consumed rows are never touched here (consumed-row retention is out of this plan's scope).
- Produces (SPI Javadoc on `saveSignal`): `receivedAt` is assigned by the store — a shared-database store MUST use the database clock and ignore the caller's value, so same-name ordering does not depend on node clock skew; the in-memory store keeps the caller's value (single clock).
- Produces (core): `final class SignalJanitor` (package-private, modelled on `TimerPoller.java:16-128` minus leader election — the delete is idempotent and cheap, every node may run it): ctor `SignalJanitor(WorkflowStore store, Duration interval, Duration retention)` validating both positive; `void start()`, `void stop()`, `boolean isRunning()`, `int sweepOnce()` (calls the SPI with `Instant.now().minus(retention)`, logs at INFO when `> 0`, catches `RuntimeException` → WARN and keeps the loop alive). Virtual thread named `maestro-signal-janitor`.
- Produces (core, public): on `WorkflowExecutor`: `public void startSignalJanitor(Duration interval, Duration retention)` (`IllegalStateException` if already started or shutting down), `public boolean isSignalJanitorRunning()`, `public @Nullable SignalJanitorSettings signalJanitorSettings()`, `public record SignalJanitorSettings(Duration interval, Duration retention)` (nested). `shutdown()` stops the janitor with the other pollers.
- Produces (starter): `SignalProperties(@DefaultValue("30s") Duration wakeRecheckInterval, @DefaultValue("1h") Duration janitorInterval, @DefaultValue("7d") Duration terminalRetention)`; `defaults()` updated. Properties `maestro.signal.janitor-interval`, `maestro.signal.terminal-retention`. `StartupRecoveryRunner.run` step "2b" calls `executor.startSignalJanitor(signal.janitorInterval(), signal.terminalRetention())` and logs it.

- [ ] **Step 1: Migration + failing Postgres tests for `received_at`**

`V5__signal_received_at_default.sql`:

```sql
-- received_at is assigned by the database clock, not the inserting node's JVM
-- clock, so same-name signals from different nodes order by arrival, not by
-- skew. AbstractJdbcWorkflowStore.saveSignal omits the column from its INSERT.
-- Band 1-99 belongs to maestro-store-postgres (see V1).
ALTER TABLE maestro_workflow_signal ALTER COLUMN received_at SET DEFAULT now();
```

Tests in `PostgresWorkflowStoreTest` (new `@Nested class SignalClockTests`):

```java
@Test
void saveSignal_receivedAtComesFromTheDatabaseClock() {
    var created = store.createInstance(newInstance("wf-clock"));
    store.saveSignal(new WorkflowSignal(UUID.randomUUID(), created.id(), "wf-clock", "s", null, false, Instant.EPOCH));
    var stored = store.getUnconsumedSignals("wf-clock", "s").getFirst();
    assertTrue(Duration.between(stored.receivedAt(), Instant.now()).abs().compareTo(Duration.ofMinutes(1)) < 0,
            "the caller's EPOCH must be ignored in favour of now()");
}

@Test
void getUnconsumedSignals_ordersByStoreArrival_notCallerClock() {
    var created = store.createInstance(newInstance("wf-order"));
    var first = UUID.randomUUID(); var second = UUID.randomUUID();
    store.saveSignal(new WorkflowSignal(first, created.id(), "wf-order", "s", null, false, Instant.now().plus(Duration.ofDays(1))));
    store.saveSignal(new WorkflowSignal(second, created.id(), "wf-order", "s", null, false, Instant.EPOCH));
    assertEquals(List.of(first, second), store.getUnconsumedSignals("wf-order", "s").stream().map(WorkflowSignal::id).toList());
}
```

Run: `./gradlew :maestro-store-postgres:test --tests '*SignalClockTests*' --console=plain` → Expected: FAIL (`receivedAt` is 1970).

- [ ] **Step 2: Implement** — `saveSignal` INSERT drops `received_at` (7 parameters); update the `saveSignal` Javadoc on the SPI. Run Step 1 again → PASS. Run `./gradlew :maestro-integration-tests:test --tests '*MaestroMigrationsCoexistIT*' --console=plain` → PASS (V5 is inside band 1–99).

- [ ] **Step 3: Failing store tests for the janitor delete**

In `InMemoryWorkflowStoreTest` and `PostgresWorkflowStoreTest` (new `@Nested class SignalJanitorDeleteTests`), same five cases; fixture: one instance per status, each `completedAt = now - 10 days` (set via `updateInstance` with `toBuilder().completedAt(...)`), one unconsumed signal each, plus one consumed signal on the COMPLETED instance, one orphan signal (null instance id), and one COMPLETED instance completed 1 day ago with a signal. `cutoff = now - 7 days`.

```java
@Test void deleteUnconsumedSignals_deletesCompletedAndTerminatedOlderThanCutoff() { assertEquals(2, store.deleteUnconsumedSignalsForTerminalInstancesBefore(cutoff)); }
@Test void deleteUnconsumedSignals_keepsSignalsOfFailedInstances() { store.delete...(cutoff); assertEquals(1, store.getUnconsumedSignals(failedWorkflowId, "s").size()); }
@Test void deleteUnconsumedSignals_keepsActiveNewerConsumedAndOrphanSignals() { store.delete...(cutoff); assertEquals(1, unconsumed(runningId)); assertEquals(1, unconsumed(recentCompletedId)); assertTrue(consumedRowStillExists(completedId)); assertEquals(1, unconsumed(orphanWorkflowId)); }
@Test void deleteUnconsumedSignals_isIdempotent() { store.delete...(cutoff); assertEquals(0, store.delete...(cutoff)); }
```

Run both modules' tests → Expected: compile failure.

- [ ] **Step 4: Add the SPI method and all implementations**

JDBC SQL (dialect-neutral, no `USING`): `DELETE FROM <signal> WHERE consumed = FALSE AND workflow_instance_id IN (SELECT id FROM <instance> WHERE status IN ('COMPLETED','TERMINATED') AND completed_at < ?)` via `update(...)`. In-memory: `signals.removeIf(...)` under `signalLock`, reading `instances` for status/completedAt. Every stub from Task 1's list gets the one-liner (stubs may `return 0`). Run Step 3 tests → PASS.

- [ ] **Step 5: Failing `SignalJanitorTest`** (core; its own tiny stub store, as the other core tests do)

```java
@Test
void sweepOnce_passesNowMinusRetentionAsCutoff() {
    var store = new RecordingStore();                   // records the cutoff, returns 3
    var janitor = new SignalJanitor(store, Duration.ofHours(1), Duration.ofDays(7));
    var before = Instant.now();
    assertEquals(3, janitor.sweepOnce());
    assertTrue(Duration.between(before.minus(Duration.ofDays(7)), store.lastCutoff()).abs().compareTo(Duration.ofSeconds(5)) < 0);
}

@Test
void storeFailure_isLoggedAndTheLoopKeepsRunning() throws Exception {
    var store = new RecordingStore(); store.failNextWith(new IllegalStateException("db down"));
    var janitor = new SignalJanitor(store, Duration.ofMillis(20), Duration.ofDays(7));
    janitor.start();
    await().atMost(Duration.ofSeconds(5)).until(() -> store.calls() >= 3);
    assertTrue(janitor.isRunning());
    janitor.stop();
    assertFalse(janitor.isRunning());
}

@Test
void rejectsNonPositiveSettings() {
    assertThrows(IllegalArgumentException.class, () -> new SignalJanitor(new RecordingStore(), Duration.ZERO, Duration.ofDays(7)));
    assertThrows(IllegalArgumentException.class, () -> new SignalJanitor(new RecordingStore(), Duration.ofHours(1), Duration.ofSeconds(-1)));
}
```

Add to `WorkflowExecutorTest` (or a new small test next to the poller tests):

```java
@Test
void startSignalJanitor_exposesSettingsAndStopsOnShutdown() {
    executor.startSignalJanitor(Duration.ofHours(2), Duration.ofDays(3));
    assertTrue(executor.isSignalJanitorRunning());
    assertEquals(new WorkflowExecutor.SignalJanitorSettings(Duration.ofHours(2), Duration.ofDays(3)), executor.signalJanitorSettings());
    assertThrows(IllegalStateException.class, () -> executor.startSignalJanitor(Duration.ofHours(2), Duration.ofDays(3)));
    executor.shutdown();
    assertFalse(executor.isSignalJanitorRunning());
}
```

Run: `./gradlew :maestro-core:test --tests '*SignalJanitorTest*' --tests '*WorkflowExecutorTest*' --console=plain` → Expected: compile failure.

- [ ] **Step 6: Implement `SignalJanitor` and the executor seam**; run Step 5 → PASS; `./gradlew :maestro-core:dependencies --configuration compileClasspath | grep -c springframework` → `0`.

- [ ] **Step 7: Failing starter tests**

`MaestroPropertiesBindingTest.remainingBlocksBind` (`:88-122`): add `"maestro.signal.janitor-interval=2h"`, `"maestro.signal.terminal-retention=3d"` and `assertThat(properties.getSignal().janitorInterval()).isEqualTo(Duration.ofHours(2)); assertThat(properties.getSignal().terminalRetention()).isEqualTo(Duration.ofDays(3));`. `defaultsSurviveWhenNothingIsConfigured` (`:138-168`): `isEqualTo(Duration.ofHours(1))` and `Duration.ofDays(7)`.

`MaestroAutoConfigurationConfigSeamsTest` (the project's inert-config history is why this exists):

```java
@Test
void signalJanitorSettingsReachTheExecutor() {
    runner.withPropertyValues("maestro.signal.janitor-interval=2h", "maestro.signal.terminal-retention=3d")
            .run(context -> {
                context.getBean(StartupRecoveryRunner.class).run(new DefaultApplicationArguments());
                var executor = context.getBean(WorkflowExecutor.class);
                assertThat(executor.isSignalJanitorRunning()).isTrue();
                assertThat(executor.signalJanitorSettings())
                        .isEqualTo(new WorkflowExecutor.SignalJanitorSettings(Duration.ofHours(2), Duration.ofDays(3)));
                executor.shutdown();
            });
}
```

(`ApplicationContextRunner` does not invoke `ApplicationRunner`s, hence the explicit `run`; follow how the file's other tests obtain beans and shut the executor down.)

Run: `./gradlew :maestro-spring-boot-starter:test --tests '*MaestroPropertiesBindingTest*' --tests '*ConfigSeamsTest*' --console=plain` → Expected: FAIL (unknown property / `startSignalJanitor` never called).

- [ ] **Step 8: Implement the properties and the runner call**; run Step 7 → PASS; run `./gradlew :maestro-spring-boot-starter:test --console=plain` → `BUILD SUCCESSFUL`.

- [ ] **Step 9: Docs**

- `docs/configuration.md:380-386`: add rows `maestro.signal.janitor-interval` (`Duration`, `1h`, "how often unconsumed signals addressed to finished workflows are purged") and `maestro.signal.terminal-retention` (`Duration`, `7d`, "how long after a workflow reaches `COMPLETED`/`TERMINATED` its unconsumed signals are kept; `FAILED` workflows keep theirs because they can be retried"). One sentence that `received_at` is the database clock since V5.
- `CLAUDE.md`: SPI block gets `int deleteUnconsumedSignalsForTerminalInstancesBefore(Instant cutoff);`; "Configuration Namespace" paragraph lists the two new properties.
- `docs/release-notes.md` `## Unreleased`: "### Added — signal janitor and database-assigned `received_at` (C14)" naming the V5 migration and the breaking SPI addition.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat(signals): database-assigned received_at and a SignalJanitor for finished workflows (C14)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: Synchronous Valkey wake subscription (C14 c)

**Files:**
- Modify: `maestro-lock-valkey/src/main/java/io/b2mash/maestro/lock/valkey/ValkeySignalNotifier.java:84-98` (+ class Javadoc `:13-42`)
- Test: `maestro-lock-valkey/src/test/java/io/b2mash/maestro/lock/valkey/ValkeySignalNotifierTest.java:48-67`
- Docs: `docs/configuration.md:392-400` (the wake-recheck paragraph)

**Interfaces:**
- Consumes: Lettuce `StatefulRedisPubSubConnection.sync().subscribe(channel)` / `.unsubscribe(channel)` — return after the server acknowledges.
- Produces: `SignalNotifier.subscribe` on Valkey returns only once the subscription is active, so `SignalManager`'s check→subscribe race closure (`SignalManager.java:328-336`) is real rather than racy.

- [ ] **Step 1: Make the existing test prove it** — in `subscribeReceivesSignal` (`:50-67`) delete `Thread.sleep(100)` and its comment; add

```java
@Test
@DisplayName("subscribe returns only once the server has registered the subscription — a publish issued immediately after is received")
void publishImmediatelyAfterSubscribe_isReceived_everyTime() throws InterruptedException {
    for (int i = 0; i < 25; i++) {
        var latch = new CountDownLatch(1);
        var workflowId = "wf-sync-" + i;
        notifier.subscribe(workflowId, (wfId, name) -> latch.countDown());
        notifier.publish(workflowId, "go");
        assertTrue(latch.await(2, TimeUnit.SECONDS), "iteration " + i + ": publish raced ahead of the subscription");
        notifier.unsubscribe(workflowId);
    }
}
```

Run: `./gradlew :maestro-lock-valkey:test --tests '*ValkeySignalNotifierTest*' --console=plain --rerun-tasks` × 3
Expected: at least one FAIL (the async subscribe has not registered when the publish lands). If it never fails, keep the test anyway — it is the pin.

- [ ] **Step 2: Implement** — `pubSubConnection.sync().subscribe(channel)` and `.sync().unsubscribe(channel)`. Javadoc on `subscribe`: returns after the server acknowledges; bounded by the connection's command timeout; the remaining gap is a notification published while this connection is disconnected (Lettuce re-subscribes on reconnect but does not replay), which the `maestro.signal.wake-recheck-interval` store re-read covers.

- [ ] **Step 3: Run** the same command × 3 → `BUILD SUCCESSFUL` each time. Also `./gradlew :maestro-core:test --tests '*SignalManagerTest*' --console=plain` → PASS (no API change).

- [ ] **Step 4: Docs** — `docs/configuration.md:392-400`: add one sentence that with Valkey the subscription is acknowledged before the workflow parks, so the recheck interval only bounds latency across a pub/sub disconnect. `docs/release-notes.md` `## Unreleased`: "### Fixed — Valkey wake subscription is acknowledged before parking".

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "fix(valkey): acknowledge the wake subscription before the workflow parks (C14c)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: Postgres transport — attempts count at claim time; stale claims with no budget are retired, not re-invoked (Issue 27)

**Files:**
- Modify: `maestro-messaging-postgres/src/main/java/io/b2mash/maestro/messaging/postgres/PostgresWorkflowMessaging.java:281-300` (task claim SQL + comment), `:344-360` (signal claim SQL), `:406-434` (process/updateStatus), `:452-525` (recordFailure / recordSingleAttemptFailure), class Javadoc `:40-66`
- Create: `maestro-messaging-postgres/src/test/java/io/b2mash/maestro/messaging/postgres/FaultInjectingDataSource.java`
- Test: `maestro-messaging-postgres/src/test/java/io/b2mash/maestro/messaging/postgres/PostgresWorkflowMessagingTest.java` (new `@Nested class StatusWriteFailureTests`; existing `staleProcessingClaimIsReclaimed` `:104-121` and `redeliveryDisabled_marksFailedAfterOneAttempt` `:269-297` must keep passing)
- Docs: `docs/configuration.md:210-232` (the FAILED-row rescue section), `docs/open-issues.md:279` and `:2341`, `docs/release-notes.md`

**Reading of Issue 27 (document in the class Javadoc).** The issue's sketch wants a claim state the stale reclaim excludes "once a status-write attempt has begun" — but when the status write fails the database is the thing that is down, so nothing can be recorded *at* the failure. The only durable marker available is written **at claim time**: the claim itself increments `attempts`. With that, every stale `PROCESSING` row is handled by one rule, uniformly across the three status-write sites (`COMPLETED`, enabled-redelivery `PENDING`/`DEAD_LETTER`, disabled-redelivery `FAILED`):

- **Budget left** (`attempts < maxAttempts`, redelivery enabled): the stale reclaim re-claims and re-invokes the handler. This is the documented, accepted double delivery under a double fault ("identical and acceptable on all three" — the issue's second allowed outcome); handlers are idempotent per `CLAUDE.md`.
- **No budget left** (`attempts >= maxAttempts` when enabled; `attempts >= 1` when disabled — "exactly one attempt"): a *retire sweep* moves the row straight to `DEAD_LETTER` (enabled) or `FAILED` (disabled) with `last_error = "finalisation write lost after attempt <n>; outcome unknown — retired without re-invoking the handler"` and never invokes the handler again.

Consequence, stated plainly: a consumer that crashes mid-handler now consumes an attempt (the comment at `:283-285` says the opposite today — rewrite it). This is deliberate: a poison message that crashes the JVM now dead-letters after `maxAttempts` crashes instead of looping forever, and it is what makes at-most-once provable when redelivery is disabled.

**Interfaces:**
- Produces (SQL): both claim statements set `attempts = attempts + 1` alongside `status = 'PROCESSING', processed_at = now()`; `RETURNING attempts` is therefore the post-increment count. `recordFailure(table, rowId, attempts, ...)` and `recordSingleAttemptFailure(...)` no longer add 1 (rename the parameter from `previousAttempts` to `attempts`).
- Produces (SQL): `private int retireStaleClaims(Connection conn, String table, String qualifierColumn, String qualifier)` run once per poll iteration, before the claim, inside the same transaction: `UPDATE <table> SET status = ?, last_error = ?, processed_at = now() WHERE <qualifierColumn> = ? AND status = 'PROCESSING' AND processed_at < now() - interval '5 minutes' AND attempts >= ?` with `status = redelivery.enabled() ? "DEAD_LETTER" : "FAILED"` and threshold `redelivery.enabled() ? redelivery.maxAttempts() : 1`. Logged at WARN with the count when `> 0`.
- Produces (test support): `FaultInjectingDataSource implements DataSource` wrapping `PGSimpleDataSource`: `arm()` makes `getConnection()` throw `SQLException("injected: connection refused")` and count it; `disarm()`; `int failedConnections()`.
- `PostgresMessagingTestSupport.ageProcessingClaim(table, workflowId)` (`:142`) backdates `processed_at` past the stale window — reuse it.

- [ ] **Step 1: Write the failing fault-injection tests** (`StatusWriteFailureTests`; each constructs `new PostgresWorkflowMessaging(faulty, objectMapper, notificationListener, config)` with `faulty = new FaultInjectingDataSource(newDataSource())`, subscribes to a unique service, publishes one signal through the un-faulted `messaging`, and closes the faulty instance in `finally`)

```java
@Test
@DisplayName("redelivery disabled: COMPLETED write fails → row retired FAILED, handler never re-invoked")
void completedWriteFails_disabled_retiredWithoutReinvocation() throws Exception {
    var calls = new AtomicInteger();
    faulty.subscribeSignals(service, m -> { calls.incrementAndGet(); faulty.arm(); });
    messaging.publishSignal(service, new SignalMessage(wf, "approval", null));
    await().atMost(BOUND).until(() -> faulty.failedConnections() >= 1);
    faulty.disarm();
    ageProcessingClaim("maestro_signal_queue", wf);
    await().atMost(BOUND).until(() -> "FAILED".equals(statusOf("maestro_signal_queue", wf)));
    assertEquals(1, intColumnOf("maestro_signal_queue", "attempts", wf));
    assertTrue(columnOf("maestro_signal_queue", "last_error", wf).contains("outcome unknown"));
    await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(8)).until(() -> calls.get() == 1);
}

@Test
@DisplayName("redelivery disabled: FAILED write fails → row retired FAILED, handler never re-invoked")
void failureWriteFails_disabled_retiredWithoutReinvocation() throws Exception { /* handler: calls++, faulty.arm(), throw; same assertions as above */ }

@Test
@DisplayName("redelivery enabled, budget left: COMPLETED write fails → reclaimed and re-invoked once (documented double delivery)")
void completedWriteFails_enabledWithBudget_reclaimedAndReinvoked() throws Exception {
    // config: enabled, maxAttempts 3
    var calls = new AtomicInteger();
    faulty.subscribeSignals(service, m -> { if (calls.incrementAndGet() == 1) faulty.arm(); });
    ... publish; await failedConnections >= 1; disarm; ageProcessingClaim ...
    await().atMost(BOUND).until(() -> "COMPLETED".equals(statusOf("maestro_signal_queue", wf)));
    assertEquals(2, calls.get());
    assertEquals(2, intColumnOf("maestro_signal_queue", "attempts", wf));
}

@Test
@DisplayName("redelivery enabled, last attempt: FAILED→DEAD_LETTER write fails → retired DEAD_LETTER, handler never re-invoked")
void failureWriteFails_enabledLastAttempt_retiredDeadLetter() throws Exception {
    // config: enabled, maxAttempts 1; handler: calls++, arm, throw
    ... await failedConnections >= 1; disarm; ageProcessingClaim ...
    await().atMost(BOUND).until(() -> "DEAD_LETTER".equals(statusOf("maestro_signal_queue", wf)));
    assertEquals(1, calls.get());
    assertTrue(columnOf("maestro_signal_queue", "last_error", wf).contains("outcome unknown"));
}

@Test
@DisplayName("a crash before the handler ran still counts as an attempt: stale claim with budget is reclaimed with attempts incremented")
void staleClaim_consumesAnAttempt() throws Exception {
    // publish; raw SQL: UPDATE maestro_signal_queue SET status='PROCESSING', attempts=1, processed_at = now() - interval '6 minutes' WHERE workflow_id = ?
    // subscribe with maxAttempts 3 → handler runs once, status COMPLETED, attempts == 2
}
```

Run: `./gradlew :maestro-messaging-postgres:test --tests '*StatusWriteFailureTests*' --console=plain`
Expected: compile error on `FaultInjectingDataSource`; after adding it, the two `disabled` tests FAIL (handler called twice, status `COMPLETED`/`FAILED` with `attempts` wrong) and `staleClaim_consumesAnAttempt` FAILS on `attempts == 1`.

- [ ] **Step 2: Implement** per Interfaces: claim SQL `attempts = attempts + 1` (both loops), `retireStaleClaims` called at the top of each claim transaction (both loops), `recordFailure`/`recordSingleAttemptFailure` use the claimed count, rewrite the comment at `:281-285`, update the class Javadoc with the reading above and the uniform three-site guarantee, and update the two WARN messages at `:487-490`/`:520-523` to say "the stale reclaim re-invokes it only if attempt budget remains; otherwise it is retired".

- [ ] **Step 3: Run the module** — `./gradlew :maestro-messaging-postgres:test --console=plain --rerun-tasks` × 3 → `BUILD SUCCESSFUL` each time (including `staleProcessingClaimIsReclaimed` and `redeliveryDisabled_marksFailedAfterOneAttempt`, whose `attempts == 1` assertion now holds because the claim counted it).

- [ ] **Step 4: Docs** — `docs/configuration.md:210-232`: a paragraph "Retired rows": what `last_error = "... outcome unknown ..."` means, that the handler may or may not have run, and that the existing rescue `UPDATE ... SET status = 'PENDING', next_attempt_at = now()` re-queues them deliberately. `docs/open-issues.md:279` → `**Resolved**`; `:2341` → `> **Resolved.** <attempt counted at claim; retire sweep; the uniform guarantee; pins `PostgresWorkflowMessagingTest.StatusWriteFailureTests`>` + commit hash. `docs/release-notes.md` `## Unreleased`: "### Changed — Postgres transport counts an attempt at claim time and retires stale claims with no budget (Issue 27)", stating the crash-consumes-an-attempt behaviour change.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "fix(messaging-postgres): count attempts at claim time; retire stale claims with no budget instead of re-invoking (Issue 27)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

## Final verification (after Task 7)

- [ ] `./gradlew build --console=plain` → `BUILD SUCCESSFUL` (needs Docker).
- [ ] `./gradlew :maestro-integration-tests:test --console=plain --rerun-tasks` × 3 → green each time.
- [ ] `./gradlew :maestro-core:dependencies --configuration compileClasspath | grep -c springframework` → `0`.
- [ ] `grep -rn "LoanTimeouts\|configureTimeouts\|Instant.now().plus(timeout)" maestro-samples/*/src/main maestro-samples/*/*/src/main maestro-core/src/main` → no matches (the last pattern is the C12 defect at `SignalManager.java:359`; scoped to production sources — sample test helpers legitimately compute a wall-clock poll deadline with it; the second glob reaches the nested loan-origination services).
- [ ] `docs/open-issues.md` rows 26 and 27 read `**Resolved**` with commit hashes; `docs/release-notes.md` `## Unreleased` has the six entries named above.

---

### Task 8: Issue 32 — bound the Valkey signal-wake publish so a durable signal send never stalls on a brownout (stub, filed by plan 06 Task 2)

Stub only — filed from the chaos triage (`triage/ViolationTriageReport.md`, soak run 40674015602, workflow `loan-chaos-11501689961368764082-1145`); not scheduled in this cycle. `SignalManager.deliverSignal` persists the signal and then calls `signalNotifier.publish` synchronously; `ValkeySignalNotifier.publish` is `sync().publish` with Lettuce's 60 s default timeout, so a Valkey pause stalls every `signal()` caller for the whole outage, and a caller that times out and retries persists a byte-identical duplicate (nine rows in the triaged run, which exhausted the loan sample's `MAX_SIGNATURE_SIGNALS = 5`). Task 6 bounded `subscribe`; this bounds `publish` the same way. **Files:** modify `maestro-core/src/main/java/io/b2mash/maestro/core/engine/SignalManager.java:329-337` so the engine never runs the notification on the caller's thread (hand it to a virtual thread, never awaited — the guarantee then holds for every `SignalNotifier`, not just Valkey's) and `maestro-lock-valkey/src/main/java/io/b2mash/maestro/lock/valkey/ValkeySignalNotifier.java:83-92` (`async().publish`, failure logged from the future, so the notifier thread is not parked for 60 s either); update the `SignalNotifier.publish` Javadoc to state it must not block the caller; `docs/open-issues.md` Issue 32 → **Resolved**; `docs/release-notes.md` `## Unreleased`. **Interfaces:** `SignalNotifier.publish(String, String)` unchanged in signature; contract gains "returns promptly; delivery is best-effort and covered by the wake-recheck". **Pins (Global Constraints — two):** a unit pin on the in-memory fakes — `SignalManager.deliverSignal` with a `SignalNotifier` fake whose `publish` blocks returns within a bound and leaves exactly one signal row in the in-memory store (fails today: the call blocks for as long as the fake does) — plus `ValkeySignalNotifierTest` asserting `publish` returns within the bound against a paused container; and an `@Tag("integration")` pin in `maestro-integration-tests` that pauses a real Valkey container (`docker pause` via Testcontainers), calls `WorkflowHandle.signal()` against a real Postgres store, asserts it returns well inside 3 s with exactly one `maestro_workflow_signal` row, unpauses, and asserts the parked workflow still wakes (via the wake-recheck). No timeout is lengthened to make either pass.
