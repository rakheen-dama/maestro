package io.b2mash.maestro.messaging.kafka.listener;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.spring.annotation.MaestroSignalListener;
import io.b2mash.maestro.spring.annotation.SignalRouting;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanInitializationException;
import java.lang.reflect.InvocationTargetException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListener;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Discovers methods annotated with {@link MaestroSignalListener} and creates
 * Kafka consumer containers that route incoming messages to workflow signals.
 *
 * <h2>Processing Phases</h2>
 * <ol>
 *   <li><b>Scanning</b> ({@code postProcessAfterInitialization}): Inspects
 *       each bean for annotated methods, validates their signature, and
 *       collects registrations.</li>
 *   <li><b>Activation</b> ({@code afterSingletonsInstantiated}): Resolves
 *       dependencies from the {@link ApplicationContext} and starts a Kafka
 *       consumer container for each registration.</li>
 * </ol>
 *
 * <h2>Per-Message Flow</h2>
 * <pre>
 * Kafka message (byte[])
 *   → deserialize to method parameter type (Jackson 3)
 *   → invoke annotated method
 *   → extract {@link SignalRouting} (workflowId + payload)
 *   → call {@link WorkflowExecutor#deliverSignal}
 * </pre>
 *
 * <h2>Thread Safety</h2>
 * <p>Scanning happens on the Spring initialization thread. Consumer containers
 * run on their own threads. The {@code registrations} list is fully populated
 * before container creation begins, so no concurrent modification occurs.
 *
 * @see MaestroSignalListener
 * @see SignalRouting
 */
public class MaestroSignalListenerBeanPostProcessor
        implements BeanPostProcessor, SmartInitializingSingleton, ApplicationContextAware, DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(MaestroSignalListenerBeanPostProcessor.class);

    private final List<ListenerRegistration> registrations = new ArrayList<>();
    private final List<ConcurrentMessageListenerContainer<String, byte[]>> containers = new CopyOnWriteArrayList<>();
    private @Nullable ApplicationContext applicationContext;

    /**
     * Internal registration for a discovered {@link MaestroSignalListener} method.
     */
    private record ListenerRegistration(
            Object bean,
            String beanName,
            Method method,
            String topic,
            String signalName,
            Class<?> parameterType,
            String groupIdSuffix
    ) {}

    // ── BeanPostProcessor ───────��────────────────────────────────────────

    @Override
    public @Nullable Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        for (var method : bean.getClass().getMethods()) {
            var annotation = method.getAnnotation(MaestroSignalListener.class);
            if (annotation == null) {
                continue;
            }

            validateMethod(method, beanName);

            registrations.add(new ListenerRegistration(
                    bean,
                    beanName,
                    method,
                    annotation.topic(),
                    annotation.signalName(),
                    method.getParameterTypes()[0],
                    annotation.groupIdSuffix()
            ));

            logger.debug("Discovered @MaestroSignalListener on {}.{} → topic='{}', signalName='{}'",
                    beanName, method.getName(), annotation.topic(), annotation.signalName());
        }
        return bean;
    }

    // ── SmartInitializingSingleton ─────────��──────────────────────────────

    @Override
    @SuppressWarnings("unchecked")
    public void afterSingletonsInstantiated() {
        if (registrations.isEmpty()) {
            return;
        }

        var ctx = Objects.requireNonNull(applicationContext,
                "ApplicationContext not set — was setApplicationContext() called?");

        var consumerFactory = (ConsumerFactory<String, byte[]>) ctx.getBean(ConsumerFactory.class);
        var executor = ctx.getBean(WorkflowExecutor.class);
        var objectMapper = ctx.getBean(ObjectMapper.class);
        var baseGroup = resolveBaseConsumerGroup(ctx);

        logger.info("Activating {} @MaestroSignalListener registration(s)", registrations.size());

        for (var reg : registrations) {
            var container = createListenerContainer(reg, consumerFactory, executor, objectMapper, baseGroup);
            containers.add(container);
            container.start();

            logger.info("Started Kafka consumer for @MaestroSignalListener {}.{} on topic '{}'",
                    reg.beanName(), reg.method().getName(), reg.topic());
        }
    }

    // ── ApplicationContextAware ──���───────────────────────────────────────

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    // ── DisposableBean ─────���─────────────────────────────────────────────

    @Override
    public void destroy() {
        logger.info("Stopping {} @MaestroSignalListener container(s)", containers.size());
        for (var container : containers) {
            try {
                container.stop();
            } catch (Exception e) {
                logger.warn("Error stopping signal listener container: {}", e.getMessage(), e);
            }
        }
        containers.clear();
    }

    // ── Internal helpers ─────────────────────────���───────────────────────

    private void validateMethod(Method method, String beanName) {
        if (!Modifier.isPublic(method.getModifiers())) {
            throw new BeanInitializationException(
                    "@MaestroSignalListener method " + beanName + "." + method.getName()
                            + " must be public");
        }
        if (method.getParameterCount() != 1) {
            throw new BeanInitializationException(
                    "@MaestroSignalListener method " + beanName + "." + method.getName()
                            + " must have exactly one parameter (the Kafka message type), but has "
                            + method.getParameterCount());
        }
        if (!SignalRouting.class.equals(method.getReturnType())) {
            throw new BeanInitializationException(
                    "@MaestroSignalListener method " + beanName + "." + method.getName()
                            + " must return SignalRouting, but returns "
                            + method.getReturnType().getName());
        }
    }

    private ConcurrentMessageListenerContainer<String, byte[]> createListenerContainer(
            ListenerRegistration reg,
            ConsumerFactory<String, byte[]> consumerFactory,
            WorkflowExecutor executor,
            ObjectMapper objectMapper,
            String baseGroup
    ) {
        var groupId = reg.groupIdSuffix().isEmpty()
                ? baseGroup
                : baseGroup + "-" + reg.groupIdSuffix();

        var containerProps = new ContainerProperties(reg.topic());
        containerProps.setGroupId(groupId);
        containerProps.setAckMode(ContainerProperties.AckMode.RECORD);
        containerProps.setMessageListener(
                (MessageListener<String, byte[]>) record -> handleMessage(record.value(), reg, executor, objectMapper));

        return new ConcurrentMessageListenerContainer<>(consumerFactory, containerProps);
    }

    private void handleMessage(
            byte[] value,
            ListenerRegistration reg,
            WorkflowExecutor executor,
            ObjectMapper objectMapper
    ) {
        try {
            // Deserialize the Kafka message to the method's parameter type
            var event = objectMapper.readValue(value, reg.parameterType());

            // Invoke the annotated method to get signal routing
            var routing = (SignalRouting) reg.method().invoke(reg.bean(), event);

            if (routing == null) {
                throw new IllegalStateException(
                        "@MaestroSignalListener " + reg.beanName() + "." + reg.method().getName()
                                + " returned null SignalRouting for topic '" + reg.topic()
                                + "' — signals must not be discarded");
            }

            // Deliver the signal to the workflow engine
            executor.deliverSignal(routing.workflowId(), reg.signalName(), routing.payload());

            logger.debug("Delivered signal '{}' to workflow '{}' via @MaestroSignalListener {}.{}",
                    reg.signalName(), routing.workflowId(), reg.beanName(), reg.method().getName());

        } catch (InvocationTargetException e) {
            // Unwrap to log the actual cause from the user's listener method.
            // Re-throw to allow Kafka error handling (retry, DLQ) —
            // signals must not be silently discarded.
            var cause = e.getCause() != null ? e.getCause() : e;
            logger.error("Error processing message from topic '{}' in @MaestroSignalListener {}.{}: {}",
                    reg.topic(), reg.beanName(), reg.method().getName(), cause.getMessage(), cause);
            throw new RuntimeException("Signal processing failed — signals must not be discarded", cause);
        } catch (Exception e) {
            logger.error("Error processing message from topic '{}' in @MaestroSignalListener {}.{}: {}",
                    reg.topic(), reg.beanName(), reg.method().getName(), e.getMessage(), e);
            throw new RuntimeException("Signal processing failed — signals must not be discarded", e);
        }
    }

    private String resolveBaseConsumerGroup(ApplicationContext ctx) {
        var properties = ctx.getBean(io.b2mash.maestro.spring.config.MaestroProperties.class);
        var messaging = properties.getMessaging();
        if (messaging.consumerGroup() != null) {
            return messaging.consumerGroup();
        }
        var serviceName = properties.getServiceName();
        if (serviceName == null || serviceName.isBlank()) {
            throw new BeanInitializationException(
                    "maestro.service-name must be set for @MaestroSignalListener processing");
        }
        return "maestro-" + serviceName;
    }
}
