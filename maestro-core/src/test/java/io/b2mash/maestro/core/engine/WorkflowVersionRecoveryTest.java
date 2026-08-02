package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The headline versioning guarantee, end-to-end through the executor: an
 * instance that recorded a version keeps it across a node replacement running
 * <em>different code</em>.
 *
 * <p>The redeploy is simulated the way it actually happens — the recovering
 * node registers a workflow implementation whose {@code maxSupported} has
 * moved on. A workflow that took the new branch mid-flight here would be the
 * exact production incident the API exists to prevent (a long-lived order
 * shipping through v2 logic having already reserved stock through v1).
 */
@DisplayName("A recorded version survives a redeploy that raises maxSupported")
class WorkflowVersionRecoveryTest {

    private VersionedInMemoryStore store;
    private PayloadSerializer serializer;
    private WorkflowExecutor nodeA;
    private WorkflowExecutor nodeB;

    @BeforeEach
    void setUp() {
        store = new VersionedInMemoryStore();
        serializer = new PayloadSerializer(JsonMapper.builder().build());
        nodeA = new WorkflowExecutor(store, null, null, null, serializer, "node-a");
    }

    @AfterEach
    void tearDown() {
        if (nodeA != null) nodeA.shutdown();
        if (nodeB != null) nodeB.shutdown();
    }

    @Test
    @DisplayName("the replay returns the recorded version, appends no second marker, and takes the old branch")
    void replayAfterRedeploy_keepsTheRecordedVersion() throws Exception {
        var method = VersionedWorkflow.class.getMethod("run");
        var workflowId = "versioned-order-1";

        // Original run on code declaring maxSupported = 1.
        nodeA.startWorkflow(workflowId, "VersionedWorkflow", "default", null,
                new VersionedWorkflow(1), method);
        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.getInstance(workflowId)
                        .map(i -> i.status() == WorkflowStatus.WAITING_SIGNAL)
                        .orElse(false));

        // Node stops mid-park; a replacement running NEW code adopts the instance.
        nodeA.shutdown();
        nodeA = null;
        nodeB = new WorkflowExecutor(store, null, null, null, serializer, "node-b");
        nodeB.recoverWorkflows(Map.of("VersionedWorkflow",
                new WorkflowRegistration("VersionedWorkflow", "default",
                        new VersionedWorkflow(3), method)));

        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.getInstance(workflowId)
                        .map(i -> i.status() == WorkflowStatus.WAITING_SIGNAL)
                        .orElse(false));
        nodeB.deliverSignal(workflowId, "go", "ship");

        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.getInstance(workflowId)
                        .map(i -> i.status().isTerminal())
                        .orElse(false));

        var instance = store.getInstance(workflowId).orElseThrow();
        var markers = store.getEvents(instance.id()).stream()
                .filter(e -> e.eventType() == EventType.VERSION_MARKER)
                .toList();

        assertAll(
                () -> assertEquals(WorkflowStatus.COMPLETED, instance.status()),
                () -> assertEquals("branch-v1:ship",
                        serializer.deserialize(instance.output(), String.class),
                        "the instance must stay on the branch it recorded, even though the "
                                + "running code now supports a newer one"),
                () -> assertEquals(1, markers.size(),
                        "exactly one durable marker across both runs"),
                () -> assertEquals(1, markers.getFirst().payload().path("version").intValue()),
                () -> assertEquals("shipping-v2", markers.getFirst().payload()
                        .path("changeId").stringValue()));
    }

    // ── fixture ────────────────────────────────────────────────────────

    /** Branches on a memoized version decision, then parks on a signal. */
    @DurableWorkflow(name = "VersionedWorkflow")
    public static class VersionedWorkflow {

        private final int maxSupported;

        /**
         * @param maxSupported the highest branch this deployment of the code carries
         */
        public VersionedWorkflow(int maxSupported) {
            this.maxSupported = maxSupported;
        }

        /**
         * @return the branch taken plus the signal payload
         */
        @WorkflowMethod
        public String run() {
            var workflow = WorkflowContext.current();
            var version = workflow.version("shipping-v2", -1, maxSupported);
            var branch = version >= 3 ? "branch-v3" : "branch-v1";
            var payload = workflow.awaitSignal("go", String.class, Duration.ofSeconds(30));
            return branch + ":" + payload;
        }
    }
}
