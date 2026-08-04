plugins {
    id("maestro.spring-app-conventions")
}

description = "Sample — Loan origination: simulated credit/employment/appraisal verifiers (port 8092)"

dependencies {
    implementation(project(":maestro-spring-boot-starter"))
    implementation(project(":maestro-store-postgres"))
    implementation(project(":maestro-messaging-kafka"))
    implementation(project(":maestro-lock-valkey"))
    // Both lock backends on the classpath; maestro.lock.type (default
    // "valkey", set by application.yml) picks which auto-configuration
    // activates - see PostgresLockAutoConfiguration/ValkeyLockAutoConfiguration's
    // @ConditionalOnProperty. Lets the E2E harness switch backends via
    // MAESTRO_LOCK_TYPE=postgres (E2E_LOCK_BACKEND=postgres) without a
    // rebuild. Runtime default is unchanged: Valkey wins whenever the
    // property is absent (matchIfMissing = true on the Valkey side).
    implementation(project(":maestro-lock-postgres"))
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
    // Spring Boot 4 modular auto-configuration: JDBC (DataSource), Flyway
    // (maestro_* schema from maestro-store-postgres), and Kafka
    // (@KafkaListener container factory + spring.kafka.* binding) each
    // live in their own starter and must be declared explicitly.
    runtimeOnly(libs.spring.boot.starter.jdbc)
    runtimeOnly(libs.spring.boot.starter.flyway)
    runtimeOnly(libs.spring.boot.starter.kafka)

    testImplementation(project(":maestro-test"))
    testImplementation(libs.spring.boot.starter.test)
}
