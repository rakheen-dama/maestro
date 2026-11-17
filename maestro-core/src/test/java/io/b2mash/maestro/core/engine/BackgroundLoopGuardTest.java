package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.exception.StoreUnavailableException;
import io.b2mash.maestro.core.logcapture.CapturingSlf4jProvider;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BackgroundLoopGuard} (WP4 D-3, D-6): which throwables
 * are rethrown, which are contained, what is reported to the observer, and the
 * log cadence (driven through an injected clock, read back through the
 * {@link BackgroundLoopGuard.Logged} each call returns).
 */
class BackgroundLoopGuardTest {

    private final AtomicLong nanos = new AtomicLong();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final RecordingEngineObserver observer = new RecordingEngineObserver();
    private BackgroundLoopGuard guard;

    @BeforeEach
    void setUp() {
        guard = new BackgroundLoopGuard(BackgroundLoopGuard.TIMER_POLLER, Duration.ofSeconds(5),
                stopping::get, observer, nanos::get);
    }

    static Stream<Error> survivableErrors() {
        return Stream.of(new OutOfMemoryError(), new StackOverflowError(),
                new NoClassDefFoundError("x"), new AssertionError("x"));
    }

    @Test
    void aControlFlowErrorIsRethrownUnchangedAndNotCounted() {
        var shutdown = new ExecutorShutdownException("test");
        assertSame(shutdown, assertThrows(ExecutorShutdownException.class, () -> guard.failed(shutdown)));
        assertEquals(0, guard.consecutiveFailures());
        assertEquals(List.of(), observer.loopErrors());
    }

    @ParameterizedTest
    @MethodSource("survivableErrors")
    void everyOtherErrorIsContainedAndReported(Error error) {
        assertEquals(BackgroundLoopGuard.Logged.FIRST, guard.failed(error));
        assertEquals(1, guard.consecutiveFailures());
        assertEquals(List.of(new RecordingEngineObserver.LoopErrorCall("timer-poller", error.getClass().getName())),
                observer.loopErrors());
    }

    @Test
    void anExceptionIsContainedButNotReportedToTheObserver() {
        assertEquals(BackgroundLoopGuard.Logged.FIRST,
                guard.failed(new StoreUnavailableException("down", new SQLException("08006"))));
        assertEquals(List.of(), observer.loopErrors());
    }

    @Test
    void repeatsWithinFiveMinutesAreQuietThenAReminderIsLoud() {
        guard.failed(new IllegalStateException("1"));
        nanos.addAndGet(Duration.ofMinutes(5).minusSeconds(1).toNanos());
        assertEquals(BackgroundLoopGuard.Logged.REPEAT, guard.failed(new IllegalStateException("2")));
        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        assertEquals(BackgroundLoopGuard.Logged.REMINDER, guard.failed(new IllegalStateException("3")));
        assertEquals(BackgroundLoopGuard.Logged.REPEAT, guard.failed(new IllegalStateException("4")));
        assertEquals(4, guard.consecutiveFailures());
    }

    @Test
    void aSuccessAfterAStreakLogsRecoveryOnceAndResets() {
        assertFalse(guard.succeeded(), "no streak, nothing to report");
        guard.failed(new IllegalStateException("1"));
        assertTrue(guard.succeeded());
        assertFalse(guard.succeeded());
        assertEquals(0, guard.consecutiveFailures());
        assertEquals(BackgroundLoopGuard.Logged.FIRST, guard.failed(new IllegalStateException("2")));
    }

    @Test
    void whileStoppingAFailureIsQuietAndUncounted() {
        stopping.set(true);
        assertEquals(BackgroundLoopGuard.Logged.STOPPING, guard.failed(new AssertionError("interrupted store call")));
        assertEquals(0, guard.consecutiveFailures());
        assertEquals(List.of(), observer.loopErrors());
    }

    @Test
    void aControlFlowErrorIsRethrownEvenWhileStopping() {
        stopping.set(true);
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
        var g = new BackgroundLoopGuard(BackgroundLoopGuard.TIMER_POLLER, Duration.ofSeconds(5), () -> false,
                throwing, nanos::get);
        assertEquals(BackgroundLoopGuard.Logged.FIRST, g.failed(new AssertionError("x")));
    }

    @Test
    void anObserverThatThrowsAnErrorIsContainedToo() {
        var throwing = new EngineObserver() {
            @Override
            public void backgroundLoopError(String loop, String errorType) {
                throw new AssertionError("observer bug");
            }
        };
        var g = new BackgroundLoopGuard(BackgroundLoopGuard.TIMER_POLLER, Duration.ofSeconds(5), () -> false,
                throwing, nanos::get);
        assertEquals(BackgroundLoopGuard.Logged.FIRST, g.failed(new AssertionError("x")));
        assertEquals(1, g.consecutiveFailures());
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
        try (var logs = CapturingSlf4jProvider.watch(BackgroundLoopGuard.class)) {
            assertEquals(BackgroundLoopGuard.Logged.FIRST, guard.failed(unprintable));
            assertEquals(BackgroundLoopGuard.Logged.REPEAT, guard.failed(unprintable));
            assertTrue(logs.records().stream().anyMatch(r -> r.message().contains(UnprintableError.class.getName())),
                    "the failure is still logged, by class name");
        }
        assertEquals(2, guard.consecutiveFailures());
        assertEquals(List.of(
                        new RecordingEngineObserver.LoopErrorCall("timer-poller", UnprintableError.class.getName()),
                        new RecordingEngineObserver.LoopErrorCall("timer-poller", UnprintableError.class.getName())),
                observer.loopErrors());
    }

    @Test
    void aFailureThatCannotBePrintedWhileStoppingIsContained() {
        stopping.set(true);
        assertEquals(BackgroundLoopGuard.Logged.STOPPING, guard.failed(new UnprintableError()));
    }

    @Test
    void theUncaughtHandlerStillLogsAFailureThatCannotBePrinted() {
        try (var logs = CapturingSlf4jProvider.watch(BackgroundLoopGuard.class)) {
            guard.uncaughtExceptionHandler().uncaughtException(Thread.currentThread(), new UnprintableError());
            assertTrue(logs.records().stream().anyMatch(r -> r.message().contains(UnprintableError.class.getName())),
                    "the dead loop is still reported, by class name");
        }
    }

    @Test
    void theUncaughtHandlerNeverThrows() {
        assertDoesNotThrow(() -> guard.uncaughtExceptionHandler()
                .uncaughtException(Thread.currentThread(), new ExecutorShutdownException("test")));
    }
}
