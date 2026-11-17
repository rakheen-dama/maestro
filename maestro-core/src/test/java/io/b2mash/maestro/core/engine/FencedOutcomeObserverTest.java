package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.FencedWriteException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.observe.StandDownReason;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import io.b2mash.maestro.core.spi.RunLease;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An activity whose outcome append is fenced still closes its observation:
 * the observer saw {@code activityStarted}, so it sees the matching
 * {@code activityCompleted} or {@code activityFailed} for the execution this
 * node genuinely made — as for a collided or store-faulted append — and the
 * run stands down {@code LEASE_FENCED}. Without it a tracing observer's
 * activity span is never ended.
 */
class FencedOutcomeObserverTest {

    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private final FencingOutcomeStore store = new FencingOutcomeStore();
    private final RecordingEngineObserver observer = new RecordingEngineObserver();
    private final WorkflowExecutor executor = WorkflowExecutor.builder(store, serializer, "svc")
            .lifecycleEventsEnabled(false)
            .observer(observer)
            .build();

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    @DisplayName("a fenced ACTIVITY_COMPLETED append is observed as activityCompleted, and the run stands down")
    void aFencedCompletionAppendIsObservedAsCompleted() throws Exception {
        var workflow = new Calls(proxy(new Succeeds()));
        executor.startWorkflow("fenced-ok", "Calls", "default", "x", () -> workflow,
                Calls.class.getMethod("run", String.class));
        await().atMost(Duration.ofSeconds(5)).until(() -> !executor.isRunning("fenced-ok"));

        assertAll(
                () -> assertEquals(List.of(StandDownReason.LEASE_FENCED), observer.standDownReasons("fenced-ok")),
                () -> assertEquals(1, observer.activityStarted().size()),
                () -> assertEquals(1, observer.activityCompleted().size(),
                        "the started activity must be closed with activityCompleted"),
                () -> assertFalse(observer.activityCompleted().getFirst().replayed()),
                () -> assertTrue(observer.activityFailed().isEmpty()),
                () -> assertTrue(observer.failed().isEmpty(), "a fence is never a workflow failure"));
    }

    @Test
    @DisplayName("a fenced ACTIVITY_FAILED append is observed as activityFailed, and the run stands down")
    void aFencedFailureAppendIsObservedAsFailed() throws Exception {
        var workflow = new Calls(proxy(new Fails()));
        executor.startWorkflow("fenced-fail", "Calls", "default", "x", () -> workflow,
                Calls.class.getMethod("run", String.class));
        await().atMost(Duration.ofSeconds(5)).until(() -> !executor.isRunning("fenced-fail"));

        assertAll(
                () -> assertEquals(List.of(StandDownReason.LEASE_FENCED),
                        observer.standDownReasons("fenced-fail")),
                () -> assertEquals(1, observer.activityStarted().size()),
                () -> assertEquals(1, observer.activityFailed().size(),
                        "the started activity must be closed with activityFailed"),
                () -> assertEquals(IllegalStateException.class.getName(),
                        observer.activityFailed().getFirst().exceptionType()),
                () -> assertTrue(observer.activityCompleted().isEmpty()),
                () -> assertTrue(observer.failed().isEmpty(), "a fence is never a workflow failure"));
    }

    private Step proxy(Step impl) {
        return new ActivityProxyFactory().createProxy(Step.class, impl, store, null, null, RetryPolicy.noRetry(),
                Duration.ofSeconds(30), serializer, new RetryExecutor(), "maestro:lock:", observer);
    }

    /** Fences every activity-outcome append, as a store would once another node adopted the run. */
    static final class FencingOutcomeStore extends VersionedInMemoryStore {
        @Override
        public void appendEvent(WorkflowEvent event, RunLease lease) {
            if (event.eventType() == EventType.ACTIVITY_COMPLETED || event.eventType() == EventType.ACTIVITY_FAILED) {
                throw new FencedWriteException(lease.instanceId(), lease.epoch(), "appendEvent");
            }
            super.appendEvent(event, lease);
        }
    }

    public interface Step {
        String call(String in);
    }

    public static final class Succeeds implements Step {
        @Override
        public String call(String in) {
            return "ok:" + in;
        }
    }

    public static final class Fails implements Step {
        @Override
        public String call(String in) {
            throw new IllegalStateException("business failure (injected)");
        }
    }

    public static class Calls {
        private final Step step;

        public Calls(Step step) {
            this.step = step;
        }

        public String run(String in) {
            return step.call(in);
        }
    }
}
