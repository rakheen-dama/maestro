package io.b2mash.maestro.integration.engine;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.CountingActivities.SagaActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Saga compensation driven by the real engine against a real Postgres store.
 *
 * <p>Three things are asserted that a unit test against an in-memory store
 * cannot settle: the persisted status march COMPENSATING → FAILED, the
 * compensation events written into the durable event log, and the LIFO order in
 * which compensations actually ran.
 *
 * <h2>Observing COMPENSATING</h2>
 * <p>COMPENSATING is a transient status — it lasts only as long as the
 * compensations do, which against a local Postgres is a handful of
 * milliseconds. Polling for it would be a race, so the first compensation is
 * gated: the workflow is held inside {@code refund} while the test reads the
 * persisted status, then released.
 */
@Tag("integration")
@DisplayName("Saga compensation against a real Postgres store")
class EnginePostgresSagaIT extends PostgresIntegrationSupport {

    private static final Duration BOUND = Duration.ofSeconds(30);

    private @Nullable MaestroEngineHarness harness;
    private CountingActivities.Recorder recorder;

    @BeforeEach
    void newRecorder() {
        recorder = new CountingActivities.Recorder();
    }

    @AfterEach
    void closeHarness() {
        if (harness != null) {
            harness.close();
            harness = null;
        }
    }

    @Test
    @DisplayName("a saga whose third step fails compensates the first two in LIFO order and lands in FAILED")
    void sagaStepFailure_compensatesInLifoOrderAndFails() throws Exception {
        var compensating = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        harness = newHarness();
        harness.registerActivities(SagaActivities.class, new GatedSagaActivities(
                new CountingActivities.RecordingSagaActivities(recorder, "ship"),
                compensating, release));
        harness.registerWorkflow(new TestWorkflows.SagaWorkflow());

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("saga-fail");
        var handle = harness.start(workflowId, TestWorkflows.SagaWorkflow.class, "widget");

        // Held inside the first compensation: the COMPENSATING transition has
        // already been persisted and cannot have moved on.
        assertTrue(compensating.await(30, TimeUnit.SECONDS),
                "compensation never started — the saga did not unwind");
        assertEquals(WorkflowStatus.COMPENSATING, handle.status(),
                "the store must record COMPENSATING while compensations run");

        release.countDown();

        assertEquals(WorkflowStatus.FAILED, handle.awaitTerminal(BOUND),
                "a compensated saga ends FAILED, not COMPLETED");

        // Forward steps ran once each; the failing step burned its retry budget
        // (the fixture's @ActivityStub default is three attempts).
        assertEquals(1, recorder.count("reserve"));
        assertEquals(1, recorder.count("charge"));
        assertEquals(3, recorder.count("ship"));

        // LIFO: charge was registered last, so refund compensates first.
        assertEquals(1, recorder.count("refund"));
        assertEquals(1, recorder.count("releaseReservation"));
        assertEquals(List.of("refund", "releaseReservation"), compensationOrder(recorder));

        // The event log tells the same story durably.
        var events = handle.events();
        assertEquals(1, eventsOfType(events, EventType.COMPENSATION_STARTED).size());
        assertEquals(1, eventsOfType(events, EventType.COMPENSATION_COMPLETED).size());
        assertEquals(1, eventsOfType(events, EventType.WORKFLOW_FAILED).size());
        assertEquals(1, events.stream()
                        .filter(e -> e.eventType() == EventType.ACTIVITY_FAILED)
                        .filter(e -> "saga.ship".equals(e.stepName()))
                        .count(),
                "the failing step must be memoized as ACTIVITY_FAILED, got " + describe(events));

        // Compensations go through the activity proxy, so each is memoized —
        // and their sequence numbers preserve the LIFO order.
        assertTrue(sequenceOf(events, "saga.refund") < sequenceOf(events, "saga.releaseReservation"),
                "compensation events must be logged in LIFO order, got " + describe(events));

        // Version march. A compensating saga writes the instance row twice —
        // RUNNING→COMPENSATING, then COMPENSATING→FAILED — so the version lands
        // at 2. SagaManager.transitionToCompensating is the call site that
        // shipped BUG1 (it forgot to pre-increment) and it swallows
        // OptimisticLockException, so a regression there is silent except in
        // the version. Pinning the count keeps that failure mode observable.
        assertEquals(2, handle.instance().version(),
                "COMPENSATING and FAILED are two persisted transitions");

        var failure = handle.instance().output();
        assertTrue(failure != null && failure.get("exceptionType") != null,
                "a failed saga must persist its error detail");
    }

    @Test
    @DisplayName("a saga that never fails completes and runs no compensation at all")
    void sagaWithoutFailure_runsNoCompensation() {
        harness = newHarness();
        harness.registerActivities(SagaActivities.class,
                new CountingActivities.RecordingSagaActivities(recorder, null));
        harness.registerWorkflow(new TestWorkflows.SagaWorkflow());

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("saga-ok");
        var handle = harness.start(workflowId, TestWorkflows.SagaWorkflow.class, "widget");

        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));
        assertEquals("shipped:widget", handle.result(String.class));

        // One transition only — RUNNING→COMPLETED — so no COMPENSATING write
        // slipped in unnoticed.
        assertEquals(1, handle.instance().version(),
                "an uncompensated saga persists exactly one status transition");

        assertEquals(1, recorder.count("reserve"));
        assertEquals(1, recorder.count("charge"));
        assertEquals(1, recorder.count("ship"));
        assertEquals(0, recorder.count("refund"), "a successful saga must not compensate");
        assertEquals(0, recorder.count("releaseReservation"), "a successful saga must not compensate");

        var events = handle.events();
        assertEquals(0, eventsOfType(events, EventType.COMPENSATION_STARTED).size());
        assertEquals(0, eventsOfType(events, EventType.COMPENSATION_COMPLETED).size());
        assertEquals(0, eventsOfType(events, EventType.COMPENSATION_STEP_FAILED).size());
        assertEquals(1, eventsOfType(events, EventType.WORKFLOW_COMPLETED).size());
        assertEquals(3, eventsOfType(events, EventType.ACTIVITY_COMPLETED).size(),
                "three forward steps and no compensations, got " + describe(events));
    }

    // ── helpers ────────────────────────────────────────────────────────

    /** @return a single-node harness with a Postgres-backed instance lock */
    private MaestroEngineHarness newHarness() {
        return MaestroEngineHarness.builder(store, objectMapper)
                .serviceName("saga-node")
                .lock(newLock())
                .build();
    }

    /**
     * @param recorder the shared recorder
     * @return the compensation steps in the order they actually executed
     */
    private static List<String> compensationOrder(CountingActivities.Recorder recorder) {
        return recorder.invocations().stream()
                .filter(step -> step.equals("refund") || step.equals("releaseReservation"))
                .toList();
    }

    /**
     * @param events    an event log
     * @param eventType the type to keep
     * @return the events of that type, in sequence order
     */
    private static List<WorkflowEvent> eventsOfType(List<WorkflowEvent> events, EventType eventType) {
        return events.stream().filter(e -> e.eventType() == eventType).toList();
    }

    /**
     * @param events   an event log
     * @param stepName the step to locate
     * @return the sequence number of the completed event for that step
     * @throws IllegalStateException if the step never completed
     */
    private static int sequenceOf(List<WorkflowEvent> events, String stepName) {
        return events.stream()
                .filter(e -> e.eventType() == EventType.ACTIVITY_COMPLETED)
                .filter(e -> stepName.equals(e.stepName()))
                .mapToInt(WorkflowEvent::sequenceNumber)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No ACTIVITY_COMPLETED for '" + stepName + "' in " + describe(events)));
    }

    /**
     * @param events an event log
     * @return a compact {@code seq:TYPE(step)} rendering for failure messages
     */
    private static String describe(List<WorkflowEvent> events) {
        return events.stream()
                .map(e -> e.sequenceNumber() + ":" + e.eventType() + "(" + e.stepName() + ")")
                .toList()
                .toString();
    }

    // ── test-local fixtures ────────────────────────────────────────────

    /**
     * Wraps the shared saga fixture and holds the workflow inside the first
     * compensation so the transient COMPENSATING status can be read from the
     * store without racing it.
     *
     * <h2>Thread Safety</h2>
     * <p>Safe for concurrent use; the latches carry the happens-before edge.
     */
    private static final class GatedSagaActivities implements SagaActivities {

        private final SagaActivities delegate;
        private final CountDownLatch compensating;
        private final CountDownLatch release;

        GatedSagaActivities(SagaActivities delegate, CountDownLatch compensating,
                            CountDownLatch release) {
            this.delegate = delegate;
            this.compensating = compensating;
            this.release = release;
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
            compensating.countDown();
            awaitGate(release);
            delegate.refund(item);
        }

        @Override
        public String ship(String item) {
            return delegate.ship(item);
        }

        /**
         * Blocks until the test opens the gate, failing loudly rather than
         * hanging the suite forever.
         *
         * @param gate the latch to wait on
         */
        private static void awaitGate(CountDownLatch gate) {
            try {
                if (!gate.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Compensation gate was never opened");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for the compensation gate", e);
            }
        }
    }
}
