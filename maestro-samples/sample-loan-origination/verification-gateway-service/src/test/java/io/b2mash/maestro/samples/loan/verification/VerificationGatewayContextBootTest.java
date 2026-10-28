package io.b2mash.maestro.samples.loan.verification;

import io.b2mash.maestro.samples.loan.verification.workflow.VerificationWorkflow;
import io.b2mash.maestro.spring.config.WorkflowRegistrar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * Boots the real verification-gateway-service application context — far
 * enough for {@code WorkflowRegistrar.afterSingletonsInstantiated} to validate
 * every {@code @DurableWorkflow} bean — and pins that
 * {@link VerificationWorkflow} is registered with a prototype factory (R55).
 *
 * <p>Sibling of the underwriting-service pin of the same shape: plan 05 made
 * the registrar fail startup on any workflow bean that is not
 * prototype-scoped, and the workflow tests never boot Spring, so a singleton
 * registration in any loan service would only surface when the E2E cluster
 * failed to start.
 *
 * <p>Only the transports differ from production: Postgres messaging and
 * locking replace Kafka and Valkey so the test needs no broker or cache, and
 * Boot's {@code @KafkaListener} containers are not started (the
 * {@code KafkaTemplate} the activities inject is still created — it connects
 * lazily). Workflow discovery and validation do not depend on the transport.
 *
 * <p>The container is started from a static initialiser, not
 * {@code @Container}, so it outlives Spring's cached context; Ryuk removes it
 * at JVM exit.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "maestro.messaging.type=postgres",
                "maestro.lock.type=postgres",
                "spring.kafka.listener.auto-startup=false",
        })
class VerificationGatewayContextBootTest {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("verification")
            .withUsername("test")
            .withPassword("test");

    static {
        POSTGRES.start();
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
    void contextStartsAndVerificationWorkflowIsRegisteredWithAPrototypeFactory() {
        var registration = registrar.getRegistration("verification");
        assertEquals("verification-gateway", registration.taskQueue());
        assertEquals(registration, registrar.getRegistration(VerificationWorkflow.class));

        var first = registration.workflowFactory().get();
        var second = registration.workflowFactory().get();
        assertInstanceOf(VerificationWorkflow.class, first);
        assertInstanceOf(VerificationWorkflow.class, second);
        assertNotSame(first, second, "one workflow object per run");
    }
}
