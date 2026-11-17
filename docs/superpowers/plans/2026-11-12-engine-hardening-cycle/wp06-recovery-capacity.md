# WP6 Recovery Capacity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A node adopts only the recovered runs it can run or queue (Issue 30 / 3.1). The recovery poller asks the task-queue governor for a slot *without blocking*, and only then takes the instance lock. When no slot is free it may still adopt up to one queue's concurrency of runs, which wait in the same first-in-first-out line as fresh starts (C2), so a recovered run takes its turn and never starves. The rest are deferred: left unlocked for a peer, and re-polled as soon as capacity frees, not a full poll interval later. The startup pass runs on the poller's thread, not on the `ApplicationRunner` thread. With `maestro.recovery.enabled=false` the startup pass stays ungated. Then Issue 36: `TestWorkflowEnvironment` takes opt-in concurrency limits (C3).

**Architecture:** Three additions in `maestro-core`, no Spring:
- **`TaskQueueGovernor.admitRecovery(taskQueue)`** returns a `SlotReservation`, or `null` for "deferred". It never blocks. A reservation is `RESERVED` (a permit, taken with the fair timed `tryAcquire(0, …)`, so it never barges past a waiting run), `QUEUED` (one of the queue's `concurrency` recovery tickets: the run will wait for a permit FIFO on its own thread) or the shared `UNBOUNDED` singleton. The launched run claims it on its own thread. Every path that does not launch the run releases it. Both are exactly-once through one compare-and-set.
- **`CapacitySignal`**, an event count the governor bumps whenever a workflow permit or a recovery ticket is given back. The poller reads it *before* a pass and waits for it to move *after* a pass that deferred, so a release during the pass is never missed.
- **`RecoveryPoller`** runs a pass first, then waits. After a pass that deferred, it runs a *capacity pass* as soon as capacity frees, at most once per second. A capacity pass resumes at the first row the last pass deferred, reads a page sized to the free capacity, and makes no statement at all when nothing is free. A *full* pass, from the first row, still runs every `maestro.recovery.poll-interval`.

`recoverWorkflows(Map)` stays the public **ungated** pass (adopt everything, as today). It is what the poller-disabled startup pass, the test kit and the integration harness call. `StartupRecoveryRunner` only starts things: the poller, or a one-shot ungated pass on its own virtual thread.

**Tech Stack:** Java 25, `maestro-core` (no Spring), `maestro-spring-boot-starter` (Spring Boot 4.0.5), `maestro-test`, `maestro-integration-tests` (Testcontainers `postgres:16-alpine`), JUnit 5, Awaitility.

**Spec:** `docs/superpowers/plans/2026-11-12-engine-hardening-cycle/00-spec.md`: the WP6 row, §2 anti-goals, §3 ("Issue 30 as filed is incomplete", "Issue 36 as filed cannot work"), §6 (2 h soak), C2, C3, C4. Evidence: `evidence/audit-A-recovery-shutdown-health.md` §3.1 and §Issue 36, "Surprises" 2 and 4. Issues: `docs/open-issues.md` Issues 30, 36 (and 29, 12 for context). Handover: `tasks/handover-2026-11-12-after-host-safety-cycle.md` finding 3.1. Siblings:
- `wp04-loop-liveness-health.md` D-10: the seam this package builds on (guarded pass, `RECOVERY_POLLER` guard for the one-shot pass).
- WP5 (shutdown, planned in parallel): closes the governor and passes a poison permit down its queues. See "Before you start".
- `wp11-k6-foundation.md`: also edits `TaskQueueGovernor.Bucket` and the meter catalog. See the rebase notes under Tasks 1 and 9.

All line numbers below are for `fix/loop-liveness` @ `14514f54` (WP0–WP4 and WP10 content). Re-check them after cutting the branch: WP5 changes `WorkflowExecutor.shutdown()`, `TaskQueueGovernor` and the pollers' `stop()`.

## Added, changed and removed public surface

**Added**

| Surface | Exact shape | Task |
|---|---|---|
| Executor method | `public void WorkflowExecutor.startOneShotRecovery(Map<String, WorkflowRegistration> registrations, Duration retryInterval)`. It runs one **ungated** recovery pass (`recoverWorkflows`) on a virtual thread `maestro-startup-recovery-<service>`. A pass that throws is contained by a `BackgroundLoopGuard(RECOVERY_POLLER, …)` and retried after `retryInterval` until one pass completes (MD-2). `shutdown()` stops it. It throws `IllegalStateException` when already started or shutting down. | 7 |
| Executor accessor | `public int WorkflowExecutor.recoveryDeferredCount()`. It returns how many instances the most recent **full** gated pass on this node deferred for lack of task-queue capacity, and `0` before the first one. It is the gauge's source (MD-3). | 2 |
| Gauge (MD-3) | `maestro.recovery.deferred` (Prometheus `maestro_recovery_deferred`), no tags, from `recoveryDeferredCount()`, in `MaestroEngineGauges`. | 9 |
| Test-kit builder | `public static TestWorkflowEnvironment.Builder TestWorkflowEnvironment.builder()`. `Builder` has `objectMapper(ObjectMapper)`, `defaultConcurrency(int workflowConcurrency, int activityConcurrency)`, `taskQueueConcurrency(String taskQueue, int workflowConcurrency, int activityConcurrency)` and `build()`. `create()` and `create(ObjectMapper)` stay, and delegate. | 10 |
| Annotation attributes (MD-5) | `int MaestroTest.defaultConcurrency() default 0` and `int MaestroTest.defaultActivityConcurrency() default 0`. `0`/`0` is unbounded, which is today's kit behaviour. | 10 |

**Package-private** (named so that reviewers can check them; not public surface):
- `TaskQueueGovernor`:
  - `@Nullable SlotReservation admitRecovery(String taskQueue)`;
  - `void acquireWorkflowSlot(String taskQueue, String workflowId, @Nullable SlotReservation reservation)` (the claim);
  - `int recoveryHeadroom(String taskQueue)`;
  - `CapacitySignal capacitySignal()`;
  - nested `static final class SlotReservation` with `enum Kind { UNBOUNDED, RESERVED, QUEUED }`, `Kind kind()`, `void release()` and `static final SlotReservation UNBOUNDED`.
- `CapacitySignal` (new class): `long epoch()`, `void signal()`, `boolean awaitChange(long seen, Duration max) throws InterruptedException`.
- `WorkflowExecutor`:
  - `enum RecoveryGate { GATED, UNGATED }`;
  - `RecoveryPass recoveryPass(Map)` becomes the **gated full** pass;
  - `RecoveryPass capacityPass(Map, @Nullable RecoveryCursor resumeAfter, Set<String> deferredQueues)`;
  - `RecoveryPass` gains `Map<String, Integer> deferredByQueue` and `@Nullable RecoveryCursor resumeAfter`, plus `int deferred()`;
  - test seams `volatile Runnable beforeRunBody` and `void startRecoveryPoller(Map, Duration pollInterval, Duration capacitySpacing)`;
  - `boolean isOneShotRecoveryRunning()`.
- `RecoveryPoller`: a constructor taking `CapacitySignal` and `Duration capacitySpacing`. The existing constructors delegate.
- `OneShotRecovery` (new class).

**Changed behaviour** (no new configuration property, SPI method or migration)

- **Recovery admission (3.1).** With bounded task queues (the default 20/20), a recovery-poller pass adopts, per queue per node, at most the free slots plus `concurrency` queued runs. Every other recoverable instance is *deferred*: no lock, no adoption, no lease-epoch bump, and a peer may take it. Unbounded queues (`default-concurrency: 0` and `default-activity-concurrency: 0`) are unchanged.
- **Re-poll on capacity.** After a pass that deferred, the poller runs again as soon as a workflow slot or a recovery ticket frees on this node, at most once per `min(1 s, maestro.recovery.poll-interval)` (MD-4). Full passes keep their `poll-interval` cadence.
- **Pass first.** The poller's first pass runs when it starts, not one `poll-interval` later.
- **Startup.** `StartupRecoveryRunner.run` no longer adopts anything on the `ApplicationRunner` thread. It returns once its loops are started, so `ApplicationReadyEvent` no longer waits for the startup pass (MD-6). A store failure in the startup pass no longer fails application startup. It is logged and retried (MD-2).
- **`maestro.recovery.enabled=false`.** The startup pass is a one-shot **ungated** pass on its own thread: it adopts every instance it can lock, exactly as today.
- **Counters.** `maestro.recovery.scanned` and `maestro.recovery.adopted` also move on capacity passes, so during a drain `scanned` grows by the rows each capacity pass reads.
- **Log lines.** These replace or add lines; none carries a workflow id:
  - `Maestro startup recovery complete: {} workflow(s) recovered` (`StartupRecoveryRunner:45`) is removed;
  - the poller's first pass logs `Maestro startup recovery pass: adopted {} workflow(s); {} deferred until a task-queue slot frees on this node`;
  - a full pass that deferred logs, at `INFO`, `Recovery pass adopted {} and deferred {} instance(s) for lack of task-queue capacity on this node {queue=count}; they are adopted as slots free`. A capacity pass logs the same at `DEBUG`;
  - the one-shot pass logs `Maestro startup recovery (recovery poller disabled) adopted {} workflow(s) without a capacity check; runs beyond a task queue's bound wait for their slots`.

**Removed:** nothing.

**Doc rule changes:** `CLAUDE.md`'s recovery paragraph (`:221`) gains one sentence (Task 11). Pitfalls rule 11's "Test it" paragraph is rewritten, because the kit can now apply limits.

## Today's recovery path, end to end (traced, and reproduced at planning time)

| Step | Where (`14514f54`) | What it does | Blocks? Holds? |
|---|---|---|---|
| Startup | `StartupRecoveryRunner.run` (`StartupRecoveryRunner.java:41-85`) | `executor.recoverWorkflows(registrations)` at `:44`, **synchronously on the `ApplicationRunner` thread**, then starts the timer poller, janitor and sweeper, then the recovery poller (`:81-84`). | The runner thread is held for the whole pass. A store exception fails application startup. |
| Poller loop | `RecoveryPoller.pollLoop` (`RecoveryPoller.java:145-168`) | **Sleeps first** (`:148`), then `executor.recoverWorkflows` (`:152`), guarded by WP4's `BackgroundLoopGuard` (`:160-165`). | No slot logic. |
| Pass | `WorkflowExecutor.recoveryPass` (`WorkflowExecutor.java:770-805`) | Keyset pages of `RECOVERY_PAGE_SIZE = 200` (`:749`) via `getRecoverableInstances(serviceName, after, 200)` (`:781`) until a short page. | Never consults the governor. |
| Page | `recoverPage` (`:816-866`) | Drops runs already running here (`:819-821`), makes one `heldElsewhere` read (`:822`), skips held and unregistered instances, and calls `resumeWorkflow` (`:839`) for every other instance. It isolates only a `WorkflowInstantiationException` (`:842`). | — |
| Resume | `resumeWorkflow` (`:694-715`) | Checks `shuttingDown` (`:699`) and running here (`:704`), then `launchWorkflow(…, replaying=true, null)` (`:709`). | — |
| Lock | `launchWorkflow` → `instanceLockManager.tryAcquire` (`:2191`) | `INSERT … ON CONFLICT` (`PostgresDistributedLock.ACQUIRE_SQL`). On success the handle goes into `heldLocks` and **is renewed every TTL/3 = 10 s** from then on (`WorkflowInstanceLockManager.java:185-244`). | — |
| Adopt | `getInstance` (`:2222`), terminal check (`:2223-2228`), `adopt` (`:2240`, `:2372-2393`) | `SELECT`, then the version-CAS `UPDATE` that bumps `lease_epoch`. From here every earlier holder is fenced. | — |
| Launch | `:2258-2332` | Builds the context and the workflow object (factory at `:2290`), registers the run in `runningWorkflows` (`:2322`), and starts the virtual thread (`:2332`). | — |
| Slot | `executeWorkflow` → `taskQueueGovernor.acquireWorkflowSlot` (`:2410`) → `TaskQueueGovernor.take` (`:338-361`) → `Semaphore.acquire()` on a **fair** semaphore (`:320-324`, `:370-376`) | The run's thread waits here, FIFO, behind every run already queued. | **Here is the block.** The pass has already returned. While the run waits it holds its instance lock (renewed), its `runningWorkflows` entry, a virtual thread and a workflow object. Peers' `heldKeys` reads report its lock as held. |

Each adoption costs 3 statements (lock insert, `getInstance`, CAS update), plus 1 page read and 1 `heldKeys` read per page (Audit A §3.1(a); the probe's counts agree).

**Reproduced at planning time (2026-11-17)** in a scratch worktree at `14514f54` (since removed). The throwaway tests were a probe with a shared in-memory store and a shared map-backed lock with a real `heldKeys`, and two executors at `defaultTaskQueueLimits(C, C)`:

```
PROBE A.recoverWorkflows adopted=40 in 20 ms; store calls {updateInstance=40, getRecoverablePage=1, getInstance=40}; lock acquires=40
PROBE locks held right after A's pass: 40 (A can run 4)
PROBE A slots free=OptionalInt[0], running entries=40
PROBE B pass: scanned=40 adopted=0; B sees 40 keys held elsewhere
PROBE drain of 40 runs (C=4, hold=100 ms) took 1158 ms; ran on {A=40}; ideal 1 node=1000 ms, 2 nodes=500 ms
PROBE fair tryAcquire(0) with a queued waiter: 0/1000 succeeded; after release (permit to waiter): false
PROBE startup pass: 500 adoptions at 1 ms/statement took 2338 ms on the calling thread; ... lock acquires 500, heldKeys 3
PROBE activity order after signal: [rec-0, rec-1, rec-2, rec-3, rec-4, parked]; parked run resumed 539 ms after its signal
```

What the probe shows:
- **Hoarding (3.1).** A node that can run 4 locks all 40. The peer finds 40 keys held elsewhere, 36 of them for runs nobody is executing, and adopts nothing. The drain takes 1,158 ms on one node, against an ideal of 500 ms on two. At the handover's scale (5,000 runs, 20 slots, 2 s per run), that is ~500 s on one node while the peer idles, instead of ~250 s.
- **Lock hold.** The last queued run held its lock for ≈ 1 s of the 1.16 s drain. At production scale that is ≈ 500 s of renewals for a lock whose run has not started.
- **FIFO, no barging, and why a bare gate starves.** A fair `tryAcquire(0, SECONDS)` never succeeded while a waiter was queued (0 of 1,000), even right after a release. So a gate that only tries for a free slot admits nothing for as long as fresh starts keep a waiter queued. That is C2's reason.
- **Parked wakes queue behind the hoard (Audit A "Surprises" 2, now reproduced).** At concurrency 1, a parked run signalled after a 5-run recovery resumed only after all 5 (index 5).
- **Startup.** 500 adoptions at 1 ms per statement held the calling thread for 2.3 s (≈ 4.7 ms per adoption). By that ratio, 5,000 take ≈ 23 s on the `ApplicationRunner` thread, and readiness waits for them (`ApplicationReadyEvent` is published after the runners return: Audit A, inferred from Boot; not re-verified).

The same scratch run produced the real red output of Tasks 2, 5 and 7 (quoted there).

## Decisions

### D-1. The reservation: reserve, then lock; claim on the run's thread; release exactly once

**Order in `recoverPage`, per instance:**
1. drop if running here;
2. drop if `heldElsewhere`;
3. drop if no registration;
4. `admitRecovery(instance.taskQueue())`: a `null` result counts the instance as deferred on that queue and moves on;
5. `resumeWorkflow(…, reservation)` inside `try { launched = … } finally { if (!launched) reservation.release(); }`.

The slot is reserved *before* `tryAcquire`, so a node never takes a lock for a run it can neither run nor queue. Nothing sits between step 4 and step 5.

**`admitRecovery(taskQueue)`** never blocks and never throws:
- **Unbounded queue:** it returns the shared `SlotReservation.UNBOUNDED`, with no allocation (D-7).
- **Governor closed** (WP5's `shutdown()`): `null`.
- **Otherwise:**
  - `workflowSlots.tryAcquire(0, SECONDS)`, the timed form, so a waiting run is never overtaken (`acquireActivitySlot` already uses this idiom at `:257-259`). On success: a `RESERVED` reservation holding one permit.
  - Else, if a recovery ticket is free (`recoveryTickets`, an `AtomicInteger` per bucket, initialised to the queue's `workflowConcurrency`, CAS-decremented while positive): a `QUEUED` reservation holding one ticket.
  - Else `null`.
  - An `InterruptedException` from `tryAcquire` (the poller's `stop()`) restores the interrupt flag and returns `null`. `recoverPage` ends the pass at the next instance once the flag is set. An `Error` on the poller thread is never manufactured here.

**The claim:** `acquireWorkflowSlot(taskQueue, workflowId, reservation)` runs as `executeWorkflow`'s first statement, replacing the call at `:2410`, and inside the same `try` whose `finally` (`:2608`) calls `releaseWorkflowSlot`. With `null` or `UNBOUNDED` it is today's `acquireWorkflowSlot`. Otherwise it wins the reservation's `claimed` compare-and-set:
- **`RESERVED`:** it registers the run's `RunSlot` with `held = true`, the permit the reservation took. If a `RunSlot` already exists for that id (which cannot happen while the instance lock is held, but can with no lock backend), it gives the reserved permit back and continues with the existing claim, as today.
- **`QUEUED`:** it registers the `RunSlot` and `take`s it, waiting FIFO like any fresh start. Its `finally` returns the ticket and bumps the capacity signal, however the wait ended: a permit, WP5's poison permit, or an interrupt.

A claim that loses the compare-and-set (the reservation was already released) falls back to today's `acquireWorkflowSlot`.

**`release()`:** wins the same compare-and-set, then gives the permit back (`RESERVED`) or the ticket (`QUEUED`), and bumps the signal. On `UNBOUNDED` it does nothing. The second of any two calls (claim or release, in any order) does nothing. That is the exactly-once guarantee, pinned by `TaskQueueGovernorTest.aReservationIsReleasedOrClaimedExactlyOnce`.

**Why a separate reservation object, not a pre-registered `RunSlot`.** A `RunSlot` placed in `runs` before the launch would be found by `computeIfAbsent` in any concurrently launching run of the same id (a retry, or a start with no lock backend). That run would then run on our permit, and our failure-path release would `runs.remove` its slot. The reservation enters `runs` only on the run's own thread.

**Every window between reservation and the run owning it** (W1–W9). Each must release the reservation once, and the instance lock wherever today's code takes one:

| # | Window (`14514f54`) | Exit | Released by | Pinned by (Task 2/3) |
|---|---|---|---|---|
| W1 | `resumeWorkflow`, executor shutting down (`:699`) | `false` | `recoverPage`'s `finally` | `aDeferredOrFailedAdoptionGivesItsReservationBack(SHUTTING_DOWN)` |
| W2 | `resumeWorkflow`, already running here (`:704`) | `false` | `finally` | `…(RUNNING_HERE)` |
| W3 | `launchWorkflow`, `tryAcquire` = `HELD_ELSEWHERE` (`:2192-2197`) | `false` | `finally` | `…(LOCK_HELD_ELSEWHERE)` |
| W4 | `tryAcquire` throws an `Error` after recording the handle (`WorkflowInstanceLockManager.java:227-242`: it releases its own handle and rethrows) | throws | `finally` | `…(LOCK_CALLBACK_ERROR)` |
| W5 | instance turned terminal or vanished (`:2223-2228`) | `false` (lock released at `:2226`) | `finally` | `…(TURNED_TERMINAL)` |
| W6 | adoption CAS lost (`:2241-2244`) | `false` (lock released) | `finally` | `…(CAS_LOST)` |
| W7 | `getInstance`/`adopt` throws `RuntimeException \| Error`, for example `StoreUnavailableException` (`:2246-2249`) | throws (lock released) | `finally` | `…(STORE_FAULT)` |
| W8 | after admission and before `thread.start()`: `afterLaunchAdmission`, ops and context construction, factory (`WorkflowInstantiationException`), thread build, `runningWorkflows.put`, `start` (`:2258-2347`) | throws (lock released, entry removed at `:2333-2347`) | `finally` | `…(FACTORY_THROWS)`, `…(ERROR_AFTER_ADMISSION)` |
| W9 | **the run's thread is started, but `executeWorkflow` was never entered**: the lambda runs `WorkflowMDC.populate(ctx)` (`:2308`) and `ScopedValue.where(…).run(…)` before `executeWorkflow`'s `try` | the thread dies with the `Throwable` | **new:** the lambda's `finally` runs the launch-failure cleanup when the body was not entered (Task 3) | `AdoptionLockLeakTest.aThrowableBeforeTheRunBodyReleasesTheLockTheSlotAndTheEntry` |
| — | `executeWorkflow` entered: claim, run, any outcome | — | `executeWorkflow`'s `finally` (`:2604-2635`), unchanged; the claim's own `finally` returns a ticket | existing tests, plus `aQueuedRunInterruptedWhileWaitingReturnsItsTicket` |

**W9 is pre-existing for the lock.** Today, a `Throwable` there leaves the instance lock in `heldLocks`, renewed forever, and the `runningWorkflows` entry in place, so this node never adopts that workflow again and no peer can (traced, not tested). That is the same class as the two adoption lock-leak windows the previous package closed (`AdoptionLockLeakTest` windows (a) and (b)). WP6 would add a reservation to what leaks there, so Task 3 closes the whole window: lock, entry, pending permits and reservation, through the seam `beforeRunBody` (as `afterLaunchAdmission` did for W8).

### D-2. The anti-starvation rule is C2: recovered runs queue like fresh starts, at most `concurrency` per queue per node

Coordinator call C2 settles the rule. This is its arithmetic for one queue on one node. C = `workflowConcurrency`; T = mean time a run holds its slot, so μ = C / T slot hand-offs per second while saturated; λ = rate of fresh starts.

- **A recovered run never starves.** A `QUEUED` run enqueues behind the W waiters present at that moment. A fair semaphore hands permits strictly in arrival order, so it gets its slot after exactly W + 1 hand-offs, that is after W / μ + T on average. That is **the same wait as a fresh start submitted at that instant**, for every λ: a recovered run is never treated worse than a fresh start. When it gets its slot, its ticket returns, the capacity signal fires, and within the spacing s (D-3) the poller queues the next recovered run. Each of the C tickets therefore admits one recovered run per ≈ (W / μ + T + s).
  - **λ < μ** (the queue drains between bursts): W is bounded and recovery advances at roughly C runs per (W̄ / μ + T + s).
  - **λ ≥ μ** (sustained overload): W grows and so does every fresh start's wait. Recovered runs advance more slowly, but never stop: each ticket keeps being served in its turn.
  - **A bare gate** (try for a free permit only) admits nothing while W > 0, because a fair `tryAcquire(0)` refuses whenever anyone waits (probe: 0/1000). Under λ ≥ μ that is indefinite starvation.
- **What it costs fresh starts:** at most C extra runs ahead of a fresh start, so ≤ C extra hand-offs ≈ **one mean slot-hold time T** of added latency, and only while a drain is in progress. A parked run's wake re-takes its slot in the same line (`TaskQueueGovernor.parked`, `SignalManager.java:1025`), so it pays the same ≤ T. Today it pays the whole backlog: the probe's parked run resumed last, at index 5. After this package it resumes third, index 2 (Task 5's test).
- **What it costs peers:** up to C locks per queue per node for runs that are queued, not running. The node holds ≤ 2C locks per queue for recovery (≤ C running + ≤ C queued), never more. Today it holds every lock it can take (probe: 40 for C = 4).
- **Why C tickets, not fewer.** C is the maximum C2 allows. With C runs already queued, every slot a run frees is taken at once by a waiting recovered run, so the drain never idles while the poller sleeps between capacity passes. One ticket would make the drain rate depend on the spacing s.

Rejected alternatives: a reserved share of slots for recovery (it permanently takes capacity from fresh starts, even when nothing is recovering); ageing or alternate grants (both need a custom queue in place of `Semaphore`, and both re-open the R49 per-run lock reasoning in `take`).

### D-3. The wake-up: an event count, read before the pass, awaited after it

`CapacitySignal` is one per governor, package-private, with no Spring and no allocation per signal.
- **State:** a `volatile long epoch`, a `ReentrantLock` with one `Condition`, and a `volatile int waiters`.
- **`signal()`:** `epoch++`. If `waiters > 0`, it also takes the lock and calls `signalAll()`. A signal with no waiter costs one atomic increment.
- **`awaitChange(long seen, Duration max)`:** under the lock, it waits on the condition while `epoch == seen`, for at most `max`. It returns `true` if the epoch moved, `false` on timeout, and throws `InterruptedException` on interrupt.
- **When the governor signals:**
  - a workflow permit is given back, from `give` (a run ended, or a park chunk gave its slot) or from `SlotReservation.release()`;
  - a recovery ticket is returned;
  - the governor is closed (WP5's `shutdown()`, one added line).

**No missed wake-up.** The poller reads `seen = epoch()` *before* the pass. Any release during the pass increments the epoch, so the `awaitChange` after the pass returns at once. This is the standard event-count pattern.

**Bound.** After a deferring pass, the poller waits for `awaitChange`, then waits out the rest of `capacitySpacing` since the previous pass started (the default is `min(1 s, pollInterval)`, MD-4). Then it checks headroom: if the deferred queues have no headroom (a park chunk's give, for example, handed its permit straight to a waiter), it goes back to waiting **without a statement**. So the poller makes at most one capacity pass per spacing, and a spurious wake costs only an in-memory check. A pass that deferred nothing does not wait for capacity: it sleeps until the next full pass.

**Why not bump only on "useful" releases** (no waiters left, or a ticket). That is racy: `hasQueuedThreads()` is advisory, and a missed bump would delay recovery to the next full pass. Bumping on every give and filtering on headroom is exact and costs nothing.

### D-4. Pages: full passes as today; capacity passes resume at the first deferred row, sized to the headroom

- **Full pass** (the startup pass, then every `poll-interval`): from the first row, pages of 200, every page read, one `heldKeys` read per page with candidates, as today. That keeps today's guarantees: every orphan is seen within one `poll-interval`, including a row whose recorded task queue no registration names, and the deferred count covers the whole backlog this node can see. A 5,000-run backlog at C = 20 costs 25 page reads, 25 `heldKeys` reads and 40 × 3 adoption statements: **170 statements, where today's pass makes ≈ 15,026**.
- **Capacity pass:**
  - **Where it starts.** It resumes *after* `resumeAfter`, the keyset position just before the first row the previous pass deferred (`null` means the first row). The rows before that position were adopted, running here, held elsewhere, unregistered, factory-failing or terminal. None of them is waiting for this node's capacity, and the next full pass re-checks them.
  - **No statement without headroom.** It reads nothing when `Σ recoveryHeadroom(q)` over the deferred queues is 0.
  - **Page size.** Its page size is `capacityPageSize(h) = (int) min(200, max(20, 2L * h))` for headroom h. The factor 2 covers two nodes interleaving one backlog: about half the rows are held elsewhere. The floor of 20 keeps a one-slot headroom to one small statement. A full page continues to the next while headroom remains, and a short page ends the pass.
  - **So:** a pass with 2 free places reads ≤ 20 rows, not 200. A node holding 2,000 parked workflows never re-reads them on capacity passes, because its parked rows sit behind the cursor or are dropped as running here.
- **`recoveryHeadroom(q)`** = `(workflowSlots.hasQueuedThreads() ? 0 : workflowSlots.availablePermits()) + recoveryTickets`. It is `Integer.MAX_VALUE` for an unbounded queue and `0` after close. It is advisory and only sizes pages; `admitRecovery` remains the authority.
- **Statements per adopted run** in a steady drain: 3 + (1 page read + 1 `heldKeys`) / (runs admitted per capacity pass). At 5,000 runs, C = 20, T = 2 s and s = 1 s, about 10 tickets return per second, so ≈ 3.2. This is arithmetic, not measurement; WP12 scenario 2 measures it.

### D-5. Fairness between task queues

- Each queue has its own permits and its own tickets (D-1), so one queue's backlog never uses another's capacity.
- A pass visits rows in keyset (oldest-first) order across every queue and keeps going past a deferral, so a full queue A does not block admission on queue B.
- A capacity wake from any deferred queue triggers a pass that re-checks every queue deferred last time.
- Within a queue, recovered runs are admitted oldest first. The ≤ C queued among them reach the semaphore in thread-start order: the probe's five ran as `rec-0…rec-4` once and `rec-0, rec-1, rec-3, rec-4, rec-2` another time. That order is bounded and immaterial.

### D-6. Startup: what "application ready" means after this package

`StartupRecoveryRunner.run` starts the recovery loop first, then the timer poller, the janitor and the sweeper, as today, and returns:
- with `maestro.recovery.enabled=true` (the default): `startRecoveryPoller`, whose first pass is immediate on `maestro-recovery-poller-<svc>`;
- with it `false`: `startOneShotRecovery(registrations, recovery.pollInterval())`, an **ungated** pass on `maestro-startup-recovery-<svc>` that retries after `poll-interval` until one pass completes (MD-2), then exits.

**"Application ready" now means:** the context is refreshed, Maestro's loops are started, and the startup pass is *in progress*. It no longer means "every orphan has been adopted". That is safe:
- a signal for a not-yet-adopted workflow is persisted, and its replay finds it (self-recovery case 3);
- a query reaches only the owning node, and a not-yet-adopted run is owned by no node in the meantime (`docs/pitfalls.md` rule 5), exactly as for any run between owners;
- a fresh start queues FIFO with the recovered runs (D-2).

`docs/self-recovery.md:229` says the opposite ("workflows are recovered before the service starts accepting new traffic") and is corrected in Task 11. The ~2 s graceful-deploy recovery of `docs/operations.md` §2 is kept by the immediate first pass (traced, not tested; Task 11 labels it).

The one-shot pass is **not** in health (`recoveryPollerRunning` reads `"disabled"`, as today when `maestro.recovery.enabled=false`), and it does not occupy the recovery-poller slot, so `StartupRecoveryRunnerTest.skipsRecoveryPollerWhenDisabled` stays true unchanged. The `SignalSubscriptionRunner` Javadoc claims "recovery replay completes before the transport consumer starts" (`SignalSubscriptionRunner.java:42-45`). That is no longer true, and the same paragraph already says early delivery is safe. Task 7 corrects it.

### D-7. Unbounded queues keep today's path, allocation-free

With `default-concurrency: 0` and `default-activity-concurrency: 0` and no listed queues, the executor uses `TaskQueueGovernor.UNBOUNDED` (`WorkflowExecutor.java:250-252`). Then:
- `bucket(q)` is `null`;
- `admitRecovery` returns the `UNBOUNDED` singleton (no allocation);
- the claim and the release are no-ops;
- `recoveryHeadroom` is `Integer.MAX_VALUE`;
- no pass defers, so the poller never waits for capacity, and the signal is never bumped (no bucket ever gives).

The only difference from today is pass-first. An unbounded queue under a bounded governor (a listed queue elsewhere, no defaults) behaves the same. Pinned by `TaskQueueGovernorTest.anUnboundedQueueAdmitsWithTheSharedSingleton` (`assertSame`) and `RecoveryCapacityTest.anUnboundedNodeAdoptsEveryInstanceAsBefore`.

### D-8. Shutdown, the loop guard and health

- **A poller waiting for capacity leaves promptly.** `RecoveryPoller.stop()` interrupts the thread, and `awaitChange` throws `InterruptedException`. The loop's existing `InterruptedException` arm exits (WP4 D-5). Pinned by `RecoveryPollerCapacityTest.stopWhileWaitingForCapacityEndsTheLoopPromptly`: `stop()` returns within 1 s and `isRecoveryPollerRunning()` is `false`.
- **The governor closed by WP5:** `admitRecovery` returns `null`, `awaitChange` is woken by the close's `signal()`, and the poller, seeing `recoveryHeadroom == 0`, waits only for its full-pass deadline or `stop()`. It never spins.
- **A queued recovered run at shutdown:** it waits in `take`, so WP5's poison permit reaches it like any waiter. It throws `ExecutorShutdownException`, its claim's `finally` returns the ticket, and `executeWorkflow`'s `finally` releases its lock. There are ≤ C such runs per queue, not the whole backlog, so lock release after a graceful stop is bounded as well (Issue 29's real cost, Audit A §3.6(b)).
- **The loop guard (WP4 D-10).**
  - Every pass, full or capacity, sits inside the iteration `try` and calls `guard.succeeded()` after returning. The broad arm calls `guard.failed(t)` and, as today, the next attempt is a **full** pass after `pollInterval`. A failure discards the resume cursor.
  - The capacity wait sits inside the same `try`, so its `InterruptedException` reaches the exit arm.
  - `admitRecovery` raises no `Error`.
  - A `MaestroControlFlowError` from a pass still ends the loop through the guard.
  - The one-shot pass uses its own `BackgroundLoopGuard(RECOVERY_POLLER, retryInterval, () -> !running, observer)`, as WP4 D-10 asked, so its failures log the same way and count on `maestro.loop.errors{loop=recovery-poller}`.
- **Health.** `recoveryPollerRunning` is unchanged in meaning. The `"starting"` window shrinks because the runner no longer adopts first. No new health detail (MD-6).

### D-9. Issue 36: opt-in limits on the test kit (C3)

- **The builder:** `TestWorkflowEnvironment.builder().defaultConcurrency(w, a).taskQueueConcurrency(q, w, a).build()`. The names mirror `maestro.worker.default-concurrency` / `default-activity-concurrency` and `maestro.worker.task-queues[].concurrency`.
- **Validation in `build()`** mirrors the starter (`MaestroAutoConfiguration.java:156-169`, `:193-214`), with `IllegalArgumentException`:
  - `(0, 0)` is unbounded;
  - one `0`, or a negative value, fails naming both arguments;
  - a queue entry needs a non-blank name and both limits ≥ 1;
  - a queue named twice fails.
- **Default:** unbounded, which is today's kit (C3: opt-in this cycle). Each `Node` passes the limits to `WorkflowExecutor.Builder#taskQueueLimits` / `#defaultTaskQueueLimits` (`TestWorkflowEnvironment.java:578-585`).
- **`@MaestroTest`** gains `defaultConcurrency` and `defaultActivityConcurrency` (MD-5). Their default `0`/`0` is unbounded, so no sentinel is needed. `MaestroTestExtension.beforeEach` (`:29-32`) reads the annotation from the test class or its nearest enclosing class (`@Nested`), and calls the builder when either attribute is non-zero.
- **`recover()` stays ungated:** it calls `recoverWorkflows`, as today. With limits set, every re-adopted run beyond a queue's bound waits for its slot on its own thread, as on a node whose recovery poller is disabled. `recover()` keeps returning "every workflow re-adopted", which is what crash tests assert. Making the kit's recovery gated as well is filed with C3's follow-up (Issue 72).
- **Why this is safe only after WP5.** `recover()` shuts the retiring node down synchronously (`:413`). Without WP5's poison permit, a run queued for a slot on the retiring node would hold that shutdown for up to 30 s (Audit A §Issue 36(c)). WP5 merges first (Before you start).

## Decisions for the maintainer

| # | Question | Recommendation | Alternative and its cost |
|---|---|---|---|
| MD-1 | Is the public `WorkflowExecutor.recoverWorkflows(Map)` gated too? | **No.** It stays the ungated "adopt everything now" call (its Javadoc says so and points to `startRecoveryPoller` for gated recovery). It is what the poller-disabled startup pass needs (spec: "ungated when the recovery poller is disabled"). The kit and the integration harness call it. About 40 test call sites keep their meaning. Only the poller's passes are gated. | Gate it: a non-Spring embedder that calls it at startup would stop hoarding. But the kit's `recover()` and `MaestroEngineHarness.recover()` would adopt only up to capacity and would need loops, `recoverWorkflows` could no longer serve as the ungated one-shot, and every bounded caller's count changes. |
| MD-2 | The one-shot startup pass (`maestro.recovery.enabled=false`) fails, for example on a database blip. | **Retry** after `maestro.recovery.poll-interval` until one pass completes, then exit; each failure goes through the loop guard (`ERROR`, then reminders). Today the same failure fails application startup, which at least is loud. Off the runner thread, with no retry and no poller behind it, every orphan would stay stranded until the next restart, silently. | (a) One attempt, logged `ERROR`: simpler, but it strands everything on a blip. (b) Keep this pass synchronous on the runner thread when the poller is disabled: restores fail-fast, but it contradicts the spec row ("off the `ApplicationRunner` thread"). |
| MD-3 | Observability for "recovery waiting on capacity". | A **gauge** `maestro.recovery.deferred` (no tags; the deferred count of this node's latest full pass), in a separate `feat(observe)` commit (Task 9) that the maintainer can move to WP11, as WP4 did with `maestro.loop.errors`. It reads `0` on a healthy fleet and the visible backlog during a drain. A value that stays high over several full passes means this node's queues cannot keep up with recovery. The `INFO` line (surface table) says the same in logs. | (a) Logs only: no meter change this package, which is the spec's §4 Lane C rule taken literally. Alerting then needs log parsing. (b) A counter of deferrals: it re-counts the same instance every pass, so its rate means nothing. |
| MD-4 | The capacity re-poll spacing. | **Fixed** at `min(1 s, maestro.recovery.poll-interval)`, with no property. It bounds capacity passes to ≤ 1/s per node: at most 2 statements a second while a drain lasts, and none when nothing is free (D-3). | A property such as `maestro.recovery.capacity-repoll-interval`: new surface and validation, for a knob whose effect C tickets already hide (D-2, "Why C tickets"). |
| MD-5 | Should `@MaestroTest` take the limits too? | **Yes:** `@MaestroTest(defaultConcurrency = 20, defaultActivityConcurrency = 20)`. Issue 36 asked for an annotation route, and without one, `@MaestroTest` users must drop the annotation to opt in. `0`/`0` keeps today's unbounded kit. | Builder only: less surface, but the common JUnit entry point cannot reproduce pitfalls rule 11. |
| MD-6 | Readiness no longer waits for the startup pass, and no health detail says it is still running. | **Accept**, document it (D-6), and file Issue 73 for a health detail (`startupRecovery: "running"`/`"complete"`). Holding readiness would re-couple pod readiness to the backlog, which is the harm 3.1 names. | Publish `ReadinessState.REFUSING_TRAFFIC` until the first pass returns: the gated first pass is short (≤ 2C adoptions per queue plus page reads), so readiness would wait seconds, not minutes. But it ties Maestro to Spring's availability state, and a store fault during the pass would then keep the pod out of service. |

## Global Constraints

- **Spec anti-goals (§2), in effect:**
  - **No run fails or compensates where it would stand down or be abandoned.** Admission writes nothing. A deferred instance is not touched. A queued run stopped by shutdown stands down recoverable, as today (`ExecutorShutdownException` arm, `:2502-2518`).
  - **Replay:** no event, history format, SPI or migration changes. A mixed fleet mixes gated and ungated nodes. An old node still hoards, a new node never does, and locks and fencing keep them correct together.
  - **An existing test is changed only as a named step.** The complete list is under "Existing tests changed". An implementer who meets any other failing test that encodes old recovery behaviour stops and asks.
- **Code rules.** `maestro-core` never imports Spring. Jackson 3 only. JSpecify `@Nullable` on every nullable parameter and return. Javadoc on every public or protected member and on `CapacitySignal`, `SlotReservation`, `OneShotRecovery` and the changed `RecoveryPoller`, each with a thread-safety note. No Lombok.
- **Control-flow `Error`s** (`CLAUDE.md` "Exceptions"):
  - every new `catch (Throwable)` (the W9 cleanup, `OneShotRecovery`'s loop) checks `instanceof MaestroControlFlowError` and rethrows it after cleanup, or hands it to the guard, which rethrows it;
  - `StoreUnavailableException` from an adoption still propagates out of the pass (W7), and the reservation is released in a `finally`, never in a `catch`;
  - no new `catch` inside workflow, activity or saga code.
- **Exactly-once.** No path may release a reservation by calling `releaseWorkflowSlot(workflowId)` before the claim. That method is the run's own release, and before the claim it could free a concurrently launched run's slot (D-1).
- **No log line or meter tag** carries a workflow id, a run id or a payload. The deferral line carries queue names and counts only.
- **Branch and worktree.**
  - Branch `fix/recovery-capacity`, cut from `origin/main` after WP5 has merged (Lane A order: WP4 → WP5 → WP6), in `.claude/worktrees/wp6-recovery-capacity`.
  - Before the first edit, run `git -C <worktree> status` and `git status`.
  - Never `cd` into the main checkout. This plan lives on `docs/engine-hardening-cycle`; read it from the main checkout's absolute path.
- **Gradle.** Always `./gradlew --no-watch-fs`, from the worktree root. Never two Gradle runs on one module at once. No `git stash`.
- **Commits.**
  - Commit at every checkpoint named below.
  - Chain git steps with `&&`. Pass messages as `-m "$(cat <<'EOF' … EOF)"`.
  - Never run git-mutating commands in parallel.
  - Every message ends with the attribution line your own session supplies.
- **Long runs** are coordinator gates: `./gradlew --no-watch-fs build`, the whole `:maestro-integration-tests:test`, `e2eTest`, chaos and the soak. The coordinator launches them detached, with a log whose header records `pwd`, `git rev-parse HEAD` and a timestamp. A module run named in a step that takes longer than about 5 minutes is handed to the coordinator.
- **Red tests.**
  - Each new red test is run against the unfixed code first and fails on a positive fact: a count, an index, a boolean naming what did not happen. It never fails on a `ConditionTimeoutException`. Use "value within" helpers (poll until the wanted value or the deadline, then return the last value read) and `CountDownLatch.await(bound)` booleans.
  - Paste the FAIL lines into the commit body.
  - Each fix gets at least one repeatable mutation check, reverted in the same step (`git checkout -- <file>`; `git diff` empty before moving on).
- **No sleeps** where a latch, an awaited condition or the `CapacitySignal` works. Activities in these tests block on latches or semaphores the test releases. The capacity spacing in tests is set through the seam (`startRecoveryPoller(…, capacitySpacing)`), never by waiting out 1 s.
- **Documentation** states only what a test shows, or it is labelled "(traced, not tested)" or "(arithmetic)".
- **New issues** start at **72** (64–68 are taken; 69–71 are reserved for WP5). Check with `grep -n '^### Issue' docs/open-issues.md | tail -3` at implementation time, and take the next free numbers if WP5 used fewer.
- **PR gates (spec §6):** Build & Test; nightly E2E; chaos PR-gate ×3; **the 2 h soak**. Commands and pass criteria are in Task 11. The maintainer merges.

## Review Focus

1. **Hoarding is gone and the spread is real.** Two nodes, one backlog: each locks ≤ 2C per queue, and both execute runs. → Task 2 `aNodeLocksOnlyWhatItCanRunOrQueueAndThePeerAdoptsTheRest`; Task 8 `MultiNodeRecoveryCapacityIT`.
2. **Exactly-once on every window, W1–W9, Errors included.** After every failed or skipped adoption the queue's slots and tickets are whole, and the lock is released where one was taken. → Task 2 `aDeferredOrFailedAdoptionGivesItsReservationBack` (nine cases), Task 1 `aReservationIsReleasedOrClaimedExactlyOnce`, Task 3.
3. **No barging, and no starvation.** A reservation never overtakes a waiting run, and recovered runs complete while a fresh-start stream keeps the queue non-empty. → Task 1 `admitRecoveryNeverOvertakesAWaitingRun`; Task 6 `recoveredRunsProgressUnderASustainedStreamOfFreshStarts`, whose mutation (no tickets) goes red.
4. **No lost wake-up, no busy loop.** A release during a pass is seen. A capacity pass runs at most once per spacing, and a wake with no headroom makes no statement. → Task 1 `aSignalDuringThePassIsSeenByTheWaitAfterIt`; Task 5 `capacityPassesAreSpacedAndFreeWhenNothingIsFree`.
5. **Drain speed.** A backlog larger than the bound drains without waiting for `poll-interval`. → Task 5 `aBacklogLargerThanTheBoundDrainsWithoutWaitingForThePollInterval`.
6. **Startup.** The runner returns while the pass is still reading. With the poller disabled the pass is ungated and retried. → Task 7.
7. **Shutdown.** A poller waiting for capacity, and a queued recovered run, both leave promptly; the run returns its ticket. → Task 5 `stopWhileWaitingForCapacityEndsTheLoopPromptly`; Task 1 `aQueuedRunInterruptedWhileWaitingReturnsItsTicket`; Task 1 `aClosedGovernorAdmitsNothingAndWakesTheWaiter`.
8. **Unbounded stays as before.** → D-7's two tests.

## Existing tests changed (named; the only allowed edits)

| File | Edit | Task |
|---|---|---|
| `maestro-core/src/test/java/io/b2mash/maestro/core/engine/WorkflowExecutorConcurrencyTest.java` | Only the Javadoc of `recoveryOfMoreRunsThanTheDefaultBoundReturnsWithoutWaitingForSlots` (`:297-304`) changes: "Recovery launches … (at startup: the `ApplicationRunner`) … (Issue 30 is that they still hold their instance lock while they queue)" becomes "`recoverWorkflows` is the ungated pass (the startup pass when the recovery poller is disabled): it adopts every run without waiting for slots, and the runs beyond the bound queue on their own threads. The recovery poller's passes are gated instead: `RecoveryCapacityTest`." No statement changes. | 2 |
| `maestro-spring-boot-starter/src/test/java/io/b2mash/maestro/spring/config/StartupRecoveryRunnerTest.java` | Only the class Javadoc (`:18-24`) gains: "The runner adopts nothing itself: recovery runs on the recovery poller's thread, or on a one-shot thread when the poller is disabled." The two existing tests are unchanged and must stay green. | 7 |

**Files that only gain members** (no line removed): `TaskQueueGovernorTest.java` (Task 1), `AdoptionLockLeakTest.java` (Task 3), `MaestroObservabilityAutoConfigurationTest.java` or `MaestroEngineGaugesTest.java`, whichever holds the gauge tests (Task 9), `TestWorkflowEnvironmentTest.java` (none; Task 10 adds a new class).

**Expected to stay green unchanged, and why** (checked at planning time against the new design):
- `BackgroundLoopStartShutdownRaceTest.recoveryPoller`: pass-first adds one poll before `stop()`. The test snapshots after `shutdown()` returns, and `stop()` still joins.
- `BackgroundLoopErrorTest.recoveryPoller_anErrorFromAPass_isContainedAndTheNextPassRuns`: call 1 now happens at start instead of after 50 ms. Call 2 follows one `pollInterval` later (a failed pass waits for a full pass, D-8).
- `WorkflowExecutorTest.recoveryPollerPicksUpAfterForeignLockRelease`: the immediate pass finds the lock held elsewhere, as the delayed one did.
- `AdoptionLockLeakTest`, `RecoveryPassIsolationTest`, `RecoveryFactoryLinkageErrorTest`, every `MultiNode*IT`, `EnginePostgresRecoveryIT`, `StoreOutageMidStepIT`: their executors are unbounded (`MaestroEngineHarness` passes `Map.of()` and no defaults), so admission is `UNBOUNDED` (D-7).

**Reviewer check, statement-level:**
1. `BASE=$(git merge-base origin/main HEAD); git diff --name-only --diff-filter=M "$BASE" HEAD -- '*/src/test/*'` prints exactly the two files above plus the member-only files.
2. `git diff --name-only --diff-filter=D "$BASE" HEAD` prints nothing.
3. For the two named files, every removed line (`^-[^-]`) is a Javadoc line (it starts with `*` or `/**` after the indent). For the member-only files, no line is removed.

## Before you start

```bash
git fetch origin && \
git log origin/main --oneline -1 --grep='loop-liveness\|WP4' && \
git log origin/main --oneline -1 --grep='shutdown\|WP5\|Issue 29' && \
git worktree add -b fix/recovery-capacity \
    .claude/worktrees/wp6-recovery-capacity origin/main && \
git -C .claude/worktrees/wp6-recovery-capacity status
```

Both logs must print a merge. If either prints nothing, stop: WP6 depends on WP4 (the loop guard around the pass) and WP5 (the governor's close and poison permit).

**WP5 seams this plan consumes.** Check them in the new worktree and record the actual names in Task 1's commit body:
- **S1.** A governor-wide *closed* state readable inside `TaskQueueGovernor` (WP5's `shutdown()` sets it). `admitRecovery` and `recoveryHeadroom` read it.
- **S2.** WP5's `TaskQueueGovernor.shutdown()` is where Task 1 adds `capacitySignal.signal()`, after the poison permits are released.
- **S3.** WP5's poison reaches waiters through `take`/`acquire`. Task 1's claim for a `QUEUED` reservation calls `take` inside a `try`/`finally` that returns the ticket, so a poisoned waiter returns its ticket without WP5 changing anything.
- **S4.** WP5 rewrites `WorkflowExecutor.shutdown()` around one deadline. Task 7 stops the one-shot recovery thread next to the recovery poller, with the same deadline helper WP5 introduced.
- **S5.** WP5 may change `RecoveryPoller.stop()` (its join). Task 5 rewrites `pollLoop` only and keeps WP5's `stop()`.

**The two plans both touch `TaskQueueGovernor`.** WP5 adds the closed flag and poison release, WP6 the reservation, tickets and signal. The hunks are disjoint, but `take`, `give` and `acquire` are touched by both, which is why WP5 goes first. If the coordinator ever reverses the order, S1–S3 become WP5's work to add on top of this plan's governor.

**WP11 (Lane C)** changes `record Bucket` to `Bucket(String name, TaskQueueLimits limits, Semaphore workflowSlots, Semaphore activitySlots)` and adds `workflowSlotsInUse`. If it merged first, keep its `limits` component and read the ticket count from `limits.workflowConcurrency()`. A `RESERVED` permit counts as in use in its gauge, which is correct: the slot is spoken for. A `QUEUED` ticket does not, which is also correct: no slot is held.

## File structure

| File | Responsibility |
|---|---|
| `maestro-core/.../engine/TaskQueueGovernor.java` | `admitRecovery`, `SlotReservation`, the claim overload, tickets, `recoveryHeadroom`, signalling on give, ticket return and close |
| `maestro-core/.../engine/CapacitySignal.java` (new) | D-3 |
| `maestro-core/.../engine/WorkflowExecutor.java` | Gated `recoveryPass`, `capacityPass`, `RecoveryPass` fields, the reservation threaded through `resumeWorkflow` → `launchWorkflow` → `executeWorkflow`, W9 cleanup and `beforeRunBody`, `recoveryDeferredCount`, `startOneShotRecovery`, poller wiring |
| `maestro-core/.../engine/RecoveryPoller.java` | Pass first; full and capacity passes; the capacity wait |
| `maestro-core/.../engine/OneShotRecovery.java` (new) | The ungated, retried, stoppable startup pass |
| `maestro-spring-boot-starter/.../config/StartupRecoveryRunner.java` | Start only, never adopt |
| `maestro-spring-boot-starter/.../config/SignalSubscriptionRunner.java` | Javadoc correction (D-6) |
| `maestro-spring-boot-starter/.../observe/MaestroEngineGauges.java` | `maestro.recovery.deferred` (MD-3) |
| `maestro-test/.../TestWorkflowEnvironment.java`, `MaestroTest.java`, `MaestroTestExtension.java` | Issue 36 |
| New tests | `TaskQueueGovernorTest` (+), `CapacitySignalTest`, `RecoveryCapacityTest`, `RecoveryPollerCapacityTest`, `RecoveryStarvationTest`, `OneShotRecoveryTest`, `AdoptionLockLeakTest` (+), `StartupRecoveryRunnerTest` (+), `MultiNodeRecoveryCapacityIT`, `TestWorkflowEnvironmentLimitsTest`, `MaestroTestLimitsTest`, gauge test (+) |
| Docs | `docs/configuration.md`, `docs/operations.md` §2, §13, `docs/self-recovery.md`, `docs/pitfalls.md` rule 11, `docs/testing.md`, `docs/observability.md`, `docs/release-notes.md`, `docs/open-issues.md`, `CLAUDE.md` |

Core test fixtures shared by Tasks 2, 3, 5, 6 and 8 go in one new package-private file, `maestro-core/src/test/java/io/b2mash/maestro/core/engine/RecoveryCapacityFixtures.java`:
- `SharedLock`: a map-backed `DistributedLock` with a real `heldKeys` (keys present in the map), exact per-key exclusion and `heldCount()`;
- `seedRecoverable(store, workflowId, taskQueue)`;
- `BlockingWork`: an `@Activity` whose implementation counts entries, records the workflow id in an ordered list, and blocks on a `Semaphore` the test releases one permit at a time;
- `WorkWorkflow`: one `BlockingWork` call;
- `valueWithin(Supplier<T>, T wanted, Duration)`: returns the last value read.

The planning probe's `SharedLock` and `CountingStore` are the model.

## Pre-flight: task pairs that share a file or an interface

| Pair | Shared | Order and rule |
|---|---|---|
| 1 → 2, 4, 5, 6 | `admitRecovery`, `SlotReservation`, the claim, `recoveryHeadroom`, `CapacitySignal` | Task 1 first. Later tasks consume D-1 and D-3 exactly. A signature change goes back to Task 1 and its tests. |
| 2 → 3 | `WorkflowExecutor.launchWorkflow` (Task 2 threads the reservation through it; Task 3 edits the lambda at `:2305-2316`) | Sequential. Task 3's cleanup releases the reservation Task 2 introduced. |
| 2 → 4 → 5 | `RecoveryPass` (fields), `recoveryPass`/`capacityPass` (Task 4 adds the capacity pass on Task 2's gated page loop); `RecoveryPoller` consumes both | Sequential. |
| 2, 4, 7, 9 | `WorkflowExecutor.java` (recovery section `:717-866`; `startOneShotRecovery` near `startRecoveryPoller` `:1571`; `shutdown()` after WP5; `recoveryDeferredCount` near the accessors `:2020-2075`) | Sequential. The hunks do not overlap. |
| 5 ↔ WP5 | `RecoveryPoller.stop()` | Task 5 keeps WP5's `stop()` verbatim (S5). |
| 7 ↔ WP5 | `WorkflowExecutor.shutdown()` | Task 7 adds one stop next to the recovery poller's, on WP5's deadline (S4). |
| 7 ↔ WP8 | `StartupRecoveryRunner.java` | WP8 (later) adds Issue 39's advisory query there. Task 7's Javadoc says the runner must stay start-only, so WP8 runs its query off the runner thread too. Send this note to WP8's planner. |
| 2, 9 | `recoveryDeferredCount()` | Task 2 produces it; Task 9 consumes it. |
| 10 | `maestro-test` only | Independent of 1–9 except that it runs after WP5 (D-9). Any order after Task 1. |
| 11 | Docs | Last. |

---

### Task 1: The governor admits recovery without blocking; `CapacitySignal`

**Files:**
- Create: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/CapacitySignal.java`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/TaskQueueGovernor.java`
  - `Bucket` gains `AtomicInteger recoveryTickets` and `int workflowConcurrency`, or WP11's `limits`; see Before you start;
  - the governor owns one `CapacitySignal`; `give` (`:364-368`) becomes an instance method, or takes the signal, and calls `signal()` after the `release()`;
  - WP5's `shutdown()` calls `signal()` last (S2);
  - add `admitRecovery`, the claim overload, `recoveryHeadroom`, `capacitySignal()` and `SlotReservation` (D-1, D-4);
  - the class Javadoc gains `<h2>Recovery admission</h2>`: D-1's "never blocks, never barges", D-2's first bullet in two sentences, and "tickets are per queue per node".
- Test: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/TaskQueueGovernorTest.java` (new methods), `maestro-core/src/test/java/io/b2mash/maestro/core/engine/CapacitySignalTest.java` (new)

**Interfaces:**
- Consumes: WP5's S1–S3.
- Produces: the package-private surface listed at the top, exactly as D-1, D-3 and D-4 state.

- [ ] **Step 1: Write the tests.** Fixture: `var g = new TaskQueueGovernor(Map.of("q", new TaskQueueLimits(2, 2)))`.

```java
@Test void admitRecoveryReservesAFreeSlotWithoutBlocking() {
    var r = g.admitRecovery("q");
    assertEquals(SlotReservation.Kind.RESERVED, r.kind());
    assertEquals(OptionalInt.of(1), g.availableWorkflowSlots("q"));
}
@Test void admitRecoveryQueuesAtMostTheQueuesConcurrencyThenDefers() {
    var kinds = Stream.generate(() -> g.admitRecovery("q")).limit(5)
            .map(r -> r == null ? "DEFERRED" : r.kind().name()).toList();
    assertEquals(List.of("RESERVED", "RESERVED", "QUEUED", "QUEUED", "DEFERRED"), kinds);
}
@Test void admitRecoveryNeverOvertakesAWaitingRun() throws Exception {
    g.acquireWorkflowSlot("q", "a"); g.acquireWorkflowSlot("q", "b");          // both slots held
    var waiterGotIt = new CountDownLatch(1);
    var waiter = Thread.ofVirtual().start(() -> { g.acquireWorkflowSlot("q", "c"); waiterGotIt.countDown(); });
    await().atMost(Duration.ofSeconds(5)).until(() -> waiter.getState() == Thread.State.WAITING);  // c is queued
    g.releaseWorkflowSlot("a");                     // a permit is free for an instant, but c is first in line
    var r = g.admitRecovery("q");                   // same thread, before c has been scheduled
    assertEquals(SlotReservation.Kind.QUEUED, r.kind(), "never RESERVED past a waiting run");
    assertTrue(waiterGotIt.await(5, SECONDS), "c got the permit");
}
@Test void aReservationIsReleasedOrClaimedExactlyOnce() {
    var r = g.admitRecovery("q");
    r.release(); r.release();
    assertEquals(OptionalInt.of(2), g.availableWorkflowSlots("q"), "one permit given back, not two");
    g.acquireWorkflowSlot("q", "wf", r);                                        // claim after release: falls back
    assertEquals(OptionalInt.of(1), g.availableWorkflowSlots("q"));
    var s = g.admitRecovery("q");
    g.acquireWorkflowSlot("q", "wf2", s);
    s.release();                                                                // release after claim: no-op
    assertEquals(OptionalInt.of(0), g.availableWorkflowSlots("q"));
}
@Test void aClaimedReservedSlotIsTheRunsSlot() {
    var r = g.admitRecovery("q");
    g.acquireWorkflowSlot("q", "wf", r);
    assertEquals(OptionalInt.of(1), g.availableWorkflowSlots("q"), "no second permit taken");
    g.releaseWorkflowSlot("wf");
    assertEquals(OptionalInt.of(2), g.availableWorkflowSlots("q"));
}
@Test void aQueuedRunTakesItsTurnAndReturnsItsTicket() throws Exception {
    g.acquireWorkflowSlot("q", "a"); g.acquireWorkflowSlot("q", "b");
    var r = g.admitRecovery("q");                                               // QUEUED
    assertEquals(1, g.recoveryHeadroom("q"));
    var got = new CountDownLatch(1);
    Thread.ofVirtual().start(() -> { g.acquireWorkflowSlot("q", "rec", r); got.countDown(); });
    g.releaseWorkflowSlot("a");
    assertTrue(got.await(5, SECONDS));
    assertEquals(2, g.recoveryHeadroom("q"), "the ticket is back once the run holds its slot");
}
@Test void aQueuedRunInterruptedWhileWaitingReturnsItsTicket() throws Exception {
    // as above, but interrupt the claiming thread instead of releasing "a";
    // assert it threw ExecutorShutdownException and recoveryHeadroom("q") == 2
}
@Test void aClosedGovernorAdmitsNothingAndWakesTheWaiter() throws Exception {
    var seen = g.capacitySignal().epoch();
    var woke = new CompletableFuture<Boolean>();
    Thread.ofVirtual().start(() -> { try { woke.complete(g.capacitySignal().awaitChange(seen, Duration.ofMinutes(1))); }
                                     catch (InterruptedException e) { woke.complete(false); } });
    g.shutdown();                                                               // WP5's method (S1, S2)
    assertTrue(woke.get(5, SECONDS));
    assertNull(g.admitRecovery("q"));
    assertEquals(0, g.recoveryHeadroom("q"));
}
@Test void anUnboundedQueueAdmitsWithTheSharedSingleton() {
    assertSame(SlotReservation.UNBOUNDED, TaskQueueGovernor.UNBOUNDED.admitRecovery("x"));
    assertSame(SlotReservation.UNBOUNDED, TaskQueueGovernor.UNBOUNDED.admitRecovery("y"));
    assertEquals(Integer.MAX_VALUE, TaskQueueGovernor.UNBOUNDED.recoveryHeadroom("x"));
}
@Test void aDefaultBoundedQueueHasItsOwnTickets() {
    var d = new TaskQueueGovernor(Map.of(), new TaskQueueLimits(1, 1));
    assertEquals(2, d.recoveryHeadroom("a"));
    d.admitRecovery("a"); d.admitRecovery("a");
    assertEquals(0, d.recoveryHeadroom("a"));
    assertEquals(2, d.recoveryHeadroom("b"), "queue b has separate pools and tickets");
}
@Test void aGiveSignalsCapacity() {
    var seen = g.capacitySignal().epoch();
    g.acquireWorkflowSlot("q", "a"); g.releaseWorkflowSlot("a");
    assertNotEquals(seen, g.capacitySignal().epoch());
}
```

  `CapacitySignalTest`:

```java
@Test void aSignalDuringThePassIsSeenByTheWaitAfterIt() throws Exception {
    var s = new CapacitySignal();
    var seen = s.epoch();
    s.signal();                                                // "a release during the pass"
    assertTrue(s.awaitChange(seen, Duration.ofMinutes(1)));    // returns at once
}
@Test void awaitTimesOutWithoutASignal() throws Exception {
    var s = new CapacitySignal();
    assertFalse(s.awaitChange(s.epoch(), Duration.ofMillis(50)));
}
@Test void aSignalWakesAParkedWaiter() throws Exception { /* waiter thread + latch; signal(); waiter returns true within 5 s */ }
@Test void anInterruptEndsTheWait() { /* waiter thread interrupted -> InterruptedException recorded */ }
```

- [ ] **Step 2: Run.** `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.TaskQueueGovernorTest' --tests 'io.b2mash.maestro.core.engine.CapacitySignalTest'`. Expected: compilation fails on the new API. That is not a red. This package's reds are in Tasks 2, 3, 5 and 7, which run against the unfixed engine. Commit nothing yet.
- [ ] **Step 3: Implement** D-1, D-3 and D-4 exactly. `SlotReservation` holds its `Bucket` (`null` for `UNBOUNDED`), its `Kind` and an `AtomicBoolean done`. Keep `take`'s R49 lock structure untouched: the claim calls `take`, never re-implements it.
- [ ] **Step 4: Run green.** Same command, then the whole of `TaskQueueGovernorTest`. Expected: PASS.
- [ ] **Step 5: Mutation checks** (each reverted with `git checkout -- <file>`; `git diff` must then be empty for that file):
  - `tryAcquire(0, SECONDS)` → `tryAcquire()` (the barging form): `admitRecoveryNeverOvertakesAWaitingRun` goes red (`RESERVED`).
  - Remove the `done` CAS from `release()`: `aReservationIsReleasedOrClaimedExactlyOnce` goes red (`3`).
  - Move the ticket return out of the claim's `finally` into its normal path: `aQueuedRunInterruptedWhileWaitingReturnsItsTicket` goes red (`1`).
  - Drop `signal()` from `give`: `aGiveSignalsCapacity` goes red.
- [ ] **Step 6: Commit:** `feat(engine): TaskQueueGovernor admits recovery without blocking, with C2's bounded queue and a capacity signal (Issue 30)`. Put WP5's seam names (S1–S3) in the body.

---

### Task 2: The poller's pass is gated, and every failed adoption gives its reservation back (3.1)

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java`:
  - `enum RecoveryGate { GATED, UNGATED }`;
  - `RecoveryPass(int scanned, int adopted, Map<String, Integer> skippedByType, Map<String, Integer> deferredByQueue, @Nullable RecoveryCursor resumeAfter)` with `int deferred()`;
  - `recoveryPass(Map)` delegates with `GATED` and full paging. `recoverWorkflows(Map)` delegates with `UNGATED` and returns `.adopted()`; its Javadoc gains MD-1's sentence;
  - `recoverPage` follows D-1's order. Under `UNGATED`, step 4 is `SlotReservation.UNBOUNDED`, so the run claims nothing and takes its slot on its own thread as today. It records `resumeAfter` at the first deferral: the cursor of the page row before it, or the page's `after` when it is the page's first row. It ends the pass when the poller thread's interrupt flag is set;
  - a package-private `resumeWorkflow(WorkflowInstance, Supplier<Object>, Method, @Nullable SlotReservation)`; the public `resumeWorkflow` passes `null`;
  - `launchWorkflow` gains `@Nullable SlotReservation reservation` and passes it into the lambda. `executeWorkflow` gains it, and `:2410` becomes `taskQueueGovernor.acquireWorkflowSlot(ctx.taskQueue(), ctx.workflowId(), reservation)`;
  - `startWorkflow` (`:667`) and `retryWorkflow` (`:1490`) pass `null`;
  - `private volatile int recoveryDeferred` is set at the end of every **full** gated pass, and `public int recoveryDeferredCount()` returns it;
  - the full-pass log lines (surface table).
- Modify: `WorkflowExecutorConcurrencyTest.java` (named Javadoc edit).
- Create: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/RecoveryCapacityFixtures.java`, `maestro-core/src/test/java/io/b2mash/maestro/core/engine/RecoveryCapacityTest.java`

**Interfaces:**
- Consumes: Task 1.
- Produces: the gated `recoveryPass`, the `RecoveryPass` fields, `recoveryDeferredCount()`, and the reservation-taking `resumeWorkflow`/`launchWorkflow`/`executeWorkflow`.

- [ ] **Step 1: Write the red tests** in `RecoveryCapacityTest`. Fixture: one `VersionedInMemoryStore` and one `SharedLock` shared by nodes built with `defaultTaskQueueLimits(new TaskQueueLimits(c, c))` and `shutdownTimeout(2 s)`. Activities block on the fixture's semaphore.

```java
@Test void aNodeLocksOnlyWhatItCanRunOrQueueAndThePeerAdoptsTheRest() throws Exception {
    for (var i = 0; i < 6; i++) seedRecoverable(store, "wf-" + i, "orders");
    var a = node(2); var b = node(2);
    var passA = a.recoveryPass(registrations(a));
    var locksAfterA = lock.heldCount();
    var passB = b.recoveryPass(registrations(b));
    assertAll(
            () -> assertEquals(4, locksAfterA, "A holds a lock only for what it runs or queues"),
            () -> assertEquals(4, passA.adopted(), "A adopts its 2 slots plus 2 queued (C2)"),
            () -> assertEquals(2, passA.deferred()),
            () -> assertEquals(2, passB.adopted(), "B adopts what A left"),
            () -> assertEquals(2, a.recoveryDeferredCount()));
    work.release(6);
    assertEquals(Set.of("A", "B"), valueWithin(work::nodesThatRan, Set.of("A", "B"), Duration.ofSeconds(10)));
}
@Test void anUnboundedNodeAdoptsEveryInstanceAsBefore() throws Exception {
    // node built with no limits: recoveryPass adopts 6 of 6, deferred() == 0, heldCount() == 6
}
@Test void recoverWorkflowsStaysUngated() throws Exception {
    // c = 1, 5 seeded: recoverWorkflows returns 5 and heldCount() == 5 (MD-1); the same as
    // WorkflowExecutorConcurrencyTest.recoveryOfMoreRunsThanTheDefaultBoundReturnsWithoutWaitingForSlots
}
@ParameterizedTest @EnumSource(Window.class)
// SHUTTING_DOWN, RUNNING_HERE, LOCK_HELD_ELSEWHERE, LOCK_CALLBACK_ERROR, TURNED_TERMINAL,
// CAS_LOST, STORE_FAULT, FACTORY_THROWS, ERROR_AFTER_ADMISSION
void aDeferredOrFailedAdoptionGivesItsReservationBack(Window window) throws Exception {
    var a = node(1);                                  // one slot, one ticket
    seedRecoverable(store, "wf-1", "orders");
    window.arm(this, a);                              // injects the window's fault for wf-1 only
    try { a.recoveryPass(registrations(a)); } catch (Throwable expectedForThrowingWindows) { }
    assertAll(
            () -> assertEquals(OptionalInt.of(1), a.taskQueueGovernor().availableWorkflowSlots("orders")),
            () -> assertEquals(2, a.taskQueueGovernor().recoveryHeadroom("orders"), "slot + ticket whole"),
            () -> assertFalse(lock.isHeld("maestro:lock:workflow:wf-1"), "no lock left behind"));
}
```

  How each `Window` is armed. Each case says which W-row it covers.
  - `SHUTTING_DOWN` (W1): `afterAdmissionHook` is unusable here, so use a `heldElsewhere` override on `SharedLock` that calls `a.shutdown()` on a virtual thread and waits for `shuttingDown`. If that is not expressible cleanly, cover W1 by reading `resumeWorkflow` directly: call the package-private overload with a reservation on an executor already shut down. Record the choice in the test.
  - `RUNNING_HERE` (W2): call the package-private `resumeWorkflow(instance, …, a.taskQueueGovernor().admitRecovery("orders"))` while `wf-1` is registered running (start it fresh first). Assert the slot math as above, minus the running run's own slot.
  - `LOCK_HELD_ELSEWHERE` (W3): `SharedLock.heldKeys` reports nothing, but `tryAcquire` fails.
  - `LOCK_CALLBACK_ERROR` (W4): the observer's `instanceLockAcquired` throws `AssertionError` once (the `AdoptionLockLeakTest` window (b) pattern).
  - `TURNED_TERMINAL` (W5): `getInstance` returns the row `COMPLETED`.
  - `CAS_LOST` (W6): `store.failNextUpdates(1)` (the existing `updatesToFail`).
  - `STORE_FAULT` (W7): `getInstance` throws `StoreUnavailableException`.
  - `FACTORY_THROWS` (W8): the registration's factory throws `IllegalStateException`.
  - `ERROR_AFTER_ADMISSION` (W8): `a.afterLaunchAdmission = () -> { throw new AssertionError(); }`.

- [ ] **Step 2: Run red.** `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.RecoveryCapacityTest'`. Before Step 3, only the first test compiles, against the existing `recoveryPass(Map)`, so write it first and run it alone. Expected FAIL, as produced at planning time on `14514f54`:

```
A holds a lock only for what it runs or queues ==> expected: <4> but was: <6>
A adopts its 2 slots plus 2 queued (C2) ==> expected: <4> but was: <6>
B adopts what A left ==> expected: <2> but was: <0>
```

  The `deferred()` and `recoveryDeferredCount()` assertions are added once they compile. The window tests need the new API, so they are not reds; their teeth are the mutation in Step 5.
- [ ] **Step 3: Implement** D-1 in `recoverPage`, and the reservation through the launch path. Apply the named Javadoc edit to `WorkflowExecutorConcurrencyTest`.
- [ ] **Step 4: Run green.** `RecoveryCapacityTest`, then `--tests 'io.b2mash.maestro.core.engine.*'`. Expected: PASS, with every test in "Expected to stay green unchanged" among them.
- [ ] **Step 5: Mutation checks.**
  - Delete `recoverPage`'s `finally` release: every throwing or `false` case of `aDeferredOrFailedAdoptionGivesItsReservationBack` goes red (`availableWorkflowSlots` `0`, or headroom `1`).
  - Call `admitRecovery` after `resumeWorkflow` instead of before: `locksAfterA` goes red (`6`).
  - Admit `UNGATED` through `admitRecovery`: `recoverWorkflowsStaysUngated` goes red.
- [ ] **Step 6: Commit:** `fix(engine): the recovery poller reserves a slot before it locks an instance; every failed adoption gives the reservation back (Issue 30, 3.1)`.

---

### Task 3: A run whose thread dies before its body gives back its lock, its slot and its entry (W9)

**Files:**
- Modify: `WorkflowExecutor.java`:
  - add `volatile Runnable beforeRunBody = () -> { };` next to `afterLaunchAdmission` (`:169`), with the same Javadoc shape ("Test seam: runs on the run's own thread before the run body …");
  - the lambda (`:2307-2316`) runs `beforeRunBody` after `WorkflowMDC.populate(ctx)`. A one-element `boolean[] entered` (or an `AtomicBoolean`), set as `executeWorkflow`'s first statement, lets the lambda's `catch (Throwable t)` run the launch-failure cleanup when the body was never entered, then rethrow: `runningWorkflows.remove(id, running)`, `parkingLot.clearPending(id)`, `reservation.release()` when non-null, `instanceLockManager.release(id)`, each failure suppressed on `t`, as at `:2340-2345`.
- Modify (members only): `maestro-core/src/test/java/io/b2mash/maestro/core/engine/AdoptionLockLeakTest.java`

**Interfaces:** consumes Task 2's `reservation` in the lambda.

- [ ] **Step 1: Add the seam only** (no cleanup) and write the test:

```java
/** Window (c), W9: a Throwable on the run's thread before executeWorkflow is entered. */
@Test
void aThrowableBeforeTheRunBodyReleasesTheLockTheSlotAndTheEntry() throws Exception {
    executor = boundedExecutor(new TaskQueueLimits(1, 1));            // GrantingLock, as the class's other tests
    var thrown = new CountDownLatch(1);
    executor.beforeRunBody = () -> { thrown.countDown(); throw new OutOfMemoryError("injected before the run body"); };
    seedRecoverable("wf-1");
    executor.recoveryPass(registrations());
    assertTrue(thrown.await(5, TimeUnit.SECONDS));
    assertAll(
            () -> assertEquals(List.of("maestro:lock:workflow:wf-1"), valueWithin(() -> lock.released,
                    List.of("maestro:lock:workflow:wf-1"), Duration.ofSeconds(5)), "the lock is released"),
            () -> assertFalse(executor.isRunning("wf-1"), "the running entry is gone"),
            () -> assertEquals(2, executor.taskQueueGovernor().recoveryHeadroom("default")));
}
```

- [ ] **Step 2: Run red.** `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.AdoptionLockLeakTest'`. Expected FAIL: `the lock is released ==> expected: <[maestro:lock:workflow:wf-1]> but was: <[]>`. This shows the pre-existing lock leak (traced at planning time, not run). Paste the actual line.
- [ ] **Step 3: Implement** the cleanup. Run green with the same command.
- [ ] **Step 4: Mutation:** skip `reservation.release()` in the cleanup, and the headroom assertion goes red (`1`). Revert.
- [ ] **Step 5: Commit:** `fix(engine): a run whose thread dies before its body releases its instance lock, running entry and slot reservation (W9)`.

---

### Task 4: The capacity pass: resume at the first deferred row, page by headroom

**Files:**
- Modify: `WorkflowExecutor.java`:
  - `RecoveryPass capacityPass(Map<String, WorkflowRegistration>, @Nullable RecoveryCursor resumeAfter, Set<String> deferredQueues)`;
  - `static int capacityPageSize(long headroom)` (D-4);
  - `static final int MIN_CAPACITY_PAGE = 20`.

  The page loop of Task 2 is reused with the start cursor and a page-size function. Before each page, headroom is re-read over `deferredQueues`, and the pass stops when it is 0. A capacity pass never sets `recoveryDeferred`, which is a full pass's count only.
- Test: `RecoveryCapacityTest` (new methods), using a `VersionedInMemoryStore` subclass that records each `getRecoverableInstances(service, after, limit)` call's `after` and `limit`.

- [ ] **Step 1: Tests.**

```java
@Test void aCapacityPassWithNoHeadroomMakesNoStatement() {
    // c = 1; seed 4; full pass admits 2 (RESERVED + QUEUED), defers 2
    var pages = store.pageReads();
    var pass = a.capacityPass(registrations(a), full.resumeAfter(), full.deferredByQueue().keySet());
    assertEquals(0, pass.scanned());
    assertEquals(pages, store.pageReads(), "no page read, no lock read");
}
@Test void aCapacityPassResumesAtTheFirstDeferredRowWithAPageSizedToTheHeadroom() {
    // c = 1; seed 30 (oldest first: wf-00..wf-29); full pass adopts wf-00, wf-01 and defers 28
    work.release(1);                                  // wf-00 completes; wf-01 takes the slot; ticket returns
    awaitHeadroom(a, "orders", 1);
    var pass = a.capacityPass(registrations(a), full.resumeAfter(), Set.of("orders"));
    var read = store.lastPageRead();
    assertAll(
            () -> assertEquals(RecoveryCursor.of(instance("wf-01")), read.after(), "resumes after the last admitted row"),
            () -> assertEquals(20, read.limit(), "max(20, 2 * headroom 1)"),
            () -> assertEquals(1, pass.adopted()),
            () -> assertEquals(List.of("wf-02"), adoptedIds(pass)));   // the oldest deferred
}
@Test void capacityPageSizeIsBounded() {
    assertEquals(20, WorkflowExecutor.capacityPageSize(1));
    assertEquals(60, WorkflowExecutor.capacityPageSize(30));
    assertEquals(200, WorkflowExecutor.capacityPageSize(Integer.MAX_VALUE));
}
```

  Use the instances' `startedAt` spacing (1 ms apart, `seedRecoverable` with an index) so that keyset order is the id order. `adoptedIds` reads the ids the observer saw `workflowResumed` for, from a `RecordingEngineObserver`.
- [ ] **Step 2: Run.** It does not compile before Step 3, so this is not a red. Teeth: Step 4.
- [ ] **Step 3: Implement.** Then run green: `--tests 'io.b2mash.maestro.core.engine.RecoveryCapacityTest'`.
- [ ] **Step 4: Mutations:**
  - start capacity passes at `null`: the `after` assertion goes red;
  - use 200 always: the `limit` assertion goes red;
  - drop the headroom check: `aCapacityPassWithNoHeadroomMakesNoStatement` goes red.
- [ ] **Step 5: Commit:** `feat(engine): a capacity pass resumes at the first deferred instance and reads only what the free capacity can adopt (Issue 30)`.

---

### Task 5: The recovery poller passes first and re-polls on capacity (3.1)

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/RecoveryPoller.java`:
  - a constructor taking `CapacitySignal signal, Duration capacitySpacing`; the two existing ones delegate with `executor.taskQueueGovernor().capacitySignal()` and `min(1 s, pollInterval)`;
  - `pollLoop` follows D-3, D-4 and D-8: pass first; full every `pollInterval`; capacity after a deferring pass, at most once per spacing; a failed pass discards the cursor and waits until the next full pass;
  - the first pass logs the startup line (surface table);
  - the class Javadoc: `<h2>Admission</h2>` (D-1, D-2 in three sentences) and `<h2>Cadence</h2>` (D-3, D-4); the "Failures" paragraph is kept.
- Modify: `WorkflowExecutor.java`: a package-private `startRecoveryPoller(Map, Duration pollInterval, Duration capacitySpacing)`; the public one delegates. The `startRecoveryPoller` Javadoc gains "its first pass runs at once, and a pass that deferred for lack of capacity is followed by another as soon as capacity frees".
- Create: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/RecoveryPollerCapacityTest.java`

- [ ] **Step 1: Write the red tests.**

```java
@Test void aBacklogLargerThanTheBoundDrainsWithoutWaitingForThePollInterval() throws Exception {
    work.releaseAll();                                          // activities return at once
    for (var i = 0; i < 6; i++) seedRecoverable(store, "wf-" + i, "orders");
    var a = node(1);
    a.startRecoveryPoller(registrations(a), Duration.ofHours(1), Duration.ofMillis(20));
    assertEquals(6, valueWithin(() -> completed(store), 6, Duration.ofSeconds(10)));
}
@Test void aParkedRunWokenDuringADrainWaitsBehindAtMostOneQueuesWorthOfRecoveredRuns() throws Exception {
    var a = node(1);
    a.startWorkflow("parked", "AwaitThenWork", "orders", null, () -> new AwaitThenWork(work.proxy(a)), AWAIT_RUN);
    await().atMost(Duration.ofSeconds(5)).until(() -> a.parkedCount() == 1);
    for (var i = 0; i < 5; i++) seedRecoverable(store, "rec-" + i, "orders");
    a.startRecoveryPoller(registrations(a), Duration.ofHours(1), Duration.ofMillis(20));
    valueWithin(work::entries, 1, Duration.ofSeconds(5));                       // rec-0 running, rec-1 queued
    a.deliverSignal("parked", "go", "now");
    valueWithin(a::parkedCount, 0, Duration.ofSeconds(5));                      // parked is now in the slot line
    for (var i = 0; i < 6; i++) { work.release(1); }                           // one activity at a time
    var order = valueWithin(work::order, o -> o.contains("parked"), Duration.ofSeconds(10));
    assertEquals(2, order.indexOf("parked"), "order=" + order);
}
@Test void stopWhileWaitingForCapacityEndsTheLoopPromptly() throws Exception {
    // c = 1, seed 3 (one running, one queued, one deferred); poller with 1 h / 1 h spacing;
    // valueWithin(() -> store.pageReads(), 1, 5 s); then a.shutdown() on a virtual thread with a latch:
    assertTrue(stopped.await(1, SECONDS), "shutdown's poller stop returned");
    assertFalse(a.isRecoveryPollerRunning());
}
@Test void capacityPassesAreSpacedAndFreeWhenNothingIsFree() throws Exception {
    // c = 1, seed 3; spacing 100 ms; a virtual thread parks and unparks a SleepWorkflow of another
    // run on the same queue 50 times a second (each park chunk gives its slot: signal bumps, no headroom
    // because the queued recovered run takes the permit) for 1 s; then:
    assertTrue(store.pageReads() - before <= 1 + 1000 / 100 + 1, "at most one capacity pass per spacing");
    assertEquals(before, store.pageReadsWithZeroHeadroom(), "a wake with no headroom read nothing");
}
@Test void thePollersFirstPassRunsAtOnce() throws Exception {
    var a = node(1);
    a.startRecoveryPoller(Map.of(), Duration.ofHours(1), Duration.ofMillis(20));
    assertEquals(1, valueWithin(store::pageReads, 1, Duration.ofSeconds(5)));
}
```

  The last fixture of `capacityPassesAreSpacedAndFreeWhenNothingIsFree` is the most involved. If a park churn cannot be produced deterministically, drive the signal directly instead: a test thread calls `a.taskQueueGovernor().capacitySignal().signal()` 500 times over 1 s while headroom stays 0. Record which form was used.

- [ ] **Step 2: Run red.** `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.RecoveryPollerCapacityTest'`. On the unfixed engine, write the first two tests against the public `startRecoveryPoller(Map, Duration)` with `Duration.ofHours(1)` (no spacing argument yet). Expected FAILs; the first was produced at planning time on `14514f54`, the second by the probe's order:

```
aBacklogLargerThanTheBoundDrainsWithoutWaitingForThePollInterval: expected: <6> but was: <0>
aParkedRunWokenDuringADrainWaitsBehindAtMostOneQueuesWorthOfRecoveredRuns: order=[rec-0, rec-1, rec-2, rec-3, rec-4, parked] ==> expected: <2> but was: <5>
```

  The planning probe's `index 5` came from adopting with `recoverWorkflows`, which is today's startup path. On the unfixed *poller* nothing is adopted within the test (it sleeps 1 h first), so the parked run has the slot to itself and the committed red reads `expected: <2> but was: <0>`. Both reds are positive facts. Paste the actual line. Why `parked` is third after the fix (D-2): rec-0 runs and rec-1 is queued before the signal. `parked` queues behind rec-1. rec-2 is admitted only after rec-1 takes its slot and a capacity pass runs at least one spacing (20 ms) later, so it lands behind `parked`.
- [ ] **Step 3: Implement.** Run green: the same class, then `--tests 'io.b2mash.maestro.core.engine.BackgroundLoop*'` (`BackgroundLoopErrorTest`, `BackgroundLoopStartShutdownRaceTest`, unchanged).
- [ ] **Step 4: Mutations:**
  - read `seen` *after* the pass instead of before: `aBacklogLargerThanTheBoundDrains…` goes red, or flaky-slow;
  - skip the spacing wait: `capacityPassesAreSpaced…` goes red;
  - drop the zero-headroom check before a capacity pass: the "read nothing" assertion goes red;
  - sleep first again: `thePollersFirstPassRunsAtOnce` goes red.

  Record each result.
- [ ] **Step 5: Commit:** `fix(engine): the recovery poller passes first and re-polls as soon as capacity frees, at most once a second (Issue 30)`.

---

### Task 6: Recovered runs are not starved by a sustained stream of fresh starts (C2)

**Files:** create `maestro-core/src/test/java/io/b2mash/maestro/core/engine/RecoveryStarvationTest.java`. Test only. It pins D-2.

- [ ] **Step 1: The test.**

```java
/**
 * C2: under a stream of fresh starts that keeps a waiter queued at every moment, a
 * bare "try for a free slot" gate admits nothing (fair tryAcquire(0) refuses while
 * anyone waits; planning probe: 0 of 1000). Recovered runs must still complete:
 * each takes its turn in the FIFO like a fresh start submitted at the same instant.
 */
@Test
void recoveredRunsProgressUnderASustainedStreamOfFreshStarts() throws Exception {
    var a = node(1);                                                    // one slot, one ticket
    for (var i = 0; i < 3; i++) startFresh(a, "fresh-" + i);           // 1 running, 2 waiting
    for (var i = 0; i < 3; i++) seedRecoverable(store, "rec-" + i, "orders");
    work.completeAtOnceFor(id -> id.startsWith("rec-"));               // recovered runs' activity returns at once
    a.startRecoveryPoller(registrations(a), Duration.ofHours(1), Duration.ofMillis(10));
    var fresh = 3;
    for (var step = 0; step < 300 && completedRecovered(store) < 3; step++) {
        startFresh(a, "fresh-" + fresh++);                              // two in, one out:
        startFresh(a, "fresh-" + fresh++);                              // the queue never empties
        work.release(1);
        await().atMost(Duration.ofSeconds(5)).until(() -> work.finished() >= step + 1);
    }
    assertEquals(3, completedRecovered(store), "recovered runs completed while fresh starts kept the queue non-empty");
}
```

- [ ] **Step 2: Run.** `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.RecoveryStarvationTest'`. Expected: PASS on this branch. On the unfixed engine the poller sleeps 1 h, so the test fails `expected: <3> but was: <0>`. That is the same red as Task 5, so it is not repeated as this test's red. Its teeth are the mutation.
- [ ] **Step 3: Mutation (the C2 check):** make `admitRecovery` return `null` instead of `QUEUED` (a bare gate). The test goes red: `expected: <3> but was: <0>`. Record it, then revert.
- [ ] **Step 4: Commit:** `test(engine): recovered runs complete under a sustained stream of fresh starts (C2)`.

---

### Task 7: Startup recovery runs off the `ApplicationRunner` thread; ungated and retried when the poller is disabled

**Files:**
- Create: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/OneShotRecovery.java`:
  - thread `maestro-startup-recovery-<svc>`, with the guard's uncaught handler;
  - `start()`, `stop()` (interrupt and join, as `RecoveryPoller`), `isRunning()`;
  - loop: `while (running) { try { var n = executor.recoverWorkflows(regs); guard.succeeded(); log; return; } catch (Throwable t) { guard.failed(t); } Thread.sleep(retryInterval) }`, with the `InterruptedException` exit arm.
- Modify: `WorkflowExecutor.java`:
  - `startOneShotRecovery(Map, Duration)`, validated like `startRecoveryPoller` and installed with `installAndStart` into a new `AtomicReference<OneShotRecovery> oneShotRecovery`;
  - `boolean isOneShotRecoveryRunning()`;
  - `shutdown()` stops it next to the recovery poller (S4).
- Modify: `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/config/StartupRecoveryRunner.java`:
  - `run` follows D-6: no `recoverWorkflows`; "1." starts the poller or the one-shot pass; "3." is removed;
  - the class Javadoc says the runner only starts loops ("It adopts nothing itself; keep it that way: anything slow added here delays readiness").
- Modify: `SignalSubscriptionRunner.java:42-45`. The Javadoc becomes: "Ordered `HIGHEST_PRECEDENCE + 20`, right after `StartupRecoveryRunner` (+10), which starts recovery but does not wait for it. A signal can therefore arrive before its workflow is re-adopted; that is safe, since `deliverSignal` needs only the store and the adopting replay finds the signal."
- Modify (named): `StartupRecoveryRunnerTest.java` class Javadoc; add members.
- Create: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/OneShotRecoveryTest.java`

- [ ] **Step 1: Write the red test** in `StartupRecoveryRunnerTest`. The store is a `java.lang.reflect.Proxy` over `InMemoryWorkflowStore`, whose three-argument `getRecoverableInstances` counts down `reading` and waits on `release`. A JDK proxy intercepts the default method too.

```java
@Test
@DisplayName("run() returns while the startup recovery pass is still reading")
void theRunnerReturnsWhileTheStartupPassIsStillReading() throws Exception {
    var returned = new CountDownLatch(1);
    Thread.ofVirtual().start(() -> { runner.run(null); returned.countDown(); });
    try {
        assertTrue(reading.await(5, SECONDS), "the startup pass began reading");
        assertTrue(returned.await(2, SECONDS), "run() returned while the startup pass was still reading");
    } finally {
        release.countDown();
    }
}
@Test void withRecoveryDisabledTheStartupPassIsUngated() { /* defaults (1,1), 3 seeded, enabled=false:
    runningCount() reaches 3 within 5 s; startRecoveryPoller still succeeds afterwards */ }
@Test void withRecoveryDisabledAFailedStartupPassIsRetried() { /* first page read throws
    StoreUnavailableException, second succeeds; pollInterval 50 ms: the seeded instance is adopted within 5 s */ }
@Test void aStoreFailureInTheStartupPassDoesNotFailRun() { /* store throws on every page read: run() returns normally */ }
```

  `OneShotRecoveryTest`:
  - `adoptsEveryInstanceWithoutACapacityCheck`: (1,1) limits, 4 seeded, then `heldCount() == 4`.
  - `retriesAfterAFailureThenExits`: `isOneShotRecoveryRunning()` is `false` after the successful pass.
  - `shutdownStopsARetryingPass`: the store always throws, retry 1 h. `shutdown()` returns within 2 s and `isOneShotRecoveryRunning()` is `false`.
  - `aControlFlowErrorEndsIt`: the store throws `ExecutorShutdownException`, and the thread ends.

- [ ] **Step 2: Run red.** `./gradlew --no-watch-fs :maestro-spring-boot-starter:test --tests 'io.b2mash.maestro.spring.config.StartupRecoveryRunnerTest'`. Expected FAIL for the first test, as produced at planning time on `14514f54`:

```
run() returned while the startup pass was still reading ==> expected: <true> but was: <false>
```

  `aStoreFailureInTheStartupPassDoesNotFailRun` is also red on the unfixed code: `run` throws the store's exception. Paste the actual line. The two disabled-mode tests need the new API.
- [ ] **Step 3: Implement.** Run green: `StartupRecoveryRunnerTest`, then `:maestro-core:test --tests '*OneShotRecoveryTest'`, then `:maestro-spring-boot-starter:test --tests 'io.b2mash.maestro.spring.config.*' --tests 'io.b2mash.maestro.spring.health.*'`.
- [ ] **Step 4: Mutations:**
  - call `recoverWorkflows` inline again: `theRunnerReturns…` goes red;
  - make the one-shot pass gated (`recoveryPass`): `withRecoveryDisabledTheStartupPassIsUngated` goes red (`2`);
  - drop the retry: `withRecoveryDisabledAFailedStartupPassIsRetried` goes red.
- [ ] **Step 5: Commit:** `fix(starter): startup recovery runs on the recovery poller's thread, or on a retried one-shot ungated thread when the poller is disabled (Issue 30)`.

---

### Task 8: Two nodes on Postgres share one backlog

**Files:** create `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/multinode/MultiNodeRecoveryCapacityIT.java`, modelled on `MultiNodeOwnerDeathIT`. Each node is a `MaestroEngineHarness` with its own `PostgresDistributedLock(newDataSource())` and `taskQueueLimits(Map.of("orders", new TaskQueueLimits(2, 2)))`. The rows are seeded with `store.createInstance` (`RUNNING`, the suite's service name, queue `orders`). Activities block on a shared semaphore.

- [ ] **Step 1: The test** (Issue 30's "Done when", on a real lock table):

```java
@Test
@DisplayName("with concurrency 2, a 12-run backlog is executed by both nodes and neither locks more than 4")
void aBacklogIsSharedAndNoNodeHoards() throws Exception {
    seed(12);
    nodeA.startRecoveryPoller(Duration.ofHours(1));
    assertEquals(4, valueWithin(this::lockRows, 4, Duration.ofSeconds(10)), "A locked 2 running + 2 queued");
    nodeB.startRecoveryPoller(Duration.ofHours(1));
    assertEquals(8, valueWithin(this::lockRows, 8, Duration.ofSeconds(10)), "B took 4 of the remaining 8");
    work.releaseAll();
    assertEquals(12, valueWithin(this::completed, 12, Duration.ofSeconds(60)));
    assertAll(
            () -> assertTrue(recorderA.count("work") >= 2, "A executed runs"),
            () -> assertTrue(recorderB.count("work") >= 2, "B executed runs"));
}
```

  `lockRows()` is `SELECT count(*) FROM maestro_distributed_lock WHERE lock_key LIKE 'maestro:lock:workflow:%' AND expires_at > now()`.
- [ ] **Step 2: Run.** `./gradlew --no-watch-fs :maestro-integration-tests:test --tests '*MultiNodeRecoveryCapacityIT'`. Expected: PASS. Against the unfixed engine the first assertion is red (`expected: <4> but was: <0>`: the poller sleeps 1 h first); with `recover()` in its place it would be `<12>`. Record which was observed if the reviewer reverts.
- [ ] **Step 3: Commit:** `test(integration): two nodes on Postgres share one recovery backlog; neither locks more than it can run or queue (Issue 30)`.

---

### Task 9 (MD-3): the `maestro.recovery.deferred` gauge

Skip this task if the maintainer rules MD-3 (a).

**Files:**
- Modify: `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/observe/MaestroEngineGauges.java`: `Gauge.builder("maestro.recovery.deferred", this.executor, WorkflowExecutor::recoveryDeferredCount).description(…).strongReference(true).register(registry)`.
- Modify: `docs/observability.md`:
  - a gauges-table row and a "how they are sourced" bullet: the value is the deferred count of this node's latest **full** pass; `0` on a healthy fleet; during a drain, the backlog this node could see but not run; node-local, like the other gauges; do not sum it across pods, because every node sees the same unadopted rows;
  - the meter-count sentence at `:75`. **Recount it from the tables**; do not add deltas by hand, because WP11 edits the same sentence.
- Test: add one method to the existing gauge test (`MaestroEngineGauges` is exercised in `MaestroObservabilityAutoConfigurationTest`).

```java
@Test void recoveryDeferredGaugeReadsTheLatestFullPass() {
    // executor with defaults (1,1), 4 seeded, executor.recoveryPass(regs) (the full pass): gauge == 2.0
}
```

- [ ] **Step 1: Test, red** (`MeterNotFoundException` naming `maestro.recovery.deferred`). **Step 2: Implement, green.** **Step 3: Commit:** `feat(observe): maestro.recovery.deferred gauges the instances this node's last full recovery pass left for capacity (Issue 30)`.

**WP11 rebase.** WP11 adds `maestro.taskqueue.slots.used` and two other meters to the same table and sentence. Keep both sides' additions, and recount the sentence from the tables.

---

### Task 10: Issue 36: opt-in concurrency limits on the test kit (C3)

**Files:**
- Modify: `maestro-test/src/main/java/io/b2mash/maestro/test/TestWorkflowEnvironment.java`:
  - `builder()`, `Builder` (D-9);
  - the private constructor takes `(ObjectMapper, Map<String, TaskQueueLimits>, @Nullable TaskQueueLimits)`; `Node` passes both to the executor builder (`:578-585`);
  - `create()` and `create(ObjectMapper)` delegate;
  - the class Javadoc gains `<h2>Concurrency limits</h2>`: opt-in, production's 20/20 is `defaultConcurrency(20, 20)`, and `recover()` is ungated (D-9);
  - the `recover()` Javadoc gains the D-9 sentence.
- Modify: `MaestroTest.java` (two attributes, with Javadoc), `MaestroTestExtension.java` (D-9).
- Create: `maestro-test/src/test/java/io/b2mash/maestro/test/TestWorkflowEnvironmentLimitsTest.java`, `maestro-test/src/test/java/io/b2mash/maestro/test/MaestroTestLimitsTest.java`

- [ ] **Step 1: Stub first.** Add `builder()` and `Builder` whose `build()` validates and **ignores** the limits, so the red below runs on a positive fact rather than a compile error.
- [ ] **Step 2: Write the tests** (Issue 36's "Done when").

```java
// A parent whose activity starts a same-queue child and waits for it (pitfalls rule 11).
@Test void aSameQueueChildWaitedOnInsideAnActivityTimesOutAtLimitsOneOne() throws Exception {
    env = TestWorkflowEnvironment.builder().defaultConcurrency(1, 1).build();
    env.registerActivities(Spawner.class, new SpawnerImpl(env, Duration.ofSeconds(1), outcome));
    env.startWorkflow(ParentWorkflow.class, "order-1");
    assertInstanceOf(TimeoutException.class, outcome.get(10, SECONDS));
}
@Test void theSameChildCompletesWithNoLimits() throws Exception {
    env = TestWorkflowEnvironment.create();
    // same wiring: assertEquals("child-done:order-1", outcome.get(10, SECONDS))
}
@Test void theSameChildCompletesWithLimitsZeroZero() throws Exception {
    env = TestWorkflowEnvironment.builder().defaultConcurrency(0, 0).build();
    // same assertion
}
@Test void aChildOnItsOwnQueueCompletesAtLimitsOneOne() throws Exception {
    // ChildWorkflow annotated taskQueue = "children": completes — rule 11's "Right" fix
}
@ParameterizedTest @CsvSource({"1,0", "0,1", "-1,1", "1,-1"})
void halfOrNegativeDefaultsFail(int w, int a) {
    var e = assertThrows(IllegalArgumentException.class, () -> TestWorkflowEnvironment.builder().defaultConcurrency(w, a).build());
    assertThat(e.getMessage()).contains("defaultConcurrency").contains(w + "").contains(a + "");
}
@Test void aQueueNamedTwiceFails() { /* taskQueueConcurrency("q",1,1).taskQueueConcurrency("q",2,2).build() → IAE naming "q" */ }
```

  `SpawnerImpl.spawn(id)` calls `env.startWorkflow(ChildWorkflow.class, id).getResult(String.class, wait)`. It completes `outcome` with the result or with the caught `TimeoutException`, and rethrows the latter.

  `MaestroTestLimitsTest`:

```java
@MaestroTest(defaultConcurrency = 1, defaultActivityConcurrency = 1)
class MaestroTestLimitsTest {
    @Test void theAnnotationsLimitsApply(TestWorkflowEnvironment env) { /* the (1,1) timeout case */ }
    @Nested class Inner {
        @Test void aNestedClassInheritsTheEnclosingLimits(TestWorkflowEnvironment env) { /* same */ }
    }
}
```

- [ ] **Step 3: Run red.** `./gradlew --no-watch-fs :maestro-test:test --tests 'io.b2mash.maestro.test.TestWorkflowEnvironmentLimitsTest' --tests 'io.b2mash.maestro.test.MaestroTestLimitsTest'`. Expected FAIL: `Unexpected type, expected: <java.util.concurrent.TimeoutException> but was: <java.lang.String>` for the (1,1) cases, because the stub ignores the limits and the child completes. The validation tests pass already.
- [ ] **Step 4: Implement** the wiring and the extension. Run green with the same command, then `:maestro-test:test` whole (the kit's own suite must not change behaviour).
- [ ] **Step 5: Mutation:** pass only `taskQueueLimits` and drop `defaultTaskQueueLimits` in `Node`: the (1,1) cases go red. Revert.
- [ ] **Step 6: Commit:** `feat(test): TestWorkflowEnvironment and @MaestroTest take opt-in concurrency limits (Issue 36, C3)`.

---

### Task 11: Docs, release note, issues, gates

**Files:** `docs/configuration.md`, `docs/operations.md`, `docs/self-recovery.md`, `docs/pitfalls.md`, `docs/testing.md`, `docs/release-notes.md`, `docs/open-issues.md`, `CLAUDE.md`.

- [ ] **Step 1: `docs/configuration.md`.**
  - Replace the paragraph "Recovery does not wait for slots …" (`:554-562`) with D-1 and D-2 in operator words:
    - a node adopts at most its free slots plus one queue's `concurrency` of queued runs, per queue;
    - the rest are left for a peer, and re-polled as capacity frees;
    - queued recovered runs wait in the same line as new starts, so neither starves the other, and the cost is at most one run-length of extra wait while a backlog drains;
    - a node can hold up to 2 × `concurrency` instance locks per queue for recovery;
    - with both defaults `0`, recovery is unbounded, as before.

    Cite `RecoveryCapacityTest`, `RecoveryStarvationTest` and `MultiNodeRecoveryCapacityIT`.
  - Recovery Configuration (`:636-660`): "On top of the one-shot recovery at startup" becomes D-6. Add a "Capacity" paragraph (D-3, D-4: the first pass is immediate, capacity passes run at most once a second and read only what can be adopted, full passes every `poll-interval`). The `maestro.recovery.enabled` row's description gains: "`false`: a one-shot startup pass still runs, without a capacity check, retried every `poll-interval` until it completes."
- [ ] **Step 2: `docs/operations.md`.**
  - **§2 (`:77-86`):** "the restarted node's own `StartupRecoveryRunner` (which runs synchronously on boot, not gated on the 60s poll cadence)" becomes "the restarted node's recovery poller, whose first pass runs as soon as it starts (not after the 60 s cadence), on its own thread". Add "(traced, not re-measured since this change)" after "roughly **2 seconds**".
  - **§13, Execution bounds** (`:1302-1308`): replace "Recovery adopts without waiting for slots … holding its instance locks (Issue 30)" with D-2's bounds (≤ C running + ≤ C queued per queue per node; the rest deferred).
  - **§13:** add a table row "Recovery capacity pass: 0 statements with no headroom; otherwise ⌈rows read / page⌉ page reads + as many `heldKeys` reads + 3 per adoption; at most one per second per node while a drain lasts".
  - **New subsection "Recovery sizing"** (arithmetic, labelled):
    - drain time ≈ N · T / (K · C) for N runs, T per run, K nodes, C per queue;
    - the 5,000 / 20 / 2 s example: ~500 s on one node, ~250 s on two (today one node takes all of it);
    - full-pass cost: 170 statements, where today's pass costs ≈ 15,026;
    - steady statements per adopted run ≈ 3.2;
    - how `maestro.recovery.deferred` reads during a drain, with MD-3's alert ("high over several full passes").
  - **Multi-instance behaviour:** one paragraph in §1 or §2 saying a restarted fleet now shares a backlog instead of the first node hoarding it.
- [ ] **Step 3: `docs/self-recovery.md`.** `:141` ("`StartupRecoveryRunner` … calls `executor.recoverWorkflows()`") and `:229` ("workflows are recovered before the service starts accepting new traffic") become D-6: the runner starts the recovery poller, whose first pass runs at once on its own thread; the service may accept traffic before every workflow is re-adopted, which is safe for signals (persisted) and queries (owning node only).
- [ ] **Step 4: `docs/pitfalls.md` rule 11, "Test it" (`:360`).** Replace it with: "**Test it:** build the kit with production's limits, `TestWorkflowEnvironment.builder().defaultConcurrency(20, 20).build()` or `@MaestroTest(defaultConcurrency = 20, defaultActivityConcurrency = 20)`. To see the deadlock at once, use `(1, 1)`: the activity's wait times out (`TestWorkflowEnvironmentLimitsTest`)." **`docs/testing.md`:** a "Concurrency limits" subsection next to the `create()` examples (`:57-60`), stating D-9 (opt-in, validation, `recover()` ungated).
- [ ] **Step 5: `CLAUDE.md` `:221`.** After "…or its workflows are stranded.", add: "Recovery admits per queue at most its free slots plus `concurrency` queued runs (`TaskQueueGovernor.admitRecovery`, never blocking, never barging), takes the lock only after that, and re-polls as soon as capacity frees; `recoverWorkflows` is the ungated pass. `StartupRecoveryRunner` only starts loops, never adopts."
- [ ] **Step 6: `docs/release-notes.md`.** Add a new first subsection under `## Unreleased — Postgres + Kafka only`: `### Fixed — a node adopts only the recovered runs it can run; startup no longer waits for recovery (Issue 30, 3.1, Issue 36)`. Its bullets:
  - **What was wrong.** The probe's numbers in three sentences: 40 of 40 locked by a node able to run 4, the peer idle, a parked run's wake behind the whole backlog, and startup held for the whole pass.
  - **What changes.** D-1, D-2 and D-3 in operator words; the cost to new starts (≤ one run length while a drain lasts); unbounded queues unchanged.
  - **Startup.** D-6. Readiness no longer waits; a store failure there no longer fails startup (MD-2); `maestro.recovery.enabled=false` keeps an ungated startup pass.
  - **Meters and logs.** `maestro.recovery.deferred` (if Task 9 shipped); `scanned`/`adopted` move on capacity passes; the changed log lines.
  - **Test kit.** Issue 36's option and annotation attributes.
  - **Mixed fleets.** Old and new nodes interoperate: an old node still adopts past capacity; locks and fencing keep both correct.
- [ ] **Step 7: `docs/open-issues.md`.**
  - **Issue 30:** status line `> **Resolved** in WP6 (branch fix/recovery-capacity): the recovery poller reserves a slot before locking, queues at most one queue's concurrency (C2), and re-polls on capacity; pinned by RecoveryCapacityTest, RecoveryPollerCapacityTest, RecoveryStarvationTest, MultiNodeRecoveryCapacityIT.` Index row `:328` → `**Resolved**`.
  - **Issue 36:** resolved the same way, pinned by `TestWorkflowEnvironmentLimitsTest` and `MaestroTestLimitsTest`. Index row `:334` → `**Resolved**`.
  - **Issue 29:** add one sentence: "Since WP6 at most `concurrency` recovered runs per queue can be queued at shutdown, not a whole backlog."
  - **New issues**, each with the house sections (what happens, file:line, harm, fix shape, why it is not WP6) and an index row:
    - **72.** The test kit stays unbounded by default and its `recover()` is ungated, so a user test sees neither production's 20/20 nor gated recovery (C3's follow-up). Fix shape: default to 20/20 and give `recover()` a gated mode, after a release note.
    - **73.** No health or readiness detail says the startup recovery pass is still running (MD-6). Fix shape: a `startupRecovery` detail on `MaestroHealthIndicator`.
- [ ] **Step 8: Reviewer checks.**
  - Run the existing-tests check (statement-level, above) and paste its output into the commit body.
  - Run `./gradlew --no-watch-fs :maestro-core:javadoc :maestro-spring-boot-starter:javadoc :maestro-test:javadoc`. Expected: no warning naming a changed member.
  - `grep -n "releaseWorkflowSlot" maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java` must show only `executeWorkflow`'s `finally`, never a call on a reservation path (Global Constraints, "Exactly-once").
- [ ] **Step 9: Module tests.** Run these, one at a time:
  - `./gradlew --no-watch-fs :maestro-core:test`;
  - `./gradlew --no-watch-fs :maestro-spring-boot-starter:test`;
  - `./gradlew --no-watch-fs :maestro-test:test`;
  - `./gradlew --no-watch-fs :maestro-integration-tests:test --tests '*MultiNode*' --tests '*EnginePostgresRecoveryIT' --tests '*StoreOutageMidStepIT'`.

  Expected: all PASS.
- [ ] **Step 10: Commit:** `docs: WP6 release note; resolve Issues 30 and 36; file issues 72-73; recovery sizing`.
- [ ] **Step 11: Coordinator gates, before the PR merges.** Each is launched detached with a log header (`pwd`, `git rev-parse HEAD`, timestamp):

| Gate | Command | Pass criterion |
|---|---|---|
| Uncached build | `./gradlew --no-watch-fs build --no-build-cache --rerun-tasks` | `BUILD SUCCESSFUL` |
| Whole integration suite | `./gradlew --no-watch-fs :maestro-integration-tests:test` | `BUILD SUCCESSFUL` |
| Nightly E2E on the branch | `gh workflow run e2e-nightly.yml --ref fix/recovery-capacity` | the run concludes `success` |
| Chaos PR-gate ×3 | `./gradlew --no-watch-fs :maestro-integration-tests:e2eTest --rerun-tasks`, three consecutive fresh-seed runs | 3/3 green. The sample services run `concurrency: 5`, so their kills and rolling restarts drive the gated path. Every I1–I5 invariant holds (operations §8). Zero workflows `FAILED` that the path script did not declare. |
| 2 h soak | `./gradlew --no-watch-fs :maestro-integration-tests:e2eTest --rerun-tasks -Dmaestro.chaos.soak=true -Dmaestro.chaos.durationMinutes=120` | Green, with the same invariants. In the soak's node-count tail (graceful stop of one node per service), every workflow of the stopped nodes reaches its declared terminal. Record the recovery-query sample next to the last soak's, for Issue 12: capacity passes add ≤ 1 page read per node per second during a drain, and none otherwise. |

  Every result goes in the review section with its log identity. Also send the WP12 note below to the owner of `test/k6-load-foundation`, and Task 7's `StartupRecoveryRunner` note to WP8's planner.

---

## WP12 scenario 2: what the "before" and "after" must measure (no k6 code here)

The scenario is a 5,000-run recovery drain on 1 and on 2 nodes of `sample-perf`, at Maestro's defaults (20/20, 60 s poll interval).
- **Setup.** Each seeded run is `RUNNING` with one remaining activity of fixed duration d (suggest 2 s, the handover's figure). All nodes are stopped, then 1 node is started (or 2 at once).
- **Rules.** The "before" is the WP6 merge base with `perf/` and `sample-perf` cherry-picked (C4); the "after" is this branch. Pass/fail per D4 rests on statement counts and within-run comparisons only.

| Measure | How (harness-visible, no new engine API) | Expected before → after (arithmetic) |
|---|---|---|
| Time to drain | First node process start → last of the 5,000 rows terminal (`maestro_workflow_instance.status`) | 1 node: ≈ 500 s → ≈ 500 s. **2 nodes: ≈ 500 s (one node hoards) → ≈ 250 s.** Gate: after, 2-node drain ≤ 0.6 × 1-node drain in the same run set. |
| Adoptions per second, per node | `maestro.recovery.adopted` counter rate per pod, or `lease_epoch` bumps per second | Before: one burst on one node at startup. After: a steady rate on every node ≈ C / d per queue. |
| Statements per adopted run | `pg_stat_statements` calls of the lock insert, the instance `SELECT` by id, the CAS `UPDATE`, the recovery page `SELECT` and the `heldKeys` `SELECT`, divided by 5,000 | Before ≈ 3.0 (+ 0.01). After ≈ 3.2. Gate: ≤ 3.5. Also record the page-read count. |
| Locks held vs runs executing | `count(*)` of live `maestro:lock:workflow:%` rows, against `maestro.taskqueue.slots.used{kind=workflow}` (WP11) summed over pods, sampled every second | Before: locks ≈ 5,000 on one node, executing 20. After: locks ≤ 2C = 40 per node. Gate: max(locks − executing) ≤ C per node. |
| Readiness | Process start → `/actuator/health/readiness` `UP` | Before: grows with the backlog (≈ 23 s for 5,000 by the probe's ratio). After: independent of the backlog. |
| Starvation under a fresh-start stream | During the drain, a constant fresh-start arrival rate of 1.5 × node capacity (C / d); record recovered completions per minute and fresh-start latency p50/p99 | After: recovered completions per minute > 0 in every minute (C2). Fresh-start p99 rises by ≤ ≈ d over a no-drain baseline in the same run (advisory). Before: recovered runs keep pace (they hoard ahead of fresh starts) but locks and parked-wake latency are unbounded, as above. |
| Parked-wake latency during the drain | Signal 100 parked runs over REST mid-drain; measure signal → resume | Before: ≈ the backlog's remaining drain time. After: ≤ ≈ d. |

---

## Self-review (run 2026-11-17)

- **Brief coverage.**
  1. Today's path end to end, where it blocks and what it holds: "Today's recovery path".
  2. The reproduction of 3.1, with numbers and real red output: same section, plus Tasks 2, 5 and 7.
  3. How starts and recovered runs compete today (FIFO, no barging; a bare gate starves): probe lines, D-2.
  4. What the startup pass blocks and for how long: the probe's 2.3 s per 500 adoptions, and D-6.
  5. Signalling "a slot freed" without a busy loop or a lost wake-up: D-3.
  6. Fairness between queues: D-5.
  7. `default-concurrency: 0`: D-7.
  8. Shutdown, the guard and health: D-8.
  9. Every window W1–W9: D-1's table.
  10. The C2 arithmetic and its cost: D-2.
  11. Page-size interaction: D-4.
  12. Startup threading and "ready": D-6.
  13. Observability: MD-3, Task 9.
  14. Issue 36: D-9, Task 10.
  15. Which tests change: "Existing tests changed".
  16. WP5 seams: Before you start.
  17. k6 scenario 2: its own section.
- **Spec.** The WP6 row in full; C2 (D-2, Task 6); C3 (D-9); §6 gates with the soak (Task 11); §3 "Issue 30 as filed is incomplete" (the re-poll, and the ungated disabled mode); §3 "Issue 36 as filed cannot work" (annotation attributes instead of binding).
- **Type consistency.** These names are the same in every task:
  - `admitRecovery`, `SlotReservation{UNBOUNDED, RESERVED, QUEUED}`, `release()`, `acquireWorkflowSlot(q, id, reservation)`, `recoveryHeadroom`;
  - `CapacitySignal.epoch/signal/awaitChange`;
  - `RecoveryGate{GATED, UNGATED}`, `RecoveryPass.deferredByQueue/resumeAfter/deferred()`, `capacityPass`, `capacityPageSize`, `MIN_CAPACITY_PAGE`;
  - `recoveryDeferredCount()`, `startOneShotRecovery`, `OneShotRecovery`, `isOneShotRecoveryRunning`, `beforeRunBody`;
  - `maestro.recovery.deferred`;
  - `TestWorkflowEnvironment.builder/defaultConcurrency/taskQueueConcurrency`, `@MaestroTest(defaultConcurrency, defaultActivityConcurrency)`.
- **Proportion.** The code blocks are test assertions, signatures, log copy and commands. Bodies are left to the decisions and the tests, except D-1's admission order and D-3's read-before-await, which the tests pin.
- **Risk left open.** Task 5's spacing test and Task 6's stream test are the two timing-sensitive fixtures. Both are written against latches and counts, with fallbacks named in their steps. If either flakes in review, the reviewer reports it rather than widening a bound.

## Review section

(The coordinator fills this in: the red and mutation lines per task; WP5's seam names as found; the MD rulings; the issue numbers given to 72–73; gate results with log identity; the WP8 and WP12 hand-offs.)
