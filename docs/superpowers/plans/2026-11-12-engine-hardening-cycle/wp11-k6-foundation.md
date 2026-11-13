# WP11 Load-Test Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A k6 load suite under `perf/` that boots Postgres, Kafka and N replicas of an unpublished `sample-perf` app at Maestro's true defaults, runs a smoke check, scenario 1 (throughput ceiling) and scenario 3 (steady DB load with 1k–10k parked runs) with one local command or a manual CI dispatch, and writes self-identifying results. It also adds the three meters the suite needs.

**Architecture:** Three isolated `feat(observe)` commits add the meters to `maestro-core`/the starter, following existing patterns. Every harness commit is `test(perf)` and touches only harness paths. The harness reads the engine through four columns of `maestro_workflow_instance`, `pg_stat_statements` and the sample's own HTTP endpoints. It never reads event rows, engine SQL text or the new meters' Java API, so WP12 can cherry-pick it onto an older engine commit. A bash driver (`perf/run.sh`) orchestrates `docker compose`, k6 (`grafana/k6:2.3.0`, run as a compose service), `psql` and `jq`. k6 only generates arrivals. Completion, latency and statement counts come from SQL the harness runs as a different Postgres role, so the harness's own queries are excluded from what is measured.

**Tech Stack:** Java 25, Spring Boot 4.0.5, Micrometer, k6 2.3.0, Docker Compose v2, Postgres 16 + `pg_stat_statements`, Kafka 3.9.0 (KRaft), Prometheus 3, bash + jq.

**Spec:** `docs/superpowers/plans/2026-11-12-engine-hardening-cycle/00-spec.md` (Lane C, WP11; D3, D4, C4). Grounding: `evidence/research-D-k6-load-tests.md`.

---

## Added public surface

**Meters** (all registered in `maestro-spring-boot-starter`, package `io.b2mash.maestro.spring.observe`; inert without a `MeterRegistry`, gated by `maestro.observability.metrics.enabled` like every existing meter):

| Meter (Micrometer → Prometheus) | Type | Tags (closed value sets) | Recorded where |
|---|---|---|---|
| `maestro.run.abandoned` → `maestro_run_abandoned_total` | Counter | `reason` = `shutdown`, `terminated`, `converged`, `terminal_write_failed`, `node_error`, `store_unavailable` | `MicrometerEngineObserver.runAbandoned` (all six `AbandonReason`s, exhaustive switch) |
| `maestro.workflow.duration` → `maestro_workflow_duration_seconds_{count,sum,max}` (+`_bucket` when the host enables a histogram) | Timer | `workflow` = workflow type; `outcome` = `completed`, `failed` | `MicrometerEngineObserver.workflowFinished` (new callback, below) |
| `maestro.taskqueue.slots.used` → `maestro_taskqueue_slots_used` | Gauge | `queue` = task-queue name of a registered `@DurableWorkflow`; `kind` = `workflow`, `activity` | `MaestroTaskQueueGauges` (new class), one gauge per (bounded queue, kind), registered on `ContextRefreshedEvent` |

The catalog grows from 17 meters (14 counters, 1 timer, 2 gauges) to 20 (15 counters, 2 timers, 3 gauges).

**`maestro-core` API (Spring-free, additive):**
- `EngineObserver`: `default void workflowFinished(WorkflowInfo w, WorkflowStatus status, Duration elapsed) {}`. It is fired only by the node that won the terminal transition, right after `workflowCompleted`/`workflowFailed` on the same thread. `status` is `COMPLETED` or `FAILED`. `elapsed` = now − the instance row's `startedAt`, clamped at `Duration.ZERO`, so it includes park time and any downtime before recovery. It is never fired for a converged loser, a stand-down, an abandon or a terminate.
- `CompositeEngineObserver`: forwards `workflowFinished`.
- `TaskQueueGovernor`: `public OptionalInt workflowSlotsInUse(String taskQueue)` and `public OptionalInt activitySlotsInUse(String taskQueue)`. Each returns limit − available permits, and empty for an unbounded queue. Like the existing `available*Slots`, a call on an unconfigured queue under default limits creates that queue's pools.
- The Javadoc of `EngineObserver.runAbandoned` changes. "Counting observers should not implement this" becomes "count it under its own meter; never fold `TERMINATED` into a terminate counter".

**Starter:** a new `public final class MaestroTaskQueueGauges implements ApplicationListener<ContextRefreshedEvent>` and its bean `maestroTaskQueueGauges` in `MaestroObservabilityAutoConfiguration.MetricsConfiguration`.

**Not added:** no config property, no SPI method, no migration, no `*Info` record, no new enum.

**Outside the library (unpublished):** module `maestro-samples:sample-perf` with its HTTP endpoints (Task 4), the top-level `perf/` directory, `.github/workflows/perf.yml`, one line in `build-test.yml`'s keepalive job, and one module line in `CLAUDE.md`'s module structure.

## Global Constraints

- Java 25; Spring Boot 4.0.5 via `maestro.spring-app-conventions`; Jackson 3 (`tools.jackson`, annotations from `com.fasterxml.jackson.annotation` only); JSpecify `@Nullable`; no Lombok; Javadoc on every public type.
- `maestro-core` gains no Spring or Micrometer dependency.
- k6 is pinned to **2.3.0** (image `grafana/k6:2.3.0`). No `latest` tag anywhere, and every image tag is exact. k6 scripts import only built-in modules (`k6`, `k6/http`, `k6/execution`): no extensions, no remote jslib.
- CI: `workflow_dispatch` plus at most a weekly schedule. **Never** `pull_request` or `push`.
- D4: the suite produces numbers first. Performance pass/fail rests only on statement counts per workflow and on comparisons within one run. Latency and throughput thresholds are wide and advisory.
- Kafka-delivered signals are out of scope. Signals go over REST (`deliverSignal` + `NOTIFY`).
- `sample-perf` runs Maestro's true defaults. It lists no `maestro.worker.*`, `maestro.signal.*`, `maestro.recovery.*`, `maestro.retention.*` or `maestro.lock.*` property and sizes no Hikari pool. It sets `maestro.admin.events.enabled` **explicitly** from `PERF_ADMIN_EVENTS` (harness default `true`, today's engine default). Every result reports both the requested and the effective value.
- Kafka topics are pre-created by `kafka-init` and the broker runs with `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`.
- **Commit isolation, so WP12 can run this harness on an older engine:**
  - Meter commits are `feat(observe): …` and touch only `maestro-core`, `maestro-spring-boot-starter`, `docs/observability.md`, `docs/operations.md` and `docs/release-notes.md`.
  - Harness commits are `test(perf): …` and touch only `perf/`, `maestro-samples/sample-perf/` and `settings.gradle.kts`.
  - The CI commit is `ci(perf): …`. Docs commits are `docs(…)`.
  - `sample-perf` Java code references no API added in this plan.
  - The PR must be merged with a merge commit, not squashed, as #49 was, so the `test(perf)` commits survive on `main`.
- Branch `test/k6-load-foundation` from `origin/main`, in a dedicated worktree at `/Users/rakheendama/Projects/2026/maestro/.claude/worktrees/k6-load-foundation` (written `$WT` below). Never `cd` into the main checkout. Run `git -C /Users/rakheendama/Projects/2026/maestro status` before the first edit and after every commit; it must stay clean.
- `./gradlew --no-watch-fs` always. No `git stash`. Never run two Gradle invocations on one module in one tree. Commit at every checkpoint. Chain every edit → commit with `&&`, and pass commit messages as `-m "$MSG"`, never as a top-level heredoc.
- Existing tests change only where a step names them (Tasks 1–3 name each one).
- New workflow files keep `actions/*@v4` like the current files; WP13 bumps all pins.
- A command that runs longer than about 9 minutes is launched detached with `nohup … > <log> 2>&1 &` and watched with bounded foreground polls (`timeout 540 bash -c 'until grep -q "^VERDICT" <log>; do sleep 15; done'`). Every result directory identifies itself, so a run that outlives its agent is still usable.
- `docs/test-plan.md`'s fencing sentence (§4) belongs to WP13. Touch only the "performance/load testing" phrase there.

## Review Focus

1. **A stale or foreign JVM or jar serves the run.** The run must be INVALID, never PASS. The check: each node's `/perf/info.buildCommit` must equal the commit `run.sh` baked into the jar, there must be exactly `--replicas` distinct `hostname`s, and the same hostnames and `jvmStartMillis` must be present at the end. Pinned in Task 5 Step 6 (`PERF_EXPECT_COMMIT=deadbeef` → exit 2, `validity.identity` INVALID).
2. **The harness's own SQL counted as engine load.** Only `userid = 'maestro'::regrole` calls are measured, and the harness runs as `postgres`. Pinned in Task 5 Step 5: the smoke result shows `harnessCalls > 0` and `maestroCalls` excluding them.
3. **A saturated load generator reported as the SUT's ceiling.** Any `dropped_iterations > 0` makes the run INVALID. Pinned in Task 6 Step 4 (`PERF_K6_VUS=1 PERF_K6_MAX_VUS=1` → INVALID `validity.droppedIterations`).
4. **Leftover state from an earlier run contaminates a result.** Every run is cold (`down -v` first), and every SQL query is scoped to the run's id prefix. Pinned in Task 9 Step 2: two consecutive smokes; the second sees zero instance rows outside its prefix, and its `pg_stat_statements` reset time is after its own start.
5. **The requested admin-events setting is not what ran.** A mismatch between requested and effective is INVALID. Effective means `/perf/info.adminEventsEnabled` plus the `maestro.admin.events` topic end offset (> 0 if enabled, 0 if disabled). Pinned in Task 5 Step 7 (`PERF_ADMIN_EVENTS=false` smoke → offset 0, PASS).

---

## File structure

```
maestro-core/src/main/java/io/b2mash/maestro/core/observe/EngineObserver.java          (modify: workflowFinished, runAbandoned Javadoc)
maestro-core/src/main/java/io/b2mash/maestro/core/observe/CompositeEngineObserver.java (modify: forward workflowFinished)
maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java         (modify: 2 emissions + elapsed helper)
maestro-core/src/main/java/io/b2mash/maestro/core/engine/TaskQueueGovernor.java        (modify: Bucket keeps limits; 2 in-use methods)
maestro-spring-boot-starter/.../observe/MicrometerEngineObserver.java                  (modify: runAbandoned, workflowFinished)
maestro-spring-boot-starter/.../observe/MaestroTaskQueueGauges.java                    (create)
maestro-spring-boot-starter/.../observe/MaestroObservabilityAutoConfiguration.java     (modify: bean)
docs/observability.md, docs/operations.md (:916), docs/release-notes.md (Unreleased)  (modify)

settings.gradle.kts                                                                    (modify: include sample-perf)
maestro-samples/sample-perf/build.gradle.kts
maestro-samples/sample-perf/src/main/java/io/b2mash/maestro/samples/perf/
    PerfApplication.java  package-info.java
    workflow/PerfChainWorkflow.java  workflow/PerfParkWorkflow.java
    workflow/ChainInput.java  workflow/ParkInput.java
    activity/PerfActivities.java  activity/SleepingPerfActivities.java
    web/PerfController.java  web/StartRequest.java  web/StartRequestValidator.java  web/PerfInfo.java
maestro-samples/sample-perf/src/main/resources/application.yml
maestro-samples/sample-perf/src/test/java/io/b2mash/maestro/samples/perf/
    PerfWorkflowsTest.java  StartRequestValidatorTest.java

perf/compose.yml  perf/.gitignore  perf/run.sh
perf/lib/common.sh  perf/lib/evaluate.sh
perf/scenarios/smoke.sh  perf/scenarios/s1-throughput.sh  perf/scenarios/s3-parked.sh
perf/eval/smoke.jq  perf/eval/s1-throughput.jq  perf/eval/s3-parked.jq  perf/eval/common.jq
perf/k6/lib/common.js  perf/k6/start-batch.js  perf/k6/start-ramp.js  perf/k6/start-rate.js  perf/k6/signal-batch.js
perf/sql/pgss-reset.sql  perf/sql/pgss-window.sql  perf/sql/run-status.sql  perf/sql/latency.sql
perf/sql/s1-levels.sql  perf/sql/pg-settings.sql  perf/sql/foreign-rows.sql
perf/postgres/init.sql  perf/prometheus/prometheus.yml  perf/prometheus/queries.txt
perf/baselines/s1-throughput.json  perf/baselines/s3-parked.json          (Task 9, recorded, not hand-written)
.github/workflows/perf.yml  .github/workflows/build-test.yml (keepalive line)
docs/test-plan.md  CLAUDE.md (module line)  perf/.evidence/wp11/*.result.json  (Task 9)
```

---

### Task 1: `maestro.run.abandoned{reason}` counter

**Files:**
- Modify: `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/observe/MicrometerEngineObserver.java` (add `runAbandoned`, class-Javadoc catalog row)
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/observe/EngineObserver.java:215-217` (Javadoc paragraph only)
- Modify: `docs/observability.md` (counters table; "Seventeen meters" → "Eighteen meters: 15 counters and 1 timer … 2 gauges"; "implements 13 of the 23" → "14 of the 23"; replace the `runAbandoned` bullet under "Which callbacks the meter adapter implements" with a paragraph on the counter that keeps the NODE_ERROR/STORE_UNAVAILABLE alerting guidance and now points at the meter)
- Modify: `docs/operations.md:916` (`runAbandoned` has no meter → alert on `maestro_run_abandoned_total{reason="store_unavailable"}`); `docs/release-notes.md` Unreleased (line ~79's "`runAbandoned` has no meter" parenthetical, plus one new bullet for the counter)
- Test: `maestro-spring-boot-starter/src/test/java/io/b2mash/maestro/spring/observe/MicrometerEngineObserverTest.java`, `MaestroObservabilityAutoConfigurationTest.java`

**Interfaces:**
- Consumes: `EngineObserver.runAbandoned(WorkflowInfo, AbandonReason)` (existing).
- Produces: meter `maestro.run.abandoned`, tag `reason` ∈ {`shutdown`,`terminated`,`converged`,`terminal_write_failed`,`node_error`,`store_unavailable`}.

- [ ] **Step 0: Create the worktree (first task only)**

```bash
git -C /Users/rakheendama/Projects/2026/maestro fetch origin && \
git -C /Users/rakheendama/Projects/2026/maestro worktree add -b test/k6-load-foundation \
  /Users/rakheendama/Projects/2026/maestro/.claude/worktrees/k6-load-foundation origin/main && \
git -C /Users/rakheendama/Projects/2026/maestro/.claude/worktrees/k6-load-foundation status && \
git -C /Users/rakheendama/Projects/2026/maestro status
```
Expected: the new worktree is on `test/k6-load-foundation` and both trees are clean. If `origin/main` already contains WP0 (the executor builder), new tests construct executors through whatever helper the file they extend uses.

- [ ] **Step 1: Write the failing tests**

In `MicrometerEngineObserverTest`, add `runAbandonedCountsByReason()`. It calls `observer.runAbandoned(info, r)` once for each of the six `AbandonReason` constants and once more for `STORE_UNAVAILABLE`, then asserts:
- `registry.get("maestro.run.abandoned").tag("reason", "store_unavailable").counter().count() == 2.0`;
- each of the other five tag values counts `1.0`;
- `registry.get("maestro.run.abandoned").counters()` has 6 entries, and each one's `getId().getTags()` has exactly one tag, `reason` (no `workflow` tag).

In `MaestroObservabilityAutoConfigurationTest`, add `runAbandonedCountsATerminateOnTheWorkflowThread()`. Using the existing `runner` with `SimpleMeterRegistry` and the existing `ParkingActivityWorkflow`: start `abandon-terminated-1`, await `WAITING_SIGNAL`, `client.getWorkflow("abandon-terminated-1").terminate("wp11")`, then await `maestro.run.abandoned{reason=terminated} == 1.0`, and assert `maestro.workflow.terminated{workflow=<type>} == 1.0`. Both meters move, each once.

- [ ] **Step 2: Run them to verify they fail**

Run: `cd /Users/rakheendama/Projects/2026/maestro/.claude/worktrees/k6-load-foundation && ./gradlew --no-watch-fs :maestro-spring-boot-starter:test --tests '*MicrometerEngineObserverTest' --tests '*MaestroObservabilityAutoConfigurationTest'`
Expected: FAIL. Both new tests throw `MeterNotFoundException` for `maestro.run.abandoned`.

- [ ] **Step 3: Implement `runAbandoned(WorkflowInfo w, AbandonReason reason)` in `MicrometerEngineObserver`**

`increment("maestro.run.abandoned", TAG_REASON, abandonTag(reason))`, where `private static String abandonTag(AbandonReason)` is an exhaustive `switch` with no `default`, mirroring `reasonTag`. Add the catalog row to the class Javadoc. Rewrite `EngineObserver.runAbandoned`'s "Counting observers should not implement this" paragraph to say: count it under its own meter; `workflowTerminated` already fires once per terminate on the operator's thread, so folding `TERMINATED` into a terminate counter double-counts. Make the doc edits listed under Files.

- [ ] **Step 4: Run the tests to verify they pass**

Same command. Expected: PASS. Then `./gradlew --no-watch-fs :maestro-core:test :maestro-spring-boot-starter:test`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd /Users/rakheendama/Projects/2026/maestro/.claude/worktrees/k6-load-foundation && \
git add maestro-spring-boot-starter maestro-core/src/main/java/io/b2mash/maestro/core/observe/EngineObserver.java docs/observability.md docs/operations.md docs/release-notes.md && \
git commit -m "feat(observe): count abandoned local runs by reason (maestro.run.abandoned)" && \
git -C /Users/rakheendama/Projects/2026/maestro status --short | wc -l
```
Expected last line: `0`.

---

### Task 2: `workflowFinished` callback and `maestro.workflow.duration` timer

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/observe/EngineObserver.java` (new default method under "Workflow lifecycle")
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/observe/CompositeEngineObserver.java` (forward it, with containment like its siblings)
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java` (after `emit("workflowCompleted", …)` at ~2441 and `emit("workflowFailed", …)` at ~3010; new `private static Duration elapsedSince(WorkflowInstance instance)`)
- Modify: `maestro-spring-boot-starter/.../observe/MicrometerEngineObserver.java`
- Modify: `docs/observability.md` (Timers table; counts become "Nineteen meters: 15 counters and 2 timers … 2 gauges" and "15 of the 24"; the replay-invariant section says a recovered run's duration spans its downtime); `docs/release-notes.md` Unreleased
- Test (existing, named changes): `maestro-core/src/test/java/io/b2mash/maestro/core/observe/RecordingEngineObserver.java` (add `record WorkflowFinish(WorkflowInfo info, WorkflowStatus status, Duration elapsed)` and `List<WorkflowFinish> finished()`); `CompositeEngineObserverTest.everyCallbackFansOut` and its `invokeEveryCallback` helper (add the call and `assertEquals(1, recorder.finished().size(), "workflowFinished")`)
- Test (new methods): `maestro-core/src/test/java/io/b2mash/maestro/core/engine/WorkflowExecutorObserverTest.java`, `MicrometerEngineObserverTest.java`, `MaestroObservabilityAutoConfigurationTest.java`

**Interfaces:**
- Produces: `EngineObserver.workflowFinished(WorkflowInfo w, WorkflowStatus status, Duration elapsed)`; meter `maestro.workflow.duration` with tags `workflow`, `outcome` ∈ {`completed`,`failed`}.

- [ ] **Step 1: Write the failing tests**
  - `WorkflowExecutorObserverTest.finishedCarriesStatusAndElapsedSinceStart`: a two-step workflow whose second activity sleeps 200 ms completes. Assert `observer.finished()` has exactly one entry with `status == COMPLETED`, `info.workflowId()` equal to the started id, and `elapsed >= 200 ms` and `< 10 s`. Assert, through the recorder's invocation log (add a public `invocations()` accessor if there is none), that `workflowFinished` is the invocation directly after `workflowCompleted`.
  - `WorkflowExecutorObserverTest.failedRunFinishesWithFailedStatus`: the existing `ThrowingWorkflow`. Assert one `finished()` entry with `status == FAILED`.
  - `WorkflowExecutorObserverTest.convergedLoserDoesNotFinish`: reuse the existing converged-loser setup in this file or in `WorkflowTerminalStateGuardTest`. If neither offers a reusable one, pre-finalise the row in the `VersionedInMemoryStore` before the terminal write. Assert `finished()` is empty and `abandoned()` holds one `CONVERGED`.
  - `MicrometerEngineObserverTest.workflowFinishedRecordsDurationByOutcome`: record 150 ms COMPLETED and 75 ms FAILED. Assert timer `count()` is 1 for each outcome and `totalTime(MILLISECONDS)` is 150 and 75. A third call with any other status (`TERMINATED`) records nothing.
  - `MaestroObservabilityAutoConfigurationTest.workflowDurationRecordedThroughRealEngineRun`: `startAndWait` the echo workflow, then assert `maestro.workflow.duration{workflow=ObservabilityEchoWorkflow,outcome=completed}` count is 1.
  - `CompositeEngineObserverTest.everyCallbackFansOut`: the named addition above.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew --no-watch-fs :maestro-core:test --tests '*WorkflowExecutorObserverTest' --tests '*CompositeEngineObserverTest'`. Expected: compile failure, because `workflowFinished`/`finished()` are undefined.

- [ ] **Step 3: Implement**
  - Add the `EngineObserver` default method with the Javadoc contract from "Added public surface".
  - Forward it in `CompositeEngineObserver`.
  - In `WorkflowExecutor`, directly after each winner-only emission, call `emit("workflowFinished", ctx.workflowId(), () -> observer.workflowFinished(observed(instance), WorkflowStatus.COMPLETED|FAILED, elapsedSince(instance)))`. `elapsedSince` is `Duration.between(instance.startedAt(), Instant.now())` floored at `Duration.ZERO`; it uses `Instant.now()` because `transitionToTerminal` stamps `completedAt` with it.
  - In `MicrometerEngineObserver.workflowFinished`, `record("maestro.workflow.duration", elapsed, TAG_WORKFLOW, w.workflowType(), TAG_OUTCOME, "completed"|"failed")`, and return without recording for any other status.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew --no-watch-fs :maestro-core:test :maestro-spring-boot-starter:test`. Expected: BUILD SUCCESSFUL. Then `./gradlew --no-watch-fs :maestro-integration-tests:test --tests '*Observer*'`. Expected: PASS. `RecordingObserver` is unaffected because the callback is a default method.

- [ ] **Step 5: Commit** (`feat(observe): workflow start-to-terminal duration timer (maestro.workflow.duration)`), with the same `git add` scope and main-checkout check as Task 1.

---

### Task 3: slot-usage gauge `maestro.taskqueue.slots.used`

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/TaskQueueGovernor.java`. `Bucket` becomes `record Bucket(String name, TaskQueueLimits limits, Semaphore workflowSlots, Semaphore activitySlots)`. Add the two in-use methods next to `available*Slots`.
- Create: `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/observe/MaestroTaskQueueGauges.java`
- Modify: `MaestroObservabilityAutoConfiguration.MetricsConfiguration`. Add the bean `@Bean @ConditionalOnBean({MeterRegistry.class, WorkflowExecutor.class}) MaestroTaskQueueGauges maestroTaskQueueGauges(MeterRegistry registry, WorkflowExecutor executor, WorkflowRegistrar registrar)`.
- Modify: `docs/observability.md` (Gauges table and its "how sourced" bullets; count "Twenty meters: 15 counters and 2 timers … 3 gauges"); `docs/release-notes.md` Unreleased
- Test: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/TaskQueueGovernorTest.java` (new methods), `MaestroObservabilityAutoConfigurationTest.java` (new methods)

**Interfaces:**
- Produces: `TaskQueueGovernor.workflowSlotsInUse(String) : OptionalInt` and `activitySlotsInUse(String) : OptionalInt`; `new MaestroTaskQueueGauges(MeterRegistry registry, WorkflowExecutor executor, WorkflowRegistrar registrar)`. On `onApplicationEvent(ContextRefreshedEvent)` it registers, for each distinct `registration.taskQueue()` in `registrar.getRegistrations().values()` whose `workflowSlotsInUse` is present, two gauges with `.strongReference(true)`: `kind=workflow` reads `workflowSlotsInUse(q).orElse(0)` and `kind=activity` reads `activitySlotsInUse(q).orElse(0)`. Registration is idempotent, because Micrometer deduplicates by id.

- [ ] **Step 1: Write the failing tests**
  - `TaskQueueGovernorTest.inUseTracksAcquireParkAndRelease`: limits (2, 3) on `q`. Initially both are 0. After `acquireWorkflowSlot("q","a")` workflow is 1. During `parked("a", …)`, observed inside the callable, it is 0. After the park returns it is 1. After `acquireActivitySlot("q")` ×2 activity is 2. After both releases activity is 0. After `releaseWorkflowSlot("a")` workflow is 0.
  - `TaskQueueGovernorTest.inUseIsEmptyForUnboundedQueues`: `TaskQueueGovernor.UNBOUNDED.workflowSlotsInUse("x")` and `activitySlotsInUse("x")` are `OptionalInt.empty()`.
  - `TaskQueueGovernorTest.inUseUsesDefaultLimitsForUnconfiguredQueue`: `new TaskQueueGovernor(Map.of(), new TaskQueueLimits(20, 20))`. `workflowSlotsInUse("default")` is `OptionalInt.of(0)`, and `OptionalInt.of(1)` after one acquire.
  - `MaestroObservabilityAutoConfigurationTest.slotGaugesTrackRealRuns`: `ParkingActivityWorkflow` (queue `default`). Before the start, `maestro.taskqueue.slots.used{queue=default,kind=workflow}` is 0. Once the run parks (`WAITING_SIGNAL`) the workflow gauge is still 0, because a parked run gives its slot back. That proves the gauge reads the governor rather than `runningCount`. Assert both gauges exist with exactly the tags `queue` and `kind`. To see a non-zero value, add a test-local workflow whose activity blocks on a static `CountDownLatch`: while it is blocked, workflow = 1 and activity = 1; release the latch and both return to 0.
  - `MaestroObservabilityAutoConfigurationTest.noSlotGaugesWhenUnbounded`: with `maestro.worker.default-concurrency=0` and `maestro.worker.default-activity-concurrency=0`, the context starts and `registry.find("maestro.taskqueue.slots.used").gauges()` is empty.
  - The real-chain test `wiresThroughRealBootMetricsAutoConfigurationChain` gets one named added assertion: `hasSingleBean(MaestroTaskQueueGauges.class)`.

- [ ] **Step 2: Run them to verify they fail.** `./gradlew --no-watch-fs :maestro-core:test --tests '*TaskQueueGovernorTest'` fails to compile, because the methods are undefined.
- [ ] **Step 3: Implement** the governor methods, the class and the bean as specified. Javadoc on `MaestroTaskQueueGauges` covers why it registers on `ContextRefreshedEvent`: `WorkflowRegistrar` fills its registrations in `afterSingletonsInstantiated`, after this bean is constructed. It also states that the gauges are node-local, sum across pods, and do not exist for an unbounded queue.
- [ ] **Step 4: Run** `./gradlew --no-watch-fs :maestro-core:test :maestro-spring-boot-starter:test`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** (`feat(observe): per-task-queue slot usage gauge (maestro.taskqueue.slots.used)`). Then run the whole build once, alone: `./gradlew --no-watch-fs build`. Expected: BUILD SUCCESSFUL.

---

### Task 4: `sample-perf` application

**Files:** everything under `maestro-samples/sample-perf/`, plus `settings.gradle.kts` (add `"maestro-samples:sample-perf"` after `sample-payment-gateway`).

**Interfaces (the wire contract every k6 script and WP12 rely on):**

| Endpoint | Request | Response |
|---|---|---|
| `POST /perf/workflows` | `{"workflowId": str, "type": "chain"\|"park", "activities"?: int, "activityMillis"?: long, "parkMode"?: "signal"\|"sleep", "parkSeconds"?: long}` | **202** `{"workflowId": str}`; **400** `{"error": str}` on validation; **409** `{"error": str}` on `WorkflowAlreadyExistsException` |
| `POST /perf/workflows/{id}/signals/{name}` | `{"payload"?: str}` | **202** `{"workflowId": str, "signal": str}` (via `MaestroClient.getWorkflow(id).signal(name, payload)`) |
| `GET /perf/info` | — | **200** `PerfInfo` (below) |
| `GET /actuator/health`, `/actuator/info`, `/actuator/prometheus` | — | Boot defaults; exposure `health,info,prometheus` |

Validation (`StartRequestValidator.validate(StartRequest) : Optional<String>`, empty means valid):
- `workflowId` matches `[A-Za-z0-9._-]{1,200}`.
- `chain`: `activities` is 1..50 (default 5) and `activityMillis` is 0..10000 (default 50).
- `park`: `parkMode` is `signal`|`sleep` (default `signal`) and `parkSeconds` is 1..86400 (default 7200).
- Any other `type` is an error.

`PerfInfo` record fields:
- `buildCommit` from `BuildProperties.get("gitCommit")`, else `"unknown"`;
- `buildTime` and `version` (from `BuildProperties`);
- `hostname`, `pid`, `jvmStartMillis` (`RuntimeMXBean.getStartTime()`), `javaVersion`, `availableProcessors`, `maxHeapBytes`;
- `serviceName`, `adminEventsEnabled` (`MaestroProperties.getAdmin().events().enabled()`);
- `workerDefaultConcurrency`, `workerDefaultActivityConcurrency`, `workerTaskQueues` (names list) from `getWorker()`;
- `wakeRecheckInterval` and `recoveryPollInterval` (ISO-8601 strings);
- `hikariMaximumPoolSize` (`dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize()`, or `-1` if not Hikari).

Workflows (no `@Component`; the starter registers `@DurableWorkflow` classes; default task queue):
- `@DurableWorkflow(name = "perf-chain") PerfChainWorkflow`. `@WorkflowMethod public int run(ChainInput input)` calls `activities.step(i, input.activityMillis())` for `i` in `[0, input.activities())` and returns the number of steps. `record ChainInput(int activities, long activityMillis)`.
- `@DurableWorkflow(name = "perf-park") PerfParkWorkflow`. `@WorkflowMethod public String run(ParkInput input)` runs `step(0, 0)`. In `signal` mode it then calls `awaitSignal("perf.release", String.class, Duration.ofSeconds(parkSeconds))` and returns `"released"`, or `"timeout"` on `SignalTimeoutException`. In `sleep` mode it calls `sleep(Duration.ofSeconds(parkSeconds))` and returns `"slept"`. Then it runs `step(1, 0)` and returns. `record ParkInput(String parkMode, long parkSeconds)`.
- `@Activity interface PerfActivities { int step(int index, long millis); }`. `@Component SleepingPerfActivities` calls `Thread.sleep(millis)` when `millis > 0` (activity code, not workflow code) and returns `index + 1`. Stubs are `@ActivityStub(startToCloseTimeout = "PT30S")`.

`build.gradle.kts`:
- `maestro.spring-app-conventions`;
- `implementation`: starter, store-postgres, messaging-kafka, `libs.spring.boot.starter.webmvc`, `libs.spring.boot.starter.actuator`, `libs.spring.boot.starter.jdbc` (compile scope, for `HikariDataSource`);
- `runtimeOnly`: `libs.postgresql`, `libs.spring.boot.starter.kafka`, `libs.micrometer.registry.prometheus`;
- `testImplementation(project(":maestro-test"))`;
- `tasks.bootJar { archiveFileName = "sample-perf.jar" }`;
- `springBoot { buildInfo { properties { additional.put("gitCommit", providers.gradleProperty("perfGitCommit").orElse("unset")) } } }`.

It is not added to `maestro-bom` or `aggregateJavadoc`, and it applies no publishing plugin.

`application.yml`:
- `spring.application.name: sample-perf`;
- datasource from `SPRING_DATASOURCE_*` env with local defaults; `spring.kafka.bootstrap-servers: ${SPRING_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`;
- `server.port: 8080`;
- `maestro.service-name: perf`, `maestro.store.type: postgres`, `maestro.messaging.type: kafka`, `maestro.messaging.topics.signals: maestro.signals.perf`;
- `maestro.admin.events.enabled: ${PERF_ADMIN_EVENTS:true}`;
- `management.endpoints.web.exposure.include: health,info,prometheus`;
- `management.metrics.distribution.percentiles-histogram`: `maestro.workflow.duration: true` and `maestro.activity.duration: true`. This is a harmless key on an engine without the timer.
- Nothing else under `maestro.*`. A comment at the top says why: true defaults; every addition must be justified in `docs/test-plan.md`.

- [ ] **Step 1: Write the failing tests.**
  - `PerfWorkflowsTest` (TestWorkflowEnvironment):
    - `chainRunsEveryActivity`: `ChainInput(5, 0)` completes with result 5 and five recorded `step` calls with indices 0..4.
    - `parkSignalReleases`: awaits `WAITING_SIGNAL`, signals `perf.release`, completes `"released"`.
    - `parkSignalTimesOut`: `parkSeconds 1` plus a clock advance; completes `"timeout"`.
    - `parkSleepCompletes`: `sleep` mode plus a clock advance; completes `"slept"`.
    - `chainSurvivesCrash`: crash after step 2, recover, result 5, and `step` was not re-invoked for indices 0–1.
  - `StartRequestValidatorTest`: one case per rule above, asserting the exact error text, plus the two "defaults applied" cases.
- [ ] **Step 2: Run** `./gradlew --no-watch-fs :maestro-samples:sample-perf:test`. Expected: compile FAIL.
- [ ] **Step 3: Implement** to the contract above.
- [ ] **Step 4: Run** the same command, then `./gradlew --no-watch-fs :maestro-samples:sample-perf:bootJar -PperfGitCommit=abc123 && unzip -p maestro-samples/sample-perf/build/libs/sample-perf.jar META-INF/build-info.properties | grep -x 'build.gitCommit=abc123'`. Expected: PASS, and the grep prints the line. Then `./gradlew --no-watch-fs verifyModuleTestCoverage`. Expected: SUCCESS.
- [ ] **Step 5: Commit** `test(perf): sample-perf app — knob-driven chain and park workflows at engine defaults`. `git add settings.gradle.kts maestro-samples/sample-perf` only. Check `git show --stat HEAD`: no other paths.

---

### Task 5: compose stack, `run.sh`, identity, evaluation framework and the `smoke` scenario

**Files:** `perf/compose.yml`, `perf/.gitignore` (`results/`), `perf/postgres/init.sql`, `perf/prometheus/prometheus.yml`, `perf/prometheus/queries.txt`, `perf/run.sh`, `perf/lib/common.sh`, `perf/lib/evaluate.sh`, `perf/eval/common.jq`, `perf/eval/smoke.jq`, `perf/scenarios/smoke.sh`, `perf/k6/lib/common.js`, `perf/k6/start-batch.js`, and the SQL files `pgss-reset`, `pgss-window`, `run-status`, `latency`, `pg-settings`, `foreign-rows`.

**Compose (`name: maestro-perf`):**

| Service | Image | Key settings |
|---|---|---|
| `postgres` | `postgres:16-alpine` | `POSTGRES_USER/PASSWORD=postgres`; command `postgres -c shared_preload_libraries=pg_stat_statements -c pg_stat_statements.track=all -c pg_stat_statements.track_utility=on -c pg_stat_statements.max=10000 -c max_connections=300`; `init.sql` runs `CREATE ROLE maestro LOGIN PASSWORD 'maestro'`, `CREATE DATABASE maestro OWNER maestro`, `\connect maestro`, `CREATE EXTENSION pg_stat_statements`; `pg_isready` healthcheck; no host port |
| `kafka` | `apache/kafka:3.9.0` | the loan sample's KRaft env without the EXTERNAL listener; `KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"`; same healthcheck |
| `kafka-init` | `apache/kafka:3.9.0` | creates `maestro.signals.perf`, `maestro.signals.perf.DLT`, `maestro.admin.events` (3 partitions, RF 1) with `--if-not-exists`; `restart: "no"` |
| `node` | `eclipse-temurin:25-jre` | `command: ["java","-jar","/app/app.jar"]`; volume `../maestro-samples/sample-perf/build/libs/sample-perf.jar:/app/app.jar:ro`; env `SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/maestro`, user/password `maestro`, `SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092`, `PERF_ADMIN_EVENTS=${PERF_ADMIN_EVENTS:-true}`, `JAVA_TOOL_OPTIONS=-Xmx${PERF_NODE_HEAP:-1g}`; `cpus: ${PERF_NODE_CPUS:-2}`; `mem_limit: ${PERF_NODE_MEM:-1536m}`; `depends_on` postgres `service_healthy`, kafka-init `service_completed_successfully`; no host port; scaled with `--scale node=N` (containers `maestro-perf-node-<i>`) |
| `prometheus` | `prom/prometheus:v3.5.0` | 5 s scrape; `dns_sd_configs: [{names: [node], type: A, port: 8080, refresh_interval: 5s}]`, `metrics_path: /actuator/prometheus`; port `127.0.0.1:${PERF_PROMETHEUS_PORT:-59090}:9090` for humans only |
| `k6` (profile `tools`) | `grafana/k6:2.3.0` | volumes `./k6:/scripts:ro`, `${PERF_RESULTS_DIR}:/results`; `user: "${PERF_UID:-1000}:${PERF_GID:-1000}"` (writable results on Linux CI) |
| `curl` (profile `tools`) | `curlimages/curl:8.11.1` | the harness's in-network HTTP client (nodes have no host ports) |

**`run.sh` contract:**
- Usage: `perf/run.sh <smoke|s1-throughput|s3-parked> [--profile local|full] [--replicas N] [--record-baseline]`.
- Env: `PERF_ADMIN_EVENTS` (default `true`), `PERF_KEEP_UP=1` (skip the final `down -v`), `PERF_ENGINE_REF` (a free-text label), and the test-only overrides `PERF_EXPECT_COMMIT`, `PERF_K6_VUS`, `PERF_K6_MAX_VUS`.
- Exit codes: `0` PASS, `1` FAIL, `2` INVALID, `3` harness error.
- Last stdout line: `VERDICT <PASS|FAIL|INVALID|ERROR> <resultsDir>`.

Sequence:
1. Set `RUN_ID=r$(date -u +%Y%m%dt%H%M%Sz)` (no dashes; ids are `${RUN_ID}-<phase>-…`) and `RESULTS=perf/results/$RUN_ID`. **Write `identity.json` before anything else**, containing:
   - `runId`, `scenario`, `profile`, `startedAtUtc`, `pwd`;
   - `repo`:
     - `head`;
     - `dirty` (`git status --porcelain -- . ':!perf/results'` non-empty);
     - `shallow`;
     - `engineCommit` = `git log -1 --format=%H -- maestro-core maestro-spring-boot-starter maestro-store-jdbc maestro-store-postgres maestro-messaging-kafka`;
     - `harnessCommit` = `git log -1 --format=%H -- perf maestro-samples/sample-perf`;
     - `engineRefLabel`;
   - `host`: `uname -a`, CPU count, memory MB, `docker version` server, `docker compose version`;
   - `images`: every tag above;
   - `config`: `replicas`, `adminEventsRequested`, `nodeHeap`, `nodeCpus`, `nodeMem`.
2. Preflight: `docker`, `docker compose` v2 and `jq` are present; Docker memory below 6 GiB → a WARN in `identity.notes`.
3. Cold: `docker compose -p maestro-perf -f perf/compose.yml down -v --remove-orphans`.
4. Build: `EXPECTED_COMMIT=${PERF_EXPECT_COMMIT:-$HEAD$([ dirty ] && echo +dirty)}`. Run `./gradlew --no-watch-fs :maestro-samples:sample-perf:bootJar -PperfGitCommit=$HEAD[+dirty]`, then assert the jar's `build-info.properties` carries it (binary provenance before start).
5. `up -d --scale node=$REPLICAS postgres kafka kafka-init node prometheus`. Wait up to 180 s for every `maestro-perf-node-<i>` to answer `/actuator/health` with `"status":"UP"` through the `curl` service.
6. Collect `/perf/info` from each node into `identity.nodes`. The validity check `validity.identity` passes only when every `buildCommit == EXPECTED_COMMIT`, the node count equals `REPLICAS` and the hostnames are distinct.
7. `scenario_run` from `perf/scenarios/<scenario>.sh` writes `measurements.json`.
8. Post-collection:
   - collect `/perf/info` again; `validity.noRestart` requires the same hostnames and `jvmStartMillis`;
   - admin topic end offset, summed via `kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic maestro.admin.events` (fallback `kafka-run-class.sh org.apache.kafka.tools.GetOffsetShell`);
   - `effective` block: `adminEventsEffective` (from `/perf/info`), `adminTopicEndOffset`, pool size and worker defaults from node 1;
   - Prometheus `query_range` (step 5 s, run window) for each line of `queries.txt` into `prometheus.json`, listing empty results under `emptyQueries`;
   - node 1's raw `/actuator/prometheus` into `node-1-scrape.txt`;
   - `docker compose logs --no-color` into `compose.log`;
   - `pg-settings.sql` into `pg-settings.json`.
9. `perf/lib/evaluate.sh` runs `jq -n --slurpfile` over identity, measurements, the baseline (if any) and `perf/eval/<scenario>.jq` (which imports `common.jq`). It writes `result.json` (schema below) and `summary.md` (an identity header that always prints `adminEventsRequested`/`adminEventsEffective`, replicas, pool size, both commits and the dirty flag, then a check table). It prints the summary.
10. `--record-baseline`: when the verdict is PASS (a NO_BASELINE baseline gate allowed) and `dirty` is false, write `perf/baselines/<scenario>.json` from the result; refuse otherwise (exit 3).
11. Unless `PERF_KEEP_UP=1`: `down -v`. A `trap` on EXIT always collects logs and tears down; on an unexpected error it writes `VERDICT ERROR`.

**SQL files** (each returns one JSON value; run as `postgres` via `docker compose exec -T postgres psql -U postgres -d maestro -At -v ON_ERROR_STOP=1 -v prefix=… -f -`):
- `pgss-reset`: `pg_stat_statements_reset()`, then `{"resetAt": stats_reset}` from `pg_stat_statements_info`.
- `pgss-window`: `{resetAt, snapshotAt: now(), windowSecs, dealloc, maestroCalls, harnessCalls, top: [25 by calls for userid=maestro {calls, rows, totalMs, query: first 200 chars, whitespace collapsed}]}`. `maestroCalls` is `sum(calls) WHERE userid = 'maestro'::regrole`; `harnessCalls` is all other users.
- `run-status`: `{total, byStatus: {STATUS: n}}` for `workflow_id LIKE :'prefix' || '%'`.
- `latency`: over COMPLETED rows of the prefix, `{completed, p50Ms, p95Ms, p99Ms, maxMs}` of `completed_at − started_at` (`percentile_cont`).
- `foreign-rows`: count of instance rows **not** matching `:'prefix'`.
- `pg-settings`: `version()`, `max_connections`, `shared_buffers`, `pg_stat_statements.*`.

The harness touches only `maestro_workflow_instance(workflow_id, status, started_at, completed_at)` and `pg_stat_statements`. It never matches engine SQL text in any check.

**`result.json` (schema 1, pinned for WP12):** `{"schema":1, "identity":{…}, "scenario", "profile", "measurements":{…}, "checks":[{"id","class":"validity|correctness|gate|advisory","status":"PASS|FAIL|INVALID|NO_BASELINE|WARN","value","limit","detail"}], "verdict":"PASS|FAIL|INVALID"}`. The verdict is INVALID if any validity check is INVALID, else FAIL if any correctness or gate check is FAIL, else PASS. Advisory checks never change the verdict.

Common validity checks (`common.jq`, every scenario):
- `validity.identity`;
- `validity.noRestart`;
- `validity.adminEvents`: requested == `/perf/info` == (offset > 0);
- `validity.pgssComplete`: `dealloc == 0` in every window;
- `validity.k6`: every k6 exit code ∈ {0, 99}, where 99 means "advisory thresholds crossed" and is recorded, not failed.

Advisory everywhere: `advisory.dirtyTree` (WARN if dirty).

**k6:**
- `lib/common.js` exports:
  - `nodes()`, parsed from `__ENV.PERF_NODES` (`http://maestro-perf-node-1:8080,…`);
  - `nodeFor(i)` (round-robin);
  - `startWorkflow(base, body, tags)` (checks `status === 202`);
  - `signalWorkflow(base, id, name, tags)`;
  - `summary(phase)`, a `handleSummary` factory returning `{"/results/k6-<phase>.json": JSON.stringify(data), stdout: <one line per key metric>}`.
- `start-batch.js`: `shared-iterations`, `vus=PERF_VUS`, `iterations=PERF_COUNT`, `maxDuration: PERF_MAX_DURATION`. The id is `${PERF_ID_PREFIX}${PERF_ID_OFFSET + exec.scenario.iterationInTest}` and the body comes from `JSON.parse(__ENV.PERF_BODY)` plus `workflowId`. `discardResponseBodies: true`. Tag `name: start`.

**Smoke scenario** (`scenarios/smoke.sh`):
1. pgss reset.
2. `start-batch.js` with 20 `chain` workflows (`activities 2`, `activityMillis 10`, prefix `${RUN_ID}-smoke-`).
3. Wait up to 120 s (psql `run-status` every 2 s) for all to be terminal.
4. `pgss-window`, `latency`, `foreign-rows`.

Checks (`eval/smoke.jq`):
- `correctness.allCompleted` (COMPLETED == 20, FAILED == 0);
- `correctness.noForeignRows` (== 0);
- `gate.statementsMeasured`: `maestroCalls / completed` in [5, 500], `harnessCalls > 0`;
- `advisory.latency` (p95 recorded, always PASS).

- [ ] **Step 1: Pull and record every image tag.** `docker pull` each image above. If a tag does not exist, pin the newest existing exact tag of the same line and note it in the commit message. Never use `latest`.
- [ ] **Step 2: Write the compose file, init SQL, Prometheus config and `queries.txt`.** `queries.txt` holds, one PromQL per line:
  - `sum by (instance) (rate(maestro_workflow_completed_total[30s]))`
  - `maestro_taskqueue_slots_used`
  - `maestro_workflows_running`
  - `maestro_workflows_parked`
  - `hikaricp_connections_active`
  - `hikaricp_connections_pending`
  - `histogram_quantile(0.95, sum by (le) (rate(maestro_workflow_duration_seconds_bucket[30s])))`
  - `sum by (reason) (increase(maestro_run_abandoned_total[1m]))`
  - `sum by (instance) (jvm_memory_used_bytes{area="heap"})`
  - `process_cpu_usage`

  Verify: `docker compose -p maestro-perf -f perf/compose.yml config -q`. Expected: exit 0.
- [ ] **Step 3: Write `run.sh`, `lib/*.sh`, the SQL, k6 and jq files and `scenarios/smoke.sh`.** Lint: `docker run --rm -v "$PWD/perf:/mnt" koalaman/shellcheck:v0.10.0 /mnt/run.sh /mnt/lib/common.sh /mnt/lib/evaluate.sh /mnt/scenarios/smoke.sh` (expected: no findings) and `docker compose -p maestro-perf -f perf/compose.yml --profile tools run --rm k6 inspect /scripts/start-batch.js` (expected: JSON options printed, exit 0).
- [ ] **Step 4: First cold smoke.** `perf/run.sh smoke`. Expected: `VERDICT PASS`. `jq .checks results/<id>/result.json` shows every check PASS (with `advisory.dirtyTree` WARN, since the tree is uncommitted).
- [ ] **Step 5: Prove harness exclusion.** `jq '.measurements.window | {maestroCalls, harnessCalls}' result.json`. Expected: both > 0. `jq '.measurements.window.top[].query' result.json | grep -ci 'pg_stat_statements\|maestro_workflow_instance WHERE workflow_id LIKE'`. Expected: `0`, meaning none of the harness's queries are in the measured set.
- [ ] **Step 6: Prove the identity check.** `PERF_EXPECT_COMMIT=deadbeef perf/run.sh smoke; echo "exit=$?"`. Expected: `exit=2` and `jq -r '.checks[]|select(.id=="validity.identity").status'` → `INVALID`.
- [ ] **Step 7: Prove the admin-events check.** `PERF_ADMIN_EVENTS=false perf/run.sh smoke`. Expected: PASS, `.identity.effective.adminTopicEndOffset == 0` and `.identity.effective.adminEventsEffective == false`. Also prove the new meters fire at runtime: `grep -c 'maestro_workflow_duration_seconds_count\|maestro_taskqueue_slots_used' results/<id>/node-1-scrape.txt`. Expected: ≥ 2.
- [ ] **Step 8: Commit** `test(perf): compose stack, run.sh driver, identity stamping and smoke scenario`. `git add perf`. `git show --stat HEAD` lists only `perf/`.

---

### Task 6: Scenario 1 — throughput ceiling at the 20/20 defaults and Boot's default pool

**Files:** `perf/k6/start-ramp.js`, `perf/sql/s1-levels.sql`, `perf/scenarios/s1-throughput.sh`, `perf/eval/s1-throughput.jq`.

**Definition:**
- One node by default, true defaults, Hikari 10.
- Workflow `chain`, `activities 5`, `activityMillis 50`, ids `${RUN_ID}-s1-L<level>-<i>`.
- Executor `ramping-arrival-rate`, `timeUnit: '1s'`, `startRate` = first level. Stages are, per level, `{target: L, duration: ramp}` then `{target: L, duration: hold}`.
- The level label in the id is computed from `Date.now() - exec.scenario.startTime` against the same stage table. The ramp into L counts as L.
- `discardResponseBodies: true`.
- k6 thresholds are advisory only: `'http_req_failed{name:start}': ['rate<0.01']`, `'http_req_duration{name:start}': ['p(95)<2000']`.

| Profile | Levels (starts/s) | Ramp / hold | preAllocatedVUs / maxVUs | Drain timeout |
|---|---|---|---|---|
| `local` | 5, 20, 40, 80 | 5 s / 40 s | 50 / 200 | 300 s |
| `full` | 5, 10, 20, 40, 60, 80, 120 | 10 s / 120 s | 100 / 400 | 1200 s |

`PERF_K6_VUS`/`PERF_K6_MAX_VUS` override the VU columns (used by the Review Focus #3 test).

**Run:**
1. pgss reset.
2. k6 `start-ramp.js` (phase `s1`).
3. Wait (psql every 5 s) until no row of the prefix is non-terminal, or the drain timeout.
4. `pgss-window`, `run-status`, `latency`, `s1-levels`.

`s1-levels.sql` groups the prefix's rows by `split_part(workflow_id,'-',3)`. For each level, the window `[ws, we]` is that level's `min/max(started_at)`. It returns, ordered by level:
- `{level, arrivals, windowSecs, arrivalPerSec, completionsInWindowPerSec (rows of ANY level with completed_at in [ws, we]) / windowSecs, p50Ms, p95Ms, p99Ms (this level's completed rows), failed}`.

**Checks (`eval/s1-throughput.jq`):**

| id | class | rule |
|---|---|---|
| `validity.droppedIterations` | validity | `metrics.dropped_iterations.values.count` absent or 0 → PASS, else INVALID ("generator saturated, not the SUT") |
| `correctness.allCompleted` | correctness | COMPLETED == accepted starts (k6 `checks` passes on `name:start`) and FAILED == 0 and no non-terminal rows left; drain timeout → FAIL with the residue |
| `gate.statementsPerWorkflow` | gate | `maestroCalls / completed ≤ 1.10 × baseline.statementsPerWorkflow`. NO_BASELINE if `perf/baselines/s1-throughput.json` is absent or its `workflowShape` ≠ `{activities:5, activityMillis:50}`. A value < 0.80 × baseline is WARN ("baseline stale: re-record as a named step"), never FAIL |
| `advisory.ceiling` | advisory | `ceilingPerSec` = max `completionsInWindowPerSec`; `kneeLevel` = highest level, from the first upward without a gap, with `completionsInWindowPerSec ≥ 0.95 × arrivalPerSec`; levels above it are flagged `saturated` |
| `advisory.startLatency` | advisory | k6 `http_req_duration{name:start}` p95 < 2000 ms → PASS else WARN |
| `advisory.littlesLaw` | advisory | the model cap `20 / (p50Ms of the first level / 1000)` runs/s next to `ceilingPerSec` (an explanation, never a verdict) |

Measurements also record `statementsPerWorkflow`, `harnessCalls`, `windowSecs`, the levels table, and k6 `iterations`, `http_reqs` and `http_req_duration{name:start}` p50/p95/p99.

- [ ] **Step 1: Write the k6 script.** `k6 inspect` it with `PERF_PROFILE=local`. Expected: `scenarios.s1.stages` has 8 entries and `startRate` 5.
- [ ] **Step 2: Write the SQL, scenario and jq.** Run shellcheck on the scenario file. Expected: clean.
- [ ] **Step 3: First cold local run.** `perf/run.sh s1-throughput --profile local` (about 6 min, foreground with `timeout 590`). Expected: `VERDICT PASS` with `gate.statementsPerWorkflow` NO_BASELINE, and `jq '.measurements.levels|length'` = 4 with every `arrivals > 0`.
- [ ] **Step 4: Prove generator saturation is INVALID.** `PERF_K6_VUS=1 PERF_K6_MAX_VUS=1 perf/run.sh s1-throughput --profile local; echo "exit=$?"`. Expected: `exit=2` and `validity.droppedIterations` INVALID.
- [ ] **Step 5: Commit** `test(perf): scenario 1 — throughput ceiling at the default 20/20 bounds` (`perf/` only).

---

### Task 7: Scenario 3 — steady DB load at 1k–10k parked runs

**Files:** `perf/k6/start-rate.js`, `perf/k6/signal-batch.js`, `perf/scenarios/s3-parked.sh`, `perf/eval/s3-parked.jq`.

**Definition:**
- One node by default.
- Park mode `PERF_PARK_MODE` (default `signal`; `sleep` supported). `parkSeconds 7200`, so no deadline fires during a run.
- Parked ids are `${RUN_ID}-park-<n>`, with `n` continuous across plateaus via `PERF_ID_OFFSET`. Probes are `${RUN_ID}-probe-p<P>-<i>` (`chain`, `activities 3`, `activityMillis 20`).
- `start-rate.js` is `constant-arrival-rate` (`rate`, `timeUnit: '1s'`, `duration`, `preAllocatedVUs: 10`, `maxVUs: 50`).
- `signal-batch.js` is `shared-iterations` over `${PERF_ID_PREFIX}<0..PERF_COUNT-1>`. It sends signal `i` to `nodeFor(i + 1)`. Run `i` was started on `nodeFor(i)`, so with two or more replicas every signal lands on a node that does not own the run, and the wake must cross nodes through `NOTIFY`.
- `settle` = the node's `/perf/info.wakeRecheckInterval` + 5 s, read at runtime, never assumed.

| Profile | Plateaus P | Idle window | Hold per plateau | Probe | Seed VUs |
|---|---|---|---|---|---|
| `local` | 1000, 2000 | 60 s | 90 s | 2/s for 30 s | 50 |
| `full` | 1000, 5000, 10000 | 120 s | 300 s | 2/s for 60 s | 50 |

**Run:**
1. Idle phase (P = 0): pgss reset, sleep idle window, `pgss-window` → `windows[0]`. Then the probe at P = 0, waiting until all probes are terminal (≤ 120 s), then `latency` for the probe prefix.
2. For each plateau P:
   - seed `P − P_prev` parked runs with `start-batch.js` (`maxDuration 10m`);
   - wait until the parked status count (`WAITING_SIGNAL`, or `WAITING_TIMER` for `sleep`) for `${RUN_ID}-park-` equals P (timeout 600 s → `correctness.plateauReached` FAIL, and the scenario stops);
   - sleep `settle`;
   - pgss reset, sleep hold, `pgss-window` → `windows[P]`;
   - probe as above.
3. Release check: `signal-batch.js` sends `perf.release` to `${RUN_ID}-park-0..99`, then wait ≤ 120 s for those 100 rows to be COMPLETED.

**Derived (`eval/s3-parked.jq`):** `cps(P) = maestroCalls / windowSecs`; `perParkedCallsPer30s(P) = (cps(P) − cps(0)) × 30 / P`.

| id | class | rule |
|---|---|---|
| `validity.droppedIterations` | validity | over every `start-rate` phase, as in S1 |
| `correctness.plateauReached` | correctness | every plateau reached its exact parked count |
| `correctness.releaseWakes` | correctness | 100/100 released runs COMPLETED with result `released` within 120 s (proves the parked runs are live, across nodes when `--replicas > 1`) |
| `correctness.noFailures` | correctness | FAILED == 0 over the run's whole prefix |
| `gate.parkedCostLinear` | gate (within run) | `perParked(Pmax) ≤ 1.25 × perParked(Pmin)` |
| `gate.parkedCostBaseline` | gate | `perParked(Pmax) ≤ 1.10 × baseline.perParkedCallsPer30s`. NO_BASELINE if the baseline is absent or its `parkMode`/`wakeRecheckInterval` differ |
| `advisory.probeLatency` | advisory | probe p95 at Pmax ≤ 2 × probe p95 at P = 0 → PASS else WARN |
| `advisory.heapPerParked` | advisory | (heap at the Pmax hold − heap at the idle window) / Pmax from `prometheus.json`, INFO only |

Measurements record each window's `{P, windowSecs, maestroCalls, cps, perParkedCallsPer30s, top}` and each probe's latency.

- [ ] **Step 1: Write the two k6 scripts** and `k6 inspect` both. Expected: exit 0, with executors `constant-arrival-rate` and `shared-iterations`.
- [ ] **Step 2: Write the scenario and jq.** Shellcheck clean.
- [ ] **Step 3: First cold local run.** Launch detached: `nohup perf/run.sh s3-parked --profile local > perf/results/launch-s3-$(date -u +%H%M%S).log 2>&1 &`, then poll with bounded waits. Expected: `VERDICT PASS` with both baseline gates NO_BASELINE. `jq '.measurements.windows|map(.P)'` → `[0,1000,2000]`, and every `perParkedCallsPer30s > 0`.
- [ ] **Step 4: Two-replica path.** `nohup perf/run.sh s3-parked --profile local --replicas 2 …`. Expected: PASS, `identity.nodes|length == 2`, and `correctness.releaseWakes` PASS with `measurements.release.crossNode == true` (set when replicas > 1). By the `nodeFor(i + 1)` rule, every one of those 100 wakes crossed nodes.
- [ ] **Step 5: Commit** `test(perf): scenario 3 — steady store load with 1k–10k parked runs`.

---

### Task 8: CI job

**Files:** create `.github/workflows/perf.yml`; modify `.github/workflows/build-test.yml` (keepalive job: add the step `gh workflow enable perf.yml`, and add the job to the comment).

**`perf.yml`:**
- `name: Perf (k6)`. A header comment says why the job is never on PRs (D4, shared 4-vCPU runners) and that `keep-nightly-enabled` re-enables it.
- `on`: `workflow_dispatch` with inputs:
  - `scenario` (choice `smoke`|`s1-throughput`|`s3-parked`, default `s1-throughput`);
  - `profile` (choice `local`|`full`, default `full`);
  - `replicas` (string, default `'1'`);
  - `admin_events` (choice `'true'`|`'false'`, default `'true'`).
- `schedule`: `- cron: '0 4 * * 6'` (Saturday 04:00 UTC; clear of the 02:30 nightly and the Sunday 01:00 soak).
- `permissions: contents: read`; `concurrency: { group: perf, cancel-in-progress: false }`.
- Job `perf`: `runs-on: ubuntu-latest`, `timeout-minutes: 90`, `strategy.fail-fast: false`.
  - `matrix.scenario: ${{ github.event_name == 'schedule' && fromJSON('["s1-throughput","s3-parked"]') || fromJSON(format('["{0}"]', inputs.scenario)) }}`.
- Steps:
  - `actions/checkout@v4` with `fetch-depth: 0` (the identity stamp needs path history);
  - `actions/setup-java@v4` (25, temurin);
  - `gradle/actions/setup-gradle@v4`;
  - install jq;
  - `perf/run.sh "${{ matrix.scenario }}" --profile "${{ inputs.profile || 'full' }}" --replicas "${{ inputs.replicas || '1' }}"` with env `PERF_ADMIN_EVENTS: ${{ inputs.admin_events || 'true' }}`;
  - `cat perf/results/*/summary.md >> "$GITHUB_STEP_SUMMARY"` (`if: always()`);
  - `actions/upload-artifact@v4` (`if: always()`, name `perf-${{ matrix.scenario }}-${{ github.run_id }}`, path `perf/results/`, `retention-days: 14`).

- [ ] **Step 1: Write both edits.**
- [ ] **Step 2: Lint.** `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:1.7.7 .github/workflows/perf.yml .github/workflows/build-test.yml`. Expected: no output, exit 0. `grep -nE 'pull_request|push:' .github/workflows/perf.yml`. Expected: no match.
- [ ] **Step 3: Commit** `ci(perf): manual-dispatch and weekly k6 load job`. The first real dispatch can only happen after merge, because GitHub dispatches workflows from the default branch. Record it under "Post-merge" in the review section.

---

### Task 9: Documentation, cold runs, baselines and the cherry-pick proof

**Files:** `docs/test-plan.md`, `CLAUDE.md` (module structure: `├── sample-perf ← Load-test target for perf/ (k6); unpublished, engine defaults`), `perf/baselines/s1-throughput.json`, `perf/baselines/s3-parked.json`, `perf/.evidence/wp11/<runId>.result.json` (copies of each cited run's `result.json`).

`docs/test-plan.md`:
- In §4, delete only ", performance/load testing" from the out-of-scope sentence, and add one sentence after the paragraph: "Performance/load testing moved into scope 2026-11-12 — see §1 *Load suite*."
- Add a subsection "### Load suite (k6, `perf/`)" at the end of §1 covering:
  - the one-line command per scenario;
  - prerequisites (Docker with ≥ 6 GiB, `docker compose` v2, `jq`, JDK 25);
  - profiles;
  - the four check classes and the exact gate rules from Tasks 5–7;
  - where results go and the `result.json` schema;
  - the CI dispatch;
  - how WP12 adds a scenario (a `scenarios/*.sh` + `eval/*.jq` + k6 script, nothing else);
  - the older-engine recipe (worktree at the engine commit, `git cherry-pick $(git log --reverse --format=%H --grep='^test(perf)' origin/main)`, run, cite both commits from `identity.repo`);
  - the first numbers table from this task's full runs, each figure with the `runId` it came from.

- [ ] **Step 1: Commit everything so the tree is clean** (`docs(test-plan): bring the k6 load suite into scope` for test-plan and CLAUDE.md). `git status --porcelain` must be empty, or baseline recording refuses.
- [ ] **Step 2: Evidence identity (Review Focus #4).** `perf/run.sh smoke && perf/run.sh smoke`. On the second result: `foreign-rows == 0` (`correctness.noForeignRows` PASS), and its `pgss` `resetAt` is later than its own `identity.startedAtUtc`. Then `perf/run.sh smoke --replicas 2`. Expected: PASS with two distinct hostnames.
- [ ] **Step 3: Record baselines.** `perf/run.sh s1-throughput --profile local --record-baseline` (foreground, `timeout 590`) and the detached `perf/run.sh s3-parked --profile local --record-baseline`. Both must be PASS. Inspect `perf/baselines/*.json`: each carries the source `runId`, `engineCommit`, `harnessCommit`, `profile`, the workflow shape or park mode, and the number. Commit `test(perf): record scenario 1 and 3 baselines`.
- [ ] **Step 4: Prove the gates against the committed baselines.** Re-run both local scenarios cold. Expected: `gate.statementsPerWorkflow`, `gate.parkedCostLinear` and `gate.parkedCostBaseline` all PASS (not NO_BASELINE).
- [ ] **Step 5: Full-profile characterisation.** Detached `perf/run.sh s1-throughput --profile full`, then `perf/run.sh s3-parked --profile full`, one at a time (about 25 and 35 min). Record the verdicts and numbers. A FAIL here is data, not a reason to edit thresholds. Report it, and stop and ask before changing any rule.
- [ ] **Step 6: Cherry-pick proof (WP12's precondition).**
  ```bash
  WT=/Users/rakheendama/Projects/2026/maestro/.claude/worktrees/k6-load-foundation && \
  git -C "$WT" worktree add --detach /Users/rakheendama/Projects/2026/maestro/.claude/worktrees/k6-cherry cbf5a2a && \
  cd /Users/rakheendama/Projects/2026/maestro/.claude/worktrees/k6-cherry && \
  git cherry-pick $(git -C "$WT" log --reverse --format=%H --grep='^test(perf)' origin/main..test/k6-load-foundation) && \
  perf/run.sh smoke && perf/run.sh s1-throughput --profile local
  ```
  Expected: the cherry-picks apply without conflict, and both runs end PASS or (S1) PASS/FAIL on the baseline gate only. Check `identity.repo.engineCommit == cbf5a2a…` and `harnessCommit` ≠ it. `prometheus.json.emptyQueries` lists the three new-meter queries, which is correct for that engine. Then remove the cherry worktree with `git worktree remove`, as its own call.
- [ ] **Step 7: Archive and record.** Copy every cited run's `result.json` to `perf/.evidence/wp11/` and fill the Review section below. Every number in it must be greppable from one of those files: run `grep -c '<number>' perf/.evidence/wp11/*.json` for each before writing it. Commit `docs(perf): WP11 run evidence` (`perf/.evidence` and, if present in the worktree, this plan file). If this plan file is not in the worktree (the docs branch has not merged), put the filled Review block in the final report for the coordinator to paste here.
- [ ] **Step 8: Final gate.** `./gradlew --no-watch-fs build`, run alone. Expected: BUILD SUCCESSFUL. `git log --format='%s' origin/main..HEAD`: every subject starts with `feat(observe)`, `test(perf)`, `ci(perf)` or `docs(`. `for c in $(git log --format=%H --grep='^test(perf)' origin/main..HEAD); do git show --stat --format= $c; done | grep -vE '^ (perf/|maestro-samples/sample-perf/|settings.gradle.kts)|changed'`. Expected: no output.

---

## Review (filled by the Task 9 implementer)

**Machine:** `identity.host` of the cited runs (CPUs, memory, Docker version). **Commits:** engine `…`, harness `…`.

| Run | runId | Profile / replicas | adminEvents req/eff | Verdict | Key numbers |
|---|---|---|---|---|---|
| smoke ×2 (+2 replicas) | | local / 1, 1, 2 | | | statements/workflow; foreignRows |
| S1 baseline record | | local / 1 | | | statementsPerWorkflow; ceilingPerSec; kneeLevel |
| S1 gate re-run | | local / 1 | | | gate value vs baseline |
| S3 baseline record | | local / 1 | | | perParkedCallsPer30s @1000/@2000; linearity ratio |
| S3 gate re-run | | local / 1 | | | |
| S1 full | | full / 1 | | | levels table (arrivals/s, completions/s, p95) |
| S3 full | | full / 1 | | | perParked @1k/5k/10k; probe p95 ratio; heap/parked |
| Cherry-pick @ cbf5a2a | | local / 1 | | | engineCommit, harnessCommit, emptyQueries |

**Post-merge:** dispatch `Perf (k6)` once with `scenario=smoke` and record the run URL. Spec C4 also requires re-running S1 and S3 after WP7 with `PERF_ADMIN_EVENTS=false` (see the note to the coordinator in the hand-off).
