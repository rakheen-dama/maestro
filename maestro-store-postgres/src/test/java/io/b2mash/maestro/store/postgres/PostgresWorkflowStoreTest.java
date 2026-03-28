package io.b2mash.maestro.store.postgres;

import io.b2mash.maestro.core.exception.DuplicateEventException;
import io.b2mash.maestro.core.exception.OptimisticLockException;
import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
import io.b2mash.maestro.core.exception.WorkflowNotFoundException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link PostgresWorkflowStore}.
 *
 * <p>Uses Testcontainers to run a real PostgreSQL instance.
 * Each test starts with empty tables (truncated in {@link PostgresTestSupport}).
 */
class PostgresWorkflowStoreTest extends PostgresTestSupport {

    // ── Test data factories ───────────────────────────────────────────────

    private WorkflowInstance newInstance(String workflowId) {
        return newInstance(workflowId, WorkflowStatus.RUNNING);
    }

    private WorkflowInstance newInstance(String workflowId, WorkflowStatus status) {
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

    private WorkflowEvent newEvent(UUID instanceId, int seq, EventType type) {
        return new WorkflowEvent(
                UUID.randomUUID(), instanceId, seq, type, "step-" + seq,
                jsonNode("{\"result\":\"ok\"}"),
                Instant.now().truncatedTo(ChronoUnit.MILLIS)
        );
    }

    private WorkflowSignal newSignal(String workflowId, UUID instanceId, String signalName) {
        return new WorkflowSignal(
                UUID.randomUUID(), instanceId, workflowId, signalName,
                jsonNode("{\"data\":\"value\"}"), false,
                Instant.now().truncatedTo(ChronoUnit.MILLIS)
        );
    }

    private WorkflowTimer newTimer(UUID instanceId, String workflowId,
                                    Instant fireAt, TimerStatus status) {
        return new WorkflowTimer(
                UUID.randomUUID(), instanceId, workflowId, "timer-" + UUID.randomUUID(),
                fireAt, status, Instant.now().truncatedTo(ChronoUnit.MILLIS)
        );
    }

    private JsonNode jsonNode(String json) {
        return objectMapper.readTree(json);
    }

    // ── Instance Tests ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Instance operations")
    class InstanceTests {

        @Test
        @DisplayName("createInstance inserts and returns the instance")
        void createInstance_insertsAndReturns() {
            var instance = newInstance("order-1");
            var created = store.createInstance(instance);

            assertEquals(instance.id(), created.id());
            assertEquals("order-1", created.workflowId());
        }

        @Test
        @DisplayName("createInstance throws on duplicate workflowId")
        void createInstance_throwsOnDuplicateWorkflowId() {
            store.createInstance(newInstance("order-dup"));

            assertThrows(WorkflowAlreadyExistsException.class,
                    () -> store.createInstance(newInstance("order-dup")));
        }

        @Test
        @DisplayName("createInstance adopts orphaned signals atomically")
        void createInstance_adoptsOrphanedSignals() {
            // Save orphan signal (null instanceId)
            var orphan = new WorkflowSignal(
                    UUID.randomUUID(), null, "order-orphan", "payment.result",
                    jsonNode("{\"status\":\"ok\"}"), false,
                    Instant.now().truncatedTo(ChronoUnit.MILLIS));
            store.saveSignal(orphan);

            // Create the workflow — should adopt the orphan
            var instance = newInstance("order-orphan");
            store.createInstance(instance);

            // Verify the signal now has the instance ID
            var signals = store.getUnconsumedSignals("order-orphan", "payment.result");
            assertEquals(1, signals.size());
            assertEquals(instance.id(), signals.getFirst().workflowInstanceId());
        }

        @Test
        @DisplayName("getInstance returns empty when not found")
        void getInstance_returnsEmpty_whenNotFound() {
            assertTrue(store.getInstance("nonexistent").isEmpty());
        }

        @Test
        @DisplayName("getInstance returns the instance when it exists")
        void getInstance_returnsInstance_whenExists() {
            var instance = newInstance("order-get");
            store.createInstance(instance);

            var found = store.getInstance("order-get");
            assertTrue(found.isPresent());
            assertEquals(instance.id(), found.get().id());
            assertEquals(WorkflowStatus.RUNNING, found.get().status());
        }

        @Test
        @DisplayName("getInstance preserves JSON input/output payloads")
        void getInstance_preservesJsonPayloads() {
            var instance = newInstance("order-json").toBuilder()
                    .input(jsonNode("{\"items\":[1,2,3]}"))
                    .output(jsonNode("{\"total\":42.5}"))
                    .build();
            store.createInstance(instance);

            var found = store.getInstance("order-json").orElseThrow();
            assertNotNull(found.input());
            assertEquals(3, found.input().get("items").size());
            assertNotNull(found.output());
            assertEquals(42.5, found.output().get("total").doubleValue());
        }

        @Test
        @DisplayName("getInstance preserves null JSON input/output")
        void getInstance_preservesNullJsonPayloads() {
            var instance = newInstance("order-null-json");
            // newInstance sets input and output to null by default
            store.createInstance(instance);

            var found = store.getInstance("order-null-json").orElseThrow();
            assertNull(found.input());
            assertNull(found.output());
        }

        @Test
        @DisplayName("getRecoverableInstances returns only active statuses")
        void getRecoverableInstances_returnsOnlyActive() {
            store.createInstance(newInstance("active-1", WorkflowStatus.RUNNING));
            store.createInstance(newInstance("active-2", WorkflowStatus.WAITING_SIGNAL));
            store.createInstance(newInstance("active-3", WorkflowStatus.WAITING_TIMER));
            store.createInstance(newInstance("active-4", WorkflowStatus.COMPENSATING));
            store.createInstance(newInstance("done-1", WorkflowStatus.COMPLETED));
            store.createInstance(newInstance("done-2", WorkflowStatus.FAILED));
            store.createInstance(newInstance("done-3", WorkflowStatus.TERMINATED));

            var recoverable = store.getRecoverableInstances();
            assertEquals(4, recoverable.size());
            assertTrue(recoverable.stream()
                    .allMatch(i -> i.status().isActive()));
        }

        @Test
        @DisplayName("getRecoverableInstances ordered by startedAt ascending")
        void getRecoverableInstances_orderedByStartedAt() {
            var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            store.createInstance(newInstance("later").toBuilder()
                    .startedAt(now.plusSeconds(10)).updatedAt(now.plusSeconds(10)).build());
            store.createInstance(newInstance("earlier").toBuilder()
                    .startedAt(now.minusSeconds(10)).updatedAt(now.minusSeconds(10)).build());

            var recoverable = store.getRecoverableInstances();
            assertEquals(2, recoverable.size());
            assertEquals("earlier", recoverable.get(0).workflowId());
            assertEquals("later", recoverable.get(1).workflowId());
        }

        @Test
        @DisplayName("updateInstance updates fields and increments version")
        void updateInstance_updatesFieldsAndIncrementsVersion() {
            var instance = newInstance("order-update");
            store.createInstance(instance);

            var updated = instance.toBuilder()
                    .status(WorkflowStatus.COMPLETED)
                    .completedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS))
                    .output(jsonNode("{\"success\":true}"))
                    .updatedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS))
                    .build();
            store.updateInstance(updated);

            var found = store.getInstance("order-update").orElseThrow();
            assertEquals(WorkflowStatus.COMPLETED, found.status());
            assertEquals(1, found.version());
            assertNotNull(found.completedAt());
            assertNotNull(found.output());
        }

        @Test
        @DisplayName("updateInstance throws OptimisticLockException on version mismatch")
        void updateInstance_throwsOptimisticLockException() {
            var instance = newInstance("order-lock");
            store.createInstance(instance);

            // First update succeeds (version 0 → 1)
            store.updateInstance(instance.toBuilder()
                    .status(WorkflowStatus.WAITING_SIGNAL)
                    .updatedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS))
                    .build());

            // Second update with stale version (still 0) fails
            var stale = instance.toBuilder()
                    .status(WorkflowStatus.COMPLETED)
                    .updatedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS))
                    .build();

            var ex = assertThrows(OptimisticLockException.class,
                    () -> store.updateInstance(stale));
            assertEquals(0, ex.expectedVersion());
            assertEquals(1, ex.actualVersion());
        }

        @Test
        @DisplayName("updateInstance throws WorkflowNotFoundException when missing")
        void updateInstance_throwsWorkflowNotFoundException() {
            var phantom = newInstance("nonexistent-update");
            assertThrows(WorkflowNotFoundException.class,
                    () -> store.updateInstance(phantom));
        }
    }

    // ── Event Tests ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Event operations")
    class EventTests {

        @Test
        @DisplayName("appendEvent inserts an event")
        void appendEvent_insertsEvent() {
            var instance = newInstance("order-evt");
            store.createInstance(instance);

            var event = newEvent(instance.id(), 1, EventType.ACTIVITY_COMPLETED);
            store.appendEvent(event);

            var found = store.getEventBySequence(instance.id(), 1);
            assertTrue(found.isPresent());
            assertEquals(event.id(), found.get().id());
            assertEquals(EventType.ACTIVITY_COMPLETED, found.get().eventType());
        }

        @Test
        @DisplayName("appendEvent throws DuplicateEventException on same sequence")
        void appendEvent_throwsDuplicateEventException() {
            var instance = newInstance("order-dup-evt");
            store.createInstance(instance);

            store.appendEvent(newEvent(instance.id(), 1, EventType.ACTIVITY_COMPLETED));

            assertThrows(DuplicateEventException.class,
                    () -> store.appendEvent(newEvent(instance.id(), 1,
                            EventType.ACTIVITY_STARTED)));
        }

        @Test
        @DisplayName("getEventBySequence returns empty when not found")
        void getEventBySequence_returnsEmpty() {
            var instance = newInstance("order-no-evt");
            store.createInstance(instance);

            assertTrue(store.getEventBySequence(instance.id(), 99).isEmpty());
        }

        @Test
        @DisplayName("getEvents returns ordered by sequence number")
        void getEvents_returnsOrderedBySequence() {
            var instance = newInstance("order-evts");
            store.createInstance(instance);

            store.appendEvent(newEvent(instance.id(), 3, EventType.WORKFLOW_COMPLETED));
            store.appendEvent(newEvent(instance.id(), 1, EventType.WORKFLOW_STARTED));
            store.appendEvent(newEvent(instance.id(), 2, EventType.ACTIVITY_COMPLETED));

            var events = store.getEvents(instance.id());
            assertEquals(3, events.size());
            assertEquals(1, events.get(0).sequenceNumber());
            assertEquals(2, events.get(1).sequenceNumber());
            assertEquals(3, events.get(2).sequenceNumber());
        }

        @Test
        @DisplayName("getEvents returns empty list for unknown instance")
        void getEvents_returnsEmptyForUnknown() {
            assertTrue(store.getEvents(UUID.randomUUID()).isEmpty());
        }

        @Test
        @DisplayName("getEvents preserves JSON payload")
        void getEvents_preservesJsonPayload() {
            var instance = newInstance("order-json-evt");
            store.createInstance(instance);

            var event = new WorkflowEvent(
                    UUID.randomUUID(), instance.id(), 1,
                    EventType.ACTIVITY_COMPLETED, "reserveInventory",
                    jsonNode("{\"reserved\":true,\"count\":5}"),
                    Instant.now().truncatedTo(ChronoUnit.MILLIS));
            store.appendEvent(event);

            var found = store.getEventBySequence(instance.id(), 1).orElseThrow();
            assertNotNull(found.payload());
            assertTrue(found.payload().get("reserved").booleanValue());
            assertEquals(5, found.payload().get("count").intValue());
        }
    }

    // ── Signal Tests ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Signal operations")
    class SignalTests {

        @Test
        @DisplayName("saveSignal persists with null instanceId (pre-delivery)")
        void saveSignal_persistsWithNullInstanceId() {
            var signal = new WorkflowSignal(
                    UUID.randomUUID(), null, "order-pre", "payment.result",
                    jsonNode("{\"ok\":true}"), false,
                    Instant.now().truncatedTo(ChronoUnit.MILLIS));
            store.saveSignal(signal);

            var signals = store.getUnconsumedSignals("order-pre", "payment.result");
            assertEquals(1, signals.size());
            assertNull(signals.getFirst().workflowInstanceId());
        }

        @Test
        @DisplayName("saveSignal persists with instanceId")
        void saveSignal_persistsWithInstanceId() {
            var instance = newInstance("order-sig");
            store.createInstance(instance);

            store.saveSignal(newSignal("order-sig", instance.id(), "payment.result"));

            var signals = store.getUnconsumedSignals("order-sig", "payment.result");
            assertEquals(1, signals.size());
            assertEquals(instance.id(), signals.getFirst().workflowInstanceId());
        }

        @Test
        @DisplayName("getUnconsumedSignals filters consumed signals")
        void getUnconsumedSignals_filtersConsumed() {
            var instance = newInstance("order-consume");
            store.createInstance(instance);

            var s1 = newSignal("order-consume", instance.id(), "payment.result");
            var s2 = newSignal("order-consume", instance.id(), "payment.result");
            store.saveSignal(s1);
            store.saveSignal(s2);

            store.markSignalConsumed(s1.id());

            var signals = store.getUnconsumedSignals("order-consume", "payment.result");
            assertEquals(1, signals.size());
            assertEquals(s2.id(), signals.getFirst().id());
        }

        @Test
        @DisplayName("getUnconsumedSignals ordered by receivedAt ascending")
        void getUnconsumedSignals_orderedByReceivedAt() {
            var instance = newInstance("order-sig-order");
            store.createInstance(instance);

            var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            var later = new WorkflowSignal(
                    UUID.randomUUID(), instance.id(), "order-sig-order", "sig",
                    null, false, now.plusSeconds(10));
            var earlier = new WorkflowSignal(
                    UUID.randomUUID(), instance.id(), "order-sig-order", "sig",
                    null, false, now.minusSeconds(10));
            store.saveSignal(later);
            store.saveSignal(earlier);

            var signals = store.getUnconsumedSignals("order-sig-order", "sig");
            assertEquals(2, signals.size());
            assertEquals(earlier.id(), signals.get(0).id());
            assertEquals(later.id(), signals.get(1).id());
        }

        @Test
        @DisplayName("adoptOrphanedSignals sets instanceId on orphans only")
        void adoptOrphanedSignals_setsInstanceIdOnOrphans() {
            // Create two workflows with orphan signals
            var orphan1 = new WorkflowSignal(
                    UUID.randomUUID(), null, "order-adopt", "sig-a",
                    null, false, Instant.now().truncatedTo(ChronoUnit.MILLIS));
            var orphanOther = new WorkflowSignal(
                    UUID.randomUUID(), null, "order-other", "sig-b",
                    null, false, Instant.now().truncatedTo(ChronoUnit.MILLIS));
            store.saveSignal(orphan1);
            store.saveSignal(orphanOther);

            var instance = newInstance("order-adopt");
            store.createInstance(instance); // adopts orphan1, not orphanOther

            // orphan1 should be adopted
            var adopted = store.getUnconsumedSignals("order-adopt", "sig-a");
            assertEquals(1, adopted.size());
            assertEquals(instance.id(), adopted.getFirst().workflowInstanceId());

            // orphanOther should remain orphaned
            var notAdopted = store.getUnconsumedSignals("order-other", "sig-b");
            assertEquals(1, notAdopted.size());
            assertNull(notAdopted.getFirst().workflowInstanceId());
        }
    }

    // ── Timer Tests ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Timer operations")
    class TimerTests {

        @Test
        @DisplayName("saveTimer persists a timer")
        void saveTimer_persistsTimer() {
            var instance = newInstance("order-timer");
            store.createInstance(instance);

            var timer = newTimer(instance.id(), "order-timer",
                    Instant.now().plusSeconds(60), TimerStatus.PENDING);
            store.saveTimer(timer);

            // Verify via getDueTimers (set now to far future)
            var due = store.getDueTimers(Instant.now().plusSeconds(120), 10);
            assertEquals(1, due.size());
            assertEquals(timer.id(), due.getFirst().id());
        }

        @Test
        @DisplayName("getDueTimers returns only PENDING timers that are due")
        void getDueTimers_returnsOnlyPendingAndDue() {
            var instance = newInstance("order-due");
            store.createInstance(instance);

            var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

            // Due and PENDING — should be returned
            store.saveTimer(newTimer(instance.id(), "order-due",
                    now.minusSeconds(10), TimerStatus.PENDING));
            // Not yet due — should NOT be returned
            store.saveTimer(newTimer(instance.id(), "order-due",
                    now.plusSeconds(3600), TimerStatus.PENDING));
            // Due but already FIRED — should NOT be returned
            store.saveTimer(newTimer(instance.id(), "order-due",
                    now.minusSeconds(5), TimerStatus.FIRED));

            var due = store.getDueTimers(now, 10);
            assertEquals(1, due.size());
            assertEquals(TimerStatus.PENDING, due.getFirst().status());
        }

        @Test
        @DisplayName("getDueTimers respects batch size limit")
        void getDueTimers_respectsBatchSize() {
            var instance = newInstance("order-batch");
            store.createInstance(instance);

            var past = Instant.now().minusSeconds(60);
            for (int i = 0; i < 5; i++) {
                store.saveTimer(newTimer(instance.id(), "order-batch",
                        past.plusSeconds(i), TimerStatus.PENDING));
            }

            var due = store.getDueTimers(Instant.now(), 3);
            assertEquals(3, due.size());
        }

        @Test
        @DisplayName("getDueTimers ordered by fireAt ascending")
        void getDueTimers_orderedByFireAt() {
            var instance = newInstance("order-fire-order");
            store.createInstance(instance);

            var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            var t1 = newTimer(instance.id(), "order-fire-order",
                    now.minusSeconds(30), TimerStatus.PENDING);
            var t2 = newTimer(instance.id(), "order-fire-order",
                    now.minusSeconds(60), TimerStatus.PENDING);
            store.saveTimer(t1);
            store.saveTimer(t2);

            var due = store.getDueTimers(now, 10);
            assertEquals(2, due.size());
            assertEquals(t2.id(), due.get(0).id()); // older first
            assertEquals(t1.id(), due.get(1).id());
        }

        @Test
        @DisplayName("markTimerFired transitions PENDING to FIRED")
        void markTimerFired_transitionsPendingToFired() {
            var instance = newInstance("order-fire");
            store.createInstance(instance);

            var timer = newTimer(instance.id(), "order-fire",
                    Instant.now().minusSeconds(10), TimerStatus.PENDING);
            store.saveTimer(timer);

            assertTrue(store.markTimerFired(timer.id()));

            // Should no longer appear as due
            var due = store.getDueTimers(Instant.now(), 10);
            assertTrue(due.isEmpty());
        }

        @Test
        @DisplayName("markTimerFired returns false for already-fired timer")
        void markTimerFired_returnsFalseForAlreadyFired() {
            var instance = newInstance("order-double-fire");
            store.createInstance(instance);

            var timer = newTimer(instance.id(), "order-double-fire",
                    Instant.now().minusSeconds(10), TimerStatus.PENDING);
            store.saveTimer(timer);

            assertTrue(store.markTimerFired(timer.id()));
            assertFalse(store.markTimerFired(timer.id())); // already fired
        }

        @Test
        @DisplayName("markTimerFired returns false for cancelled timer")
        void markTimerFired_returnsFalseForCancelled() {
            var instance = newInstance("order-fire-cancel");
            store.createInstance(instance);

            var timer = newTimer(instance.id(), "order-fire-cancel",
                    Instant.now().minusSeconds(10), TimerStatus.PENDING);
            store.saveTimer(timer);

            store.markTimerCancelled(timer.id());
            assertFalse(store.markTimerFired(timer.id()));
        }

        @Test
        @DisplayName("markTimerCancelled transitions PENDING to CANCELLED")
        void markTimerCancelled_transitionsPendingToCancelled() {
            var instance = newInstance("order-cancel");
            store.createInstance(instance);

            var timer = newTimer(instance.id(), "order-cancel",
                    Instant.now().minusSeconds(10), TimerStatus.PENDING);
            store.saveTimer(timer);

            store.markTimerCancelled(timer.id());

            // Should no longer appear as due
            assertTrue(store.getDueTimers(Instant.now(), 10).isEmpty());
        }

        @Test
        @DisplayName("markTimerCancelled is idempotent")
        void markTimerCancelled_isIdempotent() {
            var instance = newInstance("order-cancel-idem");
            store.createInstance(instance);

            var timer = newTimer(instance.id(), "order-cancel-idem",
                    Instant.now().minusSeconds(10), TimerStatus.PENDING);
            store.saveTimer(timer);

            store.markTimerCancelled(timer.id());
            assertDoesNotThrow(() -> store.markTimerCancelled(timer.id()));
        }
    }

    // ── Concurrency Tests ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Concurrency")
    class ConcurrencyTests {

        @Test
        @DisplayName("concurrent updateInstance: one succeeds, one gets OptimisticLockException")
        void concurrentUpdateInstance_oneSucceedsOneGetsOptimisticLock() throws Exception {
            var instance = newInstance("order-concurrent");
            store.createInstance(instance);

            var latch = new CountDownLatch(1);
            var failureCount = new AtomicInteger(0);
            var error = new AtomicReference<Throwable>();

            // Thread 1: update with version 0
            var t1 = Thread.startVirtualThread(() -> {
                try {
                    latch.await();
                    store.updateInstance(instance.toBuilder()
                            .status(WorkflowStatus.WAITING_SIGNAL)
                            .updatedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS))
                            .build());
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    error.set(e);
                }
            });

            // Thread 2: also update with version 0
            var t2 = Thread.startVirtualThread(() -> {
                try {
                    latch.await();
                    store.updateInstance(instance.toBuilder()
                            .status(WorkflowStatus.COMPLETED)
                            .updatedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS))
                            .build());
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    error.set(e);
                }
            });

            latch.countDown();
            t1.join(5000);
            t2.join(5000);

            // Exactly one thread should have failed with OptimisticLockException
            assertEquals(1, failureCount.get(), "Exactly one update should fail");
            assertInstanceOf(OptimisticLockException.class, error.get());
        }

        @Test
        @DisplayName("concurrent getDueTimers with SKIP LOCKED returns disjoint results")
        void concurrentGetDueTimers_skipLocked_disjointResults() throws Exception {
            var instance = newInstance("order-skip-lock");
            store.createInstance(instance);

            var past = Instant.now().minusSeconds(60);
            for (int i = 0; i < 4; i++) {
                store.saveTimer(newTimer(instance.id(), "order-skip-lock",
                        past.plusSeconds(i), TimerStatus.PENDING));
            }

            // Connection A holds a transaction with 2 timers locked
            try (var connA = dataSource.getConnection()) {
                connA.setAutoCommit(false);
                try (var ps = connA.prepareStatement(
                        "SELECT id FROM maestro_workflow_timer "
                                + "WHERE fire_at <= ? AND status = 'PENDING' "
                                + "ORDER BY fire_at ASC LIMIT 2 "
                                + "FOR UPDATE SKIP LOCKED")) {
                    ps.setTimestamp(1, java.sql.Timestamp.from(Instant.now()));
                    var rs = ps.executeQuery();
                    int lockedCount = 0;
                    while (rs.next()) lockedCount++;
                    assertEquals(2, lockedCount);

                    // Connection B should only see the remaining 2
                    var remaining = store.getDueTimers(Instant.now(), 10);
                    assertEquals(2, remaining.size());
                } finally {
                    connA.rollback();
                }
            }
        }
    }
}
