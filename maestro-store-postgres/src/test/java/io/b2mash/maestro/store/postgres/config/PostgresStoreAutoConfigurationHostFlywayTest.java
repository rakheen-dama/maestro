package io.b2mash.maestro.store.postgres.config;

import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.store.postgres.schema.MaestroSchemaInitializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins that a host application running its <em>own</em> Flyway (Spring Boot's
 * {@link FlywayAutoConfiguration}, default location {@code classpath:db/migration},
 * default history {@code flyway_schema_history}) starts cleanly beside Maestro
 * on a brand-new database.
 *
 * <p>The hazard is ordering: Flyway refuses to migrate a schema that already
 * holds tables but has no history table of its own. If Maestro's initializer
 * ran first, the host's Flyway would find the {@code maestro_*} tables and fail
 * the host's startup. {@code @DependsOnDatabaseInitialization} on the
 * initializer bean orders it after the host's database initializers.
 */
@DisplayName("PostgresStoreAutoConfiguration beside the host's own Flyway")
class PostgresStoreAutoConfigurationHostFlywayTest {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("maestro_host_flyway_test")
                    .withUsername("test")
                    .withPassword("test");

    static {
        postgres.start();
    }

    @Test
    @DisplayName("host Flyway and Maestro's initializer both apply on a fresh database, each into its own history")
    void hostFlywayAndMaestroBothApplyOnAFreshDatabase() throws SQLException {
        var ds = freshDatabase();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        FlywayAutoConfiguration.class,
                        PostgresStoreAutoConfiguration.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues(
                        "maestro.service-name=test-service",
                        "spring.datasource.url=" + ds.getUrl(),
                        "spring.datasource.username=" + postgres.getUsername(),
                        "spring.datasource.password=" + postgres.getPassword())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(WorkflowStore.class);
                });

        assertThat(tables(ds)).contains("host_customer", "maestro_workflow_instance");
        assertThat(versions(ds, "flyway_schema_history")).containsExactly("1");
        assertThat(versions(ds, MaestroSchemaInitializer.HISTORY_TABLE))
                .containsExactly("1", "2", "3", "4", "5");
    }

    // ── helpers ─────────────────────────────────────────────────────

    private static PGSimpleDataSource freshDatabase() throws SQLException {
        var name = "host_" + UUID.randomUUID().toString().replace("-", "");
        try (var conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE " + name);
        }
        var ds = new PGSimpleDataSource();
        ds.setServerNames(new String[] {postgres.getHost()});
        ds.setPortNumbers(new int[] {postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)});
        ds.setDatabaseName(name);
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    private static List<String> tables(PGSimpleDataSource ds) throws SQLException {
        var names = new ArrayList<String>();
        try (var conn = ds.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname = 'public'")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    /** Applied SQL migration versions, in order; Flyway's baseline marker row is excluded. */
    private static List<String> versions(PGSimpleDataSource ds, String historyTable) throws SQLException {
        var versions = new ArrayList<String>();
        try (var conn = ds.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT version FROM " + historyTable
                     + " WHERE type = 'SQL' AND success ORDER BY installed_rank")) {
            while (rs.next()) {
                versions.add(rs.getString(1));
            }
        }
        return versions;
    }
}
