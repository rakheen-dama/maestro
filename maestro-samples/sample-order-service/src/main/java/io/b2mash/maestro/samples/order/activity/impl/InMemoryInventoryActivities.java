package io.b2mash.maestro.samples.order.activity.impl;

import io.b2mash.maestro.samples.order.activity.InventoryActivities;
import io.b2mash.maestro.samples.order.domain.OrderItem;
import io.b2mash.maestro.samples.order.domain.ReservationConfirmation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryInventoryActivities implements InventoryActivities {

    private static final Logger log = LoggerFactory.getLogger(InMemoryInventoryActivities.class);

    private final ConcurrentHashMap<String, ReservationConfirmation> reservations = new ConcurrentHashMap<>();

    @Override
    public ReservationConfirmation reserve(List<OrderItem> items) {
        var reservationId = UUID.randomUUID().toString();
        var total = items.stream()
            .map(item -> item.price().multiply(BigDecimal.valueOf(item.quantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        var warehouseId = "WH-EAST-1";

        var reservation = new ReservationConfirmation(reservationId, total, warehouseId);
        reservations.put(reservationId, reservation);

        log.info("Reserved inventory: reservationId={}, total={}, warehouseId={}",
            reservationId, total, warehouseId);

        return reservation;
    }

    @Override
    public void releaseReservation(ReservationConfirmation reservation) {
        reservations.remove(reservation.reservationId());
        log.info("Released inventory reservation: reservationId={}", reservation.reservationId());
    }
}
