package io.b2mash.maestro.integration.engine;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.support.TerminalEventDelayStore;
import io.b2mash.maestro.integration.support.TerminalWait;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the await predicate every suite here depends on: a workflow is not
 * finished when its status says so, but when its terminal event is in the log.
 *
 * <h2>What broke</h2>
 * <p>{@code WorkflowExecutor} finalises a run with two separate,
 * non-transactional writes — instance row first, {@code WORKFLOW_COMPLETED}
 * event second. GitHub Actions run 30728290264 reddened {@code main} when
 * {@code EnginePostgresParallelIT} polled on the status, landed in that gap and
 * read four events where five were expected, the missing one being
 * {@code 5001:WORKFLOW_COMPLETED}. That shape had been copy-pasted across six
 * suites.
 *
 * <h2>Why this test and not a repeat loop</h2>
 * <p>The window is one database round trip wide, so hammering the real engine
 * reproduces it at a low single-digit percent — useless as a regression pin.
 * {@link TerminalEventDelayStore} stretches the window to seconds instead, which
 * makes the race deterministic in both directions: the first test proves a
 * status-only predicate really does observe the missing event (a pin that cannot
 * observe the symptom proves nothing), and the rest prove the shipped predicates
 * do not.
 */
@Tag("integration")
@DisplayName("A terminal status is not a finished workflow")
class TerminalEventRaceIT extends PostgresIntegrationSupport {

    /** Long enough that any 50–100ms poller lands inside the window. */
    private static final Duration APPEND_DELAY = Duration.ofSeconds(3);
    private static final Duration BOUND = Duration.ofSeconds(30);

    private MaestroEngineHarness harness;
    private TerminalEventDelayStore delayingStore;

    @AfterEach
    void closeHarness() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("a status-only wait observes a COMPLETED workflow whose terminal event is missing")
    void statusOnlyWait_seesTheGap() {
        var handle = startChain("terminal-gap");

        // The predicate that reddened main: instance row only.
        await().atMost(BOUND)
                .pollInterval(Duration.ofMillis(50))
                .until(() -> store.getInstance(handle.workflowId())
                        .map(i -> i.status().isTerminal())
                        .orElse(false));

        assertEquals(WorkflowStatus.COMPLETED, handle.status());
        assertFalse(hasTerminalEvent(handle.instanceId()),
                "the window must actually be open here — otherwise every assertion "
                        + "below is vacuous and this suite proves nothing");
        assertEquals(List.of(1, 2, 3),
                store.getEvents(handle.instanceId()).stream().map(e -> e.sequenceNumber()).toList(),
                "the three activity events are durable; the run-closing event is not yet");

        // And it does land — the engine is correct, the predicate was not.
        await().atMost(BOUND).until(() -> hasTerminalEvent(handle.instanceId()));
        assertEquals(1, delayingStore.delayedAppends(),
                "exactly one terminal append must have been held open");
    }

    @Test
    @DisplayName("TerminalWait.awaitTerminal waits for the event, not the status")
    void terminalWait_waitsForTheEvent() {
        var handle = startChain("terminal-wait");

        var instance = TerminalWait.awaitTerminal(store, handle.workflowId(), BOUND);

        assertEquals(WorkflowStatus.COMPLETED, instance.status());
        assertEquals(List.of("1:ACTIVITY_COMPLETED:chain.stepOne",
                        "2:ACTIVITY_COMPLETED:chain.stepTwo",
                        "3:ACTIVITY_COMPLETED:chain.stepThree",
                        "4:WORKFLOW_COMPLETED:null"),
                store.getEvents(instance.id()).stream()
                        .map(e -> e.sequenceNumber() + ":" + e.eventType() + ":" + e.stepName())
                        .toList(),
                "the whole log must be readable the instant the wait returns");
    }

    @Test
    @DisplayName("WorkflowHandle.awaitTerminal waits for the event, not the status")
    void workflowHandleAwaitTerminal_waitsForTheEvent() {
        var handle = startChain("terminal-handle");

        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));
        assertTrue(hasTerminalEvent(handle.instanceId()),
                "awaitTerminal must not return while the run-closing event is still in flight");
        assertEquals(4, handle.events().size());
    }

    @Test
    @DisplayName("the base-class awaitStatus helper waits for the event on a terminal status")
    void baseAwaitStatus_waitsForTheEvent() {
        var handle = startChain("terminal-status");

        var instance = awaitStatus(handle.workflowId(), WorkflowStatus.COMPLETED, BOUND);

        assertTrue(hasTerminalEvent(instance.id()));
        assertEquals(4, store.getEvents(instance.id()).size());
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    /**
     * Starts a three-activity chain on a node whose terminal event append is
     * held open, so the finalisation gap is seconds rather than microseconds.
     *
     * @param prefix workflow ID prefix
     * @return the handle
     */
    private io.b2mash.maestro.integration.support.WorkflowHandle startChain(String prefix) {
        delayingStore = new TerminalEventDelayStore(store, APPEND_DELAY);
        harness = MaestroEngineHarness.builder(delayingStore, objectMapper)
                .serviceName("terminal-race-node")
                .lock(newLock())
                .build();
        harness.registerActivities(CountingActivities.ChainActivities.class,
                new CountingActivities.RecordingChainActivities(new CountingActivities.Recorder()));
        harness.registerWorkflow(new TestWorkflows.ChainWorkflow());
        return harness.start(MaestroEngineHarness.uniqueWorkflowId(prefix),
                TestWorkflows.ChainWorkflow.class, "seed");
    }

    /**
     * @param instanceId the instance to inspect
     * @return whether the run-closing event is already in the durable log
     */
    private boolean hasTerminalEvent(java.util.UUID instanceId) {
        return store.getEvents(instanceId).stream()
                .anyMatch(e -> e.eventType() == EventType.WORKFLOW_COMPLETED
                        || e.eventType() == EventType.WORKFLOW_FAILED);
    }
}
