package io.b2mash.maestro.core;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one correct way for a {@code maestro-core} test to wait for a workflow to
 * be <em>finished</em> rather than merely <em>flagged as finished</em>.
 *
 * <h2>Why this exists</h2>
 * <p>Until Issue 26, {@code WorkflowExecutor} finalised a run with two
 * separate, non-transactional store calls, and the instance row went first
 * (an {@code UPDATE} to {@code COMPLETED}/{@code FAILED}, then the
 * {@code INSERT} of the terminal event). It now writes both in one atomic
 * {@code WorkflowStore.completeInstance} call, so the gap described below is
 * closed at the source; this predicate stays because it names what the
 * assertions that follow depend on, and it is still correct.
 *
 * <p>Before that fix there was a real, committed interval in which {@code getInstance(...)}
 * answered {@code COMPLETED}/{@code FAILED} while {@code getEvents(...)} was still
 * one event short. A test that gated on the status and then asserted on the event
 * log was asserting on a log the engine had not finished writing.
 *
 * <p>This module's fakes are in-memory, so the window is a thread preemption
 * between two field writes rather than a database round trip — rarer than the
 * Postgres case that reddened CI (GitHub Actions run 30728290264), but the same
 * defect, and a 20&nbsp;ms poll interval is more than wide enough to land in it.
 *
 * <p>The fix is not a sleep and not a longer timeout — those only move the odds.
 * The fix is to wait for the condition the assertion actually depends on: the
 * terminal event being present in the durable log. The event log is the truth;
 * the status column is an advisory hint that runs ahead of it.
 *
 * <p>The integration-test suite has its own copy of this predicate
 * ({@code io.b2mash.maestro.integration.support.TerminalWait}), and
 * {@code maestro-test}'s shipped {@code TestWorkflowHandle} enforces it for
 * downstream users. The duplication is deliberate: {@code maestro-core} has no
 * test-fixtures dependency on either module, and adding one to share nine lines
 * of predicate would cost more than it saves.
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless and static; safe to call from any thread.
 */
public final class TestTerminalWait {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(20);

    private TestTerminalWait() {
    }

    /**
     * Reports whether the event that closes a run is in the durable log.
     *
     * <p>The expected event is derived from the observed status rather than
     * accepting <em>either</em> terminal event: a {@code COMPLETED} run must
     * show {@code WORKFLOW_COMPLETED} specifically, so a leftover
     * {@code WORKFLOW_FAILED} from an earlier attempt of a retried workflow can
     * never satisfy the wait. That keeps the predicate self-contained instead of
     * depending on {@code deleteFailureEvents} having stripped the old memo
     * first.
     *
     * @param store    the store to read through
     * @param instance the instance to judge
     * @return {@code true} once the status is terminal <em>and</em> the matching
     *         terminal event has been appended; trivially {@code true} for
     *         {@link WorkflowStatus#TERMINATED}, which appends none
     */
    public static boolean isFinalised(WorkflowStore store, WorkflowInstance instance) {
        var expected = terminalEventFor(instance.status());
        if (expected == null) {
            // Not terminal, or TERMINATED — which appends no event at all, so
            // requiring one would hang forever.
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
     * @param timeout    the bound — a stability knob, never the mechanism by
     *                   which the wait becomes correct
     * @throws org.awaitility.core.ConditionTimeoutException if the workflow does
     *         not finalise in time. A terminal status that never grows its
     *         terminal event is an engine defect, not a timing artefact, and is
     *         meant to fail loudly here rather than be waited out.
     */
    public static void awaitTerminal(WorkflowStore store, String workflowId, Duration timeout) {
        await().atMost(timeout)
                .pollInterval(POLL_INTERVAL)
                .until(() -> store.getInstance(workflowId)
                        .map(i -> isFinalised(store, i))
                        .orElse(false));
    }

    /**
     * Waits until a workflow reaches a specific status, additionally requiring
     * the matching terminal event when that status is terminal.
     *
     * <p>Non-terminal statuses ({@code WAITING_SIGNAL}, {@code WAITING_TIMER},
     * …) are unaffected — they are single writes with no trailing event.
     *
     * @param store      the store to read through
     * @param workflowId the business workflow ID
     * @param expected   the awaited status
     * @param timeout    the bound
     */
    public static void awaitStatus(WorkflowStore store, String workflowId,
                                   WorkflowStatus expected, Duration timeout) {
        await().atMost(timeout)
                .pollInterval(POLL_INTERVAL)
                .untilAsserted(() -> {
                    var instance = store.getInstance(workflowId).orElseThrow(
                            () -> new AssertionError(
                                    "no instance stored for workflow '" + workflowId + "'"));
                    assertEquals(expected, instance.status());
                    assertTrue(!expected.isTerminal() || isFinalised(store, instance),
                            () -> "the instance row reads " + expected + " but the log has no "
                                    + terminalEventFor(expected) + " yet — the run is not finished");
                });
    }

    /**
     * @param status a workflow status
     * @return the event that closes a run in that status, or {@code null} when
     *         the status is non-terminal or is {@link WorkflowStatus#TERMINATED}
     *         (there is no {@code WORKFLOW_TERMINATED} member of
     *         {@link EventType} — terminating publishes only a lifecycle event)
     */
    private static @Nullable EventType terminalEventFor(WorkflowStatus status) {
        return switch (status) {
            case COMPLETED -> EventType.WORKFLOW_COMPLETED;
            case FAILED -> EventType.WORKFLOW_FAILED;
            default -> null;
        };
    }
}
