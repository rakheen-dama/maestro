package io.b2mash.maestro.store.postgres.notify;

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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link PostgresSignalNotifier} — the cross-node wake for every
 * Postgres deployment — against a real PostgreSQL backend.
 *
 * <p>Without this notifier a parked workflow only learns about a signal on the
 * store re-check interval, so a broken notifier degrades latency silently
 * rather than failing loudly.
 *
 * <p>{@code NOTIFY} is observed by a background listener thread, so every
 * positive assertion waits with Awaitility; every negative one waits long
 * enough that a misrouted notification would have arrived.
 */
@DisplayName("PostgresSignalNotifier LISTEN/NOTIFY wake")
class PostgresSignalNotifierTest extends PostgresNotifyTestSupport {

    private static final Duration BOUND = Duration.ofSeconds(20);

    /** How long a wake that must NOT arrive is given to prove its absence. */
    private static final Duration QUIET = Duration.ofSeconds(2);

    @Test
    @DisplayName("a subscriber is woken when a signal is published for its workflow")
    void subscriberIsWokenForItsWorkflow() {
        try (var notifier = newNotifier()) {
            var workflowId = "wf-notify-" + unique();
            var woken = new ConcurrentLinkedQueue<String>();
            notifier.subscribe(workflowId, (wfId, signalName) -> woken.add(signalName));

            publishAndAwaitWake(notifier, workflowId, "approval", woken);

            assertEquals("approval", woken.peek(),
                    "the callback must receive the signal name that woke it");
        }
    }

    @Test
    @DisplayName("a signal published immediately after subscribe is still delivered")
    void publishImmediatelyAfterSubscribe_isDelivered() {
        try (var notifier = newNotifier()) {
            var workflowId = "wf-immediate-" + unique();
            var woken = new ConcurrentLinkedQueue<String>();

            // The contract callers rely on: once subscribe() has returned, the
            // subscription is live. SignalManager re-checks the store straight
            // after subscribing precisely to close the check→subscribe race.
            // Here subscribe is a map put on a session that has been LISTENing
            // since start(), so one publish, no retry loop.
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
        try (var notifier = newNotifier()) {
            var mine = "wf-mine-" + unique();
            var theirs = "wf-theirs-" + unique();
            var woken = new ConcurrentLinkedQueue<String>();
            notifier.subscribe(mine, (wfId, signalName) -> woken.add(signalName));

            notifier.publish(theirs, "approval");

            await().during(QUIET).atMost(Duration.ofSeconds(8)).until(woken::isEmpty);
        }
    }

    @Test
    @DisplayName("after unsubscribing, no further wake-ups are delivered")
    void unsubscribeStopsWakeups() {
        try (var notifier = newNotifier()) {
            var workflowId = "wf-unsub-" + unique();
            var woken = new ConcurrentLinkedQueue<String>();
            notifier.subscribe(workflowId, (wfId, signalName) -> woken.add(signalName));

            publishAndAwaitWake(notifier, workflowId, "first", woken);

            notifier.unsubscribe(workflowId);
            woken.clear();
            notifier.publish(workflowId, "second");

            await().during(QUIET).atMost(Duration.ofSeconds(8)).until(woken::isEmpty);
        }
    }

    @Test
    @DisplayName("a signal name containing a quote is delivered intact, not treated as SQL")
    void payloadWithQuoteIsEscaped() {
        try (var notifier = newNotifier()) {
            var workflowId = "wf-escape-" + unique();
            var woken = new ConcurrentLinkedQueue<String>();
            notifier.subscribe(workflowId, (wfId, signalName) -> woken.add(signalName));

            var awkward = "it's-approved";
            publishAndAwaitWake(notifier, workflowId, awkward, woken);

            assertEquals(awkward, woken.peek(),
                    "a quoted signal name must survive the NOTIFY payload intact");
        }
    }

    @Test
    @DisplayName("publishing with no subscriber is harmless")
    void publishWithoutSubscriberIsHarmless() {
        try (var notifier = newNotifier()) {
            assertDoesNotThrow(() -> notifier.publish("wf-nobody-" + unique(), "approval"),
                    "publishing with no subscriber must not throw");
        }
    }

    // ── the two defects of the channel-per-workflow design, pinned ──────

    @Test
    @DisplayName("a 300-character workflow id is delivered (channel-per-workflow truncated at 63 bytes and never matched)")
    void longWorkflowIdIsDelivered() {
        try (var notifier = newNotifier()) {
            var workflowId = "wf-long-" + unique() + "-" + "x".repeat(300 - 17);
            assertEquals(300, workflowId.length());
            var woken = new ConcurrentLinkedQueue<String>();
            notifier.subscribe(workflowId, (wfId, signalName) -> woken.add(wfId + "|" + signalName));

            notifier.publish(workflowId, "approval");

            await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50))
                    .until(() -> !woken.isEmpty());
            assertEquals(workflowId + "|approval", woken.peek(),
                    "the full id must come back, not a truncated or sanitised one");
        }
    }

    @Test
    @DisplayName("ids differing only by case or punctuation do not wake each other (sanitising used to collide them)")
    void idsDifferingOnlyByCaseOrPunctuationDoNotCrossWake() {
        try (var notifier = newNotifier()) {
            var suffix = unique();
            var subscribed = "Order-1-" + suffix;
            var lookalike = "order_1_" + suffix;
            var wokenSubscribed = new ConcurrentLinkedQueue<String>();
            var wokenLookalike = new ConcurrentLinkedQueue<String>();
            notifier.subscribe(subscribed, (wfId, signalName) -> wokenSubscribed.add(signalName));
            notifier.subscribe(lookalike, (wfId, signalName) -> wokenLookalike.add(signalName));

            notifier.publish(lookalike, "approval");

            await().atMost(BOUND).pollInterval(Duration.ofMillis(50)).until(() -> !wokenLookalike.isEmpty());
            await().during(QUIET).atMost(Duration.ofSeconds(8)).until(wokenSubscribed::isEmpty);
            assertEquals(List.of("approval"), List.copyOf(wokenLookalike));
        }
    }

    @Test
    @DisplayName("a wake published on another service's channel is ignored")
    void otherServiceChannelIsIgnored() {
        var otherChannel = WakeChannel.channelFor("some-other-service-" + unique());
        try (var otherListener = new PostgresWakeListener(newDataSource(), otherChannel)) {
            otherListener.start();
            var workflowId = "wf-shared-id-" + unique();
            var wokenHere = new ConcurrentLinkedQueue<String>();
            var wokenThere = new ConcurrentLinkedQueue<String>();
            try (var mine = newNotifier();
                 var theirs = new PostgresSignalNotifier(newDataSource(), otherListener, otherChannel)) {
                mine.subscribe(workflowId, (wfId, signalName) -> wokenHere.add(signalName));
                theirs.subscribe(workflowId, (wfId, signalName) -> wokenThere.add(signalName));

                theirs.publish(workflowId, "approval");

                await().atMost(BOUND).pollInterval(Duration.ofMillis(50)).until(() -> !wokenThere.isEmpty());
                await().during(QUIET).atMost(Duration.ofSeconds(8)).until(wokenHere::isEmpty);
            }
        }
    }

    @Test
    @DisplayName("subscribing on a closed listener does not throw — the store re-read covers it")
    void subscribeOnClosedListenerDoesNotThrow() {
        var closed = new PostgresWakeListener(newDataSource(), WakeChannel.channelFor("closed-" + unique()));
        closed.start();
        closed.close();
        try (var notifier = new PostgresSignalNotifier(newDataSource(), closed, closed.channel())) {
            assertDoesNotThrow(() -> notifier.subscribe("wf-after-close", (wfId, s) -> { }));
            assertDoesNotThrow(() -> notifier.publish("wf-after-close", "approval"));
            assertDoesNotThrow(() -> notifier.unsubscribe("wf-after-close"));
        }
    }

    /**
     * Publishes once and waits for the wake.
     *
     * <p>A single publish is enough because {@code subscribe} is an in-memory
     * registration on a session that has been LISTENing since
     * {@code start()}; a retry loop here would hide a lost notification.
     */
    private static void publishAndAwaitWake(PostgresSignalNotifier notifier, String workflowId,
                                            String signalName, ConcurrentLinkedQueue<String> woken) {
        notifier.publish(workflowId, signalName);
        await().atMost(BOUND).pollInterval(Duration.ofMillis(50)).until(() -> !woken.isEmpty());
    }

    @Test
    @DisplayName("publish delivers even when the pool hands out auto-commit=false connections")
    void publishDeliversOnAutoCommitFalsePool() {
        // Hibernate-style pools set hikari.auto-commit=false. Without an explicit
        // setAutoCommit(true) the NOTIFY sits in an open transaction that the session
        // discards on close, and every wake silently degrades to the recheck interval.
        DataSource autoCommitOff = (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    var result = method.invoke(dataSource, args);
                    if ("getConnection".equals(method.getName()) && result instanceof java.sql.Connection conn) {
                        conn.setAutoCommit(false);
                    }
                    return result;
                });
        try (var notifier = new PostgresSignalNotifier(autoCommitOff, listener, channel)) {
            var workflowId = "wf-autocommit-" + unique();
            var woken = new ConcurrentLinkedQueue<String>();
            notifier.subscribe(workflowId, (wfId, signalName) -> woken.add(signalName));

            publishAndAwaitWake(notifier, workflowId, "approval", woken);
        }
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
        try (var notifier = new PostgresSignalNotifier(stalled, listener, channel)) {
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
        var unreachable = new FaultInjectingDataSource(newDataSource());
        unreachable.arm();
        var nanos = new AtomicLong(1_000_000_000L);
        var logger = (Logger) LoggerFactory.getLogger(PostgresSignalNotifier.class);
        var previousLevel = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try (var notifier = new PostgresSignalNotifier(unreachable, listener, channel, nanos::get)) {
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
            assertEquals(4, unreachable.failedConnections(), "every NOTIFY must have been attempted");

            // Recovery: the first success after the outage logs one INFO.
            unreachable.disarm();
            var woken = new ConcurrentLinkedQueue<String>();
            notifier.subscribe("wf-unreachable", (wfId, signalName) -> woken.add(signalName));
            notifier.publish("wf-unreachable", "approval");
            await().atMost(BOUND).until(() -> !woken.isEmpty());
            await().atMost(Duration.ofSeconds(5)).ignoreExceptions().until(() -> List.copyOf(appender.list).stream()
                    .anyMatch(e -> e.getLevel() == Level.INFO
                            && e.getFormattedMessage().contains("being delivered again")));
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
        // Only this test's workflow: another test's stalled NOTIFY may log late.
        return List.copyOf(appender.list).stream()
                .filter(e -> e.getFormattedMessage().startsWith("Failed to NOTIFY")
                        && e.getFormattedMessage().contains("wf-unreachable"))
                .toList();
    }
}
