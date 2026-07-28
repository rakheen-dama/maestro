plugins {
    id("maestro.java-conventions")
    id("io.spring.dependency-management")
}

// Test-only module: NOT published to Maven Central (no maestro.library-conventions),
// but it needs the Spring Boot BOM because integration suites boot real
// @SpringBootTest contexts against Testcontainers backends.
val springBootBomVersion = "4.0.5"

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:$springBootBomVersion")
    }
}

// `test` runs the integration suites (@Tag("integration")) — they execute on every
// PR via `./gradlew build`. The heavier end-to-end suites (@Tag("e2e")) are excluded
// here and run through the dedicated `e2eTest` task instead, so the PR loop stays fast.
tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("e2e")
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

tasks.register<Test>("e2eTest") {
    group = "verification"
    description = "Runs the end-to-end suites (@Tag(\"e2e\")) — excluded from `build`."

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("e2e")
    }
    testLogging {
        events("passed", "skipped", "failed")
    }
}
