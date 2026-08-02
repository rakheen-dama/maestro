# Maestro Test Plan — Verification Status and Gaps

**Date:** 2026-07-28 · **Scope:** every feature Maestro provides ·
**Must-work integrations:** **Kafka** and **Postgres** (Valkey is
best-effort; RabbitMQ/Postgres-messaging are secondary).

> **Status (updated after P0–P3, P5):** the integration module
> `maestro-integration-tests` now runs **65 tests** against real Postgres and
> real Kafka on every PR, plus **37** new backend-module tests. Closing these
> gaps found **six shipped defects** — see §5. Remaining: P4 scenario wiring,
> P6 guardrails.
>
> **Further update (release-readiness pass, 2026-07-29):** every item in §5
> "Known and still open" section A (correctness risks) and most of section B
> (design sharp edges) is now fixed — see the inline "Fixed" notes below and
> `docs/open-issues.md` for the authoritative, per-issue detail with commit
> references. `docs/open-issues.md` is the current source of truth for open
> work; treat the rest of this document as the historical record of how the
> gaps were found.
>
> **Further update (2026-07-30):** Issues 13, 14, and 15 — found during the
> pass above — are also now fixed, including item 17 below (admin
> retry/terminate). See `docs/open-issues.md` and `docs/release-notes.md`
> (0.4.0) for details.
>
> **Further update (multi-instance verification cycle, 2026-08-01):** the
> "multi-node" gap this document originally flagged as untested (§2 item 3,
> §5 item 20) is closed. The loan-origination E2E grew from 5 to 10 scenarios
> (owner-kill peer adoption, rolling restart, timer-poller leader failover,
> cross-node admin retry/terminate — each proven on both the Valkey and
> Postgres lock backends) and now runs nightly in CI, not manually; a new
> Testcontainers-orchestrated chaos/soak harness
> (`maestro-integration-tests`' `e2eTest` task, `@Tag("e2e")`) drives a real
> six-node cluster under scripted failure injection and runs nightly
> (10-minute PR-gate mode, 3× consecutive) plus weekly (multi-hour soak). See
> `docs/operations.md` for measured multi-instance guarantees and
> `docs/open-issues.md` Issues 17-20 for the four engine defects this work
> found and fixed. §1's matrix and §5's gap list below are updated in place
> where this closes something; both are otherwise kept as the historical
> record of how earlier gaps were found.

## Why this document exists

The optimistic-lock version-convention bug shipped despite a fully green
build because **every engine test runs against in-memory SPI fakes** — the
engine had never once executed against the real Postgres store until a live
end-to-end run. Coverage that is green at the unit level can still leave
entire integration seams unverified. This plan makes those seams explicit.

**Verification levels used below:**

| Level | Meaning |
|---|---|
| U | Unit / in-memory (fast, in `./gradlew build`) |
| I | Integration against a real backend (Testcontainers, in CI) |
| E | Live E2E (loan-origination `run-e2e.sh`, 10 scenarios incl. multi-node — **nightly + on-demand in CI**, both lock backends; plus the `e2eTest` chaos/soak harness for scripted multi-instance failure injection, also CI-scheduled — see `docs/operations.md`) |
| — | **Not verified anywhere** |

---

## 1. Current verification matrix

### Core engine (maestro-core — 16 unit test classes)

| Feature | U | I | E | Notes / gap |
|---|---|---|---|---|
| Hybrid memoization (replay, failure replay, crash-after-persist dedup) | ✅ | ✅ | ✅ | `EnginePostgresMemoizationIT` — counting activities prove completed steps are NOT re-executed |
| Workflow lifecycle (start/complete/fail, lifecycle events) | ✅ | ✅ | ✅ | `EnginePostgresLifecycleIT` (5) |
| Startup recovery + periodic RecoveryPoller | ✅ | ✅ | ✅ | `EnginePostgresRecoveryIT` — second executor over the same store |
| Signals: deliver / await / pre-arrived / orphan adoption / timeout / late race | ✅ | ✅ | ✅ | `EnginePostgresSignalIT` (6) — full matrix on real PG |
| Signal consume CAS; append-before-consume ordering | ✅ | ✅ | ✅ | `EnginePostgresSignalIT` — CAS loss exercised through the engine |
| Cross-instance wake (SignalNotifier subscribe/publish) | ✅ | ✅ | ✅ | `MultiNodeSignalRoutingIT` (integrated wake) + `PostgresSignalNotifierTest` (LISTEN/NOTIFY). **Found BUG9** — see §5. E now covered by loan-E2E scenarios 6-10 (multi-node, both lock backends) and the chaos harness |
| Cross-node timer wake (sleep() fired/cancelled by a remote timer-poller leader) | ✅ | ✅ | ✅ | `WorkflowExecutorCrossNodeTimerWakeTest`, `multinode.MultiNodeTimerWakeIT`. **Found Issue 17** (routine-operation stall, not a failure scenario) — see `docs/open-issues.md`. E via loan-E2E scenario 9 + the chaos harness |
| Periodic 30s store re-check while parked; spurious-wake re-park | ✅ | ✅ | ✅¹ | ¹ Now also I/E: `multinode.MultiNodeTimerWakeIT` (I) and loan-E2E scenarios 6-10 + the chaos harness (E) exercise it across real nodes, not just implicitly on one |
| collectSignals (N-of-M, FIFO by received_at) | ✅ | ✅ | ✅ | `EnginePostgresSignalIT`, `MultiNodeSignalRoutingIT` (cross-node FIFO) |
| Timers: sleep, TimerPoller, SKIP LOCKED, CAS fire, leader election | ✅ | ✅ | ✅ | `EnginePostgresTimerIT` — poller fires from PG; timer scheduled by one executor fired by another |
| Saga compensation (LIFO, parallel, partial failure, COMPENSATING) | ✅ | ✅ | ✅ | `EnginePostgresSagaIT` — LIFO by execution AND persisted sequence; version march pinned |
| Retry (RetryExecutor, RetryPolicy, retryUntil) | ✅ | ✅ | ✅ | `EnginePostgresLifecycleIT` (retry exhaustion → FAILED) |
| Parallel branches (sequence-block partitioning, overflow guard) | ✅ | ✅ | — | `EnginePostgresParallelIT` — distinct sequence blocks per branch |
| Queries (@QueryMethod, in-memory only) | ✅ | — | — | Documented single-node limitation |
| Instance lock (acquire/release/renew, NO_BACKEND, HELD_ELSEWHERE, lost-lock, TTL validation) | ✅ | ✅ | ✅ | `MultiNodeLockContentionIT`, `MultiNodeOwnerDeathIT`, `PostgresDistributedLockContractTest` (24) |
| Graceful shutdown | ✅ | ✅ | — | **BUG6 FIXED.** `WorkflowExecutorShutdownTest` (7) + `ShutdownContractIT` (6): parked workflows stay WAITING_*, no compensation, recoverable by a fresh node |
| Determinism guardrails | — | — | — | Nothing detects nondeterministic workflow code; replay divergence is silent |

### Spring Boot starter (3 test classes)

| Feature | U | I | E | Notes |
|---|---|---|---|---|
| Auto-configuration activation chain (store → engine → client) | ✅³ | — | ✅ | ³ via PostgresStoreAutoConfigurationTest context runner |
| @DurableWorkflow classpath scanning; @ActivityStub injection | ✅ | — | ✅ | |
| StartupRecoveryRunner, SignalSubscriptionRunner ordering | ✅ | — | ✅ | |
| SignalSubscriptionRunner against a **real** maestro.signals.* topic | — | ✅ | — | `KafkaSignalChannelIT` — the channel is fed for the first time |
| MaestroClient (startAsync, startAndWait, signal, query handles) | ✅ | — | ✅ | `MaestroClientTest` (8) through the real auto-config chain |
| Health indicator | ✅ | — | — | **Implemented** (post-audit). `io.b2mash.maestro.spring.health.MaestroHealthIndicator`, auto-configured with Actuator; bounded 2s store probe, three-state poller reporting (`starting`/running/`disabled`). `MaestroHealthAutoConfigurationTest`, `MaestroHealthIndicatorTest` |

### Kafka (must-work) — maestro-messaging-kafka (2 test classes)

| Feature | U | I | E | Notes |
|---|---|---|---|---|
| KafkaWorkflowMessaging publish/subscribe (tasks, signals, lifecycle) | — | ✅ | ◐ | Module Testcontainers tests exist; engine-level signals channel unused in E2E |
| @MaestroSignalListener discovery + routing → deliverSignal | ✅ | ✅ | ✅ | `KafkaSignalListenerRoundTripIT` — full round trip in CI |
| At-least-once semantics: handler failure vs ack | — | ✅ | — | **Fixed.** Bounded exponential-backoff redelivery + dead-letter on exhaustion, all transports (`maestro.messaging.redelivery.*`). `KafkaAckOnFailureIT` + `PostgresWorkflowMessagingTest` + `RabbitMqWorkflowMessagingTest` — desired-behaviour specs enabled and green, no longer `@Disabled` |
| Consumer-group routing: signal consumed on node B for workflow parked on node A | — | ✅ | — | `MultiNodeSignalRoutingIT`; E2E scenario 6 adds a two-instance run |
| Duplicate delivery tolerance end-to-end | — | ✅ | ◐ | `KafkaDuplicateDeliveryIT` — 2 rows, 1 consume, 1 completion |

### Postgres (must-work) — store + lock + messaging

| Feature | U | I | E | Notes |
|---|---|---|---|---|
| PostgresWorkflowStore (instances, events, signals, timers, concurrency, version CAS) | — | ✅ | ✅ | 37 Testcontainers tests — good |
| PostgresStoreAutoConfiguration | ✅ | — | ✅ | |
| Flyway migrations | ◐ | ✅ | ✅ | `MaestroMigrationsCoexistIT` — all modules on one DB. **Found BUG5** |
| **Engine × real Postgres store** (start→signal→timer→saga→recover on PG) | — | ✅ | ✅ | **CLOSED** — 32 tests across 8 `EnginePostgres*IT` suites |
| maestro-lock-postgres (acquire/release/renew/leader, LockBackendException) | ✅ | ✅ | ✅ | `PostgresDistributedLockContractTest` (24, Testcontainers) + the pre-existing 4 backend-failure unit tests (the module was **not** testless — the earlier claim was wrong) |
| maestro-messaging-postgres (queues, SKIP LOCKED claim, LISTEN/NOTIFY notifier, redelivery/dead-letter) | — | ✅ | — | 17 tests: `PostgresWorkflowMessagingTest` (10, incl. redelivery/dead-letter) + `PostgresSignalNotifierTest` (7). **Found BUG9**; ack-on-failure signal loss fixed (former Issue 1) |

### Other

| Area | Status |
|---|---|
| maestro-test kit (in-memory SPIs, TestWorkflowEnvironment, clock) | ✅ U (3 classes) |
| maestro-lock-valkey | ✅ I (lock + notifier mechanics) |
| maestro-messaging-rabbitmq | ✅ real suite (`RabbitMqWorkflowMessagingTest`); the shared ack-on-failure defect it carried is fixed alongside Kafka and Postgres |
| maestro-admin / admin-client | ✅ real suites (`DashboardSmokeMockMvcTest`, `EventIngestionRoundTripTest`, `AdminEventPublisherTest`, `AdminCommandDispatcherTest`, `AdminCommandKafkaIT`, others). `$maestro:retry`/`terminate` commands are now consumed end-to-end by a starter-side `AdminCommandDispatcher` — dashboard buttons are functional; see `docs/open-issues.md` Issue 15 (**Resolved**) |
| Loan-origination E2E (10 scenarios, incl. 5 multi-node) | ✅ E — nightly + on-demand in CI, both Valkey and Postgres lock backends (`.github/workflows/e2e-nightly.yml`) |
| Multi-instance chaos/soak harness (`maestro-integration-tests` `e2eTest`, `@Tag("e2e")`) | ✅ E — 6-node Testcontainers cluster, scripted failure injection; nightly PR-gate mode (3× consecutive) + weekly soak mode in CI. Found and fixed Issues 17-20. See `docs/operations.md` |

---

## 2. Ranked gaps (what has NOT been verified to work)

1. **Engine on real Postgres in CI** — all engine behaviour is proven only
   against fakes plus one manual E2E. This is the class of gap that shipped
   BUG1 (version convention) and BUG2 (no store bean).
2. **Kafka signal round-trip in CI** — `@MaestroSignalListener` → persist →
   wake on a real broker is only verified manually; the engine-level
   `maestro.signals.{service}` channel consumer is never fed at all.
3. **Multi-node behaviour.** *Closed by the 2026-08-01 multi-instance
   verification cycle* — see the update banner at the top of this document
   and `docs/operations.md`. At the time this list was written: never tested
   anywhere — consumer-group signal routing to a non-owning node,
   cross-instance notifier wake, two-node instance-lock contention,
   recovery-poller adoption after owner death, duplicate-adoption behaviour
   with no lock backend.
4. **maestro-lock-postgres has zero tests** — the default lock backend for
   the Postgres-only profile is entirely unverified at module level.
5. **Transport ack-on-failure** (deferred defect) — no test pins the desired
   "failed handler must not lose the signal" contract.
6. **Graceful shutdown recoverability** (known bug) — no test encodes that a
   parked workflow must survive shutdown as WAITING_*, not FAILED.
7. **maestro-messaging-postgres zero tests** (secondary backend).
8. Parallel-branch execution unexercised outside unit tests; no determinism
   guardrails; health indicator/audit items; admin stack untested.

---

## 3. Test plan

Ordered by risk-reduction per effort. Each phase is independently shippable.

### P0 — Engine × Postgres integration suite *(highest value)*
New module or source-set `maestro-integration-tests` (Testcontainers PG),
running `WorkflowExecutor` + `PostgresWorkflowStore` (+`PostgresDistributedLock`):
- start → activities → complete (events, version march, memoized replay)
- crash simulation: new executor over the same store mid-flow → recovery
  replay resumes from first uncompleted step; activities not re-executed
- full signal matrix on PG: pre-arrived, orphan adoption, park/wake,
  timeout, CAS double-consume attempt
- sleep + TimerPoller firing from PG (SKIP LOCKED, CAS fire), timer
  survives executor restart
- saga compensation path writes COMPENSATING/FAILED with correct versions
- optimistic-lock conflict surfaces as OptimisticLockException (regression
  pin for BUG1)
**Exit criterion:** every engine feature in §1 has an I row on Postgres.

### P1 — Kafka integration suite in CI *(must-work seam)*
Testcontainers Kafka + PG in `maestro-integration-tests`:
- `@MaestroSignalListener` round-trip: publish event → listener routes →
  signal persisted → parked workflow wakes → completes
- `SignalSubscriptionRunner`: publish a `SignalMessage` to
  `maestro.signals.{service}` → delivered into a workflow
- duplicate delivery: same event twice → one consume, extra row tolerated
- **ack-on-failure contract (encodes the deferred fix)**: handler throws →
  message redelivered, signal not lost (initially `@Disabled` with a link
  to the defect if the adapter fix hasn't landed)
- lifecycle events reach the admin topic

### P2 — Multi-node suite *(the production topology)*
Two `WorkflowExecutor` instances (one JVM, two service "nodes") over shared
PG + Kafka (+ lock backend):
- instance-lock contention: only one node runs a given workflow; second
  reports HELD_ELSEWHERE
- owner dies (close executor A without release) → TTL expiry → node B's
  recovery poller adopts and completes
- signal ingested on node B for workflow parked on node A → woken via
  notifier (Valkey profile) and via 30s re-check (no-notifier profile;
  short interval injected)
- consumer-group variant with two real Kafka consumers in one group
- no-lock-backend profile: document/assert the duplicate-adoption behaviour

### P3 — maestro-lock-postgres + maestro-messaging-postgres suites
Mirror the Valkey test structure: acquire/contention/expiry-reacquire,
release-with-wrong-token, renew true/false/`LockBackendException`, leader
election; messaging: queue claim (SKIP LOCKED), redelivery of stale
PROCESSING rows, LISTEN/NOTIFY notifier wake, FAILED-row terminality
(pin current behaviour, then revisit).

### P4 — Promote the loan E2E into CI
Wrap scenarios 1–5 as JUnit `@Tag("e2e")` tests driving the boot apps via
Testcontainers compose (or keep `run-e2e.sh` behind a nightly CI job).
**Must keep the PID/artifact identity assertions** — a readiness probe
answering 200 does not prove *your build* is serving (lesson learned).
Add scenario 6: two instances of loan-application-service (multi-node
happy path).

### P5 — Shutdown & lifecycle correctness
Encode the *desired* contract (currently a known bug): graceful shutdown
leaves parked workflows WAITING_* and recoverable, runs no compensation;
in-flight activities drain up to the timeout; locks released or left to
TTL. Write these tests first (RED) as the spec for the shutdown fix.

### P6 — Guardrails & audits
Determinism lint/test-mode check (replay-twice-and-diff in
TestWorkflowEnvironment); parallel-branch coverage in an I test; health
indicator audit; admin-command end-to-end once implemented; RabbitMQ suite
if that backend is kept.

### CI wiring
- `test` = U only (fast). New `integrationTest` source set/tag for P0–P3
  (Testcontainers; runs on every PR). P4 e2e tag nightly + pre-release.
- Coverage gate: every `maestro-*` module with production code must have
  ≥1 test class (fails today for lock-postgres, messaging-postgres,
  messaging-rabbitmq, admin, admin-client).

---

## 5. Defects found by closing these gaps

Every one of these was invisible to a fully green in-memory build. That is the
thesis of this document, restated as evidence.

| # | Defect | Found by | Status |
|---|---|---|---|
| BUG5 | `lock-postgres` and `messaging-postgres` both shipped Flyway `V100` into `classpath:db/migration`; Flyway aborts on the duplicate version, so the Postgres-only profile could never migrate | `MaestroMigrationsCoexistIT` | **Fixed** — disjoint version bands (store 1–99, lock 100–199, messaging 200–299) |
| BUG6 | Graceful shutdown marked parked workflows `FAILED` and ran their compensations — stopping a node could refund a customer whose order was merely awaiting approval | `WorkflowExecutorShutdownTest`, `ShutdownContractIT` | **Fixed** — typed `ExecutorShutdownException`; parked workflows keep `WAITING_*` and stay recoverable |
| BUG7 | A version conflict while finalising recorded a **successful** workflow as `FAILED`, contradicting its own `WORKFLOW_COMPLETED` event and compensating a saga after a successful run | `WorkflowExecutorTerminalTransitionTest`, multi-node work | **Fixed** — `transitionToTerminal` retries against a fresh read and stands down when another runner finalised |
| BUG8 | Every nested `@ConfigurationProperties` block was inert: the records declared no-arg constructors, so Boot skipped value-object binding and silently bound nothing. `maestro.messaging.topics.*`, `lock.ttl`, `timer.*`, `recovery.*`, `retry.*`, `store.table-prefix`, `worker.task-queues` were stuck at defaults in every deployment | `MaestroPropertiesBindingTest` | **Fixed** — one canonical constructor per record, defaults via `defaults()` |
| BUG9 | `PostgresNotificationListener.listen()` only queued the `LISTEN`, applied up to 500 ms later; Postgres delivers `NOTIFY` only to already-listening sessions, so cross-instance wake was silently lossy | `PostgresSignalNotifierTest`, `MultiNodeSignalRoutingIT` | **Fixed** — `listen()` blocks until the command executes |

### Known and still open

> **For working on these, read [`docs/open-issues.md`](open-issues.md) instead.**
> It is a standalone brief — it explains how Maestro works, where each defect
> lives, how to reproduce it, what a fix looks like and how to know it worked,
> without assuming any context. The list below is the summary index.

Ranked by risk. Each item names the evidence, so none of these rests on
recollection. "Unverified" below means no test asserts the behaviour either way.

#### A. Correctness risks — fixed since this table was written

1. **Ack-on-failure loses signals (all transports).** *High.* **Fixed.**
   Was measured, not assumed: the engine signal channel acked after **one**
   attempt, because `KafkaWorkflowMessaging.subscribeSignals` caught and
   logged, defeating `SignalSubscriptionRunner`'s deliberate rethrow; the
   `@MaestroSignalListener` path retried **ten** times then logged and
   skipped; the Postgres adapter marked the row `FAILED`, unreachable by any
   claim query. Now: bounded exponential-backoff redelivery
   (`maestro.messaging.redelivery.*`) plus a dead-letter destination on every
   transport (Kafka `.DLT` topic, Postgres `DEAD_LETTER` status + replay API,
   RabbitMQ `<queue>.dlq`). Executable specs `KafkaAckOnFailureIT`,
   `PostgresWorkflowMessagingTest`, `RabbitMqWorkflowMessagingTest` are
   enabled and green. See `docs/open-issues.md` Issue 1.

2. **Timer fired-but-not-appended is a permanent stall.** *High.* **Fixed.**
   Was: if a timer was marked `FIRED` and the process died before its
   `TIMER_FIRED` event was appended, replay re-parked on a timer the poller
   would never fire again, forever. Now: `WorkflowStore.findTimer` lets
   replay consult the row's actual status and self-heal — append the missing
   `TIMER_FIRED` and continue — rather than re-parking. Pinned by
   `EnginePostgresTimerIT.timerFiredBeforeEventAppend_recoveryCompletesTheWorkflow`.
   **A related, narrower case — a timer *cancelled* (not fired) while a
   workflow is parked on it — is also fixed** (`docs/open-issues.md`
   Issue 13): cancelling now unparks the workflow with a catchable
   `TimerCancelledException` instead of stranding it. Pinned by
   `EnginePostgresTimerIT.timerCancelledBeforeEventAppend_recoveryFailsTheWorkflowDeterministically`.

3. **Lifecycle publishing can stall workflow start.** *Medium.* **Fixed.**
   `publishLifecycleEvent` was fire-and-forget for *errors* but not for
   *latency*: an absent or unreachable admin topic made the producer block on
   metadata lookup for `max.block.ms` — 60 s by default — inside
   `startWorkflow`. Observed: it timed out every loan E2E scenario at 150 s.
   Now: lifecycle publishing runs on a bounded, dropping executor off the
   workflow thread; `startWorkflow` returns promptly regardless of transport
   latency. Pinned by `KafkaLifecycleEventLatencyIT` (real broker, missing
   topic, asserts `startWorkflow` returns in <1s).

4. **A lost lock does not abort the local run.** *Medium, accepted by design.*
   `LockHandle` carries a fencing token but nothing validates it in the store, so
   a node that loses its lock to a >TTL pause keeps running. Duplicate *persisted
   results* are still prevented by the unique event index; duplicate *side
   effects* are not. Closing this needs fencing-token validation in the
   `WorkflowStore` SPI.

5. **The no-lock-backend profile duplicates execution.** *By design, now
   characterised.* `MultiNodeNoLockBackendIT` pins what actually happens: a
   second node launches its own copy and an in-flight activity runs once per
   node, so activities must be idempotent in that profile. Memoized steps still
   replay rather than re-run, the unique index admits one event per sequence, and
   the losing node adopts the winner's result.

#### B. Design sharp edges

6. **`ExecutorShutdownException` is a `RuntimeException`.** **Fixed** — it now
   extends `Error` (a deliberate, documented exception to the repo's
   "everything extends `MaestroException`" convention; see `CLAUDE.md` §
   Coding Standards), so a workflow author's broad `catch (Exception e)`
   around `awaitSignal`/`sleep` can no longer swallow it. Breaking change for
   third-party code catching it as a `MaestroException` — see
   `docs/release-notes.md`.

7. **Shutdown during compensation is not clean.** **Fixed** — `SagaManager`'s
   parallel-compensation branch outcome-collection now rethrows
   `ExecutorShutdownException` before recording a step as failed, and
   `WorkflowExecutor.executeWorkflow` nests a catch for it around
   `handleWorkflowFailure`. Pinned by
   `WorkflowExecutorShutdownTest.shutdown_duringCompensation_leavesItRecoverableAndCompletesOnRecovery`
   and the equivalent `ShutdownContractIT` test against real Postgres.

8. **`PostgresNotificationListener.listen()` now blocks.** If a notification
   callback ever called `listen()`, it would run on the poll thread and wait for
   that same thread, stalling every channel until the 5 s timeout. No current
   caller does this (callbacks only `unpark` or `notifyAll`), so it is latent —
   but the blocking contract is new and has no reentrancy guard.

9. **Two hardcoded 30 s values with no configuration seam.** **Fixed** —
   `maestro.shutdown.timeout` and `maestro.signal.wake-recheck-interval` are
   now real configuration properties (both default 30s, unchanged), threaded
   through `WorkflowExecutor`/`SignalManager`. See `docs/configuration.md` §
   Shutdown and Signal Configuration.

10. **`maestro.admin.events.enabled` / `.topic` are read by nothing.** **Fixed** —
    `enabled` genuinely gates lifecycle publishing; `.topic` is a documented,
    deprecated alias for `maestro.messaging.topics.admin-events` (the
    messaging property wins on conflict, with a WARN).

11. **The health indicator does not exist.** **Fixed** —
    `io.b2mash.maestro.spring.health.MaestroHealthIndicator` now exists,
    auto-configured with Actuator. See §1's "Health indicator" row above.

12. **`ActivityInvocationHandler` hardcodes the `maestro:lock:activity:` prefix**,
    ignoring `maestro.lock.key-prefix`. **Fixed** — it now honours the same
    configured prefix as the instance lock.

13. **`EventType.WORKFLOW_STARTED` is never written.** Start is published only as
    a `LifecycleEventType`. The enum constant is dead in the store's event space.

#### C. Coverage still missing

14. **Four modules have no tests at all.** **Fixed** — `maestro-admin`,
    `maestro-admin-client`, `maestro-messaging-rabbitmq`, and
    `maestro-store-jdbc` all gained real suites; the root build's coverage-gate
    allowlist is now empty. Writing the `maestro-admin` suite also found two
    Spring Boot 4 modular-autoconfiguration gaps that meant the app couldn't
    have booted in production (missing `spring-boot-starter-kafka` /
    `-flyway`) — both fixed alongside the tests.

15. **RabbitMQ has no suite** and carries the same ack-on-failure defect as Kafka.
    **Fixed** — `RabbitMqWorkflowMessagingTest` is a real Testcontainers
    suite, and the shared redelivery/dead-letter fix applies to RabbitMQ too.
    **Valkey** is still covered only for lock and notifier mechanics, not
    integrated engine behaviour — that part is unchanged.

16. **Queries (`@QueryMethod`) are single-node by design and untested on a real
    backend** — a query against a node not running the workflow throws
    `WorkflowNotQueryableException`. No integration test pins that.

17. **The admin dashboard is unverified end-to-end.** **Fixed** — beyond the
    controller-layer coverage (`DashboardSmokeMockMvcTest`,
    `EventIngestionRoundTripTest`), `$maestro:retry` and `$maestro:terminate`
    are now consumed by a new starter-side `AdminCommandDispatcher`, wired
    ahead of ordinary signal delivery, and proven end-to-end against a real
    Kafka broker by `AdminCommandKafkaIT` and the rewritten
    `KafkaSignalChannelIT.adminCommand_terminatesWorkflowAndIsNeverPersisted`.
    Tracked as `docs/open-issues.md` Issue 15 (**Resolved**).

18. **`DeterminismChecker` catches only between-run nondeterminism** — randomness,
    clock reads, mutable static state. A value that is stable for a JVM's
    lifetime but changes across restarts or code edits still slips through, and
    replay divergence against a *stored* event log is not detected at all.

19. **Five hand-rolled `WorkflowStore` fakes and three lock fakes** live across
    `maestro-core`'s tests. Every SPI change ripples through all of them, and
    they can drift from real backend semantics — which is precisely how the
    original gap arose.

20. **The loan E2E runs nightly, not per PR**, on one machine. *Partially
    superseded:* it is no longer the only place a real `kill -9` is
    exercised — the multi-instance chaos harness (`docs/operations.md`) also
    kills, pauses, and partitions real containers, and runs on the same
    nightly/weekly CI cadence as a Testcontainers suite rather than a
    single-machine script.

#### D. Scale deferrals

21. **`getRecoverableInstances()` has no service or staleness filter** — every
    node re-reads the whole active set on each poll and probes the lock for each
    foreign-owned instance. Needs a filter plus an index (an SPI change).

22. **Lock renewal is serial** — one round-trip per held lock every TTL/3. Needs
    batching before a node holds thousands of parked workflows.

23. **Wake subscriptions churn per await** rather than being scoped to the
    workflow's local lifetime.

#### One thing I could not explain

The loan E2E failed 6/6 on its first run here because `maestro.admin.events` was
missing, yet the same topic was equally absent when the suite passed 5/5 earlier
in the day. The producer-blocking mechanism is understood and the topic is now
pre-created, but *what changed between those two runs* was never established
from the artifacts available. It is recorded rather than rationalised: if this
resurfaces, start there.

## 4. Explicitly out of scope (for now)

Valkey beyond existing coverage (best-effort), RabbitMQ parity suite,
admin dashboard UI tests, performance/load testing. **No longer out of
scope, closed 2026-08-01:** chaos testing of >TTL pauses — the multi-instance
chaos harness's `PAUSE_RESUME` action freezes a real container past the 30s
instance-lock TTL on every run (mandated ≥2 loan-node occurrences per run);
split-brain is still accepted-by-design (fencing tokens have not landed —
`docs/open-issues.md` Issue 11), but its measured consequences are no longer
unmeasured — see `docs/operations.md` and Issue 11's evidence section.
