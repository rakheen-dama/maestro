package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.ActivityExecutionException;
import io.b2mash.maestro.core.exception.SignalTimeoutException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Computes what an admin Retry of a {@code FAILED} workflow deletes (WP3, D-3).
 *
 * <p>The rule, checked in this order:
 * <ol>
 *   <li><b>{@link RetryPlan.Basis#LEGACY LEGACY}</b> when the {@link LaneTree}
 *       walk leaves any event unplaced, or two lanes claim one slot
 *       ({@link LaneTree#ambiguous()}): exactly today's delete-by-type set —
 *       every {@code ACTIVITY_FAILED} and {@code WORKFLOW_FAILED}, plus, when
 *       a {@code WORKFLOW_FAILED} names {@link SignalTimeoutException}, the
 *       highest {@code SIGNAL_TIMEOUT} and its same-step {@code TIMER_SCHEDULED}
 *       just before it.</li>
 *   <li>The <b>attribution set</b> {@code A}: the {@code WORKFLOW_FAILED}
 *       payload's {@code failedSequences} that are placed
 *       {@code ACTIVITY_FAILED}/{@code SIGNAL_TIMEOUT} events
 *       ({@link RetryPlan.Basis#ATTRIBUTED ATTRIBUTED}); for a payload written
 *       before WP3 (no array), the one placed failure memo whose rebuilt
 *       exception message equals the payload's
 *       ({@link RetryPlan.Basis#MATCHED_MESSAGE MATCHED_MESSAGE}), none if zero
 *       or several match.</li>
 *   <li>{@code A} non-empty: for every {@code F} in it, delete
 *       {@code opSlots(F)}, {@link LaneTree#after(int) after(F)} and every
 *       {@code WORKFLOW_FAILED}. Nothing else.</li>
 *   <li>{@code A} empty ({@link RetryPlan.Basis#LAST_IN_LANE LAST_IN_LANE}):
 *       every {@code WORKFLOW_FAILED}, and {@code opSlots(F')} of every placed
 *       failure memo whose {@code after(F')} holds nothing but
 *       {@code WORKFLOW_FAILED}. One pass.</li>
 *   <li><b>Refusal</b>: a {@code SIGNAL_RECEIVED} in the delete set sets
 *       {@link RetryPlan#consumedSignalAt()} to the lowest such sequence.</li>
 *   <li><b>Timer ids</b>: the {@code timerId} of every deleted
 *       {@code TIMER_SCHEDULED}, and {@code "sleep-" + s} and
 *       {@code "await-signal-" + s} for every deleted sequence {@code s} (a
 *       row saved before its memo). Applied to every basis.</li>
 * </ol>
 * <p>{@code opSlots(F)} is {@code F}, plus {@code F - 1} when {@code F} is a
 * {@code SIGNAL_TIMEOUT} and {@code F - 1} a {@code TIMER_SCHEDULED} with the
 * same step name (a parked await's two slots).
 *
 * <p>Pure: no store, no clock, no logging, no {@code catch}.
 *
 * <p><b>Thread safety:</b> stateless; safe from any thread.
 */
final class RetryPlanner {

    private static final String ACTIVITY_EXECUTION_EXCEPTION = ActivityExecutionException.class.getName();
    private static final String SIGNAL_TIMEOUT_EXCEPTION = SignalTimeoutException.class.getName();
    private static final String SLEEP_TIMER_ID_PREFIX = "sleep-";
    private static final String AWAIT_TIMER_ID_PREFIX = SignalManager.TIMER_ID_PREFIX;

    private RetryPlanner() {
    }

    /**
     * Plans the Retry of the failed workflow whose history is {@code history}.
     *
     * @param workflowId the workflow's business id (the legacy message match needs it)
     * @param history    the instance's events, in any order
     * @return the plan; {@link RetryPlan#refused()} when it would delete a consumed signal's memo
     */
    static RetryPlan plan(String workflowId, List<WorkflowEvent> history) {
        var bySeq = new HashMap<Integer, WorkflowEvent>();
        for (var event : history) {
            bySeq.putIfAbsent(event.sequenceNumber(), event);
        }
        var tree = LaneTree.of(history);
        var workflowFailed = bySeq.values().stream()
                .filter(e -> e.eventType() == EventType.WORKFLOW_FAILED)
                .map(WorkflowEvent::sequenceNumber)
                .sorted()
                .toList();

        // 1. Anything unplaced, or a slot two lanes claim: the walk cannot be
        // trusted; today's plan.
        if (!tree.unplaced().isEmpty() || tree.ambiguous()) {
            return finish(RetryPlan.Basis.LEGACY, legacyDeletes(bySeq), bySeq);
        }

        var deletes = new TreeSet<Integer>(workflowFailed);

        // 2. Attribution.
        RetryPlan.Basis basis;
        List<Integer> attributed;
        var terminal = workflowFailed.isEmpty() ? null : bySeq.get(workflowFailed.getLast());
        var terminalPayload = terminal == null ? null : terminal.payload();
        var failedSequences = terminalPayload == null ? null : terminalPayload.get("failedSequences");
        if (failedSequences != null && failedSequences.isArray()) {
            attributed = new ArrayList<>();
            for (var element : failedSequences) {
                if (element.isIntegralNumber() && element.canConvertToInt()) {
                    var seq = element.intValue();
                    var event = bySeq.get(seq);
                    if (event != null && isFailureMemo(event) && tree.isPlaced(seq)) {
                        attributed.add(seq);
                    }
                }
            }
            basis = RetryPlan.Basis.ATTRIBUTED;
        } else {
            attributed = matchByMessage(workflowId, terminalPayload, bySeq, tree);
            basis = RetryPlan.Basis.MATCHED_MESSAGE;
        }

        if (!attributed.isEmpty()) {
            // 3. Truncate every attributed failure with its propagation path.
            for (var failure : attributed) {
                deletes.addAll(opSlots(failure, bySeq));
                deletes.addAll(tree.after(failure));
            }
            return finish(basis, deletes, bySeq);
        }

        // 4. Nothing attributed: a failure whose path recorded nothing.
        for (var event : bySeq.values()) {
            var seq = event.sequenceNumber();
            if (isFailureMemo(event) && tree.isPlaced(seq) && onlyWorkflowFailed(tree.after(seq), bySeq)) {
                deletes.addAll(opSlots(seq, bySeq));
            }
        }
        return finish(RetryPlan.Basis.LAST_IN_LANE, deletes, bySeq);
    }

    /** Steps 5 and 6: the refusal and the timer ids. */
    private static RetryPlan finish(RetryPlan.Basis basis, SortedSet<Integer> deletes,
                                    Map<Integer, WorkflowEvent> bySeq) {
        Integer consumedSignalAt = null;
        var timerIds = new TreeSet<String>();
        for (var seq : deletes) {
            var event = bySeq.get(seq);
            if (event != null && event.eventType() == EventType.SIGNAL_RECEIVED && consumedSignalAt == null) {
                consumedSignalAt = seq;
            }
            if (event != null && event.eventType() == EventType.TIMER_SCHEDULED && event.payload() != null) {
                var timerId = event.payload().get("timerId");
                if (timerId != null && timerId.isString()) {
                    timerIds.add(timerId.stringValue());
                }
            }
            timerIds.add(SLEEP_TIMER_ID_PREFIX + seq);
            timerIds.add(AWAIT_TIMER_ID_PREFIX + seq);
        }
        return new RetryPlan(basis, deletes, timerIds, consumedSignalAt);
    }

    /**
     * The pre-WP3 match: the one placed failure memo whose exception message,
     * rebuilt from the memo exactly as the engine formats it, equals the
     * payload's. Empty when none or several match.
     */
    private static List<Integer> matchByMessage(String workflowId, @Nullable JsonNode payload,
                                                Map<Integer, WorkflowEvent> bySeq, LaneTree tree) {
        if (payload == null) {
            return List.of();
        }
        var exceptionType = text(payload, "exceptionType");
        var message = text(payload, "message");
        if (exceptionType == null || message == null) {
            return List.of();
        }
        var candidates = new ArrayList<Integer>();
        for (var event : bySeq.values()) {
            if (!tree.isPlaced(event.sequenceNumber()) || event.stepName() == null) {
                continue;
            }
            var memo = event.payload();
            String rebuilt = null;
            if (ACTIVITY_EXECUTION_EXCEPTION.equals(exceptionType)
                    && event.eventType() == EventType.ACTIVITY_FAILED && memo != null) {
                // ActivityExecutionException's format over the memo ActivityInvocationHandler writes.
                var cause = text(memo, "message");
                if (cause == null) {
                    cause = text(memo, "exceptionType");
                }
                if (cause != null) {
                    rebuilt = "Activity '%s' failed in workflow '%s': %s"
                            .formatted(event.stepName(), workflowId, cause);
                }
            } else if (SIGNAL_TIMEOUT_EXCEPTION.equals(exceptionType)
                    && event.eventType() == EventType.SIGNAL_TIMEOUT && memo != null) {
                // SignalTimeoutException's format over SignalTimeoutDetail.
                var signalName = text(memo, "signalName");
                var timeout = text(memo, "timeout");
                if (signalName != null && timeout != null) {
                    rebuilt = "Signal '%s' timed out after %s for workflow '%s'"
                            .formatted(signalName, timeout, workflowId);
                }
            }
            if (message.equals(rebuilt)) {
                candidates.add(event.sequenceNumber());
            }
        }
        return candidates.size() == 1 ? candidates : List.of();
    }

    /**
     * Today's {@code deleteFailureEvents} set, on the event list
     * ({@code InMemoryWorkflowStore.deleteFailureEvents}).
     */
    private static SortedSet<Integer> legacyDeletes(Map<Integer, WorkflowEvent> bySeq) {
        var deletes = new TreeSet<Integer>();
        var failedByTimeout = false;
        var highestTimeout = -1;
        for (var event : bySeq.values()) {
            switch (event.eventType()) {
                case ACTIVITY_FAILED -> deletes.add(event.sequenceNumber());
                case WORKFLOW_FAILED -> {
                    deletes.add(event.sequenceNumber());
                    if (event.payload() != null
                            && SIGNAL_TIMEOUT_EXCEPTION.equals(text(event.payload(), "exceptionType"))) {
                        failedByTimeout = true;
                    }
                }
                case SIGNAL_TIMEOUT -> highestTimeout = Math.max(highestTimeout, event.sequenceNumber());
                default -> {
                }
            }
        }
        if (failedByTimeout && highestTimeout >= 0) {
            deletes.addAll(opSlots(highestTimeout, bySeq));
        }
        return deletes;
    }

    /** {@code F}, plus a parked await's same-step {@code TIMER_SCHEDULED} at {@code F - 1}. */
    private static List<Integer> opSlots(int failure, Map<Integer, WorkflowEvent> bySeq) {
        var event = bySeq.get(failure);
        var park = bySeq.get(failure - 1);
        if (event != null && event.eventType() == EventType.SIGNAL_TIMEOUT && event.stepName() != null
                && park != null && park.eventType() == EventType.TIMER_SCHEDULED
                && event.stepName().equals(park.stepName())) {
            return List.of(failure - 1, failure);
        }
        return List.of(failure);
    }

    private static boolean isFailureMemo(WorkflowEvent event) {
        return event.eventType() == EventType.ACTIVITY_FAILED || event.eventType() == EventType.SIGNAL_TIMEOUT;
    }

    private static boolean onlyWorkflowFailed(SortedSet<Integer> sequences, Map<Integer, WorkflowEvent> bySeq) {
        for (var seq : sequences) {
            var event = bySeq.get(seq);
            if (event == null || event.eventType() != EventType.WORKFLOW_FAILED) {
                return false;
            }
        }
        return true;
    }

    private static @Nullable String text(JsonNode node, String field) {
        var value = node.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }
}
