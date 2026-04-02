package io.b2mash.maestro.samples.document.workflow;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.QueryMethod;
import io.b2mash.maestro.core.annotation.RetryPolicy;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.SignalTimeoutException;
import io.b2mash.maestro.samples.document.activity.DocumentActivities;
import io.b2mash.maestro.samples.document.domain.DocumentInput;
import io.b2mash.maestro.samples.document.domain.DocumentResult;
import io.b2mash.maestro.samples.document.domain.DocumentStatus;
import io.b2mash.maestro.samples.document.domain.ReviewDecision;

import java.time.Duration;

/**
 * Durable document approval workflow.
 *
 * <p>Steps:
 * <ol>
 *   <li>Validate and store the document</li>
 *   <li>Assign a reviewer from the pool</li>
 *   <li>Await a review decision signal (up to 7 days)</li>
 *   <li>If approved: publish the document</li>
 *   <li>If rejected: notify the author</li>
 *   <li>Archive the audit trail</li>
 * </ol>
 *
 * <p>This workflow demonstrates Maestro's signal-based human-in-the-loop pattern.
 * The workflow suspends at step 3 and resumes when a reviewer submits their
 * decision via the REST API, which delivers it as a Maestro signal.
 */
@DurableWorkflow(name = "document-approval", taskQueue = "documents")
public class DocumentApprovalWorkflow {

    @ActivityStub(startToCloseTimeout = "PT30S",
                  retryPolicy = @RetryPolicy(maxAttempts = 3))
    private DocumentActivities activities;

    // Volatile: read by @QueryMethod from caller's thread, written by workflow's virtual thread
    private volatile String currentStep = "SUBMITTED";

    @WorkflowMethod
    public DocumentResult execute(DocumentInput input) {
        var workflow = WorkflowContext.current();

        // Step 1: Validate and store
        currentStep = "VALIDATING";
        var documentId = activities.validateAndStore(input);

        // Step 2: Assign reviewer
        currentStep = "ASSIGNING_REVIEWER";
        var reviewerId = activities.assignReviewer(documentId);

        // Step 3: Await review decision signal (up to 7 days)
        currentStep = "AWAITING_REVIEW";
        ReviewDecision decision;
        try {
            decision = workflow.awaitSignal(
                "review.decision", ReviewDecision.class, Duration.ofDays(7));
        } catch (SignalTimeoutException e) {
            // Timeout — no review received within 7 days
            currentStep = "TIMED_OUT";
            activities.notifyRejection(documentId, input.authorId(), "Review timed out after 7 days");
            activities.archiveAuditTrail(documentId, "TIMED_OUT");
            return new DocumentResult(false, documentId, "TIMED_OUT", "Review timed out");
        }

        if (decision.approved()) {
            // Step 4a: Publish
            currentStep = "PUBLISHING";
            activities.publishDocument(documentId);
            activities.archiveAuditTrail(documentId, "PUBLISHED");
            currentStep = "PUBLISHED";
            return new DocumentResult(true, documentId, "PUBLISHED", null);
        } else {
            // Step 4b: Reject
            currentStep = "REJECTED";
            activities.notifyRejection(documentId, input.authorId(), decision.comments());
            activities.archiveAuditTrail(documentId, "REJECTED");
            return new DocumentResult(false, documentId, "REJECTED", decision.comments());
        }
    }

    @QueryMethod
    public DocumentStatus getStatus() {
        return new DocumentStatus(currentStep);
    }
}
