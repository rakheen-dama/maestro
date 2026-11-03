package io.b2mash.maestro.integration.engine;

import io.b2mash.maestro.core.engine.TaskQueueLimits;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.workflows.CountingActivities.ChainActivities;
import io.b2mash.maestro.integration.workflows.CountingActivities.Recorder;
import io.b2mash.maestro.integration.workflows.TestWorkflows.ChainWorkflow;
import io.b2mash.maestro.integration.workflows.TestWorkflows.ParallelSleepWorkflow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * F8 / Issue 25 against a real Postgres store: with
 * {@code maestro.worker.task-queues[default].concurrency = 1}, a second
 * workflow on the queue does not start while the first is executing, and both
 * still complete with full, correctly sequenced event logs once the first
 * finishes.
 */
@Tag("integration")
@DisplayName("Per-task-queue workflow concurrency over Postgres")
class TaskQueueConcurrencyIT extends PostgresIntegrationSupport {

    private final CountDownLatch latch = new CountDownLatch(1);
    private MaestroEngineHarness harness;

    @AfterEach
    void closeHarness() {
        latch.countDown();
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("concurrency=1 runs a queue's workflows one at a time, and both complete")
    void concurrencyOneSerialisesTheQueue() {
        var recorder = new Recorder();
        harness = MaestroEngineHarness.builder(store, objectMapper)
                .taskQueueLimits(Map.of("default", new TaskQueueLimits(1, 10)))
                .build()
                .registerActivities(ChainActivities.class, new LatchingChainActivities(recorder, latch))
                .registerWorkflow(ChainWorkflow.class);

        var first = MaestroEngineHarness.uniqueWorkflowId("tq-first");
        var second = MaestroEngineHarness.uniqueWorkflowId("tq-second");
        harness.start(first, ChainWorkflow.class, "a");
        harness.start(second, ChainWorkflow.class, "b");

        await().atMost(Duration.ofSeconds(10)).until(() -> recorder.count("stepOne") == 1);
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5))
                .until(() -> recorder.count("stepOne") == 1); // the second waits for the slot

        latch.countDown();
        assertEquals(WorkflowStatus.COMPLETED, awaitTerminal(first, Duration.ofSeconds(15)).status());
        assertEquals(WorkflowStatus.COMPLETED, awaitTerminal(second, Duration.ofSeconds(15)).status());
        assertEquals(2, recorder.count("stepOne"));

        for (var workflowId : List.of(first, second)) {
            var instance = store.getInstance(workflowId).orElseThrow();
            var events = store.getEvents(instance.id()).stream()
                    .map(e -> e.sequenceNumber() + ":" + e.eventType())
                    .toList();
            assertEquals(List.of("1:" + EventType.ACTIVITY_COMPLETED, "2:" + EventType.ACTIVITY_COMPLETED,
                            "3:" + EventType.ACTIVITY_COMPLETED, "4:" + EventType.WORKFLOW_COMPLETED),
                    events, "complete event log for " + workflowId);
        }
    }

    /**
     * R49: both branches of a {@code parallel()} run wake from one batch of
     * timer fires while another run holds the queue's only slot. When that
     * slot is freed the run must take it once and let both branches finish —
     * before the per-run lock the second branch queued on the semaphore for
     * the permit its own run held, and the run never completed.
     */
    @Test
    @DisplayName("concurrency=1: a parallel() of two sleeps fired together completes")
    void parallelSleepsFiredTogetherComplete() {
        var recorder = new Recorder();
        harness = MaestroEngineHarness.builder(store, objectMapper)
                .taskQueueLimits(Map.of("default", new TaskQueueLimits(1, 10)))
                .build()
                .registerActivities(ChainActivities.class, new LatchingChainActivities(recorder, latch))
                .registerWorkflow(ParallelSleepWorkflow.class)
                .registerWorkflow(ChainWorkflow.class);

        var parallel = MaestroEngineHarness.uniqueWorkflowId("tq-parallel");
        var blocker = MaestroEngineHarness.uniqueWorkflowId("tq-blocker");
        harness.start(parallel, ParallelSleepWorkflow.class, "p");
        await().atMost(Duration.ofSeconds(10)).until(() -> harness.executor().parkedCount() == 2);
        harness.start(blocker, ChainWorkflow.class, "b");
        await().atMost(Duration.ofSeconds(10)).until(() -> recorder.count("stepOne") == 1); // holds the slot

        var instanceId = store.getInstance(parallel).orElseThrow().id();
        await().atMost(Duration.ofSeconds(5)).until(() -> // both 50 ms timers are due
                store.getDueTimers(Instant.now(), 10).stream()
                        .filter(t -> t.workflowInstanceId().equals(instanceId)).count() == 2);
        assertEquals(2, harness.fireDueTimers(10), "both timers fire in one batch");
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5))
                .until(() -> harness.executor().parkedCount() == 0); // both branches await the slot

        latch.countDown(); // the blocker finishes and frees the slot
        assertEquals(WorkflowStatus.COMPLETED, awaitTerminal(blocker, Duration.ofSeconds(15)).status());
        var done = awaitTerminal(parallel, Duration.ofSeconds(15));
        assertEquals(WorkflowStatus.COMPLETED, done.status());
        assertEquals(OptionalInt.of(1), harness.executor().taskQueueGovernor().availableWorkflowSlots("default"));
    }

    /** Chain activities whose first step blocks on a latch the test opens. */
    private static final class LatchingChainActivities implements ChainActivities {
        private final Recorder recorder;
        private final CountDownLatch latch;

        LatchingChainActivities(Recorder recorder, CountDownLatch latch) {
            this.recorder = recorder;
            this.latch = latch;
        }

        @Override
        public String stepOne(String input) {
            recorder.record("stepOne");
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return input + "-one";
        }

        @Override
        public String stepTwo(String input) {
            recorder.record("stepTwo");
            return input + "-two";
        }

        @Override
        public String stepThree(String input) {
            recorder.record("stepThree");
            return input + "-three";
        }
    }
}
