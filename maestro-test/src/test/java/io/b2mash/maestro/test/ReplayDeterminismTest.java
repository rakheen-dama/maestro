package io.b2mash.maestro.test;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.StandDownReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replay re-derives every decision from the code: an activity asked a different
 * question, or a step that moved, is a {@code NONDETERMINISTIC_HISTORY}
 * stand-down at recovery. These tests make the kit show both before a deploy.
 */
class ReplayDeterminismTest {

    private static final List<String> SKUS = List.of("sku-1", "sku-17", "sku-33", "sku-2", "sku-49");

    /** Where {@link KeyOrderWorkflow} reads its map — swapped by the test between crash and recovery. */
    static volatile Supplier<Map<String, Integer>> stock = () -> stockMap(4);

    private TestWorkflowEnvironment env;
    private RecordingSteps steps;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.create();
        steps = new RecordingSteps();
        env.registerActivities(Steps.class, steps);
    }

    @AfterEach
    void tearDown() {
        stock = () -> stockMap(4);
        env.shutdown();
    }

    @Test
    void anArgumentBuiltFromHashMapIterationOrder_standsDownWhenTheOrderDiffersAtRecovery() throws Exception {
        var before = String.join(",", stockMap(4).keySet());
        var after = String.join(",", stockMap(1024).keySet());
        assertEquals(stockMap(4), stockMap(1024), "the two maps are equal");
        assertNotEquals(before, after, "but iterate in different orders");

        stock = () -> stockMap(4);
        var crash = env.crashAfter(1);
        var handle = env.startWorkflow(KeyOrderWorkflow.class, null);
        crash.awaitCrash(Duration.ofSeconds(10));

        stock = () -> stockMap(1024);   // the recovering node built its map differently
        crash.recover();

        var standDown = handle.awaitStandDown(Duration.ofSeconds(10));
        assertEquals(StandDownReason.NONDETERMINISTIC_HISTORY, standDown.reason());
        assertNotNull(standDown.detail());
        assertTrue(standDown.detail().contains("argsHash"), standDown.detail());
        assertTrue(handle.getStatus().isActive());
        assertEquals(List.of("reserve:" + before), steps.calls,
                "the replay refused to hand the recorded answer to a different question");
    }

    @Test
    void aBareHashMapArgumentDoesNotDrift_theFingerprintSortsKeys() throws Exception {
        stock = () -> stockMap(4);
        var crash = env.crashAfter(1);
        var handle = env.startWorkflow(MapArgumentWorkflow.class, null);
        crash.awaitCrash(Duration.ofSeconds(10));

        stock = () -> stockMap(1024);
        crash.recover();

        assertEquals("done", handle.getResult(String.class, Duration.ofSeconds(10)));
        assertTrue(handle.standDowns().isEmpty());
    }

    @Test
    void recoveringOntoUngatedNewCode_standsDown() throws Exception {
        var crash = env.crashAfter(2);
        var handle = env.startWorkflow(EvolvingV1.class, "x");

        crash.recover(UngatedV2.class);

        var standDown = handle.awaitStandDown(Duration.ofSeconds(10));
        assertEquals(StandDownReason.NONDETERMINISTIC_HISTORY, standDown.reason());
        assertEquals(WorkflowStatus.RUNNING, handle.getStatus());
    }

    @Test
    void recoveringOntoVersionGatedNewCode_completesOnTheOldBranch() throws Exception {
        var crash = env.crashAfter(2);
        var handle = env.startWorkflow(EvolvingV1.class, "x");

        crash.recover(GatedV2.class);

        assertEquals("v1-path", handle.getResult(String.class, Duration.ofSeconds(10)));
        assertTrue(handle.standDowns().isEmpty());
        assertEquals(List.of("first:x", "second:x", "third:x"), steps.calls,
                "the pre-change history replays without the new step");

        // A run started after the deploy takes the new branch.
        var fresh = env.startWorkflow(GatedV2.class, "y");
        assertEquals("v2-path", fresh.getResult(String.class, Duration.ofSeconds(10)));
    }

    private static Map<String, Integer> stockMap(int initialCapacity) {
        var map = new HashMap<String, Integer>(initialCapacity);
        for (var sku : SKUS) {
            map.put(sku, 1);
        }
        return map;
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    @Activity
    public interface Steps {
        void reserve(String skus);

        void reserveAll(Map<String, Integer> stock);

        void first(String in);

        void second(String in);

        void inserted(String in);

        void third(String in);
    }

    static final class RecordingSteps implements Steps {
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public void reserve(String skus) {
            calls.add("reserve:" + skus);
        }

        @Override
        public void reserveAll(Map<String, Integer> stock) {
            calls.add("reserveAll");
        }

        @Override
        public void first(String in) {
            calls.add("first:" + in);
        }

        @Override
        public void second(String in) {
            calls.add("second:" + in);
        }

        @Override
        public void inserted(String in) {
            calls.add("inserted:" + in);
        }

        @Override
        public void third(String in) {
            calls.add("third:" + in);
        }
    }

    /** Joins a {@code HashMap}'s keys into an activity argument — order-dependent. */
    @DurableWorkflow(name = "key-order")
    public static class KeyOrderWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private Steps steps;

        @WorkflowMethod
        public String run() {
            var skus = String.join(",", stock.get().keySet());
            steps.reserve(skus);
            steps.reserve(skus);
            return "done";
        }
    }

    /** Passes the map itself — the fingerprint sorts object keys, so order does not matter. */
    @DurableWorkflow(name = "map-argument")
    public static class MapArgumentWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private Steps steps;

        @WorkflowMethod
        public String run() {
            steps.reserveAll(stock.get());
            steps.reserveAll(stock.get());
            return "done";
        }
    }

    @DurableWorkflow(name = "evolving")
    public static class EvolvingV1 {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private Steps steps;

        @WorkflowMethod
        public String run(String in) {
            steps.first(in);
            steps.second(in);
            steps.third(in);
            return "v1-path";
        }
    }

    /** Inserts a step without a version gate: replay finds {@code second} where it now calls {@code inserted}. */
    @DurableWorkflow(name = "evolving")
    public static class UngatedV2 {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private Steps steps;

        @WorkflowMethod
        public String run(String in) {
            steps.first(in);
            steps.inserted(in);
            steps.second(in);
            steps.third(in);
            return "v2-path";
        }
    }

    /** The same change behind {@code workflow.version()}: pre-change histories keep the old path. */
    @DurableWorkflow(name = "evolving")
    public static class GatedV2 {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private Steps steps;

        @WorkflowMethod
        public String run(String in) {
            steps.first(in);
            var version = WorkflowContext.current().version("insert-step", WorkflowContext.DEFAULT_VERSION, 1);
            if (version >= 1) {
                steps.inserted(in);
            }
            steps.second(in);
            steps.third(in);
            return version >= 1 ? "v2-path" : "v1-path";
        }
    }
}
