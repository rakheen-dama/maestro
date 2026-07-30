package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.WorkflowTerminatedException;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Writes a running workflow's non-terminal status transition — the
 * {@code WAITING_SIGNAL} / {@code WAITING_TIMER} / {@code RUNNING} moves a
 * workflow's own thread makes as it parks and wakes.
 *
 * <p>Shared by {@link SignalManager} and {@link DefaultWorkflowOperations},
 * which both used to carry their own copy of this write. They are unified here
 * because the interesting part is not the write but the guard in front of it,
 * and a guard that exists in two copies is a guard that drifts.
 *
 * <h2>The terminal guard</h2>
 * <p>A workflow's own thread is not the only writer of its instance row.
 * {@code WorkflowExecutor.terminateWorkflow} can write {@code TERMINATED} from
 * <em>any</em> node, and another runner of the same workflow can finalise it as
 * {@code COMPLETED} or {@code FAILED}. Both leave a parked or waking thread
 * about to write {@code RUNNING} against a fresh read — which used to succeed,
 * silently resurrecting a terminal workflow and letting the run go on to
 * finalise itself with an outcome that contradicts the operator's terminate.
 *
 * <p>So a freshly-read terminal status is never overwritten:
 * <ul>
 *   <li>{@code TERMINATED} throws {@link WorkflowTerminatedException} — the run
 *       must stop now, without compensation, and the {@code Error} type keeps a
 *       workflow's own {@code catch (Exception)} from swallowing it.</li>
 *   <li>{@code COMPLETED} / {@code FAILED} log and stand down, preserving the
 *       existing "another runner finalised it first" convergence: that outcome
 *       is already the durable truth and this run has nothing to add.</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> stateless; safe for concurrent use. The read and the
 * write are not atomic — the store's optimistic {@code version} check is what
 * rejects a write whose read went stale.
 */
final class InstanceStatusWriter {

    private static final Logger logger = LoggerFactory.getLogger(InstanceStatusWriter.class);

    private InstanceStatusWriter() {
    }

    /**
     * Moves a workflow to a non-terminal status, standing down if the instance
     * has since reached a terminal state.
     *
     * @param store      the workflow store to write through
     * @param workflowId the workflow's business ID
     * @param newStatus  the non-terminal status to write
     * @throws WorkflowTerminatedException if the instance is already
     *                                     {@code TERMINATED} — the caller's run
     *                                     must unwind without writing anything
     * @throws io.b2mash.maestro.core.exception.OptimisticLockException if another
     *                                     writer touched the row between the read
     *                                     and the write
     */
    static void write(WorkflowStore store, String workflowId, WorkflowStatus newStatus) {
        var instance = store.getInstance(workflowId);
        if (instance.isEmpty()) {
            logger.warn("Cannot update status to {} — workflow '{}' not found", newStatus, workflowId);
            return;
        }
        var current = instance.get();
        if (current.status() == WorkflowStatus.TERMINATED) {
            logger.info("Workflow '{}' is TERMINATED — not writing {}; abandoning this run",
                    workflowId, newStatus);
            throw new WorkflowTerminatedException(workflowId, null);
        }
        if (current.status().isTerminal()) {
            logger.warn("Workflow '{}' is already {} — another runner finalised it first; "
                            + "not overwriting with {}",
                    workflowId, current.status(), newStatus);
            return;
        }
        store.updateInstance(current.toBuilder()
                .status(newStatus)
                .updatedAt(Instant.now())
                .version(current.version() + 1)
                .build());
    }
}
