// The consumer smoke test: a standalone Gradle build, NOT part of the Maestro
// build (the repository's settings.gradle.kts does not include it). It consumes
// published Maestro artifacts exactly as a service would — the BOM, the
// starter, the Postgres store and the Kafka transport, from a Maven repository —
// and runs one workflow to completion against Testcontainers Postgres + Kafka.
//
//   ./gradlew -p maestro-integration-tests/consumer-smoke test -Pmaestro.version=0.3.0-rc.1
//       (GitHub Packages: gpr.user/gpr.token, or GITHUB_ACTOR/GITHUB_TOKEN with read:packages)
//   ./gradlew -p maestro-integration-tests/consumer-smoke test -Pmaestro.version=0.3.0-rc.1 \
//       -Pmaestro.repo=/path/to/local/maven/repository
//
// See RELEASING.md § "The consumer smoke test".
rootProject.name = "maestro-consumer-smoke"

val maestroRepo = providers.gradleProperty("maestro.repo")

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral {
            content { excludeGroup("io.b2mash.maestro") }
        }
        // Maestro's artifacts come from this repository only, never from Maven
        // Central: what is tested is what was published there.
        exclusiveContent {
            forRepository {
                if (maestroRepo.isPresent) {
                    maven {
                        name = "maestroLocal"
                        url = uri(file(maestroRepo.get()))
                    }
                } else {
                    maven {
                        name = "maestroGitHubPackages"
                        val repository = providers.gradleProperty("gprRepository").orElse("rakheen-dama/maestro")
                        url = uri("https://maven.pkg.github.com/${repository.get()}")
                        credentials {
                            username = providers.gradleProperty("gpr.user")
                                .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                            password = providers.gradleProperty("gpr.token")
                                .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
                        }
                    }
                }
            }
            filter { includeGroup("io.b2mash.maestro") }
        }
    }
}
