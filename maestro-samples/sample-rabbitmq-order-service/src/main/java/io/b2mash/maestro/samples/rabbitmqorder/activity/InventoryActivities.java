package io.b2mash.maestro.samples.rabbitmqorder.activity;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.Compensate;
import io.b2mash.maestro.samples.rabbitmqorder.domain.OrderItem;
import io.b2mash.maestro.samples.rabbitmqorder.domain.ReservationConfirmation;

import java.util.List;

@Activity
public interface InventoryActivities {

    @Compensate("releaseReservation")
    ReservationConfirmation reserve(List<OrderItem> items);

    void releaseReservation(ReservationConfirmation reservation);
}
