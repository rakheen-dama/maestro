package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.Compensate;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.ActivityExecutionException;
import io.b2mash.maestro.core.exception.DuplicateEventException;
import io.b2mash.maestro.core.exception.SerializationException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.LockHandle;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.core.spi.WorkflowStore;
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

        // Activity-specific MDC keys (base workflow keys are set by the scoped executor)
        MDC.put("activityName", stepName);
        MDC.put("sequence", String.valueOf(seq));

        try {
            // ── Memoization lookup ────────────────────────────────────
            var storedEvent = store.getEventBySequence(ctx.workflowInstanceId(), seq);

            if (storedEvent.isPresent()) {
                var result = handleReplay(storedEvent.get(), method, stepName, ctx, seq);
                // Register compensation on successful replay (rebuilds stack during recovery)
                if (storedEvent.get().eventType() == EventType.ACTIVITY_COMPLETED) {
                    registerCompensationIfAnnotated(proxy, method, args, result, ctx);
                }
                return result;
            }

            // ── Live execution ────────────────────────────────────────
            ctx.setReplaying(false);
            var result = executeLive(method, args, ctx, seq, stepName);
            // Register compensation after successful live execution
            registerCompensationIfAnnotated(proxy, method, args, result, ctx);
            return result;

        } finally {
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
     *
     * <p>Attempts to reconstruct the original exception type via reflection so that
     * workflow code catching specific exception types behaves consistently between
     * live execution and replay.
     */
    private ActivityExecutionException reconstructFailure(
            WorkflowEvent event, String stepName, String workflowId
    ) {
        var message = "Replayed failure";
        var exceptionType = (String) null;

        if (event.payload() != null) {
            if (event.payload().has("message")) {
                var text = event.payload().get("message").stringValue();
                if (text != null && !text.isEmpty()) {
                    message = text;
                }
            }
            if (event.payload().has("exceptionType")) {
                exceptionType = event.payload().get("exceptionType").stringValue();
            }
        }

        var cause = tryInstantiateException(exceptionType, message);
        return new ActivityExecutionException(workflowId, stepName, cause);
    }

    /**
     * Attempts to reconstruct an exception by its class name. Falls back to
     * {@link RuntimeException} if the type cannot be instantiated.
     */
    private static Throwable tryInstantiateException(
            @Nullable String exceptionType, String message
    ) {
        if (exceptionType != null) {
            try {
                var clazz = Class.forName(exceptionType);
                if (Throwable.class.isAssignableFrom(clazz)) {
                    // Try (String) constructor first, then no-arg
                    try {
                        var ctor = clazz.getDeclaredConstructor(String.class);
                        return (Throwable) ctor.newInstance(message);
                    } catch (NoSuchMethodException ignored) {
                        try {
                            var ctor = clazz.getDeclaredConstructor();
                            return (Throwable) ctor.newInstance();
                        } catch (NoSuchMethodException alsoIgnored) {
                            // Fall through to default
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("Could not reconstruct exception type '{}', falling back to RuntimeException",
                        exceptionType, e);
            }
        }
        return new RuntimeException(message);
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
                boolean failurePersisted = persistFailure(ctx, seq, stepName, e);
                if (failurePersisted) {
                    publishLifecycleEvent(ctx, stepName, LifecycleEventType.ACTIVITY_FAILED);
                }
                // If failurePersisted is false, a prior ACTIVITY_COMPLETED event exists
                // at this sequence (DuplicateEventException). Don't publish misleading
                // ACTIVITY_FAILED lifecycle event — the stored result is a success.
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
     *
     * @return {@code true} if the failure event was actually persisted, {@code false}
     *         if a prior event already exists at this sequence (DuplicateEventException)
     */
    private boolean persistFailure(
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

        var persisted = appendEventSafe(ctx, seq, EventType.ACTIVITY_FAILED, stepName, errorPayload);
        // If appendEventSafe caught DuplicateEventException, persisted will differ from errorPayload
        // (it returns the previously stored event's payload). This means the failure was NOT persisted.
        return persisted == errorPayload;
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

    // ── @Compensate registration ────────────────────────────────

    /**
     * If the activity method is annotated with {@link Compensate}, registers
     * a compensation action on the workflow's compensation stack.
     *
     * <p>The compensation action calls the compensation method through
     * the same proxy, so it is memoized, retriable, and replayable.
     */
    private void registerCompensationIfAnnotated(
            Object proxy, Method method,
            @Nullable Object @Nullable [] args,
            @Nullable Object result,
            WorkflowContext ctx
    ) {
        var compensate = method.getAnnotation(Compensate.class);
        if (compensate == null) {
            return;
        }

        var compensationMethodName = compensate.value();
        var compensationMethod = findCompensationMethod(method.getDeclaringClass(), compensationMethodName);
        var compensationArgs = resolveCompensationArgs(compensationMethod, method, args, result);
        var compensationStepName = activityName + "." + compensationMethodName;

        ctx.addCompensation(compensationStepName, () -> {
            try {
                compensationMethod.invoke(proxy, compensationArgs);
            } catch (java.lang.reflect.InvocationTargetException e) {
                var cause = e.getCause();
                if (cause instanceof RuntimeException re) throw re;
                throw new RuntimeException("Compensation '%s' failed".formatted(compensationStepName), cause);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Compensation '%s' inaccessible".formatted(compensationStepName), e);
            }
        });
    }

    /**
     * Finds the compensation method by name on the activity interface.
     */
    private static Method findCompensationMethod(Class<?> activityInterface, String methodName) {
        for (var m : activityInterface.getMethods()) {
            if (m.getName().equals(methodName)) {
                return m;
            }
        }
        // Should not happen — validated at proxy creation time
        throw new IllegalStateException(
                "Compensation method '%s' not found on %s"
                        .formatted(methodName, activityInterface.getName()));
    }

    /**
     * Resolves the arguments to pass to the compensation method.
     *
     * <p>Convention-based resolution:
     * <ol>
     *   <li>0 params → no args</li>
     *   <li>1 param assignable from the activity's return type → pass the return value</li>
     *   <li>Same parameter types as the activity → pass the original args</li>
     * </ol>
     */
    static @Nullable Object @Nullable [] resolveCompensationArgs(
            Method compensationMethod, Method activityMethod,
            @Nullable Object @Nullable [] activityArgs,
            @Nullable Object activityResult
    ) {
        var compensationParams = compensationMethod.getParameterTypes();

        if (compensationParams.length == 0) {
            return null;
        }

        // Pattern 1: single param matching the activity's return type → pass the return value
        var returnType = activityMethod.getReturnType();
        if (compensationParams.length == 1
                && returnType != void.class
                && returnType != Void.class
                && compensationParams[0].isAssignableFrom(returnType)) {
            return new Object[]{ activityResult };
        }

        // Pattern 2: same parameters as the activity → pass the original args
        var activityParams = activityMethod.getParameterTypes();
        if (java.util.Arrays.equals(compensationParams, activityParams)) {
            return activityArgs != null ? activityArgs.clone() : null;
        }

        // Should not happen — validated at proxy creation time
        throw new IllegalStateException(
                "Cannot resolve arguments for compensation method '%s' on %s"
                        .formatted(compensationMethod.getName(),
                                compensationMethod.getDeclaringClass().getName()));
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
