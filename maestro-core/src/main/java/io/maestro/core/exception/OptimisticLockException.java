package io.maestro.core.exception;

/**
 * Thrown when a workflow instance update fails due to a version conflict.
 *
 * <p>This indicates a concurrent modification — another thread or process
 * updated the workflow instance between the read and the write. Callers
 * should typically retry the operation with a fresh read.
 */
public final class OptimisticLockException extends MaestroException {

    private final String workflowId;
    private final int expectedVersion;
    private final int actualVersion;

    /**
     * @param workflowId      the workflow ID that had a version conflict
     * @param expectedVersion the version the caller expected
     * @param actualVersion   the version found in the store
     */
    public OptimisticLockException(String workflowId, int expectedVersion, int actualVersion) {
        super("Optimistic lock conflict on workflow '%s': expected version %d but found %d"
                .formatted(workflowId, expectedVersion, actualVersion));
        this.workflowId = workflowId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    /** Returns the workflow ID that had a version conflict. */
    public String workflowId() {
        return workflowId;
    }

    /** Returns the version the caller expected. */
    public int expectedVersion() {
        return expectedVersion;
    }

    /** Returns the version found in the store. */
    public int actualVersion() {
        return actualVersion;
    }
}
