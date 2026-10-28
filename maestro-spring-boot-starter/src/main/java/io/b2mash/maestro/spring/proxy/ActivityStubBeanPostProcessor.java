package io.b2mash.maestro.spring.proxy;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.engine.ActivityProxyFactory;
import io.b2mash.maestro.core.engine.GatedWorkflowMessaging;
import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.TaskQueueGovernor;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.observe.CompositeEngineObserver;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.spring.config.MaestroProperties;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scans beans annotated with {@link DurableWorkflow} and injects memoizing
 * activity proxies into their {@link ActivityStub}-annotated fields.
 *
 * <p>For each {@code @ActivityStub} field, this processor:
 * <ol>
 *   <li>Resolves the Spring bean that implements the field's activity interface.</li>
 *   <li>Creates a JDK dynamic proxy via {@link ActivityProxyFactory} that wraps
 *       the bean with the memoization interceptor.</li>
 *   <li>Injects the proxy into the field, replacing any existing value.</li>
 * </ol>
 *
 * <p>Dependencies ({@code ActivityProxyFactory}, {@code WorkflowStore}, etc.) are
 * resolved lazily from the {@link ApplicationContext} because {@code BeanPostProcessor}
 * instances are created before regular application beans.
 *
 * <h2>Prototype workflow beans</h2>
 * <p>{@code @DurableWorkflow} beans are prototype-scoped — Maestro creates a new
 * workflow object for every run — so this processor runs once per run, not once
 * per application. Field validation and proxy construction are done once per
 * workflow class — at discovery, through {@link #prepare(Class)}, so a mis-wired
 * stub fails context startup — and cached: every object of that class receives
 * the same proxy per field. Sharing is safe because the memoizing proxy holds no per-run state;
 * it resolves the running workflow from the calling thread's workflow context.
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. Because workflow beans are prototypes, this processor is
 * invoked whenever the engine builds a workflow object — concurrently, from
 * any thread that starts, recovers or retries a run. Dependencies are resolved
 * once under a lock and published through a volatile flag; the per-class
 * injection plans live in a {@link ConcurrentHashMap} (a plan built twice by a
 * race is equivalent, and only one is kept).
 */
public class ActivityStubBeanPostProcessor implements BeanPostProcessor, ApplicationContextAware {

    private static final Logger logger = LoggerFactory.getLogger(ActivityStubBeanPostProcessor.class);

    private @Nullable ApplicationContext applicationContext;

    // Lazily resolved from ApplicationContext on first @DurableWorkflow bean.
    // Written once under the monitor, published by the volatile flag, then read-only.
    private volatile boolean dependenciesResolved;
    private @Nullable ActivityProxyFactory proxyFactory;
    private @Nullable WorkflowStore store;
    private @Nullable DistributedLock distributedLock;
    private @Nullable WorkflowMessaging messaging;
    private @Nullable PayloadSerializer serializer;
    private @Nullable RetryExecutor retryExecutor;
    private @Nullable String lockKeyPrefix;
    private MaestroProperties.@Nullable RetryProperties retryDefaults;
    private EngineObserver observer = EngineObserver.NOOP;
    private TaskQueueGovernor governor = TaskQueueGovernor.UNBOUNDED;

    /** Workflow class → the proxies to inject into its {@code @ActivityStub} fields. */
    private final ConcurrentHashMap<Class<?>, List<StubInjection>> injectionPlans = new ConcurrentHashMap<>();

    /** One {@code @ActivityStub} field and the memoizing proxy injected into it. */
    private record StubInjection(Field field, Object proxy) {}

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public @Nullable Object postProcessAfterInitialization(Object bean, String beanName) {
        var targetClass = ClassUtils.getUserClass(bean.getClass());

        if (!targetClass.isAnnotationPresent(DurableWorkflow.class)) {
            return bean;
        }

        processWorkflowBean(bean, targetClass, beanName);
        return bean;
    }

    /**
     * Validates the {@code @ActivityStub} fields of a workflow class and builds
     * (and caches) their memoizing proxies, without instantiating the workflow.
     *
     * <p>Workflow beans are prototypes, so nothing builds a workflow object at
     * context startup. {@link io.b2mash.maestro.spring.config.WorkflowRegistrar}
     * calls this for every discovered workflow class, so that a mis-wired stub —
     * a non-interface or {@code final} field, a missing activity bean, an
     * unparsable {@code startToCloseTimeout} — fails startup instead of every
     * run. Idempotent; the plan it builds is the one later injected into every
     * workflow object of that class.
     *
     * @param targetClass the user class of a {@code @DurableWorkflow} bean
     * @throws org.springframework.beans.BeansException if a stub's activity bean
     *         cannot be resolved, a stub field is invalid, or a stub's
     *         {@code startToCloseTimeout} is not an ISO-8601 duration
     */
    public void prepare(Class<?> targetClass) {
        injectionPlanFor(targetClass);
    }

    private void ensureDependenciesResolved() {
        if (dependenciesResolved) {
            return;
        }
        synchronized (this) {
            if (!dependenciesResolved) {
                resolveDependencies();
            }
        }
    }

    private void resolveDependencies() {
        var ctx = Objects.requireNonNull(applicationContext,
                "ApplicationContext not set — ActivityStubBeanPostProcessor requires ApplicationContextAware");

        proxyFactory = ctx.getBean(ActivityProxyFactory.class);
        store = ctx.getBean(WorkflowStore.class);
        serializer = ctx.getBean(PayloadSerializer.class);
        retryExecutor = ctx.getBean(RetryExecutor.class);
        var properties = ctx.getBean(MaestroProperties.class);
        lockKeyPrefix = properties.getLock().keyPrefix();
        retryDefaults = properties.getRetry();

        // Optional SPIs — null when not available
        distributedLock = ctx.getBeanProvider(DistributedLock.class).getIfAvailable();
        // Activity proxies are built here, independently of WorkflowExecutor, so
        // ACTIVITY_* lifecycle events need their own gate on maestro.admin.events.enabled
        // — WorkflowExecutor only gates the events it publishes itself and the
        // components it constructs (SignalManager, SagaManager, DefaultWorkflowOperations).
        // GatedWorkflowMessaging is the shared seam both paths use; see its Javadoc.
        var rawMessaging = ctx.getBeanProvider(WorkflowMessaging.class).getIfAvailable();
        messaging = GatedWorkflowMessaging.wrap(rawMessaging, properties.getAdmin().events().enabled());

        // Same composite the executor is handed (design §1.3): every
        // EngineObserver bean in the context, wrapped so a throwing adapter
        // can never corrupt activity execution (coordinator Ruling 4).
        observer = CompositeEngineObserver.of(ctx.getBeanProvider(EngineObserver.class).orderedStream().toList());

        // F8: the executor's governor, so maestro.worker.task-queues[].activity-concurrency
        // bounds live executions of these proxies. Without an executor bean no
        // workflow can run here at all, so unbounded is the only meaningful fallback.
        var executor = ctx.getBeanProvider(WorkflowExecutor.class).getIfAvailable();
        governor = executor != null ? executor.taskQueueGovernor() : TaskQueueGovernor.UNBOUNDED;

        dependenciesResolved = true;
    }

    private List<StubInjection> injectionPlanFor(Class<?> targetClass) {
        var plan = injectionPlans.get(targetClass);
        if (plan == null) {
            ensureDependenciesResolved();
            // Built outside the map: building resolves activity beans, which must not
            // run inside ConcurrentHashMap.computeIfAbsent (re-entrant bean creation).
            var built = buildInjectionPlan(targetClass);
            var raced = injectionPlans.putIfAbsent(targetClass, built);
            plan = raced != null ? raced : built;
        }
        return plan;
    }

    private void processWorkflowBean(Object bean, Class<?> targetClass, String beanName) {
        var plan = injectionPlanFor(targetClass);

        for (var injection : plan) {
            var field = injection.field();
            try {
                field.set(bean, injection.proxy());
            } catch (IllegalAccessException e) {
                throw new BeanInitializationException(
                        "Failed to inject activity proxy into field '%s' on %s"
                                .formatted(field.getName(), targetClass.getName()), e);
            }
        }

        if (!plan.isEmpty()) {
            logger.debug("Injected {} activity proxy(ies) into @DurableWorkflow bean '{}' ({})",
                    plan.size(), beanName, targetClass.getSimpleName());
        }
    }

    /**
     * Validates every {@code @ActivityStub} field of {@code targetClass} and builds
     * its memoizing proxy — once per workflow class, however many workflow objects
     * (one per run) are later created from it.
     */
    private List<StubInjection> buildInjectionPlan(Class<?> targetClass) {
        var ctx = Objects.requireNonNull(applicationContext);
        var plan = new ArrayList<StubInjection>();

        for (var field : getAllFields(targetClass)) {
            var stub = field.getAnnotation(ActivityStub.class);
            if (stub == null) {
                continue;
            }

            var activityInterface = field.getType();

            if (!activityInterface.isInterface()) {
                throw new BeanInitializationException(
                        "@ActivityStub field '%s' on %s must be an interface type, got %s"
                                .formatted(field.getName(), targetClass.getName(),
                                        activityInterface.getName()));
            }

            if (Modifier.isFinal(field.getModifiers())) {
                throw new BeanInitializationException(
                        ("@ActivityStub field '%s' on %s must not be final — "
                                + "Maestro injects the memoizing proxy at runtime via reflection")
                                .formatted(field.getName(), targetClass.getName()));
            }

            // Resolve the Spring bean implementing the activity interface
            Object activityImpl;
            try {
                activityImpl = ctx.getBean(activityInterface);
            } catch (NoSuchBeanDefinitionException e) {
                // Also covers NoUniqueBeanDefinitionException (several candidates).
                throw new BeanInitializationException(
                        "@ActivityStub field '%s' on %s needs exactly one bean implementing %s: %s"
                                .formatted(field.getName(), targetClass.getName(),
                                        activityInterface.getName(), e.getMessage()), e);
            }

            // Build RetryPolicy from the annotation, or from maestro.retry.default-*
            // when the annotation was left at its defaults (see resolveRetryPolicy).
            var retryPolicy = resolveRetryPolicy(stub.retryPolicy(),
                    Objects.requireNonNull(retryDefaults));
            Duration timeout;
            try {
                timeout = Duration.parse(stub.startToCloseTimeout());
            } catch (DateTimeParseException e) {
                throw new BeanInitializationException(
                        "@ActivityStub field '%s' on %s has startToCloseTimeout '%s', which is not an ISO-8601 duration (e.g. PT30S)"
                                .formatted(field.getName(), targetClass.getName(), stub.startToCloseTimeout()), e);
            }

            // Create memoizing proxy
            @SuppressWarnings({"rawtypes", "unchecked"})
            var proxy = Objects.requireNonNull(proxyFactory).createProxy(
                    (Class) activityInterface,
                    activityImpl,
                    store,
                    distributedLock,
                    messaging,
                    retryPolicy,
                    timeout,
                    serializer,
                    retryExecutor,
                    lockKeyPrefix,
                    observer,
                    governor
            );

            field.setAccessible(true);
            plan.add(new StubInjection(field, proxy));
            logger.debug("Created memoizing proxy for @ActivityStub field '{}' ({}) on {}",
                    field.getName(), activityInterface.getSimpleName(), targetClass.getName());
        }

        if (!plan.isEmpty()) {
            logger.info("Prepared @DurableWorkflow {}: {} activity proxy(ies)",
                    targetClass.getName(), plan.size());
        }
        return List.copyOf(plan);
    }

    /**
     * Resolves the {@link RetryPolicy} for an {@code @ActivityStub} field.
     *
     * <p>The {@link io.b2mash.maestro.core.annotation.RetryPolicy} annotation
     * cannot distinguish "the author left this at its defaults" from "the
     * author explicitly chose these exact values" — annotation attributes
     * always carry a value. So the rule is: if every attribute of {@code
     * annotation} equals the annotation's own declared default (see {@link
     * #isAnnotationDefault}), this activity gets the policy built from {@code
     * maestro.retry.default-*} ({@code defaults}) instead of the engine's
     * hardcoded {@link RetryPolicy#defaultPolicy()}. Any deviation — even a
     * single attribute — means the author configured this stub explicitly,
     * and {@link RetryPolicy#fromAnnotation} applies as before, unaffected by
     * {@code maestro.retry.*}.
     *
     * @param annotation the {@code @ActivityStub}'s {@code retryPolicy()} attribute
     * @param defaults   the bound {@code maestro.retry.*} properties
     * @return the retry policy to use for this activity stub
     */
    private static RetryPolicy resolveRetryPolicy(
            io.b2mash.maestro.core.annotation.RetryPolicy annotation,
            MaestroProperties.RetryProperties defaults) {
        if (isAnnotationDefault(annotation)) {
            return new RetryPolicy(
                    defaults.defaultMaxAttempts(),
                    defaults.defaultInitialInterval(),
                    defaults.defaultMaxInterval(),
                    defaults.defaultBackoffMultiplier(),
                    List.of(), List.of());
        }
        return RetryPolicy.fromAnnotation(annotation);
    }

    /**
     * Returns {@code true} if every attribute of {@code a} equals the
     * declared default of {@link io.b2mash.maestro.core.annotation.RetryPolicy}
     * — i.e. the {@code @ActivityStub} did not customize its retry policy.
     */
    private static boolean isAnnotationDefault(io.b2mash.maestro.core.annotation.RetryPolicy a) {
        return a.maxAttempts() == 3
                && "PT1S".equals(a.initialInterval())
                && "PT1M".equals(a.maxInterval())
                && a.backoffMultiplier() == 2.0
                && a.retryableExceptions().length == 0
                && a.nonRetryableExceptions().length == 0;
    }

    /**
     * Collects all declared fields from the class hierarchy, including
     * private fields from superclasses.
     */
    private static List<Field> getAllFields(Class<?> clazz) {
        var fields = new ArrayList<Field>();
        var current = clazz;
        while (current != null && current != Object.class) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }
}
