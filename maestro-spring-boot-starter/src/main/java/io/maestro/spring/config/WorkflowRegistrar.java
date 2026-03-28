package io.maestro.spring.config;

import io.maestro.core.annotation.DurableWorkflow;
import io.maestro.core.annotation.WorkflowMethod;
import io.maestro.core.engine.WorkflowExecutor;
import io.maestro.core.engine.WorkflowRegistration;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovers all {@link DurableWorkflow}-annotated beans in the Spring context,
 * builds {@link WorkflowRegistration} metadata, and registers query methods
 * with the {@link WorkflowExecutor}.
 *
 * <p>Runs during the {@link SmartInitializingSingleton} phase — after all
 * singleton beans are created (including activity proxy injection) but
 * before {@link org.springframework.boot.ApplicationRunner} fires.
 *
 * <h2>Thread Safety</h2>
 * <p>Registration happens once during the single-threaded initialization phase.
 * The resulting maps are safely published via {@code ConcurrentHashMap} and
 * exposed as unmodifiable views.
 */
public class WorkflowRegistrar implements SmartInitializingSingleton, ApplicationContextAware {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowRegistrar.class);

    private final WorkflowExecutor executor;
    private @Nullable ApplicationContext applicationContext;

    /** Workflow type name → registration. */
    private final ConcurrentHashMap<String, WorkflowRegistration> registrations = new ConcurrentHashMap<>();

    /** Workflow class → registration (for MaestroClient lookup by class). */
    private final ConcurrentHashMap<Class<?>, WorkflowRegistration> registrationsByClass = new ConcurrentHashMap<>();

    public WorkflowRegistrar(WorkflowExecutor executor) {
        this.executor = executor;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterSingletonsInstantiated() {
        var ctx = Objects.requireNonNull(applicationContext);
        var workflowBeans = ctx.getBeansWithAnnotation(DurableWorkflow.class);

        for (var entry : workflowBeans.entrySet()) {
            var beanName = entry.getKey();
            var bean = entry.getValue();
            var targetClass = ClassUtils.getUserClass(bean.getClass());
            registerWorkflow(beanName, bean, targetClass);
        }

        logger.info("Registered {} workflow type(s): {}", registrations.size(),
                registrations.keySet());
    }

    private void registerWorkflow(String beanName, Object bean, Class<?> targetClass) {
        var annotation = targetClass.getAnnotation(DurableWorkflow.class);
        if (annotation == null) {
            return;
        }

        var workflowType = annotation.name().isEmpty()
                ? targetClass.getSimpleName() : annotation.name();
        var taskQueue = annotation.taskQueue();

        var workflowMethod = findWorkflowMethod(targetClass, workflowType);

        var registration = new WorkflowRegistration(
                workflowType, taskQueue, bean, workflowMethod
        );

        var existing = registrations.putIfAbsent(workflowType, registration);
        if (existing != null) {
            throw new BeanInitializationException(
                    "Duplicate @DurableWorkflow type '%s': bean '%s' conflicts with existing registration"
                            .formatted(workflowType, beanName));
        }
        registrationsByClass.put(targetClass, registration);

        // Register @QueryMethod handlers with the executor.
        // Done after both maps are populated to ensure consistent state on error.
        try {
            executor.registerQueries(workflowType, targetClass);
        } catch (RuntimeException e) {
            registrations.remove(workflowType);
            registrationsByClass.remove(targetClass);
            throw e;
        }

        logger.debug("Registered workflow type '{}' (bean='{}', taskQueue='{}', method='{}')",
                workflowType, beanName, taskQueue, workflowMethod.getName());
    }

    private static Method findWorkflowMethod(Class<?> clazz, String workflowType) {
        Method found = null;

        for (var method : clazz.getMethods()) {
            if (method.isAnnotationPresent(WorkflowMethod.class)) {
                if (found != null) {
                    throw new BeanInitializationException(
                            "@DurableWorkflow '%s' (%s) has multiple @WorkflowMethod annotations: '%s' and '%s'"
                                    .formatted(workflowType, clazz.getName(),
                                            found.getName(), method.getName()));
                }
                found = method;
            }
        }

        if (found == null) {
            throw new BeanInitializationException(
                    "@DurableWorkflow '%s' (%s) has no @WorkflowMethod"
                            .formatted(workflowType, clazz.getName()));
        }

        return found;
    }

    // ── Public accessors ────────────────────────────────────────────

    /**
     * Returns all workflow registrations by type name.
     * Used by {@link StartupRecoveryRunner} for crash recovery.
     */
    public Map<String, WorkflowRegistration> getRegistrations() {
        return Collections.unmodifiableMap(registrations);
    }

    /**
     * Looks up a registration by workflow class.
     * Used by {@link io.maestro.spring.client.MaestroClient}.
     *
     * @param workflowClass the workflow class (unwrapped from CGLIB proxy)
     * @return the registration
     * @throws IllegalArgumentException if not registered
     */
    public WorkflowRegistration getRegistration(Class<?> workflowClass) {
        var targetClass = ClassUtils.getUserClass(workflowClass);
        var reg = registrationsByClass.get(targetClass);
        if (reg == null) {
            throw new IllegalArgumentException(
                    "No @DurableWorkflow registration for class " + targetClass.getName());
        }
        return reg;
    }

    /**
     * Looks up a registration by workflow type name.
     *
     * @param workflowType the workflow type name
     * @return the registration
     * @throws IllegalArgumentException if not registered
     */
    public WorkflowRegistration getRegistration(String workflowType) {
        var reg = registrations.get(workflowType);
        if (reg == null) {
            throw new IllegalArgumentException(
                    "No @DurableWorkflow registration for type '" + workflowType + "'");
        }
        return reg;
    }
}
