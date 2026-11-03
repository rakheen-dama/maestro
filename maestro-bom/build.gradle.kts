plugins {
    `java-platform`
    `maven-publish`
    signing
}

group = "io.b2mash.maestro"
version = property("version") as String
description = "Maestro BOM — aligned versions for every Maestro module"

dependencies {
    constraints {
        api(project(":maestro-core"))
        api(project(":maestro-spring-boot-starter"))
        api(project(":maestro-store-jdbc"))
        api(project(":maestro-store-postgres"))
        api(project(":maestro-messaging-kafka"))
        api(project(":maestro-messaging-postgres"))
        api(project(":maestro-lock-valkey"))
        api(project(":maestro-lock-postgres"))
        api(project(":maestro-admin-client"))
        api(project(":maestro-test"))
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenBom") {
            from(components["javaPlatform"])
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
    sign(publishing.publications["mavenBom"])
}
