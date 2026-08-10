package io.b2mash.maestro.spring.config;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.RetryPolicy;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Pins that configuration seams actually reach the engine through the
 * real auto-configuration chain (Issues 7 + 9, audit finding F6):
 * {@code maestro.shutdown.timeout}, {@code maestro.signal.wake-recheck-interval},
 * {@code maestro.lock.key-prefix} for the per-activity lock (in addition
 * to the instance lock, which already honoured it), and
 * {@code maestro.retry.default-*} for the default {@code @ActivityStub}
 * retry policy.
 *
 * <p>Before this fix, all four had a hardcoded value baked into
 * {@link WorkflowExecutor} / {@code ActivityInvocationHandler} /
 * {@code ActivityStubBeanPostProcessor} — 30 seconds, {@code maestro:lock:},
 * or {@code RetryPolicy.defaultPolicy()} (3 attempts, 1s/60s/2x backoff) —
 * with no property to override it. These tests drive the real
 * {@link MaestroAutoConfiguration} chain over the in-memory SPIs — the same
 * pattern {@link MaestroAutoConfigurationLifecycleEventsTest} uses — so the
 * wiring from {@link MaestroProperties} into the engine is covered, not just
 * the property binding.
 */
@DisplayName("shutdown timeout, wake-recheck-interval, activity lock prefix and default retry policy reach the engine")
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

    @Test
    @DisplayName("maestro.retry.default-max-attempts provides the default @ActivityStub retry policy (audit F6)")
    void defaultMaxAttemptsReachesDefaultActivityStubRetryPolicy() {
        var activity = new AlwaysFailingActivitiesImpl();
        runner.withBean(AlwaysFailingActivities.class, () -> activity)
                .withBean(DefaultRetryWorkflow.class, DefaultRetryWorkflow::new)
                .withPropertyValues("maestro.retry.default-max-attempts=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var client = context.getBean(MaestroClient.class);
                    var store = context.getBean(WorkflowStore.class);

                    client.newWorkflow(DefaultRetryWorkflow.class, options("default-retry-1"))
                            .startAsync(null);

                    await("the workflow must fail once the configured single attempt is exhausted")
                            .atMost(Duration.ofSeconds(10))
                            .until(() -> store.getInstance("default-retry-1")
                                    .map(i -> i.status() == WorkflowStatus.FAILED)
                                    .orElse(false));

                    assertThat(activity.invocationCount.get())
                            .as("maestro.retry.default-max-attempts=1 must bound the default "
                                    + "@ActivityStub retry policy to a single attempt, not the "
                                    + "hardcoded RetryPolicy.defaultPolicy() 3-attempt default")
                            .isEqualTo(1);
                });
    }

    @Test
    @DisplayName("an @ActivityStub with an explicit retryPolicy keeps its own attempt count "
            + "even when maestro.retry.default-max-attempts differs")
    void explicitRetryPolicyOverridesConfiguredDefault() {
        var activity = new AlwaysFailingActivitiesImpl();
        runner.withBean(AlwaysFailingActivities.class, () -> activity)
                .withBean(CustomRetryWorkflow.class, CustomRetryWorkflow::new)
                .withPropertyValues("maestro.retry.default-max-attempts=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var client = context.getBean(MaestroClient.class);
                    var store = context.getBean(WorkflowStore.class);

                    client.newWorkflow(CustomRetryWorkflow.class, options("custom-retry-1"))
                            .startAsync(null);

                    // 5 attempts at the annotation's default backoff (1s, 2s, 4s, 8s
                    // between attempts) take ~15s — well above the other seams' budget.
                    await("the workflow must fail once the annotation's 5 attempts are exhausted")
                            .atMost(Duration.ofSeconds(25))
                            .until(() -> store.getInstance("custom-retry-1")
                                    .map(i -> i.status() == WorkflowStatus.FAILED)
                                    .orElse(false));

                    assertThat(activity.invocationCount.get())
                            .as("@ActivityStub(retryPolicy = @RetryPolicy(maxAttempts = 5)) must win over "
                                    + "maestro.retry.default-max-attempts=1 — the annotation was explicitly "
                                    + "customized, so it must not resolve to the configured default")
                            .isEqualTo(5);
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

    /** Calls a single always-failing activity with the default (unset) {@code @ActivityStub} retry policy. */
    @DurableWorkflow(name = "ConfigSeamsDefaultRetryWorkflow")
    public static class DefaultRetryWorkflow {

        @ActivityStub
        private AlwaysFailingActivities activities;

        /**
         * @param input ignored
         * @return never returns — the activity always throws and retries are exhausted
         */
        @WorkflowMethod
        public String run(String input) {
            activities.call();
            return "unreachable";
        }
    }

    /** Calls a single always-failing activity with an explicit, non-default retry policy. */
    @DurableWorkflow(name = "ConfigSeamsCustomRetryWorkflow")
    public static class CustomRetryWorkflow {

        @ActivityStub(retryPolicy = @RetryPolicy(maxAttempts = 5))
        private AlwaysFailingActivities activities;

        /**
         * @param input ignored
         * @return never returns — the activity always throws and retries are exhausted
         */
        @WorkflowMethod
        public String run(String input) {
            activities.call();
            return "unreachable";
        }
    }

    /** Single-method activity interface whose implementation always throws. */
    @Activity(name = "AlwaysFailingActivities")
    public interface AlwaysFailingActivities {
        /** Always throws. */
        void call();
    }

    /** Counts invocations and always throws — pins the effective retry attempt count. */
    public static class AlwaysFailingActivitiesImpl implements AlwaysFailingActivities {

        /** Number of times {@link #call()} has actually been invoked. */
        final AtomicInteger invocationCount = new AtomicInteger();

        @Override
        public void call() {
            invocationCount.incrementAndGet();
            throw new RuntimeException("always fails");
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
