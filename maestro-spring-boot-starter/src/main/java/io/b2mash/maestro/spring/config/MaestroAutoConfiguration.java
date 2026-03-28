package io.b2mash.maestro.spring.config;

import io.b2mash.maestro.core.engine.ActivityProxyFactory;
import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.SignalNotifier;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.spring.client.MaestroClient;
import io.b2mash.maestro.spring.proxy.ActivityStubBeanPostProcessor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/**
 * Auto-configuration for the Maestro durable workflow engine.
 *
 * <p>Activates when:
 * <ul>
 *   <li>{@code maestro.enabled} is {@code true} (default)</li>
 *   <li>A {@link WorkflowStore} bean exists (provided by a store module
 *       such as {@code maestro-store-postgres})</li>
 * </ul>
 *
 * <p>Optional SPIs ({@link DistributedLock}, {@link WorkflowMessaging}) are
 * injected when available. When absent, the engine runs in single-node mode
 * without distributed locking or cross-service messaging.
 *
 * @see MaestroProperties
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "maestro", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(WorkflowStore.class)
@EnableConfigurationProperties(MaestroProperties.class)
public class MaestroAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PayloadSerializer maestroPayloadSerializer(ObjectMapper objectMapper) {
        return new PayloadSerializer(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public RetryExecutor maestroRetryExecutor() {
        return new RetryExecutor();
    }

    @Bean
    @ConditionalOnMissingBean
    public ActivityProxyFactory maestroActivityProxyFactory() {
        return new ActivityProxyFactory();
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowExecutor maestroWorkflowExecutor(
            WorkflowStore store,
            @Nullable DistributedLock distributedLock,
            @Nullable WorkflowMessaging messaging,
            @Nullable SignalNotifier signalNotifier,
            PayloadSerializer serializer,
            MaestroProperties properties
    ) {
        var serviceName = properties.getServiceName();
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalStateException(
                    "maestro.service-name must be set. Configure it in application.yml or application.properties.");
        }
        return new WorkflowExecutor(
                store, distributedLock, messaging, signalNotifier, serializer, serviceName
        );
    }

    @Bean
    public ActivityStubBeanPostProcessor maestroActivityStubBeanPostProcessor() {
        return new ActivityStubBeanPostProcessor();
    }

    @Bean
    public WorkflowRegistrar maestroWorkflowRegistrar(WorkflowExecutor executor) {
        return new WorkflowRegistrar(executor);
    }

    @Bean
    public StartupRecoveryRunner maestroStartupRecoveryRunner(
            WorkflowExecutor executor,
            WorkflowRegistrar registrar,
            MaestroProperties properties
    ) {
        return new StartupRecoveryRunner(executor, registrar, properties);
    }

    @Bean
    public GracefulShutdownHandler maestroGracefulShutdownHandler(WorkflowExecutor executor) {
        return new GracefulShutdownHandler(executor);
    }

    @Bean
    @ConditionalOnMissingBean
    public MaestroClient maestroClient(
            WorkflowExecutor executor,
            WorkflowRegistrar registrar,
            PayloadSerializer serializer,
            WorkflowStore store
    ) {
        return new MaestroClient(executor, registrar, serializer, store);
    }
}
