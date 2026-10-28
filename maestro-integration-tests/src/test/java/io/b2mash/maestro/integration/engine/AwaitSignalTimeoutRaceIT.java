package io.b2mash.maestro.integration.engine;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A signal persisted in the same instant an {@code awaitSignal} deadline fires
 * (plan 04 Review Focus 1, README Review Focus 2), on a real Postgres store.
 *
 * <p>The deadline is a durable timer row and the outcome slot is
 * {@code seq + 1}. Whichever of the signal and the fire the parked await
 * observes first, exactly one outcome — {@code SIGNAL_RECEIVED} or
 * {@code SIGNAL_TIMEOUT} — may be memoized there, never both, and the loser is
 * never discarded: a deadline that wins leaves the signal row unconsumed for a
 * later await; a signal that wins leaves a spent ({@code FIRED}) row that no
 * poller fires again.
 *
 * <p>The race is placed by {@link RacingStore}, which delivers the signal from
 * <em>inside</em> {@code markTimerFired} on the test thread — immediately
 * before or after the fire's compare-and-set — so the signal is durable in the
 * same instant as the CAS, in both interleavings.
 *
 * <h2>Thread Safety</h2>
 * <p>Per-test instance, confined to the JUnit test thread; {@link RacingStore}
 * documents its own guarantees.
 */
@Tag("integration")
@DisplayName("awaitSignal deadline fire racing a signal against a real Postgres store")
class AwaitSignalTimeoutRaceIT extends PostgresIntegrationSupport {

    private static final Duration BOUND = Duration.ofSeconds(15);

    /** Where the signal lands relative to the fire's compare-and-set. */
    enum Order { SIGNAL_THEN_FIRE, FIRE_THEN_SIGNAL }

    private @Nullable MaestroEngineHarness harness;

    @AfterEach
    void closeHarness() {
        if (harness != null) {
            harness.close();
            harness = null;
        }
    }

    @ParameterizedTest
    @EnumSource(Order.class)
    @DisplayName("signal persisted in the same instant the deadline fires: exactly one outcome is recorded, the loser is never discarded")
    void signalAndDeadlineInTheSameInstant_recordExactlyOneOutcome(Order order) throws Exception {
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("await-race-" + order);
        var racing = new RacingStore(store, order, workflowId);
        var harness = MaestroEngineHarness.builder(racing, objectMapper).build();
        this.harness = harness;
        racing.harness(harness);
        harness.registerWorkflow(new TestWorkflows.PatientSignalWorkflow(Duration.ofHours(1)));
        var handle = harness.start(workflowId, TestWorkflows.PatientSignalWorkflow.class, "seed");
        handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
        var row = store.findTimer(handle.instanceId(), "await-signal-1").orElseThrow();
        makeDue(row.id());
        await().atMost(BOUND).pollInterval(Duration.ofMillis(50)).until(() -> harness.fireDueTimers(10) == 1);
        assertTrue(racing.raced(), "the signal was never delivered inside the fire's CAS");

        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));
        var outcomes = handle.events().stream().filter(e -> e.sequenceNumber() == 2).map(WorkflowEvent::eventType).toList();
        assertEquals(1, outcomes.size());
        var signalRow = readSingleSignalRow(workflowId);
        switch (outcomes.getFirst()) {
            case SIGNAL_RECEIVED -> { assertEquals("approved", handle.result(String.class)); assertTrue(signalRow.consumed()); }
            case SIGNAL_TIMEOUT  -> { assertEquals("timed-out", handle.result(String.class)); assertFalse(signalRow.consumed(), "never discard a signal"); }
            default -> fail("unexpected outcome " + outcomes);
        }
        assertEquals(TimerStatus.FIRED, store.findTimer(handle.instanceId(), "await-signal-1").orElseThrow().status());
        assertEquals(1, handle.events().stream().filter(e -> e.eventType() == EventType.SIGNAL_RECEIVED || e.eventType() == EventType.SIGNAL_TIMEOUT).count());
        harness.close();
    }

    // ── helpers ────────────────────────────────────────────────────────

    /** Brings a timer row's {@code fire_at} forward to the database's {@code now()}. */
    private void makeDue(UUID timerDbId) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("UPDATE maestro_workflow_timer SET fire_at = now() WHERE id = ?")) {
            ps.setObject(1, timerDbId);
            assertEquals(1, ps.executeUpdate(), "the deadline row must exist to be made due");
        }
    }

    /**
     * Reads the one signal row for a workflow straight from Postgres — the SPI
     * only surfaces <em>unconsumed</em> signals, so "consumed" can only be
     * asserted against the table.
     *
     * @throws IllegalStateException if there is not exactly one row
     */
    private SignalRow readSingleSignalRow(String workflowId) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT consumed FROM maestro_workflow_signal WHERE workflow_id = ?")) {
            ps.setString(1, workflowId);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("No signal row for workflow " + workflowId);
                }
                var row = new SignalRow(rs.getBoolean("consumed"));
                if (rs.next()) {
                    throw new IllegalStateException(
                            "Expected exactly one signal row for workflow " + workflowId);
                }
                return row;
            }
        }
    }

    /** A row of {@code maestro_workflow_signal}, as far as this test cares. */
    private record SignalRow(boolean consumed) {}

    /**
     * A {@link WorkflowStore} decorator that places a signal delivery inside
     * the deadline fire: the first {@code markTimerFired} delivers the signal
     * through the harness (persist + local unpark) immediately before
     * ({@link Order#SIGNAL_THEN_FIRE}) or after ({@link Order#FIRE_THEN_SIGNAL})
     * delegating the compare-and-set. Both run on the calling (test) thread,
     * inside {@code fireDueTimers}.
     *
     * <p>One ordering is pinned beyond that, and only to keep the premise —
     * "the deadline fired" — deterministic: a {@code markTimerCancelled} (the
     * await's best-effort cancel of its now-moot row after a signal wins)
     * waits until the fire's CAS has run. Without it, in
     * {@code SIGNAL_THEN_FIRE} the woken await could cancel the row before the
     * test thread's CAS, and the race under test would never happen. Every
     * other call is straight delegation.
     *
     * <h2>Thread Safety</h2>
     * <p>Safe for concurrent use: the race is one-shot via a CAS flag, the
     * fire-done gate is a latch, and the harness reference is volatile and set
     * before any workflow starts.
     */
    private static final class RacingStore implements WorkflowStore {

        private final WorkflowStore delegate;
        private final Order order;
        private final String workflowId;
        private final AtomicBoolean raced = new AtomicBoolean(false);
        private final CountDownLatch fireCasDone = new CountDownLatch(1);
        private volatile @Nullable MaestroEngineHarness harness;

        RacingStore(WorkflowStore delegate, Order order, String workflowId) {
            this.delegate = delegate;
            this.order = order;
            this.workflowId = workflowId;
        }

        /** @param harness the node whose {@code deliverSignal} places the race */
        void harness(MaestroEngineHarness harness) {
            this.harness = harness;
        }

        /** @return whether the racing delivery ran */
        boolean raced() {
            return raced.get();
        }

        @Override
        public boolean markTimerFired(UUID timerId) {
            if (!raced.compareAndSet(false, true)) {
                return delegate.markTimerFired(timerId);
            }
            try {
                if (order == Order.SIGNAL_THEN_FIRE) {
                    deliver();
                    return delegate.markTimerFired(timerId);
                }
                var fired = delegate.markTimerFired(timerId);
                deliver();
                return fired;
            } finally {
                fireCasDone.countDown();
            }
        }

        @Override
        public boolean markTimerCancelled(UUID timerId) {
            try {
                if (!fireCasDone.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the deadline fire never ran");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted waiting for the deadline fire", e);
            }
            return delegate.markTimerCancelled(timerId);
        }

        private void deliver() {
            var node = harness;
            if (node == null) {
                throw new IllegalStateException("RacingStore.harness(...) was never set");
            }
            node.deliverSignal(workflowId, TestWorkflows.PatientSignalWorkflow.SIGNAL, "approved");        }

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
        public boolean completeInstance(WorkflowInstance terminal, WorkflowEvent terminalEvent) {
            return delegate.completeInstance(terminal, terminalEvent);
        }

        @Override
        public void appendEvent(WorkflowEvent event) {
            delegate.appendEvent(event);
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
        public int deleteFailureEvents(UUID instanceId) {
            return delegate.deleteFailureEvents(instanceId);
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
        public boolean markSignalConsumed(UUID signalId) {
            return delegate.markSignalConsumed(signalId);
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
    }
}
