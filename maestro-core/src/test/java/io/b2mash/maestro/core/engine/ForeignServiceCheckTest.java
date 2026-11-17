package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.exception.StoreUnavailableException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The startup service-name check: what it asks the store, the warning it
 * words, that it never fails its caller, and that
 * {@link WorkflowExecutor#startForeignServiceCheck} runs it on its own thread.
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
    void asksForThisServicesTypesUnderOtherNamesAndReturnsTheCounts() {
        store.answer(Map.of("orders-old", 3L));
        assertEquals(Map.of("orders-old", 3L), ForeignServiceCheck.run(store, "orders", Set.of("order")));
        assertEquals(new Asked("orders", Set.of("order"), ForeignServiceCheck.MAX_REPORTED + 1), store.asked());
    }

    @Test
    void theWarningNamesBothServiceNamesTheCountAndTheRemedy() {
        assertEquals("3 active workflow instance(s) of a type this node runs are recorded under maestro.service-name "
                        + "'orders-old', not 'orders'. Recovery adopts only 'orders' instances, so unless a node runs with "
                        + "maestro.service-name=orders-old these workflows never resume; if no node runs that name any "
                        + "more, start one under it or correct the rows (docs/operations.md §11.5).",
                ForeignServiceCheck.warning("orders", "orders-old", 3));
    }

    @Test
    void aStoreFailureIsSwallowed() {
        store.fail(new StoreUnavailableException("injected: the store is down",
                new IllegalStateException("connection refused")));
        assertEquals(Map.of(), assertDoesNotThrow(() -> ForeignServiceCheck.run(store, "orders", Set.of("order"))));
    }

    @Test
    void anErrorIsSwallowedButAControlFlowErrorIsNot() {
        store.fail(new AssertionError("injected"));
        assertEquals(Map.of(), assertDoesNotThrow(() -> ForeignServiceCheck.run(store, "orders", Set.of("order"))));

        store.fail(new ExecutorShutdownException("injected: the node is stopping"));
        assertThrows(ExecutorShutdownException.class, () -> ForeignServiceCheck.run(store, "orders", Set.of("order")));
    }

    @Test
    void noRegisteredTypesMakesNoStoreCall() {
        assertEquals(Map.of(), ForeignServiceCheck.run(store, "orders", Set.of()));
        assertNull(store.asked());
    }

    @Test
    void startForeignServiceCheckReturnsAtOnceAndQueriesOnItsOwnThread() throws Exception {
        store.blockUntilReleased();
        var executor = node();
        var registrations = registrations();
        var returned = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            executor.startForeignServiceCheck(registrations);
            returned.countDown();
        });
        assertTrue(returned.await(2, SECONDS), "startForeignServiceCheck returned while the read was blocked");
        assertTrue(store.entered.await(5, SECONDS), "the check read the store");
        store.release.countDown();
        assertEquals(ForeignServiceCheck.THREAD_NAME, store.threadName.get());
    }

    @Test
    void startForeignServiceCheckDuringShutdownDoesNothingAndDoesNotThrow() throws Exception {
        var executor = node();
        executor.shutdown();
        assertDoesNotThrow(() -> executor.startForeignServiceCheck(registrations()));
        // A started check reads at once; give one 500 ms to show itself.
        assertFalse(store.entered.await(500, MILLISECONDS), "a check read the store after shutdown");
        assertNull(store.asked());
    }

    /** A null map is a programming error, reported the same way whether or not the executor is stopping. */
    @Test
    void aNullRegistrationMapIsRejectedAlsoDuringShutdown() {
        var running = node();
        assertThrows(NullPointerException.class, () -> running.startForeignServiceCheck(null));
        var stopping = node();
        stopping.shutdown();
        assertThrows(NullPointerException.class, () -> stopping.startForeignServiceCheck(null));
    }

    // ── helpers ──────────────────────────────────────────────────────

    private record Asked(String serviceName, Set<String> workflowTypes, int limit) {
    }

    private WorkflowExecutor node() {
        var executor = WorkflowExecutor.builder(store, new PayloadSerializer(new ObjectMapper()), "orders")
                .lifecycleEventsEnabled(false)
                .shutdownTimeout(Duration.ofSeconds(2))
                .build();
        executors.add(executor);
        return executor;
    }

    private static Map<String, WorkflowRegistration> registrations() throws NoSuchMethodException {
        return Map.of("order", new WorkflowRegistration("order", "default", Object::new,
                Object.class.getMethod("toString")));
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

        @Nullable Asked asked() {
            return asked.get();
        }

        @Override
        public Map<String, Long> getActiveInstanceCountsUnderOtherServices(
                String serviceName, Set<String> workflowTypes, int limit) {
            asked.set(new Asked(serviceName, workflowTypes, limit));
            threadName.set(Thread.currentThread().getName());
            entered.countDown();
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
        }
    }
}
