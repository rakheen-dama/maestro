package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.logcapture.CapturingSlf4jProvider;
import io.b2mash.maestro.core.observe.AbandonReason;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
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
 * is down (final fix wave C4). Only {@code currentTime()} reads back what it
 * appended, so only it calls {@code appendEventReturningPayload}; {@code sleep},
 * {@code parallel}, {@code randomUUID} and {@code version} call
 * {@code appendEvent}, as before the read-back work. The store-fault latch's
 * {@code WARN} names that method ({@code Store unavailable on <method> — …}).
 */
class OperationAppendFaultDetailTest {

    private static final java.time.Duration BOUND = java.time.Duration.ofSeconds(10);

    private FaultyStore store;
    private RecordingEngineObserver observer;
    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        store = new FaultyStore();
        store.failAlways("appendEvent");
        observer = new RecordingEngineObserver();
        executor = WorkflowExecutor.builder(StoreFaultGuard.wrap(store, List.of(Duration.ofMillis(1), Duration.ofMillis(1))),
                        new PayloadSerializer(JsonMapper.builder().build()), "svc")
                .observer(observer).build();
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
