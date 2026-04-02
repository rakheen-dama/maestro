package io.b2mash.maestro.samples.rabbitmqorder.domain;

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
