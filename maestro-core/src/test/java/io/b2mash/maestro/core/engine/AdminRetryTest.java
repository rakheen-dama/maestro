package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.LockAcquisitionException;
import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.core.TestTerminalWait;
import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.engine.WorkflowExecutor.RetryOutcome;
import io.b2mash.maestro.core.exception.SignalTimeoutException;
import io.b2mash.maestro.core.exception.WorkflowNotFoundException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.observe.StandDownReason;
import io.b2mash.maestro.core.observe.WorkflowInfo;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP3 Task 6 — an admin Retry deletes only the failure that ended the run and
 * what the run recorded after it while handling that failure (3.3, D10,
 * Issue 33), refuses when that handling consumed a signal (MD-1), and leaves
 * a second independent branch failure for a second Retry (MD-2).
 *
 * <p>The first test is the {@code AuditB33RetryCaughtFailureTest} reproduction
 * (evidence/repro-tests), ported to the executor builder with its assertions
 * verbatim.
 */
@DisplayName("Admin Retry deletes only the failure that ended the run and its handling")
class AdminRetryTest {

    private static final Duration BOUND = Duration.ofSeconds(5);
    private static final Duration GATE = Duration.ofMillis(100);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private VersionedInMemoryStore store;
    private RecordingEngineObserver observer;
    private WorkflowExecutor executor;
    private PayloadSerializer serializer;
    private ActsImpl impl;
    private Acts acts;

    @BeforeEach
    void setUp() {
        store = new VersionedInMemoryStore();
        observer = new RecordingEngineObserver();
        serializer = new PayloadSerializer(MAPPER);
        executor = WorkflowExecutor.builder(store, serializer, "svc").distributedLock(new InMemoryDistributedLock())
                .instanceLockTtl(Duration.ofSeconds(30))
                .lifecycleEventsEnabled(false)
                .shutdownTimeout(Duration.ofSeconds(5))
                .wakeRecheckInterval(Duration.ofSeconds(30))
                .observer(observer)
                .build();
        impl = new ActsImpl();
        acts = new ActivityProxyFactory().createProxy(Acts.class, impl, store, null, null,
                RetryPolicy.noRetry(), Duration.ofSeconds(5), serializer, new RetryExecutor());
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    // ── The AuditB33 port (3.3) ─────────────────────────────────────────

    @Test
    void retryAfterCaughtFailure_completesOnRecordedPath() throws Exception {
        var method = FallbackWorkflow.class.getMethod("run");
        var registration = new WorkflowRegistration("FallbackWorkflow", "default",
                () -> new FallbackWorkflow(acts), method);

        executor.startWorkflow("fallback-1", "FallbackWorkflow", "default", null,
                () -> new FallbackWorkflow(acts), method);
        TestTerminalWait.awaitStatus(store, "fallback-1", WorkflowStatus.FAILED, Duration.ofSeconds(5));

        impl.outageOver.set(true);   // the operator's "dependency is back"
        TestTerminalWait.awaitRunEnded(executor, "fallback-1", BOUND);
        assertEquals(WorkflowExecutor.RetryOutcome.RETRIED, executor.retryWorkflow("fallback-1", registration));

        pollUntil(() -> store.getInstance("fallback-1").orElseThrow().status() == WorkflowStatus.COMPLETED
                || !observer.standDowns().isEmpty(), Duration.ofSeconds(5));
        // A second recovery pass: what every node does every poll interval.
        afterOneRecoveryPass("fallback-1", Map.of("FallbackWorkflow", registration));

        assertTrue(observer.standDowns().stream()
                        .noneMatch(s -> s.reason() == StandDownReason.NONDETERMINISTIC_HISTORY),
                "retry must not turn a caught failure into replay drift");
        assertEquals(WorkflowStatus.COMPLETED, store.getInstance("fallback-1").orElseThrow().status());
        assertEquals(1, impl.primaryCalls.get(), "the caught primary failure replays; primary never runs again");
    }

    // ── D10: catch, alert, rethrow ──────────────────────────────────────

    @Test
    void retryAfterCatchAlertRethrow_rerunsThePrimaryAndCompletes() {
        var reg = start("d10", this::catchAlertRethrow);
        awaitFailed("d10");
        assertEquals(1, impl.primaryCalls.get());
        assertEquals(1, impl.alertCalls.get());

        impl.outageOver.set(true);
        TestTerminalWait.awaitRunEnded(executor, "d10", BOUND);
        assertEquals(RetryOutcome.RETRIED, executor.retryWorkflow("d10", reg));
        assertEquals(WorkflowStatus.COMPLETED, lastStatusWithin("d10", WorkflowStatus.COMPLETED, Duration.ofSeconds(5)));
        assertEquals(List.of("1 ACTIVITY_COMPLETED Acts.primary", "2 ACTIVITY_COMPLETED Acts.finish",
                "3 WORKFLOW_COMPLETED null"), history("d10"));
        assertEquals(2, impl.primaryCalls.get());
        assertEquals(1, impl.alertCalls.get());
        assertEquals(List.of(), observer.standDowns());
    }

    @Test
    void retryAfterCatchAlertRethrow_whenThePrimaryFailsAgain_failsAgainAndAlertsAgain() {
        var reg = start("d10b", this::catchAlertRethrow);
        awaitFailed("d10b");

        // the outage is NOT over
        TestTerminalWait.awaitRunEnded(executor, "d10b", BOUND);
        assertEquals(RetryOutcome.RETRIED, executor.retryWorkflow("d10b", reg));
        assertEquals(WorkflowStatus.FAILED, lastStatusWithin("d10b", WorkflowStatus.FAILED, Duration.ofSeconds(5)));
        assertEquals(2, impl.alertCalls.get());
        assertEquals("[1]", failurePayload("d10b").path("failedSequences").toString());
        assertEquals(List.of(), observer.standDowns());
    }

    // ── Parallel branches (D-5) ─────────────────────────────────────────

    @Test
    void retryOfABranchFailure_keepsTheCompletedSiblingAndRerunsTheRootTail() {
        var reg = start("par", () -> {
            try {
                List<Callable<String>> tasks = List.of(() -> acts.primary(), () -> acts.sibling());
                WorkflowContext.current().parallel(tasks);
            } catch (RuntimeException e) {
                acts.alert();
                throw e;
            }
            return acts.finish("x");
        });
        awaitFailed("par");
        assertEquals(1, impl.siblingCalls.get());
        assertEquals(1, impl.alertCalls.get());

        impl.outageOver.set(true);
        TestTerminalWait.awaitRunEnded(executor, "par", BOUND);
        assertEquals(RetryOutcome.RETRIED, executor.retryWorkflow("par", reg));
        assertEquals(WorkflowStatus.COMPLETED, lastStatusWithin("par", WorkflowStatus.COMPLETED, Duration.ofSeconds(5)));
        assertEquals(1, impl.siblingCalls.get());
        assertEquals(1, impl.alertCalls.get());
        assertEquals(List.of(), observer.standDowns());
    }

    @Test
    void twoFailedBranches_needTwoRetries() {
        var reg = start("two", () -> {
            List<Callable<String>> tasks = List.of(() -> acts.primary(), () -> acts.ledger());
            return String.join(",", WorkflowContext.current().parallel(tasks));
        });
        awaitFailed("two");
        assertEquals("[2001]", failurePayload("two").path("failedSequences").toString());

        impl.outageOver.set(true);   // both fixed
        TestTerminalWait.awaitRunEnded(executor, "two", BOUND);
        assertEquals(RetryOutcome.RETRIED, executor.retryWorkflow("two", reg));
        assertEquals(WorkflowStatus.FAILED, lastStatusWithin("two", WorkflowStatus.FAILED, Duration.ofSeconds(5)));
        assertEquals("[3001]", failurePayload("two").path("failedSequences").toString());

        TestTerminalWait.awaitRunEnded(executor, "two", BOUND);
        assertEquals(RetryOutcome.RETRIED, executor.retryWorkflow("two", reg));
        assertEquals(WorkflowStatus.COMPLETED, lastStatusWithin("two", WorkflowStatus.COMPLETED, Duration.ofSeconds(5)));
        assertEquals(2, impl.primaryCalls.get());
        assertEquals(2, impl.ledgerCalls.get());
    }

    // ── Issue 33: the await that propagated, not the highest timeout ────

    @Test
    void issue33_mainAwaitAfterABranchCaughtGate_clearsOnlyTheMainAwait() {
        var reg = start("i33a", () -> {
            var ctx = WorkflowContext.current();
            List<Callable<String>> outer = List.of(() -> {
                // nested fork at 2001: branch 0 continues at 2004001
                List<Callable<String>> inner = List.of(() -> "x", () -> "y");
                ctx.parallel(inner);
                try {
                    ctx.awaitSignal("gate", String.class, GATE);   // 2004001/2004002
                } catch (SignalTimeoutException e) {
                    // caught: the gate is optional
                }
                return "a";
            }, () -> "b");
            ctx.parallel(outer);
            return ctx.awaitSignal("main", String.class, GATE);       // 4001/4002, uncaught
        });
        awaitFailed("i33a");
        assertEquals("[4002]", failurePayload("i33a").path("failedSequences").toString());

        executor.deliverSignal("i33a", "gate", "late");
        executor.deliverSignal("i33a", "main", "go");
        TestTerminalWait.awaitRunEnded(executor, "i33a", BOUND);
        assertEquals(RetryOutcome.RETRIED, executor.retryWorkflow("i33a", reg));
        assertEquals(WorkflowStatus.COMPLETED, lastStatusWithin("i33a", WorkflowStatus.COMPLETED, Duration.ofSeconds(5)));
        assertEquals(1, store.getUnconsumedSignals("i33a", "gate").size());   // the caught gate replayed its timeout
    }

    @Test
    void issue33_lowerBranchTimesOutWhileAHigherBranchCaughtAGate_clearsOnlyTheLowerBranch() {
        var reg = start("i33b", () -> {
            var ctx = WorkflowContext.current();
            List<Callable<String>> tasks = List.of(
                    () -> ctx.awaitSignal("a", String.class, GATE),       // uncaught
                    () -> {
                        try {
                            ctx.awaitSignal("gate", String.class, GATE);
                        } catch (SignalTimeoutException e) {
                            // caught
                        }
                        return "b";
                    });
            return String.join(",", ctx.parallel(tasks));
        });
        awaitFailed("i33b");
        assertEquals("[2002]", failurePayload("i33b").path("failedSequences").toString());

        executor.deliverSignal("i33b", "gate", "late");
        executor.deliverSignal("i33b", "a", "go");
        TestTerminalWait.awaitRunEnded(executor, "i33b", BOUND);
        assertEquals(RetryOutcome.RETRIED, executor.retryWorkflow("i33b", reg));
        assertEquals(WorkflowStatus.COMPLETED, lastStatusWithin("i33b", WorkflowStatus.COMPLETED, Duration.ofSeconds(5)));
        assertEquals(1, store.getUnconsumedSignals("i33b", "gate").size());
    }

    // ── The refusal (MD-1) ──────────────────────────────────────────────

    @Test
    void aSignalConsumedWhileHandlingTheFailure_refusesTheRetryAndWritesNothing() {
        var reg = start("sig", () -> {
            try {
                return acts.primary();
            } catch (RuntimeException e) {
                WorkflowContext.current().awaitSignal("ack", String.class, Duration.ofSeconds(5));
                throw e;
            }
        });
        TestTerminalWait.awaitStatus(store, "sig", WorkflowStatus.WAITING_SIGNAL, BOUND);
        executor.deliverSignal("sig", "ack", "ok");
        awaitFailed("sig");
        var before = history("sig");
        var versionBefore = store.getInstance("sig").orElseThrow().version();

        impl.outageOver.set(true);
        assertEquals(RetryOutcome.SIGNAL_CONSUMED_AFTER_FAILURE, executor.retryWorkflow("sig", reg));
        assertEquals(WorkflowStatus.FAILED, store.getInstance("sig").orElseThrow().status());
        assertEquals(before, history("sig"));
        assertEquals(versionBefore, store.getInstance("sig").orElseThrow().version(), "the row is not written");
        assertTrue(store.getUnconsumedSignals("sig", "ack").isEmpty(), "the consumed signal stays consumed");
        assertEquals(1, store.allSignals().size(), "the signal row is kept");
        assertEquals(1, impl.primaryCalls.get(), "nothing was relaunched");
    }

    // ── A row purged under the reopen ───────────────────────────────────

    @Test
    void aReopenThatFindsTheRowGone_isNotFoundAndLaunchesNothing() {
        var purging = new VersionedInMemoryStore() {
            @Override
            public synchronized void reopenFailedInstance(WorkflowInstance reopened, Set<Integer> seqs,
                                                          Set<String> timerIds) {
                // the retention purge won the row between the read and the reopen
                throw new WorkflowNotFoundException(reopened.workflowId());
            }
        };
        var purgingExecutor = WorkflowExecutor.builder(purging, serializer, "svc").distributedLock(new InMemoryDistributedLock())
                .lifecycleEventsEnabled(false)
                .observer(observer)
                .build();
        try {
            var instanceId = UUID.randomUUID();
            var now = Instant.now();
            purging.createInstance(WorkflowInstance.builder()
                    .id(instanceId).workflowId("purged").runId(UUID.randomUUID()).workflowType("Body")
                    .taskQueue("default").status(WorkflowStatus.FAILED).serviceName("svc").eventSequence(2)
                    .startedAt(now).updatedAt(now).completedAt(now).version(0).build());
            purging.appendEvent(event(instanceId, 1, EventType.ACTIVITY_FAILED, "Acts.primary",
                    "{\"exceptionType\":\"java.lang.IllegalStateException\",\"message\":\"down\"}"));
            purging.appendEvent(event(instanceId, 2, EventType.WORKFLOW_FAILED, null,
                    "{\"exceptionType\":\"java.lang.IllegalStateException\",\"failedSequences\":[1]}"));

            assertEquals(RetryOutcome.NOT_FOUND,
                    purgingExecutor.retryWorkflow("purged", registration(this::catchAlertRethrow)));
            assertFalse(purgingExecutor.isRunning("purged"));
            assertEquals(0, impl.primaryCalls.get(), "nothing was launched");
        } finally {
            purgingExecutor.shutdown();
        }
    }

    // ── A Retry while the failed run is still registered here (PR #57 review 4) ──

    @Test
    void aRetryBeforeTheFailedRunLeavesThisNode_writesNothingAndSucceedsWhenRedelivered() throws Exception {
        // PR #72 review changes this test's expectation (the row used to be
        // reopened, ALREADY_RUNNING_LOCALLY returned, and recovery relaunched
        // it): Retry now takes the instance lock before it reopens the row. The
        // failed run still holds it, so the Retry writes nothing and fails
        // transiently; redelivered once the run has left, it retries.
        // Holds the failing run after its FAILED write (workflowFailed is
        // emitted after it) and before its finally leaves the local registry.
        var failedWritten = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holdsTheRun = new EngineObserver() {
            @Override
            public void workflowFailed(WorkflowInfo w, String exceptionType) {
                failedWritten.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        var holding = WorkflowExecutor.builder(store, serializer, "svc").distributedLock(new InMemoryDistributedLock())
                .lifecycleEventsEnabled(false)
                .observer(holdsTheRun)
                .build();
        try {
            var reg = registration(this::catchAlertRethrow);
            holding.startWorkflow("held", reg.workflowType(), reg.taskQueue(), null,
                    reg.workflowFactory(), reg.workflowMethod());
            assertTrue(failedWritten.await(5, TimeUnit.SECONDS), "the run did not fail");
            awaitFailed("held");
            assertTrue(holding.isRunning("held"), "the FAILED run has not left this node's registry yet");

            impl.outageOver.set(true);
            var historyBefore = history("held");
            assertThrows(LockAcquisitionException.class, () -> holding.retryWorkflow("held", reg),
                    "the failed run still holds the instance lock");
            assertEquals(WorkflowStatus.FAILED, store.getInstance("held").orElseThrow().status(), "nothing reopened");
            assertEquals(historyBefore, history("held"), "no failure memo deleted");
            assertEquals(1, impl.primaryCalls.get(), "the retry launched nothing");

            release.countDown();
            TestTerminalWait.awaitRunEnded(holding, "held", BOUND);
            // The transport redelivers the command until the failed run has
            // released its lock (the run leaves the registry before it releases):
            // each refusal in between writes nothing.
            assertEquals(RetryOutcome.RETRIED, RecoveryCapacityFixtures.valueWithin(() -> {
                try {
                    return holding.retryWorkflow("held", reg);
                } catch (LockAcquisitionException stillReleasing) {
                    return null;
                }
            }, RetryOutcome.RETRIED, BOUND));
            assertEquals(WorkflowStatus.COMPLETED, lastStatusWithin("held", WorkflowStatus.COMPLETED, BOUND));
            assertEquals(2, impl.primaryCalls.get(), "the retried run re-ran the deleted primary");
            assertEquals(1, impl.alertCalls.get(), "and, the primary now succeeding, no alert");
        } finally {
            release.countDown();
            holding.shutdown();
        }
    }

    // ── History an older build wrote ────────────────────────────────────

    @Test
    void aLegacyFailurePayload_isMatchedByMessageAndRerunsTheAlert() {
        var instanceId = UUID.randomUUID();
        var now = Instant.now();
        store.createInstance(WorkflowInstance.builder()
                .id(instanceId)
                .workflowId("legacy")
                .runId(UUID.randomUUID())
                .workflowType("Body")
                .taskQueue("default")
                .status(WorkflowStatus.FAILED)
                .serviceName("svc")
                .eventSequence(3)
                .startedAt(now)
                .updatedAt(now)
                .completedAt(now)
                .version(0)
                .build());
        store.appendEvent(event(instanceId, 1, EventType.ACTIVITY_FAILED, "Acts.primary",
                "{\"exceptionType\":\"java.lang.IllegalStateException\",\"message\":\"pricing service down\"}"));
        store.appendEvent(event(instanceId, 2, EventType.ACTIVITY_COMPLETED, "Acts.alert", "\"alerted\""));
        store.appendEvent(event(instanceId, 3, EventType.WORKFLOW_FAILED, null,
                "{\"exceptionType\":\"io.b2mash.maestro.core.exception.ActivityExecutionException\","
                        + "\"message\":\"Activity 'Acts.primary' failed in workflow 'legacy': pricing service down\"}"));

        impl.outageOver.set(true);
        assertEquals(RetryOutcome.RETRIED, executor.retryWorkflow("legacy", registration(this::catchAlertRethrow)));
        assertEquals(WorkflowStatus.COMPLETED, lastStatusWithin("legacy", WorkflowStatus.COMPLETED, Duration.ofSeconds(5)));
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** try { q = primary(); } catch (RuntimeException e) { alert(); throw e; } return finish(q); */
    private String catchAlertRethrow() {
        String quote;
        try {
            quote = acts.primary();
        } catch (RuntimeException e) {
            acts.alert();
            throw e;
        }
        return acts.finish(quote);
    }

    private WorkflowRegistration start(String workflowId, Supplier<String> body) {
        var reg = registration(body);
        executor.startWorkflow(workflowId, reg.workflowType(), reg.taskQueue(), null,
                reg.workflowFactory(), reg.workflowMethod());
        return reg;
    }

    private static WorkflowRegistration registration(Supplier<String> body) {
        return new WorkflowRegistration("Body", "default", () -> new BodyWorkflow(body), bodyRunMethod());
    }

    private void awaitFailed(String workflowId) {
        TestTerminalWait.awaitStatus(store, workflowId, WorkflowStatus.FAILED, BOUND);
    }

    /**
     * Polls {@code getInstance(id)} every 20 ms until its status is
     * {@code wanted} or {@code bound} elapses, and returns the last status read.
     */
    private @Nullable WorkflowStatus lastStatusWithin(String workflowId, WorkflowStatus wanted, Duration bound) {
        var deadline = System.nanoTime() + bound.toNanos();
        WorkflowStatus last = null;
        while (true) {
            last = store.getInstance(workflowId).map(WorkflowInstance::status).orElse(null);
            if (last == wanted || System.nanoTime() >= deadline) {
                return last;
            }
            sleep(20);
        }
    }

    /**
     * Waits (at most 5 s) until the workflow is not running on this node,
     * runs one recovery pass, and waits 300 ms — what every node does every
     * poll interval.
     */
    private void afterOneRecoveryPass(String workflowId, Map<String, WorkflowRegistration> registrations) {
        pollUntil(() -> !executor.isRunning(workflowId), Duration.ofSeconds(5));
        executor.recoverWorkflows(registrations);
        sleep(300);
    }

    private static void pollUntil(Supplier<Boolean> condition, Duration bound) {
        var deadline = System.nanoTime() + bound.toNanos();
        while (!condition.get() && System.nanoTime() < deadline) {
            sleep(20);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** The instance's history as {@code "<seq> <type> <step>"}, ascending. */
    private List<String> history(String workflowId) {
        var instanceId = store.getInstance(workflowId).orElseThrow().id();
        return store.getEvents(instanceId).stream()
                .sorted(Comparator.comparingInt(WorkflowEvent::sequenceNumber))
                .map(e -> e.sequenceNumber() + " " + e.eventType() + " " + e.stepName())
                .toList();
    }

    /** The {@code WORKFLOW_FAILED} payload. */
    private JsonNode failurePayload(String workflowId) {
        var instanceId = store.getInstance(workflowId).orElseThrow().id();
        return store.getEvents(instanceId).stream()
                .filter(e -> e.eventType() == EventType.WORKFLOW_FAILED)
                .findFirst()
                .orElseThrow()
                .payload();
    }

    private static WorkflowEvent event(UUID instanceId, int seq, EventType type, @Nullable String step,
                                       String payloadJson) {
        return new WorkflowEvent(UUID.randomUUID(), instanceId, seq, type, step, MAPPER.readTree(payloadJson),
                Instant.now());
    }

    private static Method bodyRunMethod() {
        try {
            return BodyWorkflow.class.getMethod("run");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── Fixture ─────────────────────────────────────────────────────────

    /** The activities; {@code primary}, {@code finish} and {@code ledger} fail until the outage is over. */
    @Activity
    public interface Acts {
        /** @return a quote; fails during the outage */
        String primary();

        /** @return a cached quote */
        String fallback();

        /**
         * @param quote the quote
         * @return the result; fails during the outage
         */
        String finish(String quote);

        /** @return {@code "alerted"} */
        String alert();

        /** @return {@code "sibling"} */
        String sibling();

        /** @return {@code "ledger"}; fails during the outage */
        String ledger();
    }

    static final class ActsImpl implements Acts {
        final AtomicBoolean outageOver = new AtomicBoolean();
        final AtomicInteger primaryCalls = new AtomicInteger();
        final AtomicInteger fallbackCalls = new AtomicInteger();
        final AtomicInteger finishCalls = new AtomicInteger();
        final AtomicInteger alertCalls = new AtomicInteger();
        final AtomicInteger siblingCalls = new AtomicInteger();
        final AtomicInteger ledgerCalls = new AtomicInteger();

        @Override
        public String primary() {
            primaryCalls.incrementAndGet();
            if (!outageOver.get()) {
                throw new IllegalStateException("pricing service down");
            }
            return "primary-quote";
        }

        @Override
        public String fallback() {
            fallbackCalls.incrementAndGet();
            return "cached-quote";
        }

        @Override
        public String finish(String quote) {
            finishCalls.incrementAndGet();
            if (!outageOver.get()) {
                throw new IllegalStateException("ledger down");
            }
            return "done:" + quote;
        }

        @Override
        public String alert() {
            alertCalls.incrementAndGet();
            return "alerted";
        }

        @Override
        public String sibling() {
            siblingCalls.incrementAndGet();
            return "sibling";
        }

        @Override
        public String ledger() {
            ledgerCalls.incrementAndGet();
            if (!outageOver.get()) {
                throw new IllegalStateException("ledger down");
            }
            return "ledger";
        }
    }

    /** The AuditB33 workflow: a caught primary failure takes a fallback; finish fails uncaught. */
    @DurableWorkflow(name = "FallbackWorkflow")
    public static class FallbackWorkflow {
        private final Acts acts;

        /** @param acts the activities */
        public FallbackWorkflow(Acts acts) {
            this.acts = acts;
        }

        /** @return the finished quote */
        @WorkflowMethod
        public String run() {
            String quote;
            try {
                quote = acts.primary();
            } catch (RuntimeException e) {
                quote = acts.fallback();
            }
            return acts.finish(quote);
        }
    }

    /** One workflow class, its body supplied per test. */
    @DurableWorkflow(name = "Body")
    public static class BodyWorkflow {

        private final Supplier<String> body;

        BodyWorkflow(Supplier<String> body) {
            this.body = body;
        }

        /** @return whatever the body returns */
        @WorkflowMethod
        public String run() {
            return body.get();
        }
    }
}
