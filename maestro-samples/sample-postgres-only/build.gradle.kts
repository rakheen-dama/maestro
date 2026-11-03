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
    // Spring Boot 4 modular auto-configuration: JDBC (DataSource) lives in its
    // own starter and must be declared explicitly. Maestro applies its own schema
    // (maestro_schema_history) via maestro-store-postgres; no Flyway starter needed.
    runtimeOnly(libs.spring.boot.starter.jdbc)

    testImplementation(project(":maestro-test"))
}
