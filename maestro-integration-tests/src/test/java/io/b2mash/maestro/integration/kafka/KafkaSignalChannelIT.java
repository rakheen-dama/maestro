package io.b2mash.maestro.integration.kafka;

import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.spring.client.MaestroClient;
import io.b2mash.maestro.spring.client.WorkflowOptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feeds the engine-level inbound signal channel — {@code maestro.signals.{service}}
 * — on a real broker, which no test had ever done.
 *
 * <p>{@code SignalSubscriptionRunner} subscribes to that topic at startup and
 * routes each {@link SignalMessage} to {@code WorkflowExecutor.deliverSignal}.
 * It is the ingestion path for signals published by
 * {@link WorkflowMessaging#publishSignal} — the admin dashboard and any service
 * that talks to Maestro rather than to a domain topic. The loan-origination E2E
 * starts the subscriber but never publishes to it, so the whole channel was
 * unverified end to end.
 */
@SpringBootTest(
        classes = KafkaSignalTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "maestro.service-name=" + KafkaSignalChannelIT.SERVICE,
                "maestro.lock.type=postgres",
                "maestro.messaging.type=kafka",
                "maestro.messaging.topics.admin-events=" + KafkaSignalChannelIT.ADMIN_TOPIC,
                "maestro.recovery.enabled=false"
        })
@Tag("integration")
@DisplayName("The maestro.signals.{service} channel delivers signals into running workflows")
class KafkaSignalChannelIT extends KafkaSpringIntegrationSupport {

    static final String SERVICE = "kafka-channel";

    static final String ADMIN_TOPIC = "it.channel.admin";

    /** The engine-level inbound channel this suite exercises. */
    private static final String SIGNAL_TOPIC = "maestro.signals." + SERVICE;

    private static final Duration BOUND = Duration.ofSeconds(30);

    @Autowired
    private MaestroClient maestro;

    @Autowired
    private WorkflowMessaging messaging;

    /**
     * @throws ExecutionException   if topic creation fails
     * @throws InterruptedException if interrupted while waiting
     */
    @BeforeAll
    static void createChannelTopics() throws ExecutionException, InterruptedException {
        createTopics(SIGNAL_TOPIC, ADMIN_TOPIC);
    }

    @Test
    @DisplayName("a SignalMessage published to the channel wakes a parked workflow and completes it")
    void publishedSignalMessage_wakesTheParkedWorkflow() throws Exception {
        var workflowId = "channel-" + UUID.randomUUID().toString().substring(0, 8);

        maestro.newWorkflow(KafkaTestWorkflows.ApprovalWorkflow.class,
                WorkflowOptions.builder().workflowId(workflowId).build()).startAsync("seed");
        awaitStatus(workflowId, WorkflowStatus.WAITING_SIGNAL, BOUND);

        // The production publish path — same serialization, same topic naming
        // and same partition key a remote service or the dashboard would use.
        messaging.publishSignal(SERVICE, new SignalMessage(
                workflowId, KafkaTestWorkflows.APPROVAL_SIGNAL, objectMapper.valueToTree("granted")));

        var completed = awaitStatus(workflowId, WorkflowStatus.COMPLETED, BOUND);
        assertEquals("seed:granted", serializer.deserialize(completed.output(), String.class));

        var rows = signalRows(workflowId);
        assertEquals(1, rows.size(), "the channel must persist exactly one signal row");
        assertEquals(completed.id(), rows.getFirst().workflowInstanceId());
        assertTrue(rows.getFirst().consumed());
    }

    @Test
    @DisplayName("an admin command on the channel actually terminates the workflow, "
            + "and is never persisted as a signal row")
    void adminCommand_terminatesWorkflowAndIsNeverPersisted() throws Exception {
        var workflowId = "channel-admin-" + UUID.randomUUID().toString().substring(0, 8);

        maestro.newWorkflow(KafkaTestWorkflows.ApprovalWorkflow.class,
                WorkflowOptions.builder().workflowId(workflowId).build()).startAsync("seed");
        awaitStatus(workflowId, WorkflowStatus.WAITING_SIGNAL, BOUND);

        // Engine-side handling of $maestro: commands is now implemented (Issue 15,
        // sub-task 3b): SignalSubscriptionRunner diverts this to
        // AdminCommandDispatcher before deliverSignal is ever called, so the
        // workflow is genuinely terminated rather than the command being dropped.
        // See AdminCommandKafkaIT for the dedicated end-to-end suite; this test's
        // remaining job is narrower — pin that the engine-level channel itself
        // (as opposed to the starter unit tests) still never turns a $maestro:*
        // name into a WorkflowSignal row.
        messaging.publishSignal(SERVICE, new SignalMessage(
                workflowId, "$maestro:terminate", objectMapper.valueToTree("now")));

        awaitStatus(workflowId, WorkflowStatus.TERMINATED, BOUND);

        // Both records carry the same key, so they land on the same partition in
        // order: this signal is only ever consumed by a workflow that reaches
        // its awaitSignal() call, which a terminated run's abandoned thread
        // never will — the resurrection guard must keep the instance TERMINATED.
        messaging.publishSignal(SERVICE, new SignalMessage(
                workflowId, KafkaTestWorkflows.APPROVAL_SIGNAL, objectMapper.valueToTree("granted")));

        await().atMost(BOUND).untilAsserted(() -> {
            var rows = signalRows(workflowId);
            assertEquals(1, rows.size(), "the admin command must not have been persisted, "
                    + "only the application signal published afterward");
            assertEquals(KafkaTestWorkflows.APPROVAL_SIGNAL, rows.getFirst().signalName());
        });

        assertEquals(WorkflowStatus.TERMINATED, store.getInstance(workflowId).orElseThrow().status(),
                "a signal delivered after termination must not resurrect the workflow");
    }
}
