# Maven Migration — Comparison Scaffold

This branch (`maven-migration`) contains a complete, working Maven build alongside the
existing Gradle build so the two can be compared directly. Nothing was removed — a real
migration would delete `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`,
`gradle/`, `gradlew*`, `build-logic/`, and `buildSrc/` as a final step.

## What maps to what

| Gradle | Maven |
|---|---|
| `settings.gradle.kts` (module list) | Root `pom.xml` `<modules>` |
| `gradle.properties` (`version=`) | Root `pom.xml` `<revision>` property |
| `gradle/libs.versions.toml` | Root `pom.xml` `<properties>` + `<dependencyManagement>` |
| `build-logic/maestro.java-conventions` | Root POM compiler/surefire config + inherited baseline `<dependencies>` |
| `build-logic/maestro.library-conventions` | Root POM metadata (licenses/developers/scm) + `release` profile |
| `build-logic/maestro.spring-library-conventions` | Per-module Spring Boot BOM import (`<scope>import</scope>` — no inheritance needed) |
| `build-logic/maestro.spring-app-conventions` | Per-module `spring-boot-maven-plugin` `repackage` + skip-publishing properties |
| `io.github.gradle-nexus.publish-plugin` | `central-publishing-maven-plugin` (Central Portal) in the `release` profile |
| `aggregateJavadoc` custom task | `mvn javadoc:aggregate` (built in; output: `target/reports/apidocs`) |
| `./gradlew` wrapper | `./mvnw` wrapper (`.mvn/wrapper/`) |

## Command equivalents

| Task | Gradle | Maven |
|---|---|---|
| Full build + tests | `./gradlew build` | `./mvnw verify` |
| Build without tests | `./gradlew build -x test` | `./mvnw -DskipTests package` |
| One module's tests | `./gradlew :maestro-core:test` | `./mvnw -pl maestro-core test` |
| Run a sample | `./gradlew :maestro-samples:sample-order-service:bootRun` | `./mvnw -pl maestro-samples/sample-order-service spring-boot:run` |
| Aggregate Javadoc | `./gradlew aggregateJavadoc` | `./mvnw -DskipTests compile javadoc:aggregate` |
| Release (CI) | `./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository -Pversion=X.Y.Z` | `./mvnw deploy -Prelease -Drevision=X.Y.Z` |

## Versioning

Single source of truth is the `<revision>` property in the root POM (CI-friendly
versioning). Every module inherits/declares `${revision}`. Releases override it from
the git tag: `mvn -Drevision=0.3.0 deploy -Prelease`.

The **flatten-maven-plugin is mandatory** with this scheme: without it, installed and
published POMs contain the literal string `${revision}`. It runs on every build
(`process-resources` phase) and writes a resolved `.flattened-pom.xml` (gitignored)
that Maven installs/deploys instead of the source POM.

> **Gotcha found while building this scaffold:** `flattenMode=resolveCiFriendliesOnly`
> (the commonly recommended mode) resolves the project/parent version but leaves
> `${revision}` **unresolved in inter-module `<dependency>` versions**, and strips the
> property that would resolve it — producing broken POMs on Central. This branch uses
> `flattenMode=ossrh`, which fully resolves all versions and keeps the metadata
> Central requires. Verified by building with `-Drevision=9.9.9` and inspecting the
> flattened POMs.

## Known fidelity losses vs. Gradle (accepted trade-offs)

1. **`api` vs `implementation` is gone.** Maven's `compile` scope leaks every
   dependency into consumers' compile classpaths. Dependencies that were
   `implementation` in Gradle (e.g. `jackson-databind` in `maestro-store-jdbc`,
   `postgresql` in the lock/messaging modules) are now ordinary `compile` deps and
   are marked with a comment in each POM. Consumers will see them on their compile
   classpath; previously they were runtime-only in the published POMs.

2. **Version-conflict precedence differs inside Spring modules.** In Gradle, an
   explicit catalog version beats the Spring Boot BOM. In Maven, a module's imported
   BOM beats the parent's `dependencyManagement`. Where the Gradle build visibly
   pinned something the BOM also manages (jackson, lettuce, postgresql, flyway,
   testcontainers), the POMs pin the version explicitly on the dependency to match.
   Transitive versions inside Spring modules may still differ slightly — e.g. the
   inherited JUnit test dependency resolves via the Spring BOM there.

3. **No configuration cache / build cache.** Full local build is ~18s warm on this
   machine vs. Gradle's near-instant up-to-date checks. `-T 1C` enables parallel
   module builds; there is no incremental avoidance across invocations.

## Publishing

- All 11 library modules are published; `maestro-admin` and the 4 samples set
  `skipPublishing` / `maven.deploy.skip` / `gpg.skip` / source / javadoc skip
  properties (5 lines of boilerplate per unpublished module — the Maven equivalent
  of simply not applying a convention plugin).
- The root `maestro-parent` POM is also published (standard for Maven parents),
  although `flattenMode=ossrh` inlines everything so consumer POMs don't reference it.
- The `release` profile attaches sources + javadoc JARs, signs with GPG
  (loopback pinentry for CI), and uploads via `central-publishing-maven-plugin`
  with `autoPublish` — no manual staging-repo close/release step.
- CI credentials: `actions/setup-java` writes the `central` server entry to
  `~/.m2/settings.xml` and imports the GPG key (see `.github/workflows/release.yml`).

## Verified on this branch

- `mvn -DskipTests package` — all 17 modules build; sample/admin JARs are repackaged
  as executable Boot JARs.
- `mvn -pl maestro-core test` — 155 tests pass (Surefire 3.5.4 + JUnit 6).
- `mvn -Drevision=9.9.9 process-resources` — flattened POMs carry the overridden
  version everywhere, including inter-module dependencies.
- `mvn -DskipTests compile javadoc:aggregate` — output at `target/reports/apidocs`,
  covering exactly the library modules (admin app + samples excluded).
- `./mvnw verify` with Docker running — full build green in 1:31, zero failures.
  All Testcontainers suites executed: `maestro-store-postgres` (Postgres),
  `maestro-messaging-kafka` (Kafka), `maestro-lock-valkey` (Valkey).
  `maestro-messaging-postgres`, `maestro-messaging-rabbitmq`, and
  `maestro-lock-postgres` have no test sources yet (true under Gradle too),
  so their near-instant module times are expected, not skipped tests.

Not verified locally: an actual deploy to Central.
