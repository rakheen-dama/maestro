# Observability

Micrometer meters, OpenTelemetry tracing, and the cross-service trace
propagation contract.

[← Back to README](../README.md)

---

## Overview

Maestro instruments itself through a Spring-free seam in `maestro-core` —
`io.b2mash.maestro.core.observe.EngineObserver` — which the engine calls at
execution boundaries. `maestro-core` gains no dependency on Micrometer,
OpenTelemetry, or Spring; every adapter lives in
`maestro-spring-boot-starter` (`io.b2mash.maestro.spring.observe`) and in
`maestro-messaging-kafka`.

Two adapters ship:

| Adapter | Class | Produces |
|---|---|---|
| Meters | `MicrometerEngineObserver` + `MaestroEngineGauges` | Micrometer counters, timers, gauges under `maestro.*` |
| Tracing | `TracingEngineObserver` | Spans via the Micrometer Tracing API (`io.micrometer.tracing.Tracer`) |

Both are registered by `MaestroObservabilityAutoConfiguration`, and both are
inert unless the corresponding beans exist. Nothing you have to opt into: if
your application already has a `MeterRegistry` or a `Tracer`, Maestro's meters
and spans appear.

### The replay invariant

A recovered workflow re-runs its workflow method from the top, replaying every
completed step from the event log. **Replayed steps are never counted and
never traced.** Every `EngineObserver` callback that can fire during replay
carries a `replayed` flag, and both shipped adapters return immediately when it
is `true`:

- `activityCompleted(ActivityInfo, Duration, boolean replayed)`
- `activityFailed(ActivityInfo, Duration, String exceptionType, boolean replayed)`
- `signalConsumed(SignalInfo, boolean replayed)`
- `timerFired(TimerInfo, boolean replayed)`
- `timerScheduled(...)` / `timerCancelled(...)` carry the flag too

A workflow that crashes after three activities and is recovered elsewhere still
shows `maestro.activity.duration` count `1` per step and
`maestro.workflow.started` count `1` — a recovered run emits `workflowResumed`,
not `workflowStarted`, and `MicrometerEngineObserver` does not implement
`workflowResumed`. Pinned end-to-end over a real Postgres by
`ObserverReplayNoDoubleCountIT` (crash node A, recover on node B, assert the
replayed step's timer count stays 1) and, for spans, by
`TracingReplayNoSpansIT`.

### Failure containment

Both adapters wrap each emission: a `RuntimeException` thrown by the registry
or the tracer is caught and logged **once per meter name / callback name**,
never propagated into the engine. `Error`s — the engine's control-flow signals
(`MaestroControlFlowError`) — deliberately propagate, because swallowing one
would convert a stand-down or a terminate into a workflow failure.

Observers are composed through `CompositeEngineObserver.of(...)`, which always
wraps even a single observer, so containment is structural at every emission
site.

---

## Meter catalog

Fourteen meters: **11 counters and 1 timer**, registered by
`MicrometerEngineObserver`, plus **2 gauges**, registered by
`MaestroEngineGauges` — both in the `io.b2mash.maestro.spring.observe` package
of `maestro-spring-boot-starter`.

**Cardinality rule:** no meter is ever tagged with `workflowId`, `runId`, or a
timer ID. `workflowFailed`'s exception type is deliberately *not* a tag either
— it is open-ended cardinality; it appears as a span attribute instead
(`maestro.error.type`).

### Counters

| Meter | Tags | Tag values | Emitted when |
|---|---|---|---|
| `maestro.workflow.started` | `workflow` | workflow type | A workflow run is launched live (not on recovery) |
| `maestro.workflow.completed` | `workflow` | workflow type | A run finishes successfully |
| `maestro.workflow.failed` | `workflow` | workflow type | A run is recorded `FAILED` |
| `maestro.workflow.compensated` | `workflow` | workflow type | The saga compensation phase starts (callback is `workflowCompensating`) |
| `maestro.workflow.terminated` | `workflow` | workflow type | An operator terminated the workflow |
| `maestro.signal.consumed` | `workflow`, `signal` | workflow type (or the literal `unknown` when the workflow type is not yet known), signal name | A signal is consumed live |
| `maestro.timer.fired` | `workflow` | workflow type | A durable timer fires live |
| `maestro.recovery.scanned` | *(none)* | — | Incremented by the number of instances a recovery pass scanned |
| `maestro.recovery.adopted` | *(none)* | — | Incremented by the number of instances that pass adopted |
| `maestro.lock.renew.failures` | `outcome` | `error`, `lost` | Instance-lock renewal errored (`error`) or the lock was found gone (`lost`) |
| `maestro.standdown` | `reason` | `unknown_event_type`, `unknown_event_payload`, `stale_run` | A local run stood down without recording a workflow outcome |

`maestro.recovery.scanned` and `maestro.recovery.adopted` are `increment(n)`
calls, not `increment()` — one recovery pass moves them by the pass's counts.

The `reason` tag value set is closed at three, mapped from
`io.b2mash.maestro.core.observe.StandDownReason`:

| `reason` | Meaning |
|---|---|
| `unknown_event_type` | A persisted `event_type` string this build's `EventType` enum does not define — written by a **newer** node |
| `unknown_event_payload` | A stored payload this build could not deserialize while replaying |
| `stale_run` | Issue 18 — an event append collided with a concurrent runner's history |

See [Operations §10](operations.md#10-versioning-and-mixed-version-deploys) for
what a rising `maestro.standdown` means and how to act on each reason. The two
unknown-history reasons are **not** interchangeable: `unknown_event_type` really
can only come from a newer build, but `unknown_event_payload` can also mean an
incompatible payload change on a homogeneous fleet.

### Timers

| Meter | Tags | Tag values |
|---|---|---|
| `maestro.activity.duration` | `workflow`, `activity`, `outcome` | workflow type, activity name, `completed` or `failed` |

`outcome` has exactly two values. Replayed activities are excluded, so this
timer measures real executions only.

### Gauges

| Meter | Tags | Source |
|---|---|---|
| `maestro.workflows.running` | *(none)* | `WorkflowExecutor.runningCount()` |
| `maestro.workflows.parked` | *(none)* | `WorkflowExecutor.parkedCount()` |

Both are registered by `MaestroEngineGauges` with `.strongReference(true)` —
Micrometer's `Gauge.Builder` holds its state object behind a `WeakReference` by
default, which would report `NaN` once the builder went out of scope.

**How they are sourced, and what a value means:**

- `maestro.workflows.running` is the size of `WorkflowExecutor`'s
  `runningWorkflows` map, keyed by `workflowId` — one entry per in-flight
  workflow virtual thread **on this JVM**. The entry is added *before*
  `thread.start()` and removed in the run's `finally`, before the instance lock
  is released.
- `maestro.workflows.parked` is the size of `ParkingLot`'s `futures` map, keyed
  by **parking key** — one entry per active park (a `sleep()` on a durable
  timer, or an `awaitSignal()`). Entries are removed on unpark, timeout,
  interrupt, or exception.

**These are node-local, in-JVM values.** They are not store-derived, they do not
survive a restart, and they say nothing about workflows owned by other nodes.
That is deliberate: polling the store for a cluster-wide `COUNT(*)` on every
scrape would load the database from every node, report identical numbers from
every node, and couple scrape latency to store health. Dashboard them per pod
and **sum across pods** for a cluster total. A workflow that is durably parked
but not adopted by any node contributes `0` to every node's gauge — that is
correct: no JVM is holding it.

### Which callbacks the meter adapter implements

`MicrometerEngineObserver` implements 13 of the 22 `EngineObserver` callbacks.
It deliberately does **not** implement:

- `workflowResumed`, `workflowParked`, `workflowUnparked`, `activityStarted`,
  `signalPersisted`, `timerScheduled`, `timerCancelled`,
  `instanceLockAcquired` — no meter in the catalog needs them.
- **`runAbandoned`** — this fires when a run stops because the node is shutting
  down, an operator terminated the workflow, or another writer finalised the row
  first. Counting it would double-count: `workflowTerminated` already fires
  exactly once per terminate. It exists for the tracing adapter, which needs a
  callback on the workflow's own thread to close its span.

Every `EngineObserver` method is a `default` no-op, so a custom observer
implements only what it cares about.

---

## Tracing

`TracingEngineObserver` builds spans through the **Micrometer Tracing API**
(`io.micrometer.tracing.Tracer` / `Propagator`), which bridges to OpenTelemetry
or Brave depending on which bridge your application ships. Maestro does not
depend on the OpenTelemetry SDK directly.

### Span topology

Two span names, both literal constants — nothing dynamic is composed into a
span name:

| Span | Name | Scope |
|---|---|---|
| Run segment | `maestro.workflow.run` | One per **run segment**: the stretch of a run between parks |
| Activity | `maestro.activity` | One per live activity execution |

A third span name is produced on the Kafka side:
`maestro.signal.receive` (`Span.Kind.CONSUMER`), covering the listener's
handling of one inbound record.

**Segments open lazily.** `workflowStarted` and `workflowResumed` create no
span at all — they run on the *launching* (or recovery) thread, not on the
workflow's virtual thread, so opening a scope there would corrupt an unrelated
thread's tracing. A segment opens on the first callback that genuinely runs on
the workflow thread: `activityStarted`, `workflowUnparked`, `signalConsumed`,
or a timer event. `ensureSegment` is idempotent, so the engine's two callback
orderings both work (`SignalManager` emits `signalConsumed` *before*
`workflowUnparked`; `DefaultWorkflowOperations` emits `timerFired` *after* it).

**Segments close** on `workflowParked`, `workflowCompleted`, `workflowFailed`,
`workflowTerminated`, `standDown`, and `runAbandoned`. `runAbandoned` is what
keeps a segment from leaking when a run unwinds through a shutdown, a terminate,
or a lost terminal transition rather than through an ordinary park.

**Activity spans** are children of the open segment. Inside a `parallel()`
branch, where no segment exists (see [Known limitations](#known-limitations)),
they are parented to the fork point instead, so they still land in the same
trace.

A workflow that runs, parks, resumes, and completes therefore renders as one
trace containing a chain of `maestro.workflow.run` segments, each with its
activity spans nested underneath.

### Span attributes

| Attribute | On | Value |
|---|---|---|
| `maestro.workflow.id` | segment, activity | The business workflow ID |
| `maestro.workflow.type` | segment, activity | The workflow type name |
| `maestro.run.id` | segment, activity | From MDC, when present |
| `maestro.service.name` | segment | The owning service's `maestro.service-name` |
| `maestro.activity.name` | activity | The activity name |
| `maestro.sequence` | activity | The memoization sequence number (a **numeric** tag) |
| `maestro.signal.name` | segment | See [span events](#span-events-and-their-attributes-ruling-8) below |
| `maestro.timer.id` | segment | See [span events](#span-events-and-their-attributes-ruling-8) below |
| `maestro.error.type` | activity, segment | Exception type on `activityFailed` / `workflowFailed` |
| `maestro.standdown.reason` | segment | The `StandDownReason` constant name |
| `maestro.abandon.reason` | segment | The `AbandonReason` constant name (`SHUTDOWN`, `TERMINATED`, `CONVERGED`) |

The three MDC keys Maestro logs with (`workflowId`, `runId`, `activityName`)
appear here as `maestro.workflow.id`, `maestro.run.id`, and
`maestro.activity.name`, so a log line and a span can be joined.

### Span events, and their attributes (RULING 8)

Four span events are recorded, all of them **on the open run segment**, never on
an activity span:

| Event | Recorded when |
|---|---|
| `maestro.signal.consumed` | A signal is consumed live |
| `maestro.signal.persisted` | A signal is persisted — recorded only if a segment happens to be open, and it never opens one (a delivery thread is not a run segment) |
| `maestro.timer.fired` | A durable timer fires live |
| `maestro.timer.cancelled` | A timer is cancelled live |

**Read this before you read a trace.** Micrometer Tracing's `Span` exposes only
`event(String)` and `event(String, long, TimeUnit)` — there is **no attributed-event
API**. The attributes these events were designed to carry —
`maestro.timer.id` and `maestro.signal.name` — are therefore recorded as **span
tags on the segment**, with **last-write-wins within that segment**.

Concretely: if one run segment consumes two signals, the segment carries a
single `maestro.signal.name` tag holding the **last** signal's name, while both
`maestro.signal.consumed` events appear on the timeline with their own
timestamps. The same applies to `maestro.timer.id` across multiple timer events
in one segment. Do not read the tag as "the signal this event was about" — read
the event timeline for ordering and the tag as the most recent value. Segments
are usually short (they end at the next park), so in practice a segment most
often carries exactly one, but the multi-event case is real and this is the
semantics it has.

Branch-level events are dropped entirely — see
[Known limitations](#known-limitations).

---

## Cross-service trace propagation (Kafka)

A cross-service flow — service A publishes a signal, service B's listener
consumes it, B's parked workflow resumes and runs activities — renders as **one
connected trace**. The contract is pinned by tests, not inherited from transport
defaults.

> ### Scope limit — read this before relying on it
>
> **That promise holds for the `maestro.tasks.*` and `maestro.signals.*` topics
> the engine itself owns. It does not hold for your own domain topics**, and
> most cross-service applications route their business events over their own
> topics.
>
> `KafkaTracePropagation` is wired into `KafkaWorkflowMessaging`, which only
> handles the engine's topics. If your service publishes with an injected
> `KafkaTemplate` and consumes with `@KafkaListener` — or with
> `@MaestroSignalListener` on a topic of your own — nothing injects
> `traceparent` for you today, and each service opens a **fresh root trace**.
> The symptom is deceptive: every service reports to your tracing backend and
> shows plenty of traces, but no trace ever spans more than one service.
>
> Two separate causes, both tracked as
> [Issue 23](open-issues.md#issue-23):
>
> 1. **Producer.** The obvious lever,
>    `spring.kafka.template.observation-enabled`, is **inert in every Maestro +
>    Kafka application**. Boot's `kafkaTemplate` bean is
>    `@ConditionalOnMissingBean(KafkaTemplate.class)`, and Maestro's
>    `maestroKafkaTemplate` shadows it by type, so the property binds and does
>    nothing. The same is true of `spring.kafka.producer.*`.
> 2. **Consumer.** `@MaestroSignalListener` hand-builds its container and passes
>    only the record's value to the handler, so an inbound `traceparent` is
>    never extracted and the signal row is persisted with `trace_context = NULL`
>    — even on the engine's own topics.
>
> **What to do today.** Define your own bean *named* `maestroKafkaTemplate`
> with observation enabled; Maestro's is
> `@ConditionalOnMissingBean(name = "maestroKafkaTemplate")`, so yours wins and
> engine and application traffic share one observed template:
>
> ```java
> @Bean
> public KafkaTemplate<String, byte[]> maestroKafkaTemplate(
>         ProducerFactory<String, byte[]> maestroKafkaProducerFactory) {
>     var template = new KafkaTemplate<>(maestroKafkaProducerFactory);
>     template.setObservationEnabled(true);
>     return template;
> }
> ```
>
> Also set `spring.kafka.listener.observation-enabled=true` so plain
> `@KafkaListener` consumers continue the trace rather than starting a new one.
> Worked example:
> `maestro-samples/sample-loan-origination/*/src/main/java/.../config/ObservedKafkaTemplateConfig.java`.
> This fixes the producer side; cause 2 has no user-side workaround, so
> `trace_context` stays NULL until Issue 23 is fixed.
>
> For how services are meant to be composed in the first place, see
> [cross-service.md](cross-service.md).

### Wire contract

`KafkaTracePropagation` (in `maestro-messaging-kafka`) injects the active span's
context into Kafka record headers on publish and extracts it on consume. Header
names are lowercase ASCII, UTF-8 values:

| Header | Grammar | Present when |
|---|---|---|
| `traceparent` | `00-{32 hex trace-id}-{16 hex span-id}-{2 hex flags}` | A span is active at publish time |
| `tracestate` | W3C tracestate | The propagator emits a non-empty value |
| `baggage` | W3C baggage | The propagator emits it |

Only `traceparent` is a Maestro constant; `tracestate` and `baggage` are
whatever the configured `Propagator` writes under Boot's default
`management.tracing.propagation.type=W3C`. All three are pinned as the allowed
header set by `KafkaTracePropagationContractTest`, deliberately — if a future
Spring Boot default changed the propagation type, the cross-service contract
would otherwise break silently for every deployment that upgraded one service
before the other.

The exact grammar Maestro validates against, identical in the transport and in
the engine adapter:

```
^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$
```

A grammar-valid `traceparent` is exactly 55 characters. Only version `00` is
accepted; anything else is treated as absent.

**With no active span at publish time, nothing is written.** A build with no
tracing beans produces a byte-identical wire format to a pre-tracing build.

### Surviving a durable park

The cross-service hop is not just in-memory. When a signal arrives for a workflow
that is parked — possibly on another node, possibly not yet running at all — the
inbound `traceparent` is persisted **on the signal row** so the resumed segment
can still attach to the publisher's trace:

- Column `maestro_workflow_signal.trace_context`, `VARCHAR(128)`, **nullable**
- Added by `V4__signal_trace_context.sql` (metadata-only `ALTER` — no `DEFAULT`,
  no `NOT NULL`, so it is instant on a live table)
- Opaque: no store or engine logic parses it, branches on it, indexes it, or
  joins on it. It is written once and read back verbatim.
- 128 characters is headroom over the 55 a `traceparent` needs.

The listener thread hands the value across the `WorkflowMessaging` SPI boundary
through `io.b2mash.maestro.core.observe.TraceContextHolder`, a `ThreadLocal`
holding an opaque `String`. Use `TraceContextHolder.runWith(value, action)`
rather than a bare `set` — a `set` without a matching `clear` leaks the value
onto a pooled listener thread, where the *next* unrelated signal would inherit
it.

### Degradation is always safe

Absence or malformation never becomes an error, and never costs a signal:

- **No header, or a `NULL` `trace_context`** → the resumed segment starts as a
  fresh root span.
- **Malformed or non-`00`-version `traceparent`** → rejected at extraction, the
  signal is delivered untraced.
- **Over-long value** → rejected. This one matters: an unvalidated header
  persisted into `VARCHAR(128)` would fail `saveSignal`, and because
  `deliverSignal` runs inside the Kafka listener the record would never be
  acked, redelivery would exhaust its budget, and the signal would be
  **dead-lettered** — a discarded signal, which the engine's invariants forbid.
  There are two guards: grammar validation at extraction
  (`KafkaTracePropagation.extractTraceparent`), and a defensive length cap at
  the sole persistence site (`SignalManager.deliverSignal`, against
  `TraceContextHolder.MAX_LENGTH = 128`) so no future transport can reintroduce
  the path. Both are pinned, including at the 128-character boundary and against
  a real Postgres column.

### Parenting: remote wins, local is linked

When a live `signalConsumed` carries a usable durable trace context, the segment
is parented to the **remote** context, and the previous local segment (the
park→unpark chain) is attached as a **link** rather than dropped. This is
unconditional — it applies even when the workflow had already parked and resumed
once, so its open segment is not a root.

Parent priority, in order:

1. Remote context from the signal's `trace_context` (previous local segment
   becomes a link)
2. The previous local segment (the park→unpark chain)
3. No parent — a new root

End-to-end linkage is pinned by `KafkaTraceLinkageIT` ("a signal published by
service A and consumed by service B yields one connected trace").

---

## Configuration

| Property | Type | Default | Description |
|---|---|---|---|
| `maestro.observability.metrics.enabled` | `boolean` | `true` | Register and emit Micrometer meters. |
| `maestro.observability.tracing.enabled` | `boolean` | `true` | Create spans and propagate W3C trace context through Kafka headers. |

Both default to on and both are silently inert without the beans they need, so
neither has to be set for an application that is not instrumented.

**Conditions actually applied** (`MaestroObservabilityAutoConfiguration`):

| Feature | Requires |
|---|---|
| Meters | `maestro.enabled` ≠ `false`; `maestro.observability.metrics.enabled` ≠ `false`; `io.micrometer.core.instrument.MeterRegistry` on the classpath **and** a `MeterRegistry` bean. The gauges additionally require a `WorkflowExecutor` bean. |
| Tracing (engine spans) | `maestro.enabled` ≠ `false`; `maestro.observability.tracing.enabled` ≠ `false`; `io.micrometer.tracing.Tracer` on the classpath **and** both a `Tracer` and a `Propagator` bean. |
| Tracing (Kafka propagation) | The same property and the same `Tracer` + `Propagator` beans, gating `KafkaTracePropagation` in `maestro-messaging-kafka`. Without the bean, the Kafka wire format is exactly the pre-tracing one. |

Setting `maestro.observability.tracing.enabled: false` disables **both** the
engine spans and the Kafka header injection — the same property gates both
configurations.

```yaml
maestro:
  service-name: order-service
  observability:
    metrics:
      enabled: true
    tracing:
      enabled: true
```

Maestro's auto-configuration declares itself *after* Spring Boot's metrics and
tracing auto-configurations by name. This is load-bearing, not decorative:
`io.b2mash.maestro.spring.observe` sorts alphabetically before
`org.springframework.boot.micrometer.*`, and without the explicit ordering the
`@ConditionalOnBean` checks would run before Boot had created the
`MeterRegistry` / `Tracer` — the whole feature would ship **inert**, with zero
`maestro.*` meters and no spans, silently. Pinned by
`MaestroObservabilityAutoConfigurationTest`, which wires through the real Boot
auto-configuration chain rather than a stub bean.

---

## Known limitations

These are real, they are known, and they are documented here rather than left to
be discovered in a trace.

### 1. The fork/join observation boundary

The engine has no callback marking the start and end of a `parallel()` branch,
so the tracing adapter has to *infer* which threads are branches. It classifies
a thread in this order:

1. **Inherited a fork point** (the `InheritableThreadLocal` fork point exists but
   belongs to another thread) → definitively a branch.
2. **Owns the fork point** → definitively the main run thread, and it stays that
   way across joins and later parks.
3. **Neither** — nothing observable has happened on this thread yet — falls back
   to a sequence-number latch (branch *i* of a fork at parent sequence *p*
   allocates from `p*1000 + (i+1)*1000`, so a sequence ≥ 1000 suggests a
   branch).

A branch thread never opens a segment: nothing tells the adapter a branch has
finished, so a segment opened there could never be closed or exported. Its
activity spans hang off the inherited fork point instead, landing in the same
trace.

**The gap is case 3.** One shape remains ambiguous, and it is wider than "a
workflow whose first statement is `parallel()`": any recovered run whose first
live step after replay sits past a join hits the identical case, because
`DefaultWorkflowOperations` returns silently for a replayed completed sleep, so
a thread resuming past a join opens no fork point either — the same structural
gap a fork-first branch has. In both shapes the thread owns no fork point (it
never opened a segment before forking, or replay never opened one for it) and
its post-join activities sit at seq ≥ 2000, so case 3 latches it as a branch and
it opens no segments — its activity spans become roots and its span events are
dropped. Nothing is orphaned and no span leaks; spans still export — but **each
such activity exports as its own root span, so the run fragments into one trace
per activity** instead of staying in one.

Closing this needs a real fork/join observation boundary in the engine, which is
post-1.0 work: no fact available to the adapter distinguishes that thread from a
branch.

### 2. A workflow started from a workflow thread gets no segment of its own

The fork point is an `InheritableThreadLocal`, so **any** thread created by a
workflow thread is treated as a parallel branch. An embedder child-workflow
pattern — starting a workflow from inside another workflow's thread — would
therefore give the child run no run segment, and its activity spans would be
parented under the *parent* workflow's segment. No in-tree code does this; it is
recorded so the behaviour is documented rather than discovered.

### 3. The first recovered segment on startup can lack `maestro.service.name`

`serviceName` is a node-wide `volatile` on the observer, populated from the
first `WorkflowInfo`-bearing callback. `workflowResumed` is emitted *after*
`thread.start()`, so on a fresh JVM the first recovered run can open its segment
before the value is populated — and the tag is never backfilled onto an
already-open segment. That one segment lacks `maestro.service.name`; every later
segment carries it.

### 4. Branch-level span events are dropped

Because a branch never holds a segment, `maestro.signal.consumed`,
`maestro.timer.fired`, and `maestro.timer.cancelled` raised **inside a parallel
branch** have nowhere to be recorded and are skipped. Branch activity spans and
their parenting are unaffected.

---

## Writing your own observer

`EngineObserver` is a plain interface in `maestro-core` with 22 `default` no-op
methods — implement only what you need, and register the bean; the starter picks
up every `EngineObserver` in the context.

The callback surface, grouped:

| Group | Callbacks |
|---|---|
| Workflow lifecycle | `workflowStarted`, `workflowResumed`, `workflowCompleted`, `workflowFailed(w, exceptionType)`, `workflowCompensating`, `workflowTerminated` |
| Parking | `workflowParked(w, ParkKind)`, `workflowUnparked(w, ParkKind)` — `ParkKind` is `SIGNAL` or `TIMER` |
| Activities | `activityStarted`, `activityCompleted(a, duration, replayed)`, `activityFailed(a, duration, exceptionType, replayed)` |
| Signals | `signalPersisted`, `signalConsumed(s, replayed)` |
| Timers | `timerScheduled(t, replayed)`, `timerFired(t, replayed)`, `timerCancelled(t, replayed)` |
| Instance lock | `instanceLockAcquired`, `instanceLockRenewFailed`, `instanceLockLost` |
| Recovery | `recoveryPass(scanned, adopted)` |
| Stand-down | `standDown(StandDownReason, workflowId, detail)` |
| Run abandonment | `runAbandoned(w, AbandonReason)` — `SHUTDOWN`, `TERMINATED`, `CONVERGED` |

Rules for implementers:

- **Honour the `replayed` flag** if you count or trace, or a recovered workflow
  will double-count. (Audit observers may legitimately want replay traffic.)
- **Never tag by `workflowId` or `runId`** in a metric.
- **Never let an exception escape a callback**, and never swallow a
  `MaestroControlFlowError` — check for it and rethrow before recording anything
  as a failure.
- Callbacks run on engine threads, including workflow virtual threads. Keep them
  cheap and non-blocking; do no I/O.

---

## See also

- `docs/configuration.md` — the full `maestro.*` property reference.
- `docs/operations.md` §10 — the versioning and mixed-version playbook, and what
  to do about a rising `maestro.standdown`.
- `docs/concepts.md` — `workflow.version()` and the determinism rules.
- `docs/admin.md` — the admin dashboard, which consumes lifecycle *events*
  (a separate, store-backed channel from these meters and spans).
