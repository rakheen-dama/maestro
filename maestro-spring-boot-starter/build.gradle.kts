plugins {
    id("maestro.spring-library-conventions")
}

description = "Maestro Spring Boot Starter — Auto-configuration and Spring integration"

dependencies {
    api(project(":maestro-core"))
    api(libs.spring.boot.starter)
    annotationProcessor(libs.spring.boot.configuration.processor)

    // Optional — MaestroHealthIndicator activates only when Actuator's
    // HealthIndicator is on the consumer's classpath (@ConditionalOnClass).
    compileOnly(libs.spring.boot.starter.actuator)

    // Optional — Micrometer meters activate only when a MeterRegistry is on
    // the consumer's classpath and in the context (@ConditionalOnClass +
    // @ConditionalOnBean); the starter must still compile without it.
    compileOnly(libs.micrometer.core)

    // Optional — TracingEngineObserver activates only when Micrometer Tracing's
    // Tracer/Propagator are on the consumer's classpath and in the context
    // (@ConditionalOnClass + @ConditionalOnBean); the starter must still compile
    // and run without any tracing bridge.
    compileOnly(libs.micrometer.tracing)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.actuator)
    testImplementation(libs.micrometer.core)
    testImplementation(libs.micrometer.tracing)
    // A real OTel SDK tracer + W3C propagator: the span tests assert real
    // parent/child edges and real trace IDs, not a test double's bookkeeping.
    testImplementation(libs.micrometer.tracing.bridge.otel)
    // Boot's own tracing auto-configuration, so the ordering test runs against
    // the real AutoConfigurations chain (task-4 fix round 1's lesson).
    testImplementation(libs.spring.boot.micrometer.tracing)
    testImplementation(libs.spring.boot.micrometer.tracing.opentelemetry)
    testImplementation(project(":maestro-test"))
}

// Spring Boot's configuration processor reads each property's description from
// the Javadoc of the source it compiles. It is an "aggregating" processor, so on
// an incremental recompile Gradle re-runs it over MaestroProperties loaded from
// class files, which carry no Javadoc: the regenerated
// META-INF/spring-configuration-metadata.json loses every description, and the
// build cache then serves that output to later clean builds. Always compile the
// starter in full.
//
// The processor merges META-INF/additional-spring-configuration-metadata.json
// only if it can find it. Without Spring Boot's Gradle plugin nothing tells it
// where src/main/resources is, so it found the file only when processResources
// happened to run first. Pass the location explicitly and declare the file as
// an input, so editing it recompiles.
class AdditionalMetadataLocation(
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val additionalMetadata: File,
) : CommandLineArgumentProvider {
    override fun asArguments() = listOf(
        "-Aorg.springframework.boot.configurationprocessor.additionalMetadataLocations="
            + additionalMetadata.parentFile.parentFile.absolutePath,
    )
}

tasks.compileJava {
    options.isIncremental = false
    options.compilerArgumentProviders.add(
        AdditionalMetadataLocation(
            file("src/main/resources/META-INF/additional-spring-configuration-metadata.json"),
        ),
    )
}
