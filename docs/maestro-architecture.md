# Maestro — Architecture Document

## Durable Workflow Engine for Spring Boot Microservices

**Version:** 0.3.0-SNAPSHOT  
**Status:** Draft  
**Date:** March 2026

---

## 1. System Overview

Maestro is an embedded durable workflow engine delivered as a Spring Boot Starter. There is no central server. Each microservice includes the Maestro library, which provides the workflow runtime using the service's existing Postgres, Kafka, and Valkey infrastructure.

### Product Boundary

Maestro is a **library** that provides: a workflow execution engine with hybrid memoization, annotations and APIs for defining workflows/activities, signal handling, durable timers, saga compensation, parallel execution, and an optional admin dashboard. Maestro is **not** an application framework or domain-specific tool — it is infrastructure that your application builds on.

### High-Level Architecture

```mermaid
graph TB
    subgraph "Service A"
        A_API["REST API"]
        A_ENGINE["Maestro Engine<br/>(Embedded)"]
        A_WORKER["Workflow Workers<br/>(Virtual Threads)"]
        A_ACTIVITIES["Activity Beans"]
        A_API --> A_ENGINE
        A_ENGINE --> A_WORKER
        A_WORKER --> A_ACTIVITIES
    end

    subgraph "Service B"
        B_CONSUMER["Kafka Consumer"]
        B_ENGINE["Maestro Engine<br/>(Embedded)"]
        B_WORKER["Workflow Workers"]
        B_ACTIVITIES["Activity Beans"]
        B_CONSUMER --> B_ENGINE
        B_ENGINE --> B_WORKER
        B_WORKER --> B_ACTIVITIES
    end

    subgraph "External"
        EXT["Third-Party APIs"]
    end

    subgraph "Infrastructure"
        PG_A[("Postgres<br/>(A schema)")]
        PG_B[("Postgres<br/>(B schema)")]
        KAFKA["Kafka"]
        VALKEY["Valkey"]
    end

    subgraph "Maestro Admin (optional)"
        ADMIN["Dashboard"]
        PG_ADMIN[("Postgres")]
    end

    B_ACTIVITIES --> EXT
    A_ENGINE <--> PG_A
    A_ENGINE <--> VALKEY
    A_ENGINE <--> KAFKA
    B_ENGINE <--> PG_B
    B_ENGINE <--> VALKEY
    B_ENGINE <--> KAFKA
    KAFKA --> ADMIN
    ADMIN <--> PG_ADMIN
```

---

## 2. Module Architecture

```mermaid
graph TB
    subgraph "Your Application"
        APP["Spring Boot Service"]
    end

    subgraph "Maestro Product"
        STARTER["maestro-spring-boot-starter"]
        CORE["maestro-core<br/><i>Pure Java — no Spring</i>"]
        STORE_PG["maestro-store-postgres"]
        STORE_JDBC["maestro-store-jdbc"]
        MSG_KAFKA["maestro-messaging-kafka"]
        LOCK_VALKEY["maestro-lock-valkey"]
        ADMIN["maestro-admin"]
        ADMIN_CLIENT["maestro-admin-client"]
        TEST["maestro-test"]
    end

    subgraph "SPIs"
        SPI_STORE["WorkflowStore"]
        SPI_MSG["WorkflowMessaging"]
        SPI_LOCK["DistributedLock"]
    end

    APP --> STARTER
    STARTER --> CORE
    CORE --> SPI_STORE
    CORE --> SPI_MSG
    CORE --> SPI_LOCK
    SPI_STORE --> STORE_JDBC --> STORE_PG
    SPI_MSG --> MSG_KAFKA
    SPI_MSG --> MSG_PG["maestro-messaging-postgres"]
    SPI_MSG --> MSG_RMQ["maestro-messaging-rabbitmq"]
    SPI_LOCK --> LOCK_VALKEY
    SPI_LOCK --> LOCK_PG["maestro-lock-postgres"]

    style CORE fill:#4A90D9,color:#fff
    style STARTER fill:#5BA85A,color:#fff
    style SPI_STORE fill:#F5A623,color:#fff
    style SPI_MSG fill:#F5A623,color:#fff
    style SPI_LOCK fill:#F5A623,color:#fff
```

| Module | Responsibility | Spring Dependency? |
|---|---|---|
| `maestro-core` | Execution engine, hybrid memoization, virtual threads, timers, signals, saga, queries. | **No** |
| `maestro-spring-boot-starter` | Auto-configuration, annotation scanning, activity proxy creation, config binding, health indicators. | Yes |
| `maestro-store-jdbc` | Abstract JDBC `WorkflowStore`. | No (JDBC only) |
| `maestro-store-postgres` | Postgres implementation + Flyway 11 migrations. | No |
| `maestro-messaging-kafka` | Spring Kafka 4.x `WorkflowMessaging` implementation. `@MaestroSignalListener` processing. | Yes |
| `maestro-messaging-postgres` | PostgreSQL-based `WorkflowMessaging` + `SignalNotifier`. No external broker required. | No |
| `maestro-messaging-rabbitmq` | RabbitMQ `WorkflowMessaging` via Spring AMQP. | Yes |
| `maestro-lock-valkey` | Lettuce-based `DistributedLock`. | No |
| `maestro-lock-postgres` | PostgreSQL-based `DistributedLock` using advisory locks. | No |
| `maestro-admin` | Standalone dashboard app (Thymeleaf + HTMX, own Postgres schema). | Yes |
| `maestro-admin-client` | Publishes lifecycle events to Kafka. Lightweight. | Minimal |
| `maestro-test` | In-memory SPIs, controllable clock, `TestWorkflowEnvironment`. | No |

> **Operators choose one messaging implementation and one lock implementation for their deployment.** For example, a Postgres-only deployment uses `maestro-messaging-postgres` + `maestro-lock-postgres`, while a full infrastructure deployment uses `maestro-messaging-kafka` + `maestro-lock-valkey`.

---

## 3. Execution Engine — Hybrid Memoization

### 3.1 Normal Execution

```mermaid
sequenceDiagram
    participant Client
    participant Controller as REST Controller
    participant Engine as Maestro Engine
    participant VThread as Virtual Thread
    participant Proxy as Activity Proxy
    participant Store as Postgres
    participant Activity as Activity Bean
    participant Kafka

    Client->>Controller: POST /orders
    Controller->>Controller: Persist order, generate ID
    Controller->>Engine: startAsync(OrderFulfilment, input)
    Controller-->>Client: 202 {orderId}

    Engine->>Store: INSERT workflow_instance (RUNNING)
    Engine->>VThread: spawn virtual thread

    VThread->>Proxy: inventory.reserve(items)
    Note over Proxy: seq = 1
    Proxy->>Store: SELECT result WHERE seq = 1
    Store-->>Proxy: (not found)
    Proxy->>Activity: execute reserve(items)
    Activity-->>Proxy: ReservationConfirmation
    Proxy->>Store: INSERT workflow_event (ACTIVITY_COMPLETED, seq=1, result)
    Proxy-->>VThread: return ReservationConfirmation

    VThread->>Proxy: messaging.publishPaymentRequest(...)
    Note over Proxy: seq = 2 — publishes to Kafka
    Proxy->>Activity: execute publishPaymentRequest(...)
    Proxy->>Store: INSERT workflow_event (ACTIVITY_COMPLETED, seq=2)
    Proxy-->>VThread: return

    VThread->>Engine: workflow.awaitSignal("payment.result", 1h)
    Engine->>Store: UPDATE status = WAITING_SIGNAL
    Note over VThread: Virtual thread parks
```

### 3.2 Recovery After Crash

```mermaid
sequenceDiagram
    participant Recovery as Startup Recovery
    participant Engine as Maestro Engine
    participant VThread as Virtual Thread
    participant Proxy as Activity Proxy
    participant Store as Postgres

    Note over Recovery: Application restarts

    Recovery->>Store: SELECT workflow_instance<br/>WHERE status IN (RUNNING, WAITING_SIGNAL, WAITING_TIMER)
    Store-->>Recovery: [order-abc: OrderFulfilment, WAITING_SIGNAL]

    Recovery->>Engine: recoverWorkflow(order-abc)
    Engine->>Store: SELECT events ORDER BY seq
    Store-->>Engine: [ACTIVITY_COMPLETED(seq=1), ACTIVITY_COMPLETED(seq=2), WAITING_SIGNAL]

    Engine->>VThread: re-invoke fulfil() with original input

    VThread->>Proxy: inventory.reserve(items)
    Note over Proxy: seq = 1 — FOUND
    Proxy-->>VThread: return stored result (instant)

    VThread->>Proxy: messaging.publishPaymentRequest(...)
    Note over Proxy: seq = 2 — FOUND
    Proxy-->>VThread: return (instant, no duplicate publish)

    VThread->>Engine: workflow.awaitSignal("payment.result")
    Engine->>Store: Check for signals received during downtime
    Store-->>Engine: [payment.result: {success, txn-123}]
    Note over VThread: Signal found! Workflow resumes immediately
```

### 3.3 Activity Proxy Detail

```mermaid
flowchart TD
    A["Activity method called"] --> B["Proxy intercepts"]
    B --> C["Step key = activityName + seq"]
    C --> D{"Stored result<br/>exists?"}
    D -- "Yes (replay)" --> E["Return stored result"]
    D -- "No (live)" --> F["Acquire Valkey lock"]
    F --> G["Execute activity"]
    G --> H{"Success?"}
    H -- "Yes" --> I["Persist result<br/>(unique constraint)"]
    I --> J["Publish lifecycle event"]
    J --> K["Release lock"]
    K --> L["Return result"]
    H -- "No" --> M{"Retry<br/>allowed?"}
    M -- "Yes" --> N["Backoff wait"]
    N --> G
    M -- "No" --> O["Persist failure"]
    O --> P{"@Saga?"}
    P -- "Yes" --> Q["Compensate"]
    P -- "No" --> R["Mark FAILED"]
    Q --> R

    style D fill:#F5A623,color:#fff
    style H fill:#F5A623,color:#fff
    style M fill:#F5A623,color:#fff
    style P fill:#F5A623,color:#fff
```

---

## 4. State Management

### 4.1 Schema

```mermaid
erDiagram
    WORKFLOW_INSTANCE {
        uuid id PK
        varchar workflow_id UK
        uuid run_id UK
        varchar workflow_type
        varchar task_queue
        varchar status
        jsonb input
        jsonb output
        varchar service_name
        int event_sequence
        timestamp started_at
        timestamp completed_at
        timestamp updated_at
        int version "optimistic lock"
    }

    WORKFLOW_EVENT {
        uuid id PK
        uuid workflow_instance_id FK
        int sequence_number
        varchar event_type
        varchar step_name
        jsonb payload
        timestamp created_at
    }

    WORKFLOW_TIMER {
        uuid id PK
        uuid workflow_instance_id FK
        varchar timer_id
        timestamp fire_at
        varchar status
        timestamp created_at
    }

    WORKFLOW_SIGNAL {
        uuid id PK
        uuid workflow_instance_id FK "nullable for pre-delivery"
        varchar workflow_id "correlation for pre-delivery"
        varchar signal_name
        jsonb payload
        boolean consumed
        timestamp received_at
    }

    WORKFLOW_INSTANCE ||--o{ WORKFLOW_EVENT : "has"
    WORKFLOW_INSTANCE ||--o{ WORKFLOW_TIMER : "has"
    WORKFLOW_INSTANCE ||--o{ WORKFLOW_SIGNAL : "has"
```

### 4.2 Key Indexes

```sql
CREATE INDEX idx_wf_instance_recoverable
    ON maestro_workflow_instance(status) WHERE status IN ('RUNNING','WAITING_SIGNAL','WAITING_TIMER');

CREATE UNIQUE INDEX idx_wf_event_replay
    ON maestro_workflow_event(workflow_instance_id, sequence_number);

CREATE INDEX idx_wf_timer_due
    ON maestro_workflow_timer(fire_at, status) WHERE status = 'PENDING';

CREATE INDEX idx_wf_signal_pending
    ON maestro_workflow_signal(workflow_id, signal_name, consumed) WHERE consumed = false;

CREATE INDEX idx_wf_signal_orphan
    ON maestro_workflow_signal(workflow_id, consumed) WHERE workflow_instance_id IS NULL AND consumed = false;
```

### 4.3 State Transitions

```mermaid
stateDiagram-v2
    [*] --> RUNNING : start
    RUNNING --> WAITING_SIGNAL : awaitSignal / collectSignals
    RUNNING --> WAITING_TIMER : sleep / retryUntil
    RUNNING --> COMPLETED : workflow returns
    RUNNING --> FAILED : exception / retries exhausted
    RUNNING --> COMPENSATING : @Saga triggered

    WAITING_SIGNAL --> RUNNING : signal / timeout
    WAITING_TIMER --> RUNNING : timer fires

    COMPENSATING --> FAILED : compensation done
    FAILED --> RUNNING : manual retry

    RUNNING --> TERMINATED : admin terminate
    WAITING_SIGNAL --> TERMINATED : admin terminate
    WAITING_TIMER --> TERMINATED : admin terminate

    COMPLETED --> [*]
    FAILED --> [*]
    TERMINATED --> [*]
```

---

## 5. Self-Recovery Pattern

### 5.1 Signal Arrives Before Workflow Reaches Await

```mermaid
sequenceDiagram
    participant Ext as External Service
    participant Kafka
    participant SVC as Service
    participant Store as Postgres

    Ext->>Kafka: PaymentResult{success}
    Kafka->>SVC: @MaestroSignalListener

    Note over SVC: Workflow exists but<br/>hasn't reached awaitSignal() yet

    SVC->>Store: INSERT workflow_signal<br/>(consumed = false)

    Note over SVC: ...later...<br/>Workflow reaches awaitSignal()

    SVC->>Store: SELECT WHERE signal_name = 'payment.result'<br/>AND consumed = false
    Store-->>SVC: FOUND
    SVC->>Store: UPDATE consumed = true
    Note over SVC: Continues immediately
```

### 5.2 Signal Arrives Before Workflow Starts

```mermaid
sequenceDiagram
    participant Ext as External Service
    participant Kafka
    participant SVC as Service
    participant Store as Postgres

    Ext->>Kafka: Result event
    Kafka->>SVC: @MaestroSignalListener

    SVC->>Store: INSERT workflow_signal<br/>(workflow_id = "order-abc",<br/>workflow_instance_id = NULL)

    Note over SVC: Workflow doesn't exist yet

    Note over SVC: ...later... workflow starts

    SVC->>Store: INSERT workflow_instance
    SVC->>Store: UPDATE workflow_signal<br/>SET workflow_instance_id = {new}<br/>WHERE workflow_id = "order-abc"<br/>AND workflow_instance_id IS NULL

    Note over SVC: Orphaned signals adopted
```

---

## 6. Cross-Service Communication

### Pattern: Orchestration Within, Choreography Between

```mermaid
sequenceDiagram
    participant Client
    participant OrderSvc as Order Service<br/>(Order Workflow)
    participant Kafka
    participant PaySvc as Payment Proxy<br/>(Payment Workflow)
    participant Provider as Payment Provider

    Client->>OrderSvc: POST /orders
    OrderSvc-->>Client: 202 {orderId}
    OrderSvc->>OrderSvc: Workflow starts

    OrderSvc->>OrderSvc: inventory.reserve() ✓
    OrderSvc->>Kafka: PaymentRequest

    Kafka->>PaySvc: consume PaymentRequest
    PaySvc->>PaySvc: Payment Workflow starts

    PaySvc->>Provider: charge()
    Provider--xPaySvc: 503 Unavailable
    Note over PaySvc: Retry in 5s
    PaySvc->>Provider: charge()
    Provider-->>PaySvc: Charged ✓

    PaySvc->>Kafka: PaymentResult{success}
    Kafka->>OrderSvc: consume → signal

    Note over OrderSvc: Workflow resumes
    OrderSvc->>OrderSvc: shipping.createShipment() ✓
    OrderSvc->>OrderSvc: notifications.notify() ✓
    Note over OrderSvc: COMPLETED
```

---

## 7. Kafka Topology

All topics are pre-created and declared in `application.yml`.

| Topic | Owner | Key | Purpose |
|---|---|---|---|
| `orders.payment.requests` | Order Service | orderId | Payment commands to proxy |
| `payments.results` | Payment Proxy | orderId | Payment results back |
| `maestro.tasks.{taskQueue}` | Per-service | workflowId | Internal task dispatch |
| `maestro.signals.{service}` | Per-service | workflowId | Inbound signals |
| `maestro.admin.events` | All services | serviceName | Lifecycle events for dashboard |

Consumer group per service: `maestro-{serviceName}`.

---

## 8. Distributed Coordination (Valkey)

| Key Pattern | Purpose | TTL |
|---|---|---|
| `maestro:lock:workflow:{workflowId}` | Workflow instance lock | 30s (auto-renewed) |
| `maestro:dedup:{workflowId}:{seq}` | Activity deduplication | 5m |
| `maestro:leader:timer-poller:{service}` | Timer polling leader election | 15s |
| `maestro:signal:{workflowId}` (pub/sub) | Immediate signal notification | N/A |

**Fallback:** If Valkey is down, locking falls back to Postgres advisory locks, deduplication to unique constraints, and signal notification to poll-based delivery. Postgres is the source of truth; Valkey is a performance optimisation.

---

## 9. Timer Management

```mermaid
sequenceDiagram
    participant WF as Workflow
    participant Engine
    participant Store as Postgres
    participant Leader as Timer Poller
    participant Valkey

    WF->>Engine: workflow.sleep(30 days)
    Engine->>Store: INSERT timer (fire_at = now+30d, PENDING)
    Engine->>Store: UPDATE status = WAITING_TIMER
    Note over WF: Parks

    Note over Leader: Polling every 5s

    Leader->>Store: SELECT timers WHERE fire_at <= now<br/>AND status = PENDING<br/>FOR UPDATE SKIP LOCKED
    Store-->>Leader: [timer-xyz]
    Leader->>Store: UPDATE status = FIRED
    Leader->>Valkey: PUBLISH signal
    Note over WF: Wakes, continues
```

Timer leader election uses Valkey `SET NX EX 15`. One instance per service polls. Lock renewed every 10s.

---

## 10. Saga Compensation

```mermaid
sequenceDiagram
    participant WF as Workflow
    participant Saga as Saga Manager
    participant Store as Postgres
    participant Act1 as reserve()
    participant Act2 as charge()
    participant Comp1 as releaseReservation()

    WF->>Act1: inventory.reserve(items)
    Act1-->>WF: Reservation ✓
    Note over Saga: Push: releaseReservation()

    WF->>Act2: provider.charge(method, amount)
    Act2--xWF: PaymentDeclinedException
    Note over Saga: Retries exhausted

    Saga->>Store: COMPENSATION_STARTED
    Saga->>Comp1: releaseReservation(reservationId)
    Comp1-->>Saga: Released ✓
    Saga->>Store: COMPENSATION_COMPLETED
    Saga->>Store: status = FAILED
```

Compensation stack is LIFO — unwound in reverse order of registration.

---

## 11. Parallel Execution

```mermaid
flowchart TD
    A["workflow.parallel(branches)"] --> B["Spawn N virtual threads"]
    B --> C1["Branch 0: task A"]
    B --> C2["Branch 1: task B"]
    B --> C3["Branch 2: task C"]
    C1 --> D["All complete"]
    C2 --> D
    C3 --> D
    D --> E["Collect results, continue"]

    style B fill:#4A90D9,color:#fff
```

Parallel branches use compound sequence keys: parent `5` → branches `5.0`, `5.1`, `5.2`. Each independently memoized on replay.

---

## 12. Admin Dashboard

```mermaid
graph TB
    subgraph "Services"
        S1["Service A"]
        S2["Service B"]
    end

    subgraph "Kafka"
        T["maestro.admin.events"]
    end

    subgraph "Admin App"
        C["Consumer"]
        A["Aggregator"]
        W["Thymeleaf + HTMX UI"]
        R["REST API"]
    end

    subgraph "Storage"
        PG[("Postgres")]
    end

    S1 --> T
    S2 --> T
    T --> C --> A --> PG
    PG --> W
    PG --> R
    R -->|"Retry / Signal / Terminate"| T
```

| Page | Content |
|---|---|
| **Overview** | Counts by status per service. Sparklines. Alerts. |
| **Workflow List** | Searchable, filterable by service/type/status/date. |
| **Workflow Detail** | Event timeline. Expandable payloads. Cross-service links. |
| **Failed** | Failure reason, stack trace. One-click retry. |
| **Signals** | Pending, pre-delivered, consumed. |
| **Timers** | Upcoming, overdue. |
| **Actions** | Retry, terminate, send signal, cancel timer. |

---

## 13. Startup and Recovery

```mermaid
flowchart TD
    A["Spring Boot starts"] --> B["Maestro auto-config"]
    B --> C["Flyway migrations"]
    C --> D["Scan @DurableWorkflow<br/>@MaestroActivities"]
    D --> E["Create activity proxies"]
    E --> F["Start Kafka consumers"]
    F --> G["Timer leader election"]
    G --> H["Query recoverable workflows"]
    H --> I{"Any?"}
    I -- "Yes" --> J["Replay + resume each"]
    J --> K["Adopt orphaned signals"]
    I -- "No" --> K
    K --> L["Ready"]

    style I fill:#F5A623,color:#fff
```

### Graceful Shutdown

SIGTERM → stop accepting new workflows → stop Kafka consumers → wait for in-flight activities (30s default) → release Valkey locks → shutdown.

---

## 14. Failure Modes

| Scenario | Behaviour |
|---|---|
| **JVM crash** | Recovery replays from Postgres. At-least-once activities. |
| **Activity timeout** | Retry with backoff. Exhausted → saga or fail. |
| **External API down** | Activity retries durably (configurable: attempts, backoff, max interval). Service can restart during retries. |
| **Kafka rebalance** | In-progress workflows continue. New tasks reroute. |
| **Postgres lost** | Execution blocks until restored. No activity without persistence. |
| **Valkey down** | Fallback to Postgres locking. Poll-based signals. |
| **Duplicate Kafka delivery** | Valkey dedup + unique constraint. |
| **Signal before workflow** | Stored with null instance. Adopted on start. |
| **Signal before await** | Stored unconsumed. Consumed when await reached. |

### Guarantees

- **Workflow progression:** Exactly-once (at-least-once activities, at-most-once result storage).
- **Activities:** At-least-once. Should be idempotent.
- **Signals:** At-least-once delivery. `consumed` flag prevents double-processing.
- **Timers:** At-least-once. Slight delay possible based on poll interval.

---

## 15. Technology Stack

| Component | Technology |
|---|---|
| Language | Java 21+ |
| Framework | Spring Boot 4.x / Spring Framework 7 |
| Build | Gradle Kotlin DSL (Gradle 9) |
| Database | PostgreSQL 14+ |
| Messaging | Apache Kafka via Spring Kafka 4.x |
| Coordination | Valkey / Redis via Lettuce |
| Serialization | Jackson 3 (`tools.jackson` packages) |
| Schema migration | Flyway 11.x |
| Null safety | JSpecify (aligned with Spring 7) |
| Admin UI | Thymeleaf + HTMX |
| Testing | JUnit 5, Testcontainers 2.0 |
