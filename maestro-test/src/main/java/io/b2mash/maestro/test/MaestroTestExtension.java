package io.b2mash.maestro.test;

import org.jspecify.annotations.Nullable;
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
     * class without one, on its enclosing classes.
     */
    @Override
    public void beforeEach(ExtensionContext context) {
        var annotation = nearestMaestroTest(context.getRequiredTestClass());
        var builder = TestWorkflowEnvironment.builder();
        if (annotation != null
                && (annotation.defaultConcurrency() != 0 || annotation.defaultActivityConcurrency() != 0)) {
            builder.defaultConcurrency(annotation.defaultConcurrency(), annotation.defaultActivityConcurrency());
        }
        context.getStore(NAMESPACE).put(ENV_KEY, builder.build());
    }

    private static @Nullable MaestroTest nearestMaestroTest(Class<?> testClass) {
        for (Class<?> c = testClass; c != null; c = c.getEnclosingClass()) {
            var found = AnnotationSupport.findAnnotation(c, MaestroTest.class);
            if (found.isPresent()) {
                return found.get();
            }
        }
        return null;
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
