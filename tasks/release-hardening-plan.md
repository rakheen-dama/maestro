# Release Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the engine release-ready: remove RabbitMQ, add replay-aware observability (Micrometer meters + OTel tracing with Kafka trace propagation), and add versioning (`workflow.version()` + unknown-event stand-down).

**Architecture:** A Spring-free `EngineObserver` SPI in `maestro-core` is the single seam every observability consumer binds to; the starter contributes Micrometer/OTel binders via auto-configuration. Versioning rides the existing memoization machinery (a new `VERSION_MARKER` event) and the existing lock/recovery machinery (stand-down = release + adoptable, never FAILED).

**Tech Stack:** Java 25, Spring Boot 4, Micrometer (+ Observation API or OTel SDK — design doc decides), Spring Kafka 4, Jackson 3 (`tools.jackson`), Testcontainers 2, JUnit 5.

**Binding spec:** `docs/release-hardening-spec.md` — every task inherits its §6 Global Constraints verbatim. Fine-grained design decisions for Tasks 3–7 are bound by the approved design doc produced in Task 2 (`.superpowers/sdd/release-hardening/observability-versioning-design.md`).

## Global Constraints (from spec §6 — copied verbatim, binding on every task)

- Java 25+ toolchain; Spring Boot 4+; Jakarta EE 11 (`jakarta.*` only).
- Jackson 3 (`tools.jackson`) — never `com.fasterxml.jackson`.
- `maestro-core` must NEVER depend on Spring, Micrometer, OpenTelemetry, or any framework — observer/SPI seams only.
- `ExecutorShutdownException` / `WorkflowTerminatedException` (and any new stand-down signal) extend `Error`; check `instanceof Error` before `Exception` at every unwrap site; broad `catch (Throwable)` collectors rethrow before recording failures.
- Never break `(workflow_instance_id, sequence_number)` uniqueness. Never discard a signal. No Lombok. JSpecify annotations on public APIs. Javadoc on all public APIs, SPIs especially.
- TDD: RED before GREEN, failing output shown verbatim in reports.
- Evidence artifacts embed identity (pwd, `git rev-parse HEAD`, branch, timestamp) inside the file; evidence dir `.superpowers/sdd/release-hardening/evidence/`.
- Meter/span cardinality: never tag by workflowId/runId.
- Implementers commit incrementally — every coherent green checkpoint, never >30 min uncommitted.

---

## Task 1: RabbitMQ removal

**Files:**
- Delete: `maestro-messaging-rabbitmq/` (entire module), `maestro-samples/sample-rabbitmq-order-service/` (entire module)
- Modify: `settings.gradle.kts` (2 includes), the coverage-gate module list in `build-logic/` (wherever `maestro-` modules are enumerated), `.github/workflows/*` (any rabbitmq job/steps), `docs/configuration.md` (redelivery section says "all transports"), `docs/maestro-architecture.md`, `docs/cross-service.md` (if it names RabbitMQ), `README.md`, `CLAUDE.md` (module table + Java "21+" → "25+" and Boot 4+ platform line), `docs/release-notes.md`
- Do NOT edit: `.superpowers/sdd/multi-instance/**` (historical evidence), git history

**Interfaces:** none produced; removal only. `WorkflowMessaging` SPI untouched.

- [ ] Delete both modules and their `settings.gradle.kts` includes; run `./gradlew projects` to prove the module graph is clean
- [ ] Sweep references: `rg -i rabbitmq --files-with-matches` and fix every hit outside release notes / git history / archived evidence; re-run until the spec §3 invariant holds
- [ ] CLAUDE.md platform truth pass: Java 25+, Boot 4+, module table updated
- [ ] Release-notes entry: removal + rationale (per-transport verification cost; SPI remains open)
- [ ] Full `./gradlew build` green; commit

## Task 2: Observability + versioning design doc (architect; gates Tasks 3–7)

**Files:**
- Create: `.superpowers/sdd/release-hardening/observability-versioning-design.md`

**Required sections (each with a decision, not options):**
1. `EngineObserver` — exact interface (method signatures, argument records, `replayed` handling: flag-per-callback vs no-emit-during-replay), no-op default, composite, registration path into `WorkflowExecutor`/`ActivityInvocationHandler`/`SignalManager`/`TimerService`/`RecoveryPoller`, thread-safety contract
2. Meter catalog — final names/types/tags for every meter in spec §B2 (cardinality rules applied), gauge sourcing strategy (state-tracking vs store-polling)
3. Tracing approach — Micrometer Observation API vs direct OTel SDK, with rationale against Boot 4 idiom and the no-spans-during-replay requirement; span topology (workflow segment, activity, signal/timer events)
4. Kafka propagation contract — exact header names (W3C `traceparent`/`tracestate`), injection/extraction points in `maestro-messaging-kafka`, context restoration at workflow resume
5. `VERSION_MARKER` — event type name, payload schema (Jackson 3), `WorkflowContext.version()` signature and memoization/sequence semantics incl. parallel branches, min-guard error type
6. Stand-down mechanism — store row-mapper sentinel design (unknown `EventType` must not throw in the mapper), the engine-side control-flow signal (name, extends `Error`, Javadoc rationale mirroring `ExecutorShutdownException`), every unwrap/catch site that needs the Error-first ordering, lock-release path, observer callback
7. Config seams — `maestro.observability.metrics.enabled`, `maestro.observability.tracing.enabled`, defaults and conditional-on rules
8. Test strategy per area, incl. the replay-no-double-count pin and the SQL-injected future-event stand-down integration test

- [ ] Architect (fable) writes the doc; coordinator reviews and appends an APPROVED ruling section (rulings resolve any open questions) before Tasks 3–7 dispatch

## Task 3: Core `EngineObserver` SPI + engine wiring

**Files:**
- Create: `maestro-core/src/main/java/io/b2mash/maestro/core/observe/EngineObserver.java` (+ supporting records/no-op/composite per design doc)
- Modify: `WorkflowExecutor`, `ActivityInvocationHandler`, `SignalManager`, `TimerService`/timer poller, `RecoveryPoller`, `SagaManager` (emission points per design doc §1)
- Test: `maestro-core/src/test/java/io/b2mash/maestro/core/observe/` (recording observer; replay pin)

**Interfaces:**
- Produces: `EngineObserver` exactly as the design doc §1 defines — Tasks 4, 5, 7 consume it. `WorkflowExecutor` builder/ctor accepts an observer (default no-op).

- [ ] RED: recording-observer test — run a workflow with 3 activities, crash after 2, recover; assert started/completed counts are exactly-once per logical event (this fails until replay-awareness is implemented)
- [ ] Implement SPI + wiring per design doc; GREEN; full `:maestro-core:test`; commit incrementally

## Task 4: Micrometer meters auto-configuration (starter)

**Files:**
- Create: `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/observe/MaestroMetricsAutoConfiguration.java` (+ the `EngineObserver`→`MeterRegistry` binder)
- Modify: `MaestroProperties` (observability node), auto-config imports file
- Test: starter context tests (registry assertions through a real engine run via the starter's existing test harness)

**Interfaces:**
- Consumes: `EngineObserver` (Task 3), design doc §2 meter catalog
- Produces: meters under `maestro.*` exactly as catalogued; `maestro.observability.metrics.enabled` property

- [ ] RED: context test asserting `maestro.workflow.started` counter increments when a workflow runs (fails: no binder)
- [ ] Implement binder + conditional auto-config; GREEN incl. disabled-flag test (no meters when `enabled=false`); commit

## Task 5: OTel tracing + Kafka trace propagation

**Files:**
- Create: tracing binder in `maestro-spring-boot-starter` (per design doc §3), propagation inject/extract in `maestro-messaging-kafka` (per §4)
- Test: starter span tests (in-memory tracer/exporter); `maestro-messaging-kafka` propagation contract tests (Testcontainers Kafka); one integration-level linkage assertion in `maestro-integration-tests`

**Interfaces:**
- Consumes: `EngineObserver` (Task 3), design doc §§3–4
- Produces: spans per design doc topology; W3C headers on every published `TaskMessage`/`SignalMessage`

- [ ] RED: no-spans-during-replay pin (recover a workflow; assert zero new activity spans for replayed steps)
- [ ] RED: propagation contract test (publish under an active span → consumed record carries valid `traceparent` → resumed segment parented remotely)
- [ ] Implement; GREEN; one cross-service linkage assertion green; commit incrementally

## Task 6: `workflow.version()` (core)

**Files:**
- Modify: `WorkflowContext` (+ its implementation in `io.b2mash.maestro.core.context`), `EventType` (add `VERSION_MARKER`), memoization engine touchpoints per design doc §5, `maestro-test` `DeterminismChecker` (version markers are decisions), in-memory + JDBC stores only if the design doc requires store awareness (expected: none — it is an ordinary memoized event)
- Test: `maestro-core` version semantics suite; `maestro-store-postgres` round-trip of the new event; docs snippet in `docs/concepts.md` (or the docs file the design doc names)

**Interfaces:**
- Produces: `int version(String changeId, int minSupported, int maxSupported)` on `WorkflowContext`; `EventType.VERSION_MARKER`; the min-guard error type named by design doc §5

- [ ] RED: live call records `maxSupported` and a `VERSION_MARKER` event at the correct sequence; replay returns the recorded value even when the code's max changed; recorded < min raises the typed error naming changeId + range; parallel-branch allocation
- [ ] Implement; GREEN; `:maestro-core:test` + `:maestro-store-postgres:test`; commit incrementally

## Task 7: Unknown-event stand-down

**Files:**
- Modify: `maestro-store-jdbc` row mapper (unknown-type sentinel per design doc §6), `maestro-test` `InMemoryWorkflowStore` (same sentinel semantics), engine replay path (`WorkflowExecutor`/memoization engine) to detect the sentinel and stand down, `maestro-core` exceptions package (the new `Error`-based signal with full Javadoc rationale), every unwrap/catch site the design doc §6 enumerates
- Test: core unit tests (mapper sentinel, catch ordering, lock released, no FAILED/no compensation, observer callback); `maestro-integration-tests` stand-down integration test (SQL-inject a future event type — use `VERSION_MARKER`'s successor, i.e. a literally unknown string — into a parked workflow on real Postgres; recovery stands down; instance remains adoptable and completes after the row is normalized)

**Interfaces:**
- Consumes: `EngineObserver.standDown(reason)` (Task 3), design doc §6
- Produces: the stand-down signal type; `maestro.standdown` counter increments via the Task 4 binder (add the meter there if Task 4 landed without it)

- [ ] RED: unit — mapper returns sentinel for unknown type (today: `valueOf` throws); engine stand-down path (lock released, status unchanged, zero compensations)
- [ ] RED: integration — SQL-injected future event; recovery attempt; assert stand-down + adoptability
- [ ] Implement; GREEN; commit incrementally

## Task 8: Docs close-out + release notes

**Files:**
- Modify: `docs/configuration.md` (observability properties), `docs/operations.md` (versioning playbook: upgrade-together advisory + stand-down safety net; observability run-book pointers), new `docs/observability.md` (meter reference table generated from the design doc catalog, tracing setup, propagation header contract), `docs/concepts.md` (version() usage + determinism note), `docs/release-notes.md` (all four areas incl. `VERSION_MARKER` upgrade note), `README.md`, `CLAUDE.md` (config namespace table, new packages)

- [ ] Every claim traceable to code/tests on this branch (numbers-truth discipline from the previous cycle); stale-claims sweep (`rg` for "21+", "RabbitMQ", "all transports"); commit

## Task 9: QA gate (never patches; reopens owning task on failure)

- [ ] Full `./gradlew build --rerun-tasks` green, evidence log with identity header
- [ ] `:maestro-integration-tests:test` green; chaos PR-gate e2e ×1 green (regression only — chaos schedule untouched this cycle)
- [ ] Spec §3 rabbitmq invariant re-verified; §7 definition-of-done checklist walked item-by-item with evidence pointers
- [ ] Live meters/traces spot-check: boot one sample (postgres-only or order-service) with actuator + an in-memory/OTLP-logging exporter; scrape `/actuator/metrics/maestro.workflow.started` after driving one workflow; confirm a connected trace exists for one cross-service flow (loan sample, if boot cost is acceptable — else the integration-test linkage assertion stands as the evidence)
- [ ] GATE VERDICT written to `.superpowers/sdd/release-hardening/task-9-qa-report.md`

Then (process, not tasks): final whole-branch review (fable, `merge-base..HEAD` package, findings to file, one fix wave max) → `superpowers:finishing-a-development-branch` integration menu — **no merge/push without the user's choice**.

## Dependency graph

Task 1 independent (run first — cheapens every later CI cycle). Task 2 gates 3–7. Task 3 gates 4, 5, and 7's observer callback. Task 6 is independent of 3–5 (pure memoization) and SHOULD land before 7 so the stand-down test can reference a real newly-added event type as its "known" baseline. Tasks 4/5 parallelizable after 3. Task 8 after 3–7. Task 9 last.
