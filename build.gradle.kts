import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
    base
    alias(libs.plugins.nexus.publish)
}

description = "Maestro — Embeddable Durable Workflow Engine for Spring Boot"

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl = uri("https://ossrh-staging-api.central.sonatype.com/service/local/")
            snapshotRepositoryUrl = uri("https://central.sonatype.com/repository/maven-snapshots/")
            username = providers.gradleProperty("ossrhUsername")
            password = providers.gradleProperty("ossrhPassword")
        }
    }
}

// ── Module test-coverage gate ────────────────────────────────────────────
//
// Every module with production code must carry at least one test class. The
// gap this guards against is not "low coverage" but "no tests at all", which
// is how maestro-messaging-postgres shipped in releases untested.
//
// The modules below have no tests today. They are listed explicitly so the
// gate can pass on a known baseline while still failing the moment a NEW
// untested module appears — and so the debt is visible in the build rather
// than only in a document. Remove a module from this set when it gets a test;
// the gate then keeps it honest.
val modulesWithoutTests = setOf<String>()

val verifyModuleTestCoverage = tasks.register("verifyModuleTestCoverage") {
    group = "verification"
    description = "Fails if a module with production code has no test classes."

    // Library modules only. The sample applications are demonstrations, not
    // shipped artifacts, and are covered by the loan-origination E2E instead.
    val moduleReports = subprojects.filter { it.name.startsWith("maestro-") }.map { module ->
        val hasMain = module.file("src/main/java").isDirectory
        val testDir = module.file("src/test/java")
        val testCount = if (testDir.isDirectory) {
            testDir.walkTopDown().count {
                it.isFile && it.name.endsWith(".java") &&
                    (it.name.contains("Test") || it.name.endsWith("IT.java"))
            }
        } else 0
        Triple(module.name, hasMain, testCount)
    }
    val allowlist = modulesWithoutTests

    doLast {
        val offenders = moduleReports
            .filter { (name, hasMain, count) -> hasMain && count == 0 && name !in allowlist }
            .map { it.first }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "These modules have production code but no test classes: " +
                    offenders.sorted().joinToString(", ") +
                    ". Add a test, or add the module to `modulesWithoutTests` in the root " +
                    "build with a comment explaining why."
            )
        }
        // Surface the accepted debt on every run so it does not go quiet.
        val stale = moduleReports.filter { (name, _, count) -> name in allowlist && count > 0 }
        if (stale.isNotEmpty()) {
            logger.lifecycle(
                "Module(s) now have tests and should be removed from `modulesWithoutTests`: " +
                    stale.joinToString(", ") { it.first }
            )
        }
        logger.lifecycle(
            "Test-coverage gate: ${moduleReports.count { it.second }} modules with production " +
                "code, ${allowlist.size} accepted without tests."
        )
    }
}

tasks.named("check") { dependsOn(verifyModuleTestCoverage) }

// Aggregate Javadoc across all publishable library modules
val libraryModules = listOf(
    "maestro-core",
    "maestro-store-jdbc",
    "maestro-store-postgres",
    "maestro-spring-boot-starter",
    "maestro-messaging-kafka",
    "maestro-lock-valkey",
    "maestro-admin-client",
    "maestro-test",
)

tasks.register<Javadoc>("aggregateJavadoc") {
    group = "documentation"
    description = "Aggregate Javadoc for all library modules"

    val libProjects = libraryModules.map(::project)
    dependsOn(libProjects.map { it.tasks.named("classes") })

    val mainSourceSets = libProjects.map {
        it.extensions.getByType<SourceSetContainer>()["main"]
    }
    source(mainSourceSets.map { it.allJava })
    classpath = files(mainSourceSets.map { it.compileClasspath })
    setDestinationDir(layout.buildDirectory.dir("docs/aggregateJavadoc").get().asFile)

    options {
        this as StandardJavadocDocletOptions
        addBooleanOption("Xdoclint:none", true)
        links("https://docs.oracle.com/en/java/javase/25/docs/api/")
    }
    isFailOnError = false
}
