package io.b2mash.maestro.store.postgres;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store-side half of the unknown-event stand-down (design §6.1), against a
 * real PostgreSQL: <b>the read path must never throw on an unrecognised
 * {@code event_type} string, and the write path must never accept the
 * sentinel.</b>
 *
 * <p>The row is inserted by raw SQL, because that is precisely what a node
 * running a newer build does: it writes a type string this build's
 * {@code EventType} enum does not contain. Before the sentinel, the mapper's
 * {@code EventType.valueOf} threw an {@link IllegalArgumentException} from
 * inside {@code getEventBySequence} — an exception that, by the time it reached
 * {@code WorkflowExecutor}, was indistinguishable from a workflow failure, so
 * the workflow was marked {@code FAILED} and its sagas compensated for work
 * that never failed.
 */
@DisplayName("PostgresWorkflowStore maps an unknown event_type to the sentinel instead of throwing")
class PostgresUnknownEventMappingTest extends PostgresTestSupport {

    /** A type string no build of this repo will ever define (design §8.5, RULING 1). */
    private static final String FUTURE_TYPE = "EVT_FROM_A_NEWER_MAESTRO";

    @Test
    @DisplayName("getEventBySequence returns EventType.UNKNOWN for a future type — it does not throw")
    void getEventBySequenceMapsFutureTypeToSentinel() throws SQLException {
        var instance = createInstance("unknown-type-read");
        insertRawEvent(instance.id(), 1, FUTURE_TYPE);

        var event = assertDoesNotThrow(() -> store.getEventBySequence(instance.id(), 1),
                "a row mapper that throws on the type column turns a mixed-version deploy "
                        + "into a recorded workflow failure with compensations")
                .orElseThrow(() -> new AssertionError("the raw row must be readable"));

        assertAll(
                () -> assertEquals(EventType.UNKNOWN, event.eventType()),
                () -> assertEquals(1, event.sequenceNumber()),
                () -> assertEquals(instance.id(), event.workflowInstanceId()));
    }

    @Test
    @DisplayName("getEvents reads a whole history containing a future type without throwing")
    void getEventsMapsFutureTypeToSentinel() throws SQLException {
        var instance = createInstance("unknown-type-scan");
        store.appendEvent(realEvent(instance.id(), 1, EventType.SIDE_EFFECT));
        insertRawEvent(instance.id(), 2, FUTURE_TYPE);
        store.appendEvent(realEvent(instance.id(), 3, EventType.SIDE_EFFECT));

        var events = assertDoesNotThrow(() -> store.getEvents(instance.id()));

        assertAll(
                () -> assertEquals(3, events.size(), "every row must come back"),
                () -> assertEquals(EventType.SIDE_EFFECT, events.get(0).eventType()),
                () -> assertEquals(EventType.UNKNOWN, events.get(1).eventType(),
                        "only the unreadable row degrades to the sentinel"),
                () -> assertEquals(EventType.SIDE_EFFECT, events.get(2).eventType()));
    }

    @Test
    @DisplayName("appendEvent rejects the sentinel — it can never round-trip into history")
    void appendEventRejectsTheSentinel() throws SQLException {
        var instance = createInstance("unknown-type-write");

        var thrown = assertThrows(IllegalArgumentException.class,
                () -> store.appendEvent(realEvent(instance.id(), 1, EventType.UNKNOWN)),
                "persisting the sentinel would durably record 'unreadable', which every "
                        + "node — upgraded or not — would then stand down on forever");

        assertAll(
                () -> assertTrue(thrown.getMessage().contains("UNKNOWN"),
                        "the message must name the sentinel: " + thrown.getMessage()),
                () -> assertTrue(store.getEvents(instance.id()).isEmpty(),
                        "the rejected event must not have been written"));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private WorkflowInstance createInstance(String workflowId) {
        var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return store.createInstance(WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId(workflowId)
                .runId(UUID.randomUUID())
                .workflowType("test-workflow")
                .taskQueue("default")
                .status(WorkflowStatus.RUNNING)
                .serviceName("test-service")
                .eventSequence(0)
                .startedAt(now)
                .updatedAt(now)
                .version(0)
                .build());
    }

    private static WorkflowEvent realEvent(UUID instanceId, int seq, EventType type) {
        return new WorkflowEvent(UUID.randomUUID(), instanceId, seq, type, "step-" + seq, null,
                Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    /**
     * Writes an event row whose {@code event_type} this build does not define —
     * exactly what a node running a newer build does.
     */
    private void insertRawEvent(UUID instanceId, int seq, String storedType) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO maestro_workflow_event (id, workflow_instance_id, "
                             + "sequence_number, event_type, step_name, payload, created_at) "
                             + "VALUES (?, ?, ?, ?, ?, NULL, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, instanceId);
            ps.setInt(3, seq);
            ps.setString(4, storedType);
            ps.setString(5, "$maestro:from-the-future");
            ps.setTimestamp(6, java.sql.Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }
}
