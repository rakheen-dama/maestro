package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.observe.StandDownReason;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RULING 9 — the payload guard applies to <b>every path that deserializes a
 * persisted payload it did not itself just write</b>, not only the replay
 * caller that design §6.3 originally scoped it to.
 *
 * <h2>Why the original scope was too narrow</h2>
 * <p>The exposure needs <b>no author error at all</b>; it comes out of the
 * engine's own normal tolerated state. Instance locks are best-effort and
 * expire on TTL, so two runners of one workflow is a state the engine is
 * designed to survive: an old node reads sequence N empty, executes the
 * activity live, and loses the append race to a newer node whose event at N
 * carries a type — or a payload shape — this build does not define. The old
 * node then adopts that winner. Before this guard it either
 *
 * <ul>
 *   <li>deserialized a foreign payload as the activity's return type, throwing
 *       {@code SerializationException} into {@code executeWorkflow}'s
 *       {@code catch (Exception)} — <b>FAILED plus full compensation</b> for a
 *       workflow that never failed; or</li>
 *   <li>succeeded, and memoized a <b>silently wrong value</b> — the worse of
 *       the two, because nothing anywhere reports it.</li>
 * </ul>
 *
 * <p>Two further paths deserialize persisted state on every run and had the
 * same hole: the workflow <b>input</b> (re-read on every recovery run) and a
 * consumed <b>signal payload</b>.
 *
 * <p>Each test therefore asserts the positive durable facts — terminal status
 * and {@code COMPENSATION_STARTED} count — not merely that an exception type
 * changed.
 */
@DisplayName("A persisted payload this build cannot read stands the run down — never FAILED, never compensated")
class ForeignPayloadStandDownTest {

    /** A shape no build of this repo produces for a {@code String} result. */
    private static final String FOREIGN_PAYLOAD = "{\"reshaped\":\"by a newer node\"}";

    private static final Duration BOUND = Duration.ofSeconds(5);

    private VersionedInMemoryStore store;
    private RecordingEngineObserver observer;
    private PayloadSerializer serializer;
    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        store = new VersionedInMemoryStore();
        observer = new RecordingEngineObserver();
        serializer = new PayloadSerializer(JsonMapper.builder().build());
        executor = new WorkflowExecutor(store, null, null, null, serializer, "old-node",
                "maestro:lock:", Duration.ofSeconds(30), true,
                Duration.ofSeconds(5), Duration.ofSeconds(30), observer);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    // ── F1: the duplicate-adopt branch (ActivityInvocationHandler) ────────

    @Test
    @Timeout(30)
    @DisplayName("losing the append race to a newer node's UNKNOWN-typed event stands down — it does "
            + "NOT adopt the winner's value")
    void adoptingAnUnknownTypedWinner_standsDownInsteadOfMemoizingAWrongValue() {
        var compensations = new AtomicInteger();
        var workflowId = "adopt-unknown-type";
        // The winner's payload is a perfectly readable String — so nothing
        // downstream would fail. Pre-guard this run adopted "from-the-future"
        // and COMPLETED with it: a silently wrong memoized value, reported
        // nowhere. The TYPE is the only thing that says "do not trust this".
        var instanceId = startWithWinner(workflowId, compensations,
                EventType.fromStoredName("EVT_FROM_A_NEWER_MAESTRO"),
                serializer.serialize("from-the-future"));

        awaitRunEnded(workflowId);

        var instance = store.getInstance(workflowId).orElseThrow();
        assertAll(
                () -> assertNotEquals(WorkflowStatus.COMPLETED, instance.status(),
                        "completing here means the run adopted a value written by a build "
                                + "whose event type it cannot even name — the silently-wrong "
                                + "memoized value RULING 9 exists to stop"),
                () -> assertEquals(WorkflowStatus.RUNNING, instance.status(),
                        "the stand-down writes no status"),
                () -> assertEquals(0, countOfType(instanceId, EventType.WORKFLOW_COMPLETED)),
                () -> assertEquals(0, countOfType(instanceId, EventType.WORKFLOW_FAILED)),
                () -> assertEquals(0, countOfType(instanceId, EventType.COMPENSATION_STARTED)),
                () -> assertEquals(0, compensations.get()),
                () -> assertTrue(store.getRecoverableInstances().stream()
                                .anyMatch(i -> i.workflowId().equals(workflowId)),
                        "the instance stays adoptable by the node that CAN read the winner"),
                () -> assertEquals(List.of(StandDownReason.UNKNOWN_EVENT_TYPE), reasons(),
                        "and the run reports WHY it stood down"));
    }

    @Test
    @Timeout(30)
    @DisplayName("adopting a winner whose payload this build cannot read stands down — not FAILED, "
            + "not compensated")
    void adoptingAnUnreadableWinnerPayload_standsDownInsteadOfFailingAndCompensating() {
        var compensations = new AtomicInteger();
        var workflowId = "adopt-foreign-payload";
        // A KNOWN type — so only the payload check can catch this — carrying a
        // shape that cannot become the activity's String return value.
        var instanceId = startWithWinner(workflowId, compensations,
                EventType.ACTIVITY_COMPLETED, json(FOREIGN_PAYLOAD));

        awaitRunEnded(workflowId);

        var instance = store.getInstance(workflowId).orElseThrow();
        assertAll(
                () -> assertNotEquals(WorkflowStatus.FAILED, instance.status(),
                        "a payload this node is too old to read is not a workflow failure"),
                () -> assertEquals(WorkflowStatus.RUNNING, instance.status()),
                () -> assertEquals(0, countOfType(instanceId, EventType.WORKFLOW_FAILED),
                        "no WORKFLOW_FAILED event"),
                () -> assertEquals(0, countOfType(instanceId, EventType.COMPENSATION_STARTED),
                        "zero COMPENSATION_STARTED — the compensation stack is non-empty here, "
                                + "so this is the count that would be 1 if the run were treated "
                                + "as a failure"),
                () -> assertEquals(0, compensations.get(),
                        "no compensation action ran"),
                () -> assertTrue(observer.failed().isEmpty(), "workflowFailed must not fire"),
                () -> assertTrue(observer.compensating().isEmpty(),
                        "workflowCompensating must not fire"),
                () -> assertEquals(List.of(StandDownReason.UNKNOWN_EVENT_PAYLOAD), reasons(),
                        "and the run reports WHY it stood down"));
    }

    // ── F2a: the persisted workflow input (WorkflowExecutor) ──────────────

    @Test
    @Timeout(30)
    @DisplayName("a persisted workflow input this build cannot read stands down — not FAILED")
    void unreadableWorkflowInput_standsDownInsteadOfFailing() {
        var workflowId = "foreign-input";
        var impl = new InputWorkflow();
        // A newer node persisted this instance's input in a shape this build's
        // workflow signature cannot accept. It is re-read on EVERY recovery run.
        var instanceId = executor.startWorkflow(workflowId, "InputWorkflow", "default",
                Map.of("reshaped", "by a newer node"), impl, methodOf(InputWorkflow.class));

        awaitRunEnded(workflowId);

        var instance = store.getInstance(workflowId).orElseThrow();
        assertAll(
                () -> assertNotEquals(WorkflowStatus.FAILED, instance.status(),
                        "this throws BEFORE the workflow body runs — recording FAILED marks a "
                                + "workflow that never executed a single step as failed"),
                () -> assertEquals(WorkflowStatus.RUNNING, instance.status()),
                () -> assertEquals(0, countOfType(instanceId, EventType.WORKFLOW_FAILED)),
                () -> assertFalse(impl.ran(), "the body must not have run"),
                () -> assertTrue(store.getRecoverableInstances().stream()
                                .anyMatch(i -> i.workflowId().equals(workflowId)),
                        "an upgraded node must still be able to adopt it"),
                () -> assertEquals(List.of(StandDownReason.UNKNOWN_EVENT_PAYLOAD), reasons()),
                () -> assertEquals("seq=0", observer.standDowns().getFirst().detail(),
                        "sequence 0 names the instance's own input rather than any event"));
    }

    // ── F2b: the consumed signal payload (SignalManager) ──────────────────

    @Test
    @Timeout(30)
    @DisplayName("a consumed signal payload this build cannot read stands down — not FAILED, "
            + "not compensated")
    void unreadableSignalPayload_standsDownInsteadOfFailingAndCompensating() {
        var compensations = new AtomicInteger();
        var workflowId = "foreign-signal";
        var impl = new SignallingWorkflow(compensations);
        var instanceId = executor.startWorkflow(workflowId, "SignallingWorkflow", "default", null,
                impl, methodOf(SignallingWorkflow.class));

        await().atMost(BOUND).until(() -> store.getInstance(workflowId)
                .map(i -> i.status() == WorkflowStatus.WAITING_SIGNAL).orElse(false));

        // A newer node's producer publishes a reshaped payload.
        executor.deliverSignal(workflowId, SignallingWorkflow.SIGNAL,
                Map.of("reshaped", "by a newer node"));

        awaitRunEnded(workflowId);

        var instance = store.getInstance(workflowId).orElseThrow();
        assertAll(
                () -> assertNotEquals(WorkflowStatus.FAILED, instance.status()),
                () -> assertEquals(0, countOfType(instanceId, EventType.WORKFLOW_FAILED)),
                () -> assertEquals(0, countOfType(instanceId, EventType.COMPENSATION_STARTED),
                        "zero COMPENSATION_STARTED — the workflow registered a compensation "
                                + "before awaiting, so this is 1 if the run is treated as failed"),
                () -> assertEquals(0, compensations.get()),
                () -> assertEquals(1, countOfType(instanceId, EventType.SIGNAL_RECEIVED),
                        "the SIGNAL_RECEIVED event is already durable — an upgraded node "
                                + "replays it through the guarded replay read, so the signal "
                                + "is never lost"));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private UUID startWithWinner(String workflowId, AtomicInteger compensations,
                                 EventType winnerType, JsonNode winnerPayload) {
        var impl = new AdoptingWorkflow(compensations);
        impl.greeter = new ActivityProxyFactory().createProxy(
                Greeter.class, name -> "local-" + name, store, null, null,
                RetryPolicy.defaultPolicy(), Duration.ofSeconds(5), serializer,
                new RetryExecutor());
        // Arm the race before the run starts: the activity's own append at
        // sequence 1 is what loses it. The memoization lookup that precedes
        // that append still sees an empty slot, so the activity really does
        // execute live — which is what makes this the adopt branch and not a
        // replay.
        store.winnerAppearsOnNextAppend(1, winnerType, winnerPayload);
        return executor.startWorkflow(workflowId, "AdoptingWorkflow", "default", null,
                impl, methodOf(AdoptingWorkflow.class));
    }

    private static JsonNode json(String raw) {
        return JsonMapper.builder().build().readTree(raw);
    }

    /** @return the reasons this run stood down for, in order */
    private List<StandDownReason> reasons() {
        return observer.standDowns().stream()
                .map(RecordingEngineObserver.StandDownCall::reason).toList();
    }

    private int countOfType(UUID instanceId, EventType type) {
        return (int) store.getEvents(instanceId).stream()
                .filter(e -> e.eventType() == type).count();
    }

    private void awaitRunEnded(String workflowId) {
        await().atMost(BOUND).until(() -> !executor.isRunning(workflowId));
    }

    private static Method methodOf(Class<?> workflowClass) {
        for (var method : workflowClass.getMethods()) {
            if (method.isAnnotationPresent(WorkflowMethod.class)) {
                return method;
            }
        }
        throw new IllegalStateException("no @WorkflowMethod on " + workflowClass);
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    /** Activity whose {@code String} return type the foreign payload cannot become. */
    @Activity
    public interface Greeter {
        /**
         * @param name whom to greet
         * @return the greeting
         */
        String greet(String name);
    }

    /** Registers a compensation, then runs one activity that loses the append race. */
    @DurableWorkflow(name = "AdoptingWorkflow")
    public static class AdoptingWorkflow {

        Greeter greeter;
        private final AtomicInteger compensations;

        AdoptingWorkflow(AtomicInteger compensations) {
            this.compensations = compensations;
        }

        /** @return the activity result, reached only when the winner is readable */
        @WorkflowMethod
        public String run() {
            WorkflowContext.current().addCompensation(compensations::incrementAndGet);
            return greeter.greet("world");
        }
    }

    /** Takes a typed input the foreign persisted payload cannot become. */
    @DurableWorkflow(name = "InputWorkflow")
    public static class InputWorkflow {

        private volatile boolean ran;

        /**
         * @param input the seed
         * @return the input, echoed
         */
        @WorkflowMethod
        public String run(String input) {
            ran = true;
            return input;
        }

        boolean ran() {
            return ran;
        }
    }

    /** Registers a compensation, then awaits a typed signal. */
    @DurableWorkflow(name = "SignallingWorkflow")
    public static class SignallingWorkflow {

        /** The awaited signal name. */
        public static final String SIGNAL = "approval";

        private final AtomicInteger compensations;

        SignallingWorkflow(AtomicInteger compensations) {
            this.compensations = compensations;
        }

        /** @return the signal payload, reached only when it is readable */
        @WorkflowMethod
        public String run() {
            var workflow = WorkflowContext.current();
            workflow.addCompensation(compensations::incrementAndGet);
            return workflow.awaitSignal(SIGNAL, String.class, Duration.ofSeconds(10));
        }
    }
}
