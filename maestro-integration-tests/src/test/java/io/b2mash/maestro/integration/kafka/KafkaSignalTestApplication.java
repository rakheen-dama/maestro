package io.b2mash.maestro.integration.kafka;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Spring Boot application under test for the P1 Kafka suites.
 *
 * <p>It declares nothing but a Jackson mapper: everything else — the Postgres
 * store, the engine, the Kafka {@code WorkflowMessaging}, the
 * {@code @MaestroSignalListener} bean post-processor and the
 * {@code SignalSubscriptionRunner} — must arrive through the real
 * auto-configuration chain, because that chain is part of what these suites
 * verify.
 *
 * <p>Its package is also the auto-configuration package, which is what lets
 * {@code DurableWorkflowBeanRegistrar} discover the {@code @DurableWorkflow}
 * fixtures in {@link KafkaTestWorkflows} and {@link AdminCommandWorkflows}.
 *
 * <h2>Why the ObjectMapper is declared here</h2>
 * <p>{@code maestro-integration-tests} deliberately does not depend on
 * {@code spring-boot-starter-json} (or webmvc), so no Jackson auto-configuration
 * is on the classpath and no {@code ObjectMapper} bean would exist. Maestro's
 * auto-configuration requires one. Declaring it explicitly keeps the module's
 * dependency surface minimal and the bean deterministic.
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; the {@link ObjectMapper} it produces is thread-safe.
 */
@SpringBootApplication
public class KafkaSignalTestApplication {

    /**
     * @return the Jackson 3 mapper used by the engine, the store and the Kafka
     *         adapters in every P1 context
     */
    @Bean
    public ObjectMapper objectMapper() {
        return JsonMapper.builder().build();
    }

    /**
     * @return the {@link AdminCommandWorkflows.FlakyActivities} implementation
     *         every context needs so {@link AdminCommandWorkflows.FlakyWorkflow}
     *         can be wired — see that class's Javadoc for why this bean is
     *         global rather than scoped to {@code AdminCommandKafkaIT}
     */
    @Bean
    public AdminCommandWorkflows.FlakyActivitiesImpl flakyActivities() {
        return new AdminCommandWorkflows.FlakyActivitiesImpl();
    }
}
