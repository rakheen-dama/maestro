plugins {
    id("maestro.spring-library-conventions")
}

description = "Maestro Lock Valkey — Lettuce-based DistributedLock and SignalNotifier implementation"

dependencies {
    api(project(":maestro-core"))
    api(libs.lettuce.core)

    implementation(project(":maestro-spring-boot-starter"))
    implementation(libs.spring.boot.autoconfigure)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.junit5)
    testImplementation(libs.awaitility)
}
