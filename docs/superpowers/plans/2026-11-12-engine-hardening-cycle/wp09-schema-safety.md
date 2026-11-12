# WP9 Schema Safety Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pin Maestro's schema history so that no later change can silently break a database, or the workflow history in it, written by `main` @ `cbf5a2a`. The work: an accurate `outOfOrder` rationale plus a test of the retired V200–V202 band; frozen copies of the shipped migrations; a seeded-history upgrade-and-replay fixture captured at `cbf5a2a`; and production guidance for `maestro.store.schema.mode` and DDL privileges.

**Architecture:** Frozen artefacts are produced once, by one script (`scripts/capture-upgrade-fixture.sh`), from a git ref, never from the working tree. Migration SQL is extracted with `git show <ref>:<path>`. The seeded database is a `pg_dump` (schema, Flyway history and rows) of a Postgres that `cbf5a2a`'s own build migrated and wrote to, by running frozen fixture workflows in a detached `git worktree` of `cbf5a2a`. The script commits nothing. Tests in `maestro-store-postgres` compare shipped SQL with the frozen copies and replay the retired band. Tests in `maestro-integration-tests` load the dump, run this build's `MaestroSchemaInitializer`, then drive this build's engine over the seeded rows. WP1 and WP3 reuse the `SeededHistory` fixture API.

**Tech Stack:** Java 25, Flyway 11.14.1, PostgreSQL 16 (`postgres:16-alpine`, as every suite), Testcontainers 2.0, JUnit 6 / AssertJ / Awaitility, Jackson 3 (`tools.jackson`), bash.

**Spec:** `docs/superpowers/plans/2026-11-12-engine-hardening-cycle/00-spec.md` (WP9 row, §3 last bullet, §2 anti-goals, §4 Lane B order). **Evidence:** `evidence/audit-C-host-integration-followups.md` §3.11d, §4.2-4, §4.3.

## Added surface

No production API, config property, SPI method or migration is added. The only production-code change is comment text in `MaestroSchemaInitializer`.

Test resources (frozen; never edited, never regenerated; see "Regeneration rules"):

| Path | Content | Produced by |
|---|---|---|
| `maestro-store-postgres/src/test/resources/upgrade/cbf5a2a/{store,lock}/*.sql` + `SOURCE` | every migration shipped at `cbf5a2a` (store V1–V9, V101; lock V100) | `scripts/capture-upgrade-fixture.sh migrations cbf5a2a cbf5a2a` |
| `maestro-store-postgres/src/test/resources/upgrade/c01d498/{store,lock,messaging}/*.sql` + `SOURCE` | the last set that shipped the Postgres transport: store V1–V5, lock V100, messaging V200–V202 | `… migrations c01d498 c01d498` |
| `maestro-integration-tests/src/test/resources/upgrade/cbf5a2a/seeded-history.sql` | `pg_dump` of every `maestro_*` table (DDL, `maestro_schema_history`, rows) written by `cbf5a2a` | `… history cbf5a2a cbf5a2a` |
| `maestro-integration-tests/src/test/resources/upgrade/cbf5a2a/capture.properties` | capture identity: full ref, time, server version, SHA-256 of the frozen workflow source | same run |

Test sources:

- `io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows`. This is **frozen** and pinned by SHA-256 in `capture.properties`. It holds the fixture workflows, activity interfaces, records, the one activity implementation, `Invocations`, `StepGate` and the `Seeds` catalogue.
- `io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryCapture`. The capture driver. It only runs when `MAESTRO_UPGRADE_CAPTURE_OUT` is set.
- **Fixture API that WP1 and WP3 call:** `io.b2mash.maestro.integration.upgrade.SeededHistory`:
  ```java
  public final class SeededHistory {
      public static SeededHistory load();                         // fresh database, dump loaded, schema exactly as cbf5a2a left it
      public SeededHistory upgrade(SchemaMode mode);              // this build's MaestroSchemaInitializer over it
      public PGSimpleDataSource dataSource();
      public PostgresWorkflowStore store();
      public PayloadSerializer serializer();
      public Properties manifest();                               // capture.properties
      public List<WorkflowEvent> events(String workflowId);
      public WorkflowStatus awaitStatusOrLast(String workflowId, WorkflowStatus expected, Duration within);
      public MaestroEngineHarness node(String serviceName, RecordingObserver observer, Invocations invocations);
  }
  ```
- Tests: `ShippedMigrationsFrozenTest` and `FrozenMigrations` (helper) in `maestro-store-postgres`; new methods in `MaestroSchemaInitializerTest`; `SeededHistoryUpgradeIT` in `maestro-integration-tests`.

Tooling and docs: `scripts/capture-upgrade-fixture.sh` (new top-level `scripts/`), `docs/configuration.md` § Schema management, the `CLAUDE.md` "Database Tables" sentence on `outOfOrder`, and `RELEASING.md` § Upgrade fixtures.

## Global Constraints

- Branch `test/schema-upgrade-safety`, cut from `origin/main`, in a dedicated worktree (see "Before you start"). Commit at every coherent checkpoint. Never `git stash`. Use `./gradlew --no-watch-fs`. Never run two Gradle invocations on one module in one tree.
- The capture ref is exactly `cbf5a2a06acfba9b0f7ae23f37b3f1f1713928f7`, even if `origin/main` has moved on (for example after WP0). The retired-band ref is exactly `c01d498309047b2da5adc453f73a29055f431add`, the parent of `2729a57`, which deleted the transport.
- No behaviour change in production code. `MaestroSchemaInitializer` changes in comments only.
- No existing test is edited. Every change in this plan adds tests (spec anti-goal 4).
- The Postgres image is `postgres:16-alpine`, the same tag every suite uses, for both capture and replay.
- Seeded data is synthetic: no hostnames, user names or real identifiers in payloads.
- Jackson 3 (`tools.jackson`) only. JSpecify `@Nullable`. No Lombok. Every new public test-fixture type gets a Javadoc that says what it pins and why.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- A mutation check reverts its mutation in the same step, and `git status` must show only the intended files before every commit.

## Review Focus

1. **The dump was captured from the wrong tree** (the script was run against HEAD instead of `cbf5a2a`, or against a dirty checkout). A reviewer would expect the fixture to say so. Pinned by `theDumpWasCapturedAtCbf5a2a`: `capture.properties` `ref` must equal the full SHA, and the frozen-source hash must match (Task 3).
2. **A Windows checkout converts line endings** in the frozen or shipped SQL. Flyway ignores line endings, so the byte comparison must too, without hiding a real edit. Pinned by `lineEndingsDoNotCountButContentDoes` (Task 1).
3. **A replaying node whose JVM time zone is not UTC.** History holds `Instant` and `OffsetDateTime` payloads and recorded `currentTime()` values. Pinned by running the replay suite under `TZ=Pacific/Chatham` (Task 4, step 6).
4. **An older local Postgres 16 patch** whose `psql` does not understand the `\restrict`/`\unrestrict` meta-commands that recent `pg_dump` builds emit. The capture strips them. Pinned by `theDumpWasCapturedAtCbf5a2a`, which asserts no line of the dump starts with a backslash (Task 3).
5. **A future migration that is valid on an empty schema but fails on existing rows** (for example `ADD COLUMN … NOT NULL` with no default). Every other suite starts empty, so this fixture is the only one that can catch it. Shown by the Task 3 mutation (a probe `V102`) failing `theSeededDatabaseUpgradesUnderThisBuild`.

## Regeneration rules (read before touching any frozen file)

- `upgrade/cbf5a2a/**` and `upgrade/c01d498/**` record what those commits shipped and wrote. They are evidence about the past. Regenerating them at a later commit would turn them into "what the newer build writes", and the guard would then pass against exactly the regression it exists to catch.
- `scripts/capture-upgrade-fixture.sh` refuses to write into an existing fixture directory. Re-capturing `cbf5a2a` happens only if the committed fixture itself is defective. It needs the maintainer's explicit approval, is run at `cbf5a2a` with the script, goes in its own commit, and must produce a diff a reviewer can read (for the dump: only UUIDs and timestamps may differ).
- `SeededHistoryWorkflows.java` is frozen once the dump is committed (Task 3, step 9). It defines how the seeded history was produced, so changing it voids the fixture. `theDumpWasCapturedAtCbf5a2a` fails on any byte change.
- A later release adds a *new* fixture: a new directory, a new frozen package, and a new `SeededHistory` factory. Existing fixtures are never replaced (`RELEASING.md` § Upgrade fixtures, Task 5).

## Before you start

```bash
git -C /Users/rakheendama/Projects/2026/maestro fetch origin
git -C /Users/rakheendama/Projects/2026/maestro worktree add -b test/schema-upgrade-safety \
    /Users/rakheendama/Projects/2026/maestro/.claude/worktrees/wp9-schema-safety origin/main
WT=/Users/rakheendama/Projects/2026/maestro/.claude/worktrees/wp9-schema-safety
git -C "$WT" status && git -C /Users/rakheendama/Projects/2026/maestro status   # prove which tree you are in
```

Run every command from `$WT`. Never `cd` into the main checkout (lessons, 2026-07-31). All paths below are relative to `$WT`.

## File structure

| File | Responsibility |
|---|---|
| `scripts/capture-upgrade-fixture.sh` | The only producer of frozen artefacts. `migrations` mode: `git show` every migration at a ref and write `SOURCE`. `history` mode: worktree at the ref, run the capture driver, copy out the dump and the manifest. |
| `maestro-store-postgres/src/test/java/.../schema/FrozenMigrations.java` | Reads a frozen migration set (`SOURCE`, files, Flyway locations); builds an "older build" Flyway; does line-ending normalisation and SHA-256. |
| `maestro-store-postgres/src/test/java/.../schema/ShippedMigrationsFrozenTest.java` | No database. Every migration shipped at `cbf5a2a` is still shipped, byte for byte (modulo line endings). |
| `maestro-store-postgres/src/test/java/.../schema/MaestroSchemaInitializerTest.java` | Gains: frozen-set validate (b2), the two retired-band tests (a1, a2), the DML-only role test (Task 5). |
| `maestro-store-postgres/src/main/java/.../schema/MaestroSchemaInitializer.java` | Comment-only rewrite of the `outOfOrder` rationale. |
| `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/upgrade/cbf5a2a/SeededHistoryWorkflows.java` | Frozen fixture: workflows, activities, records, `Seeds`, `Invocations`, `StepGate`. |
| `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/upgrade/cbf5a2a/SeededHistoryCapture.java` | Capture driver (gated by an environment variable). |
| `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/upgrade/SeededHistory.java` | Fixture API: load, upgrade, build a node. |
| `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/upgrade/SeededHistoryUpgradeIT.java` | WP9's assertions over the seeded history: schema half (Task 3), replay half (Task 4). |
| `docs/configuration.md`, `CLAUDE.md`, `RELEASING.md` | `outOfOrder` wording (Task 2); production guidance and the fixture procedure (Task 5). |

---

### Task 1: Capture script and the frozen `cbf5a2a` migration set (b)

**Files:**
- Create: `scripts/capture-upgrade-fixture.sh` (mode `migrations` only in this task; `history` is added in Task 3)
- Create: `maestro-store-postgres/src/test/resources/upgrade/cbf5a2a/` (produced by the script)
- Create: `maestro-store-postgres/src/test/java/io/b2mash/maestro/store/postgres/schema/FrozenMigrations.java`
- Create: `maestro-store-postgres/src/test/java/io/b2mash/maestro/store/postgres/schema/ShippedMigrationsFrozenTest.java`
- Modify: `maestro-store-postgres/src/test/java/io/b2mash/maestro/store/postgres/schema/MaestroSchemaInitializerTest.java` (add one test and one helper; existing tests untouched)

**Interfaces:**
- Produces (package-private, test source, used by Tasks 2 and 5):
  ```java
  final class FrozenMigrations {
      static FrozenMigrations named(String name);   // loads classpath:upgrade/<name>/SOURCE
      String ref();                                 // full 40-char SHA from SOURCE
      List<String> files();                         // "store/V1__create_maestro_schema.sql", … sorted by version number
      String recordedSha256(String file);           // from SOURCE
      byte[] read(String file);                     // the frozen copy's bytes
      String[] flywayLocations();                   // "filesystem:<abs path>/<component>" for each component directory present
      Flyway olderBuild(DataSource ds, boolean outOfOrder); // table maestro_schema_history, baselineOnMigrate(true), baselineVersion "0", flywayLocations()
      static byte[] lf(byte[] raw);                 // CRLF -> LF, nothing else
      static String sha256(byte[] bytes);           // lowercase hex
  }
  ```
- Produces `scripts/capture-upgrade-fixture.sh migrations <ref> <name>`. It writes `maestro-store-postgres/src/test/resources/upgrade/<name>/<component>/<file>` for every path matching `*/src/main/resources/db/maestro/(store|lock|messaging)/V<n>__*.sql` at `<ref>` (from `git ls-tree -r --name-only <ref>`), plus `SOURCE`. `SOURCE` is in Java `Properties` format: a `#` header naming the script and the regeneration rule, `ref=<git rev-parse <ref>>`, `captured=<UTC ISO-8601>`, and one `<component>/<file>=<sha256 of the git blob bytes>` per file. The script exits non-zero without writing anything if the target directory exists, if `<ref>` does not resolve, or if it is not run from the repository root (`git rev-parse --show-toplevel`). It uses `sha256sum`, falling back to `shasum -a 256`. Start it with `set -euo pipefail`.

- [ ] **Step 1: Write the script (`migrations` mode) and run it.**

Run: `scripts/capture-upgrade-fixture.sh migrations cbf5a2a cbf5a2a`
Expected: 11 files: `store/V1…V9`, `store/V101__recovery_scan_index.sql`, `lock/V100__maestro_lock_postgres.sql`. Check them against git, then check that re-running refuses:

```bash
for f in $(cd maestro-store-postgres/src/test/resources/upgrade/cbf5a2a && ls */*.sql); do
  git show cbf5a2a:maestro-store-postgres/src/main/resources/db/maestro/$f | cmp - maestro-store-postgres/src/test/resources/upgrade/cbf5a2a/$f || echo "MISMATCH $f"; done
scripts/capture-upgrade-fixture.sh migrations cbf5a2a cbf5a2a; echo "exit=$?"   # expect non-zero, "already exists"
```
Expected: no `MISMATCH` lines; `exit=` non-zero.

- [ ] **Step 2: Write the failing tests** in `ShippedMigrationsFrozenTest` (`@DisplayName("Migrations shipped at cbf5a2a are frozen")`, no container):

```java
static final String CBF5A2A = "cbf5a2a06acfba9b0f7ae23f37b3f1f1713928f7";

@Test void theFrozenSetIsEverythingShippedAtCbf5a2a() {
    var frozen = FrozenMigrations.named("cbf5a2a");
    assertThat(frozen.ref()).isEqualTo(CBF5A2A);
    assertThat(frozen.files()).containsExactly(
        "store/V1__create_maestro_schema.sql", "store/V2__create_indexes.sql",
        "store/V3__timer_lookup_index.sql", "store/V4__signal_trace_context.sql",
        "store/V5__signal_received_at_default.sql", "store/V6__instance_lease_epoch.sql",
        "store/V7__workflow_event_args_hash.sql", "store/V8__retention_indexes.sql",
        "store/V9__signal_message_id.sql", "lock/V100__maestro_lock_postgres.sql",
        "store/V101__recovery_scan_index.sql");
    // each listed file exists in the frozen directory and hashes (after lf) to its SOURCE entry
}

@ParameterizedTest(name = "{0}") @MethodSource("frozenFiles")
void everyMigrationShippedAtCbf5a2aIsStillShippedUnchanged(String file) {
    // shipped = classpath resource "db/maestro/" + file; assert non-null with message
    //   file + " shipped at cbf5a2a is no longer shipped (deleted or renamed)"
    // assert sha256(lf(shipped)) == sha256(lf(frozen.read(file))) with message
    //   file + " was edited after it shipped at cbf5a2a; never edit a shipped migration, add the next version in 101-199"
}

@Test void lineEndingsDoNotCountButContentDoes() {
    assertThat(FrozenMigrations.lf("a;\r\nb;\n".getBytes(UTF_8))).isEqualTo("a;\nb;\n".getBytes(UTF_8));
    assertThat(FrozenMigrations.sha256(FrozenMigrations.lf("a;\r\nb;\n".getBytes(UTF_8))))
        .isNotEqualTo(FrozenMigrations.sha256("a;\nc;\n".getBytes(UTF_8)));
}
```

In `MaestroSchemaInitializerTest`, add (b2):

```java
@Test @DisplayName("a database migrated by exactly the set shipped at cbf5a2a validates under this build")
void aDatabaseMigratedByTheFrozenCbf5a2aSetValidatesUnderThisBuild() throws SQLException {
    FrozenMigrations.named("cbf5a2a").olderBuild(ds, true).migrate();   // cbf5a2a's initializer had outOfOrder(true)
    assertThat(versions(HISTORY_TABLE)).containsExactly("1","2","3","4","5","6","7","8","9","100","101");
    assertThatCode(() -> new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, cl).initialize()).doesNotThrowAnyException();
    assertThatCode(() -> new MaestroSchemaInitializer(ds, SchemaMode.VALIDATE, cl).initialize()).doesNotThrowAnyException();
}
```

- [ ] **Step 3: Run the tests to verify they fail** (`FrozenMigrations` not yet written).

Run: `./gradlew --no-watch-fs :maestro-store-postgres:test --tests '*ShippedMigrationsFrozenTest' --tests '*MaestroSchemaInitializerTest'`
Expected: compilation FAIL, `cannot find symbol: class FrozenMigrations`.

- [ ] **Step 4: Implement `FrozenMigrations`.** Read `SOURCE` with `Properties`. Ignore non-file keys (`ref`, `captured`). Order `files()` by the integer after `V`. Resolve directories with `Path.of(cl.getResource("upgrade/<name>/<component>").toURI())`, which works because test resources are a directory on the classpath. `lf` replaces each `\r\n` pair with `\n` and leaves a lone `\r` alone.

- [ ] **Step 5: Run the tests to verify they pass.**

Run: `./gradlew --no-watch-fs :maestro-store-postgres:test --tests '*ShippedMigrationsFrozenTest' --tests '*MaestroSchemaInitializerTest'`
Expected: PASS, all pre-existing `MaestroSchemaInitializerTest` methods included.

- [ ] **Step 6: Show each new test failing against the defect it guards.** Run the same command after each mutation and revert after each:
  1. Append one space to a comment line of `src/main/resources/db/maestro/store/V7__workflow_event_args_hash.sql`. Expected: `everyMigrationShippedAtCbf5a2aIsStillShippedUnchanged[store/V7…]` FAILS with "was edited after it shipped", and `aDatabaseMigratedByTheFrozenCbf5a2aSetValidatesUnderThisBuild` FAILS with Flyway's `checksum mismatch for migration version 7`.
  2. `git mv` `V8__retention_indexes.sql` to `V8__retention_index.sql`. Expected: `[store/V8…]` FAILS with "no longer shipped".
  3. Edit one byte of the frozen `upgrade/cbf5a2a/store/V3__timer_lookup_index.sql`. Expected: `theFrozenSetIsEverythingShippedAtCbf5a2a` FAILS on the SOURCE hash.
  4. Make `lf` return its input unchanged. Expected: `lineEndingsDoNotCountButContentDoes` FAILS.

  Record each FAIL line in the commit message body. Then confirm `git status` shows no `src/main` change.

- [ ] **Step 7: Commit**

```bash
git add scripts/capture-upgrade-fixture.sh maestro-store-postgres/src/test/resources/upgrade/cbf5a2a \
  maestro-store-postgres/src/test/java/io/b2mash/maestro/store/postgres/schema/FrozenMigrations.java \
  maestro-store-postgres/src/test/java/io/b2mash/maestro/store/postgres/schema/ShippedMigrationsFrozenTest.java \
  maestro-store-postgres/src/test/java/io/b2mash/maestro/store/postgres/schema/MaestroSchemaInitializerTest.java
git commit -m "test(schema): freeze the migrations shipped at cbf5a2a and fail on any edit" -m "<mutation results>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `outOfOrder` is load-bearing for the retired V200–V202 band (a)

**Files:**
- Create: `maestro-store-postgres/src/test/resources/upgrade/c01d498/` (produced by the script)
- Modify: `maestro-store-postgres/src/test/java/io/b2mash/maestro/store/postgres/schema/MaestroSchemaInitializerTest.java` (add two tests and one helper)
- Modify: `maestro-store-postgres/src/main/java/io/b2mash/maestro/store/postgres/schema/MaestroSchemaInitializer.java:37-51,63-69,149-158` (comments only)
- Modify: `docs/configuration.md:63-67`, `CLAUDE.md` (the last sentence of the "Database Tables" paragraph)

**Interfaces:**
- Consumes: `FrozenMigrations.named`, `olderBuild` (Task 1).
- Produces: a private helper in `MaestroSchemaInitializerTest`, `ClassLoader withFutureMigration(Path dir, String fileName, String sql)`. It returns a `URLClassLoader` (parent: the test class loader) over `dir`. In `dir` it writes `META-INF/maestro/schema-locations` holding the single line `filesystem:<dir>/future`, and writes `<dir>/future/<fileName>` holding `sql`.

Why these histories (verified at planning time): `c01d498` is the last commit that shipped the Postgres transport, and it shipped store V1–V5 only (V6 landed in `544cb2c`, after the transport was deleted in `2729a57`). Its initializer had no `outOfOrder`. The first initializer with `outOfOrder` is `dff3a99` (2026-11-11). A database that ran the transport therefore holds V1–V5, V100 and V200–V202. On such a database, V6–V9, V101 and every future V102–V199 sort below V202, and Flyway counts unresolved "future" rows as the highest applied version. V100 at `c01d498` is byte-identical to V100 at `cbf5a2a`.

- [ ] **Step 1: Freeze the retired set.**

Run: `scripts/capture-upgrade-fixture.sh migrations c01d498 c01d498`
Expected: `store/V1…V5`, `lock/V100__maestro_lock_postgres.sql`, `messaging/V200__maestro_messaging_postgres.sql`, `messaging/V201__redelivery_dead_letter.sql`, `messaging/V202__drop_task_and_lifecycle_queues.sql`, and `SOURCE` with `ref=c01d498309047b2da5adc453f73a29055f431add`.

- [ ] **Step 2: Write the failing tests** in `MaestroSchemaInitializerTest`:

```java
/** A database that ran the retired Postgres messaging transport, exactly as main @ c01d498 left it. */
@Test @DisplayName("a database holding the retired V200–V202 band upgrades to this build and validates")
void aDatabaseHoldingTheRetiredMessagingBandUpgradesToThisBuild() throws SQLException {
    FrozenMigrations.named("c01d498").olderBuild(ds, false).migrate();    // c01d498's initializer: no outOfOrder
    assertThat(versions(HISTORY_TABLE)).containsExactly("1","2","3","4","5","100","200","201","202");

    new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, cl).initialize();

    assertThat(versions(HISTORY_TABLE)).containsExactly(
        "1","2","3","4","5","100","200","201","202","6","7","8","9","101");
    assertThat(tables()).contains("maestro_signal_queue").doesNotContain("maestro_task_queue");   // orphan kept, V202's drop kept
    assertThatCode(() -> new MaestroSchemaInitializer(ds, SchemaMode.VALIDATE, cl).initialize()).doesNotThrowAnyException();
}

/** Every future V102–V199 sorts below V202 on such a database; it applies only out of order. */
@Test @DisplayName("a migration added after V101 still applies on a database holding the retired band")
void aMigrationAddedAfterV101AppliesOnADatabaseHoldingTheRetiredBand(@TempDir Path dir) throws Exception {
    FrozenMigrations.named("c01d498").olderBuild(ds, false).migrate();
    new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, cl).initialize();
    var future = withFutureMigration(dir, "V102__future_probe.sql", "CREATE TABLE maestro_future_probe (id INT);");

    new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, future).initialize();

    assertThat(versions(HISTORY_TABLE)).endsWith("101", "102");
    assertThat(tables()).contains("maestro_future_probe");
    assertThatCode(() -> new MaestroSchemaInitializer(ds, SchemaMode.VALIDATE, future).initialize()).doesNotThrowAnyException();
}
```

- [ ] **Step 3: Run them. They should pass on today's code; that is expected, because this task pins existing behaviour and changes none.** The red run is the mutation in step 4.

Run: `./gradlew --no-watch-fs :maestro-store-postgres:test --tests '*MaestroSchemaInitializerTest'`
Expected: PASS.

- [ ] **Step 4: Show both tests failing without `outOfOrder`.** Change `.outOfOrder(true)` to `.outOfOrder(false)` in `MaestroSchemaInitializer` and run the same command.
Expected: `aDatabaseHoldingTheRetiredMessagingBandUpgradesToThisBuild` FAILS with `Detected resolved migration not applied to database: 6` (the Issue 35 reason and the band reason both apply). `aMigrationAddedAfterV101AppliesOnADatabaseHoldingTheRetiredBand` FAILS on `102`, a version above V100 and V101, so only the V200–V202 rows can explain it. Revert. Paste both FAIL lines into the commit body. If either test passes under the mutation, stop and report: the audit's Flyway trace would then be wrong.

- [ ] **Step 5: Rewrite the comments in `MaestroSchemaInitializer` (no code change).**

The inline comment above `.outOfOrder(true)` becomes:

```java
                // outOfOrder is load-bearing; never remove it. Flyway refuses a
                // resolved migration that sorts below the highest version already
                // recorded ("Detected resolved migration not applied to
                // database"), and that highest version includes rows this build
                // does not ship. Two real histories hit this. (1) Issue 35: a
                // database first migrated before V6-V9 shipped recorded V100
                // first, so V6-V9 sort below it. (2) A database that ran the
                // retired Postgres messaging transport recorded V200-V202, so V6-V9,
                // V101 and every future V102-V199 sort below V202. Store, lock and
                // the retired band never depend on each other, so out-of-order
                // application is the intended semantics. It also makes such a
                // migration "pending" to info(), so VALIDATE names it below.
                // MaestroSchemaInitializerTest pins both histories.
```

In the class Javadoc, rename "Out-of-order application" and say the same in prose. Replace the sentence "Under the numbering rule above no new migration sorts below an applied one, so the option matters only for that history." with the V200–V202 reason. In the band paragraph (lines 48–51), replace "(Flyway ignores them as future versions)" with "(Flyway ignores them as future versions in validation, but they still count as the highest applied version, so every later migration applies there only out of order; see below)".

- [ ] **Step 6: Update the docs to match.**
  - `docs/configuration.md:65`: after "…keep passing;" add that those rows still count as the highest applied version, so V6–V9, V101 and every later migration apply on such a database only because the initializer runs Flyway with `outOfOrder`, and that anyone applying Maestro's SQL with the Flyway CLI on such a database must pass `-outOfOrder=true`.
  - `docs/configuration.md:67`: retitle "**Out-of-order application.**". Open the paragraph with both histories, then keep the existing V6–V9 text unchanged.
  - `docs/configuration.md:63`: append "…and `ShippedMigrationsFrozenTest` fails if any migration shipped at `cbf5a2a` is edited, renamed or deleted."
  - `CLAUDE.md` "Database Tables": replace "Flyway runs with `outOfOrder(true)` for databases that recorded V100 before V6–V9 (Issue 35):" with "Flyway runs with `outOfOrder(true)`, which is load-bearing: databases that recorded V100 before V6–V9 (Issue 35), and databases holding the retired V200–V202 rows, where V101 and every future migration sort below V202, apply them only out of order. Shipped migration files are frozen (`ShippedMigrationsFrozenTest`):"

- [ ] **Step 7: Run the module's suite.**

Run: `./gradlew --no-watch-fs :maestro-store-postgres:test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add maestro-store-postgres/src/test/resources/upgrade/c01d498 \
  maestro-store-postgres/src/test/java/io/b2mash/maestro/store/postgres/schema/MaestroSchemaInitializerTest.java \
  maestro-store-postgres/src/main/java/io/b2mash/maestro/store/postgres/schema/MaestroSchemaInitializer.java \
  docs/configuration.md CLAUDE.md
git commit -m "test(schema): pin outOfOrder for databases holding the retired V200-V202 band" -m "<mutation results>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Seeded history captured at `cbf5a2a` and the schema half of the upgrade test (c)

**Files:**
- Create: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/upgrade/cbf5a2a/SeededHistoryWorkflows.java`
- Create: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/upgrade/cbf5a2a/SeededHistoryCapture.java`
- Modify: `scripts/capture-upgrade-fixture.sh` (add `history` mode)
- Create: `maestro-integration-tests/src/test/resources/upgrade/cbf5a2a/{seeded-history.sql,capture.properties}` (produced by the script)
- Create: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/upgrade/SeededHistory.java`
- Create: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/upgrade/SeededHistoryUpgradeIT.java`

**Interfaces:**
- Produces the frozen fixture, `SeededHistoryWorkflows` (a `final` holder class with nested public types). It uses only the public workflow-author API (`@DurableWorkflow`, `@WorkflowMethod`, `@ActivityStub`, `@Activity`, `@Compensate`, `@Saga`, `@RetryPolicy`, `WorkflowContext`). It must compile unchanged at `cbf5a2a` and at HEAD.
  - `interface StepGate { void enter(String step, String key); StepGate OPEN = (s, k) -> {}; }`
  - `final class Invocations { int count(String step); Map<String, Integer> snapshot(); }`. Steps are keyed `"<activity name>.<method>"`, e.g. `"seed-order.charge"`.
  - `final class SeedActivities implements SeedOrderActivities, SeedRiskActivities, SeedSagaActivities, SeedFailingActivities, SeedTypedActivities`, constructor `(Invocations, StepGate, boolean explodeFails)`. Each method records its invocation, calls `gate.enter(step, key)`, then returns a value that depends only on its arguments.
  - `final class Seeds` holds:
    - the service constants `SERVICE = "upgrade-fixture"` and `WP1_SERVICE = "upgrade-fixture-wp1"`;
    - one id constant per catalogue row: `COMPLETED_RUN`, `PARKED_TIMER`, `PARKED_EXPRESS`, `PARKED_APPROVAL`, `CRASHED`, `TERMINATED_RUN`, `SAGA_COMPENSATED`, `SAGA_COMPENSATION_FAILED`, `COMPENSATING_CRASHED`, `ACTIVITY_FAILED`, `ALERT_FAILED`, `ORPHAN_TARGET`, `WP1_DECIMAL`, `WP1_OFFSET`, `WP1_SET`;
    - `Map<String, WorkflowStatus> STATUS_AT_CAPTURE`, which lists every started id;
    - `Set<EventType> EVENT_TYPES`: every `EventType` except `UNKNOWN`, `WORKFLOW_STARTED` and `ACTIVITY_STARTED`. The engine at `cbf5a2a` writes those three only as lifecycle events, never to the store;
    - `Set<String> STEPS`: every activity step key. `Invocations.snapshot()` returns an entry for each, zero included.
  - Activity names (`@Activity(name = …)`): `seed-order`, `seed-risk`, `seed-saga`, `seed-failing` and `seed-typed`.
- Produces `SeededHistory` (the signatures under "Added surface").
  - `load()` uses one static `postgres:16-alpine` container per JVM. It copies the dump in once (`copyFileToContainer(MountableFile.forClasspathResource("upgrade/cbf5a2a/seeded-history.sql"), "/tmp/seeded-history-cbf5a2a.sql")`). Each call creates a fresh database and runs `psql -v ON_ERROR_STOP=1 -q -U test -d <db> -f /tmp/seeded-history-cbf5a2a.sql` with `execInContainer`. A non-zero exit throws with stderr in the message.
  - `node(...)` builds `MaestroEngineHarness.builder(store, JsonMapper.builder().build())` with `.serviceName(serviceName)`, `.lock(new PostgresDistributedLock(dataSource))`, `.wakeRecheckInterval(Duration.ofMillis(500))` and `.observer(observer)`. It registers every frozen workflow class and registers one `new SeedActivities(invocations, StepGate.OPEN, true)` for all five activity interfaces. Callers may re-register an activity interface to override it (WP3 will).
  - `awaitStatusOrLast` polls every 100 ms until the status equals `expected` or `within` elapses, and returns the last status it saw. An assertion on it then reads "expected COMPLETED but was WAITING_SIGNAL", not a `ConditionTimeoutException` (lessons, 2026-08-03).

**The frozen workflows** (exact step order matters; it decides which event types the history holds):

- `SeedOrderWorkflow` (`@DurableWorkflow(name = "SeedOrderWorkflow")`). Fields: `@ActivityStub(startToCloseTimeout = "PT10M") SeedOrderActivities order` and `@ActivityStub(retryPolicy = @RetryPolicy(maxAttempts = 1)) SeedRiskActivities risk`. Input `OrderRequest(String orderId, List<String> skus, long napMillis, long expressWaitMillis, long cancellableNapMillis)`. Steps:
  1. `Quote quote = order.quote(request)`. `Quote(String orderId, BigDecimal total, String currency, List<QuoteLine> lines, Instant validUntil)`, `QuoteLine(String sku, int quantity, BigDecimal price)`. Prices have two decimal places; `validUntil` is the constant `2027-01-01T00:00:00Z`.
  2. `Instant decidedAt = ctx.currentTime()`
  3. `String token = ctx.randomUUID()`
  4. `int version = ctx.version("seed-change", WorkflowContext.DEFAULT_VERSION, 1)`
  5. `List<String> reservations = ctx.parallel(` one `order.reserve(sku)` per sku `)`
  6. `boolean verified`: `try { risk.verify(orderId); verified = true } catch (ActivityExecutionException e) { verified = false }`. `verify` always throws `SeedFailure extends RuntimeException` (public `(String)` constructor).
  7. `ctx.sleep(Duration.ofMillis(napMillis))`
  8. `try { ctx.awaitSignal("express", String.class, Duration.ofMillis(expressWaitMillis)) } catch (SignalTimeoutException e) { }`
  9. `boolean napCancelled`: `try { ctx.sleep(Duration.ofMillis(cancellableNapMillis)); napCancelled = false } catch (TimerCancelledException e) { napCancelled = true }`
  10. `Approval approval = ctx.awaitSignal("approval", Approval.class, Duration.ofDays(3650))`, with `Approval(String approver, int level)`
  11. `Receipt receipt = order.charge(quote, approval)`, with `Receipt(String orderId, String chargeId, BigDecimal amount)`
  12. `String note = ctx.awaitSignal("note", String.class, Duration.ofDays(3650))`
  13. return `OrderResult(orderId, receipt, reservations, verified, napCancelled, note, token, decidedAt, version, approval.approver())`
- `SeedSagaWorkflow` (`@Saga`), with `@ActivityStub(startToCloseTimeout = "PT10M", retryPolicy = @RetryPolicy(maxAttempts = 1)) SeedSagaActivities saga`: `reserve(item)` (`@Compensate("releaseReservation")`), `charge(item)` (`@Compensate("refund")`), `ship(item)`. `ship` always throws `SeedFailure`. `refund(item)` throws when `item` starts with `"fail-refund:"`.
- `SeedFailingWorkflow`, with `@ActivityStub(retryPolicy = @RetryPolicy(maxAttempts = 2, initialInterval = "PT0.1S")) SeedFailingActivities failing`. Input `FailingRequest(String key, boolean alertBeforeRethrow)`: `try { return failing.explode(key) } catch (ActivityExecutionException e) { if (alertBeforeRethrow) failing.alert(key); throw e; }`. `explode` throws `SeedFailure` while `explodeFails` is true.
- `SeedTypedResultWorkflow`, with `@ActivityStub SeedTypedActivities typed`. Input `TypedRequest(String kind)` with kind `DECIMAL`, `OFFSET` or `SET`. It calls `exactAmount()`, which returns `new BigDecimal("12345678901234567890.123456789")`; or `localStamp()`, which returns `OffsetDateTime.of(2026, 11, 12, 10, 0, 0, 0, ZoneOffset.ofHours(2))`; or `tags()`, which returns `Set.of("alpha", "beta", "gamma", "delta")`. Then `awaitSignal("go", String.class, Duration.ofDays(3650))`, then `typed.settleAmount(…)`, `settleStamp(…)` or `settleTags(…)` with the value from the first step.

**The `Seeds` catalogue** (exact ids; this is the contract WP1 and WP3 build on, so put it in the class Javadoc as a table):

| Id | Service | Workflow and input | Status at capture | For |
|---|---|---|---|---|
| `seed-completed` | `upgrade-fixture` | Order: nap 1 s, express 1 s, cancellable 1 day, cancelled at capture | COMPLETED | every Order event type in a terminal run |
| `seed-parked-timer` | `upgrade-fixture` | Order: nap 10 min, express 1 s, cancellable 1 ms | WAITING_TIMER (step 7) | the timer fires on this build |
| `seed-parked-express` | `upgrade-fixture` | Order: nap 1 s, express 10 min, cancellable 1 ms | WAITING_SIGNAL (step 8) | the deadline fires on this build (SIGNAL_TIMEOUT) |
| `seed-parked-approval` | `upgrade-fixture` | as `seed-completed`, no approval | WAITING_SIGNAL (step 10) | replays steps 1–9, including SIGNAL_TIMEOUT and TIMER_CANCELLED |
| `seed-crashed` | `upgrade-fixture` | as `seed-completed`; approval delivered; `charge` wedged | RUNNING | crash recovery; `charge` never recorded |
| `seed-terminated` | `upgrade-fixture` | as `seed-parked-approval`, then `terminateWorkflow` | TERMINATED | never adopted |
| `seed-saga-compensated` | `upgrade-fixture` | Saga, item `"plain"` | FAILED | COMPENSATION_STARTED / STEP_COMPLETED ×2 / COMPLETED; WP3 retry |
| `seed-saga-compensation-failed` | `upgrade-fixture` | Saga, item `"fail-refund:x"` | FAILED | COMPENSATION_STEP_FAILED; WP3 |
| `seed-compensating-crashed` | `upgrade-fixture` | Saga, item `"crash-compensation"`; `releaseReservation` wedged | COMPENSATING | recovered mid-compensation (refund already recorded) |
| `seed-activity-failed` | `upgrade-fixture` | Failing, alert false | FAILED | ACTIVITY_FAILED that ended the run; WP3 (3.3) |
| `seed-alert-failed` | `upgrade-fixture` | Failing, alert true | FAILED | catch, alert, rethrow; WP3 (D10) |
| `seed-orphan-target` | `upgrade-fixture` | never started; `approval` and `note` delivered before start | (signals only) | orphan adoption by this build |
| `seed-wp1-decimal` / `-offset` / `-set` | `upgrade-fixture-wp1` | Typed, kind DECIMAL / OFFSET / SET | WAITING_SIGNAL | dormant: WP9 never adopts them; reserved for WP1 (C1, 3.8) |

Every Order instance gets its `note` signal right after it starts. The payload is `"note for " + id` and the message id is `"client:" + id + ":note"`, so each holds a signal written at `cbf5a2a` that is still unconsumed. Approvals delivered at capture carry `Approval("capture", 1)`.

- [ ] **Step 1: Write the frozen fixture and the capture driver.**

`SeededHistoryCapture` is annotated `@EnabledIfEnvironmentVariable(named = "MAESTRO_UPGRADE_CAPTURE_OUT", matches = ".+")` and has one `@Test`. It may use only APIs present at `cbf5a2a`: `MaestroEngineHarness`, `PostgresWorkflowStore`, `MaestroSchemaInitializer`, `TerminalWait`, Testcontainers and `JsonMapper`. It must not use `SeededHistory`. Order of work:
1. Start its own `postgres:16-alpine`, database `maestro_seed`, user `test`. Run `new MaestroSchemaInitializer(ds, FLYWAY, cl).initialize()` (this is `cbf5a2a`'s class when run by the script).
2. Build two nodes with no lock backend (so no lock rows exist; a crashed JVM's locks would have expired anyway): `upgrade-fixture` with `startTimerPoller(200 ms, 50)` and wake recheck 200 ms, and `upgrade-fixture-wp1`. Use the `StepGate` latches for `("seed-order.charge", "seed-crashed")` and `("seed-saga.releaseReservation", "crash-compensation")`.
3. Drive, in order: `seed-completed` (find its PENDING timer with `fire_at > now() + 1 hour` by JDBC and call `executor().cancelTimer(workflowId, timer_id, id)`; deliver approval), `seed-parked-approval`, `seed-terminated`, `seed-crashed` (wait until the gate is entered), the three sagas, the two failing runs, the orphan signals, the three WP1 runs, and **last** `seed-parked-timer` and `seed-parked-express`. After those last two are created, no timer of theirs may become due before the dump. Wait for each target status with `TerminalWait.awaitStatus` (60 s).
4. Assert before dumping. Every `Seeds.STATUS_AT_CAPTURE` entry holds. The union of event types over all instances equals `Seeds.EVENT_TYPES`. Timer statuses `PENDING`, `FIRED` and `CANCELLED` are all present. If an assertion fails, fix the fixture; nothing is committed yet.
5. Run `execInContainer("pg_dump", "-U", "test", "-d", "maestro_seed", "--no-owner", "--no-privileges", "--column-inserts", "--table=public.maestro_*")`. Fail on a non-zero exit. Drop every line that starts with `\restrict` or `\unrestrict`. Write the result to `$MAESTRO_UPGRADE_CAPTURE_OUT/seeded-history.sql`.
6. Write `capture.properties` with: `ref` (from `MAESTRO_UPGRADE_CAPTURE_REF`), `capturedAt`, `serverVersion` (`SHOW server_version`), `fixtureSource.path=src/test/java/io/b2mash/maestro/integration/upgrade/cbf5a2a/SeededHistoryWorkflows.java`, and `fixtureSource.sha256` (raw bytes of that file, resolved against the working directory, which Gradle sets to the module directory).
7. In a `finally`, release the gates, then close the nodes.

- [ ] **Step 2: Add `history` mode to the script.** `scripts/capture-upgrade-fixture.sh history <ref> <name>` does the following:
  - refuses if `maestro-integration-tests/src/test/resources/upgrade/<name>` exists;
  - `git worktree add --detach "$(mktemp -d)/capture" <ref>`, with a `trap` that runs `git worktree remove --force` on exit;
  - copies `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/upgrade/<name>/` from the invoking checkout into the same path in the worktree;
  - runs `MAESTRO_UPGRADE_CAPTURE_OUT=<tmp out> MAESTRO_UPGRADE_CAPTURE_REF=$(git rev-parse <ref>) ./gradlew --no-watch-fs --no-daemon :maestro-integration-tests:test --tests 'io.b2mash.maestro.integration.upgrade.<name>.SeededHistoryCapture' --rerun` inside the worktree;
  - copies `seeded-history.sql` and `capture.properties` into the resource directory;
  - prints their SHA-256 values.

- [ ] **Step 3: Write the failing tests** in `SeededHistoryUpgradeIT` (`@Tag("integration")`; each test calls `SeededHistory.load()` itself):

```java
@Test void theDumpWasCapturedAtCbf5a2a() throws Exception {
    var seeded = SeededHistory.load();
    assertThat(seeded.manifest().getProperty("ref")).isEqualTo("cbf5a2a06acfba9b0f7ae23f37b3f1f1713928f7");
    assertThat(seeded.manifest().getProperty("serverVersion")).startsWith("16.");
    assertThat(sha256(Files.readAllBytes(Path.of(seeded.manifest().getProperty("fixtureSource.path")))))
        .as("SeededHistoryWorkflows.java is frozen: it produced the seeded history")
        .isEqualTo(seeded.manifest().getProperty("fixtureSource.sha256"));
    assertThat(dumpLines()).noneMatch(line -> line.startsWith("\\"));
}

@Test void theSeededDatabaseUpgradesUnderThisBuild() throws Exception {
    var seeded = SeededHistory.load();
    var before = rowCounts(seeded);          // maestro_workflow_{instance,event,signal,timer}
    assertThat(appliedVersions(seeded)).containsExactly("1","2","3","4","5","6","7","8","9","100","101");
    seeded.upgrade(SchemaMode.FLYWAY);
    seeded.upgrade(SchemaMode.VALIDATE);     // throws if anything this build ships is unrecorded or edited
    assertThat(appliedVersions(seeded)).startsWith("1","2","3","4","5","6","7","8","9","100","101");
    assertThat(rowCounts(seeded)).isEqualTo(before);
}

@Test void everySeededWorkflowReadsBackThroughThisBuildsStore() {
    var seeded = SeededHistory.load().upgrade(SchemaMode.FLYWAY);
    Seeds.STATUS_AT_CAPTURE.forEach((id, status) ->
        assertThat(seeded.store().getInstance(id)).get().extracting(WorkflowInstance::status).as(id).isEqualTo(status));
    var types = Seeds.STATUS_AT_CAPTURE.keySet().stream().flatMap(id -> seeded.events(id).stream())
        .map(WorkflowEvent::eventType).collect(toCollection(() -> EnumSet.noneOf(EventType.class)));
    assertThat(types).isEqualTo(Seeds.EVENT_TYPES);          // includes: no UNKNOWN
    // WP1 input: the store wrote the decimal exactly (raw JSONB text of seed-wp1-decimal's ACTIVITY_COMPLETED payload)
    assertThat(rawPayload(seeded, Seeds.WP1_DECIMAL, "ACTIVITY_COMPLETED")).contains("12345678901234567890.123456789");
}
```

If the decimal assertion fails, stop and report rather than adjusting it. A failure would mean corruption happens on the write path, which settles the spec's open question for WP1.

- [ ] **Step 4: Run them to verify they fail** (no dump yet).

Run: `./gradlew --no-watch-fs :maestro-integration-tests:test --tests '*SeededHistoryUpgradeIT'`
Expected: FAIL. `SeededHistory.load()` cannot find `upgrade/cbf5a2a/seeded-history.sql`.

- [ ] **Step 5: Commit the fixture sources before capturing.** This is a checkpoint so a dead session loses nothing.

```bash
git add maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/upgrade scripts/capture-upgrade-fixture.sh
git commit -m "test(upgrade): frozen cbf5a2a fixture workflows, capture driver and history capture mode" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 6: Capture.**

Run: `scripts/capture-upgrade-fixture.sh history cbf5a2a cbf5a2a` (foreground, Bash timeout 600000 ms; a cold build of the worktree takes a few minutes. If it is cut off, tell the coordinator, who runs it as a detached wrapper.)
Expected: the Gradle run in the worktree reports `SeededHistoryCapture > … PASSED`, and both resource files exist. `grep -c "INSERT INTO public.maestro_workflow_event" maestro-integration-tests/src/test/resources/upgrade/cbf5a2a/seeded-history.sql` prints a positive number, and `capture.properties` holds `ref=cbf5a2a06acfba9b0f7ae23f37b3f1f1713928f7`. `git -C "$WT" worktree list` shows no leftover capture worktree.

- [ ] **Step 7: Implement `SeededHistory`** (the interface above) and the `SeededHistoryUpgradeIT` helpers (`rowCounts`, `appliedVersions` from `maestro_schema_history WHERE type = 'SQL' AND success ORDER BY installed_rank`, `rawPayload`, `dumpLines`, `sha256`).

- [ ] **Step 8: Run the tests to verify they pass.**

Run: `./gradlew --no-watch-fs :maestro-integration-tests:test --tests '*SeededHistoryUpgradeIT'`
Expected: PASS (3 tests).

- [ ] **Step 9: Show each test failing against its defect.** Revert after each mutation. Run the same command each time.
  1. Add `maestro-store-postgres/src/main/resources/db/maestro/store/V102__mutation_probe.sql` containing `ALTER TABLE maestro_workflow_instance ADD COLUMN mutation_probe INT NOT NULL;`. Expected: `theSeededDatabaseUpgradesUnderThisBuild` FAILS with `column "mutation_probe" … contains null values`. This is a migration that every empty-database suite applies cleanly. Delete the file.
  2. In `EventType.fromStoredName`, return `UNKNOWN` for `"SIGNAL_TIMEOUT"`. Expected: `everySeededWorkflowReadsBackThroughThisBuildsStore` FAILS, with `UNKNOWN` present and `SIGNAL_TIMEOUT` missing.
  3. Change one comment character in `SeededHistoryWorkflows.java`. Expected: `theDumpWasCapturedAtCbf5a2a` FAILS on the source hash.
  4. Insert a line `\restrict x` at the top of the dump. Expected: `theDumpWasCapturedAtCbf5a2a` FAILS.

  From this commit on, `SeededHistoryWorkflows.java` and both resource files are frozen.

- [ ] **Step 10: Commit**

```bash
git add maestro-integration-tests/src/test/resources/upgrade/cbf5a2a \
  maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/upgrade
git commit -m "test(upgrade): seeded history captured at cbf5a2a; schema upgrades over it" -m "<capture identity + mutation results>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Replay half: this build drives history written by `cbf5a2a`

**Files:**
- Modify: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/upgrade/SeededHistoryUpgradeIT.java` (add tests)

**Interfaces:**
- Consumes: `SeededHistory.load/upgrade/node/events/awaitStatusOrLast/serializer`, `Seeds`, `Invocations`, `RecordingObserver.standDowns()/abandoned()` (all from Task 3 or existing code).

Every test calls `load().upgrade(SchemaMode.FLYWAY)` first and builds a node with `seeded.node(Seeds.SERVICE, observer, invocations)` inside try-with-resources.

- [ ] **Step 1: Write the replay tests.**

```java
@Test @DisplayName("recovery on this build replays every active run cbf5a2a left and drives it to its end")
void recoveryReplaysEveryActiveSeededRunToItsEnd() {
    // before: for each of the 5 active ids, the events as loaded (seq, type, step, payload, argsHash)
    assertThat(node.recover()).isEqualTo(5);   // parked-timer, parked-express, parked-approval, crashed, compensating-crashed
    node.startTimerPoller(Duration.ofMillis(200), 50);
    for (var id : List.of(PARKED_TIMER, PARKED_EXPRESS, PARKED_APPROVAL))
        node.deliverSignal(id, "approval", new Approval("upgrade-node", 2));
    for (var id : List.of(PARKED_TIMER, PARKED_EXPRESS, PARKED_APPROVAL, CRASHED))
        assertThat(seeded.awaitStatusOrLast(id, COMPLETED, Duration.ofSeconds(60))).as(id).isEqualTo(COMPLETED);
    assertThat(seeded.awaitStatusOrLast(COMPENSATING_CRASHED, FAILED, Duration.ofSeconds(60))).isEqualTo(FAILED);

    assertThat(observer.standDowns()).isEmpty();
    assertThat(observer.abandoned()).isEmpty();
    // recorded steps came from the log, unrecorded ones ran exactly once:
    assertThat(invocations.snapshot()).containsAllEntriesOf(Map.of(
        "seed-order.quote", 0, "seed-order.reserve", 0, "seed-risk.verify", 0, "seed-order.charge", 4,
        "seed-saga.reserve", 0, "seed-saga.charge", 0, "seed-saga.ship", 0, "seed-saga.refund", 0,
        "seed-saga.releaseReservation", 1));   // snapshot() has an entry for every Seeds.STEPS key
    // for each id: events(id) starts with exactly the `before` list (history untouched), then ends with WORKFLOW_COMPLETED / WORKFLOW_FAILED
    // each OrderResult: token == the recorded SIDE_EFFECT randomUUID value; decidedAt == the recorded currentTime;
    //   note == "note for " + id; version == 1; napCancelled == true for parked-approval and crashed, false for parked-timer and parked-express;
    //   approver == "capture" for crashed, "upgrade-node" for the other three
}

@Test @DisplayName("finished and other-service runs are left exactly as cbf5a2a wrote them")
void finishedAndOtherServiceRunsAreNotAdopted() {
    // version and lease_epoch of every non-active Seeds id, and of the three seed-wp1-* ids, captured before
    node.recover();
    // assert each is unchanged and the wp1 ids are still WAITING_SIGNAL
}

@Test @DisplayName("a signal id stored by cbf5a2a still suppresses its duplicate")
void aSignalIdStoredAtCbf5a2aStillSuppressesItsDuplicate() {
    assertThat(node.executor().deliverSignal(PARKED_APPROVAL, "note", "duplicate", "client:" + PARKED_APPROVAL + ":note")).isFalse();
}

@Test @DisplayName("a workflow started on this build adopts the orphan signals cbf5a2a stored for it")
void aWorkflowStartedOnThisBuildAdoptsOrphanSignalsStoredAtCbf5a2a() {
    node.startTimerPoller(Duration.ofMillis(200), 50);
    node.start(Seeds.ORPHAN_TARGET, SeedOrderWorkflow.class, new OrderRequest(Seeds.ORPHAN_TARGET, List.of("sku-1"), 1, 1, 1));
    assertThat(seeded.awaitStatusOrLast(Seeds.ORPHAN_TARGET, COMPLETED, Duration.ofSeconds(60))).isEqualTo(COMPLETED);
    // result.approver() == "capture" and result.note() == "note for seed-orphan-target"
}
```

- [ ] **Step 2: Run them.**

Run: `./gradlew --no-watch-fs :maestro-integration-tests:test --tests '*SeededHistoryUpgradeIT'`
Expected: PASS (7 tests). These tests pin today's behaviour. If one fails on unmodified code, that is a replay defect in the current build. Stop and report it with the failure; do not adjust the assertion or the frozen fixture.

- [ ] **Step 3: Show the replay test catching a replay-breaking change that no other suite sees.** In `ArgumentFingerprint.canonical`, prefix the returned text with `"v2:"`. This is the shape of a fix that changes how history is read. Run:

`./gradlew --no-watch-fs :maestro-integration-tests:test --tests '*SeededHistoryUpgradeIT' --tests '*EnginePostgresRecoveryIT' --tests '*ArgumentDriftStandDownIT'`

Expected: `recoveryReplaysEveryActiveSeededRunToItsEnd` FAILS (`standDowns` holds `NONDETERMINISTIC_HISTORY`, or a status assertion reports `WAITING_*`/`RUNNING`). `EnginePostgresRecoveryIT` and `ArgumentDriftStandDownIT` still PASS, because they write and replay with the same build. Revert. Paste the summary into the commit body.

- [ ] **Step 4: Show each remaining test failing against its defect.** Revert after each.
  1. Remove the `service_name = ?` predicate from the paged `getRecoverableInstances` query in `AbstractJdbcWorkflowStore`. Expected: `finishedAndOtherServiceRunsAreNotAdopted` FAILS (a `seed-wp1-*` version changes).
  2. Make `AbstractJdbcWorkflowStore.saveSignalIfAbsent` ignore the message id on insert. Expected: `aSignalIdStoredAtCbf5a2aStillSuppressesItsDuplicate` FAILS, `expected false`.
  3. Skip the `adoptOrphanedSignals` call on the start path in `WorkflowExecutor`. Expected: `aWorkflowStartedOnThisBuildAdoptsOrphanSignalsStoredAtCbf5a2a` FAILS, "expected COMPLETED but was WAITING_SIGNAL".

- [ ] **Step 5: Add a usage note for WP1 and WP3 in `SeededHistory`'s class Javadoc.** It says the following:
  - New assertions go in new test classes that call `SeededHistory.load()`. WP9's assertions change only as a named plan step.
  - WP1 adopts the dormant `upgrade-fixture-wp1` runs with `node(Seeds.WP1_SERVICE, …)`. On `cbf5a2a`, `seed-wp1-offset` and `seed-wp1-set` are expected to stand down (3.8). A fix must not turn that stand-down into FAILED (anti-goal 1).
  - WP3 calls `node.retry(id, SeedFailingWorkflow.class)` or `node.retry(id, SeedSagaWorkflow.class)` on the FAILED ids. It may first re-register `new SeedActivities(invocations, StepGate.OPEN, false)` so that `explode` succeeds.
  - Activity-step keys and expected payload values are in the `Seeds` Javadoc.

- [ ] **Step 6: Run the suite under a non-UTC time zone** (Review Focus 3).

Run: `TZ=Pacific/Chatham ./gradlew --no-watch-fs --no-daemon :maestro-integration-tests:test --tests '*SeededHistoryUpgradeIT' --rerun`
Expected: PASS (7 tests).

- [ ] **Step 7: Run the whole module once.**

Run: `./gradlew --no-watch-fs :maestro-integration-tests:test`
Expected: BUILD SUCCESSFUL. `SeededHistoryCapture` is reported SKIPPED.

- [ ] **Step 8: Commit**

```bash
git add maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/upgrade
git commit -m "test(upgrade): this build recovers, wakes and completes history written at cbf5a2a" -m "<mutation results>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Production guidance for schema mode and DDL privileges (d)

**Files:**
- Modify: `docs/configuration.md` § Schema management (new "**In production.**" paragraph, placed before "**`validate` and `none`.**")
- Modify: `maestro-store-postgres/src/test/java/io/b2mash/maestro/store/postgres/schema/MaestroSchemaInitializerTest.java` (add one test)
- Modify: `RELEASING.md` (new section "Upgrade fixtures")

**Interfaces:**
- Consumes: `withFutureMigration` (Task 2), `PostgresDistributedLock`, `PostgresWorkflowStore`, `WorkflowSignal.builder()`.

The spec makes 3.11d doc-only. This task adds no code. It adds one test because the paragraph's recipe (a role with only DML privileges, running nodes in `validate` mode) is a claim a reader will act on in production, and no test has run it.

- [ ] **Step 1: Write the test.** The GRANT block below is copied verbatim into the docs in step 3.

```java
static final String RUNTIME_GRANTS = """
        GRANT USAGE ON SCHEMA public TO maestro_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON maestro_workflow_instance, maestro_workflow_event,
            maestro_workflow_timer, maestro_workflow_signal, maestro_distributed_lock,
            maestro_leader_election TO maestro_app;
        GRANT SELECT ON maestro_schema_history TO maestro_app;
        """;

@Test @DisplayName("a role with only the documented DML grants runs validate mode and the store, and cannot migrate")
void aDmlOnlyRoleRunsValidateModeButCannotMigrate(@TempDir Path dir) throws Exception {
    new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, cl).initialize();                 // as the owner
    // as the owner: CREATE ROLE maestro_app LOGIN PASSWORD 'app'; then RUNTIME_GRANTS
    var app = /* PGSimpleDataSource on the same database as maestro_app */;
    assertThatCode(() -> new MaestroSchemaInitializer(app, SchemaMode.VALIDATE, cl).initialize()).doesNotThrowAnyException();
    // store and lock work as maestro_app: lock tryAcquire/renew/release, trySetLeader;
    //   store.saveSignal (orphan) -> getUnconsumedSignals -> markSignalConsumed;
    //   getRecoverableInstances("svc", null, 10); purgeTerminalInstancesBefore(COMPLETED, now, 10);
    //   deleteUnconsumedSignalsForTerminalInstancesBefore(now)
    var future = withFutureMigration(dir, "V102__future_probe.sql", "CREATE TABLE maestro_future_probe (id INT);");
    assertThatThrownBy(() -> new MaestroSchemaInitializer(app, SchemaMode.FLYWAY, future).initialize())
        .rootCause().hasMessageContaining("permission denied");
}
```

- [ ] **Step 2: Run it.**

Run: `./gradlew --no-watch-fs :maestro-store-postgres:test --tests '*MaestroSchemaInitializerTest'`
Expected: PASS. If `VALIDATE` needs more than these grants, stop and report; the doc must state what is actually required. Then the mutation: remove `UPDATE` from `RUNTIME_GRANTS`. Expected: FAIL with `permission denied for table maestro_workflow_signal` (or `maestro_distributed_lock`). Revert.

- [ ] **Step 3: Write the "In production" paragraph.** Content, in this order:
  - **Default.** `flyway` mode runs Maestro's DDL as the application's own database role, at every node start. This is the same as Spring Boot's own Flyway default. That role must be able to create tables in the schema and must own the `maestro_*` tables. Index migrations lock their table against writes while they run, during the deploy that ships them.
  - **Recommended for production.** Apply migrations as the role that owns the `maestro_*` tables, as a separate step before deploying the build that ships them. Run every node with `maestro.store.schema.mode: validate` and a role that holds only the grants below (insert `RUNTIME_GRANTS` verbatim, with `public` → your schema). Under `validate` a node never needs DDL rights, and it refuses to start, naming the pending versions, until the owner has applied them.
  - **How to apply as the owner.** Either the Flyway CLI line already given under "`validate` and `none`" (it must include `-outOfOrder=true`, see "Out-of-order application"), or a one-off job that calls `new MaestroSchemaInitializer(ownerDataSource, SchemaMode.FLYWAY, classLoader).initialize()`. Maestro has no `maestro.store.schema.user`/`password`, so the owner's credentials go only to that job.
  - **Order during a rolling deploy.** Migrate first. Nodes still on the earlier build keep running and keep restarting, because every new migration sorts above V100 and is a "future" version to them ("Numbering rule").
  - **Why the default is not `validate`.** It would fail every first start of every new installation.
  - One sentence: `MaestroSchemaInitializerTest.aDmlOnlyRoleRunsValidateModeButCannotMigrate` runs exactly these grants.

- [ ] **Step 4: Add an "Upgrade fixtures" section to `RELEASING.md`.** It says:
  - At each release tag, add a fixture for that tag: `scripts/capture-upgrade-fixture.sh migrations <tag> <name>`, then copy the latest frozen package to `upgrade.<name>` (rename the package only), add a `SeededHistory` factory for it, and run `scripts/capture-upgrade-fixture.sh history <tag> <name>`.
  - Never regenerate or delete an existing fixture. Link to this plan's "Regeneration rules".
  - The script refuses to overwrite.

- [ ] **Step 5: Run the full build.**

Run: `./gradlew --no-watch-fs build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add docs/configuration.md RELEASING.md \
  maestro-store-postgres/src/test/java/io/b2mash/maestro/store/postgres/schema/MaestroSchemaInitializerTest.java
git commit -m "docs(schema): production guidance for schema mode and DDL privileges, proven by a DML-only role test" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Review section

(The coordinator fills this in after the whole-branch review: gate results, mutation evidence, the capture identity line, and any finding filed.)
