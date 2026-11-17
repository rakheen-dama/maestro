package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.observe.EngineObserver;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The startup recovery pass of a node whose recovery poller is disabled: one
 * <b>ungated</b> pass ({@link WorkflowExecutor#recoverWorkflows(Map)}) on its
 * own virtual thread, {@code maestro-startup-recovery-<service>}, so that the
 * caller — the Spring Boot starter's {@code ApplicationRunner} — never waits
 * for it.
 *
 * <p>The pass adopts every instance it can lock, whatever this node's
 * task-queue capacity; runs beyond a queue's bound wait for their slots on
 * their own threads. With no poller behind it, a failed pass would strand
 * every orphan until the next restart, so a pass that throws is contained by
 * a {@link BackgroundLoopGuard} (named {@value BackgroundLoopGuard#RECOVERY_POLLER},
 * logged and counted like the poller's failures) and retried after
 * {@code retryInterval}, until one pass completes. Then the thread ends. A
 * {@code MaestroControlFlowError} ends it at once.
 *
 * <p>{@link #requestStop()} never interrupts the thread: it ends the retry
 * wait at once, and a pass in progress ends at its next instance on this
 * loop's own stop flag (or once the executor is shutting down), so a store
 * call in progress is never interrupted.
 *
 * <p><b>Thread safety:</b> {@link #start()}, {@link #stop()},
 * {@link #requestStop()}, {@link #awaitStopped(Duration)} and
 * {@link #isRunning()} are thread-safe; the pass runs on the dedicated thread.
 */
final class OneShotRecovery implements StoppableLoop {

    private static final Logger logger = LoggerFactory.getLogger(OneShotRecovery.class);

    /** How long {@link #stop()} waits for the thread to end. */
    static final Duration STOP_JOIN = Duration.ofSeconds(5);

    private final WorkflowExecutor executor;
    private final Map<String, WorkflowRegistration> registrations;
    private final String serviceName;
    private final Duration retryInterval;
    private final BackgroundLoopGuard guard;
    private final AtomicBoolean started = new AtomicBoolean(false);
    /** Counted down by {@link #requestStop()}: ends the retry wait without an interrupt. */
    private final CountDownLatch stopRequested = new CountDownLatch(1);
    private volatile boolean running;
    private volatile @Nullable Thread thread;

    /**
     * Creates the one-shot pass; {@link #start()} runs it.
     *
     * @param executor      the executor whose ungated pass this runs
     * @param registrations workflow type → registration, as for {@link WorkflowExecutor#recoverWorkflows(Map)}
     * @param serviceName   the owning service name (thread name)
     * @param retryInterval the wait after a failed pass before the next; must be positive
     * @param observer      receives {@link EngineObserver#backgroundLoopError}
     *                      for every {@code Error} a pass throws
     * @throws IllegalArgumentException if {@code retryInterval} is {@code null},
     *                                  zero or negative
     */
    OneShotRecovery(
            WorkflowExecutor executor,
            Map<String, WorkflowRegistration> registrations,
            String serviceName,
            Duration retryInterval,
            EngineObserver observer
    ) {
        if (retryInterval == null || retryInterval.isNegative() || retryInterval.isZero()) {
            throw new IllegalArgumentException("retryInterval must be positive, got " + retryInterval);
        }
        this.executor = executor;
        this.registrations = Map.copyOf(registrations);
        this.serviceName = serviceName;
        this.retryInterval = retryInterval;
        this.guard = new BackgroundLoopGuard(BackgroundLoopGuard.RECOVERY_POLLER, retryInterval, () -> !running,
                observer);
    }

    /**
     * Starts the pass on a virtual thread.
     *
     * @throws IllegalStateException if already started
     */
    synchronized void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("One-shot startup recovery already started");
        }
        running = true;
        thread = Thread.ofVirtual()
                .name("maestro-startup-recovery-" + serviceName)
                .uncaughtExceptionHandler(guard.uncaughtExceptionHandler())
                .start(this::runUntilOnePassCompletes);
    }

    /**
     * {@link #requestStop()}, then {@link #awaitStopped(Duration) awaitStopped(STOP_JOIN)}.
     */
    void stop() {
        requestStop();
        awaitStopped(STOP_JOIN);
    }

    @Override
    public String loopName() {
        return BackgroundLoopGuard.RECOVERY_POLLER;
    }

    /**
     * Asks the pass to stop and returns at once: no retry starts, the retry
     * wait ends, and a pass in progress ends at its next instance. Never
     * interrupts the thread and never joins. Idempotent.
     */
    @Override
    public void requestStop() {
        running = false;
        stopRequested.countDown();
    }

    /**
     * Waits at most {@code bound} for the thread to end.
     *
     * @param bound the longest to wait; zero or negative only checks
     * @return {@code true} if the thread has ended or was never started
     */
    @Override
    public boolean awaitStopped(Duration bound) {
        var t = thread;
        if (t == null) {
            return true;
        }
        try {
            t.join(bound);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !t.isAlive();
    }

    /**
     * Whether the pass is still in progress or waiting to retry.
     *
     * @return {@code true} while the thread is alive
     */
    boolean isRunning() {
        var t = thread;
        return t != null && t.isAlive();
    }

    private void runUntilOnePassCompletes() {
        while (running) {
            try {
                var adopted = executor.recoverWorkflowsUntil(registrations, () -> !running);
                guard.succeeded();
                if (running) {
                    logger.info("Maestro startup recovery (recovery poller disabled) adopted {} workflow(s) without "
                            + "a capacity check; runs beyond a task queue's bound wait for their slots", adopted);
                }
                return;
            } catch (Throwable t) {
                // Rethrows a MaestroControlFlowError (ending the thread through
                // its uncaught handler); contains and logs everything else.
                guard.failed(t);
            }
            try {
                if (stopRequested.await(retryInterval.toNanos(), TimeUnit.NANOSECONDS)) {
                    break;
                }
            } catch (InterruptedException e) {
                // Not from requestStop(), which never interrupts; end as asked.
                Thread.currentThread().interrupt();
                break;
            }
        }
        logger.debug("One-shot startup recovery stopped before a pass completed");
    }
}
