package io.b2mash.maestro.messaging.kafka.config;

import io.b2mash.maestro.messaging.kafka.MaestroKafkaBeans;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.Serializer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fails startup when a bean injects a {@code KafkaTemplate<…, byte[]>} without
 * a qualifier and received a template whose value serializer cannot serialize
 * {@code byte[]} (WP7, D5).
 *
 * <p>Maestro's Kafka beans are not default candidates, so an unqualified
 * {@code KafkaTemplate<String, byte[]>} — the advice of earlier docs —
 * resolves to Spring Boot's {@code kafkaTemplate}, whose default value
 * serializer is {@code StringSerializer}. That context starts, and every send
 * through the template then throws {@code SerializationException}. This check
 * turns that into one {@link IllegalStateException} at startup, naming each
 * offending bean, its injection point, the template it received and both
 * fixes.
 *
 * <h2>Rule</h2>
 * <p>After every singleton is created, for each bean of type
 * {@link KafkaOperations} that has a bean definition, is a default
 * autowire candidate and is already a singleton:
 * <ol>
 *   <li>its dependents ({@code getDependentBeans}) are scanned for injection
 *       points typed {@code KafkaOperations<?, byte[]>} (or a subtype) with no
 *       qualifier — no annotation that is, or is meta-annotated with, Spring's
 *       {@code @Qualifier} or {@code jakarta.inject.Qualifier};</li>
 *   <li>only if one exists, the template's value serializer is classified
 *       ({@link #valueSerializerTarget}); a violation is a serializer whose
 *       declared target type is not assignable from {@code byte[]}.</li>
 * </ol>
 * <p>The check fails startup only on a confirmed violation. A serializer it
 * cannot classify (a generic serializer such as {@code JacksonJsonSerializer},
 * a lambda, a delegating serializer) is not a failure: it is logged at
 * {@code WARN}, because such a serializer may write {@code byte[]} values
 * differently from {@code ByteArraySerializer}. Any exception while inspecting
 * a bean skips that bean at {@code DEBUG}.
 *
 * <h2>Reach</h2>
 * <p>Seen: constructor parameters (the {@code @Autowired} constructor, or the
 * only constructor), {@code @Autowired}/{@code @Inject} fields and method
 * parameters (including inherited ones), and {@code @Bean} method parameters,
 * of beans created at startup. Out of reach:
 * <ul>
 *   <li>{@code ObjectProvider}, {@code Optional}, collection and
 *       {@code getBean} lookups — resolved when the host calls them, or not a
 *       {@code KafkaOperations}-typed point;</li>
 *   <li>lazy beans and prototypes not created during startup, and
 *       {@code @Lazy} injection points (no dependency is recorded);</li>
 *   <li>a class with several constructors none of which is
 *       {@code @Autowired};</li>
 *   <li>beans registered with an instance supplier that looks the template up
 *       itself.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <p>Runs once, on the context-refresh thread; holds only the bean factory.
 */
final class ByteArrayTemplateInjectionCheck implements SmartInitializingSingleton, BeanFactoryAware {

    private static final Logger logger = LoggerFactory.getLogger(ByteArrayTemplateInjectionCheck.class);

    private static final String JAKARTA_QUALIFIER = "jakarta.inject.Qualifier";

    private static final String JAKARTA_INJECT = "jakarta.inject.Inject";

    private @Nullable ConfigurableListableBeanFactory beanFactory;

    ByteArrayTemplateInjectionCheck() {
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        if (beanFactory instanceof ConfigurableListableBeanFactory listable) {
            this.beanFactory = listable;
        }
    }

    @Override
    public void afterSingletonsInstantiated() {
        var bf = this.beanFactory;
        if (bf == null) {
            return;
        }
        var violations = new ArrayList<String>();
        for (var templateName : bf.getBeanNamesForType(KafkaOperations.class, true, false)) {
            try {
                inspectTemplate(bf, templateName, violations);
            }
            catch (RuntimeException | LinkageError ex) {
                logger.debug("Skipped the byte[] injection check for Kafka template bean '{}'", templateName, ex);
            }
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException(String.join("\n\n", violations));
        }
    }

    private static void inspectTemplate(ConfigurableListableBeanFactory bf, String templateName, List<String> violations) {
        if (!bf.containsBeanDefinition(templateName) || !bf.containsSingleton(templateName)) {
            return;
        }
        var definition = bf.getMergedBeanDefinition(templateName);
        if (!definition.isAutowireCandidate()
                || (definition instanceof AbstractBeanDefinition abstractDefinition
                        && !abstractDefinition.isDefaultCandidate())) {
            return;
        }
        if (!(bf.getSingleton(templateName) instanceof KafkaOperations<?, ?> template)) {
            return;
        }
        // Dependents first: the serializer is only looked at when a point needs it.
        var pointsByBean = new LinkedHashMap<String, List<String>>();
        for (var dependent : bf.getDependentBeans(templateName)) {
            try {
                var points = pointsOf(bf, dependent);
                if (!points.isEmpty()) {
                    pointsByBean.put(dependent, points);
                }
            }
            catch (RuntimeException | LinkageError ex) {
                logger.debug("Skipped the byte[] injection check for bean '{}'", dependent, ex);
            }
        }
        if (pointsByBean.isEmpty()) {
            return;
        }
        var target = valueSerializerTarget(template);
        var serializerClass = valueSerializerClass(template);
        if (target == null || serializerClass == null) {
            if (serializerClass != null) {
                warnUnclassified(templateName, serializerClass, pointsByBean);
            }
            else {
                logger.debug("Kafka template bean '{}' has no value serializer the check can see; "
                        + "unqualified byte[] injection points {} are not checked", templateName, pointsByBean);
            }
            return;
        }
        if (target.isAssignableFrom(byte[].class)) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : pointsByBean.entrySet()) {
            for (var point : entry.getValue()) {
                violations.add(violation(entry.getKey(), point, templateName, serializerClass));
            }
        }
    }

    private static List<String> pointsOf(ConfigurableListableBeanFactory bf, String beanName) {
        if (!bf.containsBeanDefinition(beanName)) {
            return List.of();
        }
        var definition = bf.getMergedBeanDefinition(beanName);
        var factoryMethod = definition instanceof RootBeanDefinition root ? root.getResolvedFactoryMethod() : null;
        var type = bf.getType(beanName);
        if (type == null) {
            return List.of();
        }
        return unqualifiedByteArrayTemplatePoints(ClassUtils.getUserClass(type), factoryMethod);
    }

    private static String violation(String bean, String point, String templateName, Class<?> serializerClass) {
        return "Bean '" + bean + "' injects a KafkaTemplate<…, byte[]> without a qualifier (" + point
                + ") and received '" + templateName + "', whose value serializer " + serializerClass.getName()
                + " cannot serialize byte[]: every send through it would fail. Maestro's Kafka beans are not "
                + "injected by type. Inject Maestro's template with @Qualifier(MaestroKafkaBeans.TEMPLATE) (\""
                + MaestroKafkaBeans.TEMPLATE + "\"), or set spring.kafka.producer.value-serializer="
                + "org.apache.kafka.common.serialization.ByteArraySerializer to send byte[] through Spring Boot's "
                + "template. See docs/configuration.md § Kafka Client Configuration.";
    }

    private static void warnUnclassified(String templateName, Class<?> serializerClass,
            Map<String, List<String>> pointsByBean) {
        for (Map.Entry<String, List<String>> entry : pointsByBean.entrySet()) {
            for (var point : entry.getValue()) {
                logger.warn("Bean '{}' injects a KafkaTemplate<…, byte[]> without a qualifier ({}) and received "
                                + "'{}', whose value serializer {} Maestro cannot classify: check that it writes "
                                + "byte[] values unchanged. Maestro's Kafka beans are not injected by type; inject "
                                + "Maestro's template with @Qualifier(MaestroKafkaBeans.TEMPLATE) (\"{}\") if that "
                                + "is the template you meant. See docs/configuration.md § Kafka Client Configuration.",
                        entry.getKey(), point, templateName, serializerClass.getName(), MaestroKafkaBeans.TEMPLATE);
            }
        }
    }

    /**
     * The type the template's value serializer declares it serializes.
     *
     * <p>Takes the producer factory's {@code getValueSerializer()} instance if
     * there is one (it wins over the configuration map), else its
     * {@code value.serializer} configuration entry (a {@code Class} or a class
     * name), and resolves {@code Serializer<T>}'s {@code T} on that class.
     *
     * @param template the template
     * @return the serialized type, or {@code null} when anything there is
     *         unknown: not a {@link KafkaTemplate}, no serializer, an unloadable
     *         class name, or a {@code T} that does not resolve
     */
    static @Nullable Class<?> valueSerializerTarget(KafkaOperations<?, ?> template) {
        return serializedType(valueSerializerClass(template));
    }

    private static @Nullable Class<?> serializedType(@Nullable Class<?> serializerClass) {
        if (serializerClass == null) {
            return null;
        }
        return ResolvableType.forClass(serializerClass).as(Serializer.class).getGeneric(0).resolve();
    }

    private static @Nullable Class<?> valueSerializerClass(KafkaOperations<?, ?> template) {
        if (!(template instanceof KafkaTemplate<?, ?> kafkaTemplate)) {
            return null;
        }
        try {
            var producerFactory = kafkaTemplate.getProducerFactory();
            var instance = producerFactory.getValueSerializer();
            if (instance != null) {
                return instance.getClass();
            }
            var configured = producerFactory.getConfigurationProperties().get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);
            if (configured instanceof Class<?> type) {
                return type;
            }
            if (configured instanceof String name && !name.isBlank()) {
                return ClassUtils.forName(name.trim(), ClassUtils.getDefaultClassLoader());
            }
            return null;
        }
        catch (ClassNotFoundException | RuntimeException | LinkageError ex) {
            logger.debug("Could not determine the value serializer of {}", template, ex);
            return null;
        }
    }

    /**
     * The unqualified {@code KafkaOperations<?, byte[]>} injection points of a
     * bean, described as they appear in the failure message.
     *
     * <p>A bean made by a factory method contributes that method's parameters
     * ({@code parameter <i> of @Bean method <class>.<method>}); otherwise its
     * injection constructor contributes its parameters
     * ({@code constructor parameter <i> of <class>}) — the {@code @Autowired}
     * constructors, or the only declared constructor. Either way the class's
     * {@code @Autowired} fields ({@code field <name> of <class>}) and method
     * parameters ({@code parameter <i> of method <class>.<method>}) are added.
     *
     * @param beanClass     the bean's user class
     * @param factoryMethod the factory method that created it, or {@code null}
     * @return the descriptions, in declaration order
     */
    static List<String> unqualifiedByteArrayTemplatePoints(Class<?> beanClass, @Nullable Method factoryMethod) {
        var points = new ArrayList<String>();
        if (factoryMethod != null) {
            // Spring resolves a @Bean method on the user class, not on a CGLIB subclass,
            // so its parameter annotations are the declared ones.
            var owner = ClassUtils.getUserClass(factoryMethod.getDeclaringClass()).getName();
            for (int i = 0; i < factoryMethod.getParameterCount(); i++) {
                if (isUnqualifiedByteArrayTemplate(MethodParameter.forExecutable(factoryMethod, i))) {
                    points.add("parameter " + i + " of @Bean method " + owner + "." + factoryMethod.getName());
                }
            }
        }
        else {
            for (var constructor : injectionConstructors(beanClass)) {
                for (int i = 0; i < constructor.getParameterCount(); i++) {
                    if (isUnqualifiedByteArrayTemplate(MethodParameter.forExecutable(constructor, i))) {
                        points.add("constructor parameter " + i + " of " + beanClass.getName());
                    }
                }
            }
        }
        ReflectionUtils.doWithFields(beanClass, field -> {
            if (!Modifier.isStatic(field.getModifiers()) && isInjected(field)
                    && isUnqualifiedByteArrayTemplate(field, beanClass)) {
                points.add("field " + field.getName() + " of " + field.getDeclaringClass().getName());
            }
        });
        for (var method : ReflectionUtils.getUniqueDeclaredMethods(beanClass, ReflectionUtils.USER_DECLARED_METHODS)) {
            if (Modifier.isStatic(method.getModifiers()) || !isInjected(method)) {
                continue;
            }
            for (int i = 0; i < method.getParameterCount(); i++) {
                if (isUnqualifiedByteArrayTemplate(MethodParameter.forExecutable(method, i))) {
                    points.add("parameter " + i + " of method " + method.getDeclaringClass().getName()
                            + "." + method.getName());
                }
            }
        }
        return points;
    }

    private static List<Constructor<?>> injectionConstructors(Class<?> beanClass) {
        var all = beanClass.getDeclaredConstructors();
        var annotated = Arrays.stream(all).filter(ByteArrayTemplateInjectionCheck::isInjected).toList();
        if (!annotated.isEmpty()) {
            return annotated;
        }
        // Several constructors, none annotated: Spring injects through none of them here.
        return all.length == 1 ? List.of(all[0]) : List.of();
    }

    private static boolean isInjected(AnnotatedElement element) {
        var annotations = MergedAnnotations.from(element);
        return annotations.isPresent(Autowired.class) || annotations.isPresent(JAKARTA_INJECT);
    }

    private static boolean isUnqualifiedByteArrayTemplate(MethodParameter parameter) {
        if (!isByteArrayTemplate(ResolvableType.forMethodParameter(parameter))) {
            return false;
        }
        if (isQualified(MergedAnnotations.from(parameter.getParameterAnnotations()))) {
            return false;
        }
        // Spring also reads a qualifier on a constructor or a void (setter-style) method.
        Executable executable = parameter.getExecutable();
        var methodLevel = executable instanceof Constructor<?>
                || (executable instanceof Method method && method.getReturnType() == void.class);
        return !(methodLevel && isQualified(MergedAnnotations.from(executable)));
    }

    private static boolean isUnqualifiedByteArrayTemplate(Field field, Class<?> beanClass) {
        return isByteArrayTemplate(ResolvableType.forField(field, beanClass))
                && !isQualified(MergedAnnotations.from(field));
    }

    private static boolean isByteArrayTemplate(ResolvableType type) {
        var raw = type.resolve();
        return raw != null
                && KafkaOperations.class.isAssignableFrom(raw)
                && type.as(KafkaOperations.class).getGeneric(1).resolve() == byte[].class;
    }

    private static boolean isQualified(MergedAnnotations annotations) {
        return annotations.isPresent(Qualifier.class) || annotations.isPresent(JAKARTA_QUALIFIER);
    }
}
