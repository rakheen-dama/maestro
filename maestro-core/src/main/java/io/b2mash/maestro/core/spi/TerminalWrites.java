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
     * @param terminal      the terminal instance about to be written
     * @param terminalEvent the terminal event about to be appended
     * @throws IllegalArgumentException if {@code terminal.status()} is not
     *         terminal, if {@code terminalEvent.eventType()} is neither
     *         {@link EventType#WORKFLOW_COMPLETED} nor {@link EventType#WORKFLOW_FAILED},
     *         or if {@code terminalEvent.workflowInstanceId()} differs from
     *         {@code terminal.id()}
     */
    public static void requireValid(WorkflowInstance terminal, WorkflowEvent terminalEvent) {
        if (!terminal.status().isTerminal()) {
            throw new IllegalArgumentException(
                    "completeInstance requires a terminal status, got %s (workflowId=%s)"
                            .formatted(terminal.status(), terminal.workflowId()));
        }
        if (terminalEvent.eventType() != EventType.WORKFLOW_COMPLETED
                && terminalEvent.eventType() != EventType.WORKFLOW_FAILED) {
            throw new IllegalArgumentException(
                    "completeInstance requires a WORKFLOW_COMPLETED or WORKFLOW_FAILED event, got %s (workflowId=%s)"
                            .formatted(terminalEvent.eventType(), terminal.workflowId()));
        }
        if (!terminalEvent.workflowInstanceId().equals(terminal.id())) {
            throw new IllegalArgumentException(
                    "completeInstance event belongs to instance %s, not %s (workflowId=%s)"
                            .formatted(terminalEvent.workflowInstanceId(), terminal.id(), terminal.workflowId()));
        }
    }
}
