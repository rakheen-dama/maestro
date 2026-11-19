package io.b2mash.maestro.consumersmoke;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.spring.client.MaestroClient;
import io.b2mash.maestro.spring.client.WorkflowOptions;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One workflow, run to completion by a Spring Boot service that consumes
 * Maestro as published artifacts, against a real Postgres and a real Kafka
 * broker (one that never auto-creates topics, so the topics are pre-created
 * here, as in production). The workflow runs an activity, a durable sleep, a
 * signal that arrives through a {@code @MaestroSignalListener}, and a second
 * activity.
 */
@SpringBootTest
class ConsumerSmokeTest {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @SuppressWarnings("resource")
    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"))
            .withKraft()
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    /** The engine's signal topic for {@code maestro.service-name=smoke}, the listener's topic, and their dead letters. */
    private static final List<String> TOPICS = List.of("maestro.signals.smoke", "maestro.signals.smoke.DLT",
            DispatchListener.TOPIC, DispatchListener.TOPIC + ".DLT");

    static {
        POSTGRES.start();
        KAFKA.start();
        try (var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(TOPICS.stream().map(t -> new NewTopic(t, 1, (short) 1)).toList()).all()
                    .get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create topics " + TOPICS, e);
        }
    }

    @DynamicPropertySource
    static void backends(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private MaestroClient maestro;

    @Autowired
    private ShipmentActivitiesImpl activities;

    @Test
    void oneWorkflowRunsToCompletionOnThePublishedArtifacts() throws Exception {
        var version = System.getProperty("maestro.consumerSmoke.version");
        assertTrue(codeSource(MaestroClient.class).endsWith("/maestro-spring-boot-starter-" + version + ".jar"),
                "the starter under test is the published " + version + ": " + codeSource(MaestroClient.class));
        assertTrue(codeSource(WorkflowExecutor.class).endsWith("/maestro-core-" + version + ".jar"),
                "the engine under test is the published " + version + ": " + codeSource(WorkflowExecutor.class));

        // The dispatch may arrive before the run reaches its await, or before the run exists:
        // either way the signal is stored and consumed when the run reaches it.
        publishDispatch("o-1", "acme");
        var options = WorkflowOptions.builder().workflowId("shipment-o-1").build();
        var tracking = maestro.newWorkflow(ShipmentWorkflow.class, options)
                .startAndWait("o-1", Duration.ofSeconds(60), String.class);

        assertEquals("acme:parcel-o-1", tracking);
        assertEquals(List.of("pack:o-1", "ship:parcel-o-1"), activities.calls(), "each activity ran once");
    }

    private static void publishDispatch(String orderId, String carrier) throws Exception {
        var json = "{\"orderId\":\"" + orderId + "\",\"carrier\":\"" + carrier + "\"}";
        try (var producer = new KafkaProducer<String, byte[]>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.ACKS_CONFIG, "all"))) {
            producer.send(new ProducerRecord<>(DispatchListener.TOPIC, orderId, json.getBytes(StandardCharsets.UTF_8)))
                    .get(30, TimeUnit.SECONDS);
        }
    }

    private static String codeSource(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation().toString();
    }
}
