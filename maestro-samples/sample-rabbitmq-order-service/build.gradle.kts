plugins {
    id("maestro.spring-app-conventions")
}

description = "Sample — Order fulfilment workflow with RabbitMQ messaging and Postgres locking"

dependencies {
    implementation(project(":maestro-spring-boot-starter"))
    implementation(project(":maestro-store-postgres"))
    implementation(project(":maestro-messaging-rabbitmq"))
    implementation(project(":maestro-lock-postgres"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation("org.springframework.amqp:spring-rabbit")
    runtimeOnly(libs.postgresql)
}
