package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.TestTerminalWait;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.spi.RunLease;
import io.b2mash.maestro.core.spi.SignalNotifier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Finding 3.2: a wake permit is stored only for a workflow that runs on this
 * node, for every wake source, without losing a wake.
 *
 * <p>The first two tests are the audit's reproduction,
 * {@code docs/superpowers/plans/2026-11-12-engine-hardening-cycle/evidence/repro-tests/AuditB32WakePermitLeakTest.java.txt},
 * ported to the executor builder and {@link WorkflowExecutor#wakePermitCount()}:
 * two nodes share one store, the workflow runs on node A and the signal (and
 * the timer fire) arrive on node B, as they do when B owns the Kafka partition
 * or holds timer-poller leadership. Before the fix node B kept one permit per
 * delivery for the life of the process.
 */
class WakePermitLocalityTest {

    private static final Duration RECHECK = Duration.ofMillis(100);

    private final PayloadSerializer serializer = new PayloadSerializer(JsonMapper.builder().build());
    private final List<WorkflowExecutor> executors = new ArrayList<>();

    @AfterEach
    void tearDown() {
        executors.forEach(WorkflowExecutor::shutdown);
    }

    private WorkflowExecutor node(VersionedInMemoryStore store) {
        var executor = WorkflowExecutor.builder(store, serializer, "svc")
                .instanceLockTtl(Duration.ofSeconds(30))
                .lifecycleEventsEnabled(false)
                .shutdownTimeout(Duration.ofSeconds(5))
                .wakeRecheckInterval(RECHECK)
                .build();
        executors.add(executor);
        return executor;
    }

    // ── The ported reproduction ────────────────────────────────────────

    @Test
    void signalsDeliveredOnANodeNotRunningTheWorkflow_leaveNoPermit() throws Exception {
        var store = new VersionedInMemoryStore();
        var nodeA = node(store);
        var nodeB = node(store);
        var method = AwaitOnce.class.getMethod("run");
        for (int i = 0; i < 50; i++) {
            var id = "leak-" + i;
            nodeA.startWorkflow(id, "AwaitOnce", "default", null, AwaitOnce::new, method);
            TestTerminalWait.awaitStatus(store, id, WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(5));
            nodeB.deliverSignal(id, "go", "payload-" + i);    // B consumed the Kafka record
            TestTerminalWait.awaitStatus(store, id, WorkflowStatus.COMPLETED, Duration.ofSeconds(5));
        }
        // Late duplicates for finished workflows, and signals for ids no run exists for.
        nodeB.deliverSignal("leak-0", "go", "late-duplicate");
        nodeB.deliverSignal("never-started", "go", "x");

        assertEquals(0, nodeB.wakePermitCount(),
                "every run finished on node A; node B should hold no wake permits");
    }

    @Test
    void sleepTimersFiredByALeaderNotRunningTheWorkflow_leaveNoPermit() {
        var store = new VersionedInMemoryStore();
        node(store);
        var nodeB = node(store);
        for (int i = 0; i < 50; i++) {
            var row = new WorkflowTimer(UUID.randomUUID(), UUID.randomUUID(), "remote-" + i, "sleep-3",
                    Instant.now(), TimerStatus.PENDING, Instant.now());
            store.saveTimer(row);
            nodeB.fireTimer(row.workflowId(), row.timerId(), row.id());   // B is the poller leader
        }
        assertEquals(0, nodeB.wakePermitCount(),
                "none of these workflows runs on node B");
    }

    // ── A signal for a workflow that later starts here ─────────────────

    /**
     * A wake for a workflow not running here stores nothing, and the wake is
     * not lost: when the workflow later starts and awaits on this node, its
     * await reads the durable row before it parks. The default 30 s re-check
     * means only that read can complete it within the bound.
     */
    @Test
    void aSignalDeliveredBeforeTheWorkflowStartsHere_leavesNoPermitAndIsFoundAtTheAwait() throws Exception {
        var store = new VersionedInMemoryStore();
        var executor = WorkflowExecutor.builder(store, serializer, "svc").build();
        executors.add(executor);

        executor.deliverSignal("pre-start", "go", "x");
        assertEquals(0, executor.wakePermitCount(), "the workflow does not run on this node yet");

        executor.startWorkflow("pre-start", "AwaitLong", "default", null, AwaitLong::new,
                AwaitLong.class.getMethod("run"));
        assertEquals(WorkflowStatus.COMPLETED,
                statusWithin(store, "pre-start", WorkflowStatus.COMPLETED, Duration.ofSeconds(5)));
    }

    // ── The cross-node NOTIFY path ─────────────────────────────────────

    @Test
    void aNotificationArrivingAfterTheRunEnded_leavesNoPermit() throws Exception {
        var store = new VersionedInMemoryStore();
        var notifier = new LingeringNotifier();
        var executor = WorkflowExecutor.builder(store, serializer, "svc")
                .instanceLockTtl(Duration.ofSeconds(30))
                .lifecycleEventsEnabled(false)
                .shutdownTimeout(Duration.ofSeconds(5))
                .wakeRecheckInterval(RECHECK)
                .signalNotifier(notifier)
                .build();
        executors.add(executor);
        var method = AwaitOnce.class.getMethod("run");

        executor.startWorkflow("late-notify", "AwaitOnce", "default", null, AwaitOnce::new, method);
        TestTerminalWait.awaitStatus(store, "late-notify", WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(5));
        executor.deliverSignal("late-notify", "go", "x");
        TestTerminalWait.awaitStatus(store, "late-notify", WorkflowStatus.COMPLETED, Duration.ofSeconds(5));
        // The run drops its registration before its permit cleanup runs, and a
        // wake that reached the await between its re-check parks can leave a
        // permit until that cleanup: wait for both, so the count below can
        // only be the late notification's.
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while ((executor.isRunning("late-notify") || executor.wakePermitCount() != 0)
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(executor.isRunning("late-notify"), "the run's finally has not run within 5 s");
        assertEquals(0, executor.wakePermitCount(), "the run's permit cleanup has not run within 5 s");

        notifier.callbacks.get("late-notify").onSignal("late-notify", "go");
        assertEquals(0, executor.wakePermitCount(), "the run is over on this node");
    }

    // ── The race the permit exists for ─────────────────────────────────

    /**
     * Review Focus 1. The signal is persisted, and its wake delivered, after
     * the await's last store read and before its park: {@link RaceStore}
     * delivers from inside that read and then answers it as if it ran just
     * before the save. Only a permit can wake the park before the default
     * 30 s re-check, so completing within 5 s proves the permit was kept.
     */
    @Test
    void aSignalDeliveredBeforeALocalAwaitParks_wakesItAtOnce() throws Exception {
        var store = new RaceStore();
        var executor = WorkflowExecutor.builder(store, serializer, "svc").build();
        executors.add(executor);
        store.executor = executor;

        executor.startWorkflow("race-await", "AwaitLong", "default", null, AwaitLong::new,
                AwaitLong.class.getMethod("run"));
        assertEquals(WorkflowStatus.COMPLETED,
                statusWithin(store, "race-await", WorkflowStatus.COMPLETED, Duration.ofSeconds(5)));
        assertEquals(1, store.permitsSeenAfterDelivery, "the delivery stored a permit for the run about to park");
    }

    /**
     * Review Focus 2. The leader owns the workflow and fires its sleep timer
     * after the {@code TIMER_SCHEDULED} memo and before the sleep parks
     * ({@link RaceStore} fires from inside that append). A live sleep does not
     * re-read its row before parking, so only a permit can wake it before the
     * default 30 s re-check.
     */
    @Test
    void aSleepTimerFiredBeforeALocalSleepParks_wakesItAtOnce() throws Exception {
        var store = new RaceStore();
        var executor = WorkflowExecutor.builder(store, serializer, "svc").build();
        executors.add(executor);
        store.executor = executor;

        executor.startWorkflow("race-sleep", "SleepOnce", "default", null, SleepOnce::new,
                SleepOnce.class.getMethod("run"));
        assertEquals(WorkflowStatus.COMPLETED,
                statusWithin(store, "race-sleep", WorkflowStatus.COMPLETED, Duration.ofSeconds(5)));
        assertEquals(1, store.permitsSeenAfterFire);
    }

    /**
     * Forces the two interleavings the permit exists for, each once:
     * <ul>
     *   <li>the first {@code getUnconsumedSignals(id, "go")} made while the
     *       instance is {@code WAITING_SIGNAL} (the await's step-1 read)
     *       delivers {@code "go"} on another thread, joins it, records the
     *       permit count, and returns no rows — as if the read ran just before
     *       the save. It does not call {@code super} first, so it holds no
     *       lock of the store's while it waits;</li>
     *   <li>the first {@code TIMER_SCHEDULED} append of a {@code sleep-*} timer
     *       is completed, then the timer is fired on another thread, joined,
     *       and the permit count recorded.</li>
     * </ul>
     */
    static final class RaceStore extends VersionedInMemoryStore {
        volatile WorkflowExecutor executor;
        volatile int permitsSeenAfterDelivery = -1;
        volatile int permitsSeenAfterFire = -1;
        private final AtomicBoolean deliveryDone = new AtomicBoolean();
        private final AtomicBoolean fireDone = new AtomicBoolean();

        @Override
        public List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName) {
            if ("go".equals(signalName)
                    && super.getInstance(workflowId).map(i -> i.status() == WorkflowStatus.WAITING_SIGNAL)
                            .orElse(false)
                    && deliveryDone.compareAndSet(false, true)) {
                runAndJoin(() -> executor.deliverSignal(workflowId, "go", "x"));
                permitsSeenAfterDelivery = executor.wakePermitCount();
                return List.of();
            }
            return super.getUnconsumedSignals(workflowId, signalName);
        }

        @Override
        public void appendEvent(WorkflowEvent event, RunLease lease) {
            super.appendEvent(event, lease);
            if (event.eventType() != EventType.TIMER_SCHEDULED
                    || event.payload() == null || !event.payload().has("timerId")) {
                return;
            }
            var timerId = event.payload().get("timerId").stringValue();
            if (timerId == null || !timerId.startsWith("sleep-") || !fireDone.compareAndSet(false, true)) {
                return;
            }
            var row = super.findTimer(event.workflowInstanceId(), timerId).orElseThrow();
            runAndJoin(() -> executor.fireTimer(row.workflowId(), timerId, row.id()));
            permitsSeenAfterFire = executor.wakePermitCount();
        }

        private static void runAndJoin(Runnable action) {
            var thread = Thread.ofVirtual().start(action);
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while forcing the race", e);
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    /**
     * Polls the instance every 20 ms until its status is {@code wanted} or
     * {@code within} elapses.
     *
     * @return the last status read ({@code null} if no instance was stored)
     */
    private static @Nullable WorkflowStatus statusWithin(VersionedInMemoryStore store, String id,
                                                         WorkflowStatus wanted, Duration within)
            throws InterruptedException {
        var deadline = System.nanoTime() + within.toNanos();
        WorkflowStatus last = null;
        while (true) {
            last = store.getInstance(id).map(i -> i.status()).orElse(null);
            if (last == wanted || System.nanoTime() >= deadline) {
                return last;
            }
            Thread.sleep(20);
        }
    }

    /**
     * A notifier whose {@code unsubscribe} leaves the callback registered: it
     * models a dispatch that looked its subscriber up just before the await
     * unsubscribed, and so reaches the engine after the run ended.
     */
    static final class LingeringNotifier implements SignalNotifier {
        final ConcurrentHashMap<String, SignalCallback> callbacks = new ConcurrentHashMap<>();

        @Override
        public void publish(String workflowId, String signalName) {
            // Cross-node delivery is driven by the test through the callback.
        }

        @Override
        public void subscribe(String workflowId, SignalCallback callback) {
            callbacks.put(workflowId, callback);
        }

        @Override
        public void unsubscribe(String workflowId) {
            // Deliberately left registered — see the class Javadoc.
        }
    }

    @DurableWorkflow(name = "AwaitOnce")
    public static class AwaitOnce {
        @WorkflowMethod
        public String run() {
            return WorkflowContext.current().awaitSignal("go", String.class, Duration.ofSeconds(30));
        }
    }

    @DurableWorkflow(name = "AwaitLong")
    public static class AwaitLong {
        @WorkflowMethod
        public String run() {
            return WorkflowContext.current().awaitSignal("go", String.class, Duration.ofHours(1));
        }
    }

    @DurableWorkflow(name = "SleepOnce")
    public static class SleepOnce {
        @WorkflowMethod
        public String run() {
            WorkflowContext.current().sleep(Duration.ofHours(1));
            return "slept";
        }
    }
}
