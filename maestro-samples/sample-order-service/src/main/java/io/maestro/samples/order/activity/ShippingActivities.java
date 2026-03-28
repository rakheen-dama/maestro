package io.maestro.samples.order.activity;

import io.maestro.core.annotation.Activity;
import io.maestro.samples.order.domain.ShipmentConfirmation;

@Activity
public interface ShippingActivities {

    ShipmentConfirmation createShipment(String orderId, String shippingAddress, String warehouseId);
}
