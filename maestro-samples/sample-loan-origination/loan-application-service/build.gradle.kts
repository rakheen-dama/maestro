import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    id("maestro.spring-app-conventions")
}

description = "Sample — Loan origination: application orchestrator with funding saga (port 8091)"

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

// ── The v2 deployment: parallel verification behind workflow.version() ────
//
// `src/v2/java` holds exactly ONE class — LoanApplicationWorkflow — at the
// SAME fully-qualified name as the one in `src/main/java`. That is deliberate:
// a versioned redeploy in production is the same workflow type running
// different code, not a second type. Everything else in the service (activities,
// domain records, controller, signal router, application.yml) is shared, so
// `v2` compiles against main's output and `v2BootJar` packages main's output
// with v2's class in front of it.
//
// Nothing on the v1 path changes: `bootJar` is untouched and still produces the
// v1 jar the loan E2E suite and demo/scripts/start-services.sh run.
val v2: SourceSet = sourceSets.create("v2") {
    java.setSrcDirs(listOf("src/v2/java"))
    // main's output first so v2's own source is what compiles; the rest of the
    // service (LoanTimeouts, activities, domain) resolves from main.
    compileClasspath += sourceSets["main"].output + configurations["compileClasspath"]
    runtimeClasspath += output + sourceSets["main"].output + configurations["runtimeClasspath"]
}

/**
 * `loan-application-v2.jar` — the same service, built from the v2 source set.
 *
 * v2's classes dir is FIRST on the jar's classpath and duplicatesStrategy is
 * EXCLUDE, so `…workflow/LoanApplicationWorkflow.class` in BOOT-INF/classes is
 * v2's and main's copy is dropped. Verified by unzipping the jar and grepping
 * the packaged class for the change id — see demo/.evidence/task-3-v2-jar-*.log.
 */
val v2BootJar by tasks.registering(BootJar::class) {
    group = "build"
    description = "Builds loan-application-v2.jar (parallel verification behind workflow.version())"
    mainClass = "io.b2mash.maestro.samples.loan.application.LoanApplicationServiceApplication"
    archiveFileName = "loan-application-v2.jar"
    // Boot's own `bootJar` gets this from the java plugin's compile task; a
    // hand-registered BootJar has to be told, or it fails with
    // "property 'targetJavaVersion' because it has no value available".
    targetJavaVersion = java.toolchain.languageVersion.map { JavaVersion.toVersion(it.asInt()) }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    classpath(v2.output, sourceSets["main"].output, configurations["runtimeClasspath"])
}

// Wired into `build` so CI compiles and packages v2 on every build — a v2
// source set that only the demo runbook touches would rot silently.
tasks.named("build") {
    dependsOn(v2BootJar)
}

// LoanApplicationWorkflowV2Test loads v2's LoanApplicationWorkflow through a
// child-first classloader over this directory, so that ONE JVM holds both the
// v1 class (from main, on the normal test classpath) and the v2 class at the
// same FQN — which is what lets the test compare their event sequences
// directly. See that test's Javadoc.
tasks.test {
    dependsOn(v2.classesTaskName)
    systemProperty("maestro.demo.v2ClassesDir", v2.output.classesDirs.asPath)
}
