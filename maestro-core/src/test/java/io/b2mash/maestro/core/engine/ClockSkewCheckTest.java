package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.exception.StoreUnavailableException;
import io.b2mash.maestro.core.logcapture.CapturingSlf4jProvider;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static io.b2mash.maestro.core.engine.ClockSkewCheck.skew;
import static io.b2mash.maestro.core.engine.ClockSkewCheck.warning;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClockSkewCheck} (finding 3.7): the skew measurement, the WARN
 * threshold and copy, the cadence, and what a store without a clock, a store
 * fault and an {@code Error} do.
 */
class ClockSkewCheckTest {

    private static final Instant T = Instant.parse("2026-11-13T10:00:00Z");

    private final AtomicLong nanos = new AtomicLong(42);

    // ── skew ───────────────────────────────────────────────────────────

    @Test
    void skewIsZeroWhenTheStoreTimeFallsInsideTheReadWindow() {
        assertEquals(Duration.ZERO, skew(T, T.plusMillis(5), T.plusMillis(10)));
    }

    @Test
    void aStoreTimeBeforeTheWindowMeansThisNodeIsAhead() {
        assertEquals(Duration.ofMillis(1500), skew(T, T.minusMillis(1500), T.plusMillis(10)));
    }

    @Test
    void aStoreTimeAfterTheWindowMeansThisNodeIsBehind() {
        assertEquals(Duration.ofMillis(-2000), skew(T, T.plusMillis(2010), T.plusMillis(10)));
    }

    // ── warning ────────────────────────────────────────────────────────

    @Test
    void exactlyOneSecondIsNotWarned() {
        assertEquals(Optional.empty(), warning(Duration.ofSeconds(1)));
        assertEquals(Optional.empty(), warning(Duration.ofSeconds(-1)));
    }

    @Test
    void aNodeAheadIsWarnedThatItsDeadlinesFireLate() {
        assertEquals(Optional.of("This node's clock is 1500 ms ahead of the database clock (warns above 1 s). "
                        + "Due timers are polled on the database clock, but sleep() and awaitSignal() deadlines "
                        + "are armed on the clock of the node that schedules them, so a deadline armed here is set "
                        + "1500 ms later than the database clock would set it: a sleep() can wake, and an "
                        + "awaitSignal() timeout can expire, up to 1500 ms late. "
                        + "Check this node's time synchronisation (NTP)."),
                warning(Duration.ofMillis(1500)));
    }

    @Test
    void aNodeBehindIsWarnedThatItsDeadlinesFireEarly() {
        assertEquals(Optional.of("This node's clock is 2000 ms behind the database clock (warns above 1 s). "
                        + "Due timers are polled on the database clock, but sleep() and awaitSignal() deadlines "
                        + "are armed on the clock of the node that schedules them, so a deadline armed here is set "
                        + "2000 ms earlier than the database clock would set it: a sleep() can wake, and an "
                        + "awaitSignal() timeout can expire, up to 2000 ms early. "
                        + "Check this node's time synchronisation (NTP)."),
                warning(Duration.ofMillis(-2000)));
    }

    // ── maybeCheck ─────────────────────────────────────────────────────

    @Test
    void checksOnTheFirstCallThenOncePerInterval() {
        var store = new ScriptedClockStore(() -> Optional.of(T.minusSeconds(3)));
        var check = check(store);

        assertTrue(check.maybeCheck().isPresent());
        nanos.addAndGet(Duration.ofMinutes(10).minusSeconds(1).toNanos());
        assertEquals(Optional.empty(), check.maybeCheck());
        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        assertTrue(check.maybeCheck().isPresent());
        assertEquals(2, store.reads.get());
    }

    @Test
    void theMeasuredSkewIsReturned() {
        var check = check(new ScriptedClockStore(() -> Optional.of(T.minusSeconds(3))));
        assertEquals(Optional.of(Duration.ofSeconds(3)), check.maybeCheck());
    }

    @Test
    void aSkewAboveTheThresholdIsLoggedAtWarn_andOneWithinItIsNot() {
        var skewed = check(new ScriptedClockStore(() -> Optional.of(T.minusMillis(1500))));
        var inSync = new ClockSkewCheck(new ScriptedClockStore(() -> Optional.of(T.minusMillis(900))),
                InstantSource.fixed(T), nanos::get);
        try (var capture = CapturingSlf4jProvider.watch(ClockSkewCheck.class)) {
            inSync.maybeCheck();
            skewed.maybeCheck();
            assertEquals(List.of(warning(Duration.ofMillis(1500)).orElseThrow()),
                    capture.records().stream().filter(r -> r.level() == Level.WARN)
                            .map(CapturingSlf4jProvider.CapturedLog::message).toList());
        }
    }

    @Test
    void aStoreWithoutAClockIsAskedOnceAndNeverAgain() {
        var store = new ScriptedClockStore(Optional::empty);
        var check = check(store);

        assertEquals(Optional.empty(), check.maybeCheck());
        nanos.addAndGet(ClockSkewCheck.INTERVAL.toNanos());
        assertEquals(Optional.empty(), check.maybeCheck());
        assertEquals(1, store.reads.get());
    }

    @Test
    void aStoreFaultIsSwallowedAndRetriedNextInterval() {
        var store = new ScriptedClockStore(() -> {
            throw new StoreUnavailableException("down", new SQLException("connection lost", "08006"));
        });
        var check = check(store);

        assertEquals(Optional.empty(), check.maybeCheck());       // no throw
        nanos.addAndGet(ClockSkewCheck.INTERVAL.toNanos());
        check.maybeCheck();
        assertEquals(2, store.reads.get());
    }

    @Test
    void anErrorFromTheStorePropagates() {
        var check = check(new ScriptedClockStore(() -> {
            throw new ExecutorShutdownException("test");
        }));
        assertThrows(ExecutorShutdownException.class, check::maybeCheck);
    }

    // ── fixtures ───────────────────────────────────────────────────────

    private ClockSkewCheck check(ScriptedClockStore store) {
        return new ClockSkewCheck(store, InstantSource.fixed(T), nanos::get);
    }

    /** A store whose {@code storeTime()} is scripted and counted. */
    static final class ScriptedClockStore extends VersionedInMemoryStore {
        final AtomicInteger reads = new AtomicInteger();
        private final Supplier<Optional<Instant>> answer;

        ScriptedClockStore(Supplier<Optional<Instant>> answer) {
            this.answer = answer;
        }

        @Override
        public Optional<Instant> storeTime() {
            reads.incrementAndGet();
            return answer.get();
        }
    }
}
