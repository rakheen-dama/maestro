package io.b2mash.maestro.samples.rabbitmqorder.domain;

import org.jspecify.annotations.Nullable;

public record PaymentResult(
    boolean success,
    @Nullable String transactionId,
    @Nullable String reason
) {

    public static PaymentResult success(String transactionId) {
        return new PaymentResult(true, transactionId, null);
    }

    public static PaymentResult failed(String reason) {
        return new PaymentResult(false, null, reason);
    }
}
