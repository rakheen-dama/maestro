package io.maestro.core.exception;

/**
 * Base exception for all Maestro workflow engine errors.
 *
 * <p>This is a {@code sealed} class — all permitted subtypes are defined
 * in the {@code io.maestro.core.exception} package, enabling exhaustive
 * pattern matching in {@code switch} expressions:
 *
 * <pre>{@code
 * switch (exception) {
 *     case OptimisticLockException e -> retryUpdate(e);
 *     case WorkflowNotFoundException e -> handleMissing(e);
 *     // ... compiler ensures all subtypes handled
 * }
 * }</pre>
 *
 * <p>All subtypes are unchecked ({@link RuntimeException}) because workflow
 * code cannot meaningfully recover from most engine-level failures. Specific
 * subtypes allow targeted catching where recovery IS possible.
 */
public sealed class MaestroException extends RuntimeException
        permits WorkflowNotFoundException,
                WorkflowAlreadyExistsException,
                OptimisticLockException,
                DuplicateEventException,
                InvalidStateTransitionException,
                WorkflowExecutionException,
                ActivityExecutionException,
                SignalTimeoutException,
                LockAcquisitionException,
                SerializationException {

    /**
     * Creates a new exception with the given message.
     *
     * @param message descriptive error message
     */
    protected MaestroException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with the given message and cause.
     *
     * @param message descriptive error message
     * @param cause   the underlying cause
     */
    protected MaestroException(String message, Throwable cause) {
        super(message, cause);
    }
}
