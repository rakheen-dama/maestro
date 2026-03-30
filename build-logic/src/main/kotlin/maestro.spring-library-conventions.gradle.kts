plugins {
    id("maestro.library-conventions")
    id("io.spring.dependency-management")
}

val springBootBomVersion = "3.5.0"

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:$springBootBomVersion")
    }
}
