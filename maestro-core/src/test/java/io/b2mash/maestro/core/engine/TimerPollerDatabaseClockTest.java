package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Finding 3.7: the timer poller asks the store for the timers due by the
 * store's own clock ({@code WorkflowStore.getDueTimers(int)}) rather than
 * passing this node's {@code Instant.now()}, so a leader whose clock is off
 * no longer moves when every timer fires.
 */
class TimerPollerDatabaseClockTest {

    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private WorkflowExecutor executor;
    private TimerPoller poller;

    @AfterEach
    void tearDown() {
        if (poller != null) {
            poller.stop();
        }
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    void thePollerAsksTheStoreForTimersDueByItsOwnClock() throws Exception {
        var store = new ClockRecordingStore();
        executor = WorkflowExecutor.builder(store, serializer, "svc").build();
        poller = new TimerPoller(store, executor, null, "svc", Duration.ofMillis(50), 10);
        poller.start();
        awaitPolls(store);
        poller.stop();

        assertEquals("store-clock", store.polls.getFirst());
        assertFalse(store.polls.contains("caller-clock"), store.polls.toString());
    }

    /**
     * The executor's own poller reaches the store through {@code StoreFaultGuard},
     * a reflective proxy: the store's override of the new method is what runs.
     */
    @Test
    void theExecutorsPollerReachesTheStoreClockThroughTheFaultGuard() throws Exception {
        var store = new ClockRecordingStore();
        executor = WorkflowExecutor.builder(store, serializer, "svc").build();
        executor.startTimerPoller(Duration.ofMillis(50), 10);
        awaitPolls(store);

        assertEquals("store-clock", store.polls.getFirst());
        assertFalse(store.polls.contains("caller-clock"), store.polls.toString());
    }

    @Test
    void aStoreWithoutAClockIsPolledOnTheCallersClock() {
        var store = new VersionedInMemoryStore();
        var due = new WorkflowTimer(UUID.randomUUID(), UUID.randomUUID(), "wf", "sleep-1",
                Instant.now().minusSeconds(10), TimerStatus.PENDING, Instant.now());
        store.saveTimer(due);
        store.saveTimer(new WorkflowTimer(UUID.randomUUID(), UUID.randomUUID(), "wf", "sleep-2",
                Instant.now().plusSeconds(3600), TimerStatus.PENDING, Instant.now()));
        assertEquals(List.of(due.id()), store.getDueTimers(10).stream().map(WorkflowTimer::id).toList());
    }

    private static void awaitPolls(ClockRecordingStore store) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (store.polls.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    /** Records which due-timer method the poller called. */
    static final class ClockRecordingStore extends VersionedInMemoryStore {
        final List<String> polls = new CopyOnWriteArrayList<>();

        @Override
        public List<WorkflowTimer> getDueTimers(Instant now, int batchSize) {
            polls.add("caller-clock");
            return super.getDueTimers(now, batchSize);
        }

        @Override
        public List<WorkflowTimer> getDueTimers(int batchSize) {
            polls.add("store-clock");
            return List.of();
        }
    }
}
