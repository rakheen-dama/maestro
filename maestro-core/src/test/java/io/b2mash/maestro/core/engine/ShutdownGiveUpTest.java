package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.CountingStore;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SharedLock;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.AbandonReason;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SERVICE;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.valueWithin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pilot readiness P1, task 2 (Issue 70): a run still executing when
 * shutdown's deadline passes is given up — abandoned as {@code SHUTDOWN} — and
 * records nothing more, whatever its activity or its code does afterwards. A
 * peer then recovers it exactly as after a crash at the deadline.
 *
 * <p>Node A drains for 300 ms while its run sits in an activity (or a
 * compensation) that a test gate holds; A's shutdown returns at the deadline,
 * then the gate opens and the activity fails or succeeds on a node that has
 * stopped. Node B shares A's store and lock and recovers what A left.
 */
@Timeout(60)
class ShutdownGiveUpTest {

    private static final String ID = "wf-overrun";

    /** Where the run is when the deadline passes. */
    enum Shape {
        /** In an activity of the workflow body. */
        ACTIVITY,
        /** In an activity of one {@code parallel()} branch; the sibling branch has completed. */
        PARALLEL_BRANCH,
        /** In an activity a compensation calls, after the workflow body failed. */
        COMPENSATION
    }

    private final CountingStore store = new CountingStore();
    private final SharedLock lock = new SharedLock();
    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private final RecordingEngineObserver observerA = new RecordingEngineObserver();
    private final WorkflowExecutor nodeA = executor(observerA, Duration.ofMillis(300));
    private final WorkflowExecutor nodeB = executor(EngineObserver.NOOP, Duration.ofSeconds(10));
    /** Holds node A's blocking call until the test opens it. */
    private final CountDownLatch gate = new CountDownLatch(1);
    private final CountDownLatch enteredOnA = new CountDownLatch(1);
    private final AtomicInteger blockingCalls = new AtomicInteger();
    private final AtomicInteger furtherCalls = new AtomicInteger();

    @AfterEach
    void tearDown() {
        gate.countDown();
        nodeA.shutdown();
        nodeB.shutdown();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Shape.class)
    void anActivityThatFailsAfterTheDeadlineRecordsNothingAndAPeerRecoversIt(Shape shape) throws Exception {
        var history = overrunTheDeadline(shape, false, false);

        assertEquals(history, store.getEvents(instanceId()), "the given-up run recorded nothing after the deadline");
        assertTrue(observerA.failed().isEmpty(), "no workflowFailed for a run given up at shutdown");
        assertAbandonedAsShutdown();

        recoverOnB();
        assertEquals(2, blockingCalls.get(), "the peer ran the step again");
        assertEquals(shape == Shape.COMPENSATION ? WorkflowStatus.FAILED : WorkflowStatus.COMPLETED,
                store.getInstance(ID).orElseThrow().status());
        assertEquals(0, count(EventType.ACTIVITY_FAILED), "no ACTIVITY_FAILED anywhere: the late failure was refused");
        assertEquals(0, count(EventType.COMPENSATION_STEP_FAILED), "no compensation step recorded failed");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Shape.class)
    void anActivityThatSucceedsAfterTheDeadlineIsRunAgainByThePeer(Shape shape) throws Exception {
        var history = overrunTheDeadline(shape, true, false);

        assertEquals(history, store.getEvents(instanceId()), "the late success was discarded, not recorded");
        assertAbandonedAsShutdown();

        recoverOnB();
        assertEquals(2, blockingCalls.get(), "the activity is invoked twice: once late on A, once on the peer");
        if (shape == Shape.COMPENSATION) {
            assertEquals(WorkflowStatus.FAILED, store.getInstance(ID).orElseThrow().status());
            assertEquals(1, count(EventType.WORKFLOW_FAILED));
            assertEquals(1, count(EventType.COMPENSATION_COMPLETED));
        } else {
            assertEquals(WorkflowStatus.COMPLETED, store.getInstance(ID).orElseThrow().status());
            assertEquals(1, count(EventType.WORKFLOW_COMPLETED), "the workflow completes once");
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Shape.class)
    void anAbandonedRunStartsNoFurtherActivity(Shape shape) throws Exception {
        // The workflow code swallows whatever its blocking call throws and goes
        // on to the next activity; if that throws too it turns it into a
        // failure of its own.
        var history = overrunTheDeadline(shape, true, true);

        assertEquals(0, furtherCalls.get(), "an abandoned run started another activity");
        assertEquals(history, store.getEvents(instanceId()));
        assertTrue(observerA.failed().isEmpty(), "code that turned the refusal into a failure did not fail the run");
        assertAbandonedAsShutdown();
    }

    /**
     * Starts the run on A, waits for it to enter its blocking call, shuts A
     * down against its 300 ms deadline, then lets the blocking call end
     * ({@code succeed} or fail) and waits for A's run to release its lock.
     *
     * @return the history as it stood when A's shutdown returned
     */
    private List<WorkflowEvent> overrunTheDeadline(Shape shape, boolean succeed, boolean keepGoing)
            throws Exception {
        nodeA.startWorkflow(ID, type(shape), "default", null, factory(shape, steps(nodeA, "A", succeed), keepGoing),
                method(shape));
        assertTrue(enteredOnA.await(10, TimeUnit.SECONDS), "A's run is inside its blocking call");

        var start = System.nanoTime();
        nodeA.shutdown();
        var tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(tookMs >= 250 && tookMs < 5_000, "A's shutdown hit its deadline (" + tookMs + " ms)");
        var history = store.getEvents(instanceId());

        gate.countDown();
        // The run's lock release is the last thing it does before it leaves.
        assertEquals(0, valueWithin(lock::heldCount, 0, Duration.ofSeconds(10)), "A's run released its lock");
        assertEquals(1, blockingCalls.get());
        return history;
    }

    /** A's run ended abandoned as {@code SHUTDOWN}, and that is the only callback it ended with. */
    private void assertAbandonedAsShutdown() {
        assertEquals(List.of(AbandonReason.SHUTDOWN), valueWithin(
                observerA::abandonReasons, List.of(AbandonReason.SHUTDOWN), Duration.ofSeconds(10)),
                "A's run was abandoned as SHUTDOWN once its blocking call ended");
    }

    private void recoverOnB() {
        var shapeType = store.getInstance(ID).orElseThrow().workflowType();
        var shape = Shape.valueOf(shapeType.substring("GiveUp-".length()));
        var registrations = Map.of(shapeType, new WorkflowRegistration(shapeType, "default",
                factory(shape, steps(nodeB, "B", true), false), method(shape)));
        assertEquals(1, nodeB.recoverWorkflows(registrations), "the peer adopts the given-up run");
        valueWithin(() -> store.getInstance(ID).orElseThrow().status().isTerminal(), true, Duration.ofSeconds(10));
    }

    private Steps steps(WorkflowExecutor node, String name, boolean succeed) {
        Steps impl = new Steps() {
            @Override
            public String blocking() {
                blockingCalls.incrementAndGet();
                if (name.equals("A")) {
                    enteredOnA.countDown();
                    try {
                        gate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    if (!succeed) {
                        throw new IllegalStateException("a host bean destroyed after the deadline (test)");
                    }
                }
                return "blocking";
            }

            @Override
            public String further() {
                furtherCalls.incrementAndGet();
                return "further";
            }

            @Override
            public String quick() {
                return "quick";
            }
        };
        return new ActivityProxyFactory().createProxy(Steps.class, impl, store, null, null,
                RetryPolicy.noRetry(), Duration.ofSeconds(30), serializer, new RetryExecutor(),
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, EngineObserver.NOOP, node.taskQueueGovernor());
    }

    private long count(EventType type) {
        return store.getEvents(instanceId()).stream().filter(e -> e.eventType() == type).count();
    }

    private java.util.UUID instanceId() {
        return store.getInstance(ID).orElseThrow().id();
    }

    private WorkflowExecutor executor(EngineObserver observer, Duration shutdownTimeout) {
        return WorkflowExecutor.builder(store, serializer, SERVICE)
                .lifecycleEventsEnabled(false)
                .distributedLock(lock)
                .observer(observer)
                .shutdownTimeout(shutdownTimeout)
                .build();
    }

    private static String type(Shape shape) {
        return "GiveUp-" + shape.name();
    }

    private static Supplier<Object> factory(Shape shape, Steps steps, boolean keepGoing) {
        Function<Steps, Object> build = switch (shape) {
            case ACTIVITY -> s -> new ActivityWorkflow(s, keepGoing);
            case PARALLEL_BRANCH -> s -> new ParallelWorkflow(s, keepGoing);
            case COMPENSATION -> s -> new CompensationWorkflow(s, keepGoing);
        };
        return () -> build.apply(steps);
    }

    private static Method method(Shape shape) {
        var type = switch (shape) {
            case ACTIVITY -> ActivityWorkflow.class;
            case PARALLEL_BRANCH -> ParallelWorkflow.class;
            case COMPENSATION -> CompensationWorkflow.class;
        };
        try {
            return type.getMethod("run");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The blocking call, then — when the workflow keeps going — the further
     * activity, with everything the blocking call threw swallowed and anything
     * the further call throws turned into a failure of the workflow's own.
     */
    static String blockingThenMaybeFurther(Steps steps, boolean keepGoing) {
        if (!keepGoing) {
            return steps.blocking();
        }
        try {
            steps.blocking();
        } catch (Throwable swallowed) {
            // deliberately swallowed: workflow code that catches too broadly
        }
        try {
            return steps.further();
        } catch (Throwable t) {
            throw new IllegalStateException("workflow code turned a stop into a failure (test)", t);
        }
    }

    // ── fixture ────────────────────────────────────────────────────────

    /** The activities. */
    @Activity(name = "steps")
    public interface Steps {
        /** @return a marker; blocks on node A until the gate opens */
        String blocking();

        /** @return a marker; must never run on an abandoned run */
        String further();

        /** @return a marker at once */
        String quick();
    }

    /** One blocking activity in the workflow body. */
    @DurableWorkflow(name = "GiveUp-ACTIVITY")
    public static final class ActivityWorkflow {
        private final Steps steps;
        private final boolean keepGoing;

        ActivityWorkflow(Steps steps, boolean keepGoing) {
            this.steps = steps;
            this.keepGoing = keepGoing;
        }

        /** @return the result */
        @WorkflowMethod
        public String run() {
            return blockingThenMaybeFurther(steps, keepGoing);
        }
    }

    /** Branch 0 blocks; branch 1 completes at once. */
    @DurableWorkflow(name = "GiveUp-PARALLEL_BRANCH")
    public static final class ParallelWorkflow {
        private final Steps steps;
        private final boolean keepGoing;

        ParallelWorkflow(Steps steps, boolean keepGoing) {
            this.steps = steps;
            this.keepGoing = keepGoing;
        }

        /** @return the branches' results */
        @WorkflowMethod
        public String run() {
            return String.join(",", WorkflowContext.current().parallel(List.of(
                    () -> blockingThenMaybeFurther(steps, keepGoing),
                    steps::quick)));
        }
    }

    /**
     * The body records a step, registers a compensation that blocks, and
     * fails; the compensation runs while the node drains.
     */
    @DurableWorkflow(name = "GiveUp-COMPENSATION")
    public static final class CompensationWorkflow {
        private final Steps steps;
        private final boolean keepGoing;

        CompensationWorkflow(Steps steps, boolean keepGoing) {
            this.steps = steps;
            this.keepGoing = keepGoing;
        }

        /** @return never */
        @WorkflowMethod
        public String run() {
            steps.quick();
            if (keepGoing) {
                // LIFO: "undo-blocking" runs first and swallows its refusal;
                // "undo-further" must then never run.
                WorkflowContext.current().addCompensation("undo-further", steps::further);
                WorkflowContext.current().addCompensation("undo-blocking", () -> {
                    try {
                        steps.blocking();
                    } catch (Throwable swallowed) {
                        // deliberately swallowed: compensation code that catches too broadly
                    }
                });
            } else {
                WorkflowContext.current().addCompensation("undo-blocking", steps::blocking);
            }
            throw new IllegalStateException("the forward path fails (test)");
        }
    }
}
