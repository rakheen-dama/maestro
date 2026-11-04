package io.b2mash.maestro.store.postgres.notify;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Names the one Postgres {@code NOTIFY} channel a service's nodes share for
 * signal wakes, and encodes the per-signal payload carried on it.
 *
 * <h2>Why one channel per service, not one per workflow</h2>
 * <p>A Postgres channel is an identifier, so it is silently truncated at 63
 * bytes and is case-folded unless quoted. A channel <em>derived from the
 * workflow ID</em> therefore never matched its listener for IDs longer than
 * about 48 characters, and let IDs that differ only by case or punctuation
 * ({@code Order-1} / {@code order_1}) wake each other. With one channel per
 * service the identifier never carries user data — it is built from the
 * sanitised service name plus a hash of the exact service name so two services
 * whose names sanitise alike still get distinct channels — and the workflow ID
 * travels verbatim in the payload, which is an arbitrary string (up to the
 * server's 8000-byte limit), prefixed with its own length so the signal name
 * that follows it can be split off without any delimiter ever colliding with
 * the ID's contents.
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; every method is a pure function.
 */
public final class WakeChannel {

    /** Fixed prefix of every wake channel. */
    static final String PREFIX = "maestro_signal_wake_";

    /** Longest sanitised service-name fragment kept in the identifier. */
    private static final int MAX_NAME_FRAGMENT = 32;

    /** Hex digits of SHA-256 appended to disambiguate names that sanitise alike. */
    private static final int HASH_HEX_CHARS = 8;

    private WakeChannel() {
    }

    /**
     * The channel all nodes of {@code serviceName} LISTEN and NOTIFY on.
     *
     * <p>Shape: {@code maestro_signal_wake_<sanitised ≤32>_<8 hex sha256>}, so
     * at most {@code 20 + 32 + 1 + 8 = 61} bytes — always a valid, untruncated
     * Postgres identifier that needs no quoting. Sanitising maps every
     * character outside {@code [A-Za-z0-9_]} to {@code _} and lower-cases the
     * rest; the hash is over the exact (unsanitised) service name.
     *
     * @param serviceName the service's {@code maestro.service-name}
     * @return the channel identifier
     */
    public static String channelFor(String serviceName) {
        Objects.requireNonNull(serviceName, "serviceName");
        var sanitised = serviceName.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase(Locale.ROOT);
        if (sanitised.length() > MAX_NAME_FRAGMENT) {
            sanitised = sanitised.substring(0, MAX_NAME_FRAGMENT);
        }
        return PREFIX + sanitised + "_" + sha256Hex(serviceName).substring(0, HASH_HEX_CHARS);
    }

    /**
     * Encodes a wake for {@code (workflowId, signalName)} as
     * {@code <workflowId.length()>:<workflowId><signalName>}.
     *
     * @param workflowId the workflow's business ID, verbatim
     * @param signalName the signal name, verbatim
     * @return the NOTIFY payload
     */
    public static String encode(String workflowId, String signalName) {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(signalName, "signalName");
        return workflowId.length() + ":" + workflowId + signalName;
    }

    /**
     * Inverse of {@link #encode}.
     *
     * @param payload a payload produced by {@link #encode}
     * @return the workflow ID and signal name
     * @throws IllegalArgumentException if the payload is not of that shape
     */
    public static WakePayload decode(String payload) {
        Objects.requireNonNull(payload, "payload");
        var colon = payload.indexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("Malformed wake payload (no length prefix): " + payload);
        }
        int length;
        try {
            length = Integer.parseInt(payload, 0, colon, 10);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed wake payload (bad length prefix): " + payload, e);
        }
        var idStart = colon + 1;
        // Compare by subtraction: idStart + length overflows int for a hostile length prefix.
        if (length < 0 || length > payload.length() - idStart) {
            throw new IllegalArgumentException("Malformed wake payload (length exceeds payload): " + payload);
        }
        return new WakePayload(payload.substring(idStart, idStart + length), payload.substring(idStart + length));
    }

    private static String sha256Hex(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every Java runtime", e);
        }
    }

    /**
     * A decoded wake notification.
     *
     * @param workflowId the workflow the signal was delivered to
     * @param signalName the signal's name
     */
    public record WakePayload(String workflowId, String signalName) {
    }
}
