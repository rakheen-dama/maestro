package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.Saga;
import io.b2mash.maestro.core.context.RunCancellation;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.context.WorkflowContextInternals;
import io.b2mash.maestro.core.context.WorkflowMDC;
import io.b2mash.maestro.core.exception.CompensationException;
import io.b2mash.maestro.core.exception.DuplicateEventException;
import io.b2mash.maestro.core.exception.FencedWriteException;
import io.b2mash.maestro.core.exception.ForeignServiceRowsException;
import io.b2mash.maestro.core.exception.OptimisticLockException;
import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.exception.MaestroControlFlowError;
import io.b2mash.maestro.core.exception.MaestroException;
import io.b2mash.maestro.core.exception.NodeErrors;
import io.b2mash.maestro.core.exception.QueryNotDefinedException;
import io.b2mash.maestro.core.exception.SequenceSpaceExhaustedException;
import io.b2mash.maestro.core.exception.StaleRunException;
import io.b2mash.maestro.core.exception.StoreUnavailableException;
import io.b2mash.maestro.core.exception.UnknownWorkflowHistoryException;
import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
import io.b2mash.maestro.core.exception.WorkflowExecutionException;
import io.b2mash.maestro.core.exception.WorkflowInstantiationException;
import io.b2mash.maestro.core.exception.WorkflowNotQueryableException;
import io.b2mash.maestro.core.exception.WorkflowNotFoundException;
import io.b2mash.maestro.core.exception.WorkflowTerminatedException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowFailureDetail;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTerminationDetail;
import io.b2mash.maestro.core.observe.AbandonReason;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.observe.StandDownReason;
import io.b2mash.maestro.core.observe.WorkflowInfo;
import io.b2mash.maestro.core.saga.CompensationStack;
import io.b2mash.maestro.core.saga.SagaManager;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.RecoveryCursor;
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
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Central orchestrator that runs workflow methods on Java virtual threads.
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
 *       node happens to own it. ("Without compensation" means terminate never
 *       starts one: {@code SagaManager} re-checks the instance before entering
 *       compensation and before each sequential entry, so a terminate observed
 *       at any of those points stops the unwind. The only compensation work
 *       that can still complete after a terminate is the entry (or parallel
 *       branch set) already executing — {@code docs/open-issues.md}
 *       Issue 22.)</li>
 *   <li><b>Shutdown:</b> Stops accepting new work, waits for in-flight
 *       workflows to drain, and leaves parked workflows in their
 *       {@code WAITING_*} status for another node to recover — a graceful
 *       stop is never a workflow failure.</li>
 * </ul>
 *
 * <h2>Construction</h2>
 * <p>Build an executor with {@link #builder(WorkflowStore, PayloadSerializer, String)}:
 * the store, serializer and service name are required, and so is a
 * {@link DistributedLock} ({@link Builder#distributedLock(DistributedLock)}):
 * {@link Builder#build()} fails without one. Every other setting has a
 * default documented on its {@link Builder} setter.
 * <pre>{@code
 * WorkflowExecutor executor = WorkflowExecutor.builder(store, serializer, "order-service")
 *         .distributedLock(lock)
 *         .build();
 * }</pre>
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
     * Default deadline for the whole of {@link #shutdown()} — 20 seconds,
     * below Spring Boot's 30 s {@code spring.lifecycle.timeout-per-shutdown-phase}
     * and Kubernetes' 30 s grace period, so the rest of a host's shutdown
     * still fits — used when no explicit {@code shutdownTimeout} is set with
     * {@link Builder#shutdownTimeout}. Corresponds to
     * {@code maestro.shutdown.timeout} in the Spring Boot starter.
     */
    static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(20);

    /**
     * How many times a terminal-status write is retried against a fresh read
     * before the conflict is treated as unrecoverable. Conflicts come from
     * other writers of the same row, so a handful of attempts is ample; an
     * unbounded loop would hide a pathological writer.
     */
    private static final int TERMINAL_TRANSITION_ATTEMPTS = 5;

    /**
     * The message {@link Builder#build()} fails with when no
     * {@link DistributedLock} was set (pilot readiness P3: the lock is
     * mandatory). Package-private so the wording is pinned.
     */
    static final String MISSING_LOCK_MESSAGE = "A DistributedLock is required: Maestro does not run without one. "
            + "Set WorkflowExecutor.Builder.distributedLock(...) — PostgresDistributedLock from "
            + "maestro-store-postgres in production (the Spring Boot starter wires it from the Postgres store's "
            + "DataSource), maestro-test's InMemoryDistributedLock in tests.";

    private final WorkflowStore store;
    private final DistributedLock distributedLock;
    private final @Nullable WorkflowMessaging messaging;
    private final @Nullable SignalNotifier signalNotifier;
    private final PayloadSerializer serializer;
    private final String serviceName;
    private final ParkingLot parkingLot;
    private final TaskQueueGovernor taskQueueGovernor;
    private final SignalManager signalManager;
    private final SagaManager sagaManager;
    private final QueryRegistry queryRegistry;
    private final WorkflowInstanceLockManager instanceLockManager;
    private final boolean lifecycleEventsEnabled;
    private final Duration shutdownTimeout;
    private final Duration wakeRecheckInterval;
    private final EngineObserver observer;
    private final InstantSource instantSource;
    private final LifecycleEventPublisher lifecycleEventPublisher;
    private final LifecycleEventSink lifecycleEventSink;
    private final ConcurrentHashMap<String, RunningWorkflow> runningWorkflows;

    /**
     * What the shutdown drain waits for: every registered run, keyed by its
     * thread, from just before it enters {@code runningWorkflows} until its
     * instance lock has been released. A run leaves {@code runningWorkflows}
     * <em>before</em> its release (remove-then-release, which the wake-permit
     * cleanup relies on), so a drain over that map alone could return while a
     * finished run still held its lock. An entry leaves here only after the
     * release was attempted, whether it succeeded or threw: at the end of the
     * run thread's body (the release, on every path a started run takes, is
     * attempted on that thread before it), or, for a launch that failed
     * before starting the thread, in the launch's cleanup after its release.
     *
     * <p>Thread-safe: each entry is put once by its launch and removed once by
     * the run's thread or by the failed launch; the drain reads it weakly
     * consistently. One put and one remove per run, keyed by thread identity.
     */
    private final ConcurrentHashMap<Thread, RunningWorkflow> unreleasedRuns = new ConcurrentHashMap<>();

    /**
     * Test seam: runs on the launching thread once a launch has been admitted
     * — the instance lock acquired and, on a resume, the instance adopted —
     * before the run is set up. A test makes it throw to show that a failure
     * between admission and the run's thread start still releases the lock.
     * Package-private; production never assigns it.
     */
    volatile Runnable afterLaunchAdmission = () -> { };

    /**
     * Test seam: runs on the run's own thread before the run body — after
     * the thread started, before {@code executeWorkflow} is entered. A test
     * makes it throw to show that a run whose thread dies there still
     * releases its instance lock, its running entry and its slot reservation.
     * Package-private; production never assigns it.
     */
    volatile Runnable beforeRunBody = () -> { };

    /** What the most recent full gated recovery pass deferred; see {@link #recoveryDeferredCount()}. */
    private volatile int recoveryDeferred;

    /**
     * Test seam: runs on the shutting-down thread just before the drain
     * (D-2's step 5). A test makes it throw to show that a second
     * {@link #shutdown()} caller is released when the first call fails.
     * Package-private; production never assigns it.
     */
    volatile Runnable beforeShutdownDrain = () -> { };

    /**
     * Test seam: runs on the launching thread after a run has been registered
     * in {@code runningWorkflows} and before its thread is started. A test
     * holds it to put a registered-but-unstarted run in front of a shutdown.
     * Package-private; production never assigns it.
     */
    volatile Runnable beforeRunThreadStart = () -> { };

    /**
     * Test seam: runs on the launching thread in a failed launch's cleanup,
     * after the run is unregistered and before its wake permits are cleared.
     * A test makes it throw to stand for a cleanup step that fails.
     * Package-private; production never assigns it.
     */
    volatile Runnable duringFailedLaunchCleanup = () -> { };
    private final AtomicBoolean shuttingDown;
    private final AtomicReference<TimerPoller> timerPoller = new AtomicReference<>();
    private final AtomicReference<RecoveryPoller> recoveryPoller = new AtomicReference<>();
    private final AtomicReference<OneShotRecovery> oneShotRecovery = new AtomicReference<>();
    private final AtomicBoolean timerPollerStarted = new AtomicBoolean(false);
    private final AtomicBoolean recoveryPollerStarted = new AtomicBoolean(false);
    private final AtomicReference<SignalJanitor> signalJanitor = new AtomicReference<>();
    private volatile @Nullable SignalJanitorSettings signalJanitorSettings;
    private final AtomicReference<RetentionSweeper> retentionSweeper = new AtomicReference<>();
    private volatile @Nullable RetentionSettings retentionSettings;

    /**
     * How long a second {@link #shutdown()} caller waits beyond what is left
     * of the first call's deadline (D-5): the first call's last steps and its
     * summary line run after the deadline's last bounded wait.
     */
    static final Duration SECOND_CALLER_GRACE = Duration.ofSeconds(2);

    /** The running shutdown's deadline, published for a second caller; {@code null} until then. */
    private volatile @Nullable ShutdownDeadline shutdownDeadline;
    /** Counted down when the first {@link #shutdown()} call has finished, however it ended. */
    private final CountDownLatch shutdownFinished = new CountDownLatch(1);
    private final Duration instanceLockTtl;

    /**
     * The single build path: {@link Builder#build()} ends here, and it is
     * the only way to obtain an executor. Validates the builder's values — the three
     * durations first, then the reference options, then the mandatory
     * {@link DistributedLock} — before building any component from them.
     *
     * @param builder the settings; read once, here, and never retained
     * @throws IllegalArgumentException if {@code instanceLockTtl}, {@code shutdownTimeout},
     *                                  or {@code wakeRecheckInterval} is {@code null}, zero,
     *                                  or negative
     * @throws NullPointerException     if {@code lockKeyPrefix}, {@code observer},
     *                                  {@code taskQueueLimits} or {@code instantSource}
     *                                  is {@code null}, or no {@code distributedLock} was set
     *                                  (message {@link #MISSING_LOCK_MESSAGE})
     */
    private WorkflowExecutor(Builder builder) {
        var store = builder.store;
        var distributedLock = builder.distributedLock;
        var messaging = builder.messaging;
        var signalNotifier = builder.signalNotifier;
        var serializer = builder.serializer;
        var serviceName = builder.serviceName;
        var lockKeyPrefix = builder.lockKeyPrefix;
        var instanceLockTtl = builder.instanceLockTtl;
        var lifecycleEventsEnabled = builder.lifecycleEventsEnabled;
        var shutdownTimeout = builder.shutdownTimeout;
        var wakeRecheckInterval = builder.wakeRecheckInterval;
        var observer = builder.observer;
        var taskQueueLimits = builder.taskQueueLimits;
        var defaultTaskQueueLimits = builder.defaultTaskQueueLimits;
        var instantSource = builder.instantSource;
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
        Objects.requireNonNull(lockKeyPrefix, "lockKeyPrefix");
        Objects.requireNonNull(observer, "observer");
        Objects.requireNonNull(taskQueueLimits, "taskQueueLimits");
        this.instantSource = Objects.requireNonNull(instantSource, "instantSource");
        Objects.requireNonNull(distributedLock, MISSING_LOCK_MESSAGE);
        // Guarded once, here, before any component below is built from it, so
        // every store call this executor and its components make on a run's
        // behalf — memo lookups, outcome appends, status writes, signal and
        // timer writes — gets the same store-fault treatment: the idempotent
        // reads/appends are retried briefly, and a final StoreUnavailableException
        // latches the run's STORE_UNAVAILABLE stand-down (see StoreFaultGuard).
        // Idempotent: an already-guarded store is used as is.
        this.store = StoreFaultGuard.wrap(store);
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
        // Assigned before the lot, which reads it: a wake that finds no parked
        // waiter stores a permit only for a workflow running on this node (3.2).
        this.runningWorkflows = new ConcurrentHashMap<>();
        this.parkingLot = new ParkingLot(runningWorkflows::containsKey);
        this.taskQueueGovernor = taskQueueLimits.isEmpty() && defaultTaskQueueLimits == null
                ? TaskQueueGovernor.UNBOUNDED
                : new TaskQueueGovernor(taskQueueLimits, defaultTaskQueueLimits);
        this.lifecycleEventsEnabled = lifecycleEventsEnabled;
        // Built before the components below so they can share the one off-thread
        // sink: every lifecycle event this executor or its components produce is
        // handed to the publisher's bounded queue, never published on a
        // workflow's own thread — see LifecycleEventSink.
        this.lifecycleEventPublisher = new LifecycleEventPublisher(serviceName);
        this.lifecycleEventSink = buildLifecycleEventSink(this.messaging, lifecycleEventsEnabled);
        this.signalManager = new SignalManager(
                this.store, this.lifecycleEventSink, signalNotifier, serializer, parkingLot, wakeRecheckInterval,
                observer, taskQueueGovernor, instantSource);
        this.sagaManager = new SagaManager(this.store, serializer, serviceName, observer, this.lifecycleEventSink);
        this.instanceLockManager = new WorkflowInstanceLockManager(
                distributedLock, serviceName, lockKeyPrefix, instanceLockTtl,
                instanceLockTtl.dividedBy(3), observer);
        this.queryRegistry = new QueryRegistry();
        this.shutdownTimeout = shutdownTimeout;
        this.instanceLockTtl = instanceLockTtl;
        this.wakeRecheckInterval = wakeRecheckInterval;
        this.shuttingDown = new AtomicBoolean(false);
        // Proactive stand-down (Issue 11): a run whose instance lock was lost
        // is marked stale now, so it stops at its next stop point — live
        // activity, sleep(), awaitSignal(), terminal write — instead of first
        // attempting a write the store's lease fence rejects anyway. The fence
        // is the guarantee; this only shortens the window.
        this.instanceLockManager.onLockLost(workflowId -> {
            var running = runningWorkflows.get(workflowId);
            if (running != null) {
                running.cancellation().requestStale("instance lock lost");
            }
        });
    }

    /**
     * Starts building a workflow executor. Every setting other than the three
     * required here has a default, documented on its {@link Builder} setter.
     *
     * @param store       workflow store for persistence
     * @param serializer  Jackson serializer for payloads
     * @param serviceName the name of the owning service
     * @return a new builder holding the defaults
     * @throws NullPointerException if any argument is {@code null}
     */
    public static Builder builder(WorkflowStore store, PayloadSerializer serializer, String serviceName) {
        return new Builder(store, serializer, serviceName);
    }

    /**
     * Collects the settings of a {@link WorkflowExecutor}; obtain one from
     * {@link WorkflowExecutor#builder(WorkflowStore, PayloadSerializer, String)}.
     *
     * <p>Each setter stores its value and nothing else; {@link #build()} runs
     * every check, so an invalid value fails there, not at the setter.
     *
     * <h2>Thread Safety</h2>
     * <p>A builder is <b>not</b> thread-safe: configure it and call
     * {@link #build()} on one thread. It may be reused — each {@code build()}
     * returns a new executor from the values set at that moment, and later
     * changes to the builder do not reach an executor already built. The
     * built {@link WorkflowExecutor} is thread-safe as its class documents.
     */
    public static final class Builder {

        final WorkflowStore store;
        final PayloadSerializer serializer;
        final String serviceName;
        @Nullable DistributedLock distributedLock;
        @Nullable WorkflowMessaging messaging;
        @Nullable SignalNotifier signalNotifier;
        String lockKeyPrefix = WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX;
        Duration instanceLockTtl = WorkflowInstanceLockManager.DEFAULT_LOCK_TTL;
        boolean lifecycleEventsEnabled = true;
        Duration shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;
        Duration wakeRecheckInterval = SignalManager.DEFAULT_WAKE_RECHECK_INTERVAL;
        EngineObserver observer = EngineObserver.NOOP;
        Map<String, TaskQueueLimits> taskQueueLimits = Map.of();
        @Nullable TaskQueueLimits defaultTaskQueueLimits;
        InstantSource instantSource = InstantSource.system();

        private Builder(WorkflowStore store, PayloadSerializer serializer, String serviceName) {
            this.store = Objects.requireNonNull(store, "store");
            this.serializer = Objects.requireNonNull(serializer, "serializer");
            this.serviceName = Objects.requireNonNull(serviceName, "serviceName");
        }

        /**
         * Sets the distributed lock backend: the per-workflow instance lock
         * and the timer-poller and retention leader elections. <b>Required</b>
         * — there is no default and no lock-less mode (pilot readiness P3):
         * {@link #build()} fails with a {@link NullPointerException} carrying
         * {@link WorkflowExecutor#MISSING_LOCK_MESSAGE} when none is set.
         * Production uses {@code PostgresDistributedLock} from
         * {@code maestro-store-postgres}; tests use {@code maestro-test}'s
         * {@code InMemoryDistributedLock}.
         *
         * @param distributedLock the distributed lock backend
         * @return this builder
         */
        public Builder distributedLock(DistributedLock distributedLock) {
            this.distributedLock = distributedLock;
            return this;
        }

        /**
         * Sets the messaging lifecycle events are published through. Default:
         * {@code null} — no messaging.
         *
         * @param messaging optional messaging for lifecycle events
         * @return this builder
         */
        public Builder messaging(@Nullable WorkflowMessaging messaging) {
            this.messaging = messaging;
            return this;
        }

        /**
         * Sets the cross-instance signal notifier. Default: {@code null} — no
         * cross-instance notification.
         *
         * @param signalNotifier optional cross-instance signal notification
         * @return this builder
         */
        public Builder signalNotifier(@Nullable SignalNotifier signalNotifier) {
            this.signalNotifier = signalNotifier;
            return this;
        }

        /**
         * Sets the prefix for distributed lock keys. Default:
         * {@code maestro:lock:}. {@link #build()} rejects {@code null} with a
         * {@link NullPointerException}.
         *
         * @param lockKeyPrefix prefix for distributed lock keys
         * @return this builder
         */
        public Builder lockKeyPrefix(String lockKeyPrefix) {
            this.lockKeyPrefix = lockKeyPrefix;
            return this;
        }

        /**
         * Sets the TTL for the per-workflow instance lock, renewed at one
         * third of this interval. Default: 30 seconds. {@link #build()}
         * rejects {@code null}, zero or negative with an
         * {@link IllegalArgumentException}.
         *
         * @param instanceLockTtl TTL for the per-workflow instance lock — must be
         *                        strictly positive
         * @return this builder
         */
        public Builder instanceLockTtl(Duration instanceLockTtl) {
            this.instanceLockTtl = instanceLockTtl;
            return this;
        }

        /**
         * Sets whether lifecycle events are published at all, independent of
         * whether {@link #messaging(WorkflowMessaging)} is configured.
         * Corresponds to {@code maestro.admin.events.enabled} in the Spring
         * Boot starter. Default here: {@code true}. The starter's
         * {@code maestro.admin.events.enabled} defaults to {@code false} and is
         * passed in explicitly.
         *
         * @param lifecycleEventsEnabled whether {@code WORKFLOW_*} lifecycle events are
         *                               published at all
         * @return this builder
         */
        public Builder lifecycleEventsEnabled(boolean lifecycleEventsEnabled) {
            this.lifecycleEventsEnabled = lifecycleEventsEnabled;
            return this;
        }

        /**
         * Sets the deadline for the whole engine shutdown — the drain, the
         * background loops, the lock renewer and the lifecycle-event flush —
         * not only the drain: {@link WorkflowExecutor#shutdown()} returns
         * within it. Corresponds to {@code maestro.shutdown.timeout}.
         * Default: 20 seconds. {@link #build()} rejects {@code null}, zero or
         * negative with an {@link IllegalArgumentException}.
         *
         * @param shutdownTimeout the deadline for the whole of
         *                        {@link WorkflowExecutor#shutdown()} — must be strictly positive
         * @return this builder
         */
        public Builder shutdownTimeout(Duration shutdownTimeout) {
            this.shutdownTimeout = shutdownTimeout;
            return this;
        }

        /**
         * Sets how often a parked {@code awaitSignal()} or {@code sleep()}
         * re-checks the store for a wake that happened without a local
         * unpark — a signal persisted on another node, or a timer
         * fired/cancelled by a remote timer-poller leader. Bounds cross-node
         * signal, timer-fire and timer-cancel latency for a parked workflow.
         * Corresponds to {@code maestro.signal.wake-recheck-interval}.
         * Default: 30 seconds.
         * {@link #build()} rejects {@code null}, zero or negative with an
         * {@link IllegalArgumentException}.
         *
         * @param wakeRecheckInterval how often a parked run re-checks the store —
         *                            must be strictly positive
         * @return this builder
         */
        public Builder wakeRecheckInterval(Duration wakeRecheckInterval) {
            this.wakeRecheckInterval = wakeRecheckInterval;
            return this;
        }

        /**
         * Sets the engine observation seam, invoked synchronously at
         * execution boundaries and handed to every component the executor
         * builds — see {@link EngineObserver} for the callback and
         * thread-safety contract. Default: {@link EngineObserver#NOOP}, which
         * is also what to pass when no observation is wanted.
         * {@link #build()} rejects {@code null} with a
         * {@link NullPointerException}.
         *
         * @param observer engine observation seam; never {@code null}
         * @return this builder
         */
        public Builder observer(EngineObserver observer) {
            this.observer = observer;
            return this;
        }

        /**
         * Sets the per-node concurrency limits keyed by task-queue name,
         * enforced by the executor's {@link WorkflowExecutor#taskQueueGovernor()}
         * (F8 / Issue 25); a queue not listed gets
         * {@link #defaultTaskQueueLimits(TaskQueueLimits)}. Corresponds to
         * {@code maestro.worker.task-queues}.
         * Default: an empty map. {@link #build()} rejects {@code null} with a
         * {@link NullPointerException}.
         *
         * @param taskQueueLimits per-node concurrency limits keyed by task-queue name
         * @return this builder
         */
        public Builder taskQueueLimits(Map<String, TaskQueueLimits> taskQueueLimits) {
            this.taskQueueLimits = taskQueueLimits;
            return this;
        }

        /**
         * Sets the per-node limits of each task queue not in
         * {@link #taskQueueLimits(Map)}: each such queue gets its own pools
         * of this size (see
         * {@link TaskQueueGovernor#TaskQueueGovernor(Map, TaskQueueLimits)}).
         * The Spring Boot starter passes
         * {@code maestro.worker.default-concurrency} /
         * {@code default-activity-concurrency}. Default: {@code null} —
         * unlisted queues are unbounded.
         *
         * @param defaultTaskQueueLimits per-node limits of each task queue not in
         *                               {@code taskQueueLimits}; {@code null} leaves
         *                               them unbounded
         * @return this builder
         */
        public Builder defaultTaskQueueLimits(@Nullable TaskQueueLimits defaultTaskQueueLimits) {
            this.defaultTaskQueueLimits = defaultTaskQueueLimits;
            return this;
        }

        /**
         * Sets the clock a live {@code sleep()} arms its timer's
         * {@code fireAt} from, a live {@code awaitSignal()} arms and decides
         * its deadline on, and {@code currentTime()} records — see
         * {@link WorkflowExecutor#instantSource()} (R59). Default:
         * {@link InstantSource#system()}. {@link #build()} rejects {@code null}
         * with a {@link NullPointerException}.
         *
         * <p>This is the seam {@code TestWorkflowEnvironment} uses to put
         * {@code sleep()} timers and {@code awaitSignal()} deadlines on its
         * controllable test clock; production code has no reason to set it.
         * Keep {@link InstantSource#system()} in production: the timer
         * poller ({@link WorkflowExecutor#startTimerPoller}) never reads this
         * source. It asks the store for the due rows
         * ({@code WorkflowStore.getDueTimers(int)}), and the store decides
         * against its own clock: on Postgres the database's {@code now()};
         * a store that does not override {@code getDueTimers(int)}, the
         * caller's system clock. So a non-system source is only for a test
         * harness that fires timers itself (as
         * {@code TestWorkflowEnvironment.advanceTime} does) and never starts
         * the poller.
         *
         * @param instantSource the clock durable deadlines are armed on
         * @return this builder
         */
        public Builder instantSource(InstantSource instantSource) {
            this.instantSource = instantSource;
            return this;
        }

        /**
         * Builds a new executor from the values set at this moment.
         *
         * @return a new workflow executor
         * @throws IllegalArgumentException if {@code instanceLockTtl}, {@code shutdownTimeout},
         *                                  or {@code wakeRecheckInterval} is {@code null}, zero,
         *                                  or negative
         * @throws NullPointerException     if {@code lockKeyPrefix}, {@code observer},
         *                                  {@code taskQueueLimits} or {@code instantSource}
         *                                  is {@code null}
         */
        public WorkflowExecutor build() {
            return new WorkflowExecutor(this);
        }
    }

    /**
     * Returns the clock this executor arms durable deadlines on: a live
     * {@code sleep()} timer's {@code fireAt}, a live {@code awaitSignal()}
     * deadline (armed {@code now + timeout} and decided against "now"), and
     * the value a live {@code currentTime()} records (R59).
     *
     * <p>{@link InstantSource#system()} unless {@link Builder#instantSource}
     * was given another — the Spring starter never does. The timer poller
     * does not read this source: the store decides which rows are due
     * ({@code WorkflowStore.getDueTimers(int)}), on Postgres by the database's
     * {@code now()} and, in a store that does not override that method, by
     * the caller's system clock. So a non-system source is only coherent where
     * the poller is not running and something else fires the rows
     * ({@code TestWorkflowEnvironment.advanceTime}).
     * Replay never re-arms from it: a memoized deadline is read back.
     *
     * @return the instant source, never {@code null}
     */
    public InstantSource instantSource() {
        return instantSource;
    }

    // ── Start workflow ─────────────────────────────────────────────────

    /**
     * Starts a new workflow on a virtual thread.
     *
     * <p>Creates a {@link WorkflowInstance}, persists it, spawns a virtual
     * thread, and invokes the workflow method. The workflow method runs
     * to completion (or parks on sleep/signal await).
     *
     * <p>The run executes only under its instance lock, taken before the row
     * is written. When the lock cannot be taken — the lock backend throws, or
     * the lock is held elsewhere — the row is still persisted, nothing runs
     * here, and this method returns normally: the instance is left
     * {@code RUNNING} for the recovery poller, which adopts it on the first
     * pass that can take the lock (one {@code maestro.recovery.poll-interval}
     * later at most, once the lock is free). As with a factory failure, no
     * {@code WORKFLOW_STARTED} event or {@code workflowStarted} callback is
     * emitted for such a start; the adopting run reports
     * {@code workflowResumed}.
     *
     * @param workflowId   the business workflow ID (e.g., {@code "order-abc"})
     * @param workflowType the workflow type name
     * @param taskQueue    the task queue name
     * @param input        the workflow input, or {@code null}
     * @param workflowFactory builds the workflow implementation object for this
     *                     run; called exactly once, on the calling thread, before
     *                     the run's virtual thread is created — or not at all
     *                     when the instance lock could not be taken
     * @param workflowMethod the entry-point method
     * @return the workflow instance UUID
     * @throws IllegalStateException        if the executor is shutting down
     * @throws WorkflowAlreadyExistsException if a workflow with this ID exists
     * @throws RuntimeException             (or an {@code Error}) from the store
     *         while the instance row is created or pre-delivered signals are
     *         adopted, propagated unchanged, with the instance lock released
     *         first. If it came after the row was created, the {@code RUNNING}
     *         row remains: the recovery poller adopts and runs it on its next
     *         pass, and a retry of the same start throws
     *         {@link WorkflowAlreadyExistsException}
     *         ({@code AdoptionLockLeakTest.startWorkflow_aFailureAfterTheRowIsCreated_…})
     * @throws WorkflowInstantiationException if the factory throws — the
     *         instance row already exists and is left {@code RUNNING} for the
     *         recovery poller to adopt; no {@code WORKFLOW_STARTED} event or
     *         {@code workflowStarted} observer callback is emitted for it
     * @see WorkflowRegistration#workflowFactory()
     */
    public UUID startWorkflow(
            String workflowId,
            String workflowType,
            String taskQueue,
            @Nullable Object input,
            Supplier<Object> workflowFactory,
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
        // poller cycle steals a just-created workflow before launch (Issue 74).
        var acquisition = instanceLockManager.tryAcquire(workflowId);
        try {
            // Persist instance and adopt any pre-delivered signals (self-recovery case 2:
            // signals sent before workflow starts are stored with null instanceId)
            store.createInstance(instance);
            signalManager.adoptOrphanedSignals(workflowId, instanceId);
        } catch (RuntimeException | Error e) {
            // An Error too (control-flow ones included): no run owns this lock yet, so
            // keeping it would leave the handle renewed for ever and the workflow, whose
            // RUNNING row may already be committed, unrecoverable until this node restarts.
            if (acquisition == WorkflowInstanceLockManager.Acquisition.ACQUIRED) {
                try {
                    instanceLockManager.release(workflowId);
                } catch (RuntimeException | Error releaseFailure) {
                    e.addSuppressed(releaseFailure);
                }
            }
            throw e;
        }

        if (acquisition != WorkflowInstanceLockManager.Acquisition.ACQUIRED) {
            // No run executes without its instance lock (P3). The row is
            // durable, so the start is not lost: recovery adopts it on the
            // first pass that can take the lock.
            logger.warn("Workflow '{}' was persisted but not started here: its instance lock {} — the recovery "
                            + "poller adopts it once the lock can be taken",
                    workflowId, acquisition == WorkflowInstanceLockManager.Acquisition.HELD_ELSEWHERE
                            ? "is held elsewhere" : "could not be taken (lock backend unavailable)");
            return instanceId;
        }

        // Launch on virtual thread (WORKFLOW_STARTED is published inside, once
        // the run's workflow object has been built)
        launchWorkflow(instance, workflowFactory, workflowMethod, inputPayload, false, acquisition, null);

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
     * @param workflowFactory builds a fresh workflow implementation object for
     *                       this run; called exactly once if — and only if — the
     *                       run is launched. A {@code RuntimeException} or
     *                       {@code LinkageError} it throws propagates as
     *                       {@link WorkflowInstantiationException}
     * @param workflowMethod the entry-point method
     * @return {@code true} if the workflow was launched; {@code false} if it
     *         was skipped (already running locally, instance lock held by
     *         another node, or the instance turned terminal in the meantime)
     */
    public boolean resumeWorkflow(
            WorkflowInstance instance,
            Supplier<Object> workflowFactory,
            Method workflowMethod
    ) {
        return resumeWorkflow(instance, workflowFactory, workflowMethod, null);
    }

    /**
     * {@link #resumeWorkflow(WorkflowInstance, Supplier, Method)} for a run
     * the recovery pass admitted with {@code reservation}. A launched run
     * claims the reservation on its own thread; every path that does not
     * launch it — skipped ({@code false}) or thrown, an {@code Error}
     * included — releases it here, in a {@code finally}, exactly once.
     *
     * @param reservation the run's task-queue reservation; {@code null} for none
     */
    boolean resumeWorkflow(
            WorkflowInstance instance,
            Supplier<Object> workflowFactory,
            Method workflowMethod,
            TaskQueueGovernor.@Nullable SlotReservation reservation
    ) {
        var launched = false;
        try {
            if (shuttingDown.get()) {
                logger.debug("Executor shutting down — skipping resume of workflow '{}'",
                        instance.workflowId());
                return false;
            }
            if (runningWorkflows.containsKey(instance.workflowId())) {
                logger.debug("Workflow '{}' is already running — skipping resume", instance.workflowId());
                return false;
            }

            launched = launchWorkflow(instance, workflowFactory, workflowMethod, instance.input(), true, null,
                    reservation);
            if (launched) {
                logger.info("Resuming workflow '{}' (type={}, status={})",
                        instance.workflowId(), instance.workflowType(), instance.status());
            }
            return launched;
        } finally {
            if (!launched && reservation != null) {
                // Not launched (shutting down, already running here, lock held
                // elsewhere or refused, turned terminal, adoption lost, a store
                // fault, or a failure before the thread started): the run will
                // never claim it. Never releaseWorkflowSlot here — before the
                // claim that could free another run's slot.
                reservation.release();
            }
        }
    }

    // ── Recovery ───────────────────────────────────────────────────────

    /**
     * Recovers all workflows that were active when the service last stopped.
     *
     * <p>Reads this service's recoverable instances (status IN RUNNING,
     * WAITING_SIGNAL, WAITING_TIMER, COMPENSATING) page by page — at most
     * {@value #RECOVERY_PAGE_SIZE} per store read, through
     * {@link WorkflowStore#getRecoverableInstances(String, RecoveryCursor, int)}
     * — and re-invokes each in replay mode. Instances of other services are
     * never read. Per page, an instance already running here is dropped, then
     * one {@link DistributedLock#heldKeys} read drops the instances whose lock
     * another node holds, so only the rest cost a lock acquisition.
     *
     * <p>This pass is <b>ungated</b>: it adopts every instance it can lock,
     * whatever this node's task-queue capacity, and the runs beyond a queue's
     * bound wait for their slots on their own threads. It is the startup pass
     * when the recovery poller is disabled
     * ({@link #startOneShotRecovery(Map, Duration)}). The recovery poller's passes are
     * gated instead — see {@link #startRecoveryPoller(Map, Duration)}.
     *
     * <p>An instance whose registration's factory throws
     * ({@link WorkflowInstantiationException}) is skipped, leaving it active
     * for a later pass; the pass continues with the remaining instances. Any
     * other exception ends the pass. Per pass, the first skipped instance of
     * each workflow type is logged at {@code ERROR} with the stack trace and
     * every further one of that type as a one-line {@code ERROR} without it;
     * a pass that skipped anything ends with one {@code WARN} summary —
     * {@code "Recovery pass skipped N instance(s) of M workflow type(s) whose
     * factory threw: {type=count, ...}"} — the line to alert on.
     *
     * @param registrations map of workflow type → registration metadata
     * @return the number of workflows recovered
     */
    public int recoverWorkflows(Map<String, WorkflowRegistration> registrations) {
        return fullPass(registrations, RecoveryGate.UNGATED, NEVER).adopted();
    }

    /**
     * {@link #recoverWorkflows(Map)} that also ends at the next instance once
     * {@code stop} answers {@code true} — the one-shot startup pass's own stop
     * flag. Like every pass it also ends once the executor is shutting down.
     *
     * @return the number of workflows recovered
     */
    int recoverWorkflowsUntil(Map<String, WorkflowRegistration> registrations, BooleanSupplier stop) {
        return fullPass(registrations, RecoveryGate.UNGATED, stop).adopted();
    }

    /** A pass that only the executor's shutdown ends. */
    private static final BooleanSupplier NEVER = () -> false;

    /**
     * The recovery poller's passes end on its thread's interrupt (its stop),
     * as well as on shutdown. The ungated passes ignore the interrupt: an
     * interrupted caller of {@link #recoverWorkflows(Map)} still adopts.
     */
    private static final BooleanSupplier INTERRUPTED = () -> Thread.currentThread().isInterrupted();

    /** The most recoverable instances one recovery-pass store read returns. */
    static final int RECOVERY_PAGE_SIZE = 200;

    /**
     * Whether a recovery pass asks the task-queue governor before it locks an
     * instance (Issue 30).
     */
    enum RecoveryGate {
        /** Admit only what this node can run or queue ({@link TaskQueueGovernor#admitRecovery}); defer the rest. */
        GATED,
        /** Adopt every instance this node can lock; runs beyond a bound wait on their own threads. */
        UNGATED
    }

    /**
     * What one recovery pass did.
     *
     * @param scanned         recoverable instances the store returned, over all pages
     * @param adopted         instances launched on this node
     * @param skippedByType   instances skipped because their factory threw,
     *                        counted per workflow type (in first-skip order)
     * @param deferredByQueue instances left unlocked because their task queue
     *                        had no slot or recovery ticket free on this node,
     *                        counted per task queue (in first-deferral order)
     * @param resumeAfter     the keyset position just before the first deferred
     *                        instance — where a capacity pass resumes;
     *                        {@code null} means the first row (or nothing deferred).
     *                        When a capacity pass ran out of headroom before
     *                        deferring anything, the position after the last row
     *                        it read
     * @param outOfCapacity   a capacity pass stopped because its queues had no
     *                        headroom left while rows after {@code resumeAfter}
     *                        may remain unread; always {@code false} for a full pass
     * @param resumeAfterByQueue per task queue that deferred, the keyset position
     *                        just before its first deferred instance in this pass
     *                        (a {@code null} value: the first row) — where a
     *                        capacity pass for that queue resumes
     * @param readTo          the position after the last row this pass read;
     *                        {@code null} if it read nothing
     * @param reachedEnd      the pass read to the last recoverable row (a short
     *                        or empty page), rather than stopping early
     */
    record RecoveryPass(int scanned, int adopted, Map<String, Integer> skippedByType,
                        Map<String, Integer> deferredByQueue, @Nullable RecoveryCursor resumeAfter,
                        boolean outOfCapacity, Map<String, @Nullable RecoveryCursor> resumeAfterByQueue,
                        @Nullable RecoveryCursor readTo, boolean reachedEnd) {
        /** @return instances skipped because their factory threw, across all types */
        int skipped() {
            return skippedByType.values().stream().mapToInt(Integer::intValue).sum();
        }

        /** @return instances deferred for lack of task-queue capacity, across all queues */
        int deferred() {
            return deferredByQueue.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /** One pass's running counts. Confined to the pass's thread. */
    private static final class PassTally {
        int scanned;
        int adopted;
        final Map<String, Integer> skippedByType = new LinkedHashMap<>();
        final Map<String, Integer> deferredByQueue = new LinkedHashMap<>();
        boolean deferredAny;
        @Nullable RecoveryCursor resumeAfter;
        /** Set when the pass must end now: the executor is shutting down or the thread was interrupted. */
        boolean stopped;
        /** Set when a capacity pass stops for lack of headroom; see {@link RecoveryPass#outOfCapacity()}. */
        boolean outOfCapacity;
        /** Per queue, the position before its first deferral; a {@code null} value is the first row. */
        final Map<String, @Nullable RecoveryCursor> resumeAfterByQueue = new LinkedHashMap<>();
        @Nullable RecoveryCursor readTo;
        boolean reachedEnd;

        RecoveryPass toPass() {
            return new RecoveryPass(scanned, adopted, Collections.unmodifiableMap(skippedByType),
                    Collections.unmodifiableMap(deferredByQueue), resumeAfter, outOfCapacity,
                    Collections.unmodifiableMap(resumeAfterByQueue), readTo, reachedEnd);
        }
    }

    /**
     * How many instances the most recent full gated pass on this node
     * deferred for lack of task-queue capacity: the recovery backlog this node
     * can see but cannot run or queue yet. Only a pass that read to the last
     * recoverable row sets it; one that threw or ended early (a stop, a
     * shutdown) leaves the previous value.
     *
     * @return the count; {@code 0} before the first full gated pass
     */
    public int recoveryDeferredCount() {
        return recoveryDeferred;
    }

    /**
     * The recovery poller's <b>gated</b> full pass: from the first row, as
     * {@link #recoverWorkflows}, but each instance is admitted by
     * {@link TaskQueueGovernor#admitRecovery} before its lock is taken, and an
     * instance its queue has no room for is deferred — no lock, no adoption,
     * left for a peer or a later pass. Returns the pass's full outcome.
     */
    RecoveryPass recoveryPass(Map<String, WorkflowRegistration> registrations) {
        return fullPass(registrations, RecoveryGate.GATED, INTERRUPTED);
    }

    private RecoveryPass fullPass(Map<String, WorkflowRegistration> registrations, RecoveryGate gate,
                                  BooleanSupplier stop) {
        var tally = new PassTally();

        // Keyset paging: each page is read strictly after the last row of the
        // previous one, and a short page is the last. A store fault ends the
        // pass mid-way (the poller retries next cycle), exactly as one fault in
        // the old single read did.
        RecoveryCursor after = null;
        while (!shuttingDown.get() && !tally.stopped) {
            var page = store.getRecoverableInstances(serviceName, after, RECOVERY_PAGE_SIZE);
            tally.scanned += page.size();
            if (page.isEmpty()) {
                tally.reachedEnd = true;
                break;
            }
            recoverPage(page, after, registrations, gate, stop, tally);
            after = RecoveryCursor.of(page.getLast());
            tally.readTo = after;
            if (page.size() < RECOVERY_PAGE_SIZE) {
                tally.reachedEnd = !tally.stopped;
                break;
            }
        }

        var pass = tally.toPass();
        if (gate == RecoveryGate.GATED && pass.reachedEnd()) {
            // A pass that ended early saw only part of the backlog.
            recoveryDeferred = pass.deferred();
        }
        logPass(pass, true);
        emit("recoveryPass", null, () -> observer.recoveryPass(pass.scanned(), pass.adopted()));
        return pass;
    }

    /** The smallest page a capacity pass reads: one small statement even for one free place. */
    static final int MIN_CAPACITY_PAGE = 20;

    /**
     * A capacity pass's page size for {@code headroom} free places on its
     * queues: twice the headroom (two nodes interleaving one backlog find
     * about half of it held elsewhere), at least {@value #MIN_CAPACITY_PAGE},
     * at most {@value #RECOVERY_PAGE_SIZE}.
     *
     * @param headroom the summed {@link TaskQueueGovernor#recoveryHeadroom} of the deferred queues
     * @return the page size
     */
    static int capacityPageSize(long headroom) {
        return (int) Math.min(RECOVERY_PAGE_SIZE, Math.max(MIN_CAPACITY_PAGE, 2L * headroom));
    }

    /**
     * A <b>capacity pass</b>: the gated pass the recovery poller runs as soon
     * as capacity frees after a pass that deferred. It resumes strictly after
     * {@code resumeAfter} — the rows before it were adopted, are running here,
     * held elsewhere, unregistered, factory-failing or terminal, and the next
     * full pass re-checks them — and reads pages sized to the free capacity of
     * {@code deferredQueues} ({@link #capacityPageSize}). Before each page it
     * re-reads that headroom and stops when it is 0, so a pass with nothing
     * free makes no statement at all. A full page continues to the next while
     * headroom remains; a short page ends the pass. It never sets
     * {@link #recoveryDeferredCount()}, which is a full pass's count.
     *
     * @param registrations  workflow type → registration
     * @param resumeAfter    where to resume; {@code null} for the first row
     * @param deferredQueues the queues the previous pass deferred on
     * @return what the pass did
     */
    RecoveryPass capacityPass(Map<String, WorkflowRegistration> registrations,
                              @Nullable RecoveryCursor resumeAfter,
                              Set<String> deferredQueues) {
        var tally = new PassTally();
        var after = resumeAfter;
        while (!shuttingDown.get() && !tally.stopped) {
            var headroom = recoveryHeadroom(deferredQueues);
            if (headroom <= 0) {
                tally.outOfCapacity = true;
                if (!tally.deferredAny) {
                    tally.resumeAfter = after;
                }
                break;
            }
            var size = capacityPageSize(headroom);
            var page = store.getRecoverableInstances(serviceName, after, size);
            tally.scanned += page.size();
            if (page.isEmpty()) {
                tally.reachedEnd = true;
                break;
            }
            recoverPage(page, after, registrations, RecoveryGate.GATED, INTERRUPTED, tally);
            after = RecoveryCursor.of(page.getLast());
            tally.readTo = after;
            if (page.size() < size) {
                tally.reachedEnd = !tally.stopped;
                break;
            }
        }
        var pass = tally.toPass();
        if (pass.scanned() > 0) {
            logPass(pass, false);
            emit("recoveryPass", null, () -> observer.recoveryPass(pass.scanned(), pass.adopted()));
        }
        return pass;
    }

    /** The summed recovery headroom of {@code queues}; never overflows. */
    long recoveryHeadroom(Set<String> queues) {
        var sum = 0L;
        for (var queue : queues) {
            sum += taskQueueGovernor.recoveryHeadroom(queue);
        }
        return sum;
    }

    /** The pass's summary lines; a capacity pass ({@code full == false}) logs its deferral at {@code DEBUG}. */
    private void logPass(RecoveryPass pass, boolean full) {
        if (pass.skipped() > 0) {
            logger.warn("Recovery pass skipped {} instance(s) of {} workflow type(s) whose factory threw: {}",
                    pass.skipped(), pass.skippedByType().size(), pass.skippedByType());
        }
        if (pass.deferred() > 0) {
            var line = "Recovery pass adopted {} and deferred {} instance(s) for lack of task-queue capacity on "
                    + "this node {}; they are adopted as slots free";
            if (full) {
                logger.info(line, pass.adopted(), pass.deferred(), pass.deferredByQueue());
            } else {
                logger.debug(line, pass.adopted(), pass.deferred(), pass.deferredByQueue());
            }
        } else if (pass.adopted() > 0) {
            logger.info("Recovered {} workflow(s) from {} recoverable instance(s)", pass.adopted(), pass.scanned());
        } else {
            logger.debug("Recovered 0 workflow(s) from {} recoverable instance(s)", pass.scanned());
        }
    }

    /**
     * Adopts what it can of one recovery page, in this order per instance:
     * drop it if it is running here; drop it if — by one advisory
     * {@code heldKeys} read for the page — another node holds its lock; drop
     * it if no registration names its type; under {@link RecoveryGate#GATED},
     * ask the governor for a slot and defer it when there is none; then
     * resume it. An instance not reported held still goes through
     * {@link #resumeWorkflow} and its {@code tryAcquire}, which stays the
     * authority on ownership.
     *
     * <p>Ends the pass ({@link PassTally#stopped}) as soon as the executor is
     * shutting down or {@code stop} answers {@code true}: the recovery
     * poller's interrupt for a gated pass, the one-shot pass's own flag, or
     * never for {@link #recoverWorkflows(Map)}.
     *
     * @param pageAfter the cursor this page was read after ({@code null}: the first row)
     * @param stop      the caller's own stop condition
     */
    private void recoverPage(List<WorkflowInstance> page,
                             @Nullable RecoveryCursor pageAfter,
                             Map<String, WorkflowRegistration> registrations,
                             RecoveryGate gate,
                             BooleanSupplier stop,
                             PassTally tally) {
        var heldElsewhere = instanceLockManager.heldElsewhere(page.stream()
                .map(WorkflowInstance::workflowId)
                .filter(id -> !runningWorkflows.containsKey(id))
                .toList());

        for (var i = 0; i < page.size(); i++) {
            if (shuttingDown.get() || stop.getAsBoolean()) {
                tally.stopped = true;
                return;
            }
            var instance = page.get(i);
            if (runningWorkflows.containsKey(instance.workflowId())) {
                continue;
            }
            if (heldElsewhere.contains(instance.workflowId())) {
                logger.debug("Workflow '{}' is locked by another node — skipping recovery this pass",
                        instance.workflowId());
                continue;
            }
            var reg = registrations.get(instance.workflowType());
            if (reg == null) {
                logger.warn("No registration for workflow type '{}', skipping recovery of '{}'",
                        instance.workflowType(), instance.workflowId());
                continue;
            }
            // Reserve, then lock: a node never locks a run it can neither run nor queue.
            var reservation = gate == RecoveryGate.GATED
                    ? taskQueueGovernor.admitRecovery(instance.taskQueue())
                    : TaskQueueGovernor.SlotReservation.UNBOUNDED;
            if (reservation == null && stop.getAsBoolean()) {
                // admitRecovery returns null on an interrupt (the poller's
                // stop): that row was not deferred for capacity.
                tally.stopped = true;
                return;
            }
            if (reservation == null) {
                tally.deferredByQueue.merge(instance.taskQueue(), 1, Integer::sum);
                var before = i == 0 ? pageAfter : RecoveryCursor.of(page.get(i - 1));
                if (!tally.deferredAny) {
                    tally.deferredAny = true;
                    tally.resumeAfter = before;
                }
                if (!tally.resumeAfterByQueue.containsKey(instance.taskQueue())) {
                    tally.resumeAfterByQueue.put(instance.taskQueue(), before);
                }
                continue;
            }
            try {
                // Releases the reservation itself whenever the run is not launched.
                if (resumeWorkflow(instance, reg.workflowFactory(), reg.workflowMethod(), reservation)) {
                    tally.adopted++;
                }
            } catch (WorkflowInstantiationException e) {
                // R44: only a factory failure is isolated per instance — one
                // type whose objects cannot be built must not starve every
                // other type's recovery. Anything else still ends the pass.
                // One stack trace per type per pass: a broken bean fails every
                // instance of its type identically, on every pass, on every node.
                var firstOfType = tally.skippedByType.merge(instance.workflowType(), 1, Integer::sum) == 1;
                if (firstOfType) {
                    logger.error("Skipping recovery of workflow '{}' (type={}) — its workflow object "
                                    + "could not be built; it stays active for a later pass. Further "
                                    + "instances of this type skipped in this pass are logged without "
                                    + "the stack trace",
                            instance.workflowId(), instance.workflowType(), e);
                } else {
                    logger.error("Skipping recovery of workflow '{}' (type={}) — its workflow object "
                                    + "could not be built (stack trace above): {}",
                            instance.workflowId(), instance.workflowType(),
                            // String, not Throwable: a trailing Throwable argument makes SLF4J
                            // print the full stack trace again for every skipped instance.
                            String.valueOf(e.getCause()));
                }
            }
        }
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
        signalManager.deliverSignal(workflowId, signalName, payload, null);
    }

    /**
     * Delivers a signal that carries the producer's identity for it, so a
     * redelivery of the same logical signal is suppressed instead of becoming
     * a second signal.
     *
     * <p>As {@link #deliverSignal(String, String, Object)}, except that at most
     * one signal is ever persisted per {@code (workflowId, signalName,
     * messageId)}: a delivery whose identity is already stored — consumed or
     * not — writes nothing and returns {@code false}. Every retry or republish
     * of one logical signal must carry the same id; two distinct signals must
     * carry distinct ids, or the second is lost as a "duplicate". A
     * {@code null} id (or one longer than 512 characters, or containing a
     * control character) means no identity: the signal is always persisted,
     * exactly as the three-argument form. The id is stored verbatim; a caller
     * that accepts ids from more than one source must namespace them so one
     * source cannot suppress another's signals — the Kafka transport stores
     * {@code env:<id>}, {@code hdr:<topic>:<id>} and
     * {@code kafka:<topicId>:<partition>:<offset>}, the starter's
     * {@code WorkflowHandle} {@code client:<id>}.
     *
     * <p>A transport consumer acknowledges its record either way — a
     * duplicate is a signal that is already durable.
     *
     * @param workflowId the target workflow's business ID
     * @param signalName the signal name
     * @param payload    the signal payload, or {@code null}
     * @param messageId  the producer's identity for this logical signal, or
     *                   {@code null} for none
     * @return {@code true} if the signal was persisted; {@code false} if it
     *         was a duplicate of one already stored
     */
    public boolean deliverSignal(String workflowId, String signalName, @Nullable Object payload,
                                 @Nullable String messageId) {
        return signalManager.deliverSignal(workflowId, signalName, payload, messageId);
    }

    // ── Timer fire ─────────────────────────────────────────────────────

    /**
     * Fires a timer, resuming a sleeping workflow.
     *
     * <p>Called by the timer poller when a due timer is found. Marks the
     * timer as fired in the store and unparks the workflow's virtual thread,
     * and stores a permit only when the workflow runs here.
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
     * <p>An {@code await-signal-*} timer is an {@code awaitSignal} deadline;
     * firing it ends the wait with
     * {@link io.b2mash.maestro.core.exception.SignalTimeoutException} (unless
     * a signal is already visible, which wins).
     *
     * @param workflowId  the workflow's business ID
     * @param timerId     the timer's logical ID (e.g., {@code "sleep-5"} or {@code "await-signal-7"})
     * @param timerDbId   the timer's database UUID (for store transition)
     */
    public void fireTimer(String workflowId, String timerId, UUID timerDbId) {
        var fired = store.markTimerFired(timerDbId);
        if (fired) {
            wakeTimerWaiter(workflowId, timerId);
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
     * <p>An {@code await-signal-*} timer is an {@code awaitSignal} deadline;
     * cancelling it ends the wait with
     * {@link io.b2mash.maestro.core.exception.SignalTimeoutException} (the row
     * is the arbiter: a non-{@code PENDING} row means the deadline is spent),
     * unless a signal is already visible, which wins.
     *
     * <p>Cancelling a timer that has already fired, or was already
     * cancelled, is a {@code false} no-op: the row's outcome is already
     * decided and the caller is told it lost the race rather than being left
     * to guess.
     *
     * @param workflowId the workflow's business ID
     * @param timerId    the timer's logical ID (e.g., {@code "sleep-5"} or {@code "await-signal-7"})
     * @param timerDbId  the timer's database UUID (for store transition)
     * @return {@code true} if this call transitioned the timer
     *         {@code PENDING → CANCELLED}; {@code false} if the timer had
     *         already fired or was already cancelled
     */
    public boolean cancelTimer(String workflowId, String timerId, UUID timerDbId) {
        var cancelled = store.markTimerCancelled(timerDbId);
        if (cancelled) {
            wakeTimerWaiter(workflowId, timerId);
            logger.debug("Cancelled timer '{}' for workflow '{}'", timerId, workflowId);
        } else {
            logger.debug("Timer '{}' for workflow '{}' already fired or cancelled — skipping unpark",
                    timerId, workflowId);
        }
        return cancelled;
    }

    /**
     * Wakes whoever waits on a timer row that just transitioned. A
     * {@code sleep()} parks on {@code <workflowId>:timer:<timerId>}; an
     * {@code await-signal-*} row is an {@code awaitSignal} deadline, whose
     * await parks on its signal key and is woken through
     * {@link SignalManager#onTimerTransition}. Unparking the {@code :timer:}
     * key for an await would only store a one-shot permit nobody ever
     * consumes, so it is skipped. A sleep's wake stores a permit only when the
     * workflow runs on this node (3.2): a sleep parked elsewhere re-reads its
     * row every wake-recheck interval.
     */
    private void wakeTimerWaiter(String workflowId, String timerId) {
        if (timerId.startsWith(SignalManager.TIMER_ID_PREFIX)) {
            signalManager.onTimerTransition(workflowId, timerId);
        } else {
            parkingLot.wake(workflowId, workflowId + ":timer:" + timerId);
        }
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
     *       different and larger feature.) <b>Compensation guards:</b>
     *       terminate never <em>starts</em> a compensation, and
     *       {@code SagaManager} re-checks the instance before entering
     *       compensation (on every CAS attempt) and before each sequential
     *       entry, so a terminate observed at any of those points stops the
     *       unwind. The only compensation work that can still complete after a
     *       terminate is the entry (or parallel branch set) already executing.
     *       See {@code docs/open-issues.md} Issue 22.</li>
     *   <li><b>No interruption of an in-flight activity attempt.</b> The
     *       attempt that is executing finishes and memoizes its result
     *       (harmless); but no further attempt starts, a retry backoff in
     *       progress is abandoned, and no later activity goes live — the run
     *       stands down at its next live activity, retry, park or status write
     *       ({@link RunCancellation}). This applies when this node owns the
     *       run; a remote owner converges via the guards described above.</li>
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
                    .output(serializer.serialize(new WorkflowTerminationDetail(reason, "admin")))
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
            // Flag first, then parks: the flag stops the next live activity
            // attempt and abandons a retry backoff; abandoning the parks
            // unblocks a thread waiting on a signal or timer. A run of this id
            // that registers after this lookup adopted the row before the
            // TERMINATED write above, so abandoning by workflowId (not by this
            // RunningWorkflow) poisons only runs of the terminated workflow —
            // see ParkingLot.abandonWorkflow (traced, not tested).
            var running = runningWorkflows.get(workflowId);
            if (running != null) {
                running.cancellation().requestTermination(reason);
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
         * distributed lock is held by another node, or the lock backend could
         * not be reached. The instance is left {@code RUNNING} — the recovery
         * poller (on any node) adopts it within one poll interval of the lock
         * becoming takeable.
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
        COMPENSATED_NOT_RETRYABLE,

        /**
         * The workflow is {@code FAILED}, and the code that handled the
         * failure consumed a signal before the run failed. Re-running that
         * code would need the signal again, and Maestro cannot give back the
         * exact signal row. Nothing was written. Terminate the workflow, or
         * finish its business outcome by hand.
         */
        SIGNAL_CONSUMED_AFTER_FAILURE
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
     * fault is fixed. But a memo the workflow <em>caught</em> and recovered
     * from (a fallback taken) must stay, or the retried run takes the other
     * branch and meets the fallback's memo.
     *
     * <h2>What a Retry deletes (WP3)</h2>
     * <p>The plan is computed from the history alone (package-private
     * {@code RetryPlanner}); the store applies it:
     * <ul>
     *   <li><b>Attributed.</b> The {@code WORKFLOW_FAILED} payload names, in
     *       {@code failedSequences}, the failure memos the exception that
     *       escaped the workflow method carried (itself, its cause chain, its
     *       suppressed exceptions). For each, Retry deletes the memo (with a
     *       parked await's {@code TIMER_SCHEDULED} just before it), everything
     *       its lane recorded after it, everything every enclosing lane
     *       recorded after the fork that led to it (with the whole subtree of
     *       any fork in those slots), and the {@code WORKFLOW_FAILED}. A
     *       caught failure followed by more work is kept and replays; a
     *       failure that was handled (an alert) and rethrown is re-run with
     *       its handling. A sibling branch, and anything before the failure,
     *       is kept. A second branch's independent failure is kept too: the
     *       run fails again naming it, and a second Retry clears it.</li>
     *   <li><b>Matched by message</b>, for a {@code WORKFLOW_FAILED} an older
     *       build wrote (no {@code failedSequences}): the one failure memo whose
     *       rebuilt exception message equals the payload's is treated as
     *       attributed.</li>
     *   <li><b>Last in lane</b>, when nothing is attributed (a failure
     *       rethrown without its cause): only a failure memo after which its
     *       path recorded nothing but {@code WORKFLOW_FAILED}.</li>
     *   <li><b>Legacy</b>, when the history holds a slot the lane walk cannot
     *       place (a slot consumed without a memo and followed by more work, or
     *       two lanes claiming one slot): the pre-WP3 delete-by-type set —
     *       every {@code ACTIVITY_FAILED}, every {@code WORKFLOW_FAILED} and,
     *       for an await-timeout failure, the highest {@code SIGNAL_TIMEOUT}
     *       with its park memo.</li>
     * </ul>
     * <p>Every deleted timer slot takes its timer row with it (any status), so
     * a retried await or sleep arms a fresh deadline instead of reusing a
     * spent one (R61); timer rows of kept slots are untouched. A version
     * marker recorded before the failure is never deleted. No signal row is
     * ever deleted or un-consumed.
     *
     * <p><b>A refusal (MD-1).</b> When the delete set would hold a
     * {@code SIGNAL_RECEIVED} memo — the failure's handling consumed a signal
     * — re-running that handling would need the signal again, and the memo
     * does not identify the exact signal row to give back. Retry then writes
     * nothing and returns {@link RetryOutcome#SIGNAL_CONSUMED_AFTER_FAILURE},
     * logged at WARN with the signal's name, its sequence and the remedy
     * (terminate the workflow or finish it by hand; the signal is not
     * redelivered).
     *
     * <h2>Mechanics — "make it recoverable again, then recover it"</h2>
     * <ol>
     *   <li>Read the instance. Absent → {@link RetryOutcome#NOT_FOUND}. Status
     *       other than {@code FAILED} → {@link RetryOutcome#NOT_FAILED} — an
     *       idempotent no-op, since retry only makes sense for a failure.</li>
     *   <li>Read the event log. A {@code COMPENSATION_STARTED} — the saga
     *       already ran compensations — returns
     *       {@link RetryOutcome#COMPENSATED_NOT_RETRYABLE} without touching
     *       anything; see that constant's Javadoc for why relaunching is
     *       unsafe on this path.</li>
     *   <li>Plan the deletes over that log (above). A refused plan returns
     *       {@link RetryOutcome#SIGNAL_CONSUMED_AFTER_FAILURE} without touching
     *       anything.</li>
     *   <li>{@link WorkflowStore#reopenFailedInstance}: the plan's event and
     *       timer-row deletes and a single optimistic compare-and-set
     *       ({@code status = RUNNING}, {@code completedAt = null},
     *       {@code version + 1}, only while the row is still {@code FAILED})
     *       in <b>one transaction</b> — all of it or none of it. Unlike
     *       {@link #terminateWorkflow}, this is <b>one</b> attempt, not a
     *       converge-loop: a conflict means another writer changed the row
     *       between the read and the write, and
     *       {@link OptimisticLockException} propagates, with nothing written,
     *       so an at-least-once transport redelivers the command against fresh
     *       state. A row purged meanwhile is {@link RetryOutcome#NOT_FOUND}.
     *       The lease epoch is not bumped here; the relaunch's adoption bumps
     *       it, as for any recovery. On every engine path nothing of the failed
     *       run can still write at its epoch (traced): it wrote {@code FAILED}
     *       through the fenced terminal write, after every {@code parallel()}
     *       branch had joined. Two cases outside the engine's own paths can
     *       append until that adoption fences them: a foreign
     *       {@link Thread#interrupt()} of a {@code parallel()} join, and an
     *       abandoned activity attempt that calls through its workflow context.
     *       The retried run then replays that event or stands down; it never
     *       records a failure for it.</li>
     *   <li>Relaunch via the same internal path
     *       {@link #resumeWorkflow(WorkflowInstance, Supplier, Method)} uses
     *       ({@code replaying = true}) — identical to crash recovery: every
     *       kept memo replays for free, and the run goes live at the first
     *       deleted slot of each lane it reaches, with a fresh retry-policy
     *       budget.</li>
     *   <li>If the local {@code runningWorkflows} guard or the instance lock
     *       refuses the launch ({@link RetryOutcome#ALREADY_RUNNING_LOCALLY} /
     *       {@link RetryOutcome#LOCK_HELD_ELSEWHERE}), the instance is already
     *       {@code RUNNING} — which is recoverable: the recovery poller (on any
     *       node) adopts it within one poll interval, the same as a crash
     *       between steps 4 and 5 heals.</li>
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
     * @param registration the registration (workflow factory and
     *                     entry-point method) for this workflow's type — the
     *                     same one recovery would use
     * @return what this call did — see {@link RetryOutcome}
     * @throws OptimisticLockException if another writer touched the instance
     *                                 row between the read and the
     *                                 compare-and-set; nothing was written.
     *                                 Transient: the caller should <em>not</em>
     *                                 acknowledge, so the command is
     *                                 redelivered against fresh state.
     * @throws WorkflowInstantiationException if the registration's factory
     *                                 throws while relaunching. The
     *                                 compare-and-set has already happened, so
     *                                 the row is left {@code RUNNING} and the
     *                                 recovery poller adopts it once the
     *                                 factory works.
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
        var events = store.getEvents(latest.id());
        var alreadyCompensated = events.stream()
                .anyMatch(e -> e.eventType() == EventType.COMPENSATION_STARTED);
        if (alreadyCompensated) {
            logger.warn("Workflow '{}' already ran saga compensations — not retrying "
                    + "(retry of a compensated saga is not yet supported; see "
                    + "RetryOutcome.COMPENSATED_NOT_RETRYABLE)", workflowId);
            return RetryOutcome.COMPENSATED_NOT_RETRYABLE;
        }

        // What to delete: the failure that ended the run and what the run
        // recorded after it while handling it (WP3, D-3). Pure; no store call.
        var plan = RetryPlanner.plan(workflowId, events);
        if (plan.refused()) {
            logger.warn("Workflow '{}' was not retried: the code that handled its failure consumed signal '{}' "
                    + "(sequence {}), which a retry would need again. Terminate the workflow or finish it by "
                    + "hand; the signal is not redelivered (RetryOutcome.SIGNAL_CONSUMED_AFTER_FAILURE)",
                    workflowId, consumedSignalName(events, plan.consumedSignalAt()), plan.consumedSignalAt());
            return RetryOutcome.SIGNAL_CONSUMED_AFTER_FAILURE;
        }

        var now = Instant.now();
        var running = latest.toBuilder()
                .status(WorkflowStatus.RUNNING)
                .completedAt(null)
                .updatedAt(now)
                .version(latest.version() + 1)
                .build();
        // The deletes and the single-attempt CAS are one transaction (D-7):
        // a crash or a lost CAS leaves the history and the row untouched.
        // OptimisticLockException (a stale version, or a row no longer
        // FAILED) and every store fault propagate; see the Javadoc above.
        try {
            store.reopenFailedInstance(running, plan.deleteSequences(), plan.deleteTimerIds());
        } catch (WorkflowNotFoundException e) {
            logger.warn("Cannot retry workflow '{}' — no such instance in this service (purged?)", workflowId);
            return RetryOutcome.NOT_FOUND;
        }
        logger.info("Workflow '{}' reopened for retry: {} event(s) deleted and {} timer id(s) named for deletion (basis {})",
                workflowId, plan.deleteSequences().size(), plan.deleteTimerIds().size(), plan.basis());
        logger.debug("Workflow '{}' retry deleted sequences {} and timer ids {}",
                workflowId, plan.deleteSequences(), plan.deleteTimerIds());

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

        var launched = launchWorkflow(running, registration.workflowFactory(), registration.workflowMethod(),
                running.input(), true, null, null);
        if (!launched) {
            logger.info("Workflow '{}' CAS'd to RUNNING but its instance lock is held elsewhere — "
                    + "left for the recovery poller to adopt", workflowId);
            return RetryOutcome.LOCK_HELD_ELSEWHERE;
        }

        publishLifecycleEvent(running, LifecycleEventType.WORKFLOW_RETRIED, null);
        logger.info("Retried workflow '{}' (type={})", workflowId, running.workflowType());
        return RetryOutcome.RETRIED;
    }

    /**
     * The name of the signal whose {@code SIGNAL_RECEIVED} memo sits at
     * {@code sequence}: its step name without the
     * {@code $maestro:awaitSignal:} prefix, or the step name as recorded if it
     * has no such prefix. Only the name, never the payload, goes into a log line.
     */
    private static @Nullable String consumedSignalName(List<WorkflowEvent> events, @Nullable Integer sequence) {
        if (sequence == null) {
            return null;
        }
        for (var event : events) {
            if (event.sequenceNumber() == sequence && event.eventType() == EventType.SIGNAL_RECEIVED) {
                var step = event.stepName();
                return step != null && step.startsWith(SignalManager.STEP_PREFIX)
                        ? step.substring(SignalManager.STEP_PREFIX.length())
                        : step;
            }
        }
        return null;
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
        var poller = new TimerPoller(store, this, distributedLock, serviceName, pollInterval, batchSize,
                observer);
        installAndStart(timerPoller, poller, "Timer poller", poller::start, poller::stop);
        timerPollerStarted.set(true);
    }

    // ── Recovery poller ─────────────────────────────────────────────────

    /**
     * Starts the background recovery poller.
     *
     * <p>The poller periodically runs a recovery pass so that workflows whose
     * owning node has died (instance lock expired) or shut down are adopted
     * without requiring a restart of this node. Every node polls — safety
     * comes from the per-instance distributed lock and the in-JVM
     * running-workflow check, not from leader election.
     *
     * <p>Its passes are <b>gated</b> (Issue 30): an instance is locked only when
     * its task queue has a free slot on this node, or room in the queue's
     * bounded line of recovered runs; the rest are deferred, unlocked, for a
     * peer or for later. Its first pass runs at once, and a pass that deferred
     * for lack of capacity is followed by another as soon as capacity frees,
     * at most once per {@code min(1 s, pollInterval)}. Full passes from the
     * first row run every {@code pollInterval}.
     *
     * <p>The Spring Boot starter calls this automatically at startup
     * (configurable via {@code maestro.recovery.*}).
     *
     * @param registrations map of workflow type → registration metadata
     * @param pollInterval  interval between full recovery passes (e.g., 60 seconds)
     * @throws IllegalStateException if the poller is already started or the
     *                               executor is shutting down
     */
    public void startRecoveryPoller(Map<String, WorkflowRegistration> registrations, Duration pollInterval) {
        startRecoveryPoller(registrations, pollInterval, RecoveryPoller.defaultCapacitySpacing(pollInterval));
    }

    /**
     * {@link #startRecoveryPoller(Map, Duration)} with an explicit spacing of
     * capacity passes; a test seam.
     *
     * @param capacitySpacing the least time between the starts of two passes
     *                        when the second is a capacity pass
     */
    void startRecoveryPoller(Map<String, WorkflowRegistration> registrations, Duration pollInterval,
                             Duration capacitySpacing) {
        if (shuttingDown.get()) {
            throw new IllegalStateException(
                    "WorkflowExecutor is shutting down — cannot start recovery poller");
        }
        var poller = new RecoveryPoller(this, registrations, serviceName, pollInterval, observer,
                taskQueueGovernor.capacitySignal(), capacitySpacing);
        installAndStart(recoveryPoller, poller, "Recovery poller", poller::start, poller::stop);
        recoveryPollerStarted.set(true);
    }

    /**
     * Starts the startup recovery pass of a node whose recovery poller is
     * disabled: one <b>ungated</b> pass ({@link #recoverWorkflows(Map)}) on its
     * own virtual thread, {@code maestro-startup-recovery-<service>}, so the
     * caller does not wait for it.
     *
     * <p>A pass that throws is logged like a recovery-poller failure (one
     * {@code ERROR} with the stack trace, then an {@code ERROR} reminder every
     * 5 minutes while it continues) and retried after {@code retryInterval},
     * with no limit, until one pass completes; then the thread ends. Only an
     * {@link Error} it contains is counted, on
     * {@code maestro.loop.errors{loop=recovery-poller}}; a store fault is
     * logged, not counted. No health detail or meter reports the pass itself.
     * A {@code MaestroControlFlowError} ends it at once.
     * {@link #shutdown()} stops it. It does not occupy the recovery poller's
     * slot: {@link #startRecoveryPoller(Map, Duration)} may still be called,
     * and {@link #isRecoveryPollerRunning()} does not report it.
     *
     * <p>The Spring Boot starter calls this at startup when
     * {@code maestro.recovery.enabled=false}, with
     * {@code maestro.recovery.poll-interval} as the retry interval.
     *
     * @param registrations map of workflow type → registration metadata
     * @param retryInterval the wait after a failed pass before the next; must be positive
     * @throws IllegalArgumentException if {@code retryInterval} is {@code null},
     *                                  zero or negative
     * @throws IllegalStateException    if it was already started on this
     *                                  executor, or the executor is shutting down
     */
    public void startOneShotRecovery(Map<String, WorkflowRegistration> registrations, Duration retryInterval) {
        if (shuttingDown.get()) {
            throw new IllegalStateException(
                    "WorkflowExecutor is shutting down — cannot start the one-shot startup recovery");
        }
        var recovery = new OneShotRecovery(this, registrations, serviceName, retryInterval, observer);
        installAndStart(oneShotRecovery, recovery, "One-shot startup recovery", recovery::start, recovery::stop);
    }

    // ── Signal janitor ──────────────────────────────────────────────────

    /**
     * The settings a signal janitor was started with — see
     * {@link #startSignalJanitor(Duration, Duration)}.
     *
     * @param interval  time between sweeps
     * @param retention how long after a workflow reached {@code COMPLETED} or
     *                  {@code TERMINATED} its unconsumed signals are kept
     */
    public record SignalJanitorSettings(Duration interval, Duration retention) {
    }

    /**
     * Starts the background signal janitor.
     *
     * <p>Every {@code interval} the janitor purges the unconsumed signals of
     * workflows that reached {@code COMPLETED} or {@code TERMINATED} more than
     * {@code retention} ago, via
     * {@link WorkflowStore#deleteUnconsumedSignalsForTerminalInstancesBefore(String, Instant, int)}
     * (see its Javadoc for what survives — notably {@code FAILED} workflows,
     * which may still be retried). Every node runs one, without leader
     * election; it deletes this service's signals only, in batches of 1,000,
     * at most 1,000 batches per sweep.
     * A store failure is logged and retried on the next interval.
     * {@link #shutdown()} stops it.
     *
     * <p>If this method is never called, nothing is purged. The Spring Boot
     * starter calls it automatically ({@code maestro.signal.janitor-interval},
     * {@code maestro.signal.terminal-retention}).
     *
     * @param interval  time between sweeps; must be positive
     * @param retention how long after a workflow finished its unconsumed
     *                  signals are kept; must be positive
     * @throws IllegalArgumentException if either duration is {@code null},
     *                                  zero or negative, or this executor's
     *                                  service name is blank
     * @throws IllegalStateException    if the janitor is already started or
     *                                  the executor is shutting down
     */
    public void startSignalJanitor(Duration interval, Duration retention) {
        if (shuttingDown.get()) {
            throw new IllegalStateException(
                    "WorkflowExecutor is shutting down — cannot start signal janitor");
        }
        var janitor = new SignalJanitor(store, serviceName, interval, retention, observer);
        installAndStart(signalJanitor, janitor, "Signal janitor", janitor::start, janitor::stop);
        signalJanitorSettings = new SignalJanitorSettings(interval, retention);
    }

    // ── Retention sweeper ───────────────────────────────────────────────

    /**
     * The settings a retention sweeper is started with — see
     * {@link #startRetentionSweeper(RetentionSettings)}.
     *
     * @param interval            time between sweeps; positive
     * @param completedRetention  how long a {@code COMPLETED} workflow is kept
     *                            after it completed; positive
     * @param terminatedRetention how long a {@code TERMINATED} workflow is kept
     *                            after it was terminated; positive
     * @param failedRetention     how long a {@code FAILED} workflow is kept after
     *                            it failed — typically longer, since an operator
     *                            may still retry it; positive
     * @param batchSize           instances deleted per transaction; 1 to
     *                            {@link #MAX_BATCH_SIZE} inclusive
     * @param maxBatchesPerSweep  batches per status per sweep, bounding one
     *                            sweep's work; at least 1
     */
    public record RetentionSettings(Duration interval,
                                    Duration completedRetention,
                                    Duration terminatedRetention,
                                    Duration failedRetention,
                                    int batchSize,
                                    int maxBatchesPerSweep) {

        /**
         * Upper bound on {@link #batchSize()}. A JDBC store binds one parameter
         * per candidate in each of its {@code IN (...)} lists, and pgjdbc
         * refuses more than 32 767 binds per statement — a larger batch would
         * make every purge fail with nothing but a {@code WARN} per interval to
         * show for it. Ten thousand leaves ample headroom and already makes a
         * long transaction; raise the interval or the batch count instead.
         */
        public static final int MAX_BATCH_SIZE = 10_000;

        /**
         * Validates the settings.
         *
         * @throws IllegalArgumentException if any duration is {@code null}, zero
         *                                  or negative, if either count is below
         *                                  1, or if {@code batchSize} exceeds
         *                                  {@link #MAX_BATCH_SIZE}
         */
        public RetentionSettings {
            requirePositive("interval", interval);
            requirePositive("completedRetention", completedRetention);
            requirePositive("terminatedRetention", terminatedRetention);
            requirePositive("failedRetention", failedRetention);
            if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
                throw new IllegalArgumentException("batchSize must be between 1 and " + MAX_BATCH_SIZE
                        + ", got " + batchSize);
            }
            if (maxBatchesPerSweep < 1) {
                throw new IllegalArgumentException("maxBatchesPerSweep must be at least 1, got " + maxBatchesPerSweep);
            }
        }

        private static void requirePositive(String name, @Nullable Duration value) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException(name + " must be positive, got " + value);
            }
        }
    }

    /**
     * Starts the background retention sweeper.
     *
     * <p>Every {@code settings.interval()} the elected leader for this service
     * purges this service's workflows that reached {@code COMPLETED},
     * {@code TERMINATED} or {@code FAILED} longer ago than that status's
     * retention — the instance row and its whole history (events, signals,
     * timers) — via
     * {@link WorkflowStore#purgeTerminalInstancesBefore(String, io.b2mash.maestro.core.model.WorkflowStatus, Instant, int)},
     * in batches of {@code settings.batchSize()}. Leadership is held on
     * {@code maestro:leader:retention:<serviceName>} through the
     * {@link DistributedLock} (always held when there is none) and re-checked
     * before every batch. A store failure is logged and retried on the next
     * interval. {@link #shutdown()} stops it.
     *
     * <p>If this method is never called, nothing is ever purged and the store
     * grows without bound. The Spring Boot starter calls it automatically
     * unless {@code maestro.retention.enabled=false}.
     *
     * @param settings interval, per-status retention and batching
     * @throws NullPointerException  if {@code settings} is {@code null}
     * @throws IllegalStateException if the sweeper is already started or the
     *                               executor is shutting down
     */
    public void startRetentionSweeper(RetentionSettings settings) {
        Objects.requireNonNull(settings, "settings");
        if (shuttingDown.get()) {
            throw new IllegalStateException(
                    "WorkflowExecutor is shutting down — cannot start retention sweeper");
        }
        var sweeper = new RetentionSweeper(store, distributedLock, serviceName, settings, observer);
        installAndStart(retentionSweeper, sweeper, "Retention sweeper", sweeper::start, sweeper::stop);
        retentionSettings = settings;
    }

    // ── Service-name check ──────────────────────────────────────────────

    /**
     * Runs the once-per-start shared-table check (pilot readiness P3; Issues
     * 39, 79): fails, or warns, when this service's Maestro tables hold active
     * workflow instances recorded under any other {@code maestro.service-name},
     * of any workflow type.
     *
     * <p>Services never share Maestro tables ({@code docs/support-matrix.md}),
     * and recovery adopts only instances recorded under this executor's own
     * service name, so such rows are either another service in the same tables
     * or workflows stranded by a rename. The check asks the store once,
     * through {@link WorkflowStore#getActiveInstanceCountsUnderOtherServices(String, int)},
     * on its own virtual thread ({@code maestro-foreign-service-check}), and
     * waits for the answer at most 5 seconds. Then:
     * <ul>
     *   <li>rows found: under {@link ForeignServiceCheckMode#FAIL} this method
     *       throws {@link ForeignServiceRowsException} naming each other service
     *       name and its count; under {@link ForeignServiceCheckMode#WARN} it logs
     *       one {@code WARN} per name (at most 20) and returns;</li>
     *   <li>no rows: one {@code DEBUG} line;</li>
     *   <li>no answer within the bound, or a failed read: <em>inconclusive</em>
     *       — one {@code WARN}, and it returns. An unreachable store must not
     *       turn into a restart loop. A read still running is left to finish on
     *       its own thread, never interrupted.</li>
     * </ul>
     * {@link ForeignServiceCheckMode#OFF} reads nothing. While the executor is
     * shutting down the method logs at {@code DEBUG} and reads nothing. It
     * never writes.
     *
     * <p>The Spring Boot starter calls this at context refresh, before
     * recovery or any listener starts, with {@code maestro.store.foreign-service-check}
     * ({@code fail} by default). An embedder without the starter calls it
     * once before starting recovery.
     *
     * @param mode what to do with rows found; never {@code null}
     * @throws ForeignServiceRowsException under {@code FAIL}, when another
     *                                     service name holds active instances
     * @throws NullPointerException        if {@code mode} is {@code null}
     */
    public void checkForeignServices(ForeignServiceCheckMode mode) {
        checkForeignServices(mode, ForeignServiceCheck.PROBE_TIMEOUT);
    }

    /** {@link #checkForeignServices(ForeignServiceCheckMode)} with the wait bound as a test seam. */
    void checkForeignServices(ForeignServiceCheckMode mode, Duration timeout) {
        Objects.requireNonNull(mode, "mode");
        if (shuttingDown.get()) {
            logger.debug("WorkflowExecutor is shutting down — the shared-table check is not run");
            return;
        }
        ForeignServiceCheck.run(store, serviceName, mode, timeout);
    }

    /**
     * Claims a background-loop slot, starts the loop, then re-checks for a
     * concurrent {@link #shutdown()}. The up-front {@code shuttingDown} check
     * in each {@code start*} method is only a fast fail: a shutdown landing
     * between it and the claim would find the slot empty, stop nothing, and
     * leave the loop running on a stopped executor. The re-check after the
     * start closes that window — whichever of {@code shutdown()} and this
     * method runs last stops the loop.
     */
    private <T> void installAndStart(
            AtomicReference<T> slot, T loop, String name, Runnable start, Runnable stop) {
        if (!slot.compareAndSet(null, loop)) {
            throw new IllegalStateException(name + " already started");
        }
        start.run();
        if (shuttingDown.get()) {
            slot.compareAndSet(loop, null);
            stop.run();
            throw new IllegalStateException(
                    "WorkflowExecutor is shutting down — " + name + " stopped");
        }
    }

    // ── Shutdown ───────────────────────────────────────────────────────

    /**
     * Gracefully shuts down the executor.
     *
     * <h2>One deadline</h2>
     * <p>The configured shutdown timeout (default 20 seconds;
     * {@code maestro.shutdown.timeout}) is the deadline for the whole call,
     * measured on the monotonic clock ({@link System#nanoTime()}, never the
     * executor's {@code InstantSource}). Every step that waits takes what is
     * left of it, so {@code shutdown()} returns within the timeout plus a few
     * milliseconds of bookkeeping even when every step is stuck. In order:
     * <ol>
     *   <li>Stop taking work, without waiting: the timer poller, recovery
     *       poller, one-shot startup recovery, signal janitor and retention
     *       sweeper are asked to stop;
     *       every park and retry backoff is abandoned; every run queued for a
     *       task-queue slot is released.</li>
     *   <li>Drain the runs in flight until the deadline, a run whose launch has
     *       registered it but not yet started its thread included, and a run
     *       that has finished but not yet released its instance lock: a run
     *       drained in time has finished its lock release when
     *       {@code shutdown()} returns. The lock is free if the backend's
     *       release succeeded; if it failed, the lock's row is left to expire
     *       by TTL.</li>
     *   <li>Join the background loops for what is left. A loop still inside
     *       a store call at the deadline is named in a {@code WARN} and ends
     *       after that call.</li>
     *   <li>Stop the instance-lock renewer, waiting at most two seconds of
     *       what is left.</li>
     *   <li>Flush queued lifecycle events, for at most five seconds of what is
     *       left; events still queued then are counted as dropped.</li>
     * </ol>
     * <p>One summary line follows: {@code INFO Maestro shutdown finished in …}
     * when no run is left, otherwise {@code WARN Maestro shutdown hit its …
     * deadline}, naming up to ten of the runs still running.
     *
     * <p>Shutdown never interrupts a workflow thread or the lock renewer. If
     * the calling thread is interrupted, every remaining wait ends at once,
     * the steps that do not wait still run, and the interrupt status is
     * restored before the call returns.
     *
     * <h2>A second call</h2>
     * <p>A call made while a shutdown is in progress — in a Spring context,
     * the executor's inferred destroy method — waits for the first to finish,
     * for at most what is left of its deadline plus two seconds, so the beans
     * the engine was built on are not destroyed under a drain still in
     * progress. A call after a shutdown has finished returns at once.
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
     * running, and their instance locks expire by TTL. A retry backoff in
     * progress is abandoned too, with {@link ExecutorShutdownException},
     * leaving the instance {@code RUNNING} and recoverable; the attempt counter
     * restarts on the next node.
     *
     * <p>A run waiting for a task-queue slot (a fresh start, an adopted run, a
     * park's reacquire, an activity or a compensation) leaves at once with
     * {@link ExecutorShutdownException}: it stands down recoverable and
     * releases its instance lock before {@code shutdown()} returns.
     */
    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            awaitShutdownInProgress();
            return;
        }
        // Step 1: one deadline for every step below, on the monotonic clock.
        var deadline = ShutdownDeadline.start(shutdownTimeout);
        shutdownDeadline = deadline;
        try {
            shutdown(deadline);
        } finally {
            shutdownFinished.countDown();
        }
    }

    /** D-2's steps 2–10, each bounded step taking what is left of {@code deadline}. */
    private void shutdown(ShutdownDeadline deadline) {
        var inFlightAtStart = unreleasedRuns.size();
        logger.info("Maestro shutdown started: {} run(s) in flight; deadline {} ms (maestro.shutdown.timeout)",
                inFlightAtStart, deadline.budget().toMillis());

        // Steps 2–4 never wait, so their order does not matter. Step 2: ask
        // the background loops to stop — no new timer fires and no new
        // recovery launches — and join them only after the drain (step 6),
        // so a loop stuck in a store call never delays it.
        var loops = new ArrayList<StoppableLoop>(5);
        for (var slot : List.<AtomicReference<? extends StoppableLoop>>of(
                timerPoller, recoveryPoller, oneShotRecovery, signalJanitor, retentionSweeper)) {
            var loop = slot.getAndSet(null);
            if (loop != null) {
                loop.requestStop();
                loops.add(loop);
            }
        }
        // Step 3: abandon every park so those threads exit promptly. Their
        // workflows keep the WAITING_* status they parked in and stay
        // recoverable — a park abandoned this way is not a workflow failure.
        parkingLot.shutdown();
        // Abandon every retry backoff the same way. Shutdown is not checked
        // before an activity attempt, so in-flight work still drains. Over
        // unreleasedRuns, the runs step 5 waits for: each is asked to stop
        // (idempotent, and inert for a run whose body has already ended).
        unreleasedRuns.values().forEach(r -> r.cancellation().requestShutdown());
        // Step 4: release every run queued for a task-queue slot (Issue 29):
        // the governor's poison permit wakes each waiter in turn, without an
        // interrupt, and each stands down recoverable.
        taskQueueGovernor.shutdown();

        // Step 5: drain in-flight runs until the deadline. Never interrupted.
        // Over unreleasedRuns, not runningWorkflows: a run that has already
        // left runningWorkflows but not yet released its instance lock is
        // still waited for, so shutdown never returns with that lock held.
        beforeShutdownDrain.run();
        var drainStart = System.nanoTime();
        for (var run : unreleasedRuns.values()) {
            try {
                // A run registered before its thread is started (launchWorkflow
                // registers, then starts) is waited for, not skipped: its
                // cancellation was requested at step 3, so once started it
                // stands down at its first stop point or completes, and the
                // join below follows it. Thread.join(Duration) would throw
                // IllegalThreadStateException on the still-NEW thread.
                if (!run.launched().await(deadline.remaining().toNanos(), TimeUnit.NANOSECONDS)) {
                    continue; // still not started at the deadline: reported as still running
                }
                if (run.thread().getState() == Thread.State.NEW) {
                    continue; // the launch failed before start(): the launcher unregistered it and released its lock
                }
                run.thread().join(deadline.remaining());
            } catch (InterruptedException e) {
                // Restored at once: every bounded wait below then ends at once
                // too, and the non-blocking steps still run.
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while waiting for workflow '{}' during shutdown",
                        run.instance().workflowId());
                break;
            }
        }
        var drainMs = millisSince(drainStart);

        // Step 6: join the loops asked to stop at step 2, against what is left.
        var loopsStart = System.nanoTime();
        for (var loop : loops) {
            if (!loop.awaitStopped(deadline.remaining())) {
                logger.warn("Background loop '{}' did not stop within the shutdown deadline; "
                        + "it ends after its current store call", loop.loopName());
            }
        }
        var loopsMs = millisSince(loopsStart);

        // Step 7: the poison has finished travelling: take the governor's
        // loans back so its slot counts are exact again.
        taskQueueGovernor.reclaimShutdownPermits();

        // Step 8: stop the lock renewer (never interrupted). It renewed the
        // locks of runs still draining until now; those still running when
        // it stops expire by TTL.
        var locksStart = System.nanoTime();
        instanceLockManager.close(deadline.remainingAtMost(WorkflowInstanceLockManager.CLOSE_JOIN));
        var locksMs = millisSince(locksStart);

        // Step 9: last, so it carries the events of the runs that drained —
        // a bounded flush, then the publisher is forced down: a stalled
        // transport must not make shutdown hang.
        var eventsStart = System.nanoTime();
        lifecycleEventPublisher.shutdown(deadline.remainingAtMost(LifecycleEventPublisher.SHUTDOWN_TIMEOUT));
        var eventsMs = millisSince(eventsStart);

        // Step 10: the summary line.
        var steps = "drain %d ms, background loops %d ms, instance locks %d ms, lifecycle events %d ms"
                .formatted(drainMs, loopsMs, locksMs, eventsMs);
        // A run stuck in its lock release counts as still running too.
        var stillRunning = unreleasedRuns.values().stream().map(r -> r.instance().workflowId()).toList();
        if (stillRunning.isEmpty()) {
            logger.info("Maestro shutdown finished in {} ms of its {} ms deadline: {} run(s) in flight at start, "
                            + "0 still running ({})",
                    deadline.elapsed().toMillis(), deadline.budget().toMillis(), inFlightAtStart, steps);
        } else {
            var recoveredBy = "their instance locks expire after " + instanceLockTtl.toMillis()
                    + " ms (maestro.lock.ttl) and a peer recovers them";
            logger.warn("Maestro shutdown hit its {} ms deadline (maestro.shutdown.timeout): {} of {} run(s) still "
                            + "running {}; shutdown does not interrupt them and records nothing for them; {} ({})",
                    deadline.budget().toMillis(), stillRunning.size(), inFlightAtStart,
                    workflowIdList(stillRunning.stream().distinct().toList()), recoveredBy, steps);
        }
    }

    /**
     * D-5: a {@link #shutdown()} call made while another is in progress — in
     * a Spring context, the executor's inferred destroy method — waits for
     * the first to finish, for at most what is left of its deadline plus
     * {@link #SECOND_CALLER_GRACE}. So bean destruction cannot take the
     * {@code DataSource} and the other beans the engine was built on away
     * under a drain that outlived Spring's phase timeout.
     */
    private void awaitShutdownInProgress() {
        if (shutdownFinished.getCount() == 0) {
            return;
        }
        var deadline = shutdownDeadline;
        var bound = (deadline != null ? deadline.remaining() : shutdownTimeout).plus(SECOND_CALLER_GRACE);
        logger.info("Shutdown already in progress — waiting up to {} ms for it to finish", bound.toMillis());
        try {
            if (!shutdownFinished.await(bound.toNanos(), TimeUnit.NANOSECONDS)) {
                logger.warn("Shutdown still in progress after waiting {} ms — returning without waiting longer",
                        bound.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long millisSince(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /** At most ten ids, then {@code and N more}: a deadline WARN stays one readable line. */
    private static String workflowIdList(List<String> ids) {
        var shown = 10;
        if (ids.size() <= shown) {
            return ids.toString();
        }
        return ids.subList(0, shown) + " and " + (ids.size() - shown) + " more";
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
     * <p>The query is answered by the workflow object of the current run —
     * the one {@link WorkflowRegistration#workflowFactory()} built when this
     * executor launched or re-adopted it — never by an object shared with
     * other runs of the same workflow type.
     *
     * <p>Queries are answered only by the node whose executor currently holds
     * this workflow's run — the one that holds its instance lock. A workflow
     * that is parked, running or compensating on another node, or that has
     * reached a terminal status, throws {@link WorkflowNotQueryableException}.
     * Cross-node query routing is out of scope; route the request to the
     * owning node or read durable state via {@code WorkflowHandle.status()}/{@code result()}.
     * "Owning node" means the lock holder in the normal case only: a node that
     * has lost its instance lock (frozen past the TTL, partitioned) keeps
     * answering from its own, possibly stale, object until that run exits —
     * the same zombie window the stand-down machinery closes for writes.
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
     * Returns the number of wake permits this node holds — wakes that arrived
     * for a workflow before it parked. A node-local gauge source, like
     * {@link #parkedCount()}.
     *
     * <p><b>Thread safety:</b> thread-safe; reads a concurrent map's size.
     *
     * @return the number of wake permits in this executor's parking lot
     */
    public int wakePermitCount() {
        return parkingLot.pendingWakeCount();
    }

    /**
     * Returns the governor that enforces this executor's per-task-queue
     * concurrency limits ({@code maestro.worker.task-queues}).
     *
     * <p>Workflow slots are enforced by the executor itself. Activity slots
     * apply only to proxies created with this governor — pass it to the
     * {@code ActivityProxyFactory.createProxy} overload that takes a
     * {@link TaskQueueGovernor}. Also exposes the free-slot counts, for tests
     * and diagnostics.
     *
     * @return the governor; {@link TaskQueueGovernor#UNBOUNDED} when no limits
     *         and no default limits were configured
     */
    public TaskQueueGovernor taskQueueGovernor() {
        return taskQueueGovernor;
    }

    /**
     * Returns the sink through which this executor — and every component it
     * built — publishes lifecycle events <em>off</em> the workflow thread.
     *
     * <p>Hand it to the {@code ActivityProxyFactory.createProxy} overload that
     * takes a {@link LifecycleEventSink} (the Spring starter's
     * {@code ActivityStubBeanPostProcessor} does) so {@code ACTIVITY_*} events
     * take the same route: a transport that blocks — Kafka's producer on a
     * missing topic, up to {@code max.block.ms} — then costs the workflow
     * nothing. Already honours {@code maestro.admin.events.enabled}: with
     * publishing disabled, or no messaging at all, this is
     * {@link LifecycleEventSink#NOOP}.
     *
     * @return the off-thread sink; never {@code null}
     */
    public LifecycleEventSink lifecycleEventSink() {
        return lifecycleEventSink;
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
     * Returns whether the one-shot startup recovery
     * ({@link #startOneShotRecovery(Map, Duration)}) is still in progress or
     * waiting to retry a failed pass.
     *
     * @return {@code false} once a pass has completed, the executor has shut
     *         it down, or it was never started
     */
    boolean isOneShotRecoveryRunning() {
        var recovery = oneShotRecovery.get();
        return recovery != null && recovery.isRunning();
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

    /**
     * Returns whether this node's instance-lock renewer thread is alive. It
     * starts with the first instance lock this node acquires, so it is
     * {@code false} before then, on a node without a {@code DistributedLock},
     * and after {@link #shutdown()}.
     *
     * @return {@code true} while the instance-lock renewer thread runs
     */
    public boolean isInstanceLockRenewerRunning() {
        return instanceLockManager.isRenewerRunning();
    }

    /**
     * Returns whether this node's instance-lock renewer has been started.
     * Monotonic in practice; see {@link #hasTimerPollerStarted()} for the
     * startup-vs-fault rationale.
     *
     * @return {@code true} once the instance-lock renewer has been started
     */
    public boolean hasInstanceLockRenewerStarted() {
        return instanceLockManager.hasRenewerStarted();
    }

    /**
     * Returns the instance-lock renewer's current failure streak: consecutive
     * renewal passes that failed (the lock backend's {@code renewAll} threw, or
     * the pass threw an {@code Error}) since the last pass that did not.
     * {@code 0} before the renewer starts. A renewer can be running with a
     * positive streak: its thread is alive, but the locks it holds are not
     * being renewed.
     *
     * @return the number of consecutive failed renewal passes
     */
    public int instanceLockRenewerFailureStreak() {
        return instanceLockManager.renewFailureStreak();
    }

    /**
     * Returns whether the background signal janitor is currently running on
     * this executor.
     *
     * @return {@code true} if {@link #startSignalJanitor(Duration, Duration)}
     *         has been called and the janitor has not since stopped (e.g. via
     *         {@link #shutdown()})
     */
    public boolean isSignalJanitorRunning() {
        var janitor = signalJanitor.get();
        return janitor != null && janitor.isRunning();
    }

    /**
     * Returns the settings the signal janitor was started with.
     *
     * <p>Lets a caller (such as the Spring Boot starter's configuration tests)
     * verify which interval and retention actually reached the engine. The
     * value is retained after {@link #shutdown()} stops the janitor.
     *
     * @return the settings passed to
     *         {@link #startSignalJanitor(Duration, Duration)}, or {@code null}
     *         if it has never been called successfully
     */
    public @Nullable SignalJanitorSettings signalJanitorSettings() {
        return signalJanitorSettings;
    }

    /**
     * Returns whether the background retention sweeper is currently running
     * on this executor.
     *
     * @return {@code true} if {@link #startRetentionSweeper(RetentionSettings)}
     *         has been called and the sweeper has not since stopped (e.g. via
     *         {@link #shutdown()})
     */
    public boolean isRetentionSweeperRunning() {
        var sweeper = retentionSweeper.get();
        return sweeper != null && sweeper.isRunning();
    }

    /**
     * Returns whether {@link #startRetentionSweeper(RetentionSettings)} has
     * ever been called successfully on this executor. Monotonic — see
     * {@link #hasTimerPollerStarted()} for the startup-vs-fault rationale.
     *
     * @return {@code true} once the retention sweeper has been started at least once
     */
    public boolean hasRetentionSweeperStarted() {
        return retentionSettings != null;
    }

    /**
     * Returns the settings the retention sweeper was started with.
     *
     * <p>Lets a caller (such as the Spring Boot starter's configuration tests)
     * verify which settings actually reached the engine. The value is retained
     * after {@link #shutdown()} stops the sweeper.
     *
     * @return the settings passed to
     *         {@link #startRetentionSweeper(RetentionSettings)}, or {@code null}
     *         if it has never been called successfully
     */
    public @Nullable RetentionSettings retentionSettings() {
        return retentionSettings;
    }

    // ── Internal: workflow launch ──────────────────────────────────────

    private boolean launchWorkflow(
            WorkflowInstance snapshot,
            Supplier<Object> workflowFactory,
            Method workflowMethod,
            @Nullable JsonNode inputPayload,
            boolean replaying,
            WorkflowInstanceLockManager.@Nullable Acquisition preAcquired,
            TaskQueueGovernor.@Nullable SlotReservation reservation
    ) {
        // The per-instance distributed lock is the admission guard: it stops a
        // second node from starting a redundant run while this one is live.
        // Fresh starts acquire it in startWorkflow (before createInstance) and
        // launch only once it is ACQUIRED; resume paths acquire here. Held —
        // renewed — until executeWorkflow's finally, including parked waits and
        // saga compensation. No run launches without it (P3): a resume that
        // cannot take it is skipped, and the next recovery pass tries again.
        // What stops a run that LOST the lock from persisting progress is not
        // the lock but the lease fence below (Issue 11).
        var acquisition = preAcquired != null
                ? preAcquired
                : instanceLockManager.tryAcquire(snapshot.workflowId());
        if (acquisition != WorkflowInstanceLockManager.Acquisition.ACQUIRED) {
            logger.info("Workflow '{}' instance lock {} — skipping resume", snapshot.workflowId(),
                    acquisition == WorkflowInstanceLockManager.Acquisition.HELD_ELSEWHERE
                            ? "is held elsewhere" : "could not be taken (lock backend unavailable)");
            return false;
        }

        // The instance this run executes: the fresh start's own row, or — on a
        // resume — the row as re-read and ADOPTED here, carrying the lease
        // epoch this run will write under. Assigned exactly once on each path.
        WorkflowInstance instance;
        if (replaying) {
            // Both store calls below run while this node holds the lock. A store fault here (a StoreUnavailableException from a DB
            // blip, or any other store error) must not leak the handle: left
            // in heldLocks it would be renewed forever, every later tryAcquire
            // would answer HELD_ELSEWHERE, and the workflow would be
            // unrecoverable on this node until a JVM restart.
            try {
                // Close the recovery-query→resume race: the previous owner may
                // have finished the workflow after the recovery snapshot was
                // taken and released the lock this node just acquired.
                var current = store.getInstance(snapshot.workflowId());
                if (current.isEmpty() || !current.get().status().isActive()) {
                    logger.debug("Workflow '{}' turned terminal before resume — skipping",
                            snapshot.workflowId());
                    instanceLockManager.release(snapshot.workflowId());
                    return false;
                }
                // Adoption (Issue 11): bump the row's lease epoch under its
                // version CAS. From this write on, every run-scoped write by any
                // EARLIER holder of this instance — a node frozen past its lock
                // TTL, a partitioned peer — is fenced by the store, whatever its
                // lock renewer did or did not notice. Losing this CAS means
                // another adopter got here first in the same instant; this node
                // yields exactly as if the lock had been held elsewhere.
                var adopted = adopt(current.get());
                if (adopted == null) {
                    instanceLockManager.release(snapshot.workflowId());
                    return false;
                }
                instance = adopted;
            } catch (RuntimeException | Error e) {
                instanceLockManager.release(snapshot.workflowId());
                throw e;
            }
        } else {
            instance = snapshot;
        }

        // From here to thread.start() the lock is held but no run owns it yet:
        // anything thrown in between — an Error included — must release it, or
        // the handle is renewed forever and the workflow is unrecoverable on
        // every node until this one restarts.
        RunningWorkflow running = null;
        try {
            afterLaunchAdmission.run();
            var compensationStack = new CompensationStack();
            var operations = new DefaultWorkflowOperations(
                    store, distributedLock, serializer, parkingLot, compensationStack,
                    signalManager, wakeRecheckInterval, observer, taskQueueGovernor, lifecycleEventSink);

            var cancellation = new RunCancellation();
            var ctx = new WorkflowContext(
                    instance.id(),
                    instance.workflowId(),
                    instance.runId(),
                    instance.workflowType(),
                    instance.taskQueue(),
                    serviceName,
                    0,
                    replaying,
                    operations,
                    cancellation,
                    instance.leaseEpoch()
            );

            // Detect @Saga on the workflow method
            var sagaAnnotation = workflowMethod.getAnnotation(Saga.class);
            var parallelCompensation = sagaAnnotation != null && sagaAnnotation.parallelCompensation();

            // F1: one workflow object per run, built on the launching thread
            // before the run's virtual thread exists — a throwing factory
            // surfaces to the caller and releases the lock in the catch below.
            Object workflowImpl;
            try {
                workflowImpl = workflowFactory.get();
            } catch (RuntimeException | LinkageError e) {
                // A LinkageError (NoClassDefFoundError, ExceptionInInitializerError)
                // is a bean that cannot be built, like a RuntimeException — and
                // recovery isolates only this wrapper per instance, so unwrapped
                // it would end every pass at the same keyset position.
                throw new WorkflowInstantiationException(instance.workflowId(), instance.workflowType(), e);
            }
            if (!replaying) {
                // A fresh start reports itself only once its object exists, so a
                // throwing factory leaves no unmatched WORKFLOW_STARTED behind.
                publishLifecycleEvent(instance, LifecycleEventType.WORKFLOW_STARTED, null);
                emit("workflowStarted", instance.workflowId(),
                        () -> observer.workflowStarted(observed(instance)));
            }
            // Set as executeWorkflow is entered. Until then no finally of the
            // run owns the lock, the entry or the reservation, so a Throwable
            // before it is cleaned up in the run's catch below. Allocated here,
            // on the launching thread, so nothing on the run's thread runs
            // before that catch can see it.
            var entered = new boolean[1];
            var thread = Thread.ofVirtual()
                    .name("maestro-workflow-%s-%s".formatted(instance.workflowType(), instance.workflowId()))
                    .unstarted(() -> {
                        try {
                            WorkflowMDC.populate(ctx);
                            beforeRunBody.run();
                            ScopedValue.where(WorkflowContext.scopedValue(), ctx)
                                    .run(() -> {
                                        entered[0] = true;
                                        executeWorkflow(ctx, instance, workflowImpl, workflowMethod,
                                                inputPayload, compensationStack, parallelCompensation, reservation);
                                    });
                        } catch (Throwable t) {
                            if (!entered[0]) {
                                runThreadDiedBeforeItsBody(instance.workflowId(), reservation, t);
                            }
                            throw t; // control-flow Errors included: cleaned up, then rethrown unchanged
                        } finally {
                            // Last on this thread: the instance lock was released,
                            // or its release attempted, above on every path.
                            unreleasedRuns.remove(Thread.currentThread());
                            WorkflowMDC.clear();
                        }
                    });

            // Register before starting to prevent the race where a fast workflow
            // finishes and removes itself before the put() below executes
            running = new RunningWorkflow(thread, instance, instance.workflowType(), workflowImpl,
                    cancellation, new CountDownLatch(1));
            // Tracked for the drain before it is registered, and until after its
            // lock release, so it is never in neither map while it holds the lock.
            unreleasedRuns.put(thread, running);
            runningWorkflows.put(instance.workflowId(), running);
            // R22(1): shutdown() requests a shutdown on every run it finds
            // registered. A launch that passed its shuttingDown check before
            // shutdown began but registers only after that loop ran would be
            // missed — and would then wait out a full retry backoff. Re-checking
            // after the put closes the window: either the loop sees this run, or
            // this check sees the flag (the flag is set before the loop starts).
            if (shuttingDown.get()) {
                cancellation.requestShutdown();
            }
            beforeRunThreadStart.run();
            thread.start();
            running.launched().countDown();
        } catch (RuntimeException | Error e) {
            // The launch's own failure is what the caller must see: every
            // cleanup failure is suppressed on it, and none skips a later step.
            try {
                if (running != null) {
                    try {
                        runningWorkflows.remove(instance.workflowId(), running);
                        duringFailedLaunchCleanup.run();
                        // A wake that saw this run registered may have stored a
                        // permit; no run will park on it now (3.2).
                        parkingLot.clearPending(instance.workflowId());
                    } catch (RuntimeException | Error cleanupFailure) {
                        e.addSuppressed(cleanupFailure);
                    }
                }
                try {
                    instanceLockManager.release(instance.workflowId());
                } catch (RuntimeException | Error releaseFailure) {
                    e.addSuppressed(releaseFailure);
                }
            } finally {
                if (running != null) {
                    // Never started. Untracked and released only after the lock's
                    // release, so a drain waiting for this launch does not let
                    // shutdown finish (and the store be torn down) under it; in a
                    // finally, so no failure above leaves a ghost run every later
                    // drain would wait its whole deadline for.
                    unreleasedRuns.remove(running.thread());
                    running.launched().countDown();
                }
            }
            throw e;
        }
        if (replaying) {
            emit("workflowResumed", instance.workflowId(),
                    () -> observer.workflowResumed(observed(instance)));
        }
        return true;
    }

    /**
     * The run's thread is dying with {@code cause} before
     * {@code executeWorkflow} was entered, so none of the run's own cleanup
     * will run. Does what a launch failure does — drops this thread's running
     * entry and any stored wake permit, gives the slot reservation back and
     * releases the instance lock — so the workflow is not stranded with a
     * lock renewed for ever. Every failure here is suppressed on {@code cause}.
     */
    private void runThreadDiedBeforeItsBody(String workflowId,
                                            TaskQueueGovernor.@Nullable SlotReservation reservation,
                                            Throwable cause) {
        // Each step in its own guard, nothing allocated ahead of them: a
        // failure in one step, or in setting it up, never skips the next.
        var self = Thread.currentThread();
        try {
            runningWorkflows.computeIfPresent(workflowId, (_, r) -> r.thread() == self ? null : r);
        } catch (RuntimeException | Error cleanupFailure) {
            cause.addSuppressed(cleanupFailure);
        }
        try {
            parkingLot.clearPending(workflowId);
        } catch (RuntimeException | Error cleanupFailure) {
            cause.addSuppressed(cleanupFailure);
        }
        try {
            if (reservation != null) {
                reservation.release();
            }
        } catch (RuntimeException | Error cleanupFailure) {
            cause.addSuppressed(cleanupFailure);
        }
        try {
            instanceLockManager.release(workflowId);
        } catch (RuntimeException | Error cleanupFailure) {
            cause.addSuppressed(cleanupFailure);
        }
    }

    /**
     * The adoption compare-and-set: rewrites {@code current} with
     * {@code leaseEpoch + 1} under its version. This is the one instance write
     * that is deliberately <em>unfenced</em> — it is what moves the fence.
     *
     * @param current the row as freshly re-read by the resume path
     * @return the row as written, carrying the new epoch and version; or
     *         {@code null} if the CAS was lost (another adopter or writer got
     *         there first) or the row vanished — the caller yields
     */
    private @Nullable WorkflowInstance adopt(WorkflowInstance current) {
        var now = Instant.now();
        var adopted = current.toBuilder()
                .leaseEpoch(current.leaseEpoch() + 1)
                .updatedAt(now)
                .version(current.version() + 1)
                .build();
        try {
            store.updateInstance(adopted);
        } catch (OptimisticLockException lost) {
            logger.info("Workflow '{}' adoption lost its compare-and-set (expected version {}, found {}) — "
                            + "another node adopted it first; skipping resume",
                    current.workflowId(), lost.expectedVersion(), lost.actualVersion());
            return null;
        } catch (WorkflowNotFoundException gone) {
            logger.debug("Workflow '{}' vanished before adoption — skipping resume", current.workflowId());
            return null;
        }
        logger.debug("Workflow '{}' adopted at lease epoch {} (version {})",
                adopted.workflowId(), adopted.leaseEpoch(), adopted.version());
        return adopted;
    }

    // ── Internal: workflow execution (virtual thread body) ─────────────

    private void executeWorkflow(
            WorkflowContext ctx,
            WorkflowInstance instance,
            Object workflowImpl,
            Method workflowMethod,
            @Nullable JsonNode inputPayload,
            CompensationStack compensationStack,
            boolean parallelCompensation,
            TaskQueueGovernor.@Nullable SlotReservation reservation
    ) {
        try {
            // F8: take this run's workflow slot on its task queue — blocking
            // while the queue is at its maestro.worker concurrency limit (a
            // no-op for an unbounded queue). Released first in finally below.
            // A recovered run claims the slot or queue place its pass reserved.
            taskQueueGovernor.acquireWorkflowSlot(ctx.taskQueue(), ctx.workflowId(), reservation);
            if (taskQueueGovernor.availableWorkflowSlots(ctx.taskQueue()).isPresent()) {
                // A bounded run may have waited for its slot while this node
                // began shutting down, or while the workflow was terminated.
                // It has executed nothing yet, so it stands down here —
                // shutdown leaves it recoverable, terminate writes nothing —
                // instead of starting a run nobody wants. An unbounded queue
                // never waits, so its behaviour is unchanged.
                var cancellation = ctx.cancellation();
                cancellation.throwIfTerminated(ctx.workflowId());
                cancellation.throwIfStale(ctx.workflowId());
                if (cancellation.cause() == RunCancellation.Cause.SHUTDOWN) {
                    throw new ExecutorShutdownException(("Executor is shutting down — workflow '%s' got its "
                            + "task-queue slot only after shutdown began; it did not start and stays "
                            + "recoverable").formatted(ctx.workflowId()));
                }
            }
            // Deserialize input and invoke the workflow method
            Object result = invokeWorkflowMethod(ctx, workflowImpl, workflowMethod, inputPayload);

            // Success — finalise, converging with any other writer
            var outputPayload = result != null ? serializer.serialize(result) : null;
            // Issue 26: the COMPLETED status and the WORKFLOW_COMPLETED event
            // are one atomic store write. A DuplicateEventException from it
            // (another runner already owns the terminal slot) wrote nothing
            // and reaches the stale-run stand-down arm below.
            //
            // R33: a run already known to be stale (workflow code swallowed an
            // earlier collision or StaleRunException) must not reach its
            // terminal write at all — the StaleRunException this raises takes
            // the same stand-down arm. Likewise a run whose store fault was
            // latched (R2: workflow code swallowed the StoreUnavailableException
            // and returned) — its StoreUnavailableException takes the
            // store-unavailable arm, so the run is abandoned, not COMPLETED.
            ctx.cancellation().throwIfStale(ctx.workflowId());
            ctx.cancellation().throwIfStoreUnavailable(ctx.workflowId());
            boolean completed;
            try {
                completed = transitionToTerminal(ctx, instance, WorkflowStatus.COMPLETED,
                        EventType.WORKFLOW_COMPLETED, outputPayload);
            } catch (DuplicateEventException | StaleRunException | FencedWriteException
                     | StoreUnavailableException routed) {
                // Keep their own arms below: the stale-run / lease-fenced
                // stand-down, and the store-unavailable abandon — a database
                // outage on the terminal write is transient (WARN,
                // STORE_UNAVAILABLE), not an operator problem.
                throw routed;
            } catch (RuntimeException terminalWriteFailed) {
                // R31: any other store error here (a DB blip surfacing as an
                // unchecked SQL exception, a failed re-read) is NOT a workflow
                // failure — the workflow body already succeeded. That includes
                // an OptimisticLockException (R34 a): the SPI reports a version
                // conflict as `false`, which transitionToTerminal retries, so a
                // store that throws one instead is a persistence fault like any
                // other — there is no dedicated arm for it below, and letting
                // it through would reach catch (Exception e). Letting it
                // reach catch (Exception e) would run a successful saga's
                // compensations and could durably record FAILED. The atomic
                // write means nothing terminal landed, so the instance stays
                // active and recoverable: abandon the local run (finally
                // releases the lock) and let the next recovery replay the
                // memoized steps and complete it. Only the store call is
                // contained — result serialization above stays outside, so a
                // non-serializable result is still a workflow failure.
                logger.error("Could not persist the COMPLETED outcome of workflow '{}' — leaving it "
                                + "non-terminal for recovery to finalise",
                        ctx.workflowId(), terminalWriteFailed);
                emitRunAbandoned(ctx, AbandonReason.TERMINAL_WRITE_FAILED);
                return;
            }
            if (completed) {
                // Only the winner of the terminal transition counts the
                // outcome — a converged loser must not double-fire. Emitted
                // BEFORE the lifecycle publish: winning the transition is what
                // durably makes this run the completer, so nothing after it
                // may decide whether the completion is observed. Contained, so
                // a throwing observer cannot land in catch (Exception) — which
                // would compensate a workflow that just succeeded.
                emit("workflowCompleted", ctx.workflowId(),
                        () -> observer.workflowCompleted(observed(instance)));
                emit("workflowFinished", ctx.workflowId(),
                        () -> observer.workflowFinished(observed(instance), WorkflowStatus.COMPLETED,
                                elapsedSince(instance)));

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
            // parked, or while it sat in an activity's retry backoff (which
            // shutdown abandons rather than waits out). Its durable state —
            // WAITING_SIGNAL or WAITING_TIMER for a park, RUNNING for an
            // abandoned backoff (nothing was memoized for that activity; the
            // in-memory attempt counter restarts on the recovering node), or
            // COMPENSATING mid-saga — is still valid and still recoverable, so
            // nothing is written and no compensation runs. Whichever node
            // starts next picks it up.
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
        } catch (UnknownWorkflowHistoryException unreadableHistory) {
            // Also not a failure: this node is too old to read part of this
            // workflow's persisted history — a newer node wrote it during a
            // mixed-version deploy window. Writes nothing, runs no
            // compensation, and leaves the instance in whatever recoverable
            // status it already had, so an upgraded node adopts it through
            // ordinary recovery. Placed with the other non-failure arms; like
            // them the ordering is readability-only, since the exception is an
            // Error and catch (Exception e) below could never reach it.
            handleUnknownHistoryStandDown(ctx, unreadableHistory);
        } catch (DuplicateEventException | StaleRunException | FencedWriteException staleRun) {
            // Issue 18 / Issue 11: all three mean "another run owns this
            // workflow's progress — this node's view is stale". A
            // FencedWriteException is the store's lease fence doing its job:
            // this node lost or outlived its instance lock (frozen past TTL,
            // partitioned, or a no-lock-backend race), a peer ADOPTED the
            // workflow and bumped the row's lease epoch, and the store refused
            // this run's write — nothing was persisted. A
            // DuplicateEventException is the (workflow_instance_id,
            // sequence_number) unique index catching the same race in the
            // window before the fence applied (a peer's event already in the
            // slot under a still-current lease). Recording a FAILURE here — as
            // the generic catch below would — durably marks a workflow that
            // SUCCEEDED on the winner as FAILED and compensates completed work.
            // Instead this run STANDS DOWN like the shutdown and termination
            // cases above: write nothing, compensate nothing, release the local
            // run. The winner's durable state governs; if no winner exists (a
            // pure self-conflict), the instance stays active and recovery
            // replays it from the log. A StaleRunException is the same signal
            // from a status write (C5): a fresh read found COMPLETED or FAILED
            // written by another runner. MUST stay ahead of catch (Exception e)
            // — all three are MaestroExceptions.
            handleStaleRunStandDown(ctx, staleRun);
        } catch (StoreUnavailableException storeDown) {
            // R1: the store could not be reached — raised by the store itself
            // (after StoreFaultGuard's bounded retries of the idempotent calls)
            // or by a stop point once the guard latched the run. Not a
            // workflow failure: recording FAILED would compensate work that
            // never failed. Write nothing, compensate nothing, leave the
            // instance active; finally releases the lock and recovery resumes
            // it once the store answers. MUST stay ahead of catch (Exception e)
            // — it is a MaestroException.
            handleStoreUnavailable(ctx, storeDown);
        } catch (Error e) {
            // Sits after the control-flow arms above and before catch (Exception e):
            // an Error that is NOT one of the three MaestroControlFlowError signals
            // and NOT a node-level error (StackOverflowError, AssertionError, ...)
            // is a workflow failure. Before this arm it unwound through finally,
            // released the lock and left the row RUNNING, so the recovery poller
            // re-adopted it every pass forever — no log, no workflowFailed callback.
            if (e instanceof MaestroControlFlowError) throw e; // owned by the arms above
            if (NodeErrors.isNodeError(e)) {
                // R20: a LinkageError (bad deploy: NoClassDefFoundError,
                // ExceptionInInitializerError...) or a VirtualMachineError other
                // than StackOverflowError (OutOfMemoryError, InternalError...) is
                // this NODE's problem, not the workflow's. Recording FAILED would
                // compensate real work that never failed; a rollback or a healthy
                // node heals it. Loud, but non-terminal: write nothing, leave the
                // instance recoverable, release the lock in finally below.
                handleNodeError(ctx, e);
            } else if (storeUnavailableLatched(ctx)) {
                // R2: the run's store fault was latched and workflow code
                // turned it into something else — this throwable is a
                // consequence of the outage, not the workflow's failure.
                handleStoreUnavailable(ctx, e);
            } else {
                failAndCompensate(ctx, instance, e, compensationStack, parallelCompensation);
            }
        } catch (Exception e) {
            if (storeUnavailableLatched(ctx)) {
                // R2: as above — workflow code caught the latched
                // StoreUnavailableException and threw something else (or
                // carried on into a failure the outage caused). Sticky: the
                // run is abandoned, never failed or compensated.
                handleStoreUnavailable(ctx, e);
            } else {
                failAndCompensate(ctx, instance, e, compensationStack, parallelCompensation);
            }
        } finally {
            // F8: give the slot back first, so a run waiting on this queue can
            // start while the bookkeeping below completes. Idempotent — a park
            // that ended in a control-flow Error already gave it back.
            taskQueueGovernor.releaseWorkflowSlot(ctx.workflowId());
            // Remove-then-release: a concurrent recovery attempt in the gap
            // still sees the lock held and skips — retried by the next poll.
            // Only this run's own entry (matched by its thread, as
            // runThreadDiedBeforeItsBody does): if this run lost its lock, a
            // new run of this id may have registered over it, and its entry stays.
            var self = Thread.currentThread();
            runningWorkflows.computeIfPresent(ctx.workflowId(), (_, r) -> r.thread() == self ? null : r);
            // Drop orphaned wake permits (e.g. duplicate signal deliveries
            // that arrived after the last await) now that the run is over —
            // BEFORE the release: once the lock is free a new run of this id
            // can launch and be given a permit, which a later clearPending
            // would erase. Here, after the removal above, any wake that stores
            // a permit fails its re-check (the run is gone) and drops it, and
            // no new run can be registered until the release below. That last
            // step relies on the instance lock: a new run of this id is
            // registered only by a node that acquired the lock, and no launch
            // proceeds without it (P3). It does not hold when THIS run lost its
            // lock before it ended (a renewal reported it lost, or it expired):
            // then a new run of the id can register here between the removal
            // and clearPending, and a permit stored for it can be erased. The
            // cost is latency, never a lost wake: that run's park finds no
            // permit and wakes at its next re-check, at most one wake-recheck
            // interval later (traced, not tested).
            parkingLot.clearPending(ctx.workflowId());
            instanceLockManager.release(ctx.workflowId());
            // Drop the sticky terminate poison: this run is over, so a future
            // run of the same workflowId must be able to park again
            parkingLot.clearTerminated(ctx.workflowId());
        }
    }

    private @Nullable Object invokeWorkflowMethod(
            WorkflowContext ctx, Object workflowImpl, Method workflowMethod,
            @Nullable JsonNode inputPayload
    ) throws Exception {
        try {
            if (workflowMethod.getParameterCount() == 0) {
                return workflowMethod.invoke(workflowImpl);
            } else {
                // Deserialize input to the method's parameter type.
                //
                // RULING 9: the input is persisted state, re-read on EVERY
                // recovery run — including runs on a node older than whichever
                // one wrote it. A shape this build cannot read must stand the
                // run down, not be recorded as a workflow failure: this throw
                // happens before the workflow body executes, so a FAILED here
                // is a workflow that never ran a single step being marked
                // failed. Sequence 0 names the instance's own input rather than
                // any event.
                //
                // A null input always goes through the serializer too: the live
                // start holds the NullNode that Optional.empty() serializes to,
                // while a JDBC store hands recovery a Java null (SQL NULL). The
                // serializer reads both alike — Optional.empty() for an
                // Optional parameter, null for any other — so the live run and
                // every recovery receive the same argument.
                var paramType = workflowMethod.getParameterTypes()[0];
                var input = UnknownHistoryGuard.requireReadablePayload(ctx.workflowId(), 0,
                        "persisted workflow input",
                        () -> serializer.deserialize(inputPayload, paramType));
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
     * {@code WAITING_*} status of the park it was interrupted in,
     * {@code RUNNING} if it was in an activity's retry backoff, or
     * {@code COMPENSATING} if shutdown landed mid-saga — exactly the state
     * recovery needs to find.
     */
    private void handleShutdownSuspension(WorkflowContext ctx) {
        var status = statusForLog(ctx.workflowId());
        logger.info("Workflow '{}' suspended by shutdown while {} — left recoverable",
                ctx.workflowId(), status);
        // RULING 5: the only callback a stateful observer gets on this thread
        // for this path. workflowTerminated/Completed/Failed never fire here.
        emitRunAbandoned(ctx, AbandonReason.SHUTDOWN);
    }

    /**
     * The instance's status, for a stand-down or abandon log line only — never
     * for a decision. Best-effort: an advisory read (neither retried nor
     * latched by {@link StoreFaultGuard}), and {@code "unknown"} if the store
     * does not answer. These handlers run while a run unwinds — often because
     * the store is down — and a read that threw out of them would skip the
     * observer callback that follows it.
     */
    private String statusForLog(String workflowId) {
        try {
            return String.valueOf(StoreFaultGuard.advisory(() -> store.getInstance(workflowId))
                    .map(WorkflowInstance::status)
                    .orElse(null));
        } catch (RuntimeException e) {
            logger.debug("Could not read workflow '{}' status for its log line", workflowId, e);
            return "unknown (store read failed: " + e + ")";
        }
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

    // ── Internal: node-level Error (R20) ────────────────────────────────

    /**
     * Ends a local run on a node-level {@link Error} without recording an
     * outcome: an {@code ERROR} log (under the workflow MDC, which this thread
     * already carries) naming the error class, and
     * {@link EngineObserver#runAbandoned} with {@link AbandonReason#NODE_ERROR}.
     * Writes nothing and runs no compensation; the instance keeps its
     * recoverable status and {@code executeWorkflow}'s {@code finally} releases
     * the lock, so the recovery poller re-adopts it — on this node once the
     * cause clears, or on a healthy one.
     */
    private void handleNodeError(WorkflowContext ctx, Error e) {
        logger.error("Workflow '{}' abandoned its local run on a node-level error ({}) — NOT recorded as "
                        + "a workflow failure, no compensation run; the instance stays recoverable. This is "
                        + "a bad deploy or resource exhaustion on this node: fix or roll back the node, "
                        + "or terminate the workflow if it cannot be healed",
                ctx.workflowId(), e.getClass().getName(), e);
        emitRunAbandoned(ctx, AbandonReason.NODE_ERROR);
    }

    // ── Internal: store unavailable (R1/R2) ────────────────────────────

    /** Whether this run's store fault has been latched by the store-fault guard. */
    private static boolean storeUnavailableLatched(WorkflowContext ctx) {
        return ctx.cancellation().cause() == RunCancellation.Cause.STORE_UNAVAILABLE;
    }

    /**
     * Ends a local run because the store was unreachable, without recording
     * an outcome: one {@code WARN} naming the workflow and the latched detail,
     * and {@link EngineObserver#runAbandoned} with
     * {@link AbandonReason#STORE_UNAVAILABLE}. Writes nothing — the store is
     * the thing that is down — and runs no compensation; the instance keeps
     * its active status and {@code executeWorkflow}'s {@code finally} releases
     * the lock, so ordinary recovery resumes it once the store answers again.
     *
     * @param ctx   the abandoned run's context
     * @param cause what unwound the run: the {@code StoreUnavailableException},
     *              or whatever workflow code turned the latched one into
     */
    private void handleStoreUnavailable(WorkflowContext ctx, @Nullable Throwable cause) {
        // The latched detail already carries the SQLSTATE (StoreFaultGuard);
        // an unlatched fault gets the same diagnosis here.
        var detail = ctx.cancellation().reason();
        logger.warn("Workflow '{}' abandoned its local run — the store is unavailable ({}); NOT recorded as a "
                        + "workflow failure, no compensation run; the instance stays recoverable and "
                        + "recovery resumes it once the store answers",
                ctx.workflowId(), detail != null ? detail
                        : cause != null ? cause + " — " + StoreFaultGuard.diagnosis(cause) : "no cause");
        logger.debug("Store-unavailable abandon of workflow '{}'", ctx.workflowId(), cause);
        emitRunAbandoned(ctx, AbandonReason.STORE_UNAVAILABLE);
    }

    // ── Internal: stale-run stand-down (Issue 18) ──────────────────────

    /**
     * Records that a workflow's local run stood down because another runner
     * owns the workflow's progress, in either of the two forms that signal
     * takes:
     * <ul>
     *   <li>a {@link DuplicateEventException} — an event append collided with
     *       a concurrent runner's already-persisted event (the
     *       {@code (workflow_instance_id, sequence_number)} unique guard),
     *       including an activity's own result append: since C5
     *       {@code ActivityInvocationHandler} no longer adopts the winner's
     *       value but lets the duplicate unwind to here, because the winner is
     *       alive and ahead;</li>
     *   <li>a {@link StaleRunException} — a status write's fresh read found
     *       {@code COMPLETED} or {@code FAILED} written by another runner;</li>
     *   <li>a {@link FencedWriteException} — the store's lease fence (Issue 11)
     *       refused one of this run's writes because another node has adopted
     *       the workflow and bumped the row's {@code lease_epoch}; reported as
     *       {@link StandDownReason#LEASE_FENCED}, the other two as
     *       {@link StandDownReason#STALE_RUN}.</li>
     * </ul>
     * Deliberately writes nothing: the other runner's durable state — its
     * events, its terminal outcome, its compensation progress — is the record.
     * If no other runner exists, the instance is still active and recovery
     * replays it from the log.
     *
     * @param ctx the stale run's context
     * @param e   the {@link DuplicateEventException}, {@link StaleRunException}
     *            or {@link FencedWriteException} that unwound the run — the
     *            only three types the stand-down arms pass
     */
    private void handleStaleRunStandDown(WorkflowContext ctx, MaestroException e) {
        var reason = switch (e) {
            case DuplicateEventException _, StaleRunException _ -> StandDownReason.STALE_RUN;
            case FencedWriteException _ -> StandDownReason.LEASE_FENCED;
            default -> throw new IllegalArgumentException(
                    "not a stale-run signal: " + e.getClass().getName(), e);
        };
        var detail = switch (e) {
            case DuplicateEventException duplicate -> "append collision at sequence " + duplicate.sequenceNumber();
            case StaleRunException stale -> stale.detail();
            case FencedWriteException fenced -> fenced.staleDetail();
            default -> throw new IllegalArgumentException(
                    "not a stale-run signal: " + e.getClass().getName(), e);
        };
        var status = statusForLog(ctx.workflowId());
        logger.warn("Workflow '{}' stood down ({}): another runner owns its progress ({}; instance status "
                        + "now {}) — no failure recorded, no compensation run; the concurrent runner's "
                        + "durable state governs",
                ctx.workflowId(), reason, detail, status);
        emit("standDown", ctx.workflowId(), () ->
                observer.standDown(reason, ctx.workflowId(), detail));
    }

    // ── Internal: unknown-history stand-down (spec §C2) ────────────────

    /**
     * Records that a workflow's local run stood down because this node cannot
     * interpret the workflow's persisted history — an event type this build
     * does not define, or a stored payload it cannot read (written by a node
     * running a newer build during a mixed-version deploy window, or reshaped
     * by an incompatible change on a homogeneous fleet), or recorded history
     * the current code no longer matches.
     *
     * <p>Deliberately writes nothing. The instance keeps whatever recoverable
     * status this run found it in, and what heals it depends on the kind —
     * exactly as the WARN's closing clause ({@link #standDownRemedy}) says:
     * <ul>
     *   <li>{@code UNKNOWN_EVENT_TYPE} — only a newer build can have written
     *       it; an upgraded node adopts it through the ordinary
     *       lock-TTL/recovery-poller machinery.</li>
     *   <li>{@code UNKNOWN_EVENT_PAYLOAD} — if a newer node wrote it, that node
     *       adopts it on recovery; on a homogeneous fleet it is an incompatible
     *       payload change, healed only by rolling back, gating the change with
     *       {@code workflow.version()}, or terminating.</li>
     *   <li>{@code NONDETERMINISTIC_HISTORY} — the code no longer matches the
     *       recorded path; healed by rolling back the deploy or gating the
     *       change, never by waiting.</li>
     * </ul>
     * This is <b>never</b> recorded as a workflow failure and <b>never</b>
     * triggers compensation: doing either would mark a workflow whose history
     * is fine — perhaps perfectly healthy on the upgraded half of the fleet —
     * as {@code FAILED}, and unwind real work (refunds issued, reservations
     * released) that never failed.
     *
     * <p>The instance lock is released by {@code executeWorkflow}'s
     * {@code finally}, exactly as it is for a shutdown — this method
     * deliberately adds no early return that could bypass it.
     *
     * <p><b>Re-adoption churn is intended.</b> This node's recovery poller will
     * adopt and stand down the same instance once per poll until the remedy
     * for its kind lands (an upgraded node wins the lock race, or a rollback /
     * gated change / terminate ships). Each pass logs one WARN and increments
     * {@code maestro.standdown{reason}} — that steadily-rising counter <em>is</em>
     * the operator signal, so it is not deduplicated away.
     */
    private void handleUnknownHistoryStandDown(WorkflowContext ctx,
                                               UnknownWorkflowHistoryException e) {
        var status = statusForLog(ctx.workflowId());
        logger.warn("Workflow '{}' stood down at sequence {}: {} (instance status still {}) — no "
                        + "failure recorded, no compensation run; {}",
                ctx.workflowId(), e.sequenceNumber(), e.getMessage(), status, standDownRemedy(e.kind()));
        // R17: the WARN above names a payload root cause by class and field path
        // only (stored values must not reach log backends). The full chain —
        // including the deserializer's raw message — is available at DEBUG.
        logger.debug("Stand-down cause for workflow '{}'", ctx.workflowId(), e);
        emit("standDown", ctx.workflowId(), () ->
                observer.standDown(standDownReason(e.kind()), ctx.workflowId(), e.detail()));
    }

    /**
     * The closing clause of {@link #handleUnknownHistoryStandDown}'s WARN — what
     * an operator should expect or do next for this kind of stand-down.
     * Package-private so the wording is pinned without an SLF4J binding.
     */
    static String standDownRemedy(UnknownWorkflowHistoryException.Kind kind) {
        return switch (kind) {
            case UNKNOWN_EVENT_TYPE -> "an upgraded node will adopt it via recovery";
            // R18: an unreadable payload is not proof of a newer node — on a
            // homogeneous fleet it is the operator's own incompatible change,
            // and "an upgraded node will adopt it" would wait forever.
            case UNKNOWN_EVENT_PAYLOAD -> "if a newer node wrote it, that node adopts it on recovery; on a "
                    + "homogeneous fleet this is an incompatible payload change — roll back, gate the "
                    + "change with workflow.version(), or terminate (operations §10.4)";
            case NONDETERMINISTIC_HISTORY -> "roll back the deploy or gate the change behind workflow.version()";
        };
    }

    private static StandDownReason standDownReason(UnknownWorkflowHistoryException.Kind kind) {
        return switch (kind) {
            case UNKNOWN_EVENT_TYPE -> StandDownReason.UNKNOWN_EVENT_TYPE;
            case UNKNOWN_EVENT_PAYLOAD -> StandDownReason.UNKNOWN_EVENT_PAYLOAD;
            case NONDETERMINISTIC_HISTORY -> StandDownReason.NONDETERMINISTIC_HISTORY;
        };
    }

    // ── Internal: failure handling ─────────────────────────────────────

    /**
     * The single entry for "record FAILED and compensate", shared by the
     * {@code Exception} and non-control-flow {@code Error} arms of
     * {@link #executeWorkflow}. A stand-down signal landing while compensating
     * is routed to its own handler rather than recorded as a failure.
     */
    private void failAndCompensate(
            WorkflowContext ctx,
            WorkflowInstance instance,
            Throwable failure,
            CompensationStack compensationStack,
            boolean parallelCompensation
    ) {
        try {
            handleWorkflowFailure(ctx, instance, failure, compensationStack, parallelCompensation);
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
        } catch (UnknownWorkflowHistoryException unreadableDuringCompensation) {
            // A compensation replay read met history this build cannot
            // interpret. SagaManager rethrows instead of recording
            // COMPENSATION_STEP_FAILED (a stand-down is not a failed
            // reversal — nothing ran), the instance stays COMPENSATING —
            // active and recoverable — and an upgraded node finishes the
            // unwind. Nothing is written here.
            handleUnknownHistoryStandDown(ctx, unreadableDuringCompensation);
        } catch (DuplicateEventException | StaleRunException | FencedWriteException staleDuringCompensation) {
            // The duplicate (or the fence) landed while compensating a genuine
            // failure: another runner adopted the workflow and is compensating
            // it (or already finished). SagaManager rethrows instead of
            // recording COMPENSATION_STEP_FAILED, exactly like the shutdown
            // case; the instance stays COMPENSATING — active and
            // recoverable — and the winner's compensation run finishes the
            // job. Nothing is written here. A StaleRunException is the
            // status-write form: the COMPENSATING transition (or a status
            // write inside a compensation action) found the row already
            // finalised by another runner, which then stands as written. A
            // FencedWriteException is the lease fence's form (Issue 11): the
            // COMPENSATING transition or a compensation memo was refused
            // because the adopting node now holds the row's epoch.
            handleStaleRunStandDown(ctx, staleDuringCompensation);
        } catch (StoreUnavailableException storeDownDuringCompensation) {
            // The store failed while handling a genuine failure — the
            // COMPENSATING transition, a compensation's memo, or a stop point
            // (handleWorkflowFailure's checks below) after the guard latched
            // the run. Not a failed reversal and not a FAILED outcome: nothing
            // more is written, the instance stays active (COMPENSATING if the
            // unwind had begun) and recovery finishes the job.
            handleStoreUnavailable(ctx, storeDownDuringCompensation);
        } catch (Error nodeErrorDuringCompensation) {
            // R28: a node-level error from a compensation action — thrown by
            // the action itself, or by a compensation activity, which the
            // retry executor no longer wraps — means this node cannot run the
            // unwind, not that a reversal failed. Abandon loudly exactly like
            // the body-level node-error arm: nothing written, the instance
            // stays COMPENSATING (active, recoverable) for a healthy node to
            // finish. Without this arm the Error escaped the workflow thread
            // uncaught — no log line, no observer callback — and recovery
            // re-adopted it silently. Any other Error keeps propagating as
            // before (the control-flow signals are caught above).
            if (!NodeErrors.isNodeError(nodeErrorDuringCompensation)) {
                throw nodeErrorDuringCompensation;
            }
            handleNodeError(ctx, nodeErrorDuringCompensation);
        }
    }

    private void handleWorkflowFailure(
            WorkflowContext ctx,
            WorkflowInstance instance,
            Throwable failure,
            CompensationStack compensationStack,
            boolean parallelCompensation
    ) {
        // R33: a run already known to be stale (workflow code turned a
        // swallowed collision or StaleRunException into this "failure") must
        // neither compensate work the winner owns nor write FAILED over it.
        // The StaleRunException reaches failAndCompensate's stand-down arm.
        // Likewise a run whose store fault was latched: its
        // StoreUnavailableException reaches failAndCompensate's abandon arm.
        ctx.cancellation().throwIfStale(ctx.workflowId());
        ctx.cancellation().throwIfStoreUnavailable(ctx.workflowId());

        // Logged after the stale check: a stand-down loser is not a failure worth
        // an ERROR line (its stand-down is logged by its own handler).
        logger.error("Workflow '{}' failed: {}", ctx.workflowId(), failure.getMessage(), failure);

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
            } catch (SequenceSpaceExhaustedException noRoom) {
                // C4: compensate() pre-checks its block arithmetic before
                // anything durable changes, so nothing ran and nothing was
                // written — the instance status is untouched. Swallowing here
                // and falling through to the FAILED transition is deliberate:
                // letting it escape would leave an active zombie that every
                // recovery re-drives into the same overflow. The stack is
                // already drained here; SagaManager.compensate() logged the
                // drained entries' step names just before rethrowing.
                logger.error("Workflow '{}' failed and its registered compensations could NOT run — the "
                                + "sequence space is exhausted ({}). Finishing as FAILED; compensate manually "
                                + "(the step names are in the preceding SagaManager ERROR line).",
                        ctx.workflowId(), noRoom.getMessage(), noRoom);
            }
        }

        // Re-checked after compensating (R33): an unwind can make the run
        // stale too, and a stale run must not reach its terminal write. Outside
        // the try below, whose catch (Exception) would misreport it as a
        // failed terminal write. The same holds for a store fault latched
        // during the unwind: abandon, never write FAILED.
        ctx.cancellation().throwIfStale(ctx.workflowId());
        ctx.cancellation().throwIfStoreUnavailable(ctx.workflowId());

        // Update instance to FAILED
        try {
            var errorPayload = serializer.serialize(new WorkflowFailureDetail(
                    failure.getClass().getName(),
                    failure.getMessage(),
                    FailureAttribution.sequences(ctx, failure)
            ));
            if (transitionToTerminal(ctx, instance, WorkflowStatus.FAILED,
                    EventType.WORKFLOW_FAILED, errorPayload)) {
                // Winner-only, and emitted before the lifecycle publish for
                // the same reason as the COMPLETED transition above: the
                // durable outcome (status and event, one write) is already
                // in, so nothing that follows may decide whether it is
                // observed.
                emit("workflowFailed", ctx.workflowId(), () ->
                        observer.workflowFailed(observed(instance), failure.getClass().getName()));
                emit("workflowFinished", ctx.workflowId(), () ->
                        observer.workflowFinished(observed(instance), WorkflowStatus.FAILED,
                                elapsedSince(instance)));

                publishLifecycleEvent(instance, LifecycleEventType.WORKFLOW_FAILED, null);
            } else {
                // F4: converged loser on the failure path — same shape as the
                // COMPLETED case above.
                emitRunAbandoned(ctx, AbandonReason.CONVERGED);
            }
        } catch (FencedWriteException | StaleRunException | DuplicateEventException
                 | StoreUnavailableException routed) {
            // None of these is a TERMINAL_WRITE_FAILED. The first three are not
            // persistence faults: another runner owns this workflow's progress
            // (the lease fence refused the FAILED write, a fresh read found the
            // row finalised, or the terminal slot is already taken). Rethrow so
            // failAndCompensate's stand-down arm reports it as LEASE_FENCED /
            // STALE_RUN — the generic arm below would log an ERROR and emit
            // TERMINAL_WRITE_FAILED, misdescribing a routine hand-off as a
            // store fault. A StoreUnavailableException (the database is down)
            // is transient, not an operator problem: it takes
            // failAndCompensate's STORE_UNAVAILABLE abandon at WARN.
            throw routed;
        } catch (Exception updateError) {
            logger.error("Failed to update workflow '{}' status to FAILED",
                    ctx.workflowId(), updateError);
            // F4: the terminal write itself failed, so no terminal callback
            // fired and the run is over on this thread regardless. Emitted
            // inside the catch so it cannot be skipped by the failure it
            // reports on. Issue 26: the write is atomic, so this arm wrote
            // NOTHING — the instance stays active and recoverable. The
            // stale-run signals (fence, finalised row, taken terminal slot)
            // are routed past this arm above, so what reaches here is a
            // genuine store fault other than an outage (which is routed to
            // the STORE_UNAVAILABLE abandon above).
            emitRunAbandoned(ctx, AbandonReason.TERMINAL_WRITE_FAILED);
        }
    }

    // ── Internal: terminal transition ──────────────────────────────────

    /**
     * Finalises a workflow — its terminal status <b>and</b> its terminal event
     * in one atomic {@link WorkflowStore#completeInstance} write — converging
     * with any other writer that touched the instance row first. This is the
     * only terminal write path.
     *
     * <p><b>One write (Issue 26).</b> The status and the event used to be two
     * independent store calls, status first, with the event append's failure
     * swallowed: a crash or an error between them left a terminal status with
     * no terminal event, forever. Now both land or neither does, so a reader
     * never sees one without the other, and a failed write leaves the instance
     * active and recoverable rather than finalised with a short log.
     *
     * <p><b>Convergence.</b> The instance is read, stamped with
     * {@code version + 1} and written. Anything that writes the row in between
     * invalidates that version: another node running the same workflow (the
     * documented no-lock-backend degradation, a lock lost mid-run, or a stale
     * lock on a fresh start), or simply another status transition on this
     * node. Before this converged, the resulting version conflict escaped into
     * the caller's failure handling and a workflow that had <em>succeeded</em>
     * was recorded as {@code FAILED} — with the conflict message as its
     * output, and running a saga's compensations after a successful run. A
     * persistence conflict is not a workflow outcome: it is resolved by
     * retrying against a fresh read, and by standing down when another runner
     * has already reached a terminal state.
     *
     * <p>The terminal event's sequence is allocated <em>once</em>, before the
     * retry loop, so every attempt targets the same slot. A
     * {@link DuplicateEventException} from the store (that slot is already
     * taken — another runner finalised first) wrote nothing and propagates to
     * the caller.
     *
     * @param ctx          the workflow context (supplies the terminal event's sequence)
     * @param fallback     the instance to fall back on if the row cannot be re-read
     * @param status       the terminal status to write
     * @param terminalType the matching terminal event type
     *                     ({@code WORKFLOW_COMPLETED} or {@code WORKFLOW_FAILED})
     * @param output       the output or error payload to record on the row and the event
     * @return {@code true} if this call wrote the status and the event;
     *         {@code false} if another runner had already finalised the
     *         workflow, or if the conflict persisted across every attempt — in
     *         which case nothing was written and the instance is deliberately
     *         left non-terminal and recoverable
     * @throws DuplicateEventException if the terminal event's sequence is
     *         already occupied (nothing was written)
     */
    private boolean transitionToTerminal(
            WorkflowContext ctx, WorkflowInstance fallback,
            WorkflowStatus status, EventType terminalType, @Nullable JsonNode output
    ) {
        // The row's event_sequence keeps its established meaning — the last
        // sequence the run consumed before finalising — and the terminal event
        // takes the next slot, exactly as when they were two writes.
        var lastStepSeq = ctx.currentSequence();
        var seq = WorkflowContextInternals.nextSequence(ctx);
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
                    .eventSequence(lastStepSeq)
                    .version(latest.version() + 1)
                    .build();
            var terminalEvent = new WorkflowEvent(
                    UUID.randomUUID(), ctx.workflowInstanceId(), seq, terminalType, null, output, now);
            boolean written;
            try {
                // Fenced on the run's lease (Issue 11): a run that has been
                // superseded by another node's adoption must not finalise the
                // workflow — the adopter's run owns the outcome now.
                written = store.completeInstance(updated, terminalEvent, ctx.lease());
            } catch (FencedWriteException fenced) {
                ctx.cancellation().requestStale(fenced.staleDetail());
                throw fenced;
            }
            if (written) {
                return true;
            }
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

    // ── Internal: event and lifecycle helpers ──────────────────────────

    /** Builds the identity-only observation record for an instance. */
    private WorkflowInfo observed(WorkflowInstance instance) {
        return new WorkflowInfo(instance.workflowId(), instance.workflowType(), serviceName);
    }

    /**
     * Wall time from the instance row's {@code startedAt} to now, for
     * {@link EngineObserver#workflowFinished}. Uses {@link Instant#now()}
     * because {@link #transitionToTerminal} stamps {@code completedAt} with it;
     * floored at {@link Duration#ZERO} so a clock step backwards cannot report a
     * negative duration.
     */
    private static Duration elapsedSince(WorkflowInstance instance) {
        var elapsed = Duration.between(instance.startedAt(), Instant.now());
        return elapsed.isNegative() ? Duration.ZERO : elapsed;
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
     * builder accepts <em>any</em> {@code EngineObserver}, so nothing
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

    /**
     * Builds and submits a lifecycle event for off-thread publishing.
     *
     * <p>Never runs {@link WorkflowMessaging#publishLifecycleEvent} on the
     * calling thread: that call is free to block (Kafka's producer, for
     * example, blocks synchronously fetching metadata for a missing topic, up
     * to {@code max.block.ms}), and the calling thread here is a workflow's
     * own virtual thread — most notably during {@code startWorkflow} itself.
     * The actual publish, and its failure handling, happens on
     * {@link #lifecycleEventPublisher} via {@link #lifecycleEventSink}; see
     * {@link LifecycleEventPublisher} for the backpressure and shutdown contract.
     */
    private void publishLifecycleEvent(
            WorkflowInstance instance, LifecycleEventType eventType,
            @Nullable String stepName
    ) {
        if (messaging == null || !lifecycleEventsEnabled) return;
        lifecycleEventSink.publish(new WorkflowLifecycleEvent(
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
    }

    /**
     * The one off-thread sink shared by this executor and every component it
     * builds: each event is handed to {@link #lifecycleEventPublisher}'s bounded
     * queue and published there, with the SPI's never-interrupt failure
     * handling. {@link LifecycleEventSink#NOOP} when there is no messaging or
     * publishing is disabled, so disabled deployments queue nothing.
     */
    private LifecycleEventSink buildLifecycleEventSink(
            @Nullable WorkflowMessaging messaging, boolean enabled
    ) {
        if (messaging == null || !enabled) {
            return LifecycleEventSink.NOOP;
        }
        return event -> lifecycleEventPublisher.submit(() -> {
            try {
                messaging.publishLifecycleEvent(event);
            } catch (Exception e) {
                // SPI contract: lifecycle event failures must not interrupt workflow execution
                logger.warn("Failed to publish {} lifecycle event for workflow '{}'",
                        event.eventType(), event.workflowId(), e);
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
     * Tracks a currently running workflow for shutdown coordination, query
     * dispatch, and — through {@code cancellation}, the same instance the run's
     * {@link WorkflowContext} holds — terminate/shutdown requests to the run.
     * {@code workflowImpl} is the object the registration's factory built for
     * this run alone; queries against the run are invoked on it.
     * {@code launched} is counted down once the launch has started
     * {@code thread}, or has failed to and unregistered the run — so a
     * shutdown drain never joins a thread that is not started yet.
     */
    record RunningWorkflow(Thread thread, WorkflowInstance instance, String workflowType,
                           Object workflowImpl, RunCancellation cancellation, CountDownLatch launched) {}
}
