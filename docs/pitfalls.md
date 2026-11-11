# Pitfalls

Eleven rules whose violations pass every test that runs a workflow straight through and fail in production, usually on the first restart, the first retry or the first traffic peak. Each rule shows the wrong code, the right code, what you see when you get it wrong, and how a test catches it.

[← Back to README](../README.md)

---

## 1. Compensation runs when the workflow throws, not when it returns a failure

The engine compensates from its failure path. An exception escapes the `@WorkflowMethod`, the `@Saga` unwinds the registered compensations, and the instance ends `FAILED`. A workflow that catches a failure and returns a value describing it has *completed*.

**Wrong**

```java
@WorkflowMethod
@Saga
public OrderResult fulfil(OrderInput input) {
    var reservation = inventory.reserve(input.items());
    var payment = workflow.awaitSignal("payment.result", PaymentResult.class, Duration.ofHours(1));
    if (!payment.success()) {
        return OrderResult.failed(payment.reason());   // "the saga will release the stock" -- it will not
    }
    ...
}
```

**Right**

```java
    if (!payment.success()) {
        notifications.notifyCustomer(input.customerId(), "Payment failed: " + payment.reason());
        throw new OrderFailedException("Payment failed: " + payment.reason());
    }
```

The throw releases the reservation, and the instance ends `FAILED` with `WorkflowFailureDetail(exceptionType, message)`. Read it with `WorkflowHandle.failure()` (any node) or `TestWorkflowHandle.getFailure()`. Return a business outcome only when there is nothing to undo, as the payment gateway does with a declined charge.

**What you see:** the instance is `COMPLETED`. The event log has no `COMPENSATION_*` events, and the compensation activity never ran, so the stock stays reserved. The admin **Retry** action does not apply, because retry only acts on `FAILED` workflows.

**Test it:** drive the failure, then `assertEquals(WorkflowStatus.FAILED, handle.awaitTerminal(timeout))` and assert that the compensation activity was called once. See the order sample's `declinedPayment_failsTheOrderAndReleasesTheReservation`.

---

## 2. Catch `ActivityExecutionException` and test it with `causedBy`; list business exceptions as non-retryable

An activity's exception always reaches workflow code wrapped in `ActivityExecutionException`, both live and on replay. On replay the cause is rebuilt from the recorded class name and message. The retry policy retries every exception unless it is excluded.

**Wrong**

```java
@ActivityStub(startToCloseTimeout = "PT60S")              // default policy: 3 attempts
private PaymentProviderActivities provider;

try {
    provider.charge(method, amount);
} catch (PaymentDeclinedException e) {                     // never matches: the proxy throws the wrapper
    return PaymentProcessingResult.declined(e.getMessage());
}
```

**Right**

```java
@ActivityStub(startToCloseTimeout = "PT60S",
        retryPolicy = @RetryPolicy(maxAttempts = 30,
                nonRetryableExceptions = PaymentDeclinedException.class))
private PaymentProviderActivities provider;

try {
    provider.charge(method, amount);
} catch (ActivityExecutionException e) {
    if (!e.causedBy(PaymentDeclinedException.class)) {
        throw e;
    }
    return PaymentProcessingResult.declined(e.getCause().getMessage());
}
```

`causedBy` matches the live cause and the replayed reconstruction alike. Give a business exception a public `(String)` constructor so replay rebuilds its original type. Without one, the cause is a `ReconstructedActivityFailure` naming the original class, which `causedBy` still recognises.

**What you see:** the business exception is retried as if it were transient, three provider calls by default with backoff. The `catch` is dead code, so the wrapper escapes and the workflow ends `FAILED` instead of returning the decline.

**Test it:** count provider calls (`assertEquals(1, provider.calls.get())`). To pin the replay path, `env.crashAfterPersisting(e -> e.eventType() == EventType.ACTIVITY_FAILED)` and `crash.recover()` (see `PaymentProcessingWorkflowTest`).

---

## 3. Every activity must be idempotent

A recorded result is never re-executed. But an activity that ran while its result was not yet recorded runs again on the node that adopts the workflow. This happens when the node crashed between the side effect and the write, when it lost its instance lock, or when an attempt timed out and was retried. Locks are a best-effort guard. The unique `(workflow_instance_id, sequence_number)` index deduplicates the *recorded result*, not the side effect.

**Wrong**

```java
public void publishPaymentRequest(PaymentRequest request) {
    kafka.send("payments.requests", request);              // a second send is a second payment request
}
```

**Right:** make the side effect safe to repeat. Key it on a business ID the receiver deduplicates on. The payment gateway starts its workflow under `payment-<orderId>`, so a duplicate request cannot start a second charge. Or pass an idempotency key the external API honours, or send a signal with a stable message id (rule 9).

**What you see:** duplicates after a restart. Two emails, two payment requests, two shipments, rarely and never in a straight-through test.

**Test it:**

```java
var crash = env.crashBeforePersisting(e -> e.eventType() == EventType.ACTIVITY_COMPLETED
        && "MessagingActivities.publishPaymentRequest".equals(e.stepName()));
var handle = env.startWorkflow(OrderFulfilmentWorkflow.class, input);
crash.recover();
assertEquals(2, handle.liveExecutions("MessagingActivities.publishPaymentRequest"));
```

Then assert that the effect downstream happened once.

---

## 4. Code between activity calls must be deterministic, and so must activity arguments

Recovery re-runs the workflow method and replays each recorded step. The step at each sequence must be the one recorded there. Every `ACTIVITY_COMPLETED`/`ACTIVITY_FAILED` event also carries a SHA-256 fingerprint of the call's arguments (`args_hash`, store migration V7). On replay the engine fingerprints the current call and refuses to return a recorded result for a different question.

**Wrong**

```java
var skus = String.join(",", stockBySku.keySet());          // HashMap iteration order
inventory.reserve(skus, Instant.now());                    // wall clock in an argument
if (Math.random() < 0.1) { audit.sample(order); }          // a step that may or may not exist
```

**Right**

```java
var skus = String.join(",", new TreeMap<>(stockBySku).keySet());   // or pass the map itself
inventory.reserve(skus, workflow.currentTime());                    // recorded once, replayed
if (workflow.randomUUID().hashCode() % 10 == 0) { audit.sample(order); }
```

The fingerprint sorts object keys, so passing a `HashMap` itself is safe: a `HashMap` and a `LinkedHashMap` with the same entries agree. List order and string content are part of the value, so a string or list built from map iteration order is not safe.

**What you see:** the run stands down. A `WARN` from `WorkflowExecutor` reads "Workflow '…' stood down at sequence N: … replay diverged from its history …" and ends "roll back the deploy or gate the change behind workflow.version()". The `maestro.standdown` counter increments with `reason=nondeterministic_history`. No failure is recorded and nothing is compensated. The instance stays active, so every recovery pass re-adopts it and stands it down again until you roll back or terminate it.

**Test it:** a crash-recovery test (`env.crashAfter(n)` + `recover()`, then `handle.awaitStandDown(...)`), or `DeterminismChecker.assertRecoverable(env, MyWorkflow.class, input)`, which crashes after every activity in turn. `assertDeterministic` compares fresh runs and catches only nondeterminism that varies between runs in one JVM.

---

## 5. `@QueryMethod` answers only on the node running the workflow

A query invokes a method on the live workflow object, which exists only on the node that holds the workflow's instance lock. Every other node throws `WorkflowNotQueryableException`, and so does every node once the workflow has ended. Cross-node query routing is not implemented.

**Wrong**

```java
@GetMapping("/orders/{id}/status")
public OrderStatus status(@PathVariable String id) {
    return maestro.getWorkflow("order-" + id).query("getStatus", OrderStatus.class);  // 500 on other nodes, and after the end
}
```

**Right**

```java
var handle = maestro.getWorkflow("order-" + id);
String step;
try {
    step = handle.query("getStatus", OrderStatus.class).currentStep();
} catch (WorkflowNotQueryableException notOnThisNode) {
    step = null;
}
return new OrderStatusResponse(id, handle.status(), step,
        handle.failure().map(WorkflowFailureDetail::message).orElse(null));
```

`status()`, `result()` and `failure()` read the store and work on every node.

**What you see:** behind a load balancer, roughly one request in N succeeds. After the workflow finishes, every request fails.

**Test it:** query a workflow after `awaitTerminal`: the kit throws `WorkflowNotQueryableException` exactly as production does.

---

## 6. Know the sequence budget: two slots per park, 999 per parallel branch

Every step occupies a sequence slot. A `sleep()`, and an `awaitSignal()` that actually parks, occupies two: the `TIMER_SCHEDULED` memo and the outcome. An `awaitSignal` whose signal had already arrived, or whose timeout is not positive, writes only the outcome. Branch *i* of a `parallel()` fork allocates from its own 1000-wide block, which leaves 999 usable slots.

**Wrong**

```java
workflow.parallel(List.of(() -> {
    for (var item : items) {                 // 1,000 items: 1,000 activity calls in one branch
        fulfilment.ship(item);
    }
    return null;
}));
```

**Right:** keep a branch's step count bounded and well under 999. Split large fan-outs across branches, batch items into fewer activity calls, or start a separate workflow per batch. Count each park as two steps.

**What you see:** the 1,000th slot fails the branch with `BranchSequenceExhaustedException`, an ordinary `MaestroException`. The fork fails, and so does the workflow unless the code handles it. A fork too deeply nested to sequence fails with `SequenceSpaceExhaustedException` before consuming a slot.

**Test it:** run the workflow with the largest input it must support.

---

## 7. Gate every change to a running workflow's steps with `workflow.version()`

Recovery replays in-flight workflows with the code that is now deployed. Inserting, removing, reordering or renaming a step makes the new code disagree with the recorded history at that sequence.

**Wrong**

```java
inventory.reserve(items);
fraud.check(order);                          // new step, ungated
payment.charge(order);
```

**Right**

```java
inventory.reserve(items);
if (workflow.version("fraud-check", WorkflowContext.DEFAULT_VERSION, 1) >= 1) {
    fraud.check(order);                      // only histories that recorded version 1
}
payment.charge(order);
```

A history written before the change resolves to `DEFAULT_VERSION` and keeps the old path. Once no pre-change instance can still be running, raise the floor (`version("fraud-check", 1, 1)`) and delete the old branch. `maxSupported` must be a code constant.

**What you see:** for an ungated change, the `NONDETERMINISTIC_HISTORY` stand-down from rule 4, on every in-flight instance of the type. If you raise `minSupported` above a running instance's recorded version, that instance fails with `UnsupportedWorkflowVersionException` (an ordinary failure: compensation runs and it ends `FAILED`).

**Test it:** `env.crashAfter(n)`, start on the old class, then `crash.recover(NewVersion.class)`. The gated version completes; an ungated one stands down. See `ReplayDeterminismTest` in `maestro-test`.

---

## 8. Workflow fields are per-run state; fields a query reads must be `volatile`

The engine builds one workflow object per run, including each recovery and each retry (Spring `@DurableWorkflow` beans are prototype-scoped). Instance fields are therefore per-run state, rebuilt on recovery by replaying the method. A `@QueryMethod` runs on the caller's thread, not the workflow's.

**Wrong**

```java
private static String currentStep;           // shared by every run of the type
private int itemsShipped;                    // read by a query without a happens-before edge
```

**Right**

```java
private volatile String currentStep = "CREATED";   // per run, visible to the query thread
private volatile int itemsShipped;
```

**What you see:** with `static`, one order's query reports another order's step. Without `volatile`, a query can return a stale value. State the workflow method derives from its steps is rebuilt on recovery, because the method re-runs and the steps replay. State put on the object any other way, for example by a query method that mutates it, is lost.

**Test it:** start two instances and query both (`twoInstancesReportTheirOwnStep` in the order sample). After `env.crashNow().recover()`, query again: the recovered run answers from its new object.

---

## 9. Give a signal a stable message id when the sender may retry

Delivery is at least once. A Kafka redelivery, a producer republish or an HTTP client retry can deliver one logical signal twice. The engine stores at most one signal per `(workflowId, signalName, messageId)`. A signal with no id at all is never deduplicated.

**Wrong**

```java
@PostMapping("/documents/{id}/approve")
void approve(@PathVariable String id) {
    maestro.getWorkflow("doc-" + id).signal("approval", Decision.APPROVED);   // a retried request is a second approval
}
```

**Right**

```java
@PostMapping("/documents/{id}/approve")
void approve(@PathVariable String id, @RequestHeader("Idempotency-Key") String key) {
    boolean stored = maestro.getWorkflow("doc-" + id).signal("approval", Decision.APPROVED, key);
    // false: a duplicate of a signal already stored
}
```

From an activity that publishes a signal to another service over Kafka, set the `maestro-message-id` header to `SignalMessageIds.forCurrentActivity()`, which is stable across that activity's retries and its re-run after a crash:

```java
MaestroSignalHeaders.setMessageId(record.headers(), SignalMessageIds.forCurrentActivity());
```

**What you see:** a workflow that awaits the same signal name more than once (a review loop, `collectSignals`) consumes the duplicate as the next event. A one-shot await consumes one copy. The other stays unconsumed until the signal janitor purges it, by default 7 days after the workflow completes or is terminated (`maestro.signal.terminal-retention`). A `FAILED` workflow keeps it for a retry to consume.

**Test it:** `assertFalse(handle.signal("approval", payload, "req-1"))` on the second call in the kit, which uses the same `client:` namespace as the starter's `WorkflowHandle`.

---

## 10. Never catch `Throwable` or `Error` around engine calls

`ExecutorShutdownException` (this node is stopping while the workflow was parked), `WorkflowTerminatedException` (an operator terminated it) and `UnknownWorkflowHistoryException` (this node cannot read the history; it stands down) are the engine's control-flow signals. They extend the sealed `MaestroControlFlowError extends Error`, precisely so that `catch (Exception e)` cannot intercept them. None of them means the workflow failed.

**Wrong**

```java
try {
    approval = workflow.awaitSignal("approval", Decision.class, Duration.ofDays(7));
} catch (Throwable t) {                       // also catches a deploy's shutdown
    throw new RuntimeException("approval failed", t);
}
```

**Right**

```java
try {
    approval = workflow.awaitSignal("approval", Decision.class, Duration.ofDays(7));
} catch (SignalTimeoutException e) {          // catch what you handle
    throw new ApprovalExpiredException(input.documentId());
}
```

If you must catch broadly, rethrow the control-flow signals first: `if (t instanceof MaestroControlFlowError e) throw e;`.

**What you see:** wrapped as above, a routine deploy that shuts down a parked workflow is recorded as a workflow failure. The instance ends `FAILED` and compensations run for work that never failed. Swallowed ("log and continue"), the run carries on past an await it never completed, on a node that has abandoned it.

**Test it:** a crash test does not show this one as an outcome. The kit's crash kills the node before it shuts down, so the failure your catch block raises cannot be written. The node logs `ERROR Workflow '…' failed` and writes nothing, and the recovering node carries the workflow on. Catch it in review instead: no `catch (Throwable` or `catch (Error` in workflow code, and no `catch (Exception` that wraps and rethrows around engine calls without first rethrowing `MaestroControlFlowError`.

---

## 11. Never make an activity wait for a workflow of its own task queue

A run keeps its workflow slot while one of its activities runs, and the activity holds one of the queue's activity slots. Every task queue is bounded per node: the queues you list in `maestro.worker.task-queues`, and every other queue at `maestro.worker.default-concurrency` / `default-activity-concurrency` (20 / 20 by default). A workflow started from an activity runs on the same node. If it has the same task queue, it needs a slot from the same pools. Every `@DurableWorkflow` that names no `taskQueue` is on `default`, so this includes a parent and a child of different types.

**Wrong**

```java
@DurableWorkflow(name = "order-fulfilment")                 // taskQueue "default"
public class OrderFulfilmentWorkflow { ... shipping.ship(input) ... }

@DurableWorkflow(name = "shipment")                          // also taskQueue "default"
public class ShipmentWorkflow { ... }

public ShipmentResult ship(OrderInput input) throws TimeoutException {   // an activity of OrderFulfilmentWorkflow
    return maestro.newWorkflow(ShipmentWorkflow.class, WorkflowOptions.builder()
                    .workflowId("shipment-" + input.orderId()).build())
            .startAndWait(input, Duration.ofMinutes(10), ShipmentResult.class);
}
```

**Right:** give the child its own pools, or stop waiting inside an activity.

```java
@DurableWorkflow(name = "shipment", taskQueue = "shipments")   // its own workflow and activity slots
public class ShipmentWorkflow { ... }
```

Alternatively, start the child with `startAsync`, have it signal the parent when it finishes, and have the parent `awaitSignal` that signal. A parked run gives its slot back.

**What you see:** under load, nothing finishes. Once `default-concurrency` order runs each wait in `ship()` (or `default-activity-concurrency` of them do), no shipment can get a slot to run, and every order blocks until `startAndWait` throws `TimeoutException`, which fails that activity attempt under its retry policy. Below that load it works, so it passes in staging and fails at the first traffic peak.

**Test it:** a `TestWorkflowEnvironment` test does not show it, because the test kit applies no concurrency limits ([Issue 36](open-issues.md#issue-36)). Catch it in review: no `startAndWait`, and no status polling loop, inside an activity, unless the started workflow names a task queue of its own.

---

## See Also

- [Testing Guide](testing.md#crash-recovery-testing) -- crash-recovery tests that catch rules 2, 3, 4 and 7
- [Core Concepts](concepts.md) -- sagas, determinism, versioning, queries
- [Cross-Service Patterns](cross-service.md) -- signal identity in depth
- [Operations](operations.md) -- stand-downs and mixed-version deploys
