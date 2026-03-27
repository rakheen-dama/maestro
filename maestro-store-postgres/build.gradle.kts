plugins {
    id("maestro.library-conventions")
}

description = "Maestro Store Postgres — PostgreSQL WorkflowStore with Flyway migrations"

dependencies {
    api(project(":maestro-store-jdbc"))
    runtimeOnly(libs.postgresql)

    // Flyway — this module owns the migration SQL files in src/main/resources/db/migration/
    // Consumers get Flyway transitively so they don't need to declare it separately.
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgres)

    testImplementation(libs.testcontainers.junit5)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.postgresql)
}
