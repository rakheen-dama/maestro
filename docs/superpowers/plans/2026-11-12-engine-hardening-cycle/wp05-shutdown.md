# WP5 Shutdown Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A graceful shutdown finishes inside one deadline, `maestro.shutdown.timeout`, which now defaults to 20 s (D11). Every step takes what is left of that deadline instead of its own timeout (3.6). A run queued for a task-queue slot leaves as soon as shutdown begins and hands its instance lock back at once; nothing interrupts it (Issue 29). Spring's shutdown phase is no longer blocked: `GracefulShutdownHandler.stop(callback)` returns at once and calls back when the engine has finished, so the web server drains alongside the engine, not after it. The lock renewer stops without an interrupt and a routine deploy no longer counts renewal failures (Issue 64, the renewer half). Nothing is recorded as `FAILED` because of a shutdown.

**Architecture:** `WorkflowExecutor.shutdown()` creates one `ShutdownDeadline` (package-private, monotonic clock) and runs its steps against it. First, nothing in the "stop taking work" step blocks: the four background pollers are asked to stop, parks are abandoned, retry backoffs are abandoned, and the `TaskQueueGovernor` is shut down. Shutting the governor down lends one permit to each of its pools. A waiter that wakes and sees the governor shut down gives the permit straight back and throws `ExecutorShutdownException`, which wakes the next waiter. Then the drain, the pollers' joins, the lock renewer's close and the lifecycle-event flush each wait at most for what remains. After the drain the governor takes its loans back, so its counts are exact again. A second `shutdown()` call (Spring's inferred destroy method) waits for the first to finish, so the `DataSource` and every bean the engine was built on outlive the drain, whatever Spring's phase timeout is. `GracefulShutdownHandler` runs `executor.shutdown()` on a virtual thread named `maestro-shutdown` and calls Spring's callback from there.

**Tech Stack:** Java 25, `maestro-core` (no Spring), `maestro-spring-boot-starter` (Spring Boot 4.0.5, Spring Framework 7.0.6), `maestro-test`, JUnit 5, Awaitility, the core test log capture (`io.b2mash.maestro.core.logcapture.CapturingSlf4jProvider`).

**Spec:** `docs/superpowers/plans/2026-11-12-engine-hardening-cycle/00-spec.md`: the WP5 row, D11, §2 anti-goals, §6 (the 2 h soak runs on WP5). Evidence: `evidence/audit-A-recovery-shutdown-health.md` § "3.6 + Issue 29", "Surprises" 3 and 5. Issues: `docs/open-issues.md` Issue 29 (`:2848-2889`) and Issue 64 (`:4476-4534`). Hand-offs: WP10's grace-arithmetic line (`.superpowers/sdd/wp10-wake-listener-shutdown/task-1-4-report.md`, "Hand-off notes"); WP4's Issue 64 (`.superpowers/sdd/wp04-loop-liveness-health/progress.md`, fix wave e10). Siblings:
- `wp04-loop-liveness-health.md`: D-10 names this package's seams (the loops' joins, the governor's poison permit). Its `BackgroundLoopGuard` and loop-name constants are reused here.
- `wp10-wake-listener-shutdown.md`: the latch-and-bounded-join pattern this package applies to the lock renewer.
- `wp06-recovery-capacity.md` (planned in parallel; it reserves Issues 69–71 for this package): consumes this package's governor `shutdown()`, its closed state, the poison reaching `take`, `ShutdownDeadline` and the pollers' two-phase stop (D-13).

All line numbers below are for `fix/loop-liveness` @ `14514f54` (everything merged so far, plus WP3 and WP4). Re-check them after cutting the branch.

## Added, changed and removed public surface

**Added:** no public type, SPI method, meter or migration.

**Package-private** (named so that reviewers can check them; not public surface):
- `final class ShutdownDeadline` in `io.b2mash.maestro.core.engine` (D-1).
- `interface StoppableLoop { String loopName(); void requestStop(); boolean awaitStopped(Duration bound); }`, implemented by `TimerPoller`, `RecoveryPoller`, `SignalJanitor`, `RetentionSweeper`. Their `stop()` stays and becomes `requestStop()` followed by `awaitStopped(STOP_JOIN)`, where `STOP_JOIN = Duration.ofSeconds(5)` as today.
- `TaskQueueGovernor`: `void shutdown()`, `boolean isClosed()`, `void reclaimShutdownPermits()` (D-3).
- `WorkflowInstanceLockManager.close(Duration join)`. `close()` stays and delegates with `CLOSE_JOIN = Duration.ofSeconds(2)`.
- `LifecycleEventPublisher.shutdown(Duration flush)`. `shutdown()` stays and delegates with today's 5 s.
- `GracefulShutdownHandler(Runnable engineShutdown)`, a test seam. The public `GracefulShutdownHandler(WorkflowExecutor)` delegates with `executor::shutdown`.

**Changed behaviour**

| Surface | Before | After |
|---|---|---|
| `maestro.shutdown.timeout` | Default `30s`. Bounds only the drain of in-flight runs. Loop joins (4 × 5 s) and the publisher flush (5 s) come on top. | Default `20s` (D11). Bounds the whole engine shutdown: every step takes what is left. Measured worst case at a 1 s timeout: 26,029 ms before; the plan's bound after is 1,500 ms (Task 5). |
| `WorkflowExecutor.DEFAULT_SHUTDOWN_TIMEOUT` / `Builder.shutdownTimeout` default | 30 s | 20 s (MD-2) |
| A run queued for a workflow or activity slot at shutdown | Keeps waiting until a slot frees or the JVM exits, holding its instance lock (measured: still running and still holding its lock when `shutdown()` returned). | Leaves at once with `ExecutorShutdownException`, never interrupted. It stands down recoverable, and its lock is released before `shutdown()` returns. |
| A run woken from a park while its queue is full, at shutdown | Waits for a slot, then continues | Leaves. The signal stays unconsumed and the timer row stays as it was, for recovery. |
| A free activity slot after shutdown begins | Taken (R47: in-flight work drains) | Unchanged (MD-3). Each bounded pool may run one activity over its limit until the drain ends (D-3). |
| `GracefulShutdownHandler.stop(Runnable)` | Calls `stop()` synchronously on the context-closing thread, then the callback. Spring's phase timeout cannot bound it, and the web server's graceful drain starts only after it (measured: 1,012 ms after close began, with a 1 s drain). | Returns at once. `executor.shutdown()` runs on the virtual thread `maestro-shutdown`, which then calls back, also when the shutdown threw. |
| `GracefulShutdownHandler.isPauseable()` | `true` (Spring 7's default). `context.pause()` shut the engine down for good (measured: the next start threw `IllegalStateException: WorkflowExecutor is shutting down`). | `false`, like the web server's lifecycle: a pause leaves the engine running. |
| `GracefulShutdownHandler` Javadoc | Says it "stops after the web server (phase MAX-2048)". It stops in the web server's graceful-shutdown phase, `MAX-1024`, and before the web server stops. | Corrected (Task 6). |
| A second `WorkflowExecutor.shutdown()` call while one is in progress | Logs `Shutdown already in progress` and returns at once (measured at bean destruction) | Waits until the first finishes, for at most what is left of its deadline plus 2 s (D-5) |
| The lock renewer at shutdown | `close()` interrupts it, even inside `renewAll` (JDBC). A pass in flight then fails and counts `instanceLockRenewFailed` for every held lock (measured: 1 per lock). | No interrupt. A latch ends its wait; `close(Duration)` joins for at most `min(2 s, remaining)`. Nothing is counted once it is closed. |
| The four pollers' stop | Sequential, each `interrupt` + `join(5 s)` | `requestStop()` for all four first (interrupt kept: Issue 64, MD-1), then joins against the deadline |
| The lifecycle-event flush | `awaitTermination(5 s)` | `awaitTermination(min(5 s, remaining))`; events dropped at the deadline are counted and logged |
| The drain's clock | `Instant.now()` (the wall clock) | `System.nanoTime()`; never the executor's injected `InstantSource` |

**Changed log lines** (D-7). Replaced: `Shutting down WorkflowExecutor, {} workflow(s) in-flight`, `WorkflowExecutor shutdown complete with {} workflow(s) still running`, `WorkflowExecutor shutdown complete — all workflows finished`, `Shutdown already in progress`, and the pollers' unconditional `… stopped` lines. Kept verbatim, because the loan E2E greps them (`run-e2e.sh:1535`, `:1539`): `Maestro graceful shutdown initiated`, `Maestro graceful shutdown complete`.

**Removed:** the `thread.interrupt()` in `WorkflowInstanceLockManager.close()` (`:365`).

**Doc rules (new, `CLAUDE.md`):** "Never give a shutdown step its own timeout" and "Never interrupt a thread that may be inside a JDBC call" (Task 8).

## Verified at planning time (and how)

**Run** (2026-11-17), in a throwaway worktree at `14514f54`, removed afterwards. Two probe test classes, never committed: one in `maestro-core`, one in `maestro-spring-boot-starter`. They were run with `./gradlew --no-watch-fs :<module>:test --tests …`.

| # | Probe | Output |
|---|---|---|
| R1 | Issue 29, workflow slot. Limits `(1, 10)`, `shutdownTimeout(300 ms)`, an in-memory lock. `wf-running` blocks in its activity; `wf-queued` waits for the slot. | `PROBE P1 shutdownMs=306 queuedStillRunning=true queuedLockHeld=true status=RUNNING`. After the holder finished: `queuedLockHeld=false status=RUNNING`. Red: `Issue 29: the run queued on the workflow slot must have left by the time shutdown returns ==> expected: <false> but was: <true>` |
| R2 | Issue 29, activity slot. Limits `(10, 1)`, otherwise the same. | `PROBE P2 shutdownMs=308 waitingStillRunning=true waitingLockHeld=true freeActivitySlots=OptionalInt[0]`. After release: `status=RUNNING freeActivitySlots=OptionalInt[1]`. |
| R3 | Every step at once. `shutdownTimeout(1 s)`. The four loops each block in their store call and swallow the interrupt; one run is blocked in an activity; the lifecycle publisher is blocked in `publishLifecycleEvent`. | `PROBE P3 shutdownMs=26029 (shutdownTimeout=1000ms) … overrunStillRunning=true`. That is 4 × 5 s + 1 s + 5 s: every step has its own timeout. |
| R4 | Issue 64, renewer. TTL 300 ms. One overrunning run holds a lock. `renewAll` blocks the way a JDBC call does: an interrupt ends it with a failure. | `PROBE P4 renewInterrupted=true renewFailedCount=1`. Red: `Issue 64: a routine shutdown must count no renewal failure ==> expected: <0> but was: <1>` |
| R5 | Same phase as the web server. Full auto-configuration (`ApplicationContextRunner`), `maestro.shutdown.timeout=1s`, a blocking workflow in flight. A recording `SmartLifecycle` in phase `MAX-1024` is registered as a manual singleton, the way Boot registers `webServerGracefulShutdown`. | `context close starts at 452 ms` → `Maestro graceful shutdown initiated` → 1.01 s later `WorkflowExecutor shutdown complete with 1 workflow(s) still running` → `Maestro graceful shutdown complete` → `web stand-in stop(callback) called at 1464 ms`, i.e. **1,012 ms after close began**. Then at bean destruction: `Shutdown already in progress` (the executor's inferred destroy method calls `shutdown()` a second time). |
| R6 | `context.pause()` | `Maestro graceful shutdown initiated` / `… complete`, then `startAsync` threw `java.lang.IllegalStateException: WorkflowExecutor is shutting down — cannot start workflow 'wp5-pause-1'` |

**Read** from source at `14514f54`, and from the Spring Framework 7.0.6, Spring Boot 4.0.5 and Spring Kafka 4.0.4 sources jars in `~/.gradle/caches/modules-2`:

- `DefaultLifecycleProcessor` (spring-context 7.0.6).
  - `LifecycleGroup.stop()` (`:633-664`) calls `doStop` for each member in turn, then waits `latch.await(timeout)` once (`:654`). On timeout it logs `INFO Shutdown phase … ends with N bean(s) still running after timeout of …ms: [...]` (`:657-659`).
  - `doStop` (`:458-517`) calls `SmartLifecycle.stop(Runnable)`. A member whose `stop` is synchronous therefore blocks the closing thread for its full duration, before the latch wait even begins.
  - Plain Spring's phase timeout is 10 s (`:124`). Boot's `spring.lifecycle.timeout-per-shutdown-phase` is 30 s (`LifecycleProperties.java:36`).
- **Member order within a phase.** `getLifecycleBeans` (`:528-545`) asks `getBeanNamesForType`, which lists bean definitions first (`DefaultListableBeanFactory.java:623`) and manual singletons after (`:684`). Boot registers `webServerGracefulShutdown` as a manual singleton (`ServletWebServerApplicationContext.java:193`), so Maestro's handler, a bean definition, is stopped first. R5 shows the consequence.
- **Phases.**
  - `WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE = DEFAULT_PHASE - 1024` (`:40`). `START_STOP_LIFECYCLE_PHASE = … - 1024` again, i.e. MAX-2048 (`:46`).
  - `WebServerGracefulShutdownLifecycle.stop(Runnable)` is asynchronous (`:62-66`) and not pauseable (`isPauseable() false`).
  - `server.shutdown` defaults to `GRACEFUL` (`ServerProperties.java:107`).
  - Spring Kafka listener containers: `AbstractMessageListenerContainer.DEFAULT_PHASE = MAX - 100` (`:85`).
  - `DefaultKafkaProducerFactory.getPhase()` is `Integer.MIN_VALUE` (`:540`). Its `stop()` closes the producer (`:529-532`), with a physical close timeout of 30 s (`ProducerFactory.java:44`).
- **Bean destruction.**
  - `destroySingletons` runs in reverse registration order, dependents first (`DefaultSingletonBeanRegistry.java:693-705`).
  - A `@Bean` with no `destroyMethod` infers a public `close()` and then a public `shutdown()` (`DisposableBeanAdapter.java:418-447`). So `maestroWorkflowExecutor` is destroyed by calling `WorkflowExecutor.shutdown()` again (R5).
  - Maestro's Postgres beans take their `DataSource` through `ObjectProvider` (the store: `PostgresStoreAutoConfiguration.java:107-108`; the lock: `PostgresLockAutoConfiguration.java:77`, `:84`), which registers no dependency link. The `DataSource` is created before the store, the lock and the executor, so it is destroyed after them (traced; WP10's Task 3 pinned the same mechanism for the wake listener).
- **Maestro's Kafka listener containers are not lifecycle beans.**
  - Both `KafkaWorkflowMessaging.subscribeSignals` (`:310-325`) and `MaestroSignalListenerBeanPostProcessor` (`:209-213`) create them with `new ConcurrentMessageListenerContainer` and start them by hand.
  - They are stopped in `DisposableBean.destroy()` (`KafkaWorkflowMessaging.java:332-341`; `MaestroSignalListenerBeanPostProcessor.java:230-240`), during bean destruction, after every lifecycle phase.
  - Each `container.stop()` waits up to `ContainerProperties.DEFAULT_SHUTDOWN_TIMEOUT` = 10 s (`AbstractMessageListenerContainer.java:622-650`; `ContainerProperties.java:167`).
  - `MaestroSignalListenerBeanPostProcessor` is a `BeanPostProcessor` (`KafkaMessagingAutoConfiguration.java:423-427`). It is instantiated before ordinary singletons, so it is destroyed after the `DataSource` (traced). This is Issue 69 (D-11).
- **The JVM side.** On `SIGTERM`, Boot's `SpringApplicationShutdownHook` runs `context.close()` on the thread `SpringApplicationShutdownHook` (`SpringApplicationShutdownHook.java:94`) and waits up to 10 minutes for it (`:52`, `:143-155`).
- **A closed pool's `SQLException`.** Hikari's has a `null` SQLSTATE (traced, not run). `AbstractJdbcWorkflowStore.storeFault` maps `null` to `StoreUnavailableException` (`:2223-2229`), so a run whose writes hit a closed pool is abandoned (`STORE_UNAVAILABLE`), never failed.

## Today's shutdown sequence (`14514f54`)

Everything below runs on the context-closing thread (`SpringApplicationShutdownHook` in production, the test thread in tests) unless a step names another thread.

| # | Step | Where | Bound | When the bound runs out |
|---|---|---|---|---|
| 1 | Lifecycle phases above `MAX-1024`: the host's `@KafkaListener` containers (`MAX-100`) | Spring Kafka | phase timeout, 30 s | Spring logs the INFO and moves on |
| 2 | Phase `MAX-1024`: `GracefulShutdownHandler.stop(callback)` calls `stop()` synchronously (`GracefulShutdownHandler.java:43-55`) → `WorkflowExecutor.shutdown()` (`WorkflowExecutor.java:1786-1851`) | | **unbounded by Spring**: the call is synchronous | — |
| 2a | Timer poller: `interrupt()` + `join(5 s)` (`TimerPoller.java:179-191`) | poller's virtual thread | 5 s | Logs `Timer poller stopped` anyway. The thread finishes its current store call in the background. |
| 2b | Recovery poller, the same (`RecoveryPoller.java:108-120`) | | 5 s | same |
| 2c | Signal janitor, the same (`SignalJanitor.java:141-152`) | | 5 s | same |
| 2d | Retention sweeper, the same, plus `stopRequested` (`RetentionSweeper.java:169-182`) | | 5 s | same |
| 2e | `parkingLot.shutdown()` (`ParkingLot.java:500-508`): every parked future completes with `ExecutorShutdownException` — no interrupt | parked runs' threads unwind | — | — |
| 2f | `requestShutdown()` on every registered run (`:1820`): retry backoffs end | | — | — |
| 2g | Drain: `join` every run thread against `Instant.now() + shutdownTimeout` (`:1823-1835`) | | 30 s, wall clock | Runs are left running, not interrupted. `WARN … with N workflow(s) still running` |
| 2h | `instanceLockManager.close()` (`WorkflowInstanceLockManager.java:359-371`): `interrupt()` on the renewer, no join | renewer's thread | — | Locks of runs still running expire after `maestro.lock.ttl` (30 s) |
| 2i | `lifecycleEventPublisher.shutdown()` (`LifecycleEventPublisher.java:117-131`): `awaitTermination(5 s)`, then `shutdownNow()` | publisher's worker | 5 s | The worker is interrupted; queued events are lost uncounted |
| — | **Runs queued on the governor** (`TaskQueueGovernor.java:338-376`): nothing reaches them (Issue 29) | their virtual threads | — | They wait for a slot (R1, R2) |
| 3 | Still in phase `MAX-1024`, only now: `webServerGracefulShutdown.stop(callback)` (R5), then `latch.await(30 s)` | Tomcat | 30 s | INFO, move on |
| 4 | Phase `MAX-2048`: the web server stops. Lower phases follow; `MIN_VALUE`: the Kafka producer factory closes its producer. | | phase timeout | |
| 5 | `destroySingletons`, in reverse registration order, dependents first: | | | |
| 5a | `executor.shutdown()` again (inferred destroy method): `Shutdown already in progress`, returns | | — | — |
| 5b | `KafkaWorkflowMessaging.destroy()`: each container `stop()` | consumer threads | ≤ 10 s each | returns; the consumer finishes closing in the background (traced) |
| 5c | `PostgresWakeListener.close()` (WP10) | poll thread | ≤ 2 s (typically ≤ 0.5 s) | INFO; the thread releases its connection later |
| 5d | `PostgresLockCleanerScheduler.close()` (`:66-76`): `interrupt()`, no join | cleaner's thread | — | — |
| 5e | The `DataSource` (Hikari) closes | | Hikari's own (not measured) | |
| 5f | `MaestroSignalListenerBeanPostProcessor.destroy()`: its containers `stop()`, **after the `DataSource`** (traced) | consumer threads | ≤ 10 s each | |

**Worst case today, with defaults.**
- Maestro alone (step 2): 4 × 5 s + 30 s + 5 s = **55 s**. R3 measured this shape at a 1 s timeout: 26,029 ms = 4 × 5 + 1 + 5.
- Phase `MAX-1024` (steps 2–3): 55 s + up to 30 s for the web server's drain, which starts only after Maestro returns = **85 s**. Spring's 30 s phase timeout bounds only the second part, because Maestro's stop is synchronous.
- Then bean destruction: each Maestro Kafka container ≤ 10 s, plus ≤ 2 s for the wake listener, plus Hikari.

**Realistic today.** The loops' store calls are interruptible on virtual threads (WP10 Verified 2), so 2a–2d return in milliseconds. One activity that overruns the drain still makes step 2 last 30 s. Kubernetes' default `terminationGracePeriodSeconds` is 30 s from pod deletion, preStop included (Kubernetes' documented behaviour, not tested here). So `SIGKILL` lands at the end of the drain: before the lock manager closes, before the publisher flushes, before the web server has drained, and before Maestro's Kafka consumers leave their group. No durable state is lost (Audit A §3.6(b)). Peers wait out the lock TTL (30 s) plus up to one recovery poll (60 s) for the runs that did not unwind.

## Waits today: what is interruptible, and what may interrupt a thread inside JDBC

| Wait | Interruptible? | Interrupted by | Can the target be inside JDBC? |
|---|---|---|---|
| Timer / recovery / janitor / sweeper `Thread.sleep` | yes | their own `stop()` (`TimerPoller.java:183`, `RecoveryPoller.java:112`, `SignalJanitor.java:145`, `RetentionSweeper.java:174`) | **yes**: leader election, `getDueTimers`, `markTimerFired`, the recovery pass's reads and adoption writes, the janitor's `DELETE`, the purge transaction. On a virtual thread the interrupt closes the socket: Issue 38's mechanism, one pool `WARN` and an evicted connection. The janitor and the sweeper also log a `WARN … sweep failed` (traced). |
| Renewer `Thread.sleep` | yes | `WorkflowInstanceLockManager.close()` (`:365`) | **yes**: `renewAll`. R4: the pass fails and counts a renewal failure per held lock. |
| Lock cleaner `Thread.sleep` | yes | `PostgresLockCleanerScheduler.close()` (`:74`) | **yes**: two `DELETE`s (Issue 64) |
| Wake listener poll | — | nothing since WP10 | — |
| Health probe | yes | `MaestroHealthIndicator.java:404` | yes (Issue 66, out of scope) |
| Lifecycle publisher's send | yes | `shutdownNow()` (`LifecycleEventPublisher.java:121`, `:125`) | no: a Kafka send |
| Activity attempt past its `startToCloseTimeout` | yes | `ActivityInvocationHandler.java:1021`, `:1037` | host code, maybe; activity-timeout semantics, out of scope |
| Governor `Semaphore.acquire()` / `reacquire.lockInterruptibly()` | yes | **nothing** (Issue 29). The `InterruptedException → ExecutorShutdownException` conversion (`TaskQueueGovernor.java:379-383`) is unreachable in production. | — |
| Parks, retry backoffs | latches / futures | `ParkingLot.shutdown`, `RunCancellation` | — |

After this package, the renewer leaves this list (D-8). The four pollers and the cleaner stay on it, and Issue 64 is widened to cover all five (MD-1).

## Decisions

### D-1. One deadline, threaded through every step

```java
/** One shutdown's time budget, measured on the monotonic clock. Confined to the shutting-down thread; readable from any. */
final class ShutdownDeadline {
    static ShutdownDeadline start(Duration budget);                        // System::nanoTime
    static ShutdownDeadline start(Duration budget, LongSupplier nanoTime); // test seam
    Duration budget();
    Duration elapsed();
    Duration remaining();                       // never negative
    Duration remainingAtMost(Duration cap);     // min(cap, remaining())
    boolean passed();
}
```

- `shutdown()` creates it first thing and publishes it in a `volatile @Nullable ShutdownDeadline shutdownDeadline` field, for a second caller (D-5).
- Every bounded step receives `remaining()` or `remainingAtMost(cap)` at the moment it starts. No step owns a timeout of its own any more.
- **Monotonic clock, never the injected `InstantSource`.** `TestWorkflowEnvironment` builds executors on a controllable clock (`instantSource`). A frozen test clock must not stop a deadline from passing. Today's drain uses `Instant.now()` (`:1823`), which a wall-clock jump can stretch or cut.
- An interrupt of the shutting-down thread ends every remaining wait at once (each join catches `InterruptedException`). The non-blocking steps still run, and the flag is restored before `shutdown()` returns, as today (`:1830-1833`).

### D-2. The sequence after this package

| # | Step | Blocks? | Gets | Why here |
|---|---|---|---|---|
| 1 | `shuttingDown` CAS. Create and publish the deadline. `INFO Maestro shutdown started: …` | no | — | |
| 2 | `requestStop()` on the four pollers (timer, recovery, janitor, sweeper). They are taken out of their `AtomicReference` slots as today. | no | — | No new timer fires and no new recovery launches, as today (`:1792-1793`). Their joins move to step 6, so a stuck loop no longer delays the drain. |
| 3 | `parkingLot.shutdown()`; `requestShutdown()` on every run | no | — | unchanged |
| 4 | `taskQueueGovernor.shutdown()` | no | — | Where Audit A put it. Steps 2–4 never wait and are idempotent, so their order does not matter. |
| 5 | Drain: `join` each run thread | yes | `remaining()` | |
| 6 | `awaitStopped(remaining())` for each poller. A `false` logs one `WARN` naming the loop. | yes | `remaining()` | All four were asked at step 2. Joining them here costs nothing extra while they behave, and only the time that is left when they do not. |
| 7 | `taskQueueGovernor.reclaimShutdownPermits()` | no | — | after the drain, when the poison has finished travelling (D-3) |
| 8 | `instanceLockManager.close(remainingAtMost(2 s))` | yes | ≤ 2 s | After the drain: the renewer keeps renewing the locks of runs still draining until the drain is over. The 2 s cap matches the wake listener's (WP10). |
| 9 | `lifecycleEventPublisher.shutdown(remainingAtMost(5 s))`. Events still queued when the flush ends are counted as dropped and logged once. | yes | ≤ 5 s | last: it carries the events of runs that drained in step 5 |
| 10 | The summary line (D-7). Then the `shutdownFinished` latch counts down, in a `finally`. | no | — | |

**When little time is left.** Steps 6, 8 and 9 get zero once the drain has used everything, so they only signal and return. Steps 2, 3, 4, 7 and 10 never block. So `shutdown()` returns within `timeout + ε`, where ε is bookkeeping. Task 5 pins `ε < 500 ms` with every step stuck at once (R3's set-up).

**Not changed:**
- Workflows still executing an activity are never interrupted. They drain until the deadline and are left running after it, as the Javadoc already says (`:1779-1784`).
- The locks of runs still running are not released at shutdown. A peer adopting while this node's run continues would be fenced, but the activity in flight could run twice. TTL expiry stays the safe hand-off (`WorkflowInstanceLockManager.java:354-357`).

### D-3. The governor's shutdown: the poison permit (Issue 29)

**Mechanism** (`TaskQueueGovernor`, package-private methods):
- `shutdown()`:
  1. If the governor can never bound anything (`buckets.isEmpty() && defaults == null`, which is `UNBOUNDED` and its equivalents), it returns without touching any state.
  2. Otherwise it flips `closed` from `false` to `true`. A second call returns.
  3. For every pool that exists (configured buckets and the default buckets created so far, both semaphores of each), it calls `release()` once (the **loan**) and records the semaphore in a `Set<Semaphore> lent`.
  4. WP6 adds its capacity signal after this step (its S2).
- `acquire(slots, …)`, the only waiting path (`:370-376`), used by `take` and by `acquireActivitySlot`'s slow path:
  1. `if (closed) throw shutdownSignal(what, queue)` **before** waiting.
  2. `slots.acquire()`.
  3. `if (closed) { slots.release(); throw shutdownSignal(what, queue); }` **after** acquiring.

  `shutdownSignal` builds `new ExecutorShutdownException("this node is shutting down — no longer waiting for " + what + " on queue '" + queue + "'")`. It sets no interrupt flag. That is the difference from `interrupted(...)` (`:379-383`), which stays for real interrupts.
- `reclaimShutdownPermits()`: for each semaphore in `lent`, `if (slots.tryAcquire()) lent.remove(slots)`. Called once, after the drain.
- `isClosed()` returns `closed`.

**Why each property holds:**
- **No interrupts.** Nothing calls `Thread.interrupt()`. The closed path never sets the flag. A poisoned waiter unwinds through `executeWorkflow`'s `finally`, which releases its instance lock over JDBC (`:2631`). With a restored interrupt flag that release could fail, which is why Audit A ("Surprises" 5) rejected Issue 29's interrupt fix.
- **No waiter left parked.**
  - (a) Waiters queued when `shutdown()` runs: the loan wakes the head of the fair queue. Each woken waiter sees `closed`, releases and throws, which wakes the next. The chain ends when the queue is empty. A real release by a finishing holder during the chain only adds a second permit doing the same.
  - (b) A thread that would wait after `shutdown()` throws before it touches the semaphore.
  - (c) A thread that read `closed == false` before `shutdown()` set it, and joined the queue after the chain ended, finds the loan still free (until step 7), takes it, sees `closed`, gives it back and throws.
  - (d) A sibling branch waiting on its run's `reacquire` lock (R49) gets the lock when the holder throws, then hits (b).
  - The only waiter that can still wait is one that stalls between two adjacent statements for the whole drain and joins the queue after the reclaim. It then waits for the next real release (traced).
- **No lost permits.** A closed acquirer gives back exactly the permit it took. `reclaimShutdownPermits` takes back at most one permit per loan: membership in `lent` is cleared only on a successful `tryAcquire`. The counts return to exactly the limits (Task 1 `noPermitIsLostOrGainedOnceTheLoanIsReclaimed`, and the existing `WorkflowExecutorConcurrencyTest.aRunStillWaitingForAnActivitySlotAtShutdownStandsDownRecoverable`, `:237-238`, unchanged). If every permit of a pool is held at step 7, which happens only when the deadline was hit with every slot held by an overrunning run, the pool keeps its loan and reads one over the limit. The executor never starts anything again after that.
- **Restart-safe for tests.**
  - `UNBOUNDED` is a JVM-wide static (`:90`) shared by every executor without limits (`WorkflowExecutor.java:250`) and by `ActivityStubBeanPostProcessor` (`:175`). It is never mutated.
  - A governor belongs to one executor. `TestWorkflowEnvironment.recover()` shuts the retiring executor down and builds a new one with a new governor (`TestWorkflowEnvironment.java:413-416`).
  - A default bucket created after `shutdown()` (a queue first named after it) got no loan, and every wait on it hits (b).

**What a shut-down governor admits.**
- Every **workflow-slot** acquisition throws (`take` has only the waiting path). That covers a fresh or recovered run whose thread starts after shutdown, which `executeWorkflow` already stood down for any bounded queue (`:2411-2426`), and a park's reacquire (D-4).
- An **activity** acquisition that finds a slot free at once (`tryAcquire(0, SECONDS)`, `:259`) still proceeds (MD-3). That keeps R47's design that in-flight work drains (`ActivityInvocationHandler.java:835-840`). The cost: until step 7 the loan is a free permit, so a bounded pool can run one activity over its limit during the drain.

### D-4. What each workflow looks like in the database after a shutdown that hit its deadline

"Shutdown writes" means what the shutdown path itself appends. A run that keeps executing writes what it normally writes.

| State at `shutdown()` | What happens | Instance row afterwards | Shutdown writes | Instance lock | Recovered |
|---|---|---|---|---|---|
| Executing an activity that ends before the deadline | Its outcome is recorded. The run continues until it parks (abandoned), needs a slot it would wait for (leaves), or completes. | as the run leaves it | nothing | released by the run's `finally` | at once, by any node |
| Executing an activity past the deadline | Not interrupted. `shutdown()` returns and the run keeps going until the JVM exits. If the `DataSource` closes first, its next write throws `StoreUnavailableException` (null SQLSTATE) and it is abandoned `STORE_UNAVAILABLE`. | `RUNNING` (or `COMPENSATING`) | nothing | held, no longer renewed; expires after `maestro.lock.ttl` (30 s) | by a peer's next recovery pass (≤ 60 s); the activity runs again (it must be idempotent) |
| Parked on `awaitSignal` | `ExecutorShutdownException` from the park (unchanged) | `WAITING_SIGNAL` | nothing | released at unwind | at once |
| Parked on `sleep` | the same | `WAITING_TIMER`, timer row `PENDING` | nothing | released | at once |
| In a retry backoff | backoff abandoned (unchanged) | `RUNNING`, no `ACTIVITY_FAILED` | nothing | released | at once |
| **Queued for a workflow slot** | **leaves at once** (was: waited, R1) | `RUNNING` (fresh or adopted) | nothing | **released at once** (was: held until a slot freed, or TTL) | at once |
| **Queued for an activity slot** | **leaves at once** (was: waited, R2) | `RUNNING` | nothing | **released at once** | at once |
| **Woken from a park, reacquiring its slot** | **leaves** (the governor refuses every workflow-slot wait). The signal is unconsumed: consumption follows the reacquire (`SignalManager.java:1025-1040`). A fired timer row stays `FIRED`; `sleep`'s replay reads it (`DefaultWorkflowOperations.java:519-555`). | `WAITING_SIGNAL` / `WAITING_TIMER` | nothing | released | at once |
| Compensating: parked, queued, or in a backoff | `ExecutorShutdownException` propagates through `SagaManager` without recording the step (unchanged; queued is new) | `COMPENSATING`, no `COMPENSATION_STEP_FAILED` | nothing | released | at once |
| Compensating, a compensation activity running past the deadline | as "executing past the deadline" | `COMPENSATING` | nothing | TTL | by a peer |

Every exit above is a point the engine already survives a crash at: shutdown appends nothing, and `ExecutorShutdownException` is handled by the `executeWorkflow` arm at `:2502-2518`.

**Residual, traced, not tested (Issue 70, MD-4).** Take a run still executing past the deadline whose activity then fails because a host bean was destroyed after the deadline (bean destruction proceeds once Maestro's phase has ended). If that is the activity's last attempt and Maestro's store is still writable, `ACTIVITY_FAILED` is recorded, and the workflow may fail and compensate. Before that attempt, a retry backoff is abandoned (`requestShutdown`), so a failure on an earlier attempt records nothing. This package does not close that path (MD-4).

### D-5. A second `shutdown()` waits for the first

`shutdown()` called while a shutdown is in progress logs `INFO Shutdown already in progress — waiting up to {} ms for it to finish`. It then awaits `shutdownFinished` for `remaining() + SECOND_CALLER_GRACE` (2 s), or `shutdownTimeout + 2 s` if the deadline is not yet published. If that wait times out, it logs one `WARN` and returns.

**Why.** Spring calls `shutdown()` a second time at bean destruction (R5). With an asynchronous `stop(callback)`, bean destruction begins once Maestro's phase ends: when Maestro calls back, or when `timeout-per-shutdown-phase` passes, whichever comes first. A second call that returned at once would let Spring destroy the `DataSource` under a drain that had outlived the phase timeout. D11 is the maintainer's answer for the default (20 s < 30 s). D-5 makes the configuration safe as well: whatever the two timeouts, the executor's own destruction waits for the drain, and every bean the executor was built on (the store, the lock, the messaging and, through registration order, the `DataSource`) is destroyed after it (traced; pinned by Task 6 `theEngineDrainsBeforeTheBeansItIsBuiltOnAreDestroyed`).

**What it does not cover:**
- Beans created after the executor (a host's services) are destroyed before it, while the drain runs, if the phase timed out. That is one more reason for the arithmetic in D-10.
- After the deadline, overrunning runs continue regardless (D-4 residual).

**No deadlock.** A second caller on a workflow thread (a workflow calling `shutdown()`) waits at most `remaining + 2 s`. The first caller's join on that thread is bounded by the deadline, so both end.

### D-6. `GracefulShutdownHandler`

```java
public GracefulShutdownHandler(WorkflowExecutor executor) { this(executor::shutdown); }
GracefulShutdownHandler(Runnable engineShutdown)                 // package-private: tests

void start()                  // running = true
void stop()                   // synchronous: running = false; INFO "Maestro graceful shutdown initiated"; engineShutdown.run();
                              //   INFO "Maestro graceful shutdown complete". A throwable propagates (Spring logs "Failed to stop bean").
void stop(Runnable callback)  // running = false; INFO "…initiated"; start a virtual thread "maestro-shutdown" running:
                              //   try { engineShutdown.run(); INFO "…complete" }
                              //   catch (Throwable t) { ERROR "Maestro graceful shutdown failed: {}" with t;
                              //                         rethrow if t is a MaestroControlFlowError }
                              //   finally { callback.run(); }
                              // If the thread cannot be started, the same body runs on the calling thread.
boolean isRunning()           // running
int getPhase()                // Integer.MAX_VALUE - 1024  (unchanged)
boolean isAutoStartup()       // true
boolean isPauseable()         // false (new)
```

- **When the callback runs.** It runs when `executor.shutdown()` returns. That is at the latest at the deadline plus ε (D-2), also when the shutdown threw. Spring waits for it up to `timeout-per-shutdown-phase`. If that passes first, Spring logs its own INFO and moves on, and D-5 still protects the `DataSource`.
- **Why a virtual thread.** It is the engine's thread model. In production the JVM is already running shutdown hooks, and Boot's hook thread waits for `context.close()` (up to 10 min), so the thread cannot be cut short by JVM exit before the callback. Outside a JVM shutdown (a test closing a context), bean destruction waits on D-5's latch.
- **The phase stays `MAX-1024`**, shared with the web server's graceful drain. Alternatives:
  - **Lower** (between `MAX-1024` and `MAX-2048`): Maestro would drain only after the web server's drain ended, in series, so the arithmetic grows by the web drain. In exchange, HTTP requests in flight could still start workflows.
  - **Higher**: Maestro first, the web server after. That is today's order, which R5 shows to be serial.

  Kept, with an asynchronous stop: both drains run at once, and the web server stops accepting new requests at once, where today it accepts them through Maestro's whole drain. A request already in flight that calls `startWorkflow` after shutdown began still gets `IllegalStateException` (`:616-619`), as today.
- **Relative to Kafka.** A host's `@KafkaListener` containers (phase `MAX-100`) stop before Maestro's phase begins. Maestro's own containers are not lifecycle beans and stop at bean destruction, after the drain (D-11).
- **`isPauseable() == false`.** Spring 7's `context.pause()` stops pauseable beans and restarts them later. The executor's shutdown is one-way (`shuttingDown`), so a pause killed the engine for good (R6). The web server's lifecycle answers `false` for the same reason.
- **Restart is not supported**, before or after (WP10 D-3): `context.stop()` then `start()` leaves the engine shut down. The Javadoc says so.

### D-7. What is logged

All lines name no payload. The deadline line names workflow ids, as the per-run `suspended by shutdown` lines already do (`:2695`).

| When | Level | Copy |
|---|---|---|
| Start | INFO | `Maestro shutdown started: {n} run(s) in flight; deadline {budget} ms (maestro.shutdown.timeout)` |
| Within the deadline | INFO | `Maestro shutdown finished in {total} ms of its {budget} ms deadline: {n} run(s) in flight at start, 0 still running (drain {d} ms, background loops {l} ms, instance locks {k} ms, lifecycle events {e} ms)` |
| Deadline hit, runs left | WARN | `Maestro shutdown hit its {budget} ms deadline (maestro.shutdown.timeout): {m} of {n} run(s) still running {ids}; shutdown does not interrupt them and records nothing for them; their instance locks expire after {ttl} (maestro.lock.ttl) and a peer recovers them (drain …, background loops …, instance locks …, lifecycle events …)`. With no lock backend the clause reads `a restarted node recovers them`. `{ids}` lists at most 10 ids, then `and {k} more`. |
| A poller not stopped by the deadline | WARN, once per loop | `Background loop '{name}' did not stop within the shutdown deadline; it ends after its current store call` (`{name}` is a `BackgroundLoopGuard` constant) |
| Events dropped at the flush deadline | WARN | `Lifecycle event publisher stopped with {k} queued event(s) unpublished` (also added to `dropped()`) |
| Second caller | INFO / WARN | D-5 |
| Handler | INFO | `Maestro graceful shutdown initiated`, `Maestro graceful shutdown complete` (verbatim, E2E) |
| Handler, engine shutdown threw | ERROR | `Maestro graceful shutdown failed: {throwable}` (with the stack trace) |

The per-run line `Workflow '{}' suspended by shutdown while {} — left recoverable` is unchanged. A queued run now produces it at once, which is how an operator sees Issue 29's fix.

**What an operator sees when the deadline is hit:** the WARN above, possibly a loop WARN, and, if the phase also timed out, Spring's own `INFO Shutdown phase 2147482623 ends with 1 bean still running after timeout of …ms: [maestroGracefulShutdownHandler]`.

### D-8. The lock renewer (Issue 64, the renewer half)

- `renewLoop` (`:396-417`) waits on `stop.await(renewInterval)` (a `CountDownLatch`) instead of `Thread.sleep(renewInterval)`.
- `close(Duration join)`:
  1. `closed` compare-and-set; a second call returns;
  2. `stop.countDown()`;
  3. **no interrupt**;
  4. join the renewer for at most `join`. If the joining thread is interrupted, restore its flag and go on as if the join had timed out;
  5. the existing `INFO {} instance lock(s) still held at close` line.
- The backend arm of `renewPass` (`:443-451`): when `closed` is set, it emits no `instanceLockRenewFailed` and returns (the guard already logs at `DEBUG` while stopping). That is Issue 64's "should not emit … once the manager is closed".
- `close()` (no argument) delegates with `CLOSE_JOIN` = 2 s. The existing `closeStopsRenewer` (`WorkflowInstanceLockManagerTest`) is unchanged.

### D-9. The four pollers: request, then join (interrupt kept)

- `requestStop()` is today's `stop()` without the join: `running = false` (and `stopRequested` for the sweeper), then `thread.interrupt()`.
- `awaitStopped(Duration bound)`: `join(bound)`. When the thread has ended it logs today's `… stopped` INFO, clears the thread reference and returns `true`. Otherwise it returns `false` without logging; the executor logs D-7's WARN.
- `stop()` is `requestStop(); awaitStopped(STOP_JOIN)`. It keeps `installAndStart`'s race path (`:1736-1748`) and every existing test that calls `stop()` unchanged.
- The interrupt stays (MD-1). Without it, a poller whose iteration is inside a store call is waited for, bounded by the deadline, which is what the deadline is for. But the existing tests that model "a driver that swallows the interrupt" would have to be rewritten (MD-1 lists them).

### D-10. The grace arithmetic (documented in Task 8)

```
terminationGracePeriodSeconds (or docker stop -t, systemd TimeoutStopSec)
    ≥ preStop
    + the host's lifecycle beans above phase MAX-1024 (e.g. @KafkaListener containers, phase MAX-100)
    + max(maestro.shutdown.timeout, the web server's graceful drain)
    + Maestro's Kafka listener containers, stopped at bean destruction (≤ 10 s each; typically under 1 s, traced)
    + 2 s for the Postgres wake listener (typically ≤ 0.5 s; WP10 measured 0–408 ms)
    + a margin for the DataSource and the JVM (5 s suggested)

spring.lifecycle.timeout-per-shutdown-phase ≥ maestro.shutdown.timeout + 2 s
```

- **Defaults, no preStop:** 20 + ~1 + 2 + 5 ≈ 28 s ≤ 30 s. That fits Kubernetes' default grace, narrowly.
- **With a 5 s preStop:** ≈ 33 s. Set `terminationGracePeriodSeconds: 45`.
- **Keeping 30 s of drain:** `maestro.shutdown.timeout: 30s`, `spring.lifecycle.timeout-per-shutdown-phase: 35s`, grace ≥ 45 s + preStop.
- **If the phase timeout is shorter than the drain:** Spring moves to lower phases and starts destroying beans created after the engine while it still drains. The engine and the beans it is built on stay up until it finishes (D-5).
- Every term except Maestro's own is labelled "(traced)" in the docs. Maestro's is pinned by Task 5.

### D-11. Kafka listener containers (Issue 69, MD-5)

Facts (read, above):
- Maestro's containers keep consuming through the whole drain. That is safe: `deliverSignal` does not check `shuttingDown` (`:882-919`), so a signal that arrives is persisted, and a parked run that would have consumed it is recovered with it.
- They stop at bean destruction, where their stop time adds to the arithmetic.
- The `@MaestroSignalListener` containers stop after the `DataSource`. A record in flight in that window fails (`StoreUnavailableException`) and goes to the container's error handler. Its back-off is cut short by the stop, so the record is redelivered after the restart and not dead-lettered (traced from Spring Kafka's `DefaultErrorHandler`; not run).

This package files it (Issue 69) and changes nothing in the messaging module (MD-5).

### D-12. Anti-goals and mixed fleets

- **No run fails or compensates where today it would stand down or be abandoned.** Every new exit is an `ExecutorShutdownException` through the existing arm, and the closed governor throws only that. The renewer change counts and records less, not more. The residual path in D-4 exists today as well. It is not widened here: the drain still never interrupts.
- **Replay:** no history, event, SPI or schema change. The seeded-history fixture is untouched (Review Focus 6).
- **Mixed fleet:** shutdown is local to a node. A peer of either version recovers what this node leaves, sooner than today because queued runs release their locks at once.

### D-13. Seams for WP6, WP8, WP11 and WP12

- **WP6** (`wp06-recovery-capacity.md`, S1–S5):
  - S1: `TaskQueueGovernor.isClosed()` and the `closed` field.
  - S2: `TaskQueueGovernor.shutdown()`; WP6 adds `capacitySignal.signal()` after the loans.
  - S3: the poison reaches `take` through `acquire`, so WP6's queued claim needs no change.
  - S4: `ShutdownDeadline` and D-2's step 2/6: WP6's one-shot recovery thread is a `StoppableLoop` requested at step 2 and joined at step 6.
  - S5: `RecoveryPoller.requestStop()` / `awaitStopped(Duration)` / `stop()` as in D-9. WP6's new wait must end on `requestStop()`. The interrupt does that today.
- **WP8.** A pause between retention batches must check `stopRequested` (as WP4 D-10 says). `requestStop()` sets it.
- **WP11.** Its slot-usage gauge reads `availablePermits()`. After step 7 the counts are exact, so the gauge never shows a negative "used" after a clean shutdown.
- **WP12 scenario 2** (recovery drain across nodes) benefits from released locks. There is no new scenario.

## Maintainer decisions

### MD-1. Issue 64: fix only the renewer here?

**Recommendation: yes.** Fix the renewer here (D-8). Keep the lock cleaner and the four core pollers under Issue 64, widened to name them (Task 8).
- The renewer stops inside the engine's shutdown sequence, which this package rewrites.
- Its interrupt has an operator-visible cost that R4 reproduced: one `maestro.lock.renew.failures{outcome="error"}` per held lock on a deploy that lands mid-pass, which fires any alert on that counter.

**Alternative:** convert all six loops to the latch-and-join pattern here. **Cost:**
- two more tasks;
- at least six existing tests built on "a driver that swallows the interrupt" would need rewriting as named edits, because their fakes block until interrupted: `SignalJanitorTest.stop_whileTheStoreSwallowsTheInterrupt_endsTheLoopPromptly`, `RetentionSweeperTest.stopMidSweepEndsBeforeTheNextBatch`, `RetentionSweeperTest.stopWhileTheStoreSwallowsTheInterruptEndsPromptly`, `BackgroundLoopErrorTest.timerPoller_stopWhileAStoreCallSwallowsTheInterruptAndThrows_endsPromptlyAndUncounted`, `BackgroundLoopErrorTest.timerPoller_stopWhileAStoreCallSwallowsTheInterruptAndReturns_endsPromptly` and `PostgresLockCleanerSchedulerTest.closeWhileARunSwallowsTheInterruptAndThrowsEndsTheCleaner`, plus any recovery-poller equivalent found at implementation time;
- a Hikari-backed test for the cleaner.

The benefit is that the new `CLAUDE.md` rule would hold everywhere at once. The harm left open is one pool `WARN` and one evicted connection when a stop lands inside a store call (milliseconds out of each loop's interval; traced).

### MD-2. The 20 s default in core and the test kit too?

**Recommendation: yes.** `WorkflowExecutor.DEFAULT_SHUTDOWN_TIMEOUT` becomes 20 s, so the builder, the starter and `TestWorkflowEnvironment` share one default. The arithmetic applies to any embedder, and two defaults would need two explanations. **Alternative:** change only `maestro.shutdown.timeout`. **Cost:** non-Spring embedders and the test kit keep 30 s, and the docs must explain why `WorkflowExecutor.builder()` and the starter differ.

### MD-3. A free activity slot after shutdown begins

**Recommendation: keep admitting it** (D-3). That is R47's design: in-flight work drains, and an unbounded queue behaves the same. The cost is that each bounded pool can run one activity over its limit until the drain ends. **Alternative:** a shut-down governor refuses every acquisition. Limits stay exact. **Cost:** every in-flight run on a bounded queue stands down at its next activity instead of draining to completion. An operator sees more `suspended by shutdown` lines, and fewer runs complete during a deploy. Bounded and unbounded queues then behave differently at shutdown.

### MD-4. The post-deadline failure path (D-4 residual)

**Recommendation: file it (Issue 70)**, with the fix shape "at the deadline, latch every run still registered with a new `RunCancellation` cause stronger than `SHUTDOWN`, checked where `STORE_UNAVAILABLE` is checked, so that nothing it does afterwards is appended and it is abandoned as `SHUTDOWN`". **Alternative:** build it here. **Cost:** a new cause threaded through about seven stop points (`RetryExecutor.java:195`, `ActivityInvocationHandler.java:875`, `DefaultWorkflowOperations.java:346`, `:916`, `SagaManager.java:507`, `:756`, `StoreFaultGuard.java:285`) plus the executor's failure path. It also changes behaviour: a successful activity result after the deadline is discarded and the activity re-runs on adoption. It deserves its own review.

### MD-5. Maestro's Kafka containers (D-11)

**Recommendation: file it (Issue 69).** **Alternative:** a `SmartLifecycle` in the messaging module, in phase `MAX-100`, that stops every Maestro container asynchronously before the drain. Partitions would move to peers sooner, and no consumer would run after the `DataSource` closes. **Cost:** a change to `maestro-messaging-kafka` (WP7's module) and a context test with a broker. The total time is unchanged, because phases run in series.

## Global Constraints

- **Spec anti-goals (§2):** D-12. **An existing test is changed only as a named step.** The complete list is under "Existing tests changed". An implementer who meets any other failing test that encodes old behaviour stops and asks.
- **Code rules.**
  - `maestro-core` never imports Spring.
  - JSpecify `@Nullable` on every nullable parameter, field and return.
  - Javadoc on every public or protected member, and on `ShutdownDeadline` and `StoppableLoop`, with thread-safety notes.
  - No Lombok. Jackson 3 only (this package adds no serialization).
- **Control-flow `Error`s.** The closed governor throws `ExecutorShutdownException` only. The handler's `catch (Throwable)` rethrows a `MaestroControlFlowError` after logging (D-6). No new `catch` in workflow, activity or saga code.
- **No interrupt is added anywhere.** The only interrupt removed is the renewer's.
- **Lifecycle events** still go through `LifecycleEventSink`; the publisher's flush is the only change there.
- **No log line or meter tag** carries a payload. Workflow ids appear only in D-7's deadline line, capped at 10.
- **Branch and worktree.** Branch `fix/shutdown`, cut from `origin/main` after WP4 has merged (Lane A order: WP4 → WP5 → WP6), in `/Users/rakheendama/Projects/2026/maestro/.claude/worktrees/wp5-shutdown`. Before the first edit, run `git -C <worktree> status` and `git -C /Users/rakheendama/Projects/2026/maestro status`. Never `cd` into the main checkout. This plan lives on `docs/engine-hardening-cycle`; read it from the main checkout's absolute path.
- **Gradle.** Always `./gradlew --no-watch-fs`, from the worktree root. Never two Gradle runs on one module at once. No `git stash`. Never `pkill`, `killall` or `kill` by name or pattern; never stop a Gradle daemon. A 2 h soak and other agents share the machine.
- **Commits.** Commit at every checkpoint named below. Chain git steps with `&&`. Pass messages as `-m "$(cat <<'EOF' … EOF)"`. Never run git-mutating commands in parallel. Every message ends with the attribution line your own session supplies.
- **Long runs** (`build`, the whole `:maestro-integration-tests:test`, `e2eTest`, the soak) are coordinator gates, launched detached with a log whose header records `pwd`, `git rev-parse HEAD` and a timestamp. Implementers run only the targeted commands in the steps.
- **Red tests** fail on the unfixed code on a positive fact: a boolean, a count, a duration or a status. They never fail on a `ConditionTimeoutException`. Paste the FAIL lines into the commit body. Each fix gets at least one mutation check that a reviewer can repeat, reverted in the same step (`git checkout -- <file>`; `git diff` empty for that file afterwards).
- **No sleeps** where a latch or an awaited condition works. The duration bounds asserted are upper bounds on a call (`shutdown()`, `requestStop()`, `close(Duration)`), never waits.
- **Documentation** states only what a test shows, or it is labelled "(traced)".
- **New issues:** 69 and 70 (64–68 are taken; WP6 reserves 69–71 for this package, and 71 goes unused). Check with `grep -n '^### Issue' docs/open-issues.md | tail -3`.
- **PR gates (spec §6):** Build & Test; nightly E2E on the branch; chaos PR-gate ×3; **the 2 h soak**; the seeded-history ITs (Task 8 Step 9). The maintainer merges.

## Review Focus

1. **A run queued on the governor at shutdown** must leave before `shutdown()` returns, release its lock, write nothing, and never see an interrupt. This holds for workflow slots, activity slots, a park's reacquire, `parallel()` branches and a compensation. → Task 1 (all), Task 2 (all).
2. **No permit is lost, and the counts are exact after a clean shutdown.** One activity over the limit during the drain is the documented exception (MD-3). → Task 1 `noPermitIsLostOrGainedOnceTheLoanIsReclaimed`, `aFreeActivitySlotIsStillAdmittedAfterShutdown`; `WorkflowExecutorConcurrencyTest.aRunStillWaitingForAnActivitySlotAtShutdownStandsDownRecoverable` (unchanged).
3. **One deadline.** `shutdown()` ends within `timeout + 0.5 s` with every step stuck, on the monotonic clock. → Task 5 `shutdownReturnsWithinItsTimeoutWhenEveryStepIsStuck`, `theDeadlineIsMeasuredOnTheMonotonicClock`.
4. **The `DataSource` never closes under the drain**, whatever the phase timeout. → Task 5 `aSecondShutdownCallReturnsOnlyAfterTheFirstHasFinished`; Task 6 `theEngineDrainsBeforeTheBeansItIsBuiltOnAreDestroyed`.
5. **Spring's phase is not blocked**: the web server drains alongside the engine, the callback always runs, and a pause leaves the engine alone. → Task 6 (all).
6. **Nothing durable changes.** Check that the seeded history is untouched: `git diff --stat "$BASE" HEAD -- maestro-integration-tests/src/test/resources/upgrade/ maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/upgrade/` must print nothing. The seeded-history ITs pass (Task 8 Step 9).
7. **The interrupt inventory.** `git grep -nE '[A-Za-z_]+\.interrupt\(\)' -- 'maestro-core/src/main' | grep -v 'currentThread()'` must print exactly four lines, one each in `TimerPoller`, `RecoveryPoller`, `SignalJanitor` and `RetentionSweeper` (MD-1), and nothing in `WorkflowInstanceLockManager` or `TaskQueueGovernor`.
8. **The E2E log strings.** `git grep -c 'Maestro graceful shutdown initiated\|Maestro graceful shutdown complete' -- maestro-spring-boot-starter/src/main` must print a count of at least 2 (one of each).

## Existing tests changed (named; the only allowed edits)

| File | Edit | Task |
|---|---|---|
| `maestro-core/src/test/java/io/b2mash/maestro/core/engine/WorkflowExecutorBuilderTest.java` | `:65` `assertEquals(Duration.ofSeconds(30), b.shutdownTimeout)` → `Duration.ofSeconds(20)` | 7 |
| `maestro-spring-boot-starter/src/test/java/io/b2mash/maestro/spring/config/MaestroPropertiesBindingTest.java` | `:210` `isEqualTo(Duration.ofSeconds(30))` → `Duration.ofSeconds(20)` | 7 |
| `maestro-core/src/test/java/io/b2mash/maestro/core/engine/WorkflowExecutorShutdownTest.java` | Text only: the `@DisplayName` at `:542` ("instead of the 30s default" → "instead of the 20 s default"), the comment at `:544` ("30s-timeout" → "20 s-timeout") and the message at `:561-562` ("the default 30s" → "the default 20 s"). No assertion changes. | 7 |
| `maestro-spring-boot-starter/src/test/java/io/b2mash/maestro/spring/config/MaestroAutoConfigurationConfigSeamsTest.java` | Text only: the `@DisplayName` at `:109` and the message at `:128-129` ("30s default" → "20 s default"). | 7 |
| `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/e2e/chaos/ChaosController.java` | Comment only, `:196`: "shutdown timeout 30s" → "shutdown timeout 20s" | 7 |
| `maestro-core/src/test/java/io/b2mash/maestro/core/engine/TaskQueueGovernorTest.java` | Gains a `@Nested class Shutdown`; no existing line changes | 1 |

**Why the two default assertions existed.** They pin the documented default against the constant (WP0's builder check, Issue 7/9's binding check). Their subject is that the default is applied; the number follows D11.

**Reviewer check, statement-level:**
1. `BASE=$(git merge-base origin/main HEAD); git diff --name-only --diff-filter=M "$BASE" HEAD -- '*/src/test/*'` must print exactly the files above.
2. `git diff --name-only --diff-filter=D "$BASE" HEAD` must print nothing.
3. Read every hunk of those files with `git diff "$BASE" HEAD -- <file>`. Each removed line (`^-[^-]`) must belong to a named edit, and the text-only rows must remove no `assert` statement. A multi-line assertion counts as one statement: check its continuation lines too.

## Before you start

```bash
git -C /Users/rakheendama/Projects/2026/maestro fetch origin && \
git -C /Users/rakheendama/Projects/2026/maestro log origin/main --oneline -1 --grep='admin-retry\|WP3' && \
git -C /Users/rakheendama/Projects/2026/maestro log origin/main --oneline -1 --grep='loop-liveness\|WP4' && \
git -C /Users/rakheendama/Projects/2026/maestro worktree add -b fix/shutdown \
    /Users/rakheendama/Projects/2026/maestro/.claude/worktrees/wp5-shutdown origin/main && \
git -C /Users/rakheendama/Projects/2026/maestro/.claude/worktrees/wp5-shutdown status
```

Both logs must print a merge. If either prints nothing, stop: this plan is written against WP4's `BackgroundLoopGuard`, its loop names and its renewer accessors.

Then re-check the line numbers this plan cites: `GracefulShutdownHandler.java:43-65`, `WorkflowExecutor.java:1786-1851`, `TaskQueueGovernor.java:338-383`, `WorkflowInstanceLockManager.java:359-451`, `LifecycleEventPublisher.java:117-131`. If WP6 or WP11 merged first and changed `TaskQueueGovernor` or `shutdown()`, stop and report: the pre-flight order assumes WP5 lands first.

## File structure

| File | Responsibility |
|---|---|
| `maestro-core/.../engine/TaskQueueGovernor.java` | D-3: `shutdown`, `isClosed`, `reclaimShutdownPermits`; the closed checks in `acquire` |
| `maestro-core/.../engine/ShutdownDeadline.java` (new) | D-1 |
| `maestro-core/.../engine/StoppableLoop.java` (new) | D-9's interface |
| `maestro-core/.../engine/TimerPoller.java`, `RecoveryPoller.java`, `SignalJanitor.java`, `RetentionSweeper.java` | D-9 |
| `maestro-core/.../engine/WorkflowInstanceLockManager.java` | D-8 |
| `maestro-core/.../engine/LifecycleEventPublisher.java` | `shutdown(Duration)`; drop count |
| `maestro-core/.../engine/WorkflowExecutor.java` | D-2, D-5, D-7; the 20 s default (Task 7) |
| `maestro-spring-boot-starter/.../config/GracefulShutdownHandler.java` | D-6 |
| `maestro-spring-boot-starter/.../config/MaestroProperties.java` | the 20 s default |
| `maestro-test/.../TestWorkflowEnvironment.java` | Javadoc only (`:377`) |
| New tests | `TaskQueueGovernorTest` (nested); `ShutdownReleasesSlotWaitersTest`; `WorkflowInstanceLockManagerCloseTest`; `BackgroundLoopStopTest`; `ShutdownDeadlineTest`; `WorkflowExecutorShutdownDeadlineTest` (core); `GracefulShutdownHandlerTest`, `GracefulShutdownLifecycleTest` (starter) |
| Docs | `docs/configuration.md`, `docs/operations.md` (new §15), `docs/maestro-architecture.md:631`, `docs/testing.md:376`, `docs/failure-scenarios.md` S4.2, `docs/open-issues.md`, `docs/release-notes.md`, `CLAUDE.md` |

## Pre-flight: task pairs that share a file or an interface

| Pair | Shared | Order and rule |
|---|---|---|
| 1 → 2 | `TaskQueueGovernor.shutdown/isClosed/reclaimShutdownPermits` | Task 1 first. Task 2 calls them from today's `shutdown()`. |
| 2, 3, 5, 7 | `WorkflowExecutor.java` (Task 2: two calls in `shutdown()`; Task 3: the `close` call; Task 5: rewrites `shutdown()`; Task 7: `DEFAULT_SHUTDOWN_TIMEOUT` and Javadoc) | Sequential, in that order. Task 5 keeps Tasks 2 and 3's calls at D-2's steps 4, 7 and 8. |
| 3 → 5 | `WorkflowInstanceLockManager.close(Duration)` | Task 3 produces it; Task 5 passes `remainingAtMost(2 s)`. |
| 4 → 5 | `StoppableLoop`, `requestStop`, `awaitStopped` | Task 4 produces them; Task 5 consumes them. |
| 5 → 6 | D-5's second-caller wait | Task 6's `theEngineDrainsBeforeTheBeansItIsBuiltOnAreDestroyed` holds only with it. A red there means D-5 is missing: stop and report. |
| 6, 7 | `GracefulShutdownHandler` Javadoc (the default it names) | Task 7 after Task 6 |
| 8 | everything | last |
| WP6 | `TaskQueueGovernor`, `RecoveryPoller.stop`, `shutdown()` | WP6 cuts after this merges (D-13) |

---

### Task 1: The governor's shutdown and its poison permit (Issue 29, D-3)

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/TaskQueueGovernor.java`:
  - fields `private final AtomicBoolean closed = new AtomicBoolean()` and `private final Set<Semaphore> lent = ConcurrentHashMap.newKeySet()`;
  - `void shutdown()`, `boolean isClosed()`, `void reclaimShutdownPermits()`, as D-3 states;
  - `acquire(...)` (`:370-376`) gains the two `closed` checks;
  - a private `shutdownSignal(String what, String taskQueue)`;
  - Javadoc: a new class section `<h2>Shutdown</h2>` (D-3's mechanism and its four properties, one sentence each). The `@throws ExecutorShutdownException` of `acquireWorkflowSlot`, `parked` and `acquireActivitySlot` gains "or if the governor has been shut down: a waiting call leaves at once, and none sets the interrupt flag".
- Modify (named; members only): `TaskQueueGovernorTest.java`, a `@Nested class Shutdown`.

**Interfaces:**
- Produces: `shutdown()`, `isClosed()`, `reclaimShutdownPermits()` (package-private; WP6's S1–S3).

- [ ] **Step 1: Stubs.** Add `void shutdown() {}`, `boolean isClosed() { return false; }` and `void reclaimShutdownPermits() {}` with their Javadoc, so that the tests compile against today's behaviour.
- [ ] **Step 2: Write the tests.** Helper `Waiter waitFor(String name, Runnable acquire)` starts a virtual thread. It records the throwable (if any) and `Thread.currentThread().isInterrupted()` after it, and counts down `left`. `awaitParked(waiter)` is `await().atMost(5, SECONDS).until(() -> waiter.thread().getState() == Thread.State.WAITING)`.

```java
@Test void aWorkflowSlotWaiterLeavesWhenTheGovernorShutsDown() throws Exception {
    var g = new TaskQueueGovernor(Map.of("q", new TaskQueueLimits(1, 1)));
    g.acquireWorkflowSlot("q", "holder");
    var w = waitFor("w", () -> g.acquireWorkflowSlot("q", "w"));
    awaitParked(w);
    g.shutdown();
    assertTrue(w.left().await(2, SECONDS), "the waiter left");
    assertInstanceOf(ExecutorShutdownException.class, w.thrown());
    assertFalse(w.interruptFlag(), "no interrupt: its own lock release must not see one");
}
@Test void anActivitySlotWaiterLeavesWhenTheGovernorShutsDown()      // the same with acquireActivitySlot("q")
@Test void everyQueuedWaiterLeavesAndNoneIsInterrupted() throws Exception {
    // limits (1, 1); "holder" takes the workflow slot and the activity slot;
    // five workflow-slot waiters and five activity-slot waiters, each awaitParked before the next starts
    g.shutdown();
    // every waiter's left.await(2, SECONDS) is true; each thrown() is ExecutorShutdownException; no interruptFlag()
}
@Test void twoBranchesOfOneRunWaitingBehindAnotherRunBothLeave() throws Exception {
    var g = new TaskQueueGovernor(Map.of("q", new TaskQueueLimits(1, 10)));
    var thrown = new ConcurrentLinkedQueue<Throwable>();
    var branches = parkTwoBranchesBehindAnotherRun(g, thrown);      // the existing helper (:204-232)
    g.shutdown();
    assertTrue(branches[0].join(Duration.ofSeconds(2)) && branches[1].join(Duration.ofSeconds(2)), "both branches left");
    assertEquals(2, thrown.size(), thrown.toString());
    thrown.forEach(t -> assertInstanceOf(ExecutorShutdownException.class, t));
}
@Test void aWaitThatStartsAfterShutdownThrowsWithoutWaiting() {
    // limits (1, 1); "holder" holds the workflow slot; g.shutdown();
    // a waiter for "late": left.await(1, SECONDS) is true, thrown() is ExecutorShutdownException
}
@Test void aFreeActivitySlotIsStillAdmittedAfterShutdown() {                  // MD-3 pin
    var g = new TaskQueueGovernor(Map.of("q", new TaskQueueLimits(1, 2)));
    g.shutdown();
    assertFalse(g.acquireActivitySlot("q"), "a free slot is taken without waiting");
    g.releaseActivitySlot("q");
}
@Test void noPermitIsLostOrGainedOnceTheLoanIsReclaimed() throws Exception {
    var g = new TaskQueueGovernor(Map.of("q", new TaskQueueLimits(2, 2)));
    // "h1", "h2" take both workflow slots; two activity acquires take both activity slots;
    // three workflow-slot waiters and three activity-slot waiters park
    g.shutdown();
    // all six leave (left.await(2, SECONDS))
    g.reclaimShutdownPermits();
    g.releaseWorkflowSlot("h1"); g.releaseWorkflowSlot("h2");
    g.releaseActivitySlot("q"); g.releaseActivitySlot("q");
    assertEquals(OptionalInt.of(2), g.availableWorkflowSlots("q"));
    assertEquals(OptionalInt.of(2), g.availableActivitySlots("q"));
}
@Test void shutdownIsIdempotent() {
    // limits (2, 2); g.shutdown(); g.shutdown(); g.reclaimShutdownPermits(); g.reclaimShutdownPermits();
    assertEquals(OptionalInt.of(2), g.availableWorkflowSlots("q"));
    assertEquals(OptionalInt.of(2), g.availableActivitySlots("q"));
}
@Test void shuttingDownAnUnboundedGovernorChangesNothing() {                  // restart-safety
    TaskQueueGovernor.UNBOUNDED.shutdown();
    new TaskQueueGovernor(Map.of()).shutdown();
    assertFalse(TaskQueueGovernor.UNBOUNDED.isClosed(), "the JVM-wide UNBOUNDED must stay usable by the next executor");
}
@Test void aDefaultQueueFirstNamedAfterShutdownRefusesWaits() {
    var g = new TaskQueueGovernor(Map.of(), new TaskQueueLimits(1, 1));
    g.shutdown();
    // a waiter calling acquireWorkflowSlot("late", "w") leaves within 1 s with ExecutorShutdownException
}
```

- [ ] **Step 3: Run red.** `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.TaskQueueGovernorTest'`. Expected FAILs:
  - `aWorkflowSlotWaiterLeavesWhenTheGovernorShutsDown`, `anActivitySlotWaiterLeaves…`, `everyQueuedWaiterLeaves…`, `twoBranchesOfOneRun…`, `aWaitThatStartsAfterShutdown…`, `aDefaultQueueFirstNamedAfterShutdown…`: `the waiter left ==> expected: <true> but was: <false>` (or `both branches left`);
  - `noPermitIsLostOrGained…`: the waiters do not leave (the first assertion).

  `aFreeActivitySlotIsStillAdmittedAfterShutdown`, `shutdownIsIdempotent` and `shuttingDownAnUnboundedGovernorChangesNothing` pass on the stubs. They are pins; their teeth are mutations 4–6.
- [ ] **Step 4: Implement** D-3. Release the waiters' stuck threads in `@AfterEach` (release the holders) so a red run does not leak threads.
- [ ] **Step 5: Run green.** The same command, then `--tests 'io.b2mash.maestro.core.engine.WorkflowExecutorConcurrencyTest'` unchanged. Expected: PASS.
- [ ] **Step 6: Mutation checks** (each reverted with `git checkout -- <file>`).

| # | Mutation | Expected red |
|---|---|---|
| 1 | Drop the `slots.release()` before the post-acquire throw | `everyQueuedWaiterLeaves…` (only the first leaves) |
| 2 | Drop the `closed` check before waiting | `aWaitThatStartsAfterShutdown…`, `aDefaultQueueFirstNamedAfterShutdown…` |
| 3 | Build the closed exception with `interrupted(...)` (it restores the flag) | `interruptFlag()` assertions |
| 4 | `reclaimShutdownPermits` without removing from `lent` (so a second call reclaims again) | `shutdownIsIdempotent` (1 instead of 2) |
| 5 | Skip `reclaimShutdownPermits`'s body | `noPermitIsLostOrGained…` (3 instead of 2) |
| 6 | Remove the "can never bound anything" early return | `shuttingDownAnUnboundedGovernorChangesNothing` |

- [ ] **Step 7: Commit:** `feat(engine): TaskQueueGovernor.shutdown passes a poison permit down every queue; waiters leave without an interrupt (Issue 29)`. The body holds the Step 3 FAIL lines and the mutation table.

---

### Task 2: The executor shuts the governor down; queued runs leave and release their locks (Issue 29)

**Files:**
- Modify: `WorkflowExecutor.shutdown()`. Call `taskQueueGovernor.shutdown()` right after the `requestShutdown` loop (`:1820`), and `taskQueueGovernor.reclaimShutdownPermits()` right after the drain loop (`:1835`). The Javadoc (`:1752-1785`) gains the paragraph "A run waiting for a task-queue slot (a fresh start, an adopted run, a park's reacquire, an activity or a compensation) leaves at once with `ExecutorShutdownException`: it stands down recoverable and releases its instance lock before `shutdown()` returns."
- Create: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/ShutdownReleasesSlotWaitersTest.java`. Fixtures copy `WorkflowExecutorConcurrencyTest`'s `WorkWorkflow`, `AwaitThenWorkWorkflow`, `BlockingActivities` and `proxy()` (`:343-395`, `:396-543`), plus:
  - `MapLock` (as `WorkflowExecutorShutdownTest`'s, `:1018-1047`, with `trySetLeader` returning `true`);
  - a `RecordingEngineObserver observer`;
  - `ParallelWorkWorkflow(activities, n, left)`: each branch calls `work()` inside `try { … } finally { left.incrementAndGet(); }`;
  - `@Saga CompensatingWorkflow(steps)`: `WorkflowContext.current().addCompensation("undo", () -> steps.undo())` (the named overload, `WorkflowContext.java:848`, as `StoreFaultStandDownTest:947` uses it), then `steps.fail()` throws `IllegalStateException`. `steps` is a proxy over the same governor, so `undo` takes an activity slot. `undo()` counts `undone`.
  - The executor: `builder(store, serializer, "node-a").lifecycleEventsEnabled(false).distributedLock(lock).observer(observer).shutdownTimeout(Duration.ofMillis(300)).wakeRecheckInterval(Duration.ofMillis(200))`, with the limits named per test.

**Interfaces:** consumes Task 1.

- [ ] **Step 1: Write the tests.** Each starts the slot holder, waits for `started == 1`, starts the waiter, and confirms it queued with `await().during(Duration.ofMillis(300)).atMost(5, SECONDS).until(() -> started.get() == 1)`.

```java
@Test @DisplayName("a run queued for a workflow slot leaves at shutdown and releases its instance lock at once (Issue 29)")
void aRunQueuedForAWorkflowSlotLeavesAndReleasesItsLock() throws Exception {
    // limits (1, 10): wf-running holds the slot, wf-queued waits
    executor.shutdown();
    assertAll(
        () -> assertFalse(executor.isRunning("wf-queued"), "the queued run left before shutdown() returned"),
        () -> assertFalse(lock.isHeld("maestro:lock:workflow:wf-queued"), "its lock was released, not left to the TTL"),
        () -> assertEquals(WorkflowStatus.RUNNING, status("wf-queued")),
        () -> assertEquals(1, started.get(), "the queued run never started"),
        () -> assertTrue(executor.isRunning("wf-running"), "the holder overruns the drain and is not interrupted"),
        () -> assertEquals(List.of(AbandonReason.SHUTDOWN), abandonReasonsOf("wf-queued")));
}
@Test void aRunQueuedForAnActivitySlotLeavesAndReleasesItsLock()     // limits (10, 1): the same assertions for wf-waiting
@Test void queuedParallelBranchesLeaveAndTheRunIsNotFailed() throws Exception {
    // limits (10, 1); wf-parallel with 3 branches: one holds the activity slot, two queue
    executor.shutdown();
    assertEquals(2, left.get(), "both queued branches left before shutdown() returned");
    latch.countDown();                                              // the holder branch finishes
    awaitNotRunning("wf-parallel");
    assertAll(
        () -> assertEquals(WorkflowStatus.RUNNING, status("wf-parallel")),
        () -> assertEquals(0, count("wf-parallel", EventType.ACTIVITY_FAILED)),
        () -> assertEquals(0, count("wf-parallel", EventType.WORKFLOW_FAILED)),
        () -> assertTrue(observer.failed().isEmpty()));
}
@Test void aCompensationQueuedForAnActivitySlotLeavesTheRunCompensating() throws Exception {
    // limits (10, 1); wf-holding holds the activity slot; wf-saga fails, so its compensation "undo" queues for the slot
    await().atMost(5, SECONDS).until(() -> status("wf-saga") == WorkflowStatus.COMPENSATING);
    executor.shutdown();
    assertAll(
        () -> assertFalse(executor.isRunning("wf-saga"), "the queued compensation left before shutdown() returned"),
        () -> assertEquals(WorkflowStatus.COMPENSATING, status("wf-saga")),
        () -> assertEquals(0, undone.get()),
        () -> assertEquals(0, count("wf-saga", EventType.COMPENSATION_STEP_FAILED)),
        () -> assertEquals(0, count("wf-saga", EventType.WORKFLOW_FAILED)),
        () -> assertFalse(lock.isHeld("maestro:lock:workflow:wf-saga")));
}
@Test void aParkWokenIntoAFullQueueLeavesWithItsSignalUnconsumed() throws Exception {
    // limits (1, 10); wf-a parks on "go"; wf-b takes the slot and blocks; deliverSignal("wf-a", "go", "now");
    // await().during(300 ms) that wf-a stays running and started == 1 (its reacquire waits)
    executor.shutdown();
    assertAll(
        () -> assertFalse(executor.isRunning("wf-a"), "the woken run left before shutdown() returned"),
        () -> assertEquals(WorkflowStatus.WAITING_SIGNAL, status("wf-a")),
        () -> assertEquals(1, store.getUnconsumedSignals("wf-a", "go").size(), "the signal stays for recovery"));
}
```

`count(id, type)`, `status(id)`, `abandonReasonsOf(id)` (filters `observer.abandoned()` by `info().workflowId()`) and `awaitNotRunning(id)` are small helpers in the class. `@AfterEach` counts the latch down and shuts the executor down.

- [ ] **Step 2: Run red.** `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.ShutdownReleasesSlotWaitersTest'`. Expected FAILs (R1, R2 measured the first two):
  - `the queued run left before shutdown() returned ==> expected: <false> but was: <true>`;
  - `its lock was released, not left to the TTL ==> expected: <false> but was: <true>`;
  - `both queued branches left before shutdown() returned ==> expected: <2> but was: <0>`;
  - `the queued compensation left before shutdown() returned ==> expected: <false> but was: <true>`;
  - `the woken run left before shutdown() returned ==> expected: <false> but was: <true>`.
- [ ] **Step 3: Implement** the two calls and the Javadoc.
- [ ] **Step 4: Run green.** The same class, then `--tests 'io.b2mash.maestro.core.engine.WorkflowExecutorShutdownTest' --tests 'io.b2mash.maestro.core.engine.WorkflowExecutorConcurrencyTest' --tests 'io.b2mash.maestro.core.engine.StoreFaultStandDownTest'`, unchanged. Expected: PASS.
- [ ] **Step 5: Mutation checks.**
  - Delete the `taskQueueGovernor.shutdown()` call: every test in the class is red.
  - Delete the `reclaimShutdownPermits()` call: `WorkflowExecutorConcurrencyTest.aRunStillWaitingForAnActivitySlotAtShutdownStandsDownRecoverable` is red at `:237` (`expected: <OptionalInt[1]> but was: <OptionalInt[2]>`).
- [ ] **Step 6: Commit:** `fix(engine): shutdown releases runs queued for a task-queue slot at once — they stand down and hand their locks back (Issue 29)`.

---

### Task 3: The lock renewer stops without an interrupt; nothing is counted after close (Issue 64, D-8)

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowInstanceLockManager.java`:
  - field `private final CountDownLatch stop = new CountDownLatch(1)`;
  - constant `static final Duration CLOSE_JOIN = Duration.ofSeconds(2)`;
  - `close()` delegates to the new `close(Duration join)`;
  - `renewLoop` waits on `stop.await(...)`;
  - the backend arm of `renewPass` returns without emitting once `closed`;
  - Javadoc of `close` and the class's "Thread Safety" note: "never interrupts the renewer: a pass in progress finishes, and `close(join)` waits for it for at most `join`".
- Modify: `WorkflowExecutor.java:1838`: `instanceLockManager.close()` → `instanceLockManager.close(WorkflowInstanceLockManager.CLOSE_JOIN)`. Task 5 replaces the bound.
- Create: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/WorkflowInstanceLockManagerCloseTest.java`. The fixture is `BlockingRenewLock extends` a map lock:
  - `blockNextRenew()` makes the next `renewAll` count down `inRenew` and wait on `release`;
  - if that wait is interrupted, it sets `renewInterrupted` and throws `new LockBackendException("renewAll interrupted", "renewAll", e)`, as a socket closed by the interrupt does;
  - `failAfterRelease` makes the released call throw a `LockBackendException` too;
  - a `RecordingEngineObserver` is passed to the manager.

**Interfaces:**
- Produces: `close(Duration)` and `CLOSE_JOIN` (package-private; Task 5).

- [ ] **Step 1: Stub.** Add `void close(Duration join) { close(); }` so the tests compile against today's behaviour.
- [ ] **Step 2: Write the tests.** The manager is built with `new WorkflowInstanceLockManager(lock, "svc", "maestro:lock:", Duration.ofMillis(300), Duration.ofMillis(100), observer)` and has `tryAcquire("wf-1")`.

```java
@Test void closeDoesNotInterruptARenewalInFlight() throws Exception {
    lock.blockNextRenew();
    assertTrue(lock.inRenew.await(5, SECONDS));
    manager.close(Duration.ofMillis(200));
    lock.release.countDown();
    assertFalse(lock.renewInterrupted.get(), "a renewal in flight is a JDBC call: it must not be interrupted");
}
@Test void aRenewalThatFailsAfterCloseCountsNoRenewalFailure() throws Exception {
    lock.blockNextRenew(); lock.failAfterRelease = true;
    assertTrue(lock.inRenew.await(5, SECONDS));
    manager.close(Duration.ofMillis(200));
    lock.release.countDown();
    await().atMost(5, SECONDS).until(() -> !manager.isRenewerRunning());
    assertEquals(List.of(), observer.lockRenewFailed(), "a routine shutdown must not fire renewal-failure alerts");
}
@Test void closeReturnsWithinItsBoundWhileARenewalIsStuck() throws Exception {
    lock.blockNextRenew();
    assertTrue(lock.inRenew.await(5, SECONDS));
    var started = System.nanoTime();
    manager.close(Duration.ofMillis(200));
    var took = Duration.ofNanos(System.nanoTime() - started);
    lock.release.countDown();
    assertTrue(took.compareTo(Duration.ofSeconds(1)) < 0, "close(200 ms) took " + took.toMillis() + " ms");
}
@Test void closeEndsTheWaitBetweenPassesAtOnce() {
    // a manager with renewInterval 10 s; tryAcquire; await().until(manager::isRenewerRunning)
    var started = System.nanoTime();
    manager.close(Duration.ofSeconds(2));
    assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofMillis(500)) < 0);
    assertFalse(manager.isRenewerRunning());
}
```

  Also add to `WorkflowExecutorShutdownDeadlineTest`, created here and extended in Task 5, the executor-level form of R4:

```java
@Test void aShutdownDuringARenewalPassCountsNoRenewalFailure() throws Exception {
    // instanceLockTtl(300 ms), shutdownTimeout(300 ms), distributedLock(BlockingRenewLock), observer(recorder);
    // one overrunning run; lock.blockNextRenew(); assertTrue(lock.inRenew.await(5, SECONDS))
    executor.shutdown();
    lock.release.countDown();
    assertEquals(List.of(), recorder.lockRenewFailed());
    assertFalse(lock.renewInterrupted.get());
}
```

- [ ] **Step 3: Run red.** `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.WorkflowInstanceLockManagerCloseTest' --tests 'io.b2mash.maestro.core.engine.WorkflowExecutorShutdownDeadlineTest'`. Expected FAILs (R4):
  - `a renewal in flight is a JDBC call: it must not be interrupted ==> expected: <false> but was: <true>`;
  - `a routine shutdown must not fire renewal-failure alerts ==> expected: <[]> but was: <[wf-1]>`;
  - the executor-level test: `expected: <[]> but was: <[wf-overrun]>`.

  `closeReturnsWithinItsBound…` and `closeEndsTheWaitBetweenPasses…` pass today, because `close()` interrupts and does not join. They pin the bound and the latch (mutations 2 and 3).
- [ ] **Step 4: Implement** D-8.
- [ ] **Step 5: Run green.** The same classes, then `--tests 'io.b2mash.maestro.core.engine.WorkflowInstanceLockManager*' --tests 'io.b2mash.maestro.core.engine.BackgroundLoopErrorTest'`, unchanged. Expected: PASS.
- [ ] **Step 6: Mutation checks.**

| # | Mutation | Expected red |
|---|---|---|
| 1 | Put `thread.interrupt()` back in `close(Duration)` | `closeDoesNotInterruptARenewalInFlight`, the executor-level test |
| 2 | `join()` with no bound | `closeReturnsWithinItsBoundWhileARenewalIsStuck` |
| 3 | `Thread.sleep(renewInterval)` back in place of the latch (no interrupt now) | `closeEndsTheWaitBetweenPassesAtOnce` |
| 4 | Remove the `closed` check in the backend arm | `aRenewalThatFailsAfterCloseCountsNoRenewalFailure` |

- [ ] **Step 7: Commit:** `fix(engine): the lock renewer stops without an interrupt and counts no renewal failure once closed (Issue 64, renewer)`.

---

### Task 4: The pollers stop in two halves (D-9)

**Files:**
- Create: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/StoppableLoop.java`, the package-private interface with Javadoc: "A background loop the executor stops in two halves: `requestStop()` never blocks, and `awaitStopped(bound)` waits at most `bound`. Thread-safe."
- Modify: `TimerPoller.java`, `RecoveryPoller.java`, `SignalJanitor.java`, `RetentionSweeper.java`:
  - each `implements StoppableLoop`;
  - `loopName()` returns its `BackgroundLoopGuard` constant;
  - `requestStop()` and `awaitStopped(Duration)` as D-9;
  - `stop()` is `requestStop(); awaitStopped(STOP_JOIN);`, with `static final Duration STOP_JOIN = Duration.ofSeconds(5)`;
  - Javadoc of `stop()`.
- Create: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/BackgroundLoopStopTest.java`.

**Interfaces:**
- Produces: `StoppableLoop`, `requestStop`, `awaitStopped`, `STOP_JOIN` (Task 5; WP6's S5).

- [ ] **Step 1: Stubs.** Add the interface. In each loop add `requestStop() { stop(); }` and `awaitStopped(Duration bound) { var t = <thread field>; if (t == null) return true; t.join(bound); return !t.isAlive(); }` (handling `InterruptedException`), so that the test compiles against today's `stop()`.
- [ ] **Step 2: Write the test.** `enum Loop { TIMER_POLLER, RECOVERY_POLLER, SIGNAL_JANITOR, RETENTION_SWEEPER }`. Each constant builds its loop over a `StuckStore extends VersionedInMemoryStore`. That store blocks the loop's store call (`getDueTimers(int)`, `getRecoverableInstances(String, RecoveryCursor, int)`, `deleteUnconsumedSignalsForTerminalInstancesBefore`, `purgeTerminalInstancesBefore`) on `release`, swallowing interrupts, and counts down `entered`. The loops are built with their package-private constructors, as `SignalJanitorTest`, `RetentionSweeperTest` and `BackgroundLoopErrorTest` do. The timer and recovery pollers get a `WorkflowExecutor` built over the same store. All intervals are 20 ms.

```java
@ParameterizedTest(name = "{0}") @EnumSource(Loop.class)
void requestStopReturnsAtOnceAndAwaitStoppedHonoursItsBound(Loop kind) throws Exception {
    var loop = kind.start(store, executor);
    assertTrue(store.entered.await(5, SECONDS), "the loop is inside its store call");
    var t0 = System.nanoTime();
    loop.requestStop();
    var requestMs = (System.nanoTime() - t0) / 1_000_000;
    var stoppedWhileStuck = loop.awaitStopped(Duration.ofMillis(100));
    store.release.countDown();
    var stoppedOnceReturned = loop.awaitStopped(Duration.ofSeconds(5));
    assertAll(
        () -> assertTrue(requestMs < 200, "requestStop() took " + requestMs + " ms"),
        () -> assertFalse(stoppedWhileStuck, "the loop is still inside its store call"),
        () -> assertTrue(stoppedOnceReturned, "the loop ended once its store call returned"));
}
```

- [ ] **Step 3: Run red.** `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.BackgroundLoopStopTest'`. Expected FAILs, one per loop: `requestStop() took 50xx ms ==> expected: <true> but was: <false>` (today's `stop()` joins for 5 s).
- [ ] **Step 4: Implement** D-9. Change no loop body. `isRunning()` and the guards' `stopping` supplier are unchanged: they read `running`, which `requestStop()` clears.
- [ ] **Step 5: Run green.** The same class, then `--tests 'io.b2mash.maestro.core.engine.BackgroundLoop*' --tests 'io.b2mash.maestro.core.engine.SignalJanitorTest' --tests 'io.b2mash.maestro.core.engine.RetentionSweeperTest' --tests 'io.b2mash.maestro.core.engine.TimerPoller*' --tests 'io.b2mash.maestro.core.engine.RecoveryPoller*'`, unchanged. Expected: PASS.
- [ ] **Step 6: Mutation:** make `requestStop()` join. The test is red for that loop. Revert.
- [ ] **Step 7: Commit:** `refactor(engine): background pollers stop in two halves — request without waiting, then join against a bound (3.6)`.

---

### Task 5: One shutdown deadline (3.6, D-1, D-2, D-5, D-7)

**Files:**
- Create: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/ShutdownDeadline.java` (D-1).
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/LifecycleEventPublisher.java`:
  - `shutdown(Duration flush)`; `shutdown()` delegates with `SHUTDOWN_TIMEOUT`;
  - when `awaitTermination` times out, `shutdownNow().size()` is added to `droppedTotal` and logged once (D-7).
- Modify: `WorkflowExecutor.java`:
  - `shutdown()` rewritten as D-2;
  - a `private volatile @Nullable ShutdownDeadline shutdownDeadline` field;
  - a `private final CountDownLatch shutdownFinished = new CountDownLatch(1)` field;
  - `static final Duration SECOND_CALLER_GRACE = Duration.ofSeconds(2)`;
  - D-7's log lines;
  - `Javadoc` of `shutdown()`: one deadline; the order; what a second caller does; "never interrupts a workflow thread or the lock renewer";
  - `Builder.shutdownTimeout`'s Javadoc (`:418-426`): "the deadline for the whole engine shutdown — the drain, the background loops, the lock renewer and the lifecycle-event flush — not only the drain".
- Create: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/ShutdownDeadlineTest.java`.
- Modify: `WorkflowExecutorShutdownDeadlineTest.java` (Task 3's new file). Fixtures:
  - R3's `StuckStore`: the four loop calls, each counting `entered` down once and blocking on `release` while swallowing interrupts;
  - `StuckMessaging`: `publishLifecycleEvent` the same;
  - `RecordingMessaging`: records `event.eventType()`, sleeping nothing;
  - `WorkWorkflow` blocked on `latch`;
  - `AwaitingWorkflow` (parks on `"go"`).

**Interfaces:**
- Consumes: Task 1, Task 3 (`close(Duration)`), Task 4 (`StoppableLoop`).
- Produces: `ShutdownDeadline` (WP6's S4).

- [ ] **Step 1: `ShutdownDeadlineTest`**, on a fake `nanoTime`:
  - `remaining` counts down and never goes below zero;
  - `remainingAtMost(cap)` is the smaller of the two;
  - `passed()` turns true exactly at the budget;
  - `elapsed()` follows the clock.

  Red: the class does not exist, so this is a compile failure, not a red. Write the class.
- [ ] **Step 2: Write the executor tests.**

```java
@Test @DisplayName("shutdown() returns within maestro.shutdown.timeout even when every loop, a run and the publisher are stuck (3.6)")
void shutdownReturnsWithinItsTimeoutWhenEveryStepIsStuck() throws Exception {
    // executor: store(stuck), messaging(stuckMessaging), lifecycleEventsEnabled(true), shutdownTimeout(1 s)
    // start wf-overrun (blocks); startTimerPoller(20 ms, 10); startRecoveryPoller(Map.of(), 20 ms);
    // startSignalJanitor(20 ms, 1 d); startRetentionSweeper(new RetentionSettings(20 ms, 1 d, 1 d, 1 d, 10, 1))
    assertTrue(entered.await(10, SECONDS), "every loop and the publisher are inside a call");
    var t0 = System.nanoTime();
    executor.shutdown();
    var took = (System.nanoTime() - t0) / 1_000_000;
    assertTrue(took < 1_500, "took " + took + " ms");
}
@Test void aSecondShutdownCallReturnsOnlyAfterTheFirstHasFinished() throws Exception {
    // shutdownTimeout(5 s); wf-park parked (AwaitingWorkflow); wf-overrun blocked on latch
    var overrunRunningWhenSecondReturned = new AtomicBoolean(true);
    var first = Thread.ofVirtual().start(executor::shutdown);
    await().atMost(5, SECONDS).until(() -> !executor.isRunning("wf-park"));   // the first call is draining
    var second = Thread.ofVirtual().start(() -> {
        executor.shutdown();
        overrunRunningWhenSecondReturned.set(executor.isRunning("wf-overrun"));
    });
    second.join(Duration.ofMillis(500));          // today it has already returned; after the fix it is still waiting
    latch.countDown();                            // wf-overrun completes, so the first call's drain ends
    assertTrue(first.join(Duration.ofSeconds(5)) && second.join(Duration.ofSeconds(5)));
    assertFalse(overrunRunningWhenSecondReturned.get(),
            "the second call (Spring's destroy method) returned while the first was still draining");
}
@Test void theLifecyclePublisherStillFlushesWhenTimeIsLeft() throws Exception {
    // messaging(recording), lifecycleEventsEnabled(true); start a workflow that returns at once; awaitStatus COMPLETED
    executor.shutdown();
    assertTrue(recording.types().contains(LifecycleEventType.WORKFLOW_COMPLETED));
}
@Test void theFinishLineReportsTheStepsAndNothingStillRunning() {
    try (var logs = CapturingSlf4jProvider.watch(WorkflowExecutor.class)) {
        executor.shutdown();
        var finish = logs.records().stream().filter(r -> r.message().startsWith("Maestro shutdown finished in")).toList();
        assertEquals(1, finish.size(), logs.records().toString());
        assertTrue(finish.getFirst().message().contains("0 still running"));
        assertTrue(finish.getFirst().message().contains("(drain "));
    }
}
@Test void aShutdownThatHitsItsDeadlineNamesTheRunsStillRunning() throws Exception {
    // shutdownTimeout(300 ms); wf-overrun blocked
    try (var logs = CapturingSlf4jProvider.watch(WorkflowExecutor.class)) {
        executor.shutdown();
        var warn = logs.records().stream().filter(r -> r.message().startsWith("Maestro shutdown hit its 300 ms deadline")).toList();
        assertEquals(1, warn.size(), logs.records().toString());
        assertEquals(Level.WARN, warn.getFirst().level());
        assertTrue(warn.getFirst().message().contains("[wf-overrun]"));
        assertTrue(warn.getFirst().message().contains("records nothing for them"));
    }
}
@Test void aLoopThatDoesNotStopByTheDeadlineIsNamed() throws Exception {
    // shutdownTimeout(300 ms); only the janitor's call is stuck
    try (var logs = CapturingSlf4jProvider.watch(WorkflowExecutor.class)) {
        executor.shutdown();
        assertTrue(logs.records().stream().anyMatch(r -> r.level() == Level.WARN
                && r.message().equals("Background loop 'signal-janitor' did not stop within the shutdown deadline; "
                        + "it ends after its current store call")), logs.records().toString());
    }
}
@Test void theDeadlineIsMeasuredOnTheMonotonicClock() throws Exception {
    // instantSource(InstantSource.fixed(Instant.parse("2026-01-01T00:00:00Z"))), shutdownTimeout(300 ms); wf-overrun blocked
    var returned = new CountDownLatch(1);
    Thread.ofVirtual().start(() -> { executor.shutdown(); returned.countDown(); });
    assertTrue(returned.await(3, SECONDS), "a frozen InstantSource must not stop the deadline from passing");
}
```

  `Level` is `org.slf4j.event.Level`, as `CapturingSlf4jProvider.CapturedLog` uses it. `@AfterEach` counts down `release` and `latch` before shutting down, so a red run does not leak threads.
- [ ] **Step 3: Run red.** `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.WorkflowExecutorShutdownDeadlineTest'`. Expected FAILs:
  - `took 26029 ms` (R3's measurement; your number will be within a few ms of `26 0xx`);
  - `the second call (Spring's destroy method) returned while the first was still draining ==> expected: <false> but was: <true>`;
  - `theFinishLine…`: `expected: <1> but was: <0>` (the line does not exist today);
  - `aShutdownThatHitsItsDeadline…`: `expected: <1> but was: <0>`;
  - `aLoopThatDoesNotStop…`: `expected: <true> but was: <false>`.

  `theLifecyclePublisherStillFlushes…` and `theDeadlineIsMeasuredOnTheMonotonicClock` pass today; they are pins for mutations 2 and 4. Task 3's test stays green.
- [ ] **Step 4: Implement** D-1, D-2, D-5 and D-7. Keep Tasks 2 and 3's calls at steps 4, 7 and 8.
- [ ] **Step 5: Run green.** `--tests 'io.b2mash.maestro.core.engine.WorkflowExecutorShutdownDeadlineTest' --tests 'io.b2mash.maestro.core.engine.ShutdownDeadlineTest' --tests 'io.b2mash.maestro.core.engine.WorkflowExecutorShutdownTest' --tests 'io.b2mash.maestro.core.engine.ShutdownReleasesSlotWaitersTest' --tests 'io.b2mash.maestro.core.engine.WorkflowExecutorConcurrencyTest' --tests 'io.b2mash.maestro.core.engine.BackgroundLoop*' --tests 'io.b2mash.maestro.core.engine.LifecycleEventPublisherTest' --tests 'io.b2mash.maestro.core.engine.WorkflowExecutorBuilderTest'`, then `./gradlew --no-watch-fs :maestro-test:test`. Expected: PASS. If `maestro-test` takes more than about 5 minutes, hand it to the coordinator.
- [ ] **Step 6: Mutation checks.**

| # | Mutation | Expected red |
|---|---|---|
| 1 | Join each poller with `STOP_JOIN` (5 s) in place of `remaining()` | `shutdownReturnsWithinItsTimeout…` (≈ 21 s) |
| 2 | Flush the publisher with a fixed 5 s | `shutdownReturnsWithinItsTimeout…` (≈ 6 s) |
| 3 | A second call returns at once (today's behaviour) | `aSecondShutdownCall…` |
| 4 | Compute the deadline from `instantSource` | `theDeadlineIsMeasuredOnTheMonotonicClock` |
| 5 | Flush with `Duration.ZERO` always | `theLifecyclePublisherStillFlushes…` |

- [ ] **Step 7: Commit:** `fix(engine): one shutdown deadline across every step; a second shutdown() waits for the first (3.6)`. The body holds the Step 3 FAIL lines and the mutation table.

---

### Task 6: `GracefulShutdownHandler` stops asynchronously, is not pauseable, and says where it stops (3.6, D-6)

**Files:**
- Modify: `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/config/GracefulShutdownHandler.java`, as D-6. The class Javadoc is rewritten to say:
  - **Phase:** `Integer.MAX_VALUE - 1024`, Spring Boot's `WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE`. It stops in the same phase as the web server's graceful drain and alongside it, before the web server itself stops (`MAX - 2048`) and before any bean, the `DataSource` included, is destroyed. The host's `@KafkaListener` containers (`MAX - 100`) have stopped before it.
  - **Asynchronous:** `stop(Runnable)` returns at once and calls back when `WorkflowExecutor.shutdown()` has returned: within `maestro.shutdown.timeout`, plus a few milliseconds.
  - **Not pauseable** (D-6).
  - **One-way:** it cannot be restarted.
  - It says nothing of a 30 s default (Task 7 writes the number).
- Create: `maestro-spring-boot-starter/src/test/java/io/b2mash/maestro/spring/config/GracefulShutdownHandlerTest.java` (unit, no context).
- Create: `maestro-spring-boot-starter/src/test/java/io/b2mash/maestro/spring/config/GracefulShutdownLifecycleTest.java` (contexts). It builds on `MaestroAutoConfigurationConfigSeamsTest.TestWorkflowConfiguration`, `BlockingProbe`, `BlockingWorkflow` and `ParkingWorkflow`, which are package-visible. Its runner is the seams test's (`:84-89`) with `OutputCaptureExtension`.

**Interfaces:** consumes Task 5's D-5 and its log lines.

- [ ] **Step 1: Seam.** Add the package-private `GracefulShutdownHandler(Runnable engineShutdown)`; the public constructor delegates. No behaviour change.
- [ ] **Step 2: Unit tests.**

```java
@Test void stopWithACallbackReturnsBeforeTheEngineHasShutDown() throws Exception {
    var release = new CountDownLatch(1); var engineDone = new CountDownLatch(1);
    var handler = new GracefulShutdownHandler(() -> { awaitQuietly(release); engineDone.countDown(); });
    handler.start();
    var callback = new CountDownLatch(1); var stopReturned = new CountDownLatch(1);
    Thread.ofVirtual().start(() -> { handler.stop(callback::countDown); stopReturned.countDown(); });
    assertTrue(stopReturned.await(1, SECONDS), "stop(callback) must return while the engine is still draining");
    assertEquals(1, callback.getCount(), "the callback waits for the engine");
    release.countDown();
    assertTrue(callback.await(5, SECONDS));
    assertEquals(0, engineDone.getCount(), "the callback runs after the engine's shutdown returned");
}
@ParameterizedTest @MethodSource("failures")              // new IllegalStateException("x"), new AssertionError("x")
void theCallbackRunsWhenTheEngineShutdownThrows(Throwable failure) throws Exception {
    var handler = new GracefulShutdownHandler(() -> { throw sneaky(failure); });
    handler.start();
    var callback = new CountDownLatch(1);
    Thread.ofVirtual().start(() -> { try { handler.stop(callback::countDown); } catch (Throwable ignored) { } });
    assertTrue(callback.await(2, SECONDS), "Spring must get its callback, or the phase waits out its timeout");
}
@Test void theHandlerIsNotPauseable() { assertFalse(new GracefulShutdownHandler(() -> { }).isPauseable()); }
@Test void stopWithoutACallbackIsSynchronous()            // pin: stop() returns only after engineShutdown returned
@Test void isRunningIsFalseOnceStopIsRequested()          // pin
@Test void itStopsInTheWebServersGracefulShutdownPhase() {   // pin
    assertEquals(SmartLifecycle.DEFAULT_PHASE - 1024, new GracefulShutdownHandler(() -> { }).getPhase());
}
```

- [ ] **Step 3: Context tests.**

```java
@Test @DisplayName("the web server's graceful drain starts while Maestro drains, not after it (3.6)")
void theWebServersGracefulDrainStartsWhileMaestroDrains() {
    // R5's set-up: maestro.shutdown.timeout=1s; a BlockingWorkflow in flight; a WebStandIn SmartLifecycle
    // (phase DEFAULT_PHASE - 1024, asynchronous stop recording System.nanoTime(), callback at once)
    // registered with context.getBeanFactory().registerSingleton("webServerGracefulShutdown", standIn)
    assertThat(Duration.ofNanos(standIn.stopCalledAt - closeStartedAt)).isLessThan(Duration.ofMillis(500));
}
@Test void pausingTheContextLeavesTheEngineRunning() {
    runner.run(context -> {
        context.pause();
        assertThatCode(() -> context.getBean(MaestroClient.class)
                .newWorkflow(MaestroAutoConfigurationConfigSeamsTest.ParkingWorkflow.class, options("pause-1"))
                .startAsync(null)).doesNotThrowAnyException();
    });
}
@Test @DisplayName("the engine finishes draining before the beans it was built on are destroyed, even when the phase times out")
void theEngineDrainsBeforeTheBeansItIsBuiltOnAreDestroyed(CapturedOutput output) {
    // AutoConfigurations.of(LifecycleAutoConfiguration.class, MaestroAutoConfiguration.class);
    // spring.lifecycle.timeout-per-shutdown-phase=200ms, maestro.shutdown.timeout=1s;
    // a user configuration with @Bean(destroyMethod = "close") RecordingPool recordingPool(), whose close() prints
    // "RECORDING POOL CLOSED", and @Bean WorkflowStore workflowStore(ObjectProvider<RecordingPool> pool)
    // { pool.getObject(); return new InMemoryWorkflowStore(); }, which mirrors MaestroDataSources' ObjectProvider wiring;
    // a BlockingWorkflow in flight when the runner closes the context
    var out = output.getOut();
    var engineDone = out.indexOf("Maestro shutdown hit its 1000 ms deadline");
    var poolClosed = out.indexOf("RECORDING POOL CLOSED");
    assertThat(engineDone).isNotNegative();
    assertThat(poolClosed).as(out).isGreaterThan(engineDone);
}
```

- [ ] **Step 4: Run red.** `./gradlew --no-watch-fs :maestro-spring-boot-starter:test --tests '*GracefulShutdownHandlerTest' --tests '*GracefulShutdownLifecycleTest'`. Expected FAILs:
  - `stopWithACallback…`: `stop(callback) must return while the engine is still draining ==> expected: <true> but was: <false>`;
  - `theCallbackRunsWhenTheEngineShutdownThrows` (×2): `Spring must get its callback … expected: <true> but was: <false>`;
  - `theHandlerIsNotPauseable`: `expected: <false> but was: <true>`;
  - `theWebServersGracefulDrain…`: `Expecting actual: PT1.01…S to be less than: PT0.5S` (R5: 1,012 ms);
  - `pausingTheContext…`: `java.lang.IllegalStateException: WorkflowExecutor is shutting down — cannot start workflow 'pause-1'` (R6).

  `theEngineDrainsBeforeTheBeans…` passes today, because the stop is synchronous. It becomes the guard for D-5 once the stop is asynchronous (mutation 3).
- [ ] **Step 5: Implement** D-6.
- [ ] **Step 6: Run green.** The same, then `./gradlew --no-watch-fs :maestro-spring-boot-starter:test`. Expected: PASS. If the module run takes more than about 5 minutes, hand it to the coordinator.
- [ ] **Step 7: Mutation checks.**
  1. `stop(callback)` back to synchronous: `stopWithACallback…` and `theWebServersGracefulDrain…` are red.
  2. The callback outside `finally`: `theCallbackRunsWhenTheEngineShutdownThrows` is red.
  3. In `WorkflowExecutor`, make the second `shutdown()` call return at once (D-5 off): `theEngineDrainsBeforeTheBeans…` is red, with `poolClosed` before `engineDone`. If it stays green, stop and report: Verified "Bean destruction" is wrong for this wiring.
  4. `isPauseable()` back to `true`: `pausingTheContext…` and `theHandlerIsNotPauseable` are red.
- [ ] **Step 8: Commit:** `fix(starter): GracefulShutdownHandler stops asynchronously alongside the web server's drain, is not pauseable, and documents its phase correctly (3.6)`.

---

### Task 7: `maestro.shutdown.timeout` defaults to 20 s (D11, MD-2)

**Files:**
- `MaestroProperties.java:632-647`: `@DefaultValue("20s")`, `defaults()` → `Duration.ofSeconds(20)`, and the record's Javadoc: "the deadline for the whole engine shutdown (default 20 s; below Spring Boot's 30 s `spring.lifecycle.timeout-per-shutdown-phase` and Kubernetes' 30 s grace — see Operations §15)".
- `WorkflowExecutor.java:131` `DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(20)`, and its Javadoc (`:126-130`). `Builder.shutdownTimeout`'s "Default: 30 seconds" (`:420`) → 20. The `shutdown()` Javadoc's "default 30 seconds" (`:1757`) → 20.
- `GracefulShutdownHandler` Javadoc: name the default, 20 s.
- `TestWorkflowEnvironment.java:377`: "up to the engine's 30-second shutdown timeout" → "20-second".
- The named test edits (Existing tests changed), and the run-e2e log text `run-e2e.sh:1531` ("maestro.shutdown.timeout default 30s + slack" → "default 20s + slack"; text only, the grace stays 40 s).

- [ ] **Step 1: Make the two named assertion edits.** Run `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.WorkflowExecutorBuilderTest'` and `:maestro-spring-boot-starter:test --tests '*MaestroPropertiesBindingTest'`. Expected red: `expected: <PT20S> but was: <PT30S>` and `expected: PT20S but was: PT30S`.
- [ ] **Step 2: Change the defaults and the Javadoc; make the text-only edits.** Run the same two, then `--tests '*MaestroAutoConfigurationConfigSeamsTest'` and `--tests 'io.b2mash.maestro.core.engine.WorkflowExecutorShutdownTest'`. Expected: PASS.
- [ ] **Step 3: Check the metadata.** `./gradlew --no-watch-fs :maestro-spring-boot-starter:compileJava`, then `grep -A3 '"maestro.shutdown.timeout"' maestro-spring-boot-starter/build/classes/java/main/META-INF/spring-configuration-metadata.json`. Expected: `"defaultValue": "20s"`.
- [ ] **Step 4: Commit:** `feat(config): maestro.shutdown.timeout and the engine default drop to 20 s (D11)`.

---

### Task 8: Docs, issues, release note, `CLAUDE.md`, gates

**Files:** `docs/configuration.md`, `docs/operations.md`, `docs/maestro-architecture.md`, `docs/testing.md`, `docs/failure-scenarios.md`, `docs/open-issues.md`, `docs/release-notes.md`, `CLAUDE.md`.

- [ ] **Step 1: `docs/configuration.md`.**
  - The table row (`:723`): default `20s`; the description becomes "The deadline for the whole engine shutdown: the drain of in-flight runs, the background loops, the instance-lock renewer and the lifecycle-event flush all share it. Runs still executing at the deadline are left running, never interrupted. Keep it at least 2 s below `spring.lifecycle.timeout-per-shutdown-phase`. See [Operations §15](operations.md#15-shutdown-one-deadline-and-the-grace-period)."
  - The paragraph at `:554-562` ("…a run still queued for a slot when shutdown begins is not interrupted…"): "a run still queued for a slot when shutdown begins leaves at once — it stands down, recoverable, and hands its instance lock back before shutdown returns (Issue 29; `ShutdownReleasesSlotWaitersTest`)".
  - The sentence after the table ("The first two were previously hardcoded 30-second constants…"): "with the same 30s defaults" becomes "`maestro.shutdown.timeout` now defaults to 20 s (see the release notes)".
- [ ] **Step 2: `docs/operations.md`**, a new `## 15. Shutdown: one deadline and the grace period`, before "See also":
  1. The sequence: D-2's table in operator words.
  2. What each workflow state looks like afterwards: D-4's table, with the residual and its issue number.
  3. What you see in the log: D-7's lines, and Spring's phase-timeout INFO.
  4. The arithmetic: D-10, verbatim, with the three worked examples and a Kubernetes snippet:
     ```yaml
     spec:
       terminationGracePeriodSeconds: 45
       containers:
         - lifecycle:
             preStop:
               exec: { command: ["sleep", "5"] }
     ```
     with `spring.lifecycle.timeout-per-shutdown-phase: 25s` and `maestro.shutdown.timeout: 20s`. Every non-Maestro term is "(traced)". Maestro's bound cites `shutdownReturnsWithinItsTimeoutWhenEveryStepIsStuck`.
  5. Kafka: D-11 in three sentences, with Issue 69.
  6. "Upgrading from 30 s": the release note's last bullet.

  In §2 ("Rolling restart"), append to "Why deploy is faster than owner-kill": "Since WP5 this includes runs that were queued for a task-queue slot; before, they kept their locks until a slot freed or the lock TTL ran out."
- [ ] **Step 3: Other docs.**
  - `docs/maestro-architecture.md:631`: the sequence line becomes "SIGTERM → the web server's graceful drain and Maestro's shutdown run side by side (Spring phase `MAX-1024`) → Maestro: stop taking work (loops, parks, retry backoffs, task-queue waiters) → drain in-flight activities until `maestro.shutdown.timeout` (20 s) → stop renewing locks → flush lifecycle events → bean destruction (Maestro's Kafka consumers, the wake listener, the `DataSource`)".
  - `docs/testing.md:376`: 30 → 20 seconds.
  - `docs/failure-scenarios.md` S4.2: replace "Check that the slot waiters not interrupted on shutdown (Issue 29) do not hold the JVM past the orchestrator's grace period" with "Slot waiters leave at shutdown since WP5 (Issue 29)". Keep the rest.
- [ ] **Step 4: `docs/open-issues.md`.**
  - **Issue 29:** a `> **Resolved** (WP5, <PR>).` callout naming D-3's mechanism and the tests (`TaskQueueGovernorTest$Shutdown`, `ShutdownReleasesSlotWaitersTest`). It notes that the issue's interrupt fix was rejected and why (Audit A "Surprises" 5). Set its index row (`:327`) to **Resolved**.
  - **Issue 64:** a `> **Partly resolved** (WP5): the renewer` callout naming D-8 and `WorkflowInstanceLockManagerCloseTest`. Widen "What happens" with the four core pollers (the interrupts at `TimerPoller.java:183`, `RecoveryPoller.java:112`, `SignalJanitor.java:145`, `RetentionSweeper.java:174`; traced). Add MD-1's list of existing tests a fix must rewrite. Update the index row.
  - **New Issue 69** — *Maestro's Kafka listener containers are not lifecycle beans: they consume through the drain, and `@MaestroSignalListener`'s stop after the `DataSource`* (Low; traced). House sections: what happens (D-11), where (file:line from Verified), harm, fix shape (MD-5's alternative), why not WP5 (MD-5).
  - **New Issue 70** — *A run still executing at the shutdown deadline can record a failure caused by bean teardown* (Low–Medium; traced). House sections from D-4's residual and MD-4.
  - Index rows for both.
- [ ] **Step 5: `docs/release-notes.md`.** A new first subsection under `## Unreleased — Postgres + Kafka only`: `### Changed — one shutdown deadline; slot waiters released at shutdown; Maestro stops alongside the web server; maestro.shutdown.timeout defaults to 20 s (3.6, Issue 29, Issue 64, D11)`. Bullets:
  - **What was wrong** (measured):
    - "With `maestro.shutdown.timeout=1s`, four background loops whose store call ignored the interrupt, plus a stuck lifecycle publisher, made `shutdown()` take 26 s: every step had its own timeout (4 × 5 s + the drain + 5 s). At the defaults that is up to 55 s.
    - Spring could not bound it: Maestro stopped synchronously in the web server's graceful-shutdown phase and ahead of it, so the web server began draining only after Maestro had finished.
    - A run queued for a task-queue slot kept waiting, and kept its instance lock, until a slot freed.
    - A deploy that landed during a lock-renewal pass counted one `maestro.lock.renew.failures{outcome="error"}` per held lock."
  - **What changes:** D-2 in two sentences; D-3 in two; D-6 in two (including `isPauseable`); D-8 in one; D-5 in one.
  - **Changed default — breaking for a drain that needs more than 20 s:** "`maestro.shutdown.timeout` (and `WorkflowExecutor.builder(...)`'s default) is now **20 s**, was 30 s, and it now bounds the whole engine shutdown, not only the drain. With a 30 s drain under an asynchronous stop, the drain alone would equal Spring Boot's 30 s `spring.lifecycle.timeout-per-shutdown-phase` and Kubernetes' 30 s `terminationGracePeriodSeconds`, leaving no time for the rest of shutdown. To keep 30 s, set `maestro.shutdown.timeout: 30s`, `spring.lifecycle.timeout-per-shutdown-phase: 35s` and `terminationGracePeriodSeconds: 45` (more with a preStop hook) — see Operations §15."
  - **Changed log lines:** D-7's replaced lines, for anyone alerting on them. The two `Maestro graceful shutdown …` lines are unchanged.
  - **Still open:** Issues 64 (the pollers and the lock cleaner), 69, 70.
- [ ] **Step 6: `CLAUDE.md`.**
  - Configuration Namespace: "`maestro.shutdown.timeout` (default 30s)" → "`maestro.shutdown.timeout` (default 20s; one deadline for the whole engine shutdown)".
  - "What NOT To Do" gains two lines:
    - "**Never give a shutdown step its own timeout** — every step of `WorkflowExecutor.shutdown()` waits at most what is left of its `ShutdownDeadline` (3.6)."
    - "**Never interrupt a thread that may be inside a JDBC call** — stop it with a latch and a bounded join, as `PostgresWakeListener` and `WorkflowInstanceLockManager` do; the four core pollers and the lock cleaner still interrupt (Issue 64), and so does the health probe (Issue 66)."
- [ ] **Step 7: Checks.**
  - Run Review Focus 6, 7 and 8 and the existing-tests check, and paste the output into the commit body.
  - `./gradlew --no-watch-fs :maestro-core:javadoc :maestro-spring-boot-starter:javadoc :maestro-test:javadoc`. Expected: no warning naming a changed member. The three `SagaManager.java:150` warnings are pre-existing (WP4 report).
- [ ] **Step 8: Commit:** `docs: WP5 — one shutdown deadline and the grace arithmetic; Issue 29 resolved, Issue 64 narrowed, Issues 69 and 70 filed`.
- [ ] **Step 9: Coordinator gates, before the PR merges.** Each is launched detached, with a log header recording `pwd`, `git rev-parse HEAD` and a UTC timestamp. Results go in the review section with their log identity.

| Gate | Command | Pass |
|---|---|---|
| Module tests | `./gradlew --no-watch-fs :maestro-core:test :maestro-test:test :maestro-spring-boot-starter:test :maestro-store-postgres:test` | 0 failures, 0 errors |
| Uncached build | `./gradlew --no-watch-fs --no-build-cache build --rerun-tasks` | `BUILD SUCCESSFUL`, exit 0 |
| Seeded history | `./gradlew --no-watch-fs :maestro-integration-tests:test --tests '*SeededHistoryUpgradeIT' --tests '*SeededHistoryReplayFidelityIT' --tests '*SeededHistoryFixtureIT'`, then `./gradlew --no-watch-fs :maestro-integration-tests:seededHistoryNonUtcTest` | PASS; Review Focus 6 prints nothing |
| Shutdown ITs | `./gradlew --no-watch-fs :maestro-integration-tests:test --tests '*ShutdownContractIT'` | PASS |
| Nightly E2E | `gh workflow run e2e-nightly.yml --ref fix/shutdown` (the dispatch also starts the soak job; cancel that one if the local soak below is the evidence) | `e2e` green. Scenario 8 finds `Maestro graceful shutdown initiated` / `complete` and the three `suspended by shutdown while WAITING_SIGNAL` lines (`run-e2e.sh:1535-1539`). |
| Chaos PR-gate ×3 | `for i in 1 2 3; do ./gradlew --no-watch-fs :maestro-integration-tests:e2eTest --rerun-tasks \|\| break; done` | three consecutive green runs, 0 invariant violations |
| **2 h soak** | `caffeinate -i -m -s ./gradlew --no-watch-fs :maestro-integration-tests:e2eTest --rerun-tasks -Dmaestro.chaos.soak=true -Dmaestro.chaos.durationMinutes=120` | Exit 0. 0 invariant violations. Side-effect census: 0 duplicates, 0 unexplained, 0 missing compensations. Wall time ≤ 130 min with no gap of more than 5 min between chaos actions: otherwise the run is invalid, as WP3's run 2 was. Also record, without pass/fail, the counts in `maestro-integration-tests/build/chaos-evidence/` of `Maestro shutdown finished in`, `Maestro shutdown hit its` and `suspended by shutdown`. |

---

## Self-review (run 2026-11-17)

- **Spec coverage.**
  - Issue 29, close the governor and pass a poison permit with no interrupts: D-3, Tasks 1–2.
  - 3.6, one shared deadline: D-1, D-2, Task 5.
  - The asynchronous `stop(callback)`: D-6, Task 6.
  - The handler's Javadoc: Task 6.
  - The grace arithmetic: D-10, Task 8.
  - The 20 s default per D11: Task 7.
  - The first `GracefulShutdownHandler` tests: Task 6.
  - The soak: Task 8 Step 9.
- **The brief's six facts:**
  1. The sequence, threads and timeouts: "Today's shutdown sequence".
  2. What each step does when its time runs out: the same table.
  3. Today's worst case against Boot's phase: "Worst case today" (55 s Maestro, 85 s in the phase; R3, R5).
  4. Issue 29's reproduction: R1, R2, Task 2's reds.
  5. Interruptible waits and the JDBC anti-pattern: the waits table.
  6. Every workflow state after a deadline-hit shutdown: D-4.
- **The brief's decisions:**
  - the deadline object and its threading: D-1;
  - the step order and what each gets: D-2;
  - `stop(callback)` and when the callback runs: D-6;
  - phases relative to Kafka and the web server: D-6, D-11;
  - Issue 64: MD-1;
  - the poison permit's four properties: D-3;
  - logs: D-7;
  - the 20 s default with release-note text: Task 7, Task 8 Step 5;
  - the tests that change: the named list.
- **Type consistency.** These names are the same in every task and in D-13 (WP6's S1–S5):
  - `ShutdownDeadline.start/remaining/remainingAtMost/passed/elapsed/budget`;
  - `StoppableLoop.loopName/requestStop/awaitStopped`, `STOP_JOIN`;
  - `TaskQueueGovernor.shutdown/isClosed/reclaimShutdownPermits`;
  - `WorkflowInstanceLockManager.close(Duration)`, `CLOSE_JOIN`;
  - `LifecycleEventPublisher.shutdown(Duration)`;
  - `SECOND_CALLER_GRACE`;
  - `GracefulShutdownHandler(Runnable)`.
- **Proportion.** The code blocks are test assertions, signatures and log copy. The implementation bodies are fixed by D-1–D-9 and the tests.
- **Known limits of this plan.**
  - Every Spring and Kafka timing outside Maestro is read, not run.
  - The web-server ordering is shown with a stand-in that is registered the way Boot registers the real one; Tomcat itself was not run.
  - The `@MaestroSignalListener`-after-`DataSource` order (Issue 69) is traced only.

## Review section

(The coordinator fills this in: each task's red and mutation lines; the existing-tests check output; the issue numbers given to 69 and 70; gate results with their log identity; the soak's counts; the hand-offs to WP6 (S1–S5 as built) and WP11 (exact slot counts after shutdown).)
