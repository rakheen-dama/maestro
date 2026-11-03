package io.b2mash.maestro.spring.config.invalidfixture.missingactivity;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;

/**
 * A scanned workflow whose {@code @ActivityStub} has no implementing bean in
 * the context. The mis-wiring must fail context startup, not every run.
 */
@DurableWorkflow(name = "missing-activity")
public class MissingActivityWorkflow {

    /** Activity interface deliberately left without an implementation bean. */
    public interface UnimplementedActivities {
        /** @return never called */
        String work();
    }

    @ActivityStub
    private UnimplementedActivities activities;

    /** @return never runs */
    @WorkflowMethod
    public String run() {
        return activities.work();
    }
}
