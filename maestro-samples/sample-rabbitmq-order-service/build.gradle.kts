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
    // Spring Boot 4 modular auto-configuration: JDBC (DataSource) and Flyway
    // (maestro_* schema from maestro-store-postgres) each live in their own
    // starter and must be declared explicitly.
    runtimeOnly(libs.spring.boot.starter.jdbc)
    runtimeOnly(libs.spring.boot.starter.flyway)
}
