package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.RunCancellation;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.DuplicateEventException;
import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.exception.StoreUnavailableException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.RunLease;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link StoreFaultGuard}: the store-boundary proxy that retries the two
 * idempotent operations ({@code appendEvent}, {@code getEventBySequence}) on a
 * {@link StoreUnavailableException} and, once a fault is final, latches the
 * run's {@link RunCancellation} as {@link RunCancellation.Cause#STORE_UNAVAILABLE}
 * — except for advisory reads and calls made off a workflow thread.
 */
@DisplayName("StoreFaultGuard retries idempotent store calls and latches the run on a final store fault")
class StoreFaultGuardTest {

    /** Zero waits keep the tests fast; the production waits are pinned separately. */
    private static final List<Duration> NO_WAIT = List.of(Duration.ZERO, Duration.ZERO);

    private FaultyStore delegate;
    private WorkflowStore guarded;
    private WorkflowContext ctx;
    private UUID instanceId;

    @BeforeEach
    void setUp() {
        delegate = new FaultyStore();
        guarded = StoreFaultGuard.wrap(delegate, NO_WAIT);
        instanceId = UUID.randomUUID();
        ctx = new WorkflowContext(instanceId, "wf-guard", UUID.randomUUID(), "T", "default",
                "svc", 0, false);
    }

    private WorkflowEvent event(int seq) {
        return new WorkflowEvent(UUID.randomUUID(), instanceId, seq, EventType.ACTIVITY_COMPLETED,
                "step", null, Instant.now());
    }

    private WorkflowInstance instance() {
        return WorkflowInstance.builder()
                .id(instanceId)
                .workflowId("wf-guard")
                .runId(UUID.randomUUID())
                .workflowType("T")
                .taskQueue("default")
                .status(WorkflowStatus.RUNNING)
                .serviceName("svc")
                .eventSequence(0)
                .startedAt(Instant.now())
                .updatedAt(Instant.now())
                .version(1)
                .build();
    }

    /** Runs {@code body} on this thread with {@link #ctx} bound, as a workflow thread would. */
    private void inRun(Executable body) {
        ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() -> {
            try {
                body.execute();
            } catch (Throwable t) {
                throw StoreFaultGuardTest.<RuntimeException>sneaky(t);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneaky(Throwable t) throws T {
        throw (T) t;
    }

    @Test
    @DisplayName("appendEvent failing twice succeeds on the third attempt and does not latch")
    void appendEventIsRetriedAndSucceedsOnThirdAttempt() {
        delegate.failNext("appendEvent", 2);

        inRun(() -> guarded.appendEvent(event(1)));

        assertEquals(3, delegate.calls("appendEvent"));
        assertEquals(1, delegate.getEvents(instanceId).size(), "the event is stored exactly once");
        assertNull(ctx.cancellation().cause());
    }

    @Test
    @DisplayName("appendEvent failing three times throws and latches STORE_UNAVAILABLE")
    void appendEventExhaustsThenLatches() {
        delegate.failNext("appendEvent", 3);

        assertThrows(StoreUnavailableException.class, () -> inRun(() -> guarded.appendEvent(event(1))));

        assertEquals(3, delegate.calls("appendEvent"));
        assertEquals(RunCancellation.Cause.STORE_UNAVAILABLE, ctx.cancellation().cause());
        assertTrue(ctx.cancellation().reason().startsWith("appendEvent: "),
                "the detail names the method: " + ctx.cancellation().reason());
    }

    @Test
    @DisplayName("the latch detail carries the SQLSTATE and driver message of the underlying SQLException")
    void latchDetailCarriesSqlStateAndDriverMessage() {
        // As the JDBC store raises a schema error: a StoreUnavailableException
        // whose message names only the statement, the SQLException as cause.
        delegate.failNext("appendEvent", 3, () -> new StoreUnavailableException(
                "Failed to append event: INSERT INTO maestro_workflow_event ...",
                new SQLException("ERROR: column \"args_hash\" does not exist", "42703")));

        assertThrows(StoreUnavailableException.class, () -> inRun(() -> guarded.appendEvent(event(1))));

        var reason = ctx.cancellation().reason();
        assertAll(
                () -> assertTrue(reason.startsWith("appendEvent: "), reason),
                () -> assertTrue(reason.contains("SQLSTATE=42703"), "the SQLSTATE must be in the detail: " + reason),
                () -> assertTrue(reason.contains("column \"args_hash\" does not exist"),
                        "the driver message must be in the detail: " + reason));
    }

    @Test
    @DisplayName("a SQLException with no SQLSTATE, deeper in the chain, is reported as SQLSTATE=none")
    void latchDetailReportsMissingSqlStateAsNone() {
        // A pool acquisition timeout carries no SQLSTATE; a framework layer
        // may sit between it and the store's exception.
        delegate.failNext("updateInstance", 1, () -> new StoreUnavailableException(
                "Update failed: UPDATE maestro_workflow_instance ...",
                new IllegalStateException("wrapped", new SQLTransientConnectionException(
                        "HikariPool-1 - Connection is not available, request timed out after 30000ms."))));

        assertThrows(StoreUnavailableException.class, () -> inRun(() -> guarded.updateInstance(instance())));

        var reason = ctx.cancellation().reason();
        assertAll(
                () -> assertTrue(reason.contains("SQLSTATE=none"), reason),
                () -> assertTrue(reason.contains("Connection is not available, request timed out after 30000ms."),
                        reason));
    }

    @Test
    @DisplayName("with no SQLException in the chain, the latch detail names the root cause")
    void latchDetailNamesRootCauseWithoutSqlException() {
        delegate.failNext("appendEvent", 3);

        assertThrows(StoreUnavailableException.class, () -> inRun(() -> guarded.appendEvent(event(1))));

        var reason = ctx.cancellation().reason();
        assertTrue(reason.contains("java.lang.RuntimeException: connection refused (injected)"),
                "the root cause's class and message must be in the detail: " + reason);
    }

    @Test
    @DisplayName("a commit lost in flight surfaces on retry as a duplicate, not a latch")
    void ambiguousCommitSurfacesAsDuplicate() {
        delegate.commitThenFailNext("appendEvent", 1);

        assertThrows(DuplicateEventException.class, () -> inRun(() -> guarded.appendEvent(event(1))));

        assertEquals(2, delegate.calls("appendEvent"));
        assertEquals(1, delegate.getEvents(instanceId).size());
        assertNull(ctx.cancellation().cause());
    }

    @Test
    @DisplayName("updateInstance is not retried, and a store fault on it latches")
    void updateInstanceIsNotRetried() {
        delegate.failNext("updateInstance", 1);

        assertThrows(StoreUnavailableException.class, () -> inRun(() -> guarded.updateInstance(instance())));

        assertEquals(1, delegate.calls("updateInstance"));
        assertEquals(RunCancellation.Cause.STORE_UNAVAILABLE, ctx.cancellation().cause());
        assertTrue(ctx.cancellation().reason().startsWith("updateInstance: "));
    }

    @Test
    @DisplayName("an advisory read is neither retried nor latched")
    void advisoryReadNeitherRetriesNorLatches() {
        delegate.failNext("getEventBySequence", 3);

        assertThrows(StoreUnavailableException.class, () -> inRun(() ->
                StoreFaultGuard.advisory(() -> guarded.getEventBySequence(instanceId, 1))));

        assertEquals(1, delegate.calls("getEventBySequence"));
        assertNull(ctx.cancellation().cause());
    }

    @Test
    @DisplayName("a park probe through the guarded store falls back at once — no retry, no latch")
    void parkProbeReadThroughGuardNeitherRetriesNorLatches() {
        delegate.failNext("getEventBySequence", 3);
        var fallback = Optional.of(event(99));
        var result = new AtomicReference<Optional<WorkflowEvent>>();

        inRun(() -> result.set(ParkProbe.read("test.probe", "wf-guard",
                () -> guarded.getEventBySequence(instanceId, 1), fallback)));

        assertSame(fallback, result.get(), "the probe must return its fallback");
        assertEquals(1, delegate.calls("getEventBySequence"),
                "an advisory read is attempted once, never retried");
        assertNull(ctx.cancellation().cause(), "a failed probe must not latch the run (R4)");
    }

    @Test
    @DisplayName("a run latched STORE_UNAVAILABLE cannot append, even once the store answers")
    void latchedRunCannotAppend() {
        ctx.cancellation().requestStoreUnavailable("getEventBySequence: earlier fault");

        assertThrows(StoreUnavailableException.class, () -> inRun(() -> guarded.appendEvent(event(1))));

        assertEquals(0, delegate.calls("appendEvent"), "the append must be refused before the store");
        assertEquals(0, delegate.getEvents(instanceId).size());
        assertEquals("getEventBySequence: earlier fault", ctx.cancellation().reason(),
                "a refused append does not re-latch");
    }

    @Test
    @DisplayName("a run latched STORE_UNAVAILABLE can still read")
    void latchedRunCanStillRead() {
        ctx.cancellation().requestStoreUnavailable("getEventBySequence: earlier fault");

        inRun(() -> guarded.getEventBySequence(instanceId, 1));

        assertEquals(1, delegate.calls("getEventBySequence"));
    }

    @Test
    @DisplayName("off a workflow thread the fault is rethrown with no latch and no NPE")
    void noBoundContextMeansNoLatchAndNoNpe() {
        delegate.failNext("getInstance", 1);
        delegate.failNext("appendEvent", 3);

        assertThrows(StoreUnavailableException.class, () -> guarded.getInstance("wf-guard"));
        assertThrows(StoreUnavailableException.class, () -> guarded.appendEvent(event(1)));

        assertEquals(1, delegate.calls("getInstance"));
        assertEquals(3, delegate.calls("appendEvent"), "retries do not depend on a bound context");
        assertNull(ctx.cancellation().cause(), "an unbound call must not reach any run's latch");
    }

    @Test
    @DisplayName("a control-flow Error from the delegate passes through unwrapped and unretried")
    void controlFlowErrorPassesThroughUnwrapped() {
        delegate.failNext("appendEvent", 1, () -> new ExecutorShutdownException("stopping"));

        assertThrows(ExecutorShutdownException.class, () -> inRun(() -> guarded.appendEvent(event(1))));

        assertEquals(1, delegate.calls("appendEvent"));
        assertNull(ctx.cancellation().cause());
    }

    @Test
    @DisplayName("wrapping an already wrapped store returns it unchanged")
    void wrapIsIdempotent() {
        var g = StoreFaultGuard.wrap(delegate);
        assertSame(g, StoreFaultGuard.wrap(g));
    }

    @Test
    @DisplayName("an interrupt during the retry wait restores the flag and ends the retries")
    void interruptDuringRetryWaitEndsRetries() {
        var slow = StoreFaultGuard.wrap(delegate, List.of(Duration.ofSeconds(30), Duration.ofSeconds(30)));
        delegate.failNext("appendEvent", 3);

        Thread.currentThread().interrupt();
        try {
            assertThrows(StoreUnavailableException.class, () -> inRun(() -> slow.appendEvent(event(1))));
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag must be restored");
        } finally {
            Thread.interrupted();
        }

        assertEquals(1, delegate.calls("appendEvent"));
        assertEquals(RunCancellation.Cause.STORE_UNAVAILABLE, ctx.cancellation().cause());
    }

    @Test
    @DisplayName("the production retry waits are 200 ms then 1 s")
    void productionBackoffIs200msThen1s() {
        assertEquals(List.of(Duration.ofMillis(200), Duration.ofSeconds(1)), StoreFaultGuard.RETRY_BACKOFF);
    }

    // ── appendEventReturningPayload (WP1 Task 7) ─────────────────────────

    /** The instance row the fenced append checks, at lease epoch 0. */
    private RunLease leaseOnStoredInstance() {
        delegate.createInstance(instance().toBuilder().version(0).build());
        return new RunLease(instanceId, 0);
    }

    private WorkflowEvent eventWithPayload(int seq, JsonNode payload) {
        return new WorkflowEvent(UUID.randomUUID(), instanceId, seq, EventType.ACTIVITY_COMPLETED,
                "step", payload, Instant.now());
    }

    @Test
    @DisplayName("appendEventReturningPayload failing twice is retried like appendEvent and returns the payload")
    void appendEventReturningPayloadIsRetriedLikeAppendEvent() {
        var lease = leaseOnStoredInstance();
        var payload = JsonNodeFactory.instance.objectNode().put("a", 1);
        delegate.failNext("appendEvent", 2);
        var returned = new AtomicReference<JsonNode>();

        inRun(() -> returned.set(guarded.appendEventReturningPayload(eventWithPayload(1, payload), lease)));

        assertAll(
                () -> assertEquals(payload, returned.get()),
                () -> assertEquals(3, delegate.calls("appendEvent")),
                () -> assertEquals(1, delegate.getEvents(instanceId).size(), "the event is stored exactly once"),
                () -> assertNull(ctx.cancellation().cause()));
    }

    @Test
    @DisplayName("a run latched STORE_UNAVAILABLE cannot appendEventReturningPayload")
    void appendEventReturningPayloadIsRefusedOnceLatched() {
        var lease = leaseOnStoredInstance();
        ctx.cancellation().requestStoreUnavailable("getEventBySequence: earlier fault");

        assertThrows(StoreUnavailableException.class,
                () -> inRun(() -> guarded.appendEventReturningPayload(event(1), lease)));

        assertEquals(0, delegate.calls("appendEvent"), "the append must be refused before the store");
        assertEquals(0, delegate.getEvents(instanceId).size());
        assertEquals("getEventBySequence: earlier fault", ctx.cancellation().reason(),
                "a refused append does not re-latch");
    }

    @Test
    @DisplayName("appendEventReturningPayload: a commit lost in flight surfaces on retry as a duplicate and returns nothing")
    void appendEventReturningPayloadAmbiguousCommitSurfacesAsDuplicate() {
        var lease = leaseOnStoredInstance();
        delegate.commitThenFailNext("appendEvent", 1);

        assertThrows(DuplicateEventException.class,
                () -> inRun(() -> guarded.appendEventReturningPayload(event(1), lease)));

        assertEquals(2, delegate.calls("appendEvent"));
        assertEquals(1, delegate.getEvents(instanceId).size());
        assertNull(ctx.cancellation().cause());
    }
}
