package io.b2mash.maestro.integration.support;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

/**
 * Base class for integration suites that exercise Maestro against a real Kafka
 * broker.
 *
 * <p>Mirrors {@code KafkaTestSupport} in {@code maestro-messaging-kafka}: one
 * static Testcontainers broker in KRaft mode shared by every test in the class,
 * with Spring Kafka producer and consumer factories over {@code byte[]} values.
 *
 * <p><b>Topics are never auto-created.</b> That is a repo-wide rule — production
 * topics are pre-declared in configuration — so every suite must call
 * {@link #createTopics(String...)} before publishing or subscribing.
 *
 * <p>Tests share one broker, so each test derives unique topic and consumer-group
 * names from {@link #testSuffix} to stay isolated without restarting the container.
 *
 * <h2>Thread Safety</h2>
 * <p>Instances are per-test and confined to the test thread; the static container
 * is managed by JUnit's Testcontainers lifecycle.
 */
@Testcontainers
@Tag("integration")
public abstract class KafkaIntegrationSupport {

    @Container
    @SuppressWarnings("resource")
    static final KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"))
                    .withKraft();

    protected KafkaTemplate<String, byte[]> kafkaTemplate;
    protected ProducerFactory<String, byte[]> producerFactory;
    protected ConsumerFactory<String, byte[]> consumerFactory;
    protected ObjectMapper objectMapper;

    /** Unique per test — use it to build topic and consumer-group names. */
    protected String testSuffix;

    @BeforeEach
    void setUpKafka() {
        objectMapper = JsonMapper.builder().build();
        testSuffix = UUID.randomUUID().toString().substring(0, 8);

        var producerProps = Map.<String, Object>of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.ACKS_CONFIG, "all");
        producerFactory = new DefaultKafkaProducerFactory<>(producerProps);
        kafkaTemplate = new KafkaTemplate<>(producerFactory);

        consumerFactory = new DefaultKafkaConsumerFactory<>(consumerProps("it-" + testSuffix));
    }

    /**
     * Builds consumer properties for a given group.
     *
     * @param groupId the consumer group
     * @return consumer configuration reading from the earliest offset
     */
    protected Map<String, Object> consumerProps(String groupId) {
        return Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, groupId,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    }

    /**
     * Pre-creates topics. Maestro never auto-creates topics, so this must be
     * called before any publish or subscribe.
     *
     * @param topicNames the topics to create
     * @throws ExecutionException   if topic creation fails
     * @throws InterruptedException if interrupted while waiting
     */
    protected void createTopics(String... topicNames) throws ExecutionException, InterruptedException {
        var adminProps = Map.<String, Object>of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (var admin = AdminClient.create(adminProps)) {
            var newTopics = Arrays.stream(topicNames)
                    .map(name -> new NewTopic(name, 1, (short) 1))
                    .toList();
            admin.createTopics(newTopics).all().get();
        }
    }

    /**
     * Creates a topic with an explicit partition count — needed for
     * consumer-group routing tests where two consumers must each own a partition.
     *
     * @param topicName  the topic
     * @param partitions the partition count
     * @throws ExecutionException   if topic creation fails
     * @throws InterruptedException if interrupted while waiting
     */
    protected void createTopic(String topicName, int partitions)
            throws ExecutionException, InterruptedException {
        var adminProps = Map.<String, Object>of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (var admin = AdminClient.create(adminProps)) {
            admin.createTopics(java.util.List.of(new NewTopic(topicName, partitions, (short) 1)))
                    .all().get();
        }
    }

    /** @return the broker's bootstrap servers, for wiring Spring properties */
    protected static String bootstrapServers() {
        return kafka.getBootstrapServers();
    }
}
