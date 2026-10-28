# Release Notes — Maestro

## Unreleased

### Changed — One workflow object per run

- **Before (F1):** the engine held one workflow object per workflow *type* — under the Spring starter, the `@DurableWorkflow` singleton bean — and every concurrent instance of that type ran its workflow method on that same object. Instance fields written by one run were visible to, and overwritten by, every other run, so a `@QueryMethod` returned whichever run wrote last rather than the run being queried, and a re-adopted run inherited the stale fields of the previous one.
- **Now:** `WorkflowRegistration` carries a `Supplier<Object> workflowFactory` in place of `Object workflowImpl`, and `WorkflowExecutor.startWorkflow`/`resumeWorkflow` take that factory instead of an object. The executor calls it exactly once per run — on `startWorkflow`, on every re-adoption by `recoverWorkflows`/`resumeWorkflow`, and on `retryWorkflow` — on the launching thread before the run's virtual thread exists. A `RuntimeException` from the factory surfaces to the caller as the new `WorkflowInstantiationException` (cause preserved; a `MaestroException`), the instance lock is released, and a fresh start emits no `WORKFLOW_STARTED` event or `workflowStarted` callback (both now fire only once the object exists); a row already written stays active for the recovery poller. `recoverWorkflows` catches only `WorkflowInstantiationException` per instance — logged at `ERROR`, instance skipped — so one workflow type whose objects cannot be built no longer starves every other type's recovery; any other exception still ends the pass (R44). The object it returns is held for the life of that run and is the receiver for every `@QueryMethod` invocation against it; it is never reused across runs. `TestWorkflowEnvironment` builds and wires a fresh object per run. **Documented limitation:** queries are answered only by the node whose executor currently holds the run (the instance-lock holder); a workflow parked, running or compensating on another node, or terminal, throws `WorkflowNotQueryableException` — cross-node query routing is out of scope. **Breaking for direct core users:** pass `() -> new MyWorkflow(...)` (or any per-run factory) where an object was passed before. Pinned by `WorkflowExecutorPerRunInstanceTest` and `MultiNodePerRunQueryIT`.
- **Spring starter:** scanned `@DurableWorkflow` classes are registered as **prototype** beans and each registration's factory is `getBean(beanName)`, so every run gets a new, fully wired object (dependency injection and `@ActivityStub` proxies; the proxies are built once per workflow class and shared). Discovery no longer instantiates workflow beans at startup, but it still validates every workflow class's `@ActivityStub` fields against the context (`ActivityStubBeanPostProcessor.prepare`), so a missing activity bean, an invalid stub field or an unparsable `startToCloseTimeout` still fails context startup (the timeout error now names the field and value). Only a genuine dependency-injection failure — a throwing constructor or an unresolvable `@Autowired` dependency — surfaces at the first run, as `WorkflowInstantiationException` (cause: the Spring `BeanCreationException`). **Breaking for workflow beans you declare yourself:** a `@DurableWorkflow` bean in any scope other than prototype (`@Component` or `@Bean` without `@Scope("prototype")`, or a request/session/custom scope) now fails context startup with `"@DurableWorkflow bean '…' (…) must be prototype-scoped …"` — remove `@Component` (the starter registers the class itself) or add `@Scope("prototype")`. Pinned by `WorkflowRegistrarPrototypeTest` and `MaestroClientTest.PerRunState`.

### Changed — Postgres transport counts an attempt at claim time and retires stale claims with no budget (Issue 27)

- **Before:** `maestro-messaging-postgres` incremented `attempts` only when it recorded a handler failure. If the status write after a handler — `COMPLETED`, the enabled-redelivery `PENDING`/`DEAD_LETTER`, or the disabled-redelivery `FAILED` — itself failed (database down), the row stayed `PROCESSING` and the five-minute stale reclaim re-invoked the handler with no bound: a second attempt despite `maestro.messaging.redelivery.enabled=false`'s "exactly one attempt", and, with the store failing every time, a loop.
- **Now:** both claim statements set `attempts = attempts + 1`, so the attempt is durable before the handler runs. A retire sweep, run before the claim in the same transaction in both poll loops, moves a stale `PROCESSING` row whose budget is spent (`attempts >= max-attempts`; `attempts >= 1` with redelivery disabled) straight to `DEAD_LETTER` — in both redelivery modes, because an "outcome unknown" row always needs triage and `FAILED` is purged by the cleaner and hidden from `listDeadLetter*` — with `last_error = "finalisation write lost after attempt <n>; outcome unknown — retired without re-invoking the handler"`, logged at `ERROR`. The sweep uses `FOR UPDATE SKIP LOCKED`, so concurrent sweeps on several nodes skip each other's rows instead of waiting or deadlocking, and the claim's stale-reclaim branch independently requires `attempts < max-attempts` (`< 1` disabled), so a row a sweep skipped is never re-invoked. A stale claim with budget left is still re-claimed and re-invoked — the documented double delivery under a double fault; handlers must be idempotent. The rule is the same for all three status-write sites. Retired rows are replayed deliberately (see `docs/configuration.md`, "Retired rows"). Pinned by `PostgresWorkflowMessagingTest.StatusWriteFailureTests`.
- **Behaviour change:** a consumer that crashes mid-handler now **consumes an attempt** (previously a crash did not count). This is deliberate: a poison message that crashes the JVM now dead-letters after `max-attempts` crashes instead of looping forever, and it is what makes at-most-once provable with redelivery disabled. `attempts` on `DEAD_LETTER`/`FAILED` rows and in `DeadLetterMessage` means the same as before for handler failures — the number of attempts made.

### Fixed — Valkey wake subscription is acknowledged before parking

- **Before:** `ValkeySignalNotifier.subscribe` used Lettuce's async pub/sub command, so it returned before the server had registered the subscription; a signal published in that window was missed until `maestro.signal.wake-recheck-interval`, making `SignalManager`'s check-then-subscribe race closure racy.
- **Now:** `subscribe` returns only once the server has acknowledged the subscription (bounded 5 s). The wait is deliberately not Lettuce's synchronous command (whose 60 s default timeout would stall every `awaitSignal` entry past the 30 s shutdown drain during a Valkey brownout): on a missing acknowledgement or a failed command the callback is removed, a `WARN` names the `maestro.signal.wake-recheck-interval` fallback, and the workflow parks normally — nothing is thrown into workflow code. `unsubscribe` removes the callback and issues the command without waiting. Concurrent waiters of one workflow on one node share the first waiter's acknowledged subscription (`SignalManager`): a later joiner waits on it, under the same bound, rather than parking before it is acknowledged. The remaining gap is a publish during a pub/sub disconnect (no replay on reconnect), covered by the recheck interval. Pinned by `ValkeySignalNotifierTest.publishImmediatelyAfterSubscribe_isReceived_everyTime` and `ValkeySignalNotifierAckTimeoutTest`.

### Added — signal janitor and database-assigned `received_at` (C14)

- **Before:** a signal row's `received_at` was the JVM clock of the node that ingested it, and `getUnconsumedSignals` orders by it — so two same-name signals delivered through different nodes could be consumed in clock-skew order rather than arrival order. Separately, unconsumed signals addressed to a workflow that had already finished were never purged, so the signal table grew without bound.
- **Now — clock:** new store migration **`V5__signal_received_at_default.sql`** gives `maestro_workflow_signal.received_at` a `DEFAULT now()`, and `AbstractJdbcWorkflowStore.saveSignal` omits the column, so the database clock assigns it and the caller's `WorkflowSignal.receivedAt()` is ignored. The `WorkflowStore.saveSignal` Javadoc now requires this of every shared-database store (the in-memory test store keeps the caller's value — one process, one clock). **Upgrade:** V5 is a metadata-only `ALTER`; a deployment with a custom `table-prefix` must add the same default to its prefixed signal table, or the `NOT NULL` column rejects every signal insert. Pinned by `PostgresWorkflowStoreTest.SignalClockTests`.
- **Now — janitor:** a framework-free `SignalJanitor` in `maestro-core`, started by `WorkflowExecutor.startSignalJanitor(interval, retention)` (the starter calls it from `StartupRecoveryRunner`) and stopped by `shutdown()`, runs on a virtual thread `maestro-signal-janitor` on every node — the purge is one idempotent statement, so there is no leader election. Each sweep deletes the unconsumed signals of workflows that reached `COMPLETED` or `TERMINATED` more than `maestro.signal.terminal-retention` (default `7d`) ago, every `maestro.signal.janitor-interval` (default `1h`). **`FAILED` workflows keep their signals** — `retryWorkflow` may still consume them (Issue 19); orphaned (pre-delivery) signals and consumed rows are never touched. A store error is logged at `WARN` and retried next interval. Pinned by `InMemoryWorkflowStoreTest`/`PostgresWorkflowStoreTest.SignalJanitorDeleteTests` (incl. `keepsSignalsOfFailedInstances`), `SignalJanitorTest`, `WorkflowExecutorTest.startSignalJanitor_exposesSettingsAndStopsOnShutdown`, `MaestroPropertiesBindingTest` and `MaestroAutoConfigurationConfigSeamsTest.signalJanitorSettingsReachTheExecutor`.
- **Breaking SPI addition for custom stores:** `WorkflowStore.deleteUnconsumedSignalsForTerminalInstancesBefore(Instant cutoff)` is **abstract, with no default**. Migration: one `DELETE ... WHERE consumed = FALSE AND workflow_instance_id IN (SELECT id FROM <instances> WHERE status IN ('COMPLETED','TERMINATED') AND completed_at < ?)` (see `AbstractJdbcWorkflowStore`); stores that extend `AbstractJdbcWorkflowStore` / `PostgresWorkflowStore` already have it.
- **Also (R34):** in `AbstractJdbcWorkflowStore.createInstance`/`completeInstance`, when a statement fails and the `rollback()` that follows also fails, the connection is now aborted instead of having auto-commit restored — `setAutoCommit(true)` on a still-open transaction would have committed the half-done write. Pinned by `AbstractJdbcWorkflowStoreUnitTest.FailedRollbackTests`.

### Changed — `awaitSignal` deadlines are durable timers (C12/F4)

- **Before:** `awaitSignal(name, type, timeout)` kept its deadline in memory (`Instant.now().plus(timeout)`). A crash, a redeploy or a recovery on another node re-armed the full timeout from scratch, so an await could outlive its deadline indefinitely across restarts; the timer poller never saw it, `cancelTimer` could not reach it, and `TestWorkflowEnvironment.advanceTime()` could not fire it, so a test of a 7-day approval timeout had to wait 7 days of wall clock or be restructured.
- **Now:** an await that has to park memoizes its deadline and persists it as a timer row. Event layout: a signal already waiting at entry is consumed at the await's slot `seq` exactly as before (`SIGNAL_RECEIVED`, no timer row); a zero or negative timeout with no signal records `SIGNAL_TIMEOUT` at `seq` and writes **no** timer row; an await that parks records `TIMER_SCHEDULED` at `seq` (step `$maestro:awaitSignal:<name>`, payload `{signalName, timerId, timeout, deadline}`), saves the row `await-signal-<seq>` with `fireAt = deadline` (row first, then the memo, like `sleep()`), and records its outcome — `SIGNAL_RECEIVED` or `SIGNAL_TIMEOUT` — at `seq+1`. Both slots are checked by the replay drift guard. The timer poller fires the row like any `sleep()` timer; `WorkflowExecutor.fireTimer`/`cancelTimer` wake the parked await, and a fired **or cancelled** row ends the wait with `SignalTimeoutException` — unless a signal is already visible, which always wins while the outcome slot is open (a consumed signal best-effort cancels the row). A replay resumes from the memoized deadline and the existing row, never from `Instant.now()` and never with a second row; an await past its deadline whose row is still `PENDING` fires the row itself. `TestWorkflowEnvironment.advanceTime()` now drives these deadlines (step just past the duration — the test clock starts when the environment is created). Pinned by nine `SignalManagerTest` cases and `TestWorkflowEnvironmentTest.advanceTimeFiresAwaitSignalTimeout`/`signalBeforeTheAdvancedDeadlineWins`.
- **Hardening (plan 04 final wave):** a store error inside the timeout decision no longer fails the run — the deadline self-fire is best-effort (like the cancel after a consumed signal), and a failed post-deadline signal re-read parks one more `wake-recheck-interval` and decides again, so a DB blip at the deadline can no longer record `WORKFLOW_FAILED` and run compensations for work that never failed (R39; `AwaitDeadlineStoreErrorTest`). The live path reuses an existing row for its `await-signal-<seq>` id and that row's `fireAt` instead of writing a second row under the same id (a crash between the row save and the memo, or a runner race — M2). A replay whose deadline row is **missing** no longer times out at once: it re-arms the row at the memoized deadline and keeps waiting (a missing row is not a spent deadline). `fireTimer`/`cancelTimer` no longer store a stray `:timer:` wake permit for `await-signal-*` rows (nobody parks there). Concurrent waiters of one workflow on one node share the first waiter's wake subscription and wait — bounded 5 s — for it to settle before parking.
- **Behaviour change — retry after a timeout:** retrying a workflow that `FAILED` because an await timed out (Issue 19) removes the `SIGNAL_TIMEOUT` memo but keeps the `TIMER_SCHEDULED` memo, so the retried await resumes from its *original* deadline: it consumes a signal that has arrived since, or else times out again immediately. It no longer gets a fresh full timeout — the memoized deadline governs, as `collectSignals`' memoized deadline already did. Deliver the signal before retrying.
- **Also:** a parked await now costs one timer row and one extra event, and every later step of that workflow sits one sequence higher than before. Await rows appear in `getDueTimers`/the timer table next to `sleep-*` rows. They do not emit the `timerScheduled`/`timerFired` observer callbacks (those remain `sleep()`'s); the await still reports `workflowParked`/`workflowUnparked(SIGNAL)`. A zero or negative timeout no longer writes `WAITING_SIGNAL` or reports a park, since it never parks.
- **Upgrade — drain or upgrade parked awaits together; no rollback while new-layout awaits are open.** There is no compatibility shim (pre-1.0). Histories an older build wrote are safe to replay on this one: an older build recorded a *completed* await as a single `SIGNAL_RECEIVED`/`SIGNAL_TIMEOUT` at `seq`, which replays unchanged, and recorded *nothing* for an await still parked — so on the first replay here such an await takes the live path and **re-arms once**, with a fresh full timeout from that moment (exactly what every restart did before), then is durable from then on. The reverse is not safe: an older node that replays a history this build wrote finds `TIMER_SCHEDULED` where it expects a single-slot outcome. A build with the replay drift guard (C10, below) **stands the run down as `nondeterministic_history`**; an older one collides on the occupied slot when it records its outcome and stands down as `stale_run` — either way nothing is written, no signal is consumed and nothing is compensated, but the older node makes no progress on that workflow. So in a mixed-version fleet only upgraded nodes advance those workflows, and rolling back stalls every workflow with a new-layout await until it is rolled forward again. Upgrade every node together, or drain parked awaits first if a rollback must stay possible.

### Changed — loan sample: memoized timeout policy activities replace the static timeout holders (F4)

- **Before:** the loan-origination sample kept its wait durations in static mutable holders (`LoanTimeouts`, `UnderwritingWorkflow.configureTimeouts`/`resetTimeouts`) because workflow instances cannot receive Spring beans; its tests shrank the timeouts to sub-second values and waited them out in real time.
- **Now:** each workflow reads its durations once, as its first activity call, from a `@Activity` (`LoanPolicyActivities.timeouts()`, `UnderwritingPolicyActivities.timeouts()`) implemented by a Spring `@Component` built from the `maestro.sample.*` properties. The result is memoized in history, so the durations are deterministic on replay and a run keeps the values it started with; the E2E script's per-node `MAESTRO_SAMPLE_DECISION_TIMEOUT` override still applies to runs that node starts. The sample tests run with SPEC timeouts and cross them with `TestWorkflowEnvironment.advanceTime()` (see C12/F4 above). **Upgrade:** the policy call adds one event at the start of each sample workflow's history, so sample loans in flight before this change should be drained first (sample code only; the engine is unaffected).

### Fixed — terminal status and terminal event are one transaction (Issue 26)

- **Before:** a workflow's terminal transition was two independent store calls — the instance row's `COMPLETED`/`FAILED` status first, then the `WORKFLOW_COMPLETED`/`WORKFLOW_FAILED` event append, whose failure was only logged. A crash or store error between them left a finalised instance whose terminal event was missing forever, and every status-gated reader could observe a log one event short.
- **Now:** the engine finalises through one new SPI call, `WorkflowStore.completeInstance(terminal, terminalEvent)`: a version-checked CAS exactly like `updateInstance`, with the terminal event appended **in the same transaction**. It returns `false` and writes nothing on a lost CAS, and throws `DuplicateEventException` (nothing written) when the terminal slot is already taken. A reader can never see a terminal status without its terminal event, nor the event without the status, so `getRecoverableInstances()` can never re-enter a finalised workflow. If the write fails with a store error, nothing is written and the workflow stays active and recoverable on **both** paths: on the success path the run is abandoned as `runAbandoned(TERMINAL_WRITE_FAILED)` directly — a store error on the `COMPLETED` write is no longer routed into failure handling, so a successful saga is never compensated and never recorded `FAILED` on a DB blip (R31) — and on the failure path the `FAILED` write's error is reported the same way. Recovery then replays the memoized steps and finalises the workflow. A run that loses the terminal slot to another runner now writes nothing: on the success path it stands down as `STALE_RUN` and reports no `workflowCompleted`; on the failure path it is abandoned as `TERMINAL_WRITE_FAILED` and reports no `workflowFailed` (two observer pins in `WorkflowExecutorObserverTest` that asserted the old status-first behaviour were rewritten accordingly). Pinned by `TerminalWriteAtomicityIT` (fault-injected event insert inside the transaction, real Postgres), `TerminalEventRaceIT.statusOnlyWait_neverSeesTheGap`, `PostgresWorkflowStoreTest.CompleteInstanceTests`, `InMemoryWorkflowStoreTest` and `WorkflowExecutorTerminalTransitionTest`. Spec row C13.
- **Also (plan 04 final wave):** `TerminalWrites.requireValid` — the shared argument check every store runs in `completeInstance` — now pairs the status with the event type: `COMPLETED` only with `WORKFLOW_COMPLETED`, `FAILED` only with `WORKFLOW_FAILED`, and `TERMINATED` is rejected (there is no terminal event type for it); a mismatch throws `IllegalArgumentException` before anything is written. And an `OptimisticLockException` thrown by a store's `completeInstance` (outside the SPI contract, which reports a lost CAS as `false`) is now contained like any other store error on the success path — `runAbandoned(TERMINAL_WRITE_FAILED)`, never `FAILED`, never compensated — instead of reaching the generic workflow-failure handler (R34 a).
- **Breaking SPI addition for custom stores:** `completeInstance` is **abstract, with no default**. A non-atomic default that called `updateInstance` then `appendEvent` would let a third-party store silently keep the Issue 26 bug, so a compile error is the deliberate signal. Migration: implement it as one transaction around your existing instance `UPDATE` and event `INSERT` (see `AbstractJdbcWorkflowStore.completeInstance`); stores that extend `AbstractJdbcWorkflowStore` / `PostgresWorkflowStore` already have it.

### Fixed — Terminate during saga compensation now stops before the next sequential entry

- **Before:** a terminate that landed while sequential compensation entry *i* was running did not stop the unwind: entries *i+1…n* still ran on the `TERMINATED` workflow and `COMPENSATION_COMPLETED` was recorded. Only the entry into compensation was guarded (Issue 22), although the Javadoc admitted "one open race" and the earlier note said Resolved. This applied most visibly to a terminate issued from a different node than the one running the workflow, and to manually registered compensation `Runnable`s.
- **Now:** `SagaManager` re-reads the instance before every live sequential entry. A `TERMINATED` row throws `WorkflowTerminatedException` (the entry that was already executing keeps its durable outcome; no later entry runs and no `COMPENSATION_COMPLETED` is written); any other terminal row throws `StaleRunException` and the run stands down. The `COMPENSATING` transition uses the same check on each CAS attempt. Cost: exactly one extra `getInstance` read per live entry; replay-skipped entries read nothing. Parallel compensation is unchanged. Pinned by `SagaManagerTerminateRaceTest` and `EnginePostgresSagaIT`. Spec row C7.
- **Also:** `parallel()` now rethrows a `DuplicateEventException` or `StaleRunException` from any branch ahead of an ordinary failure in an earlier branch (after control-flow errors), so a stale run stands down instead of being recorded FAILED and compensated. Parallel saga compensation ranks its branches' outcomes the same way: a control-flow signal in any branch first, then a stale-run signal, then index order (previously a stale-run in an earlier branch outranked a shutdown in a later one). Both forks — `parallel()` and parallel compensation — now rank a node-level error (`NodeErrors.isNodeError`) second, after control-flow signals and ahead of stale-run signals, rethrown raw: the run is abandoned as `NODE_ERROR` instead of an earlier branch's plain failure being recorded `FAILED` (or `COMPENSATION_STEP_FAILED`) and compensated.

### Fixed — A dual-execution loser now stands down at its first collision and at any terminal fresh read

- **Before:** when two nodes ran the same workflow (the lost-lock window, Issue 11 — or a deployment with no lock backend), the node that lost an activity's append race on the `(workflow_instance_id, sequence_number)` index *adopted* the winner's stored result and kept executing. The winner is alive and ahead, so every later live activity of the loser either collided again or — worse — ran a side effect the winner also ran; a loser whose own attempt had failed could surface that failure to workflow code although the winner had recorded a completion. Separately, `InstanceStatusWriter` (the park/wake status writer behind `sleep()` and `awaitSignal()`) only stopped a run on a fresh read of `TERMINATED`: a fresh read of `COMPLETED` or `FAILED` was logged and the run carried on — consuming a signal and executing further activities for a workflow another runner had already finished. And `SagaManager`'s transition to `COMPENSATING` threw `InvalidStateTransitionException` on the same read, which no executor arm caught, so it escaped and killed the workflow thread uncaught.
- **Now:** the loser accepts the winner's *record* as the truth and writes nothing that contradicts it. On a lost activity append — of its `ACTIVITY_COMPLETED` or its `ACTIVITY_FAILED` alike — the `DuplicateEventException` propagates (after the winner's event type is checked, so an unknown-typed winner still stands down as `unknown_event_type`), no lifecycle event is published, and the run stands down as `STALE_RUN`: nothing written, nothing compensated. The winner's payload is never read. A new `StaleRunException` (an ordinary `MaestroException`, carrying `workflowId()`, `observedStatus()` and `detail()`) is thrown by `InstanceStatusWriter` on a fresh read of `COMPLETED`/`FAILED` and by `SagaManager` on the same read at the `COMPENSATING` transition; the executor stands it down in the same arm as the duplicate (`EngineObserver.standDown(STALE_RUN, …)`, detail e.g. `status write of RUNNING`). A fresh read of `TERMINATED` still raises `WorkflowTerminatedException`. The observer still sees the attempt the loser genuinely executed (`activityCompleted`/`activityFailed` with `replayed=false`).
- **Behaviour change:** an activity call that loses the append race now *throws* (`DuplicateEventException`) where it used to return the winner's value. Both it and `StaleRunException` are ordinary `MaestroException`s, so a workflow's own `catch (Exception)` around an activity call, `sleep()` or `awaitSignal()` can intercept them — rethrow them rather than treating them as a business failure, or the stale run carries on (`docs/concepts.md` "Handling activity failures" and the PRD now state this contract). As a backstop, the run is marked stale at its first collision: its next live activity throws `StaleRunException` (detail `append collision at sequence N`) before it acquires a lock, publishes a start or executes anything, whatever the workflow code did with the duplicate. A parked `awaitSignal()` whose wake-recheck reads `COMPLETED`/`FAILED` now stands down too, without consuming the signal, and so does a parked `sleep()` on any wake — local or cross-node — without recording `TIMER_FIRED` (one extra instance read per local timer wake). The stale mark is set by **every** stale-run source (any append collision, any terminal fresh read at a status write, a wake recheck or a saga read) and checked at every stop point: before each activity attempt, on entry to `sleep()` and `awaitSignal()`, and before the terminal write on both the success and the failure path (a stale run neither writes `COMPLETED`/`FAILED` nor compensates). A stale mark also replaces an earlier shutdown request, so a known-stale run starts no attempt during a shutdown drain. `MultiNodeNoLockBackendIT`'s characterisation is updated accordingly. Pinned by `InstanceStatusWriterTest`, `WorkflowExecutorDuplicateEventStandDownTest`, `WorkflowTerminalStateGuardTest` and `EnginePostgresDualExecutionIT` (two harnesses on one Postgres store, no lock backend). Spec row C5.

### Changed — startToCloseTimeout is now enforced per attempt (ActivityTimeoutException); retry-durability docs corrected

- **Before:** `@ActivityStub(startToCloseTimeout)` was only the TTL hint for the per-activity distributed lock; nothing bounded an attempt, so a hung activity held its workflow indefinitely. The README and architecture doc also claimed retries were durable ("Retries survive JVM restarts"), but the attempt counter has only ever lived in memory.
- **Now:** each live attempt runs on its own virtual thread (with the caller's MDC and `WorkflowContext`) and the workflow thread waits at most `startToCloseTimeout` for it. An attempt still running at the deadline is cancelled — its thread is interrupted — and fails with the new `ActivityTimeoutException` (a `MaestroException`). It is retried under the activity's `RetryPolicy` like any other failure (list it in `nonRetryableExceptions` to make a timeout terminal); exhausted, it is persisted as `ACTIVITY_FAILED` with `exceptionType = io.b2mash.maestro.core.exception.ActivityTimeoutException`, and `ActivityExecutionException.causedBy(ActivityTimeoutException.class)` matches both live and on replay. A value an attempt produces after its deadline (one that ignored the interrupt) is discarded and never memoized; an attempt that completes in the instant between the deadline and the cancel keeps its outcome. `@Compensate` methods run through the same path and are bounded by the same timeout. A zero or negative `startToCloseTimeout` is now rejected when the proxy is built (`IllegalArgumentException`). Terminate still does not interrupt a running attempt; only the timeout does. Calling workflow APIs or other activity proxies from activity code is unsupported; the `WorkflowContext` is re-bound on the attempt thread for compatibility only.
- **Observer hook:** `EngineObserver.wrapActivityAttempt(ActivityInfo, Callable)` (default: identity) is called on the workflow thread before each attempt is handed to its thread, and the returned callable runs on the attempt thread. `TracingEngineObserver` uses it to put the `maestro.activity` span in scope for activity code, so a Kafka publish from inside an activity still carries the workflow's `traceparent`. **Custom observers that scope trace or observation context per activity must implement it**; counting observers need not. `CompositeEngineObserver` applies every delegate's wrapper with the first registered observer's outermost — its code runs first on the way in and last on the way out, the order every other callback fans out in.
- **Docs:** README "Durable Retries", `docs/concepts.md` (Retry policy → Timeouts, What survives a crash) and the architecture failure-modes table now say what is true: the attempt counter is in-memory, a crash mid-retry restarts the schedule at attempt 1, and the durable guarantee is that a *completed* attempt is never re-executed.
- **Behaviour change:** an activity whose attempt used to run past its declared timeout — including any that relied on the `PT30S` default while taking longer — now fails that attempt. Raise `startToCloseTimeout` on such stubs before upgrading. **Keep blocking client timeouts below `startToCloseTimeout`** (an HTTP or JDBC timeout, a Kafka send's `get(...)`), so a slow dependency fails the attempt with its own error and your retry policy takes over, rather than the engine timing the attempt out mid-call; the order and payment samples now bound their Kafka sends at 8 s under their `PT10S` stubs, like the loan sample. Spec row C11.

### Fixed — Terminate now stops live work: no further activity attempt, retry backoff abandoned; shutdown abandons a retry backoff

- **Before:** terminate was only observed at a park (`sleep`/`awaitSignal`) or a status write. A workflow inside a live activity's retry loop kept going: the backoff was an uninterruptible `Thread.sleep`, after which the next attempt — a real side effect — ran on a workflow an operator had terminated, and every later activity went live too until the run reached its next park or final transition. Shutdown likewise waited out a backoff in progress (up to the shutdown timeout) instead of handing the workflow to another node.
- **Now:** each local run carries an in-memory `RunCancellation` latch, shared by its root `WorkflowContext` and every branch context (`parallel()` branches, parallel- and sequential-compensation contexts). `WorkflowExecutor.terminateWorkflow` sets it **only on the node that owns the run** (the admin command lands on an arbitrary node; a non-owner's terminate still writes `TERMINATED` durably, and the owner converges via its terminal-status guards as before). The retry executor checks for a termination **before every attempt, including the first**, and the backoff wait is now a timed wait on the latch that returns as soon as a terminate or a shutdown is requested, raising the existing `WorkflowTerminatedException` / `ExecutorShutdownException` control-flow `Error`s — no `ACTIVITY_FAILED` or `WORKFLOW_FAILED` is recorded for an abandoned backoff. Neither path uses `Thread.interrupt()`, mirroring how parks are unblocked; a foreign interrupt during a backoff is still reported as an activity failure, as before.
- **Still not interrupted:** the activity attempt that is already executing. It finishes — unless its timeout elapses first — and a successful result is memoized (harmless), exactly as under shutdown; a failed one is neither retried nor persisted as `ACTIVITY_FAILED`. The run then stands down before anything further goes live.
- **Terminate outranks shutdown:** a terminate landing after a shutdown request on the same run replaces it, so the run's next attempt is still refused (previously the earlier shutdown request masked the terminate and the next attempt ran on a `TERMINATED` workflow). A run that registered just after `shutdown()` began requesting shutdowns also has its backoff abandoned.
- **Shutdown behaviour change:** shutdown is deliberately *not* checked before an attempt, so in-flight work still drains. But a run sitting in a retry backoff is no longer waited for: the backoff is abandoned, the instance is left `RUNNING` and recoverable, and the in-memory attempt counter restarts on the node that recovers it. Spec rows C6, C11.

### Fixed — Activity failures: explicit catch contract, payment sample no longer retries a declined card 30×

- **Before:** the PRD and the payment sample did `catch (PaymentDeclinedException e)`, which could never match because activity exceptions reach workflow code wrapped in `ActivityExecutionException`; the decline was also not non-retryable, so a declined card was retried up to 30 times with up-to-10-minute backoff.
- **Now:** `ActivityExecutionException.causedBy(Class<? extends Throwable>)` matches the live cause by type, and a replay-reconstructed cause (`ReconstructedActivityFailure`) by `originalClassName()` (the named type or a subtype). The payment sample catches the wrapper, checks `causedBy`, and lists `PaymentDeclinedException` in `nonRetryableExceptions`; a decline costs exactly one provider call. The PRD, `docs/concepts.md` ("Handling activity failures in workflow code") and the samples README are corrected, and the sample gains its first `TestWorkflowEnvironment` test. Spec F2.

### Changed — Replayed activity failures never degrade to a bare `RuntimeException`

- **Before:** a memoized activity failure was rebuilt by reflection — a `(String)` constructor, else a no-arg one (dropping the message), else a bare `RuntimeException` — so workflow code's `catch (MyDomainException)` behaved differently live and on replay.
- **Now:** the original type is re-created only when it is an accessible public class with a public `(String)` constructor that completes normally; otherwise (non-public or abstract class, no such constructor, throwing constructor) the cause is a `ReconstructedActivityFailure` (a `RuntimeException`, deliberately not a `MaestroException`) carrying the recorded message (a null or empty one replays as `Replayed failure`) and `originalClassName()`. The stored type name is resolved without initialising the class (context class loader, then the library's), so a name in the event log can never run a static initialiser. This also covers a class missing from the classpath, a missing recorded type (`"unknown"`), and a recorded engine control-flow signal name, which is never instantiated. The matching contract is documented on `ActivityExecutionException`: catch it and branch on `getCause()`'s class or on `originalClassName()`; do not rely on `catch (MyDomainException)` alone. Spec C15.

### Changed — `unknown_event_payload` stand-downs name the deserialization cause

- **Before:** the stand-down detail was only `seq=N` and the WARN said "could not deserialize the stored ..." — an operator could not tell a newer node's payload from their own refactor without a debugger.
- **Now:** the observer detail and the WARN carry the deserializer's root cause as the exception class and the JSON path of the offending field, `seq=N cause=<class> at <path>` (`seq=N cause=<class>` when there is no field path; shape mismatches: `seq=N cause=<phrase>`). The path is structural — list indexes, property names, and `[<key>]` for any map entry (e.g. `byCustomer[<key>].amount`): map keys are rendered as `[<key>]`; property names are kept, including keys that a reshaped map now exposes as bean properties. Class names are not rendered. The deserializer's message is deliberately **not** included — Jackson quotes stored values and source text in it, and those must not reach log or metric backends; the full cause chain, raw message included, is logged at `DEBUG` with each stand-down. The WARN no longer presumes a newer node: its closing clause for this reason reads "if a newer node wrote it, that node adopts it on recovery; on a homogeneous fleet this is an incompatible payload change — roll back, gate the change with workflow.version(), or terminate (operations §10.4)". The reason tag and metric are unchanged. [Operations §10.4](operations.md#104-the-alarm-that-matters-unknown_event_payload-on-a-homogeneous-fleet) now documents the roll-back and terminate remedies alongside `workflow.version()`, and an alert row for `unknown_event_payload` plus `nondeterministic_history` rising together. Spec C9.

### Changed — Replay drift is detected and stands down as `nondeterministic_history`

- **Before:** memoization matched a recorded event to a step by sequence number (at some sites, by event type) only, so a workflow edited without a `workflow.version()` gate replayed silently wrong: a renamed activity returned the old step's recorded payload; a renamed `awaitSignal()` returned the other signal's recorded payload; a `parallel()` whose branch count changed forked anyway over the recorded branch blocks; a renamed, reordered or mode-flipped (`@Saga(parallelCompensation)`) saga compensation entry was skipped as "already done" on another entry's record — never compensated — or re-run; and `sleep()` on a slot another operation had recorded inserted a `PENDING` timer row before colliding on the append — one leaked timer row per recovery pass, stood down as `stale_run`.
- **Now:** every replay read — including a `sleep()`'s wake/outcome slot and the saga's `COMPENSATION_STARTED`/`COMPENSATION_COMPLETED` markers, which used to accept any present event as "already recorded" — checks the recorded step name and type (and, for a fork marker, the branch/entry count) against what the code performs **before any side effect at that slot**. A mismatch stands the run down as the new `StandDownReason.NONDETERMINISTIC_HISTORY` (metric tag `maestro.standdown{reason="nondeterministic_history"}`), writing nothing and leaving the instance recoverable; its WARN ends "roll back the deploy or gate the change behind workflow.version()". The `version()` peek is unchanged — a non-marker event there still means "this history predates the change". Remedies, detail format and the alert: [Operations §10.7](operations.md#107-replay-drift-nondeterministic_history). Spec C10, F3.
- **Upgrade note — histories that drifted silently under an older build:** an in-flight instance whose workflow was edited without a `version()` gate, and which "worked" because the drift was silent, stands down as `nondeterministic_history` on its first replay after this upgrade. Rollback does not apply. Remedies: terminate it, or restore the recorded name (the detail's `stored=` value, `<group>.<method>`) via the interface's `@Activity(name = "<group>")` and/or the method name, and redeploy. Drain in-flight instances before upgrading where possible. See [Operations §10.7](operations.md#107-replay-drift-nondeterministic_history).
- **Also changed:** compensations registered inside `parallel()` branches are now pushed onto the saga stack in branch-index order once the fork joins (success and failure alike; nested forks into their enclosing branch), not in finish order — finish order is a thread race, and with the per-entry check above it would have stood correct sagas down on recovery. Auto-named `$compensation:N` entries are numbered in that order too. **Upgrade note:** an instance parked in `COMPENSATING` whose branch compensations were recorded in another order stands down as `nondeterministic_history` after upgrade; drain such instances first.

### Fixed — A JVM Error escaping a workflow method now ends the workflow FAILED; node-level errors abandon the run loudly instead

- **Before:** an `Error` thrown out of the workflow body (`AssertionError`, `StackOverflowError`, `ExceptionInInitializerError`, `OutOfMemoryError`, ...) was caught by nothing in `executeWorkflow`. The `finally` released the instance lock and the row stayed `RUNNING`, so the recovery poller re-adopted it on every pass, forever — with no engine log line and no `workflowFailed` observer callback.
- **Now:** such an `Error` takes the same path as an exception: an `ERROR` log, the `workflowFailed` callback, registered compensations, and a `WORKFLOW_FAILED` event carrying the `Error`'s class name; the row ends `FAILED` and is never re-adopted. The three control-flow signals (`ExecutorShutdownException`, `WorkflowTerminatedException`, `UnknownWorkflowHistoryException`) are unaffected and still stand down. Spec C8.
- **`parallel()` error precedence:** when several branches fail, an engine control-flow signal (shutdown, terminate, unreadable history) in *any* branch is now rethrown ahead of a plain failure in an earlier branch — previously branch-index order could turn a deploy into a recorded failure with compensations. A fork whose join is interrupted now logs a `WARN` naming the branch compensations it drops, instead of discarding them silently.
- **Node errors do not fail the workflow:** a `LinkageError` (`NoClassDefFoundError`, `ExceptionInInitializerError`, ...) or a `VirtualMachineError` other than `StackOverflowError` (`OutOfMemoryError`, `InternalError`, ...) is a bad deploy or resource exhaustion on that node, not a workflow failure. It is logged at `ERROR` under the workflow MDC, fires `EngineObserver.runAbandoned(..., AbandonReason.NODE_ERROR)` (new constant; span attribute `maestro.abandon.reason=NODE_ERROR`), writes nothing, runs no compensation, releases the lock, and leaves the instance `RUNNING`/recoverable — re-adopted (and logged again) on every recovery pass until the node is fixed or rolled back, or the workflow is terminated. Alert on that `ERROR` line. `StackOverflowError`, `AssertionError` and other `Error`s still end `FAILED`. The same holds for a node error thrown **inside an activity** (including a compensation activity): the retry executor rethrows it at once, unwrapped and unretried, instead of retrying it and recording `ACTIVITY_FAILED` — which used to end the workflow `FAILED` with compensation; mid-unwind the instance stays `COMPENSATING`. One predicate, `NodeErrors.isNodeError(Throwable)`, now serves both sites. See [Operations §10.8](operations.md#108-node-errors-node_error-abandons) and `docs/observability.md`.

### Changed — The 999-slot-per-branch limit is enforced

A parallel branch owns a 1000-number sequence block, so it can take at most 999 sequence slots (most steps take one; `sleep()` takes two). Previously a branch's 1000th slot landed on the sibling's *base* slot (which the sibling never writes); collisions with the sibling's own events began at slot 1001 (replaying a sibling's result or colliding on the unique index), and a branch's own nested fork placed at the sibling's base would collide with the sibling's nested blocks. A branch context is now bounded: the 1000th direct slot throws `BranchSequenceExhaustedException` (a `MaestroException`), failing the branch and therefore the workflow, and registered compensations run. The bound applies to `parallel()` branches, parallel-compensation branches and sequential compensation entries alike (a compensation branch or entry that exhausts its block is recorded `COMPENSATION_STEP_FAILED` with the typed cause, and the remaining compensations still run). Sequential entries were the worst case: an entry's 1000th slot landed on the *next entry's* guard slot, so the next compensation was silently skipped as already done while the phase still recorded `COMPENSATION_COMPLETED`. A nested `parallel()` inside a branch is unaffected. **Upgrade note:** an in-flight history whose branch or compensation entry took exactly 1000 direct slots used to complete and will now fail with `BranchSequenceExhaustedException` on replay after upgrade; a history whose compensation phase already skipped an entry that way keeps it skipped on replay (the spilled event still sits on that entry's guard slot), so check such workflows by hand. The upgrade-together/drain guidance in the C1 entry below applies. See docs/concepts.md § Parallel Execution.

### Changed — Sequence-space overflow is now a typed failure; compensation arithmetic is guarded too

`parallel()` used to throw a bare `IllegalStateException` when a fork's ×1000 branch blocks would not fit in the 32-bit sequence space; it now throws `SequenceSpaceExhaustedException` (a `MaestroException`), still before any sequence is consumed. Saga compensation used the same block arithmetic with no guard at all: once the compensation anchor was large enough (for example after two sequential `parallel()` calls), the `int` math wrapped silently and compensations ran at garbage, negative sequence numbers. Both now go through one overflow-checked `SequenceSpace` utility, and `SagaManager.compensate()` pre-checks its blocks before the `COMPENSATING` transition — so nothing durable changes — and the executor finishes the workflow `FAILED` with an `ERROR` log stating that no compensation ran (a preceding `SagaManager` `ERROR` line lists the skipped steps' names); the operator compensates manually. A compensation that itself calls `parallel()` after a forward `parallel()` is one level too deep: the pre-check passes, and that entry's fork fails with `SequenceSpaceExhaustedException`, recorded as its `COMPENSATION_STEP_FAILED` while the remaining entries still run. Code that caught `IllegalStateException` around `parallel()` should catch `SequenceSpaceExhaustedException` instead. See docs/concepts.md § Parallel Execution → Depth limit and [Issue 28](open-issues.md#issue-28).

### Fixed — A crash during parallel saga compensation no longer strands the workflow in COMPENSATING

The `$maestro:parallel-compensation` fork marker was appended unconditionally. On recovery the duplicate append was rethrown as a stale-run stand-down, so the instance was re-adopted on every recovery poll forever and never left `COMPENSATING`. The marker is now replay-guarded like `COMPENSATION_STARTED`. The pre-existing unit pin passed only because the test's in-memory store lacked the unique index; it now has one.

### Fixed — Compensation after a failed `parallel()` no longer reads sibling-branch events

When a `parallel()` branch threw, the parent sequence counter stayed at the fork's own sequence, so `SagaManager` anchored entry *i*'s compensation block at branch *i+1*'s base and a `@Compensate` action replayed the sibling's `ACTIVITY_COMPLETED`/`ACTIVITY_FAILED` instead of running. `parallel()` now advances the counter in a `finally`, so it sits past every branch block whether the fork returns or throws. **Behaviour change:** every step recorded after a failed `parallel()` moves, not just compensation. Compensation now starts at `p*1000+(n+1)*1000+1` (step blocks at `(that+1)*1000` and above), and so does any step a workflow runs after catching a fork failure (`try { parallel(...) } catch (Exception e) { activities.x(); }`), which previously landed at `p+1`; this stays within `int` for one forward fork (see docs/concepts.md § Parallel Execution → Depth limit). **Upgrade every node of a service together, or drain it first:** an instance recorded on the previous version that caught a fork failure and ran a step, or is parked in COMPENSATING, would on recovery by an upgraded node find nothing at the new slot and run that step again live (a duplicate external side effect), and a mixed-version fleet would write diverging histories. Stop starting and recovering workflows before mixing versions; single-node deployments should drain such instances before upgrading.

### Added — `maestro.messaging.redelivery.enabled` flag, and a startup check for missing `.DLT` topics

The dead-lettering error handler installed on every Maestro-managed consumer
container ran unconditionally, with nothing to create — or even check for —
the `.DLT` topics it depends on. A missing dead-letter topic surfaced only
once a handler's attempt budget was first exhausted, as a stalled, noisily
retrying consumer, at the worst possible moment to discover the gap.

- **`maestro.messaging.redelivery.enabled`** (default `true`) is a new, first
  field of the `maestro.messaging.redelivery.*` block, gating both
  transports. Set to `false` to restore at-most-once handler semantics: on
  Kafka, the listener container gets a zero-retry `DefaultErrorHandler` with
  no `DeadLetterPublishingRecoverer`; on Postgres, a failing row is marked
  `FAILED` after exactly one attempt instead of being retried and
  dead-lettered. This is the operator's explicit opt-out, not a recommended
  default.
- **`KafkaDeadLetterTopicCheck`** is a new warn-only startup probe, wired at
  every point Maestro subscribes to a topic — the engine's own
  `subscribe`/`subscribeSignals` and every `@MaestroSignalListener`
  container's activation. It WARNs by name when a topic's `.DLT` companion
  does not exist, is bounded to 5 seconds, never fails startup, and is
  skipped entirely when redelivery is disabled. Full contract and the
  `.DLT` pre-creation checklist: [`docs/configuration.md` § Kafka
  Dead-Letter-Topic Check](configuration.md#kafka-dead-letter-topic-check).
- **`sample-loan-origination`'s and `demo`'s compose stacks now pre-create
  `.DLT` companions** for every topic they were missing one for — the six
  engine tasks/signals topics and the two `@MaestroSignalListener` business
  topics (`loans.verification.results`, `loans.underwriting.decisions`) —
  closing the exact gap the new check now warns about.
- **Was:** [Issue 24](open-issues.md#issue-24).

### Fixed — Valkey lock honours `spring.data.redis.host`/`port`/`password`/`username`/`ssl.enabled`/`database`

The Valkey lock's connection URI was previously resolved from only
`spring.data.redis.url` and `maestro.lock.valkey.uri` — the individual
`spring.data.redis.host`/`port`/credential properties (which
`docs/configuration.md`'s own Complete Example configured) were silently
ignored, and the lock connected to `redis://localhost:6379` regardless.
A third resolution step now builds the URI from
`spring.data.redis.host` + `port`/`password`/`username`/`ssl.enabled`/`database`
when neither URI property is set. **Behaviour change:** a deployment that set
`spring.data.redis.host` expecting it to apply now genuinely connects there
instead of falling back to localhost. The full resolution order is documented
in [`docs/configuration.md` § Valkey Connection Resolution](configuration.md#valkey-connection-resolution).

### Removed

- **`maestro.store.table-prefix`** — startup now fails with a message naming the replacement if the property is set. Reason: it only changed the JDBC store's table names; the Flyway migrations and the lock/messaging SQL always used `maestro_*`, so a custom prefix never worked end to end (spec F6). To isolate services sharing a database, give each service its own Postgres schema (`?currentSchema=<schema>` or `spring.datasource.hikari.schema`). `StoreProperties` and `JdbcStoreConfiguration` lose their prefix component; `JdbcStoreConfiguration.TABLE_PREFIX` is the fixed `maestro_`.
- **RabbitMQ messaging support has been removed** — the `maestro-messaging-rabbitmq`
  module and the `sample-rabbitmq-order-service` sample are deleted. Nothing has
  been published under this artifact yet, so this is a deletion, not a
  deprecation: there is no migration path because there is no installed base to
  migrate. **Rationale:** each additional transport carries its own real-backend
  verification cost — a dedicated Testcontainers suite, its own redelivery/
  dead-letter design, its own multi-instance and chaos coverage — and the
  multi-instance verification cycle (PR #30) is the evidence for how much that
  costs per transport. Shrinking the matrix to Kafka and Postgres before the
  first public release keeps that cost bounded to backends the project can
  actually keep verified. The `WorkflowMessaging` SPI itself is untouched and
  remains transport-agnostic — a community `maestro-messaging-rabbitmq` (or any
  other broker) adapter implementing the three-method SPI remains possible; it
  is simply no longer shipped or verified in this repository.

### Fixed — A terminate racing saga compensation could no longer run compensations, but could before

`SagaManager.transitionToCompensating` re-reads the instance and re-checks for
`TERMINATED` before writing `COMPENSATING`, but the read and the write are not
atomic — a cross-node `WorkflowExecutor.terminateWorkflow` could land in that
gap, making the write lose its optimistic-lock check. That lost
compare-and-set was silently swallowed, and the failing run's compensations
ran anyway: refunds issued, reservations released, for a workflow an operator
had explicitly asked to stop.

- The lost compare-and-set is now retried against a **fresh read**, with the
  `TERMINATED`/other-terminal guard re-evaluated on **every** attempt (not
  just the first) — the same bounded-retry idiom (`STATUS_WRITE_ATTEMPTS = 5`,
  immediate, no backoff) `InstanceStatusWriter.write` already uses for the
  sibling conflict on the same row. A `TERMINATED` observed on any attempt now
  throws `WorkflowTerminatedException`, propagating out of `compensate()`
  uncaught, and no compensation runs.
- **Exhaustion policy differs deliberately from `InstanceStatusWriter`:** this
  write gates entry into the compensation phase, so on exhaustion the method
  logs an error and rethrows the last `OptimisticLockException` instead of
  standing down and proceeding — nothing terminal is written, the instance
  stays active, and recovery retries the transition with a fresh read.
- **Was:** [Issue 22](open-issues.md#issue-22).

### Fixed — Kafka client configuration now honours `spring.kafka.*`, and Kafka observation/tracing default on

**Behaviour change for every Maestro + Kafka application** — no config
migration is required, but two things you may not have noticed silently not
working now do:

- **`spring.kafka.*` reaches Maestro's engine producer/consumer.**
  `maestroKafkaProducerFactory` / `maestroKafkaConsumerFactory` are now built
  from Spring Boot's bound `KafkaProperties` — bootstrap servers, compression,
  batching, retries, SSL/security settings, arbitrary
  `spring.kafka.producer.properties.*` / `spring.kafka.consumer.properties.*`
  entries, all of it. Previously these beans ignored `spring.kafka.*` entirely
  and only ever used a hardcoded bootstrap-servers value. A small set of wire
  invariants the engine's protocol depends on (`String`/`byte[]`
  (de)serializers, `acks=all`, the engine's `group.id`) are still forced
  **last**, so no user property can corrupt engine topics. Full precedence
  table: [`docs/configuration.md` § Kafka client
  configuration](configuration.md#kafka-client-configuration).
- **Kafka observation (and therefore cross-service tracing) is on by default
  when Micrometer tracing is active** — a `Tracer` *and* a `Propagator` bean
  both exist and `maestro.observability.tracing.enabled` is not `false`, the
  same condition that activates Maestro's own `KafkaTracePropagation` bean
  (Spring Boot's unconditional no-op `Tracer` alone does not trigger this).
  `maestroKafkaTemplate` and the `@MaestroSignalListener` consumer containers
  now default `observation-enabled` to `true` under that condition, instead of
  requiring a hand-written `maestroKafkaTemplate` bean override to get a
  connected trace across services. `@MaestroSignalListener` also now extracts the
  inbound `traceparent` (and `tracestate`/`baggage`) from every record,
  independent of container observation, so `trace_context` is populated on
  the signal row rather than staying `NULL`. Set
  `spring.kafka.template.observation-enabled=false` /
  `spring.kafka.listener.observation-enabled=false` to opt out. Full contract:
  [`docs/observability.md` § Cross-service trace propagation
  (Kafka)](observability.md#cross-service-trace-propagation-kafka).
- **The sample-level observed-template workaround is gone.** The three
  identical bean-shadowing config classes previously shipped in
  `sample-loan-origination`'s services (a hand-rolled `maestroKafkaTemplate`
  bean with observation forced on) are removed — the engine now applies
  observation defaults without any per-service code. The explicit
  `spring.kafka.producer.*-serializer` entries are also removed from every
  Kafka sample's `application.yml`, since all producer traffic in these
  samples goes through the one `KafkaTemplate<String, byte[]>` in the context
  (Maestro's own), which forces those regardless. The
  `spring.kafka.consumer.*-deserializer` entries are removed only where a
  service's *only* Kafka consumer is `@MaestroSignalListener`
  (`sample-order-service`, `loan-application-service`) — that path always
  resolves Maestro's own `maestroKafkaConsumerFactory`, which forces the same
  invariants. Services that also have a plain `@KafkaListener`
  (`underwriting-service`, `verification-gateway-service`,
  `sample-payment-gateway`) keep their `spring.kafka.consumer.*-deserializer`
  entries — those back Boot's own `kafkaListenerContainerFactory`, which
  Maestro's forced invariants do not reach, so removing them broke that
  listener outright (caught by the loan sample's end-to-end suite). If your
  own service copied the workaround pattern, you can delete it too: a bean
  named `maestroKafkaTemplate` still wins by
  `@ConditionalOnMissingBean(name = "maestroKafkaTemplate")` if you keep one,
  but it is no longer necessary to get a connected trace.
- **Was:** [Issue 23](open-issues.md#issue-23).

### Fixed — `maestro.enabled=false` now disables every Maestro auto-configuration, not just the core engine

Previously, only the core `MaestroAutoConfiguration` (and, transitively, most
things that required its beans) backed off under `maestro.enabled=false`.
`maestro-messaging-kafka` and `maestro-messaging-postgres` had no gate at
all: with the engine flag off, they still built a real Kafka
producer/consumer or opened a real Postgres `LISTEN/NOTIFY` connection, then
crashed resolving `MaestroProperties` — a bean only `MaestroAutoConfiguration`
registers. `maestro-lock-valkey` had no gate either, and — because it needs
no `MaestroProperties` bean — it *succeeded*, silently opening live Valkey
connections (`RedisClient`, the lock connection, the pub/sub signal notifier)
for an engine the operator believed was fully off.

- `maestro-messaging-kafka`, `maestro-messaging-postgres`,
  `maestro-lock-valkey`, `maestro-lock-postgres`, `maestro-store-postgres`,
  and `maestro-admin-client` now all carry the same
  `@ConditionalOnProperty(prefix = "maestro", name = "enabled", matchIfMissing = true)`
  gate `MaestroAutoConfiguration` uses. `MaestroHealthAutoConfiguration`
  gets the same gate directly as defense in depth (it was already
  transitively off via `@ConditionalOnBean(WorkflowExecutor.class)` in a
  real application, but the check is now not dependent on that indirection).
- **Migration note:** if you set `maestro.enabled=false` expecting a clean,
  fully-disabled Maestro and previously either saw a startup crash (Kafka or
  Postgres messaging on the classpath) or unexpectedly-live Valkey
  connections (Valkey lock on the classpath), both are fixed — the flag now
  does what its documentation always said.
- **Was:** audit finding F8 (`tasks/audit-2026-08-05-inert-config.md`).

### Fixed — `maestro.retry.default-*` now provides the default `@ActivityStub` retry policy

`ActivityStubBeanPostProcessor` previously built every `@ActivityStub`'s
retry policy via `RetryPolicy.fromAnnotation()` unconditionally, so the four
`maestro.retry.default-*` properties bound and validated but were never
read — every stub got the hardcoded `RetryPolicy.defaultPolicy()` (3
attempts, 1s→60s backoff, 2× multiplier) regardless of configuration. The
values happened to coincide with the annotation's own declared defaults,
which is why this went unnoticed.

- `maestro.retry.default-max-attempts`, `.default-initial-interval`,
  `.default-max-interval`, and `.default-backoff-multiplier` now supply the
  policy for any `@ActivityStub` whose `retryPolicy` is left at the
  `@RetryPolicy` annotation's own defaults (all six attributes unchanged).
  Customizing even a single attribute still opts that stub out of these
  properties entirely — the annotation's explicit values are honoured as
  written. Full precedence rule:
  [`docs/configuration.md` § Retry Configuration](configuration.md#retry-configuration).
- **Migration note:** if you previously set `maestro.retry.default-*` to
  anything other than the annotation's own defaults (3 attempts, 1s, 60s,
  2.0×) believing it applied, it now genuinely does — activity retry timing
  for unconfigured stubs may change.
- **Was:** audit finding F6.

### Fixed — `maestro-admin-client` now honours the canonical `maestro.messaging.topics.admin-events` property

`AdminEventPublisher` read only the deprecated
`maestro.admin.events.topic` property; a service that followed
`CLAUDE.md`'s own guidance and set only the canonical
`maestro.messaging.topics.admin-events` had its admin-client lifecycle
events published to the wrong topic, while the engine's own publisher (in
`maestro-messaging-kafka`) correctly used the canonical property. Since
`maestro-admin-client` cannot depend on the starter module,
`AdminClientAutoConfiguration.resolveTopic` now mirrors
`KafkaMessagingAutoConfiguration.resolveAdminEventsTopic`'s precedence
directly against `Environment`, with cross-referencing comments at both
sites so the two cannot drift apart unnoticed again.

- **Migration note:** if a service using `maestro-admin-client` set only
  `maestro.messaging.topics.admin-events` (the documented canonical
  property) and not the deprecated `maestro.admin.events.topic` alias,
  admin-client lifecycle events now publish to the topic you actually
  configured instead of the deprecated-alias default.
- **Was:** audit finding F10.

### Fixed — PostgresStoreAutoConfiguration now orders after JNDI and XA DataSource auto-configurations

The store bean definition must be contributed before `MaestroAutoConfiguration` runs (the engine is guarded with `@ConditionalOnBean(WorkflowStore.class)`). Previously it was ordered only relative to the base `DataSourceAutoConfiguration`, so a deployment using JNDI or XA DataSources (which have separate, later auto-configurations) could find itself without a store bean — the engine would silently back off, appearing fully configured but completely inactive.

- `PostgresStoreAutoConfiguration` now orders after
  `JndiDataSourceAutoConfiguration` and `XADataSourceAutoConfiguration` as
  well, ensuring the store activates regardless of how the `DataSource` is
  configured. No action required; the fix is purely ordinal and touches no
  runtime code paths.
- **Was:** audit finding F9.

### Added — Observability: Micrometer meters and OpenTelemetry tracing

Full reference: [`docs/observability.md`](observability.md).

- **A framework-free observer seam in `maestro-core`.** New package
  `io.b2mash.maestro.core.observe` with an `EngineObserver` interface the
  engine calls at execution boundaries — 22 `default` no-op methods, a
  composite, and the `*Info` records. `maestro-core` gains **no** dependency on
  Micrometer, OpenTelemetry, or Spring; every adapter lives in the starter and
  in `maestro-messaging-kafka`. Embedders can implement it directly.
- **Micrometer meters under `maestro.*`**, auto-configured when a
  `MeterRegistry` is present: counters
  `maestro.workflow.started|completed|failed|compensated|terminated`,
  `maestro.signal.consumed`, `maestro.timer.fired`, `maestro.recovery.scanned`,
  `maestro.recovery.adopted`, `maestro.lock.renew.failures`,
  `maestro.standdown`; timer `maestro.activity.duration`; gauges
  `maestro.workflows.running` and `maestro.workflows.parked`. Nothing is ever
  tagged by `workflowId` or `runId`. The two gauges are **node-local, in-JVM**
  values — dashboard them per pod and sum across pods for a cluster total.
- **Replayed steps are never counted and never traced.** A recovered workflow
  replaying N activities does not double-count. Pinned end-to-end over a real
  Postgres (crash node A, recover on node B, assert the replayed step's timer
  count stays 1).
- **Spans via the Micrometer Tracing API** (`io.micrometer.tracing.Tracer`, so
  it bridges to whichever OpenTelemetry or Brave bridge your application
  ships): `maestro.workflow.run` per run segment (the stretch of a run between
  parks), `maestro.activity` per live activity execution, and
  `maestro.signal.receive` on the Kafka listener side. The MDC keys
  `workflowId` / `runId` / `activityName` appear as span attributes, so logs
  and traces join.
- **Cross-service trace propagation through Kafka.** W3C `traceparent` (plus
  `tracestate` / `baggage` when the propagator emits them) is injected into
  record headers on publish and extracted on consume, and the context is
  **persisted on the signal row** so it survives a durable park — a signal
  published by service A and consumed by service B's parked workflow renders as
  **one connected trace**, not two. The exact header names and the
  `traceparent` grammar are pinned by contract tests rather than inherited from
  transport defaults. Absent, malformed, or oversized values degrade to an
  untraced delivery with a fresh root span — never to an error and, critically,
  never to a discarded signal.
- **New properties** `maestro.observability.metrics.enabled` and
  `maestro.observability.tracing.enabled`, both defaulting to `true` and both
  silently inert without the beans they need. The tracing flag gates the Kafka
  header injection too. With no tracing beans the Kafka wire format is
  byte-identical to a pre-tracing build.
- **Known limitations are documented rather than left to be discovered** — see
  `docs/observability.md` → Known limitations. The most consequential: the
  engine has no fork/join observation boundary, so a workflow that forks as its
  first statement, or any recovered run whose first live step after replay sits
  past a join, has no fork point to own; each such activity exports as its own
  **root** span, fragmenting the run into one trace per activity. Closing it is
  post-1.0 work.

### Added — Workflow versioning: `workflow.version()`

Full reference: [`docs/concepts.md` → Versioning Workflow Code](concepts.md#versioning-workflow-code).

- **`WorkflowContext.version(changeId, minSupported, maxSupported)`** — the
  Temporal-proven memoized change-branching model. The first live evaluation
  records `maxSupported` as a `VERSION_MARKER` event and returns it; every
  replay returns the **recorded** value forever, regardless of what the code's
  `maxSupported` has moved on to. Histories that predate the change resolve to
  `WorkflowContext.DEFAULT_VERSION` (`-1`) **without consuming a sequence
  slot**, so introducing a `version()` call into existing code leaves in-flight
  instances' event logs unshifted. This is what makes it usable: you can add
  the gate to code that already has instances running through it.
- Version decisions are visible to `DeterminismChecker` with no checker change
  — the marker's step name is `$maestro:version:{changeId}`.
- Raising `minSupported` above an instance's recorded version throws the new
  `UnsupportedWorkflowVersionException`, naming the changeId, the recorded
  version, and the supported range. That is an ordinary deterministic workflow
  failure (it ends `FAILED`, saga compensation runs); the admin **Retry**
  action composes with it once the old branch is restored, because retry clears
  the failure memos but never the version marker.

### Changed — Unreadable history stands down instead of failing the workflow

- **A node that cannot interpret a workflow's persisted history no longer
  records it `FAILED`.** Previously, an `event_type` string absent from this
  build's enum threw an `IllegalArgumentException` that looked like any other
  exception escaping a workflow method — so the engine marked the workflow
  `FAILED` **and ran its compensations**, unwinding real work (refunds issued,
  reservations released) for a workflow that never failed and was, on the other
  half of the fleet, perfectly healthy. It now **stands down**: nothing is
  written, no compensation runs, the instance keeps its recoverable status, the
  instance lock is released, a `WARN` is logged, and
  `maestro.standdown{reason=...}` is incremented. An upgraded node adopts and
  finishes the workflow through the ordinary lock-TTL / recovery-poller
  machinery, unchanged.
- The same guard now covers **every path that deserializes a persisted payload
  it did not itself just write**, not only the replay caller: the activity
  duplicate-adopt branch, the persisted workflow input read on every recovery
  run, and the persisted signal payload. This matters because the duplicate-adopt
  path is reachable with **no author error** — an old node reads a sequence
  empty, executes live, and loses the append race to a newer node whose event at
  that sequence is a type it does not define.
- **An unmappable instance `status` no longer aborts the whole recovery pass.**
  `WorkflowStatus` mapping is now total: a status string a newer node wrote
  causes that **one** instance to be skipped with a `WARN` carrying the raw
  value, and the pass continues for every other workflow on the node.
  Deliberate trade, documented in the mapper's Javadoc: while a node cannot map
  the row, `getInstance` returns empty, so an operator API asking *that* node
  about an existing workflow reports it **"not found"**. Every caller already
  has a defined, non-destructive answer for an absent instance (a signal is
  treated as pre-delivery and stored, never discarded).
- **Control-flow signals now share a sealed base.** `MaestroControlFlowError
  extends Error` permits exactly three types: `ExecutorShutdownException`,
  `WorkflowTerminatedException`, and the new `UnknownWorkflowHistoryException`.
  Behaviour-preserving — every existing catch site is still exact-type — but a
  broad `catch (Throwable)` collector inside the engine now needs *one* check
  rather than an enumeration that drifts each time a signal is added. **If you
  maintain code that catches broadly around Maestro calls, check for
  `MaestroControlFlowError` and rethrow.**
- Operator guidance, including the one alarm that is easy to misread, is in
  [`docs/operations.md` §10](operations.md#10-versioning-and-mixed-version-deploys).
  Summary: a rising `maestro.standdown{reason=unknown_event_payload}` on a fleet
  you know to be **homogeneous** means "an incompatible payload change needs
  `workflow.version()`", **not** "wait for the deploy to finish" — otherwise the
  new stand-down behaviour turns a visible failure into a silent zombie.

### Changed — `maestro-test` waits for the terminal *event*, not the terminal status

- **`TestWorkflowHandle.awaitCompletion(...)` and `getResult(...)` now wait for
  the workflow's `WORKFLOW_COMPLETED` / `WORKFLOW_FAILED` event to reach the
  event log**, not merely for the instance row to read `COMPLETED`/`FAILED`.
  The engine finalises a run with two separate, non-transactional writes and the
  instance row goes first, so returning on the status alone handed you a log the
  engine had not finished writing — a test doing
  `handle.awaitCompletion(...); handle.getEvents()` could legitimately miss the
  terminal event and fail intermittently on a loaded CI machine.
  `WorkflowStatus.TERMINATED` is exempt: terminating appends no event, so there
  is nothing to wait for.
- **Behaviour change to be aware of:** both methods can now throw
  `TimeoutException` in a case where they previously returned — specifically
  when the row is terminal but the terminal event never lands within the
  timeout. That is an engine defect rather than a timing artefact, and it now
  fails loudly instead of handing you a truncated log. If a test starts timing
  out here, the fix is not a longer timeout. No signature changed;
  `TimeoutException` was already declared on both methods.
- The underlying two-write gap is now closed at the source — see Issue 26 above; the event wait remains correct and unchanged.

### Database Migrations

One new Flyway migration:

- **`V4__signal_trace_context.sql`** (`maestro-store-postgres`, version band
  1–99) — adds a nullable `trace_context VARCHAR(128)` column to
  `maestro_workflow_signal`. It carries the W3C `traceparent` captured when the
  signal was received, so a workflow parked on that signal can adopt the
  publishing service's span as a remote parent when it resumes — possibly hours
  later, possibly on another node. The column is opaque: no store or engine
  logic parses it, branches on it, indexes it or joins on it, and `NULL` (an
  untraced transport, or a build with tracing disabled) is normal and degrades
  to a fresh root span rather than to an error. The DDL is a single
  `ADD COLUMN` with no `DEFAULT` and no `NOT NULL`, so on Postgres 11+ it is
  metadata-only — instant, no table rewrite, safe to apply to a live table.

**The column must exist before a node running this version handles its first
signal.** `AbstractJdbcWorkflowStore` names `trace_context` unconditionally in
both the signal `INSERT` (`saveSignal`) and the signal `SELECT`
(`getUnconsumedSignals`); there is no feature detection and no fallback path.
Against a signal table that lacks the column, `saveSignal` throws — and because
signal delivery runs *inside the transport listener*, that failure means the
record is never acked, bounded redelivery exhausts, and the signal is
**dead-lettered**, i.e. discarded. That is the one outcome the engine otherwise
guarantees against.

- **Default deployments (`maestro.store.table-prefix` left at `maestro_`): no
  manual step.** Flyway applies `V4` automatically at startup, before the store
  serves traffic — same as `V1`–`V3`.
- **Deployments using a custom `maestro.store.table-prefix`: you must add the
  column to your own migrations before upgrading.** Maestro's shipped
  migrations hardcode the `maestro_` prefix — the note at the top of
  `V1__create_maestro_schema.sql` tells custom-prefix users to provide
  corresponding custom migrations, so such a deployment is already maintaining
  its own set. `V4` is a new one to mirror there:

  ```sql
  ALTER TABLE <your_prefix>workflow_signal ADD COLUMN trace_context VARCHAR(128);
  ```

  This is not an exotic configuration: `docs/cross-service.md` recommends "a
  unique `maestro.store.table-prefix` per service" for multi-service
  deployments. Miss the column there and the failure mode is the discarded
  signal described above, not a startup error.

### Upgrade notes — mixed-version deployments

- **`VERSION_MARKER` is a new event type — the same upgrade-together rule
  applies.** A workflow that has evaluated `workflow.version()` carries a
  `VERSION_MARKER` event in its history. A node running the previous version
  that adopts such a workflow cannot interpret it. Unlike the `SIGNAL_TIMEOUT`
  case below, it no longer *fails* the workflow — it stands down and leaves it
  for an upgraded node (see "Unreadable history stands down" above). That is a
  safety net for the rolling window, **not** a licence to run a mixed fleet:
  those workflows make no progress on the old nodes and simply wait. Upgrade
  every node of a service together, or drain the service first.
- **Upgrade every node of a service together (or drain it first) — the new
  `SIGNAL_TIMEOUT` event type is not readable by the previous version.** This
  release's Issue 19 fix (below) makes upgraded nodes write `SIGNAL_TIMEOUT`
  events into the shared event log. A node still running the previous version
  that adopts such a workflow — the normal cross-node recovery path in a
  multi-instance service — fails `EventType.valueOf("SIGNAL_TIMEOUT")` while
  reading the log and cannot replay the workflow until an upgraded node picks
  it up. In a rolling deploy that window is live traffic, so either upgrade
  all nodes of a service in one step, or drain the service (stop starting and
  recovering workflows) before mixing versions. Single-node deployments are
  unaffected.
- **Awaits that timed out *before* the upgrade replay live once *after*
  it.** The determinism guarantee below applies to `SIGNAL_TIMEOUT` events
  written by upgraded nodes; a pre-upgrade timed-out await left no memo, so
  its first post-upgrade replay re-executes at that slot exactly as the old
  version would have (and may consume a late-arrived signal there, the old
  behaviour). From that replay's own memo onward the new guarantee applies.

### Observability

- The Spring Boot starter now logs one INFO line at startup naming the
  effective distributed-lock backend (`Maestro distributed-lock backend: ...`,
  or `none (single-node mode)` when no backend is configured) — so a
  multi-instance deployment silently running without a lock backend is
  visible in the boot log.

### For third-party `WorkflowStore` implementers

- **`WorkflowStore.deleteFailureEvents`'s contract changed** (`docs/open-issues.md`
  Issue 19). This is the same abstract SPI method 0.4.0 introduced for Issue
  15's Retry command (not a new method — no recompile needed), but its
  required *behaviour* is now stricter. Previously the contract was "delete
  exactly the `ACTIVITY_FAILED`/`WORKFLOW_FAILED` events for this instance,
  nothing else." It now also requires: **if `WORKFLOW_FAILED` records a
  `SignalTimeoutException` as its cause, also delete the instance's
  highest-sequenced `SIGNAL_TIMEOUT` event.** `AbstractJdbcWorkflowStore` (and
  therefore `PostgresWorkflowStore`) and the `maestro-test` in-memory store
  both implement the new rule; **if you maintain a custom `WorkflowStore`
  implementation with your own `deleteFailureEvents`, you must add this
  exceptionType-gated deletion or `$maestro:retry` will silently loop forever**
  on any workflow that failed because a timeout-guarded `awaitSignal` timed
  out: replay will keep finding the `SIGNAL_TIMEOUT` memo at its sequence slot
  and deterministically re-raise the same timeout on every retry attempt,
  with no error surfaced (the command dispatcher reports `RETRIED`, not a
  failure). A plain workflow-code failure after a *caught* gate timeout is
  unaffected — the exceptionType gate exists precisely so that memo survives
  retry undisturbed (deleting it unconditionally would resurrect the Issue 19
  replay-divergence bug through the retry door — see the fix's rationale in
  `docs/open-issues.md` Issue 19).

### Bug Fixes

- **Two `parallel()` branches parking at the same time no longer fail the
  workflow** (`docs/open-issues.md` Issue 21). **Behavioural fix — read this if
  you use `parallel()`.** Every branch thread of a fork writes its own park
  status into the one instance row, and that write was an unguarded,
  un-retried read-modify-write. Two branches whose reads both preceded either
  write — the shape `docs/cross-service.md` sells, "fan out and await both
  replies" — made the loser throw `OptimisticLockException`, which escaped
  `parallel()` into workflow author code, into the executor's generic
  `catch (Exception)`, and so into a workflow durably recorded `FAILED` **with
  saga compensations run** — real refunds and inventory releases for work that
  never failed. `FAILED` is not active, so recovery never healed it, and manual
  retry refuses a compensated saga. The status write is now a bounded retry
  against a *fresh* read (the same idiom the terminal write already used), with
  the terminal guard re-evaluated on every attempt; on exhaustion it stands
  down rather than propagating, because the status column is an advisory hint
  for the recovery poller and the event log is the durable truth. Reproduced
  deterministically on the natural race before the fix, and the fix is
  mutation-proven.
- **Parked workflows now ride out transient store outages instead of
  failing** (`docs/open-issues.md` Issue 20). Previously, a `RuntimeException`
  raised by the store during a parked workflow's periodic wake-recheck probe
  — `standDownIfTerminated`'s instance read, the signal-poll read inside a
  parked `awaitSignal`'s recheck loop, or the timer-row recheck inside a
  parked `sleep()` (Issue 17) — propagated out of the park loop and durably
  marked a healthy, still-waiting workflow `WORKFLOW_FAILED`, running its
  compensations. These probes only ever notice something that happened on
  another node (a cross-node terminate, a signal or timer fire missed by the
  local notifier) and never write durable state, so a failed probe now logs a
  rate-limited `WARN` and the park simply continues to the next
  `maestro.signal.wake-recheck-interval` chunk — cross-node terminate
  convergence and cross-node wake are delayed by at most one interval, never
  by a failure. Found by the chaos harness's PR-gate re-proof of Issue 19's
  fix (a 39s store partition exceeding the connection pool's timeout); pinned
  by `ParkedProbeStoreOutageTest`.

- **Timed-out `awaitSignal` calls are now memoized and replay
  deterministically** (`docs/open-issues.md` Issue 19). Previously a timed-out
  await left its sequence slot empty; a recovery replay whose awaited signal
  had arrived late consumed it at that slot and diverged from the original
  execution — observed as a saga compensating at the wrong gate and leaking
  its reserved resource after a routine rolling restart. The live timeout path
  now appends a `SIGNAL_TIMEOUT` event (signal name + timeout in the payload)
  before throwing, and replay re-raises the timeout from the log alone; the
  late signal stays durably unconsumed for a later await of the same name.
  **Observable changes:** event logs no longer contain timed-out-await
  sequence gaps (tooling that asserted the old "designed gap" positions must
  expect contiguous logs with `SIGNAL_TIMEOUT` events instead), and
  `$maestro:retry` of a workflow that failed *because of* a signal timeout
  deletes that failing timeout memo so the retried await re-drives — earlier
  caught gate timeouts still replay identically after a retry. Found by the
  multi-instance chaos harness; pinned by `SignalTimeoutReplayDeterminismTest`.
  **If you maintain a custom `WorkflowStore`, read "For third-party
  `WorkflowStore` implementers" above — this is a behavioural contract
  change, not just an engine fix.**

- **A stale run whose event append collides with a concurrent runner now
  stands down instead of failing the workflow** (`docs/open-issues.md`
  Issue 18). Previously, when a node lost its instance lock mid-run (frozen
  past the lock TTL, partitioned, or the no-lock-backend race) and a peer
  adopted the workflow, the stale node's resumed run hit the store's
  `(workflow_instance_id, sequence_number)` unique guard — and the resulting
  `DuplicateEventException` was treated as a workflow failure: a workflow
  that had succeeded on the adopting node was durably marked `FAILED` and
  its saga compensations ran, reversing completed work. The executor now
  treats the collision as "another run owns this workflow's progress" and
  stands down like the shutdown/termination cases: nothing is written, no
  compensation runs, the concurrent runner's durable outcome governs (and
  with no concurrent runner the instance stays recoverable). Memoized
  activity-result adoption inside the activity proxy is unchanged. Found by
  the multi-instance chaos harness; pinned by
  `WorkflowExecutorDuplicateEventStandDownTest`.

- **Cross-node timer fires (and cancels) now wake the sleeping workflow**
  (`docs/open-issues.md` Issue 17). Previously, in any multi-instance
  deployment, a timer fired by the timer-poller leader on a node other than
  the one whose virtual thread was parked in `workflow.sleep()` durably
  marked the timer `FIRED` but woke nothing — the workflow wedged forever
  until its owning node restarted. A parked `sleep()` now re-reads its
  durable timer row every `maestro.signal.wake-recheck-interval` (default
  30s, unchanged — the property now bounds cross-node timer-fire,
  timer-cancel and terminate latency as well as signal latency), so a
  remote fire or cancel takes effect within one interval. Single-node
  behaviour is unchanged: a local fire still unparks instantly.

- **Internal resilience fix:** a lock-renewer thread that failed to start no
  longer leaves the instance lock reported as `NO_BACKEND` while it is
  actually held (which skipped `release()` and blocked the workflow ID
  cluster-wide), and a renewer-start failure no longer permanently disables
  renewer-starting for every future lock acquisition on the node — both are
  internal `WorkflowInstanceLockManager` robustness fixes with no
  configuration or API surface change.

---

## 0.4.0

This release closes out `docs/open-issues.md` Issues 13, 14, and 15 — all
three found as a side effect of the 0.3.0 hardening pass rather than planned
up front. It includes a source-breaking SPI change and a new `Error`-based
control-flow signal — read [Breaking Changes](#breaking-changes) before
upgrading.

Maestro is pre-1.0; semantic versioning guarantees don't apply yet, but
breaking changes are still called out explicitly below so nothing is a
surprise.

---

### New Features

#### Cancelling a parked timer now unparks the workflow with a catchable outcome

Previously, cancelling a timer a workflow was currently parked on (via
`TimerManager.cancelTimer`) left the workflow stranded in `WAITING_TIMER`
forever — the same silent-stall shape as the original Issue 2, just with a
different trigger (`docs/open-issues.md` Issue 13). `TimerManager.cancelTimer`
is removed; the only supported entry point is now
`WorkflowExecutor.cancelTimer(String workflowId, String timerId, UUID
timerDbId)`. Cancelling a timer a workflow is parked on now unparks it and
raises a new, catchable `TimerCancelledException` at the `sleep()` call site —
memoized as a new `TIMER_CANCELLED` event at the same log slot `TIMER_FIRED`
would have used, so replay reproduces the same outcome deterministically, with
no store read. Left uncaught, the workflow fails with compensation, exactly
like any other uncaught workflow exception — a defined outcome, never a
silent stall. Cancelling a timer nothing is parked on is unaffected (still a
plain row update). See `docs/concepts.md` § "Cancelling a timer" for the full
semantics and `docs/open-issues.md` Issue 13 for the failure-mode table. Closes
Issue 13.

#### Admin dashboard Retry and Terminate are now functional end-to-end

`$maestro:retry` and `$maestro:terminate`, published by the admin dashboard's
Retry/Terminate buttons, were previously signals nothing consumed — a click
produced a success flash message for an action that had no effect
(`docs/open-issues.md` Issue 15). Both now reach a new
`AdminCommandDispatcher` in the Spring Boot starter, wired ahead of ordinary
signal delivery so an admin command is structurally invisible to a workflow's
own `awaitSignal()`:

- **Terminate** durably marks the target instance `TERMINATED` — no
  compensation, no activity interruption, prompt local eviction of a parked
  thread wherever it's running. Safe to call from any node (an optimistic
  version check is the arbiter, not the instance lock).
- **Retry** discards the target's memoized failure (`ACTIVITY_FAILED`/
  `WORKFLOW_FAILED`), moves it `FAILED → RUNNING`, and relaunches it like
  crash recovery: everything before the failure replays from its stored
  result, and the step that failed genuinely **re-executes live**, with a
  fresh retry-policy budget.

Both are idempotent under at-least-once redelivery, and an unroutable command
(unknown name, or a workflow type with no registration on the receiving node)
dead-letters instead of being silently dropped. See `docs/admin.md` for the
full semantics, a per-state idempotency table, and the (honestly stated) lack
of authentication/authorization on the command path. Closes Issue 15.

---

### Behaviour Changes

- **`SagaManager.compensate()` no longer risks re-invoking a completed
  compensation on replay.** A recovery re-run previously re-appended
  `COMPENSATION_STARTED`/`COMPENSATION_COMPLETED` unconditionally, relying on
  the event log's unique index to silently absorb the duplicate — harmless
  for the append itself, but a manually-registered compensation
  (`wf.addCompensation(Runnable)`, as opposed to a `@Compensate` activity) is
  not memoized the way an activity result is, so a compensation that
  completed just before an interruption could be genuinely re-run on
  recovery. Each compensation entry (sequential and parallel loops alike) now
  gets its own reserved sequence block that is checked before the action
  runs, mirroring how every other event-emitting engine path already skips
  replayed work. No API change; not observable except that a rare
  double-execution can no longer happen. Closes
  `docs/open-issues.md` Issue 14.
- **A terminal workflow (`TERMINATED`, `COMPLETED`, `FAILED`) can no longer
  be resurrected by a late signal, timer fire, or wake.** Required for
  Terminate to be trustworthy under redelivery and cross-node races; closes a
  hazard found while implementing Issue 15, not a separately numbered issue.
- **Admin dashboard Retry and Terminate buttons now measurably change
  workflow state.** See [New Features](#new-features) above. Closes Issue 15.
- **Cancelling a timer a workflow is parked on now produces a defined,
  catchable outcome instead of an indefinite stall.** See
  [New Features](#new-features) above. Closes Issue 13.

---

### Breaking Changes

Three changes are source-breaking or introduce a new catch-hazard. All are
deliberate, pre-1.0 rulings — see `docs/open-issues.md` Issues 13 and 15 for
the reasoning behind each.

1. **`WorkflowStore.markTimerCancelled` changes signature from `void` to
   `boolean`.** Source-breaking for any third-party `WorkflowStore`
   implementation — same precedent as 0.3.0's `findTimer` addition. The
   return value is the CAS outcome (`true` only if this call actually moved
   the row `PENDING → CANCELLED`), needed so a cancel racing a fire can tell
   whether it won. `AbstractJdbcWorkflowStore`/`PostgresWorkflowStore` and the
   `maestro-test` in-memory store both implement it; no action needed if you
   use either unmodified.
2. **`WorkflowStore.deleteFailureEvents(UUID instanceId)` is a new abstract
   SPI method.** Source-breaking for any third-party `WorkflowStore`
   implementation. It must delete exactly the events of type
   `ACTIVITY_FAILED` and `WORKFLOW_FAILED` for the given instance and return
   the count deleted — no other event type, including compensation and
   success events, may be touched. This is what makes Retry honest: without
   it, a retried workflow's failed step deterministically replays the stored
   failure instead of re-executing. `AbstractJdbcWorkflowStore` and the
   `maestro-test` in-memory store both implement it; no action needed if you
   use either unmodified.
3. **`TimerManager.cancelTimer(UUID)` is removed.** It was package-private
   with zero production callers (only exercised by its own test), so this is
   not expected to affect any application code — flagged here for
   completeness since it is an API removal. The replacement,
   `WorkflowExecutor.cancelTimer(String, String, UUID)`, is public and is now
   the only supported cancellation entry point.
4. **New `Error` type: `WorkflowTerminatedException`.** Same catch-hazard as
   0.3.0's `ExecutorShutdownException` (Breaking Change 2 in that release):
   it extends `java.lang.Error`, not `MaestroException`, specifically so an
   ordinary workflow-author `catch (Exception e)` around `awaitSignal()`/
   `sleep()` cannot intercept it and misrecord a `$maestro:terminate` command
   as a workflow failure. If your own code unwraps a reflection or
   `CompletableFuture` completion exception, check `instanceof Error` before
   `instanceof Exception` — see `CLAUDE.md` § Coding Standards, which now
   documents both control-flow signals under one shared rationale.

---

### New API Surface

- `WorkflowExecutor.cancelTimer(String workflowId, String timerId, UUID
  timerDbId)` → `boolean` — cancels a timer; returns whether this call's CAS
  won.
- `WorkflowExecutor.retryWorkflow(String workflowId, WorkflowRegistration
  registration)` → `RetryOutcome` (`RETRIED`, `NOT_FAILED`, `NOT_FOUND`,
  `ALREADY_RUNNING_LOCALLY`, `LOCK_HELD_ELSEWHERE`,
  `COMPENSATED_NOT_RETRYABLE`).
- `WorkflowExecutor.terminateWorkflow(String workflowId, @Nullable String
  reason)` → `TerminateOutcome` (`TERMINATED`, `ALREADY_TERMINAL`,
  `NOT_FOUND`).
- `TimerCancelledException extends MaestroException` — thrown from
  `sleep()`/`WorkflowContext.sleep()` when the timer being waited on was
  cancelled; catchable, unlike the two `Error`-based control-flow signals.
- `WorkflowTerminatedException extends Error` — see
  [Breaking Changes](#breaking-changes) above.
- `LifecycleEventType.TIMER_CANCELLED` — published when a parked timer is
  cancelled (live or healed on recovery).
- `LifecycleEventType.WORKFLOW_RETRIED` — published on a successful
  `$maestro:retry`; projected by the admin dashboard's `EventProjector` to
  status `RUNNING`.

---

### Operator Notes

- **Admin Retry and Terminate are now real actions**, not inert buttons.
  Retry is for transient failures (an external dependency that has since
  recovered) — it is not a way to correct bad workflow logic or bad input.
  Retrying a workflow whose saga already ran compensations is **not
  supported**: Maestro detects it (a `COMPENSATION_STARTED` event in the
  log) and refuses with the new `COMPENSATED_NOT_RETRYABLE` outcome — logged
  at `WARN` and acknowledged as a safe no-op, instance left untouched —
  rather than risk corrupting the replay (compensations re-run or get
  wrongly skipped, or the run's terminal event is lost). Terminate stops any
  active workflow, including one currently compensating, with no
  compensation and no activity interruption. Full semantics, an idempotency
  table, and the security posture (no authentication on the command path —
  restrict with Kafka ACLs on `maestro.signals.*` if this matters for your
  deployment) are in `docs/admin.md`.
- **No new configuration properties** in this release, and no database
  migration — `deleteFailureEvents` is a `DELETE` against existing columns,
  and the timer/event schema is unchanged.

---

### Known Limitations

`docs/open-issues.md` Issues 11 and 12 remain open by deliberate design
decision, unchanged by this release — no lock fencing, and recovery polling
scales linearly with the active workflow count. See 0.3.0's
[Known Limitations](#known-limitations-1) below, which still applies.

---

### See Also

- [`docs/open-issues.md`](open-issues.md) — Issues 13, 14, and 15 in full:
  what was wrong, the design docs behind each fix, and every pinning test.
- [`docs/admin.md`](admin.md) — Retry/Terminate semantics, the idempotency
  table, and the security posture.
- [`docs/concepts.md`](concepts.md) — timer cancellation semantics under
  "Cancelling a timer".

---

## 0.3.0

This release closes out a release-readiness hardening pass: nine of the ten
issues found during Maestro's first real-backend verification effort
(`docs/open-issues.md`, tracked as PR #27's follow-up work) are fixed, the
tenth (test coverage) is closed, and two are kept open by deliberate design
decision. It includes source-breaking SPI and API changes — read
[Breaking Changes](#breaking-changes-1) before upgrading.

Maestro is pre-1.0 (`0.3.0-SNAPSHOT`); semantic versioning guarantees don't
apply yet, but breaking changes are still called out explicitly below so
nothing is a surprise.

---

### New Features

#### Bounded redelivery and dead-lettering, all transports

A signal or task handler that throws no longer loses the message. Every
transport — Kafka, Postgres, RabbitMQ — now redelivers with exponential
backoff and, once the attempt budget is exhausted, routes the message to a
durable, inspectable dead-letter destination instead of dropping it or
retrying it forever:

| Transport | Destination | Created by |
|---|---|---|
| Kafka | `<topic>.DLT` (suffix configurable) | **The operator** — Maestro never creates topics (see the [upgrade checklist](#operator-upgrade-checklist)) |
| Postgres | The same queue row, in a new `DEAD_LETTER` status | Nothing to create — it's a status, not a new destination |
| RabbitMQ | `<queue>.dlq`, bound to a `maestro.dead-letter` exchange | The module self-declares it idempotently, like the rest of its topology |

New inspect/replay API on the Postgres transport:
`PostgresWorkflowMessaging.listDeadLetterSignals`,
`listDeadLetterTasks`, `replaySignal(UUID)`, `replayTask(UUID)`. Kafka and
RabbitMQ dead letters are inspected and replayed with ordinary broker
tooling — recipes in `docs/configuration.md` § Redelivery and Dead-Letter
Properties.

Default policy: 10 total attempts, exponential backoff from 1s to a 30s
ceiling (~2.5 minutes of tolerance) — configurable via
`maestro.messaging.redelivery.*` (see [Configuration](#configuration)).

This closes `docs/open-issues.md` Issue 1, previously the most serious open
defect: a transient handler failure — a brief database blip, a momentary
downstream outage — silently and permanently dropped a signal, and the
workflow waited forever for something that would never arrive.

#### `MaestroHealthIndicator`

`io.b2mash.maestro.spring.health.MaestroHealthIndicator`, auto-configured
when Spring Boot Actuator is on the classpath. Reports:

- `DOWN` if the store is unreachable (a bounded 2-second probe, so a hung
  store can't hang `/actuator/health` indefinitely) or if a poller that has
  actually started has since died.
- `"starting"` (not `DOWN`) for a poller that hasn't started yet — this
  distinguishes a normal boot/rolling-deploy window from a real fault, so a
  Kubernetes readiness probe doesn't flap on every deploy.
- `"disabled"` (not `false`) for the recovery poller when
  `maestro.recovery.enabled=false` — a deliberate operator choice, not a
  fault.
- `UP` otherwise, with details: store reachability, both poller states, and
  the locally running workflow count.

Closes Issue 8 (`CLAUDE.md` documented this package before it existed).

#### Timer self-healing on replay

A timer that fires and is marked `FIRED` in the store, but whose
`TIMER_FIRED` event is lost to a crash before it's appended to the workflow's
event log, previously stranded the workflow in `WAITING_TIMER` forever — no
poller would ever look at an already-`FIRED` row again. Replay now consults
the timer row directly (new `WorkflowStore.findTimer` — see
[Breaking Changes](#breaking-changes-1)) and, finding it already `FIRED`, heals
by appending the missing event and continuing, rather than re-parking.
Closes Issue 2.

#### Configurable shutdown timeout, signal wake-recheck interval, and activity lock prefix

Three settings that were previously hardcoded now bind under `maestro.*`,
with unchanged defaults — see [Configuration](#configuration). Closes
Issues 7 and 9.

#### `maestro.admin.events.enabled` now actually works

Setting it to `false` genuinely stops lifecycle event publishing (all event
families — workflow, activity, signal, timer, and compensation).
`maestro.admin.events.topic` is kept as a deprecated alias for
`maestro.messaging.topics.admin-events`. Closes Issue 6.

---

### Configuration

New properties (all optional; defaults preserve prior behaviour except where
prior behaviour was itself the defect being fixed):

| Property | Default | Purpose |
|---|---|---|
| `maestro.messaging.redelivery.max-attempts` | `10` | Total delivery attempts, including the first. All transports. |
| `maestro.messaging.redelivery.initial-interval` | `1s` | Backoff before the second attempt. |
| `maestro.messaging.redelivery.multiplier` | `2.0` | Backoff growth factor per failure. |
| `maestro.messaging.redelivery.max-interval` | `30s` | Backoff ceiling. |
| `maestro.messaging.redelivery.dead-letter-suffix` | `.DLT` | Kafka dead-letter topic suffix. |
| `maestro.messaging.redelivery.dead-letter-exchange` | `maestro.dead-letter` | RabbitMQ dead-letter exchange name. |
| `maestro.shutdown.timeout` | `30s` | How long graceful shutdown waits for in-flight workflows to drain. Was a hardcoded constant. |
| `maestro.signal.wake-recheck-interval` | `30s` | How often a parked workflow re-reads the store for a missed signal. Was a hardcoded constant; bounds cross-node signal latency for Kafka-without-Valkey deployments. |

Changed behaviour on existing properties:

- `maestro.admin.events.enabled` — now wired; previously read by nothing.
- `maestro.admin.events.topic` — now a documented, deprecated alias for
  `maestro.messaging.topics.admin-events`. If both are set to different
  values, the messaging property wins and a WARN names both.
- `maestro.lock.key-prefix` — now also applied to the activity execution
  lock, not just the workflow instance lock.

**Also documented, not new:** spring-kafka's `DefaultErrorHandler` treats a
handful of exception types (deserialization failures, `ClassCastException`,
other conversion errors) as fatal — it sends those records straight to the
dead-letter topic on the first attempt, bypassing the configured
`max-attempts` budget entirely. This is spring-kafka's own default
classification, not something Maestro configures. See
`docs/configuration.md` § Redelivery and Dead-Letter Properties.

Full reference: `docs/configuration.md`.

---

### Behaviour Changes

- **Lifecycle event publishing is off the workflow thread.** Previously,
  `startWorkflow` and every terminal transition called
  `messaging.publishLifecycleEvent(...)` inline; a Kafka producer blocked on
  metadata for a missing topic could add up to `max.block.ms` (60s by
  default) to the workflow's hot path. It now runs on a bounded, dropping
  executor owned by the engine — `startWorkflow` returns promptly regardless
  of transport latency, and sustained backpressure is logged (rate-limited
  WARN) rather than silently absorbed. Closes Issue 3.
- **Signal handler failures are redelivered, not dropped.** See
  [New Features](#new-features-1) above. This is the headline behaviour change
  in the release.
- **Shutdown mid-compensation no longer records a compensation failure.** A
  node stopping while a saga's compensations are running now leaves the
  workflow `COMPENSATING` (active, recoverable) for the next node to finish,
  instead of recording the interrupted compensation step as failed. Closes
  Issue 5.
- **A `catch (Exception e)` around `awaitSignal()`/`sleep()` can no longer
  swallow a shutdown.** See `ExecutorShutdownException` in
  [Breaking Changes](#breaking-changes-1). Closes Issue 4.

---

### Breaking Changes

Four changes are source- or behaviour-breaking. All are deliberate,
pre-1.0 rulings — see `docs/open-issues.md` for the reasoning behind each.

1. **`WorkflowStore.findTimer(UUID workflowInstanceId, String timerId)` is a
   new abstract SPI method.** Source-breaking for any third-party
   `WorkflowStore` implementation — it will no longer compile against this
   version without adding the method. A `default` method returning
   `Optional.empty()` was considered and rejected: it would silently degrade
   the durability guarantee the method exists to provide (a store that
   can't answer "is this timer already fired?" can't heal Issue 2's stall).
   `AbstractJdbcWorkflowStore` (and therefore `PostgresWorkflowStore`) and
   the `maestro-test` in-memory store both implement it; no action needed if
   you use either unmodified.

2. **`ExecutorShutdownException` now extends `java.lang.Error`, not
   `MaestroException`.** It is no longer part of the `MaestroException`
   hierarchy and can no longer be caught by a `catch (Exception e)` or
   `catch (MaestroException e)` block. This is deliberate — the entire point
   of the change is that ordinary workflow-author exception handling cannot
   intercept a shutdown signal and misrecord it as a workflow failure — but
   it breaks any code that specifically caught `ExecutorShutdownException`
   expecting it to be a `RuntimeException`/`MaestroException`. Anywhere your
   own code unwraps a reflection or `CompletableFuture` completion exception
   (`Method.invoke`, `CompletionException`/`ExecutionException`), check
   `instanceof Error` before `instanceof Exception` — see `CLAUDE.md` §
   Coding Standards for the full rationale (Temporal takes the same
   approach for the same reason).

3. **`KafkaMessagingConfig` gained new record fields** (`maxAttempts`,
   `initialInterval`, `multiplier`, `maxInterval`, `deadLetterSuffix`) to
   carry the redelivery policy. Source-breaking for any code constructing
   `KafkaMessagingConfig` directly rather than through
   `KafkaMessagingAutoConfiguration`. Four in-repo call sites were updated;
   if you construct it yourself, add the new fields (or use
   `KafkaMessagingConfig`'s existing defaults convenience, if you did before
   this change).

4. **`@MaestroSignalListener` now requires a `KafkaTemplate` bean in the
   application context.** Previously, a poison or unprocessable record on
   that channel was silently logged and skipped after ten attempts, with no
   dead-letter destination — because there was nowhere to route it. Now that
   exhausted records are dead-lettered, the bean post-processor resolves a
   `KafkaTemplate` (by bean name `maestroKafkaTemplate`, falling back to
   by-type) to publish to the dead-letter topic. In practice this bean is
   always present — the same auto-configuration registers both the template
   and the listener post-processor — but an application that registers the
   post-processor by hand without a `KafkaTemplate` will now fail fast at
   startup instead of silently dropping poison records at runtime. Loud is
   the intended failure mode.

---

### Database Migrations

Two new Flyway migrations, both applied automatically on startup — no manual
action required:

- **`V3__timer_lookup_index.sql`** (`maestro-store-postgres`, version band
  1–99) — adds `idx_wf_timer_lookup ON maestro_workflow_timer
  (workflow_instance_id, timer_id)`, needed by the new `findTimer` query.
  Not unique by design (a benign race between two nodes scheduling the same
  live sleep is resolved by the event log's unique index, not this one). No
  table or column changes.
- **`V201__redelivery_dead_letter.sql`** (`maestro-messaging-postgres`,
  version band 200–299) — adds `attempts`, `next_attempt_at`, `last_error`
  columns to both `maestro_signal_queue` and `maestro_task_queue`, widens
  their status `CHECK` constraints to admit `DEAD_LETTER` (keeping `FAILED`
  valid only for rows already written by earlier versions — the code never
  writes `FAILED` again), and repoints each table's partial pending-status
  index from `created_at` to `next_attempt_at` so the redelivery-eligibility
  check (`status = 'PENDING' AND next_attempt_at <= now()`) can use it.
  Claim ordering itself is unchanged — still `ORDER BY created_at`
  (insertion order); the index change is about the filter, not the sort.

**Optional, manual, one-time rescue SQL** for rows stranded `FAILED` by the
pre-fix Postgres transport (see the [upgrade checklist](#operator-upgrade-checklist)):

```sql
UPDATE maestro_signal_queue SET status = 'PENDING', next_attempt_at = now() WHERE status = 'FAILED';
UPDATE maestro_task_queue   SET status = 'PENDING', next_attempt_at = now() WHERE status = 'FAILED';
```

Nothing runs this automatically — a deployment that never hit the old defect
has no `FAILED` rows to rescue, and running it unconditionally would be a
no-op for them anyway.

---

### Operator Upgrade Checklist

1. **Pre-create a Kafka `.DLT` topic for every topic Maestro consumes** —
   each `maestro.tasks.{taskQueue}` topic, each `maestro.signals.{service}`
   topic, and each topic behind an `@MaestroSignalListener` — before
   upgrading. Maestro never auto-creates topics, dead-letter topics
   included. **This is fail-safe, not fail-silent:** if a dead-letter topic
   is missing, the publish fails, the offset is not committed, and the
   record is attempted again — consumption stalls loudly (visible in logs
   and consumer lag) instead of losing the message. But it does stall that
   topic on the affected node until the topic exists, and — because Maestro's
   listener containers run with the default concurrency of one consumer
   thread per topic — that means the *whole topic* on that node, not just
   the affected partition.
2. **No RabbitMQ action needed.** The dead-letter exchange and per-queue
   `.dlq` are self-declared idempotently by the module, consistent with how
   it already declares its other topology.
3. **No manual migration step.** `V3` and `V201` run automatically via
   Flyway on first startup against each database.
4. **Optional: run the rescue SQL above** if this deployment previously hit
   the Postgres transport's signal-loss defect and has rows stuck `FAILED`.
   Skippable otherwise.
5. **If you construct `KafkaMessagingConfig` directly**, or **catch
   `ExecutorShutdownException` expecting a `MaestroException`**, or **have a
   third-party `WorkflowStore` implementation**, read
   [Breaking Changes](#breaking-changes-1) before upgrading — those three are
   compile-time breaks, not runtime surprises, so they'll surface
   immediately on build.
6. **If you run `@MaestroSignalListener` with a hand-registered bean
   post-processor** (rather than through Maestro's own auto-configuration),
   confirm a `KafkaTemplate` bean is present — see Breaking Change 4.

---

### Known Limitations

Two are deliberate, accepted trade-offs, not defects — closing either is a
real design/SPI change, not a patch:

- **No lock fencing (`docs/open-issues.md` Issue 11).** A node that loses
  its instance lock (e.g. to a GC pause longer than the TTL) keeps running;
  split-brain is tolerated rather than prevented. Duplicate *persisted
  results* are still impossible (the event log's unique index rejects the
  loser's writes), but duplicate *side effects* are not — **activities must
  be idempotent.** Closing this needs fencing-token validation added to the
  `WorkflowStore` SPI, touching every implementation.
- **Recovery polling scales linearly with the active workflow count**
  (`docs/open-issues.md` Issue 12). `getRecoverableInstances()` has no
  service or staleness filter, so every node re-reads the whole active set
  on every poll cycle and probes the lock for each foreign-owned instance;
  lock renewal is also serial (one round-trip per held lock per TTL/3). Fine
  at today's scale; **benchmark before tuning** if you expect a node to hold
  thousands of parked workflows — this needs measurements before a fix, not
  a blind optimisation.

Three more were found during this release's work and remain open,
unscheduled:

- **`CANCELLED` timers can strand a replaying workflow**
  (`docs/open-issues.md` Issue 13) — the same failure shape as the original
  Issue 2 (permanent, silent stall), triggered instead by
  `TimerManager.cancelTimer` being called on a timer a workflow is currently
  parked on. Narrower and rarer than Issue 2 was, but not yet fixed, and the
  underlying semantics ("what should cancelling a timer someone is waiting
  on even mean?") aren't decided yet either.
- **`SagaManager` re-appends `COMPENSATION_STARTED` on replay**
  (`docs/open-issues.md` Issue 14) — currently harmless (the event log's
  unique index silently absorbs the duplicate), but not the same
  replay-skip discipline every other event-emitting engine path follows.
- **Admin dashboard retry/terminate buttons are not functional
  end-to-end** (`docs/open-issues.md` Issue 15) — they publish
  `$maestro:retry`/`$maestro:terminate` signals successfully, but nothing in
  the engine or starter consumes those two internal signal names yet, so
  the target workflow never actually changes state. "Send Signal" is
  unaffected. Pre-existing, not introduced by this release.

**Update (0.4.0):** all three of the above — Issues 13, 14, and 15 — are
fixed. See [0.4.0](#040) above and `docs/open-issues.md` for commits and
pinning tests. This list is kept as the historical record of what was known
at 0.3.0's release.

---

### See Also

- [`docs/open-issues.md`](open-issues.md) — the full, per-issue detail this
  document summarises: what was wrong, why, where, how it was fixed, and the
  tests that pin each fix.
- [`docs/configuration.md`](configuration.md) — complete configuration
  reference, including the new redelivery/dead-letter and shutdown/signal
  properties.
- [`docs/maestro-architecture.md`](maestro-architecture.md) — updated
  architecture reference for signal-loss handling, timer recovery, and
  shutdown semantics.
