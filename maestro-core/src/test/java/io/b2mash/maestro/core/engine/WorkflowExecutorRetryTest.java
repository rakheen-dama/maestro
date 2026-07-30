package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.OptimisticLockException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.LockHandle;
import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.core.spi.TaskMessage;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@code WorkflowExecutor.retryWorkflow} — the engine half of the
 * dashboard's Retry button.
 *
 * <h2>The headline case</h2>
 * <p>The button exists for a transient activity failure: an external
 * dependency was down, the activity exhausted its retry policy, the workflow
 * is {@code FAILED} with a memoized {@code ACTIVITY_FAILED} — and now the
 * dependency is back. This suite's first test <b>is</b> the RED that proved
 * the original design (§3.1) inert: relaunching in replay mode alone
 * deterministically re-throws the stored failure forever, because replay
 * prefers a memoized outcome — including a memoized failure — over
 * re-execution. {@code deleteFailureEvents} is what makes the button honest.
 */
@DisplayName("Retrying a FAILED workflow clears its failure memo, marks it RUNNING, and relaunches it")
class WorkflowExecutorRetryTest {

    private VersionedInMemoryStore store;
    private RecordingMessaging messaging;
    private PayloadSerializer serializer;
    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        store = new VersionedInMemoryStore();
        messaging = new RecordingMessaging();
        serializer = new PayloadSerializer(JsonMapper.builder().build());
        executor = new WorkflowExecutor(
                store, null, messaging, null, serializer, "test-service",
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, Duration.ofSeconds(30),
                true, Duration.ofSeconds(5), Duration.ofMillis(200));
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    // ── Headline: the failed step genuinely re-executes ────────────────

    @Test
    @DisplayName("a workflow that exhausted retry re-executes the failed step and completes")
    void retryOfExhaustedActivity_reExecutesFailedStepAndCompletes() throws Exception {
        var activityImpl = new FlakyThreeStepActivitiesImpl();
        var proxy = new ActivityProxyFactory().createProxy(
                FlakyThreeStepActivities.class, activityImpl, store, null, messaging,
                RetryPolicy.noRetry(), Duration.ofSeconds(30), serializer, new RetryExecutor());
        var workflow = new ThreeStepWorkflow(proxy);
        var method = ThreeStepWorkflow.class.getMethod("run");
        var registration = new WorkflowRegistration("ThreeStepWorkflow", "default", workflow, method);

        executor.startWorkflow("retry-headline", "ThreeStepWorkflow", "default", null, workflow, method);
        awaitStatus("retry-headline", WorkflowStatus.FAILED);

        assertEquals(1, activityImpl.oneCount.get());
        assertEquals(1, activityImpl.twoCount.get());
        assertEquals(1, activityImpl.threeCount.get(), "step three ran once and exhausted its retry budget");
        var instanceId = store.getInstance("retry-headline").orElseThrow().id();
        assertTrue(hasEvent(instanceId, EventType.ACTIVITY_FAILED),
                "the failure must be memoized before retry — this is what the fix has to clear");
        assertFalse(hasEvent(instanceId, EventType.WORKFLOW_COMPLETED));

        // Fix the fault — the test seam a real operator's "dependency is back
        // up" corresponds to — THEN retry.
        activityImpl.faultFixed.set(true);
        var outcome = executor.retryWorkflow("retry-headline", registration);

        assertEquals(WorkflowExecutor.RetryOutcome.RETRIED, outcome);
        awaitStatus("retry-headline", WorkflowStatus.COMPLETED);

        assertEquals(1, activityImpl.oneCount.get(), "step one must replay from its memo, not re-run");
        assertEquals(1, activityImpl.twoCount.get(), "step two must replay from its memo, not re-run");
        assertEquals(2, activityImpl.threeCount.get(),
                "step three must be RE-INVOKED live — this is the counter incrementing past its pre-retry value");

        var finalInstance = store.getInstance("retry-headline").orElseThrow();
        assertEquals(WorkflowStatus.COMPLETED, finalInstance.status());
        assertTrue(hasEvent(instanceId, EventType.WORKFLOW_COMPLETED),
                "WORKFLOW_COMPLETED must genuinely appear in the event log — not swallowed by a sequence collision");
        assertFalse(hasEvent(instanceId, EventType.ACTIVITY_FAILED),
                "the stale failure memo must be gone");
        assertFalse(hasEvent(instanceId, EventType.WORKFLOW_FAILED),
                "the stale terminal failure memo must be gone");

        awaitLifecycleCount(LifecycleEventType.WORKFLOW_RETRIED, 1,
                "WORKFLOW_RETRIED must be published so the dashboard reflects the retry immediately");
    }

    // ── Idempotency / validation (design §3.3) ─────────────────────────

    @Test
    @DisplayName("retrying a RUNNING workflow is an acknowledged no-op")
    void retryOfRunningWorkflow_isNotFailedNoOp() throws Exception {
        var workflow = new ParkedWorkflow();
        var method = ParkedWorkflow.class.getMethod("run");
        executor.startWorkflow("retry-running", "ParkedWorkflow", "default", null, workflow, method);
        awaitStatus("retry-running", WorkflowStatus.WAITING_SIGNAL);
        var before = store.getInstance("retry-running").orElseThrow();

        var registration = new WorkflowRegistration("ParkedWorkflow", "default", workflow, method);
        assertEquals(WorkflowExecutor.RetryOutcome.NOT_FAILED,
                executor.retryWorkflow("retry-running", registration));

        var after = store.getInstance("retry-running").orElseThrow();
        assertEquals(before.status(), after.status());
        assertEquals(before.version(), after.version(), "a no-op must not write the row");
    }

    @Test
    @DisplayName("retrying a COMPLETED workflow is an acknowledged no-op")
    void retryOfCompletedWorkflow_isNotFailedNoOp() throws Exception {
        var workflow = new ImmediateWorkflow();
        var method = ImmediateWorkflow.class.getMethod("run", String.class);
        executor.startWorkflow("retry-completed", "ImmediateWorkflow", "default", "seed", workflow, method);
        awaitStatus("retry-completed", WorkflowStatus.COMPLETED);
        var before = store.getInstance("retry-completed").orElseThrow();

        var registration = new WorkflowRegistration("ImmediateWorkflow", "default", workflow, method);
        assertEquals(WorkflowExecutor.RetryOutcome.NOT_FAILED,
                executor.retryWorkflow("retry-completed", registration));

        var after = store.getInstance("retry-completed").orElseThrow();
        assertEquals(WorkflowStatus.COMPLETED, after.status());
        assertEquals(before.version(), after.version());
    }

    @Test
    @DisplayName("retrying a TERMINATED workflow is an acknowledged no-op")
    void retryOfTerminatedWorkflow_isNotFailedNoOp() throws Exception {
        var workflow = new ParkedWorkflow();
        var method = ParkedWorkflow.class.getMethod("run");
        executor.startWorkflow("retry-terminated", "ParkedWorkflow", "default", null, workflow, method);
        awaitStatus("retry-terminated", WorkflowStatus.WAITING_SIGNAL);
        executor.terminateWorkflow("retry-terminated", "stop it");
        var before = store.getInstance("retry-terminated").orElseThrow();

        var registration = new WorkflowRegistration("ParkedWorkflow", "default", workflow, method);
        assertEquals(WorkflowExecutor.RetryOutcome.NOT_FAILED,
                executor.retryWorkflow("retry-terminated", registration));

        var after = store.getInstance("retry-terminated").orElseThrow();
        assertEquals(WorkflowStatus.TERMINATED, after.status());
        assertEquals(before.version(), after.version(), "terminate must not be resurrected by a stray retry");
    }

    @Test
    @DisplayName("retrying an unknown workflow ID is an acknowledged no-op")
    void retryOfUnknownWorkflow_isNotFound() {
        var registration = new WorkflowRegistration(
                "Anything", "default", new Object(), Object.class.getMethods()[0]);
        assertEquals(WorkflowExecutor.RetryOutcome.NOT_FOUND,
                executor.retryWorkflow("no-such-workflow", registration));
        assertEquals(0, messaging.events.size(), "a no-op must publish nothing");
    }

    @Test
    @DisplayName("a redelivered retry converges instead of relaunching twice")
    void duplicateRetry_secondDeliverySeesNonFailed_noOp() throws Exception {
        var activityImpl = new FlakyThreeStepActivitiesImpl();
        var proxy = new ActivityProxyFactory().createProxy(
                FlakyThreeStepActivities.class, activityImpl, store, null, messaging,
                RetryPolicy.noRetry(), Duration.ofSeconds(30), serializer, new RetryExecutor());
        var workflow = new ThreeStepWorkflow(proxy);
        var method = ThreeStepWorkflow.class.getMethod("run");
        var registration = new WorkflowRegistration("ThreeStepWorkflow", "default", workflow, method);

        executor.startWorkflow("retry-dup", "ThreeStepWorkflow", "default", null, workflow, method);
        awaitStatus("retry-dup", WorkflowStatus.FAILED);

        activityImpl.faultFixed.set(true);
        assertEquals(WorkflowExecutor.RetryOutcome.RETRIED,
                executor.retryWorkflow("retry-dup", registration));
        awaitStatus("retry-dup", WorkflowStatus.COMPLETED);

        // The redelivered command re-reads the now-COMPLETED row.
        assertEquals(WorkflowExecutor.RetryOutcome.NOT_FAILED,
                executor.retryWorkflow("retry-dup", registration));
        assertEquals(2, activityImpl.threeCount.get(), "exactly one relaunch — the duplicate must not re-invoke it");
    }

    @Test
    @DisplayName("two genuinely concurrent retries produce exactly one relaunch")
    void concurrentRetry_exactlyOneLaunch() throws Exception {
        var activityImpl = new FlakyThreeStepActivitiesImpl();
        var proxy = new ActivityProxyFactory().createProxy(
                FlakyThreeStepActivities.class, activityImpl, store, null, messaging,
                RetryPolicy.noRetry(), Duration.ofSeconds(30), serializer, new RetryExecutor());
        var workflow = new ThreeStepWorkflow(proxy);
        var method = ThreeStepWorkflow.class.getMethod("run");
        var registration = new WorkflowRegistration("ThreeStepWorkflow", "default", workflow, method);

        executor.startWorkflow("retry-concurrent", "ThreeStepWorkflow", "default", null, workflow, method);
        awaitStatus("retry-concurrent", WorkflowStatus.FAILED);
        activityImpl.faultFixed.set(true);

        var pool = Executors.newFixedThreadPool(2);
        try {
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            var results = new CopyOnWriteArrayList<Object>();
            Runnable attempt = () -> {
                ready.countDown();
                try {
                    go.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                try {
                    results.add(executor.retryWorkflow("retry-concurrent", registration));
                } catch (OptimisticLockException e) {
                    results.add(e);
                }
            };
            var f1 = pool.submit(attempt);
            var f2 = pool.submit(attempt);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            f1.get(5, TimeUnit.SECONDS);
            f2.get(5, TimeUnit.SECONDS);

            var retriedCount = results.stream()
                    .filter(r -> r == WorkflowExecutor.RetryOutcome.RETRIED).count();
            assertEquals(1, retriedCount, "exactly one of the two racing attempts must win: " + results);
            for (var r : results) {
                assertTrue(r == WorkflowExecutor.RetryOutcome.RETRIED
                                || r == WorkflowExecutor.RetryOutcome.NOT_FAILED
                                || r instanceof OptimisticLockException,
                        "the loser must be a clean no-op or a transient conflict, not silent corruption: " + r);
            }
        } finally {
            pool.shutdown();
        }

        awaitStatus("retry-concurrent", WorkflowStatus.COMPLETED);
        assertEquals(2, activityImpl.threeCount.get(),
                "step three ran once live before the failure and exactly once more on the single winning retry");
    }

    // ── Cross-node lock contention ──────────────────────────────────────

    @Test
    @DisplayName("retry CASes to RUNNING even when another node holds the instance lock, and leaves it for recovery")
    void retryWithLockHeldElsewhere_leavesInstanceRunningForRecovery() throws Exception {
        var lock = new MapLock();
        var lockedExecutor = new WorkflowExecutor(store, lock, messaging, null, serializer, "test-service");
        try {
            var workflow = new ParkedWorkflow();
            var method = ParkedWorkflow.class.getMethod("run");

            // Seed a FAILED instance directly — this test is about lock
            // contention on the relaunch, not about how the workflow failed.
            var instanceId = UUID.randomUUID();
            var failedInstance = WorkflowInstance.builder()
                    .id(instanceId)
                    .workflowId("retry-lock")
                    .runId(UUID.randomUUID())
                    .workflowType("ParkedWorkflow")
                    .taskQueue("default")
                    .status(WorkflowStatus.FAILED)
                    .serviceName("test-service")
                    .startedAt(Instant.now())
                    .updatedAt(Instant.now())
                    .completedAt(Instant.now())
                    .version(0)
                    .build();
            store.createInstance(failedInstance);
            store.appendEvent(activityEvent(instanceId, 1, EventType.WORKFLOW_FAILED, null));

            // Another node holds the instance lock throughout.
            lock.holdForeign(WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX + "workflow:retry-lock");

            var registration = new WorkflowRegistration("ParkedWorkflow", "default", workflow, method);
            var outcome = lockedExecutor.retryWorkflow("retry-lock", registration);

            assertEquals(WorkflowExecutor.RetryOutcome.LOCK_HELD_ELSEWHERE, outcome);
            assertEquals(WorkflowStatus.RUNNING, store.getInstance("retry-lock").orElseThrow().status(),
                    "the CAS must still land — the instance is left RUNNING for the recovery poller");
        } finally {
            lockedExecutor.shutdown();
        }
    }

    // ── Compensated saga guard ───────────────────────────────────────────

    @Test
    @DisplayName("retrying a FAILED workflow whose saga already compensated is a guarded no-op")
    void retryOfCompensatedSaga_isCompensatedNotRetryableNoOp() throws Exception {
        var workflow = new ParkedWorkflow();
        var method = ParkedWorkflow.class.getMethod("run");
        var registration = new WorkflowRegistration("ParkedWorkflow", "default", workflow, method);

        // Seed a FAILED instance whose event log carries a completed
        // compensation — the state retryWorkflow would see after a saga ran
        // its compensations and the workflow terminated FAILED.
        var instanceId = UUID.randomUUID();
        var failedInstance = WorkflowInstance.builder()
                .id(instanceId)
                .workflowId("retry-compensated")
                .runId(UUID.randomUUID())
                .workflowType("ParkedWorkflow")
                .taskQueue("default")
                .status(WorkflowStatus.FAILED)
                .serviceName("test-service")
                .startedAt(Instant.now())
                .updatedAt(Instant.now())
                .completedAt(Instant.now())
                .version(0)
                .build();
        store.createInstance(failedInstance);
        store.appendEvent(activityEvent(instanceId, 1, EventType.ACTIVITY_FAILED, "one-failed"));
        store.appendEvent(activityEvent(instanceId, 2, EventType.COMPENSATION_STARTED, null));
        store.appendEvent(activityEvent(instanceId, 3, EventType.COMPENSATION_COMPLETED, null));
        store.appendEvent(activityEvent(instanceId, 4, EventType.WORKFLOW_FAILED, null));

        var outcome = executor.retryWorkflow("retry-compensated", registration);

        assertEquals(WorkflowExecutor.RetryOutcome.COMPENSATED_NOT_RETRYABLE, outcome);

        var after = store.getInstance("retry-compensated").orElseThrow();
        assertEquals(WorkflowStatus.FAILED, after.status(), "the guard must not touch the instance status");
        assertEquals(failedInstance.version(), after.version(), "the guard must not write the row at all");
        assertTrue(hasEvent(instanceId, EventType.ACTIVITY_FAILED),
                "deleteFailureEvents must NOT have been applied — the guard fires before it");
        assertTrue(hasEvent(instanceId, EventType.WORKFLOW_FAILED),
                "deleteFailureEvents must NOT have been applied — the guard fires before it");
        assertEquals(0, messaging.events.size(), "a guarded no-op must publish nothing");
    }

    // ── Crash-window coverage ────────────────────────────────────────────

    @Test
    @DisplayName("crash after CAS but before launch: the recovery poller adopts the RUNNING instance")
    void casWithoutLaunch_recoveryPollerAdopts() throws Exception {
        var activityImpl = new FlakyThreeStepActivitiesImpl();
        activityImpl.faultFixed.set(true);
        var proxy = new ActivityProxyFactory().createProxy(
                FlakyThreeStepActivities.class, activityImpl, store, null, messaging,
                RetryPolicy.noRetry(), Duration.ofSeconds(30), serializer, new RetryExecutor());
        var workflow = new ThreeStepWorkflow(proxy);
        var method = ThreeStepWorkflow.class.getMethod("run");

        // Seed a FAILED instance directly (as if a prior run failed and this
        // process then crashed), then apply exactly steps 2-3 of retryWorkflow
        // by hand — deleteFailureEvents + CAS to RUNNING — WITHOUT launching,
        // modelling a crash between the CAS and the relaunch.
        var instanceId = UUID.randomUUID();
        var failed = WorkflowInstance.builder()
                .id(instanceId)
                .workflowId("retry-crash-cas")
                .runId(UUID.randomUUID())
                .workflowType("ThreeStepWorkflow")
                .taskQueue("default")
                .status(WorkflowStatus.FAILED)
                .serviceName("test-service")
                .startedAt(Instant.now())
                .updatedAt(Instant.now())
                .completedAt(Instant.now())
                .version(0)
                .build();
        store.createInstance(failed);
        store.appendEvent(activityEvent(instanceId, 1, EventType.ACTIVITY_COMPLETED, "one"));
        store.appendEvent(activityEvent(instanceId, 2, EventType.ACTIVITY_COMPLETED, "two"));
        store.appendEvent(activityEvent(instanceId, 3, EventType.ACTIVITY_FAILED, "three-failed"));
        store.appendEvent(activityEvent(instanceId, 4, EventType.WORKFLOW_FAILED, null));

        store.deleteFailureEvents(instanceId);
        store.updateInstance(failed.toBuilder()
                .status(WorkflowStatus.RUNNING)
                .completedAt(null)
                .updatedAt(Instant.now())
                .version(1)
                .build());
        // No launch here — this is the "crash before relaunch" window.

        assertEquals(1, executor.recoverWorkflows(
                Map.of("ThreeStepWorkflow", new WorkflowRegistration("ThreeStepWorkflow", "default", workflow, method))));

        awaitStatus("retry-crash-cas", WorkflowStatus.COMPLETED);
        assertEquals(0, activityImpl.oneCount.get(), "seeded ACTIVITY_COMPLETED events replay without an impl call");
        assertEquals(0, activityImpl.twoCount.get());
        assertEquals(1, activityImpl.threeCount.get(), "the memo-free step executes live exactly once on adoption");
    }

    @Test
    @DisplayName("crash after deleting failure memos but before the CAS: still FAILED, harmless, next retry proceeds")
    void deletedMemosOnStillFailed_isHarmless() throws Exception {
        var activityImpl = new FlakyThreeStepActivitiesImpl();
        var proxy = new ActivityProxyFactory().createProxy(
                FlakyThreeStepActivities.class, activityImpl, store, null, messaging,
                RetryPolicy.noRetry(), Duration.ofSeconds(30), serializer, new RetryExecutor());
        var workflow = new ThreeStepWorkflow(proxy);
        var method = ThreeStepWorkflow.class.getMethod("run");
        var registration = new WorkflowRegistration("ThreeStepWorkflow", "default", workflow, method);

        executor.startWorkflow("retry-crash-delete", "ThreeStepWorkflow", "default", null, workflow, method);
        awaitStatus("retry-crash-delete", WorkflowStatus.FAILED);
        var instanceId = store.getInstance("retry-crash-delete").orElseThrow().id();

        // Model "crashed after delete, before CAS": the memos are already gone,
        // but the row is still FAILED.
        var firstDeleteCount = store.deleteFailureEvents(instanceId);
        assertTrue(firstDeleteCount > 0);
        assertEquals(WorkflowStatus.FAILED, store.getInstance("retry-crash-delete").orElseThrow().status(),
                "the row is untouched by a delete that happened before any CAS");

        activityImpl.faultFixed.set(true);
        var outcome = executor.retryWorkflow("retry-crash-delete", registration);

        assertEquals(WorkflowExecutor.RetryOutcome.RETRIED, outcome,
                "retryWorkflow's own deleteFailureEvents call finds nothing left to delete and proceeds anyway");
        awaitStatus("retry-crash-delete", WorkflowStatus.COMPLETED);
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private void awaitStatus(String workflowId, WorkflowStatus expected) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(expected, store.getInstance(workflowId).orElseThrow().status()));
    }

    private boolean hasEvent(UUID instanceId, EventType type) {
        return store.getEvents(instanceId).stream().anyMatch(e -> e.eventType() == type);
    }

    private io.b2mash.maestro.core.model.WorkflowEvent activityEvent(
            UUID instanceId, int seq, EventType type, String stepValue) {
        var payload = stepValue != null ? serializer.serialize(stepValue) : null;
        return new io.b2mash.maestro.core.model.WorkflowEvent(
                UUID.randomUUID(), instanceId, seq, type, "step-" + seq, payload, Instant.now());
    }

    private List<WorkflowLifecycleEvent> lifecycleEvents(LifecycleEventType type) {
        return messaging.events.stream().filter(e -> e.eventType() == type).toList();
    }

    private void awaitLifecycleCount(LifecycleEventType type, int expected, String message) {
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20))
                .untilAsserted(() -> assertEquals(expected, lifecycleEvents(type).size(), message));
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertEquals(expected, lifecycleEvents(type).size(), message));
    }

    // ── Fixtures ───────────────────────────────────────────────────────

    @Activity
    public interface FlakyThreeStepActivities {
        String stepOne(String input);
        String stepTwo(String input);
        String stepThree(String input);
    }

    /** Step three fails until {@code faultFixed} flips — the "dependency is back" test seam. */
    public static class FlakyThreeStepActivitiesImpl implements FlakyThreeStepActivities {
        final AtomicBoolean faultFixed = new AtomicBoolean(false);
        final AtomicInteger oneCount = new AtomicInteger();
        final AtomicInteger twoCount = new AtomicInteger();
        final AtomicInteger threeCount = new AtomicInteger();

        @Override
        public String stepOne(String input) {
            oneCount.incrementAndGet();
            return "one";
        }

        @Override
        public String stepTwo(String input) {
            twoCount.incrementAndGet();
            return "two";
        }

        @Override
        public String stepThree(String input) {
            threeCount.incrementAndGet();
            if (!faultFixed.get()) {
                throw new RuntimeException("downstream dependency unavailable");
            }
            return "three";
        }
    }

    @DurableWorkflow(name = "ThreeStepWorkflow")
    public static class ThreeStepWorkflow {
        private final FlakyThreeStepActivities activities;

        public ThreeStepWorkflow(FlakyThreeStepActivities activities) {
            this.activities = activities;
        }

        /** @return the concatenation of the three steps' results */
        @WorkflowMethod
        public String run() {
            var one = activities.stepOne("in");
            var two = activities.stepTwo("in");
            var three = activities.stepThree("in");
            return one + two + three;
        }
    }

    /** Parks on a signal nothing ever sends. */
    @DurableWorkflow(name = "ParkedWorkflow")
    public static class ParkedWorkflow {
        /** @return the signal payload, if the run ever gets that far */
        @WorkflowMethod
        public String run() {
            return WorkflowContext.current().awaitSignal("never-sent", String.class, Duration.ofSeconds(30));
        }
    }

    /** Completes immediately. */
    @DurableWorkflow(name = "ImmediateWorkflow")
    public static class ImmediateWorkflow {
        /**
         * @param input the seed
         * @return the seed, unchanged
         */
        @WorkflowMethod
        public String run(String input) {
            return input;
        }
    }

    // ── Recording messaging ────────────────────────────────────────────

    private static class RecordingMessaging implements WorkflowMessaging {

        final CopyOnWriteArrayList<WorkflowLifecycleEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void publishTask(String taskQueue, TaskMessage message) {
        }

        @Override
        public void publishSignal(String serviceName, SignalMessage message) {
        }

        @Override
        public void publishLifecycleEvent(WorkflowLifecycleEvent event) {
            events.add(event);
        }

        @Override
        public void subscribe(String taskQueue, Consumer<TaskMessage> handler) {
        }

        @Override
        public void subscribeSignals(String serviceName, Consumer<SignalMessage> handler) {
        }
    }

    // ── Map-backed DistributedLock ─────────────────────────────────────

    private static class MapLock implements DistributedLock {

        final ConcurrentHashMap<String, LockHandle> locks = new ConcurrentHashMap<>();

        void holdForeign(String key) {
            locks.put(key, new LockHandle(key, "foreign-token", Instant.now().plusSeconds(60)));
        }

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            var handle = new LockHandle(key, UUID.randomUUID().toString(), Instant.now().plus(ttl));
            var existing = locks.putIfAbsent(key, handle);
            return existing != null ? Optional.empty() : Optional.of(handle);
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
}
