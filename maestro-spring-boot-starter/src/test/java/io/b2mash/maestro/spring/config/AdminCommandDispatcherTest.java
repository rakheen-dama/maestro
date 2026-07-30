package io.b2mash.maestro.spring.config;

import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.engine.WorkflowRegistration;
import io.b2mash.maestro.core.exception.AdminCommandException;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link AdminCommandDispatcher} against the Issue 15 design's §3.3
 * validation/idempotency table: every deterministic outcome (wrong state,
 * unknown ID) must ack (return normally); every genuinely unroutable command
 * must throw so the transport does not ack.
 *
 * <p>Uses a real {@link WorkflowExecutor} over the in-memory store — like
 * {@link SignalSubscriptionRunnerTest} — rather than a mock, since
 * {@code WorkflowExecutor} is {@code final} and the behaviour under test is
 * "does the dispatcher translate a real outcome into ack-or-throw correctly."
 */
class AdminCommandDispatcherTest {

    private static final String WORKFLOW_TYPE = "DispatcherTestWorkflow";

    private InMemoryWorkflowStore store;
    private WorkflowExecutor executor;
    private StubRegistrar registrar;
    private AdminCommandDispatcher dispatcher;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        store = new InMemoryWorkflowStore();
        var serializer = new PayloadSerializer(JsonMapper.builder().build());
        executor = new WorkflowExecutor(store, null, null, null, serializer, "test-service");
        registrar = new StubRegistrar(executor);
        registrar.register(WORKFLOW_TYPE, new WorkflowRegistration(
                WORKFLOW_TYPE, "default", new SimpleWorkflow(), SimpleWorkflow.class.getMethod("run", String.class)));
        dispatcher = new AdminCommandDispatcher(executor, store, registrar);
    }

    // ── retry ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("$maestro:retry on a FAILED workflow relaunches it and acks")
    void retryFailedWorkflow_relaunchesAndAcks() {
        seedInstance("wf-retry", WorkflowStatus.FAILED);

        assertDoesNotThrow(() ->
                dispatcher.dispatch(new SignalMessage("wf-retry", "$maestro:retry", null)));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(WorkflowStatus.COMPLETED, store.getInstance("wf-retry").orElseThrow().status()));
    }

    @Test
    @DisplayName("$maestro:retry on a RUNNING workflow is an acknowledged no-op (NOT_FAILED)")
    void retryRunningWorkflow_isAckedNoOp() {
        seedInstance("wf-retry-running", WorkflowStatus.RUNNING);

        assertDoesNotThrow(() ->
                dispatcher.dispatch(new SignalMessage("wf-retry-running", "$maestro:retry", null)));

        assertEquals(WorkflowStatus.RUNNING, store.getInstance("wf-retry-running").orElseThrow().status());
    }

    @Test
    @DisplayName("$maestro:retry on a COMPLETED workflow is an acknowledged no-op (NOT_FAILED)")
    void retryCompletedWorkflow_isAckedNoOp() {
        seedInstance("wf-retry-completed", WorkflowStatus.COMPLETED);

        assertDoesNotThrow(() ->
                dispatcher.dispatch(new SignalMessage("wf-retry-completed", "$maestro:retry", null)));

        assertEquals(WorkflowStatus.COMPLETED, store.getInstance("wf-retry-completed").orElseThrow().status());
    }

    @Test
    @DisplayName("$maestro:retry on a TERMINATED workflow is an acknowledged no-op (NOT_FAILED)")
    void retryTerminatedWorkflow_isAckedNoOp() {
        seedInstance("wf-retry-terminated", WorkflowStatus.TERMINATED);

        assertDoesNotThrow(() ->
                dispatcher.dispatch(new SignalMessage("wf-retry-terminated", "$maestro:retry", null)));

        assertEquals(WorkflowStatus.TERMINATED, store.getInstance("wf-retry-terminated").orElseThrow().status());
    }

    @Test
    @DisplayName("$maestro:retry on an unknown workflow ID is an acknowledged no-op (NOT_FOUND), "
            + "even with an empty registrar")
    void retryUnknownWorkflow_isAckedNoOp() {
        // No instance seeded, and this dispatcher's registrar knows nothing —
        // proves the store lookup happens BEFORE any registration lookup.
        var emptyDispatcher = new AdminCommandDispatcher(executor, store, new StubRegistrar(executor));

        assertDoesNotThrow(() ->
                emptyDispatcher.dispatch(new SignalMessage("no-such-workflow", "$maestro:retry", null)));
    }

    @Test
    @DisplayName("$maestro:retry for a workflow type with no registration throws (redeliver -> DLT)")
    void retryWithMissingRegistration_throws() {
        seedInstance("wf-retry-unregistered", WorkflowStatus.FAILED, "SomeOtherType");

        assertThrows(AdminCommandException.class, () ->
                dispatcher.dispatch(new SignalMessage("wf-retry-unregistered", "$maestro:retry", null)));

        // The instance must be untouched — the dispatcher never reached retryWorkflow.
        assertEquals(WorkflowStatus.FAILED, store.getInstance("wf-retry-unregistered").orElseThrow().status());
    }

    // ── terminate ────────────────────────────────────────────────────────

    @Test
    @DisplayName("$maestro:terminate on an active workflow terminates it and acks")
    void terminateActiveWorkflow_terminatesAndAcks() {
        seedInstance("wf-terminate", WorkflowStatus.WAITING_SIGNAL);

        assertDoesNotThrow(() ->
                dispatcher.dispatch(new SignalMessage("wf-terminate", "$maestro:terminate", null)));

        assertEquals(WorkflowStatus.TERMINATED, store.getInstance("wf-terminate").orElseThrow().status());
    }

    @Test
    @DisplayName("$maestro:terminate extracts the reason field from the payload when present")
    void terminateWithReasonPayload_extractsReason() {
        seedInstance("wf-terminate-reason", WorkflowStatus.RUNNING);
        var payload = JsonMapper.builder().build().createObjectNode().put("reason", "operator requested shutdown");

        dispatcher.dispatch(new SignalMessage("wf-terminate-reason", "$maestro:terminate", payload));

        var output = store.getInstance("wf-terminate-reason").orElseThrow().output();
        assertEquals("operator requested shutdown", output.get("reason").asString());
    }

    @Test
    @DisplayName("$maestro:terminate tolerates a null payload")
    void terminateWithNullPayload_isTolerated() {
        seedInstance("wf-terminate-null-payload", WorkflowStatus.RUNNING);

        assertDoesNotThrow(() ->
                dispatcher.dispatch(new SignalMessage("wf-terminate-null-payload", "$maestro:terminate", null)));

        assertEquals(WorkflowStatus.TERMINATED,
                store.getInstance("wf-terminate-null-payload").orElseThrow().status());
    }

    @Test
    @DisplayName("$maestro:terminate on an already-terminal workflow is an acknowledged no-op (ALREADY_TERMINAL)")
    void terminateTerminalWorkflow_isAckedNoOp() {
        seedInstance("wf-terminate-terminal", WorkflowStatus.COMPLETED);

        assertDoesNotThrow(() ->
                dispatcher.dispatch(new SignalMessage("wf-terminate-terminal", "$maestro:terminate", null)));

        assertEquals(WorkflowStatus.COMPLETED, store.getInstance("wf-terminate-terminal").orElseThrow().status());
    }

    @Test
    @DisplayName("$maestro:terminate on an unknown workflow ID is an acknowledged no-op (NOT_FOUND)")
    void terminateUnknownWorkflow_isAckedNoOp() {
        assertDoesNotThrow(() ->
                dispatcher.dispatch(new SignalMessage("no-such-workflow", "$maestro:terminate", null)));
    }

    // ── unknown command ──────────────────────────────────────────────────

    @Test
    @DisplayName("an unknown $maestro:* command throws AdminCommandException (redeliver -> DLT)")
    void unknownCommand_throws() {
        assertThrows(AdminCommandException.class, () ->
                dispatcher.dispatch(new SignalMessage("wf-anything", "$maestro:bogus", null)));
    }

    // ── invisibility to awaitSignal ─────────────────────────────────────

    @Test
    @DisplayName("dispatching retry and terminate never persists a WorkflowSignal row")
    void dispatchedCommands_neverPersistSignalRows() {
        seedInstance("wf-invisible", WorkflowStatus.FAILED);
        dispatcher.dispatch(new SignalMessage("wf-invisible", "$maestro:retry", null));
        assertTrue(store.getUnconsumedSignals("wf-invisible", "$maestro:retry").isEmpty());

        seedInstance("wf-invisible-2", WorkflowStatus.RUNNING);
        dispatcher.dispatch(new SignalMessage("wf-invisible-2", "$maestro:terminate", null));
        assertTrue(store.getUnconsumedSignals("wf-invisible-2", "$maestro:terminate").isEmpty());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private void seedInstance(String workflowId, WorkflowStatus status) {
        seedInstance(workflowId, status, WORKFLOW_TYPE);
    }

    private void seedInstance(String workflowId, WorkflowStatus status, String workflowType) {
        var now = Instant.now();
        store.createInstance(WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId(workflowId)
                .runId(UUID.randomUUID())
                .workflowType(workflowType)
                .taskQueue("default")
                .status(status)
                .serviceName("test-service")
                .startedAt(now)
                .updatedAt(now)
                .completedAt(status.isTerminal() ? now : null)
                .version(0)
                .build());
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    /** Completes immediately — no activities, so retry has nothing to replay. */
    public static class SimpleWorkflow {
        /**
         * @param input the seed
         * @return the seed, unchanged
         */
        public String run(String input) {
            return input;
        }
    }

    /** A {@link WorkflowRegistrar} populated directly, bypassing Spring bean scanning. */
    private static class StubRegistrar extends WorkflowRegistrar {
        private final Map<String, WorkflowRegistration> byType = new HashMap<>();

        StubRegistrar(WorkflowExecutor executor) {
            super(executor);
        }

        void register(String workflowType, WorkflowRegistration registration) {
            byType.put(workflowType, registration);
        }

        @Override
        public WorkflowRegistration getRegistration(String workflowType) {
            var reg = byType.get(workflowType);
            if (reg == null) {
                throw new IllegalArgumentException("No @DurableWorkflow registration for type '" + workflowType + "'");
            }
            return reg;
        }
    }
}
