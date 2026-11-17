package io.b2mash.maestro.spring.client;

import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.exception.SerializationException;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.NullNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link WorkflowHandle#result()} keeps its documented contract — empty "when
 * it completed without a value" — for every result type, including
 * {@code Optional} and {@code JsonNode}: a null output (SQL {@code NULL} on a
 * JDBC store, {@link NullNode} in memory) is no value, decided on the stored
 * node before anything is deserialized (WP1 Task 5 ruling).
 */
class WorkflowHandleNullResultTest {

    private InMemoryWorkflowStore store;
    private PayloadSerializer serializer;
    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowStore();
        serializer = new PayloadSerializer(JsonMapper.builder().build());
        executor = WorkflowExecutor.builder(store, serializer, "svc").build();
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(classes = {Optional.class, JsonNode.class, String.class, Integer.class, int.class, Object.class})
    void aCompletedRunWithoutAValueHasAnEmptyResultForEveryResultType(Class<?> resultType) {
        var outcomes = new ArrayList<String>();
        for (var output : new JsonNode[]{null, NullNode.instance}) {
            var id = "no-value-" + resultType.getSimpleName() + "-" + (output == null ? "sql-null" : "null-node");
            store.createInstance(completed(id, output));
            outcomes.add(outcome(new WorkflowHandle<>(executor, store, serializer, id, resultType)));
        }
        assertEquals(List.of("Optional.empty", "Optional.empty"), outcomes, resultType.getName());
    }

    /** {@code result()} rendered, or the failure's class. */
    private static String outcome(WorkflowHandle<?> handle) {
        try {
            return String.valueOf(handle.result());
        } catch (SerializationException e) {
            return "throws " + e.getCause().getClass().getName();
        }
    }

    private static WorkflowInstance completed(String workflowId, @Nullable JsonNode output) {
        var now = Instant.parse("2026-11-12T10:00:00Z");
        return WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId(workflowId)
                .runId(UUID.randomUUID())
                .workflowType("NoValueWorkflow")
                .taskQueue("default")
                .status(WorkflowStatus.COMPLETED)
                .output(output)
                .serviceName("svc")
                .eventSequence(0)
                .startedAt(now)
                .completedAt(now)
                .updatedAt(now)
                .version(0)
                .build();
    }
}
