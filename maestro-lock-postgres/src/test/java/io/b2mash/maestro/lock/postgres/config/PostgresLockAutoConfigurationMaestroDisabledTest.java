package io.b2mash.maestro.lock.postgres.config;

import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.lock.postgres.PostgresDistributedLock;
import io.b2mash.maestro.lock.postgres.PostgresLockCleaner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Pins the audit F8 fix: {@code maestro.enabled=false} is documented as the
 * master kill-switch, but before this fix only {@code MaestroAutoConfiguration}
 * honoured it — this module kept registering {@link PostgresDistributedLock}
 * and {@link PostgresLockCleaner} beans regardless of the flag (neither
 * constructor touches the database eagerly, so the RED shape here is
 * "beans present", not a crash or a live connection).
 */
@DisplayName("PostgresLockAutoConfiguration — maestro.enabled=false (audit F8)")
class PostgresLockAutoConfigurationMaestroDisabledTest {

    @Test
    @DisplayName("maestro.enabled=false disables this module entirely — no beans")
    void maestroDisabledMeansNoBeans() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PostgresLockAutoConfiguration.class))
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withPropertyValues("maestro.enabled=false", "maestro.lock.type=postgres")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(DistributedLock.class);
                    assertThat(ctx).doesNotHaveBean(PostgresLockCleaner.class);
                });
    }
}
