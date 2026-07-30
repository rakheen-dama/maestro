package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.SignalTimeoutException;
import io.b2mash.maestro.core.exception.WorkflowTerminatedException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.SignalNotifier;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * How often a parked await re-checks the store for a signal persisted
     * without a notification reaching this instance — e.g. ingested on
     * another node in a deployment with no {@link SignalNotifier} (Kafka or
     * RabbitMQ messaging without Valkey), or delivered in the narrow window
     * before the park registered. Bounds cross-node signal latency in those
     * cases; with a working notifier the wake is instant and this never fires.
     */
    static final Duration DEFAULT_WAKE_RECHECK_INTERVAL = Duration.ofSeconds(30);

    private final WorkflowStore store;
    private final @Nullable WorkflowMessaging messaging;
    private final @Nullable SignalNotifier signalNotifier;
    private final PayloadSerializer serializer;
    private final ParkingLot parkingLot;
    private final Duration wakeRecheckInterval;

    /**
     * Ref-counted cross-instance wake subscriptions, keyed by workflow ID.
     *
     * <p>The {@link SignalNotifier} SPI supports one callback per workflow ID,
     * but parallel branches of one workflow may await different signals
     * concurrently — the count ensures the shared subscription is only torn
     * down when the last await finishes.
     *
     * <p>Bookkeeping happens under the map's bin lock, but the notifier
     * subscribe/unsubscribe I/O deliberately happens <em>outside</em> it — a
     * slow notifier must not stall unrelated awaits. The cost is a narrow
     * subscribe-vs-unsubscribe interleaving in which a counted await can end
     * up without an active physical subscription; the periodic store re-check
     * bounds the resulting wake delay (the notifier is an optimisation, not a
     * correctness requirement).
     */
    private final ConcurrentHashMap<String, Integer> wakeSubscriptions = new ConcurrentHashMap<>();

    /**
     * Creates a new signal manager with the default wake re-check interval.
     *
     * @param store          workflow store for signal persistence and event memoization
     * @param messaging      optional messaging for lifecycle event publishing
     * @param signalNotifier optional cross-instance signal notification (e.g., Valkey pub/sub)
     * @param serializer     Jackson serializer for signal payloads
     * @param parkingLot     virtual thread parking mechanism for signal await
     */
    SignalManager(
            WorkflowStore store,
            @Nullable WorkflowMessaging messaging,
            @Nullable SignalNotifier signalNotifier,
            PayloadSerializer serializer,
            ParkingLot parkingLot
    ) {
        this(store, messaging, signalNotifier, serializer, parkingLot, DEFAULT_WAKE_RECHECK_INTERVAL);
    }

    /**
     * Creates a new signal manager with a custom wake re-check interval
     * (package-private, for tests).
     */
    SignalManager(
            WorkflowStore store,
            @Nullable WorkflowMessaging messaging,
            @Nullable SignalNotifier signalNotifier,
            PayloadSerializer serializer,
            ParkingLot parkingLot,
            Duration wakeRecheckInterval
    ) {
        this.store = store;
        this.messaging = messaging;
        this.signalNotifier = signalNotifier;
        this.serializer = serializer;
        this.parkingLot = parkingLot;
        this.wakeRecheckInterval = wakeRecheckInterval;
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

        // Unpark if waiting locally
        var parkKey = workflowId + ":signal:" + signalName;
        parkingLot.unpark(parkKey, signalPayload);

        // Notify other instances (best-effort)
        if (signalNotifier != null) {
            try {
                signalNotifier.publish(workflowId, signalName);
            } catch (Exception e) {
                logger.warn("Failed to publish signal notification for workflow '{}': {}",
                        workflowId, e.getMessage());
            }
        }

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

        // No signal yet — subscribe for cross-instance wake, then park.
        // Subscription is a performance optimisation: if it fails (or a
        // notification slips through before the park registers), the signal
        // is still found via the periodic store re-check below.
        subscribeForWake(ctx.workflowId());
        try {
            if (signalNotifier != null) {
                // Close the check→subscribe race: a signal persisted on another
                // node before our subscription became active would never notify
                // us. Without a notifier there is no such race to close.
                var raced = store.getUnconsumedSignals(ctx.workflowId(), signalName);
                if (!raced.isEmpty()) {
                    return consumeSignal(ctx, seq, stepName, signalName, raced.getFirst(), type);
                }
            }

            updateInstanceStatus(ctx, WorkflowStatus.WAITING_SIGNAL);

            var parkKey = ctx.workflowId() + ":signal:" + signalName;
            logger.debug("Workflow '{}' waiting for signal '{}' (timeout={})", ctx.workflowId(), signalName, timeout);

            // Park in re-check-interval chunks. Every wake OR chunk expiry
            // re-reads the store, so a signal persisted without a notification
            // reaching this instance (no notifier configured, notification
            // gap, missed unpark) is delivered within one interval instead of
            // stalling until the full await timeout.
            var deadline = Instant.now().plus(timeout);
            while (true) {
                var remaining = Duration.between(Instant.now(), deadline);
                if (!remaining.isPositive()) {
                    updateInstanceStatus(ctx, WorkflowStatus.RUNNING);
                    throw new SignalTimeoutException(ctx.workflowId(), signalName, timeout);
                }
                var chunk = remaining.compareTo(wakeRecheckInterval) < 0 ? remaining : wakeRecheckInterval;

                boolean woken;
                try {
                    parkingLot.parkWithTimeout(parkKey, chunk);
                    woken = true;
                } catch (TimeoutException e) {
                    woken = false;
                }

                // Terminate outranks a signal: if the instance is TERMINATED,
                // consuming a signal now would memoize a SIGNAL_RECEIVED event
                // for a run that must not continue.
                standDownIfTerminated(ctx.workflowId());

                var signals = store.getUnconsumedSignals(ctx.workflowId(), signalName);
                if (!signals.isEmpty()) {
                    var result = consumeSignal(ctx, seq, stepName, signalName, signals.getFirst(), type);
                    updateInstanceStatus(ctx, WorkflowStatus.RUNNING);
                    return result;
                }
                if (woken) {
                    // Spurious wake — e.g. a pub/sub self-echo of a previous
                    // delivery arriving after this key was consumed and
                    // re-parked (loops like collectSignals await the same
                    // name repeatedly). Re-park; the deadline still bounds us.
                    logger.debug("Spurious wake for workflow '{}' signal '{}' — re-parking",
                            ctx.workflowId(), signalName);
                }
            }
        } finally {
            unsubscribeForWake(ctx.workflowId());
        }
    }

    // ── Cross-instance wake subscription ────────────────────────────────

    /**
     * Registers (or ref-counts) the cross-instance wake subscription for a
     * workflow. The notifier I/O happens outside the map lock; on subscribe
     * failure the count is rolled back and the await falls back to the
     * periodic store re-check.
     */
    private void subscribeForWake(String workflowId) {
        if (signalNotifier == null) {
            return;
        }
        var count = wakeSubscriptions.merge(workflowId, 1, Integer::sum);
        if (count > 1) {
            return; // subscription already active (or being set up concurrently)
        }
        try {
            signalNotifier.subscribe(workflowId, this::onRemoteSignal);
        } catch (Exception e) {
            logger.warn("Failed to subscribe for cross-instance wake of workflow '{}' — "
                            + "periodic store re-check remains the fallback: {}",
                    workflowId, e.getMessage());
            removeWakeCount(workflowId);
        }
    }

    /**
     * Decrements the wake-subscription ref-count, unsubscribing (outside the
     * map lock) when the last concurrent await for this workflow finishes.
     */
    private void unsubscribeForWake(String workflowId) {
        if (signalNotifier == null) {
            return;
        }
        if (removeWakeCount(workflowId)) {
            try {
                signalNotifier.unsubscribe(workflowId);
            } catch (Exception e) {
                logger.warn("Failed to unsubscribe cross-instance wake of workflow '{}': {}",
                        workflowId, e.getMessage());
            }
        }
    }

    /**
     * Decrements the wake ref-count for a workflow.
     *
     * @return {@code true} when the count reached zero and the physical
     *         subscription should be torn down
     */
    private boolean removeWakeCount(String workflowId) {
        var reachedZero = new boolean[1];
        wakeSubscriptions.compute(workflowId, (id, count) -> {
            if (count == null) {
                return null;
            }
            if (count <= 1) {
                reachedZero[0] = true;
                return null;
            }
            return count - 1;
        });
        return reachedZero[0];
    }

    /**
     * Cross-instance notification callback: unparks the local waiter, which
     * then re-reads the signal from the store. May run on a network event
     * loop thread — must stay lightweight and non-blocking. Unparking an
     * absent key (self-echo of a local delivery, or a waiter that already
     * woke) is a no-op.
     */
    private void onRemoteSignal(String workflowId, String signalName) {
        parkingLot.unpark(workflowId + ":signal:" + signalName, null);
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
        // Persist the SIGNAL_RECEIVED event BEFORE marking consumed.
        // If appendEvent fails, the signal stays unconsumed and can be
        // retried on recovery. The reverse order would lose the signal.
        // If the CAS below then loses, the appended event is already the
        // durable memoized truth for this sequence — consuming a different
        // row now would contradict the event log on replay, so we proceed.
        appendEvent(ctx, seq, EventType.SIGNAL_RECEIVED, stepName, signal.payload());
        if (!store.markSignalConsumed(signal.id())) {
            logger.warn("Signal '{}' (id={}) for workflow '{}' was already consumed by a "
                            + "concurrent consumer — proceeding with the memoized SIGNAL_RECEIVED "
                            + "event at seq {}",
                    signalName, signal.id(), ctx.workflowId(), seq);
        }
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
        InstanceStatusWriter.write(store, ctx.workflowId(), newStatus);
    }

    /**
     * Stands the current run down if the workflow has been terminated while it
     * was parked.
     *
     * <p>Called once per wake-recheck chunk of {@link #awaitSignal}. A terminate
     * usually arrives on a node that is <em>not</em> the owner, so nothing local
     * unparks this thread; the periodic re-read the await already performs for
     * missed signal notifications is the cheapest place to also notice a
     * terminal row, and it bounds cross-node terminate convergence for a parked
     * await to one interval.
     *
     * @param workflowId the workflow's business ID
     * @throws WorkflowTerminatedException if the instance row is {@code TERMINATED}
     */
    private void standDownIfTerminated(String workflowId) {
        var terminated = store.getInstance(workflowId)
                .map(instance -> instance.status() == WorkflowStatus.TERMINATED)
                .orElse(false);
        if (terminated) {
            logger.info("Workflow '{}' was terminated while parked on a signal — abandoning this run",
                    workflowId);
            throw new WorkflowTerminatedException(workflowId, null);
        }
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
