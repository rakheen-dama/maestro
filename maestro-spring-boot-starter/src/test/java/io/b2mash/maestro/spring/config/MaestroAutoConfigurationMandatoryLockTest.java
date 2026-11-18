package io.b2mash.maestro.spring.config;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pilot readiness P3: a {@link DistributedLock} is mandatory. A context with a
 * store and no lock bean fails startup with a message naming the remedy,
 * instead of starting an engine that runs workflows unlocked.
 */
@DisplayName("Startup without a DistributedLock bean fails")
class MaestroAutoConfigurationMandatoryLockTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MaestroAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(WorkflowStore.class, InMemoryWorkflowStore::new)
            .withPropertyValues("maestro.service-name=mandatory-lock-test");

    @Test
    @DisplayName("no DistributedLock bean: startup fails naming maestro-store-postgres and the test lock")
    void noLockBeanFailsStartup() {
        runner.run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(MaestroAutoConfiguration.MISSING_LOCK_MESSAGE)
                    .hasMessageContaining("maestro-store-postgres")
                    .hasMessageContaining("InMemoryDistributedLock");
        });
    }

    @Test
    @DisplayName("a DistributedLock bean: the engine starts on it")
    void aLockBeanStarts() {
        runner.withBean(DistributedLock.class, InMemoryDistributedLock::new)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(WorkflowExecutor.class);
                });
    }
}
