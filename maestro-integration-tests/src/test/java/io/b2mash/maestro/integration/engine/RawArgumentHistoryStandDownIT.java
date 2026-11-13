package io.b2mash.maestro.integration.engine;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.engine.ArgumentFingerprint;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.StandDownReason;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.support.RecordingObserver;
import io.b2mash.maestro.integration.support.WorkflowHandle;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan D-4(b), pinned: history an older build wrote with an argument derived
 * from a <em>raw</em> live result that does not round-trip — {@code cbf5a2a}
 * passed {@code reserveSlot()}'s {@code +02:00} slot straight into
 * {@code confirm(slot)} and fingerprinted that — is not healed and not made
 * worse. A replay on this build hashes the read-back slot ({@code 08:00Z}),
 * so it stands down as {@code nondeterministic_history}, exactly as the old
 * build did after any restart: nothing fails, nothing compensates, nothing
 * re-executes. The escape hatch is {@code maestro.replay.verify-arguments=false}:
 * the recorded step then replays and the run continues with the read-back
 * value, pinned here end to end too.
 *
 * <p>This passes before and after Task 7: what Task 7 changes is which node
 * the live read-back reads, and an {@code OffsetDateTime} reads back the same
 * from either.
 */
@Tag("integration")
@DisplayName("History written with a raw (non-round-tripping) argument stands down, never fails")
class RawArgumentHistoryStandDownIT extends PostgresIntegrationSupport {

    private static final String SERVICE = "raw-argument-history";
    private static final Duration BOUND = Duration.ofSeconds(30);
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);

    @Test
    @DisplayName("a confirm recorded with the raw +02:00 slot's fingerprint stands down as nondeterministic_history")
    void rawArgumentHistory_standsDownWithTheArgsHashDetail() throws Exception {
        var confirms = new AtomicInteger();
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("raw-args");

        // ── Node A: reserveSlot() and confirm(slot) run live; the run parks.
        var parkedA = new CountDownLatch(1);
        WorkflowHandle handle;
        try (var nodeA = node(confirms, parkedA, null)) {
            handle = nodeA.start(workflowId, SlotWorkflow.class, "order-1");
            assertTrue(parkedA.await(30, TimeUnit.SECONDS), "node A must reach the await point");
            handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
        }
        var instanceId = handle.instanceId();
        assertEquals(1, confirms.get());

        // What cbf5a2a wrote on confirm's event: the fingerprint of the raw slot.
        var raw = BookingImpl.SLOT;
        var readBack = serializer.deserialize(store.getEventBySequence(instanceId, 1).orElseThrow().payload(),
                DeliverySlot.class);
        var fpRaw = ArgumentFingerprint.of(serializer, new Object[]{raw});
        var fpReadBack = ArgumentFingerprint.of(serializer, new Object[]{readBack});
        assertNotNull(fpRaw);
        assertNotNull(fpReadBack);
        assertNotEquals(fpRaw, fpReadBack, "the +02:00 slot does not round-trip: " + raw + " vs " + readBack);
        assertEquals(fpReadBack, store.getEventBySequence(instanceId, 2).orElseThrow().argsHash(),
                "this build records the read-back slot's fingerprint");
        setArgsHash(instanceId, 2, fpRaw);
        var beforeStatus = handle.status();

        // ── Node B: replays reserveSlot, re-fingerprints confirm's argument, stands down.
        var observer = new RecordingObserver();
        var parkedB = new CountDownLatch(1);
        try (var nodeB = node(confirms, parkedB, observer)) {
            assertEquals(1, nodeB.recover());
            var deadline = System.nanoTime() + BOUND.toNanos();
            while (parkedB.getCount() > 0 && observer.standDowns().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            var deadlineIdle = System.nanoTime() + BOUND.toNanos();
            while (nodeB.executor().isRunning(workflowId) && parkedB.getCount() > 0
                    && System.nanoTime() < deadlineIdle) {
                Thread.sleep(20);
            }

            assertAll(
                    () -> assertEquals(List.of(new RecordingObserver.StandDownCall(
                                    StandDownReason.NONDETERMINISTIC_HISTORY, workflowId,
                                    "seq=2 step=Booking.confirm argsHash expected=" + fpReadBack.substring(0, 8)
                                            + " stored=" + fpRaw.substring(0, 8))),
                            observer.standDowns()),
                    () -> assertEquals(beforeStatus, store.getInstance(workflowId).orElseThrow().status()),
                    () -> assertEquals(List.of(), observer.failed(), "a stand-down is not a failure"),
                    () -> assertEquals(List.of(), observer.compensating(), "nothing is compensated"),
                    () -> assertEquals(1, confirms.get(), "confirm ran once, on node A"));
        }
    }

    @Test
    @DisplayName("with verify-arguments=false the same history replays the recorded confirm and completes with the read-back slot")
    void rawArgumentHistory_verifyArgumentsOff_replaysTheRecordedStepAndContinuesWithTheReadBackValue()
            throws Exception {
        var confirms = new AtomicInteger();
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("raw-args-off");

        // ── Node A: reserveSlot() and confirm(slot) run live; the run parks.
        var parkedA = new CountDownLatch(1);
        WorkflowHandle handle;
        try (var nodeA = node(confirms, parkedA, null)) {
            handle = nodeA.start(workflowId, SlotWorkflow.class, "order-1");
            assertTrue(parkedA.await(30, TimeUnit.SECONDS), "node A must reach the await point");
            handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
        }
        var instanceId = handle.instanceId();
        assertEquals(1, confirms.get());

        // Seq 2 as cbf5a2a wrote it: confirm's result over the raw +02:00 slot, and that slot's fingerprint.
        var fpRaw = ArgumentFingerprint.of(serializer, new Object[]{BookingImpl.SLOT});
        assertNotNull(fpRaw);
        setArgsHash(instanceId, 2, fpRaw);
        var recordedConfirmation = "confirmed slot-7 at 2026-11-12T10:00+02:00";
        setPayload(instanceId, 2, "\"" + recordedConfirmation + "\"");

        // ── Node B, verification off: replays both steps without comparing fingerprints, then completes.
        var observer = new RecordingObserver();
        var parkedB = new CountDownLatch(1);
        WorkflowStatus terminal;
        try (var nodeB = node(confirms, parkedB, observer, false)) {
            assertEquals(1, nodeB.recover());
            var deadline = System.nanoTime() + BOUND.toNanos();
            while (parkedB.getCount() > 0 && observer.standDowns().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(List.of(), observer.standDowns(), "verification is off: the replay must not stand down");
            nodeB.deliverSignal(workflowId, "go", "go");
            terminal = handle.awaitTerminal(BOUND);
        }

        assertAll(
                () -> assertEquals(WorkflowStatus.COMPLETED, terminal),
                // the recorded confirmation (seq 2 replayed, never re-run), then the slot as it reads back
                () -> assertEquals(recordedConfirmation + ":2026-11-12T08:00Z:go", handle.result(String.class)),
                () -> assertEquals(1, confirms.get(), "confirm ran once, on node A"),
                () -> assertEquals(List.of(), observer.failed(), "nothing failed"),
                () -> assertEquals(List.of(), observer.compensating(), "nothing is compensated"));
    }

    private MaestroEngineHarness node(AtomicInteger confirms, CountDownLatch parked,
                                      @Nullable RecordingObserver observer) {
        return node(confirms, parked, observer, true);
    }

    private MaestroEngineHarness node(AtomicInteger confirms, CountDownLatch parked,
                                      @Nullable RecordingObserver observer, boolean verifyArguments) {
        var builder = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName(SERVICE)
                .lock(newLock())
                .instanceLockTtl(LOCK_TTL)
                .verifyArguments(verifyArguments);
        if (observer != null) {
            builder.observer(observer);
        }
        var node = builder.build();
        node.registerActivities(Booking.class, new BookingImpl(confirms));
        node.registerWorkflow(SlotWorkflow.class, () -> new SlotWorkflow(parked));
        return node;
    }

    private void setArgsHash(UUID instanceId, int seq, String argsHash) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("UPDATE maestro_workflow_event SET args_hash = ? "
                     + "WHERE workflow_instance_id = ? AND sequence_number = ?")) {
            ps.setString(1, argsHash);
            ps.setObject(2, instanceId);
            ps.setInt(3, seq);
            assertEquals(1, ps.executeUpdate());
        }
    }

    private void setPayload(UUID instanceId, int seq, String json) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("UPDATE maestro_workflow_event SET payload = ?::jsonb "
                     + "WHERE workflow_instance_id = ? AND sequence_number = ?")) {
            ps.setString(1, json);
            ps.setObject(2, instanceId);
            ps.setInt(3, seq);
            assertEquals(1, ps.executeUpdate());
        }
    }

    // ── fixture ───────────────────────────────────────────────────────────

    public record DeliverySlot(String id, OffsetDateTime start) {}

    @Activity
    public interface Booking {
        DeliverySlot reserveSlot(String orderId);

        String confirm(DeliverySlot slot);
    }

    public static final class BookingImpl implements Booking {

        /** A courier API answering in the warehouse's local offset. */
        static final DeliverySlot SLOT = new DeliverySlot("slot-7", OffsetDateTime.parse("2026-11-12T10:00:00+02:00"));

        private final AtomicInteger confirms;

        BookingImpl(AtomicInteger confirms) {
            this.confirms = confirms;
        }

        @Override
        public DeliverySlot reserveSlot(String orderId) {
            return SLOT;
        }

        @Override
        public String confirm(DeliverySlot slot) {
            confirms.incrementAndGet();
            return "confirmed " + slot.id() + " at " + slot.start();
        }
    }

    @DurableWorkflow(name = "RawArgumentSlotWorkflow")
    public static class SlotWorkflow {

        @ActivityStub(startToCloseTimeout = "PT10S")
        Booking booking;

        private final CountDownLatch parked;

        public SlotWorkflow(CountDownLatch parked) {
            this.parked = parked;
        }

        @WorkflowMethod
        public String run(String orderId) {
            var slot = booking.reserveSlot(orderId);
            var confirmation = booking.confirm(slot);
            parked.countDown();
            var go = WorkflowContext.current().awaitSignal("go", String.class, Duration.ofSeconds(60));
            return confirmation + ":" + slot.start() + ":" + go;
        }
    }
}
