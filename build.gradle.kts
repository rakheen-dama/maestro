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
