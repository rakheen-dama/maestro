package io.b2mash.maestro.test;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.engine.ActivityProxyFactory;
import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory Maestro engine for fast, deterministic workflow tests.
 *
 * <p>Constructs a real {@link WorkflowExecutor} backed by in-memory SPI
 * implementations. No Postgres, Kafka, or Valkey required.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var env = TestWorkflowEnvironment.create();
 * env.registerActivities(PaymentActivities.class, new MockPaymentActivities());
 *
 * var handle = env.startWorkflow(OrderWorkflow.class, new OrderInput("item-1"));
 * handle.signal("payment.result", new PaymentResult(true));
 * var result = handle.getResult(OrderResult.class, Duration.ofSeconds(5));
 *
 * env.shutdown();
 * }</pre>
 *
 * <h2>Time Control</h2>
 * <p>Workflows that call {@code sleep()} create durable timers. Use
 * {@link #advanceTime(Duration)} to fire due timers deterministically
 * without waiting for real time to pass.
 *
 * <h2>Thread Safety</h2>
 * <p>The environment is thread-safe. Workflows run on virtual threads
 * internally, just like in production.
 *
 * @see TestWorkflowHandle
 * @see MaestroTest
 */
public final class TestWorkflowEnvironment {

    private static final Logger logger = LoggerFactory.getLogger(TestWorkflowEnvironment.class);
    private static final String SERVICE_NAME = "maestro-test";

    private final ControllableClock clock;
    private final InMemoryWorkflowStore store;
    private final InMemoryWorkflowMessaging messaging;
    private final InMemoryDistributedLock lock;
    private final InMemorySignalNotifier signalNotifier;
    private final PayloadSerializer serializer;
    private final WorkflowExecutor executor;
    private final ActivityProxyFactory proxyFactory;
    private final RetryExecutor retryExecutor;

    // activityInterface → activityImpl
    private final Map<Class<?>, Object> activityImpls = new LinkedHashMap<>();
    private final AtomicInteger workflowCounter = new AtomicInteger(0);

    private TestWorkflowEnvironment(ObjectMapper objectMapper) {
        this.clock = new ControllableClock();
        this.store = new InMemoryWorkflowStore();
        this.messaging = new InMemoryWorkflowMessaging();
        this.lock = new InMemoryDistributedLock();
        this.signalNotifier = new InMemorySignalNotifier();
        this.serializer = new PayloadSerializer(objectMapper);
        this.proxyFactory = new ActivityProxyFactory();
        this.retryExecutor = new RetryExecutor();
        this.executor = new WorkflowExecutor(
                store, lock, messaging, signalNotifier, serializer, SERVICE_NAME);
    }

    // ── Factory methods ──────────────────────────────────────────────────

    /**
     * Creates a test environment with a default Jackson ObjectMapper.
     */
    public static TestWorkflowEnvironment create() {
        return new TestWorkflowEnvironment(JsonMapper.builder().build());
    }

    /**
     * Creates a test environment with a custom ObjectMapper.
     *
     * @param objectMapper the Jackson 3 ObjectMapper to use for serialization
     */
    public static TestWorkflowEnvironment create(ObjectMapper objectMapper) {
        return new TestWorkflowEnvironment(objectMapper);
    }

    // ── Activity registration ────────────────────────────────────────────

    /**
     * Registers an activity implementation by its interface type.
     *
     * @param activityInterface the activity interface (e.g., {@code PaymentActivities.class})
     * @param activityImpl      the implementation (real or mock)
     * @param <T>               the activity interface type
     */
    public <T> void registerActivities(Class<T> activityInterface, T activityImpl) {
        if (!activityInterface.isInterface()) {
            throw new IllegalArgumentException(
                    "First argument must be an interface, got: " + activityInterface.getName());
        }
        activityImpls.put(activityInterface, activityImpl);
    }

    /**
     * Registers activity implementations, auto-detecting their interfaces.
     *
     * <p>Scans each bean's interfaces for the {@link Activity @Activity}
     * annotation. If exactly one annotated interface is found, it is used.
     * If none are found, the first implemented interface is used.
     *
     * @param activityBeans one or more activity implementation instances
     */
    public void registerActivities(Object... activityBeans) {
        for (var bean : activityBeans) {
            var iface = detectActivityInterface(bean);
            activityImpls.put(iface, bean);
        }
    }

    // ── Workflow start ───────────────────────────────────────────────────

    /**
     * Starts a workflow with an auto-generated workflow ID.
     *
     * @param workflowClass the workflow class (annotated with {@code @DurableWorkflow})
     * @param input         the workflow input, or {@code null}
     * @return a handle to the running workflow
     */
    public TestWorkflowHandle startWorkflow(Class<?> workflowClass, @Nullable Object input) {
        var annotation = requireDurableWorkflowAnnotation(workflowClass);
        var workflowType = resolveWorkflowType(annotation, workflowClass);
        var workflowId = workflowType + "-" + workflowCounter.incrementAndGet();
        return startWorkflow(workflowId, workflowClass, input);
    }

    /**
     * Starts a workflow with a specific workflow ID.
     *
     * @param workflowId    the business workflow ID
     * @param workflowClass the workflow class (annotated with {@code @DurableWorkflow})
     * @param input         the workflow input, or {@code null}
     * @return a handle to the running workflow
     */
    public TestWorkflowHandle startWorkflow(String workflowId, Class<?> workflowClass,
                                            @Nullable Object input) {
        var annotation = requireDurableWorkflowAnnotation(workflowClass);
        var workflowType = resolveWorkflowType(annotation, workflowClass);
        var taskQueue = annotation.taskQueue();
        var workflowMethod = findWorkflowMethod(workflowClass);

        // Instantiate workflow and wire activity stubs
        var workflowImpl = instantiate(workflowClass);
        wireActivityStubs(workflowImpl);

        // Register query methods
        executor.registerQueries(workflowType, workflowClass);

        // Start
        var instanceId = executor.startWorkflow(
                workflowId, workflowType, taskQueue, input, workflowImpl, workflowMethod);

        logger.debug("Test: started workflow '{}' (type={}, instanceId={})",
                workflowId, workflowType, instanceId);

        return new TestWorkflowHandle(workflowId, instanceId, executor, store, serializer);
    }

    // ── Time control ─────────────────────────────────────────────────────

    /**
     * Advances the test clock and fires any timers that become due.
     *
     * <p>Workflows that called {@code workflow.sleep(duration)} create
     * durable timers. This method advances the clock and fires all
     * timers whose {@code fireAt <= clock.now()}, then yields briefly
     * to let virtual threads process.
     *
     * <p>The method loops to handle cascading timers (timer A fires
     * → workflow creates timer B → B is also due).
     *
     * @param duration the amount to advance (must be non-negative)
     */
    public void advanceTime(Duration duration) {
        clock.advance(duration);
        fireAllDueTimers();
    }

    /**
     * Returns the current test clock time.
     */
    public Instant currentTime() {
        return clock.now();
    }

    // ── Signal pre-delivery ──────────────────────────────────────────────

    /**
     * Stores a signal before the workflow starts (self-recovery test).
     *
     * <p>The signal is persisted with a {@code null} workflow instance ID.
     * When the workflow starts, it adopts all orphaned signals matching
     * its workflow ID.
     *
     * @param workflowId  the target workflow's business ID
     * @param signalName  the signal name
     * @param payload     the signal payload, or {@code null}
     */
    public void preDeliverSignal(String workflowId, String signalName, @Nullable Object payload) {
        var jsonPayload = payload != null ? serializer.serialize(payload) : null;
        var signal = new WorkflowSignal(
                UUID.randomUUID(),
                null,
                workflowId,
                signalName,
                jsonPayload,
                false,
                Instant.now()
        );
        store.saveSignal(signal);
    }

    // ── Accessors for advanced assertions ────────────────────────────────

    /**
     * Returns the underlying in-memory store for advanced assertions.
     */
    public InMemoryWorkflowStore getStore() {
        return store;
    }

    /**
     * Returns the underlying in-memory messaging for lifecycle event assertions.
     */
    public InMemoryWorkflowMessaging getMessaging() {
        return messaging;
    }

    /**
     * Returns the controllable clock.
     */
    public ControllableClock getClock() {
        return clock;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    /**
     * Shuts down the executor, waiting for in-flight workflows to complete.
     */
    public void shutdown() {
        executor.shutdown();
    }

    // ── Internal: timer firing ───────────────────────────────────────────

    private void fireAllDueTimers() {
        List<WorkflowTimer> due;
        int safetyLimit = 1000;
        int iterations = 0;

        while (!(due = store.getDueTimers(clock.now(), 100)).isEmpty()) {
            for (var timer : due) {
                executor.fireTimer(timer.workflowId(), timer.timerId(), timer.id());
            }
            // Brief yield to let virtual threads process the unpark
            Thread.yield();

            if (++iterations > safetyLimit) {
                throw new IllegalStateException(
                        "advanceTime fired over %d timer batches — possible infinite timer loop"
                                .formatted(safetyLimit));
            }
        }
    }

    // ── Internal: annotation introspection ───────────────────────────────

    private static DurableWorkflow requireDurableWorkflowAnnotation(Class<?> workflowClass) {
        var annotation = workflowClass.getAnnotation(DurableWorkflow.class);
        if (annotation == null) {
            throw new IllegalArgumentException(
                    "Class %s is not annotated with @DurableWorkflow".formatted(workflowClass.getName()));
        }
        return annotation;
    }

    private static String resolveWorkflowType(DurableWorkflow annotation, Class<?> workflowClass) {
        return annotation.name().isEmpty() ? workflowClass.getSimpleName() : annotation.name();
    }

    private static Method findWorkflowMethod(Class<?> workflowClass) {
        Method found = null;
        for (var method : workflowClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(WorkflowMethod.class)) {
                if (found != null) {
                    throw new IllegalArgumentException(
                            "Class %s has multiple @WorkflowMethod annotations. Exactly one is required."
                                    .formatted(workflowClass.getName()));
                }
                found = method;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException(
                    "Class %s has no @WorkflowMethod annotated method".formatted(workflowClass.getName()));
        }
        return found;
    }

    private static Object instantiate(Class<?> workflowClass) {
        try {
            var constructor = workflowClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(
                    "Class %s must have a no-arg constructor".formatted(workflowClass.getName()), e);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(
                    "Failed to instantiate workflow class " + workflowClass.getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private void wireActivityStubs(Object workflowImpl) {
        for (var field : getAllFields(workflowImpl.getClass())) {
            var stub = field.getAnnotation(ActivityStub.class);
            if (stub == null) {
                continue;
            }

            var activityInterface = field.getType();
            if (!activityInterface.isInterface()) {
                throw new IllegalArgumentException(
                        "@ActivityStub field '%s' in %s must be an interface type, got: %s"
                                .formatted(field.getName(), workflowImpl.getClass().getSimpleName(),
                                        activityInterface.getName()));
            }

            var activityImpl = activityImpls.get(activityInterface);
            if (activityImpl == null) {
                throw new IllegalStateException(
                        "No activity implementation registered for %s. "
                                .formatted(activityInterface.getName())
                                + "Call registerActivities(%s.class, impl) before startWorkflow()."
                                        .formatted(activityInterface.getSimpleName()));
            }

            var retryPolicy = RetryPolicy.fromAnnotation(stub.retryPolicy());
            var timeout = Duration.parse(stub.startToCloseTimeout());

            @SuppressWarnings("rawtypes")
            var proxy = proxyFactory.createProxy(
                    (Class) activityInterface,
                    activityImpl,
                    store,
                    lock,
                    messaging,
                    retryPolicy,
                    timeout,
                    serializer,
                    retryExecutor
            );

            try {
                field.setAccessible(true);
                field.set(workflowImpl, proxy);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(
                        "Failed to inject activity proxy into field '%s'".formatted(field.getName()), e);
            }
        }
    }

    private static Class<?> detectActivityInterface(Object bean) {
        // Look for @Activity-annotated interface first
        for (var iface : bean.getClass().getInterfaces()) {
            if (iface.isAnnotationPresent(Activity.class)) {
                return iface;
            }
        }
        // Fall back to first interface
        var interfaces = bean.getClass().getInterfaces();
        if (interfaces.length == 0) {
            throw new IllegalArgumentException(
                    "Activity bean %s does not implement any interface".formatted(bean.getClass().getName()));
        }
        return interfaces[0];
    }

    private static List<Field> getAllFields(Class<?> clazz) {
        var fields = new java.util.ArrayList<Field>();
        var current = clazz;
        while (current != null && current != Object.class) {
            fields.addAll(List.of(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }
}
