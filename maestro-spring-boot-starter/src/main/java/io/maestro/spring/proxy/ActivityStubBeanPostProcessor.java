package io.maestro.spring.proxy;

import io.maestro.core.annotation.ActivityStub;
import io.maestro.core.annotation.DurableWorkflow;
import io.maestro.core.engine.ActivityProxyFactory;
import io.maestro.core.engine.PayloadSerializer;
import io.maestro.core.retry.RetryExecutor;
import io.maestro.core.retry.RetryPolicy;
import io.maestro.core.spi.DistributedLock;
import io.maestro.core.spi.WorkflowMessaging;
import io.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Field;
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

        // Optional SPIs — null when not available
        distributedLock = ctx.getBeanProvider(DistributedLock.class).getIfAvailable();
        messaging = ctx.getBeanProvider(WorkflowMessaging.class).getIfAvailable();

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

            // Resolve the Spring bean implementing the activity interface
            var activityImpl = ctx.getBean(activityInterface);

            // Build RetryPolicy from the annotation
            var retryPolicy = RetryPolicy.fromAnnotation(stub.retryPolicy());
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
                    retryExecutor
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
