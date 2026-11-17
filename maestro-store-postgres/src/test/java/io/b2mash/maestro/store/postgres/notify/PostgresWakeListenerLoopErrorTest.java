package io.b2mash.maestro.store.postgres.notify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP4 3.5b on the wake listener's poll loop, on a {@link ScriptedPgDataSource}
 * (no database): a {@code RuntimeException} or an {@code Error} from one poll
 * or one consumer no longer ends the loop or spins it — the loop waits one
 * second and polls again — while a {@code MaestroControlFlowError} still ends
 * it, and the end is logged through SLF4J. The connection is always released
 * with the thread's interrupt flag clear (Issue 38's mechanism), and the flag
 * is re-asserted afterwards.
 *
 * <h2>Thread Safety</h2>
 * <p>Per-test fixtures, confined to the test thread; {@link ScriptedPgDataSource}
 * is thread-safe and the log appender is read under its own monitor.
 */
@DisplayName("PostgresWakeListener's poll loop survives an Error and does not spin")
class PostgresWakeListenerLoopErrorTest {

    private final ScriptedPgDataSource scripted = new ScriptedPgDataSource();
    private final List<PostgresWakeListener> listeners = new ArrayList<>();
    private Logger listenerLogger;
    private ListAppender<ILoggingEvent> listenerLog;

    @BeforeEach
    void captureListenerLog() {
        listenerLogger = (Logger) LoggerFactory.getLogger(PostgresWakeListener.class);
        listenerLog = new ListAppender<>();
        listenerLog.start();
        listenerLogger.addAppender(listenerLog);
    }

    @AfterEach
    void tearDown() {
        scripted.releasePolls();
        listeners.forEach(PostgresWakeListener::close);
        listenerLogger.detachAppender(listenerLog);
        listenerLog.stop();
    }

    private PostgresWakeListener track(PostgresWakeListener listener) {
        listeners.add(listener);
        return listener;
    }

    @Test
    @DisplayName("a driver that keeps throwing a RuntimeException waits before the next poll instead of spinning")
    void aDriverThatKeepsThrowingARuntimeExceptionWaitsBeforeTheNextPoll() throws Exception {
        scripted.failPollsWith(() -> new IllegalStateException("driver bug"));
        var listener = track(scripted.newListener());
        listener.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> scripted.pollStartNanos().size() >= 2);
        var starts = scripted.pollStartNanos();
        var gap = Duration.ofNanos(starts.get(1) - starts.get(0));
        assertTrue(gap.compareTo(Duration.ofMillis(900)) >= 0, "gap between polls after a RuntimeException: " + gap);
    }

    @Test
    @DisplayName("an Error from a consumer does not end the loop; a later poll still dispatches")
    void anErrorFromAConsumerDoesNotEndTheLoop() throws Exception {
        var listener = track(scripted.newListener());
        var woken = new CountDownLatch(1);
        listener.register("wf-1", s -> { throw new AssertionError("consumer bug"); });
        listener.register("wf-2", s -> woken.countDown());
        listener.start();
        scripted.deliver("wf-1", "go");                      // returned by the next poll
        scripted.deliver("wf-2", "go");                      // returned by a later poll
        assertTrue(woken.await(5, TimeUnit.SECONDS), "the poll loop died on the consumer's Error");
    }

    /**
     * Review d3: one consumer's {@code Error} is contained per notification,
     * so the rest of the batch is still delivered, in the same poll, without
     * the loop's one-second pause.
     */
    @Test
    @DisplayName("an Error from one consumer does not drop or delay the rest of its notification batch")
    void anErrorFromOneConsumerDoesNotDropTheRestOfTheBatch() throws Exception {
        var listener = track(scripted.newListener());
        var thrownAt = new AtomicReference<Long>();
        var pollAtThrow = new AtomicReference<Integer>();
        var woken = new CountDownLatch(1);
        var wokenAt = new AtomicReference<Long>();
        var pollAtWake = new AtomicReference<Integer>();
        listener.register("wf-1", s -> {
            thrownAt.set(System.nanoTime());
            pollAtThrow.set(scripted.pollCalls());
            throw new AssertionError("consumer bug");
        });
        listener.register("wf-2", s -> {
            wokenAt.set(System.nanoTime());
            pollAtWake.set(scripted.pollCalls());
            woken.countDown();
        });
        scripted.deliverInOnePoll("go", "wf-1", "wf-2");
        listener.start();

        assertTrue(woken.await(5, TimeUnit.SECONDS), "the second wake of the batch was never delivered");
        assertEquals(pollAtThrow.get(), pollAtWake.get(), "both wakes are delivered by the same poll");
        var gap = Duration.ofNanos(wokenAt.get() - thrownAt.get());
        assertTrue(gap.compareTo(Duration.ofMillis(500)) < 0, "the second wake waited after the Error: " + gap);
        assertTrue(errors().stream().anyMatch(m -> m.contains("Wake consumer for workflow 'wf-1' threw")
                        && m.contains("java.lang.AssertionError: consumer bug")),
                "the consumer's Error is logged at ERROR: " + errors());
    }

    @Test
    @DisplayName("an Error from the driver is logged at ERROR, the loop waits a second, then polls and dispatches again")
    void anErrorFromTheDriverIsContainedAndTheLoopWaitsBeforeItPollsAgain() throws Exception {
        var listener = track(scripted.newListener());
        var woken = new CountDownLatch(1);
        listener.register("wf-1", s -> woken.countDown());
        Supplier<Exception> driverError = () -> {
            throw new NoClassDefFoundError("org/postgresql/Missing");
        };
        scripted.failNextPollWith(driverError);
        listener.start();
        scripted.deliver("wf-1", "go");

        assertTrue(woken.await(5, TimeUnit.SECONDS), "the poll loop died on the driver's Error");
        var starts = scripted.pollStartNanos();
        var gap = Duration.ofNanos(starts.get(1) - starts.get(0));
        assertTrue(gap.compareTo(Duration.ofMillis(900)) >= 0, "gap between polls after an Error: " + gap);
        assertTrue(errors().stream().anyMatch(m -> m.contains("java.lang.NoClassDefFoundError")),
                "the contained Error is logged at ERROR: " + errors());
        assertTrue(listener.isConnected());
    }

    @Test
    @DisplayName("close() during a failing streak ends the loop at once, not after its one-second wait")
    void closeDuringAFailingStreakEndsTheLoopPromptly() {
        scripted.failPollsWith(() -> new IllegalStateException("driver bug"));
        var listener = track(scripted.newListener());
        listener.start();
        await("the first failed poll").atMost(Duration.ofSeconds(5)).until(() -> scripted.pollCalls() >= 1);

        var started = System.nanoTime();
        listener.close();
        await("the connection to be released").atMost(Duration.ofSeconds(5))
                .until(() -> scripted.events().contains("close"));
        var elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertTrue(elapsed.compareTo(Duration.ofMillis(900)) < 0,
                "close() must end the one-second wait, not wait it out: took " + elapsed);
        var events = scripted.events();
        assertEquals(List.of("setNetworkTimeout 1000", "UNLISTEN *", "setNetworkTimeout 0", "close"),
                events.subList(events.lastIndexOf("poll") + 1, events.size()));
    }

    @Test
    @DisplayName("a control-flow Error from a consumer ends the loop, releases the connection and is logged at ERROR")
    void aControlFlowErrorFromAConsumerEndsTheLoopAndIsLogged() throws Exception {
        var listener = track(scripted.newListener());
        var pollThread = new AtomicReference<Thread>();
        listener.register("wf-1", s -> {
            pollThread.set(Thread.currentThread());
            throw new ExecutorShutdownException("test");
        });
        listener.start();
        scripted.deliver("wf-1", "go");
        await("the consumer to run").atMost(Duration.ofSeconds(5)).until(() -> pollThread.get() != null);

        assertTrue(pollThread.get().join(Duration.ofSeconds(4)), "the control-flow Error did not end the poll loop");
        var events = scripted.events();
        assertEquals(List.of("setNetworkTimeout 1000", "UNLISTEN *", "setNetworkTimeout 0", "close"),
                events.subList(events.lastIndexOf("poll") + 1, events.size()), "the connection is still released");
        assertFalse(listener.isConnected());
        assertTrue(errors().stream().anyMatch(m -> m.contains("stopped: io.b2mash.maestro.core.exception."
                        + "ExecutorShutdownException: test escaped it")),
                "the poll thread's end is logged through SLF4J: " + errors());
    }

    /**
     * WP10 hand-off: an interrupt that reaches the poll thread must not be set
     * while the connection is released — an interrupted virtual thread
     * entering a socket read closes the socket under the pool (Issue 38).
     */
    @Test
    @DisplayName("an interrupt on the poll thread is cleared while the connection is released, then re-asserted")
    void anInterruptOnThePollThreadIsClearedWhileTheConnectionIsReleased() throws Exception {
        var spy = new InterruptSpyDataSource(scripted);
        var listener = track(new PostgresWakeListener(spy, ScriptedPgDataSource.CHANNEL, Duration.ofMillis(300)));
        var pollThread = new AtomicReference<Thread>();
        var inConsumer = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        listener.register("wf-1", s -> {
            pollThread.set(Thread.currentThread());
            inConsumer.countDown();
            awaitPreservingInterrupt(release);
        });
        listener.start();
        scripted.deliver("wf-1", "go");
        assertTrue(inConsumer.await(5, TimeUnit.SECONDS), "the wake never reached the consumer");

        pollThread.get().interrupt();                         // an interrupt delivered to the poll thread
        listener.close();                                     // returns after its 300 ms join: the consumer still runs
        release.countDown();                                  // the consumer returns, flag set; the loop ends

        assertTrue(pollThread.get().join(Duration.ofSeconds(4)), "the poll thread never ended");
        assertEquals(List.of(), spy.interruptedCalls(), "connection calls made with the interrupt flag set");
        var events = scripted.events();
        assertEquals(List.of("setNetworkTimeout 1000", "UNLISTEN *", "setNetworkTimeout 0", "close"),
                events.subList(events.lastIndexOf("poll") + 1, events.size()));
        assertFalse(events.contains("poll-interrupted"));
        assertTrue(pollThread.get().isInterrupted(), "the interrupt is re-asserted after the release");
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    /** Waits for {@code latch}; an interrupt during the wait is remembered and re-asserted on return. */
    private static void awaitPreservingInterrupt(CountDownLatch latch) {
        var interrupted = false;
        while (true) {
            try {
                if (latch.await(10, TimeUnit.SECONDS)) {
                    break;
                }
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** @return the formatted messages {@link PostgresWakeListener} logged at {@code ERROR} */
    private List<String> errors() {
        synchronized (listenerLog) {
            return listenerLog.list.stream()
                    .filter(e -> e.getLevel() == Level.ERROR)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }
    }

    /**
     * Wraps the scripted connection and records every statement execution or
     * connection close made while the calling thread's interrupt flag is set.
     * Such a statement fails as pgjdbc's socket read would on an interrupted
     * virtual thread: the socket is closed and the call throws.
     */
    static final class InterruptSpyDataSource implements DataSource {

        private final DataSource delegate;
        private final List<String> interruptedCalls = new CopyOnWriteArrayList<>();

        InterruptSpyDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        List<String> interruptedCalls() {
            return List.copyOf(interruptedCalls);
        }

        @Override
        public Connection getConnection() throws SQLException {
            var target = delegate.getConnection();
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                    (self, method, args) -> {
                        if (method.getName().equals("close") && Thread.currentThread().isInterrupted()) {
                            interruptedCalls.add("close");
                        }
                        var result = invoke(method, target, args);
                        if (method.getName().equals("createStatement")) {
                            return spyOn((Statement) result);
                        }
                        return result;
                    });
        }

        private Statement spyOn(Statement target) {
            return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Statement.class},
                    (self, method, args) -> {
                        if (method.getName().equals("execute") && Thread.currentThread().isInterrupted()) {
                            interruptedCalls.add("execute " + args[0]);
                            throw new PSQLException("An I/O error occurred while sending to the backend.",
                                    PSQLState.CONNECTION_FAILURE);
                        }
                        return invoke(method, target, args);
                    });
        }

        private static @Nullable Object invoke(java.lang.reflect.Method method, Object target,
                                               @Nullable Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        @Override
        public Connection getConnection(String username, String password) {
            throw new UnsupportedOperationException("getConnection(user, password)");
        }

        @Override
        public PrintWriter getLogWriter() {
            throw new UnsupportedOperationException("getLogWriter");
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            throw new UnsupportedOperationException("setLogWriter");
        }

        @Override
        public void setLoginTimeout(int seconds) {
            throw new UnsupportedOperationException("setLoginTimeout");
        }

        @Override
        public int getLoginTimeout() {
            throw new UnsupportedOperationException("getLoginTimeout");
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            throw new UnsupportedOperationException("getParentLogger");
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException("DataSource.unwrap");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            throw new UnsupportedOperationException("DataSource.isWrapperFor");
        }
    }
}
