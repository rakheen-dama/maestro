package io.b2mash.maestro.spring.config.scanfixture;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;

/**
 * A workflow class annotated ONLY with {@code @DurableWorkflow} — no
 * {@code @Component}, no manual bean registration. This is the exact
 * user-facing pattern from the sample apps: the starter must discover
 * and register this class as a Spring bean by classpath scanning.
 */
@DurableWorkflow(name = "scanned-approval", taskQueue = "approvals")
public class ScannedApprovalWorkflow {

    @ActivityStub
    private ApprovalActivities activities;

    @WorkflowMethod
    public String approve(String documentId) {
        return activities.approve(documentId);
    }

    /** Test accessor to verify the memoizing activity proxy was injected. */
    public ApprovalActivities activities() {
        return activities;
    }
}
