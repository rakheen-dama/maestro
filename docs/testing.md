# Testing Guide

Fast, deterministic workflow testing without infrastructure.

[← Back to README](../README.md)

---

## Overview

The `maestro-test` module provides in-memory implementations of all SPIs. Tests work the same regardless of which production messaging or lock backend you use.

The module provides an in-memory Maestro engine for testing durable workflows. No Postgres, no Kafka. Workflows run on real virtual threads, just like production. Test time is controllable -- advance minutes, hours, or days instantly.

What you get:

- **Real execution engine.** The same `WorkflowExecutor` that runs in production, backed by in-memory SPI implementations.
- **Deterministic time.** A `ControllableClock` replaces wall-clock time: the engine arms every `sleep()` timer and `awaitSignal()` deadline from it. Call `advanceTime(Duration.ofDays(30))` and every timer due by then fires immediately — see [How It Works](#how-it-works).
- **Signal self-recovery testing.** Pre-deliver signals before a workflow starts to verify the orphan adoption path.
- **Crash-recovery testing.** Crash the node part-way through a run and recover it, so the run's history is read back the way a production restart reads it. This covers result deserialisation, the replay guards and the activity-argument fingerprint. See [Crash-Recovery Testing](#crash-recovery-testing).
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

The environment generates an ID from the workflow type name (`@DurableWorkflow(name = ...)`, or the class's simple name when that is empty) and an incrementing counter (e.g., `order-fulfilment-1`).

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

### Asserting on a Failure

`getResult` and `awaitCompletion` throw when the workflow fails. To assert on the failure itself, wait with `awaitTerminal`, which returns the terminal status without throwing, then read the recorded exception with `getFailure`:

```java
assertEquals(WorkflowStatus.FAILED, handle.awaitTerminal(Duration.ofSeconds(5)));
var failure = handle.getFailure().orElseThrow();
assertEquals(OrderFailedException.class.getName(), failure.exceptionType());
assertEquals("Payment failed: Card declined", failure.message());
```

A workflow is `FAILED` only when its `@WorkflowMethod` *throws*. That is also the only case in which saga compensation runs. A workflow that returns a "failed" value is `COMPLETED`. See [Pitfalls §1](pitfalls.md#1-compensation-runs-when-the-workflow-throws-not-when-it-returns-a-failure).

### Checking Status

Read the current workflow status without blocking:

```java
assertEquals(WorkflowStatus.COMPLETED, handle.getStatus());
```

Status values: `RUNNING`, `WAITING_SIGNAL`, `WAITING_TIMER`, `COMPLETED`, `FAILED`, `COMPENSATING`, `TERMINATED`.

### Waiting for a Status

`startWorkflow` returns immediately. To wait until the workflow has parked, use `awaitStatus` with a non-terminal status. You need this before `advanceTime`, because a timer that does not exist yet cannot be fired:

```java
handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(5));
```

It throws `TimeoutException` naming the current status if the status is not reached in time. It throws `IllegalStateException` if the workflow ends first. A terminal status is rejected with `IllegalArgumentException`: use `awaitTerminal`, which also waits for the terminal event in the log.

### Delivering Signals

Send a signal to the running workflow:

```java
handle.signal("payment.result", PaymentResult.success("txn-123"));
```

The signal is delivered through the same code path as production -- persisted to the store, then the workflow's `awaitSignal` call resumes.

### Inspecting the Event Log

Access the memoization event log for fine-grained assertions:

```java
List<WorkflowEvent> events = handle.getEvents();
assertEquals(5, events.size());
assertTrue(events.stream().anyMatch(e -> e.eventType() == EventType.ACTIVITY_COMPLETED));
```

**Read the log after `getResult` or `awaitCompletion` has returned.** The engine writes a run's terminal status and its terminal event (`WORKFLOW_COMPLETED` / `WORKFLOW_FAILED`) in one atomic store write (`WorkflowStore.completeInstance`, a single transaction on Postgres), so a terminal status always comes with its event -- the old interval in which the status read `COMPLETED` while the event had not yet been appended is gone (Issue 26). `getResult` and `awaitCompletion` still wait for the event itself, and they remain the recommended wait: they say what the assertions that follow depend on, and they fail loudly with `TimeoutException` if the event never lands.

### Querying a Running Workflow

Invoke a `@QueryMethod` on a workflow that is still executing (e.g., parked on a signal or timer):

```java
OrderStatus status = handle.query("getStatus", null, OrderStatus.class);
assertEquals("AWAITING_PAYMENT", status.currentStep());
```

The query name is the `@QueryMethod(name = "...")` value, or the method name when that is empty. The second argument is the query parameter (or `null` if the query takes no arguments). As in production, only a running workflow answers. A workflow that has ended throws `WorkflowNotQueryableException`. Assert on its durable state with `getStatus()`, `getResult(...)` or `getFailure()`.

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

// startWorkflow returns at once: wait until the workflow has armed its timer
// and parked
handle.awaitStatus(WorkflowStatus.WAITING_TIMER, Duration.ofSeconds(5));

// Fast-forward 30 days -- the timer fires instantly. Its fireAt was armed
// from the test clock (now + 30 days), so advancing exactly 30 days reaches it.
env.advanceTime(Duration.ofDays(30));

// Workflow resumes and completes
var result = handle.getResult(ReminderResult.class, Duration.ofSeconds(5));
```

### How It Works

`advanceTime(duration)` does two things:

1. **Advances the `ControllableClock`** by the given duration, to a target instant fixed for the call.
2. **Fires all due timers** whose `fireAt` is at or before that target.

**Why an exact advance works.** The environment hands its `ControllableClock` to the engine, which arms every durable deadline from it: `sleep(d)` gets `fireAt = clock.now() + d`, and a parked `awaitSignal(name, type, timeout)` gets `deadline = clock.now() + timeout`, decided against the same clock. The wall clock plays no part, so a 1-second deadline cannot expire while a slow test is still getting ready, and `advanceTime(timeout)` lands exactly on the deadline — no `plusSeconds(1)` margin. `workflow.currentTime()` reads the same clock, so `collectSignals` budgets its overall timeout in test time too. (Production uses the system clock; only `TestWorkflowEnvironment` swaps it.)

**What one call fires.** A workflow that a call wakes and that parks again arms its next timer from the advanced clock, at `target + d`. For any positive `d` that is after the target, so the same call never fires it — the next `advanceTime` does. So one `advanceTime` fires only timers that were already due at its target; it never chains on into a timer the woken workflow arms with a positive duration, however long that duration is relative to the advance. A zero- or negative-duration timer armed by a woken workflow is due at once, so whether this call or the next one fires it depends on thread timing — don't rely on either. The loop inside the call repeats while timers due by the target remain (more than one batch of 100, or such a timer); a safety limit of 1,000 iterations prevents infinite timer loops.

`advanceTime` fires `awaitSignal` deadlines exactly as it fires `sleep()` timers: a 7-day approval wait times out with one `env.advanceTime(Duration.ofDays(7))` instead of seven days of wall clock — and a signal delivered before the advanced clock reaches the deadline still wins. Wait for the workflow to be parked (status `WAITING_SIGNAL`, or its `TIMER_SCHEDULED` memo in the event log) before advancing: advancing first moves the clock past nothing, and the await then arms its deadline from the already-advanced clock.

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

Maestro guarantees that signals are never lost, even if they arrive before the workflow starts (unless the operator has explicitly disabled redelivery — see `maestro.messaging.redelivery.enabled` in `docs/configuration.md` — which restores at-most-once handler semantics). The `preDeliverSignal` method lets you test this path:

```java
@Test
void shouldPickUpPreDeliveredSignal() throws TimeoutException {
    // Signal arrives BEFORE workflow starts
    env.preDeliverSignal("order-abc", "payment.result",
        PaymentResult.success("txn-123"));

    // Workflow starts -- adopts the orphaned signal
    var handle = env.startWorkflow("order-abc",
        OrderFulfilmentWorkflow.class, orderInput);

    // Workflow completes without parking (the signal was already waiting)
    var result = handle.getResult(OrderResult.class, Duration.ofSeconds(5));
    assertEquals(orderInput.orderId(), result.orderId());
}
```

The signal is persisted to the `InMemoryWorkflowStore` with a `null` workflow instance ID. When the workflow starts, the executor calls `adoptOrphanedSignals`, linking the signal to the new instance. When the workflow reaches `awaitSignal("payment.result", ...)`, it finds the pre-delivered signal and consumes it immediately.

Three signal delivery scenarios this covers:

1. **Signal before `awaitSignal()`** -- stored, consumed when the workflow reaches the await point.
2. **Signal before workflow starts** -- stored with null instance ID, adopted on start.
3. **Signal while service is down** -- persisted in the store, found on recovery. Test it with `env.crashNow()`, `handle.signal(...)`, `crash.recover()`. See [Crash-Recovery Testing](#crash-recovery-testing).

See [Concepts](concepts.md) for a deeper explanation of the self-recovery model.

---

## Crash-Recovery Testing

A run that executes once, start to finish, never reads its own history back. Production recovery does: it deserialises every recorded activity result, checks that each step is the one recorded at its sequence, and re-fingerprints every activity's arguments. A result type Jackson cannot read back passes every straight-through test. So does an argument built from `HashMap` iteration order, and so does a step inserted without a `workflow.version()` gate. All three fail only on the first production restart. The test kit can crash its node part-way through a run and start a fresh one over the same store, the way a restarted service does.

### The Three-Line Test

```java
var crash = env.crashAfter(2);                         // die once two activity results are persisted
var handle = env.startWorkflow(OrderFulfilmentWorkflow.class, orderInput);
crash.recover();                                       // a fresh node re-adopts and replays the run

handle.signal("payment.result", PaymentResult.success("tx-1"));
handle.getResult(OrderResult.class, Duration.ofSeconds(10));
assertEquals(List.of("InventoryActivities.reserve", "MessagingActivities.publishPaymentRequest"),
        handle.replayedSteps());
```

`@MaestroTest` needs no change. The injected environment supports crashes and shuts down every node it built.

### Arming a Crash

| Call | The node dies... | The event |
|---|---|---|
| `env.crashAfter(n)` | once the `n`-th `ACTIVITY_COMPLETED` since arming is persisted (compensation activities count too) | survives; the step replays |
| `env.crashAfterPersisting(e -> ...)` | right after the first event the predicate accepts is persisted | survives; replays |
| `env.crashBeforePersisting(e -> ...)` | just before the first matching event is written | lost |
| `env.crashNow()` | immediately (typically while its workflows are parked) | none |

The predicates see every event a workflow writes: activity outcomes, `SIGNAL_RECEIVED`, `TIMER_SCHEDULED`/`TIMER_FIRED`, the `COMPENSATION_*` records, version markers, and the terminal `WORKFLOW_COMPLETED`/`WORKFLOW_FAILED`. Only one crash can be armed at a time; arming a second throws `IllegalStateException`. The returned `SimulatedCrash` reports `hasCrashed()`, the triggering `event()` and `workflowId()`. `awaitCrash(timeout)` waits for the crash and for the crashed run to finish unwinding. The crash is announced only once every write in flight on the node has finished. Once `hasCrashed()` is `true`, nothing more from that node reaches the store, whichever of its workflows triggered the crash.

### Persisted Versus Lost

A crash *after* persisting an activity's result means the step replays on recovery: the implementation is not called again, and the step appears in `replayedSteps()`. A crash *before* persisting means the activity ran but its result was never recorded, so the recovering node runs it again:

```java
var crash = env.crashBeforePersisting(e -> e.eventType() == EventType.ACTIVITY_COMPLETED
        && "MessagingActivities.publishPaymentRequest".equals(e.stepName()));
var handle = env.startWorkflow(OrderFulfilmentWorkflow.class, orderInput);
crash.recover();

assertEquals(2, handle.liveExecutions("MessagingActivities.publishPaymentRequest"));
```

This is the at-least-once window every activity must tolerate. See [Pitfalls §3](pitfalls.md#3-every-activity-must-be-idempotent).

### What a Dead Node Does

- Its workflow threads stop at their next store write and record nothing. The crash is thrown as the engine's own `ExecutorShutdownException`, an `Error`, so a `catch (Exception e)` in workflow code cannot swallow it. The instance keeps its status (`RUNNING`, `WAITING_*` or `COMPENSATING`) and stays recoverable, and its instance lock is released.
- The test thread can still write. `handle.signal(...)` and `env.advanceTime(...)` while the node is down model a signal or a timer that lands during an outage. The signal waits in the store, and the timer row fires. The dead node's parked thread may wake but cannot record anything. The recovered run consumes the signal, or finds the fired timer row and records the wake.
- `env.startWorkflow(...)` throws `IllegalStateException` until you recover.
- After `crashNow()`, a workflow that is mid-activity finishes the activity before it stops. `recover()` waits for it for up to the engine's 30-second shutdown timeout.

### Recovering

`env.recover()`, or `crash.recover()`, which first waits up to 10 seconds for the crash and fails with `AssertionError("... crash armed but never reached ...")` if it never fires. Both retire the node, shut it down, and start a fresh executor over the same store, lock, messaging and clock. The new node re-registers the `@QueryMethod`s and re-adopts every active workflow, bumping each instance's lease epoch as production adoption does. Handles follow: `signal` and `query` go to the new node. `recover()` with no crash armed is a plain restart.

Activity implementations are wired when a run is built, so `registerActivities(...)` before `recover()` gives the recovering node different implementations. To deploy new workflow code, pass the new classes. Each one replaces the class started under the same `@DurableWorkflow` name:

```java
var crash = env.crashAfter(2);
var handle = env.startWorkflow(CheckoutV1.class, input);
crash.recover(CheckoutV2.class);     // V2 inserts a step before the third

// Ungated: replay finds the old step where V2 calls the new one.
assertEquals(StandDownReason.NONDETERMINISTIC_HISTORY, handle.awaitStandDown(Duration.ofSeconds(5)).reason());
// Gated with workflow.version(...): the pre-change history keeps the old path and completes.
```

### Assertions

| Method | Returns |
|---|---|
| `handle.liveSteps()` | every activity step executed live, across every node (recorded as the execution starts, so a lost result still counts) |
| `handle.replayedSteps()` | every **activity** step whose recorded outcome (result or failure) was replayed; replayed signals, timers, side effects and version markers are not counted |
| `handle.liveExecutions(step)` / `handle.replays(step)` | the count for one step, e.g. `"InventoryActivities.reserve"` |
| `handle.recoveries()` | how many times a recovering node re-adopted the workflow |
| `handle.standDowns()` / `handle.awaitStandDown(timeout)` | runs that stopped without recording an outcome: `StandDown(reason, detail)` |

A stand-down is what production does with a history the code cannot follow. The run stops and writes nothing, the instance stays active, and every recovery pass re-adopts it and stands down again. The kit makes it visible:

```java
// A sealed interface with no @JsonTypeInfo serialises fine and cannot be read back.
var crash = env.crashAfter(1);
var handle = env.startWorkflow(DecisionWorkflow.class, "alice");
crash.recover();

var standDown = handle.awaitStandDown(Duration.ofSeconds(5));
assertEquals(StandDownReason.UNKNOWN_EVENT_PAYLOAD, standDown.reason());
```

### Crash at Every Step: `assertRecoverable`

```java
DeterminismChecker.assertRecoverable(env, OrderWorkflow.class, input);
```

It runs the workflow once uninterrupted. Then, for every `k` from 1 to that run's number of completed activities, it starts a fresh run, crashes after `k` completions, and recovers. Each recovered run must not stand down. It must replay, and it must execute exactly the steps the uninterrupted run executed, so nothing already recorded runs again. It must end in the same status with the same `seq:type:step:argsHash` decision sequence. The workflow must finish without external input (no signals), as with `assertDeterministic`. Every run gets its own workflow ID, so a workflow that passes its own ID into an activity cannot pass.

### Why the Lock Stays on the Wall Clock

`InMemoryDistributedLock` expires instance locks on the wall clock, not the `ControllableClock`. On the test clock, `advanceTime(Duration.ofDays(1))` would expire every live run's lock at once. A crashed run releases its lock as it unwinds, so recovery never waits for a TTL.

### Limits

- One node runs at a time. Two live nodes racing for one workflow (lock expiry, lease fencing between them) are covered by the integration tests' `MaestroEngineHarness`, not by the kit.
- A crash fires at an event write. To crash between two non-event writes (a timer row and its memo, for instance), use `crashNow()` at a moment the test controls.
- The in-memory store keeps payloads as Jackson trees and never renders them to text. A type whose round trip depends on the JSON text form is covered only against Postgres.
- `crashNow()` lets another workflow's in-flight activity finish (up to 30 seconds) before that workflow stops.
- With `parallel()` branches, `crashAfter(n)` counts completions across the branches in the order they happen to land, so which step is the `n`-th depends on thread timing. Sibling branches still mid-activity at the crash run that activity again after recovery. `assertRecoverable` can therefore report a live-step difference for a parallel workflow that is in fact correct. Use it on sequential workflows, and test parallel ones with targeted `crashAfterPersisting` predicates instead.

---

## JUnit 5 Extension

For declarative test setup, annotate your test class with `@MaestroTest`. The extension creates a fresh `TestWorkflowEnvironment` before each test and shuts it down after:

```java
@MaestroTest
class OrderFulfilmentWorkflowTest {

    @Test
    void shouldFulfilOrder(TestWorkflowEnvironment env) throws Exception {
        env.registerActivities(InventoryActivities.class, fakeInventory);
        // ... and the order workflow's other three activity interfaces

        var handle = env.startWorkflow(OrderFulfilmentWorkflow.class, orderInput);
        handle.signal("payment.result", PaymentResult.success("txn-123"));

        var result = handle.getResult(OrderResult.class, Duration.ofSeconds(5));
        assertEquals(orderInput.orderId(), result.orderId());
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

The order sample's own test class, trimmed, shows the most common patterns: the happy path, both failure paths that must compensate, time control, and crash recovery. It uses hand-written fakes rather than mocks, so a test can assert on exactly what each activity received. The complete class is [`OrderFulfilmentWorkflowTest`](../maestro-samples/sample-order-service/src/test/java/io/b2mash/maestro/samples/order/workflow/OrderFulfilmentWorkflowTest.java).

```java
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.test.TestWorkflowEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OrderFulfilmentWorkflowTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private TestWorkflowEnvironment env;
    private FakeInventory inventory;
    private RecordingMessaging messaging;
    private CountingShipping shipping;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.create();
        inventory = new FakeInventory();
        messaging = new RecordingMessaging();
        shipping = new CountingShipping();
        env.registerActivities(InventoryActivities.class, inventory);
        env.registerActivities(MessagingActivities.class, messaging);
        env.registerActivities(ShippingActivities.class, shipping);
        env.registerActivities(NotificationActivities.class, (customerId, message) -> { });
    }

    @AfterEach
    void tearDown() {
        env.shutdown();
    }

    @Test
    void paidOrderIsFulfilled() throws TimeoutException {
        var h = env.startWorkflow(OrderFulfilmentWorkflow.class, order("order-1"));
        h.awaitStatus(WorkflowStatus.WAITING_SIGNAL, WAIT);

        h.signal("payment.result", PaymentResult.success("tx-1"));

        assertEquals(new OrderResult("order-1", "TRACK-order-1"), h.getResult(OrderResult.class, WAIT));
        assertTrue(inventory.released.isEmpty(), "a fulfilled order keeps its reservation");
    }

    @Test
    void declinedPayment_failsTheOrderAndReleasesTheReservation() throws TimeoutException {
        var h = env.startWorkflow(OrderFulfilmentWorkflow.class, order("order-1"));
        h.awaitStatus(WorkflowStatus.WAITING_SIGNAL, WAIT);

        h.signal("payment.result", PaymentResult.failed("Card declined"));

        assertEquals(WorkflowStatus.FAILED, h.awaitTerminal(WAIT));
        var failure = h.getFailure().orElseThrow();
        assertEquals(OrderFailedException.class.getName(), failure.exceptionType());
        assertEquals("Payment failed: Card declined", failure.message());
        assertEquals(1, inventory.released.size(), "the saga released the reservation once");
        assertEquals("res-order-1", inventory.released.getFirst().reservationId());
        assertEquals(0, shipping.calls.get(), "a declined order never ships");
    }

    @Test
    void paymentTimeout_failsTheOrderAndReleasesTheReservation() throws TimeoutException {
        var h = env.startWorkflow(OrderFulfilmentWorkflow.class, order("order-1"));
        h.awaitStatus(WorkflowStatus.WAITING_SIGNAL, WAIT);   // the deadline timer exists now

        env.advanceTime(Duration.ofHours(1));

        assertEquals(WorkflowStatus.FAILED, h.awaitTerminal(WAIT));
        assertEquals("Payment timed out", h.getFailure().orElseThrow().message());
        assertEquals(1, inventory.released.size());
    }

    @Test
    void crashAfterPaymentRequest_thenDecline_compensatesWithTheReplayedReservation() throws TimeoutException {
        var crash = env.crashAfter(2);
        var h = env.startWorkflow(OrderFulfilmentWorkflow.class, order("order-1"));
        crash.recover();

        h.signal("payment.result", PaymentResult.failed("Card declined"));

        assertEquals(WorkflowStatus.FAILED, h.awaitTerminal(WAIT));
        assertEquals(List.of("InventoryActivities.reserve", "MessagingActivities.publishPaymentRequest"),
                h.replayedSteps());
        assertEquals(1, inventory.reserveCalls.get(), "reserved once, replayed after the crash");
        assertEquals(List.of("order-1"), messaging.requestedOrderIds, "payment requested once");
        assertEquals(0, new BigDecimal("10.00").compareTo(inventory.released.getFirst().total()));
    }

    @Test
    void crashWhileAwaitingPayment_thenPaid_ships() throws TimeoutException {
        var h = env.startWorkflow(OrderFulfilmentWorkflow.class, order("order-1"));
        h.awaitStatus(WorkflowStatus.WAITING_SIGNAL, WAIT);

        env.crashNow().recover();
        h.signal("payment.result", PaymentResult.success("tx-1"));

        assertEquals(new OrderResult("order-1", "TRACK-order-1"), h.getResult(OrderResult.class, WAIT));
        assertEquals(1, h.recoveries());
    }

    @Test
    void crashBeforeThePaymentRequestIsRecorded_publishesItTwice() throws TimeoutException {
        var crash = env.crashBeforePersisting(e -> e.eventType() == EventType.ACTIVITY_COMPLETED
                && "MessagingActivities.publishPaymentRequest".equals(e.stepName()));
        var h = env.startWorkflow(OrderFulfilmentWorkflow.class, order("order-1"));
        crash.recover();

        h.signal("payment.result", PaymentResult.success("tx-1"));
        h.getResult(OrderResult.class, WAIT);

        // The publish ran but its result was lost: the recovering node publishes
        // again. The payment gateway keys its workflow on the order ID, so the
        // duplicate request starts no second charge.
        assertEquals(List.of("order-1", "order-1"), messaging.requestedOrderIds);
    }

    private static OrderInput order(String orderId) {
        return new OrderInput(orderId, "customer-" + orderId,
                List.of(new OrderItem(orderId, 1, new BigDecimal("10.00"))), "VISA", "1 Main Street");
    }

    static final class FakeInventory implements InventoryActivities {
        final AtomicInteger reserveCalls = new AtomicInteger();
        final List<ReservationConfirmation> released = new CopyOnWriteArrayList<>();

        @Override
        public ReservationConfirmation reserve(List<OrderItem> items) {
            reserveCalls.incrementAndGet();
            var total = items.stream()
                    .map(i -> i.price().multiply(BigDecimal.valueOf(i.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return new ReservationConfirmation("res-" + items.getFirst().sku(), total, "WH-1");
        }

        @Override
        public void releaseReservation(ReservationConfirmation reservation) {
            released.add(reservation);
        }
    }

    static final class RecordingMessaging implements MessagingActivities {
        final List<String> requestedOrderIds = new CopyOnWriteArrayList<>();

        @Override
        public void publishPaymentRequest(PaymentRequest request) {
            requestedOrderIds.add(request.orderId());
        }
    }

    static final class CountingShipping implements ShippingActivities {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ShipmentConfirmation createShipment(String orderId, String shippingAddress, String warehouseId) {
            calls.incrementAndGet();
            return new ShipmentConfirmation("ship-" + orderId, "TRACK-" + orderId);
        }
    }
}
```

---

## Tips

**Keep test timeouts short.** Use `Duration.ofSeconds(5)` for `getResult` and `awaitCompletion`. If a test needs more than a few seconds of real wall-clock time, something is likely stuck -- the timeout helps you find it fast.

**Fakes or mocks for activity implementations.** Activity interfaces are the natural seam. A small hand-written fake (as in the [full example](#full-test-example)) records exactly what each activity received, including the replayed argument a compensation gets after a crash. Mockito works too: stub the return values and verify interactions after the workflow completes.

**Test failure paths explicitly.** Workflow error handling and saga compensation are the hardest code paths to get right. Write tests that trigger failures at each activity step, wait with `awaitTerminal`, and assert on `getFailure()` and on the compensations that ran. If the workflow ends `COMPLETED` when you expected `FAILED`, it is returning its failure instead of throwing it. See [Pitfalls §1](pitfalls.md#1-compensation-runs-when-the-workflow-throws-not-when-it-returns-a-failure).

**Crash every workflow at least once.** One `crashAfter(n)` + `recover()` test per workflow is the only way a test reads the history back. `DeterminismChecker.assertRecoverable` does it at every step for workflows that finish without signals. See [Crash-Recovery Testing](#crash-recovery-testing).

**One environment per test.** Each `TestWorkflowEnvironment` has its own store, clock, and executor. Sharing environments between tests leads to flaky ordering issues. Use `@BeforeEach` or `@MaestroTest` to get a fresh environment.

**Avoid `Thread.sleep()` in test code.** To wait for a **non-terminal** status, typically `WAITING_SIGNAL` or `WAITING_TIMER` before `advanceTime`, use `handle.awaitStatus(status, timeout)`. To wait for the end, use `awaitTerminal`, `awaitCompletion` or `getResult`. These wait for the terminal *event* as well as the status, so they state exactly what your log assertions depend on:

```java
// Right: waits for the durable terminal event, so the log is complete.
handle.awaitCompletion(Duration.ofSeconds(5));
assertTrue(handle.getEvents().stream()
        .anyMatch(e -> e.eventType() == EventType.WORKFLOW_COMPLETED));
```

`awaitStatus` rejects a terminal status for that reason. The engine writes the terminal status and its `WORKFLOW_COMPLETED` / `WORKFLOW_FAILED` event in one atomic store write (Issue 26), but the terminal waits remain the ones that say what the test needs. The same applies outside `maestro-test`: if you poll a `WorkflowStore` directly, gate on the terminal **event**, not the terminal status.

---

## See Also

- [Concepts](concepts.md) -- Core concepts: memoization, replay, determinism constraints
- [Pitfalls](pitfalls.md) -- The ten mistakes that fail only in production, and how a test catches each
- [Configuration](configuration.md) -- Runtime configuration reference
- [Architecture](maestro-architecture.md) -- System architecture and failure modes
