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
 * @see io.b2mash.maestro.core.retry.RetryPolicy
 * @see io.b2mash.maestro.core.retry.RetryExecutor
 */
@NullMarked
package io.b2mash.maestro.core.retry;

import org.jspecify.annotations.NullMarked;
