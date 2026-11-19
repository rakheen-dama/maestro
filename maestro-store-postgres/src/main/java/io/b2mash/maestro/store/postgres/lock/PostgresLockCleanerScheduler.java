package io.b2mash.maestro.store.postgres.lock;

import io.b2mash.maestro.core.exception.MaestroControlFlowError;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs {@link PostgresLockCleaner#clean()} periodically on a virtual thread.
 *
 * <p>Expired lock rows are harmless to correctness — acquisition reclaims them
 * in place — but one row per workflow run that crashed mid-lock accumulates
 * forever without this. Every node runs its own scheduler; the delete is
 * idempotent, so overlap between nodes is wasted work, not a hazard. The first
 * run happens one interval after {@link #start()}, never at startup, so a
 * context that is still wiring up (or a test with a stub {@code DataSource})
 * is not surprised by a background query.
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe: {@link #start()} and {@link #close()} may be called from any
 * thread and are idempotent; the cleaner runs only on the scheduler's thread.
 * A run that fails does not end the loop: a {@code RuntimeException} is logged
 * at {@code WARN} and any other {@code Throwable} (an {@code Error}) at
 * {@code ERROR}, and the next run follows one interval later. Only a
 * {@code MaestroControlFlowError} ends it, and the thread's end is logged at
 * {@code ERROR} through SLF4J.
 */
public final class PostgresLockCleanerScheduler implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(PostgresLockCleanerScheduler.class);

    private final PostgresLockCleaner cleaner;
    private final Duration interval;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile @Nullable Thread thread;

    /**
     * @param cleaner  the cleaner to run
     * @param interval time between runs; must be positive
     */
    public PostgresLockCleanerScheduler(PostgresLockCleaner cleaner, Duration interval) {
        this.cleaner = Objects.requireNonNull(cleaner, "cleaner");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("maestro.lock.cleanup-interval must be positive, was " + interval);
        }
    }

    /** Starts the loop. Idempotent. */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        thread = Thread.ofVirtual()
                .name("maestro-pg-lock-cleaner")
                .uncaughtExceptionHandler((th, e) ->
                        logger.error("Postgres lock cleaner stopped: {} escaped it", e.toString(), e))
                .start(this::loop);
        logger.info("Postgres lock cleaner scheduled every {}", interval);
    }

    /**
     * Starts the loop under {@code executor} (stub: not yet supervised).
     *
     * @param executor the executor whose supervisor runs the loop
     */
    public void start(io.b2mash.maestro.core.engine.WorkflowExecutor executor) {
        start();
    }

    /** Stops the loop; a run in progress finishes. Idempotent. */
    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        var t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    /**
     * Whether the cleaner's thread is alive and the scheduler not closed.
     *
     * @return {@code true} while the loop runs
     */
    boolean isRunning() {
        var t = thread;
        return running.get() && t != null && t.isAlive();
    }

    private void loop() {
        while (running.get()) {
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!running.get()) {
                return;
            }
            try {
                var deleted = cleaner.clean();
                if (deleted > 0) {
                    logger.debug("Lock cleaner removed {} expired row(s)", deleted);
                }
            } catch (Throwable t) {
                // The engine's control-flow rule: rethrown, it ends the loop.
                if (t instanceof MaestroControlFlowError controlFlow) {
                    throw controlFlow;
                }
                if (!running.get()) {
                    // close() interrupted a run that then threw: expected.
                    logger.debug("Postgres lock cleaner run failed while stopping (ignored): {}", t.toString());
                } else if (t instanceof RuntimeException) {
                    logger.warn("Postgres lock cleaner run failed; will retry in {}: {}", interval, t.toString());
                } else {
                    logger.error("Postgres lock cleaner run failed with {}; it keeps running and retries in {}",
                            t.toString(), interval, t);
                }
            }
        }
    }
}
