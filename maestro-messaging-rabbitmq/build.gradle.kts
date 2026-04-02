plugins {
    id("maestro.spring-library-conventions")
}

description = "RabbitMQ WorkflowMessaging SPI implementation via Spring AMQP"

dependencies {
    api(project(":maestro-spring-boot-starter"))
    api("org.springframework.amqp:spring-rabbit")
    implementation(libs.jackson.databind)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation("org.testcontainers:testcontainers-rabbitmq:${libs.versions.testcontainers.get()}")
}
