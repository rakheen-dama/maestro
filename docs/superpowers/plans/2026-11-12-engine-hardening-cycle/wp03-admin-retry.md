# WP3 Admin Retry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An operator's Retry of a `FAILED` workflow re-runs the failure that ended the run, and nothing the run recorded before it. A caught failure followed by a fallback is kept and replays (3.3). A caught failure that was handled (an alert) and then rethrown is truncated together with its handling, so the primary and the alert both run again live (D10). The failing await is chosen by what propagated, not by the highest `SIGNAL_TIMEOUT` (Issue 33). The deletes and the status flip are one transaction.

**Architecture:** The run that records `WORKFLOW_FAILED` writes, in its payload, the sequence numbers of the failure memos its escaping exception carries (`failedSequences`). The engine knows them through a run-scoped identity map: every `ActivityExecutionException` and `SignalTimeoutException` that the engine throws for a durable memo, live or replayed, is registered against that memo's sequence. On Retry, a new package-private `RetryPlanner` rebuilds the sequence-space lanes from the history (`LaneTree`) and computes the exact set of events and timer rows to delete. A new default SPI method, `WorkflowStore.reopenFailedInstance`, applies that set and the `RUNNING` compare-and-set in one transaction. The JDBC store and both in-memory stores override it. A store that does not keeps today's two-step retry through the default.

**Tech Stack:** Java 25, `maestro-core` (no Spring), `maestro-store-jdbc`, `maestro-store-postgres` (Testcontainers `postgres:16-alpine`), `maestro-test`, `maestro-spring-boot-starter` (test decorator only), `maestro-integration-tests` (Testcontainers Postgres + `confluentinc/cp-kafka:7.7.1`, auto-create off), JUnit 5, AssertJ in the integration module.

**Spec:** `docs/superpowers/plans/2026-11-12-engine-hardening-cycle/00-spec.md`: the WP3 row, §2 anti-goals, §6, D10. Evidence: `evidence/audit-B-signal-replay-correctness.md` §3.3 and "Surprises", `evidence/repro-tests/AuditB33RetryCaughtFailureTest.java.txt`. Issues: `docs/open-issues.md` Issue 33, and the work it cites (Issue 19, R61, Issue 16). Siblings: `wp00-executor-builder.md` (migration rule for the repro's constructor), `wp02-wake-timers-activity-lock.md` (house style, the decorator rule), `wp09-schema-safety.md` (the `SeededHistoryUpgradeIT` pin kinds).

All line numbers below are for `main` @ `525b287`. Re-check them after cutting the branch.

## Added, changed and removed public surface

**Added**

| Surface | Exact shape | Task |
|---|---|---|
| SPI method | `default void WorkflowStore.reopenFailedInstance(WorkflowInstance reopened, Set<Integer> deleteEventSequences, Set<String> deleteTimerIds)`. Contract under Task 4 Step 3. The default is today's two-step retry: `deleteFailureEvents(reopened.id()); updateInstance(reopened);`. It ignores both sets and is not atomic. `AbstractJdbcWorkflowStore`, `InMemoryWorkflowStore` and the core test `VersionedInMemoryStore` override it. A decorating store should forward it. | 4, 5 |
| Enum constant | `WorkflowExecutor.RetryOutcome.SIGNAL_CONSUMED_AFTER_FAILURE`. Retry writes nothing and refuses, because re-running the failure's handling would need a signal it already consumed. This is maintainer decision MD-1. | 6 |
| Record component | `WorkflowFailureDetail(String exceptionType, @Nullable String message, @Nullable List<Integer> failedSequences)`. The two-argument constructor stays and passes `null`. The engine always writes a list, possibly empty. `null` (absent in the JSON) means a build before WP3 wrote it. | 1 |
| Engine-internal accessors | `public static void WorkflowContextInternals.recordFailureMemo(WorkflowContext ctx, Throwable thrown, int sequence)` and `public static @Nullable Integer WorkflowContextInternals.failureMemoSequence(WorkflowContext ctx, Throwable thrown)`. These sit in the class that already holds `nextSequence`/`setSequence`. | 1 |

No new configuration property, meter, log key with a workflow payload, or migration. D-7 explains why no migration is needed.

**Changed behaviour**

- **What Retry deletes** (D-3). Today it deletes every `ACTIVITY_FAILED`, the `WORKFLOW_FAILED`, and, for a timeout failure, the highest `SIGNAL_TIMEOUT` with its park memo and row. After WP3 it deletes the attributed failure, everything recorded after it along its propagation path (D-2), the `WORKFLOW_FAILED`, and the timer rows of every deleted timer slot.
- **One transaction** (D-7). The deletes and the `FAILED → RUNNING` compare-and-set commit together. Today they are two writes (`WorkflowExecutor.java:1369`, `:1381`).
- **Failure payload** (D-4). `WORKFLOW_FAILED`'s payload and the instance's `output` gain `"failedSequences":[…]`. `WorkflowHandle.failure()` and `TestWorkflowHandle.getFailure()` return it in the new component.
- **A new refusal** (D-6, MD-1). A Retry whose truncation would delete a `SIGNAL_RECEIVED` returns `SIGNAL_CONSUMED_AFTER_FAILURE` and writes nothing. `AdminCommandDispatcher` already acknowledges every outcome other than `RETRIED` with a WARN (`AdminCommandDispatcher.java:139-145`), so it needs no code change.

**Removed:** nothing. `deleteFailureEvents` stays abstract with an unchanged contract. It is now called only by the default `reopenFailedInstance` and by nodes of older builds.

**Doc rules:** `docs/pitfalls.md` rule 2 gains one paragraph (Task 6). No rule is added or renumbered.

## What Retry does today (`main` @ `525b287`)

**Entry points.** There is one engine path, `WorkflowExecutor.retryWorkflow(String, WorkflowRegistration)` (`WorkflowExecutor.java:1335-1405`). It is reached three ways:

1. The dashboard's Retry button: `AdminCommandService.retry` (`maestro-admin/.../AdminCommandService.java`, after the constructor) publishes `SignalMessage(workflowId, "$maestro:retry", null)` with a fresh `admin:<uuid>` message id to `maestro.signals.<service>`.
2. The Kafka listener on any node of that service hands every `$maestro:*` name to `AdminCommandDispatcher.dispatch` before `deliverSignal`, so a command never becomes a signal row. `dispatchRetry` (`AdminCommandDispatcher.java:123-146`) resolves the registration and calls `retryWorkflow`. Every outcome is acknowledged; an exception (an optimistic-lock conflict, a store fault) is redelivered.
3. In tests, `MaestroEngineHarness.retry` (`maestro-integration-tests/.../MaestroEngineHarness.java:205-207`). `MaestroClient` and `TestWorkflowEnvironment` have no retry.

**Steps:**

| # | Step | Code | Atomic with |
|---|---|---|---|
| 1 | Read the instance. Absent: `NOT_FOUND`. Not `FAILED`: `NOT_FAILED`. | `:1336-1346` | — |
| 2 | `getEvents`. Any `COMPENSATION_STARTED`: `COMPENSATED_NOT_RETRYABLE` (Issue 16). | `:1355-1362` | — |
| 3 | `store.deleteFailureEvents(id)`. This deletes every `ACTIVITY_FAILED` and `WORKFLOW_FAILED` by type. If the `WORKFLOW_FAILED` payload's `exceptionType` is `SignalTimeoutException`, it also deletes the highest-sequenced `SIGNAL_TIMEOUT`, the `TIMER_SCHEDULED` just before it with the same step name, and every `await-signal-<seq-1>` row. All of that is one transaction (`AbstractJdbcWorkflowStore.java:979-1003, 1123-1255`; `InMemoryWorkflowStore.java:401-454`; `VersionedInMemoryStore.java:360-364`). | `:1369` | itself only |
| 4 | `updateInstance(status RUNNING, completedAt null, version+1)`. This is a single unfenced CAS; `OptimisticLockException` propagates. | `:1373-1381` | — |
| 5 | Local-running check (`ALREADY_RUNNING_LOCALLY`), then `launchWorkflow(…, replaying=true)`. That re-reads the row, adopts it with `leaseEpoch+1` under the version CAS (`:2085`, `adopt` `:2203-2224`) and starts the run. A lock held elsewhere gives `LOCK_HELD_ELSEWHERE`, and recovery adopts it later. | `:1388-1400` | — |

The lease epoch is not touched by steps 3 and 4. The fence moves at step 5's adoption, as for any recovery. Signals and `sleep-*` rows are never touched. Terminate on a `FAILED` row returns `ALREADY_TERMINAL` (`:1117-1119`). Recovery never adopts `FAILED`, because it is not active. The Postgres purge selects candidates `FOR UPDATE SKIP LOCKED` (`PostgresWorkflowStore.java:151-157`) and re-checks status in its instance delete (`AbstractJdbcWorkflowStore.java:1051-1068`).

**The defect (3.3).** Step 3 deletes failure memos by **type**, so a caught failure goes too. Minimal failing scenario (run on a copy of `525b287`, the `AuditB33` repro ported to the builder):

```
@WorkflowMethod String run() {
    String quote;
    try { quote = acts.primary(); } catch (RuntimeException e) { quote = acts.fallback(); }
    return acts.finish(quote);
}
before retry:  1 ACTIVITY_FAILED Acts.primary | 2 ACTIVITY_COMPLETED Acts.fallback | 3 ACTIVITY_FAILED Acts.finish | 4 WORKFLOW_FAILED
retry → RETRIED; status=RUNNING, standDowns=[NONDETERMINISTIC_HISTORY seq=2 expected=Acts.finish stored=Acts.fallback] (twice),
primaryCalls=2 fallbackCalls=1 finishCalls=1
```

`primary` ran live a second time, which is a real side effect, then the run met the fallback's memo and stands down on every recovery pass. It is `RUNNING`, so a second Retry returns `NOT_FAILED`, and only terminate exits.

**D10 shape, today** (same run). The workflow is `catch { alert(); throw e; }`. The history is `1 ACTIVITY_FAILED primary | 2 ACTIVITY_COMPLETED alert | 3 WORKFLOW_FAILED`. Retry deletes 1 and 3. `primary` succeeds live and the run stands down (`NONDETERMINISTIC_HISTORY seq=2 expected=Acts.finish stored=Acts.fallback`).

The cbf5a2a seeded `seed-alert-failed` is the same shape, with no next step after the catch. Run on the copy with `explode` fixed, the outcome is `RETRIED`. `explode` completes live at 1, and the run's `WORKFLOW_COMPLETED` collides with the alert's memo at 2: `STALE_RUN` "append collision at sequence 2". The row stays `RUNNING` with history `1 ACTIVITY_COMPLETED explode | 2 ACTIVITY_COMPLETED alert`.

**Issue 33** (traced). Step 3's timeout case picks the **highest** `SIGNAL_TIMEOUT`. That is wrong whenever a caught gate sits at a higher sequence than the timeout that propagated:
- **Shape 1.** The gate is in a branch *after a nested fork*, at `≈ p·10⁶`, and the root's later slots are at `p*1000+(n+1)*1000+k`. A gate in a branch's first block does not sort above the root's continuation, so Issue 33's wording "branch slots sort above later main-sequence slots" holds only for nested blocks; the test uses a nested fork.
- **Shape 2.** The gate is in a higher-index branch.
- **`collectSignals`.** It throws its own timeout without a memo (`DefaultWorkflowOperations.java:730`), so the highest `SIGNAL_TIMEOUT` found belongs to an earlier, caught await.

**The audit reproductions still fail on `main` @ `525b287`.** The evidence file does not compile on `main`, because WP0 removed the 12-argument constructor. Ported to `WorkflowExecutor.builder(store, serializer, "svc").instanceLockTtl(Duration.ofSeconds(30)).lifecycleEventsEnabled(false).shutdownTimeout(Duration.ofSeconds(5)).wakeRecheckInterval(Duration.ofSeconds(30)).observer(observer).build()`, with assertions verbatim, it fails with:
- `retry must not turn a caught failure into replay drift ==> expected: <true> but was: <false>`;
- the D10 variant with `expected: <COMPLETED> but was: <RUNNING>`.

## Decisions

### D-1. Where the rule is computed: in the engine, applied by the store

The store gets no workflow semantics. The engine reads the history once (`getEvents`, which step 2 already does), computes a plan, and hands the store two plain sets: sequences and timer ids. This is why:
- the lane structure (fork markers, branch blocks) lives in `SequenceSpace` and `DefaultWorkflowOperations`, which `maestro-store-jdbc` must not re-implement in SQL;
- the in-memory and JDBC stores then match trivially, because both apply the same sets;
- third-party stores need only "delete these rows, CAS this row, in one transaction".

### D-2. Lanes

A **lane** is the sequence of slots one `WorkflowContext` counter hands out (`WorkflowContext.nextSequence`, `WorkflowContext.java:394-401`):

- **Root lane.** Slots `1, 2, 3, …`. After a fork marker at slot `p` with `n` branches, the lane continues at `SequenceSpace.nextParentSeq(p, n) + 1 = p*1000 + (n+1)*1000 + 1` (`DefaultWorkflowOperations.java:926`). So the root lane is **not** numerically contiguous after a fork.
- **Branch lane `i` of the fork at `p`.** Slots `branchBase(p, i) + 1 … branchBase(p, i) + 999`, where `branchBase = p*1000 + (i+1)*1000` (`DefaultWorkflowOperations.java:821-831`, `SequenceSpace.java:51-53`). After a nested fork at `q` inside it, the branch lane continues at `q*1000 + (n_q+1)*1000 + 1`, unbounded: the bound check `counter == limit-1` never fires after `setSequence` jumps past the limit.
- **Fork marker.** `SIDE_EFFECT` with step `$maestro:parallel` and an integral `branchCount ≥ 2` (`:801-802`, `ParallelDetail` `:1400`). `parallel()` of 0 or 1 task writes no marker and runs inline in the caller's lane (`:742-753`).
- **Multi-slot operations stay contiguous inside one lane.**
  - A parked `awaitSignal` writes `TIMER_SCHEDULED` at `s` and its outcome at `s+1`.
  - A parked `sleep` writes `TIMER_SCHEDULED` at `s` and `TIMER_FIRED`/`TIMER_CANCELLED` at `s+1`.
  - An unparked await, `currentTime`, `randomUUID` and `version()` each write one slot.
  - `version()` consumes a slot only when it writes or finds its marker (`:1112-1140`).
- **Saga compensation blocks** (anchored at `COMPENSATION_STARTED`) are not lanes the walk knows. They never reach the planner, because Retry refuses them first (`COMPENSATED_NOT_RETRYABLE`, unchanged).

**`LaneTree`** (new, package-private, `io.b2mash.maestro.core.engine`) rebuilds the lanes from the history. It walks the root lane from slot 1, slot by slot, and stops at the first empty slot. At a fork marker it walks each branch lane from `branchBase+1`, bounded by `branchBase+999` until that branch's own first fork. Then it continues the forking lane at `nextParentSeq+1`. An event the walk never reaches is **unplaced**. `WORKFLOW_FAILED` is excluded from that set: it sits at the root's next slot, after a gap if the run's last step consumed a slot without a memo.

**`after(F)`**, the propagation path of a placed event `F`, is the union of three sets:
1. the slots of `F`'s lane after `F`;
2. for every ancestor lane, the slots after the fork marker that spawned the child lane on the path;
3. for every fork marker in sets 1 and 2, the whole subtree of that marker (its branch lanes, recursively).

Sibling branches of any lane on the path, and the subtrees of forks *before* the path positions, are never in `after(F)`.

**Why a stopping walk, not arithmetic.** Lane ranges overlap numerically. A root lane's continuation after its last fork is unbounded, and nested branch blocks (`≈ p·10⁶`) are numerically above later root slots. So no `seq → lane` formula is exact. The history is exact, and the walk reads only the history.

**Where the walk can be wrong.** A slot consumed with no memo, *followed* by more events in that lane, hides those events from the walk. The one engine path that produces this is a live activity whose result the mapper cannot write (`ActivityInvocationHandler.java:913-914`, "an ordinary live failure"), *caught* by workflow code that carries on. An unknown newer operation that partitions the sequence space differently would do the same. D-3 handles both by falling back to today's plan.

### D-3. The rule

`RetryPlanner.plan(String workflowId, List<WorkflowEvent> history)` returns a `RetryPlan`. Checks are made in this order:

1. **`LEGACY`**, when the walk leaves any unplaced event. The plan is exactly today's `deleteFailureEvents` set: every `ACTIVITY_FAILED` and `WORKFLOW_FAILED`, plus, when the payload names `SignalTimeoutException`, the highest `SIGNAL_TIMEOUT`, its same-step `TIMER_SCHEDULED` at `seq-1`, and timer id `await-signal-<seq-1>`. Never worse than today, by construction.
2. **Attribution set `A`.**
   - **New build.** The `WORKFLOW_FAILED` payload has an array `failedSequences`. `A` is its elements that are placed `ACTIVITY_FAILED` or `SIGNAL_TIMEOUT` events. Basis `ATTRIBUTED`.
   - **Older build.** The array is absent. Match the payload's `message` exactly against one event:
     - `exceptionType` is `io.b2mash.maestro.core.exception.ActivityExecutionException`: candidates are the placed `ACTIVITY_FAILED` events `e` with `"Activity '%s' failed in workflow '%s': %s".formatted(e.stepName(), workflowId, m)`, where `m` is `e.payload().message`, or `e.payload().exceptionType` when `message` is null. This is the format of `ActivityExecutionException.java:37-40` over the memo `ActivityInvocationHandler.java:1193-1196` writes.
     - `exceptionType` is `io.b2mash.maestro.core.exception.SignalTimeoutException`: candidates are the placed `SIGNAL_TIMEOUT` events with `"Signal '%s' timed out after %s for workflow '%s'".formatted(signalName, timeout, workflowId)`, read from `SignalTimeoutDetail` (`SignalTimeoutException.java:19-21`).

     If exactly one candidate matches, `A = {it}` and the basis is `MATCHED_MESSAGE`. If none or several match, `A` is empty. Run on a copy of `525b287`, the seeded `seed-alert-failed` payload is `{"message":"Activity 'seed-failing.explode' failed in workflow 'seed-alert-failed': exploded on seed-alert-failed",…}` and its memo is `{"message":"exploded on seed-alert-failed",…}`, so it matches.
3. **`A` non-empty: truncate** (D10). For every `F ∈ A`, delete:
   - `opSlots(F)`: `F`, plus `F-1` when `F` is a `SIGNAL_TIMEOUT` and `F-1` is a `TIMER_SCHEDULED` with the same step name;
   - `after(F)`;
   - every `WORKFLOW_FAILED`.
   Nothing else is deleted. In particular, another branch's uncaught failure stays (D-5, MD-2).
4. **`A` empty: last-in-lane** (basis `LAST_IN_LANE`). Delete every `WORKFLOW_FAILED`, and `opSlots(F′)` for every placed `ACTIVITY_FAILED`/`SIGNAL_TIMEOUT` `F′` whose `after(F′)` holds nothing but `WORKFLOW_FAILED`. This is one pass, not iterated to a fixpoint.
5. **Refusal.** If the delete set holds a `SIGNAL_RECEIVED`, return the plan with `consumedSignalAt` set to the lowest such sequence (D-6). Only basis 3 can produce this.
6. **Timer ids.** Delete:
   - the `timerId` string of every `TIMER_SCHEDULED` in the delete set;
   - for every deleted sequence `s`, `"sleep-" + s` and `"await-signal-" + s`.

   The second form covers a row saved before its memo (`SignalManager.java:690-716`, M2).

**Why each case is safe.** The governing fact is that a deleted slot only makes the retried run go live there. The only hazard is a *kept* event that the live run meets with another meaning.
- **Case 3** deletes a whole suffix of each lane on the path, plus the subtrees in it. The rest of the argument is D-5.
- **Case 4** deletes only a failure whose propagation path recorded nothing. Whatever it does live, nothing kept depends on it, whether it was caught or not.
- **Case 1** is today.

**Why not "last-in-lane" alongside attribution?** It would also delete a sibling branch's *caught* last-operation failure: a branch that caught a gate timeout and returned. That is safe for determinism, but it re-runs a gate that Issue 33's "Done when" says must survive, and it contradicts the WP3 row ("delete a failure memo only if it is the failure that ended the run"). It is therefore applied only when no failure is attributed.

### D-4. Attribution, live and replayed

- **Registry.** `WorkflowContext` gains `private final Map<Throwable, Integer> failureMemos = Collections.synchronizedMap(new IdentityHashMap<>())`. It is created by the public constructors and shared by `bounded(...)`, exactly as `cancellation` is shared (`WorkflowContext.java:334-339`). So branch contexts and parallel compensation contexts register into the run's map.
- **Accessors.** `WorkflowContextInternals.recordFailureMemo`/`failureMemoSequence` delegate to package-private methods.
- **Size.** One entry per failure memo thrown in this run, so it is bounded by the run's own `ACTIVITY_FAILED`/`SIGNAL_TIMEOUT` count.
- **Registration points.** Each registers the exact object about to be thrown:

| Where | Sequence |
|---|---|
| `ActivityInvocationHandler.java:909` (`throw e` after `persistFailure` succeeded) | `seq` |
| `ActivityInvocationHandler.java:599` (`throw reconstructFailure(…)`, replay) | `seq` |
| `SignalManager.java:663` (live, `timeout <= 0`, after `appendTimeout(ctx, seq, …)`) | `seq` |
| `SignalManager.java:748` (`replayOutcome`, stored `SIGNAL_TIMEOUT`) | the outcome's `seq` argument |
| `SignalManager.java:978` (deadline, after `appendTimeout(ctx, outcomeSeq, …)`) | `outcomeSeq` |

  Not registered:
  - `collectSignals`' own throw (`DefaultWorkflowOperations.java:730`), which has no memo;
  - a failure whose memo append threw (`:891-903`), which is never thrown as a failure;
  - `TimerCancelledException`, whose `TIMER_CANCELLED` is not a failure memo today and stays kept.
- **Payload.** `handleWorkflowFailure` (`WorkflowExecutor.java:2898-2901`) builds `new WorkflowFailureDetail(failure.getClass().getName(), failure.getMessage(), FailureAttribution.sequences(ctx, failure))`. `FailureAttribution` (new, package-private) walks `failure`, its cause chain and every suppressed exception, recursively. It keeps an identity-visited set, stops after 100 throwables, looks each up in the registry, and returns the sorted distinct sequences.
  - It makes no store call and cannot throw.
  - It is reached only from `failAndCompensate`, which never sees a `MaestroControlFlowError`, a `StoreUnavailableException` or a fence (`WorkflowExecutor.java:2370-2434`, `:2849-2856`). It rethrows nothing, because it only reads.
- **Why identity, not a new exception field.** `ActivityExecutionException` is built by `RetryExecutor` (`RetryExecutor.java:165, 183, 280`), which has no sequence. A field would need a mutable setter or a copy that changes the object workflow code caught. The registry adds no public type, and a rethrown or wrapped exception keeps its identity or its cause chain.
- **The author's side.** A workflow that rethrows `e`, or wraps it with `new X(msg, e)`, is attributed. A wrap without the cause is not, and falls to last-in-lane. `docs/pitfalls.md` rule 2 gains that sentence (Task 6), and MD-3 covers the residue.

### D-5. D10 for parallel branches: safe, with one shape left to a second Retry

**Claim.** Under case 3, the retried run replays every kept event at the slot that recorded it, and runs live from the first deleted slot in each lane it reaches.

1. **Kept events of a lane are a prefix of it.**
   - On the path: up to `F`, or up to the spawning fork marker.
   - Off the path: the whole lane (a sibling branch and its subtree, or the subtree of a fork before the path position), or nothing (a subtree of a fork in a deleted suffix).
2. **A kept prefix replays identically.** By the determinism constraint, each step given its recorded result computes the same next step at the same next slot. Branch blocks are fixed by the fork marker's slot and count, which are kept for every fork before the frontier.
3. **A completed sibling** replays its whole recorded subtree. Its inputs are the state at the fork, which is the kept prefix. It cannot observe `F`: branches share only the join (instance fields shared across branches are already outside the determinism contract, pitfalls rule 8). So it returns the same value to the join, without calling its activities.
4. **A sibling still parked** cannot exist in a `FAILED` run. `parallel()` waits on the latch for every branch before rethrowing (`DefaultWorkflowOperations.java:851-857`). No engine path interrupts a workflow thread (`grep -rn '\.interrupt()' maestro-core/src/main` hits only pollers and self-interrupt restores), so the "interrupted join" `RuntimeException` (`:853-855`) is not reached by the engine (traced).
5. **A sibling that failed later, uncaught.** `parallel()` rethrows the lowest-index branch error (`:916-923`), so the attributed `F` is the lowest-index failing branch. A higher-index failure is not in `A` and is kept.
   - **First Retry.** The path branch runs live, and the kept sibling replays its failure. The join rethrows it, and the run is `FAILED` again with `failedSequences` naming the sibling's memo.
   - **Second Retry.** It clears that memo.

   Nothing stands down, nothing compensates, and no sibling side effect repeats. This is MD-2.
6. **The path lane after `F`, and every ancestor after its fork, are empty.** So the join's possibly different outcome flows only into live code, and the parent lane continues live at `nextParentSeq+1`.
7. **Deterministic re-allocation.**
   - A live step after the frontier allocates slots in a deleted suffix, or in the blocks of a fork it creates live.
   - A kept event inside such a block would need two forks whose slots differ by less than their branch count. That is Issue 28's ×1000 overlap, which an original run can hit identically. It is not introduced by Retry and is surfaced by `ReplayGuard` (stand-down) or the unique index (`STALE_RUN`), never by a failure.
   - The terminal event takes the root's next slot, which is deleted.
8. **Nested forks** follow by induction on depth: `after(F)` climbs each ancestor (Task 2 `afterClimbsEveryAncestorAndKeepsEverySibling`).
9. **Compensation.** A `FAILED` run without `COMPENSATION_STARTED` had an empty stack at failure (`WorkflowExecutor.java:2863`). The kept prefix re-registers the same, empty, set (branch buffers flush in index order, `:935-940`). So a retried run compensates only work it ran live. That is correct saga behaviour, and the spec's "compensates again" cannot occur (traced).

**Shapes pinned by tests** (Task 6): completed sibling kept; parallel D10 with a root alert after the join; two failed branches needing two Retries; nested fork; Issue 33 shapes 1 and 2.

### D-6. Signals and timers in the deleted region

- **Timers.** Deleted with their memos: `TIMER_SCHEDULED`/`TIMER_FIRED`/`TIMER_CANCELLED` of a sleep, `TIMER_SCHEDULED`/`SIGNAL_TIMEOUT` of an await, and their rows, any status (D-3 step 6). A kept row with the same `timer_id` would be found by the live await's reuse check (`SignalManager.java:700-707`) and spend the fresh deadline at once. That is the R61 bug class.
  - A `PENDING` row of a `FAILED` instance that the poller fires mid-retry loses its `markTimerFired` CAS after the delete, which is harmless.
  - No timer row outside the delete set is touched. `sleep-*` rows of kept slots stay.
- **Signals consumed after the truncation point** (MD-1). A `SIGNAL_RECEIVED` memo carries the raw payload and not the row id (`SignalManager.java:1320-1321, 1356-1365`). The schema links no row to the memo that consumed it (V1, V9). So the planner cannot give the signal back exactly.
  - Deleting the memo and leaving the row consumed would discard the signal, which breaks the project rule.
  - Releasing "a consumed row with an equal payload" fails for a decimal the JDBC read rounds (spec §3, C1), and can release a sibling's row.

  So the plan refuses: `SIGNAL_CONSUMED_AFTER_FAILURE`, nothing written, a WARN naming the sequence. The workflow stays `FAILED`, and the operator terminates it or finishes it by hand. A retry today in this shape stands down (traced), so a refusal is no worse for anti-goal 1.
- **Signals in the kept region** are untouched and replay. Signals that arrived after the failure stay unconsumed, and a live await of the retried run consumes them.

### D-7. Atomicity and fencing

- **New SPI method, not a changed `deleteFailureEvents`.** The plan must commit with the CAS. Today's crash window between step 3 and step 4 was harmless only because a second delete-by-type is idempotent. A second *plan*, computed over an already-truncated history whose `WORKFLOW_FAILED` is gone, would take the last-in-lane branch and could delete more.
- **JDBC** (`AbstractJdbcWorkflowStore`), on one connection with `autoCommit=false`:
  1. `updateInstanceRow(conn, reopened, null)` (`:673-698`, `… WHERE id = ? AND version = ?`). This takes the row lock first. Zero rows: roll back, then `resolveUpdateFailure(reopened, null)` (`:1729`), which throws `WorkflowNotFoundException` or `OptimisticLockException`.
  2. `DELETE FROM maestro_workflow_event WHERE workflow_instance_id = ? AND sequence_number IN (…)`, in chunks of 500.
  3. `DELETE FROM maestro_workflow_timer WHERE workflow_instance_id = ? AND timer_id IN (…)`, in chunks of 500.
  4. Commit. Any `SQLException` or `RuntimeException` rolls back, using the existing `rollbackAfter`/`endTransaction` pattern (`:983-999`), and maps through `storeFault`.
- **In-memory** (`InMemoryWorkflowStore`, `VersionedInMemoryStore`). Take `timerLock`, then the per-workflow instance lock: the purge's lock order, data lock before instance lock (`InMemoryWorkflowStore.java:138-144`). Check the version *before* any mutation, then remove events, remove timers and put the row.
- **Concurrency.**

| Concurrent actor | Outcome | Why |
|---|---|---|
| A second Retry (same or another node) | One CAS wins. The loser rolls back with nothing deleted, and `OptimisticLockException` propagates, so redelivery sees `RUNNING` and returns `NOT_FAILED`. | Deletes and CAS in one transaction |
| Recovery adoption | Cannot adopt `FAILED`. After commit it adopts `RUNNING` over the truncated history; the retry's own launch reports `ALREADY_RUNNING_LOCALLY`/`LOCK_HELD_ELSEWHERE` as today. | `isActive` filter; `adopt` CAS |
| Terminate | Before commit: `ALREADY_TERMINAL`. After: its converge loop re-reads `RUNNING` and terminates. | Terminate re-reads per attempt (`:1110-1170`) |
| Retention purge, Postgres | The purge's `FOR UPDATE SKIP LOCKED` skips a row the reopen holds. A reopen that meets a row the purge holds waits, finds zero rows, and gets `WorkflowNotFoundException`, which the executor returns as `NOT_FOUND`. | Row lock (Task 5 `aReopenWaitingOnAPurgeFindsTheRowGone`) |
| Retention purge, portable dialect | The purge's instance delete re-checks status and rolls back. | Existing (`:1057-1068`) |
| An older-build node retrying at once | Its `deleteFailureEvents` commits on its own, and at most one CAS wins. The union of deletes is at most today's set plus the plan. | No worse than today |
| Timer poller, signal delivery | They touch rows the reopen does not lock or delete, or lose their CAS on a deleted row. | — |
| Crash | All or nothing. A crash after the commit and before the launch leaves `RUNNING`, which recovery adopts (as today). | — |

- **Fencing.** The reopen is an operator write, arbitrated by the version CAS, as today's retry and terminate are (`WorkflowStore.java:67-76`). It does **not** bump `lease_epoch`. Nothing can write at the `FAILED` run's epoch after `FAILED` is committed: the run wrote it through the fenced `completeInstance` (`:2902`), and every branch joined before that (D-5.4). The relaunch's adoption bumps the epoch as today (`:2085`). The architecture doc's sentence on unfenced operator writes (`docs/maestro-architecture.md:476`) stays true.
- **`StoreFaultGuard`.** The new method is not in `RETRIED` (`StoreFaultGuard.java:122-123`), so it is attempted once. It runs on the operator's thread with no context bound, so nothing is latched (traced). A lost commit is redelivered and re-reads the row: `RUNNING` gives `NOT_FAILED`, and `FAILED` plans again over the unchanged history.
- **No migration.** Nothing new is stored in a column. `failedSequences` lives in the existing `payload`/`output` JSONB. Had one been needed, it would take V102 (the highest shipped is V101; the rule in `CLAUDE.md` § Database Tables).

### D-8. Compatibility

- **History an older build wrote** (no `failedSequences`): `MATCHED_MESSAGE` or `LAST_IN_LANE`, and `LEGACY` when anything is unplaced.
  - The cbf5a2a fixture has four `FAILED` runs. `seed-activity-failed` (`1 ACTIVITY_FAILED, 2 WORKFLOW_FAILED`) is matched and deleted whole. `seed-alert-failed` (`1 ACTIVITY_FAILED, 2 ACTIVITY_COMPLETED alert, 3 WORKFLOW_FAILED`) is matched, and 1–3 are deleted. The two compensated sagas are refused as today.
  - A test can be **added** without touching a NEVER-EDIT pin (Task 8): `SeededHistory.load()` restores a fresh database per call (`SeededHistory.java:176-199`). The new test is a new method; `RECORDED_EVENTS` is read only by its own test; and `finishedAndOtherServiceRunsAreNotAdopted` loads its own copy.
  - The test needs `explode` to succeed. `SeededHistory.node` hard-codes `new SeedActivities(…, true)` (`:409`), and the frozen source must not change, so `SeededHistory` gains an overload. `SeededHistory.java` is not a pinned file; only its constants are.
- **A mixed fleet** (a rolling deploy across WP3):
  - **An older node performs the Retry.** It runs today's two steps and ignores `failedSequences`, so it is exactly today's behaviour.
  - **An older node replays a history a new node truncated.** It reads only event types it knows, at slots that are now empty or kept, so it replays as for any shorter history.
  - **An older node reads a new `WORKFLOW_FAILED`.** Its JDBC/in-memory `deleteFailureEvents` reads only `exceptionType` through `JsonNode`. Its `WorkflowHandle.failure()` deserialises into the two-component record. Jackson 3 ignores the unknown property by default; the audit ran this for Sub→Base. **Traced exception:** a host whose mapper enables `FAIL_ON_UNKNOWN_PROPERTIES` gets an exception from `failure()` on an older node during the window. Release note.
- **Args-hash verification after a Retry.** Kept `ACTIVITY_COMPLETED`/`ACTIVITY_FAILED` events keep their `args_hash` and are compared on replay with the same arguments (the prefix is deterministic). Deleted slots are rewritten live with a fresh hash. So a Retry adds no `nondeterministic_history`. A Retry after a code change stands down exactly as any recovery would (Task 6 runs with `verifyArguments` on, the default, `ActivityInvocationHandler.java:432`).

### D-9. Rebase surface and later packages

- **WP8 (store hygiene)** shares `WorkflowStore.java`, `AbstractJdbcWorkflowStore.java`, `InMemoryWorkflowStore.java`, `VersionedInMemoryStore.java` and the same six decorators (it adds a default janitor overload). WP8 starts after WP6, so it rebases onto WP3. Its decorators must forward both methods.
- **WP4 (loop guard)** and **WP5/WP6** edit `WorkflowExecutor.java` elsewhere (loops, shutdown, recovery admission). WP3's hunks are `retryWorkflow` (`:1172-1405`) and `handleWorkflowFailure`'s payload (`:2896-2901`).
- **`test/k6-load-foundation`** changes `WorkflowExecutor.java` near `:2439`, `:3008` and `:3160` (per WP2 D-8), with no overlap. No scenario retries, and the failure path adds no statement (the payload is the same write), so the s1/s3 figures do not move.
- **WP13** reconciles `docs/open-issues.md` §1–§2. WP3 edits only Issue 33, the summary table, and new Issues 59–62.

## Decisions for the maintainer

| # | Question | Recommendation | Alternative |
|---|---|---|---|
| MD-1 | The failure's handling consumed a signal, so a truncating Retry would have to give it back. | Refuse with `SIGNAL_CONSUMED_AFTER_FAILURE`, writing nothing (D-6). It is no worse than today's stand-down, and visible. A later package can link memo to row (a V102 column) and release the exact row. | (a) Release a consumed row of that name with an equal payload: inexact for rounded decimals and same-name siblings. (b) Delete the memo and leave the row consumed: re-awaits a fresh signal, but breaks "never discard a signal". |
| MD-2 | Two branches failed independently. | Delete only the attributed (lowest-index) failure. The first Retry ends `FAILED` again, naming the other branch; a second Retry clears it (D-5.5). This keeps Issue 33's "only the propagated await is cleared". | Also delete every last-in-lane failure after the truncation: one Retry, but it also clears a sibling's *caught* last-operation gate. |
| MD-3 | Nothing is attributed (a wrap without the cause, `collectSignals`' own deadline, an older payload with no unique match) and the failure is followed by more work. | Keep that failure. The Retry replays it and the run ends `FAILED` again, with no compensation (D-5.9). Pitfalls rule 2 tells authors to keep the cause. | Today's delete-all for that case: it then stands down (3.3) instead. |
| MD-4 | Should the new SPI method be abstract? | Default to today's two-step, which is the house pattern for every SPI addition this cycle (`getDueTimers(int)`, `storeTime()`). A third-party store keeps exactly today's Retry until it overrides the method. Issue 62 records this. | Abstract: a compile break for third-party stores (nothing is released, per D6). |

## Global Constraints

- **Spec anti-goals (§2), in effect:**
  - No fix makes a run fail or compensate where today it would stand down or be abandoned. The refusal (MD-1) writes nothing, and MD-3's re-failure compensates nothing (D-5.9).
  - History written by the current build keeps replaying, and a mixed fleet keeps working (D-8).
  - **An existing test is changed only as a named step.** The complete list is under "Existing tests changed". An implementer who meets any other failing test that encodes old retry behaviour stops and asks.
- **Code rules.**
  - `maestro-core` never imports Spring. Jackson 3 (`tools.jackson`) only, except `com.fasterxml.jackson.annotation`. JSpecify `@Nullable` on every nullable parameter, return and record component.
  - Javadoc on every public or protected member, with a thread-safety note on every new class. No Lombok.
- **Control-flow `Error`s** (`CLAUDE.md` "Exceptions"):
  - `FailureAttribution` and `RetryPlanner` contain no `catch`.
  - The new store methods catch `SQLException | RuntimeException` only, to roll back, and rethrow.
  - No new code collects failures from a broad `catch`.
- **`StoreUnavailableException` / `FencedWriteException`** are never swallowed, recorded or converted. `retryWorkflow` catches only `WorkflowNotFoundException` from the reopen (→ `NOT_FOUND`); everything else propagates as today.
- **"Never discard a signal."** No step of this plan deletes or flips a signal row (D-6).
- **No log line or meter tag** carries a payload. The new INFO line carries the workflow id (as every retry line does today), the basis and two counts. The sequence list goes at DEBUG.
- **Branch and worktree.**
  - Branch `fix/admin-retry`, cut from `origin/main` at or after `525b287` (WP2 and WP7 merged; WP9's seeded fixture and WP1 are on `main`), in `/Users/rakheendama/Projects/2026/maestro/.claude/worktrees/admin-retry`.
  - Before the first edit, run `git -C <worktree> status` and `git -C /Users/rakheendama/Projects/2026/maestro status`.
  - Never `cd` into the main checkout. This plan lives on `docs/engine-hardening-cycle`; read it from the main checkout's absolute path.
- **Gradle.** Always `./gradlew --no-watch-fs`, from the worktree root. Never two Gradle runs on one module at once. No `git stash`.
- **Commits.**
  - Commit at every checkpoint named below.
  - Chain git steps with `&&`. Pass messages as `-m "$(cat <<'EOF' … EOF)"`.
  - Never run git-mutating commands in parallel.
  - Every message ends with the attribution line your own session supplies.
- **Long runs** are coordinator gates: `./gradlew --no-watch-fs build`, the whole `:maestro-integration-tests:test`, `e2eTest`, chaos and the soak. The coordinator launches them detached, with a log whose header records `pwd`, `git rev-parse HEAD` and a timestamp. A module run named in a step that takes longer than about 5 minutes is handed to the coordinator.
- **Red tests.**
  - Each new red test is run against the unfixed code first and fails on a positive fact (a value, a status, an outcome constant), never on a `ConditionTimeoutException`. Use the "last status within" helpers named in the steps, not `await().until(...)`, for status assertions.
  - Paste the FAIL lines into the commit body.
  - Each fix gets at least one repeatable mutation check, reverted in the same step (`git checkout -- <file>`; `git diff` empty before moving on).
- **Documentation** states only what a test shows, or is labelled "(traced)".
- **New issues** take the next free numbers (`grep -n '^### Issue' docs/open-issues.md | tail -1`; 58 is the highest on `main`). They are 59–62 below if nothing merges first.
- **PR gates (spec §6):**
  - Build & Test.
  - Nightly E2E on the branch.
  - Chaos PR-gate ×3: `./gradlew --no-watch-fs :maestro-integration-tests:e2eTest --rerun-tasks`, three consecutive runs.
  - The 2 h soak: `… -Dmaestro.chaos.soak=true -Dmaestro.chaos.durationMinutes=120`.
  - The seeded-history runs (Task 9 Step 2).

  The maintainer merges.

## Review Focus

1. **A caught failure followed by a fallback, and a later uncaught failure** (3.3). The retried run must complete without running the primary again. → Task 6 `retryAfterCaughtFailure_completesOnRecordedPath` (the `AuditB33` port).
2. **Catch, alert, rethrow** (D10). The primary and the alert must run again live. If the primary fails again, the run is `FAILED` again (alert twice) and never stands down. → Task 6 `retryAfterCatchAlertRethrow_rerunsThePrimaryAndCompletes`, `…_whenThePrimaryFailsAgain_failsAgainAndAlertsAgain`; Task 8 for history cbf5a2a wrote.
3. **Parallel: a completed sibling, a root alert after the join, a nested fork.** No sibling activity may run twice, and nothing may stand down. → Task 6 `retryOfABranchFailure_keepsTheCompletedSiblingAndRerunsTheRootTail`, Task 2 `afterClimbsEveryAncestorAndKeepsEverySibling`.
4. **The failure handling consumed a signal.** Retry must write nothing and leave the signal consumed exactly as recorded. → Task 6 `aSignalConsumedWhileHandlingTheFailure_refusesTheRetryAndWritesNothing`.
5. **A concurrent purge or second Retry.** Neither may leave a half-deleted history. → Task 5 `aStaleVersionDeletesNothing`, `aFailingDeleteRollsEverythingBack`, `aReopenWaitingOnAPurgeFindsTheRowGone`; Task 4 in-memory twins.
6. **Issue 33's two shapes.** Only the propagated await's outcome, memo and row are cleared. → Task 6 `issue33_mainAwaitAfterABranchCaughtGate_clearsOnlyTheMainAwait`, `issue33_lowerBranchTimesOutWhileAHigherBranchCaughtAGate_clearsOnlyTheLowerBranch`.

## Existing tests changed (named; the only allowed edits)

| File | Edit | Task |
|---|---|---|
| `maestro-test/src/test/java/io/b2mash/maestro/test/NodeStoreViewTest.java:46-51` | `WRITES` gains `"reopenFailedInstance"`. `everyStoreMethodIsClassifiedAsAReadOrAWrite` forces this. | 4 |
| `maestro-core/src/test/java/io/b2mash/maestro/core/saga/SagaManagerTerminateRaceTest.java` (`DelegatingStore`, `:396-445`) | Add the forwarding override. | 4 |
| `maestro-spring-boot-starter/src/test/java/io/b2mash/maestro/spring/health/DelegatingWorkflowStore.java` | Add the forwarding override. | 4 |
| `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/support/TerminalEventDelayStore.java` | Add the forwarding override. | 4 |
| `maestro-integration-tests/.../engine/AwaitSignalTimeoutRaceIT.java` (`RacingStore`, `:258`) | Add the forwarding override. | 4 |
| `maestro-integration-tests/.../engine/EnginePostgresParallelIT.java` (`BranchParkBarrierStore`, `:342`) | Add the forwarding override. | 4 |
| `maestro-integration-tests/.../engine/EnginePostgresSignalIT.java` (`ConsumeStealingStore`, `:422`) | Add the forwarding override. | 4 |
| `maestro-core/src/test/java/io/b2mash/maestro/core/engine/VersionedInMemoryStore.java` | Add the override (D-7, in-memory). | 4 |
| `maestro-test/src/test/java/io/b2mash/maestro/test/InMemoryWorkflowStoreTest.java` | Add one `@Nested class ReopenFailedInstance`. No existing line changes. | 4 |
| `maestro-integration-tests/.../kafka/AdminCommandKafkaIT.java`, `AdminCommandWorkflows.java` | Add one test, plus one workflow and activity pair. No existing line changes. | 7 |
| `maestro-integration-tests/.../upgrade/SeededHistoryUpgradeIT.java` | Add one test. Add one bullet to the class Javadoc's "Current-build behaviour" list. No pin changes. | 8 |
| `maestro-integration-tests/.../upgrade/SeededHistory.java` | Add one `node(…, boolean explodeFails)` overload. The existing `node` delegates to it with `true`. | 8 |

`SagaManagerTerminateRaceTest.RaceableStore` (`:542`) is a full store, not a decorator. It inherits the default, as a third-party store would, and no test retries through it.

**Reviewer check, statement-level** (as WP2):
1. `BASE=$(git merge-base origin/main HEAD); git diff --name-only --diff-filter=M "$BASE" HEAD -- '*/src/test/*'` prints exactly the thirteen files above.
2. `git diff --name-only --diff-filter=D "$BASE" HEAD` prints nothing.
3. Every removed line (`^-[^-]`) in those files is part of a named edit. Expect none outside `NodeStoreViewTest`'s set literal, `SeededHistory.node`'s body and the `SeededHistoryUpgradeIT` Javadoc list.
4. `git diff "$BASE" HEAD -- maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/upgrade/cbf5a2a/ maestro-integration-tests/src/test/resources/` is empty.

## Pre-flight: task pairs that share a file or an interface

| Tasks | Shared | Rule |
|---|---|---|
| 1 → 3 | `WorkflowFailureDetail.failedSequences` (JSON `failedSequences`) | 3's tests write the JSON shape 1 produces. Sequential. |
| 1 → 6 | `WorkflowExecutor.java` (`handleWorkflowFailure` in 1, `retryWorkflow` in 6) | Disjoint hunks. Sequential. |
| 2 → 3 | `LaneTree` (`of`, `unplaced`, `isPlaced`, `after`) | 3 consumes. Sequential. |
| 3 → 6 | `RetryPlanner.plan`, `RetryPlan` | 6 consumes. Sequential. |
| 4 → 5 | `WorkflowStore.java` (4 writes the SPI and Javadoc; 5 implements JDBC) | Sequential. 5 edits no SPI text. |
| 4 → 6 | `VersionedInMemoryStore.reopenFailedInstance` | 6's engine tests use it. Sequential. |
| 5 → 7 | The JDBC reopen | 7's IT runs on it. Sequential. |
| 6 → 7, 8 | `RetryOutcome`, the wired `retryWorkflow` | Sequential. 7 and 8 share no file and may run in either order. |
| 6, 9 | `docs/operations.md`, `docs/concepts.md`, `docs/pitfalls.md` (6); `docs/release-notes.md`, `docs/open-issues.md`, `CLAUDE.md` (9) | Disjoint files. |

## File structure

| File | Responsibility |
|---|---|
| `maestro-core/.../context/WorkflowContext.java`, `WorkflowContextInternals.java` | The run-scoped failure-memo registry |
| `maestro-core/.../engine/FailureAttribution.java` (new) | Walk a failure's chain and suppressed exceptions to sequences |
| `maestro-core/.../model/WorkflowFailureDetail.java` | The `failedSequences` component |
| `maestro-core/.../engine/ActivityInvocationHandler.java`, `SignalManager.java` | Register at the five throw sites |
| `maestro-core/.../engine/LaneTree.java` (new) | Lanes from history; `after(F)` |
| `maestro-core/.../engine/RetryPlanner.java`, `RetryPlan.java` (new) | The rule (D-3) |
| `maestro-core/.../spi/WorkflowStore.java` | `reopenFailedInstance` and its contract; `deleteFailureEvents` Javadoc note |
| `maestro-store-jdbc/.../AbstractJdbcWorkflowStore.java` | The one-transaction reopen |
| `maestro-test/.../InMemoryWorkflowStore.java` | The in-memory reopen |
| `maestro-core/.../engine/WorkflowExecutor.java` | `handleWorkflowFailure` payload; `retryWorkflow` wiring; `RetryOutcome.SIGNAL_CONSUMED_AFTER_FAILURE` |
| New tests | `FailureAttributionTest`, `LaneTreeTest`, `RetryPlannerTest`, `WorkflowStoreReopenDefaultTest`, `VersionedInMemoryStoreReopenTest`, `InMemoryWorkflowStoreTest` (+), `PostgresReopenFailedInstanceTest`, `AdminRetryTest`, `AdminCommandKafkaIT` (+), `SeededHistoryUpgradeIT` (+) |

---

### Task 1: Record which failure memos ended the run

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/context/WorkflowContext.java`:
  - add the field;
  - extend the private constructor (`:236`) with a `Map<Throwable, Integer> failureMemos` parameter; the public constructors pass a new synchronized `IdentityHashMap`, and `bounded` (`:334-339`) passes `parent.failureMemos`;
  - add the package-private `void recordFailureMemo(Throwable, int)` and `@Nullable Integer failureMemoSequence(Throwable)`.
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/context/WorkflowContextInternals.java`: the two public static delegates, with Javadoc "Engine-internal: …".
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/model/WorkflowFailureDetail.java`: the third component, the compact constructor (`List.copyOf` when non-null) and the two-argument constructor. The Javadoc says it holds "the sequence numbers of the failure memos the escaping exception carried; empty when none did; `null` when a build before 0.x (WP3) recorded the failure. Used by admin Retry".
- Create: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/FailureAttribution.java`
- Modify: `ActivityInvocationHandler.java:599, :909`; `SignalManager.java:663, :748, :978`; `WorkflowExecutor.java:2898-2901`.
- Create: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/FailureAttributionTest.java`

**Interfaces:**
- Produces:
  - `static List<Integer> FailureAttribution.sequences(WorkflowContext ctx, Throwable failure)`;
  - `WorkflowContextInternals.recordFailureMemo(WorkflowContext, Throwable, int)`;
  - `WorkflowContextInternals.failureMemoSequence(WorkflowContext, Throwable)`;
  - `WorkflowFailureDetail.failedSequences()`.

- [ ] **Step 1: Create the worktree.**

```bash
git -C /Users/rakheendama/Projects/2026/maestro fetch origin && \
git -C /Users/rakheendama/Projects/2026/maestro log origin/main --oneline -1 --grep='kafka-coexistence' && \
git -C /Users/rakheendama/Projects/2026/maestro worktree add .claude/worktrees/admin-retry -b fix/admin-retry origin/main && \
git -C /Users/rakheendama/Projects/2026/maestro/.claude/worktrees/admin-retry status
```

  Expected: the log prints PR #55's merge, and the tree is clean on `fix/admin-retry`.

- [ ] **Step 2: Write the red tests.** `FailureAttributionTest`:
  - **Executor.** `WorkflowExecutor.builder(store, serializer, "svc").instanceLockTtl(Duration.ofSeconds(30)).lifecycleEventsEnabled(false).shutdownTimeout(Duration.ofSeconds(5)).wakeRecheckInterval(Duration.ofMillis(200)).build()` over a `VersionedInMemoryStore`. Activities use `RetryPolicy.noRetry()`.
  - **Helper.** `failed(String id)` waits (≤ 5 s, `TestTerminalWait.awaitStatus`) for `FAILED` and returns the `WORKFLOW_FAILED` event's payload `path("failedSequences").toString()`.

```java
@Test void anUncaughtLiveActivityFailureIsAttributed() { /* a(); b(); c() with c failing */      assertEquals("[3]", failed("w1")); }
@Test void aCaughtFailureHandledAndRethrownIsAttributed() { /* try{p()}catch(e){alert(); throw e;} */ assertEquals("[1]", failed("w2")); }
@Test void aFailureWrappedWithItsCauseIsAttributed() { /* catch(e){ throw new IllegalStateException("x", e); } */ assertEquals("[1]", failed("w3")); }
@Test void aFailureWrappedWithoutItsCauseIsNotAttributed() { /* catch(e){ throw new IllegalStateException("x"); } */ assertEquals("[]", failed("w4")); }
@Test void aBranchFailureRethrownByTheJoinIsAttributed() { /* parallel(List.of(() -> p(), () -> ok())) at seq 1; p fails */ assertEquals("[2001]", failed("w5")); }
@Test void aParkedAwaitTimeoutIsAttributedAtItsOutcomeSlot() { /* awaitSignal("go", String.class, Duration.ofMillis(100)) */ assertEquals("[2]", failed("w6")); }
@Test void anImmediateAwaitTimeoutIsAttributedAtItsSlot() { /* awaitSignal("go", String.class, Duration.ZERO) */ assertEquals("[1]", failed("w7")); }
@Test void collectSignalsOwnDeadlineIsNotAttributed() { /* collectSignals("go", String.class, 2, Duration.ZERO) */ assertEquals("[]", failed("w8")); }
@Test void aFailureReplayedOnRecoveryIsAttributed() {
    // createInstance("w9", RUNNING); appendEvent(1 ACTIVITY_FAILED "Acts.p" {"exceptionType":"java.lang.IllegalStateException","message":"down"});
    // executor.recoverWorkflows(Map.of("CatchAlertRethrow", registration));  — p replays its failure, alert runs live, rethrow
    assertEquals("[1]", failed("w9"));
}
@Test void aBranchContextSharesItsParentsFailureMemos() {
    var root = new WorkflowContext(UUID.randomUUID(), "w", UUID.randomUUID(), "T", "q", "svc", 0, false);
    var branch = WorkflowContext.bounded(root, 0, 2000, 3000);
    var thrown = new IllegalStateException("x");
    WorkflowContextInternals.recordFailureMemo(branch, thrown, 2001);
    assertEquals(2001, WorkflowContextInternals.failureMemoSequence(root, thrown));
}
```

  `aBranchContextSharesItsParentsFailureMemos` does not compile until the accessors exist. Add the two accessors with empty bodies that return `null`, then run.

- [ ] **Step 3: Run the reds.** `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.FailureAttributionTest'`. Expected FAILs:
  - `expected: <[3]> but was: <>` and the matching lines for w2, w3, w5, w6, w7, w9;
  - `expected: <2001> but was: <null>`.

  w4 and w8 fail with `expected: <[]> but was: <>`.

- [ ] **Step 4: Implement** the field, the accessors, `FailureAttribution.sequences` (D-4: chain, suppressed, identity-visited, 100-throwable cap, sorted distinct), the five registrations and the payload.

  In `ActivityInvocationHandler.java:599` the replay throw becomes:

```java
var replayed = reconstructFailure(event, stepName, ctx.workflowId());
WorkflowContextInternals.recordFailureMemo(ctx, replayed, seq);
throw replayed;
```

- [ ] **Step 5: Run green.** Same command. Expected: PASS. Then `./gradlew --no-watch-fs :maestro-core:test` and `./gradlew --no-watch-fs :maestro-test:test`. Expected: PASS. Any other red test stops the task.
- [ ] **Step 6: Mutation checks** (each reverted):
  - delete the `:909` registration: w1 and w2 go red;
  - delete the `:599` registration: w9 goes red;
  - delete the `:978` registration: w6 goes red;
  - drop the suppressed walk: none of these tests goes red. Add `aFailureCarriedAsSuppressedIsAttributed` (`catch(e){ var x = new IllegalStateException("x"); x.addSuppressed(e); throw x; }` → `"[1]"`) and re-run it.
- [ ] **Step 7: Commit:** `feat(engine): record the failure memos that ended a run in WORKFLOW_FAILED (WP3)`. Paste the Step 3 FAIL lines and the mutation results.

---

### Task 2: Rebuild the sequence-space lanes from a history

**Files:**
- Create: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/LaneTree.java`
- Create: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/LaneTreeTest.java`

**Interfaces** (all package-private):
- `static LaneTree of(List<WorkflowEvent> history)`
- `boolean isPlaced(int sequence)`
- `SortedSet<Integer> unplaced()`: every event sequence the walk did not reach, except `WORKFLOW_FAILED` events
- `SortedSet<Integer> after(int sequence)`: D-2's propagation path. It throws `IllegalArgumentException` for an unplaced sequence.
- Fork marker predicate: `static boolean isForkMarker(WorkflowEvent e)`, as defined in D-2. A payload whose `branchCount` is missing, not integral or `< 2` is not a fork.
- Branch bases and the continuation slot come from `SequenceSpace.branchBase` and `SequenceSpace.nextParentSeq`. The latter is passed the workflow id `"lane-tree"`; a recorded fork has already passed its overflow check.

- [ ] **Step 1: Write the tests.** The helper `ev(int seq, EventType type, String step)` builds a `WorkflowEvent` with a null payload. `fork(int seq, int n)` builds the `SIDE_EFFECT` `$maestro:parallel` with payload `{"branchCount":n}`.

```java
@Test void theRootLaneIsContiguousUntilItsFirstGap() {
    var t = LaneTree.of(List.of(ev(1, ACTIVITY_COMPLETED, "a"), ev(2, ACTIVITY_FAILED, "b"), ev(4, ACTIVITY_COMPLETED, "c")));
    assertTrue(t.isPlaced(2));
    assertEquals(new TreeSet<>(Set.of(4)), t.unplaced());
}
@Test void aForkContinuesItsLaneAfterItsBranchBlocks() {
    var t = LaneTree.of(List.of(fork(1, 2), ev(2001, ACTIVITY_COMPLETED, "x"), ev(3001, ACTIVITY_COMPLETED, "y"),
            ev(4001, ACTIVITY_COMPLETED, "z")));
    assertEquals(Set.of(), t.unplaced());
    assertEquals(new TreeSet<>(Set.of(4001)), t.after(2001));         // sibling 3001 excluded
}
@Test void anEventInABranchBlockAfterAGapIsUnplaced() {
    var t = LaneTree.of(List.of(fork(1, 2), ev(2999, ACTIVITY_COMPLETED, "x"), ev(3000, ACTIVITY_COMPLETED, "y")));
    assertFalse(t.isPlaced(2999));                                     // 2001 is empty: branch 0's walk stops there
    assertEquals(new TreeSet<>(Set.of(2999, 3000)), t.unplaced());     // 3000 is a branch base: never handed out
}
@Test void afterClimbsEveryAncestorAndKeepsEverySibling() {
    // root fork at 1 (n=2); branch 0 forks at 2001 (n=2): sub-branches 2002001.., 2003001..; branch 0 continues at 2004001
    var t = LaneTree.of(List.of(fork(1, 2), fork(2001, 2), ev(2002001, ACTIVITY_FAILED, "f"), ev(2002002, ACTIVITY_COMPLETED, "alert"),
            ev(2003001, ACTIVITY_COMPLETED, "sib"), ev(2004001, ACTIVITY_COMPLETED, "b0tail"), ev(3001, ACTIVITY_COMPLETED, "b1"),
            ev(4001, ACTIVITY_COMPLETED, "roottail"), ev(4002, WORKFLOW_FAILED, null)));
    assertEquals(new TreeSet<>(Set.of(2002002, 2004001, 4001, 4002)), t.after(2002001));
}
@Test void afterIncludesTheWholeSubtreeOfAForkInTheSuffix() {
    var t = LaneTree.of(List.of(ev(1, ACTIVITY_FAILED, "p"), fork(2, 2), ev(3001, ACTIVITY_COMPLETED, "a1"),
            ev(4001, ACTIVITY_COMPLETED, "a2"), ev(5001, WORKFLOW_FAILED, null)));
    assertEquals(new TreeSet<>(Set.of(2, 3001, 4001, 5001)), t.after(1));
}
@Test void aWorkflowFailedAfterAGapIsNotUnplaced() {
    var t = LaneTree.of(List.of(ev(1, ACTIVITY_COMPLETED, "a"), ev(3, WORKFLOW_FAILED, null)));
    assertEquals(Set.of(), t.unplaced());
}
@Test void aForkMarkerWithoutABranchCountIsNotAFork() {
    var t = LaneTree.of(List.of(ev(1, SIDE_EFFECT, "$maestro:parallel"), ev(2001, ACTIVITY_COMPLETED, "x")));
    assertEquals(new TreeSet<>(Set.of(2001)), t.unplaced());
}
```

- [ ] **Step 2: Run red.** `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.LaneTreeTest'`. Expected: a compilation failure, because `LaneTree` is missing. Then add the class with stub methods that return empty sets and `false`, and re-run. Expected: FAILs on `isPlaced` (`expected: <true> but was: <false>`) and each `assertEquals`.
- [ ] **Step 3: Implement** per D-2. The walk is iterative, with an explicit stack, so a deep nesting cannot overflow the thread stack. It keeps `Map<Integer, Lane>` from slot to lane, and per lane its ordered slots, its parent lane and its spawning fork slot.
- [ ] **Step 4: Run green.** Mutations:
  - make `after` skip the ancestor climb: `afterClimbsEveryAncestorAndKeepsEverySibling` goes red;
  - make `after` return every sequence above `F`: `aForkContinuesItsLaneAfterItsBranchBlocks` goes red (it includes 3001).

  Revert both.
- [ ] **Step 5: Commit:** `feat(engine): LaneTree rebuilds the sequence-space lanes of a history (WP3)`.

---

### Task 3: The retry planner

**Files:**
- Create: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/RetryPlanner.java`, `RetryPlan.java`
- Create: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/RetryPlannerTest.java`

**Interfaces** (package-private):
- `record RetryPlan(Basis basis, SortedSet<Integer> deleteSequences, SortedSet<String> deleteTimerIds, @Nullable Integer consumedSignalAt)`, with `enum Basis { ATTRIBUTED, MATCHED_MESSAGE, LAST_IN_LANE, LEGACY }` and `boolean refused()` (`consumedSignalAt != null`).
- `static RetryPlan RetryPlanner.plan(String workflowId, List<WorkflowEvent> history)`. It is pure: no store, no clock, no logging.

- [ ] **Step 1: Write the tests.** Each one gives the history and expects `(basis, deleteSequences)`, with `deleteTimerIds` where stated.
  - **Helpers.** `wf(seq, failedSequencesJsonOrNull, exceptionType, message)` builds the `WORKFLOW_FAILED` payload. `af(seq, step, type, msg)` builds an `ACTIVITY_FAILED` payload.
  - **Timer ids.** `timers(…)` is the set the D-3 step 6 rule yields, `sleep-s` and `await-signal-s` for every deleted `s` plus payload ids. Assert `plan.deleteTimerIds().containsAll(Set.of(...))` for the ids named and `plan.deleteTimerIds().size()` for the exact count.

| Test | History | Expected |
|---|---|---|
| `auditB33_deletesOnlyTheFinishFailure` | `1 AF primary, 2 AC fallback, 3 AF finish, 4 WF[3]` | `ATTRIBUTED`, `{3,4}` |
| `d10_truncatesTheAlertWithTheCaughtFailure` | `1 AF primary, 2 AC alert, 3 WF[1]` | `ATTRIBUTED`, `{1,2,3}` |
| `d10InABranch_truncatesTheRootTailAndKeepsTheSibling` | `1 fork(2), 2001 AF a, 2002 AC alert, 3001 AC b1, 3002 AC b2, 4001 AC rootAlert, 4002 WF[2001]` | `ATTRIBUTED`, `{2001,2002,4001,4002}` |
| `twoFailedBranches_deleteOnlyTheAttributedOne` | `1 fork(2), 2001 AF a, 3001 AF b, 4001 WF[2001]` | `ATTRIBUTED`, `{2001,4001}` |
| `aParkedAwaitTimeout_deletesItsParkMemoAndRow` | `1 TS $maestro:awaitSignal:go {timerId:"await-signal-1",…}, 2 ST $maestro:awaitSignal:go, 3 WF[2]` | `ATTRIBUTED`, `{1,2,3}`, ids ⊇ `{await-signal-1}` |
| `issue33Shape1_clearsOnlyTheMainAwait` | `1 fork(2), 2001 fork(2), 2002001 AC x, 2003001 AC y, 2004001 TS gate, 2004002 ST gate, 3001 AC z, 4001 TS main, 4002 ST main, 4003 WF[4002]` | `ATTRIBUTED`, `{4001,4002,4003}` |
| `issue33Shape2_clearsOnlyTheLowerBranch` | `1 fork(2), 2001 TS a, 2002 ST a, 3001 TS gate, 3002 ST gate, 4001 WF[2002]` | `ATTRIBUTED`, `{2001,2002,4001}` |
| `aSleepInTheTruncatedRegion_deletesItsRow` | `1 AF p, 2 TS $maestro:sleep {timerId:"sleep-2"}, 3 TF, 4 WF[1]` | `ATTRIBUTED`, `{1,2,3,4}`, ids ⊇ `{sleep-2}` |
| `aConsumedSignalInTheTruncatedRegion_refuses` | `1 AF p, 2 SR $maestro:awaitSignal:ack, 3 WF[1]` | `refused()`, `consumedSignalAt == 2` |
| `aLegacyPayload_isMatchedByItsMessage` | `seed-alert-failed`'s exact rows from "What Retry does today" (`failedSequences` absent) | `MATCHED_MESSAGE`, `{1,2,3}` |
| `aLegacyPayloadMatchingTwoMemos_fallsToLastInLane` | `1 AF p "down", 2 AF p "down", 3 WF(legacy, "Activity 'p' failed in workflow 'w': down")` | `LAST_IN_LANE`, `{2,3}` |
| `aWrapWithoutCause_deletesOnlyALastInLaneFailure` | `1 AF p, 2 AC fallback, 3 AF finish, 4 WF[]` | `LAST_IN_LANE`, `{3,4}` |
| `aCaughtLastOperationOfABranchWhoseValueTheRootUsed_isKept` | `1 fork(2), 2001 AF a, 3001 AC b, 4001 AC useA, 4002 WF[]` | `LAST_IN_LANE`, `{4002}` |
| `anUnplacedEvent_fallsBackToTheLegacyPlan` | `1 AF p, 3 AC fallback, 4 AF finish, 5 WF[4]` | `LEGACY`, `{1,4,5}` |
| `theLegacyPlanKeepsIssue19sTimeoutRule` | `1 TS a, 2 ST a, 4 TS b, 5 ST b, 6 WF(legacy SignalTimeoutException)`, unplaced at 4 | `LEGACY`, `{4,5,6}`, ids ⊇ `{await-signal-4}` |
| `noWorkflowFailed_deletesNothing` | `1 AC a, 2 AC b` | `LAST_IN_LANE`, `{}` |
| `anAttributedSequenceThatIsNotAFailure_isIgnored` | `1 AC a, 2 AF b, 3 WF[1]` | `LAST_IN_LANE`, `{2,3}` |

- [ ] **Step 2: Run red.** `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.RetryPlannerTest'`. First add `RetryPlan` and a `plan` that returns `new RetryPlan(Basis.LEGACY, new TreeSet<>(), new TreeSet<>(), null)`, so the tests compile. Expected: every test FAILs on its basis or its set.
- [ ] **Step 3: Implement** D-3 in the order given, over `LaneTree`. The legacy plan is a private method that mirrors `InMemoryWorkflowStore.deleteFailureEvents` (`:401-454`) on the event list.
- [ ] **Step 4: Run green.** Mutations:
  - apply last-in-lane also when attributed: `issue33Shape2_clearsOnlyTheLowerBranch` and `twoFailedBranches_deleteOnlyTheAttributedOne` go red;
  - drop the refusal: `aConsumedSignalInTheTruncatedRegion_refuses` goes red;
  - drop the `LEGACY` fallback: `anUnplacedEvent_fallsBackToTheLegacyPlan` goes red;
  - drop `MATCHED_MESSAGE`: `aLegacyPayload_isMatchedByItsMessage` goes red.

  Revert each.
- [ ] **Step 5: Commit:** `feat(engine): RetryPlanner computes what a Retry deletes (3.3, D10, Issue 33)`.

---

### Task 4: `reopenFailedInstance` in the SPI and the in-memory stores

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/spi/WorkflowStore.java`:
  - add the method after `deleteFailureEvents` (`:513`);
  - add one sentence to the class Javadoc's lease-fencing paragraph (`:67-76`): "an operator's terminate and retry (`updateInstance`, `reopenFailedInstance`)";
  - add a note to `deleteFailureEvents`'s Javadoc (`:431-512`): "Since WP3 the engine calls `reopenFailedInstance`; this method is called by its default and by nodes of older builds".
- Modify: `maestro-test/src/main/java/io/b2mash/maestro/test/InMemoryWorkflowStore.java`: the override, after `deleteFailureEvents`.
- Modify (named): `VersionedInMemoryStore.java`, the six decorators and `NodeStoreViewTest.java`.
- Create: `maestro-core/src/test/java/io/b2mash/maestro/core/spi/WorkflowStoreReopenDefaultTest.java`, `maestro-core/src/test/java/io/b2mash/maestro/core/engine/VersionedInMemoryStoreReopenTest.java`
- Modify: `maestro-test/src/test/java/io/b2mash/maestro/test/InMemoryWorkflowStoreTest.java`: one `@Nested class ReopenFailedInstance`.
- Modify: `CLAUDE.md` "Three SPIs": after `deleteFailureEvents`'s neighbours add `default void reopenFailedInstance(WorkflowInstance reopened, Set<Integer> deleteEventSequences, Set<String> deleteTimerIds); // admin retry: deletes + RUNNING CAS in one transaction`.

**Interfaces:**
- Produces: `default void WorkflowStore.reopenFailedInstance(WorkflowInstance reopened, Set<Integer> deleteEventSequences, Set<String> deleteTimerIds)`.

- [ ] **Step 1: Add the SPI method** with the default body `deleteFailureEvents(reopened.id()); updateInstance(reopened);` and this contract (Javadoc, verbatim in substance):
  - "Reopens a `FAILED` instance for an operator retry, as one atomic step."
  - "Deletes this instance's events at exactly `deleteEventSequences`, and its timer rows whose `timerId` is in `deleteTimerIds`, any status."
  - "Writes `reopened` under the same version compare-and-set as `updateInstance`. Either all of it is applied or none of it."
  - "Never touches a signal row or another instance. A sequence or timer id with no row is ignored."
  - "Precondition: `reopened` is the stored row with `status = RUNNING`, `completedAt = null`, `version + 1`."
  - "@throws `OptimisticLockException`: the stored version is not `reopened.version() - 1`; nothing was written."
  - "@throws `WorkflowNotFoundException`: no such instance; nothing was written."
  - "The default is the pre-WP3 retry, `deleteFailureEvents` then `updateInstance`: two writes, by event type, ignoring both sets. A store should override it, and a decorating store should forward it."
  - "Thread safety: as every store method."
- [ ] **Step 2: Write the tests.**
  - **Fixture.** `seed()` creates instance `w` (`FAILED`, version 3) with events 1 `AC`, 2 `AF`, 3 `AC`, 4 `WF`, and timer rows `sleep-3` (`FIRED`) and `sleep-1` (`FIRED`). It also creates a second instance `other` with an event at 2 and a timer `sleep-3`.
  - **`InMemoryWorkflowStoreTest.ReopenFailedInstance`**, with the same names as Task 5:

```java
@Test void reopenDeletesExactlyTheNamedEventsAndTimerRowsAndWritesTheRow() {
    store.reopenFailedInstance(running(w, 4), Set.of(2, 3, 4), Set.of("sleep-3"));
    assertEquals(List.of(1), seqs(w));
    assertEquals(List.of("sleep-1"), timerIds(w));
    assertEquals(WorkflowStatus.RUNNING, store.getInstance("w").orElseThrow().status());
    assertEquals(List.of(2), seqs(other));
    assertEquals(List.of("sleep-3"), timerIds(other));
}
@Test void aStaleVersionDeletesNothing() {
    assertThrows(OptimisticLockException.class, () -> store.reopenFailedInstance(running(w, 3), Set.of(2), Set.of("sleep-3")));
    assertEquals(List.of(1, 2, 3, 4), seqs(w));
    assertEquals(List.of("sleep-1", "sleep-3"), timerIds(w));
    assertEquals(WorkflowStatus.FAILED, store.getInstance("w").orElseThrow().status());
}
@Test void aMissingInstanceIsNotFound() { assertThrows(WorkflowNotFoundException.class, () -> store.reopenFailedInstance(runningUnknown(), Set.of(1), Set.of())); }
@Test void emptySetsOnlyWriteTheRow() { store.reopenFailedInstance(running(w, 4), Set.of(), Set.of()); assertEquals(List.of(1, 2, 3, 4), seqs(w)); }
@Test void signalRowsAreUntouched() { /* seed one consumed signal for w; reopen; */ assertTrue(store.getUnconsumedSignals("w", "go").isEmpty()); }
```

    `running(w, v)` is the stored row with `RUNNING`, `completedAt` null and version `v`. `seqs` and `timerIds` read through `getEvents` and the store's timer listing; use `findTimer` for each id if no listing exists.
  - **`VersionedInMemoryStoreReopenTest`:** the same five tests against `VersionedInMemoryStore`.
  - **`WorkflowStoreReopenDefaultTest`:** a store that implements only the abstract methods and records call names. `reopenFailedInstance(r, Set.of(9), Set.of("x"))` records exactly `["deleteFailureEvents", "updateInstance"]`.
- [ ] **Step 3: Run red.**
  - `./gradlew --no-watch-fs :maestro-test:test --tests 'io.b2mash.maestro.test.InMemoryWorkflowStoreTest'`, then `./gradlew --no-watch-fs :maestro-core:test --tests '*VersionedInMemoryStoreReopenTest' --tests '*WorkflowStoreReopenDefaultTest'`.
  - Expected: the reopen tests FAIL through the default. The first test gets `expected: <[1]> but was: <[1, 3]>`: the default deletes by type and keeps 3. The second gets the default's partial delete: `expected: <[1, 2, 3, 4]> but was: <[1, 3]>`. The default test PASSES.
- [ ] **Step 4: Implement** both overrides (D-7, in-memory: version check before any mutation, `timerLock` then the instance lock). Add the forwarding override to the six decorators, a one-line body: `delegate.reopenFailedInstance(reopened, deleteEventSequences, deleteTimerIds);`. Add `"reopenFailedInstance"` to `NodeStoreViewTest.WRITES`.
- [ ] **Step 5: Run green.** The Step 3 commands, plus `./gradlew --no-watch-fs :maestro-test:test --tests 'io.b2mash.maestro.test.NodeStoreViewTest'`. Expected: PASS. Mutation: in `InMemoryWorkflowStore`, move the version check after the event removal. `aStaleVersionDeletesNothing` goes red. Revert.
- [ ] **Step 6: Commit:** `feat(store): WorkflowStore.reopenFailedInstance, atomic in the in-memory stores (WP3)`.

---

### Task 5: The JDBC reopen, one transaction

**Files:**
- Modify: `maestro-store-jdbc/src/main/java/io/b2mash/maestro/store/jdbc/AbstractJdbcWorkflowStore.java`:
  - the override, after `deleteFailureEvents` (`:979-1003`);
  - `private static final int REOPEN_DELETE_CHUNK = 500`;
  - a class-Javadoc bullet.
- Create: `maestro-store-postgres/src/test/java/io/b2mash/maestro/store/postgres/PostgresReopenFailedInstanceTest.java` (`extends PostgresTestSupport`)

**Interfaces:** consumes the existing `updateInstanceRow` (`:673`), `resolveUpdateFailure` (`:1729`), `executeUpdate` (`:1926`), `inClause` (`:1105`), `setIds`-style binding, `rollbackAfter` and `endTransaction`.

- [ ] **Step 1: Write the tests.** The first five are Task 4's five, with the same names and assertions, and timer rows read with `SELECT timer_id FROM maestro_workflow_timer WHERE workflow_instance_id = ? ORDER BY timer_id`. Then add:

```java
@Test void aFailingDeleteRollsEverythingBack() throws Exception {
    // seed(); a trigger: CREATE FUNCTION maestro_test_refuse() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'refused'; END $$ LANGUAGE plpgsql;
    //          CREATE TRIGGER maestro_test_refuse BEFORE DELETE ON maestro_workflow_timer FOR EACH ROW EXECUTE FUNCTION maestro_test_refuse();
    // (dropped in finally)
    assertThrows(StoreUnavailableException.class,
            () -> store.reopenFailedInstance(running(w, 4), Set.of(2, 3, 4), Set.of("sleep-3")));
    assertEquals(List.of(1, 2, 3, 4), seqs(w));
    assertEquals(WorkflowStatus.FAILED, store.getInstance("w").orElseThrow().status());
    assertEquals(3, store.getInstance("w").orElseThrow().version());
}
@Test void aReopenWaitingOnAPurgeFindsTheRowGone() throws Exception {
    // seed(); on a second connection: BEGIN; SELECT id FROM maestro_workflow_instance WHERE workflow_id='w' FOR UPDATE;
    // start the reopen on a virtual thread; wait (≤ 5 s) until pg_stat_activity shows it waiting on a lock;
    // on the second connection delete w's signals, timers, events and instance; COMMIT;
    var thrown = reopenResult.get(5, TimeUnit.SECONDS);          // the virtual thread's caught exception
    assertInstanceOf(WorkflowNotFoundException.class, thrown);
}
@Test void moreSequencesThanOneChunkAreAllDeleted() {
    // seed 1203 ACTIVITY_COMPLETED rows at 1..1203 on w, WF at 1204; reopen deleting 2..1204
    assertEquals(List.of(1), seqs(w));
}
```

- [ ] **Step 2: Run red.** `./gradlew --no-watch-fs :maestro-store-postgres:test --tests 'io.b2mash.maestro.store.postgres.PostgresReopenFailedInstanceTest'`. Expected FAILs, through the default:
  - `expected: <[1]> but was: <[1, 3]>`;
  - `aFailingDeleteRollsEverythingBack`: `expected: <[1, 2, 3, 4]> but was: <[1, 3]>`, because the default does not touch timers, so no exception is thrown and `deleteFailureEvents` committed;
  - `moreSequencesThanOneChunkAreAllDeleted`: `expected: <[1]> but was: <[1, 2, …]>`;
  - `aReopenWaitingOnAPurgeFindsTheRowGone` may pass, because the default's `updateInstance` also meets the deleted row. Record what is observed.
- [ ] **Step 3: Implement** D-7's JDBC steps 1–4. The `UPDATE` goes first, then the event deletes and the timer deletes in chunks of `REOPEN_DELETE_CHUNK`, then commit. On zero rows, roll back and call `resolveUpdateFailure(reopened, null)`. Map an `SQLException` with `storeFault("Failed to reopen workflow instance " + id + " for retry", e)`.
- [ ] **Step 4: Run green**, then `./gradlew --no-watch-fs :maestro-store-jdbc:test` and `./gradlew --no-watch-fs :maestro-store-postgres:test`. Expected: PASS.
- [ ] **Step 5: Mutations** (each reverted):
  - `conn.setAutoCommit(true)`: `aFailingDeleteRollsEverythingBack` goes red, with the events deleted;
  - drop the timer delete: `reopenDeletesExactlyTheNamedEventsAndTimerRowsAndWritesTheRow` goes red on `timerIds`;
  - chunk size 2000: `moreSequencesThanOneChunkAreAllDeleted` still passes. That is not a mutation that tooth catches; record it and leave it.
- [ ] **Step 6: Commit:** `feat(store-jdbc): reopenFailedInstance deletes and CASes in one transaction (WP3)`.

---

### Task 6: Retry runs the plan; the `AuditB33` reproduction turns green

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java`:
  - `RetryOutcome` (`:1183-1230`): the new constant after `COMPENSATED_NOT_RETRYABLE`;
  - `retryWorkflow` and its Javadoc (`:1232-1405`).
- Create: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/AdminRetryTest.java`
- Docs:
  - `docs/operations.md` §4 (`:166-173`), §10.2 (`:519-521`), §10.7 (`:807-819`), §11.2 (`:1043-1048`);
  - `docs/concepts.md:334, :561`;
  - `docs/pitfalls.md` rule 2 (`:45-86`);
  - the `AdminCommandDispatcher` class Javadoc dispatch table (`:29-31`): "… then calls `retryWorkflow`; every `RetryOutcome` other than `RETRIED` (including `SIGNAL_CONSUMED_AFTER_FAILURE`) is acknowledged as a no-op".

**Interfaces:**
- Consumes `RetryPlanner.plan`, `WorkflowStore.reopenFailedInstance` and `FailureAttribution` (indirectly, through recorded payloads).
- Produces `RetryOutcome.SIGNAL_CONSUMED_AFTER_FAILURE`. Its Javadoc: "The workflow is `FAILED`, and the code that handled the failure consumed a signal before the run failed. Re-running that code would need the signal again, and Maestro cannot give back the exact signal row. Nothing was written. Terminate the workflow, or finish its business outcome by hand."

- [ ] **Step 1: Add the enum constant** (Javadoc above, no use yet), so the tests compile.
- [ ] **Step 2: Port the repro and write the tests.**
  - **The port.** `AdminRetryTest` starts as `evidence/repro-tests/AuditB33RetryCaughtFailureTest.java.txt`, with the constructor ported per WP0's rule. The 12 arguments `(store, null, null, null, serializer, "svc", DEFAULT_KEY_PREFIX, 30s, false, 5s, 30s, observer)` become `WorkflowExecutor.builder(store, serializer, "svc").instanceLockTtl(Duration.ofSeconds(30)).lifecycleEventsEnabled(false).shutdownTimeout(Duration.ofSeconds(5)).wakeRecheckInterval(Duration.ofSeconds(30)).observer(observer).build()`.
  - **Output.** The `System.out` lines are dropped. Assertion values and messages stay verbatim, and the test keeps its name `retryAfterCaughtFailure_completesOnRecordedPath`. Add `assertEquals(1, impl.primaryCalls.get(), "the caught primary failure replays; primary never runs again")`.
  - **Status helper.** `lastStatusWithin(id, wanted, Duration.ofSeconds(5))` polls `store.getInstance(id)` every 20 ms until the status equals `wanted` or the bound elapses, and returns the last status read.
  - **Second-pass helper.** `afterOneRecoveryPass(id)` waits (≤ 5 s) until `!executor.isRunning(id)`, calls `executor.recoverWorkflows(…)` once, and waits 300 ms, exactly as the repro does.

```java
@Test void retryAfterCatchAlertRethrow_rerunsThePrimaryAndCompletes() {
    // CatchAlertRethrow: try { q = acts.primary(); } catch (RuntimeException e) { acts.alert(); throw e; } return acts.finish(q);
    // FAILED with primaryCalls=1, alertCalls=1; outageOver=true; retry
    assertEquals(RetryOutcome.RETRIED, executor.retryWorkflow("d10", reg));
    assertEquals(WorkflowStatus.COMPLETED, lastStatusWithin("d10", WorkflowStatus.COMPLETED, Duration.ofSeconds(5)));
    assertEquals(List.of("1 ACTIVITY_COMPLETED Acts.primary", "2 ACTIVITY_COMPLETED Acts.finish", "3 WORKFLOW_COMPLETED null"), history("d10"));
    assertEquals(2, impl.primaryCalls.get());
    assertEquals(1, impl.alertCalls.get());
    assertEquals(List.of(), observer.standDowns());
}
@Test void retryAfterCatchAlertRethrow_whenThePrimaryFailsAgain_failsAgainAndAlertsAgain() {
    // outage NOT over; retry
    assertEquals(WorkflowStatus.FAILED, lastStatusWithin("d10b", WorkflowStatus.FAILED, Duration.ofSeconds(5)));
    assertEquals(2, impl.alertCalls.get());
    assertEquals("[1]", failurePayload("d10b").path("failedSequences").toString());
    assertEquals(List.of(), observer.standDowns());
}
@Test void retryOfABranchFailure_keepsTheCompletedSiblingAndRerunsTheRootTail() {
    // try { parallel(List.of(() -> acts.primary(), () -> acts.sibling())); } catch (RuntimeException e) { acts.alert(); throw e; } return acts.finish("x");
    // FAILED: siblingCalls=1, alertCalls=1; outageOver=true; retry
    assertEquals(WorkflowStatus.COMPLETED, lastStatusWithin("par", WorkflowStatus.COMPLETED, Duration.ofSeconds(5)));
    assertEquals(1, impl.siblingCalls.get());
    assertEquals(1, impl.alertCalls.get());
    assertEquals(List.of(), observer.standDowns());
}
@Test void twoFailedBranches_needTwoRetries() {
    // parallel(() -> acts.primary(), () -> acts.ledger()) both failing; FAILED [2001]; fix both; retry once
    assertEquals(WorkflowStatus.FAILED, lastStatusWithin("two", WorkflowStatus.FAILED, Duration.ofSeconds(5)));
    assertEquals("[3001]", failurePayload("two").path("failedSequences").toString());
    // retry again
    assertEquals(WorkflowStatus.COMPLETED, lastStatusWithin("two", WorkflowStatus.COMPLETED, Duration.ofSeconds(5)));
    assertEquals(2, impl.primaryCalls.get());
    assertEquals(2, impl.ledgerCalls.get());
}
@Test void issue33_mainAwaitAfterABranchCaughtGate_clearsOnlyTheMainAwait() {
    // parallel(() -> { parallel(() -> "x", () -> "y");                     // nested fork at 2001: branch 0 continues at 2004001
    //                   try { awaitSignal("gate", 100ms); } catch (SignalTimeoutException e) {} return "a"; },   // gate at 2004001/2004002
    //          () -> "b");
    // then awaitSignal("main", 100ms) uncaught (4001/4002 < 2004002) → FAILED; deliver "gate" (late) and "main"; retry
    assertEquals(WorkflowStatus.COMPLETED, lastStatusWithin("i33a", WorkflowStatus.COMPLETED, Duration.ofSeconds(5)));
    assertEquals(1, store.getUnconsumedSignals("i33a", "gate").size());   // the caught gate replayed its timeout
}
@Test void issue33_lowerBranchTimesOutWhileAHigherBranchCaughtAGate_clearsOnlyTheLowerBranch() {
    // parallel(() -> awaitSignal("a", 100ms) /* uncaught */, () -> { try { awaitSignal("gate", 100ms); } catch (SignalTimeoutException e) {} return "b"; });
    // FAILED; deliver "gate" (late) and "a"; retry
    assertEquals(WorkflowStatus.COMPLETED, lastStatusWithin("i33b", WorkflowStatus.COMPLETED, Duration.ofSeconds(5)));
    assertEquals(1, store.getUnconsumedSignals("i33b", "gate").size());
}
@Test void aSignalConsumedWhileHandlingTheFailure_refusesTheRetryAndWritesNothing() {
    // try { acts.primary(); } catch (RuntimeException e) { awaitSignal("ack", String.class, Duration.ofSeconds(5)); throw e; }
    // FAILED after "ack" was delivered and consumed; snapshot history; retry
    assertEquals(RetryOutcome.SIGNAL_CONSUMED_AFTER_FAILURE, executor.retryWorkflow("sig", reg));
    assertEquals(WorkflowStatus.FAILED, store.getInstance("sig").orElseThrow().status());
    assertEquals(before, history("sig"));
}
@Test void aLegacyFailurePayload_isMatchedByMessageAndRerunsTheAlert() {
    // createInstance FAILED; append 1 AF Acts.primary {"exceptionType":"java.lang.IllegalStateException","message":"pricing service down"},
    // 2 AC Acts.alert, 3 WF {"exceptionType":"io.b2mash.maestro.core.exception.ActivityExecutionException",
    //   "message":"Activity 'Acts.primary' failed in workflow 'legacy': pricing service down"}; outageOver=true; retry
    assertEquals(WorkflowStatus.COMPLETED, lastStatusWithin("legacy", WorkflowStatus.COMPLETED, Duration.ofSeconds(5)));
}
```

    `history(id)` returns `"<seq> <type> <step>"`; `failurePayload` is the `WORKFLOW_FAILED` payload. Every activity uses `RetryPolicy.noRetry()`. `activityName` is `Acts`, as in the repro.

- [ ] **Step 3: Run the reds.** `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.AdminRetryTest'`. Expected FAILs:
  - the port: `retry must not turn a caught failure into replay drift ==> expected: <true> but was: <false>`;
  - D10: `expected: <COMPLETED> but was: <RUNNING>`;
  - parallel: `expected: <COMPLETED> but was: <RUNNING>` (traced; record what is observed);
  - `twoFailedBranches_needTwoRetries`: the first retry today deletes both failures and completes, so `expected: <FAILED> but was: <COMPLETED>`. This test pins MD-2 and is red by design;
  - refusal: `expected: <SIGNAL_CONSUMED_AFTER_FAILURE> but was: <RETRIED>`;
  - legacy: `expected: <COMPLETED> but was: <RUNNING>`;
  - Issue 33 shapes 1 and 2: `expected: <COMPLETED> but was: <FAILED>` (traced). Today's heuristic deletes the caught gate's timeout, the highest one, and keeps the propagated timeout, which replays at once.

  Record the observed FAIL line for each.
- [ ] **Step 4: Implement `retryWorkflow`.**
  1. Read; `NOT_FOUND`/`NOT_FAILED` as today.
  2. `var events = store.getEvents(latest.id())`; the compensation guard, unchanged.
  3. `var plan = RetryPlanner.plan(workflowId, events)`. If `plan.refused()`, WARN `"Workflow '{}' was not retried: the code that handled its failure consumed a signal at sequence {} that a retry would need again (RetryOutcome.SIGNAL_CONSUMED_AFTER_FAILURE)"` and return the constant.
  4. Build `running` as today, then `store.reopenFailedInstance(running, plan.deleteSequences(), plan.deleteTimerIds())`. Catch `WorkflowNotFoundException` → WARN "no such instance in this service (purged?)" and return `NOT_FOUND`. `OptimisticLockException` propagates.
  5. Log INFO `"Workflow '{}' reopened for retry: {} event(s) and {} timer id(s) deleted (basis {})"`, with the sequences at DEBUG.
  6. The local check, launch and lifecycle publish follow, unchanged.

  Rewrite the Javadoc's "Why the failure memo has to go" and step 3 to state D-3, D-6 and D-7, removing "deletes exactly the instance's `ACTIVITY_FAILED` and `WORKFLOW_FAILED` events". The class-level "Idempotency" section stays.
- [ ] **Step 5: Run green.** `AdminRetryTest`, then `./gradlew --no-watch-fs :maestro-core:test --tests '*WorkflowExecutorRetryTest' --tests '*AwaitTimeoutRetryRearmTest' --tests '*SignalTimeoutReplayDeterminismTest'`, then `./gradlew --no-watch-fs :maestro-core:test` and `./gradlew --no-watch-fs :maestro-test:test`. Expected: PASS. Two existing tests keep their premise and pass:
  - `WorkflowExecutorRetryTest.deletedMemosOnStillFailed_isHarmless` (no `WORKFLOW_FAILED` gives `LAST_IN_LANE` with nothing failed left);
  - `casWithoutLaunch_recoveryPollerAdopts`.

  If either goes red, stop.
- [ ] **Step 6: Mutation checks** (each reverted):
  - call `store.deleteFailureEvents` + `updateInstance` instead of the reopen: the port, D10 and legacy go red;
  - drop the refusal branch: `aSignalConsumedWhileHandlingTheFailure_refusesTheRetryAndWritesNothing` goes red;
  - revert Task 1's `:909` registration: `retryAfterCatchAlertRethrow_rerunsThePrimaryAndCompletes` goes red (`FAILED`, the alert kept under `LAST_IN_LANE`);
  - make `LaneTree.after` skip the ancestor climb: `retryOfABranchFailure_keepsTheCompletedSiblingAndRerunsTheRootTail` goes red (a stand-down at the root alert).
- [ ] **Step 7: Docs.**
  - **`docs/operations.md` §4** gains "**What a Retry deletes.**" It states D-3's rule in four sentences (attributed truncation; message match for history recorded before this build; last-in-lane when nothing is attributed; today's delete-all when the history has a slot Maestro cannot place) and D-6's refusal. The R61 paragraph stays, re-worded as "the failing await's memo, outcome and row (attributed, Issue 33)".
  - **§10.7's "An admin Retry can surface as drift"** paragraph is replaced by:

    > Retry keeps a failure the workflow caught and recovered from: it deletes only the failure that ended the run and what the run recorded after it while handling that failure (operations §4). A retry of a workflow that failed without the activity's exception in its chain keeps a caught failure that more work followed, and fails again; rethrow the exception, or wrap it with the cause (pitfalls rule 2).

    Tests: `AdminRetryTest`.
  - **§10.2 and `docs/concepts.md:561`:** "Retry never deletes a version marker recorded before the failure".
  - **§11.2:** "Postgres: the retry's one transaction takes the row lock first, so it blocks on (or is skipped by) the purge's `FOR UPDATE SKIP LOCKED`, and a retry that waited on a purged row reports `NOT_FOUND`" (test: `aReopenWaitingOnAPurgeFindsTheRowGone`).
  - **`docs/concepts.md:334`:** "… of a workflow that failed on that await's timeout (the timeout that propagated, not the latest one) …".
  - **`docs/pitfalls.md` rule 2,** after "**Test it:**":

    > **Admin Retry reads the chain.** Retry re-runs the failure that ended the run, found through the exception that escaped `@WorkflowMethod`. Rethrow the `ActivityExecutionException`, or pass it as the cause (`throw new OrderFailed("…", e)`). A failure thrown without it is found only when nothing was recorded after it; otherwise Retry replays it and the workflow fails again.
- [ ] **Step 8: Commit:** `fix(engine): Retry deletes only the failure that ended the run and its handling (3.3, D10, Issue 33)`. Paste the Step 3 FAIL lines and the Step 6 results.

---

### Task 7: `$maestro:retry` over real Kafka and Postgres

**Files:**
- Modify (named): `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/kafka/AdminCommandWorkflows.java`. Add `@Activity interface FallbackActivities { String primary(String in); String fallback(String in); String finish(String quote); }`, a `FallbackActivitiesImpl` `@Component` with an `outageOver` flag, `reset()` and per-method counters, and `@DurableWorkflow(name = "AdminFallbackWorkflow") FallbackWorkflow`, the repro's shape with `@ActivityStub(retryPolicy = @RetryPolicy(maxAttempts = 1))`.
- Modify (named): `AdminCommandKafkaIT.java`. Add one test; `resetFlakyActivity` also calls `fallback.reset()`.

- [ ] **Step 1: Write the test.**

```java
@Test
@DisplayName("$maestro:retry after a caught fallback replays the fallback and completes (3.3)")
void retry_afterACaughtFallback_completesWithoutStandDown() throws Exception {
    var workflowId = "admincmd-fallback-" + UUID.randomUUID().toString().substring(0, 8);
    maestro.newWorkflow(AdminCommandWorkflows.FallbackWorkflow.class,
            WorkflowOptions.builder().workflowId(workflowId).build()).startAsync("seed");
    awaitStatus(workflowId, WorkflowStatus.FAILED, BOUND);
    fallback.fix();
    publish(SIGNAL_TOPIC, workflowId, new SignalMessage(workflowId, "$maestro:retry", null));
    assertEquals(WorkflowStatus.COMPLETED, lastStatusWithin(workflowId, WorkflowStatus.COMPLETED, BOUND));
    assertEquals(1, fallback.primaryCalls());
    assertEquals(1, fallback.fallbackCalls());
    assertEquals(2, fallback.finishCalls());
}
```

  `lastStatusWithin` is a private helper in this class with the same meaning as Task 6's, reading through the injected store.

- [ ] **Step 2: Run red against Task 6 reverted.** `git stash` is forbidden. Check out the Task 5 commit into a scratch worktree (`git worktree add /tmp/<scratch> <task5-sha>`), copy in the two test files, and run there: `./gradlew --no-watch-fs :maestro-integration-tests:test --tests 'io.b2mash.maestro.integration.kafka.AdminCommandKafkaIT'`. Expected: `expected: <COMPLETED> but was: <RUNNING>`. Remove the scratch worktree.
- [ ] **Step 3: Run green** on the branch, same command. Expected: PASS, all five tests. The topics are those `createAdminCommandTopics` already creates; nothing auto-creates a topic.
- [ ] **Step 4: Commit:** `test(integration): $maestro:retry over Kafka replays a caught fallback (3.3)`. Paste the Step 2 FAIL line.

---

### Task 8: History cbf5a2a wrote, retried by this build

**Files:**
- Modify (named): `maestro-integration-tests/.../upgrade/SeededHistory.java`. Add the overload `public MaestroEngineHarness node(String serviceName, RecordingObserver observer, Invocations invocations, boolean explodeFails)`, and make the existing `node` delegate with `true`.
- Modify (named): `SeededHistoryUpgradeIT.java`. Add one test, and this bullet to the class Javadoc's "Current-build behaviour" list:

  > In `{@link #theFailedRunsCbf5a2aRecordedAreRetriedByThisBuild}`: each outcome, status, the appended events, and the invocation counts.

  The bullet takes the plan's `@link` form for a method.

- [ ] **Step 1: Write the test.**

```java
@Test
@DisplayName("the FAILED runs cbf5a2a recorded are retried by this build (WP3)")
void theFailedRunsCbf5a2aRecordedAreRetriedByThisBuild() throws Exception {
    for (var id : List.of(Seeds.ACTIVITY_FAILED, Seeds.ALERT_FAILED)) {
        var seeded = SeededHistory.load().upgrade(SchemaMode.FLYWAY).requireClockWithinFixtureWindow();
        var observer = new RecordingObserver();
        var invocations = new Invocations();
        try (var node = seeded.node(Seeds.SERVICE, observer, invocations, false)) {
            assertThat(node.retry(id, SeedFailingWorkflow.class)).as(id).isEqualTo(WorkflowExecutor.RetryOutcome.RETRIED);
            assertThat(seeded.awaitStatusOrLast(id, COMPLETED, Duration.ofSeconds(30)))
                    .as("%s; stand-downs: %s", id, observer.standDowns()).isEqualTo(COMPLETED);
            assertThat(seeded.events(id).stream().map(e -> e.sequenceNumber() + " " + e.eventType() + " " + e.stepName()).toList())
                    .as(id).containsExactly("1 ACTIVITY_COMPLETED seed-failing.explode", "2 WORKFLOW_COMPLETED null");
            assertThat(invocations.count("seed-failing.explode")).as(id).isEqualTo(1);
            assertThat(invocations.count("seed-failing.alert")).as(id).isEqualTo(0);
            assertThat(observer.standDowns()).as(id).isEmpty();
        }
    }
    var saga = SeededHistory.load().upgrade(SchemaMode.FLYWAY).requireClockWithinFixtureWindow();
    try (var node = saga.node(Seeds.SERVICE, new RecordingObserver(), new Invocations(), false)) {
        assertThat(node.retry(Seeds.SAGA_COMPENSATED, SeedSagaWorkflow.class))
                .isEqualTo(WorkflowExecutor.RetryOutcome.COMPENSATED_NOT_RETRYABLE);
    }
}
```

  Use the constant name `Seeds` declares for `seed-saga-compensated`; check it in the frozen source, which is not edited.

- [ ] **Step 2: Run red on the Task 5 commit** (scratch worktree, as Task 7 Step 2). Run `./gradlew --no-watch-fs :maestro-integration-tests:test --tests '*SeededHistoryUpgradeIT'`. Expected, as observed at planning on a copy of `525b287`:
  - `seed-activity-failed` passes;
  - `[seed-alert-failed; stand-downs: [StandDownCall[reason=STALE_RUN, … detail=append collision at sequence 2]]] expected: COMPLETED but was: RUNNING`.
- [ ] **Step 3: Run green** on the branch: the same command, then `./gradlew --no-watch-fs :maestro-integration-tests:seededHistoryNonUtcTest`. Expected: PASS. Every pre-existing pin is unchanged; the reviewer check item 4 confirms it.
- [ ] **Step 4: Commit:** `test(upgrade): this build retries the FAILED runs cbf5a2a recorded (WP3, D10)`. Paste the Step 2 FAIL line.

---

### Task 9: Release note, issues, seeded history, gates

**Files:**
- `docs/release-notes.md`: a new first subsection under `## Unreleased — Postgres + Kafka only`: `### Fixed — admin Retry re-runs only the failure that ended the run (3.3, Issue 33, D10)`. Its bullets:
  - **What changed.** D-3 in operator words: a caught failure followed by a fallback replays; catch-handle-rethrow re-runs the primary and its handling (activities must be idempotent, as always); the timeout that propagated is the one re-armed. Retry is one transaction.
  - **What an operator sees.**
    - The new `SIGNAL_CONSUMED_AFTER_FAILURE` outcome, logged by the dispatcher as a WARN no-op.
    - The `failedSequences` field in `WorkflowHandle.failure()` and in the instance `output`.
    - A failure thrown without the activity's exception in its chain fails again on Retry when more work followed it.
    - Two independently failed branches need two Retries.
  - **Stores.** `WorkflowStore.reopenFailedInstance` (default: the old two-step retry); decorators must forward it; no migration.
  - **Mixed fleets.** D-8 in three sentences, including the `FAIL_ON_UNKNOWN_PROPERTIES` exception.
- `docs/open-issues.md`:
  - **Issue 33.** Status line `> **Resolved** in WP3 (branch fix/admin-retry): the failing await is the one the WORKFLOW_FAILED payload attributes; pinned by AdminRetryTest.issue33_*.` The summary row (`:331`) becomes `**Resolved**`.
  - **New issues**, each with the house sections (what happens, file:line, harm, fix shape, why it is not WP3) and a summary-table row:
    - **59.** Retry refuses when the failure's handling consumed a signal (MD-1). Fix shape: a V102 column linking `SIGNAL_RECEIVED` to its row, then release that row.
    - **60.** Two independently failed `parallel()` branches need two Retries (MD-2).
    - **61.** A history the lane walk cannot place falls back to the pre-WP3 delete-all Retry: a caught live result the mapper could not write, or an unknown newer operation. 3.3 remains for it. Fix shape: memoize the slot of a live serialization failure.
    - **62.** A store that does not override `reopenFailedInstance` keeps the two-step pre-WP3 Retry (MD-4).
- `tasks/todo.md`: nothing (WP13 reconciles it).

- [ ] **Step 1: Make the edits above.** Take the issue numbers from `grep -n '^### Issue' docs/open-issues.md | tail -1`.
- [ ] **Step 2: Run the seeded history** (named step). `./gradlew --no-watch-fs :maestro-integration-tests:test --tests '*SeededHistoryUpgradeIT' --tests '*SeededHistoryReplayFidelityIT' --tests '*SeededHistoryFixtureIT'`, then `./gradlew --no-watch-fs :maestro-integration-tests:seededHistoryNonUtcTest`. Expected: PASS. Then run the reviewer check (Existing tests changed, items 1–4) and paste its output into the commit body. If any pre-existing pin goes red, stop and report it: WP3 changes no pin (D-8).
- [ ] **Step 3: Javadoc.** `./gradlew --no-watch-fs :maestro-core:javadoc :maestro-store-jdbc:javadoc :maestro-test:javadoc`. Expected: no warning naming a changed member.
- [ ] **Step 4: Commit:** `docs: WP3 release note; resolve Issue 33; file issues 59-62`.
- [ ] **Step 5: Coordinator gates, before the PR merges:**
  - `./gradlew --no-watch-fs build`;
  - the whole `:maestro-integration-tests:test`;
  - nightly E2E on the branch. Its retry target is an uncaught await timeout (operations §4), attributed under WP3;
  - chaos PR-gate ×3;
  - the 2 h soak.

  Every result goes in the review section with its log identity.

---

## Self-review (run 2026-11-16)

- **Brief coverage.**
  1. Today's paths, deletes, epoch/lock/CAS, the defect with a run scenario: "What Retry does today".
  2. Lanes, every slot kind, signals and timers: D-2, D-6.
  3. D10 and parallel safety, with the residue flagged: D-5, MD-2.
  4. Atomicity, fencing, the SPI default, in-memory parity, no migration: D-7, MD-4, Tasks 4–5.
  5. Old history, the seeded test added without a pin edit, a mixed fleet, args hash: D-8, Task 8.
  6. Tests at every layer with named mutations: Tasks 1–8.
  7. Docs, release note, issues: Tasks 6 and 9.
- **Spec.** The WP3 row, D10 ("confirm safe for parallel branches": D-5, proved with one shape left to a second Retry), the anti-goals (Global Constraints), and the gates including the soak. WP8's overlap is in D-9.
- **Names consistent across tasks:**
  - `reopenFailedInstance(WorkflowInstance, Set<Integer>, Set<String>)`;
  - `RetryPlanner.plan(String, List<WorkflowEvent>)`, `RetryPlan(basis, deleteSequences, deleteTimerIds, consumedSignalAt)`, `Basis.{ATTRIBUTED, MATCHED_MESSAGE, LAST_IN_LANE, LEGACY}`;
  - `LaneTree.of/isPlaced/unplaced/after/isForkMarker`;
  - `FailureAttribution.sequences`, `WorkflowContextInternals.recordFailureMemo/failureMemoSequence`;
  - `WorkflowFailureDetail.failedSequences`, `RetryOutcome.SIGNAL_CONSUMED_AFTER_FAILURE`.
- **Proportion.** The code blocks are test assertions, SQL, commands and fixed log or Javadoc copy. Bodies are left to the signatures, except D-3's ordered rule and D-7's statement order, which the tests pin.

## Review section

(The coordinator fills this in: the red and mutation lines per task; the issue numbers given to 59–62; the seeded-history run; gate results with log identity.)
