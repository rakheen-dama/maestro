package io.b2mash.maestro.messaging.postgres;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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
    @DisplayName("a signal published immediately after subscribe is still delivered")
    void publishImmediatelyAfterSubscribe_isDelivered() {
        try (var notifier = new PostgresSignalNotifier(dataSource, notificationListener)) {
            var workflowId = "wf-immediate-" + unique();
            var woken = new ConcurrentLinkedQueue<String>();

            // The contract callers rely on: once subscribe() has returned, the
            // subscription is live. SignalManager re-checks the store straight
            // after subscribing precisely to close the check→subscribe race —
            // that guard is worthless if the LISTEN has not been applied yet,
            // because a signal delivered on another node after the re-check
            // and before the LISTEN lands is lost until the 30s store
            // re-check. One publish, no retry loop.
            notifier.subscribe(workflowId, (wfId, signalName) -> woken.add(signalName));
            notifier.publish(workflowId, "approval");

            await().atMost(BOUND).pollInterval(Duration.ofMillis(50))
                    .until(() -> !woken.isEmpty());
            assertEquals("approval", woken.peek());
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
     * Publishes once and waits for the wake.
     *
     * <p>A single publish is enough because {@code subscribe} does not return
     * until the {@code LISTEN} has actually been executed on the dedicated
     * connection. This used to re-publish in a loop to paper over an
     * asynchronous {@code LISTEN}; the loop hid the fact that a notification
     * sent in that window was lost outright, which is what
     * {@link #publishImmediatelyAfterSubscribe_isDelivered} now pins.
     *
     * @param notifier   the notifier under test
     * @param workflowId the workflow to notify
     * @param signalName the signal name to send
     * @param woken      the queue the callback appends to
     */
    private static void publishUntilWoken(PostgresSignalNotifier notifier, String workflowId,
                                          String signalName, ConcurrentLinkedQueue<String> woken) {
        notifier.publish(workflowId, signalName);
        await().atMost(BOUND).pollInterval(Duration.ofMillis(50)).until(() -> !woken.isEmpty());
    }

    @Test
    @DisplayName("publish returns promptly when a connection cannot be had — the NOTIFY is handed off (Issue 32)")
    void publishDoesNotWaitForAConnection() throws Exception {
        // A pool that cannot hand out a connection (exhausted, or Postgres
        // unreachable) parks getConnection() for its connection timeout.
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var stalled = (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        entered.countDown();
                        release.await(30, TimeUnit.SECONDS);
                        throw new SQLException("Connection is not available, request timed out");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        try (var notifier = new PostgresSignalNotifier(stalled, notificationListener)) {
            assertTimeoutPreemptively(Duration.ofSeconds(1),
                    () -> notifier.publish("wf-stalled-" + unique(), "approval"));
            assertTrue(entered.await(5, TimeUnit.SECONDS), "the NOTIFY must still be attempted");
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("a NOTIFY failure that never clears re-arms the outage WARN every FAILURE_WARN_INTERVAL (R58)")
    void persistentNotifyFailure_rewarnsEveryInterval() {
        var unreachable = (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        throw new SQLException("Connection refused");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        var nanos = new AtomicLong(1_000_000_000L);
        var logger = (Logger) LoggerFactory.getLogger(PostgresSignalNotifier.class);
        var previousLevel = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try (var notifier = new PostgresSignalNotifier(unreachable, notificationListener, nanos::get)) {
            var interval = PostgresSignalNotifier.FAILURE_WARN_INTERVAL.toNanos();
            // Each NOTIFY runs on its own thread: wait for its log line before moving the clock.
            publishAndAwaitFailureLine(notifier, appender, 1);    // new outage: WARN
            nanos.addAndGet(interval / 2);
            publishAndAwaitFailureLine(notifier, appender, 2);    // inside the interval: DEBUG
            nanos.addAndGet(interval / 2);
            publishAndAwaitFailureLine(notifier, appender, 3);    // interval elapsed: WARN again
            publishAndAwaitFailureLine(notifier, appender, 4);    // re-armed window: DEBUG

            assertEquals(List.of(Level.WARN, Level.DEBUG, Level.WARN, Level.DEBUG),
                    failureLines(appender).stream().map(ILoggingEvent::getLevel).toList());
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }
    }

    private static void publishAndAwaitFailureLine(PostgresSignalNotifier notifier,
                                                   ListAppender<ILoggingEvent> appender, int expected) {
        notifier.publish("wf-unreachable", "approval");
        // ListAppender's list is not thread-safe; a copy racing an append may throw — retry.
        await().atMost(Duration.ofSeconds(5)).ignoreExceptions()
                .until(() -> failureLines(appender).size() == expected);
    }

    private static List<ILoggingEvent> failureLines(ListAppender<ILoggingEvent> appender) {
        // Only this test's channel: another test's stalled NOTIFY may log late.
        return List.copyOf(appender.list).stream()
                .filter(e -> e.getFormattedMessage().startsWith("Failed to NOTIFY")
                        && e.getFormattedMessage().contains("unreachable"))
                .toList();
    }

    @Test
    @DisplayName("listen reports failure rather than claiming a subscription that was never applied")
    void listenReportsFailureWhenNotApplied() {
        // A listener that is already closed can never execute the LISTEN. The
        // caller must be told, not silently left believing it is subscribed —
        // that is the silent-lost-notification failure this class exists to
        // remove, and releasing the waiter is not the same as succeeding.
        var closed = new PostgresNotificationListener(newDataSource());
        closed.start();
        closed.close();

        var applied = closed.listen("maestro_signal_never_" + unique(), (ch, payload) -> { });

        assertFalse(applied, "listen must report that the LISTEN never reached the server");
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
