package io.b2mash.maestro.consumersmoke;

import io.b2mash.maestro.spring.annotation.MaestroSignalListener;
import io.b2mash.maestro.spring.annotation.SignalRouting;
import org.springframework.stereotype.Component;

/** Routes each record of {@value #TOPIC} to the {@code dispatched} signal of its order's shipment. */
@Component
public class DispatchListener {

    /** The domain topic; pre-created, as Maestro never creates topics. */
    public static final String TOPIC = "smoke.dispatches";

    /**
     * One domain record.
     *
     * @param orderId the order
     * @param carrier the carrier
     */
    public record Dispatch(String orderId, String carrier) {
    }

    /**
     * @param dispatch the record's value
     * @return the signal for the order's shipment workflow
     */
    @MaestroSignalListener(topic = TOPIC, signalName = ShipmentWorkflow.DISPATCHED)
    public SignalRouting route(Dispatch dispatch) {
        return SignalRouting.builder()
                .workflowId("shipment-" + dispatch.orderId())
                .payload(dispatch.carrier())
                .build();
    }
}
