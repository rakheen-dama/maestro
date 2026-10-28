package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.TestTerminalWait;
import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.AbandonReason;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec C8: a JVM {@link Error} that is not one of the three control-flow
 * signals, escaping the workflow method, is a workflow failure. Before the
 * {@code Error} arm in {@code executeWorkflow} it unwound through {@code finally},
 * released the lock and left the row {@code RUNNING} for the recovery poller to
 * re-adopt forever — no log, no observer callback.
 */
@DisplayName("An Error escaping the workflow body ends FAILED; control-flow signals still stand down")
class WorkflowExecutorErrorEscapeTest {

    private static final Duration BOUND = Duration.ofSeconds(5);

    private VersionedInMemoryStore store;
    private RecordingEngineObserver observer;
    private WorkflowExecutor executor;
    private PayloadSerializer serializer;

    @BeforeEach
    void setUp() {
        store = new VersionedInMemoryStore();
        observer = new RecordingEngineObserver();
        serializer = new PayloadSerializer(JsonMapper.builder().build());
        executor = new WorkflowExecutor(store, null, null, null, serializer, "node",
                "maestro:lock:", Duration.ofSeconds(30), true,
                Duration.ofSeconds(5), Duration.ofSeconds(30), observer);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    @DisplayName("AssertionError: FAILED, one WORKFLOW_FAILED, observer callback, compensation ran, not re-adoptable")
    void assertionError_endsFailedWithCompensation() {
        var compensations = new AtomicInteger();
        var instanceId = start("error-assertion", compensations, () -> new AssertionError("boom"));
        TestTerminalWait.awaitTerminal(store, "error-assertion", BOUND);

        var instance = store.getInstance("error-assertion").orElseThrow();
        var failedEvents = store.getEvents(instanceId).stream()
                .filter(e -> e.eventType() == EventType.WORKFLOW_FAILED).toList();
        assertAll(
                () -> assertEquals(WorkflowStatus.FAILED, instance.status()),
                () -> assertEquals(1, failedEvents.size()),
                () -> assertEquals("java.lang.AssertionError",
                        failedEvents.getFirst().payload().path("exceptionType").asString()),
                () -> assertEquals("boom", failedEvents.getFirst().payload().path("message").asString()),
                () -> assertEquals(1, observer.failed().size()),
                () -> assertEquals("java.lang.AssertionError", observer.failed().getFirst().exceptionType()),
                () -> assertEquals(1, compensations.get(), "a FAILED workflow runs its compensations"),
                () -> assertTrue(observer.standDowns().isEmpty(), "an Error is a failure, not a stand-down"),
                () -> assertFalse(executor.isRunning("error-assertion")),
                () -> assertTrue(store.getRecoverableInstances().stream()
                                .noneMatch(i -> i.workflowId().equals("error-assertion")),
                        "FAILED is terminal — the recovery poller must never re-adopt it"));
    }

    @Test
    @DisplayName("StackOverflowError: FAILED with its class name")
    void stackOverflowError_endsFailed() {
        var instanceId = start("error-so", new AtomicInteger(), () -> new StackOverflowError("deep"));
        TestTerminalWait.awaitTerminal(store, "error-so", BOUND);

        var failedEvents = store.getEvents(instanceId).stream()
                .filter(e -> e.eventType() == EventType.WORKFLOW_FAILED).toList();
        assertAll(
                () -> assertEquals(WorkflowStatus.FAILED, store.getInstance("error-so").orElseThrow().status()),
                () -> assertEquals(1, failedEvents.size()),
                () -> assertEquals("java.lang.StackOverflowError",
                        failedEvents.getFirst().payload().path("exceptionType").asString()),
                () -> assertEquals(1, observer.failed().size()),
                () -> assertEquals("java.lang.StackOverflowError", observer.failed().getFirst().exceptionType()));
    }

    // ── R20: node-level Errors abandon the run, they do not fail the workflow ──

    @Test
    @DisplayName("NoClassDefFoundError (a bad deploy): stays RUNNING, nothing written, nothing "
            + "compensated, abandoned as NODE_ERROR, still recoverable")
    void noClassDefFoundError_abandonsAsNodeError() {
        assertNodeErrorAbandons("error-ncdfe", () -> new NoClassDefFoundError("com/acme/Missing"));
    }

    @Test
    @DisplayName("OutOfMemoryError (resource exhaustion): stays RUNNING, nothing written, nothing "
            + "compensated, abandoned as NODE_ERROR, still recoverable")
    void outOfMemoryError_abandonsAsNodeError() {
        // A fresh instance thrown from the body — memory is never actually exhausted.
        assertNodeErrorAbandons("error-oom", () -> new OutOfMemoryError("Java heap space"));
    }

    @Test
    @DisplayName("ExceptionInInitializerError (a LinkageError): abandoned as NODE_ERROR")
    void exceptionInInitializerError_abandonsAsNodeError() {
        assertNodeErrorAbandons("error-eiie",
                () -> new ExceptionInInitializerError(new IllegalStateException("static init")));
    }

    private void assertNodeErrorAbandons(String workflowId, Supplier<? extends Throwable> toThrow) {
        var compensations = new AtomicInteger();
        var instanceId = start(workflowId, compensations, toThrow);
        await().atMost(BOUND).until(() -> !executor.isRunning(workflowId));
        await().atMost(BOUND).until(() -> !observer.abandoned().isEmpty() || !observer.failed().isEmpty());

        assertAll(
                () -> assertEquals(WorkflowStatus.RUNNING, store.getInstance(workflowId).orElseThrow().status(),
                        "a node problem must not be recorded as a workflow outcome"),
                () -> assertEquals(0, countOfType(instanceId, EventType.WORKFLOW_FAILED)),
                () -> assertEquals(1, store.getEvents(instanceId).size(), "only the seq-1 step — nothing written"),
                () -> assertEquals(0, compensations.get(), "no compensation for work that never failed"),
                () -> assertTrue(observer.failed().isEmpty()),
                () -> assertTrue(observer.standDowns().isEmpty()),
                () -> assertEquals(List.of(AbandonReason.NODE_ERROR), observer.abandonReasons()),
                () -> assertTrue(store.getRecoverableInstances().stream()
                                .anyMatch(i -> i.workflowId().equals(workflowId)),
                        "a healthy (or rolled-back) node must be able to adopt it"));
    }

    @Test
    @DisplayName("NoClassDefFoundError thrown INSIDE an activity: not retried, no ACTIVITY_FAILED, "
            + "abandoned as NODE_ERROR, instance stays RUNNING (plan 03 final review I3 / R28)")
    void nodeErrorInsideActivity_abandonsAsNodeErrorWithoutRecordingAFailure() throws Exception {
        var attempts = new AtomicInteger();
        var compensations = new AtomicInteger();
        var policy = RetryPolicy.builder().maxAttempts(5)
                .initialInterval(Duration.ofMillis(1)).maxInterval(Duration.ofMillis(5)).build();
        var proxy = new ActivityProxyFactory().createProxy(LinkingActivity.class, () -> {
            attempts.incrementAndGet();
            throw new NoClassDefFoundError("com/acme/Missing");
        }, store, null, null, policy, Duration.ofSeconds(30), serializer, new RetryExecutor());
        var workflowId = "error-activity-ncdfe";
        var instanceId = executor.startWorkflow(workflowId, "ActivityCallingWorkflow", "default", null,
                () -> new ActivityCallingWorkflow(compensations, proxy),
                ActivityCallingWorkflow.class.getMethod("run"));
        await().atMost(BOUND).until(() -> !executor.isRunning(workflowId));
        await().atMost(BOUND).until(() -> !observer.abandoned().isEmpty() || !observer.failed().isEmpty());

        assertAll(
                () -> assertEquals(1, attempts.get(), "a node error must not be retried"),
                () -> assertEquals(WorkflowStatus.RUNNING, store.getInstance(workflowId).orElseThrow().status()),
                () -> assertEquals(0, countOfType(instanceId, EventType.ACTIVITY_FAILED),
                        "a node error is not an activity outcome — nothing may be memoized"),
                () -> assertEquals(0, countOfType(instanceId, EventType.WORKFLOW_FAILED)),
                () -> assertEquals(0, compensations.get()),
                () -> assertTrue(observer.failed().isEmpty()),
                () -> assertEquals(List.of(AbandonReason.NODE_ERROR), observer.abandonReasons()),
                () -> assertTrue(store.getRecoverableInstances().stream()
                        .anyMatch(i -> i.workflowId().equals(workflowId))));
    }

    @Test
    @DisplayName("NoClassDefFoundError thrown by a COMPENSATION activity: the unwind is abandoned as "
            + "NODE_ERROR, no COMPENSATION_STEP_FAILED, instance left COMPENSATING and recoverable (R28)")
    void nodeErrorInsideCompensationActivity_abandonsAsNodeError() throws Exception {
        var attempts = new AtomicInteger();
        var policy = RetryPolicy.builder().maxAttempts(5)
                .initialInterval(Duration.ofMillis(1)).maxInterval(Duration.ofMillis(5)).build();
        var proxy = new ActivityProxyFactory().createProxy(LinkingActivity.class, () -> {
            attempts.incrementAndGet();
            throw new NoClassDefFoundError("com/acme/Missing");
        }, store, null, null, policy, Duration.ofSeconds(30), serializer, new RetryExecutor());
        var workflowId = "error-compensation-ncdfe";
        var instanceId = executor.startWorkflow(workflowId, "CompensatingViaActivityWorkflow", "default", null,
                () -> new CompensatingViaActivityWorkflow(proxy),
                CompensatingViaActivityWorkflow.class.getMethod("run"));
        await().atMost(BOUND).until(() -> !executor.isRunning(workflowId));

        assertAll(
                () -> assertEquals(List.of(AbandonReason.NODE_ERROR), observer.abandonReasons(),
                        "a node error during the unwind must abandon the run loudly, not escape the thread"),
                () -> assertEquals(1, attempts.get(), "a node error must not be retried"),
                () -> assertEquals(WorkflowStatus.COMPENSATING,
                        store.getInstance(workflowId).orElseThrow().status()),
                () -> assertEquals(0, countOfType(instanceId, EventType.COMPENSATION_STEP_FAILED)),
                () -> assertEquals(0, countOfType(instanceId, EventType.WORKFLOW_FAILED)),
                () -> assertTrue(store.getRecoverableInstances().stream()
                        .anyMatch(i -> i.workflowId().equals(workflowId))));
    }

    @Test
    @DisplayName("a control-flow Error from the body still stands down: nothing written, nothing compensated")
    void controlFlowErrorFromBody_stillStandsDown() {
        var compensations = new AtomicInteger();
        var instanceId = start("error-controlflow", compensations, () -> new ExecutorShutdownException("deploying"));
        await().atMost(BOUND).until(() -> !executor.isRunning("error-controlflow"));

        var instance = store.getInstance("error-controlflow").orElseThrow();
        assertAll(
                () -> assertEquals(WorkflowStatus.RUNNING, instance.status(),
                        "a control-flow signal writes nothing — the Error arm must not have caught it"),
                () -> assertTrue(observer.failed().isEmpty()),
                () -> assertEquals(0, countOfType(instanceId, EventType.WORKFLOW_FAILED)),
                () -> assertEquals(0, compensations.get()));
    }

    private UUID start(String workflowId, AtomicInteger compensations, Supplier<? extends Throwable> toThrow) {
        try {
            Method run = ErrorThrowingWorkflow.class.getMethod("run");
            return executor.startWorkflow(workflowId, "ErrorThrowingWorkflow", "default", null,
                    () -> new ErrorThrowingWorkflow(compensations, toThrow), run);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private int countOfType(UUID instanceId, EventType type) {
        return (int) store.getEvents(instanceId).stream().filter(e -> e.eventType() == type).count();
    }

    /** One activity; its implementation throws a node-level error. */
    @Activity(name = "LinkingActivity")
    public interface LinkingActivity {
        String link();
    }

    /** Registers a compensation, then calls one activity. */
    @DurableWorkflow(name = "ActivityCallingWorkflow")
    public static class ActivityCallingWorkflow {
        private final AtomicInteger compensations;
        private final LinkingActivity activity;

        public ActivityCallingWorkflow(AtomicInteger compensations, LinkingActivity activity) {
            this.compensations = compensations;
            this.activity = activity;
        }

        @WorkflowMethod
        public String run() {
            WorkflowContext.current().addCompensation(compensations::incrementAndGet);
            return activity.link();
        }
    }

    /** Registers an activity-backed compensation, then fails with an ordinary exception. */
    @DurableWorkflow(name = "CompensatingViaActivityWorkflow")
    public static class CompensatingViaActivityWorkflow {
        private final LinkingActivity activity;

        public CompensatingViaActivityWorkflow(LinkingActivity activity) {
            this.activity = activity;
        }

        @WorkflowMethod
        public String run() {
            WorkflowContext.current().addCompensation(activity::link);
            throw new IllegalStateException("business failure");
        }
    }

    /** Registers a compensation, performs one memoized step, then throws whatever it was given. */
    @DurableWorkflow(name = "ErrorThrowingWorkflow")
    public static class ErrorThrowingWorkflow {
        private final AtomicInteger compensations;
        private final Supplier<? extends Throwable> toThrow;

        public ErrorThrowingWorkflow(AtomicInteger compensations, Supplier<? extends Throwable> toThrow) {
            this.compensations = compensations;
            this.toThrow = toThrow;
        }

        @WorkflowMethod
        public String run() throws Throwable {
            var workflow = WorkflowContext.current();
            workflow.addCompensation(compensations::incrementAndGet);
            workflow.currentTime(); // seq 1 — a memoized step before the blow-up
            throw toThrow.get();
        }
    }
}
