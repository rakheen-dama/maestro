package io.b2mash.maestro.core.exception;

/**
 * Thrown when a workflow registration's factory
 * ({@code WorkflowRegistration.workflowFactory()}) fails to build the workflow
 * object for a run. The run is never launched: no virtual thread is created
 * and the instance lock is released.
 *
 * <h2>Where it comes from</h2>
 * <p>{@code WorkflowExecutor} calls the factory exactly once per run, on the
 * launching thread, before the run's thread exists. A {@link RuntimeException}
 * the factory throws — for example a Spring {@code BeanCreationException}
 * from a prototype bean whose wiring is broken, which surfaces only at the
 * first {@code getBean}, not at context start — is wrapped in this exception
 * with the original as its {@linkplain #getCause() cause}. An {@link Error}
 * the factory throws is not wrapped; it propagates unchanged.
 *
 * <h2>How it is handled</h2>
 * <ul>
 *   <li>{@code startWorkflow}, {@code resumeWorkflow} and {@code retryWorkflow}
 *       propagate it to their caller. Any instance row already written stays
 *       active, so the recovery poller adopts it once the factory works.</li>
 *   <li>{@code recoverWorkflows} catches <em>only</em> this exception per
 *       instance, logs it at {@code ERROR} (with the stack trace for the
 *       first instance of each workflow type in the pass, as one line for the
 *       rest) and moves on to the next instance — one workflow type whose
 *       objects cannot be built must not starve the recovery of every other
 *       type. The pass then logs one {@code WARN} summary counting the
 *       skipped instances and types. Every other exception still ends the
 *       pass.</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> immutable.
 */
public final class WorkflowInstantiationException extends MaestroException {

    private final String workflowId;
    private final String workflowType;

    /**
     * @param workflowId   the business ID of the run whose object could not be built
     * @param workflowType the workflow type whose factory failed
     * @param cause        what the factory threw
     */
    public WorkflowInstantiationException(String workflowId, String workflowType, Throwable cause) {
        super("Could not build the workflow object for workflow '%s' (type=%s): %s"
                .formatted(workflowId, workflowType, cause), cause);
        this.workflowId = workflowId;
        this.workflowType = workflowType;
    }

    /** @return the business ID of the run whose object could not be built */
    public String workflowId() {
        return workflowId;
    }

    /** @return the workflow type whose factory failed */
    public String workflowType() {
        return workflowType;
    }
}
