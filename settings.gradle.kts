rootProject.name = "maestro"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    "maestro-core",
    "maestro-spring-boot-starter",
    "maestro-store-jdbc",
    "maestro-store-postgres",
    "maestro-messaging-kafka",
    "maestro-messaging-postgres",
    "maestro-messaging-rabbitmq",
    "maestro-lock-valkey",
    "maestro-lock-postgres",
    "maestro-test",
    "maestro-admin",
    "maestro-admin-client",
    "maestro-samples:sample-order-service",
    "maestro-samples:sample-payment-gateway",
)
