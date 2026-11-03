plugins {
    id("maestro.spring-app-conventions")
}

description = "Sample — Order fulfilment workflow with cross-service signals and saga compensation"

dependencies {
    implementation(project(":maestro-spring-boot-starter"))
    implementation(project(":maestro-store-postgres"))
    implementation(project(":maestro-messaging-kafka"))
    implementation(project(":maestro-lock-valkey"))
    implementation(libs.spring.boot.starter.webmvc)
    runtimeOnly(libs.postgresql)
    // Spring Boot 4 modular auto-configuration: JDBC (DataSource) and Kafka
    // (KafkaTemplate + spring.kafka.* binding) each live in their own
    // starter and must be declared explicitly. Maestro applies its own schema
    // (maestro_schema_history) via maestro-store-postgres; no Flyway starter needed.
    runtimeOnly(libs.spring.boot.starter.jdbc)
    runtimeOnly(libs.spring.boot.starter.kafka)

    testImplementation(project(":maestro-test"))
}
