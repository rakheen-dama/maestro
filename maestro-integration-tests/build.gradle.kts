plugins {
    id("maestro.integration-test-conventions")
}

description = "Maestro Integration Tests — engine against real Postgres, Kafka and lock backends (not published)"

dependencies {
    // Subjects under test — the real engine and the real backend adapters.
    testImplementation(project(":maestro-core"))
    testImplementation(project(":maestro-spring-boot-starter"))
    testImplementation(project(":maestro-store-postgres"))
    testImplementation(project(":maestro-messaging-kafka"))
    testImplementation(project(":maestro-messaging-postgres"))
    testImplementation(project(":maestro-lock-postgres"))
    testImplementation(project(":maestro-lock-valkey"))

    // Fixtures only — never the subject of an integration assertion.
    testImplementation(project(":maestro-test"))

    testImplementation(libs.jackson.databind)
    testImplementation(libs.postgresql)
    testImplementation(libs.flyway.core)
    testRuntimeOnly(libs.flyway.postgres)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.jdbc)
    testImplementation(libs.spring.kafka)
    testImplementation(libs.spring.kafka.test)

    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit5)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.awaitility)

    // Micrometer — the replay-no-double-count IT wires a SimpleMeterRegistry
    // through MicrometerEngineObserver (starter's main sourceSet has it only
    // compileOnly, so it does not propagate transitively; declared explicitly
    // here per the design doc's §7.3 dependency table).
    testImplementation(libs.micrometer.core)

    // Micrometer Tracing + a real OTel SDK bridge — the cross-service trace
    // linkage IT asserts real trace IDs across two engines and a real broker.
    testImplementation(libs.micrometer.tracing)
    testImplementation(libs.micrometer.tracing.bridge.otel)
}

// ---------------------------------------------------------------------------
// Chaos/soak harness (e2e/chaos) — boot-jar wiring.
//
// The @Tag("e2e") chaos suite launches the three loan-origination services as
// Docker containers from their Spring Boot fat jars. Per the chaos-harness
// design ruling Q2 (option a), `e2eTest` gains a task dependency on each
// sample's `bootJar` and receives the resolved jar paths as system properties.
// This coupling lives ONLY on `e2eTest`; `build`/`check`/`test` never build the
// sample jars.
// ---------------------------------------------------------------------------
val loanSampleServices = mapOf(
    "loan-application" to ":maestro-samples:sample-loan-origination:loan-application-service",
    "verification-gateway" to ":maestro-samples:sample-loan-origination:verification-gateway-service",
    "underwriting" to ":maestro-samples:sample-loan-origination:underwriting-service",
)

tasks.named<Test>("e2eTest") {
    // Chaos runs are Docker-heavy and long; give them room and always show output.
    maxParallelForks = 1
    // Deliberate heap for the chaos test JVM (soak-failure fix, report §10):
    // Gradle's 512m default OOMed the 2h soak at 28 minutes. The primary fix
    // was eliminating unbounded accumulation (streamed log capture, incremental
    // log probes, single-pass census); 2g is defence in depth — 4x the default,
    // covering soak-scale transient allocation (docker-java frames for 6 nodes,
    // end-of-run census and dumps) while fitting a 7GB CI runner beside the
    // Docker containers (6x256m node JVMs + Kafka + Postgres + Valkey).
    maxHeapSize = "2g"
    testLogging {
        showStandardStreams = true
    }
    loanSampleServices.forEach { (key, projectPath) ->
        val svc = project(projectPath)
        dependsOn("$projectPath:bootJar")
        val jarFile = svc.layout.buildDirectory
            .file(svc.provider { "libs/${svc.name}-${svc.version}.jar" })
        inputs.file(jarFile).withPropertyName("bootJar-$key")
        systemProperty("maestro.chaos.jar.$key", jarFile.get().asFile.absolutePath)
    }
    // Propagate opt-in chaos system properties (mode/seed/duration/rate/soak) from
    // the Gradle invocation into the test JVM.
    listOf(
        "maestro.chaos.mode", "maestro.chaos.seed", "maestro.chaos.durationMinutes",
        "maestro.chaos.ratePerMinute", "maestro.chaos.soak", "maestro.chaos.golden",
        "maestro.chaos.smoke", "maestro.chaos.evidenceDir", "maestro.chaos.drainSeconds",
        "maestro.chaos.tailPhaseSeconds", "maestro.chaos.tailRatePerMinute",
    ).forEach { key -> System.getProperty(key)?.let { systemProperty(key, it) } }
}
