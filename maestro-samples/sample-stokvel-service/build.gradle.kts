plugins {
    id("maestro.spring-app-conventions")
}

description = "Sample — Stokvel onboarding workflow with parallel branches and signal collection"

dependencies {
    implementation(project(":maestro-spring-boot-starter"))
    implementation(project(":maestro-store-postgres"))
    implementation(project(":maestro-messaging-kafka"))
    implementation(project(":maestro-lock-valkey"))
    implementation(libs.spring.boot.starter.webmvc)
    runtimeOnly(libs.postgresql)

    testImplementation(project(":maestro-test"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.junit5)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
}
