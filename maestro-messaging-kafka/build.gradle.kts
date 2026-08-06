plugins {
    id("maestro.spring-library-conventions")
}

description = "Maestro Messaging Kafka — Spring Kafka WorkflowMessaging implementation"

dependencies {
    api(project(":maestro-spring-boot-starter"))
    api(libs.spring.kafka)
    // KafkaProperties / KafkaConnectionDetails — Maestro's engine factories are
    // built from these instead of a hand-rolled bootstrap-servers-only map, so
    // every spring.kafka.producer.*/consumer.* property is honoured. Not `api`:
    // implementation still reaches consumers' runtime classpath (Gradle Java
    // plugin semantics), which is all auto-configuration needs.
    implementation(libs.spring.boot.kafka)

    // Optional — KafkaTracePropagation activates only when Micrometer Tracing's
    // Tracer/Propagator are present; without them the wire format is
    // byte-identical to a build with no tracing at all.
    compileOnly(libs.micrometer.tracing)

    testImplementation(libs.micrometer.tracing)
    // The propagation contract test pins real W3C header names and grammar, so
    // it needs a real propagator, not a double.
    testImplementation(libs.micrometer.tracing.bridge.otel)
    // Boot's own tracing auto-configuration, so the ordering pin runs against
    // the real AutoConfigurations chain rather than a withBean stub.
    testImplementation(libs.spring.boot.micrometer.tracing)
    testImplementation(libs.spring.boot.micrometer.tracing.opentelemetry)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.kafka.test)
    testImplementation(libs.testcontainers.junit5)
    testImplementation(libs.testcontainers.kafka)
}
