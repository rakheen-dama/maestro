package io.b2mash.maestro.core.spi;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;

/**
 * Shared argument checks for {@link WorkflowStore#completeInstance}, so every
 * store implementation rejects exactly the same malformed terminal writes.
 *
 * <p><b>Thread safety:</b> stateless; safe from any thread.
 */
public final class TerminalWrites {

    private TerminalWrites() {
    }

    /**
     * Validates a {@link WorkflowStore#completeInstance} call before any write.
     *
     * <p>The status and the event must describe the same outcome:
     * {@code COMPLETED} pairs only with {@link EventType#WORKFLOW_COMPLETED} and
     * {@code FAILED} only with {@link EventType#WORKFLOW_FAILED}. A mismatched
     * pair would leave a row whose own log contradicts it. {@code TERMINATED}
     * is rejected outright: there is no {@code WORKFLOW_TERMINATED} event type
     * to pair it with, and a terminate is not written through
     * {@code completeInstance}.
     *
     * @param terminal      the terminal instance about to be written
     * @param terminalEvent the terminal event about to be appended
     * @throws IllegalArgumentException if {@code terminal.status()} is not
     *         terminal, if {@code terminalEvent.eventType()} is not the event
     *         type paired with {@code terminal.status()} (including any
     *         {@code TERMINATED} status), or if
     *         {@code terminalEvent.workflowInstanceId()} differs from
     *         {@code terminal.id()}
     */
    public static void requireValid(WorkflowInstance terminal, WorkflowEvent terminalEvent) {
        if (!terminal.status().isTerminal()) {
            throw new IllegalArgumentException(
                    "completeInstance requires a terminal status, got %s (workflowId=%s)"
                            .formatted(terminal.status(), terminal.workflowId()));
        }
        var expected = switch (terminal.status()) {
            case COMPLETED -> EventType.WORKFLOW_COMPLETED;
            case FAILED -> EventType.WORKFLOW_FAILED;
            default -> null; // TERMINATED: no terminal event type exists
        };
        if (expected == null) {
            throw new IllegalArgumentException(
                    "completeInstance does not write status %s — it has no terminal event type (workflowId=%s)"
                            .formatted(terminal.status(), terminal.workflowId()));
        }
        if (terminalEvent.eventType() != expected) {
            throw new IllegalArgumentException(
                    "completeInstance status %s requires a %s event, got %s (workflowId=%s)"
                            .formatted(terminal.status(), expected, terminalEvent.eventType(),
                                    terminal.workflowId()));
        }
        if (!terminalEvent.workflowInstanceId().equals(terminal.id())) {
            throw new IllegalArgumentException(
                    "completeInstance event belongs to instance %s, not %s (workflowId=%s)"
                            .formatted(terminalEvent.workflowInstanceId(), terminal.id(), terminal.workflowId()));
        }
    }
}
