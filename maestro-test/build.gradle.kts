plugins {
    id("maestro.library-conventions")
}

description = "Maestro Test — In-memory SPIs, controllable clock, TestWorkflowEnvironment"

dependencies {
    api(project(":maestro-core"))
    implementation(libs.jackson.databind)
}
