package io.b2mash.maestro.spring.observe;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.observe.CompositeEngineObserver;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code maestro.loop.errors} end to end for the five engine loops: an
 * {@code Error} from the store or the lock backend inside each loop reaches
 * {@link MicrometerEngineObserver} through {@link CompositeEngineObserver}, as
 * the auto-configuration wires them, and is counted under that loop's name.
 *
 * <p>The two {@code maestro-store-postgres} loops (lock cleaner, wake listener)
 * do not reach an {@code EngineObserver} and are not counted (Issue 65).
 *
 * <p><b>Thread safety:</b> per-test fixtures; the fault counters are atomic.
 */
class LoopErrorCounterWiringTest {

    private static final List<String> ENGINE_LOOPS = List.of(
            "timer-poller", "recovery-poller", "signal-janitor", "retention-sweeper", "instance-lock-renewer");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Map<String, AtomicInteger> throwsByLoop = new ConcurrentHashMap<>();
    private final WorkflowStore store = erroringStore(new InMemoryWorkflowStore());
    private final WorkflowExecutor executor = WorkflowExecutor.builder(
                    store, new PayloadSerializer(new ObjectMapper()), "svc")
            .distributedLock(new RenewErrorLock())
            .instanceLockTtl(Duration.ofMillis(150))                    // renews every 50 ms
            .lifecycleEventsEnabled(false)
            .observer(CompositeEngineObserver.of(List.of(new MicrometerEngineObserver(registry))))
            .build();

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void anErrorInEachEngineLoopIsCountedUnderThatLoop() throws Exception {
        executor.startTimerPoller(Duration.ofMillis(50), 10);
        executor.startRecoveryPoller(Map.of(), Duration.ofMillis(50));
        executor.startSignalJanitor(Duration.ofMillis(50), Duration.ofDays(7));
        executor.startRetentionSweeper(new WorkflowExecutor.RetentionSettings(Duration.ofMillis(50),
                Duration.ofDays(30), Duration.ofDays(30), Duration.ofDays(90), 10, 1));
        executor.startWorkflow("wf-1", "AwaitForever", "default", null, AwaitForever::new,
                AwaitForever.class.getMethod("run"));                    // starts the renewer

        // Each loop's fault fired twice: the first one's handling (the counter) is complete.
        await("every loop hit its Error twice").atMost(Duration.ofSeconds(10))
                .until(() -> ENGINE_LOOPS.stream().allMatch(loop -> throwsOf(loop) >= 2));

        assertAll(ENGINE_LOOPS.stream().map(loop -> () -> {
            var counter = registry.find("maestro.loop.errors").tag("loop", loop).counter();
            assertNotNull(counter, "no maestro.loop.errors{loop=" + loop + "}: " + registry.getMetersAsString());
            assertTrue(counter.count() >= 1.0, loop + " counted " + counter.count());
        }));
    }

    private int throwsOf(String loop) {
        var count = throwsByLoop.get(loop);
        return count == null ? 0 : count.get();
    }

    private void thrown(String loop) {
        throwsByLoop.computeIfAbsent(loop, l -> new AtomicInteger()).incrementAndGet();
    }

    /** Delegates every call, but each loop's own store read throws an {@code AssertionError}. */
    private WorkflowStore erroringStore(InMemoryWorkflowStore delegate) {
        var loopsByMethod = Map.of(
                "getDueTimers", "timer-poller",
                "getRecoverableInstances", "recovery-poller",
                "deleteUnconsumedSignalsForTerminalInstancesBefore", "signal-janitor",
                "purgeTerminalInstancesBefore", "retention-sweeper");
        return (WorkflowStore) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {WorkflowStore.class},
                (self, method, args) -> {
                    var loop = loopsByMethod.get(method.getName());
                    if (loop != null) {
                        thrown(loop);
                        throw new AssertionError("injected in " + method.getName());
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** Grants locks and leadership; {@code renewAll} throws an {@code AssertionError}. */
    private final class RenewErrorLock implements DistributedLock {

        private final InMemoryDistributedLock delegate = new InMemoryDistributedLock();

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
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
            thrown("instance-lock-renewer");
            throw new AssertionError("injected in renewAll");
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return true;
        }
    }

    /** Parks on a signal that never comes. */
    public static class AwaitForever {

        /** Awaits a signal that is never sent. */
        public void run() {
            WorkflowContext.current().awaitSignal("never", String.class, Duration.ofHours(1));
        }
    }
}
