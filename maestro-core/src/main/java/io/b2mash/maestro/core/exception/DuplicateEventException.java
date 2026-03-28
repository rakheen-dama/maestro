package io.b2mash.maestro.core.exception;

import java.util.UUID;

/**
 * Thrown when attempting to append a workflow event with a sequence number
 * that already exists for the given workflow instance.
 *
 * <p>This is a safety mechanism — the {@code (workflow_instance_id, sequence_number)}
 * unique constraint in the store prevents duplicate activity results from being
 * persisted, which would corrupt the memoization log.
 */
public final class DuplicateEventException extends MaestroException {

    private final UUID workflowInstanceId;
    private final int sequenceNumber;

    /**
     * @param workflowInstanceId the workflow instance UUID
     * @param sequenceNumber     the duplicate sequence number
     */
    public DuplicateEventException(UUID workflowInstanceId, int sequenceNumber) {
        super("Duplicate event at sequence %d for workflow instance %s"
                .formatted(sequenceNumber, workflowInstanceId));
        this.workflowInstanceId = workflowInstanceId;
        this.sequenceNumber = sequenceNumber;
    }

    /** Returns the workflow instance UUID. */
    public UUID workflowInstanceId() {
        return workflowInstanceId;
    }

    /** Returns the duplicate sequence number. */
    public int sequenceNumber() {
        return sequenceNumber;
    }
}
