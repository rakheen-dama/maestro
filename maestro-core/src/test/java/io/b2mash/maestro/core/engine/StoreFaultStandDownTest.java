package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.Compensate;
import io.b2mash.maestro.core.annotation.Saga;
import io.b2mash.maestro.core.context.RunCancellation;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.SignalTimeoutException;
import io.b2mash.maestro.core.exception.StoreUnavailableException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.AbandonReason;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the executor end of the store-fault stand-down (spec R1–R3, R5): a
 * {@code StoreUnavailableException} on an active run — directly, or latched
 * as {@link RunCancellation.Cause#STORE_UNAVAILABLE} by the store-fault guard
 * and raised at the next stop point — abandons the local run as
 * {@link AbandonReason#STORE_UNAVAILABLE}. Nothing is recorded, nothing is
 * compensated, the instance stays active, and ordinary recovery resumes it
 * once the store is back. A store error the workflow's own data caused is
 * still a workflow failure.
 *
 * <p>Every assertion is on a collected value — the observer's abandon list,
 * the instance row, the event log, invocation counters — never on an
 * {@code await} condition alone.
 */
@DisplayName("A store fault on an active run abandons it — never FAILED, never compensated")
class StoreFaultStandDownTest {

    /** Zero retry waits for the tests that exhaust the guard's retries. */
    private static final List<Duration> NO_WAIT = List.of(Duration.ZERO, Duration.ZERO);
    private static final Duration BOUND = Duration.ofSeconds(5);

    private FaultyStore faulty;
    /** {@link #faulty} behind the guard with zero waits; the executor's own wrap leaves it as is. */
    private WorkflowStore guarded;
    private PayloadSerializer serializer;
    private RecordingEngineObserver observer;
    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        faulty = new FaultyStore();
        guarded = StoreFaultGuard.wrap(faulty, NO_WAIT);
        serializer = new PayloadSerializer(JsonMapper.builder().build());
        observer = new RecordingEngineObserver();
        executor = newExecutor(guarded);
    }

    @AfterEach
    void tearDown() {
        faulty.heal();
        executor.shutdown();
    }

    private WorkflowExecutor newExecutor(WorkflowStore store) {
        return newExecutor(store, Duration.ofSeconds(30));
    }

    private WorkflowExecutor newExecutor(WorkflowStore store, Duration wakeRecheckInterval) {
        return new WorkflowExecutor(store, null, null, null, serializer, "test-service",
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX,
                WorkflowInstanceLockManager.DEFAULT_LOCK_TTL,
                false, WorkflowExecutor.DEFAULT_SHUTDOWN_TIMEOUT, wakeRecheckInterval, observer);
    }

    private <T> T proxy(Class<T> iface, T impl, WorkflowStore store, RetryPolicy policy) {
        return new ActivityProxyFactory().createProxy(iface, impl, store, null, null, policy,
                Duration.ofSeconds(30), serializer, new RetryExecutor());
    }

    // ── Tests ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("an exhausted fault on an activity's outcome append abandons the run; recovery completes it")
    void outcomeAppendFaultAbandonsInsteadOfFailing() throws Exception {
        faulty.failWhen("appendEvent", completedOutcomeOf("step2"));
        var impl = new CountingSteps();
        var workflow = new TwoStepSaga(proxy(StepActivities.class, impl, guarded, RetryPolicy.noRetry()));
        var method = TwoStepSaga.class.getMethod("run", String.class);

        var instanceId = executor.startWorkflow("sf-outcome", "TwoStepSaga", "default", "in",
                () -> workflow, method);
        awaitRunEnded("sf-outcome");

        assertAll(
                () -> assertEquals(List.of(AbandonReason.STORE_UNAVAILABLE), abandonReasons()),
                () -> assertEquals(WorkflowStatus.RUNNING, status("sf-outcome"),
                        "a store fault never finalises the instance"),
                () -> assertEquals(0, count(instanceId, EventType.WORKFLOW_FAILED)),
                () -> assertEquals(0, countCompensationEvents(instanceId),
                        "a store fault never opens a compensation phase"),
                () -> assertEquals(0, impl.undoStep1.get(),
                        "no compensation may run for work that never failed"),
                () -> assertTrue(observer.failed().isEmpty(), "workflowFailed must not fire"));

        faulty.heal();
        var regs = Map.of("TwoStepSaga", new WorkflowRegistration("TwoStepSaga", "default",
                () -> workflow, method));
        assertEquals(1, executor.recoverWorkflows(regs));
        awaitRunEnded("sf-outcome");

        assertAll(
                () -> assertEquals(WorkflowStatus.COMPLETED, status("sf-outcome")),
                () -> assertEquals(1, impl.step1.get(), "step 1 is replayed from its memo"),
                () -> assertEquals(2, impl.step2.get(),
                        "step 2's side effect happened but its outcome never landed, so it runs again"),
                () -> assertEquals(0, impl.undoStep1.get()));
    }

    @Test
    @DisplayName("a single blip on the outcome append is retried in place and absorbed")
    void singleBlipOnOutcomeAppendIsAbsorbed() throws Exception {
        // The raw store, so this also proves the activity proxy guards its
        // own store (one production retry wait of 200 ms).
        executor.shutdown();
        executor = newExecutor(faulty);
        faulty.failNext("appendEvent", 1);
        var impl = new CountingSteps();
        var workflow = new OneStep(proxy(StepActivities.class, impl, faulty, RetryPolicy.noRetry()));

        var instanceId = executor.startWorkflow("sf-blip", "OneStep", "default", "in", () -> workflow,
                OneStep.class.getMethod("run", String.class));
        awaitRunEnded("sf-blip");

        assertAll(
                () -> assertEquals(WorkflowStatus.COMPLETED, status("sf-blip")),
                () -> assertEquals(1, impl.step1.get(), "the activity ran once; only its append was retried"),
                () -> assertEquals(1, count(instanceId, EventType.ACTIVITY_COMPLETED),
                        "the retried append landed exactly once"),
                () -> assertEquals(List.of(), abandonReasons()));
    }

    @Test
    @DisplayName("a workflow that catches the memo-lookup fault cannot run its fallback")
    void swallowedFaultDoesNotRunTheFallback() throws Exception {
        faulty.failWhen("getEventBySequence", args -> (Integer) args[1] == 1);
        var impl = new CountingSteps();
        var workflow = new PrimaryWithFallback(proxy(StepActivities.class, impl, guarded, RetryPolicy.noRetry()));
        var method = PrimaryWithFallback.class.getMethod("run", String.class);

        executor.startWorkflow("sf-fallback", "PrimaryWithFallback", "default", "in",
                () -> workflow, method);
        awaitRunEnded("sf-fallback");

        assertAll(
                () -> assertEquals(0, impl.step2.get(), "the fallback must not run"),
                () -> assertEquals(List.of(AbandonReason.STORE_UNAVAILABLE), abandonReasons()),
                () -> assertEquals(WorkflowStatus.RUNNING, status("sf-fallback")));

        faulty.heal();
        assertEquals(1, executor.recoverWorkflows(Map.of("PrimaryWithFallback",
                new WorkflowRegistration("PrimaryWithFallback", "default", () -> workflow, method))));
        awaitRunEnded("sf-fallback");

        assertAll(
                () -> assertEquals(WorkflowStatus.COMPLETED, status("sf-fallback")),
                () -> assertEquals(1, impl.step1.get(), "primary"),
                () -> assertEquals(0, impl.step2.get(), "fallback"));
    }

    @Test
    @DisplayName("a workflow that catches the memo-lookup fault cannot record a side effect once the store heals")
    void swallowedFaultCannotRecordASideEffect() throws Exception {
        // Exactly the guard's three attempts fail at seq 1, then the store is healthy.
        var lookupsAtSeq1 = new AtomicInteger();
        faulty.failWhen("getEventBySequence", args ->
                (Integer) args[1] == 1 && lookupsAtSeq1.incrementAndGet() <= 3);
        var impl = new CountingSteps();
        var workflow = new PrimaryThenSideEffectFallback(
                proxy(StepActivities.class, impl, guarded, RetryPolicy.noRetry()));
        var method = PrimaryThenSideEffectFallback.class.getMethod("run", String.class);

        var instanceId = executor.startWorkflow("sf-side-effect", "PrimaryThenSideEffectFallback", "default",
                "in", () -> workflow, method);
        awaitRunEnded("sf-side-effect");

        assertAll(
                () -> assertEquals(3, lookupsAtSeq1.get(), "the fault must have exhausted the retries"),
                () -> assertEquals(0, count(instanceId, EventType.SIDE_EFFECT),
                        "a latched run must not record history down the path it swallowed into"),
                () -> assertEquals(0, impl.step2.get(), "the fallback must not run"),
                () -> assertEquals(List.of(AbandonReason.STORE_UNAVAILABLE), abandonReasons()),
                () -> assertEquals(WorkflowStatus.RUNNING, status("sf-side-effect")));

        faulty.heal();
        assertEquals(1, executor.recoverWorkflows(Map.of("PrimaryThenSideEffectFallback",
                new WorkflowRegistration("PrimaryThenSideEffectFallback", "default", () -> workflow, method))));
        awaitRunEnded("sf-side-effect");

        assertAll(
                () -> assertEquals(WorkflowStatus.COMPLETED, status("sf-side-effect")),
                () -> assertEquals(1, impl.step1.get(), "primary"),
                () -> assertEquals(0, impl.step2.get(), "fallback"));
    }

    @Test
    @DisplayName("a store fault on the WAITING_SIGNAL status write abandons the run")
    void statusWriteFaultWhileParkingAbandons() throws Exception {
        // Raw store: updateInstance is never retried, so no wait is incurred.
        executor.shutdown();
        executor = newExecutor(faulty);
        faulty.failWhen("updateInstance", once(args ->
                ((WorkflowInstance) args[0]).status() == WorkflowStatus.WAITING_SIGNAL));
        var workflow = new AwaitsSignal(false);

        var instanceId = executor.startWorkflow("sf-park", "AwaitsSignal", "default", "in",
                () -> workflow, AwaitsSignal.class.getMethod("run", String.class));
        awaitRunEnded("sf-park");

        assertAll(
                () -> assertEquals(List.of(AbandonReason.STORE_UNAVAILABLE), abandonReasons()),
                () -> assertEquals(0, count(instanceId, EventType.WORKFLOW_FAILED)),
                () -> assertEquals(WorkflowStatus.RUNNING, status("sf-park")));
    }

    @Test
    @DisplayName("a swallowed status-write fault still stops the run — the executor guards its own store")
    void swallowedParkFaultIsNotCompleted() throws Exception {
        // Raw store handed to the executor: only the executor's own wrap can
        // latch this fault, which the workflow catches and carries on past.
        executor.shutdown();
        executor = newExecutor(faulty);
        faulty.failWhen("updateInstance", once(args ->
                ((WorkflowInstance) args[0]).status() == WorkflowStatus.WAITING_SIGNAL));
        var workflow = new AwaitsSignal(true);

        var instanceId = executor.startWorkflow("sf-park-swallow", "AwaitsSignal", "default", "in",
                () -> workflow, AwaitsSignal.class.getMethod("run", String.class));
        awaitRunEnded("sf-park-swallow");

        assertAll(
                () -> assertEquals(WorkflowStatus.RUNNING, status("sf-park-swallow"),
                        "a run whose store fault was swallowed must not complete"),
                () -> assertEquals(List.of(AbandonReason.STORE_UNAVAILABLE), abandonReasons()),
                () -> assertEquals(0, count(instanceId, EventType.WORKFLOW_COMPLETED)));
    }

    @Test
    @DisplayName("a store error caused by the workflow's data still fails the workflow")
    void dataErrorStillFailsTheWorkflow() throws Exception {
        faulty.failNext("appendEvent", 1, () -> new RuntimeException("22001"));
        var impl = new CountingSteps();
        var workflow = new OneStep(proxy(StepActivities.class, impl, guarded, RetryPolicy.noRetry()));

        var instanceId = executor.startWorkflow("sf-data", "OneStep", "default", "in", () -> workflow,
                OneStep.class.getMethod("run", String.class));
        awaitRunEnded("sf-data");

        assertAll(
                () -> assertEquals(WorkflowStatus.FAILED, status("sf-data")),
                () -> assertEquals(1, count(instanceId, EventType.WORKFLOW_FAILED)),
                () -> assertEquals(List.of(), abandonReasons()));
    }

    @Test
    @DisplayName("a workflow that swallows a persistent append fault and returns is not completed")
    void workflowThatSwallowsAndReturnsIsNotCompleted() throws Exception {
        faulty.failWhen("appendEvent", completedOutcomeOf("step1"));
        var impl = new CountingSteps();
        var workflow = new SwallowsAndReturns(proxy(StepActivities.class, impl, guarded, RetryPolicy.noRetry()));

        var instanceId = executor.startWorkflow("sf-swallow", "SwallowsAndReturns", "default", "in",
                () -> workflow, SwallowsAndReturns.class.getMethod("run", String.class));
        awaitRunEnded("sf-swallow");

        assertAll(
                () -> assertEquals(WorkflowStatus.RUNNING, status("sf-swallow")),
                () -> assertEquals(0, count(instanceId, EventType.WORKFLOW_COMPLETED)),
                () -> assertEquals(List.of(AbandonReason.STORE_UNAVAILABLE), abandonReasons()));
    }

    @Test
    @DisplayName("a store fault latched during an activity's retry backoff abandons — no ACTIVITY_FAILED")
    void storeFaultDuringRetryBackoffAbandons() throws Exception {
        var impl = new FlakyOnce();
        var policy = RetryPolicy.builder()
                .maxAttempts(3)
                .initialInterval(Duration.ofSeconds(30))
                .maxInterval(Duration.ofSeconds(30))
                .backoffMultiplier(1.0)
                .build();
        var workflow = new CallsFlaky(proxy(FlakyActivities.class, impl, guarded, policy));

        var instanceId = executor.startWorkflow("sf-backoff", "CallsFlaky", "default", "in",
                () -> workflow, CallsFlaky.class.getMethod("run", String.class));
        assertTrue(impl.firstAttemptFailed.await(5, TimeUnit.SECONDS));
        // Let the workflow thread enter its 30 s backoff wait. If the latch
        // lands before it does, the retry loop's post-attempt check raises the
        // same signal instead; either way nothing may be recorded.
        Thread.sleep(200);
        impl.cancellation.get().requestStoreUnavailable("appendEvent: injected outage (sibling call)");
        awaitRunEnded("sf-backoff");

        assertAll(
                () -> assertEquals(List.of(AbandonReason.STORE_UNAVAILABLE), abandonReasons()),
                () -> assertEquals(1, impl.attempts.get(), "no further attempt once the store is down"),
                () -> assertEquals(0, count(instanceId, EventType.ACTIVITY_FAILED),
                        "a store fault is not the activity's failure"),
                () -> assertEquals(0, count(instanceId, EventType.WORKFLOW_FAILED)),
                () -> assertEquals(WorkflowStatus.RUNNING, status("sf-backoff")),
                () -> assertTrue(observer.activityFailed().isEmpty(),
                        "the observer must not count an activity failure"));
    }

    // ── Compensation and parallel branches ───────────────────────────

    @Nested
    @DisplayName("Compensation and parallel branches")
    class SagaAndParallel {

        @Test
        @DisplayName("a fault on a compensation's outcome append leaves the run COMPENSATING; recovery finishes the unwind")
        void faultOnCompensationAppendLeavesCompensatingAndRecovers() throws Exception {
            // LIFO unwind: refund (activity), notifyCustomer (manual Runnable), release (activity).
            faulty.failWhen("appendEvent", args -> args[0] instanceof WorkflowEvent e
                    && e.eventType() == EventType.COMPENSATION_STEP_COMPLETED
                    && e.stepName() != null && e.stepName().endsWith(".refund"));
            var impl = new CountingSagaSteps();
            var notify = new AtomicInteger();
            var workflow = new ThreeStepSaga(proxy(SagaSteps.class, impl, guarded, RetryPolicy.noRetry()), notify);
            var method = ThreeStepSaga.class.getMethod("run", String.class);

            var instanceId = executor.startWorkflow("sf-saga", "ThreeStepSaga", "default", "in",
                    () -> workflow, method);
            awaitRunEnded("sf-saga");

            assertAll(
                    () -> assertEquals(List.of(AbandonReason.STORE_UNAVAILABLE), abandonReasons()),
                    () -> assertEquals(WorkflowStatus.COMPENSATING, status("sf-saga")),
                    () -> assertEquals(0, count(instanceId, EventType.COMPENSATION_STEP_FAILED),
                            "a store fault is not a failed reversal"),
                    () -> assertEquals(0, count(instanceId, EventType.WORKFLOW_FAILED)),
                    () -> assertEquals(1, impl.refund.get(), "the first compensation ran; only its outcome was lost"),
                    () -> assertEquals(0, notify.get(),
                            "no further compensation may run once the store fault is latched"),
                    () -> assertEquals(0, impl.release.get(),
                            "no further compensation may run once the store fault is latched"));

            faulty.heal();
            assertEquals(1, executor.recoverWorkflows(Map.of("ThreeStepSaga",
                    new WorkflowRegistration("ThreeStepSaga", "default", () -> workflow, method))));
            awaitRunEnded("sf-saga");

            var completedSteps = faulty.getEvents(instanceId).stream()
                    .filter(e -> e.eventType() == EventType.COMPENSATION_STEP_COMPLETED)
                    .map(WorkflowEvent::stepName)
                    .sorted()
                    .toList();
            assertAll(
                    () -> assertEquals(WorkflowStatus.FAILED, status("sf-saga")),
                    () -> assertEquals(3, completedSteps.size(), "one outcome per compensable step: " + completedSteps),
                    () -> assertEquals(3, completedSteps.stream().distinct().count(), completedSteps::toString),
                    () -> assertEquals(0, count(instanceId, EventType.COMPENSATION_STEP_FAILED)),
                    () -> assertEquals(1, impl.refund.get(), "refund is replayed from its memo, not re-run"),
                    () -> assertEquals(1, notify.get(), "notifyCustomer ran exactly once"),
                    () -> assertEquals(1, impl.release.get(), "release ran exactly once"),
                    () -> assertEquals(1, impl.ship.get(), "the failed step is replayed, not re-run"));
        }

        @Test
        @DisplayName("a fault inside a parallel branch abandons the run, outranking a sibling's plain failure")
        void faultInsideParallelBranchAbandonsTheRun() throws Exception {
            // Branch 1 (step2) loses its outcome append; branch 0 fails with a
            // plain business exception on the first run, so index order alone
            // would surface the sibling's failure, not the store fault.
            faulty.failWhen("appendEvent", completedOutcomeOf("step2"));
            var impl = new CountingSteps();
            var workflow = new ParallelSaga(proxy(StepActivities.class, impl, guarded, RetryPolicy.noRetry()));
            var method = ParallelSaga.class.getMethod("run", String.class);

            var instanceId = executor.startWorkflow("sf-parallel", "ParallelSaga", "default", "in",
                    () -> workflow, method);
            awaitRunEnded("sf-parallel");

            assertAll(
                    () -> assertTrue(workflow.parallelThrew.get()
                                    instanceof StoreUnavailableException,
                            "parallel() must surface the store fault, not the sibling's failure: "
                                    + workflow.parallelThrew.get()),
                    () -> assertEquals(List.of(AbandonReason.STORE_UNAVAILABLE), abandonReasons()),
                    () -> assertEquals(WorkflowStatus.RUNNING, status("sf-parallel")),
                    () -> assertEquals(0, count(instanceId, EventType.WORKFLOW_FAILED)),
                    () -> assertEquals(0, countCompensationEvents(instanceId)),
                    () -> assertEquals(0, impl.undoStep1.get(), "no compensation may run"),
                    () -> assertTrue(observer.failed().isEmpty(), "workflowFailed must not fire"));

            faulty.heal();
            assertEquals(1, executor.recoverWorkflows(Map.of("ParallelSaga",
                    new WorkflowRegistration("ParallelSaga", "default", () -> workflow, method))));
            awaitRunEnded("sf-parallel");

            assertAll(
                    () -> assertEquals(WorkflowStatus.COMPLETED, status("sf-parallel")),
                    () -> assertEquals(1, impl.step1.get(), "step 1 is replayed from its memo"),
                    () -> assertEquals(2, impl.step2.get(),
                            "branch 1's side effect happened but its outcome never landed, so it runs again"),
                    () -> assertEquals(0, impl.undoStep1.get()));
        }
    }

    // ── Terminal write ────────────────────────────────────────────────

    @Nested
    @DisplayName("A store fault on the terminal write")
    class TerminalWrite {

        @Test
        @DisplayName("on the COMPLETED write abandons as STORE_UNAVAILABLE; recovery completes with every step replayed")
        void completedWriteStoreFaultAbandonsAsStoreUnavailable() throws Exception {
            faulty.failAlways("completeInstance");
            var impl = new CountingSteps();
            var workflow = new TwoStepSaga(proxy(StepActivities.class, impl, guarded, RetryPolicy.noRetry()));
            var method = TwoStepSaga.class.getMethod("run", String.class);

            var instanceId = executor.startWorkflow("sf-terminal", "TwoStepSaga", "default", "in",
                    () -> workflow, method);
            awaitRunEnded("sf-terminal");

            assertAll(
                    () -> assertEquals(List.of(AbandonReason.STORE_UNAVAILABLE), abandonReasons(),
                            "a database outage on the terminal write is transient, not an operator problem"),
                    () -> assertEquals(WorkflowStatus.RUNNING, status("sf-terminal")),
                    () -> assertEquals(0, count(instanceId, EventType.WORKFLOW_COMPLETED)),
                    () -> assertEquals(0, impl.undoStep1.get()));

            faulty.heal();
            assertEquals(1, executor.recoverWorkflows(Map.of("TwoStepSaga",
                    new WorkflowRegistration("TwoStepSaga", "default", () -> workflow, method))));
            awaitRunEnded("sf-terminal");

            assertAll(
                    () -> assertEquals(WorkflowStatus.COMPLETED, status("sf-terminal")),
                    () -> assertEquals(1, impl.step1.get(), "replayed, not re-executed"),
                    () -> assertEquals(1, impl.step2.get(), "replayed, not re-executed"),
                    () -> assertEquals(0, impl.undoStep1.get()));
        }

        @Test
        @DisplayName("on the FAILED write abandons as STORE_UNAVAILABLE; recovery records FAILED")
        void failedWriteStoreFaultAbandonsAsStoreUnavailable() throws Exception {
            faulty.failAlways("completeInstance");
            var method = AlwaysFails.class.getMethod("run", String.class);

            executor.startWorkflow("sf-terminal-failed", "AlwaysFails", "default", "in",
                    AlwaysFails::new, method);
            awaitRunEnded("sf-terminal-failed");

            assertAll(
                    () -> assertEquals(List.of(AbandonReason.STORE_UNAVAILABLE), abandonReasons()),
                    () -> assertEquals(WorkflowStatus.RUNNING, status("sf-terminal-failed")));

            faulty.heal();
            assertEquals(1, executor.recoverWorkflows(Map.of("AlwaysFails",
                    new WorkflowRegistration("AlwaysFails", "default", AlwaysFails::new, method))));
            awaitRunEnded("sf-terminal-failed");

            assertEquals(WorkflowStatus.FAILED, status("sf-terminal-failed"));
        }

        @Test
        @DisplayName("any other failure of the FAILED write is still TERMINAL_WRITE_FAILED")
        void nonStoreFailedWriteFailureStaysTerminalWriteFailed() throws Exception {
            // A pin, not new behaviour: only a StoreUnavailableException is re-routed.
            faulty.failNext("completeInstance", 1, () -> new RuntimeException("22001 (injected data error)"));

            executor.startWorkflow("sf-terminal-other", "AlwaysFails", "default", "in",
                    AlwaysFails::new, AlwaysFails.class.getMethod("run", String.class));
            awaitRunEnded("sf-terminal-other");

            assertAll(
                    () -> assertEquals(List.of(AbandonReason.TERMINAL_WRITE_FAILED), abandonReasons()),
                    () -> assertEquals(WorkflowStatus.RUNNING, status("sf-terminal-other")));
        }
    }

    // ── Await deadline ────────────────────────────────────────────────

    @Nested
    @DisplayName("A store fault in the await-deadline decision")
    class AwaitDeadline {

        @BeforeEach
        void shortRecheck() {
            executor.shutdown();
            executor = newExecutor(guarded, Duration.ofMillis(100));
        }

        @Test
        @DisplayName("on the deadline row's self-fire does not abandon the run; the await times out as usual")
        void selfFireFaultDoesNotAbandon() throws Exception {
            faulty.failAlways("markTimerFired");

            var instanceId = executor.startWorkflow("sf-deadline-fire", "TimesOut", "default", "in",
                    TimesOut::new, TimesOut.class.getMethod("run", String.class));
            awaitRunEnded("sf-deadline-fire");

            assertAll(
                    () -> assertTrue(faulty.calls("markTimerFired") >= 1, "the self-fire must have been attempted"),
                    () -> assertEquals(List.of(), abandonReasons()),
                    () -> assertEquals(1, count(instanceId, EventType.SIGNAL_TIMEOUT)),
                    () -> assertEquals(WorkflowStatus.COMPLETED, status("sf-deadline-fire")));
        }

        @Test
        @DisplayName("on the post-deadline signal re-read does not abandon the run; the await times out as usual")
        void postDeadlineReReadFaultDoesNotAbandon() throws Exception {
            // The first signal read after the self-fire is the post-deadline re-read.
            var reReadFaults = new AtomicInteger();
            faulty.failWhen("getUnconsumedSignals", args ->
                    faulty.calls("markTimerFired") > 0 && reReadFaults.getAndIncrement() < 1);

            var instanceId = executor.startWorkflow("sf-deadline-reread", "TimesOut", "default", "in",
                    TimesOut::new, TimesOut.class.getMethod("run", String.class));
            awaitRunEnded("sf-deadline-reread");

            assertAll(
                    () -> assertTrue(reReadFaults.get() >= 1, "the re-read must have faulted"),
                    () -> assertEquals(List.of(), abandonReasons()),
                    () -> assertEquals(1, count(instanceId, EventType.SIGNAL_TIMEOUT)),
                    () -> assertEquals(WorkflowStatus.COMPLETED, status("sf-deadline-reread")));
        }

        @Test
        @DisplayName("on the deadline row's cancel after consuming a signal does not abandon the run")
        void cancelFaultAfterConsumingSignalDoesNotAbandon() throws Exception {
            faulty.failAlways("markTimerCancelled");

            var instanceId = executor.startWorkflow("sf-deadline-cancel", "AwaitsSignal", "default", "in",
                    () -> new AwaitsSignal(false), AwaitsSignal.class.getMethod("run", String.class));
            await().atMost(BOUND).until(() -> status("sf-deadline-cancel") == WorkflowStatus.WAITING_SIGNAL);
            executor.deliverSignal("sf-deadline-cancel", "go", "payload");
            awaitRunEnded("sf-deadline-cancel");

            assertAll(
                    () -> assertTrue(faulty.calls("markTimerCancelled") >= 1, "the cancel must have been attempted"),
                    () -> assertEquals(List.of(), abandonReasons()),
                    () -> assertEquals(1, count(instanceId, EventType.SIGNAL_RECEIVED)),
                    () -> assertEquals(WorkflowStatus.COMPLETED, status("sf-deadline-cancel")));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private void awaitRunEnded(String workflowId) {
        await().atMost(BOUND).until(() -> !executor.isRunning(workflowId));
    }

    private List<AbandonReason> abandonReasons() {
        return observer.abandoned().stream().map(RecordingEngineObserver.AbandonCall::reason).toList();
    }

    private WorkflowStatus status(String workflowId) {
        return faulty.getInstance(workflowId).orElseThrow().status();
    }

    private long count(UUID instanceId, EventType type) {
        return faulty.getEvents(instanceId).stream().filter(e -> e.eventType() == type).count();
    }

    private long countCompensationEvents(UUID instanceId) {
        return faulty.getEvents(instanceId).stream()
                .filter(e -> e.eventType().name().startsWith("COMPENSATION_"))
                .count();
    }

    /** Matches the {@code ACTIVITY_COMPLETED} append of the named step. */
    private static Predicate<Object[]> completedOutcomeOf(String method) {
        return args -> args[0] instanceof WorkflowEvent e
                && e.eventType() == EventType.ACTIVITY_COMPLETED
                && e.stepName() != null && e.stepName().endsWith("." + method);
    }

    /** {@code predicate}, matching at most once. */
    private static Predicate<Object[]> once(Predicate<Object[]> predicate) {
        var fired = new AtomicBoolean();
        return args -> predicate.test(args) && fired.compareAndSet(false, true);
    }

    // ── Fixtures ──────────────────────────────────────────────────────

    public interface StepActivities {
        @Compensate("undoStep1")
        String step1(String in);

        void undoStep1(String in);

        String step2(String in);
    }

    public static final class CountingSteps implements StepActivities {
        final AtomicInteger step1 = new AtomicInteger();
        final AtomicInteger undoStep1 = new AtomicInteger();
        final AtomicInteger step2 = new AtomicInteger();

        @Override
        public String step1(String in) {
            step1.incrementAndGet();
            return "one:" + in;
        }

        @Override
        public void undoStep1(String in) {
            undoStep1.incrementAndGet();
        }

        @Override
        public String step2(String in) {
            step2.incrementAndGet();
            return "two:" + in;
        }
    }

    public static class TwoStepSaga {
        private final StepActivities steps;

        public TwoStepSaga(StepActivities steps) {
            this.steps = steps;
        }

        @Saga
        public String run(String in) {
            return steps.step1(in) + "|" + steps.step2(in);
        }
    }

    public static class OneStep {
        private final StepActivities steps;

        public OneStep(StepActivities steps) {
            this.steps = steps;
        }

        public String run(String in) {
            return steps.step1(in);
        }
    }

    /** {@code step1} is the primary, {@code step2} the fallback. */
    public static class PrimaryWithFallback {
        private final StepActivities steps;

        public PrimaryWithFallback(StepActivities steps) {
            this.steps = steps;
        }

        public String run(String in) {
            try {
                return steps.step1(in);
            } catch (Exception e) {
                return steps.step2(in);
            }
        }
    }

    /** {@code step1} is the primary; the fallback records a side effect, then calls {@code step2}. */
    public static class PrimaryThenSideEffectFallback {
        private final StepActivities steps;

        public PrimaryThenSideEffectFallback(StepActivities steps) {
            this.steps = steps;
        }

        public String run(String in) {
            try {
                return steps.step1(in);
            } catch (Exception e) {
                var id = WorkflowContext.current().randomUUID();
                return steps.step2(in + ":" + id);
            }
        }
    }

    public static class SwallowsAndReturns {
        private final StepActivities steps;

        public SwallowsAndReturns(StepActivities steps) {
            this.steps = steps;
        }

        public String run(String in) {
            try {
                steps.step1(in);
            } catch (Exception e) {
                // deliberately swallowed
            }
            return "ok";
        }
    }

    /** Awaits a signal that never comes; optionally swallows whatever the await throws and returns. */
    public static class AwaitsSignal {
        private final boolean swallow;

        public AwaitsSignal(boolean swallow) {
            this.swallow = swallow;
        }

        public String run(String in) {
            try {
                return WorkflowContext.current().awaitSignal("go", String.class, Duration.ofMinutes(10));
            } catch (RuntimeException e) {
                if (swallow) {
                    return "ok";
                }
                throw e;
            }
        }
    }

    public interface FlakyActivities {
        String flaky(String in);
    }

    /** Fails its first attempt with a retryable error, capturing the run's cancellation. */
    public static final class FlakyOnce implements FlakyActivities {
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicReference<RunCancellation> cancellation = new AtomicReference<>();
        final CountDownLatch firstAttemptFailed = new CountDownLatch(1);

        @Override
        public String flaky(String in) {
            if (attempts.incrementAndGet() == 1) {
                cancellation.set(WorkflowContext.current().cancellation());
                firstAttemptFailed.countDown();
                throw new IllegalStateException("transient (injected)");
            }
            return "ok:" + in;
        }
    }

    public static class CallsFlaky {
        private final FlakyActivities activities;

        public CallsFlaky(FlakyActivities activities) {
            this.activities = activities;
        }

        public String run(String in) {
            return activities.flaky(in);
        }
    }

    public interface SagaSteps {
        @Compensate("release")
        String reserve(String in);

        void release(String in);

        @Compensate("refund")
        String charge(String in);

        void refund(String in);

        String ship(String in);
    }

    /** {@code ship} always fails with a plain business exception. */
    public static final class CountingSagaSteps implements SagaSteps {
        final AtomicInteger reserve = new AtomicInteger();
        final AtomicInteger release = new AtomicInteger();
        final AtomicInteger charge = new AtomicInteger();
        final AtomicInteger refund = new AtomicInteger();
        final AtomicInteger ship = new AtomicInteger();

        @Override
        public String reserve(String in) {
            reserve.incrementAndGet();
            return "reserved:" + in;
        }

        @Override
        public void release(String in) {
            release.incrementAndGet();
        }

        @Override
        public String charge(String in) {
            charge.incrementAndGet();
            return "charged:" + in;
        }

        @Override
        public void refund(String in) {
            refund.incrementAndGet();
        }

        @Override
        public String ship(String in) {
            ship.incrementAndGet();
            throw new IllegalStateException("out of stock (injected business failure)");
        }
    }

    /** Unwinds, LIFO: {@code refund}, then the manual {@code notifyCustomer}, then {@code release}. */
    public static class ThreeStepSaga {
        private final SagaSteps steps;
        private final AtomicInteger notify;

        public ThreeStepSaga(SagaSteps steps, AtomicInteger notify) {
            this.steps = steps;
            this.notify = notify;
        }

        @Saga
        public String run(String in) {
            steps.reserve(in);
            WorkflowContext.current().addCompensation("notifyCustomer", notify::incrementAndGet);
            steps.charge(in);
            return steps.ship(in);
        }
    }

    /**
     * {@code step1}, then {@code parallel(sibling, step2)}: the sibling (branch
     * 0) fails with a plain business exception on its first run only. Records
     * what {@code parallel()} threw.
     */
    public static class ParallelSaga {
        private final StepActivities steps;
        private final AtomicBoolean siblingFailed = new AtomicBoolean();
        final AtomicReference<Throwable> parallelThrew = new AtomicReference<>();

        public ParallelSaga(StepActivities steps) {
            this.steps = steps;
        }

        @Saga
        public String run(String in) {
            steps.step1(in);
            try {
                return String.join("|", WorkflowContext.current().parallel(List.<Callable<String>>of(
                        () -> {
                            if (siblingFailed.compareAndSet(false, true)) {
                                throw new IllegalStateException("sibling business failure (injected)");
                            }
                            return "sibling";
                        },
                        () -> steps.step2(in))));
            } catch (RuntimeException e) {
                parallelThrew.set(e);
                throw e;
            }
        }
    }

    public static class AlwaysFails {
        public String run(String in) {
            throw new IllegalStateException("business failure (injected)");
        }
    }

    /** Awaits a signal that never comes and returns once the await times out. */
    public static class TimesOut {
        public String run(String in) {
            try {
                return WorkflowContext.current().awaitSignal("go", String.class, Duration.ofMillis(300));
            } catch (SignalTimeoutException e) {
                return "timed-out";
            }
        }
    }
}
