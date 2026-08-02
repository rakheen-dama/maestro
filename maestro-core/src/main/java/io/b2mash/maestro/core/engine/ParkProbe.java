package io.b2mash.maestro.core.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Runs a park loop's advisory wake-recheck read, catching any store-raised
 * {@link RuntimeException} instead of letting it escape into
 * {@code WorkflowExecutor.executeWorkflow}'s generic failure handling
 * (Issue 20 / Ruling 5).
 *
 * <h2>Why the probe reads are safe to skip</h2>
 * <p>The reads this wraps — {@code standDownIfTerminated}'s instance read in
 * {@link SignalManager} and {@link DefaultWorkflowOperations}, the signal
 * poll inside {@code SignalManager.awaitSignal}'s recheck loop, and the
 * {@code findTimer} recheck inside {@code DefaultWorkflowOperations.sleep()}
 * (Issue 17) — exist only to notice, within one interval, something that
 * happened on <em>another</em> node: a cross-node terminate, a signal
 * persisted without a notification reaching this instance, or a timer fired
 * or cancelled by a remote leader. None of them write durable state. Missing
 * one interval only delays that convergence by one more interval; it can
 * never corrupt anything. So when the store itself is unreachable, the
 * correct behaviour is to fail open — treat the probe as inconclusive and
 * keep parking — rather than let the store's exception tear down a workflow
 * that was never unhealthy in the first place (a routine infra blip
 * recorded as workflow failure, the Issue 4/5/18 family).
 *
 * <p>This is deliberately narrow: it only ever wraps a probe <em>read</em>.
 * State writes (event appends, status CAS transitions, signal consumption)
 * are unaffected and keep failing exactly as before. {@code Error}s
 * ({@code ExecutorShutdownException}, {@code WorkflowTerminatedException})
 * are never thrown by the reads this wraps, so ordinary {@code catch
 * (RuntimeException)} here cannot intercept them — callers that derive a
 * terminate decision from the read result (e.g. {@code standDownIfTerminated})
 * still throw {@code WorkflowTerminatedException} themselves, outside this
 * method, once a probe actually succeeds.
 *
 * <p>Shared by {@link SignalManager} and {@link DefaultWorkflowOperations} —
 * both park loops need the identical catch-log-continue treatment, so it
 * lives once here rather than as two copies that would drift, mirroring
 * {@link InstanceStatusWriter}.
 *
 * <h2>Log rate limiting</h2>
 * <p>A sustained outage means every parked workflow's park loop probes and
 * fails on every interval — at a short recheck interval, or with many
 * parked workflows, that is a flood of WARNs about the same underlying
 * blip. A single process-wide counter logs the first failure of an outage
 * streak and then every {@value #LOG_EVERY}th failure after that, and
 * resets on the next successful probe from any park loop — recovery is
 * then silent, since the resumed operation is itself the signal that the
 * outage ended.
 *
 * <p><b>Thread safety:</b> stateless apart from the shared failure-streak
 * counter, which is safe for concurrent use from any number of parked
 * workflow threads.
 */
final class ParkProbe {

    private static final Logger logger = LoggerFactory.getLogger(ParkProbe.class);

    /** How often a failure is logged once an outage streak is underway. */
    private static final int LOG_EVERY = 20;

    private static final AtomicInteger failureStreak = new AtomicInteger();

    private ParkProbe() {
    }

    /**
     * Attempts an advisory store read, returning {@code fallback} instead of
     * propagating if the store throws.
     *
     * @param probeName  short name identifying the probe, for the WARN log
     * @param workflowId the workflow's business ID, for the WARN log
     * @param read       the store read to attempt
     * @param fallback   the value to return when the read fails — must mean
     *                   "inconclusive this interval, try again next time",
     *                   never "confirmed absent" or another value that would
     *                   make the caller treat the outage as a real outcome
     * @param <T>        the read's result type
     * @return the read's result, or {@code fallback} if the store threw a
     *         {@link RuntimeException}
     */
    static <T> T read(String probeName, String workflowId, Supplier<T> read, T fallback) {
        try {
            var result = read.get();
            failureStreak.set(0);
            return result;
        } catch (RuntimeException e) {
            var streak = failureStreak.incrementAndGet();
            if (streak == 1 || streak % LOG_EVERY == 0) {
                logger.warn("Wake-recheck probe '{}' failed for workflow '{}' (streak={}) — "
                                + "advisory read, store unreachable, continuing to park: {}",
                        probeName, workflowId, streak, e.toString());
            }
            return fallback;
        }
    }
}
