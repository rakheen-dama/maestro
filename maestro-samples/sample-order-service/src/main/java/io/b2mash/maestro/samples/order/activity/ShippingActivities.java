package io.b2mash.maestro.samples.order.activity;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.samples.order.domain.ShipmentConfirmation;

@Activity
public interface ShippingActivities {

    ShipmentConfirmation createShipment(String orderId, String shippingAddress, String warehouseId);
}
