plugins {
    `java-library`
}

group = "io.b2mash.maestro"
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

// Versions pinned explicitly — NO Spring Boot BOM here.
// This keeps maestro-core's published POM completely free of Spring references.
val jspecifyVersion = "1.0.0"
val slf4jVersion = "2.0.17"
val junitJupiterVersion = "5.11.4"
val awaitilityVersion = "4.3.0"

dependencies {
    api("org.jspecify:jspecify:$jspecifyVersion")
    api("org.slf4j:slf4j-api:$slf4jVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:$junitJupiterVersion")
    testImplementation("org.awaitility:awaitility:$awaitilityVersion")
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
