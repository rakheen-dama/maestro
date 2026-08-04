# Milestone: Demo — runbook, observability stack, live versioning story

Spec: `docs/superpowers/specs/2026-08-03-maestro-demo-design.md`. Plan: `docs/superpowers/plans/2026-08-04-maestro-demo.md`. Domain: `demo/DOMAIN-BRIEF.md`. Branch: `worktree-demo` off main @ 945ccb4. 95+ commits, 107 files.

- [x] Task 1 — actuator + Prometheus + OTLP on the three loan services. Caught a Boot 3.x property name that would have shipped the trace exporter silently inert
- [x] Task 2 — 7-container stack (Prometheus/Grafana/Jaeger) + host-JVM scripts. Trace `d055f596…` proven across 3 services. **Found library Issue 23** (Critical): `maestroKafkaTemplate` suppresses Boot's, inerting `spring.kafka.*` for every user
- [x] Task 3 — v2 source set: `parallel()` behind `workflow.version()`. In-flight loan keeps its path across a live deploy, proven with both trace shapes. Caught that the plan's `(changeId, 1, 2)` bounds would have **failed every in-flight loan on stage**
- [x] Task 4 — 751-line rehearsed runbook + scripts. Rehearsal found the `kill -9` scenario couldn't recover as written; headline timing fixed at its cause, 250 s → ~62 s
- [x] Task 5 — 20-slide self-contained deck, offline-verified. Review caught an invented event-log table and clipping in presenter mode (14/20 at 720p)
- [x] Task 6 — cold-machine QA gate: FAIL then PASS. Found the first preflight on a clean machine always failed (consumer-group race), fixed at the cause
- [x] Final whole-branch review: SHIP after a documentation-only fix wave (0 Critical); all 18 findings closed and re-verified

**Peak 2.42 GiB across 7 containers + 4 host JVMs. Constraints held throughout: `maestro-core`, the loan `e2e/`, and the chaos harness are zero-diff.**

Product issues raised, not patched: Issue 23 (Kafka template hijack), Issue 24 (redelivery/DLT topic gap).

# Milestone: Release Hardening (RabbitMQ removal, Observability, Versioning)

Spec: `docs/release-hardening-spec.md`. Plan: `tasks/release-hardening-plan.md`. Branch: `worktree-release-hardening` off main @ 7bfedbe. 68 commits, 238 files.

- [x] Task 1 — RabbitMQ removal (2 modules deleted, five-class reference invariant, release note with rationale)
- [x] Task 2 — Architect design doc (8 sections, decisions not options); 10 coordinator rulings issued across the cycle
- [x] Task 3 — Core `EngineObserver` SPI, Spring-free, replay-aware; RULING 4 made exception containment structural
- [x] Task 4 — Micrometer meters; review caught the auto-config shipping **inert in every real app**
- [x] Task 5 — OTel tracing + Kafka W3C propagation + durable restoration; review caught a **signal-discard** path (4 fix rounds, 65 tests)
- [x] Task 5b — **Release-blocking engine defect found mid-cycle**: two `parallel()` branches parking concurrently → FAILED + saga compensation → durable damage. Fixed with a bounded retry, mutation-proven
- [x] Task 6 — `workflow.version()` memoized change-branching (17 pins, RED against two distinct broken stubs)
- [x] Task 7 — Unknown-event stand-down + sealed `MaestroControlFlowError`; RULING 9 widened the payload guard to every path deserializing history it did not write
- [x] Task 8 — Docs: new `observability.md`, operations playbook, release notes, Issues 21 (fixed) + 22 (open)
- [x] Task 9 — QA gate PASS: 899 tests, all 9 DoD items, full build + integration + chaos PR-gate green
- [x] Final whole-branch review: APPROVED, 0 Critical, documentation-only fix wave (V4 migration upgrade note + Issue 22 caveats at 8 sites)

**Deferred with rulings:** Issue 22 (compensations can continue on an operator-terminated workflow) — code fix deferred, documented at 8 sites; the final review upheld the deferral because a rushed fix there would silently skip compensations that *should* run.

# Milestone: Multi-Instance Verification (Issues 11/12 evidence)

Binding spec: `docs/multi-instance-test-plan.md`. Plan: `tasks/multi-instance-plan.md`. Branch: `worktree-multi-instance-verification` off main @ 883197f (70+ commits). SDD ledger: `.superpowers/sdd/progress.md` (worktree).

- [x] Phase 1 (Tasks 1-5, 10): E2E scenarios 7-10 + postgres-lock parity + cross-node timer wake fix (Issue 17); all 3x green both lock backends
- [x] Task 6: chaos-harness design doc + coordinator rulings Q1-Q10
- [x] Task 7: chaos harness (6-node Testcontainers cluster, seeded schedule, invariant checker I1-I5, Issue 11/12 pipeline); FOUR engine defects found+fixed RED-first: Issue 17 (timer wake), 18 (DuplicateEventException stand-down), 19 (SIGNAL_TIMEOUT memoization), 20 (advisory park-probes); PR-gate 3x green x2 waves; driver-fix wave (interrupt-safe pacer, runaway cap, PR-gate/soak selection fix d4720ca) after 3 soak attempts traced to ONE root cause (PR-gate @Timeout(25m) vs soak durationMinutes collision, interrupt swallowed into runaway)
- [x] Soak-of-record: 2h chaos window VERDICT PASS — 2376 workflows, 0 invariant violations, 0 duplicate side effects, checker 245/1/1, benchmark tail captured (run 20260801-214325--6973268155056049009)
- [x] Task 8: docs (operations.md new; Issue 11/12 evidence with caveats; Issue 20 enumerations; release notes incl. mixed-version upgrade note)
- [x] Task 9 QA gate: PASS (full build + e2eTest 3x at final HEAD, identity-verified)
- [x] Final whole-branch review (fable): Approved with one fix wave (FB-I1 exceptionType-anchored deleteFailureEvents + CI guards/timeout + docs truth); fix-wave re-review Approved; 26+ parked minors ruled record-and-merge
- [x] Final verification: core x3 + full build green at 0bbf1e6 (one terminate-test timing flake under load, 4x green after)

**Branch ready to integrate — awaiting integration choice.**

# Milestone: Close the Test Gaps (multi-agent, P0–P6)

Binding plan: `docs/test-plan.md`. Coordinator prompt: `tasks/test-gap-closure-prompt.md`.
Contract for parallel builders: `maestro-integration-tests/SPEC.md`.
Branch: `test/integration-suite-p0-p6` (off `main` after PR #26 merged).

- [x] Scaffold — `maestro-integration-tests` module wired (`maestro.integration-test-conventions`: `test` = `@Tag("integration")`, separate `e2eTest` task for `@Tag("e2e")`); SPEC.md pins fixtures/naming/timing; fixtures = `PostgresIntegrationSupport`, `MaestroEngineHarness`, `WorkflowHandle`, `TestWorkflows`, `CountingActivities`; `EngineHarnessSmokeIT` proves the harness drives the real engine on real PG (3 tests green)
  - **BUG5 FOUND + FIXED (library):** `maestro-lock-postgres` and `maestro-messaging-postgres` both shipped Flyway `V100` into `classpath:db/migration` → *"Found more than one migration with version 100"*; the Postgres-only profile could never migrate. RED first (`MaestroMigrationsCoexistIT`, 2 tests), fixed by disjoint version bands (store 1–99, lock 100–199, messaging 200–299; messaging renumbered V100→V200). Safe to renumber: zero release tags, nothing published.
  - Audit note for P6: `EventType.WORKFLOW_STARTED` is never appended to the event log (start is published as `LifecycleEventType.WORKFLOW_STARTED` only) — dead enum constant.
- [x] P0 — Engine × Postgres: **32 tests** across 8 suites, 3× flake-clean (`--rerun-tasks`)
  - `EnginePostgresLifecycleIT` (5), `EnginePostgresMemoizationIT` (3), `EnginePostgresRecoveryIT` (4), `EnginePostgresParallelIT` (3), `EnginePostgresOptimisticLockIT` (5, BUG1 pin), `EnginePostgresSignalIT` (6), `EnginePostgresTimerIT` (4), `EnginePostgresSagaIT` (2)
  - No engine defects found. Both builder agents died on transient API errors before reporting, so an independent rigor audit re-verified the suites against engine source: assertions trace to real formulas (branch partitioning `p*1000+(i+1)*1000`, store CAS `version-1`), crash sims genuinely use a second executor, orphan-adoption is distinct from pre-arrival, LIFO order asserted twice (execution + persisted sequence)
  - Audit gap closed by coordinator: saga version-march was unasserted (`SagaManager.transitionToCompensating` is the BUG1 call site and swallows `OptimisticLockException`). Added version pins (2 for compensated, 1 for clean), RED-proved (`expected: <3> but was: <2>`)
  - **Scaffold fixture bug found + fixed:** `@Container` on a static field in an abstract base is stopped by JUnit per test *class* → suites 2+ ran against a fresh unmigrated DB (`relation "maestro_workflow_signal" does not exist`, 29/35 red). Switched to JVM-wide singleton container
- [x] P1 — Kafka in CI: **12 tests** (10 green, 2 `@Disabled` as executable spec) — listener round-trip, the `maestro.signals.{service}` channel fed for the first time, duplicate delivery, lifecycle events, ack-on-failure contract
  - **BUG8 FOUND + FIXED (library):** every nested `@ConfigurationProperties` record declared a no-arg ctor → Boot skipped value-object binding → `maestro.messaging.topics.*`, `lock.*`, `timer.*`, `recovery.*`, `retry.*`, `store.table-prefix`, `worker.task-queues` were **inert in every deployment**. RED first (`MaestroPropertiesBindingTest`)
- [x] P2 — Multi-node: **12 tests** — lock contention, owner death → TTL → adoption, cross-node signal routing, no-lock-backend characterization (duplicate execution is real; activities must be idempotent there)
  - **BUG9 FOUND + FIXED (library):** `PostgresNotificationListener.listen()` only queued the LISTEN → cross-instance wake silently lossy. My earlier "production is not exposed" claim was wrong; the workaround it justified was removed
  - **BUG7 FOUND + FIXED (library):** version conflict on finalise recorded a *successful* workflow as FAILED (+ saga compensation after success)
- [x] P3 — Backend modules: **37 tests** — `PostgresDistributedLockContractTest` (24) + messaging (13). Note: lock-postgres was **not** testless (4 pre-existing unit tests); the plan's premise was wrong. messaging-postgres genuinely had zero
- [x] P4 — Loan E2E: nightly CI workflow (`e2e-nightly.yml`, schedule + manual dispatch, logs+pids uploaded); scenario 6 (two-instance loan-application, driven entirely through node B) added. **6/6 PASS** on a clean run (ports verified free, containers down first)
  - Kept as a script rather than a JUnit rewrite: it already does real `kill -9`, restart and PID identity checks; reimplementing risked losing exactly the assertions `tasks/lessons.md` exists for
  - First run FAILED 6/6 — surfaced that `maestro.admin.events` was never pre-created, so the producer blocked 60s (`max.block.ms`) inside `startWorkflow`. The sample sets `maestro.admin.events.enabled: false` to avoid this, but that property is **read by nothing**. Topic now pre-created; the inert property is logged as a library follow-up
- [x] P5 — Shutdown contract: **13 tests** (7 unit + 6 integration), RED-first
  - **BUG6 FOUND + FIXED (library):** shutdown marked parked workflows FAILED *and compensated them*. Typed `ExecutorShutdownException`; parked workflows stay `WAITING_*` and recoverable
- [x] P6 — Guardrails:
  - `MaestroClient` — 8 tests through the real auto-config chain (first dedicated test class); mutation-verified
  - Health-indicator audit answered: **not implemented at all** — no `io.b2mash.maestro.spring.health` package exists though `CLAUDE.md` documents `MaestroHealthIndicator`. Docs/code gap, not a test gap; not built (new feature, out of scope)
  - Module test-coverage gate wired into `check`: fails on any `maestro-*` module with production code and zero tests, with a documented allowlist for the four known-untested modules (admin, admin-client, messaging-rabbitmq, store-jdbc). Proven to bite by removing an entry
  - `DeterminismChecker` in `maestro-test` — runs a workflow N times and diffs the decision sequences; 3 tests prove it passes a deterministic workflow and catches a branching one, naming the divergence point

## Final verification
- [x] `./gradlew build` green repo-wide (includes the 65-test integration module + coverage gate)
- [x] Loan E2E 6/6 green on a clean run

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

> **Superseded.** The authoritative, ranked list of remaining gaps and risks now
> lives in `docs/test-plan.md` §5 ("Known and still open"), which folds these in
> alongside everything the P0–P6 work surfaced. Two entries below are resolved:
> **lock-postgres now has a test suite** (24 Testcontainers tests), and the
> **shutdown bug is fixed** (`ExecutorShutdownException`; parked workflows stay
> `WAITING_*`). The rest remain open and are restated there with severity and
> evidence.
- **Fencing/lost-lock abort:** a lock lost mid-run (>30s GC pause) logs ERROR but does not abort the local workflow; DB constraints dedup persists, not side effects. Needs fencing-token validation in the store (SPI change).
- **Recovery query scale:** `getRecoverableInstances()` has no service/staleness filter — every node re-reads the full active set every 60s and probes the lock for each foreign-owned instance. Add `service_name` (or `updated_at < now()-TTL`) filter + index (SPI change).
- **Batch lock renewal:** renewer renews serially, one round-trip per held lock every 10s; batch (SQL IN / Valkey pipeline) before nodes hold thousands of parked workflows.
- **Lifetime-scoped wake subscription:** sequential awaits churn subscribe/unsubscribe per await; scope the subscription to the workflow's local lifetime instead.
- **Admin `$maestro:retry`/`$maestro:terminate`:** now explicitly dropped with WARN — admin dashboard buttons are non-functional end-to-end until an engine-side command dispatcher exists.
- **Test-double consolidation:** 5 hand-rolled WorkflowStore fakes + 3 lock fakes across core tests; SPI changes ripple through all copies. Consider a shared core-test fixture (or opening maestro-test's fakes).
- **lock-postgres has no test suite** (renew boolean covered via InMemory + Valkey suites only).
- **Pre-existing bug (own ticket):** `shutdown()` cancels parked futures → parked workflows are marked FAILED (with compensation!) on graceful shutdown instead of staying recoverable.
- **ActivityInvocationHandler** still hardcodes `maestro:lock:activity:` prefix (pre-existing; same wiring as instance lock now supports).

# Milestone: Release Readiness (open-issues.md → fixed)

Binding plan: `tasks/release-readiness-plan.md` (SDD ledger in `.superpowers/sdd/release-readiness-plan/progress.md`).
Branch: `worktree-release-readiness` off `main` @ 0502b38.

- [x] Task 1 — Issue 2: timer fire crash window — CONFIRMED + fixed (self-healing replay, findTimer SPI, V3 index)
- [x] Task 2 — Issues 3+6: lifecycle publish latency + admin.events wiring — async bounded publisher, enabled flag wired, topic alias
- [x] Task 3 — Issues 4+5: ExecutorShutdownException → Error; SagaManager rethrow — catch ordering, ParkingLot/RetryExecutor/ActivityInvocationHandler/DefaultWorkflowOperations audit fixes, CLAUDE.md documented
- [x] Task 4 — Issues 7+9: config seams — all three wired, defaults unchanged
- [x] Task 5 — Issue 8: MaestroHealthIndicator — auto-configured on Actuator classpath, UP/DOWN + poller/running-count details
- [x] Task 6 — Issue 1: signal no-loss (Kafka + Postgres) — redelivery + DLT/DEAD_LETTER + replay API, both specs enabled
- [x] Task 7 — Issue 10a: RabbitMQ first suite (3x green) + Issue 1 parity; off the allowlist
- [x] Task 8 — Issue 10b: admin-client / admin / store-jdbc suites — all off the allowlist, gate empty
- [x] Task 9 — Docs truth pass + release notes; issues 13-15 recorded; all spot-checks passed
- [x] Task 10 — QA: all gates passed (found+drove fix for enabled-flag event leak)

## Review — Release Readiness milestone (2026-07-29)

All 10 tasks complete; issues 1–10 closed, 11/12 documented as known limitations, new issues 13–15 recorded.
- 42+ commits on `worktree-release-readiness` (base `main` @ 0502b38). Final whole-branch review (after 10 per-task reviews): ready to merge; its one Important finding (docs misattribution) fixed in `14a5fba` and re-review clean.
- Defects found and fixed BEYOND the original issue list: admin missing kafka+flyway starters (boot-breaking), admin-client silently dropping async send failures, `enabled=false` leaking ACTIVITY_*/SIGNAL_*/TIMER_*/COMPENSATION_* events (caught by QA gate 5 live E2E, fixed via GatedWorkflowMessaging).
- Verification: full `./gradlew build` green post-fix; integration suite 69/69 across 3 `--rerun-tasks` runs; loan E2E 6/6 with process-identity proof; admin ingestion verified over HTTP.
- Breaking changes (all in docs/release-notes.md): WorkflowStore.findTimer, ExecutorShutdownException→Error, KafkaMessagingConfig fields, @MaestroSignalListener KafkaTemplate requirement. Operators must pre-create .DLT topics before upgrading.

# Milestone: Issues 13–15 + QA cycle

Binding plan: `tasks/issues-13-15-plan.md`. Branch: `worktree-issues-13-15` off `main` @ PR #28 merge.

- [x] Task 1 — Issue 14: SagaManager replay-skip guard (both loops, LIFO-order gap closed)
- [x] Task 2 — Issue 13: timer cancel → TimerCancelledException, memoized + 3-way heal
- [x] Task 3 — Issue 15: $maestro:retry/terminate live end-to-end (dispatcher, retryWorkflow + deleteFailureEvents ruling, terminateWorkflow, resurrection guards, Kafka E2E)
- [x] Task 4 — Docs close-out: 13-15 Resolved callouts, 0.4.0 release notes, test-plan reconciled
- [x] Task 5 — QA cycle: all gates pass (incl. live dashboard retry/terminate); stale-artifact contamination caught and re-verified

---

# Task 7 — Chaos/Soak Harness (multi-instance verification cycle)

Implementing `.superpowers/sdd/multi-instance/chaos-harness-design.md` exactly (per §13 rulings).

- [x] 1. FIRST COMMIT: amend SPEC.md — add e2e/chaos/ to pinned layout (Q1)
- [ ] 2. Gradle wiring: e2eTest.dependsOn 3 sample bootJar tasks + jar-path sysprops (Q2 a)
- [ ] 3. EvidenceWriter — identity headers, run directory (§9)
- [ ] 4. ChaosCluster — Testcontainers infra + 6 nodes, topics, log streaming, endpoint registry (§2)
- [ ] 5. WorkloadDriver — path scripts, ledger (§3)
- [ ] 6. ChaosController — actions, safety rules, action log, heal-all (§4)
- [ ] 7. InvariantChecker — SQL I1-I6 + ledger join + dumps (§5)
- [ ] 8. MetricsSampler — pg_stat_statements + Valkey INFO -> metrics.csv (§6)
- [ ] 9. Side-effect census — log counters + correlation verdict (§7)
- [ ] 10. ChaosPrGateE2EIT + ChaosSoakE2EIT (§8)
- [ ] 11. Golden-run calibration; refine I3(d); record in design Changelog (Q6)
- [ ] 12. PR-gate green 3×; ./gradlew build green
- [ ] 13. CI: chaos-pr-gate (nightly 3x) + chaos-soak (weekly+dispatch) (Q5)
- [ ] 14. Evidence mirror + index; report

Rulings: Valkey-lock only (Q3), safety as designed (Q4), I4 hard-fail (Q7),
unexplained dups flag-and-report (Q8), constants as approved (Q9).
Phase-1 facts: awaitSignal(timeout) leaves NO timer rows (I2 -> verify wf only);
FUNDED loan misses seq {9,16}; FAILED saga has compensation events ~seq 19000.

## STATUS: BLOCKED (2026-07-31) — engine defect found on first live chaos run
- Golden calibration GREEN (all 4 paths). Harness complete (16 classes).
- DEFECT: DuplicateEventException (Issue 11 no-fencing adoption race) recorded as
  WORKFLOW_FAILED -> succeeding workflow stored FAILED. WorkflowExecutor.java:1353
  catch(Exception) -> handleWorkflowFailure. Sibling of fixed BUG7. Deterministic
  (mandated loan-node PAUSE_RESUME triggers it). PR-gate cannot be green until fixed.
- maestro-core untouched pending coordinator ruling (dispatch: BLOCKED first).
- Full analysis + question -> .superpowers/sdd/task-7-report.md §3/§7.
- Secondary (mine, not blocker): I4 verification-webhook over-delivery + PT30S uw
  timeout — driver-shaping fix queued for after unblock (Q7).
- Remaining after unblock: driver-shaping fix, PR-gate green 3x, CI workflow commit.

## Ruling 3 execution (2026-08-01)
- [x] I4 consumedTwin split (hard-fail only twin=false; twin=true = mandatory finding) — §14.4
- [x] Benchmark tail implemented as soak tail + chaosActive=false in tail — §14.5
- [x] I3(d) FAILED-path bounds -> 2 (decision-await race) — §14.6
- [x] Webhook fallback removed (TOCTOU near-duplicate was the real I4 twin=false source)
- [x] Compressed soak smoke PASS (seed 204): verdict PASS, finding surfaced, tail ran 6->3
- [ ] PR-gate 3x green (fresh seeds) — run 1 in flight
- [ ] Evidence mirror + index; report finalization
- [x] Ruling 3 items complete; soak smoke PASS (204); streak A PASS (16m33s)
- [!] BLOCKED again: streak run B found NEW engine defect (proposed Issue 19) —
  divergent replay of timed-out-gate gap after graceful rolling restart; rate
  lock leaked, WORKFLOW_FAILED append collided (I3b). Full analysis report §6a.
- [x] Ruling 4: Issue 19 fixed RED-first (SIGNAL_TIMEOUT memoization + retry
  failing-memo rule); 4 pins green; full build green
- [x] Ripples swept: run-e2e.sh empty missing-sets (scenario 7 re-run PASS,
  probe evidence), golden re-calibration gaps=[] all paths, I3(d) bounds 0,
  projector/DeterminismChecker/in-memory store verified no-op; Issue 19 docs
  + release note
- [x] PR-gate 3x GREEN consecutively (seeds 3430218812008443518,
  -200961534721746905, 886868793817033505)

## Task 8 — Findings and evidence docs (2026-08-01)
- [x] docs/open-issues.md §Issue 11: measured 0/211 duplicate-side-effect
  evidence from the 3 PR-gate streak runs + the Issue 18 "loser loses fast"
  explanation + honest caveats; PENDING-SOAK placeholder for the longer soak
  window
- [x] docs/open-issues.md §Issue 12: calm-window benchmark methodology +
  real PR-gate metrics.csv sample rows; PENDING-SOAK vs-node-count
  benchmark-of-record skeleton table for the soak-mode benchmark tail
- [x] New docs/operations.md: measured multi-instance deployment bounds
  (owner-kill adoption, rolling-restart safety, timer-leader failover,
  cross-node admin commands, cross-node wake, split-brain, lock-backend
  matrix, chaos harness how-to-run), cross-linked from
  docs/maestro-architecture.md §14 and docs/open-issues.md
- [x] docs/open-issues.md §3: removed the stale "e2eTest matches nothing"
  note — documents the chaos/soak harness now tagged @Tag("e2e"); §6
  "what's left" now names Issues 18/19, not just 17
- [x] docs/release-notes.md: explicit third-party WorkflowStore.
  deleteFailureEvents contract-change callout for Issue 19 (exceptionType-
  gated timeout-memo delete) so custom-store maintainers don't ship a
  silent infinite retry loop
- [x] Sample docs: Flyway inert-postgres-lock-tables note (Task 5
  coordinator note), E2E_LOCK_BACKEND mention, scenario count 5->10 fixed
  in README.md, SPEC.md, and run-e2e.sh's header comment
- [x] Stale-claims sweep: docs/test-plan.md's E2E legend/matrix (manual->CI,
  5->10 scenarios, "never tested"->closed with pointer to
  docs/operations.md), its out-of-scope list (>TTL chaos no longer
  out-of-scope), docs/multi-instance-test-plan.md completion banner
- [x] PENDING-SOAK placeholders filled (2026-08-02): Issue 11 soak data
  point (2,376 wf, 0 dups, 476 compensations = SAGA_WITHDRAWAL count,
  76s drain) and Issue 12 vs-node-count benchmark of record (tail6/tail3
  calm averages from `metrics.csv`), from soak run
  `20260801-214325--6973268155056049009` — provenance caveats (PR-gate
  `@Timeout` collision, leaked-checker noise, b2b5c65-vs-7113e06 stamp)
  stated in Issue 11; operations.md §6/§8/§9 updated to match, incl. the
  `d4720ca` suite-selection fix note
- Report: `.superpowers/sdd/task-8-report.md`
- [x] Evidence mirrored + INDEX updated

## Task 7 addendum — soak-failure fix validation (2026-08-01)
- [x] Diagnosed 2h-soak OOM (test-JVM 512m heap, 27.5 min in): unbounded
  log accumulation in the harness (full-log re-reads per SAGA poll,
  per-frame file open/close backpressure, whole-log census String);
  fixes committed as 7ed16d8 (heap telemetry, checker-blindness metric,
  backend heal) + da9142d (LogTailScanner, persistent log writers,
  streaming census, capped excerpts, e2eTest maxHeapSize=2g)
- [x] AFTER validation: 8-min compressed soak, seed 558112, clean host —
  TWO back-to-back SOAK runs in one JVM, both VERDICT PASS (168 wf each,
  0 dups, 0 missing comp); heap bounded: peaks 58MB then 234MB vs 2g cap
  (plateau, no growth); checker-blind 0/22 cycles; benchmark tail ran;
  sampler cadence 15.2s max gap (BEFORE: 1048s gaps, 18m27s heals)
- [x] Host-contention control (seed 910203) kept in evidence: post-fix run
  beside a stray gradle worker FAILED I1 on sample timeouts with bounded
  heap — chaos calibrations assume an uncontended Docker host; verify 0
  workers/containers before runs
- [x] PR-gate post-fix regression (seed 661901): VERDICT FAIL on I3d only —
  diagnosed as a NEW engine finding (proposed Issue 20): a 39s node
  partition exceeding HikariCP's 30s connectionTimeout made
  standDownIfTerminated's getInstance throw UncheckedSqlException inside
  awaitSignal's wake-recheck; the executor's generic catch(Exception)
  durably FAILED two healthy parked workflows (gap = burned await slot).
  Issue 18 family (infra outage recorded as workflow failure). NOT a
  calibration issue — BLOCKED on coordinator ruling per the library-bug
  protocol; evidence 20260801-093053-661901 + task-7-report.md §10.4
- Report: `.superpowers/sdd/task-7-report.md` §10; evidence
  `.superpowers/sdd/multi-instance/evidence/task7/` (soak-console.log,
  *-777-BEFORE, *-910203, *-558112, prgate-postfix.log)
