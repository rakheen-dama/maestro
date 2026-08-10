plugins {
    id("maestro.spring-library-conventions")
}

description = "Maestro Store Postgres — PostgreSQL WorkflowStore with Flyway migrations"

dependencies {
    api(project(":maestro-store-jdbc"))
    implementation(libs.postgresql)

    // Spring auto-configuration — contributes the WorkflowStore bean when a
    // DataSource is present (mirrors maestro-lock-valkey / maestro-messaging-kafka).
    implementation(project(":maestro-spring-boot-starter"))
    implementation(libs.spring.boot.autoconfigure)

    // Flyway — this module owns the migration SQL files in src/main/resources/db/migration/
    // Consumers get Flyway transitively so they don't need to declare it separately.
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgres)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.jdbc)
    testImplementation(libs.testcontainers.junit5)
    testImplementation(libs.testcontainers.postgresql)
}
