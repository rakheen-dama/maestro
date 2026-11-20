plugins {
    id("maestro.java-conventions")
}

description = "Rolling-upgrade test node: one Maestro node, compiled against this build and against each previous release (not published)"

// ---------------------------------------------------------------------------
// The rolling-upgrade test (maestro-integration-tests, task rollingUpgradeTest)
// runs a node of a previous release and a node of this build at the same time,
// against one database. Both nodes are this project's one source set, compiled
// twice: `main` against this build's modules, and once per row below against
// that release's published artifacts. One source for both is the frozen-workflow
// rule: the workflows both builds run are the same code, so the source may use
// only API present in the oldest row (the public workflow-author API, the
// starter's MaestroClient and the EngineObserver SPI).
//
// One row per previous release, oldest supported first. A release adds a row; a
// row leaves only when its release falls out of the support window. The Spring
// Boot version is the one that release was built and tested with: its published
// Gradle module metadata leaves Spring versions to the consumer's platform.
// ---------------------------------------------------------------------------
data class PreviousRelease(val maestroVersion: String, val springBootVersion: String) {
    val id: String = maestroVersion.replace(Regex("[^A-Za-z0-9]"), "_")
}

val previousReleases = listOf(
    PreviousRelease(maestroVersion = "0.3.0-rc.1", springBootVersion = "4.0.5"),
    PreviousRelease(maestroVersion = "0.3.0-rc.2", springBootVersion = "4.0.5"),
)

// Where the previous releases come from. Default: a final release from Maven
// Central, a pre-release from this repository's GitHub Packages registry, with
// the credentials RELEASING.md § "Consuming one" names
// (gpr.user / gpr.token, else GITHUB_ACTOR / GITHUB_TOKEN; in GitHub Actions the
// job's token with `packages: read`). With -Pmaestro.rolling.oldRepo=<dir>, a
// local Maven repository instead, for a machine without a `read:packages`
// token: publish the release tag's checkout into it with
// `./gradlew publishToMavenLocal -Pversion=<v> -Dmaven.repo.local=<dir>`.
// Nothing here resolves at configuration time, so `./gradlew build` (which
// compiles only `main`) never needs the network or a token.
val oldRepo = providers.gradleProperty("maestro.rolling.oldRepo")
val oldBuildSource = oldRepo.map { "local Maven repository $it" }
    .orElse("GitHub Packages (pre-releases) or Maven Central (final releases)")

// A pre-release (a version with a hyphen suffix, 0.3.0-rc.1) is published only
// to GitHub Packages, a final release (0.3.0) only to Maven Central
// (RELEASING.md), so each previous release resolves from the one repository
// its version was published to; no Maestro version comes from both.
val preRelease = ".*-.*"

repositories {
    if (oldRepo.isPresent) {
        mavenCentral {
            content { excludeGroup("io.b2mash.maestro") }
        }
        exclusiveContent {
            forRepository {
                maven {
                    name = "maestroPreviousReleasesLocal"
                    url = uri(file(oldRepo.get()))
                }
            }
            filter { includeGroup("io.b2mash.maestro") }
        }
    } else {
        mavenCentral {
            // Final releases of Maestro come from here; pre-releases never do.
            content { excludeVersionByRegex("io\\.b2mash\\.maestro", ".*", preRelease) }
        }
        maven {
            name = "maestroGitHubPackages"
            // The registry the pre-releases are published to (publish-github-packages.yml),
            // also from a fork's checkout; -PgprRepository=<owner>/<repo> overrides it.
            val repository = providers.gradleProperty("gprRepository").orElse("rakheen-dama/maestro")
            url = uri("https://maven.pkg.github.com/${repository.get()}")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.token")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
            // Only Maestro's pre-releases; nothing else is looked up here.
            content { includeVersionByRegex("io\\.b2mash\\.maestro", ".*", preRelease) }
        }
    }
}

// The node's external dependencies, the same for every build of it.
val nodeLibraries = listOf(
    "org.springframework.boot:spring-boot-starter-jdbc",
    "org.springframework.boot:spring-boot-starter-jackson",
)

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(project(":maestro-spring-boot-starter"))
    implementation(project(":maestro-store-postgres"))
    // The Kafka transport, for the scenarios that send signals through Kafka;
    // every other scenario launches its nodes with Maestro's Kafka
    // auto-configuration excluded, so they need no broker.
    implementation(project(":maestro-messaging-kafka"))
    nodeLibraries.forEach { implementation(it) }
    runtimeOnly(libs.postgresql)
}

val nodeCompiler = javaToolchains.compilerFor {
    languageVersion = JavaLanguageVersion.of(25)
}

val previousReleaseNodes = previousReleases.map { release ->
    val classpath = configurations.create("rollingNode_${release.id}") {
        description = "Runtime classpath of the rolling-upgrade node built against Maestro ${release.maestroVersion}."
        isCanBeConsumed = false
        isCanBeResolved = true
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
            attribute(
                TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                objects.named(TargetJvmEnvironment.STANDARD_JVM),
            )
            attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
        }
    }
    dependencies {
        add(classpath.name, platform("org.springframework.boot:spring-boot-dependencies:${release.springBootVersion}"))
        add(classpath.name, platform("io.b2mash.maestro:maestro-bom:${release.maestroVersion}"))
        add(classpath.name, "io.b2mash.maestro:maestro-spring-boot-starter")
        add(classpath.name, "io.b2mash.maestro:maestro-store-postgres")
        add(classpath.name, "io.b2mash.maestro:maestro-messaging-kafka")
        nodeLibraries.forEach { add(classpath.name, it) }
        add(classpath.name, "org.postgresql:postgresql")
    }
    val compile = tasks.register<JavaCompile>("compileRollingNode_${release.id}") {
        description = "Compiles the rolling-upgrade node against Maestro ${release.maestroVersion}."
        source(sourceSets.main.get().java)
        this.classpath = classpath
        destinationDirectory = layout.buildDirectory.dir("rolling-node/${release.id}/classes")
        javaCompiler = nodeCompiler
    }
    Triple(release, classpath, compile)
}

// The manifest the rolling-upgrade test reads: each node's runtime classpath
// (this build's, then one per previous release) and where the previous
// releases came from. Running it resolves the previous releases, so only the
// rollingUpgradeTest task depends on it.
tasks.register<WriteProperties>("rollingNodeManifest") {
    description = "Builds every rolling-upgrade node and writes their classpaths for the rolling-upgrade test."
    destinationFile = layout.buildDirectory.file("rolling-node/manifest.properties")
    val current = sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
    inputs.files(current).withPropertyName("currentNodeClasspath")
    property("current.classpath", provider { current.asPath })
    property("previous.source", oldBuildSource)
    property("previous.versions", previousReleases.joinToString(",") { it.maestroVersion })
    previousReleaseNodes.forEach { (release, classpath, compile) ->
        val nodeClasspath = files(compile.flatMap { it.destinationDirectory }, classpath)
        inputs.files(nodeClasspath).withPropertyName("previousNodeClasspath_${release.id}")
        property("previous.${release.maestroVersion}.classpath", provider { nodeClasspath.asPath })
    }
}
