package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SERVICE;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.valueWithin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue 87: {@code WorkflowExecutor.shutdown()} can return while a run it was
 * draining still holds its instance lock.
 *
 * <p>A run's {@code finally} removes it from {@code runningWorkflows} and only
 * then releases its instance lock. The shutdown drain iterates
 * {@code runningWorkflows} once; a run already removed is not joined. So when
 * the drain starts inside that gap, {@code shutdown()} returns with the lock
 * still held, and a peer's recovery pass that runs at once skips the workflow
 * as "locked by another node". Production re-polls; the test kit's
 * {@code recover()} is one ungated pass, so the workflow is never adopted
 * (the CI failure of {@code DeterminismCheckerRecoverableTest} on PR #64:
 * "did not finish within PT10S ... (status: COMPENSATING)").
 *
 * <p>Deterministic: the lock's release blocks until the test opens it, and
 * the drain starts only once the run is inside that release, so past its
 * removal from the map.
 */
@Timeout(30)
class ShutdownReturnsBeforeLockReleaseTest {

    private final RecoveryCapacityFixtures.CountingStore store = new RecoveryCapacityFixtures.CountingStore();
    private final GatedReleaseLock lock = new GatedReleaseLock();
    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private final WorkflowExecutor a = executor();
    private final WorkflowExecutor b = executor();

    @AfterEach
    void tearDown() {
        lock.open.countDown();
        a.shutdown();
        b.shutdown();
    }

    @Test
    @Disabled("Issue 87: red until the shutdown drain also waits for runs that left runningWorkflows "
            + "but have not released their instance lock (an engine change, routed by the coordinator)")
    void shutdownReturnsOnlyOnceEveryDrainedRunHasReleasedItsInstanceLock() throws Exception {
        a.startWorkflow("wf-1", "ParkWorkflow", "default", null, ParkWorkflow::new, parkMethod());
        assertEquals(WorkflowStatus.WAITING_SIGNAL,
                valueWithin(() -> store.getInstance("wf-1").orElseThrow().status(), WorkflowStatus.WAITING_SIGNAL,
                        Duration.ofSeconds(5)));

        // The parked run is abandoned at shutdown's step 3; it then removes
        // itself from runningWorkflows and blocks in its lock release. The
        // drain starts only once it is there; the release is let go 300 ms later.
        lock.gateNextRelease();
        a.beforeShutdownDrain = () -> {
            try {
                assertTrue(lock.inRelease.await(5, TimeUnit.SECONDS), "the run reached its lock release");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(300); // lets shutdown() run ahead of the release, as on a loaded runner
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                lock.open.countDown();
            });
        };

        a.shutdown();

        assertEquals(0, lock.heldCount(),
                "shutdown() returned while a run it drained still held its instance lock");
        var registrations = Map.of("ParkWorkflow",
                new WorkflowRegistration("ParkWorkflow", "default", ParkWorkflow::new, parkMethod()));
        assertEquals(1, b.recoverWorkflows(registrations), "a peer's recovery pass adopts the abandoned run");
    }

    private WorkflowExecutor executor() {
        return WorkflowExecutor.builder(store, serializer, SERVICE)
                .lifecycleEventsEnabled(false)
                .distributedLock(lock)
                .shutdownTimeout(Duration.ofSeconds(5))
                .build();
    }

    private static Method parkMethod() {
        try {
            return ParkWorkflow.class.getMethod("run");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Parks on a signal that never comes. */
    @DurableWorkflow(name = "ParkWorkflow")
    public static final class ParkWorkflow {
        /** @return the signal's payload */
        @WorkflowMethod
        public String run() {
            return WorkflowContext.current().awaitSignal("go", String.class, Duration.ofHours(1));
        }
    }

    /** A map-backed lock whose next release blocks until {@link #open} is counted down. */
    static final class GatedReleaseLock implements DistributedLock {
        private final ConcurrentHashMap<String, LockHandle> held = new ConcurrentHashMap<>();
        private volatile boolean gated;
        final CountDownLatch inRelease = new CountDownLatch(1);
        final CountDownLatch open = new CountDownLatch(1);

        void gateNextRelease() {
            gated = true;
        }

        int heldCount() {
            return held.size();
        }

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            var handle = new LockHandle(key, UUID.randomUUID().toString(), Instant.now().plus(ttl));
            return held.putIfAbsent(key, handle) == null ? Optional.of(handle) : Optional.empty();
        }

        @Override
        public void release(LockHandle handle) {
            if (gated) {
                gated = false;
                inRelease.countDown();
                try {
                    open.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            held.remove(handle.key(), handle);
        }

        @Override
        public boolean renew(LockHandle handle, Duration ttl) {
            return handle.equals(held.get(handle.key()));
        }

        @Override
        public Set<String> heldKeys(Collection<String> keys) {
            return keys.stream().filter(held::containsKey).collect(Collectors.toSet());
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return true;
        }
    }
}
