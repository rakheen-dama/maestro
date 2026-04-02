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
| `maestro.store.schema`     | `String` | `"maestro"` | Database schema where Maestro tables are created. Flyway migrations target this schema.                  |

The table prefix lets you run multiple Maestro instances in the same database by
giving each a unique prefix. The schema setting controls which PostgreSQL schema
Flyway creates tables in.

---

## Messaging Configuration

Properties under `maestro.messaging.*` control task dispatch, signal delivery,
and lifecycle event publishing.

### Core Properties

| Property                        | Type     | Default    | Description                                                                                     |
|---------------------------------|----------|------------|-------------------------------------------------------------------------------------------------|
| `maestro.messaging.type`        | `String` | `"kafka"`  | Messaging implementation type. Currently only `kafka` is supported.                             |
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

---

## Lock Configuration

Properties under `maestro.lock.*` configure the distributed locking layer used
for workflow instance locks, activity deduplication, and timer leader election.

| Property                  | Type       | Default          | Description                                                                                  |
|---------------------------|------------|------------------|----------------------------------------------------------------------------------------------|
| `maestro.lock.type`       | `String`   | `"valkey"`       | Lock implementation type. Currently only `valkey` (compatible with Redis) is supported.      |
| `maestro.lock.key-prefix` | `String`   | `"maestro:lock:"`| Prefix for all lock keys in Valkey/Redis. Change this to isolate multiple environments.      |
| `maestro.lock.ttl`        | `Duration` | `30s`            | Lock time-to-live. Locks are automatically renewed while the workflow is executing. If a node crashes, locks expire after this duration, enabling recovery by another node. |

The TTL value represents a trade-off: shorter values enable faster recovery after
a crash, but require more frequent renewal. The default of 30 seconds is suitable
for most workloads.

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
| `maestro.admin.events.enabled`| `boolean` | `true`                   | Whether to publish workflow lifecycle events (started, completed, failed, etc.). |
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
    schema: maestro
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
| `maestro:lock:workflow:{workflowId}`          | Workflow instance lock          | 30s (auto-renewed) |
| `maestro:dedup:{workflowId}:{seq}`            | Activity deduplication guard    | 5m                 |
| `maestro:leader:timer-poller:{service}`       | Timer poller leader election    | 15s                |
| `maestro:signal:{workflowId}`                 | Signal notification (pub/sub)   | N/A (pub/sub)      |

The `maestro:lock:` prefix is configurable via `maestro.lock.key-prefix`. If you
change it, the lock keys will use your custom prefix instead.

The instance lock is held for the duration of a workflow execution and
automatically renewed. If a node crashes, the lock expires after the configured
TTL (default 30 seconds), allowing another node to pick up the workflow during
recovery.

The deduplication key prevents the same activity from executing twice when a
workflow is recovered. It expires after 5 minutes, which is long enough to cover
any reasonable recovery window.

The leader election key ensures that only one node in the cluster runs the timer
poller. It uses a shorter TTL (15 seconds) so that leadership transfers quickly
if the current leader goes down.

---

## See Also

- [Getting Started](getting-started.md) -- Set up Maestro in a new Spring Boot project
- [Concepts](concepts.md) -- Workflows, activities, signals, timers, and the memoization model
- [Cross-Service Patterns](cross-service.md) -- Orchestration within, choreography between services
