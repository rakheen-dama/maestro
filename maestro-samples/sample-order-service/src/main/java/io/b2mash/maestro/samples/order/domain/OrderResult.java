package io.b2mash.maestro.samples.order.domain;

import org.jspecify.annotations.Nullable;

public record OrderResult(
    String orderId,
    @Nullable String trackingNumber,
    boolean success,
    @Nullable String failureReason
) {

    public static OrderResult success(String orderId, String trackingNumber) {
        return new OrderResult(orderId, trackingNumber, true, null);
    }

    public static OrderResult failed(String reason) {
        return new OrderResult("", null, false, reason);
    }
}
