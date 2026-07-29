package io.b2mash.maestro.spring.config;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.spring.client.MaestroClient;
import io.b2mash.maestro.spring.client.WorkflowOptions;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Pins that three configuration seams actually reach the engine through the
 * real auto-configuration chain (Issues 7 + 9):
 * {@code maestro.shutdown.timeout}, {@code maestro.signal.wake-recheck-interval},
 * and {@code maestro.lock.key-prefix} for the per-activity lock (in addition
 * to the instance lock, which already honoured it).
 *
 * <p>Before this fix, all three had a hardcoded 30-second (or {@code maestro:lock:})
 * value baked into {@link WorkflowExecutor} / {@code ActivityInvocationHandler}
 * with no property to override it. These tests drive the real
 * {@link MaestroAutoConfiguration} chain over the in-memory SPIs — the same
 * pattern {@link MaestroAutoConfigurationLifecycleEventsTest} uses — so the
 * wiring from {@link MaestroProperties} into the engine is covered, not just
 * the property binding.
 */
@DisplayName("shutdown timeout, wake-recheck-interval and activity lock prefix reach the engine")
class MaestroAutoConfigurationConfigSeamsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MaestroAutoConfiguration.class))
            .withUserConfiguration(TestWorkflowConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(WorkflowStore.class, InMemoryWorkflowStore::new)
            .withPropertyValues("maestro.service-name=config-seams-test");

    @Test
    @DisplayName("maestro.shutdown.timeout bounds WorkflowExecutor#shutdown() instead of the 30s default")
    void shutdownTimeoutReachesWorkflowExecutor() {
        runner.withPropertyValues("maestro.shutdown.timeout=300ms")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var client = context.getBean(MaestroClient.class);
                    var workflow = context.getBean(BlockingWorkflow.class);
                    var executor = context.getBean(WorkflowExecutor.class);

                    client.newWorkflow(BlockingWorkflow.class, options("shutdown-timeout-1")).startAsync(null);
                    assertThat(workflow.entered.await(10, TimeUnit.SECONDS))
                            .as("the workflow must be in-flight before shutdown")
                            .isTrue();

                    var start = Instant.now();
                    executor.shutdown();
                    var elapsed = Duration.between(start, Instant.now());

                    assertThat(elapsed)
                            .as("shutdown must return once the configured 300ms timeout elapses, "
                                    + "not wait out the 30s default — took " + elapsed)
                            .isLessThan(Duration.ofSeconds(5));
                });
    }

    @Test
    @DisplayName("maestro.signal.wake-recheck-interval bounds cross-node signal latency in SignalManager")
    void wakeRecheckIntervalReachesSignalManager() {
        runner.withPropertyValues("maestro.signal.wake-recheck-interval=200ms")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var client = context.getBean(MaestroClient.class);
                    var store = context.getBean(WorkflowStore.class);
                    var workflow = context.getBean(ParkingWorkflow.class);

                    client.newWorkflow(ParkingWorkflow.class, options("wake-recheck-1")).startAsync(null);
                    assertThat(workflow.parked.await(10, TimeUnit.SECONDS)).isTrue();
                    await().atMost(Duration.ofSeconds(2)).until(() ->
                            store.getInstance("wake-recheck-1")
                                    .map(i -> i.status() == WorkflowStatus.WAITING_SIGNAL)
                                    .orElse(false));

                    // Persist the signal directly — bypasses MaestroClient's signal
                    // path (and any local unpark), simulating cross-node delivery
                    // with no SignalNotifier. Only the periodic store re-check —
                    // the interval under test — can find it.
                    var instanceId = store.getInstance("wake-recheck-1").orElseThrow().id();
                    store.saveSignal(new WorkflowSignal(
                            UUID.randomUUID(), instanceId, "wake-recheck-1", ParkingWorkflow.SIGNAL,
                            null, false, Instant.now()));

                    await("the 200ms wake-recheck-interval must have reached SignalManager — "
                            + "with the 30s default this would still be waiting")
                            .atMost(Duration.ofSeconds(3))
                            .until(() -> store.getInstance("wake-recheck-1")
                                    .map(i -> i.status() == WorkflowStatus.COMPLETED)
                                    .orElse(false));
                });
    }

    @Test
    @DisplayName("maestro.lock.key-prefix reaches the per-activity distributed lock, not just the instance lock")
    void lockKeyPrefixReachesActivityInvocationHandler() {
        var lock = new RecordingLock();
        runner.withBean(DistributedLock.class, () -> lock)
                .withPropertyValues("maestro.lock.key-prefix=custom:prefix:")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var client = context.getBean(MaestroClient.class);

                    client.newWorkflow(ActivityCallingWorkflow.class, options("lock-prefix-1"))
                            .startAndWait(null, Duration.ofSeconds(10), String.class);

                    var activityKeys = lock.acquiredKeys.stream()
                            .filter(key -> key.contains(":activity:"))
                            .toList();
                    assertThat(activityKeys)
                            .as("the activity lock key must use the configured prefix, "
                                    + "not the hardcoded maestro:lock: default — acquired keys were "
                                    + lock.acquiredKeys)
                            .containsExactly("custom:prefix:activity:lock-prefix-1:1");
                });
    }

    private static WorkflowOptions options(String workflowId) {
        return WorkflowOptions.builder().workflowId(workflowId).build();
    }

    /** Registers the workflow beans the client resolves through WorkflowRegistrar. */
    @Configuration(proxyBeanMethods = false)
    static class TestWorkflowConfiguration {

        @Bean
        BlockingWorkflow blockingWorkflow() {
            return new BlockingWorkflow();
        }

        @Bean
        ParkingWorkflow parkingWorkflow() {
            return new ParkingWorkflow();
        }

        @Bean
        ActivityCallingWorkflow activityCallingWorkflow() {
            return new ActivityCallingWorkflow();
        }

        @Bean
        NoopActivities noopActivities() {
            return new NoopActivitiesImpl();
        }
    }

    /** Blocks forever inside the workflow body — an in-flight, undrained workflow. */
    @DurableWorkflow(name = "ConfigSeamsBlockingWorkflow")
    public static class BlockingWorkflow {

        /** Counted down once the workflow body is running. */
        final CountDownLatch entered = new CountDownLatch(1);

        /**
         * @param input ignored
         * @return never returns within the test's lifetime
         */
        @WorkflowMethod
        public String run(String input) {
            entered.countDown();
            try {
                new CountDownLatch(1).await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "unreachable";
        }
    }

    /** Parks on a signal so the wake-recheck-interval path can be exercised. */
    @DurableWorkflow(name = "ConfigSeamsParkingWorkflow")
    public static class ParkingWorkflow {

        /** The signal this workflow waits for. */
        public static final String SIGNAL = "go";

        /** Counted down once the workflow has reached its await point. */
        final CountDownLatch parked = new CountDownLatch(1);

        /**
         * @param input ignored
         * @return a constant once the signal arrives
         */
        @WorkflowMethod
        public String run(String input) {
            parked.countDown();
            WorkflowContext.current().awaitSignal(SIGNAL, String.class, Duration.ofSeconds(30));
            return "done";
        }
    }

    /** Calls a single no-op activity, to drive the activity lock key through the real chain. */
    @DurableWorkflow(name = "ConfigSeamsActivityCallingWorkflow")
    public static class ActivityCallingWorkflow {

        @ActivityStub
        private NoopActivities activities;

        /**
         * @param input ignored
         * @return a constant
         */
        @WorkflowMethod
        public String run(String input) {
            activities.doWork();
            return "done";
        }
    }

    /** Single-method activity interface with no side effects. */
    @Activity(name = "NoopActivities")
    public interface NoopActivities {
        /** Does nothing. */
        void doWork();
    }

    /** No-op implementation of {@link NoopActivities}. */
    public static class NoopActivitiesImpl implements NoopActivities {
        @Override
        public void doWork() {
            // no-op
        }
    }

    /** Records every lock key it is asked to acquire; always grants the lock. */
    private static final class RecordingLock implements DistributedLock {

        final List<String> acquiredKeys = new ArrayList<>();

        @Override
        public synchronized Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            acquiredKeys.add(key);
            return Optional.of(new LockHandle(key, UUID.randomUUID().toString(), Instant.now().plus(ttl)));
        }

        @Override
        public void release(LockHandle handle) {
            // no-op — nothing in these tests asserts on release
        }

        @Override
        public boolean renew(LockHandle handle, Duration ttl) {
            return true;
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return false;
        }
    }
}
