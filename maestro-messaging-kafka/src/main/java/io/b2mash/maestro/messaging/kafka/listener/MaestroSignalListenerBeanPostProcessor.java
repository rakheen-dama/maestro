package io.b2mash.maestro.messaging.kafka.listener;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.messaging.kafka.KafkaRedeliveryErrorHandlers;
import io.b2mash.maestro.messaging.kafka.KafkaTracePropagation;
import io.b2mash.maestro.spring.annotation.MaestroSignalListener;
import io.b2mash.maestro.spring.annotation.SignalRouting;
import io.b2mash.maestro.spring.config.MaestroProperties;
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
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
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
 * <h2>Failure Handling</h2>
 * <p>Anything that throws — a deserialization failure, the user's routing
 * method, or {@code deliverSignal} itself — propagates out of the listener, so
 * the offset is not committed. The container's error handler redelivers the
 * record with exponential backoff and, once the attempt budget is spent,
 * publishes it to {@code <topic>} + the configured dead-letter suffix. A signal
 * is therefore never acknowledged unprocessed, and a poison record is parked
 * rather than skipped or looped on forever.
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

        var consumerFactory = resolveConsumerFactory(ctx);
        var executor = ctx.getBean(WorkflowExecutor.class);
        var objectMapper = ctx.getBean(ObjectMapper.class);
        var baseGroup = resolveBaseConsumerGroup(ctx);
        var kafkaTemplate = resolveKafkaTemplate(ctx);
        var redelivery = ctx.getBean(MaestroProperties.class).getMessaging().redelivery();
        var tracePropagation = ctx.getBeanProvider(KafkaTracePropagation.class).getIfAvailable();
        var listenerObservation = ctx.getEnvironment()
                .getProperty("spring.kafka.listener.observation-enabled", Boolean.class);

        logger.info("Activating {} @MaestroSignalListener registration(s)", registrations.size());

        for (var reg : registrations) {
            var container = createListenerContainer(reg, consumerFactory, executor, objectMapper, baseGroup,
                    kafkaTemplate, redelivery, tracePropagation, listenerObservation);
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

    /**
     * The consumer containers started by {@link #afterSingletonsInstantiated()}.
     *
     * <p>Package-visible for {@code MaestroSignalListenerContainerConfigTest} —
     * no production caller needs container-level access, so this is
     * deliberately not public API.
     *
     * @return an immutable snapshot of the active containers
     */
    List<ConcurrentMessageListenerContainer<String, byte[]>> containersForTesting() {
        return List.copyOf(containers);
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
            String baseGroup,
            KafkaOperations<String, byte[]> kafkaTemplate,
            MaestroProperties.RedeliveryProperties redelivery,
            @Nullable KafkaTracePropagation tracePropagation,
            @Nullable Boolean listenerObservation
    ) {
        var groupId = reg.groupIdSuffix().isEmpty()
                ? baseGroup
                : baseGroup + "-" + reg.groupIdSuffix();

        var containerProps = new ContainerProperties(reg.topic());
        containerProps.setGroupId(groupId);
        containerProps.setAckMode(ContainerProperties.AckMode.RECORD);
        // Same rule as KafkaMessagingAutoConfiguration.observationEnabled — an
        // explicit spring.kafka.listener.observation-enabled always wins,
        // otherwise observation defaults on exactly when tracing is wired.
        // Duplicated rather than shared across packages: see that method's
        // Javadoc for why.
        containerProps.setObservationEnabled(
                listenerObservation != null ? listenerObservation : tracePropagation != null);
        containerProps.setMessageListener((MessageListener<String, byte[]>) record -> {
            if (tracePropagation != null) {
                tracePropagation.runWithExtractedContext(record.headers(),
                        () -> handleMessage(record.value(), reg, executor, objectMapper));
            } else {
                handleMessage(record.value(), reg, executor, objectMapper);
            }
        });

        var container = new ConcurrentMessageListenerContainer<>(consumerFactory, containerProps);
        // Without this the default error handler logs and skips once its
        // retries are exhausted — the offset is committed and the signal is
        // gone. A poison record must be parked on the dead-letter topic
        // instead, where it stays inspectable and replayable.
        container.setCommonErrorHandler(KafkaRedeliveryErrorHandlers.deadLettering(
                kafkaTemplate,
                redelivery.maxAttempts(),
                redelivery.initialInterval(),
                redelivery.multiplier(),
                redelivery.maxInterval(),
                redelivery.deadLetterSuffix()));
        return container;
    }

    /**
     * Resolves the producer used to publish exhausted records.
     *
     * <p>Maestro's own template is looked up by name first, so an application
     * that defines {@code KafkaTemplate} beans of its own does not make the
     * lookup ambiguous.
     */
    @SuppressWarnings("unchecked")
    private static KafkaOperations<String, byte[]> resolveKafkaTemplate(ApplicationContext ctx) {
        if (ctx.containsBean("maestroKafkaTemplate")) {
            return (KafkaOperations<String, byte[]>) ctx.getBean("maestroKafkaTemplate", KafkaOperations.class);
        }
        return (KafkaOperations<String, byte[]>) ctx.getBean(KafkaTemplate.class);
    }

    /**
     * Resolves the consumer factory used to build listener containers.
     *
     * <p>Maestro's own factory is looked up by name first (finding F3), so an
     * application that defines additional {@code ConsumerFactory} beans of its
     * own does not make the by-type lookup ambiguous — mirroring
     * {@link #resolveKafkaTemplate}.
     */
    @SuppressWarnings("unchecked")
    private static ConsumerFactory<String, byte[]> resolveConsumerFactory(ApplicationContext ctx) {
        if (ctx.containsBean("maestroKafkaConsumerFactory")) {
            return (ConsumerFactory<String, byte[]>)
                    ctx.getBean("maestroKafkaConsumerFactory", ConsumerFactory.class);
        }
        return (ConsumerFactory<String, byte[]>) ctx.getBean(ConsumerFactory.class);
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
        var properties = ctx.getBean(MaestroProperties.class);
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
