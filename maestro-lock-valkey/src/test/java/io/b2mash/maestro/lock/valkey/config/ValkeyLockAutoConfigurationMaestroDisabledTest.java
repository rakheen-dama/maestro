package io.b2mash.maestro.lock.valkey.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the audit F8 fix: {@code maestro.enabled=false} is documented as the
 * master kill-switch, but before this fix only {@code MaestroAutoConfiguration}
 * honoured it — this module kept wiring a real {@code RedisClient} and opening
 * live Valkey connections regardless of the flag.
 *
 * <p>Unlike the messaging modules (which crash on a missing
 * {@code MaestroProperties} bean), this module's RED shape is different: the
 * context <em>succeeds</em> today and opens real connections
 * ({@code maestroLockConnection} calls {@code RedisClient.connect()},
 * {@code valkeySignalNotifier} opens two more). Requesting those beans (or
 * letting the context eagerly instantiate every singleton, which
 * {@link ApplicationContextRunner} does by default) would try to reach a
 * Valkey server that isn't running in this test.
 *
 * <p>So the pin here is on bean <em>definitions</em>, not instances: a
 * {@link LazyInitializationBeanFactoryPostProcessor} (the same mechanism
 * behind {@code spring.main.lazy-initialization=true}) is registered so
 * {@code refresh()} populates bean definitions without instantiating any of
 * them, and the assertion reads
 * {@code containsBeanDefinition("maestroRedisClient")} directly off the bean
 * factory — never {@code getBean()}, never a socket.
 */
@DisplayName("ValkeyLockAutoConfiguration — maestro.enabled=false (audit F8)")
class ValkeyLockAutoConfigurationMaestroDisabledTest {

    @Test
    @DisplayName("maestro.enabled=false disables this module entirely — no bean DEFINITIONS registered, no connection attempted")
    void maestroDisabledMeansNoBeanDefinitions() {
        new ApplicationContextRunner()
                .withInitializer(context ->
                        context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()))
                .withConfiguration(AutoConfigurations.of(ValkeyLockAutoConfiguration.class))
                .withPropertyValues("maestro.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBeanFactory().containsBeanDefinition("maestroRedisClient"))
                            .as("RedisClient bean definition must not even be registered when maestro.enabled=false")
                            .isFalse();
                    assertThat(ctx.getBeanFactory().containsBeanDefinition("maestroLockConnection")).isFalse();
                });
    }
}
