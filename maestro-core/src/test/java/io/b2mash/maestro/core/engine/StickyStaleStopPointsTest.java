package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.DuplicateEventException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.observe.StandDownReason;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan 03 final review round 2 (R33): once a run knows it is stale — one of
 * its appends collided with another runner's event — every later stop point
 * stands it down, even if the workflow code caught and swallowed the
 * {@link DuplicateEventException}. R29 covered the next live activity; these
 * pins cover the other three places a stale run could still act: a
 * {@code sleep()} (saving a timer row), an {@code awaitSignal()} (consuming a
 * signal) and the terminal write of a workflow method that returns normally.
 *
 * <p>Each workflow calls one activity whose result append loses the race at
 * sequence 1 ({@link VersionedInMemoryStore#winnerAppearsOnNextAppend}),
 * swallows the duplicate — exactly what the author contract forbids — and
 * carries on.
 */
@DisplayName("A stale run stands down at every stop point after a swallowed collision")
class StickyStaleStopPointsTest {

    private static final Duration BOUND = Duration.ofSeconds(5);

    private VersionedInMemoryStore store;
    private PayloadSerializer serializer;
    private RecordingEngineObserver observer;
    private WorkflowExecutor executor;
    private final AtomicInteger afterStopPoint = new AtomicInteger();

    @BeforeEach
    void setUp() {
        store = new VersionedInMemoryStore();
        serializer = new PayloadSerializer(JsonMapper.builder().build());
        observer = new RecordingEngineObserver();
        executor = new WorkflowExecutor(store, null, null, null, serializer, "node",
                "maestro:lock:", Duration.ofSeconds(30), true,
                Duration.ofSeconds(5), Duration.ofSeconds(30), observer);
        store.winnerAppearsOnNextAppend(1, EventType.ACTIVITY_COMPLETED, serializer.serialize("winner"));
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    @DisplayName("(a) sleep() after a swallowed duplicate stands down before saving a timer row")
    void sleepAfterSwallowedDuplicate_standsDownBeforeSavingATimer() throws Exception {
        var instanceId = start("sticky-sleep", StopPoint.SLEEP);

        await().atMost(BOUND).until(() -> !executor.isRunning("sticky-sleep"));
        assertAll(
                () -> assertTrue(store.findTimer(instanceId, "sleep-2").isEmpty(),
                        "a stale run must not save a timer row"),
                () -> assertEquals(0, countOfType(instanceId, EventType.TIMER_SCHEDULED)),
                () -> assertEquals(0, afterStopPoint.get()),
                () -> assertEquals(List.of(StandDownReason.STALE_RUN), observer.standDownReasons("sticky-sleep")));
    }

    @Test
    @DisplayName("(b) awaitSignal() after a swallowed duplicate stands down before consuming a pre-arrived signal")
    void awaitSignalAfterSwallowedDuplicate_standsDownBeforeConsuming() throws Exception {
        // Pre-delivered before the start (self-recovery case 2): adopted at start,
        // so awaitSignal would consume it immediately without parking.
        store.saveSignal(new WorkflowSignal(UUID.randomUUID(), null, "sticky-signal", "go",
                serializer.serialize("payload"), false, Instant.now()));
        var instanceId = start("sticky-signal", StopPoint.AWAIT_SIGNAL);

        await().atMost(BOUND).until(() -> !executor.isRunning("sticky-signal"));
        assertAll(
                () -> assertTrue(store.allSignals().stream().noneMatch(WorkflowSignal::consumed),
                        "a stale run must not consume a signal"),
                () -> assertEquals(0, countOfType(instanceId, EventType.SIGNAL_RECEIVED)),
                () -> assertEquals(0, afterStopPoint.get()),
                () -> assertEquals(List.of(StandDownReason.STALE_RUN), observer.standDownReasons("sticky-signal")));
    }

    @Test
    @DisplayName("(c) a workflow method returning normally after a swallowed duplicate does NOT write COMPLETED")
    void normalReturnAfterSwallowedDuplicate_doesNotWriteCompleted() throws Exception {
        var instanceId = start("sticky-return", StopPoint.RETURN);

        await().atMost(BOUND).until(() -> !executor.isRunning("sticky-return"));
        assertAll(
                () -> assertNotEquals(WorkflowStatus.COMPLETED,
                        store.getInstance("sticky-return").orElseThrow().status(),
                        "a stale run must not reach its terminal write"),
                () -> assertEquals(0, countOfType(instanceId, EventType.WORKFLOW_COMPLETED)),
                () -> assertTrue(observer.completed().isEmpty(), "no workflowCompleted for a stale run"),
                () -> assertEquals(List.of(StandDownReason.STALE_RUN), observer.standDownReasons("sticky-return")));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private UUID start(String workflowId, StopPoint stopPoint) throws NoSuchMethodException {
        var activity = new ActivityProxyFactory().createProxy(Step.class, input -> input + "-local",
                store, null, null, RetryPolicy.noRetry(), Duration.ofSeconds(5), serializer, new RetryExecutor());
        return executor.startWorkflow(workflowId, "SwallowingWorkflow", "default", null,
                new SwallowingWorkflow(activity, stopPoint, afterStopPoint),
                SwallowingWorkflow.class.getMethod("run"));
    }

    private int countOfType(UUID instanceId, EventType type) {
        return (int) store.getEvents(instanceId).stream().filter(e -> e.eventType() == type).count();
    }

    /** Where the workflow goes after swallowing the duplicate. */
    enum StopPoint { SLEEP, AWAIT_SIGNAL, RETURN }

    /** The activity whose result append loses the race. */
    @Activity
    public interface Step {
        /**
         * @param input the seed
         * @return the step's result
         */
        String step(String input);
    }

    /** Swallows the duplicate from its first activity, then reaches one stop point. */
    @DurableWorkflow(name = "SwallowingWorkflow")
    public static class SwallowingWorkflow {
        private final Step activity;
        private final StopPoint stopPoint;
        private final AtomicInteger afterStopPoint;

        SwallowingWorkflow(Step activity, StopPoint stopPoint, AtomicInteger afterStopPoint) {
            this.activity = activity;
            this.stopPoint = stopPoint;
            this.afterStopPoint = afterStopPoint;
        }

        /** @return a fixed result, if the run survives its stop point */
        @WorkflowMethod
        public String run() {
            try {
                activity.step("seed");
            } catch (DuplicateEventException swallowed) {
                // The anti-pattern: catch-and-continue a stand-down.
            }
            var workflow = WorkflowContext.current();
            switch (stopPoint) {
                case SLEEP -> workflow.sleep(Duration.ofMinutes(10));
                case AWAIT_SIGNAL -> workflow.awaitSignal("go", String.class, Duration.ofMinutes(10));
                case RETURN -> {
                    return "local-output";
                }
            }
            afterStopPoint.incrementAndGet();
            return "after-stop-point";
        }
    }
}
