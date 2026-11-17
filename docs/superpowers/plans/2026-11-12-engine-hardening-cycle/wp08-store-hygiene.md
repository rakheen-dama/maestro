# WP8 Store Hygiene Implementation Plan

> **As built (added by WP13, 2026-11-17).** Merged as PR #63 (`40aa3c21`). Where this plan and the code differ, the code is: the signal janitor stops after **1,000 batches per sweep** (`SignalJanitor.MAX_BATCHES_PER_SWEEP`; the plan's "No cap per sweep" below was overruled in review, R2); the manual purge for names no node runs is one data-modifying-CTE statement, verified on PostgreSQL 16 only; MD-1 to MD-6 were taken as recommended, so retention is scoped to the node's service and WP12 does not file its Issue 76 for it. The plan below is kept as written.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The engine's three housekeeping paths stop leaning on the host's database in bursts and stop touching other services' rows.
- **3.11a.** Between two batches of a retention sweep, the sweeper waits as long as the batch before it took, at most 5 s. A sweep then uses the database about half the time it runs, instead of running up to 300 large transactions back to back.
- **3.11b.** The signal janitor deletes in batches of 1,000 through a new default `WorkflowStore` overload, paced the same way, and only signals of workflows recorded under this node's `maestro.service-name`.
- **Issue 39.** Once per startup, off the `ApplicationRunner` thread, each node runs one advisory query and logs one `WARN` per other `service_name` that holds active instances of a workflow type this node runs. The check never fails or delays startup, and never writes.
- **MD-1 (recommended).** The retention sweep becomes service-scoped too, through a second default overload, so a node no longer purges another service's history with its own cutoffs (WP12's Issue 76 candidate).

**Architecture:** All new logic is in `maestro-core` (no Spring) and the store modules.
- `BatchPacer` (new, package-private) measures each batch on the monotonic clock and waits on a stop latch. `requestStop()` counts the latch down, so a pause ends at once without an interrupt. The sweeper and the janitor each own one.
- The SPI gains three `default` methods: the scoped, bounded janitor delete; the scoped purge (MD-1); and the advisory count. Their defaults keep today's behaviour (unscoped and unbounded, or "nothing to report"), so third-party stores keep compiling and working. The JDBC store and `InMemoryWorkflowStore` override all three.
- `ForeignServiceCheck` (new, package-private) runs the advisory query once on its own virtual thread. `WorkflowExecutor.startForeignServiceCheck(registrations)` starts it, and `StartupRecoveryRunner` calls that and returns.

No migration, no configuration property, no meter. Every new statement keeps the leading text `perf/sql/pgss-window.sql` matches (D-9).

**Tech Stack:** Java 25, `maestro-core`, `maestro-store-jdbc`, `maestro-store-postgres` (Testcontainers `postgres:16-alpine`), `maestro-test`, `maestro-spring-boot-starter` (Spring Boot 4.0.5, Logback `ListAppender` in tests), JUnit 5, Awaitility.

**Spec:** `docs/superpowers/plans/2026-11-12-engine-hardening-cycle/00-spec.md`: the WP8 row, §2 anti-goals, §3 ("3.11a is 300 batches, not 100 … 3.11b also has no leader election and no service filter"), §4 Lane B order (WP8 last, after WP6), §6 gates, D8 ("the filter is final, Issue 39's WARN is the mitigation"), C4. Evidence: `evidence/audit-C-host-integration-followups.md` §§ 3.11a, 3.11b, Issue 39, "Overlaps". Issues: `docs/open-issues.md` Issue 39 (and 31 shared tables, 64 loop interrupts, 76 reserved by WP12). Siblings:
- `wp04-loop-liveness-health.md`: `BackgroundLoopGuard` around the janitor and the sweeper (D-10 of this plan keeps it untouched).
- `wp06-recovery-capacity.md` Task 7 and its hand-off: `StartupRecoveryRunner` only starts loops. Issue 39's query runs off the runner thread.
- `wp12-finding-linked-scenarios.md` §4.4 (`s6-retention`), Task 9 and "Hand-offs → To WP8's planner": the statement texts and predictions this plan must keep or update (D-9, last section).

All line numbers below are for branch `fix/recovery-capacity` @ `14616968` (WP0–WP6 content, read-only worktree `.claude/worktrees/recovery-capacity`). Re-check them after cutting the branch from `origin/main` once WP6 has merged.

## Added, changed and removed public surface

**Added**

| Surface | Exact shape | Task |
|---|---|---|
| SPI default | `default int WorkflowStore.deleteUnconsumedSignalsForTerminalInstancesBefore(String serviceName, Instant cutoff, int limit)`. Deletes at most `limit` unconsumed signals of `COMPLETED`/`TERMINATED` instances of `serviceName` finished before `cutoff`, and returns the count. Same survivors as the one-argument form (`FAILED`, orphans, consumed rows, newer or unstamped instances), plus every other service's signals. **Default:** delegates to the unscoped, unbounded `deleteUnconsumedSignalsForTerminalInstancesBefore(cutoff)`, which is today's behaviour. Throws `NullPointerException` on a null `serviceName` or `cutoff`, and `IllegalArgumentException` when `limit < 1`. | 2 |
| SPI default (MD-1) | `default PurgeResult WorkflowStore.purgeTerminalInstancesBefore(String serviceName, WorkflowStatus status, Instant cutoff, int batchSize)`. The same contract as the three-argument form, restricted to instances whose `service_name` equals `serviceName`. **Default:** delegates to the unscoped three-argument form. | 4 |
| Protected hook (MD-1) | `protected String AbstractJdbcWorkflowStore.getServicePurgeCandidatesSql()`. Four parameters in order: status, cutoff, service name, batch size. Default: the portable `… FETCH FIRST ? ROWS ONLY`. Postgres overrides it with `LIMIT ? FOR UPDATE SKIP LOCKED`. | 4 |
| SPI default | `default Map<String, Long> WorkflowStore.getActiveInstanceCountsUnderOtherServices(String serviceName, Set<String> workflowTypes, int limit)`. For each `service_name` other than `serviceName` that holds active instances (`WorkflowStatus.isActive()`) of a type in `workflowTypes`, it returns that name's count. At most `limit` entries, ordered by count descending, then name. It never writes. **Default:** `Map.of()`, meaning "nothing to report"; the check then logs nothing. An empty `workflowTypes` returns `Map.of()` without a query. | 5 |
| Executor method | `public void WorkflowExecutor.startForeignServiceCheck(Map<String, WorkflowRegistration> registrations)`. Returns at once. Runs Issue 39's check once on the virtual thread `maestro-foreign-service-check`. Never throws: when the executor is shutting down it logs at `DEBUG` and does nothing. | 6 |
| Log lines | One `WARN` per foreign name (D-6 gives the exact text). One `INFO` when more than 20 names exist. One `INFO` when the check could not run. Changed: the janitor's and the sweeper's per-sweep `INFO` lines gain `in {} batch(es)`, and the janitor's start line gains `batchSize` and `service` (D-8). None carries a workflow id. | 1, 3, 6 |

**Package-private** (named so that reviewers can check them; not public surface):
- `BatchPacer` (new) with:
  - `static final Duration MAX_PAUSE` (5 s);
  - nested `interface Wait { boolean await(CountDownLatch stop, Duration pause) throws InterruptedException; }` and `static final Wait LATCH`;
  - constructors `BatchPacer()` and `BatchPacer(LongSupplier nanoClock, Wait wait)`;
  - `long now()`, `static Duration pauseFor(long batchNanos)`, `boolean pauseAfter(long batchNanos)`, `void requestStop()` and `boolean stopRequested()`.
- `RetentionSweeper`: a constructor overload that takes a `BatchPacer`; the existing one delegates with `new BatchPacer()`.
- `SignalJanitor`:
  - the constructors gain `String serviceName` as their second parameter;
  - a constructor overload takes a `BatchPacer`;
  - `static final int BATCH_SIZE = 1_000`.
- `ForeignServiceCheck` (new):
  - `static Map<String, Long> run(WorkflowStore, String serviceName, Set<String> workflowTypes)`;
  - `static String warning(String ownService, String foreignService, long count)`;
  - `static final int MAX_REPORTED = 20`;
  - `static final String THREAD_NAME = "maestro-foreign-service-check"`.

**Changed behaviour** (no new configuration property, no migration, no meter):
- **Retention sweep pacing (3.11a).** Each batch after a sweep's first is preceded by a pause equal to the previous batch's duration, capped at 5 s. There is no pause after the last batch. Leadership is re-checked after the pause, right before the batch, as today. `max-batches-per-sweep`, `batch-size` and `interval` keep their meaning. A sweep of B batches of t each now spans about B·t + (B−1)·min(t, 5 s) (arithmetic).
- **Janitor (3.11b).** It deletes only this service's signals, ≤ 1,000 per statement, until a statement deletes fewer than 1,000, with the same pause between statements. It still runs on every node, with no leader (MD-6), first at startup, then every `maestro.signal.janitor-interval`.
- **Retention scope (MD-1).** A sweep purges only instances recorded under this node's `maestro.service-name`. Terminal rows under a name no node runs any more are no longer purged by anyone. Release note, `docs/operations.md` §11.5, and filed as Issue 77.
- **Startup (Issue 39).** One extra read statement per node start, on its own thread.
- **Third-party stores** that override none of the new methods behave exactly as today: unscoped janitor (looped until a short result, D-3), unscoped purge, and no Issue 39 warning.

**Removed:** nothing.

**Doc rule changes:** `CLAUDE.md`'s `WorkflowStore` block gains the three defaults; its recovery paragraph (`:221`) and configuration paragraph (`:246-254`) gain one sentence each (Task 7).

## Today's sweeper, janitor and startup, traced and measured

### Traced (read at `14616968`)

| Path | Where | What it does |
|---|---|---|
| Sweep entry | `RetentionSweeper.sweepOnce` (`RetentionSweeper.java:265-303`) | It walks three targets, COMPLETED, TERMINATED and FAILED, each with its own cutoff (`:308-313`). For each, up to `maxBatchesPerSweep` batches (`:272`, default 100, so **300 per sweep**). |
| Per batch | `:273-294` | It checks the stop flag (`:273`), then calls `trySetLeader` (`:277`, `isLeader` `:337-349`, 15 s TTL `:80`), so there is **one leader upsert per batch**. Then `store.purgeTerminalInstancesBefore(status, cutoff, batchSize)` (`:288`), and it breaks on a short batch (`:291`). **Nothing between batches**: the next statement follows at once. |
| Between sweeps | `sweepLoop` (`:351-381`) | `Thread.sleep(interval)` (`:374`, default 1 h). The first sweep runs at `start()`. |
| Stop | `requestStop` (`:188-195`) | Sets `stopRequested` and `running`, and **interrupts** the thread (Issue 64, still open for this loop). |
| One batch | `AbstractJdbcWorkflowStore.purgeTerminalInstancesBefore` (`AbstractJdbcWorkflowStore.java:1179-1241`) | One transaction. The candidate select (`:1244-1258`; Postgres `PostgresWorkflowStore.java:150-157`: `status = ? AND completed_at < ? ORDER BY completed_at LIMIT ? FOR UPDATE SKIP LOCKED`) **has no `service_name` filter**. Then it deletes signals, timers and events by instance id, then the instances (re-checking status and cutoff), and commits. It holds row locks on ≤ `batchSize` instances and all their child rows until commit. |
| Janitor | `SignalJanitor.sweepOnce` (`SignalJanitor.java:229-244`) | **One** call per sweep: `store.deleteUnconsumedSignalsForTerminalInstancesBefore(cutoff)` (`:233`). It runs on every node, with no leader (`:28-33`). It runs at start, then every `janitor-interval` (`:248-278`). |
| Janitor SQL | `AbstractJdbcWorkflowStore.java:1650-1660` | `DELETE FROM maestro_workflow_signal WHERE consumed = FALSE AND workflow_instance_id IN (SELECT id FROM maestro_workflow_instance WHERE status IN ('COMPLETED', 'TERMINATED') AND completed_at < ?)`: **no limit, no `service_name`**, one statement and one transaction. |
| Indexes | V2 `idx_wf_instance_recoverable` (status, active partial), `idx_wf_signal_pending` (`… WHERE consumed = false`); V3 `idx_wf_timer_lookup`; V8 `idx_wf_instance_terminal (status, completed_at) WHERE completed_at IS NOT NULL`, `idx_wf_signal_instance (workflow_instance_id)`; V101 `idx_wf_instance_recovery (service_name, started_at, id)`, active partial | None leads with `service_name` for terminal rows. |
| Startup | `StartupRecoveryRunner.run` (`StartupRecoveryRunner.java:54-105`) | Starts recovery (the poller, or the one-shot pass), the timer poller, the janitor (`:80`) and the sweeper (`:89`), and returns. Nothing looks at other `service_name`s anywhere (Issue 39). |
| Recovery scope | `AbstractJdbcWorkflowStore.getRecoverableInstances(String, RecoveryCursor, int)` (`:464-486`) | `service_name = ?`. Only this service's active rows are adopted. The filter is final (D8). |

**Service scope today, answered:** neither the retention purge nor the janitor is service-scoped. Every service sharing the tables (Issue 31) runs its own leader-elected sweeper (one leader key per service) and an unelected janitor on every node, and each deletes every service's qualifying rows with its own cutoffs. With two services whose `failed-retention` is 7 d and 90 d, the 7 d service purges the other's `FAILED` workflows after 7 days, so the other's Retry answers `NOT_FOUND` (traced, not tested). 3.11b's "service-scoped" requires the janitor's delete to join the instance and filter `i.service_name = ?` with this node's `maestro.service-name`. MD-1 extends the same to the purge.

### Measured at planning time (2026-11-17)

**Setup.** A throwaway Postgres 16 container started for the probe and removed afterwards, with the shipped V1–V9 and V101 applied. Seeded:
- 412,500 instances: 400,000 terminal, spread over 60 days; three-quarters `perf`, one-quarter `other`; 12,500 active across `perf`, `other` and `perf-old`;
- 1.6 M events;
- 509,000 signals: one consumed per instance, an unconsumed one on every fifth terminal instance and on every active one, and 2,000 orphans;
- `VACUUM ANALYZE`.

`EXPLAIN (ANALYZE, BUFFERS)` and timed `plpgsql` loops gave:

| # | Statement | Plan and time |
|---|---|---|
| Q1 | Today's janitor `DELETE` (rolled back) | Hash join: seq scan of the instance table (314,646 qualifying rows) against the 94,500 unconsumed signals from `idx_wf_signal_pending`. **342 ms, 69,921 rows in one statement**, both services' signals. |
| Q2 | Batched and scoped janitor `DELETE … id IN (SELECT … LIMIT 1000)` (D-3's SQL), cold cache | Custom plan: seq scan of signals plus a PK probe of the instance, 111 ms. Generic plan: seq scan of instances plus a probe of `idx_wf_signal_instance`, 75 ms. |
| Q2-drain | The same statement in a loop until a short batch, as the janitor will run it | **53 batches, 52,440 rows, 700 ms in total**; p50 8 ms, max 188 ms (the last, short batch). |
| Q2-steady | The final, empty batch after the backlog is gone | Custom 149 ms (scans the 42,060 remaining unconsumed signals). Generic **795 ms** (227,245 index probes). Today's unscoped statement on the same state: 194 ms. |
| P | Retention batch (500 `COMPLETED` instances with history), 40 batches | p50 **45 ms**, max 181 ms, 2.2 s in total. **27 MB of WAL for 20,000 instances.** |
| Q3 | Scoped candidate select (`… AND service_name = ?`) | Same index (`idx_wf_instance_terminal`), plus a filter. 0.57 ms with 164 rows filtered. |
| Q3b | Scoped candidate select where a co-tenant keeps longer retention (this service `FAILED` at 7 d, the co-tenant's rows ahead of it in the index) | **86 ms per batch**, 34,960 co-tenant rows read and filtered (Issue 78). |
| Q4 | Issue 39's query (D-6) | Bitmap scan of `idx_wf_instance_recoverable` over the 12,500 active rows: **2.97 ms**. Result `{perf-old=500}`. |
| — | `FETCH FIRST $n ROWS ONLY` with a bind parameter inside the `IN (…)` subquery | Accepted by Postgres 16 (prepared and executed). |

**What I could not measure:**
- production-sized tables (10 M+ rows) and a real disk;
- WAL shipping and replication lag on a replica;
- autovacuum behaviour over hours;
- the foreground latency a sweep costs under load. That is WP12's `s6-retention`, which measures the DB-side busy fraction;
- when pgjdbc's server-side prepare (`prepareThreshold` 5) actually switches the janitor statement to the generic plan in a pool;
- whether Flyway 11 can run `CREATE INDEX CONCURRENTLY` in this initializer. No migration is planned (D-5), so this was not needed.

### What a large purge costs foreground work today (reasoning, not measured)

- **Locks.** A batch holds row locks on up to 500 instance rows and every child row it deletes, for the batch's duration (45–181 ms on the probe). It takes no table-level lock stronger than `ROW EXCLUSIVE`. The workflow traffic that can meet these rows is small:
  - a Retry of a `FAILED` candidate is skipped by `SKIP LOCKED`, or waits one batch;
  - a late signal's foreign-key check waits on the instance row, then fails and is redelivered (`docs/operations.md` §11.2).
- **WAL and I/O.** Back to back, a 300-batch sweep (150,000 instances) at the probe's rate writes ≈ 200 MB of WAL in ≈ 14 s (27 MB per 20,000 instances, 45 ms per batch; arithmetic). It also reads cold terminal pages into shared buffers, evicting hot pages. That WAL burst is what reaches replicas as lag and checkpoints as pressure.
- **Autovacuum.** Every deleted row is a dead tuple. A full sweep leaves ~150,000 instance, ~600,000 event and ~300,000 signal dead tuples. That is enough to cross the default 20 % scale factor on a mid-sized table and start an autovacuum cycle on each of the four tables during the sweep.
- **Pool.** One pool connection is busy for the sweep's whole span.
- **Janitor.** Today's janitor is one transaction holding row locks on every row it deletes (69,921 on the probe) until it commits, on every node at roughly the same time after a deploy. Peers' identical statements wait on those locks, then delete nothing.

3.11a's pause halves the busy share and spreads the WAL over twice the time. It reduces no total. That is the spec's intent (it says "pause", not "throttle to X").

## Decisions

### D-1. The pause rule (3.11a)

- **Rule.** In one sweep, every batch except the first is preceded by `pauseFor(d) = min(d, 5 s)`, where d is the previous batch's duration: the store call only, measured on `System.nanoTime()`. A pause of zero does not wait.
  - The first batch of a sweep is not paused.
  - Nothing is paused after the last batch: the loop's own `sleep(interval)` follows.
  - The status boundary counts as "between two batches": TERMINATED's first batch is paused for COMPLETED's last.
- **Why the previous batch, not a fixed pause.** The spec says "as long as the last batch took". It adapts to the host: a 5 ms batch costs a 5 ms pause, and a 2 s batch a 2 s pause. The database's share of the sweep stays ≈ 50 % whatever the batch size, disk or table width.
- **Cap: 5 s = `RetentionSweeper.LEADER_TTL / 3`.** Leadership is renewed only by the `trySetLeader` before each batch. The gap between two renewals is t + pause.
  - Today it is t, so a batch over 15 s loses the claim.
  - With an uncapped pause it would be 2t, so a batch over 7.5 s would lose it. That is half today's tolerance, and a pathological batch would also double an already-long sweep.
  - With the cap it is t + min(t, 5 s) ≤ 15 s for every t ≤ 10 s. The pause on its own never makes a sweep lose leadership when its batches are under 10 s.
  - 5 s is also the timer poller's renewal cadence (TTL/3). The cost: a batch of t > 5 s gets a duty cycle of t / (t + 5 s) > 50 %. That is acceptable, because the cap only matters for batches that are already pathological.
- **No floor.** A floor would delay empty sweeps (three sub-millisecond statements) for nothing. When batches are fast there is nothing to protect.
- **Leader loss.** The leader is re-checked after the pause, immediately before the batch, as today. A sweeper that lost the claim during a pause logs `Lost retention leadership … mid-sweep` and returns its partial total (`:283-285`), as today. Another node picks the sweep up at its own next interval. `SKIP LOCKED` keeps two leaders disjoint at a boundary, as today.
- **Interaction with the budget and the interval.**
  - `max-batches-per-sweep` still counts batches per status; the pause adds no batch.
  - The interval is still measured from the end of a sweep (`sleep` after `sweepOnce`), so sweeps never overlap on a node.
  - At the defaults (300 batches, the probe's 45 ms) a worst-case sweep spans ≈ 27 s, against ≈ 13.5 s today, writing the same ≈ 200 MB of WAL at half the rate (arithmetic). A sweep spanning more than `interval` simply starts its successor later.
- **Shutdown.** See D-2.

### D-2. `BatchPacer`: a stop latch, not an interrupt

- **State.** A `CountDownLatch stop` (count 1), a `LongSupplier nanoClock`, and a `Wait wait`.
- **`requestStop()`** counts the latch down. It never interrupts. It is idempotent and callable from any thread.
- **`pauseAfter(long batchNanos)`:**
  - if `stopRequested()`, it returns `false` at once;
  - otherwise it computes `pauseFor`. A zero pause returns `true` with no wait;
  - otherwise it calls `wait.await(stop, pause)` and returns `!signalled`;
  - an `InterruptedException` (the loop's existing `requestStop` still interrupts, Issue 64) restores the flag and returns `false`.

  So a pause ends at once on `requestStop()` with or without the interrupt. That makes it ready for Issue 64 dropping the interrupt.
- **`LATCH`** is `(stop, pause) -> stop.await(pause.toNanos(), NANOSECONDS)`. Production uses `new BatchPacer()` = `(System::nanoTime, LATCH)`.
- **Test seam.** The two-argument constructor: a fake clock advanced by the fake store, and a `Wait` that records the pause and returns at once, or wraps `LATCH` with an "entered" latch. No test sleeps.
- **Wiring.**
  - `RetentionSweeper.requestStop()` and `SignalJanitor.requestStop()` call `pacer.requestStop()` before their existing body.
  - The sweeper keeps its `stopRequested` flag and its per-batch check (`:273`), which still covers a store call that swallowed the interrupt.
  - The janitor uses `pacer.stopRequested()` the same way. Its `running` flag stays false for a janitor that was never started, so a direct `sweepOnce()` must not read `running` (the same reason the sweeper has `stopRequested`, `:101-106`).
- **Thread safety.** The latch and the clock are thread-safe. `sweepOnce()` stays "safe to call concurrently with the loop". Two concurrent pauses both end on the same `requestStop()`.

### D-3. The batched, service-scoped janitor (3.11b)

- **SPI.** The overload in the surface table. **Parameter order** `(serviceName, …, limit)` mirrors `getRecoverableInstances(String serviceName, RecoveryCursor after, int limit)`.
- **JDBC SQL** (one statement, autocommit, dialect-neutral, no new hook):

  ```sql
  DELETE FROM maestro_workflow_signal WHERE consumed = FALSE AND id IN (
      SELECT s.id FROM maestro_workflow_signal s
      JOIN maestro_workflow_instance i ON i.id = s.workflow_instance_id
      WHERE s.consumed = FALSE AND i.status IN ('COMPLETED', 'TERMINATED')
        AND i.completed_at < ? AND i.service_name = ?
      FETCH FIRST ? ROWS ONLY)
  ```

  - The outer `consumed = FALSE` keeps the leading text the perf harness matches (D-9). It also re-checks a row that a concurrent consumer flipped after the subquery read it. That race is impossible for a terminal instance, but the guard costs nothing.
  - The `JOIN` excludes orphans (`workflow_instance_id IS NULL`), as today's `IN (SELECT id …)` does.
  - `FETCH FIRST ? ROWS ONLY` matches the base purge SQL's portable form. Postgres 16 accepts it with a bind parameter (probe).
- **`SignalJanitor.sweepOnce()`:**
  - one cutoff per sweep (`Instant.now() - retention`);
  - then batches of `BATCH_SIZE = 1_000` until a batch deletes fewer than 1,000, each batch after the first preceded by `pacer.pauseAfter(previous)` (D-1). A stop ends the sweep;
  - it returns the total;
  - a `RuntimeException` from batch k is logged at `WARN`, ends the sweep, and reports the rows deleted by batches 1…k−1. `sweepSwallowedAFailure` is set, as today;
  - an `Error` propagates to the loop's guard, as today.
- **No cap per sweep.** The cutoff is fixed at the sweep's start, so the qualifying set is a snapshot: instances finished before the cutoff, plus the rare late signal to one of them. Every batch either deletes 1,000 rows of that finite set or ends the sweep. A cap would only leave a backlog for the next hour.
- **Why 1,000 (MD-2).**
  - A batch deletes one row per signal from one table, so 1,000 is the same scale as a retention batch of 500 instances (~2 signals each plus events).
  - The probe drained 52,440 rows in 53 batches of 5–22 ms (warm), 700 ms in total.
  - The final, empty batch costs the same scan as today's single statement (149–795 ms on the probe, against 194 ms; Q2-steady).
  - WP12's `gate.janitorBatched` assumes a batch below 5,000 (its local J-own is 10,000 rows).
- **The pause applies to the janitor too.** The spec names it only for retention. Without it, the batched janitor would recreate 3.11a's back-to-back pattern on a backlog. It uses the same `BatchPacer` and the same cap; the janitor has no leader TTL, and the cap keeps one rule.
- **Default path for third-party stores.** The overload's default ignores `serviceName` and `limit` and returns the unbounded count. When that count is ≥ 1,000, the janitor issues it a second time, which deletes 0 and ends the sweep. That is correct, at the cost of one extra empty statement per sweep. It is documented in the overload's Javadoc.
- **No leader (MD-6).** Two nodes' batches select overlapping ids. The second waits at most one batch on the first's row locks, then finds the rows gone, deletes fewer than 1,000, and ends its sweep. The first goes on. That is correct, and cheaper than today's whole-backlog wait (traced, not tested).
- **A deadlock with a concurrent purge** is possible in principle today, and remains so: the purge and the janitor delete overlapping signal rows in different orders. The database aborts one, and the janitor logs `WARN` and retries next interval. Batching shrinks the window to one batch (traced, not tested).
- **No new index (D-5).**

### D-4. The service-scoped retention purge (MD-1)

- **SPI.** The overload in the surface table. **Its default delegates to the unscoped form**, so a third-party store keeps today's behaviour.
- **JDBC.**
  - The protected hook `getServicePurgeCandidatesSql()` has four parameters. Its default is `SELECT id FROM maestro_workflow_instance WHERE status = ? AND completed_at < ? AND service_name = ? ORDER BY completed_at ASC FETCH FIRST ? ROWS ONLY`.
  - `PostgresWorkflowStore` overrides it with `… ORDER BY completed_at ASC LIMIT ? FOR UPDATE SKIP LOCKED`.
  - The body of `purgeTerminalInstancesBefore(status, cutoff, batchSize)` (`:1179-1241`) becomes a private `purge(status, cutoff, batchSize, CandidateSelect select)`. Both public forms call it, so the rollback-on-short-delete and the fault handling exist once.
  - The final instance delete is unchanged (`service_name` never changes, so re-checking status and cutoff is enough).
  - The old hook `getPurgeCandidatesSql()` stays, with an unchanged contract, for the unscoped form.
- **Engine.** `RetentionSweeper` calls `store.purgeTerminalInstancesBefore(serviceName, status, cutoff, batchSize)`, with the service name it already holds for its leader key.
- **Why scope it** (MD-1 has the alternative):
  - D8 makes the recovery filter final, and 3.11b scopes the janitor. An unscoped purge would delete a co-tenant's whole history while the janitor spares the co-tenant's signals.
  - Each service's `*-retention` then governs its own rows.
  - The election key is already per service, so today two services' leaders both sweep every row.
- **Mixed fleet and rollout.**
  - An old-build leader still purges unscoped, as today, until it stops leading; a new leader purges only its own rows. The leader key is the same, so they never sweep at once.
  - A rollback restores unscoped purging.
  - No schema or history change.
  - **The behaviour change:** after every node of every sharing service runs this build, a terminal row recorded under a `service_name` no node runs any more is purged by nobody. Release note, `docs/operations.md` §11.5 with a tested manual purge, and Issue 77 (Task 7).
- **Cost in a shared database** (Q3b): the candidate select still uses `idx_wf_instance_terminal (status, completed_at)` and filters `service_name` on the heap. When a co-tenant keeps a longer retention for the same status, every batch reads past the co-tenant's rows in that window (86 ms per batch for 34,960 rows on the probe). A single-service database pays nothing (Q3). The fix is an index on `(service_name, status, completed_at) WHERE completed_at IS NOT NULL`, which is a migration (D-5). Filed as Issue 78.

### D-5. No migration

- **Janitor.** The probe shows the existing indexes serve the batched statement: Q2, at 5–22 ms per warm batch. Its dominant cost is re-reading non-qualifying unconsumed signals, or this service's old terminal instances, on each batch. A dedicated partial index on `maestro_workflow_signal (workflow_instance_id) WHERE consumed = FALSE` would shorten the signal side, but would not remove the instance-side probes of the generic plan (reasoning).
- **Retention scope.** No index for single-service databases (Q3). The shared-database cost is Issue 78.
- **Issue 39.** Served by V2's partial `idx_wf_instance_recoverable` (Q4). V101's `idx_wf_instance_recovery` also qualifies.
- **Cost a migration would impose** (why "no" is the default):
  - Under `maestro.store.schema.mode=validate`, every deployment would have to record it before a node of this build starts (`MaestroSchemaInitializer.java:184-197`).
  - Under `none`, the operator must run it.
  - V8, V9 and V101 build their indexes with a plain `CREATE INDEX IF NOT EXISTS` inside Flyway's transaction, holding a `SHARE` lock on the table, which blocks writes for the build. Each tells operators of large tables to pre-create the index `CONCURRENTLY` by hand.
  - A future index would take the next number above the highest shipped, V102, in 101–199 (`CLAUDE.md`; `MaestroSchemaInitializerTest.migrationVersionsFollowTheNumberingRule`), and should follow V101's precedent.

### D-6. Issue 39: the advisory startup check

- **"Foreign service name".** A `service_name` that differs from this node's `maestro.service-name` and holds at least one active instance (`RUNNING`, `WAITING_SIGNAL`, `WAITING_TIMER`, `COMPENSATING`) whose `workflow_type` this node registers. This is Issue 39's own definition. The type filter keeps a co-tenant service with its own types (Issue 31) out of the warning. Terminal rows are not counted: they are not stranded work, and counting them would scan the bulk of the table.
- **The query** (JDBC; the `IN` lists are bound, one parameter per registered type):

  ```sql
  SELECT service_name, count(*) FROM maestro_workflow_instance
  WHERE status IN ('RUNNING', 'WAITING_SIGNAL', 'WAITING_TIMER', 'COMPENSATING')
    AND service_name <> ? AND workflow_type IN (?, ?, …)
  GROUP BY service_name ORDER BY 2 DESC, 1
  FETCH FIRST ? ROWS ONLY
  ```

  - The status list is `ACTIVE_STATUS_SQL` (`AbstractJdbcWorkflowStore.java:212-215`), the same text the partial indexes' predicates imply, so the planner uses them (Q4).
  - `WHERE status IN` comes first and the filter is `<>`, so the text never matches the perf harness's `recoveryPage` pattern (`FROM maestro_workflow_instance WHERE service_name = \$1 AND status IN `, `perf/sql/s3-components.sql:53` on `origin/main`).
- **Cost.** Proportional to the active rows of all services in the tables, never to terminal rows: 2.97 ms for 12,500 on the probe. By linear extrapolation (arithmetic, not measured), a million active rows would take a few hundred milliseconds, once per node start, on one pooled connection, off every startup path. It is bounded by the index and by the active set, which the recovery pass already reads for its own service every `poll-interval`.
- **When.** Once, right after `StartupRecoveryRunner` has started recovery. The runner calls `executor.startForeignServiceCheck(registrations)`, which starts the virtual thread `maestro-foreign-service-check` and returns. The thread calls `ForeignServiceCheck.run(store, serviceName, registrations.keySet())` and ends.
  - The check is not a `StoppableLoop`: `shutdown()` does not join it.
  - A shutdown during the query lets it finish or fail on a closed pool. A failure is logged at `INFO` (below).
  - Non-Spring embedders call `startForeignServiceCheck` themselves (Javadoc).
- **What it logs:**
  - **Per foreign name, `WARN`** (`ForeignServiceCheck.warning`), exactly: `{count} active workflow instance(s) of a type this node runs are recorded under maestro.service-name '{foreign}', not '{own}'. Recovery adopts only '{own}' instances, so unless a node runs with maestro.service-name={foreign} these workflows never resume: start one under that name, or correct the rows (docs/operations.md §11.5).`
  - **At most `MAX_REPORTED = 20` names.** The query asks for 21. When 21 come back, one extra `INFO` says `More than 20 other service names hold active instances of this node's workflow types; docs/operations.md §11.5 lists them all.`
  - **Clean result:** one `DEBUG` line, and nothing at `INFO` or above.
- **What must never happen:**
  - **Failing startup.** `startForeignServiceCheck` never throws. `run` catches `Throwable`, rethrows a `MaestroControlFlowError` (the thread then ends; nothing else depends on it), and logs anything else once at `INFO`: `Maestro service-name check skipped: {throwable}`, with no stack trace.
  - **Blocking readiness.** The runner returns before the query runs (Task 6 test).
  - **Touching another service's rows.** It is one `SELECT` through a `get*` SPI method, classified a read by `NodeStoreView` (Task 5).
  - **Logging a workflow id.** It logs names and counts only.
- **No registered types:** no query, and nothing logged above `DEBUG`.
- **Repeat cadence:** once per node start. Ten replicas starting log ten copies, one per node. That is what "one WARN per foreign name" means per startup.

### D-7. What each store gets

| Store | Janitor overload | Scoped purge (MD-1) | Foreign counts |
|---|---|---|---|
| `AbstractJdbcWorkflowStore` / `PostgresWorkflowStore` | D-3 SQL | D-4 hook | D-6 SQL |
| `InMemoryWorkflowStore` (`maestro-test`) | Override under `signalLock`, the same predicate plus `serviceName`, removing at most `limit` | Override: the three-argument body plus a `serviceName` filter on candidates | Override: count over `instances` |
| `DelegatingWorkflowStore` (starter tests), `TerminalEventDelayStore` (integration support) | forward | forward | forward |
| `NodeStoreView` (JDK proxy) | automatic (name already a write) | automatic | automatic; `NodeStoreViewTest`'s `READS` gains the name |
| `VersionedInMemoryStore`, test fakes, third-party stores | default (today's) | default (today's) | default (`Map.of()`) |

### D-8. Observability: no new meter (MD-5)

- `maestro.retention.sweeps` and `maestro.retention.purged{kind}` are unchanged: still one `retentionPass` per leader sweep, with the summed counts. The janitor has no meter today and gets none.
- The changed `INFO` lines:
  - `Retention sweep purged {} workflow instance(s) with {} event(s), {} signal(s) and {} timer(s) in {} batch(es)`;
  - `Signal janitor purged {} unconsumed signal(s) of service '{}' workflows finished before {} in {} batch(es)`;
  - `Signal janitor started (interval={}, terminalRetention={}, batchSize={}, service={})`.
- The meter catalogue's count sentence in `docs/observability.md` is unchanged, as are its tables.

### D-9. The perf harness's statement patterns

`perf/sql/pgss-window.sql` on `origin/main` classifies two things:
- `signalJanitor` = `^DELETE FROM maestro_workflow_signal WHERE consumed = `;
- `retention` = `^SELECT id FROM maestro_workflow_instance WHERE status = \$[0-9]+ AND completed_at < `.

The plan keeps both leading texts:
- D-3's janitor statement starts `DELETE FROM maestro_workflow_signal WHERE consumed = FALSE AND id IN (`;
- D-4's scoped select starts `SELECT id FROM maestro_workflow_instance WHERE status = ? AND completed_at < ? AND service_name = ?` (pgss shows `$1`, `$2`, `$3`).

The purge's four child deletes and its leader upsert are unchanged. A `PostgresStoreHygieneTest` guard pins both prefixes against the SQL the driver actually receives. The file is on `origin/main`, not on the code branch, so Task 7 Step 8 re-reads it at merge time. Counts per sweep:
- an empty-backlog janitor sweep is still 1 call, and an empty retention sweep still 3 candidate selects, so WP12's `plannedStarts` allowance holds;
- a backlog of U janitor rows now costs ⌈(U + 1) / 1,000⌉ calls.

### D-10. Loops, guard and shutdown

- `BackgroundLoopGuard` wraps each sweep exactly as WP4 left it. A pause sits inside `sweepOnce`, so a pause that ends on stop returns normally, and the loop's existing `if (!running) break` exits.
- `WorkflowExecutor.shutdown()` (WP5's single deadline) calls `requestStop()` on both loops in step 2 and joins them against the deadline. A loop in a pause therefore exits at once and costs the deadline nothing.
- The interrupt in `requestStop()` stays (Issue 64 is out of scope). Task 7 adds one sentence to Issue 64: these two loops can now stop without it.

## Decisions for the maintainer

| # | Question | Recommendation | Alternative and its cost |
|---|---|---|---|
| MD-1 | Is the retention sweep service-scoped too? | **Yes** (D-4, Task 4). It follows D8 and 3.11b. It honours each service's own retention in shared tables, and costs nothing on a single-service database. Release note, a tested manual purge for abandoned names (§11.5), Issue 77 (abandoned names are never purged) and Issue 78 (shared-table scan cost). WP12 updates `advisory.foreignRetention`'s "after" prediction to 0 purged and does not file its Issue 76 candidate. | **Leave it unscoped** and drop Task 4. In shared tables the shortest retention among co-tenants governs everyone's history: a 7 d `failed-retention` service purges a neighbour's `FAILED` runs its operator meant to keep 90 d, so Retry answers `NOT_FOUND`. The janitor (scoped) and the purge (unscoped) disagree. WP12 files Issue 76. |
| MD-2 | Janitor batch size: a property? | **A constant**, `SignalJanitor.BATCH_SIZE = 1_000` (D-3). No property and no validation. `WorkflowExecutor.startSignalJanitor(Duration, Duration)` and the public `SignalJanitorSettings` record are unchanged. | `maestro.signal.janitor-batch-size` (1–10,000, default 1,000). That means a new `MaestroProperties.SignalProperties` component, a `startSignalJanitor` overload or a changed public record, binding and validation tests, and docs, for a knob whose effect the pause already bounds. |
| MD-3 | The pause: fixed rule or configurable? | **Fixed:** `min(previous batch, 5 s)` (D-1). No property. | `maestro.retention.batch-pause-max`, or a duty-cycle ratio. That is new surface, plus validation against the leader TTL (a cap ≥ 10 s can lose leadership on slow batches). Nothing measured yet asks for it. WP12 S6 measures the fixed rule. |
| MD-4 | Issue 39 false positives: a live co-tenant service that registers the same type name. | **Accept, with conditional wording** ("unless a node runs with maestro.service-name=X …") and the type filter (D-6). File Issue 79 to suppress names with a live leader. | Suppress names that hold an unexpired `maestro:leader:timer-poller:<name>` row: a new `DistributedLock` default (for example `Set<String> liveLeaders(Collection<String> electionKeys)`), its Postgres override and tests, coupling an advisory store check to the lock SPI. A rolling rename would still warn until the old nodes stop. |
| MD-5 | A meter for the janitor's rows, or for foreign names? | **None** (D-8). The spec put this cycle's meter changes in WP11. The janitor and sweeper log their counts at `INFO`, and the foreign check is a once-per-start `WARN`. | `maestro.signal.janitor.purged` (counter) and/or `maestro.store.foreign.active{service}` (gauge, refreshed at start only, so it is stale by design). The meter count changes 21 → 22 or 23, with catalogue rows and a new `EngineObserver` default. |
| MD-6 | Leader election for the janitor? | **No** (D-3). Batches make the race cheap: one batch of waiting, then an early end on the loser. The janitor stays independent of `maestro.retention.enabled` and of the lock backend. | Elect on `maestro:leader:signal-janitor:{service}`: one fewer racing statement per node per hour, at the cost of a new lock key (`CLAUDE.md` lock-key list, `docs/configuration.md` table) and a lock-backend dependency for a single idempotent delete. |

## Global Constraints

- **Spec anti-goals (§2), in effect:**
  - **No run fails or compensates** where it would stand down or be abandoned. Nothing here touches a run. The janitor and the purge delete only terminal rows, as today, and the check only reads.
  - **Replay:** no event, history, column or migration changes. A mixed fleet mixes scoped and unscoped sweeps (D-3, D-4), and both are correct.
  - **An existing test is changed only as a named step.** The complete list is under "Existing tests changed". An implementer who meets any other failing test stops and asks.
- **Code rules.**
  - `maestro-core` never imports Spring. Jackson 3 only. JSpecify `@Nullable` on every nullable parameter and return.
  - Javadoc on every new or changed public or protected member and on `BatchPacer` and `ForeignServiceCheck`, each with a thread-safety note.
  - No plan ids (D-n, MD-n, WP8, "3.11a") in shipped Javadoc or log text. Issue numbers are fine, as elsewhere in the code.
  - No Lombok.
- **Control-flow `Error`s** (`CLAUDE.md` "Exceptions"):
  - `ForeignServiceCheck.run`'s `catch (Throwable)` checks `instanceof MaestroControlFlowError` first and rethrows;
  - the janitor's new batch loop catches `RuntimeException` only, as today, so an `Error` still reaches the guard;
  - `StoreUnavailableException` from a janitor batch is a `RuntimeException`: it is logged and ends the sweep, as today. It never reaches a workflow.
- **No log line or meter tag** carries a workflow id, a run id or a payload.
- **Branch and worktree.**
  - Branch `fix/store-hygiene`, cut from `origin/main` after WP6 has merged (Lane B order: WP9 → WP7 → WP10 → WP8; WP8 also waits for WP6), in `/Users/rakheendama/Projects/2026/maestro/.claude/worktrees/wp8-store-hygiene`.
  - Before the first edit, run `git -C <worktree> status` and `git -C /Users/rakheendama/Projects/2026/maestro status`.
  - Never `cd` into the main checkout. This plan lives on `docs/engine-hardening-cycle`; read it from the main checkout's absolute path.
- **Gradle.** Always `./gradlew --no-watch-fs`, from the worktree root. Never two Gradle runs on one module at once. No `git stash`.
- **Commits.**
  - Commit at every checkpoint named below.
  - Chain git steps with `&&`. Pass messages as `-m "$(cat <<'EOF' … EOF)"`.
  - Never run git-mutating commands in parallel.
  - Every message ends with the attribution line your own session supplies.
- **Long runs** are coordinator gates: `./gradlew --no-watch-fs build`, the whole `:maestro-integration-tests:test`, `e2eTest` and chaos. The coordinator launches them detached, with a log whose header records `pwd`, `git rev-parse HEAD` and a timestamp. A module run named in a step that takes longer than about 5 minutes is handed to the coordinator.
- **Red tests.**
  - Each new red test runs against the unfixed code, with only the seam named in its task in place, and fails on a positive fact: a list, a count, a boolean naming what did not happen. It never fails on a `ConditionTimeoutException` or a `TimeoutException`. Use `CountDownLatch.await(bound)` booleans, `CompletableFuture.completeOnTimeout(sentinel, …)`, and "value within" helpers that return the last value read.
  - Paste the FAIL lines into the commit body.
  - Each fix gets at least one repeatable mutation check, reverted in the same step (`git checkout -- <file>`; `git diff` must then be empty for that file).
  - A test that pins an existing property (a guard) is labelled as such and is green from the start. Its mutation check is its red.
- **No sleeps** in tests. Batch durations come from a fake clock the fake store advances. Pauses are recorded through `BatchPacer.Wait`, or are real latch waits that the test ends.
- **Postgres behaviour** is tested with Testcontainers in `maestro-store-postgres`, in a new class extending `PostgresTestSupport`. `PostgresWorkflowStoreTest` is not edited.
- **Documentation** states only what a test shows, or it is labelled "(traced, not tested)", "(arithmetic)" or "(measured on the planning probe)".
- **New issues** start at **77**. 72 and 73 were filed by WP6; 74–76 are reserved by WP11/WP12. Check with `grep -n '^### Issue' docs/open-issues.md | tail -3` and take the next free numbers if any of 77–79 is used.
- **PR gates (spec §6):** Build & Test; nightly E2E; chaos PR-gate ×3. **No 2 h soak:** §6 runs it on WP1, WP2, WP3, WP5 and WP6 only. The commands are in Task 7. The maintainer merges.

## Review Focus

1. **Pacing is real and exact.** One pause before each batch but the first, equal to the previous batch's time capped at 5 s, none after the last, and the leader check after the pause. → Task 1 `everyBatchAfterTheFirstWaitsAsLongAsTheBatchBeforeIt`, `aSlowBatchPausesFiveSecondsNotItsOwnLength`, `theLeaderIsCheckedAfterThePauseRightBeforeTheBatch`.
2. **Stop ends a pause at once, without an interrupt.** → Task 1 `requestStopEndsAPauseAtOnceWithoutAnInterrupt` (sweeper) and `BatchPacerTest`; Task 3 `requestStopDuringAPauseEndsTheJanitorsSweep`.
3. **The janitor is bounded and scoped, end to end.** → Task 2 `deletesOnlyThisServicesQualifyingSignalsAtMostLimitPerCall` (Postgres) and its in-memory twin; Task 3 `aSweepDeletesThisServicesSignalsInBatchesUntilAShortBatch`.
4. **Survivors are unchanged.** `FAILED`, recent, consumed and orphan signals stay, and so does another service's history. → Task 2, Task 4 Postgres tests.
5. **Third-party stores keep today's behaviour.** → `WorkflowStoreHygieneDefaultsTest`; Task 3 `aStoreWithoutTheOverloadIsSweptByItsUnscopedDelete`.
6. **Issue 39: one WARN per foreign name, nothing when clean, never in the way of startup.** → Task 6 `StartupForeignServiceCheckTest` (four tests).
7. **Perf patterns kept.** → Task 2 and Task 4 guards; Task 7 Step 8.

## Existing tests changed (named; the only allowed edits)

| File | Edit | Task |
|---|---|---|
| `maestro-core/src/test/java/io/b2mash/maestro/core/engine/SignalJanitorTest.java` | The five `new SignalJanitor(…)` calls (`:38`, `:50`, `:71`, `:83`, `:85`) gain `"svc", ` as the second argument. Nothing else. | 3 |
| `maestro-core/src/test/java/io/b2mash/maestro/core/engine/BackgroundLoopErrorTest.java` | The two `new SignalJanitor(…)` calls (`:243`, `:283`) gain `"svc", ` as the second argument. | 3 |
| `maestro-core/src/test/java/io/b2mash/maestro/core/engine/BackgroundLoopStopTest.java` | `new SignalJanitor(store, INTERVAL, Duration.ofDays(1))` (`:61`) gains `"svc", `. | 3 |
| `maestro-test/src/test/java/io/b2mash/maestro/test/NodeStoreViewTest.java` | `READS` (`:54-56`) gains `"getActiveInstanceCountsUnderOtherServices"`. | 5 |

**Files that only gain members** (no line removed):
- `maestro-spring-boot-starter/src/test/java/io/b2mash/maestro/spring/health/DelegatingWorkflowStore.java` (Tasks 2, 4, 5: forwarders);
- `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/support/TerminalEventDelayStore.java` (the same forwarders).

**Expected to stay green unchanged, and why** (checked against the design at planning time):
- **`RetentionSweeperTest`** (all 16):
  - its `RecordingStore` overrides the three-argument purge, which the scoped overload's default reaches, so the calls are recorded as before;
  - the fake's batches take microseconds, so the pauses are microseconds;
  - `stopMidSweepEndsBeforeTheNextBatch`: the stop is already requested when the blocked batch returns, so `pauseAfter` returns `false` at once and the call count stays 1;
  - `leadershipIsRecheckedPerBatch` counts `trySetLeader` calls = batches, unchanged.
- **`SignalJanitorTest`** (after the named edit): `RecordingStore` returns 3 (< 1,000), so there is one call per sweep, reached through the default overload with the same cutoff.
- **`BackgroundLoopErrorTest`, `BackgroundLoopStopTest`:** the janitor's fakes throw or block in the one-argument method, which the default reaches unchanged. Before relying on this, run `grep -rn -A10 "int deleteUnconsumedSignalsForTerminalInstancesBefore(Instant" maestro-*/src/test | grep -E "return [0-9]{4,}"`. It must print nothing: a fake returning ≥ 1,000 would now be called again in the same sweep.
- **`PostgresWorkflowStoreTest$SignalJanitorDeleteTests`, `$RetentionPurgeTests`, `InMemoryWorkflowStoreTest`:** they call the unchanged one- and three-argument methods.
- **`MultiNodeRetentionPurgeIT`:** both nodes and every seeded instance use one service name (`retention-it`, `:48`).
- **`StartupRecoveryRunnerTest`:** its stores are `InMemoryWorkflowStore` (with Task 5's override) and its seeds use the executor's own service name, so the check finds nothing. Its proxy store (`onPageRead`, `:188-200`) forwards every method, the new default included.

**Reviewer check, statement-level:**
1. `BASE=$(git merge-base origin/main HEAD); git diff --name-only --diff-filter=M "$BASE" HEAD -- '*/src/test/*'` prints exactly the six files above.
2. `git diff --name-only --diff-filter=D "$BASE" HEAD` prints nothing.
3. In the four named files, every removed line (`^-[^-]`) is one of the named call sites or the `READS` literal. In the two member-only files, no line is removed.

## Before you start

```bash
git -C /Users/rakheendama/Projects/2026/maestro fetch origin && \
git -C /Users/rakheendama/Projects/2026/maestro log origin/main --oneline -1 --grep='recovery-capacity\|WP6\|Issue 30' && \
git -C /Users/rakheendama/Projects/2026/maestro worktree add -b fix/store-hygiene \
    /Users/rakheendama/Projects/2026/maestro/.claude/worktrees/wp8-store-hygiene origin/main && \
git -C /Users/rakheendama/Projects/2026/maestro/.claude/worktrees/wp8-store-hygiene status
```

The log must print WP6's merge. If it prints nothing, stop: WP8 shares `StartupRecoveryRunner` with WP6, and the sweeper and janitor loops with WP4 and WP5.

Then confirm in the new worktree, and record the actual lines in Task 1's commit body:
- **S1.** `RetentionSweeper.requestStop()`, `SignalJanitor.requestStop()` and `StoppableLoop` are as described in D-2 and D-10. WP5's `ShutdownDeadline` joins them in `WorkflowExecutor.shutdown`.
- **S2.** `StartupRecoveryRunner.run` starts recovery first and adopts nothing (WP6 Task 7). Task 6 adds one call after the recovery start and nothing else.
- **S3.** `git show origin/main:perf/sql/pgss-window.sql | grep -n "signalJanitor\|retention"` still shows D-9's two patterns. If WP12 has changed them, D-9's guard prefixes follow the new patterns. Note it in Task 2's commit.
- **S4.** `grep -n '^### Issue' docs/open-issues.md | tail -3`: the next free numbers for Task 7.

## File structure

| File | Responsibility |
|---|---|
| `maestro-core/.../engine/BatchPacer.java` (new) | D-1, D-2 |
| `maestro-core/.../engine/RetentionSweeper.java` | Pacer wiring (Task 1); scoped call (Task 4); `INFO` line |
| `maestro-core/.../engine/SignalJanitor.java` | Service name, `BATCH_SIZE`, batch loop, pacer (Task 3) |
| `maestro-core/.../engine/ForeignServiceCheck.java` (new) | D-6 |
| `maestro-core/.../engine/WorkflowExecutor.java` | `startSignalJanitor` passes `serviceName` (Task 3); `startForeignServiceCheck` (Task 6); Javadoc of both start methods |
| `maestro-core/.../spi/WorkflowStore.java` | Three defaults (Tasks 2, 4, 5) |
| `maestro-store-jdbc/.../AbstractJdbcWorkflowStore.java` | Janitor override (2); `getServicePurgeCandidatesSql`, `purge(...)` refactor (4); foreign counts (5) |
| `maestro-store-postgres/.../PostgresWorkflowStore.java` | `getServicePurgeCandidatesSql` override (4) |
| `maestro-test/.../InMemoryWorkflowStore.java` | Three overrides (2, 4, 5) |
| `maestro-spring-boot-starter/.../config/StartupRecoveryRunner.java` | One call and Javadoc (6) |
| New tests | `BatchPacerTest`, `RetentionSweeperPacingTest`, `SignalJanitorBatchingTest`, `RetentionSweeperScopeTest`, `ForeignServiceCheckTest`, `WorkflowStoreHygieneDefaultsTest` (core); `PostgresStoreHygieneTest` (store-postgres); `InMemoryStoreHygieneTest` (maestro-test); `StartupForeignServiceCheckTest` (starter) |
| Docs | `docs/configuration.md`, `docs/operations.md` §§ 11, 13, `docs/release-notes.md`, `docs/open-issues.md`, `CLAUDE.md` |

## Pre-flight: task pairs that share a file or an interface

| Pair | Shared | Order and rule |
|---|---|---|
| 1 → 3, 4 | `BatchPacer` (Task 1 produces it; Task 3 consumes it; Task 4 edits the sweeper Task 1 paced) | Task 1 first. A signature change goes back to Task 1 and its tests. |
| 2 → 3 | The janitor SPI overload | Task 2 first: Task 3's red needs the overload to exist. |
| 2 → 4 → 5 | `WorkflowStore.java`, `AbstractJdbcWorkflowStore.java`, `InMemoryWorkflowStore.java`, both decorators, `PostgresStoreHygieneTest`, `InMemoryStoreHygieneTest` | Sequential. Each adds its own `@Nested` class to the two new test files. The hunks do not overlap. |
| 1 ↔ 4 | `RetentionSweeper.java` (Task 1: the loop; Task 4: one call's arguments and the `INFO` line) | Sequential. |
| 5 → 6 | `getActiveInstanceCountsUnderOtherServices` | Sequential. |
| 3, 6 | `WorkflowExecutor.java` (`startSignalJanitor` `:2001-2009`; `startForeignServiceCheck` after `startRetentionSweeper` `:2108`) | Sequential; disjoint hunks. |
| 6 ↔ WP6 | `StartupRecoveryRunner.java` | Task 6 adds one call after recovery starts and keeps WP6's start-only rule (S2). |
| 7 | Docs | Last. |

Task 4 is MD-1's. If the maintainer rules MD-1 "no", skip Task 4 and the MD-1 parts of Task 7 (marked), and send WP12 the "unscoped" hand-off instead.

---

### Task 1: The retention sweeper pauses between batches (3.11a)

**Files:**
- Create: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/BatchPacer.java`.
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/RetentionSweeper.java`:
  - a constructor overload `RetentionSweeper(WorkflowStore, @Nullable DistributedLock, String, RetentionSettings, EngineObserver, BatchPacer)`; the existing constructor delegates with `new BatchPacer()`;
  - `requestStop()` calls `pacer.requestStop()` first;
  - `sweepOnce` per D-1: `long previous = -1` before the target loop. At the top of each batch iteration: `if (previous >= 0 && !pacer.pauseAfter(previous))`, take the existing stop exit. Then the existing stop check and leader check. Then `var t0 = pacer.now(); result = store…; previous = pacer.now() - t0;`;
  - count batches for the `INFO` line;
  - class Javadoc gains `<h2>Pacing</h2>`: D-1's rule, its cap and its reason in four sentences, plus "a stop request ends a pause at once".
- Test: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/BatchPacerTest.java` (new) and `maestro-core/src/test/java/io/b2mash/maestro/core/engine/RetentionSweeperPacingTest.java` (new).

**Interfaces:**
- Consumes: nothing new.
- Produces: `BatchPacer` exactly as in the surface table (D-2), and the sweeper's pacer constructor.

- [ ] **Step 1: Write the tests.**

  `RetentionSweeperPacingTest` fixture:
  - `var clock = new AtomicLong()`;
  - a `TimedStore implements WorkflowStore`. It records an `events` list (`"leader"`, `"batch:<STATUS>"`, `"pause:<ms>"`), shared with a `RecordingLock` (`trySetLeader` appends `"leader"` and returns `true`) and the recording `Wait`. It plays a scripted queue of `(PurgeResult, Duration)`: each purge call advances `clock` by the duration and returns the result;
  - `var pauses = new CopyOnWriteArrayList<Duration>()`;
  - `Wait recording = (stop, d) -> { pauses.add(d); events.add("pause:" + d.toMillis()); return stop.getCount() == 0; }`;
  - `var pacer = new BatchPacer(clock::get, recording)`;
  - `settings(2, 100)` as in `RetentionSweeperTest`, with `full = new PurgeResult(2, 2, 0, 0)` and `one = new PurgeResult(1, 1, 0, 0)`.

```java
@Test void everyBatchAfterTheFirstWaitsAsLongAsTheBatchBeforeIt() {
    store.script(full, ms(40)).script(full, ms(70)).script(one, ms(10))     // COMPLETED: full, full, short
         .script(PurgeResult.EMPTY, ms(3))                                   // TERMINATED: empty
         .script(PurgeResult.EMPTY, ms(2));                                  // FAILED: empty
    new RetentionSweeper(store, null, "orders", settings(2, 100), EngineObserver.NOOP, pacer).sweepOnce();
    assertEquals(List.of(ms(40), ms(70), ms(10), ms(3)), pauses,
            "one pause before every batch but the first, each as long as the batch before it; none after the last");
}
@Test void aSlowBatchPausesFiveSecondsNotItsOwnLength() {
    store.script(full, Duration.ofSeconds(40)).script(one, Duration.ofSeconds(40));   // T and F: unscripted, 0 ms
    new RetentionSweeper(store, null, "orders", settings(2, 100), EngineObserver.NOOP, pacer).sweepOnce();
    assertEquals(List.of(Duration.ofSeconds(5), Duration.ofSeconds(5)), pauses,
            "capped at a third of the leader TTL; T's 0 ms batch costs F no pause");
}
@Test void theLeaderIsCheckedAfterThePauseRightBeforeTheBatch() {
    store.script(full, ms(40)).script(one, ms(10));                                   // T and F: unscripted, 0 ms
    new RetentionSweeper(store, lock, "orders", settings(2, 100), EngineObserver.NOOP, pacer).sweepOnce();
    assertEquals(List.of("leader", "batch:COMPLETED", "pause:40", "leader", "batch:COMPLETED",
                         "pause:10", "leader", "batch:TERMINATED", "leader", "batch:FAILED"),
                 store.events);
}
@Test void requestStopEndsAPauseAtOnceWithoutAnInterrupt() throws Exception {
    var entered = new CountDownLatch(1);
    var pacer = new BatchPacer(clock::get, (stop, d) -> { entered.countDown(); return BatchPacer.LATCH.await(stop, d); });
    store.scriptForever(full, Duration.ofHours(1));                  // every pause is the 5 s cap
    var sweeper = new RetentionSweeper(store, null, "orders", settings(2, 100), EngineObserver.NOOP, pacer);
    var done = new CompletableFuture<Optional<PurgeResult>>();
    var interruptedAfter = new AtomicBoolean(true);
    Thread.ofVirtual().start(() -> { done.complete(sweeper.sweepOnce());
                                      interruptedAfter.set(Thread.currentThread().isInterrupted()); });
    assertTrue(entered.await(5, SECONDS), "the sweep reached its first pause");
    sweeper.requestStop();                                           // never started: no loop thread to interrupt
    var result = done.completeOnTimeout(null, 1, SECONDS).get();
    assertNotNull(result, "sweepOnce was still paused 1 s after requestStop");
    assertEquals(1, store.purgeCalls(), "no batch after the stop");
    assertEquals(2, result.orElseThrow().instances());
    assertFalse(interruptedAfter.get(), "nothing interrupted the sweeping thread");
}
```

  `TimedStore` answers an unscripted call with `PurgeResult.EMPTY` and advances the clock by 0 ms. FAILED's batch therefore follows a zero pause, which D-2 says never reaches `Wait`, so the second and third tests list no `pause:0`.

  `BatchPacerTest`:

```java
@Test void thePauseIsTheBatchDurationCappedAtFiveSeconds() {
    assertEquals(Duration.ofMillis(40), BatchPacer.pauseFor(Duration.ofMillis(40).toNanos()));
    assertEquals(Duration.ofSeconds(5), BatchPacer.pauseFor(Duration.ofMinutes(3).toNanos()));
    assertEquals(Duration.ZERO, BatchPacer.pauseFor(-1));
}
@Test void aZeroPauseDoesNotWait() { /* recording Wait: pauseAfter(0) returns true and records nothing */ }
@Test void aPauseAfterStopReturnsFalseWithoutWaiting() { /* requestStop(); pauseAfter(5 s) is false; recorder empty */ }
@Test void requestStopEndsAWaitInProgressWithoutAnInterrupt() throws Exception {
    /* LATCH wrapped with an entered latch; a virtual thread calls pauseAfter(5 s); entered.await(5 s) is true;
       requestStop(); the thread's result via completeOnTimeout(null, 1 s) is false (not null);
       the thread's isInterrupted() afterwards is false */
}
@Test void anInterruptEndsTheWaitAndIsRestored() { /* interrupt the waiting thread: pauseAfter returns false; flag set */ }
```

- [ ] **Step 2: Add the seam only, and run red.**
  - Create `BatchPacer` complete (it is new code with its own tests). Add the sweeper's pacer constructor, which stores the pacer but does not call it.
  - Run `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.BatchPacerTest' --tests 'io.b2mash.maestro.core.engine.RetentionSweeperPacingTest'`.
  - Expected:
    - `BatchPacerTest` passes;
    - `everyBatchAfterTheFirstWaitsAsLongAsTheBatchBeforeIt` FAILS with `expected: <[PT0.04S, PT0.07S, PT0.01S, PT0.003S]> but was: <[]>`;
    - `aSlowBatch…` FAILS with `expected: <[PT5S, PT5S]> but was: <[]>`;
    - `theLeaderIs…` FAILS on the missing `pause:` entries;
    - `requestStop…` FAILS on its first assertion, "the sweep reached its first pause" (`entered` never counts down: with no pause the sweep runs its 300 batches and returns). That is a boolean that names what did not happen.
  - Paste the FAIL lines into the commit body.
- [ ] **Step 3: Implement D-1 in `sweepOnce`** and D-2's `requestStop` wiring.
- [ ] **Step 4: Run green.** Run the same command, then `:maestro-core:test --tests '*RetentionSweeper*' --tests '*BackgroundLoop*'`. Expected: PASS, with `RetentionSweeperTest` unchanged (see "Expected to stay green").
- [ ] **Step 5: Mutation checks.** Revert each one, after which `git diff` on the file must be empty:
  - pause before the leader check, not after: `theLeaderIs…` goes red;
  - `LATCH` replaced by `(stop, d) -> { Thread.sleep(d); return false; }`: `requestStopEndsAPause…` goes red ("still paused 1 s after requestStop");
  - no cap in `pauseFor`: `aSlowBatch…` goes red (`PT40S`);
  - pause also after the last batch: `everyBatch…` goes red (five entries).
- [ ] **Step 6: Commit:** `fix(engine): the retention sweeper pauses between batches for as long as the last batch took, at most 5 s (3.11a)`. The body records S1's line numbers.

---

### Task 2: The store deletes a bounded batch of one service's stale signals (3.11b, store half)

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/spi/WorkflowStore.java`. Add the overload after `:802`. Its Javadoc covers:
  - the same survivors as the one-argument form, plus other services' signals;
  - "at most `limit`";
  - "the engine's janitor calls this in a loop until it returns fewer than `limit`";
  - **the default** (unscoped and unbounded, so a janitor over a store that does not override it behaves as before, plus one extra empty call when the unbounded delete removed `limit` or more);
  - validation.

  The one-argument form's Javadoc gains "The engine no longer calls this; see the scoped overload".
- Modify: `maestro-store-jdbc/src/main/java/io/b2mash/maestro/store/jdbc/AbstractJdbcWorkflowStore.java`: the override with D-3's SQL, next to `:1650`. `{@inheritDoc}` plus "one statement; Postgres accepts the parameterised `FETCH FIRST`".
- Modify: `maestro-test/src/main/java/io/b2mash/maestro/test/InMemoryWorkflowStore.java`: the override next to `:614` (D-7).
- Modify (forwarders): `DelegatingWorkflowStore.java`, `TerminalEventDelayStore.java`.
- Test (new):
  - `maestro-store-postgres/src/test/java/io/b2mash/maestro/store/postgres/PostgresStoreHygieneTest.java`, `@Nested class ScopedJanitorDelete`;
  - `maestro-test/src/test/java/io/b2mash/maestro/test/InMemoryStoreHygieneTest.java`, `@Nested class ScopedJanitorDelete`;
  - `maestro-core/src/test/java/io/b2mash/maestro/core/spi/WorkflowStoreHygieneDefaultsTest.java`.

**Interfaces:**
- Consumes: nothing.
- Produces: the overload, used by Task 3.

- [ ] **Step 1: Write the tests.**

  `PostgresStoreHygieneTest` fixture, with cutoff = now − 7 d. Workflow ids are prefixed by service:
  - `orders`: 3 `COMPLETED` and 2 `TERMINATED` finished 10 d ago, each with 1 unconsumed signal; 1 `FAILED` finished 10 d ago with 1 unconsumed signal; 1 `COMPLETED` finished 1 d ago with 1 unconsumed signal; one consumed signal on an old `COMPLETED`;
  - `billing`: 3 `COMPLETED` and 2 `TERMINATED` finished 10 d ago, each with 1 unconsumed signal;
  - one orphan signal.

  Factories as in `PostgresWorkflowStoreTest` (`:55-102`), with a `serviceName` parameter.

```java
@Test void deletesOnlyThisServicesQualifyingSignalsAtMostLimitPerCall() {
    var deleted = IntStream.range(0, 4)
            .map(i -> store.deleteUnconsumedSignalsForTerminalInstancesBefore("orders", cutoff, 2)).boxed().toList();
    assertEquals(List.of(2, 2, 1, 0), deleted);
    assertEquals(5, unconsumedOfService("billing"), "another service's signals are not this service's to delete");
    assertEquals(1, unconsumed(ordersFailed));  assertEquals(1, unconsumed(ordersRecent));
    assertEquals(1, unconsumed(orphanWorkflowId));  assertTrue(consumedRowStillExists(consumedSignalId));
}
@Test void rejectsANonPositiveLimit() {
    assertThrows(IllegalArgumentException.class, () -> store.deleteUnconsumedSignalsForTerminalInstancesBefore("orders", cutoff, 0));
}
@Test void theJanitorStatementKeepsTheLeadingTextThePerfHarnessMatches() {          // guard (D-9)
    var sql = new CopyOnWriteArrayList<String>();
    new PostgresWorkflowStore(recording(dataSource, sql), objectMapper)
            .deleteUnconsumedSignalsForTerminalInstancesBefore("orders", cutoff, 2);
    var janitor = sql.stream().map(s -> s.replaceAll("\\s+", " ").strip())
            .filter(s -> s.startsWith("DELETE FROM maestro_workflow_signal")).toList();
    assertEquals(1, janitor.size());
    assertTrue(janitor.getFirst().startsWith("DELETE FROM maestro_workflow_signal WHERE consumed = "), janitor.getFirst());
}
```

  `recording(DataSource, List<String>)`: a JDK-proxy `DataSource` whose connections are proxies recording every `prepareStatement(String…)` argument and delegating everything. It is a private helper in this class; `StartupRecoveryRunnerTest.onPageRead` (`:188-200`) is the proxy model.

  `InMemoryStoreHygieneTest.ScopedJanitorDelete`: the same fixture and the same first two tests against `new InMemoryWorkflowStore()`.

  `WorkflowStoreHygieneDefaultsTest` uses a minimal store that implements only the abstract one-argument janitor delete and records its cutoff and its call count. Every other method throws `UnsupportedOperationException`, as in `SignalJanitorTest.RecordingStore`.

```java
@Test void theJanitorOverloadsDefaultIsTheUnscopedUnboundedDelete() {
    store.returns(7);
    assertEquals(7, store.deleteUnconsumedSignalsForTerminalInstancesBefore("orders", cutoff, 2));
    assertEquals(List.of(cutoff), store.cutoffs());
}
@Test void theJanitorOverloadsDefaultValidatesItsArguments() { /* null service → NPE; limit 0 → IAE; no delegate call */ }
```

- [ ] **Step 2: Add the SPI default only** (the default body as specified), and run red:
  - `./gradlew --no-watch-fs :maestro-store-postgres:test --tests 'io.b2mash.maestro.store.postgres.PostgresStoreHygieneTest'`. Expected FAIL: `deletesOnlyThisServicesQualifyingSignalsAtMostLimitPerCall` with `expected: <[2, 2, 1, 0]> but was: <[10, 0, 0, 0]>`, because the default deletes both services' signals in one call. The guard passes (today's statement has the same prefix);
  - `./gradlew --no-watch-fs :maestro-test:test --tests 'io.b2mash.maestro.test.InMemoryStoreHygieneTest'`. The same red;
  - `WorkflowStoreHygieneDefaultsTest` passes: it pins the default.
- [ ] **Step 3: Implement** the JDBC and in-memory overrides and the two forwarders.
- [ ] **Step 4: Run green.** Run the commands above, then `:maestro-store-postgres:test --tests '*PostgresWorkflowStoreTest$SignalJanitorDeleteTests'`, `:maestro-test:test`, `:maestro-store-jdbc:test` and `:maestro-core:test --tests 'io.b2mash.maestro.core.spi.*'`, one at a time. Expected: PASS.
- [ ] **Step 5: Mutation checks** (reverted):
  - drop `AND i.service_name = ?` (and its bind): the scoped test goes red (`billing` 0);
  - drop the `FETCH FIRST`: red (`[5, 0, 0, 0]`);
  - change the leading text to `DELETE FROM maestro_workflow_signal WHERE id IN (…`: the guard goes red.
- [ ] **Step 6: Commit:** `feat(store): WorkflowStore deletes a bounded batch of one service's stale unconsumed signals (3.11b)`. The body notes S3's result.

---

### Task 3: The janitor deletes this service's stale signals in paced batches (3.11b, engine half)

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/SignalJanitor.java`:
  - constructors `SignalJanitor(WorkflowStore, String serviceName, Duration, Duration)`, `(…, EngineObserver)` and `(…, EngineObserver, BatchPacer)`. A blank `serviceName` throws `IllegalArgumentException`, as `RetentionSweeper` does (`:132-134`);
  - `BATCH_SIZE`; `sweepOnce` per D-3; `requestStop()` calls `pacer.requestStop()` first;
  - the start and purge `INFO` lines (D-8);
  - class Javadoc: replace "No leader election" (`:28-33`) with "Every node runs a janitor, without leader election. It deletes only this service's signals, in batches of 1,000, pausing between batches as the retention sweeper does. Two nodes sweeping at once wait at most one batch on each other, and the later one ends early".
- Modify: `WorkflowExecutor.startSignalJanitor` (`:2006`) passes `serviceName`. Its Javadoc (`:1980-1991`): "Every node runs one; the delete is idempotent, so no leader election is involved" becomes "Every node runs one, without leader election; it deletes this service's signals only, in batches of 1,000".
- Modify (named): `SignalJanitorTest.java`, `BackgroundLoopErrorTest.java`, `BackgroundLoopStopTest.java`.
- Test (new): `maestro-core/src/test/java/io/b2mash/maestro/core/engine/SignalJanitorBatchingTest.java`.

**Interfaces:**
- Consumes: Task 1's `BatchPacer`, Task 2's overload.
- Produces: the janitor's new constructors.

- [ ] **Step 1: Write the tests.** Fixture:
  - a `ScopedJanitorStore` overriding the **three-argument** overload. It records `Call(serviceName, cutoff, limit)`, plays a scripted queue of `(int deleted, Duration took)` advancing the shared `clock`, and can throw on call n;
  - its one-argument method increments `unscopedCalls` and returns 0;
  - the recording `Wait` from Task 1.

```java
@Test void aSweepDeletesThisServicesSignalsInBatchesUntilAShortBatch() {
    store.script(1000, ms(40)).script(1000, ms(70)).script(37, ms(10));
    var janitor = new SignalJanitor(store, "orders", Duration.ofHours(1), Duration.ofDays(7), EngineObserver.NOOP, pacer);
    assertEquals(2037, janitor.sweepOnce());
    assertEquals(List.of("orders", "orders", "orders"), store.calls().stream().map(Call::serviceName).toList());
    assertTrue(store.calls().stream().allMatch(c -> c.limit() == SignalJanitor.BATCH_SIZE));
    assertEquals(1, store.calls().stream().map(Call::cutoff).distinct().count(), "one cutoff per sweep");
    assertEquals(0, store.unscopedCalls(), "the unscoped, unbounded delete is never issued");
}
@Test void betweenTwoBatchesTheJanitorPausesAsLongAsTheBatchBeforeIt() {
    store.script(1000, ms(40)).script(1000, ms(70)).script(5, ms(10));
    janitor(pacer).sweepOnce();
    assertEquals(List.of(ms(40), ms(70)), pauses);
}
@Test void requestStopDuringAPauseEndsTheJanitorsSweep() throws Exception {
    /* As Task 1's requestStop test: batches of 1,000 every hour (5 s pauses); sweepOnce on a test virtual
       thread; entered.await(5 s); requestStop(); completeOnTimeout(null, 1 s) is 1000 (not null);
       one call; thread not interrupted */
}
@Test void aStoreFaultMidSweepReportsWhatWasDeletedSoFar() {
    store.script(1000, ms(5)).failOnCall(2, new StoreUnavailableException("down", new SQLException()));
    assertEquals(1000, janitor(pacer).sweepOnce());
}
@Test void aStoreWithoutTheOverloadIsSweptByItsUnscopedDelete() {
    /* a store implementing only the one-argument delete, returning 3: sweepOnce == 3, one call (the default path) */
}
@Test void aBlankServiceNameIsRejected() { /* IAE for " " */ }
```

- [ ] **Step 2: Add the seam only.**
  - Add the constructors, store `serviceName` and the pacer, but leave `sweepOnce` calling the one-argument delete.
  - Apply the named call-site edits in the three existing test files. Run `:maestro-core:test --tests '*SignalJanitor*' --tests '*BackgroundLoop*'`.
  - Expected:
    - `aSweepDeletes…` FAILS with `expected: <[orders, orders, orders]> but was: <[]>`;
    - `betweenTwoBatches…` FAILS with `expected: <[PT0.04S, PT0.07S]> but was: <[]>`;
    - `aStoreFault…` FAILS with `expected: <1000> but was: <0>`;
    - `requestStop…` FAILS on its `entered` boolean;
    - `SignalJanitorTest`, `BackgroundLoop*` and the default-path test pass.
  - Paste the FAIL lines into the commit body.
- [ ] **Step 3: Implement D-3** in `sweepOnce`, the `requestStop` wiring, the log lines and the Javadoc.
- [ ] **Step 4: Run green.** The same command, then `:maestro-core:test` whole. Expected: PASS.
- [ ] **Step 5: Mutation checks** (reverted):
  - loop condition `deleted < BATCH_SIZE` → `deleted == 0`: `aSweepDeletes…` goes red (4 calls);
  - drop `pacer.pauseAfter`: `betweenTwoBatches…` goes red;
  - catch the fault outside the loop and return 0: `aStoreFault…` goes red.
- [ ] **Step 6: Commit:** `fix(engine): the signal janitor deletes only this service's stale signals, in paced batches of 1,000 (3.11b)`.

---

### Task 4 (MD-1): Retention purges only this service's workflows

**Files:**
- Modify: `WorkflowStore.java`: the overload after `:863`, with Javadoc ("…of `serviceName` only; the default delegates to the unscoped form, so a store that does not override it purges every service's rows, as before").
- Modify: `AbstractJdbcWorkflowStore.java`:
  - `getServicePurgeCandidatesSql()` beside `:324`, with Javadoc mirroring `getPurgeCandidatesSql`'s and naming its four parameters;
  - the private `purge(…)` refactor (D-4); the overload.
- Modify: `PostgresWorkflowStore.java`: the override beside `:150`.
- Modify: `InMemoryWorkflowStore.java`: the override beside `:650`.
- Modify (forwarders): the two decorators.
- Modify: `RetentionSweeper.java`: `:288` calls the scoped overload with `serviceName`. The class Javadoc's first paragraph says "this service's (`serviceName`) workflows".
- Test: `PostgresStoreHygieneTest` `@Nested class ScopedPurge`; `InMemoryStoreHygieneTest` `@Nested class ScopedPurge`; `WorkflowStoreHygieneDefaultsTest` (+1); `maestro-core/src/test/java/io/b2mash/maestro/core/engine/RetentionSweeperScopeTest.java` (new).

**Interfaces:**
- Consumes: Task 1's sweeper.
- Produces: the scoped purge.

- [ ] **Step 1: Write the tests.** Fixture: `orders` has 3 `COMPLETED` finished 10 d ago; `billing` has 2 `COMPLETED` finished 10 d ago. Each instance has two events, a `PENDING` and a `FIRED` timer, and a consumed and an unconsumed signal (`PostgresWorkflowStoreTest.attachHistory`, `:1303`, is the model). Cutoff = now − 7 d.

```java
@Test void purgesOnlyThisServicesInstancesWithTheirHistory() {
    var result = store.purgeTerminalInstancesBefore("orders", WorkflowStatus.COMPLETED, cutoff, 500);
    assertEquals(new PurgeResult(3, 6, 6, 6), result);
    assertEquals(2, instancesOf("billing"));
    assertEquals(4, eventsOf("billing"));  assertEquals(4, signalsOf("billing"));  assertEquals(4, timersOf("billing"));
}
@Test void aRowLockedElsewhereIsSkippedNotWaitedOn() throws Exception {
    // conn2: BEGIN; SELECT … FROM maestro_workflow_instance WHERE workflow_id = 'orders-c-0' FOR UPDATE (held)
    var result = CompletableFuture.supplyAsync(() -> store.purgeTerminalInstancesBefore("orders", COMPLETED, cutoff, 500))
            .completeOnTimeout(null, 5, SECONDS).get();
    assertNotNull(result, "the purge waited on a locked candidate");
    assertEquals(2, result.instances());
}
@Test void theScopedCandidateSelectKeepsTheLeadingTextThePerfHarnessMatches() {            // guard (D-9)
    /* recording DataSource as in Task 2: the first statement starting with "SELECT id FROM maestro_workflow_instance"
       starts with "SELECT id FROM maestro_workflow_instance WHERE status = ? AND completed_at < " */
}
@Test void theDocumentedManualPurgeRemovesAnAbandonedServicesHistory() {
    // seed 3 COMPLETED of service 'orders-old' finished 40 d ago with history, and 2 of 'orders'
    // run MANUAL_PURGE_SQL (the exact text Task 7 puts in docs/operations.md §11.5, held here as a constant)
    // with :name = 'orders-old' until it deletes 0 instances
    assertEquals(0, instancesOf("orders-old"));
    assertEquals(0, orphanedChildRows());          // no event, timer or signal row without its instance
    assertEquals(2, instancesOf("orders"));
}
```

  Write `MANUAL_PURGE_SQL` as the statements of one transaction:
  - a temporary table of ≤ 500 candidate ids (`service_name = ? AND status IN ('COMPLETED','TERMINATED','FAILED') AND completed_at < now() - interval '30 days'`, `ORDER BY completed_at`);
  - the three child deletes by `workflow_instance_id IN (SELECT id FROM …)`;
  - the instance delete.

  If Postgres rejects a form, adjust the SQL, not the test's assertions. The doc copies whatever runs green.

  `RetentionSweeperScopeTest`: a store overriding the four-argument overload, recording `serviceName` per call, with `full` then `EMPTY` batches.

```java
@Test void everyBatchIsScopedToTheSweepersService() {
    new RetentionSweeper(store, null, "orders", settings(2, 100), EngineObserver.NOOP).sweepOnce();
    assertEquals(List.of("orders", "orders", "orders", "orders"), store.scopedServiceNames());   // C full, C empty, T, F
    assertEquals(0, store.unscopedCalls());
}
```

  `WorkflowStoreHygieneDefaultsTest`: `theScopedPurgesDefaultIsTheUnscopedPurge` (it records the three-argument call with the same status, cutoff and batch size).

- [ ] **Step 2: Add the SPI default only, and run red.**
  - `PostgresStoreHygieneTest$ScopedPurge`: `purgesOnlyThisServicesInstancesWithTheirHistory` FAILS with `expected: <PurgeResult[instances=3, …]> but was: <PurgeResult[instances=5, …]>`. `aRowLockedElsewhere…` FAILS (`instances` 4, both services). The guard and the manual-purge test pass;
  - `InMemoryStoreHygieneTest$ScopedPurge`: the same red;
  - `RetentionSweeperScopeTest` FAILS with `expected: <[orders, orders, orders, orders]> but was: <[]>`.

  Paste the FAIL lines into the commit body.
- [ ] **Step 3: Implement** the JDBC refactor and hook, the Postgres and in-memory overrides, the forwarders and the sweeper call.
- [ ] **Step 4: Run green.** Run these one at a time:
  - `:maestro-store-postgres:test --tests '*PostgresStoreHygieneTest' --tests '*PostgresWorkflowStoreTest$RetentionPurgeTests' --tests '*PostgresReopenFailedInstanceTest'`;
  - `:maestro-test:test`;
  - `:maestro-core:test --tests '*Retention*' --tests 'io.b2mash.maestro.core.spi.*'`;
  - `:maestro-integration-tests:test --tests '*MultiNodeRetentionPurgeIT'`.

  Expected: PASS.
- [ ] **Step 5: Mutation checks** (reverted):
  - the Postgres override without `AND service_name = ?`: `purgesOnly…` goes red;
  - `FOR UPDATE` without `SKIP LOCKED`: `aRowLockedElsewhere…` goes red (null: it waited);
  - the sweeper calls the three-argument form: `RetentionSweeperScopeTest` goes red.
- [ ] **Step 6: Commit:** `fix(store): the retention sweep purges only this service's workflows (Issue 76 candidate, MD-1)`.

---

### Task 5: The store counts active instances of this node's types under other service names (Issue 39, store half)

**Files:**
- Modify: `WorkflowStore.java`: the read default, under "Instance operations" after the paged recovery method (`:188`). Its Javadoc covers "advisory", "never writes", the ordering, the limit, and the default `Map.of()`.
- Modify: `AbstractJdbcWorkflowStore.java`: the override with D-6's SQL (bind `serviceName`, each type, the limit; read into a `LinkedHashMap`). An empty `workflowTypes` returns `Map.of()` before borrowing a connection.
- Modify: `InMemoryWorkflowStore.java`: the override.
- Modify (forwarders): the two decorators.
- Modify (named): `NodeStoreViewTest.java` `READS`.
- Test: `PostgresStoreHygieneTest` `@Nested class ForeignServiceCounts`; `InMemoryStoreHygieneTest` `@Nested class ForeignServiceCounts`; `WorkflowStoreHygieneDefaultsTest` (+1).

**Interfaces:**
- Consumes: nothing.
- Produces: the read, used by Task 6.

- [ ] **Step 1: Write the tests.** Fixture:
  - `orders` / type `order` 2× `RUNNING` (own: not counted);
  - `orders-old` / `order`: `RUNNING`, `WAITING_SIGNAL`, `WAITING_TIMER` (3);
  - `orders-1` / `payment` 2× `COMPENSATING`;
  - `billing` / `invoice` 4× `RUNNING` (type not ours);
  - `orders-old` / `order` 5× `COMPLETED` (terminal).

```java
@Test void countsActiveInstancesOfTheGivenTypesUnderEveryOtherServiceName() {
    var counts = store.getActiveInstanceCountsUnderOtherServices("orders", Set.of("order", "payment"), 21);
    assertEquals(List.of(Map.entry("orders-old", 3L), Map.entry("orders-1", 2L)), List.copyOf(counts.entrySet()));
}
@Test void theLimitCapsTheNamesReturned() {
    assertEquals(Set.of("orders-old"), store.getActiveInstanceCountsUnderOtherServices("orders", Set.of("order", "payment"), 1).keySet());
}
@Test void noTypesMeansNoStatement() { /* recording DataSource: Map.of() and no prepareStatement */ }
@Test void theQueryOnlyReadsAndNeverLooksLikeTheRecoveryPage() {                         // guard (D-6, D-9)
    /* recording DataSource: exactly one statement; it starts with
       "SELECT service_name, count(*) FROM maestro_workflow_instance WHERE status IN (" and does not contain
       "WHERE service_name = ?" ; a row count of the four tables is identical before and after */
}
```

  In-memory: the first two tests. Defaults: `theForeignCountsDefaultIsEmpty`.
- [ ] **Step 2: Add the SPI default and the `READS` edit only, and run red.**
  - `PostgresStoreHygieneTest$ForeignServiceCounts` and `InMemoryStoreHygieneTest$ForeignServiceCounts`: `countsActive…` FAILS with `expected: <[orders-old=3, orders-1=2]> but was: <[]>`, and `theLimit…` with `expected: <[orders-old]> but was: <[]>`.
  - `:maestro-test:test --tests '*NodeStoreViewTest'` passes.
- [ ] **Step 3: Implement** the overrides and forwarders.
- [ ] **Step 4: Run green.** The commands above, then `:maestro-test:test`. Expected: PASS.
- [ ] **Step 5: Mutation checks** (reverted):
  - drop the status filter: `countsActive…` goes red (`orders-old=8`);
  - drop the type filter: red (`billing=4` appears);
  - `ORDER BY 1`: red (order).
- [ ] **Step 6: Commit:** `feat(store): WorkflowStore counts active instances of given types under other service names (Issue 39)`.

---

### Task 6: One WARN per foreign service name at startup, never in the way (Issue 39, engine and starter)

**Files:**
- Create: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/ForeignServiceCheck.java` (D-6).
- Modify: `WorkflowExecutor.java`: `startForeignServiceCheck`, after `startRetentionSweeper` (`:2108`). Its Javadoc covers the once-only check, the thread name, "never throws, never writes", and "the Spring Boot starter calls it at startup".
- Modify: `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/config/StartupRecoveryRunner.java`:
  - after the recovery start (`:59-68`), step "1b": `executor.startForeignServiceCheck(registrations);`;
  - the class Javadoc gains "It also starts a one-time advisory check, on its own thread, that warns about active workflows of this service's types recorded under another `maestro.service-name` (Issue 39)".
- Test (new): `maestro-core/src/test/java/io/b2mash/maestro/core/engine/ForeignServiceCheckTest.java`, `maestro-spring-boot-starter/src/test/java/io/b2mash/maestro/spring/config/StartupForeignServiceCheckTest.java`.

**Interfaces:**
- Consumes: Task 5's read.
- Produces: `startForeignServiceCheck`.

- [ ] **Step 1: Write the tests.**

  `ForeignServiceCheckTest` (core, a fake store overriding the read, recording its arguments and thread):

```java
@Test void asksForThisServicesTypesUnderOtherNamesAndReturnsTheCounts() {
    store.answer(Map.of("orders-old", 3L));
    assertEquals(Map.of("orders-old", 3L), ForeignServiceCheck.run(store, "orders", Set.of("order")));
    assertEquals(new Asked("orders", Set.of("order"), ForeignServiceCheck.MAX_REPORTED + 1), store.asked());
}
@Test void theWarningNamesBothServiceNamesTheCountAndTheRemedy() {
    assertEquals("3 active workflow instance(s) of a type this node runs are recorded under maestro.service-name "
            + "'orders-old', not 'orders'. Recovery adopts only 'orders' instances, so unless a node runs with "
            + "maestro.service-name=orders-old these workflows never resume: start one under that name, or "
            + "correct the rows (docs/operations.md §11.5).",
            ForeignServiceCheck.warning("orders", "orders-old", 3));
}
@Test void aStoreFailureIsSwallowed() { /* read throws StoreUnavailableException → run returns Map.of(), no throw */ }
@Test void anErrorIsSwallowedButAControlFlowErrorIsNot() {
    /* AssertionError → Map.of(); ExecutorShutdownException (a MaestroControlFlowError) → rethrown */
}
@Test void noRegisteredTypesMakesNoStoreCall() { /* run(store, "orders", Set.of()) → Map.of(), store.asked() == null */ }
@Test void startForeignServiceCheckReturnsAtOnceAndQueriesOnItsOwnThread() throws Exception {
    /* the read blocks on a latch and records Thread.currentThread().getName(); the executor is built over the fake
       store; startForeignServiceCheck(regs) is called from a virtual thread that counts down `returned`;
       assertTrue(returned.await(2, SECONDS)); release; assertEquals("maestro-foreign-service-check", threadName within 5 s) */
}
@Test void startForeignServiceCheckDuringShutdownDoesNothingAndDoesNotThrow() {
    /* executor.shutdown(); assertDoesNotThrow(startForeignServiceCheck); store.asked() stays null */
}
```

  `StartupForeignServiceCheckTest` (starter). Build the runner and node as in `StartupRecoveryRunnerTest` (`node`, `runnerFor`, `holdRegistrations`, `valueWithin`; copy, do not share). Attach a Logback `ListAppender` to `ForeignServiceCheck`'s logger (the `AdminCommandDispatcherRefusalTest:93-94` pattern). Seed an `InMemoryWorkflowStore`.

```java
@Test void aNodeBesideActiveInstancesUnderAnotherServiceNameWarnsOnceNamingItAndTheCount() {
    seed(store, "old-1", "orders-old", RUNNING); seed(store, "old-2", "orders-old", WAITING_SIGNAL);
    seed(store, "old-3", "orders-old", WAITING_TIMER); seed(store, "own-1", SERVICE, RUNNING);
    runnerFor(node(store, null), holdRegistrations(), recovery(true, Duration.ofSeconds(60))).run(null);
    var warns = valueWithin(() -> warnings().size(), 1, Duration.ofSeconds(5));
    assertEquals(1, warns);
    assertTrue(warnings().getFirst().startsWith("3 active workflow instance(s) of a type this node runs are recorded "
            + "under maestro.service-name 'orders-old'"), warnings().getFirst());
}
@Test void aCleanNodeLogsNoWarning() {
    /* only own instances; the store (an InMemoryWorkflowStore subclass) counts down `checked` when the read returns;
       assertTrue(checked.await(5, SECONDS)); assertEquals(0, warnings().size()) */
}
@Test void runReturnsWhileTheCheckIsStillRunning() throws Exception {
    /* the read blocks on `release`; run() on a virtual thread counts down `returned`;
       assertTrue(returned.await(2, SECONDS)) while the read is still blocked; then release */
}
@Test void aCheckThatThrowsNeverFailsStartup() {
    /* the read throws IllegalStateException; assertDoesNotThrow(() -> runner.run(null));
       within 5 s an INFO starting "Maestro service-name check skipped" is logged; no WARN */
}
```

  The seeded `orders-old` instances use type `HoldWorkflow` (registered), so they count. The recovery poller ignores them, because they are another service's.
- [ ] **Step 2: Add the seam only, and run red.**
  - Add `ForeignServiceCheck` complete (new code with its own tests) and `startForeignServiceCheck`. Do **not** wire the runner.
  - Run `./gradlew --no-watch-fs :maestro-core:test --tests 'io.b2mash.maestro.core.engine.ForeignServiceCheckTest'`. Expected: PASS.
  - Run `./gradlew --no-watch-fs :maestro-spring-boot-starter:test --tests 'io.b2mash.maestro.spring.config.StartupForeignServiceCheckTest'`. Expected FAIL:
    - `aNodeBeside…` with `expected: <1> but was: <0>`;
    - `aCleanNode…` on `checked.await` (`false`: the check never ran);
    - `aCheckThatThrows…` on the missing `INFO`.
    - `runReturns…` passes trivially, which is acceptable: it pins a property the wiring must keep.
  - Paste the FAIL lines into the commit body.
- [ ] **Step 3: Implement:** the runner's one call and its Javadoc.
- [ ] **Step 4: Run green.** The same commands, then `:maestro-spring-boot-starter:test --tests '*StartupRecoveryRunnerTest'`. Expected: PASS.
- [ ] **Step 5: Mutation checks** (reverted):
  - the runner calls `ForeignServiceCheck.run` synchronously (through a temporary package-visible helper) instead of `startForeignServiceCheck`: `runReturns…` goes red;
  - `run` without its `catch`: `aCheckThatThrows…` goes red (`run()` threw, or no `INFO`).
- [ ] **Step 6: Commit:** `feat(engine): warn once per foreign service name holding this service's active workflows at startup (Issue 39)`.

---

### Task 7: Docs, release note, issues, perf hand-off, gates

**Files:** `docs/configuration.md`, `docs/operations.md`, `docs/release-notes.md`, `docs/open-issues.md`, `CLAUDE.md`. Parts marked **[MD-1]** are written only if Task 4 shipped.

- [ ] **Step 1: `docs/configuration.md`.**
  - The janitor rows (`:764-765`): `janitor-interval` becomes "… Every node runs the janitor, without leader election. It deletes only this service's signals, in batches of 1,000, pausing between batches as long as the previous batch took (at most 5 s)". Cite `SignalJanitorBatchingTest` and `PostgresStoreHygieneTest`.
  - Retention section (`:819-848`):
    - after the leader sentence, add "Between two batches the sweeper waits as long as the previous batch took, at most 5 s (a third of the leader TTL), so a sweep keeps the database busy about half of the time it runs; a sweep of B batches of t each takes about B·t + (B−1)·min(t, 5 s) (arithmetic)" (`RetentionSweeperPacingTest`);
    - **[MD-1]** "A sweep purges only workflows recorded under this node's `maestro.service-name`";
    - the `max-batches-per-sweep` row gains "the pause adds no batch".
- [ ] **Step 2: `docs/operations.md`.**
  - **§11.1** (`:1117-1147`): add the pacing paragraph, D-1 in operator words with the 5 s reason. The drain-rate sentence "50 000 instances per status per hour" stays. The janitor paragraph (`:1143-1146`) gains the scope and the batches.
  - **[MD-1]** in §11.1 "by whom": "… purges this service's workflows only; services sharing tables each purge their own, with their own retentions".
  - **§11.3:** the two changed `INFO` lines.
  - **New §11.5 "Rows recorded under another `maestro.service-name`":**
    - Issue 39's `WARN`: its text, what triggers it (active instances of this node's types), what it does not count (terminal rows, other types), and that it runs once per node start, off the startup path (`StartupForeignServiceCheckTest`);
    - the listing query (release notes `:270-275`, extended with `workflow_type`);
    - the two remedies (run a node under that name, or correct `service_name` on the rows; the latter only while no node runs the old name);
    - **[MD-1]** terminal rows under a name no node runs are never purged: how to find them (`SELECT service_name, status, count(*), min(completed_at) FROM maestro_workflow_instance WHERE completed_at IS NOT NULL GROUP BY 1, 2`) and the manual purge, exactly `MANUAL_PURGE_SQL` (pinned by `PostgresStoreHygieneTest.theDocumentedManualPurgeRemovesAnAbandonedServicesHistory`);
    - **[MD-1]** the shared-table scan cost (Q3b, "measured on the planning probe") and Issue 78.
  - **§13** table (`:1307-1314`) gains three rows:
    - "Retention sweep (leader only): per batch 1 leader upsert + 1 candidate select + 4 deletes; ≤ 3 × `max-batches-per-sweep` batches, each after the first paused as long as the previous one (≤ 5 s); every `retention.interval` (1 h)";
    - "Signal janitor (every node): ⌈(U + 1) / 1,000⌉ deletes for U stale unconsumed signals of this service, paced the same way; every `janitor-interval` (1 h)";
    - "Service-name check: 1 read per node start, over the active rows of all services in the tables".
- [ ] **Step 3: `docs/release-notes.md`.** Add a new first subsection under `## Unreleased — Postgres + Kafka only`: `### Changed — retention and the signal janitor pace their batches and keep to their own service; a startup warning for workflows under another service name (3.11a, 3.11b, Issue 39)`. Bullets:
  - **What was wrong.** One sweep ran up to 300 purge transactions back to back. The probe's 45 ms per 500-instance batch and 27 MB of WAL per 20,000 instances, so ≈ 200 MB in ≈ 14 s (arithmetic). The janitor deleted every service's stale signals in one statement on every node (69,921 rows in one 342 ms statement on the probe). **[MD-1]** The sweep purged every service's workflows with this service's retentions. Workflows under another service name were stranded silently.
  - **What changes.** D-1, D-3, D-6 in operator words. **[MD-1]** D-4.
  - **Behaviour change [MD-1]:** in shared tables each service purges only its own rows, and rows under a name no node runs are no longer purged (§11.5 gives the manual purge).
  - **Logs:** the changed and new lines (D-6, D-8).
  - **API / SPI:**
    - the three defaults and what their defaults do (today's behaviour);
    - `getServicePurgeCandidatesSql()` for JDBC subclassers **[MD-1]**;
    - `WorkflowExecutor.startForeignServiceCheck`;
    - "a custom store keeps compiling; override the overloads for the bound and the scope".
  - **Mixed fleets.** Old and new nodes interoperate. An old node's janitor and sweep stay unscoped and unpaced, which is correct, as before. No migration, history or configuration change.
- [ ] **Step 4: `docs/open-issues.md`.**
  - **Issue 39:** status line `> **Resolved** in WP8 (branch fix/store-hygiene): one advisory query per node start, off the startup path; one WARN per other service_name holding active instances of this node's workflow types. Pinned by StartupForeignServiceCheckTest, ForeignServiceCheckTest, PostgresStoreHygieneTest$ForeignServiceCounts.` Index row `:337` → `**Resolved**`.
  - **Issue 64:** one sentence: "The signal janitor and the retention sweeper now end a pause between batches on a stop latch, without the interrupt; their `requestStop` still interrupts, for the store call in progress."
  - **New issues**, each with the house sections (what happens, file:line, harm, fix shape, why it is not WP8) and an index row:
    - **77 [MD-1].** Terminal workflows recorded under a `service_name` no node runs are never purged. Fix shape: an operator command, or extend the startup check to report them. Severity Low.
    - **78 [MD-1].** In shared tables, a scoped purge reads past a co-tenant's rows that a longer retention keeps (86 ms per batch for 35k rows, planning probe). Fix shape: V102 `(service_name, status, completed_at) WHERE completed_at IS NOT NULL`, with V101's pre-create guidance. Severity Low.
    - **79.** The service-name check warns about a live co-tenant service that registers the same type names (MD-4). Fix shape: suppress names holding a live timer-poller leader row. Severity Low.
- [ ] **Step 5: `CLAUDE.md`.**
  - The `WorkflowStore` block (`:101-102`): after `deleteUnconsumedSignalsForTerminalInstancesBefore(Instant)` add `default int deleteUnconsumedSignalsForTerminalInstancesBefore(String serviceName, Instant cutoff, int limit); // janitor batch, this service only; default = the unscoped, unbounded one`. After `purgeTerminalInstancesBefore` add **[MD-1]** `default PurgeResult purgeTerminalInstancesBefore(String serviceName, WorkflowStatus status, Instant cutoff, int batchSize); // this service only; default = unscoped`. After the paged `getRecoverableInstances` add `default Map<String, Long> getActiveInstanceCountsUnderOtherServices(String serviceName, Set<String> workflowTypes, int limit); // startup advisory (Issue 39); default empty`.
  - The recovery paragraph (`:221`): after "…or its workflows are stranded." add "At startup each node runs one advisory read off the runner thread and WARNs once per other `service_name` holding active instances of its workflow types (`WorkflowExecutor.startForeignServiceCheck`)."
  - The configuration paragraph (`:246-254`): the janitor clause gains "this service's only, in batches of 1,000"; the retention clause gains "**[MD-1]** of this service only, pausing between batches as long as the last batch took (≤ 5 s)".
- [ ] **Step 6: Reviewer checks.**
  - The existing-tests check (statement-level, above): paste its output into the commit body.
  - `grep -n "deleteUnconsumedSignalsForTerminalInstancesBefore(" maestro-core/src/main/java/io/b2mash/maestro/core/engine/SignalJanitor.java` shows only the three-argument call.
  - **[MD-1]** `grep -n "purgeTerminalInstancesBefore(" maestro-core/src/main/java/io/b2mash/maestro/core/engine/RetentionSweeper.java` shows only the four-argument call.
  - `grep -n "interrupt\|sleep" maestro-core/src/main/java/io/b2mash/maestro/core/engine/BatchPacer.java` shows only the restore of the interrupt flag.
  - `grep -n "getActiveInstanceCounts\|ForeignServiceCheck.run" maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/config/StartupRecoveryRunner.java` prints nothing: the runner only starts the check.
  - `./gradlew --no-watch-fs :maestro-core:javadoc :maestro-store-jdbc:javadoc :maestro-store-postgres:javadoc :maestro-test:javadoc :maestro-spring-boot-starter:javadoc`. Expected: no warning naming a changed member.
  - `grep -rn "WP8\|MD-[0-9]\|D-[0-9]\|3\.11" maestro-*/src/main` prints nothing.
- [ ] **Step 7: Module tests.** Run these one at a time:
  - `./gradlew --no-watch-fs :maestro-core:test`;
  - `./gradlew --no-watch-fs :maestro-store-jdbc:test`;
  - `./gradlew --no-watch-fs :maestro-store-postgres:test`;
  - `./gradlew --no-watch-fs :maestro-test:test`;
  - `./gradlew --no-watch-fs :maestro-spring-boot-starter:test`;
  - `./gradlew --no-watch-fs :maestro-integration-tests:test --tests '*MultiNodeRetentionPurgeIT' --tests '*EnginePostgresSignalIT'`.

  Expected: all PASS.
- [ ] **Step 8: The perf patterns, at merge time.** Run `git -C /Users/rakheendama/Projects/2026/maestro fetch origin && git -C /Users/rakheendama/Projects/2026/maestro show origin/main:perf/sql/pgss-window.sql | grep -n "maestro_workflow_signal WHERE consumed\|completed_at < "`.
  - If it prints D-9's two patterns, the guards in Tasks 2 and 4 cover them. Record "unchanged" in the review section.
  - If WP12 changed either pattern, set the guards' expected prefixes to the new regex's literal text, re-run `:maestro-store-postgres:test --tests '*PostgresStoreHygieneTest'`, and note it.
  - If WP8's statements cannot match the new pattern, add a commit on this branch updating `perf/sql/pgss-window.sql` and the self-test case WP12 Task 9 Step 1 names. That is the only `perf/` edit allowed here, and it is made only in this case.
- [ ] **Step 9: Commit:** `docs: WP8 release note; resolve Issue 39; file issues 77-79; retention pacing, scope and the service-name check`.
- [ ] **Step 10: Coordinator gates, before the PR merges.** Each is launched detached with a log header (`pwd`, `git rev-parse HEAD`, timestamp).

| Gate | Command | Pass criterion |
|---|---|---|
| Uncached build | `./gradlew --no-watch-fs build --no-build-cache --rerun-tasks` | `BUILD SUCCESSFUL` |
| Whole integration suite | `./gradlew --no-watch-fs :maestro-integration-tests:test` | `BUILD SUCCESSFUL` |
| Nightly E2E on the branch | `gh workflow run e2e-nightly.yml --ref fix/store-hygiene` | the run concludes `success` |
| Chaos PR-gate ×3 | `./gradlew --no-watch-fs :maestro-integration-tests:e2eTest --rerun-tasks`, three consecutive fresh-seed runs | 3/3 green; every I1–I5 invariant holds (operations §8); zero `FAILED` workflows the path script did not declare; no Issue 39 `WARN` in any node log (every sample service runs under one name) |
| 2 h soak | **not required** for WP8 (spec §6 lists WP1, WP2, WP3, WP5, WP6) | — |
| WP12 S6 | WP12 Task 9: preview on an overlay of this branch's tip, then the run of record after merge | `PASS` (`gate.retentionPaced`, `gate.janitorServiceScoped`, `gate.janitorBatched`), recorded in WP12's review section |

  Every result goes in the review section with its log identity. Send the hand-off below to WP12's owner.

---

## Hand-off to WP12 (`s6-retention`, Task 9)

- **Janitor statement** (pgss text): `DELETE FROM maestro_workflow_signal WHERE consumed = FALSE AND id IN (SELECT s.id FROM maestro_workflow_signal s JOIN maestro_workflow_instance i ON i.id = s.workflow_instance_id WHERE s.consumed = FALSE AND i.status IN ($1, $2) AND i.completed_at < $3 AND i.service_name = $4 FETCH FIRST $5 ROWS ONLY)`. The constants may print as literals or `$n`. `signalJanitor` still matches.
  - Batch constant: `SignalJanitor.BATCH_SIZE = 1_000` (file:line after merge), for `gate.janitorBatched`'s documented assumption. Local J-own (10,000) predicts 11 calls (10 full plus 1 empty), rows/call ≈ 909 ≤ 5,000: PASS.
  - The statement contains ` AND id IN (`. If S6's sampler matches ` id IN (` loosely, exclude this text, or the busy fraction counts janitor work as purge work.
- **Retention candidate select [MD-1]:** `SELECT id FROM maestro_workflow_instance WHERE status = $1 AND completed_at < $2 AND service_name = $3 ORDER BY completed_at ASC LIMIT $4 FOR UPDATE SKIP LOCKED`. `retention` and S6's sampler pattern still match. The child deletes and the leader upsert are unchanged.
- **Pacing.** The pause covers the store call only. The leader upsert and commit gaps sit outside the batch, so the measured busy fraction ≈ 0.5 + 1/(2B) plus the upsert share: ≈ 0.50–0.55 for S6 local's ≈ 122 batches. The sweep span about doubles (still well inside S6's 180 s allowance at the probe's batch times).
- **X-foreign [MD-1]:** after WP8 it is **kept** (0 purged). Update `advisory.foreignRetention`'s "after" prediction, and do not file Issue 76 for the retention scope (WP8 took it). If MD-1 is "no": unchanged, and WP12 files 76.
- **Issue 39 statement:** one per node start, `SELECT service_name, count(*) FROM maestro_workflow_instance WHERE status IN (…) AND service_name <> $n AND workflow_type IN (…) GROUP BY …`. No sweeps or S3 classifier matches it (D-6).
  - **`advisory.foreignServiceWarn` as planned in WP12 Task 9 Step 2 would read 0, not 1.** The check counts **active** instances of the node's workflow types, and S6's `perf-foreign` rows are all terminal. To see one WARN, seed one active `perf-foreign` instance of a `sample-perf` workflow type.

## Self-review (run 2026-11-17)

- **Brief coverage.**
  1. Today's sweeper loop (batch size, budget, between batches, lock hold, leader cadence) and the janitor (one unbounded statement, its plan): "Today", Traced and Measured.
  2. The cost of a large purge on foreground work, labelled reasoning: its own subsection.
  3. Whether an index is needed, the migration rule, `CREATE INDEX` locking, `CONCURRENTLY` under Flyway, and `validate`/`none`: D-5.
  4. Issue 39 (meaning, exact query, cost, when, WARN text and cadence, never-do list): D-6.
  5. The pause rule (cap, floor, budget, interval, leader loss, shutdown without an interrupt): D-1, D-2, D-10.
  6. Batch size: D-3, MD-2.
  7. SPI signatures and what each store gets: the surface table, D-7.
  8. Retention scope, mixed fleet and release-note text: D-4, MD-1, Task 7 Step 3.
  9. Observability: D-8, MD-5.
  10. Existing tests changed: their own section.
  11. Migration: D-5 (none).
  12. Perf SQL patterns and the merge-time check: D-9, Task 7 Step 8, Hand-off.
  13. The WP12 service-scoping note, answered: "Service scope today".
- **Spec.** The WP8 row in full; §3's 300 batches and "no leader, no service filter"; the ordering note (Before you start, S1–S2, the pre-flight table); §6 gates without the soak (Task 7 Step 10); D8 (the WARN is the mitigation; the filter stays).
- **Type consistency.** These names are the same in every task:
  - `BatchPacer.{MAX_PAUSE, Wait, LATCH, now, pauseFor, pauseAfter, requestStop, stopRequested}`;
  - `SignalJanitor.BATCH_SIZE`;
  - `deleteUnconsumedSignalsForTerminalInstancesBefore(String, Instant, int)`;
  - `purgeTerminalInstancesBefore(String, WorkflowStatus, Instant, int)`;
  - `getServicePurgeCandidatesSql()`;
  - `getActiveInstanceCountsUnderOtherServices(String, Set<String>, int)`;
  - `ForeignServiceCheck.{run, warning, MAX_REPORTED, THREAD_NAME}`;
  - `WorkflowExecutor.startForeignServiceCheck(Map)`.
- **Proportion.** The code blocks are test assertions, SQL, signatures, log copy and commands. Method bodies are left to D-1–D-6 and the tests.
- **Risk left open.**
  - The janitor's last, empty batch can take the generic plan. On the probe that cost 795 ms, against 194 ms for today's single statement: once an hour per node, bounded, but ×4 on that one statement. WP12 S6 measures it at scale; if it matters, D-5's partial index is the next step.
  - Task 1's event-order test depends on D-2's "a zero pause never reaches `Wait`". If an implementer finds a reason to let it, the expected lists in Task 1 change with it, as a named step.
  - The manual purge SQL is pinned only on Postgres 16 (the Testcontainers image).

## Review section

(The coordinator fills this in: the red and mutation lines per task; S1–S4 as found; the MD rulings, and whether Task 4 shipped; the issue numbers given to 77–79; Task 7 Step 8's pattern check; gate results with log identity; the WP12 hand-off as sent.)
