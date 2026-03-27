# CLAUDE.md — Maestro Project Context

## What Is Maestro

Maestro is an **open-source, embeddable durable workflow engine** delivered as a Spring Boot Starter. It provides Temporal.io-grade workflow durability without a central server, using infrastructure teams already operate: Postgres, Kafka, and Valkey/Redis.

**One-sentence pitch:** Add a Spring Boot Starter to your microservice and get durable, crash-recoverable workflows using your existing database, message broker, and cache.

## Architecture Docs

Read these before making architectural decisions:
- `docs/maestro-prd.md` — Product requirements, API design, e-commerce example
- `docs/maestro-architecture.md` — System architecture, diagrams, failure modes
- `docs/example-stokvel.md` — Real-world multi-service stokvel onboarding example

## Core Design: Hybrid Memoization

1. Workflow method runs on a **Java 21 virtual thread**.
2. Activity calls intercepted by a **proxy**. Proxy checks Postgres for stored result at current **sequence number**.
3. **Replay (found):** Return stored result instantly — no execution.
4. **Live (not found):** Execute activity, persist result, return it.
5. **Recovery:** Re-invoke workflow method. Completed steps replay instantly. Resumes from first uncompleted step.

**Determinism constraint:** Code between activity calls must be deterministic. No `Math.random()`, `LocalDateTime.now()`, `UUID.randomUUID()`, or direct I/O. Use `workflow.currentTime()`, `workflow.randomUUID()`.

**Parallel branches** use compound sequence keys: step `5` → branches `5.0`, `5.1`, `5.2`.

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
├── maestro-lock-valkey             ← Lettuce DistributedLock SPI.
├── maestro-admin-client            ← Lightweight lifecycle event publisher.
├── maestro-admin                   ← Standalone dashboard (Thymeleaf + HTMX, own Postgres).
├── maestro-test                    ← In-memory SPIs, controllable clock, TestWorkflowEnvironment.
├── maestro-samples/
│   ├── sample-order-service        ← E-commerce order fulfilment (flagship example)
│   └── sample-payment-gateway      ← Payment processing with durable retries
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
    void markSignalConsumed(UUID signalId);
    void adoptOrphanedSignals(String workflowId, UUID instanceId);

    void saveTimer(WorkflowTimer timer);
    List<WorkflowTimer> getDueTimers(Instant now, int batchSize);
    void markTimerFired(UUID timerId);
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
io.maestro.core                     — Core engine, domain, SPIs
io.maestro.core.annotation          — @DurableWorkflow, @Activity, etc.
io.maestro.core.engine               — WorkflowExecutor, ActivityProxy, MemoizationEngine
io.maestro.core.model                — WorkflowInstance, WorkflowEvent, WorkflowSignal, WorkflowTimer
io.maestro.core.saga                 — SagaManager, CompensationStack
io.maestro.core.context              — WorkflowContext (sleep, awaitSignal, parallel, etc.)
io.maestro.core.spi                  — WorkflowStore, WorkflowMessaging, DistributedLock
io.maestro.core.retry                — RetryPolicy, RetryExecutor
io.maestro.core.exception            — MaestroException hierarchy

io.maestro.spring                    — Spring Boot auto-configuration
io.maestro.spring.annotation         — @MaestroSignalListener
io.maestro.spring.config              — MaestroAutoConfiguration, MaestroProperties
io.maestro.spring.proxy               — ActivityStubBeanPostProcessor
io.maestro.spring.health              — MaestroHealthIndicator
io.maestro.spring.client              — MaestroClient

io.maestro.store.jdbc                — Abstract JDBC WorkflowStore
io.maestro.store.postgres             — Postgres impl + Flyway migrations

io.maestro.messaging.kafka            — Kafka WorkflowMessaging
io.maestro.messaging.kafka.listener   — @MaestroSignalListener processing

io.maestro.lock.valkey                — Valkey DistributedLock

io.maestro.admin                      — Dashboard app
io.maestro.admin.client               — Event publisher

io.maestro.test                       — TestWorkflowEnvironment, in-memory SPIs
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
maestro:lock:workflow:{workflowId}           — Instance lock (30s TTL, renewed)
maestro:dedup:{workflowId}:{seq}             — Activity dedup (5m TTL)
maestro:leader:timer-poller:{service}         — Timer leader (15s TTL)
maestro:signal:{workflowId}                   — Pub/sub for immediate signal wake
```

## Configuration Namespace

All under `maestro.*`. Topics are pre-created, declared in config.

## Coding Standards

- **Java 21 features:** Records, sealed interfaces, virtual threads, `var` for obvious types.
- **JSpecify null safety:** `@Nullable` from `org.jspecify.annotations`. All public APIs annotated.
- **Jackson 3:** Use `tools.jackson` packages everywhere. Never `com.fasterxml.jackson`.
- **Immutability:** Records for DTOs. Final fields + builders for mutable domain objects.
- **Exceptions:** All extend `MaestroException`. Specific subtypes for each failure mode.
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
