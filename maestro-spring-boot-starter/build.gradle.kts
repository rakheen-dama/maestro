plugins {
    id("maestro.spring-library-conventions")
}

description = "Maestro Spring Boot Starter — Auto-configuration and Spring integration"

dependencies {
    api(project(":maestro-core"))
    api(libs.spring.boot.starter)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(project(":maestro-test"))
}
