package io.b2mash.maestro.messaging.kafka;

import org.apache.kafka.common.header.Headers;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;

/**
 * The Kafka record headers Maestro reads and writes for signals.
 *
 * <h2>{@value #MESSAGE_ID}</h2>
 * <p>The producer's identity for the logical signal a record carries, as
 * UTF-8 text. The consumer persists at most one signal per
 * {@code (workflowId, signalName, messageId)}, so a record redelivered after a
 * crash or rebalance — or republished by a producer that retried a send whose
 * acknowledgement it never saw — is suppressed instead of becoming a second
 * signal. The engine's own {@code publishSignal} writes it whenever the
 * {@code SignalMessage} carries an id; a domain producer whose records reach
 * a {@code @MaestroSignalListener} sets it itself, typically with
 * {@link SignalMessageIds#forCurrentActivity()} from inside the publishing
 * activity:
 *
 * <pre>{@code
 * var record = new ProducerRecord<>(topic, key, bytes);
 * MaestroSignalHeaders.setMessageId(record.headers(), SignalMessageIds.forCurrentActivity());
 * kafkaTemplate.send(record);
 * }</pre>
 *
 * <p>Every retry of one logical signal must carry the same id; two distinct
 * signals must carry distinct ids, or the second is dropped as a duplicate. A
 * record without the header (and, on the engine channel, without an envelope
 * id) falls back to its Kafka coordinate — see
 * {@link KafkaSignalIdentity#resolve}.
 *
 * <p><b>Thread safety:</b> stateless utility.
 */
@NullMarked
public final class MaestroSignalHeaders {

    /** Header carrying the producer's message id for the signal in the record. */
    public static final String MESSAGE_ID = "maestro-message-id";

    private MaestroSignalHeaders() {
    }

    /**
     * Sets (replacing any earlier value) the {@value #MESSAGE_ID} header.
     *
     * @param headers   the outbound record's headers
     * @param messageId the identity of the logical signal; must not be blank
     * @throws IllegalArgumentException if {@code messageId} is blank
     */
    public static void setMessageId(Headers headers, String messageId) {
        if (messageId.isBlank()) {
            throw new IllegalArgumentException("a message id must not be blank");
        }
        headers.remove(MESSAGE_ID);
        headers.add(MESSAGE_ID, messageId.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads the {@value #MESSAGE_ID} header — the last one, if several were
     * added.
     *
     * @param headers the consumed record's headers
     * @return the id, or {@code null} when absent, empty or blank
     */
    public static @Nullable String messageId(Headers headers) {
        var header = headers.lastHeader(MESSAGE_ID);
        if (header == null || header.value() == null) {
            return null;
        }
        var value = new String(header.value(), StandardCharsets.UTF_8);
        return value.isBlank() ? null : value;
    }
}
