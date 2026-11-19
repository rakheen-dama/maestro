package io.b2mash.maestro.store.postgres.notify;

import org.jspecify.annotations.Nullable;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.postgresql.core.Notification;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * A {@link DataSource} with no database behind it: one scripted
 * {@link Connection}, its {@link Statement}s and its {@link PGConnection},
 * all {@link Proxy}s that record what the {@link PostgresWakeListener} does to
 * them, in order and with the thread that did it.
 *
 * <p>Recorded events: the SQL of every {@code Statement.execute} (or
 * {@code "<sql> failed: closed"} once the connection is broken; under
 * {@link #unlistenTimesOut} also {@code "UNLISTEN * timed out"} or
 * {@code "UNLISTEN * interrupted"} after it),
 * {@code "setNetworkTimeout <ms>"} ({@code getNetworkTimeout} returns the last
 * value set, initially 0, and is not recorded), {@code "close"}, {@code "poll"} for every
 * {@code getNotifications(int)}, and {@code "poll-interrupted"} when a poll's
 * wait is interrupted — which, as an interrupt does to a virtual thread blocked
 * in a socket read, breaks the connection. Any method not scripted here throws
 * {@link UnsupportedOperationException}, so an unexpected call fails loudly.
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe: the event lists are guarded by {@code this}, the switches
 * are volatile or atomic, and every accessor returns a copy.
 */
final class ScriptedPgDataSource implements DataSource {

    static final String CHANNEL = "maestro_signal_wake_scripted_0000000";

    private final List<String> events = new ArrayList<>();
    private final List<String> eventThreads = new ArrayList<>();
    private final List<Long> pollStartNanos = new ArrayList<>();
    private final AtomicInteger pollCalls = new AtomicInteger();
    private final AtomicInteger refusedConnections = new AtomicInteger();
    private final BlockingQueue<PGNotification> notifications = new LinkedBlockingQueue<>();
    private final BlockingQueue<PGNotification[]> batches = new LinkedBlockingQueue<>();
    private final AtomicReference<@Nullable Supplier<? extends Exception>> nextPollFailure = new AtomicReference<>();
    private final Object pollGate = new Object();
    private final Object connectGate = new Object();
    private boolean blockConnections;
    private boolean inBlockedConnect;

    private volatile int networkTimeoutMillis;
    private volatile boolean refuseConnections;
    private volatile boolean broken;
    private volatile boolean blockPolls;
    private volatile boolean unlistenTimesOut;
    private volatile boolean inBlockedPoll;
    private volatile @Nullable Supplier<? extends Exception> everyPollFailure;

    private final PGConnection pgConnection = proxy(PGConnection.class, this::onPgConnection);
    private final Connection connection = proxy(Connection.class, this::onConnection);

    /** @return a listener on {@link #CHANNEL} over this data source, joining its poll thread for 300 ms on close */
    PostgresWakeListener newListener() {
        return new PostgresWakeListener(this, CHANNEL, Duration.ofMillis(300));
    }

    /** From now on {@link #getConnection()} blocks until {@link #releaseConnections()} — an exhausted pool. */
    void blockConnections() {
        synchronized (connectGate) {
            blockConnections = true;
        }
    }

    /** Lets a blocked {@link #getConnection()} go; later borrows do not block. */
    void releaseConnections() {
        synchronized (connectGate) {
            blockConnections = false;
            connectGate.notifyAll();
        }
    }

    /** @return whether a {@link #getConnection()} is blocked by {@link #blockConnections()} right now */
    boolean inBlockedConnect() {
        synchronized (connectGate) {
            return inBlockedConnect;
        }
    }

    /** While {@code refuse}, {@link #getConnection()} throws {@code SQLException("refused (scripted)")}. */
    void refuseConnections(boolean refuse) {
        refuseConnections = refuse;
    }

    /** Queues one notification on {@link #CHANNEL}; a poll takes at most one. */
    void deliver(String workflowId, String signalName) {
        notifications.add(new Notification(CHANNEL, 0, WakeChannel.encode(workflowId, signalName)));
    }

    /**
     * Queues one batch: the next poll returns all of these notifications at
     * once, one per workflow id, each for {@code signalName}, ahead of any
     * single notification queued by {@link #deliver}.
     */
    void deliverInOnePoll(String signalName, String... workflowIds) {
        var batch = new PGNotification[workflowIds.length];
        for (int i = 0; i < workflowIds.length; i++) {
            batch[i] = new Notification(CHANNEL, 0, WakeChannel.encode(workflowIds[i], signalName));
        }
        batches.add(batch);
    }

    /** Every poll from now on throws what {@code failure} supplies. */
    void failPollsWith(Supplier<? extends Exception> failure) {
        everyPollFailure = failure;
    }

    /** The next poll throws what {@code failure} supplies — evaluated after a blocked poll is released. */
    void failNextPollWith(Supplier<? extends Exception> failure) {
        nextPollFailure.set(failure);
    }

    /**
     * While {@code timesOut}, {@code UNLISTEN *} is recorded, then blocks for
     * the connection's network timeout and throws, recording
     * {@code "UNLISTEN * timed out"} — a half-open connection.
     */
    void unlistenTimesOut(boolean timesOut) {
        unlistenTimesOut = timesOut;
    }

    /** Polls from now on block, ignoring interrupts, until {@link #releasePolls()} — a stuck driver call. */
    void blockPolls() {
        blockPolls = true;
    }

    /** Lets a blocked poll go; later polls do not block. */
    void releasePolls() {
        synchronized (pollGate) {
            blockPolls = false;
            pollGate.notifyAll();
        }
    }

    /** @return whether a poll is blocked by {@link #blockPolls()} right now */
    boolean inBlockedPoll() {
        return inBlockedPoll;
    }

    /** @return a copy of the recorded events, in order */
    synchronized List<String> events() {
        return List.copyOf(events);
    }

    /** @return a copy of the thread name for each recorded event, same index as {@link #events()} */
    synchronized List<String> eventThreads() {
        return List.copyOf(eventThreads);
    }

    /** @return a copy of the {@link System#nanoTime()} at the start of each poll */
    synchronized List<Long> pollStartNanos() {
        return List.copyOf(pollStartNanos);
    }

    /** @return how many times {@code getNotifications(int)} was called */
    int pollCalls() {
        return pollCalls.get();
    }

    /** @return how many {@link #getConnection()} calls were refused by {@link #refuseConnections(boolean)} */
    int refusedConnections() {
        return refusedConnections.get();
    }

    // ── DataSource ──────────────────────────────────────────────────────

    @Override
    public Connection getConnection() throws SQLException {
        synchronized (connectGate) {
            while (blockConnections) {
                inBlockedConnect = true;
                try {
                    connectGate.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("interrupted while borrowing (scripted)");
                } finally {
                    inBlockedConnect = false;
                }
            }
        }
        if (refuseConnections) {
            refusedConnections.incrementAndGet();
            throw new SQLException("refused (scripted)");
        }
        return connection;
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
    public Logger getParentLogger() {
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

    // ── Scripted proxies ────────────────────────────────────────────────

    private synchronized void record(String event) {
        events.add(event);
        eventThreads.add(Thread.currentThread().getName());
    }

    private @Nullable Object onConnection(Method method, @Nullable Object[] args) {
        return switch (method.getName()) {
            case "setAutoCommit" -> null;
            case "createStatement" -> {
                if (args != null && args.length > 0) {
                    throw unscripted(method);
                }
                yield proxy(Statement.class, this::onStatement);
            }
            case "unwrap" -> {
                if (args[0] != PGConnection.class) {
                    throw unscripted(method);
                }
                yield pgConnection;
            }
            case "isWrapperFor" -> true;
            case "getNetworkTimeout" -> networkTimeoutMillis;
            case "setNetworkTimeout" -> {
                networkTimeoutMillis = (Integer) args[1];
                record("setNetworkTimeout " + args[1]);
                yield null;
            }
            case "close" -> {
                record("close");
                yield null;
            }
            default -> throw unscripted(method);
        };
    }

    private @Nullable Object onStatement(Method method, @Nullable Object[] args) throws SQLException {
        return switch (method.getName()) {
            case "execute" -> {
                if (args == null || args.length != 1) {
                    throw unscripted(method);
                }
                var sql = (String) args[0];
                if (broken) {
                    record(sql + " failed: closed");
                    throw new PSQLException("This connection has been closed.", PSQLState.CONNECTION_DOES_NOT_EXIST);
                }
                record(sql);
                if (unlistenTimesOut && sql.equals("UNLISTEN *")) {
                    awaitNetworkTimeout(sql);
                }
                yield false;
            }
            case "close" -> null;
            default -> throw unscripted(method);
        };
    }

    private @Nullable Object onPgConnection(Method method, @Nullable Object[] args) throws Exception {
        if (!method.getName().equals("getNotifications") || args == null || args.length != 1) {
            throw unscripted(method);
        }
        return poll((Integer) args[0]);
    }

    private PGNotification @Nullable [] poll(int timeoutMillis) throws Exception {
        synchronized (this) {
            record("poll");
            pollStartNanos.add(System.nanoTime());
        }
        pollCalls.incrementAndGet();
        awaitReleaseIgnoringInterrupts();
        var once = nextPollFailure.getAndSet(null);
        if (once != null) {
            throw once.get();
        }
        var every = everyPollFailure;
        if (every != null) {
            throw every.get();
        }
        var batch = batches.poll();
        if (batch != null) {
            return batch;
        }
        PGNotification notification;
        try {
            notification = notifications.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            record("poll-interrupted");
            broken = true;
            throw new PSQLException("An I/O error occurred while sending to the backend.", PSQLState.CONNECTION_FAILURE);
        }
        return notification == null ? null : new PGNotification[] {notification};
    }

    /**
     * A statement on a half-open connection: no reply comes, so it blocks for
     * the connection's network timeout (10 s if none is set — the real driver
     * would block for ever) and then fails as pgjdbc's read timeout does. An
     * interrupt during the wait breaks the connection.
     */
    private void awaitNetworkTimeout(String sql) throws SQLException {
        var timeout = networkTimeoutMillis > 0 ? networkTimeoutMillis : 10_000;
        try {
            Thread.sleep(timeout);
        } catch (InterruptedException e) {
            record(sql + " interrupted");
            broken = true;
            throw new PSQLException("An I/O error occurred while sending to the backend.", PSQLState.CONNECTION_FAILURE);
        }
        record(sql + " timed out");
        throw new PSQLException("Read timed out", PSQLState.CONNECTION_FAILURE);
    }

    private void awaitReleaseIgnoringInterrupts() {
        var interrupted = false;
        synchronized (pollGate) {
            if (blockPolls) {
                inBlockedPoll = true;
                while (blockPolls) {
                    try {
                        pollGate.wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                inBlockedPoll = false;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static UnsupportedOperationException unscripted(Method method) {
        return new UnsupportedOperationException("not scripted: " + method);
    }

    @FunctionalInterface
    private interface Handler {
        @Nullable Object handle(Method method, @Nullable Object[] args) throws Exception;
    }

    private static <T> T proxy(Class<T> type, Handler handler) {
        InvocationHandler invocation = (self, method, args) -> switch (method.getName()) {
            case "toString" -> "scripted " + type.getSimpleName();
            case "hashCode" -> System.identityHashCode(self);
            case "equals" -> self == args[0];
            default -> handler.handle(method, args);
        };
        return type.cast(Proxy.newProxyInstance(ScriptedPgDataSource.class.getClassLoader(), new Class<?>[] {type}, invocation));
    }
}
