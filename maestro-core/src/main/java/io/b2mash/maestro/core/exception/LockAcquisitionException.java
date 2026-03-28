package io.b2mash.maestro.core.exception;

/**
 * Thrown when a distributed lock cannot be acquired.
 *
 * <p>This typically indicates lock contention — another instance
 * holds the lock. Callers may retry after a backoff.
 */
public final class LockAcquisitionException extends MaestroException {

    private final String key;

    /**
     * @param key the lock key that could not be acquired
     */
    public LockAcquisitionException(String key) {
        super("Failed to acquire lock: " + key);
        this.key = key;
    }

    /**
     * @param key   the lock key that could not be acquired
     * @param cause the underlying exception (e.g., Redis/Valkey connection failure)
     */
    public LockAcquisitionException(String key, Throwable cause) {
        super("Failed to acquire lock: " + key, cause);
        this.key = key;
    }

    /** Returns the lock key that could not be acquired. */
    public String key() {
        return key;
    }
}
