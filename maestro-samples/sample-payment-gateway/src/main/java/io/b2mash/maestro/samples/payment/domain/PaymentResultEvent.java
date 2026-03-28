package io.b2mash.maestro.samples.payment.domain;

import org.jspecify.annotations.Nullable;

/**
 * Kafka event published when a payment has been processed.
 */
public record PaymentResultEvent(
        String orderId,
        boolean success,
        @Nullable String transactionId,
        @Nullable String reason
) {

    /**
     * Creates a successful payment event.
     */
    public static PaymentResultEvent success(String orderId, String transactionId) {
        return new PaymentResultEvent(orderId, true, transactionId, null);
    }

    /**
     * Creates a failed payment event.
     */
    public static PaymentResultEvent failed(String orderId, String reason) {
        return new PaymentResultEvent(orderId, false, null, reason);
    }
}
