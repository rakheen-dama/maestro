package io.b2mash.maestro.spring.config;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.engine.WorkflowRegistration;
import io.b2mash.maestro.core.exception.AdminCommandException;
import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;

/**
 * Routes {@code $maestro:*} admin-command signals — published by the admin
 * dashboard's Retry and Terminate buttons — to the corresponding
 * {@link WorkflowExecutor} action.
 *
 * <p>Wired into {@link SignalSubscriptionRunner}, which diverts any signal
 * name starting with the {@code $maestro:} prefix to
 * {@link #dispatch(SignalMessage)} <em>before</em>
 * {@link WorkflowExecutor#deliverSignal}. A command therefore never becomes a
 * {@code WorkflowSignal} row: {@code awaitSignal()} only ever sees rows and
 * memoized {@code SIGNAL_RECEIVED} events created by
 * {@code deliverSignal}, so admin commands are structurally invisible to it.
 *
 * <h2>Dispatch table</h2>
 * <ul>
 *   <li>{@code $maestro:retry} — looks up the instance's workflow type and its
 *       {@link io.b2mash.maestro.spring.config.WorkflowRegistrar} registration,
 *       then calls {@link WorkflowExecutor#retryWorkflow}.</li>
 *   <li>{@code $maestro:terminate} — calls
 *       {@link WorkflowExecutor#terminateWorkflow} with the {@code reason}
 *       field of the payload, if present, or a default reason otherwise.</li>
 *   <li>Any other {@code $maestro:*} name — throws {@link AdminCommandException}.</li>
 * </ul>
 *
 * <h2>Transport-outcome contract (Issue 15 design §3.3, §7)</h2>
 * <p>Every deterministic, non-actionable outcome — a workflow that is not in
 * the state the command requires, or an unknown workflow ID — is logged at
 * WARN and this method returns normally, so the caller (the transport's
 * signal handler) acknowledges the message: retrying a no-op can never help,
 * and letting it occupy the redelivery budget would stall the whole signal
 * topic. Everything else — a {@link RuntimeException} from the executor (a
 * transient store failure, an optimistic-lock conflict that exhausted its
 * attempt budget) or an {@link AdminCommandException} raised here (an unknown
 * command, or a workflow type with no registration) — propagates, so the
 * caller does <b>not</b> acknowledge and the transport redelivers under its
 * existing bounded-backoff-then-dead-letter policy.
 *
 * <h2>Thread safety</h2>
 * <p>Stateless and thread-safe. {@code dispatch} may be invoked concurrently
 * from any transport listener thread; all mutable state lives in the
 * thread-safe {@link WorkflowExecutor}, {@link WorkflowStore} and
 * {@link WorkflowRegistrar} it delegates to.
 */
public class AdminCommandDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(AdminCommandDispatcher.class);

    /** Prefix every admin-command signal name carries; see {@link SignalSubscriptionRunner}. */
    static final String ADMIN_COMMAND_PREFIX = "$maestro:";

    private static final String RETRY_COMMAND = "$maestro:retry";
    private static final String TERMINATE_COMMAND = "$maestro:terminate";

    /** Used when a terminate command's payload carries no {@code reason} field. */
    static final String DEFAULT_TERMINATE_REASON = "terminated via admin command";

    private static final String MDC_WORKFLOW_ID = "workflowId";

    private final WorkflowExecutor executor;
    private final WorkflowStore store;
    private final WorkflowRegistrar registrar;

    /**
     * @param executor  the executor to route retry/terminate actions to
     * @param store     used to look up a retry target's workflow type before
     *                  resolving its {@link WorkflowRegistration}
     * @param registrar resolves a workflow type to its registration
     */
    public AdminCommandDispatcher(WorkflowExecutor executor, WorkflowStore store, WorkflowRegistrar registrar) {
        this.executor = executor;
        this.store = store;
        this.registrar = registrar;
    }

    /**
     * Dispatches a single {@code $maestro:*} command.
     *
     * @param message the inbound signal; {@link SignalMessage#signalName()}
     *                must start with {@code $maestro:}
     * @throws AdminCommandException if the command name is not recognized, or
     *                                {@code $maestro:retry} names a workflow
     *                                type with no registration
     * @throws RuntimeException      if the underlying executor call fails
     *                                (e.g. a transient store failure) — see
     *                                the class Javadoc's transport-outcome
     *                                contract
     */
    public void dispatch(SignalMessage message) {
        var workflowId = message.workflowId();
        var command = message.signalName();
        MDC.put(MDC_WORKFLOW_ID, workflowId);
        try {
            switch (command) {
                case RETRY_COMMAND -> dispatchRetry(workflowId);
                case TERMINATE_COMMAND -> dispatchTerminate(workflowId, message.payload());
                default -> throw new AdminCommandException(
                        "Unknown admin command '%s' for workflow '%s'".formatted(command, workflowId));
            }
        } catch (RuntimeException e) {
            logger.error("Admin command '{}' failed for workflow '{}' — rethrowing for transport redelivery",
                    command, workflowId, e);
            throw e;
        } finally {
            MDC.remove(MDC_WORKFLOW_ID);
        }
    }

    // ── $maestro:retry ──────────────────────────────────────────────────

    private void dispatchRetry(String workflowId) {
        var instance = store.getInstance(workflowId);
        if (instance.isEmpty()) {
            logger.warn("Admin retry command for unknown workflow '{}' — ignoring", workflowId);
            return;
        }
        var workflowType = instance.get().workflowType();
        WorkflowRegistration registration;
        try {
            registration = registrar.getRegistration(workflowType);
        } catch (IllegalArgumentException e) {
            throw new AdminCommandException(
                    "No @DurableWorkflow registration for type '%s' — cannot retry workflow '%s'"
                            .formatted(workflowType, workflowId), e);
        }

        var outcome = executor.retryWorkflow(workflowId, registration);
        if (outcome == WorkflowExecutor.RetryOutcome.RETRIED) {
            logger.info("Admin retry command for workflow '{}': {}", workflowId, outcome);
        } else {
            logger.warn("Admin retry command for workflow '{}': {} (no-op)", workflowId, outcome);
        }
    }

    // ── $maestro:terminate ──────────────────────────────────────────────

    private void dispatchTerminate(String workflowId, @Nullable JsonNode payload) {
        var reason = extractReason(payload);
        var outcome = executor.terminateWorkflow(workflowId, reason);
        if (outcome == WorkflowExecutor.TerminateOutcome.TERMINATED) {
            logger.info("Admin terminate command for workflow '{}': {}", workflowId, outcome);
        } else {
            logger.warn("Admin terminate command for workflow '{}': {} (no-op)", workflowId, outcome);
        }
    }

    private static String extractReason(@Nullable JsonNode payload) {
        if (payload == null) {
            return DEFAULT_TERMINATE_REASON;
        }
        var reasonNode = payload.get("reason");
        if (reasonNode == null || reasonNode.isNull()) {
            return DEFAULT_TERMINATE_REASON;
        }
        return reasonNode.asString();
    }
}
