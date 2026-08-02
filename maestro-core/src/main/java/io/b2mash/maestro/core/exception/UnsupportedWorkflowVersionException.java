package io.b2mash.maestro.core.exception;

/**
 * A workflow replayed a versioning decision the running code no longer
 * supports: the recorded (or default) version for {@code changeId} is below the
 * {@code minSupported} the code now declares. The branch this instance needs
 * has been removed from the workflow definition.
 *
 * <p>This is a genuine, deterministic workflow failure — it will fail the same
 * way on every node until the code carrying the old branch is restored — so
 * unlike the engine's control-flow signals
 * ({@link ExecutorShutdownException}, {@code WorkflowTerminatedException},
 * both {@link Error}s) it extends {@link MaestroException} and is catchable by
 * workflow authors. After restoring the branch (or migrating the instance), the
 * admin Retry action re-drives the workflow normally: retry deletes the failure
 * memos but never the {@code VERSION_MARKER}, so the retried run replays the
 * same recorded version against the restored branch.
 *
 * <p><b>Thread safety:</b> immutable; safe to share.
 *
 * @see io.b2mash.maestro.core.context.WorkflowContext#version(String, int, int)
 */
public final class UnsupportedWorkflowVersionException extends MaestroException {

    private final String workflowId;
    private final String changeId;
    private final int recordedVersion;
    private final int minSupported;
    private final int maxSupported;

    /**
     * @param workflowId      the workflow whose recorded version is unsupported
     * @param changeId        the change-id whose branch was removed
     * @param recordedVersion the version resolved from history (or
     *                        {@code DEFAULT_VERSION} for a pre-change history)
     * @param minSupported    the lowest version the running code carries
     * @param maxSupported    the highest version the running code carries
     */
    public UnsupportedWorkflowVersionException(String workflowId, String changeId,
            int recordedVersion, int minSupported, int maxSupported) {
        super(("Workflow '%s' recorded version %d for change '%s', but the running "
                + "code supports only [%d..%d]. The branch this instance needs has "
                + "been removed — restore code supporting version %d (or migrate "
                + "the instance) and retry.")
                .formatted(workflowId, recordedVersion, changeId,
                        minSupported, maxSupported, recordedVersion));
        this.workflowId = workflowId;
        this.changeId = changeId;
        this.recordedVersion = recordedVersion;
        this.minSupported = minSupported;
        this.maxSupported = maxSupported;
    }

    /** Returns the workflow whose recorded version is unsupported. */
    public String workflowId() {
        return workflowId;
    }

    /** Returns the change-id whose branch the running code no longer carries. */
    public String changeId() {
        return changeId;
    }

    /** Returns the version resolved from this instance's history. */
    public int recordedVersion() {
        return recordedVersion;
    }

    /** Returns the lowest version the running code declares support for. */
    public int minSupported() {
        return minSupported;
    }

    /** Returns the highest version the running code declares support for. */
    public int maxSupported() {
        return maxSupported;
    }
}
