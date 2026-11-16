package io.b2mash.maestro.samples.loan.underwriting;

import io.b2mash.maestro.messaging.kafka.MaestroKafkaBeans;
import io.b2mash.maestro.samples.loan.underwriting.workflow.UnderwritingWorkflow;
import io.b2mash.maestro.spring.config.WorkflowRegistrar;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;

import static org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * Boots the real underwriting-service application context — far enough for
 * {@code WorkflowRegistrar.afterSingletonsInstantiated} to validate every
 * {@code @DurableWorkflow} bean — and pins that {@link UnderwritingWorkflow}
 * is registered with a prototype factory (R55).
 *
 * <p>Regression it guards: plan 05 made the registrar reject any workflow bean
 * that is not prototype-scoped, and this service declared the workflow as a
 * singleton {@code @Bean}, so the service could not start. The workflow tests
 * use {@code TestWorkflowEnvironment} and never boot Spring, so nothing caught
 * it until the loan E2E cluster failed to come up.
 *
 * <p>Only the transport differs from production: Maestro's Kafka messaging
 * auto-configuration is excluded, so no {@code WorkflowMessaging} bean exists
 * (the engine tolerates that — inbound signal subscription is simply
 * disabled) and {@code @MaestroSignalListener} is inert (its
 * {@code BeanPostProcessor} is registered by that auto-configuration). Boot's
 * {@code @KafkaListener} containers are not started, and the activities'
 * {@code maestroKafkaTemplate} is a stand-in declared below, because the
 * auto-configuration that declares it is excluded; it connects lazily, so no
 * broker is needed.
 * Locking is the Postgres lock, as in production. Workflow discovery and
 * validation do not depend on the transport.
 *
 * <p>The container is started from a static initialiser, not
 * {@code @Container}, so it outlives Spring's cached context; Ryuk removes it
 * at JVM exit.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.autoconfigure.exclude="
                        + "io.b2mash.maestro.messaging.kafka.config.KafkaMessagingAutoConfiguration",
                "spring.kafka.listener.auto-startup=false",
        })
class UnderwritingServiceContextBootTest {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("underwriting")
            .withUsername("test")
            .withPassword("test");

    static {
        POSTGRES.start();
    }

    /**
     * Stands in for the {@code maestroKafkaTemplate} that the excluded
     * {@code KafkaMessagingAutoConfiguration} would declare, so the messaging
     * activities' qualified constructor parameter resolves.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class MaestroTemplateStandIn {

        @Bean(name = MaestroKafkaBeans.TEMPLATE, defaultCandidate = false)
        KafkaTemplate<String, byte[]> maestroKafkaTemplate() {
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                    BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
                    KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class)));
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    WorkflowRegistrar registrar;

    @Test
    void contextStartsAndUnderwritingWorkflowIsRegisteredWithAPrototypeFactory() {
        var registration = registrar.getRegistration("underwriting");
        assertEquals("underwriting", registration.taskQueue());
        assertEquals(registration, registrar.getRegistration(UnderwritingWorkflow.class));

        var first = registration.workflowFactory().get();
        var second = registration.workflowFactory().get();
        assertInstanceOf(UnderwritingWorkflow.class, first);
        assertInstanceOf(UnderwritingWorkflow.class, second);
        assertNotSame(first, second, "one workflow object per run");
    }
}
