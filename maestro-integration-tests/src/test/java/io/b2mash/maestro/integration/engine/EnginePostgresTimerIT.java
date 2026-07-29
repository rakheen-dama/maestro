package io.b2mash.maestro.integration.engine;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.CountingActivities.ChainActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Durable timers driven by the real engine against a real Postgres store:
 * {@code sleep()} persisting a timer row, the {@code TimerPoller} firing it out
 * of Postgres, the {@code markTimerFired} compare-and-set, and a timer that
 * outlives the executor that scheduled it.
 *
 * <h2>Timing</h2>
 * <p>The engine has no injectable clock, so naps are sub-second and the poller
 * runs at 200ms. Awaitility bounds are deliberately generous — a wide bound on a
 * fast condition costs nothing and is what keeps CI stable.
 */
@Tag("integration")
@DisplayName("Durable timers against a real Postgres store")
class EnginePostgresTimerIT extends PostgresIntegrationSupport {

    private static final Duration BOUND = Duration.ofSeconds(15);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);
    private static final Duration NAP = Duration.ofMillis(300);

    private @Nullable MaestroEngineHarness harness;
    private @Nullable MaestroEngineHarness secondHarness;
    private CountingActivities.Recorder recorder;

    @BeforeEach
    void newRecorder() {
        recorder = new CountingActivities.Recorder();
    }

    @AfterEach
    void closeHarnesses() {
        if (secondHarness != null) {
            secondHarness.close();
            secondHarness = null;
        }
        if (harness != null) {
            harness.close();
            harness = null;
        }
    }

    @Test
    @DisplayName("sleep persists a pending timer row and parks the workflow in WAITING_TIMER")
    void sleep_persistsPendingTimerAndParksWorkflow() throws Exception {
        harness = newHarness("timer-node", true);
        registerSleeper(harness, NAP);

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("timer-persist");
        var handle = harness.start(workflowId, TestWorkflows.SleepingWorkflow.class, "seed");

        handle.awaitStatus(WorkflowStatus.WAITING_TIMER, BOUND);

        // No poller runs in this test, so the timer stays PENDING however long
        // its fireAt has passed — the row, not a thread, is the durable state.
        var timer = readSingleTimerRow(workflowId);
        assertEquals(TimerStatus.PENDING, timer.status());
        assertEquals(handle.instanceId(), timer.workflowInstanceId());
        // stepOne takes sequence 1, so the sleep is sequence 2.
        assertEquals("sleep-2", timer.timerId());

        var scheduled = eventsOfType(handle.events(), EventType.TIMER_SCHEDULED);
        assertEquals(1, scheduled.size(), "expected exactly one TIMER_SCHEDULED event");
        assertEquals("sleep-2", scheduled.getFirst().payload().get("timerId").stringValue());
        assertEquals(0, eventsOfType(handle.events(), EventType.TIMER_FIRED).size(),
                "TIMER_FIRED must not be written before the timer fires");
        assertEquals(1, recorder.count("stepOne"));
        assertEquals(0, recorder.count("stepTwo"), "the workflow must be parked before stepTwo");

        // Draining the due timers directly releases the workflow without a poller.
        fireWhenDue(harness);
        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));
        assertEquals("seed-one-two", handle.result(String.class));
        assertEquals(TimerStatus.FIRED, readSingleTimerRow(workflowId).status());
        assertEquals(1, eventsOfType(handle.events(), EventType.TIMER_FIRED).size());
    }

    @Test
    @DisplayName("the timer poller fires a due timer read from Postgres and resumes the workflow")
    void timerPoller_firesDueTimerFromPostgres() throws Exception {
        harness = newHarness("timer-node", true);
        registerSleeper(harness, NAP);
        harness.startTimerPoller(POLL_INTERVAL, 10);

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("timer-poller");
        var handle = harness.start(workflowId, TestWorkflows.SleepingWorkflow.class, "seed");

        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));
        assertEquals("seed-one-two", handle.result(String.class));

        assertEquals(TimerStatus.FIRED, readSingleTimerRow(workflowId).status(),
                "the poller must transition the row, not just unpark the thread");
        assertEquals(1, eventsOfType(handle.events(), EventType.TIMER_FIRED).size());
        assertEquals(1, recorder.count("stepOne"));
        assertEquals(1, recorder.count("stepTwo"));
    }

    @Test
    @DisplayName("markTimerFired is a compare-and-set: only the first caller wins")
    void markTimerFired_onlyTransitionsOnce() throws Exception {
        harness = newHarness("timer-node", true);
        registerSleeper(harness, NAP);

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("timer-cas");
        var handle = harness.start(workflowId, TestWorkflows.SleepingWorkflow.class, "seed");
        handle.awaitStatus(WorkflowStatus.WAITING_TIMER, BOUND);

        var timerDbId = readSingleTimerRow(workflowId).id();

        // The first fire wins the CAS and unparks the workflow.
        fireWhenDue(harness);
        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));

        // A duplicate fire — the poller redelivering, or a second node — loses.
        assertFalse(store.markTimerFired(timerDbId),
                "a FIRED timer must never transition again");
        assertEquals(0, harness.fireDueTimers(10),
                "a fired timer must no longer be returned as due");
        assertEquals(1, eventsOfType(handle.events(), EventType.TIMER_FIRED).size(),
                "a duplicate fire must not append a second TIMER_FIRED event");
    }

    @Test
    @DisplayName("a timer scheduled by one executor is fired and completed by a second executor over the same store")
    void timerSurvivesExecutorRestart_secondExecutorFiresIt() throws Exception {
        // Modelled without a lock backend: the first harness is abandoned rather
        // than closed (closing it would cancel the parked sleep, which is the
        // known shutdown defect tracked for P5), and an abandoned harness would
        // keep renewing the instance lock forever. A genuinely dead node stops
        // renewing and its lock expires by TTL — that path belongs to P2.
        harness = newHarness("node-a", false);
        registerSleeper(harness, NAP);

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("timer-restart");
        var handle = harness.start(workflowId, TestWorkflows.SleepingWorkflow.class, "seed");
        handle.awaitStatus(WorkflowStatus.WAITING_TIMER, BOUND);
        assertEquals(1, recorder.count("stepOne"));

        // node-a is now gone. Everything the workflow needs is in Postgres.
        secondHarness = newHarness("node-b", false);
        registerSleeper(secondHarness, NAP);

        assertEquals(1, secondHarness.recover(),
                "node-b must adopt the WAITING_TIMER instance left behind by node-a");
        secondHarness.startTimerPoller(POLL_INTERVAL, 10);

        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));
        assertEquals("seed-one-two", handle.result(String.class));

        // Replay served stepOne from the event log; only stepTwo ran on node-b.
        assertEquals(1, recorder.count("stepOne"),
                "the pre-sleep activity must replay, not re-execute, on the second executor");
        assertEquals(1, recorder.count("stepTwo"));
        assertEquals(TimerStatus.FIRED, readSingleTimerRow(workflowId).status());
        assertEquals(1, eventsOfType(handle.events(), EventType.TIMER_FIRED).size());
    }

    @Test
    @DisplayName("a timer marked FIRED before its TIMER_FIRED event was appended is healed by recovery")
    void timerFiredBeforeEventAppend_recoveryCompletesTheWorkflow() throws Exception {
        // The crash window inside fireTimer: markTimerFired has moved the row
        // PENDING → FIRED, but the workflow thread died before appending
        // TIMER_FIRED. The log then says "scheduled, never fired" while the row
        // says "fired" — and getDueTimers only returns PENDING rows, so no
        // poller will ever look at it again. Modelled without a lock backend so
        // node-b can adopt at once (see the restart test above).
        harness = newHarness("node-a", false);
        registerSleeper(harness, NAP);

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("timer-crash-window");
        var handle = harness.start(workflowId, TestWorkflows.SleepingWorkflow.class, "seed");
        handle.awaitStatus(WorkflowStatus.WAITING_TIMER, BOUND);

        var timerDbId = readSingleTimerRow(workflowId).id();
        assertTrue(store.markTimerFired(timerDbId),
                "the crash window only opens once the row has transitioned");
        // node-a is now gone: nothing unparks its thread, nothing appends TIMER_FIRED.

        secondHarness = newHarness("node-b", false);
        registerSleeper(secondHarness, NAP);
        // Running a poller proves the poller is no rescue: the row is no longer due.
        secondHarness.startTimerPoller(POLL_INTERVAL, 10);

        assertEquals(1, secondHarness.recover(),
                "node-b must adopt the WAITING_TIMER instance left behind by node-a");

        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND),
                "a timer already marked FIRED must not re-park the workflow forever");
        assertEquals("seed-one-two", handle.result(String.class));

        assertEquals(1, recorder.count("stepOne"),
                "the pre-sleep activity must replay, not re-execute, on the second executor");
        assertEquals(1, recorder.count("stepTwo"));
        assertEquals(1, eventsOfType(handle.events(), EventType.TIMER_FIRED).size(),
                "replay must heal the missing TIMER_FIRED event exactly once");
        assertEquals(TimerStatus.FIRED, readSingleTimerRow(workflowId).status());
    }

    // ── helpers ────────────────────────────────────────────────────────

    /**
     * Builds a harness for one simulated node.
     *
     * @param serviceName this node's service name
     * @param withLock    whether to give it a Postgres instance lock
     * @return the harness
     */
    private MaestroEngineHarness newHarness(String serviceName, boolean withLock) {
        return MaestroEngineHarness.builder(store, objectMapper)
                .serviceName(serviceName)
                .lock(withLock ? newLock() : null)
                .build();
    }

    /**
     * Registers the sleeping-workflow fixture and its recording activities on a
     * harness. The recorder is shared across harnesses so that "ran once in
     * total" is directly assertable across a simulated restart.
     *
     * @param target the harness to register on
     * @param nap    how long the workflow should sleep
     */
    private void registerSleeper(MaestroEngineHarness target, Duration nap) {
        target.registerActivities(ChainActivities.class,
                new CountingActivities.RecordingChainActivities(recorder));
        target.registerWorkflow(new TestWorkflows.SleepingWorkflow(nap));
    }

    /**
     * Drains due timers until exactly one fires.
     *
     * <p>{@code fireAt} is real wall-clock time, so a workflow can reach
     * WAITING_TIMER before its timer is due. Retrying the drain until it
     * succeeds keeps the fire deterministic without sleeping for the nap.
     *
     * @param target the harness whose executor should fire the timer
     */
    private static void fireWhenDue(MaestroEngineHarness target) {
        await().atMost(BOUND)
                .pollInterval(Duration.ofMillis(50))
                .until(() -> target.fireDueTimers(10) == 1);
    }

    /**
     * @param events    an event log
     * @param eventType the type to keep
     * @return the events of that type, in sequence order
     */
    private static List<WorkflowEvent> eventsOfType(List<WorkflowEvent> events, EventType eventType) {
        return events.stream().filter(e -> e.eventType() == eventType).toList();
    }

    /**
     * Reads the one timer row for a workflow straight from Postgres.
     *
     * <p>The store SPI only surfaces <em>due, pending</em> timers, so the
     * PENDING → FIRED transition can only be asserted against the table.
     *
     * @param workflowId the business workflow ID
     * @return the persisted timer row
     * @throws SQLException          if the query fails
     * @throws IllegalStateException if there is not exactly one row
     */
    private TimerRow readSingleTimerRow(String workflowId) throws SQLException {
        var sql = "SELECT id, workflow_instance_id, timer_id, status"
                + " FROM maestro_workflow_timer WHERE workflow_id = ?";
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, workflowId);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("No timer row for workflow " + workflowId);
                }
                var row = new TimerRow(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("workflow_instance_id"),
                        rs.getString("timer_id"),
                        TimerStatus.valueOf(rs.getString("status")));
                if (rs.next()) {
                    throw new IllegalStateException(
                            "Expected exactly one timer row for workflow " + workflowId);
                }
                return row;
            }
        }
    }

    /** A row of {@code maestro_workflow_timer}, as far as these tests care. */
    private record TimerRow(UUID id, UUID workflowInstanceId, String timerId, TimerStatus status) {}
}
