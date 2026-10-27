package io.b2mash.maestro.core.exception;

import org.jspecify.annotations.Nullable;

/**
 * Stands in, on replay, for an activity exception whose original type cannot be
 * safely re-created from the persisted failure record.
 *
 * <p>It is produced as the {@link ActivityExecutionException#getCause() cause}
 * of a replayed {@link ActivityExecutionException} in exactly three cases:
 * <ul>
 *   <li>the original type has no <em>public</em> {@code (String)} constructor;</li>
 *   <li>the original class is not on this node's classpath (or no type was
 *       recorded, reported as {@code "unknown"});</li>
 *   <li>the recorded type names an engine control-flow signal
 *       ({@link MaestroControlFlowError} subtype), which must never be
 *       instantiated from stored data.</li>
 * </ul>
 * The original message is preserved and {@link #originalClassName()} names the
 * type the activity actually threw, so workflow code can branch on it
 * identically on the live and replay paths.
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
        this.originalClassName = originalClassName;
    }

    /** Returns the fully-qualified class name of the exception the activity originally threw. */
    public String originalClassName() {
        return originalClassName;
    }
}
