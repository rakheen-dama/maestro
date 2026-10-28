package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.QueryMethod;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.WorkflowInstantiationException;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F1: the executor builds one workflow object per run from the registration's
 * factory, so instance fields — and therefore {@code @QueryMethod} answers —
 * never bleed between concurrent runs of the same workflow type, and a
 * re-adopted run never inherits the object of a previous one.
 */
@DisplayName("One workflow object per run")
class WorkflowExecutorPerRunInstanceTest {

    private VersionedInMemoryStore store;
    private PayloadSerializer serializer;
    private WorkflowExecutor executor;
    private WorkflowExecutor second;

    @BeforeEach
    void setUp() {
        store = new VersionedInMemoryStore();
        serializer = new PayloadSerializer(JsonMapper.builder().build());
        executor = new WorkflowExecutor(store, null, null, null, serializer, "node-a");
    }

    @AfterEach
    void tearDown() {
        if (executor != null) executor.shutdown();
        if (second != null) second.shutdown();
    }

    @Test
    void eachStartInvokesTheFactoryAndQueriesAnswerFromThatRunsObject() throws Exception {
        var created = new AtomicInteger();
        Supplier<Object> factory = () -> { created.incrementAndGet(); return new ProbeWorkflow(); };
        var method = ProbeWorkflow.class.getMethod("run", String.class);
        executor.registerQueries("probe", ProbeWorkflow.class);

        executor.startWorkflow("p-a", "probe", "default", "A", factory, method);
        executor.startWorkflow("p-b", "probe", "default", "B", factory, method);
        await().atMost(5, SECONDS).until(() -> executor.parkedCount() == 2);

        assertEquals(2, created.get(), "one workflow object per run");
        assertEquals("A", executor.queryWorkflow("p-a", "step", null, String.class));
        assertEquals("B", executor.queryWorkflow("p-b", "step", null, String.class));
    }

    @Test
    void resumeInvokesTheFactoryAgainAndDiscardsTheOldObject() throws Exception {
        var created = new AtomicInteger();
        var first = new ProbeWorkflow();
        var replacement = new ProbeWorkflow();
        Supplier<Object> factory = () ->
                created.incrementAndGet() == 1 ? first : replacement;
        var method = ProbeWorkflow.class.getMethod("run", String.class);

        executor.registerQueries("probe", ProbeWorkflow.class);
        executor.startWorkflow("p-r", "probe", "default", "R", factory, method);
        await().atMost(5, SECONDS).until(() -> executor.parkedCount() == 1);
        assertEquals("R", first.step);

        // Node stops mid-park: the park is abandoned and the row stays WAITING_SIGNAL.
        executor.shutdown();
        executor = null;
        assertEquals(WorkflowStatus.WAITING_SIGNAL,
                store.getInstance("p-r").orElseThrow().status());

        second = new WorkflowExecutor(store, null, null, null, serializer, "node-b");
        second.registerQueries("probe", ProbeWorkflow.class);
        assertTrue(second.resumeWorkflow(store.getInstance("p-r").orElseThrow(), factory, method));
        await().atMost(5, SECONDS).until(() -> second.parkedCount() == 1);

        assertEquals(2, created.get(), "the re-adoption builds a fresh object");
        assertNotSame(first, replacement);
        assertEquals("R", replacement.step, "the replayed run wrote to the new object");
        assertEquals("R", second.queryWorkflow("p-r", "step", null, String.class));
    }

    @Test
    void aThrowingFactorySurfacesToTheCallerAndLeavesNoRunBehind() throws Exception {
        var lock = new MapLock();
        var observer = new RecordingEngineObserver();
        executor.shutdown();
        executor = new WorkflowExecutor(store, lock, null, null, serializer, "node-a",
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX,
                WorkflowInstanceLockManager.DEFAULT_LOCK_TTL,
                false, WorkflowExecutor.DEFAULT_SHUTDOWN_TIMEOUT, Duration.ofMillis(200), observer);
        var method = ProbeWorkflow.class.getMethod("run", String.class);
        var cause = new IllegalStateException("no bean");
        Supplier<Object> factory = () -> { throw cause; };

        var thrown = assertThrows(WorkflowInstantiationException.class,
                () -> executor.startWorkflow("p-x", "probe", "default", "X", factory, method));

        assertSame(cause, thrown.getCause(), "the factory's exception is preserved as the cause");
        assertEquals("p-x", thrown.workflowId());
        assertEquals("probe", thrown.workflowType());
        assertFalse(executor.isRunning("p-x"), "no running-workflow record is left behind");
        assertTrue(lock.locks.isEmpty(), "the instance lock is released when the factory throws");
        assertTrue(observer.started().isEmpty(),
                "workflowStarted is emitted only once the run's object exists");
        assertEquals(WorkflowStatus.RUNNING, store.getInstance("p-x").orElseThrow().status(),
                "the row stays active for the recovery poller to adopt");
    }

    @Test
    void aRecoveryPassSkipsAnInstanceWhoseFactoryThrowsAndAdoptsTheRest() throws Exception {
        // Deterministic order: the broken instance is always offered first.
        store = new VersionedInMemoryStore() {
            @Override
            public List<WorkflowInstance> getRecoverableInstances() {
                return super.getRecoverableInstances().stream()
                        .sorted(Comparator.comparing(WorkflowInstance::workflowId))
                        .toList();
            }
        };
        executor.shutdown();
        executor = new WorkflowExecutor(store, null, null, null, serializer, "node-a");
        var method = ProbeWorkflow.class.getMethod("run", String.class);
        executor.startWorkflow("a-broken", "broken", "default", "A", ProbeWorkflow::new, method);
        executor.startWorkflow("b-healthy", "probe", "default", "B", ProbeWorkflow::new, method);
        await().atMost(5, SECONDS).until(() -> executor.parkedCount() == 2);
        executor.shutdown();
        executor = null;

        second = new WorkflowExecutor(store, null, null, null, serializer, "node-b");
        Supplier<Object> broken = () -> { throw new IllegalStateException("broken wiring"); };
        var recovered = second.recoverWorkflows(Map.of(
                "broken", new WorkflowRegistration("broken", "default", broken, method),
                "probe", new WorkflowRegistration("probe", "default", ProbeWorkflow::new, method)));

        assertEquals(1, recovered, "the healthy instance behind the broken one is still adopted");
        await().atMost(5, SECONDS).until(() -> second.parkedCount() == 1);
        second.deliverSignal("b-healthy", "go", "done");
        await().atMost(5, SECONDS).until(() ->
                store.getInstance("b-healthy").orElseThrow().status() == WorkflowStatus.COMPLETED);
        assertFalse(second.isRunning("a-broken"));
        assertEquals(WorkflowStatus.WAITING_SIGNAL, store.getInstance("a-broken").orElseThrow().status(),
                "the broken instance is left active for a later pass");
    }

    // ── fixture ────────────────────────────────────────────────────────

    /** Minimal in-memory lock: one holder per key, no expiry. */
    private static final class MapLock implements DistributedLock {

        final ConcurrentHashMap<String, LockHandle> locks = new ConcurrentHashMap<>();

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            var handle = new LockHandle(key, UUID.randomUUID().toString(), Instant.now().plus(ttl));
            return locks.putIfAbsent(key, handle) != null ? Optional.empty() : Optional.of(handle);
        }

        @Override
        public void release(LockHandle handle) {
            locks.computeIfPresent(handle.key(), (_, current) ->
                    current.token().equals(handle.token()) ? null : current);
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

    /** Records its input in an instance field, then parks on a signal. */
    @DurableWorkflow(name = "probe")
    public static class ProbeWorkflow {

        volatile String step = "init";

        /**
         * @param label the value the run writes into this object's state
         * @return the signal payload
         */
        @WorkflowMethod
        public String run(String label) {
            step = label;
            return WorkflowContext.current().awaitSignal("go", String.class, Duration.ofMinutes(1));
        }

        /** @return this object's recorded label */
        @QueryMethod
        public String step() {
            return step;
        }
    }
}
