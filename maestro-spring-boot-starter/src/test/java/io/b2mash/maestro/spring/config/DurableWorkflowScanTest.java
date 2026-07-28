package io.b2mash.maestro.spring.config;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.spring.config.scanfixture.ApprovalActivities;
import io.b2mash.maestro.spring.config.scanfixture.ComponentAnnotatedWorkflow;
import io.b2mash.maestro.spring.config.scanfixture.ScannedApprovalWorkflow;
import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests proving that classes annotated only with
 * {@link DurableWorkflow} (no {@code @Component}) are discovered by
 * classpath scanning and registered as Spring beans, so that
 * {@link WorkflowRegistrar} — and therefore
 * {@code MaestroClient.newWorkflow(...)} — can find them.
 */
class DurableWorkflowScanTest {

    private static final String FIXTURE_PACKAGE =
            "io.b2mash.maestro.spring.config.scanfixture";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MaestroAutoConfiguration.class))
            .withBean(WorkflowStore.class, InMemoryWorkflowStore::new)
            .withBean(DistributedLock.class, InMemoryDistributedLock::new)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(ApprovalActivities.class, StubApprovalActivities::new)
            .withPropertyValues("maestro.service-name=scan-test");

    @Test
    @DisplayName("bare @DurableWorkflow class is scanned, registered as a bean, and registered with WorkflowRegistrar")
    void scansAndRegistersBareDurableWorkflow() {
        runner.withPropertyValues("maestro.workflow-packages=" + FIXTURE_PACKAGE)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ScannedApprovalWorkflow.class);
                    assertThat(context.getBeansWithAnnotation(DurableWorkflow.class).values())
                            .anyMatch(ScannedApprovalWorkflow.class::isInstance);

                    var registrar = context.getBean(WorkflowRegistrar.class);
                    assertThat(registrar.getRegistrations()).containsKey("scanned-approval");
                    assertThat(registrar.getRegistration(ScannedApprovalWorkflow.class))
                            .isNotNull();
                });
    }

    @Test
    @DisplayName("scanned workflow bean still receives @ActivityStub proxy injection")
    void scannedWorkflowGetsActivityProxyInjected() {
        runner.withPropertyValues("maestro.workflow-packages=" + FIXTURE_PACKAGE)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var workflow = context.getBean(ScannedApprovalWorkflow.class);
                    assertThat(workflow.activities())
                            .as("@ActivityStub field must be injected with a memoizing proxy")
                            .isNotNull();
                    assertThat(Proxy.isProxyClass(workflow.activities().getClass()))
                            .as("injected value must be the memoizing JDK proxy, not the raw bean")
                            .isTrue();
                });
    }

    @Test
    @DisplayName("@Component-annotated workflow is not double-registered by the scan")
    void componentAnnotatedWorkflowIsNotDoubleRegistered() {
        runner.withBean(ComponentAnnotatedWorkflow.class)
                .withPropertyValues("maestro.workflow-packages=" + FIXTURE_PACKAGE)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(ComponentAnnotatedWorkflow.class))
                            .as("scan must skip workflow classes already registered as beans")
                            .hasSize(1);

                    var registrar = context.getBean(WorkflowRegistrar.class);
                    assertThat(registrar.getRegistrations()).containsKey("component-registered");
                });
    }

    @Test
    @DisplayName("auto-configuration packages are used when no explicit property is set")
    void usesAutoConfigurationPackagesWhenAvailable() {
        runner.withUserConfiguration(FixturePackageConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ScannedApprovalWorkflow.class);
                    assertThat(context.getBean(WorkflowRegistrar.class).getRegistrations())
                            .containsKey("scanned-approval");
                });
    }

    @Test
    @DisplayName("no packages configured and no auto-configuration packages — graceful no-op")
    void noPackagesConfiguredIsGracefulNoOp() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ScannedApprovalWorkflow.class);
            assertThat(context.getBean(WorkflowRegistrar.class).getRegistrations()).isEmpty();
        });
    }

    // ── Fixtures ────────────────────────────────────────────────────

    /** Simulates a {@code @SpringBootApplication} whose base package contains workflows. */
    @Configuration(proxyBeanMethods = false)
    @AutoConfigurationPackage(basePackages = FIXTURE_PACKAGE)
    static class FixturePackageConfiguration {
    }

    private static class StubApprovalActivities implements ApprovalActivities {
        @Override
        public String approve(String documentId) {
            return "approved:" + documentId;
        }
    }
}
