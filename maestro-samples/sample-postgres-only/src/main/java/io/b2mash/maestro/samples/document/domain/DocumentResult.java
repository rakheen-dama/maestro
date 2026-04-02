package io.b2mash.maestro.samples.document.domain;

import org.jspecify.annotations.Nullable;

/**
 * Final result of the document approval workflow.
 *
 * @param success    whether the document was approved and published
 * @param documentId the document identifier
 * @param status     terminal status (PUBLISHED, REJECTED, TIMED_OUT)
 * @param reason     rejection or failure reason, null on success
 */
public record DocumentResult(
    boolean success,
    String documentId,
    String status,
    @Nullable String reason
) {}
