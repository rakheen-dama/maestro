package io.maestro.samples.order.domain;

import java.math.BigDecimal;

public record PaymentRequest(String orderId, String paymentMethod, BigDecimal amount) {}
