# Maestro — Open Issues and How to Tackle Them

**Audience:** an engineer or agent picking up work on Maestro with no prior
context on how these issues were found.
**Status date:** 2026-07-28, after the P0–P6 verification work (PR #27).
**Updated:** 2026-07-29, after the release-readiness hardening pass (branch
`worktree-release-readiness`) — issues 1 and 3–10 are now resolved, issue 2
was already resolved, issues 11 and 12 remain open by design (see §5,
"Known limitations"), and three new issues (13–15) were found along the way.

This document is self-contained. You should not need to read the PR, the commit
history, or `docs/test-plan.md` to act on anything here — though
`docs/test-plan.md` holds the verification matrix if you want it, and
`docs/maestro-architecture.md` has the full design.

Read sections 1–3 once to build a mental model. Then go straight to whichever
issue you are tackling in section 5; each is written to stand alone.

---

## 1. Maestro in five minutes

Maestro is an embeddable durable workflow engine shipped as a Spring Boot
starter. It gives you Temporal-style workflow durability without a central
server, using infrastructure you already run: Postgres, Kafka, and
Valkey/Redis.

**The core trick is memoization against an event log.**

1. A workflow method runs on a Java virtual thread.
2. Calls to activities go through a proxy. Before executing, the proxy checks
   the store for a recorded result at the current **sequence number**.
3. If a result exists (*replay*), it is returned instantly — the activity does
   not run.
4. If not (*live*), the activity executes and its result is persisted.
5. After a crash, the engine simply calls the workflow method again. Completed
   steps replay instantly from the log; execution resumes at the first step
   that never completed.

Two consequences matter for almost every issue below:

- **The event log is the truth.** Anything that makes the log and the instance
  row disagree is a correctness bug, not a cosmetic one.
- **Workflow code must be deterministic between activity calls.** No
  `Math.random()`, no `Instant.now()`, no I/O. Otherwise replay takes a
  different path than the log records.

**Durable state lives in four Postgres tables:** `maestro_workflow_instance`
(one row per workflow, with a `status` and a `version` for optimistic locking),
`maestro_workflow_event` (the log, unique on `(workflow_instance_id,
sequence_number)`), `maestro_workflow_signal`, and `maestro_workflow_timer`.

**Workflow statuses:** `RUNNING`, `WAITING_SIGNAL`, `WAITING_TIMER`,
`COMPENSATING` are *active*; `COMPLETED`, `FAILED`, `TERMINATED` are *terminal*.
Only active workflows are recoverable.

**Three SPIs** decouple the engine from infrastructure:

| SPI | Job | Implementations |
|---|---|---|
| `WorkflowStore` | Durable state — instances, events, signals, timers | Postgres (via `maestro-store-jdbc`) |
| `WorkflowMessaging` | Task dispatch, signal transport, lifecycle events | Kafka, Postgres, RabbitMQ |
| `DistributedLock` | Instance locking, leader election | Valkey, Postgres |

**Locks are an optimisation, not the correctness backstop.** The SPI explicitly
says the engine proceeds unlocked if the lock backend is unavailable; the unique
event index and optimistic versioning are what actually keep state correct. Keep
this in mind — several issues below are only sensible in that light.

### Module map

```
maestro-core                  Engine. Pure Java, NEVER depends on Spring.
maestro-spring-boot-starter   Auto-configuration, annotations, MaestroClient.
maestro-store-jdbc            Abstract JDBC store.
maestro-store-postgres        Postgres store + Flyway migrations (version band 1-99).
maestro-messaging-kafka       Kafka transport.        [primary]
maestro-messaging-postgres    Postgres transport.     (band 200-299)
maestro-messaging-rabbitmq    RabbitMQ transport.
maestro-lock-valkey           Valkey lock.
maestro-lock-postgres         Postgres lock.          (band 100-199)
maestro-test                  In-memory SPIs, TestWorkflowEnvironment, DeterminismChecker.
maestro-integration-tests     Real-backend suites (not published).
maestro-admin / -admin-client Dashboard + event publisher.
maestro-samples/…             Demos, incl. the loan-origination E2E.
```

---

## 2. What state the codebase is in

Until recently **every engine test ran against in-memory fakes.** The engine had
never executed against real Postgres in CI. That gap shipped real bugs, so
P0–P6 built out real-backend testing. The result:

- `maestro-integration-tests` runs **65 tests** against Testcontainers Postgres
  and Kafka on every PR, via the normal `./gradlew build`.
- `maestro-lock-postgres` and `maestro-messaging-postgres` gained their first
  real suites (37 tests).
- The loan-origination E2E (6 scenarios, including `kill -9` recovery and a
  two-node scenario) runs nightly in CI.
- A module coverage gate fails the build if a `maestro-*` module with production
  code has no tests. As of this update the allowlist is **empty**:
  `maestro-messaging-rabbitmq`, `maestro-admin`, `maestro-admin-client`, and
  `maestro-store-jdbc` all gained real suites closing former Issue 10 (see §5).

**Six defects were found and fixed** in the process. They are listed here only
so you don't re-report them: a Flyway version collision that made the
Postgres-only profile unbootable; shutdown marking parked workflows `FAILED` and
compensating them; a version conflict recording a *successful* workflow as
`FAILED`; every nested `@ConfigurationProperties` block being silently inert;
cross-instance wake dropping notifications; and lifecycle publishing to a topic
the sample never created.

The point worth carrying forward: **all six were invisible to a fully green
in-memory build.** When judging whether something below is "really" a problem,
remember that the fakes have already been wrong about this codebase six times.

---

## 3. Working agreements

**Build and test:**

```bash
./gradlew build                              # everything, incl. integration tests (needs Docker)
./gradlew :maestro-core:test                 # fast unit tests
./gradlew :maestro-integration-tests:test    # real Postgres + Kafka
./gradlew :maestro-integration-tests:e2eTest # @Tag("e2e") only, not in `build`
cd maestro-samples/sample-loan-origination && ./e2e/run-e2e.sh   # full E2E, ~4 min
```

**Where tests belong.** Unit tests live in their module. Tests that need a real
backend go in `maestro-integration-tests` (see its `SPEC.md` for fixtures and
conventions) — *except* backend-specific suites, which live with their backend
module. Use `maestro-test`'s in-memory SPIs for fast unit tests only, never as
the subject of an integration assertion.

**Non-negotiables** (full list in `CLAUDE.md`):

- `maestro-core` must never import Spring.
- Jackson 3 (`tools.jackson`), never `com.fasterxml.jackson`. `jakarta.*`, never
  `javax.*`. No Lombok. JSpecify `@Nullable` on public APIs. Exceptions extend
  `MaestroException`. Javadoc and thread-safety notes on public classes.
- Kafka topics are never auto-created; they are pre-declared in configuration.
- Optimistic locking convention: the **caller** builds the new state with
  `version = current + 1`; the store CASes against `version - 1`.

**The library-bug protocol.** If a test exposes an engine defect, reproduce it
first as a failing test in the module that owns the defect, then fix that
module, then carry on. Never work around a proven engine bug inside a test.

**Verification standards.** These exist because they were learned the hard way:

- A test that passes on first write is suspect. Prove it can fail — break the
  expectation, watch it fail, restore it, confirm green again.
- Integration suites must pass 3 consecutive `--rerun-tasks` runs before you
  call them done.
- Use Awaitility with generous bounds. Never `Thread.sleep` as synchronisation.
- For E2E, verify *which process* served the run — PID in the log matching the
  run's own pid file, ports confirmed free beforehand. An HTTP 200 from a
  readiness probe proves something is listening, not that your build is.

---

## 4. Issue index

**All of issues 1–10 are now resolved** (see each section below for the
outcome, commit references, and pinning tests). Issues 11 and 12 remain open
by deliberate decision — see "Known limitations" at the end of §5. Three new
issues (13–15), found while doing this work, are open and unfixed.

**Read the "Kind" column first — it determines how you work.** Almost everything
here was a *library* problem, not a coverage problem. That was the outcome of the
verification work rather than a departure from it: closing test gaps converted
unknowns into two piles, things now proven to work and a defect backlog.

- **Library defect** — the shipped behaviour is wrong. Fixing it changes what
  users experience, so it needs a failing test first, a behaviour test after,
  and a line in the release notes if the change is observable.
- **Library gap** — behaviour is defensible but something is missing or
  unconfigurable. Usually an API or design decision, not a bug fix.
- **Testing gap** — the code may be fine; nothing verifies it either way. Expect
  these to *become* library defects once you look: `maestro-messaging-postgres`
  was a pure testing gap until its first suite found a signal-loss defect.

| # | Issue | Kind | Severity | Status |
|---|---|---|---|---|
| [1](#issue-1) | Failed signal handlers lose signals permanently | Library defect | High | **Resolved** |
| [2](#issue-2) | A timer can fire once and then stall the workflow forever | Library defect | High | **Resolved** |
| [3](#issue-3) | Lifecycle publishing can block workflow start for 60s | Library defect | Medium | **Resolved** |
| [4](#issue-4) | `ExecutorShutdownException` can be swallowed by user code | Library gap (API design) | Medium | **Resolved** |
| [5](#issue-5) | Shutdown during compensation leaves a workflow `COMPENSATING` | Library defect (semantics only) | Medium | **Resolved** |
| [6](#issue-6) | `maestro.admin.events.*` configuration does nothing | Library defect | Low | **Resolved** |
| [7](#issue-7) | Two hardcoded 30s timeouts with no configuration seam | Library gap | Low | **Resolved** |
| [8](#issue-8) | Health indicator is documented but does not exist | Library gap (or docs bug) | Low | **Resolved** |
| [9](#issue-9) | Activity lock prefix ignores `maestro.lock.key-prefix` | Library defect | Low | **Resolved** |
| [10](#issue-10) | Four modules have no tests at all | Testing gap | Medium | **Resolved** |
| [11](#issue-11) | Lost locks don't stop the workflow (no fencing) | Library gap — **known limitation** | Medium | Open, by design |
| [12](#issue-12) | Recovery polling doesn't scale | Library gap — **known limitation** | Low now | Open, by design |
| [13](#issue-13) | `CANCELLED` timers can strand a replaying workflow | Library defect | Medium | Open |
| [14](#issue-14) | `SagaManager` re-appends `COMPENSATION_STARTED` on replay | Library gap | Low | Open |
| [15](#issue-15) | Admin dashboard retry/terminate signals are unconsumed | Library gap | Medium | Open |

Issues 1–10 were each either observed directly through a written reproduction,
or pinned by a test that was `@Disabled` describing the desired behaviour.
Issue 2 in particular was traced through the code before being reproduced, and
the reproduction confirmed the reading was right.

## 5. The issues

### Issue 1 — Failed signal handlers lose signals permanently {#issue-1}

> **Resolved.** Bounded, exponential-backoff redelivery plus dead-lettering,
> applied uniformly across all three transports: Kafka (`.DLT` topic via
> `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`), Postgres
> (`DEAD_LETTER` status, `attempts`/`next_attempt_at`/`last_error` columns,
> `V201` migration, `listDeadLetterSignals`/`listDeadLetterTasks` +
> `replaySignal`/`replayTask` API), RabbitMQ (`RetryInterceptorBuilder` +
> self-declared `<queue>.dlq` bound to a `maestro.dead-letter` exchange). New
> `maestro.messaging.redelivery.*` configuration (10 attempts, 1s→30s
> exponential backoff by default). Both `@Disabled` specs are enabled and
> green. **Operators must pre-create the Kafka `.DLT` topic for every
> consumed topic before upgrading** — see `docs/release-notes.md`. Commits
> `50c0604`..`174e9b5` (Kafka + Postgres), `539aefc`..`669e97b` (RabbitMQ).
> Pinned by `KafkaAckOnFailureIT`, `PostgresWorkflowMessagingTest`,
> `RabbitMqWorkflowMessagingTest`. The rest of this section is kept as the
> record of the defect.

**What's wrong.** When a signal handler throws, every transport treats the
message as done. The signal is never retried and never lands anywhere you can
inspect it. This directly contradicts the engine's own rule, stated in the
architecture docs: *never discard a signal.*

Behaviour was measured, not assumed:

- **Engine signal channel:** acks after **one** attempt.
  `SignalSubscriptionRunner` (in `maestro-spring-boot-starter`) deliberately
  rethrows so the transport won't ack — there's a comment saying exactly that —
  but `KafkaWorkflowMessaging.subscribeSignals` catches and logs the exception,
  so the rethrow never reaches Kafka.
- **`@MaestroSignalListener` path:** retries **ten** times, then logs and skips.
  See `MaestroSignalListenerBeanPostProcessor`.
- **Postgres transport:** `PostgresWorkflowMessaging.processSignalMessage`
  marks the row `FAILED`, and the claim query only ever selects `PENDING` rows
  or stale `PROCESSING` ones. `FAILED` is terminal, so the signal is
  unreachable forever.

**Why it matters.** A signal is durable business state — an approval, a payment
result. A transient handler failure (a brief database blip during
`deliverSignal`) silently drops it, and the workflow waits for something that
will never arrive.

**Where.**
- `maestro-messaging-kafka/src/main/java/.../KafkaWorkflowMessaging.java` —
  `subscribeSignals`, the `catch (Exception e)` around the handler call
- `maestro-messaging-kafka/src/main/java/.../listener/MaestroSignalListenerBeanPostProcessor.java`
- `maestro-messaging-postgres/src/main/java/.../PostgresWorkflowMessaging.java` —
  `processSignalMessage` / `processTaskMessage`, and the claim SQL above them
- `maestro-messaging-rabbitmq` has the same shape and no tests at all

**Executable specs already exist**, currently `@Disabled`:
- `maestro-integration-tests/src/test/java/.../kafka/KafkaAckOnFailureIT.java`
- `maestro-messaging-postgres/src/test/java/.../PostgresWorkflowMessagingTest.java`
  (`failedHandlerMustNotLoseTheSignal`)

Enable them to see the current behaviour fail the desired contract.

**Why it wasn't just fixed.** Removing the Kafka `catch` moves the channel from
1 attempt to 10 and still drops the signal at the end. Real "not lost" needs a
dead-letter destination, which means: new configuration properties, a decision
about topic creation (Maestro never auto-creates topics, so a DLT must be
pre-declared and documented), and a matching design in RabbitMQ and Postgres.
That is an owner's design decision, not a test-phase fix.

**What a fix looks like.** Pick one policy and apply it across all three
transports:
1. Bounded retries with backoff, then publish to a dead-letter destination
   (`maestro.messaging.topics.dead-letter`, pre-created like every other topic).
   For Postgres, a `DEAD_LETTER` status plus a way to list and replay those rows.
2. Alternatively, make the *store* the retry mechanism: persist the signal
   before invoking the handler so delivery failure can't lose it, and let the
   existing recovery path re-deliver.

Option 2 is more in keeping with the engine's "Postgres is truth" design and
avoids poison-message loops entirely. Whichever you choose, remove the
`@Disabled` annotations and make those specs pass.

**Done when.** A handler that throws does not lose the signal on Kafka,
Postgres, and RabbitMQ; a permanently failing message ends up somewhere
inspectable rather than looping forever; the two disabled specs are enabled and
green.

---

### Issue 2 — A timer can fire once and then stall the workflow forever {#issue-2}

> **Resolved.** The reproduction below was written first and it failed exactly as
> predicted: the recovered workflow sat in `WAITING_TIMER` forever. Fixed by
> option 1 (self-healing replay). Pinned by
> `engine.EnginePostgresTimerIT.timerFiredBeforeEventAppend_recoveryCompletesTheWorkflow`
> and `core.engine.WorkflowExecutorTest.recoverWorkflowsHealsTimerFiredBeforeEventAppend`.
> The rest of this section is kept as the record of the defect.

**What's wrong.** Firing a timer is two writes that aren't atomic, and a crash
between them strands the workflow permanently.

The sequence:

1. `WorkflowExecutor.fireTimer` calls `store.markTimerFired(timerDbId)`. The
   timer row moves `PENDING → FIRED`.
2. It then unparks the workflow thread.
3. The **workflow thread** appends the `TIMER_FIRED` event to the log — see
   `DefaultWorkflowOperations.sleep`, around the `TIMER_SCHEDULED` /
   `TIMER_FIRED` handling.

If the process dies between 1 and 3, replay finds `TIMER_SCHEDULED` with no
`TIMER_FIRED`, concludes the timer is still pending, and re-parks. But the timer
row is already `FIRED`, and `getDueTimers` only returns `PENDING` rows — so no
poller will ever fire it again.

**Confidence.** This is traced through the code, not yet reproduced. Nothing
tests it either way. Treat the reproduction below as the first task, and be
willing to close this issue if the workflow turns out to recover.

**Why it matters.** If the reading is right, the workflow waits forever. There is
no error, no failed status, nothing in a dashboard: it simply never progresses.
The window is exactly the `kill -9` scenario durability is meant to survive.

**Where.**
- `maestro-core/src/main/java/.../engine/WorkflowExecutor.java` — `fireTimer`
- `maestro-core/src/main/java/.../engine/DefaultWorkflowOperations.java` —
  `sleep`, the replay branch that re-parks when `TIMER_FIRED` is absent
- `getDueTimers` in the store SPI and `AbstractJdbcWorkflowStore`

**How to reproduce.** Write an integration test in `maestro-integration-tests`
(`engine/` package) that: starts a workflow that sleeps; waits for the timer row
to exist; calls `store.markTimerFired(...)` directly *without* unparking, to
simulate the crash window; then builds a second `MaestroEngineHarness` over the
same store and runs `recover()`. The workflow should complete; today it parks
forever. `MaestroEngineHarness` and `TestWorkflows.SleepingWorkflow` already
exist for this.

**What a fix looks like.** Options, roughly in order of preference:
1. **Make replay self-healing.** When replay finds `TIMER_SCHEDULED` with no
   `TIMER_FIRED`, check the timer row: if it is already `FIRED`, treat the timer
   as elapsed and continue rather than re-parking. Small, local, no schema
   change.
2. **Reverse the order** — append `TIMER_FIRED` before marking the row fired.
   Moves the window rather than closing it (you can then double-fire, which the
   event uniqueness constraint absorbs).
3. **Make the poller reclaim** `FIRED` timers whose workflow is still
   `WAITING_TIMER` after some grace period.

Option 1 is the cleanest and is testable entirely at the engine level.

**What was done.** Option 1. Replay could not previously ask the question,
because `getDueTimers` returns only `PENDING` rows — a fired timer was invisible
to the engine. `WorkflowStore` gained `findTimer(workflowInstanceId, timerId)`,
implemented by `AbstractJdbcWorkflowStore` and the in-memory test store, and
`DefaultWorkflowOperations.sleep` now consults it before re-parking: a `FIRED`
row means the wake already happened and was lost, so it appends the missing
`TIMER_FIRED` event and continues. A `PENDING` row still parks, so a live sleep
is unaffected. No schema change.

**Done when.** The reproduction above passes, and a `kill -9` in that window
leaves a workflow that recovery can complete. — Done.

---

### Issue 3 — Lifecycle publishing can block workflow start for 60s {#issue-3}

> **Resolved.** Lifecycle publishing moved off the workflow thread onto a
> new `LifecycleEventPublisher` (`maestro-core`, pure `java.util.concurrent`):
> a single-worker, bounded (`ArrayBlockingQueue(1000)`) executor whose
> `submit` never blocks the caller and drops under backpressure (rate-limited
> WARN). `startWorkflow` now returns immediately even against a producer
> blocked on missing topic metadata. Commit `04f6cf2`. Pinned by
> `LifecycleEventPublisherTest`, `WorkflowExecutorLifecycleEventPublishingTest`,
> and `KafkaLifecycleEventLatencyIT` (a real broker with a missing,
> auto-create-disabled topic, asserting `startWorkflow` returns in <1s).

**What's wrong.** `WorkflowExecutor.publishLifecycleEvent` wraps its call in
`try/catch`, so a *failure* can't break workflow execution. But it does nothing
about *latency*. `KafkaTemplate.send` blocks while fetching topic metadata, and
for a topic that doesn't exist it blocks for the producer's `max.block.ms` —
**60 seconds by default** — before throwing. That happens inline inside
`startWorkflow`.

**Why it matters.** An observability concern gates the engine's hot path. If the
admin topic is missing or the broker is briefly unreachable, every workflow
start takes a minute. This was observed: it timed out all six loan E2E scenarios
at 150s each.

**Where.** `maestro-core/src/main/java/.../engine/WorkflowExecutor.java` —
`publishLifecycleEvent` (around line 905), called from `startWorkflow` and from
the terminal transitions. The blocking happens inside
`maestro-messaging-kafka`'s `KafkaWorkflowMessaging.publishLifecycleEvent`.

**Note.** Only the *sample* was fixed (its compose now pre-creates
`maestro.admin.events`). The library behaviour is unchanged.

**What a fix looks like.** Either bound the wait — set a low `max.block.ms` on
the admin producer specifically, so a missing topic costs milliseconds — or move
lifecycle publishing off the workflow thread onto a small bounded executor that
drops events under backpressure. The SPI's contract already says lifecycle
failures must not interrupt execution; make that true for latency too.

**Done when.** Starting a workflow with a missing or unreachable admin topic
costs no meaningful time on the workflow thread, proven by a test that points
the producer at a non-existent topic and asserts `startWorkflow` returns
promptly.

---

### Issue 4 — `ExecutorShutdownException` can be swallowed by user code {#issue-4}

> **Resolved.** `ExecutorShutdownException` now extends `Error` instead of
> `MaestroException` — the deliberate, documented exception to the "every
> exception extends `MaestroException`" convention (see `CLAUDE.md` §
> Coding Standards). Every reflection/`CompletableFuture` unwrap site in
> `maestro-core` was audited and fixed to check `instanceof Error` before
> `instanceof Exception`, so a `catch (Exception e)` around `awaitSignal()`/
> `sleep()` can no longer intercept it. **This is a breaking change** for any
> code that caught `ExecutorShutdownException` expecting a `MaestroException`
> — see `docs/release-notes.md`. Commit `f1ce4b6`. Pinned by
> `WorkflowExecutorShutdownTest.shutdown_withCatchExceptionAroundAwaitSignal_stillLeavesItWaitingSignalAndRecoverable`.

**Background.** Graceful shutdown used to mark parked workflows `FAILED` and run
their saga compensations — stopping a node could refund a customer whose order
was merely awaiting approval. That's fixed: parking now throws
`ExecutorShutdownException`, which `WorkflowExecutor` catches *before* its
generic failure handler and treats as "this node is stopping", leaving the
instance in `WAITING_*` and recoverable.

**What's wrong.** `ExecutorShutdownException` extends `MaestroException` extends
`RuntimeException`. A workflow author who writes a broad `try/catch` around
`awaitSignal` or `sleep` — a completely ordinary thing to do — swallows it and
silently reinstates the old behaviour: their workflow is recorded `FAILED` and
compensated during a routine deploy.

**Why it matters.** The engine's correctness now depends on user code not doing
something ordinary. Temporal solves this by using an `Error` for the equivalent
signal, precisely because `catch (Exception)` doesn't catch it.

**Where.** `maestro-core/src/main/java/.../exception/ExecutorShutdownException.java`
(the hazard is documented in its Javadoc), and the catch ordering in
`WorkflowExecutor.executeWorkflow`.

**What a fix looks like.** Make it extend `Error` (it isn't part of the
`MaestroException` hierarchy conceptually — it's a control-flow signal, not a
workflow error). This conflicts with the repo convention that all exceptions
extend `MaestroException`, so it needs a deliberate decision and a note in
`CLAUDE.md`. The alternative is to keep it as is and add a determinism/lint-style
check that flags broad catches around `awaitSignal`/`sleep` in workflow classes.

**Do this soon if you're going to do it.** It gets more expensive once
workflows are written against the current type.

**Done when.** A workflow with `try { workflow.awaitSignal(...) } catch
(Exception e) { ... }` still survives shutdown as `WAITING_SIGNAL`, with a test
proving it.

---

### Issue 5 — Shutdown during compensation leaves a workflow `COMPENSATING` {#issue-5}

> **Resolved.** `SagaManager.executeParallel`'s branch outcome-collection now
> checks for `ExecutorShutdownException` and rethrows it before recording any
> step as failed (the sequential path already couldn't catch an `Error`, once
> Issue 4 landed). `WorkflowExecutor.executeWorkflow` also nests a
> `catch (ExecutorShutdownException)` around the call into
> `handleWorkflowFailure`, so a shutdown raised while a compensation action
> itself parks is handled exactly like a shutdown mid-park: nothing is
> written, and the instance is left `COMPENSATING` (active, recoverable) for
> the next node. Commit `f1ce4b6` (bundled with Issue 4). Pinned by
> `WorkflowExecutorShutdownTest.shutdown_duringCompensation_leavesItRecoverableAndCompletesOnRecovery`
> and the equivalent `ShutdownContractIT` test against real Postgres.
> **Known gap noted along the way, not fixed here:** on recovery,
> `SagaManager.compensate()` unconditionally re-appends
> `COMPENSATION_STARTED`/`COMPENSATION_COMPLETED`, relying on
> `DuplicateEventException` to silently swallow the replay — see new
> [Issue 14](#issue-14).

**What's wrong.** `SagaManager`'s compensation loop catches `Exception` broadly.
If the executor shuts down while compensations are running, an activity throws
`ExecutorShutdownException`, and the saga treats it as a *compensation failure*
rather than as "this node is stopping".

**Why it matters.** Nothing corrupts — `COMPENSATING` is non-terminal, so the
workflow stays recoverable and the instance lock is released by
`executeWorkflow`'s `finally`. But the intended semantics are that shutdown
never influences compensation outcomes, and today a deploy can be recorded as a
compensation failure.

**Where.** `maestro-core/src/main/java/.../saga/SagaManager.java` — the
`catch (Exception e)` inside the sequential compensation loop (and the parallel
equivalent).

**What a fix looks like.** Rethrow `ExecutorShutdownException` immediately
instead of recording it as a failed compensation step, so it propagates to
`WorkflowExecutor`'s shutdown handling. Consider what should happen to the
compensations that already ran — they are memoized, so a recovering node will
replay them and continue from the right place.

**Done when.** A test shuts down an executor mid-compensation and asserts the
workflow is left recoverable with no compensation recorded as failed.

---

### Issue 6 — `maestro.admin.events.*` configuration does nothing {#issue-6}

> **Resolved.** `maestro.admin.events.enabled` now genuinely gates
> `WorkflowExecutor.publishLifecycleEvent` (threaded through a new
> constructor parameter and wired by `MaestroAutoConfiguration`).
> `maestro.admin.events.topic` is kept as a **deprecated alias** for
> `maestro.messaging.topics.admin-events`: only one set → that value used;
> both set to different values → the messaging property wins and a WARN
> names both. Commit `6eddfc4` (engine wiring), `88b5ade` (topic alias).
> Pinned by `MaestroAutoConfigurationLifecycleEventsTest` and
> `KafkaMessagingAutoConfigurationAdminTopicAliasTest`.

**What's wrong.** `maestro.admin.events.enabled` and `maestro.admin.events.topic`
are bound into `MaestroProperties` and then read by nothing. Grep for
`getAdmin()` — the only hit is the getter itself. The topic that actually works
is `maestro.messaging.topics.admin-events`, and lifecycle publishing cannot be
disabled at all.

**Why it matters.** Users set these and get silence. Both `sample-order-service`
and the loan sample set `maestro.admin.events.enabled: false` expecting
publishing to stop; it doesn't. That misconfiguration is what made Issue 3 bite
in the E2E.

**Where.** `maestro-spring-boot-starter/src/main/java/.../config/MaestroProperties.java`
(`AdminProperties` / `EventsProperties`), and the samples' `application.yml`.

**What a fix looks like.** Either wire it — have `WorkflowExecutor` skip
`publishLifecycleEvent` when disabled, and treat `admin.events.topic` as an
alias for the messaging topic with a deprecation note — or delete the block and
update the samples. Wiring it is more useful: "turn off dashboard events" is a
reasonable thing to want, and it sidesteps Issue 3 for anyone who doesn't run a
dashboard.

**Done when.** Setting `maestro.admin.events.enabled=false` demonstrably stops
lifecycle publishing, pinned by a test; or the property is gone and the samples
no longer reference it.

---

### Issue 7 — Two hardcoded 30s timeouts with no configuration seam {#issue-7}

> **Resolved.** Both threaded through `MaestroProperties` and the
> `WorkflowExecutor`/`SignalManager` constructors exactly as proposed:
> `maestro.shutdown.timeout` (default 30s, unchanged) and
> `maestro.signal.wake-recheck-interval` (default 30s, unchanged). Commits
> `ea9eb34` (core), `dd7c4f8` (starter wiring). Pinned by
> `MaestroAutoConfigurationConfigSeamsTest` (context-runner tests proving the
> configured values reach the engine) and
> `WorkflowExecutorTest.wakeRecheckIntervalReachesSignalManager`. See
> `docs/configuration.md` § Shutdown and Signal Configuration.

**What's wrong.** Both are `private static final Duration ... = Duration.ofSeconds(30)`:

- `WorkflowExecutor.SHUTDOWN_TIMEOUT` — how long shutdown waits for in-flight
  workflows to drain. Not reachable from any property.
- `SignalManager.DEFAULT_WAKE_RECHECK_INTERVAL` — how often a parked workflow
  re-reads the store for signals. There *is* a package-private constructor
  taking a custom interval, but `WorkflowExecutor` always uses the default, so
  nothing outside the package can change it.

**Why it matters.** The second is an operational knob that can't be turned: it
bounds cross-node signal latency for anyone running Kafka without a
`SignalNotifier` (that is, without Valkey). Those deployments can wait up to 30
seconds for a signal with no way to tune it. It also makes that code path
awkward to test without waiting 30 real seconds.

**Where.** `maestro-core/src/main/java/.../engine/WorkflowExecutor.java` (~line
76) and `.../engine/SignalManager.java` (~line 75).

**What a fix looks like.** Thread both through `MaestroProperties` —
`maestro.shutdown.timeout` and `maestro.signal.wake-recheck-interval` — and pass
them into the `WorkflowExecutor` constructor, which already takes explicit lock
configuration. Note that as of the config-binding fix, properties actually bind,
so a new property will take effect.

**Done when.** Both are configurable, defaults unchanged, with context-runner
tests pinning that the configured values reach the engine.

---

### Issue 8 — Health indicator is documented but does not exist {#issue-8}

> **Resolved.** `io.b2mash.maestro.spring.health.MaestroHealthIndicator` now
> exists, auto-configured when Spring Boot Actuator is on the classpath. It
> reports `DOWN` when the store is unreachable (bounded 2s probe on a virtual
> thread, so a hung store can't hang `/actuator/health`) or when a poller
> that has actually started later dies; a poller that hasn't started yet
> (the `StartupRecoveryRunner` boot window) reports `"starting"`, not `DOWN`,
> so rolling deploys don't flap a readiness probe; a poller disabled by
> configuration (`maestro.recovery.enabled=false`) reports `"disabled"`, not
> `false`. Details include store reachability, both poller states, and the
> running workflow count. Commits `ebcab68`, `d8d1c98`, `0d551a5`. Pinned by
> `MaestroHealthAutoConfigurationTest` and `MaestroHealthIndicatorTest`.

**What's wrong.** `CLAUDE.md` lists `io.b2mash.maestro.spring.health` /
`MaestroHealthIndicator` in the package layout. There is no such package and no
`*Health*` class anywhere in main source.

**Why it matters.** Documentation promises a feature that isn't there. Anyone
wiring Maestro into a readiness probe will look for it.

**What a fix looks like.** Either implement a Spring Boot `HealthIndicator`
reporting store reachability, whether the recovery and timer pollers are
running, and the count of locally running workflows — or delete the line from
`CLAUDE.md`. Implementing it is worth doing; the engine already exposes
`runningCount()` and the pollers know their own state.

**Done when.** `/actuator/health` reports Maestro's state, or the docs no longer
claim it does.

---

### Issue 9 — Activity lock prefix ignores configuration {#issue-9}

> **Resolved.** `ActivityInvocationHandler` and `ActivityProxyFactory` gained
> a `lockKeyPrefix` parameter (defaulting to
> `WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX`, the instance lock's own
> default, so behaviour is unchanged unless configured);
> `ActivityStubBeanPostProcessor` now resolves `maestro.lock.key-prefix` and
> passes it through, so `@ActivityStub`-injected proxies honour the same
> prefix as the instance lock. Commit `ea9eb34`. Pinned by
> `ActivityInvocationHandlerTest.lockHonoursCustomKeyPrefix` (asserts the
> exact acquired key) and
> `MaestroAutoConfigurationConfigSeamsTest.lockKeyPrefixReachesActivityInvocationHandler`.

**What's wrong.** `ActivityInvocationHandler` builds its lock key with a
hardcoded literal:

```java
var lockKey = "maestro:lock:activity:%s:%d".formatted(ctx.workflowId(), seq);
```

The instance lock honours `maestro.lock.key-prefix`; this one doesn't.

**Why it matters.** Two Maestro deployments sharing one Valkey and relying on
distinct prefixes to stay isolated will collide on activity locks. It's a
best-effort dedup lock, so the blast radius is limited — but the isolation the
prefix promises isn't real.

**Where.** `maestro-core/src/main/java/.../engine/ActivityInvocationHandler.java`,
around line 404.

**What a fix looks like.** Pass the configured prefix down the same way the
instance lock manager receives it, and use it here.

**Done when.** A configured `maestro.lock.key-prefix` appears in activity lock
keys, pinned by a test asserting the exact key.

---

### Issue 10 — Four modules have no tests at all {#issue-10}

> **Resolved.** All four gained real suites; `modulesWithoutTests` in the
> root `build.gradle.kts` is now empty. `maestro-messaging-rabbitmq`
> (RabbitMQ) carried the same Issue 1 defect as Kafka and Postgres — fixed
> alongside its first suite. Writing suites for `maestro-admin` also found
> two Spring Boot 4 modular-autoconfiguration gaps that meant the app
> **could not have booted in production**: it depended on bare `spring-kafka`
> and `flyway-core` instead of `spring-boot-starter-kafka` /
> `spring-boot-starter-flyway`, so no `KafkaTemplate`/`ConsumerFactory` beans
> were ever created and the `admin_*` schema was never migrated — both
> fixed. `maestro-admin-client`'s `AdminEventPublisher` also silently
> discarded async Kafka send failures (its own Javadoc promised they'd be
> logged); now logged at WARN via `.whenComplete(...)`. Commits `539aefc`,
> `a1d9aff`, `669e97b` (RabbitMQ, Task 7); `76a44ad`, `2c7fd72`, `6b32426`
> (admin-client, admin, store-jdbc, Task 8). Note: the admin dashboard's
> retry/terminate buttons remain non-functional end-to-end — that was always
> a separate, pre-existing gap (no engine-side command consumer), not
> something these suites were expected to fix; tracked as new
> [Issue 15](#issue-15).

**What's wrong.** `maestro-admin`, `maestro-admin-client`,
`maestro-messaging-rabbitmq`, and `maestro-store-jdbc` have production code and
zero test classes. They're held in an explicit allowlist in the root
`build.gradle.kts` so the coverage gate passes on today's baseline while still
failing for any *new* untested module.

**Why it matters.** `maestro-messaging-postgres` was in exactly this state and
turned out to be shipping a real signal-loss defect. RabbitMQ is the same code
shape as Kafka and Postgres, so it almost certainly carries Issue 1 too.

**Priority order.** RabbitMQ first (it ships and has a known-shape defect), then
`admin-client` (small and easy), then `admin`. `maestro-store-jdbc` is exercised
indirectly through `maestro-store-postgres` and is the least urgent.

**How to approach it.** Mirror the existing suites rather than inventing a
pattern: `PostgresWorkflowMessagingTest` for a transport,
`PostgresDistributedLockContractTest` for an SPI contract. Both use a
Testcontainers singleton started from a static initialiser — read the comments
there before writing a fixture, they encode a real pitfall.

**Expect this to produce library defects.** This is the only pure testing gap in
the list, and it probably won't stay that way — the last module in this state
(`maestro-messaging-postgres`) had a signal-loss defect waiting in it. Budget for
fixing what you find, not just for writing tests.

**Done when.** Each module is removed from the `modulesWithoutTests` allowlist
with a real suite behind it, and anything the suites uncover is either fixed or
added to this document.

---

### Known limitations (open by design)

Issues 11 and 12 were deliberately left open rather than fixed in this
release. Both are accepted trade-offs, not oversights: closing either is a
real design/SPI change, not a quick patch, and neither blocks a release —
they're documented so operators know the boundaries.

### Issue 11 — Lost locks don't stop the workflow (no fencing) {#issue-11}

**What's wrong.** `LockHandle` carries a fencing token, and the SPI documents
that downstream operations "should" validate it — but nothing does. If a node
loses its instance lock (a GC pause longer than the TTL, say), the lock renewer
logs an error and drops the handle, and the workflow keeps running.

**Why it matters.** Two nodes can then execute the same workflow. Duplicate
*persisted results* are still prevented by the unique event index — the loser's
writes fail and it adopts the winner's results. Duplicate *side effects* are
not: both nodes can call a payment API.

This is currently **accepted by design** (the architecture doc says split-brain
is tolerated until fencing lands, and activities must be idempotent). Treat it
as a known limitation to close deliberately, not a bug report.

**What a fix looks like.** Validate the fencing token in the store on writes:
carry it on the instance row, and reject a write from a stale token. That's a
`WorkflowStore` SPI change and touches every implementation, hence the size.

**Done when.** A node that has lost its lock cannot persist workflow progress,
with a multi-node test proving the stale node is fenced out.

---

### Issue 12 — Recovery polling doesn't scale {#issue-12}

**What's wrong.** `getRecoverableInstances()` has no service or staleness
filter, so every node re-reads the entire active workflow set on every poll and
probes the lock for each instance owned by someone else. Related: lock renewal
is serial — one round-trip per held lock every TTL/3 — and wake subscriptions
churn per await rather than being scoped to the workflow's local lifetime.

**Why it matters.** Fine at small scale, quadratic-ish as nodes and parked
workflows grow. A node holding thousands of parked workflows makes thousands of
renewal round-trips per interval.

**What a fix looks like.** Add a `service_name` and/or `updated_at` filter plus
an index to the recovery query (an SPI change); batch lock renewal (SQL `IN`, or
a Valkey pipeline); scope wake subscriptions to the workflow's lifetime.

**Done when.** There are numbers. This one needs a benchmark before a fix —
don't optimise it blind.

---

### Issue 13 — `CANCELLED` timers can strand a replaying workflow {#issue-13}

**What's wrong.** This is the same stall shape as the original Issue 2, with
a different trigger. If a timer is cancelled via `TimerManager.cancelTimer`
(the admin dashboard's cancel-timer path) while a workflow is parked on it,
nothing unparks the workflow thread. On replay, `DefaultWorkflowOperations.sleep`
finds `TIMER_SCHEDULED` with no `TIMER_FIRED`, consults the timer row (the
Issue 2 fix), sees `CANCELLED` rather than `FIRED`, and — because the healing
logic only recognizes `FIRED` as "the wake already happened" — falls through
to re-parking. The workflow waits on a timer that will never fire and was
never meant to.

**Why it matters.** Same as Issue 2: no error, no failed status, the workflow
simply never progresses. The trigger is narrower (an operator or admin action
explicitly cancelling a live timer), but the failure mode is identical.

**Where.** `maestro-core/src/main/java/.../engine/DefaultWorkflowOperations.java`
— `sleep`, the replay branch that consults `findTimer`;
`maestro-core/src/main/java/.../engine/TimerManager.java` — `cancelTimer`.

**Confidence.** Found by inspection while implementing the Issue 2 fix, not
yet reproduced with a test. It is also an open design question, not just a
bug: what *should* cancelling a timer under a workflow currently parked on it
do? (Options: unpark with a distinguished "cancelled" outcome the workflow
code can observe, so `sleep()`/`awaitSignal(..., timeout)` can raise a
catchable signal; or treat cancellation as only valid for timers nothing is
currently waiting on, and reject/no-op otherwise.) The fix should settle that
question before writing the healing branch, not just extend the `FIRED` case
to also cover `CANCELLED`.

**Done when.** A workflow parked on a timer that gets cancelled through
`TimerManager.cancelTimer` recovers to a defined, tested outcome — not a
silent, permanent stall — with the semantics of "cancel a timer someone is
waiting on" decided and documented.

---

### Issue 14 — `SagaManager` re-appends `COMPENSATION_STARTED` on replay {#issue-14}

**What's wrong.** `SagaManager.compensate()` has no replay-skip guard of its
own. On a recovery re-run it unconditionally re-appends
`COMPENSATION_STARTED`/`COMPENSATION_COMPLETED`, relying on the event log's
unique `(workflow_instance_id, sequence_number)` index to reject the
duplicate — which it does, via `DuplicateEventException`, caught and
logged/ignored by `appendEvent`'s caller. Nothing is lost or corrupted: the
first run's events already exist at those sequence numbers, and the
duplicate append is a no-op in effect.

**Why it matters.** It's currently harmless — swallowed silently, no
observable defect — but it's exactly the shape of "silently wrong until
someone changes something nearby" that this whole release-readiness pass
exists to close. It was noticed while proving Issues 4/5's shutdown-mid-
compensation fix, not while looking for it. A manually-registered
compensation action (`wf.addCompensation(Runnable)`, as opposed to a
`@Compensate`-annotated activity) is not memoized against re-execution the
same way an activity result is — this only matters if a compensation
actually *completes* before an interruption and then re-runs on replay, a
case none of the current shutdown tests exercise (the interrupted
compensation is always first in LIFO order in those fixtures).

**Where.** `maestro-core/src/main/java/.../saga/SagaManager.java` —
`compensate()` and the sequential/parallel compensation loops.

**What a fix looks like.** Give `compensate()` the same replay-skip check
every other event-emitting path in the engine has: before appending
`COMPENSATION_STARTED`/`COMPENSATION_COMPLETED`, check whether an event
already exists at that sequence number and skip re-executing the
already-completed compensation action, not just re-appending its event.

**Done when.** A test drives a workflow through a partially-completed
compensation, interrupts it, and asserts recovery does not re-invoke a
compensation action whose result is already durable — mirroring how a
regular activity is proven not to re-execute on replay.

---

### Issue 15 — Admin dashboard retry/terminate signals are unconsumed {#issue-15}

**What's wrong.** The dashboard's Retry and Terminate actions
(`POST /admin/workflows/{id}/retry`, `.../terminate`) publish
`$maestro:retry` / `$maestro:terminate` signals to the target service's
signal topic and report success once the publish succeeds. Nothing in the
engine or the Spring Boot starter consumes those two internal signal names —
there is no engine-side command dispatcher — so the buttons currently do
nothing end-to-end. "Send Signal" is unaffected: an application-level signal
is delivered and consumed like any other, since `awaitSignal(...)` doesn't
care who published it.

**Why it matters.** This is a pre-existing gap (already noted in
`docs/test-plan.md` before this release-readiness pass), surfaced again while
writing `maestro-admin`'s first real test suite (Issue 10): the new
`DashboardSmokeMockMvcTest` proves the controller layer degrades gracefully
(redirect + flash message) but does not — and per that task's scope,
should not — implement the missing consumer side. An operator clicking
Retry or Terminate today gets a success flash message for an action that had
no effect, which is worse than an error.

**Where.** `maestro-admin/.../AdminCommandService` (the publisher side,
correct as far as it goes); there is no consumer anywhere in
`maestro-spring-boot-starter` or `maestro-core` for `$maestro:retry`/
`$maestro:terminate`.

**What a fix looks like.** Add an engine-side listener (likely alongside
`@MaestroSignalListener`'s existing signal-routing machinery) that recognizes
the `$maestro:` prefix and dispatches to the appropriate `WorkflowExecutor`
action — re-driving a `FAILED` workflow's retry path for `$maestro:retry`,
terminating for `$maestro:terminate` — instead of treating it as an
application-level signal `awaitSignal()` might be waiting on.

**Done when.** Clicking Retry or Terminate in the dashboard against a real
running service measurably changes the target workflow's state, proven by an
end-to-end test, not just a controller-layer smoke test.

---

## 6. Suggested order

**Historical note:** the order below was the plan followed by the
release-readiness pass that closed issues 1–10 (see the "Resolved" callouts
in each section for what actually shipped, and `docs/release-notes.md` for
the release-level summary). It's kept here because the reasoning still
applies to what's left.

**First:** Issues 2 and 3. Both are small, both are silent failures in the
engine's hot path, and neither needs a design decision. Issue 4 belongs here too
if you're going to change the exception type at all — it only gets more
expensive.

**Then:** Issue 1. It's the most serious defect open, but it needs a policy
decision (dead-letter destination and its configuration) before code. Settle
that first, then apply it across Kafka, Postgres, and RabbitMQ together — and
do Issue 10's RabbitMQ suite as part of it, since you'll be touching that code.

**Then:** the small cleanups — 5, 6, 7, 8, 9 — which are each an hour or two
and remove documented-but-false behaviour.

**Later, deliberately:** Issues 11 and 12, both of which are architectural and
want measurements or an SPI change rather than a quick patch — still open,
still deliberate.

**What's left, for whoever picks this up next:** Issues 13, 14, and 15 — none
were on the original plan; all three were found as a side effect of fixing
something else nearby. 13 is the most urgent (same failure shape as the
original Issue 2); 14 is currently harmless but cheap to close properly; 15
needs a small design decision (a `$maestro:` command dispatcher) before code.

---

## 7. One thing that was never explained

The loan E2E failed all six scenarios because `maestro.admin.events` didn't
exist and the producer blocked for 60s per workflow start (Issue 3). The topic
was pre-created and everything passed.

What was never established is **why it started failing when it did.** The same
topic was equally absent earlier the same day, when the suite passed 5/5. The
blocking mechanism is understood; the trigger isn't. Candidate explanations were
checked and eliminated (the default topic name is identical before and after the
config-binding fix; the Kafka auto-configuration condition reads the raw
environment, not the bound properties).

It is written down rather than tidied into a story. If lifecycle publishing
starts misbehaving again, start here — and be suspicious of any explanation that
doesn't account for the earlier passing run.
