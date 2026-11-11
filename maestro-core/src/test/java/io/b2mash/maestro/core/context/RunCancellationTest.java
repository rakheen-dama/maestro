package io.b2mash.maestro.core.context;

import io.b2mash.maestro.core.exception.StoreUnavailableException;
import io.b2mash.maestro.core.exception.WorkflowTerminatedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the precedence of {@link RunCancellation.Cause#STORE_UNAVAILABLE}: it
 * ranks with {@code STALE} (the first of the two wins), {@code TERMINATED}
 * replaces it, and it replaces {@code SHUTDOWN}.
 */
@DisplayName("RunCancellation STORE_UNAVAILABLE precedence")
class RunCancellationTest {

    @Test
    @DisplayName("a store-unavailable request after a stale one gives way and keeps the stale detail")
    void storeUnavailableAfterStaleGivesWay() {
        var c = new RunCancellation();
        assertTrue(c.requestStale("append collision at sequence 4"));

        assertFalse(c.requestStoreUnavailable("appendEvent: boom"));

        assertEquals(RunCancellation.Cause.STALE, c.cause());
        assertEquals("append collision at sequence 4", c.reason());
    }

    @Test
    @DisplayName("a stale request after a store-unavailable one gives way and keeps its detail")
    void staleAfterStoreUnavailableGivesWay() {
        var c = new RunCancellation();
        assertTrue(c.requestStoreUnavailable("appendEvent: boom"));

        assertFalse(c.requestStale("append collision at sequence 4"));

        assertEquals(RunCancellation.Cause.STORE_UNAVAILABLE, c.cause());
        assertEquals("appendEvent: boom", c.reason());
    }

    @Test
    @DisplayName("a termination replaces a store-unavailable request")
    void terminationReplacesStoreUnavailable() {
        var c = new RunCancellation();
        assertTrue(c.requestStoreUnavailable("appendEvent: boom"));

        assertTrue(c.requestTermination("operator"));

        assertEquals(RunCancellation.Cause.TERMINATED, c.cause());
        assertThrows(WorkflowTerminatedException.class, () -> c.throwIfRequested("wf"));
    }

    @Test
    @DisplayName("a store-unavailable request replaces a shutdown")
    void storeUnavailableReplacesShutdown() {
        var c = new RunCancellation();
        assertTrue(c.requestShutdown());

        assertTrue(c.requestStoreUnavailable("updateInstance: boom"));

        assertEquals(RunCancellation.Cause.STORE_UNAVAILABLE, c.cause());
        assertEquals("updateInstance: boom", c.reason());
    }

    @Test
    @DisplayName("a repeated store-unavailable request keeps the first detail")
    void repeatedStoreUnavailableKeepsFirstDetail() {
        var c = new RunCancellation();
        assertTrue(c.requestStoreUnavailable("first"));

        assertFalse(c.requestStoreUnavailable("second"));

        assertEquals("first", c.reason());
    }

    @Test
    @DisplayName("throwIfStoreUnavailable throws only for STORE_UNAVAILABLE")
    void throwIfStoreUnavailableThrowsOnlyForThatCause() {
        var none = new RunCancellation();
        assertDoesNotThrow(() -> none.throwIfStoreUnavailable("wf"));

        var stale = new RunCancellation();
        stale.requestStale("x");
        assertDoesNotThrow(() -> stale.throwIfStoreUnavailable("wf"));

        var shutdown = new RunCancellation();
        shutdown.requestShutdown();
        assertDoesNotThrow(() -> shutdown.throwIfStoreUnavailable("wf"));

        var terminated = new RunCancellation();
        terminated.requestTermination(null);
        assertDoesNotThrow(() -> terminated.throwIfStoreUnavailable("wf"));

        var unavailable = new RunCancellation();
        unavailable.requestStoreUnavailable("appendEvent: boom");
        var e = assertThrows(StoreUnavailableException.class, () -> unavailable.throwIfStoreUnavailable("wf"));
        assertTrue(e.getMessage().contains("wf") && e.getMessage().contains("appendEvent: boom"),
                e.getMessage());
    }

    @Test
    @DisplayName("throwIfRequested raises StoreUnavailableException for STORE_UNAVAILABLE")
    void throwIfRequestedRaisesStoreUnavailable() {
        var c = new RunCancellation();
        c.requestStoreUnavailable("appendEvent: boom");

        assertThrows(StoreUnavailableException.class, () -> c.throwIfRequested("wf"));
    }
}
