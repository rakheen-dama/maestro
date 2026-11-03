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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionCustomizer;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Pins that configuration seams actually reach the engine through the
 * real auto-configuration chain (Issues 7 + 9, audit finding F6):
 * {@code maestro.shutdown.timeout}, {@code maestro.signal.wake-recheck-interval},
 * {@code maestro.lock.key-prefix} for the per-activity lock (in addition
 * to the instance lock, which already honoured it), and
 * {@code maestro.retry.default-*} for the default {@code @ActivityStub}
 * retry policy. {@code maestro.signal.janitor-interval} and
 * {@code maestro.signal.terminal-retention} (C14b) are pinned the same way,
 * through {@link StartupRecoveryRunner}.
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
    @DisplayName("the auto-configured executor arms sleep timers and await deadlines on the system clock (R59)")
    void autoConfiguredExecutorArmsDeadlinesOnTheSystemClock() {
        // The InstantSource seam exists for TestWorkflowEnvironment's test clock;
        // production deadlines must stay on the clock the timer poller's due
        // check reads.
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(WorkflowExecutor.class).instantSource())
                    .isSameAs(InstantSource.system());
        });
    }

    @Test
    @DisplayName("maestro.shutdown.timeout bounds WorkflowExecutor#shutdown() instead of the 30s default")
    void shutdownTimeoutReachesWorkflowExecutor() {
        runner.withPropertyValues("maestro.shutdown.timeout=300ms")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var client = context.getBean(MaestroClient.class);
                    var probe = context.getBean(BlockingProbe.class);
                    var executor = context.getBean(WorkflowExecutor.class);

                    client.newWorkflow(BlockingWorkflow.class, options("shutdown-timeout-1")).startAsync(null);
                    assertThat(probe.entered.await(10, TimeUnit.SECONDS))
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
                    var executor = context.getBean(WorkflowExecutor.class);

                    client.newWorkflow(ParkingWorkflow.class, options("wake-recheck-1")).startAsync(null);
                    await().atMost(Duration.ofSeconds(10)).until(() -> executor.parkedCount() == 1);
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
                .withBean(DefaultRetryWorkflow.class, DefaultRetryWorkflow::new, PROTOTYPE)
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
                .withBean(CustomRetryWorkflow.class, CustomRetryWorkflow::new, PROTOTYPE)
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

    @Test
    @DisplayName("maestro.signal.janitor-interval and maestro.signal.terminal-retention reach the executor's signal janitor")
    void signalJanitorSettingsReachTheExecutor() {
        runner.withPropertyValues("maestro.signal.janitor-interval=2h", "maestro.signal.terminal-retention=3d")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // ApplicationContextRunner never invokes ApplicationRunner beans
                    // itself, so drive the starter's runner explicitly.
                    context.getBean(StartupRecoveryRunner.class).run(new DefaultApplicationArguments());
                    var executor = context.getBean(WorkflowExecutor.class);
                    assertThat(executor.isSignalJanitorRunning()).isTrue();
                    assertThat(executor.signalJanitorSettings())
                            .as("the configured 2h/3d must reach the engine, not the 1h/7d defaults")
                            .isEqualTo(new WorkflowExecutor.SignalJanitorSettings(Duration.ofHours(2), Duration.ofDays(3)));
                    executor.shutdown();
                    assertThat(executor.isSignalJanitorRunning()).isFalse();
                });
    }

    @Test
    @DisplayName("maestro.worker.task-queues[] concurrency and activity-concurrency reach the executor's governor (F8)")
    void workerConcurrencyReachesTheGovernor() {
        runner.withPropertyValues("maestro.worker.task-queues[0].name=orders",
                        "maestro.worker.task-queues[0].concurrency=2",
                        "maestro.worker.task-queues[0].activity-concurrency=3")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var governor = context.getBean(WorkflowExecutor.class).taskQueueGovernor();
                    assertThat(governor.availableWorkflowSlots("orders")).isEqualTo(OptionalInt.of(2));
                    assertThat(governor.availableActivitySlots("orders")).isEqualTo(OptionalInt.of(3));
                    assertThat(governor.availableWorkflowSlots("unconfigured")).isEmpty();
                });
    }

    @Test
    @DisplayName("an @ActivityStub proxy holds an activity slot of its workflow's task queue while it executes (F8)")
    void activityStubProxiesUseTheExecutorsGovernor() {
        var activity = new SlotProbeActivitiesImpl();
        runner.withBean(SlotProbeActivities.class, () -> activity)
                .withBean(OrdersQueueWorkflow.class, OrdersQueueWorkflow::new, PROTOTYPE)
                .withPropertyValues("maestro.worker.task-queues[0].name=orders",
                        "maestro.worker.task-queues[0].activity-concurrency=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var governor = context.getBean(WorkflowExecutor.class).taskQueueGovernor();
                    activity.probe = () -> governor.availableActivitySlots("orders");

                    context.getBean(MaestroClient.class)
                            .newWorkflow(OrdersQueueWorkflow.class, options("activity-slot-1"))
                            .startAndWait(null, Duration.ofSeconds(10), String.class);

                    assertThat(activity.seen)
                            .as("the live execution must hold the queue's only activity slot")
                            .isEqualTo(OptionalInt.of(0));
                    assertThat(governor.availableActivitySlots("orders")).isEqualTo(OptionalInt.of(1));
                });
    }

    @Test
    @DisplayName("a queue named twice in maestro.worker.task-queues fails startup (F8)")
    void duplicateQueueNameFailsStartup() {
        runner.withPropertyValues("maestro.worker.task-queues[0].name=orders",
                        "maestro.worker.task-queues[1].name=orders")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("maestro.worker.task-queues names a queue twice: 'orders'");
                });
    }

    @Test
    @DisplayName("a maestro.worker.task-queues limit below 1 fails startup (F8)")
    void nonPositiveLimitFailsStartup() {
        runner.withPropertyValues("maestro.worker.task-queues[0].name=orders",
                        "maestro.worker.task-queues[0].concurrency=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("workflowConcurrency must be >= 1");
                });
    }

    /** Workflow beans must be prototype-scoped: Maestro creates one object per run. */
    private static final BeanDefinitionCustomizer PROTOTYPE =
            bd -> bd.setScope(BeanDefinition.SCOPE_PROTOTYPE);

    private static WorkflowOptions options(String workflowId) {
        return WorkflowOptions.builder().workflowId(workflowId).build();
    }

    /**
     * Registers the workflow beans the client resolves through WorkflowRegistrar.
     * Workflow beans are prototype-scoped: Maestro creates one object per run.
     */
    @Configuration(proxyBeanMethods = false)
    static class TestWorkflowConfiguration {

        @Bean
        BlockingProbe blockingProbe() {
            return new BlockingProbe();
        }

        @Bean
        @Scope(BeanDefinition.SCOPE_PROTOTYPE)
        BlockingWorkflow blockingWorkflow(BlockingProbe probe) {
            return new BlockingWorkflow(probe);
        }

        @Bean
        @Scope(BeanDefinition.SCOPE_PROTOTYPE)
        ParkingWorkflow parkingWorkflow() {
            return new ParkingWorkflow();
        }

        @Bean
        @Scope(BeanDefinition.SCOPE_PROTOTYPE)
        ActivityCallingWorkflow activityCallingWorkflow() {
            return new ActivityCallingWorkflow();
        }

        @Bean
        NoopActivities noopActivities() {
            return new NoopActivitiesImpl();
        }
    }

    /**
     * Context-scoped (singleton) rendezvous for {@link BlockingWorkflow}: the workflow
     * object is a per-run prototype, so the test cannot read a latch off a bean it
     * looks up itself.
     */
    static final class BlockingProbe {

        /** Counted down once the workflow body is running. */
        final CountDownLatch entered = new CountDownLatch(1);
    }

    /** Blocks forever inside the workflow body — an in-flight, undrained workflow. */
    @DurableWorkflow(name = "ConfigSeamsBlockingWorkflow")
    public static class BlockingWorkflow {

        private final BlockingProbe probe;

        BlockingWorkflow(BlockingProbe probe) {
            this.probe = probe;
        }

        /**
         * @param input ignored
         * @return never returns within the test's lifetime
         */
        @WorkflowMethod
        public String run(String input) {
            probe.entered.countDown();
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

        /**
         * @param input ignored
         * @return a constant once the signal arrives
         */
        @WorkflowMethod
        public String run(String input) {
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

    /** Runs on the {@code orders} task queue and calls one slot-probing activity. */
    @DurableWorkflow(name = "ConfigSeamsOrdersQueueWorkflow", taskQueue = "orders")
    public static class OrdersQueueWorkflow {

        @ActivityStub
        private SlotProbeActivities activities;

        /**
         * @param input ignored
         * @return a constant
         */
        @WorkflowMethod
        public String run(String input) {
            activities.probe();
            return "done";
        }
    }

    /** Single-method activity interface that samples the governor while executing. */
    @Activity(name = "SlotProbeActivities")
    public interface SlotProbeActivities {
        /** Records what the probe sees. */
        void probe();
    }

    /** Records the free activity slots of the workflow's queue from inside a live execution. */
    public static class SlotProbeActivitiesImpl implements SlotProbeActivities {

        volatile Supplier<OptionalInt> probe = OptionalInt::empty;
        volatile @Nullable OptionalInt seen;

        @Override
        public void probe() {
            seen = probe.get();
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
