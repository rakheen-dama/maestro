package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.Compensate;
import io.b2mash.maestro.core.annotation.Saga;
import io.b2mash.maestro.core.context.RunCancellation;
import io.b2mash.maestro.core.context.WorkflowContext;
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
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
        return new WorkflowExecutor(store, null, null, null, serializer, "test-service",
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX,
                WorkflowInstanceLockManager.DEFAULT_LOCK_TTL,
                false, WorkflowExecutor.DEFAULT_SHUTDOWN_TIMEOUT, Duration.ofSeconds(30), observer);
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
}
