package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import io.b2mash.maestro.core.spi.RecoveryCursor;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * WP6 test fixtures shared by the recovery-capacity tests: a lock backend with
 * a real {@code heldKeys}, a store that records its recovery-page reads, an
 * activity that blocks until the test releases it, and polling helpers that
 * return the last value read instead of throwing on timeout.
 */
final class RecoveryCapacityFixtures {

    private RecoveryCapacityFixtures() {
    }

    /** The service every node of these tests shares. */
    static final String SERVICE = "svc";

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
    private static final AtomicLong SEEDED = new AtomicLong();

    /**
     * Seeds a {@code RUNNING} {@code WorkWorkflow} instance of {@link #SERVICE}.
     * Each call's {@code startedAt} is 1 ms after the previous call's, so the
     * keyset (recovery) order is the seeding order.
     */
    static WorkflowInstance seedRecoverable(VersionedInMemoryStore store, String workflowId, String taskQueue) {
        var startedAt = BASE.plusMillis(SEEDED.incrementAndGet());
        return store.createInstance(WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId(workflowId)
                .runId(UUID.randomUUID())
                .workflowType("WorkWorkflow")
                .taskQueue(taskQueue)
                .status(WorkflowStatus.RUNNING)
                .serviceName(SERVICE)
                .eventSequence(0)
                .startedAt(startedAt)
                .updatedAt(startedAt)
                .version(0)
                .build());
    }

    /** Polls {@code read} until it equals {@code wanted} or {@code within} elapses; returns the last value read. */
    static <T> T valueWithin(Supplier<T> read, T wanted, Duration within) {
        return valueMatching(read, wanted::equals, within);
    }

    /** Polls {@code read} until it satisfies {@code wanted} or {@code within} elapses; returns the last value read. */
    static <T> T valueMatching(Supplier<T> read, Predicate<? super T> wanted, Duration within) {
        var deadline = System.nanoTime() + within.toNanos();
        var value = read.get();
        while (!wanted.test(value) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5); // a poll interval, not a wait for a condition
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return value;
            }
            value = read.get();
        }
        return value;
    }

    /** The {@code WorkWorkflow} entry point. */
    static Method workMethod() {
        try {
            return WorkWorkflow.class.getMethod("run");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── lock ──────────────────────────────────────────────────────────

    /**
     * A map-backed lock with exact per-key exclusion and a real
     * {@code heldKeys} (the keys present in the map). {@link #refuse} makes
     * {@code tryAcquire} fail for a key without {@code heldKeys} reporting it.
     */
    static final class SharedLock implements DistributedLock {

        private final ConcurrentHashMap<String, LockHandle> held = new ConcurrentHashMap<>();
        /** Keys whose {@code tryAcquire} fails while {@code heldKeys} does not report them. */
        final Set<String> refuse = ConcurrentHashMap.newKeySet();

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            if (refuse.contains(key)) {
                return Optional.empty();
            }
            var handle = new LockHandle(key, UUID.randomUUID().toString(), Instant.now().plus(ttl));
            return held.putIfAbsent(key, handle) == null ? Optional.of(handle) : Optional.empty();
        }

        @Override
        public void release(LockHandle handle) {
            held.remove(handle.key(), handle);
        }

        @Override
        public boolean renew(LockHandle handle, Duration ttl) {
            return handle.equals(held.get(handle.key()));
        }

        @Override
        public Set<LockHandle> renewAll(Collection<LockHandle> handles, Duration ttl) {
            return handles.stream().filter(h -> !h.equals(held.get(h.key()))).collect(Collectors.toSet());
        }

        @Override
        public Set<String> heldKeys(Collection<String> keys) {
            return keys.stream().filter(held::containsKey).collect(Collectors.toSet());
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return true;
        }

        int heldCount() {
            return held.size();
        }

        /**
         * Frees {@code key} behind its holder's back — as a TTL expiry seen by
         * another node would: the holder's next {@code renewAll} reports its
         * handle lost, and anyone may acquire the key.
         */
        void forceRelease(String key) {
            held.remove(key);
        }

        boolean isHeld(String key) {
            return held.containsKey(key);
        }
    }

    // ── store ─────────────────────────────────────────────────────────

    /** One {@code getRecoverableInstances(service, after, limit)} call. */
    record PageRead(@Nullable RecoveryCursor after, int limit, int headroomAtRead) {
    }

    /**
     * Records every recovery-page read, with the headroom the test's
     * {@link #headroom} supplier reports at that moment, and lets a test
     * replace {@code getInstance} for one workflow.
     */
    static class CountingStore extends VersionedInMemoryStore {

        /** When set, {@code reopenFailedInstance} throws it and writes nothing. */
        volatile @Nullable RuntimeException reopenFailure;

        @Override
        public synchronized void reopenFailedInstance(WorkflowInstance reopened, Set<Integer> deleteEventSequences,
                                                      Set<String> deleteTimerIds) {
            var failure = reopenFailure;
            if (failure != null) {
                throw failure;
            }
            super.reopenFailedInstance(reopened, deleteEventSequences, deleteTimerIds);
        }

        final List<PageRead> pageReads = new CopyOnWriteArrayList<>();
        /** Read at every page read; {@code -1} until a test sets it. */
        volatile IntSupplier headroom = () -> -1;
        /** {@code getInstance} for a workflow id present here is answered by its function. */
        final Map<String, Function<Optional<WorkflowInstance>, Optional<WorkflowInstance>>> getInstanceHooks =
                new ConcurrentHashMap<>();

        @Override
        public List<WorkflowInstance> getRecoverableInstances(String serviceName, @Nullable RecoveryCursor after,
                                                              int limit) {
            pageReads.add(new PageRead(after, limit, headroom.getAsInt()));
            return super.getRecoverableInstances(serviceName, after, limit);
        }

        private final Set<String> created = ConcurrentHashMap.newKeySet();

        @Override
        public WorkflowInstance createInstance(WorkflowInstance instance) {
            var createdInstance = super.createInstance(instance);
            created.add(instance.workflowId());
            return createdInstance;
        }

        /** How many instances whose id starts with {@code prefix} are {@code COMPLETED}. */
        int completed(String prefix) {
            return (int) created.stream()
                    .filter(id -> id.startsWith(prefix))
                    .map(super::getInstance)
                    .flatMap(Optional::stream)
                    .filter(i -> i.status() == WorkflowStatus.COMPLETED)
                    .count();
        }

        @Override
        public Optional<WorkflowInstance> getInstance(String workflowId) {
            var hook = getInstanceHooks.get(workflowId);
            var actual = super.getInstance(workflowId);
            return hook == null ? actual : hook.apply(actual);
        }

        int pageReads() {
            return pageReads.size();
        }

        int pageReadsWithZeroHeadroom() {
            return (int) pageReads.stream().filter(r -> r.headroomAtRead() == 0).count();
        }

        PageRead lastPageRead() {
            return pageReads.getLast();
        }
    }

    // ── activity and workflow ─────────────────────────────────────────

    /** The activity every recovered run calls once. */
    @Activity(name = "work")
    public interface Work {
        /** @param workflowId the calling run's id @return a marker */
        String work(String workflowId);
    }

    /**
     * Counts entries, records the calling workflow ids in order and the node
     * that ran each, and blocks on a semaphore the test releases one permit at
     * a time — unless {@link #releaseAll()} was called or the id matches
     * {@link #completeAtOnceFor}.
     */
    static final class BlockingWork {

        private final Semaphore gate = new Semaphore(0, true);
        private final AtomicInteger entries = new AtomicInteger();
        private final AtomicInteger finished = new AtomicInteger();
        private final List<String> order = new CopyOnWriteArrayList<>();
        private final Set<String> nodes = ConcurrentHashMap.newKeySet();
        private volatile boolean open;
        private volatile Predicate<String> atOnce = _ -> false;

        /** A proxy of {@link Work} for runs on {@code executor}, recording {@code node} as their node. */
        Work proxy(WorkflowExecutor executor, VersionedInMemoryStore store, PayloadSerializer serializer,
                   String node) {
            Work impl = workflowId -> {
                entries.incrementAndGet();
                order.add(workflowId);
                nodes.add(node);
                if (!open && !atOnce.test(workflowId)) {
                    gate.acquireUninterruptibly();
                }
                finished.incrementAndGet();
                return "done";
            };
            return new ActivityProxyFactory().createProxy(
                    Work.class, impl, store, null,
                    RetryPolicy.noRetry(), Duration.ofSeconds(30), serializer, new RetryExecutor(), EngineObserver.NOOP,
                    executor.taskQueueGovernor());
        }

        /** Lets {@code n} blocked (or future) calls return. */
        void release(int n) {
            gate.release(n);
        }

        /** Every call, blocked or future, returns at once. */
        void releaseAll() {
            open = true;
            gate.release(10_000);
        }

        /** Calls from matching workflow ids return at once. */
        void completeAtOnceFor(Predicate<String> ids) {
            atOnce = ids;
        }

        int entries() {
            return entries.get();
        }

        int finished() {
            return finished.get();
        }

        List<String> order() {
            return List.copyOf(order);
        }

        Set<String> nodesThatRan() {
            return Set.copyOf(nodes);
        }
    }

    /** One {@link Work} call. */
    @DurableWorkflow(name = "WorkWorkflow")
    public static final class WorkWorkflow {
        private final Work work;

        WorkWorkflow(Work work) {
            this.work = work;
        }

        /** @return the activity's result */
        @WorkflowMethod
        public String run() {
            return work.work(WorkflowContext.current().workflowId());
        }
    }
}
