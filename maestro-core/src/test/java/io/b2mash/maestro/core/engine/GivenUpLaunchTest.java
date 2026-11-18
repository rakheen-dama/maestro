package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.CountingStore;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SharedLock;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SERVICE;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.seedRecoverable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pilot readiness P1, task 2 (Issue 70), the launch side: a launch admitted
 * before shutdown began but still taking its instance lock when the deadline
 * passes is given up with every other run, and then writes nothing — no
 * instance row for a start, no adoption compare-and-set (and no lease-epoch
 * bump) for a resume — and starts no run.
 */
@Timeout(60)
class GivenUpLaunchTest {

    private final CountingStore store = new CountingStore();
    private final GatedAcquireLock lock = new GatedAcquireLock();
    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private final WorkflowExecutor executor = WorkflowExecutor.builder(store, serializer, SERVICE)
            .lifecycleEventsEnabled(false)
            .distributedLock(lock)
            .shutdownTimeout(Duration.ofMillis(300))
            .build();

    @AfterEach
    void tearDown() {
        lock.open.countDown();
        executor.shutdown();
    }

    @Test
    void aStartStillTakingItsLockAtTheDeadlineWritesNoRowAndIsRefused() throws Exception {
        var thrown = new AtomicReference<@Nullable Throwable>();
        var launcher = Thread.ofVirtual().start(() -> {
            try {
                executor.startWorkflow("wf-start", "QuickWorkflow", "default", null, QuickWorkflow::new,
                        quickMethod());
            } catch (RuntimeException | Error e) {
                thrown.set(e);
            }
        });
        assertTrue(lock.inAcquire.await(10, TimeUnit.SECONDS), "the start is inside tryAcquire");

        executor.shutdown(); // hits its 300 ms deadline: the start is still in tryAcquire
        lock.open.countDown();

        assertTrue(launcher.join(Duration.ofSeconds(10)));
        assertInstanceOf(IllegalStateException.class, thrown.get(), String.valueOf(thrown.get()));
        assertTrue(store.getInstance("wf-start").isEmpty(), "a given-up start wrote its instance row");
        assertEquals(0, lock.delegate.heldCount(), "the given-up start released the lock it took");
        assertFalse(executor.isRunning("wf-start"));
    }

    @Test
    void aResumeStillTakingItsLockAtTheDeadlineDoesNotAdoptOrRun() throws Exception {
        var seeded = seedRecoverable(store, "wf-resume", "default");
        var launched = new AtomicReference<@Nullable Boolean>();
        var launcher = Thread.ofVirtual().start(() ->
                launched.set(executor.resumeWorkflow(seeded, QuickWorkflow::new, quickMethod())));
        assertTrue(lock.inAcquire.await(10, TimeUnit.SECONDS), "the resume is inside tryAcquire");

        executor.shutdown();
        lock.open.countDown();

        assertTrue(launcher.join(Duration.ofSeconds(10)));
        assertEquals(Boolean.FALSE, launched.get(), "a given-up resume must not launch");
        var row = store.getInstance("wf-resume").orElseThrow();
        assertEquals(seeded.version(), row.version(), "no adoption compare-and-set after the give-up");
        assertEquals(seeded.leaseEpoch(), row.leaseEpoch(), "no lease-epoch bump after the give-up");
        assertEquals(0, lock.delegate.heldCount(), "the given-up resume released the lock it took");
    }

    private static Method quickMethod() {
        try {
            return QuickWorkflow.class.getMethod("run");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Completes at once. */
    @DurableWorkflow(name = "QuickWorkflow")
    public static final class QuickWorkflow {
        /** @return a constant */
        @WorkflowMethod
        public String run() {
            return "done";
        }
    }

    /** A {@link SharedLock} whose first {@code tryAcquire} blocks until {@link #open} is counted down. */
    static final class GatedAcquireLock implements DistributedLock {
        final SharedLock delegate = new SharedLock();
        final CountDownLatch inAcquire = new CountDownLatch(1);
        final CountDownLatch open = new CountDownLatch(1);

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            if (inAcquire.getCount() > 0) {
                inAcquire.countDown();
                try {
                    open.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return delegate.tryAcquire(key, ttl);
        }

        @Override
        public void release(LockHandle handle) {
            delegate.release(handle);
        }

        @Override
        public boolean renew(LockHandle handle, Duration ttl) {
            return delegate.renew(handle, ttl);
        }

        @Override
        public Set<LockHandle> renewAll(Collection<LockHandle> handles, Duration ttl) {
            return delegate.renewAll(handles, ttl);
        }

        @Override
        public Set<String> heldKeys(Collection<String> keys) {
            return delegate.heldKeys(keys);
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return true;
        }
    }
}
