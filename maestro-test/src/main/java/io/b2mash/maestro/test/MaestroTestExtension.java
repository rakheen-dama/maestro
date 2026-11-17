package io.b2mash.maestro.test;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * JUnit 5 extension that manages the {@link TestWorkflowEnvironment} lifecycle.
 *
 * <p>Creates a fresh environment before each test and shuts it down after.
 * Resolves {@link TestWorkflowEnvironment} parameters in test methods. The
 * environment takes the concurrency limits of the nearest {@link MaestroTest}
 * (the test class's, else an enclosing class's).
 *
 * <p>Typically not used directly — use the {@link MaestroTest @MaestroTest}
 * annotation instead.
 *
 * @see MaestroTest
 */
public final class MaestroTestExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(MaestroTestExtension.class);

    private static final String ENV_KEY = "testWorkflowEnvironment";

    /**
     * Builds the test's environment, with the concurrency limits of the
     * nearest {@link MaestroTest} on the test class or, for a {@code @Nested}
     * class without one, on the classes enclosing it at run time — so a
     * nested class inherited from an unannotated base takes the annotated
     * subclass's limits.
     */
    @Override
    public void beforeEach(ExtensionContext context) {
        var annotation = AnnotationSupport.findAnnotation(context.getRequiredTestClass(), MaestroTest.class,
                context.getEnclosingTestClasses());
        var builder = TestWorkflowEnvironment.builder();
        annotation.filter(a -> a.defaultConcurrency() != 0 || a.defaultActivityConcurrency() != 0)
                .ifPresent(a -> builder.defaultConcurrency(a.defaultConcurrency(), a.defaultActivityConcurrency()));
        context.getStore(NAMESPACE).put(ENV_KEY, builder.build());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        var env = context.getStore(NAMESPACE).remove(ENV_KEY, TestWorkflowEnvironment.class);
        if (env != null) {
            env.shutdown();
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return parameterContext.getParameter().getType() == TestWorkflowEnvironment.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        var env = extensionContext.getStore(NAMESPACE).get(ENV_KEY, TestWorkflowEnvironment.class);
        if (env == null) {
            throw new ParameterResolutionException(
                    "TestWorkflowEnvironment not found. Ensure @MaestroTest is on the test class.");
        }
        return env;
    }
}
