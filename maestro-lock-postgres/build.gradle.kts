plugins {
    id("maestro.spring-library-conventions")
}

description = "PostgreSQL-based DistributedLock SPI implementation"

dependencies {
    api(project(":maestro-core"))
    implementation(project(":maestro-spring-boot-starter"))
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.postgresql)
    // Applies this module's own V100 migration before the contract suite runs.
    testImplementation(libs.flyway.core)
    testRuntimeOnly(libs.flyway.postgres)
}
