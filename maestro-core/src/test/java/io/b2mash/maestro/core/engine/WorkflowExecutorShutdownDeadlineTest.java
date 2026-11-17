package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.engine.WorkflowInstanceLockManagerCloseTest.BlockingRenewLock;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP5: {@link WorkflowExecutor#shutdown()} against one deadline, and what each
 * of its steps leaves behind.
 */
@DisplayName("WorkflowExecutor shutdown deadline")
@Timeout(60)
class WorkflowExecutorShutdownDeadlineTest {

    private final AtomicInteger started = new AtomicInteger();
    private final CountDownLatch latch = new CountDownLatch(1);
    private final RecordingEngineObserver recorder = new RecordingEngineObserver();

    private VersionedInMemoryStore store;
    private PayloadSerializer serializer;
    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        store = new VersionedInMemoryStore();
        serializer = new PayloadSerializer(JsonMapper.builder().build());
    }

    @AfterEach
    void tearDown() {
        latch.countDown();
        if (executor != null) {
            executor.shutdown();
        }
    }

    /** Issue 64 (R4): the renewer is not interrupted inside {@code renewAll}, so nothing is counted. */
    @Test
    void aShutdownDuringARenewalPassCountsNoRenewalFailure() throws Exception {
        var lock = new BlockingRenewLock();
        executor = WorkflowExecutor.builder(store, serializer, "node-a")
                .lifecycleEventsEnabled(false)
                .instanceLockTtl(Duration.ofMillis(300))
                .shutdownTimeout(Duration.ofMillis(300))
                .distributedLock(lock)
                .observer(recorder)
                .build();
        lock.blockNextRenew();
        start("wf-overrun", new BlockingWorkflow(proxy()));
        await().atMost(5, SECONDS).until(() -> started.get() == 1);
        assertTrue(lock.inRenew.await(5, SECONDS));

        executor.shutdown();
        lock.release.countDown();
        lock.awaitBlockedRenewerEnded();

        assertEquals(List.of(), recorder.lockRenewFailed());
        assertFalse(lock.renewInterrupted.get());
    }

    // ── helpers ────────────────────────────────────────────────────────

    private BlockingActivities proxy() {
        return new ActivityProxyFactory().createProxy(
                BlockingActivities.class, new BlockingActivitiesImpl(), store, null, null,
                RetryPolicy.noRetry(), Duration.ofSeconds(30), serializer, new RetryExecutor(),
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, recorder,
                executor.taskQueueGovernor());
    }

    private void start(String workflowId, Object workflow) throws Exception {
        var method = workflow.getClass().getMethod("run");
        executor.startWorkflow(workflowId, workflow.getClass().getSimpleName(), "default", null,
                () -> workflow, method);
    }

    // ── fixture ────────────────────────────────────────────────────────

    /** The activity a run overruns the drain in. */
    @Activity(name = "blocking")
    public interface BlockingActivities {
        /** @return a marker once the latch opens */
        String work();
    }

    /** Counts each live entry, then blocks until the test opens the latch. */
    private final class BlockingActivitiesImpl implements BlockingActivities {
        @Override
        public String work() {
            started.incrementAndGet();
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return "done";
        }
    }

    /** One blocking activity. */
    @DurableWorkflow(name = "BlockingWorkflow")
    public static final class BlockingWorkflow {
        private final BlockingActivities activities;

        BlockingWorkflow(BlockingActivities activities) {
            this.activities = activities;
        }

        /** @return the activity's result */
        @WorkflowMethod
        public String run() {
            return activities.work();
        }
    }
}
