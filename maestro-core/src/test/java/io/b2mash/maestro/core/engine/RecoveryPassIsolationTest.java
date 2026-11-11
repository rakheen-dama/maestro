package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.exception.LockBackendException;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import io.b2mash.maestro.core.spi.PurgeResult;
import io.b2mash.maestro.core.spi.RecoveryCursor;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Why RULING 10's store fix has a wider blast radius than a single workflow:
 * {@link WorkflowExecutor#recoverWorkflows} contains only a throwing workflow
 * factory per instance (R44, {@code WorkflowInstantiationException}); anything
 * else thrown while adopting one instance — a store read included — ends the
 * pass for every instance behind it in the batch.
 *
 * <p>Both halves are pinned here against the two possible store behaviours,
 * because the difference between them <em>is</em> RULING 10:
 *
 * <ul>
 *   <li><b>Throwing</b> (what {@code WorkflowStatus.valueOf} did on a status
 *       string written by a newer node): the pass aborts and the healthy
 *       workflow behind the poisoned one is never adopted — a workflow that has
 *       nothing whatever to do with the mixed-version row silently stops making
 *       progress on this node.</li>
 *   <li><b>Skipping</b> (returning empty, what the fixed mapper does): the
 *       poisoned instance is passed over and the healthy one <em>is</em>
 *       adopted.</li>
 * </ul>
 *
 * <p>The store here fakes the mapper's two behaviours rather than parsing SQL —
 * the mapper itself is pinned against real Postgres in
 * {@code PostgresUnknownStatusMappingTest}. What this test owns is the
 * consequence: that "skip" is a survivable answer for the recovery loop and
 * "throw" is not.
 *
 * <p>The pass's bounds are pinned here too: it reads this service's instances
 * page by page through the paged scan (never the unbounded one), and it spends
 * no {@code tryAcquire} on an instance whose lock {@code heldKeys} reports
 * held elsewhere.
 */
@DisplayName("One unreadable instance must not end the recovery pass for every workflow behind it")
class RecoveryPassIsolationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private PoisonableStore store;
    private WorkflowExecutor executor;
    private int seeded;

    @BeforeEach
    void setUp() {
        store = new PoisonableStore();
        executor = executor(null, "old-node");
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    /** Replaces the default executor with one on {@code lock} for {@code serviceName}. */
    private WorkflowExecutor useExecutor(@Nullable DistributedLock lock, String serviceName) {
        executor.shutdown();
        executor = executor(lock, serviceName);
        return executor;
    }

    private WorkflowExecutor executor(@Nullable DistributedLock lock, String serviceName) {
        // The raw store: the executor wraps it in StoreFaultGuard's proxy itself,
        // so every count below is what reached the store THROUGH that proxy.
        var serializer = new PayloadSerializer(JsonMapper.builder().build());
        return new WorkflowExecutor(store, lock, null, null, serializer, serviceName);
    }

    @Test
    @Timeout(30)
    @DisplayName("a skipped instance is passed over and the healthy workflow behind it is adopted")
    void skippingOneInstanceLetsThePassContinue() {
        var runs = new AtomicInteger();
        seedRecoverable("poisoned-wf");
        seedRecoverable("healthy-wf");
        // The fixed mapper's answer: this build cannot interpret that row, so
        // the instance is invisible.
        store.invisibleTo(Optional::empty, "poisoned-wf"::equals);
        assertEquals(List.of("poisoned-wf", "healthy-wf"),
                store.getRecoverableInstances().stream().map(WorkflowInstance::workflowId).toList(),
                "the poisoned instance must be adopted FIRST, or skipping it proves nothing "
                        + "about the healthy one behind it");

        var adopted = executor.recoverWorkflows(registrations(runs));

        await().atMost(Duration.ofSeconds(5)).until(() -> runs.get() >= 1);
        assertAll(
                () -> assertEquals(1, adopted,
                        "exactly the healthy workflow is adopted; the unreadable one is "
                                + "left for an upgraded node"),
                () -> assertEquals(1, runs.get(),
                        "the workflow behind the poisoned one must still run"));
    }

    @Test
    @Timeout(30)
    @DisplayName("a THROWING instance read ends the pass — the healthy workflow behind it never runs")
    void throwingOnOneInstanceEndsTheWholePass() {
        var runs = new AtomicInteger();
        seedRecoverable("poisoned-wf");
        seedRecoverable("healthy-wf");
        // The pre-RULING-10 mapper's answer: WorkflowStatus.valueOf on a string
        // this build does not define.
        store.invisibleTo(() -> {
            throw new IllegalArgumentException(
                    "No enum constant WorkflowStatus.HIBERNATING_IN_A_NEWER_MAESTRO");
        }, "poisoned-wf"::equals);
        assertEquals(List.of("poisoned-wf", "healthy-wf"),
                store.getRecoverableInstances().stream().map(WorkflowInstance::workflowId).toList(),
                "the poisoned instance must be adopted FIRST, or the healthy one is launched "
                        + "before the throw and runs.get() races the assertion below");

        assertThrows(IllegalArgumentException.class,
                () -> executor.recoverWorkflows(registrations(runs)),
                "recoverWorkflows contains only factory failures per instance, not store-read failures");

        assertEquals(0, runs.get(),
                "this is the damage RULING 10 removes: a workflow with no connection to the "
                        + "unreadable row never got adopted, and would not until the row "
                        + "changed or the node restarted");
    }

    // ── bounded scan ──────────────────────────────────────────────────────

    @Test
    @Timeout(30)
    @DisplayName("the pass reads through the paged scan, never the unbounded one — through the guard proxy")
    void passNeverCallsTheUnboundedScan() {
        seedRecoverable("wf-1");
        seedRecoverable("wf-2");

        var pass = executor.recoveryPass(registrations(new AtomicInteger()));

        assertAll(
                () -> assertEquals(0, store.unboundedScans.get(),
                        "the no-arg scan reads every active row of every service"),
                // The executor's store is StoreFaultGuard's reflective proxy over
                // this raw store. A paged call recorded HERE proves the proxy
                // dispatched the SPI default method to the store's override; had
                // it run the default on the proxy, that default would have called
                // the no-arg scan instead.
                () -> assertEquals(List.of(2), store.pageSizes,
                        "one short page ends the pass"),
                () -> assertEquals(2, pass.adopted()));
    }

    @Test
    @Timeout(60)
    @DisplayName("450 recoverable instances are read as pages of 200, 200 and 50 — and all adopted")
    void passReadsEveryPage() {
        IntStream.range(0, 450).forEach(i -> seedRecoverable("wf-%03d".formatted(i)));

        var pass = executor.recoveryPass(registrations(new AtomicInteger()));

        assertAll(
                () -> assertEquals(List.of(200, 200, 50), store.pageSizes,
                        "a page shorter than the page size ends the pass: 3 calls, not 4"),
                () -> assertEquals(450, pass.scanned(), "scanned counts rows read"),
                () -> assertEquals(450, pass.adopted()));
    }

    @Test
    @Timeout(30)
    @DisplayName("instances whose lock heldKeys reports held elsewhere cost no tryAcquire")
    void instancesHeldElsewhereCostNoAcquire() {
        var lock = new RecordingLock();
        var ids = IntStream.range(0, 150).mapToObj("wf-%03d"::formatted).toList();
        lock.heldElsewhere = Set.copyOf(ids.subList(0, 100));
        useExecutor(lock, "old-node");
        ids.forEach(this::seedRecoverable);

        var pass = executor.recoveryPass(registrations(new AtomicInteger()));

        assertAll(
                () -> assertEquals(50, lock.acquiredKeys.size(),
                        "only the 50 instances not reported held may cost a tryAcquire"),
                () -> assertTrue(lock.acquiredKeys.stream().noneMatch(k -> lock.heldElsewhere.contains(idOf(k))),
                        "no reported-held instance was attempted: " + lock.acquiredKeys),
                () -> assertEquals(1, lock.heldKeysCalls.get(), "one heldKeys read per page"),
                () -> assertEquals(50, pass.adopted()),
                () -> assertEquals(150, pass.scanned()));
    }

    @Test
    @Timeout(30)
    @DisplayName("a throwing heldKeys skips nothing: every instance still goes through tryAcquire")
    void throwingHeldKeysStillAttemptsEveryInstance() {
        var lock = new RecordingLock();
        lock.throwOnHeldKeys = true;
        useExecutor(lock, "old-node");
        IntStream.range(0, 5).forEach(i -> seedRecoverable("wf-" + i));

        var pass = executor.recoveryPass(registrations(new AtomicInteger()));

        assertEquals(5, lock.acquiredKeys.size(), "heldKeys is advisory — tryAcquire stays the authority");
        assertEquals(5, pass.adopted());
    }

    @Test
    @Timeout(30)
    @DisplayName("another service's active instances are neither read nor adopted")
    void otherServicesInstancesAreNotAdopted() {
        var lock = new RecordingLock();
        useExecutor(lock, "svc-a");
        var mine = List.of("a-1", "a-2", "a-3");
        mine.forEach(id -> seed(id, "svc-a"));
        seed("b-1", "svc-b");
        seed("b-2", "svc-b");

        var pass = executor.recoveryPass(registrations(new AtomicInteger()));

        assertAll(
                () -> assertEquals(Set.copyOf(mine),
                        lock.acquiredKeys.stream().map(RecoveryPassIsolationTest::idOf).collect(Collectors.toSet()),
                        "only svc-a's instances may be attempted"),
                () -> assertEquals(3, pass.adopted()),
                () -> assertEquals(3, pass.scanned(), "svc-b's rows are not even read"));
    }

    /**
     * D1, pinned positively: the service filter is unconditional. Nodes of one
     * service share its name; an instance of another service sharing the
     * tables is never this node's to adopt — not on the first pass, not on
     * any later one — while this service's own instance is.
     */
    @Test
    @Timeout(30)
    @DisplayName("D1: a svc-b instance is never adopted by a svc-a executor, pass after pass; the svc-a one is")
    void anotherServicesInstanceIsNeverAdoptedPassAfterPass() {
        var lock = new RecordingLock();
        useExecutor(lock, "svc-a");
        seed("a-1", "svc-a");
        seed("b-1", "svc-b");

        var adopted = new ArrayList<Integer>();
        for (int pass = 0; pass < 3; pass++) {
            adopted.add(executor.recoveryPass(registrations(new AtomicInteger())).adopted());
        }

        assertAll(
                () -> assertEquals(List.of(1, 0, 0), adopted, "a-1 on the first pass, nothing after"),
                () -> assertEquals(List.of("a-1"), lock.acquiredKeys.stream().map(RecoveryPassIsolationTest::idOf).toList(),
                        "b-1 never costs even a lock attempt"),
                () -> assertEquals(WorkflowStatus.RUNNING, store.getInstance("b-1").orElseThrow().status(),
                        "b-1 is left exactly as svc-b's own nodes will find it"));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** The workflow id at the end of an instance lock key. */
    private static String idOf(String lockKey) {
        return lockKey.substring(lockKey.lastIndexOf(':') + 1);
    }

    private Map<String, WorkflowRegistration> registrations(AtomicInteger runs) {
        return Map.of("RecoverableWorkflow", new WorkflowRegistration(
                "RecoverableWorkflow", "default", () -> new RecoverableWorkflow(runs), workflowMethod()));
    }

    private void seedRecoverable(String workflowId) {
        seed(workflowId, "old-node");
    }

    /** Seeds a RUNNING instance; each one starts after the previous, so seed order is scan order. */
    private void seed(String workflowId, String serviceName) {
        var startedAt = T0.plusSeconds(seeded++);
        store.createInstance(WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId(workflowId)
                .runId(UUID.randomUUID())
                .workflowType("RecoverableWorkflow")
                .taskQueue("default")
                .status(WorkflowStatus.RUNNING)
                .serviceName(serviceName)
                .eventSequence(0)
                .startedAt(startedAt)
                .updatedAt(startedAt)
                .version(0)
                .build());
    }

    private static Method workflowMethod() {
        try {
            return RecoverableWorkflow.class.getMethod("run");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Counts the runs a recovery pass actually reached. */
    @DurableWorkflow(name = "RecoverableWorkflow")
    public static class RecoverableWorkflow {

        private final AtomicInteger runs;

        RecoverableWorkflow(AtomicInteger runs) {
            this.runs = runs;
        }

        /** @return a fixed result */
        @WorkflowMethod
        public String run() {
            runs.incrementAndGet();
            return "done";
        }
    }

    /**
     * Minimal store whose {@code getInstance} can be made to answer for one
     * workflow ID the way either mapper does — empty, or by throwing.
     *
     * <h2>Thread Safety</h2>
     * <p>Safe for the executor's virtual threads and the test thread.
     */
    private static final class PoisonableStore implements WorkflowStore {

        private final ConcurrentHashMap<String, WorkflowInstance> byWorkflowId =
                new ConcurrentHashMap<>();
        private volatile java.util.function.Supplier<Optional<WorkflowInstance>> poisonAnswer =
                Optional::empty;
        private volatile Predicate<String> poisoned = id -> false;
        /** Calls of the unbounded no-arg scan. */
        final AtomicInteger unboundedScans = new AtomicInteger();
        /** The size of every page the paged scan returned, in call order. */
        final List<Integer> pageSizes = new CopyOnWriteArrayList<>();

        void invisibleTo(java.util.function.Supplier<Optional<WorkflowInstance>> answer,
                         Predicate<String> which) {
            this.poisonAnswer = answer;
            this.poisoned = which;
        }

        @Override
        public WorkflowInstance createInstance(WorkflowInstance instance) {
            byWorkflowId.put(instance.workflowId(), instance);
            return instance;
        }

        @Override
        public Optional<WorkflowInstance> getInstance(String workflowId) {
            if (poisoned.test(workflowId)) {
                return poisonAnswer.get();
            }
            return Optional.ofNullable(byWorkflowId.get(workflowId));
        }

        @Override
        public List<WorkflowInstance> getRecoverableInstances() {
            unboundedScans.incrementAndGet();
            // Poisoned instances first — ordered by the poison predicate, not by
            // name — so the poisoned one is adopted FIRST and the healthy one
            // behind it is what the abort costs.
            return byWorkflowId.values().stream()
                    .sorted(Comparator.comparing((WorkflowInstance i) -> poisoned.test(i.workflowId()) ? 0 : 1)
                            .thenComparing(WorkflowInstance::workflowId))
                    .toList();
        }

        @Override
        public List<WorkflowInstance> getRecoverableInstances(
                String serviceName, @Nullable RecoveryCursor after, int limit) {
            // Scan order is seed order (seed() spaces startedAt), so a test that
            // seeds the poisoned instance first still has it adopted first.
            var page = byWorkflowId.values().stream()
                    .filter(i -> i.status().isActive())
                    .filter(i -> i.serviceName().equals(serviceName))
                    .filter(i -> after == null || after.precedes(i))
                    .sorted(RecoveryCursor.ORDER)
                    .limit(limit)
                    .toList();
            pageSizes.add(page.size());
            return page;
        }

        @Override
        public void updateInstance(WorkflowInstance instance) {
            byWorkflowId.put(instance.workflowId(), instance);
        }

        @Override
        public void appendEvent(WorkflowEvent event) {
            // no event assertions in this suite
        }

        @Override
        public boolean completeInstance(WorkflowInstance terminal, WorkflowEvent terminalEvent) {
            // Test fixture: no CAS and one writer per instance, so composing the two
            // writes is atomic enough here. Event first, so a duplicate writes nothing.
            appendEvent(terminalEvent);
            updateInstance(terminal);
            return true;
        }

        @Override
        public Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int sequenceNumber) {
            return Optional.empty();
        }

        @Override
        public List<WorkflowEvent> getEvents(UUID instanceId) {
            return List.of();
        }

        @Override
        public int deleteFailureEvents(UUID instanceId) {
            return 0;
        }

        @Override
        public void saveSignal(WorkflowSignal signal) {
            // no signals in this suite
        }

        @Override
        public List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName) {
            return List.of();
        }

        @Override
        public boolean markSignalConsumed(UUID signalId) {
            return true;
        }

        @Override
        public void adoptOrphanedSignals(String workflowId, UUID instanceId) {
            // no signals in this suite
        }

        @Override
        public int deleteUnconsumedSignalsForTerminalInstancesBefore(Instant cutoff) {
            return 0;
        }

        @Override
        public PurgeResult purgeTerminalInstancesBefore(WorkflowStatus status, Instant cutoff, int batchSize) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void saveTimer(WorkflowTimer timer) {
            // no timers in this suite
        }

        @Override
        public List<WorkflowTimer> getDueTimers(Instant now, int batchSize) {
            return List.of();
        }

        @Override
        public Optional<WorkflowTimer> findTimer(UUID workflowInstanceId, String timerId) {
            return Optional.empty();
        }

        @Override
        public boolean markTimerFired(UUID timerId) {
            return true;
        }

        @Override
        public boolean markTimerCancelled(UUID timerId) {
            return false;
        }
    }

    /**
     * Records every {@code tryAcquire} key and grants it; {@code heldKeys}
     * reports the keys of {@link #heldElsewhere} workflow ids as held, or
     * throws while {@link #throwOnHeldKeys} is set.
     *
     * <h2>Thread Safety</h2>
     * <p>Safe for the executor's threads and the test thread.
     */
    private static final class RecordingLock implements DistributedLock {

        final List<String> acquiredKeys = new CopyOnWriteArrayList<>();
        final AtomicInteger heldKeysCalls = new AtomicInteger();
        volatile Set<String> heldElsewhere = Set.of();
        volatile boolean throwOnHeldKeys;

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            acquiredKeys.add(key);
            return Optional.of(new LockHandle(key, UUID.randomUUID().toString(), Instant.now().plus(ttl)));
        }

        @Override
        public void release(LockHandle handle) {
            // nothing to free
        }

        @Override
        public boolean renew(LockHandle handle, Duration ttl) {
            return true;
        }

        @Override
        public Set<String> heldKeys(Collection<String> keys) {
            heldKeysCalls.incrementAndGet();
            if (throwOnHeldKeys) {
                throw new LockBackendException("Simulated backend failure", "batch", new RuntimeException());
            }
            return keys.stream().filter(k -> heldElsewhere.contains(idOf(k))).collect(Collectors.toSet());
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return false;
        }
    }
}
