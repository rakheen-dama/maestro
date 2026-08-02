package io.b2mash.maestro.integration.e2e.chaos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RED pins for the soak-killing pacer runaway (checker-blindness investigation
 * §5/§7): a single thread interrupt during Poisson pacing must abort generation
 * loudly and promptly — it must never degrade into a hot loop that submits
 * scripts at allocation speed (observed: ~1.8M runaway submissions vs a 2,400
 * intended budget, host->postgres path drowned, checker blind for 95 min).
 *
 * <p>No containers: the driver is constructed with a null cluster — scripts
 * fail fast on their first cluster access and the pacer loop under test never
 * touches the cluster. Deliberately no {@code driver.close()}: straggler
 * scripts drain on their own, and closing the ledger writer under them would
 * only add noise unrelated to the pacing contract.
 */
class WorkloadDriverPacingTest {

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
    @Timeout(120)
    @DisplayName("interrupt at T+2s of a 10s/600-per-min window: generated count stays <= 3x budget, abort is prompt")
    void interruptMidWindow_abortsGenerationInsteadOfHotLooping() throws Exception {
        WorkloadDriver driver = newDriver();
        int ratePerMinute = 600;
        Duration window = Duration.ofSeconds(10);
        long intendedBudget = ratePerMinute * window.toSeconds() / 60;   // 100

        var exitNanos = new AtomicLong();
        var thrown = new AtomicReference<Throwable>();
        var done = new CountDownLatch(1);
        Thread pacer = new Thread(() -> {
            try {
                driver.generateAt(ratePerMinute, window, "redpin");
            } catch (Throwable t) {
                thrown.set(t);   // the loud-abort contract surfaces here post-fix
            } finally {
                exitNanos.set(System.nanoTime());
                done.countDown();
            }
        }, "redpin-pacer");
        pacer.start();

        Thread.sleep(2000);
        long interruptedAt = System.nanoTime();
        pacer.interrupt();

        assertTrue(done.await(90, TimeUnit.SECONDS), "pacer thread never finished");
        int generated = driver.generatedCount();
        assertTrue(generated <= 3 * intendedBudget,
                "RUNAWAY PACER: " + generated + " scripts generated after one interrupt "
                + "(intended 10s budget at 600/min = " + intendedBudget
                + ", 3x cap = " + 3 * intendedBudget + ")");
        long abortMillis = (exitNanos.get() - interruptedAt) / 1_000_000;
        assertTrue(abortMillis < 3000,
                "generation did not abort promptly after interrupt: " + abortMillis
                + " ms (ran the window out instead), thrown=" + thrown.get());
    }

    @Test
    @Timeout(60)
    @DisplayName("interrupt pending before the first park: generation aborts at seq 0/1 immediately")
    void interruptBeforeFirstPark_abortsImmediately() throws Exception {
        WorkloadDriver driver = newDriver();
        var exitNanos = new AtomicLong();
        var done = new CountDownLatch(1);
        long startedAt = System.nanoTime();
        Thread pacer = new Thread(() -> {
            Thread.currentThread().interrupt();   // pending interrupt before any sleep
            try {
                driver.generateAt(600, Duration.ofSeconds(10), "redpin2");
            } catch (Throwable expectedAfterFix) {
                // loud abort
            } finally {
                exitNanos.set(System.nanoTime());
                done.countDown();
            }
        }, "redpin-pacer-preinterrupted");
        pacer.start();

        assertTrue(done.await(30, TimeUnit.SECONDS), "pacer thread never finished");
        int generated = driver.generatedCount();
        assertTrue(generated <= 1,
                "RUNAWAY PACER: " + generated + " scripts generated with an interrupt "
                + "pending before the first park");
        long elapsedMillis = (exitNanos.get() - startedAt) / 1_000_000;
        assertTrue(elapsedMillis < 2000,
                "generation did not abort immediately on a pending interrupt: "
                + elapsedMillis + " ms");
    }
}
