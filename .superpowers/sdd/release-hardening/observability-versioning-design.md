# Observability + Versioning Design — Release Hardening Task 2

Status: DESIGN — binds Tasks 3–7. Written against `docs/release-hardening-spec.md`
§§4–6 (the binding contract) and the actual source at the tree state of this
worktree (`main`, post-Task-1). Every section below states a **decision** with
rationale. Questions the coordinator must rule on are collected in §9 and
marked `OPEN-Q-n` inline.

Grounding: every file referenced below was read in full or at the cited
member before the decision was taken. Line numbers are indicative (tree at
design time), member names are the stable anchors.

---

## 1. `EngineObserver` — the core observer seam

### 1.1 Decision summary

- **Amended by RULING 4 (§10):** `CompositeEngineObserver.of()` never
  collapses to the bare delegate at size 1 — it always wraps, so
  `RuntimeException` containment is structural at every emission site. The
  code block below reflects the amendment.
- New package `io.b2mash.maestro.core.observe` in `maestro-core`. Zero new
  dependencies — the package contains only the interface, its argument
  records/enums, the no-op constant, the composite, and a `TraceContextHolder`
  (plain `ThreadLocal<String>`, see §4).
- `EngineObserver` is an interface whose methods are **all `default` no-ops**.
  The interface is therefore its own no-op default; `EngineObserver.NOOP` is
  provided as a canonical instance so engine fields are never null.
- **Replay handling: flag-per-callback**, not no-emit-during-replay. Every
  callback that can fire on a replay path carries an explicit
  `boolean replayed` parameter. The Micrometer and tracing adapters (Tasks
  4–5) skip counting / span creation when `replayed == true`; the engine
  still emits, so a future debugging/audit observer can see replay traffic.
  Rationale: no-emit would bake the metrics adapter's policy into the engine
  and make the replay path observably silent — the flag keeps the seam honest
  and puts the policy where it belongs (the adapter), while the
  replay-no-double-count pin test (§8) enforces the adapter policy.
- `EngineObserver` **stays separate from** the existing
  `WorkflowLifecycleEvent` / `GatedWorkflowMessaging` seam (see 1.5).

### 1.2 Interface — exact shape (paste-ready)

```java
package io.b2mash.maestro.core.observe;

import org.jspecify.annotations.Nullable;

import java.time.Duration;

/**
 * Engine-internal observation seam: the engine invokes these callbacks
 * synchronously at execution boundaries. maestro-core has no metrics or
 * tracing dependency — adapters (Micrometer, tracing) live in the Spring
 * Boot starter and implement this interface.
 *
 * <h2>Replay awareness</h2>
 * Callbacks that can fire while the engine replays memoized history carry a
 * {@code replayed} flag. A recovered workflow replaying N steps emits N
 * callbacks with {@code replayed == true}; adapters that count or trace MUST
 * ignore those, or they will double-count (pinned by
 * ObserverReplayNoDoubleCountIT, see the design doc §8).
 *
 * <h2>Thread safety and discipline</h2>
 * Callbacks are invoked concurrently from workflow virtual threads, poller
 * threads, Kafka listener threads and the instance-lock renewer thread.
 * Implementations MUST be thread-safe, MUST return quickly (no I/O, no
 * blocking), and MUST NOT throw. {@link CompositeEngineObserver} contains a
 * misbehaving observer by catching {@code RuntimeException} per callback;
 * {@code Error}s (including the engine's control-flow signals) always
 * propagate.
 */
public interface EngineObserver {

    /** Canonical no-op instance — engine fields default to this, never null. */
    EngineObserver NOOP = new EngineObserver() {};

    // ── Workflow lifecycle ────────────────────────────────────────────
    default void workflowStarted(WorkflowInfo w) {}
    /** A local run was launched in replay mode (recovery, resume, admin retry). */
    default void workflowResumed(WorkflowInfo w) {}
    default void workflowCompleted(WorkflowInfo w) {}
    default void workflowFailed(WorkflowInfo w, String exceptionType) {}
    /** Saga compensation is starting for this workflow. */
    default void workflowCompensating(WorkflowInfo w) {}
    default void workflowTerminated(WorkflowInfo w) {}

    // ── Run-segment boundaries (drive segment spans, §3) ──────────────
    /** The workflow thread is about to park (live path only, never replay). */
    default void workflowParked(WorkflowInfo w, ParkKind kind) {}
    /** The workflow thread resumed from a live park on this node. */
    default void workflowUnparked(WorkflowInfo w, ParkKind kind) {}

    // ── Activities ────────────────────────────────────────────────────
    /** Live execution is starting (never fired on replay). */
    default void activityStarted(ActivityInfo a) {}
    default void activityCompleted(ActivityInfo a, Duration duration, boolean replayed) {}
    default void activityFailed(ActivityInfo a, Duration duration,
                                String exceptionType, boolean replayed) {}

    // ── Signals ───────────────────────────────────────────────────────
    default void signalPersisted(SignalInfo s) {}
    default void signalConsumed(SignalInfo s, boolean replayed) {}

    // ── Timers ────────────────────────────────────────────────────────
    default void timerScheduled(TimerInfo t, boolean replayed) {}
    default void timerFired(TimerInfo t, boolean replayed) {}
    default void timerCancelled(TimerInfo t, boolean replayed) {}

    // ── Instance lock ─────────────────────────────────────────────────
    default void instanceLockAcquired(String workflowId) {}
    /** A renewal attempt failed transiently (backend error; handle kept). */
    default void instanceLockRenewFailed(String workflowId) {}
    /** Ownership was lost (renew returned false; handle dropped). */
    default void instanceLockLost(String workflowId) {}

    // ── Recovery ──────────────────────────────────────────────────────
    /** One recovery pass finished (startup or poller cycle). */
    default void recoveryPass(int scanned, int adopted) {}

    // ── Stand-down ────────────────────────────────────────────────────
    /** A local run stood down without recording a workflow outcome. */
    default void standDown(StandDownReason reason, String workflowId,
                           @Nullable String detail) {}
}
```

Argument records and enums (same package; all records, JSpecify-annotated):

```java
/** Identity of a workflow for observation. Never carry payloads here. */
public record WorkflowInfo(String workflowId, String workflowType, String serviceName) {}

/** @param activityName the step name, {@code group.method} — code-bounded */
public record ActivityInfo(String workflowId, String workflowType,
                           String activityName, int sequenceNumber) {}

/**
 * @param traceContext W3C {@code traceparent} captured when the signal was
 *        persisted from a transport consumer, or {@code null} (§4)
 */
public record SignalInfo(String workflowId, @Nullable String workflowType,
                         String signalName, @Nullable String traceContext) {}

public record TimerInfo(String workflowId, String workflowType, String timerId) {}

public enum ParkKind { SIGNAL, TIMER }

public enum StandDownReason {
    /** Persisted event whose type string this build does not know (§6). */
    UNKNOWN_EVENT_TYPE,
    /** Persisted payload of a known event could not be deserialized on replay (§6). */
    UNKNOWN_EVENT_PAYLOAD,
    /** Issue 18: event append collided with a concurrent runner's history. */
    STALE_RUN
}
```

`CompositeEngineObserver` (same package, `public final`):

```java
public final class CompositeEngineObserver implements EngineObserver {
    private final List<EngineObserver> delegates; // List.copyOf in ctor

    // AMENDED BY RULING 4 (§10): a single delegate is WRAPPED, never
    // returned bare — containment must not depend on how many observers
    // happen to be registered. `case 1 -> observers.getFirst();` is REMOVED.
    public static EngineObserver of(List<EngineObserver> observers) {
        return observers.isEmpty()
                ? EngineObserver.NOOP
                : new CompositeEngineObserver(observers);
    }
    // every override: for (var d : delegates) { try { d.callback(...); }
    //   catch (RuntimeException e) { log.warn(...); } }
    // Errors are deliberately NOT caught: the composite must never swallow
    // ExecutorShutdownException / WorkflowTerminatedException /
    // UnknownWorkflowHistoryException — and observers must never throw them.
}
```

### 1.3 Registration path into the engine

The observer is a constructor collaborator, defaulted to `NOOP`, threaded
exactly the way `PayloadSerializer` already is:

| Component | Change |
|---|---|
| `WorkflowExecutor` | Widest constructor gains a final `EngineObserver observer` parameter (12th); all narrower constructors delegate with `EngineObserver.NOOP`. The executor stores it and passes it on to every component it builds. |
| `SignalManager` | Constructor gains `EngineObserver`; fires `signalPersisted` (in `deliverSignal`, after `store.saveSignal`), `signalConsumed` (in `consumeSignal`, `replayed=false`; and in the `SIGNAL_RECEIVED` replay branch of `awaitSignal`, `replayed=true`), `workflowParked/Unparked(SIGNAL)` around the live park loop. |
| `DefaultWorkflowOperations` | Constructor gains `EngineObserver`; fires `timerScheduled/Fired/Cancelled` (live paths and the corresponding replay branches of `sleep()` with `replayed=true`), `workflowParked/Unparked(TIMER)` in `parkForTimer` callers. Built per-launch inside `WorkflowExecutor.launchWorkflow`, which passes the executor's observer. |
| `SagaManager` | Constructor gains `EngineObserver`; fires `workflowCompensating` at the top of `compensate()` (live only — guard on the `COMPENSATION_STARTED` replay-skip that already exists). |
| `ActivityInvocationHandler` | Constructor gains `EngineObserver` (new widest ctor; existing ctors default `NOOP`). Fires `activityStarted` (live path, next to the existing `ACTIVITY_STARTED` lifecycle publish), `activityCompleted`/`activityFailed` with a `Duration` measured around `retryExecutor.executeWithRetry` (live) or `Duration.ZERO` + `replayed=true` (replay path in `handleReplay`). `ActivityProxyFactory.createProxy` gains the parameter and passes it through. |
| `WorkflowInstanceLockManager` | Constructor gains `EngineObserver`; fires `instanceLockAcquired` in `tryAcquire` (ACQUIRED case), `instanceLockRenewFailed` in `renewOne`'s `catch (Exception)`, `instanceLockLost` in `renewOne`'s `!renew(...)` branch. |
| `WorkflowExecutor.recoverWorkflows` | Fires `recoveryPass(recoverable.size(), count)` before returning — covers both `StartupRecoveryRunner` and `RecoveryPoller` (the poller calls this method; `RecoveryPoller` itself is not touched). |
| `TimerPoller` | **Not instrumented.** Timer fires are observed on the workflow side (`recordTimerFired` via `DefaultWorkflowOperations`), which is the only place a fire is counted exactly once regardless of which node's poller fired the row. Instrumenting the poller too would double-count when leader == owner. |
| `WorkflowExecutor.executeWorkflow` | Fires `workflowStarted` (from `startWorkflow`, after `createInstance`), `workflowResumed` (from `launchWorkflow` when `replaying == true` and the launch succeeded), `workflowCompleted` / `workflowFailed` (inside the `transitionToTerminal(...) == true` branches only, so a converged loser does not double-fire), `workflowTerminated` (from `terminateWorkflow`, after the CAS wins), `standDown` (new §6 handler and `handleStaleRunStandDown` with `STALE_RUN`). |

Spring wiring (Task 4): `MaestroAutoConfiguration.maestroWorkflowExecutor`
gains an `ObjectProvider<EngineObserver>` parameter; it collects all
`EngineObserver` beans (ordered stream) into
`CompositeEngineObserver.of(...)` and passes the result to the new
constructor. `ActivityStubBeanPostProcessor` receives the same composite
(injected the same way it currently receives its collaborators) and passes it
to `ActivityProxyFactory.createProxy`.

### 1.4 Thread-safety contract

Stated on the interface Javadoc (above) and binding on adapters:
callbacks run synchronously on engine threads (workflow virtual threads,
recovery/timer poller threads, lock renewer thread, Kafka listener threads
via `deliverSignal`); implementations must be thread-safe, non-blocking,
allocation-light, and must not throw. The composite contains
`RuntimeException` per delegate per callback (WARN log); `Error` propagates
by design.

### 1.5 Relationship to `WorkflowLifecycleEvent` / `GatedWorkflowMessaging`

**Decision: `EngineObserver` stays separate. It neither subsumes nor wraps
the lifecycle-event seam.**

Rationale, grounded in what the existing seam actually is
(`spi/WorkflowLifecycleEvent.java`, `spi/LifecycleEventType.java`,
`engine/GatedWorkflowMessaging.java`, `engine/LifecycleEventPublisher.java`):

1. **Different consumers, different transport semantics.** Lifecycle events
   are a *cross-process* feed for the admin dashboard: serialized (Jackson),
   published over `WorkflowMessaging` to a broker topic, best-effort,
   deliberately decoupled from the calling thread via
   `LifecycleEventPublisher`'s bounded off-thread queue. `EngineObserver` is
   an *in-process, synchronous* seam whose adapters (meter increments, span
   start/stop) must run on the emitting thread to be correct — a span must
   wrap the activity execution it describes; a queue in between destroys
   that.
2. **Different gating.** Lifecycle events are gated by
   `maestro.admin.events.enabled` through `GatedWorkflowMessaging` — an
   operator choice about broker traffic and the dashboard. Metrics/tracing
   are gated by `maestro.observability.*` (§7). Subsuming one seam into the
   other would couple metric completeness to admin-event configuration
   (disable the dashboard, lose your counters) — exactly the kind of silent
   divergence `GatedWorkflowMessaging`'s own Javadoc was written to end.
3. **Different payload rules.** Lifecycle events carry `taskQueue`,
   `stepName`, optional `detail` JSON and are keyed/consumed by workflowId.
   Observer records are deliberately identity-only (§B2 cardinality rules).
4. **No wrapping either.** Making the lifecycle publisher an
   `EngineObserver` implementation was considered and rejected *for this
   cycle*: `LifecycleEventType` has event kinds the observer surface
   deliberately lacks (e.g. `WORKFLOW_RETRIED`) and the publisher needs
   `taskQueue`/instance UUID, which the observer records do not carry. The
   two seams coexist; unifying them is a possible post-1.0 cleanup, noted in
   the report, not attempted here.

Both seams fire at mostly the same code sites; the implementation tasks add
observer calls *next to* the existing `publishLifecycleEvent` calls, never
replacing them.

---

## 2. Meter catalog (Micrometer, starter — Task 4)

### 2.1 Adapter

`io.b2mash.maestro.spring.observe.MicrometerEngineObserver` (new package in
`maestro-spring-boot-starter`), `public final`, implements `EngineObserver`.
It receives a `MeterRegistry` and builds meters lazily through
`Counter.builder(...).tag(...).register(registry)` — Micrometer deduplicates
by (name, tags), so per-callback registration is cheap and correct. Every
counting/timing callback returns immediately when `replayed == true`.

### 2.2 Final meter names, types, tags

Tag key `workflow` = `workflowType` (code-bounded). Tag key `activity` =
step name `group.method` (code-bounded). Tag key `signal` = signal name
(code-bounded — signal names are string literals in workflow/listener code).
**Never** `workflowId`, `runId`, or timer IDs (timer IDs embed sequence
numbers — unbounded).

| Meter | Type | Tags | Incremented from |
|---|---|---|---|
| `maestro.workflow.started` | Counter | `workflow` | `workflowStarted` |
| `maestro.workflow.completed` | Counter | `workflow` | `workflowCompleted` |
| `maestro.workflow.failed` | Counter | `workflow` | `workflowFailed` |
| `maestro.workflow.compensated` | Counter | `workflow` | `workflowCompensating` |
| `maestro.workflow.terminated` | Counter | `workflow` | `workflowTerminated` |
| `maestro.activity.duration` | Timer | `workflow`, `activity`, `outcome` = `completed`\|`failed` | `activityCompleted` / `activityFailed` (live only) |
| `maestro.signal.consumed` | Counter | `workflow`, `signal` | `signalConsumed` (live only) |
| `maestro.timer.fired` | Counter | `workflow` | `timerFired` (live only) |
| `maestro.recovery.adopted` | Counter | *(none)* | `recoveryPass` — `increment(adopted)` |
| `maestro.recovery.scanned` | Counter | *(none)* | `recoveryPass` — `increment(scanned)`; covers the multi-instance cycle's external recovery-call sampling from inside |
| `maestro.lock.renew.failures` | Counter | `outcome` = `error`\|`lost` | `instanceLockRenewFailed` (`error`) / `instanceLockLost` (`lost`) |
| `maestro.standdown` | Counter | `reason` = `unknown_event_type`\|`unknown_event_payload`\|`stale_run` | `standDown` |
| `maestro.workflows.running` | Gauge | *(none)* | see 2.3 |
| `maestro.workflows.parked` | Gauge | *(none)* | see 2.3 |

Notes:
- `maestro.workflow.failed` does **not** tag exception type (open-ended set;
  the `exceptionType` callback argument exists for logging/audit observers,
  not for tags).
- `workflowFailed`/`workflowCompleted` fire only when
  `transitionToTerminal` returned `true`, so the counter counts durable
  outcomes exactly once cluster-wide for the node that won the transition.
- `maestro.recovery.scanned` is an addition beyond the spec's minimum set,
  justified by spec §B2's closing sentence (the multi-instance
  `MetricsSampler` measured recovery calls and parked counts; scanned-vs-
  adopted is the operator signal for "orphans exist but can't be adopted").

### 2.3 Gauge sourcing — decision: in-JVM state-tracking, not store-polling

`maestro.workflows.running` gauges `WorkflowExecutor.runningCount()`
(existing method, backed by the `runningWorkflows` map).
`maestro.workflows.parked` gauges a new
`WorkflowExecutor.parkedCount()` → delegating to a new
`ParkingLot.waiterCount()` (size of its waiter map; `ParkingLot` already
owns that state — Task 3 adds the accessor).

Registration: `MicrometerEngineObserver` cannot see the executor, so the
gauges are registered in the starter's observability auto-configuration
(§7), which has both the `MeterRegistry` and the `WorkflowExecutor` bean:
`Gauge.builder("maestro.workflows.running", executor, WorkflowExecutor::runningCount)`.

Rationale vs store-polling: these are *node* gauges — "what is this JVM
doing" — which is what an operator dashboards per-pod and sums for cluster
totals. Store-polling gauges would (a) put a `COUNT(*)` on every scrape of
every node, (b) report identical cluster-wide numbers from every node,
making sums wrong by a factor of the fleet size, and (c) couple scrape
latency to DB health. The cluster-truth view already exists in the store
and the admin dashboard; node gauges are the missing piece. This matches
what the multi-instance cycle's `MetricsSampler` measured externally
(per-node parked counts).

---

## 3. Tracing approach (Task 5)

### 3.1 Decision: Micrometer **Tracing API** (`io.micrometer:micrometer-tracing`), not the Observation API, not the direct OTel SDK

Three candidates were weighed against the spec's hard requirements (span per
activity, span per workflow run segment, events for signal consume / timer
fire, **no spans during replay**, remote-parent restoration from Kafka
headers) and Boot 4 idiom:

1. **Micrometer Observation API** — Boot 4's default instrumentation front
   door, but wrong at two load-bearing points:
   - An `Observation` fuses metrics and tracing behind one handler chain.
     The spec gives metrics and tracing *independent* config seams
     (`maestro.observability.metrics.enabled` /
     `...tracing.enabled`); driving both from Observations would either
     double-register meters against §2's hand-built catalog (Observation
     timers use the observation name — which would collide with or diverge
     from the pinned `maestro.activity.duration`) or require suppressing
     `ObservationHandler`s per-seam — more machinery than it saves.
   - Observations model a scoped start→stop on one thread. Workflow *run
     segments* end at a park and restart at an unpark hours later, and must
     adopt a **remote** parent extracted from a Kafka header persisted with
     a signal. That is `Tracer.spanBuilder().setParent(extractedContext)`
     territory; the Observation API has no first-class remote-parent story.
2. **Direct OpenTelemetry SDK** — supports everything, but binds
   `maestro-spring-boot-starter` to one tracer implementation, bypasses
   Boot's `management.tracing.*` auto-configuration (sampling, propagators,
   exporters), and forfeits Brave compatibility. Rejected.
3. **Micrometer Tracing API** (`Tracer`, `Span`, `TraceContext`,
   `Propagator`) — the abstraction Boot 4's actuator tracing
   auto-configuration itself builds on. Boot supplies the `Tracer` and
   `Propagator` beans from whichever bridge the application ships
   (`micrometer-tracing-bridge-otel` or `-brave`); our adapter consumes
   them. Supports manual span lifecycle across parks, remote parents,
   span events, and honours all `management.tracing.*` configuration.
   **Chosen.**

Consequence: `maestro-spring-boot-starter` gets an `optional`/`compileOnly +
testImplementation` dependency on `io.micrometer:micrometer-tracing` (version
managed by the Spring Boot BOM); `maestro-core` gets nothing.

### 3.2 Adapter and span topology

`io.b2mash.maestro.spring.observe.TracingEngineObserver` (`public final`,
starter), constructor `(Tracer tracer, Propagator propagator)`.

Span topology:

| Span | Name | Opened at | Closed at | Parent |
|---|---|---|---|---|
| Workflow run segment | `maestro.workflow.run` | `workflowStarted` / `workflowResumed` / `workflowUnparked` | `workflowParked` / `workflowCompleted` / `workflowFailed` / `workflowTerminated` / `standDown` | previous segment of the same thread if one exists (live park→unpark chain); else the **remote context** from `signalConsumed(traceContext)` when the unpark was signal-driven (§4); else a new root |
| Activity | `maestro.activity` | `activityStarted` (live only) | `activityCompleted` / `activityFailed` (live only) | current segment span |

Span events (recorded on the current segment span, live only):
`maestro.signal.consumed` (attribute `maestro.signal.name`),
`maestro.timer.fired` (attribute `maestro.timer.id`),
`maestro.timer.cancelled`, `maestro.signal.persisted`.

Span attributes (spec B3: MDC keys become attributes — attributes are not
metric tags, so workflowId here is allowed and required):

- segment span: `maestro.workflow.id`, `maestro.run.id` (from
  `WorkflowInfo` — **requires** `WorkflowInfo` to carry `runId`? No: the MDC
  is already populated on every engine thread by `WorkflowMDC`; the adapter
  reads `MDC.get("workflowId")`/`("runId")` — decision: attributes come from
  the observer arguments where present (`workflowId`, `workflowType`) plus
  `runId` read from MDC, which `WorkflowMDC.populate` guarantees on workflow
  threads), `maestro.workflow.type`, `maestro.service.name`.
- activity span: the above plus `maestro.activity.name` (=`activityName`
  MDC key), `maestro.sequence`.

State model inside the adapter: a `ThreadLocal<SegmentState>` holding the
open segment `Span` + `Tracer.SpanInScope`. This is sound because a
workflow's virtual thread survives live parks (the thread that parks is the
thread that resumes — see `ParkingLot` usage in
`SignalManager.awaitSignal` / `DefaultWorkflowOperations.parkForTimer`), and
parallel branches run on distinct threads with their own callbacks. The
ThreadLocal is cleared defensively in every segment-closing callback.

**No spans during replay:** every span/event-creating callback returns
immediately when `replayed == true`; `activityStarted` is only ever emitted
on the live path by construction. `workflowResumed` opens a segment span
(the resume itself is live even though the code inside it replays); the
memoized steps replayed inside that segment emit nothing.

**Crash/cross-node continuity (documented limitation):** after a crash, the
recovered run's first segment starts a new trace unless its wake carries a
remote context (signal-driven resume, §4). Timer-driven and
recovery-driven resumes do not persist trace context — extending the
instance row with one was considered and rejected as schema surface this
cycle does not need (the spec's connected-trace requirement is the
signal-driven cross-service path, which §4 covers durably).

---

## 4. Kafka propagation contract (Task 5, `maestro-messaging-kafka`)

### 4.1 Wire contract — exact header names

W3C Trace Context, lowercase, as ASCII Kafka record headers:

| Header | Content | Required |
|---|---|---|
| `traceparent` | `00-{32 hex trace-id}-{16 hex span-id}-{2 hex flags}` | yes, when a span is active at publish |
| `tracestate` | W3C tracestate list | only if non-empty |
| `baggage` | W3C baggage | only if the configured `Propagator` emits it (it is "cheap": we inject whatever Boot's propagator produces, no extra work) |

The header set is produced by Boot's configured
`io.micrometer.tracing.propagation.Propagator` (which for the OTel bridge
with default `management.tracing.propagation.type=W3C` emits exactly these).
The **contract test pins the header names and the `traceparent` grammar**
(regex `^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$`), not just "whatever the
propagator did" — if a future Boot default changed the propagation type, the
pin fails loudly.

### 4.2 Injection point

`KafkaWorkflowMessaging` gains an optional collaborator (new final field,
new constructor; existing constructor delegates with `null`):

```java
public KafkaWorkflowMessaging(
        KafkaTemplate<String, byte[]> kafkaTemplate,
        ConsumerFactory<String, byte[]> consumerFactory,
        ObjectMapper objectMapper,
        KafkaMessagingConfig config,
        @Nullable KafkaTracePropagation tracePropagation)
```

`io.b2mash.maestro.messaging.kafka.KafkaTracePropagation` (`public final`,
same module) wraps `(Tracer tracer, Propagator propagator)` and exposes:

```java
/** Injects the current span context (if any) into the record headers. */
public void inject(Headers headers);

/** @return the raw traceparent header value, or null */
public @Nullable String extractTraceparent(Headers headers);

/** Runs {@code action} with the extracted remote context current, and with
 *  TraceContextHolder set to the raw traceparent for the duration. */
public void runWithExtractedContext(Headers headers, Runnable action);
```

`KafkaWorkflowMessaging.send(...)` (the single private publish funnel used by
`publishTask` and `publishSignal`) changes from
`kafkaTemplate.send(topic, key, bytes)` to building a
`ProducerRecord<String, byte[]>(topic, key, bytes)`, calling
`tracePropagation.inject(record.headers())` when the collaborator is
non-null, then `kafkaTemplate.send(record).get()`.
`publishLifecycleEvent` gets the same treatment (harmless, and it lets the
admin dashboard join traces later).

`maestro-messaging-kafka` gains the same optional
`io.micrometer:micrometer-tracing` dependency (BOM-managed). When no
`Tracer`/`Propagator` beans exist, the collaborator bean is absent and the
wire format is byte-identical to today.

### 4.3 Extraction point and context restoration at workflow resume

Extraction happens in `KafkaWorkflowMessaging.createContainer`'s listener
wrapper — the only place the raw `ConsumerRecord` (and its headers) is
visible before the payload-typed `Consumer<SignalMessage>` handler runs:

```java
var container = createContainer(topic, record -> {
    var message = deserialize(record.value(), SignalMessage.class);
    if (tracePropagation == null) { handler.accept(message); return; }
    tracePropagation.runWithExtractedContext(record.headers(),
            () -> handler.accept(message));
});
```

From there the context must survive two hops: (a) listener thread →
signal row (durable), (b) signal row → workflow thread at consume time.

**(a) Listener thread → store.** `runWithExtractedContext` sets
`io.b2mash.maestro.core.observe.TraceContextHolder` — a framework-free
`public final class` in core holding a `private static final
ThreadLocal<@Nullable String>` with `set/current/clear` — for the duration
of the handler call. `SignalSubscriptionRunner`'s handler calls
`executor.deliverSignal(...)` synchronously on that same thread, and
`SignalManager.deliverSignal` reads `TraceContextHolder.current()` and
persists it on the signal row. This avoids changing the `WorkflowMessaging`
SPI's `Consumer<SignalMessage>` shape and keeps core free of tracing types
(the holder trafficks in `String`).

**(b) Durable row → workflow thread.** `WorkflowSignal` gains one component:

```java
public record WorkflowSignal(
        UUID id,
        @Nullable UUID workflowInstanceId,
        String workflowId,
        String signalName,
        @Nullable JsonNode payload,
        boolean consumed,
        Instant receivedAt,
        @Nullable String traceContext   // NEW — raw W3C traceparent, ≤ 64 chars
) {}
```

with a Flyway migration `V4__signal_trace_context.sql` in
`maestro-store-postgres`:
`ALTER TABLE ${prefix}workflow_signal ADD COLUMN trace_context VARCHAR(128);`
(the migration uses the literal default prefix `maestro_` exactly as
V1–V3 do). Touch points for the record change: `AbstractJdbcWorkflowStore`
(`saveSignal` insert column list, `mapSignal`), `InMemoryWorkflowStore`,
`SignalManager.deliverSignal` construction site, and any test fixtures
constructing `WorkflowSignal` (mechanical).

When the parked workflow's `awaitSignal` consumes the row (on the workflow
virtual thread, possibly on a *different node* — the durable row is exactly
why this hop works cross-node), `SignalManager.consumeSignal` passes
`signal.traceContext()` into `observer.signalConsumed(new SignalInfo(...,
signal.traceContext()), false)`. `TracingEngineObserver` then:

1. records the `maestro.signal.consumed` span event on the current segment,
2. if no segment is open (signal-triggered resume after crash) or the
   current segment is a fresh root, re-parents/opens the next segment span
   with `Propagator.extract` applied to a single-entry
   (`traceparent` → value) carrier — giving the spec's required
   "resumed segment's span has the remote parent".

Replay of a memoized `SIGNAL_RECEIVED` event carries `traceContext = null`
and `replayed = true` → no span, per the no-spans-during-replay invariant.

### 4.4 What is pinned where

- `maestro-messaging-kafka` test `KafkaTracePropagationContractTest`
  (Testcontainers Kafka + `micrometer-tracing-test` `SimpleTracer` +
  a real W3C `Propagator` from the OTel bridge as `testImplementation`):
  publish inside a span → consume raw record → assert header names
  `traceparent`/`tracestate` present and grammar-valid; consume through the
  wrapper → assert the handler observed the remote context and
  `TraceContextHolder.current()` equals the sent traceparent.
- The end-to-end single-connected-trace assertion lives in
  `maestro-integration-tests` (§8).

---

## 5. `VERSION_MARKER` and `workflow.version()` (Task 6)

### 5.1 Event type and payload

New `EventType` constant (in `io.b2mash.maestro.core.model.EventType`):

```java
/**
 * A memoized versioning decision recorded by {@code WorkflowContext.version()}.
 * Payload: {@code {"changeId": "...", "version": N}}. Introduced in 0.4.0 —
 * nodes older than 0.4.0 stand down when they encounter it (see the
 * unknown-event stand-down design); upgrade all nodes of a service together.
 */
VERSION_MARKER
```

Payload record (private, in `DefaultWorkflowOperations`, serialized with the
engine's `PayloadSerializer` — Jackson 3, `tools.jackson`):

```java
/** Payload of a {@link EventType#VERSION_MARKER} event. */
private record VersionDetail(String changeId, int version) {}
```

JSON shape on the wire / in `maestro_workflow_event.payload`:

```json
{"changeId": "shipping-v2", "version": 3}
```

Step name recorded on the event: `$maestro:version:{changeId}` — this makes
version decisions visible in `DeterminismChecker` fingerprints
(`seq:VERSION_MARKER:$maestro:version:shipping-v2`) with **no checker
change**: the checker already fingerprints
`sequence:eventType:stepName` (`DeterminismChecker.decisions(...)`), so
markers are treated as decisions by construction. A pin test proves it (§8).

### 5.2 API signature and semantics

```java
// WorkflowContext (public API)

/**
 * The version returned for a change-id when the workflow's history predates
 * the change (no marker recorded). Mirrors Temporal's DEFAULT_VERSION.
 */
public static final int DEFAULT_VERSION = -1;

/**
 * Memoized change-branching (Temporal-proven model). First live evaluation
 * records {@code maxSupported} durably and returns it; every replay returns
 * the recorded value forever, regardless of the code's current
 * {@code maxSupported}. Histories that predate the change-id yield
 * {@link #DEFAULT_VERSION}.
 *
 * @throws UnsupportedWorkflowVersionException if the resolved version is
 *         below {@code minSupported} — the running code no longer carries
 *         the branch this instance needs
 */
public int version(String changeId, int minSupported, int maxSupported) {
    return requireOperations().version(changeId, minSupported, maxSupported);
}
```

`WorkflowOperations` gains the matching method; `DefaultWorkflowOperations`
implements it. Argument validation: `changeId` non-blank,
`minSupported <= maxSupported`, `maxSupported >= 0`
(`IllegalArgumentException` — a coding error, not a workflow outcome).

Resolution algorithm (`DefaultWorkflowOperations.version`):

```
1. cached = versionCache.get(changeId)            // per-run cache, see below
   if cached != null → guard(cached) → return cached
2. peekSeq = ctx.currentSequence() + 1            // PEEK — do not consume yet
   stored  = store.getEventBySequence(ctx.workflowInstanceId(), peekSeq)
3. if stored present AND stored.eventType == VERSION_MARKER
       AND payload.changeId == changeId:
       ctx.nextSequence()                          // consume the slot
       v = payload.version
4. else if stored present:                         // any other event type —
       v = DEFAULT_VERSION                         // history predates this
       // sequence NOT consumed: the stored event belongs to the next step
       // (this is what lets version() calls be introduced into code without
       // shifting old instances' sequence space)
5. else:                                           // live frontier
       seq = ctx.nextSequence()
       ctx.setReplaying(false)
       appendEvent(ctx, seq, VERSION_MARKER, "$maestro:version:" + changeId,
                   serializer.serialize(new VersionDetail(changeId, maxSupported)))
       v = maxSupported
6. guard: if v < minSupported →
       throw new UnsupportedWorkflowVersionException(
               ctx.workflowId(), changeId, v, minSupported, maxSupported)
7. versionCache.put(changeId, v); return v
```

Step 4 is the load-bearing subtlety and the reason `version()` peeks instead
of consuming unconditionally: an old instance replaying code into which a
`version()` call was newly inserted finds its *original* event (e.g.
`ACTIVITY_COMPLETED`) at the peeked slot. Consuming the slot would shift
every subsequent replay lookup off by one and corrupt the run; returning
`DEFAULT_VERSION` without consuming keeps the old history byte-stable while
routing the instance down the pre-change branch. This is exactly the
property that makes `version()` *usable* — Temporal's `getVersion` has the
same "marker absent → DEFAULT_VERSION, no history mutation" contract.

Stand-down interplay: if the peeked event's type is `UNKNOWN` (§6), step 4
must NOT interpret it as "predates the change" — the guard in §6.3 runs
before step 4's classification (an unknown event means *this node cannot
read this history at all*).

### 5.3 Per-run cache — repeated calls, same changeId

`DefaultWorkflowOperations` gains
`private final ConcurrentHashMap<String, Integer> versionCache = new ConcurrentHashMap<>();`.
The operations instance is created per local run
(`WorkflowExecutor.launchWorkflow` builds a fresh one for every launch), so
the cache's lifetime is exactly one run — recovery re-resolves from the
durable marker.

This is what satisfies the spec's determinism clause "repeated calls with
the same changeId in one run return the same value": without the cache, an
old instance replaying `recorded=2` at the first call site and reaching a
*second* call site for the same changeId at the live frontier would record
and return `maxSupported=3` — two values in one run. With the cache, the
second and later calls return the first resolution **without consuming a
sequence number and without writing** — replay-stable, because the original
run did the same.

**Parallel-branch rule (documented, and enforced by the checker):** branches
share the operations instance, hence the cache. Two branches racing to be
the *first* resolver of the same changeId would place the marker in
whichever branch's sequence space won — nondeterministic across runs. The
documented rule is: resolve a changeId **before** forking branches that
depend on it (call `workflow.version(...)` in the parent, pass the value
in). `DeterminismChecker` surfaces violations (the marker's
`seq` diverges between runs → fingerprint mismatch), and the Javadoc on
`version()` states the rule. No runtime enforcement — same trust level as
every other determinism constraint in the engine.

Sequence allocation inside branches follows the standard rules with no
special casing: a branch's `version()` call peeks/consumes within the
branch's own partitioned space (`p*1000 + (i+1)*1000` base), which the
parallel-branch allocation test pins (§8).

### 5.4 Min-guard error type

```java
package io.b2mash.maestro.core.exception;

/**
 * A workflow replayed a versioning decision the running code no longer
 * supports: the recorded (or default) version for {@code changeId} is below
 * the {@code minSupported} the code now declares. This means the branch this
 * instance needs has been removed from the workflow definition.
 *
 * <p>This is a genuine, deterministic workflow failure (it will fail the
 * same way on every node until the code carrying the old branch is
 * restored), so unlike the engine's control-flow signals it extends
 * {@link MaestroException} and is catchable by workflow authors. After
 * restoring the branch (or migrating the instance), the admin Retry action
 * re-drives the workflow normally.
 */
public final class UnsupportedWorkflowVersionException extends MaestroException {
    private final String workflowId;
    private final String changeId;
    private final int recordedVersion;
    private final int minSupported;
    private final int maxSupported;

    public UnsupportedWorkflowVersionException(String workflowId, String changeId,
            int recordedVersion, int minSupported, int maxSupported) {
        super(("Workflow '%s' recorded version %d for change '%s', but the running "
                + "code supports only [%d..%d]. The branch this instance needs has "
                + "been removed — restore code supporting version %d (or migrate "
                + "the instance) and retry.")
                .formatted(workflowId, recordedVersion, changeId,
                        minSupported, maxSupported, recordedVersion));
        this.workflowId = workflowId;
        this.changeId = changeId;
        this.recordedVersion = recordedVersion;
        this.minSupported = minSupported;
        this.maxSupported = maxSupported;
    }
    // accessors: workflowId(), changeId(), recordedVersion(), minSupported(), maxSupported()
}
```

Decision rationale — `MaestroException`, **not** an `Error`: the stand-down
channel exists for conditions that are *not* workflow outcomes (deploys,
terminations, unreadable history). A too-low recorded version is different
in kind: the author changed the code out from under a live instance; the
failure is deterministic, attributable, and actionable, and hiding it from
the failure path would leave the instance silently thrashing. Failing the
workflow (with compensation, per the author's saga design) is the honest
outcome, and `retryWorkflow` composes with it once the code is fixed
(`deleteFailureEvents` clears the `WORKFLOW_FAILED` memo; the marker —
a non-failure event — survives, so the retried run replays the same
recorded version against the restored branch).

---

## 6. Unknown-event stand-down (Task 6)

### 6.1 Store row-mapper sentinel

**Exact site:** `AbstractJdbcWorkflowStore.mapEvent(ResultSet)`
(`maestro-store-jdbc`, currently line ~622), the only place a persisted
`event_type` string becomes an `EventType`
(`EventType.valueOf(rs.getString("event_type"))` — throws
`IllegalArgumentException` today, which would surface as an
`UncheckedSqlException`-adjacent crash inside `getEventBySequence`/
`getEvents` and, caught by a workflow's `catch (Exception)` or the
executor's generic handler, be recorded as a *workflow failure with
compensation* — the precise catastrophe this section prevents.)

Design:

1. `EventType` gains a sentinel constant and a total parse function:

```java
/**
 * Row-mapper sentinel for a persisted event type this build does not know
 * (written by a newer node). NEVER persisted: WorkflowStore.appendEvent
 * implementations reject it. The engine stands down when it reads one.
 */
UNKNOWN;

/**
 * Total parse: the enum constant for {@code name}, or {@link #UNKNOWN} if
 * this build does not define it. Row mappers MUST use this instead of
 * {@link #valueOf(String)}.
 */
public static EventType fromStoredName(String name) {
    try { return valueOf(name); }
    catch (IllegalArgumentException e) { return UNKNOWN; }
}
```

2. `mapEvent` changes to:

```java
var rawType = rs.getString("event_type");
var eventType = EventType.fromStoredName(rawType);
if (eventType == EventType.UNKNOWN) {
    log.warn("Unknown event type '{}' at (instance={}, seq={}) — written by a newer "
            + "node; this node will stand down when it reads this history",
            rawType, rs.getObject("workflow_instance_id", UUID.class),
            rs.getInt("sequence_number"));
}
```

   The mapper **never throws** for an unknown type; the WARN here is the
   durable diagnostic that carries the raw string (the `WorkflowEvent`
   record is unchanged — the raw name is not threaded through the model, it
   is logged at the one place it exists).

3. Write-side guard (both `AbstractJdbcWorkflowStore.appendEvent` and
   `InMemoryWorkflowStore.appendEvent`):
   `if (event.eventType() == EventType.UNKNOWN) throw new IllegalArgumentException(
   "EventType.UNKNOWN is a read-side sentinel and must never be persisted");`
   — the sentinel can never round-trip. `InMemoryWorkflowStore` additionally
   gets a package-visible `injectRawEvent(WorkflowEvent)` **test seam** that
   bypasses the guard, so core-level stand-down unit tests can plant unknown
   history without SQL.

`WorkflowStatus.valueOf` and `TimerStatus.valueOf` in `mapInstance` /
`mapTimer` are deliberately left strict: this cycle introduces no new
status values, and a total-parse there would have no defined engine
behavior to fall back on. (Recorded as a known asymmetry, not an oversight.)

### 6.2 The control-flow signal

```java
package io.b2mash.maestro.core.exception;

/**
 * Signals that a workflow's <em>local run</em> must stand down because this
 * node cannot interpret the workflow's persisted history — an event whose
 * type is unknown to this build ({@code EventType.UNKNOWN}), or a stored
 * payload it cannot deserialize during replay. Written by a newer node
 * during a mixed-version deploy window. It is <b>not</b> a workflow failure.
 *
 * <p>The run stands down exactly like a graceful shutdown does: nothing is
 * written, no compensation runs, the instance keeps its recoverable status,
 * and the instance lock is released as the thread unwinds — an upgraded
 * node adopts and processes the workflow through the ordinary
 * lock-TTL/recovery-poller machinery, unchanged.
 *
 * <h2>Why this extends {@code Error}, not {@code MaestroException}</h2>
 * <p>Same rationale as {@link ExecutorShutdownException}, which see: a
 * workflow author's ordinary {@code try { ... } catch (Exception e)} around
 * an activity or park point must not be able to intercept this and convert
 * "this node is too old to read this history" into a recorded workflow
 * failure — running compensations for work that never failed, on the older
 * half of a fleet mid-deploy. Extending {@code Error} puts it outside
 * {@code catch (Exception)}'s reach; broad {@code catch (Throwable)}
 * collectors in the engine check for {@link MaestroControlFlowError} and
 * rethrow before recording anything as a failure.
 *
 * <h2>Workflow authors</h2>
 * <p>Do not catch, swallow, or wrap this. If you must catch broadly, check
 * for {@code MaestroControlFlowError} first and rethrow.
 */
public final class UnknownWorkflowHistoryException extends MaestroControlFlowError {

    /** Why the history could not be interpreted. */
    public enum Kind { UNKNOWN_EVENT_TYPE, UNKNOWN_EVENT_PAYLOAD }

    private final String workflowId;
    private final int sequenceNumber;
    private final Kind kind;

    public UnknownWorkflowHistoryException(String workflowId, int sequenceNumber,
                                           Kind kind, String message) {
        super(message);
        this.workflowId = workflowId;
        this.sequenceNumber = sequenceNumber;
        this.kind = kind;
    }
    // accessors: workflowId(), sequenceNumber(), kind()
}
```

**New shared base (elegance decision):**

```java
package io.b2mash.maestro.core.exception;

/**
 * Sealed base for the engine's control-flow signals — throwables that mean
 * "this workflow's local run must stop now" and are never workflow
 * failures. Extends {@link Error} so workflow-author {@code catch
 * (Exception)} blocks cannot intercept them; see each subtype's Javadoc.
 * Broad {@code catch (Throwable)} collectors in the engine catch (or
 * instanceof-check) THIS type and rethrow before recording failures.
 */
public sealed abstract class MaestroControlFlowError extends Error
        permits ExecutorShutdownException, WorkflowTerminatedException,
                UnknownWorkflowHistoryException {
    protected MaestroControlFlowError(String message) { super(message); }
}
```

`ExecutorShutdownException` and `WorkflowTerminatedException` are re-parented
from `Error` to `MaestroControlFlowError` (still `Error`s — no behavioral
change anywhere; their Javadoc gains one line pointing at the base). This
turns "remember to enumerate all three types at every broad-catch site" into
a single-type check, which is why §6.4's list is short.

### 6.3 Detection sites — where `UNKNOWN` is checked

A single package-private guard in `io.b2mash.maestro.core.engine`:

```java
final class UnknownHistoryGuard {
    /** Throws if the stored event's type is the UNKNOWN sentinel. */
    static WorkflowEvent requireKnown(WorkflowEvent event, String workflowId) {
        if (event.eventType() == EventType.UNKNOWN) {
            throw new UnknownWorkflowHistoryException(workflowId,
                    event.sequenceNumber(),
                    UnknownWorkflowHistoryException.Kind.UNKNOWN_EVENT_TYPE,
                    "Workflow '%s' has an event of an unknown type at sequence %d — "
                    + "written by a newer node; standing this run down"
                            .formatted(workflowId, event.sequenceNumber()));
        }
        return event;
    }
}
```

Applied immediately after **every** `store.getEventBySequence(...)` whose
result drives replay/memoization decisions:

| File | Member | Reads guarded |
|---|---|---|
| `engine/ActivityInvocationHandler.java` | `invoke` | the memoization lookup before `handleReplay` (also delete the now-unreachable `default ->` `IllegalStateException`? **No** — keep it; it still guards known-but-wrong types like a `TIMER_SCHEDULED` at an activity slot) |
| `engine/SignalManager.java` | `awaitSignal` | the replay check at the top |
| `engine/DefaultWorkflowOperations.java` | `sleep` | both `storedEvent` and `nextEvent` reads |
| `engine/DefaultWorkflowOperations.java` | `parallel`, `currentTime`, `randomUUID` | the replay checks (today an unknown type would fall through to the live path, attempt a duplicate append and stand down as `STALE_RUN` — wrong reason, and only after attempting writes) |
| `engine/DefaultWorkflowOperations.java` | `version` (new, §5) | the peeked event, **before** the predates-change classification |
| `saga/SagaManager.java` | `compensate` (the `COMPENSATION_STARTED`/`COMPENSATION_COMPLETED` replay-skip reads), `executeSequential`, `executeParallel` (the per-entry replay-skip guards) |

`getEvents(...)` consumers (`retryWorkflow`'s `COMPENSATION_STARTED` probe)
need no guard: an `UNKNOWN` element simply doesn't match the probe, and
retry then relaunches into a replay that stands down at the guard — correct
and lazy.

Payload-unmappable (`Kind.UNKNOWN_EVENT_PAYLOAD`): the replay-path
deserializations wrap `SerializationException` into the stand-down signal —
sites: `ActivityInvocationHandler.deserializeResult` (replay caller only),
`SignalManager.awaitSignal`'s `SIGNAL_RECEIVED` deserialize,
`DefaultWorkflowOperations.currentTime`/`randomUUID` replay deserializes,
and `version()`'s marker-payload parse. Live-path serialization failures
remain ordinary failures — only *stored* history you cannot read is a
stand-down.

### 6.4 Catch/unwrap sites — Error-first ordering audit (exhaustive)

Sites that already handle any `Error` correctly (no change; listed for the
review checklist):

1. `engine/WorkflowExecutor.java` — `invokeWorkflowMethod` (~1433):
   `cause instanceof Error` before `Exception`. ✓
2. `engine/WorkflowExecutor.java` — `invokeQueryMethod` (~1698):
   `RuntimeException` re-thrown before the `Error` check — disjoint types,
   order immaterial; leave as-is. ✓
3. `engine/ActivityInvocationHandler.java` — `invokeActivity` (~361):
   `Error` first. ✓
4. `engine/ActivityInvocationHandler.java` — compensation lambda (~543):
   `Error` first. ✓
5. `engine/DefaultWorkflowOperations.java` — `parallel`'s branch-outcome
   loop (~541): `error instanceof Error` rethrown before wrapping. ✓
6. `engine/ParkingLot.java` — CompletableFuture unwrap (~224):
   `cause instanceof Error` first. ✓

Sites that **must change** in Task 6:

7. `retry/RetryExecutor.java` — `executeWithRetry` (~53): today it
   special-cases `ExecutorShutdownException` and
   `WorkflowTerminatedException` then `catch (Throwable)`-wraps everything
   else into `ActivityExecutionException`. Replace the two catches with one
   `catch (MaestroControlFlowError e) { throw e; }` — otherwise a
   stand-down raised by a nested replay read (compensation actions run
   through the proxy and the retry executor) would be wrapped, retried with
   backoff, and recorded as an activity failure.
8. `saga/SagaManager.java` — `executeParallel`'s branch `catch (Throwable
   t)` (~388) collects outcomes; the outcome loop currently rethrows the two
   named types before recording `COMPENSATION_STEP_FAILED`. Change both the
   loop (and the matching check in `executeSequential`'s `catch` structure,
   ~240) to check `instanceof MaestroControlFlowError` and rethrow —
   otherwise a stand-down during compensation replay is recorded as a
   failed compensation step.
9. `engine/WorkflowExecutor.java` — `executeWorkflow` (~1321): new catch
   arm, placed with the other non-failure arms (after
   `DuplicateEventException`, before `catch (Exception)`; ordering is
   readability-only since it is an `Error`):

```java
} catch (UnknownWorkflowHistoryException e) {
    handleUnknownHistoryStandDown(ctx, e);
}
```

   plus a fourth nested catch inside the `catch (Exception)` compensation
   block (`unknownHistoryDuringCompensation`), mirroring the existing
   shutdown/terminate/stale nested catches: the instance stays
   `COMPENSATING`, nothing recorded.
10. `maestro-test/TestWorkflowEnvironment` / `TestWorkflowHandle` — Task 6
    must verify the handle's completion surfacing does not swallow `Error`s
    (audit item; expected no-op since the executor handles the signal
    before the thread dies).

### 6.5 The stand-down handler, lock release, observer

```java
/**
 * Records that a workflow's local run stood down because this node cannot
 * interpret the workflow's persisted history (mixed-version deploy window).
 * Deliberately writes nothing: the instance keeps its recoverable status
 * and an upgraded node adopts it through ordinary recovery. NEVER recorded
 * as a workflow failure; NEVER triggers compensation.
 */
private void handleUnknownHistoryStandDown(WorkflowContext ctx,
                                           UnknownWorkflowHistoryException e) {
    logger.warn("Workflow '{}' stood down at sequence {}: {} — no failure recorded, "
            + "no compensation run; an upgraded node will adopt it via recovery",
            ctx.workflowId(), e.sequenceNumber(), e.getMessage());
    observer.standDown(
            e.kind() == UnknownWorkflowHistoryException.Kind.UNKNOWN_EVENT_TYPE
                    ? StandDownReason.UNKNOWN_EVENT_TYPE
                    : StandDownReason.UNKNOWN_EVENT_PAYLOAD,
            ctx.workflowId(),
            "seq=" + e.sequenceNumber());
}
```

`handleStaleRunStandDown` (Issue 18) additionally gains
`observer.standDown(StandDownReason.STALE_RUN, ctx.workflowId(),
"seq=" + e.sequenceNumber())` so the `maestro.standdown` counter covers all
three reasons.

**Lock-release path: no new code.** `executeWorkflow`'s existing `finally`
(remove from `runningWorkflows` → `instanceLockManager.release` →
`parkingLot.clearPending`/`clearTerminated`) runs for this catch arm exactly
as it does for shutdown — the design *relies* on that block and the review
checklist for Task 6 asserts the catch arm adds no early return that could
bypass it.

**Re-adoption churn (documented behavior):** the old node's recovery poller
will re-adopt and re-stand-down the same instance once per poll interval
until an upgraded node wins the lock race. Each pass logs one WARN and
increments `maestro.standdown` — that steadily-rising counter *is* the
operator signal that a mixed fleet is lingering. Deploy guidance (upgrade
all nodes together; stand-down is the safety net, not a mode of operation)
goes into `docs/operations.md` per the spec.

---

## 7. Config seams and build wiring (Tasks 4–5)

### 7.1 Properties — exact records (BUG8 rule respected: canonical ctor only, no no-arg ctors, defaults via `defaults()`)

Added to `MaestroProperties` (field + getter/setter pair on the outer class,
matching every existing nested block):

```java
private ObservabilityProperties observability = ObservabilityProperties.defaults();

/**
 * Observability configuration: Micrometer meters and tracing.
 *
 * @param metrics meter registration and emission
 * @param tracing span creation and Kafka trace propagation
 */
public record ObservabilityProperties(
        @DefaultValue MetricsProperties metrics,
        @DefaultValue TracingProperties tracing
) {
    /** @return the defaults documented above */
    public static ObservabilityProperties defaults() {
        return new ObservabilityProperties(
                MetricsProperties.defaults(), TracingProperties.defaults());
    }
}

/**
 * @param enabled whether Maestro registers and emits Micrometer meters
 *                (requires a {@code MeterRegistry} on the classpath and in
 *                the context; silently inert otherwise)
 */
public record MetricsProperties(@DefaultValue("true") boolean enabled) {
    public static MetricsProperties defaults() { return new MetricsProperties(true); }
}

/**
 * @param enabled whether Maestro creates spans and propagates W3C trace
 *                context through Kafka headers (requires a Micrometer
 *                {@code Tracer} in the context; silently inert otherwise)
 */
public record TracingProperties(@DefaultValue("true") boolean enabled) {
    public static TracingProperties defaults() { return new TracingProperties(true); }
}
```

`MaestroPropertiesBindingTest` gains cases binding
`maestro.observability.metrics.enabled=false` /
`maestro.observability.tracing.enabled=false`.

### 7.2 Auto-configuration and conditional-on rules

New class
`io.b2mash.maestro.spring.observe.MaestroObservabilityAutoConfiguration`,
registered in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```java
// AMENDED BY TASK 4 FIX ROUND 1 (coordinator-approved): `before` is REMOVED.
// `after = MaestroAutoConfiguration.class` plus `afterName` for Boot's own
// metrics auto-configuration — see the ordering note below the block.
@AutoConfiguration(after = MaestroAutoConfiguration.class,
        afterName = {
                "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
                "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"
        })
@ConditionalOnProperty(prefix = "maestro", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class MaestroObservabilityAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "maestro.observability.metrics",
            name = "enabled", havingValue = "true", matchIfMissing = true)
    static class MetricsConfiguration {
        @Bean
        @ConditionalOnBean(MeterRegistry.class)
        MicrometerEngineObserver maestroMicrometerEngineObserver(MeterRegistry registry) {
            return new MicrometerEngineObserver(registry);
        }
        @Bean
        @ConditionalOnBean({MeterRegistry.class, WorkflowExecutor.class})
        MaestroEngineGauges maestroEngineGauges(MeterRegistry registry,
                                                WorkflowExecutor executor) {
            return new MaestroEngineGauges(registry, executor); // registers the two gauges
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Tracer.class)   // io.micrometer.tracing.Tracer
    @ConditionalOnProperty(prefix = "maestro.observability.tracing",
            name = "enabled", havingValue = "true", matchIfMissing = true)
    static class TracingConfiguration {
        @Bean
        @ConditionalOnBean({Tracer.class, Propagator.class})
        TracingEngineObserver maestroTracingEngineObserver(Tracer tracer,
                                                           Propagator propagator) {
            return new TracingEngineObserver(tracer, propagator);
        }
    }
}
```

- **Ordering — amended by Task 4's fix round 1, coordinator-approved.**
  This section originally specified `@AutoConfiguration(before =
  MaestroAutoConfiguration.class)`. That is incompatible with
  `MetricsConfiguration.maestroEngineGauges`'s own
  `@ConditionalOnBean(WorkflowExecutor.class)`: Spring Boot evaluates
  auto-configuration `@ConditionalOnBean` conditions in `before`/`after`
  processing order, so a class ordered `before` `MaestroAutoConfiguration`
  has that condition evaluated before `WorkflowExecutor`'s bean definition
  exists — the gauges bean would never register, in every deployment. Task
  4's implementer verified this empirically and used `after` instead; the
  coordinator approved it, noting the matching shipped in-repo precedent:
  `MaestroHealthAutoConfiguration` (`io.b2mash.maestro.spring.health`)
  already orders itself `@AutoConfiguration(after =
  MaestroAutoConfiguration.class)` for the identical reason (its indicator
  bean needs `WorkflowExecutor` to already exist). `ObjectProvider`
  collection in `maestroWorkflowExecutor` is unaffected either way:
  `ObjectProvider` resolves lazily at actual bean *instantiation*, which
  happens only after every auto-configuration class's bean *definitions* —
  regardless of relative processing order — have already been registered;
  this is what "belt-and-braces, not load-bearing" (the original text
  below) was getting at, and remains true under `after`.
- **A second ordering gap, also found and fixed in Task 4's fix round 1:**
  `after = MaestroAutoConfiguration.class` alone still left
  `@ConditionalOnBean(MeterRegistry.class)` evaluated *before* Boot
  registers any `MeterRegistry` bean definition in a real application —
  `AutoConfigurationSorter` falls back to alphabetical order between
  classes with no explicit relative ordering, and
  `io.b2mash.maestro.spring.observe` sorts before
  `org.springframework.boot.micrometer.metrics.autoconfigure`. The
  `afterName` entries in the code block above (matching Boot's own
  `JvmMetricsAutoConfiguration`/`SystemMetricsAutoConfiguration`, which
  order themselves identically for their own identical
  `@ConditionalOnBean(MeterRegistry.class)` gate) close this gap.
  `afterName` (string class names) rather than `after` (class literals)
  because the starter depends on `micrometer-core` only as `compileOnly`
  and does not depend on `spring-boot-micrometer-metrics` at all.
- Spec rule "`maestro.observability.tracing.enabled` default `true` when a
  tracer is present" falls out structurally:
  `matchIfMissing = true` ∧ `@ConditionalOnBean(Tracer)` — no tracer, no
  spans, no property needed; tracer present, on by default, one property to
  turn off. Metrics identically with `MeterRegistry`.
- `MicrometerEngineObserver` note: gauge registration lives in
  `MaestroEngineGauges` (a small holder bean) because gauges need the
  executor while counters need only the registry; keeping them separate
  avoids a circular executor→observer→executor construction dependency —
  the composite handed to the executor contains only counter/timer/span
  observers, never anything holding the executor.
- Kafka: `KafkaMessagingConfig`'s auto-configuration (module
  `maestro-messaging-kafka`) gains a
  `@Bean @ConditionalOnClass(Tracer.class) @ConditionalOnBean({Tracer.class,
  Propagator.class}) @ConditionalOnProperty(prefix =
  "maestro.observability.tracing", name = "enabled", havingValue = "true",
  matchIfMissing = true) KafkaTracePropagation` bean, injected as an
  `@Nullable`/`ObjectProvider` collaborator into the
  `KafkaWorkflowMessaging` bean factory method.

### 7.3 Version catalog — exact additions to `gradle/libs.versions.toml`

All Micrometer versions are managed by the Spring Boot BOM in the modules
that consume them (starter, messaging-kafka, integration-tests — all Spring
modules), so entries follow the existing BOM-managed pattern
(`spring-kafka` precedent: module without `version.ref`):

```toml
[libraries]
# Micrometer (versions managed by Spring Boot BOM)
micrometer-core = { module = "io.micrometer:micrometer-core" }
micrometer-tracing = { module = "io.micrometer:micrometer-tracing" }
micrometer-tracing-test = { module = "io.micrometer:micrometer-tracing-test" }
micrometer-tracing-bridge-otel = { module = "io.micrometer:micrometer-tracing-bridge-otel" }
```

Usage per module:

| Module | Dependency | Scope |
|---|---|---|
| `maestro-spring-boot-starter` | `micrometer-core`, `micrometer-tracing` | `compileOnly` + `testImplementation` (observability is optional at runtime; conditionals guard) |
| `maestro-messaging-kafka` | `micrometer-tracing` | `compileOnly` + `testImplementation`; `micrometer-tracing-test`, `micrometer-tracing-bridge-otel` `testImplementation` (contract test needs a real W3C propagator) |
| `maestro-integration-tests` | `micrometer-core`, `micrometer-tracing`, `micrometer-tracing-test`, `micrometer-tracing-bridge-otel` | `testImplementation` |
| `maestro-core` | **nothing** | — |

(`micrometer-core` is transitively present wherever
`spring-boot-starter-actuator` is, but the starter must compile without
actuator on the classpath — hence explicit `compileOnly`.)

---

## 8. Test strategy per area

TDD discipline per the global constraints: each named test is written RED
first in its task, failing output captured in the task report.

### 8.1 Observer seam (Task 3, `maestro-core` unit tests)

- `observe/CompositeEngineObserverTest` — fan-out order; a delegate throwing
  `RuntimeException` does not stop later delegates and is logged; a delegate
  throwing `ExecutorShutdownException` (an `Error`) propagates; `of(List)`
  wrapping rules per RULING 4 (a lone delegate is wrapped, is contained like
  any other, and still lets an `Error` through).
- `engine/WorkflowExecutorObserverTest` — a `RecordingEngineObserver` test
  fixture (in-memory list of callback invocations; lives in
  `maestro-core/src/test`) wired through the new executor constructor with
  `InMemoryWorkflowStore`-equivalent core fixtures (the existing
  `VersionedInMemoryStore` test store): asserts `workflowStarted`,
  `activityStarted/Completed` (live, `replayed=false`), `workflowCompleted`,
  `recoveryPass`, terminal-transition single-fire (converged loser emits
  nothing).
- `engine/ObserverReplayFlagTest` — run to a park, relaunch the instance in
  replay mode (same store), assert the replayed activity/timer/signal
  callbacks carry `replayed=true` and `activityStarted` never fires on
  replay.
- `engine/SignalManagerTest` / `TimerManagerTest` additions — parked/unparked
  callbacks around live parks; none during replay branches.

### 8.2 Meters (Task 4, starter)

- `spring/observe/MicrometerEngineObserverTest` (unit,
  `SimpleMeterRegistry`) — every table row in §2.2: name, type, tags,
  increment on live callback, **no increment when `replayed=true`** (the
  unit-level half of the replay pin).
- `spring/config/MaestroObservabilityAutoConfigurationTest`
  (`ApplicationContextRunner`) — observer bean present with
  `MeterRegistry` bean; absent when `maestro.observability.metrics.enabled=false`;
  absent when no `MeterRegistry` bean; gauges registered against the
  executor; composite handed to the executor contains the observer
  (asserted via a probe workflow run incrementing
  `maestro.workflow.started`). This is the spec's "meters registered and
  incremented through a real engine run" starter context test.
- **The replay-no-double-count pin (spec B1 evidence):**
  `integration/observability/ObserverReplayNoDoubleCountIT` in
  `maestro-integration-tests` (package
  `io.b2mash.maestro.integration.observability`), on the existing
  `MaestroEngineHarness` + Postgres: run a workflow with N activities to a
  park, kill the engine (harness restart, as `ShutdownContractIT` does),
  recover, complete; assert `maestro.activity.duration` count == N exactly,
  `maestro.workflow.started` == 1, `maestro.workflow.completed` == 1, using
  a `SimpleMeterRegistry` wired into the harness's executor via the new
  constructor.

### 8.3 Tracing + propagation (Task 5)

- `maestro-messaging-kafka` `KafkaTracePropagationContractTest` — §4.4:
  header names + W3C grammar pinned against a real broker (Testcontainers
  Kafka, same `KafkaTestSupport` the module already uses); extraction
  restores remote context and `TraceContextHolder`.
- `spring/observe/TracingEngineObserverTest` (unit, `SimpleTracer` from
  `micrometer-tracing-test`) — segment span opens/closes at the §3.2
  boundaries; activity span child-of segment; span events for signal
  consume/timer fire; **zero spans for any `replayed=true` callback** (the
  no-phantom-spans pin); `signalConsumed` with a `traceContext` re-parents
  the segment (remote parent's traceId asserted).
- End-to-end linkage (spec's bounded single assertion):
  `integration/observability/KafkaTraceLinkageIT` — service A publishes a
  signal inside a span; service B (second harness engine) consumes, resumes
  a parked workflow; assert B's segment span traceId == A's publish-span
  traceId. One assertion; the contract tests above carry the detail.

### 8.4 Versioning (Task 6)

Core unit tests (`maestro-core/src/test/.../engine/`):

- `WorkflowVersionTest` —
  (a) new instance: first `version("c", -1, 3)` returns 3 and a
  `VERSION_MARKER` event with payload `{"changeId":"c","version":3}` exists
  at the consumed sequence;
  (b) replay under raised max: re-run same instance with code declaring
  `maxSupported=5` → returns 3 (recorded), no new event;
  (c) pre-marker history: instance whose history has an activity event at
  the peeked slot → returns `DEFAULT_VERSION`, sequence not consumed,
  subsequent replay unshifted;
  (d) min-guard: recorded 1, code `minSupported=2` →
  `UnsupportedWorkflowVersionException`, message contains changeId,
  recorded, and range (message-complete assertion);
  (e) repeated calls, same changeId, one run → same value, exactly one
  marker event.
- `WorkflowVersionParallelBranchTest` — `version()` inside branch *i* of a
  fork at parent seq *p* allocates its marker inside
  `p*1000 + (i+1)*1000 ..` (the spec's parallel-branch allocation test).
- `maestro-test` `DeterminismCheckerVersionMarkerTest` — a workflow calling
  `version()` fingerprints identically across runs (markers are decisions);
  a deliberately version-order-nondeterministic workflow (branch-racing
  resolution) fails the checker.

### 8.5 Stand-down (Task 6)

- Unit, `maestro-store-jdbc`-level via `maestro-store-postgres`'s
  Testcontainers test (`PostgresWorkflowStoreTest` addition or new
  `PostgresUnknownEventMappingTest`): insert a row with
  `event_type = 'EVT_FROM_A_NEWER_MAESTRO'` by SQL; `getEventBySequence`
  returns `EventType.UNKNOWN` without throwing; `appendEvent` with `UNKNOWN`
  throws `IllegalArgumentException`.
- Unit, `maestro-core`:
  - `EventTypeFromStoredNameTest` — total-parse behavior.
  - `engine/UnknownHistoryStandDownTest` — using
    `InMemoryWorkflowStore.injectRawEvent`: a running workflow whose next
    replay read hits an `UNKNOWN` event stands down — status unchanged,
    zero compensations, `observer.standDown(UNKNOWN_EVENT_TYPE, ...)`
    fired, `runningWorkflows` empty afterwards (lock-release path
    exercised).
  - `retry/RetryExecutorControlFlowTest` +
    `saga/SagaManagerControlFlowTest` — catch-ordering: an
    `UnknownWorkflowHistoryException` thrown inside a retried task /
    compensation branch is rethrown, never wrapped into
    `ActivityExecutionException` or recorded as
    `COMPENSATION_STEP_FAILED` (the §6.4 items 7–8 pins).
- **The SQL-injected future-event integration test (spec C2 evidence):**
  `integration/engine/UnknownEventStandDownIT` in
  `maestro-integration-tests`, real Postgres:
  1. run a workflow to a parked state (`WAITING_SIGNAL`);
  2. stop the engine; `INSERT INTO maestro_workflow_event (..., event_type)
     VALUES (..., 'EVT_FROM_A_NEWER_MAESTRO')` at the next sequence via
     JDBC;
  3. restart the engine; recovery adopts → replay hits the row → assert:
     instance status still `WAITING_SIGNAL`, zero `COMPENSATION_*` events,
     instance lock free (a second adopt attempt succeeds), observer
     callback fired (`RecordingEngineObserver` on the harness),
     `maestro.standdown{reason=unknown_event_type}` == number of recovery
     passes observed (churn is visible, not hidden);
  4. `DELETE` the injected row (simulating the upgraded node's world) →
     next recovery pass adopts, the awaited signal is delivered, the
     workflow **completes** — adoptable-and-completable proven.

  On the spec's "SHOULD use `VERSION_MARKER` as the injected type (two
  birds)": **not adopted as the permanent fixture.** Tasks 6's versioning
  and stand-down land in the same release, so `VERSION_MARKER` is a *known*
  type to the very build under test and cannot exercise the unknown path
  once merged. The test uses the dedicated
  `EVT_FROM_A_NEWER_MAESTRO` constant; a transient RED-phase demonstration
  against the pre-Task-6 tree may use `'VERSION_MARKER'` and must be
  recorded in the task report as evidence. Flagged as OPEN-Q-1 for the
  coordinator since it reads against a spec SHOULD.

### 8.6 Signal trace-context column (Tasks 5)

- `PostgresWorkflowStoreTest` addition — `saveSignal`/`mapSignal` round-trip
  `traceContext`; `V4` migration applies on a V3 database
  (`MaestroMigrationsCoexistIT` already exercises coexistence — extend).
- `SignalManagerTest` addition — `deliverSignal` persists
  `TraceContextHolder.current()`; `consumeSignal` surfaces it on
  `SignalInfo`; holder cleared after the listener scope.

---

## 9. Open questions for coordinator ruling

- **OPEN-Q-1** (§8.5): the spec SHOULD-clause "use `VERSION_MARKER` as the
  injected future type where practical" is unimplementable as a permanent
  fixture once versioning and stand-down ship in the same build (the type
  is then known). Proposed resolution: dedicated
  `EVT_FROM_A_NEWER_MAESTRO` string as the permanent fixture; optional
  transient RED-phase run with `'VERSION_MARKER'` against the pre-Task-6
  tree captured as evidence. Ruling requested.
- **OPEN-Q-2** (§4.3): propagating trace context durably requires one new
  nullable column on `maestro_workflow_signal` (`trace_context
  VARCHAR(128)`, migration V4) and an added component on the
  `WorkflowSignal` record. This is the only schema change in the cycle and
  the alternative (in-process-only propagation) fails the spec's
  "resumed segment's span has the remote parent" evidence whenever consume
  and resume are on different threads/nodes — i.e. almost always. Confirm
  the schema change is accepted.
- **OPEN-Q-3** (§6.2): re-parenting `ExecutorShutdownException` and
  `WorkflowTerminatedException` under the new sealed
  `MaestroControlFlowError` base touches two shipped exception types
  (still `Error` subtypes; no catch-site behavior changes). Confirm this
  refactor is in scope for Task 6, or the fallback (three-type instanceof
  enumeration at §6.4 sites 7–8) applies.

Everything else in this document is decided.

---

## 10. Coordinator review — APPROVED (with rulings)

Reviewed 2026-08-02 against spec §§4–6 and the plan's Global Constraints.
All eight required sections land on implementable decisions; grounding
(§ "Files read" in the task report) is genuine. The following rulings
resolve §9's open questions and BIND Tasks 3–7:

**RULING 1 (OPEN-Q-1) — APPROVED as proposed.** The permanent stand-down
integration fixture uses the dedicated unknown string
`EVT_FROM_A_NEWER_MAESTRO` (a type no build of this repo will ever define).
The spec §C2's SHOULD-clause about using `VERSION_MARKER` is satisfied by
transient RED-phase evidence only if Task 7's implementer finds it cheap
(inject VERSION_MARKER against a pre-Task-6 commit's binary); if not, skip
it — the SHOULD is discharged by the ruling, and the permanent fixture is
the contract.

**RULING 2 (OPEN-Q-2) — APPROVED.** The signal-row `trace_context` column
(nullable, opaque W3C traceparent string; Flyway V4; `WorkflowSignal`
record component; both stores + in-memory) is in scope for Task 5. Durable
remote-parent restoration is what distinguishes "tracing that survives
durability" from a demo, which is the product's identity. Constraint: the
column is opaque metadata — no store logic may parse or branch on it, and
absence degrades to a fresh root span, never an error.

**RULING 3 (OPEN-Q-3) — APPROVED.** Re-parent `ExecutorShutdownException`
and `WorkflowTerminatedException` under sealed `MaestroControlFlowError`
(extends `Error`) in Task 7, behavior-preserving; broad-catch sites may
then catch the base. Task 8 MUST update the CLAUDE.md exceptions section
and any doc that enumerates the two types to describe the sealed base and
its three permitted subtypes.

No other section requires amendment. Tasks 3–7 implement this document
exactly; deviations require a new coordinator ruling recorded here.

---

**RULING 4 (amends §1.2) — BINDING.** `CompositeEngineObserver.of()` must NOT
collapse to the bare delegate at size 1. It always returns a containing
wrapper, so per-delegate `RuntimeException` containment is structural at every
emission site — present and future — instead of depending on which call sites
someone remembered to harden. Rationale: one observer is the common deployment
(a lone Micrometer adapter in Task 4, a lone tracing adapter in Task 5), which
is exactly the case that currently has zero containment; and a third-party
adapter throwing must never be able to corrupt engine control flow. The cost is
one virtual call per emission on paths that already do database I/O —
irrelevant. `Error` still propagates uncontained (deliberate, unchanged: the
engine's control-flow signals are Errors and swallowing them would reinstate
the bug they exist to prevent).

Raised by Task 3's fix round 1, where three separate emission sites
(`WorkflowInstanceLockManager.tryAcquire`/`renewOne`, `WorkflowExecutor`'s
lifecycle emissions, `SagaManager.compensate`) each had to be hand-hardened
against a throwing adapter — a leaked instance lock reported as `NO_BACKEND`, a
dead lock-renewer thread, compensations run for a workflow that succeeded.
Those per-site guards remain as depth (the engine constructors accept any
`EngineObserver`, so nothing forces a hand-wiring embedder through `of(...)`),
but the seam is now correct by construction. §1.2's code block above is
amended accordingly: `case 1 -> observers.getFirst();` is REMOVED — no later
task may implement the collapse.

## 11. Coordinator rulings — Task 5 (tracing + Kafka propagation)

Reviewed 2026-08-02 against the Task 5 report's three flagged deviations and
its post-implementation FINDING-1. All four are ruled below and BIND the
remaining tasks.

**RULING 5 (FINDING-1) — APPROVED as the implementer recommended.** A
terminate landing between a run's last live step and its next park leaves the
`maestro.workflow.run` segment span unclosed and unexported. Fix: add
`EngineObserver.runAbandoned(WorkflowInfo w, AbandonReason reason)` —
`AbandonReason` = `{SHUTDOWN, TERMINATED}` — emitted from BOTH
`WorkflowExecutor.handleShutdownSuspension` and
`WorkflowExecutor.handleTermination`. It is deliberately DISTINCT from
`standDown`: routing an operator terminate through the stand-down counter
would recreate exactly the "routine operation recorded as a failure-shaped
event" confusion that the engine's control-flow-signal design exists to
prevent, and would corrupt Task 4's `maestro.standdown{reason}` meter.
`TracingEngineObserver` closes its segment there; `MicrometerEngineObserver`
does NOT implement it (no new meter, no double-count — `workflowTerminated`
already fires exactly once, on the operator thread at
`WorkflowExecutor:759`). The addition is purely additive: every
`EngineObserver` method is a `default` no-op, so Tasks 3 and 4 need no
change. Task 5 implements this; Task 3's in-line comment at
`SignalManager:317-319` ("a shutdown or terminate abandons the run and emits
neither") must be updated to match.

**RULING 6 (DEVIATION-1, lazy segment opening) — APPROVED.** Design §3.2
assumed `workflowStarted`/`workflowResumed` run on the workflow's virtual
thread; they factually run on the launching/caller thread
(`WorkflowExecutor:439` and `:1391`, thread created at `:1366`). Implementing
§3.2 literally would open an unclosed scope on an unrelated caller thread
(corrupting that thread's tracing) and leave every activity span a detached
root. Segments therefore open lazily on the first callback that genuinely
runs on the workflow thread. §3.2's table is amended accordingly. The
rejected follow-up (capturing the caller's context to parent a locally
started workflow's first segment) is recorded as a POST-1.0 idea, not this
cycle's work — it is unbounded state for a link §3.2 never required.

**RULING 7 (DEVIATION-2, remote parent outranks the local park chain) —
APPROVED, and this is the product-correct reading.** §3.2's literal priority
(local previous segment, else remote) makes the cycle's headline requirement
— one connected trace across services — unreachable in the common case,
because the normal cross-service flow parks and resumes live on the same
thread and would never join the publisher's trace. §4.3 already contemplated
remote re-parenting with a local segment present; the tension is resolved
toward §4.3 and toward the spec's evidence requirement. A live
`signalConsumed` carrying a usable trace context re-parents to the remote
context and attaches the previous local segment as a link, so no local
chaining is lost. §3.2's parent-priority text is amended.

**RULING 8 (DEVIATION-3, span-event attributes become span tags) —
APPROVED (API-forced).** Micrometer Tracing's `Span` exposes only
`event(String)` / `event(String, long, TimeUnit)`; there is no attributed-event
API. Event names are recorded exactly as designed; the intended event
attributes (`maestro.timer.id`, `maestro.signal.name`) become span tags,
last-write-wins within a segment. **Task 8 MUST document this in
`docs/observability.md`** — an operator reading a trace needs to know these
are segment tags with last-write-wins semantics, not per-event attributes.
Test-scope-only catalog additions (`spring-boot-micrometer-tracing`,
`spring-boot-micrometer-tracing-opentelemetry`) are approved; no new
production dependency, `maestro-core` unchanged.
