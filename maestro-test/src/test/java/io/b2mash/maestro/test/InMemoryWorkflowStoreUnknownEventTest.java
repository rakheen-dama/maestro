package io.b2mash.maestro.test;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link EventType#UNKNOWN} sentinel's two store-side rules, pinned on the
 * in-memory store so they hold for {@code TestWorkflowEnvironment} exactly as
 * they do on Postgres (design §6.1):
 *
 * <ol>
 *   <li>the <b>write</b> path rejects it — the sentinel is a read-side
 *       representation and must never round-trip into history, or a node would
 *       durably persist "unreadable" and every node, upgraded or not, would
 *       stand down on it forever;</li>
 *   <li>the <b>read</b> path can carry it — {@link
 *       InMemoryWorkflowStore#injectRawEvent} is the seam a test uses to plant
 *       the history only a newer node could have written.</li>
 * </ol>
 */
@DisplayName("InMemoryWorkflowStore — the UNKNOWN sentinel can be read back but never written")
class InMemoryWorkflowStoreUnknownEventTest {

    private final InMemoryWorkflowStore store = new InMemoryWorkflowStore();
    private final UUID instanceId = UUID.randomUUID();

    @Test
    @DisplayName("appendEvent rejects the sentinel, and rejects it before storing anything")
    void appendEventRejectsTheSentinel() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> store.appendEvent(event(1, EventType.UNKNOWN)),
                "the read-side sentinel must never be persistable");

        assertAll(
                () -> assertTrue(thrown.getMessage().contains("UNKNOWN"),
                        "the message must name the sentinel: " + thrown.getMessage()),
                () -> assertTrue(store.getEventBySequence(instanceId, 1).isEmpty(),
                        "the rejected event must not have been stored"));
    }

    @Test
    @DisplayName("every real event type is still accepted — the guard is one constant wide")
    void appendEventStillAcceptsEveryRealType() {
        int seq = 1;
        for (var type : EventType.values()) {
            if (type == EventType.UNKNOWN) {
                continue;
            }
            store.appendEvent(event(seq, type));
            assertEquals(type, store.getEventBySequence(instanceId, seq).orElseThrow().eventType());
            seq++;
        }
        assertEquals(EventType.values().length - 1, store.getEvents(instanceId).size());
    }

    @Test
    @DisplayName("injectRawEvent plants sentinel history that reads back as the sentinel")
    void injectRawEventPlantsReadableSentinelHistory() {
        store.injectRawEvent(event(42, EventType.fromStoredName("EVT_FROM_A_NEWER_MAESTRO")));

        var stored = store.getEventBySequence(instanceId, 42).orElseThrow(
                () -> new AssertionError("the injected raw event must be readable"));
        assertAll(
                () -> assertEquals(EventType.UNKNOWN, stored.eventType()),
                () -> assertEquals(42, stored.sequenceNumber()),
                () -> assertEquals(1, store.getEvents(instanceId).size()));
    }

    private WorkflowEvent event(int sequenceNumber, EventType type) {
        return new WorkflowEvent(UUID.randomUUID(), instanceId, sequenceNumber, type,
                "step-" + sequenceNumber, null, Instant.now());
    }
}
