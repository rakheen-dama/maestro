package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.Saga;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.context.WorkflowMDC;
import io.b2mash.maestro.core.exception.CompensationException;
import io.b2mash.maestro.core.exception.QueryNotDefinedException;
import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
import io.b2mash.maestro.core.exception.WorkflowExecutionException;
import io.b2mash.maestro.core.exception.WorkflowNotQueryableException;
import io.b2mash.maestro.core.exception.WorkflowNotFoundException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.saga.CompensationStack;
import io.b2mash.maestro.core.saga.SagaManager;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.SignalNotifier;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Central orchestrator that runs workflow methods on Java 21 virtual threads.
 *
 * <p>The WorkflowExecutor manages the full lifecycle of durable workflows:
 * <ul>
 *   <li><b>Start:</b> Creates a workflow instance, spawns a virtual thread,
 *       and invokes the workflow method.</li>
 *   <li><b>Resume:</b> Re-invokes the workflow method in replay mode after
 *       a signal delivery or timer fire.</li>
 *   <li><b>Recovery:</b> At startup, queries for recoverable workflows and
 *       re-invokes each in replay mode.</li>
 *   <li><b>Signal delivery:</b> Persists signals and unparks waiting workflows.</li>
 *   <li><b>Timer fire:</b> Marks timers as fired and unparks sleeping workflows.</li>
 *   <li><b>Shutdown:</b> Stops accepting new work and waits for in-flight
 *       workflows to complete.</li>
 * </ul>
 *
 * <h2>Virtual Thread Model</h2>
 * <p>Each workflow runs on its own virtual thread, named
 * {@code maestro-workflow-{type}-{workflowId}}. The thread is cheap —
 * it yields its carrier thread when parked on sleep or signal await.
 *
 * <h2>Thread Safety</h2>
 * <p>All public methods are thread-safe. The executor can handle concurrent
 * starts, signal deliveries, and timer fires from multiple threads.
 *
 * @see WorkflowContext
 * @see DefaultWorkflowOperations
 * @see ParkingLot
 */
public final class WorkflowExecutor {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowExecutor.class);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);

    private final WorkflowStore store;
    private final @Nullable DistributedLock distributedLock;
    private final @Nullable WorkflowMessaging messaging;
    private final @Nullable SignalNotifier signalNotifier;
    private final PayloadSerializer serializer;
    private final String serviceName;
    private final ParkingLot parkingLot;
    private final SignalManager signalManager;
    private final SagaManager sagaManager;
    private final QueryRegistry queryRegistry;
    private final ConcurrentHashMap<String, RunningWorkflow> runningWorkflows;
    private final AtomicBoolean shuttingDown;
    private final AtomicReference<TimerPoller> timerPoller = new AtomicReference<>();

    /**
     * Creates a new workflow executor.
     *
     * @param store           workflow store for persistence
     * @param distributedLock optional distributed lock backend
     * @param messaging       optional messaging for lifecycle events
     * @param signalNotifier  optional cross-instance signal notification
     * @param serializer      Jackson serializer for payloads
     * @param serviceName     the name of the owning service
     */
    public WorkflowExecutor(
            WorkflowStore store,
            @Nullable DistributedLock distributedLock,
            @Nullable WorkflowMessaging messaging,
            @Nullable SignalNotifier signalNotifier,
            PayloadSerializer serializer,
            String serviceName
    ) {
        this.store = store;
        this.distributedLock = distributedLock;
        this.messaging = messaging;
        this.signalNotifier = signalNotifier;
        this.serializer = serializer;
        this.serviceName = serviceName;
        this.parkingLot = new ParkingLot();
        this.signalManager = new SignalManager(store, messaging, signalNotifier, serializer, parkingLot);
        this.sagaManager = new SagaManager(store, messaging, serializer, serviceName);
        this.queryRegistry = new QueryRegistry();
        this.runningWorkflows = new ConcurrentHashMap<>();
        this.shuttingDown = new AtomicBoolean(false);
    }

    // ── Start workflow ─────────────────────────────────────────────────

    /**
     * Starts a new workflow on a virtual thread.
     *
     * <p>Creates a {@link WorkflowInstance}, persists it, spawns a virtual
     * thread, and invokes the workflow method. The workflow method runs
     * to completion (or parks on sleep/signal await).
     *
     * @param workflowId   the business workflow ID (e.g., {@code "order-abc"})
     * @param workflowType the workflow type name
     * @param taskQueue    the task queue name
     * @param input        the workflow input, or {@code null}
     * @param workflowImpl the workflow implementation instance
     * @param workflowMethod the entry-point method
     * @return the workflow instance UUID
     * @throws IllegalStateException        if the executor is shutting down
     * @throws WorkflowAlreadyExistsException if a workflow with this ID exists
     */
    public UUID startWorkflow(
            String workflowId,
            String workflowType,
            String taskQueue,
            @Nullable Object input,
            Object workflowImpl,
            Method workflowMethod
    ) {
        if (shuttingDown.get()) {
            throw new IllegalStateException(
                    "WorkflowExecutor is shutting down — cannot start workflow '%s'".formatted(workflowId));
        }

        var now = Instant.now();
        var instanceId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var inputPayload = input != null ? serializer.serialize(input) : null;

        var instance = WorkflowInstance.builder()
                .id(instanceId)
                .workflowId(workflowId)
                .runId(runId)
                .workflowType(workflowType)
                .taskQueue(taskQueue)
                .status(WorkflowStatus.RUNNING)
                .input(inputPayload)
                .serviceName(serviceName)
                .eventSequence(0)
                .startedAt(now)
                .updatedAt(now)
                .version(0)
                .build();

        // Persist instance and adopt any pre-delivered signals (self-recovery case 2:
        // signals sent before workflow starts are stored with null instanceId)
        store.createInstance(instance);
        signalManager.adoptOrphanedSignals(workflowId, instanceId);

        // Publish WORKFLOW_STARTED lifecycle event
        publishLifecycleEvent(instance, LifecycleEventType.WORKFLOW_STARTED, null);

        // Launch on virtual thread
        launchWorkflow(instance, workflowImpl, workflowMethod, inputPayload, false);

        logger.info("Started workflow '{}' (type={}, id={})", workflowId, workflowType, instanceId);
        return instanceId;
    }

    // ── Resume workflow ────────────────────────────────────────────────

    /**
     * Resumes a workflow by re-invoking its method in replay mode.
     *
     * <p>Used after signal delivery or timer fire when the workflow's
     * virtual thread is no longer alive (e.g., after a JVM restart).
     * The activity proxy returns stored results (fast-forward), and
     * execution continues from where it left off.
     *
     * @param instance       the workflow instance to resume
     * @param workflowImpl   the workflow implementation instance
     * @param workflowMethod the entry-point method
     */
    public void resumeWorkflow(
            WorkflowInstance instance,
            Object workflowImpl,
            Method workflowMethod
    ) {
        if (runningWorkflows.containsKey(instance.workflowId())) {
            logger.debug("Workflow '{}' is already running — skipping resume", instance.workflowId());
            return;
        }

        logger.info("Resuming workflow '{}' (type={}, status={})",
                instance.workflowId(), instance.workflowType(), instance.status());
        launchWorkflow(instance, workflowImpl, workflowMethod, instance.input(), true);
    }

    // ── Recovery ───────────────────────────────────────────────────────

    /**
     * Recovers all workflows that were active when the service last stopped.
     *
     * <p>Queries the store for recoverable instances (status IN RUNNING,
     * WAITING_SIGNAL, WAITING_TIMER) and re-invokes each in replay mode.
     *
     * @param registrations map of workflow type → registration metadata
     * @return the number of workflows recovered
     */
    public int recoverWorkflows(Map<String, WorkflowRegistration> registrations) {
        var recoverable = store.getRecoverableInstances();
        var count = 0;

        for (var instance : recoverable) {
            var reg = registrations.get(instance.workflowType());
            if (reg == null) {
                logger.warn("No registration for workflow type '{}', skipping recovery of '{}'",
                        instance.workflowType(), instance.workflowId());
                continue;
            }
            resumeWorkflow(instance, reg.workflowImpl(), reg.workflowMethod());
            count++;
        }

        logger.info("Recovered {} workflow(s) from {} recoverable instance(s)",
                count, recoverable.size());
        return count;
    }

    // ── Signal delivery ────────────────────────────────────────────────

    /**
     * Delivers a signal to a workflow.
     *
     * <p>Persists the signal to the store and unparks the workflow if it
     * is currently waiting for this signal. If the workflow hasn't reached
     * the await point yet, the signal is stored and consumed when the
     * workflow calls {@code awaitSignal()}.
     *
     * @param workflowId the target workflow's business ID
     * @param signalName the signal name
     * @param payload    the signal payload, or {@code null}
     */
    public void deliverSignal(String workflowId, String signalName, @Nullable Object payload) {
        signalManager.deliverSignal(workflowId, signalName, payload);
    }

    // ── Timer fire ─────────────────────────────────────────────────────

    /**
     * Fires a timer, resuming a sleeping workflow.
     *
     * <p>Called by the timer poller when a due timer is found. Marks the
     * timer as fired in the store and unparks the workflow's virtual thread.
     * The store transition is persisted before unparking to prevent the
     * timer poller from redelivering.
     *
     * @param workflowId  the workflow's business ID
     * @param timerId     the timer's logical ID (e.g., {@code "sleep-5"})
     * @param timerDbId   the timer's database UUID (for store transition)
     */
    public void fireTimer(String workflowId, String timerId, UUID timerDbId) {
        var fired = store.markTimerFired(timerDbId);
        if (fired) {
            var parkKey = workflowId + ":timer:" + timerId;
            parkingLot.unpark(parkKey, null);
            logger.debug("Fired timer '{}' for workflow '{}'", timerId, workflowId);
        } else {
            logger.debug("Timer '{}' for workflow '{}' already fired or cancelled — skipping unpark",
                    timerId, workflowId);
        }
    }

    // ── Timer poller ────────────────────────────────────────────────────

    /**
     * Starts the background timer poller.
     *
     * <p>The poller scans for due timers at the specified interval and fires
     * them via {@link #fireTimer(String, String, UUID)}. If a
     * {@link DistributedLock} was provided, only the elected leader polls.
     *
     * <p>If this method is never called, no timer polling occurs — workflows
     * that call {@code sleep()} will not wake up after a JVM restart until
     * the poller is started. The Spring Boot starter calls this automatically.
     *
     * @param pollInterval interval between polling cycles (e.g., 5 seconds)
     * @param batchSize    maximum timers to process per cycle (e.g., 100)
     * @throws IllegalStateException if the timer poller is already started
     */
    public void startTimerPoller(Duration pollInterval, int batchSize) {
        if (shuttingDown.get()) {
            throw new IllegalStateException(
                    "WorkflowExecutor is shutting down — cannot start timer poller");
        }
        var poller = new TimerPoller(store, this, distributedLock, serviceName, pollInterval, batchSize);
        if (!timerPoller.compareAndSet(null, poller)) {
            throw new IllegalStateException("Timer poller already started");
        }
        poller.start();
    }

    // ── Shutdown ───────────────────────────────────────────────────────

    /**
     * Gracefully shuts down the executor.
     *
     * <p>Stops accepting new workflows, cancels all parked futures
     * (unblocking sleeping/waiting threads), and waits up to 30 seconds
     * for in-flight workflows to complete.
     */
    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            logger.info("Shutdown already in progress");
            return;
        }

        // Stop the timer poller first — no new timers should fire during shutdown
        var poller = timerPoller.getAndSet(null);
        if (poller != null) {
            poller.stop();
        }

        logger.info("Shutting down WorkflowExecutor, {} workflow(s) in-flight",
                runningWorkflows.size());

        // Cancel all parked futures so threads can exit
        for (var entry : runningWorkflows.entrySet()) {
            parkingLot.unparkAll(entry.getKey());
        }

        // Wait for in-flight workflows with a deadline
        var deadline = Instant.now().plus(SHUTDOWN_TIMEOUT);
        for (var entry : runningWorkflows.entrySet()) {
            try {
                var remaining = Duration.between(Instant.now(), deadline);
                if (remaining.isPositive()) {
                    entry.getValue().thread().join(remaining);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while waiting for workflow '{}' during shutdown", entry.getKey());
                break;
            }
        }

        var remaining = runningWorkflows.size();
        if (remaining > 0) {
            logger.warn("WorkflowExecutor shutdown complete with {} workflow(s) still running", remaining);
        } else {
            logger.info("WorkflowExecutor shutdown complete — all workflows finished");
        }
    }

    // ── Query registration ─────────────────────────────────────────────

    /**
     * Registers query methods for a workflow type.
     *
     * <p>Scans the workflow class for {@link io.b2mash.maestro.core.annotation.QueryMethod}
     * annotations and stores them in the query registry. Must be called before
     * {@link #queryWorkflow} can dispatch queries to this workflow type.
     *
     * <p>Typically called during startup, alongside workflow registration.
     *
     * @param workflowType  the workflow type name
     * @param workflowClass the workflow class to scan for query methods
     * @throws IllegalArgumentException if any annotated method violates constraints
     */
    public void registerQueries(String workflowType, Class<?> workflowClass) {
        queryRegistry.register(workflowType, workflowClass);
    }

    // ── Query dispatch ──────────────────────────────────────────────────

    /**
     * Queries a running workflow's state by invoking a
     * {@link io.b2mash.maestro.core.annotation.QueryMethod} on the workflow instance.
     *
     * <p>The query method is invoked from the <b>caller's thread</b>, not the
     * workflow's virtual thread. The workflow author is responsible for ensuring
     * visibility of state fields read by query methods (use {@code volatile}
     * or synchronization).
     *
     * <p>Currently only workflows running in-memory on this executor can be
     * queried. If the workflow is not in-memory (completed, failed, or running
     * on a different instance), a {@link WorkflowNotQueryableException} is thrown.
     *
     * @param workflowId the workflow's business ID
     * @param queryName  the query name (from {@code @QueryMethod.name()} or the method name)
     * @param queryArg   the query argument, or {@code null} for no-arg queries
     * @param resultType the expected result type
     * @param <T>        the result type
     * @return the query result
     * @throws WorkflowNotFoundException      if no workflow with this ID exists
     * @throws WorkflowNotQueryableException  if the workflow is not in-memory
     * @throws QueryNotDefinedException       if no query method with this name exists
     * @throws WorkflowExecutionException     if the query method throws an exception
     */
    public <T> T queryWorkflow(String workflowId, String queryName,
                               @Nullable Object queryArg, Class<T> resultType) {
        var running = runningWorkflows.get(workflowId);
        if (running == null) {
            // Distinguish between "workflow exists but not in-memory" and "workflow doesn't exist"
            var instance = store.getInstance(workflowId);
            if (instance.isPresent()) {
                throw new WorkflowNotQueryableException(workflowId, queryName, instance.get().status());
            }
            throw new WorkflowNotFoundException(workflowId);
        }

        var queryMethod = queryRegistry.getQueryMethod(running.workflowType(), queryName)
                .orElseThrow(() -> new QueryNotDefinedException(
                        workflowId, queryName, running.workflowType()));

        return invokeQueryMethod(queryMethod, running.workflowImpl(), queryArg, resultType);
    }

    // ── Query status ────────────────────────────────────────────────────

    /**
     * Returns whether a workflow with the given ID is currently running
     * on this executor.
     *
     * @param workflowId the workflow's business ID
     * @return {@code true} if the workflow is active on this executor
     */
    public boolean isRunning(String workflowId) {
        return runningWorkflows.containsKey(workflowId);
    }

    /**
     * Returns the number of currently running workflows.
     *
     * @return the count of active workflows
     */
    public int runningCount() {
        return runningWorkflows.size();
    }

    // ── Internal: workflow launch ──────────────────────────────────────

    private void launchWorkflow(
            WorkflowInstance instance,
            Object workflowImpl,
            Method workflowMethod,
            @Nullable JsonNode inputPayload,
            boolean replaying
    ) {
        var compensationStack = new CompensationStack();
        var operations = new DefaultWorkflowOperations(
                store, distributedLock, messaging, serializer, parkingLot, compensationStack,
                signalManager);

        var ctx = new WorkflowContext(
                instance.id(),
                instance.workflowId(),
                instance.runId(),
                instance.workflowType(),
                instance.taskQueue(),
                serviceName,
                0,
                replaying,
                operations
        );

        // Detect @Saga on the workflow method
        var sagaAnnotation = workflowMethod.getAnnotation(Saga.class);
        var parallelCompensation = sagaAnnotation != null && sagaAnnotation.parallelCompensation();

        var thread = Thread.ofVirtual()
                .name("maestro-workflow-%s-%s".formatted(instance.workflowType(), instance.workflowId()))
                .unstarted(() -> {
                    WorkflowMDC.populate(ctx);
                    try {
                        ScopedValue.where(WorkflowContext.scopedValue(), ctx)
                                .run(() -> executeWorkflow(ctx, instance, workflowImpl, workflowMethod,
                                        inputPayload, compensationStack, parallelCompensation));
                    } finally {
                        WorkflowMDC.clear();
                    }
                });

        // Register before starting to prevent the race where a fast workflow
        // finishes and removes itself before the put() below executes
        var running = new RunningWorkflow(thread, instance, instance.workflowType(), workflowImpl);
        runningWorkflows.put(instance.workflowId(), running);
        thread.start();
    }

    // ── Internal: workflow execution (virtual thread body) ─────────────

    private void executeWorkflow(
            WorkflowContext ctx,
            WorkflowInstance instance,
            Object workflowImpl,
            Method workflowMethod,
            @Nullable JsonNode inputPayload,
            CompensationStack compensationStack,
            boolean parallelCompensation
    ) {
        try {
            // Deserialize input and invoke the workflow method
            Object result = invokeWorkflowMethod(workflowImpl, workflowMethod, inputPayload);

            // Success — re-read instance for latest version (DefaultWorkflowOperations
            // may have bumped the version via status transitions)
            var outputPayload = result != null ? serializer.serialize(result) : null;
            var latest = store.getInstance(ctx.workflowId()).orElse(instance);
            var updated = latest.toBuilder()
                    .status(WorkflowStatus.COMPLETED)
                    .output(outputPayload)
                    .completedAt(Instant.now())
                    .updatedAt(Instant.now())
                    .eventSequence(ctx.currentSequence())
                    .version(latest.version() + 1)
                    .build();
            store.updateInstance(updated);

            // Append WORKFLOW_COMPLETED event
            appendEvent(ctx, EventType.WORKFLOW_COMPLETED, null, outputPayload);
            publishLifecycleEvent(instance, LifecycleEventType.WORKFLOW_COMPLETED, null);

            logger.info("Workflow '{}' completed successfully", ctx.workflowId());

        } catch (Exception e) {
            handleWorkflowFailure(ctx, instance, e, compensationStack, parallelCompensation);
        } finally {
            runningWorkflows.remove(ctx.workflowId());
        }
    }

    private @Nullable Object invokeWorkflowMethod(
            Object workflowImpl, Method workflowMethod, @Nullable JsonNode inputPayload
    ) throws Exception {
        try {
            if (workflowMethod.getParameterCount() == 0) {
                return workflowMethod.invoke(workflowImpl);
            } else {
                // Deserialize input to the method's parameter type
                var paramType = workflowMethod.getParameterTypes()[0];
                var input = inputPayload != null ? serializer.deserialize(inputPayload, paramType) : null;
                return workflowMethod.invoke(workflowImpl, input);
            }
        } catch (InvocationTargetException e) {
            var cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause);
        }
    }

    // ── Internal: failure handling ─────────────────────────────────────

    private void handleWorkflowFailure(
            WorkflowContext ctx,
            WorkflowInstance instance,
            Exception exception,
            CompensationStack compensationStack,
            boolean parallelCompensation
    ) {
        logger.error("Workflow '{}' failed: {}", ctx.workflowId(), exception.getMessage(), exception);

        // Run compensations via SagaManager if any are registered
        if (!compensationStack.isEmpty()) {
            try {
                sagaManager.compensate(ctx, instance, compensationStack, parallelCompensation);
            } catch (CompensationException e) {
                // Partial compensation failure — log and continue to FAILED transition.
                // The CompensationException details are already recorded in the event log
                // (COMPENSATION_STEP_FAILED events) by the SagaManager.
                logger.warn("Partial compensation failure for workflow '{}': {}",
                        ctx.workflowId(), e.failedCompensations());
            }
        }

        // Update instance to FAILED
        try {
            var errorPayload = serializer.serialize(new ErrorDetail(
                    exception.getClass().getName(),
                    exception.getMessage()
            ));
            // Re-read for latest version (compensations or status transitions may have bumped it)
            var latest = store.getInstance(ctx.workflowId()).orElse(instance);
            var updated = latest.toBuilder()
                    .status(WorkflowStatus.FAILED)
                    .output(errorPayload)
                    .completedAt(Instant.now())
                    .updatedAt(Instant.now())
                    .eventSequence(ctx.currentSequence())
                    .version(latest.version() + 1)
                    .build();
            store.updateInstance(updated);

            appendEvent(ctx, EventType.WORKFLOW_FAILED, null, errorPayload);
            publishLifecycleEvent(instance, LifecycleEventType.WORKFLOW_FAILED, null);
        } catch (Exception updateError) {
            logger.error("Failed to update workflow '{}' status to FAILED",
                    ctx.workflowId(), updateError);
        }
    }

    // ── Internal: event and lifecycle helpers ──────────────────────────

    private void appendEvent(WorkflowContext ctx, EventType type,
                             @Nullable String stepName, @Nullable JsonNode payload) {
        try {
            var event = new WorkflowEvent(
                    UUID.randomUUID(),
                    ctx.workflowInstanceId(),
                    ctx.nextSequence(),
                    type,
                    stepName,
                    payload,
                    Instant.now()
            );
            store.appendEvent(event);
        } catch (Exception e) {
            logger.warn("Failed to append {} event for workflow '{}'", type, ctx.workflowId(), e);
        }
    }

    private void publishLifecycleEvent(
            WorkflowInstance instance, LifecycleEventType eventType,
            @Nullable String stepName
    ) {
        if (messaging == null) return;
        try {
            messaging.publishLifecycleEvent(new WorkflowLifecycleEvent(
                    instance.id(),
                    instance.workflowId(),
                    instance.workflowType(),
                    serviceName,
                    instance.taskQueue(),
                    eventType,
                    stepName,
                    null,
                    Instant.now()
            ));
        } catch (Exception e) {
            logger.warn("Failed to publish {} lifecycle event for workflow '{}'",
                    eventType, instance.workflowId(), e);
        }
    }

    // ── Internal: query invocation ─────────────────────────────────────

    @SuppressWarnings("unchecked")
    private <T> T invokeQueryMethod(Method queryMethod, Object workflowImpl,
                                    @Nullable Object queryArg, Class<T> resultType) {
        try {
            Object result;
            if (queryMethod.getParameterCount() == 0) {
                result = queryMethod.invoke(workflowImpl);
            } else {
                var paramType = queryMethod.getParameterTypes()[0];
                if (queryArg != null && !paramType.isInstance(queryArg)
                        && !isBoxingCompatible(paramType, queryArg.getClass())) {
                    throw new IllegalArgumentException(
                            "Query '%s' argument type mismatch: expected %s, got %s"
                                    .formatted(queryMethod.getName(), paramType.getName(),
                                            queryArg.getClass().getName()));
                }
                result = queryMethod.invoke(workflowImpl, queryArg);
            }
            return resultType.cast(result);
        } catch (InvocationTargetException e) {
            var cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new WorkflowExecutionException(
                    "Query method '%s' threw a checked exception".formatted(queryMethod.getName()), cause);
        } catch (IllegalAccessException e) {
            throw new WorkflowExecutionException(
                    "Cannot access query method '%s'".formatted(queryMethod.getName()), e);
        }
    }

    private static final Map<Class<?>, Class<?>> PRIMITIVE_TO_WRAPPER = Map.of(
            boolean.class, Boolean.class, byte.class, Byte.class,
            char.class, Character.class, short.class, Short.class,
            int.class, Integer.class, long.class, Long.class,
            float.class, Float.class, double.class, Double.class
    );

    private static boolean isBoxingCompatible(Class<?> paramType, Class<?> argType) {
        if (!paramType.isPrimitive()) return false;
        var wrapper = PRIMITIVE_TO_WRAPPER.get(paramType);
        return wrapper != null && wrapper.isAssignableFrom(argType);
    }

    // ── Internal records ───────────────────────────────────────────────

    /**
     * Tracks a currently running workflow for shutdown coordination and query dispatch.
     */
    record RunningWorkflow(Thread thread, WorkflowInstance instance, String workflowType,
                           Object workflowImpl) {}

    /**
     * Error detail stored in the workflow output on failure.
     */
    private record ErrorDetail(String exceptionType, @Nullable String message) {}
}
