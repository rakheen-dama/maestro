plugins {
    id("maestro.spring-library-conventions")
}

description = "Maestro Spring Boot Starter — Auto-configuration and Spring integration"

dependencies {
    api(project(":maestro-core"))
    api(libs.spring.boot.starter)
    annotationProcessor(libs.spring.boot.configuration.processor)

    // Optional — MaestroHealthIndicator activates only when Actuator's
    // HealthIndicator is on the consumer's classpath (@ConditionalOnClass).
    compileOnly(libs.spring.boot.starter.actuator)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.actuator)
    testImplementation(project(":maestro-test"))
}
