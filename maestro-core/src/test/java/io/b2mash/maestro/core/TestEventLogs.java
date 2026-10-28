package io.b2mash.maestro.core;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowTimer;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/**
 * Helpers shared by the in-memory {@code WorkflowStore} fakes across this
 * module's test suites.
 *
 * <p>Those fakes are deliberately per-test (each suite fakes only what it
 * needs), but
 * {@link io.b2mash.maestro.core.spi.WorkflowStore#deleteFailureEvents(UUID)}
 * has a contract subtle enough that nine independent copies would be nine
 * chances to get it wrong — the whole point is that it deletes the two failure
 * types and <em>nothing else</em>, because success and compensation memos are
 * what stop a retry re-running work that already happened.
 *
 * <p><b>Thread safety:</b> stateless. The collections passed in must
 * themselves be safe for the calling pattern; the fakes use copy-on-write
 * lists.
 */
public final class TestEventLogs {

    /** Logical-ID prefix of an {@code awaitSignal} deadline row (see the SPI contract). */
    private static final String AWAIT_SIGNAL_TIMER_ID_PREFIX = "await-signal-";

    private TestEventLogs() {
    }

    /**
     * {@link #removeFailureEvents(Collection, Collection, UUID)} for a fake
     * that stores no timer rows.
     *
     * @param events     the fake's event collection, mutated in place
     * @param instanceId the workflow instance whose failure memos to drop
     * @return the number of events removed
     */
    public static int removeFailureEvents(Collection<WorkflowEvent> events, UUID instanceId) {
        return removeFailureEvents(events, null, instanceId);
    }

    /**
     * Removes an instance's {@code ACTIVITY_FAILED} and {@code WORKFLOW_FAILED}
     * events — plus, when the failure cause recorded in {@code WORKFLOW_FAILED}
     * was a {@code SignalTimeoutException}, the <em>failing</em>
     * {@code SIGNAL_TIMEOUT} memo (the highest-sequenced one — Issue 19) and,
     * if the slot just before it holds that await's {@code TIMER_SCHEDULED}
     * park memo (same step name), that memo and the await's deadline rows
     * {@code await-signal-<memoSeq>} (R61), so a retried await arms a fresh
     * full timeout. For any other failure cause every {@code SIGNAL_TIMEOUT}
     * memo is a caught gate that must survive, preserving pre-failure replay
     * determinism. All other events and timer rows stay intact.
     *
     * <p>The signal-timeout discriminator is anchored to the payload's
     * {@code exceptionType} field (matching the shipped stores) — never a
     * whole-payload substring match, which would also fire on a failure whose
     * {@code message} merely embeds the FQCN.
     *
     * @param events     the fake's event collection, mutated in place
     * @param timers     the fake's timer collection, mutated in place;
     *                   {@code null} for a fake that stores none
     * @param instanceId the workflow instance whose failure memos to drop
     * @return the number of events removed (timer rows are not counted)
     */
    public static int removeFailureEvents(Collection<WorkflowEvent> events,
                                          @Nullable Collection<WorkflowTimer> timers,
                                          UUID instanceId) {
        var before = events.size();
        var failedByTimeout = events.stream()
                .filter(e -> e.workflowInstanceId().equals(instanceId)
                        && e.eventType() == EventType.WORKFLOW_FAILED
                        && e.payload() != null)
                .anyMatch(e -> {
                    var exceptionType = e.payload().path("exceptionType");
                    return exceptionType.isString()
                            && "io.b2mash.maestro.core.exception.SignalTimeoutException"
                                    .equals(exceptionType.stringValue());
                });
        var failingTimeout = !failedByTimeout ? null : events.stream()
                .filter(e -> e.workflowInstanceId().equals(instanceId)
                        && e.eventType() == EventType.SIGNAL_TIMEOUT)
                .max((a, b) -> Integer.compare(a.sequenceNumber(), b.sequenceNumber()))
                .orElse(null);
        var parkMemo = failingTimeout == null || failingTimeout.stepName() == null ? null : events.stream()
                .filter(e -> e.workflowInstanceId().equals(instanceId)
                        && e.sequenceNumber() == failingTimeout.sequenceNumber() - 1
                        && e.eventType() == EventType.TIMER_SCHEDULED
                        && Objects.equals(e.stepName(), failingTimeout.stepName()))
                .findFirst()
                .orElse(null);
        events.removeIf(event -> event.workflowInstanceId().equals(instanceId)
                && (event.eventType() == EventType.ACTIVITY_FAILED
                || event.eventType() == EventType.WORKFLOW_FAILED
                || event == failingTimeout
                || event == parkMemo));
        if (parkMemo != null && timers != null) {
            var timerId = AWAIT_SIGNAL_TIMER_ID_PREFIX + parkMemo.sequenceNumber();
            timers.removeIf(t -> t.workflowInstanceId().equals(instanceId) && t.timerId().equals(timerId));
        }
        return before - events.size();
    }
}
