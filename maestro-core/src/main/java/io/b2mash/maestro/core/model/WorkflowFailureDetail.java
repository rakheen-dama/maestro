package io.b2mash.maestro.core.model;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Why a workflow failed: the payload the engine stores as the instance
 * {@linkplain WorkflowInstance#output() output} (and on the
 * {@link EventType#WORKFLOW_FAILED} event) when the workflow method ends
 * with an unhandled exception.
 *
 * <p>The serialized form is a JSON object with these fields —
 * {@code {"exceptionType": "...", "message": "...", "failedSequences": [...]}}.
 * Stored histories carry that shape and the stores' signal-timeout
 * discriminator reads the {@code exceptionType} field, so the component names
 * are part of the persisted format and must not be renamed. History written
 * by a build before WP3 has no {@code failedSequences} field; it reads back as
 * {@code null}.
 *
 * <p><b>Thread safety:</b> immutable record; safe to share across threads.
 *
 * @param exceptionType   the fully-qualified class name of the exception the
 *                        workflow method threw
 * @param message         that exception's message, or {@code null} if it had none
 * @param failedSequences the sequence numbers of the failure memos the escaping
 *                        exception carried (itself, its cause chain, its
 *                        suppressed exceptions), ascending and distinct; empty
 *                        when none did; {@code null} when a build before 0.x
 *                        (WP3) recorded the failure. Used by admin Retry
 */
public record WorkflowFailureDetail(
        String exceptionType,
        @Nullable String message,
        @Nullable List<Integer> failedSequences
) {

    /**
     * Copies {@code failedSequences} into an unmodifiable list when present.
     */
    public WorkflowFailureDetail {
        if (failedSequences != null) {
            failedSequences = List.copyOf(failedSequences);
        }
    }

    /**
     * Creates a detail without failure-memo attribution — the shape a build
     * before WP3 wrote ({@code failedSequences} is {@code null}).
     *
     * @param exceptionType the fully-qualified class name of the exception the
     *                      workflow method threw
     * @param message       that exception's message, or {@code null} if it had none
     */
    public WorkflowFailureDetail(String exceptionType, @Nullable String message) {
        this(exceptionType, message, null);
    }
}
