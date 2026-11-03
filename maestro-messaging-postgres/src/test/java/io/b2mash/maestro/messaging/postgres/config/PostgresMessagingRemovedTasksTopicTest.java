package io.b2mash.maestro.messaging.postgres.config;

import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.spring.config.MaestroAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The removed {@code maestro.messaging.topics.tasks} property fails startup on
 * the Postgres transport too — the guard is transport-neutral
 * ({@code MaestroAutoConfiguration}), so an operator moving between
 * transports cannot carry the dead property along silently.
 */
@DisplayName("maestro.messaging.topics.tasks fails startup on the Postgres transport")
class PostgresMessagingRemovedTasksTopicTest {

    @Test
    @DisplayName("the removed property fails startup with messaging.type=postgres")
    void tasksTopicPropertyFailsStartup() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PostgresMessagingAutoConfiguration.class, MaestroAutoConfiguration.class))
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(WorkflowStore.class, () -> mock(WorkflowStore.class))
                .withPropertyValues("maestro.messaging.type=postgres", "maestro.service-name=pg-test",
                        "maestro.messaging.topics.tasks=maestro.tasks.orders")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("maestro.messaging.topics.tasks has been removed");
                });
    }
}
