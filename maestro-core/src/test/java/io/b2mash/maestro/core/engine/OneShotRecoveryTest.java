package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.BlockingWork;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.CountingStore;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SharedLock;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.WorkWorkflow;
import io.b2mash.maestro.core.exception.ExecutorShutdownException;
import io.b2mash.maestro.core.exception.StoreUnavailableException;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.spi.RecoveryCursor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SERVICE;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.seedRecoverable;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.valueWithin;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.workMethod;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one-shot startup recovery of a node whose recovery poller is disabled:
 * one ungated pass on its own thread, retried after a failure until one pass
 * completes, stopped by shutdown, ended by a control-flow {@code Error}.
 */
class OneShotRecoveryTest {

    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private final SharedLock lock = new SharedLock();
    private final BlockingWork work = new BlockingWork();
    private final List<WorkflowExecutor> nodes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        work.releaseAll();
        nodes.forEach(WorkflowExecutor::shutdown);
    }

    @Test
    void adoptsEveryInstanceWithoutACapacityCheck() {
        var store = new CountingStore();
        for (var i = 0; i < 4; i++) {
            seedRecoverable(store, "wf-" + i, "orders");
        }
        var a = node(store, 1);
        a.startOneShotRecovery(registrations(a, store), Duration.ofHours(1));
        assertAll(
                () -> assertEquals(4, valueWithin(lock::heldCount, 4, Duration.ofSeconds(5)),
                        "every instance locked although the queue runs one at a time"),
                () -> assertFalse(a.isRecoveryPollerRunning(), "the recovery poller's slot is not used"));
    }

    @Test
    void retriesAfterAFailureThenExits() {
        var store = new FailingStore(1);
        seedRecoverable(store, "wf-1", "orders");
        var a = node(store, 1);
        a.startOneShotRecovery(registrations(a, store), Duration.ofMillis(50));
        assertAll(
                () -> assertEquals(1, valueWithin(lock::heldCount, 1, Duration.ofSeconds(5)),
                        "the retried pass adopted the instance"),
                () -> assertEquals(2, store.reads.get(), "one failed read, one that succeeded"),
                () -> assertFalse(valueWithin(a::isOneShotRecoveryRunning, false, Duration.ofSeconds(5)),
                        "the thread ends once a pass completes"));
        assertThrows(IllegalStateException.class,
                () -> a.startOneShotRecovery(Map.of(), Duration.ofHours(1)), "it runs once per executor");
    }

    @Test
    void shutdownStopsARetryingPass() {
        var store = new FailingStore(Integer.MAX_VALUE);
        var a = node(store, 1);
        a.startOneShotRecovery(registrations(a, store), Duration.ofHours(1));
        assertEquals(1, valueWithin(store.reads::get, 1, Duration.ofSeconds(5)), "the first pass failed");
        assertTrue(a.isOneShotRecoveryRunning(), "waiting to retry");

        var start = System.nanoTime();
        a.shutdown();
        var tookMs = (System.nanoTime() - start) / 1_000_000;
        assertAll(
                () -> assertTrue(tookMs < 2_000, "shutdown() returned within 2 s, took " + tookMs + " ms"),
                () -> assertFalse(a.isOneShotRecoveryRunning()));
    }

    @Test
    void aControlFlowErrorEndsIt() {
        var store = new FailingStore(Integer.MAX_VALUE,
                () -> new ExecutorShutdownException("injected control-flow error"));
        var a = node(store, 1);
        a.startOneShotRecovery(registrations(a, store), Duration.ofMillis(10));
        assertFalse(valueWithin(a::isOneShotRecoveryRunning, false, Duration.ofSeconds(5)),
                "the thread ended");
        assertEquals(1, store.reads.get(), "no retry after a control-flow error");
    }

    @Test
    void aRetryIntervalThatIsNotPositiveIsRefused() {
        var a = node(new CountingStore(), 1);
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> a.startOneShotRecovery(Map.of(), Duration.ZERO)),
                () -> assertFalse(a.isOneShotRecoveryRunning()));
    }

    // ── helpers ──────────────────────────────────────────────────────

    /** Fails its first {@code failures} recovery-page reads with what {@code failure} supplies. */
    private static final class FailingStore extends CountingStore {
        final AtomicInteger reads = new AtomicInteger();
        private final int failures;
        private final Supplier<? extends Throwable> failure;

        FailingStore(int failures) {
            this(failures, () -> new StoreUnavailableException("injected: the store is down",
                    new IllegalStateException("connection refused")));
        }

        FailingStore(int failures, Supplier<? extends Throwable> failure) {
            this.failures = failures;
            this.failure = failure;
        }

        @Override
        public List<WorkflowInstance> getRecoverableInstances(String serviceName, @Nullable RecoveryCursor after,
                                                              int limit) {
            if (reads.incrementAndGet() <= failures) {
                switch (failure.get()) {
                    case RuntimeException e -> throw e;
                    case Error e -> throw e;
                    case Throwable t -> throw new IllegalStateException(t);
                }
            }
            return super.getRecoverableInstances(serviceName, after, limit);
        }
    }

    private WorkflowExecutor node(CountingStore store, int c) {
        var executor = WorkflowExecutor.builder(store, serializer, SERVICE)
                .distributedLock(lock)
                .lifecycleEventsEnabled(false)
                .observer(EngineObserver.NOOP)
                .defaultTaskQueueLimits(new TaskQueueLimits(c, c))
                .shutdownTimeout(Duration.ofSeconds(2))
                .build();
        nodes.add(executor);
        return executor;
    }

    private Map<String, WorkflowRegistration> registrations(WorkflowExecutor executor, CountingStore store) {
        var proxy = work.proxy(executor, store, serializer, "A");
        return Map.of("WorkWorkflow",
                new WorkflowRegistration("WorkWorkflow", "orders", () -> new WorkWorkflow(proxy), workMethod()));
    }
}
