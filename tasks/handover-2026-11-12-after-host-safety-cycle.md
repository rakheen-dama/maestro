# Handover — Maestro after the host-safety cycle

Written 2026-11-12. `main` @ `8952716`. For a coordinator or engineer picking
this up with no context from the session that produced it.

## 1. Where things stand

Everything below is merged and green on `main`:

| PR | What | Merge |
|---|---|---|
| #47 | Testcontainers 2.0.1 → 2.0.5 (CI had been red since 2026-11-07: `ubuntu-latest` moved to Ubuntu 26.04, whose Docker refuses the API version 2.0.1 speaks) | `39a8772` |
| #48 | A database fault abandons the local run instead of failing and compensating the workflow (Issue 34) | `a479622` |
| #49 | Bounded host load: batched lock renewal, paged service-scoped recovery scan (migration V101), default 20/20 worker limits, `@MaestroDataSource`, Flyway out-of-order fix (Issue 35) | `8952716` |

Evidence: post-merge Build & Test on `main` passed (2026-11-11 19:51 UTC); the
scheduled nightly E2E + chaos PR-gate ×3 passed on `main` (2026-11-12 02:32
UTC); the 2 h soak passed on each of the three branches before merge (runs
43035376695, 43053798356, 43082784284).

Read first: `CLAUDE.md`; `docs/open-issues.md` §§1–4 (note its §1 intro still
describes Valkey — see 4.6); `docs/release-notes.md` "Unreleased";
`docs/superpowers/plans/2026-11-11-host-safety-cycle/` (spec and both plans);
`tasks/lessons.md`.

Three behaviour changes from this cycle that will surprise anyone who has not
read the release notes:

- **Recovery adopts only instances whose `service_name` equals the node's
  `maestro.service-name`.** Every node of a service must share one name.
- **Unlisted task queues are bounded at 20 workflow runs / 20 live activities
  per queue per node** (`maestro.worker.default-concurrency`,
  `maestro.worker.default-activity-concurrency`; `0` on both = unbounded).
- **Migrations are numbered from 101 upward, store or lock alike.** Versions
  1–9 and 100 are history; 200–299 is retired. The directory, not the number,
  says which component a migration belongs to.

## 2. What the maintainer asked and what is still owed

The question that started this: can the library improve resilience without
hurting the host, and what is missing before Maven Central. The 2026-11-11
audit answered "not yet" and named findings. Two were built (PRs #48, #49).
The rest is this document.

**Provenance warning.** Sections 3 and 5 come from three read-only audits at
`a27a275`. Nothing in them was executed unless marked *verified*. File:line
anchors predate ~30 commits to the engine; locate by content, and re-verify
each finding against current code before planning work on it.

## 3. Engine and host-impact findings not yet addressed

Ranked by harm to a production host. None has an entry in
`docs/open-issues.md` unless an issue number is given — file them first.

| # | Finding | Status | Anchor (at `a27a275`) | Size |
|---|---|---|---|---|
| 3.1 | **Issue 30 — recovery adoption is not gated on capacity.** With default bounds this is now the default path: one node can adopt a whole backlog, hold a lock per run, and drain it through 20 slots while a peer idles. Arithmetic from the plan 02 final review: a 5,000-run runnable backlog at 2 s per next activity drains in ~500 s on one node versus ~250 s split across two. Startup recovery also runs serially on the `ApplicationRunner` thread (~7,500 statements for 2,500 adoptions), delaying readiness. | Open issue; recommended next | `docs/open-issues.md` Issue 30; `WorkflowExecutor.recoveryPass`, `StartupRecoveryRunner` | M |
| 3.2 | **Wake-permit leak.** `deliverSignal` always calls `parkingLot.unpark`; with no local waiter the payload is stored in `pendingWakes` and removed only by a later local park, terminate or run end on that node. With N replicas most signals arrive on a non-owning node, so one entry per `(workflowId, signalName)` is retained for the process lifetime. | *Verified* in code on 2026-11-11 | `ParkingLot.java` (`pendingWakes.put` in `unpark`); `SignalManager.deliverSignal` | S |
| 3.3 | **Admin retry deletes every `ACTIVITY_FAILED` memo, including failures the workflow caught.** A workflow that took a fallback path replays the primary live; if it now succeeds, the next sequence holds the fallback's memo and the run stands down as `nondeterministic_history` on every recovery pass. Activity analogue of Issue 33. | Traced by audit, not reproduced | `AbstractJdbcWorkflowStore` (delete by event type on retry); `WorkflowStore` Javadoc mandates it | M |
| 3.4 | **Maestro's Kafka beans suppress Spring Boot's.** `KafkaTemplate`, `ProducerFactory`, `ConsumerFactory` are registered by type ahead of `KafkaAutoConfiguration`; a host injecting Boot's `KafkaTemplate<String, MyDto>` fails to start. Deliberate and documented (`docs/configuration.md`), but it is the first thing an existing Kafka service hits. Candidate fix: `autowireCandidate = false` / `defaultCandidate = false` on Maestro's beans (the pattern #49 used for the DataSource). | Suppression verified by audit | `KafkaMessagingAutoConfiguration` | M |
| 3.5 | **Health goes DOWN when the pool is merely busy.** The indicator does a store read through the shared pool with a 2 s timeout; if liveness points at aggregate health, healthy pods get killed under load. Pollers also catch `Exception` only, so an `Error` kills one permanently. | Audit | `MaestroHealthIndicator`; `TimerPoller`, `RecoveryPoller` | S |
| 3.6 | **Shutdown can outlast Kubernetes' 30 s grace.** The handler sits in the web graceful-shutdown phase and can block for the 30 s drain plus poller joins plus a publisher flush. Related: Issue 29 (slot waiters not interrupted), now on the default path. | Audit | `GracefulShutdownHandler`; `WorkflowExecutor` shutdown | S |
| 3.7 | **Timers use JVM clocks.** `fire_at` comes from the scheduling node, the leader polls with its own `Instant.now()`; skew shifts every `sleep()`. Locks already use SQL `now()`. Listed as untested scenario S1.4. | Audit | `DefaultWorkflowOperations` (timer scheduling); `TimerPoller` | S |
| 3.8 | **Live activity results are returned raw; replayed results are round-tripped through Jackson.** A result type that does not round-trip identically, passed to a later activity, would change the argument fingerprint after any adoption and stand the run down with no code change. | *Suspected only* — the Jackson trigger was from memory, never run. Reproduce before believing | `ActivityInvocationHandler` (live return vs memo return); `ArgumentFingerprint` | S |
| 3.9 | **Per-parked-workflow steady load is unchanged:** 2–3 store reads every `maestro.signal.wake-recheck-interval` (30 s) per parked run; and each recovery pass still reads this service's active rows with payloads. Documented in `docs/operations.md` §13 as arithmetic. | Known, documented | Issue 12 | L |
| 3.10 | **The activity lock prevents nothing.** A failed acquire logs and proceeds; it costs two lock writes per activity. Consistent with "best-effort", but it is not a dedup guard. Decide: remove, or make it mean something. | Audit | `ActivityInvocationHandler` (`acquireLock`) | S |
| 3.11 | Smaller: retention sweeper can run 100 batches back to back with no pause; the signal janitor's `DELETE` has no limit; admin events default on (two Kafka sends per activity); default schema mode runs DDL as the app's DB user. | Audit | `RetentionSweeper`, `AbstractJdbcWorkflowStore` | S each |

## 4. Follow-ups created by this cycle

### 4.1 Open issues filed (in `docs/open-issues.md`)
- **Issue 36** — the test kit cannot apply production's default concurrency
  limits, so the same-queue wait deadlock (pitfalls rule 11) is invisible in
  users' tests.
- **Issue 37** — `WorkflowExecutor` has ten public constructors; needs a
  builder before a first release (it becomes API on publish).
- **Issue 38** — `PostgresWakeListener.close()` logs a WARN with a stack trace
  at every shutdown with a pooled `DataSource`.
- **Issue 39** — workflows recorded under another `service_name` are stranded
  silently; add a startup WARN.

### 4.2 Not filed yet — file or drop
- No meter for `runAbandoned` by reason. Alerting on a sustained
  `STORE_UNAVAILABLE` rate currently needs logs or spans. Also: the store
  guard's in-place retries log at DEBUG only.
- SQLSTATE is read from the first `SQLException` in the cause chain, not from
  `getNextException`.
- Workflow operations called from inside an activity body run unguarded under
  the pass-through scope. Unsupported usage; say so in `docs/pitfalls.md`.
- `MaestroSchemaInitializer`'s comment says `outOfOrder` matters only for
  databases that recorded V100 before V6–V9. It is also what lets V101 apply
  on a history holding the retired V200–V202 rows. Fix the comment and add a
  test before anyone touches that option.
- No test reaches the executor's `catch (Error)` latch branch or the
  `SagaManager.executeParallel` collector's store-fault pass (defensive code).
- Stand-down handlers were made best-effort for log-only reads; decision reads
  during an outage still throw out of their catch arm (pre-existing).
- Cosmetic: `MaestroDataSources` failure message omits the by-name
  tie-breaker; pool-sizing guidance uses `max`, not sum, without calling
  itself a heuristic; `docs/release-notes.md` ~:277 says "Now:" about the
  historical version bands.

### 4.3 The gap that hid Issue 35
No CI test upgrades an existing database: every suite starts empty, where
migrations apply in numeric order. `MaestroSchemaInitializerTest` now models a
"previous build" state for V101, but there is no general N-1 → N upgrade or
mixed-version test. Once a version is published, pin its migration set and
Flyway settings as a fixture and run the next build against a database it
migrated. This belongs in the release pipeline.

## 5. Maven Central blockers (none started)

From the release-readiness audit at `a27a275`; re-verify.

1. **POM URL and SCM point at the wrong repository** — `maestro-workflow/maestro`
   instead of `rakheen-dama/maestro`. *Verified 2026-11-12* in
   `maestro-bom/build.gradle.kts:30-47`; also
   `build-logic/.../maestro.library-conventions.gradle.kts` and `RELEASING.md`.
2. **Namespace** — `io.b2mash` needs a DNS TXT record on `b2mash.io`
   (`RELEASING.md`). Not checked.
3. **Release workflow is one-shot** — `publishToSonatype
   closeAndReleaseSonatypeStagingRepository` in one step, no staging
   inspection, no check the tag is on `main`, no SNAPSHOT guard, 20-minute
   timeout against a build that gets 30 in CI. No tag has ever been pushed.
4. **Produced POMs have never been inspected** — Spring modules declare
   versionless dependencies via `io.spring.dependency-management`; whether the
   published metadata resolves without the Boot BOM is unknown. Do a
   `publishToMavenLocal` and a consumer smoke project.
5. **No public/internal API boundary** — 17 of 29 `core.engine` types were
   public; `WorkflowContext` exposes `lease()`, `cancellation()`,
   `scopedValue()`. This cycle added more public surface (`RecoveryCursor`,
   `StoreUnavailableException`, `@MaestroDataSource`, two enum constants).
   Everything public becomes API on first publish. Decide the boundary
   (an `internal` package, or annotations) and add japicmp from the first
   release.
6. **Javadoc cannot fail the build** (`isFailOnError = false`,
   `Xdoclint:none`).
7. **Missing:** `SECURITY.md`, `CODE_OF_CONDUCT.md`, a changelog. Present:
   `LICENSE` (Apache-2.0), `CONTRIBUTING.md`.
8. **Should-fix before 1.0:** sealed `MaestroException` makes every new
   subtype source-breaking (this cycle added one); `WorkflowStore` is about 30
   methods and Postgres-shaped with no reusable contract test in
   `maestro-test`; Flyway is a hard dependency even under `schema.mode: none`;
   no Boot/JDK compatibility matrix; no ErrorProne/NullAway, JaCoCo, CVE
   scan, SBOM or Dependabot; no GraalVM statement.

Recommended order: 1, 6, 7 (hours) → 5 (the decision that is expensive to
change later) → 4 → 3 → publish `0.3.0-RC1`, not a final.

## 6. Decisions waiting on the maintainer

1. **Next cycle's scope.** Recommendation: Issue 30 plus 3.2 (leak) and 3.3
   (admin retry) as one engine cycle, then the Maven Central blockers.
2. **Is the unconditional service filter final?** It was kept after it broke
   49 of this repo's own tests that named each node differently. Issue 39's
   startup WARN is the mitigation on offer.
3. **Are 20/20 the right defaults?** They were a judgement, not a measurement.
   The chaos gate and soak pass with them.
4. **Feature gaps a first evaluator will ask about** (none built, none
   planned): list/search API on `MaestroClient`; workflow-level timeout;
   cooperative cancellation; child workflows; continue-as-new; cron;
   payload codec and size limit; dashboard authentication and a published
   image.

## 7. Housekeeping

- `tasks/todo.md`: the "Host-safety cycle" milestone still has an unticked
  "Review section added here after merge".
- Remote branches left in place: `fix/store-fault-stand-down`,
  `feat/bounded-host-load`, `ci/testcontainers-2.0.5`.
- Local worktrees left in place under `.claude/worktrees/`: `bounded-load`
  (merged) and `agent-a16b58f61778e83e3` (the Testcontainers branch, merged).
  Each holds a git-ignored `.superpowers/sdd/` workspace with the per-task
  briefs, reports and review ledgers for this cycle; delete once nobody
  needs them.
- CI warnings, not failures: `actions/setup-java@v4` is deprecated (move to
  `@v5`); the other `@v4` actions target the retired Node 20.
- `docs/open-issues.md` §1 ("Maestro in five minutes") and its module map
  still present Valkey, RabbitMQ and the Postgres transport as current;
  `docs/maestro-prd.md`, `docs/test-plan.md` and
  `docs/multi-instance-test-plan.md` have the same drift.
- Any development database migrated by the plan 02 branch before the
  renumbering holds a `V10` history row and will fail startup; delete the row
  (the index can stay). See Issue 35.

## 8. How this cycle was run (keep what worked)

- Spec and plan first, in `docs/superpowers/plans/<date>-<cycle>/`; one
  implementer per task and a fresh reviewer per task; one whole-branch review
  at the end; a single fix wave; sequential PRs. Opus agents throughout, at
  the maintainer's request.
- The reviews earned their cost. Per-task and final reviews found, among
  others: a latched run could still append history once the store healed; the
  saga's own append helper swallowed the store fault; the fallback
  `renewAll` stopped at the first throwing lock; the chaos harness identified
  the recovery query by SQL text and would have reported zero; and that
  numbering the migration V101 instead of V10 removes an upgrade limit
  entirely.
- Implementers were told to stop on a product decision rather than edit
  tests. That is how the 49-test service-name conflict and the Flyway defect
  surfaced as questions instead of quiet workarounds.
- Gradle: use `--no-watch-fs` in worktrees (file watching left stale classes
  in sibling worktrees). Never `git stash` across worktrees.
- A PR whose base is not `main` gets no Build & Test run; retarget and push to
  trigger it.
