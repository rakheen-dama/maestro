package io.b2mash.maestro.spring.observe;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.spring.client.MaestroClient;
import io.b2mash.maestro.spring.client.WorkflowOptions;
import io.b2mash.maestro.spring.config.MaestroAutoConfiguration;
import io.b2mash.maestro.spring.config.StartupRecoveryRunner;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Context-runner tests for {@link MaestroObservabilityAutoConfiguration}
 * (release-hardening Task 4).
 *
 * <p>Drives the real {@link MaestroAutoConfiguration} +
 * {@link MaestroObservabilityAutoConfiguration} chain over the in-memory SPIs
 * — the same pattern {@code MaestroAutoConfigurationConfigSeamsTest} and
 * {@code MaestroAutoConfigurationLifecycleEventsTest} use — so the meter
 * catalog is proven through a real engine run, not just unit-tested against
 * the adapter directly (that is
 * {@link MicrometerEngineObserverTest}'s job).
 */
@DisplayName("MaestroObservabilityAutoConfiguration")
class MaestroObservabilityAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    MaestroAutoConfiguration.class, MaestroObservabilityAutoConfiguration.class))
            .withUserConfiguration(TestWorkflowConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(WorkflowStore.class, InMemoryWorkflowStore::new)
            .withPropertyValues("maestro.service-name=observability-test");

    @Test
    @DisplayName("maestro.workflow.started increments when a workflow actually runs through the starter's engine")
    void workflowStartedIncrementsThroughRealEngineRun() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var client = context.getBean(MaestroClient.class);
                    var registry = context.getBean(MeterRegistry.class);

                    client.newWorkflow(EchoWorkflow.class, options("observability-started-1"))
                            .startAndWait("hi", Duration.ofSeconds(10), String.class);

                    assertThat(registry.get("maestro.workflow.started")
                            .tag("workflow", "ObservabilityEchoWorkflow")
                            .counter().count()).isEqualTo(1.0);
                    assertThat(registry.get("maestro.workflow.completed")
                            .tag("workflow", "ObservabilityEchoWorkflow")
                            .counter().count()).isEqualTo(1.0);
                });
    }

    @Test
    @DisplayName("maestro.observability.metrics.enabled=false — no maestro.* meters registered at all")
    void disabledFlagRegistersNoMeters() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("maestro.observability.metrics.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(MicrometerEngineObserver.class);
                    assertThat(context).doesNotHaveBean(MaestroEngineGauges.class);

                    var client = context.getBean(MaestroClient.class);
                    var registry = context.getBean(MeterRegistry.class);

                    client.newWorkflow(EchoWorkflow.class, options("observability-disabled-1"))
                            .startAndWait("hi", Duration.ofSeconds(10), String.class);

                    assertThat(registry.getMeters())
                            .as("no maestro.* meter may exist when maestro.observability.metrics.enabled=false")
                            .noneMatch(m -> m.getId().getName().startsWith("maestro."));
                });
    }

    @Test
    @DisplayName("no MicrometerEngineObserver / gauges bean when no MeterRegistry bean is present")
    void absentWithoutMeterRegistryBean() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MicrometerEngineObserver.class);
            assertThat(context).doesNotHaveBean(MaestroEngineGauges.class);
        });
    }

    @Test
    @DisplayName("no MicrometerEngineObserver / gauges bean when MeterRegistry class is not on the classpath")
    void absentWithoutMeterRegistryOnClasspath() {
        runner.withClassLoader(new FilteredClassLoader(MeterRegistry.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(MicrometerEngineObserver.class);
                    assertThat(context).doesNotHaveBean(MaestroEngineGauges.class);
                });
    }

    @Test
    @DisplayName("maestro.workflows.running / maestro.workflows.parked gauges are registered against the executor")
    void gaugesRegisteredAgainstExecutor() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MaestroEngineGauges.class);
                    var registry = context.getBean(MeterRegistry.class);
                    var executor = context.getBean(WorkflowExecutor.class);

                    assertThat(registry.get("maestro.workflows.running").gauge().value())
                            .isEqualTo(executor.runningCount());
                    assertThat(registry.get("maestro.workflows.parked").gauge().value())
                            .isEqualTo(executor.parkedCount());
                });
    }

    @Test
    @DisplayName("a recovered workflow's replayed activities do NOT increment maestro.activity.duration again")
    void replayedActivitiesDoNotDoubleCount() {
        var registryA = new SimpleMeterRegistry();
        runner.withBean(MeterRegistry.class, () -> registryA)
                .run(contextA -> {
                    assertThat(contextA).hasNotFailed();
                    var store = contextA.getBean(WorkflowStore.class);
                    var client = contextA.getBean(MaestroClient.class);
                    var workflow = contextA.getBean(ParkingActivityWorkflow.class);

                    client.newWorkflow(ParkingActivityWorkflow.class, options("observability-replay-1"))
                            .startAsync(null);
                    assertThat(workflow.reachedGate.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    await().atMost(Duration.ofSeconds(2)).until(() ->
                            store.getInstance("observability-replay-1")
                                    .map(i -> i.status() == WorkflowStatus.WAITING_SIGNAL).orElse(false));

                    assertThat(registryA.get("maestro.activity.duration")
                            .tag("activity", "ObservabilityCountingActivities.step")
                            .tag("outcome", "completed").timer().count()).isEqualTo(1);

                    // "Crash": stop this node while the workflow is parked, durable state
                    // stays WAITING_SIGNAL — exactly what recovery replays from.
                    contextA.getBean(WorkflowExecutor.class).shutdown();

                    // ── Recovery on a second context over the SAME store ──
                    var registryB = new SimpleMeterRegistry();
                    new ApplicationContextRunner()
                            .withConfiguration(AutoConfigurations.of(
                                    MaestroAutoConfiguration.class, MaestroObservabilityAutoConfiguration.class))
                            .withUserConfiguration(TestWorkflowConfiguration.class)
                            .withBean(ObjectMapper.class, ObjectMapper::new)
                            .withBean(WorkflowStore.class, () -> store)
                            .withBean(MeterRegistry.class, () -> registryB)
                            .withPropertyValues("maestro.service-name=observability-test")
                            .run(contextB -> {
                                assertThat(contextB).hasNotFailed();
                                var executorB = contextB.getBean(WorkflowExecutor.class);
                                executorB.deliverSignal("observability-replay-1", "resume", "go");

                                // Simulates StartupRecoveryRunner — ApplicationContextRunner
                                // never invokes ApplicationRunner beans itself.
                                contextB.getBean(StartupRecoveryRunner.class).run(null);

                                await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                                        assertThat(store.getInstance("observability-replay-1").orElseThrow().status())
                                                .isEqualTo(WorkflowStatus.COMPLETED));

                                assertThat(registryB.get("maestro.activity.duration")
                                        .tag("activity", "ObservabilityCountingActivities.step")
                                        .tag("outcome", "completed").timer().count())
                                        .as("the replayed step must not be counted again on the recovering node")
                                        .isEqualTo(1);
                                assertThat(registryB.find("maestro.workflow.started").counter())
                                        .as("recovery is a resume, never a second workflowStarted")
                                        .isNull();
                                assertThat(registryB.get("maestro.workflow.completed")
                                        .tag("workflow", "ObservabilityParkingActivityWorkflow")
                                        .counter().count()).isEqualTo(1.0);
                            });

                    // Node A's own ledger: exactly one live completion for the step run before the park.
                    assertThat(registryA.get("maestro.activity.duration")
                            .tag("activity", "ObservabilityCountingActivities.step")
                            .tag("outcome", "completed").timer().count()).isEqualTo(1);
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
        ParkingActivityWorkflow parkingActivityWorkflow() {
            return new ParkingActivityWorkflow();
        }

        @Bean
        CountingActivities countingActivities() {
            return new CountingActivitiesImpl();
        }
    }

    /** Completes immediately, echoing its input in upper case. */
    @DurableWorkflow(name = "ObservabilityEchoWorkflow")
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

    /** Runs one activity, parks on a signal, then completes — for the replay pin. */
    @DurableWorkflow(name = "ObservabilityParkingActivityWorkflow")
    public static class ParkingActivityWorkflow {

        @ActivityStub
        private CountingActivities activities;

        /** Counted down once the workflow has reached its await point. */
        final java.util.concurrent.CountDownLatch reachedGate = new java.util.concurrent.CountDownLatch(1);

        /**
         * @param input ignored
         * @return a constant once the signal arrives
         */
        @WorkflowMethod
        public String run(String input) {
            activities.step();
            reachedGate.countDown();
            WorkflowContext.current().awaitSignal("resume", String.class, Duration.ofSeconds(30));
            // A second live call on the recovering node — proves the replayed
            // first call didn't touch the timer while a genuinely live call does.
            activities.step();
            return "done";
        }
    }

    /** Single no-op activity, named for the replay pin's meter assertions. */
    @Activity(name = "ObservabilityCountingActivities")
    public interface CountingActivities {
        /** Does nothing observable beyond the memoized result. */
        void step();
    }

    /** No-op implementation of {@link CountingActivities}. */
    public static class CountingActivitiesImpl implements CountingActivities {
        @Override
        public void step() {
            // no-op
        }
    }
}
