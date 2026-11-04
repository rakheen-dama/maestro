plugins {
    id("maestro.spring-app-conventions")
}

description = "Sample — Loan origination: auto-rules and human decision queue (port 8093)"

dependencies {
    implementation(project(":maestro-spring-boot-starter"))
    implementation(project(":maestro-store-postgres"))
    implementation(project(":maestro-messaging-kafka"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.micrometer.tracing.opentelemetry)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.micrometer.registry.prometheus)
    // The OTel bridge is NOT pulled in transitively by
    // spring-boot-micrometer-tracing-opentelemetry — without it, Boot's
    // OpenTelemetryTracingAutoConfiguration (@ConditionalOnClass on bridge
    // classes) never activates, so no Tracer/Propagator bean is created and
    // Maestro's TracingEngineObserver never registers. Verified by running
    // the service: no tracing activity of any kind (not even a
    // connection-refused from the OTLP exporter) until this was added.
    runtimeOnly(libs.micrometer.tracing.bridge.otel)
    runtimeOnly(libs.opentelemetry.exporter.otlp)
    // Spring Boot 4 modular auto-configuration: JDBC (DataSource) and Kafka
    // (@KafkaListener container factory + spring.kafka.* binding) each live in their own
    // starter and must be declared explicitly. Maestro applies its own schema
    // (maestro_schema_history) via maestro-store-postgres; no Flyway starter needed.
    runtimeOnly(libs.spring.boot.starter.jdbc)
    runtimeOnly(libs.spring.boot.starter.kafka)

    testImplementation(project(":maestro-test"))
    testImplementation(libs.spring.boot.starter.test)
    // The *ContextBootTest boots the real application context against a
    // Testcontainers Postgres with Maestro's Kafka transport auto-configuration
    // excluded (the engine tolerates a null WorkflowMessaging), so no broker is
    // needed: far enough for WorkflowRegistrar to validate every
    // @DurableWorkflow bean (R55). The Postgres lock ships with
    // maestro-store-postgres, so nothing extra is needed for locking.
    testImplementation(libs.testcontainers.postgresql)
}
