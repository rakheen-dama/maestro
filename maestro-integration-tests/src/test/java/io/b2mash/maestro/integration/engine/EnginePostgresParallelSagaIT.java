package io.b2mash.maestro.integration.engine;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.Saga;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.SequenceSpaceExhaustedException;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.support.RecordingObserver;
import io.b2mash.maestro.integration.support.WorkflowHandle;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.CountingActivities.ChainActivities;
import io.b2mash.maestro.integration.workflows.CountingActivities.SagaActivities;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Saga compensation after a failed {@code parallel()} fork, driven by the real
 * engine against a real Postgres store (C1).
 *
 * <p>The sequence numbers asserted here are the invariant: after the fork
 * throws, the parent counter sits past every branch block, so the saga's
 * compensation block does not coincide with a sibling branch's block.
 */
@Tag("integration")
@DisplayName("Saga compensation after a failed parallel() fork, against a real Postgres store")
class EnginePostgresParallelSagaIT extends PostgresIntegrationSupport {

    private final CountDownLatch reachedRefund = new CountDownLatch(1);
    private final CountDownLatch wedge = new CountDownLatch(1);

    private @Nullable MaestroEngineHarness harness;
    private @Nullable MaestroEngineHarness recoveringNode;

    @AfterEach
    void releaseAndClose() {
        wedge.countDown();
        if (recoveringNode != null) {
            recoveringNode.close();
            recoveringNode = null;
        }
        if (harness != null) {
            harness.close();
            harness = null;
        }
    }

    /** Reserve and charge fork in parallel, then ship; the saga compensates on failure. */
    @DurableWorkflow(name = "ParallelSagaWorkflow")
    public static class ParallelSagaWorkflow {

        @ActivityStub
        SagaActivities activities;

        /**
         * @param item the item being ordered
         * @return the shipping result when no step fails
         */
        @Saga
        @WorkflowMethod
        public String run(String item) {
            WorkflowContext.current().parallel(List.<Callable<String>>of(
                    () -> activities.reserve(item), () -> activities.charge(item)));
            return activities.ship(item);
        }
    }

    /** The linear saga body, with parallel compensation enabled. */
    @DurableWorkflow(name = "ParallelCompensationSagaWorkflow")
    public static class ParallelCompensationSagaWorkflow {

        @ActivityStub
        SagaActivities activities;

        /**
         * @param item the item being ordered
         * @return the shipping result when no step fails
         */
        @Saga(parallelCompensation = true)
        @WorkflowMethod
        public String run(String item) {
            activities.reserve(item);
            activities.charge(item);
            return activities.ship(item);
        }
    }

    /** Registers one compensation, then forks three times — the third fork cannot be sequenced (C4). */
    @DurableWorkflow(name = "TripleForkWorkflow")
    public static class TripleForkWorkflow {

        final AtomicBoolean undone = new AtomicBoolean();

        /**
         * @param input echoed back if the workflow ever completed (it cannot)
         * @return {@code input}
         */
        @Saga
        @WorkflowMethod
        public String run(String input) {
            var workflow = WorkflowContext.current();
            workflow.addCompensation("undo", () -> undone.set(true));
            workflow.parallel(List.<Callable<String>>of(() -> "a", () -> "b"));
            workflow.parallel(List.<Callable<String>>of(() -> "a", () -> "b"));
            workflow.parallel(List.<Callable<String>>of(() -> "a", () -> "b"));
            return input;
        }
    }

    /**
     * Registers an inline compensation that itself fans out with {@code parallel()}, then fails.
     * A {@code Runnable} compensation is the only path where author code runs on the workflow
     * thread during compensation.
     */
    @DurableWorkflow(name = "FanOutCompensationWorkflow")
    public static class FanOutCompensationWorkflow {

        @ActivityStub
        ChainActivities chain;

        /**
         * @param input the seed value
         * @return never; the workflow always fails
         */
        @Saga
        @WorkflowMethod
        public String run(String input) {
            chain.stepOne(input);
            WorkflowContext.current().addCompensation("undoFanOut", () ->
                    WorkflowContext.current().parallel(List.<Callable<String>>of(
                            () -> chain.stepTwo(input), () -> chain.stepThree(input))));
            throw new IllegalStateException("boom");
        }
    }

    /**
     * Decorator whose {@code refund} signals arrival and then blocks until the test
     * opens the wedge — a node frozen mid-parallel-compensation.
     *
     * <p>Thread-safe: the latches carry the happens-before edge.
     */
    private final class WedgingRefund implements SagaActivities {

        private final SagaActivities delegate;

        WedgingRefund(SagaActivities delegate) {
            this.delegate = delegate;
        }

        @Override
        public String reserve(String item) {
            return delegate.reserve(item);
        }

        @Override
        public void releaseReservation(String item) {
            delegate.releaseReservation(item);
        }

        @Override
        public String charge(String item) {
            return delegate.charge(item);
        }

        @Override
        public void refund(String item) {
            reachedRefund.countDown();
            try {
                if (!wedge.await(60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Wedge was never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while wedged", e);
            }
            delegate.refund(item);
        }

        @Override
        public String ship(String item) {
            return delegate.ship(item);
        }
    }

    @Test
    @DisplayName("reserve ∥ charge, charge fails: releaseReservation runs once and lands past the branch space")
    void failedBranch_compensatesInItsOwnBlock() {
        var recorder = new CountingActivities.Recorder();
        harness = nodeFor(ParallelSagaWorkflow.class, "parallel-saga",
                new CountingActivities.RecordingSagaActivities(recorder, "charge"), true, null);
        var handle = harness.start(MaestroEngineHarness.uniqueWorkflowId("parallel-saga"),
                ParallelSagaWorkflow.class, "widget");

        assertEquals(WorkflowStatus.FAILED, handle.awaitTerminal(Duration.ofSeconds(30)));
        assertEquals(1, recorder.count("reserve"));
        assertEquals(3, recorder.count("charge"), "the fixture's @ActivityStub default is three attempts");
        assertEquals(0, recorder.count("ship"), "the fork failed, so ship never ran");
        assertEquals(1, recorder.count("releaseReservation"),
                "the compensation must execute, not replay saga.charge's failure");
        assertEquals(0, recorder.count("refund"), "charge never succeeded, so nothing to refund");
        assertEquals(List.of(
                "1:SIDE_EFFECT:$maestro:parallel",
                "2001:ACTIVITY_COMPLETED:saga.reserve",
                "3001:ACTIVITY_FAILED:saga.charge",
                "4001:COMPENSATION_STARTED:$maestro:compensation",
                "4002000:COMPENSATION_STEP_COMPLETED:saga.releaseReservation",
                "4002001:ACTIVITY_COMPLETED:saga.releaseReservation",
                "4003001:COMPENSATION_COMPLETED:$maestro:compensation",
                "4003002:WORKFLOW_FAILED:null"),
                describe(handle));
    }

    @Test
    @DisplayName("a node that dies mid-parallel-compensation is finished by a recovering node — no stand-down, no stuck COMPENSATING")
    void crashMidParallelCompensation_recoversToFailed() throws Exception {
        var recorderA = new CountingActivities.Recorder();
        harness = nodeFor(ParallelCompensationSagaWorkflow.class, "node-a",
                new WedgingRefund(new CountingActivities.RecordingSagaActivities(recorderA, "ship")), false, null);
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("parallel-comp-crash");
        var handle = harness.start(workflowId, ParallelCompensationSagaWorkflow.class, "widget");

        assertTrue(reachedRefund.await(30, TimeUnit.SECONDS), "node A never reached the parallel compensation phase");
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(50))
                .until(() -> store.getEventBySequence(handle.instanceId(), 7000).isPresent());
        assertEquals(WorkflowStatus.COMPENSATING, handle.status());
        assertTrue(store.getEventBySequence(handle.instanceId(), 6000).isEmpty(), "the wedged refund branch must have recorded nothing");
        assertEquals(1, recorderA.count("releaseReservation"));

        var recorderB = new CountingActivities.Recorder();
        var observerB = new RecordingObserver();
        recoveringNode = nodeFor(ParallelCompensationSagaWorkflow.class, "node-b",
                new CountingActivities.RecordingSagaActivities(recorderB, "ship"), true, observerB);
        assertEquals(1, recoveringNode.recover());

        var instance = awaitTerminal(workflowId, Duration.ofSeconds(15));
        assertEquals(WorkflowStatus.FAILED, instance.status(), "the compensated saga must finish FAILED, not sit in COMPENSATING");
        assertEquals(List.of(), observerB.standDowns(), "re-appending the fork marker used to stand the recovering run down");
        assertEquals(0, recorderB.count("ship"), "ship's ACTIVITY_FAILED replays — no live retries");
        assertEquals(1, recorderB.count("refund"), "the interrupted branch runs exactly once on the recovering node");
        assertEquals(0, recorderB.count("releaseReservation"), "the durable branch replays");
        assertEquals(List.of(
                "1:ACTIVITY_COMPLETED:saga.reserve",
                "2:ACTIVITY_COMPLETED:saga.charge",
                "3:ACTIVITY_FAILED:saga.ship",
                "4:COMPENSATION_STARTED:$maestro:compensation",
                "5:SIDE_EFFECT:$maestro:parallel-compensation",
                "6000:COMPENSATION_STEP_COMPLETED:saga.refund",
                "6001:ACTIVITY_COMPLETED:saga.refund",
                "7000:COMPENSATION_STEP_COMPLETED:saga.releaseReservation",
                "7001:ACTIVITY_COMPLETED:saga.releaseReservation",
                "8001:COMPENSATION_COMPLETED:$maestro:compensation",
                "8002:WORKFLOW_FAILED:null"),
                describe(instance.id()));
    }

    @Test
    @DisplayName("a workflow that exhausts the sequence space finishes FAILED with no compensation and no stand-down")
    void sequenceSpaceExhaustion_isALoudFailureNotAZombie() {
        var observer = new RecordingObserver();
        var workflow = new TripleForkWorkflow();
        harness = MaestroEngineHarness.builder(store, objectMapper).serviceName("triple-fork").lock(newLock())
                .observer(observer).build();
        harness.registerWorkflow(workflow);
        var handle = harness.start(MaestroEngineHarness.uniqueWorkflowId("triple-fork"), TripleForkWorkflow.class, "in");

        assertEquals(WorkflowStatus.FAILED, handle.awaitTerminal(Duration.ofSeconds(15)));
        assertEquals(SequenceSpaceExhaustedException.class.getName(),
                handle.instance().output().get("exceptionType").stringValue());
        assertFalse(workflow.undone.get(), "no compensation may run at a wrapped sequence");
        assertEquals(List.of(), observer.standDowns());
        assertEquals(List.of(
                "1:SIDE_EFFECT:$maestro:parallel",
                "4001:SIDE_EFFECT:$maestro:parallel",
                "4004001:WORKFLOW_FAILED:null"),
                describe(handle));
    }

    @Test
    @DisplayName("a compensation that fans out runs each branch once, in the entry's own block, past every forward slot")
    void compensationThatForks_runsOnceInItsOwnBlock() {
        var recorder = new CountingActivities.Recorder();
        var observer = new RecordingObserver();
        harness = MaestroEngineHarness.builder(store, objectMapper).serviceName("fan-out-comp").lock(newLock())
                .observer(observer).build();
        harness.registerActivities(ChainActivities.class, new CountingActivities.RecordingChainActivities(recorder));
        harness.registerWorkflow(new FanOutCompensationWorkflow());
        var handle = harness.start(MaestroEngineHarness.uniqueWorkflowId("fan-out-comp"),
                FanOutCompensationWorkflow.class, "in");

        assertEquals(WorkflowStatus.FAILED, handle.awaitTerminal(Duration.ofSeconds(30)));
        assertEquals(1, recorder.count("stepOne"));
        assertEquals(1, recorder.count("stepTwo"));
        assertEquals(1, recorder.count("stepThree"));
        assertEquals(List.of(
                "1:ACTIVITY_COMPLETED:chain.stepOne",
                "2:COMPENSATION_STARTED:$maestro:compensation",
                "3000:COMPENSATION_STEP_COMPLETED:undoFanOut",
                "3001:SIDE_EFFECT:$maestro:parallel",
                "4001:COMPENSATION_COMPLETED:$maestro:compensation",
                "4002:WORKFLOW_FAILED:null",
                "3002001:ACTIVITY_COMPLETED:chain.stepTwo",
                "3003001:ACTIVITY_COMPLETED:chain.stepThree"),
                describe(handle));
        assertEquals(List.of(), observer.standDowns());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private MaestroEngineHarness nodeFor(Class<?> workflowClass, String serviceName, SagaActivities activities,
                                         boolean withLock, @Nullable RecordingObserver observer) {
        var builder = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName(serviceName)
                .lock(withLock ? newLock() : null)
                .instanceLockTtl(Duration.ofSeconds(2));
        if (observer != null) {
            builder.observer(observer);
        }
        var built = builder.build();
        built.registerActivities(SagaActivities.class, activities);
        try {
            built.registerWorkflow(workflowClass.getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot instantiate " + workflowClass, e);
        }
        return built;
    }

    private List<String> describe(UUID instanceId) {
        return store.getEvents(instanceId).stream()
                .map(e -> e.sequenceNumber() + ":" + e.eventType() + ":" + e.stepName())
                .toList();
    }

    private static List<String> describe(WorkflowHandle handle) {
        return handle.events().stream()
                .map(e -> e.sequenceNumber() + ":" + e.eventType() + ":" + e.stepName())
                .toList();
    }
}
