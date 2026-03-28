package io.maestro.samples.payment.domain;

import org.jspecify.annotations.Nullable;

/**
 * Result of a payment attempt — published to downstream consumers via Kafka.
 */
public record PaymentResult(boolean success, @Nullable String transactionId, @Nullable String reason) {

    /**
     * Creates a successful payment result with the given transaction ID.
     */
    public static PaymentResult success(String transactionId) {
        return new PaymentResult(true, transactionId, null);
    }

    /**
     * Creates a failed payment result with the given reason.
     */
    public static PaymentResult failed(String reason) {
        return new PaymentResult(false, null, reason);
    }
}
