# Releasing Maestro

This document describes how to publish a new release of Maestro to Maven Central.

## Prerequisites (One-Time Setup)

### 1. Register the `io.b2mash.maestro` Namespace

1. Go to [central.sonatype.com](https://central.sonatype.com) and sign in (or create an account).
2. Navigate to **Namespaces** and claim `io.b2mash.maestro`.
3. Verify ownership via DNS TXT record on the `b2mash.io` domain (or use GitHub-based verification).

### 2. Generate a Publishing Token

1. In [central.sonatype.com](https://central.sonatype.com), go to **Account** > **Generate User Token**.
2. Save the **username** and **password** — these are your `OSSRH_USERNAME` and `OSSRH_PASSWORD`.

### 3. Generate a GPG Key

```bash
# Generate a key (use the project email or a maintainer email)
gpg --full-generate-key

# Export the ASCII-armored private key
gpg --armor --export-secret-keys YOUR_KEY_ID

# Get the passphrase you used during generation
```

### 4. Configure GitHub Secrets

Create a GitHub **environment** named `maven-central` in the repository settings, then add these secrets:

| Secret | Value |
|--------|-------|
| `OSSRH_USERNAME` | Sonatype token username |
| `OSSRH_PASSWORD` | Sonatype token password |
| `GPG_SIGNING_KEY` | Full ASCII-armored GPG private key (including `-----BEGIN PGP PRIVATE KEY BLOCK-----`) |
| `GPG_SIGNING_PASSWORD` | GPG key passphrase |

Optionally, enable **required reviewers** on the `maven-central` environment for deployment protection.

### 5. Enable GitHub Pages

In the repository settings:
1. Go to **Pages** > **Build and deployment**.
2. Set **Source** to **GitHub Actions**.

---

## Release Process

### 1. Prepare the Release

Ensure `main` is in a releasable state:

```bash
# Pull latest
git checkout main && git pull

# Run full build + tests
./gradlew build

# Verify Javadoc generates cleanly
./gradlew aggregateJavadoc
```

### 2. Tag the Release

```bash
# Choose the version (e.g., 0.3.0)
git tag v0.3.0
git push origin v0.3.0
```

The tag push triggers two workflows:

1. **Release to Maven Central** (`release.yml`) — builds, tests, signs, publishes all library modules to Maven Central, and creates a GitHub Release with auto-generated notes.
2. **Javadoc** (`javadoc.yml`) — generates aggregate Javadoc and deploys to GitHub Pages.

### 3. Monitor the Release

1. Go to **Actions** tab and watch both workflows.
2. If the `maven-central` environment has required reviewers, approve the deployment when prompted.
3. After the release workflow completes:
   - Verify artifacts on [central.sonatype.com](https://central.sonatype.com) (may take 10-30 minutes to sync).
   - Verify the GitHub Release at `https://github.com/maestro-workflow/maestro/releases`.
   - Verify Javadoc at the GitHub Pages URL.

### 4. Post-Release

Update the version in `gradle.properties` to the next snapshot:

```bash
# e.g., after releasing 0.3.0
# macOS/BSD:
sed -i '' 's/version=.*/version=0.4.0-SNAPSHOT/' gradle.properties
# Linux (GNU sed):
# sed -i 's/version=.*/version=0.4.0-SNAPSHOT/' gradle.properties

git add gradle.properties
git commit -m "chore: bump version to 0.4.0-SNAPSHOT"
git push origin main
```

Then add the upgrade fixtures for the new tag (see [Upgrade fixtures](#upgrade-fixtures)) and its row in `previousReleases` (see [The rolling test against previous releases](#the-rolling-test-against-previous-releases)). The next release's gate checks that both exist (see [The release gate](#the-release-gate)).

---

## The release gate

A release (a final tag through `release.yml`, a pre-release or an experimental tag through `publish-github-packages.yml`) passes a gate before anything is published. The gate is a job of its own (`gate`), with a read-only token (`contents: read`, `packages: read`); the publishing job `needs` it. It checks three things:

1. **The release before this one is the newest row of `previousReleases`, and has its upgrade fixtures.** `scripts/previous-release.sh --check-release <version>` takes the release before `<version>` from the repository's published GitHub releases — the highest one below it under semantic-version precedence, so a pre-release counts as a release (`0.3.0-rc.1` < `0.3.0-rc.2` < `0.3.0`) and experimental releases (`-exp.`) never do — and fails, naming it, when the newest row of `previousReleases` is not that release (its row was never added). It then fails, naming the missing file, unless that release has the migrations fixture and the seeded-history fixture named after its tag (`v0_3_0_rc_1` for `v0.3.0-rc.1`) and a `SeededFixture` implementation of that name. A tag counts as released only once its GitHub release exists, which both release workflows create as their last step: a tag whose publication failed part-way has no release and is skipped (see "Publishing one" for what to do with it). If the releases cannot be listed (`gh`, `GH_TOKEN`), the gate fails.
2. **The rolling test against that release is green**: `:maestro-integration-tests:rollingUpgradeTest`. A red rolling test stops the release.
3. **This build's own artifacts run the consumer smoke test**: the gate publishes the tag's checkout at the release's version to a local repository and runs `maestro-integration-tests/consumer-smoke` against it (below).

**The first release** has no release before it. The gate fails then too, unless the repository variable `MAESTRO_FIRST_RELEASE` is `true` (Settings → Secrets and variables → Actions → Variables), which both release workflows pass to the script. Set it for that one release and delete it afterwards; while there is an earlier tag, the variable is ignored.

What the workflows cannot check, the person tagging does: that the new release's own fixtures are captured from its tag right after it is published (Post-Release, above) and that its row is added to `previousReleases`. The next release's gate fails, naming the missing row or file, until they are.

Run the gate locally before tagging:

```bash
scripts/previous-release.sh --check-release 0.3.0-rc.2
./gradlew --no-watch-fs :maestro-integration-tests:rollingUpgradeTest -Pmaestro.rolling.oldRepo=<local repository with the previous release>
```

---

## Upgrade fixtures

Maestro's tests hold frozen evidence of what earlier commits shipped and wrote, so that no later change can silently break a database an earlier build migrated, or the workflow history in it. A fixture records one commit, under one name:

| Fixture | Files | Pinned by |
|---|---|---|
| Migrations | `maestro-store-postgres/src/test/resources/upgrade/<name>/` — every migration the commit shipped (`store/`, `lock/`, and `messaging/` before the transport was removed), byte for byte, plus `SOURCE` (ref, capture time, SHA-256 of each file) | `ShippedMigrationsFrozenTest`: each `SOURCE` by a SHA-256 constant, each copy against `SOURCE`, each still-shipped migration against its copy (line endings folded). `MaestroSchemaInitializerTest`: a database the frozen set migrated is taken over by this build |
| Seeded history | `maestro-integration-tests/src/test/resources/upgrade/<name>/seeded-history.sql` (a `pg_dump` of every `maestro_*` table: DDL, `maestro_schema_history`, rows) and `capture.properties` (ref, capture time, server version, SHA-256 of the frozen workflow source), produced by the frozen package `io.b2mash.maestro.integration.upgrade.<name>` (`SeededHistoryWorkflows`, the `SeededHistoryCapture` driver) | `SeededHistoryUpgradeIT`: the manifest's SHA-256 and the dump's SHA-256 against the constants in `SeededHistory` (`CAPTURE_MANIFEST_SHA256`, `DUMP_SHA256`), the manifest's `ref`, and the frozen source against the manifest's SHA-256; then this build upgrades, recovers and replays the history |

The fixtures today: `cbf5a2a` (migrations and seeded history), `v0_3_0_rc_1` (migrations and seeded history, tag `v0.3.0-rc.1`, the first published pre-release) and `c01d498` (migrations only: the last set that shipped the retired V200–V202 band).

`SeededHistoryUpgradeIT` runs once per seeded-history fixture listed in `SeededFixtures.all()`. Each fixture is a `SeededFixture` implementation outside its frozen package (`Cbf5a2aFixture`, `V030Rc1Fixture`): the pinned SHA-256s, the dump's clock window, the events the older build recorded, the events this build is expected to append, and adapters from the frozen package's own types (its `OrderResult`, `Approval`, `SeedActivities`, …) to types the shared test can hold. `ShippedMigrationsFrozenTest` checks every migration of each release migrations fixture (`SHIPPING_FIXTURES`) against this build's resources.

What one seeded-history fixture contains, and what the shared test asserts of it, is the class Javadoc of `SeededHistoryUpgradeIT` ("What resumes correctly asserts, per parked state"): runs parked on a timer, on a timer-backed await and on a signal, a run crashed mid-activity, a saga crashed mid-compensation, failed runs awaiting a Retry, a terminated and a completed run, orphan signals, a stored signal id, and three dormant typed-result runs. A later fixture keeps every `cbf5a2a` seed under the same id and may add seeds in a service of its own: `v0_3_0_rc_1` adds `seed-saga-parked`, a saga parked on a signal after both compensations were registered (`SeededParkedSagaIT`).

### Adding a fixture at a release tag

Use one name per tag for both kinds. A seeded-history name is also a Java package segment (`[a-z][a-z0-9_]*`), so tag `v0.5.0` takes the name `v0_5_0`. Run everything from the repository root, with Docker running.

1. **Capture the migrations:** `scripts/capture-upgrade-fixture.sh migrations v0.5.0 v0_5_0`. It writes `maestro-store-postgres/src/test/resources/upgrade/v0_5_0/` from the tag's git objects, never from the working tree. Pin it the way `cbf5a2a` and `v0_3_0_rc_1` are pinned: register the name in `SHIPPING_FIXTURES` in `ShippedMigrationsFrozenTest` (which then checks every migration of the set against this build's resources; `everyFrozenSetIsRegistered` fails while a fixture directory is in neither `SHIPPING_FIXTURES` nor `NOT_SHIPPING`), a `SOURCE` SHA-256 constant there with a test like `theFrozenSetIsEverythingShippedAtV030Rc1`, and a test in `MaestroSchemaInitializerTest` that migrates a database with the frozen set (`FrozenMigrations.named("v0_5_0")`) and then runs this build's initializer in `flyway` and `validate` mode.
2. **Copy the latest frozen package** (today `io.b2mash.maestro.integration.upgrade.v0_3_0_rc_1`) to `io.b2mash.maestro.integration.upgrade.v0_5_0`.
   - **`SeededHistoryWorkflows`**: change its `package` line and the Javadoc's references to the tag. Keep every seed it holds, under the same id, service, workflow and input: the shared assertions depend on them. A new seed is allowed, in a service of its own so the shared assertions do not see it, with a test of its own. The file defines how the history is produced, and the capture hashes it into `capture.properties`. It must use only the public workflow-author API of `maestro-core` (annotations, `WorkflowContext`, activity interfaces, the model and exception types), so that it compiles unchanged at the tag.
   - **`SeededHistoryCapture`**: change its `package` line, its imports, and the two path constants `FIXTURE_SOURCE` and `FIXTURE_PACKAGE`, which name the package's directory. The constants matter. The driver refuses a checkout that differs from the tag anywhere outside `FIXTURE_PACKAGE`, and it records the SHA-256 of the file at `FIXTURE_SOURCE`. The tag's own tree already holds the older packages, so a stale `FIXTURE_SOURCE` would record the hash of the wrong file. The driver compiles against the tag's code, so it may use only APIs present there. Where the test harness it drives (`MaestroEngineHarness`, `TerminalWait`) differs at the tag, adapt the driver, and only the driver.
   - Add a `-text` line for the new `SeededHistoryWorkflows.java` to `.gitattributes`. The resources are already covered there by `upgrade/**`.
3. **Add the fixture's `SeededFixture`**: copy `V030Rc1Fixture` to `V050Fixture` next to it (never inside the frozen package, which the capture copies into the tag's checkout, where `SeededFixture` may not exist), point its imports at the new package, and list it in `SeededFixtures.all()`. Its values come from the capture (step 4): the two SHA-256s the script prints, the clock window read from the dump (below), `RECORDED_EVENTS` derived from the restored dump by raw SQL, and `APPENDED` from the first run of `recoveryReplaysEveryActiveSeededRunToItsEnd` — read every appended event and explain any difference from the previous fixture's before pinning it.
4. **Capture the history:** `scripts/capture-upgrade-fixture.sh history v0.5.0 v0_5_0`. The script needs `upgrade/v0_5_0/SeededHistoryCapture.java` in this checkout (it copies the package from the working tree, so it need not be committed yet) and refuses if the tag already holds a package of that name. It checks the tag out as a temporary detached worktree, copies the package in, and runs `./gradlew --no-watch-fs --no-daemon --no-build-cache :maestro-integration-tests:test --tests io.b2mash.maestro.integration.upgrade.v0_5_0.SeededHistoryCapture --rerun` there, so every task of the capture build runs at the tag and nothing comes from a build cache. The driver runs only when the script sets `MAESTRO_UPGRADE_CAPTURE_OUT`, and refuses to capture unless that checkout's `HEAD` is the tag's full SHA and every modified or untracked file is under the fixture package. The script checks that both files were written and that `capture.properties` records the tag's SHA, moves them into `maestro-integration-tests/src/test/resources/upgrade/v0_5_0/`, and prints the SHA-256 of each. It removes the temporary worktree on every exit path: success, failure, or an interrupt.
5. **Commit the fixture in its own commit,** with its `SeededFixture` (`theDumpWasCapturedAtItsRef` then asserts it as it asserts `cbf5a2a`). Wait until the clock has passed the new dump's `LATEST_MUST_BE_DUE` (about ten minutes after the capture) before running the replay tests. Then run `./gradlew --no-watch-fs :maestro-store-postgres:test --tests '*ShippedMigrationsFrozenTest' --tests '*MaestroSchemaInitializerTest'` and `./gradlew --no-watch-fs :maestro-integration-tests:test --tests '*SeededHistoryUpgradeIT'`.

### Never regenerate or delete an existing fixture

A fixture is evidence about the past. Regenerated at a later commit, it would record what the newer build ships and writes, and the guard would then pass against exactly the regression it exists to catch. So:

- **Never edit a fixture**: not the migration copies, `SOURCE`, the dump, `capture.properties`, or the frozen `SeededHistoryWorkflows.java`. Editing the last one voids its dump, which it produced. An approved re-capture (below) is the only exception.
- **Never regenerate a fixture at a later commit, and never delete one.** A later release adds a new fixture under a new name. It never replaces one.
- **Re-capture only a defective fixture.** If the committed fixture is itself defective, it may be re-captured. That needs the maintainer's explicit approval. The re-capture deletes the old directory, runs the script at the *same* ref, and goes in its own commit, so the deletion and the new files are one reviewed change.

The script backs these rules up: it refuses to overwrite. In both modes it stops if the fixture directory exists, checks again just before it moves the new files in, and writes nothing on failure. A re-capture is not byte-identical, and it changes these pinned values, which the same commit must update:

- `DUMP_SHA256` in the fixture's `SeededFixture` (the dump's bytes);
- `CAPTURE_MANIFEST_SHA256` in the fixture's `SeededFixture` (the bytes of `capture.properties`, which always records a new capture time);
- the frozen-source SHA-256 in `capture.properties`, if `SeededHistoryWorkflows.java` was the defect (`theDumpWasCapturedAtItsRef` checks it against the file);
- the clock window in the fixture's `SeededFixture`, `LATEST_MUST_BE_DUE` and `EARLIEST_MUST_STAY_PENDING` (and the `"<workflowId>/<timerId>"` entries of `MUST_BE_DUE`, if the timer ids moved). They are the old dump's timer due times, and `requireClockWithinFixtureWindow()` refuses to run any replay test until they match the new one. Read them from the restored dump with `SELECT workflow_id, timer_id, fire_at FROM maestro_workflow_timer WHERE status = 'PENDING' ORDER BY fire_at`: `LATEST_MUST_BE_DUE` is the latest `fire_at` among the `MUST_BE_DUE` rows, and `EARLIEST_MUST_STAY_PENDING` the earliest `fire_at` among all the others; After a fresh capture, wait before running the replay tests: the capture parks two runs on ten-minute timers, so `LATEST_MUST_BE_DUE` is about ten minutes after `capturedAt`, and until the clock passes it `requireClockWithinFixtureWindow()` fails every replay test by design.
- `RECORDED_EVENTS` in the fixture's `SeededFixture`, only if the corrected defect changes which events a seeded workflow wrote. Re-derive it from the restored dump by raw SQL, as its Javadoc says, never through the store, and explain every changed entry in the commit;
- the `SOURCE` SHA-256 constants in `ShippedMigrationsFrozenTest`, for a migrations re-capture.

Reviewers should expect these differences and no others:

- **Migrations:** the `.sql` copies come from git objects and are identical. `SOURCE` records a new capture time, so its SHA-256 constant in `ShippedMigrationsFrozenTest` changes.
- **Seeded history:** UUIDs, timestamps and timer due times differ; rows can come out in a different order, because `Set.of` iteration order differs from one JVM to the next and parallel branches can write in either order. `capture.properties` records a new capture time, and a new server version if `postgres:16-alpine` has moved to a newer patch release. The dump's and the manifest's SHA-256 change, so `DUMP_SHA256` and `CAPTURE_MANIFEST_SHA256` in the fixture's `SeededFixture` change, and the new timer due times move `LATEST_MUST_BE_DUE` and `EARLIEST_MUST_STAY_PENDING`.

Either way the regeneration is a visible change to a pinned constant, in a diff a reviewer reads. (The WP9 plan, `docs/superpowers/plans/2026-11-12-engine-hardening-cycle/wp09-schema-safety.md`, records how these rules were made; this section is the rule.)

**Why an edit is always caught.** Every fixture file is pinned, directly or through a manifest, by a SHA-256 literal in Java source:

- `DUMP_SHA256` in each fixture's `SeededFixture` pins the bytes of its `seeded-history.sql`.
- `CAPTURE_MANIFEST_SHA256` in each fixture's `SeededFixture` pins the bytes of its `capture.properties`, and with them the frozen `SeededHistoryWorkflows.java`: `theDumpWasCapturedAtItsRef` hashes the raw bytes of the file at the manifest's `fixtureSource.path` against its `fixtureSource.sha256`, so editing the source together with that hash, or pointing the path at another file, changes the manifest and fails the literal.
- `CBF5A2A_SOURCE_SHA256`, `V0_3_0_RC_1_SOURCE_SHA256` and `C01D498_SOURCE_SHA256` in `ShippedMigrationsFrozenTest` pin the migration `SOURCE` manifests (line endings folded), and each `SOURCE` pins every migration copy beside it.

So a fixture cannot be edited or regenerated without changing one of these literals: a regeneration always shows up in review as an edited Java constant. Gradle cannot skip these checks: `maestro-integration-tests/build.gradle.kts` declares the frozen `*/SeededHistoryWorkflows.java` sources and `src/test/resources/upgrade` as inputs of `test` and of `seededHistoryNonUtcTest` (the same suite in a non-UTC JVM), so even a comment-only edit (which leaves the bytecode unchanged) reruns the tests instead of leaving `test` up to date or restoring it from the build cache. The root `.gitattributes` marks the frozen fixtures `-text`, so git never converts their line endings on checkout and every platform reads the bytes the hashes pin.

### The rolling test against previous releases

The fixtures freeze what a past build wrote. The rolling test (`RollingUpgradeIT`, task `:maestro-integration-tests:rollingUpgradeTest`, CI job "Rolling upgrade" in `build-test.yml`) runs a past build: a node of each previous release, as a JVM of its own, beside a node of this build, on one database. Runs parked by either build are adopted and woken by the other, and one signal sent through both builds with one client message id is stored once. The rule each direction is held to:

- **Upgrade** (parked by the previous release, adopted by this build): the run must finish exactly as on one build. A stand-down fails the test.
- **Rollback** (parked by this build, adopted by the previous release): the previous release must either (a) finish the run correctly, or (b) stand down having recorded only what a correct run records (a prefix of the run's final history), never a `FAILED` or `TERMINATED` status, never a compensation in a run that does not fail, and never losing a signal. After (b) a node of this build must take the run back and finish it correctly; the steps after the park may have run on either node, each exactly once. (b) passes the test, because `docs/operations.md` §10.3 allows it, but the CI job turns each one into a "Rollback stand-down" warning on the run and lists it in the job summary.

The previous releases are the rows of `previousReleases` in `maestro-integration-tests/rolling-node/build.gradle.kts`: a Maestro version and the Spring Boot version it was built with. After a release is published, add its row; remove a row only when its release leaves the support window. The node source (`rolling-node/src/main`) is compiled against every row, so it may use only API present in the oldest one.

Each row resolves from the repository its version was published to: a pre-release (a version with a hyphen suffix) from GitHub Packages (`gpr.user`/`gpr.token`, or `GITHUB_ACTOR`/`GITHUB_TOKEN`, as in [Consuming one](#consuming-one)), a final release from Maven Central; no Maestro version is looked up in both. The consumer smoke test and the API compatibility report route the same way. A machine without a `read:packages` token can publish the release tag's checkout into a local repository and point the test at it, without publishing anything:

```bash
git worktree add --detach /tmp/maestro-v0.3.0-rc.1 v0.3.0-rc.1
(cd /tmp/maestro-v0.3.0-rc.1 && ./gradlew publishToMavenLocal -Pversion=0.3.0-rc.1 -Dmaven.repo.local=/tmp/maestro-releases)
./gradlew :maestro-integration-tests:rollingUpgradeTest -Pmaestro.rolling.oldRepo=/tmp/maestro-releases
git worktree remove /tmp/maestro-v0.3.0-rc.1
```

The test prints where the previous release came from, and writes one line per scenario run to `maestro-integration-tests/build/rolling-upgrade/results.txt`, beside each node's log: `PASSED` with what it observed (including any stand-down), `FAILED` with the first line of the failure, or `ABORTED`.

The CI job skips the test, with a "Rolling upgrade skipped" notice, on a pull request from a fork and on a Dependabot run: their token may not be able to read the registry, so no resolution is attempted there. A skipped job still reports success, and that success is **no evidence about rolling upgrades**: on those pull requests the rolling test has not run. Before making "Rolling upgrade" a required check, observe on a fork pull request both the skip notice and a successful check, so the requirement cannot block an outside contributor. Registry resolution itself is validated on an eligible (same-repository) run; the first one, on PR #71, resolved `0.3.0-rc.1` from GitHub Packages and passed.

Signals through Kafka are covered too, in both directions: the Kafka scenarios run one broker (Testcontainers, `confluentinc/cp-kafka:7.7.1`, topic auto-creation off, every topic pre-created) and launch the nodes that consume it with Maestro's Kafka messaging, in one consumer group per scenario shared by both builds; every other node runs with `KafkaMessagingAutoConfiguration` excluded. A record without a header consumed by one build is stored as `kafka:<topicId>:<partition>:<offset>` and consumed by a run parked on the other build; one header id published twice, read by a node of each build in turn, is stored once as `hdr:<topic>:<id>`; and a redelivery of an already-consumed record to the other build (the group's offset rewound to it while the group is empty, as a lost offset commit leaves it) is stored once. In each, the listener topic's `.DLT` stays empty, so a duplicate that failed instead of being suppressed fails the test. Not covered: the engine's own signal channel (`maestro.signals.<service>`, `SignalMessage` envelopes and their `env:` ids), which nothing in Maestro publishes to and the nodes only subscribe to, and lifecycle and admin events on the wire between builds.

## The consumer smoke test

`maestro-integration-tests/consumer-smoke` is a standalone Gradle build, outside this repository's `settings.gradle.kts`: a minimal Spring Boot service that depends on `maestro-bom`, the starter, `maestro-store-postgres` and `maestro-messaging-kafka` at a published version, imports the Spring Boot BOM itself (`platform("org.springframework.boot:spring-boot-dependencies:…")`, because the published Gradle module metadata leaves Spring versions to the consumer), and runs one workflow to completion against Testcontainers Postgres and Kafka (an activity, a durable sleep, a signal through a `@MaestroSignalListener`, a second activity). It checks that the starter and the engine it ran were loaded from the published jars of that version.

```bash
# from GitHub Packages (gpr.user/gpr.token, or GITHUB_ACTOR/GITHUB_TOKEN with read:packages)
./gradlew -p maestro-integration-tests/consumer-smoke test -Pmaestro.version=0.3.0-rc.1
# from a local Maven repository (for example the one the rolling-test recipe above fills)
./gradlew -p maestro-integration-tests/consumer-smoke test -Pmaestro.version=0.3.0-rc.1 -Pmaestro.repo=/tmp/maestro-releases
```

A pull request cannot change what it tests, so it does not run per pull request. It runs in two places: in the release gate, against the artifacts of the release being published (above); and in the workflow `consumer-smoke.yml`, weekly and on demand (`workflow_dispatch`, optionally with a `version`), against a release already published to GitHub Packages — by default the newest row of `previousReleases` (`scripts/previous-release.sh`) — which is what exercises the registry path a user takes.

## API compatibility report

`maestro-integration-tests/api-compat` is a standalone Gradle build that compares every published module of two versions with japicmp (`me.champeau.gradle.japicmp` 0.4.6, which runs on Gradle 9 and reads Java 25 class files): the old version from GitHub Packages (or `-Pmaestro.oldRepo=<dir>`), the new one from a local repository that `publishToMavenLocal -Dmaven.repo.local=<dir>` of the checkout fills. It writes `build/reports/api-compat/summary.md` (per module: changed classes, classes with a binary-incompatible change, and classes with a change that is binary-compatible but source-incompatible, such as an annotation element added without a default; one table for the public API, one for the internal-until-1.0 packages `io.b2mash.maestro.core.spi`, `io.b2mash.maestro.core.loop` and `io.b2mash.maestro.core.engine`) and a japicmp report per module and scope.

```bash
./gradlew publishToMavenLocal -Pversion=0.0.0-api-check -Dmaven.repo.local=/tmp/api-check-repo
./gradlew -p maestro-integration-tests/api-compat apiCompatReport -Pmaestro.oldVersion=0.3.0-rc.1 \
    -Pmaestro.newVersion=0.0.0-api-check -Pmaestro.newRepo=/tmp/api-check-repo
```

The CI job "API compatibility report" in `build-test.yml` runs it against the newest row of `previousReleases` and writes the summary to the job summary. **Until 1.0 it reports and never fails.** To make it fail on a binary- or source-incompatible change to the public API, pass `-Pmaestro.apiCompat.fail=true` in that job (it sets japicmp's `failOnSourceIncompatibility` on the public comparisons only). In that mode japicmp also fails a public class made incompatible through an internal type it exposes, which the public table does not count; the task's failure names the module.

## Experimental releases

A pull request that changes what is persisted or read back — an event type, an event or signal payload, a workflow input or output shape, a migration — publishes an **experimental release** from its branch before it merges, so the change can be run beside the last release (the rolling test, a consumer, a pilot environment) without being released:

- **Name:** `X.Y.Z-exp.<topic>.<n>`, where `X.Y.Z` is the next version, `<topic>` is lowercase letters, digits and hyphens naming the change, and `<n>` counts from 1 (`0.4.0-exp.event-payloads.1`). `publish-github-packages.yml` refuses any other `-exp` name.
- **Published from a branch tag:** tag the branch's head (`git tag v0.4.0-exp.event-payloads.1 && git push origin v0.4.0-exp.event-payloads.1`). The tag runs the ordinary pre-release workflow, release gate included; the GitHub pre-release it creates is marked experimental and says it must never run against a database that matters.
- **Required** for any pull request that changes events, payloads or migrations; the pull request names the experimental version it was run as.
- **Never against a database that matters:** an experimental build may write history, or apply a migration, that no released build reads. Run it only against databases you can drop.
- **Deleted when the branch closes:** delete the version under the repository's **Packages**, the GitHub pre-release, and the tag (`git push --delete origin v0.4.0-exp.event-payloads.1`). It is never a row of `previousReleases` and never gets a fixture.

---

## Pre-releases on GitHub Packages

Until Maven Central is set up, and for pilot builds after that, a pre-release goes to this repository's GitHub Packages Maven registry. It needs none of the prerequisites above: no namespace, no publishing token, no GPG key.

### Publishing one

1. Choose a version with a hyphen suffix: `0.3.0-rc.1`. The suffix is what routes the tag: `v0.3.0-rc.1` runs `publish-github-packages.yml`; a tag without one (`v0.3.0`) runs `release.yml` (Maven Central) and `javadoc.yml`.
2. Tag the commit on `main` and push the tag:

   ```bash
   git tag v0.3.0-rc.1
   git push origin v0.3.0-rc.1
   ```

3. The workflow builds and tests at that version, publishes the BOM and every module listed under [Published Modules](#published-modules), and creates a GitHub pre-release.

A published version cannot be overwritten, so a failed run cannot simply be re-run once any module has uploaded: the re-run's upload of that module is refused. If every module uploaded and only the last step failed, check under the repository's **Packages** that the BOM and all seven modules show the version, then create the release by hand: `gh release create v0.3.0-rc.1 --title "Maestro 0.3.0-rc.1" --generate-notes --prerelease`. If only some modules uploaded, publish the next suffix; do not create a GitHub release for the failed tag. The release gate counts a tag as released only when its GitHub release exists, so the next suffix is checked against the last release that did publish ("The release gate", above). Delete the failed tag if you like; the gate does not need it gone.

To replace a bad pre-release, publish the next suffix (`-rc.2`); delete the bad version under the repository's **Packages** if nobody should resolve it. The artifacts are not signed.

### Consuming one

GitHub Packages asks for a token on every Maven download, from a public repository too. Each developer and each CI job needs a GitHub token with the `read:packages` scope (a workflow in another repository of the same owner can use its own `GITHUB_TOKEN` once the package grants that repository access).

Gradle, in the consuming project's `build.gradle.kts` (a build that declares its repositories in `settings.gradle.kts` puts the same `maven { … }` entry inside `dependencyResolutionManagement { repositories { … } }`):

```kotlin
repositories {
    mavenCentral()
    maven {
        name = "maestro"
        url = uri("https://maven.pkg.github.com/rakheen-dama/maestro")
        credentials {
            username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
            password = providers.gradleProperty("gpr.token").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
        }
        content { includeGroup("io.b2mash.maestro") }
    }
}
```

Put `gpr.user` and `gpr.token` in `~/.gradle/gradle.properties`, never in the project. Maven users add the same URL as a `<repository>`, and in `~/.m2/settings.xml` a `<server>` with the matching `<id>`, their GitHub login as `<username>` and the token as `<password>`.

Then depend on the BOM at the pre-release version, as in the [README](README.md#1-add-dependencies):

```kotlin
implementation(platform("io.b2mash.maestro:maestro-bom:0.3.0-rc.1"))
```

### Publishing by hand

From a checkout of the tag, with a token that has `write:packages`. Give the token through the environment, not on the command line, where it would land in shell history and the process list:

```bash
./gradlew build -Pversion=0.3.0-rc.1
export GITHUB_ACTOR=<login>
read -rs GITHUB_TOKEN && export GITHUB_TOKEN   # paste the token, press Enter
./gradlew publishAllPublicationsToGitHubPackagesRepository -Pversion=0.3.0-rc.1
unset GITHUB_TOKEN
```

`gprUser` and `gprToken` in `~/.gradle/gradle.properties` work too. `-PgprRepository=<owner>/<repo>` publishes to another repository's registry (a fork).

---

## Published Modules

The following modules are published to Maven Central:

| Module | Artifact ID |
|--------|-------------|
| `maestro-core` | `io.b2mash.maestro:maestro-core` |
| `maestro-spring-boot-starter` | `io.b2mash.maestro:maestro-spring-boot-starter` |
| `maestro-store-jdbc` | `io.b2mash.maestro:maestro-store-jdbc` |
| `maestro-store-postgres` | `io.b2mash.maestro:maestro-store-postgres` |
| `maestro-messaging-kafka` | `io.b2mash.maestro:maestro-messaging-kafka` |
| `maestro-admin-client` | `io.b2mash.maestro:maestro-admin-client` |
| `maestro-test` | `io.b2mash.maestro:maestro-test` |

Each published artifact includes: compiled JAR, sources JAR, Javadoc JAR, GPG signatures, and POM.

**Not published:** `maestro-admin` (standalone app), `maestro-samples/*` (examples).

---

## Versioning

Maestro follows [Semantic Versioning](https://semver.org/):

- **MAJOR** — Breaking API changes (SPI contract changes, removed public APIs)
- **MINOR** — New features, backward-compatible SPI additions
- **PATCH** — Bug fixes, performance improvements

During development, `gradle.properties` uses a `-SNAPSHOT` suffix (e.g., `0.4.0-SNAPSHOT`). Release builds override this via the `-Pversion=X.Y.Z` flag from the git tag.

---

## Troubleshooting

### Release workflow fails at "Publish to Maven Central"

- **401 Unauthorized**: Check `OSSRH_USERNAME` / `OSSRH_PASSWORD` secrets. Tokens expire — regenerate at central.sonatype.com.
- **Signing error**: Verify `GPG_SIGNING_KEY` contains the full armored key including headers. Verify `GPG_SIGNING_PASSWORD` matches.
- **Validation failure**: Maven Central requires: groupId, artifactId, version, name, description, URL, license, developers, SCM. All are configured in `maestro.library-conventions.gradle.kts`.

### Javadoc workflow fails

- **Java 25 not found**: Verify `temurin` distribution supports Java 25 in `actions/setup-java`. Fallback: switch to `zulu` or `oracle`.
- **Classpath errors**: The `aggregateJavadoc` task collects classpaths from all library modules. If a new dependency causes resolution issues, check that all modules compile first (`./gradlew classes`).

### CI build times are too long

- Testcontainers downloads container images on first run. GitHub-hosted runners are ephemeral, so Docker images are re-pulled on each run unless explicit caching is configured.
- Expected CI time: 8-15 minutes. If consistently over 20 minutes, consider splitting Testcontainers tests into a separate job.
