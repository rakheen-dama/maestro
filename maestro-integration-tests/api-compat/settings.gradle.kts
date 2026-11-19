// The API compatibility report: a standalone Gradle build, NOT part of the
// Maestro build. It compares the published jars of two Maestro versions with
// japicmp (me.champeau.gradle.japicmp) and writes one Markdown report.
//
//   old: -Pmaestro.oldVersion=<last release>, from GitHub Packages
//        (gpr.user/gpr.token or GITHUB_ACTOR/GITHUB_TOKEN), or from
//        -Pmaestro.oldRepo=<local Maven repository>
//   new: -Pmaestro.newVersion=<version> from -Pmaestro.newRepo=<local Maven
//        repository>, which `publishToMavenLocal -Dmaven.repo.local=<dir>`
//        of this checkout fills
//
//   ./gradlew -p maestro-integration-tests/api-compat apiCompatReport \
//       -Pmaestro.oldVersion=0.3.0-rc.1 -Pmaestro.newVersion=0.0.0-api-check -Pmaestro.newRepo=<dir>
//
// It reports and never fails, until 1.0 (RELEASING.md § "API compatibility
// report", which also says how to make it fail).
rootProject.name = "maestro-api-compat"

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

val oldRepo = providers.gradleProperty("maestro.oldRepo")
val newRepo = providers.gradleProperty("maestro.newRepo")
val oldVersion = providers.gradleProperty("maestro.oldVersion")
val newVersion = providers.gradleProperty("maestro.newVersion")

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral {
            content { excludeGroup("io.b2mash.maestro") }
        }
        // Each version of Maestro comes from exactly one repository.
        if (newRepo.isPresent && newVersion.isPresent) {
            maven {
                name = "maestroNew"
                url = uri(file(newRepo.get()))
                content { includeVersionByRegex("io\\.b2mash\\.maestro", ".*", Regex.escape(newVersion.get())) }
            }
        }
        if (oldVersion.isPresent) {
            maven {
                name = "maestroOld"
                if (oldRepo.isPresent) {
                    url = uri(file(oldRepo.get()))
                } else {
                    val repository = providers.gradleProperty("gprRepository").orElse("rakheen-dama/maestro")
                    url = uri("https://maven.pkg.github.com/${repository.get()}")
                    credentials {
                        username = providers.gradleProperty("gpr.user")
                            .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                        password = providers.gradleProperty("gpr.token")
                            .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
                    }
                }
                content { includeVersionByRegex("io\\.b2mash\\.maestro", ".*", Regex.escape(oldVersion.get())) }
            }
        }
    }
}
