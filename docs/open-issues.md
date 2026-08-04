# Maestro — Open Issues and How to Tackle Them

**Audience:** an engineer or agent picking up work on Maestro with no prior
context on how these issues were found.
**Status date:** 2026-07-28, after the P0–P6 verification work (PR #27).
**Updated:** 2026-07-29, after the release-readiness hardening pass (branch
`worktree-release-readiness`) — issues 1 and 3–10 are now resolved, issue 2
was already resolved, issues 11 and 12 remain open by design (see §5,
"Known limitations"), and three new issues (13–15) were found along the way.
**Updated again:** 2026-07-30 — issues 13, 14, and 15 are now resolved too
(branch `worktree-issues-13-15`); see each section for commits and pinning
tests.

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
| `WorkflowMessaging` | Task dispatch, signal transport, lifecycle events | Kafka, Postgres |
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
./gradlew :maestro-integration-tests:e2eTest # @Tag("e2e") only, not in `build` — the chaos/soak harness
cd maestro-samples/sample-loan-origination && ./e2e/run-e2e.sh   # full E2E, ~9 min, 10 scenarios
```

**`e2eTest` now runs the multi-instance chaos/soak harness** (added by the
2026-08-01 multi-instance verification cycle;
`maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/
e2e/chaos/`). It Testcontainers-orchestrates a real six-node loan-origination
cluster (2 instances each of loan-application, verification-gateway,
underwriting) over real Postgres + Kafka, drives a seeded workload, injects
scripted chaos (pause/resume, partition, backend outages, rolling restarts),
and asserts store-level invariants (terminal-state correctness, event-log
integrity, no missed signals, no wrongly-tolerated duplicates) plus the
Issue 11/12 evidence (duplicate side effects, recovery/lock-renewal rates).
Needs Docker; `e2eTest` `dependsOn` the three sample services' `bootJar`
tasks (jar paths passed as system properties), so it is never wired into
`build`/`check`. Two modes, selected by system property:

```bash
# PR-gate mode (default): ~10-minute chaos window, runs on every e2eTest invocation
./gradlew :maestro-integration-tests:e2eTest --rerun-tasks

# Soak mode: multi-hour window + the vs-node-count benchmark tail
./gradlew :maestro-integration-tests:e2eTest --rerun-tasks \
    -Dmaestro.chaos.soak=true -Dmaestro.chaos.durationMinutes=120
```

CI runs PR-gate mode nightly (3× consecutive, `.github/workflows/
e2e-nightly.yml` job `chaos-pr-gate`) and soak mode weekly plus on-demand
(`chaos-soak`). See `docs/operations.md` for what the harness measures and
how to read its evidence, and `.superpowers/sdd/multi-instance/
chaos-harness-design.md` for the full design. The separate
loan-origination E2E script above (`sample-loan-origination/e2e/run-e2e.sh`,
10 scenarios including multi-node owner-kill adoption, rolling restart,
timer-leader failover, and cross-node admin commands on both lock backends)
still runs nightly and on demand in the same CI file, independent of
`e2eTest`.

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
issues (13–15), found while doing this work, were opened along the way and
are **now resolved too** (see each section below). A fourth (16), found
during the final whole-branch review of 13–15, is **guarded off, not
resolved** — see its section for why. A fifth (17), found on day one of the
multi-instance verification cycle (running every service at two instances),
is **now resolved** — see its section. A sixth (18), found by the chaos
harness's first live run (the mandated Issue 11 split-brain trigger), is
**now resolved** — see its section. A seventh (19), found by the chaos
harness's PR-gate streak (a routine graceful rolling restart racing a late
signal), is **now resolved** — see its section. An eighth (20), found by a
PR-gate re-proof run after Issue 19's fix (a transient store outage during a
parked wake-recheck probe), is **now resolved** — see its section. A ninth
(21), found while building a tracing fixture during the release-hardening
cycle and triaged as release-blocking, is **now resolved** — see its section.
A tenth (22), found by the review of 21's fix in the same class of
read-modify-write race, is **open**: narrow, pre-existing, and behavioural
rather than cosmetic. An eleventh (23), found while building the demo stack —
where it surfaced as a cross-service trace that would not join up — is
**open** and the highest-severity thing on this list: Maestro's Kafka beans
shadow Spring Boot's by type, silently voiding `spring.kafka.producer.*` and
`spring.kafka.template.*` for every user, and `@MaestroSignalListener` never
extracts the inbound `traceparent`.

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
| [13](#issue-13) | `CANCELLED` timers can strand a replaying workflow | Library defect | Medium | **Resolved** |
| [14](#issue-14) | `SagaManager` re-appends `COMPENSATION_STARTED` on replay | Library gap | Low | **Resolved** |
| [15](#issue-15) | Admin dashboard retry/terminate signals are unconsumed | Library gap | Medium | **Resolved** |
| [16](#issue-16) | Retrying a compensated saga is guarded off, not supported | Library gap | Medium | Open, guarded |
| [17](#issue-17) | Cross-node timer fires never wake the sleeping workflow | Library defect | High | **Resolved** |
| [18](#issue-18) | A stale run's duplicate append is recorded as workflow failure | Library defect | High | **Resolved** |
| [19](#issue-19) | Timed-out awaits replay nondeterministically (late signal consumed at the gap) | Library defect | High | **Resolved** |
| [20](#issue-20) | A transient store outage during a parked wake-recheck fails a healthy workflow | Library defect | High | **Resolved** |
| [21](#issue-21) | Two `parallel()` branches parking at once fail the workflow and run compensations | Library defect | High | **Resolved** |
| [22](#issue-22) | Compensations can run on an operator-terminated workflow | Library defect | Medium | Open |
| [23](#issue-23) | Maestro's Kafka beans silently disable `spring.kafka.*`; `@MaestroSignalListener` drops trace context | Library defect | Critical | Open |

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
>
> **Correction.** The above described only `WorkflowExecutor`'s own
> `WORKFLOW_*` events; a live loan-origination E2E run with the flag set
> still leaked 247 `ACTIVITY_*`/`SIGNAL_*`/`TIMER_*` events, because
> `ActivityInvocationHandler`, `SignalManager`, and
> `DefaultWorkflowOperations` each published lifecycle events through their
> own unguarded reference and never checked the flag — and the audit that
> followed found a fourth unguarded publisher, `SagaManager`
> (`COMPENSATION_*`). The actual fix is `GatedWorkflowMessaging`, a
> `WorkflowMessaging` decorator in `maestro-core` whose
> `publishLifecycleEvent` no-ops when disabled and passes every other method
> through unchanged. It is applied at both places a `WorkflowMessaging`
> reference is constructed — `WorkflowExecutor`'s constructor (covering
> `SignalManager`, `SagaManager`, and `DefaultWorkflowOperations`, which it
> builds) and the Spring Boot starter's `ActivityStubBeanPostProcessor`
> (covering activity proxies) — so every event family (`WORKFLOW_*`,
> `ACTIVITY_*`, `SIGNAL_*`, `TIMER_*`, `COMPENSATION_*`) is gated from one
> shared seam instead of each publisher re-implementing its own check.
> Commit `63c01fc`. Pinned by `GatedWorkflowMessagingTest` and
> `WorkflowExecutorLifecycleEventPublishingTest`.

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

**Measured evidence (multi-instance chaos cycle, 2026-08-01).** The chaos
harness (`.superpowers/sdd/multi-instance/chaos-harness-design.md`) drives a
real six-node loan-origination cluster and mandates at least 2 loan-node
`PAUSE_RESUME` actions (`docker pause`/`unpause` past the 30s instance-lock
TTL) per run — the exact split-brain window this issue describes: the frozen
node's local run keeps executing while a peer adopts and completes the
workflow, and the frozen node resumes into a stale run when it thaws. Its
per-workflow side-effect census (design §7: log-line counts of rate-lock
reservation, disbursement, and compensation release, correlated against the
chaos action log) measured this directly across the three consecutive
PR-gate streak runs that constitute the gate of record
(`.superpowers/sdd/multi-instance/evidence/task7/INDEX.md`,
`docs/../task-7-report.md` §0):

| Run ID | Workflows | Loan-node `PAUSE_RESUME` actions | Side-effect duplicates |
|---|---|---|---|
| `20260731-234107-3430218812008443518` | 74 | ≥2 (mandated) | **0** |
| `20260731-235041--200961534721746905` | 75 | ≥2 (mandated) | **0** |
| `20260801-000014-886868793817033505` | 62 | ≥2 (mandated) | **0** |

Zero duplicate side effects across all 74+75+62 = 211 workflows, every run
containing at least one mandated split-brain window.

**Why zero, not "rare."** Two engine properties combine to make the loser
lose fast rather than merely lose eventually:

1. **The unique event index was always the store-correctness backstop** this
   section describes ("the loser's writes fail and it adopts the winner's
   results") — that part of the contract was never in doubt.
2. **Issue 18's fix makes that failure cheap and immediate.** Before Issue 18
   was fixed, the loser's colliding append threw `DuplicateEventException`
   into the generic failure handler, which durably marked the (already-won)
   workflow `FAILED` and ran compensations — real side-effect reversal, and a
   long detour (a full failure-and-compensation pass) before the loser gave
   up. After the fix, that same collision is recognised at the top of
   `executeWorkflow` as "another run owns this workflow's progress" and
   stands the local run down immediately: no further writes, no
   compensation, no retry of the activity that would have produced a second
   side effect. The window in which a stale run could still race ahead and
   call an external side effect a *second* time is now just "however long
   between the peer's adoption and the stale node's next event append" —
   short in this workload's activity shapes, and evidently short enough to
   round to zero in three ten-minute chaos windows.

**What this means for the fencing decision.** This is evidence, not proof of
absence: split-brain under the harness's mandated trigger currently produces
correct durable *state* (invariant I1/I3 clean in all three runs — see
`docs/../task-7-report.md` §0) with a **measured** duplicate-side-effect rate
of 0/211 workflows. The fencing gap this issue tracks was always specifically
about side effects, never about store correctness (that was Issue 18's
territory, now closed) — and it now has a number instead of a hypothesis:
under a 10-minute chaos window with a real cluster, real Postgres, real
Kafka, and the mandated split-brain trigger, duplicate side effects are rare
enough not to appear at all. That does not retire the issue — fencing tokens
would make the *guarantee* unconditional instead of "unobserved at this
sample size" — but it is a materially different starting point for deciding
whether to prioritise the SPI change than "we don't know how often this
happens."

**Honest caveats.**
- **Short windows.** Each streak run is the PR-gate's 10-minute chaos window
  (plus drain); a duplicate that needs a longer stale-run race to land (e.g.
  a slow activity call that outlives the adoption-to-next-append gap) would
  not necessarily show up here.
- **One workload.** All 211 workflows are loan-origination paths (HAPPY,
  SAGA_WITHDRAWAL, SIGNAL_TIMEOUT, CONDITIONS_LOOP) with activity latencies
  in the tens-to-low-hundreds-of-milliseconds range. A workload with slower
  or blocking side-effect activities has more time for a stale run to race a
  second call before standing down, and would need its own measurement.
- **One trigger shape.** Only `PAUSE_RESUME` (freeze past TTL, then resume)
  and `PARTITION`/`BACKEND_OUTAGE(valkey)` are exercised (design §4); a GC
  pause or a partition of a different duration is not separately measured.

**Soak-window data point (run `20260801-214325--6973268155056049009`,
2026-08-01/02).** The multi-hour soak run the earlier placeholder promised
has landed: SOAK mode, seed `-6973268155056049009`, a 120-minute chaos
window at 20 workflows/min — **2,376 workflows** (974 HAPPY /
454 CONDITIONS_LOOP / 472 SIGNAL_TIMEOUT / 476 SAGA_WITHDRAWAL),
`VERDICT: PASS`, `violations: []` (`run-summary.json` in the run dir under
`.superpowers/sdd/multi-instance/evidence/task7/`). The side-effect census
(`side-effects.json`, same dir):

- **0 duplicate side effects** (0 explained / 0 unexplained), no missing
  saga compensations.
- Per-effect totals: 1,904 rate-lock reservations, 1,428 disbursements, and
  **476 compensation releases — exactly the SAGA_WITHDRAWAL count**: every
  saga path compensated exactly once, and nothing else compensated at all.
- 13 redelivered-but-unconsumed signal groups, every one `consumedTwin=true`
  — the known, informational Kafka at-least-once shape (Ruling 3), not a
  correctness finding.
- Checker integrity: the run's own periodic checker completed 245 cycles
  with 1 unreachable cycle (max streak 1) — the invariants were being
  watched for essentially the entire window.
- Drain: after the end-of-window heal-all, every in-flight workflow across
  all three services reached a terminal state in **76s** (console
  01:45:57 → 01:47:13 SAST) against the harness's 240s drain SLA — the
  backlog a 2-hour chaos window builds clears in about a minute once chaos
  stops.

Combined with the PR-gate streaks above, the measured duplicate-side-effect
rate is now **0 across 2,587 workflows** (211 PR-gate + 2,376 soak). The
soak window blunts the "short windows" caveat's sharpest edge (a 12×-longer
chaos window than the PR gate); the "one workload" and "one trigger shape"
caveats still stand — as does the issue itself: fencing tokens would make
the guarantee unconditional, whereas this makes it well-measured.

**Soak-run provenance and caveats — stated honestly** (they matter for
anyone re-reading the raw console, `evidence/task7/soak-console.log`):

1. The same JVM first ran the PR-gate class: the soak invocation predates
   the `d4720ca` suite-selection fix (which makes dedicated
   soak/golden/smoke invocations select only their dedicated class), and
   that PR-gate run aborted at its own 25-minute `@Timeout` — the root
   cause, finally attributed, of every earlier failed soak attempt. The
   console therefore ends `BUILD FAILED` / `SOAK_EXIT=1`. **The soak test
   itself PASSED**; this evidence is evaluated from the soak run's own
   verdict and run directory, not the JVM's exit code.
2. The aborted PR-gate leaked its checker/sampler threads (the run's binary
   predates the `eac200e` failure-path-teardown fix), which spammed
   `CHECKER BLIND … Mapped port can only be obtained after the container is
   started` and `execInContainer` WARNs into the console from 23:43 SAST
   onward — a single monotonic streak, fully attributable to the leaked
   threads probing the torn-down PR-gate cluster. The soak's own checker was
   clean: the 245-cycles / 1-unreachable / max-streak-1 numbers above come
   from `run-summary.json`, not from grepping the polluted console.
3. Binary provenance: the test binary compiled at `b2b5c65` (the console's
   identity header, started 23:14 SAST); the run dir's identity stamps
   `gitHead 7113e06` because the stamp reads git at run start (23:43), after
   four later commits had landed (`2ac7a57`/`eac200e` harness source,
   `617a735`/`7113e06` docs). The workload-semantics fixes under test
   (interrupt-safe pacer, runaway cap, in-flight bound) **are** in
   `b2b5c65`; the later fix-loop commits touch failure paths, teardown, and
   reporting only — none of them alter what this run measured.
4. Schema provenance: the run's `run-summary.json` omits
   `payload.generationBackPressure` because the `b2b5c65` binary predates
   that field (added in `8cd2754`). Back-pressure values are therefore
   unavailable for this run; the historical artifact is not regenerated,
   and every future soak run records the field.

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

**Benchmark (multi-instance chaos cycle, 2026-08-01).**

*Methodology* (`.superpowers/sdd/multi-instance/chaos-harness-design.md` §6,
coordinator-ruled — no engine metrics seam was added; see the design doc's
§13 Q10 ruling). The metrics this issue asks for are all backend-visible, so
the chaos harness samples the backends directly rather than instrumenting
`maestro-core`:

| Metric | Source |
|---|---|
| Recovery-query rate | `pg_stat_statements` delta for the `getRecoverableInstances` statement (queryid pinned at runtime per run — fails loudly if the store's SQL changes rather than silently reporting zero) |
| Lock probes / renewals (Valkey) | `Valkey INFO commandstats` deltas for the exact commands `maestro-lock-valkey` issues |
| Lock probes / renewals (Postgres lock) | `pg_stat_statements` deltas for the `maestro_lock`-table statements |
| Wake-subscription churn | Valkey `subscribe`/`unsubscribe` command deltas + the `pubsub_channels` gauge |
| Parked / running counts, node count | store query + harness controller state |

A sampler thread snapshots all of the above every 15s into `metrics.csv`
(`windowStartUtc,windowSecs,liveNodes,running,waitingSignal,waitingTimer,
compensating,recoveryCalls,recoveryRatePerSec,lockProbeCalls,lockRenewCalls,
subscribeCalls,unsubscribeCalls,pubsubChannels,chaosActive`).
`pg_stat_statements` cannot attribute calls per node (it aggregates per
queryid across all nodes sharing a DB user), so **per-node rates are only
valid in calm windows** (`chaosActive=false`, all 6 nodes healthy) —
`clusterRate / liveNodes`. Two benchmark axes follow from that constraint:

- **vs parked-workflow count** — a soak run's natural backlog growth/shrink
  gives a spread of `(parkedCount, recoveryRate, lockProbeRate)` points
  across calm windows across a long run.
- **vs node count** — a dedicated benchmark tail (design §6/§14.5): chaos
  off, steady low-rate workload, one measurement phase at 6 nodes, a graceful
  stop of one node per service, a second measurement phase at 3 nodes.

*PR-gate metrics samples (illustrative, not the benchmark of record — each
window below is 15 seconds, far too short to characterise scaling; see
"vs node count" below for the real benchmark).* Real rows from the three
PR-gate streak runs that are the gate of record
(`.superpowers/sdd/multi-instance/evidence/task7/INDEX.md`,
`.superpowers/sdd/task-7-report.md` §0), one calm and one chaos window per
run:

| Run | Window | liveNodes | waitingSignal | waitingTimer | recoveryRatePerSec | lockProbeCalls | lockRenewCalls | subscribeCalls | pubsubChannels |
|---|---|---|---|---|---|---|---|---|---|
| 1 (`3430218812008443518`) | calm | 6 | 0 | 0 | 0.000 | 0 | 18 | 0 | 0 |
| 1 (`3430218812008443518`) | chaos | 5 | 4 | 4 | 0.000 | 44 | 61 | 11 | 4 |
| 2 (`-200961534721746905`) | calm | 6 | 2 | 3 | 0.000 | 36 | 60 | 12 | 2 |
| 2 (`-200961534721746905`) | chaos | 5 | 11 | 2 | 0.133 | 65 | 114 | 20 | 11 |
| 3 (`886868793817033505`) | calm | 6 | 3 | 4 | 0.000 | 28 | 45 | 7 | 3 |
| 3 (`886868793817033505`) | chaos | 5 | 6 | 3 | 0.000 | 29 | 50 | 7 | 6 |

Qualitative read (not a fitted curve — sample size is three 15s windows):
`liveNodes` drops 6→5 whenever the controller has an active harassed node
(`chaosActive=true`), and `lockRenewCalls`/`lockProbeCalls` both rise in
chaos windows (more contention/adoption activity, consistent with Issue 12's
theory), but a real per-node-scaling number needs the dedicated benchmark
tail below, not PR-gate noise.

**vs-node-count benchmark of record (soak run
`20260801-214325--6973268155056049009`, 2026-08-01/02).** The benchmark tail
(chaos off, steady 6/min workload, one 300s measurement phase at 6 nodes, a
graceful stop of one node per service — `LOAN_B`, `VERIFY_B`, `UW_B` — then
a second 300s phase at 3 nodes) ran after the 120-minute soak window's
passing verify. Sources: `benchmark-tail.json` (phase boundaries, stopped
nodes, workflow counts) and `metrics.csv` (524 15s samples across the whole
run, 312 of them calm) in the run dir under
`.superpowers/sdd/multi-instance/evidence/task7/`. Every sample inside both
tail phases was calm (`chaosActive=false`), 20 samples per phase; the
per-15s columns below are averages over those 20 samples.

| Phase | Duration | liveNodes | Workflows | recoveryRatePerSec (calm) | lockProbeCalls/15s (calm) | lockRenewCalls/15s (calm) | parkedCount (avg) |
|---|---|---|---|---|---|---|---|
| 6 nodes (`tail6`, 23:47:15–23:52:18Z) | 300s | 6 | 23 | 0.100 | 23.9 | 49.6 | 5.7 |
| 3 nodes (`tail3`, 23:52:22–23:57:26Z) | 300s | 3 | 27 | 0.050 | 24.0 | 45.2 | 8.1 |

Read of the numbers:

- **The recovery-query rate is proportional to node count — consistent with
  linear at both measured node counts (6 and 3).** The cluster-wide rate
  halves with the cluster (0.100 → 0.050 calls/s), i.e. a constant ≈0.0167
  calls/s per node (one `getRecoverableInstances` poll per node per ~60s) in
  both phases. That is this issue's core theory confirmed
  by a clean measurement: every node polls the full recoverable set on its
  own interval regardless of ownership, so store-side recovery-query load
  scales with node count (and each poll's cost scales with the active
  workflow set — the quadratic-ish combination this issue describes).
- **Lock probe/renew traffic tracks the parked-workflow backlog, not node
  count.** Halving the nodes left probes flat (23.9 → 24.0 per 15s) and
  renewals near-flat (49.6 → 45.2 per 15s) while the average parked count
  rose 5.7 → 8.1 (the 3-node phase inherits the 6-node phase's in-flight
  workflows on fewer nodes). Renewal round-trips are per held lock, not per
  node — the serial-renewal concern above is a backlog-scaling cost.
- The absolute numbers are modest: at this workload (6/min, ≤~10 parked)
  nothing here is a bottleneck. The issue is about the trend, and the trend
  is now measured, not hypothesised.

*Run provenance caveats:* same run as Issue 11's soak data point — see
"Soak-run provenance and caveats" there (PR-gate `@Timeout` collision in the
same JVM, leaked-checker console noise, `b2b5c65` binary vs `7113e06`
stamp). None of the three affect the tail phases, which ran chaos-free at
the end of the soak's own passing run.

---

### Issue 13 — `CANCELLED` timers can strand a replaying workflow {#issue-13}

> **Resolved.** Cancelling a timer a workflow is parked on now unparks it
> with a durable, catchable outcome instead of stranding it — option (a) from
> the open question below, decided and implemented.
> `WorkflowExecutor.cancelTimer(String, String, UUID)` is the new, only
> supported entry point (`TimerManager.cancelTimer` is **removed** — it had
> zero production callers): it CASes the timer row `PENDING → CANCELLED` via
> `WorkflowStore.markTimerCancelled`, now returning `boolean` instead of
> `void` (**source-breaking** for third-party stores — see
> `docs/release-notes.md`), and unparks the workflow only if the CAS won.
> `DefaultWorkflowOperations.sleep()` now performs a three-way heal off the
> durable timer row instead of the old two-way (fired/pending) check: `FIRED`
> behaves byte-for-byte as before the change; `CANCELLED` appends a new
> `TIMER_CANCELLED` event (at the same `seq+1` slot `TIMER_FIRED` used to
> occupy) and throws the new, catchable `TimerCancelledException`, memoized
> so replay reproduces the same outcome from the log alone, no store read;
> `PENDING` still re-parks. Left uncaught, the exception fails the workflow
> with compensation — a defined outcome, never a silent stall. New
> `LifecycleEventType.TIMER_CANCELLED`, added per the coordinator's §12
> approval; `maestro-admin`'s `EventProjector` was checked and found
> tolerant of unknown lifecycle types by construction (allow-list branches,
> `switch` with `default -> null`), so no admin-side change was needed.
> Commits `eaac670`..`9c58371`. Pinned by
> `WorkflowExecutorTest.recoverWorkflowsHealsTimerCancelledBeforeEventAppend`
> (the C2/C3 heal), `WorkflowExecutorTest.replayOnly_timerCancelledEvent_throwsWithoutNewStoreWrites`
> and `WorkflowExecutorTest.replayOnly_catchHandlerActivityAlreadyMemoized_isNotReInvoked`
> (replay determinism, including a caught handler's own activity call), and
> `EnginePostgresTimerIT.timerCancelledBeforeEventAppend_recoveryFailsTheWorkflowDeterministically`
> against real Postgres. Documented in `docs/concepts.md` § "Cancelling a
> timer". The rest of this section is kept as the record of the defect.

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

> **Resolved.** `SagaManager.compensate()` now gives every compensation entry
> — in both the sequential and parallel loops — its own reserved sequence
> block (`blockBase = anchorSeq * BRANCH_MULTIPLIER + (i + 1) *
> BRANCH_MULTIPLIER`), reusing the exact isolation scheme the file already
> used for `parallel()`-style branches, applied one level up to compensation
> entries themselves. Before an entry's action runs, its block's guard
> sequence is checked against the store: if an event already exists there,
> the action is **not** re-invoked — a stored `COMPENSATION_STEP_FAILED` is
> re-added to the failures list without re-running, a stored
> `COMPENSATION_STEP_COMPLETED` is skipped entirely. This closes the real
> hazard: a manually-registered compensation (`wf.addCompensation(Runnable)`)
> is not memoized the way a `@Compensate` activity's result is, so without
> this guard a completed-but-not-yet-persisted-as-such compensation could be
> re-invoked on a recovery replay. `COMPENSATION_STARTED`/
> `COMPENSATION_COMPLETED` in `compensate()` itself also gained an explicit
> already-appended check before writing, instead of relying on
> `DuplicateEventException` being silently swallowed. Commit `0500259`.
> Pinned by `SagaManagerTest.sequentialReplayDoesNotReinvokeAnAlreadyCompletedCompensation`,
> `SagaManagerTest.parallelReplayDoesNotReinvokeAnAlreadyCompletedCompensation`,
> and `WorkflowExecutorShutdownTest.shutdown_duringCompensationWithEarlierStepAlreadyCompleted_doesNotReinvokeItOnRecovery`
> — the last one is exactly the "a step completes before a later one (not
> first in LIFO order) is interrupted" shape this section originally called
> out as untested by the existing shutdown fixtures. The rest of this section
> is kept as the record of the defect.

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

> **Resolved.** `$maestro:retry` and `$maestro:terminate` are now consumed
> end-to-end. `SignalSubscriptionRunner` diverts any `$maestro:`-prefixed
> signal to a new `AdminCommandDispatcher` (`maestro-spring-boot-starter`)
> *before* `deliverSignal` runs, so the commands are structurally invisible
> to `awaitSignal()` — an unroutable command (unknown name, or a workflow
> type with no `@DurableWorkflow` registration on the receiving node) throws
> `AdminCommandException` and dead-letters instead of silently no-opping.
>
> **Terminate** (`WorkflowExecutor.terminateWorkflow`) durably marks the
> instance `TERMINATED`, with no compensation and no activity interruption,
> and best-effort local eviction via a new `WorkflowTerminatedException
> extends Error` — the second control-flow signal of this shape alongside
> `ExecutorShutdownException` (Issue 4). Idempotent under redelivery
> (converge-loop CAS, same budget as the existing terminal-transition path).
>
> **Retry** (`WorkflowExecutor.retryWorkflow`) needed more than the original
> design assumed: relaunching a `FAILED` instance in replay mode alone does
> nothing, because the failed step's `ACTIVITY_FAILED` event is itself
> memoized and replay deterministically re-throws it instead of
> re-executing — proven empirically before any fix was written. The
> resolution (coordinator ruling) is a new, **source-breaking** abstract SPI
> operation, `WorkflowStore.deleteFailureEvents(UUID instanceId)`, which
> discards exactly the `ACTIVITY_FAILED`/`WORKFLOW_FAILED` memos for that
> instance (never compensation or success events) before the CAS
> `FAILED → RUNNING` and the replay relaunch — so the failed step genuinely
> re-executes with a fresh retry budget, while everything before and after
> it stays memoized. This also frees the sequence slot the old
> `WORKFLOW_FAILED` occupied, so the retried run's `WORKFLOW_COMPLETED`
> lands cleanly instead of colliding with it.
>
> Both commands required closing a **terminal-state resurrection** hazard
> found along the way: a late signal, timer fire, or wake could flip a
> `TERMINATED` instance back to `RUNNING`/`WAITING_*`. Closed by unifying two
> duplicate status-writer helpers (`SignalManager` and
> `DefaultWorkflowOperations` each had their own copy) into one
> `InstanceStatusWriter` that throws `WorkflowTerminatedException` on a
> freshly-read `TERMINATED`, plus the same guard added to
> `SagaManager.transitionToCompensating` (a remote terminate racing a local
> failure could otherwise overwrite `TERMINATED` with `COMPENSATING`).
>
> New `LifecycleEventType.WORKFLOW_RETRIED`, published on a successful retry
> and projected by the admin `EventProjector` to status `RUNNING` (documented
> minor cosmetic gap: a stale `completed_at` from the retried `FAILED` run
> isn't cleared until the next terminal event). `docs/admin.md` documents the
> full Retry/Terminate semantics, a 7-row idempotency table, and the security
> posture (no auth on the command path — restrict with Kafka ACLs). Commits
> `85bec43`..`e5ed1d4`. Pinned by
> `WorkflowExecutorRetryTest.retryOfExhaustedActivity_reExecutesFailedStepAndCompletes`
> (the headline case: a step that exhausted its retry budget genuinely
> re-invokes, not just replays), `WorkflowTerminalStateGuardTest` and
> `WorkflowExecutorTerminateTest` (resurrection guard and terminate outcomes),
> `SagaManagerTest.terminateRacingAFailure_doesNotOverwriteTerminatedWithCompensating`,
> `AdminCommandDispatcherTest` (13 cases pinning the full validation/
> idempotency table), and `AdminCommandKafkaIT` plus the rewritten
> `KafkaSignalChannelIT.adminCommand_terminatesWorkflowAndIsNeverPersisted`
> against a real Kafka broker. The rest of this section is kept as the record
> of the defect.

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

### Issue 16 — Retrying a compensated saga is guarded off, not supported {#issue-16}

> **Guarded, not fixed.** Found during the final whole-branch review of
> Issues 13–15, before merge — see `.superpowers/sdd/issues-13-15-plan/final-review.md`
> §Important #1. `WorkflowExecutor.retryWorkflow` now probes the event log
> for `COMPENSATION_STARTED` right after the `FAILED` check and, if found,
> returns a new `RetryOutcome.COMPENSATED_NOT_RETRYABLE` without touching the
> instance — no CAS, no `deleteFailureEvents`, nothing written.
> `AdminCommandDispatcher` logs it at `WARN` and acknowledges like every
> other no-op outcome. This closes the corruption hazard below by refusing
> the operation instead of performing it safely; retrying a compensated saga
> remains genuinely unsupported. Pinned by
> `WorkflowExecutorRetryTest.retryOfCompensatedSaga_isCompensatedNotRetryableNoOp`
> (zero state change: status, version, and the surviving `ACTIVITY_FAILED`/
> `WORKFLOW_FAILED` memos are all asserted untouched) and
> `AdminCommandDispatcherTest.retryCompensatedSagaWorkflow_isAckedNoOp`
> (dispatcher-level, over the real signal path). `docs/admin.md` and
> `docs/release-notes.md`'s 0.4.0 operator note were corrected to stop
> promising the opposite. The rest of this section is kept as the record of
> the underlying defect this guard prevents.

**What's wrong.** Issue 15's `retryWorkflow` only deletes a `FAILED`
instance's `ACTIVITY_FAILED`/`WORKFLOW_FAILED` memos before relaunching in
replay mode. If the workflow's saga already ran compensations before it
failed, the compensation events survive — but at sequence positions anchored
to the *original* failure point (`compensate()` anchors at `F+1` where `F` is
the failed step's sequence, with each entry's replay-skip guard at
`(F+1)*1000+(i+1)*1000`). If the retried run's previously-failed step now
*succeeds* (`ACTIVITY_COMPLETED`@F), the forward path continues into seq
`F+1` — which holds `COMPENSATION_STARTED`, not the next real step's outcome.
Two ways this breaks, depending on whether the failed step was last:
- **More steps follow:** the next activity's replay check hits
  `ActivityInvocationHandler.handleReplay`'s `default` branch →
  `IllegalStateException("Unexpected event type COMPENSATION_STARTED…")` →
  `handleWorkflowFailure` → `compensate()` runs *again*, now anchored at
  `F+2`. Every per-entry guard reads a slot shifted by one anchor step:
  entries durable at the `F+1`-anchored blocks are **re-invoked** (real
  side effects — refunds, releases — run twice), and depending on entry
  counts a stale event can land exactly on a shifted guard slot and
  **wrongly skip** an entry that never ran.
- **The failed step was last:** `executeWorkflow`'s `WORKFLOW_COMPLETED`
  append at `F+1` collides with the stale `COMPENSATION_STARTED` row already
  there, and `WorkflowExecutor.appendEvent`'s duplicate-swallow (needed for
  the legitimate replay-skip cases elsewhere) silently eats it — leaving a
  `COMPLETED` instance whose log ends mid-compensation with no terminal
  event.

The benign case — the failed step re-fails identically on retry — is
unaffected: the anchors never shift because compensation never runs a second
time. QA's Issue 15 gate-4 sign-off only exercised that path; no test in the
Issue 13–15 branch ever drove a retry through a workflow that had actually
compensated (confirmed by grep before this guard was added).

**Why it matters.** Silent replay corruption in the one path an operator
reaches for specifically to fix a broken workflow. Worst case: financial or
inventory side effects run twice, or a workflow reports `COMPLETED` while its
event log is truncated mid-compensation with no audit trail of how it got
there.

**Where.**
`maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java`
(`retryWorkflow`, now with the guard) ×
`maestro-core/src/main/java/io/b2mash/maestro/core/saga/SagaManager.java`
(`compensate()`'s anchor-relative guard blocks, unchanged — the guard sits
upstream of them rather than reworking their addressing).

**What a fix looks like.** Two directions worth designing between, neither
attempted here:
1. **Fresh-run / `runId` relaunch.** Give the retry a new `runId` and replay
   the completed prefix into a fresh event log up to (not including) the
   failure, instead of resuming the old log in place — sidesteps the
   anchor-collision problem entirely because the retried run never writes
   into the old compensation block's sequence range.
2. **Compensation-aware sequence rebase.** Detect the compensated case (as
   this guard already does) and, instead of refusing, relocate the surviving
   compensation events to sequence positions that can't collide with the
   forward path — e.g. a reserved high-sequence band — before clearing the
   failure memo and relaunching.
Either way the fix needs to decide what "retry after compensation" even
*means* operationally (should the compensations that already ran still count
once the step succeeds?) before it's safe to implement — a design
conversation, not a quick patch.

**Done when.** A test drives a workflow through activity failure → saga
compensation → `FAILED`, fixes the fault, retries, and asserts the retried
run reaches a clean terminal state (`COMPLETED` or a well-formed `FAILED`)
with no re-invoked compensation side effects, no wrongly-skipped entries,
and a genuine terminal event in the log — the mirror image of
`WorkflowExecutorRetryTest.retryOfExhaustedActivity_reExecutesFailedStepAndCompletes`
for the compensated case. Until then, `COMPENSATED_NOT_RETRYABLE` stays the
correct outcome.

---

### Issue 17 — Cross-node timer fires never wake the sleeping workflow {#issue-17}

> **Resolved.** `DefaultWorkflowOperations.sleep()` no longer parks
> indefinitely: both the live park and the replay re-park now park in
> wake-recheck-interval chunks — the exact pattern
> `SignalManager.awaitSignal` already used to survive missed cross-process
> wakes — and on every chunk expiry re-read the durable timer row via
> `WorkflowStore.findTimer`. A row a remote node has already transitioned
> ends the park: `FIRED` appends the same `TIMER_FIRED` event a local wake
> would (identical event/sequence semantics to the Issue 2 replay heal),
> `CANCELLED` takes the Issue 13 outcome (`TIMER_CANCELLED` event +
> catchable `TimerCancelledException`), `PENDING` keeps parking. A
> cross-node terminate is also noticed within one interval, mirroring
> `awaitSignal`'s per-chunk stand-down. The interval is the existing
> `maestro.signal.wake-recheck-interval` (default 30s, unchanged) — reused
> rather than a new property, since it already means "how often a parked
> workflow re-reads the store for a wake it may have missed"; no SPI, schema
> or messaging change. The local unpark stays the instant fast path, so
> single-node (leader == owner) behaviour is unchanged. Commit `bdf9cc6`.
> Pinned by
> `WorkflowExecutorCrossNodeTimerWakeTest` (store-only `FIRED`/`CANCELLED`
> transitions wake the parked sleep within the interval; a `PENDING` row
> keeps it parked; the local fast path is untouched under the 30s default)
> and `multinode.MultiNodeTimerWakeIT` (two engine harnesses over one real
> Postgres: node B — sole timer poller, hence leader — fires or cancels, the
> workflow sleeping on node A completes). The rest of this section is kept
> as the record of the defect.

**What's wrong.** `TimerPoller` polls due timers only on the elected leader.
`WorkflowExecutor.fireTimer` CASes the timer row `PENDING → FIRED` in the
shared store, then unparks via `ParkingLot` — a per-JVM map, so the unpark is
a no-op when the workflow's parked virtual thread lives on a different node.
`DefaultWorkflowOperations.sleep()` parked indefinitely (plain
`ParkingLot.park`) with no periodic recheck — unlike
`SignalManager.awaitSignal`, which parks in `wakeRecheckInterval` chunks
precisely to survive missed cross-process wakes. Once the row is `FIRED` it
is invisible to `getDueTimers` forever, and the Issue 2/13 self-heals only
run on replay — so nothing short of restarting the owning node recovers the
workflow. `cancelTimer`'s unpark was local-only too (the same gap for
cross-node cancellation of a parked sleep), and a cross-node terminate of a
parked sleep was likewise only noticed at the next status write.

**Why it matters.** This is routine operation, not a failure scenario: in
*any* multi-instance deployment of a service whose workflows call
`workflow.sleep()`, every sleep wedges forever whenever the timer-poller
leader happens not to be the node owning the parked thread — roughly
(n−1)/n of sleeps in an n-node cluster. Found immediately when the
loan-origination sample was run with two instances of every service
(`.superpowers/sdd/multi-instance/rulings.md` Ruling 1); the same silent,
permanent stall shape as Issues 2 and 13.

**Where.** `maestro-core/src/main/java/.../engine/DefaultWorkflowOperations.java`
— `sleep`/`parkForTimer`; `maestro-core/src/main/java/.../engine/WorkflowExecutor.java`
— `fireTimer`/`cancelTimer` (the local-only unpark) and the
`wakeRecheckInterval` seam; `maestro-core/src/main/java/.../engine/TimerPoller.java`
— leader-only polling (unchanged; correct once the sleeper rechecks).

**Done when.** A workflow sleeping on node A completes when node B's poller
fires (or an operator on node B cancels) its timer, within a bounded
interval, with the identical event log a single-node wake produces — proven
at unit level against in-memory SPIs and end-to-end against real Postgres
with two engine instances.

### Issue 18 — A stale run's duplicate append is recorded as workflow failure {#issue-18}

> **Resolved.** A `DuplicateEventException` that reaches
> `WorkflowExecutor.executeWorkflow`'s top level now stands the local run
> down — mirroring the shutdown and termination cases — instead of falling
> into the generic `catch (Exception)` that treats workflow failures. The
> stand-down writes nothing, runs no compensation, and releases the local
> run; the concurrent runner's durable state governs, and if no concurrent
> runner exists the instance stays active and recovery replays it from the
> log. The sibling collection points got the same audit Issues 4/5's
> control-flow exceptions did: `SagaManager.appendEvent` and
> `recordStepFailure` no longer swallow the exception (a stale run must not
> keep executing compensation actions the winner also runs),
> `executeSequential` rethrows it instead of recording
> `COMPENSATION_STEP_FAILED`, and `executeParallel`'s outcome collection
> rethrows it with the same priority as shutdown/termination.
> `ActivityInvocationHandler` is deliberately unchanged: its
> adopt-the-stored-result handling of the same exception (return the
> memoized payload at that sequence) is correct memoization semantics —
> only an append that escapes to the executor's top level means the whole
> local run is stale. Commit `0fe8bd7`, reproduced RED-first in `73af765`.
> Pinned by `WorkflowExecutorDuplicateEventStandDownTest` (three tests: the
> loser adopts a winner's COMPLETED outcome — no FAILED write, no
> compensation; with no winner the instance stays active and recoverable;
> a duplicate landing mid-compensation stands the whole attempt down
> without recording a step failure). The rest of this section is the record
> of the defect.

**What's wrong.** In the Issue 11 no-fencing window — a node frozen past the
30s instance-lock TTL (long GC pause, `docker pause`), partitioned, or racing
on the no-lock-backend degradation — a peer node adopts and re-runs the
workflow. When the stale node resumes, its next event append collides with
the event the winner already persisted at that sequence, and the store's
`(workflow_instance_id, sequence_number)` unique guard throws
`DuplicateEventException` — the dedup mechanism working exactly as designed.
But the exception is a `MaestroException` (a `RuntimeException`), so it fell
into `executeWorkflow`'s generic `catch (Exception)` and was treated as *the
workflow failing*: the instance was durably marked `FAILED` with the
conflict message as its output, a `WORKFLOW_FAILED` event was appended (or
half-appended — the terminal event append often collided too, leaving a
terminal instance with no terminal event), and the saga's compensations ran,
reversing side effects of work that had **succeeded** on the winner.
`SagaManager`'s own event appends swallowed the exception outright, so a
stale run could also march through compensation actions the winner was
concurrently running.

**Why it matters.** The architecture's documented split-brain contract is
"the loser's writes fail and it adopts the winner's results" — duplicate
*side effects* are the tolerated Issue 11 consequence, store-level
correctness is not negotiable. This path instead produced a durably wrong
terminal state (a funded loan recorded `FAILED`) and real compensation-side
effects (a reserved rate lock released after disbursement). Found by the
chaos harness's first live run: the mandated `PAUSE_RESUME` split-brain
trigger hit it deterministically — 3 of 13 workflows in a one-minute
shakeout window (`.superpowers/sdd/multi-instance/rulings.md` Ruling 2).
It is the mid-run sibling of BUG7 (the finalize-time
`OptimisticLockException` that recorded a successful workflow `FAILED`),
which the convergent terminal transition fixed without covering the
event-append collision.

**Where.** `maestro-core/src/main/java/.../engine/WorkflowExecutor.java` —
`executeWorkflow`'s catch chain (the new stand-down catch, plus the
duplicate-during-compensation catch around `handleWorkflowFailure`) and
`handleStaleRunStandDown`; `maestro-core/src/main/java/.../saga/SagaManager.java`
— `appendEvent`, `recordStepFailure`, `executeSequential`,
`executeParallel`; `maestro-core/src/main/java/.../engine/ActivityInvocationHandler.java`
— `appendEventSafe` (unchanged, deliberately).

**Done when.** Under the chaos harness's split-brain trigger, every workflow
reaches the terminal state its path script declared (invariant I1), terminal
event logs are well-formed (I3), and the only Issue 11 residue is counted
duplicate side effects — proven by the harness's PR-gate mode running green
and pinned at unit level by `WorkflowExecutorDuplicateEventStandDownTest`.


### Issue 19 — Timed-out awaits replay nondeterministically {#issue-19}

> **Resolved.** A timed-out `awaitSignal` now memoizes a `SIGNAL_TIMEOUT`
> event at its allocated sequence slot <em>before</em> throwing
> `SignalTimeoutException` (payload: signal name + timeout), and replay that
> finds `SIGNAL_TIMEOUT` at the slot re-raises the timeout deterministically
> from the log alone — no store read, no signal consumption. A late-arriving
> signal stays durably unconsumed ("never discard a signal") for a later
> await of the same name to find. This is the signal analogue of Issue 13's
> `TIMER_CANCELLED` memoization; the append-then-throw ordering closes the
> crash window (any later event implies the memo is durable; a crash before
> the append leaves no post-timeout history, so a fresh consume is a
> legitimate choice, not divergence). Retry ripple: `deleteFailureEvents`
> also deletes the FAILING timeout memo — the highest-sequenced
> `SIGNAL_TIMEOUT`, and only when `WORKFLOW_FAILED` records a
> `SignalTimeoutException` as the cause — so a retried await re-drives and
> consumes the now-delivered signal; caught gate memos survive retry (any
> other deletion would resurrect the divergence through the retry door).
> Commit `9ab457e`, reproduced RED-first in `6eed32c`. Pinned by
> `SignalTimeoutReplayDeterminismTest` (four tests: replay re-raises and the
> late signal stays unconsumed; the saga shape honours the withdrawal at
> gate #2 and compensates — no leak; retry re-drives a timeout failure;
> retry preserves caught gate memos). Event logs no longer contain
> timed-out-await gaps: the loan E2E's expected missing sets and the chaos
> harness's I3(d) bounds were re-derived to empty/zero, superseding the
> earlier "designed gap" ratification. The rest of this section is the
> record of the defect.

**Mixed-version caveat.** `SIGNAL_TIMEOUT` is a new `EventType` constant: a
node still running the previous version fails `EventType.valueOf` when it
adopts a workflow whose log an upgraded node has written to, so all nodes of
a service must be upgraded together (or the service drained first) — see the
"Upgrade notes" section in `docs/release-notes.md`. And an await that timed
out *pre*-upgrade left no memo, so it replays live once post-upgrade; the
determinism guarantee covers events written by upgraded nodes.

**What's wrong.** `SignalManager.awaitSignal` allocated its sequence number
at entry and, on timeout, threw without appending any event — the sequence
slot stayed empty (the "designed gap" earlier verification work ratified as
benign). On recovery replay the await re-executed at that slot; if the
awaited signal had ARRIVED since the original timeout, the replay consumed
it there and took a different branch than the original execution — a replay
determinism violation, the exact class of bug the memoization log exists to
prevent.

**Why it matters.** The trigger is routine: a graceful rolling restart
(deploy) racing a late signal — no failure injection required. Observed by
the chaos harness (PR-gate streak run B, seed -825499340287642346): a saga
loan's original run timed out withdrawal gate #1, approved, reserved its
rate lock, and parked; the node was rolled; the withdrawal landed between
stop and recovery; the replacement's replay consumed the withdrawal at gate
#1's slot, threw there — before `reserveRateLock` — so its compensation
stack was empty: the reserved rate lock LEAKED, and the divergent
`WORKFLOW_FAILED` append collided with a memoized event, leaving a terminal
instance with no terminal event. Any workflow combining timeout-guarded
awaits with saga compensation is exposed.

**Where.** `maestro-core/src/main/java/.../engine/SignalManager.java` —
`awaitSignal` (the memo append + replay re-raise); `maestro-core/src/main/
java/.../model/EventType.java` — `SIGNAL_TIMEOUT`;
`maestro-store-jdbc/.../AbstractJdbcWorkflowStore.java` and
`maestro-test/.../InMemoryWorkflowStore.java` — `deleteFailureEvents` (the
failing-timeout-memo rule); `maestro-core/src/main/java/.../spi/
WorkflowStore.java` — the SPI contract.

**Done when.** The chaos harness's PR-gate mode runs green with strict
event-log contiguity (I3(d) bound zero), the loan E2E's re-derived empty
missing-sets pass, and the four pinning tests hold — replay determinism,
no saga leak, retry re-drive, caught-memo preservation.


### Issue 20 — A transient store outage during a parked wake-recheck fails a healthy workflow {#issue-20}

> **Resolved.** The periodic wake-recheck probes a parked workflow performs —
> `SignalManager.standDownIfTerminated`'s instance read, the signal-poll read
> inside `awaitSignal`'s recheck loop, and the Issue 17 `findTimer` recheck in
> `DefaultWorkflowOperations.sleep()` — are advisory: skipping one interval is
> always safe, since cross-node terminate convergence and cross-node wake are
> delayed by at most one interval and no durable state is written by a probe
> read. A new `ParkProbe.read()` helper (shared by `SignalManager` and
> `DefaultWorkflowOperations`, mirroring the existing `InstanceStatusWriter`
> precedent for a guard that must not exist as two drifting copies) now wraps
> each probe read: a `RuntimeException` from the store is caught, logged at
> WARN (rate-limited — the first failure of an outage streak and every 20th
> thereafter, via a shared failure counter reset on the next successful
> probe), and a fallback is returned that means "inconclusive this interval,
> try again next chunk" — never a value the caller would treat as a real
> outcome. State writes (event appends, status CAS transitions, signal
> consumption) are unaffected and keep failing exactly as before; a probe
> failure never produces a `WorkflowTerminatedException` or
> `ExecutorShutdownException` — those `Error`s are only ever thrown once a
> probe *succeeds* and observes the terminal condition, so ordinary `catch
> (RuntimeException)` in the new helper cannot intercept them. Commit
> `d13444e`, reproduced RED-first in `eb807b6`. Pinned by
> `ParkedProbeStoreOutageTest` (three tests: a parked `awaitSignal` stays
> `WAITING_SIGNAL` through several failed probes and completes once the store
> heals and the signal arrives; a parked `sleep()` stays `WAITING_TIMER`
> through the same shape and wakes on the durable row once healed; a
> terminate that arrives while the store is unreachable is honoured on the
> first successful probe after recovery, having left the run parked and
> untouched while blind). The rest of this section is the record of the
> defect.

**What's wrong.** `SignalManager.standDownIfTerminated`'s `store.getInstance`
call, the signal-poll `store.getUnconsumedSignals` call inside
`awaitSignal`'s recheck loop, and `DefaultWorkflowOperations.sleep()`'s
`store.findTimer` recheck (Issue 17) all ran unguarded on every wake-recheck
interval of a parked workflow. Any `RuntimeException` the store raised —
transient unreachability, a connection-pool timeout — propagated straight out
of the park loop to `WorkflowExecutor.executeWorkflow`'s generic `catch
(Exception)`, which durably marked the workflow `WORKFLOW_FAILED` and ran its
compensations. A workflow that was parked, healthy, and waiting for a signal
or timer that would have arrived fine got recorded as having failed, purely
because an advisory read that exists only to notice a *cross-node* event
happened to run during a blip.

**Why it matters.** No failure injection beyond a routine infra blip is
required — this is the fourth of the Issue 4/5/18 family (a graceful
condition misrecorded as a workflow failure) found this cycle, and the first
triggered by store unavailability rather than shutdown, termination, or a
duplicate append. Found by the chaos harness's PR-gate mode, seed `661901`: a
39-second `PARTITION UW_A` exceeded HikariCP's 30s `connectionTimeout`,
`standDownIfTerminated`'s `getInstance` threw `UncheckedSqlException`, and two
healthy `PARKED` workflows were durably `WORKFLOW_FAILED` mid-run. The
in-memory diagnostic double built to reproduce it (`ParkedProbeStoreOutageTest`)
shows the defect has two faces depending on timing: if the store recovers
before the failure-handling path's own writes, the result is a durable false
`WORKFLOW_FAILED` (what the chaos run observed); if the store is still down
for those writes too, the failure write itself fails, and the instance is
left wedged in its waiting status (`WAITING_SIGNAL` / `WAITING_TIMER`) with no
live run on any node — a silent stall no less serious for producing no
terminal record at all.

**Where.** `maestro-core/src/main/java/.../engine/ParkProbe.java` (new) —
the shared advisory-read wrapper; `maestro-core/src/main/java/.../engine/SignalManager.java`
— `standDownIfTerminated` and the recheck loop inside `awaitSignal`;
`maestro-core/src/main/java/.../engine/DefaultWorkflowOperations.java` —
`standDownIfTerminated` and `parkForTimer`'s recheck loop.

**Done when.** The chaos harness's PR-gate mode re-run with the originally
failing seed (`661901`) passes with invariants I1–I5 clean, a fresh seed also
passes, and the three pinning tests hold — parked `awaitSignal` and `sleep()`
both ride out several failed probes and complete normally once the store
heals, and a terminate arriving mid-outage is honoured on the first
successful probe after recovery.

---

### Issue 21 — Two `parallel()` branches parking at once fail the workflow and run compensations {#issue-21}

> **Resolved** (release-hardening cycle, 2026-08-02). `InstanceStatusWriter.write`
> — the sole writer of a running workflow's non-terminal status, and therefore
> of *both* park paths (`awaitSignal` → `WAITING_SIGNAL`, `sleep()` →
> `WAITING_TIMER`) — was an unguarded, un-retried read-modify-write. It is now
> a **bounded retry against a fresh read** (`STATUS_WRITE_ATTEMPTS = 5`,
> immediate, no backoff), the same idiom `WorkflowExecutor.transitionToTerminal`
> already used for the same conflict on the same row, with the terminal guard
> re-evaluated on every attempt because the row may have gone `TERMINATED` or
> been finalised by another runner in the meantime. On exhaustion it **stands
> down** rather than propagating. Commits `a0905f6` (RED), `158aa6e` (fix +
> boundary pins + Postgres pin), `108cd73` (mechanism pins). The rest of this
> section is the record of the defect.

**What was wrong.** Every branch thread of a `parallel()` fork writes its own
park status into the **one** instance row. `InstanceStatusWriter.write` read
the instance, built a bumped-version copy, and wrote it — with nothing between
the read and the write. Two branches that park together interleave
read/read/write/write, and the loser's compare-and-set finds a bumped version
and throws `OptimisticLockException`.

**Why it mattered.** The exception escaped `parallel()` into workflow author
code, into `WorkflowExecutor.executeWorkflow`'s generic `catch (Exception)`,
and so into a workflow durably recorded `FAILED` **with saga compensations
run** — real refunds and inventory releases for work that never failed. The
damage was durable, not transient: `FAILED` is not `isActive()`, so recovery
never healed it, and `retryWorkflow` returns `COMPENSATED_NOT_RETRYABLE` for a
compensated saga (Issue 16). A new instance of the Issue 4/5/18/20 family — a
graceful condition misrecorded as a workflow failure.

The shape that triggers it is the one `docs/cross-service.md` sells: fan out
and await both replies. The suite was green only because every existing fork
fixture parked at most one branch. It was found while building a tracing
fixture that happened to park two, and reproduced deterministically — with a
write-side barrier, and on the *natural* race within one to two iterations on
the fastest store in the repo, where the read→write window is nanoseconds.
Both branches were observed writing version 1 from a version-0 read.

**Why stand-down on exhaustion is safe.** Every status this method writes is
non-terminal. The status column is an advisory hint for the recovery poller's
`isActive()` filter; the event log is the durable truth. A lost write leaves
the instance active and recoverable, and nothing in any main source set keys
on `WAITING_*` — a stale `RUNNING` where `WAITING_SIGNAL` belonged is
indistinguishable from `WAITING_SIGNAL` to every consumer. A stale active
status costs nothing; a workflow wrongly recorded `FAILED` costs a saga.

**A caveat, stated deliberately rather than overlooked.** The retries are
immediate — no backoff, no jitter — matching the budget
`transitionToTerminal` already shares. Two branches need one retry between
them, but a wide fan-out whose branches park in lockstep gives every writer
O(N) chances to lose, so exhaustion is likelier there than the two-branch case
suggests. The consequence of exhaustion stays bounded to a stale *active*
status on an otherwise unaffected workflow, which is not worth paying for with
a second retry policy alongside the engine's existing one.

**Where.** `maestro-core/src/main/java/.../engine/InstanceStatusWriter.java`.

**Pinned by.** `ConcurrentBranchParkingTest` (`maestro-core`) — the natural
race over 15 consecutive forks, both-branches-sleep, both-branches-await, the
retry boundary, exhaustion stand-down, and two *mechanism* pins that watch the
store write ledger rather than the workflow's fate: that the loser's status
actually lands (read freshness), and that a `TERMINATED` written mid-retry
still propagates `WorkflowTerminatedException` (per-attempt guard
re-evaluation). Both mechanism pins were added after a reviewer empirically
disproved the claim that the earlier pins proved the loop executed — hoisting
the read above the loop left all five green. Plus `EnginePostgresParallelIT`
against a real Postgres. The fix is mutation-proven: hoisting the read fails
exactly the read-freshness pin; disabling the per-attempt guard fails exactly
the terminate pin.

**Incidental.** The fix also corrected a comment in `TracingParallelBranchIT`
that had **enshrined this defect as "an engine-level limitation"**.

---

### Issue 22 — Compensations can run on an operator-terminated workflow {#issue-22}

> **Open.** Narrow and pre-existing; found by the review of Issue 21's fix,
> which closed the same read-modify-write race in the sibling writer. Not
> fixed in the release-hardening cycle because it is out of that cycle's
> scope, and it is filed here so it is a known behaviour rather than a
> surprise.

**Kind:** Library defect. **Severity:** Medium (narrow window, but the
outcome contradicts a documented guarantee).

**What's wrong.** `SagaManager.transitionToCompensating`
(`maestro-core/src/main/java/.../saga/SagaManager.java`, the guard at line 542
and the swallow at line 560) has the right guard and the wrong failure mode:

```java
var latest = store.getInstance(ctx.workflowId()).orElse(instance);
if (latest.status() == WorkflowStatus.TERMINATED) {
    throw new WorkflowTerminatedException(ctx.workflowId(), null);   // correct
}
...
try {
    store.updateInstance(compensating);
} catch (OptimisticLockException e) {
    logger.debug("Optimistic lock conflict updating workflow '{}' to COMPENSATING, continuing",
            ctx.workflowId());                                        // <-- here
}
```

The read and the compare-and-set are not atomic. Sequence:

1. The guard reads a **non-terminal** status and passes.
2. A cross-node `WorkflowExecutor.terminateWorkflow` writes `TERMINATED`,
   bumping the row version.
3. The compare-and-set loses.
4. The conflict is **swallowed** (`debug`, "continuing") and the method
   returns normally.
5. `compensate()` carries on and **the compensations run**.

**Why it matters.** This contradicts the engine's own documented contract.
`InstanceStatusWriter`'s Javadoc — and the `WorkflowTerminatedException`
throw fifteen lines above the swallow (`:545` vs `:560`) — state that
`TERMINATED` means "the run must stop now, **without compensation**". Here an operator terminates a
workflow and the engine unwinds it anyway: refunds issued, reservations
released, for a workflow an operator explicitly asked to stop. The guard was
written to prevent exactly this and prevents it only when the terminate lands
*before* the read.

The window is narrow — the terminate has to land between the guard's read and
the compare-and-set, on a workflow that is entering its compensation phase —
and it is pre-existing, not introduced by this cycle. But it is a behavioural
defect, not a cosmetic one: the observable outcome is wrong.

**Where.** `maestro-core/src/main/java/.../saga/SagaManager.java`
`transitionToCompensating` — the guard at `:542`, the `OptimisticLockException`
catch at `:560`.

**How to tackle it.** The fix is the one Issue 21 already shipped for the
sibling writer, and the idiom is now proven twice in the codebase
(`WorkflowExecutor.transitionToTerminal`, `InstanceStatusWriter.write`):
replace the swallow with a **bounded retry against a fresh read**, with the
terminal guard **re-evaluated inside the loop** — so a `TERMINATED` that
appears between attempts throws `WorkflowTerminatedException` rather than
being lost. Note that the exhaustion policy differs from Issue 21's: this
write is a transition *into* an active phase, so on exhaustion the safe answer
is still to stand down (leaving the instance in its existing recoverable
state) rather than to compensate against an unread row.

**Done when.** A RED pin proves the current behaviour — a `TERMINATED` written
between the guard's read and the compare-and-set produces at least one
`COMPENSATION_STARTED` / compensation invocation — and the same pin shows zero
compensations after the fix, with `WorkflowTerminatedException` propagating.
Pin the mechanism the way Issue 21's fix was pinned: assert the store write
ledger and the compensation invocations, not merely that the workflow ended up
`TERMINATED`.

---

### Issue 23 — Maestro's Kafka beans silently disable Spring Boot's `spring.kafka.*` configuration, and `@MaestroSignalListener` drops trace context {#issue-23}

> **Open.** Found while building the demo stack (`demo/`), where it presented
> as "Jaeger shows three unrelated single-service traces". Filed rather than
> fixed: the demo cycle's remit was the stack, and the fix changes shipped
> behaviour for every Maestro + Kafka user, so it wants its own cycle with
> pinning tests. A sample-level stopgap is in place — see "Today's
> workaround".

**Kind:** Library defect (plus a documentation gap — corrected in
`docs/observability.md` §"Cross-service trace propagation (Kafka)", whose
"one connected trace" promise now carries an explicit scope limit).
**Severity:** Critical as a
product issue: it silently voids a whole family of documented Spring Boot
properties, and it breaks the cross-service trace that `docs/cross-service.md`
and `docs/observability.md` both promise.

Evidence for everything below:
`demo/.evidence/task-2-kafka-template-library-defect.log`.

**What's wrong — part 1: Maestro's beans suppress Boot's.**

`KafkaMessagingAutoConfiguration` contributes a `ProducerFactory` and a
`KafkaTemplate` unconditionally:

```java
// maestro-messaging-kafka/src/main/java/.../config/KafkaMessagingAutoConfiguration.java:105-110
@Bean
@ConditionalOnMissingBean(name = "maestroKafkaTemplate")
public KafkaTemplate<String, byte[]> maestroKafkaTemplate(
        ProducerFactory<String, byte[]> maestroKafkaProducerFactory
) {
    return new KafkaTemplate<>(maestroKafkaProducerFactory);   // bare — nothing configured
}
```

Note the condition is on the **bean name**, but Spring Boot's own beans are
conditional on the **type**. Verified in `spring-boot-kafka-4.0.5` bytecode:
`KafkaAutoConfiguration.kafkaTemplate` is
`@ConditionalOnMissingBean(KafkaTemplate.class)` and
`KafkaAutoConfiguration.kafkaProducerFactory` is
`@ConditionalOnMissingBean(ProducerFactory.class)`. So in **every** Maestro +
Kafka application, Boot's template and producer factory never exist, and every
property they are responsible for reading is silently inert:

| Property | Effect today |
|---|---|
| `spring.kafka.template.observation-enabled` | Ignored — no producer spans, no `traceparent` on user-published records |
| `spring.kafka.template.default-topic` | Ignored |
| `spring.kafka.producer.*` (serializers, `acks`, `compression-type`, `batch-size`, `properties.*`, …) | Ignored — `maestroKafkaProducerFactory` builds its own map with hardcoded `String`/`byte[]` serializers and `acks=all` |
| `spring.kafka.producer.transaction-id-prefix` | Ignored — no `KafkaTransactionManager` |

The failure mode is the bad kind: the property binds without complaint,
`/actuator/configprops` shows the value you set, and nothing happens.

**What's wrong — part 2: `@MaestroSignalListener` never extracts trace context.**

`MaestroSignalListenerBeanPostProcessor.createListenerContainer` hand-builds
`ContainerProperties`:

```java
// maestro-messaging-kafka/src/main/java/.../listener/MaestroSignalListenerBeanPostProcessor.java:213-219
var containerProps = new ContainerProperties(reg.topic());
containerProps.setGroupId(groupId);
containerProps.setAckMode(ContainerProperties.AckMode.RECORD);
containerProps.setMessageListener(
        (MessageListener<String, byte[]>) record -> handleMessage(record.value(), reg, executor, objectMapper));
var container = new ConcurrentMessageListenerContainer<>(consumerFactory, containerProps);
```

Two consequences. `spring.kafka.listener.*` (including
`observation-enabled`) cannot reach a container Boot never configured. And the
listener lambda calls `handleMessage(record.value(), …)` directly — it takes
the record's **value** and never looks at its **headers**.
`KafkaTracePropagation` exposes `runWithExtractedContext(Headers, Runnable)`
for precisely this hop; `grep -c runWithExtractedContext` over the
bean-post-processor returns **0**.

So a signal arriving on a user's own domain topic is persisted with
`trace_context = NULL` even when the record carries a valid `traceparent`.
Measured on the demo stack, with the producer side confirmed to be injecting
the header:

```
      signal_name      | rows | with_trace | null_trace
-----------------------+------+------------+------------
 underwriting.decision |    1 |          0 |          1
 verification.result   |    3 |          0 |          3
```

(`document.uploaded` and `package.signed` are also NULL in that table but are
not evidence — they enter over REST, so there is no header to extract.)

**Why it matters.** `trace_context` on the signal row is the hop designed to
survive a park, a crash and a different node — the one thing an in-memory
scope cannot do. Leaving it NULL means the durable half of the trace story is
absent exactly where the engine's value proposition lives.

**Where.**
- `maestro-messaging-kafka/src/main/java/io/b2mash/maestro/messaging/kafka/config/KafkaMessagingAutoConfiguration.java:105-110`
- `maestro-messaging-kafka/src/main/java/io/b2mash/maestro/messaging/kafka/listener/MaestroSignalListenerBeanPostProcessor.java:213-219`

**How to tackle it.**

1. **Producer.** Honour `spring.kafka.template.observation-enabled` on
   `maestroKafkaTemplate` — or, better, default observation **on** when a
   `Tracer` bean is present, matching the condition
   `TracePropagationConfiguration` already uses. Enabling it does not
   double-write the header: Spring's Kafka sender context removes before it
   adds, so the observation's `traceparent` replaces the engine's manual one
   with a child span of the same trace.
2. **Consumer.** Set `containerProps.setObservationEnabled(...)` from the
   bound listener properties, and wrap the message listener in
   `KafkaTracePropagation.runWithExtractedContext(record.headers(), …)` so the
   inbound header reaches `TraceContextHolder` and is persisted by
   `SignalManager.deliverSignal`.
3. **Decide the bean-suppression question explicitly.** Either narrow Maestro's
   beans so Boot's survive (e.g. give Maestro's a distinct type or mark them
   `@ConditionalOnMissingBean(name = …)` *and* not shadow the type), or
   document loudly that `spring.kafka.producer.*` does not apply. Silently
   swallowing a documented Spring Boot property surface is the worst of the
   three options.

**Today's workaround** (what the demo does, and what users hitting this should
do until it is fixed): define your own bean **named `maestroKafkaTemplate`**
with observation enabled. Maestro's `@ConditionalOnMissingBean(name = …)` then
backs off and engine and application traffic share one observed template:

```java
@Bean
public KafkaTemplate<String, byte[]> maestroKafkaTemplate(
        ProducerFactory<String, byte[]> maestroKafkaProducerFactory) {
    var template = new KafkaTemplate<>(maestroKafkaProducerFactory);
    template.setObservationEnabled(true);
    return template;
}
```

See `maestro-samples/sample-loan-origination/*/src/main/java/.../config/ObservedKafkaTemplateConfig.java`.
This fixes the producer side only; part 2 has no user-side workaround.

**Done when.** A RED pin shows (a) `spring.kafka.template.observation-enabled=true`
produces no `traceparent` on a record published through the injected
`KafkaTemplate`, and (b) a record delivered to an `@MaestroSignalListener`
topic **with** a valid `traceparent` persists `trace_context = NULL`. Both go
green after the fix, and a cross-service test asserts one trace id spans
producer and consumer services.

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

**What's left:** the two deliberate known limitations, plus one guarded gap.
Issues 13, 14, and 15 — none were on the original plan; all three were found
as a side effect of fixing something else nearby — are now resolved too (see
each section above for commits and pinning tests). Issue 16 — found during
13–15's own final review — is *guarded* rather than resolved: retrying a
compensated saga is refused as a safe no-op instead of being made to work;
see its section for the two design directions a real fix could take. Issue
17 — the first finding of the multi-instance verification cycle, a
routine-operation cross-node timer-wake stall — is resolved (see its
section). Issues 18, 19, and 20, all found by the chaos harness built during
the same cycle (a split-brain duplicate-append misrecorded as workflow
failure, a timed-out `awaitSignal` replaying nondeterministically after a
routine rolling restart, and a transient store outage during a parked
wake-recheck probe misrecorded as workflow failure), are also resolved (see
their sections) — Issue 20 in particular surfaced only in the PR-gate
re-proof run for Issue 19's own fix, the fourth "graceful condition recorded
as failure" defect the cycle found. Issues 11 and 12 remain open by
deliberate design decision, but both now carry measured evidence from the
same cycle — Issue 11's split-brain duplicate-side-effect count and Issue
12's benchmark — appended to their sections (see "Known limitations" below).

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
