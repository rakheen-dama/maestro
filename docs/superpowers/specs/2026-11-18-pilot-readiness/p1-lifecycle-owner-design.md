# P1 — One lifecycle owner: design note

Status: **approved by the maintainer 2026-11-18**, with the author's recommendation on every question in the last section. Written against `main` at `273b1bdc`.

Coordinator's addenda, part of the approved order of work:
- The latch-based loop stop is written once, in the loop supervisor (P2). Order: P1 tasks 1–2 (registry and runs; give-up latch), then P2, then the rest of P1, which shrinks to "the supervisor, the Kafka containers and the Postgres loops join the registry" plus the health probe.
- P2's task for the timer poller, recovery poller and renewer needs the 2 h soak.
- Coordinator's suggestion, not a maintainer decision: P1 also adds a startup WARN when `spring.lifecycle.timeout-per-shutdown-phase` is shorter than `maestro.shutdown.timeout` plus its grace (§3, Not covered).

Marked *(inferred)*: read from code or docs but not run or confirmed against a library source.

## 1. Today

Everything that can be running when `WorkflowExecutor.shutdown()` begins. Steps refer to the numbered comments in `WorkflowExecutor.shutdown(ShutdownDeadline)`.

| What | Started by | Stopped by, when | Wait | Can still act after `shutdown()` returns |
|---|---|---|---|---|
| Workflow runs (one virtual thread per run, plus its branch threads and compensation branches, which the run thread waits for) | `launchWorkflow`, called from `startWorkflow`, `resumeWorkflow` (recovery passes) and `retryWorkflow` (admin) | Step 2: `requestShutdown()` on every entry of `unreleasedRuns`; step 3: `parkingLot.shutdown()`; step 4: `taskQueueGovernor.shutdown()`; step 5: drain | Step 5: per run, the `launched` latch, then `Thread.join(deadline.remaining())`, in **one weakly consistent pass** over `unreleasedRuns` | **Yes.** A run past the deadline keeps executing and can append `ACTIVITY_FAILED`, fail and compensate (Issue 70). A launch that registers after the drain iterator passed is never joined (Issue 80). **`retryWorkflow` has no shutdown check at all**: a `$maestro:retry` command consumed during or after the drain reopens the row and launches a run that nothing joins and whose `SHUTDOWN` cause does not stop activity attempts *(traced, not tested)* |
| Activity attempt thread (`ActivityInvocationHandler`, one per attempt) | the run | the run waits for it; a timed-out attempt is cancelled with an interrupt | — | Only an attempt that ignored its timeout's interrupt, and only its own (pass-through) store calls |
| Timer poller | `StartupRecoveryRunner` → `startTimerPoller` | step 2 `requestStop()` (**interrupts**), step 6 `awaitStopped(remaining)` | remaining | Its current store call; `pollDueTimers` has no stop check between timers, so a batch can keep calling `fireTimer` *(inferred)* |
| Recovery poller | `startRecoveryPoller` | same; passes stop on the `INTERRUPTED` supplier and a per-instance `shuttingDown` check | remaining | Its current store call; a launch already past its `shuttingDown` check (Issue 80 window) |
| One-shot startup recovery | `startOneShotRecovery` | same, no interrupt | remaining | as above |
| Signal janitor, retention sweeper | `startSignalJanitor`, `startRetentionSweeper` | same; pauses end on `BatchPacer`'s latch, the store call is **interrupted** | remaining | the current `DELETE` / purge transaction |
| Foreign-service check | `startForeignServiceCheck` | nothing | not waited | one read |
| Instance-lock renewer | `WorkflowInstanceLockManager` (first acquire) | step 8 `close(remainingAtMost(2 s))`: latch, no interrupt (WP5) | ≤ 2 s of remaining | a renewal pass in progress |
| Lifecycle-event publisher (daemon platform thread) | executor constructor | step 9 `shutdown(remainingAtMost(5 s))` | ≤ 5 s of remaining | nothing once forced down |
| Engine signal container (Kafka) | `SignalSubscriptionRunner` (an `ApplicationRunner`) → `KafkaWorkflowMessaging.subscribeSignals` | `KafkaWorkflowMessaging.destroy()`, bean destruction, after every lifecycle phase | `container.stop()`, up to Spring Kafka's 10 s each, **outside the deadline** | **Yes**: consumes through the drain and after it; `deliverSignal` writes; admin commands (`retryWorkflow` above) |
| `@MaestroSignalListener` containers | `MaestroSignalListenerBeanPostProcessor.afterSingletonsInstantiated` | its `destroy()`; a `BeanPostProcessor`, so destroyed after the `DataSource` | 10 s each, outside | **Yes**, against a closed pool (Issue 69) |
| Postgres lock cleaner | `PostgresLockAutoConfiguration.postgresLockCleanerScheduler` (starts in the `@Bean` method) | `destroyMethod = "close"`: **interrupt**, no join | none | its `DELETE`; an interrupt mid-read evicts a pooled connection (Issue 64) |
| Postgres wake listener (holds one connection) | `PostgresSignalWakeAutoConfiguration` (starts in the `@Bean` method) | `destroyMethod = "close"`: latch, no interrupt, join | its own 2 s, outside | nothing (WP10, Issue 38) |
| NOTIFY hand-off threads | `SignalManager` / `PostgresSignalNotifier` (≤ 1,000 in flight) | nothing | none | one `NOTIFY` each |
| Health probe thread | `MaestroHealthIndicator.probeStore` per health call | **interrupt** on its 2 s timeout (`Probe.abandon`) | — | one read (Issue 66) |

Spring side: `GracefulShutdownHandler` (phase `MAX - 1024`, same phase as the web server's graceful drain) runs `shutdown()` on the `maestro-shutdown` virtual thread; the executor's inferred destroy method is a second `shutdown()` call that waits for the first (D-5). Host `@KafkaListener` containers stop earlier, in phase `MAX - 100`.

The shape of the problem: four owners (`shutdown()`, two `destroy()`s, two `destroyMethod`s) and three tracking structures (`unreleasedRuns`, five `AtomicReference` loop slots, two container lists). Each new path was patched where it was found: R22(1)'s re-check after `put`, Issue 87's second map, `installAndStart`'s re-check, WP10's latch.

## 2. The proposal

### Shape

One class in `maestro-core`, package `io.b2mash.maestro.core.engine`, no Spring:

```java
public final class LifecycleRegistry {
    public enum State { OPEN, DRAINING, CLOSED }
    public enum Kind { CONSUMER, LOOP, TASK }          // RUN is engine-only, see joinRun

    /** What a member does when asked to stop: return at once, never interrupt, idempotent, may come before it started. */
    public interface Member {
        String name();
        void requestStop();
    }

    /** Empty when the registry is no longer OPEN: the caller must not start the work. */
    public Optional<Membership> join(Kind kind, Member member);

    public final class Membership { public void leave(); }   // idempotent; the member's last act

    // package-private, for WorkflowExecutor only
    Membership joinRun(String workflowId, RunCancellation cancellation);  // null when refused
    List<Membership> close();                          // OPEN -> DRAINING, returns the snapshot
    boolean awaitNoRuns(ShutdownDeadline deadline);
    boolean awaitNoMembers(ShutdownDeadline deadline);
    List<Membership> remaining();
    void terminate();                                  // DRAINING -> CLOSED
}
```

`WorkflowExecutor.lifecycle()` exposes it so the Kafka and Postgres modules can call `join`. The wait methods take a `ShutdownDeadline`, not a `Duration`. That way the type system enforces the rule that no shutdown step has a timeout of its own.

**Join before start, leave after release.** A member joins before it does any work, and leaves as the last thing it does, once it has returned everything it holds (the instance lock, a pooled connection, a consumer). The registry does not join threads. It waits for its member set to empty, and every `leave()` wakes that wait.

### States

- `OPEN`: joins are admitted.
- `DRAINING`: set by `close()` at the start of `shutdown()`. Every join is refused. Members leave as they finish.
- `CLOSED`: set just before `shutdown()` returns. Anything still registered is reported as still running, and every run among them has been given up (§3).

### Thread safety

One `ReentrantLock` guards the state and the member set, with one `Condition` that `leave()` signals.

- `join` checks the state and adds the member in one critical section.
- `close` flips the state and copies the snapshot in one critical section.

So every member either is in `close()`'s snapshot or was refused. No weakly consistent iteration is left, which is Issue 80's root cause. `requestStop()` is called on the snapshot *outside* the lock, so member code never runs under it, and no lock-ordering cycle with member code is possible. A member that left between the snapshot and the call gets a `requestStop()` it ignores, because the contract makes the call idempotent and harmless after the end. Waits use `awaitNanos(deadline.remaining())`. An interrupt of the shutdown thread ends the wait and restores the flag, as `shutdown()` does today. The cost on the start path is two short lock sections per run, against today's two `ConcurrentHashMap` writes *(inferred negligible; load scenario S1 would show otherwise)*.

### Runs

The `RunCancellation` is created at join time, not inside `launchWorkflow`, so a stop request can reach a run that does not have a thread yet. The membership is taken **first, before the instance lock is acquired**. It is handed to the run thread, whose final `finally` calls `leave()` where `unreleasedRuns.remove` is today. Every path that does not hand it off leaves in one outer `finally`, after its lock release. `runningWorkflows` stays, because it serves lookups by workflow id (queries, terminate, wake permits).

### What "refused" means per caller

| Caller | Join point | Refused means |
|---|---|---|
| `startWorkflow` (host code, an HTTP request finishing during the web drain, `MaestroClient`) | first statement, before `tryAcquire` and `createInstance` | `IllegalStateException`, as the entry check throws today. Nothing is written and no lock is taken |
| Recovery adoption (`resumeWorkflow`, from a pass) | before `tryAcquire` | returns `false`; the existing `finally` releases the slot reservation; no adoption CAS, no epoch bump |
| Admin retry (`retryWorkflow` via `AdminCommandDispatcher`) | before `reopenFailedInstance` | `IllegalStateException`; the dispatcher already rethrows, so the Kafka record is not acked and is redelivered to a peer. The row stays `FAILED`. This closes the unguarded path in §1 |
| Admin terminate | not a member | allowed while the store answers: it writes `TERMINATED` and latches the local run; an operator's intent is durable |
| Signal delivery (`deliverSignal`) | not a member | allowed: the signal row is the durable part ("never discard a signal"); a parked waiter's wake is moot because parks are already abandoned |
| Timer fire (`fireTimer`) | not a member; its caller, the timer poller, is | allowed: a `markTimerFired` CAS during the drain is durable and correct; the poller checks its stop flag between timers so its batch ends promptly |
| Loops, consumers, one-shot threads | `join`, then start | not started; `start*` throws `IllegalStateException` as today |

### Spring mapping

`GracefulShutdownHandler` stays the only Maestro `SmartLifecycle`, in phase `MAX - 1024`. Maestro's Kafka containers become members (`Kind.CONSUMER`), and the **engine's shutdown stops them**:

- `requestStop()` calls `container.stop(callback)`, which stops asynchronously in Spring Kafka *(inferred; to confirm for 4.x in the task's test)*.
- The callback calls `leave()`.

The containers stop at the start of the drain and are waited for against the same deadline. Partitions move to peers while this node drains, not after bean destruction, and no Maestro consumer outlives `shutdown()`.

To join the engine's own container without the starter knowing Kafka, `WorkflowMessaging` gains `default @Nullable Member subscriptions() { return null; }`. `SignalSubscriptionRunner` joins it before calling `subscribeSignals`, and the Kafka implementation stops every container it created and starts none after `requestStop()`. The post-processor joins itself as one member before starting its containers. `KafkaWorkflowMessaging.destroy()` and the post-processor's `destroy()` remain as backstops for a context where the executor never shut down: request only, no wait. Question 1 asks whether to take Issue 69's alternative instead.

### Where P1 meets P2

P2's supervisor owns each loop's thread, failure streak and stop mechanics. P1 owns *when* loops are asked to stop and how long they are waited for. The contract:

1. Loops are registry members. The supervisor may join once per loop (recommended, so the summary names each loop) or once as a whole.
2. The registry asks every member to stop in one pass at the start of shutdown, and waits for loops only after the run drain, against what is left of the deadline.
3. A loop leaves only once its last store call has returned. It is never interrupted and never given a timeout of its own.
4. The instance-lock renewer is **not** a member: runs need it during the drain. `shutdown()` closes it after the drain, as today. P2 may supervise it for health, but its stop stays at step S7 below.

If P2 merges first, P1's loop task becomes "the supervisor joins the registry".

## 3. A run still executing at the deadline (Issue 70)

| Option | Effect | Verdict |
|---|---|---|
| A. Fence its `RunLease` at give-up: bump `lease_epoch` under the version CAS, a self-adoption | Durable; refuses even a write that somehow bypasses the guard | Rejected. It is one store write per stuck run *after* the deadline has passed, so the step has no budget left and breaks the one-deadline rule. It fails in exactly the common overrun case, a slow store. A peer's adoption bumps the epoch anyway once the lock's TTL expires |
| B. A registry-level "abandoned" latch checked at the store boundary and the stop points, like `StoreFaultGuard`'s `STORE_UNAVAILABLE` latch | In memory, costs nothing at the deadline; precedent in the test kit's `NodeStoreView.kill`, which refuses a retired node's writes | **Recommended** |
| C. Document only | A routine deploy can still record `FAILED` and compensate | Rejected: this is the outcome graceful shutdown exists to prevent |

### Mechanism (B)

1. At give-up (step S4 below), the registry calls `cancellation.abandon()` on every run still registered. This sets a sticky `abandoned` flag on `RunCancellation`, separate from the cause precedence so that a later `TERMINATED` cannot clear it. It also requests `SHUTDOWN`, which ends any retry backoff.
2. `StoreFaultGuard` refuses **every** call made under the bound `WorkflowContext` of an abandoned run with `ExecutorShutdownException`, checked on entry and again before each retry attempt. Two scopes are exempt:
   - `advisory(...)` reads, which `statusForLog` uses inside the handlers; a thrown `Error` would escape their `catch (RuntimeException)`.
   - `passThrough(...)`, the activity body's own calls on behalf of other workflows.

   The store-unavailable latch refuses only appends because its stop points come before every other write. A given-up run has no legitimate write left, so refusing everything has no exceptions to reason about.
3. The stop points that already call `throwIfStoreUnavailable` get the same check for `abandoned`: `RetryExecutor.throwIfStopRequested`, `ActivityInvocationHandler` before an attempt, `DefaultWorkflowOperations`, `SagaManager`. So an abandoned run starts no further activity.
4. In `executeWorkflow`, `catch (Error)` and `catch (Exception)` route an abandoned run to `handleShutdownSuspension` before `failAndCompensate`, as `storeUnavailableLatched` does. Workflow code that caught `Throwable` and threw something else therefore still cannot fail the run.
5. `ExecutorShutdownException` already exists in the sealed `permits` list, so no new control-flow type is added. Its abandon reason is `SHUTDOWN`, which is what Issue 70 asks for. Its Javadoc widens to "this node stopped and gave up on this run".

### Why this cannot lose a result recovery needs

From the store's point of view, a refused call is indistinguishable from the node dying just before sending it.

- What is recorded is always a prefix of what the run would have written: one per branch for parallel branches, each in its own sequence space. A crash at that instant leaves exactly the same history.
- A crash at any instruction is what the engine is built and tested to recover from (crash points in `DeterminismChecker`, chaos runs).
- Atomic store operations (`appendEventAndConsumeSignal`, `completeInstance`) are refused whole, never half-applied.
- Non-atomic sequences, such as a `TIMER_SCHEDULED` memo followed by `saveTimer`, already self-heal on replay (`findTimer`), because a crash can split them today.

The concrete cases:

- **An append already past its entry check when the latch lands** reaches the store. It is a legitimate result, and replay returns it.
- **An activity that finishes after the latch** has its outcome refused. The adopting node finds no memo at that sequence and runs the activity again. That is at-least-once, which the idempotency rule already demands for crashes and for the lock-loss window.
- **A workflow body that succeeds after the latch** has its `COMPLETED` write refused. The row stays active, and the peer replays every memo and completes it.

Today's behaviour, by contrast, records outcomes produced while host beans are being destroyed, a state no crash test covers.

**The one real change** is that a *successful* late result is discarded and paid for again with one more execution (Question 2).

Kept from today:

- **The lock row.** The run's `finally` still releases the lock if the backend answers. Otherwise the TTL expires it, as now.
- **The fence.** Once a peer adopts the run, the lease fence backs the latch.

### Not covered

If `spring.lifecycle.timeout-per-shutdown-phase` is shorter than `maestro.shutdown.timeout`, Spring can destroy host beans before the latch is set. `docs/operations.md` §15.4 already requires a phase timeout of at least the shutdown timeout plus 2 s. Nothing enforces that.

## 4. Stopping without interrupt (rest of Issue 64; Issue 66)

### Four pollers, one-shot recovery and lock cleaner

Each becomes a member that:

- waits on its own stop latch (`CountDownLatch.await(interval)`) instead of `Thread.sleep`;
- has a `requestStop()` that sets `running = false`, counts the latch down, wakes `BatchPacer` and `CapacitySignal`, and **never interrupts**;
- leaves in the `finally` of its thread body.

Loop-specific changes:

- **Recovery poller:** passes take the poller's own stop flag instead of the `INTERRUPTED` supplier. `TaskQueueGovernor.admitRecovery` no longer needs the interrupt arm to end a pass.
- **Timer poller:** checks its stop flag between timers.
- **Foreign-service check:** joins as `Kind.TASK`.
- **Lock cleaner:** joins from its auto-configuration before it starts, through `ObjectProvider<WorkflowExecutor>`, and starts standalone if no executor exists *(inferred; no bean cycle, since the executor depends on the `DistributedLock`, not the scheduler)*. `close()` becomes request-only.
- **Wake listener:** joins the same way, so it is asked to stop at S2 and is waited for against the deadline. Its `close()` keeps its 2 s join as a bean-destruction backstop; that wait is reached only if S5 hit the deadline. It protects the pool that WP10 fixed.

A store call in progress at stop finishes, and the loop is waited for at most until the deadline. The six interrupt-reliant tests Issue 64 lists are rewritten to say "a call in progress is not interrupted, and no further call is made".

### Health probe (Issue 66)

The options:

- **(a) Keep the interrupt.** Rejected: one evicted connection per timed-out health call on a slow database.
- **(b) Drop the interrupt and rely on the single-flight probe.** A probe stuck on a half-open socket with no driver `socketTimeout` holds health at `UNKNOWN` indefinitely, which fails Issue 66's second done-when.
- **(c) Recommended: (b) plus supersede-once.** Once a timed-out probe has been abandoned for longer than 5 × the probe bound (10 s), the next health call starts one fresh probe beside it. At most one superseded probe may exist, so at most two connections are held. When the store answers again, the fresh probe reports `REACHABLE`. The support matrix (P3) should also recommend pgjdbc `socketTimeout` / `tcpKeepAlive`.

The probe is not a registry member: it reads only, and the web server stops calling it.

## 5. Ordering and time budget

```
Phase MAX-100   (Spring)  host @KafkaListener containers stop
Phase MAX-1024  GracefulShutdownHandler.stop(cb) -> maestro-shutdown thread -> WorkflowExecutor.shutdown():
  S0  deadline = ShutdownDeadline.start(maestro.shutdown.timeout)
  S1  registry.close(): OPEN -> DRAINING, snapshot                                   no wait
  S2  requestStop() on every member: Kafka containers (async stop), loops, tasks,
      Postgres cleaner + wake listener, runs (RunCancellation SHUTDOWN);
      parkingLot.shutdown(); taskQueueGovernor.shutdown()                           no wait
  S3  registry.awaitNoRuns(deadline)                                                remaining()
  S4  give-up: abandon() every run still registered                                 no wait
  S5  registry.awaitNoMembers(deadline): consumers, loops, tasks                    remaining()
  S6  taskQueueGovernor.reclaimShutdownPermits()                                    no wait
  S7  instanceLockManager.close(deadline.remainingAtMost(2 s))                      <= remaining()
  S8  lifecycleEventPublisher.shutdown(deadline.remainingAtMost(5 s))               <= remaining()
  S9  registry.terminate(): DRAINING -> CLOSED; summary from registry.remaining()  no wait
Phase MAX-2048  web server stops
Bean destruction: executor destroy = second shutdown() caller (remaining + 2 s grace, D-5, unchanged);
                  Kafka destroy(), cleaner close() = backstops that do not wait; wake listener close() as in §4; DataSource
```

**Proof of the one-deadline rule.** Only S3, S5, S7 and S8 block. Each takes `remaining()` or `remainingAtMost(cap)` of the same monotonic deadline, and `remaining()` only falls. So the total wait is at most the budget, plus bookkeeping: `shutdownReturnsWithinItsTimeoutWhenEveryStepIsStuck` pins about 5 ms over. The registry's waits accept only a `ShutdownDeadline`. Members are stopped by request, not by a per-member join with a timeout, so no member's stop adds time of its own.

Consumers are stopped at S2 but waited for at S5. Their stop overlaps the drain, and a consumer stuck in `deliverSignal` cannot delay it. This replaces up to 10 s per container that today sits outside the deadline. The §15.4 grace arithmetic loses its "Maestro's Kafka listener containers" term. The wake listener's 2 s term applies only when S5 hit the deadline.

### When the deadline is hit

- **At S3:** runs still registered are abandoned at S4 and record nothing more. S5, S7 and S8 get zero:
  - loops and consumers are named in a `WARN` and end after their current call; a consumer's in-flight record that then fails on the closed pool is not committed and is redelivered;
  - the renewer is closed without a wait, its pass in progress finishes, and the locks of abandoned runs expire by TTL;
  - queued lifecycle events are counted as dropped.
- **At S5:** as above, without the runs.
- **At S7/S8:** as today.
- **If the shutdown thread is interrupted:** every wait ends at once, S4 still abandons the runs left, and the flag is restored.

The summary line now names members by kind ("2 runs abandoned, loop `retention-sweeper` still in a store call").

## 6. Migration plan

Each task merges on its own, in this order, failing test first.

1. **Registry and runs (Issue 80, plus the unguarded retry).** Add `LifecycleRegistry` and `joinRun`. `startWorkflow`, `resumeWorkflow` and `retryWorkflow` join before the lock and leave after the release. The drain becomes `awaitNoRuns`.
   - Failing tests:
     - `aLaunchPastItsAdmissionCheckWhenShutdownBeginsIsWaitedFor`: hold a launch at today's gap between the `shuttingDown` check and the `put`, and assert `shutdown()` does not return before its lock is released. It fails today.
     - `aRetryDuringShutdownIsRefusedAndWritesNothing`: today the row is reopened and an unjoined run is launched.
     - `everyLaunchOutcomeLeavesTheRegistryEmpty`, parameterized over held elsewhere, turned terminal, adoption CAS lost, factory throws, store fault on re-read, and thread start fails.
   - Must stay green: `ShutdownReturnsBeforeLockReleaseTest` (its no-lock twin case goes with P3), `WorkflowExecutorShutdownDeadlineTest` (`aRunRegisteredButNotYetStartedIsWaitedForByTheDrain`, `aRunStillUnstartedAtTheDeadlineIsReportedAsStillRunning`, `aLaunchThatFailsBeforeItsThreadStartsReleasesTheDrainAtOnce`, `startingAWorkflowDuringTheDrainIsRefused`), `ShutdownReleasesSlotWaitersTest`, `AdoptionLockLeakTest`.
   - Deleted: `unreleasedRuns`, `RunningWorkflow.launched` and the drain's `Thread.State.NEW` check, the R22(1) re-check after `put`, and the entry `shuttingDown` checks of the three launch methods.
2. **Give-up latch (Issue 70).** Add `RunCancellation.abandon()`, step S4, the refusal in `StoreFaultGuard`, the stop-point checks and the failure-arm routing.
   - Failing tests, on the in-memory executor with a second node recovering:
     - `anActivityThatFailsAfterTheDeadlineRecordsNothingAndAPeerRecoversIt`: today it records `ACTIVITY_FAILED` and `FAILED`.
     - `anActivityThatSucceedsAfterTheDeadlineIsRunAgainByThePeer`: the activity is invoked twice and the workflow completes once.
     - `anAbandonedRunStartsNoFurtherActivity`.
     - The same three for a parallel branch and a compensation.
     - A guard unit test that enumerates every `WorkflowStore` method: refused for an abandoned run, not refused under `advisory` or `passThrough`.
3. **Core loops as members, no interrupt (rest of Issue 64).**
   - Failing test: `noLoopIsInterruptedByShutdown`, parameterized over timer poller, recovery poller, janitor, sweeper and one-shot recovery. A fake store call blocks, then asserts `!isInterrupted()` and that no further call follows the stop.
   - Rewrite the five core tests that Issue 64 lists.
   - Must stay green: `BackgroundLoopStopTest`, `BackgroundLoopStartShutdownRaceTest`, `everyLoopHasEndedWhenShutdownReturns`, `aLoopThatDoesNotStopByTheDeadlineIsNamed`, `RecoveryPollerCapacityTest`.
   - Deleted: `installAndStart`, `StoppableLoop.awaitStopped` (the interface shrinks to `Member`), each loop's `Thread.interrupt()`, `RecoveryPoller.interruptOnStop`, and the `INTERRUPTED` supplier.
4. **Postgres members.** The lock cleaner stops by latch, and the cleaner and wake listener join the registry.
   - Failing test: `PostgresLockCleanerSchedulerShutdownTest`, Hikari-backed and modelled on `PostgresWakeListenerShutdownTest`: close during a slow `DELETE`, no pool `WARN`, the same backend reused.
   - Rewrite `PostgresLockCleanerSchedulerTest.closeWhileARunSwallowsTheInterruptAndThrowsEndsTheCleaner`.
   - A context test shows both members leave before `shutdown()` returns.
   - Must stay green: `PostgresWakeListenerCloseOrderTest`, `PostgresSignalWakeAutoConfigurationTest.closingTheContextLogsNoPoolWarnAndClosesTheListenerBeforeThePool`.
5. **Kafka containers as members (Issue 69).** Add `WorkflowMessaging.subscriptions()` and the post-processor's membership.
   - Failing test: a broker-backed context test, `everyMaestroContainerHasStoppedWhileTheDrainIsStillWaiting`. It holds a run in an activity, closes the context, asserts every container is not running before the run is released, and asserts no listener record is handled after the `DataSource` closes.
   - The same test measures S2's duration, which confirms or refutes that `stop(Runnable)` is asynchronous.
   - Updates `docs/operations.md` §15.4.
   - Deleted: the synchronous stop loops in both `destroy()` methods; they become request-only backstops.
6. **Health probe (Issue 66), independent of tasks 1–5.**
   - Failing tests: Hikari-backed `aTimedOutProbeEvictsNoConnection` and `aStuckProbeDoesNotHoldUnknownOnceTheStoreAnswers`.
   - `MaestroHealthIndicatorTest.aTimedOutProbeIsInterrupted` is inverted.
   - Deleted: `Probe.abandon`'s interrupt.

The last merged task also updates the `CLAUDE.md` lines on interrupts (Issue 64, Issue 66), the "Lock Keys" shutdown sentences, and `docs/operations.md` §15.

## 7. What this closes, what it does not, risks

**Closes:**

- Issue 80, by construction: no weakly consistent pass.
- The unguarded `retryWorkflow` path.
- Issue 70.
- Issue 69, within one deadline instead of before the engine's phase (see Question 1).
- The rest of Issue 64.
- Issue 66.
- The foreign-service check is now waited for. Every Maestro thread that touches the pool on shutdown has one owner.

**Does not close:**

- An activity in flight at give-up still runs to its end, and its side effect may repeat on the peer. This is by design.
- An attempt that ignored its timeout's interrupt.
- NOTIFY hand-off threads, which are best-effort, not members, and can log one outage `WARN` against a closed pool *(inferred)*.
- A phase timeout shorter than `maestro.shutdown.timeout` (§3).
- Health and loop truth, which is P2.
- Abandoned runs' locks still wait out their TTL when the release fails.

**Risks, and how the tests would show them:**

1. **A launch exit path that never leaves.** That is a ghost member: every shutdown would then wait its full deadline. `everyLaunchOutcomeLeavesTheRegistryEmpty`, and the existing failed-launch cases in `ShutdownReturnsBeforeLockReleaseTest`, would hang to the deadline and fail on elapsed time.
2. **An `Error` from the guard reaching an engine `catch` that records or swallows it,** such as a `catch (RuntimeException | Error)` cleanup or a branch collector. Task 2's tests across activity, park, saga and parallel shapes would show a `FAILED` row or a missing `runAbandoned(SHUTDOWN)`. The task also audits every `catch (Error|Throwable)` in `engine` and `saga`.
3. **`container.stop(Runnable)` blocking in Spring Kafka 4.x.** If it blocks, S2 would wait outside the deadline. Task 5's S2 timing shows it, and the fallback is to call it from a virtual thread.

## 8. Questions for the maintainer

1. **Where Kafka containers stop.** Recommended: inside the engine's shutdown, asked at S2 and waited for at S5, on the one deadline. Issue 69's alternative is a `SmartLifecycle` in phase `MAX - 100`, which stops them before the drain begins but under Spring's phase timeout and Spring Kafka's 10 s, outside Maestro's deadline. Both stop them before the `DataSource` closes.
2. **Accept discarding a successful activity result that arrives after the deadline.** The activity then runs again on the adopting node. Recommended: yes. It is crash-equivalent, and the alternative is Issue 70.
3. **Public surface.** Approve `WorkflowExecutor.lifecycle()` with a public `LifecycleRegistry.join`, and a default `WorkflowMessaging.subscriptions()` on a public SPI. Recommended: yes, both documented as internal until 1.0 alongside `WorkflowStore`.
4. **Health probe.** Recommended: no interrupt, plus supersede-once, holding at most two connections. Alternatively: no interrupt alone, accepting `UNKNOWN` until the driver's socket timeout.
5. **Postgres loops.** Recommended: P1 joins the lock cleaner and wake listener to the registry (task 4), and P2 later moves them under its supervisor. Alternatively, P1 fixes only the cleaner's interrupt and P2 does the joining.
