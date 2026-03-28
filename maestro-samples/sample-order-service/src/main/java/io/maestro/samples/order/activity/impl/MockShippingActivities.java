package io.maestro.samples.order.activity.impl;

import io.maestro.samples.order.activity.ShippingActivities;
import io.maestro.samples.order.domain.ShipmentConfirmation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class MockShippingActivities implements ShippingActivities {

    private static final Logger log = LoggerFactory.getLogger(MockShippingActivities.class);

    @Override
    public ShipmentConfirmation createShipment(String orderId, String shippingAddress, String warehouseId) {
        var shipmentId = UUID.randomUUID().toString();
        var trackingNumber = "TRK-" + orderId.substring(0, 8).toUpperCase();

        log.info("Created shipment: shipmentId={}, trackingNumber={}, warehouseId={}",
            shipmentId, trackingNumber, warehouseId);

        return new ShipmentConfirmation(shipmentId, trackingNumber);
    }
}
