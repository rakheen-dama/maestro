package io.b2mash.maestro.core.loop;

import io.b2mash.maestro.core.exception.MaestroControlFlowError;
import io.b2mash.maestro.core.observe.CompositeEngineObserver;
import io.b2mash.maestro.core.observe.EngineObserver;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Runs one background loop: owns its virtual thread, its waits, its failure
 * streak, its last success and its stop. Internal until 1.0 (see the package
 * Javadoc); the engine builds supervisors through
 * {@code WorkflowExecutor.supervise(LoopSpec, LoopBody)}.
 *
 * <h2>What the thread does</h2>
 * <pre>
 * sleep(initialDelay)                         — on the stop latch
 * while not stopping:
 *     next = body.iterate(ctx)                — a success ends a failure streak
 *     on a throwable:
 *         MaestroControlFlowError  → rethrown: the loop ends DEAD (STOPPED if stopping)
 *         anything, while stopping → DEBUG, uncounted; the loop ends
 *         anything else            → streak + 1, logged by the cadence below,
 *                                    an Error reported to backgroundLoopError;
 *                                    next = the spec's back-off
 *     Next.done() → the loop ends DONE; otherwise wait as next says
 * finally: body.onExit(), then the end is published
 * </pre>
 *
 * <h2>What is contained</h2>
 * <p>Only a {@link MaestroControlFlowError} is rethrown (unchanged, uncounted,
 * unlogged by the iteration's arm; the thread's uncaught handler logs it). Every
 * other {@code Throwable} is contained and followed by the spec's back-off, and
 * every contained {@link Error} is reported through
 * {@link EngineObserver#backgroundLoopError}: an {@link OutOfMemoryError} (the
 * iteration's allocations are garbage once it unwinds, a dead daemon loop is
 * strictly worse than a logged retry, and exiting on OOM is the host's JVM
 * flag, not Maestro's decision), a {@link StackOverflowError} (thread-local and
 * fully recovered once the stack unwinds), a {@link LinkageError} such as
 * {@link NoClassDefFoundError} or {@link ExceptionInInitializerError} (a bad
 * deploy that repeats every iteration — logged once loudly, then every five
 * minutes, and counted, which is visible and alertable where a dead loop is
 * not), and an {@link AssertionError} or any other {@code Error} (a bug; the
 * same treatment). An {@link Exception} — a store or lock-backend fault,
 * {@link InterruptedException} included (the flag is cleared) — is contained,
 * counted in the streak and logged, not reported to the observer.
 *
 * <h2>Log cadence</h2>
 * <p>One {@code ERROR} with the stack trace when a streak starts,
 * {@code DEBUG} for repeats, one {@code ERROR} reminder at most every
 * {@link #REMINDER_INTERVAL} while it continues, and one {@code INFO} when the
 * loop recovers. A loop whose back-off is at least {@code REMINDER_INTERVAL}
 * (the hourly janitor and sweeper) therefore logs every failure at
 * {@code ERROR}; its first line says so. Logging is contained too: a logger
 * that throws, or a failure whose {@code toString()} throws, cannot end the
 * loop.
 *
 * <h2>Stopping</h2>
 * <p>{@link #requestStop()} sets the stop flag, counts the stop latch down —
 * which ends every supervised wait at once — and runs the spec's
 * {@link LoopSpec#onStop()} hooks. It never interrupts the loop's thread, so a
 * store call in progress is never interrupted (CLAUDE.md, Issue 64); an
 * iteration in progress finishes, and {@link #awaitStopped(Duration)} waits for
 * it at most its bound. Nothing in Maestro interrupts a supervised thread; a
 * stray interrupt is cleared and never read as a stop.
 *
 * <h2>Thread safety</h2>
 * <p>{@link #requestStop()}, {@link #awaitStopped(Duration)},
 * {@link #snapshot()} and {@link #name()} are safe from any thread. Only the
 * loop's thread runs the body and publishes its state: each change (iteration
 * start, success, failure, note, end) is a new immutable record in one
 * {@link AtomicReference}, so a snapshot is one volatile read and never torn.
 */
public final class LoopSupervisor {

    private static final Logger logger = LoggerFactory.getLogger(LoopSupervisor.class);

    /** How long a failing loop stays quiet between two {@code ERROR}s. */
    static final Duration REMINDER_INTERVAL = Duration.ofMinutes(5);

    /** {@code iterationStartNanos} between iterations. */
    private static final long NOT_IN_ITERATION = Long.MIN_VALUE;

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

    /**
     * Everything a snapshot shows, published as one immutable record by the
     * loop's thread (the single writer).
     */
    private record Status(LoopSnapshot.State state, int streak, long streakStartNanos,
                          @Nullable Instant failingSince, @Nullable Instant lastSuccess,
                          @Nullable String lastFailureType, long iterationStartNanos, Map<String, Object> notes) {

        Status withState(LoopSnapshot.State next) {
            return new Status(next, streak, streakStartNanos, failingSince, lastSuccess, lastFailureType,
                    NOT_IN_ITERATION, notes);
        }

        Status inIteration(long nanos) {
            return new Status(state, streak, streakStartNanos, failingSince, lastSuccess, lastFailureType, nanos,
                    notes);
        }

        Status withNote(String key, Object value) {
            var copy = new HashMap<>(notes);
            copy.put(key, value);
            return new Status(state, streak, streakStartNanos, failingSince, lastSuccess, lastFailureType,
                    iterationStartNanos, Map.copyOf(copy));
        }
    }

    private final LoopSpec spec;
    private final LoopBody body;
    private final EngineObserver observer;
    private final LongSupplier nanoTime;
    private final InstantSource wallClock;
    private final Context context = new Context();
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    /** Counted down by {@link #requestStop()}: ends every supervised wait at once. */
    private final CountDownLatch stop = new CountDownLatch(1);
    /** Counted down as the loop's thread ends, after {@link LoopBody#onExit()}. */
    private final CountDownLatch exited = new CountDownLatch(1);
    private final AtomicReference<Status> status;
    private volatile boolean started;
    /** When the last loud ({@code ERROR}) failure line was logged; the loop's thread only. */
    private long lastLoudNanos;

    private LoopSupervisor(LoopSpec spec, LoopBody body, EngineObserver observer, LongSupplier nanoTime,
                           InstantSource wallClock) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.body = Objects.requireNonNull(body, "body");
        this.observer = CompositeEngineObserver.contain(observer);
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
        this.status = new AtomicReference<>(new Status(LoopSnapshot.State.STARTING, 0, 0, null, null, null,
                NOT_IN_ITERATION, Map.of()));
    }

    /**
     * Builds a supervisor and starts its loop on a new virtual thread named
     * {@link LoopSpec#threadName()}. The engine calls this through
     * {@code WorkflowExecutor.supervise}, which also stops the loop at
     * shutdown; prefer that.
     *
     * @param spec     what the loop is and how it is run
     * @param body     the loop's work
     * @param observer receives {@link EngineObserver#backgroundLoopError} for
     *                 every contained {@code Error}; contained itself
     * @return the running supervisor
     * @throws RuntimeException if the thread cannot be started; nothing runs then
     */
    public static LoopSupervisor start(LoopSpec spec, LoopBody body, EngineObserver observer) {
        var supervisor = new LoopSupervisor(spec, body, observer, System::nanoTime, InstantSource.system());
        supervisor.start();
        return supervisor;
    }

    /** A supervisor on injected clocks, not started (test seam). */
    static LoopSupervisor unstarted(LoopSpec spec, LoopBody body, EngineObserver observer, LongSupplier nanoTime,
                                    InstantSource wallClock) {
        return new LoopSupervisor(spec, body, observer, nanoTime, wallClock);
    }

    /** Starts the loop's thread; once. */
    void start() {
        if (started) {
            throw new IllegalStateException("Background loop '" + spec.name() + "' already started");
        }
        var thread = Thread.ofVirtual()
                .name(spec.threadName())
                .uncaughtExceptionHandler(uncaughtExceptionHandler())
                .unstarted(this::run);
        started = true;
        try {
            thread.start();
        } catch (RuntimeException | Error e) {
            started = false;
            throw e;
        }
    }

    /**
     * @return the loop's fixed name ({@link LoopSpec#name()})
     */
    public String name() {
        return spec.name();
    }

    /**
     * Asks the loop to stop and returns at once: no new iteration starts,
     * every supervised wait ends, the spec's stop hooks run (each contained),
     * and an iteration in progress finishes on its own. Never interrupts the
     * loop's thread and never joins. Idempotent; harmless after the loop ended.
     */
    public void requestStop() {
        if (!stopRequested.compareAndSet(false, true)) {
            return;
        }
        stop.countDown();
        for (var hook : spec.onStop()) {
            try {
                hook.run();
            } catch (Throwable t) {
                if (t instanceof MaestroControlFlowError controlFlow) {
                    throw controlFlow;
                }
                // Contained like every log line here (PR #74 review): a hook
                // that throws something unprintable, or a logger that throws,
                // must not skip the hooks after this one.
                log(t, () -> logger.warn("A stop hook of background loop '{}' threw — ignoring: {}", spec.name(),
                        describe(t)));
            }
        }
    }

    /**
     * Waits at most {@code bound} for the loop's thread to end. If the calling
     * thread is interrupted while it waits, its interrupt status is restored
     * and the wait ends as if the bound had passed.
     *
     * @param bound the longest to wait; zero or negative only checks
     * @return {@code true} if the loop has ended (or was never started),
     *         {@code false} if it is still running when the wait ends
     */
    public boolean awaitStopped(Duration bound) {
        if (!started) {
            return true;
        }
        try {
            return exited.await(Math.max(0, bound.toNanos()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return exited.getCount() == 0;
        }
    }

    /**
     * One consistent reading of the loop, from any thread, without locking.
     *
     * @return the loop's current snapshot
     */
    public LoopSnapshot snapshot() {
        var s = status.get();
        var state = s.state();
        if (!state.ended() && stopRequested.get()) {
            state = LoopSnapshot.State.STOPPING;
        }
        var now = nanoTime.getAsLong();
        var streakAge = s.streak() > 0 ? Duration.ofNanos(now - s.streakStartNanos()) : null;
        var inIterationFor = s.iterationStartNanos() == NOT_IN_ITERATION
                ? null : Duration.ofNanos(now - s.iterationStartNanos());
        return new LoopSnapshot(spec.name(), spec.role(), state, s.streak(), s.failingSince(), s.lastSuccess(),
                s.lastFailureType(), inIterationFor, spec.failing().isFailing(s.streak(), streakAge), s.notes());
    }

    // ── The loop ─────────────────────────────────────────────────────────

    private void run() {
        var done = false;
        try {
            status.set(status.get().withState(LoopSnapshot.State.RUNNING));
            if (spec.initialDelay().isPositive() && !context.sleep(spec.initialDelay())) {
                return;
            }
            while (!stopRequested.get()) {
                Next next;
                status.set(status.get().inIteration(nanoTime.getAsLong()));
                try {
                    next = body.iterate(context);
                    clearStrayInterrupt();
                    if (next instanceof Next.Await) {
                        // Not a success yet: the iteration includes its custom
                        // wait, which may still fail (PR #74 review). awaitCustom
                        // commits the outcome.
                        status.set(status.get().inIteration(NOT_IN_ITERATION));
                    } else {
                        succeeded();
                    }
                } catch (Throwable t) {
                    clearStrayInterrupt();
                    status.set(status.get().inIteration(NOT_IN_ITERATION));
                    if (failed(t) == Logged.STOPPING) {
                        break;
                    }
                    next = Next.after(spec.backoff().after(status.get().streak()));
                }
                if (next instanceof Next.Done) {
                    done = true;
                    break;
                }
                if (!waitFor(next)) {
                    break;
                }
            }
        } finally {
            end(done);
        }
    }

    /** Runs {@code onExit}, publishes how the loop ended and releases {@link #awaitStopped}. */
    private void end(boolean done) {
        MaestroControlFlowError exitFailure = null;
        try {
            body.onExit();
        } catch (Throwable t) {
            if (t instanceof MaestroControlFlowError controlFlow) {
                exitFailure = controlFlow;
            } else {
                log(t, () -> logger.warn("Background loop '{}' failed to clean up as it ended: {}", spec.name(),
                        describe(t), t));
            }
        }
        try {
            var state = done ? LoopSnapshot.State.DONE
                    : stopRequested.get() ? LoopSnapshot.State.STOPPED : LoopSnapshot.State.DEAD;
            status.set(status.get().withState(state));
            switch (state) {
                case STOPPED -> quietly(() -> logger.info("Background loop '{}' stopped", spec.name()));
                case DONE -> quietly(() -> logger.debug("Background loop '{}' finished its work", spec.name()));
                default -> { } // DEAD: the uncaught handler logs what escaped
            }
        } finally {
            // Whatever the lines above throw, the loop has ended: a waiter must
            // not wait out its deadline for it (review F3).
            exited.countDown();
        }
        if (exitFailure != null) {
            throw exitFailure;
        }
    }

    /** Waits as {@code next} says; {@code false} when the loop should stop. */
    private boolean waitFor(Next next) {
        return switch (next) {
            case Next.AfterPeriod _ -> context.sleep(spec.period());
            case Next.After after -> context.sleep(after.delay());
            case Next.Immediately _ -> !stopRequested.get();
            case Next.Done _ -> false;
            // Checked before the wait is armed: a stop that landed during the
            // iteration ran the stop hooks before anyone waited (review F4).
            // The iteration succeeds once its wait has not thrown.
            case Next.Await await -> {
                if (stopRequested.get()) {
                    succeeded();
                    yield false;
                }
                yield awaitCustom(await.loopWait());
            }
        };
    }

    /**
     * Runs a body's own wait and commits the iteration's outcome: a wait that
     * returns (or is interrupted) completes a successful iteration; one that
     * throws fails it, so the streak grows across such iterations.
     */
    private boolean awaitCustom(LoopWait wait) {
        try {
            var goOn = wait.await(context);
            succeeded();
            return goOn && !stopRequested.get();
        } catch (InterruptedException e) {
            clearStrayInterrupt();
            succeeded();
            return !stopRequested.get();
        } catch (Throwable t) {
            clearStrayInterrupt();
            if (failed(t) == Logged.STOPPING) {
                return false;
            }
            return context.sleep(spec.backoff().after(status.get().streak()));
        }
    }

    /** Nothing in Maestro interrupts a supervised thread: a stray interrupt is cleared, never read as a stop. */
    private void clearStrayInterrupt() {
        if (Thread.interrupted() && !stopRequested.get()) {
            // On the failure arm's path: a logger that throws must not skip
            // the failure's handling (PR #74 review).
            quietly(() -> logger.debug("Background loop '{}' cleared a stray interrupt of its thread",
                    spec.name()));
        }
    }

    /**
     * Handles one iteration that threw (the loop's thread only).
     *
     * <p>A {@link MaestroControlFlowError} is rethrown unchanged, without
     * logging or counting it. While the loop is stopping, any other failure is
     * logged at {@code DEBUG} and neither counted nor reported. Otherwise the
     * failure extends the streak, is published, logged by the cadence in the
     * class Javadoc, and an {@link Error} is reported to the observer.
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
        if (stopRequested.get()) {
            log(failure, () -> logger.debug("Background loop '{}' failed while stopping (ignored): {}",
                    spec.name(), describe(failure)));
            return Logged.STOPPING;
        }
        var now = nanoTime.getAsLong();
        var s = status.get();
        var n = s.streak() + 1;
        var streakStart = n == 1 ? now : s.streakStartNanos();
        var failingSince = n == 1 ? wallClock.instant() : s.failingSince();
        status.set(new Status(s.state(), n, streakStart, failingSince, s.lastSuccess(),
                failure.getClass().getName(), NOT_IN_ITERATION, s.notes()));
        Logged logged;
        if (n == 1) {
            lastLoudNanos = now;
            logged = Logged.FIRST;
            var retry = spec.backoff().after(1);
            log(failure, () -> logger.error("Background loop '{}' failed ({}); it keeps running and retries every "
                            + "{} — {}",
                    spec.name(), describe(failure), plain(retry), whatFollows(retry), failure));
        } else if (now - lastLoudNanos >= REMINDER_INTERVAL.toNanos()) {
            lastLoudNanos = now;
            logged = Logged.REMINDER;
            var minutes = TimeUnit.NANOSECONDS.toMinutes(now - streakStart);
            log(failure, () -> logger.error(
                    "Background loop '{}' is still failing: {} consecutive failure(s) over {} min; latest: {}",
                    spec.name(), n, minutes, describe(failure)));
        } else {
            logged = Logged.REPEAT;
            log(failure, () -> logger.debug("Background loop '{}' failed again ({} in a row)", spec.name(), n,
                    failure));
        }
        if (failure instanceof Error) {
            // Contained by the composite (Issue 81): a throwing observer cannot
            // end the loop; a control-flow Error from it is still rethrown.
            observer.backgroundLoopError(spec.name(), failure.getClass().getName());
        }
        return logged;
    }

    /**
     * Records an iteration that completed (the loop's thread only); ends a
     * failure streak, if any.
     *
     * @return {@code true} when a streak ended and the recovery line was logged
     */
    boolean succeeded() {
        var s = status.get();
        var n = s.streak();
        status.set(new Status(s.state(), 0, 0, null, wallClock.instant(), null, NOT_IN_ITERATION, s.notes()));
        if (n == 0) {
            return false;
        }
        // Contained: a logger that throws must not turn this success into a
        // counted failure (PR #74 review audit).
        quietly(() -> logger.info("Background loop '{}' recovered after {} consecutive failure(s)", spec.name(),
                n));
        return true;
    }

    /**
     * The handler the loop's thread is built with: it reports a throwable that
     * ended the loop through SLF4J — {@code ERROR} with the stack trace, or
     * {@code DEBUG} while stopping — instead of the JDK's default handler on
     * {@code System.err}. It never rethrows the throwable it is handed.
     *
     * @return the uncaught-exception handler for this loop's thread
     */
    Thread.UncaughtExceptionHandler uncaughtExceptionHandler() {
        return (thread, failure) -> {
            if (stopRequested.get()) {
                logger.debug("Background loop '{}' exited while stopping: {}", spec.name(), describe(failure));
            } else {
                logger.error("Background loop '{}' stopped: {} escaped it — it will not run again on this "
                        + "node until the node restarts", spec.name(), describe(failure), failure);
            }
        };
    }

    /**
     * Runs one logging call for {@code failure}, containing anything it
     * throws — a logger or appender that fails, or a throwable whose
     * {@code toString()}/{@code getMessage()} throws while it is rendered —
     * so the failure arm cannot itself end the loop. Falls back to one line
     * naming the failure's class; if that throws too, nothing is logged.
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
                        spec.name(), failure.getClass().getName(), t.getClass().getName());
            } catch (Throwable ignored) {
                if (ignored instanceof MaestroControlFlowError controlFlow) {
                    throw controlFlow;
                }
                // Nothing left to log with; the failure is still counted.
            }
        }
    }

    /** Runs one logging call, containing anything but a control-flow {@code Error} it throws. */
    private static void quietly(Runnable call) {
        try {
            call.run();
        } catch (Throwable t) {
            if (t instanceof MaestroControlFlowError controlFlow) {
                throw controlFlow;
            }
            // A logger that throws cannot change how the loop ended.
        }
    }

    /**
     * What the first failure's line promises about the ones after it. A loop
     * whose back-off is at least {@link #REMINDER_INTERVAL} never fails twice
     * within it, so each of its further failures is a reminder.
     */
    private static String whatFollows(Duration retry) {
        return retry.compareTo(REMINDER_INTERVAL) < 0
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

    /** {@code failure.toString()}, or its class name when {@code toString()} itself throws. */
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

    /** The body's view of this supervisor. */
    private final class Context implements LoopContext {

        @Override
        public boolean stopRequested() {
            return stopRequested.get();
        }

        @Override
        public boolean sleep(Duration duration) {
            var remaining = duration.toNanos();
            var end = System.nanoTime() + remaining;
            while (true) {
                try {
                    return !stop.await(Math.max(0, remaining), TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    if (stopRequested.get()) {
                        return false;
                    }
                    remaining = end - System.nanoTime();   // a stray interrupt: cleared, the wait goes on
                    if (remaining <= 0) {
                        return true;
                    }
                }
            }
        }

        @Override
        public void note(String key, Object value) {
            status.set(status.get().withNote(Objects.requireNonNull(key, "key"),
                    Objects.requireNonNull(value, "value")));
        }
    }
}
