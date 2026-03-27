plugins {
    id("maestro.library-conventions")
}

description = "Maestro Store JDBC — Abstract JDBC WorkflowStore implementation"

dependencies {
    api(project(":maestro-core"))
    implementation(libs.jackson.databind)
}
