package io.maestro.samples.order.domain;

import java.util.List;

public record OrderInput(
    String orderId,
    String customerId,
    List<OrderItem> items,
    String paymentMethod,
    String shippingAddress
) {}
