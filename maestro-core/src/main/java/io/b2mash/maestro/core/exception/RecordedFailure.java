package io.b2mash.maestro.core.exception;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * What the engine recorded for a failed activity call: the exception's type,
 * its message and — for a class annotated
 * {@link io.b2mash.maestro.core.annotation.FailureDetails @FailureDetails} —
 * its own properties (Issue 42).
 *
 * <p>Returned by {@link ActivityExecutionException#failure()}. It is read from
 * the persisted failure record, so it is the same on the first run and after
 * any recovery, on every node — including one on which the class is missing or
 * cannot be rebuilt, where the cause is a {@link ReconstructedActivityFailure}.
 *
 * <p><b>Thread safety:</b> immutable; {@link #details()} hands out copies.
 *
 * @param type    the fully-qualified class name of the exception the activity
 *                threw ({@code "unknown"} if none was recorded)
 * @param message the recorded message, {@code null} if the exception had none
 * @param details the recorded properties as a JSON object, by property name as
 *                the mapper wrote them; an empty object when the class has not
 *                opted in, when the record predates recording, or when the
 *                properties could not be written. It holds only the node
 *                kinds JSON text parses to (no binary or POJO node). Each
 *                call returns a copy.
 */
public record RecordedFailure(String type, @Nullable String message, JsonNode details) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @throws NullPointerException     if {@code type} or {@code details} is {@code null}
     * @throws IllegalArgumentException if {@code details} is not a JSON object,
     *                                  or holds a binary or POJO node
     */
    public RecordedFailure {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(details, "details");
        if (!details.isObject()) {
            throw new IllegalArgumentException("details must be a JSON object, was " + details.getNodeType());
        }
        requireTextKinds(details);
        details = details.deepCopy();
    }

    /**
     * Refuses a binary or POJO node anywhere in {@code node}: {@code deepCopy()}
     * shares their contents, so a copy would not isolate them. The engine never
     * builds one (recorded properties are parsed from JSON text).
     */
    private static void requireTextKinds(JsonNode node) {
        if (node.isBinary() || node.isPojo()) {
            throw new IllegalArgumentException(
                    "details must hold only what JSON text parses to, found a " + node.getNodeType() + " node");
        }
        for (var child : node.values()) {
            requireTextKinds(child);
        }
    }

    /**
     * What a failure records when nothing beyond type and message is known: the
     * type is a {@link ReconstructedActivityFailure}'s
     * {@link ReconstructedActivityFailure#originalClassName() original class},
     * else the cause's own class; no properties.
     *
     * @param cause the exception the activity call failed with
     * @return the record
     */
    public static RecordedFailure of(Throwable cause) {
        var type = cause instanceof ReconstructedActivityFailure rebuilt
                ? rebuilt.originalClassName()
                : cause.getClass().getName();
        return new RecordedFailure(type, cause.getMessage(), JsonNodeFactory.instance.objectNode());
    }

    /** Returns a copy of the recorded properties (an empty object when none were recorded). */
    @Override
    public JsonNode details() {
        return details.deepCopy();
    }
}
