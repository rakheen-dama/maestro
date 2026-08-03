# Task 2 Report — Observability + Versioning Design Doc

Deliverable: `.superpowers/sdd/release-hardening/observability-versioning-design.md`
(9 sections; every required section lands on a decision).

## Decisions made

1. **EngineObserver** (`io.b2mash.maestro.core.observe`): all-default-method
   interface (self-no-op) + `NOOP` constant + `CompositeEngineObserver`
   (catches `RuntimeException` per delegate, never `Error`). Replay handling
   = **flag-per-callback** (`boolean replayed`); adapters skip, engine always
   emits. Registration via new widest `WorkflowExecutor` constructor param,
   threaded to SignalManager, DefaultWorkflowOperations, SagaManager,
   ActivityInvocationHandler (via ActivityProxyFactory),
   WorkflowInstanceLockManager. TimerPoller deliberately NOT instrumented
   (workflow-side `recordTimerFired` is the once-only count point).
   **Stays separate** from `WorkflowLifecycleEvent`/`GatedWorkflowMessaging`
   (different consumers, transport, gating; §1.5 rationale).
2. **Meter catalog**: final names/types/tags table in §2.2; tags `workflow`
   (=type), `activity`, `signal`, `outcome`, `reason` only — never
   workflowId/runId/timerId. Added `maestro.recovery.scanned` beyond spec
   minimum (justified by MetricsSampler parity clause). Gauges =
   **in-JVM state-tracking** (`runningCount()`, new
   `ParkingLot.waiterCount()`), registered by a `MaestroEngineGauges` holder
   bean — not store-polling (per-node semantics, no scrape-time DB load).
3. **Tracing**: **Micrometer Tracing API** (`micrometer-tracing`
   Tracer/Propagator), not Observation API (fused metric+trace handlers
   conflict with independent config seams; no remote-parent story across
   parks) and not direct OTel SDK (bypasses Boot management, binds to one
   bridge). Span topology: `maestro.workflow.run` segment spans
   (start→park→unpark chain via ThreadLocal on the surviving virtual
   thread), `maestro.activity` child spans, span events for signal/timer.
   No spans when `replayed=true`.
4. **Kafka propagation**: headers `traceparent`/`tracestate`(+`baggage`),
   W3C grammar pinned by regex in `KafkaTracePropagationContractTest`.
   Inject in `KafkaWorkflowMessaging.send` via optional
   `KafkaTracePropagation` collaborator; extract in the listener wrapper;
   restore across the durable hop by persisting traceparent on the signal
   row (new `trace_context` column, migration V4) → surfaced to the tracing
   observer at `consumeSignal` → resumed segment gets the remote parent.
   `TraceContextHolder` (plain ThreadLocal<String> in core) bridges listener
   thread → `deliverSignal` without SPI or tracing deps in core.
5. **VERSION_MARKER**: new `EventType`; payload
   `{"changeId":...,"version":N}`; stepName `$maestro:version:{changeId}`
   (DeterminismChecker treats markers as decisions with zero checker
   change). `version()` = peek-don't-consume algorithm: matching marker →
   consume+return recorded; other event at slot → `DEFAULT_VERSION(-1)`
   without consuming (old histories stay byte-stable); no event → memoize
   maxSupported. Per-run `versionCache` gives repeated-call determinism.
   Min-guard: `UnsupportedWorkflowVersionException extends MaestroException`
   (a genuine deterministic failure — composes with admin retry), NOT an
   Error.
6. **Stand-down**: sentinel `EventType.UNKNOWN` + total
   `EventType.fromStoredName()`; exact mapper site
   `AbstractJdbcWorkflowStore.mapEvent` (never throws; WARN carries raw
   string); write-side guard rejects UNKNOWN in both stores.
   `UnknownWorkflowHistoryException extends MaestroControlFlowError` — new
   sealed Error base also adopted by ExecutorShutdownException /
   WorkflowTerminatedException, collapsing broad-catch enumeration to one
   type. Detection via `UnknownHistoryGuard.requireKnown` at every
   getEventBySequence replay read (enumerated table, §6.3). Catch-site audit
   §6.4: 6 already-correct sites listed, 3 must-change sites
   (RetryExecutor.executeWithRetry, SagaManager sequential+parallel outcome
   recording, WorkflowExecutor.executeWorkflow new arm + nested
   compensation arm). Lock release = existing finally, no new code.
   Re-adoption churn documented as the operator signal.
7. **Config seams**: `maestro.observability.{metrics,tracing}.enabled`
   records (canonical-ctor-only, `defaults()` — BUG8 respected); new
   `MaestroObservabilityAutoConfiguration` with
   ConditionalOnClass+Bean+Property (matchIfMissing=true) so
   "default true when tracer present" is structural. Exact
   libs.versions.toml entries for micrometer-core / micrometer-tracing /
   -test / -bridge-otel (BOM-managed, no version refs).
8. **Test strategy**: named pin tests per area incl.
   `ObserverReplayNoDoubleCountIT` (crash/recover, counters exactly once)
   and `UnknownEventStandDownIT` (SQL-injected future type, stand-down then
   adoptable-and-completable after row removal), plus contract, binding,
   catch-ordering, parallel-branch-allocation, and DeterminismChecker
   tests, each with its module and package.

## Open questions flagged for coordinator ruling (design §9)

- OPEN-Q-1: spec SHOULD "inject VERSION_MARKER" is unimplementable as a
  permanent fixture (type becomes known in the same build) — proposed
  dedicated `EVT_FROM_A_NEWER_MAESTRO` fixture + transient RED-phase
  VERSION_MARKER evidence.
- OPEN-Q-2: confirm the one schema change (signal `trace_context` column,
  V4 migration + WorkflowSignal record component) needed for durable
  remote-parent restoration.
- OPEN-Q-3: confirm re-parenting the two shipped control-flow Errors under
  sealed `MaestroControlFlowError` (behavior-preserving) is in scope.

## Files read (grounding)

Spec + brief: `docs/release-hardening-spec.md` (full),
`task-2-brief.md`. Core: WorkflowExecutor, ActivityInvocationHandler,
SignalManager, DefaultWorkflowOperations, TimerPoller, TimerManager,
RecoveryPoller, SagaManager (compensate/executeSequential/executeParallel),
WorkflowContext, WorkflowInstanceLockManager, ParkingLot (unwrap site),
GatedWorkflowMessaging, LifecycleEventPublisher (via executor usage),
WorkflowLifecycleEvent, LifecycleEventType, EventType, WorkflowEvent,
TaskMessage, SignalMessage, ExecutorShutdownException, RetryExecutor.
Store: AbstractJdbcWorkflowStore (mapEvent/mapInstance/mapTimer, appendEvent,
deleteFailureEvents), migration list (V1–V3). Starter:
MaestroAutoConfiguration, MaestroProperties (BUG8 comment). Kafka:
KafkaWorkflowMessaging. Test: DeterminismChecker, module layouts of
maestro-core tests, maestro-integration-tests, starter tests.
Build: gradle/libs.versions.toml.
