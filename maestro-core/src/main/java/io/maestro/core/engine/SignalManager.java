package io.maestro.core.engine;

import io.maestro.core.context.WorkflowContext;
import io.maestro.core.exception.SignalTimeoutException;
import io.maestro.core.model.EventType;
import io.maestro.core.model.WorkflowEvent;
import io.maestro.core.model.WorkflowSignal;
import io.maestro.core.model.WorkflowStatus;
import io.maestro.core.spi.LifecycleEventType;
import io.maestro.core.spi.WorkflowLifecycleEvent;
import io.maestro.core.spi.WorkflowMessaging;
import io.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * Owns the complete lifecycle of workflow signals: delivery, persistence,
 * await/consume, and orphan adoption.
 *
 * <p>Signal handling involves three phases:
 * <ol>
 *   <li><b>Delivery:</b> External callers send signals via
 *       {@link #deliverSignal}. The signal is persisted immediately and
 *       the target workflow is unparked if it is currently waiting.</li>
 *   <li><b>Await:</b> A running workflow calls {@link #awaitSignal} to
 *       block until a matching signal arrives (or replay the stored result).</li>
 *   <li><b>Adoption:</b> Signals sent before the workflow instance exists
 *       are adopted via {@link #adoptOrphanedSignals} when the instance
 *       is created.</li>
 * </ol>
 *
 * <h2>Self-Recovery</h2>
 * <p>Signals are <b>always</b> persisted to Postgres before in-memory delivery.
 * This guarantees three self-recovery scenarios:
 * <ol>
 *   <li>Signal arrives before {@code awaitSignal()} — stored unconsumed,
 *       consumed when the workflow reaches the await point.</li>
 *   <li>Signal arrives before workflow starts — stored with {@code null}
 *       instance ID, adopted when the instance is created.</li>
 *   <li>Signal arrives while service is down — persisted by a Kafka
 *       consumer on another instance, found during recovery replay.</li>
 * </ol>
 *
 * <h2>Thread Safety</h2>
 * <p>All public methods are thread-safe. {@link #deliverSignal} may be
 * called concurrently from any thread. {@link #awaitSignal} is called
 * from the workflow's virtual thread — the {@link WorkflowContext} is
 * passed explicitly rather than read from the thread-local, keeping
 * this class decoupled from thread identity.
 *
 * @see WorkflowExecutor
 * @see DefaultWorkflowOperations
 */
final class SignalManager {

    private static final Logger logger = LoggerFactory.getLogger(SignalManager.class);

    private final WorkflowStore store;
    private final @Nullable WorkflowMessaging messaging;
    private final PayloadSerializer serializer;
    private final ParkingLot parkingLot;

    /**
     * Creates a new signal manager.
     *
     * @param store      workflow store for signal persistence and event memoization
     * @param messaging  optional messaging for lifecycle event publishing
     * @param serializer Jackson serializer for signal payloads
     * @param parkingLot virtual thread parking mechanism for signal await
     */
    SignalManager(
            WorkflowStore store,
            @Nullable WorkflowMessaging messaging,
            PayloadSerializer serializer,
            ParkingLot parkingLot
    ) {
        this.store = store;
        this.messaging = messaging;
        this.serializer = serializer;
        this.parkingLot = parkingLot;
    }

    // ── Delivery ────────────────────────────────────────────────────────

    /**
     * Delivers a signal to a workflow.
     *
     * <p>Persists the signal to the store and unparks the workflow if it
     * is currently waiting for this signal. If the workflow hasn't reached
     * the await point yet, the signal is stored and consumed when the
     * workflow calls {@link #awaitSignal}.
     *
     * <p>If the workflow instance does not yet exist, the signal is persisted
     * with a {@code null} instance ID (pre-delivery pattern) and adopted
     * when the instance is created via {@link #adoptOrphanedSignals}.
     *
     * @param workflowId the target workflow's business ID
     * @param signalName the signal name
     * @param payload    the signal payload, or {@code null}
     */
    void deliverSignal(String workflowId, String signalName, @Nullable Object payload) {
        // Determine workflow instance ID (may be null for pre-delivery)
        UUID workflowInstanceId = null;
        var instance = store.getInstance(workflowId);
        if (instance.isPresent()) {
            workflowInstanceId = instance.get().id();
        }

        // Persist the signal — always before in-memory delivery
        var signalPayload = payload != null ? serializer.serialize(payload) : null;
        var signal = new WorkflowSignal(
                UUID.randomUUID(),
                workflowInstanceId,
                workflowId,
                signalName,
                signalPayload,
                false,
                Instant.now()
        );
        store.saveSignal(signal);

        // Unpark if waiting
        var parkKey = workflowId + ":signal:" + signalName;
        parkingLot.unpark(parkKey, signalPayload);

        logger.debug("Delivered signal '{}' to workflow '{}'", signalName, workflowId);
    }

    // ── Await ───────────────────────────────────────────────────────────

    /**
     * Waits for a named signal to be delivered to the current workflow.
     *
     * <p>Follows the hybrid memoization pattern:
     * <ol>
     *   <li><b>Replay:</b> If a {@code SIGNAL_RECEIVED} event exists at the
     *       current sequence number, returns the stored payload immediately.</li>
     *   <li><b>Live — signal already arrived:</b> Checks for unconsumed signals
     *       in the store (self-recovery). If found, consumes immediately.</li>
     *   <li><b>Live — no signal yet:</b> Updates instance status to
     *       {@code WAITING_SIGNAL}, parks the virtual thread with the given
     *       timeout, and consumes the signal on wake-up.</li>
     * </ol>
     *
     * @param ctx        the current workflow context (passed explicitly, not read from thread-local)
     * @param signalName the signal name to wait for
     * @param type       the expected payload type
     * @param timeout    maximum time to wait
     * @param <T>        the payload type
     * @return the deserialized signal payload
     * @throws SignalTimeoutException if the timeout elapses before a signal arrives
     */
    <T> T awaitSignal(WorkflowContext ctx, String signalName, Class<T> type, Duration timeout) {
        var seq = ctx.nextSequence();
        var stepName = "$maestro:awaitSignal:" + signalName;

        // Replay check: look for SIGNAL_RECEIVED at this sequence
        var storedEvent = store.getEventBySequence(ctx.workflowInstanceId(), seq);
        if (storedEvent.isPresent() && storedEvent.get().eventType() == EventType.SIGNAL_RECEIVED) {
            logger.debug("Replaying signal '{}' at seq {}", signalName, seq);
            return serializer.deserialize(storedEvent.get().payload(), type);
        }

        // Live path
        ctx.setReplaying(false);

        // Check for already-arrived signals (self-recovery)
        var unconsumed = store.getUnconsumedSignals(ctx.workflowId(), signalName);
        if (!unconsumed.isEmpty()) {
            var signal = unconsumed.getFirst();
            return consumeSignal(ctx, seq, stepName, signalName, signal, type);
        }

        // No signal yet — park and wait
        updateInstanceStatus(ctx, WorkflowStatus.WAITING_SIGNAL);

        var parkKey = ctx.workflowId() + ":signal:" + signalName;
        logger.debug("Workflow '{}' waiting for signal '{}' (timeout={})", ctx.workflowId(), signalName, timeout);

        try {
            parkingLot.parkWithTimeout(parkKey, timeout);
        } catch (TimeoutException e) {
            // Re-check store: signal may have arrived between our check and parking
            var lateSignals = store.getUnconsumedSignals(ctx.workflowId(), signalName);
            if (!lateSignals.isEmpty()) {
                var result = consumeSignal(ctx, seq, stepName, signalName, lateSignals.getFirst(), type);
                updateInstanceStatus(ctx, WorkflowStatus.RUNNING);
                return result;
            }
            updateInstanceStatus(ctx, WorkflowStatus.RUNNING);
            throw new SignalTimeoutException(ctx.workflowId(), signalName, timeout);
        }

        // Woke up — signal should now be in the store
        var signals = store.getUnconsumedSignals(ctx.workflowId(), signalName);
        if (signals.isEmpty()) {
            // Shouldn't happen normally — defensive fallback
            updateInstanceStatus(ctx, WorkflowStatus.RUNNING);
            throw new SignalTimeoutException(ctx.workflowId(), signalName, timeout);
        }

        var result = consumeSignal(ctx, seq, stepName, signalName, signals.getFirst(), type);
        updateInstanceStatus(ctx, WorkflowStatus.RUNNING);
        return result;
    }

    // ── Orphan adoption ─────────────────────────────────────────────────

    /**
     * Adopts orphaned signals by associating them with a workflow instance.
     *
     * <p>Orphaned signals are those delivered before the workflow instance
     * existed (pre-delivery pattern). They are persisted with a {@code null}
     * {@code workflowInstanceId} and need to be linked when the instance
     * is created.
     *
     * @param workflowId the business workflow ID to match signals against
     * @param instanceId the workflow instance UUID to assign
     */
    void adoptOrphanedSignals(String workflowId, UUID instanceId) {
        store.adoptOrphanedSignals(workflowId, instanceId);
    }

    // ── Internal helpers ────────────────────────────────────────────────

    private <T> T consumeSignal(
            WorkflowContext ctx, int seq, String stepName,
            String signalName, WorkflowSignal signal, Class<T> type
    ) {
        store.markSignalConsumed(signal.id());
        appendEvent(ctx, seq, EventType.SIGNAL_RECEIVED, stepName, signal.payload());
        publishLifecycleEvent(ctx, stepName, LifecycleEventType.SIGNAL_RECEIVED);
        logger.debug("Consumed signal '{}' for workflow '{}'", signalName, ctx.workflowId());
        return serializer.deserialize(signal.payload(), type);
    }

    private void appendEvent(WorkflowContext ctx, int seq, EventType type,
                             String stepName, @Nullable JsonNode payload) {
        var event = new WorkflowEvent(
                UUID.randomUUID(),
                ctx.workflowInstanceId(),
                seq,
                type,
                stepName,
                payload,
                Instant.now()
        );
        store.appendEvent(event);
    }

    private void updateInstanceStatus(WorkflowContext ctx, WorkflowStatus newStatus) {
        var instance = store.getInstance(ctx.workflowId());
        if (instance.isEmpty()) {
            logger.warn("Cannot update status to {} — workflow '{}' not found", newStatus, ctx.workflowId());
            return;
        }
        var updated = instance.get().toBuilder()
                .status(newStatus)
                .updatedAt(Instant.now())
                .version(instance.get().version() + 1)
                .build();
        store.updateInstance(updated);
    }

    private void publishLifecycleEvent(WorkflowContext ctx, String stepName, LifecycleEventType eventType) {
        if (messaging == null) return;
        try {
            messaging.publishLifecycleEvent(new WorkflowLifecycleEvent(
                    ctx.workflowInstanceId(),
                    ctx.workflowId(),
                    ctx.workflowType(),
                    ctx.serviceName(),
                    ctx.taskQueue(),
                    eventType,
                    stepName,
                    null,
                    Instant.now()
            ));
        } catch (Exception e) {
            logger.warn("Failed to publish {} lifecycle event for workflow '{}'", eventType, ctx.workflowId(), e);
        }
    }
}
