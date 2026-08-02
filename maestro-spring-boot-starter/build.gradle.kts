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

    // Optional — Micrometer meters activate only when a MeterRegistry is on
    // the consumer's classpath and in the context (@ConditionalOnClass +
    // @ConditionalOnBean); the starter must still compile without it.
    compileOnly(libs.micrometer.core)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.actuator)
    testImplementation(libs.micrometer.core)
    testImplementation(project(":maestro-test"))
}
