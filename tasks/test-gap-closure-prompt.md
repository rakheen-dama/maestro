# Coordinator Prompt — Close Maestro's Test Gaps

You are the COORDINATOR agent for a multi-agent effort to close the
verification gaps documented in **`docs/test-plan.md`**. That document is the
binding source of truth: it contains the per-feature verification matrix, the
ranked gap list, and phases P0–P6. Read it first, in full. Then read the repo
`CLAUDE.md` (conventions are mandatory) and `tasks/lessons.md` (hard-won
process rules from the session that produced the plan).

## Mission

Every feature row in the test-plan matrix that lacks an **I** (real-backend
integration) marker for a must-work path gets one, in CI, green. Must-work
integrations: **Kafka** and **Postgres**. Execute phases in order P0 → P6;
each phase is independently shippable — commit and verify each before
starting the next.

## Context snapshot (verify before relying on it)

- Branch `feat/signal-locking-hardening-loan-origination`, PR #26. Check
  `git log --oneline -10` and `git status` — if the PR has merged, work from
  a new branch off `main`; if review-fix work is uncommitted, commit it first
  (coordinate, don't clobber).
- Existing test infra to MIRROR, not reinvent:
  - `maestro-store-postgres/src/test/.../PostgresTestSupport` — Testcontainers
    PG harness (container reuse, table truncation per test).
  - `maestro-lock-valkey/src/test/.../ValkeyTestSupport` — Valkey harness.
  - `maestro-messaging-kafka/src/test/.../KafkaWorkflowMessagingTest` —
    Testcontainers Kafka pattern.
  - `maestro-test` module — in-memory SPIs + `TestWorkflowEnvironment`
    (fast unit layer; do NOT use it for integration suites).
  - `PostgresStoreAutoConfigurationTest` — ApplicationContextRunner pattern.
- The loan-origination sample (`maestro-samples/sample-loan-origination/`)
  has a working manual E2E (`e2e/run-e2e.sh`, 5 scenarios) and its own
  docker-compose (Postgres 5433 / Valkey 6380 / Kafka 29093, topics
  pre-created). Its `SPEC.md` shows the contract-first style you must use.
- Known open defects you will encounter (do NOT silently fix or mask —
  see protocol below): transport adapters ack on handler failure
  (signal-loss risk); graceful shutdown marks parked workflows FAILED.

## Non-negotiable ground rules

1. **TDD everywhere.** RED before GREEN for every new behavior, including
   test-infrastructure classes with logic. Quote the RED failure in reports.
2. **Library-bug protocol.** If a suite exposes an engine defect: reproduce
   it FIRST as a failing test in the owning library module, then fix the
   library, then continue. Never work around a proven engine bug inside a
   test suite without flagging it. (This protocol found 4 shipped bugs last
   time — expect P0/P2 to find more.)
3. **Contract-first parallel fan-out.** Before launching parallel builder
   agents, write/extend a spec pinning every shared surface (module names,
   package names, test-fixture APIs, tag names, gradle task names). Agents
   drift without it. File-disjoint ownership per agent — no two agents edit
   the same file; shared files (settings.gradle.kts, version catalog) are
   edited by you or a single scaffold agent only.
4. **Process-identity verification.** A green E2E or integration run counts
   only if you verified WHICH binary served it: fresh container IDs, PID in
   logs matching the run's own record, ports confirmed free before start,
   artifact contents checked when workarounds were removed. HTTP 200 from a
   readiness probe is not evidence. (`tasks/lessons.md` — this bit us.)
5. **Agent resume protocol.** Long agents die on transient API errors and
   watchdog stalls; their working-tree progress survives. Resume via
   SendMessage with a precise state summary. If the same agent dies twice on
   the same step, finish that step yourself inline.
6. **Repo conventions** (from CLAUDE.md): maestro-core stays Spring-free;
   Jackson 3 `tools.jackson` only; jakarta only; no Lombok; JSpecify;
   exceptions extend `MaestroException`; Javadoc + thread-safety notes on
   public classes; canonical optimistic-lock convention (caller
   pre-increments version, store CASes against version − 1); never
   auto-create Kafka topics.
7. **Flake discipline.** Integration tests must pass 3 consecutive runs
   (`--rerun-tasks`) before a phase is declared done. No real-time sleeps as
   synchronization — use awaitility with generous bounds, injected short
   intervals (the engine exposes package-private ctors for re-check/renew
   intervals), and container reuse for speed.

## Orchestration playbook

### Phase 0 — Scaffold (single agent, sequential)

Create the integration-test home before any fan-out:
- New module `maestro-integration-tests` (not published): register in
  `settings.gradle.kts`; depends on core, starter, store-postgres,
  messaging-kafka, lock-postgres, lock-valkey, maestro-test (fixtures),
  Testcontainers (postgres, kafka), awaitility.
- Gradle wiring: `integrationTest` runs via the module's `test` task; add a
  root convenience task if the existing conventions allow it cheaply.
  JUnit `@Tag("integration")` on every class; `@Tag("e2e")` reserved for P4.
- Shared fixtures IN THIS MODULE: `PostgresIntegrationSupport` (container +
  Flyway + truncation, mirroring PostgresTestSupport), `KafkaIntegrationSupport`
  (container + topic creation + consumer helpers), a `TestWorkflows` fixture
  package (small deterministic workflow classes covering: activity chain,
  awaitSignal, collectSignals, sleep, parallel branches, saga w/ compensation,
  failing activity w/ retry). Pin these in a short `maestro-integration-tests/SPEC.md`
  so P0–P2 builder agents share one contract.
- Exit: `./gradlew build` green with the empty-but-wired module.

### P0 — Engine × Postgres (2 builder agents in parallel after scaffold)

Split by file-disjoint suites, e.g. Agent A: lifecycle+memoization+recovery
(`EnginePostgresLifecycleIT`, `EnginePostgresRecoveryIT`); Agent B:
signals+timers+saga (`EnginePostgresSignalIT`, `EnginePostgresTimerIT`,
`EnginePostgresSagaIT`). Scenarios per docs/test-plan.md §P0 — including the
crash simulation (second `WorkflowExecutor` over the same store; assert
activities are NOT re-executed, use a counting activity) and the BUG1
regression pin (OptimisticLockException surfacing). Parallel branches MUST
be exercised here (currently unexercised outside unit tests).

### P1 — Kafka in CI (1–2 agents)

Per docs/test-plan.md §P1. The listener round-trip test needs a real
`@MaestroSignalListener` bean in a Spring context wired to Testcontainers
Kafka + PG — use `@SpringBootTest` with a minimal test app in the
integration module. The ack-on-failure contract test: write it RED,
`@Disabled("known defect — see tasks/todo.md")` with a precise message, and
report it; only implement the adapter fix if you can design redelivery
without infinite poison-message loops (error handler + bounded retries +
DLT) — otherwise leave the disabled test as the executable spec.

### P2 — Multi-node (1 agent; sequential after P0 — reuses its fixtures)

Two `WorkflowExecutor` instances in one JVM over shared containers, per
§P2. This is the phase most likely to expose real engine bugs (lock
handoff timing, adoption races) — budget for the library-bug protocol.
Inject short lock TTL / renew / recovery-poll intervals via the public
executor ctor and `startRecoveryPoller` params; never sleep for 30s real
time in a test.

### P3 — lock-postgres + messaging-postgres suites (2 agents, parallel)

Mirror `ValkeyDistributedLockTest` structure for the lock module (add
Testcontainers deps to that module's build); messaging-postgres per §P3.
These modules currently have ZERO tests — the coverage-gate fix.

### P4 — Loan E2E into CI (1 agent)

Wrap run-e2e.sh scenarios as `@Tag("e2e")` JUnit (or a documented nightly
CI job invoking the script). Keep identity assertions. Add the two-instance
loan-application scenario. Do not slow the PR loop with this tag.

### P5 — Shutdown contract (1 agent; RED-first by design)

Write the desired-behavior tests from §P5 as the executable spec, then fix
the engine (this is a real behavior change in `WorkflowExecutor.shutdown`/
`executeWorkflow` failure handling — distinguish `CancellationException`
from workflow failure; parked workflows must remain WAITING_* and their
locks released). Library-bug protocol applies; this one is pre-approved to
fix, not just document.

### P6 — Guardrails

Determinism replay-diff mode in `TestWorkflowEnvironment` (run workflow
twice from the event log, diff the sequences); module coverage gate in the
build; audits (health indicator, MaestroClient dedicated tests).

## Per-phase definition of done

1. Suites green ×3 consecutive runs; full `./gradlew build` green.
2. `docs/test-plan.md` matrix updated — flip the relevant — /◐ cells to ✅
   with the new class names (the matrix is living documentation).
3. `tasks/todo.md`: phase entry with test counts and any bugs found→fixed.
4. One commit per phase (message style: `test(P0): engine × postgres
   integration suite — <n> tests`), Co-Authored-By trailer per repo
   convention. Push to the working branch.
5. Any library fix = its own commit before the phase commit.

## Final report format

Per phase: suites + test counts, bugs found (with the failing-test names
that prove them), bugs fixed vs deferred, matrix cells flipped, flake-check
result (3× runs). Overall: the updated matrix summary — what is now
verified on real Kafka + Postgres that was not before, and what remains
open with reasons.

## Budget guidance

P0 and P2 deserve the most capable agents and the most iteration budget —
they are where real bugs live. P3/P4 are mechanical. If forced to cut
scope, cut from the bottom (P6, then P4 to nightly-script-only), never P0.
