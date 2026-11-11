package io.b2mash.maestro.core.exception;

/**
 * Thrown by a {@link io.b2mash.maestro.core.spi.WorkflowStore} when an operation
 * failed because the <em>store</em> failed — the database is unreachable or
 * restarting, the connection pool timed out, a transaction was chosen as a
 * serialization or deadlock victim, the server ran short of resources, or the
 * schema does not match — rather than because of the data the workflow
 * supplied.
 *
 * <p>A store fault says nothing about the workflow: its history is intact and
 * the operation may succeed if retried once the store recovers. It must
 * therefore never be recorded as a workflow failure or trigger compensation.
 * Contrast a data error (for a SQL store, SQLSTATE class {@code 22} data
 * exception, class {@code 23} integrity-constraint violation not otherwise
 * mapped to a domain exception, or class {@code 54} program limit exceeded,
 * such as an oversized payload), which the workflow's own values caused and
 * which retrying cannot fix.
 *
 * <p>Whether an operation that threw this had any durable effect is unknown
 * to the caller (a commit may have been lost in flight); callers that retry
 * must rely on the store's idempotency guarantees — for example the
 * {@code (workflow_instance_id, sequence_number)} uniqueness that turns a
 * replayed append into a {@link DuplicateEventException}.
 *
 * <p><b>Thread safety:</b> immutable once constructed; safe to share across
 * threads.
 */
public final class StoreUnavailableException extends MaestroException {

    /**
     * Creates a store-unavailable exception.
     *
     * @param message descriptive error message, typically naming the failed
     *                operation or statement
     * @param cause   the underlying store failure (e.g. a {@code SQLException})
     */
    public StoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
