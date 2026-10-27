# Bug-Fix Cycle 2026-10-24 — Plan Index

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement these plans task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Execute the sub-plans **in the order listed below**; later plans assume earlier ones have landed.

**Goal:** Close every known defect in Maestro as of `main` @ 4eb28e9: the 15 engine findings (C1–C15) and 13 adoption findings (F1–F13) of the independent assessment, the three open ledger issues (#25, #26, #27), and the untriaged chaos-harness violations (I1, I4).

**Spec:** [`00-spec-independent-assessment.md`](00-spec-independent-assessment.md) (this folder) plus `docs/open-issues.md` §§ Issue 25–27. Finding IDs (C*, F*, I*) below refer to the spec's tables.

**Architecture:** Six independent sub-plans, one per subsystem, each producing working, tested software on its own. Correctness fixes in `maestro-core` come first (sequence space, replay, activity semantics, signals/store), then the Spring-integration and documentation fixes that depend on the new core contracts, then CI re-enablement and chaos triage that validates the whole branch.

**Tech Stack:** Java 25, Spring Boot 4.0.x, Gradle 9 Kotlin DSL, JUnit 5, Testcontainers 2 (Postgres 16, Kafka, Valkey), Jackson 3 (`tools.jackson`), Flyway 11, JSpecify.

## Sub-plans (execute in this order)

| # | File | Scope | Findings closed |
|---|---|---|---|
| 1 | [`01-saga-parallel-sequence-space.md`](01-saga-parallel-sequence-space.md) | `DefaultWorkflowOperations.parallel()` + `SagaManager` sequence partitioning | C1, C2, C3, C4 |
| 2 | [`02-replay-drift-and-error-handling.md`](02-replay-drift-and-error-handling.md) | Replay drift detection, stand-down reason taxonomy, escaped `Error`s, exception reconstruction | C8, C9, C10, C15 |
| 3 | [`03-activity-semantics.md`](03-activity-semantics.md) | Activity timeout enforcement, retry semantics honesty, terminate stops live work, dual-execution stop, exception-wrapping contract | C5, C6, C7, C11, F2 |
| 4 | [`04-signals-timers-and-store-atomicity.md`](04-signals-timers-and-store-atomicity.md) | Durable `awaitSignal` timeouts, signal hygiene, atomic terminal write, redelivery status-write race | C12, C13/#26, C14, #27 |
| 5 | [`05-spring-integration-and-docs.md`](05-spring-integration-and-docs.md) | Per-run workflow instances, Flyway isolation, table prefix, `maestro.worker.*`, getting-started/admin/README accuracy, sample tests | F1, F5, F6, F8/#25, F9, F10, F11, F12, F13 (docs part), F3/F4 (docs part) |
| 6 | [`06-verification-and-chaos-triage.md`](06-verification-and-chaos-triage.md) | Re-enable nightly E2E/chaos, triage I1/I4, stale test-plan, flaky integration suites, coverage gate over samples, whole-branch gate | I1, I4, test-plan staleness, `RecoveryPassIsolationTest` flake |

Plan sizes: 5 + 5 + 6 + 7 + 9 + 6 = **38 tasks**, each ending in a commit. Four plans (01–04) add types to the sealed `MaestroException` `permits` clause; executing out of order produces merge conflicts there and in `WorkflowContext`/`SignalManager`, which plans 01, 02, 03, 04 and 05 all touch.

Out of scope for this cycle (features, not defects — tracked separately): history compaction/retention, lock fencing (#11), recovery-poll scaling (#12), authentication on admin/commands (F7), payload encryption codec, cross-node queries (F7 in DX table), child workflows, static analysis adoption.

## Spec corrections and reviewer-attention decisions

The sub-plan authors read the code after the spec was written. Where they found the spec stale or a brief decision costly, the plan records the deviation; the ones a reviewer should know before approving:

- **C9 (Plan 02):** the "distinct stand-down reason for unreadable payloads" the spec asks for already exists on `main` as `StandDownReason.UNKNOWN_EVENT_PAYLOAD` (tag `unknown_event_payload`). Plan 02 keeps the name and adds the missing root-cause detail and operator remedies.
- **C1/C4 (Plan 01):** anchoring compensation correctly after the parallel space means a saga with two sequential `parallel()` calls now fails compensation loudly (int overflow of the ×1000 block math) where it previously ran at silently wrapped sequences. Filed as **Issue 28 (structural, open by design)** with two redesign candidates (BIGINT widening; structured event identity). Plan 06 therefore numbers new ledger entries from 29.
- **C5 (Plan 03):** a `DuplicateEventException` on the live path can only come from a concurrent runner, so the loser adopts the *record* and stands down; it never returns an adopted value and continues. `StaleRunException` is a `MaestroException`, not a fourth control-flow `Error` (sealed `permits` frozen at three) — a workflow author's `catch (Exception)` can still swallow it, as with `DuplicateEventException` today.
- **C11c (Plan 03):** the engine never interrupts workflow threads, so the retry backoff wait is made shutdown-aware instead; `shutdown()` no longer waits for a run that is mid-backoff (it stays `RUNNING`, recoverable). Behaviour change, in release notes.
- **C12 (Plan 04):** the timer row is created only when the await actually parks; the deadline memo is a `TIMER_SCHEDULED` event, so a parked await occupies two sequence slots. Retry after a timeout failure times out again unless a signal has since arrived (memoized deadline governs). Plan 06 flags that this stricter deadline could make chaos violation I1 *more* frequent if I1 is a `SignalTimeoutException`; its triage records elapsed-since-first-arm to tell a regression from a chaos budget collision.
- **C14 (Plan 04):** the signal janitor excludes `FAILED` instances because `FAILED → RUNNING` retry is allowed and relies on late signals surviving.
- **Issue 27 (Plan 04):** a crashed consumer now consumes a redelivery attempt (the claim is the only durable marker), contradicting the comment at `PostgresWorkflowMessaging.java:283-285`; poison messages dead-letter after `maxAttempts` instead of looping. The one decision most worth re-opening in review.
- **F1 (Plan 05):** per-run instances via prototype-scoped `@DurableWorkflow` bean definitions and `getBean(name)`, not `createBean(targetClass)`; singleton user-declared workflow beans fail fast at startup.
- **F5 (Plan 05):** Flyway libraries were already transitive. Existing dev databases whose Maestro rows sit in the host's `flyway_schema_history` must be dropped and recreated (pre-1.0, documented).
- **F8 (Plan 05):** deleting `publishTask`/`subscribe` orphans `maestro_task_queue`; migration `V202` drops it and the unread lifecycle queue table.
- **F12 (Plan 05):** true hiding of `nextSequence()/setSequence()/setReplaying()` needs JPMS; the plan makes them package-private behind a `WorkflowContextInternals` accessor and records the limitation.
- **CI (Plan 06):** a scheduled keepalive cannot defeat GitHub's 60-day inactivity rule (it gets disabled too); a `push`-triggered re-enable job in `build-test.yml` is the mechanism that works. All seven failing runs' artifacts have expired, so triage starts by dispatching a fresh baseline run and archiving its logs.
- **I4 hypothesis (Plan 06):** `collectSignals` N-of-M may be satisfied by a byte-identical redelivered duplicate, leaving a later distinct signal unconsumed. Maps to no planned fix; if confirmed it becomes Issue 29 with a task stub appended to Plan 04.

## Global Constraints

Every task in every sub-plan implicitly includes these.

- `maestro-core` must never depend on Spring (`CLAUDE.md`); verify with `./gradlew :maestro-core:dependencies --configuration compileClasspath | grep -c springframework` → `0`.
- Jackson 3 only: `tools.jackson.*`, never `com.fasterxml.jackson`.
- Jakarta EE 11 namespaces (`jakarta.*`); Java SE `javax.sql`/`javax.crypto` stay.
- JSpecify `@Nullable` on every new public nullable parameter/return.
- No Lombok. Records for DTOs. Javadoc on every new public type and method; SPIs especially.
- The sealed `MaestroControlFlowError` `permits` clause stays exactly three types unless a task explicitly says otherwise; every new `catch (Throwable)` checks `instanceof MaestroControlFlowError` and rethrows before recording anything as a failure.
- Never break `(workflow_instance_id, sequence_number)` uniqueness; never add I/O into workflow-author code paths between activity calls.
- New Flyway migrations: store band V1–V99, lock band V100–V199, messaging band V200–V299 (`docs/open-issues.md` §1 module map). Never edit an existing migration.
- Every correctness fix gets **two** pins: a unit test against the in-memory fakes **and** an `@Tag("integration")` test against real Postgres in `maestro-integration-tests` (the fakes have been wrong six times — spec §5).
- TDD: write the failing test, run it and see it fail for the expected reason, then implement. Commit after every task with a Conventional Commit message ending in `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Verification commands (run from repo root):
  - unit: `./gradlew :maestro-core:test --console=plain`
  - starter: `./gradlew :maestro-spring-boot-starter:test --console=plain`
  - real backends: `./gradlew :maestro-integration-tests:test --console=plain` (needs Docker)
  - whole PR gate: `./gradlew build --console=plain`
- Behaviour changes are recorded under `## Unreleased` in `docs/release-notes.md` and, when they close a ledger issue, the issue's section in `docs/open-issues.md` is marked Resolved with the commit hash.
- Pre-1.0 and unreleased: no backward-compatibility shims are required; delete rather than deprecate.

## Review Focus

Inputs the spec implies but which are easy to leave untested; each sub-plan that owns the code adds the pin named here.

1. **Parallel inside compensation.** A `@Compensate` action that itself calls `parallel()` after Plan 1's counter fix — must not collide with the forward branch space (Plan 1, Task on overflow guard).
2. **Signal arriving in the same millisecond the durable `awaitSignal` timeout fires** — exactly one of "signal consumed" or "timed out" is recorded, never both, on real Postgres (Plan 4).
3. **Terminate while a live activity is mid-retry backoff** — the backoff sleep is interrupted and no further attempt runs (Plan 3).
4. **Two nodes race the per-run workflow instance** — per-run instantiation must not leak the previous run's object into `queryWorkflow` after re-adoption (Plan 5).
5. **Host application with its own `V1__init.sql`** boots alongside Maestro's isolated Flyway history with no version collision (Plan 5).

## Conventions for the sub-plan files

Each sub-plan file starts with the standard header (goal, architecture, tech stack, spec pointer, its own Global Constraints line "See README.md § Global Constraints", its own Review Focus), then numbered `### Task N:` sections with **Files**, **Interfaces**, and checkbox steps in the test → fail → implement → pass → commit rhythm. Cite `file:line` as of `main` @ 4eb28e9 for every modification. Line numbers are a locator, not a contract: implementers re-read the cited region before editing.
