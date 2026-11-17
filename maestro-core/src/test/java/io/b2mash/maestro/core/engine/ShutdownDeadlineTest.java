package io.b2mash.maestro.core.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP5 D-1: one shutdown's time budget, on a fake monotonic clock.
 */
@DisplayName("ShutdownDeadline")
class ShutdownDeadlineTest {

    /** Starts at an arbitrary, negative origin: nanoTime's origin means nothing. */
    private final AtomicLong nanos = new AtomicLong(-5_000_000_000L);

    private ShutdownDeadline start(Duration budget) {
        return ShutdownDeadline.start(budget, nanos::get);
    }

    private void advance(Duration by) {
        nanos.addAndGet(by.toNanos());
    }

    @Test
    void remainingCountsDownAndNeverGoesBelowZero() {
        var deadline = start(Duration.ofMillis(1_000));
        assertEquals(Duration.ofMillis(1_000), deadline.remaining());
        advance(Duration.ofMillis(400));
        assertEquals(Duration.ofMillis(600), deadline.remaining());
        advance(Duration.ofMillis(600));
        assertEquals(Duration.ZERO, deadline.remaining());
        advance(Duration.ofSeconds(10));
        assertEquals(Duration.ZERO, deadline.remaining());
    }

    @Test
    void remainingAtMostIsTheSmallerOfTheCapAndWhatIsLeft() {
        var deadline = start(Duration.ofSeconds(20));
        assertEquals(Duration.ofSeconds(2), deadline.remainingAtMost(Duration.ofSeconds(2)));
        advance(Duration.ofMillis(19_500));
        assertEquals(Duration.ofMillis(500), deadline.remainingAtMost(Duration.ofSeconds(2)));
        advance(Duration.ofSeconds(1));
        assertEquals(Duration.ZERO, deadline.remainingAtMost(Duration.ofSeconds(2)));
    }

    @Test
    void passedTurnsTrueExactlyAtTheBudget() {
        var deadline = start(Duration.ofMillis(300));
        assertFalse(deadline.passed());
        advance(Duration.ofMillis(300).minusNanos(1));
        assertFalse(deadline.passed());
        advance(Duration.ofNanos(1));
        assertTrue(deadline.passed());
    }

    @Test
    void elapsedFollowsTheClock() {
        var deadline = start(Duration.ofSeconds(1));
        assertEquals(Duration.ZERO, deadline.elapsed());
        advance(Duration.ofMillis(1_250));
        assertEquals(Duration.ofMillis(1_250), deadline.elapsed());
        assertEquals(Duration.ofSeconds(1), deadline.budget());
    }

    @Test
    void aBudgetThatIsNotPositiveIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> start(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> start(Duration.ofMillis(-1)));
    }
}
