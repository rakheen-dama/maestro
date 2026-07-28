# Maestro Release Readiness Plan

Source spec: `docs/open-issues.md` (status date 2026-07-28). This plan converts its
issue backlog into ordered, independently reviewable tasks. Issues 11 and 12 are
deliberately deferred (per the doc's own recommendation) and are handled as
documented known limitations in Task 9, not as code changes.

Branch: `worktree-release-readiness`, based on `main` @ `0502b38`.

## Global Constraints

These bind every task. Reviewers treat violations as defects.

- `maestro-core` must NEVER import Spring. All Spring integration lives in
  `maestro-spring-boot-starter`.
- Jackson 3 (`tools.jackson`), never `com.fasterxml.jackson`. `jakarta.*`, never
  `javax.*`. No Lombok. JSpecify `@Nullable` on public APIs. Public exceptions
  extend `MaestroException` (Task 3 makes one deliberate, documented exception
  to this rule). Javadoc and thread-safety notes on public classes.
- Kafka/RabbitMQ topics, queues, and exchanges are never auto-created by
  Maestro; they are pre-declared in configuration and documented.
- Optimistic locking convention: the caller builds the new state with
  `version = current + 1`; the store CASes against `version - 1`.
- Library-bug protocol: if a test exposes an engine defect, reproduce it first
  as a failing test in the module that owns the defect, then fix that module.
  Never work around a proven engine bug inside a test.
- TDD: every behaviour change starts with a failing test. Prove the test can
  fail (watch it fail against the old behaviour), then make it pass.
- Integration tests use Awaitility with generous bounds; never `Thread.sleep`
  as synchronisation. Real-backend tests go in `maestro-integration-tests`
  (read its `SPEC.md` first) except backend-specific suites, which live with
  their backend module.
- Do not change the public behaviour of anything not named by your task.
- Commit per logical change with clear messages; do not push.

## Task 1: Issue 2 — timer fire crash window strands workflows

**Kind:** Library defect (believed, unproven). **Reproduce before fixing; close
the issue if the repro shows recovery works.**

The suspected sequence: `WorkflowExecutor.fireTimer` calls
`store.markTimerFired(timerDbId)` (row `PENDING → FIRED`), then unparks the
workflow thread, and the workflow thread appends the `TIMER_FIRED` event in
`DefaultWorkflowOperations.sleep`. A crash between the row update and the event
append leaves: log = `TIMER_SCHEDULED` with no `TIMER_FIRED` → replay re-parks;
timer row = `FIRED`, and `getDueTimers` only returns `PENDING` rows → no poller
ever fires it again. Workflow waits forever with no error.

**Step 1 — reproduction.** Integration test in
`maestro-integration-tests/src/test/java/.../engine/`: start a workflow that
sleeps (use existing `TestWorkflows.SleepingWorkflow` and
`MaestroEngineHarness`); wait for the timer row to exist; call
`store.markTimerFired(...)` directly WITHOUT unparking (simulates the crash
window); build a second `MaestroEngineHarness` over the same store; run
`recover()`; assert the workflow completes. Expected today: it parks forever
(bound the assertion with Awaitility, generous timeout).

**Step 2 — decision gate.** If the repro shows the workflow completes, STOP:
report back that Issue 2 is invalid, keep the test as a pin, do not change
engine code.

**Step 3 — fix (only if repro fails).** Preferred fix is the doc's option 1,
self-healing replay: in `DefaultWorkflowOperations.sleep`'s replay branch, when
`TIMER_SCHEDULED` exists with no `TIMER_FIRED`, check the timer row via the
store; if it is already `FIRED`, append the `TIMER_FIRED` event / treat the
timer as elapsed and continue instead of re-parking. No schema change. Add/keep
a unit test in `maestro-core` if expressible there, plus the integration repro.

**Done when:** the repro passes; a `kill -9` in that window leaves a workflow
that recovery completes; all existing engine tests stay green
(`./gradlew :maestro-core:test :maestro-integration-tests:test`).

## Task 2: Issues 3 + 6 — lifecycle publishing: latency and the dead config block

**Kind:** Library defects. Two coupled problems in one seam.

**Issue 3:** `WorkflowExecutor.publishLifecycleEvent` (~line 905) catches
failures but not latency. `KafkaTemplate.send` blocks up to `max.block.ms`
(60s default) fetching metadata for a missing topic — inline inside
`startWorkflow`. Observed timing out all six loan E2E scenarios.

**Issue 6:** `maestro.admin.events.enabled` and `maestro.admin.events.topic`
bind into `MaestroProperties` (`AdminProperties`/`EventsProperties` in
`maestro-spring-boot-starter/.../config/MaestroProperties.java`) and are read
by nothing. Both samples set `enabled: false` expecting silence and get
publishing anyway.

**Required behaviour:**
1. Lifecycle publishing must never add meaningful latency to the workflow
   thread. Move it off-thread onto a small bounded executor owned by the
   engine (plain `java.util.concurrent` — core stays Spring-free) that drops
   events and logs (rate-limited) under backpressure or slow transport. The
   SPI contract already says lifecycle failures must not interrupt execution;
   make that true for latency too. Executor must shut down cleanly with the
   engine.
2. Wire `maestro.admin.events.enabled` (default `true`) so `false` disables
   lifecycle publishing entirely — thread the flag from starter config into
   `WorkflowExecutor` (e.g. constructor/config object), do not read Spring
   from core.
3. `maestro.admin.events.topic`: treat as an alias for
   `maestro.messaging.topics.admin-events` with a deprecation note in its
   Javadoc/docs — if both are set, the messaging one wins and a WARN is
   logged. (Alternative — deleting the block — is NOT chosen; disabling
   dashboard events is a legitimate need.)

**Tests:**
- Integration test: point the Kafka producer at a non-existent lifecycle topic
  and assert `startWorkflow` returns promptly (e.g. < a few seconds bound).
- Context-runner test in the starter pinning that `enabled=false` reaches the
  engine and stops publishing (observable via an in-memory messaging spy).
- Context-runner test for the topic alias + precedence.
- Update both samples' `application.yml` comments if their `enabled: false`
  now actually takes effect (it should).

**Done when:** missing/unreachable admin topic costs no meaningful workflow
thread time, `enabled=false` demonstrably stops publishing, both pinned by
tests.

## Task 3: Issues 4 + 5 — shutdown must not be catchable or recorded as compensation failure

**Kind:** Library gap (API design) + library defect (semantics). Coupled: both
are about `ExecutorShutdownException` semantics.

**Issue 4:** `ExecutorShutdownException extends MaestroException extends
RuntimeException`. A workflow author's ordinary `try { workflow.awaitSignal(...) }
catch (Exception e)` swallows it, silently reinstating the old bug: workflow
recorded `FAILED` and compensated during a routine deploy.

**Decision (coordinator-approved):** make `ExecutorShutdownException` extend
`Error` (Temporal's approach for the same problem — it is a control-flow
signal, not a workflow error). This is a deliberate exception to the "all
exceptions extend MaestroException" convention: document it in the class
Javadoc AND add a note to the repo `CLAUDE.md` coding standards section.
Adjust the catch ordering in `WorkflowExecutor.executeWorkflow` accordingly
(it must still be caught there, before any generic handler).

**Issue 5:** `SagaManager`'s compensation loops (sequential and parallel)
catch `Exception` broadly; shutdown mid-compensation is recorded as a
compensation failure and leaves the workflow `COMPENSATING`. Rethrow
`ExecutorShutdownException` (now an `Error`, so the broad catches naturally
miss it — verify no `catch (Throwable)`/`catch (Error)` blocks it) so it
propagates to `WorkflowExecutor`'s shutdown handling. Already-run
compensations are memoized; a recovering node replays them and continues.

**Tests (failing first):**
- Workflow with `try { awaitSignal } catch (Exception e) {...}` around parking
  survives executor shutdown as `WAITING_SIGNAL`, recoverable.
- Shutdown mid-compensation leaves the workflow recoverable with no
  compensation step recorded as failed; a second harness recovers and
  completes compensation.
- Grep-level audit: no other `catch (Exception)` / `catch (Throwable)` in
  `maestro-core` swallows the shutdown signal (fix any found; list them in
  the report).

**Done when:** both tests green; `CLAUDE.md` documents the Error decision;
existing suites green.

## Task 4: Issues 7 + 9 — configuration seams: shutdown timeout, wake recheck, activity lock prefix

**Kind:** Library gaps/defect. All three are "thread config into the engine"
plumbing.

- `WorkflowExecutor.SHUTDOWN_TIMEOUT` (hardcoded 30s, ~line 76) → new property
  `maestro.shutdown.timeout` (default 30s).
- `SignalManager.DEFAULT_WAKE_RECHECK_INTERVAL` (hardcoded 30s, ~line 75; a
  package-private constructor exists but is never used from outside) → new
  property `maestro.signal.wake-recheck-interval` (default 30s). This bounds
  cross-node signal latency for Kafka-without-SignalNotifier deployments.
- `ActivityInvocationHandler` (~line 404) builds
  `"maestro:lock:activity:%s:%d".formatted(...)` — ignores
  `maestro.lock.key-prefix`, which the instance lock honours. Pass the
  configured prefix down the same way the instance lock manager receives it.

**Tests:** context-runner tests in the starter pinning that configured values
reach the engine (constructor wiring); a unit test asserting the exact
activity lock key produced under a custom prefix; defaults unchanged when
properties absent.

**Done when:** all three configurable/wired, defaults unchanged, pinned by
tests.

## Task 5: Issue 8 — implement MaestroHealthIndicator

**Kind:** Library gap. `CLAUDE.md` promises `io.b2mash.maestro.spring.health` /
`MaestroHealthIndicator`; nothing exists. Decision: implement it (do not
delete the docs line).

Implement a Spring Boot Actuator `HealthIndicator` in the starter reporting:
- store reachability (cheap store call),
- whether recovery poller and timer poller are running,
- count of locally running workflows (`runningCount()` already exists).

Auto-configured only when Actuator's `HealthIndicator` is on the classpath
(`@ConditionalOnClass`) and Maestro is enabled; follow Spring Boot 4 modular
autoconfigure conventions already used by the starter. Status: `UP` when store
reachable and pollers running; `DOWN` when store unreachable; include details
map. Add the starter's optional actuator dependency scope appropriately.

**Tests:** context-runner tests — indicator present with actuator on
classpath, absent without; `DOWN` when store throws; details include running
count and poller states.

**Done when:** `/actuator/health` reports Maestro state in a booted sample
(QA verifies in Task 10); tests green.

## Task 6: Issue 1 — failed signal handlers must never lose signals (Kafka + Postgres)

**Kind:** Library defect, the most serious open. Follow the approved design in
the workspace file `issue1-design.md` (written by the architect agent and
approved by the coordinator before this task dispatches — the dispatch prompt
will carry its path). The design covers all three transports; this task
implements Kafka + Postgres; Task 7 applies it to RabbitMQ.

Measured today: Kafka engine signal channel acks after ONE attempt
(`KafkaWorkflowMessaging.subscribeSignals` catches the handler exception, so
`SignalSubscriptionRunner`'s deliberate rethrow never reaches Kafka);
`@MaestroSignalListener` path retries 10× then skips
(`MaestroSignalListenerBeanPostProcessor`); Postgres transport marks the row
`FAILED` which is terminal and unclaimable (`PostgresWorkflowMessaging.
processSignalMessage` + claim SQL).

**Fixed policy decisions (whatever the design's mechanics):** a handler
exception must not lose the signal; a permanently failing message ends up
somewhere inspectable (dead-letter destination / `DEAD_LETTER` status with a
listing+replay path), never an infinite hot loop; no auto-created topics —
any dead-letter topic is pre-declared configuration, documented; consistent
`maestro.*` property naming; RabbitMQ design included even though Task 7
implements it.

**Executable specs already exist, currently `@Disabled` — enable them and make
them pass:**
- `maestro-integration-tests/.../kafka/KafkaAckOnFailureIT.java`
- `maestro-messaging-postgres/.../PostgresWorkflowMessagingTest.java`
  (`failedHandlerMustNotLoseTheSignal`)

**Done when:** both disabled specs enabled and green; transient handler
failure followed by recovery delivers the signal (test); permanently failing
message lands in the inspectable destination (test); docs updated for any new
configuration.

## Task 7: Issue 10a — RabbitMQ transport: first test suite + Issue 1 parity

**Kind:** Testing gap expected to surface defects. `maestro-messaging-rabbitmq`
has production code and zero tests, and is the same code shape as Kafka —
assume it carries Issue 1.

- Build a real-backend suite mirroring `PostgresWorkflowMessagingTest` /
  the Kafka integration suites, using a Testcontainers RabbitMQ singleton
  started from a static initialiser — read the comments in the existing
  fixtures first (they encode a real pitfall).
- Cover: task publish/subscribe round-trip, signal publish/subscribe,
  lifecycle events, handler-failure behaviour.
- Apply the Task 6 approved design (`issue1-design.md`) to RabbitMQ:
  handler failure must not lose the signal; permanent failure lands
  somewhere inspectable (RabbitMQ-native DLX is acceptable if pre-declared,
  never auto-created).
- Remove `maestro-messaging-rabbitmq` from the `modulesWithoutTests`
  allowlist in root `build.gradle.kts`.
- Any additional defect found: library-bug protocol (failing test in the
  module, fix, continue) and list it in the report.

**Done when:** suite green 3 consecutive `--rerun-tasks` runs, module off the
allowlist, Issue 1 behaviour matches Kafka/Postgres policy.

## Task 8: Issue 10b — test suites for admin-client, admin, store-jdbc

**Kind:** Testing gap. Three modules remain on the allowlist:
`maestro-admin-client` (small, do first), `maestro-admin`, `maestro-store-jdbc`.

- `maestro-admin-client`: unit tests for the lifecycle event publisher —
  serialization shape, error handling, no-op behaviour when disabled.
- `maestro-admin`: Spring context boots against Testcontainers Postgres;
  event-ingestion round-trip persists and is queryable; key controller/view
  endpoints return 200 (MockMvc or WebTestClient level is fine — this is a
  dashboard, aim for meaningful smoke coverage, not UI pixel tests).
- `maestro-store-jdbc`: it is exercised indirectly via
  `maestro-store-postgres`; add direct unit tests only where cheap (e.g. SQL
  fragment builders, mapping helpers). If after inspection nothing testable
  in isolation exists, document that in the report and keep it allowlisted
  with a comment explaining why — do not write vacuous tests.
- Remove each covered module from the allowlist. Defects found → library-bug
  protocol, listed in report.

**Done when:** admin-client and admin have real suites and are off the
allowlist; store-jdbc either has a suite or a documented justification;
everything green.

## Task 9: Documentation truth pass + release notes

**Kind:** Docs. Do this after Tasks 1–8 are complete (it documents their
outcomes).

- Update `docs/open-issues.md`: mark issues 1–10 resolved with one-line
  outcome + commit refs (or "closed invalid" for Issue 2 if the repro showed
  recovery works); leave 11 and 12 open, reframed as "Known limitations".
- Verify `CLAUDE.md` claims are now true (health indicator package,
  `ExecutorShutdownException extends Error` note from Task 3, any new
  config properties added to the configuration section if one exists).
- Write `docs/release-notes.md` for the release: new configuration
  properties (`maestro.shutdown.timeout`, `maestro.signal.wake-recheck-interval`,
  admin events wiring, dead-letter config), behaviour changes
  (`ExecutorShutdownException` now an `Error`, signal no-loss policy, timer
  self-healing), and Known Limitations: Issue 11 (no fencing — split-brain
  tolerated, activities must be idempotent) and Issue 12 (recovery polling
  scales linearly with active workflow count; benchmark before tuning).
- Update `docs/maestro-architecture.md` and `docs/maestro-prd.md` only where
  they now state something false (signal-loss behaviour, timer recovery,
  shutdown semantics). Keep edits surgical.

**Done when:** no doc promises a feature that doesn't exist, no fixed defect
is still documented as open, release notes exist and match the diff.

## Task 10: QA — full verification of the release candidate

**Kind:** Verification. Read `tasks/lessons.md` first — especially the E2E
process-identity lesson — and follow it to the letter.

1. `./gradlew build` clean pass (includes integration tests; needs Docker).
2. `./gradlew :maestro-integration-tests:test --rerun-tasks` — 3 consecutive
   green runs (flake check).
3. `./gradlew :maestro-integration-tests:e2eTest` green.
4. Loan-origination E2E: `cd maestro-samples/sample-loan-origination &&
   ./e2e/run-e2e.sh` — all scenarios pass. Verify process identity: PIDs in
   service logs match the run's pid files, ports confirmed free beforehand,
   jar contents match this branch's build.
5. Admin dashboard live check: run `maestro-admin` against the loan sample,
   confirm lifecycle events arrive and render (HTTP-level checks acceptable:
   events ingested, instance list endpoint shows the run's workflows), and
   `/actuator/health` on a sample service shows the Task 5 Maestro indicator.
6. Report any failure via the library-bug protocol — do not patch tests to
   pass. Failures here reopen the owning task rather than being fixed inline.

**Done when:** all five verification gates pass with evidence (command output
excerpts, PID checks) in the report.
