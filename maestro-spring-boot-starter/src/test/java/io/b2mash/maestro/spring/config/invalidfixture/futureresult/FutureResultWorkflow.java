package io.b2mash.maestro.spring.config.invalidfixture.futureresult;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;

import java.util.concurrent.CompletableFuture;

/**
 * A scanned workflow whose {@code @ActivityStub} interface returns a
 * {@code CompletableFuture} (Issue 49). The shape cannot survive a restart, so
 * it must fail context startup, not hang the workflow after its first restart.
 */
@DurableWorkflow(name = "future-result")
public class FutureResultWorkflow {

    /** Activity interface with a future-typed result; the test registers a bean for it. */
    public interface AsyncActivities {
        /** @return never called */
        CompletableFuture<String> fetch(String id);
    }

    @ActivityStub
    private AsyncActivities activities;

    /** @return never runs */
    @WorkflowMethod
    public String run(String id) {
        return activities.fetch(id).join();
    }
}
