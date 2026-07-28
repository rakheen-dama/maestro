package io.b2mash.maestro.integration.support;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.engine.ActivityProxyFactory;
import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.engine.WorkflowRegistration;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.SignalNotifier;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wires a real {@link WorkflowExecutor} over real backend SPIs for integration
 * testing — the counterpart of {@code TestWorkflowEnvironment}, but without the
 * in-memory fakes.
 *
 * <p>This harness performs the same annotation-driven wiring the Spring starter
 * does, using the real {@link ActivityProxyFactory}: it resolves the
 * {@link WorkflowMethod}, injects {@link ActivityStub} fields with activity
 * proxies, and registers workflows so recovery can re-invoke them.
 *
 * <h2>Modelling a node</h2>
 * <p>One harness is one node. To exercise multi-node topologies, build a second
 * harness with a different {@code serviceName} over the <em>same</em> store and
 * lock backend. Never share a harness between simulated nodes.
 *
 * <h2>Thread Safety</h2>
 * <p>Registration methods are intended to be called from the test thread during
 * setup. Once built, {@link #start}, {@link #deliverSignal} and the accessors
 * are thread-safe, delegating to the executor's own guarantees.
 */
public final class MaestroEngineHarness implements AutoCloseable {

    private final WorkflowStore store;
    private final PayloadSerializer serializer;
    private final @Nullable DistributedLock lock;
    private final @Nullable WorkflowMessaging messaging;
    private final WorkflowExecutor executor;
    private final ActivityProxyFactory proxyFactory = new ActivityProxyFactory();
    private final RetryExecutor retryExecutor = new RetryExecutor();
    private final Map<Class<?>, Object> activityImpls = new ConcurrentHashMap<>();
    private final Map<String, WorkflowRegistration> registrations = new ConcurrentHashMap<>();

    private MaestroEngineHarness(Builder builder) {
        this.store = builder.store;
        this.serializer = new PayloadSerializer(builder.objectMapper);
        this.lock = builder.lock;
        this.messaging = builder.messaging;
        this.executor = new WorkflowExecutor(
                builder.store,
                builder.lock,
                builder.messaging,
                builder.signalNotifier,
                this.serializer,
                builder.serviceName,
                builder.lockKeyPrefix,
                builder.instanceLockTtl);
    }

    /**
     * Starts building a harness.
     *
     * @param store        the workflow store under test
     * @param objectMapper Jackson 3 mapper for payloads
     * @return a new builder
     */
    public static Builder builder(WorkflowStore store, ObjectMapper objectMapper) {
        return new Builder(store, objectMapper);
    }

    /**
     * Registers an activity implementation to be injected into
     * {@link ActivityStub} fields of registered workflows.
     *
     * @param activityInterface the activity interface
     * @param impl              the implementation
     * @param <T>               the activity type
     * @return this harness
     */
    public <T> MaestroEngineHarness registerActivities(Class<T> activityInterface, T impl) {
        activityImpls.put(activityInterface, impl);
        return this;
    }

    /**
     * Registers a workflow instance, wiring its activity stubs and resolving its
     * workflow method. Registration is what makes a workflow recoverable — the
     * same registrations are handed to {@link #recover()} and the recovery poller.
     *
     * <p>Instances (not classes) are registered so a test can hold a reference
     * and assert on counters the workflow touches.
     *
     * @param workflowImpl the workflow instance
     * @return this harness
     */
    public MaestroEngineHarness registerWorkflow(Object workflowImpl) {
        var type = workflowType(workflowImpl.getClass());
        wireActivityStubs(workflowImpl);
        registrations.put(type, new WorkflowRegistration(
                type, taskQueue(workflowImpl.getClass()), workflowImpl,
                workflowMethod(workflowImpl.getClass())));
        return this;
    }

    /**
     * Starts a registered workflow.
     *
     * @param workflowId    the business workflow ID
     * @param workflowClass the workflow class (must have been registered)
     * @param input         the workflow input, or {@code null}
     * @return a handle for awaiting and asserting on the run
     */
    public WorkflowHandle start(String workflowId, Class<?> workflowClass, @Nullable Object input) {
        var registration = requireRegistration(workflowClass);
        var instanceId = executor.startWorkflow(
                workflowId,
                registration.workflowType(),
                registration.taskQueue(),
                input,
                registration.workflowImpl(),
                registration.workflowMethod());
        return new WorkflowHandle(store, executor, serializer, workflowId, instanceId);
    }

    /**
     * Delivers a signal to a workflow.
     *
     * @param workflowId the target workflow
     * @param signalName the signal name
     * @param payload    the payload, or {@code null}
     */
    public void deliverSignal(String workflowId, String signalName, @Nullable Object payload) {
        executor.deliverSignal(workflowId, signalName, payload);
    }

    /**
     * Re-invokes every recoverable workflow found in the store, exactly as
     * startup recovery does.
     *
     * @return the number of workflows recovered
     */
    public int recover() {
        return executor.recoverWorkflows(Map.copyOf(registrations));
    }

    /**
     * Fires every timer that is currently due, without waiting for a poller.
     *
     * <p>The engine has no injectable clock, so this drains due timers directly
     * — the deterministic alternative to sleeping until a poller notices.
     *
     * @param batchSize maximum timers to fire
     * @return the number of timers fired
     */
    public int fireDueTimers(int batchSize) {
        var due = store.getDueTimers(java.time.Instant.now(), batchSize);
        for (var timer : due) {
            executor.fireTimer(timer.workflowId(), timer.timerId(), timer.id());
        }
        return due.size();
    }

    /**
     * Starts the background timer poller.
     *
     * @param pollInterval how often to poll — keep this short (≈200ms) in tests
     * @param batchSize    timers per batch
     */
    public void startTimerPoller(Duration pollInterval, int batchSize) {
        executor.startTimerPoller(pollInterval, batchSize);
    }

    /**
     * Starts the background recovery poller over the registered workflows.
     *
     * @param pollInterval how often to poll — keep this short in tests
     */
    public void startRecoveryPoller(Duration pollInterval) {
        executor.startRecoveryPoller(Map.copyOf(registrations), pollInterval);
    }

    /**
     * @return the underlying executor, for assertions the harness does not wrap
     */
    public WorkflowExecutor executor() {
        return executor;
    }

    /**
     * @return the store this harness writes through
     */
    public WorkflowStore store() {
        return store;
    }

    @Override
    public void close() {
        executor.shutdown();
    }

    // ── annotation-driven wiring (mirrors the Spring starter) ──────────────

    private WorkflowRegistration requireRegistration(Class<?> workflowClass) {
        var registration = registrations.get(workflowType(workflowClass));
        if (registration == null) {
            throw new IllegalStateException(
                    "Workflow not registered: " + workflowClass.getName()
                            + " — call registerWorkflow(instance) first");
        }
        return registration;
    }

    private static String workflowType(Class<?> workflowClass) {
        var annotation = workflowClass.getAnnotation(DurableWorkflow.class);
        if (annotation != null && !annotation.name().isEmpty()) {
            return annotation.name();
        }
        return workflowClass.getSimpleName();
    }

    private static String taskQueue(Class<?> workflowClass) {
        var annotation = workflowClass.getAnnotation(DurableWorkflow.class);
        return annotation != null ? annotation.taskQueue() : "default";
    }

    private static Method workflowMethod(Class<?> workflowClass) {
        var candidates = new HashMap<String, Method>();
        for (var method : workflowClass.getMethods()) {
            if (method.isAnnotationPresent(WorkflowMethod.class)) {
                candidates.put(method.getName(), method);
            }
        }
        if (candidates.size() != 1) {
            throw new IllegalStateException(
                    "Expected exactly one @WorkflowMethod on " + workflowClass.getName()
                            + ", found " + candidates.size());
        }
        return List.copyOf(candidates.values()).getFirst();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void wireActivityStubs(Object workflowImpl) {
        for (var field : workflowImpl.getClass().getDeclaredFields()) {
            var stub = field.getAnnotation(ActivityStub.class);
            if (stub == null) {
                continue;
            }
            var activityInterface = field.getType();
            var impl = activityImpls.get(activityInterface);
            if (impl == null) {
                throw new IllegalStateException(
                        "No activity implementation registered for " + activityInterface.getName()
                                + " required by " + workflowImpl.getClass().getName()
                                + " — call registerActivities(...) before registerWorkflow(...)");
            }
            var proxy = proxyFactory.createProxy(
                    (Class) activityInterface,
                    impl,
                    store,
                    lock,
                    messaging,
                    RetryPolicy.fromAnnotation(stub.retryPolicy()),
                    Duration.parse(stub.startToCloseTimeout()),
                    serializer,
                    retryExecutor);
            try {
                field.setAccessible(true);
                field.set(workflowImpl, proxy);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(
                        "Cannot inject activity stub into " + field, e);
            }
        }
    }

    /** Builder for {@link MaestroEngineHarness}. */
    public static final class Builder {
        private final WorkflowStore store;
        private final ObjectMapper objectMapper;
        private @Nullable DistributedLock lock;
        private @Nullable WorkflowMessaging messaging;
        private @Nullable SignalNotifier signalNotifier;
        private String serviceName = "integration-test";
        private String lockKeyPrefix = "maestro:lock:";
        private Duration instanceLockTtl = Duration.ofSeconds(30);

        private Builder(WorkflowStore store, ObjectMapper objectMapper) {
            this.store = store;
            this.objectMapper = objectMapper;
        }

        /** @param lock the distributed lock backend, or {@code null} for none */
        public Builder lock(@Nullable DistributedLock lock) {
            this.lock = lock;
            return this;
        }

        /** @param messaging the messaging backend, or {@code null} for none */
        public Builder messaging(@Nullable WorkflowMessaging messaging) {
            this.messaging = messaging;
            return this;
        }

        /** @param signalNotifier the cross-instance notifier, or {@code null} for none */
        public Builder signalNotifier(@Nullable SignalNotifier signalNotifier) {
            this.signalNotifier = signalNotifier;
            return this;
        }

        /** @param serviceName this node's service name — distinct per simulated node */
        public Builder serviceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }

        /** @param lockKeyPrefix prefix for instance-lock keys */
        public Builder lockKeyPrefix(String lockKeyPrefix) {
            this.lockKeyPrefix = lockKeyPrefix;
            return this;
        }

        /**
         * @param instanceLockTtl instance-lock TTL; renewal happens at TTL/3, so a
         *                        short TTL (1–3s) is how contention and
         *                        owner-death tests avoid real-time waits
         */
        public Builder instanceLockTtl(Duration instanceLockTtl) {
            this.instanceLockTtl = instanceLockTtl;
            return this;
        }

        /** @return the built harness */
        public MaestroEngineHarness build() {
            return new MaestroEngineHarness(this);
        }
    }

    /** Identifier helper for tests that need a unique workflow ID per run. */
    public static String uniqueWorkflowId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
