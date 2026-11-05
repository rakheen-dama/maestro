package io.b2mash.maestro.messaging.kafka;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.Uuid;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ConsumerFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Resolves the message id of a consumed signal record — the identity the
 * engine deduplicates on ({@code WorkflowExecutor.deliverSignal(..., messageId)}).
 *
 * <h2>Resolution order</h2>
 * <p>Each source has its own namespace, so no source can forge another's key
 * and suppress a genuine signal (a header set to the literal form of a
 * coordinate, say, or to an id another topic's producer used):
 * <ol>
 *   <li><b>Envelope</b> — {@code SignalMessage.messageId()}, on the engine
 *       signal channel, stored as {@code "env:<id>"}. If the
 *       {@value MaestroSignalHeaders#MESSAGE_ID} header is also present and
 *       differs, the envelope wins and the mismatch is logged at {@code WARN}
 *       once. An envelope id containing a control character is ignored.</li>
 *   <li><b>Header</b> — {@value MaestroSignalHeaders#MESSAGE_ID}, the only
 *       producer-side id a domain record reaching a
 *       {@code @MaestroSignalListener} can carry, stored as
 *       {@code "hdr:<topic>:<id>"}: one topic cannot suppress another's
 *       signals, and a republish lands on the same topic. Invalid header
 *       values are ignored ({@link MaestroSignalHeaders#messageId}).</li>
 *   <li><b>Coordinate</b> — {@code "kafka:<topicId>:<partition>:<offset>"},
 *       using the topic's KIP-516 topic id. A record's coordinate is the same
 *       on every redelivery of it, so a redelivery after a crash or rebalance
 *       (the insert committed, the offset did not) is suppressed even when the
 *       producer named nothing. The topic <em>id</em>, not its name, because a
 *       topic deleted and recreated under the same name restarts its offsets:
 *       {@code topic:partition:offset} would then collide with signals of the
 *       earlier incarnation, and the new ones would be dropped as duplicates.
 *       The id is resolved with one {@code describeTopics} call per topic,
 *       cached, and dropped from the cache whenever the topic's partitions are
 *       reassigned (see {@link #rebalanceListener()}) — a recreation always
 *       reassigns. When it cannot be resolved (a broker that does not expose
 *       topic ids, no {@code DESCRIBE} permission, an unreachable broker), the
 *       record gets <b>no</b> id and is not deduplicated; the lookup is
 *       retried at most once per {@link #RETRY_INTERVAL}.</li>
 * </ol>
 *
 * <p>The coordinate protects only against redelivery of the <em>same</em>
 * record. A producer that republishes writes a new record at a new offset —
 * only an envelope or header id makes that a duplicate.
 *
 * <p><b>Thread safety:</b> thread-safe; one instance is shared by every
 * consumer thread of the containers its owner builds.
 */
@NullMarked
public final class KafkaSignalIdentity {

    private static final Logger logger = LoggerFactory.getLogger(KafkaSignalIdentity.class);

    /** Least time between two lookups of a topic id that failed. */
    static final Duration RETRY_INTERVAL = Duration.ofMinutes(1);

    private final Function<String, @Nullable String> topicIdLookup;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, String> topicIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> failedAtNanos = new ConcurrentHashMap<>();
    private final Set<String> warnedUnresolved = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean mismatchWarned = new AtomicBoolean();

    /**
     * Creates a resolver whose topic-id lookups use a short-lived
     * {@link Admin} client built from {@code consumerFactory}'s configuration
     * (the same derivation as {@link KafkaTopicVerifier}).
     *
     * @param consumerFactory the factory the consuming containers are built from
     */
    public KafkaSignalIdentity(ConsumerFactory<?, ?> consumerFactory) {
        this(topic -> describeTopicId(consumerFactory, topic), System::nanoTime);
    }

    /**
     * Test seam.
     *
     * @param topicIdLookup topic name → topic id, or {@code null} when unknown
     * @param nanoClock     monotonic clock for the retry interval
     */
    KafkaSignalIdentity(Function<String, @Nullable String> topicIdLookup, LongSupplier nanoClock) {
        this.topicIdLookup = topicIdLookup;
        this.nanoClock = nanoClock;
    }

    /**
     * Resolves the message id of one consumed record.
     *
     * @param envelopeId the id the deserialized envelope carries, or
     *                   {@code null} (always {@code null} for a domain record)
     * @param record     the consumed record
     * @return the id to deliver with, or {@code null} for "no identity"
     */
    public @Nullable String resolve(@Nullable String envelopeId, ConsumerRecord<?, ?> record) {
        var headerId = MaestroSignalHeaders.messageId(record.headers());
        if (envelopeId != null && !envelopeId.isBlank() && MaestroSignalHeaders.isStorable(envelopeId)) {
            if (headerId != null && !headerId.equals(envelopeId) && mismatchWarned.compareAndSet(false, true)) {
                logger.warn("Signal record on {}-{}@{} carries message id '{}' in its envelope but '{}' in its {} "
                                + "header — using the envelope's (logged once; later mismatches are not logged)",
                        record.topic(), record.partition(), record.offset(), envelopeId, headerId,
                        MaestroSignalHeaders.MESSAGE_ID);
            }
            return ENVELOPE_PREFIX + envelopeId;
        }
        if (headerId != null) {
            return HEADER_PREFIX + record.topic() + ":" + headerId;
        }
        var topicId = topicId(record.topic());
        return topicId == null ? null
                : COORDINATE_PREFIX + topicId + ":" + record.partition() + ":" + record.offset();
    }

    /** Namespace of an id taken from the {@code SignalMessage} envelope. */
    public static final String ENVELOPE_PREFIX = "env:";

    /** Namespace of an id taken from the header; followed by the source topic and {@code ':'}. */
    public static final String HEADER_PREFIX = "hdr:";

    /** Namespace of a coordinate fallback; followed by {@code <topicId>:<partition>:<offset>}. */
    public static final String COORDINATE_PREFIX = "kafka:";

    /**
     * A rebalance listener that drops the cached topic id of every topic whose
     * partitions are (re)assigned, so a topic recreated under the same name is
     * looked up afresh before its first record is identified.
     *
     * @return a listener to install on each consuming container
     */
    public ConsumerRebalanceListener rebalanceListener() {
        return new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                // nothing: the cache is refreshed on assignment
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                partitions.stream().map(TopicPartition::topic).distinct().forEach(KafkaSignalIdentity.this::invalidate);
            }
        };
    }

    /** Forgets the cached id (and any failed lookup) of {@code topic}. */
    void invalidate(String topic) {
        topicIds.remove(topic);
        failedAtNanos.remove(topic);
    }

    /**
     * The topic id of {@code topic}, from the cache or one lookup.
     *
     * @return the id (Kafka's base64 {@link Uuid} form), or {@code null} if it
     *         cannot be resolved now
     */
    @Nullable String topicId(String topic) {
        var cached = topicIds.get(topic);
        if (cached != null) {
            return cached;
        }
        var failedAt = failedAtNanos.get(topic);
        if (failedAt != null && nanoClock.getAsLong() - failedAt < RETRY_INTERVAL.toNanos()) {
            return null;
        }
        var resolved = topicIdLookup.apply(topic);
        if (resolved == null) {
            failedAtNanos.put(topic, nanoClock.getAsLong());
            if (warnedUnresolved.add(topic)) {
                logger.warn("Could not resolve the Kafka topic id of '{}' — records on it that carry neither an "
                                + "envelope message id nor a {} header are delivered without deduplication until "
                                + "it can be (retried every {})", topic, MaestroSignalHeaders.MESSAGE_ID,
                        RETRY_INTERVAL);
            }
            return null;
        }
        failedAtNanos.remove(topic);
        topicIds.put(topic, resolved);
        return resolved;
    }

    /**
     * One bounded {@code describeTopics} call for {@code topic}'s id.
     *
     * @return the id, or {@code null} on any failure or when the broker
     *         reports no id ({@link Uuid#ZERO_UUID})
     */
    static @Nullable String describeTopicId(ConsumerFactory<?, ?> consumerFactory, String topic) {
        Admin admin = null;
        try {
            admin = Admin.create(KafkaTopicVerifier.adminConfigFrom(consumerFactory));
            var options = new DescribeTopicsOptions().timeoutMs((int) KafkaTopicVerifier.PROBE_TIMEOUT.toMillis());
            var future = admin.describeTopics(List.of(topic), options).topicNameValues().get(topic);
            if (future == null) {
                return null;
            }
            var id = future.get(KafkaTopicVerifier.PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).topicId();
            return id == null || Uuid.ZERO_UUID.equals(id) ? null : id.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            logger.debug("Topic id lookup for '{}' failed: {}", topic, e.toString());
            return null;
        } finally {
            if (admin != null) {
                try {
                    admin.close(Duration.ofSeconds(1));
                } catch (RuntimeException e) {
                    logger.debug("Closing the topic-id Admin client failed: {}", e.toString());
                }
            }
        }
    }
}
