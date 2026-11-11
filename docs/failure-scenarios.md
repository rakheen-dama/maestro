# Failure and recovery scenarios that would put Maestro to the test

**Status:** specification, 2026-11-04, written against `main` bf0aaeb (after the
lease-fencing, retention, Kafka fail-fast and argument-fingerprint PRs).
**Audience:** whoever extends the chaos harness or the loan E2E next, and any
adopter deciding what to rehearse before production.

The existing evidence is strong but narrow in one dimension: it is all a
**single Postgres primary, a single Kafka cluster and a flat set of
identical nodes on one LAN**. The chaos harness (`ChaosController`) injects
`KILL9`, `PAUSE_RESUME`, `PARTITION` of a node from a backend, and
`BACKEND_OUTAGE` (pause the whole Postgres or Kafka container), and checks
invariants I1–I5 (every ledgered workflow terminal with the ledgered outcome,
no stuck `WAITING_TIMER`, one event per sequence, no unexplained duplicate
side effects, admin signals delivered). The loan E2E covers owner-kill
adoption, rolling restart, timer-leader failover and cross-node admin.
Multi-node ITs pin lock contention, owner death, no-lock-backend, signal
routing, timer wake, fencing and retention.

What follows are the architectures and faults that sit **outside** that
envelope. Each scenario states the fault, the invariant Maestro must hold,
how to measure it, what the engine is expected to do today, and what harness
capability is missing. Priority is P1 (a production adopter will hit this in
year one), P2 (plausible, severe), P3 (edge, but cheap to pin).

The invariants are the ones from `CLAUDE.md` and `docs/maestro-architecture.md`:

- **INV-LOG** one event per `(workflow_instance_id, sequence_number)`; a
  completed step is never re-executed on the same node with its result
  re-persisted.
- **INV-SIGNAL** a persisted signal is never discarded; a signal consumed by a
  memo is consumed exactly once.
- **INV-TERMINAL** a workflow reaches exactly one terminal status and its
  ledgered outcome; no "routine deploy recorded as FAILED".
- **INV-FENCE** a run whose lease epoch has been superseded persists nothing.
- **INV-COMP** compensation runs at most once per compensable step, only after
  a genuine failure.
- **INV-RETAIN** retention never deletes an active instance or an orphan
  signal.
- **INV-LIVE** no active workflow is left unadoptable while any healthy node
  exists.

---

## 1. Postgres high availability: failover, replication lag, point-in-time restore

The engine treats "the row is committed" as truth and uses the **database
clock** for lock expiry and timer firing. None of the current tests ever
replace the Postgres it started with.

### S1.1 Primary failover with asynchronous replication (P1)

- **Architecture:** Patroni or cloud-managed Postgres, one primary, one async
  replica, application connects through a VIP or proxy that moves on failover.
- **Fault:** kill the primary while N workflows are mid-activity. The replica
  is promoted having **lost the last few hundred milliseconds of commits**.
- **What is at stake:** rows the engine already believes exist vanish. A node
  that appended `ACTIVITY_COMPLETED` at seq 7 and moved on to seq 8 now runs
  against a database where seq 7 is missing; the activity lock row, the
  signal-consumed flag and the `lease_epoch` bump may all have been rolled
  back. This is the one way INV-LOG can be violated *without* two writers.
- **Expected engine behaviour (derive, then pin):**
  - The next fenced write from the live run either succeeds (its epoch row
    survived) or sees `lease_epoch` regressed. A regressed epoch is **lower**
    than the run's lease; the fence predicate `lease_epoch = ?` fails → the run
    stands down `LEASE_FENCED`. That is correct and conservative.
  - Recovery re-adopts the instance from the replica's state and re-executes
    seq 7. The activity's external side effect therefore happens **twice**;
    this is the documented at-least-once contract, but the window is now
    "replication lag", not "lock loss".
  - A signal whose `SIGNAL_RECEIVED` memo was lost but whose row's `consumed`
    flag survived (or vice versa) is the dangerous half-state: `consumed=true`
    with no memo means the signal is **gone** (INV-SIGNAL). Determine from the
    code whether the memo append and the consumed flip can land in different
    WAL segments; they are two autocommit statements, so yes.
- **Measure:** count of re-executed activities per failover; any
  `consumed=true` signal with no `SIGNAL_RECEIVED` memo at the recorded
  sequence; any instance whose `event_sequence` exceeds its highest event row.
- **Harness gap:** a two-container Postgres with streaming replication and a
  scripted promotion; or simulate the loss with `pg_rewind`-style truncation of
  the last K event rows on a paused primary. The second is cheaper and
  deterministic: **"delete the last K committed rows of a running instance"**
  as a new `BACKEND_OUTAGE` variant.
- **Likely design outcome:** make `markSignalConsumed` ride in the same
  transaction as the `SIGNAL_RECEIVED` append (the store already has a
  two-statement transaction skeleton), and document failover as a
  duplicate-side-effect window.

### S1.2 Synchronous replica, long failover (P2)

- **Fault:** `synchronous_commit=on`, replica promoted after 20–40 s of no
  primary. Every node's writes block then fail.
- **Expected:** instance locks expire (30 s TTL, database clock) while the
  holders are blocked on their own renewals. On reconnect, every node's lock
  manager has `renew == false` → `onLockLost` → `requestStale`, and every
  running activity completes into a fenced append. Meanwhile the recovery
  poller on *every* node scans and adopts — the thundering herd of
  `docs/open-issues.md` Issue 30 — and bumps epochs.
- **Measure:** time from promotion to last re-adoption; peak concurrent
  adoptions; count of `LEASE_FENCED` stand-downs vs. count of workflows
  (expect ≈ one per running workflow, never more than one per workflow per
  adoption).
- **Harness gap:** `BACKEND_OUTAGE(postgres)` already pauses the container but
  resumes the *same* primary, so no lock row changes under the holders. Add a
  variant that, while paused, **deletes all `maestro_distributed_lock` rows and
  bumps every active `lease_epoch`** before unpausing, simulating a promoted
  replica that outlived the leases.

### S1.3 Point-in-time restore (P2)

- **Fault:** operator restores the database to T-15 min after a bad migration.
  Kafka was not restored: consumers' committed offsets are ahead of what the
  database remembers receiving.
- **Expected:** workflows that completed in the lost window are RUNNING again
  and get re-adopted; their activities re-run (at-least-once, loud). Signals
  that Kafka delivered in the window are **not redelivered** (offsets
  committed) and their rows are gone: INV-SIGNAL is violated by the operator,
  not the engine, but the engine should make it *visible*: an `awaitSignal`
  that now parks forever where the business knows a signal was sent.
- **Measure:** parked-forever count after restore; whether the admin
  dashboard's projection (its own database, not restored) disagrees with the
  engine and how an operator would notice.
- **Deliverable:** a runbook section, and a decision whether Maestro should
  record Kafka offsets alongside consumed signals to support "replay from
  offset X after restore".

### S1.4 Clock skew between the database and the nodes (P3)

- **Fault:** node clock +90 s against the database.
- **Expected:** locks and leader election use `now()` in SQL, so they are
  immune. Timers: `fire_at` is computed on the node from `workflow.currentTime()`
  (a side effect memo) — check whether a skewed node fires `sleep(60s)` 150 s
  late or 30 s early, and whether `getDueTimers(now)` is passed a node `Instant`.
  `completed_at` for retention is node-side too; a +90 s skew is harmless, a
  +30 d skew would purge early.
- **Harness:** `libfaketime` in one node container.

---

## 2. Connection pooling and shared databases

### S2.1 PgBouncer in transaction-pooling mode (P1)

- **Architecture:** every service connects via PgBouncer `pool_mode=transaction`.
- **Faults in play:** `LISTEN` needs a session, so `PostgresWakeListener` gets a
  different backend each poll and never receives notifications; `FOR SHARE` /
  `FOR UPDATE SKIP LOCKED` are fine inside a transaction; the one connection
  the wake listener pins is held for the app's lifetime and *is* a PgBouncer
  client slot.
- **Expected:** with `maestro.signal.wake-notify.enabled=false` the system
  degrades to the 30 s recheck and everything else holds. With the flag left
  on, the listener logs reconnects forever: measure whether that is a WARN
  storm or one line per backoff.
- **Pin:** `MultiNodeSignalRoutingIT` behind a PgBouncer Testcontainer in
  transaction mode: with wake-notify on, the 15 s bound fails *and* the log is
  bounded; with it off, the 30 s recheck path delivers.
- **Deliverable:** startup detection — `SHOW pool_mode` is PgBouncer-specific,
  but `SELECT pg_backend_pid()` differing across two statements on one
  connection is a cheap heuristic to WARN "transaction pooling detected; set
  wake-notify.enabled=false".

### S2.2 One database, many services, one schema each (P2)

- **Architecture:** three services share a Postgres instance with
  `currentSchema=svc_a|svc_b|svc_c`.
- **Faults:** `NOTIFY` channels are database-global, not schema-scoped. The
  per-service channel name (`maestro_signal_wake_<svc>_<sha8>`) handles the
  normal case; the test is two services configured with the **same
  `maestro.service-name`** by mistake, or a service whose name sanitises to the
  same 32 chars as another and differs only in the hash.
- **Expected:** same-name services cross-wake each other (harmless re-read,
  wasted work) and share a timer-leader key — **only one of the two services'
  timers fires**. That is a real outage caused by a config typo.
- **Pin:** two engines, same service name, different schemas, each with a
  `sleep(1s)` workflow: both must complete. Today one will stall until the
  other's leader lease lapses. Design outcome: scope the leader key by database
  identity (`current_database()` + schema) or fail startup when two nodes with
  the same service name report different schemas.

### S2.3 Hikari pool starvation caused by the engine (P2)

- **Architecture:** `maximumPoolSize=10`, Maestro and a chatty web layer share it.
- **Fault:** 200 workflows resume at once after a deploy; each activity takes
  a connection for its memo write, the recovery scan takes one, the lock
  renewer takes one every 10 s per workflow, the wake listener pins one.
- **Expected:** `connectionTimeout` exceptions (no SQLSTATE) surface as
  `StoreUnavailableException`. An activity's outcome append is retried in
  place (three attempts, 200 ms then 1 s); a timeout that outlasts that
  abandons the run as `STORE_UNAVAILABLE` (`WARN`, nothing written, nothing
  compensated, instance left active) and the next recovery pass resumes it →
  abandon churn, not FAILED (Issue 34; pinned on real Postgres by
  `StoreOutageMidStepIT`). The activity whose outcome was lost runs again on
  recovery, so with idempotent activities the churn repeats work but loses
  nothing. Measure whether a renewer timeout is classified as *transient* (keep lease) or *lost* (drop
  lease): the contract says throw on transient, return false only on lost;
  verify the JDBC lock's `UPDATE` timing out does the former.
- **Pin:** `MultiNodeLockContentionIT` variant with `maximumPoolSize=3` and 50
  concurrent resumes: no `LEASE_FENCED` caused by pool timeouts alone, and the
  task-queue limits (`maestro.worker.task-queues[].concurrency`, or
  `maestro.worker.default-concurrency` for unlisted queues) are shown to be the
  right knob.
- **Architecture note (2026-11-11, bounded host load).** Since this scenario
  was written, the engine's side of it is bounded by default; the scenario
  still needs running, but against these mechanics:
  - *The 200 resumes no longer run at once.* Recovery adopts all 200 but each
    task queue not listed in `maestro.worker.task-queues` runs at most 20
    workflow runs and 20 live activities per node
    (`maestro.worker.default-concurrency` / `default-activity-concurrency`);
    the rest queue on their own threads, holding their instance locks
    (`docs/open-issues.md` Issue 30). Both defaults `0` restores the
    unbounded behaviour this scenario describes.
  - *The renewer is one statement per 500 held locks every 10 s*, not one per
    workflow, on one connection.
  - *The recovery scan reads this service's active instances only, in pages of
    200*, with one advisory lock read per page instead of one acquisition per
    instance held elsewhere.
  - *The renewer-timeout question is answered:* the Postgres `renewAll` wraps
    any `SQLException` — a pool `connectionTimeout` included — as
    `LockBackendException`, and the renewer then keeps every handle for the
    next pass (transient, not lost). Pinned by
    `PostgresDistributedLockTest.renewAllWrapsBackendFailure` and
    `WorkflowInstanceLockManagerTest.backendFailureLosesNothing`.
  - *The engine can have its own pool.* A `DataSource` qualified
    `@MaestroDataSource` takes all of Maestro's traffic — the wake listener's
    pinned connection included — off the web layer's pool
    (`docs/configuration.md` § A dedicated connection pool; size it ≥
    `default-activity-concurrency` + 5). Starvation is then confined to one
    side: the scenario should be run both shared and split.
  - Per-node statement counts are in `docs/operations.md` §13 (arithmetic, not
    measurement).

---

## 3. Kafka topologies

### S3.1 Consumer-group rebalance storm during a rolling deploy (P1)

- **Architecture:** 6 nodes, one consumer group per service, signals arriving
  at 50/s during a 6-step rolling restart.
- **Expected:** every signal is delivered at least once (Kafka) and persisted
  once per delivery. Maestro has **no signal idempotency key**
  (`docs/open-issues.md` Issue 32 notes), so a redelivered record creates a
  twin `maestro_workflow_signal` row. A workflow with one `awaitSignal` consumes
  one and leaves the twin unconsumed (retention eventually purges it). A
  workflow with **two sequential `awaitSignal` of the same name** consumes both
  twins as if two business events happened — INV-SIGNAL in spirit.
- **Measure:** twin-row count after the deploy; workflows whose second
  same-name await was satisfied by a twin.
- **Pin:** chaos `KILL9` already causes redelivery; add an invariant I6 "no two
  signal rows with identical `(workflow_id, signal_name, payload, trace_context)`
  consumed by the same instance", and a scenario-10-style E2E that kills the
  consumer between `poll()` and the persisted ack.
- **Design outcome:** carry the Kafka `(topic, partition, offset)` or a
  producer-side idempotency key on the signal row with a unique index.

### S3.2 Broker unreachable at startup vs. mid-flight (P1)

- **Startup:** topic verification is inconclusive → WARN, containers tolerant;
  `KafkaTopicVerificationStartupTest` covers the missing-topic case but not
  "broker returns after 5 minutes". Pin that the containers *do* start
  consuming when the broker returns, without a restart.
- **Mid-flight:** broker down for 10 minutes while workflows run. Lifecycle
  publishes go to the bounded publisher and are **dropped** after the 1000-deep
  queue fills (admin dashboard loses history; `dropped()` counter climbs).
  Signals to other services, published by activities via the host's
  `KafkaTemplate`, fail inside the activity → retry policy → eventually
  `ActivityExecutionException` → saga compensation. That is the right
  outcome, but check the retry budget defaults make a 10-minute outage survivable.
- **Measure:** dropped lifecycle events; workflows that reached FAILED purely
  because of broker downtime shorter than their retry budget.

### S3.3 Partition reassignment lag after node death (P2)

- **Fault:** `KILL9` of the node owning partition 3; `session.timeout.ms` is
  45 s by default in Spring Kafka 4.
- **Expected:** admin commands and cross-service signals routed to partition 3
  wait ≥45 s (Issue 33). Workflows parked on those signals are correct but
  slow; the recheck interval (30 s) does not help because the signal has not
  been persisted yet.
- **Pin:** E2E scenario 10 already waits for reassignment; add the latency as a
  reported metric and a documented tuning (`session.timeout.ms`,
  `heartbeat.interval.ms`) in operations.md.

### S3.4 Dead-letter topic as the only evidence (P3)

- **Fault:** a poison signal payload (unknown workflow type in the envelope)
  exhausts redelivery and lands on `.DLT`.
- **Expected:** the workflow that needed the signal parks forever. Nothing in
  the engine correlates "DLT record for workflow X" with "X is parked on that
  signal". Measure time-to-notice with only the shipped meters.
- **Deliverable:** a `maestro.signal.dead_lettered{service}` counter and a
  runbook query joining the DLT record's workflow id to `WAITING_SIGNAL`.

---

## 4. Node lifecycle beyond kill -9

### S4.1 Stop-the-world pause longer than the lock TTL (P1)

- **Fault:** `SIGSTOP` a node for 45 s (GC pause, VM live-migration, debugger)
  then `SIGCONT`. Its lock renewals missed; another node adopted; the paused
  node resumes **mid-activity with a stale lease**.
- **Expected:** the resumed activity completes and its append is fenced
  (`LEASE_FENCED`); if the activity had an external side effect it ran twice
  (documented). The `PAUSE_RESUME` chaos action does this for ≤ TTL; raise it
  past the TTL deliberately and assert exactly one persisted result per
  sequence and the stale node's stand-down.
- **Pin:** `MultiNodeFencingIT` variant with a real pause instead of `DyingLock`.

### S4.2 Graceful shutdown that exceeds `maestro.shutdown.timeout` (P2)

- **Fault:** a 90 s activity in flight, shutdown timeout 30 s, orchestrator
  sends SIGKILL at 30 s.
- **Expected:** the shutdown path marks nothing FAILED (`ExecutorShutdownException`
  is a control-flow `Error`); the kill truncates the activity; recovery
  re-runs it. Check that the slot waiters not interrupted on shutdown
  (Issue 29) do not hold the JVM past the orchestrator's grace period and that
  the health endpoint reports "stopping" so the load balancer stops routing
  starts.
- **Measure:** startWorkflow calls accepted after shutdown began.

### S4.3 Scale-to-zero and cold start (P2)

- **Architecture:** Knative / KEDA scales the service to zero overnight;
  20,000 workflows are parked on timers due 06:00.
- **Expected:** the first pod up is the timer leader and fires timers in
  batches; its recovery scan returns 20,000 rows; admission is not capacity
  gated (Issue 30) so every due workflow becomes a virtual thread at once; the
  per-queue `concurrency` limit is per node, and there is one node.
- **Measure:** time to first fired timer, peak heap, DB connection waits, and
  whether a second pod joining mid-storm adopts anything (the first holds every
  lock) — i.e. whether scale-out helps at all during the storm.
- **Harness:** a single-node run with 20k pre-seeded `WAITING_TIMER` rows due
  simultaneously; this is the "recovery scan at scale" gap named in the
  assessment.

### S4.4 Mixed-version fleet during a history-layout change (P1)

- **Fault:** half the nodes run a build whose `awaitSignal` writes the two-slot
  layout, half the older one-slot layout, for one hour.
- **Expected:** old nodes meeting new events stand down `UNKNOWN_EVENT_TYPE` /
  `NONDETERMINISTIC_HISTORY` and re-adopt every 60 s (churn by design); new
  nodes read old events fine. INV-TERMINAL must hold: no workflow FAILED for
  version reasons. Measure the churn cost (CPU, replay reads) per stuck
  workflow per hour, and whether an old node can ever *win* the lock for a
  workflow it cannot run and hold it for the TTL, starving the new nodes
  (INV-LIVE).
- **Harness gap:** the stand-down tests simulate history by SQL edit. Build a
  real two-build cluster: the loan E2E jars at two commits, mixed per role.
  This is the largest single credibility gap left in the operations story.

---

## 5. Data and payload evolution

### S5.1 Result class renamed between deploys (P1)

- **Fault:** `OrderResult` → `FulfilmentResult`; 500 workflows in flight have
  `ACTIVITY_COMPLETED` payloads of the old shape.
- **Expected:** replay deserialisation fails → `UNKNOWN_EVENT_PAYLOAD` →
  stand-down churn, zombies visible only as a counter. No FAILED, no data loss.
  Measure how an operator finds *which* class, and whether `workflow.version()`
  gating is even applicable (it is not — the payload, not the code path,
  changed).
- **Deliverable:** a documented evolution recipe (Jackson `@JsonAlias`, keep
  the old type on the classpath for a release, or a payload migrator), and a
  stand-down detail that names the target type (R17-safe: type name, not value).

### S5.2 Host changes global Jackson configuration (P2)

- **Fault:** the web team enables `FAIL_ON_UNKNOWN_PROPERTIES` or a naming
  strategy on the shared `ObjectMapper`.
- **Expected:** durable history written under the old settings becomes
  unreadable → S5.1's zombies, with no deploy of Maestro code. Argument
  fingerprints computed under the new settings differ for identical values if
  the strategy changes property names → `NONDETERMINISTIC_HISTORY` on every
  recovery.
- **Pin:** `ArgumentDriftStandDownIT` variant that swaps the mapper's naming
  strategy between the two nodes: the stand-down must name arguments, and the
  remedy text must mention mapper configuration.
- **Design outcome:** an engine-owned, frozen `ObjectMapper` for durable
  payloads (assessment follow-up).

### S5.3 Payloads at the JSONB limit and TOAST (P3)

- **Fault:** an activity returns a 50 MB result.
- **Expected:** insert succeeds (TOAST), every replay re-reads 50 MB per
  recovery, `getEvents` for admin retry loads them all. Measure replay time per
  recovery for a 30-step workflow with 5 MB results each; decide whether a
  size WARN threshold belongs in the proxy.

---

## 6. Sagas and compensation under partial failure

### S6.1 Compensation that itself fails durably (P1)

- **Fault:** `refundPayment` compensation raises for 24 hours (payment
  provider outage).
- **Expected:** `COMPENSATING` → `COMPENSATION_STEP_FAILED` → `FAILED`,
  compensated-not-retryable. Measure whether an operator can re-drive
  *only* the compensation later; today `retryWorkflow` refuses compensated
  workflows, so a provider outage during compensation leaves money
  un-refunded with no engine-supported path back.
- **Deliverable:** decide between a "retry compensation" admin command and a
  documented manual procedure; pin whichever is chosen.

### S6.2 Adoption between a step and its compensation registration (P2)

- **Fault:** node A completes `reserveInventory` (memo persisted), is killed
  before the workflow continues; node B adopts, the next step fails, saga
  unwinds.
- **Expected:** B's replay of `reserveInventory` re-registers the compensation
  from the memo'd arguments, so `releaseInventory` runs exactly once with the
  right arguments (INV-COMP). The argument fingerprint now guards the inputs;
  check the compensation arguments are the *replayed* ones, not re-evaluated
  live values.
- **Pin:** a `SagaManagerTest` with a kill between memo and continuation, and
  an E2E (loan scenario 4 variant) with the kill placed by the chaos
  controller at that exact boundary.

### S6.3 Parallel branches fail on different nodes (P3)

- **Fault:** fork of three branches; branch 2's activity is in flight on a
  node that dies; the adopter's branch 2 collides or is fenced while branch 3
  fails for real.
- **Expected:** the fenced branch outranks the real failure (fixed in #38) so
  the stand-down is `LEASE_FENCED`, and on re-adoption the real failure
  surfaces and compensation covers exactly the branches that completed.
- **Pin:** extend `ParallelCompensationRegistrationOrderTest` into a two-node IT.

---

## 7. Retention and operator actions racing the engine

### S7.1 Retention on a long-lived loop workflow (P2)

- **Fault:** a subscription workflow sleeps 30 days per iteration for three
  years; `completed-retention=30d` does not apply (it is active) but its event
  log grows to ~75 slots per year — fine — while its **lock renewals** and
  recovery scans continue forever.
- **Expected:** nothing is purged (INV-RETAIN). Measure the fixed cost per
  long-lived parked workflow (one renew per 10 s, one row per scan per node)
  and extrapolate to 100k subscriptions: this is the case for continue-as-new
  or for releasing the instance lock while parked.

### S7.2 Admin retry racing a purge, and retry on a purged FAILED (P3)

- **Fault:** FAILED at `failed-retention − 1 s`; dashboard Retry arrives as the
  purge batch selects it.
- **Expected:** `FOR UPDATE SKIP LOCKED` or the guarded delete makes exactly
  one of them win; the loser gets `NOT_FOUND` or `OptimisticLockException`.
  Pin both orders. The dashboard projection still shows the workflow with a
  Retry button after the purge — document or project a tombstone.

### S7.3 Terminate during compensation on another node (P3)

- **Fault:** operator terminates while node B is mid-`COMPENSATING`.
- **Expected:** `terminateWorkflow` is unfenced and wins the CAS; B's next
  fenced write fails (`version` moved, epoch same) → `OptimisticLockException`
  path, not `LEASE_FENCED`; B stops; compensation is half done and the
  workflow is `TERMINATED`. That is by design, but the half-compensated state
  needs a visible marker for the operator.

---

## 8. What the harness needs to run these

| Capability | Scenarios | Cheapest form |
|---|---|---|
| Delete the last K committed rows of a running instance while Postgres is paused | S1.1 | new `BACKEND_OUTAGE` sub-action in `ChaosController` |
| Drop all lock rows and bump epochs during a pause | S1.2 | same sub-action, different SQL |
| Streaming-replication pair with scripted promotion | S1.1, S1.2 proper | two Postgres Testcontainers + `pg_basebackup`; or Patroni image |
| PgBouncer container in transaction mode in front of the loan DB | S2.1 | Testcontainers `edoburu/pgbouncer` |
| Two engines, same service name, different schemas | S2.2 | `MultiNode*IT` fixture flag |
| Pool size clamp on nodes | S2.3 | env var in `ChaosCluster.nodeEnv` |
| Kill between `poll()` and ack | S3.1 | a `@MaestroSignalListener` test hook or Toxiproxy latency on the ack |
| Pause longer than lock TTL | S4.1 | `PAUSE_RESUME` with duration > 30 s; assert `LEASE_FENCED` |
| 20k pre-seeded due timers, one node | S4.3 | SQL seed step before boot |
| Two builds in one cluster | S4.4 | loan jars from two commits, `ChaosCluster` role→jar map |
| Mapper settings differing per node | S5.2 | node env toggling a Boot Jackson property |
| Chaos action placed at an exact engine boundary | S6.2 | a test-only `EngineObserver` that trips the controller on a named callback |
| Clock skew on one node | S1.4 | `libfaketime` in the node image |

## 9. Suggested order

1. **S4.1, S1.2, S2.1** — cheapest, each exercises a guarantee shipped this
   cycle (fencing, lock loss under outage, wake fallback) in a form the
   current harness nearly supports.
2. **S3.1, S1.1** — the two scenarios most likely to show a real INV-SIGNAL
   or INV-LOG hole; both point at the same fix (atomic memo + consumed flip,
   signal idempotency key).
3. **S4.4** — the mixed-version cluster, the biggest remaining credibility gap.
4. **S4.3, S7.1** — the scale questions that decide whether recovery-scan
   filtering and continue-as-new are the next engine features.
5. Everything else as pins once the fixes above exist.

---

## 10. Real-world domains to build examples and demos from

The three shipped samples (order fulfilment, payment gateway, loan
origination) are all commerce-shaped: a few steps, one saga, one or two
signals. The domains below are chosen because each one forces a **different
engine feature to carry the plot**, and each maps onto specific scenarios
above so a demo can end with "now kill this node / pause this database and
watch the invariant hold". An agent building one should follow the
conventions of `maestro-samples/` (one Spring Boot module per service,
`@DurableWorkflow` + `@Activity` interfaces, Testcontainers tests, a
`docker-compose.yml` with a `kafka-init` that pre-creates every topic and its
`.DLT`, a README with the demo script) and keep external systems as in-process
fakes with injectable failure modes.

Each entry: the story, the workflow outline, the engine features it leans on,
the fakes it needs, the scenarios it rehearses, and the one demo moment.

### D1 Travel booking — the textbook saga

- **Story:** book flight + hotel + car as one trip; any leg failing releases the
  others.
- **Outline:** `parallel(reserveFlight, reserveHotel, reserveCar)` →
  `chargeCard` → `issueItinerary`; each reservation has a `@Compensate` release.
- **Leans on:** parallel branches with per-branch sequence space, saga
  compensation across branches, retry policy on flaky providers,
  `ActivityExecutionException.causedBy(...)` for "sold out" vs "provider down".
- **Fakes:** three provider APIs with configurable latency, 5xx rate and a
  "sold out" business rejection; a card gateway that can decline.
- **Rehearses:** S6.3 (branch fails on another node), S4.1 (pause past TTL
  mid-branch), S3.2 (provider = Kafka-backed partner).
- **Demo moment:** kill the node while the hotel reservation is in flight; the
  adopter re-runs that one leg, nothing else, and a forced card decline
  releases all three.

### D2 Subscription billing — the years-long loop

- **Story:** a subscription bills monthly for the life of the customer, with
  dunning (retry card on day 1, 3, 7, then suspend) and plan changes arriving
  mid-cycle.
- **Outline:** loop { `sleep(untilNextCycle)` → `computeInvoice` →
  `chargeCard` (retry via timers) → on exhaustion `suspend` and
  `awaitSignal("payment-updated", 30d)` } ; `awaitSignal("plan-changed")`
  handled between cycles via `workflow.version()` when pricing logic changes.
- **Leans on:** long timers across deploys and DB maintenance, `version()`
  gating for in-flight runs, signal-or-timeout, determinism discipline
  (`currentTime()` not `now()`), and it is the motivating case for
  continue-as-new.
- **Fakes:** card gateway with a per-customer failure script; a clock you can
  advance (reuse `maestro-test`'s controllable clock for the unit tests and a
  `sleep` scale factor for the live demo).
- **Rehearses:** S7.1 (fixed cost of parked long-lived runs), S4.4 (version
  gate under a mixed fleet), S1.4 (clock skew vs timers), S5.1 (invoice class
  renamed with runs in flight).
- **Demo moment:** deploy a pricing change behind `version()` while 1,000
  subscriptions are parked; show old runs finishing on the old rule and new
  cycles on the new one, then roll the deploy back without a single FAILED.

### D3 Customer onboarding / KYC — human in the loop

- **Story:** account opening with identity verification, document upload,
  manual review when the automated check is ambiguous, and regulatory
  deadlines (reject if not completed within 30 days).
- **Outline:** `collectDetails` → `screenSanctions` → if ambiguous
  `awaitSignal("reviewer-decision", 5d)` with escalation `sleep(2d)` reminders
  via a nested parallel branch → `awaitSignal("documents-uploaded", 30d)` →
  `openAccount`; `@QueryMethod status()` for the UI.
- **Leans on:** signals arriving before the await (pre-delivery orphans
  adopted at start), signal timeouts as business outcomes, queries answered on
  the owning node (and the `status()` fallback for the LB case), admin
  "send signal" from the dashboard.
- **Fakes:** a sanctions screener with an "ambiguous" outcome rate, a reviewer
  UI that posts signals, a document store.
- **Rehearses:** S3.1 (twin signals from a rebalance consuming two awaits), S3.4
  (poison signal to DLT leaves the application parked), S7.2 (retry vs purge of
  a rejected application).
- **Demo moment:** send the reviewer's decision *before* the workflow reaches
  the await, on a different node, and show it is consumed instantly when the
  await is reached.

### D4 Insurance claim — fan-out assessment with a late veto

- **Story:** a motor claim fans out to repair estimate, police report fetch and
  fraud scoring; payout proceeds unless fraud vetoes within 48 h.
- **Outline:** `registerClaim` → `parallel(getEstimate, fetchPoliceReport,
  scoreFraud)` → `awaitSignal("fraud-veto", 48h)` → `approvePayout` →
  `pay`; `@Compensate` on `approvePayout` reverses the reserve.
- **Leans on:** parallel with heterogeneous branch durations, timeout as the
  happy path (no veto), compensation after a successful payout approval, the
  999-step branch budget when estimates iterate.
- **Fakes:** an estimator that returns in 1 s or 1 h, a police-records service
  that is down nightly, a fraud model with a veto rate.
- **Rehearses:** S6.2 (adoption between a branch memo and compensation
  registration), S2.3 (pool starvation with many claims fanning out), S5.3
  (large PDF payloads in the police report step).
- **Demo moment:** take the police-records fake down for the demo's "night";
  claims park on retries, nothing fails, and the morning catch-up shows the
  recovery storm metrics (S4.3 in miniature).

### D5 Telecom number porting — strict SLAs across carriers

- **Story:** port a number from carrier A to B within the regulator's 24 h
  SLA; each carrier confirms over an asynchronous channel.
- **Outline:** `validateRequest` → `notifyLosingCarrier` →
  `awaitSignal("losing-carrier-ack", 4h)` → `scheduleCutover` →
  `sleep(untilCutover)` → `parallel(activateOnB, deactivateOnA)` →
  `awaitSignal("customer-confirms", 24h)`; every step has a regulatory
  timer and a compensating "roll port back".
- **Leans on:** cross-service choreography through `@MaestroSignalListener`
  (carrier A and B are separate services with their own Maestro), timers as
  SLA clocks, saga roll-back of a half-completed cutover, lifecycle events for
  an audit trail.
- **Fakes:** two carrier services with slow and lost acks.
- **Rehearses:** S3.3 (partition reassignment lag eating into the SLA), S1.1
  (failover losing the ack memo), S7.3 (operator terminate during roll-back).
- **Demo moment:** cross-node signal wake: the ack arrives on node 3 while the
  port is parked on node 1; show the sub-second wake, then disable
  `wake-notify` and show the 30 s fallback.

### D6 SaaS tenant provisioning — an infrastructure saga

- **Story:** provision a tenant: database schema, object-storage bucket, DNS
  record, IdP client, welcome e-mail; tear everything down if any step fails.
- **Outline:** sequential create steps each with `@Compensate` delete;
  `awaitSignal("dns-propagated", 1h)` driven by a poller service; final
  `sendWelcome` is non-compensable and idempotent.
- **Leans on:** idempotent activities with external identifiers (the
  at-least-once contract made visible), compensation ordering, activity
  timeouts (`startToCloseTimeout`) on cloud APIs, argument fingerprinting when
  the tenant request object is mutated by a step.
- **Fakes:** cloud APIs that return "already exists" on retry (idempotency),
  a DNS fake with propagation delay.
- **Rehearses:** S4.1 (pause past TTL during bucket creation → the adopter
  creates it again → idempotency key proves at-least-once is safe), S5.2
  (argument drift when a step mutates the request), S6.1 (delete fails for a
  day: how is teardown re-driven?).
- **Demo moment:** the exactly-once myth: show the duplicate bucket-create
  call in the fake's log after a kill, and the single persisted result.

### D7 Media transcoding pipeline — wide fan-out and the branch budget

- **Story:** an uploaded video is transcoded into N renditions, thumbnails
  extracted, captions generated, then published atomically.
- **Outline:** `probe` → `parallel(renditions × N, thumbnails, captions)` →
  `assembleManifest` → `publish`; retries on transient encoder failures;
  failure of any rendition fails the asset and compensates by deleting
  partial outputs.
- **Leans on:** many parallel branches (test the 999-step cap deliberately
  with a chunked loop inside a branch), activity concurrency limits per task
  queue (`activity-concurrency`), large payload discipline (store object keys,
  not bytes).
- **Fakes:** an encoder worker pool with configurable failure and duration.
- **Rehearses:** S2.3 (pool starvation under fan-out), S4.3 (scale-to-zero
  cold start with a backlog), S5.3 (payload size), `BranchSequenceExhausted`
  as a demo of a documented limit.
- **Demo moment:** set `activity-concurrency: 4` on one node and 16 on
  another; watch the task-queue governor shape throughput per node, then kill
  the busy node and watch its renditions resume elsewhere.

### D8 Incident response / on-call escalation — timers as the product

- **Story:** an alert opens an incident; page the primary, escalate after 10
  min, then the secondary, then the manager; auto-resolve when the monitor
  clears; post-mortem reminder 3 days later.
- **Outline:** `page(primary)` → `awaitSignal("acked", 10m)` → escalate loop
  → `awaitSignal("resolved")` → `sleep(3d)` → `remindPostmortem`.
- **Leans on:** many short timers, signal-vs-timeout races (`ack` arriving as
  the escalation timer fires), timer-leader failover, two-slot await layout
  and the `awaitSignal` timeout detail.
- **Fakes:** a paging provider, a monitor that flips.
- **Rehearses:** E2E scenario 9 (leader failover) under load, S1.4 (skew
  firing escalations early), S3.1 (duplicate ack signals).
- **Demo moment:** kill the timer-leader node with 200 incidents parked on
  escalation timers; show the new leader firing them within the TTL window and
  no incident escalating twice.

### D9 Logistics shipment — event-driven with out-of-order updates

- **Story:** a parcel passes through pickup, hub scans, customs, delivery
  attempts (max 3), and return-to-sender; carrier events arrive late, twice, or
  out of order.
- **Outline:** `createShipment` → loop over `awaitSignal("scan-event")` with
  state transitions guarded by sequence checks → `awaitSignal("delivered",
  7d)` → on timeout `returnToSender`.
- **Leans on:** the "never assume signal ordering" rule, duplicate-event
  handling in workflow code, many signals per instance, `@QueryMethod` for
  tracking pages, signal retention/janitor for terminal shipments.
- **Fakes:** a carrier feed that replays, reorders and duplicates events.
- **Rehearses:** S3.1 (twin rows), S7.1 (shipments that never deliver and park
  for 7 d), retention of consumed signals at scale (INV-RETAIN).
- **Demo moment:** replay a day of carrier events in random order and show the
  tracking state converging on every node identically.

### D10 Legal matter intake and trust accounting — compliance deadlines

- **Story:** a law firm opens a matter: conflict check, FICA/KYC document
  collection with statutory deadlines, trust deposit, fee-note approval, and a
  prescription-date reminder years out.
- **Outline:** `conflictCheck` → `awaitSignal("fica-complete", 30d)` →
  `recordTrustDeposit` (compensate: refund) → `parallel(draftEngagementLetter,
  openLedger)` → `awaitSignal("fee-note-approved")` → `sleep(untilPrescriptionReminder)`.
- **Leans on:** very long sleeps (years) alongside short SLAs, compensation of
  a financial step, audit-grade lifecycle events to the dashboard, retention
  policy choices (legal records must *not* be purged at 30 d — demonstrates
  `completed-retention` tuning per service).
- **Fakes:** a conflict database, a document portal, a trust ledger.
- **Rehearses:** S7.1 (years-long parked runs), S1.3 (PITR restore of a
  ledger with legal consequences), S5.1 (matter class evolution across years of
  deploys).
- **Demo moment:** show retention configured to never purge this service while
  the order service purges at 30 d, both on the same engine build.

### D11 IoT firmware rollout — canary, fleet fan-out, rollback

- **Story:** roll a firmware version to 10,000 devices in waves: canary 1 %,
  wait for health signals, proceed or roll back the wave.
- **Outline:** parent workflow per rollout drives child-like per-wave
  sub-flows (modelled today as sequential `parallel()` blocks of device
  activities, chunked under the branch budget) → `awaitSignal("wave-healthy",
  1h)` per wave → on timeout compensate the wave.
- **Leans on:** the absence of child workflows (a documented gap: this is the
  example that motivates them), fan-out limits, sagas with sequential
  `parallel()` calls (Issue 28: two sequential parallels cannot compensate —
  the example must be shaped around it and says so).
- **Fakes:** a device fleet that reports health with a failure rate per wave.
- **Rehearses:** S4.3 (20k devices becoming 20k activities), S7.1, and it is
  the natural benchmark for recovery-scan filtering.
- **Demo moment:** honest limits — show where the branch budget and the
  sequential-parallel compensation rule bite, and how the example is shaped
  to stay inside them.

### D12 Marketplace escrow and payout — money, idempotency, reconciliation

- **Story:** buyer pays into escrow, seller ships, buyer confirms or a dispute
  window elapses, funds are released minus fees; chargebacks can arrive weeks
  later.
- **Outline:** `holdFunds` → `awaitSignal("shipped", 5d)` →
  `awaitSignal("confirmed" | "disputed", 14d)` → `releaseFunds` /
  `refund`; a separate chargeback workflow signals the original by business id.
- **Leans on:** idempotency keys on every money movement (the at-least-once
  contract), signal by business id across workflows, late signals to
  completed workflows (orphans, retention), admin retry of a FAILED payout.
- **Fakes:** a payment processor with idempotency-key semantics and
  injectable timeouts after the money moved.
- **Rehearses:** S1.1 (failover after `releaseFunds` committed externally but
  before the memo), S7.2 (retry vs purge), S6.1 (refund provider outage).
- **Demo moment:** the processor fake times out *after* moving the money; the
  retry presents the same idempotency key and the ledger shows one movement.

### Coverage matrix

| Domain | Parallel | Saga | Long timers | Signals | version() | Cross-service | Queries | Scenarios |
|---|---|---|---|---|---|---|---|---|
| D1 travel | ●●● | ●●● | ○ | ○ | ○ | ○ | ○ | S6.3 S4.1 S3.2 |
| D2 billing | ○ | ○ | ●●● | ●● | ●●● | ○ | ● | S7.1 S4.4 S1.4 S5.1 |
| D3 KYC | ● | ○ | ●● | ●●● | ○ | ○ | ●●● | S3.1 S3.4 S7.2 |
| D4 claim | ●●● | ●● | ● | ●● | ○ | ○ | ○ | S6.2 S2.3 S5.3 |
| D5 porting | ● | ●● | ●● | ●●● | ○ | ●●● | ○ | S3.3 S1.1 S7.3 |
| D6 tenant | ○ | ●●● | ● | ● | ○ | ○ | ○ | S4.1 S5.2 S6.1 |
| D7 media | ●●● | ● | ○ | ○ | ○ | ○ | ○ | S2.3 S4.3 S5.3 |
| D8 incident | ○ | ○ | ●●● | ●●● | ○ | ○ | ● | E2E-9 S1.4 S3.1 |
| D9 shipment | ○ | ○ | ●● | ●●● | ○ | ● | ●●● | S3.1 S7.1 |
| D10 legal | ● | ● | ●●● | ●● | ○ | ○ | ● | S7.1 S1.3 S5.1 |
| D11 firmware | ●●● | ●● | ● | ●● | ○ | ○ | ○ | S4.3 S7.1 Issue 28 |
| D12 escrow | ○ | ●● | ●● | ●●● | ○ | ● | ● | S1.1 S7.2 S6.1 |

### Rules for anyone building one

1. **One module, one database, one Kafka topic set**, declared in the
   compose `kafka-init` with `.DLT`s; the sample must boot under the default
   `verify-on-startup=fail`.
2. **Every activity idempotent by construction**, with the idempotency key
   derived from `workflowId` + step, and the fake external system asserting it
   in its log; the README's first demo is always "kill the node, show one side
   effect".
3. **Workflow code deterministic**: `workflow.currentTime()` and
   `workflow.randomUUID()` only; any HashMap iterated in workflow code is the
   kind of bug D6 exists to demonstrate, so do it once, deliberately, and show
   the argument-fingerprint stand-down.
4. **Saga rule stated in the sample**: compensation runs when the workflow
   *throws*, not when it returns a failure object (the shipped order sample
   got this wrong once).
5. **A `DeterminismChecker` test and a `TestWorkflowEnvironment` test** per
   workflow, plus one Testcontainers test that kills the engine mid-step and
   recovers.
6. **A `demo.md`** with three scripted moments: a crash-recovery, a signal
   race, and the domain's own failure scenario from the table.
