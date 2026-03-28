package io.b2mash.maestro.core.retry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link RetryUntilOptions}.
 */
class RetryUntilOptionsTest {

    @Test
    @DisplayName("defaultOptions has expected values")
    void defaultOptions() {
        var opts = RetryUntilOptions.defaultOptions();
        assertEquals(100, opts.maxAttempts());
        assertEquals(Duration.ofHours(24), opts.maxDuration());
        assertEquals(Duration.ofSeconds(5), opts.initialInterval());
        assertEquals(Duration.ofMinutes(5), opts.maxInterval());
        assertEquals(2.0, opts.backoffMultiplier());
    }

    @Test
    @DisplayName("builder produces valid options")
    void builderWorks() {
        var opts = RetryUntilOptions.builder()
                .maxAttempts(10)
                .maxDuration(Duration.ofMinutes(30))
                .initialInterval(Duration.ofSeconds(1))
                .maxInterval(Duration.ofSeconds(30))
                .backoffMultiplier(1.5)
                .build();

        assertEquals(10, opts.maxAttempts());
        assertEquals(Duration.ofMinutes(30), opts.maxDuration());
        assertEquals(Duration.ofSeconds(1), opts.initialInterval());
        assertEquals(Duration.ofSeconds(30), opts.maxInterval());
        assertEquals(1.5, opts.backoffMultiplier());
    }

    // ── Validation ──────────────────────────────────────────────────

    @Test
    @DisplayName("maxAttempts < 1 throws")
    void maxAttemptsValidation() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetryUntilOptions(0, Duration.ofHours(1), Duration.ofSeconds(1),
                        Duration.ofSeconds(10), 2.0));
    }

    @Test
    @DisplayName("null maxDuration throws")
    void nullMaxDuration() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetryUntilOptions(10, null, Duration.ofSeconds(1),
                        Duration.ofSeconds(10), 2.0));
    }

    @Test
    @DisplayName("null initialInterval throws")
    void nullInitialInterval() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetryUntilOptions(10, Duration.ofHours(1), null,
                        Duration.ofSeconds(10), 2.0));
    }

    @Test
    @DisplayName("null maxInterval throws")
    void nullMaxInterval() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetryUntilOptions(10, Duration.ofHours(1), Duration.ofSeconds(1),
                        null, 2.0));
    }

    @Test
    @DisplayName("negative maxDuration throws")
    void negativeMaxDuration() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetryUntilOptions(10, Duration.ofSeconds(-1), Duration.ofSeconds(1),
                        Duration.ofSeconds(10), 2.0));
    }

    @Test
    @DisplayName("negative initialInterval throws")
    void negativeInitialInterval() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetryUntilOptions(10, Duration.ofHours(1), Duration.ofSeconds(-1),
                        Duration.ofSeconds(10), 2.0));
    }

    @Test
    @DisplayName("negative maxInterval throws")
    void negativeMaxInterval() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetryUntilOptions(10, Duration.ofHours(1), Duration.ofSeconds(1),
                        Duration.ofSeconds(-1), 2.0));
    }

    @Test
    @DisplayName("backoffMultiplier < 1.0 throws")
    void backoffMultiplierValidation() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetryUntilOptions(10, Duration.ofHours(1), Duration.ofSeconds(1),
                        Duration.ofSeconds(10), 0.5));
    }

    @Test
    @DisplayName("backoffMultiplier NaN throws")
    void backoffMultiplierNaN() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetryUntilOptions(10, Duration.ofHours(1), Duration.ofSeconds(1),
                        Duration.ofSeconds(10), Double.NaN));
    }

    @Test
    @DisplayName("backoffMultiplier Infinity throws")
    void backoffMultiplierInfinity() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetryUntilOptions(10, Duration.ofHours(1), Duration.ofSeconds(1),
                        Duration.ofSeconds(10), Double.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("edge case: maxAttempts = 1 is valid")
    void singleAttemptIsValid() {
        var opts = new RetryUntilOptions(1, Duration.ofHours(1), Duration.ofSeconds(1),
                Duration.ofSeconds(10), 1.0);
        assertNotNull(opts);
        assertEquals(1, opts.maxAttempts());
    }

    @Test
    @DisplayName("edge case: zero duration intervals are valid")
    void zeroDurationsValid() {
        var opts = new RetryUntilOptions(10, Duration.ZERO, Duration.ZERO,
                Duration.ZERO, 1.0);
        assertNotNull(opts);
    }
}
