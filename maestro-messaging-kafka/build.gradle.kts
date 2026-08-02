plugins {
    id("maestro.spring-library-conventions")
}

description = "Maestro Messaging Kafka — Spring Kafka WorkflowMessaging implementation"

dependencies {
    api(project(":maestro-spring-boot-starter"))
    api(libs.spring.kafka)

    // Optional — KafkaTracePropagation activates only when Micrometer Tracing's
    // Tracer/Propagator are present; without them the wire format is
    // byte-identical to a build with no tracing at all.
    compileOnly(libs.micrometer.tracing)

    testImplementation(libs.micrometer.tracing)
    // The propagation contract test pins real W3C header names and grammar, so
    // it needs a real propagator, not a double.
    testImplementation(libs.micrometer.tracing.bridge.otel)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.kafka.test)
    testImplementation(libs.testcontainers.junit5)
    testImplementation(libs.testcontainers.kafka)
}
