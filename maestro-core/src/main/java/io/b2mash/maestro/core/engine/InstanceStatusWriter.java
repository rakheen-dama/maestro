package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.OptimisticLockException;
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
 *       must stop now, without compensation — terminate never <em>starts</em> a
 *       compensation, though one open race in the sibling writer can let an
 *       already-starting one continue (a terminate landing between
 *       {@code SagaManager.transitionToCompensating}'s terminal-status check
 *       and its own status write; {@code docs/open-issues.md} Issue 22) — and
 *       the {@code Error} type keeps a
 *       workflow's own {@code catch (Exception)} from swallowing it.</li>
 *   <li>{@code COMPLETED} / {@code FAILED} log and stand down, preserving the
 *       existing "another runner finalised it first" convergence: that outcome
 *       is already the durable truth and this run has nothing to add.</li>
 * </ul>
 *
 * <h2>The version conflict</h2>
 * <p>The read and the write are not atomic, and this row has more than one
 * concurrent writer <em>within a single run</em>: every branch thread of a
 * {@code parallel()} fork writes its own park status into the one instance row.
 * Two branches that park together — the shape {@code docs/cross-service.md}
 * sells, "fan out and await both replies" — interleave read/read/write/write
 * and the loser's compare-and-set finds a bumped version.
 *
 * <p>That conflict used to propagate: out of {@code awaitSignal()}/{@code
 * sleep()}, out of {@code parallel()} into workflow author code, into
 * {@code WorkflowExecutor.executeWorkflow}'s generic {@code catch (Exception)},
 * and so into a workflow recorded {@code FAILED} with saga compensations run —
 * real refunds and inventory releases for work that never failed. {@code FAILED}
 * is not {@code isActive()}, so recovery never healed it, and {@code
 * retryWorkflow} refuses a compensated saga.
 *
 * <p>So a persistence conflict is not a workflow outcome here either. It is
 * resolved the way {@code WorkflowExecutor.transitionToTerminal} resolves the
 * same conflict at the terminal write: a bounded retry against a
 * <em>fresh</em> read — fresh because the terminal guard above must be
 * re-evaluated each attempt, the row having possibly gone {@code TERMINATED} or
 * been finalised by another runner in the meantime. On exhaustion this stands
 * down rather than propagating: the status column is an advisory hint for the
 * recovery poller's {@code isActive()} filter, the event log is the durable
 * truth, and every status this method writes is non-terminal, so a lost write
 * leaves the instance active and recoverable. A stale {@code RUNNING} where
 * {@code WAITING_SIGNAL} belonged costs nothing; a workflow wrongly recorded
 * {@code FAILED} costs a saga.
 *
 * <p>The retries are immediate — no backoff, no jitter — matching
 * {@code transitionToTerminal}, whose budget this shares. Two branches need one
 * retry between them, but a wide fan-out whose branches park in lockstep gives
 * every writer O(N) chances to lose, so exhaustion is likelier there than the
 * two-branch case suggests. That is deliberate rather than overlooked: the
 * consequence of exhaustion stays bounded to a stale <em>active</em> status on
 * a workflow that is otherwise unaffected, which is not worth paying for with a
 * second retry policy alongside the engine's existing one.
 *
 * <p><b>Thread safety:</b> stateless; safe for concurrent use, including from
 * several branch threads of one {@code parallel()} fork writing the same
 * instance row at once — which is exactly what the retry loop exists for.
 */
final class InstanceStatusWriter {

    private static final Logger logger = LoggerFactory.getLogger(InstanceStatusWriter.class);

    /**
     * How many times a losing compare-and-set is retried against a fresh read
     * before this stands down. Matches {@code WorkflowExecutor}'s
     * {@code TERMINAL_TRANSITION_ATTEMPTS}; deliberately the same number,
     * because it bounds the same conflict on the same row.
     */
    static final int STATUS_WRITE_ATTEMPTS = 5;

    private InstanceStatusWriter() {
    }

    /**
     * Moves a workflow to a non-terminal status, standing down if the instance
     * has since reached a terminal state, and retrying a lost compare-and-set
     * against a fresh read rather than turning it into a workflow outcome.
     *
     * @param store      the workflow store to write through
     * @param workflowId the workflow's business ID
     * @param newStatus  the non-terminal status to write
     * @throws WorkflowTerminatedException if the instance is already
     *                                     {@code TERMINATED} — the caller's run
     *                                     must unwind without writing anything.
     *                                     This is an {@code Error}, not a
     *                                     {@code MaestroException}, so a
     *                                     workflow's own {@code catch
     *                                     (Exception)} cannot swallow it
     */
    static void write(WorkflowStore store, String workflowId, WorkflowStatus newStatus) {
        for (var attempt = 1; ; attempt++) {
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
            try {
                store.updateInstance(current.toBuilder()
                        .status(newStatus)
                        .updatedAt(Instant.now())
                        .version(current.version() + 1)
                        .build());
                return;
            } catch (OptimisticLockException e) {
                if (attempt >= STATUS_WRITE_ATTEMPTS) {
                    // Stand down. Propagating here would escape the park call
                    // into workflow code and record a run that never failed as
                    // FAILED — the very bug this loop exists to prevent. The
                    // instance keeps its current status, which is non-terminal
                    // (the guards above returned otherwise), so the workflow
                    // stays active and recoverable and the event log remains
                    // the truth about where it is.
                    logger.warn("Could not write status {} for workflow '{}' after {} attempts — "
                                    + "the instance row is being written continuously (concurrent "
                                    + "parallel branches?). Leaving the status as-is; the workflow "
                                    + "is unaffected and the event log governs.",
                            newStatus, workflowId, attempt);
                    return;
                }
                logger.debug("Version conflict writing status {} for workflow '{}' (attempt {}) — "
                                + "retrying against a fresh read", newStatus, workflowId, attempt);
            }
        }
    }
}
