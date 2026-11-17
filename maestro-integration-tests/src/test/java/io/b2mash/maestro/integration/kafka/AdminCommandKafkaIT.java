package io.b2mash.maestro.integration.kafka;

import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.spring.client.MaestroClient;
import io.b2mash.maestro.spring.client.WorkflowOptions;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
                "maestro.admin.events.enabled=true",
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

    /** The engine's consumer group on {@link #SIGNAL_TOPIC}: {@code "maestro-" + maestro.service-name}. */
    private static final String ENGINE_GROUP = "maestro-" + SERVICE;

    private static final Duration BOUND = Duration.ofSeconds(30);

    @Autowired
    private MaestroClient maestro;

    @Autowired
    private AdminCommandWorkflows.FlakyActivitiesImpl flaky;

    @Autowired
    private AdminCommandWorkflows.FallbackActivitiesImpl fallback;

    /** The broker the context consumes from; read to inspect committed offsets and the DLT. */
    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

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
        fallback.reset();
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

    // ── (e) WP3: what a Retry deletes, over the real chain ────────────────

    @Test
    @DisplayName("$maestro:retry after a caught fallback replays the fallback and completes (3.3)")
    void retry_afterACaughtFallback_completesWithoutStandDown() throws Exception {
        var workflowId = "admincmd-fallback-" + UUID.randomUUID().toString().substring(0, 8);
        maestro.newWorkflow(AdminCommandWorkflows.FallbackWorkflow.class,
                WorkflowOptions.builder().workflowId(workflowId).build()).startAsync("seed");
        awaitStatus(workflowId, WorkflowStatus.FAILED, BOUND);
        fallback.fix();
        publish(SIGNAL_TOPIC, workflowId, new SignalMessage(workflowId, "$maestro:retry", null));
        assertEquals(WorkflowStatus.COMPLETED, lastStatusWithin(workflowId, WorkflowStatus.COMPLETED, BOUND));
        assertEquals(1, fallback.primaryCalls());
        assertEquals(1, fallback.fallbackCalls());
        assertEquals(2, fallback.finishCalls());
    }

    @Test
    @DisplayName("a $maestro:retry refused because the failure's handling consumed a signal is acknowledged "
            + "(offset committed, nothing dead-lettered) and writes nothing (MD-1)")
    void retry_refusedForAConsumedSignal_isAcknowledgedAndWritesNothing() throws Exception {
        var workflowId = "admincmd-refused-" + UUID.randomUUID().toString().substring(0, 8);
        maestro.newWorkflow(AdminCommandWorkflows.AckThenRethrowWorkflow.class,
                WorkflowOptions.builder().workflowId(workflowId).build()).startAsync("seed");
        awaitStatus(workflowId, WorkflowStatus.WAITING_SIGNAL, BOUND);
        publish(SIGNAL_TOPIC, workflowId,
                new SignalMessage(workflowId, AdminCommandWorkflows.ACK_SIGNAL, objectMapper.valueToTree("ok")));
        var failed = awaitStatus(workflowId, WorkflowStatus.FAILED, BOUND);
        var history = history(workflowId);
        assertEquals(List.of(
                "1 ACTIVITY_FAILED FallbackActivities.primary",
                "2 TIMER_SCHEDULED $maestro:awaitSignal:ack",
                "3 SIGNAL_RECEIVED $maestro:awaitSignal:ack",
                "4 WORKFLOW_FAILED null"), history, "precondition: the failure's handling consumed the ack");
        var signals = signalRows(workflowId);
        assertEquals(List.of(new SignalRow(failed.id(), AdminCommandWorkflows.ACK_SIGNAL, true)), signals,
                "precondition: one ack row, consumed by this run");
        var timers = timerRows(workflowId);
        var dltBefore = endOffset(SIGNAL_DLT);

        fallback.fix();
        var offset = publishAdminCommand(workflowId, "$maestro:retry");
        awaitCommitted(offset, BOUND);

        assertEquals(dltBefore, endOffset(SIGNAL_DLT), "a refused retry is acknowledged, never dead-lettered");
        var after = store.getInstance(workflowId).orElseThrow();
        assertEquals(WorkflowStatus.FAILED, after.status());
        assertEquals(failed.version(), after.version(), "the refusal must not write the row");
        assertEquals(history, history(workflowId), "the refusal must not delete an event");
        assertEquals(signals, signalRows(workflowId), "the consumed signal stays consumed, and its row is kept");
        assertEquals(timers, timerRows(workflowId), "the refusal must not touch a timer row");
        assertEquals(1, fallback.primaryCalls(), "nothing was relaunched");
    }

    @Test
    @DisplayName("a $maestro:retry redelivered after a committed reopen is acknowledged and does nothing")
    void retry_redeliveredAfterACommittedReopen_doesNothing() throws Exception {
        var workflowId = "admincmd-redelivered-" + UUID.randomUUID().toString().substring(0, 8);
        maestro.newWorkflow(AdminCommandWorkflows.FallbackWorkflow.class,
                WorkflowOptions.builder().workflowId(workflowId).build()).startAsync("seed");
        awaitStatus(workflowId, WorkflowStatus.FAILED, BOUND);
        fallback.fix();
        // The dashboard's wire shape: one fresh admin:<uuid> id, in the body and the header.
        var command = new SignalMessage(workflowId, "$maestro:retry", null, "admin:" + UUID.randomUUID());
        var first = publishRecord(command);
        assertEquals(WorkflowStatus.COMPLETED, lastStatusWithin(workflowId, WorkflowStatus.COMPLETED, BOUND));
        awaitCommitted(first, BOUND);
        var completed = awaitStatus(workflowId, WorkflowStatus.COMPLETED, BOUND);
        var history = history(workflowId);
        var dltBefore = endOffset(SIGNAL_DLT);

        // The same record again: what the broker hands the group when the
        // reopen committed but the offset commit did not.
        var redelivered = publishRecord(command);
        awaitCommitted(redelivered, BOUND);

        assertEquals(dltBefore, endOffset(SIGNAL_DLT), "the redelivered command is acknowledged, never dead-lettered");
        var after = store.getInstance(workflowId).orElseThrow();
        assertEquals(WorkflowStatus.COMPLETED, after.status());
        assertEquals(completed.version(), after.version(), "the redelivered command must not write the row");
        assertEquals(history, history(workflowId), "the redelivered command must not delete or append an event");
        assertEquals(1, fallback.primaryCalls());
        assertEquals(1, fallback.fallbackCalls());
        assertEquals(2, fallback.finishCalls(), "finish ran once on the first run and once after the one reopen");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /**
     * Polls the instance row every 50 ms until its status is {@code wanted}
     * or {@code bound} elapses, and returns the last status read — so an
     * assertion on it fails on the status, not on a timeout.
     */
    private @Nullable WorkflowStatus lastStatusWithin(String workflowId, WorkflowStatus wanted, Duration bound)
            throws InterruptedException {
        var deadline = System.nanoTime() + bound.toNanos();
        while (true) {
            var last = store.getInstance(workflowId).map(WorkflowInstance::status).orElse(null);
            if (last == wanted || System.nanoTime() >= deadline) {
                return last;
            }
            Thread.sleep(50);
        }
    }

    /** The instance's history as {@code "<seq> <type> <step>"}, ascending. */
    private List<String> history(String workflowId) {
        var instanceId = store.getInstance(workflowId).orElseThrow().id();
        return store.getEvents(instanceId).stream()
                .sorted(Comparator.comparingInt(WorkflowEvent::sequenceNumber))
                .map(e -> e.sequenceNumber() + " " + e.eventType() + " " + e.stepName())
                .toList();
    }

    /** Every timer row of the workflow, whole, as Postgres renders it, by timer id. */
    private List<String> timerRows(String workflowId) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT t::text FROM maestro_workflow_timer t WHERE workflow_id = ? ORDER BY timer_id")) {
            ps.setString(1, workflowId);
            try (var rs = ps.executeQuery()) {
                var rows = new ArrayList<String>();
                while (rs.next()) {
                    rows.add(rs.getString(1));
                }
                return rows;
            }
        }
    }

    /** Publishes an admin command in the dashboard's wire shape and returns its offset. */
    private long publishAdminCommand(String workflowId, String command) throws Exception {
        return publishRecord(new SignalMessage(workflowId, command, null, "admin:" + UUID.randomUUID()));
    }

    /**
     * Publishes {@code message} to {@link #SIGNAL_TOPIC}, keyed by workflow id,
     * with its message id in the {@code maestro-message-id} header too (as
     * {@code AdminCommandService} does), and returns the record's offset.
     */
    private long publishRecord(SignalMessage message) throws Exception {
        var record = new ProducerRecord<String, byte[]>(SIGNAL_TOPIC, message.workflowId(),
                objectMapper.writeValueAsBytes(message));
        if (message.messageId() != null) {
            record.headers().add("maestro-message-id", message.messageId().getBytes(StandardCharsets.UTF_8));
        }
        return producer().send(record).get().getRecordMetadata().offset();
    }

    /**
     * Waits until the engine's group has committed past {@code offset} on
     * {@link #SIGNAL_TOPIC}: the listener returned for that record (or
     * dead-lettered it, which {@link #endOffset} on the DLT tells apart).
     */
    private void awaitCommitted(long offset, Duration bound) {
        var partition = new TopicPartition(SIGNAL_TOPIC, 0);
        await("the engine's consumer group committing past offset " + offset + " of " + SIGNAL_TOPIC)
                .atMost(bound).pollInterval(Duration.ofMillis(100)).until(() -> {
                    try (var admin = admin()) {
                        var committed = admin.listConsumerGroupOffsets(ENGINE_GROUP)
                                .partitionsToOffsetAndMetadata().get().get(partition);
                        return committed != null && committed.offset() > offset;
                    }
                });
    }

    /** The log-end offset of a single-partition topic. */
    private long endOffset(String topic) throws Exception {
        var partition = new TopicPartition(topic, 0);
        try (var admin = admin()) {
            return admin.listOffsets(Map.of(partition, OffsetSpec.latest())).partitionResult(partition).get().offset();
        }
    }

    private AdminClient admin() {
        return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
    }

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
