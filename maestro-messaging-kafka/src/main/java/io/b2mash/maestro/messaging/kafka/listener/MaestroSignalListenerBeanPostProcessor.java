package io.b2mash.maestro.messaging.kafka.listener;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.messaging.kafka.KafkaContainerGroup;
import io.b2mash.maestro.messaging.kafka.KafkaMessagingConfig;
import io.b2mash.maestro.messaging.kafka.KafkaRedeliveryErrorHandlers;
import io.b2mash.maestro.messaging.kafka.KafkaSignalIdentity;
import io.b2mash.maestro.messaging.kafka.KafkaTopicVerifier;
import io.b2mash.maestro.messaging.kafka.KafkaTracePropagation;
import io.b2mash.maestro.messaging.kafka.KafkaWorkflowMessaging;
import io.b2mash.maestro.messaging.kafka.MaestroKafkaBeans;
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
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.time.Duration;
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
 *   <li><b>Topic verification</b> ({@code afterSingletonsInstantiated}, first
 *       thing): one {@link KafkaTopicVerifier} pass over every topic this
 *       service needs — the engine's own signal topic and {@code .DLT}, the
 *       admin-events topic when lifecycle events are enabled, and every
 *       registration's topic and {@code .DLT} —
 *       governed by {@code maestro.messaging.topics.verify-on-startup}. This
 *       runs whether or not there are registrations: it is the Kafka
 *       transport's one startup gate, placed here because this is the only
 *       bean that sees all listener topics, and it runs during context
 *       refresh, before any container starts and before
 *       {@code StartupRecoveryRunner} resumes a workflow.</li>
 *   <li><b>Activation</b> ({@code afterSingletonsInstantiated}): Resolves
 *       dependencies from the {@link ApplicationContext} and starts a Kafka
 *       consumer container for each registration.</li>
 * </ol>
 *
 * <h2>Per-Message Flow</h2>
 * <pre>
 * Kafka message (byte[])
 *   → resolve the message id (maestro-message-id header, else the record's
 *     topic-id coordinate — {@link KafkaSignalIdentity})
 *   → deserialize to the method's generic parameter type (Jackson 3)
 *   → invoke annotated method
 *   → extract {@link SignalRouting} (workflowId + payload)
 *   → call {@link WorkflowExecutor#deliverSignal(String, String, Object, String)}
 * </pre>
 *
 * <h2>Idempotency</h2>
 * <p>The engine persists at most one signal per {@code (workflowId,
 * signalName, messageId)}, so a record redelivered after a crash or rebalance
 * (its signal row committed, its offset not) is suppressed and acknowledged.
 * A <em>republished</em> record (a producer retry at a new offset) is
 * suppressed only when the producer set the
 * {@code maestro-message-id} header ({@code MaestroSignalHeaders}); without it
 * the republish is a second signal. Two registrations that route the same
 * record to the same workflow and signal name share its coordinate, so the
 * second delivery is a duplicate.
 *
 * <h2>Failure Handling</h2>
 * <p>Anything that throws — a deserialization failure, the user's routing
 * method, or {@code deliverSignal} itself — propagates out of the listener, so
 * the offset is not committed. The container's error handler redelivers the
 * record with exponential backoff and, once the attempt budget is spent,
 * publishes it to {@code <topic>} + the configured dead-letter suffix. A signal
 * is therefore never acknowledged unprocessed, and a poison record is parked
 * rather than skipped or looped on forever. Setting
 * {@code maestro.messaging.redelivery.enabled=false} is the operator's
 * explicit opt-out of all of that: the container gets a zero-retry error
 * handler with no dead-letter publishing instead, restoring at-most-once
 * handler semantics, and the {@code .DLT} topics drop out of the set the
 * startup verification requires.
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

    /** Mirrors {@code KafkaWorkflowMessaging.AUTH_EXCEPTION_RETRY_INTERVAL} (package-private there). */
    private static final java.time.Duration AUTH_EXCEPTION_RETRY_INTERVAL = java.time.Duration.ofSeconds(10);

    private final List<ListenerRegistration> registrations = new ArrayList<>();
    private final List<ConcurrentMessageListenerContainer<String, byte[]>> containers = new CopyOnWriteArrayList<>();
    /** The listener containers as one member of the engine's lifecycle registry (P1 task 5, Issue 69). */
    private final KafkaContainerGroup group = new KafkaContainerGroup("signal-listener-containers");
    private @Nullable ApplicationContext applicationContext;

    /**
     * Internal registration for a discovered {@link MaestroSignalListener} method.
     *
     * <p>{@code parameterType} is the method's <em>generic</em> parameter type
     * ({@link Method#getGenericParameterTypes()}), so a {@code List<Order>}
     * parameter receives {@code Order}s rather than {@code LinkedHashMap}s
     * (Issue 92, "Kafka listener payload type"; the listener-side twin of Issue
     * 44). A raw type, {@code Object} or a type variable reads as the erased
     * class read before.
     */
    private record ListenerRegistration(
            Object bean,
            String beanName,
            Method method,
            String topic,
            String signalName,
            Type parameterType,
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
                    method.getGenericParameterTypes()[0],
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
        var ctx = Objects.requireNonNull(applicationContext,
                "ApplicationContext not set — was setApplicationContext() called?");

        var consumerFactory = resolveConsumerFactory(ctx);
        var properties = ctx.getBean(MaestroProperties.class);
        var messagingConfig = ctx.getBean(KafkaMessagingConfig.class);

        // The transport's one startup gate — before the early return below, because
        // the engine's own signal topic (and the admin-events topic, when lifecycle
        // events are enabled) must exist
        // whether or not this service declares any @MaestroSignalListener.
        var verification = verifyTopics(messagingConfig, consumerFactory, properties);
        var fatalOnMissing = KafkaTopicVerifier.fatalOnMissing(messagingConfig.verifyOnStartup(), verification);

        // The engine's own signal channel is subscribed later, from
        // SignalSubscriptionRunner; hand it the same outcome so its container
        // follows the same rule (fatal only when every topic was confirmed).
        var engineMessaging = ctx.getBeanProvider(KafkaWorkflowMessaging.class).getIfAvailable();
        // P1 task 5 (Issue 69): Maestro's containers join the engine's
        // lifecycle registry before any of them starts, so the engine's
        // shutdown stops them at the start of its drain, on its one deadline —
        // not at bean destruction, after shutdown() returned (the engine's
        // container) or after the DataSource closed (these).
        var engine = ctx.getBeanProvider(WorkflowExecutor.class).getIfAvailable();
        if (engineMessaging != null) {
            engineMessaging.applyTopicVerification(verification);
            if (engine != null) {
                engineMessaging.joinLifecycle(engine.lifecycle());
            }
        }

        if (registrations.isEmpty()) {
            return;
        }

        var executor = ctx.getBean(WorkflowExecutor.class);
        if (!group.join(executor.lifecycle())) {
            logger.info("Not starting {} @MaestroSignalListener container(s): the engine is shutting down",
                    registrations.size());
            return;
        }
        var objectMapper = ctx.getBean(ObjectMapper.class);
        var baseGroup = resolveBaseConsumerGroup(ctx);
        var kafkaTemplate = resolveKafkaTemplate(ctx);
        var redelivery = properties.getMessaging().redelivery();
        var tracePropagation = ctx.getBeanProvider(KafkaTracePropagation.class).getIfAvailable();
        var listenerObservation = ctx.getEnvironment()
                .getProperty("spring.kafka.listener.observation-enabled", Boolean.class);

        // One topic-id cache for every listener container (KafkaSignalIdentity).
        var signalIdentity = new KafkaSignalIdentity(consumerFactory);

        logger.info("Activating {} @MaestroSignalListener registration(s)", registrations.size());

        for (var reg : registrations) {
            var container = createListenerContainer(reg, consumerFactory, executor, objectMapper, baseGroup,
                    kafkaTemplate, redelivery, fatalOnMissing, tracePropagation, listenerObservation,
                    signalIdentity);
            containers.add(container);
            if (!group.start(container)) {
                logger.info("Not starting the remaining @MaestroSignalListener containers: the engine is shutting "
                        + "down");
                return;
            }

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

    /**
     * A backstop for a context whose engine did not stop the containers:
     * requests the stop and returns at once, never waiting. The engine's
     * shutdown stops them at the start of its drain (P1 task 5, Issue 69).
     */
    @Override
    public void destroy() {
        group.requestStop();
        if (!group.joined()) {
            // No engine owns them: no shutdown deadline; wait as the blocking
            // stop did before.
            group.awaitStopped(Duration.ofSeconds(10));
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

    /**
     * One {@link KafkaTopicVerifier} pass over every topic this service needs.
     *
     * @return what the probe confirmed, for {@link KafkaTopicVerifier#fatalOnMissing}
     * @throws IllegalStateException under {@code verify-on-startup=fail} when a
     *                               required topic is confirmed missing — fails
     *                               context refresh with every missing topic named
     */
    private KafkaTopicVerifier.Result verifyTopics(
            KafkaMessagingConfig messagingConfig,
            ConsumerFactory<String, byte[]> consumerFactory,
            MaestroProperties properties
    ) {
        var listenerTopics = registrations.stream().map(ListenerRegistration::topic).toList();
        var required = KafkaTopicVerifier.requiredTopics(
                messagingConfig,
                properties.getServiceName(),
                properties.getAdmin().events().enabled(),
                listenerTopics);
        return KafkaTopicVerifier.verifyOnStartup(messagingConfig, consumerFactory, required);
    }

    private ConcurrentMessageListenerContainer<String, byte[]> createListenerContainer(
            ListenerRegistration reg,
            ConsumerFactory<String, byte[]> consumerFactory,
            WorkflowExecutor executor,
            ObjectMapper objectMapper,
            String baseGroup,
            KafkaOperations<String, byte[]> kafkaTemplate,
            MaestroProperties.RedeliveryProperties redelivery,
            boolean fatalOnMissing,
            @Nullable KafkaTracePropagation tracePropagation,
            @Nullable Boolean listenerObservation,
            KafkaSignalIdentity signalIdentity
    ) {
        var groupId = reg.groupIdSuffix().isEmpty()
                ? baseGroup
                : baseGroup + "-" + reg.groupIdSuffix();

        var containerProps = new ContainerProperties(reg.topic());
        containerProps.setGroupId(groupId);
        containerProps.setAckMode(ContainerProperties.AckMode.RECORD);
        // Stop after the record in hand (review M1; as KafkaWorkflowMessaging): without it a stopping
        // consumer invokes the listener for the rest of its last poll — up to
        // max.poll.records — which can outlast the shutdown deadline and reach
        // a closed DataSource. The records not handled stay uncommitted (AckMode
        // RECORD commits each handled one) and go to the partition's next owner.
        containerProps.setStopImmediate(true);
        // Same two settings, same reasons, as KafkaWorkflowMessaging.createContainer:
        // fatal on a missing topic only under verify-on-startup=fail AND only when
        // the startup probe confirmed every topic present (KafkaTopicVerifier
        // .fatalOnMissing) — Spring Kafka counts an unreachable broker as
        // "missing", so an inconclusive probe must leave this off or the
        // "starting anyway" WARN would be followed by a failed start(); and an
        // auth exception from poll() retries instead of stopping the container
        // silently.
        containerProps.setMissingTopicsFatal(fatalOnMissing);
        containerProps.setAuthExceptionRetryInterval(AUTH_EXCEPTION_RETRY_INTERVAL);
        // Same rule as KafkaMessagingAutoConfiguration.observationEnabled — an
        // explicit spring.kafka.listener.observation-enabled always wins,
        // otherwise observation defaults on exactly when tracing is wired.
        // Duplicated rather than shared across packages: see that method's
        // Javadoc for why.
        containerProps.setObservationEnabled(
                listenerObservation != null ? listenerObservation : tracePropagation != null);
        containerProps.setConsumerRebalanceListener(signalIdentity.rebalanceListener());
        // Resolved once per registration, not per record.
        var payloadType = objectMapper.constructType(reg.parameterType());
        containerProps.setMessageListener((MessageListener<String, byte[]>) record -> {
            // A domain record has no envelope: its identity is the
            // maestro-message-id header, else its topic-id coordinate. Resolved
            // before the user's method runs, so a failure there is still a
            // redelivery of the same identity.
            var messageId = signalIdentity.resolve(null, record);
            if (tracePropagation != null) {
                tracePropagation.runWithExtractedContext(record.headers(),
                        () -> handleMessage(record.value(), messageId, reg, payloadType, executor, objectMapper));
            } else {
                handleMessage(record.value(), messageId, reg, payloadType, executor, objectMapper);
            }
        });

        var container = new ConcurrentMessageListenerContainer<>(consumerFactory, containerProps);
        if (redelivery.enabled()) {
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
        } else {
            // maestro.messaging.redelivery.enabled=false: the operator's explicit
            // choice to restore at-most-once handler semantics — zero retries, no
            // DeadLetterPublishingRecoverer, a failing record is logged and skipped.
            container.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(0L, 0L)));
        }
        return container;
    }

    /**
     * Resolves the producer used to publish exhausted records: Maestro's own
     * template, by name only.
     *
     * <p>There is no by-type fallback. The name always exists (the
     * auto-configuration registers it unless a host replaced it under the same
     * name), and by type this lookup would now resolve Spring Boot's
     * {@code kafkaTemplate}, the only default candidate, which is built from
     * the host's {@code spring.kafka.producer.*} (by default a
     * {@code StringSerializer}, which cannot send the {@code byte[]} records a
     * dead letter carries).
     */
    @SuppressWarnings("unchecked")
    private static KafkaOperations<String, byte[]> resolveKafkaTemplate(ApplicationContext ctx) {
        return (KafkaOperations<String, byte[]>) ctx.getBean(MaestroKafkaBeans.TEMPLATE, KafkaOperations.class);
    }

    /**
     * Resolves the consumer factory used to build listener containers:
     * Maestro's own factory, by name only (finding F3).
     *
     * <p>There is no by-type fallback, for the same reason as
     * {@link #resolveKafkaTemplate}: the name always exists, and by type this
     * lookup would now resolve Spring Boot's {@code kafkaConsumerFactory},
     * which is built from the host's {@code spring.kafka.consumer.*} and
     * carries neither the engine's forced {@code ByteArrayDeserializer} nor
     * its group id.
     */
    @SuppressWarnings("unchecked")
    private static ConsumerFactory<String, byte[]> resolveConsumerFactory(ApplicationContext ctx) {
        return (ConsumerFactory<String, byte[]>) ctx.getBean(MaestroKafkaBeans.CONSUMER_FACTORY, ConsumerFactory.class);
    }

    private void handleMessage(
            byte[] value,
            @Nullable String messageId,
            ListenerRegistration reg,
            JavaType payloadType,
            WorkflowExecutor executor,
            ObjectMapper objectMapper
    ) {
        try {
            // Deserialize the Kafka message to the method's generic parameter type
            var event = objectMapper.readValue(value, payloadType);

            // Invoke the annotated method to get signal routing
            var routing = (SignalRouting) reg.method().invoke(reg.bean(), event);

            if (routing == null) {
                throw new IllegalStateException(
                        "@MaestroSignalListener " + reg.beanName() + "." + reg.method().getName()
                                + " returned null SignalRouting for topic '" + reg.topic()
                                + "' — signals must not be discarded");
            }

            // Deliver the signal to the workflow engine. A duplicate (same
            // identity already stored) returns false and is acked like a success.
            var persisted = executor.deliverSignal(routing.workflowId(), reg.signalName(), routing.payload(),
                    messageId);

            logger.debug("{} signal '{}' to workflow '{}' via @MaestroSignalListener {}.{} (messageId={})",
                    persisted ? "Delivered" : "Suppressed duplicate", reg.signalName(), routing.workflowId(),
                    reg.beanName(), reg.method().getName(), messageId);

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
