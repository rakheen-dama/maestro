# P2 — One loop supervisor: design note

Status: **approved by the maintainer 2026-11-18**, with the author's recommendation on every question in the last section. Written against `main` at `273b1bdc`.

Coordinator's addenda, part of the approved order of work:
- The latch-based loop stop is written once, in the loop supervisor (P2). Order: P1 tasks 1–2 (registry and runs; give-up latch), then P2, then the rest of P1, which shrinks to "the supervisor, the Kafka containers and the Postgres loops join the registry" plus the health probe.
- P2's task for the timer poller, recovery poller and renewer needs the 2 h soak.
- The two figures marked *(inferred)* (the renewer's margin under pool exhaustion; Spring Boot ignoring a custom status) are to be measured or checked during implementation.

Closes, if approved: Issues 67, 68 (both parts), 65, 81, 86, 73; Issue 75 by decision (§6).

Anything marked *(inferred)* was reasoned from code or docs but not confirmed by a test or a run.

---

## 1. Today

Eight background threads, counting the one-shot startup recovery, which the brief's seven leave out. Each one handles its own thread, its errors, its streak and its stop.

| Loop (fixed name) | Module, class | Thread | Period | What guards an iteration | Where the failure streak lives | What health reads | Meters | How it stops |
|---|---|---|---|---|---|---|---|---|
| `timer-poller` | core, `TimerPoller.pollLoop` | `maestro-timer-poller-<svc>` | 5 s; leader-elected (15 s TTL); skew check every node | `BackgroundLoopGuard.failed` for every `Throwable`, store faults included | guard, package-private `consecutiveFailures()`, no public accessor | `timerPollerRunning` = thread alive (`hasTimerPollerStarted`/`isTimerPollerRunning`) | `maestro.loop.errors` (Errors only) | `requestStop`: flag + **interrupt**; `awaitStopped` = join |
| `recovery-poller` | core, `RecoveryPoller.pollLoop` | `maestro-recovery-poller-<svc>` | 60 s full pass; capacity passes ≥ 1 s apart | guard, every `Throwable` | guard, package-private | `recoveryPollerRunning` (or `disabled`) | `loop.errors`; `recovery.scanned/adopted`; `recovery.deferred` gauge | latch + `CapacitySignal.signal()` + **interrupt** (`interruptOnStop` test hook) |
| (one-shot) | core, `OneShotRecovery` | `maestro-startup-recovery-<svc>` | retried every `recovery.poll-interval` until one pass completes | guard, named `recovery-poller` | guard; not exposed | **nothing** (Issue 73) | `loop.errors{recovery-poller}` | latch, no interrupt |
| `signal-janitor` | core, `SignalJanitor.sweepLoop` | `maestro-signal-janitor` | 1 h, sweep first | `sweepOnce` **swallows** `RuntimeException` (WARN, `sweepSwallowedAFailure`); guard sees Errors only | guard, Errors only: a store fault never extends a streak | `signalJanitorRunning` | `loop.errors` | pacer stop + flag + **interrupt** |
| `retention-sweeper` | core, `RetentionSweeper.sweepLoop` | `maestro-retention-sweeper` | 1 h; leader checked before every batch | same as janitor; leader-election fault swallowed as "not leader" | guard, Errors only | `retentionSweeperRunning` (or `disabled`) | `loop.errors`; `retention.sweeps/purged` | pacer stop + flag + **interrupt** |
| `instance-lock-renewer` | core, `WorkflowInstanceLockManager.renewLoop` | `maestro-instance-lock-renewer-<svc>` | 10 s; TTL 30 s; **started lazily** by the first `tryAcquire` | guard; `renewPass` reports a `renewAll` fault to the guard itself | guard, public via `WorkflowExecutor.instanceLockRenewerFailureStreak()` | `instanceLockRenewerRunning` (`idle`/bool) + `…ConsecutiveFailures`, `DOWN` at 2 | `lock.renew.failures{error,lost}`, `loop.errors` | `close(join)`: latch, never interrupts |
| `lock-cleaner` (unnamed) | store-postgres, `PostgresLockCleanerScheduler.loop` | `maestro-pg-lock-cleaner` | 10 min, sleeps first | inline catch: control-flow Error rethrown, RuntimeException WARN, Error ERROR with stack trace, **every run** | none | **nothing** (`isRunning()` is package-private) | none | bean `close()`: flag + **interrupt**, no join |
| `wake-listener` (unnamed) | store-postgres, `PostgresWakeListener.pollLoop` | `maestro-pg-wake-listener` | 500 ms polls; reconnect back-off 1→30 s | inline: `SQLException`/`RuntimeException` drop the session and back off; a driver `Error` → ERROR + stack trace, **1 s pause, same session, no rate limit** (about 3,600 lines an hour, Issue 65) | none (only the local `backoff` variable) | **nothing** (`isConnected()` is public but nothing reads it) | none (`dispatched()`/`reconnects()` not exported) | bean `close()`: latch + 2 s join, no interrupt |

The executor stops the core loops at `WorkflowExecutor.shutdown` step 2 (`requestStop`) and step 6 (`awaitStopped(deadline.remaining())`), and the renewer at step 8. Spring bean destroy stops the two Postgres loops, outside that deadline. The executor also carries `installAndStart`, five `AtomicReference` slots and four `…Started` flags, one set per loop.

Observer containment (Issue 81) also comes in several versions. `WorkflowExecutor.emit` and `WorkflowInstanceLockManager.emit` catch only `RuntimeException`. So do `CompositeEngineObserver.fanOut` and `RetentionSweeper.finish`. Only `backgroundLoopError` and the guard's `notifyObserver` contain an `Error`.

Store retries (Issue 86): `StoreFaultGuard.waitBeforeRetry` logs each retry at `DEBUG`. The guard holds no state and has no observer.

---

## 2. The proposal: `LoopSupervisor`

### Where it lives

There is a new **public** package, `io.b2mash.maestro.core.loop`, in `maestro-core`. It is framework-free, and its Javadoc marks it *internal until 1.0*, as P3 does for the `WorkflowStore` SPI. It is public only because `maestro-store-postgres` must use it. That module already depends on `maestro-core` (through `store-jdbc`) and on the starter. I rejected putting it in `core.spi`, because hosts are not meant to implement it, and in `core.observe`, because it owns threads.

Store-postgres loops never build supervisors themselves. They declare a loop through the executor:

```java
LoopSupervisor WorkflowExecutor.supervise(LoopSpec spec, LoopBody body); // started, joined to the registry
List<LoopSnapshot> WorkflowExecutor.loops();                              // every supervisor this executor owns
```

So every loop gets the executor's observer, its lifecycle registry (P1) and its health view. This removes Issue 65's cross-module wiring: there is no `ObjectProvider<EngineObserver>` in the store module and no `maestroPostgres` contributor. `PostgresLockAutoConfiguration` and `PostgresSignalWakeAutoConfiguration` take the `WorkflowExecutor` bean. The wake listener already does (`@ConditionalOnBean(WorkflowExecutor.class)`). The lock cleaner is a separate bean from the `DistributedLock`, so lock → executor → cleaner has no cycle *(inferred)*.

### API

```java
public record LoopSpec(
        String name,          // fixed, bounded: the meter tag and log name ("timer-poller", "lock-cleaner", …)
        String threadName,
        LoopRole role,        // CRITICAL | HOUSEKEEPING | OPTIMISATION — how health reads a failure (§3)
        Duration initialDelay,// ZERO = run first (janitor, sweeper, recovery); period = sleep first (renewer, cleaner)
        Duration period,      // nominal wait after a success, unless the body says otherwise
        Backoff backoff,      // wait after a failure: Backoff.fixed(period) (every engine loop today) or
                              // Backoff.exponential(1 s, 30 s) (wake listener)
        FailingRule failing,  // when a streak counts as "failing" (§3)
        List<Runnable> onStop // run once by requestStop, to wake a wait the supervisor does not own
) {}                          //   (pacer.requestStop, CapacitySignal::signal)

@FunctionalInterface
public interface LoopBody {
    /** One iteration. Throw to report a failure; never swallow one. */
    Next iterate(LoopContext ctx) throws Exception;
    /** Runs once on the loop thread as it ends, stop or death (wake listener: UNLISTEN and release). */
    default void onExit() {}
}

public sealed interface Next {          // what follows a successful iteration
    static Next afterPeriod();          // the spec's period
    static Next after(Duration d);
    static Next immediately();          // wake listener: the next 500 ms poll
    static Next await(LoopWait wait);   // recovery poller's capacity wait; runs outside the iteration's timing
    static Next done();                 // one-shot recovery: ends DONE, not DEAD
}

public interface LoopContext {
    boolean stopRequested();
    boolean sleep(Duration d);          // waits on the stop latch; false = stop requested; never interrupted
    void note(String key, Object value);// small informational detail, e.g. "leader" -> true
}

public final class LoopSupervisor /* implements P1's participant contract; until then StoppableLoop */ {
    public void requestStop();          // non-blocking, idempotent, NEVER interrupts
    public boolean awaitStopped(Duration bound);
    public LoopSnapshot snapshot();     // any thread, lock-free
}

public record LoopSnapshot(String name, LoopRole role, State state,  // STARTING RUNNING STOPPING STOPPED DEAD DONE
        int consecutiveFailures, @Nullable Instant failingSince, @Nullable Instant lastSuccess,
        @Nullable String lastFailureType, @Nullable Duration inIterationFor, boolean failing,
        Map<String, Object> notes) {}
```

### What the supervisor's thread does

```
join registry (refused → start() throws, no thread)  ── P1
sleep(initialDelay)
while !stopRequested:
    mark iteration start
    try   { next = body.iterate(ctx); succeeded() }
    catch (Throwable t) {
        if t is MaestroControlFlowError → rethrow            (ends the loop: DEAD, or STOPPED if stopping)
        if stopRequested → DEBUG, uncounted, exit
        failed(t): streak++, failingSince, lastFailureType; rate-limited log (today's guard cadence, same text);
                   observer.backgroundLoopFailed(name, type); observer.backgroundLoopError(name, type) if an Error
        next = backoff.after(streak)
    }
    if next is done → DONE, exit
    wait(next) on the stop latch / custom LoopWait; false → exit
finally: body.onExit(); publish final state; leave registry   ── P1
```

- **Control-flow errors and other Throwables.** A `MaestroControlFlowError` is rethrown unchanged, uncounted and unlogged by the iteration guard, then logged by the uncaught handler as today. Every other `Throwable` is contained: `Exception`, `OutOfMemoryError`, `LinkageError` and the rest, with the reasons already given in `BackgroundLoopGuard`'s Javadoc. An `InterruptedException` while not stopping counts as a failure, and the interrupt flag is cleared. Nothing in Maestro interrupts a supervised thread.
- **Thread safety.** `start`, `requestStop`, `awaitStopped` and `snapshot` are safe from any thread. Only the loop thread runs the body. The loop thread publishes each change (iteration start, success, failure, exit) as a new immutable state record in one `AtomicReference`. A snapshot is one volatile read, so it is never torn. There is one allocation per iteration; the wake listener's two a second is negligible.
- **Clocks.** Durations and streak age use an injectable monotonic clock. The `Instant`s shown in details use a wall clock captured at the same moment. Tests inject both.
- **Meters keep their names.** `maestro.loop.errors{loop}` keeps its name and meaning: a contained `Error`. Its tag values change; see §7.

### Where P2 meets P1

The supervisor owns the loop's thread, its wait and its stop. P1's registry owns membership and the deadline. Concretely:

1. `start()` joins the registry. A refused join, because shutdown has begun, is the start failing. That replaces `installAndStart`'s re-check, and is the loop side of Issue 80.
2. The thread leaves the registry in its `finally`, after `body.onExit()` has released what it holds.
3. Shutdown calls `requestStop()` on every member, then `awaitStopped(deadline.remaining())`.

Because every supervised wait is a latch, the loop half of P1's "pollers and lock cleaner stop by latch and bounded join, not interrupt" (rest of Issue 64) is delivered by the supervisor. Who writes it is maintainer question Q4. Until P1 merges, the executor's step 2 and step 6 call the same two methods through `StoppableLoop`, so P2 does not wait for P1.

---

## 3. Health semantics

### Roles and thresholds

The thresholds are fixed constants, documented and not configurable, like `STORE_PROBE_TIMEOUT`.

| Loop | Role | "Failing" means | Effect on `maestro` | Effect on `maestroLiveness` (new) |
|---|---|---|---|---|
| `timer-poller` | critical | streak ≥ 2 **and** failing ≥ max(60 s, 2 × period) → 60 s | `DOWN`, or `UNKNOWN` if the store probe timed out | `DOWN` only if dead |
| `recovery-poller` | critical | streak ≥ 2 and ≥ 120 s | same | dead |
| `startup-recovery` (one-shot) | critical | streak ≥ 2 and ≥ 2 × retry interval (120 s) | same; `DONE` is not a fault | dead (ended without completing, not stopping) |
| `instance-lock-renewer` | critical | streak ≥ 2 (unchanged) **or** locks held and no successful pass for ≥ TTL (30 s) **or** locks held and state ≠ `RUNNING` | `DOWN` regardless of the probe: held locks are expiring | dead, or not running while locks are held |
| `signal-janitor` | housekeeping | streak ≥ 2 (two sweeps in a row, about an hour apart) | `UP`, listed in `degraded` | dead |
| `retention-sweeper` | housekeeping | streak ≥ 2 | `UP` + `degraded` | dead |
| `lock-cleaner` | housekeeping | streak ≥ 2 (about 20 min) | `UP` + `degraded` | dead |
| `wake-listener` | optimisation | disconnected (failing) ≥ `maestro.signal.wake-recheck-interval` (30 s) | `UP` + `degraded`: the recheck is the correctness fallback, so a lost `LISTEN` session costs latency, not correctness (Issue 65) | dead |

### Status of the `maestro` component, in precedence order

1. **`DOWN`** in any of these cases:
   - The store refused the probe (unchanged).
   - Any supervised loop is `DEAD`: started, then ended while not stopping. This rule is unchanged, and now covers all eight loops.
   - The renewer rule in the table holds.
   - A critical loop is failing **while the probe answered `reachable`**.
2. **`UNKNOWN`** when the probe timed out and nothing in step 1 applies. A critical loop that is failing while the probe also times out reads `UNKNOWN`, not `DOWN`: both most likely have the same cause, a pool that is busy rather than broken (3.5a, MD-7). This holds even after real pool timeouts start, outside S5's validity window. A dead loop still reads `DOWN` through a timed-out probe (`aTimedOutProbeDoesNotHideADeadLoop`, unchanged).
3. **`UP`** otherwise, with housekeeping and optimisation faults named in `degraded`. I propose no custom `DEGRADED` status. Spring Boot's `SimpleStatusAggregator` ignores a status that is missing from the configured order *(inferred from Boot's source, not checked in this repo)*, so a custom status would be invisible to the aggregate and surprising elsewhere. This is maintainer question Q2.

### Specific cases

- **Leader-elected loops on a non-leader node.** A non-leader whose election call succeeds has a successful iteration. It is healthy, because it is able to take over. A failed election call (the lock backend threw) is a failed iteration on every node. Today the sweeper swallows that failure; after P2 it counts. The two loops publish `leader: true|false` as an informational note.
- **Issue 68, part 1.** The renewer starts in `WorkflowExecutor.Builder.build()`. A renewer that cannot start fails the build, and so the context. P3 makes the `DistributedLock` mandatory, so there is always something to renew. The lazy start and its `"idle"` state are deleted. Whatever ends the renewer later, the rule "locks held and renewer not `RUNNING` → `DOWN`" reads `heldLocks`, not a start flag.
- **Issue 68, part 2** is observer isolation (§4). Each lost handle's emission is contained on its own, so one throwing observer no longer delays the other lost handles.
- **A renewer stuck in `renewAll`** (a half-open connection) builds no streak but stops renewing. The "no successful pass for ≥ TTL" rule catches it. At that point every held lock really has expired, so `DOWN` is the truth. Other loops have no stuck rule: slow is not failing, and a pool wait can make an iteration take minutes. `inIterationFor` is shown as information only.
- **Startup recovery (Issue 73).** A new detail, `startupRecovery`, reads `"running"` until the recovery poller's first full pass or the one-shot pass completes, then `"complete"`. It is information only, per MD-6. A failing first pass shows through that loop's own failing rule.
- **Pool exhaustion (S5).** S5 requires no pool timeouts (`validity.noPoolTimeouts`), so no loop fails and no streak grows. The probe times out, giving `UNKNOWN`, which is what S5 pins today. The renewer's TTL rule is the one exposure. The scenario's own comment puts the worst pool wait at about 16 s, so the gap between successful renewals is at most about 10 s + 16 s = 26 s, inside 30 s *(derived from the scenario comment, not measured; see risk R2)*.

### Details payload

The flat keys stay, so the S5 classifier `perf/k6/health-probe.js#loopFault` keeps working. That classifier treats any key ending in `Running` with the value `false` as a loop fault.

```json
{ "status": "UP", "details": {
  "store": "reachable",
  "timerPollerRunning": true, "recoveryPollerRunning": true, "retentionSweeperRunning": true,
  "signalJanitorRunning": true, "instanceLockRenewerRunning": true,
  "instanceLockRenewerConsecutiveFailures": 0,
  "lockCleanerRunning": true, "wakeListenerRunning": true,          // new
  "wakeListenerConnected": true,                                     // new; deliberately not "…Running"
  "instanceLocksHeld": 12, "runningWorkflowCount": 12,               // instanceLocksHeld is new
  "startupRecovery": "complete",                                     // new (Issue 73)
  "degraded": [],                                                    // new: failing non-critical loop names
  "loops": {                                                         // new: one entry per supervised loop
    "timer-poller":   { "state": "running", "failing": false, "consecutiveFailures": 0,
                        "lastSuccess": "2026-11-18T10:15:02Z", "leader": true },
    "signal-janitor": { "state": "running", "failing": true, "consecutiveFailures": 2,
                        "failingSince": "2026-11-18T08:00:00Z", "lastSuccess": "2026-11-18T07:00:01Z",
                        "lastFailureType": "io.b2mash.maestro.core.exception.StoreUnavailableException" } } } }
```

`disabled` and `starting` keep their present meaning, both in the flat keys and as `state`.

### Readiness and liveness

| Contributor | Reads the store? | `DOWN` when | Recommended group |
|---|---|---|---|
| `maestro` (unchanged name) | yes, ≤ 2 s probe | the precedence rules above | `readiness`, only if the pod's HTTP traffic needs Maestro (unchanged advice), `timeoutSeconds ≥ 3` |
| `maestroLiveness` (new, `MaestroLivenessHealthIndicator`) | **no**: snapshots only; answers in microseconds, has no Issue 66 exposure and never waits on the pool | a supervised loop is `DEAD`, or locks are held with no running renewer. Both conditions are latched: they cannot heal without a restart | `liveness`: `livenessState,maestroLiveness` |

Only conditions that are permanent and that a restart fixes go into liveness. Failing loops never do: a broken database is not fixed by a restart. `STOPPING`, `STOPPED` and `DONE` are never faults, so a draining pod stays live. This reverses Operations §14's "never liveness" for one narrow contributor, which is maintainer question Q1.

---

## 4. Observer isolation (Issue 81)

There is one rule in one place. Every engine type that takes an `EngineObserver` wraps it with `CompositeEngineObserver.contain(observer)`. That call returns the observer unchanged if it is already a composite. The types are the executor builder, `WorkflowInstanceLockManager` and the loops, which receive it from the executor. Containment then holds for every emission site, including hand-wired observers and tests. That is Ruling 4 extended from the starter to the engine.

`fanOut` changes from catching `RuntimeException` to this:

```java
catch (Throwable t) {
    if (t instanceof MaestroControlFlowError cf) throw cf;   // the engine's control-flow rule
    if (t instanceof VirtualMachineError vme) throw vme;    // OOM, StackOverflow, InternalError: the JVM's
                                                            // distress, not the observer's; it propagates as if
                                                            // the engine had hit it (a loop's supervisor then
                                                            // contains it as it would any loop Error)
    contained(callback, delegate, t);                       // LinkageError, AssertionError, RuntimeException, …
}
```

- **Logging.** The first containment for each (callback, delegate class) pair is logged at `WARN` with the stack trace. Repeats go to `DEBUG` until a call to that pair succeeds. This generalises today's `failingLoopErrorDelegates`. The hot-path cost is one `isEmpty()` check.
- **Counting.** Each containment increments an `AtomicLong` on the composite, exposed as `WorkflowExecutor.observerErrorCount()`. `MaestroEngineGauges` registers it as the `FunctionCounter` `maestro.observer.errors`, with no tags; the log names the callback. It is not counted *through* an observer, because the observer that failed may be the Micrometer one.
- **Code deleted.** `WorkflowExecutor.emit`, `WorkflowInstanceLockManager.emit`, the try/catch in `RetentionSweeper.finish` and the guard's `notifyObserver` all become plain calls. Each lost handle in `renewPass` is then contained on its own (68.2).
- **Not covered.** The code a `wrapActivityAttempt` wrapper runs inside the attempt cannot be contained; that is already documented.

---

## 5. Absorbed store faults (Issue 86)

- `StoreFaultGuard.wrap(store, observer)`. The handler gains an `AtomicLong lastRetryNanos` and a monotonic-clock hook for tests. "Stateless" in its Javadoc becomes "one atomic".
- **Episode.** An episode is node-wide: one guarded store per executor. It starts with the first in-place retry when no retry has happened in the last **60 s**. It ends implicitly once 60 s pass without a retry. Runs, operations and threads all share one episode, because the thing being reported is the database's flakiness, not one run's bad luck.
- **Log.** The first retry of an episode logs one `WARN`: `Store unavailable on <op> — retrying in place (attempt 1/3): <diagnosis>; further in-place retries are counted on maestro.store.retries and logged at DEBUG until 60 s pass without one`. Later retries in the episode log at `DEBUG`, as today. Exhaustion keeps its existing once-per-run latch `WARN`.
- **Counter.** Each in-place retry calls a new callback, `EngineObserver.storeRetried(String operation)`, which feeds the counter **`maestro.store.retries{operation}`**. The `operation` values are `append_event`, `append_event_returning_payload` and `get_event_by_sequence`, a bounded set in the catalog's snake_case value style. The name follows `maestro.lock.renew.failures` and `maestro.loop.errors`. Advisory reads are never retried, so they are never counted.
- Background-loop store faults are not retried, so they are not counted here. From P2 on they count on `maestro.loop.failures` (§7). That also closes Issue 67's "a store fault that is an `Exception` is counted nowhere".

---

## 6. Root health on an exhausted pool (Issue 75): needs a decision

This is what Maestro controls. Its own `maestro` component already answers `UNKNOWN` within 2 s. The new `maestroLiveness` contributor never touches the pool. The root aggregate includes Boot's `db` indicator, which is the host's and waits up to Hikari's `connectionTimeout` (30 s).

| Option | What | Cost |
|---|---|---|
| A (recommended) | Documentation only. In Operations §14: probe groups, never the root. Readiness = `readinessState,maestro` without `db` (or `db` on a separate short-timeout `DataSource`). Liveness = `livenessState,maestroLiveness`. Probe `timeoutSeconds` must exceed the longest pool wait of every member. The support matrix (P3) lists "probing the root `/actuator/health` on a shared pool" as not supported. S5 `advisory.rootHealth` stays an advisory. | none in code |
| B | Opt-in `maestro.health.bound-db-indicator=true`: Maestro replaces Boot's `db` indicator with a version time-bounded like its own probe | Maestro owning a host indicator; surprising; one more property |
| C | Maestro sets default health-group properties through an `EnvironmentPostProcessor` | changes host defaults silently; rejected |

---

## 7. Migration plan

Six tasks. Each can merge on its own, in this order: T1 and T2 are independent; T3 → T4 → T5 → T6. "Red" names the test that fails first; "Pins" names existing tests that must keep passing unchanged unless the entry says otherwise.

**T1 — Observer isolation (Issues 81, 68.2).**
- Change: `CompositeEngineObserver.contain`, the widened `fanOut` with deduplicated logging and a count, wrapping in the builder and the lock manager, `maestro.observer.errors`. Deletes the two `emit` methods and the `finish`/`notifyObserver` catches.
- Red:
  - `ObserverErrorIsolationTest.anErrorFromWorkflowResumedNeitherEndsTheRecoveryPassNorChangesItsAdoptedCount` (Issue 81's done-when)
  - `…anErrorFromWorkflowStartedDoesNotFailTheStart`
  - `WorkflowInstanceLockManagerTest.anObserverErrorOnOneLostLockDoesNotDelayTheOthers` (68.2's done-when)
  - `CompositeEngineObserverTest.aVirtualMachineErrorPropagates`, `…aControlFlowErrorPropagates`
- Pins: `runtimeExceptionContained`, `singleDelegateRuntimeExceptionContained`, `CompositeEngineObserverLoopErrorTest`, `renewer_anObserverErrorOnALostLockStillStandsTheRunDown`.
- **Rewritten:** `errorPropagates` and `errorPropagatesThroughTheSingleDelegateWrapper` pin the old rule. They become "a non-VM, non-control-flow `Error` is contained".

**T2 — Absorbed store faults (Issue 86).**
- Change: `StoreFaultGuard` episode and callback, plus `MicrometerEngineObserver.storeRetried`.
- Red:
  - `StoreFaultGuardTest.anAbsorbedRetryIsCountedOnceAndTheRunContinues` (done-when)
  - `…oneWarnPerEpisode`: two retries 1 s apart give one `WARN` and two counts; a retry 61 s later on the injected clock gives a second `WARN`
  - `MicrometerEngineObserverTest.storeRetriedCountsByOperation`
- Pins: `appendEventIsRetriedAndSucceedsOnThirdAttempt`, `appendEventExhaustsThenLatches`, `advisoryReadNeitherRetriesNorLatches`, `interruptDuringRetryWaitEndsRetries`, `wrapIsIdempotent`, `controlFlowErrorPassesThroughUnwrapped`.

**T3 — The supervisor and the run-first loops.**
- Change: adds `core.loop` and `WorkflowExecutor.loops()/supervise()`, and moves the guard's logging cadence, text unchanged, into the supervisor. Migrates `SignalJanitor`, `RetentionSweeper` and `OneShotRecovery`: their bodies now throw instead of swallowing, and a partial sweep still emits `retentionPass` before rethrowing.
- Deletes those three classes' thread, start, stop, `awaitStopped`, `isRunning` and `consecutiveFailures` code, and both `sweepSwallowedAFailure` flags.
- Red:
  - `LoopSupervisorTest`: control-flow rethrown; Error and Exception contained; streak, `failingSince` and `lastSuccess`; a stop ends a wait at once with no interrupt; a body in progress finishes and the join holds its bound; `done()` gives `DONE`, not `DEAD`; a snapshot read from another thread is never torn
  - `SignalJanitorTest.aStoreFaultExtendsTheStreak`
  - `RetentionSweeperTest.aLeaderElectionFaultExtendsTheStreak`
  - `OneShotRecoveryTest.countsUnderStartupRecovery`
- Pins: `BackgroundLoopGuardTest` (15 tests; moved, assertions unchanged), `BackgroundLoopErrorTest` janitor and sweeper cases (`…aSweepThatSwallowedAStoreFaultDoesNotEndTheStreak` stays true), `BackgroundLoopStopTest`, `BackgroundLoopStartShutdownRaceTest`, `RetentionSweeperTest` (leader, pacing), `LoopErrorCounterWiringTest`.

**T4 — Timer poller, recovery poller, renewer.**
- Change: migrates all three. The recovery poller uses `Next.await` with `onStop(signal::signal)`. The renewer starts in `Builder.build()`.
- Deletes `BackgroundLoopGuard`, the remaining interrupts in `requestStop`, `interruptOnStop`, `installAndStart`, the five loop slots and four started flags (now one map of supervisors), and the renewer's lazy start (`renewerStarted`, `renewerThreadStarter`, and the catch around `startRenewerIfNeeded` in `tryAcquire`).
- Red:
  - `WorkflowInstanceLockManagerTest.theRenewerRunsBeforeTheFirstAcquisition`
  - `WorkflowExecutorBuilderTest.aRenewerThatCannotStartFailsTheBuild`
  - `TimerPollerTest.aStopNeverInterruptsAStoreCall`
- Pins: every `timerPoller_*` and `renewer_*` case in `BackgroundLoopErrorTest`, `renewThrowsKeepsHandle`, `backendFailureLosesNothing`, `oneRenewAllCallPerPassNotOnePerLock`, `lostHandleStandsOnlyThatWorkflowDown`, `ShutdownReturnsBeforeLockReleaseTest`, `BackgroundLoopStopTest`, and the recovery-capacity tests.
- **Deleted tests:** `renewerStartFailureStillReportsAcquired`, `renewerStartFailureResetsLatchForNextAcquisition`, `concurrentTryAcquireRaceAgainstThrowingStarter_noHangAndLatchResets`.
- Gate: touches locks and recovery, so the milestone's standing rule calls for a **2 h soak**, which P2's own gate line omits. See R3.

**T5 — Health, liveness, meters, docs.**
- Change: `MaestroHealthIndicator` reads `executor.loops()` and applies §3. Adds `MaestroLivenessHealthIndicator`, the callback `backgroundLoopFailed(loop, type)`, the counter `maestro.loop.failures{loop}` and the gauge `maestro.loop.failing{loop}` (1 while the loop's health reads failing). The eight series of each are registered at zero. Docs: Operations §14 (groups, Issue 75 per §6) and the observability catalog.
- Red:
  - `aTimerPollerFailingFor60sIsDownAndFor59sIsUp`
  - `aFailingCriticalLoopWithATimedOutProbeIsUnknown`
  - `aJanitorFailingTwiceIsUpAndDegraded`
  - `aNodeHoldingLocksWithNoRunningRenewerIsDown` (68.1's done-when)
  - `aRenewerHoldingLocksWithNoRenewalForATtlIsDown`
  - `startupRecoveryIsRunningThenComplete` (73's done-when)
  - `MaestroLivenessHealthIndicatorTest.aDeadLoopIsDownAStoppedLoopIsUp`, `…neverTouchesTheStore`
  - `MicrometerEngineObserverTest.loopFailureSeriesExistAtZero`
- Pins: `MaestroHealthIndicatorTest` (15 of 16 unchanged, including `aTimedOutProbeDoesNotHideADeadLoop`, `theRenewerIsUpAfterOneFailedPassAndDownAfterTwoInARow` and `bootsDefaultsLeaveAnUnknownMaestroAt200`), `loopErrorSeriesExistAtZeroBeforeAnyFailure`, `perf/eval/s5-pool-health.jq` (S5 re-run is the gate).
- **Deleted test:** `aRenewerThatNeverStartedIsIdleAndNotAFault`, because `idle` no longer exists.

**T6 — The two Postgres loops (Issue 65).**
- Change: the lock cleaner becomes a body supervised through `executor.supervise`, as role housekeeping. The wake listener becomes a body with `Backoff.exponential(1 s, 30 s)` and `onExit` = `releaseWithInterruptCleared`. Its driver-`Error` arm takes the back-off and the rate-limited log, which ends the roughly 3,600 lines an hour. Adds the loop names `lock-cleaner` and `wake-listener` to the `backgroundLoopError` contract and the meters, and the flat health keys. Updates CLAUDE.md's "a new loop does one or the other" to "every loop runs under `LoopSupervisor`".
- Deletes the cleaner's thread and loop, and the listener's `pollLoop`, back-off variable, `awaitStop`, `pauseAfterFailure` and uncaught handler.
- Red:
  - `PostgresLockCleanerSchedulerTest.anErrorMovesLoopErrors` (65's done-when)
  - `PostgresWakeListenerLoopErrorTest.aDriverErrorOnEveryPollBacksOffAndIsRateLimited`
  - starter-level `PostgresLoopsHealthTest.aDeadCleanerReadsDown`, `…aDisconnectedWakeListenerIsDegradedNotDown`
- Pins: `PostgresWakeListenerShutdownTest` (4), `PostgresWakeListenerLoopErrorTest` (11), `PostgresLockCleanerSchedulerTest` (5).
- **Rewritten:** `anErrorFromTheDriverIsContainedAndTheLoopWaitsBeforeItPollsAgain` (the wait becomes the back-off) and `closeWhileARunSwallowsTheInterruptAndThrowsEndsTheCleaner` (no interrupt any more).

### Every public name that changes

| Surface | Change | Justification |
|---|---|---|
| `maestro.loop.errors{loop}` | values grow from 5 to 8: `+lock-cleaner`, `+wake-listener`, `+startup-recovery`. **The one-shot pass moves from `loop=recovery-poller` to `loop=startup-recovery`** | Issue 65; the one-shot is a different loop and `StoppableLoop.loopName()` already calls it that |
| `EngineObserver.backgroundLoopError` contract | eight loop names | same |
| new meters | `maestro.loop.failures{loop}`, `maestro.loop.failing{loop}` (gauge), `maestro.store.retries{operation}`, `maestro.observer.errors` | Issues 67, 86, 81 |
| new callbacks | `backgroundLoopFailed(String loop, String failureType)`, `storeRetried(String operation)`, both default no-ops | same |
| health detail `instanceLockRenewerRunning` | the `"idle"` value disappears | renewer starts with the executor |
| new health details | `lockCleanerRunning`, `wakeListenerRunning`, `wakeListenerConnected`, `instanceLocksHeld`, `startupRecovery`, `degraded`, `loops` | Issues 65, 68, 73 |
| new contributor | `maestroLiveness` | §3 |
| log lines | janitor and sweeper store faults: one `WARN` per sweep becomes the guard cadence (`ERROR` on the first failure, then once per failed sweep, since the interval is ≥ 5 min). The lock cleaner's and wake listener's lines become the guard's. Optimisation role logs its first failure at `WARN` | one cadence for all loops |
| public API | adds `core.loop.*`, `WorkflowExecutor.supervise/loops/observerErrorCount`. The existing `is…Running`/`has…Started`/`…FailureStreak` accessors are kept, reimplemented over snapshots | no removal |

---

## 8. What this closes, what it does not, risks

**Closes:**
- 67: every loop's streak reaches health and meters, store faults included.
- 68: both parts.
- 65: both Postgres loops are supervised, in health and counted.
- 81: observer isolation.
- 86: absorbed store faults.
- 73: the `startupRecovery` detail.
- 75: by the decision in §6.
- The loop half of Issue 64, if Q4 is answered as recommended.

**Does not close:**
- Issue 66, the health probe's interrupt, which belongs to P1.
- Kafka container lifecycle (Issue 69).
- Per-timer `fireTimer` failures inside an otherwise successful timer-poller pass: still one `ERROR` per timer, not counted *(traced, not tested)*.
- A wedged store call in a non-renewer loop: shown as `inIterationFor` only.
- The `ForeignServiceCheck` thread: one advisory read, not supervised.
- `wrapActivityAttempt`'s runtime code.

**Risks:**

- **R1 — Restart storms through liveness.**
  - Prevented by: liveness reads only latched states (`DEAD`, or locks held with no renewer), never a streak or the store. `STOPPING`, `STOPPED` and `DONE` are excluded, so a drain or a deploy cannot trip it.
  - Residual: a deterministic control-flow escape would crash-loop the pod. That is arguably better than a dead loop nobody notices. Docs recommend `failureThreshold ≥ 3`.
  - Readiness hysteresis: critical loops need a streak of at least 2 *and* a minimum duration, so loops that fail and succeed alternately never read `DOWN` (as the renewer already does). Recovery back to `UP` takes one success.
- **R2 — S5 drift.** The renewer TTL rule leaves about 4 s of margin under S5's local profile, from the scenario's own arithmetic, not a measurement. If S5's `exhausted` phase shows a renewer `DOWN`, the fix is not to weaken the rule (locks really did expire). The fix is to keep S5 inside its own premise. S5 runs at T5 and T6.
- **R3 — Stop-path regressions.** T3, T4 and T6 rewrite every loop's thread and stop. The pins in §7 cover each stop path, but T4 touches the renewer and recovery, so it needs the 2 h soak and chaos ×3, not only the P2 gate line. Coordinating with P1 (Q4) avoids writing the latch twice.

---

## 9. Questions for the maintainer

1. **Liveness.** Add `maestroLiveness` (no I/O; `DOWN` only for a dead loop, or locks held with no renewer) and change Operations §14 from "never liveness" to `liveness = livenessState,maestroLiveness`? *Recommend: yes.* A dead loop is the one fault readiness can never repair.
2. **Housekeeping and wake-listener failures.** Should they keep `maestro` `UP` with a `degraded` list and the `maestro.loop.failing` gauge, read `DOWN`, or use a custom `DEGRADED` status? *Recommend: `UP` + `degraded` + gauge.* A failing janitor should not take a pod out of rotation, and a custom status is invisible to Boot's aggregate.
3. **Issue 75.** Documentation and a support-matrix line (option A), or a Maestro-bounded `db` indicator (B)? *Recommend: A.*
4. **Who writes the loop stop latch.** The P2 supervisor, with P1's registry calling `requestStop`/`awaitStopped`, or P1 in each poller first? *Recommend: the supervisor.* P1 keeps the registry, runs, Kafka and the probe interrupt, so the latch is written once.
5. **Eager renewer start.** Start the renewer in `Builder.build()`, so a renewer that cannot start fails startup, relying on P3's mandatory lock? *Recommend: yes.* It deletes the lazy-start machinery that caused 68.1.
