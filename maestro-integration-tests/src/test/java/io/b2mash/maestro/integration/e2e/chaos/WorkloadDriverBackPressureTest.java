package io.b2mash.maestro.integration.e2e.chaos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pin for delta-review-2 I-3: in-flight back-pressure must be LOUD and
 * machine-checkable, never a silent load truncation. Pre-fix, a stalled store
 * pinning every permit throttled generation with zero accounting — a degraded
 * soak could PASS at fractional load and still look Issue 12-comparable.
 *
 * <p>The stalled store is simulated by draining the semaphore through the
 * package-private test seam (equivalent to {@code bound} scripts all wedged on
 * a dead store, which is exactly what pins permits in production).
 */
class WorkloadDriverBackPressureTest {

    @TempDir
    Path tmp;

    private WorkloadDriver newDriver() {
        var config = new ChaosConfig(ChaosMode.SOAK, 42L, 1, 600,
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(5), Map.of(), tmp);
        var evidence = new EvidenceWriter(RunIdentity.capture(42L, ChaosMode.SOAK), tmp);
        return new WorkloadDriver(null, config, evidence, new SplittableRandom(42L));
    }

    @Test
    @Timeout(30)
    @DisplayName("a fully back-pressured window is accounted: waits, max wait, blocked time, zero submissions")
    void stalledStore_backPressureIsAccounted() {
        WorkloadDriver driver = newDriver();
        driver.inFlightForTest().drainPermits();   // simulate a stalled store pinning all permits

        int submitted = driver.generateAt(600, Duration.ofSeconds(2), "bp");

        assertEquals(0, submitted, "no permit, no submission");
        assertEquals(0, driver.generatedCount());
        assertTrue(driver.backPressureWaits() >= 1,
                "a delayed arrival must be counted (was silent pre-fix)");
        long maxWaitMs = driver.backPressureMaxWaitMs();
        assertTrue(maxWaitMs >= 500 && maxWaitMs <= 4000,
                "the wait must span the remaining window (~2s), was " + maxWaitMs + " ms");
        assertTrue(driver.backPressureTotalBlockedMs() >= 500,
                "blocked time must be accounted, was "
                + driver.backPressureTotalBlockedMs() + " ms");
    }

    @Test
    @Timeout(30)
    @DisplayName("normal load never touches the back-pressure accounting")
    void normalLoad_noBackPressureNoise() {
        WorkloadDriver driver = newDriver();

        int submitted = driver.generateAt(600, Duration.ofSeconds(2), "bpfree");

        assertTrue(submitted > 0, "scripts should have been submitted");
        assertEquals(0, driver.backPressureWaits(),
                "uncontended acquisition must not count as back-pressure");
        assertEquals(0, driver.backPressureTotalBlockedMs());
        assertEquals(0, driver.backPressureMaxWaitMs());
    }
}
