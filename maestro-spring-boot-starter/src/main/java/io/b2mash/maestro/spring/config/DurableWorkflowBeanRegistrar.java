package io.b2mash.maestro.spring.config;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import java.util.List;

/**
 * Registers classes annotated with {@link DurableWorkflow} as singleton Spring
 * bean definitions by scanning the classpath, so that users do not have to add
 * {@code @Component} to workflow classes.
 *
 * <p>{@code @DurableWorkflow} is a plain maestro-core annotation (never a Spring
 * stereotype — maestro-core must not depend on Spring), so Spring's component
 * scan does not pick it up on its own. Without this registrar, workflow classes
 * are invisible to {@link WorkflowRegistrar} and
 * {@code MaestroClient.newWorkflow(...)} fails with
 * "No {@code @DurableWorkflow} registration".
 *
 * <h2>Scanned packages</h2>
 * <ol>
 *   <li>The explicit {@code maestro.workflow-packages} property, when set
 *       (comma-separated list; useful in tests or non-Boot contexts).</li>
 *   <li>Otherwise the application's auto-configuration packages — the package
 *       of the {@code @SpringBootApplication} class — via
 *       {@link AutoConfigurationPackages}.</li>
 *   <li>If neither is available, scanning is skipped (no-op).</li>
 * </ol>
 *
 * <p>Registration is idempotent: a workflow class that is already a bean
 * (annotated {@code @Component}, registered via {@code @Bean}, etc.) is
 * skipped, so both styles may be mixed freely.
 *
 * <p>Registered definitions are ordinary singleton bean definitions, so
 * scanned workflow beans pass through the regular bean lifecycle — including
 * {@link io.b2mash.maestro.spring.proxy.ActivityStubBeanPostProcessor}, which
 * injects memoizing activity proxies into {@code @ActivityStub} fields.
 *
 * <h2>Thread Safety</h2>
 * <p>Invoked exactly once by the container during the single-threaded bean
 * definition post-processing phase; holds no mutable state beyond the
 * constructor-injected {@link Environment}.
 *
 * @see WorkflowRegistrar
 * @see MaestroAutoConfiguration
 */
public class DurableWorkflowBeanRegistrar implements BeanDefinitionRegistryPostProcessor {

    private static final Logger logger = LoggerFactory.getLogger(DurableWorkflowBeanRegistrar.class);

    /** Explicit override for the packages to scan for {@code @DurableWorkflow} classes. */
    static final String WORKFLOW_PACKAGES_PROPERTY = "maestro.workflow-packages";

    private final Environment environment;

    public DurableWorkflowBeanRegistrar(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        if (!(registry instanceof ConfigurableListableBeanFactory beanFactory)) {
            logger.warn("Bean definition registry is not a ConfigurableListableBeanFactory — "
                    + "skipping @DurableWorkflow classpath scanning");
            return;
        }

        var packages = resolveBasePackages(beanFactory);
        if (packages.isEmpty()) {
            logger.debug("No base packages to scan for @DurableWorkflow classes — set "
                    + "'{}' or use @SpringBootApplication", WORKFLOW_PACKAGES_PROPERTY);
            return;
        }

        var scanner = new ClassPathScanningCandidateComponentProvider(false, environment);
        scanner.addIncludeFilter(new AnnotationTypeFilter(DurableWorkflow.class));
        var nameGenerator = AnnotationBeanNameGenerator.INSTANCE;
        var registered = 0;

        for (var basePackage : packages) {
            for (var candidate : scanner.findCandidateComponents(basePackage)) {
                var className = candidate.getBeanClassName();
                if (className == null || isAlreadyRegistered(beanFactory, className)) {
                    continue;
                }
                var beanName = nameGenerator.generateBeanName(candidate, registry);
                registry.registerBeanDefinition(beanName, candidate);
                registered++;
                logger.debug("Registered @DurableWorkflow class '{}' as bean '{}'",
                        className, beanName);
            }
        }

        if (registered > 0) {
            logger.info("Registered {} @DurableWorkflow class(es) from packages {}",
                    registered, packages);
        }
    }

    /**
     * Returns {@code true} when a bean of the given workflow class is already
     * defined (e.g. via {@code @Component} or a {@code @Bean} method), so the
     * scan must not register a duplicate.
     */
    private boolean isAlreadyRegistered(ConfigurableListableBeanFactory beanFactory, String className) {
        Class<?> workflowClass;
        try {
            workflowClass = ClassUtils.forName(className, beanFactory.getBeanClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            throw new BeanInitializationException(
                    "Failed to load @DurableWorkflow candidate class " + className, e);
        }
        return beanFactory.getBeanNamesForType(workflowClass, true, false).length > 0;
    }

    private List<String> resolveBasePackages(ConfigurableListableBeanFactory beanFactory) {
        var explicit = Binder.get(environment)
                .bind(WORKFLOW_PACKAGES_PROPERTY, String[].class)
                .map(List::of)
                .orElse(List.of());
        if (!explicit.isEmpty()) {
            return explicit;
        }
        if (AutoConfigurationPackages.has(beanFactory)) {
            return AutoConfigurationPackages.get(beanFactory);
        }
        return List.of();
    }
}
