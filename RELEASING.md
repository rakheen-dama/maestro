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
| `maestro-lock-valkey` | `io.b2mash.maestro:maestro-lock-valkey` |
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
