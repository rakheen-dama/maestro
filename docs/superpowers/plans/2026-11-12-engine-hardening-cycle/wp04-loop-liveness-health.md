# WP4 Loop Liveness and Health Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** No background loop dies of a `Throwable` it can survive (3.5b). Every engine loop goes through one guard. The guard rethrows a `MaestroControlFlowError` and contains everything else: it logs once loudly, then quietly with a reminder every 5 minutes, and the loop keeps its own cadence. A loop that does die says so through SLF4J and in `/actuator/health`. A health probe that times out reports `UNKNOWN`, not `DOWN`, and the docs say the indicator is a readiness signal at most (3.5a). Two tests cover the defensive branches named in spec §5.

**Architecture:** A package-private `BackgroundLoopGuard` in `io.b2mash.maestro.core.engine` (no Spring) wraps each iteration of the five engine loops: the timer poller, the recovery poller, the signal janitor, the retention sweeper and the instance-lock renewer. It does not own the loop, the wait or `stop()`. It owns one decision, "this iteration threw: rethrow or contain", plus the log cadence, an `EngineObserver` callback for contained `Error`s, and an uncaught-exception handler that routes a dead loop's last throwable to SLF4J. The two store-postgres loops (lock cleaner, wake listener) cannot see a package-private core class. They apply the same rule inline. `MaestroHealthIndicator` reports a probe timeout as `UNKNOWN`, and it gains the two engine loops it does not report today.

**Tech Stack:** Java 25, `maestro-core` (no Spring), `maestro-store-postgres` (Testcontainers `postgres:16-alpine`), `maestro-spring-boot-starter` (Spring Boot 4.0.5 `spring-boot-health`, Micrometer), JUnit 5, Awaitility.

**Spec:** `docs/superpowers/plans/2026-11-12-engine-hardening-cycle/00-spec.md`: the WP4 row, §2 anti-goals, §3 ("3.5b is wider"), §5 ("Untested defensive branches"), §6, C4. Evidence: `evidence/audit-A-recovery-shutdown-health.md` §§ 3.5a, 3.5b, "Surprises" 1; `evidence/audit-C-host-integration-followups.md` § 4.2-5. Siblings:
- `wp02-wake-timers-activity-lock.md`: house style; its `ClockSkewCheck` now runs inside `TimerPoller.pollLoop`.
- `wp10-wake-listener-shutdown.md`: owns `PostgresWakeListener` first; Task 5 here builds on its loop shape.

All line numbers below are for `main` @ `525b287`. Re-check them after cutting the branch.

## Added, changed and removed public surface

**Added**

| Surface | Exact shape | Task |
|---|---|---|
| Observer callback | `default void EngineObserver.backgroundLoopError(String loop, String errorType) {}`. Fires on the loop's own thread when a background loop contained an `Error` that is not a `MaestroControlFlowError`. `loop` is one of five fixed names (D-3). `errorType` is the `Error`'s class name. `CompositeEngineObserver` fans it out. | 1 |
| Meter | `maestro.loop.errors`, counter, tag `loop` (Prometheus `maestro_loop_errors_total`). Source: `MicrometerEngineObserver.backgroundLoopError`. `errorType` is not a tag. | 8 |
| Executor accessors | `public boolean WorkflowExecutor.isInstanceLockRenewerRunning()` and `public boolean WorkflowExecutor.hasInstanceLockRenewerStarted()`. These are the same pair the timer and recovery pollers already have. | 4 |
| Health details | `signalJanitorRunning`: `true`, `false` or `"starting"`. `instanceLockRenewerRunning`: `true`, `false` or `"idle"`. A `false` makes the status `DOWN`, as a dead poller does today. | 7 |
| Log lines | `BackgroundLoopGuard`'s fixed copy (Task 1 Step 4) replaces `"Timer polling error — will retry next cycle"`, `"Recovery polling error — will retry next cycle"`, `"Leader election failed for service '{}' — skipping this poll cycle"` and `"Failed to renew {} instance lock(s) — will retry: {}"`. None of them carries a workflow id. | 1, 2, 4 |

**Package-private** (named so that reviewers can check them; not public surface): `BackgroundLoopGuard`; a constructor overload taking an `EngineObserver` on `TimerPoller`, `RecoveryPoller` and `SignalJanitor` (the old constructors delegate with `EngineObserver.NOOP`, so no existing test changes); `int consecutiveFailures()` on `TimerPoller`, `RecoveryPoller`, `SignalJanitor`, `RetentionSweeper`; `boolean isRenewerRunning()`, `boolean hasRenewerStarted()`, `int renewFailureStreak()` on `WorkflowInstanceLockManager`.

**Changed behaviour** (no new property, SPI method or migration)

- **3.5b.** An `Error` thrown inside an iteration of any of the seven loops no longer ends that loop, unless it is a `MaestroControlFlowError`. `InterruptedException` still ends a loop at once.
- **Log cadence.** Timer-poller and recovery-poller failures (a database outage, for example) and instance-lock renewal failures log one `ERROR` with a stack trace when they start. Further failures log at `DEBUG`, plus one `ERROR` reminder every 5 minutes, and one `INFO` when the loop recovers. Today a dead database gives 720 timer `WARN`s with stack traces per hour and 360 renewer `WARN`s per hour (D-6).
- **3.5a.** A store probe that does not finish within 2 s makes the `maestro` indicator `UNKNOWN`, not `DOWN`. The `store` detail stays `"timed out"`. A probe that throws is still `DOWN`. A dead loop is still `DOWN`, even when the probe timed out.

**Removed:** nothing.

**Doc rules (new):** "Maestro's health indicator is a readiness signal at most, never liveness" (Task 6, `docs/operations.md` §14).

## Decisions

### D-1. Which loops exist (the spec's "five" is the engine's five; there are seven)

A "loop" here is a thread Maestro starts that runs until stopped and repeats work on a cadence. Found by `grep -rn "Thread.ofVirtual\|new Thread(\|Executors.new" --include='*.java' maestro-*/src/main`:

| # | Loop | Thread | Started by | Cadence (default) | Module |
|---|---|---|---|---|---|
| 1 | `TimerPoller.pollLoop` | `maestro-timer-poller-<svc>` (`TimerPoller.java:128`) | `WorkflowExecutor.startTimerPoller` (`:1424`) | `maestro.timer.poll-interval` 5 s | core |
| 2 | `RecoveryPoller.pollLoop` | `maestro-recovery-poller-<svc>` (`RecoveryPoller.java:66`) | `startRecoveryPoller` (`:1453`) | `maestro.recovery.poll-interval` 60 s | core |
| 3 | `SignalJanitor.sweepLoop` | `maestro-signal-janitor` (`SignalJanitor.java:98`) | `startSignalJanitor` (`:1500`) | `maestro.signal.janitor-interval` 1 h | core |
| 4 | `RetentionSweeper.sweepLoop` | `maestro-retention-sweeper` (`RetentionSweeper.java:140`) | `startRetentionSweeper` (`:1598`) | `maestro.retention.interval` 1 h | core |
| 5 | `WorkflowInstanceLockManager.renewLoop` | `maestro-instance-lock-renewer-<svc>` (`WorkflowInstanceLockManager.java:169-171`) | lazily, by the first `tryAcquire` (`:208`, `:318-339`) | TTL/3, 10 s | core |
| 6 | `PostgresLockCleanerScheduler.loop` | `maestro-pg-lock-cleaner` (`PostgresLockCleanerScheduler.java:52`) | its `@Bean` factory (`PostgresLockAutoConfiguration.java:100-112`) | `maestro.lock.cleanup-interval` 10 min | store-postgres |
| 7 | `PostgresWakeListener.pollLoop` | `maestro-pg-wake-listener` (`PostgresWakeListener.java:103`) | its `@Bean` factory (`PostgresSignalWakeAutoConfiguration.java:88-100`) | continuous: `getNotifications(500 ms)` | store-postgres |

The spec's "five" are rows 1–5, the audit's table. Rows 6 and 7 have the same defect (D-2). This package covers all seven: rows 1–5 through the guard, rows 6–7 inline (D-9).

**Not loops, and why:**
- `LifecycleEventPublisher`: a `ThreadPoolExecutor` (`:74-79`). A task that throws kills its worker, and the pool replaces it.
- `SignalManager`'s and `PostgresSignalNotifier`'s notify threads: one virtual thread per `NOTIFY`, which ends after one statement.
- Activity-timeout and parallel-branch threads: per call, inside a run, under the run's own outcome handling.
- `MaestroHealthIndicator.PROBE_EXECUTOR`: one virtual thread per probe.
- Kafka listener containers: Spring Kafka owns their threads and error handling.

### D-2. What each loop does today (`main` @ `525b287`; reproduced, not only traced)

| Loop | `RuntimeException` (incl. `StoreUnavailableException`) | `Error` | `InterruptedException` |
|---|---|---|---|
| Timer poller | `catch (Exception)` (`TimerPoller.java:198`): `ERROR` with a stack trace, sleeps one interval, continues. A failing **leader election** is caught earlier, in `isLeader` (`:254-257`): `WARN` with a stack trace, "skipping this poll cycle". On a dead database that `WARN` fires every 5 s. | **Thread dies.** Not logged through SLF4J. | `break` (`:194-197`) |
| Recovery poller | `catch (Exception)` (`RecoveryPoller.java:116`): `ERROR` with a stack trace, continues. | **Thread dies.** | `break` (`:113-115`) |
| Signal janitor | Inside `sweepOnce` (`SignalJanitor.java:154`): `WARN` with a stack trace, returns 0. | **Thread dies.** Not in health. | `break` (`:174-177`) |
| Retention sweeper | Inside `sweepOnce` (`RetentionSweeper.java:229`), `finish` (`:257`), `isLeader` (`:274`): `WARN`. | **Thread dies.** | `break` (`:291-295`) |
| Instance-lock renewer | Around `renewAll` only (`WorkflowInstanceLockManager.java:375`): `WARN` (message only), keeps every handle, emits `instanceLockRenewFailed` per lock. That is a `WARN` every 10 s while locks are held and the database is down. | **Renewer dies for good.** `renewerStarted` stays `true`, so a later `tryAcquire` never restarts it. Not in health. | `return` (`:344-347`) |
| Lock cleaner | `catch (RuntimeException)` (`PostgresLockCleanerScheduler.java:84`): `WARN` with `toString`. | **Thread dies.** Not in health. | `return` (`:72-74`) |
| Wake listener | `SQLException`: `WARN`, closes, reconnects with a 1 s → 30 s back-off (`PostgresWakeListener.java:203-208`). `RuntimeException`: `WARN`, **continues with no wait** (`:212-219`), so a driver that keeps throwing spins the thread (traced). | **Thread dies.** `isConnected()` still says `true`, and the connection stays borrowed. | `break` (`:209-211`) |

`StoreUnavailableException` is a `RuntimeException`. No loop thread holds a `RunLease` or a `WorkflowContext`, so on a loop thread it is never latched and `FencedWriteException` cannot arise there. `fireTimer` uses `markTimerFired` (`WorkflowExecutor.java:925`), which is not run-scoped (traced).

**Reproduced at planning time (2026-11-17).** A standalone probe, not committed, compiled `main` @ `525b287`'s `maestro-core` with `javac` and drove every core loop with a store or lock whose first call throws `new Error(...)`. Tasks 2–4's red tests are the committed form of the same reproduction. Output:

```
Exception in thread "maestro-timer-poller-probe" java.lang.Error: boom-timer
Exception in thread "maestro-signal-janitor" java.lang.Error: boom-janitor
Exception in thread "maestro-retention-sweeper" java.lang.Error: boom-sweeper
Exception in thread "maestro-recovery-poller-probe" java.lang.Error: boom-recovery
timer:    store calls=1 running=false
recovery: store calls=1 running=false
janitor:  store calls=1 running=false
sweeper:  store calls=1 running=false
Exception in thread "maestro-instance-lock-renewer-probe" java.lang.Error: boom-renew
renewer:  renewAll calls=1 renewerThread alive=false renewerStarted=true
renewer:  second tryAcquire=ACQUIRED
renewer:  renewAll calls after a new acquire=1
```

Each `Exception in thread …` line came from the JDK's default handler on `System.err`, not from logback, so a log aggregator never sees it. A second probe against a real Postgres showed that a wake-listener consumer throwing an `Error` kills `maestro-pg-wake-listener` while `isConnected()` still returns `true`.

### D-3. The guard's contract

`final class BackgroundLoopGuard` (package-private, `io.b2mash.maestro.core.engine`, no Spring):

```java
static final String TIMER_POLLER = "timer-poller";
static final String RECOVERY_POLLER = "recovery-poller";
static final String SIGNAL_JANITOR = "signal-janitor";
static final String RETENTION_SWEEPER = "retention-sweeper";
static final String INSTANCE_LOCK_RENEWER = "instance-lock-renewer";
static final Duration REMINDER_INTERVAL = Duration.ofMinutes(5);

enum Logged { FIRST, REPEAT, REMINDER, STOPPING }

BackgroundLoopGuard(String loop, Duration retryInterval, BooleanSupplier stopping, EngineObserver observer);
BackgroundLoopGuard(String loop, Duration retryInterval, BooleanSupplier stopping, EngineObserver observer,
                    LongSupplier nanoTime);                       // test seam

Logged failed(Throwable failure);   // rethrows a MaestroControlFlowError unchanged; otherwise never throws
                                    // (an Error thrown by logging or the observer is the exception, see below)
boolean succeeded();                // true when it logged the recovery line
int consecutiveFailures();
Thread.UncaughtExceptionHandler uncaughtExceptionHandler();
```

**How `failed` decides:**
1. If `failure instanceof MaestroControlFlowError`, rethrow it unchanged, without logging or counting it. In a loop this ends the loop, as the `CLAUDE.md` rule requires.
2. If `stopping.getAsBoolean()` is true, the loop is being stopped: a store call that `stop()`'s interrupt broke is expected. Log at `DEBUG`, do not count it and do not notify the observer. Return `STOPPING`.
3. Otherwise, increment the streak and log according to D-6.
4. If `failure instanceof Error`, call `observer.backgroundLoopError(loop, failure.getClass().getName())` inside `try { … } catch (RuntimeException e) { DEBUG }`. This is depth: `CompositeEngineObserver` already contains `RuntimeException`s, but an embedder can wire an observer directly, as `WorkflowInstanceLockManager.emit` (`:437-444`) already allows for.

**Where it is called.** In every loop the iteration body sits in `try { …; guard.succeeded(); } catch (InterruptedException e) { …exit… } catch (Throwable t) { guard.failed(t); }`. The `InterruptedException` arm stays ahead of the broad one. The wait that follows (the loop's own `Thread.sleep`) is unchanged. Each loop's thread is built with `.uncaughtExceptionHandler(guard.uncaughtExceptionHandler())`. The handler logs `ERROR` with the throwable, using D-6's "stopped" copy, or `DEBUG` when `stopping` holds. That is the one place a rethrown control-flow `Error` or an `Error` escaping the guard itself is reported. It never rethrows.

**Thread safety.** A guard is confined to its loop's thread. `consecutiveFailures()` reads a `volatile int`, so tests and accessors may call it from any thread.

**Why a per-iteration guard, not a runner that owns the loop.** The five loops differ in shape: the recovery poller and the renewer sleep first, the janitor and the sweeper sweep first, and the timer poller runs its skew check in a `finally`. WP5 rewrites their joins, WP6 rewrites the recovery poller's wait, and WP8 rewrites what happens inside `sweepOnce`. A guard that only decides "rethrow or contain" leaves every loop's structure, wait and stop path where it is, which is the seam those packages need (D-10).

### D-4. Which `Error`s are rethrown: only `MaestroControlFlowError`

Rethrown: `ExecutorShutdownException`, `WorkflowTerminatedException` and `UnknownWorkflowHistoryException`, through the one `instanceof MaestroControlFlowError` check. Contained, logged, counted on `maestro.loop.errors` and followed by the loop's normal wait: every other `Error`, including the ones below.

| `Error` | Why contain it |
|---|---|
| `OutOfMemoryError` | The iteration's allocations are garbage once it unwinds, so the next iteration often succeeds. A dead daemon loop is strictly worse than a logged retry: a dead renewer churns every lock on the node (Audit A, Surprise 1). The JVM-wide decision belongs to the host (`-XX:+ExitOnOutOfMemoryError` / `CrashOnOutOfMemoryError`). Maestro must not make it for them by letting a thread die. |
| `StackOverflowError` | It is thread-local and fully recovered once the stack unwinds. |
| `LinkageError` (`NoClassDefFoundError`, `ExceptionInInitializerError`) | It is a bad deploy. It repeats on every iteration, so it is logged once loudly, then once per 5 minutes, and `maestro.loop.errors` rises. That is visible and alertable. A loop that dies of it is not. |
| `AssertionError`, other `Error`s | These are bugs. Same treatment. |

This is consistent with the engine's run-level `NodeErrors` rule (`NodeErrors.java:56-61`). There, a node error abandons the run without recording a failure. Here, a node error abandons the iteration without stopping the loop. The guard does not use `NodeErrors`: it treats every non-control-flow `Error` the same way.

### D-5. The back-off is the loop's own interval

| Loop | Wait after a failed iteration (unchanged) | Cap |
|---|---|---|
| Timer poller | `pollInterval` (5 s), the `Thread.sleep` in the failure arm (`TimerPoller.java:201-206`) | 5 s |
| Recovery poller | `pollInterval` (60 s), the sleep at the top of the loop (`:105`) | 60 s |
| Signal janitor | `interval` (1 h) (`:173`) | 1 h |
| Retention sweeper | `settings.interval()` (1 h) (`:292`) | 1 h |
| Instance-lock renewer | `renewInterval` (TTL/3, 10 s) (`:344`) | 10 s |
| Lock cleaner | `interval` (10 min) (`:71`) | 10 min |
| Wake listener | `SQLException`: today's 1 s → 30 s reconnect back-off. `RuntimeException` and `Error`: **new** fixed 1 s (`INITIAL_BACKOFF_MS`), which ends the spin (Task 5). | 30 s / 1 s |

**Why the schedule is constant, not exponential.**
- **The renewer must not slow down.** A lock lives 30 s. Backing the renewer off past 10 s would lose every lock on the node, which is the harm this package exists to prevent.
- **The timer poller must resume at once** when the database returns. A back-off of 5 → 60 s would delay every due timer by up to a minute after an outage ends.
- **No loop can spin.** Every engine loop's failure path already waits its interval. The intervals are ≥ 1 s in every documented configuration; a test may use 20 ms. The one loop with no wait on its failure path is the wake listener, and Task 5 gives it one.

So the guard adds no wait. **What is rate-limited is the log** (D-6).

**Shutdown during a back-off.** The wait is the loop's own `Thread.sleep`. `stop()` (or `close()` for the renewer) interrupts it, and the `InterruptedException` arm exits, exactly as today. A store call that swallowed the interrupt returns or throws. In both cases the existing `if (!running) break` re-checks end the loop. If it throws, the guard sees `stopping == true` and logs at `DEBUG` only. `BackgroundLoopStartShutdownRaceTest` pins that stopping is still prompt; it is run in Task 2 and Task 3.

### D-6. What is logged, at which level, and how often

One `failed()` and `succeeded()` sequence per loop:

| Event | Level | Copy (`{}` are SLF4J arguments) | Stack trace |
|---|---|---|---|
| First failure of a streak → `FIRST` | `ERROR` | `Background loop '{loop}' failed ({failure}); it keeps running and retries every {retryInterval} — further failures are logged at DEBUG, with a reminder every 5 minutes while they continue` | yes |
| Later failure, < 5 min since the last `ERROR` → `REPEAT` | `DEBUG` | `Background loop '{loop}' failed again ({n} in a row)` | yes |
| Later failure, ≥ 5 min since the last `ERROR` → `REMINDER` | `ERROR` | `Background loop '{loop}' is still failing: {n} consecutive failure(s) over {minutes} min; latest: {failure}` | no |
| Failure while stopping → `STOPPING` | `DEBUG` | `Background loop '{loop}' failed while stopping (ignored): {failure}` | no |
| `succeeded()` after a streak | `INFO` | `Background loop '{loop}' recovered after {n} consecutive failure(s)` | — |
| Uncaught (thread ends), not stopping | `ERROR` | `Background loop '{loop}' stopped: {failure} escaped it — it will not run again on this node until the node restarts` | yes |
| Uncaught, stopping | `DEBUG` | `Background loop '{loop}' exited while stopping: {failure}` | no |

`{failure}` is `Throwable.toString()`. `{minutes}` is the whole minutes since the streak's first failure. Times come from the injected `nanoTime`, never from the wall clock. The 5-minute reminder copies `PostgresSignalNotifier.FAILURE_WARN_INTERVAL` (`PostgresSignalNotifier.java:47`, R58), so the two outage logs read alike.

**A dead database, per hour at the defaults:**

| Source | Today | After WP4 |
|---|---|---|
| Timer-poller leader election (`isLeader`) | 720 × `WARN` + stack | 1 `ERROR` + stack, 12 reminders |
| Timer poll (no lock backend) | 720 × `ERROR` + stack | the same 1 + 12 |
| Recovery poller | 60 × `ERROR` + stack | 1 + 12 |
| Renewer (locks held) | 360 × `WARN` | 1 + 12 |
| Janitor, sweeper | 1 × `WARN` + stack each | unchanged (their store faults stay inside `sweepOnce`) |
| Lock cleaner | 6 × `WARN` | unchanged |

**Two code moves this needs** (Tasks 2 and 4):
- `TimerPoller.isLeader` stops catching. Its failure reaches the loop's guard, and `isLeader()` moves inside the inner `try` whose `finally` runs `skewCheck.maybeCheck()`, so the skew check still runs on every iteration (WP2's ordering comment at `:172-177` stays true).
- `renewPass`'s backend arm keeps every handle and still emits `instanceLockRenewFailed` per lock, so `maestro.lock.renew.failures{outcome=error}` is unchanged. It then calls `guard.failed(e)` instead of logging its own `WARN`. A pass with no backend failure calls `guard.succeeded()`.

### D-7. A loop that dies is visible

After the guard, a loop thread ends unexpectedly only when a `MaestroControlFlowError` reaches it outside a shutdown, or an `Error` escapes the guard itself (for example an `OutOfMemoryError` while logging). Both are reported three ways:
1. **SLF4J.** The uncaught handler (D-3) logs `ERROR`, never `System.err`.
2. **Health.** Today the timer poller, the recovery poller and the retention sweeper are reported. Task 7 adds the janitor (`signalJanitorRunning`, started = `signalJanitorSettings() != null`) and the renewer (`instanceLockRenewerRunning`). The renewer reads `"idle"` until a lock is first acquired: a node with no lock backend, or one that has run no workflow, never starts it, and `"starting"` would wrongly suggest that it will start. Once started, `false` means `DOWN`.
3. **Meter.** `maestro.loop.errors{loop}` counts contained `Error`s, so an alert fires before anything dies. `Exception`s are not counted: those are store or backend faults that `store`, `maestro.lock.renew.failures` and the logs already show. The counter answers one question only: "an `Error` reached a background loop". Any increase means a bug or resource exhaustion.

**Rejected:** restarting a dead loop automatically, for example resetting `renewerStarted` in the renewer's uncaught handler. After the guard a death is close to impossible. A self-restarting thread can hide a recurring fault, and the node restart that health and the log ask for is the honest remedy. **Rejected:** widening `TimerPoller.pollDueTimers`'s per-timer `catch (Exception)` (`:221`). An `Error` from one `fireTimer` ends that batch and reaches the guard, and the next poll retries the batch. A poison timer would need an `Error` specific to one row in `markTimerFired` plus an in-memory wake, which is not plausible (traced).

### D-8. 3.5a: a probe timeout is `UNKNOWN`

**Today** (`MaestroHealthIndicator.java:132-157`, `:184-199`): the probe submits `store.getInstance("__maestro-health-check__")` to a virtual thread and waits `STORE_PROBE_TIMEOUT = 2 s` (`:97`). A timeout gives `"timed out"`. Anything but `"reachable"` gives `Status.DOWN` (`:140-143`). When the pool is busy, the probe waits for a connection for up to Hikari's `connectionTimeout` (30 s), so a busy and healthy pool reads `DOWN`.

**After:**

```
fault := any started-and-enabled loop is not running (timer, recovery, retention, janitor, renewer)
status := fault || store == "unreachable" ? DOWN
        : store == "timed out"            ? UNKNOWN
        :                                   UP
```

`Status.UNKNOWN` is `org.springframework.boot.health.contributor.Status.UNKNOWN` (spring-boot-health 4.0.5). Verified by `javap` at planning time:
- `SimpleStatusAggregator`'s default order is `DOWN, OUT_OF_SERVICE, UP, UNKNOWN`, so an `UNKNOWN` member never pulls an aggregate below `UP` when any other member is `UP`.
- `SimpleHttpCodeStatusMapper` maps only `DOWN` and `OUT_OF_SERVICE` to 503, so `UNKNOWN` answers 200.
- `AvailabilityProbesHealthEndpointGroups` builds `liveness` from `livenessState` and `readiness` from `readinessState` only, so `maestro` is in neither group unless the host adds it.

**Kept:** the 2 s bound (no property; Audit A §3.5a(c)3 calls one unnecessary), the probe query, the `store` detail values, `withException` for a probe that throws, and `future.cancel(true)` on timeout. Cancelling interrupts the probe's virtual thread. In a pool wait that is harmless, but mid-query it closes the socket, which is the mechanism WP10 reproduced for Issue 38, so a slow database can see one evicted connection and one Hikari `WARN` per timed-out probe. That is traced and filed as `Issue N2`: changing it means a single-flight probe, which is a design change outside 3.5a.

**Kubernetes.** Boot's `liveness` and `readiness` groups are unaffected by default, because `maestro` is in neither. The harm was in hosts that point a probe at the aggregate `/actuator/health`. After WP4, a busy pool keeps the aggregate `UP`. The docs (Task 6) say the following:
- Never put `maestro` in liveness, and never point liveness at the aggregate.
- Add it to readiness only if the pod's HTTP traffic needs Maestro's store: `management.endpoint.health.group.readiness.include=readinessState,maestro`. The property name was verified in spring-boot-health 4.0.5's configuration metadata.
- A dead loop reads `DOWN`, the uncaught handler's `ERROR` names it, and the remedy is a restart, which readiness does not perform.

### D-9. The store-postgres loops (rows 6 and 7)

`BackgroundLoopGuard` is package-private in core, and making it public would add surface for two call sites. So both store-postgres loops apply D-3's rule inline:

```java
} catch (Throwable t) {
    if (t instanceof MaestroControlFlowError controlFlow) {
        throw controlFlow;
    }
    // log as below, then the loop's wait
}
```

- **Lock cleaner.** A `RuntimeException` keeps today's `WARN` with `toString`. An `Error` logs `ERROR` with the throwable. The cadence is 10 min, so it needs no rate limit.
- **Wake listener.** WP10 (Lane B, merged before WP4 by lane order) rewrites `pollLoop` into a `try { while … } finally { release }` and adds `private boolean awaitStop(long millis)`. This package then makes two changes:
  - the `RuntimeException` arm waits `awaitStop(INITIAL_BACKOFF_MS)` before continuing, which ends the spin;
  - a new arm, placed after the `InterruptedException` arm, rethrows a `MaestroControlFlowError`, and for any other `Error` logs `ERROR` with the throwable and waits `awaitStop(INITIAL_BACKOFF_MS)`.

  A notification batch cut short by an `Error` loses its remaining wakes. The engine's `wake-recheck-interval` re-read covers them, as it covers any lost `NOTIFY`.

Neither store-postgres loop reaches an `EngineObserver`, so neither feeds `maestro.loop.errors`. Neither is in health, because the indicator lives in the starter and does not know these beans. `Issue N1` records that gap.

### D-10. Seams for WP5, WP6, WP8 and the k6 lane

- **WP5 (shutdown).** It changes the loops' `stop()` joins to one shared deadline and adds the governor's poison permit. WP4 does not touch any `stop()` and adds no wait. A `stop()` that returns sooner is still observed by `stopping`.
- **WP6 (recovery capacity).** It rewrites `RecoveryPoller.pollLoop` to pass first, then wait on "a slot freed, or `pollInterval`". It keeps the guard around the pass, calling `guard.succeeded()` after a pass that returned and `guard.failed(t)` in the broad arm. Any new wait must stay interruptible, or wake on `stop()`. An `ExecutorShutdownException` from WP6's reservation is a `MaestroControlFlowError`: the guard rethrows it, the poller thread ends, and the uncaught handler logs at `DEBUG` because `running` is already `false` by then. WP6's one-shot startup pass for `maestro.recovery.enabled=false` should use its own `BackgroundLoopGuard(RECOVERY_POLLER, …)`, so a failure there is logged the same way.
- **WP8 (store hygiene).** It changes the inside of `RetentionSweeper.sweepOnce` (a pause between batches) and `SignalJanitor.sweepOnce` (a batched delete). The guard wraps the call to `sweepOnce()` in `sweepLoop` only. Store faults stay inside `sweepOnce`. A new pause inside `sweepOnce` must check `stopRequested`.
- **WP12 scenario 5 ("pool exhaustion vs health", C4).** Its "before" is WP4's merge base, where `maestro` reads `DOWN` / `"timed out"` under an exhausted pool. Its "after" is this branch, where the same run reads `UNKNOWN`. Task 6 is self-contained, so a "before" branch can also cherry-pick it alone.
- **WP11 (meters).** The spec puts this cycle's meter changes in WP11. `maestro.loop.errors` is the one meter whose producer this package adds, as WP1 added `maestro.activity.result.unreadable` and WP2 added `maestro.wake.permits`. Task 8 is a separate commit, so the maintainer can move it to WP11. The rebase surface is under Task 8.

### D-11. The two defensive-branch tests (spec §5; Audit C § 4.2-5)

These are tests only. Today's code already passes them; their teeth are the mutation checks.

1. **The executor's `catch (Error)` latch arm** (`WorkflowExecutor.java:2417-2421`, `storeUnavailableLatched(ctx)`). Workflow code catches the latched `StoreUnavailableException` and throws an `AssertionError`. `NodeErrors.isNodeError(AssertionError)` is `false`, so without the arm the run goes to `failAndCompensate`.
2. **`SagaManager.executeParallel`'s store-fault passes** (`SagaManager.java:744-757`). R1 rethrows a branch's raw `StoreUnavailableException`. R2 calls `throwIfStoreUnavailable` when a branch converted the fault. `StoreFaultStandDownTest` covers `parallel()` in workflow code (`:470-508`) and sequential compensation (`:421-468`), but not `@Saga(parallelCompensation = true)`.

The second test is parameterized over R1 and R2. R1 alone is redundant with R2, because the branches share the latch. The mutation table under Task 9 says which mutation each case catches.

### D-12. Anti-goals and mixed fleets

- **No run fails or compensates where it would stand down.** Loops run outside runs, and no loop writes history. The recovery poller's `launchWorkflow` path is unchanged.
- **Replay:** no history format, event or SPI changes.
- **Mixed fleet:** health and logging are per node. An old node's loops can still die. A new node's cannot. Nothing crosses nodes.

## Global Constraints

- **Spec anti-goals (§2), in effect:** see D-12. **An existing test is changed only as a named step.** The complete list is under "Existing tests changed". An implementer who meets any other failing test that encodes old behaviour stops and asks.
- **Code rules.** `maestro-core` never imports Spring. Jackson 3 (`tools.jackson`) only. JSpecify `@Nullable` on every nullable parameter and return. Javadoc on every public or protected member and on `BackgroundLoopGuard`, with thread-safety notes. No Lombok.
- **Control-flow `Error`s** (`CLAUDE.md` "Exceptions"):
  - every new `catch (Throwable)` checks `instanceof MaestroControlFlowError` and rethrows it first;
  - `StoreUnavailableException` and `FencedWriteException` are never recorded as workflow outcomes (loop threads record none, D-2);
  - no new `catch` inside workflow, activity or saga code.
- **No log line or meter tag** carries a workflow id, a run id or a payload. `maestro.loop.errors` has the single tag `loop`, with five values bounded by code.
- **Branch and worktree.** Branch `fix/loop-liveness-health`, cut from `origin/main` after WP3 has merged (Lane A order), in `.claude/worktrees/wp4-loop-liveness-health`. Task 5 also needs WP10 merged (see Before you start). Before the first edit, run `git -C <worktree> status` and `git status`. Never `cd` into the main checkout. This plan lives on `docs/engine-hardening-cycle`; read it from the main checkout's absolute path.
- **Gradle.** Always `./gradlew --no-watch-fs`, from the worktree root. Never two Gradle runs on one module at once. No `git stash`.
- **Commits.** Commit at every checkpoint named below. Chain git steps with `&&`. Pass messages as `-m "$(cat <<'EOF' … EOF)"`. Never run git-mutating commands in parallel. Every message ends with the attribution line your own session supplies.
- **Long runs** (`./gradlew --no-watch-fs build`, the whole `:maestro-integration-tests:test`, `e2eTest`) are **coordinator gates**, launched detached with a log whose header records `pwd`, `git rev-parse HEAD` and a timestamp. Implementers run only the targeted commands in the steps.
- **Red tests** fail on the unfixed code on a positive fact: a value, a status or a class. They never fail on a `ConditionTimeoutException`. Paste the FAIL lines into the commit body. Each fix gets at least one mutation check that a reviewer can repeat, and the mutation is reverted in the same step.
- **No sleeps** where a latch or an awaited condition works. A test store that must be "called a second time" counts a latch down on that call, and the test `await`s the latch with a bound and asserts the boolean.
- **Documentation** states only what a test shows, or it is labelled "(traced)".
- **New issues** take the next free numbers at implementation time (`grep -n '^### Issue' docs/open-issues.md | tail -1`). At planning time 58 is the highest on `main`, and 59–64 are reserved by a plan being written in parallel, so expect 65 and 66. They are `Issue N1` and `Issue N2` below.
- **PR gates (spec §6):** Build & Test; nightly E2E on the branch; chaos PR-gate ×3 (`./gradlew --no-watch-fs :maestro-integration-tests:e2eTest --rerun-tasks`, three consecutive runs). WP4 has no soak (spec §6). The maintainer merges.

## Review Focus

1. **A database outage on a node holding locks.** The renewer must keep every handle and keep its 10 s cadence. It must log one `ERROR` and then reminders, not a `WARN` per pass, and it must log `INFO` once the database returns. → Task 4 `aRenewalBackendFailureKeepsEveryHandleAndGoesThroughTheGuard`, `aSuccessfulPassAfterFailuresEndsTheStreak`; the cadence itself is Task 1's `repeatsWithinFiveMinutesAreQuietThenAReminderIsLoud`.
2. **An `Error` on the renewer.** Every lock must still be renewed on the next pass, and the renewer must not be left half-dead (`renewerStarted == true`, thread gone). → Task 4 `renewer_anErrorFromRenewAll_isContainedAndTheNextPassRenews`.
3. **Shutdown while a store call swallows the interrupt and then throws.** The loop must end promptly and log nothing above `DEBUG`. → Task 1 `whileStoppingAFailureIsQuietAndUncounted`; `BackgroundLoopStartShutdownRaceTest` and `SignalJanitorTest.stop_whileTheStoreSwallowsTheInterrupt_endsTheLoopPromptly` stay green.
4. **An exhausted pool on an otherwise healthy node.** A host with liveness on the aggregate must see `UP`, not `DOWN`, and a dead loop must still be `DOWN`. → Task 6 `returnsPromptlyWhenStoreBlocksIndefinitely` (now `UNKNOWN`), `aTimedOutProbeDoesNotHideADeadLoop`.
5. **A node with no lock backend.** It must not read `DOWN` because its renewer never started. → Task 7 `aRenewerThatNeverStartedIsIdleAndNotAFault`.
6. **The engine's control-flow `Error` on a loop thread.** It must end the loop, not be swallowed, and still be logged through SLF4J. → Task 1 `aControlFlowErrorIsRethrownUnchangedAndNotCounted`, Task 2 `timerPoller_aControlFlowErrorEndsTheLoop`.

## Existing tests changed (named; the only allowed edits)

| File | Edit | Task |
|---|---|---|
| `maestro-core/src/test/java/io/b2mash/maestro/core/observe/RecordingEngineObserver.java` | Add `public record LoopErrorCall(String loop, String errorType) {}`, a `loopErrors` list, the `backgroundLoopError` override (records `"backgroundLoopError"` like its siblings) and `public List<LoopErrorCall> loopErrors()`. | 1 |
| `maestro-core/src/test/java/io/b2mash/maestro/core/observe/CompositeEngineObserverTest.java` | `invokeEveryCallback` (`:267-291`) gains `o.backgroundLoopError("timer-poller", "java.lang.AssertionError");`. `everyCallbackFansOut` (`:140-174`) gains `assertEquals(1, recorder.loopErrors().size(), "backgroundLoopError");`. | 1 |
| `maestro-spring-boot-starter/src/test/java/io/b2mash/maestro/spring/health/MaestroHealthIndicatorTest.java` | `returnsPromptlyWhenStoreBlocksIndefinitely` (`:44-60`): `isEqualTo(Status.DOWN)` becomes `isEqualTo(Status.UNKNOWN)`. Its `@DisplayName` gains ", and reports UNKNOWN". The class Javadoc gains one sentence (Task 6 Step 1). | 6 |

**Why the old health assertion existed.** It was written for Issue 8's follow-up, which guarded that a hung store cannot hang `/actuator/health`. Its subject is the 2 s bound, and that half stays verbatim. `DOWN` was the status the code produced at the time, not a requirement anyone stated. 3.5a reverses it deliberately.

**Reviewer check, statement-level:**
1. `BASE=$(git merge-base origin/main HEAD); git diff --name-only --diff-filter=M "$BASE" HEAD -- '*/src/test/*'` must print exactly the files above, plus these files that only gain members (no line removed): `StoreFaultStandDownTest.java` (Task 9), `PostgresLockCleanerSchedulerTest.java` and `PostgresWakeListenerShutdownTest.java` (Task 5; the second is WP10's file), `MicrometerEngineObserverTest.java` (Task 8).
2. `git diff --name-only --diff-filter=D "$BASE" HEAD` must print nothing.
3. Read every hunk of those files with `git diff "$BASE" HEAD -- <file>`. Each removed line (`^-[^-]`) must belong to a named edit. A multi-line assertion counts as one statement: check its continuation lines too.

Do not grep for changed `assert` lines: that misses the continuation lines of multi-line assertions (found in WP0).

## Before you start

```bash
git fetch origin && \
git log origin/main --oneline -1 --grep='admin-retry\|WP3' && \
git log origin/main --oneline -1 --grep='wake-listener-shutdown\|WP10' && \
git worktree add -b fix/loop-liveness-health \
    .claude/worktrees/wp4-loop-liveness-health origin/main && \
git -C .claude/worktrees/wp4-loop-liveness-health status
```

The first log must print WP3's merge; if it prints nothing, stop. If the second prints nothing, WP10 has not merged. Tasks 1–4 and 6–9 can still go ahead. Task 5 waits, and the coordinator decides whether WP4 waits for it or ships Task 5's lock-cleaner half alone.

## File structure

| File | Responsibility |
|---|---|
| `maestro-core/.../engine/BackgroundLoopGuard.java` (new) | D-3, D-6 |
| `maestro-core/.../observe/EngineObserver.java`, `CompositeEngineObserver.java` | The `backgroundLoopError` callback |
| `maestro-core/.../engine/TimerPoller.java`, `RecoveryPoller.java` | Guarded iterations; `isLeader` through the guard |
| `maestro-core/.../engine/SignalJanitor.java`, `RetentionSweeper.java` | Guarded iterations |
| `maestro-core/.../engine/WorkflowInstanceLockManager.java` | Guarded pass; renewal failures through the guard; liveness accessors |
| `maestro-core/.../engine/WorkflowExecutor.java` | Passes `observer` to three loops; two renewer accessors |
| `maestro-store-postgres/.../lock/PostgresLockCleanerScheduler.java`, `.../notify/PostgresWakeListener.java` | D-9 inline |
| `maestro-spring-boot-starter/.../health/MaestroHealthIndicator.java` | `UNKNOWN`; two new details |
| `maestro-spring-boot-starter/.../observe/MicrometerEngineObserver.java` | `maestro.loop.errors` |
| New tests | `BackgroundLoopGuardTest`, `BackgroundLoopErrorTest` (core); `PostgresLockCleanerSchedulerTest` (new methods); `PostgresWakeListenerShutdownTest` (new methods; WP10's file); `MaestroHealthIndicatorTest` (new methods); `MicrometerEngineObserverTest` (new method); `StoreFaultStandDownTest` (new methods) |
| Docs | `docs/operations.md` (§10.9 `:901`, new §14), `docs/configuration.md` (Observability Configuration), `docs/observability.md`, `docs/test-plan.md:114`, `docs/release-notes.md`, `docs/open-issues.md`, `CLAUDE.md` |

## Pre-flight: task pairs that share a file or an interface

| Pair | Shared | Order and rule |
|---|---|---|
| 1 → 2, 3, 4 | `BackgroundLoopGuard` API, `EngineObserver.backgroundLoopError` | Task 1 first. Tasks 2–4 consume the API exactly as D-3 states. A signature change goes back to Task 1 and its tests. |
| 2, 3, 4 | `WorkflowExecutor.java` (Task 2: `startTimerPoller`, `startRecoveryPoller`; Task 3: `startSignalJanitor`; Task 4: two accessors near `:1946`) | Sequential. The hunks do not overlap. |
| 2, 3 | `BackgroundLoopErrorTest.java` (Task 2 creates it; Task 3 adds methods) | Task 2 first. |
| 4 → 7 | `isInstanceLockRenewerRunning()`, `hasInstanceLockRenewerStarted()` | Task 4 produces them; Task 7 consumes them. |
| 6, 7 | `MaestroHealthIndicator.java`, `MaestroHealthIndicatorTest.java` | Task 6 first (it is cherry-pickable alone, D-10); Task 7 rebases on it. |
| 1 → 8 | The callback | Task 8 consumes it. |
| 5 ↔ WP10 | `PostgresWakeListener.java`, `PostgresWakeListenerShutdownTest.java`, WP10's `ScriptedPgDataSource` and `awaitStop` | Task 5 only after WP10 has merged (Before you start). |
| 9 | `StoreFaultStandDownTest.java` only | Independent; any order. |

---

### Task 1: `BackgroundLoopGuard` and the `backgroundLoopError` callback

**Files:**
- Create: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/BackgroundLoopGuard.java`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/observe/EngineObserver.java`. Add a section `// ── Background loops ───` after "Retention" (`:216-225`), with the default method and its Javadoc: "A background loop (one of `timer-poller`, `recovery-poller`, `signal-janitor`, `retention-sweeper`, `instance-lock-renewer`) contained an `Error` that is not a `MaestroControlFlowError`, and keeps running. Fires on the loop's own thread, never on a workflow thread. `errorType` is the `Error`'s class name. Any occurrence means a bug or resource exhaustion on this node."
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/observe/CompositeEngineObserver.java`. Add an override that calls `fanOut("backgroundLoopError", d -> d.backgroundLoopError(loop, errorType))`.
- Modify (named): `RecordingEngineObserver.java`, `CompositeEngineObserverTest.java`
- Create: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/BackgroundLoopGuardTest.java`

**Interfaces:**
- Produces: everything under D-3, and `EngineObserver.backgroundLoopError(String, String)`.

- [ ] **Step 1: Write `BackgroundLoopGuardTest`.** Fixtures:
  - `var nanos = new AtomicLong()`;
  - `var stopping = new AtomicBoolean()`;
  - `var observer = new RecordingEngineObserver()`;
  - `guard = new BackgroundLoopGuard(BackgroundLoopGuard.TIMER_POLLER, Duration.ofSeconds(5), stopping::get, observer, nanos::get)`.

```java
@Test void aControlFlowErrorIsRethrownUnchangedAndNotCounted() {
    var shutdown = new ExecutorShutdownException("test");
    assertSame(shutdown, assertThrows(ExecutorShutdownException.class, () -> guard.failed(shutdown)));
    assertEquals(0, guard.consecutiveFailures());
    assertEquals(List.of(), observer.loopErrors());
}
@ParameterizedTest @MethodSource("survivableErrors")   // OutOfMemoryError, StackOverflowError, NoClassDefFoundError, AssertionError
void everyOtherErrorIsContainedAndReported(Error error) {
    assertEquals(BackgroundLoopGuard.Logged.FIRST, guard.failed(error));
    assertEquals(1, guard.consecutiveFailures());
    assertEquals(List.of(new RecordingEngineObserver.LoopErrorCall("timer-poller", error.getClass().getName())),
            observer.loopErrors());
}
@Test void anExceptionIsContainedButNotReportedToTheObserver() {
    assertEquals(BackgroundLoopGuard.Logged.FIRST,
            guard.failed(new StoreUnavailableException("down", new SQLException("08006"))));
    assertEquals(List.of(), observer.loopErrors());
}
@Test void repeatsWithinFiveMinutesAreQuietThenAReminderIsLoud() {
    guard.failed(new IllegalStateException("1"));
    nanos.addAndGet(Duration.ofMinutes(5).minusSeconds(1).toNanos());
    assertEquals(BackgroundLoopGuard.Logged.REPEAT, guard.failed(new IllegalStateException("2")));
    nanos.addAndGet(Duration.ofSeconds(1).toNanos());
    assertEquals(BackgroundLoopGuard.Logged.REMINDER, guard.failed(new IllegalStateException("3")));
    assertEquals(BackgroundLoopGuard.Logged.REPEAT, guard.failed(new IllegalStateException("4")));
    assertEquals(4, guard.consecutiveFailures());
}
@Test void aSuccessAfterAStreakLogsRecoveryOnceAndResets() {
    assertFalse(guard.succeeded(), "no streak, nothing to report");
    guard.failed(new IllegalStateException("1"));
    assertTrue(guard.succeeded());
    assertFalse(guard.succeeded());
    assertEquals(0, guard.consecutiveFailures());
    assertEquals(BackgroundLoopGuard.Logged.FIRST, guard.failed(new IllegalStateException("2")));
}
@Test void whileStoppingAFailureIsQuietAndUncounted() {
    stopping.set(true);
    assertEquals(BackgroundLoopGuard.Logged.STOPPING, guard.failed(new AssertionError("interrupted store call")));
    assertEquals(0, guard.consecutiveFailures());
    assertEquals(List.of(), observer.loopErrors());
}
@Test void aControlFlowErrorIsRethrownEvenWhileStopping() {
    stopping.set(true);
    assertThrows(ExecutorShutdownException.class, () -> guard.failed(new ExecutorShutdownException("test")));
}
@Test void aThrowingObserverIsContained() {
    var throwing = new EngineObserver() {
        @Override public void backgroundLoopError(String loop, String errorType) { throw new IllegalStateException("observer bug"); }
    };
    var g = new BackgroundLoopGuard(BackgroundLoopGuard.TIMER_POLLER, Duration.ofSeconds(5), () -> false, throwing, nanos::get);
    assertEquals(BackgroundLoopGuard.Logged.FIRST, g.failed(new AssertionError("x")));
}
@Test void theUncaughtHandlerNeverThrows() {
    assertDoesNotThrow(() -> guard.uncaughtExceptionHandler()
            .uncaughtException(Thread.currentThread(), new ExecutorShutdownException("test")));
}
```

- [ ] **Step 2: Make the named edits** to `RecordingEngineObserver` and `CompositeEngineObserverTest`, as in the table.
- [ ] **Step 3: Run red.** `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.BackgroundLoopGuardTest' --tests 'io.b2mash.maestro.core.observe.CompositeEngineObserverTest'`. Expected: compilation fails on `BackgroundLoopGuard` and `backgroundLoopError`. That is not a red; the reds of this package are in Tasks 2–7. Commit nothing yet.
- [ ] **Step 4: Implement** D-3 and D-6 exactly. Logger: `LoggerFactory.getLogger(BackgroundLoopGuard.class)`. Copy: D-6's table, verbatim. State:
  - `private volatile int streak`;
  - `private long streakStartNanos`;
  - `private long lastLoudNanos`.

  A `FIRST` sets both of the `long`s. A `REMINDER` sets `lastLoudNanos`. `succeeded()` zeroes `streak`. Then add the callback to `EngineObserver` and `CompositeEngineObserver`, with the Javadoc from Files. The class Javadoc of `BackgroundLoopGuard` states D-3's "Why a per-iteration guard", D-4's table in one paragraph, and "Thread safety: confined to its loop's thread; `consecutiveFailures()` may be read from any thread."
- [ ] **Step 5: Run green.** Same command. Expected: PASS.
- [ ] **Step 6: Mutation checks** (each reverted with `git checkout -- <file>`; `git diff` must then be empty for that file).
  - Remove step 1 (the control-flow rethrow) from `failed`: `aControlFlowErrorIsRethrownUnchangedAndNotCounted` and `aControlFlowErrorIsRethrownEvenWhileStopping` go red.
  - Swap the order of steps 1 and 2: `aControlFlowErrorIsRethrownEvenWhileStopping` goes red.
  - Change `>=` to `>` in the reminder test: `repeatsWithinFiveMinutesAreQuietThenAReminderIsLoud` goes red at the `REMINDER` line.
  - Report every `Throwable` to the observer: `anExceptionIsContainedButNotReportedToTheObserver` goes red.
- [ ] **Step 7: Commit:** `feat(engine): BackgroundLoopGuard and EngineObserver.backgroundLoopError (3.5b)`.

---

### Task 2: The timer poller and the recovery poller survive an `Error` (3.5b)

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/TimerPoller.java`:
  - a 7-argument constructor ending in `EngineObserver observer`; the 6-argument one delegates with `EngineObserver.NOOP`;
  - `private final BackgroundLoopGuard guard = new BackgroundLoopGuard(TIMER_POLLER, pollInterval, () -> !running, observer)`, assigned in the constructor;
  - `start()` (`:128-130`) adds `.uncaughtExceptionHandler(guard.uncaughtExceptionHandler())`;
  - `pollLoop` (`:168-210`): `isLeader()` moves inside the inner `try` whose `finally` runs `skewCheck.maybeCheck()`. After the inner block, the iteration calls `guard.succeeded()`. `catch (Exception e)` (`:198-207`) becomes `catch (Throwable t) { guard.failed(t); <the existing sleep and its InterruptedException break> }`;
  - `isLeader` (`:242-258`) loses its `try/catch`; its Javadoc says a backend failure propagates to the loop's guard and skips this cycle's poll;
  - `int consecutiveFailures()` returns `guard.consecutiveFailures()`;
  - the class Javadoc gains `<h2>Failures</h2>`: D-5's line for this loop and D-6's cadence, in two sentences.
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/RecoveryPoller.java`: the same pattern. The 5-argument constructor ends in `EngineObserver`, and the guard is `new BackgroundLoopGuard(RECOVERY_POLLER, pollInterval, () -> !running, observer)`. `catch (Exception e)` (`:116-119`) becomes `catch (Throwable t) { guard.failed(t); }`. `guard.succeeded()` follows `recoverWorkflows`.
- Modify: `WorkflowExecutor.java:1429` and `:1458` pass `observer`.
- Modify: `docs/operations.md:901`. "(`Recovery polling error — will retry next cycle` at `ERROR`)" becomes "(`Background loop 'recovery-poller' failed …` at `ERROR` once, then a reminder every 5 minutes, and `… recovered after N consecutive failure(s)` at `INFO`)".
- Create: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/BackgroundLoopErrorTest.java`

**Interfaces:**
- Consumes: Task 1.
- Produces (package-private): the constructor overloads and `consecutiveFailures()`.

- [ ] **Step 1: Write the red tests** in `BackgroundLoopErrorTest`. They compile on the unfixed code; the leader-election test comes in Step 5. Fixtures:
  - `PollFaultStore extends VersionedInMemoryStore`, with `volatile @Nullable Supplier<? extends Throwable> failure` (`null` = never throw) and `AtomicInteger` counters `dueTimerPolls` and `clockReads`. It overrides `getDueTimers(int)`: it counts, call 1 throws `failure.get()` when `failure != null`, and later calls return `super.getDueTimers(batchSize)`. It overrides `getRecoverableInstances(String, RecoveryCursor, int)`: call 1 throws in the same way, call 2 counts down `secondRecoveryPass`, and every call returns `List.of()`. Its `storeTime()` counts `clockReads` and returns `Optional.of(Instant.now())`.
  - `statusWithin(store, row, wanted, within)` polls `store.findTimer(row.workflowInstanceId(), row.timerId())` every 20 ms and returns the last status read.

```java
@ParameterizedTest @MethodSource("survivableErrors")
void timerPoller_anErrorFromAPoll_isContainedAndTheNextPollFiresTheTimer(Supplier<Error> error) {
    store.failure = error;
    var row = dueTimer(store, "wf-timer", "sleep-1");                 // PENDING, fire_at = now - 1 s
    executor.startTimerPoller(Duration.ofMillis(50), 10);
    assertEquals(TimerStatus.FIRED, statusWithin(store, row, TimerStatus.FIRED, Duration.ofSeconds(5)));
    assertTrue(executor.isTimerPollerRunning());
}
@Test void timerPoller_aControlFlowErrorEndsTheLoop() {
    store.failure = () -> new ExecutorShutdownException("test");
    executor.startTimerPoller(Duration.ofMillis(50), 10);
    await().atMost(Duration.ofSeconds(5)).until(() -> !executor.isTimerPollerRunning());
    assertEquals(1, store.dueTimerPolls.get(), "the guard rethrew it; the loop did not poll again");
}
@ParameterizedTest @MethodSource("survivableErrors")
void recoveryPoller_anErrorFromAPass_isContainedAndTheNextPassRuns(Supplier<Error> error) throws Exception {
    store.failure = error;
    executor.startRecoveryPoller(Map.of(), Duration.ofMillis(50));
    assertTrue(store.secondRecoveryPass.await(5, TimeUnit.SECONDS), "the recovery poller made no second pass");
    assertTrue(executor.isRecoveryPollerRunning());
}
```

  `survivableErrors()` supplies `OutOfMemoryError::new`, `StackOverflowError::new`, `() -> new NoClassDefFoundError("x")` and `() -> new AssertionError("x")`. The executor is built with `WorkflowExecutor.builder(store, serializer, "svc").lifecycleEventsEnabled(false).observer(observer).build()` and shut down in `@AfterEach`.

- [ ] **Step 2: Run red.** `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.BackgroundLoopErrorTest'`. Expected FAILs, one per supplier:
  - `expected: <FIRED> but was: <PENDING>`;
  - `the recovery poller made no second pass ==> expected: <true> but was: <false>`.

  `timerPoller_aControlFlowErrorEndsTheLoop` passes: it is the pin. Paste the FAIL lines into the commit body.
- [ ] **Step 3: Implement** the Files list.
- [ ] **Step 4: Run green.** Run the same command, then `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.TimerPoller*' --tests 'io.b2mash.maestro.core.engine.RecoveryPassIsolationTest' --tests 'io.b2mash.maestro.core.engine.BackgroundLoopStartShutdownRaceTest' --tests 'io.b2mash.maestro.core.engine.WorkflowExecutorShutdownTest'`. Expected: PASS.
- [ ] **Step 5: Add the leader-election pin.** `ThrowingLeaderLock` throws `new IllegalStateException("lock backend down")` from `trySetLeader`, and every other method throws `UnsupportedOperationException`. The store is a fresh `PollFaultStore` with `failure == null`.

```java
@Test void timerPoller_aLeaderElectionFailureGoesThroughTheGuardAndTheSkewCheckStillRuns() {
    var poller = new TimerPoller(store, executor, new ThrowingLeaderLock(), "svc", Duration.ofMillis(50), 10, observer);
    poller.start();
    try {
        await().atMost(Duration.ofSeconds(5)).until(() -> poller.consecutiveFailures() >= 2);
        assertEquals(1, store.clockReads.get(), "the skew check ran on the first iteration although isLeader threw");
        assertTrue(poller.isRunning());
    } finally { poller.stop(); }
}
```

  Run it: PASS.
- [ ] **Step 6: Mutation checks** (each reverted).
  - `catch (Throwable t)` back to `catch (Exception e)` in `TimerPoller`: the timer reds return.
  - Move `isLeader()` back above the inner `try`: `…SkewCheckStillRuns` goes red with `expected: <1> but was: <0>`.
  - Restore `isLeader`'s `catch (Exception)`: `…SkewCheckStillRuns` goes red, because `consecutiveFailures()` stays 0.
  - In `RecoveryPoller`, `catch (Throwable)` back to `catch (Exception)`: the recovery reds return.
- [ ] **Step 7: Commit:** `fix(engine): the timer and recovery pollers survive an Error; their failures are logged once, then every 5 minutes (3.5b)`.

---

### Task 3: The signal janitor and the retention sweeper survive an `Error` (3.5b)

**Files:**
- Modify: `SignalJanitor.java`:
  - a 4-argument constructor ending in `EngineObserver`; the 3-argument one delegates with `NOOP`;
  - `new BackgroundLoopGuard(SIGNAL_JANITOR, interval, () -> !running, observer)`;
  - `start()` gains the uncaught handler;
  - in `sweepLoop` (`:162-180`), `sweepOnce()` becomes `try { sweepOnce(); guard.succeeded(); } catch (Throwable t) { guard.failed(t); }`;
  - `int consecutiveFailures()`;
  - the "Failure handling" Javadoc (`:34-38`) gains: "An `Error` is contained by the loop's guard, logged and counted on `maestro.loop.errors`, and the next sweep runs one interval later."
- Modify: `RetentionSweeper.java`: the same pattern, with its existing `observer` and `new BackgroundLoopGuard(RETENTION_SWEEPER, settings.interval(), () -> !running, observer)`. In `sweepLoop` (`:281-299`), `sweepOnce()` becomes guarded the same way. `sweepOnce` itself is unchanged (D-10, WP8).
- Modify: `WorkflowExecutor.java:1505` passes `observer`.
- Modify: `BackgroundLoopErrorTest.java` (this branch's new file).

- [ ] **Step 1: Write the reds.**
  - `PollFaultStore` gains overrides of `deleteUnconsumedSignalsForTerminalInstancesBefore(Instant)` and `purgeTerminalInstancesBefore(WorkflowStatus, Instant, int)`. Call 1 throws `failure.get()`. Call 2 counts down `secondJanitorSweep` or `secondRetentionSweep`. Later calls return `0` or `PurgeResult.EMPTY`.
  - The janitor test uses `executor.startSignalJanitor(Duration.ofMillis(50), Duration.ofDays(1))`. The sweeper test uses `executor.startRetentionSweeper(new WorkflowExecutor.RetentionSettings(Duration.ofMillis(50), Duration.ofDays(1), Duration.ofDays(1), Duration.ofDays(1), 10, 1))`.

```java
@ParameterizedTest @MethodSource("survivableErrors")
void signalJanitor_anErrorFromASweep_isContainedAndTheNextSweepRuns(Supplier<Error> error) throws Exception {
    store.failure = error;
    executor.startSignalJanitor(Duration.ofMillis(50), Duration.ofDays(1));
    assertTrue(store.secondJanitorSweep.await(5, TimeUnit.SECONDS), "the janitor made no second sweep");
    assertTrue(executor.isSignalJanitorRunning());
}
@ParameterizedTest @MethodSource("survivableErrors")
void retentionSweeper_anErrorFromASweep_isContainedAndTheNextSweepRuns(Supplier<Error> error) throws Exception { /* same shape */ }
@Test void anErrorIsCountedForItsLoop() throws Exception {
    store.failure = () -> new AssertionError("x");
    executor.startSignalJanitor(Duration.ofMillis(50), Duration.ofDays(1));
    assertTrue(store.secondJanitorSweep.await(5, TimeUnit.SECONDS));
    assertEquals(new RecordingEngineObserver.LoopErrorCall("signal-janitor", "java.lang.AssertionError"),
            observer.loopErrors().getFirst());
}
```

- [ ] **Step 2: Run red.** `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.BackgroundLoopErrorTest'`. Expected FAILs:
  - `the janitor made no second sweep ==> expected: <true> but was: <false>`, and the same for the sweeper (one per supplier);
  - `anErrorIsCountedForItsLoop`: `expected: <true> but was: <false>`.
- [ ] **Step 3: Implement**, then run green: run the same command, then `--tests 'io.b2mash.maestro.core.engine.SignalJanitorTest' --tests 'io.b2mash.maestro.core.engine.RetentionSweeperTest' --tests 'io.b2mash.maestro.core.engine.BackgroundLoopStartShutdownRaceTest'`. Expected: PASS.
- [ ] **Step 4: Mutation checks.** Remove the guard from `SignalJanitor.sweepLoop`: its reds return. Pass `EngineObserver.NOOP` instead of `observer` at `WorkflowExecutor.java:1505`: `anErrorIsCountedForItsLoop` goes red. Revert both.
- [ ] **Step 5: Commit:** `fix(engine): the signal janitor and retention sweeper survive an Error (3.5b)`.

---

### Task 4: The instance-lock renewer survives an `Error`, and its failures go through the guard (3.5b)

**Files:**
- Modify: `WorkflowInstanceLockManager.java`:
  - the field `private final BackgroundLoopGuard guard`, assigned in the 6-argument constructor (`:148-172`) as `new BackgroundLoopGuard(INSTANCE_LOCK_RENEWER, renewInterval, closed::get, observer)`;
  - `renewerThreadStarter` (`:169-171`) adds `.uncaughtExceptionHandler(guard.uncaughtExceptionHandler())` before `.unstarted`;
  - in `renewLoop` (`:341-354`), `renewPass()` becomes `try { if (renewPass()) guard.succeeded(); } catch (Throwable t) { guard.failed(t); }`;
  - `renewPass` returns `boolean`: `false` from the backend arm, `true` otherwise (including the empty early return). Its backend arm (`:375-383`) keeps every handle and the per-lock `instanceLockRenewFailed`, and replaces its `logger.warn` with `guard.failed(e)`. The "pass slower than the interval" `WARN` in `finally` is unchanged;
  - `boolean isRenewerRunning()` returns `!closed.get() && renewerThread != null && renewerThread.isAlive()`;
  - `boolean hasRenewerStarted()` returns `renewerStarted.get()`;
  - `int renewFailureStreak()` returns `guard.consecutiveFailures()`;
  - the class Javadoc's "Lost locks" paragraph gains: "An `Error` in a pass is contained by the renewer's guard: every handle is kept and the next pass runs one interval later."
- Modify: `WorkflowExecutor.java`, after `hasRecoveryPollerStarted()` (`:1946-1948`), adds two public accessors with Javadoc:
  - `isInstanceLockRenewerRunning()`: "whether this node's instance-lock renewer thread is alive. It starts with the first instance lock this node acquires, so it is `false` before then, on a node without a `DistributedLock`, and after `shutdown()`";
  - `hasInstanceLockRenewerStarted()`: "monotonic in practice; see `hasTimerPollerStarted()` for the startup-vs-fault rationale".
- Modify: `BackgroundLoopErrorTest.java`.

- [ ] **Step 1: Add the two lock-manager liveness accessors** (`isRenewerRunning`, `hasRenewerStarted`, returning the values in the Files list), with no other change, so that the reds below compile on otherwise unfixed code.
- [ ] **Step 2: Write the reds.** `FaultyRenewLock implements DistributedLock`:
  - `tryAcquire` returns a handle;
  - `renewAll` increments `calls`, counts down `renewCalls` (a `CountDownLatch(2)`), and then takes its answer from `answer`, a `volatile Supplier<Set<LockHandle>>` (it may throw). After its first use the answer becomes `Set::of`, unless `alwaysFail` is set;
  - everything else is trivial.

  Build the manager as `new WorkflowInstanceLockManager(lock, "svc", "maestro:lock:", Duration.ofSeconds(30), Duration.ofMillis(50), observer)`.

```java
@ParameterizedTest @MethodSource("survivableErrors")
void renewer_anErrorFromRenewAll_isContainedAndTheNextPassRenews(Supplier<Error> error) throws Exception {
    lock.answer = () -> { throw error.get(); };
    assertEquals(WorkflowInstanceLockManager.Acquisition.ACQUIRED, manager.tryAcquire("wf-1"));
    assertTrue(lock.renewCalls.await(5, TimeUnit.SECONDS), "the renewer made no second pass");
    assertTrue(manager.isHeld("wf-1"), "an Error loses nothing: the handle is kept");
    assertTrue(manager.isRenewerRunning());
}
@Test void aControlFlowErrorStopsTheRenewer() {
    lock.answer = () -> { throw new ExecutorShutdownException("test"); };
    manager.tryAcquire("wf-1");
    await().atMost(Duration.ofSeconds(5)).until(() -> !manager.isRenewerRunning());
    assertTrue(manager.hasRenewerStarted());
}
```

- [ ] **Step 3: Run red.** `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.BackgroundLoopErrorTest'`. Expected FAIL, one per supplier: `the renewer made no second pass ==> expected: <true> but was: <false>`. `aControlFlowErrorStopsTheRenewer` passes (pin).
- [ ] **Step 4: Implement** the rest of the Files list (including `renewFailureStreak()`). Run green with the same command, then `--tests 'io.b2mash.maestro.core.engine.WorkflowInstanceLockManager*'`. Expected: PASS.
- [ ] **Step 5: Add the streak pins.** They need `renewFailureStreak()`, so they are written after it exists. Their teeth are Step 6.

```java
@Test void aRenewalBackendFailureKeepsEveryHandleAndGoesThroughTheGuard() {
    lock.alwaysFail = true;
    lock.answer = () -> { throw new IllegalStateException("db down"); };
    manager.tryAcquire("wf-1");
    await().atMost(Duration.ofSeconds(5)).until(() -> manager.renewFailureStreak() >= 3);
    assertTrue(manager.isHeld("wf-1"), "nothing known, nothing lost");
    assertTrue(observer.lockRenewFailed().size() >= 3, "still one instanceLockRenewFailed per lock per failed pass");
}
@Test void aSuccessfulPassAfterFailuresEndsTheStreak() {
    lock.answer = () -> { throw new IllegalStateException("db down"); };
    manager.tryAcquire("wf-1");
    await().atMost(Duration.ofSeconds(5)).until(() -> lock.calls.get() >= 2 && manager.renewFailureStreak() == 0);
    assertTrue(manager.isHeld("wf-1"));
}
```

  Run them: PASS.
- [ ] **Step 6: Mutation checks** (each reverted).
  - Restore `logger.warn` and drop `guard.failed(e)` in the backend arm: `aRenewalBackendFailureKeepsEveryHandleAndGoesThroughTheGuard` fails (the streak stays 0; the reviewer sees an Awaitility timeout here, which is acceptable for a mutation, not for a red).
  - Drop `if (renewPass())` and call `guard.succeeded()` unconditionally: the same test fails.
  - Remove the guard around `renewPass()`: the renewer reds return.
- [ ] **Step 7: Commit:** `fix(engine): the instance-lock renewer survives an Error; renewal failures are logged once, then every 5 minutes (3.5b)`.

---

### Task 5: The lock cleaner and the wake listener survive an `Error` (D-9)

Run this only after WP10 has merged; see Before you start.

**Files:**
- Modify: `maestro-store-postgres/src/main/java/io/b2mash/maestro/store/postgres/lock/PostgresLockCleanerScheduler.java`:
  - `loop()` (`:79-86`): `catch (RuntimeException e)` becomes D-9's `catch (Throwable t)`. A `RuntimeException` keeps `logger.warn("Postgres lock cleaner run failed; will retry in {}: {}", interval, t.toString())`. Any other `Error` gets `logger.error("Postgres lock cleaner run failed with {}; it keeps running and retries in {}", t.toString(), interval, t)`;
  - add `.uncaughtExceptionHandler((th, e) -> logger.error("Postgres lock cleaner stopped: {} escaped it", e.toString(), e))` to the thread builder at `:52`;
  - the class Javadoc gains a Thread Safety sentence on failures.
- Modify: `maestro-store-postgres/src/main/java/io/b2mash/maestro/store/postgres/notify/PostgresWakeListener.java`, as WP10 left it:
  - the `RuntimeException` arm waits `awaitStop(INITIAL_BACKOFF_MS)` after its `WARN`, and `break`s if that returns `true`;
  - a new arm after the `InterruptedException` arm: `catch (Error e) { if (e instanceof MaestroControlFlowError cf) throw cf; if (running.get()) logger.error("Postgres wake poll loop on channel '{}' hit {}; continuing in {} ms", channel, e.toString(), INITIAL_BACKOFF_MS, e); if (awaitStop(INITIAL_BACKOFF_MS)) break; }`;
  - add an uncaught handler to the thread builder at `start()`, logging `ERROR`.
- Test: `maestro-store-postgres/src/test/java/io/b2mash/maestro/store/postgres/lock/PostgresLockCleanerSchedulerTest.java` (new methods) and `.../notify/PostgresWakeListenerShutdownTest.java` (WP10's file; new methods only).

- [ ] **Step 1: Write the reds.** `PostgresLockCleaner` is `new PostgresLockCleaner(DataSource)` (`PostgresLockCleaner.java:51`), as `PostgresLockCleanerSchedulerTest.expiredRowIsDeletedOnSchedule` (`:28-29`) builds it. Wrap the suite's `dataSource` in a `DataSource` whose `getConnection()` throws `failure.get()` on call 1, counts down `secondRun` on call 2, and delegates otherwise.

```java
@ParameterizedTest @MethodSource("survivableErrors")
void anErrorFromARunIsContainedAndTheNextRunHappens(Supplier<Error> error) throws Exception {
    try (var scheduler = new PostgresLockCleanerScheduler(new PostgresLockCleaner(faulty), Duration.ofMillis(50))) {
        scheduler.start();
        assertTrue(faulty.secondRun.await(5, TimeUnit.SECONDS), "the cleaner made no second run");
    }
}
```

  In `PostgresWakeListenerShutdownTest`, use WP10's `ScriptedPgDataSource` (its API is listed in WP10 Task 2's Interfaces):

```java
@Test void aDriverThatKeepsThrowingARuntimeExceptionWaitsBeforeTheNextPoll() throws Exception {
    scripted.failPollsWith(() -> new IllegalStateException("driver bug"));
    var listener = scripted.newListener();
    listener.start();
    await().atMost(Duration.ofSeconds(5)).until(() -> scripted.pollStartNanos().size() >= 2);
    var starts = scripted.pollStartNanos();
    var gap = Duration.ofNanos(starts.get(1) - starts.get(0));
    assertTrue(gap.compareTo(Duration.ofMillis(900)) >= 0, "gap between polls after a RuntimeException: " + gap);
}
@Test void anErrorFromAConsumerDoesNotEndTheLoop() throws Exception {
    var listener = scripted.newListener();
    var woken = new CountDownLatch(1);
    listener.register("wf-1", s -> { throw new AssertionError("consumer bug"); });
    listener.register("wf-2", s -> woken.countDown());
    listener.start();
    scripted.deliver("wf-1", "go");                      // returned by the next poll
    scripted.deliver("wf-2", "go");                      // returned by a later poll
    assertTrue(woken.await(5, TimeUnit.SECONDS), "the poll loop died on the consumer's Error");
}
```

- [ ] **Step 2: Run red.** `./gradlew --no-watch-fs :maestro-store-postgres:test --tests '*PostgresLockCleanerSchedulerTest' --tests '*PostgresWakeListenerShutdownTest'`. Expected FAILs:
  - `the cleaner made no second run ==> expected: <true> but was: <false>`, one per supplier;
  - `gap between polls after a RuntimeException: PT0.000…S` (microseconds: the spin);
  - `the poll loop died on the consumer's Error ==> expected: <true> but was: <false>`.

  If `scripted.deliver` delivers both notifications in one poll on WP10's helper, the consumer test still holds: the `Error` ends that batch, and `wf-2` must then arrive on a later poll. Make `deliver` one-per-poll in that case, and note it in the commit.
- [ ] **Step 3: Implement**, then run green with the same command. Expected: PASS. Also run `--tests '*PostgresWakeListener*' --tests '*PostgresSignalNotifierTest'`.
- [ ] **Step 4: Mutation checks** (each reverted).
  - Remove the `MaestroControlFlowError` rethrow from the wake-listener `Error` arm. No test goes red: no wake-listener consumer can throw one in production (D-9). Record that in the commit body, so the reviewer knows the line is rule, not tested behaviour.
  - Remove `awaitStop` from the `RuntimeException` arm: the spin test goes red.
- [ ] **Step 5: Commit:** `fix(store-postgres): the lock cleaner and the wake listener survive an Error; the listener no longer spins on a RuntimeException (3.5b)`.

---

### Task 6: A health probe timeout reports `UNKNOWN` (3.5a)

Self-contained on purpose: WP12's scenario 5 can cherry-pick it (D-10).

**Files:**
- Modify: `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/health/MaestroHealthIndicator.java`:
  - `health()` (`:140-143`) uses D-8's status rule. A loop fault or `"unreachable"` gives `DOWN`. Otherwise `"timed out"` gives `UNKNOWN`. Otherwise `UP`;
  - the class Javadoc (`:17-60`) rewrites its first list as `DOWN`, `UNKNOWN` and `UP`, and gains a paragraph `<h2>Readiness, never liveness</h2>` that carries D-8's three Kubernetes bullets in short form.
- Modify (named): `MaestroHealthIndicatorTest.java`.
- Docs:
  - `docs/operations.md`: a new `## 14. Health checks and Kubernetes probes` before `## See also` (`:1223`). Text in Step 5;
  - the §13 "Pool sizing" paragraph (`:1205-1220`): after "A pool that runs dry shows up as `STORE_UNAVAILABLE` abandons (§10.9), not as workflow failures.", add "and as `store: "timed out"` with status `UNKNOWN` on `/actuator/health` (§14)";
  - `docs/configuration.md` "Observability Configuration" (`:884`): one paragraph pointing at Operations §14;
  - `docs/test-plan.md:114`: the row gains "a probe timeout is `UNKNOWN` (3.5a)".

- [ ] **Step 1: Make the named edit**, then add tests to `MaestroHealthIndicatorTest`. `ThrowingWorkflowStore extends DelegatingWorkflowStore` throws `new IllegalStateException("connection refused")` from `getInstance`.

```java
@Test void aStoreThatThrowsWithinTheBoundIsDown() {
    var health = new MaestroHealthIndicator(new ThrowingWorkflowStore(), executor, true, true).health();
    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("store", "unreachable");
}
@Test void aTimedOutProbeDoesNotHideADeadLoop() {
    executor.startTimerPoller(Duration.ofSeconds(5), 100);
    executor.shutdown();                                   // started, then stopped: a fault
    var health = new MaestroHealthIndicator(new BlockingWorkflowStore(), executor, false, false).health();
    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("store", "timed out").containsEntry("timerPollerRunning", false);
}
```

  The class Javadoc sentence: "Since 3.5a a probe that does not finish within the bound reports `UNKNOWN`: a busy pool is not a broken store."
- [ ] **Step 2: Run red.** `./gradlew --no-watch-fs :maestro-spring-boot-starter:test --tests 'io.b2mash.maestro.spring.health.MaestroHealthIndicatorTest'`. Expected FAIL: `returnsPromptlyWhenStoreBlocksIndefinitely` with `expected: UNKNOWN but was: DOWN`. The two new tests pass on the unfixed code (pins).
- [ ] **Step 3: Implement.** Run green: the same command, then `--tests 'io.b2mash.maestro.spring.health.*'`. Expected: PASS.
- [ ] **Step 4: Mutation checks** (each reverted).
  - Let `"timed out"` outrank a loop fault: `aTimedOutProbeDoesNotHideADeadLoop` goes red.
  - Map `"unreachable"` to `UNKNOWN`: `aStoreThatThrowsWithinTheBoundIsDown` goes red.
- [ ] **Step 5: Docs.** Operations §14:

  > **Maestro's health indicator is a readiness signal at most, never liveness.** The `maestro` contributor (`MaestroHealthIndicator`) reports `DOWN` when the store refused the probe (`store: "unreachable"`) or a background loop that started has stopped (`timerPollerRunning`, `recoveryPollerRunning`, `retentionSweeperRunning`: `false`). It reports `UNKNOWN` when the probe (`getInstance` on a sentinel id) did not finish within 2 s (`store: "timed out"`) — usually a pool whose connections are all borrowed, which is the node doing its job. Spring Boot orders statuses `DOWN`, `OUT_OF_SERVICE`, `UP`, `UNKNOWN`, so an `UNKNOWN` `maestro` leaves the aggregate `UP`, and only `DOWN` and `OUT_OF_SERVICE` answer 503.
  >
  > - **Liveness.** Spring Boot's `liveness` group contains only `livenessState`; keep it that way, and never point a liveness probe at `/actuator/health`. Restarting a busy pod abandons its in-flight runs to the lock TTL and adds a recovery pass to the fleet's load.
  > - **Readiness.** Add `maestro` only if this pod's HTTP traffic needs Maestro's store: `management.endpoint.health.group.readiness.include=readinessState,maestro`. A shared database that is down then takes every pod out of the Service, which is what you want only if the pods cannot serve without it.
  > - **A dead loop.** Since 3.5b a loop contains every `Error` except Maestro's control-flow signals, so a loop that stops is a bug: the log carries `Background loop '<name>' stopped: …` at `ERROR`, health reads `DOWN`, and the remedy is restarting the node — readiness does not do that for you.

  Task 7 adds `signalJanitorRunning` and `instanceLockRenewerRunning` to the first paragraph's list. Task 8 appends to the last bullet: "Alert on `maestro.loop.errors` (contained `Error`s) before it comes to that." This keeps Task 6 cherry-pickable without describing what it does not ship.

  The configuration paragraph: "`/actuator/health`'s `maestro` component is described in [Operations §14](operations.md#14-health-checks-and-kubernetes-probes): it is a readiness signal at most, and a probe timeout reads `UNKNOWN`."
- [ ] **Step 6: Commit:** `fix(health): a store probe timeout reports UNKNOWN, not DOWN; document the indicator as readiness-only (3.5a)`.

---

### Task 7: Health reports the signal janitor and the instance-lock renewer

**Files:**
- Modify: `MaestroHealthIndicator.java`:
  - `var janitor = pollerState(executor.signalJanitorSettings() != null, executor.isSignalJanitorRunning(), false)`;
  - `var renewer = renewerState(executor.hasInstanceLockRenewerStarted(), executor.isInstanceLockRenewerRunning())`, a new private static method returning `new PollerState(IDLE, false)` when it has not started, else `new PollerState(running, !running)`;
  - `static final String IDLE = "idle"`, with Javadoc: "Detail value for the instance-lock renewer before this node has acquired its first instance lock: on a node without a `DistributedLock`, or one that has run no workflow yet. Not a fault";
  - both details go into the builder after `retentionSweeperRunning`; both faults go into D-8's `fault`;
  - the details list in the class Javadoc gains both entries.
- Test: `MaestroHealthIndicatorTest.java` (new methods).

- [ ] **Step 1: Write the reds.** Build `executorWithLock = WorkflowExecutor.builder(new InMemoryWorkflowStore(), serializer, "svc").distributedLock(new InMemoryDistributedLock()).lifecycleEventsEnabled(false).build()` (`io.b2mash.maestro.test.InMemoryDistributedLock`; the default 30 s TTL is fine, since the test only needs the renewer started), and shut it down in `@AfterEach`. `AwaitForever` awaits a signal for an hour.

```java
@Test void aRenewerThatNeverStartedIsIdleAndNotAFault() {
    var health = new MaestroHealthIndicator(new InMemoryWorkflowStore(), executor, false, false).health();
    assertThat(health.getDetails()).containsEntry("instanceLockRenewerRunning", "idle");
    assertThat(health.getStatus()).isEqualTo(Status.UP);
}
@Test void aRenewerThatStartedAndStoppedIsDown() throws Exception {
    executorWithLock.startWorkflow("wf-renew", "AwaitForever", "default", null, AwaitForever::new, awaitForever);
    await().atMost(Duration.ofSeconds(5)).until(executorWithLock::hasInstanceLockRenewerStarted);
    var indicator = new MaestroHealthIndicator(new InMemoryWorkflowStore(), executorWithLock, false, false);
    assertThat(indicator.health().getDetails()).containsEntry("instanceLockRenewerRunning", true);
    executorWithLock.shutdown();
    var health = indicator.health();
    assertThat(health.getDetails()).containsEntry("instanceLockRenewerRunning", false);
    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
}
@Test void theSignalJanitorIsStartingThenRunningThenAFaultOnceStopped() {
    var indicator = new MaestroHealthIndicator(new InMemoryWorkflowStore(), executor, false, false);
    assertThat(indicator.health().getDetails()).containsEntry("signalJanitorRunning", "starting");
    executor.startSignalJanitor(Duration.ofHours(1), Duration.ofDays(7));
    assertThat(indicator.health().getDetails()).containsEntry("signalJanitorRunning", true);
    executor.shutdown();
    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    assertThat(indicator.health().getDetails()).containsEntry("signalJanitorRunning", false);
}
```

- [ ] **Step 2: Run red.** `./gradlew --no-watch-fs :maestro-spring-boot-starter:test --tests 'io.b2mash.maestro.spring.health.MaestroHealthIndicatorTest'`. Expected FAILs: AssertJ `Expecting map … to contain entry ["instanceLockRenewerRunning"="idle"]` (and the same for the other two). Each failure lists the details map, which has no such key.
- [ ] **Step 3: Implement**, then run green: the same command, then `--tests 'io.b2mash.maestro.spring.health.*'`. Expected: PASS.
- [ ] **Step 4: Mutation checks.** Map an unstarted renewer to `"starting"` with `fault = false`: the `"idle"` entry assertion goes red. Leave the renewer out of `fault`: `aRenewerThatStartedAndStoppedIsDown` goes red. Revert both.
- [ ] **Step 5: Docs.** `docs/operations.md:1055`, the sentence on `retentionSweeperRunning`, gains: "`signalJanitorRunning` and `instanceLockRenewerRunning` read the same way (the renewer reads `"idle"` until this node takes its first instance lock); see §14." In §14's first paragraph, the list of loop details gains `signalJanitorRunning`, `instanceLockRenewerRunning`.
- [ ] **Step 6: Commit:** `feat(health): report the signal janitor and the instance-lock renewer; a stopped one is DOWN (3.5b)`.

---

### Task 8: The `maestro.loop.errors` counter

**Files:**
- Modify: `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/observe/MicrometerEngineObserver.java`:
  - `private static final String TAG_LOOP = "loop"`;
  - the override calls `increment("maestro.loop.errors", TAG_LOOP, loop)`;
  - a class-Javadoc catalog row after `maestro.lock.renew.failures` (`:44`).
- Modify: `docs/observability.md`:
  - a counters-table row after `maestro.lock.renew.failures` (`:105`): `` | `maestro.loop.errors` | `loop` | `timer-poller`, `recovery-poller`, `signal-janitor`, `retention-sweeper`, `instance-lock-renewer` | A background loop contained an `Error` (not an `Exception`, not one of Maestro's control-flow signals) and kept running. Any increase is a bug or resource exhaustion on that node; the loop's `ERROR` log names the throwable ([Operations §14](operations.md#14-health-checks-and-kubernetes-probes)) | ``;
  - the meter-count sentence at `:70` and the callback count at `:182`. **Recount from the tables** (counter rows, timer rows, gauge rows; `grep -c 'default ' EngineObserver.java` for callbacks). Do not add deltas by hand: WP11 edits the same sentences.
- Modify: `docs/configuration.md` meter list (`:894-896`).
- Modify: `docs/operations.md` §14, the "A dead loop" bullet: append "Alert on `maestro.loop.errors` (contained `Error`s) before it comes to that."
- Test: `MicrometerEngineObserverTest.java` (new method).

- [ ] **Step 1: Test.**

```java
@Test void backgroundLoopErrorCountsByLoop() {
    observer.backgroundLoopError("instance-lock-renewer", "java.lang.OutOfMemoryError");
    observer.backgroundLoopError("instance-lock-renewer", "java.lang.AssertionError");
    var counter = registry.get("maestro.loop.errors").tag("loop", "instance-lock-renewer").counter();
    assertEquals(2.0, counter.count());
    assertEquals(List.of("loop"), counter.getId().getTags().stream().map(Tag::getKey).toList());
}
```

- [ ] **Step 2: Run red.** `./gradlew --no-watch-fs :maestro-spring-boot-starter:test --tests '*MicrometerEngineObserverTest'`. Expected: `MeterNotFoundException` naming `maestro.loop.errors`.
- [ ] **Step 3: Implement**, then run green. Mutation: tag with `errorType` as well, and the tag-key assertion goes red; revert.
- [ ] **Step 4: Commit:** `feat(observe): maestro.loop.errors counts Errors contained by a background loop (3.5b)`.

**WP11 rebase.** WP11 adds `maestro.run.abandoned`, `maestro.workflow.duration`, `maestro.taskqueue.slots.used` and `workflowFinished` to `EngineObserver`, `CompositeEngineObserver`, `MicrometerEngineObserver`, `RecordingEngineObserver`, `CompositeEngineObserverTest` and `docs/observability.md`. The names do not collide. Keep both sides' additions, and recount the doc sentences from the tables.

---

### Task 9: Two tests for the untested defensive branches (spec §5)

**Files:** `maestro-core/src/test/java/io/b2mash/maestro/core/engine/StoreFaultStandDownTest.java`. Add members only: two fixture classes and two tests, in the `SagaAndParallel` nested class and at the top level.

- [ ] **Step 1: Fixtures.**
  - `SwallowsThenThrowsAssertionError`: `try { return steps.step1(in); } catch (Exception e) { throw new AssertionError("workflow code converted the store fault: " + e); }`.
  - `ParallelCompensationSaga`: `@Saga(parallelCompensation = true)` with `reserve`, a compensation added via `WorkflowContext.current().addCompensation("convertingRefund", …)`, `charge`, then `ship` (throws).
  - The manual compensation, used only by the R2 case, calls `steps.refund(in)` inside `try { … } catch (StoreUnavailableException e) { throw new IllegalStateException("converted: " + e.getMessage()); }`. The R1 case uses the `refund` activity directly.

- [ ] **Step 2: Tests.**

```java
@Test
@DisplayName("workflow code that turns the latched store fault into an AssertionError is abandoned, not failed")
void anErrorThrownAfterTheLatchAbandonsTheRun() throws Exception {
    faulty.failWhen("getEventBySequence", args -> (Integer) args[1] == 1);
    var impl = new CountingSteps();
    var workflow = new SwallowsThenThrowsAssertionError(proxy(StepActivities.class, impl, guarded, RetryPolicy.noRetry()));
    var instanceId = executor.startWorkflow("sf-error-after-latch", "SwallowsThenThrowsAssertionError", "default", "in",
            () -> workflow, SwallowsThenThrowsAssertionError.class.getMethod("run", String.class));
    awaitRunEnded("sf-error-after-latch");
    assertAll(
            () -> assertEquals(List.of(AbandonReason.STORE_UNAVAILABLE), abandonReasons()),
            () -> assertEquals(WorkflowStatus.RUNNING, status("sf-error-after-latch")),
            () -> assertEquals(0, count(instanceId, EventType.WORKFLOW_FAILED)),
            () -> assertTrue(observer.failed().isEmpty(), "workflowFailed must not fire"));
}

@ParameterizedTest(name = "{0}")
@EnumSource(ParallelCompensationFault.class)          // RAW_FAULT (R1), CONVERTED_FAULT (R2)
@DisplayName("a store fault in a parallel compensation branch leaves the run COMPENSATING with no failed step")
void aStoreFaultInAParallelCompensationBranchIsNotAFailedReversal(ParallelCompensationFault mode) throws Exception {
    // faulty.failWhen("appendEvent", <COMPENSATION_STEP_COMPLETED of ".refund"> for RAW_FAULT,
    //                                <the refund activity's ACTIVITY_COMPLETED inside convertingRefund> for CONVERTED_FAULT)
    // start ParallelCompensationSaga; awaitRunEnded
    assertAll(
            () -> assertEquals(List.of(AbandonReason.STORE_UNAVAILABLE), abandonReasons()),
            () -> assertEquals(WorkflowStatus.COMPENSATING, status(id)),
            () -> assertEquals(0, count(instanceId, EventType.COMPENSATION_STEP_FAILED), "a store fault is not a failed reversal"),
            () -> assertEquals(0, count(instanceId, EventType.WORKFLOW_FAILED)));
}
```

  If the `CONVERTED_FAULT` fault predicate cannot be expressed against the events the parallel unwind writes, read `SagaManager.executeParallel` (`:550-760`) and choose the first append the converted branch makes. Record the choice in the test's comment.
- [ ] **Step 3: Run them.** `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.StoreFaultStandDownTest'`. Expected: PASS, three cases. They are pins of existing code.
- [ ] **Step 4: Mutation checks** (each reverted with `git checkout -- <file>`). Record each result in the commit body.

| Mutation | Expected red |
|---|---|
| `WorkflowExecutor.java:2417`: `else if (storeUnavailableLatched(ctx))` becomes `else if (false)` | `anErrorThrownAfterTheLatchAbandonsTheRun` (`WORKFLOW_FAILED` or `observer.failed()` non-empty) |
| `SagaManager.java:756`: delete `ctx.cancellation().throwIfStoreUnavailable(…)` | `CONVERTED_FAULT` (`COMPENSATION_STEP_FAILED` = 1) |
| Delete the R1 loop (`:744-751`) only | **none expected**. R2 covers it through the shared latch (D-11). Record it as "R1 is depth" |
| Delete both R1 and R2 | `RAW_FAULT` and `CONVERTED_FAULT` |

  If the third row goes red, R1 is not redundant. Record which case went red and continue.
- [ ] **Step 5: Commit:** `test(engine): pin the executor's catch (Error) latch arm and executeParallel's store-fault passes (spec §5)`.

---

### Task 10: Release note, issues, docs pass, gates

**Files:** `docs/release-notes.md`, `docs/open-issues.md`, `CLAUDE.md`.

- [ ] **Step 1: Release note.** Add a new first subsection under `## Unreleased — Postgres + Kafka only`: `### Fixed — background loops survive an Error; a health probe timeout is UNKNOWN (3.5a, 3.5b)`. Its bullets:
  - **What was wrong.** D-2's table in three sentences. All seven loops died on an `Error`, which the JDK printed to `System.err`, not to the log. The renewer's death was invisible and churned every lock on the node 30 s after acquisition.
  - **What changes.** D-3 and D-4 in two sentences; D-5's "the cadence is unchanged"; D-6's per-hour table, given as "a dead database now logs one `ERROR` per loop and a reminder every 5 minutes"; the two new health details; `maestro.loop.errors`.
  - **3.5a.** A probe timeout is `UNKNOWN`. Operations §14 is linked, and its liveness/readiness rule appears in one sentence.
  - **Changed log lines.** The four replaced texts (surface table), for anyone alerting on them.
- [ ] **Step 2: `CLAUDE.md`.** Under "Coding Standards → Exceptions", after "any new `catch` that collects failures must rethrow it too.", add: "A background loop wraps each iteration in `BackgroundLoopGuard` (package `engine`): it rethrows a `MaestroControlFlowError` and contains every other `Throwable`, so an `Error` never ends a loop silently (3.5b)."
- [ ] **Step 3: `docs/open-issues.md`.** File two issues with the house sections (what happens, file:line, harm, fix shape, why it is not WP4), plus an index row for each:
  - **`Issue N1` — The wake listener's session and the lock cleaner are not in health** (Low). `PostgresWakeListener.isConnected()` and the cleaner's liveness reach no indicator. The starter's indicator does not know store-postgres beans (D-9). Fix shape: a `HealthContributor` in `maestro-store-postgres` (`maestroPostgres`), readiness-only like §14.
  - **`Issue N2` — A timed-out health probe interrupts its virtual thread, which can close a pooled connection mid-query** (Low; traced). `MaestroHealthIndicator.java:190` calls `future.cancel(true)`. In a pool wait the interrupt is harmless. Mid-query it closes the socket, which is the mechanism WP10 reproduced for Issue 38, so a slow database sees one Hikari `WARN` and one eviction per timed-out probe. Fix shape: a single-flight probe (reuse an in-flight probe; never interrupt).
- [ ] **Step 4: Run the existing-tests check** from "Existing tests changed", and paste its output into the commit body. Then run `./gradlew --no-watch-fs :maestro-core:javadoc :maestro-spring-boot-starter:javadoc :maestro-store-postgres:javadoc`. Expected: no warning naming a changed member.
- [ ] **Step 5: Commit:** `docs: WP4 release note, two new issues, the loop-guard rule (3.5a, 3.5b)`.
- [ ] **Step 6: Coordinator gates, before the PR merges.**
  - `./gradlew --no-watch-fs build`, detached with a log header;
  - nightly E2E on the branch (`gh workflow run e2e-nightly.yml --ref fix/loop-liveness-health`);
  - chaos PR-gate ×3.

  Every result goes in the review section with its log identity. Also send D-10's WP12 note (the scenario 5 before/after) to the owner of `test/k6-load-foundation`, and Task 8's rebase note to WP11.

---

## Self-review (run 2026-11-17)

- **Spec coverage.**
  - 3.5b, one guard for the five engine loops including the renewer: Tasks 1–4.
  - Rethrow control flow; log and back off on anything else: D-3–D-6.
  - The loops the spec did not count: Task 5.
  - 3.5a, `UNKNOWN` plus the readiness-only doc: Task 6.
  - The two §5 tests: Task 9.
  - The surface is listed first. Gates follow §6 (no soak for WP4).
- **The brief's questions:**
  - which loops exist, and the reconciliation with "five": D-1;
  - today's behaviour on each throwable class, reproduced: D-2;
  - the guard's signature and location: D-3;
  - which `Error`s, with the `OOM`/`SOE`/`LinkageError` justification: D-4;
  - back-off values and cap, and shutdown during a back-off: D-5;
  - log levels and cadence, and the dead-database flood: D-6;
  - meter, observer and death visibility: D-7;
  - the probe, its timeout, the Boot 4 API, Kubernetes groups, doc text: D-8 and Task 6;
  - the WP6/WP8 seams: D-10.
- **Type consistency.** These names are the same in every task:
  - `BackgroundLoopGuard`, `failed`, `succeeded`, `consecutiveFailures`, `uncaughtExceptionHandler`, `Logged`;
  - the five loop-name constants;
  - `backgroundLoopError(String, String)`, `LoopErrorCall`, `loopErrors()`;
  - `isInstanceLockRenewerRunning`, `hasInstanceLockRenewerStarted`, `isRenewerRunning`, `hasRenewerStarted`, `renewFailureStreak`;
  - `IDLE`, `signalJanitorRunning`, `instanceLockRenewerRunning`, `maestro.loop.errors`.
- **Proportion.** The code blocks are test assertions, the guard's signature, log copy and doc text. Implementation bodies are left to the signatures and tests.

## Review section

(The coordinator fills this in: the red and mutation lines per task; the issue numbers given to N1 and N2; gate results with log identity; the WP11 and WP12 hand-offs.)
