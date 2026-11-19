# Support Matrix

What Maestro supports, what it does not, and what nobody has tested. Written
for the pilot-readiness milestone (2026-11-18); it applies to `main` from
pilot-readiness P3 onward and to the `0.3.x` line.

Three words carry the meaning of every row:

- **Supported** — intended, and exercised by the tests or harnesses named in
  the row.
- **Untested** — not ruled out, but no test or harness in this repository runs
  it. Treat it as a pilot risk you own; a defect found there is still a
  defect, but it is not a regression of anything we verified.
- **Not supported** — deliberately out of scope. The engine refuses it at
  startup where it can (the row says how); where it cannot, behaviour is
  undefined and reports about it are closed as out of scope.

A review finding about something this matrix marks *not supported* is closed
with a reason, not filed (`tasks/todo.md`, pilot-readiness working rules).

## Deployment topology

| Setup | Status | Notes |
|---|---|---|
| One service, one set of Maestro tables (its own database, or its own schema via `currentSchema`) | **Supported** | `docs/configuration.md` § Store Configuration. Every suite and harness runs one service per database. |
| Several nodes of one service sharing that service's tables | **Supported** | All nodes share one `maestro.service-name`. Exercised by the multi-node integration suites (`maestro-integration-tests` `multinode.*`), the chaos/soak harness (2 nodes per service, 6 nodes) and the loan-origination E2E. Larger fleets are untested. |
| Two or more services sharing one set of `maestro_*` tables | **Not supported** | Startup fails when the tables hold active workflow instances under another `maestro.service-name` (`maestro.store.foreign-service-check`, default `fail`; see below). The engine was never designed for co-tenancy: timers, purges and the shared-table costs in Issues 31, 77, 78 and 79 follow from it. |
| A service rename in progress (old name's rows still active) | **Supported, temporarily** | Set `maestro.store.foreign-service-check=warn` until the old name's instances are drained or moved; `docs/operations.md` §11.5. |
| Nodes of one service running with different `maestro.service-name` values | **Not supported** | Recovery adopts only its own name's rows; workflows under another name are stranded. The startup check reports them like a co-tenant. |
| A node with the recovery poller disabled (`maestro.recovery.enabled=false`) | **Supported, with limits** | A start whose instance lock cannot be taken is refused (`LockAcquisitionException`, nothing persisted) rather than deferred, because nothing on the node would run it; a resume the one-shot startup pass skips because the lock backend failed waits until a node starts with recovery; a node that dies strands its workflows until a node restarts (`docs/operations.md` §2). |
| A node without a `DistributedLock` (lock-less mode) | **Not supported** | Startup fails: the starter needs a `DistributedLock` bean (`maestro-store-postgres` provides one), and `WorkflowExecutor.Builder.build()` rejects a missing lock. No run executes without its instance lock. |

### The shared-table startup check

At context refresh each node reads, once, how many **active** instances
(`RUNNING`, `WAITING_SIGNAL`, `WAITING_TIMER`, `COMPENSATING`) are recorded
under any other `maestro.service-name`, of any workflow type. Rows found:

- `fail` (default): startup fails with `ForeignServiceRowsException`, naming
  each other service name and its count.
- `warn`: one `WARN` per name; startup continues. For a rename in progress.
- `off`: nothing is read.

The read is bounded: if the store does not answer within 5 seconds, or the read
fails, the result is *inconclusive* — one `WARN`, startup continues, the next
start re-checks. It runs at context refresh, after every singleton exists, so a
workflow host code starts earlier (from a `@PostConstruct`, say) is written
before the check; recovery never runs before it. An unreachable store therefore never turns into a restart
loop. Terminal rows under another name are not read (there is no index for
them; reading them would scan the table), so a co-tenant with no active
instance at that moment is not detected — see `docs/operations.md` §11.5 for
finding them by hand.

## Store and lock implementations

| Implementation | Status | Notes |
|---|---|---|
| `PostgresWorkflowStore` (`maestro-store-postgres`) | **Supported** | The only supported store. |
| `PostgresDistributedLock` (`maestro-store-postgres`) | **Supported** | The only supported lock; mandatory. Uses the store's `DataSource`. |
| `InMemoryWorkflowStore`, `InMemoryDistributedLock` (`maestro-test`) | **Supported for tests only** | The test kit's fakes. Never for production. |
| Any other `WorkflowStore` (another database, a custom subclass of `maestro-store-jdbc`'s `AbstractJdbcWorkflowStore`, a decorator that is not a test fixture) | **Not supported** | The `WorkflowStore` SPI is **internal until 1.0**: its methods and defaults change between releases without notice or deprecation. |
| Any other `DistributedLock` | **Not supported** | Only the Postgres lock is tested against the engine's admission, renewal and leader-election contract. |
| `WorkflowMessaging` other than `maestro-messaging-kafka` | **Not supported** | `maestro.messaging.type` accepts only `kafka`. |

## Engine versions

| Setup | Status | Notes |
|---|---|---|
| All nodes of a service on one build | **Supported** | |
| Rolling upgrade from the **previous release** (N-1 → N) | **Supported by decision; not yet proven** | Decided 2026-11-18. Today only a persisted history written at `cbf5a2a` is replayed by the current build (`upgrade.SeededHistoryUpgradeIT`). Old and new nodes running together is untested (Issue 41); the two-build rolling test is pilot-readiness P4. Follow `docs/operations.md` §10. |
| Rollback N → N-1 | **Limited** | Possible only as `docs/operations.md` §10.1 describes (drain parked awaits first; roll forward after). |
| Version skew beyond the previous release (N-2 or older beside N, or an upgrade that skips a release) | **Not supported** | Upgrade one release at a time. |
| Workflow code changed with runs in flight | **Supported as the versioning playbook describes** | `docs/operations.md` §10.2 and `docs/pitfalls.md`. A change the playbook calls unsafe stands down (`nondeterministic_history`) rather than failing the run. |

## Runtime

| Component | Status | Notes |
|---|---|---|
| Java 25 | **Supported** | The build toolchain; virtual threads are required. Other JDKs are untested. |
| Spring Boot 4.0.5 | **Supported** | The version the build and every suite use. Other 4.0.x patch releases are untested; Spring Boot 3.x is not supported. |
| Embedding `maestro-core` without Spring (`WorkflowExecutor.builder(...)`) | **Supported as an API** | Exercised by the integration suites' `MaestroEngineHarness`. The starter is the primary path; the embedder must also call `checkForeignServices(...)` and start the loops the starter starts. |

## PostgreSQL

| Setup | Status | Notes |
|---|---|---|
| PostgreSQL 16 | **Supported** | Every suite and harness runs it (Testcontainers and the chaos harness `postgres:16-alpine`; the compose files `postgres:16`). |
| PostgreSQL 14 and 15 | **Untested** | Documented as the supported floor (14) since the first release; no suite runs them. `docs/configuration.md` notes the one known difference (`PUBLIC`'s `CREATE` on `public` up to 14). |
| PostgreSQL 17 and later | **Untested** | |
| Managed services (RDS, Aurora, Cloud SQL, Azure Database for PostgreSQL) | **Untested** | |
| A read replica as the store | **Not supported** | The store and the lock write on every run. |
| Failover of the primary under a running fleet | **Untested** | Store outages are tested (`STORE_UNAVAILABLE` stand-down, `docs/operations.md` §10.9); a promoted replica is not. |
| Schema management `flyway` / `validate` / `none` | **Supported** | `docs/configuration.md` § Schema management; `MaestroSchemaInitializerTest`, `ShippedMigrationsFrozenTest`. |

## Connection pools

| Setup | Status | Notes |
|---|---|---|
| HikariCP (Spring Boot's default) | **Supported** | The samples, the chaos harness and the load scenarios run it; pool exhaustion is load scenario S5 (`perf/scenarios/s5-pool-health.sh`). |
| A dedicated pool for Maestro (`@MaestroDataSource`) | **Supported** | `docs/configuration.md` § A dedicated connection pool; `MaestroDataSourceSelectionTest`. |
| Other pools (Tomcat JDBC, DBCP2, a plain `DataSource`) | **Untested** | The unit suites use `PGSimpleDataSource`, which pools nothing. |
| PgBouncer in **transaction** pooling mode | **Untested** | `LISTEN` needs a session: set `maestro.signal.wake-notify.enabled=false` (cross-node wake then falls back to `maestro.signal.wake-recheck-interval`). Nothing else has been run behind PgBouncer, including pgjdbc's server-side prepared statements. |
| PgBouncer in session pooling mode | **Untested** | |
| Probing the root `/actuator/health` on a pool Maestro shares with the host | **Not supported** | On an exhausted pool the root waits on Boot's `db` indicator (Hikari's `connectionTimeout`, 30 s) though `maestro` answers within 2 s. Probe the groups: `readinessState,maestro` and `livenessState,maestroLiveness` (`docs/operations.md` §14; Issue 75). |

## Kafka

| Setup | Status | Notes |
|---|---|---|
| Confluent Platform 7.7 (`cp-kafka:7.7.1`, KRaft, single broker) | **Supported** | The broker of the Testcontainers suites (`maestro-messaging-kafka`, `maestro-integration-tests`, `maestro-admin`). |
| Apache Kafka 3.9 (`apache/kafka:3.9.0`, KRaft, single broker) | **Supported** | The broker of the chaos/soak harness and of the compose files (loan-origination E2E, demo, load scenarios). |
| Multi-broker clusters, replication factor above 1 | **Untested** | |
| Topics pre-created, broker auto-create off | **Supported, required** | Startup verifies every required topic (`maestro.messaging.topics.verify-on-startup`, default `fail`); `docs/configuration.md` § Kafka Topic Verification. Auto-creating topics is not supported. |
| TLS / SASL to the broker | **Untested** | The engine's clients take security settings from `spring.kafka.*`, not from `KafkaConnectionDetails` (Issue 53). |
| No Kafka at all (no `maestro-messaging-kafka`) | **Untested in production** | The starter tests run without a transport; cross-service signals, `@MaestroSignalListener` and lifecycle events then do not exist. |
| Spring Boot's own Kafka beans beside Maestro's | **Supported** | WP7; `docs/configuration.md` § Kafka Client Configuration. |

## Where this matrix is enforced

| Rule | Enforced by |
|---|---|
| A `DistributedLock` is mandatory | `WorkflowExecutor.Builder.build()` (`MISSING_LOCK_MESSAGE`); the starter's `MaestroAutoConfiguration` (`MISSING_LOCK_MESSAGE`) |
| Services do not share tables | `WorkflowExecutor.checkForeignServices`, run by the starter at context refresh (`maestro.store.foreign-service-check`) |
| One lock and one transport type | `maestro.lock.type` accepts only `postgres`, `maestro.messaging.type` only `kafka` |
| Topics are pre-created | `KafkaTopicVerifier` (`maestro.messaging.topics.verify-on-startup`) |
| `WorkflowStore` is internal until 1.0 | Its Javadoc; this matrix |

See also: `docs/operations.md` (multi-instance behaviour, versioning, the
shared-table runbook), `docs/configuration.md` (every property), and
`docs/open-issues.md` (open issues ranked by severity against this matrix).
