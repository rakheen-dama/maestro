package io.maestro.samples.payment.workflow;

import io.maestro.core.annotation.ActivityStub;
import io.maestro.core.annotation.DurableWorkflow;
import io.maestro.core.annotation.RetryPolicy;
import io.maestro.core.annotation.WorkflowMethod;
import io.maestro.samples.payment.activity.PaymentMessagingActivities;
import io.maestro.samples.payment.activity.PaymentProviderActivities;
import io.maestro.samples.payment.domain.PaymentProcessingResult;
import io.maestro.samples.payment.domain.PaymentRequest;
import io.maestro.samples.payment.domain.PaymentResult;
import io.maestro.samples.payment.exception.PaymentDeclinedException;

/**
 * Durable workflow that processes a payment request by charging the payment
 * provider and publishing the result to Kafka.
 *
 * <p>Transient provider failures are retried automatically by Maestro with
 * exponential backoff (up to 30 attempts over ~10 minutes). Permanent
 * failures (card declined) are caught and published as a failed result.</p>
 */
@DurableWorkflow(name = "payment-processing", taskQueue = "payments")
public class PaymentProcessingWorkflow {

    @ActivityStub(
            startToCloseTimeout = "PT60S",
            retryPolicy = @RetryPolicy(
                    maxAttempts = 30,
                    initialInterval = "PT5S",
                    maxInterval = "PT10M",
                    backoffMultiplier = 2.0
            ))
    private PaymentProviderActivities provider;

    // Publishing the payment result is critical — if this fails after a successful
    // charge, money is taken but the order service never learns the outcome.
    // Aggressive retry ensures Kafka transient failures don't cause data loss.
    @ActivityStub(
            startToCloseTimeout = "PT10S",
            retryPolicy = @RetryPolicy(
                    maxAttempts = 60,
                    initialInterval = "PT1S",
                    maxInterval = "PT2M",
                    backoffMultiplier = 2.0
            ))
    private PaymentMessagingActivities messaging;

    @WorkflowMethod
    public PaymentProcessingResult process(PaymentRequest request) {
        try {
            var confirmation = provider.charge(request.paymentMethod(), request.amount());

            messaging.publishPaymentResult(
                    request.orderId(),
                    PaymentResult.success(confirmation.transactionId())
            );

            return PaymentProcessingResult.success(confirmation.transactionId());

        } catch (PaymentDeclinedException e) {
            messaging.publishPaymentResult(
                    request.orderId(),
                    PaymentResult.failed(e.getMessage())
            );
            return PaymentProcessingResult.declined(e.getMessage());
        }
    }
}
