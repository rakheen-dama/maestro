package io.b2mash.maestro.samples.order.workflow;

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

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-memory tests for {@link OrderFulfilmentWorkflow} using
 * {@link TestWorkflowEnvironment}, including the per-run state of its
 * {@code currentStep} query field (F1: one shared workflow object made every
 * running order report the step of whichever ran last).
 */
class OrderFulfilmentWorkflowTest {

    private TestWorkflowEnvironment env;
    private LatchingInventory inventory;
    private RecordingMessaging messaging;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.create();
        inventory = new LatchingInventory();
        messaging = new RecordingMessaging();
        env.registerActivities(InventoryActivities.class, inventory);
        env.registerActivities(MessagingActivities.class, messaging);
        env.registerActivities(ShippingActivities.class, new FixedShipping());
        env.registerActivities(NotificationActivities.class, new SilentNotifications());
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
        awaitStatus(h, WorkflowStatus.WAITING_SIGNAL);

        h.signal("payment.result", PaymentResult.success("tx-1"));
        var r = h.getResult(OrderResult.class, Duration.ofSeconds(10));

        assertTrue(r.success());
        assertEquals("order-1", r.orderId());
        assertEquals("TRACK-order-1", r.trackingNumber());
        assertEquals(List.of("order-1"), messaging.requestedOrderIds);
    }

    @Test
    void twoInstancesReportTheirOwnStep() {
        inventory.block("B");
        var a = env.startWorkflow(OrderFulfilmentWorkflow.class, order("A"));
        var b = env.startWorkflow(OrderFulfilmentWorkflow.class, order("B"));
        awaitStatus(a, WorkflowStatus.WAITING_SIGNAL);
        inventory.awaitBlocked("B");

        assertEquals("AWAITING_PAYMENT", a.query("getStatus", null, OrderStatus.class).currentStep());
        assertEquals("RESERVING_INVENTORY", b.query("getStatus", null, OrderStatus.class).currentStep());

        inventory.release("B");
        awaitStatus(b, WorkflowStatus.WAITING_SIGNAL);
        assertEquals("AWAITING_PAYMENT", b.query("getStatus", null, OrderStatus.class).currentStep());
    }

    /** One line item whose SKU is the order ID, so the inventory fake can tell orders apart. */
    private static OrderInput order(String orderId) {
        return new OrderInput(orderId, "customer-" + orderId,
                List.of(new OrderItem(orderId, 1, new BigDecimal("10.00"))),
                "VISA", "1 Main Street");
    }

    private static void awaitStatus(TestWorkflowHandle handle, WorkflowStatus status) {
        await().atMost(Duration.ofSeconds(10)).until(() -> handle.getStatus() == status);
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    /**
     * Reserves immediately, except that {@code reserve} blocks while the order —
     * identified by its first item's SKU (see {@link #order}) — is {@linkplain #block held}.
     */
    static final class LatchingInventory implements InventoryActivities {

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
            var orderId = items.getFirst().sku();
            var gate = gates.get(orderId);
            if (gate != null) {
                arrivals.get(orderId).countDown();
                try {
                    gate.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            var total = items.stream()
                    .map(i -> i.price().multiply(BigDecimal.valueOf(i.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return new ReservationConfirmation("res-" + orderId, total, "WH-1");
        }

        @Override
        public void releaseReservation(ReservationConfirmation reservation) {
            // no-op
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

    /** Ships every order with a tracking number derived from its ID. */
    static final class FixedShipping implements ShippingActivities {
        @Override
        public ShipmentConfirmation createShipment(String orderId, String shippingAddress, String warehouseId) {
            return new ShipmentConfirmation("ship-" + orderId, "TRACK-" + orderId);
        }
    }

    /** Drops customer notifications. */
    static final class SilentNotifications implements NotificationActivities {
        @Override
        public void notifyCustomer(String customerId, String message) {
            // no-op
        }
    }
}
