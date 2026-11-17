package io.b2mash.maestro.store.postgres;

import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store halves of the housekeeping paths, against a real Postgres: the
 * signal janitor's bounded, service-scoped delete; and the guards that pin the
 * leading text of each housekeeping statement, which the load suite's
 * {@code perf/sql/pgss-window.sql} matches.
 */
class PostgresStoreHygieneTest extends PostgresTestSupport {

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private final Instant cutoff = now.minus(Duration.ofDays(7));

    /**
     * {@code orders}: 3 COMPLETED and 2 TERMINATED finished 10 days ago with
     * one unconsumed signal each, a FAILED finished 10 days ago and a
     * COMPLETED finished 1 day ago with one unconsumed signal each, and one
     * consumed signal on an old COMPLETED. {@code billing}: 3 COMPLETED and 2
     * TERMINATED finished 10 days ago with one unconsumed signal each. One
     * orphan signal. The cutoff is 7 days ago.
     */
    @Nested
    class ScopedJanitorDelete {

        private final String ordersFailed = "orders-failed";
        private final String ordersRecent = "orders-recent";
        private final String orphanWorkflowId = "orphan-wf";
        private UUID consumedSignalId;

        @BeforeEach
        void fixture() {
            for (var service : List.of("orders", "billing")) {
                for (int i = 0; i < 3; i++) {
                    withUnconsumedSignal(finished(service + "-c-" + i, service, WorkflowStatus.COMPLETED, 10));
                }
                for (int i = 0; i < 2; i++) {
                    withUnconsumedSignal(finished(service + "-t-" + i, service, WorkflowStatus.TERMINATED, 10));
                }
            }
            withUnconsumedSignal(finished(ordersFailed, "orders", WorkflowStatus.FAILED, 10));
            withUnconsumedSignal(finished(ordersRecent, "orders", WorkflowStatus.COMPLETED, 1));
            var old = store.getInstance("orders-c-0").orElseThrow();
            var consumed = newSignal(old.workflowId(), old.id());
            consumedSignalId = consumed.id();
            store.saveSignal(consumed);
            assertTrue(store.markSignalConsumed(consumedSignalId));
            store.saveSignal(newSignal(orphanWorkflowId, null));
        }

        @Test
        void deletesOnlyThisServicesQualifyingSignalsAtMostLimitPerCall() throws Exception {
            var deleted = IntStream.range(0, 4)
                    .map(i -> store.deleteUnconsumedSignalsForTerminalInstancesBefore("orders", cutoff, 2)).boxed().toList();
            assertEquals(List.of(2, 2, 1, 0), deleted);
            assertEquals(5, unconsumedOfService("billing"), "another service's signals are not this service's to delete");
            assertEquals(1, unconsumed(ordersFailed));
            assertEquals(1, unconsumed(ordersRecent));
            assertEquals(1, unconsumed(orphanWorkflowId));
            assertTrue(consumedRowStillExists(consumedSignalId));
        }

        @Test
        void rejectsANonPositiveLimit() {
            assertThrows(IllegalArgumentException.class,
                    () -> store.deleteUnconsumedSignalsForTerminalInstancesBefore("orders", cutoff, 0));
        }

        /** Guard: the load suite classifies the janitor by this leading text. */
        @Test
        void theJanitorStatementKeepsTheLeadingTextThePerfHarnessMatches() {
            var sql = new CopyOnWriteArrayList<String>();
            new PostgresWorkflowStore(recording(dataSource, sql), objectMapper)
                    .deleteUnconsumedSignalsForTerminalInstancesBefore("orders", cutoff, 2);
            var janitor = sql.stream().map(s -> s.replaceAll("\\s+", " ").strip())
                    .filter(s -> s.startsWith("DELETE FROM maestro_workflow_signal")).toList();
            assertEquals(1, janitor.size());
            assertTrue(janitor.getFirst().startsWith("DELETE FROM maestro_workflow_signal WHERE consumed = "),
                    janitor.getFirst());
        }

        private void withUnconsumedSignal(WorkflowInstance instance) {
            store.saveSignal(newSignal(instance.workflowId(), instance.id()));
        }

        private int unconsumed(String workflowId) {
            return store.getUnconsumedSignals(workflowId, "s").size();
        }

        private long unconsumedOfService(String serviceName) throws Exception {
            return count("SELECT count(*) FROM maestro_workflow_signal s JOIN maestro_workflow_instance i"
                    + " ON i.id = s.workflow_instance_id WHERE s.consumed = FALSE AND i.service_name = ?", serviceName);
        }

        private boolean consumedRowStillExists(UUID signalId) throws Exception {
            try (Connection conn = dataSource.getConnection();
                 var ps = conn.prepareStatement("SELECT consumed FROM maestro_workflow_signal WHERE id = ?")) {
                ps.setObject(1, signalId);
                try (var rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            }
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    /** Creates an instance of {@code serviceName} in {@code status}, finished {@code daysAgo} days ago. */
    WorkflowInstance finished(String workflowId, String serviceName, WorkflowStatus status, int daysAgo) {
        return store.createInstance(WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId(workflowId)
                .runId(UUID.randomUUID())
                .workflowType("test-workflow")
                .taskQueue("default")
                .status(status)
                .serviceName(serviceName)
                .eventSequence(0)
                .startedAt(now.minus(Duration.ofDays(daysAgo + 1)))
                .updatedAt(now)
                .completedAt(now.minus(Duration.ofDays(daysAgo)))
                .version(0)
                .build());
    }

    WorkflowSignal newSignal(String workflowId, UUID instanceId) {
        return new WorkflowSignal(UUID.randomUUID(), instanceId, workflowId, "s",
                jsonNode("{\"data\":\"value\"}"), false, now);
    }

    JsonNode jsonNode(String json) {
        return objectMapper.readTree(json);
    }

    long count(String sql, Object... args) throws Exception {
        try (Connection conn = dataSource.getConnection();
             var ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * A {@link DataSource} whose connections record the SQL text of every
     * {@code prepareStatement} call into {@code sql} and delegate everything.
     */
    static DataSource recording(DataSource delegate, List<String> sql) {
        InvocationHandler connections = (proxy, method, args) -> {
            var result = invoke(method, delegate, args);
            if (result instanceof Connection conn) {
                return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                        (p, m, a) -> {
                            if (m.getName().equals("prepareStatement") && a != null && a[0] instanceof String s) {
                                sql.add(s);
                            }
                            return invoke(m, conn, a);
                        });
            }
            return result;
        };
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, connections);
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
