# Testing Guide

Fast, deterministic workflow testing without infrastructure.

[← Back to README](../README.md)

---

## Overview

The `maestro-test` module provides in-memory implementations of all SPIs. Tests work the same regardless of which production messaging or lock backend you use.

The module provides an in-memory Maestro engine for testing durable workflows. No Postgres, no Kafka, no Valkey. Workflows run on real virtual threads, just like production. Test time is controllable -- advance minutes, hours, or days instantly.

What you get:

- **Real execution engine.** The same `WorkflowExecutor` that runs in production, backed by in-memory SPI implementations.
- **Deterministic time.** A `ControllableClock` replaces wall-clock time. Call `advanceTime(Duration.ofDays(30))` and all due timers fire immediately.
- **Signal self-recovery testing.** Pre-deliver signals before a workflow starts to verify the orphan adoption path.
- **Full event log inspection.** Assert on every activity completion, signal receipt, and timer firing.
- **Sub-second test execution.** No containers to start, no connections to establish.

---

## Setup

Add the test dependency:

```kotlin
// build.gradle.kts
dependencies {
    testImplementation("io.b2mash.maestro:maestro-test")
}
```

The module transitively brings in `maestro-core` and JUnit 5. No other dependencies are needed.

---

## TestWorkflowEnvironment

`TestWorkflowEnvironment` is the central test fixture. It creates a real `WorkflowExecutor` backed entirely by in-memory implementations:

| Production SPI        | Test Implementation           |
|----------------------|-------------------------------|
| `WorkflowStore`      | `InMemoryWorkflowStore`       |
| `WorkflowMessaging`  | `InMemoryWorkflowMessaging`   |
| `DistributedLock`    | `InMemoryDistributedLock`     |
| `SignalNotifier`     | `InMemorySignalNotifier`      |
| Wall clock           | `ControllableClock`           |

### Creating an Environment

```java
// Default Jackson 3 ObjectMapper
var env = TestWorkflowEnvironment.create();

// Custom ObjectMapper (e.g., with extra modules or custom serializers)
var env = TestWorkflowEnvironment.create(customMapper);
```

### Lifecycle

Always shut down the environment after your test to release virtual threads:

```java
@AfterEach
void tearDown() {
    env.shutdown();
}
```

Or use the [`@MaestroTest` annotation](#junit-5-extension) for automatic lifecycle management.

---

## Registering Activities

Before starting a workflow, register implementations for every activity interface the workflow uses. These can be real implementations or mocks.

### Explicit Registration

Specify the interface and implementation directly:

```java
env.registerActivities(InventoryActivities.class, mockInventoryActivities);
env.registerActivities(ShippingActivities.class, mockShippingActivities);
```

The first argument must be an interface. The second is the implementation (real or mock) that will back the activity proxy during execution.

### Auto-Detect Registration

Pass implementation instances directly -- Maestro scans each object's interfaces for the `@Activity` annotation:

```java
env.registerActivities(new MockInventoryActivities(), new MockShippingActivities());
```

If an implementation's interface has `@Activity`, that interface is used. Otherwise, the first implemented interface is used as a fallback.

### Missing Activities

If a workflow references an `@ActivityStub` field whose interface has not been registered, `startWorkflow` throws `IllegalStateException`:

```
No activity implementation registered for com.example.InventoryActivities.
Call registerActivities(InventoryActivities.class, impl) before startWorkflow().
```

---

## Starting Workflows

### Auto-Generated Workflow ID

When you do not need a specific workflow ID:

```java
var handle = env.startWorkflow(OrderFulfilmentWorkflow.class, new OrderInput("item-1"));
```

The environment generates an ID from the workflow type name and an incrementing counter (e.g., `OrderFulfilmentWorkflow-1`).

### Explicit Workflow ID

Use an explicit ID when you need to pre-deliver signals or reference the workflow by a known business ID:

```java
var handle = env.startWorkflow("order-abc", OrderFulfilmentWorkflow.class, orderInput);
```

### Null Input

Workflows that take no input can pass `null`:

```java
var handle = env.startWorkflow(ReminderWorkflow.class, null);
```

---

## Working with TestWorkflowHandle

`startWorkflow` returns a `TestWorkflowHandle` -- your interface for interacting with the running workflow.

### Waiting for a Result

Block until the workflow reaches a terminal state and return the deserialized result. Throws `TimeoutException` if the workflow does not complete in time, or `RuntimeException` if the workflow failed:

```java
OrderResult result = handle.getResult(OrderResult.class, Duration.ofSeconds(5));
```

Note: the type parameter comes first, then the timeout.

### Waiting Without a Result

Block until completion when you do not need the return value:

```java
handle.awaitCompletion(Duration.ofSeconds(5));
```

### Checking Status

Query the current workflow status without blocking:

```java
assertEquals(WorkflowStatus.COMPLETED, handle.getStatus());
```

Status values: `RUNNING`, `WAITING_SIGNAL`, `WAITING_TIMER`, `COMPLETED`, `FAILED`, `COMPENSATING`, `TERMINATED`.

### Delivering Signals

Send a signal to the running workflow:

```java
handle.signal("payment.result", new PaymentResult(true, "txn-123"));
```

The signal is delivered through the same code path as production -- persisted to the store, then the workflow's `awaitSignal` call resumes.

### Inspecting the Event Log

Access the memoization event log for fine-grained assertions:

```java
List<WorkflowEvent> events = handle.getEvents();
assertEquals(5, events.size());
assertTrue(events.stream().anyMatch(e -> e.eventType() == EventType.ACTIVITY_COMPLETED));
```

### Querying a Running Workflow

Invoke a `@QueryMethod` on a workflow that is still executing (e.g., parked on a signal or timer):

```java
OrderStatus status = handle.query("getStatus", null, OrderStatus.class);
assertEquals("processing", status.phase());
```

The query name matches the `@QueryMethod(name = "...")` annotation on the workflow class. The second argument is the query parameter (or `null` if the query takes no arguments).

### Identity

```java
String workflowId = handle.getWorkflowId();   // Business ID
UUID instanceId = handle.getInstanceId();       // Internal instance UUID
```

---

## Time Control

Durable timers are central to Maestro workflows. In production, `workflow.sleep(Duration.ofDays(30))` creates a timer in Postgres that fires 30 days later. In tests, you control time directly.

### Advancing Time

```java
// Workflow calls workflow.sleep(Duration.ofDays(30))
var handle = env.startWorkflow(ReminderWorkflow.class, input);

// Workflow is now parked on a timer
assertEquals(WorkflowStatus.WAITING_TIMER, handle.getStatus());

// Fast-forward 30 days -- timer fires instantly
env.advanceTime(Duration.ofDays(30));

// Workflow resumes and completes
var result = handle.getResult(ReminderResult.class, Duration.ofSeconds(5));
```

### How It Works

`advanceTime(duration)` does two things:

1. **Advances the `ControllableClock`** by the given duration.
2. **Fires all due timers** whose `fireAt <= clock.now()`.

The method loops to handle cascading timers: if timer A fires and the workflow immediately creates timer B that is also due, timer B fires in the same `advanceTime` call. A safety limit of 1,000 iterations prevents infinite timer loops.

### Direct Clock Access

For advanced scenarios, access the clock directly:

```java
ControllableClock clock = env.getClock();
Instant now = env.currentTime();

// Set to a specific instant
clock.setTime(Instant.parse("2026-01-15T09:00:00Z"));

// Advance incrementally
clock.advance(Duration.ofHours(2));
```

---

## Self-Recovery Testing

Maestro guarantees that signals are never lost, even if they arrive before the workflow starts. The `preDeliverSignal` method lets you test this path:

```java
@Test
void shouldPickUpPreDeliveredSignal() throws TimeoutException {
    // Signal arrives BEFORE workflow starts
    env.preDeliverSignal("order-abc", "payment.result",
        new PaymentResult(true, "txn-123"));

    // Workflow starts -- adopts the orphaned signal
    var handle = env.startWorkflow("order-abc",
        OrderFulfilmentWorkflow.class, orderInput);

    // Workflow completes immediately (signal was already waiting)
    var result = handle.getResult(OrderResult.class, Duration.ofSeconds(5));
    assertEquals(Status.SUCCESS, result.status());
}
```

The signal is persisted to the `InMemoryWorkflowStore` with a `null` workflow instance ID. When the workflow starts, the executor calls `adoptOrphanedSignals`, linking the signal to the new instance. When the workflow reaches `awaitSignal("payment.result", ...)`, it finds the pre-delivered signal and consumes it immediately.

Three signal delivery scenarios this covers:

1. **Signal before `awaitSignal()`** -- stored, consumed when the workflow reaches the await point.
2. **Signal before workflow starts** -- stored with null instance ID, adopted on start.
3. **Signal while service is down** -- persisted in the store, found on recovery.

See [Concepts](concepts.md) for a deeper explanation of the self-recovery model.

---

## JUnit 5 Extension

For declarative test setup, annotate your test class with `@MaestroTest`. The extension creates a fresh `TestWorkflowEnvironment` before each test and shuts it down after:

```java
@MaestroTest
class OrderFulfilmentWorkflowTest {

    @Test
    void shouldFulfilOrder(TestWorkflowEnvironment env) {
        env.registerActivities(InventoryActivities.class, mockInventory);

        var handle = env.startWorkflow(OrderFulfilmentWorkflow.class, orderInput);
        handle.signal("payment.result", new PaymentResult(true, "txn-123"));

        var result = handle.getResult(OrderResult.class, Duration.ofSeconds(5));
        assertEquals("SUCCESS", result.status());
    }
}
```

`@MaestroTest` is shorthand for `@ExtendWith(MaestroTestExtension.class)`. The extension uses JUnit 5 parameter resolution -- declare `TestWorkflowEnvironment` as a test method parameter and it is injected automatically. Each test gets an isolated environment.

---

## Advanced: Store and Messaging Assertions

For tests that need to verify internal state beyond the workflow result, access the underlying in-memory SPIs directly.

### Inspecting the Store

```java
InMemoryWorkflowStore store = env.getStore();

// Verify workflow instance exists and has expected state
var instance = store.getInstance("order-abc");
assertTrue(instance.isPresent());
assertEquals(WorkflowStatus.COMPLETED, instance.get().status());

// Inspect all signals (consumed and unconsumed)
List<WorkflowSignal> signals = store.getAllSignals();
assertEquals(1, signals.size());
assertTrue(signals.getFirst().consumed());

// Inspect all timers
List<WorkflowTimer> timers = store.getAllTimers();
assertEquals(1, timers.size());
assertEquals(TimerStatus.FIRED, timers.getFirst().status());
```

### Inspecting Lifecycle Events

```java
InMemoryWorkflowMessaging messaging = env.getMessaging();

// Verify lifecycle events were published (e.g., for maestro-admin integration)
List<WorkflowLifecycleEvent> events = messaging.getLifecycleEvents();
assertFalse(events.isEmpty());
```

### Clearing State Between Tests

If you reuse an environment (not typical, but possible), clear all state:

```java
env.getStore().clear();
env.getMessaging().clear();
```

---

## Full Test Example

A complete test class demonstrating the most common testing patterns -- happy path, failure with saga compensation, time control, and event inspection:

```java
import io.b2mash.maestro.test.TestWorkflowEnvironment;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderFulfilmentWorkflowTest {

    private TestWorkflowEnvironment env;
    private InventoryActivities mockInventory;
    private PaymentActivities mockPayment;
    private ShippingActivities mockShipping;
    private NotificationActivities mockNotifications;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.create();

        mockInventory = mock(InventoryActivities.class);
        mockPayment = mock(PaymentActivities.class);
        mockShipping = mock(ShippingActivities.class);
        mockNotifications = mock(NotificationActivities.class);

        when(mockInventory.reserve(any()))
            .thenReturn(new ReservationConfirmation("res-1", "WH-EAST",
                new BigDecimal("89.97")));
        when(mockShipping.createShipment(any(), any(), any()))
            .thenReturn(new ShipmentConfirmation("TRACK-12345"));

        env.registerActivities(InventoryActivities.class, mockInventory);
        env.registerActivities(PaymentActivities.class, mockPayment);
        env.registerActivities(ShippingActivities.class, mockShipping);
        env.registerActivities(NotificationActivities.class, mockNotifications);
    }

    @AfterEach
    void tearDown() {
        env.shutdown();
    }

    @Test
    void shouldCompleteOrder_whenPaymentSucceeds() throws TimeoutException {
        var handle = env.startWorkflow(
            OrderFulfilmentWorkflow.class, orderInput);

        // Workflow is now waiting for payment signal
        handle.signal("payment.result",
            new PaymentResult(true, "txn-123", null));

        var result = handle.getResult(OrderResult.class, Duration.ofSeconds(5));

        assertEquals("SUCCESS", result.status());
        assertEquals("TRACK-12345", result.trackingNumber());

        verify(mockInventory).reserve(any());
        verify(mockShipping).createShipment(any(), any(), any());
        verify(mockNotifications, times(1)).notifyCustomer(any(), any());
    }

    @Test
    void shouldCompensateInventory_whenPaymentFails() throws TimeoutException {
        var handle = env.startWorkflow(
            OrderFulfilmentWorkflow.class, orderInput);

        handle.signal("payment.result",
            new PaymentResult(false, null, "Card declined"));

        var result = handle.getResult(OrderResult.class, Duration.ofSeconds(5));

        assertEquals("FAILED", result.status());

        // Saga compensation should have released inventory
        verify(mockInventory).releaseReservation(any());
        verify(mockShipping, never()).createShipment(any(), any(), any());
    }

    @Test
    void shouldSendReminder_afterThirtyDayWait() throws TimeoutException {
        var handle = env.startWorkflow(
            OrderFollowUpWorkflow.class, orderInput);

        // Workflow sleeps for 30 days before sending a follow-up
        assertEquals(WorkflowStatus.WAITING_TIMER, handle.getStatus());

        // Fast-forward 30 days
        env.advanceTime(Duration.ofDays(30));

        handle.awaitCompletion(Duration.ofSeconds(5));

        verify(mockNotifications).sendFollowUp(any());
    }

    @Test
    void shouldHandlePreDeliveredSignal() throws TimeoutException {
        var workflowId = "order-early-signal";

        // Signal arrives before workflow starts
        env.preDeliverSignal(workflowId, "payment.result",
            new PaymentResult(true, "txn-early", null));

        var handle = env.startWorkflow(workflowId,
            OrderFulfilmentWorkflow.class, orderInput);

        // Workflow picks up the pre-delivered signal immediately
        var result = handle.getResult(OrderResult.class, Duration.ofSeconds(5));
        assertEquals("SUCCESS", result.status());
    }

    @Test
    void shouldRecordActivityEventsInLog() throws TimeoutException {
        var handle = env.startWorkflow(
            OrderFulfilmentWorkflow.class, orderInput);

        handle.signal("payment.result",
            new PaymentResult(true, "txn-123", null));
        handle.getResult(OrderResult.class, Duration.ofSeconds(5));

        var events = handle.getEvents();
        assertFalse(events.isEmpty());

        // Verify specific activity completions were memoized
        long activityCount = events.stream()
            .filter(e -> e.eventType() == EventType.ACTIVITY_COMPLETED)
            .count();
        assertTrue(activityCount >= 3, "Expected at least 3 activity completions");
    }
}
```

---

## Tips

**Keep test timeouts short.** Use `Duration.ofSeconds(5)` for `getResult` and `awaitCompletion`. If a test needs more than a few seconds of real wall-clock time, something is likely stuck -- the timeout helps you find it fast.

**Use Mockito for activity implementations.** Activity interfaces are the natural seam for mocking. Mock the activity, stub the return values, and verify interactions after the workflow completes.

**Test failure paths explicitly.** Workflow error handling and saga compensation are the hardest code paths to get right. Write tests that trigger failures at each activity step and verify the correct compensations run.

**One environment per test.** Each `TestWorkflowEnvironment` has its own store, clock, and executor. Sharing environments between tests leads to flaky ordering issues. Use `@BeforeEach` or `@MaestroTest` to get a fresh environment.

**Avoid `Thread.sleep()` in test code.** If you need to wait for a workflow to reach a specific status before delivering a signal, poll the status with a short interval rather than sleeping for a fixed duration:

```java
private static void waitForStatus(TestWorkflowHandle handle,
                                   WorkflowStatus expected,
                                   Duration timeout) {
    var deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
        if (handle.getStatus() == expected) {
            return;
        }
        Thread.sleep(Duration.ofMillis(10));
    }
}
```

---

## See Also

- [Concepts](concepts.md) -- Core concepts: memoization, replay, determinism constraints
- [Configuration](configuration.md) -- Runtime configuration reference
- [Architecture](maestro-architecture.md) -- System architecture and failure modes
