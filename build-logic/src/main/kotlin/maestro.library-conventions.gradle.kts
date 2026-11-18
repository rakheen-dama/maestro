plugins {
    id("maestro.java-conventions")
    `maven-publish`
    signing
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    // Pre-release builds for pilots go to this repository's GitHub Packages
    // registry (RELEASING.md, "Pre-releases on GitHub Packages"). The repository
    // and the credentials come from the environment GitHub Actions provides, or
    // from the gradle properties gprRepository / gprUser / gprToken.
    repositories {
        maven {
            name = "GitHubPackages"
            val repository = providers.gradleProperty("gprRepository")
                .orElse(providers.environmentVariable("GITHUB_REPOSITORY"))
                .orElse("rakheen-dama/maestro")
            url = uri("https://maven.pkg.github.com/${repository.get()}")
            credentials {
                username = providers.gradleProperty("gprUser")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gprToken")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
        }
    }
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name = project.name
                description = project.description
                url = "https://github.com/maestro-workflow/maestro"
                licenses {
                    license {
                        name = "Apache License, Version 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0"
                    }
                }
                developers {
                    developer {
                        id = "maestro-workflow"
                        name = "Maestro Contributors"
                        url = "https://github.com/maestro-workflow/maestro/graphs/contributors"
                    }
                }
                scm {
                    connection = "scm:git:git://github.com/maestro-workflow/maestro.git"
                    developerConnection = "scm:git:ssh://github.com/maestro-workflow/maestro.git"
                    url = "https://github.com/maestro-workflow/maestro"
                }
            }
        }
    }
}

signing {
    isRequired = providers.gradleProperty("signingKey").isPresent
    val signingKey: String? by project
    val signingPassword: String? by project
    useInMemoryPgpKeys(signingKey, signingPassword)
    sign(publishing.publications["mavenJava"])
}
