package io.b2mash.maestro.spring.proxy;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.engine.ActivityProxyFactory;
import io.b2mash.maestro.core.engine.GatedWorkflowMessaging;
import io.b2mash.maestro.core.engine.PayloadSerializer;
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
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

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
 * <h2>Thread Safety</h2>
 * <p>This processor is invoked during the single-threaded bean creation phase.
 * The lazily resolved dependencies are set once and read thereafter.
 */
public class ActivityStubBeanPostProcessor implements BeanPostProcessor, ApplicationContextAware {

    private static final Logger logger = LoggerFactory.getLogger(ActivityStubBeanPostProcessor.class);

    private @Nullable ApplicationContext applicationContext;

    // Lazily resolved from ApplicationContext on first @DurableWorkflow bean.
    // Written once during the single-threaded bean creation phase, then read-only.
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

        ensureDependenciesResolved();
        processWorkflowBean(bean, targetClass, beanName);
        return bean;
    }

    private void ensureDependenciesResolved() {
        if (dependenciesResolved) {
            return;
        }

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

        dependenciesResolved = true;
    }

    @SuppressWarnings("unchecked")
    private void processWorkflowBean(Object bean, Class<?> targetClass, String beanName) {
        var ctx = Objects.requireNonNull(applicationContext);
        var fields = getAllFields(targetClass);
        var injectedCount = 0;

        for (var field : fields) {
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
            var activityImpl = ctx.getBean(activityInterface);

            // Build RetryPolicy from the annotation, or from maestro.retry.default-*
            // when the annotation was left at its defaults (see resolveRetryPolicy).
            var retryPolicy = resolveRetryPolicy(stub.retryPolicy(),
                    Objects.requireNonNull(retryDefaults));
            var timeout = Duration.parse(stub.startToCloseTimeout());

            // Create memoizing proxy
            @SuppressWarnings({"rawtypes", "unchecked"})
            var proxy = proxyFactory.createProxy(
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
                    observer
            );

            // Inject the proxy into the field
            field.setAccessible(true);
            try {
                field.set(bean, proxy);
            } catch (IllegalAccessException e) {
                throw new BeanInitializationException(
                        "Failed to inject activity proxy into field '%s' on %s"
                                .formatted(field.getName(), targetClass.getName()), e);
            }

            injectedCount++;
            logger.debug("Injected memoizing proxy for @ActivityStub field '{}' ({}) on bean '{}'",
                    field.getName(), activityInterface.getSimpleName(), beanName);
        }

        if (injectedCount > 0) {
            logger.info("Processed @DurableWorkflow bean '{}': injected {} activity proxy(ies)",
                    beanName, injectedCount);
        }
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
