package io.b2mash.maestro.messaging.kafka;

import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Apache Kafka implementation of the {@link WorkflowMessaging} SPI.
 *
 * <p>Uses {@code KafkaTemplate<String, byte[]>} for publishing and
 * {@link ConcurrentMessageListenerContainer} for consuming. Messages are
 * serialized with Jackson 3 at the application layer, keeping the Kafka
 * value type as raw bytes for efficiency.
 *
 * <h2>Topic Naming</h2>
 * <ul>
 *   <li>Signals: {@code maestro.signals.{serviceName}} (or fixed override)</li>
 *   <li>Admin events: {@code maestro.admin.events} (configurable)</li>
 * </ul>
 *
 * <h2>Trace Propagation</h2>
 * <p>When a {@link KafkaTracePropagation} collaborator is supplied, every
 * published record carries the W3C trace context of the span active at publish
 * time, and every consumed record's handler runs under the remote context it
 * carried — see that class for the exact wire contract. Without the
 * collaborator the wire format is byte-identical to a build with no tracing.
 *
 * <h2>Partition Key</h2>
 * <p>All messages are keyed by {@code workflowId} to guarantee per-workflow
 * ordering within a topic partition.
 *
 * <h2>Handler Failure</h2>
 * <p>Handler exceptions are never swallowed: they reach the container, whose
 * error handler redelivers the record with exponential backoff and, once the
 * attempt budget is spent, publishes it to {@code <topic><deadLetterSuffix>}.
 * The offset is committed only after the handler succeeds or the record is
 * dead-lettered, so a message is never acknowledged unprocessed. A handler may
 * therefore be re-invoked with the same message.
 *
 * <h2>Message identity</h2>
 * <p>Every message handed to a signal handler carries a resolved
 * {@link SignalMessage#messageId()} — the envelope's, else the
 * {@value MaestroSignalHeaders#MESSAGE_ID} header's, else the record's
 * topic-id/partition/offset coordinate (see {@link KafkaSignalIdentity}) —
 * which the engine's handler delivers with, so a redelivered record is
 * suppressed rather than persisted as a second signal. {@link #publishSignal}
 * writes the envelope id into the header as well. A record for which none of
 * the three can be resolved is delivered without an id and is not
 * deduplicated.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Publishing can be called concurrently from
 * multiple virtual threads. Listener containers are managed in a
 * {@link CopyOnWriteArrayList} and stopped on {@link #destroy()}.
 *
 * @see WorkflowMessaging
 * @see KafkaMessagingConfig
 * @see KafkaRedeliveryErrorHandlers
 */
public final class KafkaWorkflowMessaging implements WorkflowMessaging, DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(KafkaWorkflowMessaging.class);

    /** Kafka's own default for {@code max.block.ms}, used when the producer config does not set one. */
    static final long KAFKA_DEFAULT_MAX_BLOCK_MS = 60_000L;

    /** Kafka's own default for {@code delivery.timeout.ms}, used when the producer config does not set one. */
    static final long KAFKA_DEFAULT_DELIVERY_TIMEOUT_MS = 120_000L;

    /** Added on top of the producer's own budget so it has definitively given up before we throw. */
    static final Duration SEND_TIMEOUT_GRACE = Duration.ofSeconds(5);

    /** How often a container whose poll hit an auth exception retries, instead of stopping. */
    static final Duration AUTH_EXCEPTION_RETRY_INTERVAL = Duration.ofSeconds(10);

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final ConsumerFactory<String, byte[]> consumerFactory;
    private final ObjectMapper objectMapper;
    private final KafkaMessagingConfig config;
    private final @Nullable KafkaTracePropagation tracePropagation;
    private final Duration sendTimeout;
    private final KafkaSignalIdentity signalIdentity;
    private final List<ConcurrentMessageListenerContainer<String, byte[]>> containers =
            new CopyOnWriteArrayList<>();

    /**
     * Whether containers built from here are fatal on a missing topic. Set once,
     * during context refresh, by {@link #applyTopicVerification} — before
     * {@code SignalSubscriptionRunner} ever calls {@link #subscribeSignals} —
     * and {@code false} until then: nothing confirmed, nothing fatal.
     */
    private volatile boolean missingTopicsFatal;

    /**
     * Creates a new Kafka-based workflow messaging implementation.
     *
     * @param kafkaTemplate   the Kafka template for publishing messages
     * @param consumerFactory the consumer factory for creating listener containers
     * @param objectMapper    Jackson 3 ObjectMapper for serialization
     * @param config          resolved topic and consumer group configuration
     */
    public KafkaWorkflowMessaging(
            KafkaTemplate<String, byte[]> kafkaTemplate,
            ConsumerFactory<String, byte[]> consumerFactory,
            ObjectMapper objectMapper,
            KafkaMessagingConfig config
    ) {
        this(kafkaTemplate, consumerFactory, objectMapper, config, null);
    }

    /**
     * Creates a new Kafka-based workflow messaging implementation with W3C trace
     * context propagation.
     *
     * @param kafkaTemplate    the Kafka template for publishing messages
     * @param consumerFactory  the consumer factory for creating listener containers
     * @param objectMapper     Jackson 3 ObjectMapper for serialization
     * @param config           resolved topic and consumer group configuration
     * @param tracePropagation injects W3C headers on publish and restores the
     *                         remote context around each handler call; when
     *                         {@code null} the wire format is byte-identical to
     *                         a build with no tracing at all
     */
    public KafkaWorkflowMessaging(
            KafkaTemplate<String, byte[]> kafkaTemplate,
            ConsumerFactory<String, byte[]> consumerFactory,
            ObjectMapper objectMapper,
            KafkaMessagingConfig config,
            @Nullable KafkaTracePropagation tracePropagation
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.consumerFactory = consumerFactory;
        this.objectMapper = objectMapper;
        this.config = config;
        this.tracePropagation = tracePropagation;
        this.sendTimeout = sendTimeoutFor(producerConfigOf(kafkaTemplate));
        this.signalIdentity = new KafkaSignalIdentity(consumerFactory);
    }

    /**
     * Records what the startup topic verification confirmed, so the consumer
     * containers {@link #subscribeSignals} builds can be made fatal on a
     * missing topic — only under {@code verify-on-startup=fail} and only when
     * the probe confirmed every required topic present
     * ({@link KafkaTopicVerifier#fatalOnMissing}). Called once by
     * {@code MaestroSignalListenerBeanPostProcessor} during context refresh,
     * before any subscription; an adapter nobody calls this on (a directly
     * constructed one) keeps tolerant containers.
     *
     * @param verification the outcome of {@link KafkaTopicVerifier#verifyOnStartup}
     */
    public void applyTopicVerification(KafkaTopicVerifier.Result verification) {
        this.missingTopicsFatal = KafkaTopicVerifier.fatalOnMissing(config.verifyOnStartup(), verification);
    }

    /**
     * @return whether containers built from now on are fatal on a missing topic;
     *         package-visible for tests
     */
    boolean missingTopicsFatal() {
        return missingTopicsFatal;
    }

    /**
     * @return how long {@link #publishSignal} waits for the broker's acknowledgement
     *         before throwing; package-visible for tests
     */
    Duration sendTimeout() {
        return sendTimeout;
    }

    /**
     * The bound {@link #publishSignal} waits for an acknowledgement, derived
     * from the producer's own delivery budget: {@code max.block.ms} (how long
     * {@code send()} may block fetching metadata or buffer space) plus
     * {@code delivery.timeout.ms} (how long the producer then retries the
     * record) plus a {@value #SEND_TIMEOUT_GRACE} grace. Waiting at least that
     * long is what makes the "no late delivery after this throws" guarantee
     * true: once the producer's own budget is spent it has failed the record's
     * future itself, so a timeout here can only mean the producer is wedged,
     * never that the record is still queued and about to land. A shorter
     * fixed wait — the previous 30 s against a 120 s default delivery
     * timeout — could report failure for a record Kafka went on to deliver,
     * inviting a duplicate on retry.
     *
     * @param producerConfig the producer factory's configuration; values may
     *                       be numbers or strings, as Boot binds them; absent or
     *                       unparsable values fall back to Kafka's defaults
     *                       ({@value #KAFKA_DEFAULT_MAX_BLOCK_MS} /
     *                       {@value #KAFKA_DEFAULT_DELIVERY_TIMEOUT_MS} ms)
     * @return the acknowledgement wait
     */
    static Duration sendTimeoutFor(Map<String, Object> producerConfig) {
        var maxBlock = millis(producerConfig.get(ProducerConfig.MAX_BLOCK_MS_CONFIG), KAFKA_DEFAULT_MAX_BLOCK_MS);
        var deliveryTimeout = millis(producerConfig.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG),
                KAFKA_DEFAULT_DELIVERY_TIMEOUT_MS);
        return Duration.ofMillis(maxBlock + deliveryTimeout).plus(SEND_TIMEOUT_GRACE);
    }

    private static long millis(@Nullable Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    private static Map<String, Object> producerConfigOf(KafkaTemplate<String, byte[]> template) {
        try {
            var factory = template.getProducerFactory();
            return factory.getConfigurationProperties();
        } catch (RuntimeException e) {
            // A template whose factory exposes no configuration (a test double,
            // say) — fall back to Kafka's defaults rather than fail construction.
            return Map.of();
        }
    }

    // ── Publishing ───────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Blocks until the broker acknowledges the record ({@code acks=all}) or
     * the producer's delivery budget — {@code max.block.ms} +
     * {@code delivery.timeout.ms} + 5 s, see {@link #sendTimeoutFor} — is
     * spent, whichever comes first.
     *
     * @throws IllegalStateException if the record was not acknowledged: the
     *                               producer reported a failure, the wait
     *                               expired, or the thread was interrupted.
     *                               <b>Once this throws the record will not be
     *                               delivered later</b> — the wait outlasts the
     *                               producer's own retry budget, after which the
     *                               producer has failed the record itself — so a
     *                               caller may retry without producing a
     *                               duplicate from this attempt
     */
    @Override
    public void publishSignal(String serviceName, SignalMessage message) {
        var topic = resolveSignalTopic(serviceName);
        var key = message.workflowId();
        var record = tracedRecord(topic, key, serialize(message));
        // The envelope already carries the id; the header repeats it so a
        // consumer that reads headers only (a tap, a bridge) sees it too.
        if (message.messageId() != null && !message.messageId().isBlank()) {
            MaestroSignalHeaders.setMessageId(record.headers(), message.messageId());
        }
        send(topic, key, record);
    }

    @Override
    public void publishLifecycleEvent(WorkflowLifecycleEvent event) {
        var topic = config.adminEventsTopic();
        var key = event.workflowId();
        try {
            kafkaTemplate.send(tracedRecord(topic, key, serialize(event)));
        } catch (Exception e) {
            // SPI contract: lifecycle event failures must not interrupt workflow execution
            logger.warn("Failed to publish lifecycle event {} for workflow '{}' to topic '{}'",
                    event.eventType(), key, topic, e);
        }
    }

    // ── Subscribing ──────────────────────────────────────────────────────

    @Override
    public void subscribeSignals(String serviceName, Consumer<SignalMessage> handler) {
        var topic = resolveSignalTopic(serviceName);
        // Topic existence (this topic and its .DLT) was verified once at startup by
        // KafkaTopicVerifier, from MaestroSignalListenerBeanPostProcessor — not
        // per subscription here; the container below is additionally started
        // with missingTopicsFatal when that verification confirmed every topic
        // under verify-on-startup=fail (see applyTopicVerification).
        //
        // Nothing is caught here on purpose: a handler failure — or an
        // undeserializable record — must reach the container's error handler so
        // the offset is not committed. See the class Javadoc. This is also the
        // only place the raw ConsumerRecord — and therefore its W3C headers — is
        // visible before the payload-typed handler runs.
        var container = createContainer(topic, record -> {
            var envelope = deserialize(record.value(), SignalMessage.class);
            // Identity: envelope field → maestro-message-id header → the
            // record's topic-id coordinate (KafkaSignalIdentity). The handler
            // delivers with it, so a redelivered record is suppressed.
            var messageId = signalIdentity.resolve(envelope.messageId(), record);
            var message = java.util.Objects.equals(messageId, envelope.messageId())
                    ? envelope : envelope.withMessageId(messageId);
            if (tracePropagation == null) {
                handler.accept(message);
            } else {
                tracePropagation.runWithExtractedContext(record.headers(), () -> handler.accept(message));
            }
        });
        containers.add(container);
        container.start();
        logger.info("Subscribed to signals for service '{}' on topic '{}'", serviceName, topic);
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    @Override
    public void destroy() {
        logger.info("Stopping {} Kafka listener container(s)", containers.size());
        for (var container : containers) {
            try {
                container.stop();
            } catch (Exception e) {
                logger.warn("Error stopping Kafka listener container: {}", e.getMessage(), e);
            }
        }
        containers.clear();
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    private void send(String topic, String key, ProducerRecord<String, byte[]> record) {
        try {
            // Block on the future to ensure at-least-once delivery, but never
            // without bound: an unacknowledged record must surface as a failure
            // the caller can act on, not hold a signal publisher forever. The
            // bound outlasts the producer's own delivery budget (sendTimeoutFor),
            // so a timeout here never races a record that is about to land.
            // Safe on virtual threads — yields the carrier thread while waiting.
            kafkaTemplate.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException(
                    "Failed to publish message to Kafka topic '" + topic + "' (key=" + key + ")", e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException(
                    "Timed out after " + sendTimeout + " (the producer's max.block.ms + delivery.timeout.ms + "
                            + SEND_TIMEOUT_GRACE + ") waiting for Kafka to acknowledge a message on topic '"
                            + topic + "' (key=" + key + ") — the producer has given up on it; it will not be "
                            + "delivered later", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while publishing message to Kafka topic '" + topic + "'", e);
        }
    }

    /**
     * Builds the outbound record, injecting the current span's W3C trace context
     * when tracing is configured. Without the collaborator the record is exactly
     * what {@code kafkaTemplate.send(topic, key, bytes)} would have produced.
     */
    private ProducerRecord<String, byte[]> tracedRecord(String topic, String key, byte[] bytes) {
        var record = new ProducerRecord<String, byte[]>(topic, key, bytes);
        if (tracePropagation != null) {
            tracePropagation.inject(record.headers());
        }
        return record;
    }

    private ConcurrentMessageListenerContainer<String, byte[]> createContainer(
            String topic,
            MessageListener<String, byte[]> listener
    ) {
        var containerProps = new ContainerProperties(topic);
        containerProps.setGroupId(config.consumerGroup());
        containerProps.setMessageListener(listener);
        containerProps.setAckMode(ContainerProperties.AckMode.RECORD);
        // Fatal only under verify-on-startup=fail AND only when the startup probe
        // confirmed every required topic present (applyTopicVerification): then a
        // topic gone at container start fails start() instead of leaving a
        // consumer polling nothing forever. Spring Kafka counts an unreachable
        // broker as "missing" here, so after an inconclusive probe this must stay
        // false or the "starting anyway" WARN would be followed by a failed start.
        containerProps.setMissingTopicsFatal(missingTopicsFatal);
        // Without an interval an AuthenticationException/AuthorizationException
        // from poll() stops the container silently; with one it retries, so a
        // credential or ACL fix is picked up without a restart.
        containerProps.setAuthExceptionRetryInterval(AUTH_EXCEPTION_RETRY_INTERVAL);
        // A (re)assignment drops the cached topic id, so a topic recreated
        // under the same name is never identified by its old incarnation's id.
        containerProps.setConsumerRebalanceListener(signalIdentity.rebalanceListener());
        var container = new ConcurrentMessageListenerContainer<>(consumerFactory, containerProps);
        container.setCommonErrorHandler(config.redeliveryEnabled()
                ? KafkaRedeliveryErrorHandlers.deadLettering(
                        kafkaTemplate,
                        config.maxAttempts(),
                        config.initialInterval(),
                        config.multiplier(),
                        config.maxInterval(),
                        config.deadLetterSuffix())
                // maestro.messaging.redelivery.enabled=false: the operator's explicit
                // choice to restore at-most-once handler semantics — zero retries, no
                // DeadLetterPublishingRecoverer, a failing record is logged and skipped.
                : new DefaultErrorHandler(new FixedBackOff(0L, 0L)));
        return container;
    }

    private byte[] serialize(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to serialize message of type " + value.getClass().getName(), e);
        }
    }

    private <T> T deserialize(byte[] bytes, Class<T> type) {
        try {
            return objectMapper.readValue(bytes, type);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to deserialize message to " + type.getName(), e);
        }
    }

    private String resolveSignalTopic(String serviceName) {
        return config.signalTopicFor(serviceName);
    }
}
