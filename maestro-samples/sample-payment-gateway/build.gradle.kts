plugins {
    id("maestro.spring-app-conventions")
}

description = "Sample — Payment gateway proxy with durable retries and cross-service signalling"

dependencies {
    implementation(project(":maestro-spring-boot-starter"))
    implementation(project(":maestro-store-postgres"))
    implementation(project(":maestro-messaging-kafka"))
    implementation(project(":maestro-lock-valkey"))
    implementation(libs.spring.boot.starter.web)
    runtimeOnly(libs.postgresql)
}
