# Admin Dashboard

The Maestro Admin Dashboard provides real-time visibility into workflow state across all Maestro-enabled services. It runs as a standalone Spring Boot application, completely decoupled from your workflow services.

[← Back to README](../README.md)

---

## Overview

The Maestro Admin Dashboard is a standalone Spring Boot application that consumes workflow lifecycle events from Kafka, aggregates them in its own Postgres database, and serves a Thymeleaf + HTMX web UI on port 8090. It requires no access to your service databases -- it reads everything from Kafka.

The dashboard is completely decoupled from your services. If the dashboard goes down, your workflows continue running unaffected. When the dashboard comes back up, it catches up on missed events from Kafka.

---

## Architecture

```mermaid
graph TB
    subgraph "Services"
        S1["Order Service"]
        S2["Payment Gateway"]
    end

    subgraph "Kafka"
        T["maestro.admin.events"]
    end

    subgraph "Admin App (:8090)"
        C["Kafka Consumer"]
        A["Event Projector"]
        W["Thymeleaf + HTMX UI"]
        R["Admin Actions"]
    end

    subgraph "Storage"
        PG[("Postgres<br/>maestro_admin")]
    end

    S1 --> T
    S2 --> T
    T --> C --> A --> PG
    PG --> W
    PG --> R
    R -->|"Retry / Signal / Terminate"| T
```

**Event flow:**

1. Services publish `WorkflowLifecycleEvent` records (workflow started, activity completed, workflow failed, etc.) to the `maestro.admin.events` Kafka topic.
2. The admin app's `AdminEventConsumer` consumes these events using consumer group `maestro-admin` with per-record acknowledgement.
3. The `EventProjector` upserts the event data into four admin-owned tables: `admin_service`, `admin_workflow`, `admin_event`, and `admin_metrics`.
4. The Thymeleaf + HTMX UI queries these tables to render dashboards, workflow lists, and event timelines.
5. Admin actions (retry, terminate, send signal) are published back to Kafka on per-service signal topics (`maestro.signals.{serviceName}`) for the target service to pick up.

**Lifecycle event types** published by the engine:

| Event Type | Description |
|---|---|
| `WORKFLOW_STARTED` | Workflow execution began |
| `WORKFLOW_COMPLETED` | Workflow completed successfully |
| `WORKFLOW_FAILED` | Workflow failed (retries exhausted or compensation done) |
| `WORKFLOW_TERMINATED` | Workflow terminated by admin action |
| `ACTIVITY_STARTED` | An activity execution began |
| `ACTIVITY_COMPLETED` | An activity completed successfully |
| `ACTIVITY_FAILED` | An activity execution failed |
| `SIGNAL_RECEIVED` | A signal was received by a workflow |
| `SIGNAL_TIMEOUT` | A signal await timed out |
| `TIMER_SCHEDULED` | A durable timer was scheduled |
| `TIMER_FIRED` | A durable timer fired |
| `COMPENSATION_STARTED` | Saga compensation started |
| `COMPENSATION_COMPLETED` | Saga compensation completed |
| `COMPENSATION_STEP_COMPLETED` | An individual compensation step completed |
| `COMPENSATION_STEP_FAILED` | An individual compensation step failed |

---

## Setup with Docker Compose

The project's `docker-compose.yml` includes the admin dashboard as a service:

```yaml
admin-dashboard:
  build:
    context: .
    target: admin-dashboard
  ports:
    - "8090:8090"
  environment:
    POSTGRES_HOST: postgres
    POSTGRES_PORT: 5432
    ADMIN_DB: maestro_admin
    POSTGRES_USER: maestro
    POSTGRES_PASSWORD: maestro
    KAFKA_BOOTSTRAP: kafka:9092
    SERVER_PORT: 8090
  depends_on:
    postgres:
      condition: service_healthy
    kafka:
      condition: service_healthy
    kafka-init:
      condition: service_completed_successfully
```

### Admin database initialization

The admin dashboard uses a separate Postgres database (`maestro_admin`), not the same database as your workflow services. The `docker/init-admin-db.sh` script creates it on first startup:

```bash
#!/bin/bash
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE maestro_admin;
    GRANT ALL PRIVILEGES ON DATABASE maestro_admin TO $POSTGRES_USER;
EOSQL
```

This script is mounted into Postgres's `docker-entrypoint-initdb.d/` directory and runs only on first initialization (fresh volume). In the project's `docker-compose.yml`, this is already configured:

```yaml
postgres:
  volumes:
    - ./docker/init-admin-db.sh:/docker-entrypoint-initdb.d/init-admin-db.sh
```

### Schema migration

The admin schema is managed by Flyway and applied automatically on startup. Migrations live in `maestro-admin/src/main/resources/db/migration/admin/`. The schema creates four tables:

- **`admin_service`** -- Discovered services (auto-populated from event `serviceName` field)
- **`admin_workflow`** -- Projected workflow state (status, last step, timestamps, event count)
- **`admin_event`** -- Full event timeline log (one row per lifecycle event)
- **`admin_metrics`** -- Pre-computed workflow counts by service and status

### Kafka topic

> **Note:** When using Postgres messaging (`maestro.messaging.type: postgres`), lifecycle events are stored in the `maestro_lifecycle_event_queue` table instead of being published to a Kafka topic. The admin dashboard consumes from that table automatically when configured for Postgres messaging.

The `maestro.admin.events` topic must be pre-created. The `kafka-init` service in `docker-compose.yml` handles this:

```bash
kafka-topics.sh --create --if-not-exists --topic maestro.admin.events --partitions 1 --replication-factor 1
```

---

## Enabling Event Publishing in Services

Services must include the `maestro-admin-client` dependency to publish lifecycle events to the dashboard:

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.b2mash.maestro:maestro-admin-client")
}
```

```yaml
# application.yml
maestro:
  admin:
    events:
      enabled: true
      topic: maestro.admin.events
```

Event publishing is **enabled by default** (`enabled: true`). Adding the dependency is sufficient -- no additional configuration is required unless you need to change the topic name.

Set `enabled: false` to disable publishing (e.g., in test environments):

```yaml
maestro:
  admin:
    events:
      enabled: false
```

The `AdminEventPublisher` uses **fire-and-forget** semantics: publishing failures are logged at `WARN` level and silently swallowed. Lifecycle event failures never interrupt workflow execution.

---

## Dashboard Pages

The admin UI consists of six pages, accessible via the navigation bar:

| Page | URL | Description |
|---|---|---|
| **Overview** | `/admin` | Aggregate workflow counts by status per service. Metrics auto-refresh every 5 seconds via HTMX polling. |
| **Workflows** | `/admin/workflows` | Paginated, filterable table of all workflows across services. Filter by service, status, or free-text search against workflow ID and type. |
| **Workflow Detail** | `/admin/workflows/{workflowId}` | Full event timeline for a single workflow instance. Shows each lifecycle event with timestamps, step names, and expandable JSON detail payloads. |
| **Failed** | `/admin/failed` | Filtered view of failed workflows, sorted by most recent. One-click retry from this page. |
| **Signals** | `/admin/signals` | Signal monitor showing `SIGNAL_RECEIVED` and `SIGNAL_TIMEOUT` events. Helps identify workflows stuck waiting for signals that never arrived. |
| **Timers** | `/admin/timers` | Timer monitor showing `TIMER_SCHEDULED` and `TIMER_FIRED` events. Spot overdue timers and timer misconfigurations. |

All list pages support pagination. Filter and pagination state is preserved via query parameters, and HTMX swaps table fragments without full page reloads.

---

## Admin Actions

Operators can take the following actions from the workflow detail page. Each action publishes a command message to the target service's signal topic (`maestro.signals.{serviceName}`):

| Action | Endpoint | Description | Use Case |
|---|---|---|---|
| **Retry** | `POST /admin/workflows/{id}/retry` | Sends a `$maestro:retry` signal. The workflow resumes from its last failed step — unless its saga already compensated, in which case retry is refused as a safe no-op (see below). | Transient failures resolved, external API outage ended. |
| **Terminate** | `POST /admin/workflows/{id}/terminate` | Sends a `$maestro:terminate` signal. The workflow stops immediately, and terminate itself never starts a compensation — but a narrow open race can let an *already-starting* compensation continue on the terminated workflow ([Issue 22](open-issues.md#issue-22)). | Stuck workflows, bad data, manual intervention needed. |
| **Send Signal** | `POST /admin/workflows/{id}/signal` | Sends an application-level signal with a name and optional JSON payload. | Missing Kafka events, manual approval flows, testing. |

All actions produce a flash message confirming success or reporting failure, then redirect back to the workflow detail page.

**Note:** Admin commands use internal signal names prefixed with `$maestro:` (e.g., `$maestro:retry`, `$maestro:terminate`) to distinguish them from application-level signals.

**Semantics:**

- **Retry** applies only to a `FAILED` workflow whose saga, if any, never
  reached compensation. It discards the workflow's memoized failure (the
  `ACTIVITY_FAILED`/`WORKFLOW_FAILED` events), marks the instance `RUNNING`,
  and relaunches it exactly like crash recovery: every step before the
  failure replays from its stored result — it does **not** re-execute — and
  the step that failed re-executes live with a fresh retry-policy budget.
  Nothing else changes: memoized successful steps stay memoized. Retry is
  intended for transient failures (an external dependency that has since
  recovered), not for correcting bad workflow logic or bad input.
  **Compensated-saga caveat:** retrying a workflow whose saga already ran
  compensations is **not supported**. Its compensation events sit at
  sequence positions anchored to the failed run; if the previously-failed
  step succeeds on a hypothetical retry, the forward path would collide with
  those stale positions — re-running real compensating side effects,
  wrongly skipping others, or losing the terminal event entirely. Rather
  than risk that, Maestro detects this case (a `COMPENSATION_STARTED` event
  in the log) and refuses: the command is logged at `WARN` and acknowledged
  as a safe no-op, and the instance is left exactly as it was. There is no
  supported way to retry a compensated saga today; see the tracked
  follow-up in `docs/open-issues.md`.
- **Terminate** applies to any active workflow, including one currently
  compensating. It durably marks the instance `TERMINATED` and stops it —
  **without running any compensation** and **without interrupting an
  in-flight activity** (consistent with a graceful shutdown; the activity's
  result is still memoized if it completes, but the run stands down at its
  next checkpoint instead of continuing). A parked thread (waiting on a
  signal or timer) is unwound promptly wherever it happens to be running.
  Terminate is safe to call from any node: an optimistic version check, not
  the instance lock, is the arbiter, so the command need not reach the node
  that owns the workflow. The instance row is updated immediately regardless
  of which node processes the command; a *remote* node's own parked thread
  converges — notices the terminal state and stops — within one
  `maestro.signal.wake-recheck-interval` (default 30s) for a signal-parked
  workflow, or at its next timer fire / activity checkpoint otherwise.

Both commands are idempotent under at-least-once redelivery — a duplicate or
out-of-order command re-reads current state and either completes the
remainder or stands down as a no-op:

| Command | Instance state | Behaviour |
|---|---|---|
| Retry | `FAILED`, saga never compensated | Discards failure memo, `RUNNING`, relaunches in replay mode |
| Retry | `FAILED`, saga already compensated | No-op (`COMPENSATED_NOT_RETRYABLE`) — logged at `WARN`, unsupported today |
| Retry | `RUNNING` / `WAITING_*` / `COMPENSATING` | No-op (not failed) |
| Retry | `COMPLETED` / `TERMINATED` | No-op (not failed) |
| Retry | unknown workflow ID | No-op (not found) |
| Terminate | any active state (incl. `COMPENSATING`) | `TERMINATED`, no compensation started by the terminate itself, local eviction if this node owns it — except in the race of [Issue 22](open-issues.md#issue-22) |
| Terminate | `COMPLETED` / `FAILED` / `TERMINATED` | No-op (already terminal) |
| Terminate | unknown workflow ID | No-op (not found) |

No-op outcomes are logged and the command is acknowledged — they are
deterministic non-actions, so retrying delivery of them can never help.

**One caveat on "no compensation".** Terminate marks and stops; it never
unwinds a saga itself. There is one known exception, open and narrow: if a
terminate issued from another node lands between the saga's terminal-status
check and its own status write, the resulting conflict is swallowed and a
compensation that was just starting runs to completion on a workflow now
marked `TERMINATED`. See [`docs/open-issues.md` Issue 22](open-issues.md#issue-22)
for the mechanism and the planned fix.

**Security posture:** there is no authentication, authorization, or
provenance check on the admin-command path — anyone who can publish to
`maestro.signals.{serviceName}` can retry or terminate any workflow, and can
already inject an arbitrary application signal the same way. The dashboard
adds no additional control of its own. If this matters for your deployment,
the real control is Kafka ACLs: restrict `Write`/produce on
`maestro.signals.*` to the admin app's principal and the owning services
themselves, the same way you would restrict any other privileged control
topic.

---

## Configuration Reference

### Admin dashboard (`maestro-admin`)

All properties are configured in the admin app's `application.yml`:

| Property | Default | Description |
|---|---|---|
| `server.port` | `8090` | HTTP port for the dashboard |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/maestro_admin` | Admin database JDBC URL |
| `spring.datasource.username` | `maestro` | Database username |
| `spring.datasource.password` | `maestro` | Database password |
| `maestro.admin.events-topic` | `maestro.admin.events` | Kafka topic to consume lifecycle events from |
| `maestro.admin.consumer-group` | `maestro-admin` | Kafka consumer group ID |
| `maestro.admin.signal-topic-prefix` | `maestro.signals.` | Prefix for per-service signal topics (used for admin actions) |
| `spring.kafka.bootstrap-servers` | `localhost:29092` | Kafka bootstrap servers |

### Admin client (`maestro-admin-client`)

Configured in each service's `application.yml`:

| Property | Default | Description |
|---|---|---|
| `maestro.admin.events.enabled` | `true` | Enable or disable lifecycle event publishing |
| `maestro.admin.events.topic` | `maestro.admin.events` | Kafka topic to publish lifecycle events to |

---

## Standalone Deployment

The admin dashboard can run independently in several ways:

**As a Docker container** (alongside your services via Docker Compose, as shown above).

**As a standalone JAR:**

```bash
java -jar maestro-admin.jar \
  --spring.datasource.url=jdbc:postgresql://db-host:5432/maestro_admin \
  --spring.datasource.username=maestro \
  --spring.datasource.password=maestro \
  --spring.kafka.bootstrap-servers=kafka-host:9092
```

**In Kubernetes** as a separate Deployment with its own service and ingress.

### Requirements

The dashboard only needs:

- **Kafka access** -- to consume lifecycle events and publish admin action commands
- **Its own Postgres database** -- separate from service databases; schema is auto-migrated by Flyway on startup

No access to service databases is required. The dashboard reconstructs workflow state entirely from Kafka events.

### Resilience

- **Dashboard downtime** has zero impact on running workflows. Services continue executing normally.
- **On restart**, the Kafka consumer group (`maestro-admin`) resumes from the last committed offset, catching up on missed events.
- **Event processing errors** are logged per-message without crashing the consumer. A single malformed event does not block subsequent events.

---

## See Also

- [Configuration](configuration.md) -- Full configuration reference for all Maestro modules
- [Cross-Service Patterns](cross-service.md) -- How services communicate via signals and Kafka events
- [Concepts](concepts.md) -- Core concepts: workflows, activities, signals, timers, sagas
- [Self-Recovery](self-recovery.md) -- How Maestro handles crash recovery and signal persistence
