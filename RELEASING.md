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

Then add the upgrade fixtures for the new tag (see [Upgrade fixtures](#upgrade-fixtures)).

---

## Upgrade fixtures

Maestro's tests hold frozen evidence of what earlier commits shipped and wrote, so that no later change can silently break a database an earlier build migrated, or the workflow history in it. A fixture records one commit, under one name:

| Fixture | Files | Pinned by |
|---|---|---|
| Migrations | `maestro-store-postgres/src/test/resources/upgrade/<name>/` — every migration the commit shipped (`store/`, `lock/`, and `messaging/` before the transport was removed), byte for byte, plus `SOURCE` (ref, capture time, SHA-256 of each file) | `ShippedMigrationsFrozenTest`: each `SOURCE` by a SHA-256 constant, each copy against `SOURCE`, each still-shipped migration against its copy (line endings folded). `MaestroSchemaInitializerTest`: a database the frozen set migrated is taken over by this build |
| Seeded history | `maestro-integration-tests/src/test/resources/upgrade/<name>/seeded-history.sql` (a `pg_dump` of every `maestro_*` table: DDL, `maestro_schema_history`, rows) and `capture.properties` (ref, capture time, server version, SHA-256 of the frozen workflow source), produced by the frozen package `io.b2mash.maestro.integration.upgrade.<name>` (`SeededHistoryWorkflows`, the `SeededHistoryCapture` driver) | `SeededHistoryUpgradeIT`: the manifest's SHA-256 and the dump's SHA-256 against the constants in `SeededHistory` (`CAPTURE_MANIFEST_SHA256`, `DUMP_SHA256`), the manifest's `ref`, and the frozen source against the manifest's SHA-256; then this build upgrades, recovers and replays the history |

The fixtures today: `cbf5a2a` (migrations and seeded history) and `c01d498` (migrations only: the last set that shipped the retired V200–V202 band).

### Adding a fixture at a release tag

Use one name per tag for both kinds. A seeded-history name is also a Java package segment (`[a-z][a-z0-9_]*`), so tag `v0.5.0` takes the name `v0_5_0`. Run everything from the repository root, with Docker running.

1. **Capture the migrations:** `scripts/capture-upgrade-fixture.sh migrations v0.5.0 v0_5_0`. It writes `maestro-store-postgres/src/test/resources/upgrade/v0_5_0/` from the tag's git objects, never from the working tree. Pin it the way `cbf5a2a` is pinned: a `SOURCE` SHA-256 constant in `ShippedMigrationsFrozenTest`, and a test in `MaestroSchemaInitializerTest` that migrates a database with the frozen set (`FrozenMigrations.named("v0_5_0")`) and then runs this build's initializer in `flyway` and `validate` mode.
2. **Copy the latest frozen package** (today `io.b2mash.maestro.integration.upgrade.cbf5a2a`) to `io.b2mash.maestro.integration.upgrade.v0_5_0`.
   - **`SeededHistoryWorkflows`**: change only its `package` line. It defines how the history is produced, and the capture hashes it into `capture.properties`. It must use only the public workflow-author API of `maestro-core` (annotations, `WorkflowContext`, activity interfaces, the model and exception types), so that it compiles unchanged at the tag.
   - **`SeededHistoryCapture`**: change its `package` line, its imports, and the two path constants `FIXTURE_SOURCE` and `FIXTURE_PACKAGE`, which name the package's directory. The constants matter. The driver refuses a checkout that differs from the tag anywhere outside `FIXTURE_PACKAGE`, and it records the SHA-256 of the file at `FIXTURE_SOURCE`. The tag's own tree already holds the older packages, so a stale `FIXTURE_SOURCE` would record the hash of the wrong file. The driver compiles against the tag's code, so it may use only APIs present there. Where the test harness it drives (`MaestroEngineHarness`, `TerminalWait`) differs at the tag, adapt the driver, and only the driver.
   - Add a `-text` line for the new `SeededHistoryWorkflows.java` to `.gitattributes`. The resources are already covered there by `upgrade/**`.
3. **Make `SeededHistory` load more than one fixture.** There is no factory to copy: `SeededHistory` is hard-wired to `cbf5a2a`. Its dump and manifest resources (`DUMP_RESOURCE`, `MANIFEST_RESOURCE`), the pinned `DUMP_SHA256` and `CAPTURE_MANIFEST_SHA256`, the timer window of that dump (`MUST_BE_DUE`, `LATEST_MUST_BE_DUE`, `EARLIEST_MUST_STAY_PENDING`) and its single `load()` are all `cbf5a2a`'s. `node()` registers the workflow and activity types it imports from `cbf5a2a.SeededHistoryWorkflows`. So the first later fixture must first parameterize `SeededHistory` by fixture: the resources, the pinned SHA-256, the timer window and the frozen workflow types. That refactoring has not been done or rehearsed. The `cbf5a2a` tests must pass unchanged through it before the new fixture's values are added; fill in the new SHA-256s from step 4.
4. **Capture the history:** `scripts/capture-upgrade-fixture.sh history v0.5.0 v0_5_0`. The script needs `upgrade/v0_5_0/SeededHistoryCapture.java` in this checkout (it copies the package from the working tree, so it need not be committed yet) and refuses if the tag already holds a package of that name. It checks the tag out as a temporary detached worktree, copies the package in, and runs `./gradlew --no-watch-fs --no-daemon --no-build-cache :maestro-integration-tests:test --tests io.b2mash.maestro.integration.upgrade.v0_5_0.SeededHistoryCapture --rerun` there, so every task of the capture build runs at the tag and nothing comes from a build cache. The driver runs only when the script sets `MAESTRO_UPGRADE_CAPTURE_OUT`, and refuses to capture unless that checkout's `HEAD` is the tag's full SHA and every modified or untracked file is under the fixture package. The script checks that both files were written and that `capture.properties` records the tag's SHA, moves them into `maestro-integration-tests/src/test/resources/upgrade/v0_5_0/`, and prints the SHA-256 of each. It removes the temporary worktree on every exit path: success, failure, or an interrupt.
5. **Commit the fixture in its own commit,** with tests that assert it as `theDumpWasCapturedAtCbf5a2a` asserts `cbf5a2a`. Then run `./gradlew --no-watch-fs :maestro-store-postgres:test --tests '*ShippedMigrationsFrozenTest' --tests '*MaestroSchemaInitializerTest'` and `./gradlew --no-watch-fs :maestro-integration-tests:test --tests '*SeededHistoryUpgradeIT'`.

### Never regenerate or delete an existing fixture

A fixture is evidence about the past. Regenerated at a later commit, it would record what the newer build ships and writes, and the guard would then pass against exactly the regression it exists to catch. So:

- **Never edit a fixture**: not the migration copies, `SOURCE`, the dump, `capture.properties`, or the frozen `SeededHistoryWorkflows.java`. Editing the last one voids its dump, which it produced. An approved re-capture (below) is the only exception.
- **Never regenerate a fixture at a later commit, and never delete one.** A later release adds a new fixture under a new name. It never replaces one.
- **Re-capture only a defective fixture.** If the committed fixture is itself defective, it may be re-captured. That needs the maintainer's explicit approval. The re-capture deletes the old directory, runs the script at the *same* ref, and goes in its own commit, so the deletion and the new files are one reviewed change.

The script backs these rules up: it refuses to overwrite. In both modes it stops if the fixture directory exists, checks again just before it moves the new files in, and writes nothing on failure. A re-capture is not byte-identical, and it changes these pinned values, which the same commit must update:

- `DUMP_SHA256` in `SeededHistory` (the dump's bytes);
- `CAPTURE_MANIFEST_SHA256` in `SeededHistory` (the bytes of `capture.properties`, which always records a new capture time);
- the frozen-source SHA-256 in `capture.properties`, if `SeededHistoryWorkflows.java` was the defect (`theDumpWasCapturedAtCbf5a2a` checks it against the file);
- the `SOURCE` SHA-256 constants in `ShippedMigrationsFrozenTest`, for a migrations re-capture.

Reviewers should expect these differences and no others:

- **Migrations:** the `.sql` copies come from git objects and are identical. `SOURCE` records a new capture time, so its SHA-256 constant in `ShippedMigrationsFrozenTest` changes.
- **Seeded history:** UUIDs, timestamps and timer due times differ; rows can come out in a different order, because `Set.of` iteration order differs from one JVM to the next and parallel branches can write in either order. `capture.properties` records a new capture time, and a new server version if `postgres:16-alpine` has moved to a newer patch release. The dump's and the manifest's SHA-256 change, so `DUMP_SHA256` and `CAPTURE_MANIFEST_SHA256` in `SeededHistory` change.

Either way the regeneration is a visible change to a pinned constant, in a diff a reviewer reads. (The WP9 plan, `docs/superpowers/plans/2026-11-12-engine-hardening-cycle/wp09-schema-safety.md`, records how these rules were made; this section is the rule.)

**Why an edit is always caught.** Every fixture file is pinned, directly or through a manifest, by a SHA-256 literal in Java source:

- `DUMP_SHA256` in `SeededHistory` pins the bytes of `seeded-history.sql`.
- `CAPTURE_MANIFEST_SHA256` in `SeededHistory` pins the bytes of `capture.properties`, and with them the frozen `SeededHistoryWorkflows.java`: `theDumpWasCapturedAtCbf5a2a` hashes the raw bytes of the file at the manifest's `fixtureSource.path` against its `fixtureSource.sha256`, so editing the source together with that hash, or pointing the path at another file, changes the manifest and fails the literal.
- `CBF5A2A_SOURCE_SHA256` and `C01D498_SOURCE_SHA256` in `ShippedMigrationsFrozenTest` pin the two migration `SOURCE` manifests (line endings folded), and each `SOURCE` pins every migration copy beside it.

So a fixture cannot be edited or regenerated without changing one of these four literals: a regeneration always shows up in review as an edited Java constant. Gradle cannot skip these checks: `maestro-integration-tests/build.gradle.kts` declares the frozen `*/SeededHistoryWorkflows.java` sources and `src/test/resources/upgrade` as inputs of `test` and of `seededHistoryNonUtcTest` (the same suite in a non-UTC JVM), so even a comment-only edit (which leaves the bytecode unchanged) reruns the tests instead of leaving `test` up to date or restoring it from the build cache. The root `.gitattributes` marks the frozen fixtures `-text`, so git never converts their line endings on checkout and every platform reads the bytes the hashes pin.

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
