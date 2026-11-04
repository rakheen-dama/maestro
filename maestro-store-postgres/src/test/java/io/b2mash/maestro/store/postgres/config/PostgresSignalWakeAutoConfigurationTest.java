package io.b2mash.maestro.store.postgres.config;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.SignalNotifier;
import io.b2mash.maestro.spring.config.MaestroAutoConfiguration;
import io.b2mash.maestro.store.postgres.lock.PostgresDistributedLock;
import io.b2mash.maestro.store.postgres.lock.PostgresLockCleanerScheduler;
import io.b2mash.maestro.store.postgres.notify.PostgresSignalNotifier;
import io.b2mash.maestro.store.postgres.notify.PostgresWakeListener;
import io.b2mash.maestro.store.postgres.notify.WakeChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

/**
 * Verifies that a Postgres deployment gets the LISTEN/NOTIFY wake by default
 * — wired exactly as an application would wire it, through this module's
 * three auto-configurations plus the engine's, against a real database — and
 * that every documented way of backing off works.
 */
@DisplayName("PostgresSignalWakeAutoConfiguration")
class PostgresSignalWakeAutoConfigurationTest {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("maestro_wake_autoconfig_test")
                    .withUsername("test")
                    .withPassword("test");

    static {
        postgres.start();
    }

    private static final String SERVICE = "wake-autoconfig-test";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PostgresStoreAutoConfiguration.class,
                    MaestroAutoConfiguration.class,
                    PostgresLockAutoConfiguration.class,
                    PostgresSignalWakeAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(DataSource.class, PostgresSignalWakeAutoConfigurationTest::realDataSource)
            .withPropertyValues("maestro.service-name=" + SERVICE);

    @Test
    @DisplayName("by default the engine gets a PostgresSignalNotifier on a started listener — and the Postgres lock")
    void notifierPresentByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(WorkflowExecutor.class);
            assertThat(ctx).hasSingleBean(SignalNotifier.class);
            assertThat(ctx.getBean(SignalNotifier.class)).isInstanceOf(PostgresSignalNotifier.class);
            var listener = ctx.getBean(PostgresWakeListener.class);
            assertThat(listener.channel()).isEqualTo(WakeChannel.channelFor(SERVICE));
            await().atMost(Duration.ofSeconds(10)).until(listener::isConnected);
            // The lock fold: no maestro.lock.type needed any more.
            assertThat(ctx).hasSingleBean(DistributedLock.class);
            assertThat(ctx.getBean(DistributedLock.class)).isInstanceOf(PostgresDistributedLock.class);
            assertThat(ctx).hasSingleBean(PostgresLockCleanerScheduler.class);
        });
    }

    @Test
    @DisplayName("maestro.signal.wake-notify.enabled=false backs off entirely (PgBouncer transaction pooling)")
    void disabledByProperty() {
        runner.withPropertyValues("maestro.signal.wake-notify.enabled=false").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(WorkflowExecutor.class);
            assertThat(ctx).doesNotHaveBean(SignalNotifier.class);
            assertThat(ctx).doesNotHaveBean(PostgresWakeListener.class);
        });
    }

    @Test
    @DisplayName("maestro.enabled=false backs off entirely")
    void disabledWithMaestro() {
        runner.withPropertyValues("maestro.enabled=false").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(WorkflowExecutor.class);
            assertThat(ctx).doesNotHaveBean(SignalNotifier.class);
            assertThat(ctx).doesNotHaveBean(PostgresWakeListener.class);
        });
    }

    @Test
    @DisplayName("a user-supplied SignalNotifier wins, and no listener connection is held for nothing")
    void userSuppliedNotifierWins() {
        var custom = mock(SignalNotifier.class);
        runner.withBean("customNotifier", SignalNotifier.class, () -> custom).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(SignalNotifier.class);
            assertThat(ctx.getBean(SignalNotifier.class)).isSameAs(custom);
            assertThat(ctx).doesNotHaveBean(PostgresSignalNotifier.class);
            assertThat(ctx).doesNotHaveBean(PostgresWakeListener.class);
        });
    }

    @Test
    @DisplayName("without the engine (no WorkflowStore) nothing is created")
    void backsOffWithoutEngine() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        MaestroAutoConfiguration.class, PostgresSignalWakeAutoConfiguration.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(DataSource.class, PostgresSignalWakeAutoConfigurationTest::realDataSource)
                .withPropertyValues("maestro.service-name=" + SERVICE)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(SignalNotifier.class);
                    assertThat(ctx).doesNotHaveBean(PostgresWakeListener.class);
                });
    }

    private static DataSource realDataSource() {
        var ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }
}
