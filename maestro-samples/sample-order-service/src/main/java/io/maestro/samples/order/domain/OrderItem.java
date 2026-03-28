package io.maestro.samples.order.domain;

import java.math.BigDecimal;

public record OrderItem(String sku, int quantity, BigDecimal price) {}
