package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.MaestroControlFlowError;
import io.b2mash.maestro.core.observe.CompositeEngineObserver;
import io.b2mash.maestro.core.observe.EngineObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Decides, for one iteration of one of the engine's background loops that
 * threw, whether the throwable ends the loop or is contained — and rate-limits
 * what is logged while a loop keeps failing.
 *
 * <p>Each loop wraps its iteration body as
 * {@code try { …; guard.succeeded(); } catch (InterruptedException e) { …exit… }
 * catch (Throwable t) { guard.failed(t); }} and builds its thread with
 * {@link #uncaughtExceptionHandler()}. The loop's own wait follows unchanged:
 * the guard adds no wait and no back-off of its own, so a loop keeps its
 * schedule after a contained failure, and what is rate-limited is the log —
 * one {@code ERROR} with the stack trace when a streak starts, {@code DEBUG}
 * for repeats, one {@code ERROR} reminder at most every
 * {@link #REMINDER_INTERVAL} while it continues, and one {@code INFO} when the
 * loop recovers. A loop whose interval is at least {@code REMINDER_INTERVAL}
 * (the hourly janitor and sweeper) therefore logs every failure at
 * {@code ERROR}; its first line says so.
 *
 * <h2>Why a per-iteration guard, not a runner that owns the loop</h2>
 * <p>The five loops differ in shape: the recovery poller and the instance-lock
 * renewer sleep first, the signal janitor and the retention sweeper sweep
 * first, and the timer poller runs its clock-skew check in a {@code finally}.
 * Later work rewrites their joins, the recovery poller's wait and the inside
 * of the sweeps. A guard that only decides "rethrow or contain" leaves every
 * loop's structure, wait and stop path where it is.
 *
 * <h2>What is contained</h2>
 * <p>Only a {@link MaestroControlFlowError} is rethrown (unchanged, uncounted,
 * unlogged); it ends the loop, as the engine's control-flow rule requires.
 * Every other {@code Throwable} is contained and followed by the loop's normal
 * wait, and every contained {@link Error} is reported through
 * {@link EngineObserver#backgroundLoopError}: an {@link OutOfMemoryError}
 * (the iteration's allocations are garbage once it unwinds, a dead daemon loop
 * is strictly worse than a logged retry, and exiting on OOM is the host's JVM
 * flag, not Maestro's decision), a {@link StackOverflowError} (thread-local and
 * fully recovered once the stack unwinds), a {@link LinkageError} such as
 * {@link NoClassDefFoundError} or {@link ExceptionInInitializerError} (a bad
 * deploy that repeats every iteration — logged once loudly, then every five
 * minutes, and counted, which is visible and alertable where a dead loop is
 * not), and an {@link AssertionError} or any other {@code Error} (a bug; the
 * same treatment). An {@link Exception} is contained and logged but not
 * reported to the observer: those are store or backend faults that other
 * signals already show.
 *
 * <h2>Thread safety</h2>
 * <p>Confined to its loop's thread; {@link #consecutiveFailures()} may be read
 * from any thread.
 */
final class BackgroundLoopGuard {

    private static final Logger logger = LoggerFactory.getLogger(BackgroundLoopGuard.class);

    /** Loop name of {@link TimerPoller}. */
    static final String TIMER_POLLER = "timer-poller";
    /** Loop name of {@link RecoveryPoller}. */
    static final String RECOVERY_POLLER = "recovery-poller";
    /** Loop name of {@link SignalJanitor}. */
    static final String SIGNAL_JANITOR = "signal-janitor";
    /** Loop name of {@link RetentionSweeper}. */
    static final String RETENTION_SWEEPER = "retention-sweeper";
    /** Loop name of {@link WorkflowInstanceLockManager}'s renewer. */
    static final String INSTANCE_LOCK_RENEWER = "instance-lock-renewer";

    /** How long a failing loop stays quiet between two {@code ERROR}s. */
    static final Duration REMINDER_INTERVAL = Duration.ofMinutes(5);

    /** What {@link #failed(Throwable)} logged. */
    enum Logged {
        /** The first failure of a streak: {@code ERROR} with the stack trace. */
        FIRST,
        /** A later failure within {@link #REMINDER_INTERVAL} of the last {@code ERROR}: {@code DEBUG}. */
        REPEAT,
        /** A later failure at least {@link #REMINDER_INTERVAL} after the last {@code ERROR}: {@code ERROR}. */
        REMINDER,
        /** A failure while the loop is being stopped: {@code DEBUG}, uncounted. */
        STOPPING
    }

    private final String loop;
    private final Duration retryInterval;
    private final BooleanSupplier stopping;
    private final CompositeEngineObserver observer;
    private final LongSupplier nanoTime;

    private volatile int streak;
    private long streakStartNanos;
    private long lastLoudNanos;

    /**
     * Creates a guard on the system's monotonic clock.
     *
     * @param loop          the loop's fixed name (one of the constants above)
     * @param retryInterval the loop's own wait after a failed iteration (logged only)
     * @param stopping      {@code true} once the loop is being stopped
     * @param observer      receives {@link EngineObserver#backgroundLoopError} per contained {@code Error}
     */
    BackgroundLoopGuard(String loop, Duration retryInterval, BooleanSupplier stopping, EngineObserver observer) {
        this(loop, retryInterval, stopping, observer, System::nanoTime);
    }

    /**
     * Creates a guard on an injected monotonic clock (test seam).
     *
     * @param loop          the loop's fixed name (one of the constants above)
     * @param retryInterval the loop's own wait after a failed iteration (logged only)
     * @param stopping      {@code true} once the loop is being stopped
     * @param observer      receives {@link EngineObserver#backgroundLoopError} per contained {@code Error}
     * @param nanoTime      monotonic clock in nanoseconds, as {@link System#nanoTime()}
     */
    BackgroundLoopGuard(String loop, Duration retryInterval, BooleanSupplier stopping, EngineObserver observer,
                        LongSupplier nanoTime) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.retryInterval = Objects.requireNonNull(retryInterval, "retryInterval");
        this.stopping = Objects.requireNonNull(stopping, "stopping");
        this.observer = CompositeEngineObserver.contain(Objects.requireNonNull(observer, "observer"));
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * Handles one iteration that threw.
     *
     * <p>A {@link MaestroControlFlowError} is rethrown unchanged, without
     * logging or counting it. While the loop is stopping, any other failure is
     * logged at {@code DEBUG} and neither counted nor reported. Otherwise the
     * failure extends the streak and is logged by the cadence in the class
     * Javadoc, and an {@link Error} is reported to the observer.
     *
     * <p>Logging is contained too: a logger that throws, or a failure whose
     * {@code toString()} throws, cannot escape — the failure is still counted
     * and the observer still told.
     *
     * @param failure what the iteration threw
     * @return what was logged
     * @throws MaestroControlFlowError when {@code failure} is one; otherwise
     *         this never throws
     */
    Logged failed(Throwable failure) {
        if (failure instanceof MaestroControlFlowError controlFlow) {
            throw controlFlow;
        }
        if (stopping.getAsBoolean()) {
            log(failure, () -> logger.debug("Background loop '{}' failed while stopping (ignored): {}",
                    loop, describe(failure)));
            return Logged.STOPPING;
        }
        var now = nanoTime.getAsLong();
        var n = streak + 1;
        streak = n;
        Logged logged;
        if (n == 1) {
            streakStartNanos = now;
            lastLoudNanos = now;
            logged = Logged.FIRST;
            log(failure, () -> logger.error("Background loop '{}' failed ({}); it keeps running and retries every "
                            + "{} — {}",
                    loop, describe(failure), plain(retryInterval), whatFollows(), failure));
        } else if (now - lastLoudNanos >= REMINDER_INTERVAL.toNanos()) {
            lastLoudNanos = now;
            logged = Logged.REMINDER;
            var minutes = TimeUnit.NANOSECONDS.toMinutes(now - streakStartNanos);
            log(failure, () -> logger.error(
                    "Background loop '{}' is still failing: {} consecutive failure(s) over {} min; latest: {}",
                    loop, n, minutes, describe(failure)));
        } else {
            logged = Logged.REPEAT;
            log(failure, () -> logger.debug("Background loop '{}' failed again ({} in a row)", loop, n, failure));
        }
        if (failure instanceof Error) {
            // Contained by the composite (Issue 81): a throwing observer cannot
            // end the loop this guard is keeping alive; a control-flow Error
            // from it is still rethrown.
            observer.backgroundLoopError(loop, failure.getClass().getName());
        }
        return logged;
    }

    /**
     * Runs one logging call for {@code failure}, containing anything it
     * throws — a logger or appender that fails, or a throwable whose
     * {@code toString()}/{@code getMessage()} throws while it is rendered —
     * so the guard's catch arm cannot itself end the loop. Falls back to one
     * line naming the failure's class; if that throws too, nothing is logged.
     */
    private void log(Throwable failure, Runnable call) {
        try {
            call.run();
        } catch (Throwable t) {
            if (t instanceof MaestroControlFlowError controlFlow) {
                throw controlFlow;
            }
            try {
                logger.error("Background loop '{}' failed with {}; logging it threw {}",
                        loop, failure.getClass().getName(), t.getClass().getName());
            } catch (Throwable ignored) {
                if (ignored instanceof MaestroControlFlowError controlFlow) {
                    throw controlFlow;
                }
                // Nothing left to log with; the failure is still counted.
            }
        }
    }

    /**
     * What the first failure's line promises about the ones after it. A loop
     * whose interval is at least {@link #REMINDER_INTERVAL} never fails twice
     * within it, so each of its further failures is a reminder.
     */
    private String whatFollows() {
        return retryInterval.compareTo(REMINDER_INTERVAL) < 0
                ? "further failures are logged at DEBUG, with an ERROR reminder at most every 5 minutes while they "
                        + "continue"
                : "each further failure is logged at ERROR, without the stack trace, until it recovers";
    }

    /** {@code d} in one plain unit: {@code 50 ms}, {@code 5 s}, {@code 10 min}, {@code 1 h}. */
    static String plain(Duration d) {
        var ms = d.toMillis();
        if (ms != 0 && ms % 3_600_000 == 0) {
            return ms / 3_600_000 + " h";
        }
        if (ms != 0 && ms % 60_000 == 0) {
            return ms / 60_000 + " min";
        }
        if (ms != 0 && ms % 1000 == 0) {
            return ms / 1000 + " s";
        }
        return ms + " ms";
    }

    /**
     * {@code failure.toString()}, or its class name when {@code toString()}
     * itself throws.
     */
    private static String describe(Throwable failure) {
        try {
            return failure.toString();
        } catch (Throwable t) {
            if (t instanceof MaestroControlFlowError controlFlow) {
                throw controlFlow;
            }
            return failure.getClass().getName() + " (its toString() threw " + t.getClass().getName() + ")";
        }
    }

    /**
     * Records an iteration that completed; ends a failure streak, if any.
     *
     * @return {@code true} when a streak ended and the recovery line was logged
     */
    boolean succeeded() {
        var n = streak;
        if (n == 0) {
            return false;
        }
        streak = 0;
        logger.info("Background loop '{}' recovered after {} consecutive failure(s)", loop, n);
        return true;
    }

    /**
     * The current streak: failures since the last success, not counting
     * failures while stopping. Safe to call from any thread.
     *
     * @return the number of consecutive failed iterations
     */
    int consecutiveFailures() {
        return streak;
    }

    /**
     * The handler to build the loop's thread with: it reports a throwable that
     * ended the loop through SLF4J — {@code ERROR} with the stack trace, or
     * {@code DEBUG} while stopping — instead of the JDK's default handler on
     * {@code System.err}. It never rethrows the throwable it is handed (and
     * the JVM ignores anything an uncaught handler throws).
     *
     * @return the uncaught-exception handler for this loop's thread
     */
    Thread.UncaughtExceptionHandler uncaughtExceptionHandler() {
        return (thread, failure) -> {
            if (stopping.getAsBoolean()) {
                logger.debug("Background loop '{}' exited while stopping: {}", loop, describe(failure));
            } else {
                logger.error("Background loop '{}' stopped: {} escaped it — it will not run again on this "
                        + "node until the node restarts", loop, describe(failure), failure);
            }
        };
    }
}
