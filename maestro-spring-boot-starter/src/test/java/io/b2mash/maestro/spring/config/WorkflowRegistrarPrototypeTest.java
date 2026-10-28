package io.b2mash.maestro.spring.config;

import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.spring.config.invalidfixture.missingactivity.MissingActivityWorkflow;
import io.b2mash.maestro.spring.config.invalidfixture.threadscope.ThreadScopedWorkflow;
import io.b2mash.maestro.spring.config.scanfixture.ApprovalActivities;
import io.b2mash.maestro.spring.config.scanfixture.ScannedApprovalWorkflow;
import io.b2mash.maestro.spring.config.scanfixture.singleton.SingletonComponentWorkflow;
import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.CustomScopeConfigurer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.SimpleThreadScope;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Proxy;
import java.time.format.DateTimeParseException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that {@code @DurableWorkflow} beans are prototype-scoped and that
 * {@link WorkflowRegistrar} hands the engine a factory building a new, fully
 * wired workflow object per run — the Spring half of F1 (one shared workflow
 * object leaked instance fields between concurrent runs).
 */
@DisplayName("WorkflowRegistrar — prototype workflow beans")
class WorkflowRegistrarPrototypeTest {

    private static final String FIXTURE_PACKAGE =
            "io.b2mash.maestro.spring.config.scanfixture";

    /** Mis-wired workflows, one per sub-package so each test scans exactly one. */
    private static final String INVALID_FIXTURES =
            "io.b2mash.maestro.spring.config.invalidfixture";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MaestroAutoConfiguration.class))
            .withBean(WorkflowStore.class, InMemoryWorkflowStore::new)
            .withBean(DistributedLock.class, InMemoryDistributedLock::new)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(ApprovalActivities.class, StubApprovalActivities::new)
            .withPropertyValues(
                    "maestro.service-name=prototype-test",
                    "maestro.workflow-packages=" + FIXTURE_PACKAGE);

    @Test
    @DisplayName("a scanned @DurableWorkflow bean definition is prototype-scoped")
    void scannedWorkflowDefinitionIsPrototype() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeanFactory().getBeanDefinition("scannedApprovalWorkflow").isPrototype())
                    .isTrue();
        });
    }

    @Test
    @DisplayName("the registration's factory returns a new, fully wired object on every call")
    void factoryReturnsANewFullyWiredObjectEveryTime() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var reg = ctx.getBean(WorkflowRegistrar.class).getRegistration(ScannedApprovalWorkflow.class);

            var first = (ScannedApprovalWorkflow) reg.workflowFactory().get();
            var second = (ScannedApprovalWorkflow) reg.workflowFactory().get();

            assertThat(first).isNotSameAs(second);
            assertThat(Proxy.isProxyClass(first.activities().getClass()))
                    .as("@ActivityStub must be injected into every prototype instance")
                    .isTrue();
            assertThat(first.activities())
                    .as("the memoizing proxy is cached per field, not rebuilt per run")
                    .isSameAs(second.activities());
        });
    }

    @Test
    @DisplayName("workflow discovery at startup does not instantiate workflow objects")
    void discoveryDoesNotInstantiateWorkflows() {
        var before = ScannedApprovalWorkflow.CONSTRUCTED.get();
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(WorkflowRegistrar.class).getRegistrations()).containsKey("scanned-approval");
            assertThat(ScannedApprovalWorkflow.CONSTRUCTED.get())
                    .as("only a run may build a workflow object; discovery reads bean definitions")
                    .isEqualTo(before);
        });
    }

    @Test
    @DisplayName("a singleton-scoped @DurableWorkflow bean fails context startup")
    void singletonWorkflowBeanFailsStartup() {
        runner.withUserConfiguration(SingletonComponentScanConfiguration.class)
                .withPropertyValues("maestro.workflow-packages=" + FIXTURE_PACKAGE + ".singleton")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .isInstanceOf(BeanInitializationException.class)
                            .hasMessageContaining("must be prototype-scoped")
                            .hasMessageContaining(SingletonComponentWorkflow.class.getName());
                });
    }

    @Test
    @DisplayName("a workflow bean in a custom non-prototype scope fails context startup")
    void customScopedWorkflowBeanFailsStartup() {
        runner.withBean(CustomScopeConfigurer.class, () -> {
                    var configurer = new CustomScopeConfigurer();
                    configurer.addScope("thread", new SimpleThreadScope());
                    return configurer;
                })
                .withUserConfiguration(ThreadScopedComponentScanConfiguration.class)
                .withPropertyValues("maestro.workflow-packages=" + INVALID_FIXTURES + ".threadscope")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .isInstanceOf(BeanInitializationException.class)
                            .hasMessageContaining("must be prototype-scoped")
                            .hasMessageContaining(ThreadScopedWorkflow.class.getName());
                });
    }

    /*
     * Ruling R46: with prototype workflow beans nothing builds a workflow object at
     * startup, so the @ActivityStub checks that used to fail context refresh would
     * only fail every run. Discovery must run them against the class instead.
     */

    @Test
    @DisplayName("an @ActivityStub with no implementing activity bean fails context startup")
    void missingActivityBeanFailsStartup() {
        var before = ScannedApprovalWorkflow.CONSTRUCTED.get();
        runner.withPropertyValues("maestro.workflow-packages=" + INVALID_FIXTURES + ".missingactivity")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .isInstanceOf(NoSuchBeanDefinitionException.class)
                            .hasMessageContaining(MissingActivityWorkflow.UnimplementedActivities.class.getName());
                });
        assertThat(ScannedApprovalWorkflow.CONSTRUCTED.get()).isEqualTo(before);
    }

    @Test
    @DisplayName("an unparsable @ActivityStub startToCloseTimeout fails context startup")
    void unparsableTimeoutFailsStartup() {
        runner.withPropertyValues("maestro.workflow-packages=" + INVALID_FIXTURES + ".badtimeout")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .isInstanceOf(BeanInitializationException.class)
                            .hasMessageContaining("'activities'")
                            .hasMessageContaining("'thirty seconds'")
                            .hasRootCauseInstanceOf(DateTimeParseException.class);
                });
    }

    // ── Fixtures ────────────────────────────────────────────────────

    /** Simulates the user's component scan registering a custom-scoped workflow bean. */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = ThreadScopedWorkflow.class)
    static class ThreadScopedComponentScanConfiguration {
    }

    /**
     * The user's own {@code @ComponentScan} — what a {@code @SpringBootApplication}
     * does — registering {@code @Component} workflow classes as singletons before the
     * starter's scan runs (which then skips them as already registered).
     */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = SingletonComponentWorkflow.class)
    static class SingletonComponentScanConfiguration {
    }

    private static class StubApprovalActivities implements ApprovalActivities {
        @Override
        public String approve(String documentId) {
            return "approved:" + documentId;
        }
    }
}
