# Milestone: Close the Test Gaps (multi-agent, P0–P6)

Binding plan: `docs/test-plan.md`. Coordinator prompt: `tasks/test-gap-closure-prompt.md`.
Contract for parallel builders: `maestro-integration-tests/SPEC.md`.
Branch: `test/integration-suite-p0-p6` (off `main` after PR #26 merged).

- [x] Scaffold — `maestro-integration-tests` module wired (`maestro.integration-test-conventions`: `test` = `@Tag("integration")`, separate `e2eTest` task for `@Tag("e2e")`); SPEC.md pins fixtures/naming/timing; fixtures = `PostgresIntegrationSupport`, `MaestroEngineHarness`, `WorkflowHandle`, `TestWorkflows`, `CountingActivities`; `EngineHarnessSmokeIT` proves the harness drives the real engine on real PG (3 tests green)
  - **BUG5 FOUND + FIXED (library):** `maestro-lock-postgres` and `maestro-messaging-postgres` both shipped Flyway `V100` into `classpath:db/migration` → *"Found more than one migration with version 100"*; the Postgres-only profile could never migrate. RED first (`MaestroMigrationsCoexistIT`, 2 tests), fixed by disjoint version bands (store 1–99, lock 100–199, messaging 200–299; messaging renumbered V100→V200). Safe to renumber: zero release tags, nothing published.
  - Audit note for P6: `EventType.WORKFLOW_STARTED` is never appended to the event log (start is published as `LifecycleEventType.WORKFLOW_STARTED` only) — dead enum constant.
- [ ] P0 — Engine × Postgres (lifecycle/memoization/recovery + signals/timers/saga + parallel branches + BUG1 pin)
- [ ] P1 — Kafka in CI (listener round-trip, signals channel, duplicate delivery, ack-on-failure contract)
- [ ] P2 — Multi-node (lock contention, owner death → adoption, cross-node signal wake, consumer-group)
- [ ] P3 — lock-postgres + messaging-postgres module suites (currently zero tests)
- [ ] P4 — Loan E2E promoted into CI (`@Tag("e2e")`, identity assertions kept, + two-instance scenario)
- [ ] P5 — Shutdown contract (RED-first; parked workflows must stay WAITING_*, not FAILED)
- [ ] P6 — Guardrails (determinism replay-diff, coverage gate, health-indicator + MaestroClient audits)

---

# Milestone: Loan-Origination Sample (multi-agent build)

Contract: `maestro-samples/sample-loan-origination/SPEC.md`.

- [x] SPEC.md written (topics, signal names, workflow IDs, idioms, test matrix, library-bug protocol)
- [x] Scaffold: 3 modules registered, compose (Postgres 5433 / Valkey 6380 / Kafka 29093, topics pre-created), root build green
- [x] Builder: loan-application-service (8/8 tests green; publishes VerificationRequested{loanId,type,amount} ✔ matches gateway; UnderwritingRequested{loanId,round,amount,income,propertyValue,verificationsApproved} — reconcile with underwriting builder; verdicts are plain Strings)
- [x] Builder: verification-gateway-service (4/4 tests green; VerificationRequest DTO = {loanId, type, amount:long})
- [x] LIBRARY BUG FIXED: DurableWorkflowBeanRegistrar (starter) scans auto-config packages / `maestro.workflow-packages` for bare @DurableWorkflow classes; idempotent with @Bean/@Component; RED test proved the bug first; 5 new starter tests green
- [x] Builder: underwriting-service (6/6 tests green; consumes UnderwritingRequest{loanId,round,amount,income,verificationsApproved} — loan-app also sends propertyValue (extra field, Jackson3 ignores; E2E to confirm); rejection reasons travel in `conditions`; workflow registered via explicit @Bean pending starter fix)
- [x] Verify wave 1: full `./gradlew build` green (all modules incl. 3 new samples + starter fix, 18 sample tests)
- [x] Verify wave 2: E2E — ALL 5 SCENARIOS PASS (happy path co-borrower-first 16s; orphan adoption 15s; conditions loop 15s; withdrawal+compensation 15s; kill-9 recovery 80s ≈ lock TTL + poll interval). BUT via flagged workarounds; 4 proven bugs:
  - BUG1 (blocker): engine pre-increments version, AbstractJdbcWorkflowStore expects current → first Postgres updateInstance always throws OptimisticLock; SagaManager.transitionToCompensating is the one non-incrementing call site
  - BUG2: maestro-store-postgres has NO auto-config → no WorkflowStore bean → no sample ever bootable
  - BUG3: existing 4 samples missing jdbc/flyway/kafka modular starters
  - BUG4: root docker-compose Kafka 0.0.0.0 listener rejected by apache/kafka:3.9.0
- [x] BUG1 FIXED (fixer agent wrote RED tests, died twice on API errors; coordinator completed GREEN): canonical convention = caller pre-increments, store CASes `stored == version - 1`; AbstractJdbcWorkflowStore SQL + resolveUpdateFailure fixed; SagaManager.transitionToCompensating now pre-increments; SPI javadoc rewritten; all convention tests green (core/test/jdbc/starter/store-postgres)
- [x] BUG2 FIXED: PostgresStoreAutoConfiguration (@AutoConfiguration(before = MaestroAutoConfiguration), ConditionalOnMissingBean(WorkflowStore), honors maestro.store.table-prefix; 7/7 TDD tests + 44/44 module suite). BUG3 FIXED: 4 existing samples got jdbc/flyway(/kafka) starters. BUG4 FIXED: root compose Kafka listeners.
- [x] Cleanup wave: 3 WorkflowStoreConfig workarounds + EngineVersionConventionAdapter DELETED; jars verified workaround-free (unzip grep)
- [x] Final E2E on fixed library: first attempt INVALIDATED (stale pre-fix JVMs from an interrupted run were still holding ports 8091-8093 and served the probes — caught via PID check); after killing stale JVMs + fresh jars + fresh infra: ALL 5 SCENARIOS PASS (happy 42s incl. boot / orphan 15s / conditions 15s / withdrawal-saga 14s / kill-9 recovery 80s), fresh PID confirmed, 0 OptimisticLockException, 0 workaround references, Flyway migrated, clean teardown
- [x] README finalized (architecture, actors, signal tables, idioms, E2E section)
- [x] Final `./gradlew build` green repo-wide

**Milestone complete.** Lesson captured: E2E readiness probes must verify process identity (PID/build fingerprint), not just HTTP 200 — stale services satisfy naive probes.

---

# Maestro Hardening — Close the Code↔Docs Gaps

Plan: `~/.claude/plans/tackle-the-gaps-found-atomic-milner.md` (approved 2026-07-28).
Previous milestone (Gradle multi-module setup) completed — see git history of this file.

## Phase 1 — Signal-consume CAS
- [x] RED: store tests for `markSignalConsumed` CAS semantics (Postgres + in-memory)
- [x] `WorkflowStore.markSignalConsumed` → boolean; JDBC + in-memory impls
- [x] `SignalManager.consumeSignal` handles false (WARN + proceed); test `consumeSignalProceedsWhenCasLoses`
- [x] Fix test-fake ripple in maestro-core tests; module tests green

## Phase 2 — Cross-instance wake (SignalNotifier.subscribe)
- [x] RED: SignalManagerTest with SubscribingNotifier (subscribe/unsubscribe lifecycle, remote wake, race re-check, ref-count, failure fallback)
- [x] Ref-counted subscribeForWake/unsubscribeForWake + onRemoteSignal in SignalManager
- [x] awaitSignal restructure: subscribe → re-check → park in try/finally

## Phase 3 — Wire subscribeSignals in starter
- [x] RED: SignalSubscriptionRunnerTest
- [x] New SignalSubscriptionRunner (ApplicationRunner, HIGHEST_PRECEDENCE+20) + bean in MaestroAutoConfiguration

## Phase 4 — Instance lock + recovery poller
- [x] `DistributedLock.renew` → boolean (Valkey, Postgres, in-memory, test stubs)
- [x] RED+impl: WorkflowInstanceLockManager (30s TTL, 10s renew, ACQUIRED/HELD_ELSEWHERE/NO_BACKEND)
- [x] Wire into WorkflowExecutor (launchWorkflow acquire, finally release, resumeWorkflow boolean)
- [x] RecoveryPoller + startRecoveryPoller; starter RecoveryProperties + StartupRecoveryRunner
- [x] InMemoryDistributedLock TTL expiry honor
- [x] All module tests green (full build)

## Phase 5 — Docs
- [x] maestro-architecture.md corrections (topics, dedup key, timer TTL, branch keys, instance lock semantics)
- [x] cross-service.md / self-recovery.md / concepts.md wake-behavior + branch-key corrections
- [x] configuration.md recovery properties; DistributedLock javadoc; CLAUDE.md key table

## Code review — findings fixed
- [x] Notifier subscribe/unsubscribe I/O moved outside ConcurrentHashMap.compute (no bin-lock stalls)
- [x] Instance lock acquired BEFORE createInstance — closes the recovery-poller "steal a just-created workflow" race; released on createInstance failure
- [x] Parked awaitSignal re-checks the store every 30s (`SignalManager.DEFAULT_WAKE_RECHECK_INTERVAL`) — bounds cross-node signal latency when no SignalNotifier is configured (Kafka/RabbitMQ without Valkey) and closes the missed-notification window
- [x] SignalSubscriptionRunner rethrows delivery failures (transport must not ack an unpersisted signal); `$maestro:*` commands dropped with WARN instead of persisted as junk rows
- [x] `maestro.lock.key-prefix` / `maestro.lock.ttl` wired through to the instance lock (renew = ttl/3)
- [x] Guarded post-subscribe re-check on `signalNotifier != null`; dead test fields removed; thread-safety + ctor Javadoc on SignalSubscriptionRunner

## Verification
- [x] `./gradlew build` green (all modules, includes Testcontainers suites for store-postgres and lock-valkey)
- [x] All phases done test-first (RED verified before each implementation)

## Review — deferred follow-ups (deliberate, from code review)
- **Fencing/lost-lock abort:** a lock lost mid-run (>30s GC pause) logs ERROR but does not abort the local workflow; DB constraints dedup persists, not side effects. Needs fencing-token validation in the store (SPI change).
- **Recovery query scale:** `getRecoverableInstances()` has no service/staleness filter — every node re-reads the full active set every 60s and probes the lock for each foreign-owned instance. Add `service_name` (or `updated_at < now()-TTL`) filter + index (SPI change).
- **Batch lock renewal:** renewer renews serially, one round-trip per held lock every 10s; batch (SQL IN / Valkey pipeline) before nodes hold thousands of parked workflows.
- **Lifetime-scoped wake subscription:** sequential awaits churn subscribe/unsubscribe per await; scope the subscription to the workflow's local lifetime instead.
- **Admin `$maestro:retry`/`$maestro:terminate`:** now explicitly dropped with WARN — admin dashboard buttons are non-functional end-to-end until an engine-side command dispatcher exists.
- **Test-double consolidation:** 5 hand-rolled WorkflowStore fakes + 3 lock fakes across core tests; SPI changes ripple through all copies. Consider a shared core-test fixture (or opening maestro-test's fakes).
- **lock-postgres has no test suite** (renew boolean covered via InMemory + Valkey suites only).
- **Pre-existing bug (own ticket):** `shutdown()` cancels parked futures → parked workflows are marked FAILED (with compensation!) on graceful shutdown instead of staying recoverable.
- **ActivityInvocationHandler** still hardcodes `maestro:lock:activity:` prefix (pre-existing; same wiring as instance lock now supports).
