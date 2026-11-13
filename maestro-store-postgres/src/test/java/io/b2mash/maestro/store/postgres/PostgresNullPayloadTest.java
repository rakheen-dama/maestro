package io.b2mash.maestro.store.postgres;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.engine.ActivityProxyFactory;
import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.engine.WorkflowRegistration;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code Optional.empty()} as a workflow input, an activity result, a signal
 * payload and a workflow output, on a real {@code jsonb} store: what each
 * column holds, and that the live run and a recovery on a second node read the
 * same value (WP1 Task 5, fix round 1).
 */
@DisplayName("Optional.empty() through Postgres: stored as SQL NULL, read alike live and after recovery")
class PostgresNullPayloadTest extends PostgresTestSupport {

    private static final String SERVICE = "pg-null-svc";
    private static final Duration BOUND = Duration.ofSeconds(15);

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
    @Timeout(60)
    void optionalEmptyIsStoredAsSqlNullAndReadsAsEmptyLiveAndAfterRecovery() throws Exception {
        var id = "pg-optional-empty";
        var seen = Collections.synchronizedList(new ArrayList<@Nullable Object>());
        var method = OptionalEverywhere.class.getMethod("run", Optional.class);

        nodeA = WorkflowExecutor.builder(store, serializer, SERVICE).build();
        nodeA.startWorkflow(id, "OptionalEverywhere", "default", Optional.empty(),
                () -> new OptionalEverywhere(seen, store, serializer), method);
        nodeA.deliverSignal(id, OptionalEverywhere.SIGNAL, Optional.empty());
        await().atMost(BOUND).until(() -> seen.size() >= 3);

        // What the columns hold once the live run is parked on "go".
        var instanceId = store.getInstance(id).orElseThrow().id();
        assertEquals(Map.of("input", "SQL NULL"), columns(
                "SELECT 'input' AS k, input::text AS v FROM maestro_workflow_instance WHERE workflow_id = ?", id));
        assertEquals(Map.of("Nothing.none", "SQL NULL", "$maestro:awaitSignal:sig", "SQL NULL"), columns(
                "SELECT step_name AS k, payload::text AS v FROM maestro_workflow_event "
                        + "WHERE workflow_instance_id = ?::uuid AND event_type IN ('ACTIVITY_COMPLETED', 'SIGNAL_RECEIVED')",
                instanceId.toString()));
        assertEquals(Map.of("sig", "SQL NULL"), columns(
                "SELECT signal_name AS k, payload::text AS v FROM maestro_workflow_signal WHERE workflow_id = ?", id));

        // Node A stops while parked; node B adopts the run, re-reads the input and replays both reads.
        nodeA.shutdown();
        nodeA = null;
        nodeB = WorkflowExecutor.builder(store, serializer, SERVICE).build();
        nodeB.recoverWorkflows(Map.of("OptionalEverywhere", new WorkflowRegistration("OptionalEverywhere",
                "default", () -> new OptionalEverywhere(seen, store, serializer), method)));
        await().atMost(BOUND).until(() -> seen.size() >= 6);

        // [input, activity result, signal] live, then the same three after recovery.
        var empty = Optional.empty();
        assertEquals(List.of(empty, empty, empty, empty, empty, empty), new ArrayList<>(seen));

        nodeB.deliverSignal(id, OptionalEverywhere.GO, "go");
        await().atMost(BOUND).until(() -> store.getInstance(id).map(i -> i.status().isTerminal()).orElse(false));
        var instance = store.getInstance(id).orElseThrow();
        assertEquals(WorkflowStatus.COMPLETED, instance.status());
        assertEquals(Map.of("output", "SQL NULL"), columns(
                "SELECT 'output' AS k, output::text AS v FROM maestro_workflow_instance WHERE workflow_id = ?", id));
        assertEquals(Optional.empty(), serializer.deserialize(instance.output(), Optional.class));
    }

    /** Each row's {@code k} to its {@code v}, with SQL {@code NULL} spelled out. */
    private Map<String, String> columns(String sql, String param) throws SQLException {
        var out = new LinkedHashMap<String, String>();
        try (var conn = dataSource.getConnection(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    var v = rs.getString("v");
                    out.put(rs.getString("k"), v == null ? "SQL NULL" : "jsonb " + v);
                }
            }
        }
        return out;
    }

    @Activity(name = "Nothing")
    public interface Nothing {
        Optional<String> none();
    }

    /** Records the input, an activity's empty result and an empty signal, then parks until "go". */
    @DurableWorkflow(name = "OptionalEverywhere")
    public static class OptionalEverywhere {

        static final String SIGNAL = "sig";
        static final String GO = "go";

        private final List<@Nullable Object> seen;
        private final Nothing nothing;

        OptionalEverywhere(List<@Nullable Object> seen, WorkflowStore store, PayloadSerializer serializer) {
            this.seen = seen;
            this.nothing = new ActivityProxyFactory().createProxy(Nothing.class, Optional::empty, store, null, null,
                    RetryPolicy.noRetry(), Duration.ofSeconds(10), serializer, new RetryExecutor(), "maestro:lock:");
        }

        /**
         * @param in the input
         * @return an empty result
         */
        @WorkflowMethod
        public Optional<String> run(Optional<String> in) {
            seen.add(in);
            seen.add(nothing.none());
            seen.add(WorkflowContext.current().awaitSignal(SIGNAL, Optional.class, Duration.ofSeconds(30)));
            WorkflowContext.current().awaitSignal(GO, String.class, Duration.ofSeconds(30));
            return Optional.empty();
        }
    }
}
