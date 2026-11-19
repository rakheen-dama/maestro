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
import io.b2mash.maestro.core.exception.WorkflowNotLaunchedException;
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
    private final ReleaseOrderCheckingLock checkingLock = new ReleaseOrderCheckingLock();
    private final WorkflowExecutor executor = WorkflowExecutor.builder(store, serializer, SERVICE)
            .lifecycleEventsEnabled(false)
            .distributedLock(checkingLock)
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
        START_LOCK_LOST_BEFORE_IT_REGISTERS_RELAUNCHED_ONCE(0),
        START_LOCK_LOST_BEFORE_IT_REGISTERS_NOT_LAUNCHED(0),
        START_DEFERRAL_REFUSED_FOR_AN_UNREGISTERED_TYPE(0),
        START_LOCK_LOST_BEFORE_IT_REGISTERS_CONVERGED(0),
        RESUME_LOCK_HELD_ELSEWHERE(0),
        RESUME_TURNED_TERMINAL(0),
        RESUME_ADOPTION_CAS_LOST(0),
        RESUME_STORE_FAULT_ON_REREAD(0),
        RESUME_FACTORY_THROWS(0),
        RESUME_THREAD_START_FAILS(0),
        RESUME_LOCK_LOST_BEFORE_IT_REGISTERS(0),
        RESUME_ALREADY_RUNNING_HERE(1),
        RESUME_OBSERVER_ERROR_AFTER_THE_RUN_STARTED(1),
        RESUME_LATE_GRANT_REJECTED_IN_ACQUIRE(1),
        RETRY_NOT_FOUND(0),
        RETRY_NOT_FAILED(0),
        RETRY_REFUSED_AT_THE_LOCK(0),
        RETRY_LOCK_LOST_AFTER_THE_REOPEN_WITH_A_POLLER(0),
        RETRY_LOCK_LOST_AFTER_THE_REOPEN_RELAUNCHED_ONCE(0),
        RETRY_LOCK_LOST_AFTER_THE_REOPEN_NOT_LAUNCHED(0),
        RETRY_LOCK_LOST_AFTER_THE_REOPEN_CONVERGED(0),
        RETRY_REPLACES_THE_STILL_UNWINDING_FAILED_RUN(1),
        RETRY_RELAUNCHES(0),
        RETRY_WHILE_RUNNING_HERE_REFUSED_AT_THE_LOCK(1),
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
                awaitThePromptedPass();
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
                // A recovery poller that registers the type runs here: deferred.
                executor.startRecoveryPoller(registrations(), Duration.ofHours(1));
                loseTheLockOnTheFirstAdmission(false, false);
                start(QuickWorkflow.class, QuickWorkflow::new);
                assertEquals(1, observer.startDeferrals().size(), "the start was deferred to recovery");
                // PR #75 review 4626925634: the prompted pass adopts and runs it;
                // wait for that, so the drain count below is not a race with it.
                assertEquals(WorkflowStatus.COMPLETED, valueWithin(this::status, WorkflowStatus.COMPLETED,
                        Duration.ofSeconds(10)));
                awaitThePromptedPass();
            }
            case START_LOCK_LOST_BEFORE_IT_REGISTERS_RELAUNCHED_ONCE -> {
                // No recovery poller: the start relaunches once as a resume,
                // which joins the registry on its own.
                loseTheLockOnTheFirstAdmission(false, false);
                start(QuickWorkflow.class, QuickWorkflow::new);
                assertEquals(WorkflowStatus.COMPLETED, valueWithin(this::status, WorkflowStatus.COMPLETED,
                        Duration.ofSeconds(5)));
            }
            case START_LOCK_LOST_BEFORE_IT_REGISTERS_NOT_LAUNCHED -> {
                // No recovery poller, and the relaunch cannot take the lock.
                loseTheLockOnTheFirstAdmission(true, false);
                assertThrows(WorkflowNotLaunchedException.class, () -> start(QuickWorkflow.class, QuickWorkflow::new));
                assertEquals(WorkflowStatus.RUNNING, status(), "persisted, left for a node with recovery");
            }
            case START_LOCK_LOST_BEFORE_IT_REGISTERS_CONVERGED -> {
                // No poller; the relaunch cannot take the lock, and another
                // node has finished the row meanwhile: the start returns.
                loseTheLockOnTheFirstAdmission(true, true);
                start(QuickWorkflow.class, QuickWorkflow::new);
                assertEquals(WorkflowStatus.COMPLETED, status());
            }
            case START_DEFERRAL_REFUSED_FOR_AN_UNREGISTERED_TYPE -> {
                executor.startRecoveryPoller(Map.of("OtherWorkflow", new WorkflowRegistration("OtherWorkflow",
                        "default", QuickWorkflow::new, method(QuickWorkflow.class))), Duration.ofHours(1));
                holdElsewhere();
                assertThrows(LockAcquisitionException.class, () -> start(QuickWorkflow.class, QuickWorkflow::new));
                assertTrue(store.getInstance(ID).isEmpty(), "a refused start writes nothing");
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
            case RETRY_WHILE_RUNNING_HERE_REFUSED_AT_THE_LOCK -> {
                // A run of the id is live here and holds its lock: since the
                // retry takes the lock before reopening (P3 review), it is
                // refused there, before any write (was ALREADY_RUNNING_LOCALLY).
                start(ParkWorkflow.class, ParkWorkflow::new);
                assertEquals(WorkflowStatus.WAITING_SIGNAL, valueWithin(this::status, WorkflowStatus.WAITING_SIGNAL,
                        Duration.ofSeconds(5)));
                store.forceStatus(ID, WorkflowStatus.FAILED);
                var failed = store.getInstance(ID).orElseThrow();
                assertThrows(LockAcquisitionException.class, () -> executor.retryWorkflow(ID, registration()));
                assertEquals(failed.version(), store.getInstance(ID).orElseThrow().version(), "nothing written");
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
            case RESUME_LATE_GRANT_REJECTED_IN_ACQUIRE -> {
                // A's backend grant pauses; its key is freed and B acquires and
                // publishes; A's late grant is then rejected (HELD_ELSEWHERE).
                var seeded = seed(WorkflowStatus.RUNNING);
                var granted = new java.util.concurrent.CountDownLatch(1);
                var go = new java.util.concurrent.CountDownLatch(1);
                checkingLock.afterGrant = () -> {
                    granted.countDown();
                    await(go);
                };
                var aLaunched = new AtomicReference<@Nullable Boolean>();
                var a = Thread.ofVirtual().start(() -> aLaunched.set(resume(seeded, QuickWorkflow::new)));
                await(granted);
                lock.forceRelease(KEY);
                assertTrue(executor.resumeWorkflow(store.getInstance(ID).orElseThrow(), ParkWorkflow::new,
                        method(ParkWorkflow.class)), "B acquired, published and launched");
                go.countDown();
                assertTrue(a.join(Duration.ofSeconds(5)));
                assertEquals(Boolean.FALSE, aLaunched.get(), "A's late grant was rejected");
                assertEquals(WorkflowStatus.WAITING_SIGNAL, valueWithin(this::status, WorkflowStatus.WAITING_SIGNAL,
                        Duration.ofSeconds(5)));
            }
            case RETRY_NOT_FOUND -> assertEquals(WorkflowExecutor.RetryOutcome.NOT_FOUND,
                    executor.retryWorkflow(ID, registration()));
            case RETRY_NOT_FAILED -> {
                seed(WorkflowStatus.RUNNING);
                assertEquals(WorkflowExecutor.RetryOutcome.NOT_FAILED, executor.retryWorkflow(ID, registration()));
            }
            case RETRY_REFUSED_AT_THE_LOCK -> {
                runToFailed();
                var failed = store.getInstance(ID).orElseThrow();
                holdElsewhere();
                assertThrows(LockAcquisitionException.class, () -> executor.retryWorkflow(ID, registration()));
                var now = store.getInstance(ID).orElseThrow();
                assertEquals(WorkflowStatus.FAILED, now.status(), "nothing written");
                assertEquals(failed.version(), now.version(), "nothing written");
            }
            case RETRY_LOCK_LOST_AFTER_THE_REOPEN_WITH_A_POLLER -> {
                executor.startRecoveryPoller(registrations(), Duration.ofHours(1));
                runToFailed();
                loseTheLockOnTheFirstAdmission(false, false);
                assertEquals(WorkflowExecutor.RetryOutcome.LOCK_HELD_ELSEWHERE,
                        executor.retryWorkflow(ID, registration()));
                // The prompted pass adopts the reopened row (its own membership).
                assertEquals(WorkflowStatus.COMPLETED, valueWithin(this::status, WorkflowStatus.COMPLETED,
                        Duration.ofSeconds(10)));
            }
            case RETRY_LOCK_LOST_AFTER_THE_REOPEN_RELAUNCHED_ONCE -> {
                runToFailed();
                loseTheLockOnTheFirstAdmission(false, false);
                assertEquals(WorkflowExecutor.RetryOutcome.RETRIED, executor.retryWorkflow(ID, registration()));
                assertEquals(WorkflowStatus.FAILED, valueWithin(this::status, WorkflowStatus.FAILED,
                        Duration.ofSeconds(5)), "the relaunched run ran (and failed again)");
            }
            case RETRY_LOCK_LOST_AFTER_THE_REOPEN_NOT_LAUNCHED -> {
                runToFailed();
                loseTheLockOnTheFirstAdmission(true, false);
                assertThrows(WorkflowNotLaunchedException.class, () -> executor.retryWorkflow(ID, registration()));
                assertEquals(WorkflowStatus.RUNNING, status(), "reopened, left for a node with recovery");
            }
            case RETRY_LOCK_LOST_AFTER_THE_REOPEN_CONVERGED -> {
                runToFailed();
                loseTheLockOnTheFirstAdmission(true, true);
                assertEquals(WorkflowExecutor.RetryOutcome.LOCK_HELD_ELSEWHERE,
                        executor.retryWorkflow(ID, registration()));
            }
            case RETRY_REPLACES_THE_STILL_UNWINDING_FAILED_RUN -> {
                // The failed run is held in workflowFailed — FAILED written, its
                // lock lost, still registered — while a Retry takes the lock,
                // reopens and launches: two distinct memberships of wf-x.
                var held = new java.util.concurrent.CountDownLatch(1);
                var go = new java.util.concurrent.CountDownLatch(1);
                observer.onFailed = () -> {
                    lock.forceRelease(KEY);
                    assertTrue(valueWithin(() -> observer.lockLost().contains(ID), true, Duration.ofSeconds(5)));
                    held.countDown();
                    await(go);
                };
                start(FailingWorkflow.class, FailingWorkflow::new);
                await(held);
                var parking = new WorkflowRegistration("QuickWorkflow", "default", ParkWorkflow::new,
                        method(ParkWorkflow.class));
                assertEquals(WorkflowExecutor.RetryOutcome.RETRIED, executor.retryWorkflow(ID, parking));
                assertEquals(WorkflowStatus.WAITING_SIGNAL, valueWithin(this::status, WorkflowStatus.WAITING_SIGNAL,
                        Duration.ofSeconds(5)));
                assertEquals(2, executor.lifecycle().remaining().stream()
                        .filter(m -> m.isRun() && m.name().equals(ID)).count(), "the old and the new run");
                go.countDown();
                assertEquals(1, valueWithin(() -> executor.lifecycle().remaining().size(), 1, Duration.ofSeconds(5)),
                        "the old run left; the new one stays");
                assertTrue(executor.isRunning(ID), "the old run's cleanup left the new run's entry alone");
            }
            case RETRY_RELAUNCHES -> {
                runToFailed();
                assertEquals(WorkflowExecutor.RetryOutcome.RETRIED, executor.retryWorkflow(ID, registration()));
                assertEquals(WorkflowStatus.FAILED, valueWithin(this::status, WorkflowStatus.FAILED,
                        Duration.ofSeconds(5)));
            }
        }
    }

    /**
     * On the next launch admission only, frees wf-x's lock behind the
     * launch's back and waits until the renewer reports it lost — and, when
     * {@code keepItElsewhere}, takes it as another node, so no relaunch can;
     * when {@code finishedElsewhere}, that node has also finished the row.
     */
    private void loseTheLockOnTheFirstAdmission(boolean keepItElsewhere, boolean finishedElsewhere) {
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        executor.afterLaunchAdmission = () -> {
            if (first.compareAndSet(true, false)) {
                lock.forceRelease(KEY);
                assertTrue(valueWithin(() -> observer.lockLost().contains(ID), true, Duration.ofSeconds(5)),
                        "the renewer reported the lock lost");
                if (keepItElsewhere) {
                    holdElsewhere();
                }
                if (finishedElsewhere) {
                    store.forceStatus(ID, WorkflowStatus.COMPLETED);
                }
            }
        };
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /**
     * PR #75 review 4626925634: waits until the recovery poller has served
     * the deferral's prompt and is idle again — no pass in progress, none
     * pending — so no pass holds a membership when the test shuts down.
     *
     * <p>Not "at least two passes": a prompt requested before the poller's
     * first wait is served by the first pass itself (the full pass due at
     * start "serves the prompt too"), so there may be only one. Waiting for a
     * second pass that never comes was this test's 1-in-30 flake.
     */
    private void awaitThePromptedPass() {
        assertTrue(valueWithin(() -> !observer.recoveryPasses().isEmpty() && executor.isRecoveryPollerIdle(), true,
                Duration.ofSeconds(10)), "the prompted recovery pass ran and the poller is idle");
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

    /**
     * Thrown by {@link ThrowingObserver}: a {@code VirtualMachineError}, which
     * observer containment (P2, Issue 81) lets through, as
     * {@code HandleScopedLockRaceTest} does — an {@code AssertionError} would
     * now be contained and never reach the launcher.
     */
    static final class ObserverError extends InternalError {
        ObserverError() {
            super("observer fails (test)");
        }
    }

    /** Records like its parent; throws {@link #onResumed} once from {@code workflowResumed} when set. */
    static final class ThrowingObserver extends RecordingEngineObserver {
        volatile @Nullable Error onResumed;
        /** Run once, on the run's thread, from the next {@code workflowFailed}. */
        volatile @Nullable Runnable onFailed;

        @Override
        public void workflowFailed(io.b2mash.maestro.core.observe.WorkflowInfo w, String exceptionType) {
            super.workflowFailed(w, exceptionType);
            var hook = onFailed;
            if (hook != null) {
                onFailed = null;
                hook.run();
            }
        }

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
        /** Run once, after the backend's next grant and before it is returned. */
        volatile @Nullable Runnable afterGrant;

        /** Which launch's membership took each handle: the one the acquiring thread belongs to. */
        private final java.util.Map<LockHandle, LifecycleRegistry.Membership> owners =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            var granted = lock.tryAcquire(key, ttl);
            var node = self.get();
            if (granted.isPresent() && node != null) {
                var mine = node.lifecycle().callerRuns();
                if (!mine.isEmpty()) {
                    // The innermost launch on this thread (a relaunch-once is
                    // a resume joined while the start's membership is still in).
                    owners.put(granted.get(), mine.getLast());
                }
            }
            var hook = afterGrant;
            if (hook != null && granted.isPresent()) {
                afterGrant = null;
                hook.run();
            }
            return granted;
        }

        @Override
        public void release(LockHandle handle) {
            var node = self.get();
            // PR #75 review 4626925649: the exact membership that took this
            // handle must still be registered — not just any run of the id
            // (in the late-grant case, B is registered when A gives back its grant).
            var owner = owners.remove(handle);
            if (handle.key().equals(KEY) && node != null) {
                var remaining = node.lifecycle().remaining();
                var member = owner != null ? remaining.contains(owner)
                        : remaining.stream().anyMatch(m -> m.isRun() && m.name().equals(ID));
                if (!member) {
                    leftBeforeRelease.add(handle + (owner != null ? " of " + owner : ""));
                }
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
