package io.b2mash.maestro.samples.rabbitmqorder.domain;

import java.math.BigDecimal;

public record PaymentRequest(String orderId, String paymentMethod, BigDecimal amount) {}
