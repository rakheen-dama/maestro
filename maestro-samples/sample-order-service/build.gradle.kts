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
    // Spring Boot 4 modular auto-configuration: JDBC (DataSource), Flyway
    // (maestro_* schema from maestro-store-postgres), and Kafka
    // (KafkaTemplate + spring.kafka.* binding) each live in their own
    // starter and must be declared explicitly.
    runtimeOnly(libs.spring.boot.starter.jdbc)
    runtimeOnly(libs.spring.boot.starter.flyway)
    runtimeOnly(libs.spring.boot.starter.kafka)
}
