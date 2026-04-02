package io.b2mash.maestro.core.context;

import org.slf4j.MDC;

/**
 * Utility for managing SLF4J MDC keys during workflow execution.
 *
 * <p>Centralises the MDC key names and populate/clear lifecycle so that
 * every virtual-thread entry point ({@code WorkflowExecutor},
 * parallel branches, compensation branches) uses a consistent set of keys.
 *
 * <p>SLF4J MDC is still {@link ThreadLocal}-based. Each virtual thread
 * gets its own MDC state, so populate/clear remains correct. When SLF4J
 * adds {@link ScopedValue}-backed MDC support, this utility will be the
 * single point of migration.
 *
 * <h2>Thread Safety</h2>
 * <p>MDC operations are per-thread. This class is stateless and safe
 * to call from any thread.
 */
public final class WorkflowMDC {

    /** MDC key for the business workflow ID. */
    public static final String KEY_WORKFLOW_ID = "workflowId";

    /** MDC key for the current run ID. */
    public static final String KEY_RUN_ID = "runId";

    /** MDC key for the workflow type name. */
    public static final String KEY_WORKFLOW_TYPE = "workflowType";

    private WorkflowMDC() {
        // utility class
    }

    /**
     * Populates MDC with the base workflow keys from the given context.
     *
     * <p>Call this at the top of each virtual-thread body that executes
     * workflow or branch logic. Always pair with {@link #clear()} in a
     * {@code finally} block.
     *
     * @param ctx the workflow context to read identity from
     */
    public static void populate(WorkflowContext ctx) {
        MDC.put(KEY_WORKFLOW_ID, ctx.workflowId());
        MDC.put(KEY_RUN_ID, ctx.runId().toString());
        MDC.put(KEY_WORKFLOW_TYPE, ctx.workflowType());
    }

    /**
     * Removes the base workflow MDC keys from the current thread.
     */
    public static void clear() {
        MDC.remove(KEY_WORKFLOW_ID);
        MDC.remove(KEY_RUN_ID);
        MDC.remove(KEY_WORKFLOW_TYPE);
    }
}
