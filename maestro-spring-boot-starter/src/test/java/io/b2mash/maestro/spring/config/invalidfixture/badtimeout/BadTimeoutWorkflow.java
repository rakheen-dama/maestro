package io.b2mash.maestro.spring.config.invalidfixture.badtimeout;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.spring.config.scanfixture.ApprovalActivities;

/**
 * A scanned workflow whose {@code @ActivityStub} timeout is not an ISO-8601
 * duration. The mis-configuration must fail context startup, not every run.
 */
@DurableWorkflow(name = "bad-timeout")
public class BadTimeoutWorkflow {

    @ActivityStub(startToCloseTimeout = "thirty seconds")
    private ApprovalActivities activities;

    /** @return never runs */
    @WorkflowMethod
    public String run(String documentId) {
        return activities.approve(documentId);
    }
}
