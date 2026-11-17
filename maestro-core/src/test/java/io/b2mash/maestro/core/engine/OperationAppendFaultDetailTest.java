package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.logcapture.CapturingSlf4jProvider;
import io.b2mash.maestro.core.observe.AbandonReason;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.event.Level;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which store method a workflow operation's event append names when the store
 * is down (final fix wave C4 and its follow-up). Only the appends whose
 * returned payload is read back call {@code appendEventReturningPayload}: a
 * completed activity's outcome and {@code currentTime()}. {@code sleep},
 * {@code parallel}, {@code randomUUID}, {@code version} and a failed
 * activity's {@code ACTIVITY_FAILED} memo call {@code appendEvent}, as before
 * the read-back work. The store-fault latch's {@code WARN} names that method
 * ({@code Store unavailable on <method> — …}); either way the guard retries the
 * write three times, then latches the run {@code STORE_UNAVAILABLE} and
 * abandons it — never fails it.
 */
class OperationAppendFaultDetailTest {

    private static final java.time.Duration BOUND = java.time.Duration.ofSeconds(10);

    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());

    private FaultyStore store;
    private WorkflowStore guarded;
    private RecordingEngineObserver observer;
    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        store = new FaultyStore();
        store.failAlways("appendEvent");
        observer = new RecordingEngineObserver();
        guarded = StoreFaultGuard.wrap(store, List.of(Duration.ofMillis(1), Duration.ofMillis(1)));
        executor = WorkflowExecutor.builder(guarded, serializer, "svc").observer(observer).build();
    }

    @AfterEach
    void tearDown() {
        store.heal();
        executor.shutdown();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "sleep,       appendEvent",
            "parallel,    appendEvent",
            "randomUUID,  appendEvent",
            "version,     appendEvent",
            "currentTime, appendEventReturningPayload"})
    void theLatchNamesTheStoreMethodTheOperationCalls(String operation, String storeMethod) throws Exception {
        var id = "fault-" + operation;
        try (var logs = CapturingSlf4jProvider.watch(StoreFaultGuard.class)) {
            executor.startWorkflow(id, "OperationWorkflow", "default", operation,
                    OperationWorkflow::new, OperationWorkflow.class.getMethod("run", String.class));
            await().atMost(BOUND).until(() -> !executor.isRunning(id));

            var latched = logs.records().stream()
                    .filter(r -> r.level() == Level.WARN && r.message().contains("'" + id + "'"))
                    .map(r -> r.message().substring(0, r.message().indexOf(" — ")))
                    .toList();
            assertEquals(List.of("Store unavailable on " + storeMethod), latched);
        }
        assertEquals(List.of(AbandonReason.STORE_UNAVAILABLE), observer.abandoned().stream()
                .filter(a -> a.info().workflowId().equals(id))
                .map(RecordingEngineObserver.AbandonCall::reason).toList());
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "succeed, appendEventReturningPayload",
            "fail,    appendEvent"})
    void anActivityOutcomeAppendNamesItsStoreMethod_andTheFaultAbandonsTheRunWithoutFailingIt(
            String outcome, String storeMethod) throws Exception {
        var id = "fault-activity-" + outcome;
        var acts = new ActivityProxyFactory().createProxy(Acts.class, new ActsImpl(), guarded, null,
                RetryPolicy.noRetry(), Duration.ofSeconds(30), serializer, new RetryExecutor(),
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, observer, TaskQueueGovernor.UNBOUNDED,
                LifecycleEventSink.NOOP, true);
        try (var logs = CapturingSlf4jProvider.watch(StoreFaultGuard.class)) {
            executor.startWorkflow(id, "ActivityWorkflow", "default", outcome,
                    () -> new ActivityWorkflow(acts), ActivityWorkflow.class.getMethod("run", String.class));
            await().atMost(BOUND).until(() -> !executor.isRunning(id));

            var latched = logs.records().stream()
                    .filter(r -> r.level() == Level.WARN && r.message().contains("'" + id + "'"))
                    .map(r -> r.message().substring(0, r.message().indexOf(" — ")))
                    .toList();
            assertEquals(List.of("Store unavailable on " + storeMethod), latched);
        }
        assertEquals(List.of(AbandonReason.STORE_UNAVAILABLE), observer.abandoned().stream()
                .filter(a -> a.info().workflowId().equals(id))
                .map(RecordingEngineObserver.AbandonCall::reason).toList());
        assertEquals(3, store.calls("appendEvent"), "the guard retried the outcome append three times");
        assertEquals(WorkflowStatus.RUNNING, store.getInstance(id).orElseThrow().status());
        assertEquals(List.of(), observer.failed(), "a store fault never fails the run");
    }

    public interface Acts {
        String act(String outcome);
    }

    public static final class ActsImpl implements Acts {
        @Override
        public String act(String outcome) {
            if ("fail".equals(outcome)) {
                throw new IllegalStateException("declined");
            }
            return "done";
        }
    }

    /** Calls the activity once; its outcome (completed or failed) is the run's first append. */
    public static class ActivityWorkflow {
        private final Acts acts;

        ActivityWorkflow(Acts acts) {
            this.acts = acts;
        }

        public String run(String outcome) {
            return acts.act(outcome);
        }
    }

    /** Performs the one operation its input names; its event is the run's first append. */
    public static class OperationWorkflow {
        public String run(String operation) {
            var workflow = WorkflowContext.current();
            switch (operation) {
                case "sleep" -> workflow.sleep(Duration.ofMinutes(5));
                case "parallel" -> workflow.parallel(List.<Callable<String>>of(() -> "a", () -> "b"));
                case "randomUUID" -> workflow.randomUUID();
                case "version" -> workflow.version("change-1", 1, 1);
                case "currentTime" -> workflow.currentTime();
                default -> throw new IllegalArgumentException(operation);
            }
            return operation;
        }
    }
}
