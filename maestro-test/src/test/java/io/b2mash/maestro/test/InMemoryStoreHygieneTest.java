package io.b2mash.maestro.test;

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
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The in-memory twins of {@code PostgresStoreHygieneTest}: the same fixtures
 * and the same assertions against {@link InMemoryWorkflowStore}, so the test
 * kit's store behaves as the shipped one does.
 */
class InMemoryStoreHygieneTest {

    private final InMemoryWorkflowStore store = new InMemoryWorkflowStore();
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private final Instant cutoff = now.minus(Duration.ofDays(7));

    /** The fixture of {@code PostgresStoreHygieneTest.ScopedJanitorDelete}. */
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
        void deletesOnlyThisServicesQualifyingSignalsAtMostLimitPerCall() {
            var deleted = IntStream.range(0, 4)
                    .map(i -> store.deleteUnconsumedSignalsForTerminalInstancesBefore("orders", cutoff, 2)).boxed().toList();
            assertEquals(List.of(2, 2, 1, 0), deleted);
            assertEquals(5, unconsumedOfService("billing"), "another service's signals are not this service's to delete");
            assertEquals(1, unconsumed(ordersFailed));
            assertEquals(1, unconsumed(ordersRecent));
            assertEquals(1, unconsumed(orphanWorkflowId));
            assertTrue(store.getAllSignals().stream().anyMatch(s -> s.id().equals(consumedSignalId) && s.consumed()));
        }

        @Test
        void rejectsANonPositiveLimit() {
            assertThrows(IllegalArgumentException.class,
                    () -> store.deleteUnconsumedSignalsForTerminalInstancesBefore("orders", cutoff, 0));
        }

        private void withUnconsumedSignal(WorkflowInstance instance) {
            store.saveSignal(newSignal(instance.workflowId(), instance.id()));
        }

        private int unconsumed(String workflowId) {
            return store.getUnconsumedSignals(workflowId, "s").size();
        }

        private long unconsumedOfService(String serviceName) {
            return store.getAllSignals().stream()
                    .filter(s -> !s.consumed() && s.workflowInstanceId() != null)
                    .filter(s -> store.getInstance(s.workflowId())
                            .filter(i -> i.serviceName().equals(serviceName)).isPresent())
                    .count();
        }
    }

    /** The fixture of {@code PostgresStoreHygieneTest.ScopedPurge}. */
    @Nested
    class ScopedPurge {

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
        void purgesOnlyThisServicesInstancesWithTheirHistory() {
            var result = store.purgeTerminalInstancesBefore("orders", WorkflowStatus.COMPLETED, cutoff, 500);
            assertEquals(new PurgeResult(3, 6, 6, 6), result);
            var billing = List.of("billing-c-0", "billing-c-1").stream()
                    .map(id -> store.getInstance(id).orElseThrow().id()).toList();
            assertEquals(4, billing.stream().mapToInt(id -> store.getEvents(id).size()).sum());
            assertEquals(4, store.getAllSignals().stream().filter(s -> billing.contains(s.workflowInstanceId())).count());
            assertEquals(4, store.getAllTimers().stream().filter(t -> billing.contains(t.workflowInstanceId())).count());
        }

        /**
         * A candidate whose row is replaced, after the candidate select and
         * before the purge takes its lock, by a same-status row of another
         * service is that service's now: the scoped purge re-checks the scope
         * under the lock and leaves it, with its history.
         */
        @Test
        void aCandidateMovedToAnotherServiceBeforeItsLockIsTakenIsNotPurged() throws Exception {
            var moved = finished("orders-m", "orders", WorkflowStatus.COMPLETED, 10);
            attachHistory(moved);
            var lock = instanceLock("orders-m");
            var result = new CompletableFuture<PurgeResult>();
            var purger = new Thread(() -> result.complete(
                    store.purgeTerminalInstancesBefore("orders", WorkflowStatus.COMPLETED, cutoff, 500)));
            synchronized (lock) {
                purger.start();
                assertEquals(Thread.State.BLOCKED, stateWithin(purger, Thread.State.BLOCKED, Duration.ofSeconds(5)),
                        "the purge selected its candidates and waits for the moved row's lock");
                store.updateInstance(moved.toBuilder().serviceName("billing").version(moved.version() + 1).build());
            }
            var purged = result.get(5, SECONDS);
            var id = moved.id();
            assertEquals(List.of(3, 1L, 2, 2L, 2L), List.of(purged.instances(),
                    store.getInstance("orders-m").stream().count(), store.getEvents(id).size(),
                    store.getAllSignals().stream().filter(s -> id.equals(s.workflowInstanceId())).count(),
                    store.getAllTimers().stream().filter(t -> id.equals(t.workflowInstanceId())).count()));
        }

        /** The store's per-workflow lock object, which its instance writes synchronise on. */
        private Object instanceLock(String workflowId) throws Exception {
            var field = InMemoryWorkflowStore.class.getDeclaredField("instanceLocks");
            field.setAccessible(true);
            return ((Map<?, ?>) field.get(store)).get(workflowId);
        }

        /** Polls {@code thread}'s state until it is {@code wanted} or {@code within} elapses; returns the last read. */
        private static Thread.State stateWithin(Thread thread, Thread.State wanted, Duration within) {
            var deadline = System.nanoTime() + within.toNanos();
            var state = thread.getState();
            while (state != wanted && System.nanoTime() < deadline) {
                Thread.onSpinWait();
                state = thread.getState();
            }
            return state;
        }

        /** Two events, a PENDING and a FIRED timer, an unconsumed and a consumed signal. */
        private void attachHistory(WorkflowInstance instance) {
            for (int seq = 1; seq <= 2; seq++) {
                store.appendEvent(new WorkflowEvent(UUID.randomUUID(), instance.id(), seq,
                        seq == 1 ? EventType.ACTIVITY_COMPLETED : EventType.WORKFLOW_COMPLETED, "step-" + seq,
                        JsonMapper.builder().build().readTree("{\"result\":\"ok\"}"), now));
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
    }

    /** The fixture of {@code PostgresStoreHygieneTest.ForeignServiceCounts}. */
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
                JsonMapper.builder().build().readTree("{\"data\":\"value\"}"), false, now);
    }
}
