# Maestro — Independent Assessment

**Date:** 2026-10-24. **Repo state:** `main` @ 4eb28e9 (last commit 2026-08-11), 485 commits, `0.3.0-SNAPSHOT`, never released, zero tags.
**Method:** five parallel research tracks (developer experience, engine correctness, operations, verification maturity, competitive landscape), each citing `file:line`. Every High-severity finding below was re-verified in source by the coordinator. 754 tests were executed locally (0 failures). GitHub Actions history was pulled with `gh`.

---

## 1. Verdict in one paragraph

Maestro is a genuinely well-conceived idea with an unusually self-aware codebase and a correct memoization core for **linear, idempotent workflows**. It is **not yet production-ready**, and its two headline differentiators beyond "durable retries + sleep" — `parallel()` and saga compensation — share a sequence-partitioning scheme that currently has two confirmed High-severity correctness bugs. Its own chaos harness has been reporting unresolved invariant violations on unchanged `main` since 2026-08-09. The market gap it was designed for ("embedded durable execution for Java on your own Postgres") was filled during 2026 by DBOS Transact Java 1.x; what remains defensible is a narrower Spring Boot 4 + Kafka choreography + sagas + free dashboard niche.

| Axis | Score | One-line reason |
|---|---|---|
| Value if it worked as documented | 8/10 | Zero-new-infra durability, sagas, signals, versioning, test kit, dashboard: a real and attractive bundle for Spring teams |
| Engine correctness today | 5/10 | Linear core sound; parallel/saga path has 2 High bugs + unenforced invariant; drift undetected |
| Developer experience | 5/10 | Small, idiomatic API; but the paved path (query state, catching activity errors, testing timeouts) is broken or untestable |
| Operations / production-readiness | 4/10 | No retention, O(N×M) recovery polling, Flyway coexistence unsolved, no auth, dead config |
| Verification maturity | 5/10 | Layered tests incl. chaos; but chaos oracle is disabled and failing, no static analysis, 3 open correctness issues |
| Market positioning | 4/10 | README compares against strawmen and omits DBOS and JobRunr; "nothing new to deploy" no longer unique |

---

## 2. Problems it solves well (verified)

1. **Crash-recoverable linear workflows with no new infrastructure.** Live path executes → serialises → appends under the `(instance, seq)` unique index (`ActivityInvocationHandler.java:379-406`, `V2__create_indexes.sql:5-6`); crash-after-persist is handled by re-reading on `DuplicateEventException` (`:483-499`). Postgres-only mode is real: `spring.datasource.*` plus three `maestro.*` keys (`sample-postgres-only/application.yml`), LISTEN/NOTIFY + `FOR UPDATE SKIP LOCKED` (`PostgresWorkflowMessaging.java:286-300`).
2. **Signals are never lost (in the non-chaos case).** `SIGNAL_RECEIVED` is appended before the CAS consume (`SignalManager.java:526-527`); pre-delivery rows adopt in the same JDBC transaction as `createInstance` (`AbstractJdbcWorkflowStore.java:170-178`).
3. **Durable timers with replay self-heal** distinguishing pending vs fired-just-before-crash (`DefaultWorkflowOperations.java:239-273`; `markTimerFired` is CAS at store `:530-537`).
4. **Deploy-safe control flow.** Shutdown/terminate/unknown-history are `Error`s, unwrapped before `Exception` at every reflective boundary (`AIH:446`, `WE:1580`, `DWO:637`, `RE:52`, `SagaM:484`). A routine deploy cannot be recorded as a workflow failure by a user's `catch (Exception)`.
5. **Mixed-version stand-down** — unknown event types degrade to a sentinel on read, `version()` peeks without consuming a slot, stand-down writes nothing (`WE:1682-1693`). Honestly documented in `docs/operations.md §10`.
6. **Graceful shutdown is correct by construction** (`WorkflowExecutor.java:1099-1152`); parked workflows fail open on store blips (`DefaultWorkflowOperations.java:356-366`).
7. **Small, Spring-idiomatic API.** Eight annotations, one `WorkflowContext` with ~12 user methods, two-method `MaestroClient`. Good fail-fast startup validation (`ActivityProxyFactory.java:213-268`). Six-line unit tests via `TestWorkflowEnvironment`.
8. **Observability out of the box**: Micrometer meters, OTel spans, W3C trace propagation through Kafka (`docs/observability.md`).
9. **Honest documentation.** `docs/open-issues.md`, `docs/operations.md`, `tasks/lessons.md` openly record silent-zombie risks, inert config, and "fakes were wrong six times".

---

## 3. Problems it fails to solve

### 3a. Correctness defects (confirmed in source)

| ID | Finding | Evidence | Sev |
|---|---|---|---|
| C1 | **Compensation after a failed `parallel()` collides with sibling-branch sequence ranges.** On branch error the fork rethrows (`DWO:633-639`) *before* `setSequence` (`DWO:645`), leaving the counter at `p`. `compensate()` anchors at `p+1` (`SagaManager.java:172`); entry *i*'s block `(p+1)*1000+(i+1)*1000` equals branch *i+1*'s base `p*1000+(i+2)*1000` (`DWO:582`). A `void` compensation "replays" and never executes; an `ACTIVITY_FAILED` there reconstructs as a compensation failure. The canonical "reserve ∥ charge, charge fails" saga demo is plausibly broken. | DWO:551-645, SagaM:172-281 | High |
| C2 | **Parallel compensation cannot survive a crash.** `executeParallel` appends its fork marker unconditionally (`SagaM:371-374`); `appendEvent` rethrows duplicates (`SagaM:710-717`) → stand-down → row stuck `COMPENSATING`, re-adopted every 60 s forever. The existing unit pin passes only because the fake store has no uniqueness check. | SagaM:362-378, 705-720 | High |
| C3 | **999-step-per-branch limit is unenforced.** Branch contexts are plain `WorkflowContext`s with unbounded `incrementAndGet` (`DWO:591-600`, `WorkflowContext.java:191-193`). Step 1000 silently reads/writes the next branch's slots. | DWO:581-600 | High |
| C4 | Nested or sequential `parallel()` calls exhaust `int` almost immediately (depth 3 or three sequential forks) → `IllegalStateException` → FAILED with compensation (`DWO:540-548`). | DWO:540-548 | Med |
| C5 | Dual execution (lock lost) does not stop at first collision: loser adopts each winner payload and keeps executing live activities (`AIH:483-499`; `InstanceStatusWriter:139-149` only throws on TERMINATED). | | Med |
| C6 | Terminate does not stop live activities; only parks and status writes observe `TERMINATED` (`AIH:195-430`). `WorkflowTerminatedException` Javadoc promises otherwise. | | Med |
| C7 | Terminate-vs-compensate race only half closed: no per-entry re-check in the sequential loop (`SagaM:255-324`); Javadoc admits "one open race" while release notes say Resolved. | | Med |
| C8 | A user `Error` escaping the workflow body loops silently: no `catch (Throwable)` in `executeWorkflow` (`WE:1450-1532`), row stays RUNNING, re-adopted every 60 s, never FAILED, no observer callback. | | Med |
| C9 | Replay deserialisation failure (e.g. enum constant removed) is misclassified as "newer node wrote it" → perpetual WARN stand-down loop, never FAILED (`UnknownHistoryGuard:97-107`, `WE:1676-1693`). | | Med |
| C10 | **Non-determinism is not detected.** Replay switches on `eventType` only; `step_name` is never compared (`AIH:260-279`); `parallel()` replay ignores stored `branchCount` (`DWO:559-566`). Renamed/reordered activities replay the wrong payload silently. `DeterminismChecker.java:17-18` says so itself. | | Med/High |
| C11 | **`@RetryPolicy` and `startToCloseTimeout` are not durable.** Retry is an in-memory `Thread.sleep` loop (`RetryExecutor.java:49-96`); attempt count is not persisted; `startToCloseTimeout` is used only as a lock TTL (`AIH:572`), nothing enforces an activity timeout. README's "Retries survive JVM restarts" is at best half true. | | Med |
| C12 | `awaitSignal` timeout is wall-clock from `Instant.now()`, no timer row (`SignalManager.java:359`); re-arms from scratch after a crash; untestable with the controllable clock (samples resort to a static mutable timeout holder). | | Med/High (DX) |
| C13 | Terminal status write and terminal event append are non-transactional (open Issue 26). | | Low |
| C14 | Signals to terminal workflows stored unconsumed forever; `received_at` is node-local clock; Valkey wake is fire-and-forget (bounded by 30 s recheck). | | Low |
| C15 | Exception reconstruction on replay uses `Class.forName` + `(String)` ctor; exceptions without that ctor replay as `RuntimeException`, so `catch (MyDomainException)` behaves differently live vs replay. | AIH:325-351 | Low |

### 3b. Live evidence from the project's own chaos harness

- `gh run list` shows the weekly **Chaos soak (2h window)** job failing on unchanged `main` on 2026-08-09, 08-16, 08-30, 09-20, 10-04, and the PR-gate on 09-01 and 09-12. Run 37169821147 (2026-10-04) logs:
  - `VIOLATION [I1] terminal status != declared expectation` — two workflows expected COMPLETED ended FAILED (paths HAPPY and CONDITIONS_LOOP).
  - `VIOLATION [I4] unconsumed application signal with NO consumed twin for COMPLETED workflow — genuinely missed signal` (`document.uploaded` ×2).
- None of these are in `docs/open-issues.md`, `release-notes.md` or `tasks/`. They post-date the last commit and are untriaged.
- GitHub auto-disabled the `E2E (loan-origination)` schedule after 60 days of inactivity; **no chaos/E2E coverage runs today.**
- `Build & Test` failed three times on `main` (07-28, 08-02, 08-03) in integration suites that pass locally → flakiness in exactly the layer that matters.

### 3c. Structural limitations (architecture, not bugs)

- **Unbounded history, full replay, no compaction.** No continue-as-new, snapshot, truncation, retention or archival anywhere in main code. Replay costs one `getEventBySequence` round-trip per step. A 10k-step loop costs ~10k reads per recovery. Completed workflows' events are never deleted. PRD's `archival.retention: 90d` is unimplemented; `PostgresMessageCleaner`/`PostgresLockCleaner` exist as beans but nothing schedules them.
- **Integer sequence space with ×1000 partitioning** makes `parallel()` usable only shallowly (C3/C4).
- **Recovery is lock-TTL only, no fencing, no heartbeat table** (Issue 11, by design). Side-effect correctness rests entirely on user idempotency — a word that appears in neither README nor PRD.
- **Every node scans every active row every 60 s** with no LIMIT, no service filter at SQL level, full JSONB `input` loaded (`WE:504-531`, store `:207-215`). Parked workflows cost ~2 reads/30 s each. 10k parked on one node ≈ 1.7k ops/s idle.
- **No workflow-level timeout, no child workflows, no activity heartbeat/cancellation.**
- **Queries work only on the owning node** (`WE:1202-1209`); a load-balanced `GET /status` on a 3-node service fails 2/3 of the time. Not documented in the Queries section.

---

## 4. Problems it creates for adopters

| # | Problem | Evidence | Sev |
|---|---|---|---|
| F1 | **Workflow classes are Spring singletons shared by every concurrent instance**, yet README, getting-started, concepts.md (`:283-330`) and every sample store per-instance state in `private volatile String currentStep` exposed via `@QueryMethod`. `WorkflowRegistration.workflowImpl` is the bean; `WorkflowExecutor.java:1570,1964` invokes it for every run and query. Two concurrent orders → A's status returns B's step. Nothing documents this. (Verified by coordinator.) | High |
| F2 | **Activity exceptions reach workflow code wrapped in `ActivityExecutionException`** (`RetryExecutor.java:76-105`, rethrown at `AIH:400`), but PRD (`:250`) and `PaymentProcessingWorkflow.java:60` teach `catch (PaymentDeclinedException e)` — dead code. The declined exception is also not in `nonRetryableExceptions`, so a declined card retries 30× with up-to-10-minute backoff. No test covers this (sample has none). (Verified by coordinator.) | High |
| F3 | Non-determinism after a refactor is silent or becomes a zombie loop (C9/C10). The engine neither tells the developer nor fails the workflow. Temporal throws `NonDeterministicException`. | High |
| F4 | `awaitSignal` timeouts are untestable with the controllable clock (C12). | High |
| F5 | **Flyway collision with the host app.** Maestro's `V1..V4`, `V100`, `V200-201` land in default `classpath:db/migration` sharing the host's `flyway_schema_history`; no property to relocate/disable; getting-started omits `spring-boot-starter-flyway`/`-jdbc` which all samples declare as "must be declared explicitly". (Unverified at runtime.) | High |
| F6 | **Custom table prefix = own the schema forever.** Migrations hardcode `maestro_`; lock/messaging SQL ignore the prefix (`PostgresDistributedLock.java:54`, `PostgresMessageCleaner.java:46`); `cross-service.md:488` recommends a per-service prefix anyway. | High |
| F7 | **Zero security.** No Spring Security in admin app, starter, or command path; retry/terminate/signal are unauthenticated POSTs; commands ride the `maestro.signals.{svc}` topic so anyone who can produce there can terminate workflows (`docs/admin.md:292-300` admits it). Payloads are plaintext JSONB with no codec SPI (`PayloadSerializer` is `final`), no PII masking, retained forever. | High |
| F8 | **Documented config that does nothing**: `maestro.worker.*` (Issue 25) in the "minimal configuration" and every sample; `maestro.tasks.*` topics have no production caller yet docs instruct creating them plus `.DLT`s. Hence **no concurrency bound anywhere**. | Med |
| F9 | Kafka bean suppression: Boot's `kafkaTemplate`/factories are deliberately suppressed (`configuration.md:641-648`); app code must inject by name — a trap in services already using Kafka. Missing `.DLT` in prod stalls the whole topic on that node (single consumer thread); DLT partition-count requirement undocumented. | Med |
| F10 | Admin dashboard is a fourth infra piece (own Boot app, own Postgres) and Kafka-only; `docs/admin.md:139` claim that it reads Postgres lifecycle queue in Postgres-only mode is false (nothing reads that table). | Med |
| F11 | Client API incomplete: `WorkflowHandle` has only `signal`/`query`/`workflowId`; no status/result/terminate/cancel; `startAndWait` polls with `Thread.sleep` and throws without a cause. | Med |
| F12 | Engine internals (`nextSequence()`, `setSequence()`, `setReplaying()`) are public on the user-facing `WorkflowContext` with only a Javadoc "do not call". Activity args are never persisted so the dashboard cannot show what a step was called with. Stringly-typed durations/compensation names. | Low |
| F13 | **Java 25 + Spring Boot 4.0.x hard floor** (`maestro.java-conventions.gradle.kts:8-12`), Jackson 3, Testcontainers 2. Realistic audience today: greenfield Boot 4 services only. Nothing published; dependency snippets have no versions and no BOM; `./gradlew integrationTest` referenced in README/CONTRIBUTING does not exist. | Adoption |

---

## 5. Ease of use

**Approachability: strong.** A Spring developer can read the whole public API in 20 minutes. Auto-config is Boot-4-idiomatic (`AutoConfiguration.imports`), only `maestro.service-name` is mandatory, errors at startup are actionable, and a workflow unit test is six lines.

**But the paved path is broken at four points** a new user hits in their first hour:
1. Add a `currentStep` field and `@QueryMethod` as the README shows → wrong under concurrency (F1).
2. Catch a domain exception from an activity as the PRD and payment sample show → never fires (F2).
3. Write `awaitSignal(..., Duration.ofDays(7))` → untestable (F4).
4. Edit a workflow without a `version()` gate → silent wrong payloads or a zombie stand-down loop (F3).

**Testing story:** `maestro-test` is low-boilerplate but the project itself admits the in-memory fakes "have already been wrong about this codebase six times" (`open-issues.md:117-119`). A green `TestWorkflowEnvironment` suite proves workflow logic, not durability, and no Testcontainers-backed harness ships as a library. Three of four samples have zero tests — which is exactly why F2 went unnoticed.

**Versioning ergonomics:** the two-step "ship gate, then raise floor" playbook is the same discipline Temporal asks; reasonable. But without drift detection (C10) a missed gate hurts far more here than in Temporal.

---

## 6. Verification maturity and code health

- **Local run (coordinator-verified counts):** core 412, test-kit 58, starter 109, store-postgres 66 (real Postgres), integration-tests 109 (Postgres+Kafka+Valkey containers). **754 tests, 0 failures, ~5 min wall.** `sample-postgres-only` has `NO-SOURCE` tests.
- **Inventory ≈ 973 `@Test`.** Test:main LOC ratio 1.64. Coverage gate fails any `maestro-*` module with no tests (samples excluded).
- **Only in-memory or weaker:** `@QueryMethod`, health indicator, auto-config activation chain, determinism (nothing), real two-binary mixed-version (manual script only). `docs/test-plan.md` is stale (credits a deleted RabbitMQ suite; "16 unit test classes" vs 48).
- **Code health:** largest class `WorkflowExecutor.java` 2,020 lines; comment ratio ~0.5; **no static analysis** (no ErrorProne/NullAway/Checkstyle/Spotless/SpotBugs/JaCoCo, `-Xlint` without `-Werror`, `Xdoclint:none`); no ArchUnit for the "core never depends on Spring" rule (enforced only by absence of the dependency); 52 log-and-continue catches; 13 `Instant.now()` sites in core engine beside the warning telling users not to; duplicated helpers across modules.
- **Defect history:** ~40 defects logged; ~22 correctness, ~10 "config that reads correctly and does nothing". Fix-commit share rose W31→W33 (26%→50%) and was still rising when development stopped. Open: #25, #26, #27, plus by-design #11, #12, plus the untriaged chaos violations.
- **Provenance:** single human author, 82% of commits co-authored by Claude models via a coordinator/builder-agent process. Consequence for reviewers: a great deal of prose asserting correctness; treat tests and CI artefacts, not Javadoc, as evidence. `tasks/lessons.md` records "Six pins in this cycle first passed against broken code" and "Evidence fabrication recurred three times before the rule was made mechanical."

---

## 7. Competitive position (October 2026)

| Competitor | Infra | Java/Spring | Licence | Beats Maestro at | Maestro beats it at |
|---|---|---|---|---|---|
| **DBOS Transact Java** 1.2.0 (Oct 2026) | Postgres only, embedded | Production; Boot 3.5 starter, Boot 4 test-only; JDK 17 | MIT (Conductor UI paid) | Maturity, polyglot, durable queues, release velocity | Boot 4 primary, Kafka signal bridge, sagas, free admin UI |
| Temporal | Separate server + Postgres/MySQL/Cassandra (ES optional) | Very mature; Boot 2/3/4 starter | MIT / Cloud | Everything at scale, determinism checks, replayer | No cluster to run |
| Restate | Single-binary server | Java/Kotlin SDK, Boot starter | BSL server | K-V state, low latency, no DB | State in your Postgres, no extra server |
| JobRunr / Pro | Your DB | Very mature, Boot 4 | LGPL / Pro paid | Install base, simplest durable jobs | Replay, signals, sagas, parallel memoization |
| Flowable 8 | Embedded, any JDBC | Mature, Boot 4 | Apache 2.0 | BPMN, human tasks, history | Code-first, memoization model |
| Conductor, Camunda 8, Axon 5, Dapr Workflow, Infinitic, LittleHorse | Central server/sidecar/broker | Varies | Mixed; Camunda 8.6+ and Axon 5.1+ partly commercial | DSL/BPMN/ES ecosystems | Nothing new to deploy |

**README accuracy:** the Temporal row ("Cassandra, Elasticsearch, multiple server roles") is outdated/misleading (Postgres fully supported, ES optional since 1.20, single-binary possible); Conductor's ES dependency is optional; Camunda and Axon rows are accurate. The PRD's "there is no lightweight, embeddable durable workflow engine that leverages existing Spring Boot infrastructure" is **false as of 2026** (DBOS Java). README omits DBOS, JobRunr, Restate, Dapr, Flowable entirely.

**Real gap Maestro fills:** Spring Boot 4 teams on Postgres + Kafka with multi-service business processes who need sagas, cross-service signals with trace propagation, and a free self-hosted dashboard, and who reject Temporal on ops cost and find JobRunr too job-shaped. Real but modest, and DBOS could close it within a release or two.

---

## 8. Recommendations (priority order)

**Release blockers**
1. Fix C1, C2, C3: make `parallel()` advance the counter even on failure (or anchor compensation from the post-fork sequence), replay-guard the parallel-compensation fork marker, enforce the 999-step branch limit with a hard error. Add real-Postgres tests for each; the current pins pass against fakes.
2. Triage the chaos soak I1/I4 violations; re-enable the E2E/chaos schedule.
3. Fix F1: instantiate a workflow object per run (Temporal model) or provide `workflow.state()`; rewrite README/concepts/samples.
4. Fix F2: unwrap `ActivityExecutionException` to the cause at the proxy boundary (or document and make the samples correct); add a non-retryable marker usable at throw time.
5. Add step-name comparison on replay → `NonDeterministicWorkflowException` (and distinguish "unreadable payload" from "newer node").
6. Retention: scheduled purge of completed instances/events/signals/timers; schedule the existing cleaners.

**Before 1.0**
7. Make `awaitSignal` timeouts durable timer rows (also fixes testability). Persist retry attempt counts or stop claiming retries survive restarts. Enforce `startToCloseTimeout`.
8. Flyway: own Flyway bean or configurable location; table prefix honoured in all SQL or removed.
9. Remove `maestro.worker.*` and `maestro.tasks.*` from docs/samples or implement them; implement a concurrency bound.
10. Security: Spring Security on admin, signed/authenticated admin commands, payload codec SPI.
11. Add ErrorProne/NullAway + ArchUnit; break up `WorkflowExecutor`.
12. Rewrite the README comparison table: fix Temporal/Conductor rows, add DBOS/JobRunr rows honestly, reposition as "Durable workflows for Spring Boot 4 on Postgres + Kafka — sagas, cross-service signals, free dashboard."

**Unverified items in this report:** Flyway collision at runtime; `max.block.ms` blocking of inline lifecycle events; getting-started build file compiles; F1/F2 are code-read conclusions (not executed reproductions); competitor facts are from web sources dated 2026 cited in the landscape research.
