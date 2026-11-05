package io.b2mash.maestro.samples.order.exception;

/**
 * Thrown by the order workflow when an order cannot be fulfilled — the payment
 * was declined or never arrived.
 *
 * <p>Throwing, not returning a failed result, is what makes the saga
 * compensate: Maestro runs the {@code @Compensate} actions of a
 * {@code @Saga} workflow only when the workflow method ends with an exception.
 * The order then ends {@code FAILED} with this exception's class name and
 * message as its recorded failure ({@code WorkflowHandle.failure()}).
 *
 * <p><b>Note:</b> This extends {@link RuntimeException} rather than
 * {@code MaestroException} because {@code MaestroException} is {@code sealed}
 * to the {@code io.b2mash.maestro.core.exception} package. Application-level
 * domain exceptions should use standard Java exception hierarchies.
 */
public class OrderFailedException extends RuntimeException {

    private final String reason;

    /**
     * @param reason why the order failed, e.g. {@code "Payment timed out"}
     */
    public OrderFailedException(String reason) {
        super(reason);
        this.reason = reason;
    }

    /**
     * @return why the order failed
     */
    public String reason() {
        return reason;
    }
}
