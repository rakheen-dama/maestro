package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.spi.WorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Why RULING 10's store fix has a wider blast radius than a single workflow:
 * {@link WorkflowExecutor#recoverWorkflows} wraps <b>none</b> of its per-instance
 * work in a {@code try}/{@code catch}, so anything thrown while adopting one
 * instance ends the pass for every instance behind it in the batch.
 *
 * <p>Both halves are pinned here against the two possible store behaviours,
 * because the difference between them <em>is</em> RULING 10:
 *
 * <ul>
 *   <li><b>Throwing</b> (what {@code WorkflowStatus.valueOf} did on a status
 *       string written by a newer node): the pass aborts and the healthy
 *       workflow behind the poisoned one is never adopted — a workflow that has
 *       nothing whatever to do with the mixed-version row silently stops making
 *       progress on this node.</li>
 *   <li><b>Skipping</b> (returning empty, what the fixed mapper does): the
 *       poisoned instance is passed over and the healthy one <em>is</em>
 *       adopted.</li>
 * </ul>
 *
 * <p>The store here fakes the mapper's two behaviours rather than parsing SQL —
 * the mapper itself is pinned against real Postgres in
 * {@code PostgresUnknownStatusMappingTest}. What this test owns is the
 * consequence: that "skip" is a survivable answer for the recovery loop and
 * "throw" is not.
 */
@DisplayName("One unreadable instance must not end the recovery pass for every workflow behind it")
class RecoveryPassIsolationTest {

    private PoisonableStore store;
    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        store = new PoisonableStore();
        var serializer = new PayloadSerializer(JsonMapper.builder().build());
        executor = new WorkflowExecutor(store, null, null, null, serializer, "old-node");
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    @Timeout(30)
    @DisplayName("a skipped instance is passed over and the healthy workflow behind it is adopted")
    void skippingOneInstanceLetsThePassContinue() {
        var runs = new AtomicInteger();
        seedRecoverable("poisoned-wf");
        seedRecoverable("healthy-wf");
        // The fixed mapper's answer: this build cannot interpret that row, so
        // the instance is invisible.
        store.invisibleTo(Optional::empty, "poisoned-wf"::equals);

        var adopted = executor.recoverWorkflows(registrations(runs));

        await().atMost(Duration.ofSeconds(5)).until(() -> runs.get() >= 1);
        assertAll(
                () -> assertEquals(1, adopted,
                        "exactly the healthy workflow is adopted; the unreadable one is "
                                + "left for an upgraded node"),
                () -> assertEquals(1, runs.get(),
                        "the workflow behind the poisoned one must still run"));
    }

    @Test
    @Timeout(30)
    @DisplayName("a THROWING instance read ends the pass — the healthy workflow behind it never runs")
    void throwingOnOneInstanceEndsTheWholePass() {
        var runs = new AtomicInteger();
        seedRecoverable("poisoned-wf");
        seedRecoverable("healthy-wf");
        // The pre-RULING-10 mapper's answer: WorkflowStatus.valueOf on a string
        // this build does not define.
        store.invisibleTo(() -> {
            throw new IllegalArgumentException(
                    "No enum constant WorkflowStatus.HIBERNATING_IN_A_NEWER_MAESTRO");
        }, "poisoned-wf"::equals);

        assertThrows(IllegalArgumentException.class,
                () -> executor.recoverWorkflows(registrations(runs)),
                "nothing in recoverWorkflows contains a per-instance failure");

        assertEquals(0, runs.get(),
                "this is the damage RULING 10 removes: a workflow with no connection to the "
                        + "unreadable row never got adopted, and would not until the row "
                        + "changed or the node restarted");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private Map<String, WorkflowRegistration> registrations(AtomicInteger runs) {
        return Map.of("RecoverableWorkflow", new WorkflowRegistration(
                "RecoverableWorkflow", "default", new RecoverableWorkflow(runs), workflowMethod()));
    }

    private void seedRecoverable(String workflowId) {
        var now = Instant.now();
        store.createInstance(WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId(workflowId)
                .runId(UUID.randomUUID())
                .workflowType("RecoverableWorkflow")
                .taskQueue("default")
                .status(WorkflowStatus.RUNNING)
                .serviceName("old-node")
                .eventSequence(0)
                .startedAt(now)
                .updatedAt(now)
                .version(0)
                .build());
    }

    private static Method workflowMethod() {
        try {
            return RecoverableWorkflow.class.getMethod("run");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Counts the runs a recovery pass actually reached. */
    @DurableWorkflow(name = "RecoverableWorkflow")
    public static class RecoverableWorkflow {

        private final AtomicInteger runs;

        RecoverableWorkflow(AtomicInteger runs) {
            this.runs = runs;
        }

        /** @return a fixed result */
        @WorkflowMethod
        public String run() {
            runs.incrementAndGet();
            return "done";
        }
    }

    /**
     * Minimal store whose {@code getInstance} can be made to answer for one
     * workflow ID the way either mapper does — empty, or by throwing.
     *
     * <h2>Thread Safety</h2>
     * <p>Safe for the executor's virtual threads and the test thread.
     */
    private static final class PoisonableStore implements WorkflowStore {

        private final ConcurrentHashMap<String, WorkflowInstance> byWorkflowId =
                new ConcurrentHashMap<>();
        private volatile java.util.function.Supplier<Optional<WorkflowInstance>> poisonAnswer =
                Optional::empty;
        private volatile Predicate<String> poisoned = id -> false;

        void invisibleTo(java.util.function.Supplier<Optional<WorkflowInstance>> answer,
                         Predicate<String> which) {
            this.poisonAnswer = answer;
            this.poisoned = which;
        }

        @Override
        public WorkflowInstance createInstance(WorkflowInstance instance) {
            byWorkflowId.put(instance.workflowId(), instance);
            return instance;
        }

        @Override
        public Optional<WorkflowInstance> getInstance(String workflowId) {
            if (poisoned.test(workflowId)) {
                return poisonAnswer.get();
            }
            return Optional.ofNullable(byWorkflowId.get(workflowId));
        }

        @Override
        public List<WorkflowInstance> getRecoverableInstances() {
            // Ordered so the poisoned instance is adopted FIRST — the healthy
            // one behind it is what the abort costs.
            return byWorkflowId.values().stream()
                    .sorted((a, b) -> a.workflowId().compareTo(b.workflowId()))
                    .toList();
        }

        @Override
        public void updateInstance(WorkflowInstance instance) {
            byWorkflowId.put(instance.workflowId(), instance);
        }

        @Override
        public void appendEvent(WorkflowEvent event) {
            // no event assertions in this suite
        }

        @Override
        public Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int sequenceNumber) {
            return Optional.empty();
        }

        @Override
        public List<WorkflowEvent> getEvents(UUID instanceId) {
            return List.of();
        }

        @Override
        public int deleteFailureEvents(UUID instanceId) {
            return 0;
        }

        @Override
        public void saveSignal(WorkflowSignal signal) {
            // no signals in this suite
        }

        @Override
        public List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName) {
            return List.of();
        }

        @Override
        public boolean markSignalConsumed(UUID signalId) {
            return true;
        }

        @Override
        public void adoptOrphanedSignals(String workflowId, UUID instanceId) {
            // no signals in this suite
        }

        @Override
        public void saveTimer(WorkflowTimer timer) {
            // no timers in this suite
        }

        @Override
        public List<WorkflowTimer> getDueTimers(Instant now, int batchSize) {
            return List.of();
        }

        @Override
        public Optional<WorkflowTimer> findTimer(UUID workflowInstanceId, String timerId) {
            return Optional.empty();
        }

        @Override
        public boolean markTimerFired(UUID timerId) {
            return true;
        }

        @Override
        public boolean markTimerCancelled(UUID timerId) {
            return false;
        }
    }
}
