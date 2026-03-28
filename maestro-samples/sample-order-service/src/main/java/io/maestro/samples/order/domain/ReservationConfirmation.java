package io.maestro.samples.order.domain;

import java.math.BigDecimal;

public record ReservationConfirmation(String reservationId, BigDecimal total, String warehouseId) {}
