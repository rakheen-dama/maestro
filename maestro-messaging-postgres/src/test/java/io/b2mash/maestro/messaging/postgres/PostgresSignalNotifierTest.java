package io.b2mash.maestro.messaging.postgres;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link PostgresSignalNotifier} — the cross-instance wake mechanism
 * for the Postgres-only profile — against a real PostgreSQL backend.
 *
 * <p>Without this notifier a parked workflow only learns about a signal on the
 * store re-check interval, so a broken notifier degrades latency silently
 * rather than failing loudly. Nothing exercised it before.
 *
 * <p>{@code NOTIFY} is delivered on transaction commit and observed by a
 * background listener thread, so every assertion waits with Awaitility.
 */
@DisplayName("PostgresSignalNotifier LISTEN/NOTIFY wake")
class PostgresSignalNotifierTest extends PostgresMessagingTestSupport {

    private static final Duration BOUND = Duration.ofSeconds(20);

    @Test
    @DisplayName("a subscriber is woken when a signal is published for its workflow")
    void subscriberIsWokenForItsWorkflow() {
        try (var notifier = new PostgresSignalNotifier(dataSource, notificationListener)) {
            var workflowId = "wf-notify-" + unique();
            var woken = new ConcurrentLinkedQueue<String>();
            notifier.subscribe(workflowId, (wfId, signalName) -> woken.add(signalName));

            publishUntilWoken(notifier, workflowId, "approval", woken);

            assertEquals("approval", woken.peek(),
                    "the callback must receive the signal name that woke it");
        }
    }

    @Test
    @DisplayName("a subscriber is not woken by a signal for a different workflow")
    void subscriberIgnoresOtherWorkflows() {
        try (var notifier = new PostgresSignalNotifier(dataSource, notificationListener)) {
            var mine = "wf-mine-" + unique();
            var theirs = "wf-theirs-" + unique();
            var woken = new ConcurrentLinkedQueue<String>();
            notifier.subscribe(mine, (wfId, signalName) -> woken.add(signalName));

            notifier.publish(theirs, "approval");

            // Wait long enough that a misrouted notification would have arrived.
            await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(8))
                    .until(woken::isEmpty);
        }
    }

    @Test
    @DisplayName("after unsubscribing, no further wake-ups are delivered")
    void unsubscribeStopsWakeups() {
        try (var notifier = new PostgresSignalNotifier(dataSource, notificationListener)) {
            var workflowId = "wf-unsub-" + unique();
            var woken = new ConcurrentLinkedQueue<String>();
            notifier.subscribe(workflowId, (wfId, signalName) -> woken.add(signalName));

            publishUntilWoken(notifier, workflowId, "first", woken);

            notifier.unsubscribe(workflowId);
            woken.clear();
            notifier.publish(workflowId, "second");

            await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(8))
                    .until(woken::isEmpty);
        }
    }

    @Test
    @DisplayName("a signal name containing a quote is delivered intact, not treated as SQL")
    void payloadWithQuoteIsEscaped() {
        try (var notifier = new PostgresSignalNotifier(dataSource, notificationListener)) {
            var workflowId = "wf-escape-" + unique();
            var woken = new ConcurrentLinkedQueue<String>();
            notifier.subscribe(workflowId, (wfId, signalName) -> woken.add(signalName));

            var awkward = "it's-approved";
            publishUntilWoken(notifier, workflowId, awkward, woken);

            assertEquals(awkward, woken.peek(),
                    "a quoted signal name must survive the NOTIFY payload intact");
        }
    }

    @Test
    @DisplayName("publishing with no subscriber is harmless")
    void publishWithoutSubscriberIsHarmless() {
        try (var notifier = new PostgresSignalNotifier(dataSource, notificationListener)) {
            notifier.publish("wf-nobody-" + unique(), "approval");
            assertTrue(true, "publishing to an unlistened channel must not throw");
        }
    }

    /**
     * Publishes repeatedly until the subscriber is woken.
     *
     * <p>{@code subscribe} only queues the {@code LISTEN}; it is executed later
     * on the listener's polling thread. Postgres delivers a {@code NOTIFY} only
     * to sessions that are already listening, so a notification published
     * immediately after subscribing can legitimately be missed. Production code
     * is not exposed to this: the engine re-checks the store after subscribing.
     * Re-publishing keeps the test honest about the wake actually being
     * delivered, without encoding the polling interval.
     *
     * @param notifier   the notifier under test
     * @param workflowId the workflow to notify
     * @param signalName the signal name to send
     * @param woken      the queue the callback appends to
     */
    private static void publishUntilWoken(PostgresSignalNotifier notifier, String workflowId,
                                          String signalName, ConcurrentLinkedQueue<String> woken) {
        await().atMost(BOUND).pollInterval(Duration.ofMillis(200)).until(() -> {
            notifier.publish(workflowId, signalName);
            return !woken.isEmpty();
        });
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
