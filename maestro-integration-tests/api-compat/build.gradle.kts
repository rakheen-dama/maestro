import me.champeau.gradle.japicmp.JapicmpTask

plugins {
    `java-base`
    id("me.champeau.gradle.japicmp") version "0.4.6"
}

fun required(name: String): String = providers.gradleProperty(name).orNull
    ?: throw GradleException("Set -P$name (see settings.gradle.kts)")

val oldVersion = required("maestro.oldVersion")
val newVersion = required("maestro.newVersion")
// The published module metadata leaves Spring versions to the consumer's
// platform; resolve both versions under the one the build uses.
val springBootVersion = providers.gradleProperty("maestro.springBootVersion").getOrElse("4.0.5")
// Until 1.0 the report never fails the build. -Pmaestro.apiCompat.fail=true
// makes a binary- or source-incompatible change to the PUBLIC API fail it
// (RELEASING.md § "API compatibility report").
val failOnBreak = providers.gradleProperty("maestro.apiCompat.fail").map { it.toBoolean() }.getOrElse(false)

/** The published library modules (RELEASING.md § Published Modules); the BOM has no classes. */
val modules = listOf(
    "maestro-core",
    "maestro-spring-boot-starter",
    "maestro-store-jdbc",
    "maestro-store-postgres",
    "maestro-messaging-kafka",
    "maestro-admin-client",
    "maestro-test",
)

/**
 * Internal until 1.0 (docs/support-matrix.md): the WorkflowStore/WorkflowMessaging/
 * DistributedLock SPI, the loop supervisor, and the engine's implementation
 * package. Their changes are reported in a section of their own and are never
 * part of the public-API verdict.
 */
val internalPackages = listOf(
    "io.b2mash.maestro.core.spi", "io.b2mash.maestro.core.spi.*",
    "io.b2mash.maestro.core.loop", "io.b2mash.maestro.core.loop.*",
    "io.b2mash.maestro.core.engine", "io.b2mash.maestro.core.engine.*",
)

fun runtimeClasspath(side: String, module: String, version: String): Configuration {
    val name = "${side}_${module}_${version}".replace(Regex("[^A-Za-z0-9_]"), "_")
    val configuration = configurations.create(name) {
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
        add(name, platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
        add(name, "io.b2mash.maestro:$module:$version")
    }
    return configuration
}

val reportDir = layout.buildDirectory.dir("reports/api-compat")

val comparisons = modules.flatMap { module ->
    val oldClasspath = runtimeClasspath("old", module, oldVersion)
    val newClasspath = runtimeClasspath("new", module, newVersion)
    listOf("public", "internal").map { scope ->
        tasks.register<JapicmpTask>("japicmp_${module}_$scope") {
            description = "Compares the $scope API of $module $oldVersion with $newVersion."
            this.oldClasspath.from(oldClasspath)
            this.newClasspath.from(newClasspath)
            oldArchives.from(oldClasspath.filter { it.name == "$module-$oldVersion.jar" })
            newArchives.from(newClasspath.filter { it.name == "$module-$newVersion.jar" })
            onlyModified = true
            // The published runtime classpath leaves out the modules Maestro
            // declares compileOnly (Spring Boot's health and SQL-init modules,
            // Micrometer Tracing); a type that only extends one of those is
            // compared as far as it is visible.
            ignoreMissingClasses = true
            if (scope == "public") {
                packageExcludes = internalPackages
                failOnSourceIncompatibility = failOnBreak
            } else {
                packageIncludes = internalPackages
            }
            mdOutputFile = reportDir.map { it.file("$module-$scope.md") }
            txtOutputFile = reportDir.map { it.file("$module-$scope.txt") }
        }
    }
}

tasks.register("apiCompatReport") {
    description = "Writes build/reports/api-compat/summary.md: every published module's API changes, $oldVersion -> $newVersion."
    dependsOn(comparisons)
    val summary = reportDir.map { it.file("summary.md") }
    outputs.file(summary)
    outputs.upToDateWhen { false }
    doLast {
        val dir = reportDir.get().asFile
        val text = StringBuilder()
        text.append("# API compatibility: Maestro $oldVersion -> $newVersion\n\n")
        text.append("Reported by japicmp over the published jars. Until 1.0 this report never fails the build.\n")
        text.append("Internal until 1.0 (reported separately, not part of the verdict): ")
        text.append(internalPackages.filterNot { it.endsWith("*") }.joinToString { "`$it`" }).append(".\n\n")
        for (scope in listOf("public", "internal")) {
            text.append("## ").append(if (scope == "public") "Public API" else "Internal packages").append("\n\n")
            text.append("| Module | Changed classes | Classes with an incompatible change |\n|---|---|---|\n")
            for (module in modules) {
                // japicmp's text report: one unindented line per changed class,
                // "***" modified, "+++" new, "---" removed; "!" after the marker
                // flags a binary-incompatible change.
                val classes = dir.resolve("$module-$scope.txt").readLines()
                    .filter { it.startsWith("***") || it.startsWith("+++") || it.startsWith("---") }
                val incompatible = classes.filter { it.startsWith("***!") || it.startsWith("---!") }
                    .map { line -> "`" + (Regex("""io\.b2mash\.\S+""").find(line)?.value ?: line) + "`" }
                text.append("| `$module` | ${classes.size} | ")
                    .append(if (incompatible.isEmpty()) "none" else "**${incompatible.size}**: " + incompatible.joinToString())
                    .append(" |\n")
            }
            text.append('\n')
        }
        text.append("Per-module detail: `build/reports/api-compat/<module>-<public|internal>.md`.\n")
        summary.get().asFile.writeText(text.toString())
        println(text)
    }
}
