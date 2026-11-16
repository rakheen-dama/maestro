package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.TestTerminalWait;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run's end clears its wake permits before it releases its instance lock.
 *
 * <p>Releasing first left a gap in which a new run of the same workflow id
 * could take the lock, launch, and have a permit stored for it — which the old
 * run's {@code clearPending} then erased, so the new run parked for up to its
 * wake-recheck interval.
 */
class RunEndPermitOrderingTest {

    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private WorkflowExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    /**
     * Forces the gap with the lock backend as the hook: the old run's release
     * frees the lock and then waits, so the retried run launches and a signal's
     * wake stores its permit before the old run's bookkeeping after the
     * release resumes.
     */
    @Test
    void aRunEndingAfterItsLockIsReleased_leavesTheNextRunsPermit() throws Exception {
        var store = new VersionedInMemoryStore();
        var lock = new PausingReleaseLock();
        executor = WorkflowExecutor.builder(store, serializer, "svc")
                .distributedLock(lock)
                .instanceLockTtl(Duration.ofSeconds(30))
                .lifecycleEventsEnabled(false)
                .shutdownTimeout(Duration.ofSeconds(5))
                .build();
        var gate = new CountDownLatch(1);
        var secondRunStarted = new CountDownLatch(1);
        var runs = new AtomicInteger();
        var method = FailsThenAwaits.class.getMethod("run");
        var registration = new WorkflowRegistration("FailsThenAwaits", "default",
                () -> new FailsThenAwaits(runs.incrementAndGet(), secondRunStarted, gate), method);

        executor.startWorkflow("wf", "FailsThenAwaits", "default", null, registration.workflowFactory(), method);
        assertTrue(lock.paused.await(5, TimeUnit.SECONDS), "the first run's lock release did not pause");
        assertEquals(WorkflowStatus.FAILED, store.getInstance("wf").orElseThrow().status());

        // The first run's lock is free: a retry launches the second run.
        assertEquals(WorkflowExecutor.RetryOutcome.RETRIED, executor.retryWorkflow("wf", registration));
        assertTrue(secondRunStarted.await(5, TimeUnit.SECONDS), "the second run did not start");
        executor.deliverSignal("wf", "go", "x");
        assertEquals(1, executor.wakePermitCount(), "precondition: the signal stored a permit for the second run");

        // The first run's finally resumes after its release.
        lock.resume.countDown();
        var firstRun = lock.pausedThread.get();
        assertNotNull(firstRun);
        firstRun.join(Duration.ofSeconds(5));
        assertFalse(firstRun.isAlive(), "the first run's thread did not finish");

        assertEquals(1, executor.wakePermitCount(), "the first run's end erased the second run's permit");

        gate.countDown();
        TestTerminalWait.awaitStatus(store, "wf", WorkflowStatus.COMPLETED, Duration.ofSeconds(5));
    }

    /**
     * A map-backed lock whose first release on a workflow thread frees the
     * lock and then pauses until {@link #resume} opens.
     */
    static final class PausingReleaseLock implements DistributedLock {
        final ConcurrentHashMap<String, LockHandle> locks = new ConcurrentHashMap<>();
        final CountDownLatch paused = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);
        final AtomicReference<Thread> pausedThread = new AtomicReference<>();
        private final AtomicBoolean armed = new AtomicBoolean(true);

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            var handle = new LockHandle(key, UUID.randomUUID().toString(), Instant.now().plus(ttl));
            return locks.putIfAbsent(key, handle) != null ? Optional.empty() : Optional.of(handle);
        }

        @Override
        public void release(LockHandle handle) {
            locks.computeIfPresent(handle.key(), (_, current) ->
                    current.token().equals(handle.token()) ? null : current);
            if (Thread.currentThread().getName().startsWith("maestro-workflow-")
                    && armed.compareAndSet(true, false)) {
                pausedThread.set(Thread.currentThread());
                paused.countDown();
                try {
                    if (!resume.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("not resumed within 10 s");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public boolean renew(LockHandle handle, Duration ttl) {
            var current = locks.get(handle.key());
            return current != null && current.token().equals(handle.token());
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return false;
        }
    }

    /** The first run fails at once; the second waits on {@code gate}, then awaits "go". */
    @DurableWorkflow(name = "FailsThenAwaits")
    public static class FailsThenAwaits {
        private final int run;
        private final CountDownLatch started;
        private final CountDownLatch gate;

        FailsThenAwaits(int run, CountDownLatch started, CountDownLatch gate) {
            this.run = run;
            this.started = started;
            this.gate = gate;
        }

        @WorkflowMethod
        public String run() throws InterruptedException {
            if (run == 1) {
                throw new IllegalStateException("first run fails");
            }
            started.countDown();
            if (!gate.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("gate not opened");
            }
            return WorkflowContext.current().awaitSignal("go", String.class, Duration.ofHours(1));
        }
    }
}
