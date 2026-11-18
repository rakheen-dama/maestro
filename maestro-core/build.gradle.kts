plugins {
    id("maestro.library-conventions")
}

description = "Maestro Core — Workflow engine, SPIs, domain model (pure Java, no Spring)"

dependencies {
    api(libs.jackson.databind)

    // Test only: maestro-test's InMemoryDistributedLock, which every core test
    // that builds a WorkflowExecutor needs now that a lock is mandatory (P3).
    // maestro-test depends on this module's main output, not its tests, so
    // there is no cycle; the published maestro-core POM is unaffected.
    testImplementation(project(":maestro-test"))
}
