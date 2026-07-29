package io.b2mash.maestro.admin;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Base class for {@code maestro-admin} tests that boot the real Spring Boot
 * application against real Postgres and Kafka backends via Testcontainers.
 *
 * <p>Mirrors the pattern used by {@code PostgresIntegrationSupport} /
 * {@code KafkaSpringIntegrationSupport} in {@code maestro-integration-tests}:
 * both containers start from a static initialiser rather than through JUnit's
 * {@code @Testcontainers}/{@code @Container} extension, because that extension
 * stops a static container when its test <em>class</em> finishes — and Spring
 * caches the {@code ApplicationContext} across test classes, so a second suite
 * reusing the cached context would find its producer/consumer bound to a
 * broker that no longer exists. Ryuk removes both containers at JVM exit.
 *
 * <p>Unlike the engine's own integration tests, this module's Flyway
 * migrations ({@code classpath:db/migration/admin}) are applied by Spring
 * Boot's own {@code FlywayAutoConfiguration} during context refresh — nothing
 * in this app queries the database before that runs, so there is no need to
 * pre-migrate outside the Spring lifecycle the way the engine's
 * {@code StartupRecoveryRunner} forces {@code PostgresIntegrationSupport} to.
 *
 * <h2>Topics</h2>
 * <p>Maestro never auto-creates Kafka topics; every subclass must pre-create
 * the topic(s) it configures via {@link #createTopics(String...)} before the
 * Spring context starts (e.g. from a {@code @BeforeAll} method, which JUnit
 * runs before the test instance — and therefore the context — is created).
 *
 * <h2>Context isolation</h2>
 * <p>Each subclass should set a distinct {@code maestro.admin.events-topic}
 * and/or {@code maestro.admin.consumer-group} via {@code @SpringBootTest}
 * properties. Distinct properties mean a distinct context-cache key, so
 * suites never share a consumer group and cached contexts from earlier
 * suites keep consuming only their own topics.
 *
 * <h2>Thread Safety</h2>
 * <p>The containers and shared producer are static and thread-safe.
 */
abstract class AdminAppTestSupport {

    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("maestro_admin_test")
                    .withUsername("test")
                    .withPassword("test");

    @SuppressWarnings("resource")
    protected static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"))
                    .withKraft();

    private static final KafkaTemplate<String, byte[]> PRODUCER;

    /** Serializes test events; the app under test has its own mapper inside the context. */
    protected static final JsonMapper MAPPER = JsonMapper.builder().build();

    static {
        POSTGRES.start();
        KAFKA.start();

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
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

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

    /**
     * Publishes a test event, blocking until the broker acknowledges it, so a
     * test never races its own producer.
     *
     * @param topic the destination topic
     * @param key   the record key
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
}
