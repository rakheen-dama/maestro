package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that firing or cancelling an {@code await-signal-*} deadline row does
 * not leave a stray one-shot wake permit at {@code <workflowId>:timer:<timerId>}.
 *
 * <p>Nothing ever parks on that key for an await — the await parks on its
 * signal key and is woken through {@code SignalManager.onTimerTransition} — so
 * the {@code :timer:} unpark only stored a permit nobody consumes, one per
 * poller-fired await timeout, until the workflow finished. A {@code sleep-*}
 * row still gets its {@code :timer:} unpark (and so its permit when nobody is
 * parked yet): that is the sleep's real wake path.
 */
@DisplayName("await-signal-* timer transitions leave no stray :timer: wake permit")
class AwaitTimerStrayPermitTest {

    private VersionedInMemoryStore store;
    private WorkflowExecutor executor;
    private ParkingLot parkingLot;

    @BeforeEach
    void setUp() throws Exception {
        store = new VersionedInMemoryStore();
        executor = new WorkflowExecutor(store, null, null, null,
                new PayloadSerializer(new ObjectMapper()), "test-service");
        var field = WorkflowExecutor.class.getDeclaredField("parkingLot");
        field.setAccessible(true);
        parkingLot = (ParkingLot) field.get(executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    @DisplayName("fireTimer on an await-signal-* row stores no permit")
    void fireAwaitTimer_storesNoPermit() {
        var row = pendingRow(SignalManager.TIMER_ID_PREFIX + 3);

        executor.fireTimer("wf-permit", row.timerId(), row.id());

        assertEquals(TimerStatus.FIRED, store.findTimer(row.workflowInstanceId(), row.timerId())
                .orElseThrow().status());
        assertEquals(0, parkingLot.pendingWakeCount(), "nobody parks on :timer: for an await");
    }

    @Test
    @DisplayName("cancelTimer on an await-signal-* row stores no permit")
    void cancelAwaitTimer_storesNoPermit() {
        var row = pendingRow(SignalManager.TIMER_ID_PREFIX + 3);

        assertTrue(executor.cancelTimer("wf-permit", row.timerId(), row.id()));

        assertEquals(0, parkingLot.pendingWakeCount(), "nobody parks on :timer: for an await");
    }

    @Test
    @DisplayName("fireTimer on a sleep-* row still unparks its :timer: key (a permit when nobody is parked yet)")
    void fireSleepTimer_stillUnparksTheTimerKey() {
        var row = pendingRow("sleep-3");

        executor.fireTimer("wf-permit", row.timerId(), row.id());

        assertEquals(1, parkingLot.pendingWakeCount(), "the sleep's wake path is unchanged");
    }

    private WorkflowTimer pendingRow(String timerId) {
        var row = new WorkflowTimer(UUID.randomUUID(), UUID.randomUUID(), "wf-permit", timerId,
                Instant.now(), TimerStatus.PENDING, Instant.now());
        store.saveTimer(row);
        return row;
    }
}
