plugins {
    id("maestro.library-conventions")
}

description = "Maestro Lock Valkey — Lettuce-based DistributedLock implementation"

dependencies {
    api(project(":maestro-core"))
    api(libs.lettuce.core)

    testImplementation(libs.testcontainers.junit5)
}
