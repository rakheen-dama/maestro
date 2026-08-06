# Release Notes — Maestro

## Unreleased

### Added — `maestro.messaging.redelivery.enabled` flag, and a startup check for missing `.DLT` topics

The dead-lettering error handler installed on every Maestro-managed consumer
container ran unconditionally, with nothing to create — or even check for —
the `.DLT` topics it depends on. A missing dead-letter topic surfaced only
once a handler's attempt budget was first exhausted, as a stalled, noisily
retrying consumer, at the worst possible moment to discover the gap.

- **`maestro.messaging.redelivery.enabled`** (default `true`) is a new, first
  field of the `maestro.messaging.redelivery.*` block, gating both
  transports. Set to `false` to restore at-most-once handler semantics: on
  Kafka, the listener container gets a zero-retry `DefaultErrorHandler` with
  no `DeadLetterPublishingRecoverer`; on Postgres, a failing row is marked
  `FAILED` after exactly one attempt instead of being retried and
  dead-lettered. This is the operator's explicit opt-out, not a recommended
  default.
- **`KafkaDeadLetterTopicCheck`** is a new warn-only startup probe, wired at
  every point Maestro subscribes to a topic — the engine's own
  `subscribe`/`subscribeSignals` and every `@MaestroSignalListener`
  container's activation. It WARNs by name when a topic's `.DLT` companion
  does not exist, is bounded to 5 seconds, never fails startup, and is
  skipped entirely when redelivery is disabled. Full contract and the
  `.DLT` pre-creation checklist: [`docs/configuration.md` § Kafka
  Dead-Letter-Topic Check](configuration.md#kafka-dead-letter-topic-check).
- **`sample-loan-origination`'s and `demo`'s compose stacks now pre-create
  `.DLT` companions** for every topic they were missing one for — the six
  engine tasks/signals topics and the two `@MaestroSignalListener` business
  topics (`loans.verification.results`, `loans.underwriting.decisions`) —
  closing the exact gap the new check now warns about.
- **Was:** [Issue 24](open-issues.md#issue-24).

### Fixed — Valkey lock honours `spring.data.redis.host`/`port`/`password`/`username`/`ssl.enabled`/`database`

The Valkey lock's connection URI was previously resolved from only
`spring.data.redis.url` and `maestro.lock.valkey.uri` — the individual
`spring.data.redis.host`/`port`/credential properties (which
`docs/configuration.md`'s own Complete Example configured) were silently
ignored, and the lock connected to `redis://localhost:6379` regardless.
A third resolution step now builds the URI from
`spring.data.redis.host` + `port`/`password`/`username`/`ssl.enabled`/`database`
when neither URI property is set. **Behaviour change:** a deployment that set
`spring.data.redis.host` expecting it to apply now genuinely connects there
instead of falling back to localhost. The full resolution order is documented
in [`docs/configuration.md` § Valkey Connection Resolution](configuration.md#valkey-connection-resolution).

### Removed

- **RabbitMQ messaging support has been removed** — the `maestro-messaging-rabbitmq`
  module and the `sample-rabbitmq-order-service` sample are deleted. Nothing has
  been published under this artifact yet, so this is a deletion, not a
  deprecation: there is no migration path because there is no installed base to
  migrate. **Rationale:** each additional transport carries its own real-backend
  verification cost — a dedicated Testcontainers suite, its own redelivery/
  dead-letter design, its own multi-instance and chaos coverage — and the
  multi-instance verification cycle (PR #30) is the evidence for how much that
  costs per transport. Shrinking the matrix to Kafka and Postgres before the
  first public release keeps that cost bounded to backends the project can
  actually keep verified. The `WorkflowMessaging` SPI itself is untouched and
  remains transport-agnostic — a community `maestro-messaging-rabbitmq` (or any
  other broker) adapter implementing the three-method SPI remains possible; it
  is simply no longer shipped or verified in this repository.

### Fixed — A terminate racing saga compensation could no longer run compensations, but could before

`SagaManager.transitionToCompensating` re-reads the instance and re-checks for
`TERMINATED` before writing `COMPENSATING`, but the read and the write are not
atomic — a cross-node `WorkflowExecutor.terminateWorkflow` could land in that
gap, making the write lose its optimistic-lock check. That lost
compare-and-set was silently swallowed, and the failing run's compensations
ran anyway: refunds issued, reservations released, for a workflow an operator
had explicitly asked to stop.

- The lost compare-and-set is now retried against a **fresh read**, with the
  `TERMINATED`/other-terminal guard re-evaluated on **every** attempt (not
  just the first) — the same bounded-retry idiom (`STATUS_WRITE_ATTEMPTS = 5`,
  immediate, no backoff) `InstanceStatusWriter.write` already uses for the
  sibling conflict on the same row. A `TERMINATED` observed on any attempt now
  throws `WorkflowTerminatedException`, propagating out of `compensate()`
  uncaught, and no compensation runs.
- **Exhaustion policy differs deliberately from `InstanceStatusWriter`:** this
  write gates entry into the compensation phase, so on exhaustion the method
  logs an error and rethrows the last `OptimisticLockException` instead of
  standing down and proceeding — nothing terminal is written, the instance
  stays active, and recovery retries the transition with a fresh read.
- **Was:** [Issue 22](open-issues.md#issue-22).

### Fixed — Kafka client configuration now honours `spring.kafka.*`, and Kafka observation/tracing default on

**Behaviour change for every Maestro + Kafka application** — no config
migration is required, but two things you may not have noticed silently not
working now do:

- **`spring.kafka.*` reaches Maestro's engine producer/consumer.**
  `maestroKafkaProducerFactory` / `maestroKafkaConsumerFactory` are now built
  from Spring Boot's bound `KafkaProperties` — bootstrap servers, compression,
  batching, retries, SSL/security settings, arbitrary
  `spring.kafka.producer.properties.*` / `spring.kafka.consumer.properties.*`
  entries, all of it. Previously these beans ignored `spring.kafka.*` entirely
  and only ever used a hardcoded bootstrap-servers value. A small set of wire
  invariants the engine's protocol depends on (`String`/`byte[]`
  (de)serializers, `acks=all`, the engine's `group.id`) are still forced
  **last**, so no user property can corrupt engine topics. Full precedence
  table: [`docs/configuration.md` § Kafka client
  configuration](configuration.md#kafka-client-configuration).
- **Kafka observation (and therefore cross-service tracing) is on by default
  when Micrometer tracing is active** — a `Tracer` *and* a `Propagator` bean
  both exist and `maestro.observability.tracing.enabled` is not `false`, the
  same condition that activates Maestro's own `KafkaTracePropagation` bean
  (Spring Boot's unconditional no-op `Tracer` alone does not trigger this).
  `maestroKafkaTemplate` and the `@MaestroSignalListener` consumer containers
  now default `observation-enabled` to `true` under that condition, instead of
  requiring a hand-written `maestroKafkaTemplate` bean override to get a
  connected trace across services. `@MaestroSignalListener` also now extracts the
  inbound `traceparent` (and `tracestate`/`baggage`) from every record,
  independent of container observation, so `trace_context` is populated on
  the signal row rather than staying `NULL`. Set
  `spring.kafka.template.observation-enabled=false` /
  `spring.kafka.listener.observation-enabled=false` to opt out. Full contract:
  [`docs/observability.md` § Cross-service trace propagation
  (Kafka)](observability.md#cross-service-trace-propagation-kafka).
- **The sample-level observed-template workaround is gone.** The three
  identical bean-shadowing config classes previously shipped in
  `sample-loan-origination`'s services (a hand-rolled `maestroKafkaTemplate`
  bean with observation forced on), and the explicit
  `spring.kafka.producer.*-serializer` / `spring.kafka.consumer.*-deserializer`
  entries in the Kafka samples' `application.yml` files, are removed — the
  engine now applies both without any per-service code. If your own service
  copied that pattern, you can delete it too: a bean named
  `maestroKafkaTemplate` still wins by
  `@ConditionalOnMissingBean(name = "maestroKafkaTemplate")` if you keep one,
  but it is no longer necessary to get a connected trace.
- **Was:** [Issue 23](open-issues.md#issue-23).

### Added — Observability: Micrometer meters and OpenTelemetry tracing

Full reference: [`docs/observability.md`](observability.md).

- **A framework-free observer seam in `maestro-core`.** New package
  `io.b2mash.maestro.core.observe` with an `EngineObserver` interface the
  engine calls at execution boundaries — 22 `default` no-op methods, a
  composite, and the `*Info` records. `maestro-core` gains **no** dependency on
  Micrometer, OpenTelemetry, or Spring; every adapter lives in the starter and
  in `maestro-messaging-kafka`. Embedders can implement it directly.
- **Micrometer meters under `maestro.*`**, auto-configured when a
  `MeterRegistry` is present: counters
  `maestro.workflow.started|completed|failed|compensated|terminated`,
  `maestro.signal.consumed`, `maestro.timer.fired`, `maestro.recovery.scanned`,
  `maestro.recovery.adopted`, `maestro.lock.renew.failures`,
  `maestro.standdown`; timer `maestro.activity.duration`; gauges
  `maestro.workflows.running` and `maestro.workflows.parked`. Nothing is ever
  tagged by `workflowId` or `runId`. The two gauges are **node-local, in-JVM**
  values — dashboard them per pod and sum across pods for a cluster total.
- **Replayed steps are never counted and never traced.** A recovered workflow
  replaying N activities does not double-count. Pinned end-to-end over a real
  Postgres (crash node A, recover on node B, assert the replayed step's timer
  count stays 1).
- **Spans via the Micrometer Tracing API** (`io.micrometer.tracing.Tracer`, so
  it bridges to whichever OpenTelemetry or Brave bridge your application
  ships): `maestro.workflow.run` per run segment (the stretch of a run between
  parks), `maestro.activity` per live activity execution, and
  `maestro.signal.receive` on the Kafka listener side. The MDC keys
  `workflowId` / `runId` / `activityName` appear as span attributes, so logs
  and traces join.
- **Cross-service trace propagation through Kafka.** W3C `traceparent` (plus
  `tracestate` / `baggage` when the propagator emits them) is injected into
  record headers on publish and extracted on consume, and the context is
  **persisted on the signal row** so it survives a durable park — a signal
  published by service A and consumed by service B's parked workflow renders as
  **one connected trace**, not two. The exact header names and the
  `traceparent` grammar are pinned by contract tests rather than inherited from
  transport defaults. Absent, malformed, or oversized values degrade to an
  untraced delivery with a fresh root span — never to an error and, critically,
  never to a discarded signal.
- **New properties** `maestro.observability.metrics.enabled` and
  `maestro.observability.tracing.enabled`, both defaulting to `true` and both
  silently inert without the beans they need. The tracing flag gates the Kafka
  header injection too. With no tracing beans the Kafka wire format is
  byte-identical to a pre-tracing build.
- **Known limitations are documented rather than left to be discovered** — see
  `docs/observability.md` → Known limitations. The most consequential: the
  engine has no fork/join observation boundary, so a workflow that forks as its
  first statement, or any recovered run whose first live step after replay sits
  past a join, has no fork point to own; each such activity exports as its own
  **root** span, fragmenting the run into one trace per activity. Closing it is
  post-1.0 work.

### Added — Workflow versioning: `workflow.version()`

Full reference: [`docs/concepts.md` → Versioning Workflow Code](concepts.md#versioning-workflow-code).

- **`WorkflowContext.version(changeId, minSupported, maxSupported)`** — the
  Temporal-proven memoized change-branching model. The first live evaluation
  records `maxSupported` as a `VERSION_MARKER` event and returns it; every
  replay returns the **recorded** value forever, regardless of what the code's
  `maxSupported` has moved on to. Histories that predate the change resolve to
  `WorkflowContext.DEFAULT_VERSION` (`-1`) **without consuming a sequence
  slot**, so introducing a `version()` call into existing code leaves in-flight
  instances' event logs unshifted. This is what makes it usable: you can add
  the gate to code that already has instances running through it.
- Version decisions are visible to `DeterminismChecker` with no checker change
  — the marker's step name is `$maestro:version:{changeId}`.
- Raising `minSupported` above an instance's recorded version throws the new
  `UnsupportedWorkflowVersionException`, naming the changeId, the recorded
  version, and the supported range. That is an ordinary deterministic workflow
  failure (it ends `FAILED`, saga compensation runs); the admin **Retry**
  action composes with it once the old branch is restored, because retry clears
  the failure memos but never the version marker.

### Changed — Unreadable history stands down instead of failing the workflow

- **A node that cannot interpret a workflow's persisted history no longer
  records it `FAILED`.** Previously, an `event_type` string absent from this
  build's enum threw an `IllegalArgumentException` that looked like any other
  exception escaping a workflow method — so the engine marked the workflow
  `FAILED` **and ran its compensations**, unwinding real work (refunds issued,
  reservations released) for a workflow that never failed and was, on the other
  half of the fleet, perfectly healthy. It now **stands down**: nothing is
  written, no compensation runs, the instance keeps its recoverable status, the
  instance lock is released, a `WARN` is logged, and
  `maestro.standdown{reason=...}` is incremented. An upgraded node adopts and
  finishes the workflow through the ordinary lock-TTL / recovery-poller
  machinery, unchanged.
- The same guard now covers **every path that deserializes a persisted payload
  it did not itself just write**, not only the replay caller: the activity
  duplicate-adopt branch, the persisted workflow input read on every recovery
  run, and the persisted signal payload. This matters because the duplicate-adopt
  path is reachable with **no author error** — an old node reads a sequence
  empty, executes live, and loses the append race to a newer node whose event at
  that sequence is a type it does not define.
- **An unmappable instance `status` no longer aborts the whole recovery pass.**
  `WorkflowStatus` mapping is now total: a status string a newer node wrote
  causes that **one** instance to be skipped with a `WARN` carrying the raw
  value, and the pass continues for every other workflow on the node.
  Deliberate trade, documented in the mapper's Javadoc: while a node cannot map
  the row, `getInstance` returns empty, so an operator API asking *that* node
  about an existing workflow reports it **"not found"**. Every caller already
  has a defined, non-destructive answer for an absent instance (a signal is
  treated as pre-delivery and stored, never discarded).
- **Control-flow signals now share a sealed base.** `MaestroControlFlowError
  extends Error` permits exactly three types: `ExecutorShutdownException`,
  `WorkflowTerminatedException`, and the new `UnknownWorkflowHistoryException`.
  Behaviour-preserving — every existing catch site is still exact-type — but a
  broad `catch (Throwable)` collector inside the engine now needs *one* check
  rather than an enumeration that drifts each time a signal is added. **If you
  maintain code that catches broadly around Maestro calls, check for
  `MaestroControlFlowError` and rethrow.**
- Operator guidance, including the one alarm that is easy to misread, is in
  [`docs/operations.md` §10](operations.md#10-versioning-and-mixed-version-deploys).
  Summary: a rising `maestro.standdown{reason=unknown_event_payload}` on a fleet
  you know to be **homogeneous** means "an incompatible payload change needs
  `workflow.version()`", **not** "wait for the deploy to finish" — otherwise the
  new stand-down behaviour turns a visible failure into a silent zombie.

### Changed — `maestro-test` waits for the terminal *event*, not the terminal status

- **`TestWorkflowHandle.awaitCompletion(...)` and `getResult(...)` now wait for
  the workflow's `WORKFLOW_COMPLETED` / `WORKFLOW_FAILED` event to reach the
  event log**, not merely for the instance row to read `COMPLETED`/`FAILED`.
  The engine finalises a run with two separate, non-transactional writes and the
  instance row goes first, so returning on the status alone handed you a log the
  engine had not finished writing — a test doing
  `handle.awaitCompletion(...); handle.getEvents()` could legitimately miss the
  terminal event and fail intermittently on a loaded CI machine.
  `WorkflowStatus.TERMINATED` is exempt: terminating appends no event, so there
  is nothing to wait for.
- **Behaviour change to be aware of:** both methods can now throw
  `TimeoutException` in a case where they previously returned — specifically
  when the row is terminal but the terminal event never lands within the
  timeout. That is an engine defect rather than a timing artefact, and it now
  fails loudly instead of handing you a truncated log. If a test starts timing
  out here, the fix is not a longer timeout. No signature changed;
  `TimeoutException` was already declared on both methods.

### Database Migrations

One new Flyway migration:

- **`V4__signal_trace_context.sql`** (`maestro-store-postgres`, version band
  1–99) — adds a nullable `trace_context VARCHAR(128)` column to
  `maestro_workflow_signal`. It carries the W3C `traceparent` captured when the
  signal was received, so a workflow parked on that signal can adopt the
  publishing service's span as a remote parent when it resumes — possibly hours
  later, possibly on another node. The column is opaque: no store or engine
  logic parses it, branches on it, indexes it or joins on it, and `NULL` (an
  untraced transport, or a build with tracing disabled) is normal and degrades
  to a fresh root span rather than to an error. The DDL is a single
  `ADD COLUMN` with no `DEFAULT` and no `NOT NULL`, so on Postgres 11+ it is
  metadata-only — instant, no table rewrite, safe to apply to a live table.

**The column must exist before a node running this version handles its first
signal.** `AbstractJdbcWorkflowStore` names `trace_context` unconditionally in
both the signal `INSERT` (`saveSignal`) and the signal `SELECT`
(`getUnconsumedSignals`); there is no feature detection and no fallback path.
Against a signal table that lacks the column, `saveSignal` throws — and because
signal delivery runs *inside the transport listener*, that failure means the
record is never acked, bounded redelivery exhausts, and the signal is
**dead-lettered**, i.e. discarded. That is the one outcome the engine otherwise
guarantees against.

- **Default deployments (`maestro.store.table-prefix` left at `maestro_`): no
  manual step.** Flyway applies `V4` automatically at startup, before the store
  serves traffic — same as `V1`–`V3`.
- **Deployments using a custom `maestro.store.table-prefix`: you must add the
  column to your own migrations before upgrading.** Maestro's shipped
  migrations hardcode the `maestro_` prefix — the note at the top of
  `V1__create_maestro_schema.sql` tells custom-prefix users to provide
  corresponding custom migrations, so such a deployment is already maintaining
  its own set. `V4` is a new one to mirror there:

  ```sql
  ALTER TABLE <your_prefix>workflow_signal ADD COLUMN trace_context VARCHAR(128);
  ```

  This is not an exotic configuration: `docs/cross-service.md` recommends "a
  unique `maestro.store.table-prefix` per service" for multi-service
  deployments. Miss the column there and the failure mode is the discarded
  signal described above, not a startup error.

### Upgrade notes — mixed-version deployments

- **`VERSION_MARKER` is a new event type — the same upgrade-together rule
  applies.** A workflow that has evaluated `workflow.version()` carries a
  `VERSION_MARKER` event in its history. A node running the previous version
  that adopts such a workflow cannot interpret it. Unlike the `SIGNAL_TIMEOUT`
  case below, it no longer *fails* the workflow — it stands down and leaves it
  for an upgraded node (see "Unreadable history stands down" above). That is a
  safety net for the rolling window, **not** a licence to run a mixed fleet:
  those workflows make no progress on the old nodes and simply wait. Upgrade
  every node of a service together, or drain the service first.
- **Upgrade every node of a service together (or drain it first) — the new
  `SIGNAL_TIMEOUT` event type is not readable by the previous version.** This
  release's Issue 19 fix (below) makes upgraded nodes write `SIGNAL_TIMEOUT`
  events into the shared event log. A node still running the previous version
  that adopts such a workflow — the normal cross-node recovery path in a
  multi-instance service — fails `EventType.valueOf("SIGNAL_TIMEOUT")` while
  reading the log and cannot replay the workflow until an upgraded node picks
  it up. In a rolling deploy that window is live traffic, so either upgrade
  all nodes of a service in one step, or drain the service (stop starting and
  recovering workflows) before mixing versions. Single-node deployments are
  unaffected.
- **Awaits that timed out *before* the upgrade replay live once *after*
  it.** The determinism guarantee below applies to `SIGNAL_TIMEOUT` events
  written by upgraded nodes; a pre-upgrade timed-out await left no memo, so
  its first post-upgrade replay re-executes at that slot exactly as the old
  version would have (and may consume a late-arrived signal there, the old
  behaviour). From that replay's own memo onward the new guarantee applies.

### Observability

- The Spring Boot starter now logs one INFO line at startup naming the
  effective distributed-lock backend (`Maestro distributed-lock backend: ...`,
  or `none (single-node mode)` when no backend is configured) — so a
  multi-instance deployment silently running without a lock backend is
  visible in the boot log.

### For third-party `WorkflowStore` implementers

- **`WorkflowStore.deleteFailureEvents`'s contract changed** (`docs/open-issues.md`
  Issue 19). This is the same abstract SPI method 0.4.0 introduced for Issue
  15's Retry command (not a new method — no recompile needed), but its
  required *behaviour* is now stricter. Previously the contract was "delete
  exactly the `ACTIVITY_FAILED`/`WORKFLOW_FAILED` events for this instance,
  nothing else." It now also requires: **if `WORKFLOW_FAILED` records a
  `SignalTimeoutException` as its cause, also delete the instance's
  highest-sequenced `SIGNAL_TIMEOUT` event.** `AbstractJdbcWorkflowStore` (and
  therefore `PostgresWorkflowStore`) and the `maestro-test` in-memory store
  both implement the new rule; **if you maintain a custom `WorkflowStore`
  implementation with your own `deleteFailureEvents`, you must add this
  exceptionType-gated deletion or `$maestro:retry` will silently loop forever**
  on any workflow that failed because a timeout-guarded `awaitSignal` timed
  out: replay will keep finding the `SIGNAL_TIMEOUT` memo at its sequence slot
  and deterministically re-raise the same timeout on every retry attempt,
  with no error surfaced (the command dispatcher reports `RETRIED`, not a
  failure). A plain workflow-code failure after a *caught* gate timeout is
  unaffected — the exceptionType gate exists precisely so that memo survives
  retry undisturbed (deleting it unconditionally would resurrect the Issue 19
  replay-divergence bug through the retry door — see the fix's rationale in
  `docs/open-issues.md` Issue 19).

### Bug Fixes

- **Two `parallel()` branches parking at the same time no longer fail the
  workflow** (`docs/open-issues.md` Issue 21). **Behavioural fix — read this if
  you use `parallel()`.** Every branch thread of a fork writes its own park
  status into the one instance row, and that write was an unguarded,
  un-retried read-modify-write. Two branches whose reads both preceded either
  write — the shape `docs/cross-service.md` sells, "fan out and await both
  replies" — made the loser throw `OptimisticLockException`, which escaped
  `parallel()` into workflow author code, into the executor's generic
  `catch (Exception)`, and so into a workflow durably recorded `FAILED` **with
  saga compensations run** — real refunds and inventory releases for work that
  never failed. `FAILED` is not active, so recovery never healed it, and manual
  retry refuses a compensated saga. The status write is now a bounded retry
  against a *fresh* read (the same idiom the terminal write already used), with
  the terminal guard re-evaluated on every attempt; on exhaustion it stands
  down rather than propagating, because the status column is an advisory hint
  for the recovery poller and the event log is the durable truth. Reproduced
  deterministically on the natural race before the fix, and the fix is
  mutation-proven.
- **Parked workflows now ride out transient store outages instead of
  failing** (`docs/open-issues.md` Issue 20). Previously, a `RuntimeException`
  raised by the store during a parked workflow's periodic wake-recheck probe
  — `standDownIfTerminated`'s instance read, the signal-poll read inside a
  parked `awaitSignal`'s recheck loop, or the timer-row recheck inside a
  parked `sleep()` (Issue 17) — propagated out of the park loop and durably
  marked a healthy, still-waiting workflow `WORKFLOW_FAILED`, running its
  compensations. These probes only ever notice something that happened on
  another node (a cross-node terminate, a signal or timer fire missed by the
  local notifier) and never write durable state, so a failed probe now logs a
  rate-limited `WARN` and the park simply continues to the next
  `maestro.signal.wake-recheck-interval` chunk — cross-node terminate
  convergence and cross-node wake are delayed by at most one interval, never
  by a failure. Found by the chaos harness's PR-gate re-proof of Issue 19's
  fix (a 39s store partition exceeding the connection pool's timeout); pinned
  by `ParkedProbeStoreOutageTest`.

- **Timed-out `awaitSignal` calls are now memoized and replay
  deterministically** (`docs/open-issues.md` Issue 19). Previously a timed-out
  await left its sequence slot empty; a recovery replay whose awaited signal
  had arrived late consumed it at that slot and diverged from the original
  execution — observed as a saga compensating at the wrong gate and leaking
  its reserved resource after a routine rolling restart. The live timeout path
  now appends a `SIGNAL_TIMEOUT` event (signal name + timeout in the payload)
  before throwing, and replay re-raises the timeout from the log alone; the
  late signal stays durably unconsumed for a later await of the same name.
  **Observable changes:** event logs no longer contain timed-out-await
  sequence gaps (tooling that asserted the old "designed gap" positions must
  expect contiguous logs with `SIGNAL_TIMEOUT` events instead), and
  `$maestro:retry` of a workflow that failed *because of* a signal timeout
  deletes that failing timeout memo so the retried await re-drives — earlier
  caught gate timeouts still replay identically after a retry. Found by the
  multi-instance chaos harness; pinned by `SignalTimeoutReplayDeterminismTest`.
  **If you maintain a custom `WorkflowStore`, read "For third-party
  `WorkflowStore` implementers" above — this is a behavioural contract
  change, not just an engine fix.**

- **A stale run whose event append collides with a concurrent runner now
  stands down instead of failing the workflow** (`docs/open-issues.md`
  Issue 18). Previously, when a node lost its instance lock mid-run (frozen
  past the lock TTL, partitioned, or the no-lock-backend race) and a peer
  adopted the workflow, the stale node's resumed run hit the store's
  `(workflow_instance_id, sequence_number)` unique guard — and the resulting
  `DuplicateEventException` was treated as a workflow failure: a workflow
  that had succeeded on the adopting node was durably marked `FAILED` and
  its saga compensations ran, reversing completed work. The executor now
  treats the collision as "another run owns this workflow's progress" and
  stands down like the shutdown/termination cases: nothing is written, no
  compensation runs, the concurrent runner's durable outcome governs (and
  with no concurrent runner the instance stays recoverable). Memoized
  activity-result adoption inside the activity proxy is unchanged. Found by
  the multi-instance chaos harness; pinned by
  `WorkflowExecutorDuplicateEventStandDownTest`.

- **Cross-node timer fires (and cancels) now wake the sleeping workflow**
  (`docs/open-issues.md` Issue 17). Previously, in any multi-instance
  deployment, a timer fired by the timer-poller leader on a node other than
  the one whose virtual thread was parked in `workflow.sleep()` durably
  marked the timer `FIRED` but woke nothing — the workflow wedged forever
  until its owning node restarted. A parked `sleep()` now re-reads its
  durable timer row every `maestro.signal.wake-recheck-interval` (default
  30s, unchanged — the property now bounds cross-node timer-fire,
  timer-cancel and terminate latency as well as signal latency), so a
  remote fire or cancel takes effect within one interval. Single-node
  behaviour is unchanged: a local fire still unparks instantly.

---

## 0.4.0

This release closes out `docs/open-issues.md` Issues 13, 14, and 15 — all
three found as a side effect of the 0.3.0 hardening pass rather than planned
up front. It includes a source-breaking SPI change and a new `Error`-based
control-flow signal — read [Breaking Changes](#breaking-changes) before
upgrading.

Maestro is pre-1.0; semantic versioning guarantees don't apply yet, but
breaking changes are still called out explicitly below so nothing is a
surprise.

---

### New Features

#### Cancelling a parked timer now unparks the workflow with a catchable outcome

Previously, cancelling a timer a workflow was currently parked on (via
`TimerManager.cancelTimer`) left the workflow stranded in `WAITING_TIMER`
forever — the same silent-stall shape as the original Issue 2, just with a
different trigger (`docs/open-issues.md` Issue 13). `TimerManager.cancelTimer`
is removed; the only supported entry point is now
`WorkflowExecutor.cancelTimer(String workflowId, String timerId, UUID
timerDbId)`. Cancelling a timer a workflow is parked on now unparks it and
raises a new, catchable `TimerCancelledException` at the `sleep()` call site —
memoized as a new `TIMER_CANCELLED` event at the same log slot `TIMER_FIRED`
would have used, so replay reproduces the same outcome deterministically, with
no store read. Left uncaught, the workflow fails with compensation, exactly
like any other uncaught workflow exception — a defined outcome, never a
silent stall. Cancelling a timer nothing is parked on is unaffected (still a
plain row update). See `docs/concepts.md` § "Cancelling a timer" for the full
semantics and `docs/open-issues.md` Issue 13 for the failure-mode table. Closes
Issue 13.

#### Admin dashboard Retry and Terminate are now functional end-to-end

`$maestro:retry` and `$maestro:terminate`, published by the admin dashboard's
Retry/Terminate buttons, were previously signals nothing consumed — a click
produced a success flash message for an action that had no effect
(`docs/open-issues.md` Issue 15). Both now reach a new
`AdminCommandDispatcher` in the Spring Boot starter, wired ahead of ordinary
signal delivery so an admin command is structurally invisible to a workflow's
own `awaitSignal()`:

- **Terminate** durably marks the target instance `TERMINATED` — no
  compensation, no activity interruption, prompt local eviction of a parked
  thread wherever it's running. Safe to call from any node (an optimistic
  version check is the arbiter, not the instance lock).
- **Retry** discards the target's memoized failure (`ACTIVITY_FAILED`/
  `WORKFLOW_FAILED`), moves it `FAILED → RUNNING`, and relaunches it like
  crash recovery: everything before the failure replays from its stored
  result, and the step that failed genuinely **re-executes live**, with a
  fresh retry-policy budget.

Both are idempotent under at-least-once redelivery, and an unroutable command
(unknown name, or a workflow type with no registration on the receiving node)
dead-letters instead of being silently dropped. See `docs/admin.md` for the
full semantics, a per-state idempotency table, and the (honestly stated) lack
of authentication/authorization on the command path. Closes Issue 15.

---

### Behaviour Changes

- **`SagaManager.compensate()` no longer risks re-invoking a completed
  compensation on replay.** A recovery re-run previously re-appended
  `COMPENSATION_STARTED`/`COMPENSATION_COMPLETED` unconditionally, relying on
  the event log's unique index to silently absorb the duplicate — harmless
  for the append itself, but a manually-registered compensation
  (`wf.addCompensation(Runnable)`, as opposed to a `@Compensate` activity) is
  not memoized the way an activity result is, so a compensation that
  completed just before an interruption could be genuinely re-run on
  recovery. Each compensation entry (sequential and parallel loops alike) now
  gets its own reserved sequence block that is checked before the action
  runs, mirroring how every other event-emitting engine path already skips
  replayed work. No API change; not observable except that a rare
  double-execution can no longer happen. Closes
  `docs/open-issues.md` Issue 14.
- **A terminal workflow (`TERMINATED`, `COMPLETED`, `FAILED`) can no longer
  be resurrected by a late signal, timer fire, or wake.** Required for
  Terminate to be trustworthy under redelivery and cross-node races; closes a
  hazard found while implementing Issue 15, not a separately numbered issue.
- **Admin dashboard Retry and Terminate buttons now measurably change
  workflow state.** See [New Features](#new-features) above. Closes Issue 15.
- **Cancelling a timer a workflow is parked on now produces a defined,
  catchable outcome instead of an indefinite stall.** See
  [New Features](#new-features) above. Closes Issue 13.

---

### Breaking Changes

Three changes are source-breaking or introduce a new catch-hazard. All are
deliberate, pre-1.0 rulings — see `docs/open-issues.md` Issues 13 and 15 for
the reasoning behind each.

1. **`WorkflowStore.markTimerCancelled` changes signature from `void` to
   `boolean`.** Source-breaking for any third-party `WorkflowStore`
   implementation — same precedent as 0.3.0's `findTimer` addition. The
   return value is the CAS outcome (`true` only if this call actually moved
   the row `PENDING → CANCELLED`), needed so a cancel racing a fire can tell
   whether it won. `AbstractJdbcWorkflowStore`/`PostgresWorkflowStore` and the
   `maestro-test` in-memory store both implement it; no action needed if you
   use either unmodified.
2. **`WorkflowStore.deleteFailureEvents(UUID instanceId)` is a new abstract
   SPI method.** Source-breaking for any third-party `WorkflowStore`
   implementation. It must delete exactly the events of type
   `ACTIVITY_FAILED` and `WORKFLOW_FAILED` for the given instance and return
   the count deleted — no other event type, including compensation and
   success events, may be touched. This is what makes Retry honest: without
   it, a retried workflow's failed step deterministically replays the stored
   failure instead of re-executing. `AbstractJdbcWorkflowStore` and the
   `maestro-test` in-memory store both implement it; no action needed if you
   use either unmodified.
3. **`TimerManager.cancelTimer(UUID)` is removed.** It was package-private
   with zero production callers (only exercised by its own test), so this is
   not expected to affect any application code — flagged here for
   completeness since it is an API removal. The replacement,
   `WorkflowExecutor.cancelTimer(String, String, UUID)`, is public and is now
   the only supported cancellation entry point.
4. **New `Error` type: `WorkflowTerminatedException`.** Same catch-hazard as
   0.3.0's `ExecutorShutdownException` (Breaking Change 2 in that release):
   it extends `java.lang.Error`, not `MaestroException`, specifically so an
   ordinary workflow-author `catch (Exception e)` around `awaitSignal()`/
   `sleep()` cannot intercept it and misrecord a `$maestro:terminate` command
   as a workflow failure. If your own code unwraps a reflection or
   `CompletableFuture` completion exception, check `instanceof Error` before
   `instanceof Exception` — see `CLAUDE.md` § Coding Standards, which now
   documents both control-flow signals under one shared rationale.

---

### New API Surface

- `WorkflowExecutor.cancelTimer(String workflowId, String timerId, UUID
  timerDbId)` → `boolean` — cancels a timer; returns whether this call's CAS
  won.
- `WorkflowExecutor.retryWorkflow(String workflowId, WorkflowRegistration
  registration)` → `RetryOutcome` (`RETRIED`, `NOT_FAILED`, `NOT_FOUND`,
  `ALREADY_RUNNING_LOCALLY`, `LOCK_HELD_ELSEWHERE`,
  `COMPENSATED_NOT_RETRYABLE`).
- `WorkflowExecutor.terminateWorkflow(String workflowId, @Nullable String
  reason)` → `TerminateOutcome` (`TERMINATED`, `ALREADY_TERMINAL`,
  `NOT_FOUND`).
- `TimerCancelledException extends MaestroException` — thrown from
  `sleep()`/`WorkflowContext.sleep()` when the timer being waited on was
  cancelled; catchable, unlike the two `Error`-based control-flow signals.
- `WorkflowTerminatedException extends Error` — see
  [Breaking Changes](#breaking-changes) above.
- `LifecycleEventType.TIMER_CANCELLED` — published when a parked timer is
  cancelled (live or healed on recovery).
- `LifecycleEventType.WORKFLOW_RETRIED` — published on a successful
  `$maestro:retry`; projected by the admin dashboard's `EventProjector` to
  status `RUNNING`.

---

### Operator Notes

- **Admin Retry and Terminate are now real actions**, not inert buttons.
  Retry is for transient failures (an external dependency that has since
  recovered) — it is not a way to correct bad workflow logic or bad input.
  Retrying a workflow whose saga already ran compensations is **not
  supported**: Maestro detects it (a `COMPENSATION_STARTED` event in the
  log) and refuses with the new `COMPENSATED_NOT_RETRYABLE` outcome — logged
  at `WARN` and acknowledged as a safe no-op, instance left untouched —
  rather than risk corrupting the replay (compensations re-run or get
  wrongly skipped, or the run's terminal event is lost). Terminate stops any
  active workflow, including one currently compensating, with no
  compensation and no activity interruption. Full semantics, an idempotency
  table, and the security posture (no authentication on the command path —
  restrict with Kafka ACLs on `maestro.signals.*` if this matters for your
  deployment) are in `docs/admin.md`.
- **No new configuration properties** in this release, and no database
  migration — `deleteFailureEvents` is a `DELETE` against existing columns,
  and the timer/event schema is unchanged.

---

### Known Limitations

`docs/open-issues.md` Issues 11 and 12 remain open by deliberate design
decision, unchanged by this release — no lock fencing, and recovery polling
scales linearly with the active workflow count. See 0.3.0's
[Known Limitations](#known-limitations-1) below, which still applies.

---

### See Also

- [`docs/open-issues.md`](open-issues.md) — Issues 13, 14, and 15 in full:
  what was wrong, the design docs behind each fix, and every pinning test.
- [`docs/admin.md`](admin.md) — Retry/Terminate semantics, the idempotency
  table, and the security posture.
- [`docs/concepts.md`](concepts.md) — timer cancellation semantics under
  "Cancelling a timer".

---

## 0.3.0

This release closes out a release-readiness hardening pass: nine of the ten
issues found during Maestro's first real-backend verification effort
(`docs/open-issues.md`, tracked as PR #27's follow-up work) are fixed, the
tenth (test coverage) is closed, and two are kept open by deliberate design
decision. It includes source-breaking SPI and API changes — read
[Breaking Changes](#breaking-changes-1) before upgrading.

Maestro is pre-1.0 (`0.3.0-SNAPSHOT`); semantic versioning guarantees don't
apply yet, but breaking changes are still called out explicitly below so
nothing is a surprise.

---

### New Features

#### Bounded redelivery and dead-lettering, all transports

A signal or task handler that throws no longer loses the message. Every
transport — Kafka, Postgres, RabbitMQ — now redelivers with exponential
backoff and, once the attempt budget is exhausted, routes the message to a
durable, inspectable dead-letter destination instead of dropping it or
retrying it forever:

| Transport | Destination | Created by |
|---|---|---|
| Kafka | `<topic>.DLT` (suffix configurable) | **The operator** — Maestro never creates topics (see the [upgrade checklist](#operator-upgrade-checklist)) |
| Postgres | The same queue row, in a new `DEAD_LETTER` status | Nothing to create — it's a status, not a new destination |
| RabbitMQ | `<queue>.dlq`, bound to a `maestro.dead-letter` exchange | The module self-declares it idempotently, like the rest of its topology |

New inspect/replay API on the Postgres transport:
`PostgresWorkflowMessaging.listDeadLetterSignals`,
`listDeadLetterTasks`, `replaySignal(UUID)`, `replayTask(UUID)`. Kafka and
RabbitMQ dead letters are inspected and replayed with ordinary broker
tooling — recipes in `docs/configuration.md` § Redelivery and Dead-Letter
Properties.

Default policy: 10 total attempts, exponential backoff from 1s to a 30s
ceiling (~2.5 minutes of tolerance) — configurable via
`maestro.messaging.redelivery.*` (see [Configuration](#configuration)).

This closes `docs/open-issues.md` Issue 1, previously the most serious open
defect: a transient handler failure — a brief database blip, a momentary
downstream outage — silently and permanently dropped a signal, and the
workflow waited forever for something that would never arrive.

#### `MaestroHealthIndicator`

`io.b2mash.maestro.spring.health.MaestroHealthIndicator`, auto-configured
when Spring Boot Actuator is on the classpath. Reports:

- `DOWN` if the store is unreachable (a bounded 2-second probe, so a hung
  store can't hang `/actuator/health` indefinitely) or if a poller that has
  actually started has since died.
- `"starting"` (not `DOWN`) for a poller that hasn't started yet — this
  distinguishes a normal boot/rolling-deploy window from a real fault, so a
  Kubernetes readiness probe doesn't flap on every deploy.
- `"disabled"` (not `false`) for the recovery poller when
  `maestro.recovery.enabled=false` — a deliberate operator choice, not a
  fault.
- `UP` otherwise, with details: store reachability, both poller states, and
  the locally running workflow count.

Closes Issue 8 (`CLAUDE.md` documented this package before it existed).

#### Timer self-healing on replay

A timer that fires and is marked `FIRED` in the store, but whose
`TIMER_FIRED` event is lost to a crash before it's appended to the workflow's
event log, previously stranded the workflow in `WAITING_TIMER` forever — no
poller would ever look at an already-`FIRED` row again. Replay now consults
the timer row directly (new `WorkflowStore.findTimer` — see
[Breaking Changes](#breaking-changes-1)) and, finding it already `FIRED`, heals
by appending the missing event and continuing, rather than re-parking.
Closes Issue 2.

#### Configurable shutdown timeout, signal wake-recheck interval, and activity lock prefix

Three settings that were previously hardcoded now bind under `maestro.*`,
with unchanged defaults — see [Configuration](#configuration). Closes
Issues 7 and 9.

#### `maestro.admin.events.enabled` now actually works

Setting it to `false` genuinely stops lifecycle event publishing (all event
families — workflow, activity, signal, timer, and compensation).
`maestro.admin.events.topic` is kept as a deprecated alias for
`maestro.messaging.topics.admin-events`. Closes Issue 6.

---

### Configuration

New properties (all optional; defaults preserve prior behaviour except where
prior behaviour was itself the defect being fixed):

| Property | Default | Purpose |
|---|---|---|
| `maestro.messaging.redelivery.max-attempts` | `10` | Total delivery attempts, including the first. All transports. |
| `maestro.messaging.redelivery.initial-interval` | `1s` | Backoff before the second attempt. |
| `maestro.messaging.redelivery.multiplier` | `2.0` | Backoff growth factor per failure. |
| `maestro.messaging.redelivery.max-interval` | `30s` | Backoff ceiling. |
| `maestro.messaging.redelivery.dead-letter-suffix` | `.DLT` | Kafka dead-letter topic suffix. |
| `maestro.messaging.redelivery.dead-letter-exchange` | `maestro.dead-letter` | RabbitMQ dead-letter exchange name. |
| `maestro.shutdown.timeout` | `30s` | How long graceful shutdown waits for in-flight workflows to drain. Was a hardcoded constant. |
| `maestro.signal.wake-recheck-interval` | `30s` | How often a parked workflow re-reads the store for a missed signal. Was a hardcoded constant; bounds cross-node signal latency for Kafka-without-Valkey deployments. |

Changed behaviour on existing properties:

- `maestro.admin.events.enabled` — now wired; previously read by nothing.
- `maestro.admin.events.topic` — now a documented, deprecated alias for
  `maestro.messaging.topics.admin-events`. If both are set to different
  values, the messaging property wins and a WARN names both.
- `maestro.lock.key-prefix` — now also applied to the activity execution
  lock, not just the workflow instance lock.

**Also documented, not new:** spring-kafka's `DefaultErrorHandler` treats a
handful of exception types (deserialization failures, `ClassCastException`,
other conversion errors) as fatal — it sends those records straight to the
dead-letter topic on the first attempt, bypassing the configured
`max-attempts` budget entirely. This is spring-kafka's own default
classification, not something Maestro configures. See
`docs/configuration.md` § Redelivery and Dead-Letter Properties.

Full reference: `docs/configuration.md`.

---

### Behaviour Changes

- **Lifecycle event publishing is off the workflow thread.** Previously,
  `startWorkflow` and every terminal transition called
  `messaging.publishLifecycleEvent(...)` inline; a Kafka producer blocked on
  metadata for a missing topic could add up to `max.block.ms` (60s by
  default) to the workflow's hot path. It now runs on a bounded, dropping
  executor owned by the engine — `startWorkflow` returns promptly regardless
  of transport latency, and sustained backpressure is logged (rate-limited
  WARN) rather than silently absorbed. Closes Issue 3.
- **Signal handler failures are redelivered, not dropped.** See
  [New Features](#new-features-1) above. This is the headline behaviour change
  in the release.
- **Shutdown mid-compensation no longer records a compensation failure.** A
  node stopping while a saga's compensations are running now leaves the
  workflow `COMPENSATING` (active, recoverable) for the next node to finish,
  instead of recording the interrupted compensation step as failed. Closes
  Issue 5.
- **A `catch (Exception e)` around `awaitSignal()`/`sleep()` can no longer
  swallow a shutdown.** See `ExecutorShutdownException` in
  [Breaking Changes](#breaking-changes-1). Closes Issue 4.

---

### Breaking Changes

Four changes are source- or behaviour-breaking. All are deliberate,
pre-1.0 rulings — see `docs/open-issues.md` for the reasoning behind each.

1. **`WorkflowStore.findTimer(UUID workflowInstanceId, String timerId)` is a
   new abstract SPI method.** Source-breaking for any third-party
   `WorkflowStore` implementation — it will no longer compile against this
   version without adding the method. A `default` method returning
   `Optional.empty()` was considered and rejected: it would silently degrade
   the durability guarantee the method exists to provide (a store that
   can't answer "is this timer already fired?" can't heal Issue 2's stall).
   `AbstractJdbcWorkflowStore` (and therefore `PostgresWorkflowStore`) and
   the `maestro-test` in-memory store both implement it; no action needed if
   you use either unmodified.

2. **`ExecutorShutdownException` now extends `java.lang.Error`, not
   `MaestroException`.** It is no longer part of the `MaestroException`
   hierarchy and can no longer be caught by a `catch (Exception e)` or
   `catch (MaestroException e)` block. This is deliberate — the entire point
   of the change is that ordinary workflow-author exception handling cannot
   intercept a shutdown signal and misrecord it as a workflow failure — but
   it breaks any code that specifically caught `ExecutorShutdownException`
   expecting it to be a `RuntimeException`/`MaestroException`. Anywhere your
   own code unwraps a reflection or `CompletableFuture` completion exception
   (`Method.invoke`, `CompletionException`/`ExecutionException`), check
   `instanceof Error` before `instanceof Exception` — see `CLAUDE.md` §
   Coding Standards for the full rationale (Temporal takes the same
   approach for the same reason).

3. **`KafkaMessagingConfig` gained new record fields** (`maxAttempts`,
   `initialInterval`, `multiplier`, `maxInterval`, `deadLetterSuffix`) to
   carry the redelivery policy. Source-breaking for any code constructing
   `KafkaMessagingConfig` directly rather than through
   `KafkaMessagingAutoConfiguration`. Four in-repo call sites were updated;
   if you construct it yourself, add the new fields (or use
   `KafkaMessagingConfig`'s existing defaults convenience, if you did before
   this change).

4. **`@MaestroSignalListener` now requires a `KafkaTemplate` bean in the
   application context.** Previously, a poison or unprocessable record on
   that channel was silently logged and skipped after ten attempts, with no
   dead-letter destination — because there was nowhere to route it. Now that
   exhausted records are dead-lettered, the bean post-processor resolves a
   `KafkaTemplate` (by bean name `maestroKafkaTemplate`, falling back to
   by-type) to publish to the dead-letter topic. In practice this bean is
   always present — the same auto-configuration registers both the template
   and the listener post-processor — but an application that registers the
   post-processor by hand without a `KafkaTemplate` will now fail fast at
   startup instead of silently dropping poison records at runtime. Loud is
   the intended failure mode.

---

### Database Migrations

Two new Flyway migrations, both applied automatically on startup — no manual
action required:

- **`V3__timer_lookup_index.sql`** (`maestro-store-postgres`, version band
  1–99) — adds `idx_wf_timer_lookup ON maestro_workflow_timer
  (workflow_instance_id, timer_id)`, needed by the new `findTimer` query.
  Not unique by design (a benign race between two nodes scheduling the same
  live sleep is resolved by the event log's unique index, not this one). No
  table or column changes.
- **`V201__redelivery_dead_letter.sql`** (`maestro-messaging-postgres`,
  version band 200–299) — adds `attempts`, `next_attempt_at`, `last_error`
  columns to both `maestro_signal_queue` and `maestro_task_queue`, widens
  their status `CHECK` constraints to admit `DEAD_LETTER` (keeping `FAILED`
  valid only for rows already written by earlier versions — the code never
  writes `FAILED` again), and repoints each table's partial pending-status
  index from `created_at` to `next_attempt_at` so the redelivery-eligibility
  check (`status = 'PENDING' AND next_attempt_at <= now()`) can use it.
  Claim ordering itself is unchanged — still `ORDER BY created_at`
  (insertion order); the index change is about the filter, not the sort.

**Optional, manual, one-time rescue SQL** for rows stranded `FAILED` by the
pre-fix Postgres transport (see the [upgrade checklist](#operator-upgrade-checklist)):

```sql
UPDATE maestro_signal_queue SET status = 'PENDING', next_attempt_at = now() WHERE status = 'FAILED';
UPDATE maestro_task_queue   SET status = 'PENDING', next_attempt_at = now() WHERE status = 'FAILED';
```

Nothing runs this automatically — a deployment that never hit the old defect
has no `FAILED` rows to rescue, and running it unconditionally would be a
no-op for them anyway.

---

### Operator Upgrade Checklist

1. **Pre-create a Kafka `.DLT` topic for every topic Maestro consumes** —
   each `maestro.tasks.{taskQueue}` topic, each `maestro.signals.{service}`
   topic, and each topic behind an `@MaestroSignalListener` — before
   upgrading. Maestro never auto-creates topics, dead-letter topics
   included. **This is fail-safe, not fail-silent:** if a dead-letter topic
   is missing, the publish fails, the offset is not committed, and the
   record is attempted again — consumption stalls loudly (visible in logs
   and consumer lag) instead of losing the message. But it does stall that
   topic on the affected node until the topic exists, and — because Maestro's
   listener containers run with the default concurrency of one consumer
   thread per topic — that means the *whole topic* on that node, not just
   the affected partition.
2. **No RabbitMQ action needed.** The dead-letter exchange and per-queue
   `.dlq` are self-declared idempotently by the module, consistent with how
   it already declares its other topology.
3. **No manual migration step.** `V3` and `V201` run automatically via
   Flyway on first startup against each database.
4. **Optional: run the rescue SQL above** if this deployment previously hit
   the Postgres transport's signal-loss defect and has rows stuck `FAILED`.
   Skippable otherwise.
5. **If you construct `KafkaMessagingConfig` directly**, or **catch
   `ExecutorShutdownException` expecting a `MaestroException`**, or **have a
   third-party `WorkflowStore` implementation**, read
   [Breaking Changes](#breaking-changes-1) before upgrading — those three are
   compile-time breaks, not runtime surprises, so they'll surface
   immediately on build.
6. **If you run `@MaestroSignalListener` with a hand-registered bean
   post-processor** (rather than through Maestro's own auto-configuration),
   confirm a `KafkaTemplate` bean is present — see Breaking Change 4.

---

### Known Limitations

Two are deliberate, accepted trade-offs, not defects — closing either is a
real design/SPI change, not a patch:

- **No lock fencing (`docs/open-issues.md` Issue 11).** A node that loses
  its instance lock (e.g. to a GC pause longer than the TTL) keeps running;
  split-brain is tolerated rather than prevented. Duplicate *persisted
  results* are still impossible (the event log's unique index rejects the
  loser's writes), but duplicate *side effects* are not — **activities must
  be idempotent.** Closing this needs fencing-token validation added to the
  `WorkflowStore` SPI, touching every implementation.
- **Recovery polling scales linearly with the active workflow count**
  (`docs/open-issues.md` Issue 12). `getRecoverableInstances()` has no
  service or staleness filter, so every node re-reads the whole active set
  on every poll cycle and probes the lock for each foreign-owned instance;
  lock renewal is also serial (one round-trip per held lock per TTL/3). Fine
  at today's scale; **benchmark before tuning** if you expect a node to hold
  thousands of parked workflows — this needs measurements before a fix, not
  a blind optimisation.

Three more were found during this release's work and remain open,
unscheduled:

- **`CANCELLED` timers can strand a replaying workflow**
  (`docs/open-issues.md` Issue 13) — the same failure shape as the original
  Issue 2 (permanent, silent stall), triggered instead by
  `TimerManager.cancelTimer` being called on a timer a workflow is currently
  parked on. Narrower and rarer than Issue 2 was, but not yet fixed, and the
  underlying semantics ("what should cancelling a timer someone is waiting
  on even mean?") aren't decided yet either.
- **`SagaManager` re-appends `COMPENSATION_STARTED` on replay**
  (`docs/open-issues.md` Issue 14) — currently harmless (the event log's
  unique index silently absorbs the duplicate), but not the same
  replay-skip discipline every other event-emitting engine path follows.
- **Admin dashboard retry/terminate buttons are not functional
  end-to-end** (`docs/open-issues.md` Issue 15) — they publish
  `$maestro:retry`/`$maestro:terminate` signals successfully, but nothing in
  the engine or starter consumes those two internal signal names yet, so
  the target workflow never actually changes state. "Send Signal" is
  unaffected. Pre-existing, not introduced by this release.

**Update (0.4.0):** all three of the above — Issues 13, 14, and 15 — are
fixed. See [0.4.0](#040) above and `docs/open-issues.md` for commits and
pinning tests. This list is kept as the historical record of what was known
at 0.3.0's release.

---

### See Also

- [`docs/open-issues.md`](open-issues.md) — the full, per-issue detail this
  document summarises: what was wrong, why, where, how it was fixed, and the
  tests that pin each fix.
- [`docs/configuration.md`](configuration.md) — complete configuration
  reference, including the new redelivery/dead-letter and shutdown/signal
  properties.
- [`docs/maestro-architecture.md`](maestro-architecture.md) — updated
  architecture reference for signal-loss handling, timer recovery, and
  shutdown semantics.
