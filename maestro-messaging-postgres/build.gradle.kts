plugins {
    id("maestro.spring-library-conventions")
}

description = "PostgreSQL-based WorkflowMessaging SPI implementation using LISTEN/NOTIFY"

dependencies {
    api(project(":maestro-core"))
    implementation(project(":maestro-spring-boot-starter"))
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.postgresql)
    implementation(libs.jackson.databind)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.postgresql)
}
