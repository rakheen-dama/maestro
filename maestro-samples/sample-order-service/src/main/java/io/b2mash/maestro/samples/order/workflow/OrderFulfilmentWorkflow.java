package io.b2mash.maestro.samples.order.workflow;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.QueryMethod;
import io.b2mash.maestro.core.annotation.RetryPolicy;
import io.b2mash.maestro.core.annotation.Saga;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.SignalTimeoutException;
import io.b2mash.maestro.samples.order.activity.InventoryActivities;
import io.b2mash.maestro.samples.order.activity.MessagingActivities;
import io.b2mash.maestro.samples.order.activity.NotificationActivities;
import io.b2mash.maestro.samples.order.activity.ShippingActivities;
import io.b2mash.maestro.samples.order.domain.OrderInput;
import io.b2mash.maestro.samples.order.domain.OrderResult;
import io.b2mash.maestro.samples.order.domain.OrderStatus;
import io.b2mash.maestro.samples.order.domain.PaymentRequest;
import io.b2mash.maestro.samples.order.domain.PaymentResult;
import io.b2mash.maestro.samples.order.exception.OrderFailedException;

import java.time.Duration;

/**
 * Fulfils an order: reserve inventory, request payment from the payment
 * gateway, wait for its answer, ship, notify.
 *
 * <h2>The saga</h2>
 * <p>{@code inventory.reserve} is {@code @Compensate("releaseReservation")}, and
 * the workflow method is a {@code @Saga}. When the payment is declined or never
 * arrives, the workflow notifies the customer and then <em>throws</em>
 * {@link OrderFailedException}. The throw is what triggers compensation: Maestro
 * releases the reservation (with the reservation the {@code reserve} step
 * returned) and records the order {@code FAILED}, with the exception's class and
 * message readable through {@code WorkflowHandle.failure()}. Returning a
 * "failed" result instead would complete the workflow — {@code COMPLETED}, no
 * compensation, the stock held forever.
 */
@DurableWorkflow(name = "order-fulfilment", taskQueue = "orders")
public class OrderFulfilmentWorkflow {

    @ActivityStub(startToCloseTimeout = "PT30S",
                  retryPolicy = @RetryPolicy(maxAttempts = 3))
    private InventoryActivities inventory;

    @ActivityStub(startToCloseTimeout = "PT10S")
    private MessagingActivities messaging;

    @ActivityStub(startToCloseTimeout = "PT30S")
    private ShippingActivities shipping;

    @ActivityStub(startToCloseTimeout = "PT10S")
    private NotificationActivities notifications;

    // Per-run state: Maestro creates a new workflow object for every run. Volatile because @QueryMethod runs on the caller's thread.
    private volatile String currentStep = "CREATED";

    @WorkflowMethod
    @Saga(parallelCompensation = false)
    public OrderResult fulfil(OrderInput input) {
        var workflow = WorkflowContext.current();

        // Step 1: Reserve inventory (compensatable)
        currentStep = "RESERVING_INVENTORY";
        var reservation = inventory.reserve(input.items());

        // Step 2: Request payment via Kafka
        currentStep = "REQUESTING_PAYMENT";
        messaging.publishPaymentRequest(
            new PaymentRequest(input.orderId(), input.paymentMethod(), reservation.total())
        );

        // Step 3: Await payment result signal (throws SignalTimeoutException on timeout)
        currentStep = "AWAITING_PAYMENT";
        PaymentResult paymentResult;
        try {
            paymentResult = workflow.awaitSignal(
                "payment.result", PaymentResult.class, Duration.ofHours(1)
            );
        } catch (SignalTimeoutException e) {
            currentStep = "PAYMENT_TIMED_OUT";
            notifications.notifyCustomer(input.customerId(), "Payment timed out");
            throw new OrderFailedException("Payment timed out");
        }

        if (!paymentResult.success()) {
            currentStep = "PAYMENT_FAILED";
            var reason = "Payment failed: " + paymentResult.reason();
            notifications.notifyCustomer(input.customerId(), reason);
            throw new OrderFailedException(reason);
        }

        // Step 4: Arrange shipment
        currentStep = "ARRANGING_SHIPMENT";
        var shipment = shipping.createShipment(
            input.orderId(), input.shippingAddress(), reservation.warehouseId()
        );

        // Step 5: Notify customer
        currentStep = "NOTIFYING_CUSTOMER";
        notifications.notifyCustomer(input.customerId(),
            "Order confirmed! Tracking: " + shipment.trackingNumber());

        currentStep = "COMPLETED";
        return OrderResult.success(input.orderId(), shipment.trackingNumber());
    }

    @QueryMethod
    public OrderStatus getStatus() {
        return new OrderStatus(currentStep);
    }
}
