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

---

# Fix round 1

**Status: COMPLETE**
Commits: `90e3432` (F1), `a0accac` (F2), evidence/report commit follows.
Evidence: `.superpowers/sdd/release-hardening/evidence/task-3-fix1-red.log`,
`.../task-3-fix1-green.log`, `.../task-3-fix1-build.log`.

## F1 — a throwing observer read as a lock-backend failure

`WorkflowInstanceLockManager.tryAcquire`: `observer.instanceLockAcquired(...)`
moved **after** the backend `try` whose `catch (Exception)` returns
`NO_BACKEND`, and contained locally (`RuntimeException` only — `Error`s
propagate, per the composite's discipline).

Containment is part of the fix, not extra: the handle is already in
`heldLocks` by then, so letting the exception propagate instead would leak
exactly the same lock (and break the method's documented *"never throws"*
contract at line 126).

**Also fixed, same class, same method family — `renewOne`.**
`observer.instanceLockLost(...)` sat inside `renewOne`'s `try`. A throwing
observer there was (a) mis-reported as a *transient renew failure* and (b) the
`instanceLockRenewFailed` emission in that `catch` block then threw again,
escaping `renewOne` → the `for` loop → `renewLoop()` — which has no try/catch
of its own. The single renewer thread dies and **every** held lock silently
stops being renewed, so live workflows get stolen by peers at TTL expiry. Both
emissions now sit outside the backend `try` and go through the same contained
`emit(...)`.

### The two sites the reviewer flagged

**`WorkflowExecutor` `workflowStarted` (was line 439) — NOT benign; fixed.**
A throw there propagates out of `startWorkflow` *after* `createInstance` and
*after* `tryAcquire` returned `ACQUIRED`. The instance row exists as `RUNNING`,
`launchWorkflow` never runs, and nothing releases the lock — the renewer keeps
it alive forever, `tryAcquire` returns `HELD_ELSEWHERE` for that `workflowId`
on this node from then on, and peers see a renewed lock. Same permanent-stall
shape as F1. Contained.

**`SagaManager` `workflowCompensating` (was line 174) — NOT benign; fixed,
and it also had F2's ordering bug (see below).** A throw escapes `compensate()`
→ `handleWorkflowFailure`'s `try` catches only `CompensationException` → out of
`executeWorkflow` entirely (its catches are `ExecutorShutdownException`,
`WorkflowTerminatedException`, `DuplicateEventException`, `Exception` — and
this throw originates *inside* the `catch (Exception)` block, so nothing
catches it). The `FAILED` transition never happens and the instance is left
stuck in `COMPENSATING`. Contained.

### The remaining `WorkflowExecutor` emissions

Every emission in the class now goes through one contained `emit(callback,
workflowId, Runnable)` — a single uniform rule is cheaper to review than a
per-site asymmetry, and it is a no-op unless an observer throws. Per-site
judgement, for the record:

| Site | Escape consequence |
|---|---|
| `workflowStarted` | lock leaked + `RUNNING` instance never launched (above) |
| `workflowTerminated` | `parkingLot.abandonWorkflow` skipped — an operator-terminated workflow keeps executing activities on this node |
| `workflowResumed` | escapes `resumeWorkflow` → aborts the `recoverWorkflows` loop mid-pass; deterministic if the adapter always throws |
| `recoveryPass` | escapes to `StartupRecoveryRunner` → application startup fails (`RecoveryPoller` does contain it) |
| `workflowCompleted` / `workflowFailed` | see F2 |
| `standDown` | genuinely benign — last statement of `handleStaleRunStandDown`, all durable work done, `executeWorkflow`'s `finally` still runs. Contained only for uniformity. |

## F2 — terminal emission ordered after the append

`workflowCompleted` and `workflowFailed` now fire **immediately after the
winning `transitionToTerminal`**, before `appendEvent` and the lifecycle
publish. Winning that transition is what durably makes this run the completer;
nothing after it may decide whether the outcome is observed.

**Correction to the finding's stated mechanism, for the coordinator's record.**
The cited path — terminal append collides → `workflowCompleted` skipped →
`standDown(STALE_RUN)` counted instead — is **not reachable today at
`WorkflowExecutor:1413/1617`**: the executor's own
`appendEvent(ctx, type, stepName, payload)` swallows *every* exception
including `DuplicateEventException` (`catch (Exception e) { logger.warn(...) }`),
so the collision never escapes to `executeWorkflow`'s
`catch (DuplicateEventException)`. `publishLifecycleEvent` cannot throw either
(`LifecycleEventPublisher.submit` swallows `RejectedExecutionException`). The
reorder is still applied — it is free, and it makes the emission unconditional
on the durable fact rather than on a swallow that a future change could remove.

**The same bug IS real in the compensation path, and that is the RED test.**
`SagaManager.appendEvent` deliberately *rethrows* `DuplicateEventException`
(Issue 18, so a stale run stops executing compensation actions). Its
`COMPENSATION_STARTED` append therefore does escape → `handleStaleRunStandDown`
→ `standDown(STALE_RUN)`, with `workflowCompensating` never emitted for a
compensation phase that genuinely started on this node. Emission moved before
the append.

Containment is again load-bearing here: emitting *before* the append puts the
callback inside `executeWorkflow`'s `try`, where an escaping
`RuntimeException` would land in `catch (Exception e)` → `handleWorkflowFailure`
→ **compensations run for a workflow that just succeeded**. The contained
`emit(...)` closes that.

## Covering tests (5 new; 3 fail without the fix)

`maestro-core/src/test/java/io/b2mash/maestro/core/engine/WorkflowInstanceLockManagerObserverTest.java`
- `a throwing instanceLockAcquired does not report NO_BACKEND and does not leak the lock` — asserts `ACQUIRED` (not `NO_BACKEND`), the callback was attempted, `isHeld` is true, and the caller's `release()` still drops the handle. **RED before the fix.**
- `a throwing instanceLockLost does not kill the renewer — every held lock is still processed` — two held locks, both lost; asserts *both* handles get dropped, i.e. the renewer survived the first throwing callback. **RED before the fix** (the thread dies and the second lock stays held).

`maestro-core/src/test/java/io/b2mash/maestro/core/engine/WorkflowExecutorObserverTest.java`
- `compensation-start append collision: the compensation phase is still reported, not only the stand-down` — `COMPENSATION_STARTED` append collides; asserts `standDown` fires (correct) **and** `workflowCompensating` fires. **RED before the fix.**
- `terminal append collision: a run that durably completed still reports completion, never a stand-down` — instance row durably `COMPLETED`, `workflowCompleted` once, zero stand-downs. **Passes before the fix too** (the swallow above); kept as the regression pin for the new ordering.
- `terminal append collision: a run that durably failed still reports the failure` — same shape for `WORKFLOW_FAILED`. Also a pin, not a RED.

New fault injection: `VersionedInMemoryStore.collideOnEventType(EventType)` —
forces every append of one event type to raise `DuplicateEventException`,
modelling a concurrent runner that already owns that sequence.

## Commands run

### 1. RED — new tests only, main sources reverted to HEAD `006b62e` via `git stash`

```
$ ./gradlew :maestro-core:test --tests '*WorkflowInstanceLockManagerObserverTest' --tests '*WorkflowExecutorObserverTest' --rerun-tasks

> Task :maestro-core:test

WorkflowExecutor wires EngineObserver emissions at every design §1 site > compensation-start append collision: the compensation phase is still reported, not only the stand-down FAILED
    org.opentest4j.AssertionFailedError at WorkflowExecutorObserverTest.java:249

WorkflowInstanceLockManager emits lock observer callbacks > a throwing instanceLockLost does not kill the renewer — every held lock is still processed FAILED
    org.opentest4j.AssertionFailedError at WorkflowInstanceLockManagerObserverTest.java:140

WorkflowInstanceLockManager emits lock observer callbacks > a throwing instanceLockAcquired does not report NO_BACKEND and does not leak the lock FAILED
    org.opentest4j.AssertionFailedError at WorkflowInstanceLockManagerObserverTest.java:121

17 tests completed, 3 failed

> Task :maestro-core:test FAILED
```

(The two terminal-collision pins pass here — see the mechanism correction above.)

### 2. GREEN — full core suite, fixes applied

```
$ ./gradlew :maestro-core:test --rerun-tasks
> Task :maestro-core:testClasses
> Task :maestro-core:test

BUILD SUCCESSFUL in 48s
12 actionable tasks: 12 executed
```

JUnit XML totals for that run: **tests=317 failures=0 errors=0 skipped=0**
(312 before this round + 5 new).

### 3. Full multi-module build

```
$ ./gradlew build
> Task :maestro-integration-tests:check
> Task :maestro-integration-tests:build

BUILD SUCCESSFUL in 1m 42s
134 actionable tasks: 34 executed, 100 up-to-date
```

## Residual risk for the coordinator

Containment is now duplicated at three call sites (`WorkflowExecutor`,
`WorkflowInstanceLockManager`, `SagaManager`) because
`CompositeEngineObserver.of` collapses to the bare delegate at size 1 — design
§1.2, pinned by `CompositeEngineObserverTest.of(single) returns the sole
delegate itself`. `SignalManager`, `DefaultWorkflowOperations` and
`ActivityInvocationHandler` still emit raw; their throws were not in scope this
round and were not audited. If Task 4's adapter can throw at all, the durable
fix is one place — either drop the size-1 collapse, or have the engine wrap
whatever observer it is handed — rather than a guard per call site. Flagging
rather than deciding: it changes a design-doc-binding shape.

---

# Fix round 2 — Coordinator Ruling 4

**Status: COMPLETE**
Commits: `248faab` (Ruling 4 + pins), `f8fb5cc` (design doc + rationale
corrections), evidence/report commit follows.
Evidence: `.superpowers/sdd/release-hardening/evidence/task-3-fix2-red.log`,
`.../task-3-fix2-green.log`, `.../task-3-fix2-build.log`.

## What changed

`CompositeEngineObserver.of(List)` no longer collapses at size 1:

```java
public static EngineObserver of(List<EngineObserver> observers) {
    return observers.isEmpty() ? EngineObserver.NOOP : new CompositeEngineObserver(observers);
}
```

Nothing in production code depended on the collapse (Task 4's wiring does not
exist yet), so this is the only behavioural change. `Error` handling is
untouched: `fanOut` still catches `RuntimeException` only.

The three `emit(...)` helpers from fix round 1 stay, but their Javadoc no
longer claims containment is missing at the seam — it is structural now. Their
justification is narrower and still true: the engine constructors accept *any*
`EngineObserver`, so nothing forces an embedder or a hand-wired test through
`of(...)`, and each guarded site is one where an escape is read as something
else entirely.

## Design doc updates (`observability-versioning-design.md`)

- §10: **RULING 4 (amends §1.2)** appended verbatim, with the rationale as
  given and a note on where it was raised.
- §1.1: decision summary now leads with the amendment.
- §1.2: the paste-ready block is rewritten —
  `case 1 -> observers.getFirst();` is **removed** and replaced with an
  `AMENDED BY RULING 4` comment, so no later task re-implements the collapse.
- §8.1: "`of(List)` collapsing rules" → the wrapping rules.
- `EngineObserver` Javadoc: states that `of` wraps any non-empty list,
  including a single observer.

## Covering tests (4 new, 1 rewritten; 3 fail without Ruling 4)

`observe/CompositeEngineObserverTest`
- `of(single) still wraps — containment must not depend on how many observers are registered` (rewritten from `of(single) returns the sole delegate itself`) — asserts not-same, is a `CompositeEngineObserver`, and still fans out. **RED before.**
- `a lone delegate throwing RuntimeException is contained by the wrapper`. **RED before.**
- `an Error from a LONE delegate propagates too — Ruling 4's wrapper must not widen containment` — `ExecutorShutdownException` and `WorkflowTerminatedException` through the new single-delegate wrapper. This is the carve-out pin: Ruling 4 *added* a log-and-continue layer, and those two types are `Error`s precisely to escape such layers.

`engine/WorkflowExecutorObserverTest`
- `one registered throwing observer cannot corrupt engine control flow at un-hardened sites` — **the requested pin.** Exactly ONE registered observer (`CompositeEngineObserver.of(List.of(throwing))`), an adapter that throws from every callback, driving a workflow that runs an activity, parks on a signal, wakes and runs a second activity. `ActivityInvocationHandler` and `SignalManager` emit **raw** — neither was hand-hardened in round 1 — so containment there can only come from the wrapper. Asserts the instance reaches `COMPLETED` and that `activityStarted`, `activityCompleted`, `signalPersisted`, `signalConsumed`, `workflowParked`, `workflowUnparked` were all still reached. **RED before** (the throw from `activityStarted` propagates into workflow code and the run is recorded FAILED).

## Commands run

### 1. RED — `CompositeEngineObserver.java` reverted to the pre-ruling collapse via `git stash`, new tests applied

```
$ ./gradlew :maestro-core:test --tests '*CompositeEngineObserverTest' --tests '*WorkflowExecutorObserverTest' --rerun-tasks

> Task :maestro-core:test

WorkflowExecutor wires EngineObserver emissions at every design §1 site > one registered throwing observer cannot corrupt engine control flow at un-hardened sites FAILED
    org.awaitility.core.ConditionTimeoutException at WorkflowExecutorObserverTest.java:282

CompositeEngineObserver semantics > of(single) still wraps — containment must not depend on how many observers are registered FAILED
    org.opentest4j.AssertionFailedError at CompositeEngineObserverTest.java:50

CompositeEngineObserver semantics > a lone delegate throwing RuntimeException is contained by the wrapper FAILED
    org.opentest4j.AssertionFailedError at CompositeEngineObserverTest.java:66
        Caused by: java.lang.IllegalStateException at CompositeEngineObserverTest.java:62

23 tests completed, 3 failed

> Task :maestro-core:test FAILED
```

### 2. GREEN — full core suite

```
$ ./gradlew :maestro-core:test --rerun-tasks
> Task :maestro-core:test

BUILD SUCCESSFUL in 49s
12 actionable tasks: 12 executed
```

JUnit XML totals: **tests=320 failures=0 errors=0 skipped=0** (317 after round
1 + 4 new − 1 rewritten in place).

### 3. Full multi-module build

```
$ ./gradlew build
> Task :maestro-integration-tests:build

BUILD SUCCESSFUL in 1m 41s
134 actionable tasks: 35 executed, 99 up-to-date
```

## One-off flake observed (NOT in the observer surface) — for the coordinator

The first full-suite run of this tree failed one **pre-existing, unrelated**
test:

```
Terminating a workflow marks it TERMINATED and stops it without compensating > terminate during compensation stops the compensations that have not run FAILED
    org.opentest4j.AssertionFailedError at WorkflowExecutorTerminateTest.java:209

org.opentest4j.AssertionFailedError: the compensation that had not run yet must never run ==> expected: <false> but was: <true>

320 tests completed, 1 failed
```

Not reproduced since: 3 further full-suite runs on this tree (green), 5
targeted runs on this tree, 3 targeted runs on the round-1 tree. The test
races `terminateWorkflow` against a compensation action that counts its latch
down *before* it parks (`ParkingCompensationWorkflow`, line 457-458), so the
sticky terminate poison and the park registration interleave under load.
Nothing in the observer diff touches that path except a `NOOP` emit reorder in
`SagaManager.compensate` (timing, not semantics — the executor in that test is
built with the narrow constructor, so its observer is `EngineObserver.NOOP`).
`SagaManager.executeSequential`'s Error discipline is intact and was re-read.
Flagging rather than chasing: if it recurs it deserves its own issue, and it is
a genuine product-level race, not a test-only one.

## Concerns after round 2

None on the observer seam. `SignalManager`, `DefaultWorkflowOperations` and
`ActivityInvocationHandler` still emit raw — that is now correct by
construction rather than an audit gap, and the pin above proves it at two of
those three sites.
