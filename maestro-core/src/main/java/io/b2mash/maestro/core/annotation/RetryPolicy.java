package io.b2mash.maestro.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares retry configuration for activity method invocations.
 *
 * <p>This annotation is used as an attribute of {@link ActivityStub} to
 * configure how the activity proxy retries failed invocations before
 * recording a permanent failure.
 *
 * <h2>Backoff Strategy</h2>
 * <p>Exponential backoff: delay = min(initialInterval × backoffMultiplier^attempt, maxInterval).
 * On virtual threads, the backoff sleep yields the carrier thread — no
 * platform thread is blocked.
 *
 * <h2>Exception Filtering</h2>
 * <p>If {@link #retryableExceptions()} is non-empty, only those exception
 * types (and their subtypes) are retried. If {@link #nonRetryableExceptions()}
 * is non-empty, those types immediately fail without retry. If both are
 * empty, all exceptions are retried.
 *
 * @see ActivityStub
 * @see io.b2mash.maestro.core.retry.RetryPolicy
 */
@Documented
@Target({})  // only used as an attribute of @ActivityStub
@Retention(RetentionPolicy.RUNTIME)
public @interface RetryPolicy {

    /** Maximum number of attempts (including the initial attempt). */
    int maxAttempts() default 3;

    /** Initial delay between retries, as an ISO 8601 duration (e.g., {@code "PT1S"}). */
    String initialInterval() default "PT1S";

    /** Maximum delay between retries, as an ISO 8601 duration (e.g., {@code "PT1M"}). */
    String maxInterval() default "PT1M";

    /** Multiplier applied to the delay after each retry attempt. */
    double backoffMultiplier() default 2.0;

    /**
     * Exception types that should be retried. If empty, all exceptions
     * are retried (unless excluded by {@link #nonRetryableExceptions()}).
     */
    Class<? extends Throwable>[] retryableExceptions() default {};

    /**
     * Exception types that should never be retried. Takes precedence
     * over {@link #retryableExceptions()}.
     */
    Class<? extends Throwable>[] nonRetryableExceptions() default {};
}
