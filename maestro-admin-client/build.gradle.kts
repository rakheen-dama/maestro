plugins {
    id("maestro.spring-library-conventions")
}

description = "Maestro Admin Client — Lightweight lifecycle event publisher"

dependencies {
    api(project(":maestro-core"))
    implementation(libs.spring.kafka)

    testImplementation(libs.spring.boot.starter.test)
}
