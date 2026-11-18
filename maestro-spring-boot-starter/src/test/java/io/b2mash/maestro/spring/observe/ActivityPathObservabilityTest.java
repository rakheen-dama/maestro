package io.b2mash.maestro.spring.observe;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.exception.StoreUnavailableException;
import io.b2mash.maestro.core.observe.ActivityInfo;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.spring.client.MaestroClient;
import io.b2mash.maestro.spring.client.WorkflowOptions;
import io.b2mash.maestro.spring.config.MaestroAutoConfiguration;
import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review F1, F2: the activity proxies the starter builds share the executor's
 * guarded store and contained observer, so an absorbed fault on an activity's
 * outcome append moves {@code maestro.store.retries} and opens one episode per
 * node, not one per proxy, and an observer failure from an activity callback
 * moves {@code maestro.observer.errors}.
 */
class ActivityPathObservabilityTest {

    private final ListAppender<ILoggingEvent> guardLog = new ListAppender<>();
    private final Logger guardLogger =
            (Logger) LoggerFactory.getLogger("io.b2mash.maestro.core.engine.StoreFaultGuard");

    @BeforeEach
    void attach() {
        guardLog.start();
        guardLogger.addAppender(guardLog);
    }

    @AfterEach
    void detach() {
        guardLogger.detachAppender(guardLog);
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        MaestroAutoConfiguration.class, MaestroObservabilityAutoConfiguration.class))
                .withUserConfiguration(TwoActivitiesConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(DistributedLock.class, InMemoryDistributedLock::new)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("maestro.service-name=activity-path-test");
    }

    @Test
    void anAbsorbedFaultOnAnActivityOutcomeAppendIsCountedAndWarnedOncePerEpisodeAcrossProxies() {
        var faults = new AtomicInteger();
        runner().withBean(WorkflowStore.class, () -> failingEachOutcomeAppendOnce(new InMemoryWorkflowStore(), faults))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var result = context.getBean(MaestroClient.class)
                            .newWorkflow(TwoActivitiesWorkflow.class,
                                    WorkflowOptions.builder().workflowId("activity-path-retry").build())
                            .startAndWait("x", Duration.ofSeconds(15), String.class);

                    assertThat(result).isEqualTo("a(x)+b(x)");
                    assertThat(faults.get()).as("both proxies' outcome appends were failed once").isEqualTo(2);
                    var registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.get("maestro.store.retries")
                            .tag("operation", "append_event_returning_payload").counter().count())
                            .isEqualTo(2.0);
                    assertThat(guardLog.list.stream().filter(e -> e.getLevel() == Level.WARN).toList())
                            .as("one WARN for the node's episode, across both proxies")
                            .hasSize(1);
                });
    }

    @Test
    void aThrowingObserverOnActivityCompletedMovesMaestroObserverErrors() {
        runner().withBean(WorkflowStore.class, InMemoryWorkflowStore::new)
                .withBean("throwingActivityObserver", EngineObserver.class, () -> new EngineObserver() {
                    @Override
                    public void activityCompleted(ActivityInfo a, Duration duration, boolean replayed) {
                        throw new IllegalStateException("observer bug");
                    }
                })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(MaestroClient.class)
                            .newWorkflow(TwoActivitiesWorkflow.class,
                                    WorkflowOptions.builder().workflowId("activity-path-observer").build())
                            .startAndWait("x", Duration.ofSeconds(15), String.class);

                    var registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.get("maestro.observer.errors").functionCounter().count())
                            .as("two activity completions, each contained once")
                            .isEqualTo(2.0);
                });
    }

    /** Every {@code appendEventReturningPayload} of an activity outcome fails once, then succeeds. */
    private static WorkflowStore failingEachOutcomeAppendOnce(InMemoryWorkflowStore delegate, AtomicInteger faults) {
        var failedSequences = java.util.concurrent.ConcurrentHashMap.<Integer>newKeySet();
        return (WorkflowStore) Proxy.newProxyInstance(WorkflowStore.class.getClassLoader(),
                new Class<?>[] {WorkflowStore.class}, (self, method, args) -> {
                    if (method.getName().equals("appendEventReturningPayload")
                            && args[0] instanceof io.b2mash.maestro.core.model.WorkflowEvent event
                            && failedSequences.add(event.sequenceNumber())) {
                        faults.incrementAndGet();
                        throw new StoreUnavailableException("injected blip",
                                new java.sql.SQLTransientConnectionException("connection reset"));
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoActivitiesConfiguration {

        @Bean
        @Scope(BeanDefinition.SCOPE_PROTOTYPE)
        TwoActivitiesWorkflow twoActivitiesWorkflow() {
            return new TwoActivitiesWorkflow();
        }

        @Bean
        StepA stepA() {
            return input -> "a(" + input + ")";
        }

        @Bean
        StepB stepB() {
            return input -> "b(" + input + ")";
        }
    }

    /** One activity of the workflow. */
    @Activity(name = "ActivityPathStepA")
    public interface StepA {
        /** @param input the input @return a marker */
        String run(String input);
    }

    /** The other activity, behind its own proxy. */
    @Activity(name = "ActivityPathStepB")
    public interface StepB {
        /** @param input the input @return a marker */
        String run(String input);
    }

    /** Calls two activities, each through its own proxy. */
    @DurableWorkflow(name = "ActivityPathWorkflow")
    public static class TwoActivitiesWorkflow {

        @ActivityStub
        private StepA a;

        @ActivityStub
        private StepB b;

        /** @param input the input @return both activities' results */
        @WorkflowMethod
        public String run(String input) {
            return a.run(input) + "+" + b.run(input);
        }
    }
}
