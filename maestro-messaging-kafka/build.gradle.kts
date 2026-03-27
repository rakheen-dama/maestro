plugins {
    id("maestro.spring-library-conventions")
}

description = "Maestro Messaging Kafka — Spring Kafka WorkflowMessaging implementation"

dependencies {
    api(project(":maestro-core"))
    api(libs.spring.kafka)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.kafka.test)
    testImplementation(libs.testcontainers.junit5)
    testImplementation(libs.testcontainers.kafka)
}
