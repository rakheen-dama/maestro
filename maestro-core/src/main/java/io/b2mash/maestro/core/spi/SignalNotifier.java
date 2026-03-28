package io.b2mash.maestro.core.spi;

/**
 * SPI for cross-instance signal notification.
 *
 * <p>When a signal is delivered to a workflow, the {@code SignalNotifier}
 * publishes a notification so that other service instances running that
 * workflow can wake it immediately rather than waiting for the next
 * poll/recovery cycle.
 *
 * <p>This is a <b>performance optimisation</b> — not a correctness
 * requirement. If the notifier is unavailable or the publish fails,
 * signals are still delivered via Postgres persistence and discovered
 * on replay/recovery.
 *
 * <h2>Thread Safety</h2>
 * <p>Implementations must be thread-safe. Multiple virtual threads may
 * call {@link #publish} concurrently, and callbacks from
 * {@link #subscribe} may fire on any thread.
 *
 * @see io.b2mash.maestro.core.engine.SignalManager
 */
public interface SignalNotifier {

    /**
     * Publishes a signal notification to all subscribers watching the
     * given workflow.
     *
     * <p>This is best-effort — failures are silently tolerated by the
     * engine. Implementations should log warnings but never throw.
     *
     * @param workflowId the target workflow's business ID
     * @param signalName the signal name
     */
    void publish(String workflowId, String signalName);

    /**
     * Subscribes to signal notifications for a workflow.
     *
     * <p>When a notification is received, the callback is invoked.
     * The callback may be invoked on any thread (e.g., a Netty event
     * loop thread) — it must be lightweight and non-blocking.
     *
     * <p>Only one callback per workflow ID is supported. A second
     * call with the same workflow ID replaces the previous callback.
     *
     * @param workflowId the workflow to watch
     * @param callback   invoked when a signal notification arrives
     */
    void subscribe(String workflowId, SignalCallback callback);

    /**
     * Unsubscribes from signal notifications for a workflow.
     *
     * <p>No-op if no subscription exists for the given workflow ID.
     *
     * @param workflowId the workflow to stop watching
     */
    void unsubscribe(String workflowId);

    /**
     * Callback invoked when a cross-instance signal notification arrives.
     */
    @FunctionalInterface
    interface SignalCallback {

        /**
         * Called when a signal notification is received.
         *
         * <p>Implementations must be lightweight and non-blocking —
         * this may run on a network event loop thread.
         *
         * @param workflowId the workflow that received a signal
         * @param signalName the signal name
         */
        void onSignal(String workflowId, String signalName);
    }
}
