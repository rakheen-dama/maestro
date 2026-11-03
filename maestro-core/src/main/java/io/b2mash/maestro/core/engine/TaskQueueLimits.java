package io.b2mash.maestro.core.engine;

/**
 * The per-node concurrency limits of one task queue, enforced by
 * {@link TaskQueueGovernor}.
 *
 * <p>Bound in the Spring Boot starter from
 * {@code maestro.worker.task-queues[].concurrency} and
 * {@code maestro.worker.task-queues[].activity-concurrency}. A task queue is the
 * bucket a workflow names in {@code @DurableWorkflow(taskQueue = ...)}.
 *
 * @param workflowConcurrency how many workflow runs on the queue may execute at
 *                            once on this node; a run parked on {@code sleep()} or
 *                            {@code awaitSignal()} does not count — must be {@code >= 1}
 * @param activityConcurrency how many live activity executions on the queue may run
 *                            at once on this node; replayed (memoized) steps never
 *                            count — must be {@code >= 1}
 */
public record TaskQueueLimits(int workflowConcurrency, int activityConcurrency) {

    /**
     * Validates both limits.
     *
     * @throws IllegalArgumentException if either limit is less than {@code 1}
     */
    public TaskQueueLimits {
        if (workflowConcurrency < 1) {
            throw new IllegalArgumentException(
                    "workflowConcurrency must be >= 1, got " + workflowConcurrency);
        }
        if (activityConcurrency < 1) {
            throw new IllegalArgumentException(
                    "activityConcurrency must be >= 1, got " + activityConcurrency);
        }
    }
}
