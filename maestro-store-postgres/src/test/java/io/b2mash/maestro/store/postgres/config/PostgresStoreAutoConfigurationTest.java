package io.b2mash.maestro.store.postgres.config;

import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.spring.client.MaestroClient;
import io.b2mash.maestro.spring.config.MaestroAutoConfiguration;
import io.b2mash.maestro.store.jdbc.JdbcStoreConfiguration;
import io.b2mash.maestro.store.postgres.PostgresWorkflowStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/**
 * Verifies that maestro-store-postgres ships a Spring Boot auto-configuration
 * that contributes a {@link WorkflowStore} bean when a {@link DataSource} is
 * available — and that it evaluates <em>before</em>
 * {@link MaestroAutoConfiguration}, whose
 * {@code @ConditionalOnBean(WorkflowStore.class)} otherwise backs the whole
 * engine off.
 *
 * <p>The auto-configuration classes under test are discovered from this
 * module's {@code META-INF/spring/...AutoConfiguration.imports} file, exactly
 * as Spring Boot would discover them at application startup.
 */
class PostgresStoreAutoConfigurationTest {

    private static final String MODULE_PACKAGE_PREFIX = "io.b2mash.maestro.store.postgres.";

    @Test
    void moduleRegistersAutoConfigurationInImportsFile() {
        assertThat(moduleAutoConfigurations())
                .as("maestro-store-postgres must register an auto-configuration in "
                        + "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
                .isNotEmpty();
    }

    @Test
    void createsPostgresWorkflowStoreWhenDataSourcePresent() {
        storeRunner()
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(WorkflowStore.class);
                    assertThat(context.getBean(WorkflowStore.class))
                            .isInstanceOf(PostgresWorkflowStore.class);
                });
    }

    @Test
    void userDefinedWorkflowStoreTakesPrecedence() {
        var custom = mock(WorkflowStore.class);
        storeRunner()
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean("customWorkflowStore", WorkflowStore.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasSingleBean(WorkflowStore.class);
                    assertThat(context.getBean(WorkflowStore.class)).isSameAs(custom);
                    assertThat(context).doesNotHaveBean(PostgresWorkflowStore.class);
                });
    }

    @Test
    void backsOffWithoutDataSource() {
        storeRunner()
                .run(context -> assertThat(context).doesNotHaveBean(WorkflowStore.class));
    }

    @Test
    void backsOffWhenStoreTypeIsNotPostgres() {
        storeRunner()
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withPropertyValues("maestro.store.type=custom")
                .run(context -> assertThat(context).doesNotHaveBean(WorkflowStore.class));
    }

    @Test
    void honorsTablePrefixProperty() {
        storeRunner()
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withPropertyValues("maestro.store.table-prefix=custom_")
                .run(context -> {
                    var store = context.getBean(PostgresWorkflowStore.class);
                    var config = (JdbcStoreConfiguration) ReflectionTestUtils.getField(store, "config");
                    assertThat(config).isNotNull();
                    assertThat(config.tablePrefix()).isEqualTo("custom_");
                });
    }

    /**
     * Reproduces the original bug end-to-end: without the store
     * auto-configuration (or with it ordered after
     * {@link MaestroAutoConfiguration}), the engine's
     * {@code @ConditionalOnBean(WorkflowStore.class)} backs off and no
     * {@link MaestroClient} exists.
     */
    @Test
    void engineActivatesBecauseStoreAutoConfiguresBeforeMaestroAutoConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(withEngine(moduleAutoConfigurations())))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withPropertyValues("maestro.service-name=test-service")
                .run(context -> {
                    assertThat(context).hasSingleBean(WorkflowStore.class);
                    assertThat(context).hasSingleBean(MaestroClient.class);
                });
    }

    @Test
    void afterNameCoversAllBootDataSourceAutoConfigs() {
        var afterNames = PostgresStoreAutoConfiguration.class
                .getAnnotation(AutoConfiguration.class).afterName();
        assertThat(afterNames).contains(
                "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.JndiDataSourceAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.XADataSourceAutoConfiguration");
        // Guard against typos: every named class must exist on the test classpath.
        for (var name : afterNames) {
            assertThatCode(() -> Class.forName(name, false, getClass().getClassLoader()))
                    .as("afterName entry %s must be a real class", name).doesNotThrowAnyException();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────

    private ApplicationContextRunner storeRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(moduleAutoConfigurations()))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("maestro.service-name=test-service");
    }

    private static Class<?>[] moduleAutoConfigurations() {
        var classes = new ArrayList<Class<?>>();
        for (var name : ImportCandidates.load(
                AutoConfiguration.class,
                PostgresStoreAutoConfigurationTest.class.getClassLoader())) {
            if (name.startsWith(MODULE_PACKAGE_PREFIX)) {
                try {
                    classes.add(Class.forName(name));
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException(
                            "Auto-configuration declared in imports file but not on classpath: " + name, e);
                }
            }
        }
        return classes.toArray(Class<?>[]::new);
    }

    private static Class<?>[] withEngine(Class<?>[] moduleClasses) {
        List<Class<?>> all = new ArrayList<>(List.of(moduleClasses));
        all.add(MaestroAutoConfiguration.class);
        return all.toArray(Class<?>[]::new);
    }
}
