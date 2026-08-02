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
