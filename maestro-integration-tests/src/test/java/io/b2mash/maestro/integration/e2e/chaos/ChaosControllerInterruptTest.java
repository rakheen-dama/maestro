package io.b2mash.maestro.integration.e2e.chaos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RED pin for the controller-side swallowed interrupt (checker-blindness
 * investigation §5 note): {@code ChaosController.sleep} must abort the schedule
 * loudly on interrupt — a swallowed interrupt zeroes every inter-action gap and
 * pause/outage duration, blasting docker operations in a hot loop.
 *
 * <p>No containers: the cluster is null. Pre-fix, the interrupt is swallowed,
 * the zero-length gap elapses instantly and the controller dispatches its first
 * action into the null cluster (an anonymous NPE); post-fix the interrupt
 * aborts BEFORE any action is dispatched, with a message naming the interrupt.
 */
class ChaosControllerInterruptTest {

    @TempDir
    Path tmp;

    @Test
    @Timeout(30)
    @DisplayName("an interrupted controller aborts the schedule loudly before dispatching any action")
    void interrupt_abortsScheduleLoudly() {
        var evidence = new EvidenceWriter(RunIdentity.capture(7L, ChaosMode.PR_GATE), tmp);
        var controller = new ChaosController(null, ChaosMode.PR_GATE, evidence,
                new SplittableRandom(7L));

        Throwable thrown = null;
        long started = System.nanoTime();
        Thread.currentThread().interrupt();   // pending interrupt before the first gap sleep
        try {
            controller.run(1);
        } catch (Throwable t) {
            thrown = t;
        } finally {
            // never leak the interrupt flag into other tests
            Thread.interrupted();
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertNotNull(thrown, "controller must abort loudly on interrupt");
        assertTrue(String.valueOf(thrown.getMessage()).toLowerCase().contains("interrupt"),
                "the abort must name the interrupt (no action may be dispatched on a "
                + "zeroed gap); got: " + thrown);
        assertTrue(elapsedMillis < 2000,
                "controller must abort promptly on interrupt, took " + elapsedMillis + " ms");
    }
}
