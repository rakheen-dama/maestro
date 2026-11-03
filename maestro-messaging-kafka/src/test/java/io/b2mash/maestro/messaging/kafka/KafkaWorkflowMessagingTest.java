package io.b2mash.maestro.messaging.kafka;

import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
        messaging = new KafkaWorkflowMessaging(
                kafkaTemplate, consumerFactory, objectMapper, config(null));
    }

    /**
     * Builds a config with a deliberately tiny redelivery budget: these tests
     * assert delivery, not the production backoff, and a failing record would
     * otherwise stall its partition for minutes.
     */
    private KafkaMessagingConfig config(@Nullable String signalsTopic) {
        return new KafkaMessagingConfig(
                signalsTopic,
                "maestro.admin.events." + testSuffix,
                "test-group-" + testSuffix,
                2,                          // one redelivery, then dead-letter
                Duration.ofMillis(50),
                2.0,
                Duration.ofMillis(200),
                ".DLT",
                true
        );
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
        @DisplayName("publishSignal uses workflowId as partition key")
        void publishSignalUsesWorkflowIdAsKey() throws Exception {
            var serviceName = "keyed-" + testSuffix;
            var topic = "maestro.signals." + serviceName;
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

            messaging.publishSignal(serviceName, new SignalMessage("wf-abc", "approval", null));

            await().atMost(10, SECONDS).untilAsserted(() -> {
                assertEquals(1, keys.size());
                assertEquals("wf-abc", keys.getFirst());
            });

            container.stop();
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
            var serviceName = "errors-" + testSuffix;
            var topic = "maestro.signals." + serviceName;
            // The failing record is redelivered and then dead-lettered, so the
            // dead-letter topic has to exist — Maestro never creates topics.
            createTopics(topic, topic + ".DLT");

            var successCount = new AtomicInteger(0);
            var failCount = new AtomicInteger(0);

            messaging.subscribeSignals(serviceName, msg -> {
                if ("fail-me".equals(msg.workflowId())) {
                    failCount.incrementAndGet();
                    throw new RuntimeException("Deliberate test failure");
                }
                successCount.incrementAndGet();
            });
            Thread.sleep(500);

            // Send a message that will fail
            messaging.publishSignal(serviceName, new SignalMessage("fail-me", "approval", null));

            // The failing record is redelivered before it is dead-lettered, so
            // the whole budget is spent before the consumer moves on.
            await().atMost(10, SECONDS).untilAsserted(() ->
                    assertEquals(2, failCount.get(),
                            "Failing message should have been attempted twice, then dead-lettered"));

            // Now send a second message AFTER the failure — proves the consumer
            // is still alive and consuming, not just that both messages were
            // in-flight before the exception occurred
            messaging.publishSignal(serviceName, new SignalMessage("succeed", "approval", null));

            await().atMost(10, SECONDS).untilAsserted(() ->
                    assertTrue(successCount.get() >= 1,
                            "Message sent after failure should still be processed — consumer must still be running"));

            messaging.destroy();
        }
    }

    // ── Redelivery flag (engine channel) ────────────────────────────────

    @Nested
    @DisplayName("maestro.messaging.redelivery.enabled=false (engine channel)")
    class RedeliveryFlagTests {

        @Test
        @DisplayName("installs a zero-retry handler with no dead-letter recoverer")
        void redeliveryDisabled_installsZeroRetryHandlerWithNoDeadLetterRecoverer() throws Exception {
            var disabledConfig = new KafkaMessagingConfig(
                    "flagoff-" + testSuffix, "maestro.admin.events." + testSuffix,
                    "test-group-flagoff-" + testSuffix,
                    2, Duration.ofMillis(50), 2.0, Duration.ofMillis(200), ".DLT",
                    false); // redeliveryEnabled
            var disabledMessaging = new KafkaWorkflowMessaging(
                    kafkaTemplate, consumerFactory, objectMapper, disabledConfig);

            try {
                disabledMessaging.subscribeSignals("svc-flagoff-" + testSuffix, message -> { });

                @SuppressWarnings("unchecked")
                var containers = (List<ConcurrentMessageListenerContainer<String, byte[]>>)
                        ReflectionTestUtils.getField(disabledMessaging, "containers");
                assertEquals(1, containers.size());

                var handler = containers.get(0).getCommonErrorHandler();
                assertInstanceOf(DefaultErrorHandler.class, handler);

                // FailedRecordProcessor (DefaultErrorHandler's superclass) has no public
                // getter for its tracker's recoverer/backOff — reflection is the only way
                // to pin the actual handler shape from outside the package. Mirrors
                // MaestroSignalListenerContainerConfigTest's equivalent pin for the
                // @MaestroSignalListener path.
                var tracker = ReflectionTestUtils.getField(handler, "failureTracker");
                var recoverer = ReflectionTestUtils.getField(tracker, "recoverer");
                assertFalse(recoverer instanceof DeadLetterPublishingRecoverer,
                        "redelivery disabled must not install a DeadLetterPublishingRecoverer — "
                                + "nothing should ever try to publish to a .DLT topic");

                var backOff = ReflectionTestUtils.getField(tracker, "backOff");
                assertInstanceOf(FixedBackOff.class, backOff);
                var fixedBackOff = (FixedBackOff) backOff;
                assertEquals(0L, fixedBackOff.getInterval());
                assertEquals(0L, fixedBackOff.getMaxAttempts());
            } finally {
                disabledMessaging.destroy();
            }
        }
    }

    // ── Topic Resolution ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Topic resolution with overrides")
    class TopicResolutionTests {

        @Test
        @DisplayName("fixed topic override is used instead of dynamic naming")
        void fixedTopicOverride() throws Exception {
            var fixedTopic = "custom.signals." + testSuffix;
            createTopics(fixedTopic);

            var overrideConfig = config(fixedTopic);
            var overrideMessaging = new KafkaWorkflowMessaging(
                    kafkaTemplate, consumerFactory, objectMapper, overrideConfig);

            var received = new CopyOnWriteArrayList<SignalMessage>();
            overrideMessaging.subscribeSignals("ignored-service-name", received::add);
            Thread.sleep(500);

            overrideMessaging.publishSignal("ignored-service-name", new SignalMessage("wf-1", "approval", null));

            await().atMost(10, SECONDS).untilAsserted(() -> {
                assertEquals(1, received.size());
                assertEquals("wf-1", received.getFirst().workflowId());
            });

            overrideMessaging.destroy();
        }
    }
}
