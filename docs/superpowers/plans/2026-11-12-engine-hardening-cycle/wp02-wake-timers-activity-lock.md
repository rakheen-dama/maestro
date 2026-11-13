# WP2 Wake Permits, Timer Clock, Activity Lock Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A node holds a wake permit only for a workflow it is running (3.2). Due timers are compared with the database clock, and every node WARNs when its clock is off by more than 1 s (3.7). The per-activity lock and its lock key are gone (3.10, D2).

**Architecture:** `ParkingLot` takes a predicate from the executor: its `runningWorkflows::containsKey`. It stores a permit only when that predicate holds both before and after the put. All four wake sources (signal delivery, a cross-node `NOTIFY`, a timer fire or cancel, an await-deadline transition) go through one gated `wake(workflowId, key)` that carries no payload. A new default SPI overload, `getDueTimers(int)`, lets the Postgres store compare `fire_at <= now()` in SQL. The poller calls that overload; every other store keeps today's caller-clock behaviour through the default. A small `ClockSkewCheck` on every node's poller loop reads a second new default SPI method, `storeTime()`. The activity lock's acquire and release go away; the proxy factory's signatures stay and ignore their lock arguments.

**Tech Stack:** Java 25, `maestro-core` (no Spring), `maestro-store-jdbc`, `maestro-store-postgres` (Testcontainers `postgres:16-alpine`), `maestro-test`, `maestro-spring-boot-starter` (Micrometer), JUnit 5.

**Spec:** `docs/superpowers/plans/2026-11-12-engine-hardening-cycle/00-spec.md`: the WP2 row, §2 anti-goals, §6, D2. Evidence: `evidence/audit-B-signal-replay-correctness.md` §§ 3.2, 3.7, 3.10, and `evidence/repro-tests/AuditB32WakePermitLeakTest.java.txt`. Siblings:
- `wp00-executor-builder.md`: its "Adding an option later" and its migration rule.
- `wp01-replay-fidelity.md`: house style, and the existing-tests check.
- `wp09-schema-safety.md`: the `SeededHistoryUpgradeIT` pin kinds.

All line numbers below are for `main` @ `834a9da`. Re-check them after cutting the branch.

## Added, changed and removed public surface

**Added**

| Surface | Exact shape | Task |
|---|---|---|
| Executor accessor | `public int WorkflowExecutor.wakePermitCount()`: the number of wake permits this node holds. It is a node-local gauge source next to `parkedCount()`. | 1 |
| Gauge | `maestro.wake.permits`, no tags (Prometheus `maestro_wake_permits`). Registered by `MaestroEngineGauges` with `.strongReference(true)`. Source: `WorkflowExecutor.wakePermitCount()`. | 1 |
| SPI method | `default List<WorkflowTimer> WorkflowStore.getDueTimers(int batchSize)`. Contract: the `PENDING` timers due now **by the store's own clock**, ordered by `fireAt` ascending, at most `batchSize`. Default body: `return getDueTimers(Instant.now(), batchSize);`, which is the caller's clock and today's behaviour. A decorating store should forward it; one that does not degrades to the default. | 3 |
| SPI method | `default Optional<Instant> WorkflowStore.storeTime()`: the store's current time, used only for the clock-skew check. Default `Optional.empty()` means the store has no clock of its own, and then no check runs. It may throw what any read throws. | 4 |
| JDBC hooks | `protected @Nullable String AbstractJdbcWorkflowStore.getDueTimersByStoreClockSql()` and `protected @Nullable String storeTimeSql()`. Both default to `null`, which means the store has no clock: `getDueTimers(int)` then falls back to `getDueTimers(Instant.now(), n)`, and `storeTime()` returns empty. `PostgresWorkflowStore` overrides both. | 3, 4 |
| WARN log line | `ClockSkewCheck`'s fixed copy, under Task 4 Step 3. It never carries a workflow id. | 4 |

**Changed behaviour** (no new property, exception or migration)

- **Wake permits (3.2).** A wake that finds no parked waiter stores a permit only for a workflow that runs on this node (D-1). A permit no longer carries the signal payload.
- **Timer polling (3.7).** On Postgres the poller's due-timer statement compares `fire_at <= now()`. Its text changes from `… WHERE fire_at <= ? AND status = 'PENDING' …` to `… WHERE fire_at <= now() AND status = 'PENDING' …` (D-2).
- **Skew statement (3.7).** Each node issues one `SELECT now()` when its timer poller starts and every 10 minutes after that.
- **Live activities (3.10).** A live activity issues no `tryAcquire` or `release`, which is 2 statements fewer per live activity on Postgres (D-4).

**Removed**

- The lock key `maestro:lock:activity:{workflowId}:{seq}`. It is removed from the code, from `CLAUDE.md` and from every doc that lists it (Task 5).
- `ActivityInvocationHandler`'s private `acquireLock`/`releaseLock`, and its `distributedLock` and `lockKeyPrefix` fields.
- **Kept but ignored:** the `distributedLock` and `lockKeyPrefix` parameters of the six `ActivityInvocationHandler` constructors and the six `ActivityProxyFactory.createProxy` overloads. They stay so that the ~100 call sites (≈90 of them tests, many of which WP3–WP6 also edit) compile unchanged. A new issue (`Issue N1`) records dropping them behind a builder, as Issue 37 did for the executor.
- **No property is removed or becomes dead.** `maestro.lock.key-prefix` and `maestro.lock.ttl` still configure the instance lock. The repo's removed-property convention, an `IllegalStateException` at startup (`MaestroAutoConfiguration.java:257`, `PostgresStoreAutoConfiguration.java:150`), therefore does not apply.

**Doc rules:** none added or renumbered. `docs/pitfalls.md:90` changes wording (Task 5).

## Decisions

### D-1. The permit rule (3.2)

**Rule.** The decision is the executor's existing `runningWorkflows` map (`WorkflowExecutor.java:160`, a `ConcurrentHashMap<String, RunningWorkflow>`). Its entry is put on the launching thread before `thread.start()` (`:2127`), and it is removed in the run's `finally` (`:2408`) before `parkingLot.clearPending` (`:2412`). The same map already answers `isRunning`, the recovery skip and terminate.

`ParkingLot` is built as `new ParkingLot(runningWorkflows::containsKey)`, so the map must be assigned before the lot (`:237` vs `:258`). `wake(workflowId, key)` then does the following:
1. If a waiter is registered at `key`, complete it with `null`. This needs no check: only the node running the workflow registers a waiter.
2. Otherwise, if `runsHere(workflowId)` is false, store nothing (log at DEBUG) and return.
3. Otherwise, put the permit and run today's reverse-race hand-off.
4. Then re-check `runsHere(workflowId)`. If it is now false, `pendingWakes.remove(key, MARKER)`.

`containsKey` on a `ConcurrentHashMap` is lock-free and gives happens-before with the put and remove of the same key, so the check needs no extra synchronisation.

**Why a dropped permit never loses a wake.** These are all the cases in which step 2 drops one:

| Case | Why nothing is lost |
|---|---|
| The workflow runs on another node | A permit here could never be consumed: the waiter parks there. It is woken by `NOTIFY` (signals) or by its own store re-read every `maestro.signal.wake-recheck-interval` (signals and timers: `SignalManager.java:897`, `DefaultWorkflowOperations.java:539`). That is today's path; today's permit on this node is dead weight. |
| The workflow runs nowhere: crashed, not yet adopted, or finished | The signal row or the `FIRED`/`CANCELLED` timer row is durable. Whichever node later runs the workflow reads the store before its first park: `awaitOutcome` step 1 reads the unconsumed signals before parking (`:897` before `:987`), and a replayed `sleep` reads its row before re-parking (`DefaultWorkflowOperations.java:392`). A live `sleep` creates its row only after the run registered, so no fire can precede it. |
| The workflow is about to start or be adopted here and is not yet registered | Same as the row above. The put (`:2127`) precedes the run's first park, and the first park is preceded by a store read. |

The race the permit exists for is still covered. A run registered here that has read the store but not yet parked has `runsHere` true, so its permit is kept, exactly as today. Task 2 pins this for a signal and for a sleep timer, each at a 30 s re-check interval.

**The run-end race.** Take a delivery that sees `runsHere` true, while the run ends concurrently. Either the re-check at step 4 sees the run gone and removes the permit, or it still saw the run. In the second case the put came before `runningWorkflows.remove`, which itself comes before `clearPending`, so `clearPending` removes it. No interleaving leaves one behind (Task 2, `aRunEndingBetweenTheCheckAndThePut_leavesNoPermit`). The launch-failure catch (`:2138-2141`) removes the map entry without `clearPending`. It gains that one call. This is traced only: no test can make `thread.start()` fail.

**The cross-node `NOTIFY` path.** `PostgresWakeListener.dispatch` already drops a notification with no local subscriber (`PostgresWakeListener.java:275-279`). A notification that looked up its subscriber just before the await unsubscribed could still reach `onRemoteSignal` (`SignalManager.java:1257`) after the run ended. It now goes through the same gate (Task 2, `aNotificationArrivingAfterTheRunEnded_leavesNoPermit`).

**No payload.** Every park's caller discards the value the park returns: `parkChunk` at `SignalManager.java:1013-1016` and `parkForTimer` at `DefaultWorkflowOperations.java:512-515`. Both re-read the store. So `wake` takes no payload, and a permit is a key plus a shared marker. Today each leaked permit is as large as the signal.

**No bound or TTL.** After the gate, every retained permit belongs to a run on this node, and it is dropped at that run's next park, at its end, at a terminate or at shutdown. A bound would be safe, because an evicted permit costs one re-check interval and never a wake. But there is nothing left for it to bound. The gauge (Task 1) makes any regression visible.

**API.** These are package-private:
- `ParkingLot()` keeps "every workflow counts as running here", which is today's behaviour, for the eight existing tests that build a lot or a `SignalManager` directly.
- The two-argument `unpark(String key, @Nullable Object payload)` stays as the ungated test seam that `ParkingLotTest` and `SignalManagerTest` use.

Production code calls only `wake`; Task 2 Step 7 greps for this.

### D-2. The timer clock (3.7): compared on the database clock, armed as today

`fire_at` is **compared** on the database clock at poll time (`fire_at <= now()`). It is **not computed** there. This is why:

- **Replay and determinism.**
  - An await's deadline is memoized in `AwaitSignalDetail.deadline`, computed on the scheduling node (`SignalManager.java:712`).
  - The owner's self-fire compares that memo with its own instant source (`:926`).
  - A replay re-arms a missing row at the memoized deadline (`:815-837`).

  A database-computed `fire_at` would disagree with the memo by the skew, which gives two sources of truth for one deadline. Comparing only at poll time leaves everything that is written, and everything a replay reads, byte-for-byte as today. The replayed `sleep` self-heals from the row's status, not from a clock (`DefaultWorkflowOperations.java:392-427`), so it is unaffected.
- **The test kit.** `TestWorkflowEnvironment.advanceTime` drives `getDueTimers(Instant target, 100)` (`TestWorkflowEnvironment.java:680`), and the kit never starts `TimerPoller`. The in-memory store has no store clock. So the controllable clock keeps working unchanged. Computing `fire_at` in the store would instead need the kit's clock injected into `InMemoryWorkflowStore`, plus a `saveTimer` variant that takes a duration.
- **SPI.** The change is one additive default overload. Its default is today's behaviour. The JDBC base opts in through a hook that defaults to `null`, and only Postgres opts in. The in-memory store, third-party stores, the dialects on other branches, and decorators that do not forward it all keep caller-clock polling. Production is safe from that degradation: `StoreFaultGuard` is a `java.lang.reflect.Proxy` that `method.invoke`s the delegate (`StoreFaultGuard.java:255`), so the Postgres override is reached.
- **Mixed fleet.** See D-5.
- **What remains.** A deadline is still armed on the scheduling node's clock, and an await can still self-fire on the owner's clock. The skew WARN (D-3) reports both, and `Issue N2` records the remaining exposure.
- **Facts the SQL relies on.** `fire_at` is `TIMESTAMPTZ` (`V1__create_maestro_schema.sql:41`), so the comparison is absolute under any session or JVM zone, including the seeded non-UTC task. V2's partial index on `(fire_at, status)` still serves `fire_at <= now()`, because `now()` is stable within the statement. That index point is traced, not tested.

### D-3. The skew WARN

- **Where.** `ClockSkewCheck` (new, package-private, `io.b2mash.maestro.core.engine`). Every node's `TimerPoller` loop calls `maybeCheck()` at the top of each iteration, before the leader check, so non-leaders check too: every node arms deadlines. It checks on the first iteration and then once every `ClockSkewCheck.INTERVAL = Duration.ofMinutes(10)`, measured with `System.nanoTime()` so that a jumping JVM clock cannot defeat the cadence.
- **What it measures.** It takes `before = clock.instant()`, `t = store.storeTime()`, then `after = clock.instant()`. The skew is the distance of `t` from the window `[before, after]`: positive when `t < before` (this node is ahead), negative when `t > after`, and zero inside the window. Statement latency can therefore never produce a false WARN.
- **Threshold.** It WARNs when `|skew| > WARN_ABOVE = Duration.ofSeconds(1)`, the audit's figure. NTP keeps skew to milliseconds, so 1 s means a broken clock. The WARN repeats on every check while the skew persists, which is at most 6 per hour per node.
- **No clock.** When `storeTime()` is empty, the check logs one DEBUG line and never asks again.
- **Errors.** Any `RuntimeException`, `StoreUnavailableException` included, is logged at DEBUG and retried at the next interval. It is never rethrown. The poller thread has no `WorkflowContext` bound, so nothing can be latched, and the check is not a workflow outcome. An `Error` propagates, which is the control-flow rule.
- **No skew gauge.** The spec puts this cycle's meters in WP11; this package adds only the gauge its own scenario needs (Task 1). A WARN is enough to act on.
- **Cost.** One statement per node per 10 minutes.

### D-4. Removing the activity lock (3.10, D2)

**Code.**
- Delete `acquireLock`/`releaseLock` (`ActivityInvocationHandler.java:1243-1282`), the call at `:875`, the `finally` at `:945-947`, and `LockHandle lockHandle` at `:870`.
- Delete the `distributedLock` and `lockKeyPrefix` fields (`:221`, `:228`, `:488`, `:494`) and their imports.
- The parameters stay and are ignored (see Removed). Each `@param` then reads: "ignored: Maestro takes no per-activity lock (3.10); kept so existing callers compile until `Issue N1`".

**What replaces the "best-effort dedup fast path" claim.** Everywhere it appears, the docs now say:

> There is no per-activity lock. A step runs twice only when two runners hold the same workflow: the window after a node lost its instance lock. Lease fencing (V6) rejects the stale runner's outcome append, so the adopting node runs the step again. Activities must be idempotent. A duplicate Kafka delivery is deduplicated by the signal's message id (V9), not by a lock.

**Nothing else references the lock.**
- No property is dedicated to it.
- No observer callback or meter: `EngineObserver`, `MicrometerEngineObserver` and `RecordingEngineObserver` have no lock callback.
- No admin view.
- No migration: the table is shared with the instance lock.

**Statements.** Two fewer per live activity (`PostgresDistributedLock.java:70-85`: one `INSERT … ON CONFLICT … RETURNING` and one `DELETE`). For the k6 harness, see D-7.

### D-5. Mixed-version fleets (a rolling deploy across this change)

- **3.2 (in-memory, per node).** An old node keeps leaking permits in its own heap, as today. No permit ever crosses nodes: a cross-node wake is `NOTIFY` plus the re-check, unchanged on both builds. A new node keeps every permit that a run on it can consume. So every combination wakes exactly as an all-old fleet does, and none loses a wake.
- **3.7.** Only the elected leader polls. An old leader compares with its JVM clock; a new leader compares with the database's. Both builds arm `fire_at` identically, and both select every `PENDING` row whose `fire_at` their clock has passed. Every clock advances, so no row is fired never. `markTimerFired` is a compare-and-set, so a row is fired at most once whichever leader sees it. If leadership moves between versions, a fire can shift by the old leader's skew, which is exactly what happens today when leadership moves.
- **3.10.**
  - **Before.** On an old node, an activity takes `maestro:lock:activity:{id}:{seq}` and proceeds whatever `tryAcquire` returns (`:1259-1263`). A new node neither takes nor reads the row.
  - **The only double-run.** A duplicate execution needs two runners on one workflow, which is the lost-lock window. In every combination (old then new, new then old, old/old, new/new) the adopting runner executes the step, exactly as today, because today's failed acquire never blocked.
  - **Leftover rows.** An old node's rows are released by that node, or they expire (`startToCloseTimeout + 10 s`) and are deleted by any node's lock cleaner (rows expired for more than a minute).
  - **No interference.** The rows never collide with instance-lock keys or with `heldKeys`, which reads only workflow keys.

  So nothing double-runs worse than today.

### D-6. `SeededHistoryUpgradeIT` pins: none are expected to move

Three CURRENT-BUILD pins in `recoveryReplaysEveryActiveSeededRunToItsEnd` exercise the paths this package changes, and each must stay as written:

1. **`timerStatus(seeded, PARKED_TIMER, "sleep-8002")` is `"FIRED"`.** After Task 3 the harness's poller, `executor.startTimerPoller`, reaches `PostgresWorkflowStore.getDueTimers(int)` through `StoreFaultGuard`. The row's `fire_at` is before `LATEST_MUST_BE_DUE` (2026-11-12), so `now()` has passed it.
2. **`invocations.snapshot()` and the `live` list.** The activities still run once each, now without a lock.
3. **`signalRows(...)` are consumed.** `deliverSignal` runs on the node that runs the workflow, so its permits are kept.

`APPENDED`, `node.recover() == 5`, `lease_epoch=1` and the `argsHash` pin do not touch these paths. No pin mentions lock rows. The frozen `cbf5a2a/SeededHistoryCapture.java:360` counts lock rows at capture only.

`requireClockWithinFixtureWindow` checks the JVM clock, while the poller now uses the database's. The window runs from 2026-11-12 to 2036-11-09, so no realistic skew matters, and `SeededHistory` is not edited.

**If any of these pins goes red, stop and report it.** It is a regression, not a pin to move. Task 6 runs the seeded classes as a named step.

### D-7. The k6 harness (branch `test/k6-load-foundation`, follow-ups owned there)

1. **Tick pattern (3.7).** `perf/eval/common.jq:126` `tick_query_re` matches `fire_at <= \$[0-9]+`. Once WP2 merges, a Postgres node records `… WHERE fire_at <= now() AND status = $n …` (the literal normalised to a `$n`). Until the pattern changes, `background_problem` reports "the timer poller's due-timer query … is absent", so **every s1/s3 run on post-WP2 code is invalid**.
   - Change it to accept both forms, so that before/after runs on older commits still parse: `"FROM maestro_workflow_timer WHERE fire_at <= (\\$[0-9]+|now\\(\\)) AND status = "`.
   - Make the same change to `perf/eval/selftest.sh:129` `due_re`, and to the comments at `common.jq:98-109` and `perf/run.sh:36`.
2. **Statements per workflow (3.10).** The s1 5-activity chain measures **36.00** net statements per completed workflow (`perf/eval/s1-throughput.jq:178-185`, branch HEAD `130b9ea`). After WP2 the expected figure is **26.00** (36.00 − 5 activities × 2 lock statements). Task 3 adds no per-workflow statement: the tick is one statement on the leader, as before. Task 2 adds none, and neither does Task 1.
   - **This is outside the gate's band** (±0.72 at `s1_relative_allowance` 0.02).
   - So `perf/baselines/s1-throughput.json` is re-recorded as a named step on that branch.
   - The 36.00 and 26.00 runs are WP2's before/after pair (C4).
3. **Background (3.7).** One `SELECT now()` per node at poller start and then every 10 minutes. An idle window of 60 s catches at most one per node, which is ≤ 0.02 calls/s against a tick background of about 0.4 calls/s. The harness may leave it, or list it as a known background statement.
4. **Scenario 4 (WP12) "permit growth".** It reads `maestro_wake_permits`. Its "before" run cherry-picks Task 1's commit, which is self-contained by design, onto the pre-WP2 merge base.

### D-8. Rebase surface

- **`test/k6-load-foundation` (unmerged).**
  - `WorkflowExecutor.java`: it changes `:2439`, `:3008` and `:3160`. This package changes `:237-258`, `:989-995`, `:2138-2141` and adds one accessor near `:1827`. The hunks do not overlap.
  - `TaskQueueGovernor.java`: its hunks are at `:89`, `:301` and `:318`. This package edits only the Javadoc sentence at `:67` (Task 5).
  - `MicrometerEngineObserver`, `MaestroObservabilityAutoConfiguration` and `MaestroObservabilityAutoConfigurationTest`: not touched by this package. The gauge goes into `MaestroEngineGauges`, with a new test class.
  - **Expected conflicts.** `docs/observability.md`, where that branch rewrites the meter catalogue (meter count, gauges table), and `docs/release-notes.md` (Unreleased subsections). Resolve both by hand.
- **WP4 (loop guard).** It will wrap `TimerPoller.pollLoop`, which this package edits (Task 4). WP4 rebases onto WP2.
- **WP5 (shutdown).** It changes the governor and `WorkflowExecutor.shutdown()`. There is no overlap beyond the file.

## Global Constraints

- **Spec anti-goals (§2), in effect:**
  - No fix makes a run fail or compensate where today it would stand down or be abandoned.
  - History written by the current build keeps replaying, and a mixed fleet keeps working (D-5).
  - **An existing test is changed only as a named step.** The complete list is under "Existing tests changed". An implementer who meets any other failing test that encodes old behaviour, such as an unexpected permit count, a lock key or a caller-clock poll, stops and asks.
- **Code rules.** `maestro-core` never imports Spring. Jackson 3 (`tools.jackson`) only. JSpecify `@Nullable` on every nullable parameter and return. Javadoc on every public or protected member, with thread-safety notes. No Lombok.
- **Control-flow `Error`s** (`CLAUDE.md` "Exceptions"):
  - No new `catch` may intercept a `MaestroControlFlowError`.
  - `ClockSkewCheck` catches `RuntimeException` only.
  - `ParkingLot.wake` adds no `catch`.
  - Anywhere an outcome is unwrapped, check `instanceof Error` before `Exception`.
- **`StoreUnavailableException`** is a store fault, never a workflow failure. The skew check swallows it only because it runs on the poller thread, outside any run, and records no outcome (D-3). No other new `catch` may swallow, record or convert it.
- **No log line or meter tag** carries a workflow id, a run id or a payload. The new gauge has no tags.
- **Branch and worktree.**
  - Branch `fix/wake-timers-activity-lock`, cut from `origin/main` after PR #53 (WP1) has merged, in the worktree `/Users/rakheendama/Projects/2026/maestro/.claude/worktrees/wake-timers-activity-lock`.
  - Before the first edit, run `git -C <worktree> status` and `git -C /Users/rakheendama/Projects/2026/maestro status`.
  - Never `cd` into the main checkout. This plan lives on `docs/engine-hardening-cycle`; read it from the main checkout's absolute path.
- **Gradle.** Always `./gradlew --no-watch-fs`, run from the worktree root. Never two Gradle runs on one module at once. No `git stash`.
- **Commits.**
  - Commit at every checkpoint named below.
  - Chain git steps with `&&`. Pass messages as `-m "$(cat <<'EOF' … EOF)"`, never as a top-level heredoc.
  - Never run git-mutating commands in parallel.
  - Every message ends with the attribution line your own session supplies.
- **Long runs.**
  - Any run longer than ~5 minutes is a **coordinator gate**: `./gradlew --no-watch-fs build`, the whole `:maestro-integration-tests:test`, `e2eTest`, and the soak.
  - The coordinator launches it detached, with a log whose header records `pwd`, `git rev-parse HEAD` and a timestamp. Implementers do not start them.
  - If a module run named in a step takes longer than ~5 minutes on this machine, hand it to the coordinator instead.
- **Red tests.**
  - Each new red test is run against the unfixed code first and must fail on a positive fact: a value, a class or a status. It must not fail on an absence, and not on a `ConditionTimeoutException`.
  - Paste the FAIL lines into the commit body.
  - Each fix gets at least one mutation check that a reviewer can repeat. The mutation is reverted in the same step.
- **Documentation** states only what a test shows, or it is labelled "(traced)".
- **New issues** take the next free numbers in `docs/open-issues.md` at implementation time (`grep -n '^### Issue' docs/open-issues.md | tail -1`; 49 is the highest on `main` today). They are `Issue N1`–`N3` below.
- **PR gates (spec §6):**
  - Build & Test.
  - Nightly E2E on the branch.
  - Chaos PR-gate ×3: `./gradlew --no-watch-fs :maestro-integration-tests:e2eTest --rerun-tasks`, three consecutive runs.
  - The 2 h soak: `./gradlew --no-watch-fs :maestro-integration-tests:e2eTest --rerun-tasks -Dmaestro.chaos.soak=true -Dmaestro.chaos.durationMinutes=120`.

  The maintainer merges.

## Review Focus

1. **A signal persisted on the owning node between the await's last store read and its park.** This is the race the permit exists for. The run must wake at once, not after the 30 s re-check. → Task 2 `aSignalDeliveredBeforeALocalAwaitParks_wakesItAtOnce`.
2. **The leader owns the workflow and fires its sleep timer before the sleep has parked.** The sleep must wake at once. This is the intent of the old `AwaitTimerStrayPermitTest` pin. → Task 2 `aSleepTimerFiredBeforeALocalSleepParks_wakesItAtOnce`.
3. **A delivery or a late `NOTIFY` racing the run's end.** It must leave no permit behind. → Task 2 `aRunEndingBetweenTheCheckAndThePut_leavesNoPermit` (deterministic) and `aNotificationArrivingAfterTheRunEnded_leavesNoPermit`.
4. **A store with no clock of its own.** This covers the in-memory kit store, a third-party store and a decorator that does not forward. It must keep firing due timers on the caller's clock, `advanceTime` must stay exact, and the skew check must stay silent. → Task 3 `aStoreWithoutAClockIsPolledOnTheCallersClock`, Task 4 `aStoreWithoutAClockIsAskedOnceAndNeverAgain`, and the unchanged `TestWorkflowEnvironmentTest.advanceTimeByExactlyTheDurationFiresASleep` / `advanceTimeByExactlyTheTimeoutFiresAnAwaitDeadline`.
5. **A store outage during the skew check.** It must neither stop the poller nor latch a run, while an `Error` still propagates. → Task 4 `aStoreFaultIsSwallowedAndRetriedNextInterval`, `anErrorFromTheStorePropagates`.

## Existing tests changed (named; the only allowed edits)

| File | Edit | Task |
|---|---|---|
| `maestro-core/src/test/java/io/b2mash/maestro/core/engine/AwaitTimerStrayPermitTest.java` | Rewrite the class Javadoc's last sentence (`:24-26`). Replace `fireSleepTimer_stillUnparksTheTimerKey` (`:71-79`) with `fireSleepTimer_forAWorkflowNotRunningHere_storesNoPermit`. | 2 |
| `maestro-core/src/test/java/io/b2mash/maestro/core/engine/ActivityInvocationHandlerTest.java` | Delete `:435` (`assertEquals(1, lock.acquiredKeys.size(), "no activity lock for the stood-down call")`). Delete the five `Lock:` tests and their section comment (`:444-507`). Delete the helper `createProxyWithLockPrefix` (`:814-819`). Add `liveActivityTakesNoLock`. | 5 |
| `maestro-spring-boot-starter/src/test/java/io/b2mash/maestro/spring/config/MaestroAutoConfigurationConfigSeamsTest.java` | In the class Javadoc (`:62-63`), drop "for the per-activity lock (in addition to the instance lock, which already honoured it)". Rename `lockKeyPrefixReachesActivityInvocationHandler` (`:171-193`) and give it a new body. Reword the comment at `:656`. | 5 |
| `maestro-test/src/test/java/io/b2mash/maestro/test/NodeStoreViewTest.java:54-56` | `READS` gains `"storeTime"`. `everyStoreMethodIsClassifiedAsAReadOrAWrite` forces this. | 4 |

**Why the old `AwaitTimerStrayPermitTest` assertion existed.** It came in with the fix that stopped `fireTimer`/`cancelTimer` storing a `:timer:` permit for `await-signal-*` rows (`docs/release-notes.md:417`). It guarded that the fix skipped await rows only and left the sleep's real wake path alone. With nobody parked, a stored permit is the only trace an unpark leaves, and a workflow running nowhere was the cheapest setup. So the test asserted `1` for a workflow no node runs, and in doing so it pinned the 3.2 leak.

The replacement keeps both halves of that intent. The rewritten test asserts that a row of a workflow not running here stores no permit. `WakePermitLocalityTest.aSleepTimerFiredBeforeALocalSleepParks_wakesItAtOnce` pins that the sleep's wake path still works where it matters: on the node that will park.

**Reviewer check, statement-level:**
1. Run `BASE=$(git merge-base origin/main HEAD); git diff --name-only --diff-filter=M "$BASE" HEAD -- '*/src/test/*'`. It must print exactly the four files above.
2. `git diff --name-only --diff-filter=D "$BASE" HEAD` must print nothing.
3. Read every hunk of the four files with `git diff "$BASE" HEAD -- <file>`. Each removed line (`^-[^-]`) must belong to a named edit. A multi-line assertion counts as one statement: check its continuation lines too.
4. `git diff --stat "$BASE" HEAD -- maestro-integration-tests/` must be empty, because no seeded pin moved (D-6).

Do not grep for changed `assert` lines: that misses the continuation lines of multi-line assertions (found in WP0).

## File structure

| File | Responsibility |
|---|---|
| `maestro-core/.../engine/ParkingLot.java` | The gate: `ParkingLot(Predicate<String>)` and `wake(String, String)` |
| `maestro-core/.../engine/WorkflowExecutor.java` | Builds the gated lot; `wakeTimerWaiter`; the launch-failure `clearPending`; `wakePermitCount()` |
| `maestro-core/.../engine/SignalManager.java` | Three wake sources call `wake` |
| `maestro-core/.../spi/WorkflowStore.java` | `getDueTimers(int)` and `storeTime()` |
| `maestro-core/.../engine/TimerPoller.java`, `ClockSkewCheck.java` (new) | DB-clock poll; skew check |
| `maestro-store-jdbc/.../AbstractJdbcWorkflowStore.java`, `maestro-store-postgres/.../PostgresWorkflowStore.java` | Hooks and the Postgres SQL |
| `maestro-core/.../engine/ActivityInvocationHandler.java`, `ActivityProxyFactory.java` (Javadoc) | Lock removal |
| `maestro-spring-boot-starter/.../observe/MaestroEngineGauges.java` | The gauge |
| New tests | `MaestroEngineGaugesTest`, `WakePermitLocalityTest`, `ParkingLotWakeGateTest`, `TimerPollerDatabaseClockTest`, `ClockSkewCheckTest`, `JdbcStoreClockTest`, `PostgresDatabaseClockTest` |

---

### Task 1: The `maestro.wake.permits` gauge

A self-contained commit with no behaviour change, so that WP12's scenario 4 "before" run can cherry-pick it (D-7.4).

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java`: add the accessor after `parkedCount()` (`:1825-1827`).
- Modify: `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/observe/MaestroEngineGauges.java`: the third `Gauge.builder`, plus the class and constructor Javadoc ("three gauges").
- Modify: `docs/observability.md`:
  - `:70-72`: the meter count becomes nineteen, with **3 gauges**;
  - the gauges table at `:141-144` gains a row;
  - a third bullet under "How they are sourced" (`:150-160`).
- Modify: `docs/configuration.md:894-896`, the meter list.
- Create: `maestro-spring-boot-starter/src/test/java/io/b2mash/maestro/spring/observe/MaestroEngineGaugesTest.java`

**Interfaces:**
- Produces: `public int WorkflowExecutor.wakePermitCount()`, which returns `parkingLot.pendingWakeCount()`. Tasks 2's tests read it.

- [ ] **Step 1: Create the worktree.**

```bash
git -C /Users/rakheendama/Projects/2026/maestro fetch origin && \
git -C /Users/rakheendama/Projects/2026/maestro log origin/main --oneline -1 --grep='replay-fidelity' && \
git -C /Users/rakheendama/Projects/2026/maestro worktree add .claude/worktrees/wake-timers-activity-lock -b fix/wake-timers-activity-lock origin/main && \
git -C /Users/rakheendama/Projects/2026/maestro/.claude/worktrees/wake-timers-activity-lock status
```

Expected: the log prints the PR #53 merge, and the worktree is clean on `fix/wake-timers-activity-lock`. If the log prints nothing, stop: WP1 has not merged.

- [ ] **Step 2: Write the test.** It builds the executor with `WorkflowExecutor.builder(new InMemoryWorkflowStore(), new PayloadSerializer(JsonMapper.builder().build()), "svc").build()` and shuts it down in `finally`.

```java
@Test void wakePermitsGaugeIsRegisteredAgainstTheExecutor() {
    var registry = new SimpleMeterRegistry();
    new MaestroEngineGauges(registry, executor);
    assertEquals(0.0, registry.get("maestro.wake.permits").gauge().value());
}
```

- [ ] **Step 3: Run it red.** `./gradlew --no-watch-fs :maestro-spring-boot-starter:test --tests 'io.b2mash.maestro.spring.observe.MaestroEngineGaugesTest'`. Expected: FAIL with `MeterNotFoundException` naming `maestro.wake.permits`.
- [ ] **Step 4: Implement.** Add the accessor. Its Javadoc reads: "Returns the number of wake permits this node holds — wakes that arrived for a workflow before it parked. A node-local gauge source, like `parkedCount()`." Register the gauge with `.description("Wake permits held on this node for workflows that have not parked yet")` and `.strongReference(true)`. Then extend the test with `assertEquals(executor.wakePermitCount(), (int) registry.get("maestro.wake.permits").gauge().value());`.
- [ ] **Step 5: Run it green.** Run the same command. Expected: PASS. Mutation: register the gauge under another name, and the test goes red; revert.
- [ ] **Step 6: Docs.**
  - Gauges table row: `| maestro.wake.permits | *(none)* | WorkflowExecutor.wakePermitCount() |`.
  - Bullet: "`maestro.wake.permits` is the size of `ParkingLot`'s permit map, keyed by parking key: a wake (a signal, a timer fire or cancel, a cross-node notification) that reached this node when nobody was parked on that key yet."

  Task 2 adds the sentence on what a healthy value is.
- [ ] **Step 7: Commit:** `feat(observe): maestro.wake.permits gauge and WorkflowExecutor.wakePermitCount()`.

---

### Task 2: A wake permit only for a workflow running on this node (3.2)

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/ParkingLot.java`:
  - fields and constructors (`:77-78`);
  - the new `wake`;
  - `unpark` (`:233-266`) becomes the documented ungated seam;
  - class Javadoc "Wake Permits" (`:36-43`).
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java`:
  - construction order, so `runningWorkflows` (`:258`) is assigned before `parkingLot` (`:237`);
  - `wakeTimerWaiter` (`:989-995`);
  - the launch-failure catch (`:2138-2141`);
  - the `fireTimer` Javadoc (`:896-901`): "…and stores a permit only when the workflow runs here".
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/SignalManager.java`: `:512-514`, `:1143` and `:1258` call `parkingLot.wake(workflowId, key)`. In the `deliverSignal` Javadoc, the "Unpark if waiting locally" comment becomes "Wake a local waiter; a permit only if this node runs the workflow".
- Modify (named): `AwaitTimerStrayPermitTest.java`
- Create: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/WakePermitLocalityTest.java`, `ParkingLotWakeGateTest.java`
- Docs:
  - `docs/observability.md`, the `maestro.wake.permits` bullet;
  - `docs/operations.md` §5 (`:195-216`): one sentence.

**Interfaces:**
- Consumes: `WorkflowExecutor.wakePermitCount()` (Task 1).
- Produces (package-private):
  - `ParkingLot()`, under which every workflow counts as running here;
  - `ParkingLot(Predicate<String> runsHere)`;
  - `void wake(String workflowId, String key)`;
  - unchanged: `unpark(String, Object)`, `pendingWakeCount()`.

- [ ] **Step 1: Port the repro, and make the named change to `AwaitTimerStrayPermitTest`.**

  `WakePermitLocalityTest` starts as `evidence/repro-tests/AuditB32WakePermitLeakTest.java.txt`, ported as follows:
  1. **The two 11-argument constructors** become `WorkflowExecutor.builder(store, serializer, "svc").instanceLockTtl(Duration.ofSeconds(30)).lifecycleEventsEnabled(false).shutdownTimeout(Duration.ofSeconds(5)).wakeRecheckInterval(RECHECK).build()`. This is WP0's migration rule: arguments 2–4 (`null`) and 7 (`DEFAULT_KEY_PREFIX`) are omitted, and the literal `Duration.ofSeconds(30)` stays a setter.
  2. **Reflection and output.** The reflective `parkingLot(...)` helper and both `System.out.println` lines are dropped. The assertions read `nodeB.wakePermitCount()`.
  3. **Names.** The tests are renamed for the fixed behaviour: `signalsDeliveredOnANodeNotRunningTheWorkflow_leaveNoPermit` and `sleepTimersFiredByALeaderNotRunningTheWorkflow_leaveNoPermit`. Assertion values and messages stay verbatim. The class Javadoc cites the evidence file, which is not edited.

  In `AwaitTimerStrayPermitTest`, replace `:71-79` with:

```java
@Test
@DisplayName("fireTimer on a sleep-* row of a workflow not running here stores no permit")
void fireSleepTimer_forAWorkflowNotRunningHere_storesNoPermit() {
    var row = pendingRow("sleep-3");

    executor.fireTimer("wf-permit", row.timerId(), row.id());

    assertEquals(TimerStatus.FIRED, store.findTimer(row.workflowInstanceId(), row.timerId())
            .orElseThrow().status());
    assertEquals(0, parkingLot.pendingWakeCount(),
            "nobody on this node will park on it — the owner re-reads the FIRED row");
}
```

  The class Javadoc's last sentence becomes: "A `sleep-*` row still gets its `:timer:` wake, which stores a permit only when the workflow runs on this node (3.2); `WakePermitLocalityTest` pins that wake reaching a local sleep that has not parked yet."

- [ ] **Step 2: Add the NOTIFY red test** to `WakePermitLocalityTest`.
  - The test supplies its own `SignalNotifier`. `subscribe` stores the callback in a map, and `unsubscribe` deliberately leaves it there, which simulates a dispatch that looked its subscriber up just before the await unsubscribed. `publish` does nothing.
  - The executor is built with `.signalNotifier(notifier)`, and `AwaitOnce` comes from the repro.

```java
@Test void aNotificationArrivingAfterTheRunEnded_leavesNoPermit() throws Exception {
    executor.startWorkflow("late-notify", "AwaitOnce", "default", null, AwaitOnce::new, method);
    TestTerminalWait.awaitStatus(store, "late-notify", WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(5));
    executor.deliverSignal("late-notify", "go", "x");
    TestTerminalWait.awaitStatus(store, "late-notify", WorkflowStatus.COMPLETED, Duration.ofSeconds(5));
    // wait (≤ 5 s, polling) until !executor.isRunning("late-notify"); then:
    notifier.callbacks.get("late-notify").onSignal("late-notify", "go");
    assertEquals(0, executor.wakePermitCount(), "the run is over on this node");
}
```

- [ ] **Step 3: Run the reds.** Run `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.WakePermitLocalityTest' --tests 'io.b2mash.maestro.core.engine.AwaitTimerStrayPermitTest'`. Expected FAILs:
  - `expected: <0> but was: <51>`;
  - `expected: <0> but was: <50>`;
  - `expected: <0> but was: <1>` for each of the stray-permit and NOTIFY tests.

  The two `await-signal-*` tests stay green. Commit nothing yet.

- [ ] **Step 4: Add the race pins.** They pass on the unfixed code and guard what the fix must keep. Use `WorkflowExecutor.builder(store, serializer, "svc").build()`, which has the 30 s default re-check, so that only a permit can make either wake fast.
  - **Status helper.** `statusWithin(id, wanted, within)` polls `store.getInstance(id)` every 20 ms until the status equals `wanted` or `within` elapses, and returns the last status read.
  - **Workflows.** `AwaitLong` does `awaitSignal("go", String.class, Duration.ofHours(1))`. `SleepOnce` does `sleep(Duration.ofHours(1))` and then returns `"slept"`.
  - **The await hook.** A `VersionedInMemoryStore` subclass overrides `getUnconsumedSignals(workflowId, "go")`. The first time it is called while `super.getInstance(workflowId)` is `WAITING_SIGNAL` (that is, inside `awaitOutcome`'s step 1), it does the following:
    1. on a new virtual thread, call `executor.deliverSignal(workflowId, "go", "x")`, and join that thread;
    2. record `executor.wakePermitCount()`;
    3. return `List.of()`, as if the read had run just before the save.

    Every other call returns `super`. The override does not call `super` before delivering, so it holds no lock of the store's while it waits.
  - **The sleep hook.** The same subclass overrides the `appendEvent` overload that `VersionedInMemoryStore` receives the `TIMER_SCHEDULED` append on. After calling `super`, it does the following:
    1. read `timerId` from the event payload;
    2. find the row with `super.findTimer(instanceId, timerId)`;
    3. on a new virtual thread, call `executor.fireTimer(workflowId, timerId, row.id())`, and join it;
    4. record `executor.wakePermitCount()`.

```java
@Test void aSignalDeliveredBeforeALocalAwaitParks_wakesItAtOnce() throws Exception {
    executor.startWorkflow("race-await", "AwaitLong", "default", null, AwaitLong::new, awaitLong);
    assertEquals(WorkflowStatus.COMPLETED, statusWithin("race-await", WorkflowStatus.COMPLETED, Duration.ofSeconds(5)));
    assertEquals(1, store.permitsSeenAfterDelivery, "the delivery stored a permit for the run about to park");
}

@Test void aSleepTimerFiredBeforeALocalSleepParks_wakesItAtOnce() throws Exception {
    executor.startWorkflow("race-sleep", "SleepOnce", "default", null, SleepOnce::new, sleepOnce);
    assertEquals(WorkflowStatus.COMPLETED, statusWithin("race-sleep", WorkflowStatus.COMPLETED, Duration.ofSeconds(5)));
    assertEquals(1, store.permitsSeenAfterFire);
}
```

  Run them: expected PASS on the unfixed code. Their teeth are the mutation checks in Step 8.

- [ ] **Step 5: Write `ParkingLotWakeGateTest`.**

```java
@Test void aWakeForAWorkflowRunningHere_storesAPermitTheNextParkConsumes() throws Exception {
    var lot = new ParkingLot(id -> true);
    lot.wake("wf", "wf:signal:go");
    assertEquals(1, lot.pendingWakeCount());
    assertNull(lot.parkWithTimeout("wf:signal:go", Duration.ofSeconds(30)));   // returns at once
    assertEquals(0, lot.pendingWakeCount());
}
@Test void aWakeForAWorkflowNotRunningHere_storesNothing() {
    var lot = new ParkingLot(id -> false);
    lot.wake("wf", "wf:signal:go");
    assertEquals(0, lot.pendingWakeCount());
}
@Test void aWakeCompletesARegisteredWaiterWhateverThePredicateSays() throws Exception {
    // lot(id -> false); a virtual thread parks on "wf:signal:go" for 30 s; wait (≤ 5 s) for isParked;
    // wake("wf", "wf:signal:go"); the thread's park returns null within 5 s (not a TimeoutException)
}
@Test void aRunEndingBetweenTheCheckAndThePut_leavesNoPermit() {
    var calls = new AtomicInteger();
    var lot = new ParkingLot(id -> calls.getAndIncrement() == 0);   // running at the check, gone at the re-check
    lot.wake("wf", "wf:signal:go");
    assertEquals(0, lot.pendingWakeCount());
    assertEquals(2, calls.get(), "checked before the put and again after it");
}
@Test void theNoArgumentLotTreatsEveryWorkflowAsRunningHere() {
    var lot = new ParkingLot();
    lot.wake("wf", "wf:timer:sleep-1");
    assertEquals(1, lot.pendingWakeCount());
}
```

- [ ] **Step 6: Implement.**
  - **`ParkingLot`.** Add a `private final Predicate<String> runsHere` field. `ParkingLot()` delegates to `this(workflowId -> true)`. Implement `wake(String workflowId, String key)` exactly as in D-1, steps 1–4, with the `NULL_PAYLOAD` marker as the permit value.
  - **`unpark(key, payload)`.** Keep today's body. Its Javadoc reads: "Ungated test seam: stores a permit whatever the predicate says. Production code calls `wake`."
  - **`WorkflowExecutor`.** Assign `runningWorkflows` first, then `this.parkingLot = new ParkingLot(runningWorkflows::containsKey)`. `wakeTimerWaiter`'s `else` branch calls `parkingLot.wake(workflowId, workflowId + ":timer:" + timerId)`. The launch-failure catch calls `parkingLot.clearPending(instance.workflowId())` after the `remove`.
  - **`SignalManager`.** The three sites call `parkingLot.wake(workflowId, parkKey)`. At `:1143` that is `onTimerTransition`'s `workflowId` argument.

- [ ] **Step 7: Run green and grep.** `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.WakePermitLocalityTest' --tests 'io.b2mash.maestro.core.engine.AwaitTimerStrayPermitTest' --tests 'io.b2mash.maestro.core.engine.ParkingLotWakeGateTest' --tests 'io.b2mash.maestro.core.engine.ParkingLotTest' --tests 'io.b2mash.maestro.core.engine.SignalManagerTest'`. Expected: PASS.

  Then `grep -rn 'parkingLot.unpark(' maestro-core/src/main`. Expected: no output.

- [ ] **Step 8: Mutation checks** (each reverted).
  - `runsHere` always true (`new ParkingLot()` in the executor): the two ported tests, the stray-permit test and the NOTIFY test go red.
  - `runsHere` always false: both race pins go red (`expected: <COMPLETED> but was: <WAITING_SIGNAL>` / `<WAITING_TIMER>`).
  - Drop the re-check in step 4 of `wake`: `aRunEndingBetweenTheCheckAndThePut_leavesNoPermit` goes red.
  - Revert `wakeTimerWaiter` to the ungated `unpark`: `sleepTimersFiredByALeaderNotRunningTheWorkflow_leaveNoPermit` goes red.

- [ ] **Step 9: Module runs.** `./gradlew --no-watch-fs :maestro-core:test`, then `./gradlew --no-watch-fs :maestro-test:test`. Expected: PASS. Any other red test stops the task (Global Constraints).
- [ ] **Step 10: Docs.**
  - **The `maestro.wake.permits` bullet** gains: "Since 3.2 a permit is stored only for a workflow running on this node, and it is dropped when that run parks, ends, is terminated or the node shuts down, so the value stays near zero; one that grows with signal or timer throughput is a regression." Tests show this: `WakePermitLocalityTest`, `ParkingLotWakeGateTest`.
  - **`docs/operations.md` §5** gains: "A wake that reaches a node not running the workflow (a Kafka-consumed signal, a timer the leader fires for a workflow owned elsewhere) stores nothing there: the durable row is the wake, and the owner is woken by `NOTIFY` or its re-check."
- [ ] **Step 11: Commit** with the message `fix(engine): store a wake permit only for a workflow running on this node (3.2)`. Paste the Step 3 FAIL lines and the Step 8 mutation results into the body.

---

### Task 3: Poll due timers on the database clock (3.7)

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/spi/WorkflowStore.java`. Add `getDueTimers(int)` after `:839`. In the `:827-838` Javadoc, add "the caller's clock; see `getDueTimers(int)` for the store's".
- Modify: `maestro-store-jdbc/src/main/java/io/b2mash/maestro/store/jdbc/AbstractJdbcWorkflowStore.java`:
  - add the hook next to `getDueTimersSql()` (`:212-226`);
  - add a `getDueTimers(int)` override next to `:1531-1539`;
  - list the hook in the class Javadoc (`:112-125`).
- Modify: `maestro-store-postgres/src/main/java/io/b2mash/maestro/store/postgres/PostgresWorkflowStore.java`: override the hook, and add a class-Javadoc bullet.
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/TimerPoller.java`. `:186` becomes `store.getDueTimers(batchSize)`. The class Javadoc gains a `<h2>Clock</h2>` section.
- Create: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/TimerPollerDatabaseClockTest.java`, `maestro-store-jdbc/src/test/java/io/b2mash/maestro/store/jdbc/JdbcStoreClockTest.java`, `maestro-store-postgres/src/test/java/io/b2mash/maestro/store/postgres/PostgresDatabaseClockTest.java` (extends `PostgresTestSupport`)
- Docs:
  - `CLAUDE.md` "Three SPIs": after `getDueTimers(Instant now, int batchSize);` add `default List<WorkflowTimer> getDueTimers(int batchSize); // due by the store's clock (Postgres: fire_at <= now()); the timer poller calls this`;
  - `docs/configuration.md` "Timer Configuration" (`:608-620`): the paragraph in Step 7.

**Interfaces:**
- Produces:
  - `default List<WorkflowTimer> WorkflowStore.getDueTimers(int batchSize)`;
  - `protected @Nullable String AbstractJdbcWorkflowStore.getDueTimersByStoreClockSql()`.

  In Postgres the hook returns `"SELECT id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at FROM " + tableName("workflow_timer") + " WHERE fire_at <= now() AND status = 'PENDING' ORDER BY fire_at ASC LIMIT ? FOR UPDATE SKIP LOCKED"`. That is `getDueTimersSql()` with `?` replaced by `now()`, and the single parameter is the limit.

- [ ] **Step 1: Write the red test.** The red is `TimerPollerDatabaseClockTest`, and it compiles on the unfixed code.

```java
static final class ClockRecordingStore extends VersionedInMemoryStore {
    final List<String> polls = new CopyOnWriteArrayList<>();
    @Override public List<WorkflowTimer> getDueTimers(Instant now, int batchSize) {
        polls.add("caller-clock");
        return super.getDueTimers(now, batchSize);
    }
    // No @Override yet: on the unfixed code nothing calls this. Step 4 adds @Override.
    public List<WorkflowTimer> getDueTimers(int batchSize) {
        polls.add("store-clock");
        return List.of();
    }
}

@Test void thePollerAsksTheStoreForTimersDueByItsOwnClock() {
    // executor = WorkflowExecutor.builder(store, serializer, "svc").build();
    // poller = new TimerPoller(store, executor, null, "svc", Duration.ofMillis(50), 10); poller.start();
    // poll (≤ 2 s) until store.polls is non-empty; poller.stop();
    assertEquals("store-clock", store.polls.getFirst());
    assertFalse(store.polls.contains("caller-clock"), store.polls.toString());
}
```

- [ ] **Step 2: Run it red.** Run `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.TimerPollerDatabaseClockTest'`. Expected: `expected: <store-clock> but was: <caller-clock>`.
- [ ] **Step 3: Implement.**
  - **SPI.** Add the default method with the surface table's Javadoc.
  - **JDBC base.** The hook defaults to `null`. The override runs `queryList(sql, ps -> ps.setInt(1, batchSize), this::mapTimer)` when the hook returns SQL. Otherwise it returns `getDueTimers(Instant.now(), batchSize)`.
  - **Postgres.** Override the hook with the SQL under Interfaces.
  - **`TimerPoller`.** Change `:186`. The new `Clock` Javadoc section says:
    - a due timer is one the store says is due by its own clock;
    - on Postgres that is the database's clock;
    - on a store without one it is this JVM's clock;
    - `fire_at` itself is armed by the scheduling node.
- [ ] **Step 4: Add `@Override`** to the recording store's `getDueTimers(int)`, then add:

```java
@Test void aStoreWithoutAClockIsPolledOnTheCallersClock() {
    var store = new VersionedInMemoryStore();
    var due = new WorkflowTimer(UUID.randomUUID(), UUID.randomUUID(), "wf", "sleep-1",
            Instant.now().minusSeconds(10), TimerStatus.PENDING, Instant.now());
    store.saveTimer(due);
    store.saveTimer(/* same, fireAt Instant.now().plusSeconds(3600), timerId "sleep-2" */);
    assertEquals(List.of(due.id()), store.getDueTimers(10).stream().map(WorkflowTimer::id).toList());
}
```

- [ ] **Step 5: Write `JdbcStoreClockTest`.** Copy `JdbcAppendEventReturningPayloadTest`'s `ScriptedDb`, which records prepared SQL and executions, rather than sharing it. Make it also record bound parameters.

```java
@Test void getDueTimersByStoreClockRunsTheHookBindingOnlyTheBatchSize() {
    // Store subclass: getDueTimersByStoreClockSql() returns "DUE BY STORE CLOCK LIMIT ?"; the result set is empty
    store.getDueTimers(7);
    assertEquals(List.of("DUE BY STORE CLOCK LIMIT ?"), db.sql);
    assertEquals(List.of(7), db.parameters);
}
@Test void withoutTheHookItBindsTheCallersClock() {
    // hook returns null; getDueTimersSql() returns "DUE BY CALLER CLOCK ? LIMIT ?"
    var before = Instant.now(); store.getDueTimers(7); var after = Instant.now();
    assertEquals(List.of("DUE BY CALLER CLOCK ? LIMIT ?"), db.sql);
    // parameter 1 is a Timestamp within [before, after]; parameter 2 is 7
}
```

- [ ] **Step 6: Write `PostgresDatabaseClockTest`.**

```java
@Test void dueTimersByTheStoreClockAreTheRowsTheDatabaseSaysAreDue() throws Exception {
    var dbNow = selectNow();              // SELECT now() on dataSource, read as OffsetDateTime -> Instant
    // create one instance as PostgresWorkflowStoreTest's timer tests do; save PENDING rows
    // "sleep-1" at dbNow - 1 s and "sleep-2" at dbNow + 1 h
    assertEquals(List.of(dueId), store.getDueTimers(10).stream().map(WorkflowTimer::id).toList());
}
@Test void theDueTimerStatementComparesWithTheDatabaseClock() {
    assertTrue(store.getDueTimersByStoreClockSql().contains("WHERE fire_at <= now() AND status = 'PENDING'"),
            store.getDueTimersByStoreClockSql());
}
```

  `getDueTimersByStoreClockSql()` is `protected`, and the test is in the same package. A Testcontainers database shares the host's clock, so the first test cannot by itself tell the two clocks apart. The second test pins the SQL. Together with `JdbcStoreClockTest` and the poller red, they make every link from poller to SQL testable.

- [ ] **Step 7: Run green, mutate, docs.**
  - **Run.** `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.TimerPollerDatabaseClockTest' --tests 'io.b2mash.maestro.core.engine.TimerPollerTest' --tests 'io.b2mash.maestro.core.engine.BackgroundLoopStartShutdownRaceTest'`, then `./gradlew --no-watch-fs :maestro-store-jdbc:test`, then `./gradlew --no-watch-fs :maestro-store-postgres:test --tests '*PostgresDatabaseClockTest' --tests '*PostgresWorkflowStoreTest'`. Expected: PASS.
  - **Mutations** (each reverted):
    - The Postgres hook returns `null`: `theDueTimerStatementComparesWithTheDatabaseClock` goes red (NPE / false).
    - The JDBC override ignores the hook: `getDueTimersByStoreClockRunsTheHookBindingOnlyTheBatchSize` goes red.
    - `TimerPoller` reverted to `getDueTimers(Instant.now(), …)`: the poller red returns.
  - **The `docs/configuration.md` paragraph:**

  > Due timers are compared with the store's clock: on Postgres the poller selects `PENDING` rows with `fire_at <= now()`, so the poller leader's own clock no longer moves when a timer fires. A timer's `fire_at`, and an `awaitSignal` deadline, are still computed from the clock of the node that schedules them, so a node whose clock is off arms its deadlines off by the same amount (see the clock-skew `WARN` below). A store without a clock of its own (the in-memory test store, a custom store) is polled with the poller's clock, as before.

- [ ] **Step 8: Commit** with the message `fix(timers): poll due timers on the database clock (3.7)`. Paste the FAIL line and the mutation results into the body.

---

### Task 4: WARN when a node's clock is off from the database's (3.7)

**Files:**
- Modify: `WorkflowStore.java`: add `storeTime()` after `getDueTimers(int)`.
- Modify: `AbstractJdbcWorkflowStore.java`: add the `storeTimeSql()` hook and `storeTime()`. `storeTime()` reads one row as `OffsetDateTime` and returns `toInstant()`. SQL errors translate as every other read does.
- Modify: `PostgresWorkflowStore.java`: `storeTimeSql()` returns `"SELECT now()"`.
- Create: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/ClockSkewCheck.java`
- Modify: `TimerPoller.java`. Add a `private final ClockSkewCheck skewCheck = new ClockSkewCheck(store)`, assigned in the existing constructor. Call `skewCheck.maybeCheck()` as the first statement inside `pollLoop`'s `try` (`:159-160`), before `isLeader()`.
- Modify (named): `NodeStoreViewTest.java:54-56`. Check `NodeStoreView`'s own classification: if it lists writes explicitly (`isWrite`), `storeTime` is a read and needs no change there.
- Create: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/ClockSkewCheckTest.java`. Extend: `TimerPollerDatabaseClockTest`, `JdbcStoreClockTest`, `PostgresDatabaseClockTest`. These are new files in this branch, not existing tests.
- Docs:
  - `CLAUDE.md` "Three SPIs": `default Optional<Instant> storeTime(); // the store's clock, for the skew WARN; empty = no clock`;
  - `CLAUDE.md` "Lock Keys" (after the key block): the sentence in Step 7;
  - `docs/configuration.md` "Timer Configuration": the paragraph in Step 7;
  - `docs/operations.md` §13's table (`:1165-1171`) gains the row `| Clock-skew check | 1 \`SELECT now()\` | at timer-poller start, then every **10 min** |`;
  - `docs/operations.md` §3 (`:108-129`) gains one sentence: "The leader's own clock does not decide when a timer fires: due timers are compared with the database clock (§13 has the skew check)."

**Interfaces:**
- Consumes: `WorkflowStore.storeTime()`.
- Produces (package-private):
  - `ClockSkewCheck(WorkflowStore store)`, on the system clock and `System::nanoTime`;
  - `ClockSkewCheck(WorkflowStore store, InstantSource clock, LongSupplier nanoTime)`;
  - `Optional<Duration> maybeCheck()`: the measured skew, or empty when it did not check this call, the store has no clock, or the read failed. It never throws a `RuntimeException`;
  - `static Duration skew(Instant before, Instant storeTime, Instant after)`: positive means this node is ahead;
  - `static Optional<String> warning(Duration skew)`;
  - `static final Duration WARN_ABOVE = Duration.ofSeconds(1)`, `static final Duration INTERVAL = Duration.ofMinutes(10)`.

- [ ] **Step 1: Write the red test.** The red is in `TimerPollerDatabaseClockTest`, and it compiles on the unfixed code. The recording store gains a `storeTime()` with no `@Override` yet, which counts its calls and returns `Optional.of(Instant.now())`.

```java
@Test void everyNodesPollerReadsTheStoreClockOnStartEvenWhenItIsNotTheLeader() {
    // poller = new TimerPoller(store, executor, new NeverLeaderLock(), "svc", Duration.ofMillis(50), 10)
    // NeverLeaderLock: trySetLeader -> false; every other method throws UnsupportedOperationException
    // start; poll (≤ 2 s) for store.clockReads > 0 OR 1 s elapsed; stop
    assertEquals(1, store.clockReads.get(), "one check at start, the next only after ClockSkewCheck.INTERVAL");
}
```

  Run it: `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.TimerPollerDatabaseClockTest'`. Expected: `expected: <1> but was: <0>`.

- [ ] **Step 2: Write `ClockSkewCheckTest`.** It uses a `VersionedInMemoryStore` subclass whose `storeTime()` is scripted, a fixed `InstantSource`, and an `AtomicLong`-backed `nanoTime`.

```java
@Test void skewIsZeroWhenTheStoreTimeFallsInsideTheReadWindow() { assertEquals(Duration.ZERO, skew(t, t.plusMillis(5), t.plusMillis(10))); }
@Test void aStoreTimeBeforeTheWindowMeansThisNodeIsAhead()   { assertEquals(Duration.ofMillis(1500), skew(t, t.minusMillis(1500), t.plusMillis(10))); }
@Test void aStoreTimeAfterTheWindowMeansThisNodeIsBehind()   { assertEquals(Duration.ofMillis(-2000), skew(t, t.plusMillis(2010), t.plusMillis(10))); }
@Test void exactlyOneSecondIsNotWarned() {
    assertEquals(Optional.empty(), warning(Duration.ofSeconds(1)));
    assertEquals(Optional.empty(), warning(Duration.ofSeconds(-1)));
}
@Test void aNodeAheadIsWarnedThatItsDeadlinesFireLate()  { assertEquals(Optional.of(/* Step 3 copy, 1500 ms, "ahead of", "late" */), warning(Duration.ofMillis(1500))); }
@Test void aNodeBehindIsWarnedThatItsDeadlinesFireEarly() { assertEquals(Optional.of(/* Step 3 copy, 2000 ms, "behind", "early" */), warning(Duration.ofMillis(-2000))); }
@Test void checksOnTheFirstCallThenOncePerInterval() {
    assertTrue(check.maybeCheck().isPresent());
    nanos.addAndGet(Duration.ofMinutes(10).minusSeconds(1).toNanos());
    assertEquals(Optional.empty(), check.maybeCheck());
    nanos.addAndGet(Duration.ofSeconds(1).toNanos());
    assertTrue(check.maybeCheck().isPresent());
    assertEquals(2, store.reads.get());
}
@Test void aStoreWithoutAClockIsAskedOnceAndNeverAgain() { /* storeTime() -> empty; two calls one INTERVAL apart; reads == 1 */ }
@Test void aStoreFaultIsSwallowedAndRetriedNextInterval() {
    // storeTime() throws new StoreUnavailableException("down", new SQLException("08006"))
    assertEquals(Optional.empty(), check.maybeCheck());       // no throw
    nanos.addAndGet(ClockSkewCheck.INTERVAL.toNanos());
    check.maybeCheck();
    assertEquals(2, store.reads.get());
}
@Test void anErrorFromTheStorePropagates() {
    // storeTime() throws new ExecutorShutdownException("test")
    assertThrows(ExecutorShutdownException.class, check::maybeCheck);
}
```

- [ ] **Step 3: Implement.**
  - **SPI.** `storeTime()`, with the surface table's Javadoc.
  - **JDBC base and Postgres.** The hook defaults to `null`, which returns empty without SQL. Postgres returns `"SELECT now()"`.
  - **`ClockSkewCheck`.** As in D-3: `catch (RuntimeException e)` only, and a `volatile boolean noClock`. The WARN fixed copy, with `%d` the absolute skew in milliseconds:

  > This node's clock is %d ms ahead of the database clock (warns above 1 s). Due timers are polled on the database clock, but sleep() and awaitSignal() deadlines are armed on the clock of the node that schedules them, so deadlines armed here fire up to %d ms late. Check this node's time synchronisation (NTP).

  For a node behind, the same copy reads `behind` for `ahead of` and `early` for `late`. Log it at WARN from `maybeCheck`, the store-without-clock line once at DEBUG, and a swallowed fault at DEBUG.
  - **Wiring.** Wire it into `TimerPoller`.

- [ ] **Step 4: Add `@Override`** to the recording store's `storeTime()`. Then extend:
  - `JdbcStoreClockTest`: `storeTimeRunsTheHook` (hook `"STORE NOW"`, the result `OffsetDateTime` of a fixed instant, returns that instant) and `withoutTheHookStoreTimeIsEmptyAndRunsNoSql`.
  - `PostgresDatabaseClockTest`: `storeTimeIsReadFromTheDatabase`. Read `a = store.storeTime().orElseThrow()`, then `b = selectNow()`. Assert `!b.isBefore(a)` and `Duration.between(a, b) < 5 s`: `now()` is the database's own monotonic clock, so a later read can never be earlier.
- [ ] **Step 5: Make the named edit to `NodeStoreViewTest`, then run.** Run `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.ClockSkewCheckTest' --tests 'io.b2mash.maestro.core.engine.TimerPollerDatabaseClockTest' --tests 'io.b2mash.maestro.core.engine.TimerPollerTest'`, then `./gradlew --no-watch-fs :maestro-test:test --tests '*NodeStoreViewTest'`, then `:maestro-store-jdbc:test`, then `:maestro-store-postgres:test --tests '*PostgresDatabaseClockTest'`. Expected: PASS.
- [ ] **Step 6: Mutation checks** (each reverted).
  - `>` becomes `>=` in `warning`: `exactlyOneSecondIsNotWarned` goes red.
  - The check moved after `isLeader()`: `everyNodesPollerReadsTheStoreClockOnStart…` goes red with `<0>`.
  - `catch (RuntimeException)` widened to `catch (Throwable)`: `anErrorFromTheStorePropagates` goes red.
  - The `noClock` latch removed: `aStoreWithoutAClockIsAskedOnceAndNeverAgain` goes red.
- [ ] **Step 7: Docs.**
  - **`CLAUDE.md`:** "Due timers are polled on the database clock (`fire_at <= now()`); `fire_at` and await deadlines are armed on the scheduling node's clock, and every node WARNs when its clock is more than 1 s from the database's (checked at timer-poller start and every 10 min)."
  - **`docs/configuration.md`:** "Each node compares its clock with the database's when its timer poller starts and every 10 minutes (one `SELECT now()`), and logs a `WARN` from `ClockSkewCheck` when they differ by more than 1 s, naming the direction and how early or late its deadlines fire. A store without a clock of its own is not checked."
- [ ] **Step 8: Commit** with the message `feat(timers): WARN when a node's clock is more than 1 s off the database's (3.7)`.

---

### Task 5: Remove the activity lock (3.10, D2)

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/ActivityInvocationHandler.java`:
  - code as in D-4;
  - class Javadoc: replace "Lock Strategy" (`:171-174`) with `<h2>Duplicate execution</h2>`, carrying the D-4 text;
  - "before it acquires a lock, publishes a start" (`:147`) loses "acquires a lock,";
  - remove "also the base of the per-activity lock TTL" from every `startToCloseTimeout` `@param`;
  - the ignored-parameter `@param`s;
  - check the comment block at `:850-868` for "lock" and reword it. `grep -n -i lock` over the file must then show only the ignored `@param`s and the "Duplicate execution" text.
- Modify: `ActivityProxyFactory.java`: the same `@param` edits (`:49`, `:56`, `:60`, `:84`, `:90`, `:94`, `:97`, `:123`, `:129`, `:133`, `:136`, `:165`, `:172`, `:176`, `:179` and the later overloads).
- Modify: `TaskQueueGovernor.java:67`: "from the activity's dedup lock, through every retry attempt" becomes "from the activity's start, through every retry attempt".
- Modify: `maestro-test/src/main/java/io/b2mash/maestro/test/TestWorkflowEnvironment.java:125`, the comment: "The instance-lock key prefix (activity proxies take no lock since 3.10; `createProxy` still takes the argument, Issue N1); everything else is the engine's default (WorkflowExecutor.Builder)."
- Modify (named): `ActivityInvocationHandlerTest.java`, `MaestroAutoConfigurationConfigSeamsTest.java`
- Docs:
  - `CLAUDE.md:208` (delete the key line; add the D-4 text, in one sentence, after the key block);
  - `docs/configuration.md`: `:481` ("for workflow instance locks and leader election (timer poller, retention sweeper)"), `:489` (drop "Applies to the instance lock and the activity lock alike."), `:550` ("from the activity's start through every retry attempt"), `:756-758` (delete the paragraph), `:1137-1146` (the intro drops "activity deduplication"; the table row goes), `:1160-1165` (the D-4 text replaces the paragraph);
  - `docs/maestro-architecture.md`: the `:205-216` mermaid (delete nodes `F` and `K`; `D -- "No (live)" --> G`; `J --> L`; `I` reads `"Persist result<br/>(unique constraint, lease fence)"`), the `:474` row, and `:657` ("Signal message-id dedup (V9) + unique constraint");
  - `docs/cross-service.md`: the `:466` row, and `:479-483` ("Activity lock" paragraph replaced by "**No activity lock** — " plus the D-4 text);
  - `docs/self-recovery.md:245` (same replacement as architecture `:657`);
  - `docs/failure-scenarios.md:59` (drop "the activity lock row,");
  - `docs/pitfalls.md:90` ("Locks are a best-effort guard." becomes "The instance lock and lease fencing stop a second runner from *recording* a result, not from executing the step.");
  - `docs/operations.md:1206` ("an activity slot's holder for the activity's outcome write");
  - `docs/open-issues.md` Issue 9: append "*Superseded (WP2, 3.10): the activity lock was removed.*".

**Interfaces:** none produced. The `createProxy` and handler signatures are unchanged.

- [ ] **Step 1: Write the red tests.**

  In `ActivityInvocationHandlerTest`, add:

```java
@Test
@DisplayName("A live activity takes no lock (3.10)")
void liveActivityTakesNoLock() {
    withContext(() -> createProxy(GreetingActivities.class, new GreetingActivitiesImpl()).greet("World"));
    assertEquals(List.of(), lock.acquiredKeys, "a live activity takes no lock (3.10)");
    assertEquals(0, lock.releasedCount);
}
```

  In `MaestroAutoConfigurationConfigSeamsTest`, the renamed test keeps `:174-182` and replaces `:184-191` with:

```java
@Test
@DisplayName("A workflow's activities take no lock under a custom maestro.lock.key-prefix; its instance lock uses the prefix")
void noActivityLockIsTakenUnderACustomKeyPrefix() {
    // … unchanged setup and startAndWait …
    assertThat(lock.acquiredKeys.stream().filter(key -> key.contains(":activity:")).toList())
            .as("acquired keys were " + lock.acquiredKeys).isEmpty();
    assertThat(lock.acquiredKeys).contains("custom:prefix:workflow:lock-prefix-1");
}
```

  The comment at `:656` becomes "Calls a single no-op activity, to drive a live activity through the real chain."

- [ ] **Step 2: Run them red.** `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.ActivityInvocationHandlerTest'` and `./gradlew --no-watch-fs :maestro-spring-boot-starter:test --tests 'io.b2mash.maestro.spring.config.MaestroAutoConfigurationConfigSeamsTest'`. Expected:
  - `liveActivityTakesNoLock`: `expected: <[]> but was: <[maestro:lock:activity:<WORKFLOW_ID>:1]>`;
  - the starter test: an AssertJ failure listing `custom:prefix:activity:lock-prefix-1:1`;
  - everything else in both classes: PASS.
- [ ] **Step 3: Make the named deletions** in `ActivityInvocationHandlerTest`, at `:435`, `:444-507` and `:814-819`. Then implement D-4's code change.
- [ ] **Step 4: Run green.** Run the same two commands, then `./gradlew --no-watch-fs :maestro-core:test`, `./gradlew --no-watch-fs :maestro-test:test` and `./gradlew --no-watch-fs :maestro-spring-boot-starter:test`. Expected: PASS.

  Mutation: restore the `acquireLock` call (keep the method), and both red tests return. Revert.
- [ ] **Step 5: Docs, then sweep.**

```bash
grep -rniE "lock:activity|activity lock|activity-lock|dedup lock|per-activity lock|activity deduplication|activity execution lock" \
  --include='*.java' --include='*.md' . | grep -vE '/build/|docs/superpowers/|^\./tasks/|docs/release-notes\.md|docs/open-issues\.md|docs/test-plan\.md'
```

  Expected: no output. The excluded files are history: the release notes, Issue 9 and `test-plan.md:414`.
- [ ] **Step 6: Commit** with the message `refactor(engine): remove the per-activity lock and its lock key (3.10, D2)`. Paste the red lines into the body, and note the expected harness figure (36.00 → 26.00, D-7.2).

---

### Task 6: Release note, issues, seeded history, gates

**Files:**
- `docs/release-notes.md`: a new first subsection under `## Unreleased — Postgres + Kafka only`: `### Changed — wake permits only on the running node; timers polled on the database clock; the activity lock is gone (3.2, 3.7, 3.10, D2)`. Its bullets:
  - **3.2.** The leak and its two sources; the rule; no payload in a permit; the gauge.
  - **3.7.** The compare-only rule; the `SELECT now()` per node per 10 min; the WARN; the SPI defaults, and that decorators should forward `getDueTimers(int)` and `storeTime()`.
  - **3.10.** The key is gone; −2 statements per live activity (s1: 36.00 → 26.00); the createProxy parameters are now ignored (`Issue N1`); activities must be idempotent.
  - **Mixed fleets.** D-5, in three sentences.
- `docs/open-issues.md`: three new issues with the house sections (what happens, file:line, harm, fix shape, why it is not WP2), and a summary-table row for each.
  - **`Issue N1`.** The `ActivityInvocationHandler` constructors and `ActivityProxyFactory.createProxy` overloads keep two ignored parameters (`distributedLock`, `lockKeyPrefix`). Likewise `DefaultWorkflowOperations` keeps an unused `distributedLock` field (`:125`, `:316`). Fix: a proxy-factory builder, as Issue 37 did for the executor.
  - **`Issue N2`.** Timer deadlines are armed on the scheduling node's clock (`DefaultWorkflowOperations.java:438`, `SignalManager.java:712`), and an await self-fires on the owner's clock (`:926`). The skew WARN reports this but does not prevent it. Fix shape: arm on the database clock, which is M-size because of the memoized `AwaitSignalDetail.deadline` and the kit's clock (D-2).
  - **`Issue N3`** (traced, not run). `ParkingLot` matches a workflow's keys by the prefix `workflowId + ":"`, in `abandonWorkflow` (`:326-333`), `terminatedSignal` (`:196-199`) and `clearPending` (`:379-380`). So terminating `order` on a node that also runs `order:1` also poisons and abandons `order:1`'s parks. That run unwinds without writing anything and is re-adopted by recovery: latency, never loss. Fix shape: key the maps by `workflowId`.

- [ ] **Step 1: Make the edits above.** Take N1–N3's numbers from `grep -n '^### Issue' docs/open-issues.md | tail -1`.
- [ ] **Step 2: Run the seeded history (D-6, named step).** Run `./gradlew --no-watch-fs :maestro-integration-tests:test --tests '*SeededHistoryUpgradeIT' --tests '*SeededHistoryReplayFidelityIT'`, then `./gradlew --no-watch-fs :maestro-integration-tests:seededHistoryNonUtcTest`.

  Expected: PASS, and `git diff --stat "$(git merge-base origin/main HEAD)" HEAD -- maestro-integration-tests/` prints nothing. If any pin goes red, stop and report it (D-6): no pin is expected to move.
- [ ] **Step 3: Run the existing-tests check** from "Existing tests changed", and paste its output into the commit body. Then run `./gradlew --no-watch-fs :maestro-core:javadoc :maestro-store-jdbc:javadoc :maestro-store-postgres:javadoc`. Expected: no warning naming a changed member.
- [ ] **Step 4: Commit** with the message `docs: WP2 release note and three new issues (3.2, 3.7, 3.10)`.
- [ ] **Step 5: Coordinator gates, before the PR merges.**
  - `./gradlew --no-watch-fs build`;
  - the whole `:maestro-integration-tests:test`;
  - nightly E2E on the branch;
  - chaos PR-gate ×3;
  - the 2 h soak.

  Every result goes in the review section with its log identity. Also hand D-7's four items to the owner of `test/k6-load-foundation`, as one message the coordinator records in the review section.

---

## Self-review (run 2026-11-13)

- **Spec coverage.**
  - (a) 3.2, both sources plus the `NOTIFY` path and the await-deadline transition: Task 2. `AwaitTimerStrayPermitTest` is changed as a named step, with the reason written down.
  - (b) 3.7, polling on the DB clock: Task 3. The skew WARN: Task 4.
  - (c) 3.10, removal of the lock and its documented key: Task 5.
  - The surface is listed first. The `AuditB32` port is the first red for 3.2 and says how it is ported (Task 2 Step 1).
  - Gates follow spec §6, the soak included.
- **Every decision the brief asked for:**
  - the permit rule, its data structure, thread safety, the not-yet-parked race and the run-end race, for every source including `NOTIFY`, with no bound and a gauge: D-1;
  - compare versus compute, replay, the kit, the SPI and non-JDBC stores: D-2;
  - the skew WARN's site, formula and threshold: D-3;
  - every activity-lock reference, the replacement claim and the property convention: D-4;
  - mixed fleets: D-5;
  - seeded pins: D-6;
  - the harness tick pattern and 26.00: D-7;
  - the rebase surface: D-8.
- **Type consistency.** These names are the same in every task:
  - `wake(String, String)`, `ParkingLot(Predicate<String>)`, `wakePermitCount()`;
  - `getDueTimers(int)`, `getDueTimersByStoreClockSql()`, `storeTime()`, `storeTimeSql()`;
  - `ClockSkewCheck.maybeCheck()/skew/warning/WARN_ABOVE/INTERVAL`;
  - `maestro.wake.permits`.
- **Review Focus.** Every item has an owning test in Tasks 2, 3 and 4, or an unchanged kit test.
- **Proportion.** The code blocks are test assertions, SQL and commands. Implementation bodies are left to the signatures, except D-1's four steps (an ordering the tests pin) and the WARN copy (fixed text).

## Review section

(The coordinator fills this in: the red and mutation lines per task; the issue numbers given to N1–N3; the seeded-history run; gate results with log identity; the D-7 hand-off to the k6 branch.)
