/**
 * Retry policies and execution logic for activity invocations.
 *
 * <p>The retry mechanism is used by the activity proxy to retry failed
 * activity calls with exponential backoff before recording a permanent
 * failure in the memoization log.
 *
 * <p>All types in this package are non-null by default.
 * Nullable fields are explicitly annotated with {@link org.jspecify.annotations.Nullable}.
 *
 * @see io.maestro.core.retry.RetryPolicy
 * @see io.maestro.core.retry.RetryExecutor
 */
@NullMarked
package io.maestro.core.retry;

import org.jspecify.annotations.NullMarked;
