package io.b2mash.maestro.samples.order.domain;

/**
 * The result of a fulfilled order. A failed order has no result: the workflow
 * throws {@code OrderFailedException}, compensates, and ends {@code FAILED}.
 *
 * @param orderId        the order's ID
 * @param trackingNumber the shipment's tracking number
 */
public record OrderResult(String orderId, String trackingNumber) {

    /**
     * @param orderId        the order's ID
     * @param trackingNumber the shipment's tracking number
     * @return the result of a fulfilled order
     */
    public static OrderResult success(String orderId, String trackingNumber) {
        return new OrderResult(orderId, trackingNumber);
    }
}
