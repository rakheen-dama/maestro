# Release Notes — Maestro

## Unreleased

### Bug Fixes

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
