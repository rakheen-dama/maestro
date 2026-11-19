package io.b2mash.maestro.spring.config;

import io.b2mash.maestro.core.exception.ExecutorShuttingDownException;
import io.b2mash.maestro.test.InMemoryDistributedLock;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.engine.WorkflowRegistration;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP3: a {@code $maestro:retry} that the engine refuses with
 * {@link WorkflowExecutor.RetryOutcome#SIGNAL_CONSUMED_AFTER_FAILURE} is a
 * handled outcome. The dispatcher returns normally, so the transport
 * acknowledges the command instead of redelivering it to the dead-letter
 * topic, and nothing is written. The engine's WARN names the consumed signal
 * and the remedy.
 */
@DisplayName("AdminCommandDispatcher acknowledges a refused retry")
class AdminCommandDispatcherRefusalTest {

    private static final String WORKFLOW_TYPE = "RefusalTestWorkflow";

    private final JsonMapper mapper = JsonMapper.builder().build();
    private InMemoryWorkflowStore store;
    private WorkflowExecutor executor;
    private AdminCommandDispatcher dispatcher;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        store = new InMemoryWorkflowStore();
        executor = WorkflowExecutor.builder(store, new PayloadSerializer(mapper), "test-service").distributedLock(new InMemoryDistributedLock()).build();
        var registration = new WorkflowRegistration(WORKFLOW_TYPE, "default", SimpleWorkflow::new,
                SimpleWorkflow.class.getMethod("run", String.class));
        dispatcher = new AdminCommandDispatcher(executor, store, new WorkflowRegistrar(executor) {
            @Override
            public WorkflowRegistration getRegistration(String workflowType) {
                return registration;
            }
        });
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    @DisplayName("$maestro:retry whose failure handling consumed a signal is acked and writes nothing")
    void aRetryRefusedForAConsumedSignal_isAcknowledgedAndWritesNothing() {
        var instanceId = UUID.randomUUID();
        var now = Instant.now();
        store.createInstance(WorkflowInstance.builder()
                .id(instanceId)
                .workflowId("wf-refused")
                .runId(UUID.randomUUID())
                .workflowType(WORKFLOW_TYPE)
                .taskQueue("default")
                .status(WorkflowStatus.FAILED)
                .serviceName("test-service")
                .startedAt(now)
                .updatedAt(now)
                .completedAt(now)
                .version(0)
                .build());
        // catch (e) { awaitSignal("ack"); throw e; }: the ack's memo lies after the failure
        store.appendEvent(event(instanceId, 1, EventType.ACTIVITY_FAILED, "Acts.primary",
                "{\"exceptionType\":\"java.lang.IllegalStateException\",\"message\":\"down\"}"));
        store.appendEvent(event(instanceId, 2, EventType.SIGNAL_RECEIVED, "$maestro:awaitSignal:ack", "\"ok\""));
        store.appendEvent(event(instanceId, 3, EventType.WORKFLOW_FAILED, null,
                "{\"exceptionType\":\"io.b2mash.maestro.core.exception.ActivityExecutionException\","
                        + "\"message\":\"x\",\"failedSequences\":[1]}"));

        var engineLog = (Logger) LoggerFactory.getLogger(WorkflowExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        engineLog.addAppender(appender);
        try {
            assertDoesNotThrow(() -> dispatcher.dispatch(new SignalMessage("wf-refused", "$maestro:retry", null)));
        } finally {
            engineLog.detachAppender(appender);
        }

        var warnings = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertEquals(List.of("Workflow 'wf-refused' was not retried: the code that handled its failure consumed "
                + "signal 'ack' (sequence 2), which a retry would need again. Terminate the workflow or finish it "
                + "by hand; the signal is not redelivered (RetryOutcome.SIGNAL_CONSUMED_AFTER_FAILURE)"), warnings);

        var after = store.getInstance("wf-refused").orElseThrow();
        assertEquals(WorkflowStatus.FAILED, after.status());
        assertEquals(0, after.version(), "the refusal must not write the row");
        assertEquals(3, store.getEvents(instanceId).size(), "the refusal must not delete an event");
    }

    /**
     * P1: a {@code $maestro:retry} consumed while this node shuts down is
     * refused by the engine with {@code IllegalStateException} and nothing is
     * written. The dispatcher rethrows it, so the record is not acknowledged,
     * and logs it at WARN without a stack trace: a draining node refusing work
     * is not an error.
     */
    @Test
    @DisplayName("$maestro:retry during shutdown is refused, not acked, and logged at WARN, not ERROR")
    void aRetryRefusedBecauseTheNodeIsShuttingDown_isRethrownAndLoggedAtWarn() {
        var now = Instant.now();
        store.createInstance(WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId("wf-draining")
                .runId(UUID.randomUUID())
                .workflowType(WORKFLOW_TYPE)
                .taskQueue("default")
                .status(WorkflowStatus.FAILED)
                .serviceName("test-service")
                .startedAt(now)
                .updatedAt(now)
                .completedAt(now)
                .version(0)
                .build());
        executor.shutdown();

        var dispatcherLog = (Logger) LoggerFactory.getLogger(AdminCommandDispatcher.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        dispatcherLog.addAppender(appender);
        try {
            assertThrows(ExecutorShuttingDownException.class,
                    () -> dispatcher.dispatch(new SignalMessage("wf-draining", "$maestro:retry", null)));
        } finally {
            dispatcherLog.detachAppender(appender);
        }

        assertEquals(List.of(), appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList(),
                "a refusal by a shutting-down node is not an ERROR");
        var warnings = appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertEquals(1, warnings.size(), appender.list.toString());
        assertEquals(null, warnings.getFirst().getThrowableProxy(), "no stack trace for a routine refusal");
        assertEquals(WorkflowStatus.FAILED, store.getInstance("wf-draining").orElseThrow().status());
    }

    /**
     * PR #75 review 4626925586: only the executor's own admission refusal is
     * logged as a routine WARN. Any other {@code IllegalStateException} while
     * the node shuts down — here a store failure inside Terminate, which has
     * no admission check — keeps its ERROR and its stack trace.
     */
    @Test
    @DisplayName("an IllegalStateException from the store during a Terminate while shutting down stays an ERROR with its stack trace")
    void aStoreFailureDuringShutdownIsNotReportedAsAShutdownRefusal() throws NoSuchMethodException {
        var real = new InMemoryWorkflowStore();
        var failing = new java.util.concurrent.atomic.AtomicBoolean();
        var faulty = (io.b2mash.maestro.core.spi.WorkflowStore) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{io.b2mash.maestro.core.spi.WorkflowStore.class},
                (proxy, method, args) -> {
                    if (failing.get() && method.getName().equals("getInstance")) {
                        throw new IllegalStateException("the store's pool is closed (test)");
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        var node = WorkflowExecutor.builder(faulty, new PayloadSerializer(mapper), "test-service")
                .distributedLock(new InMemoryDistributedLock()).build();
        var registration = new WorkflowRegistration(WORKFLOW_TYPE, "default", SimpleWorkflow::new,
                SimpleWorkflow.class.getMethod("run", String.class));
        var terminating = new AdminCommandDispatcher(node, real, new WorkflowRegistrar(node) {
            @Override
            public WorkflowRegistration getRegistration(String workflowType) {
                return registration;
            }
        });
        node.shutdown();
        failing.set(true);

        var dispatcherLog = (Logger) LoggerFactory.getLogger(AdminCommandDispatcher.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        dispatcherLog.addAppender(appender);
        try {
            assertThrows(IllegalStateException.class,
                    () -> terminating.dispatch(new SignalMessage("wf-any", "$maestro:terminate", null)));
        } finally {
            dispatcherLog.detachAppender(appender);
        }

        var errors = appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
        assertEquals(1, errors.size(), appender.list.toString());
        assertTrue(errors.getFirst().getThrowableProxy() != null, "the store failure keeps its stack trace");
        assertEquals(List.of(), appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList(),
                "not reported as a shutdown refusal");
    }

    private WorkflowEvent event(UUID instanceId, int seq, EventType type, String step, String json) {
        return new WorkflowEvent(UUID.randomUUID(), instanceId, seq, type, step, mapper.readTree(json), Instant.now());
    }

    /** Completes immediately; never reached by a refused retry. */
    public static class SimpleWorkflow {
        /**
         * @param input the seed
         * @return the seed, unchanged
         */
        public String run(String input) {
            return input;
        }
    }
}
