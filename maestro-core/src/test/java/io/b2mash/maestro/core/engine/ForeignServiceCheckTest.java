package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.exception.ForeignServiceRowsException;
import io.b2mash.maestro.core.exception.StoreUnavailableException;
import io.b2mash.maestro.test.InMemoryDistributedLock;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The startup shared-table check (pilot readiness P3; Issues 39, 79): one
 * bounded read for active instances under any other {@code maestro.service-name},
 * whatever their workflow type. Under {@link ForeignServiceCheckMode#FAIL} (the
 * default) rows found fail startup; {@code WARN} logs them; {@code OFF} reads
 * nothing. A read that fails or does not answer within the bound is
 * inconclusive — logged, never fatal, so an unreachable store cannot become a
 * restart loop.
 */
class ForeignServiceCheckTest {

    private final AskingStore store = new AskingStore();
    private final List<WorkflowExecutor> executors = new ArrayList<>();

    @AfterEach
    void tearDown() {
        store.release.countDown();
        executors.forEach(WorkflowExecutor::shutdown);
    }

    @Test
    void asksForEveryOtherServiceNameWhateverTheWorkflowType() {
        store.answer(Map.of("orders-old", 3L));
        var result = ForeignServiceCheck.probe(store, "orders", Duration.ofSeconds(5));
        assertEquals(Map.of("orders-old", 3L), result.foreign());
        assertNull(result.inconclusive());
        assertEquals(new Asked("orders", ForeignServiceCheck.MAX_REPORTED + 1), store.asked());
        assertEquals(ForeignServiceCheck.THREAD_NAME, store.threadName.get(), "the read runs on its own thread");
    }

    @Test
    void underFailRowsOfAnotherServiceFailStartupNamingEveryNameAndTheEscapeHatch() {
        var counts = new LinkedHashMap<String, Long>();
        counts.put("billing", 7L);
        counts.put("orders-old", 3L);
        store.answer(counts);

        var e = assertThrows(ForeignServiceRowsException.class, () -> node().checkForeignServices(
                ForeignServiceCheckMode.FAIL));

        assertEquals(counts, e.counts());
        assertEquals(ForeignServiceCheck.failure("orders", counts), e.getMessage());
        assertTrue(e.getMessage().contains("'billing' (7)"), e.getMessage());
        assertTrue(e.getMessage().contains("'orders-old' (3)"), e.getMessage());
        assertTrue(e.getMessage().contains("maestro.store.foreign-service-check=warn"), e.getMessage());
    }

    @Test
    void underWarnRowsOfAnotherServiceAreReportedAndStartupContinues() {
        store.answer(Map.of("orders-old", 3L));
        assertDoesNotThrow(() -> node().checkForeignServices(ForeignServiceCheckMode.WARN));
        assertEquals(new Asked("orders", ForeignServiceCheck.MAX_REPORTED + 1), store.asked());
    }

    @Test
    void offReadsNothing() {
        store.answer(Map.of("orders-old", 3L));
        assertDoesNotThrow(() -> node().checkForeignServices(ForeignServiceCheckMode.OFF));
        assertNull(store.asked());
    }

    @Test
    void aCleanStorePasses() {
        assertDoesNotThrow(() -> node().checkForeignServices(ForeignServiceCheckMode.FAIL));
        assertEquals(new Asked("orders", ForeignServiceCheck.MAX_REPORTED + 1), store.asked());
    }

    @Test
    void theWarningNamesBothServiceNamesTheCountAndTheRemedy() {
        var warning = ForeignServiceCheck.warning("orders", "orders-old", 3);
        assertTrue(warning.startsWith("3 active workflow instance(s) are recorded under maestro.service-name "
                + "'orders-old', not 'orders'"), warning);
        assertTrue(warning.contains("docs/operations.md §11.5"), warning);
    }

    @Test
    void aStoreFailureIsInconclusiveAndNeverFatal() {
        store.fail(new StoreUnavailableException("injected: the store is down",
                new IllegalStateException("connection refused")));
        var result = ForeignServiceCheck.probe(store, "orders", Duration.ofSeconds(5));
        assertEquals(Map.of(), result.foreign());
        assertTrue(result.inconclusive() != null && result.inconclusive().contains("the store is down"),
                String.valueOf(result.inconclusive()));
        assertDoesNotThrow(() -> node().checkForeignServices(ForeignServiceCheckMode.FAIL));
    }

    @Test
    void anErrorIsInconclusiveButAControlFlowErrorIsRethrown() {
        store.fail(new AssertionError("injected"));
        assertTrue(ForeignServiceCheck.probe(store, "orders", Duration.ofSeconds(5)).inconclusive() != null);

        store.fail(new ExecutorShutdownException("injected: the node is stopping"));
        assertThrows(ExecutorShutdownException.class,
                () -> ForeignServiceCheck.probe(store, "orders", Duration.ofSeconds(5)));
    }

    @Test
    void aStoreThatDoesNotAnswerWithinTheBoundIsInconclusiveAndStartupGoesOn() throws Exception {
        store.blockUntilReleased();
        var executor = node();
        var started = System.nanoTime();
        assertDoesNotThrow(() -> executor.checkForeignServices(ForeignServiceCheckMode.FAIL, Duration.ofMillis(200)));
        var tookMs = (System.nanoTime() - started) / 1_000_000;
        assertTrue(store.entered.await(5, SECONDS), "the check read the store");
        assertTrue(tookMs < 2_000, "the check waited " + tookMs + " ms for a read bounded at 200 ms");
        assertTrue(store.stillBlocked(), "the read was not interrupted: it is left to finish on its own thread");
    }

    @Test
    void duringShutdownTheCheckReadsNothing() {
        var executor = node();
        executor.shutdown();
        store.answer(Map.of("orders-old", 3L));
        assertDoesNotThrow(() -> executor.checkForeignServices(ForeignServiceCheckMode.FAIL));
        assertNull(store.asked());
    }

    @Test
    void aNullModeIsRejected() {
        assertThrows(NullPointerException.class, () -> node().checkForeignServices(null));
    }

    // ── helpers ──────────────────────────────────────────────────────

    private record Asked(String serviceName, int limit) {
    }

    private WorkflowExecutor node() {
        var executor = WorkflowExecutor.builder(store, new PayloadSerializer(new ObjectMapper()), "orders")
                .distributedLock(new InMemoryDistributedLock())
                .lifecycleEventsEnabled(false)
                .shutdownTimeout(Duration.ofSeconds(2))
                .build();
        executors.add(executor);
        return executor;
    }

    /**
     * Answers the service-name read with a scripted map or throwable, and
     * records its arguments and the reading thread's name; it can block the
     * read until {@code release}.
     */
    private static final class AskingStore extends VersionedInMemoryStore {

        private final AtomicReference<@Nullable Asked> asked = new AtomicReference<>();
        private final AtomicReference<@Nullable String> threadName = new AtomicReference<>();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch returned = new CountDownLatch(1);
        private volatile Map<String, Long> answer = Map.of();
        private volatile @Nullable Throwable failure;
        private volatile boolean block;

        void answer(Map<String, Long> counts) {
            this.answer = counts;
        }

        void fail(Throwable throwable) {
            this.failure = throwable;
        }

        void blockUntilReleased() {
            this.block = true;
        }

        boolean stillBlocked() {
            return returned.getCount() == 1;
        }

        @Nullable Asked asked() {
            return asked.get();
        }

        @Override
        public Map<String, Long> getActiveInstanceCountsUnderOtherServices(String serviceName, int limit) {
            asked.set(new Asked(serviceName, limit));
            threadName.set(Thread.currentThread().getName());
            entered.countDown();
            try {
                if (block) {
                    try {
                        release.await(30, SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                var thrown = failure;
                if (thrown instanceof RuntimeException e) {
                    throw e;
                }
                if (thrown instanceof Error e) {
                    throw e;
                }
                return answer;
            } finally {
                returned.countDown();
            }
        }
    }
}
