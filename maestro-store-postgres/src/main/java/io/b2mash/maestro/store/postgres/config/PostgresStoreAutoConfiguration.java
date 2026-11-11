package io.b2mash.maestro.store.postgres.config;

import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.spring.config.MaestroAutoConfiguration;
import io.b2mash.maestro.store.jdbc.JdbcStoreConfiguration;
import io.b2mash.maestro.store.postgres.MaestroDataSource;
import io.b2mash.maestro.store.postgres.PostgresWorkflowStore;
import io.b2mash.maestro.store.postgres.schema.MaestroSchemaInitializer;
import io.b2mash.maestro.store.postgres.schema.SchemaMode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
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
 * <p><b>Schema:</b> a {@link MaestroSchemaInitializer} bean applies Maestro's
 * own migrations (recorded in {@code maestro_schema_history}, never the host's
 * {@code flyway_schema_history}) according to {@code maestro.store.schema.mode}
 * ({@code flyway | validate | none}, default {@code flyway}). The store bean
 * takes it as a parameter, so the schema is in place before the store — and
 * therefore the engine — is created. The initializer is ordered after the
 * host's own database initializers (its Flyway, Liquibase or SQL scripts) via
 * {@link DependsOnDatabaseInitialization}: on a brand-new database the host's
 * Flyway must run first, because it refuses to migrate a schema that already
 * holds tables but no {@code flyway_schema_history}. The ordering is kept when
 * Maestro has its own {@code DataSource}: a dedicated pool often points at the
 * host's database and schema, where the same hazard applies.
 *
 * <p><b>DataSource:</b> the store and the schema initializer use the
 * {@link MaestroDataSource @MaestroDataSource}-qualified {@code DataSource}
 * when the application declares one, otherwise its single (or
 * {@code @Primary}) {@code DataSource}. Two or more unqualified candidates
 * with no primary fail startup naming {@code @MaestroDataSource}.
 *
 * <p>Tables are always named {@code maestro_*}, matching the Flyway
 * migrations shipped in this module. The removed
 * {@code maestro.store.table-prefix} property fails startup if set — before
 * any DDL runs; isolate services with one Postgres schema each instead.
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

    private static final String SCHEMA_MODE_PROPERTY = "maestro.store.schema.mode";

    /**
     * Applies (or validates, or skips) Maestro's schema migrations at startup.
     *
     * @param maestroDataSource the {@link MaestroDataSource @MaestroDataSource} bean, if any
     * @param dataSource        the application's data source, used when there is none
     * @param env               the environment {@code maestro.store.schema.mode} is read from
     * @return the initializer, already run
     */
    @Bean
    @ConditionalOnMissingBean(MaestroSchemaInitializer.class)
    @DependsOnDatabaseInitialization
    public MaestroSchemaInitializer maestroSchemaInitializer(
            @MaestroDataSource ObjectProvider<DataSource> maestroDataSource,
            ObjectProvider<DataSource> dataSource, Environment env) {
        // Environment (not MaestroProperties) on purpose: MaestroProperties is
        // registered by MaestroAutoConfiguration, which evaluates after this
        // auto-configuration and may back off entirely.
        rejectRemovedTablePrefix(env);
        // Parsed here rather than by the Environment's ConversionService:
        // whether that converter is case-lenient depends on how the context
        // was built, and "flyway" must work either way.
        var mode = SchemaMode.fromProperty(env.getProperty(SCHEMA_MODE_PROPERTY, "flyway"));
        var initializer = new MaestroSchemaInitializer(
                MaestroDataSources.resolve(maestroDataSource, dataSource), mode,
                PostgresStoreAutoConfiguration.class.getClassLoader());
        initializer.initialize();
        return initializer;
    }

    /**
     * The Postgres {@link WorkflowStore}.
     *
     * @param maestroDataSource the {@link MaestroDataSource @MaestroDataSource} bean, if any
     * @param dataSource   the application's data source, used when there is none
     * @param objectMapper the mapper for payloads
     * @param env          the environment, checked for removed properties
     * @param initializer  the schema initializer — injected only so the schema
     *                     exists before the store is created
     * @return the store
     */
    @Bean
    @ConditionalOnMissingBean(WorkflowStore.class)
    public PostgresWorkflowStore postgresWorkflowStore(
            @MaestroDataSource ObjectProvider<DataSource> maestroDataSource,
            ObjectProvider<DataSource> dataSource, ObjectMapper objectMapper, Environment env,
            MaestroSchemaInitializer initializer
    ) {
        rejectRemovedTablePrefix(env);
        return new PostgresWorkflowStore(
                MaestroDataSources.resolve(maestroDataSource, dataSource), new JdbcStoreConfiguration(objectMapper));
    }

    private static void rejectRemovedTablePrefix(Environment env) {
        if (env.containsProperty(TABLE_PREFIX_PROPERTY)) {
            throw new IllegalStateException(
                    "maestro.store.table-prefix has been removed: Maestro tables are always named maestro_*. "
                            + "To isolate services sharing one database, give each service its own Postgres schema "
                            + "(JDBC URL ...?currentSchema=<schema> or spring.datasource.hikari.schema=<schema>). "
                            + "See docs/configuration.md \u00a7 Store Configuration.");
        }
    }
}
