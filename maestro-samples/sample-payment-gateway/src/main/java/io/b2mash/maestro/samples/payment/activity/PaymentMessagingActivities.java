package io.b2mash.maestro.samples.payment.activity;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.samples.payment.domain.PaymentResult;

/**
 * Activities for publishing payment results to downstream consumers.
 */
@Activity
public interface PaymentMessagingActivities {

    /**
     * Publishes the payment result for the given order to Kafka.
     *
     * @param orderId the order that was paid (or failed)
     * @param result  the payment outcome
     */
    void publishPaymentResult(String orderId, PaymentResult result);
}
