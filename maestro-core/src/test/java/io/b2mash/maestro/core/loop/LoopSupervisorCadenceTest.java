package io.b2mash.maestro.core.loop;

import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.exception.StoreUnavailableException;
import io.b2mash.maestro.core.logcapture.CapturingSlf4jProvider;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.event.Level;

import java.sql.SQLException;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The failure arm of {@link LoopSupervisor} (WP4 D-3, D-6, moved from the
 * background-loop guard's test with its assertions unchanged): which
 * throwables are rethrown, which are contained, what is reported to the
 * observer, and the log cadence (driven through an injected clock, read back
 * through the {@link LoopSupervisor.Logged} each call returns). The
 * supervisor is built unstarted, and its failure arm called on the test
 * thread as the loop thread would.
 */
class LoopSupervisorCadenceTest {

    private final AtomicLong nanos = new AtomicLong();
    private final RecordingEngineObserver observer = new RecordingEngineObserver();
    private LoopSupervisor guard;

    @BeforeEach
    void setUp() {
        guard = unstarted("timer-poller", Duration.ofSeconds(5), observer);
    }

    /** A supervisor that is never started, with a fixed back-off of {@code interval}. */
    private LoopSupervisor unstarted(String loop, Duration interval, EngineObserver observer) {
        return LoopSupervisor.unstarted(LoopSpec.of(loop, "t-" + loop, LoopRole.CRITICAL, interval),
                ctx -> Next.afterPeriod(), observer, nanos::get, InstantSource.system());
    }

    /** The guard's {@code stopping} flag: the supervisor's stop request. */
    private void stopping() {
        guard.requestStop();
    }

    private int streak(LoopSupervisor supervisor) {
        return supervisor.snapshot().consecutiveFailures();
    }

    static Stream<Error> survivableErrors() {
        return Stream.of(new OutOfMemoryError(), new StackOverflowError(),
                new NoClassDefFoundError("x"), new AssertionError("x"));
    }

    @Test
    void aControlFlowErrorIsRethrownUnchangedAndNotCounted() {
        var shutdown = new ExecutorShutdownException("test");
        assertSame(shutdown, assertThrows(ExecutorShutdownException.class, () -> guard.failed(shutdown)));
        assertEquals(0, streak(guard));
        assertEquals(List.of(), observer.loopErrors());
    }

    @ParameterizedTest
    @MethodSource("survivableErrors")
    void everyOtherErrorIsContainedAndReported(Error error) {
        assertEquals(LoopSupervisor.Logged.FIRST, guard.failed(error));
        assertEquals(1, streak(guard));
        assertEquals(List.of(new RecordingEngineObserver.LoopErrorCall("timer-poller", error.getClass().getName())),
                observer.loopErrors());
    }

    @Test
    void anExceptionIsContainedButNotReportedToTheObserver() {
        assertEquals(LoopSupervisor.Logged.FIRST,
                guard.failed(new StoreUnavailableException("down", new SQLException("08006"))));
        assertEquals(List.of(), observer.loopErrors());
    }

    @Test
    void repeatsWithinFiveMinutesAreQuietThenAReminderIsLoud() {
        guard.failed(new IllegalStateException("1"));
        nanos.addAndGet(Duration.ofMinutes(5).minusSeconds(1).toNanos());
        assertEquals(LoopSupervisor.Logged.REPEAT, guard.failed(new IllegalStateException("2")));
        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        assertEquals(LoopSupervisor.Logged.REMINDER, guard.failed(new IllegalStateException("3")));
        assertEquals(LoopSupervisor.Logged.REPEAT, guard.failed(new IllegalStateException("4")));
        assertEquals(4, streak(guard));
    }

    @Test
    void aSuccessAfterAStreakLogsRecoveryOnceAndResets() {
        assertFalse(guard.succeeded(), "no streak, nothing to report");
        guard.failed(new IllegalStateException("1"));
        assertTrue(guard.succeeded());
        assertFalse(guard.succeeded());
        assertEquals(0, streak(guard));
        assertEquals(LoopSupervisor.Logged.FIRST, guard.failed(new IllegalStateException("2")));
    }

    /**
     * Review c7: the log cadence at the levels D-6 names, read back as SLF4J
     * delivers it to a binding — after SLF4J's own normalisation, which is what
     * decides whether a trailing argument becomes the attached throwable (the
     * stack trace). FIRST is ERROR with it, REPEAT DEBUG with it, REMINDER
     * ERROR without it, the recovery INFO, and a failure while stopping DEBUG
     * without it.
     */
    @Test
    void theCadenceLogsAtD6sLevelsWithTheStackTraceOnlyWhereItSays() {
        try (var logs = CapturingSlf4jProvider.watch(LoopSupervisor.class)) {
            var first = new IllegalStateException("first");
            var repeat = new IllegalStateException("repeat");
            var reminder = new IllegalStateException("reminder");
            var whileStopping = new IllegalStateException("while stopping");
            assertEquals(LoopSupervisor.Logged.FIRST, guard.failed(first));
            assertEquals(LoopSupervisor.Logged.REPEAT, guard.failed(repeat));
            nanos.addAndGet(LoopSupervisor.REMINDER_INTERVAL.toNanos());
            assertEquals(LoopSupervisor.Logged.REMINDER, guard.failed(reminder));
            assertTrue(guard.succeeded());
            stopping();
            assertEquals(LoopSupervisor.Logged.STOPPING, guard.failed(whileStopping));

            var records = logs.records();
            assertEquals(5, records.size(), "one line per call: " + records);
            assertAll(
                    () -> assertEquals(Level.ERROR, records.get(0).level(), "FIRST"),
                    () -> assertSame(first, records.get(0).throwable(), "FIRST carries the stack trace"),
                    () -> assertTrue(records.get(0).message().startsWith("Background loop 'timer-poller' failed "
                            + "(java.lang.IllegalStateException: first); it keeps running and retries every 5 s"),
                            records.get(0).message()),
                    () -> assertEquals(Level.DEBUG, records.get(1).level(), "REPEAT"),
                    () -> assertSame(repeat, records.get(1).throwable(), "REPEAT carries the stack trace"),
                    () -> assertEquals("Background loop 'timer-poller' failed again (2 in a row)",
                            records.get(1).message()),
                    () -> assertEquals(Level.ERROR, records.get(2).level(), "REMINDER"),
                    () -> assertNull(records.get(2).throwable(), "REMINDER has no stack trace"),
                    () -> assertEquals("Background loop 'timer-poller' is still failing: 3 consecutive failure(s) "
                            + "over 5 min; latest: java.lang.IllegalStateException: reminder", records.get(2).message()),
                    () -> assertEquals(Level.INFO, records.get(3).level(), "recovery"),
                    () -> assertNull(records.get(3).throwable()),
                    () -> assertEquals("Background loop 'timer-poller' recovered after 3 consecutive failure(s)",
                            records.get(3).message()),
                    () -> assertEquals(Level.DEBUG, records.get(4).level(), "STOPPING"),
                    () -> assertNull(records.get(4).throwable(), "STOPPING has no stack trace"));
        }
    }

    @Test
    void whileStoppingAFailureIsQuietAndUncounted() {
        stopping();
        assertEquals(LoopSupervisor.Logged.STOPPING, guard.failed(new AssertionError("interrupted store call")));
        assertEquals(0, streak(guard));
        assertEquals(List.of(), observer.loopErrors());
    }

    @Test
    void aControlFlowErrorIsRethrownEvenWhileStopping() {
        stopping();
        assertThrows(ExecutorShutdownException.class, () -> guard.failed(new ExecutorShutdownException("test")));
    }

    @Test
    void aThrowingObserverIsContained() {
        var throwing = new EngineObserver() {
            @Override
            public void backgroundLoopError(String loop, String errorType) {
                throw new IllegalStateException("observer bug");
            }
        };
        var g = unstarted("timer-poller", Duration.ofSeconds(5), throwing);
        assertEquals(LoopSupervisor.Logged.FIRST, g.failed(new AssertionError("x")));
    }

    @Test
    void anObserverThatThrowsAnErrorIsContainedToo() {
        var throwing = new EngineObserver() {
            @Override
            public void backgroundLoopError(String loop, String errorType) {
                throw new AssertionError("observer bug");
            }
        };
        var g = unstarted("timer-poller", Duration.ofSeconds(5), throwing);
        assertEquals(LoopSupervisor.Logged.FIRST, g.failed(new AssertionError("x")));
        assertEquals(1, streak(g));
    }

    /** An Error whose toString() and getMessage() throw, as a buggy or hostile throwable might. */
    static final class UnprintableError extends Error {
        @Override
        public String getMessage() {
            throw new IllegalStateException("getMessage() bug");
        }

        @Override
        public String toString() {
            throw new IllegalStateException("toString() bug");
        }
    }

    @Test
    void aFailureThatCannotBePrintedIsStillContainedCountedAndReported() {
        var unprintable = new UnprintableError();
        try (var logs = CapturingSlf4jProvider.watch(LoopSupervisor.class)) {
            assertEquals(LoopSupervisor.Logged.FIRST, guard.failed(unprintable));
            assertEquals(LoopSupervisor.Logged.REPEAT, guard.failed(unprintable));
            assertTrue(logs.records().stream().anyMatch(r -> r.message().contains(UnprintableError.class.getName())),
                    "the failure is still logged, by class name");
        }
        assertEquals(2, streak(guard));
        assertEquals(List.of(
                        new RecordingEngineObserver.LoopErrorCall("timer-poller", UnprintableError.class.getName()),
                        new RecordingEngineObserver.LoopErrorCall("timer-poller", UnprintableError.class.getName())),
                observer.loopErrors());
    }

    @Test
    void aFailureThatCannotBePrintedWhileStoppingIsContained() {
        stopping();
        assertEquals(LoopSupervisor.Logged.STOPPING, guard.failed(new UnprintableError()));
    }

    @Test
    void theUncaughtHandlerStillLogsAFailureThatCannotBePrinted() {
        try (var logs = CapturingSlf4jProvider.watch(LoopSupervisor.class)) {
            guard.uncaughtExceptionHandler().uncaughtException(Thread.currentThread(), new UnprintableError());
            assertTrue(logs.records().stream().anyMatch(r -> r.message().contains(UnprintableError.class.getName())),
                    "the dead loop is still reported, by class name");
        }
    }

    static Stream<Arguments> intervalsInPlainUnits() {
        return Stream.of(
                Arguments.of(Duration.ofMillis(50), "every 50 ms "),
                Arguments.of(Duration.ofMillis(1500), "every 1500 ms "),
                Arguments.of(Duration.ofSeconds(5), "every 5 s "),
                Arguments.of(Duration.ofSeconds(90), "every 90 s "),
                Arguments.of(Duration.ofMinutes(10), "every 10 min "),
                Arguments.of(Duration.ofHours(1), "every 1 h "));
    }

    @ParameterizedTest
    @MethodSource("intervalsInPlainUnits")
    void theFirstFailureNamesTheRetryIntervalInPlainUnits(Duration interval, String expected) {
        var g = unstarted("timer-poller", interval, observer);
        try (var logs = CapturingSlf4jProvider.watch(LoopSupervisor.class)) {
            g.failed(new IllegalStateException("down"));
            var first = logs.records().getFirst().message();
            assertTrue(first.contains(expected), "expected '" + expected + "' in: " + first);
        }
    }

    @Test
    void aLoopFasterThanTheReminderIntervalSaysRepeatsAreQuiet() {
        try (var logs = CapturingSlf4jProvider.watch(LoopSupervisor.class)) {
            guard.failed(new IllegalStateException("down"));                       // 5 s interval
            assertEquals("Background loop 'timer-poller' failed (java.lang.IllegalStateException: down); it keeps "
                            + "running and retries every 5 s — further failures are logged at DEBUG, with an ERROR "
                            + "reminder at most every 5 minutes while they continue",
                    logs.records().getFirst().message());
        }
    }

    @Test
    void aLoopNoFasterThanTheReminderIntervalSaysEveryFailureIsLoggedAtError() {
        var hourly = unstarted("signal-janitor", Duration.ofHours(1), observer);
        try (var logs = CapturingSlf4jProvider.watch(LoopSupervisor.class)) {
            hourly.failed(new IllegalStateException("down"));
            assertEquals("Background loop 'signal-janitor' failed (java.lang.IllegalStateException: down); it keeps "
                            + "running and retries every 1 h — each further failure is logged at ERROR, without the "
                            + "stack trace, until it recovers",
                    logs.records().getFirst().message());
        }
        nanos.addAndGet(Duration.ofHours(1).toNanos());
        assertEquals(LoopSupervisor.Logged.REMINDER, hourly.failed(new IllegalStateException("down")),
                "what the message promised");
    }

    @Test
    void theUncaughtHandlerNeverThrows() {
        assertDoesNotThrow(() -> guard.uncaughtExceptionHandler()
                .uncaughtException(Thread.currentThread(), new ExecutorShutdownException("test")));
    }
}
