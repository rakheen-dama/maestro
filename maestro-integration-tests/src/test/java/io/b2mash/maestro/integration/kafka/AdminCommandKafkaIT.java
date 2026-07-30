package io.b2mash.maestro.integration.kafka;

import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.spring.client.MaestroClient;
import io.b2mash.maestro.spring.client.WorkflowOptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage for the admin-command Kafka path (Issue 15,
 * sub-task 3b): a real {@code $maestro:retry} / {@code $maestro:terminate}
 * signal, in the exact wire shape {@code AdminCommandService} publishes,
 * flowing through the real auto-configuration chain —
 * {@code SignalSubscriptionRunner} → {@code AdminCommandDispatcher} →
 * {@code WorkflowExecutor} — against real Kafka and real Postgres.
 *
 * <p>Mirrors {@link KafkaAckOnFailureIT}'s fixture style: every topic,
 * including {@code .DLT}s, is pre-created in {@code @BeforeAll}, and the
 * redelivery budget is tightened so the poison-command case exhausts in
 * well under a second instead of the production ~2.5 minutes.
 */
@SpringBootTest(
        classes = {
                KafkaSignalTestApplication.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "maestro.service-name=" + AdminCommandKafkaIT.SERVICE,
                "maestro.lock.type=postgres",
                "maestro.messaging.type=kafka",
                "maestro.messaging.topics.admin-events=" + AdminCommandKafkaIT.ADMIN_TOPIC,
                "maestro.recovery.enabled=false",
                // Tight budget so the poison case's exhaustion (~1.75s of backoff)
                // fits comfortably inside this suite's bound; production default is
                // 10 attempts over ~2.5 minutes.
                "maestro.messaging.redelivery.max-attempts=4",
                "maestro.messaging.redelivery.initial-interval=250ms"
        })
@Tag("integration")
@DisplayName("$maestro:retry / $maestro:terminate over real Kafka")
class AdminCommandKafkaIT extends KafkaSpringIntegrationSupport {

    static final String SERVICE = "kafka-admin-cmd";

    static final String ADMIN_TOPIC = "it.admincmd.admin";

    /** The engine's inbound signal channel — where the admin dashboard publishes commands. */
    static final String SIGNAL_TOPIC = "maestro.signals." + SERVICE;

    /** Where a command the dispatcher can never process is parked. */
    static final String SIGNAL_DLT = SIGNAL_TOPIC + ".DLT";

    private static final Duration BOUND = Duration.ofSeconds(30);

    @Autowired
    private MaestroClient maestro;

    @Autowired
    private AdminCommandWorkflows.FlakyActivitiesImpl flaky;

    /**
     * @throws ExecutionException   if topic creation fails
     * @throws InterruptedException if interrupted while waiting
     */
    @BeforeAll
    static void createAdminCommandTopics() throws ExecutionException, InterruptedException {
        createTopics(SIGNAL_TOPIC, SIGNAL_DLT, ADMIN_TOPIC);
    }

    @BeforeEach
    void resetFlakyActivity() {
        flaky.reset();
    }

    // ── (a) retry: FAILED -> fix the fault -> $maestro:retry -> COMPLETED ──

    @Test
    @DisplayName("a FAILED workflow reaches COMPLETED after $maestro:retry, once the fault is fixed")
    void retry_failedWorkflow_completesAfterFix() throws Exception {
        var workflowId = "admincmd-retry-" + UUID.randomUUID().toString().substring(0, 8);

        maestro.newWorkflow(AdminCommandWorkflows.FlakyWorkflow.class,
                WorkflowOptions.builder().workflowId(workflowId).build()).startAsync("seed");
        awaitStatus(workflowId, WorkflowStatus.FAILED, BOUND);
        assertEquals(2, flaky.attempts(), "the activity must have exhausted its 2-attempt retry budget");

        // Fix the fault — the test seam a real operator's "dependency is back
        // up" corresponds to — THEN publish the retry command in exactly the
        // wire shape AdminCommandService sends.
        flaky.fix();
        publish(SIGNAL_TOPIC, workflowId, new SignalMessage(workflowId, "$maestro:retry", null));

        var completed = awaitStatus(workflowId, WorkflowStatus.COMPLETED, BOUND);
        assertEquals("seed-ok", serializer.deserialize(completed.output(), String.class));
    }

    // ── (b) terminate: parked on awaitSignal -> $maestro:terminate -> TERMINATED ──

    @Test
    @DisplayName("a workflow parked on awaitSignal is TERMINATED by $maestro:terminate, "
            + "and WORKFLOW_TERMINATED is observed on the admin events topic")
    void terminate_parkedWorkflow_terminatesAndPublishesLifecycleEvent() throws Exception {
        var workflowId = "admincmd-terminate-" + UUID.randomUUID().toString().substring(0, 8);

        try (var events = recorderFor(ADMIN_TOPIC)) {
            maestro.newWorkflow(KafkaTestWorkflows.ApprovalWorkflow.class,
                    WorkflowOptions.builder().workflowId(workflowId).build()).startAsync("seed");
            awaitStatus(workflowId, WorkflowStatus.WAITING_SIGNAL, BOUND);

            var payload = objectMapper.createObjectNode().put("reason", "operator requested shutdown");
            publish(SIGNAL_TOPIC, workflowId, new SignalMessage(workflowId, "$maestro:terminate", payload));

            var terminated = awaitStatus(workflowId, WorkflowStatus.TERMINATED, BOUND);
            assertEquals("operator requested shutdown", terminated.output().get("reason").asString());

            await().atMost(BOUND).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
                var terminatedEvents = events.messages(WorkflowLifecycleEvent.class).stream()
                        .filter(e -> workflowId.equals(e.workflowId())
                                && e.eventType() == LifecycleEventType.WORKFLOW_TERMINATED)
                        .toList();
                assertEquals(1, terminatedEvents.size(),
                        "WORKFLOW_TERMINATED must be published exactly once for this workflow");
            });
        }
    }

    // ── (c) poison: unknown command -> DLT after the attempt budget; the channel keeps moving ──

    @Test
    @DisplayName("$maestro:bogus is dead-lettered after the attempt budget, "
            + "and a subsequent valid command still processes")
    void poisonCommand_isDeadLettered_thenSubsequentCommandStillProcesses() throws Exception {
        var poisonWorkflowId = "admincmd-poison-" + UUID.randomUUID().toString().substring(0, 8);

        try (var dlt = recorderFor(SIGNAL_DLT)) {
            publish(SIGNAL_TOPIC, poisonWorkflowId, new SignalMessage(poisonWorkflowId, "$maestro:bogus", null));

            await().atMost(BOUND).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                    assertEquals(1, dlt.messages(SignalMessage.class).size(),
                            "an unroutable command must be dead-lettered, not silently dropped"));
            assertEquals("$maestro:bogus", dlt.messages(SignalMessage.class).getFirst().signalName());
            assertEquals(poisonWorkflowId, dlt.keys().getFirst(), "the DLT record must keep its partition key");
        }

        // The channel must keep moving after the poison record: a fresh
        // command for a different workflow, published after the DLT is
        // confirmed, must still be processed normally.
        var followUpWorkflowId = "admincmd-poison-followup-" + UUID.randomUUID().toString().substring(0, 8);
        maestro.newWorkflow(KafkaTestWorkflows.ApprovalWorkflow.class,
                WorkflowOptions.builder().workflowId(followUpWorkflowId).build()).startAsync("seed");
        awaitStatus(followUpWorkflowId, WorkflowStatus.WAITING_SIGNAL, BOUND);

        publish(SIGNAL_TOPIC, followUpWorkflowId, new SignalMessage(followUpWorkflowId, "$maestro:terminate", null));
        awaitStatus(followUpWorkflowId, WorkflowStatus.TERMINATED, BOUND);
    }

    // ── (d) invisibility: commands never become WorkflowSignal rows ────────

    @Test
    @DisplayName("after retry and terminate commands flow, no $maestro:* row was ever persisted "
            + "as a WorkflowSignal, and getUnconsumedSignals stays empty")
    void commands_neverPersistAsWorkflowSignalRows() throws Exception {
        var retryWorkflowId = "admincmd-invis-retry-" + UUID.randomUUID().toString().substring(0, 8);
        maestro.newWorkflow(AdminCommandWorkflows.FlakyWorkflow.class,
                WorkflowOptions.builder().workflowId(retryWorkflowId).build()).startAsync("seed");
        awaitStatus(retryWorkflowId, WorkflowStatus.FAILED, BOUND);
        flaky.fix();
        publish(SIGNAL_TOPIC, retryWorkflowId, new SignalMessage(retryWorkflowId, "$maestro:retry", null));
        awaitStatus(retryWorkflowId, WorkflowStatus.COMPLETED, BOUND);

        var terminateWorkflowId = "admincmd-invis-terminate-" + UUID.randomUUID().toString().substring(0, 8);
        maestro.newWorkflow(KafkaTestWorkflows.ApprovalWorkflow.class,
                WorkflowOptions.builder().workflowId(terminateWorkflowId).build()).startAsync("seed");
        awaitStatus(terminateWorkflowId, WorkflowStatus.WAITING_SIGNAL, BOUND);
        publish(SIGNAL_TOPIC, terminateWorkflowId, new SignalMessage(terminateWorkflowId, "$maestro:terminate", null));
        awaitStatus(terminateWorkflowId, WorkflowStatus.TERMINATED, BOUND);

        assertEquals(0, adminCommandSignalRowCount(),
                "no row in maestro_workflow_signal may ever carry a $maestro:* signal name");
        assertTrue(store.getUnconsumedSignals(retryWorkflowId, "$maestro:retry").isEmpty());
        assertTrue(store.getUnconsumedSignals(terminateWorkflowId, "$maestro:terminate").isEmpty());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private long adminCommandSignalRowCount() throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery(
                     "SELECT count(*) FROM maestro_workflow_signal WHERE signal_name LIKE '$maestro:%'")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
