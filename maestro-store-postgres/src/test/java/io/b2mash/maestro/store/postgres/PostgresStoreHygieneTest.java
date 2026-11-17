package io.b2mash.maestro.store.postgres;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.spi.PurgeResult;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store halves of the housekeeping paths, against a real Postgres: the
 * signal janitor's bounded, service-scoped delete; the service-scoped
 * retention purge and the documented manual purge; and the guards that pin the
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

    /**
     * {@code orders}: 3 COMPLETED finished 10 days ago; {@code billing}: 2
     * COMPLETED finished 10 days ago. Each has two events, a PENDING and a
     * FIRED timer, and a consumed and an unconsumed signal. The cutoff is 7
     * days ago.
     */
    @Nested
    class ScopedPurge {

        /**
         * The manual purge {@code docs/operations.md} §11.5 gives for the
         * history of a service name no node runs any more: one transaction
         * per batch of at most 500 terminal instances finished over 30 days
         * ago, children before the instances. Repeat until the last
         * statement deletes no row. The one parameter is the service name.
         */
        static final List<String> MANUAL_PURGE_SQL = List.of(
                "CREATE TEMPORARY TABLE maestro_purge_ids ON COMMIT DROP AS"
                        + " SELECT id FROM maestro_workflow_instance"
                        + " WHERE service_name = ? AND status IN ('COMPLETED', 'TERMINATED', 'FAILED')"
                        + " AND completed_at < now() - interval '30 days'"
                        + " ORDER BY completed_at LIMIT 500",
                "DELETE FROM maestro_workflow_signal WHERE workflow_instance_id IN (SELECT id FROM maestro_purge_ids)",
                "DELETE FROM maestro_workflow_timer WHERE workflow_instance_id IN (SELECT id FROM maestro_purge_ids)",
                "DELETE FROM maestro_workflow_event WHERE workflow_instance_id IN (SELECT id FROM maestro_purge_ids)",
                "DELETE FROM maestro_workflow_instance WHERE id IN (SELECT id FROM maestro_purge_ids)");

        @BeforeEach
        void fixture() {
            for (int i = 0; i < 3; i++) {
                attachHistory(finished("orders-c-" + i, "orders", WorkflowStatus.COMPLETED, 10));
            }
            for (int i = 0; i < 2; i++) {
                attachHistory(finished("billing-c-" + i, "billing", WorkflowStatus.COMPLETED, 10));
            }
        }

        @Test
        void purgesOnlyThisServicesInstancesWithTheirHistory() throws Exception {
            var result = store.purgeTerminalInstancesBefore("orders", WorkflowStatus.COMPLETED, cutoff, 500);
            assertEquals(new PurgeResult(3, 6, 6, 6), result);
            assertEquals(2, instancesOf("billing"));
            assertEquals(4, childRowsOf("maestro_workflow_event", "billing"));
            assertEquals(4, childRowsOf("maestro_workflow_signal", "billing"));
            assertEquals(4, childRowsOf("maestro_workflow_timer", "billing"));
        }

        @Test
        void aRowLockedElsewhereIsSkippedNotWaitedOn() throws Exception {
            try (Connection conn2 = dataSource.getConnection()) {
                conn2.setAutoCommit(false);
                try (var ps = conn2.prepareStatement(
                        "SELECT id FROM maestro_workflow_instance WHERE workflow_id = 'orders-c-0' FOR UPDATE");
                     var rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                }
                var result = CompletableFuture.supplyAsync(
                                () -> store.purgeTerminalInstancesBefore("orders", WorkflowStatus.COMPLETED, cutoff, 500))
                        .completeOnTimeout(null, 5, SECONDS).get();
                assertNotNull(result, "the purge waited on a locked candidate");
                assertEquals(2, result.instances());
                conn2.rollback();
            }
        }

        /** Guard: the load suite classifies the retention sweep by this leading text. */
        @Test
        void theScopedCandidateSelectKeepsTheLeadingTextThePerfHarnessMatches() {
            var sql = new CopyOnWriteArrayList<String>();
            new PostgresWorkflowStore(recording(dataSource, sql), objectMapper)
                    .purgeTerminalInstancesBefore("orders", WorkflowStatus.COMPLETED, cutoff, 500);
            var select = sql.stream().map(s -> s.replaceAll("\\s+", " ").strip())
                    .filter(s -> s.startsWith("SELECT id FROM maestro_workflow_instance")).findFirst().orElseThrow();
            assertTrue(select.startsWith("SELECT id FROM maestro_workflow_instance WHERE status = ? AND completed_at < "),
                    select);
        }

        @Test
        void theDocumentedManualPurgeRemovesAnAbandonedServicesHistory() throws Exception {
            for (int i = 0; i < 3; i++) {
                attachHistory(finished("orders-old-" + i, "orders-old", WorkflowStatus.COMPLETED, 40));
            }
            int rounds = 0;
            int deleted;
            do {
                deleted = runManualPurge("orders-old");
                rounds++;
            } while (deleted > 0 && rounds < 10);
            assertEquals(0, deleted, "the manual purge ran dry");
            assertEquals(0, instancesOf("orders-old"));
            assertEquals(0, orphanedChildRows());
            assertEquals(3, instancesOf("orders"), "another service's rows are untouched");
            assertEquals(2, instancesOf("billing"), "another service's rows are untouched");
        }

        /** Runs {@link #MANUAL_PURGE_SQL} as one transaction; returns the instance rows it deleted. */
        private int runManualPurge(String serviceName) throws Exception {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                int last = 0;
                for (int i = 0; i < MANUAL_PURGE_SQL.size(); i++) {
                    try (var ps = conn.prepareStatement(MANUAL_PURGE_SQL.get(i))) {
                        if (i == 0) {
                            ps.setString(1, serviceName);
                        }
                        last = ps.executeUpdate();
                    }
                }
                conn.commit();
                return last;
            }
        }

        /** Two events, a PENDING and a FIRED timer, an unconsumed and a consumed signal. */
        private void attachHistory(WorkflowInstance instance) {
            for (int seq = 1; seq <= 2; seq++) {
                store.appendEvent(new WorkflowEvent(UUID.randomUUID(), instance.id(), seq,
                        seq == 1 ? EventType.ACTIVITY_COMPLETED : EventType.WORKFLOW_COMPLETED, "step-" + seq,
                        jsonNode("{\"result\":\"ok\"}"), now));
            }
            for (var status : List.of(TimerStatus.PENDING, TimerStatus.FIRED)) {
                store.saveTimer(new WorkflowTimer(UUID.randomUUID(), instance.id(), instance.workflowId(),
                        "timer-" + UUID.randomUUID(), now.plusSeconds(60), status, now));
            }
            store.saveSignal(newSignal(instance.workflowId(), instance.id()));
            var consumed = newSignal(instance.workflowId(), instance.id());
            store.saveSignal(consumed);
            assertTrue(store.markSignalConsumed(consumed.id()));
        }

        private long instancesOf(String serviceName) throws Exception {
            return count("SELECT count(*) FROM maestro_workflow_instance WHERE service_name = ?", serviceName);
        }

        private long childRowsOf(String table, String serviceName) throws Exception {
            return count("SELECT count(*) FROM " + table + " c JOIN maestro_workflow_instance i"
                    + " ON i.id = c.workflow_instance_id WHERE i.service_name = ?", serviceName);
        }

        private long orphanedChildRows() throws Exception {
            long total = 0;
            for (var table : List.of("maestro_workflow_event", "maestro_workflow_signal", "maestro_workflow_timer")) {
                total += count("SELECT count(*) FROM " + table + " c WHERE c.workflow_instance_id IS NOT NULL"
                        + " AND NOT EXISTS (SELECT 1 FROM maestro_workflow_instance i WHERE i.id = c.workflow_instance_id)");
            }
            return total;
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
