package io.maestro.samples.order.domain;

import java.util.List;

public record PlaceOrderRequest(
    String customerId,
    List<OrderItem> items,
    String paymentMethod,
    String shippingAddress
) {
    public PlaceOrderRequest {
        items = List.copyOf(items);
    }
}
