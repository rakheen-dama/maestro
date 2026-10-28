package io.b2mash.maestro.integration.support;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.engine.ActivityProxyFactory;
import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.TaskQueueLimits;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.engine.WorkflowRegistration;
import io.b2mash.maestro.core.observe.EngineObserver;
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
import java.util.function.Supplier;

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
    private final EngineObserver observer;
    private final String lockKeyPrefix;
    private final Map<Class<?>, Object> activityImpls = new ConcurrentHashMap<>();
    private final Map<String, WorkflowRegistration> registrations = new ConcurrentHashMap<>();

    /** Matches {@code WorkflowExecutor}'s own default — package-private there,
     * so this harness (a different module) states it explicitly rather than
     * referencing it. */
    private static final Duration DEFAULT_WAKE_RECHECK_INTERVAL = Duration.ofSeconds(30);

    private MaestroEngineHarness(Builder builder) {
        this.store = builder.store;
        this.serializer = new PayloadSerializer(builder.objectMapper);
        this.lock = builder.lock;
        this.messaging = builder.messaging;
        this.observer = builder.observer != null ? builder.observer : EngineObserver.NOOP;
        this.lockKeyPrefix = builder.lockKeyPrefix;
        // The engine's defaults for everything the builder leaves unset —
        // lifecycle events on, 30s shutdown timeout, 30s wake re-check, no
        // observer, unbounded task queues.
        this.executor = new WorkflowExecutor(
                builder.store,
                builder.lock,
                builder.messaging,
                builder.signalNotifier,
                this.serializer,
                builder.serviceName,
                builder.lockKeyPrefix,
                builder.instanceLockTtl,
                true,
                Duration.ofSeconds(30),
                builder.wakeRecheckInterval != null
                        ? builder.wakeRecheckInterval : DEFAULT_WAKE_RECHECK_INTERVAL,
                this.observer,
                builder.taskQueueLimits);
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
     * Registers a workflow class, built reflectively through its no-arg
     * constructor once per run — on every start, recovery and retry — exactly
     * as the Spring starter builds a prototype bean per run. Registration is
     * what makes a workflow recoverable — the same registrations are handed to
     * {@link #recover()} and the recovery poller.
     *
     * @param workflowClass the workflow class; must have an accessible-or-not
     *                      no-arg constructor
     * @return this harness
     */
    public MaestroEngineHarness registerWorkflow(Class<?> workflowClass) {
        return registerWorkflow(workflowClass, () -> instantiate(workflowClass));
    }

    /**
     * Registers a workflow class whose per-run objects come from
     * {@code factory}. The harness wires {@link ActivityStub} fields on every
     * object the factory returns, each time it returns one.
     *
     * <p>Use this overload for fixtures with constructor arguments, or to
     * hand back a pre-built object a test holds a reference to (to assert on
     * counters or latches it touches). A factory that returns the same object
     * on every call deliberately reintroduces the shared-object behaviour —
     * acceptable for a single-run fixture, wrong for anything that asserts
     * per-run isolation.
     *
     * @param workflowClass the workflow class (supplies type, task queue and
     *                      workflow method)
     * @param factory       builds the workflow object for each run; must
     *                      return an instance of {@code workflowClass}
     * @return this harness
     */
    public MaestroEngineHarness registerWorkflow(Class<?> workflowClass, Supplier<?> factory) {
        var type = workflowType(workflowClass);
        Supplier<Object> wiringFactory = () -> {
            Object workflowImpl = workflowClass.cast(factory.get());
            wireActivityStubs(workflowImpl);
            return workflowImpl;
        };
        registrations.put(type, new WorkflowRegistration(
                type, taskQueue(workflowClass), wiringFactory, workflowMethod(workflowClass)));
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
                registration.workflowFactory(),
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
     * @return the {@link EngineObserver} this node was built with — {@link
     *         EngineObserver#NOOP} if none was configured
     */
    public EngineObserver observer() {
        return observer;
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
                            + " — call registerWorkflow(" + workflowClass.getSimpleName()
                            + ".class) first");
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

    private static Object instantiate(Class<?> workflowClass) {
        try {
            var constructor = workflowClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Cannot instantiate " + workflowClass.getName()
                            + " through a no-arg constructor — use registerWorkflow(Class, Supplier)", e);
        }
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
                                + " — call registerActivities(...) before starting or recovering it");
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
                    retryExecutor,
                    lockKeyPrefix,
                    observer,
                    executor.taskQueueGovernor());
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
        private @Nullable Duration wakeRecheckInterval;
        private @Nullable EngineObserver observer;
        private Map<String, TaskQueueLimits> taskQueueLimits = Map.of();

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

        /**
         * @param wakeRecheckInterval how often a parked {@code awaitSignal()} or
         *                            {@code sleep()} re-reads the store for a wake
         *                            it may have missed
         *                            ({@code maestro.signal.wake-recheck-interval});
         *                            {@code null} keeps the engine's 30s default.
         *                            A short interval (≈200–500ms) is how
         *                            cross-node wake tests avoid real-time waits.
         */
        public Builder wakeRecheckInterval(@Nullable Duration wakeRecheckInterval) {
            this.wakeRecheckInterval = wakeRecheckInterval;
            return this;
        }

        /**
         * @param observer engine observation seam wired into both the executor
         *                 and every activity proxy this harness builds; {@code
         *                 null} (the default) keeps {@link EngineObserver#NOOP}
         */
        public Builder observer(@Nullable EngineObserver observer) {
            this.observer = observer;
            return this;
        }

        /**
         * @param taskQueueLimits per-task-queue concurrency limits
         *                        ({@code maestro.worker.task-queues}) enforced by
         *                        the executor and every activity proxy this
         *                        harness builds; the default (empty) leaves
         *                        every queue unbounded
         */
        public Builder taskQueueLimits(Map<String, TaskQueueLimits> taskQueueLimits) {
            this.taskQueueLimits = Map.copyOf(taskQueueLimits);
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
