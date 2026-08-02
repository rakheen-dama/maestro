package io.b2mash.maestro.core.retry;

import io.b2mash.maestro.core.exception.ActivityExecutionException;
import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.exception.MaestroControlFlowError;
import io.b2mash.maestro.core.exception.UnknownWorkflowHistoryException;
import io.b2mash.maestro.core.exception.WorkflowTerminatedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link RetryExecutor} must let every engine control-flow signal through
 * untouched (design §6.4 item 7).
 *
 * <h2>Why this is not merely tidy</h2>
 * <p>Compensation actions run through the activity proxy, which runs through
 * this executor — so a stand-down raised by a <em>replay read</em> nested
 * inside a retried task arrives here. If it were caught by the
 * {@code catch (Throwable)} arm it would be:
 * <ol>
 *   <li>retried, with exponential-backoff sleeps, several times over — each
 *       attempt re-reading the same unreadable row and failing identically;</li>
 *   <li>then wrapped in an {@link ActivityExecutionException}, which is an
 *       ordinary {@code Exception}. That wrapper reaches
 *       {@code executeWorkflow}'s {@code catch (Exception)} and the workflow is
 *       recorded {@code FAILED} <b>with compensation</b> — the exact outcome
 *       the stand-down mechanism exists to prevent, reinstated one layer
 *       down.</li>
 * </ol>
 * The pins below therefore assert both facts: the identical instance escapes,
 * and the task ran exactly once.
 */
@DisplayName("RetryExecutor never retries and never wraps a control-flow signal")
class RetryExecutorControlFlowTest {

    private final RetryExecutor retryExecutor = new RetryExecutor();

    /** Enough attempts and backoff that a wrongly-retried signal is unmistakable. */
    private static final RetryPolicy CHATTY = new RetryPolicy(
            5, Duration.ofMillis(20), Duration.ofMillis(40), 2.0, List.of(), List.of());

    @Test
    @Timeout(20)
    @DisplayName("an unknown-history stand-down escapes intact, after exactly one attempt")
    void unknownHistoryStandDownEscapesUnwrapped() {
        var signal = new UnknownWorkflowHistoryException("wf-1", 7,
                UnknownWorkflowHistoryException.Kind.UNKNOWN_EVENT_TYPE,
                "written by a newer node");
        assertEscapesIntact(signal);
    }

    @Test
    @Timeout(20)
    @DisplayName("a shutdown signal escapes intact, after exactly one attempt")
    void shutdownEscapesUnwrapped() {
        assertEscapesIntact(new ExecutorShutdownException("node stopping"));
    }

    @Test
    @Timeout(20)
    @DisplayName("a terminate signal escapes intact, after exactly one attempt")
    void terminateEscapesUnwrapped() {
        assertEscapesIntact(new WorkflowTerminatedException("wf-1", "operator asked"));
    }

    @Test
    @Timeout(20)
    @DisplayName("an ordinary failure is still retried and still wrapped — the guard is narrow")
    void ordinaryFailureIsStillRetriedAndWrapped() {
        var attempts = new AtomicInteger();

        assertThrows(ActivityExecutionException.class, () ->
                retryExecutor.executeWithRetry(CHATTY, () -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("a genuine activity failure");
                }, "act", "wf-1"));

        assertEquals(CHATTY.maxAttempts(), attempts.get(),
                "an ordinary failure must still exhaust the policy — the control-flow "
                        + "guard must not have swallowed the retry behaviour");
    }

    private void assertEscapesIntact(MaestroControlFlowError signal) {
        var attempts = new AtomicInteger();

        var thrown = assertThrows(MaestroControlFlowError.class, () ->
                retryExecutor.executeWithRetry(CHATTY, () -> {
                    attempts.incrementAndGet();
                    throw signal;
                }, "compensate-payment", "wf-1"));

        assertAll(
                () -> assertSame(signal, thrown,
                        "the identical signal instance must escape — wrapping it in "
                                + "ActivityExecutionException makes it catchable as an "
                                + "Exception and the workflow is recorded FAILED"),
                () -> assertEquals(1, attempts.get(),
                        "a control-flow signal is not a retryable failure: retrying it "
                                + "burns the backoff budget re-reading history that will "
                                + "not become readable"));
    }
}
