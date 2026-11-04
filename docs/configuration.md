# Configuration Reference

Complete reference for all `maestro.*` configuration properties.

[← Back to README](../README.md)

---

## Overview

Maestro binds its configuration to the `maestro.*` namespace via Spring Boot's
`@ConfigurationProperties`. You can set properties in `application.yml`,
`application.properties`, environment variables, or any other Spring-supported
configuration source.

The only **required** property is `maestro.service-name`. Everything else has
sensible defaults and can be left unset for local development.

---

## Root Properties

| Property                    | Type       | Default | Description                                                                                                  |
|-----------------------------|------------|---------|--------------------------------------------------------------------------------------------------------------|
| `maestro.enabled`           | `boolean`  | `true`  | Master switch for Maestro auto-configuration. Set to `false` to disable the engine entirely.                 |
| `maestro.service-name`      | `String`   | --      | **Required.** Logical name of the owning service. Used for Kafka consumer groups, lock key prefixes, and lifecycle event attribution. Auto-configuration will fail if not set. |
| `maestro.workflow-packages` | `String[]` | --      | Comma-separated base packages to scan for `@DurableWorkflow` classes. Optional in a `@SpringBootApplication` context, where the application's own auto-configuration package is scanned automatically; set this explicitly in tests or other non-Boot-application contexts. Consumed by `DurableWorkflowBeanRegistrar`. |

**`maestro.enabled=false` disables every Maestro auto-configuration in the
application**, not just the core engine: `maestro-messaging-kafka`,
`maestro-store-postgres` (store, lock and the LISTEN/NOTIFY wake listener),
`maestro-admin-client`, and the health/observability
auto-configurations all carry the same `@ConditionalOnProperty(maestro.enabled)`
gate as the core `MaestroAutoConfiguration`, so flipping this one flag backs
every module off cleanly rather than leaving, say, a `LISTEN` connection
or Kafka consumer running for an engine that is otherwise off.

---

## Store Configuration

Properties under `maestro.store.*` control the workflow persistence layer.

| Property                   | Type     | Default     | Description                                                                                              |
|----------------------------|----------|-------------|----------------------------------------------------------------------------------------------------------|
| `maestro.store.type`       | `String` | `"postgres"`| Store implementation type. Currently only `postgres` is supported.                                       |
| `maestro.store.schema.mode`| `String` | `"flyway"`  | `flyway` applies pending Maestro migrations; `validate` fails startup if any are pending; `none` skips — you own the DDL. Case-insensitive. See [Schema management](#schema-management). |

**Isolating services that share a database.** Maestro's tables are always named `maestro_*`. Two services must never share a `maestro_workflow_instance` table, so give each service its own Postgres schema and point its datasource at it — `jdbc:postgresql://host/db?currentSchema=order_service` or `spring.datasource.hikari.schema: order_service`. Maestro's migrations (see [Schema management](#schema-management)) run in the connection's current schema, so no further configuration is needed. `maestro.store.table-prefix` was removed; setting it fails startup.

### Schema management

`maestro-store-postgres` creates and upgrades Maestro's `maestro_*` tables at startup with an embedded Flyway run that Maestro owns: it records what it applied in its **own history table, `maestro_schema_history`**, and never reads or writes your application's `flyway_schema_history`. Your `spring.flyway.*` settings (location, table, baseline, `enabled`) apply only to your own Flyway and are unaffected — a `V1__init.sql` of yours in `classpath:db/migration` does not collide with Maestro's `V1`. Your application does not need `spring-boot-starter-flyway` for Maestro; Flyway is already a runtime dependency of `maestro-store-postgres`. You do need `spring-boot-starter-jdbc` (for the `DataSource`).

`maestro-store-postgres` ships its SQL under two classpath locations, each in its own version band, and advertises them in a `META-INF/maestro/schema-locations` resource; the initializer applies every location it finds on the classpath:

| Concern                      | Location                         | Versions  |
|------------------------------|----------------------------------|-----------|
| Workflow store               | `classpath:db/maestro/store`     | 1–99      |
| Distributed lock + leader election | `classpath:db/maestro/lock` | 100–199   |
| *(retired)* Postgres messaging transport | *(no longer shipped)*  | 200–299 — never reused |

Band 200–299 belonged to the Postgres messaging transport (`V200`–`V202`), removed in favour of Kafka as the only transport. A database that applied it keeps its rows in `maestro_schema_history` and an orphan `maestro_signal_queue` table (and, if it never reached `V202`, `maestro_task_queue` and `maestro_lifecycle_event_queue`). Flyway's default `ignoreMigrationPatterns=*:future` treats those history rows as future versions, so `flyway` and `validate` modes keep passing; `DROP TABLE IF EXISTS maestro_signal_queue, maestro_task_queue, maestro_lifecycle_event_queue;` is optional cleanup once no node on an earlier build remains.

The initializer runs after your application's own database initializers (its Flyway, Liquibase or `spring.sql.init` scripts) and before the store and engine beans are created. When it first meets a schema that already holds your tables, it writes a baseline marker row at version `0` into `maestro_schema_history` and then applies every Maestro migration — your tables are not touched. Several nodes starting at once are safe: Flyway serialises concurrent runs with a database lock. (If you add Flyway to your own application *later*, against a database that already holds Maestro's tables, your Flyway needs `spring.flyway.baseline-on-migrate: true` for its first run, as for any non-empty schema.)

The ordering covers only database initializers that Spring Boot knows about (`spring.flyway.*`, `spring.liquibase.*`, `spring.sql.init.*`). If your application runs Flyway by hand — its own `Flyway.configure()...migrate()` in a bean or a runner — it is not ordered before Maestro's initializer: either migrate through `spring.flyway.*`, or set `maestro.store.schema.mode: none` and call `new MaestroSchemaInitializer(dataSource, SchemaMode.FLYWAY, classLoader).initialize()` yourself after your own migration.

The initializer belongs to `maestro-store-postgres` and runs only when `maestro.store.type` is `postgres` (the only supported store). The lock tables are created by that same initializer, from the same module.

**`validate` and `none`.** With `maestro.store.schema.mode: validate` Maestro applies nothing and fails startup — naming the pending versions — if any migration is pending, or if an applied one no longer matches the shipped SQL; it reads `maestro_schema_history`, so whoever applies the SQL out of band must record it there (e.g. the Flyway CLI with `-table=maestro_schema_history`, the locations above, and — on a schema that already holds your tables — `-baselineOnMigrate=true -baselineVersion=0`, exactly as Maestro's own run does). With `none` Maestro does nothing and reads no history: you apply the SQL under the locations above yourself, in version order, before deploying the build that ships it. A build at or past V5 omits `received_at` from signal inserts, so a self-managed database must have V5 applied (`ALTER TABLE maestro_workflow_signal ALTER COLUMN received_at SET DEFAULT now()`) before that build is deployed, or every signal insert fails. A build at or past V6 names `maestro_workflow_instance.lease_epoch` in every instance read and write (the lease-fencing token), so V6 (`ALTER TABLE maestro_workflow_instance ADD COLUMN IF NOT EXISTS lease_epoch BIGINT NOT NULL DEFAULT 0`) must likewise be applied first, or no workflow can be started, resumed or read.

**Upgrading an existing database.** A database migrated by an earlier Maestro build, before Maestro owned its history (0.4.0 applied `V1`–`V3`, `V100`, `V200` and `V201`), has Maestro's rows in your `flyway_schema_history` and no `maestro_schema_history`, and the new build's first start fails either way:

- if your application still runs its own Flyway with migrations of its own, *your* Flyway fails first — `Detected applied migration not resolved locally` — because Maestro's rows remain in `flyway_schema_history` while their scripts are no longer in `classpath:db/migration` (Flyway's default `ignore-migration-patterns` is `*:future` only);
- otherwise — no Flyway, or a Flyway that resolves no migrations and so validates nothing — Maestro's `V1` fails because its tables already exist.

Upgrade such a database once, with this procedure, in order (the same text is in `docs/release-notes.md` → "Maestro owns its schema migrations"):

1. **Stop every node** of every service that uses the database — no node running an earlier build may stay connected. This upgrade cannot roll: an earlier node locking in Valkey and a node on this build locking in `maestro_distributed_lock` do not exclude each other, an earlier build's Postgres transport still reads the retired queue tables, and an earlier node must not adopt workflows this build has run (`docs/operations.md` §10.1).
2. **Either drop Maestro's tables or keep them** — pick one:
   - **Fresh start (discards every workflow, signal and timer).** Drop the tables and leave `maestro.store.schema.mode` at its default, `flyway`; the new build recreates the schema and records it in `maestro_schema_history`:

     ```sql
     DROP TABLE IF EXISTS maestro_workflow_signal, maestro_workflow_timer, maestro_workflow_event,
         maestro_workflow_instance, maestro_distributed_lock, maestro_leader_election,
         maestro_signal_queue, maestro_task_queue, maestro_lifecycle_event_queue;
     ```

   - **Keep the data.** Set `maestro.store.schema.mode: none` on every node — you own Maestro's DDL from then on, applying each later migration by hand before deploying the build that ships it — and apply by hand the three migrations a 0.4.0 database lacks: `V4__signal_trace_context.sql`, `V5__signal_received_at_default.sql` and `V6__instance_lease_epoch.sql` (`maestro-store-postgres`, `classpath:db/maestro/store`). As written here they are safe to re-run, including on a database a pre-release build already took to V4, V5 or V6; the retired messaging transport's queue tables may be dropped at the same time (optional — nothing on this build reads them):

     ```sql
     ALTER TABLE maestro_workflow_signal ADD COLUMN IF NOT EXISTS trace_context VARCHAR(128);  -- V4
     ALTER TABLE maestro_workflow_signal ALTER COLUMN received_at SET DEFAULT now();         -- V5
     ALTER TABLE maestro_workflow_instance ADD COLUMN IF NOT EXISTS lease_epoch BIGINT NOT NULL DEFAULT 0; -- V6
     DROP TABLE IF EXISTS maestro_signal_queue, maestro_task_queue, maestro_lifecycle_event_queue; -- optional cleanup
     ```

     Do not skip V4, V5 or V6: this build names `trace_context` in every signal insert and leaves `received_at` to the column default, so without them every `saveSignal` fails — inside the Kafka listener, so each signal is redelivered until it is dead-lettered; and it names `lease_epoch` in every instance read and write (it is the lease-fencing token — see [Operations §6](operations.md#6-split-brain-behaviour-issue-11--issue-18)), so without V6 no workflow can be started, resumed or read. V6 is metadata-only on PostgreSQL 11+ (a constant `DEFAULT` on `ADD COLUMN` rewrites no rows) and every pre-existing row reads back as epoch `0`.
3. **Delete Maestro's rows from your `flyway_schema_history`** (or the table `spring.flyway.table` names) — on either path; skip this step only if the table does not exist. The statement matches each row by version **and** script name, so none of your own migrations can match, and rows your database never had are simply not matched:

   ```sql
   DELETE FROM flyway_schema_history
   WHERE (version, script) IN (
       ('1',   'V1__create_maestro_schema.sql'),
       ('2',   'V2__create_indexes.sql'),
       ('3',   'V3__timer_lookup_index.sql'),
       ('4',   'V4__signal_trace_context.sql'),
       ('5',   'V5__signal_received_at_default.sql'),
       ('6',   'V6__instance_lease_epoch.sql'),
       ('100', 'V100__maestro_lock_postgres.sql'),
       ('200', 'V200__maestro_messaging_postgres.sql'),
       ('201', 'V201__redelivery_dead_letter.sql'));
   ```

   Without this, an application whose own Flyway has migrations fails startup with `Detected applied migration not resolved locally` — and one whose Flyway has none yet fails the day it adds its first.
4. **Start the new build** on every node.

---

## Messaging Configuration

Properties under `maestro.messaging.*` control signal delivery and lifecycle
event publishing. There is no task-dispatch channel: a workflow runs in-process
on the node that started it.

### Core Properties

| Property                        | Type     | Default    | Description                                                                                     |
|---------------------------------|----------|------------|-------------------------------------------------------------------------------------------------|
| `maestro.messaging.type`        | `String` | `"kafka"`  | Messaging implementation. `kafka` is the only supported value; any other value (including the removed `postgres`) fails startup with a message naming the removal. |
| `maestro.messaging.consumer-group`| `String`| `null`   | Kafka consumer group ID. If not set, defaults to `maestro-{serviceName}` at runtime.            |

### Topic Properties

| Property                                 | Type     | Default                  | Description                                                       |
|------------------------------------------|----------|--------------------------|-------------------------------------------------------------------|
| `maestro.messaging.topics.signals`       | `String` | `null`                   | Topic for inbound cross-service signals. Resolved at runtime based on the service name.        |
| `maestro.messaging.topics.admin-events`  | `String` | `"maestro.admin.events"` | Topic for lifecycle events consumed by the admin dashboard.       |

`maestro.messaging.topics.tasks` has been removed. It configured a task topic
nothing ever consumed; setting it now **fails startup** — on either transport,
or with none — with a message telling you to delete it and the `maestro.tasks.*`
topics.

When `consumer-group` is not explicitly set, Maestro derives it from the service
name as `maestro-{serviceName}`. This ensures each service gets its own consumer
group by default, which is the correct behavior for most deployments.

### Redelivery and Dead-Letter Properties

A message handler that throws has **not** processed the message — on the signal
channel it means the signal is not yet in Postgres — so the record's offset is
not committed. The Kafka transport redelivers with exponential backoff and, once
the attempt budget is spent, routes the record to a durable, inspectable
dead-letter topic rather than dropping it or looping on it forever.

| Property                                          | Type       | Default                 | Description                                                                 |
|---------------------------------------------------|------------|-------------------------|-----------------------------------------------------------------------------|
| `maestro.messaging.redelivery.enabled`            | `boolean`  | `true`                  | Whether handler-failure redelivery and dead-lettering are active at all.    |
| `maestro.messaging.redelivery.max-attempts`       | `int`      | `10`                    | Total delivery attempts, including the first.                               |
| `maestro.messaging.redelivery.initial-interval`   | `Duration` | `1s`                    | Backoff before the second attempt.                                          |
| `maestro.messaging.redelivery.multiplier`         | `double`   | `2.0`                   | Factor applied to the backoff after each failure.                           |
| `maestro.messaging.redelivery.max-interval`       | `Duration` | `30s`                   | Ceiling for the computed backoff.                                           |
| `maestro.messaging.redelivery.dead-letter-suffix` | `String`   | `".DLT"`                | Appended to a topic to name its dead-letter topic.                          |

**Disabling redelivery.** Setting `maestro.messaging.redelivery.enabled=false`
is the operator's explicit opt-out of everything below —
it is not a recommended default, and defaults to `true`: the listener container
gets a `DefaultErrorHandler` backed by a zero-length `FixedBackOff` instead of
`KafkaRedeliveryErrorHandlers.deadLettering(...)`. A failing record gets **zero
retries** and **no `DeadLetterPublishingRecoverer`** — it is logged and skipped,
restoring plain at-most-once handler semantics. The
[dead-letter-topic startup probe](#kafka-dead-letter-topic-check) below is
skipped entirely, since nothing will ever publish to a `.DLT` topic;
`max-attempts` and the backoff properties are ignored while the flag is off.

### Kafka Dead-Letter-Topic Check

Maestro never creates topics, `.DLT` companions included (see below) — an
operator who forgets to pre-create one does not find out until a handler's
attempt budget is first exhausted, and the failure then shows up as a stalled,
noisily-retrying consumer rather than a clear message while the gap could
still be fixed for free.

`KafkaDeadLetterTopicCheck` closes that gap: at every point Maestro subscribes
to a topic — `KafkaWorkflowMessaging.subscribeSignals` (the engine's own
signal channel) and `MaestroSignalListenerBeanPostProcessor`'s
container activation (every `@MaestroSignalListener` topic) — it probes whether
`<topic><dead-letter-suffix>` exists and logs:

```text
WARN Dead-letter topic '<topic>.DLT' does not exist — redelivery for '<topic>' will
     exhaust its attempts and then fail to publish; pre-create it or set
     maestro.messaging.redelivery.enabled=false
```

The probe is **warn-only**: it never fails startup, is bounded to 5 seconds,
and its own failure (an unreachable broker, for instance) is logged at `DEBUG`
and otherwise ignored — a diagnostic, not a gate. It is skipped entirely when
`maestro.messaging.redelivery.enabled=false`, since nothing will ever publish
to a dead-letter topic in that mode.

**`.DLT` pre-creation checklist.** Before deploying a service with Kafka
messaging and redelivery enabled (the default), pre-create a `.DLT` companion
for every topic **a Maestro consumer subscribes to**:

- [ ] The engine's inbound signal topic: `maestro.signals.{serviceName}.DLT`
      (or the fixed override `maestro.messaging.topics.signals` + suffix).
- [ ] Every `@MaestroSignalListener(topic = "...")` topic this service
      declares.

Plain `@KafkaListener`-consumed topics are **not** in scope — they run outside
Maestro's redelivery path entirely and get no dead-lettering error handler, so
they need no `.DLT` companion. The admin-events topic is publish-only from a
workflow service's perspective (only `maestro-admin` consumes it, over its own
listener, not this mechanism) and is likewise out of scope.

The delay before the attempt following the *n*-th failure is
`min(initial-interval × multiplier^(n-1), max-interval)`. The defaults give
1s, 2s, 4s, 8s, 16s, 30s, 30s, 30s, 30s between 10 attempts — roughly 2.5
minutes of tolerance, long enough to ride out a store blip and short enough
that a poison message does not stall a service's signal channel for long.

**Tuning.** Raise `max-attempts` if your store outages routinely run longer
than the budget (an outage longer than the budget dead-letters signals, which
then need an operator replay — they are parked, never lost). Lower it to
contain a poison message sooner. There is no unbounded mode: a poison message
would stall redelivery forever behind it — this stalls the whole
*topic* on that node (the default listener concurrency is one consumer
thread per topic, which owns every partition assigned to it, not just the
failed record's partition).

**Fatal exceptions bypass retries.** `DefaultErrorHandler` treats a
handful of exception types as unrecoverable regardless of the configured
budget — deserialization failures, `ClassCastException`, and other framework
conversion errors — and sends the record straight to the dead-letter topic on
the first attempt rather than retrying it. This is spring-kafka's own default
classification, not something Maestro configures; the `max-attempts` budget
above only governs exceptions it doesn't consider fatal.

**Where exhausted messages go:** `<topic>` + `dead-letter-suffix`, e.g.
`maestro.signals.order-service.DLT` — created by **the operator**; Maestro never
creates topics. If a dead-letter topic is missing, the publish fails, the
offset is not committed and the record is attempted again: consumption stalls
noisily instead of losing the message.

**Inspecting and replaying.** A dead-letter topic is an ordinary
topic — read it with any consumer (`kafka-console-consumer --topic
maestro.signals.order-service.DLT --from-beginning --property
print.headers=true`; the `kafka_dlt-*` headers carry the original topic,
partition, offset and exception) and replay by producing the record back to the
source topic with the same key. A consequence worth knowing: a poison message
that crashes the JVM is redelivered on restart until `max-attempts` is spent
(the offset is never committed), then dead-lettered instead of looping forever.

---

## Lock Configuration

Properties under `maestro.lock.*` configure the distributed locking layer used
for workflow instance locks, activity deduplication, and timer leader election.
The lock is table-backed, in the same PostgreSQL database as the workflow store,
and ships in `maestro-store-postgres` — there is no separate lock module and
nothing extra to deploy.

| Property                        | Type       | Default          | Description                                                                                  |
|---------------------------------|------------|------------------|----------------------------------------------------------------------------------------------|
| `maestro.lock.type`             | `String`   | `"postgres"`     | Lock implementation. `postgres` is the only supported value; any other value (including the removed `valkey`) fails startup with a message naming the removal. |
| `maestro.lock.key-prefix`       | `String`   | `"maestro:lock:"`| Prefix for all lock keys — the `lock_key` column of `maestro_distributed_lock`. Change this to isolate multiple environments that share a schema. Applies to the instance lock and the activity lock alike. |
| `maestro.lock.ttl`              | `Duration` | `30s`            | Lock time-to-live. Locks are automatically renewed while the workflow is executing. If a node crashes, locks expire after this duration, enabling recovery by another node. |
| `maestro.lock.cleanup-interval` | `Duration` | `10m`            | How often the lock cleaner deletes rows whose TTL expired more than a minute ago. Every node runs it (the delete is idempotent). Expired rows never block an acquire — `tryAcquire` treats an expired row as free — so the cleaner only bounds table growth. |

The TTL value represents a trade-off: shorter values enable faster recovery after
a crash, but require more frequent renewal. The default of 30 seconds is suitable
for most workloads.

### Postgres Locking

`PostgresDistributedLock` (`io.b2mash.maestro.store.postgres.lock`) keeps locks
in two tables, created by Maestro's schema initializer from
`classpath:db/maestro/lock` (band 100–199; see [Schema management](#schema-management)):

- `maestro_distributed_lock` — one row per held key. `tryAcquire` is a single
  `INSERT ... ON CONFLICT (lock_key) DO UPDATE` whose update predicate requires
  the existing row's `expires_at` to be in the past, so an unexpired lock held by
  another node is never stolen and an expired one is taken over atomically. The
  returned handle carries a random owner token; `release` and `renew` are
  conditioned on that token, so a node whose lock expired and was re-acquired
  elsewhere can neither release nor extend the new holder's row.
- `maestro_leader_election` — one row per election key (`trySetLeader`), same
  expiry-predicate CAS, used by the timer poller.

The lock shares the application `DataSource` with the store: an acquire, renew
(every 10 s per running workflow) or release is one short statement on the
pool. Timestamps come from the database clock, so node clock skew does not
affect expiry. No extra dependency is needed beyond `maestro-store-postgres`.

---

## Worker Configuration

Properties under `maestro.worker.*` bound how many workflow runs and live
activity executions each task queue may have in flight at once on this node.
A task queue is the bucket a workflow names in
`@DurableWorkflow(taskQueue = "...")` (default `default`); every activity a run
calls counts against its run's queue.

| Property                                            | Type                       | Default | Description                                                          |
|-----------------------------------------------------|----------------------------|---------|----------------------------------------------------------------------|
| `maestro.worker.task-queues`                        | `List<TaskQueueProperties>`| `[]`    | Task queues to bound on this node, each with its concurrency limits. Not a subscription: listing a queue routes nothing to this node. |
| `maestro.worker.task-queues[].name`                 | `String`                   | --      | **Required.** Name of the task queue.                                |
| `maestro.worker.task-queues[].concurrency`          | `int`                      | `10`    | Maximum number of concurrent workflow executions for this queue.     |
| `maestro.worker.task-queues[].activity-concurrency` | `int`                      | `20`    | Maximum number of concurrent activity executions for this queue.     |

`concurrency` caps how many workflow runs on this queue execute at once on this node; a run holds its slot from start until it parks on `sleep()`/`awaitSignal()` or reaches a terminal status, and reacquires one before resuming. `activity-concurrency` caps live activity executions per queue on this node; replayed (memoized) steps never take a slot. Queues not listed here are unbounded. Limits are per node — three nodes with `concurrency: 10` run up to 30 at once.

A run waiting for a slot holds its instance lock (so no other node adopts it)
but has executed nothing; if the node begins shutting down before a slot frees,
it stands down and stays recoverable. The same goes for a run waiting for an
activity slot: the activity does not run. An activity slot is held from the
activity's dedup lock through every retry attempt and backoff to its persisted
outcome. Every limit must be `>= 1`, and naming a queue twice fails startup.

On a saturated queue, a parked run's periodic re-check (which picks up a
terminate issued on another node and is the fallback for a missed wake) waits
for a free slot too, so convergence is bounded by slot availability rather than
by `maestro.signal.wake-recheck-interval`. An activity that calls
`startAndWait` on a workflow of its **own** queue can deadlock at
`concurrency: 1` until the wait times out — the waiting run holds the only
slot the started workflow needs — so start such workflows asynchronously or
give them another queue.

**Example:**

```yaml
maestro:
  worker:
    task-queues:
      - name: orders
        concurrency: 10
        activity-concurrency: 20
      - name: notifications
        concurrency: 5
        activity-concurrency: 15
```

---

## Timer Configuration

Properties under `maestro.timer.*` control the timer poller that fires scheduled
timers (created by `workflow.sleep()` and `workflow.awaitSignal()` with timeouts).

| Property                      | Type       | Default | Description                                                                          |
|-------------------------------|------------|---------|--------------------------------------------------------------------------------------|
| `maestro.timer.poll-interval` | `Duration` | `5s`    | How often the timer poller checks for due timers. Lower values reduce wake-up latency but increase database load. |
| `maestro.timer.batch-size`    | `int`      | `100`   | Maximum number of timers to process per polling cycle. Prevents a single poll from consuming too many resources.  |

Timer polling uses leader election via the distributed lock so that only one node
in the cluster polls at a time. The poll interval directly affects the maximum
delay between a timer becoming due and the workflow resuming.

---

## Recovery Configuration

Properties under `maestro.recovery.*` control the periodic recovery poller. On
top of the one-shot recovery at startup, every node re-runs recovery at this
interval so that workflows owned by a node that has since died (its instance
lock expired) or shut down are adopted without a restart. All nodes poll — the
per-instance distributed lock guarantees only one of them wins each workflow.

| Property                         | Type       | Default | Description                                                                 |
|----------------------------------|------------|---------|-----------------------------------------------------------------------------|
| `maestro.recovery.enabled`       | `boolean`  | `true`  | Whether the periodic recovery poller runs.                                   |
| `maestro.recovery.poll-interval` | `Duration` | `60s`   | Interval between recovery cycles. Together with the 30s instance-lock TTL, this bounds how long an orphaned workflow waits before another node adopts it. |

---

## Shutdown and Signal Configuration

| Property                              | Type       | Default | Description                                                                                                    |
|----------------------------------------|------------|---------|------------------------------------------------------------------------------------------------------------------|
| `maestro.shutdown.timeout`            | `Duration` | `30s`   | How long graceful shutdown waits for in-flight workflows to drain before forcing through. |
| `maestro.signal.wake-recheck-interval`| `Duration` | `30s`   | How often a parked workflow re-reads the store for a wake it may have missed — a signal ingested elsewhere, or a timer fired/cancelled by a remote timer-poller leader. |
| `maestro.signal.wake-notify.enabled`  | `boolean`  | `true`  | Whether the Postgres `LISTEN/NOTIFY` cross-node signal wake is active. Set `false` behind PgBouncer in transaction-pooling mode (`LISTEN` needs a session); wake then falls back to `wake-recheck-interval`. |
| `maestro.signal.janitor-interval`     | `Duration` | `1h`    | How often unconsumed signals addressed to finished workflows are purged. Every node runs the janitor (the purge is idempotent; no leader election). |
| `maestro.signal.terminal-retention`   | `Duration` | `7d`    | How long after a workflow reaches `COMPLETED`/`TERMINATED` its unconsumed signals are kept; `FAILED` workflows keep theirs because they can be retried. Orphaned (pre-delivery) and consumed signals are never purged by the janitor. |

The first two were previously hardcoded 30-second constants with no
configuration seam; they now bind under `maestro.*` like every other property,
with the same 30s defaults, so unset deployments are unaffected.

Since migration `V5`, a signal's `received_at` — the order in which same-name
signals are consumed — is assigned by the database clock (`DEFAULT now()`), not
by the JVM clock of the node that ingested it, so clock skew between nodes can
no longer reorder signals.

**Cross-node signal wake.** A signal delivered to a node other than the one
holding the parked workflow is woken instantly through Postgres `LISTEN/NOTIFY`:
`PostgresSignalNotifier` and `PostgresWakeListener`
(`io.b2mash.maestro.store.postgres.notify`, auto-configured by
`PostgresSignalWakeAutoConfiguration` whenever the engine is active) use **one
channel per service**, `maestro_signal_wake_<service-name>_<hash>` — the service
name sanitised and cut to 32 characters plus the first 8 hex digits of its
SHA-256, so the identifier always fits Postgres's 63-byte limit regardless of
how long or punctuated workflow IDs are. The payload carries the workflow ID and
signal name; the listener dispatches it in memory to the parked waiter. The
sender's `NOTIFY` runs on a virtual thread after the signal row has committed
and never blocks `deliverSignal`. The listener holds **one connection borrowed
from the application `DataSource` for the application's lifetime** — budget for
it (effective pool capacity is one less; a configured Hikari
`leakDetectionThreshold` logs one false positive for this connection) — and
reconnects with 1 s → 30 s backoff if it drops.

`maestro.signal.wake-recheck-interval` is a real operational knob, not just a
test seam: it is the correctness fallback for that wake. A notification lost
while the listener is reconnecting, or never sent because
`maestro.signal.wake-notify.enabled=false`, is covered by the next re-check
rather than retried — the sending side is best-effort and never awaited by
`signal()` (Issue 32). It also
bounds cross-node **timer** latency in every multi-instance deployment: the
timer poller runs on the elected leader only, so a timer fired (or cancelled)
by a node that does not own the parked `workflow.sleep()` thread is observed
via the sleeping node's periodic re-read of the durable timer row
(`docs/open-issues.md` Issue 17).

`maestro.lock.key-prefix` (see [Lock Configuration](#lock-configuration))
now also applies to the activity execution lock, not just the instance
lock — both honour the same configured prefix.

---

## Retry Configuration

Properties under `maestro.retry.*` define the default retry policy applied to
an `@ActivityStub` whose `retryPolicy` is left at the `@RetryPolicy`
annotation's own defaults (`maxAttempts = 3`, `initialInterval = "PT1S"`,
`maxInterval = "PT1M"`, `backoffMultiplier = 2.0`, no exception filters).

| Property                                  | Type       | Default | Description                                                                        |
|-------------------------------------------|------------|---------|------------------------------------------------------------------------------------|
| `maestro.retry.default-max-attempts`      | `int`      | `3`     | Maximum number of attempts (including the initial call). Set to `1` to disable retries. |
| `maestro.retry.default-initial-interval`  | `Duration` | `1s`    | Delay before the first retry attempt.                                              |
| `maestro.retry.default-max-interval`      | `Duration` | `60s`   | Upper bound on the backoff delay. The interval will never exceed this value.        |
| `maestro.retry.default-backoff-multiplier`| `double`   | `2.0`   | Multiplier applied to the interval after each failed attempt.                      |

These defaults apply globally, to every `@ActivityStub` that leaves
`retryPolicy` unset. An annotation cannot distinguish "left unset" from
"explicitly set to these same values" — annotation attributes always carry a
value — so the rule Maestro applies is: if **every** attribute of
`retryPolicy` equals the `@RetryPolicy` annotation's own default, the stub is
treated as unconfigured and gets the policy built from the properties above.
Customizing **any single attribute** (e.g. just `@ActivityStub(retryPolicy =
@RetryPolicy(maxAttempts = 5))`) opts the whole policy out of these
properties — the annotation's values are used as declared, and
`maestro.retry.*` no longer applies to that stub.

**How backoff works:**

With the defaults (`initialInterval=1s`, `multiplier=2.0`, `maxInterval=60s`),
the retry delays are:

| Attempt | Delay  |
|---------|--------|
| 1st     | 1s     |
| 2nd     | 2s     |
| 3rd     | 4s     |
| 4th     | 8s     |
| 5th     | 16s    |
| 6th     | 32s    |
| 7th+    | 60s    |

The delay doubles each time until it hits the maximum interval, then stays there.

---

## Admin Events Configuration

Properties under `maestro.admin.events.*` control lifecycle event publishing to
the Maestro admin dashboard.

| Property                      | Type      | Default                  | Description                                                                     |
|-------------------------------|-----------|--------------------------|---------------------------------------------------------------------------------|
| `maestro.admin.events.enabled`| `boolean` | `true`                   | Whether to publish workflow lifecycle events (started, completed, failed, etc.) — all event families (workflow, activity, signal, timer, and compensation). |
| `maestro.admin.events.topic`  | `String`  | `"maestro.admin.events"` | Kafka topic where lifecycle events are published.                               |

When enabled, Maestro publishes lifecycle events for every workflow state
transition. The admin dashboard (`maestro-admin` module) consumes these events to
provide real-time visibility into workflow execution across all services.

Set `enabled` to `false` if you are not running the admin dashboard and want to
eliminate the publishing overhead.

---

## Observability Configuration

Properties under `maestro.observability.*` control Micrometer meters and
OpenTelemetry tracing. Full reference: [`docs/observability.md`](observability.md).

| Property                                 | Type      | Default | Description                                                                                                    |
|------------------------------------------|-----------|---------|------------------------------------------------------------------------------------------------------------------|
| `maestro.observability.metrics.enabled`  | `boolean` | `true`  | Whether Maestro registers and emits Micrometer meters under `maestro.*`. Requires a `MeterRegistry` on the classpath **and** in the context; silently inert otherwise. |
| `maestro.observability.tracing.enabled`  | `boolean` | `true`  | Whether Maestro creates spans **and** propagates W3C trace context through Kafka headers. Requires a Micrometer `Tracer` and a `Propagator` in the context; silently inert otherwise. |

Both default to `true` and neither has to be set: an application with no
`MeterRegistry` and no `Tracer` gets no meters and no spans regardless. Setting
`maestro.observability.tracing.enabled: false` disables both the engine spans
and the Kafka header injection — the same property gates both — and the Kafka
wire format reverts to byte-identical to a pre-tracing build.

The whole block is additionally gated by `maestro.enabled`.

```yaml
maestro:
  observability:
    metrics:
      enabled: true
    tracing:
      enabled: true
```

Meters registered: counters `maestro.workflow.started|completed|failed|compensated|terminated`,
`maestro.signal.consumed`, `maestro.timer.fired`, `maestro.recovery.scanned`,
`maestro.recovery.adopted`, `maestro.lock.renew.failures`, `maestro.standdown`;
timer `maestro.activity.duration`; gauges `maestro.workflows.running` and
`maestro.workflows.parked` (node-local — sum across pods for a cluster total).
See [`docs/observability.md`](observability.md) for tags, tag values, span
topology, and the Kafka propagation header contract.

---

## Complete Example

A full `application.yml` for an order service:

```yaml
maestro:
  service-name: order-service
  store:
    type: postgres
  messaging:
    type: kafka
    topics:
      signals: maestro.signals.order-service
      admin-events: maestro.admin.events
  lock:
    type: postgres
    key-prefix: "maestro:lock:"
    ttl: 30s
    cleanup-interval: 10m
  signal:
    wake-notify:
      enabled: true
  worker:
    task-queues:
      - name: orders
        concurrency: 10
        activity-concurrency: 20
  timer:
    poll-interval: 5s
    batch-size: 100
  retry:
    default-max-attempts: 3
    default-initial-interval: 1s
    default-max-interval: 60s
    default-backoff-multiplier: 2.0
  admin:
    events:
      enabled: true
      topic: maestro.admin.events

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/maestro
    username: maestro
    password: maestro
  kafka:
    bootstrap-servers: localhost:29092
```

A minimal configuration (relying on defaults) looks like this:

```yaml
maestro:
  service-name: order-service
  worker:
    task-queues:
      - name: orders
```

Everything else uses the defaults documented above. You still need to configure
`spring.datasource` and `spring.kafka` for the underlying infrastructure
connections; the lock and the signal wake use the same `DataSource`.

---

## Kafka Topics

Maestro uses Kafka for cross-service signaling and lifecycle event
publishing. **Topics must be pre-created** -- Maestro never
auto-creates topics.

### Topic Naming Conventions

| Topic Pattern                       | Purpose                                           |
|-------------------------------------|---------------------------------------------------|
| `maestro.signals.{serviceName}`     | Inbound signals for a specific service             |
| `maestro.admin.events`              | Lifecycle events consumed by the admin dashboard   |

### Consumer Groups

Consumer groups default to `maestro-{serviceName}` unless explicitly overridden
via `maestro.messaging.consumer-group`. This means each service automatically
gets its own consumer group, ensuring that every service instance in a cluster
receives its share of messages.

### Kafka Client Configuration

Maestro's engine producer and consumer (`maestroKafkaProducerFactory` /
`maestroKafkaConsumerFactory`) are built from Spring Boot's bound
`spring.kafka.*` properties (`KafkaProperties`), the same properties any other
Spring Kafka client in the service honours — `spring.kafka.bootstrap-servers`,
`spring.kafka.producer.*` (compression, batching, retries, arbitrary
`spring.kafka.producer.properties.*` entries), `spring.kafka.consumer.*`, and
SSL/security settings. A `KafkaConnectionDetails` bean (e.g. from a
service-connection Testcontainers setup) overrides the bootstrap servers when
present.

A small set of wire-format invariants the engine's own protocol depends on are
forced **last**, after `spring.kafka.*` is applied, so no user property can
silently corrupt engine topics:

| Invariant                          | Forced value                    |
|-------------------------------------|----------------------------------|
| Producer/consumer key (de)serializer | `StringSerializer`/`StringDeserializer` |
| Producer/consumer value (de)serializer | `ByteArraySerializer`/`ByteArrayDeserializer` |
| Producer `acks`                     | `all`                            |
| Consumer `group.id`                 | `maestro-{serviceName}` (or `maestro.messaging.consumer-group`) |

`spring.kafka.consumer.auto-offset-reset` is **not** an invariant — Maestro
only supplies a default of `earliest` when the property is unset; an explicit
value wins.

Boot's own `kafkaTemplate` / `kafkaProducerFactory` / `kafkaConsumerFactory`
beans are **deliberately suppressed**: `KafkaMessagingAutoConfiguration`
registers before `KafkaAutoConfiguration` and satisfies
`ConditionalOnMissingBean(KafkaTemplate.class)`, so Boot's typed,
`Object`-valued beans never get created. This is intentional, not a bug —
Maestro needs exactly one `String`/`byte[]`-typed producer/consumer pair for
its own topics, and letting Boot's beans coexist would leave two
`KafkaTemplate`s of overlapping type in the context.

A service that also needs Kafka for its own application traffic has two
options:

- **Different value type (the common case).** Define your own `KafkaTemplate`
  bean under a bean name other than `maestroKafkaTemplate` — e.g. one typed
  `KafkaTemplate<String, YourDto>` with its own `ProducerFactory`. It still
  reads `spring.kafka.producer.*` for its own settings; only the engine's
  forced invariants above are specific to `maestroKafkaTemplate`.
- **Same `byte[]` traffic.** Inject and reuse `maestroKafkaTemplate` /
  `maestroKafkaProducerFactory` directly rather than standing up a second
  client.

**Plain `@KafkaListener` consumers.** The suppression above only removes
Boot's *typed* `String`/`byte[]` beans — it does not touch Boot's own
`kafkaListenerContainerFactory`, which stays name-conditioned and is never
suppressed. A plain `@KafkaListener` method with no explicit
`containerFactory` rides that factory, built from `spring.kafka.consumer.*`
the ordinary Boot way. Maestro's engine-forced invariants (key/value
(de)serializer, `group.id`) apply **only** to `maestroKafkaConsumerFactory`
and never reach this factory — so a plain `@KafkaListener` must declare its
own `spring.kafka.consumer.key-deserializer` /
`spring.kafka.consumer.value-deserializer` explicitly, exactly as it would in
a Maestro-free Boot application. Injecting `ConsumerFactory` by type resolves
Maestro's `byte[]` factory instead; inject by bean name
(`maestroKafkaConsumerFactory`) or define your own if that is not what you
want. This is the rule the `sample-payment-gateway`, `underwriting-service`,
and `verification-gateway-service` samples follow — each keeps its plain
`@KafkaListener`'s `spring.kafka.consumer.*-deserializer` properties in its
`application.yml`, with a comment explaining why removing them (on the
mistaken belief that Maestro's invariants cover them) breaks that listener.

**Two `spring.kafka.template.*` properties that read but do nothing for
Maestro's own template.** `spring.kafka.template.default-topic` binds and
appears in `/actuator/configprops`, but `maestroKafkaTemplate`'s send calls
always name an explicit topic, so it is never consulted. Kafka producer
transactions (`spring.kafka.producer.transaction-id-prefix` and friends) are
also quietly unsupported — Boot's `kafkaTransactionManager` never exists in a
Maestro context, since `maestroKafkaProducerFactory` is not
transaction-capable. Both are ordinary, honoured settings for a *second*,
user-defined `KafkaTemplate` (the "different value type" option above); only
`maestroKafkaTemplate` ignores them.

Observation (Micrometer spans on send/receive) on `maestroKafkaTemplate` and
the `@MaestroSignalListener` consumer containers defaults **on** when
Micrometer tracing is active — a `Tracer` *and* a `Propagator` bean both exist
and `maestro.observability.tracing.enabled` is not `false`, exactly the
condition that activates Maestro's own `KafkaTracePropagation` bean — and
**off** otherwise. Note that Spring Boot registers a no-op `Tracer` by
default, so a `Tracer` bean being present is not by itself the gate; an
explicit `spring.kafka.template.observation-enabled` /
`.listener.observation-enabled` value always wins over that default. See
[`docs/observability.md` § Cross-service trace propagation (Kafka)](observability.md#cross-service-trace-propagation-kafka)
for the full contract.

### Pre-creating Topics

Create topics before starting your service. Example using the Kafka CLI:

```bash
kafka-topics.sh --create --topic maestro.signals.order-service \
  --partitions 6 --replication-factor 3 \
  --bootstrap-server localhost:9092

kafka-topics.sh --create --topic maestro.admin.events \
  --partitions 3 --replication-factor 3 \
  --bootstrap-server localhost:9092
```

---

## Lock Keys

Maestro uses PostgreSQL tables for distributed locking, activity deduplication
and leader election. The keys below are the `lock_key` values stored in
`maestro_distributed_lock` and `maestro_leader_election`:

| Key Pattern                                   | Table                       | Purpose                         | TTL               |
|-----------------------------------------------|-----------------------------|-------------------------------- |--------------------|
| `maestro:lock:workflow:{workflowId}`          | `maestro_distributed_lock`  | Workflow instance lock          | 30s (renewed every 10s) |
| `maestro:lock:activity:{workflowId}:{seq}`    | `maestro_distributed_lock`  | Activity execution lock (fast-path dedup) | activity timeout + 10s |
| `maestro:leader:timer-poller:{service}`       | `maestro_leader_election`   | Timer poller leader election    | 15s                |

The `maestro:lock:` prefix is configurable via `maestro.lock.key-prefix`. If you
change it, the lock keys will use your custom prefix instead. Cross-node signal
wake is not a key: it is a Postgres `LISTEN/NOTIFY` channel, one per service
(see [Shutdown and Signal Configuration](#shutdown-and-signal-configuration)).

The instance lock is held for the duration of a workflow's local lifetime —
including parked waits — and renewed every 10 seconds. If a node crashes, the
lock expires after 30 seconds, allowing another node's recovery poller to pick
up the workflow.

The activity lock is a best-effort guard against concurrent duplicate execution
and doubles as the fast-path dedup key. The authoritative dedup is the Postgres
unique index on `(workflow_instance_id, sequence_number)` — but note that this
deduplicates *persisted results*, not external side effects. If a lock expires
or is lost mid-execution, an activity can run more than once, so activities
must be idempotent (or use fencing/idempotency keys with external systems).

The leader election key ensures that only one node in the cluster runs the timer
poller. It uses a shorter TTL (15 seconds) so that leadership transfers quickly
if the current leader goes down.

---

## See Also

- [Getting Started](getting-started.md) -- Set up Maestro in a new Spring Boot project
- [Concepts](concepts.md) -- Workflows, activities, signals, timers, and the memoization model
- [Cross-Service Patterns](cross-service.md) -- Orchestration within, choreography between services
- [Observability](observability.md) -- The meter catalog, span topology, and the Kafka trace-propagation contract
- [Operations](operations.md) -- Multi-instance behaviour, and the versioning / mixed-version playbook
