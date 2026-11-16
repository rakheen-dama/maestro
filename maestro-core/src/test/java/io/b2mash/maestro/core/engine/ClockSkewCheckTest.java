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
import java.util.ArrayDeque;
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
        assertEquals(Optional.empty(), warning(Duration.ofSeconds(1), Duration.ZERO));
        assertEquals(Optional.empty(), warning(Duration.ofSeconds(-1), Duration.ZERO));
    }

    @Test
    void aNodeAheadIsWarnedThatItsDeadlinesFireLate() {
        assertEquals(Optional.of("This node's clock is 1500 ms ahead of the database clock (warns above 1 s; "
                        + "the upper bound adds the time the store read took). "
                        + "Due timers are polled on the database clock, but sleep() and awaitSignal() deadlines "
                        + "are armed on the clock of the node that schedules them, so a deadline armed here is set "
                        + "1500 ms later than the database clock would set it: a sleep() can wake, and an "
                        + "awaitSignal() timeout can expire, up to 1500 ms late. "
                        + "Check this node's time synchronisation (NTP)."),
                warning(Duration.ofMillis(1500), Duration.ZERO));
    }

    @Test
    void aNodeBehindIsWarnedThatItsDeadlinesFireEarly() {
        assertEquals(Optional.of("This node's clock is 2000 ms behind the database clock (warns above 1 s; "
                        + "the upper bound adds the time the store read took). "
                        + "Due timers are polled on the database clock, but sleep() and awaitSignal() deadlines "
                        + "are armed on the clock of the node that schedules them, so a deadline armed here is set "
                        + "2000 ms earlier than the database clock would set it: a sleep() can wake, and an "
                        + "awaitSignal() timeout can expire, up to 2000 ms early. "
                        + "Check this node's time synchronisation (NTP)."),
                warning(Duration.ofMillis(-2000), Duration.ZERO));
    }

    @Test
    void aReadThatTookTimeIsWarnedWithBothBounds_theUpperRoundedUp() {
        assertEquals(Optional.of("This node's clock is between 1500 and 1521 ms behind the database clock "
                        + "(warns above 1 s; the upper bound adds the time the store read took). "
                        + "Due timers are polled on the database clock, but sleep() and awaitSignal() deadlines "
                        + "are armed on the clock of the node that schedules them, so a deadline armed here is set "
                        + "between 1500 and 1521 ms earlier than the database clock would set it: a sleep() can "
                        + "wake, and an awaitSignal() timeout can expire, up to 1521 ms early. "
                        + "Check this node's time synchronisation (NTP)."),
                warning(Duration.ofMillis(-1500), Duration.ofMillis(20).plusNanos(1)));
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
            assertEquals(List.of(warning(Duration.ofMillis(1500), Duration.ZERO).orElseThrow()),
                    capture.records().stream().filter(r -> r.level() == Level.WARN)
                            .map(CapturingSlf4jProvider.CapturedLog::message).toList());
        }
    }

    /**
     * The node is 5 s ahead and the read takes 3 s: {@code before = T+5s},
     * {@code storeTime = T+2s}, {@code after = T+8s}. The measured skew is
     * 3 s, a lower bound; the WARN must give an upper bound that covers the
     * true 5 s — measured skew plus the read time, 6 s — not the 3 s alone.
     */
    @Test
    void aSlowReadIsWarnedWithAnUpperBoundThatCoversTheTrueOffset() {
        var instants = new ArrayDeque<>(List.of(T.plusSeconds(5), T.plusSeconds(8)));
        InstantSource advancing = instants::poll;
        var check = new ClockSkewCheck(new ScriptedClockStore(() -> Optional.of(T.plusSeconds(2))),
                advancing, nanos::get);
        try (var capture = CapturingSlf4jProvider.watch(ClockSkewCheck.class)) {
            assertEquals(Optional.of(Duration.ofSeconds(3)), check.maybeCheck());
            var warns = capture.records().stream().filter(r -> r.level() == Level.WARN)
                    .map(CapturingSlf4jProvider.CapturedLog::message).toList();
            assertEquals(1, warns.size());
            assertTrue(warns.getFirst().contains("between 3000 and 6000 ms ahead of the database clock"),
                    warns.getFirst());
            assertTrue(warns.getFirst().contains("up to 6000 ms late"), warns.getFirst());
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
