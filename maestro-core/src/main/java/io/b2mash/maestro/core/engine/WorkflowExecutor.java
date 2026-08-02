package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.Saga;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.context.WorkflowMDC;
import io.b2mash.maestro.core.exception.CompensationException;
import io.b2mash.maestro.core.exception.DuplicateEventException;
import io.b2mash.maestro.core.exception.OptimisticLockException;
import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.exception.QueryNotDefinedException;
import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
import io.b2mash.maestro.core.exception.WorkflowExecutionException;
import io.b2mash.maestro.core.exception.WorkflowNotQueryableException;
import io.b2mash.maestro.core.exception.WorkflowNotFoundException;
import io.b2mash.maestro.core.exception.WorkflowTerminatedException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.AbandonReason;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.observe.StandDownReason;
import io.b2mash.maestro.core.observe.WorkflowInfo;
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
import tools.jackson.databind.JsonNode;

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
 *   <li><b>Timer cancel:</b> Marks a pending timer as cancelled and unparks a
 *       sleeping workflow with a catchable
 *       {@link io.b2mash.maestro.core.exception.TimerCancelledException}.</li>
 *   <li><b>Terminate:</b> Marks a workflow {@code TERMINATED} from any node and
 *       stops it — without compensation — evicting its virtual thread if this
 *       node happens to own it.</li>
 *   <li><b>Shutdown:</b> Stops accepting new work, waits for in-flight
 *       workflows to drain, and leaves parked workflows in their
 *       {@code WAITING_*} status for another node to recover — a graceful
 *       stop is never a workflow failure.</li>
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

    /**
     * Default wait for in-flight workflows to drain during {@link #shutdown()},
     * used when no explicit {@code shutdownTimeout} is passed to the
     * constructor. Corresponds to {@code maestro.shutdown.timeout} in the
     * Spring Boot starter.
     */
    static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);

    /**
     * How many times a terminal-status write is retried against a fresh read
     * before the conflict is treated as unrecoverable. Conflicts come from
     * other writers of the same row, so a handful of attempts is ample; an
     * unbounded loop would hide a pathological writer.
     */
    private static final int TERMINAL_TRANSITION_ATTEMPTS = 5;

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
    private final WorkflowInstanceLockManager instanceLockManager;
    private final boolean lifecycleEventsEnabled;
    private final Duration shutdownTimeout;
    private final Duration wakeRecheckInterval;
    private final EngineObserver observer;
    private final LifecycleEventPublisher lifecycleEventPublisher;
    private final ConcurrentHashMap<String, RunningWorkflow> runningWorkflows;
    private final AtomicBoolean shuttingDown;
    private final AtomicReference<TimerPoller> timerPoller = new AtomicReference<>();
    private final AtomicReference<RecoveryPoller> recoveryPoller = new AtomicReference<>();
    private final AtomicBoolean timerPollerStarted = new AtomicBoolean(false);
    private final AtomicBoolean recoveryPollerStarted = new AtomicBoolean(false);

    /**
     * Creates a new workflow executor with the default lock key prefix
     * ({@code maestro:lock:}) and instance-lock TTL (30s).
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
        this(store, distributedLock, messaging, signalNotifier, serializer, serviceName,
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX,
                WorkflowInstanceLockManager.DEFAULT_LOCK_TTL);
    }

    /**
     * Creates a new workflow executor with explicit lock configuration and
     * lifecycle event publishing enabled.
     *
     * @param store           workflow store for persistence
     * @param distributedLock optional distributed lock backend
     * @param messaging       optional messaging for lifecycle events
     * @param signalNotifier  optional cross-instance signal notification
     * @param serializer      Jackson serializer for payloads
     * @param serviceName     the name of the owning service
     * @param lockKeyPrefix   prefix for distributed lock keys (e.g. {@code maestro:lock:})
     * @param instanceLockTtl TTL for the per-workflow instance lock; renewed
     *                        at one third of this interval — must be strictly
     *                        positive
     * @throws IllegalArgumentException if {@code instanceLockTtl} is
     *                                  {@code null}, zero, or negative
     */
    public WorkflowExecutor(
            WorkflowStore store,
            @Nullable DistributedLock distributedLock,
            @Nullable WorkflowMessaging messaging,
            @Nullable SignalNotifier signalNotifier,
            PayloadSerializer serializer,
            String serviceName,
            String lockKeyPrefix,
            Duration instanceLockTtl
    ) {
        this(store, distributedLock, messaging, signalNotifier, serializer, serviceName,
                lockKeyPrefix, instanceLockTtl, true);
    }

    /**
     * Creates a new workflow executor with explicit lock configuration and
     * explicit control over lifecycle event publishing.
     *
     * @param store                   workflow store for persistence
     * @param distributedLock         optional distributed lock backend
     * @param messaging               optional messaging for lifecycle events
     * @param signalNotifier          optional cross-instance signal notification
     * @param serializer              Jackson serializer for payloads
     * @param serviceName             the name of the owning service
     * @param lockKeyPrefix           prefix for distributed lock keys (e.g. {@code maestro:lock:})
     * @param instanceLockTtl         TTL for the per-workflow instance lock; renewed
     *                                at one third of this interval — must be strictly
     *                                positive
     * @param lifecycleEventsEnabled  whether {@code WORKFLOW_STARTED}/{@code _COMPLETED}/
     *                                {@code _FAILED} lifecycle events are published at all
     *                                (independent of whether {@code messaging} is configured).
     *                                Corresponds to {@code maestro.admin.events.enabled} in the
     *                                Spring Boot starter.
     * @throws IllegalArgumentException if {@code instanceLockTtl} is
     *                                  {@code null}, zero, or negative
     */
    public WorkflowExecutor(
            WorkflowStore store,
            @Nullable DistributedLock distributedLock,
            @Nullable WorkflowMessaging messaging,
            @Nullable SignalNotifier signalNotifier,
            PayloadSerializer serializer,
            String serviceName,
            String lockKeyPrefix,
            Duration instanceLockTtl,
            boolean lifecycleEventsEnabled
    ) {
        this(store, distributedLock, messaging, signalNotifier, serializer, serviceName,
                lockKeyPrefix, instanceLockTtl, lifecycleEventsEnabled,
                DEFAULT_SHUTDOWN_TIMEOUT, SignalManager.DEFAULT_WAKE_RECHECK_INTERVAL);
    }

    /**
     * Creates a new workflow executor with explicit lock configuration,
     * explicit control over lifecycle event publishing, and explicit
     * shutdown/signal timing.
     *
     * @param store                  workflow store for persistence
     * @param distributedLock        optional distributed lock backend
     * @param messaging              optional messaging for lifecycle events
     * @param signalNotifier         optional cross-instance signal notification
     * @param serializer             Jackson serializer for payloads
     * @param serviceName            the name of the owning service
     * @param lockKeyPrefix          prefix for distributed lock keys (e.g. {@code maestro:lock:})
     * @param instanceLockTtl        TTL for the per-workflow instance lock; renewed
     *                               at one third of this interval — must be strictly
     *                               positive
     * @param lifecycleEventsEnabled whether {@code WORKFLOW_STARTED}/{@code _COMPLETED}/
     *                               {@code _FAILED} lifecycle events are published at all
     *                               (independent of whether {@code messaging} is configured).
     *                               Corresponds to {@code maestro.admin.events.enabled} in the
     *                               Spring Boot starter.
     * @param shutdownTimeout        how long {@link #shutdown()} waits for in-flight
     *                               workflows to drain before returning — must be strictly
     *                               positive. Corresponds to {@code maestro.shutdown.timeout}.
     * @param wakeRecheckInterval    how often a parked {@code awaitSignal()} or
     *                               {@code sleep()} re-checks the store for a wake that
     *                               happened without a local unpark — a signal persisted
     *                               on another node, or a timer fired/cancelled by a
     *                               remote timer-poller leader — must be strictly
     *                               positive. Bounds cross-node signal, timer-fire and
     *                               timer-cancel latency for a parked workflow.
     *                               Corresponds to {@code maestro.signal.wake-recheck-interval}.
     * @throws IllegalArgumentException if {@code instanceLockTtl}, {@code shutdownTimeout},
     *                                  or {@code wakeRecheckInterval} is {@code null}, zero,
     *                                  or negative
     */
    public WorkflowExecutor(
            WorkflowStore store,
            @Nullable DistributedLock distributedLock,
            @Nullable WorkflowMessaging messaging,
            @Nullable SignalNotifier signalNotifier,
            PayloadSerializer serializer,
            String serviceName,
            String lockKeyPrefix,
            Duration instanceLockTtl,
            boolean lifecycleEventsEnabled,
            Duration shutdownTimeout,
            Duration wakeRecheckInterval
    ) {
        this(store, distributedLock, messaging, signalNotifier, serializer, serviceName,
                lockKeyPrefix, instanceLockTtl, lifecycleEventsEnabled,
                shutdownTimeout, wakeRecheckInterval, EngineObserver.NOOP);
    }

    /**
     * Creates a new workflow executor with explicit lock configuration,
     * explicit control over lifecycle event publishing, explicit
     * shutdown/signal timing, and an {@link EngineObserver}.
     *
     * @param store                  workflow store for persistence
     * @param distributedLock        optional distributed lock backend
     * @param messaging              optional messaging for lifecycle events
     * @param signalNotifier         optional cross-instance signal notification
     * @param serializer             Jackson serializer for payloads
     * @param serviceName            the name of the owning service
     * @param lockKeyPrefix          prefix for distributed lock keys (e.g. {@code maestro:lock:})
     * @param instanceLockTtl        TTL for the per-workflow instance lock; renewed
     *                               at one third of this interval — must be strictly
     *                               positive
     * @param lifecycleEventsEnabled whether {@code WORKFLOW_STARTED}/{@code _COMPLETED}/
     *                               {@code _FAILED} lifecycle events are published at all
     *                               (independent of whether {@code messaging} is configured).
     *                               Corresponds to {@code maestro.admin.events.enabled} in the
     *                               Spring Boot starter.
     * @param shutdownTimeout        how long {@link #shutdown()} waits for in-flight
     *                               workflows to drain before returning — must be strictly
     *                               positive. Corresponds to {@code maestro.shutdown.timeout}.
     * @param wakeRecheckInterval    how often a parked {@code awaitSignal()} or
     *                               {@code sleep()} re-checks the store for a wake that
     *                               happened without a local unpark — must be strictly
     *                               positive. Corresponds to
     *                               {@code maestro.signal.wake-recheck-interval}.
     * @param observer               engine observation seam invoked synchronously at
     *                               execution boundaries and handed to every component
     *                               this executor builds; pass
     *                               {@link EngineObserver#NOOP} (never {@code null})
     *                               when no observation is wanted — see
     *                               {@link EngineObserver} for the callback and
     *                               thread-safety contract
     * @throws IllegalArgumentException if {@code instanceLockTtl}, {@code shutdownTimeout},
     *                                  or {@code wakeRecheckInterval} is {@code null}, zero,
     *                                  or negative
     */
    public WorkflowExecutor(
            WorkflowStore store,
            @Nullable DistributedLock distributedLock,
            @Nullable WorkflowMessaging messaging,
            @Nullable SignalNotifier signalNotifier,
            PayloadSerializer serializer,
            String serviceName,
            String lockKeyPrefix,
            Duration instanceLockTtl,
            boolean lifecycleEventsEnabled,
            Duration shutdownTimeout,
            Duration wakeRecheckInterval,
            EngineObserver observer
    ) {
        if (instanceLockTtl == null || instanceLockTtl.isNegative() || instanceLockTtl.isZero()) {
            throw new IllegalArgumentException(
                    "instanceLockTtl must be positive, got " + instanceLockTtl);
        }
        if (shutdownTimeout == null || shutdownTimeout.isNegative() || shutdownTimeout.isZero()) {
            throw new IllegalArgumentException(
                    "shutdownTimeout must be positive, got " + shutdownTimeout);
        }
        if (wakeRecheckInterval == null || wakeRecheckInterval.isNegative() || wakeRecheckInterval.isZero()) {
            throw new IllegalArgumentException(
                    "wakeRecheckInterval must be positive, got " + wakeRecheckInterval);
        }
        this.store = store;
        this.distributedLock = distributedLock;
        this.observer = observer;
        // Wrapped once, here, and handed to every component this executor builds
        // (SignalManager, SagaManager, DefaultWorkflowOperations below) so the
        // enabled flag is honoured by every lifecycle-event publisher this
        // executor owns, not just this class's own WORKFLOW_* events — see
        // GatedWorkflowMessaging's Javadoc for why this is the shared seam
        // rather than each class re-implementing its own enabled check.
        this.messaging = GatedWorkflowMessaging.wrap(messaging, lifecycleEventsEnabled);
        this.signalNotifier = signalNotifier;
        this.serializer = serializer;
        this.serviceName = serviceName;
        this.parkingLot = new ParkingLot();
        this.signalManager = new SignalManager(
                store, this.messaging, signalNotifier, serializer, parkingLot, wakeRecheckInterval,
                observer);
        this.sagaManager = new SagaManager(store, this.messaging, serializer, serviceName, observer);
        this.instanceLockManager = new WorkflowInstanceLockManager(
                distributedLock, serviceName, lockKeyPrefix, instanceLockTtl,
                instanceLockTtl.dividedBy(3), observer);
        this.queryRegistry = new QueryRegistry();
        this.lifecycleEventsEnabled = lifecycleEventsEnabled;
        this.shutdownTimeout = shutdownTimeout;
        this.wakeRecheckInterval = wakeRecheckInterval;
        this.lifecycleEventPublisher = new LifecycleEventPublisher(serviceName);
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

        // Acquire the instance lock BEFORE the instance row exists: the
        // recovery poller can only see the workflow after createInstance, and
        // by then this node already owns the lock — closing the race where a
        // poller cycle steals a just-created workflow before launch.
        var acquisition = instanceLockManager.tryAcquire(workflowId);
        try {
            // Persist instance and adopt any pre-delivered signals (self-recovery case 2:
            // signals sent before workflow starts are stored with null instanceId)
            store.createInstance(instance);
            signalManager.adoptOrphanedSignals(workflowId, instanceId);
        } catch (RuntimeException e) {
            if (acquisition == WorkflowInstanceLockManager.Acquisition.ACQUIRED) {
                instanceLockManager.release(workflowId);
            }
            throw e;
        }

        // Publish WORKFLOW_STARTED lifecycle event
        publishLifecycleEvent(instance, LifecycleEventType.WORKFLOW_STARTED, null);
        emit("workflowStarted", workflowId, () -> observer.workflowStarted(observed(instance)));

        // Launch on virtual thread
        launchWorkflow(instance, workflowImpl, workflowMethod, inputPayload, false, acquisition);

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
     * @return {@code true} if the workflow was launched; {@code false} if it
     *         was skipped (already running locally, instance lock held by
     *         another node, or the instance turned terminal in the meantime)
     */
    public boolean resumeWorkflow(
            WorkflowInstance instance,
            Object workflowImpl,
            Method workflowMethod
    ) {
        if (shuttingDown.get()) {
            logger.debug("Executor shutting down — skipping resume of workflow '{}'",
                    instance.workflowId());
            return false;
        }
        if (runningWorkflows.containsKey(instance.workflowId())) {
            logger.debug("Workflow '{}' is already running — skipping resume", instance.workflowId());
            return false;
        }

        var launched = launchWorkflow(instance, workflowImpl, workflowMethod, instance.input(), true, null);
        if (launched) {
            logger.info("Resuming workflow '{}' (type={}, status={})",
                    instance.workflowId(), instance.workflowType(), instance.status());
        }
        return launched;
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
            if (resumeWorkflow(instance, reg.workflowImpl(), reg.workflowMethod())) {
                count++;
            }
        }

        if (count > 0) {
            logger.info("Recovered {} workflow(s) from {} recoverable instance(s)",
                    count, recoverable.size());
        } else {
            logger.debug("Recovered 0 workflow(s) from {} recoverable instance(s)",
                    recoverable.size());
        }
        var scanned = recoverable.size();
        var adopted = count;
        emit("recoveryPass", null, () -> observer.recoveryPass(scanned, adopted));
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
     * <p>The unpark is local to this JVM. If the sleeping workflow's thread
     * is parked on <em>another</em> node — routine whenever the timer-poller
     * leader is not the owner — the durable {@code FIRED} row is what wakes
     * it: a parked {@code sleep()} re-reads its timer row every wake-recheck
     * interval ({@code maestro.signal.wake-recheck-interval}) and resumes
     * within one interval of this call.
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

    // ── Timer cancel ───────────────────────────────────────────────────

    /**
     * Cancels a pending timer, unparking a sleeping workflow with a
     * catchable, durable "cancelled" outcome.
     *
     * <p>The store transition ({@code PENDING → CANCELLED}) is the single
     * arbiter of the outcome — arbitrated by the same compare-and-set
     * {@link #fireTimer(String, String, UUID)} uses for firing, so exactly
     * one of a racing fire and cancel wins. This is the only supported
     * cancellation entry point: unlike a hypothetical store-only cancel, it
     * also unparks the waiting workflow, so {@code sleep()} does not stall
     * forever on a row that will never become {@code FIRED}.
     *
     * <p>If this call wins the compare-and-set, the workflow's virtual
     * thread — if parked here — resumes, reads the now-{@code CANCELLED} row,
     * and its {@code sleep()} call throws
     * {@link io.b2mash.maestro.core.exception.TimerCancelledException}. The
     * same exception is thrown on every later replay, because the outcome is
     * memoized as a {@code TIMER_CANCELLED} event at the sequence a
     * {@code TIMER_FIRED} event would otherwise occupy. If the workflow is
     * not parked on this node — it may be parked on another node, or about
     * to re-park after a crash — the cancellation is still durable and is
     * observed within one wake-recheck interval by the owning node's parked
     * {@code sleep()}, or at the workflow's next recovery — the same
     * cross-node wake contract {@link #fireTimer} has.
     *
     * <p>Cancelling a timer that has already fired, or was already
     * cancelled, is a {@code false} no-op: the row's outcome is already
     * decided and the caller is told it lost the race rather than being left
     * to guess.
     *
     * @param workflowId the workflow's business ID
     * @param timerId    the timer's logical ID (e.g., {@code "sleep-5"})
     * @param timerDbId  the timer's database UUID (for store transition)
     * @return {@code true} if this call transitioned the timer
     *         {@code PENDING → CANCELLED}; {@code false} if the timer had
     *         already fired or was already cancelled
     */
    public boolean cancelTimer(String workflowId, String timerId, UUID timerDbId) {
        var cancelled = store.markTimerCancelled(timerDbId);
        if (cancelled) {
            var parkKey = workflowId + ":timer:" + timerId;
            parkingLot.unpark(parkKey, null);
            logger.debug("Cancelled timer '{}' for workflow '{}'", timerId, workflowId);
        } else {
            logger.debug("Timer '{}' for workflow '{}' already fired or cancelled — skipping unpark",
                    timerId, workflowId);
        }
        return cancelled;
    }

    // ── Terminate ──────────────────────────────────────────────────────

    /**
     * The outcome of a {@link #terminateWorkflow(String, String)} call.
     *
     * <p>Every constant is a <em>deterministic</em> outcome: retrying the same
     * command can never turn one into another, so a caller driving terminate
     * from an at-least-once transport should acknowledge the message for all of
     * them. Only genuinely transient conditions (the store being unavailable, a
     * version conflict that never clears) are signalled as exceptions instead.
     */
    public enum TerminateOutcome {

        /** This call moved the workflow to {@code TERMINATED}. */
        TERMINATED,

        /**
         * The workflow was already {@code COMPLETED}, {@code FAILED} or
         * {@code TERMINATED}. Nothing was written — a finished workflow's
         * outcome is durable truth and terminate does not rewrite history.
         */
        ALREADY_TERMINAL,

        /** No instance with this workflow ID exists in this service's store. */
        NOT_FOUND
    }

    /**
     * Terminates a workflow: marks it {@code TERMINATED} and stops it.
     *
     * <p>This is the engine side of the admin dashboard's Terminate button.
     *
     * <h2>Durable first, then local eviction</h2>
     * <p>The instance row is moved to {@code TERMINATED} by an optimistic
     * compare-and-set before anything else happens, and the row's
     * {@code version} — not the instance lock — is the arbiter. That is what
     * lets this method be called on <b>any</b> node: the command arrives on
     * whichever member of the consumer group the transport picks, which is
     * usually not the node running the workflow. Only afterwards, and only if
     * this node happens to own the workflow, is the running virtual thread
     * evicted by abandoning its parks with a
     * {@link WorkflowTerminatedException}.
     *
     * <p>A workflow owned by <em>another</em> node converges without any
     * cross-node signalling: its thread hits the terminal-status guard the next
     * time it writes a status, and a thread parked on {@code awaitSignal()}
     * notices within one wake-recheck interval. In every case the durable state
     * is already {@code TERMINATED} — convergence only bounds when the zombie
     * thread stops.
     *
     * <h2>What terminate does not do</h2>
     * <ul>
     *   <li><b>No compensation.</b> Terminate marks and stops; it does not
     *       unwind a saga. Compensation steps that already ran stay memoized,
     *       pending ones never run. ("Cancel", which compensates, is a
     *       different and larger feature.)</li>
     *   <li><b>No interruption of an in-flight activity.</b> Consistent with
     *       {@link #shutdown()}: the activity finishes and memoizes its result
     *       (harmless), and the run stands down at its next park or at its
     *       final transition.</li>
     *   <li><b>No memoization-log event.</b> Appending one from outside the
     *       workflow's own thread would race that thread's sequence counter
     *       against the {@code (workflow_instance_id, sequence_number)} unique
     *       index. The instance row plus the
     *       {@link LifecycleEventType#WORKFLOW_TERMINATED} lifecycle event are
     *       the durable record.</li>
     *   <li><b>No timer cancellation.</b> A pending timer row may still fire;
     *       the terminal-status guard makes that a no-op.</li>
     * </ul>
     *
     * <h2>Idempotency</h2>
     * <p>Safe under at-least-once redelivery: a second delivery re-reads the
     * row, finds it terminal and returns {@link TerminateOutcome#ALREADY_TERMINAL}
     * without overwriting the first call's reason.
     *
     * <p><b>Thread safety:</b> thread-safe; may be called concurrently from any
     * thread, including several nodes at once.
     *
     * @param workflowId the business workflow ID to terminate
     * @param reason     an operator-supplied reason recorded on the instance's
     *                   {@code output}, or {@code null}
     * @return what this call did — see {@link TerminateOutcome}
     * @throws OptimisticLockException if the instance row is being written
     *                                 continuously and the attempt budget is
     *                                 exhausted. Transient: the caller should
     *                                 <em>not</em> acknowledge, so the command is
     *                                 redelivered against fresh state.
     */
    public TerminateOutcome terminateWorkflow(String workflowId, @Nullable String reason) {
        for (var attempt = 1; ; attempt++) {
            var existing = store.getInstance(workflowId);
            if (existing.isEmpty()) {
                logger.warn("Cannot terminate workflow '{}' — no such instance in this service", workflowId);
                return TerminateOutcome.NOT_FOUND;
            }
            var latest = existing.get();
            if (latest.status().isTerminal()) {
                logger.warn("Workflow '{}' is already {} — not terminating", workflowId, latest.status());
                return TerminateOutcome.ALREADY_TERMINAL;
            }

            var now = Instant.now();
            var terminated = latest.toBuilder()
                    .status(WorkflowStatus.TERMINATED)
                    .output(serializer.serialize(new TerminationDetail(reason, "admin")))
                    .completedAt(now)
                    .updatedAt(now)
                    .version(latest.version() + 1)
                    .build();
            try {
                store.updateInstance(terminated);
            } catch (OptimisticLockException e) {
                if (attempt >= TERMINAL_TRANSITION_ATTEMPTS) {
                    // Unlike the workflow's own finalisation — which leaves the
                    // instance recoverable rather than recording an outcome it
                    // could not persist — an unwritable terminate must be
                    // surfaced: the caller has to not-acknowledge so the command
                    // is redelivered, otherwise the operator's action is lost.
                    logger.error("Could not terminate workflow '{}' after {} attempts — "
                                    + "the instance row is being written continuously", workflowId, attempt);
                    throw e;
                }
                logger.debug("Version conflict terminating workflow '{}' (attempt {}) — "
                        + "retrying against a fresh read", workflowId, attempt);
                continue;
            }

            publishLifecycleEvent(terminated, LifecycleEventType.WORKFLOW_TERMINATED, null);
            emit("workflowTerminated", workflowId, () -> observer.workflowTerminated(observed(terminated)));

            // Best-effort local eviction. Only this node's threads can be
            // abandoned; a remote owner converges via the terminal-status guard.
            if (runningWorkflows.containsKey(workflowId)) {
                parkingLot.abandonWorkflow(workflowId, reason);
            }

            logger.info("Terminated workflow '{}' (previous status={}, reason={})",
                    workflowId, latest.status(), reason);
            return TerminateOutcome.TERMINATED;
        }
    }

    // ── Retry ──────────────────────────────────────────────────────────

    /**
     * The outcome of a {@link #retryWorkflow(String, WorkflowRegistration)} call.
     *
     * <p>Every constant is a <em>deterministic</em> outcome: retrying the same
     * command can never turn one into another, so a caller driving retry from
     * an at-least-once transport should acknowledge the message for all of
     * them. Only genuinely transient conditions (an optimistic-lock conflict
     * on the compare-and-set) are signalled as exceptions instead.
     */
    public enum RetryOutcome {

        /** This call cleared the failure and relaunched the workflow. */
        RETRIED,

        /**
         * The workflow was not {@code FAILED} — {@code RUNNING}, a
         * {@code WAITING_*} status, {@code COMPENSATING}, {@code COMPLETED} or
         * {@code TERMINATED}. Nothing was written; retry only applies to a
         * workflow that has actually failed.
         */
        NOT_FAILED,

        /** No instance with this workflow ID exists in this service's store. */
        NOT_FOUND,

        /**
         * The compare-and-set to {@code RUNNING} succeeded, but by the time
         * this call was ready to launch, the workflow was already running on
         * this node (typically the recovery poller won the race). The instance
         * is left {@code RUNNING} — recoverable, not lost.
         */
        ALREADY_RUNNING_LOCALLY,

        /**
         * The compare-and-set to {@code RUNNING} succeeded, but the per-instance
         * distributed lock is held by another node. The instance is left
         * {@code RUNNING} — the recovery poller (on any node) adopts it within
         * one poll interval.
         */
        LOCK_HELD_ELSEWHERE,

        /**
         * The workflow is {@code FAILED}, but its event log carries a
         * {@code COMPENSATION_STARTED} event — its saga already ran
         * compensations before the instance terminated. Nothing was written:
         * relaunching would replay the surviving compensation events at their
         * original, now-stale sequence positions, and if the previously-failed
         * step succeeds this time the forward path collides with them —
         * re-running real compensating side effects, wrongly skipping others,
         * or silently swallowing the run's terminal event as a duplicate. This
         * outcome is a deliberate, acknowledged no-op until a dedicated design
         * (a fresh-run/{@code runId} relaunch, or a compensation-aware sequence
         * rebase) makes retrying a compensated saga safe; see the tracked
         * follow-up issue in {@code docs/open-issues.md}.
         */
        COMPENSATED_NOT_RETRYABLE
    }

    /**
     * Retries a {@code FAILED} workflow: clears its memoized failure, marks it
     * {@code RUNNING} again, and relaunches it exactly like crash recovery.
     *
     * <p>This is the engine side of the admin dashboard's Retry button.
     *
     * <h2>Why the failure memo has to go</h2>
     * <p>Hybrid memoization normally replays a stored outcome instead of
     * re-executing a step — including a stored <em>failure</em>: on replay,
     * the step that exhausted its retry policy re-throws the memoized
     * {@code ACTIVITY_FAILED} event rather than running again. Relaunching a
     * {@code FAILED} workflow without first clearing that memo would therefore
     * replay straight back into the same failure, even after the underlying
     * fault is fixed — a Retry button that always "succeeds" at flipping the
     * row to {@code RUNNING} and then silently fails again. So the very first
     * step, before anything else, is {@link WorkflowStore#deleteFailureEvents}:
     * it deletes exactly the instance's {@code ACTIVITY_FAILED} and
     * {@code WORKFLOW_FAILED} events, leaving every success and compensation
     * memo untouched. The freed sequence also lets the retried run's own
     * terminal event land where {@code WORKFLOW_FAILED} used to sit, instead of
     * colliding with it.
     *
     * <h2>Mechanics — "make it recoverable again, then recover it"</h2>
     * <ol>
     *   <li>Read the instance. Absent → {@link RetryOutcome#NOT_FOUND}. Status
     *       other than {@code FAILED} → {@link RetryOutcome#NOT_FAILED} — an
     *       idempotent no-op, since retry only makes sense for a failure.</li>
     *   <li>Probe the event log for {@code COMPENSATION_STARTED} — if the
     *       saga already ran compensations, return
     *       {@link RetryOutcome#COMPENSATED_NOT_RETRYABLE} without touching
     *       anything; see that constant's Javadoc for why relaunching is
     *       unsafe on this path.</li>
     *   <li>{@link WorkflowStore#deleteFailureEvents} — see above.</li>
     *   <li>A single optimistic compare-and-set: {@code status = RUNNING},
     *       {@code completedAt = null}, {@code version + 1}. Unlike
     *       {@link #terminateWorkflow}, this is <b>one</b> attempt, not a
     *       converge-loop: a conflict here means another writer changed the row
     *       between the read and the write, and
     *       {@link OptimisticLockException} propagates so an at-least-once
     *       transport redelivers the command against fresh state rather than
     *       this call guessing what the fresh state now means.</li>
     *   <li>Relaunch via the same internal path
     *       {@link #resumeWorkflow(WorkflowInstance, Object, Method)} uses
     *       ({@code replaying = true}) — identical to crash recovery: every
     *       memoized event before the failure point replays for free, and the
     *       step that failed (now memo-free) executes live with a fresh
     *       retry-policy budget.</li>
     *   <li>If the local {@code runningWorkflows} guard or the instance lock
     *       refuses the launch ({@link RetryOutcome#ALREADY_RUNNING_LOCALLY} /
     *       {@link RetryOutcome#LOCK_HELD_ELSEWHERE}), the instance is already
     *       {@code RUNNING} — which is recoverable: the recovery poller (on any
     *       node) adopts it within one poll interval, the same as a crash
     *       between steps 3 and 4 heals.</li>
     * </ol>
     *
     * <h2>Semantics caveat — compensated sagas</h2>
     * <p>Retry is guarded off for a workflow whose saga already ran
     * compensations: see {@link RetryOutcome#COMPENSATED_NOT_RETRYABLE}. The
     * compensation events are anchored to the sequence position the failure
     * occupied on the failed run; a retry that lets the previously-failed step
     * succeed this time would run the forward path straight into those stale
     * positions, corrupting the replay rather than safely skipping already-run
     * compensations. Retrying a compensated saga is not yet supported — this
     * method returns the guarded no-op instead of guessing.
     *
     * <h2>Idempotency</h2>
     * <p>Safe under at-least-once redelivery: a second delivery re-reads the
     * row, finds it no longer {@code FAILED} (it is at least {@code RUNNING})
     * and returns {@link RetryOutcome#NOT_FAILED} without touching anything.
     * A delivery that races the first one closely enough to still see
     * {@code FAILED} loses the compare-and-set instead and its
     * {@link OptimisticLockException} propagates for redelivery.
     *
     * <p><b>Thread safety:</b> thread-safe; may be called concurrently from any
     * thread, including several nodes at once.
     *
     * @param workflowId   the business workflow ID to retry
     * @param registration the registration (implementation instance and
     *                     entry-point method) for this workflow's type — the
     *                     same one recovery would use
     * @return what this call did — see {@link RetryOutcome}
     * @throws OptimisticLockException if another writer touched the instance
     *                                 row between the read and the
     *                                 compare-and-set. Transient: the caller
     *                                 should <em>not</em> acknowledge, so the
     *                                 command is redelivered against fresh
     *                                 state.
     */
    public RetryOutcome retryWorkflow(String workflowId, WorkflowRegistration registration) {
        var existing = store.getInstance(workflowId);
        if (existing.isEmpty()) {
            logger.warn("Cannot retry workflow '{}' — no such instance in this service", workflowId);
            return RetryOutcome.NOT_FOUND;
        }
        var latest = existing.get();
        if (latest.status() != WorkflowStatus.FAILED) {
            logger.warn("Workflow '{}' is {} — not retrying (retry only applies to FAILED)",
                    workflowId, latest.status());
            return RetryOutcome.NOT_FAILED;
        }

        // A saga that already compensated leaves its compensation events
        // anchored to the failed run's sequence positions. If the retried run
        // lets the previously-failed step succeed this time, the forward path
        // collides with those stale positions — see COMPENSATED_NOT_RETRYABLE's
        // Javadoc for the full failure mode. Guard it off rather than corrupt
        // the replay; getEvents is the cheapest correct read the store SPI
        // offers (no targeted "by event type" probe exists).
        var alreadyCompensated = store.getEvents(latest.id()).stream()
                .anyMatch(e -> e.eventType() == EventType.COMPENSATION_STARTED);
        if (alreadyCompensated) {
            logger.warn("Workflow '{}' already ran saga compensations — not retrying "
                    + "(retry of a compensated saga is not yet supported; see "
                    + "RetryOutcome.COMPENSATED_NOT_RETRYABLE)", workflowId);
            return RetryOutcome.COMPENSATED_NOT_RETRYABLE;
        }

        // Clear the memoized failure BEFORE the CAS: a crash between this line
        // and the CAS leaves the instance FAILED with its failure memos already
        // gone, which is harmless — deleteFailureEvents is idempotent, and a
        // later retry of the still-FAILED instance simply deletes zero rows and
        // proceeds normally.
        var deleted = store.deleteFailureEvents(latest.id());
        logger.debug("Deleted {} failure memo(s) for workflow '{}' before retry", deleted, workflowId);

        var now = Instant.now();
        var running = latest.toBuilder()
                .status(WorkflowStatus.RUNNING)
                .completedAt(null)
                .updatedAt(now)
                .version(latest.version() + 1)
                .build();
        // Single-attempt CAS — OptimisticLockException propagates rather than
        // being retried here; see the Javadoc above.
        store.updateInstance(running);

        // Recheck the local running-workflow guard right before launching: the
        // CAS just made the instance RUNNING, and in the gap between that write
        // and this line, this node's own recovery poller could have observed
        // the RUNNING row and already launched it (mirrors resumeWorkflow's
        // own guard). The instance is left RUNNING either way — recoverable.
        if (runningWorkflows.containsKey(workflowId)) {
            logger.info("Workflow '{}' is already running locally after the retry CAS — "
                    + "leaving it to the run already in flight", workflowId);
            return RetryOutcome.ALREADY_RUNNING_LOCALLY;
        }

        var launched = launchWorkflow(running, registration.workflowImpl(), registration.workflowMethod(),
                running.input(), true, null);
        if (!launched) {
            logger.info("Workflow '{}' CAS'd to RUNNING but its instance lock is held elsewhere — "
                    + "left for the recovery poller to adopt", workflowId);
            return RetryOutcome.LOCK_HELD_ELSEWHERE;
        }

        publishLifecycleEvent(running, LifecycleEventType.WORKFLOW_RETRIED, null);
        logger.info("Retried workflow '{}' (type={})", workflowId, running.workflowType());
        return RetryOutcome.RETRIED;
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
        timerPollerStarted.set(true);
    }

    // ── Recovery poller ─────────────────────────────────────────────────

    /**
     * Starts the background recovery poller.
     *
     * <p>The poller periodically re-runs {@link #recoverWorkflows(Map)} so
     * that workflows whose owning node has died (instance lock expired) or
     * shut down are adopted without requiring a restart of this node. Every
     * node polls — safety comes from the per-instance distributed lock and
     * the in-JVM running-workflow check, not from leader election.
     *
     * <p>The Spring Boot starter calls this automatically after startup
     * recovery (configurable via {@code maestro.recovery.*}).
     *
     * @param registrations map of workflow type → registration metadata
     * @param pollInterval  interval between recovery cycles (e.g., 60 seconds)
     * @throws IllegalStateException if the poller is already started or the
     *                               executor is shutting down
     */
    public void startRecoveryPoller(Map<String, WorkflowRegistration> registrations, Duration pollInterval) {
        if (shuttingDown.get()) {
            throw new IllegalStateException(
                    "WorkflowExecutor is shutting down — cannot start recovery poller");
        }
        var poller = new RecoveryPoller(this, registrations, serviceName, pollInterval);
        if (!recoveryPoller.compareAndSet(null, poller)) {
            throw new IllegalStateException("Recovery poller already started");
        }
        poller.start();
        recoveryPollerStarted.set(true);
    }

    // ── Shutdown ───────────────────────────────────────────────────────

    /**
     * Gracefully shuts down the executor.
     *
     * <p>Stops accepting new workflows, abandons every parked waiter so those
     * virtual threads exit, waits up to the configured shutdown timeout
     * (default 30 seconds; {@code maestro.shutdown.timeout}) for in-flight
     * workflows to drain, and then stops renewing instance locks.
     *
     * <h2>What a parked workflow sees</h2>
     * <p>A workflow parked on {@code awaitSignal()} or {@code sleep()} has
     * committed no failure, so shutdown does not fail it. Its park throws
     * {@link ExecutorShutdownException}, which the executor tells apart from a
     * workflow failure: the instance keeps the {@code WAITING_SIGNAL} or
     * {@code WAITING_TIMER} status it parked in, no compensation runs, and its
     * instance lock is released as the thread unwinds — so a surviving node,
     * or this one after a restart, recovers it immediately.
     *
     * <p>The same holds if shutdown lands <em>during</em> saga compensation: a
     * compensation action that parks (e.g. one calling {@code sleep()} or
     * {@code awaitSignal()}) is unblocked the same way, {@link SagaManager}
     * lets the exception propagate instead of recording the interrupted step
     * as failed, and the instance is left {@code COMPENSATING} — still an
     * active, recoverable status — for the next node to finish. Because
     * {@link ExecutorShutdownException} extends {@link Error}, ordinary
     * {@code catch (Exception)} code — in a workflow method or a compensation
     * action — cannot intercept it and mistake it for a real failure.
     *
     * <p>Workflows still executing an activity are <em>not</em> interrupted;
     * they drain up to the shutdown timeout. Any that overrun it are left
     * running, and their instance locks expire by TTL.
     */
    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            logger.info("Shutdown already in progress");
            return;
        }

        // Stop the pollers first — no new timers should fire and no new
        // recoveries should launch during shutdown
        var poller = timerPoller.getAndSet(null);
        if (poller != null) {
            poller.stop();
        }
        var recovery = recoveryPoller.getAndSet(null);
        if (recovery != null) {
            recovery.stop();
        }

        logger.info("Shutting down WorkflowExecutor, {} workflow(s) in-flight",
                runningWorkflows.size());

        // Abandon every park so those threads exit promptly. Their workflows
        // keep the WAITING_* status they parked in and stay recoverable —
        // a park abandoned this way is not a workflow failure.
        parkingLot.shutdown();

        // Wait for in-flight workflows with a deadline
        var deadline = Instant.now().plus(shutdownTimeout);
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

        // Stop the lock renewer; locks of overrunning workflows expire via TTL
        instanceLockManager.close();

        // Give the lifecycle publisher a short, bounded window to flush events
        // queued by workflows that just drained, then force it down — a stalled
        // transport must not make shutdown hang.
        lifecycleEventPublisher.shutdown();

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

    /**
     * Returns the number of workflow virtual threads currently parked on this
     * node (sleeping on a durable timer or awaiting a signal).
     *
     * <p>A node-local gauge source: together with {@link #runningCount()} it
     * answers "what is this JVM doing right now" — the starter's
     * observability auto-configuration registers both as gauges.
     *
     * @return the number of parked waiters in this executor's parking lot
     */
    public int parkedCount() {
        return parkingLot.parkedCount();
    }

    /**
     * Returns whether the background timer poller is currently running on
     * this executor.
     *
     * @return {@code true} if {@link #startTimerPoller(Duration, int)} has
     *         been called and the poller has not since stopped (e.g. via
     *         {@link #shutdown()})
     */
    public boolean isTimerPollerRunning() {
        var poller = timerPoller.get();
        return poller != null && poller.isRunning();
    }

    /**
     * Returns whether the background recovery poller is currently running
     * on this executor.
     *
     * @return {@code true} if {@link #startRecoveryPoller(Map, Duration)}
     *         has been called and the poller has not since stopped (e.g.
     *         via {@link #shutdown()})
     */
    public boolean isRecoveryPollerRunning() {
        var poller = recoveryPoller.get();
        return poller != null && poller.isRunning();
    }

    /**
     * Returns whether {@link #startTimerPoller(Duration, int)} has ever been
     * called on this executor.
     *
     * <p>Unlike {@link #isTimerPollerRunning()}, this flag is monotonic — it
     * stays {@code true} even after {@link #shutdown()} stops the poller.
     * Combined with {@link #isTimerPollerRunning()}, a caller (e.g. a health
     * check) can distinguish "not started yet" (still starting up — not a
     * fault) from "started, then stopped running" (a real fault, such as a
     * crashed poller thread).
     *
     * @return {@code true} once the timer poller has been started at least once
     */
    public boolean hasTimerPollerStarted() {
        return timerPollerStarted.get();
    }

    /**
     * Returns whether {@link #startRecoveryPoller(Map, Duration)} has ever
     * been called on this executor. Monotonic — see
     * {@link #hasTimerPollerStarted()} for the startup-vs-fault rationale.
     *
     * @return {@code true} once the recovery poller has been started at least once
     */
    public boolean hasRecoveryPollerStarted() {
        return recoveryPollerStarted.get();
    }

    // ── Internal: workflow launch ──────────────────────────────────────

    private boolean launchWorkflow(
            WorkflowInstance instance,
            Object workflowImpl,
            Method workflowMethod,
            @Nullable JsonNode inputPayload,
            boolean replaying,
            WorkflowInstanceLockManager.@Nullable Acquisition preAcquired
    ) {
        // The per-instance distributed lock guarantees a second node can never
        // concurrently execute this workflow. Fresh starts acquire it in
        // startWorkflow (before createInstance) and pass the result in; resume
        // paths acquire here. Held — renewed — until executeWorkflow's
        // finally, including parked waits and saga compensation.
        var acquisition = preAcquired != null
                ? preAcquired
                : instanceLockManager.tryAcquire(instance.workflowId());
        if (acquisition == WorkflowInstanceLockManager.Acquisition.HELD_ELSEWHERE) {
            if (replaying) {
                logger.info("Workflow '{}' instance lock is held elsewhere — skipping resume",
                        instance.workflowId());
                return false;
            }
            // Fresh start: createInstance just succeeded, so this node is the
            // legitimate owner — a held lock here is a stale-lock anomaly
            logger.warn("Could not acquire instance lock for new workflow '{}' — proceeding; "
                            + "store constraints remain the duplicate-execution guard",
                    instance.workflowId());
        }

        if (replaying) {
            // Close the recovery-query→resume race: the previous owner may
            // have finished the workflow after the recovery snapshot was
            // taken. Run the re-check regardless of lock acquisition — with
            // no lock backend (NO_BACKEND) the snapshot can be just as stale.
            var current = store.getInstance(instance.workflowId());
            if (current.isEmpty() || !current.get().status().isActive()) {
                logger.debug("Workflow '{}' turned terminal before resume — skipping",
                        instance.workflowId());
                if (acquisition == WorkflowInstanceLockManager.Acquisition.ACQUIRED) {
                    instanceLockManager.release(instance.workflowId());
                }
                return false;
            }
        }

        var compensationStack = new CompensationStack();
        var operations = new DefaultWorkflowOperations(
                store, distributedLock, messaging, serializer, parkingLot, compensationStack,
                signalManager, wakeRecheckInterval, observer);

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
        try {
            thread.start();
        } catch (RuntimeException | Error e) {
            runningWorkflows.remove(instance.workflowId());
            instanceLockManager.release(instance.workflowId());
            throw e;
        }
        if (replaying) {
            emit("workflowResumed", instance.workflowId(),
                    () -> observer.workflowResumed(observed(instance)));
        }
        return true;
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

            // Success — finalise, converging with any other writer
            var outputPayload = result != null ? serializer.serialize(result) : null;
            if (transitionToTerminal(ctx, instance, WorkflowStatus.COMPLETED, outputPayload)) {
                // Only the winner of the terminal transition counts the
                // outcome — a converged loser must not double-fire. Emitted
                // BEFORE the event append and the lifecycle publish: winning
                // the transition is what durably makes this run the completer,
                // so nothing after it may decide whether the completion is
                // observed. Contained, so a throwing observer cannot skip the
                // append below or land in catch (Exception) — which would
                // compensate a workflow that just succeeded.
                emit("workflowCompleted", ctx.workflowId(),
                        () -> observer.workflowCompleted(observed(instance)));

                // Append WORKFLOW_COMPLETED event
                appendEvent(ctx, EventType.WORKFLOW_COMPLETED, null, outputPayload);
                publishLifecycleEvent(instance, LifecycleEventType.WORKFLOW_COMPLETED, null);

                logger.info("Workflow '{}' completed successfully", ctx.workflowId());
            } else {
                // F4: the converged LOSER. Another writer finalised the row
                // first, so this run deliberately records nothing — but it did
                // run, on this thread, and a stateful observer still holds
                // per-thread state that only this emission will release.
                emitRunAbandoned(ctx, AbandonReason.CONVERGED);
            }

        } catch (ExecutorShutdownException e) {
            // Not a failure: this node is stopping while the workflow was
            // parked. Its durable state — WAITING_SIGNAL or WAITING_TIMER —
            // is still valid and still recoverable, so nothing is written and
            // no compensation runs. Whichever node starts next picks it up.
            //
            // This catch is deliberately ahead of catch (Exception e) below:
            // ExecutorShutdownException extends Error (see its Javadoc), so
            // the ordering is not load-bearing for the compiler, but it keeps
            // the shutdown path visually first as the case the rest of this
            // method must never treat as a failure.
            handleShutdownSuspension(ctx);
        } catch (WorkflowTerminatedException e) {
            // Also not a failure, and — unlike shutdown — not recoverable
            // either: terminateWorkflow already wrote TERMINATED. Writes
            // nothing and runs no compensation (terminate marks and stops).
            // Ahead of catch (Exception e) for the same readability reason as
            // the shutdown case above.
            handleTermination(ctx, e);
        } catch (DuplicateEventException staleRun) {
            // Issue 18: a DuplicateEventException that reaches this level means
            // "another run owns this workflow's progress — this node's view is
            // stale". It is the store's (workflow_instance_id, sequence_number)
            // dedup guard doing its job in the Issue 11 no-fencing window: this
            // node lost its instance lock (frozen past TTL, partitioned, or a
            // no-lock-backend race), a peer adopted the workflow, and the peer's
            // event at this sequence is already the durable truth. Recording a
            // FAILURE here — as the generic catch below would — durably marks a
            // workflow that SUCCEEDED on the winner as FAILED and compensates
            // completed work. Instead this run STANDS DOWN like the shutdown and
            // termination cases above: write nothing, compensate nothing,
            // release the local run. The winner's durable state governs; if no
            // winner exists (a pure self-conflict), the instance stays active
            // and recovery replays it from the log. MUST stay ahead of
            // catch (Exception e) — DuplicateEventException is a MaestroException.
            handleStaleRunStandDown(ctx, staleRun);
        } catch (Exception e) {
            try {
                handleWorkflowFailure(ctx, instance, e, compensationStack, parallelCompensation);
            } catch (ExecutorShutdownException shutdownDuringCompensation) {
                // Shutdown landed while compensating a genuine failure. The
                // compensation actions that already ran are memoized (they
                // went through the activity proxy); the instance is still
                // COMPENSATING — an active, recoverable status — and no
                // COMPENSATION_STEP_FAILED was recorded for the interrupted
                // step (SagaManager rethrows this instead of recording it).
                // Treat it exactly like a shutdown during a park: leave the
                // durable state alone for the next node to finish.
                handleShutdownSuspension(ctx);
            } catch (WorkflowTerminatedException terminatedDuringCompensation) {
                // Terminate landed while compensating. The remaining
                // compensations do not run — that is terminate's contract —
                // and the TERMINATED row already stands, so nothing is written.
                handleTermination(ctx, terminatedDuringCompensation);
            } catch (DuplicateEventException staleDuringCompensation) {
                // The duplicate landed while compensating a genuine failure:
                // another runner adopted the workflow and is compensating it
                // (or already finished). SagaManager rethrows instead of
                // recording COMPENSATION_STEP_FAILED, exactly like the shutdown
                // case; the instance stays COMPENSATING — active and
                // recoverable — and the winner's compensation run finishes the
                // job. Nothing is written here.
                handleStaleRunStandDown(ctx, staleDuringCompensation);
            }
        } finally {
            // Remove-then-release: a concurrent recovery attempt in the gap
            // still sees the lock held and skips — retried by the next poll
            runningWorkflows.remove(ctx.workflowId());
            instanceLockManager.release(ctx.workflowId());
            // Drop orphaned wake permits (e.g. duplicate signal deliveries
            // that arrived after the last await) now that the run is over
            parkingLot.clearPending(ctx.workflowId());
            // Drop the sticky terminate poison: this run is over, so a future
            // run of the same workflowId must be able to park again
            parkingLot.clearTerminated(ctx.workflowId());
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
            // ExecutorShutdownException (an Error) must reach executeWorkflow's
            // catch (ExecutorShutdownException e) intact — e.g. a workflow's own
            // try { ... } catch (Exception e) { ... } around awaitSignal()/sleep()
            // doesn't catch it (Error isn't an Exception), reflection unwraps it
            // to this InvocationTargetException, and wrapping it in a
            // RuntimeException here would re-hide it as an ordinary failure.
            if (cause instanceof Error err) throw err;
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause);
        }
    }

    // ── Internal: shutdown suspension ──────────────────────────────────

    /**
     * Records that a workflow's local run ended because this node is shutting
     * down. Deliberately writes nothing: the instance is already in the
     * {@code WAITING_*} (or {@code COMPENSATING}, if shutdown landed mid-saga)
     * status it was interrupted in, which is exactly the state recovery needs
     * to find.
     */
    private void handleShutdownSuspension(WorkflowContext ctx) {
        var status = store.getInstance(ctx.workflowId())
                .map(WorkflowInstance::status)
                .orElse(null);
        logger.info("Workflow '{}' suspended by shutdown while {} — left recoverable",
                ctx.workflowId(), status);
        // RULING 5: the only callback a stateful observer gets on this thread
        // for this path. workflowTerminated/Completed/Failed never fire here.
        emitRunAbandoned(ctx, AbandonReason.SHUTDOWN);
    }

    // ── Internal: termination ──────────────────────────────────────────

    /**
     * Records that a workflow's local run ended because the workflow was
     * terminated. Deliberately writes nothing:
     * {@link #terminateWorkflow(String, String)} already wrote {@code TERMINATED}
     * — on this node or another — and that row is the durable record. No
     * compensation runs either; terminate marks and stops.
     */
    private void handleTermination(WorkflowContext ctx, WorkflowTerminatedException e) {
        logger.info("Workflow '{}' abandoned its local run — terminated{}",
                ctx.workflowId(), e.reason() != null ? " (" + e.reason() + ")" : "");
        // RULING 5. Note workflowTerminated already fired — on the OPERATOR's
        // thread, inside terminateWorkflow — so it cannot release anything this
        // workflow thread holds. This emission is what closes that gap.
        emitRunAbandoned(ctx, AbandonReason.TERMINATED);
    }

    // ── Internal: stale-run stand-down (Issue 18) ──────────────────────

    /**
     * Records that a workflow's local run stood down because its event append
     * collided with a concurrent runner's already-persisted event (the
     * {@code (workflow_instance_id, sequence_number)} unique guard — see
     * {@link DuplicateEventException}). Deliberately writes nothing: the other
     * runner's durable state — its events, its terminal outcome, its
     * compensation progress — is the record. If no other runner exists, the
     * instance is still active and recovery replays it from the log.
     *
     * <p>This is the executor-level meaning only. Inside
     * {@code ActivityInvocationHandler} the same exception keeps its
     * adopt-the-stored-result semantics (the memoized payload at that sequence
     * is returned); only an append that escapes to this level means the whole
     * local run is stale.
     */
    private void handleStaleRunStandDown(WorkflowContext ctx, DuplicateEventException e) {
        var status = store.getInstance(ctx.workflowId())
                .map(WorkflowInstance::status)
                .orElse(null);
        logger.warn("Workflow '{}' stood down: event append at sequence {} collided with a "
                        + "concurrent runner (instance status now {}) — no failure recorded, "
                        + "no compensation run; the concurrent runner's durable state governs",
                ctx.workflowId(), e.sequenceNumber(), status);
        emit("standDown", ctx.workflowId(), () ->
                observer.standDown(StandDownReason.STALE_RUN, ctx.workflowId(),
                        "append collision at sequence " + e.sequenceNumber()));
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
            if (transitionToTerminal(ctx, instance, WorkflowStatus.FAILED, errorPayload)) {
                // Winner-only, and emitted before the append for the same
                // reason as the COMPLETED transition above: the durable
                // outcome is already written, so nothing that follows may
                // decide whether it is observed.
                emit("workflowFailed", ctx.workflowId(), () ->
                        observer.workflowFailed(observed(instance), exception.getClass().getName()));

                appendEvent(ctx, EventType.WORKFLOW_FAILED, null, errorPayload);
                publishLifecycleEvent(instance, LifecycleEventType.WORKFLOW_FAILED, null);
            } else {
                // F4: converged loser on the failure path — same shape as the
                // COMPLETED case above.
                emitRunAbandoned(ctx, AbandonReason.CONVERGED);
            }
        } catch (Exception updateError) {
            logger.error("Failed to update workflow '{}' status to FAILED",
                    ctx.workflowId(), updateError);
            // F4: the terminal write itself failed, so no terminal callback
            // fired and the run is over on this thread regardless. Emitted
            // inside the catch so it cannot be skipped by the failure it
            // reports on.
            emitRunAbandoned(ctx, AbandonReason.TERMINAL_WRITE_FAILED);
        }
    }

    // ── Internal: terminal transition ──────────────────────────────────

    /**
     * Writes a workflow's terminal status, converging with any other writer
     * that touched the instance row first.
     *
     * <p>The instance is read, stamped with {@code version + 1} and written.
     * Anything that writes the row in between invalidates that version:
     * another node running the same workflow (the documented no-lock-backend
     * degradation, a lock lost mid-run, or a stale lock on a fresh start), or
     * simply another status transition on this node. Before this converged,
     * the resulting {@link io.b2mash.maestro.core.exception.OptimisticLockException}
     * escaped into the caller's failure handling and a workflow that had
     * <em>succeeded</em> was recorded as {@code FAILED} — with the conflict
     * message as its output, contradicting its own {@code WORKFLOW_COMPLETED}
     * event, and running a saga's compensations after a successful run.
     *
     * <p>A persistence conflict is not a workflow outcome. It is resolved by
     * retrying against a fresh read, and by standing down when another runner
     * has already reached a terminal state — the event log at that sequence is
     * then the durable truth and must not be contradicted.
     *
     * @param ctx      the workflow context (supplies the final event sequence)
     * @param fallback the instance to fall back on if the row cannot be re-read
     * @param status   the terminal status to write
     * @param output   the output or error payload to record
     * @return {@code true} if this call wrote the transition and its caller
     *         should record the matching event; {@code false} if another runner
     *         had already finalised the workflow, or if the conflict persisted
     *         across every attempt — in which case the instance is deliberately
     *         left non-terminal and recoverable rather than being recorded with
     *         an outcome this run could not persist
     */
    private boolean transitionToTerminal(
            WorkflowContext ctx, WorkflowInstance fallback,
            WorkflowStatus status, @Nullable JsonNode output
    ) {
        for (var attempt = 1; ; attempt++) {
            var latest = store.getInstance(ctx.workflowId()).orElse(fallback);
            if (latest.status().isTerminal()) {
                logger.warn("Workflow '{}' is already {} — another runner finalised it first; "
                                + "not overwriting with {}",
                        ctx.workflowId(), latest.status(), status);
                return false;
            }
            var now = Instant.now();
            var updated = latest.toBuilder()
                    .status(status)
                    .output(output)
                    .completedAt(now)
                    .updatedAt(now)
                    .eventSequence(ctx.currentSequence())
                    .version(latest.version() + 1)
                    .build();
            try {
                store.updateInstance(updated);
                return true;
            } catch (OptimisticLockException e) {
                if (attempt >= TERMINAL_TRANSITION_ATTEMPTS) {
                    // Give up on the write, but do NOT turn a persistence
                    // conflict into a workflow outcome. Propagating here would
                    // land in the caller's failure handling and record a run
                    // that SUCCEEDED as FAILED — the very bug this method
                    // exists to prevent. Leaving the instance in its current,
                    // non-terminal status keeps it recoverable: the next node
                    // to pick it up replays the memoized steps and finalises it.
                    logger.error("Could not finalise workflow '{}' as {} after {} attempts — "
                                    + "the instance row is being written continuously. Leaving it "
                                    + "non-terminal for recovery to finalise.",
                            ctx.workflowId(), status, attempt);
                    return false;
                }
                logger.debug("Version conflict finalising workflow '{}' as {} (attempt {}) — "
                                + "retrying against a fresh read",
                        ctx.workflowId(), status, attempt);
            }
        }
    }

    // ── Internal: event and lifecycle helpers ──────────────────────────

    /** Builds the identity-only observation record for an instance. */
    private WorkflowInfo observed(WorkflowInstance instance) {
        return new WorkflowInfo(instance.workflowId(), instance.workflowType(), serviceName);
    }

    /**
     * Builds the identity-only observation record for the run currently
     * executing on this thread. Used by the abandonment emissions, which run
     * after the instance row may already have been rewritten by another node —
     * the context is the reliable identity there.
     */
    private WorkflowInfo observed(WorkflowContext ctx) {
        return new WorkflowInfo(ctx.workflowId(), ctx.workflowType(), serviceName);
    }

    /**
     * Emits {@link EngineObserver#runAbandoned} for a local run that ended on
     * the workflow thread without recording an outcome (design §11, RULING 5).
     *
     * <p>Contained like every other emission here: a throwing observer must not
     * turn a routine shutdown into an escaping exception on the unwind path.
     */
    private void emitRunAbandoned(WorkflowContext ctx, AbandonReason reason) {
        emit("runAbandoned", ctx.workflowId(),
                () -> observer.runAbandoned(observed(ctx), reason));
    }

    /**
     * Invokes one observer callback, containing a misbehaving observer.
     *
     * <p>Since coordinator Ruling 4, containment is structural at the seam:
     * {@link io.b2mash.maestro.core.observe.CompositeEngineObserver#of} always
     * wraps, so a registered adapter's {@code RuntimeException} is contained
     * before it ever reaches here. This guard stays as depth — the
     * constructors accept <em>any</em> {@code EngineObserver}, so nothing
     * forces an embedder or a test wiring the engine by hand through
     * {@code of(...)} — and because every emission in this class sits on a
     * durable path where an escaping {@code RuntimeException} would be read as
     * something else entirely: a workflow failure (and its compensations), an
     * aborted recovery pass, a leaked instance lock, or a terminated workflow
     * whose local thread never gets abandoned.
     *
     * <p>{@code RuntimeException} only: {@code Error}s — the engine's
     * control-flow signals {@code ExecutorShutdownException} and
     * {@code WorkflowTerminatedException} — always propagate, exactly as the
     * composite does it.
     *
     * @param callback   the callback name, for the containment log line
     * @param workflowId the workflow the callback described, or {@code null}
     *                   for node-scoped callbacks such as {@code recoveryPass}
     * @param emission   the observer invocation
     */
    private void emit(String callback, @Nullable String workflowId, Runnable emission) {
        try {
            emission.run();
        } catch (RuntimeException e) {
            logger.warn("EngineObserver.{} threw for workflow '{}' — ignoring: {}",
                    callback, workflowId, e.toString());
        }
    }

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

    /**
     * Builds and submits a lifecycle event for off-thread publishing.
     *
     * <p>Never runs {@link WorkflowMessaging#publishLifecycleEvent} on the
     * calling thread: that call is free to block (Kafka's producer, for
     * example, blocks synchronously fetching metadata for a missing topic, up
     * to {@code max.block.ms}), and the calling thread here is a workflow's
     * own virtual thread — most notably during {@code startWorkflow} itself.
     * The actual publish, and its failure handling, happens on
     * {@link #lifecycleEventPublisher}; see {@link LifecycleEventPublisher}
     * for the backpressure and shutdown contract.
     */
    private void publishLifecycleEvent(
            WorkflowInstance instance, LifecycleEventType eventType,
            @Nullable String stepName
    ) {
        if (messaging == null || !lifecycleEventsEnabled) return;
        var event = new WorkflowLifecycleEvent(
                instance.id(),
                instance.workflowId(),
                instance.workflowType(),
                serviceName,
                instance.taskQueue(),
                eventType,
                stepName,
                null,
                Instant.now()
        );
        lifecycleEventPublisher.submit(() -> {
            try {
                messaging.publishLifecycleEvent(event);
            } catch (Exception e) {
                // SPI contract: lifecycle event failures must not interrupt workflow execution
                logger.warn("Failed to publish {} lifecycle event for workflow '{}'",
                        eventType, instance.workflowId(), e);
            }
        });
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

    /**
     * Termination detail stored in the workflow output when an admin terminates
     * it — the audit trail for why an operator stopped this workflow.
     */
    private record TerminationDetail(@Nullable String reason, String terminatedBy) {}
}
