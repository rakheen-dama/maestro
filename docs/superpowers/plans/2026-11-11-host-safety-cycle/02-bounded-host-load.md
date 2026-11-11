# Bounded Host Load Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Out of the box, Maestro's load on the host's database and connection pool is bounded and does not grow one statement at a time with the number of parked workflows.

**Architecture:** Four independent changes. Lock renewal and the recovery scan each gain a batch method on their SPI with a loop-based default, so third-party implementations keep working and the Postgres implementation does the work in one statement per batch. The task-queue governor gets a default limit for queues nobody configured. The Postgres auto-configurations resolve their `DataSource` through one helper that prefers a bean qualified `@MaestroDataSource`.

**Tech Stack:** Java 25, `maestro-core`, `maestro-store-jdbc`, `maestro-store-postgres` (Flyway migration V10), `maestro-spring-boot-starter`, JUnit 5, Testcontainers Postgres, `ApplicationContextRunner`.

**Spec:** `docs/superpowers/plans/2026-11-11-host-safety-cycle/00-spec.md` (R6–R10; D1, D2)

## Global Constraints

- `maestro-core` must never import Spring.
- New SPI methods are `default` methods whose default body is correct (if slow) for any implementation.
- Store migrations live in `maestro-store-postgres/src/main/resources/db/maestro/store`, band 1–99; the next free version is V10. Band 200–299 is retired.
- A migration's index must be optional for correctness: the build must work on a database without it (`schema.mode: none`), and the file follows V8's convention (`CREATE INDEX IF NOT EXISTS`, header comment on the SHARE lock, a `CONCURRENTLY` variant documented in `docs/configuration.md`).
- Rolling-deploy safe against a27a275: an old node and a new node must be able to share one database.
- `jakarta.*` not Jakarta-EE `javax.*`; `javax.sql.DataSource` is correct. JSpecify `@Nullable`. No Lombok. Javadoc + thread-safety note on public types.
- Auto-config ordering: prove any new conditional with a context test built from real `AutoConfigurations.of(...)`, never only `.withBean(...)` (`tasks/lessons.md`, 2026-08-03).
- Every new test is run against the unfixed code and seen to fail on a positive assertion.
- Work in a worktree on branch `feat/bounded-host-load`; commit at every green step.

## Review Focus

1. A held lock whose row another node has taken over is in the same renewal batch as 499 healthy ones: exactly that workflow is stood down, the 499 are renewed. (Task 1, test 2.)
2. The renewal statement itself fails (pool timeout): no lock is treated as lost, the pass is retried next interval, one WARN. (Task 1, test 4.)
3. Two instances share the same `started_at` across a page boundary: neither is skipped nor adopted twice. (Task 2, test 2.)
4. A workflow names a task queue that appears nowhere in configuration: it runs, bounded by the defaults, and the startup log says so. (Task 3, tests 1 and 4.)
5. A host declares a `@MaestroDataSource` bean without `defaultCandidate = false`, which makes Boot's own `DataSource` back off: startup fails or the docs warn — it must not silently run the host's JPA on Maestro's pool. (Task 4, test 4 and docs.)

---

### Task 1: Batched lock renewal

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/spi/DistributedLock.java`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowInstanceLockManager.java:284-330` (`renewLoop`, `renewOne`)
- Modify: `maestro-store-postgres/src/main/java/io/b2mash/maestro/store/postgres/lock/PostgresDistributedLock.java`
- Test: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/WorkflowInstanceLockManagerTest.java`
- Test: `maestro-store-postgres/src/test/java/io/b2mash/maestro/store/postgres/lock/PostgresDistributedLockTest.java`

**Interfaces:**
- Produces: `default Set<LockHandle> renewAll(Collection<LockHandle> handles, Duration ttl)` on `DistributedLock` — returns the handles that were **not** renewed because the lock is no longer held with that token (lost). Throws `LockBackendException` when the backend could not be asked; then nothing is known and nothing is lost. Default body loops `renew`.
- Postgres override: chunks of 500; per chunk one statement

```sql
UPDATE maestro_distributed_lock l
SET expires_at = now() + make_interval(secs => ?)
FROM unnest(?::text[], ?::text[]) AS h(lock_key, token)
WHERE l.lock_key = h.lock_key AND l.token = h.token AND l.expires_at > now()
RETURNING l.lock_key
```

  Lost = chunk handles whose key was not returned.
- `renewLoop` calls `renewAll` once per pass on a snapshot of `heldLocks.values()`; each lost handle takes the existing lost path of `renewOne` (remove from `heldLocks`, ERROR log, lock-lost callback). A pass that takes longer than `renewInterval` logs one WARN with the lock count and duration.

- [ ] **Step 1: Write the failing tests.**

```java
// PostgresDistributedLockTest
@Test void renewAllExtendsEveryHeldLockInOneStatementPerChunk()
// acquire 1,200 locks through a statement-counting DataSource; renewAll(handles, 30s).
// assert: returned set is empty; executed UPDATE statements == 3; every row's expires_at > its previous value.

@Test void renewAllReportsOnlyTheStolenLock()
// acquire 500; overwrite one row's token by SQL; assert renewAll returns exactly that handle;
// the other 499 rows' expires_at advanced.

@Test void renewAllReportsAnExpiredLockAsLost()
// set one row's expires_at to now() - 1s: returned set == {that handle}.

// WorkflowInstanceLockManagerTest (recording DistributedLock fake)
@Test void oneRenewAllCallPerPassNotOnePerLock()      // hold 50: after one interval renewAll calls == 1, renew calls == 0
@Test void lostHandleStandsOnlyThatWorkflowDown()     // fake returns {h7}: lost callback workflowIds == ["wf-7"]; heldLocks size 49
@Test void backendFailureLosesNothing()               // fake throws LockBackendException: lost callback never invoked; heldLocks size 50; next pass calls renewAll again
```

- [ ] **Step 2: Run** `./gradlew :maestro-core:test --tests '*WorkflowInstanceLockManagerTest*' :maestro-store-postgres:test --tests '*PostgresDistributedLockTest*'` — expect compile failure (`renewAll` undefined).
- [ ] **Step 3: Implement** the SPI default, the Postgres override, and the renew loop.
- [ ] **Step 4: Run** the same command plus `*PostgresDistributedLockContractTest*` — expect PASS.
- [ ] **Step 5: Commit** `perf(lock): renew held instance locks in one statement per 500`

### Task 2: Paged, service-scoped recovery scan

**Files:**
- Create: `maestro-core/src/main/java/io/b2mash/maestro/core/spi/RecoveryCursor.java`
- Create: `maestro-store-postgres/src/main/resources/db/maestro/store/V10__recovery_scan_index.sql`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/spi/WorkflowStore.java:122-131`, `DistributedLock.java`
- Modify: `maestro-store-jdbc/src/main/java/io/b2mash/maestro/store/jdbc/AbstractJdbcWorkflowStore.java:309-316`
- Modify: `maestro-store-postgres/.../lock/PostgresDistributedLock.java`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java:759-800` (`recoveryPass`), `WorkflowInstanceLockManager.java`
- Modify: `maestro-test/src/main/java/io/b2mash/maestro/test/InMemoryWorkflowStore.java:237`
- Test: `maestro-store-postgres/src/test/java/io/b2mash/maestro/store/postgres/PostgresWorkflowStoreTest.java`, `maestro-core/src/test/java/io/b2mash/maestro/core/engine/RecoveryPassIsolationTest.java`, `maestro-store-postgres/.../schema/` (migration count pin, if one exists)

**Interfaces:**
- Produces: `public record RecoveryCursor(Instant startedAt, UUID id)`.
- Produces on `WorkflowStore`: `default List<WorkflowInstance> getRecoverableInstances(String serviceName, @Nullable RecoveryCursor after, int limit)` — active instances whose `serviceName` equals the argument, ordered by `(startedAt, id)` ascending, strictly after the cursor, at most `limit`. Default body filters, sorts and slices the result of the no-arg method. The no-arg method stays and is documented as "unbounded; the engine no longer calls it".
- JDBC override: `WHERE service_name = ? AND status IN (active) AND (started_at, id) > (?, ?) ORDER BY started_at, id LIMIT ?` (omit the row comparison when the cursor is null).
- V10: `CREATE INDEX IF NOT EXISTS idx_wf_instance_recovery ON maestro_workflow_instance (service_name, started_at, id) WHERE status IN ('RUNNING', 'WAITING_SIGNAL', 'WAITING_TIMER', 'COMPENSATING');`
- Produces on `DistributedLock`: `default Set<String> heldKeys(Collection<String> keys)` — the subset currently held by anyone; advisory; default returns `Set.of()` ("unknown, try them all"). Postgres: `SELECT lock_key FROM maestro_distributed_lock WHERE lock_key = ANY(?) AND expires_at > now()`; on `SQLException` return `Set.of()`.
- Produces on `WorkflowInstanceLockManager`: `Set<String> heldElsewhere(Collection<String> workflowIds)` — maps ids to lock keys, calls `heldKeys`, maps back; excludes locks this node holds.
- `recoveryPass` loops pages of `RECOVERY_PAGE_SIZE = 200` using the executor's `serviceName`; per page drops ids in `runningWorkflows`, then ids in `heldElsewhere`, then attempts `resumeWorkflow` on the rest. `RecoveryPass.scanned` counts rows read.

- [ ] **Step 1: Write the failing tests.**

```java
// PostgresWorkflowStoreTest
@Test void recoveryScanReturnsOnlyThisServicesActiveRows()
// insert svc-a: 3 RUNNING + 1 COMPLETED; svc-b: 2 RUNNING. getRecoverableInstances("svc-a", null, 10)
// -> exactly the 3 svc-a active ids, ascending by (startedAt, id).

@Test void recoveryScanPagesWithoutSkippingOrRepeatingOnEqualStartedAt()
// 5 svc-a RUNNING rows sharing ONE started_at. page 1 = (null, 2); page 2 = (cursor of last, 2); page 3.
// assert: concatenated ids are 5 distinct ids == all inserted; page 3 has size 1; page 4 is empty.

// PostgresDistributedLockTest
@Test void heldKeysReturnsLiveLocksOnly()   // 2 live, 1 expired, 1 never taken -> exactly the 2 live keys

// RecoveryPassIsolationTest (counting store + recording lock fake)
@Test void passNeverCallsTheUnboundedScan()              // no-arg getRecoverableInstances calls == 0
@Test void passReadsEveryPage()                          // 450 active rows: paged calls == 4 (200,200,50,0 or 200,200,50); adopted == 450
@Test void instancesHeldElsewhereCostNoAcquire()         // heldKeys returns 100 of 150 keys: tryAcquire calls == 50
@Test void otherServicesInstancesAreNotAdopted()         // rows for "svc-b" present: adopted ids all belong to "svc-a"
```

- [ ] **Step 2: Run** `./gradlew :maestro-core:test --tests '*RecoveryPassIsolationTest*' :maestro-store-postgres:test --tests '*PostgresWorkflowStoreTest*' --tests '*PostgresDistributedLockTest*'` — expect compile failure.
- [ ] **Step 3: Implement** in this order: `RecoveryCursor`, SPI defaults, JDBC override, V10, Postgres `heldKeys`, lock manager, `recoveryPass`, `InMemoryWorkflowStore` override.
- [ ] **Step 4: Rolling-deploy check.** Run `PostgresWorkflowStoreTest`'s two new tests once against a schema migrated only to V9 (drop the index in the test's setup): expect PASS — the query needs no index to be correct.
- [ ] **Step 5: Run** `./gradlew :maestro-core:test :maestro-store-jdbc:test :maestro-store-postgres:test :maestro-test:test` — expect PASS. Update any pin that asserts the migration count or latest version.
- [ ] **Step 6: Commit** `perf(recovery): page the recovery scan by service; skip instances locked elsewhere (V10)`

### Task 3: Default concurrency bound

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/TaskQueueGovernor.java:83,119`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java:535` and the constructor that receives `taskQueueLimits`
- Modify: `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/config/MaestroProperties.java:437-446` (`WorkerProperties`), `MaestroAutoConfiguration.java` (where limits are passed to the executor)
- Create: `maestro-spring-boot-starter/src/main/resources/META-INF/additional-spring-configuration-metadata.json`
- Test: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/TaskQueueGovernorTest.java`, the starter's properties-binding test, the starter's auto-config context test

**Interfaces:**
- Produces: `public TaskQueueGovernor(Map<String, TaskQueueLimits> limits, @Nullable TaskQueueLimits defaults)` — a queue absent from `limits` gets its own pools sized from `defaults`, created on first use; `null` defaults keep today's unbounded behaviour. The one-argument constructor delegates with `null`. `UNBOUNDED` unchanged.
- Produces: `WorkerProperties(List<TaskQueueProperties> taskQueues, @DefaultValue("20") int defaultConcurrency, @DefaultValue("20") int defaultActivityConcurrency)`; properties `maestro.worker.default-concurrency`, `maestro.worker.default-activity-concurrency`. `0` on **both** means unbounded; `0` on exactly one, or a negative value, fails startup with a message naming both properties.
- Startup logs one INFO: `Maestro worker limits: default <n>/<m> per task queue (workflows/activities); configured: <name>=<c>/<a>, …`, or `unbounded (maestro.worker.default-concurrency=0)`.

- [ ] **Step 1: Write the failing tests.**

```java
// TaskQueueGovernorTest
@Test void unconfiguredQueueIsBoundedByDefaults()
// new TaskQueueGovernor(Map.of(), new TaskQueueLimits(2, 1)); acquire 2 workflow slots on "q";
// availableWorkflowSlots("q") == OptionalInt.of(0); a third acquire blocks until one is released.
@Test void twoUnconfiguredQueuesHaveSeparatePools()      // exhausting "q1" leaves availableWorkflowSlots("q2") == OptionalInt.of(2)
@Test void configuredQueueIgnoresDefaults()              // limits {"q": (5,5)}, defaults (2,1): available == 5
@Test void nullDefaultsStayUnbounded()                   // availableWorkflowSlots("q") is empty

// starter
@Test void defaultsBindTo20And20()
@Test void zeroOnBothMeansUnbounded()                    // executor's governor: availableWorkflowSlots("any") is empty
@Test void zeroOnOneFailsStartup()                       // context fails; message contains both property names
@Test void startupLogStatesEffectiveLimits()             // OutputCaptureExtension: contains "default 20/20 per task queue"
```

- [ ] **Step 2: Run** `./gradlew :maestro-core:test --tests '*TaskQueueGovernorTest*' :maestro-spring-boot-starter:test` — expect compile failure.
- [ ] **Step 3: Implement.** Use `ConcurrentHashMap.computeIfAbsent` for the lazily created pools; park-and-reacquire (`parked`) must work for them exactly as for configured queues.
- [ ] **Step 4: Write the metadata file** with hints for the two new properties and `deprecation` entries (level `error`) for the removed `maestro.store.table-prefix` and `maestro.messaging.topics.tasks`.
- [ ] **Step 5: Run** `./gradlew :maestro-core:test :maestro-spring-boot-starter:test :maestro-test:test` — expect PASS. `TestWorkflowEnvironment` keeps the unbounded governor (it constructs the executor directly); assert that with one existing kit test still green.
- [ ] **Step 6: Commit** `feat(worker): bound unconfigured task queues to 20/20 per node by default`

### Task 4: `@MaestroDataSource`

**Files:**
- Create: `maestro-store-postgres/src/main/java/io/b2mash/maestro/store/postgres/MaestroDataSource.java`
- Create: `maestro-store-postgres/src/main/java/io/b2mash/maestro/store/postgres/config/MaestroDataSources.java`
- Modify: `maestro-store-postgres/.../config/PostgresStoreAutoConfiguration.java:94,122`, `PostgresLockAutoConfiguration.java:69,75`, `PostgresSignalWakeAutoConfiguration.java:78,95`
- Test: `maestro-store-postgres/src/test/java/io/b2mash/maestro/store/postgres/config/MaestroDataSourceSelectionTest.java`

**Interfaces:**
- Produces: `@Target({FIELD, METHOD, PARAMETER, TYPE, ANNOTATION_TYPE}) @Retention(RUNTIME) @Documented @Qualifier public @interface MaestroDataSource {}` (the shape of Boot's `@FlywayDataSource`).
- Produces: `static DataSource MaestroDataSources.resolve(ObjectProvider<DataSource> qualified, ObjectProvider<DataSource> any)` — `qualified.getIfAvailable()`, else `any.getIfUnique()`, else throw `IllegalStateException("Maestro found <n> DataSource beans and none is @Primary or @MaestroDataSource. Annotate the one Maestro should use with @MaestroDataSource.")`.
- Each of the six bean methods takes `@MaestroDataSource ObjectProvider<DataSource> maestroDataSource, ObjectProvider<DataSource> dataSource` and calls `resolve`.

- [ ] **Step 1: Write the failing tests** (`ApplicationContextRunner` with real `AutoConfigurations.of(...)` for the three Maestro configs plus Boot's `DataSourceAutoConfiguration`, against Testcontainers Postgres; two `DataSource`s distinguishable by `currentSchema`):

```java
@Test void singleHostDataSourceIsUsed()                  // store, lock, cleaner, schema initializer, wake listener, notifier all hold the host bean
@Test void qualifiedDataSourceWinsForEveryMaestroBean()  // host primary + @MaestroDataSource @Bean(defaultCandidate = false): all six hold the qualified one;
                                                         // maestro_* tables exist only in the qualified schema; context.getBean(DataSource.class) is the host's
@Test void twoUnqualifiedDataSourcesFailWithGuidance()   // startup failure message contains "@MaestroDataSource"
@Test void qualifiedBeanWithoutDefaultCandidateFalseIsDocumentedBehaviour()
// @MaestroDataSource @Bean with no defaultCandidate=false and no other DataSource: Boot's auto-config backs off,
// context starts, Maestro uses it. Pins the behaviour the docs warn about.
```

- [ ] **Step 2: Run** `./gradlew :maestro-store-postgres:test --tests '*MaestroDataSourceSelectionTest*'` — expect compile failure.
- [ ] **Step 3: Implement** the annotation, the helper and the six injection points. Keep `@ConditionalOnBean(DataSource.class)` as is.
- [ ] **Step 4: Run** `./gradlew :maestro-store-postgres:test` — expect PASS.
- [ ] **Step 5: Commit** `feat(store): @MaestroDataSource gives the engine its own pool`

### Task 5: Docs and the capacity statement

**Files:**
- Modify: `docs/configuration.md` (worker defaults; "A dedicated connection pool" with the `@MaestroDataSource @Bean(defaultCandidate = false)` snippet and the warning that omitting `defaultCandidate = false` makes Boot's own `DataSource` back off; V10 added to the "Upgrading an existing database" list with its `CONCURRENTLY` line)
- Modify: `docs/operations.md` (new "Capacity" section), `docs/failure-scenarios.md` (S2.3 architecture note), `docs/open-issues.md` (Issue 12 section: renewal cost is now per batch; note Issue 30 is still open and is now the default path), `docs/release-notes.md` (Unreleased, with an **Upgrade** paragraph), `CLAUDE.md` (SPI block gains the three default methods; V1–V10; the two properties; `@MaestroDataSource`), `README.md` configuration snippet if it shows worker settings

The "Capacity" section states, as arithmetic and labelled as such, per node: statements per renewal pass = ⌈held locks / 500⌉ every 10 s; recovery pass = ⌈active instances of this service / 200⌉ reads + 1 lock read per page + 1 acquire per unowned unlocked instance every `maestro.recovery.poll-interval`; per parked workflow 2–3 reads every `maestro.signal.wake-recheck-interval` (unchanged by this cycle); default concurrency 20/20 per queue per node and the sizing rule "pool size ≥ default-activity-concurrency + 5 if Maestro has its own pool". The **Upgrade** paragraph states D1 (renaming `maestro.service-name` strands active workflows until restored) and D2 (previously unbounded; set both defaults to `0` to restore).

- [ ] **Step 1: Write the docs.**
- [ ] **Step 2: Grep check:** `grep -rn "unbounded" docs README.md CLAUDE.md | grep -i "queue"` — every hit agrees with the new default.
- [ ] **Step 3: Run** `./gradlew build` — expect `BUILD SUCCESSFUL`.
- [ ] **Step 4: Commit** `docs: capacity section, worker defaults, dedicated pool, V10 upgrade notes`

## Gate before the PR

`./gradlew build` green; loan-origination E2E 1–10 and chaos PR-gate ×3 green on a dispatched `e2e-nightly.yml` run on the branch (the chaos harness is the proof that bounded defaults and paged recovery do not slow adoption below its invariants' deadlines — if an invariant deadline trips, raise the question, do not raise the deadline); `code-reviewer` agent on the branch diff.
