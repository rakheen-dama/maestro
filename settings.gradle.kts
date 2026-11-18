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
    "maestro-bom",
    "maestro-test",
    "maestro-integration-tests",
    // The node the rolling-upgrade test launches, built twice: against this
    // build and against each previous release (not published).
    "maestro-integration-tests:rolling-node",
    "maestro-admin",
    "maestro-admin-client",
    "maestro-samples:sample-order-service",
    "maestro-samples:sample-payment-gateway",
    "maestro-samples:sample-perf",
    "maestro-samples:sample-loan-origination:loan-application-service",
    "maestro-samples:sample-loan-origination:verification-gateway-service",
    "maestro-samples:sample-loan-origination:underwriting-service",
)
