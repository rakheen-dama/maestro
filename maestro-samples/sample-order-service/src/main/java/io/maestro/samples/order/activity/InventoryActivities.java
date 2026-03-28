package io.maestro.samples.order.activity;

import io.maestro.core.annotation.Activity;
import io.maestro.core.annotation.Compensate;
import io.maestro.samples.order.domain.OrderItem;
import io.maestro.samples.order.domain.ReservationConfirmation;

import java.util.List;

@Activity
public interface InventoryActivities {

    @Compensate("releaseReservation")
    ReservationConfirmation reserve(List<OrderItem> items);

    void releaseReservation(ReservationConfirmation reservation);
}
