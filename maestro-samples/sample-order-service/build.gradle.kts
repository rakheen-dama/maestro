plugins {
    id("maestro.spring-app-conventions")
}

description = "Sample — Order fulfilment workflow with cross-service signals and saga compensation"

dependencies {
    implementation(project(":maestro-spring-boot-starter"))
    implementation(project(":maestro-store-postgres"))
    implementation(project(":maestro-messaging-kafka"))
    implementation(project(":maestro-lock-valkey"))
    implementation(libs.spring.boot.starter.webmvc)
    runtimeOnly(libs.postgresql)
}
