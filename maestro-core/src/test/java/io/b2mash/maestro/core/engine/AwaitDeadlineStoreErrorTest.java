package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.SignalTimeoutException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins R39 end to end: a store error raised by the timeout decision's own
 * calls — the deadline self-fire and the signal re-read after the deadline —
 * is an infrastructure blip, not a workflow failure. Before the fix it
 * propagated out of {@code awaitSignal} into workflow code, so the executor
 * recorded {@code WORKFLOW_FAILED} and ran the saga's compensations for work
 * that never failed. Now the await keeps parking and decides on the next pass.
 */
@DisplayName("A store error inside the awaitSignal timeout decision never fails the run (R39)")
class AwaitDeadlineStoreErrorTest {

    private DeadlineFaultStore store;
    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        store = new DeadlineFaultStore();
        executor = new WorkflowExecutor(store, null, null, null, new PayloadSerializer(new ObjectMapper()),
                "test-service", WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX,
                WorkflowInstanceLockManager.DEFAULT_LOCK_TTL,
                false, WorkflowExecutor.DEFAULT_SHUTDOWN_TIMEOUT, Duration.ofMillis(100));
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    @DisplayName("self-fire throws once → the await times out, the workflow completes, nothing is compensated")
    void selfFireError_workflowCompletesWithoutCompensation() throws Exception {
        store.failNextSelfFire = true;
        assertCompletesWithTimeoutOutcome("deadline-selffire");
    }

    @Test
    @DisplayName("post-deadline signal re-read throws once → the await times out, the workflow completes, nothing is compensated")
    void lateReadError_workflowCompletesWithoutCompensation() throws Exception {
        store.failLateReadAfterSelfFire = true;
        assertCompletesWithTimeoutOutcome("deadline-lateread");
    }

    private void assertCompletesWithTimeoutOutcome(String workflowId) throws Exception {
        var compensations = new AtomicInteger();
        var impl = new TimingOutSaga(compensations);
        var instanceId = executor.startWorkflow(workflowId, "TimingOutSaga", "default", null, () -> impl,
                TimingOutSaga.class.getMethod("run"));

        await().atMost(Duration.ofSeconds(10)).until(() -> !executor.isRunning(workflowId));

        assertEquals(1, store.injected.get(), "the fault must actually have been hit");
        assertEquals(WorkflowStatus.COMPLETED, store.getInstance(workflowId).orElseThrow().status());
        assertEquals(0, count(instanceId, EventType.WORKFLOW_FAILED), "a store blip is not a workflow failure");
        assertEquals(1, count(instanceId, EventType.SIGNAL_TIMEOUT));
        assertEquals(0, compensations.get(), "no compensation may run for work that never failed");
        assertEquals("timed-out", impl.outcome);
    }

    private long count(UUID instanceId, EventType type) {
        return store.getEvents(instanceId).stream().filter(e -> e.eventType() == type).count();
    }

    /** Registers a compensation, then awaits a signal that never comes and handles the timeout. */
    public static class TimingOutSaga {
        private final AtomicInteger compensations;
        volatile String outcome = "unset";

        public TimingOutSaga(AtomicInteger compensations) {
            this.compensations = compensations;
        }

        public String run() {
            var ctx = WorkflowContext.current();
            ctx.addCompensation(compensations::incrementAndGet);
            try {
                outcome = ctx.awaitSignal("approval", String.class, Duration.ofMillis(300));
            } catch (SignalTimeoutException e) {
                outcome = "timed-out";
            }
            return outcome;
        }
    }

    /** Throws once from the deadline self-fire, or from the signal re-read that follows it. */
    private static final class DeadlineFaultStore extends VersionedInMemoryStore {
        volatile boolean failNextSelfFire;
        volatile boolean failLateReadAfterSelfFire;
        private volatile boolean selfFireSeen;
        final AtomicInteger injected = new AtomicInteger();

        @Override
        public boolean markTimerFired(UUID timerId) {
            selfFireSeen = true;
            if (failNextSelfFire) {
                failNextSelfFire = false;
                injected.incrementAndGet();
                throw new RuntimeException("store blip at the deadline (self-fire, injected)");
            }
            return super.markTimerFired(timerId);
        }

        @Override
        public List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName) {
            if (failLateReadAfterSelfFire && selfFireSeen) {
                failLateReadAfterSelfFire = false;
                injected.incrementAndGet();
                throw new RuntimeException("store blip at the deadline (late signal read, injected)");
            }
            return super.getUnconsumedSignals(workflowId, signalName);
        }
    }
}
