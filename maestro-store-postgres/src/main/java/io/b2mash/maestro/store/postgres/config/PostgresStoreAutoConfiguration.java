package io.b2mash.maestro.store.postgres.config;

import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.spring.config.MaestroAutoConfiguration;
import io.b2mash.maestro.store.jdbc.JdbcStoreConfiguration;
import io.b2mash.maestro.store.postgres.PostgresWorkflowStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;

/**
 * Auto-configuration for the PostgreSQL-backed {@link WorkflowStore}.
 *
 * <p>Activates when:
 * <ul>
 *   <li>The PostgreSQL JDBC driver is on the classpath</li>
 *   <li>A {@link DataSource} bean exists (e.g., via Spring Boot's
 *       DataSource auto-configuration from {@code spring-boot-starter-jdbc})</li>
 *   <li>{@code maestro.store.type} is {@code "postgres"} (default)</li>
 * </ul>
 *
 * <p>Creates a {@link PostgresWorkflowStore} bean unless the application
 * already defines its own {@link WorkflowStore}
 * ({@link ConditionalOnMissingBean} — user beans always win).
 *
 * <p><b>Ordering:</b> unlike the messaging/lock sibling modules (which run
 * {@code after} {@link MaestroAutoConfiguration} because their SPIs are
 * optional), this auto-configuration must run <em>before</em>
 * {@link MaestroAutoConfiguration}: the engine is guarded with
 * {@code @ConditionalOnBean(WorkflowStore.class)}, so the store bean
 * definition has to be contributed first or the entire engine backs off.
 *
 * <p>The table prefix is resolved from {@code maestro.store.table-prefix}
 * (default {@code "maestro_"}), matching the Flyway migrations shipped in
 * this module.
 *
 * @see PostgresWorkflowStore
 * @see MaestroAutoConfiguration
 */
@AutoConfiguration(
        before = MaestroAutoConfiguration.class,
        afterName = {
                "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.JndiDataSourceAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.XADataSourceAutoConfiguration"
        })
@ConditionalOnClass(name = "org.postgresql.Driver")
@ConditionalOnBean(DataSource.class)
// Audit F8: maestro.enabled=false is documented as the master kill-switch
// (see MaestroAutoConfiguration), but this class previously had no direct
// gate on it — it kept registering a real WorkflowStore whenever a
// DataSource was present. See
// PostgresStoreAutoConfigurationMaestroDisabledTest.
@ConditionalOnProperty(prefix = "maestro", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "maestro.store", name = "type", havingValue = "postgres", matchIfMissing = true)
public class PostgresStoreAutoConfiguration {

    private static final String TABLE_PREFIX_PROPERTY = "maestro.store.table-prefix";
    private static final String DEFAULT_TABLE_PREFIX = "maestro_";

    @Bean
    @ConditionalOnMissingBean(WorkflowStore.class)
    public PostgresWorkflowStore postgresWorkflowStore(
            DataSource dataSource, ObjectMapper objectMapper, Environment env
    ) {
        // Environment (not MaestroProperties) on purpose: MaestroProperties is
        // registered by MaestroAutoConfiguration, which evaluates after this
        // auto-configuration and may back off entirely.
        var tablePrefix = env.getProperty(TABLE_PREFIX_PROPERTY, DEFAULT_TABLE_PREFIX);
        return new PostgresWorkflowStore(dataSource, new JdbcStoreConfiguration(tablePrefix, objectMapper));
    }
}
