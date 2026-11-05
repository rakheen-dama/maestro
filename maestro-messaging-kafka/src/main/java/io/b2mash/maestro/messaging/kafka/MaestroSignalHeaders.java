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
 * signals must carry distinct ids, or the second is dropped as a duplicate.
 * The consumer stores the header id namespaced by its source topic —
 * {@code "hdr:<topic>:<id>"} — so a header can never collide with an envelope
 * id ({@code "env:..."}), a coordinate fallback ({@code "kafka:..."}), a
 * client id ({@code "client:..."}) or a header id that arrived on another
 * topic; a republish lands on the same topic and still deduplicates. A record
 * without the header (and, on the engine channel, without an envelope id)
 * falls back to its Kafka coordinate — see {@link KafkaSignalIdentity#resolve}.
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
     * <p>A header is untrusted input. A value that could not be stored as an
     * identity — longer than {@value #MAX_MESSAGE_ID_BYTES} UTF-8 bytes, or
     * containing a control character (below {@code U+0020}, or
     * {@code U+007F}; a NUL in particular is rejected by Postgres
     * {@code TEXT}) — is treated as <em>no identity</em> rather than allowed
     * to fail the insert: a failed insert would redeliver the record until it
     * is dead-lettered, discarding a signal over its metadata. Such a value is
     * logged at {@code DEBUG} (once per JVM).
     *
     * @param headers the consumed record's headers
     * @return the id, or {@code null} when absent, empty, blank or invalid
     */
    public static @Nullable String messageId(Headers headers) {
        var header = headers.lastHeader(MESSAGE_ID);
        if (header == null || header.value() == null) {
            return null;
        }
        var bytes = header.value();
        if (bytes.length > MAX_MESSAGE_ID_BYTES) {
            logInvalidOnce("is " + bytes.length + " bytes (limit " + MAX_MESSAGE_ID_BYTES + ")");
            return null;
        }
        var value = new String(bytes, StandardCharsets.UTF_8);
        if (!isStorable(value)) {
            logInvalidOnce("contains a control character");
            return null;
        }
        return value.isBlank() ? null : value;
    }

    /** Longest {@value #MESSAGE_ID} header value, in UTF-8 bytes, read as an identity. */
    public static final int MAX_MESSAGE_ID_BYTES = 512;

    private static final java.util.concurrent.atomic.AtomicBoolean INVALID_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(MaestroSignalHeaders.class);

    /**
     * Whether {@code id} contains no control character ({@code < U+0020} or
     * {@code U+007F}).
     *
     * @param id a candidate message id
     * @return {@code true} if it can be stored as an identity
     */
    static boolean isStorable(String id) {
        for (int i = 0; i < id.length(); i++) {
            var c = id.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return false;
            }
        }
        return true;
    }

    private static void logInvalidOnce(String why) {
        if (INVALID_LOGGED.compareAndSet(false, true)) {
            logger.debug("Ignoring a {} header that {} — the record is delivered without that identity "
                    + "(logged once)", MESSAGE_ID, why);
        }
    }
}
