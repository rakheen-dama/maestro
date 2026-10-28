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
`maestro-messaging-postgres`, `maestro-lock-valkey`, `maestro-lock-postgres`,
`maestro-store-postgres`, `maestro-admin-client`, and the health/observability
auto-configurations all carry the same `@ConditionalOnProperty(maestro.enabled)`
gate as the core `MaestroAutoConfiguration`, so flipping this one flag backs
every module off cleanly rather than leaving, say, a live Valkey connection
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

Each Maestro Postgres module ships its SQL under its own classpath location, in its own version band, and advertises that location in a `META-INF/maestro/schema-locations` resource; the initializer applies every location it finds on the classpath:

| Module                       | Location                         | Versions  |
|------------------------------|----------------------------------|-----------|
| `maestro-store-postgres`     | `classpath:db/maestro/store`     | 1–99      |
| `maestro-lock-postgres`      | `classpath:db/maestro/lock`      | 100–199   |
| `maestro-messaging-postgres` | `classpath:db/maestro/messaging` | 200–299   |

The initializer runs after your application's own database initializers (its Flyway, Liquibase or `spring.sql.init` scripts) and before the store and engine beans are created. When it first meets a schema that already holds your tables, it writes a baseline marker row at version `0` into `maestro_schema_history` and then applies every Maestro migration — your tables are not touched. Several nodes starting at once are safe: Flyway serialises concurrent runs with a database lock. (If you add Flyway to your own application *later*, against a database that already holds Maestro's tables, your Flyway needs `spring.flyway.baseline-on-migrate: true` for its first run, as for any non-empty schema.)

The ordering covers only database initializers that Spring Boot knows about (`spring.flyway.*`, `spring.liquibase.*`, `spring.sql.init.*`). If your application runs Flyway by hand — its own `Flyway.configure()...migrate()` in a bean or a runner — it is not ordered before Maestro's initializer: either migrate through `spring.flyway.*`, or set `maestro.store.schema.mode: none` and call `new MaestroSchemaInitializer(dataSource, SchemaMode.FLYWAY, classLoader).initialize()` yourself after your own migration.

The initializer belongs to `maestro-store-postgres` and runs only when `maestro.store.type` is `postgres` (the only supported store). The Postgres lock and messaging tables are created by that same initializer, so `maestro-lock-postgres` or `maestro-messaging-postgres` used under any other `maestro.store.type` gets no table creation.

**`validate` and `none`.** With `maestro.store.schema.mode: validate` Maestro applies nothing and fails startup — naming the pending versions — if any migration is pending, or if an applied one no longer matches the shipped SQL; it reads `maestro_schema_history`, so whoever applies the SQL out of band must record it there (e.g. the Flyway CLI with `-table=maestro_schema_history`, the locations above, and — on a schema that already holds your tables — `-baselineOnMigrate=true -baselineVersion=0`, exactly as Maestro's own run does). With `none` Maestro does nothing and reads no history: you apply the SQL under the locations above yourself, in version order, before deploying the build that ships it. A build at or past V5 omits `received_at` from signal inserts, so a self-managed database must have V5 applied (`ALTER TABLE maestro_workflow_signal ALTER COLUMN received_at SET DEFAULT now()`) before that build is deployed, or every signal insert fails.

**Upgrading an existing database.** A database migrated by an earlier Maestro build, before Maestro owned its history (0.4.0 applied `V1`–`V3`, `V100`, `V200` and `V201`), has Maestro's rows in your `flyway_schema_history` and no `maestro_schema_history`, and the new build's first start fails either way:

- if your application still runs its own Flyway with migrations of its own, *your* Flyway fails first — `Detected applied migration not resolved locally` — because Maestro's rows remain in `flyway_schema_history` while their scripts are no longer in `classpath:db/migration` (Flyway's default `ignore-migration-patterns` is `*:future` only);
- otherwise — no Flyway, or a Flyway that resolves no migrations and so validates nothing — Maestro's `V1` fails because its tables already exist.

Upgrade such a database once, with this procedure, in order (the same text is in `docs/release-notes.md` → "Maestro owns its schema migrations"):

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

---

## Messaging Configuration

Properties under `maestro.messaging.*` control signal delivery and lifecycle
event publishing. There is no task-dispatch channel: a workflow runs in-process
on the node that started it.

### Core Properties

| Property                        | Type     | Default    | Description                                                                                     |
|---------------------------------|----------|------------|-------------------------------------------------------------------------------------------------|
| `maestro.messaging.type`        | `String` | `"kafka"`  | Messaging implementation. Supported values: `kafka` (default), `postgres`.          |
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
channel it means the signal is not yet in Postgres — so no transport
acknowledges it. Every transport redelivers with exponential backoff and, once
the attempt budget is spent, routes the message to a durable, inspectable
dead-letter destination rather than dropping it or looping on it forever.

| Property                                          | Type       | Default                 | Description                                                                 |
|---------------------------------------------------|------------|-------------------------|-----------------------------------------------------------------------------|
| `maestro.messaging.redelivery.enabled`            | `boolean`  | `true`                  | Whether handler-failure redelivery and dead-lettering are active at all. Both transports. |
| `maestro.messaging.redelivery.max-attempts`       | `int`      | `10`                    | Total delivery attempts, including the first. Both transports.               |
| `maestro.messaging.redelivery.initial-interval`   | `Duration` | `1s`                    | Backoff before the second attempt. Both transports.                          |
| `maestro.messaging.redelivery.multiplier`         | `double`   | `2.0`                   | Factor applied to the backoff after each failure. Both transports.           |
| `maestro.messaging.redelivery.max-interval`       | `Duration` | `30s`                   | Ceiling for the computed backoff. Both transports.                           |
| `maestro.messaging.redelivery.dead-letter-suffix` | `String`   | `".DLT"`                | Appended to a topic to name its dead-letter topic. **Kafka only.**          |

**Disabling redelivery.** Setting `maestro.messaging.redelivery.enabled=false`
is the operator's explicit opt-out of everything below, on both transports —
it is not a recommended default, and defaults to `true`:

- **Kafka:** the listener container gets a `DefaultErrorHandler` backed by a
  zero-length `FixedBackOff` instead of `KafkaRedeliveryErrorHandlers.deadLettering(...)`.
  A failing record gets **zero retries** and **no `DeadLetterPublishingRecoverer`** —
  it is logged and skipped, restoring plain at-most-once handler semantics. The
  [dead-letter-topic startup probe](#kafka-dead-letter-topic-check) below is
  skipped entirely, since nothing will ever publish to a `.DLT` topic.
- **Postgres:** a failing row is marked `FAILED` after exactly **one** attempt
  — no backoff, no `DEAD_LETTER` parking of *handler failures*. (Rows retired as
  "outcome unknown" still go to `DEAD_LETTER` in both modes — see the
  retirement note below.) This is the pre-redelivery behaviour
  (see the `FAILED` rescue statement below); `max-attempts` and the backoff
  properties are ignored while the flag is off.

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
This section covers Kafka and Postgres — the only two transports Maestro
ships today.

**Tuning.** Raise `max-attempts` if your store outages routinely run longer
than the budget (an outage longer than the budget dead-letters signals, which
then need an operator replay — they are parked, never lost). Lower it to
contain a poison message sooner. There is no unbounded mode: a poison message
would stall redelivery forever behind it — on Kafka this stalls the whole
*topic* on that node (the default listener concurrency is one consumer
thread per topic, which owns every partition assigned to it, not just the
failed record's partition); on Postgres it stalls that queue.

**Fatal exceptions bypass retries.** On Kafka, `DefaultErrorHandler` treats a
handful of exception types as unrecoverable regardless of the configured
budget — deserialization failures, `ClassCastException`, and other framework
conversion errors — and sends the record straight to the dead-letter topic on
the first attempt rather than retrying it. This is spring-kafka's own default
classification, not something Maestro configures; the `max-attempts` budget
above only governs exceptions it doesn't consider fatal.

**Where exhausted messages go:**

| Transport | Destination | Created by |
|---|---|---|
| Kafka | `<topic>` + `dead-letter-suffix`, e.g. `maestro.signals.order-service.DLT` | **The operator** — Maestro never creates topics |
| Postgres | The same queue row, in `DEAD_LETTER` status | Nothing to create |

If a Kafka dead-letter topic is missing, the publish fails, the offset is not
committed and the record is attempted again: consumption stalls noisily instead
of losing the message.

**Inspecting and replaying.** On Kafka a dead-letter topic is an ordinary
topic — read it with any consumer (`kafka-console-consumer --topic
maestro.signals.order-service.DLT --from-beginning --property
print.headers=true`; the `kafka_dlt-*` headers carry the original topic,
partition, offset and exception) and replay by producing the record back to the
source topic with the same key. On Postgres use
`PostgresWorkflowMessaging.listDeadLetterSignals` and `replaySignal(id)`, or
plain SQL:

```sql
SELECT id, workflow_id, signal_name, attempts, last_error, created_at
  FROM maestro_signal_queue
 WHERE service_name = 'order-service' AND status = 'DEAD_LETTER'
 ORDER BY created_at;

UPDATE maestro_signal_queue
   SET status = 'PENDING', attempts = 0, next_attempt_at = now(), last_error = NULL
 WHERE id = '...' AND status = 'DEAD_LETTER';
```

`DEAD_LETTER` rows are never removed by `PostgresMessageCleaner` — they are the
inspectable destination. Rows stranded as `FAILED` — either by versions before
redelivery existed, or by `maestro.messaging.redelivery.enabled=false` since
(see above) — can be rescued once, deliberately:

```sql
UPDATE maestro_signal_queue SET status = 'PENDING', next_attempt_at = now() WHERE status = 'FAILED';
```

**Retired rows (Postgres).** The Postgres transport counts an attempt when it
*claims* a row, not when the handler finishes — so a consumer that crashes
mid-handler, or whose final status write is lost because the database went
down right after the handler returned, has consumed that attempt. A
`PROCESSING` claim older than five minutes is re-claimed and its handler
re-invoked only while attempt budget remains (the accepted double delivery
under a double fault — handlers must be idempotent). Once the budget is spent
(`attempts >= max-attempts`; with `maestro.messaging.redelivery.enabled=false`,
after its single attempt) the row is instead **retired** to `DEAD_LETTER` —
in *both* redelivery modes — with
`last_error = 'finalisation write lost after attempt <n>; outcome unknown —
retired without re-invoking the handler'`, logged at `ERROR`. *Outcome
unknown* means exactly that: the handler may or may not have run to
completion, and Maestro will not guess by invoking it again. A retired row
always needs triage, so with redelivery disabled it still goes to
`DEAD_LETTER`, not `FAILED`: it shows up in `listDeadLetterSignals`,
`PostgresMessageCleaner` never purges it, and the
blanket `FAILED` rescue statement above does not touch it (that rescue applies
only to handler failures recorded as `FAILED`, never to retired rows). Check
the downstream effect, then replay it deliberately with `replaySignal`. A
consequence worth knowing: a poison message that crashes the
JVM dead-letters after `max-attempts` crashes instead of looping forever.

---

### Postgres Messaging

When `maestro.messaging.type: postgres`, Maestro uses PostgreSQL queue tables with `LISTEN/NOTIFY` for immediate notification and polling as a fallback. No Kafka infrastructure is needed.

The Postgres messaging module shares the same `DataSource` as the workflow store. Maestro's schema initializer creates the queue table (`maestro_signal_queue`) from `classpath:db/maestro/messaging` (see [Schema management](#schema-management)); migration `V202` drops the former `maestro_task_queue` and `maestro_lifecycle_event_queue` tables.

Lifecycle events are **not** carried by the Postgres transport — the admin dashboard consumes Kafka only. `publishLifecycleEvent` is a no-op that logs one WARN; set `maestro.admin.events.enabled: false` in Postgres-only services to silence it.

**Dependencies:**
```kotlin
implementation("io.b2mash.maestro:maestro-messaging-postgres")
```

---

## Lock Configuration

Properties under `maestro.lock.*` configure the distributed locking layer used
for workflow instance locks, activity deduplication, and timer leader election.

| Property                  | Type       | Default          | Description                                                                                  |
|---------------------------|------------|------------------|----------------------------------------------------------------------------------------------|
| `maestro.lock.type`       | `String`   | `"valkey"`       | Lock implementation. Supported values: `valkey` (default), `postgres`.                       |
| `maestro.lock.key-prefix` | `String`   | `"maestro:lock:"`| Prefix for all lock keys in Valkey/Redis. Change this to isolate multiple environments.      |
| `maestro.lock.ttl`        | `Duration` | `30s`            | Lock time-to-live. Locks are automatically renewed while the workflow is executing. If a node crashes, locks expire after this duration, enabling recovery by another node. |

The TTL value represents a trade-off: shorter values enable faster recovery after
a crash, but require more frequent renewal. The default of 30 seconds is suitable
for most workloads.

### Valkey Connection Resolution

When `maestro.lock.type: valkey` (the default), `ValkeyLockAutoConfiguration`
resolves the Redis/Valkey connection URI by checking the following properties
in order, using the first one that is set:

| Order | Property                        | Type      | Description                                                                                     |
|-------|----------------------------------|-----------|---------------------------------------------------------------------------------------------------|
| 1     | `spring.data.redis.url`          | `String`  | Standard Spring Data Redis connection URI (e.g. `redis://user:pass@host:6379/1`). Takes priority over everything below. |
| 2     | `maestro.lock.valkey.uri`        | `String`  | Maestro-specific override, for when you don't want to set the standard Spring property. |
| 3     | `spring.data.redis.host`         | `String`  | If set (and neither property above is), a URI is built from the standard Spring Data Redis host/port/credential properties: `spring.data.redis.port` (default `6379`), `spring.data.redis.password`, `spring.data.redis.username` (only applied if a password is also set), `spring.data.redis.ssl.enabled` (default `false`), and `spring.data.redis.database` (default `0`). |
| 4     | *(none set)*                     | —         | Falls back to the default `redis://localhost:6379`.                                              |

Only one source is used — properties from lower-priority steps are ignored
once a higher-priority one is set. For example, if `spring.data.redis.url` is
set, `spring.data.redis.host`/`port`/`password` are not consulted at all.

---

### Postgres Locking

When `maestro.lock.type: postgres`, Maestro uses PostgreSQL tables for distributed locking and leader election. Locks use `INSERT ... ON CONFLICT` with token-based ownership and TTL-based expiry.

The Postgres lock module shares the same `DataSource` as the workflow store. Its lock tables (`maestro_distributed_lock`, `maestro_leader_election`) are created by Maestro's schema initializer from `classpath:db/maestro/lock` (see [Schema management](#schema-management)).

**Dependencies:**
```kotlin
implementation("io.b2mash.maestro:maestro-lock-postgres")
```

### Backend Comparison

| | Kafka + Valkey | Postgres-only |
|---|---|---|
| **External deps** | Postgres, Kafka, Valkey | Postgres only |
| **Throughput** | Highest | Moderate (~5-10k msg/s) |
| **Latency** | Sub-ms locks | 1-5ms per lock/message |
| **Ordering** | Partition-keyed | FOR UPDATE SKIP LOCKED |
| **Best for** | High-scale production | Getting started, simple deployments |

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
| `maestro.signal.janitor-interval`     | `Duration` | `1h`    | How often unconsumed signals addressed to finished workflows are purged. Every node runs the janitor (the purge is idempotent; no leader election). |
| `maestro.signal.terminal-retention`   | `Duration` | `7d`    | How long after a workflow reaches `COMPLETED`/`TERMINATED` its unconsumed signals are kept; `FAILED` workflows keep theirs because they can be retried. Orphaned (pre-delivery) and consumed signals are never purged by the janitor. |

The first two were previously hardcoded 30-second constants with no
configuration seam; they now bind under `maestro.*` like every other property,
with the same 30s defaults, so unset deployments are unaffected.

Since migration `V5`, a signal's `received_at` — the order in which same-name
signals are consumed — is assigned by the database clock (`DEFAULT now()`), not
by the JVM clock of the node that ingested it, so clock skew between nodes can
no longer reorder signals.

`maestro.signal.wake-recheck-interval` is a real operational knob, not just a
test seam: it bounds cross-node signal latency for any deployment running
Kafka without a `SignalNotifier` (i.e. without Valkey's pub/sub wake) — a
signal delivered to a node other than the one holding the parked workflow is
picked up on the next re-check, not instantly, in that configuration. It also
bounds cross-node **timer** latency in every multi-instance deployment: the
timer poller runs on the elected leader only, so a timer fired (or cancelled)
by a node that does not own the parked `workflow.sleep()` thread is observed
via the sleeping node's periodic re-read of the durable timer row
(`docs/open-issues.md` Issue 17).
With Valkey, the wake subscription is acknowledged (bounded 5 s) by the
server before the workflow parks, so the recheck interval only bounds latency
across a pub/sub disconnect (Lettuce re-subscribes on reconnect but does not
replay) — and across a Valkey brownout: if the acknowledgement does not arrive
within 5 s the workflow parks anyway (a `WARN` names this fallback) and the
recheck interval bounds its wake latency, rather than every `awaitSignal`
stalling for Lettuce's 60 s command timeout.
The sending side is bounded the same way: the cross-node notification is
best-effort and never awaited by `signal()` (Issue 32) — a notification lost
to a brownout is covered by this recheck, not retried.

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
    type: valkey
    key-prefix: "maestro:lock:"
    ttl: 30s
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
  data:
    redis:
      host: localhost
      port: 6379
```

#### Postgres-Only Example

```yaml
maestro:
  service-name: my-service
  store:
    type: postgres
  messaging:
    type: postgres
  lock:
    type: postgres
  worker:
    task-queues:
      - name: my-tasks
        concurrency: 5
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
`spring.datasource`, `spring.kafka`, and `spring.data.redis` for the underlying
infrastructure connections.

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

## Valkey Keys

Maestro uses Valkey (or Redis) for distributed locking, activity deduplication,
leader election, and signal notification. The following key patterns are used:

| Key Pattern                                   | Purpose                         | TTL               |
|-----------------------------------------------|-------------------------------- |--------------------|
| `maestro:lock:workflow:{workflowId}`          | Workflow instance lock          | 30s (renewed every 10s) |
| `maestro:lock:activity:{workflowId}:{seq}`    | Activity execution lock (fast-path dedup) | activity timeout + 10s |
| `maestro:leader:timer-poller:{service}`       | Timer poller leader election    | 15s                |
| `maestro:signal:{workflowId}`                 | Signal notification (pub/sub)   | N/A (pub/sub)      |

The `maestro:lock:` prefix is configurable via `maestro.lock.key-prefix`. If you
change it, the lock keys will use your custom prefix instead.

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
