package io.b2mash.maestro.samples.rabbitmqorder.domain;

import java.util.List;

public record OrderInput(
    String orderId,
    String customerId,
    List<OrderItem> items,
    String paymentMethod,
    String shippingAddress
) {
    public OrderInput {
        items = List.copyOf(items);
    }
}
