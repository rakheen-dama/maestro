/**
 * Workflow execution context — per-instance state bound to the workflow's virtual thread.
 *
 * <p>{@link io.maestro.core.context.WorkflowContext} carries the identity
 * and memoization state (sequence counter) for a running workflow. The
 * activity proxy reads the context to determine the current sequence number
 * for memoization lookups.
 *
 * <p>All types in this package are non-null by default.
 * Nullable fields are explicitly annotated with {@link org.jspecify.annotations.Nullable}.
 *
 * @see io.maestro.core.context.WorkflowContext
 */
@NullMarked
package io.maestro.core.context;

import org.jspecify.annotations.NullMarked;
