package io.maestro.core.engine;

import io.maestro.core.annotation.Activity;
import io.maestro.core.annotation.Compensate;
import io.maestro.core.retry.RetryExecutor;
import io.maestro.core.retry.RetryPolicy;
import io.maestro.core.spi.DistributedLock;
import io.maestro.core.spi.WorkflowMessaging;
import io.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

        validateCompensateAnnotations(activityInterface);

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

    /**
     * Validates all {@link Compensate} annotations on the activity interface
     * at proxy creation time (fail-fast).
     *
     * <p>Checks that:
     * <ol>
     *   <li>The referenced compensation method exists on the interface.</li>
     *   <li>The compensation method's parameters are compatible with
     *       the activity's return type or parameters.</li>
     * </ol>
     *
     * @param activityInterface the activity interface to validate
     * @throws IllegalArgumentException if any {@code @Compensate} annotation is misconfigured
     */
    private void validateCompensateAnnotations(Class<?> activityInterface) {
        for (var method : activityInterface.getMethods()) {
            var compensate = method.getAnnotation(Compensate.class);
            if (compensate == null) {
                continue;
            }

            var compensationName = compensate.value();
            var compensationMethods = findMethods(activityInterface, compensationName);

            if (compensationMethods.isEmpty()) {
                throw new IllegalArgumentException(
                        "@Compensate on %s.%s references '%s', but no method with that name exists on %s"
                                .formatted(activityInterface.getSimpleName(), method.getName(),
                                        compensationName, activityInterface.getSimpleName()));
            }

            if (compensationMethods.size() > 1) {
                throw new IllegalArgumentException(
                        "@Compensate on %s.%s references '%s', but %s has %d overloads of '%s'. "
                                .formatted(activityInterface.getSimpleName(), method.getName(),
                                        compensationName, activityInterface.getSimpleName(),
                                        compensationMethods.size(), compensationName)
                                + "Compensation methods must have a unique name — rename one of the overloads.");
            }

            var compensationMethod = compensationMethods.getFirst();

            // Validate argument compatibility
            var compensationParams = compensationMethod.getParameterTypes();
            if (compensationParams.length == 0) {
                continue; // No-arg compensation is always valid
            }

            var returnType = method.getReturnType();
            // Pattern 1: single param matching return type
            if (compensationParams.length == 1
                    && returnType != void.class
                    && returnType != Void.class
                    && compensationParams[0].isAssignableFrom(returnType)) {
                continue;
            }

            // Pattern 2: same params as the activity
            if (Arrays.equals(compensationParams, method.getParameterTypes())) {
                continue;
            }

            throw new IllegalArgumentException(
                    "@Compensate on %s.%s references '%s', but the compensation method's parameters "
                            .formatted(activityInterface.getSimpleName(), method.getName(), compensationName)
                            + "are incompatible. Expected: no params, a single param matching the return type (%s), "
                                    .formatted(returnType.getSimpleName())
                            + "or the same parameters as the activity method %s"
                                    .formatted(Arrays.toString(method.getParameterTypes())));
        }
    }

    private static List<Method> findMethods(Class<?> iface, String name) {
        var matches = new ArrayList<Method>();
        for (var m : iface.getMethods()) {
            if (m.getName().equals(name)) {
                matches.add(m);
            }
        }
        return matches;
    }
}
