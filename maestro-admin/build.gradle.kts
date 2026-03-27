plugins {
    id("maestro.spring-app-conventions")
}

description = "Maestro Admin — Standalone workflow dashboard"

dependencies {
    implementation(project(":maestro-admin-client"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.thymeleaf)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.kafka)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
}
