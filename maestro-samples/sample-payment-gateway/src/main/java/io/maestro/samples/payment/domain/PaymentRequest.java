package io.maestro.samples.payment.domain;

import java.math.BigDecimal;

/**
 * Inbound payment request — typically received from an order service via Kafka.
 */
public record PaymentRequest(String orderId, String paymentMethod, BigDecimal amount) {}
