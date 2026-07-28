# Maestro Test Plan — Verification Status and Gaps

**Date:** 2026-07-28 · **Scope:** every feature Maestro provides ·
**Must-work integrations:** **Kafka** and **Postgres** (Valkey is
best-effort; RabbitMQ/Postgres-messaging are secondary).

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
| Hybrid memoization (replay, failure replay, crash-after-persist dedup) | ✅ | — | ✅ | Never exercised against real Postgres in CI |
| Workflow lifecycle (start/complete/fail, lifecycle events) | ✅ | — | ✅ | Same |
| Startup recovery + periodic RecoveryPoller | ✅ | — | ✅ | Recovery against real store only in manual E2E (kill -9 scenario) |
| Signals: deliver / await / pre-arrived / orphan adoption / timeout / late race | ✅ | ◐ | ✅ | Store ops I-tested (PostgresWorkflowStoreTest); engine signal flow on real store: E only |
| Signal consume CAS; append-before-consume ordering | ✅ | ◐ | ✅ | CAS I-tested at store level only |
| Cross-instance wake (SignalNotifier subscribe/publish) | ✅ | ◐ | — | Valkey pub/sub mechanics I-tested (ValkeySignalNotifierTest); **integrated wake of a parked workflow via notifier: never**; Postgres LISTEN/NOTIFY notifier: **no tests at all** |
| Periodic 30s store re-check while parked; spurious-wake re-park | ✅ | — | ✅¹ | ¹ E2E exercises it implicitly (single node); no I test |
| collectSignals (N-of-M, FIFO by received_at) | ✅² | — | ✅ | ² via loan-sample tests (in-memory) |
| Timers: sleep, TimerPoller, SKIP LOCKED, CAS fire, leader election | ✅ | ◐ | ✅ | getDueTimers/markTimerFired I-tested; poller loop + leader election only with stubs; **timer fires across restart: E only** |
| Saga compensation (LIFO, parallel, partial failure, COMPENSATING) | ✅ | — | ✅ | |
| Retry (RetryExecutor, RetryPolicy, retryUntil) | ✅ | — | ✅ | |
| Parallel branches (sequence-block partitioning, overflow guard) | ✅ | — | — | **Not exercised by the loan sample or E2E** (design chose loop-fan-in) |
| Queries (@QueryMethod, in-memory only) | ✅ | — | — | Documented single-node limitation |
| Instance lock (acquire/release/renew, NO_BACKEND, HELD_ELSEWHERE, lost-lock, TTL validation) | ✅ | — | ✅¹ | ¹ E observed lock behaviour incidentally; **no I test of the lock manager against real Valkey or Postgres locks** |
| Graceful shutdown | ◐ | — | — | Known bug: parked workflows are marked FAILED (+compensated!) on shutdown instead of staying recoverable — **no test encodes the desired behaviour** |
| Determinism guardrails | — | — | — | Nothing detects nondeterministic workflow code; replay divergence is silent |

### Spring Boot starter (3 test classes)

| Feature | U | I | E | Notes |
|---|---|---|---|---|
| Auto-configuration activation chain (store → engine → client) | ✅³ | — | ✅ | ³ via PostgresStoreAutoConfigurationTest context runner |
| @DurableWorkflow classpath scanning; @ActivityStub injection | ✅ | — | ✅ | |
| StartupRecoveryRunner, SignalSubscriptionRunner ordering | ✅ | — | ✅ | |
| SignalSubscriptionRunner against a **real** maestro.signals.* topic | — | — | — | Runner subscribes in E2E but no scenario publishes to the channel |
| MaestroClient (startAsync, startAndWait, signal, query handles) | ◐ | — | ✅ | No dedicated client test class |
| Health indicator | — | — | — | Audit: confirm whether `io.b2mash.maestro.spring.health` is implemented at all |

### Kafka (must-work) — maestro-messaging-kafka (2 test classes)

| Feature | U | I | E | Notes |
|---|---|---|---|---|
| KafkaWorkflowMessaging publish/subscribe (tasks, signals, lifecycle) | — | ✅ | ◐ | Module Testcontainers tests exist; engine-level signals channel unused in E2E |
| @MaestroSignalListener discovery + routing → deliverSignal | ✅ | ◐ | ✅ | BPP unit-tested; full listener→signal→parked-workflow round-trip on real Kafka: **E only, not CI** |
| At-least-once semantics: handler failure vs ack | — | — | — | **Known deferred defect**: Kafka/Rabbit adapters ack even when the handler throws → a failed `deliverSignal` loses the signal. No test encodes desired nack/retry/DLT behaviour |
| Consumer-group routing: signal consumed on node B for workflow parked on node A | — | — | — | The **normal** production topology; never tested anywhere (E2E is single-node) |
| Duplicate delivery tolerance end-to-end | — | — | ◐ | Unit-level duplicate signal tests only |

### Postgres (must-work) — store + lock + messaging

| Feature | U | I | E | Notes |
|---|---|---|---|---|
| PostgresWorkflowStore (instances, events, signals, timers, concurrency, version CAS) | — | ✅ | ✅ | 37 Testcontainers tests — good |
| PostgresStoreAutoConfiguration | ✅ | — | ✅ | |
| Flyway migrations | ◐ | ✅⁴ | ✅ | ⁴ implicitly via store tests |
| **Engine × real Postgres store** (start→signal→timer→saga→recover on PG) | — | — | ✅ | **The BUG1 gap. Highest-risk hole in the suite.** |
| maestro-lock-postgres (acquire/release/renew/leader, LockBackendException) | — | — | ✅¹ | **Zero test classes in the module** |
| maestro-messaging-postgres (queues, SKIP LOCKED claim, LISTEN/NOTIFY notifier) | — | — | — | **Zero test classes**; secondary backend but ships in releases |

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

## 4. Explicitly out of scope (for now)

Valkey beyond existing coverage (best-effort), RabbitMQ parity suite,
admin dashboard UI tests, chaos testing of >TTL JVM pauses (split-brain is
accepted-by-design until fencing tokens land), performance/load testing.
