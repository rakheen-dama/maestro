# CLAUDE.md — Maestro Project Context

## What Is Maestro

Maestro is an **open-source, embeddable durable workflow engine** delivered as a Spring Boot Starter. It provides Temporal.io-grade workflow durability without a central server, using infrastructure teams already operate: Postgres, Kafka, and Valkey/Redis.

**One-sentence pitch:** Add a Spring Boot Starter to your microservice and get durable, crash-recoverable workflows using your existing database, message broker, and cache.

## Architecture Docs

Read these before making architectural decisions:
- `docs/maestro-prd.md` — Product requirements, API design, e-commerce example
- `docs/maestro-architecture.md` — System architecture, diagrams, failure modes

## Core Design: Hybrid Memoization

1. Workflow method runs on a **Java 21 virtual thread**.
2. Activity calls intercepted by a **proxy**. Proxy checks Postgres for stored result at current **sequence number**.
3. **Replay (found):** Return stored result instantly — no execution.
4. **Live (not found):** Execute activity, persist result, return it.
5. **Recovery:** Re-invoke workflow method. Completed steps replay instantly. Resumes from first uncompleted step.

**Determinism constraint:** Code between activity calls must be deterministic. No `Math.random()`, `LocalDateTime.now()`, `UUID.randomUUID()`, or direct I/O. Use `workflow.currentTime()`, `workflow.randomUUID()`.

**Parallel branches** partition the sequence space: branch *i* of a fork at parent seq `p` allocates from base `p*1000 + (i+1)*1000` (≤999 steps per branch).

## Tech Stack

| Component | Technology | Version |
|---|---|---|
| Language | Java | 21+ (virtual threads required) |
| Framework | Spring Boot | 4.x (Spring Framework 7, Jakarta EE 11) |
| Build | Gradle | Kotlin DSL, Gradle 9 |
| Database | PostgreSQL | 14+ |
| Messaging | Apache Kafka | via Spring Kafka 4.x |
| Cache/Lock | Valkey or Redis | via Lettuce |
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
├── maestro-core                    ← Pure Java. NO Spring. Engine, memoization, timers, signals, saga.
├── maestro-spring-boot-starter     ← Auto-config, annotations, bean proxying, config binding.
├── maestro-store-jdbc              ← Abstract JDBC WorkflowStore SPI.
├── maestro-store-postgres          ← Postgres implementation + Flyway 11 migrations.
├── maestro-messaging-kafka         ← Spring Kafka 4.x WorkflowMessaging SPI.
├── maestro-messaging-postgres      ← PostgreSQL WorkflowMessaging + SignalNotifier (LISTEN/NOTIFY).
├── maestro-messaging-rabbitmq      ← RabbitMQ WorkflowMessaging via Spring AMQP.
├── maestro-lock-valkey             ← Lettuce DistributedLock SPI.
├── maestro-lock-postgres           ← PostgreSQL DistributedLock SPI.
├── maestro-admin-client            ← Lightweight lifecycle event publisher.
├── maestro-admin                   ← Standalone dashboard (Thymeleaf + HTMX, own Postgres).
├── maestro-test                    ← In-memory SPIs, controllable clock, TestWorkflowEnvironment.
├── maestro-samples/
│   ├── sample-order-service        ← Order fulfilment workflow (e-commerce demo)
│   ├── sample-payment-gateway      ← Payment processing with durable retries & saga
│   ├── sample-postgres-only        ← Document approval (Postgres-only, zero external deps)
│   ├── sample-rabbitmq-order-service ← Order fulfilment using RabbitMQ + Postgres
│   └── sample-loan-origination     ← Multi-service loan E2E (application/underwriting/verification), nightly CI
└── docs/
```

**Critical:** `maestro-core` must NEVER depend on Spring. All Spring integration lives in `maestro-spring-boot-starter`.

## Three SPIs

```java
public interface WorkflowStore {
    WorkflowInstance createInstance(WorkflowInstance instance);
    Optional<WorkflowInstance> getInstance(String workflowId);
    List<WorkflowInstance> getRecoverableInstances();
    void updateInstance(WorkflowInstance instance);       // optimistic locking

    void appendEvent(WorkflowEvent event);
    Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int seq);
    List<WorkflowEvent> getEvents(UUID instanceId);

    void saveSignal(WorkflowSignal signal);
    List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName);
    boolean markSignalConsumed(UUID signalId);           // CAS: false if already consumed
    void adoptOrphanedSignals(String workflowId, UUID instanceId);

    void saveTimer(WorkflowTimer timer);
    List<WorkflowTimer> getDueTimers(Instant now, int batchSize);
    Optional<WorkflowTimer> findTimer(UUID workflowInstanceId, String timerId); // any status; replay self-heal
    boolean markTimerFired(UUID timerId);                 // CAS: false if already fired/cancelled
    void markTimerCancelled(UUID timerId);
}

public interface WorkflowMessaging {
    void publishTask(String taskQueue, TaskMessage message);
    void publishSignal(String serviceName, SignalMessage message);
    void publishLifecycleEvent(WorkflowLifecycleEvent event);
    void subscribe(String taskQueue, Consumer<TaskMessage> handler);
    void subscribeSignals(String serviceName, Consumer<SignalMessage> handler);
}

public interface DistributedLock {
    Optional<LockHandle> tryAcquire(String key, Duration ttl);
    void release(LockHandle handle);
    void renew(LockHandle handle, Duration ttl);
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
io.b2mash.maestro.core.exception            — MaestroException hierarchy

io.b2mash.maestro.spring                    — Spring Boot auto-configuration
io.b2mash.maestro.spring.annotation         — @MaestroSignalListener
io.b2mash.maestro.spring.config              — MaestroAutoConfiguration, MaestroProperties
io.b2mash.maestro.spring.proxy               — ActivityStubBeanPostProcessor
io.b2mash.maestro.spring.health              — MaestroHealthIndicator
io.b2mash.maestro.spring.client              — MaestroClient

io.b2mash.maestro.store.jdbc                — Abstract JDBC WorkflowStore
io.b2mash.maestro.store.postgres             — Postgres impl + Flyway migrations

io.b2mash.maestro.messaging.kafka            — Kafka WorkflowMessaging
io.b2mash.maestro.messaging.kafka.listener   — @MaestroSignalListener processing

io.b2mash.maestro.messaging.postgres         — Postgres WorkflowMessaging + SignalNotifier
io.b2mash.maestro.messaging.rabbitmq         — RabbitMQ WorkflowMessaging

io.b2mash.maestro.lock.valkey                — Valkey DistributedLock
io.b2mash.maestro.lock.postgres              — Postgres DistributedLock

io.b2mash.maestro.admin                      — Dashboard app
io.b2mash.maestro.admin.client               — Event publisher

io.b2mash.maestro.test                       — TestWorkflowEnvironment, in-memory SPIs

io.b2mash.maestro.samples.order              — Sample order service (e-commerce demo)
io.b2mash.maestro.samples.payment            — Sample payment gateway (e-commerce demo)
```

## Database Tables

Prefix `maestro_` (configurable). Flyway migrations in `maestro-store-postgres/src/main/resources/db/migration/`:

- `maestro_workflow_instance`
- `maestro_workflow_event` (unique on `workflow_instance_id, sequence_number`)
- `maestro_workflow_timer`
- `maestro_workflow_signal` (`workflow_instance_id` nullable for pre-delivery)

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

**Never discard a signal.**

## Cross-Service Model

Orchestration within, choreography between. Each service owns its state. Kafka events → `@MaestroSignalListener` → workflow signals.

## Valkey Keys

```
maestro:lock:workflow:{workflowId}           — Instance lock (30s TTL, renewed every 10s)
maestro:lock:activity:{workflowId}:{seq}     — Activity execution lock, best-effort dedup fast path (timeout + 10s TTL)
maestro:leader:timer-poller:{service}         — Timer leader (15s TTL)
maestro:signal:{workflowId}                   — Pub/sub for immediate signal wake
```

Locks are best-effort guards: if one expires or is lost, the unique event index dedups *persisted results*, not external side effects — activities must be idempotent.

## Configuration Namespace

All under `maestro.*`. Topics are pre-created, declared in config. Full
reference: `docs/configuration.md`. Notable properties added post-0.3.0:
`maestro.shutdown.timeout` (default 30s), `maestro.signal.wake-recheck-interval`
(default 30s), `maestro.messaging.redelivery.*` (bounded retry + dead-letter
policy, all transports), `maestro.admin.events.enabled` (now actually wired;
`.topic` is a deprecated alias for `maestro.messaging.topics.admin-events`).

## Coding Standards

- **Java 21 features:** Records, sealed interfaces, virtual threads, `var` for obvious types.
- **JSpecify null safety:** `@Nullable` from `org.jspecify.annotations`. All public APIs annotated.
- **Jackson 3:** Use `tools.jackson` packages everywhere. Never `com.fasterxml.jackson`.
- **Immutability:** Records for DTOs. Final fields + builders for mutable domain objects.
- **Exceptions:** All extend `MaestroException`. Specific subtypes for each failure mode.
  **Two deliberate exceptions — the engine's control-flow signals:**
  `ExecutorShutdownException` (this node is stopping while the workflow was
  parked) and `WorkflowTerminatedException` (an operator terminated the
  workflow) extend `Error`, not `MaestroException`. Neither means the workflow
  failed; both mean a workflow's *local run* must stop now. If either were a
  `RuntimeException` like everything else, a workflow author's ordinary
  `try { ... } catch (Exception e) { ... }` around `awaitSignal()`/`sleep()`
  would silently swallow it and reinstate the exact bug it exists to prevent —
  a routine deploy recorded as a workflow failure and compensations run for
  work that never failed, or a terminated workflow carrying on executing
  activities an operator asked you to stop. Making them `Error`s means `catch
  (Exception)` — and most `catch (Throwable)` "log and continue" blocks —
  cannot intercept them (Temporal takes the same approach for the same
  problem). Anywhere reflection is involved (`Method.invoke`,
  `CompletableFuture` completion), unwrap the cause and check `instanceof
  Error` *before* checking `instanceof Exception`/`RuntimeException` —
  otherwise the unwrap silently re-wraps it into a catchable type. Anywhere a
  broad `catch (Throwable)` collects outcomes (e.g. parallel branches), check
  for both types and rethrow before recording anything as a failure. See their
  Javadoc for the full rationale.
- **Logging:** SLF4J. MDC with `workflowId`, `runId`, `activityName`.
- **No Lombok.** Records and IDE-generated code only.
- **Javadoc:** All public APIs. SPIs especially.
- **Thread safety:** Document guarantees on all public classes.
- **Tests:** Unit for core. Testcontainers 2.0 for store/messaging/lock.

## Build

```bash
./gradlew build
./gradlew :maestro-core:test
./gradlew :maestro-store-postgres:integrationTest
./gradlew :maestro-samples:sample-order-service:bootRun
```

## What NOT To Do

- **Never add Spring to `maestro-core`.**
- **Never do I/O between activity calls in workflow code.**
- **Never use `Thread.sleep()` in workflow code** — use `workflow.sleep()`.
- **Never store workflow state in memory only** — Postgres is truth, Valkey is optimisation.
- **Never assume signal ordering.**
- **Never break `(workflow_instance_id, sequence_number)` uniqueness.**
- **Never auto-create Kafka topics** — pre-created, declared in config.
- **Never use `com.fasterxml.jackson`** — Jackson 3 uses `tools.jackson`.
- **Never use `javax.*`** — Spring Boot 4 is Jakarta EE 11 (`jakarta.*`).
