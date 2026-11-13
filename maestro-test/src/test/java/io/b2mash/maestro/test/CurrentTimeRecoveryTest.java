package io.b2mash.maestro.test;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End to end through the kit: {@code currentTime()} hands workflow code the
 * same value live and after a crash and recovery, under a host mapper that
 * writes and reads timestamps as epoch milliseconds — both nanosecond
 * features off (WP1 D7, Task 4). A mapper that writes millis
 * ({@code WRITE_DATES_AS_TIMESTAMPS} on) but leaves
 * {@code READ_DATE_TIMESTAMPS_AS_NANOSECONDS} on reads its own writes as
 * seconds: the live run keeps the clock value (with a WARN) and a replay gets
 * the misread instant, so live and replay differ under it
 * ({@code CurrentTimeReadBackTest.asymmetricMillisMapper_*} in
 * {@code maestro-core}).
 *
 * <p>Before the fix the live call returned the clock's nanoseconds while the
 * replay read the stored milliseconds. A later activity that took the time
 * as text was therefore recorded with one argument fingerprint and replayed
 * with another, and the unchanged workflow stood down as
 * {@code nondeterministic_history} after the crash. An activity that took
 * the {@code Instant} itself never stood down — its fingerprint hashes the
 * serialized argument, milliseconds on both runs — but it received the
 * nanosecond value live, one no replay can reproduce.
 */
class CurrentTimeRecoveryTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00.123456789Z");
    private static final Instant STORED = Instant.parse("2026-11-12T10:00:00.123Z");

    private TestWorkflowEnvironment env;

    @BeforeEach
    void setUp() {
        Seen.clear();
        env = TestWorkflowEnvironment.create(JsonMapper.builder()
                .enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DateTimeFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
                .disable(DateTimeFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS)
                .build());
        env.getClock().setTime(NOW);
        env.registerActivities(Stamps.class, new StampsImpl());
    }

    @AfterEach
    void tearDown() {
        env.shutdown();
    }

    @Test
    void timeTakenAsText_completesAfterRecoveryWithoutStandingDown() throws Exception {
        var id = "ct-text";
        // Completions: label, stamp. The node dies after stamp's event, so
        // recovery replays currentTime(), label and stamp, and runs done() live.
        var crash = env.crashAfter(2);
        var handle = env.startWorkflow(id, TextWorkflow.class, id);
        crash.awaitCrash(Duration.ofSeconds(10));
        crash.recover();
        var status = settle(handle);

        assertEquals(List.of("COMPLETED", List.of(STORED, STORED), List.of(STORED.toString())),
                List.of(status, Seen.of(id), Seen.of(id + "#label")),
                "[status, currentTime() live then replayed, what label() received]");
        assertEquals(STORED.toString(), handle.getResult(String.class, Duration.ofSeconds(5)));
    }

    @Test
    void timeTakenAsAnInstant_reachesTheActivityAsTheValueReplayReads() throws Exception {
        var id = "ct-instant";
        // One completion: stamp. The node dies after it, so recovery replays
        // currentTime() and stamp, and runs done() live.
        var crash = env.crashAfter(1);
        var handle = env.startWorkflow(id, InstantWorkflow.class, id);
        crash.awaitCrash(Duration.ofSeconds(10));
        crash.recover();
        var status = settle(handle);

        assertEquals(List.of("COMPLETED", List.of(STORED, STORED), List.of(STORED)),
                List.of(status, Seen.of(id), Seen.of(id + "#stamp")),
                "[status, currentTime() live then replayed, what stamp() received]");
    }

    /**
     * The terminal status, or the active status with the run's stand-downs
     * once one has been reported — whichever comes first, within ten seconds.
     */
    private static String settle(TestWorkflowHandle handle) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            var status = handle.getStatus();
            if (status.isTerminal()) {
                return status.name();
            }
            if (!handle.standDowns().isEmpty()) {
                return status + " (stood down: " + handle.standDowns() + ")";
            }
            Thread.sleep(20);
        }
        return handle.getStatus() + " (neither terminal nor stood down after 10s)";
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    @Activity
    public interface Stamps {
        String label(String workflowId, String at);

        String stamp(String workflowId, Instant at);

        String done(String workflowId);
    }

    public static final class StampsImpl implements Stamps {
        @Override
        public String label(String workflowId, String at) {
            Seen.record(workflowId + "#label", at);
            return "labelled";
        }

        @Override
        public String stamp(String workflowId, Instant at) {
            Seen.record(workflowId + "#stamp", at);
            return "stamped";
        }

        @Override
        public String done(String workflowId) {
            return "done";
        }
    }

    /** Passes {@code currentTime()} as text to {@code label}, then as an {@code Instant} to {@code stamp}. */
    @DurableWorkflow(name = "current-time-text-workflow")
    public static class TextWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private Stamps stamps;

        @WorkflowMethod
        public String run(String id) {
            var now = WorkflowContext.current().currentTime();
            Seen.record(id, now);
            stamps.label(id, now.toString());
            stamps.stamp(id, now);
            stamps.done(id);
            return now.toString();
        }
    }

    /** Passes {@code currentTime()} as an {@code Instant} to {@code stamp}. */
    @DurableWorkflow(name = "current-time-instant-workflow")
    public static class InstantWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private Stamps stamps;

        @WorkflowMethod
        public String run(String id) {
            var now = WorkflowContext.current().currentTime();
            Seen.record(id, now);
            stamps.stamp(id, now);
            stamps.done(id);
            return now.toString();
        }
    }

    /** What workflow and activity code saw, in order, across both nodes. */
    static final class Seen {
        private static final ConcurrentHashMap<String, List<Object>> SEEN = new ConcurrentHashMap<>();

        static void record(String key, Object value) {
            SEEN.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>())).add(value);
        }

        static List<Object> of(String key) {
            var values = SEEN.get(key);
            return values == null ? List.of() : new ArrayList<>(values);
        }

        static void clear() {
            SEEN.clear();
        }
    }
}
