plugins {
    id("maestro.library-conventions")
}

description = "Maestro Core — Workflow engine, SPIs, domain model (pure Java, no Spring)"

dependencies {
    api(libs.jackson.databind)
    api(libs.jackson.datatype.jsr310)
}
