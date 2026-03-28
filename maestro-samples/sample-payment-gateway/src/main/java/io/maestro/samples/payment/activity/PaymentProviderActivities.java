package io.maestro.samples.payment.activity;

import io.maestro.core.annotation.Activity;
import io.maestro.samples.payment.domain.PaymentConfirmation;

import java.math.BigDecimal;

/**
 * Activities for interacting with an external payment provider.
 */
@Activity
public interface PaymentProviderActivities {

    /**
     * Charges the given payment method for the specified amount.
     *
     * @param paymentMethod the payment method identifier
     * @param amount        the amount to charge
     * @return confirmation with the provider's transaction ID
     */
    PaymentConfirmation charge(String paymentMethod, BigDecimal amount);
}
