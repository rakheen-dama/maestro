package io.b2mash.maestro.messaging.kafka.config;

import io.b2mash.maestro.messaging.kafka.MaestroKafkaBeans;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

/**
 * Explains the startup failure of an unqualified
 * {@code KafkaTemplate<…, byte[]>} injection point that finds no candidate at
 * all: Maestro's {@code maestroKafkaTemplate} is not a default candidate, and
 * Spring Boot's {@code kafkaTemplate} backed off because the application
 * declares a {@code KafkaTemplate} of its own. The raw
 * {@code NoSuchBeanDefinitionException} never mentions Maestro, and the
 * injection point is exactly what Maestro's earlier docs advised.
 *
 * <p>Fires only when all of these hold, and returns {@code null} otherwise,
 * so every other missing bean keeps Spring Boot's own analysis:
 * <ul>
 *   <li>the missing bean is a {@link KafkaOperations} whose value type is
 *       {@code byte[]} (not a {@link NoUniqueBeanDefinitionException});</li>
 *   <li>it is the dependency of an injection point (the
 *       {@link UnsatisfiedDependencyException} that carries it) with no
 *       qualifier — no annotation that is, or is meta-annotated with, Spring's
 *       {@code @Qualifier} or {@code jakarta.inject.Qualifier};</li>
 *   <li>a bean definition named {@link MaestroKafkaBeans#TEMPLATE} exists.</li>
 * </ul>
 *
 * <p>Registered in {@code META-INF/spring.factories} under
 * {@code org.springframework.boot.diagnostics.FailureAnalyzer}, ordered first
 * like {@link ProducerFactoryReplacementFailureAnalyzer}. It reads bean
 * definitions only and never creates a bean.
 *
 * <p><b>Thread safety:</b> stateless apart from the bean factory; Spring Boot
 * calls it once, on the failing startup thread.
 */
final class ByteArrayTemplateMissingFailureAnalyzer extends AbstractFailureAnalyzer<NoSuchBeanDefinitionException>
        implements Ordered {

    private static final String JAKARTA_QUALIFIER = "jakarta.inject.Qualifier";

    private final BeanFactory beanFactory;

    ByteArrayTemplateMissingFailureAnalyzer(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    protected @Nullable FailureAnalysis analyze(Throwable rootFailure, NoSuchBeanDefinitionException cause) {
        if (cause instanceof NoUniqueBeanDefinitionException || !isByteArrayTemplate(cause.getResolvableType())) {
            return null;
        }
        if (!(this.beanFactory instanceof ConfigurableListableBeanFactory bf)
                || !bf.containsBeanDefinition(MaestroKafkaBeans.TEMPLATE)) {
            return null;
        }
        var unsatisfied = unsatisfiedDependency(rootFailure, cause);
        if (unsatisfied == null || unsatisfied.getInjectionPoint() == null
                || isQualified(unsatisfied.getInjectionPoint())) {
            return null;
        }
        var hostTemplates = defaultCandidateTemplates(bf);
        var description = "Bean '" + unsatisfied.getBeanName() + "' injects a KafkaTemplate<…, byte[]> without a "
                + "qualifier (" + describe(unsatisfied.getInjectionPoint()) + "), and no Kafka template can be "
                + "injected there by type. Maestro's '" + MaestroKafkaBeans.TEMPLATE + "' is not a default "
                + "candidate: Maestro's Kafka beans are injected by qualifier only. Spring Boot's kafkaTemplate backs "
                + "off when the application declares a KafkaTemplate of its own (here: "
                + (hostTemplates.isEmpty() ? "none found" : String.join(", ", hostTemplates)) + ").";
        var action = "Inject Maestro's template with @Qualifier(MaestroKafkaBeans.TEMPLATE) (\""
                + MaestroKafkaBeans.TEMPLATE + "\"). See docs/configuration.md § Kafka Client Configuration.";
        return new FailureAnalysis(description, action, cause);
    }

    private static boolean isByteArrayTemplate(@Nullable ResolvableType type) {
        if (type == null) {
            return false;
        }
        var raw = type.resolve();
        return raw != null
                && KafkaOperations.class.isAssignableFrom(raw)
                && type.as(KafkaOperations.class).getGeneric(1).resolve() == byte[].class;
    }

    private static @Nullable UnsatisfiedDependencyException unsatisfiedDependency(Throwable rootFailure,
            NoSuchBeanDefinitionException cause) {
        for (Throwable t = rootFailure; t != null; t = t.getCause()) {
            if (t instanceof UnsatisfiedDependencyException unsatisfied && unsatisfied.getCause() == cause) {
                return unsatisfied;
            }
        }
        return null;
    }

    private static boolean isQualified(InjectionPoint injectionPoint) {
        var annotations = MergedAnnotations.from(injectionPoint.getAnnotations());
        if (annotations.isPresent(Qualifier.class) || annotations.isPresent(JAKARTA_QUALIFIER)) {
            return true;
        }
        // Spring also reads a qualifier on a constructor or a void (setter-style) method.
        var parameter = injectionPoint.getMethodParameter();
        if (parameter == null) {
            return false;
        }
        var executable = parameter.getExecutable();
        var methodLevel = executable instanceof Constructor<?>
                || (parameter.getMethod() != null && parameter.getMethod().getReturnType() == void.class);
        if (!methodLevel) {
            return false;
        }
        var onExecutable = MergedAnnotations.from(executable);
        return onExecutable.isPresent(Qualifier.class) || onExecutable.isPresent(JAKARTA_QUALIFIER);
    }

    private static String describe(InjectionPoint injectionPoint) {
        var field = injectionPoint.getField();
        if (field != null) {
            return "field " + field.getName() + " of " + field.getDeclaringClass().getName();
        }
        MethodParameter parameter = injectionPoint.getMethodParameter();
        if (parameter == null) {
            return String.valueOf(injectionPoint.getMember());
        }
        var index = parameter.getParameterIndex();
        if (parameter.getExecutable() instanceof Constructor<?> constructor) {
            return "constructor parameter " + index + " of " + constructor.getDeclaringClass().getName();
        }
        var method = parameter.getExecutable();
        return "parameter " + index + " of method " + method.getDeclaringClass().getName() + "." + method.getName();
    }

    /** The default-candidate {@code KafkaTemplate} definitions: what made Boot's back off. */
    private static List<String> defaultCandidateTemplates(ConfigurableListableBeanFactory bf) {
        var names = new ArrayList<String>();
        for (var name : bf.getBeanNamesForType(KafkaTemplate.class, true, false)) {
            if (!bf.containsBeanDefinition(name)) {
                continue;
            }
            var definition = bf.getMergedBeanDefinition(name);
            if (definition.isAutowireCandidate()
                    && !(definition instanceof AbstractBeanDefinition abstractDefinition
                            && !abstractDefinition.isDefaultCandidate())) {
                names.add(name);
            }
        }
        return names;
    }
}
