package io.b2mash.maestro.spring.config;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.spring.client.MaestroClient;
import io.b2mash.maestro.spring.client.WorkflowOptions;
import io.b2mash.maestro.test.InMemoryWorkflowMessaging;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Pins that {@code maestro.admin.events.enabled} actually reaches the engine
 * through the real auto-configuration chain (Issue 6).
 *
 * <p>Before this fix, {@code maestro.admin.events.enabled} bound into
 * {@link MaestroProperties} but nothing read it — {@code false} had no effect
 * and lifecycle events kept publishing regardless. This test proves the flag
 * is threaded from {@link MaestroProperties} into the {@link
 * io.b2mash.maestro.core.engine.WorkflowExecutor} bean and observably stops
 * publishing, using an in-memory {@link WorkflowMessaging} spy — the same
 * pattern {@code io.b2mash.maestro.spring.client.MaestroClientTest} uses to
 * drive the client through the real wiring.
 */
@DisplayName("maestro.admin.events.enabled reaches the engine")
class MaestroAutoConfigurationLifecycleEventsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MaestroAutoConfiguration.class))
            .withUserConfiguration(TestWorkflowConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(WorkflowStore.class, InMemoryWorkflowStore::new)
            .withBean(WorkflowMessaging.class, InMemoryWorkflowMessaging::new)
            .withPropertyValues("maestro.service-name=lifecycle-events-test");

    @Test
    @DisplayName("enabled=false stops lifecycle publishing entirely")
    void adminEventsDisabled_stopsLifecyclePublishing() {
        runner.withPropertyValues("maestro.admin.events.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var client = context.getBean(MaestroClient.class);
                    var messaging = (InMemoryWorkflowMessaging) context.getBean(WorkflowMessaging.class);

                    var result = client.newWorkflow(EchoWorkflow.class, options("lifecycle-off-1"))
                            .startAndWait("hi", Duration.ofSeconds(10), String.class);

                    assertThat(result).isEqualTo("HI");
                    assertThat(messaging.getLifecycleEvents())
                            .as("no lifecycle event may be published when maestro.admin.events.enabled=false")
                            .isEmpty();
                });
    }

    @Test
    @DisplayName("lifecycle publishing happens by default (enabled defaults to true)")
    void adminEventsEnabledByDefault_publishesLifecycleEvents() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var client = context.getBean(MaestroClient.class);
            var messaging = (InMemoryWorkflowMessaging) context.getBean(WorkflowMessaging.class);

            client.newWorkflow(EchoWorkflow.class, options("lifecycle-on-1"))
                    .startAndWait("hi", Duration.ofSeconds(10), String.class);

            // Publishing is off-thread now (Issue 3), so this is the one place in
            // this test class that needs to wait rather than assert immediately.
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(messaging.getLifecycleEvents()).hasSize(2));
        });
    }

    private static WorkflowOptions options(String workflowId) {
        return WorkflowOptions.builder().workflowId(workflowId).build();
    }

    /** Registers the workflow bean the client resolves through WorkflowRegistrar. */
    @Configuration(proxyBeanMethods = false)
    static class TestWorkflowConfiguration {

        @Bean
        EchoWorkflow echoWorkflow() {
            return new EchoWorkflow();
        }
    }

    /** Completes immediately, echoing its input in upper case. */
    @DurableWorkflow(name = "LifecycleEventsEchoWorkflow")
    public static class EchoWorkflow {

        /**
         * @param input the value to echo
         * @return the input in upper case
         */
        @WorkflowMethod
        public String run(String input) {
            return input == null ? "EMPTY" : input.toUpperCase();
        }
    }
}
