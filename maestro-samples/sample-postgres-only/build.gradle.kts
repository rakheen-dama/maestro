plugins {
    id("maestro.spring-app-conventions")
}

description = "Sample — Document approval workflow using Postgres only (no Kafka, no Valkey)"

dependencies {
    implementation(project(":maestro-spring-boot-starter"))
    implementation(project(":maestro-store-postgres"))
    implementation(project(":maestro-messaging-postgres"))
    implementation(project(":maestro-lock-postgres"))
    implementation(libs.spring.boot.starter.webmvc)
    runtimeOnly(libs.postgresql)
}
