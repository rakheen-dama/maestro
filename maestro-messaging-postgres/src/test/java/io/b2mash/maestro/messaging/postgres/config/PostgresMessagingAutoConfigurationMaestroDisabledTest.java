package io.b2mash.maestro.messaging.postgres.config;

import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.messaging.postgres.PostgresNotificationListener;
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
 * honoured it — this module kept wiring a real
 * {@link PostgresNotificationListener} (which opens a dedicated LISTEN/NOTIFY
 * connection) and crashed trying to resolve {@code MaestroProperties} (a bean
 * only {@code MaestroAutoConfiguration} registers) once the engine itself had
 * backed off.
 *
 * <p>This test loads {@link PostgresMessagingAutoConfiguration} in isolation
 * — exactly the shape that reproduces the crash — and asserts the fixed
 * behaviour: {@code maestro.enabled=false} makes the whole class back off,
 * no beans, no crash.
 */
@DisplayName("PostgresMessagingAutoConfiguration — maestro.enabled=false (audit F8)")
class PostgresMessagingAutoConfigurationMaestroDisabledTest {

    @Test
    @DisplayName("maestro.enabled=false disables this module entirely — no beans, no crash")
    void maestroDisabledMeansNoBeansAndNoCrash() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PostgresMessagingAutoConfiguration.class))
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("maestro.enabled=false", "maestro.messaging.type=postgres")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(WorkflowMessaging.class);
                    assertThat(ctx).doesNotHaveBean(PostgresNotificationListener.class);
                });
    }
}
