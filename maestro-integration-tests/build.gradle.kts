plugins {
    id("maestro.integration-test-conventions")
}

description = "Maestro Integration Tests — engine against real Postgres, Kafka and lock backends (not published)"

dependencies {
    // Subjects under test — the real engine and the real backend adapters.
    testImplementation(project(":maestro-core"))
    testImplementation(project(":maestro-spring-boot-starter"))
    testImplementation(project(":maestro-store-postgres"))
    testImplementation(project(":maestro-messaging-kafka"))
    testImplementation(project(":maestro-messaging-postgres"))
    testImplementation(project(":maestro-lock-postgres"))
    testImplementation(project(":maestro-lock-valkey"))

    // Fixtures only — never the subject of an integration assertion.
    testImplementation(project(":maestro-test"))

    testImplementation(libs.jackson.databind)
    testImplementation(libs.postgresql)
    testImplementation(libs.flyway.core)
    testRuntimeOnly(libs.flyway.postgres)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.jdbc)
    testImplementation(libs.spring.kafka)
    testImplementation(libs.spring.kafka.test)

    testImplementation(libs.testcontainers.junit5)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.awaitility)
}
