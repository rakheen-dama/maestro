package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.core.TestTerminalWait;
import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.context.WorkflowContextInternals;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * WP3 Task 1 — the run that records {@code WORKFLOW_FAILED} names, in the
 * payload's {@code failedSequences}, the sequence numbers of the failure memos
 * its escaping exception carried: itself, its cause chain or its suppressed
 * exceptions. Live and replayed memos alike; a branch context registers into
 * the run's map. A failure with no memo ({@code collectSignals}' own deadline)
 * or a wrap that drops the cause carries none.
 */
@DisplayName("WORKFLOW_FAILED names the failure memos the escaping exception carried")
class FailureAttributionTest {

    private static final Duration BOUND = Duration.ofSeconds(5);

    private VersionedInMemoryStore store;
    private PayloadSerializer serializer;
    private WorkflowExecutor executor;
    private Acts acts;

    @BeforeEach
    void setUp() {
        store = new VersionedInMemoryStore();
        serializer = new PayloadSerializer(JsonMapper.builder().build());
        executor = WorkflowExecutor.builder(store, serializer, "svc").distributedLock(new InMemoryDistributedLock())
                .instanceLockTtl(Duration.ofSeconds(30))
                .lifecycleEventsEnabled(false)
                .shutdownTimeout(Duration.ofSeconds(5))
                .wakeRecheckInterval(Duration.ofMillis(200))
                .build();
        acts = new ActivityProxyFactory().createProxy(Acts.class, new FailingActs(), store, null, null,
                RetryPolicy.noRetry(), Duration.ofSeconds(5), serializer, new RetryExecutor());
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    @DisplayName("an uncaught live activity failure is attributed at its slot")
    void anUncaughtLiveActivityFailureIsAttributed() {
        start("w1", () -> {
            acts.a();
            acts.b();
            return acts.c();
        });
        assertEquals("[3]", failed("w1"));
    }

    @Test
    @DisplayName("a caught failure that was handled and rethrown is attributed")
    void aCaughtFailureHandledAndRethrownIsAttributed() {
        start("w2", this::catchAlertRethrow);
        assertEquals("[1]", failed("w2"));
    }

    @Test
    @DisplayName("a failure wrapped with its cause is attributed")
    void aFailureWrappedWithItsCauseIsAttributed() {
        start("w3", () -> {
            try {
                return acts.p();
            } catch (RuntimeException e) {
                throw new IllegalStateException("x", e);
            }
        });
        assertEquals("[1]", failed("w3"));
    }

    @Test
    @DisplayName("a failure wrapped without its cause is not attributed")
    void aFailureWrappedWithoutItsCauseIsNotAttributed() {
        start("w4", () -> {
            try {
                return acts.p();
            } catch (RuntimeException e) {
                throw new IllegalStateException("x");
            }
        });
        assertEquals("[]", failed("w4"));
    }

    @Test
    @DisplayName("a failure carried as a suppressed exception is attributed")
    void aFailureCarriedAsSuppressedIsAttributed() {
        start("w4s", () -> {
            try {
                return acts.p();
            } catch (RuntimeException e) {
                var x = new IllegalStateException("x");
                x.addSuppressed(e);
                throw x;
            }
        });
        assertEquals("[1]", failed("w4s"));
    }

    @Test
    @DisplayName("a branch failure rethrown by the join is attributed at its branch slot")
    void aBranchFailureRethrownByTheJoinIsAttributed() {
        start("w5", () -> {
            List<Callable<String>> tasks = List.of(() -> acts.p(), () -> acts.ok());
            return String.join(",", WorkflowContext.current().parallel(tasks));
        });
        assertEquals("[2001]", failed("w5"));
    }

    @Test
    @DisplayName("a parked await's timeout is attributed at its outcome slot")
    void aParkedAwaitTimeoutIsAttributedAtItsOutcomeSlot() {
        start("w6", () -> WorkflowContext.current().awaitSignal("go", String.class, Duration.ofMillis(100)));
        assertEquals("[2]", failed("w6"));
    }

    @Test
    @DisplayName("an immediate await timeout is attributed at its single slot")
    void anImmediateAwaitTimeoutIsAttributedAtItsSlot() {
        start("w7", () -> WorkflowContext.current().awaitSignal("go", String.class, Duration.ZERO));
        assertEquals("[1]", failed("w7"));
    }

    @Test
    @DisplayName("collectSignals' own deadline has no memo and is not attributed")
    void collectSignalsOwnDeadlineIsNotAttributed() {
        start("w8", () -> String.join(",",
                WorkflowContext.current().collectSignals("go", String.class, 2, Duration.ZERO)));
        assertEquals("[]", failed("w8"));
    }

    @Test
    @DisplayName("a failure replayed on recovery is attributed at its recorded slot")
    void aFailureReplayedOnRecoveryIsAttributed() {
        var instanceId = seedRunning("w9", "CatchAlertRethrow");
        var now = Instant.now();
        store.appendEvent(new WorkflowEvent(UUID.randomUUID(), instanceId, 1, EventType.ACTIVITY_FAILED,
                "Acts.p", JsonMapper.builder().build().readTree(
                        "{\"exceptionType\":\"java.lang.IllegalStateException\",\"message\":\"down\"}"),
                now));

        // p replays its recorded failure, alert runs live, the catch rethrows
        executor.recoverWorkflows(Map.of("CatchAlertRethrow", registration("CatchAlertRethrow",
                this::catchAlertRethrow)));

        assertEquals("[1]", failed("w9"));
    }

    @Test
    @DisplayName("an await timeout replayed on recovery is attributed at its recorded slot")
    void anAwaitTimeoutReplayedOnRecoveryIsAttributed() {
        var instanceId = seedRunning("w10", "AwaitZero");
        store.appendEvent(new WorkflowEvent(UUID.randomUUID(), instanceId, 1, EventType.SIGNAL_TIMEOUT,
                "$maestro:awaitSignal:go", JsonMapper.builder().build().readTree(
                        "{\"signalName\":\"go\",\"timeout\":\"PT0S\"}"),
                Instant.now()));

        executor.recoverWorkflows(Map.of("AwaitZero", registration("AwaitZero",
                () -> WorkflowContext.current().awaitSignal("go", String.class, Duration.ZERO))));

        assertEquals("[1]", failed("w10"));
    }

    @Test
    @DisplayName("a branch context registers into its parent's failure-memo map")
    void aBranchContextSharesItsParentsFailureMemos() {
        var root = new WorkflowContext(UUID.randomUUID(), "w", UUID.randomUUID(), "T", "q", "svc", 0, false);
        var branch = WorkflowContext.bounded(root, 0, 2000, 3000);
        var thrown = new IllegalStateException("x");
        WorkflowContextInternals.recordFailureMemo(branch, thrown, 2001);
        assertEquals(2001, WorkflowContextInternals.failureMemoSequence(root, thrown));
    }

    @Test
    @DisplayName("the registry does not keep an exception alive once nothing else reaches it")
    void anExceptionNoLongerReachableIsCollectable() throws InterruptedException {
        // The JVM does not promise that System.gc() clears a weak reference, so
        // a control twin, allocated beside the registered exception and reached
        // by nothing at all, tells a collection that cleared weak references
        // from one that did not. No clearing within the bound: inconclusive.
        // The control cleared but the registered exception kept: the registry
        // holds its keys strongly (PR #57 review 5).
        var root = new WorkflowContext(UUID.randomUUID(), "w", UUID.randomUUID(), "T", "q", "svc", 0, false);
        var refs = registerUnreachableBesideAControl(root, 1);
        for (int i = 0; i < 100 && refs.control().get() != null; i++) {
            System.gc();
            Thread.sleep(10);
        }
        assumeTrue(refs.control().get() == null,
                "no collection cleared an unreachable control object within 100 System.gc() calls");
        for (int i = 0; i < 10 && refs.registered().get() != null; i++) {
            System.gc();
            Thread.sleep(10);
        }
        assertNull(refs.registered().get(), "a collection cleared the unreachable control object, but the "
                + "registry kept the registered exception alive: it must not hold its keys strongly");
    }

    @Test
    @DisplayName("a registered failure still reachable from the escaping exception keeps its attribution")
    void aReachableFailureKeepsItsAttributionAcrossCollections() throws InterruptedException {
        var root = new WorkflowContext(UUID.randomUUID(), "w", UUID.randomUUID(), "T", "q", "svc", 0, false);
        var memo = new IllegalStateException("down");
        WorkflowContextInternals.recordFailureMemo(root, memo, 7);
        var escaping = new IllegalStateException("x", memo);
        memo = null;
        for (int i = 0; i < 5; i++) {
            System.gc();
            Thread.sleep(10);
        }
        assertEquals(List.of(7), FailureAttribution.sequences(root, escaping));
    }

    @Test
    @DisplayName("parallel(p, p) with both failing names only the lower branch (the join attaches no sibling)")
    void bothBranchesFailing_attributeOnlyTheLowerBranch() {
        start("w11", () -> {
            List<Callable<String>> tasks = List.of(() -> acts.p(), () -> acts.p());
            return String.join(",", WorkflowContext.current().parallel(tasks));
        });
        assertEquals("[2001]", failed("w11"));
    }

    // ── Fixture ─────────────────────────────────────────────────────────

    /** Weak references to a registered exception and to its unregistered twin. */
    private record Unreachable(WeakReference<Throwable> registered, WeakReference<Throwable> control) {}

    /** Registers a fresh exception beside an unregistered twin and returns only weak references to both. */
    private static Unreachable registerUnreachableBesideAControl(WorkflowContext ctx, int sequence) {
        var control = new IllegalStateException("unreachable");
        var thrown = new IllegalStateException("unreachable");
        WorkflowContextInternals.recordFailureMemo(ctx, thrown, sequence);
        return new Unreachable(new WeakReference<>(thrown), new WeakReference<>(control));
    }

    private String catchAlertRethrow() {
        try {
            return acts.p();
        } catch (RuntimeException e) {
            acts.alert();
            throw e;
        }
    }

    private UUID seedRunning(String workflowId, String workflowType) {
        var instanceId = UUID.randomUUID();
        var now = Instant.now();
        store.createInstance(WorkflowInstance.builder()
                .id(instanceId)
                .workflowId(workflowId)
                .runId(UUID.randomUUID())
                .workflowType(workflowType)
                .taskQueue("default")
                .status(WorkflowStatus.RUNNING)
                .serviceName("svc")
                .eventSequence(0)
                .startedAt(now)
                .updatedAt(now)
                .version(0)
                .build());
        return instanceId;
    }

    private void start(String workflowId, Supplier<String> body) {
        var reg = registration("Body", body);
        executor.startWorkflow(workflowId, reg.workflowType(), reg.taskQueue(), null,
                reg.workflowFactory(), reg.workflowMethod());
    }

    private static WorkflowRegistration registration(String type, Supplier<String> body) {
        return new WorkflowRegistration(type, "default", () -> new BodyWorkflow(body), runMethod());
    }

    /**
     * Waits for {@code FAILED} (with its terminal event) and returns the
     * {@code WORKFLOW_FAILED} payload's {@code failedSequences} as JSON text.
     */
    private String failed(String workflowId) {
        TestTerminalWait.awaitStatus(store, workflowId, WorkflowStatus.FAILED, BOUND);
        var instanceId = store.getInstance(workflowId).orElseThrow().id();
        var event = store.getEvents(instanceId).stream()
                .filter(e -> e.eventType() == EventType.WORKFLOW_FAILED)
                .findFirst()
                .orElseThrow();
        return event.payload().path("failedSequences").toString();
    }

    private static Method runMethod() {
        try {
            return BodyWorkflow.class.getMethod("run");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The activities the workflows call; {@code c} and {@code p} always fail. */
    @Activity(name = "Acts")
    public interface Acts {
        /** @return {@code "a"} */
        String a();

        /** @return {@code "b"} */
        String b();

        /** @return never; always throws */
        String c();

        /** @return never; always throws */
        String p();

        /** @return {@code "alerted"} */
        String alert();

        /** @return {@code "ok"} */
        String ok();
    }

    static final class FailingActs implements Acts {
        @Override public String a() { return "a"; }
        @Override public String b() { return "b"; }
        @Override public String c() { throw new IllegalStateException("c down"); }
        @Override public String p() { throw new IllegalStateException("down"); }
        @Override public String alert() { return "alerted"; }
        @Override public String ok() { return "ok"; }
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
