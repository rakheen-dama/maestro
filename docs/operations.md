# Maestro — Multi-Instance Operations Guide

**Audience:** an operator running more than one instance of a service that
embeds Maestro — the normal production topology, not an edge case.
**Status date:** 2026-08-01, after the multi-instance verification cycle
(`.superpowers/sdd/multi-instance/`).

## Why this document exists, and why it's separate from the architecture doc

`docs/maestro-architecture.md` describes what the engine is *designed* to
guarantee — the mermaid diagrams, the state machine, the SPI contracts. This
document is narrower and more operational: what happens, **with measured
numbers**, when you actually run two-or-more instances of a service and kill,
restart, or partition them. Every bound below is traceable to a specific
evidence file, not derived from reading the source. Growing
`maestro-architecture.md` with a wall of "observed 43-50s" tables would bury
its design-reference purpose under operational detail that changes as the
engine changes; keeping it here means an operator tuning a Kubernetes
`terminationGracePeriodSeconds` or deciding whether to page on-call for a
`PAUSE_RESUME`-shaped incident has one place to look, and a contributor
reading the architecture doc isn't wading through timing tables to understand
the design. Cross-links run both ways (see `maestro-architecture.md` §14
"Failure Modes").

**Read this as "what we observed under CI conditions," not as an SLA.**
Every number here comes from a specific machine, a specific run, a specific
workload (the `sample-loan-origination` demo). They are useful as *shape* —
which term dominates a bound, whether two backends behave equivalently — not
as guarantees for your production hardware, network, or workload.

**What is supported at all** — one service per set of Maestro tables, a
mandatory `DistributedLock`, Postgres as the only store, rolling upgrades from
the previous release only, and which Postgres, pool and Kafka setups are
tested — is stated in [`docs/support-matrix.md`](support-matrix.md). This
guide assumes a supported setup.

---

## 1. Owner-kill → peer adoption (`kill -9`, no restart)

**Scenario:** a node holding a workflow's instance lock is killed outright
(`kill -9`, no graceful shutdown) and never restarted. A peer node — same
`maestro.service-name`, same store, same lock namespace — must adopt the
workflow via the recovery poller once the lock's TTL lapses, and complete it
alone.

| Lock backend | Observed adoption latency | Bound | Source |
|---|---|---|---|
| Postgres | 45s, 43s, 50s (3 consecutive runs) | 150s (30s lock TTL + 60s recovery poll-interval + slack) | `.superpowers/sdd/task-5-report.md` §"3× consecutive green runs on postgres"; `evidence/task5/postgres-run{1,2,3}-s7-8.log`, `backend-timings.md` |

**What dominates the bound.** Adoption latency is
**recovery-poll-interval-dominated, not lock-aging-dominated**. The instance
lock ages by the `expires_at < now()` predicate in `PostgresDistributedLock`'s
`ON CONFLICT` clause — "the lock becomes takeable once its TTL has lapsed" —
so the spread (43-50s) reflects where the kill happened to land in the peer's
60s `maestro.recovery.poll-interval` cycle.

**Operational takeaway.** If you need faster adoption after a hard node
death, lower `maestro.recovery.poll-interval` (default 60s) — that is the
term that dominates, not the lock TTL.

---

## 2. Rolling restart (graceful deploy)

**Scenario:** a node is sent `SIGTERM` (not killed) while it owns workflows
parked in `WAITING_SIGNAL`, with a peer node up throughout. Graceful shutdown
(Issues 4/5's semantics: `ExecutorShutdownException` extends `Error`, so an
ordinary `catch (Exception)` around `awaitSignal()`/`sleep()` can't swallow
it) must leave every parked workflow `WAITING_SIGNAL` — never `FAILED`, never
compensated — and the peer must be able to read/signal them while the node is
down.

| Lock backend | Observed scenario duration (3 runs) | Zero FAILED? | Zero compensation? |
|---|---|---|---|
| Postgres | 38s, 37s, 41s | Yes, all 3 runs | Yes, all 3 runs |

Source: `.superpowers/sdd/task-3-report.md` §"Scenario 8, 3 consecutive
runs"; `.superpowers/sdd/task-5-report.md` §"3× consecutive green runs on
postgres"; `evidence/task3/run{1,2,3}-scenario8.log`,
`evidence/task5/postgres-run{1,2,3}-s7-8.log`.

**Why deploy is faster than owner-kill.** Graceful shutdown's `finally`
block releases the instance lock immediately
(`WorkflowExecutor.executeWorkflow`, per-workflow, on
`ExecutorShutdownException`) instead of leaving it to expire via TTL — so the
restarted node's recovery poller, whose first pass runs as soon as it starts
(not after the 60 s cadence), on its own thread, resumes all three workflows
in roughly **2 seconds** (traced, not re-measured since this change), not the
TTL/poll-interval-bound tens of seconds a hard kill requires. This is the practical argument for graceful shutdown over
`kill -9` wherever your platform gives you the choice (`.superpowers/sdd/
task-3-report.md` §"Decisions and justifications"). Since WP5 this includes
runs that were queued for a task-queue slot; before, they kept their locks
until a slot freed or the lock TTL ran out. See
[§15](#15-shutdown-one-deadline-and-the-grace-period) for the whole shutdown
sequence and the grace-period arithmetic.

**A restarted fleet shares its recovery backlog.** A node's recovery poller
locks only the runs its task queues can run or queue — per queue, its free
slots plus `concurrency` queued runs — and leaves the rest unlocked for its
peers, then takes more as its own runs finish. Before this release the first
node to start locked every orphan it could, and its peers, finding them all
locked, adopted nothing while it worked through the backlog at its own queue
bound. Two nodes now execute one backlog together
(`MultiNodeRecoveryCapacityIT`: a 12-run backlog at `concurrency` 2, each
node locking 4 and both executing runs). Startup no longer waits for the
recovery pass: the starter's `ApplicationRunner` returns while the first pass
is still reading (`StartupRecoveryRunnerTest`), so a node reports ready
before every orphan is re-adopted (traced, not tested: Spring Boot publishes
readiness after its runners return).

**With `maestro.recovery.enabled=false`** there is no recovery poller: one
startup pass runs on its own thread (`maestro-startup-recovery-<service>`),
ungated — it adopts every orphan it can lock, and the runs beyond a queue's
bound wait for their slots holding their locks, as every pass did before
this release. Nothing adopts orphans after it, so a node that dies later
strands its workflows until a node restarts (traced, not tested). Since pilot
readiness P3 the poller also runs *deferred starts* (a start whose instance
lock could not be taken), so without it such a start is **refused** with
`LockAcquisitionException`, persisting nothing (`MandatoryInstanceLockTest`),
and a resume this pass skips because the lock backend failed waits until a
node starts with recovery (its `INFO` line says so; a resume skipped because
the lock is held elsewhere is that holder's to run). A pass that fails (a store
outage) is logged — one `ERROR`, then an `ERROR` reminder every 5 minutes —
and retried every `maestro.recovery.poll-interval`, **with no limit**, until
one pass completes (`OneShotRecoveryTest`). A store fault there is not
counted on any meter (`maestro.loop.errors` counts only `Error`s, as
`recovery-poller`), and no health detail shows the pass: watch for the log
line `Maestro startup recovery (recovery poller disabled) adopted …` (Issue 73).

**Coverage boundary, stated honestly.** Scenario 8 drives three workflows
into three distinct `WAITING_SIGNAL` states (fan-in await, decision await,
signature await) — it does **not** achieve a `WAITING_TIMER` state on the
node being restarted. The coordinator ratified this explicitly during the
cycle (`.superpowers/sdd/progress.md`, Task 3 entry): *"`awaitSignal(timeout)`
does NOT persist a durable timer row... the brief was wrong; scenario covers
3 distinct WAITING_SIGNAL states; WAITING_TIMER deploy-safety is covered by
engine ITs + the Task 7 chaos harness ROLLING_RESTART action on verification
nodes."* That quote predates the durable `awaitSignal` deadline (C12/F4):
since then a parked `awaitSignal(timeout)` **does** persist a durable timer
row (`await-signal-<seq>`), but the await still parks as `WAITING_SIGNAL`, so
the scenario's coverage boundary is unchanged — it still reaches no
`WAITING_TIMER` state. If you need evidence specifically for "a workflow parked in
`sleep()` survives a graceful restart," look at
`WorkflowExecutorShutdownTest`/`ShutdownContractIT` (engine-level) and the
chaos harness's `ROLLING_RESTART` action against `verification-gateway`
nodes (which do run genuine `sleep()`-backed workflows), not scenario 8.

---

## 3. Timer-leader failover

**Scenario:** two instances of a service run `workflow.sleep()`-backed
workflows; only one is the elected `TimerPoller` leader
(`maestro:leader:timer-poller:{service}`, 15s TTL). The elected leader is
`kill -9`'d while a workflow *not owned by the leader* is mid-sleep on the
surviving node. The durable timer must still fire and the workflow must
progress, bounded by leader-TTL + poll-interval + wake-recheck. The leader's
own clock does not decide when a timer fires: due timers are compared with the
database clock (§13 has the skew check).

| Lock backend | Observed failover latency | Bound |
|---|---|---|
| Postgres | 14s, 16s, 15s (3 runs) | 60s (15s leader TTL + 5s poll + 5s wake-recheck + slack) |

Source: `.superpowers/sdd/task-4-report.md` §"3× consecutive green runs";
`.superpowers/sdd/task-5-report.md` §"3× consecutive green runs on
postgres"; `backend-timings.md`. Same 15s leader TTL governs both backends
(`TimerPoller.LEADER_TTL` is backend-agnostic); equivalent within noise.

This scenario is the end-to-end proof of Issue 17's fix (§6 below):
`sleep()` no longer parks indefinitely on a node that isn't the timer-poller
leader — it rechecks the durable timer row every wake-recheck interval, so a
remote leader's fire is noticed within one interval instead of never.

---

## 4. Cross-node admin retry/terminate

**Scenario:** a workflow is driven to `FAILED` (no saga compensation) or left
`WAITING_SIGNAL` while owned by node A; node A is `kill -9`'d and never
restarted; `$maestro:retry` / `$maestro:terminate` are published (raw Kafka,
wire-identical to `AdminCommandService`) with only node B alive.

**Node-agnostic by design.** Both commands are ordinary signals routed
through the shared store — any live node consuming the service's signal
topic can execute them; nothing in the protocol depends on which node
originally owned the workflow. `WORKFLOW_RETRIED`/`WORKFLOW_TERMINATED`
lifecycle events land on the admin-events topic in every run.

| Lock backend | Observed admin-command convergence (3 runs) | Kill→first-command latency (worst case) |
|---|---|---|
| Postgres | 43s, 0s, 42s | ~46s observed (`.superpowers/sdd/task-4-report.md` pitfall #5, kill `20:48:42` → retry executed `20:49:28`) |

Bound: `WAIT_ADMIN_COMMAND_SECS=90`. Source:
`.superpowers/sdd/task-4-report.md`, `.superpowers/sdd/task-5-report.md`
§"Admin retry convergence"; `backend-timings.md`.

**What actually dominates this bound — not the lock backend.** The first
command published to a partition the dead node owned isn't delivered to the
survivor until the Kafka consumer-group coordinator evicts the dead node's
session and reassigns its partitions — bounded by Kafka's default
`session.timeout.ms` (~45s, unconfigured by this sample). Every command
*after* that first rebalance is near-instant (run 2's `0s` is the
already-rebalanced case). The lock backend is not in this path at all — if you need
faster admin-command delivery after a hard node death, tune Kafka's
`session.timeout.ms`, not anything in Maestro.

**What a Retry deletes.** A Retry deletes the failure that ended the run —
the one the exception escaping `@WorkflowMethod` carries, as itself, a cause
or a suppressed exception (recorded in `WORKFLOW_FAILED` as
`failedSequences`) — together with everything the run recorded after it
along its path, the whole subtree of any `parallel()` started there, and the
`WORKFLOW_FAILED`; a failure the workflow caught and recovered from, a
completed sibling branch, and a second branch's independent failure are kept
(the run then fails again naming that branch, and a second Retry clears it).
For a failure recorded by a build before this one (no `failedSequences`),
the one failure memo whose rebuilt exception message equals the recorded
message is treated the same way. When nothing is attributed (an exception
thrown without the activity's exception as its cause), only an
`ACTIVITY_FAILED` — or, when a `SignalTimeoutException` escaped, a
`SIGNAL_TIMEOUT` — after which nothing but `WORKFLOW_FAILED` was recorded is
deleted, so a caught gate timeout survives. When the history has a slot
Maestro cannot place (a slot consumed without a memo and followed by more
work, or two lanes claiming one slot), Retry deletes what it always did:
every `ACTIVITY_FAILED`, the `WORKFLOW_FAILED` and, for an await timeout, the
highest `SIGNAL_TIMEOUT` with its park memo. Every deleted timer slot takes
its timer row, of any status. In the shipped JDBC/Postgres store and the
in-memory test store, the deletes and the `FAILED → RUNNING` compare-and-set
are one transaction (`PostgresReopenFailedInstanceTest`,
`InMemoryWorkflowStoreTest`). A third-party store that inherits the SPI
default `WorkflowStore.reopenFailedInstance` is not atomic and does not apply
this plan: the default ignores both delete sets and runs the old two-step
Retry, `deleteFailureEvents` then `updateInstance`, as separate operations
(`WorkflowStoreReopenDefaultTest`; Issue 62). The relaunch bumps the lease
epoch through the normal adoption. If the deleted region holds a consumed signal
(`SIGNAL_RECEIVED`) — the failure's handling awaited one — Retry writes
nothing and returns `SIGNAL_CONSUMED_AFTER_FAILURE`, and the engine logs a
`WARN` naming the workflow, the signal, its sequence and the remedy
(`AdminCommandDispatcherRefusalTest` asserts the line): the signal cannot be
given back exactly and is not redelivered, so terminate the workflow or
finish its business outcome by hand. Over Kafka the admin command is then
acknowledged like every other no-op outcome — its offset is committed and
nothing reaches the `.DLT` — and the instance row, its events, its signal
row and its timer rows are unchanged
(`AdminCommandKafkaIT.retry_refusedForAConsumedSignal_isAcknowledgedAndWritesNothing`).
The dashboard still reports "Retry command sent" and the row stays `FAILED`;
no refusal reaches the operator there (Issue 63). A `$maestro:retry`
redelivered after its reopen committed does nothing
(`AdminCommandKafkaIT.retry_redeliveredAfterACommittedReopen_doesNothing`).
Tests: `AdminRetryTest`, `RetryPlannerTest`, `AdminCommandDispatcherRefusalTest`,
`AdminCommandKafkaIT`, `SeededHistoryUpgradeIT.theFailedRunsCbf5a2aRecordedAreRetriedByThisBuild`.

The rest of this paragraph is traced, not tested. A saga whose `compensate()` met
`SequenceSpaceExhaustedException` is `FAILED` with no
`COMPENSATION_STARTED`, so Retry relaunches it, exactly as before this
change. With a host connection pool at `REPEATABLE READ` or `SERIALIZABLE`, a
Retry that loses to a concurrent one surfaces as `StoreUnavailableException`
(SQLSTATE `40001`) instead of `OptimisticLockException`; nothing is deleted
either way, and the command is redelivered. On every engine path nothing of
the failed run can still write once `FAILED` is committed: it wrote
`FAILED` through the fenced terminal write, after every `parallel()` branch
had joined. Two cases outside the engine's own paths can append until the
relaunch's adoption fences them — a foreign `Thread.interrupt()` of a
`parallel()` join, and an abandoned activity attempt that calls through its
workflow context; the retried run then replays that event or stands down,
and never records a failure for it. If a reopen commits but the command
handler then throws (so Kafka redelivers the command) and the retried run
fails again before the redelivery arrives, the redelivery retries it again:
an admin command is not deduplicated by its message id, only by the row's
status. A retry whose relaunch finds the row already terminated, or loses
the adoption compare-and-set to another node, also returns
`LOCK_HELD_ELSEWHERE`.

**A Retry that lands as the failed run finishes.** A run commits `FAILED`
(and its `WORKFLOW_FAILED`) first, then reports `workflowFailed` to the
observer, hands the lifecycle event to its publisher and only in its
`finally` leaves the node's local running registry and then releases its
instance lock. A Retry on the same node in the gap before the registry
removal still finds the row `FAILED`: it commits the reopen (the planned
deletes and `RUNNING`), then finds the run registered locally, launches
nothing and returns `ALREADY_RUNNING_LOCALLY`. The row is then `RUNNING`
with no run anywhere until a recovery pass adopts it; that pass re-runs the
deleted failure and completes
(`AdminRetryTest.aRetryBeforeTheFailedRunLeavesThisNode_reopensTheRowAndRecoveryRelaunchesIt`).
The rest of this paragraph is traced, not tested. `AdminCommandDispatcher`
logs that outcome as a `WARN` no-op and acknowledges the command. With a
lock backend, a Retry after the registry removal but before the lock release
returns `LOCK_HELD_ELSEWHERE` with the same end state. The first recovery
pass on any node once the failed run has left the registry (and, with a lock
backend, released its lock) adopts it, so the retried run starts within one
`maestro.recovery.poll-interval` (default 60 s) of the failed run's end; no
`WORKFLOW_RETRIED` lifecycle event is published for it. The build before
this change behaved the same way: it deleted the failure memos and wrote
`RUNNING` before the same local check. The check is not moved ahead of the
reopen because the dispatcher acknowledges `ALREADY_RUNNING_LOCALLY`: a
Retry refused before writing would leave the workflow `FAILED` and the
operator's Retry lost.

**Retrying an await-timeout failure.** The E2E's retry target fails on an
uncaught `awaitSignal` timeout and the harness sends the missing signal only
*after* the retry. That works because a retry waits the full timeout again,
counted from the retry: it deletes the failing await's memo, outcome and row
(its `TIMER_SCHEDULED` memo, its `SIGNAL_TIMEOUT` and its
`await-signal-<seq>` row; attributed, Issue 33 — the await whose timeout
propagated, not the latest one) (R61). A signal sent before the retry is
consumed at once. The timeout itself is whatever the run memoized — in the
loan sample, node A's `PT8S` override (F4) — not the retrying node's
configuration.

---

## 5. Cross-node timer/signal wake bounds (Issue 17)

Before this cycle, a timer fired by a remote `TimerPoller` leader **never**
woke a `sleep()`-parked workflow on a different node — a routine-operation,
silent, permanent stall in any multi-instance deployment (`docs/open-
issues.md` Issue 17). Fixed: `sleep()` now parks in
`maestro.signal.wake-recheck-interval` chunks (default 30s at the engine
level — same property `SignalManager.awaitSignal` already used for signals)
and re-reads the durable timer row on every chunk expiry. A remote fire,
cancel, or terminate is noticed within one interval; a local fire still
unparks instantly (the fast path is unchanged).

**Sample configuration note.** `sample-loan-origination`'s three services set
`maestro.signal.wake-recheck-interval: 5s` (the engine default stays 30s) —
tightened specifically so the sample's multi-instance timing doesn't look
artificially slower than single-node for its short (2-8s) simulated
verification latencies. This is a sample choice, not an engine default;
choose your own interval as a latency/overhead trade-off (a shorter interval
means more parked workflows re-reading one indexed row more often).

**Signal wake is instant; the recheck is the fallback.** Cross-node *signal*
wake does not ride the recheck interval: `maestro-store-postgres` ships
`PostgresSignalNotifier` + `PostgresWakeListener`, which use Postgres
`LISTEN/NOTIFY` on one channel per service (auto-configured whenever the engine
is active; `maestro.signal.wake-notify.enabled=false` turns it off, which you
want behind PgBouncer in transaction-pooling mode). A signal ingested on node A
for a workflow parked on node B wakes B as soon as the `NOTIFY` lands — the
recheck interval only bounds the cases where that cannot happen: the wake
disabled, the listener's connection down (it reconnects with 1 s → 30 s backoff
and holds one `DataSource` connection for the application's lifetime), or a
notification lost in between. The notification itself is best-effort and
bounded: `signal()` returns once the row is saved and never waits for the
notifier — the `NOTIFY` runs on a virtual thread after the commit — so a
database outage delays the remote wake by at most one recheck interval instead
of stalling the sender (Issue 32). During an outage, pending `NOTIFY` threads
back up in proportion to the signal rate, up to *rate × the pool's
`connectionTimeout`* (Hikari default 30 s); each node keeps at most 1,000
notification hand-offs in flight and drops the excess, logged at `DEBUG`. A
dropped or failed notification costs latency (one recheck interval), never a
signal. While failures persist, the outage `WARN` repeats every 5 minutes. The
cross-node *timer* wake described above has no notify path and always rides
the recheck interval. A wake that reaches a node not running the workflow (a
Kafka-consumed signal, a timer the leader fires for a workflow owned elsewhere)
stores nothing there: the durable row is the wake, and the owner is woken by
`NOTIFY` or its re-check.

---

## 6. Split-brain behaviour (Issue 11 + Issue 18)

**The stance: the store fences a stale node's writes.** Maestro implements
lock fencing with a lease epoch on the instance row (`docs/open-issues.md`
Issue 11, resolved; `db/maestro/store` V6). A node that loses its instance
lock — a GC pause longer than the TTL, `docker pause`, a partition — may
keep running, but it cannot persist progress once a peer has adopted the
workflow: the adoption bumps `maestro_workflow_instance.lease_epoch` under
the row's version CAS, every write a run makes on its own behalf carries the
epoch it adopted at, and the store rejects a write whose epoch has been
superseded (`FencedWriteException`, zero rows written, the check and the
write one atomic statement under `FOR SHARE`). The fenced run stands down as
`lease_fenced` — no failure recorded, no compensation run, the adopter's
durable state governs. **State correctness no longer depends on the lock
being honoured**, and it no longer depends on the unique
`(workflow_instance_id, sequence_number)` event index either — that index
stays as depth beneath the fence. What the fence does *not* bound is the
activity already executing on the stale node at the instant of adoption: it
runs to completion there (its result is then fenced) and the adopter runs it
again, so duplicate *external side effects* (a payment API called twice)
remain possible for exactly that one step — activities must be idempotent.

**How the fence surfaces.** The stale node logs one WARN per fenced write
(`... was fenced — another node has adopted this workflow; standing this run
down (lease epoch 0 superseded at appendEvent)`) and one stand-down
(`stood down (LEASE_FENCED): ...`), and `maestro.standdown{reason=lease_fenced}`
increments once. If the node's lock renewer noticed the loss first, the run
is marked stale proactively and stands down at its next stop point as
`stale_run` with detail `instance lock lost` instead — same outcome, earlier.
Pinned by `MultiNodeFencingIT` (a node with a real, expired Postgres lock
resumes into the adopter's run: exactly one `LEASE_FENCED`, sequence 2 still
empty at that moment, the adopter completes alone) and
`EnginePostgresDualExecutionIT` (two nodes sharing no lock, the lost-lock
window made permanent: the loser is fenced at its first write).

**No lock backend.** Not supported, and refused: since pilot readiness P3 a
node without a `DistributedLock` fails startup, and a run whose instance-lock
acquire fails does not start (`docs/support-matrix.md`). The suite that
characterised lock-less nodes (`MultiNodeNoLockBackendIT`) was removed with the
mode.

**What changed in the earlier cycle: the loser loses fast and clean.** Before
Issue 18 was fixed, a stale node's post-thaw event-append collision
(`DuplicateEventException`) was misrecorded as *the workflow failing* — a
workflow that had **succeeded** on the winner was durably marked `FAILED`
and its saga compensations ran, reversing completed work. That's fixed: the
same collision now stands the stale run down immediately (no write, no
compensation, the winner's outcome governs), mirroring the shutdown/
termination control-flow pattern. Since C5 the loser also stands down when a
fresh read at a status write, a signal wake, the `COMPENSATING` transition or a
sequential compensation entry finds the row already finalised by the winner
(`StaleRunException`), and a run whose stand-down workflow code swallowed is
stopped at its next stop point (live activity, `sleep()`, `awaitSignal()`,
terminal write) — all reported as `stale_run`. See
`docs/open-issues.md` Issue 18 for the full defect and fix.

**Measured consequence.** Under the chaos harness's mandated split-brain
trigger (≥2 loan-node `PAUSE_RESUME` actions — freeze a real container past
the 30s lock TTL, then resume it — per 10-minute PR-gate run), the three
consecutive PR-gate streak runs that are the gate of record measured **0
duplicate side effects across 211 workflows** (74 + 75 + 62). Full detail,
the "why zero" explanation, and the honest caveats (short windows, one
workload) live in `docs/open-issues.md` Issue 11's "Measured evidence"
subsection — this section is a pointer, not a duplicate, so the numbers stay
in one place. The multi-hour soak data point has since landed: **0 duplicate
side effects across a further 2,376 workflows** in a 120-minute soak chaos
window (run `20260801-214325--6973268155056049009`), taking the measured
total to 0/2,587. Full numbers and the run's provenance caveats (a pre-fix
PR-gate `@Timeout` collision in the same JVM, leaked-checker console noise,
binary-vs-stamp commit skew) live in that same Issue 11 subsection.

**Practical guidance, unchanged:** activities must still be idempotent. The
fence makes the *persisted-state* guarantee unconditional; the measured
0/2,587 duplicate side effects says the remaining *side-effect* window (the
one step in flight at adoption) is rare under the measured conditions, not
that it is impossible — do not remove idempotency handling on the strength
of either. The chaos harness's `SideEffectCensus` tolerances are unchanged
this cycle; tighten them after one green soak on a fenced build.

---

## 7. Lock backend

Maestro has one lock backend: the table-backed `PostgresDistributedLock` in
`maestro-store-postgres` (`maestro_distributed_lock`, `maestro_leader_election`;
`maestro.lock.type` accepts only `postgres`). It is **mandatory**: a node
without a `DistributedLock` fails startup, and a run whose instance-lock
acquire fails (the lock is held elsewhere, or the lock backend threw) does not
start — a fresh start is persisted `RUNNING` for the recovery poller, a resume
is skipped until a later pass can take the lock (pilot readiness P3). It is verified across the full
multi-node scenario set above (owner-kill adoption, rolling restart,
timer-leader failover, cross-node admin commands), 3 consecutive green runs,
on `sample-loan-origination`'s E2E script.

| Dimension | Postgres |
|---|---|
| Adoption latency (§1) | 43-50s |
| Rolling-restart total (§2) | 37-41s |
| Timer-leader failover (§3) | 14-16s |
| Admin-command convergence (§4) | 0-43s |
| Cross-node signal wake | Instant (`LISTEN/NOTIFY`, one channel per service; §5) |
| Cross-node timer wake | ≤ `maestro.signal.wake-recheck-interval` (§5) |
| Extra infra dependency | None (reuses the service's Postgres) |

Adoption and leader failover are dominated by poll intervals, not lock
latency; admin-command convergence is Kafka-session-timeout-bound. A lock
cleaner on every node deletes rows expired for more than a minute every
`maestro.lock.cleanup-interval` (default 10m) — expired rows are already
takeable, so the cleaner only bounds table growth.

The lock is the *admission* guard only. The guarantee that a node which lost
its lock cannot persist progress does not live in this table: it is the lease
epoch on the instance row (§6), which every adoption bumps and every
run-scoped write is validated against. The lock's TTL therefore bounds how
long a dead owner blocks adoption, not how much damage a live-but-stale owner
can do.

*History:* a Valkey/Redis lock module (`maestro-lock-valkey`, the former
default, selected with `E2E_LOCK_BACKEND`) was measured alongside Postgres in
`.superpowers/sdd/multi-instance/evidence/task5/backend-timings.md` and found
equivalent within noise on every dimension except signal wake, where only
Valkey was instant. It was removed in 2026-11 once the Postgres path gained the
`LISTEN/NOTIFY` wake; see `docs/release-notes.md`.

---

## 8. The multi-instance chaos/soak harness

Beyond the loan-origination E2E's scripted scenarios (§1-4 above, deliberate
single-fault scenarios), a second, complementary harness drives a real
six-node cluster (2 instances each of loan-application, verification-gateway,
underwriting) under continuous scripted chaos — pause/resume, partition,
backend outages, rolling restarts — while a seeded workload runs and store-
level invariants are checked continuously.

**What it checks (I1-I5, `chaos-harness-design.md` §5):** every workflow
reaches the terminal state its path script declared; terminal event logs are
well-formed (no duplicate sequence numbers, no unaccounted gaps below the
terminal event); no signal is permanently lost; duplicate side effects are
counted and correlated against the chaos action log (§6 above); recovery/lock
metrics are sampled for the Issue 12 benchmark (`docs/open-issues.md` Issue
12).

**What it found and fixed, this cycle:** Issue 18 (split-brain duplicate
append misrecorded as workflow failure), Issue 19 (timed-out `awaitSignal`
replaying nondeterministically after a routine rolling restart), and Issue
20 (a transient store outage during a parked workflow's wake-recheck probe
durably failing a healthy workflow — surfaced by the PR-gate re-proof run
for Issue 19's own fix, when a 39s partition outlived the connection pool's
30s timeout) — all real `maestro-core` defects, all fixed RED-first via the
library-bug protocol. None required deliberate failure injection beyond the
harness's normal operation; Issue 19 in particular was triggered by a
routine graceful rolling restart racing a late signal.

**How to run it:**

```bash
# PR-gate mode (default): ~10-minute chaos window. This is the gate of record —
# 3 consecutive fresh-seed runs, all green, is what "the harness passes" means.
./gradlew :maestro-integration-tests:e2eTest --rerun-tasks

# Soak mode: multi-hour window + the vs-node-count benchmark tail
# (chaos off, steady low rate, ~5 min at 6 nodes, graceful stop of one node
# per service, ~5 min at 3 nodes — the Issue 12 benchmark of record).
# On macOS, prefix it with `caffeinate -i -m -s`: a host that sleeps mid-soak
# leaves a gap between chaos actions and invalidates the run.
./gradlew :maestro-integration-tests:e2eTest --rerun-tasks \
    -Dmaestro.chaos.soak=true -Dmaestro.chaos.durationMinutes=120
```

**Suite-selection note (`d4720ca`):** each dedicated invocation selects
*only* its dedicated test class — `ChaosPrGateE2EIT` runs on the default
invocation only, and is guard-disabled under the soak/golden/smoke/mode
flags. Before this fix, `-Dmaestro.chaos.soak=true` also selected the
PR-gate class, which picked up the soak duration and aborted at its own
25-minute `@Timeout` — the single root cause of every failed soak attempt in
this cycle. The CI weekly `chaos-soak` job uses the identical invocation and
self-heals through the same class-level guards; no workflow change was
needed.

Needs Docker; `e2eTest` pulls in the three sample services' boot jars via
`dependsOn`, so it is never wired into `build`/`check`. CI runs PR-gate mode
nightly (3× consecutive, `.github/workflows/e2e-nightly.yml` job
`chaos-pr-gate`) and soak mode weekly plus on-demand (`chaos-soak`). Evidence
(JSONL/CSV/JSON, identity-stamped) lands in `maestro-integration-tests/
build/chaos-evidence/<runId>/`; the SDD evidence mirror is
`.superpowers/sdd/multi-instance/evidence/task7/` (see its `INDEX.md`).
Design doc: `.superpowers/sdd/multi-instance/chaos-harness-design.md`.

---

## 9. Coverage boundaries — stated honestly

This section exists so nothing above is read as broader than it was
measured:

- **WAITING_TIMER under a rolling restart** is not covered by loan-E2E
  scenario 8 (§2's coverage-boundary note) — it's covered by engine
  integration tests and the chaos harness's `ROLLING_RESTART` action against
  `verification-gateway` nodes instead.
- **The `underwriting-service` pending-review queue
  (`PendingReviewRegistry`) is a documented best-effort, node-local view**
  (its own Javadoc says so). The loan-E2E driver works around this by
  polling *all* underwriting instances for pending reviews in cluster mode —
  a harness accommodation to a documented sample-application design choice,
  not an engine guarantee that pending-review state is cluster-visible.
  Decision endpoints themselves are unaffected (store-backed signals, routed
  cross-node by the engine regardless of which node receives the HTTP call).
- **Every §1-4 latency number is one workload** (`sample-loan-origination`)
  **on one set of machines.** They show which term dominates a bound (poll
  interval vs. lock TTL vs. Kafka session timeout), not a portable SLA.
- **The chaos harness's split-brain evidence (§6) is three 10-minute
  PR-gate runs plus one 120-minute soak run, one workload** — see
  `docs/open-issues.md` Issue 11 for the full caveats, including the soak
  run's provenance caveats (pre-fix PR-gate collision in the same JVM,
  leaked-checker console noise, binary-vs-stamp commit skew).
- **The chaos evidence in §6 predates the lease fence** (`docs/open-issues.md`
  Issue 11, fixed 2026-11-04). It measured how often a stale node's *side
  effects* duplicated under the old no-fencing design; the fence now makes the
  *persisted-state* guarantee unconditional, but the one-step side-effect
  window it leaves open has not yet been re-measured on a fenced build. The
  harness's `SideEffectCensus` tolerances are unchanged until one green soak
  on this build.
- **Recovery-polling scale (`docs/open-issues.md` Issue 12)** now has its
  vs-node-count benchmark of record from the soak run's chaos-free tail:
  cluster recovery-query rate is proportional to node count — consistent
  with linear at both measured node counts (a constant
  ≈0.0167 calls/s per node at 6 and at 3 nodes), while lock probe/renew
  traffic tracks the parked-workflow backlog rather than node count. One
  workload, modest absolute load (6/min tail rate) — the *trend* is the
  result, not an SLA. See Issue 12's section for the table. That benchmark
  predates the 2026-11-11 changes (batched renewal, the paged service-scoped
  recovery scan): it measured one unscoped recovery read per node per poll and
  one renewal statement per held lock, and has not been re-run since. §13 gives
  the current per-node cost as arithmetic.

---

## 10. Versioning and mixed-version deploys

*Added in the release-hardening cycle. Unlike §§1–8, this section carries no
measured numbers — it is a behaviour playbook, and every statement in it is
traceable to engine code and its pinning tests rather than to a timing run.*

*Admin Retry in a mixed fleet: a node of the previous build retries the old
way (it deletes failure memos by type and ignores `failedSequences`); a node of
this build retries history an older node wrote by matching the recorded
message. An older node reads the new `failedSequences` field of a newer
`WORKFLOW_FAILED` by ignoring it (Jackson's default) — except in a host whose
`ObjectMapper` enables `FAIL_ON_UNKNOWN_PROPERTIES`, where
`WorkflowHandle.failure()` throws on the older node until the deploy completes
(traced, not tested). The details are in `docs/release-notes.md`, "Mixed
fleets", under the admin Retry entry.*

### 10.1 The deploy rule has not changed: upgrade all nodes together

*Supported skew (`docs/support-matrix.md`): a rolling upgrade from the
**previous release** only. Old and new nodes running together is not yet
proven by a test (Issue 41, pilot-readiness P4); skew beyond the previous
release is not supported.*

**Upgrade every node of a service together, or drain the service first.** The
engine now stands down rather than failing when it meets history it cannot read
(§10.3), but that is a **safety net for the rolling window**, not a licence to
run a mixed fleet. A workflow whose next step only an upgraded node can read
makes no progress on the old nodes; it simply waits, safely, to be adopted by a
new one. Leave a fleet half-upgraded and those workflows wait indefinitely.

New event types are rare, so this is insurance, not a hot path.

**This release changes the `awaitSignal` layout — rollback requires draining
parked awaits.** An await that parks now records `TIMER_SCHEDULED` at its slot
and its outcome at the next one (the two-slot layout, `docs/release-notes.md`
→ "`awaitSignal` deadlines are durable timers"). Histories an older build wrote
replay safely here, but **not the reverse**: an older node that adopts a
workflow parked in a new-layout await stands down — a build with the replay
drift guard as `nondeterministic_history` (detail
`stored=$maestro:awaitSignal:<name> type=TIMER_SCHEDULED`, the same step name
the code performs), a pre-drift-guard build as `stale_run` when its outcome
append collides with the occupied slot. Nothing is written, nothing is
compensated, but the older node makes no progress on that workflow. So upgrade
every node together, or drain parked awaits first; **after a rollback, roll
forward** — redeploying the older build again (§10.7's first remedy) does not
heal these stand-downs.

**Lock backends do not mix.** A node on a build that locked in Valkey and a
node on this build locking in `maestro_distributed_lock` do not exclude each
other — both can run the same workflow at once. The upgrade that removed the
Valkey lock therefore cannot roll: stop every older node first
(`docs/configuration.md` § Schema management → "Upgrading an existing
database").

### 10.2 `workflow.version()` — changing workflow code with instances in flight

Recovery re-runs the workflow method against the **current** code. If you edit a
workflow while long-lived instances are in flight, a replaying instance takes
the new path from wherever it resumed — half its work done the old way, the rest
the new way.

`workflow.version(changeId, minSupported, maxSupported)` makes the choice of
path a durable, memoized decision: the first live evaluation records
`maxSupported` as a `VERSION_MARKER` event and returns it; every replay returns
the **recorded** value forever, regardless of what the code's `maxSupported` has
moved on to. See [`docs/concepts.md` → Versioning Workflow Code](concepts.md#versioning-workflow-code)
for the two-step ship-then-raise-the-floor pattern and the rules.

Operationally, what matters:

- **`VERSION_MARKER` is a new event type in this release.** A node from the
  previous version that adopts a workflow whose history contains one cannot
  interpret it, and stands down (§10.3). This is exactly the case §10.1's rule
  covers.
- Raising `minSupported` before every pre-change instance has drained fails
  those instances with `UnsupportedWorkflowVersionException` — a genuine,
  deterministic workflow failure: it ends `FAILED` and saga compensation runs.
  Recovery: restore code carrying the old branch, then use the admin **Retry**
  action. Retry never deletes a version marker recorded before the failure, so
  the retried run replays the same recorded version against the restored
  branch (`RetryPlannerTest.aVersionMarkerRecordedBeforeTheFailure_isKept`).
- `maxSupported` must be a **code constant** — see the rules in
  `docs/concepts.md`.

### 10.3 Stand-down: what it is, and what it is not

When a node reads persisted history it cannot interpret, the run **stands down**
instead of failing:

- Nothing is written. **No compensation runs.**
- The instance keeps whatever recoverable status it already had.
- The instance lock is released as the thread unwinds.
- `EngineObserver.standDown(reason, workflowId, detail)` fires, incrementing
  `maestro.standdown{reason=...}`.
- A `WARN` is logged, of the form:

  ```
  Workflow '<id>' stood down at sequence <n>: <why> (instance status still
  <status>) — no failure recorded, no compensation run; an upgraded node will
  adopt it via recovery
  ```

  That closing clause is the `unknown_event_type` one. For
  `unknown_event_payload` it reads instead `if a newer node wrote it, that node
  adopts it on recovery; on a homogeneous fleet this is an incompatible payload
  change — roll back, gate the change with workflow.version(), or terminate
  (operations §10.4)`; for `nondeterministic_history`, `roll back the deploy or
  gate the change behind workflow.version()` (§10.7).

For an `unknown_event_type` stand-down, an upgraded node then adopts and
finishes the workflow through the ordinary lock-TTL / recovery-poller
machinery, unchanged — nothing special is needed. The other two reasons are
**not** healed by waiting: see §10.4 and §10.7.

There are three history reasons and **they mean different things**:

| `reason` tag | What it means |
|---|---|
| `unknown_event_type` | An `event_type` string this build's enum does not define. Only a **newer** build can have written it. |
| `unknown_event_payload` | A stored payload this build could not deserialize while replaying. A newer build *or* an incompatible payload change. |
| `nondeterministic_history` | The current code performs a different operation at this sequence than the one recorded (step renamed/reordered, `parallel()` arity changed), or calls the recorded activity with different arguments (`argsHash` detail). A code change, or a non-deterministic activity input. See §10.7. |

(A fourth value, `stale_run`, is normally unrelated to versioning — it is the
split-brain convergence of §6: another runner owns the workflow. The one
versioning exception: after a rollback, a build older than the replay drift
guard reports `stale_run` on a workflow parked in a new-layout `awaitSignal`
(§10.1) — roll forward. It is reported
for an event-append collision with that runner's history (Issue 18), and for a
fresh read that finds the row already finalised (`COMPLETED`/`FAILED`) by it
(`StaleRunException`, C5) — at a park/wake status write, an `awaitSignal()`
wake, the transition to `COMPENSATING`, or before a sequential compensation
entry — and at the next stop point (live activity, `sleep()`,
`awaitSignal()`, terminal write) of a run whose earlier stand-down the workflow
code caught and swallowed.)

### 10.4 The alarm that matters: `unknown_event_payload` on a homogeneous fleet

**A replay `SerializationException` is now a permanent re-adopt/stand-down loop
with no `FAILED` status.** On a genuinely mixed fleet that is correct and
self-healing — the workflow waits for an upgraded node. On a **homogeneous**
fleet it is not a version skew at all: it is an author's incompatible payload
change, and the new behaviour converts what used to be a visible failure into a
**silent zombie** — the workflow never completes, never fails, and re-adopts
forever.

So, on a fleet you know to be homogeneous (deploy finished, one version
running), a rising

```
maestro.standdown{reason="unknown_event_payload"}
```

means **"an incompatible payload change needs `workflow.version()`"** — not
"wait for the deploy to finish". Find the changed activity return type,
parameter type, or workflow input shape; gate the change behind
`workflow.version()` so in-flight instances keep the old shape; redeploy.

Two other remedies exist, and neither needs a code change:

- **Roll back** to the build that wrote the payload. The stand-down loop ends
  immediately and nothing needs repairing — a stand-down writes nothing.
- **Terminate** the instance from the admin dashboard when it is not worth
  finishing. Stand-down never terminates on its own, because it cannot tell a
  transient skew from a permanent refactor.

The WARN and the `standDown` observer detail carry the deserializer's root
cause as **the exception class and the JSON path of the offending field**:
`seq=N cause=<class> at <path>` — for example
`seq=3 cause=tools.jackson.databind.exc.InvalidFormatException at
lines[2].status` — or just `seq=N cause=<class>` when the failure has no field
path (a root-level shape mismatch, or a stream-level error). The path is
structural only: property names, list indexes, and `[<key>]` in place of any
map key (`byCustomer[<key>].amount`), because map keys are often user data. That is
what distinguishes "a newer node wrote this" from "my own refactor broke this"
on a fleet you know to be homogeneous. The deserializer's own message is
deliberately left out: Jackson quotes the stored *values* (and, for stream
errors, the source text) in it, and stored payloads carry customer data that
must not reach log or metric backends. **The raw deserializer message is in the
DEBUG stack trace** — enable `DEBUG` for `io.b2mash.maestro.core.engine.WorkflowExecutor`
to see the full cause chain logged with each stand-down.

`unknown_event_type` keeps the "wait for the deploy" reading, because a type
this build does not define really can only come from a newer build. That is
precisely why the two are distinct enum constants and distinct tag values rather
than one.

**Suggested alerts:**

| Signal | Reading |
|---|---|
| `rate(maestro.standdown{reason="unknown_event_type"})` > 0 **during** a rolling deploy | Expected. Should return to zero once every node is upgraded. |
| the same, **after** the deploy completes | Not expected — a node was missed, or a stale replica is still running. |
| `rate(maestro.standdown{reason="unknown_event_payload"})` > 0 on a homogeneous fleet | **An incompatible payload change. Use `workflow.version()`.** |
| `rate(maestro.activity.result.unreadable)` > 0 | A live result cannot be read back, or reads back as another class than the object the activity returned (a subtype declared as its supertype, a value declared as `Object`). Those runs continued with the activity's own object; on their next recovery they will stand down as `unknown_event_payload` (cannot be read back) or replay the other class, which can stand them down as `nondeterministic_history` or fail a cast. The `ActivityInvocationHandler` `WARN` says which. Declare the concrete, round-trip-stable return type ([Pitfalls §12](pitfalls.md#12-your-workflow-gets-an-activitys-result-as-the-store-gives-it-back-not-the-object-the-activity-returned)). |
| `unknown_event_payload` **and** `nondeterministic_history` both rising on a homogeneous fleet | One deploy shipped both an ungated code change and an incompatible payload change; roll it back. |
| `maestro.workflows.running` / `maestro.workflows.parked` flat at zero on a node with traffic | That node is adopting nothing — check the lock backend and the recovery poller. |

Remember that both gauges are **node-local, in-JVM** values: dashboard them per
pod and sum for a cluster total (see `docs/observability.md`).

### 10.5 A skipped instance row reports "not found"

An instance row whose `status` column this build cannot map — again, written by
a newer node — is **skipped** by the store's row mapper rather than throwing.
`WorkflowStatus.valueOf` used to throw an `IllegalArgumentException`, and
because `WorkflowExecutor.recoverWorkflows` has no per-instance try/catch, one
such row aborted the **entire recovery pass** for every workflow on that node.
Skipping keeps the pass running, and an upgraded node — which can read the
status — owns the instance.

**The deliberate trade:** while that node cannot map the row, `getInstance`
returns empty, so an operator API asking this node about an existing workflow
reports it **"not found"**. Every caller already has a defined, non-destructive
answer for an absent instance — log and return; treat a signal as pre-delivery
so it is stored and adopted later, never discarded. The trade is documented in
the mapper's Javadoc (`AbstractJdbcWorkflowStore.mapInstance`) and it is chosen
deliberately: a misleading "not found" on one node during a deploy window is
cheaper than a recovery pass that stops for every other workflow on that node.

If an operator reports "the dashboard says the workflow doesn't exist" during a
rolling deploy, check for the WARN:

```
Unknown workflow status '<raw>' on instance (workflowId=..., id=...) — written
by a newer node; skipping this instance so the rest of this query, and any
recovery pass it feeds, still completes
```

### 10.6 Schema migrations

Maestro's migrations always target `maestro_*` tables and are applied by Maestro itself at startup in the connection's current schema, so under the default `maestro.store.schema.mode: flyway` an upgrade between builds that both keep `maestro_schema_history` needs no manual SQL (the one-time move to that history table does — last paragraph below). The former custom-prefix mode (`maestro.store.table-prefix`) is gone; to isolate services sharing a database, give each its own Postgres schema (see `docs/configuration.md` § Store Configuration).

Maestro records what it applied in its **own Flyway history table, `maestro_schema_history`** — never your application's `flyway_schema_history`, whose `spring.flyway.*` settings it ignores. `maestro-store-postgres` ships its SQL under two locations (`classpath:db/maestro/store`: V1–V9 and V101; `classpath:db/maestro/lock`: V100; band 200–299 belonged to the removed Postgres messaging transport and is never reused), and every location on the classpath is applied. **Every new Maestro migration, store or lock, takes the next version above the highest shipped, within 101–199; versions 1–9 and 100 are history.** To a build that does not ship it, a migration above V100 is a "future" migration that Flyway ignores, so a node on an earlier main build from e91bd9d (PR #43) on restarts, and a rollback to such a build starts, against a database a newer build migrated; an older build may not (`docs/configuration.md` § Schema management, "Numbering rule"). The first node of the new build applies the new migrations before its engine starts, and Flyway's database lock makes concurrent starts safe. **Do not assume older nodes keep working against the newer schema**: most of Maestro's migrations add a column, index or default, but not all. Before a deploy that ships a migration, read its release-notes entry; a migration that drops or renames anything means stopping every older node first, on top of the deploy rule in §10.1. `maestro.store.schema.mode: validate` makes a node fail startup instead when migrations are pending; `none` hands the DDL to you entirely. V6–V9 predate that rule and sort below V100. A database that recorded V100 before them gets them out of order: this build applies them, and reports them pending under `validate` (`docs/open-issues.md` Issue 35). They also leave one limit, confined to unreleased builds: a node on a main build from c01d498 (PR #35) up to, but not including, e91bd9d (PR #43) that lacks one of V6–V9 (and, before PR #36, resolves V100 or V200–V202 because the optional lock or Postgres-transport module was on its classpath) **cannot start** in `flyway` or `validate` mode against a database that recorded it (`Detected applied migration not resolved locally: <n>`). Nodes already running carry on, and such a node starts only with `maestro.store.schema.mode: none`. Every build from e91bd9d on, main @ a27a275 included, restarts normally against this release's schema. Under `validate`, record a new migration before rolling the fleet, through the Flyway CLI as the role that owns the tables, or with one `flyway`-mode start if the application's role owns them. A hand-inserted history row fails the checksum check, and a role that does not own the tables cannot run even `CREATE INDEX IF NOT EXISTS`. The per-mode V101 steps are in `docs/configuration.md` § Recovery Configuration. A database migrated by a build from before Maestro owned its history (0.4.0 and earlier) needs a one-time procedure — stop every node; then either drop the `maestro_*` tables, or keep them under `maestro.store.schema.mode: none` with `V4`–`V9` and `V101` applied by hand (`V9`, the signal `message_id` column and its unique index, is a hard prerequisite: this build names the column in every signal insert; `V8` and `V101` are index-only — their queries are correct without the indexes, and under `none` nothing reads the history); delete Maestro's rows from `flyway_schema_history`; start the new build. A database that once ran the Postgres messaging transport keeps an orphan `maestro_signal_queue` table (Flyway's default `ignoreMigrationPatterns=*:future` tolerates its history rows); dropping it is optional. The SQL for each step is in `docs/configuration.md` § Schema management → "Upgrading an existing database".

### 10.7 Replay drift: `nondeterministic_history`

**What it means.** Recovery replays the workflow method against the current
code and matches each step to the recorded event at the same sequence number.
A `nondeterministic_history` stand-down means the event recorded there was
written by a *different* operation than the one the code now performs: an
activity was renamed or reordered, a `sleep()`/`awaitSignal()`/side effect now
sits where something else ran, an awaited signal was renamed, a `parallel()`
now forks a different number of branches, a saga compensation entry was
renamed or reordered, or `@Saga(parallelCompensation)` was flipped. In every
case the workflow was edited without a `workflow.version()` gate (§10.2) while
instances were in flight. It is checked at every replay read — including a
`sleep()`'s wake slot (which must hold that sleep's own `TIMER_FIRED` /
`TIMER_CANCELLED`) and the saga's `COMPENSATION_STARTED` / `COMPENSATION_COMPLETED`
markers — **before** any side effect at that slot: no activity runs, no timer
row is saved or re-parked on, no signal is consumed, no branch or compensation
is started. The `version()` peek itself
is not a drift check: a non-marker event there means "this history predates
the change", by design.

It also means, for an activity slot whose step name *does* match, that the
activity is being called with **different arguments** than the recorded run
passed. Every `ACTIVITY_COMPLETED` / `ACTIVITY_FAILED` event carries a SHA-256
fingerprint of its arguments (`maestro_workflow_event.args_hash`, V7); replay
fingerprints the current call and compares. This drift has two causes, and
the detail cannot tell them apart: an ungated change to *how the argument is
built* (a code change, the remedies below apply), or a **non-deterministic
input** — the argument came from a `HashMap`'s iteration order, from
`Instant.now()`, from a per-node config value, from anything the determinism
rule (`docs/concepts.md`) forbids between activity calls. Before this check
the second cause was *silent*: the recorded result replayed for an input the
recorded run never saw, and the workflow carried on with it. A stored row
without a fingerprint (written before V7, or by an older node mid-rollout) is
unverified and replays as before; the check is also switchable
(`maestro.replay.verify-arguments`, `docs/configuration.md`).

**Why it stands down rather than fails.** The history is fine; the code is the
problem, and rolling the code back is the fix. Recording `FAILED` here would run
compensations for work that never failed — the same argument as §10.3. Before
this check existed the drift was silent: a renamed step returned the old step's
recorded payload and the run carried on with it, a renamed signal await
returned the other signal's payload, and a `sleep()` landing on an occupied slot
inserted a `PENDING` timer row before colliding on the append — one leaked row
per recovery pass, misreported as `stale_run`.

**What you see.** The WARN of §10.3, ending `roll back the deploy or gate the
change behind workflow.version()`, and an observer detail of the form

```
seq=<n> expected=<step the code performs> stored=<step recorded> type=<EVENT_TYPE>
```

(for a `parallel()` arity change: `seq=<n> expected=$maestro:parallel
branches=<code> stored=$maestro:parallel branches=<recorded>`; for an
argument mismatch on a matching step: `seq=<n> step=<group.method> argsHash
expected=<8 hex of the current call> stored=<8 hex recorded>` — the hashes
only, never an argument value, since arguments routinely carry customer
data). The instance keeps its status (`RUNNING`, `WAITING_*` or
`COMPENSATING`) and stays recoverable.

**The two remedies.**

1. **Redeploy the previous build**, so the recorded path is runnable again.
   The next recovery pass adopts the instance and it finishes normally.
   Schema caveat: this release's only migration, V101, does not stop a
   rollback to any build since PR #36, main @ a27a275 included, because it
   sorts above that build's newest migration, V100, and so is a future
   migration to it. A build older than e91bd9d (PR #43, all unreleased) that
   lacks one of V6–V9 this database recorded fails startup in `flyway` and
   `validate` mode (`Detected applied migration not resolved locally: <n>`).
   Roll back no further than e91bd9d, or start the older build with
   `maestro.store.schema.mode: none` (§10.6).
2. **Gate the change behind `workflow.version()`** (§10.2) — the old path for
   histories that predate it, the new one for new instances — and redeploy.

**For an `argsHash` detail, first decide which cause you have.** If the way
the argument is built changed in the deploy, it is a code change and the two
remedies above apply. If it did not — the same build, or the drift appears on
a node that never deployed anything — the input is non-deterministic, and
*no build can replay this instance correctly*: the recorded run passed a
value this run cannot reproduce. Then:

- **Fix the workflow** so the argument is deterministic (compute it inside an
  activity, or from `workflow.currentTime()` / `workflow.randomUUID()`, or
  iterate a sorted view), gated behind `workflow.version()` so histories that
  predate the fix keep their path; and
- for the instances already in flight, either **terminate** them (admin
  dashboard) and complete their business outcome by hand, or — if you have
  established that the drifted argument is benign for these instances (the
  activity would have produced the same result) — set
  `maestro.replay.verify-arguments: false` on the fleet long enough for them
  to drain, then set it back. Live outcomes keep recording their fingerprint
  while it is off, so nothing is lost for later checks. The engine WARNs at
  startup while the check is off; do not leave it off.

**Exception — `stored=$maestro:awaitSignal:* type=TIMER_SCHEDULED` after a
rollback → roll forward.** When `expected` and `stored` name the same
`$maestro:awaitSignal:<name>` step and the stored type is `TIMER_SCHEDULED`,
the "drift" is the engine's own two-slot await layout, written by the newer
build you rolled back from (§10.1). The workflow code did not change, and
remedy 1 is what caused it: redeploy the newer build.

**Upgrade note — histories that already drifted silently.** A workflow edited
without a `version()` gate under an *older* build may have "worked": the drift
was silent, so the instance carried on (on the wrong payload) and is still in
flight. After upgrading to this build, its next replay checks the recorded
step at every read and the instance stands down as `nondeterministic_history`.
**Rollback does not apply** — the build you would roll back to is the one that
replayed it wrongly, and the history it wrote already disagrees with the code.
The remedies are:

- **Terminate** the instance (admin dashboard) and handle its business outcome
  by hand, or
- **restore the recorded name** so the recorded path is runnable again. A
  recorded activity step is `<group>.<method>` (the detail's `stored=` field
  names it exactly): if the interface was renamed, pin the old group with
  `@Activity(name = "<group>")` on it; if the method was renamed, give it its
  old name back. Then redeploy.

Where possible, **drain in-flight instances before upgrading** a service whose
workflow code was edited without `version()` gates since those instances
started.

**Alert.** `rate(maestro.standdown{reason="nondeterministic_history"}) > 0` →
"an ungated workflow edit shipped; roll back". Unlike `unknown_event_type` it
does not go away when a deploy finishes — it *is* the deploy.

**Churn is by design.** Until one of the remedies ships, every recovery pass
adopts the instance, stands down again, and emits one WARN plus one counter
increment — exactly the §10.3 behaviour, and the reason the alert above is a
rate.

Retry keeps a failure the workflow caught and recovered from: it deletes only the failure that ended the run and what the run recorded after it while handling that failure (operations §4). A retry of a workflow that failed without the activity's exception in its chain keeps a caught failure that more work followed, and fails again; rethrow the exception, or wrap it with the cause (pitfalls rule 2).

Tests: `AdminRetryTest`.

**Compensations registered inside `parallel()` branches are not drift.** They
reach the saga stack in branch-index order, not finish order (see
`docs/concepts.md` → Compensation ordering), so a saga recovered
mid-compensation unwinds the same entries in the same order whichever branch
finishes first on replay. **Upgrade note:** an instance parked in
`COMPENSATING` whose branch compensations were recorded by the previous
version in an order other than branch-index order stands down here after
upgrade; drain such instances before upgrading.

### 10.8 Node errors: `node_error` abandons

**Node errors are not workflow failures — alert on them separately.** A
`LinkageError` (`NoClassDefFoundError`, `ExceptionInInitializerError`, ...) or
a `VirtualMachineError` other than `StackOverflowError` (`OutOfMemoryError`,
`InternalError`, ...) escaping a workflow method — or thrown inside an
activity or a compensation activity, which is then neither retried nor
recorded as `ACTIVITY_FAILED` — means that *node* is broken — a bad deploy or
resource exhaustion — so the engine does **not** record `FAILED` and does
**not** compensate (an unwind it interrupts is left `COMPENSATING`). It logs an `ERROR` under the workflow's
MDC (`Workflow '<id>' abandoned its local run on a node-level error (<class>)
...`), fires `runAbandoned(..., NODE_ERROR)` (span attribute
`maestro.abandon.reason=NODE_ERROR`), writes nothing, releases the lock, and
leaves the instance recoverable, so every recovery pass re-adopts it and logs
again until the node is fixed or rolled back. Alert on that `ERROR` line (or the
span attribute, or any increase of
`maestro_run_abandoned_total{reason="node_error"}`); the remedy is the node — redeploy the previous build, fix the
classpath, raise the heap — or terminate the workflow if it cannot be healed.
`StackOverflowError`, `AssertionError` and every other `Error` remain workflow
failures (`FAILED` + compensation): they recur identically on every node.

### 10.9 Store outages: `STORE_UNAVAILABLE` abandons

**A database outage is not a workflow failure (Issue 34).** The JDBC store
classifies every `SQLException` it raises. SQLSTATE class `22` (data
exception), `23` (integrity constraint) and `54` (program limit exceeded: a
`jsonb` payload over the size limit, an index row too large) are caused by
the workflow's own values and stay ordinary errors — the workflow fails as
before. Everything else — a pool acquisition timeout (no SQLSTATE), a refused
or dropped connection (class `08`), a restarting server (`57P01`),
insufficient resources (class `53`), a schema mismatch (class `42`) — is
thrown as `StoreUnavailableException`.

What a running workflow does with one:

- **Event appends and the memo lookup are retried in place**
  (`appendEvent`, `appendEventReturningPayload`, `getEventBySequence` — an
  activity's outcome, a compensation memo, a timer memo): three attempts,
  200 ms then 1 s apart. `appendEventReturningPayload` is the same write as
  `appendEvent`; the engine uses it only where it reads back what it
  stored, a completed activity's outcome and `currentTime()`, so the latch
  `WARN` and the abandon detail below name it there (`Store unavailable on
  appendEventReturningPayload — …`). A failed activity's memo and the
  `sleep()`, `parallel()`, `randomUUID()` and `version()` events still name
  `appendEvent`. A
  blip shorter than that is absorbed and nothing else happens. An append
  whose commit was lost in flight is caught on retry by the
  `(workflow_instance_id, sequence_number)` index. Every other store call —
  status writes, timer rows, signal consumption, the terminal write — is
  attempted once.
- **A store call an activity body makes is the activity's own.** A
  `MaestroClient` start or signal of another workflow from inside an activity
  body neither latches nor abandons the calling run: a store fault there fails
  that activity attempt under its `RetryPolicy`, like any other exception. Only
  the engine's own calls for the run — its memo lookup, outcome append, status
  and timer writes — latch it.
- **When the fault is final, the run is latched and abandoned.** The engine
  logs two `WARN`s under the workflow's MDC — `Store unavailable on <method> —
  workflow '<id>' stands down until the store recovers: <exception> —
  SQLSTATE=<state> <driver message>` and `Workflow '<id>' abandoned its local
  run — the store is unavailable (<method>: <same detail>); NOT recorded as a
  workflow failure, no compensation run; ...` — and fires
  `runAbandoned(..., STORE_UNAVAILABLE)` (span attribute
  `maestro.abandon.reason=STORE_UNAVAILABLE`). Nothing is written and nothing
  is compensated. The instance keeps its status (`RUNNING`, `WAITING_*`, or
  `COMPENSATING` if the fault hit mid-unwind) and stays recoverable. A
  latched run cannot append any more history, even if the store answers
  again before the run has unwound. Workflow code that catches the exception
  cannot run a fallback or complete: the run stops at its next activity,
  `sleep()`, `awaitSignal()` or terminal write. A fault inside a `parallel()`
  branch, or during compensation, abandons the whole run the same way.
- **Recovery resumes it.** The recovery poller's own pass fails while the
  store is down (`Background loop 'recovery-poller' failed …` at `ERROR`
  once, then a reminder every 5 minutes, and `… recovered after N consecutive
  failure(s)` at `INFO`).
  The first pass after the store answers adopts the instance, so expect it
  back within one `maestro.recovery.poll-interval` (default 60s). If the
  instance-lock release could not reach the database either, the lock row
  holds the instance until its TTL (30s) lapses. The recovering run replays
  every recorded step and carries on from the first unrecorded one.
- **The activity in flight runs again.** If the fault hit after an
  activity's body ran but before its outcome was recorded, recovery runs that
  activity again. For a transient fault the in-place retry narrows this
  window; it does not close it. For a fault that repeats — a schema error
  (class `42`, such as V7's `args_hash` column missing under
  `maestro.store.schema.mode=none`) — the in-flight activity runs once per
  recovery pass until the cause is fixed. In parallel compensation, a sibling
  compensation whose outcome append is refused after the run is latched runs
  again on recovery, so compensations must be idempotent too. Activities must
  be idempotent — the same at-least-once rule as an owner kill (§1) or a
  fenced run (§6).
- **Advisory reads never abandon.** The parked wake-recheck probes (Issue 20),
  an await deadline's self-fire, the post-deadline signal re-read, the
  cancel of a consumed signal's deadline row and the re-arm of a missing
  deadline row on replay skip a failed read or write and try again later.

**Alert on a sustained rate, not on single events.** An outage produces a
burst: roughly one `STORE_UNAVAILABLE` abandon per workflow that was running
(not parked — parked workflows only make advisory reads) when the database
went away, on every node. Recovery passes fail until the database answers,
so nothing is re-adopted into the outage. Alert when the abandon rate stays
up after the database is healthy again: alert on the rate of
`maestro_run_abandoned_total{reason="store_unavailable"}` (Micrometer
`maestro.run.abandoned`, see `docs/observability.md`), or count the `WARN`
lines or the segment spans tagged `maestro.abandon.reason=STORE_UNAVAILABLE`.
**The same run id abandoning
`STORE_UNAVAILABLE` on every recovery pass while other workflows progress is
not an outage.** It is a deterministic fault the classifier cannot tell from
one, most often a schema mismatch (class `42`). The `WARN` carries the
SQLSTATE and the driver's message — `SQLSTATE=42703 ERROR: column "args_hash"
does not exist` for a schema error, `SQLSTATE=none` for a pool acquisition
timeout — which tells them apart; the full exception is logged at `DEBUG`
under `io.b2mash.maestro.core.engine.StoreFaultGuard`. Every such pass also
re-runs the in-flight activity (above). Fix the cause or terminate the
workflow. A program-limit error (class `54`, such as an oversized payload) is
a data error and fails the workflow instead of looping.

### 10.10 Store-side faults that need a human: `TERMINAL_WRITE_FAILED`

One store-side outcome is deliberately *not* a workflow failure, and it is
logged at `ERROR` — alert on it as "the store is refusing writes":

- **`TERMINAL_WRITE_FAILED`** — `runAbandoned(..., TERMINAL_WRITE_FAILED)`
  (span attribute `maestro.abandon.reason=TERMINAL_WRITE_FAILED`), with the
  `ERROR` line `Could not persist the COMPLETED outcome of workflow '<id>' ...`
  (success path) or `Failed to update workflow '<id>' status to FAILED`
  (failure path). The atomic terminal write (Issue 26) failed with an error
  that is not a store outage — a store outage on the terminal write is a
  `STORE_UNAVAILABLE` abandon (§10.9) and no longer reaches this path. The
  instance stays active and the next recovery pass re-runs it. **Repeated for
  one run id** means the rejection is deterministic — a constraint or type
  error in the output or error payload (SQLSTATE class `22`/`23`): every
  recovery pass re-runs the workflow, re-abandons with the same `ERROR`, and
  the workflow never ends. Fix the cause (shrink the result, widen the
  column) or terminate the workflow.
  **It can also mean the commit landed but its acknowledgement was lost.**
  Then the row *is* terminal and the run is never re-driven — but
  `workflowCompleted`/`workflowFailed` and the `WORKFLOW_COMPLETED` /
  `WORKFLOW_FAILED` lifecycle event were never emitted. Check the instance:
  if it is already `COMPLETED`/`FAILED`, the outcome stands and only
  downstream consumers of the lifecycle event (the admin dashboard, any
  listener) missed it.

Suggested rule: any such `ERROR` → page; the same run id
abandoning `TERMINAL_WRITE_FAILED` on consecutive recovery passes → treat as
deterministic and escalate. (Signals whose handler exhausts its redelivery
budget land on the Kafka dead-letter topic — `docs/configuration.md`
§ Redelivery — not in the store.)

---

## 11. Retention and table growth

*Added with `maestro.retention.*`. Behaviour playbook, like §10: every
statement is traceable to `RetentionSweeper`, `WorkflowStore.purgeTerminalInstancesBefore`
and their pinning tests (`RetentionSweeperTest`, `PostgresWorkflowStoreTest.RetentionPurgeTests`,
`MultiNodeRetentionPurgeIT`), not to a timing run.*

Nothing in Maestro before this purged a finished workflow. Every `COMPLETED`,
`FAILED` and `TERMINATED` instance stayed in `maestro_workflow_instance` with
its full event log, its signals and its timers, so the four tables grew with
the number of workflows ever run. The **retention sweeper** bounds that.

### 11.1 What is purged, when, by whom

Each terminal status has its own retention (`completed-retention` 30 d,
`terminated-retention` 30 d, `failed-retention` 90 d by default): a workflow is
a candidate once its `completed_at` is strictly before `now − retention`. A
candidate is deleted with its entire history — every event, every signal whose
`workflow_instance_id` is the instance (consumed or not), every timer whatever
its status (a terminated workflow leaves `PENDING` ones behind) — and then the
instance row. Instances with a `NULL` `completed_at` are never candidates, and
orphaned signals (a `NULL` `workflow_instance_id`) are never touched.

Every node runs a sweep loop (`maestro-retention-sweeper` virtual thread, first
sweep at startup, then every `maestro.retention.interval`), but only the
**elected leader** for the service purges: before *every batch* the sweeper
calls `trySetLeader("maestro:leader:retention:<service>", candidateId, 15 s)` —
the same election the timer poller uses — and a node that is not the leader
stops at once, emits nothing and logs at `DEBUG`. A leader that dies hands over
within the 15 s TTL. The leader purges **this service's workflows only** —
those recorded under its `maestro.service-name` (`RetentionSweeperScopeTest`,
`PostgresStoreHygieneTest$ScopedPurge`). Services never share Maestro tables
(`docs/support-matrix.md`), so this matters only for rows recorded under a name
no node runs any more — after a rename: they are purged by nobody (§11.5).

Each batch (`batch-size` instances, 500 by default) is one
transaction: Postgres selects the candidates `FOR UPDATE SKIP LOCKED`, deletes
signals, timers and events, then the instances. A sweep stops a status's loop
when a batch comes back short or after `max-batches-per-sweep` batches — a
large backlog (say, the first deploy of this build onto a year-old database) is
drained at 50 000 instances per status per hour at the defaults, not in one
go. Raise `batch-size`/`max-batches-per-sweep` or lower `interval` to drain
faster; the per-batch transaction size is what bounds lock duration and WAL
burst.

**Pacing.** Between two batches the sweeper waits as long as the previous
batch took — the store call alone, on the monotonic clock — at most 5 s. The
first batch of a sweep is not paused, nothing is paused after the last, and the
step from one status to the next counts as between two batches
(`RetentionSweeperPacingTest`). So a sweep keeps the database busy about half
of the time it runs, instead of issuing up to 3 × `max-batches-per-sweep` (300
at the defaults) transactions back to back. It reduces no total: the same rows,
WAL and I/O are spread over about twice the time — a sweep of B batches of t
each takes about B·t + (B−1)·min(t, 5 s) (arithmetic). The 5 s cap is a third
of the leader TTL: leadership is renewed only by the election before each
batch, so the gap between two renewals is one batch plus one pause, and the cap
keeps that within the 15 s TTL for every batch shorter than 10 s — the pause
alone never costs a sweeper its leadership. Leadership is still re-checked
right before each batch, after the pause. A stop ends a pause at once, without
an interrupt (`RetentionSweeperPacingTest`, `BatchPacerTest`).

`maestro.signal.terminal-retention` (7 d, every node, no election) is the older,
narrower purge, the **signal janitor**: it removes only the *unconsumed
signals* of `COMPLETED`/`TERMINATED` workflows, and only this service's —
signals of workflows recorded under this node's `maestro.service-name`. It
deletes them in batches of 1,000, one short statement each, until a batch
deletes fewer, pausing between batches by the same rule as the sweeper, and
issues at most 1,000 batches (1,000,000 signals) per sweep; a larger backlog
continues at the next `janitor-interval` (`SignalJanitorBatchingTest`,
`PostgresStoreHygieneTest$ScopedJanitorDelete`). Two nodes sweeping at once
wait at most one batch on each other's row locks, and the later one then ends
its sweep early (traced, not tested). The two purges coexist; the retention
sweeper later removes the workflow itself.

### 11.2 What a purge means downstream

- **The admin dashboard keeps its own copy.** It consumed the lifecycle events
  and shows the workflow after the engine has purged it. **Retry on a purged
  `FAILED` workflow reports `NOT_FOUND`** (`WorkflowExecutor.retryWorkflow`
  reads the instance first) — the operator has `failed-retention` (90 d) to
  retry; after that the failure is history, not a workflow. Terminate behaves
  the same way.
- **The workflow ID becomes free again.** `workflow_id` is unique in
  `maestro_workflow_instance`, so a purge lets a new `startWorkflow` with the
  same ID succeed where it would have thrown `WorkflowAlreadyExistsException`.
  That new instance **adopts any orphaned signals** still addressed to that
  ID (orphans survive the purge precisely because the SPI promises never to
  discard a signal). If a late duplicate signal for the *old* run would be
  wrong for the *new* run, keep the ID unique across time (include a date or
  version in it) rather than relying on the engine to tell them apart.
- **A late signal races the purge benignly.** A signal delivered for a
  workflow that is being purged either resolves no instance and is stored as
  an orphan, or — if it resolved the instance ID just before the purge
  committed — fails its insert on the foreign key, inside the Kafka listener,
  and is redelivered; the redelivery finds no instance and stores the orphan.
  Nothing is lost; the dead-letter topic is reached only if every redelivery
  fails for some other reason (`docs/configuration.md` § Redelivery).
- **A concurrent retry never loses its history.** Postgres: the retry's one
  transaction takes the row lock first, so it blocks on (or is skipped by)
  the purge's `FOR UPDATE SKIP LOCKED`, and a retry that waited on a purged
  row reports `NOT_FOUND` (tests:
  `PostgresReopenFailedInstanceTest.aReopenWaitingOnAPurgeFindsTheRowGone`,
  `AdminRetryTest.aReopenThatFindsTheRowGone_isNotFoundAndLaunchesNothing`);
  the two cannot interleave. A dialect without row locking (the portable base
  SQL in `maestro-store-jdbc`) re-checks the status in the instance delete and
  rolls the whole batch back — children included — when the count falls
  short, logging a `WARN`; and because a retry locks the instance row before
  its children while that purge deletes the children first, the two can
  deadlock, and the database aborts one of them whole (traced, not tested).

### 11.3 Observing it

`maestro.retention.sweeps` counts sweeps this node ran as leader (a node that
never increments it is simply not the leader — expected on all but one node).
`maestro.retention.purged{kind=instance|event|signal|timer}` moves by the
sweep's counts. `/actuator/health` reports `retentionSweeperRunning` (`true`,
`false`, `"starting"`, or `"disabled"` when `maestro.retention.enabled=false`);
`false` after a start is a dead loop and turns the status `DOWN`.
`signalJanitorRunning` and `instanceLockRenewerRunning` read the same way (the renewer reads `"idle"` until this node takes its first instance lock); see §14. A sweep that
purged anything logs one `INFO` line with its counts; a store failure logs
`WARN` and the loop retries next interval. The lines:

- `Retention sweep purged {} workflow instance(s) with {} event(s), {} signal(s) and {} timer(s) in {} batch(es)`
- `Signal janitor purged {} unconsumed signal(s) of service '{}' workflows finished before {} in {} batch(es)`
- `Signal janitor started (interval={}, terminalRetention={}, batchSize={}, service={})`
- `Signal janitor ended this sweep after {} batches ({} signal(s)); the rest is left to the next sweep, in {}` — the janitor reached its 1,000-batch cap. Once after a large backlog is expected; every interval means a backlog above a million stale signals, or a custom store whose delete reports more rows than it removes.

### 11.4 Deploying it

- **Migration `V8__retention_indexes.sql`** adds `idx_wf_instance_terminal`
  and `idx_wf_signal_instance`. Flyway applies it in a transaction, so it is a
  plain `CREATE INDEX IF NOT EXISTS`, which takes a `SHARE` lock on each table
  for the build — writes block, reads do not. On a large table, pre-create both
  indexes with `CREATE INDEX CONCURRENTLY` under exactly those names before the
  deploy; the migration then finds them and does nothing. V8 names only V1
  objects, so it applies in any order relative to the other pending
  migrations.
- **Migration `V9__signal_message_id.sql`** (signal idempotency, not
  retention, but usually deployed alongside) adds
  `maestro_workflow_signal.message_id` (nullable, metadata-only) and the
  partial unique index `idx_wf_signal_message_id`. Every existing row has a
  `NULL` id, so the index starts empty and builds in one scan; pre-create it
  `CONCURRENTLY` under that name on a very large table. **Mixed-version:** an
  older node's signal insert omits `message_id` (stored `NULL`, never
  deduplicated) and its consume is the old two-step one, so duplicate
  suppression and atomic consumption hold only once every node runs this
  build. Under `schema.mode: none`, V9 must be applied before this build
  starts — it names the column in every signal insert.
- **First sweep after the deploy** on a database that has never been purged
  will find everything older than the retention eligible at once; see §11.1
  for the drain rate and how to tune it. Set `maestro.retention.enabled:
  false` on every node to keep the old keep-forever behaviour, or raise the
  retentions first and lower them later.
- **Mixed-version deploy** (§10.1 still applies): an older node's engine does
  not know the sweeper and keeps running; it merely sees finished workflows
  disappear, which no active-path read depends on.
- **Mixed-version deploy of the paced, service-scoped housekeeping:** a node of
  an older build still sweeps and runs its janitor unscoped and unpaced, as
  before. The retention leader key is the same in both builds, so an old and a
  new node of one service never sweep at once; the janitor has no leader, so an
  old node's janitor still deletes every service's stale signals in one
  statement. Nothing is worse than before the upgrade (traced, not tested). No
  migration, history or configuration change.

### 11.5 Rows recorded under another `maestro.service-name`

Every row of `maestro_workflow_instance` carries the `service_name` of the node
that started it. Recovery adopts only active rows recorded under this node's
`maestro.service-name`, and the retention sweep and the signal janitor touch
only this service's rows. **Services never share Maestro tables**
([`docs/support-matrix.md`](support-matrix.md)), so a row under any other name
is either another service pointed at the wrong tables, or nobody's: left by a
rename, or by replicas that once ran with per-replica names.

**The startup check (Issues 39, 79; pilot readiness P3).** At context refresh
— before any Kafka listener or recovery starts — each node runs one read for
**active** instances (`RUNNING`, `WAITING_SIGNAL`, `WAITING_TIMER`,
`COMPENSATING`) recorded under any other service name, of any workflow type.
`maestro.store.foreign-service-check` decides what rows found do:

- `fail` (default) — startup fails with `ForeignServiceRowsException`:

  ```text
  Maestro's tables hold active workflow instances recorded under another maestro.service-name than 'orders': 'orders-old' (3). Services never share Maestro tables (docs/support-matrix.md): give each service its own database or schema. If a node of this service ran under another name, or the service is being renamed, recovery adopts only 'orders' instances; set maestro.store.foreign-service-check=warn until the old name's instances are drained or moved (docs/operations.md §11.5).
  ```

- `warn` — one `WARN` per other name (at most 20; one `INFO` when there are
  more), and startup continues. For a rename in progress only:

  ```text
  3 active workflow instance(s) are recorded under maestro.service-name 'orders-old', not 'orders'. Recovery adopts only 'orders' instances, so unless a node runs with maestro.service-name=orders-old they never resume; finish the rename and set maestro.store.foreign-service-check back to fail (docs/operations.md §11.5).
  ```

- `off` — nothing is read.

The read is bounded: it runs on its own thread and startup waits for it at
most 5 seconds. A read that fails or does not answer in time is
**inconclusive** — one `WARN`, `Maestro shared-table check inconclusive: …`,
and startup continues, so an unreachable database never becomes a restart
loop; the next start checks again. It counts no terminal rows (no index
serves them by service name), never writes, and never logs a workflow id
(`StartupForeignServiceCheckTest`, `ForeignServiceCheckTest`,
`PostgresStoreHygieneTest$ForeignServiceCounts`). It runs once per node
start: ten replicas starting under `warn` log ten copies.

**Listing every name** (not exercised by a test; the release notes' pre-upgrade
query, by type):

```sql
SELECT service_name, workflow_type, count(*)
FROM maestro_workflow_instance
WHERE status IN ('RUNNING', 'WAITING_SIGNAL', 'WAITING_TIMER', 'COMPENSATING')
GROUP BY service_name, workflow_type ORDER BY service_name, workflow_type;
```

**Remedies for active rows** (traced, not tested):

1. Run a node with `maestro.service-name` set to the recorded name. Its
   recovery adopts the rows and finishes them; then retire it.
2. Or correct `service_name` on those rows to the name the service runs under
   now — **only while no node runs the old name**, so no run of those
   workflows is in flight anywhere: `UPDATE maestro_workflow_instance SET
   service_name = '<current>' WHERE service_name = '<old>' AND status IN
   ('RUNNING', 'WAITING_SIGNAL', 'WAITING_TIMER', 'COMPENSATING')`. The next
   recovery pass adopts them.

**Finishing a rename (runbook; Issue 77).** A service renamed from
`orders-old` to `orders` (traced, not tested):

1. Deploy the new name with `maestro.store.foreign-service-check=warn`. The
   nodes start and report the old name's active rows.
2. Drain or move the old name's **active** rows: run one node under the old
   name — also with `warn`, since to it the new name's rows are foreign —
   until its workflows finish (remedy 1 above), or, once no node runs the old
   name, correct their `service_name` (remedy 2).
3. Purge the old name's **terminal** history with the statement below. No
   node's retention sweep ever will: each sweep purges only its own name,
   so terminal rows under a name no node runs are kept for ever.
4. Set `maestro.store.foreign-service-check` back to `fail` (remove the
   property). The next start reads no other name.

Find terminal rows under other names (not exercised by a test):

```sql
SELECT service_name, status, count(*), min(completed_at)
FROM maestro_workflow_instance WHERE completed_at IS NOT NULL
GROUP BY 1, 2;
```

To remove one abandoned name's history, run this in `psql`, after setting
the name and the age once at the top:

```sql
\set ON_ERROR_STOP on
\set service 'orders-old'
\set age '30 days'
WITH candidates AS (
  SELECT id FROM maestro_workflow_instance
  WHERE service_name = :'service' AND status IN ('COMPLETED', 'TERMINATED', 'FAILED')
  AND completed_at < now() - CAST(:'age' AS interval)
  ORDER BY completed_at LIMIT 500
  FOR UPDATE SKIP LOCKED),
purged_signals AS (DELETE FROM maestro_workflow_signal
  WHERE workflow_instance_id IN (SELECT id FROM candidates)),
purged_timers AS (DELETE FROM maestro_workflow_timer
  WHERE workflow_instance_id IN (SELECT id FROM candidates)),
purged_events AS (DELETE FROM maestro_workflow_event
  WHERE workflow_instance_id IN (SELECT id FROM candidates))
DELETE FROM maestro_workflow_instance WHERE id IN (SELECT id FROM candidates);
```

**What it does.** It is one statement. It picks at most 500 terminal
workflows (`COMPLETED`, `TERMINATED` or `FAILED`) recorded under that name and
finished longer ago than the age, oldest first. It deletes their signals,
timers and events, then the workflows themselves. `psql` prints `DELETE n`,
the number of workflows removed. **Run it again until it prints `DELETE 0`.**

`DELETE 0` alone does not prove the name is clean. The statement skips any
candidate that another transaction holds at that moment. So after a
`DELETE 0`, count what is left, in the same `psql` session and with the same
variables. This count does not skip locked rows:

```sql
SELECT count(*) AS remaining FROM maestro_workflow_instance
WHERE service_name = :'service' AND status IN ('COMPLETED', 'TERMINATED', 'FAILED')
AND completed_at < now() - CAST(:'age' AS interval);
```

If `remaining` is above 0, run the purge again, and then this count again.

**Why it is safe beside running services:**

- **It is atomic, even in autocommit.** A single statement either removes a
  batch completely or changes nothing.
- **The name and the age are written once**, in the `\set` lines, so the
  child deletes and the workflow delete cannot disagree.
- **It locks its candidates for the whole statement** (`FOR UPDATE SKIP
  LOCKED`):
  - a candidate that another transaction holds is skipped, not waited on,
    and keeps its row and history. A Retry in flight is one such
    transaction;
  - a Retry that starts later waits for the statement, then finds no row.

Tested on Postgres 16 (Testcontainers `postgres:16-alpine`) by
`PostgresStoreHygieneTest$ScopedPurge`:

| Test | Shows |
|---|---|
| `theDocumentedManualPurgeRemovesAnAbandonedServicesHistory` | The history goes and nothing is orphaned. |
| `theManualPurgeTakesOnlyTheNamedServicesRowsOlderThanTheAge` | Only the named service, only past the age. |
| `theManualPurgeTakesAtMostFiveHundredInstancesPerRun` | Runs of 501 rows report 500, 1, then 0. |
| `aRetryInFlightWhenTheManualPurgeRunsKeepsItsRowAndHistory` | A Retry in flight keeps its row and history. |
| `theManualPurgeSkipsACandidateLockedElsewhereAndLeavesItsHistory` | A locked candidate is skipped, not waited on. While it is held, a run reports 0 and the count still reports 1. |
| `theOperationsGuidePrintsExactlyThisStatement` | This text is the tested statement, and the count is the tested count. |

The tests bind the name and the age as the two parameters. Neither the
statement nor the count is tested on Postgres 14.

**The cost of scoping in shared tables (Issue 78; history).** Shared tables
are no longer supported (pilot readiness P3), so this cost arises only while
a rename is in progress; Issue 78 is closed as out of scope. The sweep's candidate
select still walks `idx_wf_instance_terminal (status, completed_at)` and
filters `service_name` on the heap. A single-service database pays nothing for
the filter. When a co-tenant keeps a longer retention for the same status,
every batch reads past the co-tenant's rows in that window: 86 ms per batch
reading past 34,960 co-tenant rows, measured on the planning probe (Postgres 16,
412,500 instances, not production-sized).

---

## 12. Startup fails: missing Kafka topic

**Symptom.** A node refuses to start; the last lines of its log are one
`IllegalStateException` from `KafkaTopicVerifier`:

```text
3 of 5 Kafka topic(s) Maestro needs do not exist on kafka:9092:
[maestro.signals.order-service.DLT, payments.results.DLT, maestro.admin.events].
Maestro never creates topics — pre-create them (or set
maestro.messaging.topics.verify-on-startup=warn to start without them):
  kafka-topics.sh --bootstrap-server kafka:9092 --create --topic maestro.signals.order-service.DLT --partitions 3 --replication-factor 3
  kafka-topics.sh --bootstrap-server kafka:9092 --create --topic payments.results.DLT --partitions 3 --replication-factor 3
  kafka-topics.sh --bootstrap-server kafka:9092 --create --topic maestro.admin.events --partitions 3 --replication-factor 3
  (adjust --partitions/--replication-factor to the cluster; a single-broker development cluster needs --replication-factor 1)
```

**What it means.** The node asked the broker, once during context refresh,
whether every topic it needs exists — its own `maestro.signals.<service>`
channel and (with redelivery on) its `.DLT`, the admin-events topic (with
lifecycle events on), and every `@MaestroSignalListener` topic and its `.DLT`
— and the broker confirmed these do not. Maestro never creates topics, on
purpose: an engine that auto-created `maestro.signals.ordre-service` on a typo
would silently split a service's signals across two topics. This check is what
turned the old failure modes — a producer stalling `max.block.ms` on every
lifecycle publish (the never-explained six-of-six loan E2E failures), a
consumer polling a non-existent topic, a dead-letter publish failing minutes
after the handler gave up — into a message at the moment the gap can still be
fixed for free. See `docs/configuration.md` § Kafka Topic Verification.

**Fix.** Run the printed `kafka-topics.sh` lines (adjust partitions and
replication to the cluster) and restart the node. Nothing else is needed; the
node did not get far enough to touch Postgres or start a consumer.

**If you cannot create topics right now** — a cluster where topic creation is
a ticket, say — set `maestro.messaging.topics.verify-on-startup=warn`. The
node starts, logs the same message at `WARN`, and runs; its consumer on a
missing topic polls nothing until the topic appears, and a lifecycle publish
to a missing admin-events topic is dropped off-thread after `max.block.ms`
(10 s by Maestro's default) without ever blocking a workflow. Switch back to
`fail` once the topics exist.

**A different WARN: "Could not verify that N Kafka topic(s) exist".** The
probe could not get an answer — the broker was unreachable, slow (5 s bound),
or refused the request (authentication, authorization). This is
*inconclusive*, not *missing*, and never fails startup under any mode: a broker
blip during a rolling deploy must not become a fleet-wide restart loop. The
node continues with **tolerant** consumer containers for this boot — Spring
Kafka's `missingTopicsFatal` check counts an unreachable broker as "missing"
and would otherwise fail `start()` a moment after the WARN — so a topic that
really is missing polls nothing until it is created. Once the broker is
reachable again, restart the node to re-verify: a restart that then prints the
missing-topic failure above is a genuinely missing topic — apply the fix.
(`Topic(s) [...] is/are not present and missingTopicsFatal is true` from a
container therefore only ever appears on a node whose startup probe *did*
confirm the topics, i.e. a topic deleted while the node was running.)

**Upgrading from a build without the check.** A service whose `.DLT`
companions were never created used to start and run until its first exhausted
redelivery; it now stops at startup with the list above. Create the topics
before deploying (both compose stacks in this repository do), or deploy with
`warn` first and tighten afterwards.

---

## 13. Capacity

*Arithmetic, not measurement.* Unlike §§1–8, nothing in this section comes
from a timing run: every figure is derived from the intervals, page sizes and
batch sizes in the code of this build, at the default configuration. Use it to
estimate the load Maestro puts on your database and pool; measure your own
workload before relying on it.

**Per node, per interval** (H = instance locks this node holds, i.e. workflows
it is running or holding parked; A = active instances — `RUNNING`,
`WAITING_SIGNAL`, `WAITING_TIMER`, `COMPENSATING` — of this node's service;
P = workflows parked on this node):

| Work | Statements | Every |
|---|---|---|
| Instance-lock renewal | ⌈H / 500⌉ `UPDATE ... FROM unnest(...)`, on one connection; none when H = 0 | `maestro.lock.ttl` / 3 = **10 s** after the previous pass ended, so the period is 10 s plus the pass's own duration |
| Recovery pass — instance reads | ⌊A / 200⌋ + 1 page reads, i.e. ⌈A / 200⌉ plus one empty page when A is a multiple of 200 (A = 0 costs one read) | `maestro.recovery.poll-interval` = **60 s** |
| Recovery pass — lock reads | 1 `heldKeys` read of `maestro_distributed_lock` per page that holds a row neither running on this node nor locked by it, ≤ ⌈A / 200⌉; none on a node that runs every active row of its service, so on a single node normally none | same pass |
| Recovery pass — lock acquisitions | 1 `tryAcquire` per instance that is neither running on this node nor reported held by another (normally only orphans: a parked workflow's run keeps its lock) and that its task queue has room for on this node; with bounded queues, at most 2 × `concurrency` per queue for runs that are running or queued (a recovered run that parks gives its slot back, so a later pass can adopt more) | same pass |
| Recovery capacity pass | 0 statements with no free room; otherwise ⌈rows read / page⌉ page reads (a page is twice the free room, 20 to 200 rows) + as many `heldKeys` reads + 3 per adoption | at most once per second per node, only while a recovery backlog drains |
| Parked-workflow wake re-check | 2 reads per parked `sleep()` (instance status, timer row), 3 per parked `awaitSignal()` (status, signals, deadline row): 2P–3P reads | `maestro.signal.wake-recheck-interval` = **30 s** (unchanged by this cycle) |
| Timer poller | 1 leader-election upsert on every node; on the leader, also 1 due-timer read of at most `maestro.timer.batch-size` rows, plus the firing of each due timer it returns | `maestro.timer.poll-interval` = **5 s** after the previous tick ended |
| Clock-skew check | 1 `SELECT now()` | at timer-poller start, then every **10 min** |
| Retention sweep (leader only) | per batch 1 leader upsert + 1 candidate select + 4 deletes; ≤ 3 × `max-batches-per-sweep` batches, each after the first paused as long as the previous one (≤ 5 s) | `maestro.retention.interval` = **1 h** |
| Signal janitor (every node) | ⌈(U + 1) / 1,000⌉ deletes for U stale unconsumed signals of this service, at most 1,000 per sweep, paced the same way | `maestro.signal.janitor-interval` = **1 h** |
| Shared-table check | 1 read, over the other services' active rows only (none, on a correctly deployed service); bounded to 5 s | once per node start, at context refresh |
| Lock cleaner (every node) | 2 deletes: `maestro_distributed_lock` and `maestro_leader_election` rows expired for more than a minute | `maestro.lock.cleanup-interval` = **10 min** |

**The janitor's steady state.** Once the backlog is gone, every node's sweep is one statement that deletes nothing. That statement took 149 ms with the planner's custom plan, and 795 ms with the generic plan a server-side prepared statement can switch to in a pool. The old single, unscoped statement took 194 ms on the same state. That cost is paid once per node per `janitor-interval`. These figures were measured on the planning probe: Postgres 16, 412,500 instances, 42,060 unconsumed signals left to scan, not production-sized.

Worked example (arithmetic): a node holding 2,000 parked workflows of a
service with 10,000 active instances spread over five nodes renews them with
⌈2,000 / 500⌉ = 4 statements every 10 s (one per lock before this release:
2,000); its recovery pass is ⌊10,000 / 200⌋ + 1 = 51 page reads plus at most 50
lock reads (one per page that holds another node's rows) every 60 s, about 1.7 statements/s, with no acquisitions while the
other nodes are healthy; and its 2,000 parked workflows cost 4,000–6,000 reads
every 30 s, about 133–200 reads/s — at this scale the wake re-check, not
renewal or recovery, is the dominant term, and lengthening
`maestro.signal.wake-recheck-interval` is the knob (it also bounds cross-node
timer wake and terminate convergence, §5).

The table covers a node's steady background work. It leaves out what each
run costs to start, park, wake and finish (its activities, memos, status
writes and lock acquire and release); measure that for your own workflows,
for example with the k6 suite in `perf/`.

What paging does **not** change: a pass still reads every active row of its
service (A rows, payload columns included), only in bounded statements of at
most 200 rows that the V101 index serves without a sort. A renewal pass slower
than the 10 s interval logs a `WARN` naming the lock count, the duration and
the TTL; a pass that fails on the database keeps every lock and retries next
pass.

**Execution bounds.** Every task queue not listed in
`maestro.worker.task-queues` runs at most **20 workflow runs and 20 live
activities per node** (`maestro.worker.default-concurrency` /
`default-activity-concurrency`; each unlisted queue has its own pools, so Q
unlisted queues allow up to 20·Q of each). Recovery adopts per queue at most
C running plus C queued runs on a node at a time (C = the queue's
`concurrency`; a recovered run that parks frees its slot and keeps its lock,
so parked runs come on top) and leaves the rest unlocked for a peer or a later pass, so after a deploy the
backlog drains at the queue bound on every node that has room, not all at
once and not on one node. Both defaults `0` restores unbounded queues, and
unbounded recovery.

**Pool sizing.** If Maestro shares the application's pool, its traffic above
competes with the application's own. If Maestro has its own pool
(`@MaestroDataSource`, `docs/configuration.md` § A dedicated connection pool),
size it at **≥ max(`default-concurrency`, `default-activity-concurrency`) + 5**
(25 at the defaults; add the same `max(...)` for every further queue that is
busy at the same time). That is a heuristic, not a bound: the sum of the two
limits, plus 5, is the conservative figure. Both kinds of slot holder borrow connections: an
activity slot's holder for the activity's outcome write, and a
workflow slot's holder that runs no activity, such as a run replaying its
history or a parked run's wake re-check. The headroom covers the wake listener's one connection held for the
application's lifetime, the short borrow of every `NOTIFY`, the lock renewer
and the pollers. *Arithmetic:* wake re-checks keep on average
P × d / `maestro.signal.wake-recheck-interval` workflow slots busy, where d is
one re-check's duration (its 2–3 reads). In the worked example above, and
assuming 5 ms per re-check, 2,000 × 5 ms / 30 s ≈ 0.3 slots, so re-checks rarely crowd out runs; with
slow reads or a short interval, check the product. A pool that runs dry shows up as `STORE_UNAVAILABLE` abandons
(§10.9), not as workflow failures, and as `store: "timed out"` with status `UNKNOWN` on `/actuator/health` (§14).

### 13.1 Recovery sizing

*Arithmetic, not measurement*, for a backlog of N recoverable runs on one task
queue, T seconds per run, K nodes of the service and C = the queue's
`concurrency`:

- **Drain time** ≈ N · T / (K · C). At 5,000 runs, C = 20 and T = 2 s: about
  500 s on one node and about 250 s on two. Before this release the first node
  to start locked the whole backlog, so two nodes still took about 500 s while
  the second one idled.
- **A full pass** over that backlog costs 25 page reads, 25 `heldKeys` reads
  and 40 × 3 adoption statements (C running plus C queued): **170
  statements**, where the ungated pass before this release made about 15,026
  (3 per adoption for all 5,000, plus the reads).
- **In a steady drain**, each adopted run costs about
  3 + (1 page read + 1 `heldKeys` read) / (runs adopted per capacity pass). At
  the example's 10 runs finishing per second and one capacity pass per
  second, that is about **3.2 statements per adopted run**. A capacity pass
  starts at the first run it left on a queue that has room, so a full queue's
  backlog is not re-read for another queue's room
  (`RecoveryPollerCapacityTest.aQueueThatDrainsIsNotReReadBehindAQueueThatIsFull`).
- **`maestro.recovery.deferred`** (a gauge, `docs/observability.md`) is the
  number of runs this node's latest full pass left for lack of capacity: `0`
  on a healthy fleet, the visible backlog during a drain. A value that stays
  high over several full passes (`maestro.recovery.poll-interval` apart) means
  this node's queues cannot keep up with recovery: add nodes or raise
  `concurrency`. Every node of a service sees the same unadopted rows, so do
  not sum it across pods.

## 14. Health checks and Kubernetes probes

**Maestro's health indicator is a readiness signal at most, never liveness.** The `maestro` contributor (`MaestroHealthIndicator`) reports `DOWN` when the store refused the probe (`store: "unreachable"`) or a background loop that started has stopped (`timerPollerRunning`, `recoveryPollerRunning`, `retentionSweeperRunning`, `signalJanitorRunning`, `instanceLockRenewerRunning`: `false`), or the instance-lock renewer is running but has failed two passes in a row (`instanceLockRenewerConsecutiveFailures` `2` or more: held locks are not being renewed, and they expire after `maestro.lock.ttl`). One failed pass reads `UP`, with the streak of `1` in the details: the TTL is three renew intervals, so it covers roughly two missed passes — after one there is still a pass to spare, after two the held locks are at the edge of expiring (`theRenewerIsUpAfterOneFailedPassAndDownAfterTwoInARow`). It reports `UNKNOWN` when the probe (`getInstance` on a sentinel id) did not finish within 2 s (`store: "timed out"`) — usually a pool whose connections are all borrowed, which is the node doing its job. Spring Boot orders statuses `DOWN`, `OUT_OF_SERVICE`, `UP`, `UNKNOWN`, so an `UNKNOWN` `maestro` leaves the aggregate `UP`, and only `DOWN` and `OUT_OF_SERVICE` answer 503. A renewer that alternates one failed pass with one good pass never reads `DOWN`: each good pass renews every lock to its full TTL, so the locks stay valid (traced, not tested). If the probe's own thread cannot be started, the contributor reports `DOWN` with that failure; it never throws.

- **Liveness.** Spring Boot's `liveness` group contains only `livenessState`; keep it that way, and never point a liveness probe at `/actuator/health`. Restarting a busy pod abandons its in-flight runs to the lock TTL and adds a recovery pass to the fleet's load.
- **Readiness.** Add `maestro` only if this pod's HTTP traffic needs Maestro's store: `management.endpoint.health.group.readiness.include=readinessState,maestro`. A shared database that is down then takes every pod out of the Service, which is what you want only if the pods cannot serve without it.
- **Probe timeout.** The indicator can take up to 2 s to answer: a call that starts or joins a store probe waits for it for up to 2 s (only a call that finds the probe already timed out by an earlier call answers at once). A kubelet probe's default `timeoutSeconds` is `1`, so a slow store makes the HTTP probe itself time out, which the kubelet counts as a failure — the pod leaves the Service even though `maestro` would have answered `UNKNOWN` (HTTP 200). Set the readiness probe's `timeoutSeconds` to at least `3` whenever `maestro` is in its group; the `UNKNOWN`-is-200 behaviour below helps only if the probe waits long enough to receive it. (The 2 s bound is `returnsPromptlyWhenStoreBlocksIndefinitely`; the kubelet default is Kubernetes' documented behaviour, not tested here.)
- **A dead loop.** Since 3.5b a loop contains every `Error` except Maestro's control-flow signals, so a loop that stops is a bug: the log carries `Background loop '<name>' stopped: …` at `ERROR`, health reads `DOWN`, and the remedy is restarting the node — readiness does not do that for you. Alert on `maestro.loop.errors` (contained `Error`s) before it comes to that.

**`UNKNOWN` answers HTTP 200 by default.** That holds for `/actuator/health/maestro` too, and for a group whose only member is `maestro`. A host that wants a timed-out probe to answer 503 maps the status, and, for an aggregate or a group that also has an `UP` member, ranks `UNKNOWN` above `UP` — otherwise the aggregate stays `UP` and the mapping never applies:

```properties
management.endpoint.health.status.http-mapping.unknown=503
management.endpoint.health.status.order=DOWN,OUT_OF_SERVICE,UNKNOWN,UP
```

Think twice before doing this for readiness: a pool that is busy, not broken, then takes the pod out of the Service. `MaestroHealthIndicatorTest` pins Spring Boot's defaults (`bootsDefaultsLeaveAnUnknownMaestroAt200`) and the effect of both properties on Boot's aggregator and status mapper (`aHostCanMapUnknownTo503`); the full actuator endpoint with these properties set is traced, not tested. The status rule itself is pinned by `returnsPromptlyWhenStoreBlocksIndefinitely` (`UNKNOWN` within the bound), `aStoreThatThrowsWithinTheBoundIsDown` and `aTimedOutProbeDoesNotHideADeadLoop`.

## 15. Shutdown: one deadline and the grace period

`maestro.shutdown.timeout` (default **20 s**) is the deadline for the whole
engine shutdown, not only the drain. Every step that waits takes what is left
of it, measured on the monotonic clock. With every step stuck at once — four
background loops inside a store call that ignores the interrupt, a run inside
an activity, the lock renewer inside `renewAll`, and a stalled lifecycle-event
transport — `shutdown()` returns within the timeout plus under 1 s, the bound
`WorkflowExecutorShutdownDeadlineTest.shutdownReturnsWithinItsTimeoutWhenEveryStepIsStuck`
asserts at a 1 s timeout; it measured 1,002–1,008 ms. Before WP5 the same
set-up without the renewer took 26 s, because every step had a timeout of its
own.

### 15.1 The sequence

Maestro's `GracefulShutdownHandler` stops in Spring's phase `MAX_VALUE - 1024`,
the web server's graceful-shutdown phase, and **alongside** the web server's
drain: its `stop(callback)` returns at once and the engine shuts down on the
virtual thread `maestro-shutdown`, which calls Spring back when it is done
(`GracefulShutdownLifecycleTest.theWebServersGracefulDrainStartsWhileMaestroDrains`;
with a stand-in registered the way Boot registers its web-server lifecycle —
Tomcat itself was not run). The host's `@KafkaListener` containers (phase
`MAX_VALUE - 100`) have stopped before it (traced). Then the engine:

| # | Step | Waits? | For at most |
|---|---|---|---|
| 1 | Logs `Maestro shutdown started` and starts the deadline | no | — |
| 2 | Asks the timer poller, recovery poller, signal janitor and retention sweeper to stop: no new timer fires, no new recovery starts | no | — |
| 3 | Abandons every park (`awaitSignal`, `sleep`) and every retry backoff | no | — |
| 4 | Releases every run queued for a task-queue slot (Issue 29) | no | — |
| 5 | Drains the runs still executing | yes | what is left |
| 6 | Joins the four loops asked to stop at step 2 | yes | what is left |
| 7 | Takes back the permits lent at step 4, so the task-queue slot counts are exact again (a pool with a thread still queued keeps its loan) | no | — |
| 8 | Stops the instance-lock renewer, without an interrupt | yes | 2 s of what is left |
| 9 | Flushes queued lifecycle events; what is still queued is dropped and counted | yes | 5 s of what is left |
| 10 | Logs the summary line | no | — |

Nothing is interrupted: not a run executing an activity, not the lock renewer.
Once the drain has used the whole deadline, steps 6, 8 and 9 only signal and
return.

A second `shutdown()` call — Spring's inferred destroy method on the executor
bean — waits for the first to finish, for at most what is left of its deadline
plus 2 s (`WorkflowExecutorShutdownDeadlineTest.aSecondShutdownCallReturnsOnlyAfterTheFirstHasFinished`).
So even when `spring.lifecycle.timeout-per-shutdown-phase` is shorter than the
drain, the beans the engine was built on — the store, the lock, the messaging
and the `DataSource` — are destroyed only after the engine has finished
(`GracefulShutdownLifecycleTest.theEngineDrainsBeforeTheBeansItIsBuiltOnAreDestroyed`,
with a recording bean in the `DataSource`'s place, wired through an
`ObjectProvider` as Maestro's Postgres beans are). Beans created after the
executor — the host's own services — are destroyed before it, while it still
drains, if the phase timed out (traced).

`context.pause()` leaves the engine running: the handler is not pauseable
(`pausingTheContextLeavesTheEngineRunning`). `context.stop()` followed by
`start()` does not restart it.

A request already in flight that calls `startWorkflow` after the engine's
shutdown has begun gets `IllegalStateException` (`WorkflowExecutor is shutting
down — cannot start workflow '…'`), as before WP5. In a web host that is an
HTTP 500 unless the host maps it, for example to 503 so the client retries
against another node (`WorkflowExecutorShutdownDeadlineTest.startingAWorkflowDuringTheDrainIsRefused`;
the HTTP status is traced, not tested). Because the web server now stops
accepting new requests at the start of the drain instead of after it, only
requests already in flight can meet this.

### 15.2 What each workflow looks like afterwards

Shutdown itself appends nothing. A run that keeps executing writes what it
normally writes.

| State when shutdown began | What happens | Instance row afterwards | Instance lock | Recovered |
|---|---|---|---|---|
| Executing an activity that ends before the deadline | Its outcome is recorded; the run goes on until it parks, needs a slot, or completes | as the run leaves it | released by the run | at once |
| Executing an activity past the deadline | Not interrupted; `shutdown()` returns and the run keeps going until the JVM exits (`aShutdownThatHitsItsDeadlineNamesTheRunsStillRunning`) | `RUNNING` (or `COMPENSATING`) | held, no longer renewed; expires after `maestro.lock.ttl` (traced) | by a peer's next recovery pass; the activity runs again, so it must be idempotent |
| Parked on `awaitSignal` / `sleep` | The park throws `ExecutorShutdownException` | `WAITING_SIGNAL` / `WAITING_TIMER` | released at unwind | at once |
| In a retry backoff | The backoff is abandoned | `RUNNING`, no `ACTIVITY_FAILED` | released | at once |
| Queued for a workflow or activity slot | Leaves at once, never interrupted (`ShutdownReleasesSlotWaitersTest`) | `RUNNING` | released at unwind — normally before `shutdown()` returns; the drain waits for the unwind only within the deadline, so one still unwinding when it passes releases later, or by TTL | at once |
| Woken from a park, waiting for its slot again | Leaves; the signal stays unconsumed, a fired timer row stays `FIRED` | `WAITING_SIGNAL` / `WAITING_TIMER` | released | at once |
| Compensating: parked, queued or in a backoff | `ExecutorShutdownException` passes through the saga without recording the step | `COMPENSATING` | released | at once |

**Residual (Issue 70, traced, not tested).** A run still executing past the
deadline whose activity then fails because a host bean was destroyed after the
deadline can record `ACTIVITY_FAILED` on its last attempt, and the workflow
may then fail and compensate. Keep the deadline long enough for your
activities, and keep them idempotent.

### 15.3 What you see in the log

| When | Level | Line |
|---|---|---|
| Start | INFO | `Maestro shutdown started: {n} run(s) in flight; deadline {budget} ms (maestro.shutdown.timeout)` |
| Within the deadline | INFO | `Maestro shutdown finished in {total} ms of its {budget} ms deadline: {n} run(s) in flight at start, 0 still running (drain … ms, background loops … ms, instance locks … ms, lifecycle events … ms)` |
| Deadline hit, runs left | WARN | `Maestro shutdown hit its {budget} ms deadline (maestro.shutdown.timeout): {m} of {n} run(s) still running [ids]; shutdown does not interrupt them and records nothing for them; their instance locks expire after {ttl} ms (maestro.lock.ttl) and a peer recovers them (…)` — at most ten ids, then `and {k} more`; without a lock backend the clause reads `a restarted node recovers them` |
| A loop still inside a store call at the deadline | WARN | `Background loop '{name}' did not stop within the shutdown deadline; it ends after its current store call` |
| Events dropped at the flush deadline | WARN | `Lifecycle event publisher stopped with {k} queued event(s) unpublished` (also added to the publisher's internal drop count) |
| A second `shutdown()` call | INFO | `Shutdown already in progress — waiting up to {} ms for it to finish` (a WARN if that wait runs out) |
| Handler | INFO | `Maestro graceful shutdown initiated`, `Maestro graceful shutdown complete`; ERROR `Maestro graceful shutdown failed: …` if the engine's shutdown threw |
| Per run | INFO | `Workflow '{}' suspended by shutdown while {} — left recoverable` (unchanged; a queued run now logs it at once) |

The summary lines are pinned by `theFinishLineReportsTheStepsAndNothingStillRunning`,
`aShutdownThatHitsItsDeadlineNamesTheRunsStillRunning` and
`aLoopThatDoesNotStopByTheDeadlineIsNamed`; the publisher's by
`LifecycleEventPublisherShutdownTest`. If Spring's phase also timed out, Spring
logs its own `INFO Shutdown phase 2147482623 ends with 1 bean(s) still running
after timeout of …ms: [maestroGracefulShutdownHandler]`.

### 15.4 The grace-period arithmetic

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

Maestro's own term is bounded by `shutdownReturnsWithinItsTimeoutWhenEveryStepIsStuck`
(timeout + under 1 s asserted; about 5 ms over the timeout measured). Every other term is traced from the Spring, Spring Kafka
and Kubernetes sources and documentation, not run.

**The web server's drain has no timeout of its own.** Spring Boot's Tomcat
graceful shutdown waits while any request is active (it re-checks every 50 ms
until aborted, `org.springframework.boot.tomcat.GracefulShutdown` in Spring
Boot 4.0.5). What ends it is Spring's phase timeout,
`spring.lifecycle.timeout-per-shutdown-phase` (30 s by default in Spring
Boot): it bounds the whole phase `MAX-1024`, which Maestro's handler and the
web drain share, after which the web server is stopped in phase `MAX-2048`
and the drain aborted (traced). So the `max(...)` term above is the longer of
`maestro.shutdown.timeout` and the web drain, and at most the phase timeout:
with a request still active at the end, it is the full phase timeout. To cap
the web drain at Maestro's deadline, set the phase timeout to
`maestro.shutdown.timeout + 2 s` (22 s at the defaults).

- **Defaults, no preStop — the typical case:** 20 (`maestro.shutdown.timeout`;
  the web server's drain runs alongside it and is assumed to end within it) +
  ~1 (Maestro's Kafka containers, typically under 1 s together) + 2 (the wake
  listener, at most) + 5 (the `DataSource` and JVM margin) ≈ 28 s ≤ 30 s. That
  fits Kubernetes' default grace (30 s, traced), narrowly, and assumes no host
  `@KafkaListener` container is slow to stop.
- **Defaults, an HTTP request still active at the end:** the phase lasts the
  full 30 s `timeout-per-shutdown-phase`, so 30 + ~1 + 2 + 5 ≈ 38 s, beyond
  the 30 s default grace. Set the phase timeout to 22 s (≈ 30 s total) or
  raise the grace.
- **Defaults, worst case:** each of Maestro's Kafka listener containers — the
  engine's signal container plus one per `@MaestroSignalListener`, N in all —
  can take up to Spring Kafka's 10 s to stop, so 20 + 10·N + 2 + 5 s: 37 s
  for N = 1, 47 s for N = 2 — or, with a request keeping the web drain open to
  the 30 s phase timeout, 30 + 10·N + 2 + 5 s (47 s for N = 1). That exceeds the 30 s default grace, and the
  kubelet's `SIGKILL` then lands during bean destruction — after the engine
  has drained, so nothing durable is lost (traced), but consumers may not
  leave their group cleanly. If your consumers stop slowly, set
  `terminationGracePeriodSeconds` to at least `max(maestro.shutdown.timeout,
  timeout-per-shutdown-phase if a request can stay active) + 10·N + 7` seconds
  plus your preStop, plus whatever your own `@KafkaListener` containers and other
  lifecycle beans take to stop (traced; Issue 69 would take these containers out of the sum).
- **With a 5 s preStop:** ≈ 33 s typical. Set `terminationGracePeriodSeconds: 45`.
- **Keeping 30 s of drain:** `maestro.shutdown.timeout: 30s`,
  `spring.lifecycle.timeout-per-shutdown-phase: 35s`, grace ≥ 45 s + preStop.
- **If the phase timeout is shorter than the drain:** Spring moves on to lower
  phases and starts destroying beans created after the engine while it still
  drains. The engine and the beans it is built on stay up until it finishes
  (§15.1).

```yaml
spec:
  terminationGracePeriodSeconds: 45
  containers:
    - lifecycle:
        preStop:
          exec: { command: ["sleep", "5"] }
```

```yaml
spring:
  lifecycle:
    timeout-per-shutdown-phase: 25s
maestro:
  shutdown:
    timeout: 20s
```

### 15.5 Kafka

Maestro's own Kafka listener containers are not Spring lifecycle beans: they
keep consuming through the drain, which is safe because a signal that arrives
is persisted and recovered with the run it wakes, and they stop at bean
destruction, where their stop time adds to the arithmetic above. The
`@MaestroSignalListener` containers stop after the `DataSource`, so a record in
flight in that window fails; with redelivery attempts left (the default 10) it
is redelivered after the restart, with `maestro.messaging.redelivery.max-attempts: 1`
it goes to `<topic>.DLT`, and with redelivery disabled it is skipped (traced,
not run). Both are Issue 69.

### 15.6 Upgrading from 30 s

`maestro.shutdown.timeout` (and `WorkflowExecutor.builder(...)`'s default) is
now 20 s, was 30 s, and it now bounds the whole engine shutdown, not only the
drain. With a 30 s drain under an asynchronous stop, the drain alone would
equal Spring Boot's 30 s `spring.lifecycle.timeout-per-shutdown-phase` and
Kubernetes' 30 s `terminationGracePeriodSeconds`, leaving no time for the rest
of shutdown. To keep 30 s, set `maestro.shutdown.timeout: 30s`,
`spring.lifecycle.timeout-per-shutdown-phase: 35s` and
`terminationGracePeriodSeconds: 45` (more with a preStop hook).

---

## See also

- `docs/support-matrix.md` — what is supported, not supported and untested.
- `docs/observability.md` — the full meter catalog (including
  `maestro.standdown`), span topology, and the Kafka trace-propagation
  contract.
- `docs/concepts.md` — `workflow.version()` and the determinism rules.
- `docs/open-issues.md` — Issues 11, 12, 17, 18, 19, 20 in full: what was
  wrong (or measured), where, and every pinning test.
- `docs/maestro-architecture.md` §14 "Failure Modes" — the design-level
  guarantee table this document adds measured numbers to.
- `docs/configuration.md` — `maestro.recovery.poll-interval`,
  `maestro.lock.ttl`, `maestro.signal.wake-recheck-interval`,
  `maestro.worker.default-concurrency` / `default-activity-concurrency`,
  `@MaestroDataSource`,
  `maestro.lock.type`, `maestro.retention.*`, and every other property named
  above.
- `.superpowers/sdd/multi-instance/chaos-harness-design.md` — the chaos/soak
  harness's full design and implementation changelog.
- `maestro-samples/sample-loan-origination/README.md` and its `e2e/
  run-e2e.sh` header comment — the ten E2E scenarios these numbers come from.
