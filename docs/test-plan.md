# Maestro Test Plan — Verification Status and Gaps

**Date:** 2026-07-28 · **Scope:** every feature Maestro provides · **Inventory refreshed:** 2026-10-28 (197 test classes / 1299 `@Test` across 18 modules — counts from `find … -name '*Test.java' -o -name '*IT.java'` and `grep -rhE '^\s*@(Test|ParameterizedTest|RepeatedTest)\b'`; `maestro-bom` has no sources and is excluded)
**Must-work integrations:** **Kafka** and **Postgres** — since 2026-11-04 the
*only* integrations: `maestro-lock-valkey`, `maestro-messaging-postgres` and
`sample-postgres-only` were removed and `maestro-lock-postgres` folded into
`maestro-store-postgres` (`docs/release-notes.md`).

> **Superseded 2026-11-04.** Rows and narrative below that name the Valkey lock
> module, the Postgres messaging transport, `E2E_LOCK_BACKEND`,
> `ValkeyBrownoutSignalDeliveryIT`, `ValkeySignalNotifier*Test` or
> `PostgresWorkflowMessagingTest` describe past cycles and are kept as history.
> Current state: one lock backend (Postgres, in `maestro-store-postgres`), one
> transport (Kafka); `PostgresSignalNotifierTest` lives in
> `maestro-store-postgres` and its `publishDoesNotWaitForAConnection` pins the
> Issue 32 never-blocks guarantee; `MultiNodeSignalRoutingIT` wakes through the
> store-postgres `LISTEN/NOTIFY` notifier; the loan E2E runs on Postgres only.

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
> **Further update (release-hardening cycle, 2026-08-02):** **RabbitMQ
> messaging support has been removed** — `maestro-messaging-rabbitmq` and
> `sample-rabbitmq-order-service` are deleted, and the supported transports are
> now Kafka and Postgres only (see `docs/release-notes.md` for the rationale).
> Every mention of RabbitMQ below is part of this document's historical record
> of the release-readiness pass and does **not** describe a shipped backend.
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

### Core engine (maestro-core — 72 test classes, 621 tests)

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
| Determinism guardrails | ✅ | ✅ | — | Replay drift (step name/type/fork arity) stands down as `nondeterministic_history`; value-level nondeterminism with an unchanged step sequence is still only caught by `DeterminismChecker`. `ReplayDriftStandDownTest`, `ReplayGuardTest`, `SagaManagerTest`, `ReplayDriftStandDownIT` |

### Spring Boot starter (17 test classes, 134 tests)

| Feature | U | I | E | Notes |
|---|---|---|---|---|
| Auto-configuration activation chain (store → engine → client) | ✅³ | — | ✅ | ³ via PostgresStoreAutoConfigurationTest context runner |
| @DurableWorkflow classpath scanning; @ActivityStub injection | ✅ | — | ✅ | |
| StartupRecoveryRunner, SignalSubscriptionRunner ordering | ✅ | — | ✅ | |
| SignalSubscriptionRunner against a **real** maestro.signals.* topic | — | ✅ | — | `KafkaSignalChannelIT` — the channel is fed for the first time |
| MaestroClient (startAsync, startAndWait, signal, query handles) | ✅ | — | ✅ | `MaestroClientTest` (8) through the real auto-config chain |
| Health indicator | ✅ | — | — | **Implemented** (post-audit). `io.b2mash.maestro.spring.health.MaestroHealthIndicator`, auto-configured with Actuator; bounded 2s store probe, three-state poller reporting (`starting`/running/`disabled`); a probe timeout is `UNKNOWN` (3.5a). `MaestroHealthAutoConfigurationTest`, `MaestroHealthIndicatorTest` |

### Kafka (must-work) — maestro-messaging-kafka (11 test classes, 59 tests)

| Feature | U | I | E | Notes |
|---|---|---|---|---|
| KafkaWorkflowMessaging publish/subscribe (tasks, signals, lifecycle) | — | ✅ | ◐ | Module Testcontainers tests exist; engine-level signals channel unused in E2E |
| @MaestroSignalListener discovery + routing → deliverSignal | ✅ | ✅ | ✅ | `KafkaSignalListenerRoundTripIT` — full round trip in CI |
| At-least-once semantics: handler failure vs ack | — | ✅ | — | **Fixed.** Bounded exponential-backoff redelivery + dead-letter on exhaustion, all transports (`maestro.messaging.redelivery.*`). `KafkaAckOnFailureIT` + `PostgresWorkflowMessagingTest` — desired-behaviour specs enabled and green, no longer `@Disabled` |
| Consumer-group routing: signal consumed on node B for workflow parked on node A | — | ✅ | — | `MultiNodeSignalRoutingIT`; E2E scenario 6 adds a two-instance run |
| Duplicate delivery tolerance end-to-end | — | ✅ | ◐ | `KafkaDuplicateDeliveryIT` — 2 rows, 1 consume, 1 completion |

### Postgres (must-work) — store + lock + messaging

| Feature | U | I | E | Notes |
|---|---|---|---|---|
| PostgresWorkflowStore (instances, events, signals, timers, concurrency, version CAS) | — | ✅ | ✅ | 37 Testcontainers tests — good |
| PostgresStoreAutoConfiguration | ✅ | — | ✅ | |
| Flyway migrations | ◐ | ✅ | ✅ | `MaestroMigrationsCoexistIT` — all modules on one DB. **Found BUG5** |
| **Engine × real Postgres store** (start→signal→timer→saga→recover on PG) | — | ✅ | ✅ | **CLOSED** — 32 tests across 8 `EnginePostgres*IT` suites |
| Postgres lock (acquire/release/renew/leader, LockBackendException) — now in `maestro-store-postgres`, formerly `maestro-lock-postgres` | ✅ | ✅ | ✅ | `PostgresDistributedLockContractTest` (24, Testcontainers) + the pre-existing 4 backend-failure unit tests (the module was **not** testless — the earlier claim was wrong) |
| maestro-messaging-postgres (queues, SKIP LOCKED claim, LISTEN/NOTIFY notifier, redelivery/dead-letter) — *module removed 2026-11-04* | — | ✅ | — | Historical: 17 tests, `PostgresWorkflowMessagingTest` (10, deleted with the module) + `PostgresSignalNotifierTest` (7, now in `maestro-store-postgres` covering the per-service wake channel). **Found BUG9**; ack-on-failure signal loss fixed (former Issue 1) |

### Other

| Area | Status |
|---|---|
| maestro-test kit (in-memory SPIs, TestWorkflowEnvironment, clock) | ✅ U (7 classes, 72 tests) |
| maestro-lock-valkey — *module removed 2026-11-04* | Historical: ✅ I (lock + notifier mechanics) |
| maestro-admin / admin-client | ✅ real suites (`DashboardSmokeMockMvcTest`, `EventIngestionRoundTripTest`, `AdminEventPublisherTest`, `AdminCommandDispatcherTest`, `AdminCommandKafkaIT`, others). `$maestro:retry`/`terminate` commands are now consumed end-to-end by a starter-side `AdminCommandDispatcher` — dashboard buttons are functional; see `docs/open-issues.md` Issue 15 (**Resolved**) |
| Loan-origination E2E (10 scenarios, incl. 5 multi-node) | ✅ E — nightly + on-demand in CI; Postgres lock only since 2026-11-04 (earlier runs below covered both Valkey and Postgres lock backends via `E2E_LOCK_BACKEND`, now gone) (`.github/workflows/e2e-nightly.yml`) — schedule was auto-disabled 2026-10-10 → 2026-10-24 (GitHub 60-day inactivity rule, see § CI wiring); soak failed 5/5 and PR-gate 2/30 between 2026-08-09 and 2026-10-04, baseline dispatch 40593820947 (2026-10-27) at 66cdf95 was wholly green; the second dispatch 40674015602 (2026-10-28, same SHA) failed the soak with one I1 workflow (a Valkey outage stalled signal sends, so retries duplicated a signature — Issue 32), PR-gate and both loan E2Es green; triaged in docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/ViolationTriageReport.md and fixed (Issue 32, `1da4f1a`); the bug-fix-branch dispatch 40762898010 (2026-10-28) at ec9286d was wholly green — all four jobs success, the 2 h soak ran 2320 workflows with 0 violations (`triage/branch-verification.md`) |
| Multi-instance chaos/soak harness (`maestro-integration-tests` `e2eTest`, `@Tag("e2e")`) | ✅ E — 6-node Testcontainers cluster, scripted failure injection; nightly PR-gate mode (3× consecutive) + weekly soak mode in CI. Found and fixed Issues 17-20. See `docs/operations.md` — schedule was auto-disabled 2026-10-10 → 2026-10-24 (GitHub 60-day inactivity rule, see § CI wiring); soak failed 5/5 and PR-gate 2/30 between 2026-08-09 and 2026-10-04, baseline dispatch 40593820947 (2026-10-27) at 66cdf95 was wholly green; the second dispatch 40674015602 (2026-10-28, same SHA) failed the soak with one I1 workflow (a Valkey outage stalled signal sends, so retries duplicated a signature — Issue 32), PR-gate and both loan E2Es green; triaged in docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/ViolationTriageReport.md and fixed (Issue 32, `1da4f1a`); the bug-fix-branch dispatch 40762898010 (2026-10-28) at ec9286d was wholly green — all four jobs success, the 2 h soak ran 2320 workflows with 0 violations (`triage/branch-verification.md`) |

### Bug-fix cycle 2026-10-24 — new pins

| Finding | Unit pin (in-memory) | Integration pin (real Postgres) | Plan |
|---|---|---|---|
| C1 parallel() failure leaves the parent seq inside the branch space | `ParallelFailureCompensationSequenceTest` | `EnginePostgresParallelSagaIT` | 01 |
| C2 parallel-compensation fork marker not replay-guarded | `SagaManagerTest` | `EnginePostgresParallelSagaIT` | 01 |
| C3 999-step branch limit (`BranchSequenceExhaustedException`) | `ParallelBranchExhaustionTest`, `WorkflowContextBoundTest` | `EnginePostgresParallelIT` | 01 |
| C4 overflow-guarded sequence arithmetic | `SequenceSpaceTest`, `SequenceSpaceExhaustionTest` | `EnginePostgresParallelSagaIT` | 01 |
| Review Focus 1 — parallel() inside saga compensation | `CompensationWithParallelTest` | `EnginePostgresParallelSagaIT` | 01 |
| C8 Error escaping the workflow body loops instead of failing | `WorkflowExecutorErrorEscapeTest` | `ErrorEscapeFailsWorkflowIT` | 02 |
| C9 unreadable-payload stand-down carries cause, redacts content | `UnknownHistoryGuardTest`, `ForeignPayloadStandDownTest` | `UnreadablePayloadStandDownIT` | 02 |
| C10 replay drift → `nondeterministic_history` stand-down (F3 docs) | `ReplayDriftStandDownTest`, `ReplayGuardTest` | `ReplayDriftStandDownIT` | 02 |
| C15 replayed activity failures reconstruct deterministically | `ActivityFailureReconstructionTest` | `ActivityFailureReplayIT` | 02 |
| F2 `ActivityExecutionException.causedBy` | `ActivityExecutionExceptionTest`, `PaymentProcessingWorkflowTest` (sample) | — | 02 |
| C5 stale run stands down at first collision / terminal read | `InstanceStatusWriterTest`, `WorkflowExecutorDuplicateEventStandDownTest`, `StickyStaleStopPointsTest` | `EnginePostgresDualExecutionIT`, `MultiNodeNoLockBackendIT` | 03 |
| C6 terminate stops live activities and retry backoff | `WorkflowExecutorTerminateTest`, `RetryExecutorTest` | `EnginePostgresTerminateIT` | 03 |
| C7 compensation re-checks instance status per entry | `SagaManagerTerminateRaceTest` | `EnginePostgresSagaIT` | 03 |
| C11 per-attempt `startToCloseTimeout` (`ActivityTimeoutException`) | `ActivityInvocationHandlerTest` | `EnginePostgresActivityTimeoutIT` | 03 |
| C12 durable `awaitSignal` deadlines via timer rows (F4 docs) | `SignalManagerTest`, `AwaitDeadlineStoreErrorTest`, `AwaitTimerStrayPermitTest` | `EnginePostgresSignalTimeoutIT`, `AwaitSignalTimeoutRaceIT` | 04 |
| C13 / Issue 26 atomic terminal status + event write | `TerminalWritesTest`, `InMemoryWorkflowStoreTest` | `TerminalWriteAtomicityIT`, `TerminalEventRaceIT` | 04 |
| C14 DB-assigned `received_at`, `SignalJanitor`, acknowledged Valkey wake subscribe | `SignalJanitorTest`, `ValkeySignalNotifierTest`, `ValkeySignalNotifierAckTimeoutTest` | `PostgresWorkflowStoreTest` (ORDER BY `received_at`, Testcontainers) | 04 |
| Issue 27 redelivery claim counting / stale-claim retirement | — | `PostgresWorkflowMessagingTest` (Testcontainers) | 04 |
| F1 per-run workflow instance (prototype scope, per-run factory) | `WorkflowExecutorPerRunInstanceTest`, `WorkflowRegistrarPrototypeTest`, sample workflow tests | `MultiNodePerRunQueryIT` | 05 |
| F5/F9 Maestro-owned Flyway history, module-specific locations | `MaestroSchemaInitializerTest`, `PostgresStoreAutoConfigurationHostFlywayTest` | `MaestroMigrationsCoexistIT` | 05 |
| F6 `maestro.store.table-prefix` removed (fail fast) | `MaestroPropertiesBindingTest`, `JdbcStoreConfigurationTest`, `PostgresStoreAutoConfigurationTest` | — | 05 |
| F8 / Issue 25 per-task-queue concurrency (`TaskQueueGovernor`) | `TaskQueueGovernorTest`, `WorkflowExecutorConcurrencyTest` | `TaskQueueConcurrencyIT` | 05 |
| F10 unused task-dispatch channel / Postgres lifecycle queue deleted | `WorkflowMessagingSurfaceTest`, `PostgresMessagingRemovedTasksTopicTest`, `MaestroAutoConfigurationRemovedPropertiesTest` | — | 05 |
| F11 `WorkflowHandle` status/result/terminate | `MaestroClientTest` | — | 05 |
| F12 sequence/replay mutators moved to `WorkflowContextInternals` | `WorkflowContextSurfaceTest` | — | 05 |
| F13 BOM module, build/competitive-table/timeout/drift docs | — (docs and `maestro-bom`, no sources) | — | 05 |
| Plan 06 Task 3 de-flaked `Build & Test` suites (R53) | `RecoveryPassIsolationTest` (poisoned instance adopted first) | `TracingParallelBranchIT` (parking fixture no longer races the timer poller); Kafka Spring suites close their context after the class | 06 |
| Plan 06 Task 2 chaos-violation triage (I1/I4) — `triage/ViolationTriageReport.md`; the one dumped violation (soak 40674015602) is class 8, filed as **Issue 32** (Valkey outage stalls signal sends; retries duplicate signals) — **Resolved** at `1da4f1a` (plan 04 Task 8, fix round `4880710`) | `SignalManagerTest` (blocking-notifier case: `deliverSignalDoesNotWaitForABlockingNotifier` — `deliverSignal` returns promptly while `SignalNotifier.publish` blocks, one row, notified off-thread) | `ValkeyBrownoutSignalDeliveryIT` (Valkey paused: `signal()` on node B returns promptly, persists one row, and node A's parked workflow still wakes) | 04 / 06 |

Every integration pin gates on the terminal **event** via `TerminalWait` (Review Focus 5), not on the instance row, so it cannot pass on the non-atomic-write window Issue 26 closed.

### Load suite (k6, `perf/`)

A k6 harness that drives `maestro-samples/sample-perf` (Maestro's true
defaults, unpublished) on a cold Docker Compose stack (Postgres 16 with
`pg_stat_statements`, Kafka 3.9 with every topic pre-created, Prometheus) and
judges each run from what Postgres recorded. D4: numbers first — pass/fail
rests only on statement counts and correctness; throughput, latency, memory
and slots are reported, never gated.

**Commands** (from the repository root; one run per host at a time — the
host-wide lock `/tmp/maestro-perf.lock` refuses a second):

| Scenario | Command | What it answers |
|---|---|---|
| smoke | `perf/run.sh smoke` | 20 chain runs (2 × 10 ms) complete; every measurement path works |
| s1-throughput | `perf/run.sh s1-throughput` | one node's throughput ceiling at the defaults; statements per workflow (chain 5 × 50 ms) |
| s3-parked | `perf/run.sh s3-parked` | the steady store load of 1k–10k parked runs, per parked run per minute; `PERF_PARK_MODE=sleep` parks on a durable `sleep()` instead of a signal |
| s2-recovery-drain | `perf/run.sh s2-recovery-drain` (2 replicas) | finding 3.1 (WP6): 5,000 orphaned runs drained by one node, then by a rolling start of two |
| s4-signals | `perf/run.sh s4-signals` (2 replicas) | finding 3.2 (WP2): signal delivery on the run's own node vs another, and whether a delivery leaves a wake permit behind |
| s5-pool-health | `perf/run.sh s5-pool-health` | finding 3.5a (WP4): Maestro's health component while the host's own database work exhausts the shared pool |
| s6-retention | `perf/run.sh s6-retention` (2 replicas) | findings 3.11a/b (WP8): a node's startup retention sweep and signal janitor over a large terminal backlog, against foreground load |

Options: `--profile local|full` (default `local`), `--replicas N` (default 1;
`s2-recovery-drain` and `s6-retention` run exactly 2, `s4-signals` defaults to
2 and refuses 1 — refused before anything is touched),
`--record-baseline` (S1, S3; clean tree only: judges the run *without* the
existing `perf/baselines/<scenario>.json`, and on PASS replaces it, printing
old and new values side by side; see *Re-recording a baseline* below). Environment: `PERF_ADMIN_EVENTS=true|false`
(default `false`, the engine's default since 3.11c; every result records it in
`identity.config.adminEventsRequested`, `identity.effective.adminEventsEffective`,
`validity.adminEvents` and `summary.md`), `PERF_PARK_MODE`, `PERF_KEEP_UP=1`,
`PERF_ENGINE_REF`, `PERF_NODE_HEAP` / `PERF_NODE_CPUS` / `PERF_NODE_MEM`,
`PERF_BASELINE_ALLOW_SETTINGS_CHANGE=1` (with `--record-baseline`: allow a
baseline of other settings to replace the old one).

**Prerequisites:** Docker with ≥ 6 GiB of memory (a run below that records a
WARN note), `docker compose` v2, `jq`, `unzip`, JDK 25 (the run builds and
stamps the sample's jar with `./gradlew :maestro-samples:sample-perf:bootJar`).

**Profiles:**

| Scenario | local | full |
|---|---|---|
| s1-throughput | levels 5 / 20 / 40 / 80 starts/s, ramp 5 s, hold 40 s; about 6 min | levels 5 / 10 / 20 / 40 / 60 / 80 / 120, ramp 10 s, hold 120 s; about 20–25 min |
| s3-parked | plateaus 1000 / 2000 parked, idle 60 s, hold 120 s; about 12 min | plateaus 1000 / 5000 / 10000, idle 120 s, hold 300 s; about 35 min |
| s2-recovery-drain | N = 5,000, chain 2 × 2 s, probes 0.2/s, phases A and B, drain timeout 1,800 s per phase; about 16–20 min | the same plus phase C (600 rows, 15 fresh starts/s for 120 s) |
| s4-signals | 2 nodes, W = 450 runs × M = 10 signals, levels 50 and 100/s × 30 s, 200 sleep runs; about 6 min | 3 nodes, W = 1,500, levels 50 / 100 / 200 / 400/s, 600 sleep runs |
| s5-pool-health | 10 hold runs × 5 × 8 s; health 30 / 35 / 30 s; about 5 min | 10 × 10 × 8 s; exhausted window 75 s |
| s6-retention | 84,000 seeded instances (sets in `perf/sql/s6-seed.sql`), windows 60 s + sweep (≤ 180 s) + 60 s; about 8 min | 252,000 instances; sweep allowance 600 s |

**Check classes and the verdict** (`perf/eval/common.jq`; allow-list, first
match wins): **ERROR** (exit 3) — a check is ERROR or of an unknown class or
status, an advisory reports anything but PASS/WARN, or there are no checks;
**INVALID** (exit 2) — a *validity* check is not PASS (the measurement cannot
be trusted: identity of the jar on every node, no restart, admin events as
requested, no `pg_stat_statements` eviction, k6 exit and dropped iterations,
warm-up drained, the background window, the load window's record (smoke, S1;
S3: `validity.record`), periodic sweeps accounted for, the executed plan);
**FAIL** (exit 1) — a *correctness* check is not PASS or
NO_BASELINE, or a *gate* is FAIL or WARN; **INVALID** — a gate could not be
judged; otherwise **PASS** (NO_BASELINE gates count as pass). *Advisory*
checks (latency, ceiling, contention, heap, slots, decomposition, …) only
PASS or WARN and never change the verdict.

**Gate rules:**

- Statement counts are reported gross and **net**: the background rate
  measured on the idle stack (timer-poll ticks counted from the due-timer
  query, never assumed) is subtracted; lock-cleaner passes are identified by
  text and subtracted exactly; a signal-janitor or retention statement in a
  window makes the run INVALID.
- `gate.statementsPerWorkflow` (S1) and `gate.parkedCostBaseline` (S3):
  `statements_gate`, **symmetric** — PASS when |net − expected| ≤ band,
  band = tolerance (one tick per background poller) + relativeAllowance ×
  expected, relativeAllowance 0.02 in both scenarios (justified in each
  evaluator's header); a drop below the band FAILs too, and its detail says
  how to re-record the baseline if the drop is intended. INVALID when the tolerance alone exceeds 25 % of
  expected. NO_BASELINE when no baseline matches (S1: workflow shape, profile,
  replicas, `adminEventsEnabled`; S3: park mode, wake-recheck and
  recovery-poll intervals, lock TTL, plateaus and Pmax, profile, replicas,
  `adminEventsEnabled`). A baseline file of another `schema`
  or `scenario` makes the gate INVALID; an empty or unparseable one, or one
  holding more than one JSON document or a non-object, makes it ERROR (never
  "absent"). A key that appears twice in a baseline file takes its last
  value (jq's parser). A gate that used a baseline names its `runId` and
  `engineCommit` in the detail.
- `gate.parkedCostLinear` (S3): every plateau's per-parked cost within ± 25 %
  of the smallest plateau's.
- `gate.parkedCostArithmetic` (S3, absolute, with or without a baseline):
  every plateau within ± 5 % (+ tolerance) of the documented arithmetic
  (`docs/operations.md` §13) at the run's park mode, replicas and bound
  intervals — centre 6.017 per parked run per minute for `awaitSignal` at one
  node, 6.032 at two, 4.017 for `sleep` at one node.
- `gate.statementsMeasured` (smoke): net statements per workflow in [5, 500].

**Results:** `perf/results/<runId>/` (git-ignored): `identity.json` (repo
head, `engineCommit` and `harnessCommit` — the last commits touching the
engine modules and the harness paths —, dirty flag, host, images and digests,
container limits, every node's `/perf/info`, effective settings),
`measurements.json`, `result.json`, `summary.md`, `prometheus.json`,
`compose.log`, k6 summaries. `result.json` is schema 1:
`{schema: 1, identity, scenario, profile, measurements, checks: [{id, class,
status, value, limit, detail}], verdict}`. The last stdout line is always
`VERDICT <PASS|FAIL|INVALID|ERROR> <dir>`. Baselines live in `perf/baselines/`
(committed; each carries its source `runId`, `engineCommit`, `harnessCommit`,
profile, replicas, `adminEventsEnabled` and the figure). Run evidence cited
in docs and harness headers is committed as `perf/.evidence/wp11/<runId>.result.json`.

**Re-recording a baseline** (after an intended change of a gated figure,
e.g. an engine change that removes statements — the symmetric gate FAILs it
against the old baseline, by design):

1. Commit the change; the tree must be clean (`git status` shows nothing
   outside `perf/results/`).
2. `perf/run.sh <scenario> --profile <profile> --replicas <n> --record-baseline`
   with the profile and replicas of the baseline you replace (both committed
   baselines are `local`, 1 replica). The run is judged without the old
   baseline: validity and correctness must PASS, and every absolute gate
   (S3's linearity and arithmetic) still judges; a run that does not PASS
   leaves the file untouched (exit 3, "--record-baseline refused"). So does
   a run whose matched settings differ from the old baseline's (profile,
   replicas, `adminEventsEnabled`, workflow shape; S3 also park mode, the
   wake-recheck and recovery-poll intervals, lock TTL, plateaus, Pmax): the
   weekly CI gates against that file. To change the settings on purpose, set
   `PERF_BASELINE_ALLOW_SETTINGS_CHANGE=1` (exactly `1`) for that run; the
   change is printed.
3. On PASS the file is rewritten and the old and new values are printed side
   by side (also in `perf/results/<runId>/baseline-record.txt`). Review
   `git diff perf/baselines/` and commit it on its own, with the reason.
4. To record a second scenario, commit the first baseline before its run
   (the new file makes the tree dirty).

**Start retries and connection keep-alive.** A refused start fails S1's
correctness with zero tolerance. A start that fails at the transport (status
0: the connection failed or was closed under the request, k6's "Request
Failed … EOF", or k6's request timeout) is retried **once** with the same
body, so the same `workflowId` (`perf/k6/lib/common.js` `startWorkflow`,
every start script). It is accepted when the first attempt got 202, or the
retry got 202 or 409 (`PerfController`'s answer for an existing
`workflowId`: the first attempt had created the row); any other status is
never retried. k6 counts retries in `start_retries`; each k6 record carries
`startRetries`, and S1 reports its phase's as `s1StartRetries` in
`correctness.allCompleted` — a non-zero count is a note in the detail, never
a failure. A retry that fails again is a failed check: FAIL. The node keeps
an idle keep-alive connection for 5 min (`server.tomcat.keep-alive-timeout:
5m` in `sample-perf`'s `application.yml`; unset, Tomcat uses its connection
timeout), longer than any VU waits between two requests (about 120 s: 600
VUs at 5 starts/s), so it does not close a connection a VU is about to
reuse. This is the harness's connection setting, not a Maestro one.

**Self-tests (no Docker):** `perf/eval/selftest.sh <smoke dir> <s1 dir> <s3 dir>`
doctors recorded runs one fault at a time and asserts the verdict each fault
must produce (`perf/eval/selftest-evidence.sh` runs it on the three source
runs rebuilt from `perf/.evidence/wp11/`, including the S1 run's committed
`prometheus.json`, so it needs no local results); `perf/lib/lock-selftest.sh`
races the run lock; `perf/lib/baseline-selftest.sh` exercises the record
path on committed evidence (an intended improvement recorded; an INVALID or
FAIL run, a settings change without the override, or a failing write refused
with the old file untouched); `perf/lib/evidence-selftest.sh` asserts that no
committed evidence or baseline holds an absolute path, a `pwd` or a host
name, and that `run.sh` still records `uname -srm`. All but the lock race
pass on jq 1.7.1 and 1.8.1.

**CI:** a pull request that touches `perf/**`, `sample-perf` or the
workflow runs `perf.yml`'s `selftest` job (contents: read): the evidence
guard, the evaluator self-test from committed evidence and the
baseline-record self-test (the evaluator self-test covers the WP12 scenarios
from `perf/.evidence/wp12/` too); never a load run. The load job:
`.github/workflows/perf.yml` (`Perf (k6)`) — `workflow_dispatch`
(scenario — any of the seven —, profile, replicas, admin_events; defaults
`s1-throughput`, `local`, empty = the scenario's own replica count, `false`)
and Saturday 04:00 UTC: s1-throughput, s3-parked, s5-pool-health,
s2-recovery-drain and s6-retention in parallel jobs, **local** profile (the
profile both committed baselines were recorded with, so the baseline gates
judge; `full` is a manual choice, and its baseline gates read NO_BASELINE).
s4-signals is dispatch-only: 100 signals/s on two nodes of a shared 4-vCPU
runner risks dropped iterations (INVALID). Never on pull requests: a load run on a shared 4-vCPU
runner is not a merge signal (pull requests run only the self-tests above). What is hardware-sensitive on such a runner
(the statement figures are not): dropped iterations and the plan's iteration
count (`validity.droppedIterations`, `validity.plan`; also the warm-up drain,
`validity.warmup` — all INVALID), Scenario 3's 600 s plateau wait
(`correctness.plateauReached`) and the drain timeouts (S1 300 s local /
1200 s full, S3 probes 120 s, S3 release 120 s + Pmax / 10 s; `correctness.*`
— all FAIL). FAIL and ERROR fail the job on every trigger; INVALID fails a
dispatched job, but a **scheduled INVALID is a warning annotation and the job
succeeds**; a NO_BASELINE gate is a warning annotation. Read a FAIL's detail
before calling it an engine regression. Results are uploaded for 14 days.
`gh workflow run perf.yml -f scenario=smoke -f profile=local`.

**Adding a scenario (WP12):** a `perf/scenarios/<name>.sh` defining
`scenario_run` (writes `measurements.json`), a `perf/eval/<name>.jq` (includes
`common`, returns `result(...)`) and its k6 script under `perf/k6/` — nothing
else; `run.sh` dispatches on the file names (add the name to its scenario
`case` and to `perf.yml`'s choice list). Shared pieces a scenario can use:
`perf/lib/topology.sh` (`node_stop` / `node_start` / `node_wait_ready` by exact
container name; each event lands in `measurements.topology`, so
`validity.identity`, `validity.noRestart` and `validity.sweeps` judge planned
restarts, and each planned start allows its own startup sweep in the window
it falls in); the in-database activity sampler
(`perf/sql/activity-sampler.sql`: a procedure counting the engine's active
backends matching a pattern at 10 Hz, as role postgres, unlogged;
`activity-samples.sql` summarises a label over a time window, with a
time-weighted busy fraction and the largest gap inside the busy span);
`K6_NODES` (a k6 phase to a subset of nodes); `measurements.phaseTable`
(rendered in `summary.md`). Every background pid a scenario starts is
registered with `bg_register`: `run.sh` kills them by pid on any exit, and a
loop also stops when the harness is gone or past its deadline
(`perf/lib/bgloop-selftest.sh`).

**First numbers** (2026-11-16/17, engine 9eb7ca6 = `main` 525b287 merged
into the harness branch; one node unless stated; admin events off unless
stated; MacBook Pro, 10 CPUs, Docker 7837 MiB, a **shared** host: "contended"
= `advisory.contention` WARN, host load above 2 per CPU). Every runId's
`result.json` is committed under `perf/.evidence/wp11/`; the baselines are
`perf/baselines/s1-throughput.json` (r20261116t231034z) and
`perf/baselines/s3-parked.json` (r20261116t231822z).

| Run | runId | Verdict | Figures |
|---|---|---|---|
| smoke, local | r20261116t230707z, r20261116t230851z | PASS, PASS | net statements per workflow 16.98, 16.97 (chain 2 × 10 ms); foreign rows 0 |
| smoke, 2 replicas | r20261116t231624z | PASS | 17.06; two distinct hostnames |
| S1 local, baseline record | r20261116t231034z | PASS | 26 net statements per workflow (6337 workflows); ceiling 71.60 completions/s; sustained level 40; contended |
| S1 local, gate re-run | r20261116t232948z | PASS | gate 26 against 26 ± 0.52; ceiling 73.43/s, hold-window 75.36/s, engine overhead 16.96 ± 2.9 ms; quiet |
| S1 local, admin events on ×2 | r20261116t222331z, r20261116t225610z | PASS, PASS | 26 and 26 net (164860, 164857 calls vs 164862, 164858 off: r20261116t221747z, r20261116t222856z); ceilings 73.16, 73.48 on vs 73.01, 70.61 off; all four contended |
| S1 full | r20261116t234614z | PASS when run (gate NO_BASELINE: the baseline is local); **FAIL** under the evaluator of the first fix wave: 1 of 42973 starts was refused (k6 "Request Failed … EOF" with no HTTP status; no row), and a refused start is a correctness failure. Probable cause, not proven: the node closed an idle keep-alive connection just as a VU reused it. Since then the node keeps idle connections for 5 min and k6 retries such a start once (see *Start retries*) | 26.01 net over 42972 workflows; completions/s per level L5 4.99, L10 9.80, L20 19.57, L40 39.21, L60 59.19, L80 74.26, L120 75.61 (offered 79.24 / 118.43); p95 309.2 / 321.4 / 397.7 / 417.8 / 395.3 / 8379.0 / 82102.9 ms; contended (2.06 per CPU) |
| S3 local, baseline record | r20261116t231822z | PASS | 6.0154 / 6.0137 statements per parked run per minute at P = 1000 / 2000 (3.0077 / 3.0068 per 30 s); linearity 0.9997; arithmetic centre 6.017 |
| S3 local, gate re-run | r20261116t233507z | PASS | 6.0165 / 6.0161; baseline gate 6.0161 against 6.0137 ± 0.121; quiet |
| S3 local, sleep mode | r20261116t224549z | PASS | 4.0173 / 4.0169 against the 4.017 arithmetic; re-check 2.0001 / 1.9999 reads per parked run per 30 s |
| S3 full | r20261117t000526z | **INVALID** | k6's virtual users were exhausted seeding P = 5000 (723 dropped iterations; the start endpoint's p95 reached 4 s on a host at up to 14.48 load per CPU), so 4278 runs parked and the plateau was not reached. Whether the system under test was slow or the generator was starved is not established either way: the run measures neither. P = 1000 measured 6.0151 (arithmetic gate PASS). To be re-run on a quiet host |
| cherry-pick onto cbf5a2a (pre-WP11 engine): smoke | r20261117t002705z | PASS | 21.07; `engineCommit` fe7e548 (the last engine commit at cbf5a2a, same engine tree), `harnessCommit` 64416c3; four empty queries (the three WP11 meters and `maestro_wake_permits`) |
| cherry-pick onto cbf5a2a: S1 local | r20261117t002853z | **FAIL**, on the baseline gate only | 36 net statements per workflow (228232 calls) against the 26 baseline: the gate sees the per-activity lock's 10 statements; the WP11-meter advisories say "not available" |

The pre-merge engine measured 36 net statements per workflow in S1 (four quiet
local runs, e.g. r20261112t223114z) and the same S3 figures (6.0141–6.0172,
r20261113t030358z and others): removing the per-activity lock (3.10) took
exactly 10 statements (2 × 5 activities) off S1 and nothing off a parked run.

**Finding-linked scenarios (WP12): gates.** Each gate ties to one finding and
was FAIL on the engine before its fix and PASS after it (table below); every
fix-linked gate has a validity guard that makes "nothing measured" INVALID,
never PASS. Pass/fail rests on counts and ratios of two figures of one run.

- **s2-recovery-drain** (3.1, WP6): `gate.twoNodeDrain` drainB / drainA ≤
  0.65; `gate.noHoard` max (running − parked − workflow slots used) per node
  ≤ 2C, the engine's documented running-or-queued bound (operations.md,
  recovery pass row); `gate.freshStartsNotStarved` phase A probe p95 ≤ 0.25 ×
  drainA; and three statement gates — `gate.lockAcquisitionsPerAdoption`
  (successful lock acquisitions − probe starts + adoption CAS) / adoptions ≤
  2.02, `gate.lostLockRaces` (lock calls − lock rows) / N ≤ 0.25 (attempts lost
  to the peer between its heldKeys read and its lock call: inherent on two
  nodes without a claim protocol in the store; measured 0 on one node, 0.124
  and 0.185 on two after WP6), `gate.recoveryPassRate` (page + heldKeys reads)
  ≤ 1.25 × the designed reads (capacity passes at most once per second per
  node, one page + one heldKeys each, plus a full pass every recovery poll
  interval and one at each node start). The old single figure, (lockAcquire −
  probes + adoptCas + pages + heldKeys) / N, is `advisory.recoveryStatements`:
  on two nodes after WP6 it reads 2.41–2.45, above the one-node 2.17–2.22,
  because it counts the lost races and the second node's passes — expected,
  not a regression. Validity: template snapshot, exact seeding, a rolling
  start (node 2 starts 0–15 s after node 1 reads ready, and reads ready
  itself), every statement component matched (≥ 1 each, adoptCas = the nodes'
  adoption counters = N), gauge series for every node.
- **s4-signals** (3.2, WP2): `gate.noStrayPermits` — every node's
  `maestro_wake_permits` delta over the local, cross and timer phases is 0
  (per node: a sum over nodes could cancel); `gate.crossNodeStatementParity`
  — net engine statements per consumed signal, |cross − local| ≤ tolerance +
  5 % of local. Validity: the permit gauge present on every node at every
  read (absent is never 0), ≥ 2 nodes, and both phases set up as named (every
  run parked before signalling; local routed to the owner, cross to another
  node).
- **s5-pool-health** (3.5a, WP4): `gate.notDownWhenBusy` — while the pool is
  exhausted, 0 `DOWN` answers and 0 unanswered within 5 s from
  `/actuator/health/perf` (Maestro's component alone). Validity: ≥ 80 % of
  the exhausted window's samples show ≥ `hikariMaximumPoolSize` − 1 engine
  backends in `pg_sleep` and Hikari's pending reached ≥ 1
  (`validity.poolExhausted`); no Hikari timeout (busy, not failing); the
  sampler never stalled > 1 s (the database itself reachable). Each probe
  iteration also asks the root `/actuator/health`, which runs Maestro's
  indicator a second time and Boot's `db` indicator on the same pool, so the
  36 answers of a window are not independent draws (the same on both builds).
- **s6-retention** (3.11a/b, WP8): `gate.retentionPaced` — the sweep's busy
  fraction ≤ 0.65: the time-weighted share of the sweep's own span (first to
  last sampler sample with a purge statement active) during which one was;
  `gate.janitorServiceScoped` — every unconsumed signal of the other service
  kept; `gate.janitorBatched` — ≥ 2 janitor DELETE calls and rows per call ≤
  half the janitor's own targets (WP8's batch: 1,000). Validity: exact
  seeding; node 2's sweep observed and node 1's not; the statement texts
  matched (purge DELETEs a whole 4 per batch, the janitor ≥ 1 call when its
  targets are gone); the sampler and `pg_stat_statements`' purge execution
  time agree on the duty cycle within 0.15; the sampler's largest gap inside
  the span ≤ 0.3 s. The span is each sweep's own, so a paced sweep that spans
  longer than an unpaced one is judged on the same footing; the before's
  sweep spans only ~5.5 s (55 samples, ≈ 0.02 resolution) and the after's
  ~10.3 s (101): short, but both resolved, and the gate's 0.65 sits 0.20 from
  the before (0.85) and 0.18 from the after (0.47).

**Seeding fidelity limits.** S2 seeds by cloning one mid-run snapshot (one
step completed) of a real chain run, 5,000 times, by SQL: no instance-lock
rows (a crashed node's locks expired), one history shape, no signal or timer
rows, the clones never passed through `startWorkflow`. S6 clones one
released park run (events, its cancelled timer, a consumed signal) into eight
sets of ages, statuses and services by SQL; the sweeping node's own pool is
not under foreground load (the foreground runs on the other node).

**Measured characteristics, not defects.** S4's consume latency (a signal
row's `received_at` to its `SIGNAL_RECEIVED` event): a local delivery wakes
the parked run in memory; a cross-node delivery wakes it through the
post-commit `NOTIFY` (a virtual thread borrows a second pooled connection on
the receiving node, Postgres delivers to the owner's one `LISTEN`
connection, one listener thread dispatches in turn); both phases issue the
`NOTIFY` (4,500 `pg_notify` calls each). Measured after WP2
(r20261117t171420z): p50 1.0 ms local vs 3.4 ms cross at 100 signals/s
(+2.4 ms, the `NOTIFY` hop), p95 2.1 vs 5.0 ms; the pooled p95 is dominated
by rare stalls of tens of ms that land on either phase (13.2 local / 38.7
cross in r20261117t155519z, 39.4 local / 5.1 cross in r20261117t171420z, the
local tail all in its first level), so `advisory.consumeLatency` is advisory.
S5: with the pool exhausted the root `/actuator/health` (Boot's `db`
indicator on the same pool) went unanswered within 5 s 12 times in 36 after
WP4 while Maestro's component answered every time — Issue 75.

**Finding-linked before/after** (local profile, MacBook Pro 10 CPUs, Docker
7837 MiB; every result in `perf/.evidence/wp12/`; "as run" is the verdict
stored in the file, written by the evaluator of its harness — a stub for the
first runs of each scenario —, "now" the verdict of today's evaluator):

| Scenario | Role | runId | Engine | As run / now | Key figures |
|---|---|---|---|---|---|
| S2 | before | r20261117t115610z | `main` 5b170009 (no WP6) | ERROR (stub) / **FAIL** | B/A 0.9962; hoard 4,941; probe p95 / drainA 0.917; lost races 0 |
| S2 | preview | r20261117t122039z | `fix/recovery-capacity` 25e84962 | ERROR (stub) / **PASS** | 0.5165; 21; 0.010; lost 0.124 / run; pass rate 0.90 × designed |
| S2 | after | r20261117t162103z | `main` d1600bf1 | PASS / **PASS** | 0.5113; 21; 0.011; lost 0.185 / run; pass rate 0.86 × designed; adoptions 2,551 / 2,449 |
| S4 | before | r20261117t171940z | 0c710a1e (`834a9da8` + the gauge, MD-2) | FAIL / **FAIL** | permits local 0, cross **4,500** (2,250 per node), timer **100**; 24.9 / 24.9 statements per signal |
| S4 | after | r20261117t171420z | `main` 40aa3c21 | PASS / **PASS** | permits 0 / 0 / 0; 24.7 / 24.7 statements per signal |
| S5 | before | r20261117t155013z | bdcc074d | FAIL / **FAIL** | exhausted: 28 `DOWN` (store "timed out"), 8 `UP`; root 14 `DOWN`, 14 unanswered |
| S5 | after | r20261117t153355z | `main` bd583f06 | PASS / **PASS** | 0 `DOWN`, 32 `UNKNOWN`, 4 `UP`; root 24 `UP`, 12 unanswered (Issue 75) |
| S6 | before | r20261117t165803z | `main` d1600bf1 (no WP8) | FAIL / **FAIL** | busy 0.8525; janitor 1 call × 20,000 rows; foreign signals kept 0 / 10,000; `perf-foreign` rows purged 1,000 / 1,000 (Issue 76) |
| S6 | after | r20261117t170538z | `main` 40aa3c21 | PASS / **PASS** | busy 0.4692; 11 calls, 909 rows per call; all foreign signals and rows kept |

Superseded runs, kept as run: S5 r20261117t130237z was replaced by
r20261117t153355z (its probe counted a 503 as no answer); S4
r20261117t155519z / r20261117t160028z by the per-level re-takes; S6
r20261117t161125z / r20261117t163846z by re-takes on the sampler that
measures its span gap (they predate it, so they read INVALID today).

**`sample-perf` additions (harness settings, not Maestro ones):** the
management health group `perf` (`include: maestro`, `show-details: always`)
so S5 reads Maestro's component alone, the root endpoint unchanged; the
`hold` type (activities holding a pooled connection with `pg_sleep`: the
host's own database work, MD-6); the `signals` type (M distinct awaits, so
each delivery is a distinct key).

**An older engine with this harness (MD-1, the overlay):**

```bash
H=$(git rev-parse HEAD)                     # the harness commit
git worktree add --detach ../k6-old <engine-commit>
cd ../k6-old
git diff <engine-commit> "$H" -- settings.gradle.kts   # expect only the sample-perf include
git rm -r -q perf maestro-samples/sample-perf          # drop files H no longer has
git checkout "$H" -- perf maestro-samples/sample-perf settings.gradle.kts
git commit -m "perf harness overlay $H (local only, never pushed)"
git diff "$H" HEAD --stat -- perf maestro-samples/sample-perf   # must be empty
PERF_ENGINE_REF="<WPn> before: engine <sha>; harness $H" perf/run.sh <scenario>
```

`identity.repo.engineCommit` names the old engine and the run's label names
`H`; the tree-equality check proves the before and the after ran the same
harness. Remove the worktree once its `result.json` is copied. (The
cherry-pick of every `test(perf)` commit, used once for `cbf5a2a`, still
works but proves tree equality only indirectly.)

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
- `test` runs the unit and `@Tag("integration")` suites on every PR; `e2eTest`
  is excluded from `build`
  (`build-logic/src/main/kotlin/maestro.integration-test-conventions.gradle.kts:18-39`).
- The nightly/weekly schedule lives in `e2e-nightly.yml`. GitHub disables every
  scheduled workflow in a repository with no commits for 60 days
  (`disabled_inactivity`); `build-test.yml`'s `keep-nightly-enabled` job
  re-enables it on every push to `main`. `gh workflow list --all` (not plain
  `gh workflow list`) shows disabled workflows.
- Dispatched runs of `e2e-nightly.yml` (`gh workflow run e2e-nightly.yml --ref <branch>`):
  - Baseline 40593820947 (2026-10-27, `main` at 66cdf95): wholly green.
  - 40674015602 (2026-10-28, same SHA): red. The soak hit one I1 workflow, which was triaged as Issue 32 and fixed in `1da4f1a`.
  - 40762898010 (2026-10-28, branch `worktree-bugfix-cycle` at ec9286d): green. All four jobs succeeded (both loan E2Es, the PR-gate run three times, and the 2 h soak, which ran 2320 workflows with 0 violations). This run is the CI part of the whole-branch gate recorded in `docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/branch-verification.md`.
- Coverage gate: every subproject with `src/main/java` — libraries and samples alike — must carry ≥1 test class (`verifyModuleTestCoverage`, root `build.gradle.kts`); accepted debt is listed by name in `modulesWithoutTests` and printed on every run.

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
   the losing node stands down at its first collision instead of adopting the
   winner's result (C5, `EnginePostgresDualExecutionIT`).

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
    lifetime but changes across restarts or code edits still slips through.
    Replay drift (step name/type/fork arity) against a *stored* event log now
    stands down as `nondeterministic_history`; value-level nondeterminism with
    an unchanged step sequence is still only caught by `DeterminismChecker`.

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
    **Partly fixed 2026-11-11** — the engine now calls the paged, service-scoped
    `getRecoverableInstances(serviceName, after, limit)` (pages of 200, V101
    index) and skips instances held elsewhere with one `heldKeys` read per page
    (`RecoveryPassIsolationTest`, `PostgresWorkflowStoreTest$RecoveryScanTests`).
    Still no staleness filter: each node reads every active row of its service
    per poll.

22. **Lock renewal is serial** — one round-trip per held lock every TTL/3. Needs
    batching before a node holds thousands of parked workflows.
    **Fixed 2026-11-11** — one `renewAll` call per pass; Postgres renews in one
    statement per 500 locks (`WorkflowInstanceLockManagerTest`,
    `PostgresDistributedLockContractTest`).

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
admin dashboard UI tests. **No longer out of
scope, closed 2026-08-01:** chaos testing of >TTL pauses — the multi-instance
chaos harness's `PAUSE_RESUME` action freezes a real container past the 30s
instance-lock TTL on every run (mandated ≥2 loan-node occurrences per run);
split-brain is still accepted-by-design (fencing tokens have not landed —
`docs/open-issues.md` Issue 11), but its measured consequences are no longer
unmeasured — see `docs/operations.md` and Issue 11's evidence section.

Performance/load testing moved into scope 2026-11-12 — see §1 *Load suite*.
