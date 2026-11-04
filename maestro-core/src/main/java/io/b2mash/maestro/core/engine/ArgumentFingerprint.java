package io.b2mash.maestro.core.engine;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.POJONode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Fingerprints an activity invocation's arguments so a replay can tell that
 * the activity is being called with <em>different inputs</em> than the run
 * that recorded its outcome.
 *
 * <p>Memoization matches a stored outcome to a step by sequence number and
 * ({@link ReplayGuard#requireMatch}) by step name. Neither sees the
 * arguments: a workflow that computes an activity's input from something
 * non-deterministic — a {@code HashMap}'s iteration order, {@code Instant.now()},
 * a config value that differs between nodes — replays the recorded result for
 * an input the recorded run never saw, silently. The fingerprint written on
 * every {@code ACTIVITY_COMPLETED}/{@code ACTIVITY_FAILED} event
 * ({@link io.b2mash.maestro.core.model.WorkflowEvent#argsHash()}) and
 * recomputed at replay ({@link ReplayGuard#requireArgumentsMatch}) turns that
 * into a {@code NONDETERMINISTIC_HISTORY} stand-down.
 *
 * <h2>Canonical form</h2>
 * <p>Each argument is serialized to a {@link JsonNode} by the engine's
 * {@link PayloadSerializer} — the same mapper that writes payloads, so a
 * custom module or mix-in that shapes a payload shapes its fingerprint the
 * same way — and rendered canonically, independent of the mapper's own
 * ordering and number formatting:
 * <ul>
 *   <li>object properties sorted by key ({@link String#compareTo}), so a
 *       {@code HashMap} and a {@code LinkedHashMap} with the same entries
 *       agree, and so do records whose field order changed;</li>
 *   <li>array elements in order (order is part of a list's value);</li>
 *   <li>strings JSON-escaped and quoted; binary as base64, quoted;</li>
 *   <li>numbers as {@code decimalValue().stripTrailingZeros().toPlainString()},
 *       so {@code 1}, {@code 1.0} and {@code 1E0} agree; non-finite doubles as
 *       their {@code NaN}/{@code Infinity} literals;</li>
 *   <li>{@code null}, {@code true} and {@code false} as their literals.</li>
 * </ul>
 * The arguments are joined with {@code U+001E} (record separator) and
 * prefixed by their count, so {@code f()} ({@code "0"}) and {@code f(null)}
 * ({@code "1\u001Enull"}) differ while a {@code null} array and an empty
 * array are both the zero-arity call. The digest is SHA-256, rendered as 64
 * lowercase hex characters.
 *
 * <h2>Failure</h2>
 * <p>Fingerprinting never fails an activity call. Any exception — an
 * argument the mapper cannot serialize, a node shape this writer does not
 * handle — yields {@code null}, which every reader treats as
 * <em>unverified</em>: the live write records no hash and the replay guard
 * accepts the slot. One DEBUG line per such call names the cause (the step
 * and sequence are in the MDC the invocation handler set).
 *
 * <p><b>Thread safety:</b> stateless; safe to call from any thread.
 */
public final class ArgumentFingerprint {

    private static final Logger logger = LoggerFactory.getLogger(ArgumentFingerprint.class);

    /** Separates the canonical arguments in the digested text. */
    static final char SEPARATOR = '\u001E';

    private static final HexFormat HEX = HexFormat.of();

    private ArgumentFingerprint() {
    }

    /**
     * Fingerprints {@code args}.
     *
     * @param serializer the engine's payload serializer — the mapper the
     *                   stored payloads are written with
     * @param args       the invocation's arguments as the proxy received
     *                   them; {@code null} (a no-argument method) and an empty
     *                   array fingerprint identically
     * @return 64 lowercase hex characters, or {@code null} if any argument
     *         could not be fingerprinted (logged at DEBUG; the caller must
     *         treat the slot as unverified)
     */
    public static @Nullable String of(PayloadSerializer serializer, @Nullable Object @Nullable [] args) {
        try {
            return sha256Hex(canonical(serializer, args));
        } catch (RuntimeException e) {
            logger.debug("Activity arguments could not be fingerprinted ({}); this slot will not be "
                    + "argument-verified at replay", e.toString());
            return null;
        }
    }

    /**
     * The digested text — package-private so the canonical form is testable
     * without hashing.
     *
     * @throws RuntimeException if an argument cannot be serialized or rendered
     */
    static String canonical(PayloadSerializer serializer, @Nullable Object @Nullable [] args) {
        var text = new StringBuilder();
        var arity = args == null ? 0 : args.length;
        text.append(arity);
        for (int i = 0; i < arity; i++) {
            text.append(SEPARATOR);
            writeCanonical(serializer.serialize(args[i]), text);
        }
        return text.toString();
    }

    /**
     * @throws IllegalArgumentException for a node kind this writer does not
     *                                  handle (a {@code POJONode} the mapper
     *                                  could not expand, a missing node)
     */
    private static void writeCanonical(@Nullable JsonNode node, StringBuilder out) {
        if (node == null || node.isNull()) {
            out.append("null");
        } else if (node.isBoolean()) {
            out.append(node.booleanValue());
        } else if (node.isNumber()) {
            writeNumber(node, out);
        } else if (node.isString()) {
            writeQuoted(node.stringValue(), out);
        } else if (node.isBinary()) {
            writeQuoted(Base64.getEncoder().encodeToString(node.binaryValue()), out);
        } else if (node instanceof POJONode pojo && pojo.getPojo() instanceof byte[] bytes) {
            // valueToTree keeps a top-level byte[] as an embedded POJO rather
            // than a BinaryNode; render it as the binary it is.
            writeQuoted(Base64.getEncoder().encodeToString(bytes), out);
        } else if (node.isArray()) {
            out.append('[');
            var first = true;
            for (var element : node) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeCanonical(element, out);
            }
            out.append(']');
        } else if (node.isObject()) {
            // String.compareTo order (code-point order), not the mapper's
            // insertion order: a HashMap's and a LinkedHashMap's rendering of
            // the same entries must agree.
            var sorted = new TreeMap<String, JsonNode>();
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                sorted.put(property.getKey(), property.getValue());
            }
            out.append('{');
            var first = true;
            for (var property : sorted.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeQuoted(property.getKey(), out);
                out.append(':');
                writeCanonical(property.getValue(), out);
            }
            out.append('}');
        } else {
            throw new IllegalArgumentException(
                    "no canonical form for node type " + node.getNodeType());
        }
    }

    private static void writeNumber(JsonNode node, StringBuilder out) {
        var value = node.numberValue();
        if (value instanceof Double d && (d.isNaN() || d.isInfinite())) {
            out.append(d);              // "NaN", "Infinity", "-Infinity"
            return;
        }
        if (value instanceof Float f && (f.isNaN() || f.isInfinite())) {
            out.append(f);
            return;
        }
        out.append(node.decimalValue().stripTrailingZeros().toPlainString());
    }

    /** JSON string escaping — enough to make the rendering unambiguous. */
    private static void writeQuoted(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            var c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static String sha256Hex(String text) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required JCA algorithm", e);
        }
    }
}
