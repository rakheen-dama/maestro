package io.b2mash.maestro.integration.kafka;

import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Base class for the P1 suites: a real Spring Boot application wired to a real
 * Kafka broker <em>and</em> a real Postgres store, both from Testcontainers.
 *
 * <h2>Why this class exists instead of {@code support.KafkaIntegrationSupport}</h2>
 * <p>{@code KafkaIntegrationSupport} starts its broker through JUnit's
 * {@code @Testcontainers}/{@code @Container} extension, which stops the container
 * when each test <em>class</em> ends. Spring caches an {@code ApplicationContext}
 * across test classes, so the second suite onwards would reuse producer and
 * consumer factories bound to a broker that no longer exists. This class
 * therefore starts the broker from a static initialiser and never stops it —
 * exactly the reasoning {@link PostgresIntegrationSupport} documents for
 * Postgres. Ryuk removes the container when the JVM exits.
 *
 * <h2>Schema</h2>
 * <p>Flyway runs in the static initialiser, not in a {@code @BeforeEach}: the
 * Spring context is built before any {@code @BeforeEach} runs and its
 * {@code StartupRecoveryRunner} queries {@code maestro_workflow_instance} during
 * startup, so the schema has to exist before the context is created. The
 * inherited per-test truncation still provides isolation.
 *
 * <h2>Topics</h2>
 * <p>Maestro never auto-creates topics, so every suite pre-creates the ones it
 * uses from a {@code @BeforeAll} method — JUnit runs those before the test
 * instance is created and therefore before the Spring context is loaded.
 *
 * <h2>Context isolation</h2>
 * <p>Each suite sets a distinct {@code maestro.service-name} and distinct topic
 * names. Distinct properties mean distinct context cache keys, so suites never
 * share a consumer group; cached contexts from earlier suites stay alive but
 * consume only their own topics.
 *
 * <h2>Thread Safety</h2>
 * <p>The container, producer and admin helpers are static and thread-safe;
 * per-test state is inherited from {@link PostgresIntegrationSupport} and
 * confined to the test thread.
 */
@Tag("integration")
abstract class KafkaSpringIntegrationSupport extends PostgresIntegrationSupport {

    @SuppressWarnings("resource")
    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"))
                    .withKraft();

    /** Producer shared by every suite — the broker is shared, so this can be too. */
    private static final KafkaTemplate<String, byte[]> PRODUCER;

    /** Serializes test events; the engine has its own mapper inside the context. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    static {
        KAFKA.start();

        var dataSource = newDataSource();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        PRODUCER = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.ACKS_CONFIG, "all")));
    }

    /**
     * Points the Spring application at both containers. Inherited by every
     * suite, so no suite repeats the wiring.
     *
     * @param registry the registry Spring exposes for dynamic properties
     */
    @DynamicPropertySource
    static void backends(DynamicPropertyRegistry registry) {
        var dataSource = newDataSource();
        registry.add("spring.datasource.url", dataSource::getUrl);
        registry.add("spring.datasource.username", dataSource::getUser);
        registry.add("spring.datasource.password", dataSource::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    // ── Topics ──────────────────────────────────────────────────────────

    /**
     * Pre-creates topics with a single partition each.
     *
     * @param topicNames the topics to create
     * @throws ExecutionException   if topic creation fails
     * @throws InterruptedException if interrupted while waiting
     */
    protected static void createTopics(String... topicNames)
            throws ExecutionException, InterruptedException {
        try (var admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(Arrays.stream(topicNames)
                    .map(name -> new NewTopic(name, 1, (short) 1))
                    .toList()).all().get();
        }
    }

    // ── Publishing ──────────────────────────────────────────────────────

    /**
     * Publishes a test event, blocking until the broker acknowledges it, so a
     * test never races its own producer.
     *
     * @param topic the destination topic
     * @param key   the record key (Maestro keys by workflow ID)
     * @param event the event; serialized with Jackson 3
     */
    protected static void publish(String topic, String key, Object event) {
        try {
            PRODUCER.send(topic, key, MAPPER.writeValueAsBytes(event)).get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to publish to '" + topic + "'", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted publishing to '" + topic + "'", e);
        }
    }

    // ── Persisted-state assertions ──────────────────────────────────────

    /**
     * Waits until a workflow reaches a status, reading the instance row each
     * poll — the engine's in-memory state is never the subject of an assertion.
     *
     * @param workflowId the business workflow ID
     * @param expected   the status to wait for
     * @param bound      how long to wait
     * @return the instance in that status
     */
    protected WorkflowInstance awaitStatus(String workflowId, WorkflowStatus expected, Duration bound) {
        await().atMost(bound).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            var instance = store.getInstance(workflowId);
            assertTrue(instance.isPresent(), "no instance row for workflow " + workflowId);
            assertEquals(expected, instance.get().status(),
                    "workflow " + workflowId + " never reached " + expected);
        });
        return store.getInstance(workflowId).orElseThrow();
    }

    /**
     * Reads every signal row for a workflow straight from Postgres.
     *
     * <p>The store SPI only exposes <em>unconsumed</em> signals, so duplicate
     * tolerance and consumption can only be asserted against the table itself.
     *
     * @param workflowId the business workflow ID
     * @return the rows, oldest first
     * @throws SQLException if the query fails
     */
    protected List<SignalRow> signalRows(String workflowId) throws SQLException {
        var sql = "SELECT workflow_instance_id, signal_name, consumed"
                + " FROM maestro_workflow_signal WHERE workflow_id = ? ORDER BY received_at";
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, workflowId);
            try (var rs = ps.executeQuery()) {
                var rows = new ArrayList<SignalRow>();
                while (rs.next()) {
                    rows.add(new SignalRow(
                            (UUID) rs.getObject("workflow_instance_id"),
                            rs.getString("signal_name"),
                            rs.getBoolean("consumed")));
                }
                return rows;
            }
        }
    }

    /**
     * A row of {@code maestro_workflow_signal}, as far as these suites care.
     *
     * @param workflowInstanceId the owning instance, {@code null} before adoption
     * @param signalName         the signal name
     * @param consumed           whether an await has claimed the row
     */
    protected record SignalRow(@Nullable UUID workflowInstanceId, String signalName, boolean consumed) {}

    // ── Raw adapter wiring ──────────────────────────────────────────────

    /**
     * @return the shared producer, for suites that construct a
     *         {@code KafkaWorkflowMessaging} of their own to test the adapter
     *         directly
     */
    protected static KafkaTemplate<String, byte[]> producer() {
        return PRODUCER;
    }

    /**
     * Builds a consumer factory for an isolated group, reading from the earliest
     * offset so a subscriber never has to sleep for its partition assignment
     * before the producer runs.
     *
     * @param groupId the consumer group
     * @return a factory over {@code byte[]} values
     */
    protected static ConsumerFactory<String, byte[]> consumerFactory(String groupId) {
        return new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, groupId,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
    }

    // ── Consuming ───────────────────────────────────────────────────────

    /**
     * Starts a throwaway consumer over a topic in a brand-new group reading from
     * the earliest offset.
     *
     * <p>Because the group is new and starts at the earliest offset, a recorder
     * created <em>after</em> the records were produced still sees them — which is
     * how these suites avoid sleeping for a partition assignment.
     *
     * @param topic the topic to record
     * @return an open recorder; close it to stop the container
     */
    protected static RecordRecorder recorderFor(String topic) {
        return new RecordRecorder(consumerFactory("recorder-" + UUID.randomUUID()), topic);
    }

    /**
     * Collects every record on a topic for later assertion.
     *
     * <h2>Thread Safety</h2>
     * <p>Records arrive on the container's consumer thread and are published
     * through a copy-on-write list, so test threads may read at any time.
     */
    protected static final class RecordRecorder implements AutoCloseable {

        private final ConcurrentMessageListenerContainer<String, byte[]> container;
        private final List<Recorded> recorded = new CopyOnWriteArrayList<>();

        private RecordRecorder(ConsumerFactory<String, byte[]> consumerFactory, String topic) {
            var props = new ContainerProperties(topic);
            props.setGroupId("recorder-" + UUID.randomUUID());
            props.setMessageListener((MessageListener<String, byte[]>) consumed ->
                    recorded.add(new Recorded(consumed.key(), consumed.value())));
            this.container = new ConcurrentMessageListenerContainer<>(consumerFactory, props);
            this.container.start();
        }

        /**
         * Deserializes everything recorded so far.
         *
         * @param type the expected message type
         * @param <T>  the message type
         * @return the messages, in the order they were consumed
         */
        <T> List<T> messages(Class<T> type) {
            return recorded.stream().map(r -> MAPPER.readValue(r.value(), type)).toList();
        }

        /**
         * @return the record keys, in the order they were consumed and index-aligned
         *         with {@link #messages(Class)} — Maestro keys by workflow ID to
         *         keep a workflow's messages on one partition
         */
        List<String> keys() {
            return recorded.stream().map(Recorded::key).toList();
        }

        /** One consumed record: its key and raw value. */
        private record Recorded(String key, byte[] value) {}

        @Override
        public void close() {
            container.stop();
        }
    }
}
