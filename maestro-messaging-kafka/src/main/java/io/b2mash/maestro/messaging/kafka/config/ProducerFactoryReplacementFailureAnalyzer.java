package io.b2mash.maestro.messaging.kafka.config;

import io.b2mash.maestro.messaging.kafka.MaestroKafkaBeans;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Explains the startup failure caused by replacing
 * {@code maestroKafkaProducerFactory} with a plain {@code @Bean}: Spring Boot's
 * {@code kafkaProducerFactory} backs off for it (it is a default candidate of
 * that type), Spring Boot's {@code kafkaTemplate} still registers (Maestro's
 * template is not a default candidate), and that template finds no
 * {@code ProducerFactory<Object, Object>}. The raw
 * {@code NoSuchBeanDefinitionException} never mentions Maestro.
 *
 * <p>Fires only when all of these hold, and returns {@code null} otherwise,
 * so another missing {@code ProducerFactory} keeps Spring Boot's own analysis:
 * <ul>
 *   <li>the missing bean is a {@link ProducerFactory} (not a
 *       {@link NoUniqueBeanDefinitionException});</li>
 *   <li>it is the dependency of the bean {@code kafkaTemplate} declared in
 *       Spring Boot's {@code KafkaAutoConfiguration} (the
 *       {@link UnsatisfiedDependencyException} that carries it);</li>
 *   <li>a bean definition named {@link MaestroKafkaBeans#PRODUCER_FACTORY}
 *       exists and is a default autowire candidate — so it is not Maestro's
 *       own, which is {@code defaultCandidate = false};</li>
 *   <li>it is the only default-candidate {@code ProducerFactory} definition, so
 *       declaring it {@code defaultCandidate = false} brings Spring Boot's
 *       {@code kafkaProducerFactory} back.</li>
 * </ul>
 *
 * <p>Registered in {@code META-INF/spring.factories} under
 * {@code org.springframework.boot.diagnostics.FailureAnalyzer}. Ordered first,
 * because Spring Boot's own {@code NoSuchBeanDefinitionFailureAnalyzer} (not
 * ordered) would otherwise analyze the same exception. It reads bean
 * definitions only and never creates a bean.
 *
 * <p><b>Thread safety:</b> stateless apart from the bean factory; Spring Boot
 * calls it once, on the failing startup thread.
 */
final class ProducerFactoryReplacementFailureAnalyzer extends AbstractFailureAnalyzer<NoSuchBeanDefinitionException>
        implements Ordered {

    static final String BOOT_KAFKA_TEMPLATE = "kafkaTemplate";

    static final String BOOT_KAFKA_AUTO_CONFIGURATION =
            "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration";

    private final BeanFactory beanFactory;

    ProducerFactoryReplacementFailureAnalyzer(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    protected @Nullable FailureAnalysis analyze(Throwable rootFailure, NoSuchBeanDefinitionException cause) {
        if (cause instanceof NoUniqueBeanDefinitionException) {
            return null;
        }
        var missingType = cause.getBeanType();
        if (missingType == null || !ProducerFactory.class.isAssignableFrom(missingType)) {
            return null;
        }
        var bootTemplateParameter = bootKafkaTemplateParameter(rootFailure, cause);
        if (bootTemplateParameter == null) {
            return null;
        }
        if (!(this.beanFactory instanceof ConfigurableListableBeanFactory bf)
                || !isTheOnlyDefaultCandidateProducerFactory(bf, MaestroKafkaBeans.PRODUCER_FACTORY)) {
            return null;
        }
        var description = "The bean '" + MaestroKafkaBeans.PRODUCER_FACTORY + "' replaces Maestro's engine "
                + "producer factory but is a default candidate, so Spring Boot's kafkaProducerFactory backed off. "
                + "Spring Boot's kafkaTemplate (parameter " + bootTemplateParameter.getParameterIndex()
                + " of method " + BOOT_KAFKA_TEMPLATE + " in " + BOOT_KAFKA_AUTO_CONFIGURATION
                + ") then found no ProducerFactory<Object, Object> to use.";
        var action = "Declare the replacement with @Bean(name = MaestroKafkaBeans.PRODUCER_FACTORY, "
                + "defaultCandidate = false), so Spring Boot's kafkaProducerFactory registers beside it, or define "
                + "your own KafkaTemplate bean, so Spring Boot's kafkaTemplate backs off. "
                + "See docs/configuration.md § Kafka Client Configuration.";
        return new FailureAnalysis(description, action, cause);
    }

    /**
     * The parameter of Spring Boot's {@code kafkaTemplate} factory method that
     * {@code cause} could not satisfy, or {@code null} when {@code cause} is
     * not that dependency.
     */
    private static @Nullable MethodParameter bootKafkaTemplateParameter(Throwable rootFailure,
            NoSuchBeanDefinitionException cause) {
        for (Throwable t = rootFailure; t != null; t = t.getCause()) {
            if (t instanceof UnsatisfiedDependencyException unsatisfied && unsatisfied.getCause() == cause) {
                if (!BOOT_KAFKA_TEMPLATE.equals(unsatisfied.getBeanName())) {
                    return null;
                }
                var injectionPoint = unsatisfied.getInjectionPoint();
                var parameter = injectionPoint != null ? injectionPoint.getMethodParameter() : null;
                if (parameter == null || parameter.getMethod() == null
                        || !BOOT_KAFKA_TEMPLATE.equals(parameter.getMethod().getName())
                        || !BOOT_KAFKA_AUTO_CONFIGURATION.equals(parameter.getDeclaringClass().getName())) {
                    return null;
                }
                return parameter;
            }
        }
        return null;
    }

    private static boolean isTheOnlyDefaultCandidateProducerFactory(ConfigurableListableBeanFactory bf, String name) {
        if (!bf.containsBeanDefinition(name) || !isDefaultCandidate(bf, name)) {
            return false;
        }
        for (var other : bf.getBeanNamesForType(ProducerFactory.class, true, false)) {
            if (!other.equals(name) && bf.containsBeanDefinition(other) && isDefaultCandidate(bf, other)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDefaultCandidate(ConfigurableListableBeanFactory bf, String name) {
        var definition = bf.getMergedBeanDefinition(name);
        return definition.isAutowireCandidate()
                && !(definition instanceof AbstractBeanDefinition abstractDefinition
                        && !abstractDefinition.isDefaultCandidate());
    }
}
