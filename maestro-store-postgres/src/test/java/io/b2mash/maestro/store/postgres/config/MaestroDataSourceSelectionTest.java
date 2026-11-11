package io.b2mash.maestro.store.postgres.config;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.spring.config.MaestroAutoConfiguration;
import io.b2mash.maestro.store.postgres.MaestroDataSource;
import io.b2mash.maestro.store.postgres.PostgresWorkflowStore;
import io.b2mash.maestro.store.postgres.lock.PostgresDistributedLock;
import io.b2mash.maestro.store.postgres.lock.PostgresLockCleaner;
import io.b2mash.maestro.store.postgres.notify.PostgresSignalNotifier;
import io.b2mash.maestro.store.postgres.notify.PostgresWakeListener;
import io.b2mash.maestro.store.postgres.schema.MaestroSchemaInitializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins which {@link DataSource} every Maestro bean that talks to Postgres is
 * given: the host's single (or {@code @Primary}) one by default, and a bean
 * qualified with {@link MaestroDataSource} whenever one exists — so a host can
 * give the engine its own connection pool without the engine starving the
 * host's request threads.
 *
 * <p>Built from real auto-configurations, Boot's
 * {@link DataSourceAutoConfiguration} included, so the class-level
 * {@code @ConditionalOnBean(DataSource.class)} guards are evaluated in the
 * order an application would evaluate them; the host's own beans come in as
 * user configuration. The two databases are told apart by
 * {@code currentSchema}: the host's pool points at {@value #HOST_SCHEMA},
 * Maestro's at {@value #MAESTRO_SCHEMA}.
 */
@DisplayName("@MaestroDataSource selection")
class MaestroDataSourceSelectionTest {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("maestro_ds_selection_test")
                    .withUsername("test")
                    .withPassword("test");

    static {
        postgres.start();
    }

    private static final String SERVICE = "ds-selection-test";

    private static final String HOST_SCHEMA = "host_app";

    private static final String MAESTRO_SCHEMA = "maestro_engine";

    /** Read by the user configurations below to build their {@code DataSource}s. */
    private static final String MAESTRO_URL_PROPERTY = "test.maestro-datasource.url";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    PostgresStoreAutoConfiguration.class,
                    MaestroAutoConfiguration.class,
                    PostgresLockAutoConfiguration.class,
                    PostgresSignalWakeAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withPropertyValues("maestro.service-name=" + SERVICE);

    /** The same, for a host that has no DataSource of its own: Boot's DataSource auto-configuration is absent. */
    private final ApplicationContextRunner runnerWithoutBootDataSource = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PostgresStoreAutoConfiguration.class,
                    MaestroAutoConfiguration.class,
                    PostgresLockAutoConfiguration.class,
                    PostgresSignalWakeAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withPropertyValues("maestro.service-name=" + SERVICE);

    @Test
    @DisplayName("a single host DataSource is the one every Maestro bean uses")
    void singleHostDataSourceIsUsed() throws SQLException {
        var database = freshDatabase();
        runner.withPropertyValues(hostDataSourceProperties(database)).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var host = ctx.getBean(DataSource.class);
            assertThat(maestroDataSources(ctx))
                    .hasSize(6)
                    .allSatisfy((bean, dataSource) -> assertThat(dataSource).as(bean).isSameAs(host));
        });
        assertThat(maestroTables(database, HOST_SCHEMA)).contains("maestro_workflow_instance");
    }

    @Test
    @DisplayName("a @MaestroDataSource @Bean(defaultCandidate = false) wins for every Maestro bean; the host keeps its own")
    void qualifiedDataSourceWinsForEveryMaestroBean() throws SQLException {
        var database = freshDatabase();
        runner.withUserConfiguration(QualifiedNonDefaultCandidateDataSource.class)
                .withPropertyValues(hostDataSourceProperties(database))
                .withPropertyValues(MAESTRO_URL_PROPERTY + "=" + url(database, MAESTRO_SCHEMA))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    var qualified = ctx.getBean("maestroDataSource", DataSource.class);
                    assertThat(maestroDataSources(ctx))
                            .hasSize(6)
                            .allSatisfy((bean, dataSource) -> assertThat(dataSource).as(bean).isSameAs(qualified));
                    // The qualified bean is not a default candidate: Boot's
                    // DataSourceAutoConfiguration did not back off, and the host's
                    // own unqualified injection points still get the host's pool.
                    assertThat(ctx.getBean(DataSource.class)).isNotSameAs(qualified);
                    assertThat(ctx.getBean(DataSource.class)).isSameAs(ctx.getBean("dataSource"));
                });
        assertThat(maestroTables(database, MAESTRO_SCHEMA)).contains("maestro_workflow_instance");
        assertThat(maestroTables(database, HOST_SCHEMA)).isEmpty();
    }

    @Test
    @DisplayName("two unqualified DataSources fail startup with guidance naming @MaestroDataSource")
    void twoUnqualifiedDataSourcesFailWithGuidance() throws SQLException {
        var database = freshDatabase();
        runner.withUserConfiguration(TwoUnqualifiedDataSources.class)
                .withPropertyValues(MAESTRO_URL_PROPERTY + "=" + url(database, MAESTRO_SCHEMA))
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("Maestro found 2 DataSource beans")
                            .hasMessageContaining("@MaestroDataSource");
                });
    }

    /**
     * A {@code @MaestroDataSource} bean <em>without</em>
     * {@code defaultCandidate = false} is an ordinary {@code DataSource}: Boot's
     * auto-configuration backs off and the host's own JDBC consumers get it
     * too. The documentation warns about exactly this; the test pins it.
     */
    @Test
    @DisplayName("a @MaestroDataSource @Bean that is a default candidate makes Boot back off; Maestro uses it")
    void qualifiedBeanWithoutDefaultCandidateFalseIsDocumentedBehaviour() throws SQLException {
        var database = freshDatabase();
        runner.withUserConfiguration(QualifiedDefaultCandidateDataSource.class)
                .withPropertyValues(MAESTRO_URL_PROPERTY + "=" + url(database, MAESTRO_SCHEMA))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    var qualified = ctx.getBean("maestroDataSource", DataSource.class);
                    assertThat(ctx.getBeansOfType(DataSource.class)).containsOnlyKeys("maestroDataSource");
                    assertThat(maestroDataSources(ctx))
                            .hasSize(6)
                            .allSatisfy((bean, dataSource) -> assertThat(dataSource).as(bean).isSameAs(qualified));
                });
        assertThat(maestroTables(database, MAESTRO_SCHEMA)).contains("maestro_workflow_instance");
    }

    /**
     * A host with no DataSource of its own that gives Maestro one declared
     * {@code defaultCandidate = false}: Boot's own bean conditions ignore such
     * a bean, so the engine's auto-configurations must look for the qualifier
     * themselves — or the engine silently does not exist.
     */
    @Test
    @DisplayName("only a @MaestroDataSource @Bean(defaultCandidate = false) and no Boot DataSource: the engine starts on it")
    void onlyNonDefaultCandidateQualifiedDataSourceStartsTheEngine() throws SQLException {
        var database = freshDatabase();
        runnerWithoutBootDataSource.withUserConfiguration(QualifiedNonDefaultCandidateDataSource.class)
                .withPropertyValues(MAESTRO_URL_PROPERTY + "=" + url(database, MAESTRO_SCHEMA))
                .run(ctx -> assertEngineRunsOn(ctx, "maestroDataSource"));
        assertThat(maestroTables(database, MAESTRO_SCHEMA)).contains("maestro_workflow_instance");
    }

    @Test
    @DisplayName("a @MaestroDataSource on the bean's class (not the @Bean method) is seen too")
    void qualifierOnTheBeanClassIsSeen() throws SQLException {
        var database = freshDatabase();
        runnerWithoutBootDataSource.withUserConfiguration(ClassQualifiedNonDefaultCandidateDataSource.class)
                .withPropertyValues(MAESTRO_URL_PROPERTY + "=" + url(database, MAESTRO_SCHEMA))
                .run(ctx -> assertEngineRunsOn(ctx, "classQualifiedDataSource"));
        assertThat(maestroTables(database, MAESTRO_SCHEMA)).contains("maestro_workflow_instance");
    }

    /**
     * Boot's DataSource auto-configuration does not see a non-default-candidate
     * bean either, so with no {@code spring.datasource.url} it still tries to
     * build the host's pool and fails — Boot's behaviour, not Maestro's. The
     * documentation tells such a host to exclude Boot's DataSource
     * auto-configuration or declare a plain {@code @MaestroDataSource @Bean}.
     */
    @Test
    @DisplayName("only a @MaestroDataSource @Bean(defaultCandidate = false) beside Boot's DataSource auto-config, no url: Boot fails")
    void onlyNonDefaultCandidateQualifiedDataSourceBesideBootWithoutUrlFailsInBoot() throws SQLException {
        var database = freshDatabase();
        runner.withUserConfiguration(QualifiedNonDefaultCandidateDataSource.class)
                .withPropertyValues(MAESTRO_URL_PROPERTY + "=" + url(database, MAESTRO_SCHEMA))
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("Failed to determine a suitable driver class");
                });
    }

    @Test
    @DisplayName("no DataSource at all: every Postgres auto-configuration backs off, and the context starts")
    void noDataSourceBacksOff() {
        runnerWithoutBootDataSource.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(DataSource.class);
            assertThat(ctx).doesNotHaveBean(MaestroSchemaInitializer.class);
            assertThat(ctx).doesNotHaveBean(WorkflowStore.class);
            assertThat(ctx).doesNotHaveBean(DistributedLock.class);
            assertThat(ctx).doesNotHaveBean(PostgresLockCleaner.class);
            assertThat(ctx).doesNotHaveBean(PostgresWakeListener.class);
            assertThat(ctx).doesNotHaveBean(WorkflowExecutor.class);
        });
    }

    @Test
    @DisplayName("two @MaestroDataSource beans fail startup: exactly one is allowed")
    void twoQualifiedDataSourcesFail() throws SQLException {
        var database = freshDatabase();
        runner.withUserConfiguration(TwoQualifiedDataSources.class)
                .withPropertyValues(hostDataSourceProperties(database))
                .withPropertyValues(MAESTRO_URL_PROPERTY + "=" + url(database, MAESTRO_SCHEMA))
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("Maestro found 2 @MaestroDataSource beans; exactly one is allowed");
                });
    }

    private static void assertEngineRunsOn(AssertableApplicationContext ctx, String dataSourceBean) {
        assertThat(ctx).hasNotFailed();
        assertThat(ctx).hasSingleBean(WorkflowStore.class);
        assertThat(ctx).hasSingleBean(DistributedLock.class);
        assertThat(ctx).hasSingleBean(PostgresWakeListener.class);
        assertThat(ctx).hasSingleBean(MaestroSchemaInitializer.class);
        assertThat(ctx).hasSingleBean(WorkflowExecutor.class);
        var qualified = ctx.getBean(dataSourceBean, DataSource.class);
        assertThat(maestroDataSources(ctx))
                .hasSize(6)
                .allSatisfy((bean, dataSource) -> assertThat(dataSource).as(bean).isSameAs(qualified));
    }

    // ── host configurations ─────────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    static class QualifiedNonDefaultCandidateDataSource {

        @Bean(defaultCandidate = false)
        @MaestroDataSource
        DataSource maestroDataSource(Environment env) {
            return dataSource(env.getRequiredProperty(MAESTRO_URL_PROPERTY));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class QualifiedDefaultCandidateDataSource {

        @Bean
        @MaestroDataSource
        DataSource maestroDataSource(Environment env) {
            return dataSource(env.getRequiredProperty(MAESTRO_URL_PROPERTY));
        }
    }

    /** The qualifier sits on the bean's class; the {@code @Bean} method carries none. */
    @MaestroDataSource
    static class ClassQualifiedDataSource extends PGSimpleDataSource {
    }

    @Configuration(proxyBeanMethods = false)
    static class ClassQualifiedNonDefaultCandidateDataSource {

        @Bean(defaultCandidate = false)
        ClassQualifiedDataSource classQualifiedDataSource(Environment env) {
            var ds = new ClassQualifiedDataSource();
            ds.setUrl(env.getRequiredProperty(MAESTRO_URL_PROPERTY));
            ds.setUser(postgres.getUsername());
            ds.setPassword(postgres.getPassword());
            return ds;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoQualifiedDataSources {

        @Bean(defaultCandidate = false)
        @MaestroDataSource
        DataSource firstMaestroDataSource(Environment env) {
            return dataSource(env.getRequiredProperty(MAESTRO_URL_PROPERTY));
        }

        @Bean(defaultCandidate = false)
        @MaestroDataSource
        DataSource secondMaestroDataSource(Environment env) {
            return dataSource(env.getRequiredProperty(MAESTRO_URL_PROPERTY));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoUnqualifiedDataSources {

        @Bean
        DataSource firstDataSource(Environment env) {
            return dataSource(env.getRequiredProperty(MAESTRO_URL_PROPERTY));
        }

        @Bean
        DataSource secondDataSource(Environment env) {
            return dataSource(env.getRequiredProperty(MAESTRO_URL_PROPERTY));
        }
    }

    // ── helpers ─────────────────────────────────────────────────────

    /** Each Maestro bean that talks to Postgres, mapped to the DataSource it holds. */
    private static Map<String, Object> maestroDataSources(AssertableApplicationContext ctx) {
        var holders = new LinkedHashMap<String, Object>();
        holders.put("schemaInitializer", ctx.getBean(MaestroSchemaInitializer.class));
        holders.put("store", ctx.getBean(PostgresWorkflowStore.class));
        holders.put("lock", ctx.getBean(PostgresDistributedLock.class));
        holders.put("lockCleaner", ctx.getBean(PostgresLockCleaner.class));
        holders.put("wakeListener", ctx.getBean(PostgresWakeListener.class));
        holders.put("signalNotifier", ctx.getBean(PostgresSignalNotifier.class));
        var dataSources = new LinkedHashMap<String, Object>();
        holders.forEach((name, bean) -> dataSources.put(name, ReflectionTestUtils.getField(bean, "dataSource")));
        return dataSources;
    }

    private static String[] hostDataSourceProperties(String database) {
        return new String[] {
                "spring.datasource.url=" + url(database, HOST_SCHEMA),
                "spring.datasource.username=" + postgres.getUsername(),
                "spring.datasource.password=" + postgres.getPassword()
        };
    }

    private static DataSource dataSource(String url) {
        var ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    private static String url(String database, String schema) {
        return "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                + "/" + database + "?currentSchema=" + schema;
    }

    /** A new database holding the two empty schemas the pools point at. */
    private static String freshDatabase() throws SQLException {
        var name = "ds_" + UUID.randomUUID().toString().replace("-", "");
        try (var conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE " + name);
        }
        try (var conn = DriverManager.getConnection(
                url(name, "public"), postgres.getUsername(), postgres.getPassword());
             var stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA " + HOST_SCHEMA);
            stmt.execute("CREATE SCHEMA " + MAESTRO_SCHEMA);
        }
        return name;
    }

    private static List<String> maestroTables(String database, String schema) throws SQLException {
        var names = new ArrayList<String>();
        try (var conn = DriverManager.getConnection(
                url(database, "public"), postgres.getUsername(), postgres.getPassword());
             var stmt = conn.prepareStatement(
                     "SELECT tablename FROM pg_tables WHERE schemaname = ? AND tablename LIKE 'maestro\\_%'")) {
            stmt.setString(1, schema);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }
}
