package io.b2mash.maestro.messaging.kafka.config;

import io.b2mash.maestro.messaging.kafka.MaestroKafkaBeans;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.Serializer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
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
import java.util.Set;
import java.util.function.IntPredicate;

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
 *   <li>a point counts against this template only if it received it: a
 *       {@link DependencyDescriptor} is built for the point, and this template
 *       must be the only default-candidate bean of the point's declared type
 *       that {@code isAutowireCandidate} accepts for it (strict generic
 *       matches, else Spring's fallback matches, as Spring searches). A bean that uses
 *       a {@code String} template and a separate {@code byte[]} template is
 *       therefore never blamed for the wrong one. A point several templates
 *       match (Spring picked by {@code @Primary}, {@code @Priority} or the
 *       parameter or field name) is never a failure: when this template
 *       cannot serialize {@code byte[]}, or cannot be classified, one
 *       {@code WARN} per point says Maestro cannot tell which one it
 *       received (a template whose value serializer the check cannot see,
 *       such as a {@code KafkaOperations} that is not a {@code KafkaTemplate},
 *       gets a {@code DEBUG} line only);</li>
 *   <li>only if a point remains, the template's value serializer is classified
 *       ({@link #valueSerializerTarget}); a violation is a serializer whose
 *       declared target type is not assignable from {@code byte[]}.</li>
 * </ol>
 * <p>The check fails startup only on a confirmed violation, with one
 * exception listing every violation. A serializer it
 * cannot classify (a generic serializer such as {@code JacksonJsonSerializer},
 * a lambda, a delegating serializer) is not a failure: it is logged at
 * {@code WARN}, because such a serializer may write {@code byte[]} values
 * differently from {@code ByteArraySerializer}. Any exception while inspecting
 * a bean skips that bean at {@code DEBUG}. The check reads bean definitions
 * and already-created singletons only; it creates no bean.
 *
 * <h2>Reach</h2>
 * <p>Seen: constructor parameters that Spring autowired (the
 * {@code @Autowired} constructor, or the only constructor),
 * {@code @Autowired}/{@code @Inject} fields and method parameters (including
 * inherited ones), and {@code @Bean} method parameters, of beans created at
 * startup. For a {@code FactoryBean}, its own class is scanned, never its
 * product's. Not points, because Spring did not autowire them: the
 * constructor of a bean built by an instance supplier (a
 * {@code BeanRegistrar} supplier or {@code registerBean(…, Supplier)}, which
 * may call {@code getBean} itself), and constructor or factory-method
 * parameters given explicit constructor-argument values (an indexed value
 * exempts its index; any generic value exempts every parameter). Out of
 * reach:
 * <ul>
 *   <li>a qualified point, whichever template its qualifier names;</li>
 *   <li>raw or wildcard generics ({@code KafkaTemplate},
 *       {@code KafkaTemplate<String, ?>}): not a {@code byte[]} point;</li>
 *   <li>{@code ObjectProvider}, {@code Optional}, collection and
 *       {@code getBean} lookups — resolved when the host calls them, or not a
 *       {@code KafkaOperations}-typed point;</li>
 *   <li>lazy beans and prototypes not created during startup, and
 *       {@code @Lazy} injection points (no dependency is recorded);</li>
 *   <li>a class with several constructors none of which is
 *       {@code @Autowired};</li>
 *   <li>templates an instance supplier looks up itself.</li>
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
        var templates = defaultCandidateTemplates(bf);
        var violations = new ArrayList<String>();
        var ambiguous = new LinkedHashMap<String, String>();
        for (var templateName : bf.getBeanNamesForType(KafkaOperations.class, true, false)) {
            try {
                inspectTemplate(bf, templateName, templates, violations, ambiguous);
            }
            catch (RuntimeException | LinkageError ex) {
                logger.debug("Skipped the byte[] injection check for Kafka template bean '{}'", templateName, ex);
            }
        }
        ambiguous.values().forEach(logger::warn);
        if (!violations.isEmpty()) {
            throw new IllegalStateException(String.join("\n\n", violations));
        }
    }

    /**
     * Every {@link KafkaOperations} bean an unqualified injection point can
     * receive: the default autowire candidates, in this factory and its
     * ancestors. Reads bean definitions only.
     */
    private static List<String> defaultCandidateTemplates(ConfigurableListableBeanFactory bf) {
        var names = new ArrayList<String>();
        for (var name : BeanFactoryUtils.beanNamesForTypeIncludingAncestors(bf, KafkaOperations.class, true, false)) {
            try {
                if (isDefaultCandidate(bf.getMergedBeanDefinition(name))) {
                    names.add(name);
                }
            }
            catch (RuntimeException ex) {
                // No definition to read (a manually registered singleton): Spring can still
                // inject it, so keep it as a possible candidate.
                names.add(name);
            }
        }
        return names;
    }

    private static boolean isDefaultCandidate(BeanDefinition definition) {
        return definition.isAutowireCandidate()
                && !(definition instanceof AbstractBeanDefinition abstractDefinition
                        && !abstractDefinition.isDefaultCandidate());
    }

    private static void inspectTemplate(ConfigurableListableBeanFactory bf, String templateName,
            List<String> templates, List<String> violations, Map<String, String> ambiguous) {
        if (!bf.containsBeanDefinition(templateName) || !bf.containsSingleton(templateName)) {
            return;
        }
        if (!isDefaultCandidate(bf.getMergedBeanDefinition(templateName))) {
            return;
        }
        if (!(bf.getSingleton(templateName) instanceof KafkaOperations<?, ?> template)) {
            return;
        }
        // Dependents first: the serializer is only looked at when a point needs it.
        // A point counts against this template only if this template is the one
        // candidate Spring could have injected there; a point several templates
        // match (resolved by @Primary, @Priority or the parameter/field name) is
        // never failed, only warned about.
        var pointsByBean = new LinkedHashMap<String, List<String>>();
        var ambiguousPoints = new ArrayList<AmbiguousPoint>();
        for (var dependent : bf.getDependentBeans(templateName)) {
            try {
                for (var point : pointsOf(bf, dependent)) {
                    var candidates = candidatesFor(bf, point.descriptor(), templates);
                    if (!candidates.contains(templateName)) {
                        continue;
                    }
                    if (candidates.size() == 1) {
                        pointsByBean.computeIfAbsent(dependent, k -> new ArrayList<>()).add(point.description());
                    }
                    else {
                        ambiguousPoints.add(new AmbiguousPoint(dependent, point.description(), candidates));
                    }
                }
            }
            catch (RuntimeException | LinkageError ex) {
                logger.debug("Skipped the byte[] injection check for bean '{}'", dependent, ex);
            }
        }
        if (pointsByBean.isEmpty() && ambiguousPoints.isEmpty()) {
            return;
        }
        // Resolved once: on a DefaultKafkaProducerFactory each getValueSerializer()
        // call runs the supplier and configure(...) on the shared serializer.
        var serializerClass = valueSerializerClass(template);
        var target = valueSerializerTarget(serializerClass);
        if (target != null && target.isAssignableFrom(byte[].class)) {
            return;
        }
        if (serializerClass != null) {
            for (var point : ambiguousPoints) {
                ambiguous.putIfAbsent(point.bean() + "\u0000" + point.description(),
                        ambiguityWarning(point, templateName, serializerClass, target != null));
            }
        }
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
        for (Map.Entry<String, List<String>> entry : pointsByBean.entrySet()) {
            for (var point : entry.getValue()) {
                violations.add(violation(entry.getKey(), point, templateName, serializerClass));
            }
        }
    }

    /**
     * The templates among {@code templates} that Spring would consider for
     * {@code descriptor}: the strict generic matches, else (as Spring does when
     * there are none) the fallback matches. Reads bean definitions only.
     */
    private static List<String> candidatesFor(ConfigurableListableBeanFactory bf, DependencyDescriptor descriptor,
            List<String> templates) {
        // Spring first narrows to beans of the point's declared type (a non-generic
        // KafkaTemplate subclass matches only its own type), then checks generics.
        var ofRequiredType = Set.of(BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
                bf, descriptor.getDependencyType(), true, false));
        var pool = templates.stream().filter(ofRequiredType::contains).toList();
        var strict = matching(bf, descriptor, pool);
        return strict.isEmpty() ? matching(bf, descriptor.forFallbackMatch(), pool) : strict;
    }

    private static List<String> matching(ConfigurableListableBeanFactory bf, DependencyDescriptor descriptor,
            List<String> templates) {
        var matches = new ArrayList<String>();
        for (var name : templates) {
            if (bf.isAutowireCandidate(name, descriptor)) {
                matches.add(name);
            }
        }
        return matches;
    }

    private static List<InjectionPoint> pointsOf(ConfigurableListableBeanFactory bf, String beanName) {
        if (!bf.containsBeanDefinition(beanName)) {
            return List.of();
        }
        var definition = bf.getMergedBeanDefinition(beanName);
        var factoryMethod = definition instanceof RootBeanDefinition root ? root.getResolvedFactoryMethod() : null;
        // For a FactoryBean, getType(name) is the product's class, which Spring never
        // injects into; the FactoryBean itself is what was autowired.
        var type = bf.isFactoryBean(beanName)
                ? bf.getType(BeanFactory.FACTORY_BEAN_PREFIX + beanName)
                : bf.getType(beanName);
        if (type == null) {
            return List.of();
        }
        return unqualifiedByteArrayTemplateInjectionPoints(ClassUtils.getUserClass(type), factoryMethod,
                autowiredArguments(definition, factoryMethod));
    }

    /**
     * Which constructor (or factory-method) parameter indexes Spring resolved by
     * autowiring: none for a bean built by an instance supplier (it calls the
     * constructor itself); none when the definition has generic argument values
     * (matched by type, so any parameter may be one); otherwise every index
     * without an explicit indexed value.
     */
    private static IntPredicate autowiredArguments(BeanDefinition definition, @Nullable Method factoryMethod) {
        if (factoryMethod == null && definition instanceof AbstractBeanDefinition abstractDefinition
                && abstractDefinition.getInstanceSupplier() != null) {
            return index -> false;
        }
        var arguments = definition.getConstructorArgumentValues();
        if (!arguments.getGenericArgumentValues().isEmpty()) {
            return index -> false;
        }
        return index -> !arguments.hasIndexedArgumentValue(index);
    }

    private static String ambiguityWarning(AmbiguousPoint point, String templateName, Class<?> serializerClass,
            boolean classified) {
        return "Bean '" + point.bean() + "' injects a KafkaTemplate<…, byte[]> without a qualifier ("
                + point.description() + ") that several Kafka templates match (" + String.join(", ", point.candidates())
                + "); Maestro does not check which one it received. Among them, '" + templateName
                + "' has the value serializer " + serializerClass.getName()
                + (classified ? ", which cannot serialize byte[]." : ", which Maestro cannot classify.")
                + " Qualify the injection point with the template you mean (Maestro's is "
                + "@Qualifier(MaestroKafkaBeans.TEMPLATE) (\"" + MaestroKafkaBeans.TEMPLATE + "\")). "
                + "See docs/configuration.md § Kafka Client Configuration.";
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
        return valueSerializerTarget(valueSerializerClass(template));
    }

    /**
     * The type a value serializer class declares it serializes: {@code T} of
     * {@code Serializer<T>}.
     *
     * @param serializerClass the serializer class, or {@code null}
     * @return the serialized type, or {@code null} when the class is
     *         {@code null} or {@code T} does not resolve
     */
    static @Nullable Class<?> valueSerializerTarget(@Nullable Class<?> serializerClass) {
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
        return unqualifiedByteArrayTemplateInjectionPoints(beanClass, factoryMethod, index -> true).stream()
                .map(InjectionPoint::description)
                .toList();
    }

    /**
     * The same points as {@link #unqualifiedByteArrayTemplatePoints}, each with
     * the {@link DependencyDescriptor} Spring resolves it through; constructor
     * and factory-method parameters only where {@code autowired} accepts their
     * index.
     */
    private static List<InjectionPoint> unqualifiedByteArrayTemplateInjectionPoints(Class<?> beanClass,
            @Nullable Method factoryMethod, IntPredicate autowired) {
        var points = new ArrayList<InjectionPoint>();
        if (factoryMethod != null) {
            // Spring resolves a @Bean method on the user class, not on a CGLIB subclass,
            // so its parameter annotations are the declared ones.
            var owner = ClassUtils.getUserClass(factoryMethod.getDeclaringClass()).getName();
            for (int i = 0; i < factoryMethod.getParameterCount(); i++) {
                var parameter = MethodParameter.forExecutable(factoryMethod, i);
                if (autowired.test(i) && isUnqualifiedByteArrayTemplate(parameter)) {
                    points.add(new InjectionPoint("parameter " + i + " of @Bean method " + owner + "."
                            + factoryMethod.getName(), new DependencyDescriptor(parameter, true)));
                }
            }
        }
        else {
            for (var constructor : injectionConstructors(beanClass)) {
                for (int i = 0; i < constructor.getParameterCount(); i++) {
                    var parameter = MethodParameter.forExecutable(constructor, i);
                    if (autowired.test(i) && isUnqualifiedByteArrayTemplate(parameter)) {
                        points.add(new InjectionPoint("constructor parameter " + i + " of " + beanClass.getName(),
                                descriptor(parameter, beanClass)));
                    }
                }
            }
        }
        ReflectionUtils.doWithFields(beanClass, field -> {
            if (!Modifier.isStatic(field.getModifiers()) && isInjected(field)
                    && isUnqualifiedByteArrayTemplate(field, beanClass)) {
                var descriptor = new DependencyDescriptor(field, true);
                descriptor.setContainingClass(beanClass);
                points.add(new InjectionPoint("field " + field.getName() + " of "
                        + field.getDeclaringClass().getName(), descriptor));
            }
        });
        for (var method : ReflectionUtils.getUniqueDeclaredMethods(beanClass, ReflectionUtils.USER_DECLARED_METHODS)) {
            if (Modifier.isStatic(method.getModifiers()) || !isInjected(method)) {
                continue;
            }
            for (int i = 0; i < method.getParameterCount(); i++) {
                var parameter = MethodParameter.forExecutable(method, i);
                if (isUnqualifiedByteArrayTemplate(parameter)) {
                    points.add(new InjectionPoint("parameter " + i + " of method "
                            + method.getDeclaringClass().getName() + "." + method.getName(),
                            descriptor(parameter, beanClass)));
                }
            }
        }
        return points;
    }

    private static DependencyDescriptor descriptor(MethodParameter parameter, Class<?> beanClass) {
        var descriptor = new DependencyDescriptor(parameter, true);
        descriptor.setContainingClass(beanClass);
        return descriptor;
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

    /** An unqualified {@code byte[]} template injection point: its description and how Spring resolves it. */
    private record InjectionPoint(String description, DependencyDescriptor descriptor) {}

    /** A point that several default-candidate templates match, among them the one being inspected. */
    private record AmbiguousPoint(String bean, String description, List<String> candidates) {}
}
