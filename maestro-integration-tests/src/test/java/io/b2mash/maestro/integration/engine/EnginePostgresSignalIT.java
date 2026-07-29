package io.b2mash.maestro.integration.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.SignalTimeoutException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.CountingActivities.ChainActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The full signal matrix executed by the real engine against a real Postgres
 * store: park/wake, pre-arrived signals, orphan adoption, await timeout, a lost
 * consume CAS, and {@code collectSignals} FIFO ordering.
 *
 * <p>Every assertion reads persisted state — instance rows, the event log and
 * the signal table — because the point of this suite is that the engine's
 * signal protocol survives a round trip through Postgres, not that its
 * in-memory bookkeeping is self-consistent.
 *
 * <h2>Why signals are never delivered by waiting</h2>
 * <p>{@code SignalManager}'s parked-await re-check interval is a hard-coded 30s
 * that {@code WorkflowExecutor} does not expose. Every test here therefore
 * delivers through {@link MaestroEngineHarness#deliverSignal}, which unparks the
 * waiter directly; no test depends on the re-check path.
 */
@Tag("integration")
@DisplayName("Engine signal handling against a real Postgres store")
class EnginePostgresSignalIT extends PostgresIntegrationSupport {

    private static final Duration BOUND = Duration.ofSeconds(15);

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
    @DisplayName("a workflow parked on awaitSignal wakes and completes when a signal is delivered")
    void parkedWorkflow_wakesAndCompletesOnSignalDelivery() throws Exception {
        var parked = new CountDownLatch(1);
        harness = newHarness(store);
        harness.registerActivities(ChainActivities.class,
                new CountingActivities.RecordingChainActivities(recorder));
        harness.registerWorkflow(new TestWorkflows.SignalWorkflow(parked));

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("signal-wake");
        var handle = harness.start(workflowId, TestWorkflows.SignalWorkflow.class, "seed");

        assertTrue(parked.await(15, TimeUnit.SECONDS), "workflow never reached its await point");
        // WAITING_SIGNAL is only persisted on the park path, so reaching it
        // proves the wake below travelled through park/unpark rather than
        // through the already-arrived shortcut.
        handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);

        harness.deliverSignal(workflowId, TestWorkflows.SignalWorkflow.SIGNAL, "approved");

        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));
        assertEquals("seed-one:approved-two", handle.result(String.class));
        assertEquals(1, recorder.count("stepOne"));
        assertEquals(1, recorder.count("stepTwo"));

        var received = eventsOfType(handle.events(), EventType.SIGNAL_RECEIVED);
        assertEquals(1, received.size(), "expected exactly one SIGNAL_RECEIVED event");
        assertEquals("approved", serializer.deserialize(received.getFirst().payload(), String.class));

        var row = readSingleSignalRow(workflowId);
        assertTrue(row.consumed(), "the delivered signal row must be marked consumed");
        assertEquals(handle.instanceId(), row.workflowInstanceId());
    }

    @Test
    @DisplayName("a signal that arrives before the await point is consumed without parking")
    void preArrivedSignal_isConsumedWhenTheAwaitPointIsReached() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        harness = newHarness(store);
        harness.registerActivities(ChainActivities.class, new GatedChainActivities(
                new CountingActivities.RecordingChainActivities(recorder), entered, release));
        harness.registerWorkflow(new TestWorkflows.SignalWorkflow(new CountDownLatch(1)));

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("signal-prearrived");
        var handle = harness.start(workflowId, TestWorkflows.SignalWorkflow.class, "seed");

        // Hold the workflow inside its first activity: the instance row exists,
        // but the await point is provably not yet reached.
        assertTrue(entered.await(15, TimeUnit.SECONDS), "workflow never entered stepOne");
        harness.deliverSignal(workflowId, TestWorkflows.SignalWorkflow.SIGNAL, "early");

        var pending = store.getUnconsumedSignals(workflowId, TestWorkflows.SignalWorkflow.SIGNAL);
        assertEquals(1, pending.size(), "signal must be persisted before the workflow awaits it");
        assertEquals(handle.instanceId(), pending.getFirst().workflowInstanceId(),
                "an instance already exists, so the signal is not an orphan");

        release.countDown();

        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));
        assertEquals("seed-one:early-two", handle.result(String.class));
        assertEquals(1, eventsOfType(handle.events(), EventType.SIGNAL_RECEIVED).size());
        assertTrue(readSingleSignalRow(workflowId).consumed());
    }

    @Test
    @DisplayName("a signal delivered before the workflow exists is adopted when the instance is created")
    void orphanSignal_isAdoptedOnWorkflowStart() throws Exception {
        harness = newHarness(store);
        harness.registerActivities(ChainActivities.class,
                new CountingActivities.RecordingChainActivities(recorder));
        harness.registerWorkflow(new TestWorkflows.SignalWorkflow(new CountDownLatch(1)));

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("signal-orphan");
        harness.deliverSignal(workflowId, TestWorkflows.SignalWorkflow.SIGNAL, "adopted");

        // Pre-delivery: the row is persisted with no instance to point at.
        var orphan = readSingleSignalRow(workflowId);
        assertNull(orphan.workflowInstanceId(),
                "a signal for a non-existent workflow must persist with a null instance ID");
        assertFalse(orphan.consumed());
        assertTrue(store.getInstance(workflowId).isEmpty(), "no instance should exist yet");

        var handle = harness.start(workflowId, TestWorkflows.SignalWorkflow.class, "seed");

        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));
        assertEquals("seed-one:adopted-two", handle.result(String.class));

        // Adoption is the claim under test: the same row now points at the
        // instance, which consumption alone would not prove.
        var adopted = readSingleSignalRow(workflowId);
        assertEquals(handle.instanceId(), adopted.workflowInstanceId(),
                "adoptOrphanedSignals must link the pre-delivered signal to the new instance");
        assertTrue(adopted.consumed());
    }

    @Test
    @DisplayName("an await that times out fails the workflow with SignalTimeoutException")
    void awaitSignalTimeout_failsTheWorkflow() {
        harness = newHarness(store);
        harness.registerWorkflow(new ImpatientSignalWorkflow());

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("signal-timeout");
        var handle = harness.start(workflowId, ImpatientSignalWorkflow.class, "seed");

        assertEquals(WorkflowStatus.FAILED, handle.awaitTerminal(BOUND));

        var output = handle.instance().output();
        assertNotNull(output, "a failed workflow must persist its error detail");
        assertEquals(SignalTimeoutException.class.getName(), output.get("exceptionType").stringValue());

        // Nothing was consumed and nothing was memoized — the await produced no
        // SIGNAL_RECEIVED event, so a replay would time out identically.
        assertEquals(0, eventsOfType(handle.events(), EventType.SIGNAL_RECEIVED).size());
    }

    @Test
    @DisplayName("losing the consume CAS to a concurrent consumer still completes the workflow from the memoized event")
    void lostConsumeCas_completesFromTheMemoizedSignalEvent() throws Exception {
        var parked = new CountDownLatch(1);
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("signal-cas");
        var racingStore = new ConsumeStealingStore(
                store, workflowId, TestWorkflows.SignalWorkflow.SIGNAL);

        harness = newHarness(racingStore);
        harness.registerActivities(ChainActivities.class,
                new CountingActivities.RecordingChainActivities(recorder));
        harness.registerWorkflow(new TestWorkflows.SignalWorkflow(parked));

        var handle = harness.start(workflowId, TestWorkflows.SignalWorkflow.class, "seed");
        assertTrue(parked.await(15, TimeUnit.SECONDS), "workflow never reached its await point");
        handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);

        harness.deliverSignal(workflowId, TestWorkflows.SignalWorkflow.SIGNAL, "approved");

        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));
        assertEquals("seed-one:approved-two", handle.result(String.class));

        // The steal ran, and the engine's own CAS therefore lost.
        assertTrue(racingStore.stole(), "the injected competing consumer never ran");
        assertEquals(List.of(false), racingStore.engineCasResults(),
                "the engine's markSignalConsumed must observe the lost CAS");

        // The memoized event is the durable truth, and it was written exactly once.
        assertEquals(1, eventsOfType(handle.events(), EventType.SIGNAL_RECEIVED).size());
        assertTrue(readSingleSignalRow(workflowId).consumed(),
                "the row stays consumed — it can never satisfy a second consumer");
    }

    @Test
    @DisplayName("collectSignals takes the first N signals in received-at order and leaves the rest unconsumed")
    void collectSignals_takesTheFirstNInFifoOrder() throws Exception {
        var parked = new CountDownLatch(1);
        harness = newHarness(store);
        harness.registerWorkflow(new TestWorkflows.CollectingWorkflow(3, parked));

        var workflowId = MaestroEngineHarness.uniqueWorkflowId("signal-collect");
        var handle = harness.start(workflowId, TestWorkflows.CollectingWorkflow.class, "seed");

        assertTrue(parked.await(15, TimeUnit.SECONDS), "workflow never reached its await point");
        handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);

        // Five votes for a three-vote collector. Each delivery is a full round
        // trip to Postgres, so received_at is strictly increasing.
        for (var vote : List.of("v1", "v2", "v3", "v4", "v5")) {
            harness.deliverSignal(workflowId, TestWorkflows.CollectingWorkflow.SIGNAL, vote);
        }

        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));
        assertEquals("v1,v2,v3", handle.result(String.class),
                "collectSignals must consume the three earliest signals, in order");

        var leftover = store.getUnconsumedSignals(workflowId, TestWorkflows.CollectingWorkflow.SIGNAL);
        assertEquals(2, leftover.size(), "surplus signals must survive unconsumed, never be discarded");
        assertEquals(List.of("v4", "v5"), leftover.stream()
                .map(s -> serializer.deserialize(s.payload(), String.class))
                .toList());

        assertEquals(3, eventsOfType(handle.events(), EventType.SIGNAL_RECEIVED).size());
    }

    // ── helpers ────────────────────────────────────────────────────────

    /**
     * Builds a single-node harness over the given store.
     *
     * @param backingStore the store the engine should write through — the real
     *                     Postgres store, or a decorator that injects a race
     * @return a harness with a Postgres-backed instance lock
     */
    private MaestroEngineHarness newHarness(WorkflowStore backingStore) {
        return MaestroEngineHarness.builder(backingStore, objectMapper)
                .serviceName("signal-node")
                .lock(newLock())
                .build();
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
     * Reads the one signal row for a workflow straight from Postgres.
     *
     * <p>The store SPI only exposes <em>unconsumed</em> signals, so adoption and
     * consumption of a row that has already been consumed can only be asserted
     * against the table itself.
     *
     * @param workflowId the business workflow ID
     * @return the row's instance ID (nullable) and consumed flag
     * @throws SQLException            if the query fails
     * @throws IllegalStateException   if there is not exactly one row
     */
    private SignalRow readSingleSignalRow(String workflowId) throws SQLException {
        var sql = "SELECT workflow_instance_id, consumed FROM maestro_workflow_signal"
                + " WHERE workflow_id = ?";
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, workflowId);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("No signal row for workflow " + workflowId);
                }
                var row = new SignalRow((UUID) rs.getObject("workflow_instance_id"),
                        rs.getBoolean("consumed"));
                if (rs.next()) {
                    throw new IllegalStateException(
                            "Expected exactly one signal row for workflow " + workflowId);
                }
                return row;
            }
        }
    }

    /** A row of {@code maestro_workflow_signal}, as far as these tests care. */
    private record SignalRow(@Nullable UUID workflowInstanceId, boolean consumed) {}

    // ── test-local fixtures ────────────────────────────────────────────

    /**
     * Awaits a signal with a sub-second timeout.
     *
     * <p>The shared {@code SignalWorkflow} fixture waits 30s, which is also the
     * signal-manager re-check interval — waiting it out would make this suite
     * slow and would exercise the re-check path the contract forbids depending
     * on. This local fixture exists solely to make the timeout observable.
     */
    @DurableWorkflow(name = "ImpatientSignalWorkflow")
    public static class ImpatientSignalWorkflow {

        /** Signal name this workflow awaits but never receives. */
        public static final String SIGNAL = "never-arrives";

        /**
         * @param input unused seed
         * @return never — the await always times out
         */
        @WorkflowMethod
        public String run(String input) {
            return WorkflowContext.current()
                    .awaitSignal(SIGNAL, String.class, Duration.ofMillis(500));
        }
    }

    /**
     * Holds the workflow inside {@code stepOne} until the test releases it, so
     * a signal can provably be delivered before the await point is reached.
     *
     * <h2>Thread Safety</h2>
     * <p>Safe for concurrent use; the latches carry the happens-before edge.
     */
    private static final class GatedChainActivities implements ChainActivities {
        private final ChainActivities delegate;
        private final CountDownLatch entered;
        private final CountDownLatch release;

        GatedChainActivities(ChainActivities delegate, CountDownLatch entered, CountDownLatch release) {
            this.delegate = delegate;
            this.entered = entered;
            this.release = release;
        }

        @Override
        public String stepOne(String input) {
            entered.countDown();
            awaitGate(release);
            return delegate.stepOne(input);
        }

        @Override
        public String stepTwo(String input) {
            return delegate.stepTwo(input);
        }

        @Override
        public String stepThree(String input) {
            return delegate.stepThree(input);
        }
    }

    /**
     * Blocks until a gate opens, failing loudly rather than hanging forever.
     *
     * @param gate the latch to wait on
     */
    private static void awaitGate(CountDownLatch gate) {
        try {
            if (!gate.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Gate was never opened");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for gate", e);
        }
    }

    /**
     * A {@link WorkflowStore} decorator that simulates another node consuming
     * the signal in the window between the engine's read and its own CAS.
     *
     * <p>The engine appends {@code SIGNAL_RECEIVED} immediately before calling
     * {@code markSignalConsumed}; stealing the row during that append lands the
     * competing consumer exactly inside the race the CAS exists to arbitrate.
     *
     * <h2>Thread Safety</h2>
     * <p>Safe for concurrent use — the steal is one-shot via a CAS flag and the
     * observed results list is copy-on-write.
     */
    private static final class ConsumeStealingStore implements WorkflowStore {

        private final WorkflowStore delegate;
        private final String workflowId;
        private final String signalName;
        private final AtomicBoolean stealAttempted = new AtomicBoolean(false);
        private final List<Boolean> engineCasResults = new CopyOnWriteArrayList<>();
        private volatile int stolenRows;

        ConsumeStealingStore(WorkflowStore delegate, String workflowId, String signalName) {
            this.delegate = delegate;
            this.workflowId = workflowId;
            this.signalName = signalName;
        }

        /** @return whether the competing consumer actually won a row */
        boolean stole() {
            return stolenRows > 0;
        }

        /** @return the result of every {@code markSignalConsumed} the engine issued */
        List<Boolean> engineCasResults() {
            return List.copyOf(engineCasResults);
        }

        @Override
        public void appendEvent(WorkflowEvent event) {
            if (event.eventType() == EventType.SIGNAL_RECEIVED
                    && stealAttempted.compareAndSet(false, true)) {
                var won = 0;
                for (var signal : delegate.getUnconsumedSignals(workflowId, signalName)) {
                    if (delegate.markSignalConsumed(signal.id())) {
                        won++;
                    }
                }
                stolenRows = won;
            }
            delegate.appendEvent(event);
        }

        @Override
        public boolean markSignalConsumed(UUID signalId) {
            var applied = delegate.markSignalConsumed(signalId);
            engineCasResults.add(applied);
            return applied;
        }

        // ── straight delegation ────────────────────────────────────────

        @Override
        public WorkflowInstance createInstance(WorkflowInstance instance) {
            return delegate.createInstance(instance);
        }

        @Override
        public Optional<WorkflowInstance> getInstance(String workflowId) {
            return delegate.getInstance(workflowId);
        }

        @Override
        public List<WorkflowInstance> getRecoverableInstances() {
            return delegate.getRecoverableInstances();
        }

        @Override
        public void updateInstance(WorkflowInstance instance) {
            delegate.updateInstance(instance);
        }

        @Override
        public Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int sequenceNumber) {
            return delegate.getEventBySequence(instanceId, sequenceNumber);
        }

        @Override
        public List<WorkflowEvent> getEvents(UUID instanceId) {
            return delegate.getEvents(instanceId);
        }

        @Override
        public void saveSignal(WorkflowSignal signal) {
            delegate.saveSignal(signal);
        }

        @Override
        public List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName) {
            return delegate.getUnconsumedSignals(workflowId, signalName);
        }

        @Override
        public void adoptOrphanedSignals(String workflowId, UUID instanceId) {
            delegate.adoptOrphanedSignals(workflowId, instanceId);
        }

        @Override
        public void saveTimer(WorkflowTimer timer) {
            delegate.saveTimer(timer);
        }

        @Override
        public List<WorkflowTimer> getDueTimers(Instant now, int batchSize) {
            return delegate.getDueTimers(now, batchSize);
        }

        @Override
        public Optional<WorkflowTimer> findTimer(UUID workflowInstanceId, String timerId) {
            return delegate.findTimer(workflowInstanceId, timerId);
        }

        @Override
        public boolean markTimerFired(UUID timerId) {
            return delegate.markTimerFired(timerId);
        }

        @Override
        public void markTimerCancelled(UUID timerId) {
            delegate.markTimerCancelled(timerId);
        }
    }
}
