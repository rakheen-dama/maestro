# WP0 Executor Builder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `WorkflowExecutor` is built only through `WorkflowExecutor.builder(store, serializer, serviceName)…build()`; its ten public constructors are gone; no behaviour changes.

**Architecture:** A nested `WorkflowExecutor.Builder` holds one field per setting, initialised to the value the shortest constructor supplied. `build()` calls one private constructor, `WorkflowExecutor(Builder)`, which keeps today's validation. Task 1 rewrites each of the ten constructors as a one-line delegation to the builder that sets only that constructor's own parameters. The unchanged suite, 106 call sites, then runs through the builder's defaults, and that is the behavioural proof. Tasks 2–4 migrate every call site mechanically. Task 5 deletes the constructors and updates the docs.

**Tech Stack:** Java 25, `maestro-core` (no Spring), `maestro-spring-boot-starter`, `maestro-test`, JUnit 5, `ApplicationContextRunner`.

**Spec:** `docs/superpowers/plans/2026-11-12-engine-hardening-cycle/00-spec.md` (WP0, decision D6). Evidence: `evidence/audit-A-recovery-shutdown-health.md` § Issue 37. Issue: `docs/open-issues.md` Issue 37.

## Added and removed public surface

**Added** (all in `io.b2mash.maestro.core.engine`):

| Member | Default (the value the 6-arg constructor supplied at `cbf5a2a`) | Checked at `build()` |
|---|---|---|
| `public static WorkflowExecutor.Builder builder(WorkflowStore store, PayloadSerializer serializer, String serviceName)` | all three required | `Objects.requireNonNull` at the `builder(...)` call: messages `"store"`, `"serializer"`, `"serviceName"` |
| `public static final class WorkflowExecutor.Builder` | — | — |
| `Builder distributedLock(@Nullable DistributedLock)` | `null` | — |
| `Builder messaging(@Nullable WorkflowMessaging)` | `null` | — |
| `Builder signalNotifier(@Nullable SignalNotifier)` | `null` | — |
| `Builder lockKeyPrefix(String)` | `WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX` (`"maestro:lock:"`) | NPE `"lockKeyPrefix"` |
| `Builder instanceLockTtl(Duration)` | `WorkflowInstanceLockManager.DEFAULT_LOCK_TTL` (30 s) | IAE `"instanceLockTtl must be positive, got " + v` (null, zero, negative) |
| `Builder lifecycleEventsEnabled(boolean)` | `true` | — |
| `Builder shutdownTimeout(Duration)` | `WorkflowExecutor.DEFAULT_SHUTDOWN_TIMEOUT` (30 s) | IAE `"shutdownTimeout must be positive, got " + v` |
| `Builder wakeRecheckInterval(Duration)` | `SignalManager.DEFAULT_WAKE_RECHECK_INTERVAL` (30 s) | IAE `"wakeRecheckInterval must be positive, got " + v` |
| `Builder observer(EngineObserver)` | `EngineObserver.NOOP` | NPE `"observer"` |
| `Builder taskQueueLimits(Map<String, TaskQueueLimits>)` | `Map.of()` | NPE `"taskQueueLimits"` |
| `Builder defaultTaskQueueLimits(@Nullable TaskQueueLimits)` | `null` (unlisted queues unbounded) | — |
| `Builder instantSource(InstantSource)` | `InstantSource.system()` | NPE `"instantSource"` (today's check) |
| `WorkflowExecutor build()` | — | runs every check above |

Setters store their value and nothing else. A `Duration` setter accepts `null`, and `build()` rejects it with today's `IllegalArgumentException`, so the existing validation tests keep their assertions. Builder fields are package-private (no modifier) so the defaults test in the same package can read them. They are not public surface.

**Removed:** all ten public constructors of `WorkflowExecutor` (`WorkflowExecutor.java:172, 202, 238, 289, 346, 401, 457, 496, 552, 604` at `cbf5a2a`; 6, 8, 9, 11, 12, 13, 14 (`defaultTaskQueueLimits`), 7 (`InstantSource`), 14 (`InstantSource`) and 15 arguments).

**Deliberately stricter, on invalid input only:** a `null` `serializer`, `serviceName`, `lockKeyPrefix` or `observer` is accepted by today's constructors and fails later or never. With the builder it fails fast with an NPE. No call site passes `null` for any of them; the call-site inventory below shows this. Every other input behaves as today.

**Not changed:** the package-private constants `DEFAULT_SHUTDOWN_TIMEOUT`, `WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX`/`DEFAULT_LOCK_TTL`, and `SignalManager.DEFAULT_WAKE_RECHECK_INTERVAL` keep their values and their package-private visibility. No public accessor is added.

### Adding an option later (WP4, WP5, WP6 and after)

A new engine setting is:

1. one package-private field on `Builder` with its default;
2. one setter, in the order of the table above, with Javadoc stating the default and what `build()` rejects;
3. a read in `WorkflowExecutor(Builder)`, with any validation there (the single build path);
4. one new row in `WorkflowExecutorBuilderTest.defaultsAreTheValuesTheSixArgumentConstructorSupplied`;
5. if it has a property: `MaestroAutoConfiguration` passes it, and `MaestroAutoConfigurationConfigSeamsTest` pins that it arrives.

Never add a constructor or a second `builder(...)` overload. Changing an existing default (for example, if WP5 moves `DEFAULT_SHUTDOWN_TIMEOUT` with D11, or WP7 decides the engine should follow D3) is a named step in that package's plan that edits the row in the defaults test. D3 and D11 as written change the starter properties' defaults (`maestro.admin.events.enabled`, `maestro.shutdown.timeout`), not this builder's. WP6's Issue 36 option maps onto `taskQueueLimits(...)` and `defaultTaskQueueLimits(...)`, so it needs no engine API. WP2 and WP3 port the evidence repro tests (`AuditB32WakePermitLeakTest.java.txt` uses the 11-arg form; `AuditB33RetryCaughtFailureTest.java.txt` uses the 12-arg form) with this plan's migration rule. The evidence files themselves are not edited.

## Global Constraints

- No behaviour change. The only allowed differences are the NPEs listed under "Deliberately stricter".
- `maestro-core` never imports Spring. JSpecify `@Nullable` on every nullable parameter (the package is `@NullMarked`). Javadoc on every public member, with a thread-safety note on `Builder`. No Lombok.
- Spec anti-goal: an existing test is changed only as a named step of this plan. The only allowed edits to existing tests are:
  - replacing a `new WorkflowExecutor(...)` expression;
  - removing imports left unused;
  - the comment and `@DisplayName` rewordings named in Tasks 3 and 5;
  - the `RecordingLock` field and new test named in Task 2.

  No assertion line changes. Task 4 Step 3 checks this mechanically.
- **Migration rule.** Each call site becomes `WorkflowExecutor.builder(<arg1 store>, <arg5 serializer>, <arg6 serviceName>)`. That is followed by one setter for every other argument, in positional order, then `.build()`.
  - Omit an argument only when it is literally one of these expressions:
    - `null` for `distributedLock`, `messaging`, `signalNotifier` or `defaultTaskQueueLimits`;
    - `WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX` or `WorkflowInstanceLockManager.DEFAULT_LOCK_TTL`;
    - `true` for `lifecycleEventsEnabled`;
    - `WorkflowExecutor.DEFAULT_SHUTDOWN_TIMEOUT` or `SignalManager.DEFAULT_WAKE_RECHECK_INTERVAL`;
    - `EngineObserver.NOOP`, `Map.of()` or `InstantSource.system()`.
  - Keep everything else as a setter, including literals that equal a default (`"maestro:lock:"`, `Duration.ofSeconds(30)`).
  - The two exceptions are the kit (Task 2) and the integration harness (Task 4). Both say in a comment that they spell out the engine defaults only because a constructor forced them to. Their plan steps say exactly what to drop.
  - 14-argument sites: a last argument named `clock` is `instantSource`; anything else is `defaultTaskQueueLimits`.
- Branch `refactor/executor-builder` from `origin/main`, in the worktree `/Users/rakheendama/Projects/2026/maestro/.claude/worktrees/executor-builder`.
  - Before the first edit, run `git -C <worktree> status` and `git -C /Users/rakheendama/Projects/2026/maestro status`. Never `cd` into the main checkout.
  - This plan file lives on branch `docs/engine-hardening-cycle`; read it from the main checkout's absolute path.
- Gradle: always `./gradlew --no-watch-fs`, run from the worktree root. No two Gradle runs on one module at once. No `git stash`.
- Commit at every checkpoint named below.
  - Chain each git sequence with `&&`, and pass messages as `-m "$(cat <<'EOF' … EOF)"`, never as a top-level heredoc.
  - End each message with the attribution line the session supplies.
  - Never run git commands that mutate the repository in parallel.
- Any run longer than ~5 minutes (`./gradlew --no-watch-fs build`, `:maestro-integration-tests:test`) is a **coordinator gate**. The coordinator launches it detached, with a log whose header records `pwd`, `git rev-parse HEAD` and a timestamp (`tasks/lessons.md`, 2026-07-30 and 2026-08-02). The implementer does not start it.

## Review Focus

1. **The starter drops or swaps one of its eleven settings.** The setting no test pins today is `maestro.lock.ttl`, and nothing would notice it reverting to 30 s. → Task 2 adds `lockTtlReachesTheInstanceLock`.
2. **A mechanically migrated test site loses a non-default argument** (a `false` for lifecycle events, a short re-check, a lock) and still passes because nothing in that test asserts it. → the setter-count check in Task 3 Step 8 and Task 4 Step 3.
3. **A `null`, zero or negative `Duration` passes through the builder.** It must still raise `IllegalArgumentException` with today's message at `build()`, not an NPE at the setter. → Task 1 test `buildRejectsNonPositiveDurationsWithTodaysMessages`, plus the three migrated `WorkflowExecutorTest` validation tests.
4. **Only `defaultTaskQueueLimits` is set**, which is the starter's path when no queue is listed. Every queue must be bounded and the governor must not be `UNBOUNDED`. → Task 1 test `defaultTaskQueueLimitsAloneBoundsAnUnlistedQueue`.
5. **The kit loses `instantSource(clock)`,** so deadlines arm on the wall clock. → covered by the existing `TestWorkflowEnvironmentTest.advanceTimeByExactlyTheTimeoutFiresAnAwaitDeadline` and `advanceTimeByExactlyTheDurationFiresASleep`. The Task 2 reviewer mutation-checks this.

## Call-site inventory (at `cbf5a2a`, 106 sites)

| Module | Sites | Files | Constructor shapes (arg count × sites) |
|---|---|---|---|
| `maestro-spring-boot-starter` main | 1 | `MaestroAutoConfiguration.java:133` | 14 (`defaultTaskQueueLimits`) |
| `maestro-test` main | 1 | `TestWorkflowEnvironment.java:581` | 14 (`InstantSource`) |
| `maestro-core` test | 95 | 33 | 6×52, 7×1, 8×3, 9×4, 11×14, 12×14, 13×6, 14×1 (`defaultTaskQueueLimits`) |
| `maestro-spring-boot-starter` test | 5 | `AdminCommandDispatcherTest`, `SignalSubscriptionRunnerTest` (2), `StartupRecoveryRunnerTest`, `MaestroHealthIndicatorTest` | 6×5 |
| `maestro-samples` test | 3 | `OrderControllerTest`, `LoanApplicationWorkflowV2Test` (2) | 6×3 |
| `maestro-integration-tests` test | 1 | `support/MaestroEngineHarness.java:86` | 13 |

To regenerate: `grep -rn "new WorkflowExecutor(" --include='*.java' . | grep -v /build/`, run from the worktree root. It prints 106 lines before Task 2 and exactly 1 after Task 5 (inside `Builder.build()`).

---

### Task 1: The builder, with the ten constructors delegating to it

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java:161-688` (constructors), class Javadoc `:67-110`
- Create: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/WorkflowExecutorBuilderTest.java`

**Interfaces:**
- Produces: everything in the "Added" table. `private WorkflowExecutor(Builder builder)`. `Builder.build()` is `return new WorkflowExecutor(this);`.

- [ ] **Step 1: Create the worktree.**

```bash
git -C /Users/rakheendama/Projects/2026/maestro fetch origin && \
git -C /Users/rakheendama/Projects/2026/maestro worktree add .claude/worktrees/executor-builder -b refactor/executor-builder origin/main && \
git -C /Users/rakheendama/Projects/2026/maestro/.claude/worktrees/executor-builder status
```

Expected: a clean tree on `refactor/executor-builder`.

- [ ] **Step 2: Write the failing test** `WorkflowExecutorBuilderTest` (package `io.b2mash.maestro.core.engine`).
  - Fixtures: `new VersionedInMemoryStore()` and `new PayloadSerializer(new ObjectMapper())`.
  - Every executor the test builds goes into a list that `@AfterEach` shuts down.

```java
@Test void defaultsAreTheValuesTheSixArgumentConstructorSupplied() {
    var b = WorkflowExecutor.builder(store, serializer, "svc");
    assertNull(b.distributedLock); assertNull(b.messaging); assertNull(b.signalNotifier);
    assertEquals("maestro:lock:", b.lockKeyPrefix);
    assertSame(WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, b.lockKeyPrefix);
    assertEquals(Duration.ofSeconds(30), b.instanceLockTtl);
    assertSame(WorkflowInstanceLockManager.DEFAULT_LOCK_TTL, b.instanceLockTtl);
    assertTrue(b.lifecycleEventsEnabled);
    assertEquals(Duration.ofSeconds(30), b.shutdownTimeout);
    assertSame(WorkflowExecutor.DEFAULT_SHUTDOWN_TIMEOUT, b.shutdownTimeout);
    assertEquals(Duration.ofSeconds(30), b.wakeRecheckInterval);
    assertSame(SignalManager.DEFAULT_WAKE_RECHECK_INTERVAL, b.wakeRecheckInterval);
    assertSame(EngineObserver.NOOP, b.observer);
    assertEquals(Map.of(), b.taskQueueLimits);
    assertNull(b.defaultTaskQueueLimits);
    assertSame(InstantSource.system(), b.instantSource);
}

@Test void aDefaultBuildIsUnboundedOnTheSystemClock() {
    var e = keep(WorkflowExecutor.builder(store, serializer, "svc").build());
    assertSame(InstantSource.system(), e.instantSource());
    assertSame(TaskQueueGovernor.UNBOUNDED, e.taskQueueGovernor());
}

@Test void defaultTaskQueueLimitsAloneBoundsAnUnlistedQueue() {
    var e = keep(WorkflowExecutor.builder(store, serializer, "svc")
            .defaultTaskQueueLimits(new TaskQueueLimits(2, 3)).build());
    assertNotSame(TaskQueueGovernor.UNBOUNDED, e.taskQueueGovernor());
    assertEquals(OptionalInt.of(2), e.taskQueueGovernor().availableWorkflowSlots("never-configured"));
    assertEquals(OptionalInt.of(3), e.taskQueueGovernor().availableActivitySlots("never-configured"));
}

@Test void buildRejectsNonPositiveDurationsWithTodaysMessages() {
    // for each of instanceLockTtl, shutdownTimeout, wakeRecheckInterval, and each of
    // Duration.ZERO, Duration.ofSeconds(-5), null: build() throws IllegalArgumentException with
    // message "<name> must be positive, got <value>" — e.g. "shutdownTimeout must be positive, got PT-5S",
    // "wakeRecheckInterval must be positive, got null". The setter itself must not throw.
}

@Test void requiredArgumentsAndReferenceOptionsAreNeverNull() {
    // builder(null, serializer, "svc") → NPE "store"; builder(store, null, "svc") → "serializer";
    // builder(store, serializer, null) → "serviceName".
    // .observer(null).build() → NPE "observer"; same for lockKeyPrefix, taskQueueLimits, instantSource.
}

@Test void aBuiltExecutorKeepsItsSettingsWhenTheBuilderChangesLater() {
    var b = WorkflowExecutor.builder(store, serializer, "svc");
    var first = keep(b.build());
    var second = keep(b.defaultTaskQueueLimits(new TaskQueueLimits(1, 1)).build());
    assertSame(TaskQueueGovernor.UNBOUNDED, first.taskQueueGovernor());
    assertNotSame(TaskQueueGovernor.UNBOUNDED, second.taskQueueGovernor());
}
```

- [ ] **Step 3: Run it and confirm it fails.** Run `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.WorkflowExecutorBuilderTest'`. Expected: compilation failure, `cannot find symbol … builder`.

- [ ] **Step 4: Implement the builder** in `WorkflowExecutor.java`.
  - Add `public static Builder builder(WorkflowStore, PayloadSerializer, String)` and `public static final class Builder`. Fields and setters follow the "Added" table, in that order.
  - Move each parameter's Javadoc from the 15-arg constructor (and the long `instantSource` paragraph from the 14-arg `InstantSource` constructor) onto its setter. Add the default and what `build()` rejects.
  - `Builder` Javadoc must say:
    - it is not thread-safe, so configure and build on one thread;
    - it may be reused, and each `build()` returns a new executor from the values set at that moment;
    - the built executor is thread-safe as the class documents.
  - Replace the 15-arg constructor's body with `private WorkflowExecutor(Builder builder)`, which runs these checks in this order before building anything:
    1. the three existing IAE checks, with messages unchanged;
    2. `Objects.requireNonNull` for `lockKeyPrefix`, `observer` and `taskQueueLimits`;
    3. the rest of today's body, reading `builder.*` (the existing `requireNonNull(instantSource, "instantSource")` stays where it is).
  - Rewrite each of the ten public constructors (all ten stay public in this task) as one `this(builder(store, serializer, serviceName).<only this constructor's own parameters>)` call, with its Javadoc unchanged. For example:
    - the 6-arg form sets `distributedLock`, `messaging` and `signalNotifier`;
    - the 7-arg form sets those three plus `instantSource`;
    - the 14-arg `InstantSource` form sets everything except `defaultTaskQueueLimits`.

    A constructor must not set a value it does not take. The builder's defaults supply those, and that is what the unchanged suite then proves.
  - Add to the class Javadoc a `<h2>Construction</h2>` paragraph with a three-line `builder(...)…build()` example.

- [ ] **Step 5: Run the new test.** Run `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.WorkflowExecutorBuilderTest'`. Expected: PASS, 6 tests.

- [ ] **Step 6: Run the whole core suite unchanged.** Run `./gradlew --no-watch-fs :maestro-core:test`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit** `WorkflowExecutor.java` and `WorkflowExecutorBuilderTest.java` with the message `refactor(core): add WorkflowExecutor.builder; constructors delegate to it (Issue 37)`. Then run `git -C <worktree> diff --stat "$(git -C <worktree> merge-base origin/main HEAD)" HEAD -- '*/src/test/*'`. Expected: exactly one file, `WorkflowExecutorBuilderTest.java`. This shows that every existing test ran unmodified through the builder.

- [ ] **Step 8: Coordinator gate.** The coordinator runs `./gradlew --no-watch-fs build` on this commit. Expected: `BUILD SUCCESSFUL`. Every module's suite, including the integration harness and the samples, now constructs through the builder with no test edits. Reviewer mutation checks, each reverted after:
  - change the `lifecycleEventsEnabled` default to `false`: `defaultsAreThe…` goes red;
  - drop `.wakeRecheckInterval(wakeRecheckInterval)` from the 11-arg delegation: `WorkflowExecutorTest.wakeRecheckIntervalReachesSignalManager` goes red.

---

### Task 2: Production call sites: starter and test kit

**Files:**
- Modify: `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/config/MaestroAutoConfiguration.java:133-138`
- Modify: `maestro-test/src/main/java/io/b2mash/maestro/test/TestWorkflowEnvironment.java:125-130, 581-584`
- Test: `maestro-spring-boot-starter/src/test/java/io/b2mash/maestro/spring/config/MaestroAutoConfigurationConfigSeamsTest.java` (new test, `RecordingLock` gains a field)

**Interfaces:**
- Consumes: `WorkflowExecutor.builder(...)` and the setters from Task 1.

- [ ] **Step 1: Write the regression pin.** In `RecordingLock`, add `final Map<String, Duration> acquiredTtls = new ConcurrentHashMap<>();` and record `acquiredTtls.put(key, ttl)` in `tryAcquire`. Then add:

```java
@Test
@DisplayName("maestro.lock.ttl reaches the instance lock")
void lockTtlReachesTheInstanceLock() {
    var lock = new RecordingLock();
    runner.withBean(DistributedLock.class, () -> lock)
            .withPropertyValues("maestro.lock.ttl=45s")
            .run(context -> {
                assertThat(context).hasNotFailed();
                context.getBean(MaestroClient.class)
                        .newWorkflow(ActivityCallingWorkflow.class, options("lock-ttl-1"))
                        .startAndWait(null, Duration.ofSeconds(10), String.class);
                assertThat(lock.acquiredTtls)
                        .containsEntry("maestro:lock:workflow:lock-ttl-1", Duration.ofSeconds(45));
            });
}
```

This pin passes on today's code by design; it guards the migration. Run `./gradlew --no-watch-fs :maestro-spring-boot-starter:test --tests 'io.b2mash.maestro.spring.config.MaestroAutoConfigurationConfigSeamsTest'`. Expected: PASS. Then delete `lock.ttl()` from the current constructor call (replace it with `Duration.ofSeconds(30)`) and rerun. Expected: FAIL, `but was … PT30S`. Revert.

- [ ] **Step 2: Migrate the starter.** Use `WorkflowExecutor.builder(store, serializer, serviceName)` and set all eleven options from today's locals, in table order:

```
distributedLock, messaging, signalNotifier, lockKeyPrefix(lock.keyPrefix()), instanceLockTtl(lock.ttl()),
lifecycleEventsEnabled, shutdownTimeout, wakeRecheckInterval, observer, taskQueueLimits(queueLimits),
defaultTaskQueueLimits(defaultLimits)
```

- [ ] **Step 3: Migrate the kit.**
  - Use `WorkflowExecutor.builder(view.store(), serializer, SERVICE_NAME)`, then `.distributedLock(lock).messaging(messaging).signalNotifier(signalNotifier).lockKeyPrefix(LOCK_KEY_PREFIX).observer(observer).instantSource(clock).build()`.
  - Keep `LOCK_KEY_PREFIX`, because `:821` hands the same prefix to the activity proxies, so the two must stay one value.
  - Delete `INSTANCE_LOCK_TTL`, `SHUTDOWN_TIMEOUT` and `WAKE_RECHECK_INTERVAL`, plus any import left unused.
  - Replace the `:125-126` comment with: `// The activity-proxy lock prefix must equal the executor's; everything else is the engine's default (WorkflowExecutor.Builder).`

- [ ] **Step 4: Run the tests.** Run `./gradlew --no-watch-fs :maestro-spring-boot-starter:test :maestro-test:test`. Expected: `BUILD SUCCESSFUL`. Reviewer mutation check (reverted after): drop `.instantSource(clock)` and `TestWorkflowEnvironmentTest.advanceTimeByExactlyTheTimeoutFiresAnAwaitDeadline` goes red.

- [ ] **Step 5: Commit** the three files with the message `refactor: starter and test kit build their executor with the builder (Issue 37)`.

---

### Task 3: Migrate the `maestro-core` tests (95 sites, 33 files)

**Files** (all under `maestro-core/src/test/java/io/b2mash/maestro/core/engine/`):
- Batch A: `WorkflowExecutorTest.java` (29 sites).
- Batch B (43 sites): `WorkflowExecutorLifecycleEventPublishingTest` (12), `WorkflowExecutorShutdownTest` (10), `WorkflowExecutorPerRunInstanceTest` (6), `WorkflowExecutorConcurrencyTest` (4), `WorkflowExecutorTerminateTest`, `WorkflowExecutorTerminalTransitionTest` and `WorkflowExecutorRetryTest` (2 each), and one each in `WorkflowExecutorQueryTest`, `WorkflowExecutorObserverTest`, `WorkflowExecutorErrorEscapeTest`, `WorkflowExecutorDuplicateEventStandDownTest` and `WorkflowExecutorCrossNodeTimerWakeTest`.
- Batch C (23 sites): `SignalTimeoutReplayDeterminismTest` (3), `WorkflowVersionRecoveryTest` (2), and one each in `WorkflowTerminalStateGuardTest`, `UnknownHistoryStandDownTest`, `TimerPollerTest`, `StoreFaultStandDownTest`, `StickyStaleStopPointsTest`, `SequenceSpaceExhaustionTest`, `RetentionSweeperTest`, `ReplayDriftStandDownTest`, `RecoveryPassIsolationTest`, `ParkedProbeStoreOutageTest`, `ParallelBranchSameSignalTest`, `ObserverReplayNoDoubleCountTest`, `ForeignPayloadStandDownTest`, `ConcurrentBranchParkingTest`, `BackgroundLoopStartShutdownRaceTest`, `AwaitTimerStrayPermitTest`, `AwaitTimeoutRetryRearmTest` and `AwaitDeadlineStoreErrorTest`.

**Interfaces:**
- Consumes: the builder from Task 1. Constructors still exist, so each batch compiles on its own.

- [ ] **Step 1: Batch A.** Apply the migration rule to every site in `WorkflowExecutorTest`. These are named edits:
  - The three validation tests at `:855-899` keep each `assertThrows(IllegalArgumentException.class, () -> …)`. The lambda becomes the builder chain ending in `.build()`.
  - Rename them `buildRejectsNonPositiveInstanceLockTtl`, `buildRejectsNonPositiveShutdownTimeout` and `buildRejectsNonPositiveWakeRecheckInterval`. Their `@DisplayName`s become `"build() rejects a non-positive instance lock TTL"`, `"… shutdown timeout"` and `"… wake-recheck interval"`.
  - Comments at `:184` and `:210`: change "constructor" to "builder".

- [ ] **Step 2: Compile and run Batch A.** Run `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.WorkflowExecutorTest'`. Expected: PASS.

- [ ] **Step 3: Commit Batch A** with the message `test(core): WorkflowExecutorTest builds executors with the builder`.

- [ ] **Step 4: Batch B.** Apply the migration rule. Named comment edit: in `WorkflowExecutorLifecycleEventPublishingTest.java:54`, change "threaded in via the executor constructor" to "threaded in via the executor builder".

- [ ] **Step 5: Run Batch B and commit.** Run `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.WorkflowExecutor*'`. Expected: PASS. Commit with the message `test(core): WorkflowExecutor*Test build executors with the builder`.

- [ ] **Step 6: Batch C.** Apply the migration rule.

- [ ] **Step 7: Run the whole core suite.** Run `./gradlew --no-watch-fs :maestro-core:test`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Run the setter-count check.** From the worktree root:

```bash
grep -rn "new WorkflowExecutor(" maestro-core/src/test | wc -l          # expect 0
grep -rhoF "WorkflowExecutor.builder(" maestro-core/src/test --exclude=WorkflowExecutorBuilderTest.java | wc -l   # expect 95
for s in distributedLock messaging signalNotifier lockKeyPrefix instanceLockTtl shutdownTimeout wakeRecheckInterval observer taskQueueLimits defaultTaskQueueLimits instantSource; do
  printf "%s=%s " "$s" "$(grep -rhoE "\.$s\([^)]" maestro-core/src/test --exclude=WorkflowExecutorBuilderTest.java | wc -l | tr -d ' ')"; done; echo
grep -rhoF ".lifecycleEventsEnabled(false)" maestro-core/src/test --exclude=WorkflowExecutorBuilderTest.java | wc -l   # expect 16
```

Expected (baseline at `cbf5a2a` is 0 for every pattern):

```
distributedLock=19 messaging=45 signalNotifier=0 lockKeyPrefix=18 instanceLockTtl=22 shutdownTimeout=24 wakeRecheckInterval=34 observer=17 taskQueueLimits=4 defaultTaskQueueLimits=1 instantSource=1
```

A mismatch means a site dropped or invented an argument. Fix that site; never change the expectation.

- [ ] **Step 9: Commit Batch C** with the message `test(core): remaining engine tests build executors with the builder`.

---

### Task 4: Migrate the starter, sample and integration-harness tests (9 sites)

**Files:**
- Modify: `maestro-spring-boot-starter/src/test/java/io/b2mash/maestro/spring/config/{AdminCommandDispatcherTest, SignalSubscriptionRunnerTest, StartupRecoveryRunnerTest}.java`, `…/spring/health/MaestroHealthIndicatorTest.java`
- Modify: `maestro-samples/sample-order-service/src/test/java/io/b2mash/maestro/samples/order/controller/OrderControllerTest.java`, `maestro-samples/sample-loan-origination/loan-application-service/src/test/java/io/b2mash/maestro/samples/loan/application/workflow/LoanApplicationWorkflowV2Test.java`
- Modify: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/support/MaestroEngineHarness.java:71-101`

- [ ] **Step 1: Migrate the starter and sample sites** with the migration rule. Starter: 5 sites, no setters. Order sample: 1 site, no setters. Loan sample: 2 sites, each `.distributedLock(lock).messaging(messaging).signalNotifier(notifier)`.

- [ ] **Step 2: Migrate the harness.**
  - Build with `WorkflowExecutor.builder(builder.store, this.serializer, builder.serviceName)`, then `.distributedLock(builder.lock).messaging(builder.messaging).signalNotifier(builder.signalNotifier).lockKeyPrefix(builder.lockKeyPrefix).instanceLockTtl(builder.instanceLockTtl)`.
  - Call `.wakeRecheckInterval(builder.wakeRecheckInterval)` only when that value is non-null. Then `.observer(this.observer).taskQueueLimits(builder.taskQueueLimits).build()`.
  - Drop the spelled-out `true` and `Duration.ofSeconds(30)`. Delete `DEFAULT_WAKE_RECHECK_INTERVAL` and its comment (`:71-74`).
  - Reword the `:83-85` comment to: `// Everything the harness builder leaves unset takes WorkflowExecutor.Builder's default.`

- [ ] **Step 3: Run the tests and the assertion check.**

```bash
./gradlew --no-watch-fs :maestro-spring-boot-starter:test
./gradlew --no-watch-fs :maestro-samples:sample-order-service:test :maestro-samples:sample-loan-origination:loan-application-service:test
./gradlew --no-watch-fs :maestro-integration-tests:compileTestJava
./gradlew --no-watch-fs :maestro-integration-tests:test --tests '*TaskQueueConcurrencyIT' --tests '*MultiNodeTimerWakeIT'
git diff "$(git merge-base origin/main HEAD)" -U0 -- '*/src/test/*' ':!**/WorkflowExecutorBuilderTest.java' ':!**/MaestroAutoConfigurationConfigSeamsTest.java' \
  | grep -E '^[-+][^-+].*\b(assert[A-Za-z]*|assertThat|await)\(' ; echo "exit=$?"
```

Expected: every Gradle run `BUILD SUCCESSFUL`; the two ITs (which use the harness's `taskQueueLimits` and `wakeRecheckInterval`) pass. The `grep` prints nothing and `exit=1`, meaning no assertion line changed outside the two files that gain new tests.

- [ ] **Step 4: Commit** with the message `test: starter, samples and integration harness build executors with the builder`.

---

### Task 5: Remove the constructors; documentation

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java`
  - delete the ten public constructors and their Javadoc;
  - `:115-121` `DEFAULT_SHUTDOWN_TIMEOUT` Javadoc: "passed to the constructor" becomes "set with `Builder#shutdownTimeout`";
  - `:696-697` `instantSource()` Javadoc: "the seven- or 14-argument constructor was given another" becomes "`Builder#instantSource` was given another";
  - `:3191-3193` `emit` Javadoc: "the constructors accept" becomes "the builder accepts".
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/GatedWorkflowMessaging.java:33`: "the `messaging` constructor argument" becomes "the `messaging` it is built with".
- Modify: `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/observe/MaestroEngineGauges.java:16-17`: "handed to the executor's constructor" becomes "handed to the executor's builder".
- Modify: `maestro-integration-tests/SPEC.md:171`: "configurable through `WorkflowExecutor`'s full constructor" becomes "configurable through `WorkflowExecutor.Builder#wakeRecheckInterval`".
- Modify: `docs/open-issues.md`:
  - Issue 37: a status block `> **Resolved (<date>, branch refactor/executor-builder).**` naming the builder, the removal (D6) and `WorkflowExecutorBuilderTest`;
  - summary table row `:308`: `**Resolved**`;
  - Issue 36 "How to tackle it": "pass them to the executor constructor that takes default limits" becomes "pass them to `WorkflowExecutor.Builder#taskQueueLimits` / `#defaultTaskQueueLimits`".
- Modify: `docs/release-notes.md`: a new first subsection under `## Unreleased`.
- Not changed: `CLAUDE.md`, `docs/*.md` other than the two above. None of them names a constructor; checked with `grep -rn "WorkflowExecutor" CLAUDE.md docs/*.md`. Historical plan, task and evidence files are left alone.

- [ ] **Step 1: Delete the ten public constructors.** `private WorkflowExecutor(Builder)` remains the only constructor.

- [ ] **Step 2: Apply the Javadoc and doc edits** listed under Files. The release-notes subsection is `### Changed — WorkflowExecutor is built with a builder; its ten constructors are removed (Issue 37)`. It must state:
  - **Before:** ten public constructors chosen by argument count; swapped same-type arguments compiled.
  - **Now:** `WorkflowExecutor.builder(store, serializer, serviceName)`, one named setter per setting, and the default of each (the table above); `build()` validates as the constructors did, and null `serializer`, `serviceName`, `lockKeyPrefix` and `observer` now fail fast.
  - **Upgrade:** a direct core user replaces `new WorkflowExecutor(a1, …)` with the builder, one setter per argument; the starter and `TestWorkflowEnvironment` users change nothing.
  - Earlier entries in this Unreleased section that name constructor arities refer to the builder option of the same name.

- [ ] **Step 3: Verify that no constructor or stale mention remains.**

```bash
grep -rn "new WorkflowExecutor(" --include='*.java' . | grep -v /build/            # expect exactly 1: Builder.build()
grep -c "public WorkflowExecutor(" maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java   # expect 0
grep -rn -i "argument constructor\|executor's constructor\|full constructor\|short constructors\|executor constructor" --include='*.java' --include='*.md' maestro-* docs/open-issues.md | grep -v /build/ | grep -i executor   # expect 0 (at cbf5a2a it prints 3: MaestroEngineGauges:17, TestWorkflowEnvironment:125, open-issues:3291; release-notes history entries are left as written)
```

- [ ] **Step 4: Run the touched modules.** Run `./gradlew --no-watch-fs :maestro-core:test :maestro-core:javadoc :maestro-spring-boot-starter:test :maestro-test:test`. Expected: `BUILD SUCCESSFUL`, and no `javadoc` warning naming `WorkflowExecutor(`.

- [ ] **Step 5: Commit** with the message `refactor(core)!: remove WorkflowExecutor's public constructors (Issue 37, D6)`.

- [ ] **Step 6: Coordinator gate.** The coordinator runs `./gradlew --no-watch-fs build` on this commit. Expected: `BUILD SUCCESSFUL`. Then the branch goes to the whole-branch review, and the PR's gates (spec §6) follow: Build & Test, nightly E2E, chaos PR-gate ×3. No soak is required for WP0.

---

## Self-review (run 2026-11-12)

- **Spec coverage:**
  - builder: Task 1;
  - the 2 production sites: Task 2;
  - the 104 test sites (95 + 5 + 3 + 1): Tasks 3–4;
  - constructors removed (D6): Task 5;
  - "a builder test pins each default" (Issue 37, Done when): Task 1 `defaultsAreThe…`;
  - no behaviour change: Task 1 Steps 6–7 plus the Task 1 and Task 5 gates;
  - new surface listed first (spec §8): yes;
  - docs: Task 5.
- **Type consistency:** setter names equal the old constructor parameter names everywhere (`distributedLock`, `messaging`, `signalNotifier`, `lockKeyPrefix`, `instanceLockTtl`, `lifecycleEventsEnabled`, `shutdownTimeout`, `wakeRecheckInterval`, `observer`, `taskQueueLimits`, `defaultTaskQueueLimits`, `instantSource`).
- **Review Focus:** all five items have an owning test or check (Task 2 Step 1, Task 3 Step 8 / Task 4 Step 3, Task 1, Task 1, existing kit tests plus a Task 2 mutation check).
- **Proportion:** the only code blocks are test assertions and verification commands. The builder bodies are left to the implementer, because the signatures and the table settle them.
