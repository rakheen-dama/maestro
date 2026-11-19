package io.b2mash.maestro.consumersmoke;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;

import java.time.Duration;

/**
 * Packs, sleeps durably (a timer row the timer poller fires), waits for the
 * {@value #DISPATCHED} signal that arrives through Kafka, then ships.
 */
@DurableWorkflow(name = "smoke-shipment")
public class ShipmentWorkflow {

    /** The signal {@link DispatchListener} delivers. */
    public static final String DISPATCHED = "dispatched";

    @ActivityStub(startToCloseTimeout = "PT1M")
    private ShipmentActivities activities;

    /**
     * @param orderId the order
     * @return the tracking reference
     */
    @WorkflowMethod
    public String run(String orderId) {
        var workflow = WorkflowContext.current();
        var parcel = activities.pack(orderId);
        workflow.sleep(Duration.ofMillis(200));
        var carrier = workflow.awaitSignal(DISPATCHED, String.class, Duration.ofMinutes(5));
        return activities.ship(parcel, carrier);
    }
}
