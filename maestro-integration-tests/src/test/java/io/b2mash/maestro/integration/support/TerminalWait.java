package io.b2mash.maestro.integration.support;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Optional;

import static org.awaitility.Awaitility.await;

/**
 * The one correct way for an integration test to wait for a workflow to be
 * <em>finished</em> rather than merely <em>flagged as finished</em>.
 *
 * <h2>Why this exists</h2>
 * <p>{@code WorkflowExecutor} finalises a run with two separate,
 * non-transactional store calls, and the instance row goes first:
 *
 * <pre>{@code
 * if (transitionToTerminal(ctx, instance, COMPLETED, output)) {  // 1. UPDATE instance
 *     emit("workflowCompleted", ...);                           //    (observer)
 *     appendEvent(ctx, WORKFLOW_COMPLETED, null, output);       // 2. INSERT event
 * }
 * }</pre>
 *
 * <p>So there is a real, committed interval in which {@code getInstance(...)}
 * answers {@code COMPLETED}/{@code FAILED} while {@code getEvents(...)} is still
 * one event short. A test that gates on {@code status().isTerminal()} and then
 * asserts on the event log is asserting on a log the engine has not finished
 * writing. It passes on a fast machine, and reddens {@code main} on CI — which
 * is exactly what happened to
 * {@code EnginePostgresParallelIT.parallelReplay_resumesOnlyTheUnfinishedBranch}
 * (GitHub Actions run 30728290264: five events expected, four observed, the
 * missing one being {@code 5001:WORKFLOW_COMPLETED}).
 *
 * <p>The fix is not a sleep and not a longer timeout — those only move the odds.
 * The fix is to wait for the condition the assertion actually depends on: the
 * terminal event being present in the durable log. The event log is the truth;
 * the status column is an advisory hint that runs ahead of it.
 *
 * <h2>The {@code TERMINATED} exemption</h2>
 * <p>{@code terminate()} moves the instance row to {@link WorkflowStatus#TERMINATED}
 * and publishes a {@code WORKFLOW_TERMINATED} <em>lifecycle</em> event, but there
 * is no {@code WORKFLOW_TERMINATED} member of {@link EventType} — nothing is
 * appended to the durable log. Requiring an event there would hang forever, so
 * {@code TERMINATED} settles on the status alone. Every other terminal status is
 * required to carry its event.
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless and static; safe to call from any thread.
 */
public final class TerminalWait {

    private TerminalWait() {
    }

    /**
     * Reports whether a run is finished in the durable log, not merely flagged
     * as finished on the instance row.
     *
     * <p>The expected event is derived from the observed status rather than
     * accepting <em>either</em> terminal event. Accepting either would let a
     * {@code COMPLETED} instance be satisfied by a leftover
     * {@code WORKFLOW_FAILED} from an earlier attempt of a retried workflow, and
     * the wait would return before the {@code WORKFLOW_COMPLETED} the caller
     * actually asserts on had landed. That is unreachable today only because
     * {@code AbstractJdbcWorkflowStore.deleteFailureEvents} strips
     * {@code WORKFLOW_FAILED} before every retry — a dependency this predicate
     * has no business relying on, so it does not.
     *
     * @param store    the store to read through
     * @param instance the instance to judge
     * @return {@code true} once the status is terminal <em>and</em> the matching
     *         terminal event has been appended (or the status is
     *         {@link WorkflowStatus#TERMINATED}, which appends none)
     */
    public static boolean isFinalised(WorkflowStore store, WorkflowInstance instance) {
        var expected = terminalEventFor(instance.status());
        if (expected == null) {
            return instance.status() == WorkflowStatus.TERMINATED;
        }
        return store.getEvents(instance.id()).stream()
                .anyMatch(e -> e.eventType() == expected);
    }

    /**
     * Waits until a workflow is finalised as defined by
     * {@link #isFinalised(WorkflowStore, WorkflowInstance)}.
     *
     * @param store      the store to read through
     * @param workflowId the business workflow ID
     * @param timeout    the bound — be generous, this is a stability knob, and
     *                   never the mechanism by which the wait becomes correct
     * @return the finalised instance
     * @throws org.awaitility.core.ConditionTimeoutException if the workflow does
     *         not finalise in time. A terminal status that never grows its
     *         terminal event is an engine defect, not a timing artefact, and is
     *         meant to fail loudly here rather than be waited out.
     */
    public static WorkflowInstance awaitTerminal(WorkflowStore store, String workflowId,
                                                 Duration timeout) {
        await().atMost(timeout)
                .pollInterval(Duration.ofMillis(50))
                .until(() -> store.getInstance(workflowId)
                        .map(i -> isFinalised(store, i))
                        .orElse(false));
        return store.getInstance(workflowId).orElseThrow();
    }

    /**
     * Waits until a workflow reaches a specific status, additionally requiring
     * the terminal event when that status is terminal.
     *
     * @param store      the store to read through
     * @param workflowId the business workflow ID
     * @param expected   the awaited status
     * @param timeout    the bound
     * @return the instance in that status
     */
    public static WorkflowInstance awaitStatus(WorkflowStore store, String workflowId,
                                               WorkflowStatus expected, Duration timeout) {
        await().atMost(timeout)
                .pollInterval(Duration.ofMillis(50))
                .until(() -> matches(store, store.getInstance(workflowId), expected));
        return store.getInstance(workflowId).orElseThrow();
    }

    private static boolean matches(WorkflowStore store, Optional<WorkflowInstance> instance,
                                   WorkflowStatus expected) {
        return instance
                .map(i -> i.status() == expected
                        && (!expected.isTerminal() || isFinalised(store, i)))
                .orElse(false);
    }

    /**
     * @param status a workflow status
     * @return the event that closes a run in that status, or {@code null} when
     *         the status is non-terminal or is {@link WorkflowStatus#TERMINATED}
     */
    private static @Nullable EventType terminalEventFor(WorkflowStatus status) {
        return switch (status) {
            case COMPLETED -> EventType.WORKFLOW_COMPLETED;
            case FAILED -> EventType.WORKFLOW_FAILED;
            default -> null;
        };
    }
}
