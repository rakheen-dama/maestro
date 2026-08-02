# Release Hardening Spec — RabbitMQ removal, Observability, Versioning

Status: binding contract for the `worktree-release-hardening` milestone.
Decided with the product owner 2026-08-02 (discussion recorded in session; scope
confirmed verbatim below). Successor process to the multi-instance verification
cycle (PR #30); the same evidence discipline applies.

## 1. Purpose

Make the engine **release-ready** for its first public artifact. Three work
areas, one platform declaration:

1. **Remove RabbitMQ support** — shrink the transport matrix to Kafka +
   Postgres before anything is published.
2. **Observability** — Micrometer meters + OpenTelemetry tracing, including
   cross-service trace propagation through Kafka message headers.
3. **Versioning** — `workflow.version()` memoized change-branching plus the
   unknown-event stand-down guard for mixed-version deploy safety.
4. **Platform declaration** — Java 25+, Spring Boot 4+ (toolchain already 25;
   docs still say 21+ in places and must be corrected).

## 2. Non-goals (explicitly out of scope)

- Maven Central publishing (separate effort after product review).
- Spring Boot 3.x compatibility.
- ShedLock adoption (assessed and rejected 2026-08-02; a
  `maestro-lock-shedlock` adapter is a post-1.0 community-bridge idea only).
- Admin dashboard investment beyond keeping it compiling and its tests green.
- A RabbitMQ deprecation/migration path — nothing is published, so this is
  deletion, not deprecation.
- Full event-schema evolution. Stand-down makes unknown history *safe*, not
  *processable*.

## 3. Area A — RabbitMQ removal

### Requirements
- Delete modules `maestro-messaging-rabbitmq` and
  `maestro-samples:sample-rabbitmq-order-service` (code, tests, build
  registration).
- Sweep every reference: `settings.gradle.kts`, coverage-gate module list,
  CI workflows, `docs/configuration.md` (redelivery says "all transports"),
  `docs/maestro-architecture.md`, `docs/cross-service.md` if applicable,
  `README.md`, root `CLAUDE.md` module table, samples docs.
- Release note states the removal **with rationale**: per-transport
  verification cost (the multi-instance cycle is the evidence); the
  `WorkflowMessaging` SPI remains transport-agnostic and community
  implementations remain possible.

### Invariants
- `rg -i rabbitmq` over the repo returns hits only in one of these classes
  (coordinator ruling, fix round 1 — the literal "returns only release
  notes/git history/archived evidence" wording above could never pass, since
  this spec itself must keep naming RabbitMQ):
  (a) the release-notes removal entry;
  (b) git history;
  (c) archived evidence under `.superpowers/` (historical records, must not
      be edited);
  (d) dated historical-record documents — `docs/open-issues.md`,
      `docs/test-plan.md`, `docs/multi-instance-test-plan.md`,
      `maestro-integration-tests/SPEC.md`'s dated resolved-in-place notes,
      and `tasks/*.md` history — **provided no hit in them is a present-tense
      claim that Maestro currently ships RabbitMQ support** (a dated "as of
      this update, module X shipped" or "Issue N: RabbitMQ — Resolved"
      narrative is fine; a live "Maestro supports Kafka, Postgres, or
      RabbitMQ" statement is not, regardless of which of these files it's
      in);
  (e) this spec document and its dispatch plan, which necessarily describe
      the removal itself.
- Full `./gradlew build` green after removal.

## 4. Area B — Observability

### B1. Core observer seam (Spring-free)

- New SPI in `maestro-core` (suggested package
  `io.b2mash.maestro.core.observe`): an `EngineObserver` interface the engine
  calls at execution boundaries. Core gains **no** dependency on Micrometer,
  OpenTelemetry, or Spring. No-op default; composite supported.
- Callback surface (minimum; architect may refine names in the design doc):
  workflow started/completed/failed/compensating/terminated; activity
  started/completed/failed with duration; signal persisted/consumed; timer
  scheduled/fired/cancelled; instance lock acquired/renew-failed/lost;
  recovery pass (instances scanned/adopted); stand-down (reason).
- **Replay-awareness is a hard invariant:** every callback that can fire
  during replay carries a `replayed` flag (or replay simply does not emit —
  architect decides and documents). A recovered workflow replaying N
  activities MUST NOT double-count meters or emit phantom spans. This is
  pinned by a dedicated test: run a workflow, crash, recover, assert counters
  incremented exactly once per logical event.

### B2. Micrometer meters (starter)

- Auto-configuration in `maestro-spring-boot-starter`, conditional on
  `MeterRegistry` (class + bean). Namespace `maestro.*`. Config seam
  `maestro.observability.metrics.enabled` (default `true`).
- Minimum meter set (names final in the design doc; tags must include
  `workflow` type where cardinality-safe, never workflowId):
  - counters: `maestro.workflow.started|completed|failed|compensated|terminated`,
    `maestro.signal.consumed`, `maestro.timer.fired`,
    `maestro.recovery.adopted`, `maestro.lock.renew.failures`,
    `maestro.standdown` (see Area C)
  - timers: `maestro.activity.duration` (tags: workflow, activity, outcome)
  - gauges: `maestro.workflows.running`, `maestro.workflows.parked`
- The multi-instance cycle's external `MetricsSampler` measurements
  (recovery calls, lock probes/renews, parked counts) are the reference for
  what operators need; meters must cover those signals from inside.

### B3. OpenTelemetry tracing + Kafka propagation

- Approach decision (architect, in the design doc): Micrometer Observation
  API (Boot 4-native, bridges to OTel) vs direct OpenTelemetry API. The
  chosen approach must support: span per activity execution, span per
  workflow run segment (start → park/complete), events for signal
  consume/timer fire, and **no spans during replay**.
- **Cross-service propagation through Kafka is in scope** (product decision):
  W3C `traceparent`/`tracestate` (+ baggage if cheap) injected into
  `TaskMessage`/`SignalMessage` headers on publish, extracted on consume, so
  a cross-service workflow (loan-style) renders as one connected trace:
  signal publish → listener consume → workflow resume → activity spans.
- The propagation contract (exact header names, W3C format) is pinned by
  tests in `maestro-messaging-kafka`, not left to transport defaults.
- Config seam `maestro.observability.tracing.enabled` (default `true` when
  a tracer is present).
- MDC keys (`workflowId`, `runId`, `activityName`) become span attributes.

### Evidence requirements (Area B)
- Replay-no-double-count test (B1) green.
- Starter context tests: meters registered and incremented through a real
  engine run.
- Propagation contract test: publish with active span → consumed message
  carries valid `traceparent` → resumed segment's span has the remote parent.
- One integration-level assertion that a two-service flow yields a single
  connected trace (bounded scope: messaging-layer contract tests carry the
  detail; one end-to-end linkage assertion suffices).

## 5. Area C — Versioning

### C1. `workflow.version()` API

- `WorkflowContext` gains
  `int version(String changeId, int minSupported, int maxSupported)`.
- Semantics (Temporal-proven model):
  - First (live) evaluation memoizes a `VERSION_MARKER` event
    (payload: `changeId`, `version = maxSupported`) at the current sequence
    number and returns `maxSupported`.
  - Replay returns the recorded version — forever — regardless of the code's
    current `maxSupported`.
  - If the recorded version `< minSupported`, the engine raises a clear,
    documented error naming the changeId, recorded and supported range
    (the workflow author has removed code an old instance still needs).
- Sequence-number allocation follows the standard memoization rules,
  including inside parallel branches (`p*1000 + (i+1)*1000` partitioning).
- Determinism: repeated calls with the same `changeId` in one run return the
  same value; `maestro-test`'s `DeterminismChecker` must treat version
  markers as decisions.

### C2. Unknown-event stand-down

- When a node reads persisted history it cannot interpret — an event `type`
  string absent from its `EventType` enum, or a payload it cannot map — the
  run **stands down**: release the instance lock, leave the instance in its
  recoverable state, emit `EngineObserver.standDown(reason)`, log WARN. It is
  NEVER recorded as a workflow failure and NEVER triggers compensation.
- Mechanism (architect pins details): the store row-mapper must not throw on
  unknown types (sentinel/unknown event representation); the engine detects
  the sentinel and exits through the same Error-based control-flow channel as
  `ExecutorShutdownException` (same rationale: workflow-author `catch
  (Exception)` must not intercept it). Catch-ordering rules from CLAUDE.md
  apply at every unwrap site.
- An upgraded node adopting the workflow later processes it normally — the
  existing lock-TTL/recovery-poller adoption machinery, unchanged.
- **Deploy guidance is unchanged and stays in the docs: upgrade all nodes of
  a service together.** Stand-down is the safety net for the mixed-version
  window, not an invitation to run mixed fleets indefinitely. (Product
  ruling 2026-08-02; new event types are rare — this is insurance, not a
  hot path.)
- Metric: stand-down increments `maestro.standdown` (tag: reason) and the
  parked/standby population is visible via existing gauges.

### Evidence requirements (Area C)
- Version API: new-instance records max; replay returns recorded value under
  changed code (simulated by re-registering a workflow whose `maxSupported`
  differs); min-guard failure is typed and message-complete; parallel-branch
  allocation test.
- Stand-down: integration test against real Postgres — inject a raw event
  row with a future type string via SQL into a parked workflow's history;
  recovery attempt stands down (lock released, status unchanged, zero
  compensations, observer callback fired); the same instance is adoptable
  and completable once the unknown row is replaced/removed (simulating the
  upgraded node). Unit tests for the mapper sentinel and catch-ordering.
- `VERSION_MARKER` is itself a new event type: the stand-down test SHOULD use
  it as the injected future type where practical (two birds).

## 6. Global constraints (verbatim, binding on every task)

- Java 25+ toolchain; Spring Boot 4+; Jakarta EE 11 (`jakarta.*` only).
- Jackson 3 (`tools.jackson`) — never `com.fasterxml.jackson`.
- `maestro-core` must NEVER depend on Spring, Micrometer, OpenTelemetry, or
  any framework — observer/SPI seams only.
- `ExecutorShutdownException` / `WorkflowTerminatedException` (and any new
  stand-down signal) extend `Error`; check `instanceof Error` before
  `Exception` at every unwrap site; broad `catch (Throwable)` collectors
  rethrow before recording failures.
- Never break `(workflow_instance_id, sequence_number)` uniqueness. Never
  discard a signal. No Lombok. JSpecify annotations on public APIs. Javadoc
  on all public APIs, SPIs especially.
- TDD: RED before GREEN, failing output shown verbatim in reports.
- Evidence artifacts embed identity (pwd, `git rev-parse HEAD`, branch,
  timestamp) inside the file; per-cycle evidence directory
  `.superpowers/sdd/release-hardening/evidence/`.
- Meter/span cardinality: never tag by workflowId/runId.

## 7. Definition of done

- All tasks complete, each with a clean adversarial review (or
  parked-with-ruling minors).
- Full `./gradlew build --rerun-tasks` green on the exact tree to integrate;
  integration suite green; chaos PR-gate e2e run green once (regression
  check — this cycle does not touch the chaos schedule).
- `rg -i rabbitmq` invariant (§3) holds.
- Docs truthful: configuration reference covers new
  `maestro.observability.*` properties; operations playbook covers
  versioning + stand-down; README/CLAUDE.md say Java 25+/Boot 4+; release
  notes cover removal, observability, versioning, and the new
  `VERSION_MARKER` event type's upgrade note.
- QA gate passes (verifies with evidence; never patches).
- Final whole-branch review clean after at most one fix wave; branch pushed
  and PR opened only after the user picks the integration option.
