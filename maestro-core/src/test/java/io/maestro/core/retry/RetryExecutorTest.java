package io.maestro.core.retry;

import io.maestro.core.exception.ActivityExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RetryExecutor}.
 *
 * <p>All tests use real execution (no mocking). Backoff tests use short
 * intervals to keep the test suite fast while still verifying that the
 * executor actually sleeps between attempts.
 */
class RetryExecutorTest {

    private final RetryExecutor executor = new RetryExecutor();

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Returns a callable that fails {@code failCount} times with the given
     * exception, then returns {@code successValue}.
     */
    private static <T> Callable<T> failingThenSucceeding(int failCount, Throwable failure, T successValue) {
        var counter = new AtomicInteger();
        return () -> {
            if (counter.getAndIncrement() < failCount) {
                if (failure instanceof Exception ex) {
                    throw ex;
                }
                throw new RuntimeException(failure);
            }
            return successValue;
        };
    }

    /**
     * Returns a callable that always throws the given exception.
     */
    private static <T> Callable<T> alwaysFailing(Throwable failure) {
        return () -> {
            if (failure instanceof Exception ex) {
                throw ex;
            }
            throw new RuntimeException(failure);
        };
    }

    /**
     * Builds a fast retry policy (tiny intervals) so tests don't block.
     */
    private static RetryPolicy fastPolicy(int maxAttempts) {
        return RetryPolicy.builder()
                .maxAttempts(maxAttempts)
                .initialInterval(Duration.ofMillis(1))
                .maxInterval(Duration.ofMillis(10))
                .backoffMultiplier(1.0)
                .build();
    }

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Successful first attempt returns value without retries")
    void successfulFirstAttempt() {
        var policy = fastPolicy(3);
        var counter = new AtomicInteger();

        var result = executor.executeWithRetry(policy, () -> {
            counter.incrementAndGet();
            return "hello";
        }, "greet", "wf-1");

        assertEquals("hello", result);
        assertEquals(1, counter.get(), "Task should have been called exactly once");
    }

    @Test
    @DisplayName("Success after retries - fails twice then succeeds on third attempt")
    void successAfterRetries() {
        var policy = fastPolicy(3);
        Callable<String> task = failingThenSucceeding(2, new RuntimeException("transient"), "recovered");

        var result = executor.executeWithRetry(policy, task, "flaky-activity", "wf-2");

        assertEquals("recovered", result);
    }

    @Test
    @DisplayName("Retries exhausted - throws ActivityExecutionException wrapping last cause")
    void retriesExhausted() {
        var policy = fastPolicy(3);
        var lastError = new RuntimeException("persistent failure");
        Callable<String> task = alwaysFailing(lastError);

        var thrown = assertThrows(ActivityExecutionException.class,
                () -> executor.executeWithRetry(policy, task, "doWork", "wf-3"));

        assertEquals("wf-3", thrown.workflowId());
        assertEquals("doWork", thrown.activityName());
        assertInstanceOf(RuntimeException.class, thrown.getCause());
        assertEquals("persistent failure", thrown.getCause().getMessage());
    }

    @Test
    @DisplayName("Non-retryable exception fails immediately without retry")
    void nonRetryableException() {
        var policy = RetryPolicy.builder()
                .maxAttempts(5)
                .initialInterval(Duration.ofMillis(1))
                .maxInterval(Duration.ofMillis(10))
                .backoffMultiplier(1.0)
                .nonRetryableExceptions(List.of(IllegalArgumentException.class))
                .build();

        var counter = new AtomicInteger();
        Callable<String> task = () -> {
            counter.incrementAndGet();
            throw new IllegalArgumentException("bad input");
        };

        var thrown = assertThrows(ActivityExecutionException.class,
                () -> executor.executeWithRetry(policy, task, "validate", "wf-4"));

        assertEquals(1, counter.get(), "Should fail on first attempt without retrying");
        assertInstanceOf(IllegalArgumentException.class, thrown.getCause());
        assertEquals("bad input", thrown.getCause().getMessage());
    }

    @Test
    @DisplayName("Retryable exception filter - only retries exceptions in retryableExceptions list")
    void retryableExceptionFilter() {
        var policy = RetryPolicy.builder()
                .maxAttempts(5)
                .initialInterval(Duration.ofMillis(1))
                .maxInterval(Duration.ofMillis(10))
                .backoffMultiplier(1.0)
                .retryableExceptions(List.of(IllegalStateException.class))
                .build();

        // An exception NOT in the retryable list should fail immediately
        var counter = new AtomicInteger();
        Callable<String> task = () -> {
            counter.incrementAndGet();
            throw new RuntimeException("not in retryable list");
        };

        var thrown = assertThrows(ActivityExecutionException.class,
                () -> executor.executeWithRetry(policy, task, "process", "wf-5"));

        assertEquals(1, counter.get(), "Non-matching exception should not be retried");
        assertInstanceOf(RuntimeException.class, thrown.getCause());

        // An exception IN the retryable list should be retried
        var retryCounter = new AtomicInteger();
        Callable<String> retryableTask = () -> {
            if (retryCounter.incrementAndGet() < 3) {
                throw new IllegalStateException("retryable");
            }
            return "ok";
        };

        var result = executor.executeWithRetry(policy, retryableTask, "process", "wf-5b");
        assertEquals("ok", result);
        assertEquals(3, retryCounter.get(), "Should have retried the IllegalStateException");
    }

    @Test
    @DisplayName("InvocationTargetException is unwrapped and the real cause is checked for retryability")
    void invocationTargetExceptionUnwrapping() {
        var policy = RetryPolicy.builder()
                .maxAttempts(3)
                .initialInterval(Duration.ofMillis(1))
                .maxInterval(Duration.ofMillis(10))
                .backoffMultiplier(1.0)
                .nonRetryableExceptions(List.of(IllegalArgumentException.class))
                .build();

        // Wrap a non-retryable exception in InvocationTargetException
        var counter = new AtomicInteger();
        Callable<String> task = () -> {
            counter.incrementAndGet();
            throw new InvocationTargetException(new IllegalArgumentException("wrapped bad input"));
        };

        var thrown = assertThrows(ActivityExecutionException.class,
                () -> executor.executeWithRetry(policy, task, "invoke", "wf-6"));

        assertEquals(1, counter.get(), "Should fail immediately - unwrapped cause is non-retryable");
        assertInstanceOf(IllegalArgumentException.class, thrown.getCause(),
                "Cause should be the unwrapped IllegalArgumentException, not the InvocationTargetException");
        assertEquals("wrapped bad input", thrown.getCause().getMessage());
    }

    @Test
    @DisplayName("Backoff calculation - executor sleeps between retries")
    void backoffCalculation() {
        // Use a measurable initial interval so we can verify elapsed time
        var policy = RetryPolicy.builder()
                .maxAttempts(3)
                .initialInterval(Duration.ofMillis(50))
                .maxInterval(Duration.ofSeconds(1))
                .backoffMultiplier(2.0)
                .build();

        Callable<String> task = alwaysFailing(new RuntimeException("always fails"));

        long startNanos = System.nanoTime();

        assertThrows(ActivityExecutionException.class,
                () -> executor.executeWithRetry(policy, task, "slow", "wf-7"));

        long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        // With 3 attempts, 2 backoff sleeps occur:
        // attempt 0 -> fail -> sleep 50ms (50 * 2^0)
        // attempt 1 -> fail -> sleep 100ms (50 * 2^1)
        // attempt 2 -> fail -> throw
        // Total minimum sleep: 150ms
        assertTrue(elapsedMs >= 100,
                "Expected at least 100ms of backoff sleep, but elapsed was " + elapsedMs + "ms");
    }

    @Test
    @DisplayName("noRetry policy fails immediately on first exception")
    void noRetryPolicy() {
        var policy = RetryPolicy.noRetry();

        var counter = new AtomicInteger();
        Callable<String> task = () -> {
            counter.incrementAndGet();
            throw new RuntimeException("one and done");
        };

        var thrown = assertThrows(ActivityExecutionException.class,
                () -> executor.executeWithRetry(policy, task, "oneShot", "wf-8"));

        assertEquals(1, counter.get(), "noRetry should execute exactly once");
        assertEquals("wf-8", thrown.workflowId());
        assertEquals("oneShot", thrown.activityName());
        assertEquals("one and done", thrown.getCause().getMessage());
    }

    @Test
    @DisplayName("defaultPolicy retries up to 3 attempts")
    void defaultPolicyRetriesThreeTimes() {
        // Verify that defaultPolicy() works and has maxAttempts=3
        var policy = RetryPolicy.defaultPolicy();
        assertEquals(3, policy.maxAttempts());

        var counter = new AtomicInteger();
        // Use a fast policy variant based on default values but with small intervals
        var fastDefault = RetryPolicy.builder()
                .maxAttempts(policy.maxAttempts())
                .initialInterval(Duration.ofMillis(1))
                .maxInterval(Duration.ofMillis(10))
                .backoffMultiplier(policy.backoffMultiplier())
                .build();

        Callable<String> task = () -> {
            counter.incrementAndGet();
            throw new RuntimeException("fail");
        };

        assertThrows(ActivityExecutionException.class,
                () -> executor.executeWithRetry(fastDefault, task, "check", "wf-9"));

        assertEquals(3, counter.get(), "Default policy should attempt exactly 3 times");
    }
}
