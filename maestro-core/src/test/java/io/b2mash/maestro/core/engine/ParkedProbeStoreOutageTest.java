package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins Issue 20 (Ruling 5): the periodic wake-recheck probes a parked workflow
 * performs — {@code standDownIfTerminated}'s instance read and the signal /
 * timer-row polls in {@code awaitSignal} and {@code sleep()} — are ADVISORY.
 * Skipping one interval is always safe: terminate convergence and cross-node
 * wake are delayed by at most one interval, and durable state cannot be
 * corrupted by not probing. A store exception raised by a probe must therefore
 * be caught at the probe and the park must continue — never propagate into the
 * executor's generic failure handling.
 *
 * <p>The defect these tests pin (chaos run {@code 20260801-093053-661901},
 * seed 661901): a 39s network partition exceeded HikariCP's 30s
 * connectionTimeout, {@code standDownIfTerminated}'s {@code getInstance}
 * threw, and the executor's {@code catch (Exception)} durably
 * {@code WORKFLOW_FAILED} two healthy PARKED workflows — a routine infra blip
 * recorded as workflow failure (the Issue 4/5/18 family).
 *
 * <p>Scope boundary pinned here implicitly: only the probe reads are
 * forgiven. State writes (event appends, status transitions, signal
 * consumption) keep their existing semantics, and terminate is still honoured
 * on the first successful probe after the store recovers (fail open on
 * "can't prove terminated" — an unreachable store means keep parking).
 */
@DisplayName("Parked workflows ride out transient store outages during wake-recheck probes (Issue 20)")
class ParkedProbeStoreOutageTest {

    /** Short enough that several probe failures land well inside await bounds. */
    private static final Duration SHORT_RECHECK = Duration.ofMillis(200);

    private OutageStore store;
    private PayloadSerializer serializer;
    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        store = new OutageStore();
        serializer = new PayloadSerializer(new ObjectMapper());
        executor = new WorkflowExecutor(store, null, null, null, serializer, "test-service",
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX,
                WorkflowInstanceLockManager.DEFAULT_LOCK_TTL,
                true, WorkflowExecutor.DEFAULT_SHUTDOWN_TIMEOUT, SHORT_RECHECK);
    }

    @AfterEach
    void tearDown() {
        store.readsUnavailable = false;
        executor.shutdown();
    }

    @Test
    @DisplayName("a parked awaitSignal stays WAITING_SIGNAL through several failed probes and completes once the store heals")
    void parkedAwait_storeOutageDuringRecheck_staysParkedAndCompletesAfterHeal() throws Exception {
        var completed = new CountDownLatch(1);
        var workflow = new AwaitingWorkflow(completed);
        var method = AwaitingWorkflow.class.getMethod("run", String.class);

        var instanceId = executor.startWorkflow("probe-await-1", "AwaitingWorkflow", "default",
                "hello", workflow, method);

        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.getInstance("probe-await-1")
                        .map(i -> i.status() == WorkflowStatus.WAITING_SIGNAL)
                        .orElse(false));

        // The partition: every probe read now throws, across several recheck
        // intervals (>= 5 at the 200ms interval).
        store.readsUnavailable = true;
        await().atMost(Duration.ofSeconds(5)).until(() -> store.failedReads.get() >= 5);

        assertFalse(completed.await(200, TimeUnit.MILLISECONDS),
                "the workflow must still be parked — a failed probe is not a wake");
        store.readsUnavailable = false;

        assertEquals(WorkflowStatus.WAITING_SIGNAL,
                store.getInstance("probe-await-1").orElseThrow().status(),
                "a store outage during the advisory wake-recheck probe must leave the "
                        + "instance WAITING_SIGNAL — before the Issue 20 fix it was durably FAILED");
        assertEquals(0, store.getEvents(instanceId).stream()
                        .filter(e -> e.eventType() == EventType.WORKFLOW_FAILED).count(),
                "no WORKFLOW_FAILED may be recorded for an infra blip while parked");
        assertTrue(executor.isRunning("probe-await-1"),
                "the parked run must still be alive on this node");

        // The store heals and the signal arrives — the await must complete
        // normally, exactly as if the outage had never happened.
        executor.deliverSignal("probe-await-1", "approval", "granted");
        assertTrue(completed.await(5, TimeUnit.SECONDS),
                "the healed await must consume the signal and finish");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(WorkflowStatus.COMPLETED,
                        store.getInstance("probe-await-1").orElseThrow().status()));
        assertEquals("granted", workflow.received);
        assertEquals(1, store.getEvents(instanceId).stream()
                        .filter(e -> e.eventType() == EventType.SIGNAL_RECEIVED).count(),
                "the post-heal consumption must memoize SIGNAL_RECEIVED exactly once");
    }

    @Test
    @DisplayName("a parked sleep stays WAITING_TIMER through several failed probes and wakes once the store heals")
    void parkedSleep_storeOutageDuringRecheck_staysParkedAndWakesAfterHeal() throws Exception {
        var completed = new CountDownLatch(1);
        var workflow = new SleepingWorkflow(completed);
        var method = SleepingWorkflow.class.getMethod("run", String.class);

        var instanceId = executor.startWorkflow("probe-sleep-1", "SleepingWorkflow", "default",
                "hello", workflow, method);

        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.getInstance("probe-sleep-1")
                        .map(i -> i.status() == WorkflowStatus.WAITING_TIMER)
                        .orElse(false));

        store.readsUnavailable = true;
        await().atMost(Duration.ofSeconds(5)).until(() -> store.failedReads.get() >= 5);

        assertFalse(completed.await(200, TimeUnit.MILLISECONDS),
                "the workflow must still be parked — a failed probe is not a wake");
        store.readsUnavailable = false;

        assertEquals(WorkflowStatus.WAITING_TIMER,
                store.getInstance("probe-sleep-1").orElseThrow().status(),
                "a store outage during sleep()'s timer recheck must leave the instance "
                        + "WAITING_TIMER — the Issue 17 recheck loop shares the Issue 20 defect");
        assertEquals(0, store.getEvents(instanceId).stream()
                        .filter(e -> e.eventType() == EventType.WORKFLOW_FAILED).count(),
                "no WORKFLOW_FAILED may be recorded for an infra blip while sleeping");
        assertTrue(executor.isRunning("probe-sleep-1"));

        // Cross-node fire after heal (row-only CAS, no local unpark): the
        // recheck must observe it within one interval and complete the run.
        var timer = store.findTimer(instanceId, "sleep-1").orElseThrow();
        assertTrue(store.markTimerFired(timer.id()));
        assertTrue(completed.await(5, TimeUnit.SECONDS),
                "the healed timer recheck must observe the fired row and wake");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(WorkflowStatus.COMPLETED,
                        store.getInstance("probe-sleep-1").orElseThrow().status()));
    }

    @Test
    @DisplayName("a terminate arriving during the outage is honoured on the first successful probe after heal")
    void terminateDuringOutage_honouredOnFirstSuccessfulProbeAfterHeal() throws Exception {
        var completed = new CountDownLatch(1);
        var workflow = new AwaitingWorkflow(completed);
        var method = AwaitingWorkflow.class.getMethod("run", String.class);

        var instanceId = executor.startWorkflow("probe-term-1", "AwaitingWorkflow", "default",
                "hello", workflow, method);

        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.getInstance("probe-term-1")
                        .map(i -> i.status() == WorkflowStatus.WAITING_SIGNAL)
                        .orElse(false));

        // Captured before the outage: the row cannot change while the
        // workflow is parked (status writes happen only on wake).
        var latest = store.getInstance("probe-term-1").orElseThrow();

        store.readsUnavailable = true;
        await().atMost(Duration.ofSeconds(5)).until(() -> store.failedReads.get() >= 3);

        // A remote node terminates while this node cannot read: only the
        // durable row transitions (writes are not part of the outage; the
        // remote node is unaffected by OUR partition).
        store.updateInstance(latest.toBuilder()
                .status(WorkflowStatus.TERMINATED)
                .completedAt(Instant.now())
                .updatedAt(Instant.now())
                .version(latest.version() + 1)
                .build());

        // Fail open while blind: still parked, nothing failed.
        assertFalse(completed.await(200, TimeUnit.MILLISECONDS));
        assertTrue(executor.isRunning("probe-term-1"),
                "an unreachable store means keep parking — never guess terminated, never fail");

        // Heal: the first successful probe must observe TERMINATED and stand
        // the run down without writing anything.
        store.readsUnavailable = false;
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertFalse(executor.isRunning("probe-term-1"),
                        "the first successful recheck after heal must honour the terminate"));
        assertEquals(WorkflowStatus.TERMINATED,
                store.getInstance("probe-term-1").orElseThrow().status());
        assertEquals(0, store.getEvents(instanceId).stream()
                        .filter(e -> e.eventType() == EventType.WORKFLOW_FAILED).count(),
                "a terminated stand-down writes nothing");
        assertFalse(completed.await(100, TimeUnit.MILLISECONDS),
                "the workflow body must not run past the await after a terminate");
    }

    // ── store double ───────────────────────────────────────────────────

    /**
     * {@link VersionedInMemoryStore} whose PROBE READ methods —
     * {@code getInstance}, {@code getUnconsumedSignals}, {@code findTimer} —
     * throw while {@link #readsUnavailable} is set, modelling a node-side
     * partition exceeding the connection pool's acquisition timeout
     * (the store threw {@code UncheckedSqlException} in the chaos run; any
     * store-raised {@code RuntimeException} must get the same treatment).
     * Writes are unaffected: the outage models THIS node's view; other nodes
     * (and the store itself) stay healthy.
     */
    private static final class OutageStore extends VersionedInMemoryStore {
        volatile boolean readsUnavailable;
        final AtomicInteger failedReads = new AtomicInteger();

        @Override
        public Optional<WorkflowInstance> getInstance(String workflowId) {
            failIfUnavailable();
            return super.getInstance(workflowId);
        }

        @Override
        public List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName) {
            failIfUnavailable();
            return super.getUnconsumedSignals(workflowId, signalName);
        }

        @Override
        public Optional<WorkflowTimer> findTimer(UUID workflowInstanceId, String timerId) {
            failIfUnavailable();
            return super.findTimer(workflowInstanceId, timerId);
        }

        private void failIfUnavailable() {
            if (readsUnavailable) {
                failedReads.incrementAndGet();
                throw new RuntimeException("Query failed: store unreachable (injected outage)");
            }
        }
    }

    // ── fixtures ───────────────────────────────────────────────────────

    /** Awaits one signal with a generous timeout, then finishes. */
    public static class AwaitingWorkflow {
        private final CountDownLatch completed;
        volatile String received = "unset";

        public AwaitingWorkflow(CountDownLatch completed) {
            this.completed = completed;
        }

        public String run(String input) {
            received = WorkflowContext.current()
                    .awaitSignal("approval", String.class, Duration.ofMinutes(10));
            completed.countDown();
            return received;
        }
    }

    /** Sleeps far longer than the test; the store outage happens mid-park. */
    public static class SleepingWorkflow {
        private final CountDownLatch completed;

        public SleepingWorkflow(CountDownLatch completed) {
            this.completed = completed;
        }

        public String run(String input) {
            WorkflowContext.current().sleep(Duration.ofMinutes(10));
            completed.countDown();
            return "awake";
        }
    }
}
