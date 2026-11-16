package io.b2mash.maestro.store.postgres;

import io.b2mash.maestro.core.exception.OptimisticLockException;
import io.b2mash.maestro.core.exception.StoreUnavailableException;
import io.b2mash.maestro.core.exception.WorkflowNotFoundException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code reopenFailedInstance} on Postgres: the row lock, the version and
 * status check, the event deletes, the timer deletes and the status flip are
 * one transaction. The first six tests are the twins of
 * {@code InMemoryWorkflowStoreTest.ReopenFailedInstance} and
 * {@code VersionedInMemoryStoreReopenTest}, with the same names and assertions.
 */
class PostgresReopenFailedInstanceTest extends PostgresTestSupport {

    private WorkflowInstance w;
    private WorkflowInstance other;

    @BeforeEach
    void seed() {
        var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        w = store.createInstance(instance("w", WorkflowStatus.FAILED).toBuilder()
                .completedAt(now).version(3).build());
        store.appendEvent(event(w.id(), 1, EventType.ACTIVITY_COMPLETED));
        store.appendEvent(event(w.id(), 2, EventType.ACTIVITY_FAILED));
        store.appendEvent(event(w.id(), 3, EventType.ACTIVITY_COMPLETED));
        store.appendEvent(event(w.id(), 4, EventType.WORKFLOW_FAILED));
        store.saveTimer(new WorkflowTimer(UUID.randomUUID(), w.id(), "w", "sleep-3", now, TimerStatus.FIRED, now));
        store.saveTimer(new WorkflowTimer(UUID.randomUUID(), w.id(), "w", "sleep-1", now, TimerStatus.FIRED, now));
        other = store.createInstance(instance("other", WorkflowStatus.FAILED).toBuilder()
                .completedAt(now).version(3).build());
        store.appendEvent(event(other.id(), 2, EventType.ACTIVITY_FAILED));
        store.saveTimer(new WorkflowTimer(UUID.randomUUID(), other.id(), "other", "sleep-3", now,
                TimerStatus.FIRED, now));
    }

    @Test
    void reopenDeletesExactlyTheNamedEventsAndTimerRowsAndWritesTheRow() throws SQLException {
        // 99 and "nope" have no row: ignored
        store.reopenFailedInstance(running(w, 4), Set.of(2, 3, 4, 99), Set.of("sleep-3", "nope"));
        assertEquals(List.of(1), seqs(w));
        assertEquals(List.of("sleep-1"), timerIds(w));
        assertEquals(WorkflowStatus.RUNNING, store.getInstance("w").orElseThrow().status());
        assertEquals(List.of(2), seqs(other));
        assertEquals(List.of("sleep-3"), timerIds(other));
    }

    @Test
    void aStaleVersionDeletesNothing() throws SQLException {
        assertThrows(OptimisticLockException.class,
                () -> store.reopenFailedInstance(running(w, 3), Set.of(2), Set.of("sleep-3")));
        assertEquals(List.of(1, 2, 3, 4), seqs(w));
        assertEquals(List.of("sleep-1", "sleep-3"), timerIds(w));
        assertEquals(WorkflowStatus.FAILED, store.getInstance("w").orElseThrow().status());
    }

    @Test
    void aRowNoLongerFailedDeletesNothing() throws SQLException {
        // Another retry won first: the row is RUNNING at version 4, and a
        // reopen built for version 4 → 5 must not delete the new run's history.
        store.updateInstance(running(w, 4));
        assertThrows(OptimisticLockException.class,
                () -> store.reopenFailedInstance(running(w, 5), Set.of(2, 3, 4), Set.of("sleep-3")));
        assertEquals(List.of(1, 2, 3, 4), seqs(w));
        assertEquals(List.of("sleep-1", "sleep-3"), timerIds(w));
        assertEquals(4, store.getInstance("w").orElseThrow().version());
    }

    @Test
    void aMissingInstanceIsNotFound() {
        assertThrows(WorkflowNotFoundException.class,
                () -> store.reopenFailedInstance(runningUnknown(), Set.of(1), Set.of()));
    }

    @Test
    void aReopenedRowThatIsNotRunningIsRefusedBeforeAnyWrite() throws SQLException {
        var stillFailed = running(w, 4).toBuilder().status(WorkflowStatus.FAILED).build();
        assertThrows(IllegalArgumentException.class,
                () -> store.reopenFailedInstance(stillFailed, Set.of(2, 3, 4), Set.of("sleep-3")));
        var completed = running(w, 4).toBuilder().completedAt(Instant.now()).build();
        assertThrows(IllegalArgumentException.class,
                () -> store.reopenFailedInstance(completed, Set.of(2, 3, 4), Set.of("sleep-3")));
        assertEquals(List.of(1, 2, 3, 4), seqs(w));
        assertEquals(List.of("sleep-1", "sleep-3"), timerIds(w));
        assertEquals(WorkflowStatus.FAILED, store.getInstance("w").orElseThrow().status());
        assertEquals(3, store.getInstance("w").orElseThrow().version());
    }

    @Test
    void emptySetsOnlyWriteTheRow() throws SQLException {
        store.reopenFailedInstance(running(w, 4), Set.of(), Set.of());
        assertEquals(List.of(1, 2, 3, 4), seqs(w));
        assertEquals(List.of("sleep-1", "sleep-3"), timerIds(w));
        assertEquals(WorkflowStatus.RUNNING, store.getInstance("w").orElseThrow().status());
    }

    @Test
    void signalRowsAreUntouched() throws SQLException {
        var consumed = new WorkflowSignal(UUID.randomUUID(), w.id(), "w", "go", null, true,
                Instant.now().truncatedTo(ChronoUnit.MILLIS));
        store.saveSignal(consumed);
        store.reopenFailedInstance(running(w, 4), Set.of(2, 3, 4), Set.of("sleep-3"));
        assertTrue(store.getUnconsumedSignals("w", "go").isEmpty());
        assertEquals(List.of("go:true"), signalRows(w));
    }

    @Test
    void aFailingDeleteRollsEverythingBack() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("CREATE FUNCTION maestro_test_refuse() RETURNS trigger AS $$ "
                    + "BEGIN RAISE EXCEPTION 'refused'; END $$ LANGUAGE plpgsql");
            stmt.execute("CREATE TRIGGER maestro_test_refuse BEFORE DELETE ON maestro_workflow_timer "
                    + "FOR EACH ROW EXECUTE FUNCTION maestro_test_refuse()");
        }
        try {
            assertThrows(StoreUnavailableException.class,
                    () -> store.reopenFailedInstance(running(w, 4), Set.of(2, 3, 4), Set.of("sleep-3")));
            assertEquals(List.of(1, 2, 3, 4), seqs(w));
            assertEquals(WorkflowStatus.FAILED, store.getInstance("w").orElseThrow().status());
            assertEquals(3, store.getInstance("w").orElseThrow().version());
        } finally {
            try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
                stmt.execute("DROP TRIGGER IF EXISTS maestro_test_refuse ON maestro_workflow_timer");
                stmt.execute("DROP FUNCTION IF EXISTS maestro_test_refuse()");
            }
        }
    }

    @Test
    void aReopenWaitingOnAPurgeFindsTheRowGone() throws Exception {
        try (var purge = dataSource.getConnection()) {
            purge.setAutoCommit(false);
            try (var lock = purge.prepareStatement(
                    "SELECT id FROM maestro_workflow_instance WHERE workflow_id = 'w' FOR UPDATE")) {
                lock.executeQuery().close();
            }

            var reopened = running(w, 4);
            var reopenResult = new CompletableFuture<Throwable>();
            Thread.ofVirtual().start(() -> {
                try {
                    store.reopenFailedInstance(reopened, Set.of(2, 3, 4), Set.of("sleep-3"));
                    reopenResult.complete(null);
                } catch (Throwable t) {
                    reopenResult.complete(t);
                }
            });
            awaitAReopenBlockedOnALock();

            try (var stmt = purge.createStatement()) {
                var id = "'" + w.id() + "'";
                stmt.executeUpdate("DELETE FROM maestro_workflow_signal WHERE workflow_instance_id = " + id);
                stmt.executeUpdate("DELETE FROM maestro_workflow_timer WHERE workflow_instance_id = " + id);
                stmt.executeUpdate("DELETE FROM maestro_workflow_event WHERE workflow_instance_id = " + id);
                stmt.executeUpdate("DELETE FROM maestro_workflow_instance WHERE id = " + id);
            }
            purge.commit();

            var thrown = reopenResult.get(5, TimeUnit.SECONDS);
            assertInstanceOf(WorkflowNotFoundException.class, thrown);
        }
        assertEquals(List.of(2), seqs(other));
        assertEquals(List.of("sleep-3"), timerIds(other));
    }

    @Test
    void aReopenWaitingOnAConcurrentRetryLosesAndDeletesNothing() throws Exception {
        try (var first = dataSource.getConnection()) {
            // A first retry's transaction, in flight: it holds the row and has
            // deleted its own plan (here, only seq 2).
            first.setAutoCommit(false);
            try (var stmt = first.createStatement()) {
                var id = "'" + w.id() + "'";
                stmt.executeUpdate("UPDATE maestro_workflow_instance SET status = 'RUNNING', "
                        + "completed_at = NULL, version = 4 WHERE id = " + id);
                stmt.executeUpdate("DELETE FROM maestro_workflow_event WHERE workflow_instance_id = " + id
                        + " AND sequence_number = 2");
            }

            var reopened = running(w, 4);
            var reopenResult = new CompletableFuture<Throwable>();
            Thread.ofVirtual().start(() -> {
                try {
                    store.reopenFailedInstance(reopened, Set.of(2, 3, 4), Set.of("sleep-3"));
                    reopenResult.complete(null);
                } catch (Throwable t) {
                    reopenResult.complete(t);
                }
            });
            awaitAReopenBlockedOnALock();
            first.commit();

            var thrown = reopenResult.get(5, TimeUnit.SECONDS);
            assertInstanceOf(OptimisticLockException.class, thrown);
        }
        assertEquals(List.of(1, 3, 4), seqs(w), "only the winner's delete applied");
        assertEquals(List.of("sleep-1", "sleep-3"), timerIds(w));
        assertEquals(4, store.getInstance("w").orElseThrow().version());
    }

    @Test
    void moreSequencesThanOneChunkAreAllDeleted() throws SQLException {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            var id = "'" + w.id() + "'";
            stmt.executeUpdate("DELETE FROM maestro_workflow_event WHERE workflow_instance_id = " + id);
            stmt.executeUpdate("INSERT INTO maestro_workflow_event (id, workflow_instance_id, sequence_number, "
                    + "event_type, step_name, payload, created_at) SELECT gen_random_uuid(), " + id
                    + ", s, 'ACTIVITY_COMPLETED', 'step-' || s, NULL, now() FROM generate_series(1, 1203) s");
        }
        store.appendEvent(event(w.id(), 1204, EventType.WORKFLOW_FAILED));
        assertEquals(1204, seqs(w).size());
        var deletes = IntStream.rangeClosed(2, 1204).boxed().collect(Collectors.toSet());

        store.reopenFailedInstance(running(w, 4), deletes, Set.of());

        assertEquals(List.of(1), seqs(w));
    }

    @Test
    void aFailingLastChunkRollsBackTheEarlierChunks() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            var id = "'" + w.id() + "'";
            stmt.executeUpdate("DELETE FROM maestro_workflow_event WHERE workflow_instance_id = " + id);
            stmt.executeUpdate("INSERT INTO maestro_workflow_event (id, workflow_instance_id, sequence_number, "
                    + "event_type, step_name, payload, created_at) SELECT gen_random_uuid(), " + id
                    + ", s, 'ACTIVITY_COMPLETED', 'step-' || s, NULL, now() FROM generate_series(1, 1203) s");
            // Refuses only the delete of 1204, the last sequence of the last chunk.
            stmt.execute("CREATE FUNCTION maestro_test_refuse_last() RETURNS trigger AS $$ "
                    + "BEGIN IF OLD.sequence_number = 1204 THEN RAISE EXCEPTION 'refused'; END IF; "
                    + "RETURN OLD; END $$ LANGUAGE plpgsql");
            stmt.execute("CREATE TRIGGER maestro_test_refuse_last BEFORE DELETE ON maestro_workflow_event "
                    + "FOR EACH ROW EXECUTE FUNCTION maestro_test_refuse_last()");
        }
        try {
            store.appendEvent(event(w.id(), 1204, EventType.WORKFLOW_FAILED));
            var deletes = IntStream.rangeClosed(2, 1204).boxed().collect(Collectors.toSet());

            assertThrows(StoreUnavailableException.class,
                    () -> store.reopenFailedInstance(running(w, 4), deletes, Set.of("sleep-3")));

            assertEquals(1204, seqs(w).size(), "the first two chunks' deletes are rolled back");
            assertEquals(List.of("sleep-1", "sleep-3"), timerIds(w));
            assertEquals(WorkflowStatus.FAILED, store.getInstance("w").orElseThrow().status());
            assertEquals(3, store.getInstance("w").orElseThrow().version());
        } finally {
            try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
                stmt.execute("DROP TRIGGER IF EXISTS maestro_test_refuse_last ON maestro_workflow_event");
                stmt.execute("DROP FUNCTION IF EXISTS maestro_test_refuse_last()");
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Waits (at most 5 s) until a session of this database is waiting on a lock. */
    private void awaitAReopenBlockedOnALock() throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try (var conn = dataSource.getConnection(); var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT count(*) FROM pg_stat_activity "
                         + "WHERE datname = current_database() AND wait_event_type = 'Lock'")) {
                rs.next();
                if (rs.getInt(1) > 0) {
                    return;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the reopen never blocked on the purge's row lock");
    }

    /** The stored row, reopened: {@code RUNNING}, {@code completedAt} null, version {@code version}. */
    private WorkflowInstance running(WorkflowInstance instance, int version) {
        return store.getInstance(instance.workflowId()).orElseThrow().toBuilder()
                .status(WorkflowStatus.RUNNING).completedAt(null).version(version).build();
    }

    private static WorkflowInstance runningUnknown() {
        return instance("never-created", WorkflowStatus.RUNNING).toBuilder().version(1).build();
    }

    private List<Integer> seqs(WorkflowInstance instance) {
        return store.getEvents(instance.id()).stream().map(WorkflowEvent::sequenceNumber).toList();
    }

    private List<String> timerIds(WorkflowInstance instance) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("SELECT timer_id FROM maestro_workflow_timer "
                     + "WHERE workflow_instance_id = ? ORDER BY timer_id")) {
            ps.setObject(1, instance.id());
            try (var rs = ps.executeQuery()) {
                var ids = new ArrayList<String>();
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
                return ids;
            }
        }
    }

    /** Every signal row of {@code instance}, as {@code name:consumed}. */
    private List<String> signalRows(WorkflowInstance instance) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("SELECT signal_name, consumed FROM maestro_workflow_signal "
                     + "WHERE workflow_instance_id = ? ORDER BY signal_name")) {
            ps.setObject(1, instance.id());
            try (var rs = ps.executeQuery()) {
                var rows = new ArrayList<String>();
                while (rs.next()) {
                    rows.add(rs.getString(1) + ":" + rs.getBoolean(2));
                }
                return rows;
            }
        }
    }

    private static WorkflowInstance instance(String workflowId, WorkflowStatus status) {
        var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId(workflowId)
                .runId(UUID.randomUUID())
                .workflowType("test-workflow")
                .taskQueue("default")
                .status(status)
                .serviceName("test-service")
                .eventSequence(0)
                .startedAt(now)
                .updatedAt(now)
                .version(0)
                .build();
    }

    private static WorkflowEvent event(UUID instanceId, int seq, EventType type) {
        return new WorkflowEvent(UUID.randomUUID(), instanceId, seq, type, "step-" + seq, null,
                Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }
}
