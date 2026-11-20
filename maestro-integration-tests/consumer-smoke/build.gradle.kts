plugins {
    java
}

// The Maestro release under test (required), and the Spring Boot platform the
// consumer imports itself: the published Gradle module metadata leaves Spring
// versions to the consumer's platform, exactly as a Spring Boot application
// supplies them (RELEASING.md § "The consumer smoke test").
val maestroVersion = providers.gradleProperty("maestro.version").orNull
    ?: throw GradleException("Set -Pmaestro.version=<the Maestro release to consume>, e.g. 0.3.0-rc.1")
val springBootVersion = providers.gradleProperty("maestro.springBootVersion").getOrElse("4.0.5")
val testcontainersVersion = "2.0.5"

group = "io.b2mash.maestro.consumersmoke"
version = "0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    implementation(platform("io.b2mash.maestro:maestro-bom:$maestroVersion"))

    implementation("io.b2mash.maestro:maestro-spring-boot-starter")
    implementation("io.b2mash.maestro:maestro-store-postgres")
    implementation("io.b2mash.maestro:maestro-messaging-kafka")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:testcontainers-postgresql:$testcontainersVersion")
    testImplementation("org.testcontainers:testcontainers-kafka:$testcontainersVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // Always runs: the artifacts under test may have been re-resolved.
    outputs.upToDateWhen { false }
    systemProperty("maestro.consumerSmoke.version", maestroVersion)
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        // As maestro.java-conventions sets for every build module: a failed
        // test prints its message, causes and stack in the log.
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
    }
}
