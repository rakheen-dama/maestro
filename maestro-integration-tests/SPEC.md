# maestro-integration-tests — Build Contract

**Single source of truth for every agent writing integration suites.**
Deviations require updating this file *first*. Binding plan: `docs/test-plan.md`
(phases P0–P6). Repo conventions in `CLAUDE.md` are mandatory. Process rules in
`tasks/lessons.md` are mandatory.

## Purpose

Prove Maestro's features against **real backends** — Postgres and Kafka are
must-work; Valkey is best-effort. Every feature row in the `docs/test-plan.md`
matrix that lacks an **I** marker for a must-work path gets one here, in CI,
green.

This module is **not published**. It contains no production code — everything
lives under `src/test/java`.

## Module wiring (fixed — do not change without updating this file)

- Gradle module `:maestro-integration-tests`, registered in `settings.gradle.kts`.
- Convention plugin `maestro.integration-test-conventions` (in `build-logic`):
  applies `maestro.java-conventions` + the Spring Boot BOM, **not**
  `library-conventions` (nothing is published or signed).
- `test` task → **excludes** `@Tag("e2e")`; everything else in the module runs.
  (It does not `includeTags("integration")` — an untagged class should run and
  be noticed, not be silently skipped.) Runs on every PR through the existing
  `./gradlew build` in `.github/workflows/build-test.yml`.
- `e2eTest` task → runs `@Tag("e2e")` only. Never wired into `build`/`check`.
- Root convenience: `./gradlew :maestro-integration-tests:test`.

## Naming and layout (pinned)

```
io.b2mash.maestro.integration
├── support/     — shared fixtures (coordinator-owned; ask before editing)
├── workflows/   — deterministic workflow + activity fixtures (coordinator-owned)
├── schema/      — migration-composition suites            [scaffold]
├── engine/      — P0: engine × Postgres
├── kafka/       — P1: Kafka in CI
├── multinode/   — P2: two-node topology
├── backends/    — P3: lock-postgres + messaging-postgres
└── e2e/         — P4: loan-origination E2E (@Tag("e2e"))
```

- Every class is `@Tag("integration")` (or `@Tag("e2e")` in `e2e/`).
- Class names end in **`IT`** (e.g. `EnginePostgresLifecycleIT`).
- Test-method style follows `PostgresWorkflowStoreTest`: `@DisplayName` full
  sentence on class and method; method names `subjectUnderTest_expectation`.
- Assertions: **plain JUnit** `org.junit.jupiter.api.Assertions.*` (the repo does
  not use AssertJ outside Spring context-runner tests, which may use it).
- Waiting: **Awaitility only**. No `Thread.sleep` as synchronisation — the one
  permitted exception is letting a Kafka listener container get its partition
  assignment, mirroring `KafkaWorkflowMessagingTest`.

## Shared fixtures — the API every suite uses

Owned by the coordinator. If a suite needs a change here, request it; do not
fork a private copy.

### `support.PostgresIntegrationSupport` (abstract base class)

Mirrors `PostgresTestSupport` from `maestro-store-postgres`:

```java
@Testcontainers @Tag("integration")
abstract class PostgresIntegrationSupport {
    static final PostgreSQLContainer<?> postgres;   // postgres:16-alpine, shared, static
    protected PGSimpleDataSource dataSource;
    protected PostgresWorkflowStore store;
    protected ObjectMapper objectMapper;            // tools.jackson JsonMapper.builder().build()
    protected PayloadSerializer serializer;

    protected PostgresDistributedLock newLock();    // lock backend over the same DataSource
    protected void truncateAll() throws SQLException;
}
```

- Flyway migrates `classpath:db/migration` **once per container**, guarded by a
  static flag — this applies the store (V1–V99), lock (V100–V199) and messaging
  (V200–V299) bands together. Version bands are pinned by
  `schema.MaestroMigrationsCoexistIT`; never reuse another module's band.
- `@BeforeEach` truncates every `maestro_*` table for isolation. The container
  is **never** restarted between tests.

### `kafka.KafkaSpringIntegrationSupport` — the Kafka base

The canonical base for any suite needing Kafka: a real Spring Boot application
wired to a Testcontainers broker **and** Postgres store. It extends
`PostgresIntegrationSupport` and owns its broker, because Java has no multiple
inheritance.

Both container fixtures start from a **static initialiser**, never via
`@Testcontainers`/`@Container`. That extension stops a static container when its
test *class* ends, so an inherited container is recreated per subclass — and a
cached `@SpringBootTest` context then holds factories bound to a dead broker.
Flyway likewise runs in the static initialiser, because Spring refreshes the
context (and `StartupRecoveryRunner` queries `maestro_workflow_instance`)
*before* any `@BeforeEach` runs.

`confluentinc/cp-kafka:7.7.1` in KRaft mode. **Topics are never auto-created**
(repo rule) — pre-create them from `@BeforeAll`, which JUnit runs before the
Spring context loads.

### `support.MaestroEngineHarness` — the engine-under-test seam

Builds a **real** `WorkflowExecutor` over whatever SPIs a suite supplies, wires
`@ActivityStub` fields through the real `ActivityProxyFactory`, and resolves
`@WorkflowMethod` — i.e. it does what `TestWorkflowEnvironment` does, but
against real backends. `maestro-test`'s in-memory environment must **not** be
used as the subject of an integration assertion.

```java
var harness = MaestroEngineHarness.builder(store, objectMapper)
        .serviceName("node-a")
        .lock(lock)                       // optional
        .messaging(messaging)             // optional
        .signalNotifier(notifier)         // optional
        .instanceLockTtl(Duration.ofSeconds(2))   // short TTL for contention tests
        .build();

harness.registerActivities(GreetActivities.class, activityImpl);
harness.registerWorkflow(new ChainWorkflow());        // instance, so tests can hold counters
var handle = harness.start("wf-1", ChainWorkflow.class, input);
handle.awaitTerminal(Duration.ofSeconds(10));
assertEquals(WorkflowStatus.COMPLETED, handle.status());
```

Key methods: `start`, `deliverSignal`, `fireDueTimers`, `startTimerPoller`,
`startRecoveryPoller`, `recover()`, `executor()`, `close()` (calls `shutdown()`).
`WorkflowHandle` exposes `status()`, `instance()`, `events()`, `result(Class)`,
`awaitStatus(...)`, `awaitTerminal(...)`.

**Multi-node rule:** a second node is a second `MaestroEngineHarness` with a
different `serviceName` over the **same** `store`/`lock`. Never share a harness.

### `workflows.*` — deterministic workflow fixtures

Small, deterministic workflow classes shared by P0–P2. Activity implementations
count invocations so that *replay must not re-execute* is directly assertable.
Covered shapes: activity chain, `awaitSignal`, `collectSignals`, `sleep`,
parallel branches, saga with compensation, failing activity with retry.

## Timing rules (flake discipline)

- The engine has **no injectable clock** — `sleep()`/timers use real wall-clock
  time. Use short durations (≤ 1s) and a fast `TimerPoller` (`Duration.ofMillis(200)`).
- Awaitility bounds: **generous** (5–15s), poll interval short. A generous bound
  on a fast condition is not slow; it is what makes CI stable.
- `SignalManager`'s parked-workflow re-check interval is a hardcoded **30s** and
  is **not reachable** from `WorkflowExecutor`'s public API (only a
  package-private `SignalManager` constructor takes it). Any test that depends
  on the no-notifier re-check path must therefore either supply a
  `SignalNotifier` or get a library seam added first — see *Open items*.
- A phase is done only when its suites pass **3 consecutive** `--rerun-tasks` runs.

## Library-bug protocol

If a suite exposes an engine defect: reproduce it FIRST as a failing test in the
**owning library module**, then fix the library, then continue. Never mask a
proven engine bug inside a test suite. Where no single module owns the defect
(it only appears when modules are composed), the regression test lives here and
the fix goes to the module that must change — as was done for the Flyway
version-band collision.

Bugs found so far:
- **BUG5 (fixed):** `maestro-lock-postgres` and `maestro-messaging-postgres` both
  shipped `V100`; Flyway aborts with *"Found more than one migration with
  version 100"*, so the Postgres-only profile could not migrate. Fixed by
  disjoint version bands; pinned by `schema.MaestroMigrationsCoexistIT`.
- **BUG6 (fixed, P2):** `PostgresNotificationListener.listen()` only *queued*
  the `LISTEN`; it was executed later on the polling thread. Postgres delivers
  a `NOTIFY` only to sessions already listening, so every cross-node signal
  published in that window (up to one 500ms poll cycle) was lost outright and
  the parked workflow stalled until `SignalManager`'s 30s store re-check —
  which `SignalWorkflow`'s 30s await timeout then raced. `SignalManager`
  re-checks the store straight after subscribing precisely to close this race,
  a guard that was worthless against an asynchronous subscribe. Fixed by making
  `listen()` block until the command has been executed; reproduced by
  `PostgresSignalNotifierTest.publishImmediatelyAfterSubscribe_isDelivered`,
  pinned end-to-end by `multinode.MultiNodeSignalRoutingIT`.
- **BUG7 (fixed, P2):** `WorkflowExecutor` finalised a workflow by writing
  `version + 1` from an earlier read. Any concurrent writer of that row — a
  second node running the workflow (no-lock-backend degradation, a lock lost
  mid-run, a stale lock on a fresh start), or another status transition on this
  node — made that write throw `OptimisticLockException`, which fell into the
  generic `catch (Exception)` for *workflow* failures. A workflow that had
  succeeded was recorded `FAILED`, its output replaced by the conflict message,
  contradicting its own `WORKFLOW_COMPLETED` event, and a saga's compensations
  ran after a successful run. Fixed with a convergent terminal transition
  (bounded retry against a fresh read; stand down if another runner already
  reached a terminal state). Reproduced by
  `core.engine.WorkflowExecutorTerminalTransitionTest`, pinned end-to-end by
  `multinode.MultiNodeNoLockBackendIT`.

## Open items (decide before the phase that needs them)

1. **Wake re-check seam (blocks part of P2).** To test cross-node wake without a
   notifier in bounded time, `WorkflowExecutor` needs to expose the
   `SignalManager` wake-recheck interval (and the starter a
   `maestro.signal.wake-recheck-interval` property). This is a small, legitimate
   library improvement — the value currently also bounds production cross-node
   signal latency for Kafka-without-Valkey deployments. Raise it in P2.
2. **Ack-on-failure (P1).** Transport adapters ack even when the handler throws.
   Write the contract test RED and `@Disabled("known defect — tasks/todo.md")`
   unless a redelivery design with bounded retries + DLT lands.
3. **Shutdown contract (P5).** `shutdown()` unparks every running workflow and
   marks parked ones FAILED (with compensation). The desired-behaviour tests are
   the spec; the fix is pre-approved.
