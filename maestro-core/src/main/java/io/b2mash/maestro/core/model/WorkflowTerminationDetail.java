package io.b2mash.maestro.core.model;

import org.jspecify.annotations.Nullable;

/**
 * Why a workflow was terminated: the payload the engine stores as the
 * instance {@linkplain WorkflowInstance#output() output} when it is
 * terminated — the audit trail for why the workflow was stopped.
 *
 * <p>The serialized form is a JSON object with exactly these two fields —
 * {@code {"reason": "...", "terminatedBy": "..."}}. Stored instances carry
 * that shape, so the component names are part of the persisted format and
 * must not be renamed.
 *
 * <p><b>Thread safety:</b> immutable record; safe to share across threads.
 *
 * @param reason       the caller-supplied reason, or {@code null} if none was given
 * @param terminatedBy who terminated the workflow (the engine records {@code "admin"})
 */
public record WorkflowTerminationDetail(@Nullable String reason, String terminatedBy) {}
