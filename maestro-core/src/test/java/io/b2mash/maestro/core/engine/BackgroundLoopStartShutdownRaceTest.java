package io.b2mash.maestro.core.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @Test
    @DisplayName("timer poller")
    void timerPoller() throws Exception {
        assertEquals(0, leakedLoops(e -> e.startTimerPoller(Duration.ofSeconds(30), 10),
                WorkflowExecutor::isTimerPollerRunning));
    }

    @Test
    @DisplayName("recovery poller")
    void recoveryPoller() throws Exception {
        assertEquals(0, leakedLoops(e -> e.startRecoveryPoller(Map.of(), Duration.ofSeconds(30)),
                WorkflowExecutor::isRecoveryPollerRunning));
    }

    @Test
    @DisplayName("signal janitor")
    void signalJanitor() throws Exception {
        assertEquals(0, leakedLoops(e -> e.startSignalJanitor(Duration.ofSeconds(30), Duration.ofDays(1)),
                WorkflowExecutor::isSignalJanitorRunning));
    }

    private interface Starter {
        void start(WorkflowExecutor executor);
    }

    private interface RunningProbe {
        boolean running(WorkflowExecutor executor);
    }

    /** Races start against shutdown {@link #ROUNDS} times; returns how many left the loop running. */
    private static int leakedLoops(Starter starter, RunningProbe probe) throws Exception {
        var leaked = new AtomicInteger();
        for (var i = 0; i < ROUNDS; i++) {
            var executor = new WorkflowExecutor(new VersionedInMemoryStore(), null, null, null,
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
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            barrier.await();
            executor.shutdown();
            starting.join();
            if (probe.running(executor)) {
                leaked.incrementAndGet();
            }
        }
        return leaked.get();
    }
}
