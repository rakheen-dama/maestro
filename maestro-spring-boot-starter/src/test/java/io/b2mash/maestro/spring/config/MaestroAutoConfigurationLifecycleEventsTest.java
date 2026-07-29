package io.b2mash.maestro.spring.config;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
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
 *
 * <p>Fix round 2 (QA Gate 5): {@code enabled=false} stopped only {@code
 * WorkflowExecutor}'s own {@code WORKFLOW_*} events. Activity proxies are
 * built independently of {@code WorkflowExecutor} — in production, by {@link
 * io.b2mash.maestro.spring.proxy.ActivityStubBeanPostProcessor}, which
 * resolves its own {@link WorkflowMessaging} bean straight from the Spring
 * context — so {@code ACTIVITY_*} events kept publishing regardless, exactly
 * as observed live (247 leaked events across a real E2E run with the flag
 * set). {@code adminEventsDisabled_stopsActivityLifecycleEvents} drives a
 * workflow with a real {@code @ActivityStub} field through the real bean
 * post-processor and proves that gap is closed.
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

    // ── Fix round 2: enabled=false must gate ACTIVITY_* events too ─────────

    @Test
    @DisplayName("enabled=false stops ACTIVITY_* events from the real @ActivityStub wiring too")
    void adminEventsDisabled_stopsActivityLifecycleEvents() {
        runner.withPropertyValues("maestro.admin.events.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var client = context.getBean(MaestroClient.class);
                    var messaging = (InMemoryWorkflowMessaging) context.getBean(WorkflowMessaging.class);

                    var result = client.newWorkflow(ActivityWorkflow.class, options("lifecycle-off-activity-1"))
                            .startAndWait("world", Duration.ofSeconds(10), String.class);

                    assertThat(result).isEqualTo("hello world");
                    assertThat(messaging.getLifecycleEvents())
                            .as("no lifecycle event — including ACTIVITY_* from the real "
                                    + "ActivityStubBeanPostProcessor-built proxy — may be published "
                                    + "when maestro.admin.events.enabled=false")
                            .isEmpty();
                });
    }

    @Test
    @DisplayName("ACTIVITY_* events are published by default through the real @ActivityStub wiring")
    void adminEventsEnabledByDefault_publishesActivityLifecycleEvents() {
        // The positive control: without it, the disabled-case test above could
        // pass vacuously if ActivityWorkflow never actually reached the proxy.
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var client = context.getBean(MaestroClient.class);
            var messaging = (InMemoryWorkflowMessaging) context.getBean(WorkflowMessaging.class);

            client.newWorkflow(ActivityWorkflow.class, options("lifecycle-on-activity-1"))
                    .startAndWait("world", Duration.ofSeconds(10), String.class);

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                var types = messaging.getLifecycleEvents().stream()
                        .map(WorkflowLifecycleEvent::eventType)
                        .toList();
                assertThat(types).contains(LifecycleEventType.ACTIVITY_STARTED, LifecycleEventType.ACTIVITY_COMPLETED);
            });
        });
    }

    private static WorkflowOptions options(String workflowId) {
        return WorkflowOptions.builder().workflowId(workflowId).build();
    }

    /** Registers the workflow beans the client resolves through WorkflowRegistrar. */
    @Configuration(proxyBeanMethods = false)
    static class TestWorkflowConfiguration {

        @Bean
        EchoWorkflow echoWorkflow() {
            return new EchoWorkflow();
        }

        @Bean
        ActivityWorkflow activityWorkflow() {
            return new ActivityWorkflow();
        }

        @Bean
        GreetingActivities greetingActivities() {
            return new GreetingActivitiesImpl();
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

    /** A trivial activity, invoked through the real {@code @ActivityStub} wiring. */
    public interface GreetingActivities {
        String greet(String name);
    }

    /** @see GreetingActivities */
    public static class GreetingActivitiesImpl implements GreetingActivities {
        @Override
        public String greet(String name) {
            return "hello " + name;
        }
    }

    /** Calls a real activity through a proxy injected by ActivityStubBeanPostProcessor. */
    @DurableWorkflow(name = "LifecycleEventsActivityWorkflow")
    public static class ActivityWorkflow {

        @ActivityStub
        private GreetingActivities activities;

        /**
         * @param input the value to greet
         * @return the activity's greeting
         */
        @WorkflowMethod
        public String run(String input) {
            return activities.greet(input);
        }
    }
}
