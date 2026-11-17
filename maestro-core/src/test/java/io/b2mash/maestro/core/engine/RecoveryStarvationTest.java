package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.BlockingWork;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.CountingStore;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SharedLock;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.WorkWorkflow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;

import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SERVICE;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.seedRecoverable;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.valueMatching;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.workMethod;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * WP6 D-2 / coordinator call C2: recovered runs are never starved by fresh
 * starts.
 */
class RecoveryStarvationTest {

    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private final CountingStore store = new CountingStore();
    private final SharedLock lock = new SharedLock();
    private final BlockingWork work = new BlockingWork();
    private WorkflowExecutor executor;

    @AfterEach
    void tearDown() {
        work.releaseAll();
        if (executor != null) {
            executor.shutdown();
        }
    }

    /**
     * C2: under a stream of fresh starts that keeps a waiter queued at every
     * moment, a bare "try for a free slot" gate admits nothing (fair
     * tryAcquire(0) refuses while anyone waits; planning probe: 0 of 1000).
     * Recovered runs must still complete: each takes its turn in the FIFO
     * like a fresh start submitted at the same instant.
     */
    @Test
    void recoveredRunsProgressUnderASustainedStreamOfFreshStarts() {
        var a = node(1); // one slot, one ticket
        for (var i = 0; i < 3; i++) {
            startFresh(a, "fresh-" + i); // 1 running, 2 waiting
        }
        for (var i = 0; i < 3; i++) {
            seedRecoverable(store, "rec-" + i, "orders");
        }
        work.completeAtOnceFor(id -> id.startsWith("rec-")); // recovered runs' activity returns at once
        a.startRecoveryPoller(registrations(a), Duration.ofHours(1), Duration.ofMillis(10));
        var fresh = 3;
        for (var step = 0; step < 300 && store.completed("rec-") < 3; step++) {
            startFresh(a, "fresh-" + fresh++); // two in, one out:
            startFresh(a, "fresh-" + fresh++); // the queue never empties
            work.release(1);
            var wanted = step + 1;
            valueMatching(work::finished, n -> n >= wanted, Duration.ofSeconds(5));
        }
        assertEquals(3, store.completed("rec-"),
                "recovered runs completed while fresh starts kept the queue non-empty");
    }

    // ── fixtures ──────────────────────────────────────────────────────

    private WorkflowExecutor node(int c) {
        executor = WorkflowExecutor.builder(store, serializer, SERVICE)
                .distributedLock(lock)
                .lifecycleEventsEnabled(false)
                .shutdownTimeout(Duration.ofSeconds(2))
                .defaultTaskQueueLimits(new TaskQueueLimits(c, c))
                .build();
        return executor;
    }

    private Map<String, WorkflowRegistration> registrations(WorkflowExecutor a) {
        var proxy = work.proxy(a, store, serializer, "A");
        return Map.of("WorkWorkflow", new WorkflowRegistration("WorkWorkflow", "orders",
                () -> new WorkWorkflow(proxy), workMethod()));
    }

    private void startFresh(WorkflowExecutor a, String workflowId) {
        var proxy = work.proxy(a, store, serializer, "A");
        a.startWorkflow(workflowId, "WorkWorkflow", "orders", null, () -> new WorkWorkflow(proxy), workMethod());
    }
}
