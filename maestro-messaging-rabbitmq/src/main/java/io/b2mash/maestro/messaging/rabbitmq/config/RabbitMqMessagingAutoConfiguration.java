package io.b2mash.maestro.messaging.rabbitmq.config;

import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.messaging.rabbitmq.RabbitMqWorkflowMessaging;
import io.b2mash.maestro.spring.config.MaestroAutoConfiguration;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/**
 * Auto-configuration for RabbitMQ-based workflow messaging.
 *
 * <p>Activates when:
 * <ul>
 *   <li>Spring AMQP's {@link RabbitTemplate} is on the classpath</li>
 *   <li>{@code maestro.messaging.type} is {@code "rabbitmq"}</li>
 * </ul>
 *
 * <p>Creates the following bean:
 * <ul>
 *   <li>{@link RabbitMqWorkflowMessaging} — the {@link WorkflowMessaging} SPI implementation</li>
 * </ul>
 *
 * <p>Injects {@link RabbitTemplate}, {@link ConnectionFactory}, and
 * {@link ObjectMapper} from the Spring context. The {@link RabbitTemplate}
 * and {@link ConnectionFactory} are typically provided by Spring Boot's
 * RabbitMQ auto-configuration ({@code spring-boot-starter-amqp}).
 *
 * <p>The bean is guarded with {@link ConditionalOnMissingBean} to allow
 * user overrides.
 *
 * @see RabbitMqWorkflowMessaging
 */
@AutoConfiguration(after = MaestroAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.amqp.rabbit.core.RabbitTemplate")
@ConditionalOnProperty(prefix = "maestro.messaging", name = "type", havingValue = "rabbitmq")
public class RabbitMqMessagingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(WorkflowMessaging.class)
    public RabbitMqWorkflowMessaging rabbitMqWorkflowMessaging(
            RabbitTemplate rabbitTemplate,
            ConnectionFactory connectionFactory,
            ObjectMapper objectMapper
    ) {
        return new RabbitMqWorkflowMessaging(rabbitTemplate, connectionFactory, objectMapper);
    }
}
