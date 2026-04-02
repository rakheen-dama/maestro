package io.b2mash.maestro.samples.rabbitmqorder.domain;

import org.jspecify.annotations.Nullable;

public record PaymentResultEvent(
    String orderId,
    boolean success,
    @Nullable String transactionId,
    @Nullable String reason
) {}
