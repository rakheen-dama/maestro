package io.b2mash.maestro.consumersmoke;

import io.b2mash.maestro.core.annotation.Activity;

/** The shipment's two steps. */
@Activity(name = "smoke-shipment")
public interface ShipmentActivities {

    /**
     * @param orderId the order
     * @return the parcel reference
     */
    String pack(String orderId);

    /**
     * @param parcel  {@link #pack}'s parcel reference
     * @param carrier the carrier the dispatch signal named
     * @return the tracking reference
     */
    String ship(String parcel, String carrier);
}
