# Maestro — Product Requirements Document

## Durable Workflow Engine for Spring Boot Microservices

**Version:** 0.3.0-SNAPSHOT  
**Status:** Draft  
**Date:** March 2026

---

## 1. Problem Statement

Teams building microservice architectures on the Spring Boot / Kafka / Postgres stack face a recurring challenge: **reliable orchestration of long-running, multi-step business processes**.

Today, developers cobble together ad-hoc solutions using:

- Database flags and polling loops to track process state
- Kafka consumers with manual offset management for retries
- Cron jobs and scheduled tasks for timeouts and reminders
- Bespoke compensation logic scattered across services
- Redis-based locks and flags for coordination

This results in fragile, hard-to-debug systems where the "happy path" is 20% of the code and retry/recovery/compensation logic is the other 80%. When something fails at 2 AM, understanding *where* a process is and *how to resume it* requires forensic analysis across multiple services, databases, and Kafka topics.

### What Exists Today

| Solution | Trade-off |
|---|---|
| **Temporal.io / Cadence** | Powerful but requires deploying and operating a separate cluster (Cassandra/MySQL, Elasticsearch, multiple server roles). Heavy operational overhead for teams that already run Postgres and Kafka. |
| **Netflix Conductor** | Central server architecture, Elasticsearch dependency, limited Spring Boot integration. |
| **Camunda / Zeebe** | BPMN-oriented — requires modelling in a visual designer. Zeebe needs its own broker. |
| **Axon Framework** | Event sourcing focused, steep learning curve, commercial licensing for advanced features. |
| **Spring State Machine** | Too low-level — no persistence, no distribution, no activity management. |
| **Raw Kafka Streams** | Powerful but not designed for workflow orchestration. No concept of activities, signals, or compensation. |

**The gap:** There is no lightweight, embeddable durable workflow engine that leverages existing Spring Boot infrastructure (Postgres, Kafka, Redis/Valkey) without requiring a separate central server.

---

## 2. Vision

**Maestro** is an open-source, embeddable durable workflow engine delivered as a Spring Boot Starter. Add it to your existing microservices and get Temporal-grade workflow durability using infrastructure you already operate.

### Core Principles

1. **No central server.** The engine runs embedded in each microservice. Postgres stores state, Kafka handles messaging, Valkey manages coordination. Nothing new to deploy or operate.

2. **Write workflows as code.** Workflows are plain Java methods. Activities are Spring beans. No XML, no BPMN, no visual designers — just annotated Java with a clean API.

3. **Opinionated stack, swappable components.** First-class support for Postgres + Kafka + Valkey on Spring Boot 4 / Java 21+. Storage and messaging backends are behind SPIs for future extensibility.

4. **Orchestration within, choreography between.** Each service owns its workflows. Cross-service coordination happens via Kafka events and signals — not distributed transactions.

5. **Self-recovery by default.** Signals can arrive before a workflow starts, before it reaches the await point, or while the service is down. Maestro persists signals immediately and delivers them when the workflow is ready.

6. **Production-observable.** A standalone admin dashboard provides real-time visibility into workflow state across all services.

---

## 3. Target Audience

- **Primary:** Backend Java developers building microservices on Spring Boot who need reliable async processing and cross-service coordination.
- **Secondary:** Platform/infrastructure teams looking for a standardised workflow pattern across their organisation's services.
- **Tertiary:** Teams currently using Temporal or Conductor who want to reduce operational overhead.

---

## 4. Core Concepts

### 4.1 Durable Workflow

A workflow is a Java method annotated with `@WorkflowMethod` inside a class annotated with `@DurableWorkflow`. It describes a business process as a sequence of steps. Workflows are **durable** — if the JVM crashes, the workflow resumes from the last completed step on restart via the hybrid memoization execution model.

### 4.2 Activity

An activity is a method on a Spring bean that performs a side effect — calling an API, writing to a database, publishing a Kafka message. Activities are the unit of work and the unit of retry. Each activity execution is persisted with its result, forming a checkpoint.

### 4.3 Signal

A signal is a named, typed message sent *into* a running workflow from an external source. Workflows can await signals individually or collect multiple signals (quorum pattern).

**Self-recovery property:** Signals are persisted to Postgres immediately upon arrival. If the target workflow hasn't started yet, hasn't reached the await point yet, or is currently down, the signal waits in the database and is delivered when the workflow is ready.

### 4.4 Query

A query reads the current state of a running workflow without affecting its execution.

### 4.5 Durable Timer

A workflow can sleep for arbitrary durations. The timer is persisted — the worker does not need to remain alive.

### 4.6 Compensation (Saga)

Activities can declare compensating actions. If a workflow fails after several activities have completed, Maestro automatically invokes compensations in reverse order.

### 4.7 Parallel Execution

A workflow can execute multiple activities or awaits concurrently and wait for all (or a subset) to complete.

---

## 5. Key Design Decisions

### 5.1 Hybrid Memoization Execution Model

1. A workflow method runs on a Java 21 virtual thread.
2. Activity calls are intercepted by a proxy which checks Postgres for a stored result at the current sequence number.
3. **Replay path (found):** Return stored result instantly.
4. **Live path (not found):** Execute the activity, persist the result, return it.
5. On recovery, the workflow method is re-invoked. Completed steps return memoized results. Execution resumes from the first uncompleted step.

**Determinism constraint:** Workflow code between activity calls must be deterministic. No `Math.random()`, `LocalDateTime.now()`, or direct I/O — use `workflow.currentTime()`, `workflow.randomUUID()`.

### 5.2 Virtual Thread Execution

Workflows execute on Java 21 virtual threads. `workflow.sleep(Duration.ofDays(30))` blocks the virtual thread cheaply.

### 5.3 Cross-Service Model

**Orchestration within, choreography between.** Each service owns its workflow state. Inter-service communication is via Kafka events routed to workflow signals using `@MaestroSignalListener`.

### 5.4 Async-Start-After-REST Pattern

REST endpoints do minimal synchronous work and return immediately, then kick off workflows asynchronously via `startAsync()`. If the service crashes between the REST response and workflow start, recovery handles it.

### 5.5 Single Tenant

Single-tenant per deployment. Multi-tenancy is out of scope for v1.

---

## 6. Illustrative Example: Order Fulfilment

This example shows a typical e-commerce order workflow spanning two Maestro-enabled services — an **Order Service** and a **Payment Gateway Proxy** — to illustrate every core Maestro capability.

### 6.1 Scenario

1. Customer places an order via REST API — gets back an order ID immediately.
2. Order Workflow reserves inventory, requests payment, and waits for payment confirmation.
3. Payment Gateway Proxy picks up the payment request from Kafka and calls an external payment provider (unreliable, may be down). It retries durably.
4. Once payment is confirmed, the Order Workflow arranges shipment and sends a confirmation notification.
5. If payment fails permanently, the workflow compensates by releasing the inventory reservation.

### 6.2 Order Service — Order Fulfilment Workflow

```java
@DurableWorkflow(name = "order-fulfilment", taskQueue = "orders")
public class OrderFulfilmentWorkflow {

    @ActivityStub(startToCloseTimeout = "PT30S",
                  retryPolicy = @RetryPolicy(maxAttempts = 3))
    private InventoryActivities inventory;

    @ActivityStub(startToCloseTimeout = "PT10S")
    private MessagingActivities messaging;

    @ActivityStub(startToCloseTimeout = "PT30S")
    private ShippingActivities shipping;

    @ActivityStub(startToCloseTimeout = "PT10S")
    private NotificationActivities notifications;

    @WorkflowMethod
    @Saga(parallelCompensation = false)
    public OrderResult fulfil(OrderInput input) {

        // Step 1: Reserve inventory (compensatable — released on failure)
        ReservationConfirmation reservation = inventory.reserve(input.items());

        // Step 2: Request payment via Kafka to the Payment Gateway Proxy
        messaging.publishPaymentRequest(
            new PaymentRequest(input.orderId(), input.paymentMethod(), reservation.total())
        );

        // Step 3: Await payment result (self-recovery: signal may arrive before we get here)
        PaymentResult paymentResult = workflow.awaitSignal(
            "payment.result",
            PaymentResult.class,
            Duration.ofHours(1)
        );

        if (paymentResult == null) {
            // Timeout — no response from payment gateway
            notifications.notifyCustomer(input.customerId(), "Payment timed out");
            return OrderResult.failed("Payment timed out");
            // @Saga compensation: inventory.releaseReservation() runs automatically
        }

        if (!paymentResult.isSuccess()) {
            notifications.notifyCustomer(input.customerId(),
                "Payment failed: " + paymentResult.reason());
            return OrderResult.failed(paymentResult.reason());
            // @Saga compensation: inventory.releaseReservation() runs automatically
        }

        // Step 4: Arrange shipment
        ShipmentConfirmation shipment = shipping.createShipment(
            input.orderId(), input.shippingAddress(), reservation.warehouseId()
        );

        // Step 5: Notify customer
        notifications.notifyCustomer(input.customerId(),
            "Order confirmed! Tracking: " + shipment.trackingNumber());

        return OrderResult.success(input.orderId(), shipment.trackingNumber());
    }

    @QueryMethod
    public OrderStatus getStatus() {
        return currentStatus;
    }
}
```

### 6.3 Payment Gateway Proxy — Payment Processing Workflow

```java
@DurableWorkflow(name = "payment-processing", taskQueue = "payments")
public class PaymentProcessingWorkflow {

    @ActivityStub(
        startToCloseTimeout = "PT60S",
        retryPolicy = @RetryPolicy(
            maxAttempts = 30,
            initialInterval = "PT5S",
            maxInterval = "PT10M",
            backoffMultiplier = 2.0
        ))
    private PaymentProviderActivities provider;

    @ActivityStub(startToCloseTimeout = "PT10S")
    private PaymentMessagingActivities messaging;

    @WorkflowMethod
    public PaymentProcessingResult process(PaymentRequest request) {

        try {
            // Retries automatically if the payment provider is down
            PaymentConfirmation confirmation = provider.charge(
                request.paymentMethod(), request.amount()
            );

            messaging.publishPaymentResult(
                request.orderId(),
                PaymentResult.success(confirmation.transactionId())
            );

            return PaymentProcessingResult.success(confirmation.transactionId());

        } catch (PaymentDeclinedException e) {
            // Permanent failure — card declined, insufficient funds, etc.
            messaging.publishPaymentResult(
                request.orderId(),
                PaymentResult.failed(e.getMessage())
            );
            return PaymentProcessingResult.declined(e.getMessage());
        }
    }
}
```

### 6.4 Signal Routing

```java
// In the Order Service — routes payment results to the order workflow
@MaestroSignalListener(
    topic = "payments.results",
    signalName = "payment.result"
)
public SignalRouting routePaymentResult(PaymentResultEvent event) {
    return SignalRouting.builder()
        .workflowId("order-" + event.orderId())
        .payload(new PaymentResult(event.success(), event.transactionId(), event.reason()))
        .build();
}
```

### 6.5 Starting the Workflow from REST

```java
@RestController
@RequiredArgsConstructor
public class OrderController {

    private final MaestroClient maestro;
    private final OrderRepository orders;

    @PostMapping("/orders")
    public ResponseEntity<OrderResponse> placeOrder(@RequestBody PlaceOrderRequest request) {
        String orderId = UUID.randomUUID().toString();
        orders.save(new OrderEntity(orderId, request));

        maestro.newWorkflow(OrderFulfilmentWorkflow.class,
            WorkflowOptions.builder()
                .workflowId("order-" + orderId)
                .build()
        ).startAsync(new OrderInput(orderId, request));

        return ResponseEntity.accepted().body(new OrderResponse(orderId));
    }

    @GetMapping("/orders/{orderId}/status")
    public OrderStatus getStatus(@PathVariable String orderId) {
        return maestro.getWorkflow("order-" + orderId, OrderFulfilmentWorkflow.class)
            .query(OrderFulfilmentWorkflow::getStatus);
    }
}
```

---

## 7. API Summary

### 7.1 Annotations

| Annotation | Target | Purpose |
|---|---|---|
| `@DurableWorkflow(name, taskQueue)` | Class | Marks a durable workflow definition |
| `@WorkflowMethod` | Method | Main entry point of the workflow |
| `@ActivityStub(...)` | Field | Injects a memoizing proxy for activities |
| `@QueryMethod` | Method | Read-only query on workflow state |
| `@Saga(...)` | Method | Enables automatic saga compensation |
| `@MaestroActivities(taskQueue)` | Class | Marks a Spring bean as containing activities |
| `@Activity` | Method | Marks a checkpointed, retriable activity |
| `@Compensate("methodName")` | Method | Declares compensation for saga rollback |
| `@RetryPolicy(...)` | Annotation | Configures retry behaviour |
| `@MaestroSignalListener(topic, signalName)` | Method | Routes Kafka events to workflow signals |

### 7.2 MaestroClient API

```java
WorkflowHandle<R> handle = maestro.newWorkflow(WorkflowClass.class, options).startAsync(input);
R result = maestro.newWorkflow(WorkflowClass.class, options).startAndWait(input, timeout);
maestro.getWorkflow(workflowId).signal(signalName, payload);
T state = maestro.getWorkflow(workflowId, WorkflowClass.class).query(WorkflowClass::queryMethod);
```

### 7.3 Workflow Context API

```java
T result = workflow.awaitSignal(name, type, timeout);
List<T> results = workflow.collectSignals(name, type, count, timeout);
workflow.sleep(duration);
List<Object> results = workflow.parallel(tasks);
T result = workflow.retryUntil(supplier, predicate, retryOptions);
Instant now = workflow.currentTime();
String id = workflow.randomUUID();
workflow.addCompensation(() -> activity.undo(args));
```

---

## 8. Module Structure

```
maestro/
├── maestro-core                    # Engine: state machine, memoization, timers, signals, saga
├── maestro-spring-boot-starter     # Auto-configuration, annotations, Spring integration
├── maestro-store-jdbc              # JDBC WorkflowStore abstraction
├── maestro-store-postgres          # Postgres implementation + Flyway migrations
├── maestro-messaging-kafka         # Kafka WorkflowMessaging implementation
├── maestro-lock-valkey             # Valkey/Redis distributed locking
├── maestro-admin                   # Standalone dashboard (Spring Boot + Thymeleaf + HTMX)
├── maestro-admin-client            # Lifecycle event publisher for services
├── maestro-test                    # In-memory engine, time control, TestWorkflowEnvironment
├── maestro-samples/
│   ├── sample-order-service        # Order fulfilment workflow
│   └── sample-payment-gateway      # Payment processing with durable retries
└── docs/
```

---

## 9. Configuration

```yaml
maestro:
  enabled: true
  service-name: order-service

  store:
    type: postgres
    table-prefix: maestro_
    schema: maestro

  messaging:
    type: kafka
    consumer-group: ${maestro.service-name}
    topics:
      tasks: maestro.tasks.orders
      signals: maestro.signals.order-service
      admin-events: maestro.admin.events

  lock:
    type: valkey
    key-prefix: "maestro:lock:"
    ttl: 30s

  worker:
    task-queues:
      - name: orders
        concurrency: 10
        activity-concurrency: 20

  timer:
    poll-interval: 5s
    batch-size: 100

  retry:
    default-max-attempts: 3
    default-initial-interval: 1s
    default-max-interval: 60s
    default-backoff-multiplier: 2.0

  admin:
    events:
      enabled: true
      topic: maestro.admin.events

  archival:
    retention: 90d
    strategy: delete
```

---

## 10. Admin Dashboard

Standalone Spring Boot application. Consumes `maestro.admin.events` from Kafka, stores aggregated state in its own Postgres, serves a Thymeleaf + HTMX UI. Completely decoupled — downtime has zero impact on services.

**Features (v1):** Overview dashboard with aggregate counts and sparklines. Workflow list (searchable, filterable). Workflow detail with event timeline. Cross-service correlation via shared identifiers. Failed workflow view with one-click retry. Signal monitor (pending, pre-delivered, consumed). Timer monitor. Manual actions (retry, terminate, send signal).

---

## 11. Testing

```java
@MaestroTest
class OrderFulfilmentWorkflowTest {

    @Inject
    private TestWorkflowEnvironment testEnv;

    @Test
    void shouldFulfilOrder_whenPaymentSucceeds() {
        testEnv.registerActivities(new InventoryActivitiesImpl(mockRepo));
        testEnv.registerActivities(mock(MessagingActivities.class));
        testEnv.registerActivities(mock(ShippingActivities.class));
        testEnv.registerActivities(mock(NotificationActivities.class));

        var handle = testEnv.startWorkflow(OrderFulfilmentWorkflow.class, orderInput);

        handle.signal("payment.result", PaymentResult.success("txn-123"));

        OrderResult result = handle.getResult(Duration.ofSeconds(5));
        assertThat(result.status()).isEqualTo(Status.SUCCESS);
    }

    @Test
    void shouldCompensateInventory_whenPaymentFails() {
        // ... setup ...
        var handle = testEnv.startWorkflow(OrderFulfilmentWorkflow.class, orderInput);

        handle.signal("payment.result", PaymentResult.failed("Card declined"));

        OrderResult result = handle.getResult(Duration.ofSeconds(5));
        assertThat(result.status()).isEqualTo(Status.FAILED);
        verify(inventoryActivities).releaseReservation(any());
    }

    @Test
    void shouldHandleSelfRecovery_paymentResultArrivesEarly() {
        testEnv.preDeliverSignal("order-abc", "payment.result",
            PaymentResult.success("txn-123"));

        var handle = testEnv.startWorkflow(OrderFulfilmentWorkflow.class, orderInput);

        // Workflow picks up pre-delivered signal immediately
        OrderResult result = handle.getResult(Duration.ofSeconds(5));
        assertThat(result.status()).isEqualTo(Status.SUCCESS);
    }
}
```

---

## 12. Tech Stack

| Component | Technology | Version |
|---|---|---|
| Language | Java | 21+ |
| Framework | Spring Boot | 4.x (Spring Framework 7) |
| Build | Gradle | Kotlin DSL, Gradle 9 |
| Database | PostgreSQL | 14+ |
| Messaging | Apache Kafka | via Spring Kafka 4.x |
| Cache/Lock | Valkey or Redis | via Lettuce |
| Serialization | Jackson 3 | `tools.jackson` packages |
| Schema migration | Flyway | 11.x |
| Null safety | JSpecify | Aligned with Spring Framework 7 |
| Testing | JUnit 5, Testcontainers 2.0 | Integration tests with real infra |

---

## 13. Non-Goals (v1)

- Multi-tenancy / namespace isolation
- Visual workflow designer
- Cross-service distributed workflows
- Non-JVM languages
- CQRS / Event Sourcing
- Full workflow versioning / migration tooling
- Messaging backends beyond Kafka

---

## 14. Success Criteria

### Open Source (6 months)
500+ GitHub stars. 5+ production deployments. Active contributors.

### Technical
Workflow with 100 activities recovers in < 5 seconds. 10,000 concurrent instances per service with < 100ms dispatch overhead. Zero lost state under crash/rebalance/failover. Admin dashboard < 2s lag.

### Developer Experience
Clone to running sample app: < 15 minutes. Add Maestro to existing service: < 30 minutes. First working workflow: < 1 hour.

---

## 15. Open Source

**Licence:** Apache 2.0.

**Why open source:** Trust (teams need to read engine code). Network effects (more users = more battle-tested). Real gap in the ecosystem. Community-driven backends via SPI.

**Community:** GitHub-first. Documentation site. Sample apps. Discord/Slack. Conference talks.
