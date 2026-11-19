# CLAUDE.md — Maestro Project Context

## What Is Maestro

Maestro is an **open-source, embeddable durable workflow engine** delivered as a Spring Boot Starter. It provides Temporal.io-grade workflow durability without a central server, using infrastructure teams already operate: Postgres and Kafka.

**One-sentence pitch:** Add a Spring Boot Starter to your microservice and get durable, crash-recoverable workflows using your existing database and message broker.

## Architecture Docs

Read these before making architectural decisions:
- `docs/maestro-prd.md` — Product requirements, API design, e-commerce example
- `docs/maestro-architecture.md` — System architecture, diagrams, failure modes
- `docs/observability.md` — Meter catalog, span topology, Kafka trace-propagation contract
- `docs/support-matrix.md` — What is supported, not supported and untested (one service per set of tables, mandatory lock, Postgres-only store, previous-release rolling upgrades, tested Postgres/pool/Kafka setups)
- `docs/operations.md` — Multi-instance behaviour; versioning + mixed-version deploy playbook
- `docs/pitfalls.md` — The thirteen workflow-author rules (saga throws, `causedBy`, idempotency, determinism, owning-node queries, sequence budget, versioning, per-run fields, signal ids, control-flow `Error`s, no same-queue waits in activities, results arrive as the store returns them, no workflow operations inside an activity)

## Core Design: Hybrid Memoization

1. Workflow method runs on a **Java 25 virtual thread**.
2. Activity calls intercepted by a **proxy**. Proxy checks Postgres for stored result at current **sequence number**.
3. **Replay (found):** Return stored result instantly — no execution.
4. **Live (not found):** Execute activity, persist result, return it **as read back from the persisted payload** (deserialized into the method's declared return type, exactly as a replay reads it — not the activity's own object). Three exceptions return the activity's own object and still differ on replay (`docs/pitfalls.md` rule 12): a result that cannot be read back (stands down on replay), one that reads back as another class (the type valve: supertype/`Object`/type-variable declarations; a same-kind container — both `List`, `Set`, `Queue` or `Map` — is not a difference), and a `Future`/`CompletionStage` return type (never read back live). The first two are reported via `EngineObserver.activityResultUnreadable` and `maestro.activity.result.unreadable`.
5. **Recovery:** Re-invoke workflow method. Completed steps replay instantly. Resumes from first uncompleted step.

**Determinism constraint:** Code between activity calls must be deterministic. No `Math.random()`, `LocalDateTime.now()`, `UUID.randomUUID()`, or direct I/O. Use `workflow.currentTime()`, `workflow.randomUUID()`.

**Parallel branches** partition the sequence space: branch *i* of a fork at parent seq `p` allocates from base `p*1000 + (i+1)*1000` (≤999 steps per branch; the 1000th step fails the branch with `BranchSequenceExhaustedException`). When it PARKS, `awaitSignal` (and likewise `sleep()`) occupies TWO slots (TIMER_SCHEDULED memo at seq, outcome at seq+1); an already-arrived signal or a non-positive timeout writes only the outcome at seq. The per-branch budget stays 999 steps.

**Workflow beans are prototype-scoped:** `@DurableWorkflow` beans get one workflow object per run, so instance fields are per-run state; `@QueryMethod` answers only on the node that owns the run. Sequence/replay mutators live on `WorkflowContextInternals`, not the public `WorkflowContext`.

## Tech Stack

| Component | Technology | Version |
|---|---|---|
| Language | Java | 25+ (virtual threads required) |
| Framework | Spring Boot | 4.x (Spring Framework 7, Jakarta EE 11) |
| Build | Gradle | Kotlin DSL, Gradle 9 |
| Database | PostgreSQL | 14+ |
| Messaging | Apache Kafka | via Spring Kafka 4.x |
| Lock | PostgreSQL tables (same DB as the store) | `maestro_distributed_lock`, `maestro_leader_election` |
| Serialization | Jackson 3 | `tools.jackson` packages (NOT `com.fasterxml.jackson`) |
| Schema migration | Flyway | 11.x |
| Null safety | JSpecify | `@Nullable`, `@NonNull` |
| Testing | JUnit 5, Testcontainers 2.0 | |
| Admin UI | Thymeleaf + HTMX | |

### Spring Boot 4 Specifics

- **Starters renamed:** Use `spring-boot-starter-webmvc` (not `spring-boot-starter-web`). Use modular starters.
- **Jackson 3:** Packages moved from `com.fasterxml.jackson` → `tools.jackson`. All serialization code uses Jackson 3 APIs.
- **Jakarta EE 11:** All `javax.*` replaced with `jakarta.*`. Servlet 6.1 baseline.
- **Spring Framework 7 retry:** Built-in `@Retryable` and `@ConcurrencyLimit`. Evaluate leveraging this for activity retries.
- **JSpecify null safety:** Use `@Nullable` and `@NonNull` annotations consistently. Spring 7 enforces these.
- **No Undertow:** Dropped in Spring Boot 4. Use Tomcat (default) or Jetty.
- **Modular auto-configuration:** `spring-boot-autoconfigure` is no longer a public dependency. Use starters.

## Module Structure

```
maestro/
├── maestro-bom                     ← Platform (BOM) aligning all Maestro module versions.
├── maestro-core                    ← Pure Java. NO Spring. Engine, memoization, timers, signals, saga.
├── maestro-spring-boot-starter     ← Auto-config, annotations, bean proxying, config binding.
├── maestro-store-jdbc              ← Abstract JDBC WorkflowStore SPI.
├── maestro-store-postgres          ← Postgres WorkflowStore + DistributedLock + LISTEN/NOTIFY signal wake; Flyway 11 migrations.
├── maestro-messaging-kafka         ← Spring Kafka 4.x WorkflowMessaging SPI (the only transport).
├── maestro-admin-client            ← Plain AdminEventPublisher class only; no auto-config, no service needs it (Issue 52).
├── maestro-admin                   ← Standalone dashboard (Thymeleaf + HTMX, own Postgres).
├── maestro-test                    ← In-memory SPIs, controllable clock, TestWorkflowEnvironment (crash/recover a node to exercise replay).
├── maestro-samples/
│   ├── sample-order-service        ← Order fulfilment workflow (e-commerce demo)
│   ├── sample-payment-gateway      ← Payment processing with durable retries & saga
│   ├── sample-perf                 ← Load-test target for perf/ (k6); unpublished, engine defaults
│   └── sample-loan-origination     ← Multi-service loan E2E (application/underwriting/verification), nightly CI
└── docs/
```

**Critical:** `maestro-core` must NEVER depend on Spring. All Spring integration lives in `maestro-spring-boot-starter`.

## Three SPIs

`WorkflowStore` is **internal until 1.0** (Postgres is the only supported store; methods and defaults change without deprecation). A `DistributedLock` is **mandatory**: `WorkflowExecutor.Builder.build()` and the starter fail without one.

```java
public interface WorkflowStore {
    WorkflowInstance createInstance(WorkflowInstance instance);
    Optional<WorkflowInstance> getInstance(String workflowId);
    List<WorkflowInstance> getRecoverableInstances();     // unbounded, all services; the engine no longer calls it
    default List<WorkflowInstance> getRecoverableInstances(String serviceName, @Nullable RecoveryCursor after, int limit); // keyset page on (started_at, id), this service only (V101 index)
    default Map<String, Long> getActiveInstanceCountsUnderOtherServices(String serviceName, int limit); // shared-table startup check (P3): active rows of ANY other service, any type; default empty
    void updateInstance(WorkflowInstance instance);       // optimistic locking
    boolean completeInstance(WorkflowInstance terminal, WorkflowEvent terminalEvent); // atomic terminal write (CAS + event in one transaction)
    default void reopenFailedInstance(WorkflowInstance reopened, Set<Integer> deleteEventSequences, Set<String> deleteTimerIds); // manual Retry: exact deletes + FAILED→RUNNING CAS, one transaction in JDBC; the default is today's non-atomic two-step

    void appendEvent(WorkflowEvent event);
    default @Nullable JsonNode appendEventReturningPayload(WorkflowEvent event, RunLease lease); // the payload as stored (Postgres: INSERT … RETURNING); a live activity returns what replay reads; a store decorator must forward it (the default returns the payload sent, not the jsonb form)
    Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int seq);
    List<WorkflowEvent> getEvents(UUID instanceId);
    int deleteFailureEvents(UUID instanceId);            // the pre-WP3 Retry's delete; reopenFailedInstance's default calls it

    void saveSignal(WorkflowSignal signal);
    List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName);
    boolean markSignalConsumed(UUID signalId);           // CAS: false if already consumed
    void adoptOrphanedSignals(String workflowId, UUID instanceId);
    int deleteUnconsumedSignalsForTerminalInstancesBefore(Instant cutoff); // COMPLETED/TERMINATED only, every service; the engine no longer calls it
    default int deleteUnconsumedSignalsForTerminalInstancesBefore(String serviceName, Instant cutoff, int limit); // janitor batch, this service only; default = the unscoped, unbounded one
    PurgeResult purgeTerminalInstancesBefore(WorkflowStatus status, Instant cutoff, int batchSize); // one batch = one transaction, children before parent; every service
    default PurgeResult purgeTerminalInstancesBefore(String serviceName, WorkflowStatus status, Instant cutoff, int batchSize); // retention sweeper, this service only; default = unscoped

    void saveTimer(WorkflowTimer timer);
    List<WorkflowTimer> getDueTimers(Instant now, int batchSize);
    default List<WorkflowTimer> getDueTimers(int batchSize); // due by the store's clock (Postgres: fire_at <= now()); the timer poller calls this
    default Optional<Instant> storeTime(); // the store's clock, for the skew WARN; empty = no clock
    Optional<WorkflowTimer> findTimer(UUID workflowInstanceId, String timerId); // any status; replay self-heal
    boolean markTimerFired(UUID timerId);                 // CAS: false if already fired/cancelled
    boolean markTimerCancelled(UUID timerId);             // CAS: false if already fired/cancelled
    // plus default RunLease-fenced overloads of updateInstance, completeInstance, appendEvent,
    // markSignalConsumed, saveTimer, markTimerFired and markTimerCancelled; saveSignalIfAbsent; appendEventAndConsumeSignal
}

public interface WorkflowMessaging {
    void publishSignal(String serviceName, SignalMessage message);
    void publishLifecycleEvent(WorkflowLifecycleEvent event);
    void subscribeSignals(String serviceName, Consumer<SignalMessage> handler);
}

public interface DistributedLock {
    Optional<LockHandle> tryAcquire(String key, Duration ttl);
    void release(LockHandle handle);
    boolean renew(LockHandle handle, Duration ttl);       // false: lost (expired, re-acquired or released)
    default Set<LockHandle> renewAll(Collection<LockHandle> handles, Duration ttl); // returns lost; throw = nothing lost (Postgres: 1 UPDATE per 500)
    default Set<String> heldKeys(Collection<String> keys);  // advisory; empty = unknown, try them all
    boolean trySetLeader(String electionKey, String candidateId, Duration ttl);
}
```

## Package Naming

```
io.b2mash.maestro.core                     — Core engine, domain, SPIs
io.b2mash.maestro.core.annotation          — @DurableWorkflow, @Activity, etc.
io.b2mash.maestro.core.engine               — WorkflowExecutor, ActivityProxy, MemoizationEngine
io.b2mash.maestro.core.model                — WorkflowInstance, WorkflowEvent, WorkflowSignal, WorkflowTimer
io.b2mash.maestro.core.saga                 — SagaManager, CompensationStack
io.b2mash.maestro.core.context              — WorkflowContext (sleep, awaitSignal, parallel, etc.)
io.b2mash.maestro.core.spi                  — WorkflowStore, WorkflowMessaging, DistributedLock
io.b2mash.maestro.core.retry                — RetryPolicy, RetryExecutor
io.b2mash.maestro.core.exception            — MaestroException hierarchy + sealed MaestroControlFlowError
io.b2mash.maestro.core.observe              — EngineObserver SPI, *Info records, StandDownReason,
                                              AbandonReason, ParkKind, TraceContextHolder (framework-free)

io.b2mash.maestro.spring                    — Spring Boot auto-configuration
io.b2mash.maestro.spring.annotation         — @MaestroSignalListener
io.b2mash.maestro.spring.config              — MaestroAutoConfiguration, MaestroProperties
io.b2mash.maestro.spring.proxy               — ActivityStubBeanPostProcessor
io.b2mash.maestro.spring.health              — MaestroHealthIndicator
io.b2mash.maestro.spring.observe             — MicrometerEngineObserver, MaestroEngineGauges,
                                               TracingEngineObserver, MaestroObservabilityAutoConfiguration
io.b2mash.maestro.spring.client              — MaestroClient

io.b2mash.maestro.store.jdbc                — Abstract JDBC WorkflowStore
io.b2mash.maestro.store.postgres             — Postgres impl + Flyway migrations
io.b2mash.maestro.store.postgres.lock        — PostgresDistributedLock (maestro_distributed_lock, maestro_leader_election)
io.b2mash.maestro.store.postgres.notify      — PostgresSignalNotifier + PostgresWakeListener (LISTEN/NOTIFY cross-node wake)

io.b2mash.maestro.messaging.kafka            — Kafka WorkflowMessaging
io.b2mash.maestro.messaging.kafka.listener   — @MaestroSignalListener processing

io.b2mash.maestro.admin                      — Dashboard app
io.b2mash.maestro.admin.client               — Event publisher

io.b2mash.maestro.test                       — TestWorkflowEnvironment, in-memory SPIs

io.b2mash.maestro.samples.order              — Sample order service (e-commerce demo)
io.b2mash.maestro.samples.payment            — Sample payment gateway (e-commerce demo)
```

## Database Tables

Prefix `maestro_` (fixed; `maestro.store.table-prefix` was removed). Migrations live in maestro-store-postgres under `db/maestro/store` (V1–V9, V101) and `db/maestro/lock` (V100). **Every new Maestro migration, store or lock, takes the next version above the highest shipped, within 101–199; versions 1–9 and 100 are history; band 200–299 (the removed Postgres messaging transport) stays retired and must never be reused** (orphan-table upgrade note: `docs/configuration.md` § Schema management). The directory says which component a migration belongs to; the version number does not. Why: every build since PR #36 resolves V100, and to it an applied migration it does not ship is "future" (ignored, the node starts) above V100 but "missing" (startup fails) below it; `MaestroSchemaInitializerTest.migrationVersionsFollowTheNumberingRule` guards the rule. They run through `MaestroSchemaInitializer` into Maestro's own history table `maestro_schema_history` (`maestro.store.schema.mode` = flyway | validate | none); the host's `flyway_schema_history` is untouched. Flyway runs with `outOfOrder(true)`, which is load-bearing: databases that recorded V100 before V6–V9 (Issue 35), and databases holding any of the retired V200–V202 rows, where V101 and every future migration sort below the retired rows, apply them only out of order. The migrations shipped at `cbf5a2a` and at release `v0.3.0-rc.1` (both V1–V9, V100, V101; fixtures `upgrade/cbf5a2a`, `upgrade/v0_3_0_rc_1`) are frozen (`ShippedMigrationsFrozenTest` fails if one is edited, renamed or deleted); a migration added after `v0.3.0-rc.1` (V102 onward) is not protected until the release that ships it has its migrations fixture captured (`RELEASING.md` § Upgrade fixtures). Tables:

- `maestro_workflow_instance`
- `maestro_workflow_event` (unique on `workflow_instance_id, sequence_number`)
- `maestro_workflow_timer`
- `maestro_workflow_signal` (`workflow_instance_id` nullable for pre-delivery; unique on `workflow_id, signal_name, message_id` where `message_id` is not null, V9)
- V101 adds `idx_wf_instance_recovery` (partial, active statuses) for the paged recovery scan — index-only: the query works without it; under `flyway` the first node records it, under `validate` it must be recorded before a node of this build starts
- `maestro_distributed_lock`, `maestro_leader_election` (lock band; no task, lifecycle or signal queue tables)

## State Machine

```
RUNNING → WAITING_SIGNAL | WAITING_TIMER | COMPLETED | FAILED | COMPENSATING
WAITING_SIGNAL → RUNNING (signal/timeout)
WAITING_TIMER → RUNNING (timer fires)
COMPENSATING → FAILED
FAILED → RUNNING (manual retry)
Any active → TERMINATED
```

## Self-Recovery

Signals are persisted immediately. Three cases:
1. Signal before `awaitSignal()` → stored, consumed when reached.
2. Signal before workflow starts → stored with null instance, adopted on start.
3. Signal while service is down → persisted, found on recovery.

**Never discard a signal.** A signal carries a message id; duplicates are suppressed — at most one row per `(workflowId, signalName, messageId)` (`WorkflowStore.saveSignalIfAbsent`), ids namespaced by source (`env:`, `hdr:<topic>:`, `kafka:<topicId>:<p>:<o>`, `client:`). A signal without an id is never deduplicated. Consuming is atomic: the `SIGNAL_RECEIVED` memo and the `consumed` flip are one transaction (`WorkflowStore.appendEventAndConsumeSignal`); a consumer that loses the row writes nothing and moves to the next row or parks.

## Cross-Service Model

Orchestration within, choreography between. Each service owns its state. Kafka events → `@MaestroSignalListener` → workflow signals.

## Lock Keys

Locks are rows in `maestro_distributed_lock` / `maestro_leader_election` (`lock_key` column; prefix from `maestro.lock.key-prefix`, default `maestro:lock:`):

```
maestro:lock:workflow:{workflowId}           — Instance lock (30s TTL, renewed every 10s)
maestro:leader:timer-poller:{service}         — Timer leader in maestro_leader_election (15s TTL)
maestro:leader:retention:{service}            — Retention sweeper leader in maestro_leader_election (15s TTL, re-elected per batch)
```

There is no per-activity lock (3.10). A lock is mandatory and no run executes without its instance lock (P3): a start whose acquire fails (held elsewhere, or the backend threw) is persisted `RUNNING`, reported (`workflowStartDeferred`, `maestro.workflow.start.deferred`) and run by a prompted recovery pass — or, when this node runs no recovery poller, refused with `LockAcquisitionException` before anything is written; a resume is skipped. Every release and lost-lock stand-down goes by the run's own `LockHandle`, never by workflow id, so a run that lost its lock cannot release or stand down a newer run of the same id. So two runners execute a step concurrently only in the window after a node lost its instance lock; lease fencing (V6) rejects the stale runner's outcome append, so the stale runner records nothing, and the adopting node, finding no recorded outcome, runs the step again — so activities must be idempotent. A duplicate Kafka delivery is deduplicated by the signal's message id (V9), not by a lock.

Due timers are polled on the database clock (`fire_at <= now()`); `fire_at` and await deadlines are armed on the scheduling node's clock, and every node WARNs when its clock is more than 1 s from the database's (checked at timer-poller start and every 10 min).

Cross-node signal wake is Postgres `LISTEN/NOTIFY` on ONE channel per service, `maestro_signal_wake_<service ≤32 chars>_<8-hex sha256>` (always ≤63 bytes); the payload carries workflowId + signalName and `PostgresWakeListener` dispatches in memory to the parked waiter. `NOTIFY` runs on a virtual thread after the signal row commits and never blocks `deliverSignal`. The listener holds one `DataSource` connection for the app lifetime; at shutdown its poll thread `UNLISTEN`s and returns it to the pool (no interrupt, Issue 38); `maestro.signal.wake-recheck-interval` (30s) is the correctness fallback. Maestro's Postgres beans use the `DataSource` qualified `@MaestroDataSource` (`io.b2mash.maestro.store.postgres`) if one exists, else the host's single/`@Primary` one; declare it `@Bean(defaultCandidate = false)` when the host keeps its own `DataSource` (`docs/configuration.md` § A dedicated connection pool).

Recovery reads only this node's service (`service_name = maestro.service-name`, pages of 200) and skips instances locked elsewhere via at most one `heldKeys` read per page (none when every row of the page runs here): every node of a service must share one service name, or its workflows are stranded. Services never share Maestro tables: at context refresh each node runs one bounded read (≤ 5 s, its own thread, never interrupted) for active instances under any other `service_name`, any type, and by default fails startup (`WorkflowExecutor.checkForeignServices`, `ForeignServiceRowsException`; `maestro.store.foreign-service-check` = fail | warn | off, `warn` for a rename in progress); a failed or late read is inconclusive — one WARN, never fatal. Recovery admits per queue at most its free slots plus `concurrency` queued runs (`TaskQueueGovernor.admitRecovery`, never blocking, never barging), takes the lock only after that, and re-polls as soon as capacity frees; `recoverWorkflows` is the ungated pass. `StartupRecoveryRunner` only starts loops, never adopts. Instance-lock renewal is one `renewAll` per pass (every TTL/3). A lock cleaner deletes rows expired for more than a minute every `maestro.lock.cleanup-interval` (10m).

There are no `maestro.tasks.*` topics: `maestro.messaging.topics.tasks` fails startup. `taskQueue` on `@DurableWorkflow` is a per-node concurrency bucket (`maestro.worker.task-queues[]`), not a route.

Locks fence persisted progress: every adoption bumps `maestro_workflow_instance.lease_epoch` under the version CAS, every run-scoped write (`WorkflowStore` overloads taking a `RunLease`) is rejected with `FencedWriteException` once the epoch has moved on, and the fenced run stands down as `LEASE_FENCED` — writes nothing, fails nothing. The activity already in flight at adoption can still run twice (the fence bounds persisted progress, not external side effects) — activities must stay idempotent.

## Configuration Namespace

All under `maestro.*`. Topics are pre-created, declared in config, and verified
at startup (`maestro.messaging.topics.verify-on-startup` = fail | warn | off,
default `fail`: a confirmed-missing topic fails startup naming every missing
topic with a `kafka-topics.sh --create` line each; an unreachable broker only
WARNs). Full reference: `docs/configuration.md`. Notable properties added post-0.3.0:
`maestro.shutdown.timeout` (default 20s; one deadline for the whole engine shutdown), `maestro.signal.wake-recheck-interval`
(default 30s), `maestro.messaging.redelivery.*` (bounded retry + dead-letter
policy, Kafka), `maestro.admin.events.enabled` (default `false` since WP7;
dashboard users set `true` on every service; the admin-events topic is required
at startup only while it is `true`; `.topic` is a deprecated alias for
`maestro.messaging.topics.admin-events`), `maestro.messaging.template-injection-check`
(fail | warn | off, default `fail`; the way out of last resort when an unqualified
`KafkaTemplate<…, byte[]>` point that received a template unable to send `byte[]`
unchanged sits in code the host cannot change),
`maestro.observability.metrics.enabled` / `maestro.observability.tracing.enabled`
(both default `true`, both silently inert without a `MeterRegistry` / `Tracer`;
the tracing flag gates the Kafka W3C header injection too — see
`docs/observability.md`), `maestro.signal.janitor-interval` (default 1h) /
`maestro.signal.terminal-retention` (default 7d) (the signal janitor purges
unconsumed signals of workflows `COMPLETED`/`TERMINATED` longer ago than the
retention, this service's only, in batches of 1,000, at most 1,000 batches per
sweep; `FAILED` keeps them for retry), `maestro.retention.*` (`enabled` default
`true`, `interval` 1h, `completed-retention` 30d, `terminated-retention` 30d,
`failed-retention` 90d, `batch-size` 500, `max-batches-per-sweep` 100 — the
leader-elected retention sweeper purges finished workflows of this service only,
with their whole history, via `WorkflowStore.purgeTerminalInstancesBefore`,
pausing between batches as long as the last batch took (≤ 5 s); migration V8 adds its
indexes), `maestro.replay.verify-arguments` (default `true`; every
`ACTIVITY_COMPLETED`/`ACTIVITY_FAILED` event carries a SHA-256 fingerprint of
the call's arguments — `maestro_workflow_event.args_hash`, migration V7 — and a
replay whose arguments fingerprint differently stands down as
`nondeterministic_history` instead of returning the recorded result; `NULL` on
either side is unverified, never a mismatch; `false` skips the comparison but
still writes the hash), `maestro.worker.task-queues[].concurrency` /
`activity-concurrency` (bound per node), `maestro.worker.default-concurrency` /
`default-activity-concurrency` (default 20/20 for every unlisted queue, per
queue per node; `0` on both = unbounded, `0` on one or negative fails
startup), `maestro.store.schema.mode`
(flyway | validate | none), `maestro.signal.wake-notify.enabled` (default `true`;
set `false` behind PgBouncer transaction pooling — wake then falls back to the
recheck interval), `maestro.lock.cleanup-interval` (default 10m), `maestro.store.foreign-service-check`
(fail | warn | off, default `fail`: active rows of another service name fail
startup; an inconclusive read only WARNs). `maestro.lock.type`
and `maestro.messaging.type` each accept exactly one value (`postgres`, `kafka`);
any other value fails startup. Removed (setting either fails startup):
`maestro.store.table-prefix`, `maestro.messaging.topics.tasks`.

## Coding Standards

- **Java 25 features:** Records, sealed interfaces, virtual threads, `var` for obvious types.
- **JSpecify null safety:** `@Nullable` from `org.jspecify.annotations`. All public APIs annotated.
- **Jackson 3:** Use `tools.jackson` packages everywhere. Never `com.fasterxml.jackson` — except `com.fasterxml.jackson.annotation`, which Jackson 3 keeps (see "What NOT To Do").
- **Immutability:** Records for DTOs. Final fields + builders for mutable domain objects.
- **Exceptions:** All extend `MaestroException`. Specific subtypes for each failure mode.
  **Three deliberate exceptions — the engine's control-flow signals:**
  they extend the sealed `MaestroControlFlowError extends Error`, not
  `MaestroException`, and the `permits` clause lists exactly three:
  `ExecutorShutdownException` (this node is stopping while the workflow was
  parked, or gave the run up at its shutdown deadline), `WorkflowTerminatedException` (an operator terminated the workflow)
  and `UnknownWorkflowHistoryException` (this node cannot interpret the
  workflow's persisted history — a newer node wrote it; see the stand-down
  design). None means the workflow
  failed; all mean a workflow's *local run* must stop now. If any were a
  `RuntimeException` like everything else, a workflow author's ordinary
  `try { ... } catch (Exception e) { ... }` around `awaitSignal()`/`sleep()`
  would silently swallow it and reinstate the exact bug it exists to prevent —
  a routine deploy recorded as a workflow failure and compensations run for
  work that never failed, a terminated workflow carrying on executing
  activities an operator asked you to stop, or an older node in a
  mixed-version fleet marking a workflow FAILED because it could not read a
  newer node's event. Making them `Error`s means `catch
  (Exception)` — and most `catch (Throwable)` "log and continue" blocks —
  cannot intercept them (Temporal takes the same approach for the same
  problem). Anywhere reflection is involved (`Method.invoke`,
  `CompletableFuture` completion), unwrap the cause and check `instanceof
  Error` *before* checking `instanceof Exception`/`RuntimeException` —
  otherwise the unwrap silently re-wraps it into a catchable type. Anywhere a
  broad `catch (Throwable)` collects outcomes (e.g. parallel branches), check
  `instanceof MaestroControlFlowError` and rethrow before recording anything
  as a failure — the sealed base exists so that check is one line rather than
  an enumeration that drifts. See their Javadoc for the full rationale.
  `StoreUnavailableException` (a `MaestroException`) is a store fault, never a
  workflow failure: the JDBC store throws it for every SQLSTATE outside class
  `22`/`23`/`54`, and `StoreFaultGuard` retries outcome appends then latches the run
  `STORE_UNAVAILABLE` — a latched run appends nothing and is abandoned for
  recovery, so any new `catch` that collects failures must rethrow it too.
  A run still executing at the shutdown deadline is **given up**
  (`RunCancellation.abandon()`, sticky; P1, Issue 70): `StoreFaultGuard` then
  refuses every store call under its context with `ExecutorShutdownException`
  (advisory reads and the activity body's pass-through excepted), and every
  stop point that calls `throwIfStoreUnavailable` also calls
  `throwIfAbandoned` — a new stop point, or a new failure-path `catch` that
  checks the store latch, checks this one too. A launcher thread (start,
  resume, Retry before the run thread starts) has no context for the guard,
  so each launcher write checks the launch's `RunCancellation` itself; a new
  launcher write does too.
  A background loop never ends on an `Error` silently (3.5b): it rethrows a `MaestroControlFlowError` and contains every other `Throwable`. The owner of that rule is `LoopSupervisor` (package `io.b2mash.maestro.core.loop`, public but internal until 1.0): a loop is a `LoopBody` declared through `WorkflowExecutor.supervise(LoopSpec, LoopBody)`, whose supervisor owns the thread, the waits, the streak (`WorkflowExecutor.loops()` → `LoopSnapshot`) and a latch stop that never interrupts; a body throws to report a failure and never swallows one, and a wait it owns (`Next.await`) must be woken stickily by the spec's stop hook. Every loop runs under `LoopSupervisor`: the timer poller, the recovery poller, the one-shot startup recovery, the signal janitor, the retention sweeper, the instance-lock renewer (which starts with the executor's build and is stopped by `shutdown()` after the drain, not with the others), and the two `maestro-store-postgres` loops, `PostgresLockCleanerScheduler` (`lock-cleaner`) and `PostgresWakeListener` (`wake-listener`), which their auto-configurations start under the executor (`start(WorkflowExecutor)`; the listener from a `SmartInitializingSingleton`, because the executor takes the notifier that takes the listener). A new loop is supervised. `supervise` makes every loop but the renewer a `LOOP` member of the executor's lifecycle registry: it joins before its thread starts (refused once shutdown began, so no loop is started and forgotten) and leaves through `LoopSupervisor.whenEnded`, after its end state is published; shutdown asks loops to stop with every other member and waits for them after the drain on its one deadline (P1 task 3).
  Observers are contained in one place (Issue 81): every engine type that takes an `EngineObserver` wraps it with `CompositeEngineObserver.contain`, which contains every `Throwable` a delegate throws except a `MaestroControlFlowError` and a `VirtualMachineError`, logs it once per callback and delegate, and counts it (`WorkflowExecutor.observerErrorCount()`, `maestro.observer.errors`). Do not add per-call-site `try`/`catch` around an observer call.
- **Logging:** SLF4J. MDC with `workflowId`, `runId`, `activityName`.
- **No Lombok.** Records and IDE-generated code only.
- **Javadoc:** All public APIs. SPIs especially.
- **Thread safety:** Document guarantees on all public classes.
- **Tests:** Unit for core. Testcontainers 2.0 for store/messaging/lock.

## Build

```bash
./gradlew build                                        # everything except e2eTest; needs Docker
./gradlew :maestro-core:test
./gradlew :maestro-store-postgres:test                 # its Testcontainers suites run under `test`
./gradlew :maestro-integration-tests:test              # real Postgres + Kafka
./gradlew :maestro-integration-tests:e2eTest           # chaos/soak harness, not part of build
./gradlew :maestro-samples:sample-order-service:bootRun
```

## What NOT To Do

- **Never add Spring to `maestro-core`.**
- **Never do I/O between activity calls in workflow code.**
- **Never use `Thread.sleep()` in workflow code** — use `workflow.sleep()`.
- **Never store workflow state in memory only** — Postgres is truth; locks and NOTIFY wake are an optimisation.
- **Never assume signal ordering.**
- **Never point two services at one set of `maestro_*` tables, and never build an executor without a `DistributedLock`** — both are unsupported (`docs/support-matrix.md`) and refused at startup; never reintroduce a launch that proceeds without its instance lock.
- **Never break `(workflow_instance_id, sequence_number)` uniqueness.**
- **Never auto-create Kafka topics** — pre-created, declared in config, and enforced at startup: `KafkaTopicVerifier` fails the context on a confirmed-missing topic (`maestro.messaging.topics.verify-on-startup=fail`, the default), the engine consumer defaults `allow.auto.create.topics=false`, and under `fail` — once the probe has confirmed every required topic present (`KafkaTopicVerifier.fatalOnMissing`; never after an inconclusive probe, which must not turn into a restart loop) — every Maestro container is `missingTopicsFatal`. Every suite that touches a broker pre-creates its topics — `.DLT` companions included — against a broker with `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`.
- **Never inject a Maestro Kafka bean by type** — `maestroKafkaTemplate`, `maestroKafkaProducerFactory` and `maestroKafkaConsumerFactory` are `@Bean(defaultCandidate = false)`, so Spring Boot's Kafka beans exist beside them; Maestro code injects them only with `@Qualifier(MaestroKafkaBeans.TEMPLATE)` (or `PRODUCER_FACTORY`, `CONSUMER_FACTORY`). An unqualified `KafkaTemplate<String, byte[]>` receives Spring Boot's template (with Boot's `StringSerializer`, which `ByteArrayTemplateInjectionCheck` fails startup over) or, once the host declares a template of its own for another value type, nothing at all (a `NoSuchBeanDefinitionException` that `ByteArrayTemplateMissingFailureAnalyzer` explains).
- **Never publish a lifecycle event on the workflow thread** — go through `LifecycleEventSink` (`WorkflowExecutor.lifecycleEventSink()`), never `WorkflowMessaging.publishLifecycleEvent` directly; a Kafka producer blocks up to `max.block.ms` on a missing topic.
- **Never give a shutdown step its own timeout** — every step of `WorkflowExecutor.shutdown()` waits at most what is left of its `ShutdownDeadline` (3.6).
- **Never launch a run outside the lifecycle registry** — a launch path joins `WorkflowExecutor.lifecycle()` (`LifecycleRegistry.joinRun`) before it takes the instance lock, does nothing if refused, and leaves after the lock's release, on the run thread once it has started (P1, Issue 80). Shutdown waits on the registry, not on `runningWorkflows`.
- **Never interrupt a thread that may be inside a JDBC call** — stop it with a latch and a bounded join, as `LoopSupervisor` does for every loop; no engine-owned thread (loop, renewer, health probe, shutdown) is interrupted any more: a timed-out health probe is abandoned, not interrupted, and one stuck for 10 s is superseded once (at most two probes; P1, Issue 66). The one interrupt by design: an activity attempt past its `startToCloseTimeout` is cancelled with `cancel(true)` (docs/concepts.md "Timeouts", `ActivityTimeoutException`).
- **Never start a Maestro Kafka listener container outside its `KafkaContainerGroup`** — the group joins the executor's lifecycle registry before any container starts, so the engine's shutdown asks every Maestro consumer to stop at its start, asynchronously, and waits for it on the one deadline (a consumer leaves its group once its record in hand returns); every Maestro container sets `stopImmediate` (stop after the record in hand); `destroy()` is a request-only backstop (P1, Issue 69).
- **Never use `com.fasterxml.jackson`** — Jackson 3 uses `tools.jackson`. The one exception is the annotations package: Jackson 3 keeps `com.fasterxml.jackson.annotation` (`@JsonTypeInfo`, `@JsonSubTypes`, `@JsonProperty`, …), so those imports are correct and required.
- **Never use a Jakarta EE `javax.*` namespace** (`javax.servlet`, `javax.persistence`, `javax.annotation`, etc.) — Spring Boot 4 is Jakarta EE 11, so these are `jakarta.*` now. This does **not** cover Java SE's own `javax.*` packages, which never moved and have no `jakarta.*` equivalent — `javax.sql.DataSource`, `javax.crypto.*`, and similar are correct and required as-is.
