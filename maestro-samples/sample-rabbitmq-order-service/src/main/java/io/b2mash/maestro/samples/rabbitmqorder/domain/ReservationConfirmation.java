package io.b2mash.maestro.samples.rabbitmqorder.domain;

import java.math.BigDecimal;

public record ReservationConfirmation(String reservationId, BigDecimal total, String warehouseId) {}
