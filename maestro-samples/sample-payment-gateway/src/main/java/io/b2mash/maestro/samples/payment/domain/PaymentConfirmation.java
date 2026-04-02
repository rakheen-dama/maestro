package io.b2mash.maestro.samples.payment.domain;

/**
 * Confirmation returned by the payment provider after a successful charge.
 */
public record PaymentConfirmation(String transactionId) {}
