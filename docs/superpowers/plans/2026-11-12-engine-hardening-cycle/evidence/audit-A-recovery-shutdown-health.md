# Audit A: recovery admission, shutdown, health, test-kit limits, executor API

**Repo:** `/Users/rakheendama/Projects/2026/maestro`, branch `main`, HEAD `cbf5a2a` (clean). Read-only; nothing modified.
**Date:** 2026-11-12.
**Method:** I found each finding by searching for its content and read the current source. Line numbers are at `cbf5a2a`.
- **[traced]** means I read the code path end to end.
- **[inferred]** means a conclusion about runtime behaviour I did not execute. That includes JDK/Spring internals, except where I disassembled the jar, which is marked as such.
- I ran no tests.

Paths are abbreviated as follows:
- `core/` = `maestro-core/src/main/java/io/b2mash/maestro/core/`
- `starter/` = `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/`
- `kit/` = `maestro-test/src/main/java/io/b2mash/maestro/test/`

---

## Summary table

| # | Finding | Verdict | Fix (one phrase) | Size |
|---|---|---|---|---|
| 3.1 / Issue 30 | Recovery adoption not gated on capacity; startup recovery serial on the runner thread | **CONFIRMED** (both halves). The harm is larger than stated: hoarded waiters also delay parked runs' wakes | Reserve the slot before locking, plus a capacity-triggered early re-poll; run the startup pass on the poller thread | M (M/L with the re-poll) |
| 3.5a | Health goes DOWN when the pool is merely busy | **CONFIRMED** in code. Harm depends on how the host wires its probes | A probe timeout reports UNKNOWN or UP-with-detail, not DOWN; document readiness-only | S |
| 3.5b | Pollers catch `Exception` only, so an `Error` kills them | **CONFIRMED**, and **wider than stated**: janitor, retention sweeper and the **instance-lock renewer** do the same, and the renewer's death is invisible | Use one `catch (Throwable)` loop guard that rethrows `MaestroControlFlowError` | S |
| 3.6 | Shutdown can outlast a 30 s k8s grace | **CONFIRMED.** The handler's Javadoc misstates its phase | One shutdown budget across all steps; stop asynchronously via the callback; document the grace | S |
| Issue 29 | Slot waiters not interrupted at shutdown | **CONFIRMED.** Its marginal drain cost is smaller than the issue implies, but it delays lock release | Close the governor and pass one "poison" permit along the queue; no interrupts | S |
| 3.9 / Issue 12 | Per-parked-workflow steady load | **CONFIRMED** accurate and documented (`docs/operations.md` §13) | None in scope (needs a benchmark) | L |
| Issue 36 | Test kit cannot apply default limits | **CONFIRMED**. The issue's "bind from `maestro.worker.*`" is not possible as written | Add an option on `TestWorkflowEnvironment` that passes defaults to the 15-arg constructor | S |
| Issue 37 | Ten public `WorkflowExecutor` constructors | **CONFIRMED** (10, counted). There is also a `null`-ambiguity trap | Builder; migrate the 2 production call sites; deprecate the rest | M (churn: 106 call sites) |

---

## 3.1 / Issue 30: recovery adoption not gated on capacity

### (a) Verdict: CONFIRMED [traced]

**The recovery pass never consults the governor.**
- `WorkflowExecutor.recoveryPass` (`core/engine/WorkflowExecutor.java:889-924`) pages `store.getRecoverableInstances(serviceName, after, 200)` until it reads a short page.
- `recoverPage` (`:935-985`) filters the instances running locally, makes one `heldElsewhere` read, then calls `resumeWorkflow` for every remaining candidate.
- Neither method references `taskQueueGovernor`.

**Each adoption takes the lock and moves the fence before the run has a slot.**
- `resumeWorkflow` (`:813-834`) calls `launchWorkflow`.
- `launchWorkflow` (`:2143-2305`) does `instanceLockManager.tryAcquire` (`:2160`), `store.getInstance` (`:2191`) and `adopt` → `store.updateInstance` with an epoch bump (`:2209`, `:2324-2345`).
- It then registers the run in `runningWorkflows` and starts the virtual thread (`:2280-2292`).
- Only inside the thread does `executeWorkflow` call `taskQueueGovernor.acquireWorkflowSlot` (`:2362`), which blocks on a fair `Semaphore` (`core/engine/TaskQueueGovernor.java:338-361`, `:370-376`).
- While the run waits, its lock sits in `heldLocks` and is renewed every TTL/3.
- Peers' `heldKeys` reads skip it as held elsewhere (`recoverPage` `:941-950`).
- `WorkflowExecutorConcurrencyTest.recoveryOfMoreRunsThanTheDefaultBoundReturnsWithoutWaitingForSlots` (`maestro-core/src/test/.../WorkflowExecutorConcurrencyTest.java:301-325`) pins this behaviour: 5 runs adopted under limit 1.

**Statement count per adoption [traced].**
- 1 `INSERT ... ON CONFLICT` (`PostgresDistributedLock` `ACQUIRE_SQL`), 1 `SELECT` (`getInstance`) and 1 `UPDATE` (the version CAS in `updateInstance`). That the CAS is a single statement on success is [inferred].
- Each page adds 1 recoverable-page read and 1 `heldKeys` read.
- 2,500 adoptions ≈ 7,500 + 26 statements. The handover's figure stands.

**Startup is serial on the runner thread [traced].**
- `StartupRecoveryRunner.run` (`starter/config/StartupRecoveryRunner.java:41-84`) calls `executor.recoverWorkflows(registrations)` synchronously at `:44`, then starts the timer poller, janitor, sweeper and recovery poller.
- `RecoveryPoller.pollLoop` sleeps *first* (`core/engine/RecoveryPoller.java:105`), so the synchronous pass is the only immediate one.
- [inferred, Spring Boot behaviour] `SpringApplication.run` publishes `ApplicationReadyEvent` / `ReadinessState.ACCEPTING_TRAFFIC` only after all runners return. Readiness therefore waits for the whole adoption loop.
- A store exception from the startup pass propagates out of an `ApplicationRunner` and fails application startup.

### (b) Harm to a production host

- **Load is concentrated on one node.**
  - After a deploy, the first node to run a pass locks every orphan of its service and runs 20 per queue.
  - Peers stay idle for that backlog until the hoarder finishes or dies.
  - Per the handover's arithmetic, a 5,000-run backlog takes ~500 s on one node versus ~250 s across two.
- **New finding: parked runs' wakes queue behind the hoard [inferred from traced code].**
  - A parked run gives its slot back for each park chunk and *re-takes* a slot after every wake or chunk expiry: `TaskQueueGovernor.parked` (`:220-235`) → `take` on the same fair semaphore, called from `SignalManager.parkChunk` (`core/engine/SignalManager.java:998-1016`).
  - The semaphore is FIFO, so a parked workflow woken by a signal, a timer or its 30 s re-check queues behind every hoarded recovered run already waiting.
  - On a node hoarding 5,000 runs, signal and timer latency for the node's *other* parked workflows rises to roughly the backlog drain time (minutes), not milliseconds.
  - This is not in Issue 30's text.
- **Hoarded locks outlive a crash.** If the hoarding node is killed, every hoarded lock must expire (30 s TTL) before peers can adopt, plus up to one 60 s poll. On graceful shutdown see Issue 29: waiters release only when they get a slot.
- **Readiness is delayed** by the serial startup pass. Assuming 1–5 ms per statement, 7,500 statements take ~8–40 s, and in that window the adopted runs' own replay reads compete for connections. A DB blip during it fails startup instead of being retried by the poller.

### (c) Recommended fix

**1. Reserve the slot before taking the lock.**
- Add `TaskQueueGovernor.tryReserveWorkflowSlot(taskQueue, workflowId)`. It is non-blocking and uses `slots.tryAcquire(0, SECONDS)`, the timed form, so it honours the fair FIFO and does not barge past waiting fresh starts. That is the same idiom `acquireActivitySlot` uses at `:259`.
- On success it registers the `RunSlot` with `held = true`. The later `acquireWorkflowSlot` in `executeWorkflow` is then a no-op through the fast path in `take` (`:339`).
- An unbounded queue always reserves.
- `recoverPage` calls it before `resumeWorkflow`. When it fails, the instance is skipped for capacity and counted in `RecoveryPass` (new field `skippedForCapacity`).
- Every non-launch exit after a reservation must call `releaseWorkflowSlot(workflowId)`. Those exits are:
  - `HELD_ELSEWHERE` at `:2161-2166`
  - the turned-terminal branch at `:2192-2196`
  - a lost adoption CAS at `:2210-2212`
  - the store-fault catch at `:2215-2217`
  - the factory or launch failure at `:2293-2298`
  - `resumeWorkflow`'s early returns
- This is simpler and exact compared with "check `availableWorkflowSlots`". Adopted threads take their slots asynchronously, so `availablePermits()` does not reflect adoptions made earlier in the same pass and a bare check over-admits.

**2. Re-poll early when capacity frees. Without this, the gate is a regression.**
- With only (1), a single node with a 5,000 backlog adopts ≤20 per queue per 60 s poll: about 4 h instead of ~500 s.
- When a pass skipped for capacity, the recovery poller should run its next pass as soon as a workflow slot is released. Concretely, the governor signals a condition on `give`, and the poller waits on it with `pollInterval` as the cap and a minimum spacing of about 1 s.
- Optionally end a pass early once a page adopts nothing because of capacity. This bounds the extra page reads.
- This is the Temporal model: poll only when you have a slot.

**3. Move startup recovery off the runner thread.**
- Give `RecoveryPoller` an immediate first pass (pass-then-sleep) and have `StartupRecoveryRunner` start the poller instead of calling `recoverWorkflows` inline.
- **`maestro.recovery.enabled=false` must still get a startup pass.** Run a one-shot pass on a virtual thread, and in that mode do **not** gate (or loop until no capacity skips). Otherwise a gated single pass would strand everything beyond 20 per queue until the next restart.
- `docs/operations.md` §2 currently cites the synchronous runner as why deploys recover in about 2 s. An immediate first pass preserves that number; update the wording.

**Policy change to call out.**
- `startWorkflow` cannot be gated: the caller just created the row, so fresh starts still queue FIFO.
- Gated recovery therefore yields to fresh work. Under sustained overload on *every* node, recovered runs wait until some node has idle capacity. Today they share the FIFO with fresh starts.
- That is defensible as backpressure, but it is a behaviour change to document.

**Risks.**
- Determinism: none. Admission happens before launch and writes no history.
- Fencing: unchanged. The reservation sits strictly outside `tryAcquire` and `adopt`, and a lost CAS releases both lock and slot.
- Control flow:
  - `tryAcquire(0, …)` can throw `InterruptedException`, which the governor turns into `ExecutorShutdownException` (an `Error`) on the **recovery-poller thread**.
  - Today `RecoveryPoller.pollLoop` catches only `Exception` (`:116`), so that `Error` would kill the poller and leak the reservation (see 3.5b).
  - Release in a `finally`, and let the poller treat a `MaestroControlFlowError` as "exit loop".
- `StoreUnavailableException` from `getInstance` or `adopt` after a reservation must release the slot (the catch at `:2215`).
- The test kit's `recover()` (`kit/TestWorkflowEnvironment.java:430`) returns `recoverWorkflows`'s count. Once the kit has limits (Issue 36), it would adopt ≤ free slots per call unless the kit loops.

**Tests.**
- Rewrite `WorkflowExecutorConcurrencyTest.recoveryOfMoreRunsThanTheDefaultBoundReturnsWithoutWaitingForSlots`: it pins the old behaviour.
- Add a two-executor test (shared `InMemoryWorkflowStore` + `InMemoryDistributedLock`, `concurrency: 1`, N orphans) asserting both nodes execute runs. That is Issue 30's "Done when".
- Add a single-node test that a backlog larger than the limit fully drains without waiting for `pollInterval` (this pins the re-poll).
- Extend `RecoveryPassIsolationTest` with reservation-release-on-skip for: held elsewhere, terminal, CAS lost, factory throws, store fault.
- Add `TaskQueueGovernorTest` cases for `tryReserve` FIFO and no barging.
- Change `StartupRecoveryRunnerTest` to assert the runner returns without adopting inline.
- Two-node Postgres coverage via `maestro-integration-tests` `MaestroEngineHarness`.

### (d) Size and dependencies

- Size M for (1)+(3); M/L with (2).
- Touches `WorkflowExecutor` (`recoverPage`, `launchWorkflow`), `TaskQueueGovernor`, `RecoveryPoller`, `StartupRecoveryRunner` and the ops docs.
- Conflicts with:
  - Issue 29: same `TaskQueueGovernor`.
  - 3.5b: same `RecoveryPoller.pollLoop`.
  - 3.6: same `WorkflowExecutor`.
  - Issue 37: if a new knob is added, such as an admission toggle.
  - Issue 36: the kit's `recover()` semantics.

---

## 3.5a: `MaestroHealthIndicator` goes DOWN when the pool is merely busy

### (a) Verdict: CONFIRMED in code [traced]; production harm is conditional [inferred]

- `MaestroHealthIndicator.health` (`starter/health/MaestroHealthIndicator.java:132-160`) runs `probeStore()` (`:184-200`).
- `probeStore()` submits `store.getInstance("__maestro-health-check__")` to a virtual-thread executor and waits `STORE_PROBE_TIMEOUT = 2 s` (`:97`, `:187`).
- A timeout yields `"timed out"`, and anything other than `"reachable"` makes the status `Status.DOWN` (`:139-143`).
- The `store` here is the raw `WorkflowStore` bean, which uses Maestro's `DataSource`: the `@MaestroDataSource` one or the host's primary pool (CLAUDE.md, `docs/operations.md` §13 "Pool sizing").
- When every connection is borrowed by workflow and activity slot holders or by the host's own traffic, the probe waits for a connection. With Hikari's default `connectionTimeout` of 30 s, it exceeds 2 s, and health reports DOWN with the database perfectly healthy.
- The documented pool sizing ("≥ max(20,20)+5", §13) leaves 5 connections of headroom, which are shared with the wake listener's permanent connection, the renewer, the pollers and NOTIFY.

### (b) Harm

- The indicator registers under the default `maestro` contributor (`MaestroHealthAutoConfiguration`), so it is in the **aggregate** `/actuator/health`.
- [inferred, Spring Boot defaults] Boot's `liveness` and `readiness` groups contain only `livenessState` and `readinessState` unless the host adds members.
- So the harm materialises only when a host points a probe at the aggregate endpoint, which is common, or adds `maestro` to a group:
  - **Liveness on aggregate:** a pod that is busy, i.e. doing exactly its job, gets killed. Its in-flight runs are abandoned to TTL. The restart then runs a recovery pass that adds load, which can cascade across the fleet.
  - **Readiness on aggregate:** a busy pod is pulled from the Service. This is milder, but it sheds HTTP traffic for a pool that is busy and not broken.
- I found no Maestro doc that tells hosts which probe group the indicator belongs in. Grepping `docs/` for liveness or readiness guidance found nothing beyond release-note mentions.
- [inferred] `future.cancel(true)` interrupts the probe's virtual thread. If that thread is mid-socket-read, the JDK's virtual-thread socket implementation closes the socket, and the pool then evicts that connection. This is benign but churns connections exactly when the pool is under pressure.

### (c) Recommended fix

1. Report a **probe timeout** as `Status.UNKNOWN` (or UP), with detail `store: "timed out"` kept. Keep DOWN for a definitive failure: the probe *threw* within the bound (refused connection, auth). Boot's aggregator orders DOWN > OUT_OF_SERVICE > UP > UNKNOWN, so UNKNOWN never drags the aggregate down.
2. Document in `docs/operations.md` and `docs/configuration.md`:
   - Maestro's indicator is a **readiness** signal at most, never liveness.
   - A dead poller (3.5b) is the only state for which a restart is the right remedy.
3. Optionally make the timeout a property. Not needed for correctness.
4. Do not add a dedicated health connection. It is overkill and adds a pool member.

**Risks.**
- A genuinely hung database no longer shows DOWN on this indicator. That is acceptable: a hung shared database affects every pod equally, so pulling all pods fixes nothing. Hikari's own `db` indicator still reports.
- There are no determinism, fencing or control-flow implications.

**Tests.** Extend `MaestroHealthIndicatorTest` (it already has a `DelegatingWorkflowStore` helper):
- A slow store gives UNKNOWN or UP with detail "timed out".
- A throwing store still gives DOWN.

### (d) Size and dependencies

- Size S. Independent of the other findings.
- Same file as 3.5b's health wiring only if the renewer gets a health detail (see below).

---

## 3.5b: pollers catch `Exception` only, so an `Error` kills them

### (a) Verdict: CONFIRMED, and wider than the handover states [traced]

| Loop | Catch | File:line | Effect of an `Error` |
|---|---|---|---|
| `TimerPoller.pollLoop` | `catch (Exception e)` | `core/engine/TimerPoller.java:156-180` (`:171`); per-timer `:194` | Thread dies. Health shows DOWN (`timerPollerRunning=false`) |
| `RecoveryPoller.pollLoop` | `catch (Exception e)` | `core/engine/RecoveryPoller.java:102-122` (`:116`) | Thread dies. Health shows DOWN |
| `SignalJanitor.sweepOnce` / `sweepLoop` | `catch (RuntimeException e)` | `core/engine/SignalJanitor.java:154`, `:162-180` | Thread dies. **Not in health** |
| `RetentionSweeper` | `catch (RuntimeException e)` ×3 | `core/engine/RetentionSweeper.java:229,257,274`; loop `:281` | Thread dies. Health shows DOWN |
| **Instance-lock renewer** `renewLoop` / `renewPass` | `catch (Exception e)` around `renewAll` only | `core/engine/WorkflowInstanceLockManager.java:341-397` (`:375`) | **Renewer dies permanently.** `renewerStarted` stays `true`, so it is never restarted (`:318-339`). **Not in health** |

Plausible `Error`s:
- `OutOfMemoryError` or `StackOverflowError`.
- A `LinkageError` (`NoClassDefFoundError` and similar) on a rarely used code path.
- An `AssertionError`.
- A control-flow `Error` thrown where none is expected, e.g. `ExecutorShutdownException` from a governor wait if Issue 30's reservation is added (see 3.1).

Which paths can carry one:
- Recovery: `launchWorkflow` deliberately rethrows `Error` (`WorkflowExecutor.java:2215-2217`, `:2293-2298`). Its factory catch is `RuntimeException`-only (`:2253-2257`), so a factory `Error` reaches the poller.
  - [inferred] Spring usually wraps a constructor's `Error` in `BeanCreationException`. A hand-wired factory (non-Spring embedder) does not.
- `emit` wrappers contain only `RuntimeException` (`:3210-3217`, `WorkflowInstanceLockManager.java:437-444`).

[inferred, JDK behaviour] A virtual thread's uncaught `Error` goes to the default handler, which prints to `System.err` and not to SLF4J. In log-aggregated deployments the death may not appear in the application's logs at all.

### (b) Harm

**Timer poller.**
- On the leader, its lease (15 s) lapses and a peer takes over, costing ~15 s of timer latency.
- On a single node, or if the `Error` recurs on each new leader, durable timers stop firing.
- Parked `sleep()` still self-checks every 30 s, but `awaitSignal` deadlines rely on `selfFire`, so this degrades rather than stops. The dependence on `selfFire` is [inferred].
- Health turns DOWN. If liveness points at aggregate health, the pod restarts. That is a crude remedy that at least makes the failure visible.

**Recovery poller.** This node stops adopting orphans; peers still poll.

**Lock renewer (worst, and unflagged).**
- Every lock this node holds expires after ≤30 s TTL.
- Peers' recovery adopts those runs and bumps the epoch. This node's runs are then fenced (`LEASE_FENCED`), and new adoptions here have no renewer either.
- The result is permanent churn: every run on the node loses its lock 30 s after acquiring it, and in-flight activities may execute twice.
- Fencing preserves correctness, but nothing reports it. Health shows UP, the node keeps adopting, and the only signal is ERROR "lock lost" lines.

**Janitor and sweeper.** Purging stops silently (janitor) or health turns DOWN (sweeper).

### (c) Recommended fix

- Use one loop-guard pattern in all five loops:

  ```java
  catch (MaestroControlFlowError e) { throw e; }   // CLAUDE.md rule; in a loop this exits it
  catch (Throwable t) { logger.error("... — will retry next cycle", t); /* existing backoff sleep */ }
  ```

  - `InterruptedException` handling stays ahead of the broad catch.
  - In the renewer, put the guard *around `renewPass()`* inside `renewLoop` and keep the existing `renewAll` catch. A thrown pass then keeps every handle, which is the existing "nothing known, nothing lost" rule.
- Whether to swallow `VirtualMachineError`: I recommend swallowing it with backoff. A silently dead daemon loop is strictly worse than a logged retry, and the JVM's own OOM handling (`-XX:+ExitOnOutOfMemoryError`) is the host's choice.
- Optional: expose renewer liveness in `MaestroHealthIndicator` (`instanceLockRenewerRunning`), using the same never-started/disabled semantics as the pollers.
- `StoreUnavailableException` is a `RuntimeException` and is already caught. Nothing changes for it.

**Tests.**
- `TimerPollerTest`: a store throwing `new Error("boom")` once still fires a later timer.
- `RecoveryPassIsolationTest` or a new `RecoveryPollerTest`: an `Error` in one pass, the next pass still runs.
- A new `WorkflowInstanceLockManager` test: `renewAll` throws an `Error` once, a later pass renews, and `isRenewerAlive` stays true.
- `BackgroundLoopStartShutdownRaceTest`: confirm `stop()` still exits promptly. A `MaestroControlFlowError` must still end the loop.

### (d) Size and dependencies

- Size S.
- Touches `RecoveryPoller` (also touched by 3.1) and `TimerPoller`, `SignalJanitor`, `RetentionSweeper`, `WorkflowInstanceLockManager`.
- Do it **before or with 3.1**, because 3.1's reservation can raise an `Error` on the poller thread.

---

## 3.6 + Issue 29: shutdown can outlast a 30 s k8s grace; slot waiters not interrupted

### (a) Verdict: CONFIRMED [traced]; the handler's Javadoc is wrong

**Phase.** I disassembled the Spring Boot 4.0.5 and Spring Framework 7.0.6 jars from the Gradle cache.
- `GracefulShutdownHandler.getPhase()` returns `Integer.MAX_VALUE - 1024 = 2147482623` (`starter/config/GracefulShutdownHandler.java:63-65`).
- `WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE` is also `2147482623`, and the servlet `WebServerStartStopLifecycle.getPhase()` is `2147481599` (MAX−2048).
- So Maestro **shares the web graceful-shutdown phase**, and it stops **before** the web server stops, because higher phases stop first.
- The class Javadoc (`:22-25`) says it "stops after the web server (phase MAX-2048)". That is backwards.

**Blocking.**
- `stop(Runnable)` calls `stop()` synchronously and then the callback (`:43-56`).
- `DefaultLifecycleProcessor$LifecycleGroup.stop()` (disassembled) calls `doStop` for each member *sequentially* and only then `latch.await(timeout-per-shutdown-phase)`.
- A synchronous member therefore blocks the lifecycle thread for its full duration, and the phase timeout does not bound it.
- [cannot tell statically] Member order within the phase. If Maestro is called first, the web server's graceful drain starts only after Maestro returns, so the two run in series.

**`WorkflowExecutor.shutdown()`** (`core/engine/WorkflowExecutor.java:1805-1870`) runs these steps in series:
1. Stop the timer poller, recovery poller, janitor and sweeper, each `join(5 s)`: `TimerPoller.java:130-139`, `RecoveryPoller.java:76-86`, `SignalJanitor.java:~110-120`, `RetentionSweeper.java:155-166`. That is up to 20 s if loops are stuck in a JDBC call that ignores interrupt; typically about 0.
2. `parkingLot.shutdown()` and `requestShutdown()` on every run.
3. Join every running thread against one deadline of `shutdownTimeout`, default **30 s** (`:1842-1854`). The constant `DEFAULT_SHUTDOWN_TIMEOUT` is at `:121`, and `maestro.shutdown.timeout` also defaults to 30s (`docs/configuration.md:627`).
4. `instanceLockManager.close()`.
5. `lifecycleEventPublisher.shutdown()`, which waits up to `SHUTDOWN_TIMEOUT = 5 s` (`LifecycleEventPublisher.java:54`, `:117-131`).

Worst case is about 55 s from Maestro alone. The realistic case, with one activity running past the drain, is about 30 s from Maestro, which equals Kubernetes' default `terminationGracePeriodSeconds`. Before it come Kafka container stops and any `preStop` hook; after it come the web drain (if in series) and bean destruction.

**Issue 29: nothing interrupts slot waiters [traced].**
- The only `.interrupt()` calls in `core/` are on the five background loops' own threads (grep).
- `TaskQueueGovernor.acquire` and `take` block in `Semaphore.acquire()` (`:370-376`, `:338-361`). The `interrupted(...)` → `ExecutorShutdownException` conversion (`:379-383`) is unreachable in production.
- `RunCancellation`'s Javadoc ("Why a latch, not `Thread.interrupt()`", `core/context/RunCancellation.java:87-100`) documents that the engine deliberately never interrupts workflow threads.
- The post-acquire checks do exist: `executeWorkflow` `:2363-2378`, and `ActivityInvocationHandler` `:613-627` on `waited`.

### (b) Harm

**SIGKILL mid-drain [inferred].**
- In-flight activities are cut and re-run on adoption. Idempotency is already required, so this is not a correctness break.
- Locks of runs that did not unwind persist until TTL (30 s), then peers adopt on their next poll (≤60 s). Failover goes from the ~2 s graceful path (`docs/operations.md` §2) to 30–90 s for those runs.
- Queued lifecycle and admin events are lost, and Hikari and the wake listener are not closed cleanly.
- No durable state is lost.

**Issue 29's marginal cost [inferred from traced code]** is narrower than the issue suggests.
- When *any* slot holder finishes, its slot passes to the next waiter. That waiter immediately fails the post-acquire check, throws `ExecutorShutdownException`, releases the slot in `finally`, and the chain unwinds in microseconds.
- So waiters extend the drain only when **every** slot holder outlives the drain, and then the drain times out anyway.
- The real cost is **lock release**. Waiters that never get a slot keep their instance locks until the renewer stops and TTL expires.
- Combined with Issue 30's hoarding, thousands of locks stay unavailable to peers for 30 s+ after a graceful shutdown, where they could be released at once.

**Same-phase side effect [inferred].** While Maestro is draining, the web server may still accept requests. A request that calls `startWorkflow` gets `IllegalStateException("...shutting down")` (`:743-746`), i.e. a 500.

### (c) Recommended fix

**Issue 29: close the governor; do not use interrupts.**
- Add `TaskQueueGovernor.shutdown()`. It sets a volatile `closed` flag, then releases **one** permit on every bucket's workflow and activity semaphore.
- `acquire` and `take` check `closed` before waiting and again after acquiring. If it is set, they give the permit straight back and throw `ExecutorShutdownException`.
- The single poison permit passes along the FIFO queue: each waiter wakes, gives it back and throws, which wakes the next.
- `WorkflowExecutor.shutdown()` calls it right after `parkingLot.shutdown()`.
- Why not interrupts, as the issue suggests:
  - The governor's `interrupted()` *restores* the interrupt flag (`:380`).
  - The waiter then unwinds through `executeWorkflow`'s `finally`, which releases the instance lock over JDBC.
  - [inferred, JDK/pgjdbc/Hikari behaviour] With the flag set, a virtual thread's socket op or pool borrow fails. The lock release would fail and the lock fall back to TTL, defeating the point.
  - Tracking waiters and interrupting them also races with a waiter that has just acquired and is entering code that must not see an interrupt.
- The poison-permit design avoids all of that and reuses the existing control-flow `Error`.
- Over-release after shutdown is harmless: no new runs start. Never `release(Integer.MAX_VALUE)`, because `Semaphore` throws `Error("Maximum permit count exceeded")` on overflow.

**3.6: one budget, asynchronous stop, documentation.**
1. Compute one deadline at the top of `shutdown()` from `shutdownTimeout`, and pass the remaining time to every bounded step.
   - Interrupt all four loops first, then join them against the shared deadline instead of 4×5 s in series.
   - Bound the publisher flush by `min(5 s, remaining)`.
2. Implement `GracefulShutdownHandler.stop(Runnable callback)` asynchronously: run `executor.shutdown()` on a thread and call `callback` from it. Maestro's drain then overlaps the web drain, and the phase timeout (`spring.lifecycle.timeout-per-shutdown-phase`) bounds the lifecycle thread.
   - [inferred] If Maestro overruns the phase timeout, beans (the `DataSource`) are destroyed under the drain. Late writes then raise `StoreUnavailableException`, which latches `STORE_UNAVAILABLE` and abandons the run as recoverable. That is correct but noisy, so recommend `maestro.shutdown.timeout` < the phase timeout.
3. Fix the Javadoc's phase claim.
4. Document the arithmetic: `terminationGracePeriodSeconds ≥ preStop + max(web drain, maestro.shutdown.timeout) + ~10 s`. Either lower the default `maestro.shutdown.timeout` to ~20 s or state that 30 s needs a 45–60 s grace.

**Risks.**
- Determinism and fencing: none. Shutdown writes nothing, and runs that stand down stay recoverable.
- Control flow: `ExecutorShutdownException` is already handled as a recoverable stand-down in every path that can wait on the governor: workflow start, activity slot, and `parked` reacquire (Javadoc `:216-218`).
- The poison path must also cover `parked`'s `take` after a `TimeoutException`. It does, because `take` is shared.

**Tests.**
- `WorkflowExecutorShutdownTest`: add a case with limit `(1,1)`, one long activity holding the slot past the drain, and one queued run. Assert the queued run exits within the drain and its instance lock is released *before* the TTL (lock row deleted). This is Issue 29's "Done when", plus lock release.
- `WorkflowExecutorShutdownTest.shutdown_withConfiguredTimeout_returnsAfterTheConfiguredWaitNotTheDefault` already exists. Extend it to assert total `shutdown()` time ≤ timeout + ε with stuck pollers.
- `TaskQueueGovernorTest`: poison-chain cases for 1 and N waiters, for both workflow and activity pools.
- **No `GracefulShutdownHandler` test exists.** Add one: `stop(callback)` returns immediately and the callback fires once `shutdown()` completes.

### (d) Size and dependencies

- Issue 29: S, in `TaskQueueGovernor` plus one line in `shutdown()`.
- 3.6: S, in `WorkflowExecutor.shutdown`, `GracefulShutdownHandler` and the docs.
- Both touch `WorkflowExecutor.shutdown()`, so do them together. They share `TaskQueueGovernor` with 3.1.
- Do Issue 29 before Issue 36 with production defaults (see below).

---

## 3.9 / Issue 12: per-parked-workflow steady load

### (a) Verdict: CONFIRMED accurate [traced]

**Per wake-recheck chunk of a parked `awaitSignal`:**
- `parkChunk` (`SignalManager.java:998-1030`), then `standDownIfTerminated`, which is an instance read (`:1468-1500`).
- Then the loop reads the signals (`getUnconsumedSignals`) and the deadline row (`findTimer`, `:918-922`).
- That is 3 reads per 30 s chunk.
- `sleep()` makes 2 (`DefaultWorkflowOperations.java:~489`, `:534-540`).

**Per recovery pass:** pages of 200 over every active row of the service, with payload columns (`WorkflowExecutor.java:899-910`).

`docs/operations.md` §13 states all of this as arithmetic. Issue 12's "Partly addressed" note matches the code. The renewal (`renewAll` per pass) and the paged scan are as described.

**One addition [traced]:** every chunk also re-takes a workflow slot through `governor.parked`. Re-check load therefore also consumes slot time (§13 notes ≈ P×d/30 s slots), and re-checks queue behind any slot backlog (see 3.1(b)).

### (b) to (d)

- Known and documented; no new harm beyond 3.1's slot-FIFO interaction.
- A fix needs a benchmark, per Issue 12's "Done when". Size L. Out of scope.

---

## Issue 36: test kit cannot apply production's default concurrency limits

### (a) Verdict: CONFIRMED [traced]

- `TestWorkflowEnvironment.Node` builds its executor with the 14-arg `InstantSource` constructor, passing `Map.of()` for limits (`kit/TestWorkflowEnvironment.java:581-584`).
- That constructor delegates with `defaultTaskQueueLimits = null` (`WorkflowExecutor.java:552-571`), and `null` plus an empty map gives `TaskQueueGovernor.UNBOUNDED` (`:654-656`).
- The only factories are `create()` and `create(ObjectMapper)` (`:178`, `:188`), and neither takes limits.
- **Correction to the issue text:** "`@MaestroTest` can bind it from `maestro.worker.*`" is not possible as written. `@MaestroTest` is a plain JUnit extension with **no attributes** (`kit/MaestroTest.java`). `MaestroTestExtension` calls `TestWorkflowEnvironment.create()` and has no Spring environment to bind from (`MaestroTestExtension.java:30`). It would need annotation attributes.

### (b) Harm

User tests never queue for a slot, so these are invisible:
- the pitfall-11 same-queue deadlock
- slot-starved re-checks
- Issue 29 and Issue 30 behaviour

A deadlock that production reaches at 20/20 passes CI. This is a test-fidelity gap, not a runtime harm.

### (c) Recommended fix

- Add `TestWorkflowEnvironment.create(Options)`, or a small builder, with `defaultLimits(int workflows, int activities)` and `queueLimits(Map)`.
- Pass them to the canonical 15-arg constructor (`WorkflowExecutor.java:604-620`), which already takes both `defaultTaskQueueLimits` and `InstantSource`. No new engine API is needed.
- Optionally add `@MaestroTest(defaultConcurrency=…, defaultActivityConcurrency=…)`.
- Default: matching production (20/20) is the honest choice, but only **after Issue 29**.
  - `recover()` calls `retiring.executor.shutdown()` synchronously (`:416`), and the kit's `SHUTDOWN_TIMEOUT` is 30 s (`:129`).
  - With limits on and Issue 29 unfixed, a test that crashes a node with queued runs could block up to 30 s in `recover()`.
  - If Issue 30's gate lands, `recover()`'s single `recoverWorkflows` call adopts at most free capacity, so the kit must loop or rely on the poller (which the kit does not start).
- Determinism, fencing and control flow are unaffected.

**Tests.** `TestWorkflowEnvironmentTest`: Issue 36's "Done when". A parent activity `startAndWait`s a same-queue child: with `(1,1)` it times out, and unset or `(0,0)` it completes.

### (d) Size and dependencies

- Size S.
- Must follow Issue 29 if it defaults to 20/20; interacts with Issue 30's `recover()` semantics.
- Independent of Issue 37: it can use the canonical constructor now, or the builder if 37 lands first.

---

## Issue 37: `WorkflowExecutor` has ten public constructors

### (a) Verdict: CONFIRMED [traced]

There are 10 constructors (`grep -c "^    public WorkflowExecutor("`), at `WorkflowExecutor.java:172`, 202, 238, 289, 346, 401, 457, 496, 552 and 604, with 6, 8, 9, 11, 12, 13, 14, 7, 14 and 15 arguments.

Production call sites:
- `starter/config/MaestroAutoConfiguration.java:133-138` uses the 14-arg form with `defaultTaskQueueLimits`.
- `kit/TestWorkflowEnvironment.java:581` uses the 14-arg form with `InstantSource`.
- Two *different* 14-arg overloads differ only in their last parameter type (`@Nullable TaskQueueLimits` vs `InstantSource`). A caller passing a literal `null` last gets an ambiguity compile error, and a cast silently picks one.
- Neighbouring same-type parameters (`Duration shutdownTimeout, Duration wakeRecheckInterval`; `String serviceName, String lockKeyPrefix`) compile when swapped.

`new WorkflowExecutor(` appears **106 times** (2 main, 104 test/sample; `grep`, excluding `build/` and `.claude/worktrees`).

### (b) Harm

- No runtime harm.
- It becomes permanent public API on the first Maven Central publish, which per handover §5 has not happened.
- It already caused Issue 36: the kit and the starter picked different constructors.

### (c) Recommended fix

- Add `WorkflowExecutor.builder(store, serializer, serviceName)` with named setters and the engine's defaults:
  - lock prefix and TTL
  - lifecycle events on
  - 30 s shutdown and 30 s re-check
  - `EngineObserver.NOOP`
  - no limits
  - `InstantSource.system()`
- Make the canonical constructor private, taking the builder.
- Move the starter and the kit onto it.
- Since nothing has shipped, **remove** the constructors rather than deprecate them. Migrate the 104 test call sites mechanically, or via one per-test-module helper.
- If removal is too much churn for one PR, deprecate them now and remove them before release.
- A `BuilderDefaultsTest` pins each default against the constants (`DEFAULT_SHUTDOWN_TIMEOUT`, `SignalManager.DEFAULT_WAKE_RECHECK_INTERVAL`, `WorkflowInstanceLockManager.DEFAULT_*`).
- Risks: none to behaviour. The validation in the canonical constructor (`:621-632`) must stay on the single build path.

### (d) Size and dependencies

- Size M, mostly mechanical churn. The builder itself is S.
- Touches the top of `WorkflowExecutor` and 40+ test files, so it conflicts textually with everything above.
- **Land it first**, or strictly last. Otherwise 3.1 (a possible admission knob) and 3.6 (a possible shutdown budget) each add constructor overloads 11 and 12.

---

## Recommended sequencing (files overlap)

1. **Issue 37** (builder): removes the constructor churn before anything adds settings.
2. **3.5b** (loop guards): small. It must precede 3.1, because 3.1 can raise an `Error` on the recovery-poller thread.
3. **Issue 29 + 3.6** together, both in `TaskQueueGovernor` and `WorkflowExecutor.shutdown`.
4. **Issue 30 / 3.1**: governor reservation, recovery re-poll, startup pass off the runner thread.
5. **Issue 36**: the kit can now safely default to production limits.
6. **3.5a** (health semantics and docs): independent; can go anywhere.

## Surprises (not in the handover)

1. **The lock renewer has the 3.5b defect and nothing watches it.** `WorkflowInstanceLockManager.renewLoop` dies on an `Error` and never restarts (`renewerStarted` stays true). Every lock on the node then expires 30 s after acquisition, forever, while health reports UP.
2. **Hoarded recovery waiters delay unrelated parked workflows' wakes.** The fair-FIFO slot reacquire after every park chunk queues behind the hoard (3.1(b)).
3. **`GracefulShutdownHandler`'s Javadoc misstates its phase.** It shares MAX−1024 with the web graceful drain and stops before the web server (verified against the Boot 4.0.5 jar). A synchronous `stop(Runnable)` defeats the phase timeout.
4. **Gating alone would regress single-node drain** from ~500 s to ~4 h for a 5,000 backlog. Issue 30's suggested fix needs a capacity-triggered re-poll, and `maestro.recovery.enabled=false` must keep an ungated startup pass.
5. **Issue 29's suggested interrupt approach is unsafe here.** The governor restores the interrupt flag, so the waiter's own lock release in `finally` would likely fail (an inference about JDK/pgjdbc/Hikari behaviour). Use a poison permit instead.
6. **Issue 36's "bind from `maestro.worker.*`" is not possible.** `@MaestroTest` has no attributes and no Spring context.
