package io.maestro.core.engine;

import io.maestro.core.annotation.Activity;
import io.maestro.core.retry.RetryExecutor;
import io.maestro.core.retry.RetryPolicy;
import io.maestro.core.spi.DistributedLock;
import io.maestro.core.spi.WorkflowMessaging;
import io.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Proxy;
import java.time.Duration;

/**
 * Creates JDK dynamic proxies for activity interfaces that implement
 * hybrid memoization.
 *
 * <p>Each created proxy wraps an {@link ActivityInvocationHandler} that
 * intercepts method calls to check for stored results (replay) or execute
 * live and persist results.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var factory = new ActivityProxyFactory();
 * var proxy = factory.createProxy(
 *     InventoryActivities.class,
 *     new InventoryActivitiesImpl(),
 *     store, lock, messaging,
 *     RetryPolicy.defaultPolicy(),
 *     Duration.ofSeconds(30),
 *     serializer, retryExecutor
 * );
 * }</pre>
 *
 * <p><b>Thread safety:</b> This class is stateless and thread-safe.
 *
 * @see ActivityInvocationHandler
 */
public final class ActivityProxyFactory {

    /**
     * Creates a memoizing proxy for the given activity interface.
     *
     * @param <T>                 the activity interface type
     * @param activityInterface   the activity interface class (must be an interface)
     * @param activityImpl        the real implementation to delegate live calls to
     * @param store               the workflow store for memoization
     * @param distributedLock     optional distributed lock for dedup optimization
     * @param messaging           optional messaging for lifecycle events
     * @param retryPolicy         retry policy for failed invocations
     * @param startToCloseTimeout timeout used as lock TTL hint
     * @param serializer          Jackson serializer for payloads
     * @param retryExecutor       retry executor for live invocations
     * @return a proxy instance that implements {@code activityInterface}
     * @throws IllegalArgumentException if {@code activityInterface} is not an interface
     */
    public <T> T createProxy(
            Class<T> activityInterface,
            T activityImpl,
            WorkflowStore store,
            @Nullable DistributedLock distributedLock,
            @Nullable WorkflowMessaging messaging,
            RetryPolicy retryPolicy,
            Duration startToCloseTimeout,
            PayloadSerializer serializer,
            RetryExecutor retryExecutor
    ) {
        if (!activityInterface.isInterface()) {
            throw new IllegalArgumentException(
                    "Activity type must be an interface, got: " + activityInterface.getName());
        }

        var activityName = resolveActivityName(activityInterface);

        var handler = new ActivityInvocationHandler(
                activityImpl,
                activityName,
                store,
                distributedLock,
                messaging,
                retryPolicy,
                startToCloseTimeout,
                serializer,
                retryExecutor
        );

        var proxy = Proxy.newProxyInstance(
                activityInterface.getClassLoader(),
                new Class<?>[]{ activityInterface },
                handler
        );

        return activityInterface.cast(proxy);
    }

    /**
     * Resolves the activity group name from the {@link Activity} annotation
     * or falls back to the interface's simple name.
     */
    private String resolveActivityName(Class<?> activityInterface) {
        var annotation = activityInterface.getAnnotation(Activity.class);
        if (annotation != null && !annotation.name().isEmpty()) {
            return annotation.name();
        }
        return activityInterface.getSimpleName();
    }
}
