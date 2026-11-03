package io.b2mash.maestro.integration.engine;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.StandDownReason;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.support.RecordingObserver;
import io.b2mash.maestro.integration.support.WorkflowHandle;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.CountingActivities.ChainActivities;
import io.b2mash.maestro.integration.workflows.CountingActivities.SagaActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C10 / F3 end to end on real PostgreSQL: a workflow whose recorded history no
 * longer matches the code (a step renamed, a slot now taken by a different
 * operation) <b>stands down</b> as {@link StandDownReason#NONDETERMINISTIC_HISTORY}
 * on the node that adopts it — writing nothing, failing nothing, compensating
 * nothing, leaking no timer row — and is finished normally once the history
 * and the code agree again (the operator rolled the deploy back).
 *
 * <p>The "code change" is simulated by editing the recorded row in SQL rather
 * than by deploying a second build: from the replaying node's point of view the
 * two are indistinguishable — the step name or type it reads at a sequence is
 * not the one its code performs there.
 */
@Tag("integration")
@DisplayName("Replay drift stands down on a real store and recovers once history matches the code")
class ReplayDriftStandDownIT extends PostgresIntegrationSupport {

    private static final Duration BOUND = Duration.ofSeconds(30);
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);

    private final CountDownLatch reachedRelease = new CountDownLatch(1);
    private final CountDownLatch wedge = new CountDownLatch(1);
    private @Nullable MaestroEngineHarness wedgedNode;

    @AfterEach
    void releaseWedge() {
        wedge.countDown();
        if (wedgedNode != null) {
            wedgedNode.close();
            wedgedNode = null;
        }
    }

    @Test
    @DisplayName("a renamed activity stands down — nothing re-executed, nothing written — and the "
            + "instance completes once the name matches again")
    void renamedStepStandsDownThenCompletesOnceHistoryMatchesAgain() throws Exception {
        var recorder = new CountingActivities.Recorder();
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("drift-rename");

        // ── Node A: SignalWorkflow to its park. seq 1 = stepOne ACTIVITY_COMPLETED.
        var parked = new CountDownLatch(1);
        WorkflowHandle handle;
        try (var nodeA = signalNode("drift-a", recorder, parked, null)) {
            handle = nodeA.start(workflowId, TestWorkflows.SignalWorkflow.class, "seed");
            assertTrue(parked.await(30, TimeUnit.SECONDS));
            handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
        }
        var instanceId = handle.instanceId();
        assertEquals(1, recorder.count("stepOne"));

        var original = store.getEventBySequence(instanceId, 1).orElseThrow().stepName();
        setStepName(instanceId, 1, original + "-renamed");     // "the code was refactored under this history"
        var beforeStatus = handle.status();
        var logBefore = fingerprint(instanceId);

        // ── Node B (the "new code"): adopts, replays seq 1, stands down ─────
        var observer = new RecordingObserver();
        try (var nodeB = signalNode("drift-b", recorder, new CountDownLatch(1), observer)) {
            assertEquals(1, nodeB.recover());
            await().atMost(BOUND).until(() -> !observer.standDowns().isEmpty());
            await().atMost(BOUND).until(() -> !nodeB.executor().isRunning(workflowId));

            var call = observer.standDowns().getFirst();
            assertAll(
                    () -> assertEquals(1, observer.standDowns().size(), observer.callbackNames().toString()),
                    () -> assertEquals(StandDownReason.NONDETERMINISTIC_HISTORY, call.reason()),
                    () -> assertEquals("seq=1 expected=" + original + " stored=" + original
                            + "-renamed type=ACTIVITY_COMPLETED", call.detail()),
                    () -> assertTrue(observer.failed().isEmpty()),
                    () -> assertTrue(observer.compensating().isEmpty()),
                    () -> assertEquals(beforeStatus, store.getInstance(workflowId).orElseThrow().status()),
                    () -> assertEquals(logBefore, fingerprint(instanceId), "a stand-down writes nothing"),
                    () -> assertEquals(1, recorder.count("stepOne"), "the activity was NOT re-executed"),
                    () -> assertFalse(lockRowHeld(workflowId)));
        }

        // ── Rollback: the history matches the code again ────────────────────
        setStepName(instanceId, 1, original);

        var completionParked = new CountDownLatch(1);
        try (var nodeC = signalNode("drift-c", recorder, completionParked, null)) {
            assertEquals(1, nodeC.recover());
            assertTrue(completionParked.await(30, TimeUnit.SECONDS),
                    "the recovered run must reach its await point");
            handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
            nodeC.deliverSignal(workflowId, TestWorkflows.SignalWorkflow.SIGNAL, "approved");
            assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));
        }
        assertEquals(1, recorder.count("stepOne"), "replayed on node C, never re-executed");
    }

    @Test
    @DisplayName("sleep() on a slot another operation recorded leaks no timer row across recovery "
            + "passes")
    void sleepOnOccupiedSlot_leaksNoTimerRowAcrossRecoveryPasses() throws Exception {   // Review Focus 5
        var recorder = new CountingActivities.Recorder();
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("drift-sleep");

        WorkflowHandle handle;
        try (var nodeA = sleepNode("drift-sleep-a", recorder, null)) {
            handle = nodeA.start(workflowId, TestWorkflows.SleepingWorkflow.class, "seed");
            handle.awaitStatus(WorkflowStatus.WAITING_TIMER, BOUND);
        }
        var instanceId = handle.instanceId();
        assertEquals(1, timerRowCount(instanceId), "the one PENDING row the live sleep wrote");

        var stepOne = store.getEventBySequence(instanceId, 1).orElseThrow().stepName();
        overwriteAsActivity(instanceId, 2, stepOne);   // history: "an activity ran at seq 2"; code: sleep

        var observer = new RecordingObserver();
        try (var nodeB = sleepNode("drift-sleep-b", recorder, observer)) {
            for (int pass = 1; pass <= 3; pass++) {
                var expected = pass;
                assertEquals(1, nodeB.recover(), "pass " + pass);
                await().atMost(BOUND).until(() -> observer.standDowns().size() == expected);
                await().atMost(BOUND).until(() -> !nodeB.executor().isRunning(workflowId));
                assertEquals(1, timerRowCount(instanceId),
                        "pass " + pass + ": no timer row may be inserted before the stand-down");
            }
            assertTrue(observer.standDowns().stream()
                            .allMatch(s -> s.reason() == StandDownReason.NONDETERMINISTIC_HISTORY),
                    "the pre-fix reason was STALE_RUN — after the leaked insert and the duplicate "
                            + "append: " + observer.standDowns());
            assertEquals("seq=2 expected=$maestro:sleep stored=" + stepOne + " type=ACTIVITY_COMPLETED",
                    observer.standDowns().getFirst().detail());
            assertEquals(WorkflowStatus.WAITING_TIMER, store.getInstance(workflowId).orElseThrow().status());
        }
    }

    @Test
    @DisplayName("a compensation entry renamed under a COMPENSATING saga stands down without re-running "
            + "it, and the saga finishes FAILED once the name matches again")
    void renamedCompensationEntryStandsDownThenCompensatesOnceHistoryMatchesAgain() throws Exception {
        // ── Node A (no lock): reserve, charge, ship fails; sequential LIFO
        // compensation runs refund (entry 0, block 5000) and wedges inside
        // releaseReservation (entry 1, block 6000) — a node frozen mid-saga.
        var recorderA = new CountingActivities.Recorder();
        wedgedNode = sagaNode("drift-saga-a", new WedgingRelease(
                new CountingActivities.RecordingSagaActivities(recorderA, "ship")), false, null);
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("drift-saga");
        var handle = wedgedNode.start(workflowId, TestWorkflows.SagaWorkflow.class, "widget");

        assertTrue(reachedRelease.await(30, TimeUnit.SECONDS), "node A never reached entry 1");
        var instanceId = handle.instanceId();
        await().atMost(BOUND).until(() -> store.getEventBySequence(instanceId, 5000).isPresent());
        assertEquals(WorkflowStatus.COMPENSATING, handle.status());
        assertEquals(1, recorderA.count("refund"));
        var entry0 = store.getEventBySequence(instanceId, 5000).orElseThrow();
        assertEquals(EventType.COMPENSATION_STEP_COMPLETED, entry0.eventType());
        var original = entry0.stepName();

        setStepName(instanceId, 5000, original + "-renamed");
        var logBefore = fingerprint(instanceId);

        // ── Node B: replays forward, reaches the entry-0 guard slot, stands down
        var recorderB = new CountingActivities.Recorder();
        var observer = new RecordingObserver();
        try (var nodeB = sagaNode("drift-saga-b",
                new CountingActivities.RecordingSagaActivities(recorderB, "ship"), true, observer)) {
            assertEquals(1, nodeB.recover());
            await().atMost(BOUND).until(() -> !observer.standDowns().isEmpty());
            await().atMost(BOUND).until(() -> !nodeB.executor().isRunning(workflowId));

            var call = observer.standDowns().getFirst();
            assertAll(
                    () -> assertEquals(1, observer.standDowns().size(), observer.callbackNames().toString()),
                    () -> assertEquals(StandDownReason.NONDETERMINISTIC_HISTORY, call.reason()),
                    () -> assertEquals("seq=5000 expected=" + original + " stored=" + original
                            + "-renamed type=COMPENSATION_STEP_COMPLETED", call.detail()),
                    () -> assertEquals(0, recorderB.count("refund"), "no compensation re-run"),
                    () -> assertEquals(0, recorderB.count("releaseReservation"),
                            "the stand-down precedes every entry, not just the drifted one"),
                    () -> assertEquals(0, recorderB.count("ship"), "ship's failure replays"),
                    () -> assertTrue(observer.failed().isEmpty()),
                    () -> assertEquals(WorkflowStatus.COMPENSATING,
                            store.getInstance(workflowId).orElseThrow().status()),
                    () -> assertEquals(logBefore, fingerprint(instanceId), "a stand-down writes nothing"));
        }

        // ── Rollback: restore the name; node C finishes the compensation ────
        setStepName(instanceId, 5000, original);
        var recorderC = new CountingActivities.Recorder();
        var observerC = new RecordingObserver();
        try (var nodeC = sagaNode("drift-saga-c",
                new CountingActivities.RecordingSagaActivities(recorderC, "ship"), true, observerC)) {
            assertEquals(1, nodeC.recover());
            assertEquals(WorkflowStatus.FAILED, awaitTerminal(workflowId, BOUND).status(),
                    "the compensated saga finishes FAILED");
            assertAll(
                    () -> assertEquals(List.of(), observerC.standDowns()),
                    () -> assertEquals(0, recorderC.count("refund"), "entry 0 replays from its record"),
                    () -> assertEquals(1, recorderC.count("releaseReservation"),
                            "entry 1, interrupted on node A, runs once"));
        }
    }

    // ── nodes ─────────────────────────────────────────────────────────────

    private MaestroEngineHarness signalNode(String serviceName, CountingActivities.Recorder recorder,
                                            CountDownLatch parked, @Nullable RecordingObserver observer) {
        var builder = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName(serviceName)
                .lock(newLock())
                .instanceLockTtl(LOCK_TTL);
        if (observer != null) {
            builder.observer(observer);
        }
        var node = builder.build();
        node.registerActivities(ChainActivities.class, new CountingActivities.RecordingChainActivities(recorder));
        node.registerWorkflow(TestWorkflows.SignalWorkflow.class, () -> new TestWorkflows.SignalWorkflow(parked));
        return node;
    }

    private MaestroEngineHarness sleepNode(String serviceName, CountingActivities.Recorder recorder,
                                           @Nullable RecordingObserver observer) {
        var builder = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName(serviceName)
                .lock(newLock())
                .instanceLockTtl(LOCK_TTL);
        if (observer != null) {
            builder.observer(observer);
        }
        var node = builder.build();
        node.registerActivities(ChainActivities.class, new CountingActivities.RecordingChainActivities(recorder));
        node.registerWorkflow(TestWorkflows.SleepingWorkflow.class, () -> new TestWorkflows.SleepingWorkflow(Duration.ofHours(1)));
        return node;
    }

    private MaestroEngineHarness sagaNode(String serviceName, SagaActivities activities, boolean withLock,
                                          @Nullable RecordingObserver observer) {
        var builder = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName(serviceName)
                .lock(withLock ? newLock() : null)
                .instanceLockTtl(Duration.ofSeconds(2));
        if (observer != null) {
            builder.observer(observer);
        }
        var node = builder.build();
        node.registerActivities(SagaActivities.class, activities);
        node.registerWorkflow(TestWorkflows.SagaWorkflow.class);
        return node;
    }

    /**
     * Decorator whose {@code releaseReservation} signals arrival and then blocks
     * until the test opens the wedge — a node frozen inside compensation entry 1.
     *
     * <p>Thread-safe: the latches carry the happens-before edge.
     */
    private final class WedgingRelease implements SagaActivities {

        private final SagaActivities delegate;

        WedgingRelease(SagaActivities delegate) {
            this.delegate = delegate;
        }

        @Override
        public String reserve(String item) {
            return delegate.reserve(item);
        }

        @Override
        public void releaseReservation(String item) {
            reachedRelease.countDown();
            try {
                if (!wedge.await(60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Wedge was never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while wedged", e);
            }
            delegate.releaseReservation(item);
        }

        @Override
        public String charge(String item) {
            return delegate.charge(item);
        }

        @Override
        public void refund(String item) {
            delegate.refund(item);
        }

        @Override
        public String ship(String item) {
            return delegate.ship(item);
        }
    }

    // ── SQL helpers ───────────────────────────────────────────────────────

    private void setStepName(UUID instanceId, int seq, String stepName) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "UPDATE maestro_workflow_event SET step_name = ? "
                             + "WHERE workflow_instance_id = ? AND sequence_number = ?")) {
            ps.setString(1, stepName);
            ps.setObject(2, instanceId);
            ps.setInt(3, seq);
            assertEquals(1, ps.executeUpdate());
        }
    }

    private void overwriteAsActivity(UUID instanceId, int seq, String stepName) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "UPDATE maestro_workflow_event SET event_type = 'ACTIVITY_COMPLETED', step_name = ?, "
                             + "payload = '\"x\"'::jsonb "
                             + "WHERE workflow_instance_id = ? AND sequence_number = ?")) {
            ps.setString(1, stepName);
            ps.setObject(2, instanceId);
            ps.setInt(3, seq);
            assertEquals(1, ps.executeUpdate());
        }
    }

    private int timerRowCount(UUID instanceId) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM maestro_workflow_timer WHERE workflow_instance_id = ?")) {
            ps.setObject(1, instanceId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** @return whether the Postgres lock backend still holds this instance's lock */
    private boolean lockRowHeld(String workflowId) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM maestro_distributed_lock "
                             + "WHERE lock_key = ? AND expires_at > now()")) {
            ps.setString(1, "maestro:lock:workflow:" + workflowId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /** A comparable fingerprint of the durable log: sequence, type, step. */
    private List<String> fingerprint(UUID instanceId) {
        return store.getEvents(instanceId).stream()
                .map(e -> "%d:%s:%s".formatted(e.sequenceNumber(), e.eventType(), e.stepName()))
                .toList();
    }
}
