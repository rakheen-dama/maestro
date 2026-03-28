package io.maestro.test;

import io.maestro.core.spi.SignalNotifier;

/**
 * No-op {@link SignalNotifier} for tests.
 *
 * <p>In a single-JVM test environment, cross-instance signal notification
 * is not needed — the {@link io.maestro.core.engine.ParkingLot} handles
 * signal wake-up directly within the same process.
 */
public final class InMemorySignalNotifier implements SignalNotifier {

    @Override
    public void publish(String workflowId, String signalName) {
        // No-op: single-JVM, ParkingLot handles wake-up
    }

    @Override
    public void subscribe(String workflowId, SignalCallback callback) {
        // No-op: no cross-instance notification needed
    }

    @Override
    public void unsubscribe(String workflowId) {
        // No-op
    }
}
