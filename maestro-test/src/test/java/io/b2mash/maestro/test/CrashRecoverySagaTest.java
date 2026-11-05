package io.b2mash.maestro.test;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.Compensate;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.RetryPolicy;
import io.b2mash.maestro.core.annotation.Saga;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.exception.ActivityExecutionException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Saga compensation across a crash: the compensations must receive the
 * <em>replayed</em> (deserialised) results, and a compensation already recorded
 * must not run twice.
 */
class CrashRecoverySagaTest {

    private TestWorkflowEnvironment env;
    private RecordingShop shop;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.create();
        shop = new RecordingShop();
        env.registerActivities(Shop.class, shop);
    }

    @AfterEach
    void tearDown() {
        env.shutdown();
    }

    @Test
    void crashBeforeTheFailure_recoveredRunCompensatesWithReplayedResults() throws Exception {
        var crash = env.crashAfter(2);
        var handle = env.startWorkflow(PurchaseWorkflow.class, "sku-9");

        crash.recover();

        assertEquals(WorkflowStatus.FAILED, handle.awaitTerminal(Duration.ofSeconds(10)));
        assertEquals(List.of("Shop.reserve", "Shop.charge"), handle.replayedSteps());
        assertEquals(1, shop.reserved.size());
        assertEquals(1, shop.charged.size());
        assertEquals(1, shop.shipAttempts.size());

        assertEquals(1, shop.refunded.size(), "refund ran exactly once");
        assertEquals(new Charge("ch-sku-9", new BigDecimal("42.50")), shop.refunded.getFirst());
        assertEquals(1, shop.released.size(), "release ran exactly once");
        var released = shop.released.getFirst();
        assertEquals("res-sku-9", released.reservationId());
        assertEquals(0, new BigDecimal("42.50").compareTo(released.amount()),
                "the compensation got the replayed reservation, amount intact");

        var failure = handle.getFailure().orElseThrow();
        assertEquals(ActivityExecutionException.class.getName(), failure.exceptionType());
        assertTrue(failure.message() != null && failure.message().contains("no courier"), failure.message());
    }

    @Test
    void crashMidCompensation_aRecordedCompensationDoesNotRunAgain() throws Exception {
        var crash = env.crashAfterPersisting(e -> e.eventType() == EventType.COMPENSATION_STEP_COMPLETED);
        var handle = env.startWorkflow(PurchaseWorkflow.class, "sku-7");
        crash.awaitCrash(Duration.ofSeconds(10));
        assertEquals(WorkflowStatus.COMPENSATING, handle.getStatus(), "the crash interrupted the unwind");
        assertEquals(1, shop.refunded.size());
        assertEquals(0, shop.released.size());

        crash.recover();

        assertEquals(WorkflowStatus.FAILED, handle.awaitTerminal(Duration.ofSeconds(10)));
        assertEquals(1, shop.refunded.size(), "the recorded refund is not re-run");
        assertEquals(1, shop.released.size(), "the release that never ran runs once");
        assertEquals("res-sku-7", shop.released.getFirst().reservationId());
        assertEquals(1, shop.shipAttempts.size(), "the recorded failure replays");
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    public record Reservation(String reservationId, BigDecimal amount) {
    }

    public record Charge(String chargeId, BigDecimal amount) {
    }

    @Activity
    public interface Shop {
        @Compensate("release")
        Reservation reserve(String sku);

        void release(Reservation reservation);

        @Compensate("refund")
        Charge charge(BigDecimal amount);

        void refund(Charge charge);

        String ship(String sku);
    }

    static final class RecordingShop implements Shop {
        final List<String> reserved = new CopyOnWriteArrayList<>();
        final List<Reservation> released = new CopyOnWriteArrayList<>();
        final List<BigDecimal> charged = new CopyOnWriteArrayList<>();
        final List<Charge> refunded = new CopyOnWriteArrayList<>();
        final List<String> shipAttempts = new CopyOnWriteArrayList<>();
        private volatile String lastSku = "";

        @Override
        public Reservation reserve(String sku) {
            reserved.add(sku);
            lastSku = sku;
            return new Reservation("res-" + sku, new BigDecimal("42.50"));
        }

        @Override
        public void release(Reservation reservation) {
            released.add(reservation);
        }

        @Override
        public Charge charge(BigDecimal amount) {
            charged.add(amount);
            return new Charge("ch-" + lastSku, amount);
        }

        @Override
        public void refund(Charge charge) {
            refunded.add(charge);
        }

        @Override
        public String ship(String sku) {
            shipAttempts.add(sku);
            throw new IllegalStateException("no courier for " + sku);
        }
    }

    @DurableWorkflow(name = "purchase")
    public static class PurchaseWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S", retryPolicy = @RetryPolicy(maxAttempts = 1))
        private Shop shop;

        @WorkflowMethod
        @Saga
        public String run(String sku) {
            var reservation = shop.reserve(sku);
            shop.charge(reservation.amount());
            return shop.ship(sku);
        }
    }
}
