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
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §C2's evidence requirement, end to end on real PostgreSQL: a raw event
 * row carrying a <em>future</em> type string is injected by SQL into a parked
 * workflow's history; the node that adopts it for recovery <b>stands down</b>;
 * and the same instance is adopted and <b>completes</b> once that row is gone.
 *
 * <h2>What "a future type" means here</h2>
 * <p>The injected type is {@code EVT_FROM_A_NEWER_MAESTRO} — a string no build
 * of this repository will ever define (coordinator RULING 1). The spec's
 * SHOULD-clause about reusing {@code VERSION_MARKER} is discharged by that
 * ruling: versioning and stand-down ship in the same release, so
 * {@code VERSION_MARKER} is a <em>known</em> type to the very build under test
 * and could not exercise this path at all once merged.
 *
 * <h2>The failure this prevents</h2>
 * <p>Node A runs version N+1 and writes a new event type. Node B is still on
 * version N; its recovery poller adopts one of those workflows, and its row
 * mapper meets a type string it does not define. Before this mechanism, the
 * resulting exception reached {@code executeWorkflow} looking exactly like a
 * workflow failure — so the workflow was durably marked {@code FAILED} and its
 * sagas compensated, reversing real work for a workflow that never failed and
 * was, on node A, perfectly healthy.
 *
 * <p>Every assertion below is therefore a positive fact about what node B left
 * alone (status, event log, lock) plus the two things it must do (emit
 * {@code standDown}, release the instance lock) and the one thing that proves
 * the workflow was never harmed (it completes afterwards).
 */
@Tag("integration")
@DisplayName("A node that cannot read a workflow's history stands down and leaves it adoptable")
class UnknownEventStandDownIT extends PostgresIntegrationSupport {

    /** A type string no build of this repository will ever define (RULING 1). */
    private static final String FUTURE_TYPE = "EVT_FROM_A_NEWER_MAESTRO";

    private static final Duration BOUND = Duration.ofSeconds(30);
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);

    /**
     * {@link TestWorkflows.SignalWorkflow} parks on {@code awaitSignal} at
     * sequence 2 (sequence 1 is its pre-park activity), so 2 is the slot the
     * recovering node's replay reads next — where the future event goes.
     */
    private static final int PARKED_AWAIT_SEQUENCE = 2;

    @Test
    @DisplayName("recovery stands down — status unchanged, zero compensations, lock free, observer "
            + "fired — and the same instance completes once the row is removed")
    void futureEventStandsDownThenTheInstanceCompletes() throws Exception {
        var recorder = new CountingActivities.Recorder();
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("standdown");

        // ── Node A (any version): run the workflow to its park ─────────────
        var parked = new CountDownLatch(1);
        WorkflowHandle handle = null;
        try (var nodeA = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName("node-a")
                .lock(newLock())
                .instanceLockTtl(LOCK_TTL)
                .build()) {
            nodeA.registerActivities(ChainActivities.class,
                    new CountingActivities.RecordingChainActivities(recorder));
            nodeA.registerWorkflow(new TestWorkflows.SignalWorkflow(parked));
            handle = nodeA.start(workflowId, TestWorkflows.SignalWorkflow.class, "seed");
            assertTrue(parked.await(30, TimeUnit.SECONDS));
            handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
        }
        var instanceId = handle.instanceId();

        // ── A newer node writes an event type this build does not define ───
        insertRawEvent(instanceId, PARKED_AWAIT_SEQUENCE, FUTURE_TYPE);
        assertEquals(EventType.UNKNOWN,
                store.getEventBySequence(instanceId, PARKED_AWAIT_SEQUENCE).orElseThrow().eventType(),
                "the store's read path must degrade the future type to the sentinel, not throw");

        var beforeStatus = handle.status();
        var logBefore = fingerprint(instanceId);

        // ── Node B (this build): adopts it, cannot read it, stands down ────
        var observer = new RecordingObserver();
        try (var nodeB = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName("node-b")
                .lock(newLock())
                .instanceLockTtl(LOCK_TTL)
                .observer(observer)
                .build()) {
            nodeB.registerActivities(ChainActivities.class,
                    new CountingActivities.RecordingChainActivities(recorder));
            nodeB.registerWorkflow(new TestWorkflows.SignalWorkflow(new CountDownLatch(1)));

            assertEquals(1, nodeB.recover(),
                    "the parked workflow must still be adopted — a node cannot know a "
                            + "history is unreadable until it reads it");

            await().atMost(BOUND).until(() -> !observer.standDowns().isEmpty());
            await().atMost(BOUND).until(() -> !nodeB.executor().isRunning(workflowId));

            var standDowns = observer.standDowns();
            assertAll("the stand-down itself",
                    () -> assertEquals(1, standDowns.size(),
                            "exactly one stand-down for one recovery pass: "
                                    + observer.callbackNames()),
                    () -> assertEquals(StandDownReason.UNKNOWN_EVENT_TYPE,
                            standDowns.getFirst().reason(),
                            "the reason tag is the operator's signal that a mixed-version "
                                    + "fleet is lingering — it must not be STALE_RUN"),
                    () -> assertEquals(workflowId, standDowns.getFirst().workflowId()),
                    () -> assertEquals("seq=" + PARKED_AWAIT_SEQUENCE,
                            standDowns.getFirst().detail()),
                    () -> assertTrue(observer.failed().isEmpty(),
                            "workflowFailed must NOT fire"),
                    () -> assertTrue(observer.compensating().isEmpty(),
                            "workflowCompensating must NOT fire"));

            var after = store.getInstance(workflowId).orElseThrow();
            assertAll("durable state the stand-down must have left alone",
                    () -> assertEquals(beforeStatus, after.status(),
                            "the instance status must be byte-identical to what node B found"),
                    () -> assertEquals(WorkflowStatus.WAITING_SIGNAL, after.status()),
                    () -> assertNotEquals(WorkflowStatus.FAILED, after.status(),
                            "an unreadable history is NEVER a workflow failure"),
                    () -> assertEquals(0, countOfType(instanceId, EventType.COMPENSATION_STARTED),
                            "zero COMPENSATION_STARTED events"),
                    () -> assertEquals(0,
                            countOfType(instanceId, EventType.COMPENSATION_STEP_COMPLETED)),
                    () -> assertEquals(0,
                            countOfType(instanceId, EventType.COMPENSATION_STEP_FAILED)),
                    () -> assertEquals(0, countOfType(instanceId, EventType.WORKFLOW_FAILED)),
                    () -> assertEquals(logBefore, fingerprint(instanceId),
                            "the event log must be unchanged — a stand-down writes nothing"));

            assertFalse(lockRowHeld(workflowId),
                    "the instance lock must be released as the stand-down unwinds — a held "
                            + "lock keeps every upgraded node out until the TTL expires");
        }

        // ── The upgraded node's world: the row is no longer in the way ─────
        deleteEvent(instanceId, PARKED_AWAIT_SEQUENCE);

        var completionParked = new CountDownLatch(1);
        try (var nodeC = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName("node-c")
                .lock(newLock())
                .instanceLockTtl(LOCK_TTL)
                .build()) {
            nodeC.registerActivities(ChainActivities.class,
                    new CountingActivities.RecordingChainActivities(recorder));
            nodeC.registerWorkflow(new TestWorkflows.SignalWorkflow(completionParked));

            assertEquals(1, nodeC.recover(),
                    "the stood-down instance must still be adoptable — that is the whole "
                            + "point of writing nothing");
            assertTrue(completionParked.await(30, TimeUnit.SECONDS),
                    "the recovered run must reach its await point");
            handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);

            nodeC.deliverSignal(workflowId, TestWorkflows.SignalWorkflow.SIGNAL, "approved");

            assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND),
                    "the workflow node B could not read completes on a node that can — "
                            + "proof that standing down harmed nothing");
        }
    }

    @Test
    @DisplayName("re-adoption churn is visible, not hidden: each recovery pass stands down again")
    void everyRecoveryPassStandsDownAgain() throws Exception {
        var recorder = new CountingActivities.Recorder();
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("standdown-churn");

        var parked = new CountDownLatch(1);
        WorkflowHandle handle = null;
        try (var nodeA = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName("churn-a")
                .lock(newLock())
                .instanceLockTtl(LOCK_TTL)
                .build()) {
            nodeA.registerActivities(ChainActivities.class,
                    new CountingActivities.RecordingChainActivities(recorder));
            nodeA.registerWorkflow(new TestWorkflows.SignalWorkflow(parked));
            handle = nodeA.start(workflowId, TestWorkflows.SignalWorkflow.class, "seed");
            assertTrue(parked.await(30, TimeUnit.SECONDS));
            handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
        }
        insertRawEvent(handle.instanceId(), PARKED_AWAIT_SEQUENCE, FUTURE_TYPE);

        var observer = new RecordingObserver();
        try (var nodeB = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName("churn-b")
                .lock(newLock())
                .instanceLockTtl(LOCK_TTL)
                .observer(observer)
                .build()) {
            nodeB.registerActivities(ChainActivities.class,
                    new CountingActivities.RecordingChainActivities(recorder));
            nodeB.registerWorkflow(new TestWorkflows.SignalWorkflow(new CountDownLatch(1)));

            for (int pass = 1; pass <= 3; pass++) {
                var expected = pass;
                assertEquals(1, nodeB.recover(),
                        "pass " + pass + ": the instance must still be adoptable, which is "
                                + "exactly why the churn exists");
                await().atMost(BOUND).until(() -> observer.standDowns().size() == expected);
            }

            assertEquals(3, observer.standDowns().size(),
                    "one stand-down per recovery pass — a steadily rising "
                            + "maestro.standdown{reason=unknown_event_type} IS the operator "
                            + "signal that a mixed fleet is lingering, so it must not be "
                            + "deduplicated away");
            assertTrue(observer.standDowns().stream()
                            .allMatch(s -> s.reason() == StandDownReason.UNKNOWN_EVENT_TYPE),
                    "every pass reports the same reason");
            assertEquals(WorkflowStatus.WAITING_SIGNAL, handle.status(),
                    "three stand-downs later the durable state is still untouched");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** Writes a row whose {@code event_type} this build does not define. */
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

    private void deleteEvent(UUID instanceId, int seq) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "DELETE FROM maestro_workflow_event WHERE workflow_instance_id = ? "
                             + "AND sequence_number = ?")) {
            ps.setObject(1, instanceId);
            ps.setInt(2, seq);
            assertEquals(1, ps.executeUpdate(), "the injected row must have been removed");
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

    private long countOfType(UUID instanceId, EventType type) {
        return store.getEvents(instanceId).stream().filter(e -> e.eventType() == type).count();
    }

    /** A comparable fingerprint of the durable log: sequence, type, step. */
    private List<String> fingerprint(UUID instanceId) {
        return store.getEvents(instanceId).stream()
                .map(e -> "%d:%s:%s".formatted(e.sequenceNumber(), e.eventType(), e.stepName()))
                .toList();
    }
}
