package io.b2mash.maestro.store.postgres.config;

import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.store.postgres.PostgresWorkflowStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Pins the audit F8 fix: {@code maestro.enabled=false} is documented as the
 * master kill-switch, but before this fix only {@code MaestroAutoConfiguration}
 * honoured it — this module kept registering a real {@link PostgresWorkflowStore}
 * bean regardless of the flag whenever a {@link DataSource} was present (the
 * RED shape here is "beans present", not a crash — the constructor doesn't
 * touch the database eagerly).
 */
@DisplayName("PostgresStoreAutoConfiguration — maestro.enabled=false (audit F8)")
class PostgresStoreAutoConfigurationMaestroDisabledTest {

    @Test
    @DisplayName("maestro.enabled=false disables this module entirely — no WorkflowStore bean")
    void maestroDisabledMeansNoBeans() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PostgresStoreAutoConfiguration.class))
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("maestro.enabled=false", "maestro.service-name=x")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(WorkflowStore.class);
                    assertThat(ctx).doesNotHaveBean(PostgresWorkflowStore.class);
                });
    }
}
