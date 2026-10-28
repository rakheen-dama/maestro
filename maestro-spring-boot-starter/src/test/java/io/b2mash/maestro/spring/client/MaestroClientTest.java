package io.b2mash.maestro.spring.client;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.QueryMethod;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.exception.WorkflowExecutionException;
import io.b2mash.maestro.core.exception.WorkflowInstantiationException;
import io.b2mash.maestro.core.exception.WorkflowNotFoundException;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.spring.config.MaestroAutoConfiguration;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MaestroClient} — the public entry point applications use to
 * start, signal and query workflows.
 *
 * <p>The client had no dedicated test class: it was only ever exercised
 * indirectly through the sample applications and the manual end-to-end script.
 * These tests drive it through the real auto-configuration chain over the
 * in-memory SPIs, so the wiring from {@code WorkflowStore} through
 * {@code WorkflowRegistrar} to the client is covered too.
 */
@DisplayName("MaestroClient")
class MaestroClientTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MaestroAutoConfiguration.class))
            .withUserConfiguration(TestWorkflowConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(WorkflowStore.class, InMemoryWorkflowStore::new)
            .withPropertyValues("maestro.service-name=client-test");

    @Nested
    @DisplayName("starting workflows")
    class StartTests {

        @Test
        @DisplayName("startAsync creates the instance under the requested workflow ID")
        void startAsyncCreatesInstance() {
            runner.run(context -> {
                var client = context.getBean(MaestroClient.class);
                var store = context.getBean(WorkflowStore.class);

                var instanceId = client
                        .newWorkflow(EchoWorkflow.class, options("echo-1"))
                        .startAsync("hello");

                assertNotNull(instanceId, "startAsync must return the instance id");
                assertTrue(store.getInstance("echo-1").isPresent(),
                        "the instance must be persisted under the requested workflow id");
            });
        }

        @Test
        @DisplayName("startAndWait returns the workflow's deserialized output")
        void startAndWaitReturnsOutput() {
            runner.run(context -> {
                var client = context.getBean(MaestroClient.class);

                var result = client
                        .newWorkflow(EchoWorkflow.class, options("echo-2"))
                        .startAndWait("hello", Duration.ofSeconds(10), String.class);

                assertEquals("HELLO", result);
            });
        }

        @Test
        @DisplayName("startAndWait raises WorkflowExecutionException when the workflow fails")
        void startAndWaitPropagatesFailure() {
            runner.run(context -> {
                var client = context.getBean(MaestroClient.class);

                var stub = client.newWorkflow(FailingWorkflow.class, options("fail-1"));

                assertThrows(WorkflowExecutionException.class,
                        () -> stub.startAndWait("boom", Duration.ofSeconds(10), String.class),
                        "a failed workflow must not be reported as a normal result");
            });
        }

        @Test
        @DisplayName("startAndWait times out rather than blocking forever on a parked workflow")
        void startAndWaitTimesOut() {
            runner.run(context -> {
                var client = context.getBean(MaestroClient.class);

                var stub = client.newWorkflow(ParkingWorkflow.class, options("park-1"));

                assertThrows(TimeoutException.class,
                        () -> stub.startAndWait(null, Duration.ofMillis(500), String.class));
            });
        }
    }

    @Nested
    @DisplayName("interacting with a running workflow")
    class HandleTests {

        @Test
        @DisplayName("signal wakes a parked workflow so it can complete")
        void signalWakesParkedWorkflow() {
            runner.run(context -> {
                var client = context.getBean(MaestroClient.class);
                var store = context.getBean(WorkflowStore.class);
                var executor = context.getBean(WorkflowExecutor.class);

                client.newWorkflow(ParkingWorkflow.class, options("park-2")).startAsync(null);
                awaitParked(executor, 1);

                client.getWorkflow("park-2").signal(ParkingWorkflow.SIGNAL, "go");

                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() ->
                        store.getInstance("park-2")
                                .map(i -> i.status() == WorkflowStatus.COMPLETED)
                                .orElse(false));
            });
        }

        @Test
        @DisplayName("query reads state from a running workflow")
        void queryReadsRunningState() {
            runner.run(context -> {
                var client = context.getBean(MaestroClient.class);
                var executor = context.getBean(WorkflowExecutor.class);

                client.newWorkflow(ParkingWorkflow.class, options("park-3")).startAsync(null);
                awaitParked(executor, 1);

                var state = client.getWorkflow("park-3").query("state", String.class);

                assertEquals("waiting", state);
            });
        }

        @Test
        @DisplayName("querying an unknown workflow fails loudly instead of returning null")
        void queryUnknownWorkflowThrows() {
            runner.run(context -> {
                var client = context.getBean(MaestroClient.class);

                assertThrows(WorkflowNotFoundException.class,
                        () -> client.getWorkflow("no-such-workflow").query("state", String.class));
            });
        }

        @Test
        @DisplayName("the handle reports the workflow id it was created for")
        void handleCarriesWorkflowId() {
            runner.run(context -> {
                var client = context.getBean(MaestroClient.class);

                assertEquals("some-id", client.getWorkflow("some-id").workflowId());
            });
        }
    }

    @Nested
    @DisplayName("per-run workflow state")
    class PerRunState {

        @Test
        @DisplayName("two concurrent instances of one workflow type keep separate query state")
        void twoConcurrentInstancesKeepSeparateQueryState() {
            runner.run(context -> {
                var client = context.getBean(MaestroClient.class);
                var executor = context.getBean(WorkflowExecutor.class);

                client.newWorkflow(StatusProbeWorkflow.class, options("probe-a")).startAsync("A");
                client.newWorkflow(StatusProbeWorkflow.class, options("probe-b")).startAsync("B");
                awaitParked(executor, 2);

                assertEquals("A", client.getWorkflow("probe-a").query("step", String.class),
                        "probe-a must answer from its own workflow object");
                assertEquals("B", client.getWorkflow("probe-b").query("step", String.class),
                        "probe-b must answer from its own workflow object, not probe-a's");
            });
        }

        @Test
        @DisplayName("a workflow bean that cannot be created fails startAsync with WorkflowInstantiationException")
        void brokenPrototypeFailsStartWithInstantiationException() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                var client = context.getBean(MaestroClient.class);

                var thrown = assertThrows(WorkflowInstantiationException.class,
                        () -> client.newWorkflow(BrokenWorkflow.class, options("broken-1")).startAsync(null));

                assertInstanceOf(BeanCreationException.class, thrown.getCause(),
                        "the Spring bean-creation failure must be preserved as the cause");
            });
        }
    }

    private static WorkflowOptions options(String workflowId) {
        return WorkflowOptions.builder().workflowId(workflowId).build();
    }

    private static void awaitParked(WorkflowExecutor executor, int count) {
        await().atMost(Duration.ofSeconds(10)).until(() -> executor.parkedCount() == count);
    }

    /**
     * Registers the workflow beans the client resolves through WorkflowRegistrar.
     * Workflow beans are prototype-scoped: Maestro creates one object per run.
     */
    @Configuration(proxyBeanMethods = false)
    static class TestWorkflowConfiguration {

        @Bean
        @Scope(BeanDefinition.SCOPE_PROTOTYPE)
        EchoWorkflow echoWorkflow() {
            return new EchoWorkflow();
        }

        @Bean
        @Scope(BeanDefinition.SCOPE_PROTOTYPE)
        FailingWorkflow failingWorkflow() {
            return new FailingWorkflow();
        }

        @Bean
        @Scope(BeanDefinition.SCOPE_PROTOTYPE)
        ParkingWorkflow parkingWorkflow() {
            return new ParkingWorkflow();
        }

        @Bean
        @Scope(BeanDefinition.SCOPE_PROTOTYPE)
        StatusProbeWorkflow statusProbeWorkflow() {
            return new StatusProbeWorkflow();
        }

        @Bean
        @Scope(BeanDefinition.SCOPE_PROTOTYPE)
        BrokenWorkflow brokenWorkflow() {
            throw new IllegalStateException("workflow wiring is broken deliberately");
        }
    }

    /** Completes immediately, echoing its input in upper case. */
    @DurableWorkflow(name = "EchoWorkflow")
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

    /** Always throws, to exercise the failure path. */
    @DurableWorkflow(name = "FailingWorkflow")
    public static class FailingWorkflow {

        /**
         * @param input ignored
         * @return never returns
         */
        @WorkflowMethod
        public String run(String input) {
            throw new IllegalStateException("workflow failed deliberately");
        }
    }

    /** Parks on a signal so timeout, signal and query paths can be exercised. */
    @DurableWorkflow(name = "ParkingWorkflow")
    public static class ParkingWorkflow {

        /** The signal this workflow waits for. */
        public static final String SIGNAL = "go";

        /**
         * @param input ignored
         * @return the payload of the signal that released it
         */
        @WorkflowMethod
        public String run(String input) {
            return WorkflowContext.current()
                    .awaitSignal(SIGNAL, String.class, Duration.ofSeconds(30));
        }

        /**
         * @return a constant describing what the workflow is doing
         */
        @QueryMethod(name = "state")
        public String state() {
            return "waiting";
        }
    }

    /**
     * Records its input in an instance field, then parks — so two concurrent runs
     * prove each has its own workflow object (F1: one shared object leaked state).
     */
    @DurableWorkflow(name = "StatusProbeWorkflow")
    public static class StatusProbeWorkflow {

        // Per-run state; volatile because @QueryMethod runs on the caller's thread.
        private volatile String step = "CREATED";

        /**
         * @param label the value {@link #step()} reports for this run
         */
        @WorkflowMethod
        public void run(String label) {
            step = label;
            WorkflowContext.current().awaitSignal("go", String.class, Duration.ofMinutes(1));
        }

        /**
         * @return the label this run was started with
         */
        @QueryMethod(name = "step")
        public String step() {
            return step;
        }
    }

    /** Never constructed: its {@code @Bean} method throws, like a mis-wired prototype. */
    @DurableWorkflow(name = "BrokenWorkflow")
    public static class BrokenWorkflow {

        /**
         * @param input ignored
         * @return never returns
         */
        @WorkflowMethod
        public String run(String input) {
            return input;
        }
    }
}
