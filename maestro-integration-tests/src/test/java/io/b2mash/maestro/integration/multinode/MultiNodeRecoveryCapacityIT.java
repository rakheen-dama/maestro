package io.b2mash.maestro.integration.multinode;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.engine.TaskQueueLimits;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.CountingActivities.ChainActivities;
import io.b2mash.maestro.store.postgres.lock.PostgresDistributedLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two nodes of one service, on a real Postgres lock table, share one recovery
 * backlog (Issue 30). With a task queue bounded at 2, a node's recovery poller
 * locks only what it can run or queue — 2 running plus 2 queued — and leaves
 * the rest unlocked, so the peer adopts its share and both nodes execute runs.
 * As runs finish, each node adopts more as its capacity frees, until the
 * backlog is drained.
 *
 * <p>The backlog is seeded as {@code RUNNING} rows with no owner, as a
 * restarted fleet finds the runs of nodes that died. The one activity each
 * run calls blocks until the test opens it, so the lock counts are read while
 * nothing has finished.
 */
@Tag("integration")
@DisplayName("Two nodes share one recovery backlog; neither locks more than it can run or queue")
class MultiNodeRecoveryCapacityIT extends PostgresIntegrationSupport {

    private static final String SERVICE = "multinode-recovery-capacity";
    private static final String QUEUE = "orders";
    private static final int BACKLOG = 12;

    private final Semaphore gate = new Semaphore(0);
    private volatile boolean open;
    private final CountingActivities.Recorder recorderA = new CountingActivities.Recorder();
    private final CountingActivities.Recorder recorderB = new CountingActivities.Recorder();
    private final List<String> seeded = new ArrayList<>();

    private MaestroEngineHarness nodeA;
    private MaestroEngineHarness nodeB;

    @AfterEach
    void releaseAndClose() {
        releaseAll();
        if (nodeB != null) {
            nodeB.close();
        }
        if (nodeA != null) {
            nodeA.close();
        }
    }

    @Test
    @DisplayName("with concurrency 2, a 12-run backlog is executed by both nodes and neither locks more than 4")
    void aBacklogIsSharedAndNoNodeHoards() throws Exception {
        seed(BACKLOG);
        nodeA = node(recorderA);
        nodeB = node(recorderB);

        nodeA.startRecoveryPoller(Duration.ofHours(1));
        assertEquals(4, valueWithin(this::lockRows, 4, Duration.ofSeconds(10)), "A locked 2 running + 2 queued");
        assertEquals(8, valueWithin(nodeA.executor()::recoveryDeferredCount, 8, Duration.ofSeconds(10)),
                "A's first pass ended, deferring the other 8");
        assertEquals(4, lockRows(), "and A locked nothing more");

        nodeB.startRecoveryPoller(Duration.ofHours(1));
        assertEquals(8, valueWithin(this::lockRows, 8, Duration.ofSeconds(10)), "B took 4 of the remaining 8");
        assertEquals(4, valueWithin(nodeB.executor()::recoveryDeferredCount, 4, Duration.ofSeconds(10)),
                "B's first pass ended, deferring the 4 nobody holds");
        assertEquals(8, lockRows(), "and B locked nothing more");

        releaseAll();
        assertEquals(BACKLOG, valueWithin(this::completed, BACKLOG, Duration.ofSeconds(60)),
                "the whole backlog completed, adopted as capacity freed, with full passes an hour apart");
        assertAll(
                () -> assertTrue(recorderA.count("work") >= 2, "A executed runs"),
                () -> assertTrue(recorderB.count("work") >= 2, "B executed runs"),
                () -> assertEquals(BACKLOG, recorderA.count("work") + recorderB.count("work"),
                        "each run executed once"));
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private MaestroEngineHarness node(CountingActivities.Recorder recorder) {
        var harness = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName(SERVICE)
                .lock(new PostgresDistributedLock(newDataSource()))
                .taskQueueLimits(Map.of(QUEUE, new TaskQueueLimits(2, 2)))
                .build();
        harness.registerActivities(ChainActivities.class, new BlockingWork(recorder));
        harness.registerWorkflow(OrdersWorkflow.class);
        return harness;
    }

    /** Seeds {@code n} unowned {@code RUNNING} runs on {@link #QUEUE}, 1 ms apart (the recovery order). */
    private void seed(int n) {
        var base = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(60);
        for (var i = 0; i < n; i++) {
            var workflowId = MaestroEngineHarness.uniqueWorkflowId("backlog-" + i);
            var startedAt = base.plusMillis(i);
            store.createInstance(WorkflowInstance.builder()
                    .id(UUID.randomUUID())
                    .workflowId(workflowId)
                    .runId(UUID.randomUUID())
                    .workflowType("RecoveryCapacityOrders")
                    .taskQueue(QUEUE)
                    .status(WorkflowStatus.RUNNING)
                    .serviceName(SERVICE)
                    .eventSequence(0)
                    .startedAt(startedAt)
                    .updatedAt(startedAt)
                    .version(0)
                    .build());
            seeded.add(workflowId);
        }
    }

    private void releaseAll() {
        open = true;
        gate.release(10_000);
    }

    /** Live instance-lock rows, over both nodes. */
    private int lockRows() {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("SELECT count(*) FROM maestro_distributed_lock "
                     + "WHERE lock_key LIKE 'maestro:lock:workflow:%' AND expires_at > now()");
             var rs = stmt.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private int completed() {
        return (int) seeded.stream()
                .map(store::getInstance)
                .filter(i -> i.orElseThrow().status() == WorkflowStatus.COMPLETED)
                .count();
    }

    /** Polls {@code read} until it equals {@code wanted} or {@code within} elapses; returns the last value read. */
    private static <T> T valueWithin(Supplier<T> read, T wanted, Duration within) {
        var deadline = System.nanoTime() + within.toNanos();
        var value = read.get();
        while (!wanted.equals(value) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20); // a poll interval, not a wait for a condition
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return value;
            }
            value = read.get();
        }
        return value;
    }

    /** Records each call as {@code "work"} on its node's recorder, then blocks until the test opens the gate. */
    private final class BlockingWork implements ChainActivities {
        private final CountingActivities.Recorder recorder;

        private BlockingWork(CountingActivities.Recorder recorder) {
            this.recorder = recorder;
        }

        @Override
        public String stepOne(String input) {
            recorder.record("work");
            if (!open) {
                gate.acquireUninterruptibly();
            }
            return input + "-done";
        }

        @Override
        public String stepTwo(String input) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public String stepThree(String input) {
            throw new UnsupportedOperationException("not used");
        }
    }

    /** One blocking activity call on the bounded {@code orders} queue. */
    @DurableWorkflow(name = "RecoveryCapacityOrders", taskQueue = QUEUE)
    public static class OrdersWorkflow {

        @ActivityStub
        ChainActivities activities;

        /** @return the activity's result */
        @WorkflowMethod
        public String run() {
            return activities.stepOne(WorkflowContext.current().workflowId());
        }
    }
}
