package io.maestro.core.exception;

/**
 * Thrown when JSON serialization or deserialization of workflow payloads fails.
 *
 * <p>Wraps Jackson processing exceptions. The original cause is available
 * via {@link #getCause()}.
 */
public final class SerializationException extends MaestroException {

    /**
     * @param message descriptive error message
     * @param cause   the underlying Jackson exception
     */
    public SerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
