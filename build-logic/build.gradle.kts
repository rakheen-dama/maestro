plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
    maven { url = uri("https://repo.spring.io/milestone") }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-gradle-plugin:4.0.0-RC2")
    implementation("io.spring.gradle:dependency-management-plugin:1.1.7")
}
