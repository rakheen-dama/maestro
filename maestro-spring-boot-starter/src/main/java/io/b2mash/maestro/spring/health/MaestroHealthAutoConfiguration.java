package io.b2mash.maestro.spring.health;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.spring.config.MaestroAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for {@link MaestroHealthIndicator}.
 *
 * <p>Activates when:
 * <ul>
 *   <li>Spring Boot Actuator's {@link HealthIndicator} is on the classpath</li>
 *   <li>{@link MaestroAutoConfiguration} has activated — a {@link WorkflowExecutor}
 *       bean exists, which itself requires {@code maestro.enabled=true} (default)
 *       and a configured {@link WorkflowStore}</li>
 * </ul>
 *
 * <p>{@code spring-boot-starter-actuator} is an optional (compile-only)
 * dependency of the starter: when Actuator is not on the consumer's
 * classpath, this configuration — and the {@code maestro} health indicator
 * it would register — is absent entirely, rather than failing to start.
 *
 * @see MaestroHealthIndicator
 */
@AutoConfiguration(after = MaestroAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnBean(WorkflowExecutor.class)
public class MaestroHealthAutoConfiguration {

    /**
     * Creates the {@link MaestroHealthIndicator} bean if one does not already exist.
     *
     * @param store    the workflow store to probe for reachability
     * @param executor the executor whose poller and running-workflow state is reported
     * @return a configured {@link MaestroHealthIndicator}
     */
    @Bean
    @ConditionalOnMissingBean
    public MaestroHealthIndicator maestroHealthIndicator(WorkflowStore store, WorkflowExecutor executor) {
        return new MaestroHealthIndicator(store, executor);
    }
}
