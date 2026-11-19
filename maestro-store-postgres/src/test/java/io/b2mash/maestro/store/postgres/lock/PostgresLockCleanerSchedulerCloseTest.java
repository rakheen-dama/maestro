package io.b2mash.maestro.store.postgres.lock;

import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR #76 review 4629668590: a standalone cleaner (a host that runs the lock
 * without the engine — no executor joins it) waits, bounded, for a delete in
 * progress when its bean is destroyed, so the context does not close the data
 * source under it. A cleaner the executor owns returns at once: the executor's
 * shutdown joins it within its one deadline. Neither ever interrupts.
 *
 * <p>No database: the data source is a stub whose borrow blocks.
 *
 * <p><b>Thread safety:</b> per-test fixtures; the gates are latches.
 */
class PostgresLockCleanerSchedulerCloseTest {

    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicReference<Thread> cleanerThread = new AtomicReference<>();

    /** A data source whose borrow blocks until {@link #release}, then fails: one delete in progress. */
    private DataSource blocking() {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        cleanerThread.set(Thread.currentThread());
                        entered.countDown();
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new SQLException("never released (scripted)");
                        }
                        throw new SQLException("the delete ends (scripted)");
                    }
                    if (method.getName().equals("toString")) {
                        return "blocking data source";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void aStandaloneCleanerClosedDuringADeleteWaitsForIt() throws Exception {
        var scheduler = new PostgresLockCleanerScheduler(new PostgresLockCleaner(blocking()), Duration.ofMillis(20));
        scheduler.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the cleaner reached the data source");
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            release.countDown();
        });

        var started = System.nanoTime();
        scheduler.close();
        var took = Duration.ofNanos(System.nanoTime() - started);

        assertTrue(took.compareTo(Duration.ofMillis(250)) >= 0, "close() waited for the delete: " + took);
        assertTrue(cleanerThread.get().join(Duration.ofMillis(500)), "the cleaner ended with close()");
    }

    @Test
    void aCleanerTheExecutorOwnsDoesNotWaitOnClose() throws Exception {
        var executor = WorkflowExecutor.builder(new InMemoryWorkflowStore(), new PayloadSerializer(new ObjectMapper()),
                        "svc")
                .distributedLock(new InMemoryDistributedLock())
                .lifecycleEventsEnabled(false)
                .build();
        var scheduler = new PostgresLockCleanerScheduler(new PostgresLockCleaner(blocking()), Duration.ofMillis(20));
        try {
            scheduler.start(executor);
            assertTrue(entered.await(5, TimeUnit.SECONDS), "the cleaner reached the data source");

            var started = System.nanoTime();
            scheduler.close();
            var took = Duration.ofNanos(System.nanoTime() - started);

            assertTrue(took.compareTo(Duration.ofMillis(200)) < 0, "the executor's deadline owns the wait: " + took);
            assertFalse(scheduler.isRunning());
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
