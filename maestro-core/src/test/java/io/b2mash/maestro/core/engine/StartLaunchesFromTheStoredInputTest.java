package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.test.InMemoryDistributedLock;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Issue 43: a fresh start launches its first run from the input
 * {@link io.b2mash.maestro.core.spi.WorkflowStore#createInstance} handed back
 * — the input as the store holds it, which is what every recovery reads — not
 * from the node the engine sent. A store that returns {@code null} (a stub)
 * still launches, from the node sent.
 */
@DisplayName("A fresh start launches from the stored input")
class StartLaunchesFromTheStoredInputTest {

    private static final Duration BOUND = Duration.ofSeconds(10);
    private static final List<String> SEEN = new CopyOnWriteArrayList<>();

    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private @Nullable WorkflowExecutor executor;

    @AfterEach
    void tearDown() {
        SEEN.clear();
        if (executor != null) executor.shutdown();
    }

    @Test
    @Timeout(30)
    void theFirstRunReadsTheInputTheStoreReturned() throws Exception {
        // Mimics jsonb: the store keeps the input's keys in another order.
        var store = new ReturningStore(instance -> {
            var reordered = JsonMapper.builder().build().createObjectNode();
            var sent = (ObjectNode) instance.input();
            reordered.set("b", sent.get("b"));
            reordered.set("aa", sent.get("aa"));
            return instance.toBuilder().input(reordered).build();
        });

        start(store);

        await().atMost(BOUND).until(() -> !SEEN.isEmpty());
        assertEquals(List.of("[b, aa]"), SEEN, "the store's order, as a recovery would read it");
    }

    @Test
    @Timeout(30)
    void aStoreReturningNullFromCreateInstanceStillLaunchesFromTheSentInput() throws Exception {
        start(new ReturningStore(instance -> null));

        await().atMost(BOUND).until(() -> !SEEN.isEmpty());
        assertEquals(List.of("[aa, b]"), SEEN);
    }

    private void start(VersionedInMemoryStore store) throws Exception {
        executor = WorkflowExecutor.builder(store, serializer, "stored-input-svc")
                .distributedLock(new InMemoryDistributedLock()).build();
        var input = new LinkedHashMap<String, Object>();
        input.put("aa", 1);
        input.put("b", 2);
        executor.startWorkflow("stored-input", "KeyOrderWorkflow", "default", input,
                KeyOrderWorkflow::new, KeyOrderWorkflow.class.getMethod("run", Map.class));
    }

    /** A store whose {@code createInstance} persists as usual but returns what {@code returned} makes of it. */
    private static final class ReturningStore extends VersionedInMemoryStore {
        private final UnaryOperator<@Nullable WorkflowInstance> returned;

        ReturningStore(UnaryOperator<@Nullable WorkflowInstance> returned) {
            this.returned = returned;
        }

        @Override
        @SuppressWarnings("NullAway")
        public WorkflowInstance createInstance(WorkflowInstance instance) {
            return returned.apply(super.createInstance(instance));
        }
    }

    /** Records its input's key order. */
    @DurableWorkflow(name = "KeyOrderWorkflow")
    public static class KeyOrderWorkflow {
        /** @return the key order */
        @WorkflowMethod
        public String run(Map<String, Object> input) {
            var seen = input.keySet().toString();
            SEEN.add(seen);
            return seen;
        }
    }
}
