package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.WorkflowTerminatedException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A terminate racing the end of the run it terminates leaves no poison entry
 * behind once the run has ended.
 *
 * <p>{@code WorkflowExecutor.terminateWorkflow} looks the run up in its
 * running-workflow map and then calls {@link ParkingLot#abandonWorkflow}. The
 * run can end in between: its {@code finally} removes it from the map, clears
 * its permits, releases its lock and calls {@link ParkingLot#clearTerminated}
 * — all before the terminate puts its poison. The lot's {@code runsHere}
 * predicate is that map, so these tests model each ordering with it.
 */
class ParkingLotTerminatePoisonTest {

    /**
     * The run ended entirely between the terminate's lookup and its sweep: the
     * run's own {@code clearTerminated} ran before the poison was put, so
     * nothing else would ever remove it.
     */
    @Test
    void aTerminateReachingTheLotAfterTheRunEnded_leavesNoPoison() throws Exception {
        var lot = new ParkingLot(id -> false);   // the run is no longer registered
        lot.clearTerminated("wf");                // the run's finally, before the sweep

        lot.abandonWorkflow("wf", "operator");

        assertEquals(false, lot.isTerminated("wf"), "a poison entry outlived the run it was for");
        // A later run of the same id (the id reused after the row was purged)
        // parks normally rather than being refused as terminated.
        assertThrows(TimeoutException.class,
                () -> lot.parkWithTimeout("wf", "wf:signal:go", Duration.ofMillis(50)),
                "a later run of the id is not refused by the stale poison");
    }

    /**
     * The run is still registered when the sweep re-checks: the poison stays,
     * so a park racing the sweep is refused, and the run's own
     * {@code clearTerminated} removes it when the run ends.
     */
    @Test
    void aTerminateWhileTheRunIsRegistered_keepsThePoisonUntilTheRunClearsIt() {
        var running = new AtomicBoolean(true);
        var lot = new ParkingLot(id -> running.get());

        lot.abandonWorkflow("wf", "operator");

        assertTrue(lot.isTerminated("wf"), "the poison is kept while the run is registered");
        assertThrows(WorkflowTerminatedException.class,
                () -> lot.parkWithTimeout("wf", "wf:signal:go", Duration.ofSeconds(5)),
                "a park racing the sweep is refused");

        running.set(false);          // the run's finally: map removal ...
        lot.clearTerminated("wf");   // ... then its clearTerminated
        assertEquals(false, lot.isTerminated("wf"));
    }
}
