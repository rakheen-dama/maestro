package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.ExceptionTypeResolver;
import io.b2mash.maestro.core.exception.MaestroControlFlowError;
import io.b2mash.maestro.core.exception.ReconstructedActivityFailure;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministically rebuilds the exception recorded for a failed step (C15).
 *
 * <p>Replay must hand workflow code the same <em>kind</em> of cause on every
 * pass and on every node, independent of reflection accidents. The rule is:
 * the original type is re-created only when the stored name resolves to a
 * {@link Throwable} that is <b>not</b> a {@link MaestroControlFlowError} and
 * exposes a <b>public</b> {@code (String)} constructor that completes normally
 * (so a package-private or private nested class, an abstract class and a
 * throwing constructor all fall back); in every other case
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
 *
 * <p>The stored name is resolved by {@link ExceptionTypeResolver#resolve}
 * <em>without initialising</em> the class; initialisation happens only at
 * instantiation, for a type that has passed the gate.
 */
public final class FailureReconstruction {

    private static final Logger logger = LoggerFactory.getLogger(FailureReconstruction.class);

    /** Types already reported at WARN, so a replay loop does not flood the log. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

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
            var clazz = ExceptionTypeResolver.resolve(exceptionType);
            if (clazz == null) {
                logger.debug("Exception type '{}' is not loadable; replaying as ReconstructedActivityFailure",
                        exceptionType);
            } else if (Throwable.class.isAssignableFrom(clazz)
                    && !MaestroControlFlowError.class.isAssignableFrom(clazz)) {
                try {
                    return (Throwable) clazz.getConstructor(String.class).newInstance(message);
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
}
