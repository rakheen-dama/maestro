package io.b2mash.maestro.core.loop;

import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LoopSupervisor} on a real thread: what ends a loop and what does not,
 * the streak it publishes, and its stop — a latch, never an interrupt.
 *
 * <p>Most tests drive the loop one iteration at a time through a
 * {@link ScriptedBody}: the body takes its next step from a queue the test
 * fills, so the test knows exactly which iteration has run; the loop's thread
 * publishes an iteration's outcome before it asks for the next step.
 */
class LoopSupervisorTest {

    private static final Duration BOUND = Duration.ofSeconds(5);

    private final RecordingEngineObserver observer = new RecordingEngineObserver();
    private final List<LoopSupervisor> started = new ArrayList<>();

    @AfterEach
    void tearDown() {
        started.forEach(LoopSupervisor::requestStop);
        started.forEach(s -> s.awaitStopped(BOUND));
    }

    /** A loop that runs at once, waits 1 ms after a success and nothing after a failure. */
    private static LoopSpec fast(String name) {
        return LoopSpec.of(name, "test-" + name, LoopRole.CRITICAL, Duration.ofMillis(1))
                .withBackoff(Backoff.fixed(Duration.ZERO));
    }

    private LoopSupervisor start(LoopSpec spec, LoopBody body) {
        var supervisor = LoopSupervisor.start(spec, body, observer);
        started.add(supervisor);
        return supervisor;
    }

    @Test
    void aControlFlowErrorIsRethrownAndTheLoopEndsDead() {
        var body = new ScriptedBody();
        var loop = start(fast("cf"), body);
        body.step(() -> {
            throw new ExecutorShutdownException("test");
        });
        assertTrue(loop.awaitStopped(BOUND), "the loop ended");
        var snapshot = loop.snapshot();
        assertAll(
                () -> assertEquals(LoopSnapshot.State.DEAD, snapshot.state()),
                () -> assertEquals(0, snapshot.consecutiveFailures(), "not counted"),
                () -> assertEquals(List.of(), observer.loopErrors(), "not reported"),
                () -> assertEquals(1, body.iterations.get(), "no further iteration"));
    }

    @Test
    void anErrorAndAnExceptionAreContainedAndTheLoopGoesOn() {
        var body = new ScriptedBody();
        var loop = start(fast("contain"), body);
        body.step(() -> {
            throw new AssertionError("an Error");
        });
        body.step(() -> {
            throw new IllegalStateException("an Exception");
        });
        body.step(Next::afterPeriod);
        body.awaitIterations(4);                         // the fourth has begun: the third was published

        var snapshot = loop.snapshot();
        assertAll(
                () -> assertEquals(LoopSnapshot.State.RUNNING, snapshot.state()),
                () -> assertEquals(0, snapshot.consecutiveFailures(), "the success ended the streak"),
                () -> assertNotNull(snapshot.lastSuccess()),
                () -> assertEquals(List.of(new RecordingEngineObserver.LoopErrorCall("contain",
                        AssertionError.class.getName())), observer.loopErrors(), "only the Error is reported"));
    }

    @Test
    void aStreakPublishesFailingSinceAndItsLatestFailureAndASuccessEndsIt() {
        var wall = new AtomicReference<>(Instant.parse("2026-11-18T08:00:00Z"));
        var nanos = new AtomicLong(1_000_000_000L);
        var body = new ScriptedBody();
        var loop = LoopSupervisor.unstarted(fast("streak").withFailing(FailingRule.streakFor(2, Duration.ofSeconds(60))),
                body, observer, nanos::get, wall::get);
        loop.start();
        started.add(loop);

        body.step(() -> {
            throw new IllegalStateException("first");
        });
        body.awaitIterations(2);
        wall.set(Instant.parse("2026-11-18T08:00:30Z"));
        nanos.addAndGet(Duration.ofSeconds(30).toNanos());
        body.step(() -> {
            throw new UnsupportedOperationException("second");
        });
        body.awaitIterations(3);

        var failing30s = loop.snapshot();
        nanos.addAndGet(Duration.ofSeconds(30).toNanos());
        var failing60s = loop.snapshot();
        assertAll(
                () -> assertEquals(2, failing30s.consecutiveFailures()),
                () -> assertEquals(Instant.parse("2026-11-18T08:00:00Z"), failing30s.failingSince(),
                        "the streak's first failure"),
                () -> assertEquals(UnsupportedOperationException.class.getName(), failing30s.lastFailureType()),
                () -> assertNull(failing30s.lastSuccess()),
                () -> assertFalse(failing30s.failing(), "two failures, but only 30 s old"),
                () -> assertTrue(failing60s.failing(), "two failures spanning 60 s"));

        wall.set(Instant.parse("2026-11-18T08:01:00Z"));
        body.step(Next::afterPeriod);
        body.awaitIterations(4);
        var healed = loop.snapshot();
        assertAll(
                () -> assertEquals(0, healed.consecutiveFailures()),
                () -> assertNull(healed.failingSince()),
                () -> assertNull(healed.lastFailureType()),
                () -> assertFalse(healed.failing()),
                () -> assertEquals(Instant.parse("2026-11-18T08:01:00Z"), healed.lastSuccess()));
    }

    @Test
    void anInterruptedExceptionIsAFailureAndTheFlagIsCleared() {
        var body = new ScriptedBody();
        var loop = start(fast("interrupted"), body);
        body.step(() -> {
            Thread.currentThread().interrupt();
            throw new InterruptedException("stray");
        });
        body.awaitIterations(2);
        assertAll(
                () -> assertEquals(1, loop.snapshot().consecutiveFailures()),
                () -> assertFalse(body.interruptedOnEntry.get(), "the next iteration starts with the flag clear"),
                () -> assertEquals(LoopSnapshot.State.RUNNING, loop.snapshot().state()));
    }

    @Test
    void aStopEndsTheWaitAtOnceWithoutAnInterrupt() {
        var interruptedAtExit = new AtomicReference<@Nullable Boolean>();
        var ran = new CountDownLatch(1);
        var loop = start(LoopSpec.of("waiting", "test-waiting", LoopRole.HOUSEKEEPING, Duration.ofHours(1)),
                new LoopBody() {
                    @Override
                    public Next iterate(LoopContext ctx) {
                        ran.countDown();
                        return Next.afterPeriod();
                    }

                    @Override
                    public void onExit() {
                        interruptedAtExit.set(Thread.currentThread().isInterrupted());
                    }
                });
        awaitLatch(ran);
        awaitUntil(() -> loop.snapshot().lastSuccess() != null);    // now in its one-hour wait

        var t0 = System.nanoTime();
        loop.requestStop();
        assertTrue(loop.awaitStopped(BOUND));
        var tookMs = (System.nanoTime() - t0) / 1_000_000;
        assertAll(
                () -> assertTrue(tookMs < 1_000, "the wait ended at once, took " + tookMs + " ms"),
                () -> assertEquals(LoopSnapshot.State.STOPPED, loop.snapshot().state()),
                () -> assertEquals(Boolean.FALSE, interruptedAtExit.get(), "never interrupted"));
    }

    @Test
    void anIterationInProgressFinishesAndTheJoinHoldsItsBound() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var loop = start(fast("busy"), ctx -> {
            entered.countDown();
            while (true) {
                try {
                    release.await();
                    return Next.afterPeriod();
                } catch (InterruptedException e) {
                    interrupted.set(true);            // a driver that swallows it and keeps waiting
                }
            }
        });
        awaitLatch(entered);

        loop.requestStop();
        assertEquals(LoopSnapshot.State.STOPPING, loop.snapshot().state());
        assertNotNull(loop.snapshot().inIterationFor(), "the iteration is in progress");
        var t0 = System.nanoTime();
        var stoppedWhileBusy = loop.awaitStopped(Duration.ofMillis(100));
        var waitedMs = (System.nanoTime() - t0) / 1_000_000;
        release.countDown();
        var stoppedOnceReturned = loop.awaitStopped(BOUND);
        assertAll(
                () -> assertFalse(stoppedWhileBusy, "the body has not returned yet"),
                () -> assertTrue(waitedMs < 1_000, "the join held its 100 ms bound, took " + waitedMs + " ms"),
                () -> assertTrue(stoppedOnceReturned),
                () -> assertFalse(interrupted.get(), "the body was never interrupted"),
                () -> assertEquals(LoopSnapshot.State.STOPPED, loop.snapshot().state()));
    }

    @Test
    void doneEndsDoneNotDead() {
        var iterations = new AtomicInteger();
        var loop = start(fast("one-shot"), ctx -> {
            iterations.incrementAndGet();
            return Next.done();
        });
        assertTrue(loop.awaitStopped(BOUND));
        assertAll(
                () -> assertEquals(LoopSnapshot.State.DONE, loop.snapshot().state()),
                () -> assertFalse(loop.snapshot().running()),
                () -> assertEquals(1, iterations.get()));
    }

    @Test
    void aStopBeforeTheInitialDelayEndsStoppedWithoutAnIteration() {
        var iterations = new AtomicInteger();
        var loop = start(fast("delayed").withInitialDelay(Duration.ofHours(1)), ctx -> {
            iterations.incrementAndGet();
            return Next.afterPeriod();
        });
        loop.requestStop();
        assertTrue(loop.awaitStopped(BOUND));
        assertEquals(LoopSnapshot.State.STOPPED, loop.snapshot().state());
        assertEquals(0, iterations.get());
    }

    /** {@link Next#await} runs a custom wait, and the spec's stop hook is how a stop reaches it. */
    @Test
    void aCustomWaitIsEndedByTheStopHook() {
        var wake = new CountDownLatch(1);
        var waiting = new CountDownLatch(1);
        var loop = start(fast("custom").withOnStop(wake::countDown), ctx -> Next.await(c -> {
            waiting.countDown();
            wake.await();
            return !c.stopRequested();
        }));
        awaitLatch(waiting);
        loop.requestStop();
        assertTrue(loop.awaitStopped(BOUND));
        assertEquals(LoopSnapshot.State.STOPPED, loop.snapshot().state());
    }

    /**
     * Review F3: the loop's end is reported to {@link LoopSupervisor#awaitStopped}
     * even when logging its end throws (a failing appender), so shutdown does
     * not wait out its deadline for a loop that has already ended.
     */
    @Test
    void aLoggerThatThrowsAsTheLoopEndsStillReleasesAwaitStopped() {
        try (var logs = io.b2mash.maestro.core.logcapture.CapturingSlf4jProvider.watch(LoopSupervisor.class)
                .failWhen(m -> m.startsWith("Background loop 'end-log' stopped"))) {
            var ran = new CountDownLatch(1);
            var loop = start(LoopSpec.of("end-log", "test-end-log", LoopRole.HOUSEKEEPING, Duration.ofHours(1)),
                    ctx -> {
                        ran.countDown();
                        return Next.afterPeriod();
                    });
            awaitLatch(ran);
            loop.requestStop();
            assertTrue(loop.awaitStopped(Duration.ofSeconds(2)), "the end was reported despite the logger");
            assertEquals(LoopSnapshot.State.STOPPED, loop.snapshot().state());
            assertTrue(logs.records().stream().anyMatch(r -> r.message().startsWith("Background loop 'end-log' stopped")),
                    "the end line was attempted: " + logs.records());
        }
    }

    /**
     * Review F4: a stop that lands while an iteration runs reaches a wait the
     * body returns afterwards, even when the wait's wake is not sticky — the
     * stop hook ran before the wait was armed, so only the supervisor's own
     * check can end it.
     */
    @Test
    void aStopDuringTheIterationEndsAWaitTheBodyReturnsAfterIt() {
        var armed = new AtomicReference<@Nullable CountDownLatch>();
        var timesArmed = new AtomicInteger();
        var inIteration = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var loop = start(fast("pulse").withOnStop(() -> {
            var current = armed.get();
            if (current != null) {
                current.countDown();          // a pulse: lost if nobody waits yet
            }
        }), ctx -> {
            inIteration.countDown();
            release.await();
            return Next.await(c -> {
                timesArmed.incrementAndGet();
                var wake = new CountDownLatch(1);
                armed.set(wake);
                wake.await(30, TimeUnit.SECONDS);
                return true;
            });
        });
        awaitLatch(inIteration);
        loop.requestStop();                   // the hook finds no armed wait
        release.countDown();
        assertTrue(loop.awaitStopped(Duration.ofSeconds(2)), "the stop reached the wait returned after it");
        assertEquals(0, timesArmed.get(), "the wait was never entered");
        assertEquals(LoopSnapshot.State.STOPPED, loop.snapshot().state());
    }

    /** An exception whose {@code toString()} throws, as a buggy or hostile one might. */
    static final class UnprintableException extends RuntimeException {
        @Override
        public String toString() {
            throw new IllegalStateException("toString() bug");
        }

        @Override
        public String getMessage() {
            throw new IllegalStateException("getMessage() bug");
        }
    }

    /**
     * PR #74 review 4624922235: a stop hook that throws something unprintable
     * cannot keep the hooks after it from running — the stop flag is already
     * set, so a skipped hook would never run and a body in a custom wait would
     * stay blocked.
     */
    @Test
    void aStopHookThatThrowsSomethingUnprintableDoesNotSkipTheHooksAfterIt() {
        var second = new CountDownLatch(1);
        var loop = LoopSupervisor.unstarted(fast("hooks").withOnStop(() -> {
            throw new UnprintableException();
        }, second::countDown), ctx -> Next.afterPeriod(), observer, System::nanoTime, InstantSource.system());

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(loop::requestStop);
        assertEquals(0, second.getCount(), "the second hook ran");
    }

    /**
     * PR #74 review 4624922252: a logger that throws while the failure arm
     * clears a stray interrupt cannot skip the failure's handling — the loop
     * counts the failure and retries instead of ending DEAD.
     */
    @Test
    void aThrowingLoggerWhileClearingAStrayInterruptDoesNotKillTheLoop() {
        try (var logs = io.b2mash.maestro.core.logcapture.CapturingSlf4jProvider.watch(LoopSupervisor.class)
                .failWhen(m -> m.contains("cleared a stray interrupt"))) {
            var body = new ScriptedBody();
            var loop = start(fast("stray-log"), body);
            body.step(() -> {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("fails with the flag set");
            });
            body.awaitIterations(2);
            assertAll(
                    () -> assertEquals(LoopSnapshot.State.RUNNING, loop.snapshot().state()),
                    () -> assertEquals(1, loop.snapshot().consecutiveFailures(), "the failure was counted"));
        }
    }

    /**
     * Audit (PR #74 review 4624922252, every log call on a state path): a
     * logger that throws on the recovery line cannot turn a success into a
     * counted failure.
     */
    @Test
    void aThrowingLoggerOnTheRecoveryLineDoesNotTurnASuccessIntoAFailure() {
        try (var logs = io.b2mash.maestro.core.logcapture.CapturingSlf4jProvider.watch(LoopSupervisor.class)
                .failWhen(m -> m.contains("recovered after"))) {
            var body = new ScriptedBody();
            var loop = start(fast("recovery-log"), body);
            body.step(() -> {
                throw new IllegalStateException("one failure");
            });
            body.step(Next::afterPeriod);
            body.awaitIterations(3);
            assertAll(
                    () -> assertEquals(0, loop.snapshot().consecutiveFailures(), "the success ended the streak"),
                    () -> assertNull(loop.snapshot().lastFailureType()));
        }
    }

    /**
     * PR #74 review 4624922241: an iteration whose custom wait then throws is
     * not a success — the streak grows across such iterations, so a loop
     * whose wait always fails reaches {@code failing}.
     */
    @Test
    void aCustomWaitThatAlwaysThrowsBuildsAStreak() {
        var waits = new AtomicInteger();
        var loop = start(fast("failing-wait"), ctx -> Next.await(c -> {
            waits.incrementAndGet();
            throw new IllegalStateException("the wait fails");
        }));
        awaitUntil(() -> waits.get() >= 3);
        awaitUntil(() -> loop.snapshot().consecutiveFailures() >= 2);
        assertTrue(loop.snapshot().failing(), "two failures in a row: failing under streak(2)");
    }

    /**
     * P2 task 5: every counted failure — an {@code Exception} as well as an
     * {@code Error} — is reported to {@code backgroundLoopFailed}, which feeds
     * {@code maestro.loop.failures}; a failure while stopping is not.
     */
    @Test
    void everyCountedFailureIsReportedToBackgroundLoopFailed() {
        var body = new ScriptedBody();
        start(fast("reported"), body);
        body.step(() -> {
            throw new IllegalStateException("an Exception");
        });
        body.step(() -> {
            throw new AssertionError("an Error");
        });
        body.awaitIterations(3);
        assertEquals(List.of(
                        new RecordingEngineObserver.LoopErrorCall("reported", IllegalStateException.class.getName()),
                        new RecordingEngineObserver.LoopErrorCall("reported", AssertionError.class.getName())),
                observer.loopFailures());
    }

    @Test
    void notesAreShownInTheSnapshot() {
        var noted = new CountDownLatch(1);
        var loop = start(fast("noting"), ctx -> {
            ctx.note("leader", true);
            noted.countDown();
            return Next.after(Duration.ofHours(1));
        });
        awaitLatch(noted);
        awaitUntil(() -> loop.snapshot().lastSuccess() != null);
        assertEquals(true, loop.snapshot().notes().get("leader"));
    }

    /**
     * A snapshot is one immutable record published by the loop thread: read
     * from other threads while the loop alternates failure and success, the
     * streak, its start and its latest failure always agree.
     */
    @Test
    void aSnapshotReadFromAnotherThreadIsNeverTorn() throws Exception {
        var flip = new AtomicInteger();
        var loop = start(fast("flapping"), ctx -> {
            if (flip.incrementAndGet() % 2 == 1) {
                throw new IllegalStateException("odd");
            }
            return Next.immediately();
        });
        var torn = new CopyOnWriteArrayList<LoopSnapshot>();
        var readers = new ArrayList<Thread>();
        var readersStarted = new CountDownLatch(4);
        var observations = new AtomicInteger[4];
        var done = new AtomicBoolean();
        // Readiness by synchronisation, not elapsed time (PR #74 review): the
        // readers run until the loop has flapped enough AND each has read
        // enough snapshots while it did, within one overall bound.
        for (int r = 0; r < 4; r++) {
            var seen = observations[r] = new AtomicInteger();
            readers.add(Thread.ofPlatform().start(() -> {
                readersStarted.countDown();
                while (!done.get()) {
                    var s = loop.snapshot();
                    seen.incrementAndGet();
                    var streak = s.consecutiveFailures() > 0;
                    if (streak != (s.failingSince() != null) || streak != (s.lastFailureType() != null)
                            || s.consecutiveFailures() > 1) {
                        torn.add(s);
                    }
                }
            }));
        }
        try {
            awaitLatch(readersStarted);
            var flipsAtStart = flip.get();
            awaitUntil(() -> flip.get() - flipsAtStart >= 100
                    && java.util.Arrays.stream(observations).allMatch(o -> o.get() >= 1_000));
        } finally {
            done.set(true);
            for (var reader : readers) {
                reader.join(BOUND.toMillis());
            }
        }
        assertEquals(List.of(), torn);
    }

    @Test
    void aSpecRejectsAnInvalidPeriodOrName() {
        assertThrows(IllegalArgumentException.class,
                () -> LoopSpec.of("x", "t", LoopRole.CRITICAL, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> LoopSpec.of(" ", "t", LoopRole.CRITICAL, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> Next.after(Duration.ofSeconds(-1)));
    }

    @Test
    void anExponentialBackoffDoublesUpToItsCap() {
        var backoff = Backoff.exponential(Duration.ofSeconds(1), Duration.ofSeconds(30));
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4),
                        Duration.ofSeconds(16), Duration.ofSeconds(30), Duration.ofSeconds(30)),
                List.of(backoff.after(1), backoff.after(2), backoff.after(3), backoff.after(5), backoff.after(6),
                        backoff.after(1_000)));
        assertEquals(Duration.ofSeconds(5), Backoff.fixed(Duration.ofSeconds(5)).after(7));
    }

    // ── helpers ──────────────────────────────────────────────────────

    /** One iteration's behaviour. */
    @FunctionalInterface
    interface Step {
        Next run() throws Exception;
    }

    /**
     * Takes each iteration's step from a queue the test fills, counting
     * iterations as they begin. With no step within the bound the iteration
     * just succeeds, and a stop ends the wait for a step, so a test never hangs
     * its teardown.
     */
    static final class ScriptedBody implements LoopBody {
        final BlockingQueue<Step> steps = new LinkedBlockingQueue<>();
        final AtomicInteger iterations = new AtomicInteger();
        final AtomicBoolean interruptedOnEntry = new AtomicBoolean();

        void step(Step step) {
            steps.add(step);
        }

        void awaitIterations(int n) {
            awaitUntil(() -> iterations.get() >= n);
        }

        @Override
        public Next iterate(LoopContext ctx) throws Exception {
            interruptedOnEntry.set(Thread.currentThread().isInterrupted());
            iterations.incrementAndGet();
            var deadline = System.nanoTime() + BOUND.toNanos();
            while (!ctx.stopRequested() && System.nanoTime() < deadline) {
                var step = steps.poll(10, TimeUnit.MILLISECONDS);
                if (step != null) {
                    return step.run();
                }
            }
            return Next.afterPeriod();
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(BOUND.toMillis(), TimeUnit.MILLISECONDS), "timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition) {
        var deadline = System.nanoTime() + BOUND.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + BOUND);
            }
            Thread.onSpinWait();
            try {
                Thread.sleep(1); // a poll interval, not a wait for a condition
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
