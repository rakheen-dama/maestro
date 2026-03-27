package io.maestro.core.engine;

import io.maestro.core.annotation.QueryMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry that maps {@code (workflowType, queryName)} to the corresponding
 * {@link QueryMethod}-annotated method.
 *
 * <p>During workflow registration, {@link #register(String, Class)} scans the
 * workflow class for query methods and validates their signatures. At query
 * time, {@link #getQueryMethod(String, String)} performs a fast lookup.
 *
 * <h2>Thread Safety</h2>
 * <p>All public methods are thread-safe. Registration and lookup can occur
 * concurrently from multiple threads.
 *
 * @see QueryMethod
 * @see WorkflowExecutor#queryWorkflow
 */
final class QueryRegistry {

    private static final Logger logger = LoggerFactory.getLogger(QueryRegistry.class);

    /** Map of workflowType → (queryName → Method). Inner map is unmodifiable after registration. */
    private final ConcurrentHashMap<String, Map<String, Method>> registry = new ConcurrentHashMap<>();

    /**
     * Scans a workflow class for {@link QueryMethod} annotations and registers them.
     *
     * <p>Validates each annotated method:
     * <ul>
     *   <li>Must be {@code public} and non-static</li>
     *   <li>Must have a non-void return type</li>
     *   <li>Must accept 0 or 1 parameter</li>
     *   <li>Query names must be unique within a workflow type</li>
     * </ul>
     *
     * @param workflowType  the workflow type name
     * @param workflowClass the workflow implementation class to scan
     * @throws IllegalArgumentException if any annotated method violates constraints
     */
    void register(String workflowType, Class<?> workflowClass) {
        var queryMethods = new HashMap<String, Method>();

        for (var method : workflowClass.getMethods()) {
            var annotation = method.getAnnotation(QueryMethod.class);
            if (annotation == null) {
                continue;
            }

            validateQueryMethod(method, workflowClass);

            var queryName = annotation.name().isEmpty() ? method.getName() : annotation.name();

            var existing = queryMethods.put(queryName, method);
            if (existing != null) {
                throw new IllegalArgumentException(
                        "Duplicate @QueryMethod name '%s' on workflow class %s: methods '%s' and '%s'"
                                .formatted(queryName, workflowClass.getName(),
                                        existing.getName(), method.getName()));
            }
        }

        if (!queryMethods.isEmpty()) {
            var existing = registry.putIfAbsent(workflowType, Collections.unmodifiableMap(queryMethods));
            if (existing != null) {
                throw new IllegalArgumentException(
                        "Query methods already registered for workflow type '%s'"
                                .formatted(workflowType));
            }
            logger.debug("Registered {} query method(s) for workflow type '{}': {}",
                    queryMethods.size(), workflowType, queryMethods.keySet());
        }
    }

    /**
     * Looks up a query method by workflow type and query name.
     *
     * @param workflowType the workflow type name
     * @param queryName    the query name
     * @return the query method, or empty if not found
     */
    Optional<Method> getQueryMethod(String workflowType, String queryName) {
        var methods = registry.get(workflowType);
        if (methods == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(methods.get(queryName));
    }

    /**
     * Returns all registered query names for a workflow type.
     *
     * @param workflowType the workflow type name
     * @return the set of query names, or an empty set if none
     */
    Set<String> getQueryNames(String workflowType) {
        var methods = registry.get(workflowType);
        return methods != null ? methods.keySet() : Set.of();
    }

    private void validateQueryMethod(Method method, Class<?> workflowClass) {
        var mods = method.getModifiers();

        if (!Modifier.isPublic(mods)) {
            throw new IllegalArgumentException(
                    "@QueryMethod '%s' on %s must be public"
                            .formatted(method.getName(), workflowClass.getName()));
        }

        if (Modifier.isStatic(mods)) {
            throw new IllegalArgumentException(
                    "@QueryMethod '%s' on %s must not be static"
                            .formatted(method.getName(), workflowClass.getName()));
        }

        if (method.getReturnType() == void.class || method.getReturnType() == Void.class) {
            throw new IllegalArgumentException(
                    "@QueryMethod '%s' on %s must have a non-void return type"
                            .formatted(method.getName(), workflowClass.getName()));
        }

        if (method.getParameterCount() > 1) {
            throw new IllegalArgumentException(
                    "@QueryMethod '%s' on %s must accept 0 or 1 parameter, found %d"
                            .formatted(method.getName(), workflowClass.getName(),
                                    method.getParameterCount()));
        }
    }
}
