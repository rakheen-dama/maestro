package io.b2mash.maestro.core.exception;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Stands in, on replay, for an activity exception whose original type cannot be
 * safely re-created from the persisted failure record.
 *
 * <p>It is produced as the {@link ActivityExecutionException#getCause() cause}
 * of a replayed {@link ActivityExecutionException} whenever the original type
 * cannot be re-created: it is not an accessible public class with a public
 * {@code (String)} constructor that completes normally (package-private or
 * private nested classes, abstract classes, a missing constructor and a
 * throwing constructor all qualify); the class is not on this node's
 * classpath; no type was recorded (reported as {@code "unknown"}); or the
 * recorded type names an engine control-flow signal
 * ({@link MaestroControlFlowError} subtype), which is never instantiated from
 * stored data. {@link #originalClassName()} names the type the activity
 * actually threw, so workflow code can branch on it identically live and on
 * replay. The message is the recorded one, except that a null or empty
 * recorded message replays as {@code "Replayed failure"}.
 *
 * <p>Deliberately <b>not</b> a {@link MaestroException}: it represents the
 * <em>user's</em> exception, and {@code catch (MaestroException)} must keep
 * meaning "engine-level problem".
 *
 * <p><b>Thread safety:</b> immutable once constructed.
 */
public final class ReconstructedActivityFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String originalClassName;

    /**
     * @param originalClassName fully-qualified name of the exception type the activity threw
     *                          ({@code "unknown"} if none was recorded)
     * @param message           the original exception message, if any
     */
    public ReconstructedActivityFailure(String originalClassName, @Nullable String message) {
        super(message);
        this.originalClassName = Objects.requireNonNull(originalClassName, "originalClassName");
    }

    /** Returns the fully-qualified class name of the exception the activity originally threw. */
    public String originalClassName() {
        return originalClassName;
    }
}
