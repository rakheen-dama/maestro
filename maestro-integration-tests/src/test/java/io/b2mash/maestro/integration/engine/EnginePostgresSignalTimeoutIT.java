package io.b2mash.maestro.integration.engine;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import io.b2mash.maestro.store.postgres.PostgresWorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The durable {@code awaitSignal} deadline (C12 / F4) across an executor
 * restart, on a real Postgres store.
 *
 * <p>A parked await persists its deadline as a timer row
 * {@code await-signal-<seq>} and memoizes it as {@code TIMER_SCHEDULED} at
 * {@code seq}; the outcome belongs at {@code seq + 1}. These pins prove the
 * two claims that only a restart can exercise: the recovered await resumes
 * from the <em>same</em> row and deadline (it is never re-armed from
 * {@code now()}), and a signal persisted while no node owned the workflow is
 * consumed on recovery rather than lost to the deadline.
 *
 * <p>The fixture's timeout is one hour, so nothing here waits for a deadline:
 * the row is made due with one SQL update and fired through
 * {@link MaestroEngineHarness#fireDueTimers(int)}.
 *
 * <h2>Thread Safety</h2>
 * <p>Per-test instance, confined to the JUnit test thread.
 */
@Tag("integration")
@DisplayName("Durable awaitSignal deadlines against a real Postgres store")
class EnginePostgresSignalTimeoutIT extends PostgresIntegrationSupport {

    private static final Duration BOUND = Duration.ofSeconds(15);
    private static final String TIMER_ID = "await-signal-1";

    private final List<MaestroEngineHarness> harnesses = new ArrayList<>();

    @AfterEach
    void closeHarnesses() {
        // close() is idempotent, so a harness a test already closed is safe here.
        harnesses.forEach(MaestroEngineHarness::close);
        harnesses.clear();
    }

    @Test
    @DisplayName("a parked await's deadline survives an executor restart and is not re-armed from now()")
    void deadlineSurvivesRestart_andIsNotReArmed() throws Exception {
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("await-durable");
        var a = newHarness(store); a.registerWorkflow(new TestWorkflows.PatientSignalWorkflow(Duration.ofHours(1)));
        var handle = a.start(workflowId, TestWorkflows.PatientSignalWorkflow.class, "seed");
        handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
        var row = store.findTimer(handle.instanceId(), TIMER_ID).orElseThrow();
        assertEquals(TimerStatus.PENDING, row.status());
        assertTrue(Duration.between(Instant.now(), row.fireAt()).compareTo(Duration.ofMinutes(55)) > 0);
        a.close();                                            // abandons the park; row and memo stay

        var b = newHarness(store); b.registerWorkflow(new TestWorkflows.PatientSignalWorkflow(Duration.ofHours(1)));
        assertEquals(1, b.recover());
        await().atMost(BOUND).until(() -> b.executor().isRunning(workflowId));
        assertEquals(row, store.findTimer(handle.instanceId(), TIMER_ID).orElseThrow(), "same row, same fireAt");
        assertEquals(1, countTimerRows(handle.instanceId()));

        makeDue(row.id());
        await().atMost(BOUND).pollInterval(Duration.ofMillis(50)).until(() -> b.fireDueTimers(10) == 1);
        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));
        assertEquals("timed-out", handle.result(String.class));
        assertEquals(List.of(EventType.TIMER_SCHEDULED, EventType.SIGNAL_TIMEOUT, EventType.WORKFLOW_COMPLETED),
                handle.events().stream().map(WorkflowEvent::eventType).toList());
        assertEquals(1, countTimerRows(handle.instanceId()), "still one deadline row after the timeout");
    }

    @Test
    @DisplayName("a signal delivered while no node owned the workflow is consumed on recovery, not timed out")
    void signalDuringDowntime_isConsumedOnRecovery() throws Exception {
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("await-downtime");
        var a = newHarness(store); a.registerWorkflow(new TestWorkflows.PatientSignalWorkflow(Duration.ofHours(1)));
        var handle = a.start(workflowId, TestWorkflows.PatientSignalWorkflow.class, "seed");
        handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
        a.close();                                            // no node owns the workflow now

        // Delivered by a bare store — no executor, no SignalManager, nothing to
        // unpark: the row is the only trace. It reuses the test's unpooled
        // PGSimpleDataSource (not Closeable; every connection the store opens
        // is closed by the call), so there is nothing extra to close.
        new PostgresWorkflowStore(dataSource, objectMapper).saveSignal(new WorkflowSignal(
                UUID.randomUUID(), handle.instanceId(), workflowId, TestWorkflows.PatientSignalWorkflow.SIGNAL,
                serializer.serialize("approved"), false, Instant.now()));

        var b = newHarness(store); b.registerWorkflow(new TestWorkflows.PatientSignalWorkflow(Duration.ofHours(1)));
        assertEquals(1, b.recover());
        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));
        assertEquals("approved", handle.result(String.class));
        assertEquals(List.of(EventType.TIMER_SCHEDULED, EventType.SIGNAL_RECEIVED, EventType.WORKFLOW_COMPLETED),
                handle.events().stream().map(WorkflowEvent::eventType).toList());
        var status = store.findTimer(handle.instanceId(), TIMER_ID).orElseThrow().status();
        assertTrue(Set.of(TimerStatus.CANCELLED, TimerStatus.FIRED).contains(status),
                "the deadline row must be spent once the signal won, never left PENDING — was " + status);
        assertEquals(1, countTimerRows(handle.instanceId()), "recovery must not write a second deadline row");
    }

    // ── helpers ────────────────────────────────────────────────────────

    /**
     * Builds a node over the given store, with a Postgres instance lock — a
     * closed node hands its lock back, so the next node adopts at once.
     *
     * @param backingStore the store the engine writes through
     * @return the harness, closed after the test if the test did not close it
     */
    private MaestroEngineHarness newHarness(WorkflowStore backingStore) {
        var harness = MaestroEngineHarness.builder(backingStore, objectMapper)
                .serviceName("await-node-" + (harnesses.size() + 1))
                .lock(newLock())
                .build();
        harnesses.add(harness);
        return harness;
    }

    /**
     * Counts every timer row of an instance, whatever its status — the SPI only
     * surfaces due pending rows or a lookup by timer id, neither of which can
     * prove a second row was never written.
     */
    private int countTimerRows(UUID instanceId) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT count(*) FROM maestro_workflow_timer WHERE workflow_instance_id = ?")) {
            ps.setObject(1, instanceId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Brings a timer row's {@code fire_at} forward to the database's {@code now()}. */
    private void makeDue(UUID timerDbId) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("UPDATE maestro_workflow_timer SET fire_at = now() WHERE id = ?")) {
            ps.setObject(1, timerDbId);
            assertEquals(1, ps.executeUpdate(), "the deadline row must exist to be made due");
        }
    }
}
