package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A signal delivered with a {@code null} payload and awaited as a primitive
 * class ({@code int.class}) reads as Java {@code null} — on the live consume of
 * the signal row and on the replay of its {@code SIGNAL_RECEIVED} memo — and
 * never stands the run down (WP1 Task 5 ruling: outside the {@code Optional}
 * family a JSON null reads as Java {@code null}, exactly as before Task 5).
 *
 * <p>{@code collectSignals} is not exercised: it returns {@code List.copyOf}
 * of what it awaited, which throws {@code NullPointerException} on a null
 * element — before Task 5 and after it alike.
 */
class NullSignalPayloadTest {

    private static final String SERVICE = "null-signal-svc";
    private static final Duration BOUND = Duration.ofSeconds(10);

    private VersionedInMemoryStore store;
    private PayloadSerializer serializer;
    private RecordingEngineObserver observerA;
    private RecordingEngineObserver observerB;
    private WorkflowExecutor nodeA;
    private WorkflowExecutor nodeB;

    @BeforeEach
    void setUp() {
        store = new VersionedInMemoryStore();
        serializer = new PayloadSerializer(JsonMapper.builder().build());
        observerA = new RecordingEngineObserver();
        observerB = new RecordingEngineObserver();
        nodeA = WorkflowExecutor.builder(store, serializer, SERVICE).observer(observerA).build();
    }

    @AfterEach
    void tearDown() {
        if (nodeA != null) nodeA.shutdown();
        if (nodeB != null) nodeB.shutdown();
    }

    @Test
    @Timeout(30)
    void aNullPayloadAwaitedAsIntReadsAsJavaNull_liveAndOnReplay_withoutAStandDown() throws Exception {
        var id = "null-int-signal";
        var seen = Collections.synchronizedList(new ArrayList<@Nullable Object>());
        var method = NullIntSignalWorkflow.class.getMethod("run");

        nodeA.startWorkflow(id, "NullIntSignalWorkflow", "default", null,
                () -> new NullIntSignalWorkflow(seen), method);
        nodeA.deliverSignal(id, NullIntSignalWorkflow.AWAITED, null);

        // Live: the read reaches workflow code (it then parks on "go"), or the run stands down.
        await().atMost(BOUND).until(() -> !seen.isEmpty() || !observerA.standDowns().isEmpty());
        assertEquals(List.of(), observerA.standDowns(), "live stand-downs");
        assertEquals(Arrays.asList((Object) null), new ArrayList<>(seen), "live read");

        // Node A stops while parked on "go"; node B adopts the run and replays the SIGNAL_RECEIVED memo.
        nodeA.shutdown();
        nodeA = null;
        nodeB = WorkflowExecutor.builder(store, serializer, SERVICE).observer(observerB).build();
        nodeB.recoverWorkflows(Map.of("NullIntSignalWorkflow", new WorkflowRegistration(
                "NullIntSignalWorkflow", "default", () -> new NullIntSignalWorkflow(seen), method)));

        await().atMost(BOUND).until(() -> seen.size() >= 2 || !observerB.standDowns().isEmpty());
        assertEquals(List.of(), observerB.standDowns(), "replay stand-downs");
        assertEquals(Arrays.asList(null, null), new ArrayList<>(seen), "live read, then replayed read");

        nodeB.deliverSignal(id, NullIntSignalWorkflow.GO, "go");
        await().atMost(BOUND).until(() -> store.getInstance(id).map(i -> i.status().isTerminal()).orElse(false));
        var instance = store.getInstance(id).orElseThrow();
        assertEquals(WorkflowStatus.COMPLETED, instance.status());
        assertEquals("awaited=null", serializer.deserialize(instance.output(), String.class));
    }

    /** Awaits a signal as {@code int.class}, then parks until "go". */
    @DurableWorkflow(name = "NullIntSignalWorkflow")
    public static class NullIntSignalWorkflow {

        static final String AWAITED = "awaited";
        static final String GO = "go";

        private final List<@Nullable Object> seen;

        NullIntSignalWorkflow(List<@Nullable Object> seen) {
            this.seen = seen;
        }

        /** @return the read, rendered */
        @WorkflowMethod
        public String run() {
            var workflow = WorkflowContext.current();
            Integer awaited = workflow.awaitSignal(AWAITED, int.class, Duration.ofSeconds(30));
            seen.add(awaited);
            workflow.awaitSignal(GO, String.class, Duration.ofSeconds(30));
            return "awaited=" + awaited;
        }
    }
}
