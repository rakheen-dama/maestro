package io.b2mash.maestro.messaging.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.awaitility.Awaitility.await;

/**
 * Base class for Kafka integration tests.
 *
 * <p>Provides a shared Testcontainers {@link KafkaContainer} (KRaft mode,
 * confluentinc/cp-kafka image), along with pre-configured {@link KafkaTemplate},
 * {@link ConsumerFactory}, and Jackson 3 {@link ObjectMapper}.
 */
@Testcontainers
public abstract class KafkaTestSupport {

    @Container
    protected static final KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"))
                    .withKraft()
                    // Maestro never auto-creates topics, and neither may the
                    // broker behind its tests: every suite pre-creates what it
                    // uses via createTopics, so a forgotten topic fails loudly
                    // here instead of only in production.
                    .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    protected KafkaTemplate<String, byte[]> kafkaTemplate;
    protected ProducerFactory<String, byte[]> producerFactory;
    protected ConsumerFactory<String, byte[]> consumerFactory;
    protected ObjectMapper objectMapper;

    @BeforeEach
    void setUpKafka() {
        objectMapper = JsonMapper.builder().build();

        var producerProps = Map.<String, Object>of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.ACKS_CONFIG, "all"
        );
        producerFactory = new DefaultKafkaProducerFactory<>(producerProps);
        kafkaTemplate = new KafkaTemplate<>(producerFactory);

        var consumerProps = Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"
        );
        consumerFactory = new DefaultKafkaConsumerFactory<>(consumerProps);
    }

    /** How long a fresh consumer may take to join its group and be assigned its partitions. */
    protected static final Duration ASSIGNMENT_BOUND = Duration.ofSeconds(30);

    /**
     * Waits until every container has been assigned partitions — the
     * precondition a test that publishes right after subscribing depends on
     * once its consumer does not start at {@code earliest} (Issue 92: a fixed
     * {@code Thread.sleep} waited on nothing in particular).
     *
     * @param containers the started containers; at least one
     */
    protected static void awaitAssignment(Collection<? extends MessageListenerContainer> containers) {
        if (containers.isEmpty()) {
            throw new IllegalArgumentException("no container to wait for");
        }
        await().atMost(ASSIGNMENT_BOUND)
                .pollInterval(Duration.ofMillis(20))
                .until(() -> containers.stream().allMatch(c -> {
                    var assigned = c.getAssignedPartitions();
                    return assigned != null && !assigned.isEmpty();
                }));
    }

    /** @param containers the started containers */
    protected static void awaitAssignment(MessageListenerContainer... containers) {
        awaitAssignment(List.of(containers));
    }

    /**
     * Pre-creates Kafka topics for testing. Topics must exist before
     * publishing or subscribing (Maestro does not auto-create topics).
     */
    protected void createTopics(String... topicNames) throws ExecutionException, InterruptedException {
        var adminProps = Map.<String, Object>of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()
        );
        try (var admin = AdminClient.create(adminProps)) {
            KafkaTopicsForTests.create(admin, Arrays.asList(topicNames));
        }
    }
}
