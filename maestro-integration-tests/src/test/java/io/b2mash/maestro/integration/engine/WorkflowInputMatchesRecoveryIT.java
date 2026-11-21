package io.b2mash.maestro.integration.engine;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.support.RecordingObserver;
import io.b2mash.maestro.integration.support.WorkflowHandle;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue 43 on real PostgreSQL: the first run receives the workflow input as
 * the store holds it, exactly as a recovered run reads it. {@code jsonb}
 * reorders object keys (shortest first) and rewrites a whole exponent number
 * ({@code 1.0E7} becomes {@code 10000000}); before the fix the first run read
 * the node the engine sent, so a {@code Map<String, Object>} input iterated
 * in insertion order and an untyped {@code 1.0E7} was a {@code Double} — and
 * after a recovery, jsonb's order and an {@code Integer}. Now the first run
 * launches from the input {@code INSERT … RETURNING input} handed back.
 *
 * <p>Node A runs the first run, which records what it saw (key order and the
 * number's class) and passes that text to an activity, then parks; node A
 * closes. Node B recovers the run — replaying the activity, whose argument
 * fingerprint is re-checked — then a signal completes it. Both runs must have
 * seen the same thing, and the run must not stand down.
 */
@Tag("integration")
@DisplayName("The first run's workflow input equals a recovered run's on Postgres")
class WorkflowInputMatchesRecoveryIT extends PostgresIntegrationSupport {

    private static final String SERVICE = "input-matches-recovery";
    private static final Duration BOUND = Duration.ofSeconds(30);
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);

    @BeforeEach
    void clearSeen() {
        Seen.clear();
    }

    @Test
    void theFirstRunSeesTheStoredInput_asTheRecoveredRunDoes() throws Exception {
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("input-recovery");
        // Insertion order differs from jsonb's (shorter keys first), and an
        // untyped exponent number jsonb stores as an integer.
        var input = new LinkedHashMap<String, Object>();
        input.put("aa", 1);
        input.put("b", 2);
        input.put("n", 1.0E7);

        var parkedA = new CountDownLatch(1);
        WorkflowHandle handle;
        try (var nodeA = node(parkedA, null)) {
            handle = nodeA.start(workflowId, InputWorkflow.class, input);
            assertTrue(parkedA.await(30, TimeUnit.SECONDS), "node A must reach the await point");
            handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
        }

        var observer = new RecordingObserver();
        var parkedB = new CountDownLatch(1);
        String outcome;
        try (var nodeB = node(parkedB, observer)) {
            assertEquals(1, nodeB.recover());
            var deadline = System.nanoTime() + BOUND.toNanos();
            while (parkedB.getCount() > 0 && observer.standDowns().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            if (!observer.standDowns().isEmpty()) {
                outcome = handle.status() + " (stood down: " + observer.standDowns() + ")";
            } else {
                nodeB.deliverSignal(workflowId, InputWorkflow.SIGNAL, "go");
                outcome = handle.awaitTerminal(BOUND).name();
            }
        }

        var seen = Seen.of(workflowId);                       // [first run, recovered run]
        assertEquals(2, seen.size(), "seen=" + seen + " outcome=" + outcome);
        assertAll(
                () -> assertEquals("COMPLETED", outcome),
                () -> assertEquals("[b, n, aa] n=Integer", seen.get(1), "the recovered run reads jsonb's form"),
                () -> assertEquals(seen.get(1), seen.get(0), "the first run reads the same"));
    }

    @Test
    void anInputJsonbStoresInAFormThatCannotBeReadBackFailsTheStart_nothingPersisted() throws Exception {
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("input-unparseable");
        // Readable as sent ("1E+1500"), but jsonb stores a 1501-digit integer
        // the parser refuses, so no recovery could ever load the row.
        var input = new LinkedHashMap<String, Object>();
        input.put("x", new java.math.BigDecimal("1E+1500"));

        try (var node = node(new CountDownLatch(1), null)) {
            var thrown = org.junit.jupiter.api.Assertions.assertThrows(
                    io.b2mash.maestro.core.exception.UnreadableWorkflowInputException.class,
                    () -> node.start(workflowId, InputWorkflow.class, input));
            assertEquals("input-matches-recovery", thrown.workflowType());
            assertTrue(store.getInstance(workflowId).isEmpty(), "the insert was rolled back");

            // The lock was released and the id is not burnt.
            var ok = new LinkedHashMap<String, Object>();
            ok.put("n", 1);
            node.start(workflowId, InputWorkflow.class, ok).awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
        }
    }

    private MaestroEngineHarness node(CountDownLatch parked, @Nullable RecordingObserver observer) {
        var builder = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName(SERVICE)
                .lock(newLock())
                .instanceLockTtl(LOCK_TTL);
        if (observer != null) {
            builder.observer(observer);
        }
        var node = builder.build();
        node.registerActivities(Sink.class, new SinkImpl());
        node.registerWorkflow(InputWorkflow.class, () -> new InputWorkflow(parked));
        return node;
    }

    // ── fixture ───────────────────────────────────────────────────────────

    @Activity
    public interface Sink {
        String take(String value);
    }

    public static final class SinkImpl implements Sink {
        @Override
        public String take(String value) {
            return "took";
        }
    }

    @DurableWorkflow(name = "input-matches-recovery")
    public static class InputWorkflow {

        static final String SIGNAL = "go";

        @ActivityStub(startToCloseTimeout = "PT10S")
        Sink sink;

        private final CountDownLatch parked;

        public InputWorkflow(CountDownLatch parked) {
            this.parked = parked;
        }

        @WorkflowMethod
        public String run(Map<String, Object> input) {
            var seen = input.keySet() + " n=" + input.get("n").getClass().getSimpleName();
            Seen.record(WorkflowContext.current().workflowId(), seen);
            // What the run saw becomes an activity argument, so a first run
            // that saw something else than its recovery changes the recorded
            // fingerprint and the recovery stands down.
            sink.take(seen);
            parked.countDown();
            WorkflowContext.current().awaitSignal(SIGNAL, String.class, Duration.ofSeconds(60));
            return "done";
        }
    }

    /** What workflow code saw, per workflow id, in arrival order (first run, then the recovery). */
    static final class Seen {
        private static final ConcurrentHashMap<String, List<String>> SEEN = new ConcurrentHashMap<>();

        static void record(String workflowId, String value) {
            SEEN.computeIfAbsent(workflowId, k -> Collections.synchronizedList(new ArrayList<>())).add(value);
        }

        static List<String> of(String workflowId) {
            var values = SEEN.get(workflowId);
            return values == null ? List.of() : new ArrayList<>(values);
        }

        static void clear() {
            SEEN.clear();
        }
    }
}
