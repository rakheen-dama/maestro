package io.b2mash.maestro.store.postgres;

import io.b2mash.maestro.core.exception.WorkflowNotFoundException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.spi.PurgeResult;
import io.b2mash.maestro.store.jdbc.AbstractJdbcWorkflowStore;
import io.b2mash.maestro.store.jdbc.JdbcStoreConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store halves of the housekeeping paths, against a real Postgres: the
 * signal janitor's bounded, service-scoped delete; the service-scoped
 * retention purge and the documented manual purge; the startup check's count
 * of active instances under other service names; and the guards that pin the
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
     * The janitor's orphan purge (Issue 83), against a real Postgres. Every
     * row below has a {@code NULL} instance; the cutoff is 7 days ago.
     * Deletable: an orphan received 10 days ago for an id that never started,
     * and two received 10 days ago whose workflow (created without adopting
     * them, as a delivery that raced the start leaves them) COMPLETED or was
     * TERMINATED 10 days ago. Kept: an orphan received 1 day ago (its
     * workflow may still start), old orphans of a RUNNING and of a FAILED
     * instance (both may still consume them), a consumed old orphan, and an
     * adopted unconsumed signal of the old COMPLETED instance (the terminal
     * rule's, not this one's).
     */
    @Nested
    class OrphanPurge {

        private final List<UUID> deletable = new ArrayList<>();
        private final List<UUID> kept = new ArrayList<>();

        @BeforeEach
        void fixture() throws Exception {
            deletable.add(orphan("never-started-old", 10));
            deletable.add(orphan(finished("done-c", "orders", WorkflowStatus.COMPLETED, 10).workflowId(), 10));
            deletable.add(orphan(finished("done-t", "orders", WorkflowStatus.TERMINATED, 10).workflowId(), 10));
            kept.add(orphan("never-started-new", 1));
            kept.add(orphan(active("live-r").workflowId(), 10));
            kept.add(orphan(finished("done-f", "orders", WorkflowStatus.FAILED, 10).workflowId(), 10));
            var consumed = orphan("consumed-old", 10);
            assertTrue(store.markSignalConsumed(consumed));
            kept.add(consumed);
            var adopted = newSignal("done-c", store.getInstance("done-c").orElseThrow().id());
            store.saveSignal(adopted);
            kept.add(adopted.id());
        }

        /** The done-when of Issue 83, and the batch bound: at most {@code limit} rows per call. */
        @Test
        void deletesExactlyTheOrphansNoWorkflowCanConsumeAtMostLimitPerCall() throws Exception {
            var deleted = IntStream.range(0, 3)
                    .map(i -> store.deleteOrphanedSignalsBefore(cutoff, 2)).boxed().toList();
            assertEquals(List.of(2, 1, 0), deleted);
            assertEquals(List.of(), deletable.stream().filter(this::exists).toList(), "deletable rows left");
            assertEquals(kept, kept.stream().filter(this::exists).toList(), "a kept row was deleted");
        }

        /**
         * A signal that arrived before its workflow started is kept by a sweep
         * inside the retention, and a start after the sweep adopts it and
         * reads it.
         */
        @Test
        void aWorkflowStartedAfterASweepInsideTheRetentionStillGetsItsSignal() throws Exception {
            var early = orphan("late-start", 6);
            while (store.deleteOrphanedSignalsBefore(cutoff, 2) == 2) {
                // drain the deletable rows
            }
            var instance = active("late-start");
            store.adoptOrphanedSignals(instance.workflowId(), instance.id());
            var signals = store.getUnconsumedSignals("late-start", "s");
            assertEquals(List.of(early), signals.stream().map(WorkflowSignal::id).toList());
            assertEquals(instance.id(), signals.getFirst().workflowInstanceId());
        }

        @Test
        void rejectsANonPositiveLimit() {
            assertThrows(IllegalArgumentException.class, () -> store.deleteOrphanedSignalsBefore(cutoff, 0));
        }

        /**
         * Guard: the load suite counts the janitor by the leading text
         * {@code DELETE FROM maestro_workflow_signal WHERE consumed = }, which
         * this statement shares with the terminal-instance delete.
         */
        @Test
        void theOrphanStatementKeepsTheLeadingTextThePerfHarnessMatches() {
            var sql = new CopyOnWriteArrayList<String>();
            new PostgresWorkflowStore(recording(dataSource, sql), objectMapper).deleteOrphanedSignalsBefore(cutoff, 2);
            var janitor = sql.stream().map(s -> s.replaceAll("\\s+", " ").strip())
                    .filter(s -> s.startsWith("DELETE FROM maestro_workflow_signal")).toList();
            assertEquals(1, janitor.size());
            assertTrue(janitor.getFirst().startsWith("DELETE FROM maestro_workflow_signal WHERE consumed = "),
                    janitor.getFirst());
        }

        /** Saves an orphan for {@code workflowId} and backdates its {@code received_at} by {@code daysAgo}. */
        private UUID orphan(String workflowId, int daysAgo) throws Exception {
            var signal = newSignal(workflowId, null);
            store.saveSignal(signal);
            try (Connection conn = dataSource.getConnection();
                 var ps = conn.prepareStatement("UPDATE maestro_workflow_signal SET received_at = ? WHERE id = ?")) {
                ps.setTimestamp(1, java.sql.Timestamp.from(now.minus(Duration.ofDays(daysAgo))));
                ps.setObject(2, signal.id());
                assertEquals(1, ps.executeUpdate());
            }
            return signal.id();
        }

        private WorkflowInstance active(String workflowId) {
            return store.createInstance(WorkflowInstance.builder()
                    .id(UUID.randomUUID())
                    .workflowId(workflowId)
                    .runId(UUID.randomUUID())
                    .workflowType("test-workflow")
                    .taskQueue("default")
                    .status(WorkflowStatus.RUNNING)
                    .serviceName("orders")
                    .eventSequence(0)
                    .startedAt(now.minus(Duration.ofDays(11)))
                    .updatedAt(now)
                    .version(0)
                    .build());
        }

        private boolean exists(UUID signalId) {
            try {
                return count("SELECT count(*) FROM maestro_workflow_signal WHERE id = ?", signalId) == 1;
            } catch (Exception e) {
                throw new IllegalStateException(e);
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
         * history of a service name no node runs any more: ONE statement that
         * deletes at most 500 terminal instances of the service finished
         * longer ago than the age, with their signals, timers and events.
         * Repeat it until it reports 0 rows. The two parameters are the
         * service name and the age (an interval text such as
         * {@code '30 days'}), each written once.
         *
         * <p>One statement is atomic even in autocommit. The {@code candidates}
         * CTE locks its rows ({@code FOR UPDATE SKIP LOCKED}) for the whole
         * statement: a candidate another transaction holds (a Retry in
         * flight, say) is skipped, not waited on, and keeps its row and
         * history; a Retry that starts later waits and then finds no row. The
         * data-modifying CTEs run whether or not anything reads them, and
         * the foreign keys are checked at the end of the statement, after
         * the children are gone.
         */
        static final String MANUAL_PURGE_SQL = "WITH candidates AS ("
                + " SELECT id FROM maestro_workflow_instance"
                + " WHERE service_name = ? AND status IN ('COMPLETED', 'TERMINATED', 'FAILED')"
                + " AND completed_at < now() - CAST(? AS interval)"
                + " ORDER BY completed_at LIMIT 500"
                + " FOR UPDATE SKIP LOCKED),"
                + " purged_signals AS (DELETE FROM maestro_workflow_signal"
                + " WHERE workflow_instance_id IN (SELECT id FROM candidates)),"
                + " purged_timers AS (DELETE FROM maestro_workflow_timer"
                + " WHERE workflow_instance_id IN (SELECT id FROM candidates)),"
                + " purged_events AS (DELETE FROM maestro_workflow_event"
                + " WHERE workflow_instance_id IN (SELECT id FROM candidates))"
                + " DELETE FROM maestro_workflow_instance WHERE id IN (SELECT id FROM candidates)";

        /**
         * The completion check {@code docs/operations.md} §11.5 gives after
         * {@link #MANUAL_PURGE_SQL} reports 0: the same service, status and
         * age predicates, without {@code SKIP LOCKED}. A report of 0 alone is
         * not proof, because the purge skips a candidate another transaction
         * holds; a count above 0 means run the purge again.
         */
        static final String MANUAL_PURGE_REMAINING_SQL = "SELECT count(*) AS remaining FROM maestro_workflow_instance"
                + " WHERE service_name = ? AND status IN ('COMPLETED', 'TERMINATED', 'FAILED')"
                + " AND completed_at < now() - CAST(? AS interval)";

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
            assertEquals(List.of(1L, 2L, 2L, 2L), List.of(rowsOfWorkflow("orders-c-0"),
                            childRowsOfWorkflow("maestro_workflow_event", "orders-c-0"),
                            childRowsOfWorkflow("maestro_workflow_signal", "orders-c-0"),
                            childRowsOfWorkflow("maestro_workflow_timer", "orders-c-0")),
                    "the skipped instance keeps its row and its whole history");
        }

        /**
         * A Retry that reaches a FAILED candidate while the scoped purge's
         * batch holds it waits for the batch to commit, then finds no row:
         * the purge took the whole history, and the Retry wrote nothing.
         */
        @Test
        void aRetryOfACandidateTheScopedPurgeHoldsWaitsAndThenFindsNoRow() throws Exception {
            var failed = finished("orders-f", "orders", WorkflowStatus.FAILED, 10);
            attachHistory(failed);
            var selected = new CountDownLatch(1);
            var go = new CountDownLatch(1);
            var purging = new PostgresWorkflowStore(
                    pausingAt(dataSource, "DELETE FROM maestro_workflow_signal", selected, go), objectMapper);
            var purge = CompletableFuture.supplyAsync(
                    () -> purging.purgeTerminalInstancesBefore("orders", WorkflowStatus.FAILED, cutoff, 500));
            Boolean retriedWhileHeld;
            CompletableFuture<Boolean> retry;
            try {
                assertTrue(selected.await(5, SECONDS), "the purge selected its candidates");
                retry = CompletableFuture.supplyAsync(() -> retry(failed));
                retriedWhileHeld = retry.copy().completeOnTimeout(null, 1, SECONDS).get();
            } finally {
                go.countDown();
            }
            var purged = purge.get(10, SECONDS);
            boolean retried = retry.get(10, SECONDS);
            assertEquals(List.of("retriedWhileHeld=null", "purged=1", "retried=false", "row=0", "orphans=0"),
                    List.of("retriedWhileHeld=" + retriedWhileHeld, "purged=" + purged.instances(),
                            "retried=" + retried, "row=" + rowsOfWorkflow("orders-f"),
                            "orphans=" + orphanedChildRows()));
        }

        /**
         * On the portable SQL (no row lock on the candidates), an instance
         * whose {@code service_name} is corrected between the candidate select
         * and the deletes is no longer this service's: the instance delete's
         * service re-check falls short, and the whole batch, children
         * included, rolls back.
         */
        @Test
        void onThePortableSqlAnInstanceRenamedBeforeTheDeleteKeepsItsRowAndHistory() throws Exception {
            var selected = new CountDownLatch(1);
            var go = new CountDownLatch(1);
            var portable = new AbstractJdbcWorkflowStore(
                    pausingAt(dataSource, "DELETE FROM maestro_workflow_signal", selected, go),
                    JdbcStoreConfiguration.withDefaults(objectMapper)) {
                @Override
                protected String getDueTimersSql() {
                    throw new UnsupportedOperationException("not used by this test");
                }
            };
            var purge = CompletableFuture.supplyAsync(
                    () -> portable.purgeTerminalInstancesBefore("orders", WorkflowStatus.COMPLETED, cutoff, 500));
            try {
                assertTrue(selected.await(5, SECONDS), "the purge selected its candidates");
                try (Connection conn = dataSource.getConnection();
                     var ps = conn.prepareStatement("UPDATE maestro_workflow_instance"
                             + " SET service_name = 'orders-renamed' WHERE workflow_id = 'orders-c-0'")) {
                    assertEquals(1, ps.executeUpdate());
                }
            } finally {
                go.countDown();
            }
            var purged = purge.get(10, SECONDS);
            assertEquals(List.of(PurgeResult.EMPTY, 1L, 2L, 2L, 2L, 2L), List.of(purged,
                    rowsOfWorkflow("orders-c-0"),
                    childRowsOfWorkflow("maestro_workflow_event", "orders-c-0"),
                    childRowsOfWorkflow("maestro_workflow_signal", "orders-c-0"),
                    childRowsOfWorkflow("maestro_workflow_timer", "orders-c-0"),
                    instancesOf("orders")));
        }

        /** A Retry that committed before the scoped purge ran leaves nothing for it to take. */
        @Test
        void aRetryCommittedBeforeTheScopedPurgeKeepsTheWorkflowAndItsHistory() throws Exception {
            var failed = finished("orders-f", "orders", WorkflowStatus.FAILED, 10);
            attachHistory(failed);
            assertTrue(retry(failed));
            var purged = store.purgeTerminalInstancesBefore("orders", WorkflowStatus.FAILED, cutoff, 500);
            assertEquals(List.of(0, 1L, 2L, 2L, 2L), List.of(purged.instances(), rowsOfWorkflow("orders-f"),
                    childRowsOfWorkflow("maestro_workflow_event", "orders-f"),
                    childRowsOfWorkflow("maestro_workflow_signal", "orders-f"),
                    childRowsOfWorkflow("maestro_workflow_timer", "orders-f")));
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
            attachHistory(finished("orders-old-f", "orders-old", WorkflowStatus.FAILED, 40));
            attachHistory(finished("orders-old-t", "orders-old", WorkflowStatus.TERMINATED, 40));
            assertEquals(List.of(5, 0), List.of(runManualPurge("orders-old", "30 days"),
                    runManualPurge("orders-old", "30 days")), "one statement took the batch; the next reported 0");
            assertEquals(0, instancesOf("orders-old"));
            assertEquals(0, orphanedChildRows());
            assertEquals(3, instancesOf("orders"), "another service's rows are untouched");
            assertEquals(2, instancesOf("billing"), "another service's rows are untouched");
        }

        @Test
        void theManualPurgeTakesOnlyTheNamedServicesRowsOlderThanTheAge() throws Exception {
            attachHistory(finished("old-40", "orders-old", WorkflowStatus.COMPLETED, 40));
            attachHistory(finished("old-10", "orders-old", WorkflowStatus.COMPLETED, 10));
            attachHistory(finished("other-40", "orders-other", WorkflowStatus.COMPLETED, 40));
            assertEquals(1, runManualPurge("orders-old", "30 days"));
            assertEquals(List.of(0L, 1L, 2L, 1L, 2L), List.of(rowsOfWorkflow("old-40"), rowsOfWorkflow("old-10"),
                    childRowsOfWorkflow("maestro_workflow_event", "old-10"), rowsOfWorkflow("other-40"),
                    childRowsOfWorkflow("maestro_workflow_event", "other-40")));
        }

        @Test
        void theManualPurgeTakesAtMostFiveHundredInstancesPerRun() throws Exception {
            for (int i = 0; i < 501; i++) {
                finished("old-" + i, "orders-old", WorkflowStatus.COMPLETED, 40);
            }
            assertEquals(List.of(500, 1, 0), List.of(runManualPurge("orders-old", "30 days"),
                    runManualPurge("orders-old", "30 days"), runManualPurge("orders-old", "30 days")));
        }

        /**
         * A Retry in flight — it has reopened a FAILED candidate and not yet
         * committed — when the manual purge runs: the purge skips the
         * candidate at once, and the retried workflow keeps its row and its
         * whole history.
         */
        @Test
        void aRetryInFlightWhenTheManualPurgeRunsKeepsItsRowAndHistory() throws Exception {
            attachHistory(finished("orders-old-f", "orders-old", WorkflowStatus.FAILED, 40));
            attachHistory(finished("orders-old-c", "orders-old", WorkflowStatus.COMPLETED, 40));
            Integer deleted;
            CompletableFuture<Integer> purge;
            try (Connection retry = dataSource.getConnection()) {
                retry.setAutoCommit(false);
                try (var ps = retry.prepareStatement("UPDATE maestro_workflow_instance SET status = 'RUNNING',"
                        + " completed_at = NULL, version = version + 1 WHERE workflow_id = 'orders-old-f'")) {
                    assertEquals(1, ps.executeUpdate());
                }
                purge = CompletableFuture.supplyAsync(() -> manualPurge("orders-old", "30 days"));
                deleted = purge.copy().completeOnTimeout(-1, 5, SECONDS).get();
                retry.commit();
            }
            purge.get(10, SECONDS);
            assertEquals(1, deleted, "the manual purge waited on the Retry (-1) instead of skipping its row");
            assertEquals(List.of(1L, 2L, 2L, 2L, 0L), List.of(rowsOfWorkflow("orders-old-f"),
                    childRowsOfWorkflow("maestro_workflow_event", "orders-old-f"),
                    childRowsOfWorkflow("maestro_workflow_signal", "orders-old-f"),
                    childRowsOfWorkflow("maestro_workflow_timer", "orders-old-f"),
                    rowsOfWorkflow("orders-old-c")));
        }

        @Test
        void theManualPurgeSkipsACandidateLockedElsewhereAndLeavesItsHistory() throws Exception {
            attachHistory(finished("orders-old-0", "orders-old", WorkflowStatus.COMPLETED, 40));
            attachHistory(finished("orders-old-1", "orders-old", WorkflowStatus.COMPLETED, 40));
            try (Connection conn2 = dataSource.getConnection()) {
                conn2.setAutoCommit(false);
                try (var ps = conn2.prepareStatement(
                        "SELECT id FROM maestro_workflow_instance WHERE workflow_id = 'orders-old-0' FOR UPDATE");
                     var rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                }
                var purge = CompletableFuture.supplyAsync(() -> manualPurge("orders-old", "30 days"));
                var deleted = purge.copy().completeOnTimeout(-1, 5, SECONDS).get();
                var again = deleted == 1 ? runManualPurge("orders-old", "30 days") : -1;
                var remaining = remaining("orders-old", "30 days");
                conn2.rollback();
                purge.get(10, SECONDS);
                assertEquals(1, deleted, "the manual purge waited on a locked candidate (-1) instead of skipping it");
                assertEquals(List.of(0, 1L), List.of(again, remaining),
                        "a second run reports 0 while the completion check still counts the locked candidate");
            }
            assertEquals(List.of(1L, 2L, 2L, 2L, 0L), List.of(rowsOfWorkflow("orders-old-0"),
                    childRowsOfWorkflow("maestro_workflow_event", "orders-old-0"),
                    childRowsOfWorkflow("maestro_workflow_signal", "orders-old-0"),
                    childRowsOfWorkflow("maestro_workflow_timer", "orders-old-0"),
                    rowsOfWorkflow("orders-old-1")));
            assertEquals(1, runManualPurge("orders-old", "30 days"), "once released, the next run takes it");
            assertEquals(0L, remaining("orders-old", "30 days"));
        }

        /**
         * Guard: the script {@code docs/operations.md} §11.5 prints is this
         * statement, with {@code :'service'} and {@code :'age'} for the two
         * parameters, each written exactly once.
         */
        @Test
        void theOperationsGuidePrintsExactlyThisStatement() throws Exception {
            var guide = Files.readString(Path.of("..", "docs", "operations.md"));
            var start = guide.indexOf("\\set ON_ERROR_STOP on\n");
            assertTrue(start >= 0, "the guide's manual purge block starts with \\set ON_ERROR_STOP on");
            var block = guide.substring(start, guide.indexOf("```", start));
            var statement = block.lines().filter(l -> !l.startsWith("\\")).collect(Collectors.joining(" "));
            assertEquals(List.of(1, 1), List.of(occurrences(statement, ":'service'"), occurrences(statement, ":'age'")),
                    "service and age are each written once");
            assertEquals(normalised(MANUAL_PURGE_SQL),
                    normalised(statement.replace(":'service'", "?").replace(":'age'", "?").replace(";", "")));

            var countStart = guide.indexOf("SELECT count(*) AS remaining", start);
            assertTrue(countStart >= 0, "the guide prints the completion check after the purge");
            var count = guide.substring(countStart, guide.indexOf("```", countStart));
            assertEquals(List.of(1, 1), List.of(occurrences(count, ":'service'"), occurrences(count, ":'age'")),
                    "the completion check names service and age once each");
            assertEquals(normalised(MANUAL_PURGE_REMAINING_SQL),
                    normalised(count.replace(":'service'", "?").replace(":'age'", "?").replace(";", "")));
        }

        /** Runs {@link #MANUAL_PURGE_SQL} once, in autocommit; returns the instance rows it deleted. */
        private int runManualPurge(String serviceName, String age) throws Exception {
            try (Connection conn = dataSource.getConnection();
                 var ps = conn.prepareStatement(MANUAL_PURGE_SQL)) {
                ps.setString(1, serviceName);
                ps.setString(2, age);
                return ps.executeUpdate();
            }
        }

        /** Runs {@link #MANUAL_PURGE_REMAINING_SQL}; returns the count. */
        private long remaining(String serviceName, String age) throws Exception {
            return count(MANUAL_PURGE_REMAINING_SQL, serviceName, age);
        }

        private int manualPurge(String serviceName, String age) {
            try {
                return runManualPurge(serviceName, age);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private static int occurrences(String text, String token) {
            return text.split(java.util.regex.Pattern.quote(token), -1).length - 1;
        }

        private static String normalised(String sql) {
            return sql.replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")").strip();
        }

        /** The engine's Retry write; {@code false} when the row is gone. */
        private boolean retry(WorkflowInstance failed) {
            try {
                store.reopenFailedInstance(failed.toBuilder().status(WorkflowStatus.RUNNING).completedAt(null)
                        .version(failed.version() + 1).build(), Set.of(), Set.of());
                return true;
            } catch (WorkflowNotFoundException e) {
                return false;
            }
        }

        private long rowsOfWorkflow(String workflowId) throws Exception {
            return count("SELECT count(*) FROM maestro_workflow_instance WHERE workflow_id = ?", workflowId);
        }

        private long childRowsOfWorkflow(String table, String workflowId) throws Exception {
            return count("SELECT count(*) FROM " + table + " c JOIN maestro_workflow_instance i"
                    + " ON i.id = c.workflow_instance_id WHERE i.workflow_id = ?", workflowId);
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

    /**
     * {@code orders} (this node) / {@code order}: 2 RUNNING. {@code orders-old}
     * / {@code order}: RUNNING, WAITING_SIGNAL, WAITING_TIMER, and 5 COMPLETED.
     * {@code orders-1} / {@code payment}: 2 COMPENSATING. {@code billing} /
     * {@code invoice}: 4 RUNNING.
     */
    @Nested
    class ForeignServiceCounts {

        @BeforeEach
        void fixture() {
            seed("own-1", "orders", "order", WorkflowStatus.RUNNING);
            seed("own-2", "orders", "order", WorkflowStatus.RUNNING);
            seed("old-1", "orders-old", "order", WorkflowStatus.RUNNING);
            seed("old-2", "orders-old", "order", WorkflowStatus.WAITING_SIGNAL);
            seed("old-3", "orders-old", "order", WorkflowStatus.WAITING_TIMER);
            seed("pay-1", "orders-1", "payment", WorkflowStatus.COMPENSATING);
            seed("pay-2", "orders-1", "payment", WorkflowStatus.COMPENSATING);
            for (int i = 0; i < 4; i++) {
                seed("inv-" + i, "billing", "invoice", WorkflowStatus.RUNNING);
            }
            for (int i = 0; i < 5; i++) {
                seed("old-done-" + i, "orders-old", "order", WorkflowStatus.COMPLETED);
            }
        }

        /**
         * P3 (Issue 79): every other service name's active instances, whatever
         * their type — {@code billing}'s invoices included, terminal rows not.
         * (Before P3 the read took a type filter and left {@code billing} out.)
         */
        @Test
        void countsActiveInstancesOfAnyTypeUnderEveryOtherServiceName() {
            var counts = store.getActiveInstanceCountsUnderOtherServices("orders", 21);
            assertEquals(List.of(Map.entry("billing", 4L), Map.entry("orders-old", 3L), Map.entry("orders-1", 2L)),
                    List.copyOf(counts.entrySet()));
        }

        @Test
        void theLimitCapsTheNamesReturned() {
            assertEquals(Set.of("billing"), store.getActiveInstanceCountsUnderOtherServices("orders", 1).keySet());
        }

        /**
         * The read is cheap by construction: with sequential scans disabled,
         * the planner answers it from the partial active-instance index
         * {@code idx_wf_instance_recovery} (V101), so it never needs to read
         * the table or its terminal rows.
         */
        @Test
        void theReadCanBeAnsweredFromThePartialActiveInstanceIndex() throws Exception {
            var sql = new CopyOnWriteArrayList<String>();
            new PostgresWorkflowStore(recording(dataSource, sql), objectMapper)
                    .getActiveInstanceCountsUnderOtherServices("orders", 21);
            var statement = sql.getFirst();
            var plan = new StringBuilder();
            try (var conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try (var off = conn.createStatement()) {
                    off.execute("SET LOCAL enable_seqscan = off");
                }
                try (var explain = conn.prepareStatement("EXPLAIN " + statement)) {
                    explain.setString(1, "orders");
                    explain.setString(2, "orders");
                    explain.setInt(3, 21);
                    try (var rs = explain.executeQuery()) {
                        while (rs.next()) {
                            plan.append(rs.getString(1)).append('\n');
                        }
                    }
                }
                conn.rollback();
            }
            assertTrue(plan.toString().contains("idx_wf_instance_recovery"), plan.toString());
            assertFalse(plan.toString().contains("Seq Scan"), plan.toString());
        }

        /**
         * Guard: the check is one read, and its text never matches the load
         * suite's recovery-page pattern ({@code WHERE service_name = ...}).
         */
        @Test
        void theQueryOnlyReadsAndNeverLooksLikeTheRecoveryPage() throws Exception {
            var before = tableRowCounts();
            var sql = new CopyOnWriteArrayList<String>();
            new PostgresWorkflowStore(recording(dataSource, sql), objectMapper)
                    .getActiveInstanceCountsUnderOtherServices("orders", 21);
            var statements = sql.stream().map(s -> s.replaceAll("\\s+", " ").strip()).toList();
            assertEquals(1, statements.size(), statements.toString());
            assertTrue(statements.getFirst().startsWith(
                    "SELECT service_name, count(*) FROM maestro_workflow_instance WHERE status IN ("),
                    statements.getFirst());
            assertFalse(statements.getFirst().contains("WHERE service_name = ?"), statements.getFirst());
            assertEquals(before, tableRowCounts());
        }

        private List<Long> tableRowCounts() throws Exception {
            var counts = new ArrayList<Long>();
            for (var table : List.of("maestro_workflow_instance", "maestro_workflow_event",
                    "maestro_workflow_signal", "maestro_workflow_timer")) {
                counts.add(count("SELECT count(*) FROM " + table));
            }
            return counts;
        }

        private void seed(String workflowId, String serviceName, String type, WorkflowStatus status) {
            store.createInstance(WorkflowInstance.builder()
                    .id(UUID.randomUUID())
                    .workflowId(workflowId)
                    .runId(UUID.randomUUID())
                    .workflowType(type)
                    .taskQueue("default")
                    .status(status)
                    .serviceName(serviceName)
                    .eventSequence(0)
                    .startedAt(now.minus(Duration.ofDays(2)))
                    .updatedAt(now)
                    .completedAt(status.isTerminal() ? now.minus(Duration.ofDays(1)) : null)
                    .version(0)
                    .build());
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

    /**
     * A {@link DataSource} whose connections, on the first
     * {@code prepareStatement} whose SQL starts with {@code prefix}, count
     * {@code reached} down and wait for {@code go} before preparing it.
     */
    static DataSource pausingAt(DataSource delegate, String prefix, CountDownLatch reached, CountDownLatch go) {
        InvocationHandler connections = (proxy, method, args) -> {
            var result = invoke(method, delegate, args);
            if (result instanceof Connection conn) {
                return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                        (p, m, a) -> {
                            if (m.getName().equals("prepareStatement") && a != null && a[0] instanceof String s
                                    && s.strip().startsWith(prefix) && reached.getCount() > 0) {
                                reached.countDown();
                                go.await(30, SECONDS);
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
