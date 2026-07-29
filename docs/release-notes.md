# Release Notes — Maestro 0.3.0

This release closes out a release-readiness hardening pass: nine of the ten
issues found during Maestro's first real-backend verification effort
(`docs/open-issues.md`, tracked as PR #27's follow-up work) are fixed, the
tenth (test coverage) is closed, and two are kept open by deliberate design
decision. It includes source-breaking SPI and API changes — read
[Breaking Changes](#breaking-changes) before upgrading.

Maestro is pre-1.0 (`0.3.0-SNAPSHOT`); semantic versioning guarantees don't
apply yet, but breaking changes are still called out explicitly below so
nothing is a surprise.

---

## New Features

### Bounded redelivery and dead-lettering, all transports

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

### `MaestroHealthIndicator`

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

### Timer self-healing on replay

A timer that fires and is marked `FIRED` in the store, but whose
`TIMER_FIRED` event is lost to a crash before it's appended to the workflow's
event log, previously stranded the workflow in `WAITING_TIMER` forever — no
poller would ever look at an already-`FIRED` row again. Replay now consults
the timer row directly (new `WorkflowStore.findTimer` — see
[Breaking Changes](#breaking-changes)) and, finding it already `FIRED`, heals
by appending the missing event and continuing, rather than re-parking.
Closes Issue 2.

### Configurable shutdown timeout, signal wake-recheck interval, and activity lock prefix

Three settings that were previously hardcoded now bind under `maestro.*`,
with unchanged defaults — see [Configuration](#configuration). Closes
Issues 7 and 9.

### `maestro.admin.events.enabled` now actually works

Setting it to `false` genuinely stops lifecycle event publishing (all event
families — workflow, activity, signal, timer, and compensation).
`maestro.admin.events.topic` is kept as a deprecated alias for
`maestro.messaging.topics.admin-events`. Closes Issue 6.

---

## Configuration

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

## Behaviour Changes

- **Lifecycle event publishing is off the workflow thread.** Previously,
  `startWorkflow` and every terminal transition called
  `messaging.publishLifecycleEvent(...)` inline; a Kafka producer blocked on
  metadata for a missing topic could add up to `max.block.ms` (60s by
  default) to the workflow's hot path. It now runs on a bounded, dropping
  executor owned by the engine — `startWorkflow` returns promptly regardless
  of transport latency, and sustained backpressure is logged (rate-limited
  WARN) rather than silently absorbed. Closes Issue 3.
- **Signal handler failures are redelivered, not dropped.** See
  [New Features](#new-features) above. This is the headline behaviour change
  in the release.
- **Shutdown mid-compensation no longer records a compensation failure.** A
  node stopping while a saga's compensations are running now leaves the
  workflow `COMPENSATING` (active, recoverable) for the next node to finish,
  instead of recording the interrupted compensation step as failed. Closes
  Issue 5.
- **A `catch (Exception e)` around `awaitSignal()`/`sleep()` can no longer
  swallow a shutdown.** See `ExecutorShutdownException` in
  [Breaking Changes](#breaking-changes). Closes Issue 4.

---

## Breaking Changes

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

## Database Migrations

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

## Operator Upgrade Checklist

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
   [Breaking Changes](#breaking-changes) before upgrading — those three are
   compile-time breaks, not runtime surprises, so they'll surface
   immediately on build.
6. **If you run `@MaestroSignalListener` with a hand-registered bean
   post-processor** (rather than through Maestro's own auto-configuration),
   confirm a `KafkaTemplate` bean is present — see Breaking Change 4.

---

## Known Limitations

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

---

## See Also

- [`docs/open-issues.md`](open-issues.md) — the full, per-issue detail this
  document summarises: what was wrong, why, where, how it was fixed, and the
  tests that pin each fix.
- [`docs/configuration.md`](configuration.md) — complete configuration
  reference, including the new redelivery/dead-letter and shutdown/signal
  properties.
- [`docs/maestro-architecture.md`](maestro-architecture.md) — updated
  architecture reference for signal-loss handling, timer recovery, and
  shutdown semantics.
