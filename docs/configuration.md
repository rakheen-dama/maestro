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
| `maestro.service-name`      | `String`   | --      | **Required.** Logical name of the owning service. Used for Kafka consumer groups, lock key prefixes, and lifecycle event attribution. Auto-configuration will fail if not set. **Every node of a service must use the same name**: recovery adopts only active instances whose `service_name` equals it (see [Recovery Configuration](#recovery-configuration)), so a replica with a name of its own — or a rename while workflows are active — leaves those workflows unadopted until the name is restored. |
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

**Isolating services that share a database.** Maestro's tables are always named `maestro_*`. Two services must never share a `maestro_workflow_instance` table, so give each service its own Postgres schema and point its datasource at it — `jdbc:postgresql://host/db?currentSchema=order_service` or `spring.datasource.hikari.schema: order_service`. Maestro's migrations (see [Schema management](#schema-management)) run in the connection's current schema, so no further configuration is needed. `maestro.store.table-prefix` was removed; setting it fails startup. To give the engine a connection pool of its own rather than the application's, see [A dedicated connection pool](#a-dedicated-connection-pool).

### Schema management

`maestro-store-postgres` creates and upgrades Maestro's `maestro_*` tables at startup with an embedded Flyway run that Maestro owns: it records what it applied in its **own history table, `maestro_schema_history`**, and never reads or writes your application's `flyway_schema_history`. Your `spring.flyway.*` settings (location, table, baseline, `enabled`) apply only to your own Flyway and are unaffected — a `V1__init.sql` of yours in `classpath:db/migration` does not collide with Maestro's `V1`. Your application does not need `spring-boot-starter-flyway` for Maestro; Flyway is already a runtime dependency of `maestro-store-postgres`. You do need `spring-boot-starter-jdbc` (for the `DataSource`).

`maestro-store-postgres` ships its SQL under two classpath locations and advertises them in a `META-INF/maestro/schema-locations` resource; the initializer applies every location it finds on the classpath:

| Concern                      | Location                         | Versions shipped |
|------------------------------|----------------------------------|-----------|
| Workflow store               | `classpath:db/maestro/store`     | V1–V9, V101 |
| Distributed lock + leader election | `classpath:db/maestro/lock` | V100      |
| *(retired)* Postgres messaging transport | *(no longer shipped)*  | 200–299 — never reused |

**Numbering rule.** Every new Maestro migration, store or lock, takes the next version above the highest shipped, within 101–199. Versions 1–9 and 100 are history: nothing is ever added below V100 again. Band 200–299 stays retired and must never be reused. The directory says which component a migration belongs to; the version number no longer does. The reason is older builds. Every build since the lock band joined `maestro-store-postgres` resolves V100. To such a build, an applied migration it does not ship is a "future" migration if it sorts above V100, and Flyway's default `ignoreMigrationPatterns=*:future` ignores it, so the older node starts normally. Below V100 the same migration is "missing", and the older node fails startup in `flyway` and `validate` mode with `Detected applied migration not resolved locally: <n>`. Numbered this way, a migration never stops a node on an earlier build from restarting, or a rollback from starting. `MaestroSchemaInitializerTest` pins the rule and runs main @ a27a275's exact Flyway configuration against a database this build migrated, and `ShippedMigrationsFrozenTest` fails if any migration shipped at `cbf5a2a` is edited, renamed or deleted.

Band 200–299 belonged to the Postgres messaging transport (`V200`–`V202`), removed in favour of Kafka as the only transport. A database that applied it keeps its rows in `maestro_schema_history` and an orphan `maestro_signal_queue` table (and, if it never reached `V202`, `maestro_task_queue` and `maestro_lifecycle_event_queue`). Flyway's default `ignoreMigrationPatterns=*:future` treats those history rows as future versions, so `flyway` and `validate` modes keep passing; but those rows still count as the highest applied version, so V6–V9, V101 and every later migration apply on such a database only because the initializer runs Flyway with `outOfOrder`, and anyone applying Maestro's SQL to such a database with the Flyway CLI must pass `-outOfOrder=true`. `DROP TABLE IF EXISTS maestro_signal_queue, maestro_task_queue, maestro_lifecycle_event_queue;` is optional cleanup once no node on an earlier build remains.

**Out-of-order application.** Flyway refuses a migration that sorts below the highest version a database has recorded, and that includes versions this build does not ship. Two real histories hit this, so `outOfOrder` is load-bearing and must never be removed: a database that ran the retired Postgres messaging transport recorded V200–V202 (or as much of that band as it reached), so V6–V9, V101 and every future V102–V199 sort below the retired rows there (see above); and a database first migrated before V6–V9 shipped recorded V100 before them. `MaestroSchemaInitializerTest` pins both. For V6–V9: V6–V9 shipped before the numbering rule, below V100. A database first migrated before they shipped recorded V100 before them, so they sort below its highest applied version. The store and lock migrations never depend on each other, so the initializer runs Flyway with `outOfOrder` enabled: such a migration is applied although it sorts below V100, and `validate` reports it as pending ([Issue 35](open-issues.md#issue-35)). The same history has a limit that this build cannot remove, because it lies in the older build's own Flyway check. A node on an earlier, unreleased build that lacks one of V6–V9 cannot start in `flyway` or `validate` mode against a database that recorded it (`Detected applied migration not resolved locally: <n>`). Those are the main builds from c01d498 (PR #35) up to, but not including, e91bd9d (PR #43): from PR #36 every build resolves V100; before it, a build did only when the host had the optional `maestro-lock-postgres` module (or `maestro-messaging-postgres`, whose V200–V202 sort above every store version). Nodes already running are unaffected, because Flyway checks only at startup. Such a node can start against the newer history only with `maestro.store.schema.mode: none`. Every build from e91bd9d on, main @ a27a275 included, ships V1–V9 and V100, and V101 is a future migration to it. (A pre-PR #36 build with `maestro-messaging-postgres` sees V101 as missing too, but it already fails on V6–V9.)

The initializer runs after your application's own database initializers (its Flyway, Liquibase or `spring.sql.init` scripts) and before the store and engine beans are created. When it first meets a schema that already holds your tables, it writes a baseline marker row at version `0` into `maestro_schema_history` and then applies every Maestro migration — your tables are not touched. Several nodes starting at once are safe: Flyway serialises concurrent runs with a database lock. (If you add Flyway to your own application *later*, against a database that already holds Maestro's tables, your Flyway needs `spring.flyway.baseline-on-migrate: true` for its first run, as for any non-empty schema.)

The ordering covers only database initializers that Spring Boot knows about (`spring.flyway.*`, `spring.liquibase.*`, `spring.sql.init.*`). If your application runs Flyway by hand — its own `Flyway.configure()...migrate()` in a bean or a runner — it is not ordered before Maestro's initializer: either migrate through `spring.flyway.*`, or set `maestro.store.schema.mode: none` and call `new MaestroSchemaInitializer(dataSource, SchemaMode.FLYWAY, classLoader).initialize()` yourself after your own migration.

The initializer belongs to `maestro-store-postgres` and runs only when `maestro.store.type` is `postgres` (the only supported store). The lock tables are created by that same initializer, from the same module.

**In production.** By default (`flyway`), every node start runs Maestro's DDL as the application's own database role: the initializer's Flyway connects through the application's `DataSource` and applies whatever is pending. This is the same as Spring Boot's own Flyway default. That role must therefore be able to create tables in the schema and must own the `maestro_*` tables (PostgreSQL lets only the owner alter a table or index it, see below). Index migrations lock their table against writes while they run, so the deploy that ships one pauses workflow writes on that table for as long as the index takes to build.

The recommended production setup separates two roles. The SQL below uses placeholder names: `maestro_owner` is the role that migrates and owns Maestro's tables, `maestro_app` is the role the nodes connect as, and `public` is the schema that holds Maestro's tables (see "A schema other than `public`" below before you change it).

**Apply migrations as the owner, as a separate step before deploying the build that ships them.** Connect as `maestro_owner`. Either run the Flyway CLI with the options listed under "`validate` and `none`" below (they must include `-outOfOrder=true`, see "Out-of-order application" above), or run a one-off job that calls `new MaestroSchemaInitializer(ownerDataSource, SchemaMode.FLYWAY, classLoader).initialize()`. Maestro has no `maestro.store.schema.user` or `maestro.store.schema.password`, so the owner's credentials go only to that job, never to the nodes. The migration role need not be a superuser. It needs `USAGE` and `CREATE` on the schema, and the nodes' role needs `USAGE`. Only the schema's owner (or a superuser) can grant these. Issued by `maestro_owner`, the second line would only draw a warning and grant nothing:

```sql
GRANT USAGE, CREATE ON SCHEMA public TO maestro_owner;
GRANT USAGE ON SCHEMA public TO maestro_app;
```

The migration role must also own the `maestro_*` tables. It does if it created them; a role that did not create them must be a member of the role that did. The membership case is not covered by the tests named below.

**Run every node with `maestro.store.schema.mode: validate`** and a role that holds only that `USAGE` and these table grants, issued by `maestro_owner`:

```sql
GRANT SELECT, INSERT, UPDATE, DELETE ON maestro_workflow_instance, maestro_workflow_timer,
    maestro_workflow_signal, maestro_distributed_lock, maestro_leader_election TO maestro_app;
GRANT SELECT, INSERT, DELETE ON maestro_workflow_event TO maestro_app;
GRANT SELECT ON maestro_schema_history TO maestro_app;
```

Under `validate` a node never needs DDL rights: it reads `maestro_schema_history`, and it refuses to start, naming the pending versions, until the owner has applied them. The role can append workflow history and purge it, but not rewrite it: it holds no `UPDATE` on `maestro_workflow_event`, and Maestro issues none. Nothing else is needed: Maestro's tables use no sequences, `LISTEN` and `pg_notify` (the cross-node signal wake) need no grant, and the lock cleaner's deletes are covered by `DELETE`. The role also needs `CONNECT` on the database, which PostgreSQL grants to `PUBLIC` by default. A role without `USAGE` on the schema cannot see `maestro_schema_history`, so `validate` reports *every* migration as pending, even on a fully migrated database.

**A schema other than `public`.** Replacing `public` in the SQL above fixes only the schema grants. The table names in the grants are unqualified, so they resolve through the `search_path` of the session that issues them. Maestro's Flyway run names no schema either, so it reads `maestro_schema_history`, and creates tables, in each connection's current schema: the first schema on its `search_path` that exists and that the role may use. Point both roles at Maestro's schema, with `ALTER ROLE maestro_owner SET search_path = <schema>` and the same for `maestro_app`, or with `currentSchema=<schema>` in each role's JDBC URL (see "Isolating services that share a database" above). An application role whose search path misses the schema shows the same symptom as a missing `USAGE`: `validate` reports every migration as pending.

**Tables added by a later migration.** The grants name tables, so they cover only the tables that exist when you run them. A migration that only adds a column or an index needs no new grant; one that adds a `maestro_*` table does. `validate` checks the migration history, never grants. A node whose role lacks the new table's grant therefore starts normally, and then every statement on that table fails with SQLSTATE `42501`. Maestro's store treats that as a store fault (`StoreUnavailableException`): only SQLSTATE classes `22`, `23` and `54` count as an error in the workflow's own data. So the affected runs are not failed. They stand down and are abandoned for recovery, over and over. To prevent it, run this once as the owner, before the migration that adds the table:

```sql
ALTER DEFAULT PRIVILEGES FOR ROLE maestro_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO maestro_app;
```

It has two limits. It covers only tables that `maestro_owner` creates after it is issued; tables that already exist still need the table grants above. And it covers every table that role creates in that schema, not only Maestro's, so use Maestro's migration role for nothing else. The alternative is to re-run the table grants, extended with the new table, after each migration and before any node of the new build starts.

**PostgreSQL 14.** Up to PostgreSQL 14, the oldest version Maestro supports, `PUBLIC` holds `CREATE` on the `public` schema by default, so `maestro_app` could still create tables there. To keep it DML-only, run `REVOKE CREATE ON SCHEMA public FROM PUBLIC;` (the default from PostgreSQL 15) together with `GRANT USAGE, CREATE ON SCHEMA public TO maestro_owner;`. Without that grant, a migration role that does not own the schema and relied on `PUBLIC`'s `CREATE` can no longer migrate.

**Order during a rolling deploy: migrate first.** Nodes still on the earlier build keep running, and keep restarting, against the migrated database, because every new migration takes a version above V100 and is a "future" version to them (see "Numbering rule" above). That covers Flyway's version check only. The earlier build's SQL keeps working only if the new migration is backward-compatible with it: one that adds what the earlier build ignores, not one that renames, drops or tightens what it reads and writes.

`validate` is not the default because it would fail every first start of every new installation: on an empty database every migration is pending. Two tests in `MaestroSchemaInitializerTest` run this setup with exactly the SQL printed here, on a schema where `PUBLIC` holds no rights, with a non-superuser owner that holds only the schema grants above:

- `aDmlOnlyRoleRunsValidateModeButCannotMigrate`: with only these grants, a node starts under `validate` and drives the store, the lock, the lock cleaner and the signal wake. A migration, `CREATE TABLE`, `CREATE INDEX` and `ALTER TABLE` all fail with a permission error.
- `aTableAddedByALaterMigrationNeedsDefaultPrivileges`: a table added by a later migration passes `validate` and is refused with `42501`. A table added after `ALTER DEFAULT PRIVILEGES` is usable.

`theDocumentedSqlIsTheTestedSql` fails if this page and those tests disagree. The "A schema other than `public`" setup is not covered by a test.

**`validate` and `none`.** With `maestro.store.schema.mode: validate` Maestro applies nothing and fails startup — naming the pending versions — if any migration is pending, or if an applied one no longer matches the shipped SQL; it reads `maestro_schema_history`, so whoever applies the SQL out of band must record it there (e.g. the Flyway CLI with `-table=maestro_schema_history`, the locations above, `-outOfOrder=true`, and — on a schema that already holds your tables — `-baselineOnMigrate=true -baselineVersion=0`, exactly as Maestro's own run does). Do not insert a history row by hand: `validate` compares each applied migration's checksum with the shipped SQL, so a hand-written row fails it. Whatever records a migration must connect as the role that owns the `maestro_*` tables. PostgreSQL checks table ownership before `IF NOT EXISTS`, so even a `CREATE INDEX IF NOT EXISTS` for an index that already exists fails for any other role with `must be owner of table maestro_workflow_instance` (verified on PostgreSQL 16). Under `validate`, the application's role often has no DDL rights, so the Flyway CLI run by the owning role is the usual route. If the application's role does own the tables, one start of a node, or a one-off job calling `new MaestroSchemaInitializer(dataSource, SchemaMode.FLYWAY, classLoader).initialize()`, with `maestro.store.schema.mode: flyway`, then the rest under `validate`, works too. With `none` Maestro does nothing and reads no history: you apply the SQL under the locations above yourself, in version order, before deploying the build that ships it. A build at or past V5 omits `received_at` from signal inserts, so a self-managed database must have V5 applied (`ALTER TABLE maestro_workflow_signal ALTER COLUMN received_at SET DEFAULT now()`) before that build is deployed, or every signal insert fails. A build at or past V6 names `maestro_workflow_instance.lease_epoch` in every instance read and write (the lease-fencing token), so V6 (`ALTER TABLE maestro_workflow_instance ADD COLUMN IF NOT EXISTS lease_epoch BIGINT NOT NULL DEFAULT 0`) must likewise be applied first, or no workflow can be started, resumed or read. A build at or past V7 names `maestro_workflow_event.args_hash` in every event insert and read (the activity-argument fingerprint), so V7 (`ALTER TABLE maestro_workflow_event ADD COLUMN IF NOT EXISTS args_hash VARCHAR(64)`) must likewise be applied first, or no activity outcome can be recorded or replayed.

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

   - **Keep the data.** Set `maestro.store.schema.mode: none` on every node — you own Maestro's DDL from then on, applying each later migration by hand before deploying the build that ships it — and apply by hand the five migrations a 0.4.0 database lacks that this build cannot run without: `V4__signal_trace_context.sql`, `V5__signal_received_at_default.sql`, `V6__instance_lease_epoch.sql`, `V7__workflow_event_args_hash.sql` and `V9__signal_message_id.sql` (`maestro-store-postgres`, `classpath:db/maestro/store`), plus the index-only V8 and V101. As written here they are safe to re-run, including on a database a pre-release build already took to V4, V5, V6, V7 or V9; the retired messaging transport's queue tables may be dropped at the same time (optional — nothing on this build reads them):

     ```sql
     ALTER TABLE maestro_workflow_signal ADD COLUMN IF NOT EXISTS trace_context VARCHAR(128);  -- V4
     ALTER TABLE maestro_workflow_signal ALTER COLUMN received_at SET DEFAULT now();         -- V5
     ALTER TABLE maestro_workflow_instance ADD COLUMN IF NOT EXISTS lease_epoch BIGINT NOT NULL DEFAULT 0; -- V6
     ALTER TABLE maestro_workflow_event ADD COLUMN IF NOT EXISTS args_hash VARCHAR(64);       -- V7
     CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_wf_instance_terminal                        -- V8
         ON maestro_workflow_instance (status, completed_at) WHERE completed_at IS NOT NULL;
     CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_wf_signal_instance                          -- V8
         ON maestro_workflow_signal (workflow_instance_id);
     ALTER TABLE maestro_workflow_signal ADD COLUMN IF NOT EXISTS message_id TEXT;            -- V9
     CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS idx_wf_signal_message_id                 -- V9
         ON maestro_workflow_signal (workflow_id, signal_name, message_id) WHERE message_id IS NOT NULL;
     CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_wf_instance_recovery                        -- V101
         ON maestro_workflow_instance (service_name, started_at, id)
         WHERE status IN ('RUNNING', 'WAITING_SIGNAL', 'WAITING_TIMER', 'COMPENSATING');
     DROP TABLE IF EXISTS maestro_signal_queue, maestro_task_queue, maestro_lifecycle_event_queue; -- optional cleanup
     ```

     `V8` is index-only (the retention sweeper works without it, scanning the tables on every batch), so it is written here with `CONCURRENTLY`, which the Flyway-run original cannot use — see [Retention Configuration](#retention-configuration); so is V9's unique index (every existing row has `message_id` `NULL`, so the partial index starts empty), and so is V101 (index-only: under `none` nothing reads the history, and the paged recovery scan's query is correct without the index, only slower — see [Recovery Configuration](#recovery-configuration)). Paste the `CONCURRENTLY` lines one statement at a time — `CREATE INDEX CONCURRENTLY` cannot run inside a transaction block. **V9 is a hard prerequisite under `schema.mode: none`**: this build names `message_id` in every signal insert and read, so without the column every delivery fails inside the Kafka listener and is redelivered until dead-lettered, and without the index duplicates are not suppressed (see [Cross-service § Signal identity](cross-service.md#signal-identity)). Do not skip V4, V5, V6 or V7 either: this build names `trace_context` in every signal insert and leaves `received_at` to the column default, so without them every `saveSignal` fails — inside the Kafka listener, so each signal is redelivered until it is dead-lettered; it names `lease_epoch` in every instance read and write (it is the lease-fencing token — see [Operations §6](operations.md#6-split-brain-behaviour-issue-11--issue-18)), so without V6 no workflow can be started, resumed or read; and it names `args_hash` in every event insert and read (the activity-argument fingerprint — see [Operations §10.7](operations.md#107-replay-drift-nondeterministic_history)), so without V7 no activity outcome can be recorded or replayed. V6 and V7 are metadata-only on PostgreSQL 11+ (a constant `DEFAULT` on `ADD COLUMN`, or no default at all, rewrites no rows); every pre-existing row reads back as epoch `0` and as an unverified (`NULL`) fingerprint.
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
       ('7',   'V7__workflow_event_args_hash.sql'),
       ('8',   'V8__retention_indexes.sql'),
       ('9',   'V9__signal_message_id.sql'),
       ('100', 'V100__maestro_lock_postgres.sql'),
       ('200', 'V200__maestro_messaging_postgres.sql'),
       ('201', 'V201__redelivery_dead_letter.sql'));
   ```

   Without this, an application whose own Flyway has migrations fails startup with `Detected applied migration not resolved locally` — and one whose Flyway has none yet fails the day it adds its first.
4. **Start the new build** on every node.

### A dedicated connection pool

By default every Maestro component that talks to Postgres — the schema
initializer, the store, the distributed lock, the lock cleaner, the
`LISTEN/NOTIFY` wake listener and the signal notifier — uses the application's
single (or `@Primary`) `DataSource`, so a busy engine borrows from the same
pool as the application's request threads. To give the engine a pool of its
own, qualify one `DataSource` bean with
`@io.b2mash.maestro.store.postgres.MaestroDataSource`; all six components then
use it, and Maestro's tables are created in (and migrated through) that pool's
database and schema. The declaration depends on whether the application keeps
a `DataSource` of its own.

**The application also has its own `DataSource`** (Spring Boot's
auto-configured one from `spring.datasource.*`, or its own `@Primary`) —
declare Maestro's with `defaultCandidate = false`:

```java
@Bean(defaultCandidate = false)
@MaestroDataSource
DataSource maestroDataSource() {
    var pool = new HikariDataSource();
    pool.setJdbcUrl("jdbc:postgresql://db:5432/app?currentSchema=maestro");
    pool.setMaximumPoolSize(25);   // >= default-activity-concurrency + 5, see below
    return pool;
}
```

`defaultCandidate = false` keeps the pool out of the application's way:
Boot's `DataSource` auto-configuration does not back off because of it, and
the application's unqualified injection points (JPA, `JdbcTemplate`, its own
Flyway) never receive it. **Without `defaultCandidate = false`** the qualified
bean is an ordinary `DataSource` candidate: where the application relied on
Boot's auto-configured pool, that auto-configuration backs off, the
application's own pool is never created, and every unqualified consumer gets
Maestro's pool instead.

**Maestro's pool is the only one** — a plain bean is simplest; Boot's
auto-configuration backs off and nothing else competes for the type:

```java
@Bean
@MaestroDataSource
DataSource maestroDataSource() { ... }
```

A `defaultCandidate = false` bean as the *only* `DataSource` also starts the
engine, but Boot's `DataSource` auto-configuration does not see it and still
tries to build a host pool from `spring.datasource.*`: with no URL and no
embedded database on the classpath, startup fails with `Failed to determine a
suitable driver class`. Exclude `DataSourceAutoConfiguration`, or use the plain
form above.

Put the qualifier on one bean, on the `@Bean` method or on the bean's class.
If several carry it, Spring's usual tie-breakers pick Maestro's: the `@Primary`
one, else the one named `maestroDataSource` (the name of Maestro's injection
points). With neither, startup fails with `Maestro found 2 @MaestroDataSource
beans and none is @Primary: [...]. Keep one, or mark the one Maestro should use
@Primary.` With no qualified bean and two or more
unqualified `DataSource`s, none `@Primary`, startup fails with `Maestro found
<n> DataSource beans and none is @Primary or @MaestroDataSource. Annotate the
one Maestro should use with @MaestroDataSource.`

**Sizing the pool.** With a qualified pool, all of Maestro's database traffic
comes out of it: the wake listener's one long-lived connection, a short
borrow for every `NOTIFY`, the lock renewer, the pollers, and the store calls
of every workflow run and activity. A workflow slot's holder borrows
connections as well as an activity slot's: a run replaying its history, or a
parked run's wake re-check, holds a workflow slot without running an
activity. Size the pool at **at least `max(maestro.worker.default-concurrency,
maestro.worker.default-activity-concurrency) + 5`** (25 at the defaults); if
several task queues are busy at once, add the same `max(...)` for each. As arithmetic, wake re-checks keep on average
P × d / `wake-recheck-interval` workflow slots busy, where P is the number of
parked workflows and d one re-check's duration (2–3 reads). For example,
assuming 5 ms per re-check, 2,000 parked workflows × 5 ms / 30 s ≈ 0.3 slots. A
pool that is too small surfaces as connection timeouts, which the engine
treats as store outages (`STORE_UNAVAILABLE` abandons, see
[Operations §10.9](operations.md#109-store-outages-store_unavailable-abandons)),
not as workflow failures. The same arithmetic is in
[Operations § Capacity](operations.md#13-capacity).

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
| `maestro.messaging.topics.verify-on-startup` | `fail` \| `warn` \| `off` | `fail` | What happens when a topic this service needs does not exist at startup — see [Kafka Topic Verification](#kafka-topic-verification). |

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
restoring plain at-most-once handler semantics. The `.DLT` topics drop out of
the set the [startup topic verification](#kafka-topic-verification) requires,
since nothing will ever publish to one; `max-attempts` and the backoff
properties are ignored while the flag is off.

### Kafka Topic Verification

Maestro never creates topics, `.DLT` companions included (see
[Pre-creating Topics](#pre-creating-topics)). Before this check existed a
forgotten topic surfaced late and indirectly: the producer blocked for
`max.block.ms` on *every* publish to a missing admin-events topic, a consumer
on a missing signal topic polled nothing forever, and a missing `.DLT` only
showed up once a handler's attempt budget was first exhausted — as a recoverer
that could not publish, at the exact moment an operator was already debugging
something else.

`KafkaTopicVerifier` closes that gap **once, at startup**, from
`MaestroSignalListenerBeanPostProcessor.afterSingletonsInstantiated` — after
every singleton exists (so every `@MaestroSignalListener` is known), before any
consumer container starts, and before `StartupRecoveryRunner` resumes a
workflow. It composes the set of topics this service needs and asks the broker,
in one `describeTopics` call bounded to 5 seconds, whether each exists:

| Required topic                                                   | When                                              |
|------------------------------------------------------------------|---------------------------------------------------|
| `maestro.signals.{serviceName}` (or `maestro.messaging.topics.signals`) | always                                     |
| `maestro.signals.{serviceName}.DLT` (`+ dead-letter-suffix`)     | `maestro.messaging.redelivery.enabled=true` (default) |
| `maestro.messaging.topics.admin-events`                          | `maestro.admin.events.enabled=true` (default)     |
| every `@MaestroSignalListener(topic = "...")` topic              | always                                            |
| each listener topic `+ dead-letter-suffix`                       | `maestro.messaging.redelivery.enabled=true` (default) |

Plain `@KafkaListener`-consumed topics are **not** in scope — they run outside
Maestro entirely.

`maestro.messaging.topics.verify-on-startup` decides what a gap means:

- **`fail`** (default) — a topic the broker confirms **missing** fails
  startup with one `IllegalStateException` that lists every missing topic and
  a ready `kafka-topics.sh --create` line for each:

  ```text
  2 of 5 Kafka topic(s) Maestro needs do not exist on kafka:9092:
  [maestro.signals.order-service.DLT, payments.results.DLT]. Maestro never creates
  topics — pre-create them (or set maestro.messaging.topics.verify-on-startup=warn
  to start without them):
    kafka-topics.sh --bootstrap-server kafka:9092 --create --topic maestro.signals.order-service.DLT --partitions 3 --replication-factor 3
    kafka-topics.sh --bootstrap-server kafka:9092 --create --topic payments.results.DLT --partitions 3 --replication-factor 3
    (adjust --partitions/--replication-factor to the cluster; a single-broker development cluster needs --replication-factor 1)
  ```

  Under `fail`, once the probe has confirmed **every** required topic present,
  the consumer containers (the engine's signal channel and every listener) are
  also started with Spring Kafka's `missingTopicsFatal`, so a topic that
  disappears *after* startup fails the container instead of leaving it polling
  nothing. (Not after an inconclusive probe — see below.)
- **`warn`** — the same message at `WARN`; startup continues and the
  containers stay tolerant. The escape hatch for a deploy that cannot create
  topics yet.
- **`off`** — no verification; the pre-0.5 behaviour.

A topic is *missing* only when the broker says so
(`UnknownTopicOrPartitionException`). A probe that cannot reach the broker —
timeout, connection refused, authentication or authorization failure — is
**inconclusive**: logged at `WARN` naming the unverified topics, and never
fatal under any mode, because a broker blip at deploy time must not become a
fleet-wide restart loop. That boot's consumer containers then stay tolerant
too — Spring Kafka's `missingTopicsFatal` check counts an unreachable broker
as "missing" and would otherwise fail `start()` right after the WARN — so a
topic that really is missing polls nothing until it is created, and the next
restart re-verifies. The verifier's `Admin` client is derived from the
engine consumer factory's full configuration (`bootstrap.servers`, `security.*`,
`sasl.*`, `ssl.*`), so it authenticates the way the consumers do.

Two client defaults back the policy up in code (both are defaults, not
invariants — see [Kafka Client Configuration](#kafka-client-configuration)):
the engine consumer sets `allow.auto.create.topics=false`, so a Maestro consumer
cannot conjure a topic into existence on a broker that still allows it; and
the engine producer sets `max.block.ms=10000`, down from Kafka's 60 s, so a
publish to a topic that is missing anyway costs at most ten seconds — off the
workflow thread (see [Operations § Startup fails: missing Kafka topic](operations.md#12-startup-fails-missing-kafka-topic)).

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
`classpath:db/maestro/lock` (V100; see [Schema management](#schema-management)):

- `maestro_distributed_lock` — one row per held key. `tryAcquire` is a single
  `INSERT ... ON CONFLICT (lock_key) DO UPDATE` whose update predicate requires
  the existing row's `expires_at` to be in the past, so an unexpired lock held by
  another node is never stolen and an expired one is taken over atomically. The
  returned handle carries a random owner token; `release` and `renew` are
  conditioned on that token, so a node whose lock expired and was re-acquired
  elsewhere can neither release nor extend the new holder's row.
- `maestro_leader_election` — one row per election key (`trySetLeader`), same
  expiry-predicate CAS, used by the timer poller.

The lock uses the same `DataSource` as the store (the application's, or the
one qualified `@MaestroDataSource` — see
[A dedicated connection pool](#a-dedicated-connection-pool)): an acquire or
release is one short statement on the pool. Renewal is batched: every
`maestro.lock.ttl` / 3 (10 s at the default TTL) each node renews **all** the
instance locks it holds with one `UPDATE ... FROM unnest(...)` statement per
500 locks, on one connection. A renewal pass that takes longer than that
interval logs a `WARN` naming the lock count, the duration and the TTL. A
database error during a pass loses nothing: every handle is kept and retried
on the next pass (the TTL covers about two missed passes). Timestamps come from the database clock, so node clock skew does not
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
| `maestro.worker.default-concurrency`                | `int`                      | `20`    | Workflow-run limit of every task queue **not** listed in `task-queues`, per queue, per node. `0` (together with `default-activity-concurrency: 0`) leaves unlisted queues unbounded. |
| `maestro.worker.default-activity-concurrency`       | `int`                      | `20`    | Live-activity limit of every task queue not listed in `task-queues`, per queue, per node. `0` only together with `default-concurrency: 0`. |

`concurrency` caps how many workflow runs on this queue execute at once on this node; a run holds its slot from start until it parks on `sleep()`/`awaitSignal()` or reaches a terminal status, and reacquires one before resuming. `activity-concurrency` caps live activity executions per queue on this node; replayed (memoized) steps never take a slot. A queue not listed here gets its own pools sized by `default-concurrency` / `default-activity-concurrency` — **20 runs and 20 live activities per queue per node by default** — so a deploy cannot replay every active workflow at once through the database pool. Each unlisted queue has separate pools; they are not shared between queues. Set both defaults to `0` to leave unlisted queues unbounded (the behaviour before this default existed); `0` on only one of them, or a negative value, fails startup with a message naming both properties. Limits are per node — three nodes with `concurrency: 10` run up to 30 at once. At startup the engine logs the effective limits at `INFO`, for example `Maestro worker limits: default 20/20 per task queue (workflows/activities); configured: orders=10/20`, or `Maestro worker limits: unbounded (maestro.worker.default-concurrency=0)`.

A run waiting for a slot holds its instance lock (so no other node adopts it)
but has executed nothing; if the node begins shutting down before a slot frees,
it stands down and stays recoverable. The same goes for a run waiting for an
activity slot: the activity does not run. An activity slot is held from the
activity's dedup lock through every retry attempt and backoff to its persisted
outcome. Every listed limit must be `>= 1`, and naming a queue twice fails startup.

Recovery does not wait for slots: it adopts every instance it can lock, and
each adopted run then queues for its slot on its own thread, so startup is
never held up by a backlog. Because every queue is now bounded by default,
two consequences of that are on the default path (`docs/open-issues.md`
Issues 29 and 30): a node that adopts more runs than its slots holds their
instance locks while they queue, so other nodes cannot take that backlog over;
and a run still queued for a slot when shutdown begins is not interrupted — it
stands down, recoverable, once it gets a slot, or the shutdown drain times out
around it. Nothing is written or lost either way.

On a saturated queue, a parked run's periodic re-check (which picks up a
terminate issued on another node and is the fallback for a missed wake) waits
for a free slot too, so convergence is bounded by slot availability rather than
by `maestro.signal.wake-recheck-interval`.

**Waiting on a workflow of the same queue can deadlock.** A run keeps its
workflow slot while one of its activities runs, and the activity keeps an
activity slot. Suppose an activity starts another workflow on this node and
waits for it, through `startAndWait` or by polling its status. If that
workflow has the same task queue, it needs a slot from the same pools. Once
`concurrency` runs of one queue each wait on another run of that queue, no
child can get a workflow slot, and every parent blocks until its wait times
out. The same happens once `activity-concurrency` such activities are
waiting, because the children's activities cannot get a slot either. For
unlisted queues both limits are 20 by default. Every `@DurableWorkflow` that
names no `taskQueue` is on `default`, so a parent and a child of different
types share these pools unless one of them names its own queue. The test kit
(`TestWorkflowEnvironment`) is unbounded and will not show this. Remedies:

- Put the child workflow type on a task queue of its own
  (`@DurableWorkflow(taskQueue = ...)`). It then gets its own pools.
- Or start the child without waiting for it in the activity, have the child
  signal the parent when it finishes, and have the parent `awaitSignal` that
  signal. A parked run gives its slot back.
- Or raise the limits for that queue, or set both defaults to `0` to leave
  unlisted queues unbounded.

**Example:**

```yaml
maestro:
  worker:
    default-concurrency: 20          # every queue not listed below (the default)
    default-activity-concurrency: 20
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

A recovery pass reads only the active instances (`RUNNING`, `WAITING_SIGNAL`,
`WAITING_TIMER`, `COMPENSATING`) whose `service_name` is this node's
`maestro.service-name`, in pages of 200 ordered by `(started_at, id)`; a page
shorter than 200 ends the pass. Per page it skips instances already running on
this node, then makes one read of `maestro_distributed_lock` and skips the
instances whose lock another node holds; only the rest cost a lock
acquisition. That read is advisory — the acquisition stays the authority — so
if it fails, every remaining instance is tried as before. Another service's
instances are never read or adopted, even when services share one table set:
**every node of a service must share one `maestro.service-name`** (see
[Root Properties](#root-properties)).

| Property                         | Type       | Default | Description                                                                 |
|----------------------------------|------------|---------|-----------------------------------------------------------------------------|
| `maestro.recovery.enabled`       | `boolean`  | `true`  | Whether the periodic recovery poller runs.                                   |
| `maestro.recovery.poll-interval` | `Duration` | `60s`   | Interval between recovery cycles. Together with the 30s instance-lock TTL, this bounds how long an orphaned workflow waits before another node adopts it. |

Migration `V101__recovery_scan_index.sql` adds the partial index the paged scan
reads (`idx_wf_instance_recovery` on `(service_name, started_at, id)` over the
four active statuses). It takes V101, above V100, under the
[numbering rule](#schema-management). The **query** is correct without the
index, only slower. The **migration record** is a different matter: under
`validate` a node starts only once V101 is recorded in
`maestro_schema_history`. Flyway applies V101 in a transaction, so it is a
plain `CREATE INDEX IF NOT EXISTS`, which holds a `SHARE` lock on
`maestro_workflow_instance` while it builds. Writes wait; reads do not. The
index to pre-create by hand, under exactly this name:

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_wf_instance_recovery
    ON maestro_workflow_instance (service_name, started_at, id)
    WHERE status IN ('RUNNING', 'WAITING_SIGNAL', 'WAITING_TIMER', 'COMPENSATING');
```

By `maestro.store.schema.mode`:

- **`flyway`** (default): nothing to do. The first node of this build applies
  and records V101. On a large table you can run the line above first, and
  V101 then finds the index and takes no long `SHARE` lock.
- **`none`**: run the line above yourself, before or after deploying. The
  build works without the index, and nothing reads the history.
- **`validate`**: V101 must be **recorded** in `maestro_schema_history` before
  a node of this build starts, or the node fails with `Maestro schema is
  behind: pending migrations [101]`. Creating the index by hand does not
  record it, and a hand-inserted history row fails `validate`'s checksum
  check. Run the line above if the table is large, then record V101 through
  the Flyway CLI route in [Schema management](#schema-management), as the
  role that owns the tables. If the application's role owns them, one start
  in `flyway` mode records it too: one node, or a one-off job calling `new
  MaestroSchemaInitializer(dataSource, SchemaMode.FLYWAY,
  classLoader).initialize()`, which finds the index and does nothing else.
  Only then roll the rest of the fleet on `validate`.

**Rolling deploy and rollback.** V101 sorts above V100, so every build whose
newest migration is V100 (every build since PR #36) treats it as a future
migration and ignores it: V101 never stops such a node from starting. A node
on main @ a27a275, or on any build since e91bd9d (PR #43), therefore starts in
`flyway` and `validate` mode against a database that recorded V101, so a pod
restarted mid-rollout and a rollback both work. An unreleased build older
than e91bd9d can still fail, on whichever of V6–V9 it lacks, not on V101 (see
[Schema management](#schema-management)).

---

## Replay Configuration

Properties under `maestro.replay.*` control what a recovering node checks when
it replays a workflow against its recorded history.

| Property                            | Type      | Default | Description |
|-------------------------------------|-----------|---------|-------------|
| `maestro.replay.verify-arguments`   | `boolean` | `true`  | Whether a replayed activity call compares the SHA-256 fingerprint of its arguments with the one the recorded outcome carries (`maestro_workflow_event.args_hash`, V7) and stands the run down as `nondeterministic_history` when they differ — the activity is being asked a different question than the recorded run asked (a `HashMap`'s iteration order, the clock, a per-node config value, or an ungated code change; see [Operations §10.7](operations.md#107-replay-drift-nondeterministic_history)). A missing fingerprint on either side is never a mismatch, so a rolling deploy from a build without the column is safe. Set to `false` only to ride out a known-benign drift while it is fixed: live outcomes still record their fingerprint, so re-enabling covers the rows written meanwhile. The engine logs a WARN at startup while it is off. |

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
from Maestro's `DataSource` for the application's lifetime** (the application's
own, or the `@MaestroDataSource` one — see
[A dedicated connection pool](#a-dedicated-connection-pool)), and each `NOTIFY`
borrows another briefly — budget for it (effective pool capacity is one less; a configured Hikari
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

## Retention Configuration

Finished workflows are not kept forever. The **retention sweeper** deletes
workflows that reached a terminal status longer ago than that status's
retention — the instance row *and its whole history*: events, signals (consumed
or not) and timers, so the four `maestro_*` tables stop growing without bound.
One node per service sweeps at a time, elected on
`maestro:leader:retention:{service}` (15 s TTL, re-checked before every batch);
each batch is one transaction, children deleted before the parent. See
[Operations § Retention and table growth](operations.md#11-retention-and-table-growth)
for what a purge means for the dashboard, for workflow-ID reuse and for late
signals.

| Property                                 | Type       | Default | Description                                                                 |
|------------------------------------------|------------|---------|-----------------------------------------------------------------------------|
| `maestro.retention.enabled`              | `boolean`  | `true`  | Whether the sweeper runs at all. `false` keeps every finished workflow forever (the pre-retention behaviour). |
| `maestro.retention.interval`             | `Duration` | `1h`    | Time between sweeps. The first sweep runs at startup.                        |
| `maestro.retention.completed-retention`  | `Duration` | `30d`   | How long a `COMPLETED` workflow is kept after it completed.                  |
| `maestro.retention.terminated-retention` | `Duration` | `30d`   | How long a `TERMINATED` workflow is kept after it was terminated. Its leftover `PENDING` timers go with it. |
| `maestro.retention.failed-retention`     | `Duration` | `90d`   | How long a `FAILED` workflow is kept after it failed. Longer by default because an operator may still retry it; once purged, a retry reports the workflow not found. |
| `maestro.retention.batch-size`           | `int`      | `500`   | Instances deleted per transaction (plus all of their child rows). 1 to 10 000; each candidate is one JDBC bind parameter in the batch's deletes and pgjdbc allows 32 767 per statement, so a larger value is rejected at startup rather than failing every batch. |
| `maestro.retention.max-batches-per-sweep`| `int`      | `100`   | Batches per status per sweep. Bounds one sweep's work on a large backlog: at the defaults, at most 50 000 instances per status per hour; the rest waits for the next interval. |

`maestro.signal.terminal-retention` (above) is independent and narrower: it
purges only the *unconsumed signals* of `COMPLETED`/`TERMINATED` workflows,
earlier (7 days), on every node, and leaves everything else. The retention
sweeper later takes the whole workflow.

Migration `V8__retention_indexes.sql` adds the two indexes the sweeper's
queries need (`idx_wf_instance_terminal` on `(status, completed_at) WHERE
completed_at IS NOT NULL`, `idx_wf_signal_instance` on
`maestro_workflow_signal(workflow_instance_id)`). Flyway applies it in a
transaction, so it is a plain `CREATE INDEX IF NOT EXISTS`, which holds a
`SHARE` lock on each table while the index builds — writes wait, reads do not.
On a large table, create both indexes by hand with `CREATE INDEX CONCURRENTLY`
under the same names *before* deploying this build; the migration then finds
them and does nothing.

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
    default-concurrency: 20
    default-activity-concurrency: 20
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
  replay:
    verify-arguments: true
  admin:
    events:
      enabled: true
      topic: maestro.admin.events
  retention:
    enabled: true
    interval: 1h
    completed-retention: 30d
    terminated-retention: 30d
    failed-retention: 90d
    batch-size: 500
    max-batches-per-sweep: 100

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
connections; the lock and the signal wake use the same `DataSource` as the
store — the application's, unless you give Maestro its own
([A dedicated connection pool](#a-dedicated-connection-pool)).

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

Three further settings are **defaults, not invariants** — Maestro supplies them
only when the property is unset, and an explicit `spring.kafka.*` value wins:

| Default                                 | Maestro's value | Why                                                                                                    |
|-----------------------------------------|-----------------|--------------------------------------------------------------------------------------------------------|
| Consumer `auto.offset.reset`            | `earliest`      | A new consumer group must not skip signals published before it first joined.                            |
| Consumer `allow.auto.create.topics`     | `false`         | "Never auto-create Kafka topics", in code — see [Kafka Topic Verification](#kafka-topic-verification). |
| Producer `max.block.ms`                 | `10000`         | Bounds how long `send()` blocks fetching metadata for a missing topic; Kafka's own default is 60 s.    |

Override with `spring.kafka.consumer.auto-offset-reset`,
`spring.kafka.consumer.properties.allow.auto.create.topics` and
`spring.kafka.producer.properties.max.block.ms` respectively.

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

Create topics before starting your service — with the default
`maestro.messaging.topics.verify-on-startup=fail` the service refuses to start
otherwise, and prints exactly these commands for whatever is missing (see
[Kafka Topic Verification](#kafka-topic-verification)). For a service named
`order-service` with one `@MaestroSignalListener(topic = "payments.results")`,
redelivery on (default) and lifecycle events on (default):

```bash
# The engine's inbound signal topic and its dead-letter companion
kafka-topics.sh --create --topic maestro.signals.order-service \
  --partitions 6 --replication-factor 3 \
  --bootstrap-server localhost:9092
kafka-topics.sh --create --topic maestro.signals.order-service.DLT \
  --partitions 6 --replication-factor 3 \
  --bootstrap-server localhost:9092

# Every @MaestroSignalListener topic and its dead-letter companion
kafka-topics.sh --create --topic payments.results.DLT \
  --partitions 6 --replication-factor 3 \
  --bootstrap-server localhost:9092
# (payments.results itself is usually created by the service that publishes it)

# Lifecycle events for the admin dashboard (skip with maestro.admin.events.enabled=false)
kafka-topics.sh --create --topic maestro.admin.events \
  --partitions 3 --replication-factor 3 \
  --bootstrap-server localhost:9092
```

The `.DLT` companions are plain topics; use the same partition count as their
source so a dead-lettered record keeps its partition. With
`maestro.messaging.redelivery.enabled=false` none of them is needed.

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
| `maestro:leader:retention:{service}`          | `maestro_leader_election`   | Retention sweeper leader election | 15s              |

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

The leader election keys ensure that only one node in the cluster runs the timer
poller, and only one runs the retention sweeper's purge batches. They use a
shorter TTL (15 seconds) so that leadership transfers quickly if the current
leader goes down; the retention sweeper re-elects before every batch, which is
also what renews its claim.

---

## See Also

- [Getting Started](getting-started.md) -- Set up Maestro in a new Spring Boot project
- [Concepts](concepts.md) -- Workflows, activities, signals, timers, and the memoization model
- [Cross-Service Patterns](cross-service.md) -- Orchestration within, choreography between services
- [Observability](observability.md) -- The meter catalog, span topology, and the Kafka trace-propagation contract
- [Operations](operations.md) -- Multi-instance behaviour, and the versioning / mixed-version playbook
