package io.b2mash.maestro.messaging.kafka;

import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.core.spi.TaskMessage;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link KafkaWorkflowMessaging}.
 *
 * <p>Uses a real Kafka broker via Testcontainers. Each test creates unique
 * topic names to avoid cross-test pollution.
 */
class KafkaWorkflowMessagingTest extends KafkaTestSupport {

    private KafkaWorkflowMessaging messaging;
    private String testSuffix;

    @BeforeEach
    void setUpMessaging() {
        testSuffix = UUID.randomUUID().toString().substring(0, 8);
        var config = new KafkaMessagingConfig(
                null,   // dynamic task topics
                null,   // dynamic signal topics
                "maestro.admin.events." + testSuffix,
                "test-group-" + testSuffix
        );
        messaging = new KafkaWorkflowMessaging(kafkaTemplate, consumerFactory, objectMapper, config);
    }

    // ── Publish + Subscribe Tasks ────────────────────────────────────────

    @Nested
    @DisplayName("Task publishing and subscription")
    class TaskTests {

        @Test
        @DisplayName("subscribe receives published task message with correct fields")
        void subscribeReceivesPublishedTask() throws Exception {
            var taskQueue = "orders-" + testSuffix;
            var topic = "maestro.tasks." + taskQueue;
            createTopics(topic);

            var received = new CopyOnWriteArrayList<TaskMessage>();

            messaging.subscribe(taskQueue, received::add);
            Thread.sleep(500); // Allow container to start

            var instanceId = UUID.randomUUID();
            var runId = UUID.randomUUID();
            var task = new TaskMessage(instanceId, "order-123", "order-fulfilment", runId, "order-service", null);

            messaging.publishTask(taskQueue, task);

            await().atMost(10, SECONDS).untilAsserted(() -> {
                assertEquals(1, received.size());
                var msg = received.getFirst();
                assertEquals(instanceId, msg.workflowInstanceId());
                assertEquals("order-123", msg.workflowId());
                assertEquals("order-fulfilment", msg.workflowType());
                assertEquals(runId, msg.runId());
                assertEquals("order-service", msg.serviceName());
                assertTrue(msg.input() == null || msg.input().isNull(),
                        "Input should be null or NullNode");
            });

            messaging.destroy();
        }

        @Test
        @DisplayName("publishTask uses workflowId as partition key")
        void publishTaskUsesWorkflowIdAsKey() throws Exception {
            var taskQueue = "keyed-" + testSuffix;
            var topic = "maestro.tasks." + taskQueue;
            createTopics(topic);

            var keys = new CopyOnWriteArrayList<String>();

            // Subscribe with a raw Kafka consumer to check the record key
            var containerProps = new org.springframework.kafka.listener.ContainerProperties(topic);
            containerProps.setGroupId("key-check-" + testSuffix);
            containerProps.setMessageListener(
                    (org.springframework.kafka.listener.MessageListener<String, byte[]>)
                            record -> keys.add(record.key()));
            var container = new org.springframework.kafka.listener.ConcurrentMessageListenerContainer<>(
                    consumerFactory, containerProps);
            container.start();
            Thread.sleep(500);

            var task = new TaskMessage(UUID.randomUUID(), "wf-abc", "type", UUID.randomUUID(), "svc", null);
            messaging.publishTask(taskQueue, task);

            await().atMost(10, SECONDS).untilAsserted(() -> {
                assertEquals(1, keys.size());
                assertEquals("wf-abc", keys.getFirst());
            });

            container.stop();
        }

        @Test
        @DisplayName("publishTask with non-null input preserves JSON payload")
        void publishTaskWithInput() throws Exception {
            var taskQueue = "input-" + testSuffix;
            var topic = "maestro.tasks." + taskQueue;
            createTopics(topic);

            var received = new CopyOnWriteArrayList<TaskMessage>();
            messaging.subscribe(taskQueue, received::add);
            Thread.sleep(500);

            var inputNode = objectMapper.readTree("{\"amount\":100,\"currency\":\"ZAR\"}");
            var task = new TaskMessage(UUID.randomUUID(), "order-1", "type", UUID.randomUUID(), "svc", inputNode);

            messaging.publishTask(taskQueue, task);

            await().atMost(10, SECONDS).untilAsserted(() -> {
                assertEquals(1, received.size());
                var input = received.getFirst().input();
                assertNotNull(input);
                assertEquals(100, input.get("amount").asInt());
                assertEquals("ZAR", input.get("currency").stringValue());
            });

            messaging.destroy();
        }
    }

    // ── Publish + Subscribe Signals ──────────────────────────────────────

    @Nested
    @DisplayName("Signal publishing and subscription")
    class SignalTests {

        @Test
        @DisplayName("subscribeSignals receives published signal message")
        void subscribeReceivesPublishedSignal() throws Exception {
            var serviceName = "payments-" + testSuffix;
            var topic = "maestro.signals." + serviceName;
            createTopics(topic);

            var received = new CopyOnWriteArrayList<SignalMessage>();

            messaging.subscribeSignals(serviceName, received::add);
            Thread.sleep(500);

            var payloadNode = objectMapper.readTree("{\"success\":true,\"txId\":\"tx-001\"}");
            var signal = new SignalMessage("order-456", "payment.result", payloadNode);

            messaging.publishSignal(serviceName, signal);

            await().atMost(10, SECONDS).untilAsserted(() -> {
                assertEquals(1, received.size());
                var msg = received.getFirst();
                assertEquals("order-456", msg.workflowId());
                assertEquals("payment.result", msg.signalName());
                assertNotNull(msg.payload());
                assertTrue(msg.payload().get("success").asBoolean());
            });

            messaging.destroy();
        }

        @Test
        @DisplayName("subscribeSignals handles null payload")
        void signalWithNullPayload() throws Exception {
            var serviceName = "nullpay-" + testSuffix;
            var topic = "maestro.signals." + serviceName;
            createTopics(topic);

            var received = new CopyOnWriteArrayList<SignalMessage>();
            messaging.subscribeSignals(serviceName, received::add);
            Thread.sleep(500);

            var signal = new SignalMessage("wf-1", "timeout", null);
            messaging.publishSignal(serviceName, signal);

            await().atMost(10, SECONDS).untilAsserted(() -> {
                assertEquals(1, received.size());
                assertEquals("wf-1", received.getFirst().workflowId());
                var payload = received.getFirst().payload();
                assertTrue(payload == null || payload.isNull(),
                        "Payload should be null or NullNode");
            });

            messaging.destroy();
        }
    }

    // ── Lifecycle Events ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Lifecycle event publishing")
    class LifecycleEventTests {

        @Test
        @DisplayName("publishLifecycleEvent sends to admin events topic")
        void publishLifecycleEventSendsToAdminTopic() throws Exception {
            var topic = "maestro.admin.events." + testSuffix;
            createTopics(topic);

            var received = new CopyOnWriteArrayList<byte[]>();
            var containerProps = new org.springframework.kafka.listener.ContainerProperties(topic);
            containerProps.setGroupId("admin-check-" + testSuffix);
            containerProps.setMessageListener(
                    (org.springframework.kafka.listener.MessageListener<String, byte[]>)
                            record -> received.add(record.value()));
            var container = new org.springframework.kafka.listener.ConcurrentMessageListenerContainer<>(
                    consumerFactory, containerProps);
            container.start();
            Thread.sleep(500);

            var event = new WorkflowLifecycleEvent(
                    UUID.randomUUID(), "order-1", "order-fulfilment", "order-service",
                    "default", LifecycleEventType.WORKFLOW_STARTED, null, null, Instant.now()
            );

            messaging.publishLifecycleEvent(event);

            await().atMost(10, SECONDS).untilAsserted(() -> {
                assertEquals(1, received.size());
                var deserialized = objectMapper.readValue(received.getFirst(), WorkflowLifecycleEvent.class);
                assertEquals("order-1", deserialized.workflowId());
                assertEquals(LifecycleEventType.WORKFLOW_STARTED, deserialized.eventType());
            });

            container.stop();
        }
    }

    // ── Error Handling ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("handler exception does not crash consumer — subsequent messages still processed")
        void handlerExceptionDoesNotCrashConsumer() throws Exception {
            var taskQueue = "errors-" + testSuffix;
            var topic = "maestro.tasks." + taskQueue;
            createTopics(topic);

            var successCount = new AtomicInteger(0);
            var failCount = new AtomicInteger(0);

            messaging.subscribe(taskQueue, msg -> {
                if ("fail-me".equals(msg.workflowId())) {
                    failCount.incrementAndGet();
                    throw new RuntimeException("Deliberate test failure");
                }
                successCount.incrementAndGet();
            });
            Thread.sleep(500);

            // Send a message that will fail
            var failing = new TaskMessage(UUID.randomUUID(), "fail-me", "type", UUID.randomUUID(), "svc", null);
            messaging.publishTask(taskQueue, failing);

            // Wait for the failure to be processed
            await().atMost(10, SECONDS).untilAsserted(() ->
                    assertTrue(failCount.get() >= 1, "Failing message should have been attempted"));

            // Now send a second message AFTER the failure — proves the consumer
            // is still alive and consuming, not just that both messages were
            // in-flight before the exception occurred
            var succeeding = new TaskMessage(UUID.randomUUID(), "succeed", "type", UUID.randomUUID(), "svc", null);
            messaging.publishTask(taskQueue, succeeding);

            await().atMost(10, SECONDS).untilAsserted(() ->
                    assertTrue(successCount.get() >= 1,
                            "Message sent after failure should still be processed — consumer must still be running"));

            messaging.destroy();
        }
    }

    // ── Topic Resolution ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Topic resolution with overrides")
    class TopicResolutionTests {

        @Test
        @DisplayName("fixed topic override is used instead of dynamic naming")
        void fixedTopicOverride() throws Exception {
            var fixedTopic = "custom.tasks." + testSuffix;
            createTopics(fixedTopic);

            var overrideConfig = new KafkaMessagingConfig(
                    fixedTopic,  // fixed task topic
                    null,
                    "maestro.admin.events." + testSuffix,
                    "override-group-" + testSuffix
            );
            var overrideMessaging = new KafkaWorkflowMessaging(
                    kafkaTemplate, consumerFactory, objectMapper, overrideConfig);

            var received = new CopyOnWriteArrayList<TaskMessage>();
            overrideMessaging.subscribe("ignored-queue-name", received::add);
            Thread.sleep(500);

            var task = new TaskMessage(UUID.randomUUID(), "wf-1", "type", UUID.randomUUID(), "svc", null);
            overrideMessaging.publishTask("ignored-queue-name", task);

            await().atMost(10, SECONDS).untilAsserted(() -> {
                assertEquals(1, received.size());
                assertEquals("wf-1", received.getFirst().workflowId());
            });

            overrideMessaging.destroy();
        }
    }
}
