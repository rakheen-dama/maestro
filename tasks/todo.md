# Maestro — Gradle Multi-Module Setup

## Completed

- [x] Generate Gradle 9.2.0 wrapper
- [x] Create `gradle.properties` (group=io.b2mash.maestro, version=0.3.0-SNAPSHOT)
- [x] Create `gradle/libs.versions.toml` version catalog (Spring Boot 4.0.0-RC2 BOM, JSpecify 1.0.0)
- [x] Create `settings.gradle.kts` with all 11 modules + 2 samples
- [x] Create root `build.gradle.kts`
- [x] Create `build-logic/` composite build with 4 convention plugins:
  - `maestro.java-conventions` — Java 21 toolchain, JSpecify, SLF4J, JUnit 5
  - `maestro.library-conventions` — extends java-conventions + maven-publish
  - `maestro.spring-library-conventions` — extends library-conventions + Spring DM plugin
  - `maestro.spring-app-conventions` — java-conventions + Spring Boot plugin
- [x] Create all module `build.gradle.kts` with correct dependency wiring
- [x] Create directory structure (src/main/java, src/test/java, resources)
- [x] Create `package-info.java` with `@NullMarked` in each module
- [x] Create `@SpringBootApplication` main classes for admin + samples
- [x] Create `application.yml` placeholders for admin + samples
- [x] Create auto-configuration imports file for spring-boot-starter
- [x] Verify `./gradlew build` passes (BUILD SUCCESSFUL, 66 tasks)
- [x] Verify maestro-core has NO Spring JARs on classpath (only BOM for version alignment)
- [x] Verify library modules have `publish` tasks
- [x] Verify app modules have `bootJar`/`bootRun` tasks

## Code Review Fixes Applied

- [x] Changed `api(platform(BOM))` → `implementation(platform(BOM))` in java-conventions to prevent Spring BOM leaking from maestro-core's published POM
- [x] Changed `api(spring-boot-autoconfigure)` → `implementation(...)` in spring-boot-starter (SB4 guideline: autoconfigure is internal)
- [x] Moved Flyway from `testImplementation` → `implementation`/`runtimeOnly` in store-postgres (module owns the migrations)
- [x] Removed redundant Flyway declarations from sample apps (now inherited from store-postgres)
- [x] Re-verified build passes after all fixes

## Review Notes

- Spring Boot 4.0.0 is not GA — using RC2 from Spring milestone repo
- Jackson 3 `jackson-datatype-jsr310:3.0.1` not published yet — omitted (JSR310 support built into Jackson 3 core)
- `libs` type-safe accessor not available in precompiled script plugins in composite builds — convention plugins use string literals
- Javadoc configured with `failOnError = false` since modules have no public classes yet
