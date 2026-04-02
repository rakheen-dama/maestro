plugins {
    id("maestro.library-conventions")
}

description = "Maestro Test — In-memory SPIs, controllable clock, TestWorkflowEnvironment"

dependencies {
    api(project(":maestro-core"))
    api(libs.junit.jupiter)
    implementation(libs.jackson.databind)
}
