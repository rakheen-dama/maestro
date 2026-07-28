# Maestro Test Plan — Verification Status and Gaps

**Date:** 2026-07-28 · **Scope:** every feature Maestro provides ·
**Must-work integrations:** **Kafka** and **Postgres** (Valkey is
best-effort; RabbitMQ/Postgres-messaging are secondary).

> **Status (updated after P0–P3, P5):** the integration module
> `maestro-integration-tests` now runs **65 tests** against real Postgres and
> real Kafka on every PR, plus **37** new backend-module tests. Closing these
> gaps found **six shipped defects** — see §5. Remaining: P4 scenario wiring,
> P6 guardrails.

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
| E | Live E2E (loan-origination `run-e2e.sh` — currently **manual, not CI**) |
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
| Cross-instance wake (SignalNotifier subscribe/publish) | ✅ | ✅ | — | `MultiNodeSignalRoutingIT` (integrated wake) + `PostgresSignalNotifierTest` (LISTEN/NOTIFY). **Found BUG9** — see §5 |
| Periodic 30s store re-check while parked; spurious-wake re-park | ✅ | — | ✅¹ | ¹ E2E exercises it implicitly (single node); no I test |
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
| Health indicator | — | — | — | **Audit answered: NOT IMPLEMENTED.** No `io.b2mash.maestro.spring.health` package and no `*Health*` class exists, though `CLAUDE.md` documents `MaestroHealthIndicator`. Docs/code gap, not a test gap |

### Kafka (must-work) — maestro-messaging-kafka (2 test classes)

| Feature | U | I | E | Notes |
|---|---|---|---|---|
| KafkaWorkflowMessaging publish/subscribe (tasks, signals, lifecycle) | — | ✅ | ◐ | Module Testcontainers tests exist; engine-level signals channel unused in E2E |
| @MaestroSignalListener discovery + routing → deliverSignal | ✅ | ✅ | ✅ | `KafkaSignalListenerRoundTripIT` — full round trip in CI |
| At-least-once semantics: handler failure vs ack | — | ◐ | — | **Still deferred, now specified.** `KafkaAckOnFailureIT` + `PostgresWorkflowMessagingTest` pin current behaviour and carry `@Disabled` desired-behaviour specs. Measured: engine channel acks after 1 attempt; listener path retries 10 then skips; Postgres marks the row FAILED terminally |
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
| maestro-messaging-postgres (queues, SKIP LOCKED claim, LISTEN/NOTIFY notifier) | — | ✅ | — | 13 tests: `PostgresWorkflowMessagingTest` (8) + `PostgresSignalNotifierTest` (5). **Found BUG9** |

### Other

| Area | Status |
|---|---|
| maestro-test kit (in-memory SPIs, TestWorkflowEnvironment, clock) | ✅ U (3 classes) |
| maestro-lock-valkey | ✅ I (lock + notifier mechanics) |
| maestro-messaging-rabbitmq | **Zero tests** (secondary; same ack-on-failure defect as Kafka) |
| maestro-admin / admin-client | **Zero tests**; `$maestro:retry/terminate` commands are dropped by design (unimplemented) — dashboard buttons are non-functional end-to-end |
| Loan-origination E2E (5 scenarios) | ✅ E — but **manual**, single-node, and Valkey-profile only |

---

## 2. Ranked gaps (what has NOT been verified to work)

1. **Engine on real Postgres in CI** — all engine behaviour is proven only
   against fakes plus one manual E2E. This is the class of gap that shipped
   BUG1 (version convention) and BUG2 (no store bean).
2. **Kafka signal round-trip in CI** — `@MaestroSignalListener` → persist →
   wake on a real broker is only verified manually; the engine-level
   `maestro.signals.{service}` channel consumer is never fed at all.
3. **Multi-node behaviour** — never tested anywhere: consumer-group signal
   routing to a non-owning node, cross-instance notifier wake, two-node
   instance-lock contention, recovery-poller adoption after owner death,
   duplicate-adoption behaviour with no lock backend.
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

#### A. Correctness risks — unfixed

1. **Ack-on-failure loses signals (all transports).** *High.* Measured, not
   assumed: the engine signal channel acks after **one** attempt, because
   `KafkaWorkflowMessaging.subscribeSignals` catches and logs, defeating
   `SignalSubscriptionRunner`'s deliberate rethrow; the `@MaestroSignalListener`
   path retries **ten** times then logs and skips; the Postgres adapter marks the
   row `FAILED`, and its claim query only ever selects `PENDING` or stale
   `PROCESSING`, so the signal is gone permanently. This contradicts the
   engine's own "never discard a signal" rule. Deferred because a real fix needs
   a dead-letter destination, new configuration, a topic-creation policy
   decision (Maestro never auto-creates topics) and a mirrored RabbitMQ change.
   Executable specs: `KafkaAckOnFailureIT` and `PostgresWorkflowMessagingTest`,
   both `@Disabled` with precise messages.

2. **Timer fired-but-not-appended is a permanent stall.** *High.* If a timer is
   marked `FIRED` and the process dies before its `TIMER_FIRED` event is
   appended, replay re-parks on a timer the poller will never fire again. The
   workflow waits forever with no error. Pre-existing and unrelated to the
   shutdown work — a `kill -9` in that window reproduces it. Unverified: no test
   covers it.

3. **Lifecycle publishing can stall workflow start.** *Medium.* `publishLifecycleEvent`
   catches exceptions, so it is fire-and-forget for *errors* but not for
   *latency*: an absent or unreachable admin topic makes the producer block on
   metadata lookup for `max.block.ms` — 60 s by default — inside
   `startWorkflow`. Observed: it timed out every loan E2E scenario at 150 s. Only
   the sample was fixed (the topic is now pre-created). The library-side fix is
   to bound `max.block.ms` on the admin producer or publish off the workflow
   thread, so observability can never gate execution.

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

6. **`ExecutorShutdownException` is a `RuntimeException`.** A workflow author who
   catches broadly around `awaitSignal`/`sleep` can swallow it and silently
   reinstate the old "shutdown fails the workflow" behaviour. Temporal uses an
   `Error` precisely so this cannot happen. The repo's convention (everything
   extends `MaestroException`) was followed and the hazard documented on the
   exception, but the trade-off deserves a second opinion.

7. **Shutdown during compensation is not clean.** `SagaManager`'s compensation
   loop catches `Exception` broadly, so an `ExecutorShutdownException` thrown by
   an activity mid-compensation is treated as a compensation failure rather than
   as "this node is stopping". The instance is left `COMPENSATING` — non-terminal
   and recoverable, so nothing corrupts — but it is not the intended semantics.
   Unverified: no test covers shutdown racing compensation.

8. **`PostgresNotificationListener.listen()` now blocks.** If a notification
   callback ever called `listen()`, it would run on the poll thread and wait for
   that same thread, stalling every channel until the 5 s timeout. No current
   caller does this (callbacks only `unpark` or `notifyAll`), so it is latent —
   but the blocking contract is new and has no reentrancy guard.

9. **Two hardcoded 30 s values with no configuration seam.** `SHUTDOWN_TIMEOUT`
   in `WorkflowExecutor`, and `SignalManager`'s parked-workflow re-check
   interval, which is unreachable from `WorkflowExecutor`'s public API. The
   second one also bounds production cross-node signal latency for
   Kafka-without-Valkey deployments, so it is a real operational knob that
   cannot be turned.

10. **`maestro.admin.events.enabled` / `.topic` are read by nothing.** The topic
    that works is `maestro.messaging.topics.admin-events`, and lifecycle
    publishing cannot be disabled at all. `sample-order-service` and the
    loan sample both set the inert property. Either wire it or delete it.

11. **The health indicator does not exist.** `CLAUDE.md` documents
    `io.b2mash.maestro.spring.health.MaestroHealthIndicator`; no such package or
    class is present. Docs promise a feature the code does not have.

12. **`ActivityInvocationHandler` hardcodes the `maestro:lock:activity:` prefix**,
    ignoring `maestro.lock.key-prefix`, which the instance lock now honours.

13. **`EventType.WORKFLOW_STARTED` is never written.** Start is published only as
    a `LifecycleEventType`. The enum constant is dead in the store's event space.

#### C. Coverage still missing

14. **Four modules have no tests at all:** `maestro-admin`, `maestro-admin-client`,
    `maestro-messaging-rabbitmq`, `maestro-store-jdbc`. Held as an explicit
    allowlist in the root build's coverage gate, so new untested modules fail the
    build while this debt stays visible.

15. **RabbitMQ has no suite** and carries the same ack-on-failure defect as Kafka.
    **Valkey** is covered only for lock and notifier mechanics, not integrated
    engine behaviour.

16. **Queries (`@QueryMethod`) are single-node by design and untested on a real
    backend** — a query against a node not running the workflow throws
    `WorkflowNotQueryableException`. No integration test pins that.

17. **The admin dashboard is unverified end-to-end.** `$maestro:retry` and
    `$maestro:terminate` are dropped with a WARN, so the dashboard buttons do
    nothing; no engine-side command dispatcher exists.

18. **`DeterminismChecker` catches only between-run nondeterminism** — randomness,
    clock reads, mutable static state. A value that is stable for a JVM's
    lifetime but changes across restarts or code edits still slips through, and
    replay divergence against a *stored* event log is not detected at all.

19. **Five hand-rolled `WorkflowStore` fakes and three lock fakes** live across
    `maestro-core`'s tests. Every SPI change ripples through all of them, and
    they can drift from real backend semantics — which is precisely how the
    original gap arose.

20. **The loan E2E runs nightly, not per PR**, on one machine, and is the only
    place a real `kill -9` is exercised.

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
admin dashboard UI tests, chaos testing of >TTL JVM pauses (split-brain is
accepted-by-design until fencing tokens land), performance/load testing.
