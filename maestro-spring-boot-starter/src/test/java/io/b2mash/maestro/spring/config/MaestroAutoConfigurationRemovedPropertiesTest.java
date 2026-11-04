package io.b2mash.maestro.spring.config;

import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Removed properties fail startup in {@link MaestroAutoConfiguration} itself,
 * whichever transport (or none) is on the classpath, rather than being bound
 * to nothing and silently ignored.
 */
@DisplayName("Removed maestro.* properties fail startup, transport-neutrally")
class MaestroAutoConfigurationRemovedPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MaestroAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(WorkflowStore.class, InMemoryWorkflowStore::new)
            .withPropertyValues("maestro.service-name=removed-properties-test");

    @Test
    @DisplayName("maestro.messaging.topics.tasks fails startup with no transport at all")
    void tasksTopicPropertyFailsStartup() {
        runner.withPropertyValues("maestro.messaging.topics.tasks=maestro.tasks.orders")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("maestro.messaging.topics.tasks has been removed")
                            .hasMessageContaining("maestro.tasks.*");
                });
    }

    @Test
    @DisplayName("maestro.lock.type=valkey fails startup naming the removed backend")
    void valkeyLockTypeFailsStartup() {
        runner.withPropertyValues("maestro.lock.type=valkey")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("maestro.lock.type='valkey' is not supported")
                            .hasMessageContaining("Valkey lock backend was removed")
                            .hasMessageContaining("only 'postgres' is available");
                });
    }

    @Test
    @DisplayName("maestro.messaging.type=postgres fails startup naming the removed transport")
    void postgresMessagingTypeFailsStartup() {
        runner.withPropertyValues("maestro.messaging.type=postgres")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("maestro.messaging.type='postgres' is not supported")
                            .hasMessageContaining("Postgres messaging transport was removed")
                            .hasMessageContaining("only 'kafka' is available");
                });
    }

    @Test
    @DisplayName("the supported values, spelled explicitly, still start")
    void explicitSupportedValuesStart() {
        runner.withPropertyValues("maestro.lock.type=postgres", "maestro.messaging.type=kafka")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("without the property the engine starts")
    void startsWithoutTheProperty() {
        runner.run(ctx -> assertThat(ctx).hasNotFailed());
    }
}
