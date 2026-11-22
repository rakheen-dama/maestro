package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.FailureDetails;
import io.b2mash.maestro.core.exception.ExceptionTypeResolver;
import io.b2mash.maestro.core.exception.MaestroControlFlowError;
import io.b2mash.maestro.core.exception.ReconstructedActivityFailure;
import io.b2mash.maestro.core.exception.RecordedFailure;
import io.b2mash.maestro.core.exception.SerializationException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministically rebuilds the exception recorded for a failed step (C15,
 * Issue 42).
 *
 * <p>Workflow code must be handed the same cause on every pass and on every
 * node, independent of reflection accidents — and, since Issue 42, on the
 * first run too: the live path records the failure and throws what
 * {@link #rebuild} makes of the record, exactly as a replay does.
 *
 * <h2>The record</h2>
 * <p>{@link #record} writes {@code {"exceptionType", "message"}} — the fields
 * every build reads — and, for a class annotated {@link FailureDetails}, a
 * {@code "details"} object of the exception's own properties
 * ({@link PayloadSerializer#failureDetails}), or {@code "details": null} when
 * they could not be written. An older build ignores {@code details}.
 *
 * <h2>The rule</h2>
 * <ul>
 *   <li><b>A record without {@code details}</b> (a class that has not opted
 *       in, or a record an older build wrote) — {@link #reconstruct}: the
 *       original type is re-created only when the stored name resolves to a
 *       {@link Throwable} that is <b>not</b> a {@link MaestroControlFlowError}
 *       and exposes a <b>public</b> {@code (String)} constructor that
 *       completes normally and keeps the message it is given (so a
 *       package-private or private nested class, an abstract class, a
 *       throwing constructor and one that derives its message all fall
 *       back); in every
 *       other case (no such constructor, class not on the classpath, no type
 *       recorded, or a control-flow signal name) the result is a
 *       {@link ReconstructedActivityFailure} carrying the original class name
 *       and message.</li>
 *   <li><b>A record with {@code details}</b> — the mapper creates the class
 *       from {@code {"message": …}} plus the recorded properties, under the
 *       same type gate; the result is kept only if it is of exactly that
 *       class, has the recorded message and writes the recorded properties
 *       again (numbers compared by value, so a store's normalisation of a
 *       number is not a difference). Otherwise — including
 *       {@code "details": null} — it is a {@link ReconstructedActivityFailure},
 *       never the {@code (String)} constructor, which would leave the
 *       properties silently {@code null}; {@link Rebuilt#notRebuilt()} says
 *       why.</li>
 * </ul>
 * The result is never a bare {@code RuntimeException}, and an engine
 * control-flow signal is never instantiated from stored data — doing so would
 * make a recorded failure stop the workflow's local run instead of failing it.
 * The message is the recorded one, {@code null} included. The rebuild is a
 * function of the stored payload, the classpath and the mapper only.
 *
 * <h2>Visibility</h2>
 * <p>Public for parity with {@link ReplayGuard}: replay reads outside this
 * package must apply the identical reconstruction. It is internal engine API —
 * not part of the embedder-facing surface — and carries no compatibility
 * promise.
 *
 * <p><b>Thread safety:</b> stateless apart from a once-per-type WARN set; all
 * methods are static and safe to call from any thread.
 *
 * <p>The stored name is resolved by {@link ExceptionTypeResolver#resolve}
 * <em>without initialising</em> the class; initialisation happens only at
 * instantiation, for a type that has passed the gate.
 */
public final class FailureReconstruction {

    private static final Logger logger = LoggerFactory.getLogger(FailureReconstruction.class);

    /** The payload field holding the recorded exception class name. */
    static final String TYPE_FIELD = "exceptionType";

    /** The payload field holding the recorded message. */
    static final String MESSAGE_FIELD = "message";

    /** The payload field holding an opted-in class's recorded properties. */
    static final String DETAILS_FIELD = "details";

    /** Types already reported at WARN, so a replay loop does not flood the log. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private FailureReconstruction() {
    }

    /**
     * A rebuilt failure.
     *
     * @param cause      what workflow code receives as the
     *                   {@code ActivityExecutionException}'s cause
     * @param failure    what was recorded
     * @param notRebuilt why a record carrying properties was rebuilt as a
     *                   {@link ReconstructedActivityFailure} (fixed text that
     *                   names classes only, never a value), or {@code null}
     *                   when it was not one
     */
    record Rebuilt(Throwable cause, RecordedFailure failure, @Nullable String notRebuilt) {
    }

    /**
     * The {@code ACTIVITY_FAILED} payload for a live failure.
     *
     * @param failure    the exception the activity call failed with
     * @param serializer the run's serializer, which writes an opted-in class's properties
     * @return {@code exceptionType} and {@code message}, plus {@code details}
     *         for a class annotated {@link FailureDetails}
     */
    static ObjectNode record(Throwable failure, PayloadSerializer serializer) {
        var payload = JsonNodeFactory.instance.objectNode();
        payload.put(TYPE_FIELD, failure.getClass().getName());
        payload.put(MESSAGE_FIELD, failure.getMessage());
        if (failure.getClass().isAnnotationPresent(FailureDetails.class)) {
            try {
                payload.set(DETAILS_FIELD, serializer.failureDetails(failure));
            } catch (SerializationException unwritable) {
                // Recorded as null, not left out: a record without details
                // reads by the (String)-constructor rule, which would rebuild
                // this class with its properties silently null. The rebuild
                // reports it (notRebuilt).
                logger.debug("The properties of {} could not be written ({}); recording none",
                        failure.getClass().getName(), UnknownHistoryGuard.redactedCause(rootOf(unwritable)));
                payload.putNull(DETAILS_FIELD);
            }
        }
        return payload;
    }

    /**
     * Rebuilds a recorded failure (class Javadoc, "The rule").
     *
     * @param payload    the {@code ACTIVITY_FAILED} payload as the store returned it
     * @param serializer the run's serializer
     * @return the cause, the record and, for a record with properties rebuilt
     *         as a reconstruction, why
     */
    static Rebuilt rebuild(@Nullable JsonNode payload, PayloadSerializer serializer) {
        var type = text(payload, TYPE_FIELD);
        var message = text(payload, MESSAGE_FIELD);
        var recordedType = type != null ? type : "unknown";
        var details = payload == null ? null : payload.get(DETAILS_FIELD);
        if (details == null) {
            return new Rebuilt(reconstruct(type, message),
                    new RecordedFailure(recordedType, message, JsonNodeFactory.instance.objectNode()), null);
        }
        if (!(details instanceof ObjectNode recorded)) {
            return reconstructed(recordedType, message, JsonNodeFactory.instance.objectNode(),
                    "its properties could not be written when it was recorded");
        }
        if (type == null) {
            return reconstructed(recordedType, message, recorded, "no type was recorded");
        }
        var clazz = ExceptionTypeResolver.resolve(type);
        if (clazz == null) {
            return reconstructed(recordedType, message, recorded, "the class is not on this node's classpath");
        }
        if (!Throwable.class.isAssignableFrom(clazz) || MaestroControlFlowError.class.isAssignableFrom(clazz)) {
            return reconstructed(recordedType, message, recorded,
                    "the recorded type is not an exception that may be rebuilt");
        }
        var input = JsonNodeFactory.instance.objectNode();
        input.put(MESSAGE_FIELD, message);
        input.setAll(recorded);
        Object created;
        try {
            created = serializer.deserialize(input, clazz);
        } catch (SerializationException | LinkageError cannotCreate) {
            return reconstructed(recordedType, message, recorded, "the mapper could not create it ("
                    + UnknownHistoryGuard.redactedCause(rootOf(cannotCreate)) + ")");
        }
        if (created == null || created.getClass() != clazz) {
            return reconstructed(recordedType, message, recorded, "the mapper created "
                    + (created == null ? "nothing" : "a " + created.getClass().getName()));
        }
        var rebuilt = (Throwable) created;
        if (!Objects.equals(rebuilt.getMessage(), message)) {
            return reconstructed(recordedType, message, recorded,
                    "the rebuilt exception's message differs from the recorded one");
        }
        ObjectNode rewritten;
        try {
            rewritten = serializer.failureDetails(rebuilt);
        } catch (SerializationException unwritable) {
            return reconstructed(recordedType, message, recorded, "the rebuilt exception's properties "
                    + "cannot be written (" + UnknownHistoryGuard.redactedCause(rootOf(unwritable)) + ")");
        }
        if (!sameJson(rewritten, recorded)) {
            return reconstructed(recordedType, message, recorded,
                    "the rebuilt exception's properties differ from the recorded ones");
        }
        return new Rebuilt(rebuilt, new RecordedFailure(recordedType, message, recorded), null);
    }

    private static Rebuilt reconstructed(String type, @Nullable String message, ObjectNode details,
                                         String why) {
        return new Rebuilt(new ReconstructedActivityFailure(type, message),
                new RecordedFailure(type, message, details), why);
    }

    /**
     * Rebuilds a failure from its type and message alone (class Javadoc, the
     * rule for a record without {@code details}).
     *
     * @param exceptionType the recorded fully-qualified exception class name, or {@code null}
     * @param message       the recorded message, or {@code null}
     * @return the original type (public {@code (String)} constructor only) or a
     *         {@link ReconstructedActivityFailure}; never {@code null}
     */
    public static Throwable reconstruct(@Nullable String exceptionType, @Nullable String message) {
        if (exceptionType != null) {
            var clazz = ExceptionTypeResolver.resolve(exceptionType);
            if (clazz == null) {
                logger.debug("Exception type '{}' is not loadable; replaying as ReconstructedActivityFailure",
                        exceptionType);
            } else if (Throwable.class.isAssignableFrom(clazz)
                    && !MaestroControlFlowError.class.isAssignableFrom(clazz)) {
                try {
                    var rebuilt = (Throwable) clazz.getConstructor(String.class).newInstance(message);
                    if (Objects.equals(rebuilt.getMessage(), message)) {
                        return rebuilt;
                    }
                    // A constructor that derives its message (super("x: " + m))
                    // would wrap the recorded, already-derived message again.
                    logger.debug("Exception type '{}' changes the message its (String) constructor is given; "
                            + "replaying as ReconstructedActivityFailure", exceptionType);
                } catch (NoSuchMethodException e) {
                    logger.debug("Exception type '{}' has no public (String) constructor; "
                            + "replaying as ReconstructedActivityFailure", exceptionType);
                } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                    if (WARNED.add(exceptionType)) {
                        logger.warn("Could not reconstruct exception type '{}'; "
                                + "replaying as ReconstructedActivityFailure", exceptionType, e);
                    }
                }
            }
        }
        return new ReconstructedActivityFailure(exceptionType != null ? exceptionType : "unknown", message);
    }

    /**
     * Whether two JSON trees hold the same values: objects by property (in any
     * order), arrays element by element, numbers by value (so {@code 1.0E7}
     * and {@code 10000000} agree, as a {@code jsonb} round trip makes them),
     * everything else by {@link JsonNode#equals}. Both sides come from
 * {@link PayloadSerializer#failureDetails} or a store's parse of JSON text, so
 * no binary or POJO node reaches the last case.
     */
    static boolean sameJson(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) {
            // By the value of the number's JSON text, never decimalValue(): a
            // FloatNode's widens 0.1f to 0.10000000149011612.
            try {
                return new BigDecimal(a.asString()).compareTo(new BigDecimal(b.asString())) == 0;
            } catch (RuntimeException notADecimal) {
                return a.equals(b);
            }
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (var entry : a.properties()) {
                var other = b.get(entry.getKey());
                if (other == null || !sameJson(entry.getValue(), other)) {
                    return false;
                }
            }
            return true;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!sameJson(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    /** The recorded string at {@code field}, or {@code null} if absent, JSON null or not a string. */
    private static @Nullable String text(@Nullable JsonNode payload, String field) {
        if (payload == null) {
            return null;
        }
        var value = payload.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }

    /** The innermost cause, stopping at a cycle. */
    private static Throwable rootOf(Throwable t) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var root = t;
        while (seen.add(root) && root.getCause() != null && !seen.contains(root.getCause())) {
            root = root.getCause();
        }
        return root;
    }
}
