package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.CountingStore;
import io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SharedLock;
import io.b2mash.maestro.core.exception.LockAcquisitionException;
import io.b2mash.maestro.core.exception.StoreUnavailableException;
import io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException;
import io.b2mash.maestro.core.exception.WorkflowInstantiationException;
import io.b2mash.maestro.core.logcapture.CapturingSlf4jProvider;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.SERVICE;
import static io.b2mash.maestro.core.engine.RecoveryCapacityFixtures.valueWithin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pilot readiness P1, task 1, risk 1: every way a launch can end — handed to
 * its run thread or not, normally or by a throw — leaves the executor's
 * lifecycle registry. A path that never leaves is a ghost member every later
 * shutdown would wait its whole deadline for.
 */
@Timeout(60)
class LaunchOutcomeRegistryTest {

    private static final String ID = "wf-x";
    private static final String KEY = WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX + "workflow:" + ID;

    private final CountingStore store = new CountingStore();
    private final SharedLock lock = new SharedLock();
    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private final ThrowingObserver observer = new ThrowingObserver();
    /** Releases of wf-x's lock made when no run of wf-x was a registry member any more. */
    private final List<String> leftBeforeRelease = new CopyOnWriteArrayList<>();
    private final AtomicReference<@Nullable WorkflowExecutor> self = new AtomicReference<>();
    private final WorkflowExecutor executor = WorkflowExecutor.builder(store, serializer, SERVICE)
            .lifecycleEventsEnabled(false)
            .distributedLock(new ReleaseOrderCheckingLock())
            .observer(observer)
            // renewed every 100 ms, so a forced loss is reported at once
            .instanceLockTtl(Duration.ofMillis(300))
            .shutdownTimeout(Duration.ofSeconds(10))
            .build();

    {
        self.set(executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    /** How the launch ends, and what is legitimately still registered afterwards. */
    enum Outcome {
        START_RUNS_TO_COMPLETION(0),
        START_DEFERRED_LOCK_HELD_ELSEWHERE(0),
        START_REFUSED_NO_RECOVERY_POLLER(0),
        START_ROW_ALREADY_EXISTS(0),
        START_FACTORY_THROWS(0),
        START_THREAD_START_FAILS(0),
        START_RUN_THREAD_DIES_BEFORE_ITS_BODY(0),
        START_LOCK_LOST_BEFORE_IT_REGISTERS(0),
        RESUME_LOCK_HELD_ELSEWHERE(0),
        RESUME_TURNED_TERMINAL(0),
        RESUME_ADOPTION_CAS_LOST(0),
        RESUME_STORE_FAULT_ON_REREAD(0),
        RESUME_FACTORY_THROWS(0),
        RESUME_THREAD_START_FAILS(0),
        RESUME_LOCK_LOST_BEFORE_IT_REGISTERS(0),
        RESUME_ALREADY_RUNNING_HERE(1),
        RESUME_OBSERVER_ERROR_AFTER_THE_RUN_STARTED(1),
        RETRY_NOT_FOUND(0),
        RETRY_NOT_FAILED(0),
        RETRY_LOCK_HELD_ELSEWHERE(0),
        RETRY_RELAUNCHES(0),
        RETRY_ALREADY_RUNNING_LOCALLY(1),
        RETRY_OBSERVER_ERROR_AFTER_THE_RUN_STARTED(1);

        final int stillRegistered;

        Outcome(int stillRegistered) {
            this.stillRegistered = stillRegistered;
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Outcome.class)
    void everyLaunchOutcomeLeavesTheRegistryEmpty(Outcome outcome) throws Exception {
        launch(outcome);

        assertEquals(outcome.stillRegistered, valueWithin(() -> executor.lifecycle().remaining().size(),
                        outcome.stillRegistered, Duration.ofSeconds(5)),
                () -> "still registered after " + outcome + ": " + executor.lifecycle().remaining());
        try (var logs = CapturingSlf4jProvider.watch(WorkflowExecutor.class)) {
            var start = System.nanoTime();
            executor.shutdown();
            var tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(tookMs < 3_000, "the drain waited " + tookMs + " ms after " + outcome);
            assertTrue(logs.records().stream().anyMatch(r -> r.message().startsWith("Maestro shutdown finished in")
                            && r.message().contains(outcome.stillRegistered + " run(s) in flight at start, "
                            + "0 still running")),
                    logs.records().toString());
        }
        assertEquals(0, executor.lifecycle().remaining().size());
        assertEquals(LifecycleRegistry.State.CLOSED, executor.lifecycle().state());
        assertEquals(List.of(), leftBeforeRelease,
                "a launch or run left the registry before its instance lock was released");
    }

    private void launch(Outcome outcome) throws Exception {
        switch (outcome) {
            case START_RUNS_TO_COMPLETION -> {
                start(QuickWorkflow.class, QuickWorkflow::new);
                assertEquals(WorkflowStatus.COMPLETED, valueWithin(this::status, WorkflowStatus.COMPLETED,
                        Duration.ofSeconds(5)));
            }
            case START_DEFERRED_LOCK_HELD_ELSEWHERE -> {
                executor.startRecoveryPoller(registrations(), Duration.ofHours(1));
                holdElsewhere();
                start(QuickWorkflow.class, QuickWorkflow::new);
                assertEquals(1, observer.startDeferrals().size());
            }
            case START_REFUSED_NO_RECOVERY_POLLER -> {
                holdElsewhere();
                assertThrows(LockAcquisitionException.class, () -> start(QuickWorkflow.class, QuickWorkflow::new));
            }
            case START_ROW_ALREADY_EXISTS -> {
                seed(WorkflowStatus.RUNNING);
                assertThrows(WorkflowAlreadyExistsException.class, () -> start(QuickWorkflow.class, QuickWorkflow::new));
            }
            case START_FACTORY_THROWS -> assertThrows(WorkflowInstantiationException.class,
                    () -> start(QuickWorkflow.class, () -> {
                        throw new IllegalStateException("factory fails (test)");
                    }));
            case START_THREAD_START_FAILS -> {
                executor.beforeRunThreadStart = () -> {
                    throw new IllegalStateException("thread start fails (test)");
                };
                assertThrows(IllegalStateException.class, () -> start(QuickWorkflow.class, QuickWorkflow::new));
            }
            case START_RUN_THREAD_DIES_BEFORE_ITS_BODY -> {
                executor.beforeRunBody = () -> {
                    throw new IllegalStateException("the run thread dies before its body (test)");
                };
                start(QuickWorkflow.class, QuickWorkflow::new);
                assertEquals(0, valueWithin(lock::heldCount, 0, Duration.ofSeconds(5)));
            }
            case START_LOCK_LOST_BEFORE_IT_REGISTERS -> {
                executor.afterLaunchAdmission = () -> {
                    lock.forceRelease(KEY);
                    assertTrue(valueWithin(() -> observer.lockLost().contains(ID), true, Duration.ofSeconds(5)),
                            "the renewer reported the lock lost");
                };
                start(QuickWorkflow.class, QuickWorkflow::new);
                assertEquals(1, observer.startDeferrals().size(), "the start was deferred to recovery");
            }
            case RESUME_LOCK_HELD_ELSEWHERE -> {
                var seeded = seed(WorkflowStatus.RUNNING);
                holdElsewhere();
                assertFalse(resume(seeded, QuickWorkflow::new));
            }
            case RESUME_TURNED_TERMINAL -> {
                var seeded = seed(WorkflowStatus.RUNNING);
                store.getInstanceHooks.put(ID, actual -> actual.map(i -> i.toBuilder()
                        .status(WorkflowStatus.COMPLETED).build()));
                assertFalse(resume(seeded, QuickWorkflow::new));
            }
            case RESUME_ADOPTION_CAS_LOST -> {
                var seeded = seed(WorkflowStatus.RUNNING);
                store.failNextUpdates(1);
                assertFalse(resume(seeded, QuickWorkflow::new));
            }
            case RESUME_STORE_FAULT_ON_REREAD -> {
                var seeded = seed(WorkflowStatus.RUNNING);
                store.getInstanceHooks.put(ID, _ -> {
                    throw new StoreUnavailableException("store down (test)", new IllegalStateException());
                });
                assertThrows(StoreUnavailableException.class, () -> resume(seeded, QuickWorkflow::new));
            }
            case RESUME_FACTORY_THROWS -> {
                var seeded = seed(WorkflowStatus.RUNNING);
                assertThrows(WorkflowInstantiationException.class, () -> resume(seeded, () -> {
                    throw new IllegalStateException("factory fails (test)");
                }));
            }
            case RESUME_THREAD_START_FAILS -> {
                var seeded = seed(WorkflowStatus.RUNNING);
                executor.beforeRunThreadStart = () -> {
                    throw new IllegalStateException("thread start fails (test)");
                };
                assertThrows(IllegalStateException.class, () -> resume(seeded, QuickWorkflow::new));
            }
            case RESUME_LOCK_LOST_BEFORE_IT_REGISTERS -> {
                var seeded = seed(WorkflowStatus.RUNNING);
                executor.afterLaunchAdmission = () -> {
                    lock.forceRelease(KEY);
                    assertTrue(valueWithin(() -> observer.lockLost().contains(ID), true, Duration.ofSeconds(5)),
                            "the renewer reported the lock lost");
                };
                assertFalse(resume(seeded, QuickWorkflow::new));
            }
            case RESUME_ALREADY_RUNNING_HERE -> {
                start(ParkWorkflow.class, ParkWorkflow::new);
                assertEquals(WorkflowStatus.WAITING_SIGNAL, valueWithin(this::status, WorkflowStatus.WAITING_SIGNAL,
                        Duration.ofSeconds(5)));
                assertFalse(resume(store.getInstance(ID).orElseThrow(), QuickWorkflow::new));
            }
            case RESUME_OBSERVER_ERROR_AFTER_THE_RUN_STARTED -> {
                // The run thread has started (and parks); the observer's Error
                // reaches the launcher afterwards. The run owns its membership.
                var seeded = seed(WorkflowStatus.RUNNING);
                observer.onResumed = new ObserverError();
                assertThrows(ObserverError.class, () -> executor.resumeWorkflow(seeded, ParkWorkflow::new,
                        method(ParkWorkflow.class)));
                assertEquals(WorkflowStatus.WAITING_SIGNAL, valueWithin(this::status, WorkflowStatus.WAITING_SIGNAL,
                        Duration.ofSeconds(5)));
            }
            case RETRY_ALREADY_RUNNING_LOCALLY -> {
                start(ParkWorkflow.class, ParkWorkflow::new);
                assertEquals(WorkflowStatus.WAITING_SIGNAL, valueWithin(this::status, WorkflowStatus.WAITING_SIGNAL,
                        Duration.ofSeconds(5)));
                store.forceStatus(ID, WorkflowStatus.FAILED);
                assertEquals(WorkflowExecutor.RetryOutcome.ALREADY_RUNNING_LOCALLY,
                        executor.retryWorkflow(ID, registration()));
            }
            case RETRY_OBSERVER_ERROR_AFTER_THE_RUN_STARTED -> {
                runToFailed();
                observer.onResumed = new ObserverError();
                var parking = new WorkflowRegistration("QuickWorkflow", "default", ParkWorkflow::new,
                        method(ParkWorkflow.class));
                assertThrows(ObserverError.class, () -> executor.retryWorkflow(ID, parking));
                assertEquals(WorkflowStatus.WAITING_SIGNAL, valueWithin(this::status, WorkflowStatus.WAITING_SIGNAL,
                        Duration.ofSeconds(5)));
            }
            case RETRY_NOT_FOUND -> assertEquals(WorkflowExecutor.RetryOutcome.NOT_FOUND,
                    executor.retryWorkflow(ID, registration()));
            case RETRY_NOT_FAILED -> {
                seed(WorkflowStatus.RUNNING);
                assertEquals(WorkflowExecutor.RetryOutcome.NOT_FAILED, executor.retryWorkflow(ID, registration()));
            }
            case RETRY_LOCK_HELD_ELSEWHERE -> {
                runToFailed();
                holdElsewhere();
                assertEquals(WorkflowExecutor.RetryOutcome.LOCK_HELD_ELSEWHERE,
                        executor.retryWorkflow(ID, registration()));
            }
            case RETRY_RELAUNCHES -> {
                runToFailed();
                assertEquals(WorkflowExecutor.RetryOutcome.RETRIED, executor.retryWorkflow(ID, registration()));
                assertEquals(WorkflowStatus.FAILED, valueWithin(this::status, WorkflowStatus.FAILED,
                        Duration.ofSeconds(5)));
            }
        }
    }

    /** Runs {@code wf-x} to {@code FAILED} on this executor and waits until that run has left. */
    private void runToFailed() throws Exception {
        start(FailingWorkflow.class, FailingWorkflow::new);
        assertEquals(WorkflowStatus.FAILED, valueWithin(this::status, WorkflowStatus.FAILED, Duration.ofSeconds(5)));
        assertEquals(0, valueWithin(() -> executor.lifecycle().remaining().size(), 0, Duration.ofSeconds(5)));
    }

    private void holdElsewhere() {
        assertTrue(lock.tryAcquire(KEY, Duration.ofHours(1)).isPresent(), "another node holds the lock");
    }

    private WorkflowStatus status() {
        return store.getInstance(ID).map(WorkflowInstance::status).orElse(null);
    }

    private void start(Class<?> type, Supplier<Object> factory) throws Exception {
        executor.startWorkflow(ID, "QuickWorkflow", "default", null, factory, method(type));
    }

    private boolean resume(WorkflowInstance instance, Supplier<Object> factory) {
        return executor.resumeWorkflow(instance, factory, method(QuickWorkflow.class));
    }

    private WorkflowInstance seed(WorkflowStatus status) {
        var startedAt = Instant.now().minusSeconds(60);
        return store.createInstance(WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId(ID)
                .runId(UUID.randomUUID())
                .workflowType("QuickWorkflow")
                .taskQueue("default")
                .status(status)
                .serviceName(SERVICE)
                .eventSequence(0)
                .startedAt(startedAt)
                .updatedAt(startedAt)
                .version(0)
                .build());
    }

    private static WorkflowRegistration registration() {
        return new WorkflowRegistration("QuickWorkflow", "default", FailingWorkflow::new, method(FailingWorkflow.class));
    }

    private static Map<String, WorkflowRegistration> registrations() {
        return Map.of("QuickWorkflow", new WorkflowRegistration("QuickWorkflow", "default", QuickWorkflow::new,
                method(QuickWorkflow.class)));
    }

    private static Method method(Class<?> type) {
        try {
            return type.getMethod("run");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Thrown by {@link ThrowingObserver}: an {@code Error}, which the executor's emit does not contain. */
    static final class ObserverError extends Error {
        ObserverError() {
            super("observer fails (test)");
        }
    }

    /** Records like its parent; throws {@link #onResumed} once from {@code workflowResumed} when set. */
    static final class ThrowingObserver extends RecordingEngineObserver {
        volatile @Nullable Error onResumed;

        @Override
        public void workflowResumed(io.b2mash.maestro.core.observe.WorkflowInfo w) {
            super.workflowResumed(w);
            var error = onResumed;
            if (error != null) {
                onResumed = null;
                throw error;
            }
        }
    }

    /**
     * The test's {@link SharedLock}, behind a check on every release of
     * wf-x's lock: a run of wf-x must still be a member of the registry then
     * — join before the lock, leave after its release.
     */
    final class ReleaseOrderCheckingLock implements DistributedLock {
        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            return lock.tryAcquire(key, ttl);
        }

        @Override
        public void release(LockHandle handle) {
            var node = self.get();
            if (handle.key().equals(KEY) && node != null && node.lifecycle().remaining().stream()
                    .noneMatch(m -> m.isRun() && m.name().equals(ID))) {
                leftBeforeRelease.add(handle.toString());
            }
            lock.release(handle);
        }

        @Override
        public boolean renew(LockHandle handle, Duration ttl) {
            return lock.renew(handle, ttl);
        }

        @Override
        public Set<LockHandle> renewAll(Collection<LockHandle> handles, Duration ttl) {
            return lock.renewAll(handles, ttl);
        }

        @Override
        public Set<String> heldKeys(Collection<String> keys) {
            return lock.heldKeys(keys);
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return true;
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

    /** Fails at once. */
    @DurableWorkflow(name = "QuickWorkflow")
    public static final class FailingWorkflow {
        /** @return never */
        @WorkflowMethod
        public String run() {
            throw new IllegalStateException("the workflow fails (test)");
        }
    }

    /** Parks on a signal that never comes. */
    @DurableWorkflow(name = "QuickWorkflow")
    public static final class ParkWorkflow {
        /** @return the signal's payload */
        @WorkflowMethod
        public String run() {
            return WorkflowContext.current().awaitSignal("go", String.class, Duration.ofHours(1));
        }
    }
}
