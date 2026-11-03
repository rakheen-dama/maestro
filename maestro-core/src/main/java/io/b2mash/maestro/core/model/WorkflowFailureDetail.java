package io.b2mash.maestro.core.model;

import org.jspecify.annotations.Nullable;

/**
 * Why a workflow failed: the payload the engine stores as the instance
 * {@linkplain WorkflowInstance#output() output} (and on the
 * {@link EventType#WORKFLOW_FAILED} event) when the workflow method ends
 * with an unhandled exception.
 *
 * <p>The serialized form is a JSON object with exactly these two fields —
 * {@code {"exceptionType": "...", "message": "..."}}. Stored histories carry
 * that shape and the stores' signal-timeout discriminator reads the
 * {@code exceptionType} field, so the component names are part of the
 * persisted format and must not be renamed.
 *
 * <p><b>Thread safety:</b> immutable record; safe to share across threads.
 *
 * @param exceptionType the fully-qualified class name of the exception the
 *                      workflow method threw
 * @param message       that exception's message, or {@code null} if it had none
 */
public record WorkflowFailureDetail(String exceptionType, @Nullable String message) {}
