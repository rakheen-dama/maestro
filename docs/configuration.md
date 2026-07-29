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

| Property              | Type      | Default | Description                                                                                                  |
|-----------------------|-----------|---------|--------------------------------------------------------------------------------------------------------------|
| `maestro.enabled`     | `boolean` | `true`  | Master switch for Maestro auto-configuration. Set to `false` to disable the engine entirely.                 |
| `maestro.service-name`| `String`  | --      | **Required.** Logical name of the owning service. Used for Kafka consumer groups, lock key prefixes, and lifecycle event attribution. Auto-configuration will fail if not set. |

---

## Store Configuration

Properties under `maestro.store.*` control the workflow persistence layer.

| Property                   | Type     | Default     | Description                                                                                              |
|----------------------------|----------|-------------|----------------------------------------------------------------------------------------------------------|
| `maestro.store.type`       | `String` | `"postgres"`| Store implementation type. Currently only `postgres` is supported.                                       |
| `maestro.store.table-prefix`| `String`| `"maestro_"`| Prefix applied to all database table names (e.g., `maestro_workflow_instance`, `maestro_workflow_event`). |

The table prefix lets you run multiple Maestro instances in the same database by
giving each a unique prefix. To target a non-default PostgreSQL schema, set it
on the JDBC connection (e.g. `currentSchema` in the datasource URL) — Maestro
does not manage schemas itself.

---

## Messaging Configuration

Properties under `maestro.messaging.*` control task dispatch, signal delivery,
and lifecycle event publishing.

### Core Properties

| Property                        | Type     | Default    | Description                                                                                     |
|---------------------------------|----------|------------|-------------------------------------------------------------------------------------------------|
| `maestro.messaging.type`        | `String` | `"kafka"`  | Messaging implementation. Supported values: `kafka` (default), `postgres`, `rabbitmq`.          |
| `maestro.messaging.consumer-group`| `String`| `null`   | Kafka consumer group ID. If not set, defaults to `maestro-{serviceName}` at runtime.            |

### Topic Properties

| Property                                 | Type     | Default                  | Description                                                       |
|------------------------------------------|----------|--------------------------|-------------------------------------------------------------------|
| `maestro.messaging.topics.tasks`         | `String` | `null`                   | Topic for internal workflow task dispatch. Resolved at runtime based on the task queue name.    |
| `maestro.messaging.topics.signals`       | `String` | `null`                   | Topic for inbound cross-service signals. Resolved at runtime based on the service name.        |
| `maestro.messaging.topics.admin-events`  | `String` | `"maestro.admin.events"` | Topic for lifecycle events consumed by the admin dashboard.       |

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
| `maestro.messaging.redelivery.max-attempts`       | `int`      | `10`                    | Total delivery attempts, including the first. All transports.               |
| `maestro.messaging.redelivery.initial-interval`   | `Duration` | `1s`                    | Backoff before the second attempt. All transports.                          |
| `maestro.messaging.redelivery.multiplier`         | `double`   | `2.0`                   | Factor applied to the backoff after each failure. All transports.           |
| `maestro.messaging.redelivery.max-interval`       | `Duration` | `30s`                   | Ceiling for the computed backoff. All transports.                           |
| `maestro.messaging.redelivery.dead-letter-suffix` | `String`   | `".DLT"`                | Appended to a topic to name its dead-letter topic. **Kafka only.**          |
| `maestro.messaging.redelivery.dead-letter-exchange`| `String`  | `"maestro.dead-letter"` | Exchange exhausted messages are republished to. **RabbitMQ only.**          |

The delay before the attempt following the *n*-th failure is
`min(initial-interval × multiplier^(n-1), max-interval)`. The defaults give
1s, 2s, 4s, 8s, 16s, 30s, 30s, 30s, 30s between 10 attempts — roughly 2.5
minutes of tolerance, long enough to ride out a store blip and short enough
that a poison message does not stall a service's signal channel for long.

**Tuning.** Raise `max-attempts` if your store outages routinely run longer
than the budget (an outage longer than the budget dead-letters signals, which
then need an operator replay — they are parked, never lost). Lower it to
contain a poison message sooner. There is no unbounded mode: a poison message
would stall redelivery forever behind it — on Kafka this stalls the whole
*topic* on that node (the default listener concurrency is one consumer
thread per topic, which owns every partition assigned to it, not just the
failed record's partition); on Postgres/RabbitMQ it stalls that queue.

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
| RabbitMQ | `<queue>.dlq`, bound to `dead-letter-exchange` | The module declares it idempotently, like its other topology |

If a Kafka dead-letter topic is missing, the publish fails, the offset is not
committed and the record is attempted again: consumption stalls noisily instead
of losing the message.

**Inspecting and replaying.** On Kafka a dead-letter topic is an ordinary
topic — read it with any consumer (`kafka-console-consumer --topic
maestro.signals.order-service.DLT --from-beginning --property
print.headers=true`; the `kafka_dlt-*` headers carry the original topic,
partition, offset and exception) and replay by producing the record back to the
source topic with the same key. On Postgres use
`PostgresWorkflowMessaging.listDeadLetterSignals` / `listDeadLetterTasks` and
`replaySignal(id)` / `replayTask(id)`, or plain SQL:

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
inspectable destination. Rows stranded as `FAILED` by versions before
redelivery existed can be rescued once, deliberately:

```sql
UPDATE maestro_signal_queue SET status = 'PENDING', next_attempt_at = now() WHERE status = 'FAILED';
UPDATE maestro_task_queue   SET status = 'PENDING', next_attempt_at = now() WHERE status = 'FAILED';
```

---

### Postgres Messaging

When `maestro.messaging.type: postgres`, Maestro uses PostgreSQL queue tables with `LISTEN/NOTIFY` for immediate notification and polling as a fallback. No Kafka or RabbitMQ infrastructure is needed.

The Postgres messaging module shares the same `DataSource` as the workflow store. Additional Flyway migrations create the queue tables (`maestro_task_queue`, `maestro_signal_queue`, `maestro_lifecycle_event_queue`).

**Dependencies:**
```kotlin
implementation("io.b2mash.maestro:maestro-messaging-postgres")
```

### RabbitMQ Messaging

When `maestro.messaging.type: rabbitmq`, Maestro uses Spring AMQP with direct exchanges for task dispatch and signal delivery, and a fanout exchange for lifecycle events. All queues are quorum queues for durability.

**Required Spring properties:**
```yaml
spring:
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USER:guest}
    password: ${RABBITMQ_PASSWORD:guest}
```

**Dependencies:**
```kotlin
implementation("io.b2mash.maestro:maestro-messaging-rabbitmq")
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

---

### Postgres Locking

When `maestro.lock.type: postgres`, Maestro uses PostgreSQL tables for distributed locking and leader election. Locks use `INSERT ... ON CONFLICT` with token-based ownership and TTL-based expiry.

The Postgres lock module shares the same `DataSource` as the workflow store. Flyway migrations create the lock tables (`maestro_distributed_lock`, `maestro_leader_election`).

**Dependencies:**
```kotlin
implementation("io.b2mash.maestro:maestro-lock-postgres")
```

### Backend Comparison

| | Kafka + Valkey | Postgres-only | RabbitMQ + Postgres |
|---|---|---|---|
| **External deps** | Postgres, Kafka, Valkey | Postgres only | Postgres, RabbitMQ |
| **Throughput** | Highest | Moderate (~5-10k msg/s) | High |
| **Latency** | Sub-ms locks | 1-5ms per lock/message | Low |
| **Ordering** | Partition-keyed | FOR UPDATE SKIP LOCKED | Engine-level dedup |
| **Best for** | High-scale production | Getting started, simple deployments | Spring/enterprise teams |

---

## Worker Configuration

Properties under `maestro.worker.*` configure the task queue workers that execute
workflows and activities.

| Property                                            | Type                       | Default | Description                                                          |
|-----------------------------------------------------|----------------------------|---------|----------------------------------------------------------------------|
| `maestro.worker.task-queues`                        | `List<TaskQueueProperties>`| `[]`    | List of task queues this service listens on, with per-queue concurrency settings. |
| `maestro.worker.task-queues[].name`                 | `String`                   | --      | **Required.** Name of the task queue.                                |
| `maestro.worker.task-queues[].concurrency`          | `int`                      | `10`    | Maximum number of concurrent workflow executions for this queue.     |
| `maestro.worker.task-queues[].activity-concurrency` | `int`                      | `20`    | Maximum number of concurrent activity executions for this queue.     |

Each task queue entry defines a named queue with independent concurrency limits.
Workflow concurrency controls how many workflow methods can run in parallel, while
activity concurrency controls how many activity invocations can execute
simultaneously.

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
| `maestro.signal.wake-recheck-interval`| `Duration` | `30s`   | How often a parked workflow re-reads the store for a signal it may have missed. |

Both were previously hardcoded 30-second constants with no configuration
seam; they now bind under `maestro.*` like every other property, with the
same 30s defaults, so unset deployments are unaffected.

`maestro.signal.wake-recheck-interval` is a real operational knob, not just a
test seam: it bounds cross-node signal latency for any deployment running
Kafka without a `SignalNotifier` (i.e. without Valkey's pub/sub wake) — a
signal delivered to a node other than the one holding the parked workflow is
picked up on the next re-check, not instantly, in that configuration.

`maestro.lock.key-prefix` (see [Lock Configuration](#lock-configuration))
now also applies to the activity execution lock, not just the instance
lock — both honour the same configured prefix.

---

## Retry Configuration

Properties under `maestro.retry.*` define the default retry policy applied to
activities that do not specify their own `@RetryPolicy` annotation.

| Property                                  | Type       | Default | Description                                                                        |
|-------------------------------------------|------------|---------|------------------------------------------------------------------------------------|
| `maestro.retry.default-max-attempts`      | `int`      | `3`     | Maximum number of attempts (including the initial call). Set to `1` to disable retries. |
| `maestro.retry.default-initial-interval`  | `Duration` | `1s`    | Delay before the first retry attempt.                                              |
| `maestro.retry.default-max-interval`      | `Duration` | `60s`   | Upper bound on the backoff delay. The interval will never exceed this value.        |
| `maestro.retry.default-backoff-multiplier`| `double`   | `2.0`   | Multiplier applied to the interval after each failed attempt.                      |

These defaults apply globally. Individual activities can override them using the
`@RetryPolicy` annotation on the activity method.

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

## Complete Example

A full `application.yml` for an order service:

```yaml
maestro:
  service-name: order-service
  store:
    type: postgres
    table-prefix: maestro_
  messaging:
    type: kafka
    topics:
      tasks: maestro.tasks.orders
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

Maestro uses Kafka for internal task dispatch, cross-service signaling, and
lifecycle event publishing. **Topics must be pre-created** -- Maestro never
auto-creates topics.

### Topic Naming Conventions

| Topic Pattern                       | Purpose                                           |
|-------------------------------------|---------------------------------------------------|
| `maestro.tasks.{taskQueue}`         | Internal task dispatch for a specific task queue   |
| `maestro.signals.{serviceName}`     | Inbound signals for a specific service             |
| `maestro.admin.events`              | Lifecycle events consumed by the admin dashboard   |

### Consumer Groups

Consumer groups default to `maestro-{serviceName}` unless explicitly overridden
via `maestro.messaging.consumer-group`. This means each service automatically
gets its own consumer group, ensuring that every service instance in a cluster
receives its share of messages.

### Pre-creating Topics

Create topics before starting your service. Example using the Kafka CLI:

```bash
kafka-topics.sh --create --topic maestro.tasks.orders \
  --partitions 6 --replication-factor 3 \
  --bootstrap-server localhost:9092

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
