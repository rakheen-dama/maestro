package io.b2mash.maestro.core.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Plan 04 open-issue item 19 (accepted, fixed in the plan 06 tidy): the timer
 * poller, recovery poller and signal janitor are claimed with a CAS after an
 * up-front {@code shuttingDown} check. A {@code shutdown()} landing between the
 * two used to find the slot empty, stop nothing, and leave the loop running on
 * a stopped executor. Whichever of start and shutdown runs last must now stop it.
 */
@DisplayName("A background loop started concurrently with shutdown() never outlives it")
class BackgroundLoopStartShutdownRaceTest {

    private static final int ROUNDS = 400;
    private static final Duration LOOP_INTERVAL = Duration.ofMillis(30);

    @Test
    @DisplayName("timer poller")
    void timerPoller() throws Exception {
        assertEquals(0, leakedLoops(e -> e.startTimerPoller(LOOP_INTERVAL, 10)));
    }

    @Test
    @DisplayName("recovery poller")
    void recoveryPoller() throws Exception {
        assertEquals(0, leakedLoops(e -> e.startRecoveryPoller(Map.of(), LOOP_INTERVAL)));
    }

    @Test
    @DisplayName("signal janitor")
    void signalJanitor() throws Exception {
        assertEquals(0, leakedLoops(e -> e.startSignalJanitor(LOOP_INTERVAL, Duration.ofDays(1))));
    }

    private interface Starter {
        void start(WorkflowExecutor executor);
    }

    /** Counts every call a polling loop makes, whichever of the three loops it is. */
    private static final class CountingStore extends VersionedInMemoryStore {
        final AtomicInteger polls = new AtomicInteger();

        @Override
        public List<io.b2mash.maestro.core.model.WorkflowTimer> getDueTimers(Instant now, int batchSize) {
            polls.incrementAndGet();
            return super.getDueTimers(now, batchSize);
        }

        @Override
        public List<io.b2mash.maestro.core.model.WorkflowInstance> getRecoverableInstances() {
            polls.incrementAndGet();
            return super.getRecoverableInstances();
        }

        @Override
        public int deleteUnconsumedSignalsForTerminalInstancesBefore(Instant cutoff) {
            polls.incrementAndGet();
            return super.deleteUnconsumedSignalsForTerminalInstancesBefore(cutoff);
        }
    }

    /**
     * Races start against shutdown {@link #ROUNDS} times. A loop that outlived
     * its executor is detected by what it does, not by what the executor
     * reports: after {@code shutdown()} has returned and the starter has
     * finished, the store must see no further polls.
     *
     * @return how many rounds left a loop polling
     */
    private static int leakedLoops(Starter starter) throws Exception {
        var stores = new ArrayList<CountingStore>();
        var snapshots = new ArrayList<Integer>();
        var starterFailure = new AtomicReference<Throwable>();
        for (var i = 0; i < ROUNDS; i++) {
            var store = new CountingStore();
            var executor = new WorkflowExecutor(store, null, null, null,
                    new PayloadSerializer(new ObjectMapper()), "node", "maestro:lock:",
                    Duration.ofSeconds(30), true, Duration.ofSeconds(5), Duration.ofSeconds(30),
                    new io.b2mash.maestro.core.observe.RecordingEngineObserver());
            var barrier = new CyclicBarrier(2);
            var starting = Thread.ofPlatform().start(() -> {
                try {
                    barrier.await();
                    starter.start(executor);
                } catch (IllegalStateException expectedWhenShutdownWins) {
                    // fine — the executor was already shutting down
                } catch (Throwable t) {
                    starterFailure.set(t);
                }
            });
            barrier.await();
            executor.shutdown();
            starting.join();
            stores.add(store);
            snapshots.add(store.polls.get());
        }
        assertNull(starterFailure.get(), () -> "starter thread failed: " + starterFailure.get());
        Thread.sleep(LOOP_INTERVAL.multipliedBy(6));
        var leaked = 0;
        for (var i = 0; i < ROUNDS; i++) {
            if (stores.get(i).polls.get() != snapshots.get(i)) {
                leaked++;
            }
        }
        return leaked;
    }
}
