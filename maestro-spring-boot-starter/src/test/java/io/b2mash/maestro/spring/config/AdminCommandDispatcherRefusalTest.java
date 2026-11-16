package io.b2mash.maestro.spring.config;

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
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * WP3: a {@code $maestro:retry} that the engine refuses with
 * {@link WorkflowExecutor.RetryOutcome#SIGNAL_CONSUMED_AFTER_FAILURE} is a
 * handled outcome. The dispatcher returns normally, so the transport
 * acknowledges the command instead of redelivering it to the dead-letter
 * topic, and nothing is written.
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
        executor = WorkflowExecutor.builder(store, new PayloadSerializer(mapper), "test-service").build();
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

        assertDoesNotThrow(() -> dispatcher.dispatch(new SignalMessage("wf-refused", "$maestro:retry", null)));

        var after = store.getInstance("wf-refused").orElseThrow();
        assertEquals(WorkflowStatus.FAILED, after.status());
        assertEquals(0, after.version(), "the refusal must not write the row");
        assertEquals(3, store.getEvents(instanceId).size(), "the refusal must not delete an event");
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
