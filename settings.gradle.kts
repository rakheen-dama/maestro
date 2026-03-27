rootProject.name = "maestro"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven { url = uri("https://repo.spring.io/milestone") }
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://repo.spring.io/milestone") }
    }
}

include(
    "maestro-core",
    "maestro-spring-boot-starter",
    "maestro-store-jdbc",
    "maestro-store-postgres",
    "maestro-messaging-kafka",
    "maestro-lock-valkey",
    "maestro-test",
    "maestro-admin",
    "maestro-admin-client",
    "maestro-samples:sample-stokvel-service",
    "maestro-samples:sample-core-banking-proxy",
)
