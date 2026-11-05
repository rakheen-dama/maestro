package io.b2mash.maestro.samples.order.workflow;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.samples.order.activity.InventoryActivities;
import io.b2mash.maestro.samples.order.activity.MessagingActivities;
import io.b2mash.maestro.samples.order.activity.NotificationActivities;
import io.b2mash.maestro.samples.order.activity.ShippingActivities;
import io.b2mash.maestro.samples.order.domain.OrderInput;
import io.b2mash.maestro.samples.order.domain.OrderItem;
import io.b2mash.maestro.samples.order.domain.OrderResult;
import io.b2mash.maestro.samples.order.domain.OrderStatus;
import io.b2mash.maestro.samples.order.domain.PaymentRequest;
import io.b2mash.maestro.samples.order.domain.PaymentResult;
import io.b2mash.maestro.samples.order.domain.ReservationConfirmation;
import io.b2mash.maestro.samples.order.domain.ShipmentConfirmation;
import io.b2mash.maestro.samples.order.exception.OrderFailedException;
import io.b2mash.maestro.test.TestWorkflowEnvironment;
import io.b2mash.maestro.test.TestWorkflowHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-memory tests for {@link OrderFulfilmentWorkflow} using
 * {@link TestWorkflowEnvironment}: the paid path, the two failure paths that
 * must compensate (decline and timeout), crash recovery, and the per-run state
 * of the {@code currentStep} query field (F1: one shared workflow object made
 * every running order report the step of whichever ran last).
 */
class OrderFulfilmentWorkflowTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private TestWorkflowEnvironment env;
    private LatchingInventory inventory;
    private RecordingMessaging messaging;
    private CountingShipping shipping;
    private RecordingNotifications notifications;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.create();
        inventory = new LatchingInventory();
        messaging = new RecordingMessaging();
        shipping = new CountingShipping();
        notifications = new RecordingNotifications();
        env.registerActivities(InventoryActivities.class, inventory);
        env.registerActivities(MessagingActivities.class, messaging);
        env.registerActivities(ShippingActivities.class, shipping);
        env.registerActivities(NotificationActivities.class, notifications);
    }

    @AfterEach
    void tearDown() {
        if (inventory != null) {
            inventory.releaseAll();
        }
        if (env != null) {
            env.shutdown();
        }
    }

    @Test
    void paidOrderIsFulfilled() throws TimeoutException {
        var h = env.startWorkflow(OrderFulfilmentWorkflow.class, order("order-1"));
        h.awaitStatus(WorkflowStatus.WAITING_SIGNAL, WAIT);

        h.signal("payment.result", PaymentResult.success("tx-1"));
        var r = h.getResult(OrderResult.class, WAIT);

        assertEquals(new OrderResult("order-1", "TRACK-order-1"), r);
        assertEquals(List.of("order-1"), messaging.requestedOrderIds);
        assertTrue(inventory.released.isEmpty(), "a fulfilled order keeps its reservation");
    }

    @Test
    void declinedPayment_failsTheOrderAndReleasesTheReservation() throws TimeoutException {
        var h = env.startWorkflow(OrderFulfilmentWorkflow.class, order("order-1"));
        h.awaitStatus(WorkflowStatus.WAITING_SIGNAL, WAIT);

        h.signal("payment.result", PaymentResult.failed("Card declined"));

        assertEquals(WorkflowStatus.FAILED, h.awaitTerminal(WAIT));
        var failure = h.getFailure().orElseThrow();
        assertEquals(OrderFailedException.class.getName(), failure.exceptionType());
        assertEquals("Payment failed: Card declined", failure.message());
        assertReleasedOnce("res-order-1");
        assertEquals(0, shipping.calls.get(), "a declined order never ships");
        assertEquals(List.of("Payment failed: Card declined"), notifications.messages);
        assertCompensated(h);
    }

    @Test
    void paymentTimeout_failsTheOrderAndReleasesTheReservation() throws TimeoutException {
        var h = env.startWorkflow(OrderFulfilmentWorkflow.class, order("order-1"));
        h.awaitStatus(WorkflowStatus.WAITING_SIGNAL, WAIT);

        env.advanceTime(Duration.ofHours(1));

        assertEquals(WorkflowStatus.FAILED, h.awaitTerminal(WAIT));
        var failure = h.getFailure().orElseThrow();
        assertEquals(OrderFailedException.class.getName(), failure.exceptionType());
        assertEquals("Payment timed out", failure.message());
        assertReleasedOnce("res-order-1");
        assertEquals(0, shipping.calls.get());
        assertEquals(List.of("Payment timed out"), notifications.messages);
        assertCompensated(h);
    }

    @Test
    void crashAfterPaymentRequest_thenDecline_compensatesWithTheReplayedReservation() throws TimeoutException {
        var crash = env.crashAfter(2);
        var h = env.startWorkflow(OrderFulfilmentWorkflow.class, order("order-1"));
        crash.recover();

        h.signal("payment.result", PaymentResult.failed("Card declined"));

        assertEquals(WorkflowStatus.FAILED, h.awaitTerminal(WAIT));
        assertEquals(List.of("InventoryActivities.reserve", "MessagingActivities.publishPaymentRequest"),
                h.replayedSteps());
        assertEquals(1, inventory.reserveCalls.get(), "reserved once, replayed after the crash");
        assertEquals(List.of("order-1"), messaging.requestedOrderIds, "payment requested once");
        assertReleasedOnce("res-order-1");
        assertEquals(0, shipping.calls.get());
    }

    @Test
    void crashWhileAwaitingPayment_thenPaid_ships() throws TimeoutException {
        var h = env.startWorkflow(OrderFulfilmentWorkflow.class, order("order-1"));
        h.awaitStatus(WorkflowStatus.WAITING_SIGNAL, WAIT);

        env.crashNow().recover();
        h.signal("payment.result", PaymentResult.success("tx-1"));

        assertEquals(new OrderResult("order-1", "TRACK-order-1"), h.getResult(OrderResult.class, WAIT));
        assertEquals(1, h.recoveries());
        assertEquals(1, inventory.reserveCalls.get());
        assertEquals(1, shipping.calls.get());
    }

    @Test
    void crashBeforeThePaymentRequestIsRecorded_publishesItTwice() throws TimeoutException {
        // The publish ran, but the crash lost its result: the recovering node
        // publishes again. This is why the payment gateway keys its workflow on
        // the order ID — the duplicate request starts no second charge.
        var crash = env.crashBeforePersisting(e -> e.eventType() == EventType.ACTIVITY_COMPLETED
                && "MessagingActivities.publishPaymentRequest".equals(e.stepName()));
        var h = env.startWorkflow(OrderFulfilmentWorkflow.class, order("order-1"));
        crash.recover();

        h.signal("payment.result", PaymentResult.success("tx-1"));

        assertEquals(new OrderResult("order-1", "TRACK-order-1"), h.getResult(OrderResult.class, WAIT));
        assertEquals(List.of("order-1", "order-1"), messaging.requestedOrderIds);
        assertEquals(2, h.liveExecutions("MessagingActivities.publishPaymentRequest"));
        assertEquals(1, inventory.reserveCalls.get());
    }

    @Test
    void twoInstancesReportTheirOwnStep() throws TimeoutException {
        inventory.block("B");
        var a = env.startWorkflow(OrderFulfilmentWorkflow.class, order("A"));
        var b = env.startWorkflow(OrderFulfilmentWorkflow.class, order("B"));
        a.awaitStatus(WorkflowStatus.WAITING_SIGNAL, WAIT);
        inventory.awaitBlocked("B");

        assertEquals("AWAITING_PAYMENT", a.query("getStatus", null, OrderStatus.class).currentStep());
        assertEquals("RESERVING_INVENTORY", b.query("getStatus", null, OrderStatus.class).currentStep());

        inventory.release("B");
        b.awaitStatus(WorkflowStatus.WAITING_SIGNAL, WAIT);
        assertEquals("AWAITING_PAYMENT", b.query("getStatus", null, OrderStatus.class).currentStep());
    }

    /** One line item whose SKU is the order ID, so the inventory fake can tell orders apart. */
    private static OrderInput order(String orderId) {
        return new OrderInput(orderId, "customer-" + orderId,
                List.of(new OrderItem(orderId, 1, new BigDecimal("10.00"))),
                "VISA", "1 Main Street");
    }

    private void assertReleasedOnce(String reservationId) {
        assertEquals(1, inventory.released.size(), "the reservation is released exactly once");
        var released = inventory.released.getFirst();
        assertEquals(reservationId, released.reservationId());
        assertEquals(0, new BigDecimal("10.00").compareTo(released.total()), "released total " + released.total());
        assertEquals("WH-1", released.warehouseId());
    }

    private static void assertCompensated(TestWorkflowHandle h) {
        var types = h.getEvents().stream().map(e -> e.eventType()).toList();
        assertTrue(types.contains(EventType.COMPENSATION_STARTED), types.toString());
        assertTrue(types.contains(EventType.COMPENSATION_COMPLETED), types.toString());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    /**
     * Reserves immediately, except that {@code reserve} blocks while the order —
     * identified by its first item's SKU (see {@link #order}) — is {@linkplain #block held}.
     * Records every release.
     */
    static final class LatchingInventory implements InventoryActivities {

        final AtomicInteger reserveCalls = new AtomicInteger();
        final List<ReservationConfirmation> released = new CopyOnWriteArrayList<>();
        private final ConcurrentHashMap<String, CountDownLatch> gates = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, CountDownLatch> arrivals = new ConcurrentHashMap<>();

        void block(String orderId) {
            gates.put(orderId, new CountDownLatch(1));
            arrivals.put(orderId, new CountDownLatch(1));
        }

        void awaitBlocked(String orderId) {
            try {
                assertTrue(arrivals.get(orderId).await(10, TimeUnit.SECONDS),
                        "order '" + orderId + "' never reached reserve");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        void release(String orderId) {
            gates.get(orderId).countDown();
        }

        void releaseAll() {
            gates.values().forEach(CountDownLatch::countDown);
        }

        @Override
        public ReservationConfirmation reserve(List<OrderItem> items) {
            reserveCalls.incrementAndGet();
            var orderId = items.getFirst().sku();
            var gate = gates.get(orderId);
            if (gate != null) {
                arrivals.get(orderId).countDown();
                try {
                    if (!gate.await(20, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("reserve for order '" + orderId + "' was never released by the test");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            var total = items.stream()
                    .map(i -> i.price().multiply(BigDecimal.valueOf(i.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return new ReservationConfirmation("res-" + orderId, total, "WH-1");
        }

        @Override
        public void releaseReservation(ReservationConfirmation reservation) {
            released.add(reservation);
        }
    }

    /** Records every payment request instead of publishing it to Kafka. */
    static final class RecordingMessaging implements MessagingActivities {
        final List<String> requestedOrderIds = new CopyOnWriteArrayList<>();

        @Override
        public void publishPaymentRequest(PaymentRequest request) {
            requestedOrderIds.add(request.orderId());
        }
    }

    /** Ships every order with a tracking number derived from its ID, counting calls. */
    static final class CountingShipping implements ShippingActivities {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ShipmentConfirmation createShipment(String orderId, String shippingAddress, String warehouseId) {
            calls.incrementAndGet();
            return new ShipmentConfirmation("ship-" + orderId, "TRACK-" + orderId);
        }
    }

    /** Records customer notifications other than the order confirmation. */
    static final class RecordingNotifications implements NotificationActivities {
        final List<String> messages = new CopyOnWriteArrayList<>();

        @Override
        public void notifyCustomer(String customerId, String message) {
            if (!message.startsWith("Order confirmed")) {
                messages.add(message);
            }
        }
    }
}
