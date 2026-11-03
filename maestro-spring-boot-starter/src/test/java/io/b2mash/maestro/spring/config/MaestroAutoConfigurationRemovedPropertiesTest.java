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
    @DisplayName("without the property the engine starts")
    void startsWithoutTheProperty() {
        runner.run(ctx -> assertThat(ctx).hasNotFailed());
    }
}
