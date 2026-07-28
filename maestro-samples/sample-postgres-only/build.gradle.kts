plugins {
    id("maestro.spring-app-conventions")
}

description = "Sample — Document approval workflow using Postgres only (no Kafka, no Valkey)"

dependencies {
    implementation(project(":maestro-spring-boot-starter"))
    implementation(project(":maestro-store-postgres"))
    implementation(project(":maestro-messaging-postgres"))
    implementation(project(":maestro-lock-postgres"))
    implementation(libs.spring.boot.starter.webmvc)
    runtimeOnly(libs.postgresql)
    // Spring Boot 4 modular auto-configuration: JDBC (DataSource) and Flyway
    // (maestro_* schema from maestro-store-postgres) each live in their own
    // starter and must be declared explicitly.
    runtimeOnly(libs.spring.boot.starter.jdbc)
    runtimeOnly(libs.spring.boot.starter.flyway)
}
