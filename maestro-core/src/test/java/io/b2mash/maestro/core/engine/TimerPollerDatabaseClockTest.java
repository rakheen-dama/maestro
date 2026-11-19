package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    private RunningLoop running;

    @AfterEach
    void tearDown() {
        if (running != null) {
            running.stop();
        }
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    void thePollerAsksTheStoreForTimersDueByItsOwnClock() throws Exception {
        var store = new ClockRecordingStore();
        executor = WorkflowExecutor.builder(store, serializer, "svc").distributedLock(new InMemoryDistributedLock()).build();
        poller = new TimerPoller(store, executor, new InMemoryDistributedLock(), "svc", Duration.ofMillis(50), 10);
        running = RunningLoop.start(poller.spec(), poller);
        awaitPolls(store);
        running.stop();

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
        executor = WorkflowExecutor.builder(store, serializer, "svc").distributedLock(new InMemoryDistributedLock()).build();
        executor.startTimerPoller(Duration.ofMillis(50), 10);
        awaitPolls(store);

        assertEquals("store-clock", store.polls.getFirst());
        assertFalse(store.polls.contains("caller-clock"), store.polls.toString());
    }

    /**
     * Finding 3.7, the skew WARN: every node arms deadlines, so every node's
     * poller checks its clock against the store's — leader or not — once at
     * start and then only every {@link ClockSkewCheck#INTERVAL}.
     */
    @Test
    void everyNodesPollerReadsTheStoreClockOnStartEvenWhenItIsNotTheLeader() throws Exception {
        var store = new ClockRecordingStore();
        executor = WorkflowExecutor.builder(store, serializer, "svc").distributedLock(new InMemoryDistributedLock()).build();
        poller = new TimerPoller(store, executor, new NeverLeaderLock(), "svc", Duration.ofMillis(50), 10);
        var started = System.nanoTime();
        running = RunningLoop.start(poller.spec(), poller);
        var deadline = started + Duration.ofSeconds(10).toNanos();
        var atLeast = started + Duration.ofSeconds(1).toNanos();
        while (System.nanoTime() < deadline
                && (store.clockReads.get() == 0 || System.nanoTime() < atLeast)) {
            Thread.sleep(10);
        }
        running.stop();

        assertTrue(store.clockReads.get() > 0, "awaited (10 s) the non-leader poller's start-up store-clock read");
        assertEquals(1, store.clockReads.get(), "one check at start, the next only after ClockSkewCheck.INTERVAL");
    }

    /**
     * W6: the skew check's {@code SELECT now()} can block on an exhausted
     * connection pool for longer than the 15 s leader TTL, so each pass
     * renews the timer-poller leadership before it reads the store clock.
     */
    @Test
    void eachPassRenewsTheLeadershipBeforeItReadsTheStoreClock() throws Exception {
        var calls = new CopyOnWriteArrayList<String>();
        var store = new ClockRecordingStore() {
            @Override
            public Optional<Instant> storeTime() {
                calls.add("store-clock");
                return super.storeTime();
            }
        };
        var lock = new NeverLeaderLock() {
            @Override
            public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
                calls.add("leader");
                return super.trySetLeader(electionKey, candidateId, ttl);
            }
        };
        executor = WorkflowExecutor.builder(store, serializer, "svc").distributedLock(new InMemoryDistributedLock()).build();
        poller = new TimerPoller(store, executor, lock, "svc", Duration.ofMillis(50), 10);
        running = RunningLoop.start(poller.spec(), poller);
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!calls.contains("store-clock") && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        running.stop();

        assertTrue(calls.contains("store-clock"), "awaited the poller's start-up store-clock read: " + calls);
        assertEquals("leader", calls.getFirst(), "the leadership is renewed first: " + calls);
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
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (store.polls.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(store.polls.isEmpty(), "awaited (10 s) the poller's first due-timer query");
    }

    /** A lock under which this node never wins the timer-poller election. */
    static class NeverLeaderLock implements DistributedLock {
        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            throw new UnsupportedOperationException("tryAcquire");
        }

        @Override
        public void release(LockHandle handle) {
            throw new UnsupportedOperationException("release");
        }

        @Override
        public boolean renew(LockHandle handle, Duration ttl) {
            throw new UnsupportedOperationException("renew");
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return false;
        }
    }

    /** Records which due-timer method the poller called, and its store-clock reads. */
    static class ClockRecordingStore extends VersionedInMemoryStore {
        final List<String> polls = new CopyOnWriteArrayList<>();
        final AtomicInteger clockReads = new AtomicInteger();

        @Override
        public Optional<Instant> storeTime() {
            clockReads.incrementAndGet();
            return Optional.of(Instant.now());
        }

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
