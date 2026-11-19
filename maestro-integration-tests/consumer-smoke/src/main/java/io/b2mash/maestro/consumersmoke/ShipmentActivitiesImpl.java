package io.b2mash.maestro.consumersmoke;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records every call, so the test can count live executions. Thread-safe. */
@Component
public class ShipmentActivitiesImpl implements ShipmentActivities {

    private final List<String> calls = new CopyOnWriteArrayList<>();

    @Override
    public String pack(String orderId) {
        calls.add("pack:" + orderId);
        return "parcel-" + orderId;
    }

    @Override
    public String ship(String parcel, String carrier) {
        calls.add("ship:" + parcel);
        return carrier + ":" + parcel;
    }

    /** @return every call so far, in order */
    public List<String> calls() {
        return List.copyOf(calls);
    }
}
