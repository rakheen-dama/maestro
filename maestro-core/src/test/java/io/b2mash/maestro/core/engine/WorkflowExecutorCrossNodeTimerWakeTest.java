package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.TimerCancelledException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-node timer wake (Issue 17): a timer whose {@code PENDING → FIRED} (or
 * {@code → CANCELLED}) transition happens on a node that does <em>not</em> own
 * the parked virtual thread must still wake the sleeping workflow.
 *
 * <p>The remote node's {@code fireTimer}/{@code cancelTimer} is simulated by
 * writing the CAS directly through the store — exactly what a remote leader's
 * call amounts to from this JVM's perspective: the durable row transitions,
 * but no local unpark ever happens (the {@link ParkingLot} is per-JVM).
 * Before the fix, {@code sleep()} parked indefinitely and the workflow wedged
 * forever; the fixed {@code sleep()} re-checks the durable timer row every
 * wake-recheck interval, mirroring {@code SignalManager.awaitSignal}.
 */
@DisplayName("A timer fired or cancelled on another node wakes the locally parked sleep (Issue 17)")
class WorkflowExecutorCrossNodeTimerWakeTest {

    /** Short enough that a recheck-driven wake lands well inside the await bounds. */
    private static final Duration SHORT_RECHECK = Duration.ofMillis(200);

    private VersionedInMemoryStore store;
    private PayloadSerializer serializer;
    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        store = new VersionedInMemoryStore();
        serializer = new PayloadSerializer(new ObjectMapper());
        executor = newExecutor(SHORT_RECHECK);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    private WorkflowExecutor newExecutor(Duration wakeRecheckInterval) {
        return new WorkflowExecutor(store, null, null, null, serializer, "test-service",
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX,
                WorkflowInstanceLockManager.DEFAULT_LOCK_TTL,
                true, WorkflowExecutor.DEFAULT_SHUTDOWN_TIMEOUT, wakeRecheckInterval);
    }

    @Test
    @DisplayName("a timer marked FIRED through the store alone wakes the parked sleep within the recheck interval")
    void remoteFire_storeOnlyTransition_wakesParkedSleepWithinRecheckInterval() throws Exception {
        var completed = new CountDownLatch(1);
        var workflow = new SleepingWorkflow(Duration.ofMinutes(10), completed);
        var method = SleepingWorkflow.class.getMethod("run", String.class);

        var instanceId = executor.startWorkflow("xnode-fire-1", "SleepingWorkflow", "default",
                "hello", workflow, method);

        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.getInstance("xnode-fire-1")
                        .map(i -> i.status() == WorkflowStatus.WAITING_TIMER)
                        .orElse(false));

        // The remote leader's fireTimer, as seen from this JVM: the row CASes
        // PENDING → FIRED in the shared store, and nothing unparks locally.
        var timer = store.findTimer(instanceId, "sleep-1").orElseThrow();
        assertEquals(TimerStatus.PENDING, timer.status());
        assertTrue(store.markTimerFired(timer.id()), "the simulated remote fire must win the CAS");

        assertTrue(completed.await(5, TimeUnit.SECONDS),
                "a timer fired on another node must wake the parked sleep within the "
                        + "wake-recheck interval — before the Issue 17 fix this wedged forever");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(WorkflowStatus.COMPLETED,
                        store.getInstance("xnode-fire-1").orElseThrow().status()));

        assertEquals(1, store.getEvents(instanceId).stream()
                        .filter(e -> e.eventType() == EventType.TIMER_FIRED).count(),
                "the recheck-driven wake must memoize TIMER_FIRED exactly once, "
                        + "identical to a locally unparked wake");
        assertEquals(0, store.getEvents(instanceId).stream()
                .filter(e -> e.eventType() == EventType.TIMER_CANCELLED).count());
    }

    @Test
    @DisplayName("a timer marked CANCELLED through the store alone wakes the parked sleep with the Issue 13 outcome")
    void remoteCancel_storeOnlyTransition_wakesParkedSleepWithinRecheckInterval() throws Exception {
        var completed = new CountDownLatch(1);
        var workflow = new CancellableSleepWorkflow(Duration.ofMinutes(10), completed);
        var method = CancellableSleepWorkflow.class.getMethod("run", String.class);

        var instanceId = executor.startWorkflow("xnode-cancel-1", "CancellableSleepWorkflow", "default",
                "hello", workflow, method);

        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.getInstance("xnode-cancel-1")
                        .map(i -> i.status() == WorkflowStatus.WAITING_TIMER)
                        .orElse(false));

        // The remote node's cancelTimer, as seen from this JVM: row-only CAS.
        var timer = store.findTimer(instanceId, "sleep-1").orElseThrow();
        assertTrue(store.markTimerCancelled(timer.id()), "the simulated remote cancel must win the CAS");

        assertTrue(completed.await(5, TimeUnit.SECONDS),
                "a timer cancelled on another node must wake the parked sleep within the "
                        + "wake-recheck interval — before the Issue 17 fix this wedged forever");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(WorkflowStatus.COMPLETED,
                        store.getInstance("xnode-cancel-1").orElseThrow().status()));

        assertEquals("cancelled:sleep-1", workflow.outcome,
                "the wake must surface as the catchable Issue 13 TimerCancelledException");
        assertEquals(1, store.getEvents(instanceId).stream()
                        .filter(e -> e.eventType() == EventType.TIMER_CANCELLED).count(),
                "the recheck-driven wake must memoize TIMER_CANCELLED exactly once");
        assertEquals(0, store.getEvents(instanceId).stream()
                .filter(e -> e.eventType() == EventType.TIMER_FIRED).count());
    }

    @Test
    @DisplayName("a timer still PENDING keeps the sleep parked across many recheck intervals")
    void pendingTimer_staysParkedAcrossRecheckIntervals() throws Exception {
        var completed = new CountDownLatch(1);
        var workflow = new SleepingWorkflow(Duration.ofMinutes(10), completed);
        var method = SleepingWorkflow.class.getMethod("run", String.class);

        var instanceId = executor.startWorkflow("xnode-pending-1", "SleepingWorkflow", "default",
                "hello", workflow, method);

        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.getInstance("xnode-pending-1")
                        .map(i -> i.status() == WorkflowStatus.WAITING_TIMER)
                        .orElse(false));

        // Several recheck intervals pass; the durable row never transitions.
        assertFalse(completed.await(1, TimeUnit.SECONDS),
                "a PENDING timer must keep the workflow parked — the recheck must not "
                        + "invent a wake");
        assertEquals(WorkflowStatus.WAITING_TIMER,
                store.getInstance("xnode-pending-1").orElseThrow().status());
        assertEquals(0, store.getEvents(instanceId).stream()
                        .filter(e -> e.eventType() == EventType.TIMER_FIRED).count(),
                "no TIMER_FIRED event may be written while the timer is pending");

        // A real (local) fire still releases it — the fast path is intact.
        var timer = store.findTimer(instanceId, "sleep-1").orElseThrow();
        executor.fireTimer("xnode-pending-1", "sleep-1", timer.id());
        assertTrue(completed.await(5, TimeUnit.SECONDS),
                "firing the timer must release the parked workflow");
    }

    @Test
    @DisplayName("a locally fired timer still wakes instantly with the default 30s recheck interval")
    void localFire_fastPathUnchanged_withDefaultRecheckInterval() throws Exception {
        // Leader == owner (the single-node topology): the unpark is local and
        // instant. With the default 30s interval, completing well inside the
        // await bound proves the wake came from the unpark, not the recheck.
        executor.shutdown();
        executor = newExecutor(SignalManager.DEFAULT_WAKE_RECHECK_INTERVAL);

        var completed = new CountDownLatch(1);
        var workflow = new SleepingWorkflow(Duration.ofMinutes(10), completed);
        var method = SleepingWorkflow.class.getMethod("run", String.class);

        var instanceId = executor.startWorkflow("local-fire-1", "SleepingWorkflow", "default",
                "hello", workflow, method);

        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.getInstance("local-fire-1")
                        .map(i -> i.status() == WorkflowStatus.WAITING_TIMER)
                        .orElse(false));

        var timer = store.findTimer(instanceId, "sleep-1").orElseThrow();
        executor.fireTimer("local-fire-1", "sleep-1", timer.id());

        assertTrue(completed.await(5, TimeUnit.SECONDS),
                "the local unpark fast path must wake the sleep instantly — far inside "
                        + "the 30s default recheck interval");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(WorkflowStatus.COMPLETED,
                        store.getInstance("local-fire-1").orElseThrow().status()));
    }

    // ── fixtures ───────────────────────────────────────────────────────

    /** Sleeps once; the tests transition the timer row out from under it. */
    public static class SleepingWorkflow {
        private final Duration nap;
        private final CountDownLatch completed;

        public SleepingWorkflow(Duration nap, CountDownLatch completed) {
            this.nap = nap;
            this.completed = completed;
        }

        public String run(String input) {
            WorkflowContext.current().sleep(nap);
            completed.countDown();
            return "awake";
        }
    }

    /** Sleeps once, catching a cancelled timer to take a fallback branch. */
    public static class CancellableSleepWorkflow {
        private final Duration nap;
        private final CountDownLatch completed;
        volatile String outcome = "unset";

        public CancellableSleepWorkflow(Duration nap, CountDownLatch completed) {
            this.nap = nap;
            this.completed = completed;
        }

        public String run(String input) {
            try {
                WorkflowContext.current().sleep(nap);
                outcome = "fired";
            } catch (TimerCancelledException e) {
                outcome = "cancelled:" + e.timerId();
            }
            completed.countDown();
            return outcome;
        }
    }
}
