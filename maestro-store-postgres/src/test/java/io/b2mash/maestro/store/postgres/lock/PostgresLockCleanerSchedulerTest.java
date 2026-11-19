package io.b2mash.maestro.store.postgres.lock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cleaner used to be a bean nothing called. With Postgres the only lock
 * backend, every crashed run leaves a row behind, so the scheduler must
 * actually run it.
 */
@DisplayName("PostgresLockCleanerScheduler")
class PostgresLockCleanerSchedulerTest extends PostgresLockTestSupport {

    @Test
    @DisplayName("an expired lock row is deleted within the configured interval")
    void expiredRowIsDeletedOnSchedule() throws SQLException {
        var key = "scheduler-test-" + System.nanoTime();
        insertExpiredLock(key);
        assertEquals(1, lockRowCount(key));

        try (var scheduler = new PostgresLockCleanerScheduler(
                new PostgresLockCleaner(dataSource), Duration.ofMillis(200))) {
            scheduler.start();
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50))
                    .until(() -> lockRowCount(key) == 0);
        }
    }

    @Test
    @DisplayName("a live lock row is left alone")
    void liveRowIsKept() throws SQLException {
        var handle = lock.tryAcquire("scheduler-live-" + System.nanoTime(), Duration.ofMinutes(5)).orElseThrow();
        try (var scheduler = new PostgresLockCleanerScheduler(
                new PostgresLockCleaner(dataSource), Duration.ofMillis(100))) {
            scheduler.start();
            await().during(Duration.ofMillis(600)).atMost(Duration.ofSeconds(5))
                    .until(() -> lockRowCount(handle.key()) == 1);
        } finally {
            lock.release(handle);
        }
    }

    @Test
    @DisplayName("a non-positive interval is rejected")
    void nonPositiveIntervalRejected() {
        var cleaner = new PostgresLockCleaner(dataSource);
        assertThrows(IllegalArgumentException.class,
                () -> new PostgresLockCleanerScheduler(cleaner, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new PostgresLockCleanerScheduler(cleaner, Duration.ofSeconds(-1)));
    }

    static List<Named<Supplier<Throwable>>> survivableErrors() {
        return List.of(
                Named.of("OutOfMemoryError", OutOfMemoryError::new),
                Named.of("StackOverflowError", StackOverflowError::new),
                Named.of("NoClassDefFoundError", () -> new NoClassDefFoundError("x")),
                Named.of("AssertionError", () -> new AssertionError("x")));
    }

    @ParameterizedTest
    @MethodSource("survivableErrors")
    @DisplayName("an Error from one run is contained and the next run happens (3.5b)")
    void anErrorFromARunIsContainedAndTheNextRunHappens(Supplier<Throwable> error) throws Exception {
        var faulty = new FaultyDataSource(dataSource, error);
        try (var scheduler = new PostgresLockCleanerScheduler(new PostgresLockCleaner(faulty), Duration.ofMillis(50))) {
            scheduler.start();
            assertTrue(faulty.secondRun.await(5, TimeUnit.SECONDS), "the cleaner made no second run");
        }
    }

    /**
     * Issue 65's done-when (P2 task 6): supervised by the executor, the
     * cleaner reports a contained {@code Error} to the engine's observer under
     * its loop name, which is what moves {@code maestro.loop.errors{loop=lock-cleaner}},
     * and it shows in {@link WorkflowExecutor#loops()}.
     */
    @Test
    @DisplayName("supervised by the executor, an Error from a run reaches the observer as loop 'lock-cleaner'")
    void anErrorMovesLoopErrors() throws Exception {
        var loopErrors = new CopyOnWriteArrayList<String>();
        var executor = WorkflowExecutor.builder(new InMemoryWorkflowStore(),
                        new PayloadSerializer(new ObjectMapper()), "svc")
                .distributedLock(new InMemoryDistributedLock())
                .lifecycleEventsEnabled(false)
                .observer(new EngineObserver() {
                    @Override
                    public void backgroundLoopError(String loop, String errorType) {
                        loopErrors.add(loop + " " + errorType);
                    }
                })
                .build();
        var faulty = new FaultyDataSource(dataSource, () -> new AssertionError("x"));
        try (var scheduler = new PostgresLockCleanerScheduler(new PostgresLockCleaner(faulty), Duration.ofMillis(50))) {
            scheduler.start(executor);
            assertTrue(faulty.secondRun.await(5, TimeUnit.SECONDS), "the cleaner made no second run");
            assertEquals(List.of("lock-cleaner java.lang.AssertionError"), loopErrors);
            assertTrue(executor.loops().stream().anyMatch(l -> l.name().equals("lock-cleaner")),
                    "the cleaner is one of the executor's loops: " + executor.loops());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("a control-flow Error ends the cleaner, and its end is logged through SLF4J")
    void aControlFlowErrorEndsTheCleanerAndIsLogged() {
        var faulty = new FaultyDataSource(dataSource, () -> new ExecutorShutdownException("test"));
        var log = (Logger) LoggerFactory.getLogger(PostgresLockCleanerScheduler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        log.addAppender(appender);
        try (var scheduler = new PostgresLockCleanerScheduler(new PostgresLockCleaner(faulty), Duration.ofMillis(50))) {
            scheduler.start();
            await("the cleaner thread to end").atMost(Duration.ofSeconds(5)).until(() -> !scheduler.isRunning());
            assertEquals(1, faulty.calls.get(), "the control-flow Error ended the loop; it made no second run");
            // The uncaught handler runs on the thread before it terminates.
            assertTrue(errors(appender).stream().anyMatch(m -> m.startsWith("Postgres lock cleaner stopped: ")),
                    "the cleaner's end was not logged through SLF4J: " + errors(appender));
        } finally {
            log.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("close() while a run swallows the interrupt and then throws ends the cleaner")
    void closeWhileARunSwallowsTheInterruptAndThrowsEndsTheCleaner() throws Exception {
        var entered = new CountDownLatch(1);
        var cleanerThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var swallowing = new FaultyDataSource(dataSource, () -> {
            cleanerThread.set(Thread.currentThread());
            entered.countDown();
            try {
                new CountDownLatch(1).await();                  // until close() interrupts
            } catch (InterruptedException swallowed) {
                // a driver that swallows the interrupt (flag cleared) and then fails
            }
            return new AssertionError("interrupted run");
        });
        var scheduler = new PostgresLockCleanerScheduler(new PostgresLockCleaner(swallowing), Duration.ofMillis(50));
        scheduler.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the cleaner never reached the data source");

        scheduler.close();

        assertTrue(cleanerThread.get().join(Duration.ofSeconds(4)), "the cleaner thread outlived close()");
        assertFalse(scheduler.isRunning());
    }

    private static List<String> errors(ListAppender<ILoggingEvent> appender) {
        synchronized (appender) {
            return appender.list.stream()
                    .filter(e -> e.getLevel() == Level.ERROR)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }
    }

    /**
     * Delegates to {@code delegate}, except that {@link #getConnection()}
     * throws {@code failure.get()} on call 1 and counts down
     * {@link #secondRun} on call 2.
     */
    static final class FaultyDataSource implements DataSource {

        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch secondRun = new CountDownLatch(1);
        private final DataSource delegate;
        private final Supplier<Throwable> failure;

        FaultyDataSource(DataSource delegate, Supplier<Throwable> failure) {
            this.delegate = delegate;
            this.failure = failure;
        }

        @Override
        public Connection getConnection() throws SQLException {
            var call = calls.incrementAndGet();
            if (call == 1) {
                throw FaultyDataSource.<RuntimeException>sneaky(failure.get());
            }
            if (call == 2) {
                secondRun.countDown();
            }
            return delegate.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return delegate.getConnection(username, password);
        }

        @Override
        public @Nullable PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(@Nullable PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            throw new UnsupportedOperationException("getParentLogger");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }

        @SuppressWarnings("unchecked")
        private static <T extends Throwable> T sneaky(Throwable t) throws T {
            throw (T) t;
        }
    }

    /** A row whose expiry is well past the cleaner's one-minute grace period. */
    private void insertExpiredLock(String key) throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "INSERT INTO maestro_distributed_lock (lock_key, token, expires_at) "
                             + "VALUES (?, 'dead-token', now() - interval '10 minutes')")) {
            stmt.setString(1, key);
            stmt.executeUpdate();
        }
    }
}
