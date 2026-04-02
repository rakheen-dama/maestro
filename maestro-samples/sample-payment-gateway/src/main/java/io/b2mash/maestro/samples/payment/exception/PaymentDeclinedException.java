package io.b2mash.maestro.samples.payment.exception;

/**
 * Thrown when a payment is permanently declined (e.g. card declined, insufficient funds).
 *
 * <p>This is a non-retryable failure — the workflow should catch it and notify
 * downstream services rather than retrying.</p>
 *
 * <p><b>Note:</b> This extends {@link RuntimeException} rather than
 * {@code MaestroException} because {@code MaestroException} is {@code sealed}
 * to the {@code io.b2mash.maestro.core.exception} package. Application-level domain
 * exceptions should use standard Java exception hierarchies.</p>
 */
public class PaymentDeclinedException extends RuntimeException {

    public PaymentDeclinedException(String message) {
        super(message);
    }
}
