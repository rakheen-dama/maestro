package io.maestro.core.engine;

import io.maestro.core.context.WorkflowContext;
import io.maestro.core.exception.ActivityExecutionException;
import io.maestro.core.exception.DuplicateEventException;
import io.maestro.core.exception.SerializationException;
import io.maestro.core.model.EventType;
import io.maestro.core.model.WorkflowEvent;
import io.maestro.core.retry.RetryExecutor;
import io.maestro.core.retry.RetryPolicy;
import io.maestro.core.spi.DistributedLock;
import io.maestro.core.spi.LifecycleEventType;
import io.maestro.core.spi.LockHandle;
import io.maestro.core.spi.WorkflowLifecycleEvent;
import io.maestro.core.spi.WorkflowMessaging;
import io.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * JDK dynamic proxy {@link InvocationHandler} that implements hybrid memoization
 * for activity method calls.
 *
 * <p>This is the core execution mechanism of Maestro. Every activity method
 * call is intercepted by this handler, which:
 * <ol>
 *   <li>Reads the current sequence number from {@link WorkflowContext}.</li>
 *   <li>Checks the {@link WorkflowStore} for a stored result at that sequence.</li>
 *   <li><b>Replay path:</b> If found, deserializes and returns the stored result.</li>
 *   <li><b>Live path:</b> If not found, executes the activity with retries,
 *       persists the result, and returns it.</li>
 * </ol>
 *
 * <h2>Idempotency</h2>
 * <p>The Postgres unique constraint on {@code (workflow_instance_id, sequence_number)}
 * is the primary idempotency guard. If a {@link DuplicateEventException} is caught
 * during event append, the handler re-reads the stored event and returns/throws
 * its result — handling crash-after-persist scenarios gracefully.
 *
 * <h2>Lock Strategy</h2>
 * <p>The distributed lock is an optimization to prevent duplicate execution, not
 * a correctness requirement. If the lock backend is unavailable, the handler
 * proceeds without the lock — the Postgres unique constraint prevents corruption.
 *
 * <h2>Thread Safety</h2>
 * <p>Each handler instance is associated with one activity interface but may be
 * shared across workflow instances (the per-instance state comes from
 * {@link WorkflowContext} on the current thread). All mutable state is
 * thread-local via the context.
 *
 * @see ActivityProxyFactory
 * @see WorkflowContext
 */
public final class ActivityInvocationHandler implements InvocationHandler {

    private static final Logger logger = LoggerFactory.getLogger(ActivityInvocationHandler.class);

    private final Object activityImpl;
    private final String activityName;
    private final WorkflowStore store;
    private final @Nullable DistributedLock distributedLock;
    private final @Nullable WorkflowMessaging messaging;
    private final RetryPolicy retryPolicy;
    private final Duration startToCloseTimeout;
    private final PayloadSerializer serializer;
    private final RetryExecutor retryExecutor;

    /**
     * @param activityImpl        the real activity implementation to delegate to
     * @param activityName        the activity group name (for step name prefix)
     * @param store               the workflow store for memoization lookups and event persistence
     * @param distributedLock     optional distributed lock for dedup optimization
     * @param messaging           optional messaging for lifecycle event publishing
     * @param retryPolicy         retry policy for failed activity invocations
     * @param startToCloseTimeout timeout used as lock TTL hint
     * @param serializer          Jackson serializer for payloads
     * @param retryExecutor       retry executor for live activity invocations
     */
    public ActivityInvocationHandler(
            Object activityImpl,
            String activityName,
            WorkflowStore store,
            @Nullable DistributedLock distributedLock,
            @Nullable WorkflowMessaging messaging,
            RetryPolicy retryPolicy,
            Duration startToCloseTimeout,
            PayloadSerializer serializer,
            RetryExecutor retryExecutor
    ) {
        this.activityImpl = activityImpl;
        this.activityName = activityName;
        this.store = store;
        this.distributedLock = distributedLock;
        this.messaging = messaging;
        this.retryPolicy = retryPolicy;
        this.startToCloseTimeout = startToCloseTimeout;
        this.serializer = serializer;
        this.retryExecutor = retryExecutor;
    }

    @Override
    public @Nullable Object invoke(Object proxy, Method method, @Nullable Object @Nullable [] args)
            throws Throwable {

        // Pass-through for Object methods
        if (method.getDeclaringClass() == Object.class) {
            return handleObjectMethod(proxy, method, args);
        }

        var ctx = WorkflowContext.current();
        var seq = ctx.nextSequence();
        var stepName = activityName + "." + method.getName();

        MDC.put("workflowId", ctx.workflowId());
        MDC.put("activityName", stepName);
        MDC.put("sequence", String.valueOf(seq));

        try {
            // ── Memoization lookup ────────────────────────────────────
            var storedEvent = store.getEventBySequence(ctx.workflowInstanceId(), seq);

            if (storedEvent.isPresent()) {
                return handleReplay(storedEvent.get(), method, stepName, ctx, seq);
            }

            // ── Live execution ────────────────────────────────────────
            ctx.setReplaying(false);
            return executeLive(method, args, ctx, seq, stepName);

        } finally {
            MDC.remove("workflowId");
            MDC.remove("activityName");
            MDC.remove("sequence");
        }
    }

    // ── Replay path ───────────────────────────────────────────────────

    /**
     * Handles a memoized (replayed) activity result.
     */
    private @Nullable Object handleReplay(
            WorkflowEvent event, Method method, String stepName,
            WorkflowContext ctx, int seq
    ) throws ActivityExecutionException {
        return switch (event.eventType()) {
            case ACTIVITY_COMPLETED -> {
                logger.debug("Replaying completed activity '{}' at seq {}", stepName, seq);
                yield deserializeResult(event.payload(), method);
            }
            case ACTIVITY_FAILED -> {
                logger.debug("Replaying failed activity '{}' at seq {}", stepName, seq);
                throw reconstructFailure(event, stepName, ctx.workflowId());
            }
            default -> throw new IllegalStateException(
                    "Unexpected event type %s at seq %d for activity '%s' in workflow '%s'"
                            .formatted(event.eventType(), seq, stepName, ctx.workflowId()));
        };
    }

    /**
     * Deserializes a stored activity result to the method's return type.
     */
    private @Nullable Object deserializeResult(@Nullable JsonNode payload, Method method) {
        if (method.getReturnType() == void.class || method.getReturnType() == Void.class) {
            return null;
        }
        return serializer.deserialize(payload, method.getGenericReturnType());
    }

    /**
     * Reconstructs an {@link ActivityExecutionException} from a stored failure event.
     */
    private ActivityExecutionException reconstructFailure(
            WorkflowEvent event, String stepName, String workflowId
    ) {
        var message = "Replayed failure";
        if (event.payload() != null && event.payload().has("message")) {
            var text = event.payload().get("message").stringValue();
            if (text != null && !text.isEmpty()) {
                message = text;
            }
        }
        return new ActivityExecutionException(
                workflowId, stepName, new RuntimeException(message));
    }

    // ── Live execution path ───────────────────────────────────────────

    /**
     * Executes the activity live — with optional locking, retries, persistence,
     * and lifecycle event publishing.
     */
    private @Nullable Object executeLive(
            Method method, @Nullable Object @Nullable [] args,
            WorkflowContext ctx, int seq, String stepName
    ) throws Throwable {
        LockHandle lockHandle = null;

        try {
            // Acquire distributed lock (optional — Postgres unique constraint is the real guard)
            lockHandle = acquireLock(ctx, seq, stepName);

            // Publish ACTIVITY_STARTED lifecycle event (best-effort)
            publishLifecycleEvent(ctx, stepName, LifecycleEventType.ACTIVITY_STARTED);

            // Execute activity with retry
            Object result;
            try {
                result = retryExecutor.executeWithRetry(
                        retryPolicy,
                        () -> invokeActivity(method, args),
                        stepName,
                        ctx.workflowId()
                );
            } catch (ActivityExecutionException e) {
                // Retries exhausted — persist failure
                persistFailure(ctx, seq, stepName, e);
                publishLifecycleEvent(ctx, stepName, LifecycleEventType.ACTIVITY_FAILED);
                throw e;
            }

            // Persist success
            var payload = serializer.serialize(result);
            var persisted = appendEventSafe(ctx, seq, EventType.ACTIVITY_COMPLETED, stepName, payload);

            // If DuplicateEventException was caught, use the stored result
            if (persisted != null && persisted != payload) {
                logger.debug("Using previously stored result for '{}' at seq {} (idempotent)", stepName, seq);
                return deserializeResult(persisted, method);
            }

            publishLifecycleEvent(ctx, stepName, LifecycleEventType.ACTIVITY_COMPLETED);
            return result;

        } finally {
            releaseLock(lockHandle, stepName, seq);
        }
    }

    /**
     * Invokes the actual activity method on the implementation.
     * Unwraps {@link InvocationTargetException} to expose the real cause.
     */
    private @Nullable Object invokeActivity(Method method, @Nullable Object @Nullable [] args)
            throws Exception {
        try {
            return method.invoke(activityImpl, args);
        } catch (InvocationTargetException e) {
            var cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new RuntimeException(cause);
        }
    }

    // ── Event persistence ─────────────────────────────────────────────

    /**
     * Appends an event to the memoization log, handling {@link DuplicateEventException}
     * for idempotency.
     *
     * @return the payload that was persisted. If a DuplicateEventException was caught,
     *         returns the payload from the already-stored event (which may differ from
     *         the provided payload). Returns the same payload reference on normal success.
     */
    private @Nullable JsonNode appendEventSafe(
            WorkflowContext ctx, int seq, EventType type,
            String stepName, @Nullable JsonNode payload
    ) {
        var event = new WorkflowEvent(
                UUID.randomUUID(),
                ctx.workflowInstanceId(),
                seq,
                type,
                stepName,
                payload,
                Instant.now()
        );

        try {
            store.appendEvent(event);
            return payload;
        } catch (DuplicateEventException e) {
            logger.debug("Event at seq {} already exists (idempotent), re-reading stored result", seq);
            // Re-read the stored event and return its payload
            var stored = store.getEventBySequence(ctx.workflowInstanceId(), seq);
            return stored.map(WorkflowEvent::payload).orElse(payload);
        }
    }

    /**
     * Persists an ACTIVITY_FAILED event with error details in the payload.
     */
    private void persistFailure(
            WorkflowContext ctx, int seq, String stepName,
            ActivityExecutionException exception
    ) {
        JsonNode errorPayload;
        try {
            var errorDetail = new ErrorDetail(
                    exception.getCause() != null ? exception.getCause().getClass().getName() : "unknown",
                    exception.getCause() != null ? exception.getCause().getMessage() : exception.getMessage()
            );
            errorPayload = serializer.serialize(errorDetail);
        } catch (SerializationException e) {
            logger.warn("Failed to serialize error detail for '{}' at seq {}", stepName, seq, e);
            errorPayload = null;
        }

        appendEventSafe(ctx, seq, EventType.ACTIVITY_FAILED, stepName, errorPayload);
    }

    /**
     * Structured error detail stored in the payload of ACTIVITY_FAILED events.
     */
    private record ErrorDetail(String exceptionType, @Nullable String message) {}

    // ── Lock management ───────────────────────────────────────────────

    /**
     * Attempts to acquire a distributed lock for this activity step.
     * Returns null if the lock backend is unavailable or the lock is already held.
     */
    private @Nullable LockHandle acquireLock(WorkflowContext ctx, int seq, String stepName) {
        if (distributedLock == null) {
            return null;
        }

        var lockKey = "maestro:lock:activity:%s:%d".formatted(ctx.workflowId(), seq);
        var lockTtl = startToCloseTimeout.plusSeconds(10);

        try {
            var handle = distributedLock.tryAcquire(lockKey, lockTtl);
            if (handle.isEmpty()) {
                logger.warn("Could not acquire lock for activity '{}' at seq {}, proceeding with DB dedup only",
                        stepName, seq);
            }
            return handle.orElse(null);
        } catch (Exception e) {
            logger.warn("Lock backend error for activity '{}' at seq {}, proceeding without lock",
                    stepName, seq, e);
            return null;
        }
    }

    /**
     * Releases a distributed lock, logging but not propagating any failure.
     */
    private void releaseLock(@Nullable LockHandle lockHandle, String stepName, int seq) {
        if (lockHandle != null && distributedLock != null) {
            try {
                distributedLock.release(lockHandle);
            } catch (Exception e) {
                logger.warn("Failed to release lock for activity '{}' at seq {}", stepName, seq, e);
            }
        }
    }

    // ── Lifecycle event publishing ────────────────────────────────────

    /**
     * Publishes a lifecycle event for observability. Failures are logged but
     * never interrupt workflow execution.
     */
    private void publishLifecycleEvent(
            WorkflowContext ctx, String stepName, LifecycleEventType eventType
    ) {
        if (messaging == null) {
            return;
        }

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
            logger.warn("Failed to publish {} lifecycle event for activity '{}'",
                    eventType, stepName, e);
        }
    }

    // ── Object method pass-through ────────────────────────────────────

    private @Nullable Object handleObjectMethod(
            Object proxy, Method method, @Nullable Object @Nullable [] args
    ) {
        return switch (method.getName()) {
            case "toString" -> "ActivityProxy[%s]".formatted(activityName);
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args != null && args.length > 0 ? args[0] : null);
            default -> throw new UnsupportedOperationException(
                    "Unsupported Object method: " + method.getName());
        };
    }
}
