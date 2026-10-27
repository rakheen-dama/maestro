package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.MaestroControlFlowError;
import io.b2mash.maestro.core.exception.ReconstructedActivityFailure;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deterministically rebuilds the exception recorded for a failed step (C15).
 *
 * <p>Replay must hand workflow code the same <em>kind</em> of cause on every
 * pass and on every node, independent of reflection accidents. The rule is:
 * the original type is re-created only when the stored name resolves to a
 * {@link Throwable} that is <b>not</b> a {@link MaestroControlFlowError} and
 * exposes a <b>public</b> {@code (String)} constructor; in every other case
 * (no such constructor, class not on the classpath, no type recorded, or a
 * control-flow signal name) the result is a {@link ReconstructedActivityFailure}
 * carrying the original class name and message. The result is never a bare
 * {@code RuntimeException}, and an engine control-flow signal is never
 * instantiated from stored data — doing so would make a replayed failure
 * stop the workflow's local run instead of failing it.
 *
 * <h2>Visibility</h2>
 * <p>Public for parity with {@link ReplayGuard}: replay reads outside this
 * package must apply the identical reconstruction. It is internal engine API —
 * not part of the embedder-facing surface — and carries no compatibility
 * promise.
 *
 * <p><b>Thread safety:</b> stateless; all methods are static and safe to call
 * from any thread.
 */
public final class FailureReconstruction {

    private static final Logger logger = LoggerFactory.getLogger(FailureReconstruction.class);

    private FailureReconstruction() {
    }

    /**
     * Rebuilds the recorded failure.
     *
     * @param exceptionType the recorded fully-qualified exception class name, or {@code null}
     * @param message       the recorded message, or {@code null}
     * @return the original type (public {@code (String)} constructor only) or a
     *         {@link ReconstructedActivityFailure}; never {@code null}
     */
    public static Throwable reconstruct(@Nullable String exceptionType, @Nullable String message) {
        if (exceptionType != null) {
            try {
                var clazz = Class.forName(exceptionType);
                if (Throwable.class.isAssignableFrom(clazz)
                        && !MaestroControlFlowError.class.isAssignableFrom(clazz)) {
                    return (Throwable) clazz.getConstructor(String.class).newInstance(message);
                }
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                logger.debug("Could not reconstruct exception type '{}'; replaying as ReconstructedActivityFailure",
                        exceptionType, e);
            }
        }
        return new ReconstructedActivityFailure(exceptionType != null ? exceptionType : "unknown", message);
    }
}
