package io.b2mash.maestro.spring.client;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.QueryMethod;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.WorkflowExecutionException;
import io.b2mash.maestro.core.exception.WorkflowNotFoundException;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.spring.config.MaestroAutoConfiguration;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                var workflow = context.getBean(ParkingWorkflow.class);

                client.newWorkflow(ParkingWorkflow.class, options("park-2")).startAsync(null);
                assertTrue(workflow.parked.await(10, TimeUnit.SECONDS),
                        "the workflow must reach its await point");

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
                var workflow = context.getBean(ParkingWorkflow.class);

                client.newWorkflow(ParkingWorkflow.class, options("park-3")).startAsync(null);
                assertTrue(workflow.parked.await(10, TimeUnit.SECONDS));

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
        FailingWorkflow failingWorkflow() {
            return new FailingWorkflow();
        }

        @Bean
        ParkingWorkflow parkingWorkflow() {
            return new ParkingWorkflow();
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

        /** Counted down once the workflow has reached its await point. */
        final CountDownLatch parked = new CountDownLatch(1);

        /**
         * @param input ignored
         * @return the payload of the signal that released it
         */
        @WorkflowMethod
        public String run(String input) {
            parked.countDown();
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
}
