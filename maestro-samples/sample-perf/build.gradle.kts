plugins {
    id("maestro.spring-app-conventions")
}

description = "Sample — load-test target: knob-driven chain and park workflows at Maestro's true defaults (unpublished)"

dependencies {
    implementation(project(":maestro-spring-boot-starter"))
    implementation(project(":maestro-store-postgres"))
    implementation(project(":maestro-messaging-kafka"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    // Compile scope (not runtimeOnly as in the other samples): /perf/info reads
    // the effective pool size through HikariDataSource.
    implementation(libs.spring.boot.starter.jdbc)

    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.spring.boot.starter.kafka)
    runtimeOnly(libs.micrometer.registry.prometheus)

    testImplementation(project(":maestro-test"))
}

tasks.bootJar {
    archiveFileName = "sample-perf.jar"
}

// The commit the harness baked into this jar, reported by GET /perf/info as
// buildCommit (BuildProperties "gitCommit"). The harness passes
// -PperfGitCommit=<sha>; any other build reports "unset".
springBoot {
    buildInfo {
        properties {
            additional.put("gitCommit", providers.gradleProperty("perfGitCommit").orElse("unset"))
        }
    }
}
