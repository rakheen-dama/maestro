package io.b2mash.maestro.test;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.engine.ActivityProxyFactory;
import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.engine.WorkflowRegistration;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.observe.CompositeEngineObserver;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * In-memory Maestro engine for fast, deterministic workflow tests.
 *
 * <p>Constructs a real {@link WorkflowExecutor} backed by in-memory SPI
 * implementations. No Postgres or Kafka required.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var env = TestWorkflowEnvironment.create();
 * env.registerActivities(PaymentActivities.class, new MockPaymentActivities());
 *
 * var handle = env.startWorkflow(OrderWorkflow.class, new OrderInput("item-1"));
 * handle.signal("payment.result", new PaymentResult(true));
 * var result = handle.getResult(OrderResult.class, Duration.ofSeconds(5));
 *
 * env.shutdown();
 * }</pre>
 *
 * <h2>Time Control</h2>
 * <p>Workflows that call {@code sleep()} or {@code awaitSignal(..., timeout)}
 * create durable timers, armed from this environment's
 * {@link ControllableClock} rather than the wall clock. Use
 * {@link #advanceTime(Duration)} to fire due timers deterministically
 * without waiting for real time to pass; {@code workflow.currentTime()}
 * reads the same clock.
 *
 * <h2>Crash and recovery</h2>
 * <p>A run that executes once, start to finish, never reads its own history
 * back — so a result type Jackson cannot deserialise, an activity argument
 * that depends on map iteration order, or an ungated code change passes every
 * such test and fails only when production recovers a workflow. The
 * environment can therefore crash its node part-way through a run and start
 * a fresh one over the same store, exactly as a restarted service would:
 * <pre>{@code
 * var crash = env.crashAfter(2);          // die once two activity results are persisted
 * var handle = env.startWorkflow(OrderWorkflow.class, input);
 * crash.recover();                        // a new node re-adopts and replays the workflow
 *
 * handle.getResult(OrderResult.class, Duration.ofSeconds(5));
 * assertEquals(2, handle.replayedSteps().size());
 * }</pre>
 * <ul>
 *   <li>{@link #crashAfter(int)}, {@link #crashAfterPersisting(Predicate)}:
 *       the matching event is persisted, then the node dies — the step replays
 *       on recovery.</li>
 *   <li>{@link #crashBeforePersisting(Predicate)}: the node dies just before
 *       the event is written — an activity that already executed is executed
 *       again on recovery, which is why activities must be idempotent.</li>
 *   <li>{@link #crashNow()}: the node dies immediately — typically while its
 *       workflows are parked on a signal or a timer.</li>
 *   <li>{@link #recover()} / {@link #recover(Class[])}: retire the dead node and
 *       start a fresh one (optionally running new versions of the workflow
 *       classes, matched by workflow type name) that re-adopts every active
 *       workflow.</li>
 * </ul>
 * <p>A dead node's workflow threads cannot write to the store; they stop at
 * their next write and record nothing. The test thread still can, so
 * {@link TestWorkflowHandle#signal} and {@link #advanceTime(Duration)} while
 * the node is down model a signal or a timer that lands during an outage.
 * {@link #startWorkflow} on a dead node throws until {@link #recover()} is
 * called. Only one crash can be armed at a time. One node runs at a time —
 * concurrent live nodes racing for the same workflow are out of scope.
 *
 * <p>The instance locks live in {@link InMemoryDistributedLock}, which expires
 * them on the <em>wall</em> clock, deliberately: on the controllable clock,
 * {@code advanceTime(Duration.ofDays(1))} would expire every live run's lock
 * at once. A crashed run releases its lock as it unwinds, so recovery never
 * waits for a TTL.
 *
 * <h2>Thread Safety</h2>
 * <p>The environment is thread-safe. Workflows run on virtual threads
 * internally, just like in production.
 *
 * @see TestWorkflowHandle
 * @see SimulatedCrash
 * @see MaestroTest
 */
public final class TestWorkflowEnvironment {

    private static final Logger logger = LoggerFactory.getLogger(TestWorkflowEnvironment.class);
    private static final String SERVICE_NAME = "maestro-test";

    // The engine defaults (WorkflowExecutor's short constructors), spelled out
    // because the environment uses the full constructor to attach its observer.
    private static final String LOCK_KEY_PREFIX = "maestro:lock:";
    private static final Duration INSTANCE_LOCK_TTL = Duration.ofSeconds(30);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration WAKE_RECHECK_INTERVAL = Duration.ofSeconds(30);

    private final ControllableClock clock;
    private final InMemoryWorkflowStore store;
    private final InMemoryWorkflowMessaging messaging;
    private final InMemoryDistributedLock lock;
    private final InMemorySignalNotifier signalNotifier;
    private final PayloadSerializer serializer;
    private final ActivityProxyFactory proxyFactory;
    private final RetryExecutor retryExecutor;
    private final RunRecorder recorder;
    private final EngineObserver observer;

    // activityInterface → activityImpl; read by workflow factories on whichever thread builds a run
    private final Map<Class<?>, Object> activityImpls = new ConcurrentHashMap<>();
    private final AtomicInteger workflowCounter = new AtomicInteger(0);

    // Guards node replacement and the per-node query registration below.
    private final Object nodeLock = new Object();
    // Every node ever built, in order; the last is current.
    private final List<Node> nodes = new CopyOnWriteArrayList<>();
    private volatile Node current;
    // Workflow type → the class to run it with on the next node; guarded by nodeLock.
    private final Map<String, Class<?>> workflowClasses = new LinkedHashMap<>();

    private final AtomicReference<@Nullable Armed> armed = new AtomicReference<>();

    private TestWorkflowEnvironment(ObjectMapper objectMapper) {
        this.clock = new ControllableClock();
        this.store = new InMemoryWorkflowStore();
        this.messaging = new InMemoryWorkflowMessaging();
        this.lock = new InMemoryDistributedLock();
        this.signalNotifier = new InMemorySignalNotifier();
        this.serializer = new PayloadSerializer(objectMapper);
        this.proxyFactory = new ActivityProxyFactory();
        this.retryExecutor = new RetryExecutor();
        this.recorder = new RunRecorder();
        this.observer = CompositeEngineObserver.of(List.of(recorder));
        this.current = newNode();
    }

    // ── Factory methods ──────────────────────────────────────────────────

    /**
     * Creates a test environment with a default Jackson ObjectMapper.
     *
     * @return a new environment with one live node
     */
    public static TestWorkflowEnvironment create() {
        return new TestWorkflowEnvironment(JsonMapper.builder().build());
    }

    /**
     * Creates a test environment with a custom ObjectMapper.
     *
     * @param objectMapper the Jackson 3 ObjectMapper to use for serialization
     * @return a new environment with one live node
     */
    public static TestWorkflowEnvironment create(ObjectMapper objectMapper) {
        return new TestWorkflowEnvironment(objectMapper);
    }

    // ── Activity registration ────────────────────────────────────────────

    /**
     * Registers an activity implementation by its interface type.
     *
     * <p>Workflow objects are wired when a run is built, so registering a
     * different implementation before {@link #recover()} gives the recovering
     * node that implementation — a differently configured node.
     *
     * @param activityInterface the activity interface (e.g., {@code PaymentActivities.class})
     * @param activityImpl      the implementation (real or mock)
     * @param <T>               the activity interface type
     */
    public <T> void registerActivities(Class<T> activityInterface, T activityImpl) {
        if (!activityInterface.isInterface()) {
            throw new IllegalArgumentException(
                    "First argument must be an interface, got: " + activityInterface.getName());
        }
        activityImpls.put(activityInterface, activityImpl);
    }

    /**
     * Registers activity implementations, auto-detecting their interfaces.
     *
     * <p>Scans each bean's interfaces for the {@link Activity @Activity}
     * annotation. If exactly one annotated interface is found, it is used.
     * If none are found, the first implemented interface is used.
     *
     * @param activityBeans one or more activity implementation instances
     */
    public void registerActivities(Object... activityBeans) {
        for (var bean : activityBeans) {
            var iface = detectActivityInterface(bean);
            activityImpls.put(iface, bean);
        }
    }

    // ── Workflow start ───────────────────────────────────────────────────

    /**
     * Starts a workflow with an auto-generated workflow ID.
     *
     * @param workflowClass the workflow class (annotated with {@code @DurableWorkflow})
     * @param input         the workflow input, or {@code null}
     * @return a handle to the running workflow
     * @throws IllegalStateException if the node has crashed and not been recovered
     */
    public TestWorkflowHandle startWorkflow(Class<?> workflowClass, @Nullable Object input) {
        var annotation = requireDurableWorkflowAnnotation(workflowClass);
        var workflowType = resolveWorkflowType(annotation, workflowClass);
        var workflowId = workflowType + "-" + workflowCounter.incrementAndGet();
        return startWorkflow(workflowId, workflowClass, input);
    }

    /**
     * Starts a workflow with a specific workflow ID.
     *
     * @param workflowId    the business workflow ID
     * @param workflowClass the workflow class (annotated with {@code @DurableWorkflow})
     * @param input         the workflow input, or {@code null}
     * @return a handle to the running workflow
     * @throws IllegalStateException if the node has crashed and not been recovered
     */
    public TestWorkflowHandle startWorkflow(String workflowId, Class<?> workflowClass,
                                            @Nullable Object input) {
        var annotation = requireDurableWorkflowAnnotation(workflowClass);
        var workflowType = resolveWorkflowType(annotation, workflowClass);
        var taskQueue = annotation.taskQueue();
        var workflowMethod = findWorkflowMethod(workflowClass);

        // Fail fast — before the instance row exists — on the wiring errors the
        // factory would otherwise only hit inside the engine (where they arrive
        // wrapped in WorkflowInstantiationException), keeping this method's
        // documented exceptions unchanged.
        validateWiring(workflowClass);

        Node node;
        synchronized (nodeLock) {
            node = current;
            if (node.view.isDead()) {
                throw new IllegalStateException(
                        "The simulated node has crashed — call recover() (or crash.recover()) before "
                                + "starting workflow '%s'".formatted(workflowId));
            }
            workflowClasses.put(workflowType, workflowClass);
            // Register query methods once per workflow type per node — the
            // registry rejects a second registration.
            node.registerQueries(workflowType, workflowClass);
        }

        // One workflow object per run (F1): the executor calls this factory on
        // every launch of this workflow, each call building and wiring a fresh
        // instance — exactly as the Spring starter does with prototype beans.
        var instanceId = node.executor.startWorkflow(
                workflowId, workflowType, taskQueue, input, workflowFactory(workflowClass), workflowMethod);

        logger.debug("Test: started workflow '{}' (type={}, instanceId={})",
                workflowId, workflowType, instanceId);

        return new TestWorkflowHandle(workflowId, instanceId, this, store, serializer, recorder);
    }

    // ── Crash and recovery ───────────────────────────────────────────────

    /**
     * Arms a crash that fires once {@code completedActivities} activity results
     * have been persisted ({@code ACTIVITY_COMPLETED} events, counted across
     * all workflows from now on, compensation activities included). The
     * {@code completedActivities}-th result is persisted before the node dies,
     * so it replays on recovery.
     *
     * @param completedActivities how many live activity completions to allow; at least 1
     * @return the armed crash, to {@linkplain SimulatedCrash#recover() recover} from
     * @throws IllegalArgumentException if {@code completedActivities < 1}
     * @throws IllegalStateException    if a crash is already armed
     */
    public SimulatedCrash crashAfter(int completedActivities) {
        if (completedActivities < 1) {
            throw new IllegalArgumentException(
                    "crashAfter needs at least one completed activity, got " + completedActivities);
        }
        var seen = new AtomicInteger();
        return arm("after %d completed activit%s".formatted(completedActivities,
                        completedActivities == 1 ? "y" : "ies"),
                (event, persisted) -> persisted
                        && event.eventType() == EventType.ACTIVITY_COMPLETED
                        && seen.incrementAndGet() == completedActivities);
    }

    /**
     * Arms a crash that fires right after the first event matching
     * {@code eventMatcher} is persisted: the event survives and replays on
     * recovery. Offered every event a workflow thread writes —
     * activity outcomes, signal memos, timer memos, compensation records,
     * version markers, and the terminal {@code WORKFLOW_COMPLETED}/{@code _FAILED}.
     *
     * @param eventMatcher selects the event
     * @return the armed crash
     * @throws IllegalStateException if a crash is already armed
     */
    public SimulatedCrash crashAfterPersisting(Predicate<WorkflowEvent> eventMatcher) {
        return arm("after persisting a matching event",
                (event, persisted) -> persisted && eventMatcher.test(event));
    }

    /**
     * Arms a crash that fires just before the first event matching
     * {@code eventMatcher} is written: the event is lost. For an activity
     * outcome this means the activity executed but its result was never
     * recorded, so the recovering node executes it again — the at-least-once
     * window every activity must tolerate.
     *
     * @param eventMatcher selects the event
     * @return the armed crash
     * @throws IllegalStateException if a crash is already armed
     */
    public SimulatedCrash crashBeforePersisting(Predicate<WorkflowEvent> eventMatcher) {
        return arm("before persisting a matching event",
                (event, persisted) -> !persisted && eventMatcher.test(event));
    }

    /**
     * Kills the node now. Workflows parked on it stay parked until
     * {@link #recover()}; a workflow mid-activity finishes the activity and then
     * stops at its next store write, recording nothing.
     *
     * @return the (already fired) crash
     * @throws IllegalStateException if a crash is already armed
     */
    public SimulatedCrash crashNow() {
        if (armed.get() != null) {
            throw alreadyArmed();
        }
        var crash = new SimulatedCrash(this, "crashNow()");
        var node = current;
        node.view.kill("crashNow()");
        crash.trigger(null, null, node.executor);
        return crash;
    }

    /**
     * Restarts the node: retires the current one (dead or not) and starts a
     * fresh executor over the same store, lock, messaging and clock, which
     * re-adopts every active workflow — replaying each one's history through
     * the production recovery path.
     *
     * <p>The retiring node is shut down synchronously: its parked workflows
     * unwind writing nothing, and a workflow still executing an activity is
     * waited for (up to the engine's 30-second shutdown timeout) and then stops
     * at its next write.
     *
     * @return the number of workflows the new node re-adopted
     */
    public int recover() {
        return recover(new Class<?>[0]);
    }

    /**
     * Restarts the node onto new code: like {@link #recover()}, but each given
     * class replaces the workflow class previously started under the same
     * {@code @DurableWorkflow} type name — a deploy of a new workflow version.
     * Use it to check that a change is safe for in-flight workflows (gated by
     * {@code workflow.version()}) or to see the stand-down an ungated one causes.
     *
     * @param workflowClasses the replacement workflow classes
     * @return the number of workflows the new node re-adopted
     * @throws IllegalArgumentException if a class is not a valid workflow class
     */
    public int recover(Class<?>... workflowClasses) {
        Node node;
        Map<String, WorkflowRegistration> registrations;
        synchronized (nodeLock) {
            for (var workflowClass : workflowClasses) {
                var annotation = requireDurableWorkflowAnnotation(workflowClass);
                findWorkflowMethod(workflowClass);
                validateWiring(workflowClass);
                this.workflowClasses.put(resolveWorkflowType(annotation, workflowClass), workflowClass);
            }
            var retiring = current;
            retiring.view.kill("recover()");
            retiring.executor.shutdown();

            node = newNode();
            current = node;
            registrations = new LinkedHashMap<>();
            for (var entry : this.workflowClasses.entrySet()) {
                var type = entry.getKey();
                var workflowClass = entry.getValue();
                var annotation = requireDurableWorkflowAnnotation(workflowClass);
                registrations.put(type, new WorkflowRegistration(type, annotation.taskQueue(),
                        workflowFactory(workflowClass), findWorkflowMethod(workflowClass)));
                node.registerQueries(type, workflowClass);
            }
        }
        var adopted = node.executor.recoverWorkflows(registrations);
        logger.debug("Test: recovered onto node {} — re-adopted {} workflow(s)", nodes.size(), adopted);
        return adopted;
    }

    // ── Time control ─────────────────────────────────────────────────────

    /**
     * Advances the test clock and fires any timers that become due.
     *
     * <p>Workflows that call {@code workflow.sleep(duration)} or
     * {@code workflow.awaitSignal(name, type, timeout)} create durable timers,
     * armed from the test clock: a timer armed at {@code t} with duration
     * {@code d} has {@code fireAt = t + d}, so advancing by exactly {@code d}
     * fires it — no margin needed (R59). This method advances the clock to
     * a target instant and fires every timer whose {@code fireAt} is at or
     * before that target.
     *
     * <p>The target is fixed when the call starts. A workflow woken by this
     * call that parks again arms its next timer from the advanced clock, at
     * {@code target + d}; for any positive {@code d} that is after the target,
     * so this call never fires it — the next {@code advanceTime} does. One
     * call therefore fires only the timers already due at its target and
     * never chains on into a positive-duration timer a woken workflow arms.
     * A zero- or negative-duration timer armed by a woken workflow is due at
     * once: whether this call or the next fires it depends on thread timing.
     * The loop repeats while timers due by the target remain (more than one
     * batch, or such a timer).
     *
     * <p>While the node is crashed the timer rows still fire (the test thread
     * reaches the store); the dead node's workflows cannot record the wake,
     * and the recovering node's replay finds the fired row and records it.
     *
     * @param duration the amount to advance (must be non-negative)
     */
    public void advanceTime(Duration duration) {
        clock.advance(duration);
        fireAllDueTimers(clock.now());
    }

    /**
     * Returns the current test clock time.
     *
     * @return the controllable clock's current instant
     */
    public Instant currentTime() {
        return clock.now();
    }

    // ── Signal pre-delivery ──────────────────────────────────────────────

    /**
     * Stores a signal before the workflow starts (self-recovery test).
     *
     * <p>The signal is persisted with a {@code null} workflow instance ID.
     * When the workflow starts, it adopts all orphaned signals matching
     * its workflow ID.
     *
     * @param workflowId  the target workflow's business ID
     * @param signalName  the signal name
     * @param payload     the signal payload, or {@code null}
     */
    public void preDeliverSignal(String workflowId, String signalName, @Nullable Object payload) {
        var jsonPayload = payload != null ? serializer.serialize(payload) : null;
        var signal = new WorkflowSignal(
                UUID.randomUUID(),
                null,
                workflowId,
                signalName,
                jsonPayload,
                false,
                Instant.now(),
                null,
                null // no message id: every pre-delivery is a distinct signal
        );
        store.saveSignal(signal);
    }

    // ── Accessors for advanced assertions ────────────────────────────────

    /**
     * Returns the underlying in-memory store for advanced assertions.
     *
     * @return the store shared by every node of this environment
     */
    public InMemoryWorkflowStore getStore() {
        return store;
    }

    /**
     * Returns the underlying in-memory messaging for lifecycle event assertions.
     *
     * @return the messaging shared by every node of this environment
     */
    public InMemoryWorkflowMessaging getMessaging() {
        return messaging;
    }

    /**
     * Returns the controllable clock.
     *
     * @return the clock timers are armed on
     */
    public ControllableClock getClock() {
        return clock;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    /**
     * Shuts down every node this environment built — the current one and any
     * retired by {@link #recover()} — waiting for in-flight workflows.
     */
    public void shutdown() {
        for (var node : nodes) {
            node.executor.shutdown();
        }
    }

    // ── Internal: nodes ──────────────────────────────────────────────────

    /** @return the executor of the current node — the one handles route signals and queries to */
    WorkflowExecutor currentExecutor() {
        return current.executor;
    }

    /** @return every node's executor, oldest first (for tests of this package) */
    List<WorkflowExecutor> nodeExecutors() {
        return nodes.stream().map(n -> n.executor).toList();
    }

    private Node newNode() {
        var node = new Node();
        nodes.add(node);
        return node;
    }

    /**
     * One simulated service instance: an executor over its own
     * {@link NodeStoreView} of the shared store.
     */
    private final class Node implements NodeStoreView.CrashPolicy {
        final NodeStoreView view;
        final WorkflowExecutor executor;
        // Workflow types whose @QueryMethods this node's executor knows; guarded by nodeLock.
        private final Set<String> queryTypes = new HashSet<>();

        Node() {
            this.view = NodeStoreView.over(store, this);
            // Deadlines arm on the test clock, not the wall clock (R59): a timer
            // armed at clock.now() + d fires on exactly advanceTime(d).
            this.executor = new WorkflowExecutor(
                    view.store(), lock, messaging, signalNotifier, serializer, SERVICE_NAME,
                    LOCK_KEY_PREFIX, INSTANCE_LOCK_TTL, true, SHUTDOWN_TIMEOUT, WAKE_RECHECK_INTERVAL,
                    observer, Map.of(), clock);
        }

        void registerQueries(String workflowType, Class<?> workflowClass) {
            if (queryTypes.add(workflowType)) {
                executor.registerQueries(workflowType, workflowClass);
            }
        }

        @Override
        public @Nullable String before(WorkflowEvent event) {
            return fire(this, event, false);
        }

        @Override
        public @Nullable String after(WorkflowEvent event) {
            return fire(this, event, true);
        }
    }

    // ── Internal: crash plans ────────────────────────────────────────────

    /** Decides, for one event either side of its write, whether the armed crash fires. */
    @FunctionalInterface
    private interface CrashTrigger {
        boolean fires(WorkflowEvent event, boolean persisted);
    }

    private record Armed(SimulatedCrash crash, CrashTrigger trigger) {
    }

    private SimulatedCrash arm(String description, CrashTrigger trigger) {
        var crash = new SimulatedCrash(this, description);
        if (!armed.compareAndSet(null, new Armed(crash, trigger))) {
            throw alreadyArmed();
        }
        return crash;
    }

    private IllegalStateException alreadyArmed() {
        return new IllegalStateException(("A crash is already armed (%s) — recover from it, or let "
                + "awaitCrash() time out and disarm it, before arming another").formatted(armed.get()));
    }

    /** Disarms {@code crash} if it is the armed one (it was never reached). */
    void disarm(SimulatedCrash crash) {
        armed.updateAndGet(a -> a != null && a.crash() == crash ? null : a);
    }

    /**
     * Runs on a workflow thread of {@code node} for each event it writes.
     *
     * @return the crash reason if the armed crash fires now, else {@code null}
     */
    private @Nullable String fire(Node node, WorkflowEvent event, boolean persisted) {
        var plan = armed.get();
        if (plan == null || !plan.trigger().fires(event, persisted)) {
            return null;
        }
        if (!armed.compareAndSet(plan, null)) {
            return null;    // another thread fired it first
        }
        var workflowId = WorkflowContext.scopedValue().isBound()
                ? WorkflowContext.scopedValue().get().workflowId()
                : null;
        plan.crash().trigger(event, workflowId, node.executor);
        return "%s — %s %s seq %d (%s) of workflow '%s'".formatted(plan.crash(),
                persisted ? "after persisting" : "before persisting",
                event.eventType(), event.sequenceNumber(), event.stepName(), workflowId);
    }

    // ── Internal: timer firing ───────────────────────────────────────────

    private void fireAllDueTimers(Instant target) {
        List<WorkflowTimer> due;
        int safetyLimit = 1000;
        int iterations = 0;

        while (!(due = store.getDueTimers(target, 100)).isEmpty()) {
            var executor = current.executor;
            for (var timer : due) {
                executor.fireTimer(timer.workflowId(), timer.timerId(), timer.id());
            }
            // Brief yield to let virtual threads process the unpark
            Thread.yield();

            if (++iterations > safetyLimit) {
                throw new IllegalStateException(
                        "advanceTime fired over %d timer batches — possible infinite timer loop"
                                .formatted(safetyLimit));
            }
        }
    }

    // ── Internal: annotation introspection ───────────────────────────────

    private static DurableWorkflow requireDurableWorkflowAnnotation(Class<?> workflowClass) {
        var annotation = workflowClass.getAnnotation(DurableWorkflow.class);
        if (annotation == null) {
            throw new IllegalArgumentException(
                    "Class %s is not annotated with @DurableWorkflow".formatted(workflowClass.getName()));
        }
        return annotation;
    }

    private static String resolveWorkflowType(DurableWorkflow annotation, Class<?> workflowClass) {
        return annotation.name().isEmpty() ? workflowClass.getSimpleName() : annotation.name();
    }

    private static Method findWorkflowMethod(Class<?> workflowClass) {
        Method found = null;
        for (var method : workflowClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(WorkflowMethod.class)) {
                if (found != null) {
                    throw new IllegalArgumentException(
                            "Class %s has multiple @WorkflowMethod annotations. Exactly one is required."
                                    .formatted(workflowClass.getName()));
                }
                found = method;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException(
                    "Class %s has no @WorkflowMethod annotated method".formatted(workflowClass.getName()));
        }
        return found;
    }

    /**
     * Builds and wires one workflow object per call. The node is read when the
     * run is built — not when the workflow was first started — so a run
     * re-adopted by a recovering node gets activity proxies over that node's
     * store view and the activity implementations registered at that moment.
     */
    private Supplier<Object> workflowFactory(Class<?> workflowClass) {
        return () -> {
            var workflowImpl = instantiate(workflowClass);
            wireActivityStubs(workflowImpl, current);
            return workflowImpl;
        };
    }

    private static Object instantiate(Class<?> workflowClass) {
        try {
            var constructor = workflowClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(
                    "Class %s must have a no-arg constructor".formatted(workflowClass.getName()), e);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(
                    "Failed to instantiate workflow class " + workflowClass.getName(), e);
        }
    }

    /**
     * Checks, without instantiating anything, that {@code workflowClass} has a
     * no-arg constructor and that every {@link ActivityStub} field has a
     * registered implementation.
     */
    private void validateWiring(Class<?> workflowClass) {
        try {
            workflowClass.getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(
                    "Class %s must have a no-arg constructor".formatted(workflowClass.getName()), e);
        }
        for (var field : getAllFields(workflowClass)) {
            if (field.isAnnotationPresent(ActivityStub.class)) {
                requireActivityImpl(field, workflowClass);
            }
        }
    }

    private Object requireActivityImpl(Field field, Class<?> workflowClass) {
        var activityInterface = field.getType();
        if (!activityInterface.isInterface()) {
            throw new IllegalArgumentException(
                    "@ActivityStub field '%s' in %s must be an interface type, got: %s"
                            .formatted(field.getName(), workflowClass.getSimpleName(),
                                    activityInterface.getName()));
        }
        var activityImpl = activityImpls.get(activityInterface);
        if (activityImpl == null) {
            throw new IllegalStateException(
                    "No activity implementation registered for %s. "
                            .formatted(activityInterface.getName())
                            + "Call registerActivities(%s.class, impl) before startWorkflow()."
                                    .formatted(activityInterface.getSimpleName()));
        }
        return activityImpl;
    }

    @SuppressWarnings("unchecked")
    private void wireActivityStubs(Object workflowImpl, Node node) {
        for (var field : getAllFields(workflowImpl.getClass())) {
            var stub = field.getAnnotation(ActivityStub.class);
            if (stub == null) {
                continue;
            }

            var activityInterface = field.getType();
            var activityImpl = requireActivityImpl(field, workflowImpl.getClass());

            var retryPolicy = RetryPolicy.fromAnnotation(stub.retryPolicy());
            var timeout = Duration.parse(stub.startToCloseTimeout());

            @SuppressWarnings("rawtypes")
            var proxy = proxyFactory.createProxy(
                    (Class) activityInterface,
                    activityImpl,
                    node.view.store(),
                    lock,
                    messaging,
                    retryPolicy,
                    timeout,
                    serializer,
                    retryExecutor,
                    LOCK_KEY_PREFIX,
                    observer
            );

            try {
                field.setAccessible(true);
                field.set(workflowImpl, proxy);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(
                        "Failed to inject activity proxy into field '%s'".formatted(field.getName()), e);
            }
        }
    }

    private static Class<?> detectActivityInterface(Object bean) {
        // Look for @Activity-annotated interface first
        for (var iface : bean.getClass().getInterfaces()) {
            if (iface.isAnnotationPresent(Activity.class)) {
                return iface;
            }
        }
        // Fall back to first interface
        var interfaces = bean.getClass().getInterfaces();
        if (interfaces.length == 0) {
            throw new IllegalArgumentException(
                    "Activity bean %s does not implement any interface".formatted(bean.getClass().getName()));
        }
        return interfaces[0];
    }

    private static List<Field> getAllFields(Class<?> clazz) {
        var fields = new java.util.ArrayList<Field>();
        var current = clazz;
        while (current != null && current != Object.class) {
            fields.addAll(List.of(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }
}
