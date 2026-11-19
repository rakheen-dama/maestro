package io.b2mash.maestro.store.postgres.lock;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.loop.FailingRule;
import io.b2mash.maestro.core.loop.LoopBody;
import io.b2mash.maestro.core.loop.LoopContext;
import io.b2mash.maestro.core.loop.LoopRole;
import io.b2mash.maestro.core.loop.LoopSpec;
import io.b2mash.maestro.core.loop.LoopSupervisor;
import io.b2mash.maestro.core.loop.Next;
import io.b2mash.maestro.core.observe.EngineObserver;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;

/**
 * Runs {@link PostgresLockCleaner#clean()} periodically, as the supervised
 * loop {@value #LOOP_NAME} (role housekeeping).
 *
 * <p>Expired lock rows are harmless to correctness — acquisition reclaims them
 * in place — but one row per workflow run that crashed mid-lock accumulates
 * forever without this. Every node runs its own scheduler; the delete is
 * idempotent, so overlap between nodes is wasted work, not a hazard. The first
 * run happens one interval after the start, never at startup, so a context
 * that is still wiring up (or a test with a stub {@code DataSource}) is not
 * surprised by a background query.
 *
 * <h2>Supervision</h2>
 * <p>{@link #start(WorkflowExecutor)} runs the loop under the executor's
 * {@code LoopSupervisor} (P2 task 6, Issue 65): it is one of
 * {@link WorkflowExecutor#loops()}, so the {@code maestro} health contributor
 * reports it ({@code lockCleanerRunning}; a failing cleaner is named in
 * {@code degraded}, a dead one reads {@code DOWN}), a contained {@code Error}
 * reaches the engine's observer as {@code backgroundLoopError("lock-cleaner", …)}
 * ({@code maestro.loop.errors}), every failed run moves
 * {@code maestro.loop.failures{loop=lock-cleaner}}, and the executor's shutdown
 * stops it with its other loops. {@link #start()} runs it under a supervisor of
 * its own with no observer, for a host that wires the lock without the engine.
 * Either way the supervisor contains every failure but a
 * {@code MaestroControlFlowError}, logs a failing streak once at {@code ERROR}
 * and then at most every five minutes, and waits one interval before the next
 * run.
 *
 * <h2>Stopping</h2>
 * <p>{@link #close()} asks the loop to stop and returns without waiting; it
 * never interrupts the thread, so a delete in progress is never interrupted
 * (CLAUDE.md; Issue 64) — it finishes and the loop ends after it. Under an
 * executor, the executor's shutdown waits for it within its one deadline.
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe: {@code start} and {@link #close()} may be called from any
 * thread; the first start wins and later ones do nothing, and a close is
 * terminal. The cleaner runs only on the loop's thread.
 */
public final class PostgresLockCleanerScheduler implements AutoCloseable, LoopBody {

    /** The loop's name: the {@code loop} tag of its meters and its name in health. */
    public static final String LOOP_NAME = "lock-cleaner";

    private static final Logger logger = LoggerFactory.getLogger(PostgresLockCleanerScheduler.class);

    private final PostgresLockCleaner cleaner;
    private final Duration interval;
    private @Nullable LoopSupervisor supervisor;
    private boolean closed;

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

    /**
     * Starts the loop under {@code executor}'s supervisor: in its health, its
     * meters and its shutdown. Does nothing if already started or closed.
     *
     * @param executor the executor whose supervisor runs the loop
     * @throws IllegalStateException if the executor is shutting down
     */
    public void start(WorkflowExecutor executor) {
        Objects.requireNonNull(executor, "executor");
        startWith(spec -> executor.supervise(spec, this));
    }

    /**
     * Starts the loop under a supervisor of its own, with no observer and no
     * health — for a host that runs the lock without the engine. Does nothing
     * if already started or closed.
     */
    public void start() {
        startWith(spec -> LoopSupervisor.start(spec, this, EngineObserver.NOOP));
    }

    private synchronized void startWith(Function<LoopSpec, LoopSupervisor> starter) {
        if (closed || supervisor != null) {
            return;
        }
        supervisor = starter.apply(spec());
        logger.info("Postgres lock cleaner scheduled every {}", interval);
    }

    /** The loop's spec: housekeeping, sleeps one interval first, failing after two failed runs in a row. */
    LoopSpec spec() {
        return LoopSpec.of(LOOP_NAME, "maestro-pg-lock-cleaner", LoopRole.HOUSEKEEPING, interval)
                .withInitialDelay(interval)
                .withFailing(FailingRule.streak(2));
    }

    /**
     * One run: deletes the lock rows expired for more than a minute.
     *
     * @param ctx the loop's context
     * @return wait one interval
     */
    @Override
    public Next iterate(LoopContext ctx) {
        var deleted = cleaner.clean();
        if (deleted > 0) {
            logger.debug("Lock cleaner removed {} expired row(s)", deleted);
        }
        return Next.afterPeriod();
    }

    /**
     * Asks the loop to stop, without an interrupt and without waiting: a run
     * in progress finishes. Idempotent and terminal.
     */
    @Override
    public void close() {
        LoopSupervisor loop;
        synchronized (this) {
            closed = true;
            loop = supervisor;
        }
        if (loop != null) {
            loop.requestStop();
        }
    }

    /**
     * Whether the loop was started and is neither stopping nor ended.
     *
     * @return {@code true} while the loop runs
     */
    boolean isRunning() {
        LoopSupervisor loop;
        synchronized (this) {
            loop = supervisor;
        }
        return loop != null && loop.snapshot().running();
    }
}
