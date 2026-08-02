# Task 3 Report — Core `EngineObserver` SPI + engine wiring

**Status: COMPLETE**
Worktree: `/Users/rakheendama/Projects/2026/maestro/.claude/worktrees/release-hardening` (branch `worktree-release-hardening`)
Commits: `78a7e41` (RED), `382cf4d` (GREEN), evidence commit follows.

## What was built

New package `io.b2mash.maestro.core.observe` — zero new dependencies
(jspecify + slf4j only; no Spring/Micrometer/OTel in maestro-core):

- `EngineObserver` — interface pasted exactly from design §1.2 (all-default
  no-op methods, `NOOP` constant, replay-flag-per-callback). Javadoc added to
  the methods the design left bare; no signature differs from §1.2.
- `WorkflowInfo`, `ActivityInfo`, `SignalInfo`, `TimerInfo` (records),
  `ParkKind`, `StandDownReason` (enums) — exactly §1.2, JSpecify-annotated,
  `@NullMarked` package.
- `CompositeEngineObserver` — `of(List)` collapsing (0→NOOP, 1→delegate,
  else composite over `List.copyOf`); every callback fans out in order,
  catching `RuntimeException` per delegate (WARN log); `Error`s deliberately
  propagate — `ExecutorShutdownException`/`WorkflowTerminatedException` can
  never be swallowed.

## Wiring (design §1.3 table, implemented row by row)

| Component | Done |
|---|---|
| `WorkflowExecutor` | New widest (12-param) ctor takes `EngineObserver`; all narrower ctors delegate with `NOOP`; observer handed to every component it builds. Emits `workflowStarted` (startWorkflow, after createInstance), `workflowResumed` (launchWorkflow, `replaying==true` and launch succeeded — covers recovery, resume, admin retry), `workflowCompleted`/`workflowFailed` (only inside `transitionToTerminal(...)==true` — converged loser emits nothing), `workflowTerminated` (after the CAS win), `standDown(STALE_RUN, id, "append collision at sequence N")` (handleStaleRunStandDown), `recoveryPass(scanned, adopted)` (recoverWorkflows — covers StartupRecoveryRunner and RecoveryPoller; the poller itself untouched). Also new public `parkedCount()` for the §2.3 gauge. |
| `SignalManager` | New ctor (existing ones delegate `NOOP`). `signalPersisted` after `store.saveSignal` in deliverSignal (workflowType from the instance when it exists, else null); `signalConsumed(false)` in consumeSignal; `signalConsumed(true)` in the `SIGNAL_RECEIVED` replay branch; `workflowParked/Unparked(SIGNAL)` around the live park loop (unparked on signal-consumed and on await-timeout — the two paths where the thread genuinely resumes; shutdown/terminate abandonment emits neither). |
| `DefaultWorkflowOperations` | New ctor; built per-launch with the executor's observer. `timerScheduled(false)` after the live `TIMER_SCHEDULED` append; `timerScheduled(true)` at the top of the replay branch; `timerFired/timerCancelled(true)` in the both-events-memoized replay branches; `timerFired/timerCancelled(false)` inside `recordTimerFired/Cancelled` (fires exactly where the seq+1 event is appended — live wake, heal paths); `workflowParked/Unparked(TIMER)` at both `parkForTimer` call sites. |
| `SagaManager` | New ctor. `workflowCompensating` fired only inside the existing `COMPENSATION_STARTED` replay-skip guard (live only). |
| `ActivityInvocationHandler` / `ActivityProxyFactory` | New widest ctor / `createProxy` overload (existing default `NOOP`). `activityStarted` live only, next to the `ACTIVITY_STARTED` lifecycle publish. `activityCompleted/Failed` with `Duration` measured around `retryExecutor.executeWithRetry` (live) or `Duration.ZERO` + `replayed=true` (replay; failure type read from the memoized payload, `"unknown"` fallback). |
| `WorkflowInstanceLockManager` | New ctor. `instanceLockAcquired` (ACQUIRED case only), `instanceLockRenewFailed` (renewOne catch — handle kept), `instanceLockLost` (renew returned false — handle dropped). |
| `TimerPoller` | **Not instrumented**, per design (workflow-side observation is the exactly-once site). |

## TDD — RED first (verbatim failing output)

Tests were written against a compile-only skeleton (SPI types + ctor
scaffolding storing the observer; composite `of()` unimplemented, zero
emissions). Full log with identity header:
`.superpowers/sdd/release-hardening/evidence/task-3-red.log` (committed,
force-added). Verbatim excerpt — 20 of 23 failing:

```
Replay emits observer callbacks flagged replayed=true — never double-counted > a memoized sleep replays timerScheduled/timerFired with replayed=true FAILED
    org.opentest4j.AssertionFailedError at ObserverReplayNoDoubleCountTest.java:231
Replay emits observer callbacks flagged replayed=true — never double-counted > 3 activities, crash after 2, recover: exactly-once live callbacks; replayed=true on the replayed pair FAILED
    org.opentest4j.AssertionFailedError at ObserverReplayNoDoubleCountTest.java:98
Replay emits observer callbacks flagged replayed=true — never double-counted > a memoized signal consumption replays with replayed=true FAILED
    org.awaitility.core.ConditionTimeoutException at ObserverReplayNoDoubleCountTest.java:180
WorkflowExecutor wires EngineObserver emissions at every design §1 site > recoverWorkflows emits recoveryPass(scanned, adopted) even when nothing is adoptable FAILED
    org.opentest4j.AssertionFailedError at WorkflowExecutorObserverTest.java:277
WorkflowExecutor wires EngineObserver emissions at every design §1 site > live run: workflowStarted, activityStarted/Completed(replayed=false), workflowCompleted FAILED
    org.opentest4j.AssertionFailedError at WorkflowExecutorObserverTest.java:87
WorkflowExecutor wires EngineObserver emissions at every design §1 site > failed run: workflowFailed carries the exception type FAILED
    org.opentest4j.AssertionFailedError at WorkflowExecutorObserverTest.java:118
WorkflowExecutor wires EngineObserver emissions at every design §1 site > signal flow: parked/unparked(SIGNAL) around the live park; signalPersisted + signalConsumed(replayed=false) FAILED
    org.opentest4j.AssertionFailedError at WorkflowExecutorObserverTest.java:200
WorkflowExecutor wires EngineObserver emissions at every design §1 site > saga: workflowCompensating fires once when a live compensation phase starts FAILED
    org.opentest4j.AssertionFailedError at WorkflowExecutorObserverTest.java:262
WorkflowExecutor wires EngineObserver emissions at every design §1 site > terminate: workflowTerminated fires once from the winning CAS, never on ALREADY_TERMINAL FAILED
    org.opentest4j.AssertionFailedError at WorkflowExecutorObserverTest.java:165
WorkflowExecutor wires EngineObserver emissions at every design §1 site > timer flow: timerScheduled/timerFired(replayed=false) + parked/unparked(TIMER) FAILED
    org.opentest4j.AssertionFailedError at WorkflowExecutorObserverTest.java:234
WorkflowExecutor wires EngineObserver emissions at every design §1 site > stale run: DuplicateEventException at top level emits standDown(STALE_RUN) FAILED
    org.opentest4j.AssertionFailedError at WorkflowExecutorObserverTest.java:179
WorkflowInstanceLockManager emits lock observer callbacks > a transient renew error emits instanceLockRenewFailed and keeps the handle FAILED
    org.opentest4j.AssertionFailedError at WorkflowInstanceLockManagerObserverTest.java:75
WorkflowInstanceLockManager emits lock observer callbacks > a winning acquire emits instanceLockAcquired; HELD_ELSEWHERE and NO_BACKEND emit nothing FAILED
    org.opentest4j.AssertionFailedError at WorkflowInstanceLockManagerObserverTest.java:49
WorkflowInstanceLockManager emits lock observer callbacks > lost ownership emits instanceLockLost and drops the handle FAILED
    org.opentest4j.AssertionFailedError at WorkflowInstanceLockManagerObserverTest.java:96
CompositeEngineObserver semantics > every callback fans out to every delegate FAILED
    org.opentest4j.AssertionFailedError at CompositeEngineObserverTest.java:66
CompositeEngineObserver semantics > delegates are invoked in list order FAILED
    org.opentest4j.AssertionFailedError at CompositeEngineObserverTest.java:103
CompositeEngineObserver semantics > of(single) returns the sole delegate itself FAILED
    org.opentest4j.AssertionFailedError at CompositeEngineObserverTest.java:42
CompositeEngineObserver semantics > of(empty) collapses to NOOP FAILED
    org.opentest4j.AssertionFailedError at CompositeEngineObserverTest.java:35
CompositeEngineObserver semantics > an Error (engine control-flow signal) propagates and is never swallowed FAILED
    org.opentest4j.AssertionFailedError at CompositeEngineObserverTest.java:141
CompositeEngineObserver semantics > a delegate throwing RuntimeException is contained; later delegates still run FAILED
    org.opentest4j.AssertionFailedError at CompositeEngineObserverTest.java:125

23 tests completed, 20 failed
> Task :maestro-core:test FAILED
```

(The 3 passers were the trivial pre-implementation truths: `of(multiple)`
returns a composite, and the two no-op-default tests.)

## The required replay pin

`engine/ObserverReplayNoDoubleCountTest` — the core half of the design §8 pin,
per the brief: workflow with 3 activities through the real in-memory harness
(`VersionedInMemoryStore`, which enforces `(workflow_instance_id,
sequence_number)` uniqueness and optimistic locking), crash after 2 (executor
A shut down while parked — same durable state a JVM kill leaves), recovered by
executor B over the same store. Asserts:

- each logical activity completion observed **live exactly once** across the
  crash boundary; the replayed pair (step1, step2) carries `replayed=true` and
  `Duration.ZERO` on executor B; `activityStarted` never fires on replay;
- `workflowStarted` total 1 (recovery emits `workflowResumed`, never a second
  start); `workflowCompleted` total 1; `recoveryPass(1, 1)`;
- companion tests pin the same flag for a memoized `SIGNAL_RECEIVED`
  (`replayed=true`) and a memoized sleep (`timerScheduled/timerFired`
  `replayed=true`, no re-park).

## Files touched

Created (main):
- `maestro-core/src/main/java/io/b2mash/maestro/core/observe/EngineObserver.java`
- `.../observe/WorkflowInfo.java`, `ActivityInfo.java`, `SignalInfo.java`, `TimerInfo.java`
- `.../observe/ParkKind.java`, `StandDownReason.java`
- `.../observe/CompositeEngineObserver.java`, `package-info.java`

Modified (main):
- `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java`
- `.../engine/SignalManager.java`, `DefaultWorkflowOperations.java`,
  `ActivityInvocationHandler.java`, `ActivityProxyFactory.java`,
  `WorkflowInstanceLockManager.java`
- `maestro-core/src/main/java/io/b2mash/maestro/core/saga/SagaManager.java`

Created (test):
- `maestro-core/src/test/java/io/b2mash/maestro/core/observe/RecordingEngineObserver.java` (fixture)
- `.../observe/CompositeEngineObserverTest.java` (8 tests)
- `.../engine/WorkflowExecutorObserverTest.java` (9 tests)
- `.../engine/ObserverReplayNoDoubleCountTest.java` (3 tests)
- `.../engine/WorkflowInstanceLockManagerObserverTest.java` (3 tests)

## Design-conformance notes

1. **Interface is §1.2 paste-exact** (signatures, NOOP, flag placement).
   Records/enums live in their own files (same package, public) rather than
   one file — required for public Javadoc'd types; shapes unchanged.
2. **`ParkingLot.waiterCount()`** (§2.3): the accessor already existed as
   package-private `parkedCount()` (size of the waiter map). Task 3 adds the
   public `WorkflowExecutor.parkedCount()` delegating to it — the surface
   Task 4's gauge registration actually consumes. No duplicate accessor
   added; functional equivalent, name kept consistent with the existing one.
3. **`workflowUnparked` semantics**: emitted only where the workflow thread
   genuinely resumes execution on this node — signal consumed after a park,
   await timeout (workflow may catch `SignalTimeoutException` and continue),
   timer wake. Shutdown/terminate abandonment emits neither boundary; the
   run-ending callbacks tell the adapter what happened. This is the reading
   consistent with §3's segment-span consumer.
4. **Live-execution failures under a duplicate event** (undirected by the
   design): when a live execution fails but a prior `ACTIVITY_COMPLETED`
   already occupies the sequence (loser of a split-brain race), the lifecycle
   ACTIVITY_FAILED publish is skipped (pre-existing behaviour) but the
   observer still sees `activityFailed` with the measured duration — the
   observer measures this node's real executions; the durable ledger's
   exactly-once story is carried by the `replayed` flag, which the pin test
   enforces. Same for the dup-adopt success branch. Flagging for the
   coordinator's awareness; no doc contradiction, just a gap filled.
5. **`standDown` detail**: `"append collision at sequence N"` for
   `STALE_RUN`. §6 stand-down reasons (`UNKNOWN_EVENT_*`) are Task 6's to
   emit; the enum constants ship now as §1.2 requires.
6. **Error discipline**: composite catches `RuntimeException` only; the
   activity failure emission sits in the `ActivityExecutionException` catch,
   so `ExecutorShutdownException`/`WorkflowTerminatedException` (Errors) are
   never observed as failures anywhere. Pinned by
   `CompositeEngineObserverTest.errorPropagates` and implicitly by the
   crash-recovery pin (shutdown while parked → zero `workflowFailed`).

## Test counts and build

- New observer tests: **23** (8 composite + 9 executor wiring + 3 replay pin
  + 3 lock manager), all green.
- `:maestro-core:test`: **312 tests, 0 failures, 0 skipped** (fresh
  `--rerun-tasks` run; evidence log).
- `./gradlew build` (full multi-module, incl. starter, stores, messaging,
  integration tests): **BUILD SUCCESSFUL in 1m 43s** — the old constructors
  delegate with `NOOP`, so every existing `WorkflowExecutor`/proxy
  construction site (starter auto-config, `TestWorkflowEnvironment`,
  `MaestroEngineHarness`) compiles and passes unchanged. Build tail:

```
> Task :maestro-integration-tests:check
> Task :maestro-integration-tests:build
BUILD SUCCESSFUL in 1m 43s
134 actionable tasks: 57 executed, 77 up-to-date
```

Evidence: `.superpowers/sdd/release-hardening/evidence/task-3-red.log`,
`.../task-3-green.log` (both committed with identity headers).

## Handoff to Tasks 4/5/7

- Task 4 consumes: `EngineObserver` bean seam, `CompositeEngineObserver.of`,
  the new `WorkflowExecutor` ctor (12th param), `runningCount()` +
  `parkedCount()` gauge sources, and `createProxy(..., observer)` for
  `ActivityStubBeanPostProcessor`.
- Replay policy is adapter-side, exactly as §1.1 decided: the engine emits
  replay traffic flagged; Micrometer/tracing adapters skip
  `replayed == true`.
- `SignalInfo.traceContext` is always `null` until Task 5 wires the holder.
