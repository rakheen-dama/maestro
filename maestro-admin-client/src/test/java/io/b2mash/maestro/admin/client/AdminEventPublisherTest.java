package io.b2mash.maestro.admin.client;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AdminEventPublisher}.
 *
 * <p>Uses Kafka's {@link MockProducer} — a broker-free double built for exactly
 * this purpose — so these stay true unit tests (no Testcontainers, no network),
 * verifying serialization shape, keying, and the fire-and-forget error contract
 * described in the class Javadoc.
 */
@DisplayName("AdminEventPublisher")
class AdminEventPublisherTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private MockProducer<String, byte[]> mockProducer;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger publisherLogger;

    @BeforeEach
    void setUp() {
        mockProducer = new MockProducer<>(true, null, new StringSerializer(), new org.apache.kafka.common.serialization.ByteArraySerializer());

        publisherLogger = (Logger) LoggerFactory.getLogger(AdminEventPublisher.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        publisherLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        publisherLogger.detachAppender(logAppender);
    }

    @Test
    @DisplayName("serializes the event to JSON and sends it keyed by workflowId")
    void publish_serializesAndSendsKeyedByWorkflowId() {
        var kafkaTemplate = new KafkaTemplate<>(fixedProducerFactory(mockProducer));
        var publisher = new AdminEventPublisher(kafkaTemplate, objectMapper, "maestro.admin.events");
        var event = sampleEvent("wf-123");

        publisher.publish(event);

        assertThat(mockProducer.history()).hasSize(1);
        ProducerRecord<String, byte[]> record = mockProducer.history().get(0);
        assertThat(record.topic()).isEqualTo("maestro.admin.events");
        assertThat(record.key()).isEqualTo("wf-123");

        var deserialized = objectMapper.readValue(record.value(), WorkflowLifecycleEvent.class);
        assertThat(deserialized.workflowId()).isEqualTo("wf-123");
        assertThat(deserialized.workflowInstanceId()).isEqualTo(event.workflowInstanceId());
        assertThat(deserialized.eventType()).isEqualTo(LifecycleEventType.WORKFLOW_STARTED);
        assertThat(deserialized.stepName()).isNull();
    }

    @Test
    @DisplayName("publishes to the configured topic, not a hardcoded one")
    void publish_usesConfiguredTopic() {
        var kafkaTemplate = new KafkaTemplate<>(fixedProducerFactory(mockProducer));
        var publisher = new AdminEventPublisher(kafkaTemplate, objectMapper, "custom-topic");

        publisher.publish(sampleEvent("wf-456"));

        assertThat(mockProducer.history()).hasSize(1);
        assertThat(mockProducer.history().get(0).topic()).isEqualTo("custom-topic");
    }

    @Test
    @DisplayName("swallows send failures and logs a WARN instead of throwing")
    void publish_sendFailure_isSwallowedAndLogged() {
        var failingProducer = new MockProducer<>(
                false, null, new StringSerializer(), new org.apache.kafka.common.serialization.ByteArraySerializer());
        var kafkaTemplate = new KafkaTemplate<>(fixedProducerFactory(failingProducer));
        var publisher = new AdminEventPublisher(kafkaTemplate, objectMapper, "maestro.admin.events");

        // publish() must not throw even though the send will be completed
        // exceptionally below.
        publisher.publish(sampleEvent("wf-789"));

        // MockProducer buffers the send until completed; complete it with an error
        // to simulate a broker-side failure and let KafkaTemplate's callback fire.
        assertThat(failingProducer.history()).hasSize(1);
        failingProducer.errorNext(new RuntimeException("simulated broker failure"));

        assertThat(logAppender.list)
                .as("a WARN log must record the swallowed failure")
                .anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("wf-789")
                        && e.getFormattedMessage().contains("maestro.admin.events"));
    }

    @Test
    @DisplayName("serialization failure is caught and logged, never propagated")
    void publish_serializationFailure_isSwallowedAndLogged() {
        var kafkaTemplate = new KafkaTemplate<>(fixedProducerFactory(mockProducer));
        var throwingMapper = new ThrowingObjectMapper();
        var publisher = new AdminEventPublisher(kafkaTemplate, throwingMapper, "maestro.admin.events");
        var event = sampleEvent("wf-bad");

        org.assertj.core.api.Assertions.assertThatCode(() -> publisher.publish(event))
                .as("a serialization failure must be caught, never propagated to the caller")
                .doesNotThrowAnyException();

        assertThat(mockProducer.history())
                .as("nothing should be sent to Kafka once serialization fails")
                .isEmpty();
        assertThat(logAppender.list)
                .as("a WARN log must record the swallowed serialization failure")
                .anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("wf-bad"));
    }

    /** ObjectMapper stand-in that always fails serialization, to exercise the catch path. */
    private static final class ThrowingObjectMapper extends JsonMapper {
        @Override
        public byte[] writeValueAsBytes(Object value) {
            throw new RuntimeException("forced serialization failure");
        }
    }

    private static WorkflowLifecycleEvent sampleEvent(String workflowId) {
        return new WorkflowLifecycleEvent(
                UUID.randomUUID(),
                workflowId,
                "TestWorkflow",
                "test-service",
                "test-queue",
                LifecycleEventType.WORKFLOW_STARTED,
                null,
                null,
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static ProducerFactory<String, byte[]> fixedProducerFactory(MockProducer<String, byte[]> producer) {
        return new ProducerFactory<>() {
            @Override
            public org.apache.kafka.clients.producer.Producer<String, byte[]> createProducer() {
                return producer;
            }
        };
    }
}
