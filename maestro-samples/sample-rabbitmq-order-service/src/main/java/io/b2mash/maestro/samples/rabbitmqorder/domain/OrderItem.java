package io.b2mash.maestro.samples.rabbitmqorder.domain;

import java.math.BigDecimal;

public record OrderItem(String sku, int quantity, BigDecimal price) {}
