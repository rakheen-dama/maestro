package io.b2mash.maestro.messaging.postgres.config;

import io.b2mash.maestro.core.spi.SignalNotifier;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.messaging.postgres.PostgresMessageCleaner;
import io.b2mash.maestro.messaging.postgres.PostgresNotificationListener;
import io.b2mash.maestro.messaging.postgres.PostgresSignalNotifier;
import io.b2mash.maestro.messaging.postgres.PostgresWorkflowMessaging;
import io.b2mash.maestro.spring.config.MaestroAutoConfiguration;
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
            PostgresNotificationListener maestroPostgresNotificationListener
    ) {
        return new PostgresWorkflowMessaging(dataSource, objectMapper, maestroPostgresNotificationListener);
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
