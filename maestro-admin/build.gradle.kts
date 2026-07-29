plugins {
    id("maestro.spring-app-conventions")
}

description = "Maestro Admin — Standalone workflow dashboard"

dependencies {
    implementation(project(":maestro-admin-client"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.thymeleaf)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.actuator)
    // spring-boot-starter-kafka (not the bare spring-kafka library) is required:
    // it pulls in Spring Boot's KafkaAutoConfiguration, which is what actually
    // creates the KafkaTemplate<String, byte[]> and ConsumerFactory<String, byte[]>
    // beans from the spring.kafka.* properties in application.yml. Without it,
    // AdminCommandService and AdminEventConsumer fail to wire at all — see
    // DashboardSmokeMockMvcTest / EventIngestionRoundTripTest, which caught this
    // as a context-startup failure (Issue 10b).
    implementation(libs.spring.boot.starter.kafka)
    // Same modular-autoconfiguration gap as Kafka above: flyway-core alone never
    // runs a migration for this app. spring-boot-starter-flyway is what pulls in
    // Spring Boot's FlywayAutoConfiguration, which actually invokes Flyway against
    // the DataSource on startup (see maestro-samples/sample-postgres-only, which
    // documents the same requirement). Without it, every admin_* table is missing
    // and every repository query fails with "relation does not exist" — caught by
    // the same two test classes as the Kafka gap above (Issue 10b).
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgres)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.testcontainers.junit5)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
}
