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
}
