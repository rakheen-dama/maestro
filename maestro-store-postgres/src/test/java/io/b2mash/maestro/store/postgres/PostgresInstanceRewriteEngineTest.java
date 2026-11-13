package io.b2mash.maestro.store.postgres;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.engine.WorkflowRegistration;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The engine rewrites a workflow's stored {@code input} on every instance
 * status write — the park ({@code InstanceStatusWriter}), the adoption
 * compare-and-set on recovery, the retry compare-and-set and the completion —
 * from the tree the store parsed (WP1 Task 6, fix round 1). On real Postgres:
 * the stored input keeps its decimals' digits and scale through all of them,
 * and an input an older build already rounded replays as the rounded values.
 */
@DisplayName("Instance rewrites through the engine keep a stored input's decimals")
class PostgresInstanceRewriteEngineTest extends PostgresTestSupport {

    private static final String SERVICE = "pg-rewrite-svc";
    private static final Duration BOUND = Duration.ofSeconds(15);
    private static final String EXACT_INPUT = "{\"big\": 12345678901234567890.123456789, \"amount\": 10.50}";

    private PayloadSerializer serializer;
    private WorkflowExecutor nodeA;
    private WorkflowExecutor nodeB;

    @BeforeEach
    void createSerializer() {
        serializer = new PayloadSerializer(objectMapper);
    }

    @AfterEach
    void stopNodes() {
        if (nodeA != null) nodeA.shutdown();
        if (nodeB != null) nodeB.shutdown();
    }

    @Test
    @Timeout(90)
    @DisplayName("park, adoption, retry and completion keep the input's digits and scale")
    void parkAdoptionRetryAndCompletionKeepTheInputExact() throws Exception {
        var id = "pg-rewrite-exact";
        var seen = Collections.synchronizedList(new ArrayList<PostgresPayloadFidelityTest.Order>());
        var failuresLeft = new AtomicInteger(1);
        var registration = registration(seen, failuresLeft);
        var facts = new ArrayList<String>();

        // Live start, then the park on "go" rewrites the instance (InstanceStatusWriter).
        nodeA = WorkflowExecutor.builder(store, serializer, SERVICE).build();
        nodeA.startWorkflow(id, Priced.TYPE, "default", new PostgresPayloadFidelityTest.Order(
                new BigDecimal("10.50"), new BigDecimal("12345678901234567890.123456789")),
                registration.workflowFactory(), registration.workflowMethod());
        awaitStatus(id, WorkflowStatus.WAITING_SIGNAL);
        facts.add("after park: " + inputText(id));

        // Node A stops; node B adopts the run (the adoption CAS rewrites the row) and replays the input.
        nodeA.shutdown();
        nodeA = null;
        nodeB = WorkflowExecutor.builder(store, serializer, SERVICE).build();
        nodeB.recoverWorkflows(Map.of(Priced.TYPE, registration));
        await().atMost(BOUND).until(() -> seen.size() >= 2);
        awaitStatus(id, WorkflowStatus.WAITING_SIGNAL);
        facts.add("after adoption: " + inputText(id));

        // "go" makes the run fail once; the retry CAS rewrites the row, the re-run replays and completes.
        nodeB.deliverSignal(id, Priced.GO, "go");
        awaitStatus(id, WorkflowStatus.FAILED);
        facts.add("after failure: " + inputText(id));
        facts.add("retry: " + nodeB.retryWorkflow(id, registration));
        awaitStatus(id, WorkflowStatus.COMPLETED);
        facts.add("after retry and completion: " + inputText(id));
        facts.add("output: " + outputText(id));
        facts.add("typed output: " + describe(serializer.deserialize(
                store.getInstance(id).orElseThrow().output(), PostgresPayloadFidelityTest.Order.class)));
        facts.add("inputs seen: " + seen.stream().map(PostgresInstanceRewriteEngineTest::describe).toList());

        var exactOrder = "amount=BigDecimal 10.50 (scale 2), big=BigDecimal 12345678901234567890.123456789 (scale 9)";
        assertEquals(List.of(
                "after park: " + EXACT_INPUT,
                "after adoption: " + EXACT_INPUT,
                "after failure: " + EXACT_INPUT,
                "retry: RETRIED",
                "after retry and completion: " + EXACT_INPUT,
                "output: " + EXACT_INPUT,
                "typed output: " + exactOrder,
                "inputs seen: " + List.of(exactOrder, exactOrder, exactOrder)), facts);
    }

    @Test
    @Timeout(90)
    @DisplayName("an input an old build already rounded replays as the rounded values")
    void anInputAnOldBuildAlreadyRoundedReplaysAsBefore() throws Exception {
        var id = "pg-rewrite-damaged";
        var seen = Collections.synchronizedList(new ArrayList<PostgresPayloadFidelityTest.Order>());
        var registration = registration(seen, new AtomicInteger(0));

        nodeA = WorkflowExecutor.builder(store, serializer, SERVICE).build();
        nodeA.startWorkflow(id, Priced.TYPE, "default", new PostgresPayloadFidelityTest.Order(
                new BigDecimal("10.50"), new BigDecimal("12345678901234567890.123456789")),
                registration.workflowFactory(), registration.workflowMethod());
        awaitStatus(id, WorkflowStatus.WAITING_SIGNAL);
        nodeA.shutdown();
        nodeA = null;

        // The row as an older build leaves it after any status write.
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "UPDATE maestro_workflow_instance SET input = ?::jsonb WHERE workflow_id = ?")) {
            ps.setString(1, PostgresPayloadFidelityTest.LOSSY_ORDER_INPUT);
            ps.setString(2, id);
            assertEquals(1, ps.executeUpdate());
        }

        nodeB = WorkflowExecutor.builder(store, serializer, SERVICE).build();
        nodeB.recoverWorkflows(Map.of(Priced.TYPE, registration));
        await().atMost(BOUND).until(() -> seen.size() >= 2);
        nodeB.deliverSignal(id, Priced.GO, "go");
        awaitStatus(id, WorkflowStatus.COMPLETED);

        var rounded = "amount=BigDecimal 10.5 (scale 1), big=BigDecimal 12345678901234567000 (scale 0)";
        assertEquals(List.of(
                        "replayed input: " + rounded,
                        "input: " + PostgresPayloadFidelityTest.LOSSY_ORDER_INPUT,
                        "output: " + PostgresPayloadFidelityTest.LOSSY_ORDER_INPUT),
                List.of(
                        "replayed input: " + describe(seen.get(1)),
                        "input: " + inputText(id),
                        "output: " + outputText(id)));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private WorkflowRegistration registration(List<PostgresPayloadFidelityTest.Order> seen, AtomicInteger failuresLeft)
            throws NoSuchMethodException {
        return new WorkflowRegistration(Priced.TYPE, "default", () -> new Priced(seen, failuresLeft),
                Priced.class.getMethod("run", PostgresPayloadFidelityTest.Order.class));
    }

    private void awaitStatus(String id, WorkflowStatus status) {
        await().atMost(BOUND).until(() -> store.getInstance(id).map(i -> i.status() == status).orElse(false));
    }

    private String inputText(String id) throws SQLException {
        return columnText("input", id);
    }

    private String outputText(String id) throws SQLException {
        return columnText("output", id);
    }

    private String columnText(String column, String id) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("SELECT " + column + "::text FROM maestro_workflow_instance "
                     + "WHERE workflow_id = ?")) {
            ps.setString(1, id);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "no row";
            }
        }
    }

    private static String describe(PostgresPayloadFidelityTest.Order order) {
        return "amount=" + describe(order.amount()) + ", big=" + describe(order.big());
    }

    private static String describe(BigDecimal d) {
        return "BigDecimal " + d + " (scale " + d.scale() + ")";
    }

    /** Records its input, parks on "go", fails while {@code failuresLeft} is positive, then returns its input. */
    @DurableWorkflow(name = Priced.TYPE)
    public static class Priced {

        static final String TYPE = "Priced";
        static final String GO = "go";

        private final List<PostgresPayloadFidelityTest.Order> seen;
        private final AtomicInteger failuresLeft;

        Priced(List<PostgresPayloadFidelityTest.Order> seen, AtomicInteger failuresLeft) {
            this.seen = seen;
            this.failuresLeft = failuresLeft;
        }

        /**
         * @param in the input
         * @return the input
         */
        @WorkflowMethod
        public PostgresPayloadFidelityTest.Order run(PostgresPayloadFidelityTest.Order in) {
            seen.add(in);
            WorkflowContext.current().awaitSignal(GO, String.class, Duration.ofSeconds(60));
            if (failuresLeft.getAndDecrement() > 0) {
                throw new IllegalStateException("fails once");
            }
            return in;
        }
    }
}
