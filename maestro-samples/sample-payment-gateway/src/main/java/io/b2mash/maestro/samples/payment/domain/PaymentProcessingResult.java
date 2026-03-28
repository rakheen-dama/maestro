package io.b2mash.maestro.samples.payment.domain;

import org.jspecify.annotations.Nullable;

/**
 * Final outcome of the payment processing workflow.
 */
public record PaymentProcessingResult(boolean success, @Nullable String transactionId, @Nullable String reason) {

    /**
     * Creates a successful processing result with the given transaction ID.
     */
    public static PaymentProcessingResult success(String transactionId) {
        return new PaymentProcessingResult(true, transactionId, null);
    }

    /**
     * Creates a declined processing result with the given reason.
     */
    public static PaymentProcessingResult declined(String reason) {
        return new PaymentProcessingResult(false, null, reason);
    }
}
