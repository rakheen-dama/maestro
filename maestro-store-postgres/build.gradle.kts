plugins {
    id("maestro.spring-library-conventions")
}

description = "Maestro Store Postgres — PostgreSQL WorkflowStore, distributed lock and LISTEN/NOTIFY signal wake, with Maestro-owned Flyway migrations"

dependencies {
    api(project(":maestro-store-jdbc"))
    implementation(libs.postgresql)

    // Spring auto-configuration — contributes the WorkflowStore, DistributedLock
    // and SignalNotifier beans when a DataSource is present (mirrors
    // maestro-messaging-kafka).
    implementation(project(":maestro-spring-boot-starter"))
    implementation(libs.spring.boot.autoconfigure)
    // @DependsOnDatabaseInitialization only — present at runtime wherever a
    // DataSource is (spring-boot-jdbc depends on it), and an annotation whose
    // class is absent is simply ignored.
    compileOnly(libs.spring.boot.sql)

    // Flyway — MaestroSchemaInitializer applies the migration SQL in
    // src/main/resources/db/maestro/{store,lock}/ (and any other module's
    // META-INF/maestro/schema-locations) into maestro_schema_history.
    // Both are runtime dependencies of every consumer, so hosts need neither
    // Flyway nor spring-boot-starter-flyway for Maestro's tables.
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgres)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.jdbc)
    // The host-Flyway ordering test runs Boot's FlywayAutoConfiguration
    // beside this module's auto-configuration.
    testImplementation(libs.spring.boot.starter.flyway)
    testImplementation(libs.testcontainers.junit5)
    testImplementation(libs.testcontainers.postgresql)
    // NOTIFY is delivered asynchronously; the wake tests poll for it.
    testImplementation(libs.awaitility)
}

// ---------------------------------------------------------------------------
// docs/configuration.md is an input of `test`.
//
// MaestroSchemaInitializerTest.theDocumentedSqlIsTheTestedSql reads the
// production grants printed in docs/configuration.md (§ Schema management, "In
// production") from disk and checks they are the SQL its role tests execute.
// The file is outside this module and off the test classpath, so without this
// declaration a doc-only edit would leave `test` UP-TO-DATE, or restore it from
// the build cache (org.gradle.caching=true), and the guard would never run.
// ---------------------------------------------------------------------------
tasks.named<Test>("test") {
    inputs.file(rootProject.layout.projectDirectory.file("docs/configuration.md"))
        .withPathSensitivity(PathSensitivity.NONE).withPropertyName("documentedProductionGrants")
}
