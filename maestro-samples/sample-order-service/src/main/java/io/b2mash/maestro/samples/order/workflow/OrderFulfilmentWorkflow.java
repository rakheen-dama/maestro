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

import java.time.Duration;

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

    // Volatile: read by @QueryMethod from caller's thread, written by workflow's virtual thread
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
            return OrderResult.failed("Payment timed out");
            // @Saga compensation: inventory.releaseReservation() runs automatically
        }

        if (!paymentResult.success()) {
            currentStep = "PAYMENT_FAILED";
            notifications.notifyCustomer(input.customerId(),
                "Payment failed: " + paymentResult.reason());
            return OrderResult.failed(paymentResult.reason());
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
