package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.BlockingWork;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.CountingStore;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SharedLock;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.WorkWorkflow;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SERVICE;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.seedRecoverable;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.valueWithin;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.workMethod;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run executes only while this node holds its instance lock (pilot
 * readiness P3, Issue 74). A launch whose lock acquisition fails — the lock
 * backend throws, or the lock is held elsewhere — runs nothing: a fresh start
 * still persists its {@code RUNNING} row and leaves it to recovery, and a
 * resume is skipped. Recovery adopts the workflow once a pass can take the
 * lock. Before P3 such a launch proceeded unlocked.
 */
class MandatoryInstanceLockTest {

    private static final String KEY = WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX + "workflow:wf-1";

    private final CountingStore store = new CountingStore();
    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private final BlockingWork work = new BlockingWork();
    private final FlakyLock lock = new FlakyLock();
    private final WorkflowExecutor node = WorkflowExecutor.builder(store, serializer, SERVICE)
            .distributedLock(lock)
            .lifecycleEventsEnabled(false)
            .shutdownTimeout(Duration.ofSeconds(2))
            .build();

    @AfterEach
    void tearDown() {
        work.releaseAll();
        node.shutdown();
    }

    @Test
    void aStartWhoseLockAcquireThrowsPersistsItsRowRunsNothingAndRecoveryRunsItUnderTheLock() {
        var registrations = registrations();
        lock.failAcquire = true;

        node.startWorkflow("wf-1", "WorkWorkflow", "orders", null, workflow(), workMethod());

        assertFalse(node.isRunning("wf-1"), "a start whose lock acquire threw must not run unlocked");
        assertEquals(WorkflowStatus.RUNNING, store.getInstance("wf-1").orElseThrow().status(),
                "the start is persisted and left for recovery");

        lock.failAcquire = false;
        assertEquals(1, node.recoveryPass(registrations).adopted(), "recovery adopts it once the lock is takeable");
        assertEquals(1, valueWithin(work::entries, 1, Duration.ofSeconds(5)), "the adopted run reached its activity");
        assertTrue(lock.isHeld(KEY), "the run executes under its instance lock");
        work.releaseAll();
        assertEquals(WorkflowStatus.COMPLETED, valueWithin(() -> store.getInstance("wf-1").orElseThrow().status(),
                WorkflowStatus.COMPLETED, Duration.ofSeconds(5)));
        assertEquals(1, work.entries(), "the activity ran once");
    }

    @Test
    void aStartWhoseLockIsHeldElsewhereRunsNothingUntilTheLockFrees() {
        var registrations = registrations();
        var elsewhere = lock.tryAcquire(KEY, Duration.ofSeconds(30)).orElseThrow();

        node.startWorkflow("wf-1", "WorkWorkflow", "orders", null, workflow(), workMethod());

        assertFalse(node.isRunning("wf-1"), "a start whose lock is held elsewhere must not run unlocked");
        assertEquals(0, node.recoveryPass(registrations).adopted(), "recovery waits while the lock is held");

        lock.release(elsewhere);
        assertEquals(1, node.recoveryPass(registrations).adopted());
        work.releaseAll();
        assertEquals(WorkflowStatus.COMPLETED, valueWithin(() -> store.getInstance("wf-1").orElseThrow().status(),
                WorkflowStatus.COMPLETED, Duration.ofSeconds(5)));
        assertEquals(1, work.entries(), "the activity ran once");
    }

    @Test
    void aResumeWhoseLockAcquireThrowsIsSkippedAndTheNextPassRunsIt() {
        var registrations = registrations();
        seedRecoverable(store, "wf-1", "orders");
        lock.failAcquire = true;

        assertEquals(0, node.recoveryPass(registrations).adopted(), "a resume whose lock acquire threw is skipped");
        assertFalse(node.isRunning("wf-1"));
        assertEquals(0, store.getInstance("wf-1").orElseThrow().leaseEpoch(), "nothing adopted the row");

        lock.failAcquire = false;
        assertEquals(1, node.recoveryPass(registrations).adopted());
        work.releaseAll();
        assertEquals(WorkflowStatus.COMPLETED, valueWithin(() -> store.getInstance("wf-1").orElseThrow().status(),
                WorkflowStatus.COMPLETED, Duration.ofSeconds(5)));
    }

    private java.util.function.Supplier<Object> workflow() {
        var proxy = work.proxy(node, store, serializer, "A");
        return () -> new WorkWorkflow(proxy);
    }

    private Map<String, WorkflowRegistration> registrations() {
        return Map.of("WorkWorkflow", new WorkflowRegistration("WorkWorkflow", "orders", workflow(), workMethod()));
    }

    /** A {@link SharedLock} whose {@code tryAcquire} throws while {@link #failAcquire} is set. */
    private static final class FlakyLock implements DistributedLock {

        private final SharedLock delegate = new SharedLock();
        volatile boolean failAcquire;

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            if (failAcquire) {
                throw new IllegalStateException("lock backend unavailable (test)");
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

        boolean isHeld(String key) {
            return delegate.isHeld(key);
        }
    }
}
