# Task 5 Report — OpenTelemetry tracing + cross-service trace propagation through Kafka

**Status: COMPLETE**

pwd: `/Users/rakheendama/Projects/2026/maestro/.claude/worktrees/release-hardening`
branch: `worktree-release-hardening`
HEAD at start: `4c0b8528554bcdb8ab0d9c5816e4c06c5431b1fc`

Commits (oldest first):

| Commit | Content |
|---|---|
| `533932b` | core plumbing: `TraceContextHolder`, `WorkflowSignal.traceContext`, `SignalManager` capture/surface, Flyway V4, JDBC + in-memory stores |
| `0962c20` | evidence: core plumbing RED/GREEN logs |
| `0ec0688` | `TracingEngineObserver` + `MaestroObservabilityAutoConfiguration.TracingConfiguration` |
| `42eb347` | evidence: tracing observer + auto-config RED/GREEN logs |
| `417fee8` | `KafkaTracePropagation`, `KafkaWorkflowMessaging` inject/extract, Kafka auto-config bean |
| `c548264` | evidence: Kafka propagation RED/GREEN logs |
| `5c2bf37` | integration pins: `TracingReplayNoSpansIT`, `KafkaTraceLinkageIT` |
| `21029a1` | evidence: integration RED/GREEN logs |
| `d486758` | `MaestroMigrationsCoexistIT` V4 column pin (design §8.6) |
| `5a59d5d` | evidence: migration pin + required verification-command logs |

Evidence (force-added; `.superpowers/sdd/.gitignore` ignores the directory), all
with pwd / branch / HEAD / timestamp / command identity headers:

```
.superpowers/sdd/release-hardening/evidence/
  task-5-red-core.log              task-5-green-core.log
  task-5-red-store.log             task-5-green-starter.log
  task-5-red-tracing.log           task-5-green-tracing.log
  task-5-red-autoconfig.log        task-5-green-autoconfig.log
  task-5-red-autoconfig-ordering.log
  task-5-red-kafka.log             task-5-green-kafka.log
  task-5-red-kafka-autoconfig.log
  task-5-red-linkage.log           task-5-green-integration.log
  task-5-red-replay-it.log         task-5-green-migration.log
  task-5-verify.log                task-5-build.log  task-5-build-full.log
  run.sh   (the archiver that writes the identity headers)
```

---

## What was read, in order

1. `.superpowers/sdd/release-hardening-plan/task-5-brief.md`
2. `.superpowers/sdd/release-hardening/observability-versioning-design.md` §3
   (tracing approach + span topology), §4 (Kafka propagation contract), §7
   (config seams, `libs.versions.toml`), §8.3/§8.6 (pins), §10 (RULINGs 2 and 4),
   plus §1 for the `EngineObserver` surface.
3. `task-3-report.md` (handoff + both fix rounds) and `task-4-report.md`
   (the `afterName` ordering lesson, F1 in particular).
4. Source: `EngineObserver` + its argument records, `SignalManager` (all three
   constructors, `deliverSignal`, `awaitSignal`, `consumeSignal`),
   `WorkflowExecutor.startWorkflow`/`launchWorkflow` (where `workflowStarted` /
   `workflowResumed` are actually emitted — see DEVIATION-1),
   `WorkflowSignal`, `AbstractJdbcWorkflowStore`'s signal SQL,
   `InMemoryWorkflowStore`'s two signal copy sites, `SignalSubscriptionRunner`,
   `KafkaWorkflowMessaging`, `KafkaMessagingAutoConfiguration`,
   `MaestroObservabilityAutoConfiguration` + `MicrometerEngineObserver`
   (Task 4's patterns), `MaestroEngineHarness`, `ObserverReplayNoDoubleCountIT`,
   `PostgresIntegrationSupport`, `KafkaSpringIntegrationSupport`,
   `MaestroMigrationsCoexistIT`, and the Micrometer Tracing 1.6.4 /
   Boot 4.0.5 tracing auto-configuration APIs read directly from their jars.

---

## The handoff ruling: `signalConsumed` before `workflowUnparked`, `timerFired` after

**Ruling: neither ordering changes. The adapter is made order-tolerant, and the
two requirements turn out not to conflict.**

The brief asked whether span nesting or remote-parent restoration wins. The
answer is *both*, because the conflict is an artefact of assuming the segment
span is opened by exactly one designated callback.

`TracingEngineObserver.ensureSegment(...)` is **idempotent**: it opens the run
segment if none is open on this thread, and returns silently if one already is.
Consequently:

- **Signal path** (`SignalManager`): `workflowParked(SIGNAL)` closes segment *n*;
  `signalConsumed(info, false)` finds no open segment, opens segment *n+1* — with
  the signal's durable `traceContext` as remote parent — and records the
  `maestro.signal.consumed` event **on that segment**; the following
  `workflowUnparked(SIGNAL)` finds the segment already open and does nothing.
- **Timer path** (`DefaultWorkflowOperations`): `workflowUnparked(TIMER)` opens
  segment *n+1*; the following `timerFired(info, false)` records
  `maestro.timer.fired` on it.

Both orderings therefore yield an event nested inside an open segment, and the
signal path additionally gets the remote parent that design §3.2's
remote-parent rule requires. Pinned by
`TracingEngineObserverTest.signalConsumedEventIsNestedInTheSegment` (which
replays `SignalManager`'s exact emission order and asserts *one* segment, with
the event inside it) and `...timerEventsRecordedOnSegment`.

Why not change the engine instead: moving `signalConsumed` after
`workflowUnparked` would break the remote-parent rule (the segment would already
be open, rootless, before the context is known); moving `timerFired` before
`workflowUnparked` would put the event outside any segment. Either engine change
would also perturb Task 3's and Task 4's shipped emission-site semantics for a
problem the adapter can absorb for free. Order tolerance is the property that
actually wants to be true here — a third emission site added later cannot break
span nesting.

---

## Design conformance, and three deviations flagged for coordinator ruling

Everything in design §§3, 4, 7 is implemented as written except the three items
below. Each is documented in the relevant class Javadoc as well.

### DEVIATION-1 (structural) — run segments open **lazily**, on the workflow thread

Design §3.2's table names `workflowStarted` / `workflowResumed` /
`workflowUnparked` as the segment-opening callbacks, and justifies a
`ThreadLocal<SegmentState>` on the grounds that "a workflow's virtual thread
survives live parks".

That justification holds for parks but **not** for the first two callbacks.
`WorkflowExecutor` emits them on the *launching* thread:
`workflowStarted` fires inside `startWorkflow` (`WorkflowExecutor:439`), on the
caller's thread — typically an HTTP request thread — and `workflowResumed` fires
inside `launchWorkflow` (`WorkflowExecutor:1391`) *after* `thread.start()`, on
the recovery/caller thread. The workflow's virtual thread is a different thread
in both cases (`WorkflowExecutor:1366`).

Implementing §3.2 literally would therefore (a) open a `maestro.workflow.run`
span and an unclosed `SpanInScope` on an unrelated caller thread, corrupting that
thread's tracing for everything it does afterwards, and (b) leave the workflow
thread with no segment at all, so every activity span would be a detached root.
This is not a shortfall in the design's intent — it is a factual mismatch with
where the engine emits.

**Resolution:** `workflowStarted` / `workflowResumed` create no span. The first
live callback that genuinely runs on the workflow thread and needs a segment
opens it (`activityStarted`, live `signalConsumed`, live `timerFired` /
`timerCancelled`, `workflowUnparked`, and the terminal callbacks). Observable
topology is identical except that a segment begins at the run's first observable
step rather than a few microseconds earlier — and for a *resumed* run that is
arguably more honest, since everything between the resume and the first live step
is silent replay by design. Pinned by
`TracingEngineObserverTest.launchCallbacksOpenNoSpan`.

One consequence worth the coordinator's attention: a locally-started workflow's
first segment is a fresh root rather than a child of the caller's span. Capturing
the caller's context at `workflowStarted` into a `Map<workflowId, TraceContext>`
and consuming it on the workflow thread would restore that link; it was rejected
for this cycle as unbounded state for a link design §3.2 does not require (its
stated parent priority is previous-segment → remote → root). Noted as a possible
follow-up, not implemented.

`workflowStarted`/`workflowResumed` are not entirely inert: they record the
node's `serviceName`. `WorkflowInfo` is the only observation record that carries
it, yet segments are routinely opened from `ActivityInfo`/`SignalInfo`/
`TimerInfo` callbacks; the value is a per-engine constant
(`WorkflowExecutor`'s own `serviceName`), so remembering the last one seen is
exact rather than approximate.

### DEVIATION-2 (semantic) — a remote parent outranks the local park chain

Design §3.2's parent priority reads "previous segment of the same thread if one
exists (live park→unpark chain); **else** the remote context". Taken literally,
the remote context is used only when there is no previous local segment — i.e.
only after a crash or a cross-node resume.

That makes the cycle's headline requirement unreachable in the common case. The
normal cross-service flow is: service B's workflow parks *live* on B's own
thread, service A publishes the signal, B resumes on that same thread. Under the
literal priority, B's resumed segment chains to B's earlier segment and A's trace
is never joined — the "single connected trace" would only ever appear after a
crash. Design §4.3's own step 2 already contemplates remote re-parenting when a
local segment exists ("or the current segment is a fresh root, re-parents"), so
the table and §4.3 are in mild tension with each other; this resolves it toward
§4.3 and toward the spec's evidence requirement.

**Resolution:** when a live `signalConsumed` carries a usable `traceContext`, the
next segment takes the **remote** parent; the previous local segment is attached
as a `Span.Builder.addLink(...)` so the local chain is preserved rather than
dropped. With no remote context the previous segment is the parent, exactly as
designed. Pinned by
`TracingEngineObserverTest.remoteParentWinsOverLocalChainAndLinksIt` (asserts the
remote trace ID *and* the link to the prior segment) and by
`KafkaTraceLinkageIT`, which exercises precisely the live-park case end to end.

`addLink` is a `default` method on Micrometer's `Span.Builder`, so a bridge that
does not implement links (Brave) silently ignores it — no runtime risk.

### DEVIATION-3 (API-forced, cosmetic) — span-event attributes become span tags

Design §3.2 asks for span events with attributes
(`maestro.timer.fired` with attribute `maestro.timer.id`). Micrometer Tracing's
`Span` interface exposes only `event(String)` and `event(String, long, TimeUnit)`
— there is no attributed-event API to call (verified against
`micrometer-tracing-1.6.4`'s `Span` interface). The event names are recorded
exactly as designed; the values that were to be event attributes
(`maestro.timer.id`, `maestro.signal.name`) are recorded as **span tags** on the
segment instead. Last-write-wins if a segment fires several timers — acceptable,
and the events themselves are still individually visible.

Everything else conforms: span names, the attribute set on both span kinds
(`maestro.workflow.id`, `maestro.run.id` from MDC, `maestro.workflow.type`,
`maestro.service.name`, plus `maestro.activity.name` / `maestro.sequence` on
activity spans), the four span-event names, the exact Kafka header names and
`traceparent` grammar, the §7.1 property records (Task 4 already added
`ObservabilityProperties`/`TracingProperties`, canonical-ctor-only with
`defaults()` — unchanged), and the §7.3 version-catalog entries.

**One addition to §7.3, test-scope only:** the ordering pins need Boot's own
tracing auto-configuration on the test classpath, so
`spring-boot-micrometer-tracing` and
`spring-boot-micrometer-tracing-opentelemetry` were added to
`gradle/libs.versions.toml` and declared `testImplementation` in the starter and
`maestro-messaging-kafka`. `micrometer-tracing-test` was *not* needed: the tests
use a real OpenTelemetry SDK (transitively supplied by
`micrometer-tracing-bridge-otel`) with a ~15-line in-test `SpanExporter`, which
is strictly stronger evidence than §8.3's suggested `SimpleTracer` — it asserts
real parent edges, real 32-hex trace IDs and real W3C headers rather than a test
double's bookkeeping. No new production dependency: `maestro-core` gains nothing,
the starter and `maestro-messaging-kafka` gain `micrometer-tracing` as
`compileOnly` only.

---

## RULING 2 (signal-row `trace_context`) — how the constraints are met

- **Nullable, opaque, migration V4, record component, both stores** — all
  implemented: `V4__signal_trace_context.sql`
  (`ALTER TABLE maestro_workflow_signal ADD COLUMN trace_context VARCHAR(128)`,
  no `DEFAULT`, no `NOT NULL`, so it is a metadata-only ALTER on a live table),
  `WorkflowSignal.traceContext`, `AbstractJdbcWorkflowStore`
  (insert column list, select column list, `mapSignal`), `InMemoryWorkflowStore`.
- **No store or engine logic parses or branches on it.** The only reads are
  `ps.setString(8, signal.traceContext())` and `rs.getString("trace_context")`
  in the JDBC store, and pass-through in `SignalManager`. The single place the
  string is ever *interpreted* is `TracingEngineObserver.remoteParent(...)` in
  the starter — an adapter, not the store. Verified by grep: no `substring`,
  `split`, `indexOf`, `startsWith` or comparison against `traceContext()` exists
  in `maestro-core`, `maestro-store-jdbc`, `maestro-store-postgres` or
  `maestro-test`.
- **Absence degrades to a fresh root span, never an error.** Pinned four ways:
  `PostgresWorkflowStoreTest.saveSignal_nullTraceContextReadsBackNull`,
  `SignalManagerTest.deliverSignalWithoutTraceContextPersistsNull`,
  `TracingEngineObserverTest.nullTraceContextDegradesToRoot`, and
  `...malformedTraceContextDegradesToRoot` (a `traceContext` that is not
  grammar-valid W3C is treated as absent, not as a failure).
- **The two in-memory copy sites** (`markSignalConsumed`,
  `adoptOrphanedSignals`) were the real hazard: they rebuild the record and would
  have silently dropped the column. Both now carry it forward, pinned by
  `InMemoryWorkflowStoreTest.markSignalConsumedPreservesTraceContext` /
  `...adoptOrphanedSignalsPreservesTraceContext` (both RED before the fix) and by
  `PostgresWorkflowStoreTest.adoptOrphanedSignals_preservesTraceContext`.

**Implementation choice worth recording:** `WorkflowSignal` keeps a 7-arg
convenience constructor delegating `traceContext = null`, so the ~40 existing
construction sites compile unchanged (the same pattern `WorkflowExecutor`'s
narrower constructors use for `EngineObserver.NOOP`). Its Javadoc states
explicitly that copy sites must use the canonical constructor and carry
`traceContext` forward — the failure mode the in-memory store pins guard.

## RULING 4 — used, not relied upon

`TracingEngineObserver` routes every callback body through a private
`safely(name, Runnable)` that contains `RuntimeException` and warns at most once
per callback name. `Error` is deliberately not caught, so
`ExecutorShutdownException` / `WorkflowTerminatedException` still propagate.
The composite's containment is a second layer, not the first: relying on it alone
would leave a half-opened span and a dangling `SpanInScope` on a live workflow
thread and log on every subsequent emission.

---

## RED evidence, quoted verbatim from the archived logs

### 1. Core plumbing — `evidence/task-5-red-core.log`

Run with the `WorkflowSignal` component and `TraceContextHolder` present (the
API surface the tests need to compile) but no persistence wiring, so the failures
are assertion failures rather than compile errors:

```
> Task :maestro-test:test FAILED

InMemoryWorkflowStoreTest > markSignalConsumedPreservesTraceContext() FAILED
    org.opentest4j.AssertionFailedError at InMemoryWorkflowStoreTest.java:358

InMemoryWorkflowStoreTest > adoptOrphanedSignalsPreservesTraceContext() FAILED
    org.opentest4j.AssertionFailedError at InMemoryWorkflowStoreTest.java:372

34 tests completed, 2 failed

> Task :maestro-core:test

SignalManagerTest > signalPersisted carries the captured traceparent on SignalInfo FAILED
    org.opentest4j.AssertionFailedError at SignalManagerTest.java:698

SignalManagerTest > deliverSignal persists TraceContextHolder.current() on the signal row FAILED
    org.opentest4j.AssertionFailedError at SignalManagerTest.java:669

SignalManagerTest > consumeSignal surfaces the row's traceContext on SignalInfo — the durable hop FAILED
    org.opentest4j.AssertionFailedError at SignalManagerTest.java:722

31 tests completed, 3 failed

> Task :maestro-core:test FAILED
```

### 2. Postgres store / V4 — `evidence/task-5-red-store.log`

```
> Task :maestro-store-postgres:test

PostgresWorkflowStoreTest > Signal operations > adoptOrphanedSignals preserves trace_context — the column is opaque metadata FAILED
    org.opentest4j.AssertionFailedError at PostgresWorkflowStoreTest.java:709

PostgresWorkflowStoreTest > Signal operations > the V4 column is wide enough for a traceparent plus a tracestate-sized suffix FAILED
    org.opentest4j.AssertionFailedError at PostgresWorkflowStoreTest.java:727

PostgresWorkflowStoreTest > Signal operations > saveSignal round-trips trace_context through the V4 column FAILED
    org.opentest4j.AssertionFailedError at PostgresWorkflowStoreTest.java:678

50 tests completed, 3 failed

> Task :maestro-store-postgres:test FAILED
```

### 3. Span topology — `evidence/task-5-red-tracing.log`

Run against a `TracingEngineObserver` stub with `EngineObserver`'s no-op
defaults, so the failures show the tests detect the *absence of behaviour*, not
the absence of a symbol. 11 of 14 fail; the 3 that pass are the ones that pin
*absence* (`replayedCallbacksProduceNoSpans`,
`replayedSignalWithTraceContextIsSilent`, `launchCallbacksOpenNoSpan`) — they are
regression pins, and the log records them passing at this point honestly.

```
> Task :maestro-spring-boot-starter:test FAILED

TracingEngineObserver builds the design §3.2 span topology > a signal carrying a durable traceContext gives the resumed segment the remote parent FAILED
    java.util.NoSuchElementException at TracingEngineObserverTest.java:243

TracingEngineObserver builds the design §3.2 span topology > an activity span is a child of the run-segment span, and both carry the §3.2 attributes FAILED
    org.opentest4j.AssertionFailedError at TracingEngineObserverTest.java:108

TracingEngineObserver builds the design §3.2 span topology > the signal-consumed event lands inside the segment, even though the engine emits signalConsumed before workflowUnparked FAILED
    org.opentest4j.AssertionFailedError at TracingEngineObserverTest.java:206

TracingEngineObserver builds the design §3.2 span topology > a park closes the segment; the next unpark opens a segment chained to it FAILED
    org.opentest4j.AssertionFailedError at TracingEngineObserverTest.java:161

14 tests completed, 11 failed
```

(The full 11 are in the log; four representative lines quoted.)

### 4. Auto-config ordering — the two logs that matter most

`evidence/task-5-red-autoconfig.log` — before `TracingConfiguration` existed at
all, with Boot's real tracing chain producing `Tracer` and `Propagator`:

```
MaestroObservabilityAutoConfiguration > a recovered workflow's replayed activity produces zero spans on the recovering node FAILED
MaestroObservabilityAutoConfiguration > registers through the real Boot tracing auto-configuration chain, not a withBean Tracer stub FAILED

12 tests completed, 2 failed
```

`evidence/task-5-red-autoconfig-ordering.log` — **`TracingConfiguration` present
and correct, `afterName` deliberately absent.** This is the run that proves the
annotation is load-bearing and that the feature would otherwise have shipped
inert, exactly as the meters feature did in Task 4:

```
> Task :maestro-spring-boot-starter:test FAILED

MaestroObservabilityAutoConfiguration > registers through the real Boot tracing auto-configuration chain, not a withBean Tracer stub FAILED
    java.lang.AssertionError at MaestroObservabilityAutoConfigurationTest.java:329

12 tests completed, 1 failed
...
BUILD FAILED in 6s
17 actionable tasks: 17 executed
---- assertion detail from JUnit XML ----
### registers through the real Boot tracing auto-configuration chain, not a withBean Tracer stub
java.lang.AssertionError:
Expecting:
 <Started application [AnnotationConfigApplicationContext@5f8d9767 ... beanDefinitionCount = 61]>
to have a single bean of type:
 <io.b2mash.maestro.spring.observe.TracingEngineObserver>
but found no beans of that type
```

The context started, Boot's own chain produced a real `Tracer` and `Propagator`
(asserted separately in the same test, and true), and the adapter bean was still
absent — the "ships inert" signature. The same pin exists for the Kafka module,
`evidence/task-5-red-kafka-autoconfig.log`:

```
> Task :maestro-messaging-kafka:test FAILED

KafkaMessagingAutoConfiguration wires KafkaTracePropagation > registers through the real Boot tracing auto-configuration chain, not a withBean Tracer stub FAILED
    java.lang.AssertionError at KafkaMessagingAutoConfigurationTracingTest.java:68

4 tests completed, 1 failed
```

Both classes now declare `afterName` for Boot 4's four tracing
auto-configurations —
`micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration`,
`...NoopTracerAutoConfiguration`,
`micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration`,
`micrometer.tracing.brave.autoconfigure.BraveAutoConfiguration` — so the ordering
holds whichever bridge the application ships. Class names absent from the
classpath are ignored by `AutoConfigurationSorter`, which is what makes naming
all four safe. (Class names read directly from the Boot 4.0.5 jars, not from
memory.)

### 5. Kafka wire contract — `evidence/task-5-red-kafka.log`

Run against a `KafkaTracePropagation` stub (no-op inject, null extract) already
wired into `KafkaWorkflowMessaging`:

```
> Task :maestro-messaging-kafka:test

Kafka records carry the W3C trace context contract > a consumed signal restores the remote context and the TraceContextHolder for the handler FAILED
    org.opentest4j.AssertionFailedError at KafkaTracePropagationContractTest.java:217

Kafka records carry the W3C trace context contract > a signal published under an active span carries a grammar-valid traceparent naming that span FAILED
    org.opentest4j.AssertionFailedError at KafkaTracePropagationContractTest.java:114

Kafka records carry the W3C trace context contract > task subscriptions restore the remote context too FAILED
    org.opentest4j.AssertionFailedError at KafkaTracePropagationContractTest.java:305

Kafka records carry the W3C trace context contract > tasks and lifecycle events are injected on the same contract FAILED
    org.opentest4j.AssertionFailedError at KafkaTracePropagationContractTest.java:144

Kafka records carry the W3C trace context contract > the holder is cleared after the handler returns, so the next record on the same listener thread never inherits it FAILED
    org.opentest4j.AssertionFailedError at KafkaTracePropagationContractTest.java:250

10 tests completed, 5 failed

> Task :maestro-messaging-kafka:test FAILED
```

The 5 that pass at this point are the degradation pins (no active span → no
headers; no collaborator → byte-identical wire; untraced record delivered
normally; `extractTraceparent` returning null; propagator field names).

### 6. The two integration pins — RED by temporary, reverted source patches

Both ITs were written after their unit/contract counterparts were already green,
so a "before the feature existed" RED had to be produced deliberately. Each was
produced by temporarily disabling exactly the behaviour under test, running the
IT, archiving, and reverting with `git checkout --`; the working tree contains no
trace of either patch (verified: `grep -c TEMPORARY` returns 0 in both files, and
both are at their committed content).

`evidence/task-5-red-replay-it.log` — replay guard disabled so replayed
activities emit a span:

```
A recovered workflow's replayed activity produces no spans on the recovering node > node B replays stepOne to reach the park and emits exactly one activity span — for the genuinely live stepTwo FAILED
...
org.opentest4j.AssertionFailedError: node B replayed stepOne and ran stepTwo live, so exactly one activity span may exist on B; a phantom span for the replayed step would make this 2. Spans were: [chain.stepOne, chain.stepTwo] ==> expected: <1> but was: <2>
```

`evidence/task-5-red-linkage.log` — `inject` and `runWithExtractedContext`
disabled (the pre-Task-5 state):

```
A signal published by service A and consumed by service B yields one connected trace > B's resumed run segment carries A's trace ID, parented to A's publish span, and the durable signal row is what carried it FAILED
...
org.opentest4j.AssertionFailedError: the signal row must carry the traceparent the listener extracted ==> expected: not <null>
	at app//io.b2mash.maestro.integration.observability.KafkaTraceLinkageIT.crossServiceSignalYieldsOneConnectedTrace(KafkaTraceLinkageIT.java:146)
```

---

## Required pins — where each one lives

| Brief's required pin | Test | Evidence |
|---|---|---|
| No spans during replay (recovered workflow, replayed activities → zero new spans) | `TracingReplayNoSpansIT` (real crash + Postgres, one OTel SDK **per node**); `MaestroObservabilityAutoConfigurationTest.replayedActivitiesProduceNoSpans` (starter context, cross-context recovery); `TracingEngineObserverTest.replayedCallbacksProduceNoSpans` (unit, all six replay-flagged callbacks) | `task-5-red-replay-it.log`, `task-5-red-autoconfig.log`, `task-5-red-tracing.log` |
| Kafka propagation contract: exact header names + W3C grammar | `KafkaTracePropagationContractTest` (10 tests, Testcontainers Kafka, real W3C propagator) | `task-5-red-kafka.log` / `task-5-green-kafka.log` |
| Durable restoration: signal persisted with `trace_context`, consumed later → resumed segment has the remote parent | `TracingEngineObserverTest.durableTraceContextRestoresTheRemoteParent` + `...rootlessSegmentIsReparentedOnRemoteContext`; `SignalManagerTest.consumeSignalSurfacesDurableTraceContext`; `KafkaTraceLinkageIT` end to end | `task-5-red-tracing.log`, `task-5-red-core.log`, `task-5-red-linkage.log` |
| V4 migration round-trip in the Postgres store | `PostgresWorkflowStoreTest` × 4 (round-trip, null, adoption-preserves, 128-char width) + `MaestroMigrationsCoexistIT.v4AddsTheNullableSignalTraceContextColumn` (type/nullability/width from `information_schema`, design §8.6) | `task-5-red-store.log`, `task-5-green-migration.log` |
| Null/absent `trace_context` degrades to a root span | `TracingEngineObserverTest.nullTraceContextDegradesToRoot` + `...malformedTraceContextDegradesToRoot`; `KafkaTracePropagationContractTest.untracedRecordIsDeliveredNormally` | `task-5-red-tracing.log`, `task-5-green-kafka.log` |
| One integration-level assertion: two-service flow → one connected trace | `KafkaTraceLinkageIT` — separate OTel SDK per service, real broker, real Postgres; asserts B's resumed segment's trace ID == A's publish trace ID, parented to A's publish span, and that the `trace_context` column is what carried it | `task-5-red-linkage.log`, `task-5-green-integration.log` |

---

## Files touched

**`maestro-core`** (no new dependency of any kind)
- `core/observe/TraceContextHolder.java` — new; plain `ThreadLocal<@Nullable String>` with `set`/`current`/`clear`/`runWith`.
- `core/model/WorkflowSignal.java` — nullable `traceContext` component + documented 7-arg convenience constructor.
- `core/engine/SignalManager.java` — `deliverSignal` captures `TraceContextHolder.current()` onto the row and onto `SignalInfo`; `consumeSignal` surfaces `signal.traceContext()`.

**`maestro-store-jdbc` / `maestro-store-postgres`**
- `AbstractJdbcWorkflowStore` — insert column list, select column list, `mapSignal`.
- `db/migration/V4__signal_trace_context.sql` — new.

**`maestro-test`**
- `InMemoryWorkflowStore` — both signal copy sites preserve `traceContext`.

**`maestro-spring-boot-starter`**
- `spring/observe/TracingEngineObserver.java` — new (the adapter).
- `spring/observe/MaestroObservabilityAutoConfiguration.java` — new nested `TracingConfiguration`; `afterName` extended with Boot's four tracing auto-configurations; class Javadoc updated.
- `build.gradle.kts` — `compileOnly(micrometer-tracing)`, test deps.

**`maestro-messaging-kafka`**
- `KafkaTracePropagation.java` — new.
- `KafkaWorkflowMessaging.java` — new 5-arg constructor (old one delegates with `null`), `tracedRecord(...)` publish funnel for tasks/signals/lifecycle events, extraction wrapper on both subscribe paths.
- `config/KafkaMessagingAutoConfiguration.java` — nested `TracePropagationConfiguration`, `ObjectProvider` injection, `afterName`.
- `build.gradle.kts` — `compileOnly(micrometer-tracing)`, test deps.

**`maestro-integration-tests`**
- `support/OtelTracingFixture.java`, `observability/TracingReplayNoSpansIT.java`, `observability/KafkaTraceLinkageIT.java` — new.
- `schema/MaestroMigrationsCoexistIT.java` — V4 column pin.
- `build.gradle.kts` — tracing test deps.

**Build**
- `gradle/libs.versions.toml` — two test-only Boot tracing modules (§7.3 addition, above).

Test-only fixtures: `OtelTracingFixture` exists three times (starter,
`maestro-messaging-kafka`, `maestro-integration-tests`) because the three modules
share no test-fixtures configuration; each is ~45–110 lines and deliberately
scoped to what its module asserts. Adding the `java-test-fixtures` plugin to
share them was judged more build surface than the duplication costs.

---

## Test counts (from JUnit XML, appended to `evidence/task-5-verify.log`)

New tests written in this task: **50**
- `TracingEngineObserverTest` — 16
- `MaestroObservabilityAutoConfigurationTest` — +5 (7 → 12)
- `KafkaTracePropagationContractTest` — 10
- `KafkaMessagingAutoConfigurationTracingTest` — 4
- `TraceContextHolderTest` — 5
- `SignalManagerTest` — +5
- `PostgresWorkflowStoreTest` — +4
- `InMemoryWorkflowStoreTest` — +3
- `TracingReplayNoSpansIT` — 1, `KafkaTraceLinkageIT` — 1, `MaestroMigrationsCoexistIT` — +1

Module totals after the change (`evidence/task-5-verify.log`, "per-module JUnit
XML totals"):

```
maestro-core: tests=330 failures=0 errors=0 skipped=0
maestro-spring-boot-starter: tests=100 failures=0 errors=0 skipped=0
maestro-messaging-kafka: tests=36 failures=0 errors=0 skipped=0
maestro-store-postgres: tests=57 failures=0 errors=0 skipped=0
maestro-integration-tests: tests=95 failures=0 errors=0 skipped=0
```

(`maestro-integration-tests` reads 95 in `task-5-verify.log` and 96 in
`task-5-build-full.log`: the difference is the one
`MaestroMigrationsCoexistIT.v4AddsTheNullableSignalTraceContextColumn` added
after the verify run, in commit `d486758`.)

## Verification

The task's required command, `evidence/task-5-verify.log`:

```
$ ./gradlew :maestro-core:test :maestro-spring-boot-starter:test :maestro-messaging-kafka:test :maestro-store-postgres:test :maestro-integration-tests:test --rerun-tasks

BUILD SUCCESSFUL in 1m 48s
44 actionable tasks: 44 executed
```

Full multi-module build, `evidence/task-5-build-full.log`
(`./gradlew build --rerun-tasks` — every task re-executed, samples included):

```
> Task :maestro-integration-tests:build

BUILD SUCCESSFUL in 1m 53s
134 actionable tasks: 134 executed
```

Per-module totals from that same run (appended to the log):

```
maestro-core: tests=330 failures=0 errors=0 skipped=0
maestro-spring-boot-starter: tests=100 failures=0 errors=0 skipped=0
maestro-messaging-kafka: tests=36 failures=0 errors=0 skipped=0
maestro-store-postgres: tests=57 failures=0 errors=0 skipped=0
maestro-integration-tests: tests=96 failures=0 errors=0 skipped=0
maestro-test: tests=52 failures=0 errors=0 skipped=0
maestro-store-jdbc: tests=11 failures=0 errors=0 skipped=0
```

---

## Self-review against the brief and design §§3/4/7

- [x] `maestro-core` gains no Spring / Micrometer / OpenTelemetry dependency — verified in `maestro-core/build.gradle.kts` (unchanged) and by the fact that `TraceContextHolder` imports only `org.jspecify`.
- [x] All tracing code lives in the starter and `maestro-messaging-kafka`, both `compileOnly` on `micrometer-tracing`. Runtime absence is pinned by `FilteredClassLoader(Tracer.class)` tests in *both* modules (`tracingAbsentWithoutTracerOnClasspath`, `absentWithoutTracerOnClasspath`), which load the auto-configurations with Micrometer Tracing genuinely off the classpath.
- [x] Jackson 3 only — no new serialization code; `tools.jackson` unchanged. No `javax.*`.
- [x] Config property records untouched (Task 4's `ObservabilityProperties`/`TracingProperties`, canonical-ctor-only with `defaults()`); `maestro.observability.tracing.enabled=false` pinned in both modules.
- [x] No meter tag added anywhere; `workflowId`/`runId` appear only as span *attributes*. `MicrometerEngineObserver` untouched.
- [x] `(workflow_instance_id, sequence_number)` uniqueness untouched — V4 adds a column to `maestro_workflow_signal`, no index, no constraint.
- [x] JSpecify + Javadoc on every new public API; `@Nullable` placed on the nested type where required (`Span.@Nullable Builder`, `Tracer.@Nullable SpanInScope`).
- [x] No Lombok; records for value types; `final` classes.
- [x] TDD RED→GREEN for every behavioural change, failing output quoted above verbatim from freshly archived logs.
- [x] Every number in this report is greppable from an archived log; the counts were produced by re-parsing JUnit XML at report time, not recalled.
- [x] Commits incremental (10 commits, none holding more than one work unit).

## Post-implementation code review — one real finding, raised not improvised

A `feature-dev:code-reviewer` agent was run over `4c0b852..HEAD` after the work
was green. It confirmed the invariants (no core dependency, opaque
`trace_context`, replay discipline, no meter-tag regression, correct
`SpanInScope`/`Span` lifecycle on the Kafka path including handler exceptions,
`compileOnly` loadability, canonical-ctor-only property records) and raised one
Critical finding. **It is real. I verified it against the source rather than
accepting it, and the verification also corrected half of it.**

### FINDING-1 — a terminate landing mid-run leaves the run-segment span unclosed

Verified reachable sequence: a workflow runs a live activity (segment span
opens), then reaches a status write that discovers the instance is already
`TERMINATED`. `InstanceStatusWriter.write` (`InstanceStatusWriter.java:75`)
throws `WorkflowTerminatedException` — an `Error` — and that throw happens
*before* `observer.workflowParked(...)`:

- `SignalManager.java:319` (`updateInstanceStatus(ctx, WAITING_SIGNAL)`) vs the
  emission at `SignalManager.java:327`;
- `DefaultWorkflowOperations.java:279` vs the emission at
  `DefaultWorkflowOperations.java:288`;
- the post-resume `updateInstanceStatus(ctx, RUNNING)` calls, which run while a
  segment is open;
- and `SagaManager.transitionToCompensating` (`SagaManager.java:532`), which
  throws the same `Error` during failure handling with the segment still open.

`WorkflowExecutor`'s handlers for that unwind — `handleShutdownSuspension`
(`WorkflowExecutor.java:1546`) and `handleTermination`
(`WorkflowExecutor.java:1563`) — emit **no observer callback at all**, unlike
their sibling `handleStaleRunStandDown` (`WorkflowExecutor.java:1585`), which
does emit `standDown`. `observer.workflowTerminated(...)`
(`WorkflowExecutor.java:759`) fires on the *terminating* thread (an admin
thread), not the workflow thread that holds the open segment, so it cannot help.

Result: `TracingEngineObserver`'s segment `Span` is started but never `end()`ed,
so it is never exported — and the activity spans already exported inside it
reference a parent that never appears.

**Two corrections to the review's framing, both verified:**

1. **The shutdown path is NOT affected.** `ExecutorShutdownException` is
   constructed in exactly one place, `ParkingLot.shutdownSignal`
   (`ParkingLot.java:364`), i.e. only while a workflow is already parked — which
   is *after* `workflowParked` has closed the segment. Likewise the two
   `standDownIfTerminated` throw sites (`SignalManager.java:579`,
   `DefaultWorkflowOperations.java:381`) sit inside the park loop, after the
   segment is closed. The gap is terminate-specific, not
   "shutdown-or-terminate".
2. **It is not a memory leak.** Each run gets its own virtual thread
   (`WorkflowExecutor.java:1366`, `Thread.ofVirtual()...unstarted(...)`), so the
   `ThreadLocal` is garbage as soon as that thread unwinds. The defect is a
   *dropped span*, an observability gap on the operator-terminate path — not a
   leak, and not a workflow-correctness defect: engine execution, durability and
   compensation semantics are entirely unaffected.

**Why I did not fix it here.** The only complete fix is a new engine emission on
the terminate-abandonment path — either a new `EngineObserver` callback or a new
`StandDownReason`. Both change design §1.2's observer surface, which binds Tasks
3–7; and routing it through `standDown` would make Task 4's
`maestro.standdown{reason=...}` counter increment on every operator terminate,
which is precisely the "a routine operation recorded as a stand-down" confusion
the design's control-flow-signal rationale exists to prevent. Task 3 also chose
the current silence deliberately and documented it in-line
(`SignalManager.java:317-319`: *"a shutdown or terminate abandons the run and
emits neither"*). Changing that is a design amendment, and the brief binds
deviations to a coordinator ruling — so this is raised, with the reachable
sequence and blast radius established, rather than improvised.

**Recommended ruling** (for the coordinator, not applied): add a single
`EngineObserver.runAbandoned(WorkflowInfo w, AbandonReason reason)` callback
emitted from `handleShutdownSuspension` and `handleTermination`, kept distinct
from `standDown` so the meter catalog stays honest. `TracingEngineObserver`
would close its segment there; `MicrometerEngineObserver` can ignore it. Until
then the gap is bounded to: *a segment span is dropped when an operator
terminates a workflow between its last live step and its next park.*

## Concerns / handoff

0. **FINDING-1 above needs a coordinator ruling** — the one substantive defect
   found in review, deliberately raised rather than fixed because the fix is a
   design-surface change.
1. **Three deviations above need a coordinator ruling**, in descending
   importance: DEVIATION-1 (lazy segment opening — forced by where the engine
   emits `workflowStarted`/`workflowResumed`), DEVIATION-2 (remote parent
   outranks the local park chain — without it the cycle's headline requirement
   is unreachable in the common live-park case), DEVIATION-3 (span-event
   attributes become span tags — forced by Micrometer's API).
2. **`docs/configuration.md` documents no `maestro.observability.*` property at
   all** — neither Task 4's `metrics.enabled` nor this task's `tracing.enabled`.
   Left for Task 8 (the docs task) rather than expanded into here, but it is a
   real gap: two shipped properties with no reference documentation.
3. **`@MaestroSignalListener` listeners do not restore trace context.**
   `MaestroSignalListenerBeanPostProcessor` builds its own listener containers
   (`MaestroSignalListenerBeanPostProcessor:217`) and has raw `ConsumerRecord`
   access, but design §4.3 scopes extraction to `KafkaWorkflowMessaging`'s
   containers only, so that is what was implemented. A cross-service flow that
   arrives on a *domain* topic via `@MaestroSignalListener` — the pattern
   CLAUDE.md calls the typical one — therefore does not get a connected trace
   today. Flagged as a scoped follow-up, not a defect against the design.
4. **Parallel branches start detached segments.** Each branch runs on its own
   thread with its own ThreadLocal, and no callback carries the fork's parent
   context, so a branch's first segment is a fresh root. Design §3.2 anticipates
   per-thread state for branches but does not require them to be linked;
   recorded here so it is a known limitation rather than a surprise.
5. **Crash/cross-node continuity for timer- and recovery-driven resumes** is
   unchanged from design §3.2's documented limitation: only signal-driven wakes
   carry a durable trace context.

---

# Fix round 1

**Status: COMPLETE**

pwd: `/Users/rakheendama/Projects/2026/maestro/.claude/worktrees/release-hardening`
branch: `worktree-release-hardening`
HEAD before this round: `7af16a4` (design §11, RULINGs 5–8)
Implementation commit: `5dc9926`; this report + evidence commit follows.

Design §11 was read first. RULINGs 6/7/8 confirm the three deviations, RULING 5
approves the `runAbandoned` recommendation. Five items fixed (F1, F2, F3, F5,
RULING 5 + F4); F6 deferred by the coordinator and untouched.

Evidence archived this round (identity headers on all):
`task-5-fix1-red-starter.log`, `task-5-fix1-green-starter.log`,
`task-5-fix1-red-kafka.log`, `task-5-fix1-green-kafka.log`,
`task-5-fix1-red-fanout.log`, `task-5-fix1-green-fanout.log`,
`task-5-fix1-red-linkage.log`, `task-5-fix1-green-linkage.log`,
`task-5-fix1-green-core.log`, `task-5-fix1-verify.log`, `task-5-fix1-build.log`.

---

## F1 (CRITICAL) — an inbound trace header could discard a signal

**Fixed at extraction, before the holder and therefore before the database.**
`KafkaTracePropagation.extractTraceparent` now validates the W3C grammar and
returns `null` for anything that fails, so a malformed or over-long header is
treated as *absent*: no span, no `TraceContextHolder` value, nothing persisted,
signal delivered normally. Grammar-valid implies exactly 55 characters, so this
subsumes any length cap on the `VARCHAR(128)` column. Validation deliberately
did **not** go into `maestro-core` — RULING 2 forbids the store or engine
interpreting the value.

The RED is the most important evidence in this round, because it shows the
consequence rather than the mechanism. With validation reverted, driving the
real Postgres column end to end
(`evidence/task-5-fix1-red-linkage.log`):

```
A signal published by service A and consumed by service B yields one connected trace > a signal carrying an over-long traceparent is still delivered and consumed — the workflow completes and the resumed segment is simply a fresh root FAILED
...
org.opentest4j.AssertionFailedError: the signal must still wake the workflow — a bad trace header may never cost a signal its delivery ==> expected: <COMPLETED> but was: <FAILED>
```

The workflow reached **FAILED**, not merely "stuck": the signal was
dead-lettered, so its `awaitSignal` timed out. A decorative header cost the
workflow its signal *and* its run.

**Honest limit on this evidence:** the archived log does not contain the
Postgres `22001` / "value too long" string — the integration suite's test
logging is filtered to test events, so the underlying SQLException text is not
in it (`grep -c "value too long\|22001"` over that log returns `0`). The
diagnosis of the mechanism is inference from the code path plus the fact that
capping the value at extraction makes the test pass; only the assertion above is
quoted as fact.

Unit-level RED at the wire (`evidence/task-5-fix1-red-kafka.log`), 3 of 13
failing:

```
org.opentest4j.AssertionFailedError: an over-long traceparent must never reach the holder, and so can never reach the trace_context column ==> expected: <null> but was: <00-aaaaaaaa…
org.opentest4j.AssertionFailedError: every malformed traceparent must degrade to absent; holder values were [not-a-traceparent, , 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331, 00-0AF7651916CD43DD8448EB211C80319C-B7AD6B7169203331-01, 01-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01] ==> expected: <true> but was: <false>
org.opentest4j.AssertionFailedError: extraction is the choke point — nothing invalid may travel further ==> expected: <null> but was: <00-aaaaaaaa…
```

Pins added: `KafkaTracePropagationContractTest` +3 (over-long, five malformed
shapes incl. truncated / upper-case / version ≠ 00, and `extractTraceparent`
itself), plus `KafkaTraceLinkageIT.oversizedTraceparentNeverDiscardsTheSignal`
which drives the real column and asserts the workflow completes, the row's
`trace_context` is null, and the resumed segment degrades to a fresh root.

## F2 — parallel-branch segments could never be closed

Each branch of `WorkflowContext.parallel` runs on its own virtual thread
(`DefaultWorkflowOperations`), and nothing ever tells the adapter a branch
finished. A segment opened there died un-`end()`ed and unexported, so the
branch's activity spans exported naming a parent the backend never receives.
My original report called these "detached", which understated it — they were
*orphaned*.

**Fix: branch threads never open a segment.** They parent their activity spans
to the forking thread's segment, inherited through an `InheritableThreadLocal`
(`ForkPoint`, carrying the parent context plus the owning thread id so a branch
can tell "my parent's segment" from "my own"). I verified empirically that
virtual threads created with `Thread.ofVirtual().start()` inherit inheritable
thread-locals, **including virtual → virtual**, which is the actual shape here —
this was measured, not assumed.

A second detector covers the harder shape: a workflow whose *first* statement is
`parallel(...)` forks before any callback has reached the adapter, so no fork
point exists. There, `ActivityInfo.sequenceNumber() >= 1000` identifies a branch
via the engine's documented sequence-space partition (CLAUDE.md: branch *i* of a
fork at parent seq *p* allocates from `p*1000 + (i+1)*1000`, ≤999 steps per
branch). **CORRECTED in fix round 2 — this trade-off was described wrongly in both
directions.** See "Corrected blast radius" in the Fix round 2 section: the
long-main-line cost is narrower than stated here, and the fork-first parking
branch was wider (it was, in fact, still broken).

Pinned by `TracingParallelBranchIT` on `TestWorkflows.ParallelWorkflow`
(deliberately the fork-first shape). RED with the guards reverted
(`evidence/task-5-fix1-red-fanout.log`):

```
org.opentest4j.AssertionFailedError: these spans name a local parent that was never exported — the parent segment was opened on a branch thread and never closed: [maestro.activity(parent=4bcab7eca4f85cbc), maestro.activity(parent=a4baf60cda7f3473), maestro.activity(parent=6215f2bb2db4c47e)] ==> expected: <true> but was: <false>
```

Three branches, three orphaned parents — exactly the finding.

## F3 — RULING 7 was only half-implemented

Re-parenting was gated on `s.segmentIsRoot`, so a **non-root** open segment
early-returned and the remote context was dropped entirely: neither re-parented
nor linked. Reachable with no crash at all, exactly as the review described — a
workflow parks on S1 and resumes (its segment is now chained, hence non-root),
then awaits S2 which was already delivered, so `SignalManager.awaitSignal`
consumes on the no-park fast path. Now unconditional, with one guard: a repeated
*identical* remote context is a no-op, so a workflow consuming several signals
from the same publisher does not mint an empty segment each time.

## F5 — grammar agreement

`TracingEngineObserver`'s pattern is now `^00-…`, matching design §4.1 and
`KafkaTracePropagationContractTest`. A `01-` traceparent is treated as absent
rather than handed to the propagator.

RED for F3 and F5 together with RULING 5 (`evidence/task-5-fix1-red-starter.log`,
3 of 19 failing):

```
org.opentest4j.AssertionFailedError: expected: <3> but was: <2>
org.opentest4j.AssertionFailedError: the adapter's grammar must agree with design §4.1 and the Kafka contract test, which both pin version 00 ==> expected: <false> but was: <true>
org.opentest4j.AssertionFailedError: no scope may survive runAbandoned(SHUTDOWN) ==> expected: <null> but was: <SdkSpan{… name=maestro.workflow.run … endEpochNanos=0}>
```

`endEpochNanos=0` in that third failure is the FINDING-1 defect stated in the
SDK's own terms: the span was started and never ended.

## RULING 5 + F4 — `runAbandoned`

Added `EngineObserver.runAbandoned(WorkflowInfo, AbandonReason)`, a `default`
no-op like every other callback, so Tasks 3 and 4 need no change and
`MicrometerEngineObserver` deliberately does not implement it (no new meter, no
double-count — `workflowTerminated` already fires once on the operator thread).
`CompositeEngineObserver` fans it out with the same containment as every other
callback.

**F4 — the judgment call the coordinator left open.** `runAbandoned` as ruled
covers shutdown and terminate. The converged-loser branches
(`transitionToTerminal` returning false, on both the COMPLETED and FAILED paths)
and `handleWorkflowFailure`'s `catch (Exception updateError)` leave the segment
unclosed in exactly the same shape. I extended `AbandonReason` rather than
adding a separate emission, because all four cases are one fact —
*this local run ended on the workflow thread without this node recording an
outcome* — and one callback with a precise reason keeps that fact in one place.
The reasons are kept individually meaningful rather than lumped, because they
are operationally different things:

| Reason | Emitted from | Means |
|---|---|---|
| `SHUTDOWN` | `handleShutdownSuspension` | node stopping; instance stays recoverable |
| `TERMINATED` | `handleTermination` | operator terminated; the `TERMINATED` row stands |
| `CONVERGED` | both `transitionToTerminal(...) == false` branches | another writer finalised first; this run deliberately did not double-record |
| `TERMINAL_WRITE_FAILED` | `handleWorkflowFailure`'s `catch` | the terminal write itself failed; recovery decides |

Every emission goes through `WorkflowExecutor`'s contained `emit(...)`, so a
throwing observer cannot turn a routine shutdown into an escaping exception on
the unwind path. Task 3's now-stale in-line comment at `SignalManager:317-319`
is updated, and states the reachable case that motivates the callback: the
status write immediately above it can itself raise
`WorkflowTerminatedException` before the park emission ever runs.

Core pins: `WorkflowExecutorObserverTest` +2 — terminate and shutdown each emit
exactly one `runAbandoned` with the right reason, **zero** `standDown` and
**zero** `workflowFailed` (the ruling's central point: a routine deploy must not
land in a failure-shaped counter). Adapter pin: `runAbandonedClosesTheSegment`
loops all four reasons, asserting the segment is exported, tagged with the
reason, and the thread left clean.

## A process note — a self-inflicted error worth recording

The first attempt at the F3/F5/RULING 5 RED reverted the patch with
`git checkout -- TracingEngineObserver.java`, which restored the file to `HEAD`
and silently wiped every fix-round change in it, not just the temporary patch.
The compiler caught it immediately (`cannot find symbol`), and the changes were
reapplied. Every later RED demonstration copied the file to the scratchpad first
and restored from that copy. All temporary patches are verified gone:
`grep -c "TEMPORARY RED"` returns `0` for both `TracingEngineObserver.java` and
`KafkaTracePropagation.java`.

## Verification

Required command (`evidence/task-5-fix1-verify.log`):

```
$ ./gradlew :maestro-core:test :maestro-spring-boot-starter:test :maestro-messaging-kafka:test :maestro-store-postgres:test :maestro-integration-tests:test --rerun-tasks

BUILD SUCCESSFUL in 1m 49s
44 actionable tasks: 44 executed
```

Per-module totals appended to that log:

```
maestro-core: tests=332 failures=0 errors=0 skipped=0
maestro-spring-boot-starter: tests=103 failures=0 errors=0 skipped=0
maestro-messaging-kafka: tests=39 failures=0 errors=0 skipped=0
maestro-store-postgres: tests=57 failures=0 errors=0 skipped=0
maestro-integration-tests: tests=98 failures=0 errors=0 skipped=0
```

Full build (`evidence/task-5-fix1-build.log`):

```
BUILD SUCCESSFUL in 1m 55s
134 actionable tasks: 134 executed
```

**CORRECTED in fix round 2 — this figure was wrong.** See the single
authoritative count in the Fix round 2 section below; the "+3 integration /
net 11" stated here contradicted its own 96→98 delta.

## Concerns after this round

1. **F6 remains deferred as instructed** — `serviceName` is a node-wide volatile
   set by `workflowStarted`/`workflowResumed`, so the first recovered run's first
   segment on startup can lack `maestro.service.name`. Untouched.
2. **F2's residual — CLOSED in fix round 2**, and it was larger than this
   bullet claimed: the predicate was consulted only from `activityStarted`, so a
   parking branch still leaked a segment. See Fix round 2.
3. **Branch-level span events are dropped.** A branch that awaits a signal or
   sleeps has no segment to record `maestro.signal.consumed` /
   `maestro.timer.fired` on, so those events are skipped on branch threads. The
   activity spans and their parenting are unaffected. Flagged as a known,
   documented limit of the no-segment-on-branches rule.
4. **`docs/observability.md` still owes RULING 8's note** (event attributes are
   segment tags with last-write-wins) and the `maestro.observability.*`
   properties — Task 8's, unchanged from the original report.

---

# Fix round 2

**Status: COMPLETE**

pwd: `/Users/rakheendama/Projects/2026/maestro/.claude/worktrees/release-hardening`
branch: `worktree-release-hardening`
HEAD before this round: `8d47715`
Implementation commit: `26cd8a9`; this report + evidence commit follows.

Three items. Fix-round-1's own text has been corrected in place where it was
wrong (marked **CORRECTED in fix round 2** at each site) rather than left to
contradict this section.

Evidence archived this round: `task-5-fix2-red-fanout.log`,
`task-5-fix2-green-fanout.log`, `task-5-fix2-red-linkage.log`,
`task-5-fix2-green-linkage.log`, `task-5-fix2-green-core.log`,
`task-5-fix2-verify.log`, `task-5-fix2-build.log`.

---

## 1. F2 residual — the gate now lives in `ensureSegment`

The branch predicate was consulted only from `activityStarted`. The other three
`ensureSegment` callers — `workflowUnparked`, `signalConsumed`, `timerEvent` —
did not consult it, so a branch that *parked* (explicitly supported: a branch may
`sleep()` or `awaitSignal()`) opened a segment on the branch thread through the
wake path, and nothing could ever close it. F2's exact defect, still live.

**Fix:** the gate moved into `ensureSegment` itself, so all four entry points are
covered by one check. The verdict now **latches per thread**
(`RunState.branchThread`), because `activityStarted` is the only callback that
carries a sequence number — a branch identified there must stay identified when
it later wakes through callbacks that carry none.

### Two corrections the pin needed before it discriminated

Worth recording, because the first two versions of this test passed against the
*broken* code and would have been false assurance:

1. **`parallel()` runs a single-task list inline** (`DefaultWorkflowOperations:510`
   — `if (tasks.size() == 1) { return List.of(tasks.getFirst().call()); }`). My
   first fixture used one branch to dodge a race and therefore never forked a
   thread at all; the "2 segments" it reported were an ordinary main-thread
   park/resume chain. Diagnosed by dumping the exported spans and seeing the
   activity at `maestro.sequence=1`, not ≥1000.
2. **With only a pre-park activity, the leak is invisible.** A leaked segment is
   never exported, and in that shape it has no exported children either — so
   "leaked" and "never opened" produce byte-identical span output. The fixture
   now runs a second activity **after** the park, which becomes the leaked
   segment's child and is thus orphaned. That is what makes the defect
   observable.

A third constraint shaped the fixture: two branches parking concurrently race
each other on the instance status row and the run dies with
`OptimisticLockException` — an engine-level limitation of concurrent parking
branches, unrelated to tracing. The fixture therefore forks two branches of which
only one parks.

RED, with the gate reverted to the fix-round-1 shape
(`evidence/task-5-fix2-red-fanout.log`):

```
A parallel fan-out leaves no unclosed segment and no orphan parent > a fork-first workflow whose branches park still leaves no unclosed segment and no orphan parent FAILED
...
org.opentest4j.AssertionFailedError: a parking branch opened a segment nothing closed; orphaned spans: [maestro.activity(parent=43137248a0659e20)] ==> expected: <true> but was: <false>
```

### Corrected blast radius — **this paragraph was wrong twice; see Fix round 3**

Fix round 1 described the trade-off wrongly, and this round's replacement was
*also* wrong: it claimed the latch bites only when the first live
`activityStarted` on a thread sits at seq ≥ 1000 (recovery replaying 1000+
steps). That is not true. After a join the engine advances the main sequence past
the branch spaces, so **every** main line following any `parallel()` runs at
seq ≥ 2000 and latched as a branch — the sequence latch broke every
fork-then-park workflow, not a rare recovery case. Fix round 3 removes the
sequence number from the classification decision entirely; the accurate statement
lives there.

The one part of this paragraph that was right: the fork-first *parking* branch
was not a residual but the unfixed defect, and this round closed it.

## 2. F1 residual — a guard at the persistence layer

`TraceContextHolder.set` is public core API with no grammar enforcement, so fix
round 1's Kafka-side validation was not the only route to the column: an embedder
or a future traced transport can set a value directly and reintroduce the
insert-fails → never-acked → dead-lettered → **signal discarded** path.

**Fix:** `SignalManager.deliverSignal` drops a captured value longer than
`TraceContextHolder.MAX_LENGTH` (128, the column width) and logs it, persisting
the signal with no trace context. This is a bound on *size*, not an
interpretation of *contents*, so RULING 2 holds — and degrading to absence rather
than erroring is exactly what RULING 2 requires. `MAX_LENGTH` is documented,
along with the constraint on `set(...)`'s Javadoc and the reason the trade is the
only safe one.

Pinned at three levels: two `SignalManagerTest` cases (over-long dropped but the
signal still stored *and still consumable*; a value exactly at the limit still
persisted, so the guard bounds rather than truncates the legitimate range), and
`KafkaTraceLinkageIT.oversizedTraceContextViaHolderNeverDiscardsTheSignal`, which
drives the **real** `VARCHAR(128)` column — the in-memory store used by the unit
pins has no width and cannot prove this.

**This round also produced the evidence fix round 1 could only infer.** With the
guard reverted (`evidence/task-5-fix2-red-linkage.log`):

```
io.b2mash.maestro.store.jdbc.AbstractJdbcWorkflowStore$UncheckedSqlException: Update failed: INSERT INTO maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
```

The failing insert is now directly greppable. The Postgres `22001` / "value too
long" text is still **not** in the archived log — the cause chain is not printed
— so, as in fix round 1, only the string above is quoted as fact.

## 3. Test counts — recounted from the XML, stated once

Authoritative per-module totals, from the JUnit XML appended to each round's
verify log:

| Module | Original round | Fix round 1 | Fix round 2 |
|---|---|---|---|
| `maestro-core` | 330 | 332 | **334** |
| `maestro-spring-boot-starter` | 100 | 103 | **103** |
| `maestro-messaging-kafka` | 36 | 39 | **39** |
| `maestro-store-postgres` | 57 | 57 | **57** |
| `maestro-integration-tests` | 96 | 98 | **100** |

Net new tests: original round **50**; fix round 1 **10** (core +2, starter +3,
kafka +3, integration +2 — the "11 / integration +3" in that section was wrong
and is corrected there); fix round 2 **4** (core +2, integration +2). Task 5
total: **64**.

## Verification

Required command (`evidence/task-5-fix2-verify.log`):

```
$ ./gradlew :maestro-core:test :maestro-spring-boot-starter:test :maestro-messaging-kafka:test :maestro-store-postgres:test :maestro-integration-tests:test --rerun-tasks

BUILD SUCCESSFUL in 1m 53s
44 actionable tasks: 44 executed
```

Full build (`evidence/task-5-fix2-build.log`):

```
BUILD SUCCESSFUL in 1m 56s
134 actionable tasks: 134 executed
```

All temporary RED patches reverted from pristine copies; `grep -c "TEMPORARY RED"`
returns `0` for `TracingEngineObserver.java` and `SignalManager.java`.

---

## Known limitations (deferred by coordinator ruling — for Task 8 to fold into `docs/observability.md`)

1. **A workflow started from a workflow thread gets no segment of its own.**
   `forkPoint` is an `InheritableThreadLocal`, so any thread created by a workflow
   thread is treated as a parallel branch. An embedder child-workflow pattern —
   starting a workflow from inside another workflow's thread — would therefore
   give the child run no run segment, and its activity spans would be parented
   under the *parent* workflow's segment. There is no in-tree caller that does
   this; flagged so the behaviour is documented rather than discovered.
2. **The first recovered segment on startup can lack `maestro.service.name`**
   (F6, deferred). `serviceName` is a node-wide `volatile` set from
   `workflowStarted`/`workflowResumed`, and `workflowResumed` is emitted after
   `thread.start()`, so the first recovered run can open its segment before the
   value is populated.
3. **Branch-level span events are dropped.** Because a branch never holds a
   segment, `maestro.signal.consumed` / `maestro.timer.fired` /
   `maestro.timer.cancelled` raised inside a parallel branch have nowhere to be
   recorded and are skipped. Branch activity spans and their parenting are
   unaffected.
4. **A real fork/join observation boundary is post-1.0 work.** Both the branch
   detection above and limitation 1 exist only because the engine has no callback
   marking the start and end of a parallel branch. Adding one would make branch
   handling exact instead of inferred, and would let branch segments exist and be
   closed properly — a design-surface change, deliberately not taken in this
   cycle.

## Concerns after this round

1. All three items in this round's brief are closed, with a discriminating pin
   for each (both REDs verified to fail against the pre-fix code).
2. `docs/observability.md` still owes RULING 8's note (event attributes are
   segment tags, last-write-wins), the `maestro.observability.*` properties, and
   the four limitations above — all Task 8's.
3. Concurrent parking branches die with `OptimisticLockException` (two branches
   writing the instance status row at once). Found while building this round's
   fixture; it is an engine limitation entirely separate from tracing, is not
   something this task introduced, and is recorded here only so it is not
   rediscovered as a tracing bug.

---

# Fix round 3

**Status: COMPLETE**

pwd: `/Users/rakheendama/Projects/2026/maestro/.claude/worktrees/release-hardening`
branch: `worktree-release-hardening`
HEAD before this round: `249904c`
Implementation commit: `008d8eb`; this report + evidence commit follows.

One item: fix round 2's branch latch was a regression. Evidence archived:
`task-5-fix3-red.log`, `task-5-fix3-green-starter.log`,
`task-5-fix3-green-integration.log`, `task-5-fix3-verify.log`,
`task-5-fix3-build.log`.

## The regression, reproduced independently

`DefaultWorkflowOperations:618` advances the main thread's sequence past the
branch spaces after a join:

```java
var nextParentSeq = parentSeq * BRANCH_MULTIPLIER + (branchCount + 1) * BRANCH_MULTIPLIER;
ctx.setSequence(nextParentSeq);
```

So every main line following *any* `parallel()` runs at seq ≥ 2000. Fix round 2
latched `branchThread = true` on any `activityStarted` at seq ≥ 1000 — which
meant the **main workflow thread** of every fork-then-park workflow was
permanently misclassified as a branch and stopped opening segments, dropping the
rest of the run into a separate trace.

I reproduced it before fixing, with the reviewer's probe shape as a unit pin
(`evidence/task-5-fix3-red.log`, 1 of 20 failing):

```
TracingEngineObserver builds the design §3.2 span topology > a main thread that forks keeps its segments across the join and later parks — post-join sequence numbers must not classify it as a branch FAILED
...
org.opentest4j.AssertionFailedError: the whole run must stay in one trace; a main thread misclassified as a branch stops opening segments and its later work starts a new trace. Trace ids were [29f5f21ef3bfdc26f62c76d6529fcc4d, 4cbbb74c24300c50a1a93a18b4c5a8b1] ==> expected: <1> but was: <2>
```

Two trace IDs where there must be one — the reviewer's finding, confirmed
independently rather than taken on trust.

## The fix — classification keyed off fork-point ownership

The invariant to hold was: *a thread that owns the fork must keep its segment
across joins and subsequent parks; only genuine branch threads are gated.* The
sequence number cannot express that, because the main line reaches the same
range. `isBranchThread` now decides in strict order of confidence:

1. **Inherited fork point** (`ownerThreadId != this thread`) — the thread was
   created by a thread that had an open segment. Definitively a branch.
2. **Own fork point** — the thread has opened a segment itself. Definitively the
   main run thread, and it *stays* main across joins and every later park however
   high its sequence numbers climb. Any latch a post-join sequence set is cleared
   here. This is the case round 2 destroyed.
3. **Neither** — nothing observable has happened on any thread yet, i.e. a
   workflow whose very first statement is `parallel()`. Only here does the
   sequence-derived latch get a vote.

The latch is additionally never *set* on a thread that already owns its fork
point, so the ambiguous case is the only one it can ever influence.

Why the fork-first pin from round 2 still passes: such a branch never opens a
segment, so it owns no fork point and falls to case 3, where the latch still
gates it. Both behaviours now coexist, which the sequence-only rule could not
achieve.

Chose this over the reviewer's other suggested shape (reset the latch on
park/unpark for a fork-point owner) because it removes the ambiguity at the
source rather than papering over it at two more call sites: ownership is a fact
the adapter already records, whereas "reset on park" would still leave a
mis-latched thread wrong until its next park.

## Residual, stated accurately this time

One shape remains ambiguous and is documented rather than guessed at, and it is
wider than "a workflow whose first statement is `parallel()`": any recovered run
whose first live step after replay sits past a join hits the identical case,
because `DefaultWorkflowOperations:205` returns silently for a replayed completed
sleep, so a thread resuming past a join opens no fork point either — the same
structural gap a fork-first branch has. In both shapes the thread owns no fork
point (it never opened a segment before forking, or replay never opened one for
it) and its post-join activities sit at seq ≥ 2000, so case 3 latches it as a
branch and it opens no segments — its activity spans become roots and its span
events are dropped. Nothing is orphaned and no span leaks; spans still export —
but each such activity exports as its own root span, so the run fragments into
one trace per activity instead of staying in one: the reviewer's probe showed 2
post-join activities producing 2 separate traces, not one flat trace. Closing
this needs the fork/join observation boundary already listed as post-1.0 work
(known limitation 4), because no fact available to the adapter distinguishes that
thread from a branch.

This is the fourth time this paragraph has been restated. The two earlier
versions are marked **CORRECTED** in place above rather than deleted, so the
record shows what was believed when.

## Verification

Required command (`evidence/task-5-fix3-verify.log`):

```
$ ./gradlew :maestro-core:test :maestro-spring-boot-starter:test :maestro-messaging-kafka:test :maestro-store-postgres:test :maestro-integration-tests:test --rerun-tasks

BUILD SUCCESSFUL in 1m 50s
44 actionable tasks: 44 executed
```

Per-module totals appended to that log:

```
maestro-core: tests=334 failures=0 errors=0 skipped=0
maestro-spring-boot-starter: tests=104 failures=0 errors=0 skipped=0
maestro-messaging-kafka: tests=39 failures=0 errors=0 skipped=0
maestro-store-postgres: tests=57 failures=0 errors=0 skipped=0
maestro-integration-tests: tests=100 failures=0 errors=0 skipped=0
```

Full build (`evidence/task-5-fix3-build.log`):

```
BUILD SUCCESSFUL in 1m 56s
134 actionable tasks: 134 executed
```

Net new tests this round: **1** (starter 103 → 104). Task 5 total: **65**.
No temporary patches were used this round — the RED ran against the unfixed HEAD
directly, so there was nothing to revert; `grep -c "TEMPORARY RED"` on
`TracingEngineObserver.java` returns `0`.

## Concerns after this round

1. **Three of this task's pins first passed against broken code** (two in round
   2, and round 2's F2 gate itself shipped a regression a pin did not catch).
   The pattern in all three: I asserted the *absence* of a symptom without first
   confirming the assertion could observe it. Running every new pin against the
   unfixed code before fixing — done from round 3's start — is what caught this
   one, and is the discipline I should have applied from the beginning.
2. The residual above is the only known classification gap — wider than
   fork-first workflows alone, since any recovered run whose first live step
   after replay sits past a join hits it too — and it fragments the run into
   one trace per post-join activity rather than leaking or orphaning spans.
3. Unchanged from round 2: `docs/observability.md` still owes RULING 8's note,
   the `maestro.observability.*` properties, and the four known limitations —
   all Task 8's.
