package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.loop.FailingRule;
import io.b2mash.maestro.core.loop.LoopBody;
import io.b2mash.maestro.core.loop.LoopContext;
import io.b2mash.maestro.core.loop.LoopRole;
import io.b2mash.maestro.core.loop.LoopSpec;
import io.b2mash.maestro.core.loop.Next;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;

/**
 * The startup recovery pass of a node whose recovery poller is disabled: one
 * <b>ungated</b> pass ({@link WorkflowExecutor#recoverWorkflows(Map)}), run
 * under the executor's {@link io.b2mash.maestro.core.loop.LoopSupervisor} as
 * loop {@value #LOOP_NAME} on its own virtual thread,
 * {@code maestro-startup-recovery-<service>}, so that the caller — the Spring
 * Boot starter's {@code ApplicationRunner} — never waits for it.
 *
 * <p>The pass adopts every instance it can lock, whatever this node's
 * task-queue capacity; runs beyond a queue's bound wait for their slots on
 * their own threads. With no poller behind it, a failed pass would strand
 * every orphan until the next restart, so a pass that throws is a failed
 * iteration — logged at the supervisor's cadence, counted in the loop's
 * streak, an {@link Error} also counted on
 * {@code maestro.loop.errors{loop=startup-recovery}} — and retried after
 * {@code retryInterval}, with no limit, until one pass completes. Then the
 * loop ends {@code DONE}. A {@code MaestroControlFlowError} ends it at once.
 *
 * <p>A stop never interrupts the thread: it ends the retry wait at once, and a
 * pass in progress ends at its next instance on the loop's stop flag (or once
 * the executor is shutting down), so a store call in progress is never
 * interrupted. A pass a stop cut short is not a completed one: the loop ends
 * {@code STOPPED}, not {@code DONE}.
 *
 * <p><b>Thread safety:</b> the supervisor runs {@link #iterate} on the loop's
 * thread only.
 */
final class OneShotRecovery implements LoopBody {

    private static final Logger logger = LoggerFactory.getLogger(OneShotRecovery.class);

    /** The loop's fixed name: its meter tag, log name and name in shutdown's log. */
    static final String LOOP_NAME = "startup-recovery";

    private final WorkflowExecutor executor;
    private final Map<String, WorkflowRegistration> registrations;
    private final String serviceName;
    private final Duration retryInterval;

    /**
     * Creates the one-shot pass; the executor supervises it.
     *
     * @param executor      the executor whose ungated pass this runs
     * @param registrations workflow type → registration, as for {@link WorkflowExecutor#recoverWorkflows(Map)}
     * @param serviceName   the owning service name (thread name)
     * @param retryInterval the wait after a failed pass before the next; must be positive
     * @throws IllegalArgumentException if {@code retryInterval} is {@code null},
     *                                  zero or negative
     */
    OneShotRecovery(
            WorkflowExecutor executor,
            Map<String, WorkflowRegistration> registrations,
            String serviceName,
            Duration retryInterval
    ) {
        if (retryInterval == null || retryInterval.isNegative() || retryInterval.isZero()) {
            throw new IllegalArgumentException("retryInterval must be positive, got " + retryInterval);
        }
        this.executor = executor;
        this.registrations = Map.copyOf(registrations);
        this.serviceName = serviceName;
        this.retryInterval = retryInterval;
    }

    /**
     * How the pass is supervised: critical; the first pass at once, a retry
     * every {@code retryInterval} after a failure; failing after two failed
     * passes in a row spanning at least two retry intervals.
     *
     * @return the pass's loop spec
     */
    LoopSpec spec() {
        return LoopSpec.of(LOOP_NAME, "maestro-startup-recovery-" + serviceName, LoopRole.CRITICAL, retryInterval)
                .withFailing(FailingRule.streakFor(2, retryInterval.multipliedBy(2)));
    }

    @Override
    public Next iterate(LoopContext ctx) {
        var adopted = executor.recoverWorkflowsUntil(registrations, ctx::stopRequested);
        if (ctx.stopRequested()) {
            logger.debug("One-shot startup recovery stopped before a pass completed");
            return Next.afterPeriod();       // the loop ends STOPPED: the pass did not complete
        }
        logger.info("Maestro startup recovery (recovery poller disabled) adopted {} workflow(s) without "
                + "a capacity check; runs beyond a task queue's bound wait for their slots", adopted);
        return Next.done();
    }
}
