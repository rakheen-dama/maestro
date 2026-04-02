package io.b2mash.maestro.samples.document.activity;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.samples.document.domain.DocumentInput;

/**
 * Activities for the document approval workflow.
 *
 * <p>Each method represents a single unit of work that is durably executed and
 * memoized by the Maestro engine. On replay, completed activities return their
 * stored result without re-execution.
 */
@Activity
public interface DocumentActivities {

    /**
     * Validates the document and stores it. Returns a generated document ID.
     *
     * @param input the document submission input
     * @return generated document identifier
     */
    String validateAndStore(DocumentInput input);

    /**
     * Assigns a reviewer from the available pool.
     *
     * @param documentId the document to assign a reviewer for
     * @return the assigned reviewer's identifier
     */
    String assignReviewer(String documentId);

    /**
     * Marks the document as published and available.
     *
     * @param documentId the document to publish
     */
    void publishDocument(String documentId);

    /**
     * Sends a rejection notification to the document author.
     *
     * @param documentId the rejected document
     * @param authorId   the author to notify
     * @param reason     the reason for rejection
     */
    void notifyRejection(String documentId, String authorId, String reason);

    /**
     * Archives the final state of the document for audit purposes.
     *
     * @param documentId  the document to archive
     * @param finalStatus the terminal status to record
     */
    void archiveAuditTrail(String documentId, String finalStatus);
}
