# Release Notes — Maestro

## Unreleased — pilot readiness P1 (part 1): one lifecycle owner for runs

### Fixed — a launch admitted before shutdown is waited for; an admin Retry during shutdown is refused (Issue 80)

- **What was wrong.** The drain made one weakly consistent pass over the runs it knew. A launch already past its shutting-down check but not yet registered when the pass began was never waited for: `shutdown()` could return while it still held its instance lock, and the run went on after the engine had stopped. `retryWorkflow` (the dashboard's Retry, `$maestro:retry`) had no shutdown check at all: a command consumed during or after the drain reopened the row and launched a run nothing waited for.
- **What changes.** The executor has a lifecycle registry (`WorkflowExecutor.lifecycle()`, `LifecycleRegistry`, internal until 1.0). `startWorkflow`, recovery adoption and `retryWorkflow` join it before they take the instance lock and leave it after the lock's release; shutdown closes it and copies its runs in one critical section, so every launch is either drained or refused. A refused start throws `IllegalStateException` as before; a refused recovery adoption is skipped (no lock, no CAS, no epoch bump); a refused **Retry now throws `IllegalStateException` (`WorkflowExecutor is shutting down — cannot retry workflow '…'`)** with nothing read or written, and the admin dispatcher rethrows it, so the record is not acknowledged. The refusal is the new **`ExecutorShuttingDownException`** (a `MaestroException`; a refused *start* keeps its `IllegalStateException`), and the dispatcher logs that type, and only that type, at `WARN` without a stack trace; any other exception keeps its `ERROR` and stack trace. **Until Maestro's Kafka containers stop inside the drain (Issue 69, a later P1 task)** the record is redelivered to the same draining node and refused again. If the container stops before the redelivery budget is spent — counted from the record's first refusal, so always with the default 10 attempts (about 2.5 min) — a peer consumes it after the rebalance; a budget spent before the container stops (possible only when its backoff fits between the first refusal and the stop) dead-letters it; `maestro.messaging.redelivery.enabled=false` drops it (Operations §15.1) (`ShutdownAdmissionTest`, `LaunchOutcomeRegistryTest`, `LifecycleRegistryTest`).
- **`shutdown()` from a run's own thread.** Called from a workflow, an activity body, a branch, or the thread still launching a run (an observer callback, a store decorator), `shutdown()` does not wait for that run — it could not end while its own thread waits — and gives it up with the rest, with a `WARN` naming it. Before, the drain waited out its whole deadline for it.
- **New:** `WorkflowExecutor.isShuttingDown()`.
- **Logs.** The summary's `{n} run(s) in flight at start` now counts the runs in the registry when shutdown began, launches not yet started included. `Interrupted while waiting for workflow '…' during shutdown` becomes `Interrupted while waiting for {n} run(s) during shutdown`.

### Fixed — a run still executing at the shutdown deadline records nothing more (Issue 70)

- **What was wrong.** A run past the deadline kept writing. Spring destroys the host's beans once Maestro's phase has ended, so its activity could fail because of the teardown, record `ACTIVITY_FAILED`, and the workflow could fail and compensate work that never failed.
- **What changes.** At the deadline every run still registered is **given up** (`RunCancellation.abandon()`, sticky): from then on every store call made under its context is refused with `ExecutorShutdownException` without reaching the store (advisory log-line reads and the activity body's own calls excepted), every stop point that checks for a store fault checks for it too, and a failure the workflow code makes of it is routed to the shutdown arm, never to `FAILED` or a compensation. The run ends `runAbandoned(SHUTDOWN)`; its row keeps the active status it had, and a peer recovers it as after a crash at the deadline. A launch still in progress at the deadline skips every launcher write after it — the launcher has no workflow context for the guard, so each write checks the flag itself: a start throws `IllegalStateException` with no row, or `WorkflowNotLaunchedException` if its row was already written (no orphan-signal adoption, no `WORKFLOW_STARTED`, no run); a resume or a Retry's relaunch adopts nothing (`ShutdownGiveUpTest`, `GivenUpLaunchTest`, `StoreFaultGuardAbandonTest`). Give-up refuses *new* store calls: a write already inside the store when it lands can still commit, a legitimate prefix that replay returns, as after a crash. An attempt that fails after the deadline still reaches `EngineObserver.activityFailed` (nothing persisted). A member whose `requestStop()` throws no longer ends `shutdown()`: it is logged and the rest of shutdown runs (a `VirtualMachineError` is rethrown once shutdown has run).
- **Behaviour change.** A **successful** activity result that arrives after the deadline is discarded, and the activity runs once more on the adopting node — at-least-once, as for a crash. The deadline `WARN` now reads `… records nothing for them: it gave them up, so nothing they do from now on is recorded; …`.
- **Not enforced.** A `spring.lifecycle.timeout-per-shutdown-phase` shorter than `maestro.shutdown.timeout` lets Spring destroy host beans before the deadline; Operations §15.4 still requires it to be at least the timeout plus 2 s.

## Unreleased — pilot readiness P2 (tasks 1–3): observer isolation, absorbed store faults, the loop supervisor

### Fixed — an observer's `Error` no longer changes what the engine does (Issues 81, 68 part 2)

- **What was wrong.** The engine contained only a `RuntimeException` from an observer callback. An `Error` — a metrics adapter hitting a `LinkageError`, an `AssertionError` — propagated into whatever engine path made the call: from `workflowResumed` it ended the whole recovery pass and dropped the adoption from its count; from `workflowStarted` it failed a fresh start; from `instanceLockLost` it left the renewal pass, so the other locks lost in that pass were handled one renew interval apart.
- **What changes.** `CompositeEngineObserver` contains every `Throwable` a delegate throws, per callback and per delegate, **except** a `MaestroControlFlowError` and a `VirtualMachineError` (`OutOfMemoryError`, `StackOverflowError`, `InternalError`), which propagate. Every engine type that takes an observer wraps it (`CompositeEngineObserver.contain`), so this holds for an observer wired by hand too (`ObserverErrorIsolationTest`, `CompositeEngineObserverTest`, `WorkflowInstanceLockManagerTest.anObserverErrorOnOneLostLockDoesNotDelayTheOthers`).
- **Logs.** The first containment for a (callback, delegate) pair logs `WARN EngineObserver delegate <class> threw from <callback> — contained; further failures of it from <callback> are logged at DEBUG until a call to it succeeds again` with the stack trace (was a `WARN` on every call); repeats `DEBUG`. The per-call-site `WARN EngineObserver.<callback> threw for workflow '<id>' — ignoring: …` lines are gone; the executor's own lost-lock callback, which is not an observer, logs `WARN The lost-lock callback threw for workflow '<id>' — ignoring: …` if it throws.
- **Activity proxies share the executor's observer and store guard.** New internal-until-1.0 accessors `WorkflowExecutor.guardedStore()` and `WorkflowExecutor.observer()`; the starter's `@ActivityStub` proxies and `TestWorkflowEnvironment` use them, so the activity callbacks' observer failures count on `maestro.observer.errors` and the activities' absorbed store faults on `maestro.store.retries` within the node's one retry episode (`ActivityPathObservabilityTest`). A proxy built by hand over a plain store keeps a guard of its own, which counts its retries through the proxy's observer; pass `guardedStore()` and `observer()` to share the executor's.
- **New meter.** `maestro.observer.errors` (function counter, no tags) over the new `WorkflowExecutor.observerErrorCount()`.
- **Behaviour change.** An observer `AssertionError`/`LinkageError` from `instanceLockAcquired` no longer releases the lock it reports — the acquisition stands. A `VirtualMachineError` still does.

### Changed — a store fault the guard retries away is counted and logged once per episode (Issue 86)

- **What was wrong.** A `StoreUnavailableException` the store guard absorbed by retrying in place was logged at `DEBUG` only and counted nowhere, so a database that drops connections often but briefly was invisible until a fault outlasted the retries.
- **What changes.** Every in-place retry calls the new `EngineObserver.storeRetried(String operation)` (default no-op) and moves the new counter **`maestro.store.retries{operation=append_event|append_event_returning_payload|get_event_by_sequence}`**. Retries are grouped in node-wide episodes: the first retry after 60 s without one logs one `WARN Store unavailable on <op> — retrying in place (attempt n/3): <diagnosis>; further in-place retries are counted on maestro.store.retries and logged at DEBUG until 60 s pass without one`; the rest stay `DEBUG` (`StoreFaultGuardTest.anAbsorbedRetryIsCountedOnceAndTheRunContinues`, `.oneWarnPerEpisode`). Exhaustion keeps its once-per-run latch `WARN`.

### Changed — the signal janitor, the retention sweeper and the one-shot startup recovery run under one loop supervisor (P2)

- **What changes.** New public package `io.b2mash.maestro.core.loop` (**internal until 1.0**): `LoopSupervisor`, `LoopSpec`, `LoopBody`, `Next`, `LoopContext`, `LoopWait`, `LoopSnapshot`, `LoopRole`, `Backoff`, `FailingRule`. A supervisor owns a loop's thread, waits, failure streak, last success and stop; `WorkflowExecutor.supervise(LoopSpec, LoopBody)` runs a loop under the executor (stopped by `shutdown()`), and `WorkflowExecutor.loops()` returns a snapshot of each (`state`, `consecutiveFailures`, `failingSince`, `lastSuccess`, `lastFailureType`, `inIterationFor`, `failing`, notes such as the sweeper's `leader`). The timer poller, the recovery poller, the instance-lock renewer and the two Postgres loops move in later tasks; health does not read the snapshots yet.
- **A store fault is a failed sweep.** The janitor's and the sweeper's store faults, and a lock-backend fault on the sweeper's leader election, now fail the iteration and extend the loop's streak instead of being logged and swallowed (`SignalJanitorTest.aStoreFaultExtendsTheStreak`, `RetentionSweeperTest.aLeaderElectionFaultExtendsTheStreak`). A partial sweep still emits `retentionPass`; the janitor still logs what it purged.
- **Breaking for custom lock backends — a failed leader election throws.** `PostgresDistributedLock.trySetLeader` now throws `LockBackendException` on a `SQLException`, as `tryAcquire`, `renew` and `renewAll` already did, instead of logging `WARN Leader election failed for '<key>': <message>` and answering "not the leader" (`PostgresDistributedLockTest.trySetLeaderWrapsBackendFailure`). `DistributedLock.trySetLeader`'s contract says so: `false` means another candidate holds the claim, a throw means unknown. A broken election table therefore builds a failure streak in the timer poller (under its guard; its clock-skew check still runs, `BackgroundLoopErrorTest.timerPoller_aLeaderElectionFailureGoesThroughTheGuardAndTheSkewCheckStillRuns`) and in the retention sweeper, logged at their cadence; each iteration still does nothing, as for a non-leader. The `WARN` on every poll (720 an hour) is gone. A custom backend that answered `false` on a failure should throw instead.
- **Never interrupted.** None of the three is stopped by an interrupt any more: a stop ends their waits through a latch, a store call in progress finishes, and the loop ends as soon as it returns (part of Issue 64).
- **Renamed tag value.** The one-shot startup recovery (`maestro.recovery.enabled=false`) counts an `Error` on `maestro.loop.errors{loop=startup-recovery}`, pre-registered at zero, instead of `loop=recovery-poller` (`OneShotRecoveryTest.countsUnderStartupRecovery`). `EngineObserver.backgroundLoopError` now names six loops. It ends `DONE` once a pass completes.
- **Logs.** The three loops' failure lines are the guard's text unchanged, now under logger `io.b2mash.maestro.core.loop.LoopSupervisor` (was `io.b2mash.maestro.core.engine.BackgroundLoopGuard`). The janitor's and sweeper's store-fault `WARN`s (`Signal janitor sweep failed after purging …`, `Retention sweep failed after purging …`, `Retention leader election failed for service … — treating as not the leader`) are replaced by that cadence: `ERROR` with the stack trace on the first failure, then — at the default hourly interval — one `ERROR` per further failed sweep. `Signal janitor stopped` / `Retention sweeper stopped` become `Background loop '<name>' stopped`. The start lines `Signal janitor started (…)` / `Retention sweeper started (…)` keep their text but are logged by `io.b2mash.maestro.core.engine.WorkflowExecutor` (was `…engine.SignalJanitor` / `…engine.RetentionSweeper`). The `DEBUG` lines `Signal janitor loop exited` / `Retention sweeper loop exited` are gone; the one-shot's `DEBUG One-shot startup recovery stopped before a pass completed` now appears only for a pass a stop cut short.
- **Health and meters unchanged** for these loops: `signalJanitorRunning` and `retentionSweeperRunning` read the supervisor and mean what they meant.

## Unreleased — pilot readiness P3: scope cuts and the support matrix

**New: [`docs/support-matrix.md`](support-matrix.md)** states what is supported, what is not, and what is untested (shared tables, lock-less nodes, stores other than Postgres, version skew, Postgres versions, connection pools, PgBouncer, Kafka setups). `docs/open-issues.md` is triaged against it: five issues closed (31, 62, 74, 78, 79) and every open issue labelled `blocks-pilot`, `blocks-1.0` or `limitation`, with a ranked index at the top.

### Breaking — a `DistributedLock` is mandatory (Issue 74)

- **What changes.** `WorkflowExecutor.Builder.build()` fails without a lock (`NullPointerException`, message `WorkflowExecutor.MISSING_LOCK_MESSAGE`), and the starter fails startup when no `DistributedLock` bean exists (`IllegalStateException`, `MaestroAutoConfiguration.MISSING_LOCK_MESSAGE`) instead of starting in "single-node mode". With `maestro-store-postgres` on the classpath nothing changes: it provides `PostgresDistributedLock`.
- **No run executes without its instance lock.** A start whose acquire fails — the lock held elsewhere, or the lock backend throwing — is, while this node's recovery poller runs, still persisted `RUNNING` and `startWorkflow` returns its id, but nothing runs on the calling node: the poller is prompted at once and adopts it about a second later if the lock can be taken by then, otherwise at its next full pass. It is reported by the new `EngineObserver.workflowStartDeferred(WorkflowInfo, boolean lockHeldElsewhere)` (default method) and the new counter **`maestro.workflow.start.deferred{workflow, reason=lock_held_elsewhere|lock_unavailable}`**; no `WORKFLOW_STARTED` event or `workflowStarted` callback follows for it (the adopting run reports `workflowResumed`; no persisted marker could make a later start event exactly-once). **With no recovery poller running** (`maestro.recovery.enabled=false`, or before `StartupRecoveryRunner` has started it) the start is **refused** with `LockAcquisitionException` and nothing is persisted (a duplicate id still throws `WorkflowAlreadyExistsException`). A resume whose acquire fails is skipped (recovery retries; a retry reports `LOCK_HELD_ELSEWHERE`). Before, both proceeded unlocked (`MandatoryInstanceLockTest`). New `WARN`: `Workflow '{id}' was persisted but not started here: its instance lock {is held elsewhere | could not be taken (lock backend unavailable)} — the recovery poller adopts it once the lock can be taken (a prompt pass is requested)`.
- **Locks are released by handle.** Every release and every lost-lock stand-down now goes by the `LockHandle` the run's own acquisition returned, never one looked up by workflow id: a run that lost its lock can no longer delete a newer run's lock or stand it down; a resume whose handle was reported lost before it adopted does not bump the lease epoch under the run that now holds the lock; a launch whose lock was lost before it registered does not run at all, and one whose lock is lost after it registered but before its thread enters the workflow method runs no workflow code, on every task queue. A lock lost once the run is executing stands it down at its next stop point; the activity already in flight can still run again on the node that adopts the workflow, so activities must stay idempotent (`LostLockTwinRunTest`, `HandleScopedLockRaceTest`). A deferral also requires the running recovery poller to register the workflow type (else the start is refused), and its prompt ends a wait for task-queue capacity on another queue (`PromptDuringCapacityWaitTest`).
- **No recovery poller on the node (`maestro.recovery.enabled=false`).** A start whose lock is lost between its row's write and its run's registration is relaunched once; if that fails too it throws the new **`WorkflowNotLaunchedException`** (a `MaestroException`): the row *was* persisted and was not launched, and it runs when a node starts with recovery or this node restarts. The one-shot startup pass is not finished while it skipped a row because the lock backend failed (a row held elsewhere is that holder's); it retries after `maestro.recovery.poll-interval` (`RecoveryDisabledLockFailureTest`).
- **Behaviour change — admin Retry takes the instance lock before it reopens the row**, on every node. A lock held elsewhere, or a failing lock backend, now writes nothing: the row stays `FAILED` with its memos and `retryWorkflow` throws `LockAcquisitionException`, which the dispatcher rethrows for redelivery (with `maestro.messaging.redelivery.enabled=false` it is not redelivered: since nothing was written, retry it by hand). A lock lost after the reopen, before the run started, now prompts a recovery poller that registers the type; on a node without one it is met with one relaunch and, if that fails, `WorkflowNotLaunchedException` (the row was reopened: another Retry answers `NOT_FAILED`; it needs a node with recovery for the type, or a restart). A relaunch that finds the row already finished elsewhere converges (`LOCK_HELD_ELSEWHERE` for a Retry, the id for a start) instead of throwing. Before, the row was reopened first and returned `LOCK_HELD_ELSEWHERE` (or `ALREADY_RUNNING_LOCALLY` while the failed run was still leaving this node), left `RUNNING` for recovery. `LOCK_HELD_ELSEWHERE` now means only a launch refused after the reopen, and `ALREADY_RUNNING_LOCALLY` is no longer returned: a failed run still registered after losing its lock is replaced by the relaunch. The instance-lock manager also publishes a backend grant only if no newer handle was published meanwhile; a late grant is rejected and gives back only its own token. The lock backend's own `WARN` now ends `— not running it now; recovery retries it once the lock can be taken` (was `— proceeding unlocked`).
- **Embedders and tests.** Pass `.distributedLock(...)`: `PostgresDistributedLock` in production, `maestro-test`'s `InMemoryDistributedLock` in tests (`TestWorkflowEnvironment` already does). A Spring test context with a store and no lock needs `.withBean(DistributedLock.class, InMemoryDistributedLock::new)`.

### Breaking — services never share Maestro tables (Issues 78, 79; Issue 77 stays open with a runbook)

- **What changes.** The startup advisory of Issue 39 becomes a check at context refresh, before any listener or recovery starts. It reads the active instances under **any** other `maestro.service-name`, of any workflow type, and by default **fails startup** with `ForeignServiceRowsException` naming each name and its count. `maestro.store.foreign-service-check` = `fail` (default) | `warn` (one `WARN` per name; for a rename in progress) | `off`. The read waits at most 5 s; a failed or late read is inconclusive — one `WARN`, `Maestro shared-table check inconclusive: …`, startup continues — so an unreachable database never becomes a restart loop (`StartupForeignServiceCheckTest`, `ForeignServiceCheckTest`). Terminal rows are not read.
- **Upgrading.** A service that shares its `maestro_*` tables with another must move to its own database or schema before upgrading, or set `warn` while it moves. A rename: [Operations §11.5](operations.md#115-rows-recorded-under-another-maestroservice-name), "Finishing a rename". A rolling upgrade of one service is unaffected (its nodes share one name).
- **The root `docker-compose.yml`** gives `sample-payment-gateway` its own `payment_gateway` database (it shared the order service's `maestro` tables). On an existing `pgdata` volume the init script does not run again: `docker compose down -v`, or `CREATE DATABASE payment_gateway`.
- **Logs.** The old `WARN` (`… of a type this node runs …`), `More than 20 other service names hold active instances of this node's workflow types` and `Maestro service-name check skipped` are gone; the new texts are in Operations §11.5.

### API and SPI

- `WorkflowExecutor.startForeignServiceCheck(Map<String, WorkflowRegistration>)` is replaced by `checkForeignServices(ForeignServiceCheckMode)`, which runs the bounded read on its own thread and returns (or throws) once it has an answer. New public types: `ForeignServiceCheckMode` (`io.b2mash.maestro.core.engine`) and `ForeignServiceRowsException` (`io.b2mash.maestro.core.exception`, a `MaestroException`). The starter's new bean `maestroForeignServiceStartupCheck` (`ForeignServiceStartupCheck`) runs it.
- `WorkflowStore.getActiveInstanceCountsUnderOtherServices(String serviceName, int limit)` replaces the `(serviceName, workflowTypes, limit)` form. The JDBC store reads it from the partial active-instance index with two ranges, so it touches only other services' active rows.
- **The `WorkflowStore` SPI is internal until 1.0** (Issue 62, closed): Postgres is the only supported store; methods and defaults change between releases without deprecation.
- `TimerPoller`, `RetentionSweeper` and `WorkflowInstanceLockManager` (package-private) reject a null lock; their single-node branches are gone.

### Tests

Removed with lock-less mode: `NoLockStartRecoveryRaceTest`, `MultiNodeNoLockBackendIT`, three two-runs-of-one-id tests in `ShutdownReturnsBeforeLockReleaseTest`, and the lock-less duplicate of a terminal-resume test. `MaestroEngineHarness` gives a node built without a lock its own in-memory lock.

## Unreleased — Postgres + Kafka only

### Changed — retention and the signal janitor pace their batches and keep to their own service; a startup warning for workflows under another service name (3.11a, 3.11b, Issue 39)

- **What was wrong.**
  - One retention sweep ran up to 300 purge transactions (3 statuses × `max-batches-per-sweep` 100) back to back. On the planning probe a 500-instance batch took 45 ms (p50) and wrote 27 MB of WAL per 20,000 instances, so a full sweep is ≈ 200 MB of WAL in ≈ 14 s (arithmetic), a burst that reaches replicas as lag.
  - The signal janitor deleted every service's stale signals in one unbounded statement, on every node at once: 69,921 rows in one 342 ms statement on the probe, holding their row locks until it committed while its peers' identical statements waited and then deleted nothing.
  - The retention sweep purged every service's workflows with this service's retentions: in tables several services share, the shortest `failed-retention` among them governed everyone's history.
  - Workflows recorded under another `maestro.service-name` were stranded silently.
- **What changes.**
  - **Retention pacing.** Between two batches the sweeper waits as long as the previous batch took, at most 5 s (a third of the 15 s leader TTL, so the pause alone never costs a sweeper its leadership for batches under 10 s). No pause before the first batch or after the last. The database is busy about half the time a sweep runs; the totals are unchanged, spread over about twice the time (`RetentionSweeperPacingTest`, `BatchPacerTest`). A stop ends a pause at once, without an interrupt.
  - **Batched, service-scoped janitor.** The janitor deletes only signals of this service's workflows, at most 1,000 per statement, until a statement deletes fewer, pausing between statements by the same rule, and at most 1,000 statements (1,000,000 signals) per sweep; a larger backlog continues next interval (`SignalJanitorBatchingTest`, `PostgresStoreHygieneTest$ScopedJanitorDelete`). It still runs on every node, without leader election.
  - **Service-scoped retention.** A sweep purges only workflows recorded under this node's `maestro.service-name` (`RetentionSweeperScopeTest`, `PostgresStoreHygieneTest$ScopedPurge`).
  - **The service-name check (Issue 39).** Once per node start, on its own virtual thread `maestro-foreign-service-check` started right after recovery, each node runs one read and logs one `WARN` per other `service_name` (at most 20) holding active instances of a workflow type it registers. It never fails or delays startup and never writes (`StartupForeignServiceCheckTest`, `ForeignServiceCheckTest`, `PostgresStoreHygieneTest$ForeignServiceCounts`). See [Operations §11.5](operations.md#115-rows-recorded-under-another-maestroservice-name).
- **Behaviour change — shared tables.** Each service now purges only its own rows, with its own retentions; before, any service's sweep purged every service's terminal workflows. Rows recorded under a name no node runs any more are no longer purged by anyone (Issue 77): [Operations §11.5](operations.md#115-rows-recorded-under-another-maestroservice-name) lists them and gives a tested manual purge: one statement, safe to run beside live services, repeated until it reports 0. In shared tables a scoped batch can read past a co-tenant's rows (Issue 78).
  - **A co-tenant that does not sweep.** A service sharing the tables that runs with `maestro.retention.enabled=false`, or a build older than the retention sweeper, used to have its finished workflows purged by its neighbours' sweeps. Now nobody purges them. The startup warning counts active rows only, so it does not mention them. List them with the query in §11.5, and purge them with its manual purge if that is what you want.
  - **A blank service name.** An executor built with a blank service name (`WorkflowExecutor.builder(store, serializer, " ")`, possible only without the Spring Boot starter) now fails `startSignalJanitor` with `IllegalArgumentException` (`SignalJanitorBatchingTest.aBlankServiceNameIsRejected`, at the janitor's constructor). Before, its janitor started and deleted every service's stale signals.
- **Logs.**
  - New `WARN` (once per foreign name per node start): `{n} active workflow instance(s) of a type this node runs are recorded under maestro.service-name '{other}', not '{own}'. Recovery adopts only '{own}' instances, so unless a node runs with maestro.service-name={other} these workflows never resume; if no node runs that name any more, start one under it or correct the rows (docs/operations.md §11.5).` New `INFO`s: `More than 20 other service names hold active instances of this node's workflow types; …` and `Maestro service-name check skipped: {throwable}`.
  - Changed `INFO`: `Retention sweep purged … and {} timer(s) in {} batch(es)`; `Signal janitor purged {} unconsumed signal(s) of service '{}' workflows finished before {} in {} batch(es)`; `Signal janitor started (interval={}, terminalRetention={}, batchSize={}, service={})`. New `INFO`: `Signal janitor ended this sweep after {} batches ({} signal(s)); the rest is left to the next sweep, in {}`. No new meter.
- **API / SPI.**
  - `WorkflowStore` gains three defaults, each keeping today's behaviour for a store that does not override it: `deleteUnconsumedSignalsForTerminalInstancesBefore(String serviceName, Instant cutoff, int limit)` (default: the unscoped, unbounded one-argument delete; the janitor then calls once more when it reports 1,000 or more, and that call deletes nothing); `purgeTerminalInstancesBefore(String serviceName, WorkflowStatus, Instant, int)` (default: the unscoped purge); `getActiveInstanceCountsUnderOtherServices(String serviceName, Set<String> workflowTypes, int limit)` (default: empty, so the check logs nothing). The shipped JDBC store and `InMemoryWorkflowStore` override all three.
  - JDBC subclassers: the protected hook `AbstractJdbcWorkflowStore.getServicePurgeCandidatesSql()` (four parameters: status, cutoff, service name, batch size); Postgres overrides it with `LIMIT ? FOR UPDATE SKIP LOCKED`.
  - `WorkflowExecutor.startForeignServiceCheck(Map<String, WorkflowRegistration>)`: the Spring Boot starter calls it; an embedder without the starter calls it once after starting recovery.
  - A custom store keeps compiling; override the overloads for the bound and the scope.
- **Mixed fleets.** Old and new nodes interoperate. An old node's sweep and janitor stay unscoped and unpaced, as before; the retention leader key is the same in both builds, so an old and a new node of one service never sweep at once, and nothing is worse than before the upgrade (traced, not tested). No migration, history or configuration change.

### Fixed — a node adopts only the recovered runs it can run; startup no longer waits for recovery (Issue 30, 3.1, Issue 36)

- **What was wrong** (reproduced at planning time with an in-memory probe, two nodes at `concurrency` 4). A node able to run 4 workflows locked all 40 orphans of a backlog in one recovery pass, and its peer, finding all 40 locked, adopted nothing; the drain ran on one node. A parked run woken during the drain waited behind the whole backlog for its slot (it resumed last). And the startup pass ran on the `ApplicationRunner` thread, so readiness waited for every adoption (500 adoptions at 1 ms per statement held it 2.3 s), and a store failure in it failed application startup.
- **What changes.**
  - **Reserve, then lock.** Before the recovery poller locks an instance, it asks the instance's task queue for room on this node, without waiting and without overtaking a run already waiting. Per queue, a node adopts at most its free slots plus `concurrency` recovered runs that queue for a slot; every other instance is left unlocked, with no lease-epoch bump, for a peer or for later. So a node holds at most 2 × `concurrency` instance locks per queue for recovered runs that are running or queued at the same time; a recovered run that parks gives its slot back and keeps its lock, like any parked run, so parked runs are adopted beyond that bound (traced) (`RecoveryCapacityTest`; on Postgres, `MultiNodeRecoveryCapacityIT`: with a 12-run backlog at `concurrency` 2, each of two nodes locks 4, and both execute runs).
  - **Re-poll on capacity.** After a pass that left instances for lack of capacity, the poller passes again as soon as a slot or a queued place frees on this node, at most once per `min(1 s, maestro.recovery.poll-interval)`, from the first instance it left on a queue that now has room, reading a page sized to that room; with nothing free it reads nothing (`RecoveryPollerCapacityTest`). Full passes keep their `poll-interval` cadence, and the poller's first pass now runs when it starts, not one interval later.
  - **Neither side starves.** Queued recovered runs wait in the same first-in-first-out line as new starts: a recovered run completes while new starts keep the queue busy, and a new start waits behind at most `concurrency` queued recovered runs (`RecoveryStarvationTest`) — at most about one run's length of extra wait while a backlog drains (arithmetic). A parked run woken during a drain now resumes behind at most one queue's worth of recovered runs, not the whole backlog.
  - **Unbounded queues are unchanged:** with `default-concurrency: 0`, `default-activity-concurrency: 0` and no listed queue, recovery adopts everything it can lock, as before.
- **Startup.** `StartupRecoveryRunner` only starts loops; it adopts nothing itself. The startup pass is the recovery poller's first pass, on its own thread, so the runner returns while the pass is still reading and readiness no longer waits for recovery (`StartupRecoveryRunnerTest`; readiness following the runners is Spring Boot's, traced). A signal for a workflow not yet re-adopted is stored and found by its replay; a query reaches only the owning node. A store failure in the startup pass no longer fails startup: it is logged and retried. With `maestro.recovery.enabled=false` a one-shot startup pass still runs, ungated as before (it adopts everything it can lock; runs beyond a queue's bound wait for their slots), on its own thread, retried every `poll-interval`, with no limit, until it completes (`OneShotRecoveryTest`); a failed attempt is logged, not counted (`maestro.loop.errors` counts only `Error`s). There is no health detail for "startup recovery still running", nor for the one-shot pass, yet (Issue 73).
- **Meters and logs.**
  - New gauge **`maestro.recovery.deferred`** (Prometheus `maestro_recovery_deferred`, no tags): the instances this node's latest full recovery pass left for lack of capacity — `0` on a healthy fleet, the visible backlog during a drain; do not sum it across pods. Meters: 24 (17 counters, 2 timers, 5 gauges), with this release's other new meters.
  - `maestro.recovery.scanned` and `maestro.recovery.adopted` also move on capacity passes (only those that read rows).
  - Log lines: `Maestro startup recovery complete: {} workflow(s) recovered` is gone. The poller's first pass logs `Maestro startup recovery pass: adopted {} workflow(s); {} deferred until a task-queue slot frees on this node` (INFO). A full pass that left instances logs `Recovery pass adopted {} and deferred {} instance(s) for lack of task-queue capacity on this node {queue=count}; they are adopted as slots free` (INFO; the same line at DEBUG for a capacity pass) instead of `Recovered {} workflow(s) …`. With the poller disabled: `Maestro startup recovery (recovery poller disabled) adopted {} workflow(s) without a capacity check; runs beyond a task queue's bound wait for their slots`.
- **Test kit (Issue 36).** `TestWorkflowEnvironment.builder().defaultConcurrency(w, a).taskQueueConcurrency(q, w, a).build()` and `@MaestroTest(defaultConcurrency = …, defaultActivityConcurrency = …)` bound the kit's task queues like `maestro.worker.*`, validated the same way; the default stays unbounded. Pitfalls rule 11's same-queue wait now times out in a kit test at `(1, 1)` (`TestWorkflowEnvironmentLimitsTest`, `MaestroTestLimitsTest`). `recover()` stays ungated (Issue 72).
- **API.** `WorkflowExecutor.startOneShotRecovery(Map, Duration)` and `WorkflowExecutor.recoveryDeferredCount()` are new. `recoverWorkflows(Map)` is unchanged: the ungated "adopt everything now" pass.
- **Mixed fleets.** Old and new nodes interoperate: an old node still adopts past its capacity, a new node never does, and the instance locks and lease fencing keep both correct. No migration, SPI or history change.

### Changed — one shutdown deadline; slot waiters released at shutdown; Maestro stops alongside the web server; maestro.shutdown.timeout defaults to 20 s (3.6, Issue 29, Issue 64, D11)

- **What was wrong** (measured).
  - With `maestro.shutdown.timeout=1s`, four background loops whose store call ignored the interrupt, plus a stuck lifecycle publisher, made `shutdown()` take 26 s: every step had its own timeout (4 × 5 s + the drain + 5 s). At the defaults that is up to 55 s.
  - Spring could not bound it: Maestro stopped synchronously in the web server's graceful-shutdown phase and ahead of it, so the web server began draining only after Maestro had finished (1,012 ms later with a 1 s drain).
  - A run queued for a task-queue slot kept waiting, and kept its instance lock, until a slot freed.
  - A deploy that landed during a lock-renewal pass counted one `maestro.lock.renew.failures{outcome="error"}` per held lock.
- **What changes.**
  - **One deadline.** `WorkflowExecutor.shutdown()` measures one deadline on the monotonic clock and gives every step that waits only what is left of it: the drain, then the background loops' joins, the lock renewer's close (at most 2 s of it) and the lifecycle-event flush (at most 5 s of it). With every step stuck at once it returns within the timeout plus under 1 s (`WorkflowExecutorShutdownDeadlineTest.shutdownReturnsWithinItsTimeoutWhenEveryStepIsStuck`: 1,002–1,008 ms measured at a 1 s timeout).
  - **Slot waiters leave (Issue 29).** Shutting down lends each task-queue pool one "poison" permit: a waiter that wakes on a shut-down governor gives it back, wakes the next waiter, and stands down recoverable with `ExecutorShutdownException`, releasing its instance lock as it unwinds — normally before `shutdown()` returns, though the drain waits for that only within the shutdown deadline. Nothing is interrupted (`TaskQueueGovernorTest$Shutdown`, `ShutdownReleasesSlotWaitersTest`).
  - **Maestro stops alongside the web server.** `GracefulShutdownHandler.stop(callback)` returns at once; the engine shuts down on the virtual thread `maestro-shutdown`, which calls Spring back when it is done, also when it threw (`GracefulShutdownHandlerTest`, `GracefulShutdownLifecycleTest`). The handler is no longer pauseable, so `context.pause()` leaves the engine running instead of shutting it down for good. Unchanged: a request already in flight that calls `startWorkflow` after shutdown began gets `IllegalStateException` (HTTP 500 unless the host maps it; `startingAWorkflowDuringTheDrainIsRefused`).
  - **The lock renewer stops without an interrupt** and counts no renewal failure once closed; a lock loss it learns of after close is logged at INFO and not reported as `instanceLockLost`, though the run is still stood down (Issue 64, the renewer half; `WorkflowInstanceLockManagerCloseTest`).
  - **A second `shutdown()` call waits for the first** (at most what is left plus 2 s), so Spring's destroy method on the executor no longer lets the `DataSource` close under a drain that outlived the phase timeout (`aSecondShutdownCallReturnsOnlyAfterTheFirstHasFinished`, `theEngineDrainsBeforeTheBeansItIsBuiltOnAreDestroyed`).
- **Changed default — breaking for a drain that needs more than 20 s.** `maestro.shutdown.timeout` (and `WorkflowExecutor.builder(...)`'s default) is now **20 s**, was 30 s, and it now bounds the whole engine shutdown, not only the drain. With a 30 s drain under an asynchronous stop, the drain alone would equal Spring Boot's 30 s `spring.lifecycle.timeout-per-shutdown-phase` and Kubernetes' 30 s `terminationGracePeriodSeconds`, leaving no time for the rest of shutdown. To keep 30 s, set `maestro.shutdown.timeout: 30s`, `spring.lifecycle.timeout-per-shutdown-phase: 35s` and `terminationGracePeriodSeconds: 45` (more with a preStop hook) — see [Operations §15](operations.md#15-shutdown-one-deadline-and-the-grace-period).
- **Changed log lines** — for anyone alerting on them.

  | Old | New |
  |---|---|
  | `Shutting down WorkflowExecutor, {} workflow(s) in-flight` (INFO) | `Maestro shutdown started: {n} run(s) in flight; deadline {budget} ms (maestro.shutdown.timeout)` (INFO) |
  | `WorkflowExecutor shutdown complete — all workflows finished` (INFO) | `Maestro shutdown finished in {total} ms of its {budget} ms deadline: … 0 still running (drain …, background loops …, instance locks …, lifecycle events …)` (INFO) |
  | `WorkflowExecutor shutdown complete with {} workflow(s) still running` (WARN) | `Maestro shutdown hit its {budget} ms deadline (maestro.shutdown.timeout): {m} of {n} run(s) still running [ids]; …` (WARN, at most ten ids) |
  | `Shutdown already in progress` (INFO) | `Shutdown already in progress — waiting up to {} ms for it to finish` (INFO; WARN if the wait runs out) |
  | `Timer poller stopped` and the other loops' `… stopped` (INFO, always) | logged only once the loop has ended; a loop still in a store call at the deadline logs WARN `Background loop '{name}' did not stop within the shutdown deadline; it ends after its current store call` |
  | — | WARN `Lifecycle event publisher stopped with {k} queued event(s) unpublished`; ERROR `Maestro graceful shutdown failed: …` |

  `Maestro graceful shutdown initiated` and `Maestro graceful shutdown complete` are unchanged.
- **Fixed after review (PR #60).** `Thread.join(Duration)` throws `IllegalThreadStateException` for a thread that has not been started. Two shutdown joins could meet one, and the exception escaped `shutdown()`, skipping its later steps (the lock renewer's close, the lifecycle flush, the summary line):
  - The drain could meet a run that a launch had registered but not yet started. The drain now waits for that launch, within the deadline, and then joins the run. A launch still not started at the deadline is reported as still running (`aRunRegisteredButNotYetStartedIsWaitedForByTheDrain`, `aRunStillUnstartedAtTheDeadlineIsReportedAsStillRunning`, `aLaunchThatFailsBeforeItsThreadStartsReleasesTheDrainAtOnce`).
  - The lock manager's close could meet a renewer that had been published but not yet started. The close now skips the join for it, and the renewer leaves at once when it starts (`closeWhileTheRenewerIsPublishedButNotStartedReturnsAndTheRenewerEnds`).
- **Fixed (Issue 87).** `shutdown()` could return while a run it was draining still held its instance lock: a run leaves the running map before it releases its lock, and the drain read only that map, so a peer's immediate recovery pass, and the test kit's one-pass `recover()`, skipped the workflow as locked elsewhere. The drain now waits, within the deadline, for every run it finds until that run's lock release has been attempted (a release the backend fails leaves the lock to its TTL), and asks each of those runs to stop; a run still in its release at the deadline is named in the deadline `WARN`. A run registered after the drain began is still not waited for (Issue 80) (`ShutdownReturnsBeforeLockReleaseTest`).
- **Still open:** Issue 64 (the four core pollers and the lock cleaner still interrupt), Issue 69 (Maestro's Kafka listener containers stop at bean destruction, the `@MaestroSignalListener` ones after the `DataSource`), Issue 70 (a run still executing at the deadline can record a failure caused by bean teardown).

### Fixed — background loops survive an Error; a health probe timeout is UNKNOWN (3.5a, 3.5b)

- **What was wrong.** Every background loop Maestro starts died on the first `Error` an iteration threw — an `OutOfMemoryError`, a `NoClassDefFoundError` from a bad deploy, an `AssertionError` — and the JDK printed it to `System.err`, not to the log. Nothing reported the dead loop: the signal janitor and the instance-lock renewer were not in `/actuator/health`, and the renewer's death was invisible while it churned every instance lock on the node 30 s after acquisition (a later `tryAcquire` never restarted it). The wake listener also spun without a pause on a driver that kept throwing a `RuntimeException`, and `/actuator/health` read `DOWN` whenever its 2 s store probe timed out — on a busy, healthy pool too.
- **What changes.** Each iteration of the five engine loops goes through one guard (`BackgroundLoopGuard`); the two `maestro-store-postgres` loops apply the same rule inline. A `MaestroControlFlowError` is rethrown and ends the loop, as before; every other `Throwable` is contained, logged, and followed by the loop's normal wait — the cadence is unchanged, and `InterruptedException` still ends a loop at once. A loop whose thread does end is reported through SLF4J (`Background loop '<name>' stopped: … escaped it — it will not run again on this node until the node restarts`, `ERROR` with the stack trace), never `System.err`. A failure that reaches a loop's guard — the recovery poller's scan, the renewer's `renewAll` (360 `WARN`s an hour before, with locks held), the timer poll — now logs one `ERROR` and a reminder every 5 minutes while it lasts (traced from the cadence, which `BackgroundLoopGuardTest` pins). **Not changed on Postgres:** a dead database during the timer poller's leader election never reaches the guard, because `PostgresDistributedLock.trySetLeader` catches the `SQLException`, logs its own `WARN` `Leader election failed for '<key>': <message>` and reports "not elected"; that `WARN` still fires on every poll (720 an hour at the default 5 s), and the retention sweeper's election behaves the same (traced, not tested).
- **The seven loops, and what an `Error` in one iteration does now:**

  | Loop (thread) | Now | Pinned by |
  |---|---|---|
  | Timer poller (`maestro-timer-poller-<svc>`) | Contained; the next poll after `maestro.timer.poll-interval` fires the timer | `BackgroundLoopErrorTest.timerPoller_anErrorFromAPoll_isContainedAndTheNextPollFiresTheTimer` |
  | Recovery poller (`maestro-recovery-poller-<svc>`) | Contained; the next pass runs | `recoveryPoller_anErrorFromAPass_isContainedAndTheNextPassRuns` |
  | Signal janitor (`maestro-signal-janitor`) | Contained; the next sweep runs | `signalJanitor_anErrorFromASweep_isContainedAndTheNextSweepRuns` |
  | Retention sweeper (`maestro-retention-sweeper`) | Contained; the next sweep runs | `retentionSweeper_anErrorFromASweep_isContainedAndTheNextSweepRuns` |
  | Instance-lock renewer (`maestro-instance-lock-renewer-<svc>`) | Contained; every handle is kept and the next pass renews it | `renewer_anErrorFromRenewAll_isContainedAndTheNextPassRenews` |
  | Lock cleaner (`maestro-pg-lock-cleaner`) | Contained; logged at `ERROR`; the next run follows one interval later | `PostgresLockCleanerSchedulerTest.anErrorFromARunIsContainedAndTheNextRunHappens` |
  | Wake listener (`maestro-pg-wake-listener`) | From the driver: contained, `ERROR`, same session, 1 s pause. From a consumer: contained per notification; the rest of the batch is delivered at once | `PostgresWakeListenerLoopErrorTest.anErrorFromTheDriverIsContainedAndTheLoopWaitsBeforeItPollsAgain`, `anErrorFromOneConsumerDoesNotDropTheRestOfTheBatch` |

- **Health (`/actuator/health`, `maestro` component).**
  - **3.5a: a probe timeout is `UNKNOWN`, not `DOWN`.** `store: "timed out"` now gives `UNKNOWN`; a probe that throws (`"unreachable"`) is still `DOWN`, and so is a loop fault, even when the probe also timed out (`MaestroHealthIndicatorTest`). A loop fault is a loop whose thread has stopped, or the instance-lock renewer after two failed passes in a row; the other four engine loops report only whether their thread is alive, so one that fails every pass still reads `UP` (Issue 67). Spring Boot answers `UNKNOWN` with HTTP 200 and ranks it below `UP`, so a busy pool keeps the aggregate `UP`. The indicator is a readiness signal at most, never liveness: never point liveness at `/actuator/health`, add `maestro` to readiness only if the pod's traffic needs Maestro's store — see [Operations §14](operations.md#14-health-checks-and-kubernetes-probes), which also shows how to map `UNKNOWN` to 503 if you want that.
  - **One probe in flight.** A health call that finds the previous probe still stuck joins it instead of starting another, so a store call that ignores the interrupt holds one thread, not one per health call (`aStuckProbeIsSharedByLaterCallsNotStartedAgain`). A call that finds the probe already timed out by an earlier call answers `"timed out"` at once (`aCallThatFindsAnAbandonedProbeAnswersTimedOutAtOnce`); a probe thread that fails to start, or a control-flow `Error` from the store call, never leaves the probe stranded (`aProbeThreadThatFailsToStartDoesNotLeaveTheIndicatorStuck`, `aControlFlowErrorInTheProbeCompletesItAtOnce`). A timed-out probe is still interrupted (Issue 66). The indicator can take up to 2 s to answer: a readiness probe that includes `maestro` needs `timeoutSeconds` of at least 3 (the kubelet default is 1), or a slow store fails the HTTP probe before `UNKNOWN` arrives (Operations §14).
  - **New details.** `signalJanitorRunning` (`true` | `false` | `"starting"`), `instanceLockRenewerRunning` (`true` | `false` | `"idle"` until this node takes its first instance lock — not a fault) and `instanceLockRenewerConsecutiveFailures`. A stopped janitor or renewer is `DOWN`; so is a renewer that runs but has failed two passes in a row, because held locks are not being renewed. One failed pass reads `UP` with the streak shown: the lock TTL covers roughly two missed passes (`theRenewerIsUpAfterOneFailedPassAndDownAfterTwoInARow`).
- **New counter `maestro.loop.errors{loop}`** (Prometheus `maestro_loop_errors_total`): a background loop contained an `Error` and kept running. Five `loop` values, one per engine loop, each registered at zero when the observer is created (alert on `increase(maestro_loop_errors_total[15m]) > 0`); the error type is not a tag. The lock cleaner and the wake listener are logged but **not counted**: they reach no `EngineObserver` (Issue 65). Any increase is a bug or resource exhaustion on that node. Meters: 24 with this release's other new meters (17 counters, 2 timers, 5 gauges).
- **Changed log lines** — for anyone alerting on them. The four old lines are gone:

  | Old (level) | New (level) | What was lost |
  |---|---|---|
  | `Timer polling error — will retry next cycle` (`ERROR` + stack, every cycle) | the guard's lines below | — |
  | `Leader election failed for service '{}' — skipping this poll cycle` (`WARN` + stack, every cycle) — fired only for a lock backend whose `trySetLeader` throws; `PostgresDistributedLock` catches and logs its own line (above) | the guard's lines, `loop` = `timer-poller` (`ERROR`) | The service name is only in the thread name; an election failure and a poll failure now share one text; alerts keyed on this text or on `WARN` stop firing |
  | `Recovery polling error — will retry next cycle` (`ERROR` + stack, every cycle) | the guard's lines, `recovery-poller` | — |
  | `Failed to renew {} instance lock(s) — will retry: {}` (`WARN`, message only, every pass) | the guard's lines, `instance-lock-renewer` | The count of locks in the failed pass |

  The guard's lines: first failure `ERROR` with the stack trace, `Background loop '<name>' failed (<toString>); it keeps running and retries every <5 s | 1 h | …> — …`; for a loop faster than 5 minutes, later failures `DEBUG` (`… failed again (<n> in a row)`, with the stack trace) and an `ERROR` reminder at most every 5 minutes (`… is still failing: <n> consecutive failure(s) over <m> min; latest: <toString>` — **no stack trace, only the exception's `toString()`**); for the hourly janitor and sweeper every failure is an `ERROR` reminder; `INFO` `… recovered after <n> consecutive failure(s)`; `DEBUG` while stopping. The janitor's and sweeper's own `WARN`s for a store fault inside a sweep, and the lock cleaner's `WARN` for a `RuntimeException`, are unchanged. The wake listener's `WARN` for an unexpected `RuntimeException` now ends `; dropping the session and reconnecting` instead of `; continuing`.
- **Other behaviour changes.**
  - A workflow factory's `LinkageError` (`NoClassDefFoundError`, `ExceptionInInitializerError`) now surfaces from `startWorkflow`, `resumeWorkflow` and `retryWorkflow` as `WorkflowInstantiationException`, like a factory `RuntimeException`, where the raw `Error` used to escape; recovery skips that instance and recovers the ones behind it in the same pass, instead of ending every pass there (`RecoveryFactoryLinkageErrorTest`). Other `Error`s from a factory still propagate.
  - An `Error` during an adoption — while recovery launches a run, or after the lock manager recorded a new handle — releases the instance lock instead of leaking it; a leaked handle was renewed for ever and the workflow could not be recovered by any node until this one restarted (`AdoptionLockLeakTest`).
  - An `Error` while `startWorkflow` creates the instance row or adopts orphaned signals releases the instance lock, and the original `Error` reaches the caller (`startWorkflow_anErrorAdoptingOrphanedSignals_…`).
  - An observer that throws an `Error` on a lost instance lock no longer skips that run's stand-down (`renewer_anObserverErrorOnALostLockStillStandsTheRunDown`).
  - One wake consumer's `Error` no longer drops the rest of its notification batch or pauses every other wake for 1 s (`anErrorFromOneConsumerDoesNotDropTheRestOfTheBatch`).
  - A wake session whose poll keeps throwing a `RuntimeException` is dropped and reconnected with the 1 s → 30 s back-off, instead of being retried on the same connection every second while `isConnected()` says `true`. The dropped connection is released as at close — `UNLISTEN *` bounded by a 1 s network timeout, its failure ignored, then closed — so the pool never gets it back still listening (`aSessionThatKeepsThrowingARuntimeExceptionIsDroppedAndReconnectedWithBackoff`, `anUnlistenThatTimesOutAfterARuntimeExceptionIsIgnoredAndTheBackOffIsUnchanged`, `closeDuringTheReleaseOfASessionThatThrewReturnsWithinItsJoin`, `theReleaseAfterARuntimeExceptionRunsWithTheInterruptFlagClear`). The back-off now resets only after a poll returns, so a session that connects and then fails at once backs off too, whatever it threw (`aSessionThatKeepsThrowingARuntimeExceptionIsDroppedAndReconnectedWithBackoff`).
  - A lock release that fails while a launch is already failing is added as suppressed to the launch's own throwable instead of replacing it (`AdoptionLockLeakTest.aReleaseThatThrowsDoesNotReplaceTheLaunchsError_*`).
- **New public API.** `EngineObserver.backgroundLoopError(String loop, String errorType)` (a default no-op; `CompositeEngineObserver` contains every `Throwable` but a control-flow `Error` from it, per delegate); `WorkflowExecutor.isInstanceLockRenewerRunning()`, `hasInstanceLockRenewerStarted()`, `instanceLockRenewerFailureStreak()`. No property, SPI method or migration changes.
- **Mixed fleets.** Health and logging are per node; an older node's loops can still die. Nothing crosses nodes.

### Fixed — admin Retry re-runs only the failure that ended the run (3.3, Issue 33, D10)

- **What changed.** Retry used to delete every `ACTIVITY_FAILED` by type, so a failure the workflow had caught went too: a workflow that caught `primary`'s failure, took a `fallback` and then failed in `finish` re-ran `primary` live on Retry, met the fallback's memo and stood down as `nondeterministic_history` on every recovery pass, `RUNNING` for good. Now Retry deletes only the failure that ended the run, everything the run recorded after it while handling it, and the `WORKFLOW_FAILED`:
  - A caught failure followed by a fallback is kept and replays; `primary` does not run again (`AdminRetryTest.retryAfterCaughtFailure_completesOnRecordedPath`; over real Kafka and Postgres, `AdminCommandKafkaIT.retry_afterACaughtFallback_completesWithoutStandDown`).
  - Catch, handle, rethrow (`catch (e) { alert(); throw e; }`) re-runs the primary **and its handling**: the alert runs again. Activities must be idempotent, as always (`AdminRetryTest.retryAfterCatchAlertRethrow_*`). A completed sibling `parallel()` branch is kept and does not run again (`retryOfABranchFailure_keepsTheCompletedSiblingAndRerunsTheRootTail`).
  - For an await-timeout failure, the timeout that propagated is the one re-armed, not the highest-sequenced one; a gate timeout the workflow caught is kept (`AdminRetryTest.issue33_*`). When the failure cannot be attributed and the exception that escaped is not a `SignalTimeoutException`, a caught gate's timeout is kept too (`RetryPlannerTest.lastInLane_keepsACaughtGateWhenTheEscapingExceptionIsNotATimeout`, `SignalTimeoutReplayDeterminismTest`).
  - History an older build wrote (no `failedSequences`) is handled by matching the recorded message: the two `FAILED` runs of the `cbf5a2a` fixture are retried by this build and complete (`SeededHistoryUpgradeIT.theFailedRunsCbf5a2aRecordedAreRetriedByThisBuild`). A history Maestro cannot place keeps the old delete-by-type Retry (Issue 61).
  - In the shipped JDBC/Postgres and in-memory stores, Retry is one transaction: the deletes and the `FAILED → RUNNING` compare-and-set commit together or not at all (`PostgresReopenFailedInstanceTest`, `InMemoryWorkflowStoreTest`). A store that inherits the SPI default of `reopenFailedInstance` is not atomic and ignores the planned deletes (see Stores below).
- **What an operator sees.**
  - A new `WorkflowExecutor.RetryOutcome.SIGNAL_CONSUMED_AFTER_FAILURE`: when the failure's handling consumed a signal, Retry writes nothing and refuses. The engine logs a `WARN` naming the signal, its sequence and the remedy (terminate the workflow or finish it by hand; the signal is not redelivered), and `AdminCommandDispatcher` logs the outcome as a `WARN` no-op and acknowledges the command: offset committed, nothing dead-lettered, nothing written (`AdminCommandDispatcherRefusalTest`, `AdminCommandKafkaIT.retry_refusedForAConsumedSignal_isAcknowledgedAndWritesNothing`). The dashboard still says "Retry command sent" (Issue 63). Host code with an exhaustive `switch` over `RetryOutcome` no longer compiles until it handles the constant. Issue 59.
  - `WORKFLOW_FAILED`'s payload gains `"failedSequences":[…]`, the sequences of the failure memos the escaping exception carried (`FailureAttributionTest`). The instance `output`, and so `WorkflowHandle.failure()`, carry the same field in the new third component of `WorkflowFailureDetail` (traced: written from the same object); `null` means a build before this one wrote it.
  - `WorkflowFailureDetail` gained a third record component: host code that deconstructs it with a record pattern (`WorkflowFailureDetail(var type, var message)`) or calls its canonical constructor no longer compiles.
  - An attributed timeout of an `awaitSignal` inside `collectSignals` is re-armed with the time remaining to the collection's deadline, computed from the replayed `currentTime()`, not with the full timeout (traced, not tested).
  - A failure thrown without the activity's exception in its chain (`throw new X(msg)` instead of `throw e` or `new X(msg, e)`) is not attributed: when more work followed the caught failure, Retry keeps it, and the retried run fails again (`docs/pitfalls.md` rule 2).
  - Two independently failed `parallel()` branches need two Retries: the first clears the branch whose failure propagated and fails again naming the other; the second completes (`AdminRetryTest.twoFailedBranches_needTwoRetries`). Issue 60.
  - A `$maestro:retry` redelivered after its reopen committed does nothing (`AdminCommandKafkaIT.retry_redeliveredAfterACommittedReopen_doesNothing`). Traced, not tested: if the reopen commits but the command handler then throws and the retried run fails again before the redelivery arrives, the redelivery retries again, because an admin command is deduplicated only by the row's status. `LOCK_HELD_ELSEWHERE` is also returned when the relaunch finds the row already terminated or loses its adoption to another node (unchanged).
- **Stores.** New SPI method `WorkflowStore.reopenFailedInstance(reopened, deleteEventSequences, deleteTimerIds)`, with a default that is the old two-step Retry (`deleteFailureEvents`, then `updateInstance`): neither atomic nor checking `FAILED`, ignoring both sets (`WorkflowStoreReopenDefaultTest`). The JDBC and in-memory stores override it; a third-party store keeps the old Retry until it does (Issue 62), and a decorating store must forward it. The shipped overrides throw `IllegalArgumentException` before writing anything when `reopened` is not `RUNNING` with `completedAt = null`. No migration. With a host pool at `REPEATABLE READ` or `SERIALIZABLE`, a Retry that loses to a concurrent one surfaces as `StoreUnavailableException` instead of `OptimisticLockException`, with nothing deleted (traced, not tested).
- **Mixed fleets.** An older node that performs a Retry during a rolling deploy runs the old delete-by-type Retry and ignores `failedSequences`; an older node replays a history a newer node truncated as any shorter history. An older node reads a newer `WORKFLOW_FAILED` with Jackson's default of ignoring the unknown `failedSequences` property, except in a host whose mapper enables `FAIL_ON_UNKNOWN_PROPERTIES`, where `WorkflowHandle.failure()` throws on the older node until the deploy completes (traced, not tested). Two Retries of one workflow running at the same time on an older and a newer node can combine badly (traced, not tested; Issue 88): the older node deletes every failure memo by type in a transaction of its own, without re-checking the status, and only then attempts its compare-and-set. If the newer node's reopen commits in between, the older node's compare-and-set fails but its delete stands, so the run the newer node relaunched loses its failure memos, caught ones included, and can stand down as `nondeterministic_history` on every recovery pass: the outcome of an old-build Retry. `AdminCommandDispatcher` handles one workflow's `$maestro:retry` commands one at a time (the record key is the workflow id), so this takes two concurrent callers of `WorkflowExecutor.retryWorkflow`. Do not retry workflows until the rolling deploy across this build is complete.

### Fixed — no pool WARN when the Postgres wake listener closes (Issue 38)

- **What was wrong:** closing `PostgresWakeListener` interrupted its poll thread, and an interrupt of a virtual thread blocked in pgjdbc's `getNotifications` socket read closes the connection under the pool. `close()` then ran `UNLISTEN *` on that closed connection, so every clean shutdown on a Hikari pool logged `WARN com.zaxxer.hikari.pool.ProxyConnection - … Connection org.postgresql.jdbc.PgConnection@… marked as broken because of SQLSTATE(08003), ErrorCode(0)` with a stack trace, and the pool evicted the connection.
- **What changes:** `close()` no longer interrupts; it stops the loop, ends any reconnect back-off at once and waits for the poll thread for at most 2 s. The poll thread leaves within one poll, puts a 1 s network timeout on its connection, issues `UNLISTEN *`, restores the earlier timeout and returns the connection itself, so no other thread ever touches it; if the wait times out, `close()` logs one `INFO` and the thread releases the connection when its call returns. Shutdown waits up to one poll (≤ 500 ms; bounded at 2 s) longer. The pool keeps its connection: the same backend is reused, listening on nothing. Pinned by `PostgresWakeListenerShutdownTest`, `PostgresWakeListenerCloseOrderTest` and `PostgresSignalWakeAutoConfigurationTest.closingTheContextLogsNoPoolWarnAndClosesTheListenerBeforeThePool`.
- **Nothing to configure.** `maestro.signal.wake-notify.enabled=false` deployments are unaffected: they have no listener.

### Changed — Spring Boot's Kafka beans now coexist with Maestro's (3.4, D5)

**What was wrong.** Maestro's `maestroKafkaTemplate`, `maestroKafkaProducerFactory` and `maestroKafkaConsumerFactory` were ordinary by-type candidates, registered ahead of Spring Boot's `KafkaAutoConfiguration`, so Boot's type-conditioned Kafka beans backed off:

- A service with no Kafka beans of its own had no `kafkaTemplate`, `kafkaProducerFactory` or `kafkaConsumerFactory`. `spring.kafka.template.*`, a `ProducerListener`, a `RecordMessageConverter` and Boot's factory customizers reached nothing.
- A service that injected `KafkaTemplate<String, MyDto>` failed to start with `NoSuchBeanDefinitionException: No qualifying bean of type 'org.springframework.kafka.core.KafkaTemplate<java.lang.String, …>' available`. So did one that declared its own template over an injected `ProducerFactory<String, MyDto>`.
- Setting `spring.kafka.producer.transaction-id-prefix` failed startup: Boot built `kafkaTransactionManager` over Maestro's factory, which is not transaction-capable, and the context failed with `java.lang.IllegalArgumentException: The 'ProducerFactory' must support transactions`.

**What changes.** Maestro's three Kafka beans are `@Bean(defaultCandidate = false)`, and Maestro injects them only by qualifier, through the constants in the new `io.b2mash.maestro.messaging.kafka.MaestroKafkaBeans` (`TEMPLATE`, `PRODUCER_FACTORY`, `CONSUMER_FACTORY`). `KafkaMessagingAutoConfiguration` no longer orders itself before `KafkaAutoConfiguration`. Boot's `kafkaTemplate`, `kafkaProducerFactory`, `kafkaConsumerFactory` and, with a `transaction-id-prefix`, `kafkaTransactionManager` (over Boot's transactional factory) register beside Maestro's, whichever configuration runs first, and `spring.kafka.*` configures them. The engine still holds exactly Maestro's beans, and its producer is never transactional. A host that declares its own Kafka beans keeps working, except one that declares a `ProducerFactory` without a `KafkaTemplate` (see Breaking). A host that replaces an engine bean by name with `defaultCandidate = false` now gets its replacement used by the engine; before, a replaced producer or consumer factory was not the one the engine used (by type it resolved Boot's bean of that type, traced), and a replaced template failed startup (`No qualifying bean of type 'org.springframework.kafka.core.ProducerFactory<java.lang.Object, java.lang.Object>'`). Pinned by `KafkaCoexistenceContextTest`, the transaction manager in both configuration orders. See [Configuration § Kafka Client Configuration](configuration.md#kafka-client-configuration).

**Breaking:**

- An unqualified `KafkaTemplate<String, byte[]>`, `ProducerFactory<String, byte[]>` or `ConsumerFactory<String, byte[]>` injection point no longer receives Maestro's bean. In a service that declares no bean of that type of its own it receives Boot's bean; in one that declares such a bean for another value type, Boot's bean backs off and the point receives nothing (next bullet). Add `@Qualifier(MaestroKafkaBeans.TEMPLATE)` (or `PRODUCER_FACTORY`, `CONSUMER_FACTORY`).
- **A service with a `KafkaTemplate` of its own for another value type and an unqualified `KafkaTemplate<String, byte[]>` point fails startup.** Any host `KafkaTemplate` bean makes Boot's `kafkaTemplate` back off, and Maestro's is not a default candidate, so a point the host's template does not match finds no bean: `NoSuchBeanDefinitionException: No qualifying bean of type 'org.springframework.kafka.core.KafkaTemplate<java.lang.String, byte[]>'`. Before, Maestro's template was a default candidate of exactly that type and the point received it (traced, not tested). The fix is `@Qualifier(MaestroKafkaBeans.TEMPLATE)` on the point. A Spring Boot application reports it through the new `ByteArrayTemplateMissingFailureAnalyzer`: `Bean '<bean>' injects a KafkaTemplate<…, byte[]> without a qualifier (<injection point>), and no Kafka template can be injected there by type. Maestro's 'maestroKafkaTemplate' is not a default candidate: …`, with the qualifier as the action. It analyzes only an unqualified `byte[]` dependency typed `KafkaTemplate` or a supertype (not a `KafkaTemplate` subclass, which Maestro's template cannot satisfy) while a bean named `maestroKafkaTemplate` exists, and leaves every other missing bean to Spring Boot. Pinned by `ByteArrayTemplateMissingFailureAnalyzerTest`. The same shape for an unqualified `ProducerFactory<String, byte[]>` or `ConsumerFactory<String, byte[]>` beside a host factory of that kind should fail the same way, without that analysis (traced, not tested).
- **An unqualified `KafkaTemplate<…, byte[]>` whose template cannot send `byte[]` unchanged fails startup**, instead of every send throwing `SerializationException` (traced) or, with a JSON serializer, every record silently carrying a different payload. With Boot's default `StringSerializer` the new `ByteArrayTemplateInjectionCheck` throws one `IllegalStateException` that lists every offending injection point, one block each: `Bean '<bean>' injects a KafkaTemplate<…, byte[]> without a qualifier (<injection point>) and received 'kafkaTemplate', whose value serializer org.apache.kafka.common.serialization.StringSerializer cannot serialize byte[]: every send through it would fail.`, followed by both fixes: the qualifier, or `spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.ByteArraySerializer` (a service that sets it starts and sends `byte[]` through Boot's template), and then the way out of last resort, `maestro.messaging.template-injection-check` (below). Spring Kafka's JSON serializers fail the same way: `JacksonJsonSerializer`, the deprecated `JsonSerializer` (matched by class name; traced, not tested) and their subclasses, even one that declares `byte[]`, write a `byte[]` value as a base64-encoded JSON string (`hello` becomes `"aGVsbG8="`, pinned), so the message says `writes byte[] as a base64-encoded JSON string, not the bytes themselves` and adds the way out for a template meant to send JSON: qualify the point with that template's own bean name (`@Qualifier("kafkaTemplate")`), which takes it out of the check. A JSON subclass made to pass `byte[]` through unchanged still fails (the check goes by class): give its points that qualifier, or use `warn`. The check runs before any `@MaestroSignalListener` consumer starts or its topics are probed. It sees constructor parameters Spring autowired, and field, `@Bean`-method and `@Autowired`-method parameters, of beans created at startup (for a `FactoryBean`, the FactoryBean's own class, never its product's; for a bean wrapped in a JDK dynamic proxy, the class its bean definition names), and counts a point against a template only when that template is the one the point could have received, among the templates of the point's declared type. It never fails a point several of the host's templates match (`@Primary`, `@Priority`, parameter name): it logs one `WARN` when a matching template the bean depends on cannot serialize `byte[]` or has a serializer it cannot classify, and only a `DEBUG` line when that template's serializer is not visible to it (a `KafkaOperations` that is not a `KafkaTemplate`, or a factory with no value serializer; traced, not tested). A `@Qualifier` takes a point out of the check. It does not see raw or wildcard generics, `ObjectProvider`, `Optional`, collection or `@Lazy` points, or a class with several constructors none `@Autowired` (and, traced, not `getBean` calls or lazy beans). The constructor of a bean built by an instance supplier, and constructor parameters given explicit indexed argument values, are not points, because Spring did not autowire them (pinned; a definition with generic argument values exempts every constructor parameter, traced). Another serializer it cannot classify (a generic one whose type it cannot resolve) logs a `WARN` instead of failing. A serializer named only in the producer configuration (`spring.kafka.producer.value-serializer`) is classified from its class without being instantiated. Pinned by `ByteArrayTemplateInjectionCheckTest`. See [Configuration § Kafka Client Configuration](configuration.md#kafka-client-configuration).
- **`MaestroProperties.MessagingProperties` gained a component, `templateInjectionCheck`.** The record is public, so code that calls its canonical constructor directly no longer compiles; pass `MessagingProperties.TemplateInjectionCheck.FAIL` (the default) as the new last argument, or start from `MessagingProperties.defaults()`. Binding from `maestro.messaging.*` is unaffected.
- **New property `maestro.messaging.template-injection-check`** (`fail` | `warn` | `off`, default `fail`), for a service whose offending point sits in code it cannot change: `warn` logs each violation at `WARN` with the same text, less the last-resort sentence and ending `(Logged, not fatal, because maestro.messaging.template-injection-check=warn.)`, and starts; `off` turns the check off, its `WARN`s included. An unknown value fails startup. Pinned by `ByteArrayTemplateInjectionCheckTest` and `MaestroPropertiesBindingTest`.
- **A plain `@Bean` replacing `maestroKafkaProducerFactory` now fails startup.** Before, the context started and the engine used the replacement (the start is observed against the old code, the engine's use traced). Now Boot's `kafkaProducerFactory` backs off for it while Boot's `kafkaTemplate` still registers, and startup fails with `NoSuchBeanDefinitionException: No qualifying bean of type 'org.springframework.kafka.core.ProducerFactory<java.lang.Object, java.lang.Object>'`, raised while creating `kafkaTemplate`. A Spring Boot application reports it through the new `ProducerFactoryReplacementFailureAnalyzer`: `The bean 'maestroKafkaProducerFactory' replaces Maestro's engine producer factory but is a default candidate, so Spring Boot's kafkaProducerFactory backed off.`, with the action to declare the replacement `@Bean(name = MaestroKafkaBeans.PRODUCER_FACTORY, defaultCandidate = false)` or to define your own `KafkaTemplate`; either starts. The analysis appears for this failure only. Pinned by `ProducerFactoryReplacementFailureAnalyzerTest`.
- **A host that declares its own typed `ProducerFactory` (such as `ProducerFactory<String, YourDto>`) but no `KafkaTemplate` now fails startup.** Boot's `kafkaProducerFactory` backs off for the host's factory, and Boot's `kafkaTemplate`, which used to back off for Maestro's template, now registers and cannot use a factory whose declared generics its `ProducerFactory<Object, Object>` parameter does not match: startup fails with `NoSuchBeanDefinitionException: No qualifying bean of type 'org.springframework.kafka.core.ProducerFactory<java.lang.Object, java.lang.Object>'`, raised while creating `kafkaTemplate`. Before, the same context started (observed against the old code). `ProducerFactoryReplacementFailureAnalyzer` does not explain this case. Either way out starts: declare your own `KafkaTemplate` over your factory, so Boot's `kafkaTemplate` backs off; or declare your factory `@Bean(defaultCandidate = false)` and inject it by name, so Boot's `kafkaProducerFactory` and `kafkaTemplate` register beside it. Pinned by `KafkaCoexistenceContextTest` (`aHostWithOnlyItsOwnProducerFactory_*`, `aNonDefaultCandidateHostProducerFactory_isInjectedByName`). A host factory declared `ProducerFactory<?, ?>` is used by Boot's `kafkaTemplate` and starts (`aHostProducerFactoryWithWildcardGenerics_isUsedByBootsTemplate_andStarts`); a raw or `<Object, Object>` one should behave the same (traced, not tested).
- A plain `@Bean` replacing `maestroKafkaTemplate` or `maestroKafkaConsumerFactory` still works, as before: the engine uses it and Boot's bean of that type backs off. Declare it with `defaultCandidate = false` to keep Boot's bean of that type as well.

**Fixed:**

- **A plain `@KafkaListener` now honours `KafkaConnectionDetails`.** Before, Boot's `kafkaListenerContainerFactory` found no consumer factory bean it could use and built an inline one from `spring.kafka.*` alone, so with a Testcontainers `@ServiceConnection` a host's plain listeners connected to `spring.kafka.bootstrap-servers` (observed: `[localhost:1]` where the connection details said otherwise) while Maestro's consumers used the connection details. That inline factory also skipped every `DefaultKafkaConsumerFactoryCustomizer` (traced). Now a plain `@KafkaListener` rides Boot's `kafkaConsumerFactory` bean, gets Boot's `kafkaTemplate` as reply template, and stays separate from `@MaestroSignalListener` (its own deserializers, group, `spring.kafka.listener.*` and error handler; no dead-letter topic unless the host configures one). This holds while Maestro's consumer factory and template are not replaced by plain `@Bean`s; after such a replacement a plain listener rides an inline factory with no reply template, or the host's `byte[]` replacement, depending on its declared generics (see the configuration reference). Pinned by `KafkaListenerBoundaryTest`. See [Configuration § Kafka Client Configuration](configuration.md#kafka-client-configuration).

**The wire is unchanged.** The engine's producer and consumer keep their serializers, `acks=all` and group ids. Against a real broker, beside a host template configured for JSON, the engine writes exactly its own envelope bytes and a dead-lettered `@MaestroSignalListener` record keeps its original bytes (`KafkaCoexistenceRoundTripTest`).

### Removed — the unused AdminEventPublisher bean

`maestro-admin-client` no longer contributes auto-configuration: `AdminClientAutoConfiguration`, its `adminEventPublisher` bean and `AdminClientProperties` are deleted. Nothing ever called the bean; a service's lifecycle events are published by the engine's `WorkflowMessaging` (`maestro-messaging-kafka`). The bean injected the engine's `KafkaTemplate<String, byte[]>` by type, so with Maestro's Kafka beans no longer default candidates (above) it would have received Spring Boot's template instead. Pinned by `AdminClientContributesNoAutoConfigurationTest`.

- The `AdminEventPublisher` class remains, as a plain class nobody auto-configures, for manual use.
- A service needs no `maestro-admin-client` dependency to feed the dashboard; `docs/admin.md` § Enabling Event Publishing in Services no longer asks for one. In a Maestro service, `maestro.admin.events.enabled` and `maestro.admin.events.topic` keep binding through the starter's `MaestroProperties`, unchanged. In an application with `maestro-admin-client` but without the Maestro starter, nothing binds `maestro.admin.events.*` any more: those properties are now ignored.

### Changed — admin lifecycle events are off by default (3.11c, D3)

`maestro.admin.events.enabled` now defaults to `false`. A service that does not set it publishes no lifecycle event, `ACTIVITY_*` included (pinned by `MaestroPropertiesBindingTest.adminEventsAreOffByDefault` and `MaestroAutoConfigurationLifecycleEventsTest.byDefault_noLifecycleEventIsPublished`).

- **Why.** With publishing on, every live activity costs two producer sends (started, then completed or failed), on top of the workflow, signal, timer and compensation events (traced, not tested). Most services do not run the dashboard.
- **The admin-events topic is no longer required at startup** unless publishing is enabled: a service with no admin property at all starts against a broker that lacks `maestro.admin.events` (pinned by `KafkaTopicVerificationStartupTest.failMode_adminTopicIsNotRequiredByDefault`). With `true`, the topic stays required, as before.
- **Breaking — upgrade:** dashboard users set `maestro.admin.events.enabled: true` on every service the dashboard should show, at the latest with this upgrade. That one line restores the old behaviour. During a rolling deploy, nodes of this build publish nothing unless it is set, so until every node is upgraded the dashboard shows only the old nodes' events (traced, not tested).
- **Unchanged:** the deprecated `maestro.admin.events.topic` alias for `maestro.messaging.topics.admin-events`, and the default of `WorkflowExecutor.Builder.lifecycleEventsEnabled`, which stays `true` for code that builds the engine directly (`WorkflowExecutorBuilderTest`). The starter passes its property in explicitly.
- The samples that feed a dashboard (`sample-order-service`, `sample-payment-gateway`), the demo (`demo/scripts/lib/jvm-env.sh`) and the loan E2E's admin-events scenario (`run-e2e.sh`) already set the property explicitly; the loan services set `false`.

### Changed — wake permits only on the running node; timers polled on the database clock; the activity lock is gone (3.2, 3.7, 3.10, D2)

- **3.2 — wake permits no longer leak.** A wake that found no parked waiter stored a one-shot permit (the signal's key and its latest payload) on whichever node the wake arrived on, and only that node's run of the workflow could ever remove it. Two sources left permits on nodes that never ran the workflow: a signal delivered on a node that does not run it (with Kafka, the partition's consumer), and a `sleep()` timer fired by the timer-poller leader for a workflow owned elsewhere. Each stayed on the heap until the JVM restarted; the audit's reproduction left 51 permits for signals and 50 for timers on the non-owning node. Now a permit is stored only for a workflow that runs on this node, checked before the put and again after it, for every wake source (signal delivery, cross-node `NOTIFY`, timer fire or cancel, await-deadline transition); the reproduction leaves 0. No wake is lost: a waiter on another node is woken by `NOTIFY` or its wake re-check, and a workflow that runs nowhere yet reads its durable signal or timer row before its first park. A permit no longer carries the payload (every park re-reads the store), so a permit is a key and its workflow id. A new gauge, `maestro.wake.permits` (no tags; source `WorkflowExecutor.wakePermitCount()`), shows the permits a node holds. Fixed alongside, in the same `ParkingLot`: terminating workflow `order` abandoned the parked run of workflow `order:1` and removed its permits (workflow ids were matched by key prefix; they are now matched exactly); two workflows whose parking keys spelled the same string (a signal name containing `:signal:` or `:timer:`, for example workflow `order` awaiting `1:signal:go` and workflow `order:signal:1` awaiting `go`) collided, and the second park failed its workflow (slots are now keyed by workflow id and key); and a terminate that reached the lot just after its run ended left a poison entry behind. Pinned by `WakePermitLocalityTest`, `ParkingLotWakeGateTest`, `ParkingLotPermitTokenTest`, `RunEndPermitOrderingTest`, `WorkflowIdPrefixIsolationTest`, `ParkKeyCollisionTest`, `ParkingLotTerminatePoisonTest`, `TerminateReachesEveryWaiterTest`, `AwaitTimerStrayPermitTest` and `MaestroEngineGaugesTest`.
- **3.7 — due timers are compared on the database clock.** On Postgres the timer poller now selects `PENDING` rows with `fire_at <= now()` instead of `fire_at <= ?` bound to the leader's JVM clock, so the leader's own clock no longer decides when a timer fires. Only the comparison moved: `fire_at` and an `awaitSignal()` deadline are still armed on the clock of the node that schedules them, so nothing written or replayed changes ([Issue 51](open-issues.md#issue-51)). **This is a trade-off, not only a fix:** a timer is now armed on one clock (the scheduling node's) and compared on another (the database's), so its accuracy is the difference between the two. A single node, or a fleet whose nodes agree with one another but not with the database, armed and compared on the same clock before, so a `sleep()` fired on time; now it fires off by that node's difference from the database, late on a node ahead and early on a node behind (a node 5 minutes ahead of Postgres fires `sleep(1s)` about 5 minutes late). An `awaitSignal()` timeout expires early, by up to the skew, on a node behind the database, because the database-clock poll fires the row first; on a node ahead it still expires on time, because the await also compares its deadline with the clock of the node running it (traced, not tested). The skew `WARN` below reports the difference; arming deadlines on the database clock ([Issue 51](open-issues.md#issue-51)) is the full fix. Postgres-backed tests on a developer machine read the Docker VM's clock, which can drift from the host's; Linux CI shares the host clock. The comparison in both directions is pinned at the store by `PostgresDatabaseClockTest` (`aSingleNodeAheadOfTheDatabase_sleepArmedOnItsClockFiresLateByTheSkew`, `aSingleNodeBehindTheDatabase_deadlineArmedOnItsClockFiresEarlyByTheSkew`); the end-to-end `sleep()` and `awaitSignal()` timings are traced, not tested. Each node issues one `SELECT now()` when its timer poller starts and every 10 minutes after, and logs a `WARN` from `ClockSkewCheck` when the measured skew is more than 1 s, naming the direction and both bounds on how far its deadlines can be off. The measured skew is a lower bound (the upper bound adds the time the read took), so a read slower than 1 s can hide a larger skew. SPI: two new default methods, `WorkflowStore.getDueTimers(int)` (due by the store's own clock; the default is `getDueTimers(Instant.now(), n)`, the caller's clock, as before) and `WorkflowStore.storeTime()` (the default, empty, means no clock and no skew check), with the JDBC hooks `AbstractJdbcWorkflowStore.getDueTimersByStoreClockSql()` and `storeTimeSql()`, which only `PostgresWorkflowStore` overrides. A decorating `WorkflowStore` should forward `getDueTimers(int)` and `storeTime()`; one that does not keeps polling on the caller's clock and runs no skew check. A dashboard or query that matches the due-timer statement's text must also match `fire_at <= now()`. Pinned by `TimerPollerDatabaseClockTest`, `ClockSkewCheckTest`, `JdbcStoreClockTest` and `PostgresDatabaseClockTest`.
- **3.10 — the per-activity lock is removed.** Each live activity took and released a lock row, `maestro:lock:activity:{workflowId}:{seq}`, but never waited on it: on a failed acquire or a lock-backend error it logged a `WARN` and ran the activity anyway. The key is gone, with its acquire and release, so a live activity issues two lock statements fewer (2 → 0; a five-activity run issues only its instance lock's 2, `ActivityLockStatementCountTest`), and the three `WARN` lines (could not acquire, backend error, failed release) are gone. The k6 scenario s1 (five activities per workflow) is expected to move from 36.00 to 26.00 statements per workflow; that is re-measured on the k6 branch. The `distributedLock` and `lockKeyPrefix` parameters of `ActivityProxyFactory.createProxy` and the `ActivityInvocationHandler` constructors are now ignored and kept only so callers compile ([Issue 50](open-issues.md#issue-50)). No property changes: `maestro.lock.key-prefix` and `maestro.lock.ttl` still configure the instance lock. Duplicate execution is bounded as before by the instance lock and lease fencing (V6), which bounds persisted progress, not external side effects: activities must stay idempotent. Pinned by `ActivityInvocationHandlerTest.liveActivityTakesNoLock`, `MaestroAutoConfigurationConfigSeamsTest.noActivityLockIsTakenUnderACustomKeyPrefix` and `TwoNodeActivityRaceTest` (two nodes racing one activity end as fencing dictates, with or without the lock).
- **Mixed fleets.** Permits never cross nodes, so an old node only keeps leaking in its own heap and no combination of builds loses a wake. An old timer leader compares `fire_at` with its JVM clock and a new one with the database's, but both arm `fire_at` the same way and `markTimerFired` is a compare-and-set, so each timer fires once and none is lost; only its moment can shift by the old leader's skew, as it already does when leadership moves (`PostgresDatabaseClockTest`, the two mixed-fleet tests). An old node still takes and releases its activity-lock rows, which a new node never reads; rows it leaves behind expire and are deleted by any node's lock cleaner.

**Upgrade.** No migration, no configuration change, nothing removed from the public API. Store decorators: forward the two new `WorkflowStore` methods (above).

### Fixed — stored workflow input lost decimal digits; a live activity call returns what its replay returns (3.8, D7, D9, C1)

**Stored workflow input and output were rounded in the database.** The JDBC store parsed every non-integer number in a payload column as a `double`, and every instance status write (a park, an adoption, a retry, the compensation and completion writes, a terminate) re-reads the instance and writes its `input` and `output` back from that parsed tree. A workflow input holding a non-integer number was therefore rounded in `maestro_workflow_instance.input` at the run's first park: `{"big": 12345678901234567890.123456789, "amount": 10.50}` became `{"big": 12345678901234567000, "amount": 10.5}`. Every recovery and retry of the run then started from the rounded input. A status write now writes back the exact text it read, so a non-integer number in `input` or `output` survives parks, adoptions, retries, compensation, completion and terminate unchanged.

- **Rows an older build already rounded stay rounded.** This build cannot recover digits an older build dropped. Such a row reads and replays as the rounded values it holds, exactly as before. Input that still has its exact digits keeps them from now on.
- **Mixed fleets.** While old nodes are still running, an old node that writes the row (any status change) rounds it again (traced, not tested end to end).
- **A workflow that returns its own input** now stores the exact value as its output, where it stored the rounded one.

The same parse affected event payloads. A typed `BigDecimal` replayed without its scale at any length (`10.50` as `10.5`), and with a different value once it had more digits than a `double` holds, from about 16 significant digits (`12345678901234567890.123456789` as `12345678901234567000`). Scale alone did not stand a run down, because the argument fingerprint hashes `stripTrailingZeros()`, but the value workflow code saw on replay was wrong. The JDBC store now parses every payload column (event payloads, instance `input` and `output`, signal payloads) keeping each decimal's digits and scale: a typed `BigDecimal` (top level, field, list element or typed map value) and a `java.time` value read from a numeric timestamp (`Instant`, `OffsetDateTime`, `ZonedDateTime`, `Duration`) get the stored value exactly. Untyped positions (`Object`, `Map<String, Object>`, `List<Object>`) still get a `Double`. Except as listed under "Replay differences" below, `double`, `float` and integer targets get the same values as before, and a host mapper with either `USE_BIG_DECIMAL_FOR_FLOATS` feature on keeps getting `BigDecimal` everywhere.

**A live activity call returns what its replay returns.** A live call handed workflow code the object the activity returned, while a replay of the same call handed it the recorded result deserialized into the method's declared return type. Where Jackson does not round-trip a value exactly, the first run and every run after a restart saw different values: an `OffsetDateTime` with a `+02:00` offset came back in UTC, a `TreeSet` declared as a `Set` came back as a `HashSet`. When such a value reached a later activity's arguments, an unchanged workflow stood down as `nondeterministic_history` after the crash; otherwise it computed something different with no sign of it.

- **A live call returns the read-back.** Once an activity's outcome is recorded, the engine deserializes the stored payload with the function and generic return type a replay uses, and returns that value instead of the activity's object. A `@Compensate` method that takes the result receives the read-back too. On Postgres the read-back is of the `jsonb` form: the outcome insert gains `RETURNING payload` and stays one statement. [Pitfalls rule 12](pitfalls.md#12-your-workflow-gets-an-activitys-result-as-the-store-gives-it-back-not-the-object-the-activity-returned) lists what that means for workflow code.
- **Except a result that reads back as another class.** Where the read-back is not an instance of the returned object's class (a subtype declared as its supertype, a record or a `Long` declared as `Object`, `Number` or a type variable), the live run keeps the activity's own object, as before, so a cast to it does not fail on a run that never crashed. A container that reads back as another container of the same kind (both a `List`, a `Set`, a `Queue` or a `Map`) is not a class difference: a `List.of(..)` or a `TreeMap` arrives as the read-back `ArrayList` or `LinkedHashMap`. A `TreeSet` declared as `Object` or `Collection`, which reads back as an `ArrayList`, is one: the live run keeps the `TreeSet`. **For this shape the first run and a replay still differ**, as they did before: after a restart the replay reads the declared type, so a cast fails, or a later activity whose argument changed with it stands the run down as `nondeterministic_history`. It is now reported on the first run: a `WARN` naming the two classes (never a value), once per activity method and proxy, then `DEBUG`; the `maestro.activity.result.unreadable` counter; and `EngineObserver.activityResultUnreadable` with the cause `maestro:read-back-type-mismatch`.
- **Except a result that cannot be read back.** If Jackson can write a result but not read it back (an interface without type information, for example), the run continues with the activity's own object and does not fail. A `WARN` names the workflow, the step, the sequence, the declared type and the cause's class and JSON path, never a value; with the starter it is logged once per activity method and workflow type on each node, then at `DEBUG`. The same counter and callback (with the cause's class name). A recovery of that run stands it down as `unknown_event_payload`, as before.
- **Except a future.** A method declared to return a `Future` or a `CompletionStage` (`CompletableFuture` included) is not read back on the live run: the workflow gets the activity's own future, and `join()` works. It is recorded as the future's bean properties, so after a restart a `CompletableFuture` replays as a new future nothing completes, and a `Future` or `CompletionStage` stands the run down as `unknown_event_payload`, as before. These return types are unsupported ([Issue 49](open-issues.md#issue-49)).
- **`currentTime()` returns the read-back of the `Instant` it recorded.** Under Jackson's default mapper that is the clock value to the nanosecond, as before. A mapper that writes and reads epoch milliseconds now gets the millisecond value on the live run, as replay always did. Two mappers keep the clock value on the live run: one that throws reading back its own `Instant` (logged at `DEBUG`; a recovery stands the run down as `unknown_event_payload`), and one whose read-back is more than a second from the recorded time (see "A mapper that misreads its own timestamps" below).
- **Payloads are read through JSON text.** `PayloadSerializer.deserialize` renders the stored tree to JSON and parses it with the host mapper, as a JDBC store parses a column. An untyped number arrives as `Integer`, `Long`, `BigInteger` or `Double`, whatever store the tree came from. A `JsonNode` result that is read back is a copy, so workflow code cannot alter recorded history. A JSON `null` reads as the empty value for `Optional`, `OptionalInt`, `OptionalLong` and `OptionalDouble`, live and after recovery (after recovery it read as `null`). Every other target reads `null` as before.

**What is written does not change.** The bytes stored for an activity result, a side effect or a timer, and the argument fingerprint, are what an older build stores for the same value. A later call's fingerprint changes only where its argument came from a result that was read back and did not round-trip, and then equals what a replay computes. One exception: a `SIGNAL_RECEIVED` memo copies the signal row as read, so it now records an exact decimal where it recorded the `double`.

**Changed behaviour.** Most workflows see no difference. These do:

- **Rule 12's forms now appear on the first run.** A non-UTC `OffsetDateTime` or `ZonedDateTime` arrives in UTC. A plain `Set` arrives as a `HashSet`, without the sorted or insertion order the activity gave it; a `SortedSet` as a `TreeSet`, a `Set` of an enum type as an `EnumSet`, and a `Queue` or `Deque` as a `LinkedList` (a `PriorityQueue` no longer polls in priority order). Inside an untyped map or list (whose elements are not compared with what the activity returned), a `Long` arrives as an `Integer` when it fits one, and a `Float` or `BigDecimal` as a `Double`. On Postgres, a map arrives in `jsonb` key order (shorter keys first), and an untyped whole-number exponent such as `1.0E7` arrives as `Integer 10000000`. A JSON `null` for an `Optional`-family type arrives as its empty value. Before, each of these appeared only after a restart. Unchanged on the first run are only the exceptions above: a result whose read-back would be a different class that is not the same kind of container (a subtype under its supertype, a record, a `Long` or a set under `Object`), one that cannot be read back, and a future. Lists, sets, queues and maps that read back as a container of the same kind arrive as the read-back container, those declared as `Object` included: a `List` or `Map` under `Object` arrives as an `ArrayList` or a `LinkedHashMap`, on Postgres with the map's keys in `jsonb` order.
- **`Optional` from a JSON null.** `WorkflowStub.startAndWait(…, Optional.class)` and `TestWorkflowHandle.getResult(Optional.class, timeout)` return `Optional.empty()` where they returned `null`. A workflow input of `Optional.empty()` is `Optional.empty()` after recovery too. `WorkflowHandle.result()` still returns an empty `Optional` for a completed run without a value.
- **The test kit's untyped values.** In `TestWorkflowEnvironment` an untyped `Float` or small `Long` now arrives as a `Double` or an `Integer`, as it does from Postgres, and binary inside an untyped map as a base64 `String`.
- **A mapper that misreads its own timestamps.** A mapper that writes timestamps as numbers (`DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS` on; Jackson 3's default is off) in epoch milliseconds (`WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS` off) but still reads integer timestamps as seconds (`READ_DATE_TIMESTAMPS_AS_NANOSECONDS`, on by default) reads `currentTime()` back about a thousand times too far from the epoch: the year 58834 for a time in 2026. The live run keeps the clock value, as the released build did, so `collectSignals` and `retryUntil` budgets are computed from the real clock; a `WARN` says the mapper does not read back the timestamp it wrote, once per `PayloadSerializer` (with the starter, once per process). A replay after a restart reads the year 58834, as before ([Issue 48](open-issues.md#issue-48)).
- **An `Error` from a result deserializer.** A custom deserializer that throws an `Error`, not an exception, now fails the run on its first execution, after the outcome was recorded. Before, it failed only on replay ([Issue 46](open-issues.md#issue-46)).

**Upgrade.**

1. **No migration, no configuration change, no API removed.** In a rolling deploy, a run that moves from an old node to a node of this build replays values this build reads (case 3 below). A replayed value that differs from what the old build saw usually ends in a `nondeterministic_history` stand-down when it feeds a later recorded step, but it can also steer workflow code into a different branch or a `throw` first, which completes or fails the run with no stand-down (traced, not tested end to end; see "Replay differences" below). The other direction, an old node replaying history a node of this build appended, is traced, not tested end to end ([Issue 41](open-issues.md#issue-41)).
2. **Rows an older build already rounded stay rounded** (see above).
3. **Runs in flight across the upgrade.**
   - *Healed:* the old build ran an activity live and recorded its result, then the run parked or crashed before the code that uses the result ran. This build replays the result and runs that code with the read-back value.
   - *Not healed, not worse:* the old build recorded a later activity whose argument came from a result that does not round-trip. This build replays the read-back, fingerprints the argument differently and stands down as `nondeterministic_history`, which is what the old build does after any restart. With `maestro.replay.verify-arguments=false` the recorded step replays and the run continues with the read-back value.
   - *New, narrow (traced, not tested end to end):* the old build recovered a run after an activity returned a decimal (replaying it lossily) and then recorded a later activity whose argument used that decimal in a precision- or scale-sensitive way (`toString()`, `scale()`, `equals`, division with rounding). This build replays the exact decimal and stands down. The same escape hatch applies.
4. **Mixed fleet.** An old node replays decimals and numeric-timestamp `java.time` values lossily, as it always has. Where a later recorded step depends on them in a precision-sensitive way, it stands the run down, and a node of this build then adopts the run (traced, not tested end to end: [Issue 41](open-issues.md#issue-41)).
5. **Log alerts.** The store-fault latch `WARN` and the `STORE_UNAVAILABLE` abandon detail name the store method. For a completed activity's outcome and for `currentTime()` that is now `appendEventReturningPayload` (`Store unavailable on appendEventReturningPayload — …`). An alert or log query that matches `on appendEvent —` must also match `on appendEventReturningPayload —`. A failed activity's `ACTIVITY_FAILED` memo, the `sleep()`, `parallel()`, `randomUUID()` and `version()` events, the `awaitSignal()` timer and timeout memos and the compensation memos still name `appendEvent`, as do fenced-write details; consuming a signal names `appendEventAndConsumeSignal`, as before.
6. **SPI: a decorating `WorkflowStore` must forward the new method.** The new default method `WorkflowStore.appendEventReturningPayload(WorkflowEvent, RunLease)` is the same write as `appendEvent(event, lease)` and returns the payload exactly as a later read of the store returns it. The default appends and returns the payload it was given. The JDBC store overrides it; `PostgresWorkflowStore` turns on the new hook `AbstractJdbcWorkflowStore.returnsInsertedPayload()` (`INSERT … RETURNING payload`). A decorator that does not forward it still works through the default, but on Postgres its live results then miss `jsonb`'s normalisation (key order, rewritten exponent numbers) and can differ from their replay. The test kit's crash points count the new method as they count `appendEvent`.
7. **Performance.** Each live activity call does one more deserialization, and every deserialization, live or replay, renders the stored tree to JSON text and parses it. On Postgres each outcome insert also returns its payload. No statement is added. Nothing was benchmarked in this change; WP11's k6 scenario 1 (`perf/run.sh s1-throughput`, five activities per workflow, not yet in the repository) is where the cost will show.
8. **Docs.** `docs/pitfalls.md` gains rule 12: your workflow gets an activity's result as the store gives it back. `docs/testing.md` says what the kit's text read-back shows and what only Postgres shows. Issues 42–49 record what this work found and did not fix: replayed activity failures keep only type and message (42), workflow input on Postgres differs between the first run and recovery (43), workflow input is read with the raw parameter class (44), `collectSignals` throws on a null signal payload (45), an `Error` from a result deserializer (46), an unparseable stored payload logged only at `DEBUG` (47), no startup check that the mapper round-trips its own values (48), and future-typed activity results (49).

**Replay differences for history an older build wrote.** A value an older build's run saw on replay can differ on this build. Where a later recorded step's arguments depend on it, the run stands down as `nondeterministic_history`; `maestro.replay.verify-arguments=false` lets such a run continue. A replayed value that differs can also steer workflow code into a `throw` or a different branch before the next recorded step, which fails or completes the run differently with no stand-down. This applies to each of these:

- A typed `BigDecimal` replays exact in value and scale.
- A `java.time` value read from a numeric timestamp replays exact to the nanosecond.
- An `Optional`-family JSON null replays as empty, not `null`.
- A `float` or `Float` target reading the stored text `1.0000000596046448`: that is the shortest decimal text of the `double` 1 + 2⁻²⁴, which lies exactly halfway between two floats, and the text's own value lies just above halfway. The released build rounded it twice (text to `double`, then to `float`) to `1.0f`; this build rounds the text once, to `1.0000001f`. Only `float` and `Float` targets are affected.
- A non-integral decimal beyond `double` range reads exactly into a typed `BigDecimal`, where the read used to fail. Untyped and `JsonNode` targets still read `Double` infinity.
- Host mappers with non-default decimal features: with `JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS` on, nothing changes. With `DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS` on and the node feature off, an untyped decimal arrives as a `BigDecimal` exact in digits and scale. With `JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES` and decimal trees both on, an untyped stored `2.0` reads as `Integer 2` instead of `BigDecimal 2`.

Pinned by `LiveResultReadBackTest` (`maestro-core`: the read-back, D9's raw return with its callback and once-per-method `WARN`, a store fault that never reaches the read-back, the `@Compensate` argument, unchanged payloads and fingerprints), `ReadBackTypeValveTest` (`maestro-core`: what each shape gives the live run and a replay, the type-mismatch report and `WARN`, futures), `OperationAppendFaultDetailTest` (the store method each operation names), `ActivityResultReadBackTest` and `ReadBackFormsTest` (`maestro-test`: each form, live against replayed; a subtype under its supertype, end to end), `CurrentTimeReadBackTest`, `CurrentTimeRecoveryTest`, `PayloadSerializerTest`, `JdbcPayloadDecimalReaderTest`, `JdbcAppendEventReturningPayloadTest`, `PostgresPayloadFidelityTest` (the C1 matrix, every payload column, the instance rewrite, the `RETURNING` corpus, one statement), `PostgresInstanceRewriteEngineTest` (park, adoption, retry and completion keep the input exact through the engine; a row an older build rounded replays as before), `MaestroObservabilityAutoConfigurationTest` (the counter, for both causes), `LiveResultMatchesReplayIT` (Postgres: live equals replay, key order and exponents included), `RawArgumentHistoryStandDownIT` and `SeededHistoryReplayFidelityIT` (history written by `cbf5a2a` completes on this build; a run started here writes the bytes `cbf5a2a` wrote).

### Changed — WorkflowExecutor is built with a builder; its ten constructors are removed (Issue 37)

- **Before:** `WorkflowExecutor` had ten public constructors (6, 7, 8, 9, 11, 12, 13, 14 and 15 arguments, two of them at 14), and a caller chose one by argument count. Several neighbouring parameters share a type (`Duration`, `boolean`, `String`), so swapped arguments compiled. The starter and the test kit each chose a different constructor, which is how the test kit came to miss the default task-queue limits ([Issue 36](open-issues.md#issue-36)).
- **Now:** `WorkflowExecutor.builder(store, serializer, serviceName)` returns a `WorkflowExecutor.Builder` with one named setter per setting; `build()` returns the executor. The three arguments are required: `builder(...)` rejects a `null` one with a `NullPointerException` naming it. Every other setting has the default the six-argument constructor supplied:
  - `distributedLock`, `messaging`, `signalNotifier`: `null` (none);
  - `lockKeyPrefix`: `"maestro:lock:"`;
  - `instanceLockTtl`: 30 s;
  - `lifecycleEventsEnabled`: `true`;
  - `shutdownTimeout`: 30 s;
  - `wakeRecheckInterval`: 30 s;
  - `observer`: `EngineObserver.NOOP`;
  - `taskQueueLimits`: `Map.of()`;
  - `defaultTaskQueueLimits`: `null` (task queues not in `taskQueueLimits` are unbounded);
  - `instantSource`: `InstantSource.system()`.

  Setters only store their value; `build()` validates as the constructors did: a `null`, zero or negative `instanceLockTtl`, `shutdownTimeout` or `wakeRecheckInterval` throws `IllegalArgumentException` (`"<name> must be positive, got <value>"`), and a `null` `taskQueueLimits` or `instantSource` throws `NullPointerException`. Stricter, on invalid input only: a `null` `serializer` or `serviceName` now fails at `builder(...)`, and a `null` `lockKeyPrefix` or `observer` at `build()`, where the constructors accepted them and failed later or never. A builder is not thread-safe and may be reused; each `build()` returns a new executor. All ten constructors are removed, not deprecated: no Maven Central release has shipped them (decision D6). Pinned by `WorkflowExecutorBuilderTest` (`defaultsAreTheValuesTheSixArgumentConstructorSupplied` pins each default).
- **Upgrade:** code that builds a `WorkflowExecutor` directly from `maestro-core` replaces `new WorkflowExecutor(a1, …)` with `WorkflowExecutor.builder(store, serializer, serviceName)`, one setter per other argument (named as the constructor parameter was), then `.build()`. Applications using the Spring Boot starter or `TestWorkflowEnvironment` change nothing. Entries below that name a `WorkflowExecutor` constructor by its argument count, as its "full constructor", or as taking a setting (the default limits, an `InstantSource`, task-queue limits), refer to the builder and its setter of the same name.

### Observability — new meters

- **`maestro.run.abandoned{reason}`** (Counter; Prometheus `maestro_run_abandoned_total`). `MicrometerEngineObserver` now implements `EngineObserver.runAbandoned` and counts every abandoned local run under its own meter, tagged only by `reason` = `shutdown`, `terminated`, `converged`, `terminal_write_failed`, `node_error` or `store_unavailable` (no `workflow` tag). Alert on any `node_error` increase and on a sustained `store_unavailable` rate after the database is back; until now both were visible only as a log line and a span attribute. A terminate moves `maestro.workflow.terminated` once, on the node that handled the terminate; `maestro.run.abandoned{reason="terminated"}` moves at most once, on the node running the workflow (workflow thread), and not at all if no node holds a live run; do not add them together. The `EngineObserver.runAbandoned` Javadoc no longer tells counting observers to skip the callback: count it under its own meter and never fold `TERMINATED` into a terminate counter. Pinned by `MicrometerEngineObserverTest.runAbandonedCountsByReason` and `MaestroObservabilityAutoConfigurationTest.runAbandonedCountsATerminateOnTheWorkflowThread`.
- **`maestro.workflow.duration{workflow,outcome}`** (Timer; Prometheus `maestro_workflow_duration_seconds_count`/`_sum`/`_max`, plus `_bucket` when the host enables a histogram). Start-to-terminal wall time per workflow type, `outcome` = `completed` or `failed`. It is measured from the instance row's `startedAt`, so it includes park time (signals, timers) and, for a recovered run, the downtime before recovery; it is recorded once per terminal transition, so a manually retried workflow records once per outcome it reached, and its completed duration counts from the original start, including the time it sat `FAILED`. Both ends are wall-clock `Instant.now()`, so `TestWorkflowEnvironment`'s controllable clock does not move it. Recorded from a new `EngineObserver` callback, `workflowFinished(WorkflowInfo, WorkflowStatus, Duration)` (a default no-op, so existing observers compile unchanged; `CompositeEngineObserver` forwards it). The engine fires it only on the node that won the terminal transition, directly after `workflowCompleted`/`workflowFailed` on the same thread, and never for a converged loser, a stand-down, an abandoned run or a terminate. `MicrometerEngineObserver` now implements 18 of the 27 callbacks. Pinned by `WorkflowExecutorObserverTest.finishedCarriesStatusAndElapsedSinceStart`, `failedRunFinishesWithFailedStatus` and `convergedLoserDoesNotFinish`, `MicrometerEngineObserverTest.workflowFinishedRecordsDurationByOutcome` and `MaestroObservabilityAutoConfigurationTest.workflowDurationRecordedThroughRealEngineRun`.
- **`maestro.taskqueue.slots.used{queue,kind}`** (Gauge; Prometheus `maestro_taskqueue_slots_used`). How many of a task queue's per-node slots are held right now, `kind` = `workflow` (runs executing on the queue; a parked run gives its slot back, so it counts in `maestro.workflows.running` but not here) or `activity` (live activity executions; replayed steps take none). Compare it against the queue's `maestro.worker.*` limits to see saturation. Registered by a new starter bean, `MaestroTaskQueueGauges`, on `ContextRefreshedEvent`, once per distinct task queue a registered `@DurableWorkflow` names and only for a queue bounded on this node: with `maestro.worker.default-concurrency=0` and `default-activity-concurrency=0`, an unlisted queue has no gauge. Node-local: sum across pods for a cluster total. Backed by two new read-only, non-blocking `TaskQueueGovernor` accessors, `workflowSlotsInUse(String)` and `activitySlotsInUse(String)` (`OptionalInt`: limit minus free slots, empty for an unbounded queue; like `available*Slots`, a call naming an unlisted queue under default limits creates its pools). With `maestro.wake.permits` (3.2, above), `maestro.loop.errors` and `maestro.recovery.deferred` (the recovery-capacity entry, above) the meter catalog is now 24 meters: 17 counters, 2 timers, 5 gauges. During recovery it counts a slot the recovery poller reserved from the reservation, and not a recovered run queued for a slot (`TaskQueueGovernorTest$RecoveryAdmission.slotsInUseCountsAReservedSlotButNotAQueuedRecoveredRun`). Pinned by `TaskQueueGovernorTest.inUseTracksAcquireParkAndRelease`, `inUseIsEmptyForUnboundedQueues` and `inUseUsesDefaultLimitsForUnconfiguredQueue`, `MaestroObservabilityAutoConfigurationTest.slotGaugesTrackRealRuns` and `noSlotGaugesWhenUnbounded`, and the real Boot metrics chain in `wiresThroughRealBootMetricsAutoConfigurationChain`.

### Host load — bounded by default: batched lock renewal, a paged service-scoped recovery scan, default worker limits, `@MaestroDataSource`

The engine's load on the host's database and connection pool had no bound by default. The lock renewer issued one `UPDATE` per held instance lock every 10 s on one thread, so a node holding thousands of parked workflows made thousands of round-trips per pass and, once a pass outran the TTL margin, peers adopted en masse. Every node, every 60 s, read every active instance of every service sharing the tables, payloads included, in one unbounded query, and then made one lock statement per instance not running locally. `maestro.worker.task-queues` defaulted to empty, meaning unbounded, so a deploy replayed every active workflow at once through the host's pool, and every Maestro bean took the host's `DataSource` by type with no way to give the engine its own.

- **Batched renewal.** New SPI default `DistributedLock.renewAll(Collection<LockHandle>, Duration)` returns the handles that were lost; the default loops `renew`, attempts every handle even when one throws, and rethrows the first failure. `PostgresDistributedLock` overrides it with one `UPDATE maestro_distributed_lock ... FROM unnest(keys, tokens) ... RETURNING lock_key` per 500 handles on one connection. The renewer makes one `renewAll` call per pass: a node holding H locks issues ⌈H / 500⌉ statements every `maestro.lock.ttl` / 3 (10 s). A pass longer than that interval logs a `WARN` with the lock count, the duration and the TTL. A backend failure loses nothing: every handle is kept for the next pass, with one `WARN` per pass rather than one per lock.
- **Paged, service-scoped recovery.** New SPI default `WorkflowStore.getRecoverableInstances(String serviceName, RecoveryCursor after, int limit)` and record `io.b2mash.maestro.core.spi.RecoveryCursor` (`startedAt`, `id`). The JDBC store answers with one keyset query (`service_name = ?`, the active statuses, `(started_at, id) > (?, ?)`, `ORDER BY started_at, id LIMIT ?`); `InMemoryWorkflowStore` overrides it too; the default filters and slices the no-arg scan, running that unbounded scan once per page. The engine no longer calls the no-arg scan. A recovery pass reads pages of 200, ends on a short page or when shutdown begins, skips instances running locally, and then makes one advisory `DistributedLock.heldKeys(Collection<String>)` read per page (new SPI default: empty, "unknown, try them all"; Postgres: one `SELECT lock_key ... WHERE lock_key = ANY(?) AND expires_at > now()`, empty on a database error) to skip instances another node holds. Only the rest cost a `tryAcquire`, which stays the authority. Per pass that is ⌊A / 200⌋ + 1 page reads and at most ⌈A / 200⌉ lock reads for A active instances of the service.
- **Default worker limits.** New `maestro.worker.default-concurrency` and `maestro.worker.default-activity-concurrency`, both default `20`, size separate pools for every task queue not listed in `maestro.worker.task-queues`, per queue, per node. `0` on both leaves unlisted queues unbounded; `0` on one alone or a negative value fails startup with `maestro.worker.default-concurrency=<n> and maestro.worker.default-activity-concurrency=<m>: both must be >= 1, or both 0 to leave task queues not listed in maestro.worker.task-queues unbounded`. Startup logs the effective limits at `INFO` (`Maestro worker limits: default 20/20 per task queue (workflows/activities); configured: ...`). Recovery never waits for a slot: adopted runs queue on their own threads. `WorkflowExecutor` gains two constructors taking the default limits; every existing constructor, and so `TestWorkflowEnvironment`, stays unbounded. The starter now ships `additional-spring-configuration-metadata.json` with hints for both properties and error-level deprecations for the removed `maestro.store.table-prefix` and `maestro.messaging.topics.tasks`.
- **A dedicated pool: `@MaestroDataSource`** (`io.b2mash.maestro.store.postgres`). The schema initializer, store, lock, lock cleaner, wake listener and signal notifier take the `DataSource` qualified with it if one exists, otherwise the host's single or `@Primary` one, the way Boot resolves Flyway's. Declare it `@Bean(defaultCandidate = false)` when the application keeps its own `DataSource`, or as a plain `@Bean` when it is the only one. The three Postgres auto-configurations now activate on `@ConditionalOnMaestroDataSource` (any default-candidate `DataSource`, or any `@MaestroDataSource` one) instead of `@ConditionalOnBean(DataSource.class)`, which could not see a lone non-default-candidate bean and silently left the engine out. Among several qualified beans Spring's usual tie-breakers pick Maestro's (the `@Primary` one, else the one named `maestroDataSource`); with neither, startup fails with `Maestro found 2 @MaestroDataSource beans, none @Primary and none named maestroDataSource: [...]. Keep one, mark the one Maestro should use @Primary, or name its bean maestroDataSource.`; two or more unqualified ones with no primary fail with `Maestro found <n> DataSource beans and none is @Primary or @MaestroDataSource. ...` instead of Spring's `NoUniqueBeanDefinitionException`. With a qualified pool, all of Maestro's traffic, including the wake listener's long-lived connection and every `NOTIFY`, comes out of it: size it at least `max(default-concurrency, default-activity-concurrency)` + 5, because a workflow slot's holder (a replay, a wake re-check) borrows connections too. That figure is a heuristic, not a bound: workflow and activity slot holders can borrow at the same time, so the sum of the two limits, plus 5, is the conservative figure.
- **Docs.** `docs/operations.md` §13 "Capacity" gives the per-node cost as arithmetic. `docs/configuration.md` covers the new properties, the recovery scan, V101, the migration numbering rule and § A dedicated connection pool. `docs/pitfalls.md` gains rule 11: an activity must not wait for a workflow of its own task queue.

Pinned by `WorkflowInstanceLockManagerTest` (one `renewAll` per pass, a lost handle stands only its workflow down, a backend failure loses nothing), `PostgresDistributedLockContractTest` (1,200 handles cost 3 statements; stolen and expired locks are reported lost; `heldKeys` reports live locks only, in one `SELECT`), `InMemoryDistributedLockTest`, `PostgresWorkflowStoreTest$RecoveryScanTests` and `InMemoryWorkflowStoreTest` (service scope, keyset paging without skips or repeats on equal and sub-microsecond `started_at`), `RecoveryPassIsolationTest` (pages of 200/200/50, held instances cost no acquire, another service's instance is never adopted pass after pass), `TaskQueueGovernorTest`, `WorkflowExecutorConcurrencyTest`, `MaestroPropertiesBindingTest`, `MaestroAutoConfigurationConfigSeamsTest` and `MaestroDataSourceSelectionTest` (real auto-configurations, Boot's `DataSourceAutoConfiguration` with and without). The chaos sampler counts the paged scan's pages in `recoveryCalls` and the `heldKeys` read in a new `metrics.csv` column, `lockHeldKeysCalls`.

**Upgrade.**

1. **Every node of a service must share one `maestro.service-name` (decision D1).** Recovery now adopts only instances whose `service_name` equals this node's `maestro.service-name`; there is no property to turn the filter off. A deployment that gave each replica its own name (`orders-1`, `orders-2`, ...), or that renames the service while workflows are active, strands those workflows: no node adopts them after their owner stops, until a node runs under the recorded name again. Before upgrading, list the names your active workflows are recorded under and check each against a service you deploy:

   ```sql
   SELECT service_name, count(*)
   FROM maestro_workflow_instance
   WHERE status IN ('RUNNING', 'WAITING_SIGNAL', 'WAITING_TIMER', 'COMPENSATING')
   GROUP BY service_name ORDER BY service_name;
   ```

   One row per service you run is right. A name per replica, or a name no deployment uses any more, means those workflows need a node running under that name to finish them. Services that share one set of `maestro_*` tables no longer adopt each other's workflows.
2. **Unlisted task queues were unbounded and are now 20/20 per node (decision D2).** A service that never configured `maestro.worker.task-queues` now runs at most 20 workflow runs and 20 live activities per task queue per node; a backlog after a deploy drains at that rate instead of all at once. To restore the old behaviour set both `maestro.worker.default-concurrency: 0` and `maestro.worker.default-activity-concurrency: 0`; to size them, raise both or list the queue. Queues already listed are unchanged. Because every queue is now bounded, [Issue 29](open-issues.md#issue-29) (a run queued for a slot at shutdown is not interrupted) and [Issue 30](open-issues.md#issue-30) (a node that adopts more than it can run holds the excess) are on the default path; neither writes or loses anything.

   **A same-queue wait can now deadlock without any opt-in.** A run keeps its workflow slot while one of its activities runs. If an activity starts another workflow on this node and waits for it (`startAndWait`, or polling its status), and the child is on the same task queue, the child needs a slot from the same pools. Once `concurrency` runs of one queue each wait on another run of that queue, or `activity-concurrency` such activities do (20 each, by default, for unlisted queues), no child can run and every parent blocks until its wait times out. Every `@DurableWorkflow` without a `taskQueue` is on `default`, so a parent and a child of different types share these pools. Before this release, unlisted queues were unbounded and this needed an explicit limit. `TestWorkflowEnvironment` stays unbounded, so tests do not show it. Remedies: put the child workflow type on a task queue of its own (`@DurableWorkflow(taskQueue = ...)`); or have the child signal the parent and the parent `awaitSignal` it instead of waiting in an activity; or raise the queue's limits, or set both defaults to `0`. See [Pitfalls rule 11](pitfalls.md#11-never-make-an-activity-wait-for-a-workflow-of-its-own-task-queue).
3. **Migration `V101__recovery_scan_index.sql`** adds the partial index `idx_wf_instance_recovery` on `maestro_workflow_instance (service_name, started_at, id)` over the active statuses. It is numbered under the new rule: every new Maestro migration, store or lock, takes the next version above the highest shipped, within 101–199; versions 1–9 and 100 are history, and band 200–299 stays retired. The paged scan's *query* is correct without the index, only slower; under `validate` the *migration record* is still required for a node to start. Flyway builds the index inside a transaction under a `SHARE` lock (writes wait). The index to pre-create by hand:

   ```sql
   CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_wf_instance_recovery
       ON maestro_workflow_instance (service_name, started_at, id)
       WHERE status IN ('RUNNING', 'WAITING_SIGNAL', 'WAITING_TIMER', 'COMPENSATING');
   ```

   - **`flyway`** (default): nothing to do. Optionally, on a large table, run the line above first; V101 then finds the index and takes no long lock.
   - **`none`**: run the line above yourself, before or after deploying; the build works without the index.
   - **`validate`**: V101 must be *recorded* in `maestro_schema_history` before a node of this build starts (otherwise: `Maestro schema is behind: pending migrations [101]`). Creating the index does not record it, and a hand-inserted history row fails `validate`'s checksum check. Run the line above if the table is large, then record V101 through the Flyway CLI route in `docs/configuration.md` § Schema management, connected as the role that owns the `maestro_*` tables. PostgreSQL checks ownership before `IF NOT EXISTS`, so any other role fails with `must be owner of table maestro_workflow_instance` even when the index exists. If the application's role owns the tables, one start of a node, or a one-off job calling `new MaestroSchemaInitializer(dataSource, SchemaMode.FLYWAY, classLoader).initialize()`, with `maestro.store.schema.mode: flyway` records it too. Then deploy the rest on `validate`.
4. **Mixed fleet and rollback: safe against main @ a27a275.** An older node that is already running keeps its unscoped, unpaged scan and per-lock renewal against the same tables; the new node's scan, `heldKeys` read and batched renewal use the same rows and lock keys, and no column changes. V101 sorts above V100, the newest migration of every build since PR #36. To those builds it is a "future" migration, which Flyway's default `ignoreMigrationPatterns=*:future` ignores, so V101 never stops such a node from starting. A node on a27a275, or on any build since e91bd9d (PR #43), that restarts during the rollout starts normally in `flyway` and `validate` mode, and so does a rollback to it. `MaestroSchemaInitializerTest.anOlderBuildStartsAgainstADatabaseThatAlreadyRecordedV101` runs a27a275's exact Flyway configuration and migration set against a database this build migrated: 0 applied, nothing pending, `validate` passes. One older limit remains, not caused by this release: an unreleased build from before e91bd9d (PR #43) that lacks one of V6–V9 cannot restart against a database that recorded it (see the Issue 35 entry below).
5. **Custom SPI implementations** keep compiling: `renewAll`, `heldKeys` and the paged `getRecoverableInstances` are `default` methods whose defaults are correct, only slower. The paged default runs the whole unbounded no-arg scan once per page, so a pass over A active instances costs ⌊A / 200⌋ + 1 full scans where the engine used to make one; a third-party `WorkflowStore` should override it with a keyset query. Override all three for the cost bound. Hosts with more than one `DataSource` and no `@Primary`, which failed with Spring's `NoUniqueBeanDefinitionException`, now fail with Maestro's message naming `@MaestroDataSource`.

### Fixed — a new store migration no longer fails startup on a database that has the lock band (Issue 35)

Maestro's store and lock migrations share one history table, `maestro_schema_history`. Maestro took that history at 762a5d6 (PR #35, merged as c01d498), with the store's V1–V5 in `maestro-store-postgres` and the lock's V100 in the then-optional `maestro-lock-postgres` module. 2729a57 (PR #36) folded the lock into `maestro-store-postgres`, so from then on every new database records V1–V5 and V100 together; before it, every database whose host had the lock module did. V6–V9, shipped afterwards (all unreleased), sort below V100, and Flyway's default refuses a resolved migration below the highest applied one. A node on the new build failed startup in the default `flyway` mode, and in `validate`, with `Detected resolved migration not applied to database: <n>`.

- `MaestroSchemaInitializer` now runs Flyway with `outOfOrder(true)`. Store and lock migrations never depend on each other, so applying V6–V9 after V100 is the intended semantics.
- Under `validate`, such a migration is now reported as pending (`Maestro schema is behind: pending migrations [<n>]`) instead of failing inside Flyway.
- **New numbering rule, so this cannot recur.** Every new Maestro migration, store or lock, takes the next version above the highest shipped, within 101–199. Versions 1–9 and 100 are history; band 200–299 stays retired. The directory still says which component a migration belongs to. This release's index migration is therefore V101, not the next store-band number. To a build whose newest migration is V100, an applied V101 is "future" and ignored, where any version below V100 would have been "missing" and failed that build's startup. Pinned by `migrationVersionsFollowTheNumberingRule`.
- **What remains, for unreleased builds only.** A node on a main build from c01d498 up to, but not including, e91bd9d (PR #43, the merge of V9) lacks at least one of V6–V9. It cannot start in `flyway` or `validate` mode against a database that recorded one it does not ship: to it that version is "missing", below its own newest (V100 from PR #36 on; before it, V100 or V202 when the optional lock or Postgres-transport module was on the classpath). Every such build is unreleased. Builds from e91bd9d on, main @ a27a275 included, are not affected, by V6–V9 or by V101. A node on an affected build starts only with `maestro.store.schema.mode: none`.

Pinned by `MaestroSchemaInitializerTest`:
- `flywayModeAppliesANewStoreMigrationToADatabaseThatAlreadyHasTheLockBand` (a V1–V5 + V100 history takes V6–V9 out of order, then V101);
- `everyStoreMigrationAppliesAfterTheLockBand`, run for every store version below V100;
- `validateModeNeedsV101RecordedNotJustTheIndex`;
- `anOlderBuildStartsAgainstADatabaseThatAlreadyRecordedV101` (a27a275's configuration and migrations restart against this build's history);
- `migrationVersionsFollowTheNumberingRule`.

The first three failed on the old configuration with the Flyway error above. The last two failed while the index migration still had a store-band number below V100: `Detected applied migration not resolved locally` for a27a275, and a shipped version in 10–99.

### Store faults — a database outage abandons the run instead of failing the workflow (Issue 34)

Issue 20 made a parked workflow's wake-recheck reads ride out a store outage, but every other store call a running workflow makes still failed it. The JDBC store wrapped every `SQLException` in a plain `RuntimeException`, so a pool timeout on an activity's outcome append reached the workflow method as that activity's exception: the workflow was recorded `FAILED` and its saga compensated a step whose side effect had happened. The same held for status writes, timer arms, signal consumption and compensation memos, and a store outage on the terminal write was reported as `TERMINAL_WRITE_FAILED`, the "needs a human" `ERROR`.

- **Classification.** `AbstractJdbcWorkflowStore` now classifies each `SQLException`. SQLSTATE class `22`/`23`/`54` (caused by the workflow's own values; class `54` is a program limit such as an oversized `jsonb` payload) is still an ordinary error and still fails the workflow. Everything else, including a pool timeout with no SQLSTATE, a dropped connection, a restarting server, insufficient resources or a schema mismatch, is thrown as the new `io.b2mash.maestro.core.exception.StoreUnavailableException` (added to `MaestroException`'s `permits`).
- **In-place retry.** The engine reaches the store through an internal guard that retries `appendEvent` and `getEventBySequence` on a `StoreUnavailableException`: three attempts, 200 ms then 1 s apart. A blip shorter than that is absorbed. An append whose commit was lost in flight surfaces on retry as a `DuplicateEventException` through the `(workflow_instance_id, sequence_number)` index. Every other call is attempted once.
- **Abandon, not fail.** A fault that outlasts the retries latches the run (`RunCancellation.Cause.STORE_UNAVAILABLE`) and ends it as the new `AbandonReason.STORE_UNAVAILABLE`: a `WARN` (`Workflow '<id>' abandoned its local run — the store is unavailable (...)`), `runAbandoned(..., STORE_UNAVAILABLE)` (span attribute `maestro.abandon.reason=STORE_UNAVAILABLE`), nothing written, no compensation. The latch `WARN` and the abandon `WARN` carry the SQLSTATE and driver message of the underlying `SQLException` (`SQLSTATE=none` when it has none, as a pool timeout does), so an outage, a schema error and a deterministic rejection read differently; the full exception is at `DEBUG`. The instance keeps its active status and the recovery poller resumes it once the store answers. A latched run's appends are refused even after the store recovers, and stop points beside every stale-run check (activity, `sleep()`, `awaitSignal()`, terminal write, failure handling) end it, so workflow code that catches the exception cannot run a fallback or complete. A fault during a retry backoff unwinds without `ACTIVITY_FAILED`.
- **Compensation and `parallel()`.** A store fault mid-unwind stops the unwind and leaves the instance `COMPENSATING`, with no `COMPENSATION_STEP_FAILED`; recovery finishes it. In `parallel()` and in parallel compensation, a store fault in any branch ranks after control-flow signals, node errors and stale-run signals, and ahead of a sibling's plain failure.
- **Terminal write.** A store fault on the `COMPLETED` or `FAILED` write is a `STORE_UNAVAILABLE` abandon. `TERMINAL_WRITE_FAILED` remains for any other terminal-write failure, such as a data error in the output payload.
- **Activity bodies keep their own failures.** A store call an activity body makes on its own account — a `MaestroClient` start or signal of another workflow — is not guarded: a store fault there fails the activity attempt under its `RetryPolicy`, as any other exception does, and never abandons the calling run.
- **Advisory sites stay advisory.** An await deadline's self-fire, the post-deadline signal re-read, the cancel of a consumed signal's deadline row and the re-arm of a missing deadline row on replay tolerate a store error, as before, and no longer latch the run. The status read in the shutdown and stand-down handlers, used only for their log line, is best-effort, so an outage no longer skips their `runAbandoned`/`standDown` callback.
- **At-least-once, unchanged.** If the fault hit after an activity's body ran and before its outcome was recorded, that activity runs again on recovery. The in-place retry narrows this to outages longer than about 1.2 s; it does not close it. A fault that repeats, such as a schema error, re-runs the in-flight activity once per recovery pass until it is fixed. Activities and compensations must be idempotent.

Pinned by `AbstractJdbcWorkflowStoreUnitTest`, `StoreFaultGuardTest`, `RunCancellationTest`, `StoreFaultStandDownTest` and `StoreOutageMidStepIT` (real Postgres: a 3 s pool outage starting inside an activity body is abandoned and completed by recovery within three poll intervals with no `WORKFLOW_FAILED` and no compensation; a single failed connection is absorbed in place). `TerminalWriteAtomicityIT` still passes; its injected failure now abandons as `STORE_UNAVAILABLE`. See [Operations §10.9](operations.md#109-store-outages-store_unavailable-abandons) and [Issue 34](open-issues.md#issue-34).

**Upgrade.** No migration, no configuration and no public API removed; rolling-deploy safe. `maestro_workflow_event` rows written before this change are unaffected. Expect `STORE_UNAVAILABLE` abandons in bursts during a database outage. Alert on a *sustained* `STORE_UNAVAILABLE` abandon rate after the database is back (from `maestro_run_abandoned_total{reason="store_unavailable"}`, the `WARN` or the span attribute), or on one run id that abandons on every recovery pass: that is a deterministic fault such as a schema error (class `42`; the `WARN`'s SQLSTATE names it), not an outage. An oversized payload (class `54`) now fails the workflow instead. `TERMINAL_WRITE_FAILED` no longer fires for plain outages, so an alert on it now means a non-outage rejection of the terminal write. `AbandonReason` gained a constant, so a `switch` over it with no `default` must handle `STORE_UNAVAILABLE`. A custom `WorkflowStore` that throws its own exceptions keeps the old behaviour (a failure fails the workflow) until it throws `StoreUnavailableException` for infrastructure faults.

### Developer experience — the saga demo compensates; the test kit replays

The flagship sample taught the wrong lesson, and the test kit could not catch it. `OrderFulfilmentWorkflow` *returned* `OrderResult.failed(...)` on a declined or timed-out payment, beside a comment saying the saga would release the reservation. The engine compensates only when the workflow method throws, so the order was recorded `COMPLETED`, `releaseReservation` never ran, and no sample test covered the failure path. Separately, `TestWorkflowEnvironment` ran each workflow once, start to finish. A user's test never read a history back, so result deserialisation, the replay guards and the activity-argument fingerprint ran for the first time on a production restart.

- **The order sample compensates.** Both failure branches notify the customer and then throw the new `OrderFailedException` (`"Payment timed out"`, `"Payment failed: <reason>"`). The `@Saga` releases the reservation, and the order ends `FAILED`. `OrderResult` is now the success shape only, `(orderId, trackingNumber)`. `GET /orders/{id}/status` returns `OrderStatusResponse(orderId, status, step, failureReason)`. The status and failure are store-backed and work on any node; the step comes from the `@QueryMethod` and is `null` off the owning node. An unknown order is a 404. New tests: decline and timeout each end `FAILED` with exactly one release and no shipment; a crash before the decline compensates with the replayed reservation; a crash while awaiting payment still ships; a crash before the payment request is recorded publishes it twice. The payment gateway, which has no `@Saga` and nothing to undo, keeps returning a decline; its Javadoc now says why.
- **`WorkflowHandle.failure()`** (starter) returns `Optional<WorkflowFailureDetail>`, the recorded exception class and message of a `FAILED` workflow. It is store-backed, like `status()` and `result()`.
- **Crash and recover in the test kit.** `TestWorkflowEnvironment` gains `crashAfter(n)`, `crashAfterPersisting(predicate)`, `crashBeforePersisting(predicate)`, `crashNow()`, `recover()` and `recover(Class<?>...)`, which recovers onto new workflow code under the same type names. A crash kills the node's workflow threads at their next store write (the engine's own `ExecutorShutdownException`, so nothing is recorded). The test thread can still signal and advance time while the node is down. `recover()` starts a fresh executor over the same store, which re-adopts every active workflow through the production recovery path. The crash returns a `SimulatedCrash` handle. `TestWorkflowHandle` gains `awaitStatus`, `awaitTerminal`, `getFailure`, `liveSteps`, `replayedSteps`, `liveExecutions`, `replays`, `recoveries`, `standDowns` and `awaitStandDown` (new `StandDown` record). `DeterminismChecker.assertRecoverable` crashes after every activity completion in turn and requires each recovery to finish exactly as an uninterrupted run (meant for sequential workflows). A crash is announced only after the node's in-flight writes have drained, so nothing lands after `hasCrashed()` is true. The terminal waits (`awaitTerminal`, `getResult`, `awaitCompletion`) also wait for the run to leave its node, so a `query()` right after them throws `WorkflowNotQueryableException` as it would in production. `@MaestroTest` needs no change.
- **Kit internals.** Each simulated node writes through a `java.lang.reflect.Proxy` view of the shared `InMemoryWorkflowStore`, so the fenced `RunLease` defaults reach the store's real overrides. The executors use the full constructor with the engine's defaults plus a recording `EngineObserver`, and activity proxies use the observer-aware `createProxy`. `InMemoryDistributedLock` stays on the wall clock, deliberately: on the test clock, `advanceTime(days)` would expire every live lock.
- **Docs.** New [`docs/pitfalls.md`](pitfalls.md) gives ten rules, each with wrong code, right code and what you see. `docs/testing.md` gains a "Crash-Recovery Testing" section; its full example is now the real order-sample test, and the snippets that did not compile are fixed (`PaymentResult` construction, `result.status()`, the `Thread.sleep` polling helper replaced by `awaitStatus`). `docs/getting-started.md` adds a `crashNow().recover()` test. `docs/concepts.md` states when compensation runs and documents `failure()`. `CLAUDE.md` records that `com.fasterxml.jackson.annotation` (`@JsonTypeInfo`/`@JsonSubTypes`), which Jackson 3 keeps, is allowed.

**Upgrade.** No migration and no engine change: `maestro-core` is untouched. Two source changes in the sample: callers of the order sample's `OrderResult.failed()` factory and its `success()`/`failureReason()` accessors, and clients of its status endpoint, which returned `{"currentStep": ...}` and now returns `OrderStatusResponse`. `TestWorkflowHandle`'s package-private constructor changed; the public API only grew.

### Signals — idempotent delivery and atomic consumption

Kafka is at-least-once and the engine's consumer was not idempotent: every delivery of a signal became a new `maestro_workflow_signal` row with a random id. A record redelivered after a crash or rebalance (insert committed, offset not), a producer that republished, or an HTTP client that retried `WorkflowHandle.signal` all left a twin — harmless for a single await, but a workflow that awaits the same name again (the loan sample's `verification.result` loop, `collectSignals("document.uploaded", n)`) consumed the twin as the next signal. Separately, consuming appended the `SIGNAL_RECEIVED` memo and flipped `consumed` as two statements and proceeded when the flip lost: a crash between them left a consumed memo beside an unconsumed row that the next same-name await consumed again, and two parallel branches awaiting one name both took one row's payload.

- **Signal identity.** `SignalMessage` and `WorkflowSignal` gain a nullable `messageId`; the store keeps at most one row per `(workflow_id, signal_name, message_id)` (`WorkflowStore.saveSignalIfAbsent`). A duplicate writes nothing, is acknowledged, and is counted as `maestro.signal.duplicates{workflow,signal}` (`EngineObserver.signalDuplicateSuppressed`; an orphan duplicate is tagged `unknown`/`unknown` so off-the-wire names never become tag values). A signal without an id is never deduplicated.
- **Where ids come from, namespaced by source** so none can forge another's key: the engine-channel envelope (`env:<id>`; the admin dashboard mints `admin:<uuid>` per action), the new `maestro-message-id` Kafka header (`hdr:<topic>:<id>`; `MaestroSignalHeaders`, with `SignalMessageIds.forCurrentActivity()` for a stable `"<workflowId>:<seq>"` from inside the publishing activity), else the record's coordinate `kafka:<topicId>:<partition>:<offset>` using the KIP-516 topic id (cached per topic, dropped on reassignment; no fallback when the topic id cannot be resolved), and `WorkflowHandle.signal(name, payload, messageId)` / `TestWorkflowHandle.signal(...)` (`client:<id>`). `publishSignal` mirrors the envelope id into the header. A header over 512 UTF-8 bytes or containing a control character is ignored; the engine treats any id over 512 characters or with a control character as absent — never a failed insert. See `docs/cross-service.md` § Signal identity.
- **Atomic consume.** `WorkflowStore.appendEventAndConsumeSignal` writes the fenced memo and the flip in one transaction (JDBC: fenced → `FencedWriteException`, lost flip or `40001`/`40P01` → `false` with nothing written, memo collision → `DuplicateEventException`). `SignalManager` tries the earliest unconsumed row and, when a concurrent consumer took it, the next — at most 10 per pass — and otherwise falls into the park path. The memo payload is unchanged (the raw signal payload), so replay and history are untouched. `getUnconsumedSignals` orders by `received_at, id`.
- **SPI defaults keep old behaviour.** Both new `WorkflowStore` methods are `default`s: `saveSignalIfAbsent` delegates to `saveSignal` (no deduplication) and `appendEventAndConsumeSignal` composes the two writes and always returns `true` once the memo is appended (the old at-least-once consume). The JDBC and in-memory stores override both. `InMemoryWorkflowStore`'s fenced timer and signal writes now take their data lock before the instance lock (the purge's order) — the previous order could deadlock against a concurrent purge.
- **Samples and harness.** The sample Kafka activities set `maestro-message-id`; the loan REST signal endpoints accept `Idempotency-Key`; the chaos driver sends one per logical event and I4 treats `consumedTwin=true` as a hard violation.
- **Behaviour change.** Two signals with the same name and id are one signal: a producer that reuses an id for distinct signals now loses the second. `WorkflowExecutor.deliverSignal(..., messageId)` returns `false` for a duplicate. `KafkaDuplicateDeliveryIT` (which pinned twin rows as expected) now pins suppression.

**Upgrade.** Migration `V9__signal_message_id.sql` adds `message_id TEXT` (nullable, metadata-only) and the partial unique index `idx_wf_signal_message_id`. This build names `message_id` in every signal insert and read, so without V9 every delivery fails inside the listener and is dead-lettered. By `maestro.store.schema.mode`:
- **`flyway`**: nothing to do; the first node applies and records V9.
- **`none`**: apply the V9 lines of the hand-apply block in `docs/configuration.md` before deploying. This is a hard prerequisite.
- **`validate`**: V9 must be applied *and recorded* in `maestro_schema_history` before a node of this build starts. SQL applied by hand is not recorded, and the node fails with `pending migrations [9]`. Record it through the Flyway CLI route in `docs/configuration.md` § Schema management, as the role that owns the tables, or with one start in `flyway` mode if the application's role owns them.

Mixed fleet: an older node that is already running keeps working against the V9 schema (it inserts `NULL` ids and ignores the column), but deduplication and atomic consumption hold only once every node runs this build. V9 sorts below V100, so a node on a build without it (an unreleased main build from c01d498, PR #35, up to but not including PR #43; see the Issue 35 entry for builds before PR #36) cannot *restart* in `flyway` or `validate` mode once V9 is recorded: `Detected applied migration not resolved locally: 9`. Such a node starts only with `schema.mode: none`. Every build from e91bd9d (PR #43) on ships V9.

### Fencing — lease epoch validated on every run-scoped write

Closes `docs/open-issues.md` Issue 11. A node that loses its instance lock — a GC pause longer than the TTL, `docker pause`, a partition, or a deployment with no lock backend at all — can no longer persist workflow progress once a peer has adopted the workflow. Before, only the unique `(workflow_instance_id, sequence_number)` event index stood between two nodes running the same workflow, and it caught the loser only at a sequence the winner had already written; the loser could still arm timers, flip signals and write statuses in the gaps.

- **New column `maestro_workflow_instance.lease_epoch`** (`V6__instance_lease_epoch.sql`, `BIGINT NOT NULL DEFAULT 0`; metadata-only on PostgreSQL 11+, every existing row reads back as epoch `0`). Every adoption of an instance — a recovery resume, a manual retry's relaunch — bumps it by one under the row's version compare-and-set; a fresh start writes `0`. Carried on `WorkflowInstance.leaseEpoch()` and on the run's `WorkflowContext.lease()` (`io.b2mash.maestro.core.spi.RunLease`).
- **`WorkflowStore` gains seven fenced overloads** taking a `RunLease`, as `default` methods that delegate to the unfenced forms — an existing implementation keeps compiling and stays unfenced until it overrides them. `appendEvent`, `completeInstance`, `updateInstance` and `saveTimer` throw the new sealed `io.b2mash.maestro.core.exception.FencedWriteException` and write nothing when the row's epoch has moved on; `markSignalConsumed`, `markTimerFired` and `markTimerCancelled` return `false`. The shipped JDBC store implements them as one `INSERT ... SELECT ... FROM maestro_workflow_instance WHERE id = ? AND lease_epoch = ?` (+ the new `fenceRowLockClause()` hook, `FOR SHARE` on Postgres, so a concurrent adoption orders strictly before or after the write), `AND lease_epoch = ?` on the instance CAS, and `AND EXISTS (...)` on the flips. `InMemoryWorkflowStore` (`maestro-test`) implements them too. The unfenced forms remain for writes that are not a run's own: `createInstance`, `saveSignal`/`adoptOrphanedSignals`, the timer leader's `markTimerFired`, operator terminate/retry, and the adoption bump itself.
- **Every engine write a running workflow's own thread makes passes its lease** — activity results and failures, `sleep()`/`awaitSignal()` timer arms and memos, signal consumption, park/wake status writes, the `COMPENSATING` transition, compensation memos, and the terminal write. A fenced run stands down with the new `StandDownReason.LEASE_FENCED` (`maestro.standdown{reason=lease_fenced}`): nothing written, no failure recorded, no compensation run; the adopting node's durable state governs. Like `STALE_RUN`, the stand-down is sticky — workflow code that swallows the exception still stops at its next live step.
- **Proactive stand-down.** When the lock renewer reports an instance lock lost, the run is marked stale at once and stands down at its next stop point (`stale_run`, detail `instance lock lost`) instead of first attempting a write the fence would reject. The fence is the guarantee; this only shortens the window.
- **What is still not bounded:** the one activity already executing on the stale node at the instant of adoption runs to completion there (its result is then fenced) and the adopter runs it again. The fence bounds persisted progress, not external side effects — activities must stay idempotent (`CLAUDE.md`, `docs/operations.md` §6).
- **Behaviour change in tests and observability:** a dual-execution loser now reports `lease_fenced` where it used to report `stale_run` from a `DuplicateEventException`; the unique index remains as depth beneath the fence. `MultiNodeNoLockBackendIT` and `EnginePostgresDualExecutionIT` were rewritten accordingly; `MultiNodeFencingIT` is the Issue 11 "done when" (a node holding a real, expired Postgres lock resumes into the adopter's run and is fenced).
- **Multi-node without a lock backend** remains unsupported but is now safe: two lock-less nodes adopting the same workflow either race the adoption CAS (one yields without launching) or adopt in turn (the earlier adopter's first write is fenced).

**Upgrade.** This build names `lease_epoch` in every instance read and write, so without V6 no workflow can be started, resumed or read. By `maestro.store.schema.mode`:
- **`flyway`**: nothing to do; the first node applies and records V6.
- **`none`**: apply V6 by hand before deploying this build (see `docs/configuration.md`, "Upgrading an existing database", for the hand-apply block and `flyway_schema_history` DELETE).
- **`validate`**: V6 must be applied *and recorded* in `maestro_schema_history` before a node of this build starts; SQL applied by hand is not recorded (`pending migrations [6]`). Record it through the Flyway CLI route in `docs/configuration.md` § Schema management, as the role that owns the tables, or with one start in `flyway` mode if the application's role owns them.

Mixed fleet: running nodes are safe together. The column defaults to `0`, which is the epoch every pre-upgrade run implicitly holds, and an old node's unfenced writes keep working against a V6 schema. V6 sorts below V100, so a node on a build without it (an unreleased main build from c01d498, PR #35, up to but not including PR #38; see the Issue 35 entry for builds before PR #36) cannot *restart* in `flyway` or `validate` mode once V6 is recorded (`Detected applied migration not resolved locally: 6`). Such a node starts only with `schema.mode: none`.

### Retention — terminal workflows are purged

Until now nothing ever deleted a finished workflow: every `COMPLETED`, `FAILED` and `TERMINATED` instance stayed in `maestro_workflow_instance` with its full event log, signals and timers, so the host database grew with the number of workflows ever run (`reports/Maestro framework value assessment.md`, gap 2). A **retention sweeper** now bounds that.

- **What it does.** Every `maestro.retention.interval` (default `1h`) the elected leader for the service deletes workflows whose `completed_at` is older than their status's retention — `completed-retention` **30d**, `terminated-retention` **30d**, `failed-retention` **90d** (longer, because an operator may still retry a failure) — together with *all* of their history: events, signals consumed or not, timers of any status. In batches of `batch-size` (500) instances, each batch one transaction, at most `max-batches-per-sweep` (100) batches per status per sweep. Orphaned signals and instances with no `completed_at` are never touched. `maestro.retention.enabled: false` restores the keep-forever behaviour.
- **One leader per service.** `maestro:leader:retention:<service>` in `maestro_leader_election` (15 s TTL), re-elected before every batch; the Postgres candidate select is `FOR UPDATE SKIP LOCKED`, so a concurrent retry can neither interleave with a batch nor lose its memoization log. Without a lock backend the single node is always the leader.
- **New SPI method** `WorkflowStore.purgeTerminalInstancesBefore(status, cutoff, batchSize)` returning the new `PurgeResult` record; it is **abstract**, so a custom `WorkflowStore` must implement it. New `EngineObserver.retentionPass(PurgeResult)` (default no-op). `WorkflowExecutor.startRetentionSweeper(RetentionSettings)` for embedders not using the starter.
- **Observability.** Counters `maestro.retention.sweeps` and `maestro.retention.purged{kind=instance|event|signal|timer}`; `/actuator/health` detail `retentionSweeperRunning` (`true`/`false`/`"starting"`/`"disabled"`).
- **The signal janitor is unchanged** (`maestro.signal.terminal-retention`, 7d): it still purges only the unconsumed signals of finished workflows, earlier and on every node.
- **Migration `V8__retention_indexes.sql`** (`classpath:db/maestro/store`): `idx_wf_instance_terminal` on `(status, completed_at) WHERE completed_at IS NOT NULL` and `idx_wf_signal_instance` on `maestro_workflow_signal(workflow_instance_id)`, both `CREATE INDEX IF NOT EXISTS`.

**Upgrade.**

1. **Large tables: pre-create the V8 indexes.** Flyway runs V8 in a transaction, so its `CREATE INDEX` holds a `SHARE` lock on `maestro_workflow_instance` and `maestro_workflow_signal` while it builds — writes wait. Run `CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_wf_instance_terminal ...` and `... idx_wf_signal_instance ...` by hand first (the exact statements are in `docs/configuration.md` § Retention Configuration); the migration then finds them and does nothing. Under `maestro.store.schema.mode: none`, apply V8 yourself the same way. Under `validate`, the hand-made indexes do not record V8 (`pending migrations [8]`): record it through the Flyway CLI route in `docs/configuration.md` § Schema management, as the role that owns the tables, or with one start in `flyway` mode if the application's role owns them. V8 sorts below V100, so a node on a build without it (an unreleased main build from c01d498, PR #35, up to but not including PR #39; see the Issue 35 entry for builds before PR #36) cannot restart in `flyway` or `validate` mode once V8 is recorded.
2. **Expect the first sweep to be large.** On a database that has never been purged, everything older than the retentions becomes eligible at once and drains at up to 50 000 instances per status per hour at the defaults. Raise the retentions first and lower them later, or set `maestro.retention.enabled: false`, if you want to control the pace.
3. **The admin dashboard keeps showing purged workflows** (it has its own copy from the lifecycle events); **Retry/Terminate on one now reports `NOT_FOUND`**. A purged `workflow_id` becomes free for a new `startWorkflow`, which adopts any orphaned signals still addressed to it — see `docs/operations.md` § 11 if your IDs are reused across time.
4. **Custom `WorkflowStore` implementations must implement `purgeTerminalInstancesBefore`** (see its Javadoc for the children-before-parent and all-or-nothing contract). The chaos harness runs with `MAESTRO_RETENTION_ENABLED=false`.


### Kafka — missing topics fail startup; lifecycle publishes never block a workflow

**Behaviour change: a service whose Kafka topics — `.DLT` companions included — do not exist no longer starts.** `reports/Maestro framework value assessment.md` found that "Maestro never auto-creates Kafka topics" was policy, not code: nothing set `allow.auto.create.topics`, nothing set `missingTopicsFatal`, the only guard was a warn-only dead-letter probe, and a missing admin-events topic cost `max.block.ms` (60 s) on every lifecycle publish — inline on the workflow thread for every activity, timer, signal and compensation event (the never-explained six-of-six loan E2E failures, Issue 3).

- **New property `maestro.messaging.topics.verify-on-startup` = `fail` | `warn` | `off`, default `fail`.** At startup — once, from `MaestroSignalListenerBeanPostProcessor.afterSingletonsInstantiated`, before any consumer container starts and before startup recovery — `KafkaTopicVerifier` asks the broker whether every topic the service needs exists: `maestro.signals.<service>` (or `maestro.messaging.topics.signals`) and its `.DLT` when redelivery is on, the admin-events topic when `maestro.admin.events.enabled`, and every `@MaestroSignalListener` topic and its `.DLT`. Under `fail` a confirmed-missing topic fails the context with one `IllegalStateException` listing every missing topic and a ready `kafka-topics.sh --create` line each; `warn` logs the same and continues; `off` restores the previous behaviour. A probe the broker does not answer (unreachable, slow, unauthorised) is *inconclusive* — WARN only, never fatal — so a broker blip cannot become a fleet-wide restart loop. The verifier's `Admin` client carries the consumer factory's `security.*`/`sasl.*`/`ssl.*` settings. `KafkaDeadLetterTopicCheck` (the per-subscription warn-only probe) is removed.
- **Never auto-create, in code.** The engine consumer defaults `allow.auto.create.topics=false` (an overridable `spring.kafka.*` default, not a forced invariant); under `fail`, once the startup probe has confirmed every required topic present, both container builders set `missingTopicsFatal(true)`, so a topic that disappears after startup fails the container instead of polling nothing — after an inconclusive probe that boot's containers stay tolerant, since Spring Kafka's check counts an unreachable broker as missing and would otherwise fail `start()` right after the "starting anyway" WARN; every container sets `authExceptionRetryInterval=10s` so an auth failure retries instead of stopping the container silently.
- **Bounded producer stalls, no late deliveries.** The engine producer defaults `max.block.ms=10000` (Kafka's default is 60 s; `spring.kafka.producer.properties.max.block.ms` wins). `publishSignal` now waits for the acknowledgement for the producer's own delivery budget — `max.block.ms + delivery.timeout.ms + 5 s`, read from the producer factory at construction (135 s with Maestro's defaults; 185 s with Kafka's) — and then fails with `IllegalStateException`. Because the wait outlasts the producer's retry budget, a thrown `publishSignal` guarantees the record will not be delivered later, so a caller's retry cannot duplicate it; the `WorkflowMessaging` SPI now documents that contract.
- **Lifecycle publishes leave the workflow thread.** New `core.engine.LifecycleEventSink` and `WorkflowExecutor.lifecycleEventSink()`: `ActivityInvocationHandler` (`ACTIVITY_*`), `SignalManager` (`SIGNAL_RECEIVED`), `DefaultWorkflowOperations` (`TIMER_*`) and `SagaManager` (`COMPENSATION_*`) — which each used to call `WorkflowMessaging.publishLifecycleEvent` inline — now hand events to the executor's bounded off-thread publisher, as `WORKFLOW_*` already did. New sink-taking constructors/`ActivityProxyFactory.createProxy` overload; every existing signature is kept and publishes inline via `LifecycleEventSink.direct(messaging)`. `ActivityStubBeanPostProcessor` wires the executor's sink into activity proxies. Measured in `KafkaLifecycleEventLatencyIT`: a ten-activity workflow against a missing admin-events topic with `max.block.ms=2000` completes in ~370 ms where the inline path cost 20 × `max.block.ms`.
- **Compat:** `KafkaMessagingConfig` gains a tenth component, `verifyOnStartup`; the nine-argument constructor remains and defaults it to `FAIL`. New `KafkaMessagingConfig.signalTopicFor`/`deadLetterTopicFor`.
- **Fixtures and compose:** the shared IT broker and the Kafka module's test broker run with `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`; every Kafka suite and the chaos cluster pre-create their `.DLT` companions; the root `docker-compose.yml` gained `maestro.signals.order-service.DLT`, `maestro.signals.payment-gateway.DLT`, `payments.requests.DLT` and `payments.results.DLT` (loan and demo compose already had theirs).
- **Upgrade.** Before deploying, create the `.DLT` companion of every topic a Maestro consumer subscribes to — the startup failure prints the exact commands — or set `maestro.messaging.topics.verify-on-startup=warn` to defer. Runbook: `docs/operations.md` § 12; reference: `docs/configuration.md` § Kafka Topic Verification.

### Replay — activity arguments are fingerprinted; value-level drift stands down

Memoization matched a stored activity outcome to a replayed call by sequence number and (since C10) step name — never by what the activity was called *with*. A workflow whose activity argument depended on something non-deterministic (a `HashMap`'s iteration order, `Instant.now()`, a config value that differs between nodes, an ungated change to how the argument is built) replayed the recorded result for an input the recorded run never saw, silently, and carried on. The assessment's "silent wrong replay".

- **New column `maestro_workflow_event.args_hash`** (`V7__workflow_event_args_hash.sql`, `VARCHAR(64)`, nullable, no default; metadata-only on PostgreSQL). Every `ACTIVITY_COMPLETED` and `ACTIVITY_FAILED` event the engine writes — `@Compensate` outcomes included — carries the SHA-256 of a canonical rendering of the call's arguments (`io.b2mash.maestro.core.engine.ArgumentFingerprint`: object keys sorted, numbers normalised so `1`, `1.0` and `1E0` agree, strings JSON-escaped, arity-prefixed). `WorkflowEvent` gains an eighth component, `argsHash`; the seven-component constructor remains.
- **Replay compares it.** After the step-name check, `ReplayGuard.requireArgumentsMatch` fingerprints the current call and stands the run down as `nondeterministic_history` when two present hashes differ — before the recorded payload is returned and before any `@Compensate` entry is registered. The observer detail is `seq=<n> step=<group.method> argsHash expected=<8 hex> stored=<8 hex>`; no argument value is ever quoted (R17). Nothing is written, no failure recorded, the instance stays recoverable.
- **`NULL` is unverified, never a mismatch** — a row written before V7, a row an older node writes mid-rollout, or arguments the mapper cannot serialize (logged once at DEBUG). So the rolling deploy is safe in both directions; the check simply covers more rows as the fleet upgrades.
- **New property `maestro.replay.verify-arguments`** (default `true`). `false` skips the comparison — to ride out a known-benign drift while it is fixed — but live outcomes still record their fingerprint, so re-enabling covers the rows written meanwhile. A WARN is logged at startup while it is off.
- **`DeterminismChecker`** (`maestro-test`) entries are now `sequence:eventType:stepName:argsHash`, so a run-to-run difference in an activity's *input* is reported as a divergence, not just a difference in the step sequence.
- **Still not compared:** the duration passed to `sleep()`, the timeout passed to `awaitSignal()`, the recorded `currentTime()` / `randomUUID()` side-effect values, and `parallel()` branch contents beyond their count (`docs/concepts.md`, "The Determinism Rule").

**Upgrade.** This build names `args_hash` in every event insert and read, so without V7 no activity outcome can be recorded or replayed. By `maestro.store.schema.mode`:
- **`flyway`**: nothing to do; the first node applies and records V7.
- **`none`**: apply V7 by hand before deploying this build (`docs/configuration.md`, "Upgrading an existing database").
- **`validate`**: V7 must be applied *and recorded* in `maestro_schema_history` before a node of this build starts; SQL applied by hand is not recorded (`pending migrations [7]`). Record it through the Flyway CLI route in `docs/configuration.md` § Schema management, as the role that owns the tables, or with one start in `flyway` mode if the application's role owns them.

Mixed fleet: the fingerprint check is safe in both directions (above). V7 sorts below V100, so a node on a build without it (an unreleased main build from c01d498, PR #35, up to but not including PR #41, including ee17041 (PR #39), which ships V8 but not V7; see the Issue 35 entry for builds before PR #36) cannot *restart* in `flyway` or `validate` mode once V7 is recorded (`Detected applied migration not resolved locally: 7`). Such a node starts only with `schema.mode: none`. An in-flight instance whose argument had *already* drifted silently under an older build has no fingerprint on the drifted row and keeps replaying as before; one that drifts after upgrade stands down — see `docs/operations.md` §10.7 for the remedies.

### Removed — the Valkey lock, the Postgres messaging transport and the Postgres-only sample; Postgres + Kafka is the whole stack

Rationale: `reports/Maestro framework value assessment.md` — two lock backends and two transports multiplied the test matrix, the upgrade procedures and the documentation without adding durability; the one thing only Valkey offered (an instant cross-node signal wake) is now provided by Postgres itself.

- **Removed modules:** `maestro-lock-valkey` (Lettuce `DistributedLock` + pub/sub `ValkeySignalNotifier`) and `maestro-messaging-postgres` (the Postgres `WorkflowMessaging` transport: `maestro_signal_queue`, its LISTEN/NOTIFY wake, its redelivery/dead-letter rows and `PostgresMessageCleaner`). **Removed sample:** `sample-postgres-only`. The supported stack is Java 25, Spring Boot 4, PostgreSQL 14+ and Apache Kafka; the published modules are `maestro-bom`, `maestro-core`, `maestro-spring-boot-starter`, `maestro-store-jdbc`, `maestro-store-postgres`, `maestro-messaging-kafka`, `maestro-admin-client` and `maestro-test`.
- **Lock folded into the store.** `maestro-lock-postgres` no longer exists as a module: `io.b2mash.maestro:maestro-store-postgres` now provides the Postgres `WorkflowStore` *and* the Postgres `DistributedLock` — `io.b2mash.maestro.store.postgres.lock.PostgresDistributedLock`, tables `maestro_distributed_lock` and `maestro_leader_election`, migration band 100–199 still at `classpath:db/maestro/lock` (`V100`). Lock keys are unchanged (`maestro:lock:workflow:{id}`, `maestro:lock:activity:{id}:{seq}`, `maestro:leader:timer-poller:{service}`; `maestro.lock.key-prefix` default `maestro:lock:`, `maestro.lock.ttl` 30s) and are now the `lock_key` column values of those tables.
- **One accepted value each.** `maestro.lock.type` defaults to `postgres` and accepts only `postgres`; `maestro.messaging.type` defaults to `kafka` and accepts only `kafka`. Any other value — `valkey`, `postgres` for messaging — fails startup with a message naming the removal.
- **Cross-node signal wake moved into `maestro-store-postgres`** as `io.b2mash.maestro.store.postgres.notify.PostgresSignalNotifier` + `PostgresWakeListener`, auto-configured by `PostgresSignalWakeAutoConfiguration` whenever the engine is active, so every deployment now has an instant cross-node wake (the Postgres-lock profile previously had none and waited for the 30s recheck). It uses **one channel per service**, `maestro_signal_wake_<service-name ≤32 chars>_<8-hex sha256>`, whose payload carries the workflow ID and signal name and which the listener dispatches in memory to the parked waiter. This fixes two bugs of the removed transport's per-workflow channels: Postgres silently truncates identifiers to 63 bytes, so **workflow IDs longer than 48 characters never received an instant wake**, and the sanitising of punctuation **let distinct workflow IDs share one channel**. The sender's `NOTIFY` runs on a virtual thread after the signal row commits and never blocks `deliverSignal` (Issue 32's guarantee, now pinned by `PostgresSignalNotifierTest.publishDoesNotWaitForAConnection` in `maestro-store-postgres`). The listener holds **one connection borrowed from the application `DataSource` for the application's lifetime** — effective pool capacity is one less, and a configured Hikari `leakDetectionThreshold` logs one false positive for it — and reconnects with 1 s → 30 s backoff; `maestro.signal.wake-recheck-interval` (30s) remains the correctness fallback.
- **New property `maestro.signal.wake-notify.enabled`** (default `true`). Set `false` behind PgBouncer in transaction-pooling mode (`LISTEN` needs a session); wake then falls back to the recheck interval.
- **New property `maestro.lock.cleanup-interval`** (default `10m`). The lock cleaner that deletes rows expired for more than a minute was an inert bean before — it is now actually scheduled on every node. Expired rows were already takeable, so this only bounds table growth.
- **`maestro.messaging.redelivery.*` applies to Kafka only** — the Postgres dead-letter rows, `listDeadLetterSignals`/`replaySignal` and the stale-claim retirement (Issue 27) left with the transport. Signals whose handler exhausts the budget land on the `.DLT` topic as before.
- **Chaos harness:** backend-outage targets are `{postgres, kafka}`; recorded seeds from before 2026-11 do not replay identically.

**Upgrade.**

1. **Stop every node first — this change does not roll.** A node on an earlier build locking in Valkey and a node on this build locking in `maestro_distributed_lock` do not exclude each other, so both could run the same workflow at once.
2. **Dependencies:** drop `maestro-lock-valkey`, `maestro-lock-postgres` and `maestro-messaging-postgres`; keep `maestro-store-postgres` and `maestro-messaging-kafka`. A service that ran Postgres-only now needs Kafka and the `maestro.signals.<service>` topic (plus its `.DLT`).
3. **Configuration:** remove `maestro.lock.type: valkey`, `maestro.lock.valkey.uri` and every `spring.data.redis.*` property Maestro was the only consumer of; remove `maestro.messaging.type: postgres`. Leaving `lock.type: valkey` or `messaging.type: postgres` in place fails startup by design.
4. **Schema:** nothing to run. Migration band 200–299 is retired and is never reused; a database that applied `V200`–`V202` keeps those rows in `maestro_schema_history` and an orphan `maestro_signal_queue` table (and `maestro_task_queue` / `maestro_lifecycle_event_queue` if it never reached `V202`). Flyway's default `ignoreMigrationPatterns=*:future` treats the rows as future versions, so `flyway` and `validate` modes keep passing; `DROP TABLE IF EXISTS maestro_signal_queue, maestro_task_queue, maestro_lifecycle_event_queue;` is optional cleanup once no earlier node remains. Databases from 0.4.0 and earlier still follow "Maestro owns its schema migrations" below, now applying `V4` and `V5` only.
5. **Connection pool:** budget one extra connection per node for the `LISTEN` session, or set `maestro.signal.wake-notify.enabled: false` behind a transaction-pooling PgBouncer.
6. **CI / tooling:** the `E2E_LOCK_BACKEND` switch is gone (the loan E2E runs on Postgres only); chaos seeds recorded before 2026-11 are not comparable.


### Verified — the whole bug-fix branch passes every gate with zero invariant violations

All gates below ran at `ec9286d`:
- `./gradlew build`, with every task re-run.
- The integration suites against real Postgres, Kafka and Valkey: 147 tests, 0 failures.
- The ten-scenario loan-origination E2E on both lock backends, Valkey and Postgres: 10/10 each.
- Three consecutive fresh-seed chaos PR-gates.
- A 75-minute local soak on the seed of the triaged Issue 32 failure.
- The dispatched CI run [40762898010](https://github.com/rakheen-dama/maestro/actions/runs/40762898010). All four jobs succeeded: both E2E backends, three more PR-gates, and one 2 h chaos soak (2320 workflows).

Across the 8 chaos runs there were zero invariant violations and zero unexplained duplicate side effects. Two blocked attempts came first: a test-clock flake (R59) and a retry-after-timeout regression (R61, R62). Both were fixed and the gate was re-run from the build.

Evidence: `docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/branch-verification.md` and `triage/branch-logs/`.

The nightly E2E schedule is enabled again. It heals itself: `build-test.yml`'s `keep-nightly-enabled` job turns it back on after a push if GitHub's inactivity rule disabled it.

### Fixed — admin retry after an `awaitSignal` timeout waits the full timeout again (R61)

- **Before:** with durable await deadlines (C12/F4, below), retrying a workflow that `FAILED` on an uncaught `SignalTimeoutException` deleted only the failing `SIGNAL_TIMEOUT` memo. The await's `TIMER_SCHEDULED` memo, which carries the absolute deadline, and its `FIRED` deadline row survived, so the retried run resumed the await past its old deadline and failed again within milliseconds — unless the signal had already arrived. The loan E2E's cross-node retry scenario (retry first, decision afterwards) failed this way 42 ms after the retry.
- **Now:** `WorkflowStore.deleteFailureEvents` also deletes the failing await's spent deadline: the `TIMER_SCHEDULED` memo in the slot just before the failing `SIGNAL_TIMEOUT` (same `$maestro:awaitSignal:<name>` step name) and every timer row of the instance named `await-signal-<that seq>`. The retried await takes the live path at the same sequence slots: a signal that arrived meanwhile is consumed at once; otherwise it arms a fresh full timeout from the retry, with a fresh `PENDING` row, and parks. `sleep()` memos and rows, earlier (caught) awaits' memos and rows, and compensated-saga retry semantics are unchanged. The JDBC store now runs the whole delete in one transaction. Pinned by `AwaitTimeoutRetryRearmTest`, `AwaitTimeoutRetryRearmIT`, and new `deleteFailureEvents` cases in `PostgresWorkflowStoreTest`, `InMemoryWorkflowStoreTest` and `TestEventLogsTest`.
- **SPI note:** a custom `WorkflowStore` must delete the failing await's park memo and deadline rows too (see the `deleteFailureEvents` Javadoc). A store that does not keeps the pre-R61 behaviour: the retried await times out again immediately unless the signal is already there.

### Fixed — `TestWorkflowEnvironment` arms deadlines on its test clock, so `advanceTime(timeout)` is exact (R59)

- **Before:** `sleep()` timers and `awaitSignal()` deadlines were armed from the wall clock (`Instant.now() + timeout`), and a parked await also decided "deadline passed" against the wall clock, while `TestWorkflowEnvironment` drove a `ControllableClock` frozen when the environment was created. Three consequences: `advanceTime(timeout)` stopped a few milliseconds short of a deadline armed after the clock started, so tests and docs advanced `timeout.plusSeconds(1)`; that 1 s margin let `advanceTime` fire a *second* deadline the woken workflow armed while the call was still running; and a short await (the loan sample's 1 s withdrawal gates) expired on the wall clock by itself whenever the test thread was slow. Under a loaded `./gradlew build` this failed `LoanApplicationWorkflowTest` (the workflow completed through both gates before the test advanced the clock), `UnderwritingWorkflowTest` (the senior-desk deadline fired in the same advance as the underwriter one) and, separately, `LoanApplicationWorkflowV2Test` (a signal delivered before its await changed the event layout).
- **Now:** deadline arming reads an injectable `java.time.InstantSource`. `WorkflowExecutor` takes it through two new constructors — `(store, lock, messaging, notifier, serializer, serviceName, InstantSource)` and the 13-argument form plus a trailing `InstantSource` — and exposes it as `instantSource()`; every existing constructor, and the Spring starter, use `InstantSource.system()`, so production behaviour is unchanged. The source is read for a live `sleep()`'s `fireAt`, a live `awaitSignal()`'s deadline and its "deadline passed" check, and the value a live `workflow.currentTime()` records (`collectSignals` budgets its overall timeout from it, and `retryUntil` measures its `maxDuration` with it — test time under the test kit). Replay still reads memoized deadlines back; the timer poller still compares due rows against the system clock. `TestWorkflowEnvironment` passes its `ControllableClock` (now an `InstantSource`, starting at microsecond precision), so `env.advanceTime(timeout)` fires a deadline armed before it, no deadline expires on the wall clock, and a timer armed by a workflow that one `advanceTime` call woke is due after that call's target and fires on the next call. `advanceTime` fixes its target instant once per call. `docs/testing.md`, `docs/concepts.md` and `docs/getting-started.md` drop the `plusSeconds(1)` margin. The loan sample tests advance by exact timeouts, wait for the workflow's `TIMER_SCHEDULED` memo before advancing, and the v2 in-flight test delivers each signal only once the workflow is parked on it. **Test-code note:** a test that relied on `workflow.currentTime()` tracking the wall clock under `TestWorkflowEnvironment` now sees the test clock. Pinned by `TestWorkflowEnvironmentTest.advanceTimeByExactlyTheTimeoutFiresAnAwaitDeadline`/`advanceTimeByExactlyTheDurationFiresASleep`/`anAwaitDeadlineDoesNotExpireOnTheWallClock`/`aTimerArmedWhileAdvanceTimeRunsIsNotFiredByThatCall`, `SignalManagerTest.liveAwait_armsAndDecidesItsDeadlineOnTheInstantSource`/`defaultInstantSource_isTheSystemClock`, `WorkflowExecutorTest.defaultInstantSource_isTheSystemClock` and `MaestroAutoConfigurationConfigSeamsTest.autoConfiguredExecutorArmsDeadlinesOnTheSystemClock`.

### Fixed — a Valkey brownout no longer stalls `signal()` or turns client retries into duplicate signals (Issue 32)

- **Before:** `SignalManager.deliverSignal` saved the signal row and then called `SignalNotifier.publish` on the caller's thread, and `ValkeySignalNotifier.publish` was a synchronous `PUBLISH` bounded only by Lettuce's 60 s command timeout. While Valkey was unreachable every `WorkflowHandle.signal()` and `@MaestroSignalListener` delivery blocked for the outage although its signal was already durable; a caller with a shorter timeout retried and persisted a byte-identical duplicate row (nine in the triaged chaos soak, which exhausted the loan sample's `MAX_SIGNATURE_SIGNALS = 5` and failed the workflow).
- **Now:** the engine hands the notification to a virtual thread it never awaits, after the row is saved, so a slow notifier cannot hold any sender. `ValkeySignalNotifier.publish` is asynchronous and `PostgresSignalNotifier.publish` hands its `NOTIFY` off the same way; both log one `WARN` per outage (then `DEBUG`, then one `INFO` on recovery) instead of one per signal. A lost notification is covered by the parked await's `maestro.signal.wake-recheck-interval` store re-read, as before.
- **SPI:** `SignalNotifier.publish` (signature unchanged) is now documented to return promptly without waiting for the transport; delivery is best-effort. Custom notifiers that block should switch to an asynchronous send. Pinned by `SignalManagerTest.deliverSignalDoesNotWaitForABlockingNotifier`, `ValkeySignalNotifierTest.BrownoutTests`, `PostgresSignalNotifierTest.publishDoesNotWaitForAConnection` and `ValkeyBrownoutSignalDeliveryIT`.

### Added — maestro-bom platform

- New published platform `io.b2mash.maestro:maestro-bom` constrains `maestro-core`, `maestro-spring-boot-starter`, `maestro-store-jdbc`, `maestro-store-postgres`, `maestro-messaging-kafka`, `maestro-messaging-postgres`, `maestro-lock-valkey`, `maestro-lock-postgres`, `maestro-admin-client` and `maestro-test` to one version. Import it with `implementation(platform("io.b2mash.maestro:maestro-bom:<version>"))` and declare the modules without versions. README, CONTRIBUTING and getting-started now show the BOM and the real build/test commands (`:maestro-integration-tests:test` / `:e2eTest`, `:maestro-store-postgres:test`; there is no `integrationTest` task), and the README's competitive table was rewritten.

### Changed — WorkflowContext.nextSequence/setSequence/setReplaying are no longer public (F12)

- **Before:** the engine's sequence and replay mutators were `public` on the user-facing `WorkflowContext`, so they showed up in `workflow.` autocomplete where calling one silently corrupts replay (the only guard was a Javadoc "do not call").
- **Now:** all three are package-private. The engine reaches them through the new `io.b2mash.maestro.core.context.WorkflowContextInternals` (`nextSequence(ctx)`, `setSequence(ctx, n)`, `setReplaying(ctx, b)`), whose Javadoc states it is engine-internal. `currentSequence()`, `isReplaying()` and the identity accessors stay public. **Limitation:** this removes the methods from the workflow author's autocomplete but is not real encapsulation — `maestro-core` has no `module-info.java`, and the engine lives in other packages, so the accessor class must be public.
- **Upgrade:** third-party `WorkflowOperations` implementers (and tests) that called `ctx.nextSequence()` / `ctx.setSequence(n)` / `ctx.setReplaying(b)` switch to the `WorkflowContextInternals` static equivalents. Pinned by `WorkflowContextSurfaceTest`.

### Added — WorkflowHandle.status()/result()/terminate(); startAndWait failures carry the cause

- **Before (F11):** `WorkflowHandle` offered only `signal` and `query`, and `query` is answered only by the node running the workflow — so a caller had no way to read a workflow's status or result from an arbitrary node, or to terminate it, through the client. `WorkflowStub.startAndWait` on a failed or terminated workflow threw `WorkflowExecutionException` whose cause was a bare `RuntimeException("Workflow ended with status FAILED")`: the workflow's exception type, message and the termination reason were all discarded.
- **Now:** `WorkflowHandle<R>` (now a generic `final` class) adds `status()` (the durable `WorkflowStatus`; throws `WorkflowNotFoundException` for an unknown ID), `result()` (`Optional<R>` — present only when `COMPLETED` with a non-null output; empty while active, `FAILED` or `TERMINATED`) and `terminate(@Nullable String reason)` (delegates to `WorkflowExecutor.terminateWorkflow`, returning its `TerminateOutcome`). All three read or write the store, so they work from any node; only `query` needs the owning node. `MaestroClient.getWorkflow(id)` returns `WorkflowHandle<Object>`; the new `getWorkflow(id, Class<R>)` types `result()`. `startAndWait` on `FAILED` now throws `WorkflowExecutionException("Workflow '<id>' failed: <exceptionType>: <message>")` whose cause is the recorded exception rebuilt by `FailureReconstruction.reconstruct` (the original type when it has a public `(String)` constructor, otherwise `ReconstructedActivityFailure`); on `TERMINATED`, `"Workflow '<id>' was terminated by <terminatedBy>: <reason>"` with no cause.
- **API:** the failure and termination payloads stored as the instance output are now public records, `io.b2mash.maestro.core.model.WorkflowFailureDetail(exceptionType, message)` and `WorkflowTerminationDetail(reason, terminatedBy)`, replacing private records in `WorkflowExecutor`; their JSON shape is unchanged, so stored instances and histories read as before. New constructor `WorkflowExecutionException(String workflowId, String message, @Nullable Throwable cause)`. **Source note:** code that declared the raw type `WorkflowHandle` still compiles (with a raw-type warning); prefer `WorkflowHandle<?>` or `var`. Pinned by `MaestroClientTest.HandleTests` and `MaestroClientTest.StartTests`.

### Removed — the task-dispatch channel and the unread Postgres lifecycle queue (F8, F10)

- **Before:** `WorkflowMessaging` declared `publishTask(String, TaskMessage)` and `subscribe(String, Consumer<TaskMessage>)`, and both transports implemented them — but workflows start in-process on the node that calls `startWorkflow`, so nothing ever published a task and nothing ever subscribed. Every deployment was still told to configure `maestro.messaging.topics.tasks` and pre-create `maestro.tasks.*` topics (plus their `.DLT` companions) that carried no traffic. Separately, the Postgres transport appended every lifecycle event to `maestro_lifecycle_event_queue`, which nothing ever read (the admin dashboard consumes Kafka only), so the table only grew until the cleaner trimmed it.
- **Removed:** `WorkflowMessaging.publishTask` and `WorkflowMessaging.subscribe` — the SPI now has exactly `publishSignal`, `publishLifecycleEvent` and `subscribeSignals` — and the `TaskMessage` record; `KafkaMessagingConfig.tasksTopic` (the record loses its first component); `MaestroProperties.TopicsProperties.tasks` (now `TopicsProperties(signals, adminEvents)`); `PostgresWorkflowMessaging.listDeadLetterTasks` and `replayTask`; the `maestro.messaging.topics.tasks` property, which now **fails startup** whichever transport is in use — the check is in `MaestroAutoConfiguration`, so a user-supplied `KafkaMessagingConfig` does not bypass it (`"maestro.messaging.topics.tasks has been removed: Maestro no longer dispatches work over a task topic (workflows start in-process). Delete the property and the maestro.tasks.* topics."`); the `maestro.tasks.*` topics and their `.DLT` companions from every compose file, sample and doc; and the `maestro_task_queue` and `maestro_lifecycle_event_queue` tables, dropped by new messaging migration **`V202__drop_task_and_lifecycle_queues.sql`**. The Postgres transport's signal loop — claim-time attempt counting, the stale-claim retire sweep, redelivery and dead-lettering (Issue 27, below) — is unchanged; it is now the transport's only poll loop, and `PostgresMessageCleaner` only touches `maestro_signal_queue`.
- **Upgrade:** delete `maestro.messaging.topics.tasks` from every service's configuration, then delete the `maestro.tasks.*` and `maestro.tasks.*.DLT` topics at your leisure (nothing reads or writes them). `V202` runs automatically through `maestro_schema_history` — except on a database a 0.4.0 build migrated, where it is part of the one-time, stop-every-node upgrade procedure under "Maestro owns its schema migrations" below; any rows still in the two dropped tables were never consumed. **Breaking for custom `WorkflowMessaging` implementations and test fakes:** delete the two `@Override`s and the `TaskMessage` import. Pinned by `WorkflowMessagingSurfaceTest`, `MaestroAutoConfigurationRemovedPropertiesTest`, `KafkaMessagingAutoConfigurationAdminTopicAliasTest.tasksTopicPropertyFailsStartup*`, `PostgresMessagingRemovedTasksTopicTest` and `MaestroMigrationsCoexistIT`.

### Changed — the Postgres transport does not publish lifecycle events (F10)

- **Before:** `PostgresWorkflowMessaging.publishLifecycleEvent` wrote each event to `maestro_lifecycle_event_queue`, and `docs/admin.md` claimed the dashboard consumed that table.
- **Now:** it is a no-op that logs one `WARN` per messaging instance — `"Lifecycle events are not supported on the Postgres messaging transport — the admin dashboard consumes Kafka only. Set maestro.admin.events.enabled=false to silence this warning."` — and the dashboard cannot observe Postgres-only services. Set `maestro.admin.events.enabled: false` in those services. Pinned by `PostgresWorkflowMessagingTest.LifecycleEventTests.publishLifecycleEventIsANoOpThatWarnsOnce`.

### Added — maestro.worker.task-queues[].concurrency / activity-concurrency now bound execution

- **Before (F8 / Issue 25):** `maestro.worker.task-queues[]` bound without error and appeared in `/actuator/configprops`, but nothing read it — every workflow and activity ran with no queue-level limit, including under the documented minimal configuration.
- **Now:** a framework-free `TaskQueueGovernor` in `maestro-core` (one fair `Semaphore` pair per configured queue) enforces both limits per node. `concurrency` caps how many workflow runs of a queue execute at once: a run takes its slot when it starts executing, gives it back while parked on `sleep()`/`awaitSignal()` (each park chunk), reacquires one before resuming, and releases it at its terminal status. `activity-concurrency` caps live activity executions of the queue: a slot is held from the activity's dedup lock through every retry attempt and backoff to its persisted outcome; replayed (memoized) steps never take one. The queue is the one named by `@DurableWorkflow(taskQueue = ...)`. Queues not listed are unbounded, and limits are per node. *(Superseded in this release: unlisted queues are now bounded at 20/20 per node by default — see "Host load — bounded by default" above.)* A run still waiting for its workflow slot — or for an activity slot — when the node starts shutting down stands down without executing (the activity does not run) and stays recoverable. Startup fails on a queue named twice (`"maestro.worker.task-queues names a queue twice: 'orders'"`), an entry without a name, or a limit below 1.
- **API:** `TaskQueueLimits` and `TaskQueueGovernor` (`io.b2mash.maestro.core.engine`); a 13-argument `WorkflowExecutor` constructor taking `Map<String, TaskQueueLimits>` (the 12-argument one passes none) and `WorkflowExecutor.taskQueueGovernor()`; `ActivityProxyFactory.createProxy(..., EngineObserver, TaskQueueGovernor)` (shorter overloads are unbounded — direct core users who want `activity-concurrency` pass the executor's governor). `TaskQueueGovernor.acquireActivitySlot` returns whether it had to wait. **Behaviour note for `parallel()`:** a run's one slot is shared by its branches. It is given back by whichever branch parks while the run holds it and taken back by the first branch whose park chunk ends, so sibling branches still executing after a give are bounded only by their activity slots, and a run can hold its slot with no thread executing (a woken branch finished while a sibling stays parked) — for at most one park chunk (`maestro.signal.wake-recheck-interval`), because every chunk gives the slot again. A run never holds more than one slot, and no slot wait can deadlock on a parked run. On a saturated queue a parked run's periodic re-check waits for a slot, so cross-node terminate/missed-wake convergence is bounded by slot availability rather than the re-check interval; and an activity that waits for a workflow of its own queue (`startAndWait`, or polling its status) deadlocks until the wait times out once `concurrency` runs of that queue, or `activity-concurrency` such activities, are waiting at once. Since "Host load — bounded by default" above, unlisted queues are bounded at 20/20, so this no longer needs an explicit limit. Pinned by `TaskQueueGovernorTest`, `WorkflowExecutorConcurrencyTest`, `MaestroAutoConfigurationConfigSeamsTest.workerConcurrencyReachesTheGovernor` and `TaskQueueConcurrencyIT`.

### Changed — One workflow object per run

- **Before (F1):** the engine held one workflow object per workflow *type* — under the Spring starter, the `@DurableWorkflow` singleton bean — and every concurrent instance of that type ran its workflow method on that same object. Instance fields written by one run were visible to, and overwritten by, every other run, so a `@QueryMethod` returned whichever run wrote last rather than the run being queried, and a re-adopted run inherited the stale fields of the previous one.
- **Now:** `WorkflowRegistration` carries a `Supplier<Object> workflowFactory` in place of `Object workflowImpl`, and `WorkflowExecutor.startWorkflow`/`resumeWorkflow` take that factory instead of an object. The executor calls it exactly once per run — on `startWorkflow`, on every re-adoption by `recoverWorkflows`/`resumeWorkflow`, and on `retryWorkflow` — on the launching thread before the run's virtual thread exists. A `RuntimeException` from the factory surfaces to the caller as the new `WorkflowInstantiationException` (cause preserved; a `MaestroException`), the instance lock is released, and a fresh start emits no `WORKFLOW_STARTED` event or `workflowStarted` callback (both now fire only once the object exists); a row already written stays active for the recovery poller. `recoverWorkflows` catches only `WorkflowInstantiationException` per instance — instance skipped and logged at `ERROR`, with the stack trace only for the first instance of each type per pass; the pass then logs one `WARN` "Recovery pass skipped N instance(s) of M workflow type(s) whose factory threw: {type=count}" to alert on — so one workflow type whose objects cannot be built no longer starves every other type's recovery; any other exception still ends the pass (R44). The object it returns is held for the life of that run and is the receiver for every `@QueryMethod` invocation against it; it is never reused across runs. `TestWorkflowEnvironment` builds and wires a fresh object per run. **Documented limitation:** queries are answered only by the node whose executor currently holds the run (the instance-lock holder); a workflow parked, running or compensating on another node, or terminal, throws `WorkflowNotQueryableException` — cross-node query routing is out of scope. **Breaking for direct core users:** pass `() -> new MyWorkflow(...)` (or any per-run factory) where an object was passed before. A start whose factory throws leaves its instance row `RUNNING` (not rolled back): recovery runs it once the factory works, and retrying the start with the same workflow ID throws `WorkflowAlreadyExistsException`. Pinned by `WorkflowExecutorPerRunInstanceTest` and `MultiNodePerRunQueryIT`.
- **Spring starter:** scanned `@DurableWorkflow` classes are registered as **prototype** beans and each registration's factory is `getBean(beanName)`, so every run gets a new, fully wired object (dependency injection and `@ActivityStub` proxies; the proxies are built once per workflow class and shared). Discovery no longer instantiates workflow beans at startup, but it still validates every workflow class's `@ActivityStub` fields against the context (`ActivityStubBeanPostProcessor.prepare`), so a missing activity bean, an invalid stub field or an unparsable `startToCloseTimeout` still fails context startup (the timeout error now names the field and value). Only a genuine dependency-injection failure — a throwing constructor or an unresolvable `@Autowired` dependency — surfaces at the first run, as `WorkflowInstantiationException` (cause: the Spring `BeanCreationException`). **Breaking for workflow beans you declare yourself:** a `@DurableWorkflow` bean in any scope other than prototype (`@Component` or `@Bean` without `@Scope("prototype")`, or a request/session/custom scope) now fails context startup with `"@DurableWorkflow bean '…' (…) must be prototype-scoped …"` — remove `@Component` (the starter registers the class itself) or add `@Scope("prototype")`. Pinned by `WorkflowRegistrarPrototypeTest` and `MaestroClientTest.PerRunState`.

### Changed — Maestro owns its schema migrations

- **Before (F5, F9):** Maestro's migrations (`V1`–`V5`, `V100`, `V200`, `V201`) were shipped into `classpath:db/migration` — Spring Boot's default Flyway location — and applied by the *host application's* Flyway into the host's `flyway_schema_history`. A host's own `V1__init.sql` therefore collided with Maestro's `V1`, there was no way to relocate or switch off Maestro's DDL, and every service had to add `spring-boot-starter-flyway` just to get Maestro's tables (getting-started did not say so).
- **Now:** each Maestro Postgres module ships its SQL under its own location — `classpath:db/maestro/store` (versions 1–99), `classpath:db/maestro/lock` (100–199), `classpath:db/maestro/messaging` (200–299) — and advertises it in a `META-INF/maestro/schema-locations` resource. The new `MaestroSchemaInitializer` (`io.b2mash.maestro.store.postgres.schema`, created by `PostgresStoreAutoConfiguration` before the store bean, and ordered after the host's own Flyway/Liquibase via `@DependsOnDatabaseInitialization`) discovers every location and applies them with an embedded Flyway into Maestro's **own history table, `maestro_schema_history`**. The host's Flyway, its `spring.flyway.*` settings and its `flyway_schema_history` are never touched. On a schema that already holds the host's tables it baselines at version `0` (a marker row only) and applies every Maestro migration. Migration file contents are unchanged; only their location moved. (Superseded later in this release: the bands no longer apply. Every new migration, store or lock, takes the next version above the highest shipped, within 101–199, and 200–299 stays retired; see "Numbering rule" in `docs/configuration.md`.)
- **New property `maestro.store.schema.mode`** — `flyway` (default) applies pending migrations; `validate` applies nothing and fails startup naming the pending versions (or on a checksum mismatch); `none` does nothing — you own the DDL. Case-insensitive; an unknown value fails startup. See `docs/configuration.md` § Schema management.
- **Dependencies:** hosts need `spring-boot-starter-jdbc` (for the `DataSource`) and **no longer need `spring-boot-starter-flyway`** for Maestro — Flyway is a runtime dependency of `maestro-store-postgres`. The six sample services drop the Flyway starter; getting-started now lists `spring-boot-starter-jdbc`.
- **Upgrade — existing databases (one-time; do this before deploying).** A database migrated by an earlier build (0.4.0 applied `V1`–`V3`, `V100`, `V200` and `V201`) has Maestro's rows in your `flyway_schema_history` and no `maestro_schema_history`, so this build's first start fails loudly — a host whose own Flyway has migrations of its own fails first with `Detected applied migration not resolved locally`, since Maestro's scripts are no longer in `classpath:db/migration`; any other host (no Flyway, or a Flyway that resolves no migrations and so validates nothing) fails when Maestro's `V1` finds its tables already there. Upgrade with this procedure, in order (the same text is in `docs/configuration.md` § Schema management):

  1. **Stop every node** of every service that uses the database — no node running an earlier build may stay connected. This upgrade cannot roll: `V202` drops `maestro_task_queue` and `maestro_lifecycle_event_queue`, which an earlier build's Postgres transport still reads and writes, and an earlier node must not adopt workflows this build has run (`docs/operations.md` §10.1).
  2. **Either drop Maestro's tables or keep them** — pick one:
     - **Fresh start (discards every workflow, signal and timer).** Drop the tables and leave `maestro.store.schema.mode` at its default, `flyway`; the new build recreates the schema and records it in `maestro_schema_history`:

       ```sql
       DROP TABLE IF EXISTS maestro_workflow_signal, maestro_workflow_timer, maestro_workflow_event,
           maestro_workflow_instance, maestro_distributed_lock, maestro_leader_election,
           maestro_signal_queue, maestro_task_queue, maestro_lifecycle_event_queue;
       ```

     - **Keep the data.** Set `maestro.store.schema.mode: none` on every node — you own Maestro's DDL from then on, applying each later migration by hand before deploying the build that ships it — and apply by hand the three migrations a 0.4.0 database lacks: `V4__signal_trace_context.sql` and `V5__signal_received_at_default.sql` (`maestro-store-postgres`, `classpath:db/maestro/store`) and `V202__drop_task_and_lifecycle_queues.sql` (`maestro-messaging-postgres`, `classpath:db/maestro/messaging`). Run all three whichever modules you use; as written here they are safe to re-run, including on a database a pre-release build already took to V4 or V5:

       ```sql
       ALTER TABLE maestro_workflow_signal ADD COLUMN IF NOT EXISTS trace_context VARCHAR(128);  -- V4
       ALTER TABLE maestro_workflow_signal ALTER COLUMN received_at SET DEFAULT now();         -- V5
       DROP TABLE IF EXISTS maestro_task_queue;                                                 -- V202
       DROP TABLE IF EXISTS maestro_lifecycle_event_queue;                                      -- V202
       ```

       Do not skip V4 or V5: this build names `trace_context` in every signal insert and leaves `received_at` to the column default, so without them every `saveSignal` fails — inside the transport listener, so each signal is redelivered until it is dead-lettered.
  3. **Delete Maestro's rows from your `flyway_schema_history`** (or the table `spring.flyway.table` names) — on either path; skip this step only if the table does not exist. The statement matches each row by version **and** script name, so none of your own migrations can match, and rows your database never had are simply not matched:

     ```sql
     DELETE FROM flyway_schema_history
     WHERE (version, script) IN (
         ('1',   'V1__create_maestro_schema.sql'),
         ('2',   'V2__create_indexes.sql'),
         ('3',   'V3__timer_lookup_index.sql'),
         ('4',   'V4__signal_trace_context.sql'),
         ('5',   'V5__signal_received_at_default.sql'),
         ('100', 'V100__maestro_lock_postgres.sql'),
         ('200', 'V200__maestro_messaging_postgres.sql'),
         ('201', 'V201__redelivery_dead_letter.sql'));
     ```

     Without this, an application whose own Flyway has migrations fails startup with `Detected applied migration not resolved locally` — and one whose Flyway has none yet fails the day it adds its first.
  4. **Start the new build** on every node.

  A host that adds its own Flyway *later*, against a database that already holds Maestro's tables, needs `spring.flyway.baseline-on-migrate: true` for its first run. Pinned by `MaestroSchemaInitializerTest`, `PostgresStoreAutoConfigurationHostFlywayTest` and `MaestroMigrationsCoexistIT` (which runs both paths of this procedure against a simulated 0.4.0 database).

### Changed — Postgres transport counts an attempt at claim time and retires stale claims with no budget (Issue 27)

- **Before:** `maestro-messaging-postgres` incremented `attempts` only when it recorded a handler failure. If the status write after a handler — `COMPLETED`, the enabled-redelivery `PENDING`/`DEAD_LETTER`, or the disabled-redelivery `FAILED` — itself failed (database down), the row stayed `PROCESSING` and the five-minute stale reclaim re-invoked the handler with no bound: a second attempt despite `maestro.messaging.redelivery.enabled=false`'s "exactly one attempt", and, with the store failing every time, a loop.
- **Now:** both claim statements set `attempts = attempts + 1`, so the attempt is durable before the handler runs. A retire sweep, run before the claim in the same transaction in both poll loops, moves a stale `PROCESSING` row whose budget is spent (`attempts >= max-attempts`; `attempts >= 1` with redelivery disabled) straight to `DEAD_LETTER` — in both redelivery modes, because an "outcome unknown" row always needs triage and `FAILED` is purged by the cleaner and hidden from `listDeadLetter*` — with `last_error = "finalisation write lost after attempt <n>; outcome unknown — retired without re-invoking the handler"`, logged at `ERROR`. The sweep uses `FOR UPDATE SKIP LOCKED`, so concurrent sweeps on several nodes skip each other's rows instead of waiting or deadlocking, and the claim's stale-reclaim branch independently requires `attempts < max-attempts` (`< 1` disabled), so a row a sweep skipped is never re-invoked. A stale claim with budget left is still re-claimed and re-invoked — the documented double delivery under a double fault; handlers must be idempotent. The rule is the same for all three status-write sites. Retired rows are replayed deliberately (see `docs/configuration.md`, "Retired rows"). Pinned by `PostgresWorkflowMessagingTest.StatusWriteFailureTests`.
- **Behaviour change:** a consumer that crashes mid-handler now **consumes an attempt** (previously a crash did not count). This is deliberate: a poison message that crashes the JVM now dead-letters after `max-attempts` crashes instead of looping forever, and it is what makes at-most-once provable with redelivery disabled. `attempts` on `DEAD_LETTER`/`FAILED` rows and in `DeadLetterMessage` means the same as before for handler failures — the number of attempts made.

### Fixed — Valkey wake subscription is acknowledged before parking

- **Before:** `ValkeySignalNotifier.subscribe` used Lettuce's async pub/sub command, so it returned before the server had registered the subscription; a signal published in that window was missed until `maestro.signal.wake-recheck-interval`, making `SignalManager`'s check-then-subscribe race closure racy.
- **Now:** `subscribe` returns only once the server has acknowledged the subscription (bounded 5 s). The wait is deliberately not Lettuce's synchronous command (whose 60 s default timeout would stall every `awaitSignal` entry past the 30 s shutdown drain during a Valkey brownout): on a missing acknowledgement or a failed command the callback is removed, a `WARN` names the `maestro.signal.wake-recheck-interval` fallback, and the workflow parks normally — nothing is thrown into workflow code. `unsubscribe` removes the callback and issues the command without waiting. Concurrent waiters of one workflow on one node share the first waiter's acknowledged subscription (`SignalManager`): a later joiner waits on it, under the same bound, rather than parking before it is acknowledged. The remaining gap is a publish during a pub/sub disconnect (no replay on reconnect), covered by the recheck interval. Pinned by `ValkeySignalNotifierTest.publishImmediatelyAfterSubscribe_isReceived_everyTime` and `ValkeySignalNotifierAckTimeoutTest`.

### Added — signal janitor and database-assigned `received_at` (C14)

- **Before:** a signal row's `received_at` was the JVM clock of the node that ingested it, and `getUnconsumedSignals` orders by it — so two same-name signals delivered through different nodes could be consumed in clock-skew order rather than arrival order. Separately, unconsumed signals addressed to a workflow that had already finished were never purged, so the signal table grew without bound.
- **Now — clock:** new store migration **`V5__signal_received_at_default.sql`** gives `maestro_workflow_signal.received_at` a `DEFAULT now()`, and `AbstractJdbcWorkflowStore.saveSignal` omits the column, so the database clock assigns it and the caller's `WorkflowSignal.receivedAt()` is ignored. The `WorkflowStore.saveSignal` Javadoc now requires this of every shared-database store (the in-memory test store keeps the caller's value — one process, one clock). **Upgrade:** V5 is a metadata-only `ALTER`; a database managed outside Maestro (`maestro.store.schema.mode: none`) must apply it before this build is deployed, or the `NOT NULL` column rejects every signal insert — see the upgrade procedure above. Pinned by `PostgresWorkflowStoreTest.SignalClockTests`.
- **Now — janitor:** a framework-free `SignalJanitor` in `maestro-core`, started by `WorkflowExecutor.startSignalJanitor(interval, retention)` (the starter calls it from `StartupRecoveryRunner`) and stopped by `shutdown()`, runs on a virtual thread `maestro-signal-janitor` on every node — the purge is one idempotent statement, so there is no leader election. Each sweep deletes the unconsumed signals of workflows that reached `COMPLETED` or `TERMINATED` more than `maestro.signal.terminal-retention` (default `7d`) ago, every `maestro.signal.janitor-interval` (default `1h`). **`FAILED` workflows keep their signals** — `retryWorkflow` may still consume them (Issue 19); orphaned (pre-delivery) signals and consumed rows are never touched. A store error is logged at `WARN` and retried next interval. Pinned by `InMemoryWorkflowStoreTest`/`PostgresWorkflowStoreTest.SignalJanitorDeleteTests` (incl. `keepsSignalsOfFailedInstances`), `SignalJanitorTest`, `WorkflowExecutorTest.startSignalJanitor_exposesSettingsAndStopsOnShutdown`, `MaestroPropertiesBindingTest` and `MaestroAutoConfigurationConfigSeamsTest.signalJanitorSettingsReachTheExecutor`.
- **Breaking SPI addition for custom stores:** `WorkflowStore.deleteUnconsumedSignalsForTerminalInstancesBefore(Instant cutoff)` is **abstract, with no default**. Migration: one `DELETE ... WHERE consumed = FALSE AND workflow_instance_id IN (SELECT id FROM <instances> WHERE status IN ('COMPLETED','TERMINATED') AND completed_at < ?)` (see `AbstractJdbcWorkflowStore`); stores that extend `AbstractJdbcWorkflowStore` / `PostgresWorkflowStore` already have it.
- **Also (R34):** in `AbstractJdbcWorkflowStore.createInstance`/`completeInstance`, when a statement fails and the `rollback()` that follows also fails, the connection is now aborted instead of having auto-commit restored — `setAutoCommit(true)` on a still-open transaction would have committed the half-done write. Pinned by `AbstractJdbcWorkflowStoreUnitTest.FailedRollbackTests`.

### Changed — `awaitSignal` deadlines are durable timers (C12/F4)

- **Before:** `awaitSignal(name, type, timeout)` kept its deadline in memory (`Instant.now().plus(timeout)`). A crash, a redeploy or a recovery on another node re-armed the full timeout from scratch, so an await could outlive its deadline indefinitely across restarts; the timer poller never saw it, `cancelTimer` could not reach it, and `TestWorkflowEnvironment.advanceTime()` could not fire it, so a test of a 7-day approval timeout had to wait 7 days of wall clock or be restructured.
- **Now:** an await that has to park memoizes its deadline and persists it as a timer row. Event layout: a signal already waiting at entry is consumed at the await's slot `seq` exactly as before (`SIGNAL_RECEIVED`, no timer row); a zero or negative timeout with no signal records `SIGNAL_TIMEOUT` at `seq` and writes **no** timer row; an await that parks records `TIMER_SCHEDULED` at `seq` (step `$maestro:awaitSignal:<name>`, payload `{signalName, timerId, timeout, deadline}`), saves the row `await-signal-<seq>` with `fireAt = deadline` (row first, then the memo, like `sleep()`), and records its outcome — `SIGNAL_RECEIVED` or `SIGNAL_TIMEOUT` — at `seq+1`. Both slots are checked by the replay drift guard. The timer poller fires the row like any `sleep()` timer; `WorkflowExecutor.fireTimer`/`cancelTimer` wake the parked await, and a fired **or cancelled** row ends the wait with `SignalTimeoutException` — unless a signal is already visible, which always wins while the outcome slot is open (a consumed signal best-effort cancels the row). A replay resumes from the memoized deadline and the existing row, never from `Instant.now()` and never with a second row; an await past its deadline whose row is still `PENDING` fires the row itself. `TestWorkflowEnvironment.advanceTime()` now drives these deadlines, and advancing by exactly the timeout fires them — the deadline is armed from the test clock (see the R59 entry above). Pinned by nine `SignalManagerTest` cases and `TestWorkflowEnvironmentTest.advanceTimeFiresAwaitSignalTimeout`/`signalBeforeTheAdvancedDeadlineWins`.
- **Hardening (plan 04 final wave):** a store error inside the timeout decision no longer fails the run — the deadline self-fire is best-effort (like the cancel after a consumed signal), and a failed post-deadline signal re-read parks one more `wake-recheck-interval` and decides again, so a DB blip at the deadline can no longer record `WORKFLOW_FAILED` and run compensations for work that never failed (R39; `AwaitDeadlineStoreErrorTest`). The live path reuses an existing row for its `await-signal-<seq>` id and that row's `fireAt` instead of writing a second row under the same id (a crash between the row save and the memo, or a runner race — M2). A replay whose deadline row is **missing** no longer times out at once: it re-arms the row at the memoized deadline and keeps waiting (a missing row is not a spent deadline). `fireTimer`/`cancelTimer` no longer store a stray `:timer:` wake permit for `await-signal-*` rows (nobody parks there). Concurrent waiters of one workflow on one node share the first waiter's wake subscription and wait — bounded 5 s — for it to settle before parking.
- **Retry after a timeout (corrected by R61, above):** retrying a workflow that `FAILED` because an await timed out (Issue 19) removes that await's `SIGNAL_TIMEOUT` memo, its `TIMER_SCHEDULED` memo and its deadline row, so the retried await waits the **full timeout again**, armed from the retry; a signal that arrived meanwhile is consumed at once. (As first shipped on this branch, the `TIMER_SCHEDULED` memo survived and the retried await timed out again immediately unless the signal was already there — that was the R61 regression.)
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
- **Also:** `parallel()` now rethrows a `DuplicateEventException` or `StaleRunException` from any branch ahead of an ordinary failure in an earlier branch (after control-flow errors), so a stale run stands down instead of being recorded FAILED and compensated. Parallel saga compensation ranks its branches' outcomes the same way: a control-flow signal in any branch first, then a stale-run signal, then index order (previously a stale-run in an earlier branch outranked a shutdown in a later one). Both forks — `parallel()` and parallel compensation — now rank a node-level error (`NodeErrors.isNodeError`) second, after control-flow signals and ahead of stale-run signals, rethrown raw: the run is abandoned as `NODE_ERROR` instead of an earlier branch's plain failure being recorded `FAILED` (or `COMPENSATION_STEP_FAILED`) and compensated. Issue 34 adds a fourth rank: a store fault (`StoreUnavailableException`, or any branch error once the run is latched `STORE_UNAVAILABLE`) comes after stale-run signals and ahead of index order, so the run is abandoned as `STORE_UNAVAILABLE`.

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
in `docs/configuration.md` § Valkey Connection Resolution (section removed with the Valkey module in 2026-11).

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
  serves traffic — same as `V1`–`V3`. *(Superseded for a database a 0.4.0 build
  migrated: this release also moves Maestro to its own history table, and
  that database is upgraded once by the procedure under "Maestro owns its
  schema migrations" above — on its keep-the-data path `V4` is one of the
  three migrations you apply by hand.)*
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
  a blind optimisation. *(Historical, as written for 0.3.0. Since the
  unreleased "Host load — bounded by default" entry, renewal is batched — one
  statement per 500 held locks — and the recovery scan is paged and scoped to
  the node's own service; see Issue 12's status note.)*

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
