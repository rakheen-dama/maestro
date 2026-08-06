package io.b2mash.maestro.messaging.postgres.config;

import io.b2mash.maestro.core.spi.SignalNotifier;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.messaging.postgres.PostgresMessageCleaner;
import io.b2mash.maestro.messaging.postgres.PostgresNotificationListener;
import io.b2mash.maestro.messaging.postgres.PostgresRedeliveryConfig;
import io.b2mash.maestro.messaging.postgres.PostgresSignalNotifier;
import io.b2mash.maestro.messaging.postgres.PostgresWorkflowMessaging;
import io.b2mash.maestro.spring.config.MaestroAutoConfiguration;
import io.b2mash.maestro.spring.config.MaestroProperties;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.time.Duration;

/**
 * Auto-configuration for PostgreSQL-based workflow messaging.
 *
 * <p>Activates when:
 * <ul>
 *   <li>The PostgreSQL JDBC driver ({@code org.postgresql.PGConnection}) is on the classpath</li>
 *   <li>{@code maestro.messaging.type} is {@code "postgres"}</li>
 * </ul>
 *
 * <p>Creates the following beans:
 * <ul>
 *   <li>{@link PostgresNotificationListener} — shared LISTEN/NOTIFY connection manager</li>
 *   <li>{@link PostgresWorkflowMessaging} — the {@link WorkflowMessaging} SPI implementation</li>
 *   <li>{@link PostgresSignalNotifier} — the {@link SignalNotifier} SPI implementation</li>
 *   <li>{@link PostgresMessageCleaner} — cleanup utility for processed messages</li>
 * </ul>
 *
 * <p>This module requires a {@link DataSource} and {@link ObjectMapper} in the
 * Spring context, both of which are typically provided by Spring Boot's
 * auto-configuration for JDBC and Jackson.
 *
 * @see PostgresWorkflowMessaging
 * @see PostgresSignalNotifier
 * @see MaestroAutoConfiguration
 */
@NullMarked
@AutoConfiguration(after = MaestroAutoConfiguration.class)
@ConditionalOnClass(name = "org.postgresql.PGConnection")
// Audit F8: maestro.enabled=false is documented as the master kill-switch
// (see MaestroAutoConfiguration), but this class previously had no direct
// gate on it — it kept wiring a real PostgresNotificationListener (a live
// LISTEN/NOTIFY connection) and crashed resolving MaestroProperties (a bean
// only MaestroAutoConfiguration registers) once the engine itself had
// backed off. See PostgresMessagingAutoConfigurationMaestroDisabledTest.
// (Boot 4's @ConditionalOnProperty is @Repeatable — see OnPropertyCondition
// — so stacking it here composes as AND with the property gate below.)
@ConditionalOnProperty(prefix = "maestro", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "maestro.messaging", name = "type", havingValue = "postgres")
public class PostgresMessagingAutoConfiguration {

    /**
     * Default retention period for cleaned-up messages.
     */
    private static final Duration DEFAULT_RETENTION = Duration.ofHours(24);

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public PostgresNotificationListener maestroPostgresNotificationListener(DataSource dataSource) {
        var listener = new PostgresNotificationListener(dataSource);
        listener.start();
        return listener;
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(WorkflowMessaging.class)
    public PostgresWorkflowMessaging postgresWorkflowMessaging(
            DataSource dataSource,
            ObjectMapper objectMapper,
            PostgresNotificationListener maestroPostgresNotificationListener,
            MaestroProperties properties
    ) {
        var redelivery = properties.getMessaging().redelivery();
        return new PostgresWorkflowMessaging(
                dataSource,
                objectMapper,
                maestroPostgresNotificationListener,
                new PostgresRedeliveryConfig(
                        redelivery.enabled(),
                        redelivery.maxAttempts(),
                        redelivery.initialInterval(),
                        redelivery.multiplier(),
                        redelivery.maxInterval()));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(SignalNotifier.class)
    public PostgresSignalNotifier postgresSignalNotifier(
            DataSource dataSource,
            PostgresNotificationListener maestroPostgresNotificationListener
    ) {
        return new PostgresSignalNotifier(dataSource, maestroPostgresNotificationListener);
    }

    @Bean
    @ConditionalOnMissingBean
    public PostgresMessageCleaner maestroPostgresMessageCleaner(DataSource dataSource) {
        return new PostgresMessageCleaner(dataSource, DEFAULT_RETENTION);
    }
}
