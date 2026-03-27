plugins {
    `java-library`
}

group = "io.maestro"
version = property("version") as String

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf(
        "-parameters",
        "-Xlint:all",
        "-Xlint:-processing",
        "-Xlint:-serial",
    ))
}

// Versions aligned with Spring Boot 4.0.0-RC2 BOM
val springBootBomVersion = "4.0.0-RC2"
val jspecifyVersion = "1.0.0"

dependencies {
    api("org.jspecify:jspecify:$jspecifyVersion")

    // Import Spring Boot BOM for version alignment (SLF4J, JUnit, etc.)
    // Using implementation scope so the BOM is NOT leaked to consumers' POMs.
    // This is critical for maestro-core which must never expose Spring in its API surface.
    implementation(platform("org.springframework.boot:spring-boot-dependencies:$springBootBomVersion"))

    implementation("org.slf4j:slf4j-api")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.withType<Javadoc>().configureEach {
    isFailOnError = false
    options {
        this as StandardJavadocDocletOptions
        addBooleanOption("Xdoclint:none", true)
    }
}
