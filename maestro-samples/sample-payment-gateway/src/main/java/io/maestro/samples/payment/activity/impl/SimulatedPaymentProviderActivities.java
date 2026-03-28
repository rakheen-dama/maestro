package io.maestro.samples.payment.activity.impl;

import io.maestro.samples.payment.activity.PaymentProviderActivities;
import io.maestro.samples.payment.domain.PaymentConfirmation;
import io.maestro.samples.payment.exception.PaymentDeclinedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulates an unreliable external payment provider to demonstrate
 * Maestro's durable retry behaviour.
 *
 * <ul>
 *   <li>{@code "DECLINE_ME"} payment method triggers a permanent failure.</li>
 *   <li>~30% of calls throw a transient exception (retried by Maestro).</li>
 *   <li>Successful calls include a random 100-500ms latency.</li>
 * </ul>
 */
@Component
public class SimulatedPaymentProviderActivities implements PaymentProviderActivities {

    private static final Logger logger = LoggerFactory.getLogger(SimulatedPaymentProviderActivities.class);

    @Override
    public PaymentConfirmation charge(String paymentMethod, BigDecimal amount) {
        logger.info("Attempting charge: {} {}", paymentMethod, amount);

        if ("DECLINE_ME".equals(paymentMethod)) {
            throw new PaymentDeclinedException("Card permanently declined");
        }

        var random = ThreadLocalRandom.current().nextInt(100);
        if (random < 30) {
            throw new RuntimeException(
                    "Payment provider temporarily unavailable — simulating transient failure");
        }

        // Thread.sleep() is safe in activity code (not workflow code).
        // Activities run real side effects — non-determinism is expected.
        // In workflow code, use workflow.sleep() instead (durable timer).
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(100, 501));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Charge interrupted", e);
        }

        var transactionId = "txn-" + UUID.randomUUID().toString().substring(0, 8);
        logger.info("Payment charged successfully: txnId={}, amount={}", transactionId, amount);

        return new PaymentConfirmation(transactionId);
    }
}
