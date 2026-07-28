package io.b2mash.maestro.core.exception;

/**
 * Thrown when a distributed-lock backend operation fails for infrastructure
 * reasons (for example a lost database connection or an unreachable
 * Valkey/Redis server).
 *
 * <p>This is distinct from {@link LockAcquisitionException}, which signals
 * lock <em>contention</em> — another instance holds the lock. A backend
 * failure means the outcome of the operation is unknown, so callers should
 * treat it as "backend unavailable" (degrade gracefully or retry) rather
 * than as lost ownership or contention.
 */
public final class LockBackendException extends MaestroException {

    private final String key;

    /**
     * @param message descriptive error message
     * @param key     the lock key the failed operation targeted
     * @param cause   the underlying backend failure (e.g., a {@code SQLException})
     */
    public LockBackendException(String message, String key, Throwable cause) {
        super(message, cause);
        this.key = key;
    }

    /** Returns the lock key the failed operation targeted. */
    public String key() {
        return key;
    }
}
